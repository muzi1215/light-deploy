# Light Deploy 开发约定

本文件记录 Light Deploy 项目已经确认的业务边界、数据库规范、代码结构和生成规则。后续开发、代码生成和自动化修改必须优先遵守本文件，不能在未确认的情况下引入另一套设计。

## 1. 项目定位

Light Deploy 是面向中小企业的轻量化部署发布平台，采用 Spring Boot 单体架构，通过 SSH/SFTP 管理多台 Linux 目标服务器。

当前规模基线是至少纳管 6 台服务器，并支持这些服务器上的不同项目并发发布。这里的 6 台服务器表示平台管理规模和任务并发能力，不表示一个项目需要同时发布到 6 台服务器。

例如：

```text
服务器 1：AIops
服务器 2：数据采集
服务器 3：数据分析
服务器 4：巡检系统
服务器 5：其他业务
服务器 6：其他业务
```

不同服务器上的部署任务可以并发运行。同一服务器默认同一时刻只执行一个发布任务。

## 2. 技术栈

- Java 21
- Spring Boot 4.x
- MyBatis-Plus
- MySQL
- Redis
- Apache SSHD 和 SFTP
- Vue 3、Vite、TypeScript
- Nginx 托管前端静态文件
- Docker 作为主要交付方式

## 3. 发布类型

平台最终支持以下运行时：

- `DOCKER`：Dockerfile 或 Docker Compose 部署；
- `JAR`：Java JAR 配合 systemd 部署；
- `STATIC`：前端静态产物配合 Nginx 部署。

Docker 构建模式预留：

- `PLATFORM_TEMPLATE`：平台 Dockerfile 模板；
- `PROJECT_DOCKERFILE`：项目自带 Dockerfile；
- `LAYERED_DOCKER`：`Dockerfile.base + Dockerfile.app` 分层构建；
- `ARTIFACT_ONLY`：直接发布已有 JAR 或静态产物。

AIops 项目使用分层构建：基础依赖镜像推送到阿里云镜像仓库，业务镜像基于基础镜像构建，Docker Compose 使用业务镜像启动。

## 4. 数据库约定

### 4.1 表命名

- 普通业务表不使用统一前缀，例如 `user`、`server`、`project`；
- 任务域表使用 `lds_` 前缀，例如 `lds_task`、`lds_task_log`；
- 不再使用 `ld_` 作为所有表的统一前缀。

### 4.2 字符集

所有表统一使用：

```sql
ENGINE=InnoDB
DEFAULT CHARSET=utf8mb4
COLLATE=utf8mb4_unicode_ci
```

表和字段必须具有清晰的中文 `COMMENT`。

### 4.3 基础字段

所有业务表必须包含：

```text
id           VARCHAR(32)
create_time  DATETIME
update_time  DATETIME
is_deleted   TINYINT
```

主键由 MyBatis-Plus `ASSIGN_UUID` 生成 32 位 UUID 字符串，统一调用 `com.lx.deploy.common.util.IdGenerator`。

删除操作统一使用软删除：

```text
0 = 未删除
1 = 已删除
```

业务代码不得直接物理删除数据。只有数据库维护脚本可以执行 `DROP` 或物理清理。

### 4.4 DDL 文件

- `src/main/resources/db/schema.sql` 保存完整业务表 DDL；
- `src/main/resources/db/database.sql` 用于手工创建数据库；
- `src/main/resources/db/cleanup.sql` 用于手工删除当前表和早期遗留表；
- 升级脚本单独放在 `src/main/resources/db/` 下。

Spring Boot 启动时禁止自动执行数据库脚本：

```yaml
spring:
  sql:
    init:
      mode: never
```

不得擅自将其改为 `always` 或自动绑定 `schema.sql`。数据库创建、升级和清理必须由开发者确认后手工执行。

## 5. 基础实体

所有 MyBatis-Plus 业务实体继承 `BaseEntity`。

`BaseEntity` 统一提供：

- `String id`；
- `LocalDateTime createTime`；
- `LocalDateTime updateTime`；
- `Integer isDeleted`。

实体优先使用 Lombok `@Data`，不手写没有额外逻辑的 getter 和 setter。

## 6. 服务器模型

服务器 SSH 认证使用单表方案，不通过 `credential_id` 关联凭据表。

`server` 表直接保存：

```text
host                   服务器地址
ssh_port               SSH 端口
username               SSH 登录用户
auth_type              SSH 认证类型
ciphertext             Java AES-GCM 加密后的密码或私钥
environment_tag        服务器环境标签
status                 服务器启用状态
```

### 6.1 SSH 认证类型

数据库使用数字编码，Java 使用 `ServerAuthType` 枚举：

```text
1 = 密码认证
2 = 私钥认证
```

凭据密文字段使用 `TEXT`，保存 Java AES-GCM 加密后的密码或私钥内容。禁止保存明文密码、明文私钥或不稳定的本地私钥路径。

该字段必须标记为只写，服务器查询接口不得返回密文：

```java
@JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
@Schema(accessMode = Schema.AccessMode.WRITE_ONLY)
```

### 6.2 服务器环境标签

数据库使用数字编码，Java 使用 `EnvironmentTag` 枚举：

```text
1 = 开发环境
2 = 测试环境
3 = 生产环境
```

环境标签表示服务器的默认环境归属，用于服务器列表展示和筛选。正式项目发布目标仍以项目环境绑定配置为准。

### 6.3 服务器状态

数据库使用数字编码，Java 使用 `ServerStatus` 枚举：

```text
0 = 停用
1 = 启用
```

状态只表示服务器是否允许被平台连接和参与部署，不能用来表达开发、测试或生产环境。

## 7. 凭据安全

凭据加解密统一使用 `com.lx.deploy.common.crypto.CredentialCrypto`。该实现使用 Java 标准 `AES/GCM/NoPadding`：服务器新增/修改请求提交明文凭据，Service 写入数据库前加密；SSH、Docker Registry 或 Git 执行前才短暂解密。AES key 通过 `LIGHT_DEPLOY_AES_KEY` 注入，绝不提交到 Git。

- SSH 密码和私钥必须加密保存；
- 凭据加解密统一使用 `CredentialCrypto.encrypt/decrypt`；
- AES key 只能通过 `LIGHT_DEPLOY_AES_KEY` 等外部配置注入，禁止写入源码、数据库或 Git；
- 数据库、日志、响应对象中不得出现明文凭据；
- 敏感字段不得参与普通条件查询；
- SSH 执行器只在建立连接前临时解密；
- 使用完成后不得在内存缓存或日志中长期保留明文；
- Git Token、镜像仓库凭据等其他系统凭据可以使用独立 `credential` 表，服务器 SSH 凭据不使用该表。

## 8. Service 约定

纯 CRUD 业务不创建 Service 接口层。

正确结构：

```java
@Service
public class ServerService extends ServiceImpl<ServerMapper, Server> {
}
```

不要创建以下无实际抽象价值的接口：

```java
public interface IServerService extends IService<Server> {
}
```

只有存在多个实现、外部适配器或明确领域边界时才创建接口。

## 9. 统一响应和异常

接口统一返回 `ApiResponse<T>`。

响应码统一维护在 `ResponseCode` 枚举中。业务场景可以使用稳定响应码配合具体消息：

```java
throw new DeployException(ResponseCode.DATA_NOT_FOUND, "服务器不存在");
```

Controller 不捕获通用异常，统一交给 `GlobalExceptionHandler`。

所有响应应携带 `traceId`，用于关联接口请求和服务端日志。

## 10. MyBatis-Plus 配置

必须保留：

- MySQL 分页插件；
- 下划线转驼峰；
- `ASSIGN_UUID` 主键策略；
- 数据库主键生成统一使用 `IdGenerator.generateUUID()`，禁止在业务代码中直接调用 `UUID.randomUUID()`；
- `is_deleted` 逻辑删除；
- `createTime`、`updateTime` 自动填充；
- 通用 `PageQuery` 和 `PageResult`。

分页接口每页最大数量为 100，避免无边界查询。

## 11. 代码生成器

代码生成器位于：

```text
src/main/java/com/lx/deploy/common/generator/CodeGenerator.java
```

自定义模板位于：

```text
src/main/resources/templates/entity.java.ftl
src/main/resources/templates/serviceImpl.java.ftl
src/main/resources/templates/controller.java.ftl
src/main/resources/templates/dto.java.ftl
src/main/resources/templates/vo.java.ftl
```

### 11.1 生成约定

生成器必须：

- 生成 Model、Mapper、Service、Controller；
- 在 `model/dto` 生成请求 DTO，在 `model/vo` 生成响应 VO；
- 实体继承 `BaseEntity`；
- 实体使用 `@Data`；
- 不重复生成基础字段；
- 不生成 Service 接口；
- Service 类名为 `XxxService`；
- Controller 使用字段注入，不生成构造方法；
- Controller 的请求体、查询条件使用 DTO，响应泛型使用 VO，不得直接使用实体类；
- 数据库字段注释生成实体字段 JavaDoc；
- 任务模块自动移除 `lds_` Java 类名前缀。

### 11.2 自动生成的方法

Service 自动生成：

```java
pageByCondition(PageQuery pageQuery, XxxDto condition)
listByCondition(XxxDto condition)
getRequiredById(String id) -> XxxVo
create(XxxDto dto) -> XxxVo
update(String id, XxxDto dto) -> XxxVo
delete(String id)
```

Controller 自动生成：

```text
GET    /{module}/page
GET    /{module}/list
GET    /{module}/{id}
POST   /{module}
PUT    /{module}/{id}
DELETE /{module}/{id}
```

### 11.3 条件查询规则

- 只使用实体中的非空业务字段；
- 字符串为空时不参与查询；
- 默认使用等值查询；
- 默认按 `create_time` 倒序；
- 自动排除基础字段；
- 自动排除包含以下名称的敏感字段：`password`、`credential`、`secret`、`token`、`privateKey`、`encrypted`；
- MyBatis-Plus 自动追加未删除条件。

## 12. Controller 约定

Controller 只负责：

- 接收请求；
- 校验参数；
- 调用 Service；
- 包装 `ApiResponse`；
- 返回 `traceId`。

Controller 不负责：

- 直接调用 Mapper；
- 拼接数据库查询条件；
- 编写 SSH 或 Docker 执行逻辑；
- 捕获所有异常并返回字符串；
- 返回密码、私钥、Token 或密文。

### 12.1 DTO/VO 分层

每张业务表对应三个模型层级：

```text
model/Entity.java       数据库持久化实体，只在 Service/Mapper 内部使用
model/dto/EntityDto.java Controller 请求对象，用于查询、新增和修改
model/vo/EntityVo.java  Controller 响应对象，用于对外返回
```

DTO 不包含主键、创建时间、更新时间、创建人、更新人和软删除字段，避免调用方修改服务端维护字段。
VO 包含必要的主键和审计信息，但默认排除密码、密文、Token、私钥等敏感字段。Service 负责 DTO 与实体、实体与 VO 之间的转换和脱敏。

## 13. 注释规范

新增或生成的类必须有类级 JavaDoc，说明职责、边界和使用方式。

公共方法和核心私有方法必须有详细 JavaDoc，包括：

- 方法用途；
- 关键业务规则；
- 每个参数的含义；
- 返回值含义；
- 可能抛出的业务异常。

不能只使用一句没有信息量的注释，例如：

```java
/** 查询数据。 */
```

方法内部的核心步骤应添加行注释，例如：

- 动态条件构建；
- 敏感数据处理；
- ID 覆盖规则；
- 逻辑删除；
- 状态转换；
- 远程命令执行和结果校验。

简单赋值、普通 getter/setter 和显而易见的代码不需要逐行解释。能够使用 Lombok `@Data` 的实体，不生成冗余 getter/setter。

## 14. 日志约定

日志配置文件：

```text
src/main/resources/logback-spring.xml
```

只使用两个主日志文件：

```text
logs/info.log
logs/error.log
```

- `info.log` 保存 INFO 及以上日志；
- `error.log` 单独保存 ERROR 日志；
- 支持按日期和大小滚动；
- 历史文件存放在 `logs/archive/`；
- 日志格式必须包含 `traceId`；
- `logs/` 不提交 Git。

日志中禁止输出密码、私钥、Token、密文、完整环境变量或其他敏感信息。

### 14.1 traceId 约定

`RequestTraceFilter` 为每个请求生成或透传 `X-Request-Id`，并同时写入请求属性、响应头和 SLF4J MDC。`ApiResponse.traceId` 返回同一个值，日志格式通过 `%X{traceId}` 输出。

因此，前端只需要把错误响应中的 `traceId` 提供给运维人员，即可在 `logs/info.log` 或 `logs/error.log` 中检索同一次请求。调用方也可以主动传入 `X-Request-Id`，用于网关、AIops 和部署平台之间的链路关联。traceId 只用于排查和关联，不代表用户身份、权限或业务主键。

## 15. AIops 集成边界

Light Deploy 负责：

- 构建和发布；
- 健康检查；
- 版本和回滚；
- 发布任务日志。

AIops 负责：

- 发布期间的告警维护窗口；
- 指标、日志和告警分析；
- 发布风险建议。

部署平台发布前调用 AIops 创建精确范围的告警静默，发布完成或回滚稳定后关闭静默。静默必须具有自动过期时间。

AIops 第一版只提供分析和建议，不直接无条件执行生产回滚。

## 16. 权限边界

当前 `user` 表只用于过渡期登录和开发联调。

后续 AIops 和 Light Deploy 都接入统一权限中心。本项目不重复建设完整 RBAC，业务表只保留外部用户 ID 和操作者信息。

## 17. 验证要求

修改 Java 代码或模板后至少执行：

```bash
mvn -o -q -DskipTests compile
```

需要数据库连接的 Spring 上下文测试只能在 MySQL 可用时执行：

```bash
mvn test
```

测试失败时必须区分：

- Java 编译错误；
- 模板错误；
- MySQL/Redis 不可用；
- 沙箱或本机权限限制。

不得把数据库无法连接描述为代码编译失败。

## 18. 变更原则

- 不擅自扩大第一版范围；
- 不因“以后可能需要”提前创建大量 Java 包和实现类；
- `schema.sql` 可以保留完整 DDL，但 Java 代码按当前开发阶段逐步创建；
- 不混用两套互斥的数据模型；
- 修改字段归属前先明确业务实体边界；
- 涉及数据库自动执行、删除、凭据存储和安全边界时，必须先说明影响；
- 设计应优先保持轻量、明确和可落地，再为后续能力预留扩展点。

## 19. 代码生成器外置配置

代码生成器的数据库连接、模块、表名和覆盖策略必须从外置 `.properties` 文件读取，禁止在 Java 源码中硬编码数据库连接或密码。

默认读取项目根目录的 `generator.properties`，首次使用时从 `generator.properties.example` 复制。也可以通过 main 方法第一个参数或 `-Dgenerator.config` 指定其他配置文件。`generator.properties` 和 `generator-*.properties` 均不得提交到 Git。

完整配置项、IntelliJ 启动方式、输出目录、覆盖规则和故障排查见：

```text
docs/code-generator-guide.md
```

默认必须保持 `generator.overwrite=false`。只有在代码已经提交或备份且明确需要按新模板重生成时才可临时开启覆盖，生成后必须检查 Git diff。
