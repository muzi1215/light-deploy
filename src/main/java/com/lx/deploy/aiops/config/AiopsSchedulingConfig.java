package com.lx.deploy.aiops.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * AIops 联动的调度开关与线程池。
 *
 * <p>平台其余模块没有定时任务，{@code @EnableScheduling} 因此只为 AIops 联动而开，
 * 并且跟随 {@code light-deploy.aiops.enabled}：未接入 AIops 的环境不会因为本模块
 * 凭空拉起一个调度线程池。</p>
 *
 * <p><b>为什么需要显式线程池：</b>本模块有两个周期任务——{@code AiopsRetryScheduler}（事件补发）
 * 与 {@code AiopsSilenceReconciler}（静默窗补关对账）。后者要串行发 HTTP（建连 3s / 读 5s），
 * 一批 50 条全超时最坏能占住调度线程数分钟。若沿用默认单线程调度器，补关会把事件补发饿死，
 * 反过来静默窗事件也跟着积压。这里固定 2 个线程，让两条任务互不阻塞。</p>
 *
 * <p>线程数不必再放大：两个任务各自都是 {@code fixedDelay}（上一轮跑完才排下一轮），
 * 天然不会自我重叠；同一批事件被两个线程重复投递也没有收益。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "light-deploy.aiops", name = "enabled", havingValue = "true")
public class AiopsSchedulingConfig {

    private static final Logger log = LoggerFactory.getLogger(AiopsSchedulingConfig.class);

    /**
     * AIops 专用调度器。
     *
     * <p>Spring 会优先使用容器内唯一的 {@code TaskScheduler} Bean 来执行 {@code @Scheduled} 任务，
     * 因此这里定义的实例会整体替换掉默认的单线程调度器。</p>
     *
     * <p>{@code setErrorHandler} 兜住漏网的运行时异常：单个周期任务抛异常不该把调度线程带走，
     * 只记 ERROR 并在下一轮继续。</p>
     *
     * @return 固定 2 线程的调度器
     */
    @Bean(destroyMethod = "shutdown")
    public ThreadPoolTaskScheduler aiopsTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("aiops-sched-");
        // 停机时给正在跑的补关/补发留出收尾时间，避免写到一半被强杀
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(10);
        scheduler.setErrorHandler(e -> log.error("[AIops] 调度任务异常：{}", e.getMessage(), e));
        scheduler.initialize();
        return scheduler;
    }
}
