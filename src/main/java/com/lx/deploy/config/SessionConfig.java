package com.lx.deploy.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * Spring Session Cookie 配置。
 *
 * <p>平台后端 {@code server.servlet.context-path=/deploy}，而前端经 Nginx / Vite 代理以 {@code /api} 前缀调用后端
 * （{@code /api/**} 在后端视角被重写成 {@code /deploy/**}）。Spring Session 默认把会话 Cookie 的 Path 设为
 * context-path（{@code /deploy}），导致浏览器实际发出的 {@code /api/...} 请求路径与该 Cookie 的 Path 不匹配，
 * 浏览器不携带 SESSION Cookie，表现为「登录成功 200，但下一个业务请求立刻 4002 会话失效」。</p>
 *
 * <p>把 Cookie Path 收敛为根路径 {@code /}，使 {@code /api} 与 {@code /deploy} 两类路径都能带上会话 Cookie，
 * 登录态即可在前后端之间正常传递。当前主机只部署本平台，根路径 Cookie 不会造成跨应用泄漏。</p>
 */
@Configuration
public class SessionConfig {

    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookiePath("/");
        return serializer;
    }
}
