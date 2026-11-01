package com.lx.deploy.config;

import com.lx.deploy.common.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 *
 * <p>注册登录态拦截器：除登录/登出、接口文档与静态资源外，全部请求都必须已登录。
 * 白名单路径说明：
 * <ul>
 *   <li>{@code /auth/login}、{@code /auth/logout}：登录与登出本身不能要求已登录；</li>
 *   <li>springdoc 相关路径：{@code /v3/api-docs/**}、{@code /swagger-ui/**}、{@code /swagger-ui.html}：接口文档免登录；</li>
 *   <li>{@code /error}、{@code /favicon.ico}：容器与浏览器默认请求，避免被拦截器误拦。</li>
 * </ul>
 * {@code /auth/me} 故意不放行：未登录时返回 4002，前端据此跳转登录页。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public WebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/auth/login",
                        "/auth/logout",
                        "/v3/api-docs",
                        "/v3/api-docs/**",
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**",
                        "/error",
                        "/favicon.ico");
    }
}
