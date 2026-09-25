package com.lx.deploy.deployment.executor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 健康检查探测器。
 *
 * <p>在<b>平台侧</b>对目标服务器发起探测：HTTP 方式请求 {@code http://host:port/path} 并把 2xx 视为健康，
 * TCP 方式只验证端口可连通。选择平台侧而不是「在目标机上 curl」的理由：
 * 探测逻辑与超时控制完全由平台掌握，不依赖目标机是否安装 curl / nc，
 * 也避免远程命令输出解析带来的不确定性；代价是平台需要能访问到目标机的业务端口。</p>
 *
 * <p>探测结果只做观测，不改变发布动作本身是否成功：判定与落库由 DeployTaskHandler 决定
 * （不健康会把目标判为失败，但原因明确写为健康检查不通过）。</p>
 *
 * @author lx
 * @since 2026-09-24 22:30:00
 */
@Slf4j
@Component
public class HealthChecker {

    /** 单次探测默认的等待毫秒数，配置缺失或非法时使用。 */
    private static final int DEFAULT_TIMEOUT_MS = 3000;

    /**
     * 按配置对目标主机执行健康检查。
     *
     * <p>在最大探测次数内只要有一次通过即返回健康，避免应用启动慢导致误判；
     * 每次探测之间等待配置间隔。全部失败时返回不健康，并带上最后一次的失败细节。</p>
     *
     * @param config 健康检查配置，不允许为 null
     * @param host   目标服务器地址（IP 或域名）
     * @return 健康检查结果，含是否健康与面向日志/页面的细节说明
     */
    public HealthCheckResult check(HealthConfig config, String host) {
        int attempts = config.getRetryCount() == null || config.getRetryCount() < 1
                ? 1 : config.getRetryCount();
        long intervalMs = config.getIntervalSeconds() == null || config.getIntervalSeconds() < 0
                ? 0L : config.getIntervalSeconds() * 1000L;
        long timeoutMs = config.getTimeoutSeconds() == null || config.getTimeoutSeconds() < 1
                ? DEFAULT_TIMEOUT_MS : config.getTimeoutSeconds() * 1000L;

        String lastDetail = "未执行探测";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            ProbeResult result = probe(config, host, timeoutMs);
            lastDetail = result.detail();
            if (result.healthy()) {
                return new HealthCheckResult(true, "第 " + attempt + "/" + attempts + " 次探测通过");
            }
            // 最后一次失败后不再等待，避免为每个失败目标都多等一个间隔。
            if (attempt < attempts && intervalMs > 0) {
                sleepQuietly(intervalMs);
            }
        }
        return new HealthCheckResult(false, lastDetail);
    }

    /**
     * 执行一次探测。
     *
     * @param config    健康检查配置
     * @param host      目标服务器地址
     * @param timeoutMs 单次探测超时毫秒数
     * @return 本次探测结果
     */
    private ProbeResult probe(HealthConfig config, String host, long timeoutMs) {
        if (isTcp(config)) {
            return tcpProbe(host, config.getPort(), timeoutMs);
        }
        return httpProbe(buildUrl(config, host), timeoutMs);
    }

    /**
     * 判断是否为 TCP 探测。
     *
     * @param config 健康检查配置
     * @return true 表示 type 为 TCP（忽略大小写）
     */
    private boolean isTcp(HealthConfig config) {
        return config.getType() != null && "TCP".equalsIgnoreCase(config.getType().trim());
    }

    /**
     * 拼装 HTTP 探测地址。
     *
     * @param config 健康检查配置
     * @param host   目标服务器地址
     * @return 形如 http://host:8080/health 的探测地址
     */
    private String buildUrl(HealthConfig config, String host) {
        int port = config.getPort() == null ? 80 : config.getPort();
        String path = config.getPath() == null || config.getPath().isBlank() ? "/" : config.getPath().trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        return "http://" + host + ":" + port + path;
    }

    /**
     * HTTP 探测：2xx 视为健康。
     *
     * @param url       探测地址
     * @param timeoutMs 超时毫秒数
     * @return 本次探测结果；非 2xx 或异常时返回不健康并带上状态码/异常摘要
     */
    private ProbeResult httpProbe(String url, long timeoutMs) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            return code >= 200 && code < 300
                    ? new ProbeResult(true, "HTTP " + code)
                    : new ProbeResult(false, "HTTP 状态码 " + code + "，期望 2xx");
        } catch (Exception e) {
            return new ProbeResult(false, "HTTP 探测失败：" + safeMessage(e));
        }
    }

    /**
     * TCP 探测：端口可连通即健康。
     *
     * @param host      目标服务器地址
     * @param port      目标端口，为空时视为配置错误
     * @param timeoutMs 超时毫秒数
     * @return 本次探测结果
     */
    private ProbeResult tcpProbe(String host, Integer port, long timeoutMs) {
        if (port == null || port < 1 || port > 65535) {
            return new ProbeResult(false, "TCP 探测未配置合法端口");
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) timeoutMs);
            return new ProbeResult(true, "TCP " + port + " 可连通");
        } catch (IOException e) {
            return new ProbeResult(false, "TCP " + port + " 连接失败：" + safeMessage(e));
        }
    }

    /**
     * 等待指定毫秒数，中断时恢复中断标记并静默返回。
     *
     * @param millis 等待毫秒数
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 提取异常消息，避免把异常类名或堆栈写进日志/数据库。
     *
     * @param e 原始异常
     * @return 简短的异常消息；为空时返回异常类名
     */
    private String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * 单次探测结果。
     *
     * @param healthy 是否健康
     * @param detail  结果细节，面向日志与页面展示
     */
    private record ProbeResult(boolean healthy, String detail) {
    }

    /**
     * 健康检查结果。
     *
     * @param healthy 是否健康
     * @param detail  结果细节，面向日志与页面展示
     */
    public record HealthCheckResult(boolean healthy, String detail) {
    }
}
