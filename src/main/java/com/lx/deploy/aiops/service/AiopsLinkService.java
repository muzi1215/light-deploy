package com.lx.deploy.aiops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.aiops.client.AiopsClient;
import com.lx.deploy.aiops.client.AiopsDtos.SilenceDetail;
import com.lx.deploy.aiops.config.AiopsProperties;
import com.lx.deploy.aiops.mapper.AiopsEventMapper;
import com.lx.deploy.aiops.model.AiopsEvent;
import com.lx.deploy.aiops.model.AiopsEventStatus;
import com.lx.deploy.aiops.model.AiopsEventType;
import com.lx.deploy.aiops.model.vo.AiopsEventVo;
import com.lx.deploy.aiops.model.vo.AiopsLinkVo;
import com.lx.deploy.aiops.model.vo.AiopsReconcileVo;
import com.lx.deploy.aiops.model.vo.AiopsSilenceVo;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.common.exception.DeployException;
import com.lx.deploy.common.response.ResponseCode;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.model.Environment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 发布联动的只读展示服务。
 *
 * <p>把「这次发布在 AIops 侧做了什么」拼成页面能直接渲染的对象：静默窗（1 条）+ 事件流水（多条，
 * 条数与公式见 {@code docs/AIops联动.md} §4）。数据来自三处，缺任何一处都要能解释清楚：</p>
 *
 * <ul>
 *   <li>本地 {@code deployment.aiops_silence_id}：只存窗口标识；</li>
 *   <li>本地 {@code aiops_event}：事件流水，含投递状态与失败原因；</li>
 *   <li>AIops {@code GET /openapi/v1/silences/{id}}：窗口详情，远程只读。</li>
 * </ul>
 *
 * <p><b>为什么从 `request_body` 里取序号与主机：</b>{@code aiops_event} 是「发送队列」，
 * 只把投递必需的东西落成列（类型、幂等键、状态），事件内容整体存 JSON 快照。
 * 展示层需要序号和主机时读快照即可，不为展示去改表结构——加列意味着一次手工 DDL，
 * 而这份数据本来就是原始报文，解析它比多一列更不容易失真。</p>
 *
 * <p><b>只读、不参与发布决策</b>：远程查询发生在人看页面时，与发布流程无关，
 * 失败只把原因写进 {@code silenceMessage}，绝不抛给前端——排障最需要这个页面的时候，
 * 往往正是 AIops 不可用的时候。</p>
 *
 * @author lx
 * @since 2026-11-02 03:10:00
 */
@Slf4j
@Service
public class AiopsLinkService {

    /** 只用于读回自己写的事件快照，不需要 Spring 的全局配置（与写侧的用法一致）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiopsProperties properties;
    private final AiopsClient client;
    private final DeploymentMapper deploymentMapper;
    private final EnvironmentMapper environmentMapper;
    private final AiopsEventMapper eventMapper;

    public AiopsLinkService(AiopsProperties properties,
                            AiopsClient client,
                            DeploymentMapper deploymentMapper,
                            EnvironmentMapper environmentMapper,
                            AiopsEventMapper eventMapper) {
        this.properties = properties;
        this.client = client;
        this.deploymentMapper = deploymentMapper;
        this.environmentMapper = environmentMapper;
        this.eventMapper = eventMapper;
    }

    /**
     * 查询一次发布的联动全景。
     *
     * @param deploymentId 部署主键
     * @return 静默窗 + 事件流水；各段可独立为空
     * @throws DeployException 部署记录不存在时
     */
    public AiopsLinkVo getByDeployment(String deploymentId) {
        Deployment deployment = deploymentMapper.selectById(deploymentId);
        if (deployment == null) {
            throw new DeployException(ResponseCode.DATA_NOT_FOUND, "部署记录不存在：" + deploymentId);
        }
        AiopsLinkVo vo = new AiopsLinkVo();
        vo.setDeploymentId(deploymentId);
        vo.setGlobalEnabled(properties.isEnabled());
        Environment environment = deployment.getEnvironmentId() == null
                ? null : environmentMapper.selectById(deployment.getEnvironmentId());
        vo.setEnvironmentAiopsEnabled(environment != null && environment.getAiopsEnabled() != null
                && environment.getAiopsEnabled() == 1);
        vo.setSilenceId(deployment.getAiopsSilenceId());
        fillSilence(vo, deployment);
        List<AiopsEventVo> events = listEvents(deploymentId);
        vo.setEvents(events);
        vo.setSilenceReconcile(resolveReconcile(deployment, events));
        return vo;
    }

    /**
     * 计算补关对账状态。
     *
     * <p>判定条件与 {@link AiopsSilenceReconciler} 的候选条件逐条对齐（终态、有窗口、
     * 没「静默已关」事件、终态稳定超过宽限、在回看时间窗内）。对齐是刻意的：页面显示
     * 「补关中」就等于「下一轮扫描会处理它」，不会出现「页面说在补，任务其实不管」的错位。</p>
     *
     * @param deployment 部署记录
     * @param events     本次发布的事件流水（已在内存里，不再多查一次）
     * @return 对账状态；间隔与宽限恒定返回，供页面把「多久内会重试」说清楚
     */
    private AiopsReconcileVo resolveReconcile(Deployment deployment, List<AiopsEventVo> events) {
        AiopsReconcileVo vo = new AiopsReconcileVo();
        vo.setRetryIntervalSeconds(properties.getSilenceReconcileIntervalSeconds());
        vo.setGraceSeconds(AiopsSilenceReconciler.GRACE_SECONDS);
        vo.setPending(isAwaitingClose(deployment, events));
        return vo;
    }

    /**
     * 是否正在等待补关。
     *
     * <p>总闸关闭时恒为 false：对账任务靠 {@code @ConditionalOnProperty} 注册，总闸一关就没有
     * 「稍后会自动关」这回事，此时给乐观提示等于骗人。</p>
     *
     * @param deployment 部署记录
     * @param events     事件流水
     * @return true 表示窗口未关且确定会被补关
     */
    private boolean isAwaitingClose(Deployment deployment, List<AiopsEventVo> events) {
        if (!properties.isEnabled() || !StringUtils.hasText(deployment.getAiopsSilenceId())) {
            return false;
        }
        if (!DeploymentStatus.isFinal(deployment.getStatus())) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        // 超出回看时间窗的部署，对账任务根本不会扫到，不能提示「等待补关」
        LocalDateTime since = now.minusHours(Math.max(1, properties.getSilenceReconcileWindowHours()));
        if (deployment.getCreateTime() == null || deployment.getCreateTime().isBefore(since)) {
            return false;
        }
        // 终态刚稳定不久：可能正在收尾关窗，先按正常流程看，别抢答
        if (deployment.getUpdateTime() == null
                || deployment.getUpdateTime().isAfter(now.minusSeconds(AiopsSilenceReconciler.GRACE_SECONDS))) {
            return false;
        }
        return !hasClosedEvent(events);
    }

    /**
     * 事件流水里是否已有「静默已关」。
     *
     * <p>与对账任务同一套判据：事件落库即「已关」标记（零 DDL）。这里读的是已加载的列表，
     * 不再多查一次库。</p>
     *
     * @param events 事件流水
     * @return true 表示已记录关窗
     */
    private boolean hasClosedEvent(List<AiopsEventVo> events) {
        String closedCode = AiopsEventType.SILENCE_CLOSED.getCode();
        for (AiopsEventVo event : events) {
            if (closedCode.equals(event.getEventType())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 填充静默窗详情。
     *
     * <p>三档短路，顺序即「为什么没有窗口」的解释优先级：总闸关 → 没建过窗 → 远程查不到。
     * 前两档是配置事实，第三档才是异常，因此只有第三档会记 WARN。</p>
     *
     * @param vo         待填充的聚合对象
     * @param deployment 部署记录
     */
    private void fillSilence(AiopsLinkVo vo, Deployment deployment) {
        if (!properties.isEnabled()) {
            vo.setSilenceMessage("平台 AIops 联动总闸未开启（light-deploy.aiops.enabled=false），本次发布不建静默窗");
            return;
        }
        if (!StringUtils.hasText(deployment.getAiopsSilenceId())) {
            vo.setSilenceMessage(Boolean.TRUE.equals(vo.getEnvironmentAiopsEnabled())
                    ? "本次发布未创建静默窗（可能建窗失败并按 fail-open 降级，详见下方事件与任务日志）"
                    : "该环境未开启 AIops 联动，本次发布不建静默窗");
            return;
        }
        try {
            SilenceDetail detail = client.getSilence(deployment.getAiopsSilenceId());
            vo.setSilence(toSilenceVo(detail));
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            log.warn("[AIops] 查询静默窗详情失败 - 部署={}, 窗口={}, 原因={}",
                    deployment.getId(), deployment.getAiopsSilenceId(), reason);
            vo.setSilenceMessage("静默窗详情查询失败（" + reason
                    + "），窗口本身不受影响，会按自身到期时间失效");
        }
    }

    /**
     * 查询事件流水，按序号升序。
     *
     * <p>排序依据是快照里的 `sequence`，不是 `create_time`：同一秒内会连续落多条
     * （PREPARED/STARTED、SUCCEEDED/SILENCE_CLOSED），只按时间排序结果不稳定，页面时间线会跳序。
     * 取不到序号的历史数据按落库顺序兜底，保证前端总能稳定排序。</p>
     *
     * @param deploymentId 部署主键
     * @return 事件展示对象列表
     */
    private List<AiopsEventVo> listEvents(String deploymentId) {
        List<AiopsEvent> events = eventMapper.selectList(new LambdaQueryWrapper<AiopsEvent>()
                .eq(AiopsEvent::getDeploymentId, deploymentId)
                .orderByAsc(AiopsEvent::getCreateTime));
        List<AiopsEventVo> result = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            result.add(toEventVo(events.get(i), i + 1));
        }
        result.sort((a, b) -> Integer.compare(a.getSequence(), b.getSequence()));
        return result;
    }

    /**
     * 事件实体转展示对象。
     *
     * @param event    事件实体
     * @param fallback 快照里取不到序号时使用的兜底序号（落库顺序）
     * @return 展示对象
     */
    private AiopsEventVo toEventVo(AiopsEvent event, int fallback) {
        JsonNode payload = parsePayload(event);
        AiopsEventVo vo = new AiopsEventVo();
        vo.setId(event.getId());
        vo.setEventType(event.getEventType());
        AiopsEventType type = AiopsEventType.fromCode(event.getEventType());
        vo.setEventTypeLabel(type == null ? event.getEventType() : type.getLabel());
        vo.setIdempotencyKey(event.getIdempotencyKey());
        vo.setStatus(event.getStatus());
        vo.setStatusText(describeEventStatus(event.getStatus()));
        vo.setRetryCount(event.getRetryCount());
        vo.setNextRetryTime(event.getNextRetryTime());
        vo.setErrorMessage(event.getErrorMessage());
        vo.setOccurredAt(event.getCreateTime());
        vo.setDeliveredAt(event.getStatus() != null && event.getStatus() == AiopsEventStatus.SUCCESS
                ? event.getUpdateTime() : null);
        vo.setSequence(resolveSequence(payload, fallback));
        JsonNode server = payload == null ? null : payload.get("server");
        if (server != null && !server.isNull()) {
            vo.setServerId(textOf(server.get("serverId")));
            vo.setHost(textOf(server.get("host")));
        }
        return vo;
    }

    /**
     * 解析事件报文快照。
     *
     * @param event 事件实体
     * @return JSON 树；快照为空或损坏时返回 null（属于历史脏数据，不阻断页面）
     */
    private JsonNode parsePayload(AiopsEvent event) {
        if (!StringUtils.hasText(event.getRequestBody())) {
            return null;
        }
        try {
            return MAPPER.readTree(event.getRequestBody());
        } catch (Exception e) {
            log.debug("[AIops] 事件快照解析失败，按落库顺序兜底 - 事件={}, 原因={}",
                    event.getId(), e.getMessage());
            return null;
        }
    }

    /**
     * 从快照取事件序号。
     *
     * @param payload  报文快照
     * @param fallback 兜底序号
     * @return 序号
     */
    private int resolveSequence(JsonNode payload, int fallback) {
        JsonNode node = payload == null ? null : payload.get("sequence");
        return node == null || !node.canConvertToInt() ? fallback : node.asInt();
    }

    /**
     * 取节点文本值，缺失返回 null。
     *
     * @param node JSON 节点
     * @return 文本
     */
    private String textOf(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 静默窗详情转展示对象。
     *
     * @param detail 对端返回的详情
     * @return 展示对象；入参为空时返回 null
     */
    private AiopsSilenceVo toSilenceVo(SilenceDetail detail) {
        if (detail == null) {
            return null;
        }
        AiopsSilenceVo vo = new AiopsSilenceVo();
        vo.setSilenceId(detail.silenceId());
        vo.setStatus(detail.status());
        vo.setExpired(detail.expired());
        vo.setStatusText(describeSilenceStatus(detail.status(), detail.expired()));
        vo.setStartAt(detail.startAt());
        vo.setExpiresAt(detail.expiresAt());
        vo.setCloseTime(detail.closeTime());
        vo.setCloseReason(detail.closeReason());
        vo.setProjectCode(detail.projectCode());
        vo.setEnvironmentCode(detail.environmentCode());
        vo.setHosts(detail.hosts());
        vo.setServerIds(detail.serverIds());
        vo.setAlertCategories(detail.alertCategories());
        vo.setSource(detail.source());
        return vo;
    }

    /**
     * 静默窗状态文案。
     *
     * <p>「已过期」不入库（对端按到期时间实时算），所以必须先看 `expired` 再看 `status`，
     * 否则会把一个早已到期的窗口显示成「生效中」。</p>
     *
     * @param status  状态编码
     * @param expired 是否已过期
     * @return 中文文案
     */
    private String describeSilenceStatus(Integer status, Boolean expired) {
        if (Boolean.TRUE.equals(expired)) {
            return "已过期";
        }
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "生效中";
            case 1 -> "已关闭";
            case 2 -> "已过期";
            default -> "未知(" + status + ")";
        };
    }

    /**
     * 事件投递状态文案。
     *
     * @param status 状态编码
     * @return 中文文案
     */
    private String describeEventStatus(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case AiopsEventStatus.PENDING -> "待发送";
            case AiopsEventStatus.SUCCESS -> "已送达";
            case AiopsEventStatus.FAILED -> "待重试";
            case AiopsEventStatus.ABANDONED -> "已放弃";
            default -> "未知(" + status + ")";
        };
    }
}
