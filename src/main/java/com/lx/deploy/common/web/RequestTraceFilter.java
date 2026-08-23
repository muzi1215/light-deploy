package com.lx.deploy.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.lx.deploy.common.util.IdGenerator;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** 为请求和日志补充统一追踪标识。 */
@Component
public class RequestTraceFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Request-Id";
    public static final String ATTRIBUTE_NAME = "requestTraceId";

    /**
     * 为每个 HTTP 请求生成或透传追踪ID。
     *
     * <p>优先使用调用方传入的 X-Request-Id；未传入时生成32位UUID。追踪ID会同时写入
     * 请求属性、响应头和 MDC，确保 Controller 响应与持久化日志能够关联。</p>
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
        String traceId = request.getHeader(HEADER_NAME);
        if (traceId == null || traceId.isBlank()) {
            // 上游未提供请求ID时，在本服务内生成不带连字符的32位UUID。
            traceId = IdGenerator.generateUUID();
        }
        // 请求属性供 Controller 和异常处理器读取，响应头供调用方获取。
        request.setAttribute(ATTRIBUTE_NAME, traceId);
        response.setHeader(HEADER_NAME, traceId);
        // MDC 中的 traceId 会被 logback-spring.xml 的日志格式自动输出。
        MDC.put("traceId", traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 线程可能被 Web 容器复用，必须清理 MDC，避免请求之间串号。
            MDC.remove("traceId");
        }
    }
}
