package com.lx.deploy.config;

import com.lx.deploy.common.security.AuthInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 *
 * <p>注册登录态拦截器：除登录/登出与少量纯静态资源外，全部请求都必须已登录。
 * 白名单路径说明：
 * <ul>
 *   <li>{@code /auth/login}、{@code /auth/logout}：登录与登出本身不能要求已登录；</li>
 *   <li>{@code /swagger-ui}、{@code /swagger-ui/**}、{@code /swagger-ui.html}、{@code /webjars/**}：
 *       只放行静态页面与 js/css，不含任何接口数据；</li>
 *   <li>{@code /error}、{@code /favicon.ico}：容器与浏览器默认请求，避免被拦截器误拦。</li>
 * </ul>
 * {@code /auth/me} 故意不放行：未登录时返回 4002，前端据此跳转登录页。</p>
 *
 * <p><b>接口文档不再免登录</b>：{@code /doc.html}（Knife4j UI）与 {@code /v3/api-docs}、
 * {@code /v3/api-docs/**}（OpenAPI 文档数据）已从白名单撤出，必须先登录再打开
 * {@code http://<host>:2027/deploy/doc.html}。原因是文档会把全部接口路径、参数与错误码平铺出来，
 * 属于攻击面测绘信息，不适合对公网裸奔；撤出后未登录访问会由全局异常处理器返回 4002，
 * 而不是渲染出一个空白文档页。</p>
 *
 * <p>平台只对外调用 AIops（建静默窗、推事件），不提供反向服务间接口，
 * 因此这里没有任何机器到机器的免登录路径（见 AGENTS.md §15.1）。</p>
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
                        // 只放行文档 UI 的静态资源（html/js/css）。
                        // 注意：文档数据接口 /v3/api-docs 与 /v3/api-docs/** 已不在白名单，
                        // 因此未登录打开 swagger-ui 只会看到空壳，正式入口请用登录后的 /doc.html。
                        "/swagger-ui",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/webjars/**",
                        "/error",
                        "/favicon.ico");
    }
}
