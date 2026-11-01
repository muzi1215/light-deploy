package com.lx.deploy.aiops.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lx.deploy.aiops.client.AiopsClient;
import com.lx.deploy.aiops.client.AiopsDtos.EnvRef;
import com.lx.deploy.aiops.client.AiopsDtos.EventData;
import com.lx.deploy.aiops.client.AiopsDtos.EventRequest;
import com.lx.deploy.aiops.client.AiopsDtos.ProjectRef;
import com.lx.deploy.aiops.client.AiopsDtos.ServerRef;
import com.lx.deploy.aiops.config.AiopsProperties;
import com.lx.deploy.aiops.mapper.AiopsEventMapper;
import com.lx.deploy.aiops.model.AiopsEvent;
import com.lx.deploy.aiops.model.AiopsEventStatus;
import com.lx.deploy.aiops.model.AiopsEventType;
import com.lx.deploy.aiops.util.AiopsTime;
import com.lx.deploy.deployment.model.Deployment;
import com.lx.deploy.deployment.model.DeploymentTarget;
import com.lx.deploy.project.mapper.EnvironmentMapper;
import com.lx.deploy.project.mapper.ProjectMapper;
import com.lx.deploy.project.model.Environment;
import com.lx.deploy.project.model.Project;
import com.lx.deploy.release.mapper.ReleaseRecordMapper;
import com.lx.deploy.release.model.ReleaseRecord;
import com.lx.deploy.server.model.Server;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 发布事件发布器：把发布生命周期事件可靠地送到 AIops。
 *
 * <p><b>为什么先落库再发：</b>事件是「发布做过什么」的证据链，不能因为一次网络抖动就丢。
 * 每次推送先写 {@code aiops_event}（待发送），再调对端；成功置成功，失败置失败并排下次重试时间。
 * 这样即使平台在发布中途重启，重启后照样能把没送达的事件补发出去（由
 * {@code AiopsRetryScheduler} 扫描）。</p>
 *
 * <p><b>双键设计：</b>{@code eventId} 是本次投递的唯一标识（HTTP 的 Idempotency-Key），
 * {@code idempotencyKey} 是业务幂等键（部署ID + 事件类型 + 服务器）。重试时 {@code eventId}
 * 保持不变——换成新 ID 会让对端把同一条事件当成两条。</p>
 *
 * <p><b>失败隔离：</b>所有对外调用都包在 try/catch 里，本类任何方法都不向外抛异常。
 * 它被部署状态流转调用，AIops 挂掉绝不能连带把「部署成功」写成「部署失败」。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Slf4j
@Service
public class AiopsEventPublisher {

    /** 重试退避（秒）：越晚的事件价值越低，但「发布失败」这类事件必须尽力送到。 */
    private static final int[] BACKOFF_SECONDS = {5, 30, 120, 600, 1800};

    /** 事件报文单独存储后重试时反序列化用；只有字符串与整数，不需要额外模块。 */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final AiopsProperties properties;
    private final AiopsClient client;
    private final AiopsEventMapper eventMapper;
    private final ProjectMapper projectMapper;
    private final EnvironmentMapper environmentMapper;
    private final ReleaseRecordMapper releaseRecordMapper;

    public AiopsEventPublisher(AiopsProperties properties,
                               AiopsClient client,
                               AiopsEventMapper eventMapper,
                               ProjectMapper projectMapper,
                               EnvironmentMapper environmentMapper,
                               ReleaseRecordMapper releaseRecordMapper) {
        this.properties = properties;
        this.client = client;
        this.eventMapper = eventMapper;
        this.projectMapper = projectMapper;
        this.environmentMapper = environmentMapper;
        this.releaseRecordMapper = releaseRecordMapper;
    }

    /**
     * 发布一条发布事件。
     *
     * @param type       事件类型
     * @param deployment 部署主记录
     * @param target     目标级事件对应的目标行；整体事件传 null
     * @param server     目标级事件对应的服务器；整体事件传 null
     * @param detail     面向日志的补充说明（如失败原因），不会进入报文
     */
    public void publish(AiopsEventType type, Deployment deployment, DeploymentTarget target,
                        Server server, String detail) {
        if (!properties.isEnabled() || deployment == null || type == null) {
            return;
        }
        try {
            String eventId = newEventId();
            EventRequest request = buildRequest(type, deployment, target, server, eventId);
            AiopsEvent event = new AiopsEvent();
            event.setDeploymentId(deployment.getId());
            event.setEventId(eventId);
            event.setEventType(type.getCode());
            event.setIdempotencyKey(request.idempotencyKey());
            event.setRequestBody(objectMapper.writeValueAsString(request));
            event.setStatus(AiopsEventStatus.PENDING);
            event.setRetryCount(0);
            eventMapper.insert(event);
            deliver(event, request);
            log.info("[AIops] 事件已上报 - 部署={}, 类型={}, 服务器={}, 结果={}, 说明={}",
                    deployment.getId(), type.getCode(),
                    server == null ? "整体" : server.getHost(), event.getStatus(), detail);
        } catch (Exception e) {
            // 事件管道故障不影响发布结论：这里只留痕，绝不外抛。
            log.warn("[AIops] 事件上报链路异常，已忽略 - 部署={}, 类型={}, 原因={}",
                    deployment.getId(), type == null ? null : type.getCode(), e.getMessage());
        }
    }

    /**
     * 重试一条未送达的事件，由定时任务调用。
     *
     * @param event 待重试事件，请求报文从 {@code request_body} 还原
     */
    public void retry(AiopsEvent event) {
        if (event == null || !StringUtils.hasText(event.getRequestBody())) {
            return;
        }
        if (isExpired(event)) {
            abandon(event, "事件已超过 " + properties.getExpireHours() + " 小时，不再补发");
            return;
        }
        try {
            EventRequest request = objectMapper.readValue(event.getRequestBody(), EventRequest.class);
            deliver(event, request);
        } catch (Exception e) {
            abandon(event, "事件报文无法还原：" + e.getMessage());
        }
    }

    /**
     * 组装事件报文。
     *
     * @param type       事件类型
     * @param deployment 部署主记录
     * @param target     目标行，允许为空
     * @param server     服务器，允许为空
     * @param eventId    本次投递唯一标识，报文体与 HTTP 头必须同值
     * @return 事件报文
     */
    private EventRequest buildRequest(AiopsEventType type, Deployment deployment,
                                      DeploymentTarget target, Server server, String eventId) {
        Project project = projectMapper.selectById(deployment.getProjectId());
        Environment environment = environmentMapper.selectById(deployment.getEnvironmentId());
        ReleaseRecord release = StringUtils.hasText(deployment.getReleaseId())
                ? releaseRecordMapper.selectById(deployment.getReleaseId()) : null;
        String serverId = server == null ? null : server.getId();
        String key = deployment.getId() + ":" + type.getCode() + (serverId == null ? "" : ":" + serverId);
        return new EventRequest(
                eventId,
                type.getCode(),
                deployment.getId(),
                key,
                nextSequence(deployment.getId()),
                project == null ? null : new ProjectRef(project.getCode(), project.getName()),
                environment == null ? null : new EnvRef(environment.getCode(), environment.getName()),
                release == null ? null : release.getVersion(),
                target == null ? null : target.getOldVersion(),
                release == null ? null : release.getCommitId(),
                deployment.getStrategy(),
                server == null ? null : new ServerRef(server.getId(), server.getHost()),
                resolveStatusText(type),
                AiopsTime.iso(null),
                deployment.getOperatorName(),
                deployment.getId());
    }

    /**
     * 事件的简短状态词。
     *
     * <p>对端 {@code status} 列限长 30 字符，因此这里只放固定词，不塞原因；
     * 失败原因在平台侧部署详情里看，不靠这个字段传递。</p>
     *
     * @param type 事件类型
     * @return 状态词
     */
    private String resolveStatusText(AiopsEventType type) {
        return switch (type) {
            case PREPARED -> "PREPARED";
            case STARTED -> "RUNNING";
            case HOST_SUCCESS, SUCCEEDED -> "SUCCESS";
            case HOST_FAILED, FAILED -> "FAILED";
            case SILENCE_CLOSED -> "CLOSED";
            case ROLLBACK_SUCCEEDED -> "ROLLED_BACK";
            case ROLLBACK_FAILED -> "ROLLBACK_FAILED";
            case CANCELLED -> "CANCELED";
        };
    }

    /**
     * 实际投递并落状态。
     *
     * @param event   事件记录
     * @param request 事件报文
     */
    private void deliver(AiopsEvent event, EventRequest request) {
        try {
            EventData data = client.pushEvent(event.getEventId(), request);
            event.setStatus(AiopsEventStatus.SUCCESS);
            event.setErrorMessage(null);
            event.setNextRetryTime(null);
            event.setResponseBody(objectMapper.writeValueAsString(data));
            eventMapper.updateById(event);
        } catch (Exception e) {
            markFailure(event, e.getMessage());
        }
    }

    /**
     * 记录一次失败并排下次重试；重试耗尽或事件过期则置为已放弃。
     *
     * @param event  事件记录
     * @param reason 失败原因
     */
    private void markFailure(AiopsEvent event, String reason) {
        int retried = (event.getRetryCount() == null ? 0 : event.getRetryCount()) + 1;
        event.setRetryCount(retried);
        event.setErrorMessage(AiopsTime.truncate(reason, 500));
        if (retried >= properties.getRetryMax() || isExpired(event)) {
            event.setStatus(AiopsEventStatus.ABANDONED);
            event.setNextRetryTime(null);
            log.warn("[AIops] 事件重试耗尽，已放弃 - 事件={}, 类型={}, 次数={}, 原因={}",
                    event.getEventId(), event.getEventType(), retried, reason);
        } else {
            event.setStatus(AiopsEventStatus.FAILED);
            int index = Math.min(retried - 1, BACKOFF_SECONDS.length - 1);
            event.setNextRetryTime(LocalDateTime.now().plusSeconds(BACKOFF_SECONDS[index]));
            log.warn("[AIops] 事件投递失败，{} 秒后重试 - 事件={}, 类型={}, 第{}次, 原因={}",
                    BACKOFF_SECONDS[index], event.getEventId(), event.getEventType(), retried, reason);
        }
        eventMapper.updateById(event);
    }

    /**
     * 直接放弃一条事件（报文损坏、已过期）。
     *
     * @param event  事件记录
     * @param reason 放弃原因
     */
    private void abandon(AiopsEvent event, String reason) {
        event.setStatus(AiopsEventStatus.ABANDONED);
        event.setNextRetryTime(null);
        event.setErrorMessage(AiopsTime.truncate(reason, 500));
        eventMapper.updateById(event);
        log.warn("[AIops] 事件已放弃 - 事件={}, 类型={}, 原因={}",
                event.getEventId(), event.getEventType(), reason);
    }

    /**
     * 事件是否已过期。
     *
     * <p>过期事件不再补发：发布早已结束，此时把「发布开始」推到对方反而会污染告警归因时序。</p>
     *
     * @param event 事件记录
     * @return true 表示超过有效期
     */
    private boolean isExpired(AiopsEvent event) {
        LocalDateTime createdAt = event.getCreateTime();
        return createdAt != null && createdAt.plusHours(properties.getExpireHours()).isBefore(LocalDateTime.now());
    }

    /**
     * 本次部署内的事件序号。
     *
     * @param deploymentId 部署主键
     * @return 从 1 开始的单调递增序号
     */
    private int nextSequence(String deploymentId) {
        Long count = eventMapper.selectCount(new LambdaQueryWrapper<AiopsEvent>()
                .eq(AiopsEvent::getDeploymentId, deploymentId));
        return (int) (count == null ? 1 : count + 1);
    }

    /**
     * 生成投递唯一标识。
     *
     * @return 32 位无连字符 UUID
     */
    private String newEventId() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
