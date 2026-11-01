package com.lx.deploy.aiops.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.deploy.aiops.mapper.AiopsEventMapper;
import com.lx.deploy.aiops.model.AiopsEvent;
import com.lx.deploy.aiops.model.AiopsEventStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 事件补偿任务：把没送达 AIops 的发布事件补发出去。
 *
 * <p>平台侧推送是「一次失败不阻断发布」的旁路逻辑，因此失败事件只能靠本任务兜底。
 * 只在 {@code light-deploy.aiops.enabled=true} 时注册：没接 AIops 的环境连线程都不该多一条。</p>
 *
 * <p>扫描条件刻意收紧为「有下次重试时间且已到期」：已成功、已放弃、以及还在退避等待中的事件都不捞，
 * 避免把同一条事件在退避期内反复重试，把对方打得更惨。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "light-deploy.aiops", name = "enabled", havingValue = "true")
public class AiopsRetryScheduler {

    /** 单轮最多补发的事件数，防止一次抖动后堆积的事件把一轮扫描拖成分钟级。 */
    private static final int BATCH_SIZE = 50;

    private final AiopsEventMapper eventMapper;
    private final AiopsEventPublisher publisher;

    public AiopsRetryScheduler(AiopsEventMapper eventMapper, AiopsEventPublisher publisher) {
        this.eventMapper = eventMapper;
        this.publisher = publisher;
    }

    /**
     * 扫描并补发到期的失败事件。
     *
     * <p>固定延迟（fixedDelay）而非固定频率：上一轮没跑完不叠加下一轮。
     * 单轮内部逐条处理且每条都有超时上限，不会长时间占用调度线程。</p>
     */
    @Scheduled(fixedDelayString = "#{@aiopsProperties.retryIntervalMs}")
    public void retryPendingEvents() {
        List<AiopsEvent> events;
        try {
            events = eventMapper.selectList(new LambdaQueryWrapper<AiopsEvent>()
                    .in(AiopsEvent::getStatus, AiopsEventStatus.PENDING, AiopsEventStatus.FAILED)
                    .isNotNull(AiopsEvent::getNextRetryTime)
                    .le(AiopsEvent::getNextRetryTime, LocalDateTime.now())
                    .orderByAsc(AiopsEvent::getNextRetryTime)
                    .last("limit " + BATCH_SIZE));
        } catch (Exception e) {
            // 数据库抖动不该让调度线程带着异常跑；下一轮继续。
            log.warn("[AIops] 扫描待补发事件失败：{}", e.getMessage());
            return;
        }
        if (events.isEmpty()) {
            return;
        }
        log.info("[AIops] 开始补发发布事件，本轮 {} 条", events.size());
        for (AiopsEvent event : events) {
            publisher.retry(event);
        }
    }
}
