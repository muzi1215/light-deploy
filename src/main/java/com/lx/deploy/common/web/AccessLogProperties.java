package com.lx.deploy.common.web;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 访问日志配置。
 *
 * <p>只解决一件事：把「前端定时轮询」这类高频只读接口从访问日志里摘出去。日志查看器打开时
 * 每 1.5 秒拉一次 {@code /taskLog/pull}，一个几分钟的任务就是上百行 INFO，真正有价值的
 * 业务请求反而被淹没；但完全不打又会丢掉「接口 500 了」「任务不存在了」这些唯一线索，
 * 所以这里只提供「静默路径」清单，具体降噪口径由 {@code RequestTraceFilter} 决定。</p>
 *
 * <p>配置键为 {@code light-deploy.access-log.silent-paths}，路径前缀匹配（不含
 * {@code server.servlet.context-path}）。新增轮询接口时改配置即可，不必改代码。</p>
 *
 * @author lx
 * @since 2026-09-25 19:00:00
 */
@Data
@Component
@ConfigurationProperties(prefix = "light-deploy.access-log")
public class AccessLogProperties {

    /**
     * 静默路径前缀列表，默认包含任务日志轮询接口。
     *
     * <p>按前缀匹配：配置 {@code /taskLog/pull} 可命中 {@code /taskLog/pull} 及其所有子路径。
     * 前缀写得太短会误伤同前缀的正常接口（如配 {@code /task} 会把全部任务接口静默掉），
     * 因此应尽量写到具体接口。</p>
     */
    private List<String> silentPaths = new ArrayList<>(List.of("/taskLog/pull"));

    /**
     * 判断给定路径是否命中静默清单。
     *
     * @param path 去掉上下文路径后的请求路径，如 {@code /taskLog/pull}
     * @return true 表示该路径属于高频轮询类接口，访问日志应降噪
     */
    public boolean isSilent(String path) {
        if (path == null || silentPaths == null || silentPaths.isEmpty()) {
            return false;
        }
        return silentPaths.stream().anyMatch(prefix -> StringUtils.hasText(prefix) && path.startsWith(prefix));
    }
}
