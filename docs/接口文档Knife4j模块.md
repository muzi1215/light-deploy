# 接口文档（Knife4j）模块

> 本文档讲清楚 light-deploy 的接口文档「怎么用、怎么配、怎么排障」，以及「新增接口后文档为什么会自动出现 / 不出现」。
> 注解书写规范见 [AGENTS.md §12.3](../AGENTS.md)，本文档聚焦 Knife4j 本身的装配、开关与常见坑。

## 1. 这是什么、为什么用

Knife4j 是 Springdoc OpenAPI 3.x 的增强 UI。本平台用它的原因是：**原 `knife4j-spring-boot-starter` 只支持 Boot 2.x，Boot 4 必须换 Boot4 专用 starter**（`com.baizhukui:knife4j-openapi3-boot4-spring-boot-starter:5.7.10`）。该 starter 已传递依赖 springdoc-openapi 3.0.3 与 swagger-annotations-jakarta 2.2.55，因此：

- 注解包固定为 `io.swagger.v3.oas.annotations.*`（OpenAPI 3），**禁止**引入 `io.swagger.annotations.*`（Swagger 2，与 Boot4 不兼容）。
- 不需要单独再声明 `springdoc-openapi-starter-webmvc-ui`，否则会与 starter 内的版本冲突（pom.xml 里已移除旧的 springdoc 2.6.0 声明）。

## 2. 怎么访问

文档页地址（注意 `context-path` 是 `/deploy`）：

```
http://<host>:2027/deploy/doc.html
```

**必须登录后访问**：`/doc.html`、`/v3/api-docs`、`/v3/api-docs/**` 不在 `WebConfig` 的免登录白名单内（详见 [AGENTS.md §12.3](../AGENTS.md) 末段）。未登录直接打开会被 `AuthInterceptor` 拦成 `4002「登录已失效，请重新登录」`，不会出现半截文档。

鉴权方式是 **HttpSession + 浏览器 Cookie**，不是 JWT。所以文档页只需「先登录、再打开」；在页面里点「调试」发请求时，浏览器自动带 Cookie，会话过期重新登录即可。**不要去填 Token**——本平台没有声明任何 `bearerAuth` 安全方案（见 §6）。

## 3. 全局开关：一处关、三处联动

总开关 `light-deploy.doc.enabled`（默认 `true`）。为什么是「三处而不是一处」：

- `config/Knife4jConfig.java` 上有 `@ConditionalOnProperty(prefix = "light-deploy.doc", name = "enabled", havingValue = "true", matchIfMissing = true)`——关掉时这个配置类整体不加载，不会注册 `OpenAPI` / `GroupedOpenApi` Bean。
- 但**只关这一类不够**：springdoc 在没有 `OpenAPI` Bean 时会自己造一个默认文档，`/v3/api-docs` 照样能访问。所以 `application.yaml` 里还要把另外两个也指到同一开关：

```yaml
light-deploy:
  doc:
    enabled: true          # 总开关

knife4j:
  enable: ${light-deploy.doc.enabled:true}

springdoc:
  api-docs:
    enabled: ${light-deploy.doc.enabled:true}
```

生产环境要藏文档，把 `light-deploy.doc.enabled` 置 `false`，三处一起关。

## 4. 按域分组（8 组）

分组在 `config/Knife4jConfig.java` 里用 `GroupedOpenApi` 定义，覆盖全部 14 个 Controller / 82 个接口：

| 分组名（ASCII） | 匹配的 base-path | 包含模块 |
|---|---|---|
| `auth` | `/auth/**` | 后台鉴权 |
| `infra` | `/server/**` `/credential/**` `/registry/**` | 服务器 / 凭据 / 镜像仓库 |
| `project` | `/project/**` `/environment/**` `/projectEnvironmentTarget/**` | 项目 / 环境 / 部署目标 |
| `build` | `/build/**` `/release/**` | 构建 / 发布版本 |
| `deployment` | `/deployment/**` | 发布 |
| `task` | `/task/**` `/taskLog/**` | 任务 / 任务日志 |
| `audit` | `/auditLog/**` | 操作审计 |
| `aiops` | `/aiops/**` | AIops 联动查询 |

两条硬约定（已在 [AGENTS.md §12.4](../AGENTS.md) 登记）：

- **分组名必须 ASCII**：分组名会拼进 `/v3/api-docs/{group}`，中文名会让 Knife4j「发送前 Schema 诊断」按中文 URL 解析失败（报「Schema 诊断不可用」）。
- **`pathsToMatch` 不写 `/deploy` 前缀**：它匹配的是容器内应用路径，控制器 `@RequestMapping("/build")` 这里就写 `/build/**`，不要写成 `/deploy/build/**`。分组为空时第一个要怀疑的就是这个。

## 5. 注解怎么写（规范，详见 AGENTS §12.3）

- **类级**必须 `@Tag(name = "…", description = "…")`：`name` 用中文业务名，与同类 `@OperLog(title=…)` 保持一致（如「构建管理」「发布管理」）。
- **方法级**每个 Mapping 必须 `@Operation(summary = "…", description = "…")`：`summary` 是动作，`description` 写调用方真正要懂的行为与约束（是否异步、哪些状态才允许、有无副作用）。`description` 不许照抄 `summary`，不确定的行为先去 Service 核对再写——**写错的文档比没有文档更有害**。
- 注解位置固定：`@Operation` 在 Javadoc 之后、`@OperLog` 之前，`@XxxMapping` 最靠近方法签名。

代码生成器模板 `src/main/resources/templates/controller.java.ftl` 已同步产出这两个注解，新增接口走模板不会漏。

## 6. 查询对象拍平（default-flat-param-object: true）

这是最容易让人误会的点，单列一节。

**现象**：`GET /project/page` 的 `condition`（`@ModelAttribute ProjectDto`）在文档里显示成「必填的 object 参数」，但前端只发 `?pageNum=1&pageSize=10`（不带任何 condition 字段）也能正常返回。

**为什么文档会撒谎**：springdoc 3.x 的 `springdoc.default-flat-param-object` 默认是 `false`，即**不把 `@ModelAttribute` 对象拍平**，而是当成一个单一的 object 类型参数 `condition` 渲染；Knife4j 对 object 类型参数默认标成「必填」。但运行时完全不同——`@ModelAttribute` 在 Spring MVC 里默认可选，请求不带对应字段时 Spring 直接 `new` 一个全 null 的 `ProjectDto`，而 `ProjectDto` 所有字段只有 `@Schema(description=…)` 文档注解、**没有任何 `@NotNull` 这类 JSR-303 运行时约束**，所以 `@Valid` 也能过，`service.pageByCondition` 把全 null 的 condition 当成「无筛选」。

**怎么让文档与运行时一致**：`application.yaml` 的 `springdoc:` 段已置

```yaml
springdoc:
  default-flat-param-object: true
```

开启后，所有 `@ModelAttribute` / `@ParameterObject` 查询对象会被拍平成独立的可选 query 参数（每个字段 `required=false`，如 `condition.code`、`condition.name`…），文档不再把 `condition` 显示成单个必填 object。**纯配置改动，不需要改任何 Controller 代码**。

> 若你更希望字段名不带 `condition.` 前缀（更干净），可把查询 DTO 的 `@ModelAttribute` 换成 Spring 6.1 的 `@ParameterObject`（Boot 4.1 可用，springdoc 3.0.3 原生支持，拍平后字段名即裸字段名）。这是跨多个查询接口的改动，按需再做。

## 7. 新增接口 / 新增模块，文档怎么自动出现

**只加一个新接口**（已有模块内）：在 Controller 方法上补 `@Operation` 即可。分组按 `pathsToMatch` 自动归到对应域，无需动 `Knife4jConfig`。

**新增一个业务模块**（新 Controller + 新 base-path）：

1. Controller 类上加 `@Tag`、方法加 `@Operation`（见 §5）；
2. 若想让它独立成组，在 `Knife4jConfig` 加一个 `GroupedOpenApi`，`group` 用英文短横线、`pathsToMatch` 对齐该 Controller 的 `@RequestMapping`（不含 `/deploy` 前缀）；
3. 不需要改 pom、不需要改白名单（文档页仍走统一登录）。

## 8. 排障速查

| 症状 | 原因 / 处理 |
|---|---|
| 打开 `/deploy/doc.html` 返回 `4002` 或空白 | 未登录。先在同一浏览器登录平台，再打开。 |
| 文档里某个接口显示「必填 object」但实际可不传 | 没开 `default-flat-param-object`（见 §6）；或该对象确实有 `@NotNull` 字段（此时必填是对的）。 |
| 分组下拉里某组是空的 | 多半 `pathsToMatch` 误写了 `/deploy` 前缀；或 base-path 与分组不匹配。 |
| Knife4j 报「Schema 诊断不可用 / 发送前诊断失败」 | 分组名用了中文等非 ASCII；改成英文（见 §4）。 |
| 关闭 `light-deploy.doc.enabled` 后 `/v3/api-docs` 仍可访问 | 只关了 `Knife4jConfig` 一类，没关 `knife4j.enable` / `springdoc.api-docs.enabled`；三处必须一起关（见 §3）。 |
| 文档参数/返回体缺字段 | 字段没加 `@Schema` 或用了 Swagger 2 的 `io.swagger.annotations.*`（Boot4 不识别）；换成 `io.swagger.v3.oas.annotations.*`。 |

## 9. 与 auth-cloud 的关键差异

auth-cloud 是 JWT 鉴权，所以它的 `Knife4jConfig` 声明了 `bearerAuth` 安全方案。**light-deploy 不声明**——平台是 HttpSession + Cookie，声明 bearer 只会多出一个「填 Token」的输入框，而平台根本不认 Token，纯误导。文档页靠「先登录再打开」即可，调试请求自动带 Cookie。
