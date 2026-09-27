package com.lx.deploy.common.web;

import jakarta.servlet.http.HttpServletRequest;

import java.util.Locale;

/**
 * 从 HTTP 请求里解析操作日志需要的客户端信息（IP、操作系统、浏览器）。
 *
 * <p>IP 必须先看代理头：平台前端由 Nginx 反代到后端，直接取 {@code getRemoteAddr()}
 * 拿到的永远是网关地址，操作日志里就会「所有人都是同一个 IP」。{@code X-Forwarded-For}
 * 是「客户端, 代理1, 代理2」的链，取第一段才是真实客户端。</p>
 *
 * <p>操作系统与浏览器只做粗粒度识别（够页面展示即可），不引入 UA 解析库：
 * 这类库体积不小、且需要持续维护 UA 规则库，换来的只是「Chrome 131.0」这种对运维没有意义的精度。</p>
 *
 * @author lx
 * @since 2026-09-27 00:35:00
 */
public final class ClientInfoResolver {

    /** 代理链头，逗号分隔，第一段是真实客户端。 */
    private static final String HEADER_X_FORWARDED_FOR = "X-Forwarded-For";

    /** Nginx 等反向代理写入的客户端地址。 */
    private static final String HEADER_X_REAL_IP = "X-Real-IP";

    /** 无法识别时的展示文本。 */
    private static final String UNKNOWN = "未知";

    private ClientInfoResolver() {
    }

    /**
     * 解析客户端真实 IP。
     *
     * @param request 当前 HTTP 请求，允许为 null（非 Web 线程）
     * @return 客户端 IP；取不到时返回 {@code 未知}
     */
    public static String resolveIp(HttpServletRequest request) {
        if (request == null) {
            return UNKNOWN;
        }
        String forwarded = request.getHeader(HEADER_X_FORWARDED_FOR);
        if (forwarded != null && !forwarded.isBlank()) {
            // 取链首：后续每一段都是一跳代理，不是客户端。
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader(HEADER_X_REAL_IP);
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? UNKNOWN : remote;
    }

    /**
     * 粗粒度识别操作系统。
     *
     * @param userAgent 浏览器 User-Agent，允许为 null
     * @return 操作系统名称；识别不出时返回 {@code 未知}
     */
    public static String resolveOs(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("windows")) {
            return "Windows";
        }
        if (ua.contains("iphone") || ua.contains("ipad") || ua.contains("ios")) {
            return "iOS";
        }
        if (ua.contains("android")) {
            return "Android";
        }
        if (ua.contains("mac os x") || ua.contains("macintosh")) {
            return "macOS";
        }
        if (ua.contains("linux")) {
            return "Linux";
        }
        return UNKNOWN;
    }

    /**
     * 粗粒度识别浏览器。
     *
     * <p>判定顺序有讲究：Edge 与 Chrome 的 UA 里都含 Chrome，必须先判 Edge；
     * 同理 Safari 的 UA 里含 "safari" 但 Chrome 也含，所以 Chrome 系列要排在 Safari 之前。</p>
     *
     * @param userAgent 浏览器 User-Agent，允许为 null
     * @return 浏览器名称；识别不出时返回 {@code 未知}
     */
    public static String resolveBrowser(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return UNKNOWN;
        }
        String ua = userAgent.toLowerCase(Locale.ROOT);
        if (ua.contains("edg/") || ua.contains("edge/")) {
            return "Edge";
        }
        if (ua.contains("opr/") || ua.contains("opera")) {
            return "Opera";
        }
        if (ua.contains("firefox")) {
            return "Firefox";
        }
        if (ua.contains("chrome")) {
            return "Chrome";
        }
        if (ua.contains("safari")) {
            return "Safari";
        }
        return UNKNOWN;
    }
}
