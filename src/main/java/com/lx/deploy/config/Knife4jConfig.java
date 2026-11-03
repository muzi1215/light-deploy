package com.lx.deploy.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Knife4j / springdoc-openapi 配置：文档元信息 + 按业务域分组。
 *
 * <p>文档入口是 {@code http://<host>:2027/deploy/doc.html}（context-path 为 {@code /deploy}），
 * 且<b>必须登录后访问</b>：{@code /doc.html}、{@code /v3/api-docs/**} 都不在
 * {@link WebConfig} 的免登录白名单里（见 AGENTS.md §12.3）。</p>
 *
 * <p><b>没有安全方案（Authorize 输入框）是刻意的。</b>本平台用 HttpSession + 浏览器 Cookie 鉴权，
 * 不是 Bearer Token（这点与 auth-cloud 不同，那边是 JWT，所以那边才有 {@code bearerAuth} 方案）。
 * 这里若声明 bearer 方案，页面会多出一个「请填写 Token」的输入框，而平台根本不认 Token，
 * 只会误导使用者去到处找 JWT。文档页只需「先登录、再打开」，调试请求时浏览器自动带 Cookie。</p>
 *
 * <p><b>总开关 {@code light-deploy.doc.enabled}</b>（默认开）：生产环境可置 {@code false} 关闭全部文档。
 * 注意只加 {@link ConditionalOnProperty} 是不够的——它只让本类不注册，springdoc 发现没有
 * {@code OpenAPI} Bean 会自己造一个默认的，{@code /v3/api-docs} 照样能被访问。因此
 * application.yaml 里 {@code springdoc.api-docs.enabled} 与 {@code knife4j.enable} 都指向同一个
 * 配置项（{@code ${light-deploy.doc.enabled:true}}），关开关时三处一起关。改这一行即可，
 * 不要在代码里另开判断。</p>
 *
 * <p><b>分组名必须是 ASCII。</b>分组名会拼进文档路径 {@code /v3/api-docs/{group}}，中文等非 ASCII
 * 名称会让 Knife4j「发送前 Schema 诊断」按中文 URL 解析失败（表现为「Schema 诊断不可用」）。
 * 这也是从 auth-cloud 带过来的既有教训。若以后想在页面分组下拉里显示中文，springdoc 的
 * {@code GroupedOpenApi.Builder#displayName} 可以做到，但必须先在实际运行的服务上确认
 * Knife4j 不会拿它去拼 URL，否则又会踩同一个坑。</p>
 *
 * <p><b>分组路径不写 {@code /deploy} 前缀。</b>{@code pathsToMatch} 匹配的是容器内的应用路径，
 * 不含 {@code server.servlet.context-path}；控制器上写 {@code @RequestMapping("/build")}，
 * 这里就写 {@code /build/**}。若实机发现某个分组是空的，先怀疑是不是把上下文路径写进去了。</p>
 *
 * <p>新增模块时按约定补一个分组：路径前缀与 {@code @RequestMapping} 一致，分组名用英文短横线风格。</p>
 *
 * @author lx
 * @since 2026-11-03 21:40:00
 */
@Configuration
@ConditionalOnProperty(prefix = "light-deploy.doc", name = "enabled", havingValue = "true", matchIfMissing = true)
public class Knife4jConfig {

    /** 文档元信息。分组只决定「看到哪些接口」，标题与说明在这里统一给一次。 */
    @Bean
    public OpenAPI lightDeployOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("轻量部署发布平台 API")
                        .description("""
                                内部发布平台的后端接口文档（构建 / 发布 / 回滚 / 任务 / 审计）。

                                访问前提：先在平台登录页登录，再在同一个浏览器打开 /deploy/doc.html。
                                未登录时本页与 /v3/api-docs/** 都会被登录拦截器拦成 4002「登录已失效，请重新登录」。

                                本平台用 HttpSession 会话鉴权，不是 Bearer Token，所以没有 Authorize 输入框；
                                调试请求时浏览器会自动带上 Cookie，无需填写任何密钥。会话过期就回登录页重新登录。
                                """)
                        .version("v1"));
    }

    /** 分组：后台鉴权（登录 / 登出 / 当前用户）。 */
    @Bean
    public GroupedOpenApi authGroup() {
        return GroupedOpenApi.builder()
                .group("auth")
                .pathsToMatch("/auth/**")
                .build();
    }

    /** 分组：基础资源（服务器、凭据、镜像仓库）——这三者是所有发布动作的前置资产。 */
    @Bean
    public GroupedOpenApi infraGroup() {
        return GroupedOpenApi.builder()
                .group("infra")
                .pathsToMatch("/server/**", "/credential/**", "/registry/**")
                .build();
    }

    /** 分组：项目与环境（项目、环境、部署目标绑定）。 */
    @Bean
    public GroupedOpenApi projectGroup() {
        return GroupedOpenApi.builder()
                .group("project")
                .pathsToMatch("/project/**", "/environment/**", "/projectEnvironmentTarget/**")
                .build();
    }

    /** 分组：构建与版本（构建记录、产物上传、发布版本查询）。 */
    @Bean
    public GroupedOpenApi buildGroup() {
        return GroupedOpenApi.builder()
                .group("build")
                .pathsToMatch("/build/**", "/release/**")
                .build();
    }

    /** 分组：发布与回滚。 */
    @Bean
    public GroupedOpenApi deploymentGroup() {
        return GroupedOpenApi.builder()
                .group("deployment")
                .pathsToMatch("/deployment/**")
                .build();
    }

    /** 分组：任务与任务日志（异步任务的查询、取消、日志增量拉取与导出）。 */
    @Bean
    public GroupedOpenApi taskGroup() {
        return GroupedOpenApi.builder()
                .group("task")
                .pathsToMatch("/task/**", "/taskLog/**")
                .build();
    }

    /** 分组：操作审计（只读）。 */
    @Bean
    public GroupedOpenApi auditGroup() {
        return GroupedOpenApi.builder()
                .group("audit")
                .pathsToMatch("/auditLog/**")
                .build();
    }

    /** 分组：AIops 联动查询（只读）。 */
    @Bean
    public GroupedOpenApi aiopsGroup() {
        return GroupedOpenApi.builder()
                .group("aiops")
                .pathsToMatch("/aiops/**")
                .build();
    }
}
