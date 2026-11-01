package com.lx.deploy.aiops.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * AIops 联动的调度开关。
 *
 * <p>平台其余模块没有定时任务，{@code @EnableScheduling} 因此只为事件补发而开，
 * 并且跟随 {@code light-deploy.aiops.enabled}：未接入 AIops 的环境不会因为本模块
 * 凭空拉起一个调度线程池。</p>
 *
 * <p>注意 {@code @EnableScheduling} 默认注册单线程调度器，这对「几十条事件补发」完全够用；
 * 若将来出现真正耗时的周期任务，应另建 {@code TaskScheduler} 并显式指定线程数，
 * 而不是把这里改成并发执行——同一批事件被两个线程重复投递没有收益。</p>
 *
 * @author lx
 * @since 2026-11-02 00:40:00
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "light-deploy.aiops", name = "enabled", havingValue = "true")
public class AiopsSchedulingConfig {
}
