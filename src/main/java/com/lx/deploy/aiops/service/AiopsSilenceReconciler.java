package com.lx.deploy.aiops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.aiops.config.AiopsProperties;
import com.lx.deploy.aiops.mapper.AiopsEventMapper;
import com.lx.deploy.aiops.model.AiopsEvent;
import com.lx.deploy.aiops.model.AiopsEventType;
import com.lx.deploy.aiops.service.AiopsSilenceService.CloseOutcome;
import com.lx.deploy.common.constants.DeploymentStatus;
import com.lx.deploy.deployment.mapper.DeploymentMapper;
import com.lx.deploy.deployment.model.Deployment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 静默窗补关对账任务：把「部署已结束、窗口却还开着」的记录补关掉。
 *
 * <p><b>为什么需要它：</b>发布收尾的关窗是一次性同步 RPC
 * （{@link AiopsSilenceService#closeForDeployment}），失败只记日志、不重试；而
 * {@code SILENCE_CLOSED} 事件又只在关窗成功后才发布。于是当发布目标恰好是 AIops 自身时，
 * 收尾关窗撞上 AIops 容器重启，必然失败——窗口会一直「生效中」直到 TTL（默认 15 分钟）。
 * 这期间该环境/主机的「服务异常」告警仍被抑制，反而可能掩盖「AIops 没起来」这种最该报警的情况。</p>
 *
 * <p><b>它怎么补：</b>定期扫描「已终态 + 有过静默窗 + 最近 N 小时」的部署；若该部署还没有
 * {@code SILENCE_CLOSED} 事件，就再调一次关窗（AIops 侧 close 接口幂等，已关闭会幂等回放），
 * 成功后再补发 {@code SILENCE_CLOSED}。事件一经发布即落库，下一轮扫描看到事件存在便跳过，
 * 因此不会重复补关。</p>
 *
 * <p><b>为什么用「事件是否存在」判定是否已关：</b>关窗成功必定紧跟着发布该事件，事件落库是可靠
 * 标记；这样无需新增表或字段（零 DDL），也不会因为平台重启丢掉进度。若事件只是还没送达，
 * 由 {@link AiopsRetryScheduler} 继续补投，本任务不重复关窗。</p>
 *
 * <p><b>为什么留 2 分钟宽限：</b>收尾关窗与本任务可能几乎同时发生。以部署的 {@code update_time}
 * 为界，只处理「终态已稳定 2 分钟」的记录，避开与正常收尾路径并发导致的重复事件。</p>
 *
 * <p>只在 {@code light-deploy.aiops.enabled=true} 时注册：没接 AIops 的环境连线程都不该多一条。</p>
 *
 * @author lx
 * @since 2026-11-03
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "light-deploy.aiops", name = "enabled", havingValue = "true")
public class AiopsSilenceReconciler {

    /** 单轮最多处理的部署数，避免一次抖动后堆积把一轮扫描拖成分钟级。 */
    private static final int BATCH_SIZE = 50;

    /**
     * 终态稳定宽限（秒）：避开与收尾关窗并发，防止同一次部署发出两条「静默已关」。
     *
     * <p>公开给联动详情页复用：页面提示「补关中」的判定必须与这里的候选条件一致，
     * 否则会出现「页面说在补关、任务其实还没轮到它」这种自相矛盾。</p>
     */
    public static final int GRACE_SECONDS = 120;

    private final AiopsProperties properties;
    private final DeploymentMapper deploymentMapper;
    private final AiopsEventMapper eventMapper;
    private final AiopsSilenceService silenceService;
    private final AiopsEventPublisher eventPublisher;

    public AiopsSilenceReconciler(AiopsProperties properties,
                                  DeploymentMapper deploymentMapper,
                                  AiopsEventMapper eventMapper,
                                  AiopsSilenceService silenceService,
                                  AiopsEventPublisher eventPublisher) {
        this.properties = properties;
        this.deploymentMapper = deploymentMapper;
        this.eventMapper = eventMapper;
        this.silenceService = silenceService;
        this.eventPublisher = eventPublisher;
    }

    /**
     * 扫描并补关遗留的静默窗。
     *
     * <p>固定延迟（fixedDelay）而非固定频率：上一轮没跑完不叠加下一轮。单轮逐条处理，
     * 单条失败只记日志、下一轮继续，不会让一个坏记录卡住整轮。</p>
     */
    @Scheduled(fixedDelayString = "#{@aiopsProperties.silenceReconcileIntervalMs}")
    public void reconcileSilences() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime since = now.minusHours(Math.max(1, properties.getSilenceReconcileWindowHours()));
        LocalDateTime stableBefore = now.minusSeconds(GRACE_SECONDS);

        List<Deployment> candidates;
        try {
            // 只扫终态 + 开过窗 + 最近 N 小时 + 终态已稳定 2 分钟的记录
            candidates = deploymentMapper.selectList(new LambdaQueryWrapper<Deployment>()
                    .isNotNull(Deployment::getAiopsSilenceId)
                    .in(Deployment::getStatus,
                            DeploymentStatus.SUCCESS,
                            DeploymentStatus.FAILED,
                            DeploymentStatus.CANCELED,
                            DeploymentStatus.ROLLED_BACK)
                    .ge(Deployment::getCreateTime, since)
                    .le(Deployment::getUpdateTime, stableBefore)
                    .orderByDesc(Deployment::getUpdateTime)
                    .last("limit " + BATCH_SIZE));
        } catch (Exception e) {
            // 数据库抖动不该让调度线程带着异常跑；下一轮继续。
            log.warn("[AIops] 扫描待补关静默窗失败：{}", e.getMessage());
            return;
        }
        if (candidates.isEmpty()) {
            return;
        }

        int closed = 0;
        for (Deployment deployment : candidates) {
            // 已有「静默已关」事件 → 正常收尾已处理过（或已补过），跳过
            if (hasClosedEvent(deployment.getId())) {
                continue;
            }
            String reason = closeReason(deployment.getStatus());
            CloseOutcome outcome = silenceService.closeForDeployment(deployment.getId(), reason);
            if (!outcome.hadWindow()) {
                continue;
            }
            if (outcome.closed()) {
                // 真正关成功后才补发事件；事件落库即成为「已补关」标记
                eventPublisher.publish(AiopsEventType.SILENCE_CLOSED, deployment, null, null,
                        reason + "（平台补关）");
                closed++;
                log.info("[AIops] 静默窗补关成功 - 部署={}, 窗口={}, 原因={}",
                        deployment.getId(), outcome.silenceId(), reason);
            } else {
                log.warn("[AIops] 静默窗补关失败，下一轮继续 - 部署={}, 窗口={}",
                        deployment.getId(), outcome.silenceId());
            }
        }
        if (closed > 0) {
            log.info("[AIops] 本轮补关静默窗 {} 个（候选 {} 个）", closed, candidates.size());
        }
    }

    /**
     * 判断某次部署是否已经产生「静默已关」事件。
     *
     * <p>查询异常时按「已关」处理并跳过本条：宁可少补一次（下一轮还会再判），
     * 也不要在查询不可靠时重复关窗、发重复事件。</p>
     *
     * @param deploymentId 部署主键
     * @return true 表示已正常关窗或已补关过，无需重复处理
     */
    private boolean hasClosedEvent(String deploymentId) {
        try {
            Long count = eventMapper.selectCount(new LambdaQueryWrapper<AiopsEvent>()
                    .eq(AiopsEvent::getDeploymentId, deploymentId)
                    .eq(AiopsEvent::getEventType, AiopsEventType.SILENCE_CLOSED.getCode()));
            return count != null && count > 0;
        } catch (Exception e) {
            log.warn("[AIops] 查询静默窗关闭事件失败，本轮跳过 - 部署={}, 原因={}",
                    deploymentId, e.getMessage());
            return true;
        }
    }

    /**
     * 按部署终态给出关窗原因。
     *
     * @param status 部署状态编码，取值见 {@link DeploymentStatus}
     * @return 中文关窗原因，写入 AIops 静默窗留痕
     */
    private String closeReason(Integer status) {
        if (status == null) {
            return "补关静默窗";
        }
        return switch (status) {
            case DeploymentStatus.SUCCESS -> "发布成功";
            case DeploymentStatus.FAILED -> "发布失败";
            case DeploymentStatus.CANCELED -> "已取消";
            case DeploymentStatus.ROLLED_BACK -> "回滚成功";
            default -> "补关静默窗";
        };
    }
}
