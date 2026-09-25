package com.lx.deploy.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.lx.deploy.common.util.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 为请求和日志补充统一追踪标识，并在请求结束时记一行访问日志。
 *
 * <p>追踪：优先透传上游的 `X-Request-Id`，缺失时生成 32 位 UUID，同时写入请求属性、响应头与 MDC，
 * 供 Controller、异常处理器与日志格式（`%X{requestId}`）共用。</p>
 *
 * <p>访问日志：请求结束后记录「方法 + 路径 + 状态码 + 耗时」。只记请求路径、<b>不记查询串</b>——
 * 查询串可能携带凭据或业务敏感参数（AGENTS.md §14）。该行在 finally 中输出，因此下游抛异常时同样有记录。</p>
 *
 * <p>降噪：命中 {@link AccessLogProperties} 静默清单的路径（前端定时轮询，如 {@code /taskLog/pull}）
 * 在响应 &lt;400 时降为 DEBUG，仅失败时保留 WARN——轮询本身没有阅读价值，但接口出错、
 * 任务不存在这类失败必须留痕。其余路径照常记 INFO。如需整体关闭，把本类 logger 调到 WARN 即可：
 * {@code logging.level.com.lx.deploy.common.web.RequestTraceFilter: warn}。</p>
 */
@Slf4j
@Component
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = "requestId";

    /** 访问日志配置：提供高频轮询接口的静默清单。 */
    private final AccessLogProperties accessLogProperties;

    /**
     * 注入访问日志配置。
     *
     * @param accessLogProperties 静默路径配置，由 {@code light-deploy.access-log} 绑定
     */
    public RequestTraceFilter(AccessLogProperties accessLogProperties) {
        this.accessLogProperties = accessLogProperties;
    }

    /**
     * 为每个 HTTP 请求生成或透传追踪ID，并在请求结束时记录一行访问日志。
     *
     * <p>优先使用调用方传入的 X-Request-Id；未传入时生成32位UUID。追踪ID会同时写入
     * 请求属性、响应头和 MDC，确保 Controller 响应与持久化日志能够关联。</p>
     *
     * <p>访问日志放在 finally 中，先于 MDC 清理输出，因此也覆盖下游抛异常的请求；
     * 状态码取 {@code response.getStatus()}，异常处理器通过 {@code @ResponseStatus} 写入的
     * 400/500 会如实反映在这里。</p>
     *
     * @param request 当前 HTTP 请求
     * @param response 当前 HTTP 响应
     * @param filterChain Servlet 过滤器链
     * @throws ServletException 后续过滤器或 Servlet 处理失败时抛出
     * @throws IOException 读写请求或响应失败时抛出
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        // 优先复用上游网关传递的请求ID，保证跨服务调用链可以使用同一个标识。
        String requestId = request.getHeader(HEADER_NAME);
        if (requestId == null || requestId.isBlank()) {
            // 上游未提供请求ID时，在本服务内生成不带连字符的32位UUID。
            requestId = IdGenerator.generateUUID();
        }
        // 请求属性供 Controller 和异常处理器读取，响应头供调用方获取。
        request.setAttribute(ATTRIBUTE_NAME, requestId);
        response.setHeader(HEADER_NAME, requestId);
        // MDC 中的 requestId 会被 logback-spring.xml 的日志格式自动输出。
        MDC.put("requestId", requestId);
        long startedAt = System.currentTimeMillis();
        try {
            filterChain.doFilter(request, response);
        } finally {
            writeAccessLog(request, response, startedAt);
            // 线程可能被 Web 容器复用，必须清理 MDC，避免请求之间串号。
            // 必须在 writeAccessLog 之后：日志格式里的 %X{requestId} 依赖它。
            MDC.remove("requestId");
        }
    }

    /**
     * 写一行访问日志，并对高频轮询接口降噪。
     *
     * <p>路径命中静默清单时：响应 &lt;400 走 DEBUG（默认级别下不输出），&ge;400 走 WARN 保留线索。
     * 这样日志既不被 1.5 秒一次的轮询刷屏，又能在接口出错时及时发现。</p>
     *
     * @param request   当前 HTTP 请求
     * @param response  当前 HTTP 响应
     * @param startedAt 请求进入时刻（毫秒），用于计算耗时
     */
    private void writeAccessLog(HttpServletRequest request, HttpServletResponse response, long startedAt) {
        String uri = request.getRequestURI();
        long cost = System.currentTimeMillis() - startedAt;
        int status = response.getStatus();
        if (!accessLogProperties.isSilent(stripContextPath(uri, request.getContextPath()))) {
            // requestId 已由 MDC 带入日志格式，无需手工拼接。
            // 只记 URI 不记查询串，避免把凭据或敏感业务参数写进日志（AGENTS.md §14）。
            log.info("{} {} -> {}，耗时 {} ms", request.getMethod(), uri, status, cost);
            return;
        }
        if (status >= 400) {
            log.warn("{} {} -> {}，耗时 {} ms（轮询接口返回失败）", request.getMethod(), uri, status, cost);
        } else if (log.isDebugEnabled()) {
            log.debug("{} {} -> {}，耗时 {} ms", request.getMethod(), uri, status, cost);
        }
    }

    /**
     * 去掉上下文路径，得到用于静默清单匹配的路径。
     *
     * <p>配置里写的是业务路径（{@code /taskLog/pull}），不含 {@code server.servlet.context-path}
     * 的 {@code /deploy}，所以匹配前必须剥掉，否则永远命中不了。日志输出仍用完整 URI，
     * 便于直接复制到 Nginx 或浏览器地址栏。</p>
     *
     * @param uri         完整请求 URI
     * @param contextPath 当前应用的上下文路径，根路径下为空串
     * @return 去掉上下文路径后的路径
     */
    private String stripContextPath(String uri, String contextPath) {
        if (uri == null) {
            return "";
        }
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            return uri.substring(contextPath.length());
        }
        return uri;
    }
}
