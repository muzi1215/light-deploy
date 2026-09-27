package com.lx.deploy.deployment.executor;

import com.lx.deploy.server.model.Server;
import com.lx.deploy.ssh.SshCommandResult;
import com.lx.deploy.ssh.SshSession;
import com.lx.deploy.ssh.SshSessionFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 健康检查探测器，支持两种探测位置。
 *
 * <p><b>{@code TARGET}（默认）：在目标机本地探测。</b>通过已配置的 SSH 连接在目标机上执行
 * 端口监听检查（{@code ss}）或 HTTP 请求（python3），判断「服务有没有起来」。
 * 选它做默认的理由：发布后健康检查的唯一目的是确认应用活了，而这个事实只取决于目标机自身，
 * 不该额外依赖「平台机器 → 业务端口」这段网络。否则会出现应用明明在跑、
 * 健康检查却因防火墙/安全组未放行而失败，把网络问题误报成发布问题——
 * 平台为此还得要求业务端口对平台放行，这既不是部署的必要条件，也扩大了暴露面。</p>
 *
 * <p><b>{@code PLATFORM}：从平台侧探测。</b>由平台 JVM 直接对 {@code server.host:port} 发起连接，
 * 验证的是「客户端能不能访问到」，适合确实要校验外部可达性的场景。
 * 代价是平台必须能访问业务端口，且结论受平台所在网络的出口影响——
 * 在开发机上跑平台得到的结论，与平台部署到服务器上跑不一定一致。</p>
 *
 * <p>早期只在平台侧探测的理由是「不依赖目标机安装 curl / nc」，该理由已不成立：
 * 目标机必然有 python3（JAR 部署脚本本身就是 python 的，初始化阶段还会显式探测），
 * 且 bash 内置的 {@code /dev/tcp} 零依赖。既然 SSH 连接与凭据本来就有，
 * 走目标机本地既不增加依赖，也不受防火墙干扰。</p>
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

    /** 探测位置取值：从平台侧探测；其余取值或缺省均按目标机本地探测处理。 */
    private static final String PROBE_PLATFORM = "PLATFORM";

    /** SSH 会话工厂：{@code TARGET} 模式下用于在目标机上执行探测命令。 */
    private final SshSessionFactory sshSessionFactory;

    /**
     * 创建健康检查探测器。
     *
     * @param sshSessionFactory SSH 会话工厂，{@code TARGET} 模式必需
     */
    public HealthChecker(SshSessionFactory sshSessionFactory) {
        this.sshSessionFactory = sshSessionFactory;
    }

    /**
     * 按配置对目标服务器执行健康检查。
     *
     * <p>在最大探测次数内只要有一次通过即返回健康，避免应用启动慢导致误判；
     * 每次探测之间等待配置间隔。全部失败时返回不健康，并带上最后一次的失败细节。</p>
     *
     * <p>{@code TARGET} 模式下整个重试过程复用同一条 SSH 连接：
     * 一次握手即可完成多轮探测，避免把重试次数放大成同等数量的 SSH 握手开销。</p>
     *
     * @param config 健康检查配置，不允许为 null
     * @param server 目标服务器档案，提供地址与 SSH 凭据
     * @return 健康检查结果，含是否健康与面向日志/页面的细节说明
     */
    public HealthCheckResult check(HealthConfig config, Server server) {
        int attempts = config.getRetryCount() == null || config.getRetryCount() < 1
                ? 1 : config.getRetryCount();
        long intervalMs = config.getIntervalSeconds() == null || config.getIntervalSeconds() < 0
                ? 0L : config.getIntervalSeconds() * 1000L;
        long timeoutMs = config.getTimeoutSeconds() == null || config.getTimeoutSeconds() < 1
                ? DEFAULT_TIMEOUT_MS : config.getTimeoutSeconds() * 1000L;

        if (isTargetProbe(config)) {
            try (SshSession session = sshSessionFactory.open(server)) {
                return retry(config, server, session, attempts, intervalMs, timeoutMs);
            } catch (Exception e) {
                // 连不上目标机时健康检查没有执行的前提：把这一点说清楚，
                // 而不是笼统报「探测失败」——前者要查 SSH/网络，后者要查应用。
                return new HealthCheckResult(false,
                        "无法登录目标机执行本地探测（" + safeMessage(e) + "），请检查服务器 SSH 配置与网络");
            }
        }
        return retry(config, server, null, attempts, intervalMs, timeoutMs);
    }

    /**
     * 按重试策略循环探测。
     *
     * @param config     健康检查配置
     * @param server     目标服务器
     * @param session    {@code TARGET} 模式下已建立的 SSH 会话，平台侧探测时为 null
     * @param attempts   最大探测次数
     * @param intervalMs 探测间隔毫秒数
     * @param timeoutMs  单次探测超时毫秒数
     * @return 健康检查结果
     */
    private HealthCheckResult retry(HealthConfig config, Server server, SshSession session,
                                    int attempts, long intervalMs, long timeoutMs) {
        String lastDetail = "未执行探测";
        for (int attempt = 1; attempt <= attempts; attempt++) {
            ProbeResult result = probe(config, server, session, timeoutMs);
            lastDetail = result.detail();
            if (result.healthy()) {
                return new HealthCheckResult(true, "第 " + attempt + "/" + attempts + " 次探测通过，"
                        + result.detail());
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
     * @param server    目标服务器
     * @param session   {@code TARGET} 模式下已建立的 SSH 会话，平台侧探测时为 null
     * @param timeoutMs 单次探测超时毫秒数
     * @return 本次探测结果
     */
    private ProbeResult probe(HealthConfig config, Server server, SshSession session, long timeoutMs) {
        if (session != null) {
            return targetProbe(server, config, timeoutMs, session);
        }
        if (isTcp(config)) {
            return tcpProbe(server.getHost(), config.getPort(), timeoutMs);
        }
        return httpProbe(buildUrl(config, server.getHost()), timeoutMs);
    }

    /**
     * 判断是否走目标机本地探测。
     *
     * <p>未配置、为空或填了其他值时一律按 {@code TARGET} 处理：
     * 默认值必须是那个「不会因环境差异而误判」的选项，
     * 让运维因为少填一个字段就掉进防火墙陷阱是不合理的。</p>
     *
     * @param config 健康检查配置
     * @return true 表示在目标机本地探测
     */
    private boolean isTargetProbe(HealthConfig config) {
        String from = config.getProbeFrom();
        return from == null || from.isBlank() || !PROBE_PLATFORM.equalsIgnoreCase(from.trim());
    }

    // ==================== 目标机本地探测 ====================

    /**
     * 在目标机本地执行一次探测。
     *
     * @param server    目标服务器
     * @param config    健康检查配置
     * @param timeoutMs 单次探测超时毫秒数
     * @param session   已建立的 SSH 会话
     * @return 本次探测结果
     */
    private ProbeResult targetProbe(Server server, HealthConfig config, long timeoutMs, SshSession session) {
        Integer port = config.getPort();
        if (port == null || port < 1 || port > 65535) {
            return new ProbeResult(false, "健康检查未配置合法端口");
        }
        long seconds = Math.max(1, timeoutMs / 1000L);
        boolean tcp = isTcp(config);
        SshCommandResult result = session.exec(tcp
                ? listenCommand(port, seconds)
                : httpCommand(config, port, seconds));
        String out = result.success() && result.stdout() != null ? result.stdout().strip() : "";
        if ("ok".equalsIgnoreCase(out)) {
            return new ProbeResult(true, tcp
                    ? "目标机本地探测：端口 " + port + " 处于监听状态"
                    : "目标机本地探测：http://127.0.0.1:" + port + pathOf(config) + " 返回 2xx");
        }
        String err = result.stderr();
        String suffix = StringUtils.hasText(err) ? "（" + firstLine(err) + "）" : "";
        return new ProbeResult(false, tcp
                ? "目标机本地探测：端口 " + port + " 未处于监听状态" + suffix
                : "目标机本地探测：http://127.0.0.1:" + port + pathOf(config) + " 未返回 2xx" + suffix);
    }

    /**
     * 拼装 TCP 探测命令：优先用 {@code ss} 判断端口是否在监听。
     *
     * <p><b>为什么判监听而不是连回环地址</b>：服务可能只绑定在具体网卡 IP 上，
     * 此时连 {@code 127.0.0.1:port} 会失败，但服务其实是正常的。
     * 「端口处于监听状态」与绑定地址无关，是更准确的判据。
     * 机器上没有 {@code ss} 时回落到 bash 内置的 {@code /dev/tcp} 连回环，
     * 这条路径不依赖任何额外工具。</p>
     *
     * @param port    目标端口
     * @param seconds 回落路径的连接超时秒数
     * @return 目标机上执行的 shell 命令，通过时输出 ok
     */
    private String listenCommand(int port, long seconds) {
        String listen = "ss -lntn 2>/dev/null | awk '{print $4}' | grep -qE ':" + port + "$'";
        String devTcp = "timeout " + seconds + " bash -c 'exec 3<>/dev/tcp/127.0.0.1/" + port + "'";
        return "if command -v ss >/dev/null 2>&1; then " + listen + " && echo ok || echo none; "
                + "else " + devTcp + " 2>/dev/null && echo ok || echo none; fi";
    }

    /**
     * 拼装 HTTP 探测命令：优先用 python3 请求本机地址。
     *
     * <p>目标机上不一定有 curl / wget，但一定有 python3——JAR 部署脚本本身就是 python 的，
     * 初始化阶段还会显式探测它。没有 python3 时回落到「端口是否在监听」，
     * 此时语义降级为 TCP 探测：只能证明有进程在监听，不能证明应用能正常响应。</p>
     *
     * @param config  健康检查配置
     * @param port    目标端口
     * @param seconds 请求超时秒数
     * @return 目标机上执行的 shell 命令，2xx 时输出 ok
     */
    private String httpCommand(HealthConfig config, int port, long seconds) {
        String url = "http://127.0.0.1:" + port + shellQuoteSafe(pathOf(config));
        String request = "python3 -c \"import urllib.request,sys;"
                + "r=urllib.request.urlopen('" + url + "', timeout=" + seconds + ");"
                + "sys.exit(0 if 200 <= r.status < 300 else 1)\"";
        return "if command -v python3 >/dev/null 2>&1; then " + request + " && echo ok || echo none; "
                + "else " + listenCommand(port, seconds) + "; fi";
    }

    /**
     * 把路径中的单引号转成可安全嵌入 shell 单引号串的形式。
     *
     * <p>{@code '} → {@code '"'"'}：先闭合当前单引号，再用双引号包一个单引号，最后重新打开单引号。
     * 健康检查路径一般是 {@code /health}，但既然要拼进 shell 就必须堵住这个注入口子。</p>
     *
     * @param path 探测路径
     * @return 可安全嵌入单引号字符串的文本
     */
    private String shellQuoteSafe(String path) {
        return path.replace("'", "'\"'\"'");
    }

    // ==================== 平台侧探测 ====================

    /**
     * 拼装 HTTP 探测地址。
     *
     * @param config 健康检查配置
     * @param host   目标服务器地址
     * @return 形如 http://host:8080/health 的探测地址
     */
    private String buildUrl(HealthConfig config, String host) {
        int port = config.getPort() == null ? 80 : config.getPort();
        return "http://" + host + ":" + port + pathOf(config);
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
                    ? new ProbeResult(true, "HTTP " + code + " " + url)
                    : new ProbeResult(false, "HTTP 状态码 " + code + "，期望 2xx（" + url + "）");
        } catch (Exception e) {
            return new ProbeResult(false, "HTTP " + url + " 探测失败：" + safeMessage(e));
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
            return new ProbeResult(true, "TCP " + host + ":" + port + " 可连通");
        } catch (SocketTimeoutException e) {
            // 超时与「连接被拒绝」是两种完全不同的故障：被拒绝说明主机可达、端口没监听，
            // 超时说明 SYN 在网络路径上被丢弃。平台侧探测要经过「平台 → 业务端口」这段网络，
            // 最常见的丢弃者就是云防火墙/安全组与目标机 firewalld——应用明明起来了却判失败，
            // 九成是这个原因。把结论直接写进探测细节，运维不用再去猜超时是什么意思。
            return new ProbeResult(false, "TCP " + host + ":" + port + " 连接超时：端口很可能未放行或被防火墙丢弃，"
                    + "请检查云防火墙/安全组与目标机 firewalld（与「连接被拒绝」不同，超时代表包被丢弃）");
        } catch (IOException e) {
            return new ProbeResult(false, "TCP " + host + ":" + port + " 连接失败：" + safeMessage(e));
        }
    }

    // ==================== 公共工具 ====================

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
     * 取探测路径，缺失时回落为根路径。
     *
     * @param config 健康检查配置
     * @return 以 / 开头的路径
     */
    private String pathOf(HealthConfig config) {
        String path = config.getPath() == null || config.getPath().isBlank() ? "/" : config.getPath().trim();
        return path.startsWith("/") ? path : "/" + path;
    }

    /**
     * 取多行文本的首行，避免把大段输出拼进结论。
     *
     * @param text 原始文本
     * @return 首行内容，为空时返回空串
     */
    private String firstLine(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.strip().split("\\R")[0];
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
