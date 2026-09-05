# lx-common 通用模块 SDK 化技术方案

> 版本: v1.0 ｜ 作者: 木子 ｜ 日期: 2026-08-31
> 状态: 待评审

---

## 方案摘要（TL;DR）

1. **架构选择**：将各项目重复编写的 common 基座代码抽取为多模块 Maven 工程 `lx-common-parent`，按领域拆分为 core / web-starter / mybatis-starter / crypto / util 五个子模块 + 一个 BOM，发布到 Maven 远程仓库，业务项目按需引入、开箱即用。
2. **核心技术栈**：Java 21 + Spring Boot 4.x + MyBatis-Plus + Spring Boot 自动装配（AutoConfiguration）机制。
3. **发布通道**：SNAPSHOT 日常迭代 + 语义化正式版本双通道，打 tag 触发 CI 自动发版。
4. **关键风险**：依赖版本传导污染业务项目、自动装配绑架使用方、Light Deploy 迁移期间行为不一致，均已设计降级方案。
5. **预计排期**：共 5 个阶段（骨架搭建 → 代码迁移 → starter 化 → 仓库接入 → 试点接入），单人预计 1～2 周完成。

---

## 一、背景与目标

### 1.1 业务背景

当前每新建一个 Java 项目（如 Light Deploy），都需要重新构建一遍 common 基座模块，包括：

- 接口统一返回结果（`ApiResponse`）；
- 枚举状态码（`ResponseCode`）；
- 业务异常与全局异常处理（`DeployException` / `GlobalExceptionHandler`）;
- MyBatis-Plus 分页封装（`PageQuery` / `PageResult` / 分页插件配置）；
- 通用工具类（`IdGenerator` 等）；
- 请求链路追踪（`RequestTraceFilter` + requestId）；
- 凭据加解密（`CredentialCrypto`，AES-GCM）。

这些代码在不同项目间高度相似但又各自维护，导致：重复劳动、修复不同步、风格漂移、无法统一升级。

### 1.2 技术目标

| 目标编号 | 目标 | 说明 |
|---------|------|------|
| G-01 | 一次编写，处处引入 | 通用能力沉淀到 SDK，新项目通过 Maven 依赖直接使用 |
| G-02 | 按需引入，不绑架使用方 | 多模块拆分 + 自动装配开关，不需要的能力可排除或关闭 |
| G-03 | 可持续更新维护 | 语义化版本 + 远程仓库，业务项目升级依赖即可获得新能力 |
| G-04 | 渐进沉淀 | 后续发现的通用能力持续并入 SDK，形成个人/团队基础设施资产 |

### 1.3 成功标准

| 维度 | 指标 | 目标值 | 度量方式 |
|------|------|--------|---------|
| 效率 | 新项目基座搭建时间 | 从半天以上降到 10 分钟内 | 实际新项目验证 |
| 复用 | Light Deploy common 层代码量 | 迁移后仅保留项目适配层 | 代码行数对比 |
| 质量 | 迁移后 Light Deploy 现有接口行为 | 与迁移前完全一致 | 编译 + 接口回归 |
| 维护 | SDK 发版流程 | 打 tag 自动发版，全流程无手工推送 jar | CI 流水线验证 |

---

## 二、需求与约束

### 2.1 功能需求

| 需求编号 | 需求描述 | 优先级 | 来源 |
|---------|---------|--------|------|
| F-01 | 统一返回 `ApiResponse`、响应码枚举 `ResponseCode`、业务异常基类 | P0 | Light Deploy 现有实现 |
| F-02 | 全局异常处理 `GlobalExceptionHandler` 自动生效，可关闭 | P0 | Light Deploy 现有实现 |
| F-03 | MyBatis-Plus 基座：`BaseEntity`、分页插件、字段自动填充、逻辑删除、UUID 主键策略 | P0 | Light Deploy 现有实现 |
| F-04 | 通用分页模型 `PageQuery` / `PageResult` | P0 | Light Deploy 现有实现 |
| F-05 | 主键生成器 `IdGenerator`（32 位 UUID） | P0 | Light Deploy 现有实现 |
| F-06 | requestId 链路追踪过滤器 `RequestTraceFilter`，自动生效，可关闭 | P1 | Light Deploy 现有实现 |
| F-07 | 凭据加解密 `CredentialCrypto`（AES-GCM），key 外部注入 | P1 | Light Deploy 现有实现 |
| F-08 | 通用工具类沉淀（日期、字符串、JSON 等） | P1 | 持续沉淀 |
| F-09 | BOM 统一版本管理，业务项目无需写版本号 | P0 | 方案设计 |
| F-10 | SNAPSHOT + 正式版双通道发布 | P0 | 方案设计 |

### 2.2 非功能需求

- **兼容性**：SDK 基线锁定 Java 21 + Spring Boot 4.x，遵循 Light Deploy 技术栈约定；
- **安全性**：SDK 自身不包含任何密钥、明文凭据；AES key 只能由使用方外部注入（环境变量等）；敏感字段序列化约定（`WRITE_ONLY`）在 SDK 中文档化并默认执行；
- **轻量性**：core / util / crypto 模块尽量零 Spring 依赖或依赖设为 optional，避免依赖传染；
- **可维护性**：每个装配点提供关闭开关与 `@ConditionalOnMissingBean` 覆盖点。

### 2.3 约束条件

- 单人开发维护，仓库方案须零运维或近零运维；
- SDK 不得包含 Light Deploy 业务语义（如 `DeployException`、`LIGHT_DEPLOY_AES_KEY` 环境变量名需泛化为可配置项）；
- 遵循项目既定约定：不生成无抽象价值的接口层、软删除、`ASSIGN_UUID` 主键等。

---

## 三、总体架构

### 3.1 架构图（ASCII）

```
┌──────────────────────────────────────────────────────────────┐
│                   lx-common-parent（Maven 多模块工程）          │
│                                                              │
│  ┌──────────────────┐  ┌──────────────────────────────────┐  │
│  │  lx-common-bom   │  │  lx-common-core                  │  │
│  │  版本清单（pom）   │  │  ApiResponse / ResponseCode      │  │
│  │  业务项目 import  │  │  BizException / PageQuery        │  │
│  └──────────────────┘  │  PageResult / IdGenerator        │  │
│                        │  （零 Spring 依赖）                 │  │
│                        └──────────────────────────────────┘  │
│                                                              │
│  ┌─────────────────────┐   ┌─────────────────────────────┐   │
│  │ lx-common-web-      │   │ lx-common-mybatis-          │   │
│  │ starter             │   │ starter                     │   │
│  │ GlobalExceptionHandler│  │ BaseEntity / 分页插件        │   │
│  │ RequestTraceFilter  │   │ 字段自动填充 / 逻辑删除       │   │
│  │ （自动装配+开关）      │   │ UUID 主键策略（自动装配+开关） │   │
│  └─────────────────────┘   └─────────────────────────────┘   │
│                                                              │
│  ┌──────────────────┐    ┌──────────────────────────────┐   │
│  │ lx-common-crypto │    │ lx-common-util               │   │
│  │ CredentialCrypto │    │ 通用工具类（持续沉淀）          │   │
│  │ AES-GCM，key 注入 │    │ 日期/字符串/JSON 等           │   │
│  └──────────────────┘    └──────────────────────────────┘   │
└──────────────────────────┬───────────────────────────────────┘
                           │ mvn deploy（CI：push→SNAPSHOT，tag→release）
                           v
              ┌──────────────────────────┐
              │   Maven 远程仓库           │
              │ （GitHub Packages 私有）   │
              └────────────┬─────────────┘
                           │ 依赖引入（BOM import）
        ┌──────────────────┼──────────────────┐
        v                  v                  v
 ┌─────────────┐    ┌─────────────┐    ┌─────────────┐
 │ Light Deploy │    │  新项目 A    │    │  新项目 B    │
 │ （迁移接入）   │    │ （开箱即用）  │    │ （开箱即用）  │
 └─────────────┘    └─────────────┘    └─────────────┘
```

### 3.2 核心组件说明

| 组件 | 职责 | 技术选型 | 交付方式 |
|------|------|---------|---------|
| lx-common-bom | 统一版本清单，业务项目 `import` 一次 | Maven BOM | pom |
| lx-common-core | 核心契约：统一响应、响应码、业务异常、分页模型、ID 生成 | 纯 Java（Spring 注解 compileOnly） | jar |
| lx-common-web-starter | Web 层自动装配：全局异常处理、requestId 过滤器 | Spring Boot AutoConfiguration | jar |
| lx-common-mybatis-starter | 持久层自动装配：BaseEntity、分页插件、自动填充、逻辑删除、UUID 主键 | MyBatis-Plus + AutoConfiguration | jar |
| lx-common-crypto | 凭据加解密（AES-GCM），key 外部注入 | Java 标准 JCE | jar |
| lx-common-util | 通用工具类沉淀 | 纯 Java | jar |

---

## 四、关键模块设计

### 4.1 模块划分与现有代码映射

| SDK 模块 | 内容 | 来源（Light Deploy 现有类） | Spring 依赖 |
|---------|------|---------------------------|------------|
| core | `ApiResponse<T>`、`ResponseCode`、`BizException`（由 `DeployException` 泛化）、`PageQuery` / `PageResult`、`IdGenerator` | `common/response/*`、`common/model/Page*`、`common/util/IdGenerator`、`common/exception/DeployException` | 无 |
| web-starter | `GlobalExceptionHandler`、`RequestTraceFilter`、Jackson 敏感字段序列化约定 | `common/exception/GlobalExceptionHandler`、`common/web/RequestTraceFilter` | spring-boot-starter-web |
| mybatis-starter | `BaseEntity`、分页插件、`createTime`/`updateTime` 自动填充、`is_deleted` 逻辑删除、UUID 主键策略 | `common/model/BaseEntity`、`config/MybatisPlusConfig`、`config/UuidIdentifierGenerator` | mybatis-plus starter |
| crypto | `CredentialCrypto`（AES-GCM）、key 注入配置项（泛化环境变量名）、`AesKeyGenerator` | `common/crypto/*` | 无或极轻 |
| util | 通用工具类（持续沉淀） | 各处散落工具方法 | 无 |

**不进入 SDK 的内容**：`CodeGenerator`（代码生成器）与 Light Deploy 项目模板强绑定，保留在项目内；业务实体、枚举（`ServerAuthType` 等）属于业务域，不进 SDK。

### 4.2 web-starter 自动装配设计

```
引入 lx-common-web-starter
    |
    v
Spring Boot 启动扫描 AutoConfiguration.imports
    |
    ├─ GlobalExceptionHandler  ← @ConditionalOnProperty(lx.web.global-exception-handler.enabled, matchIfMissing=true)
    └─ RequestTraceFilter     ← @ConditionalOnProperty(lx.web.trace-id.enabled, matchIfMissing=true)
                                 @ConditionalOnMissingBean（使用方可自定义覆盖）
```

关键配置项（附 spring-configuration-metadata，IDE 可提示）：

```properties
# 关闭平台统一异常处理（使用方自定义）
lx.common.web.global-exception-handler.enabled=false
# 关闭 requestId 过滤器
lx.common.web.trace-id.enabled=false
```

### 4.3 mybatis-starter 自动装配设计

- `BaseEntity`、分页插件、自动填充器、逻辑删除配置通过自动装配类注册；
- 分页插件注册使用 `@ConditionalOnMissingBean`，使用方可整体覆盖；
- 逻辑删除值（0/1）、主键策略（`ASSIGN_UUID`）作为**默认值**而非强制约束，提供配置项：
  - `lx.common.mybatis.id-type=assign_uuid`（默认，可改为 assign_id 等）；
- `IdGenerator` 保持独立工具类身份，主键策略配置引用它，遵循「禁止业务代码直接调用 `UUID.randomUUID()`」的既定约定。

### 4.4 crypto 模块设计

- `CredentialCrypto` 保持 AES/GCM/NoPadding 实现，逻辑不变；
- key 注入从硬编码环境变量名 `LIGHT_DEPLOY_AES_KEY` 泛化为配置项：
  - `lx.common.crypto.aes-key-env`（默认值 `LX_COMMON_AES_KEY`，Light Deploy 迁移时配为原变量名，行为不变）；
- SDK 不内置任何 key，禁止 key 进入源码、Git、日志。

### 4.5 业务项目接入方式

```xml
<!-- 父 pom dependencyManagement 中 import 一次 -->
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>com.lx</groupId>
            <artifactId>lx-common-bom</artifactId>
            <version>1.0.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>

<!-- 按需引入，不写版本号 -->
<dependency>
    <groupId>com.lx</groupId>
    <artifactId>lx-common-web-starter</artifactId>
</dependency>
```

效果：引入 starter 即自动获得统一响应、异常处理、requestId、分页等能力，无需任何代码；不需要的模块不引入即可。

---

## 五、技术选型

### 5.1 仓库选型评估矩阵

| 评估维度 | 自建 Nexus | GitHub Packages | 阿里云云效 Packages | Maven Central | 权重 |
|---------|-----------|----------------|--------------------|---------------|------|
| 运维成本 | 高（需自维护服务） | 低（零运维） | 低 | 低 | 25% |
| 私有性 | 完全可控 | 私有 | 私有 | 完全公开 | 20% |
| 接入便利性 | 内网最好 | 拉取需 PAT token | 云效内免 token | 拉取零配置 | 20% |
| 与现有工作流契合 | 一般 | 高（已有 GitHub + gh + Actions） | 一般 | 一般 | 20% |
| 自动化发版 | 需自建 CI | 原生 Actions 支持 | 云效流水线 | 需签名审核，流程重 | 15% |
| **综合结论** | 备选（团队扩大后） | **首选（私有）** | 备选 | 备选（开源时） | — |

**结论**：
- **私有场景（当前推荐）**：GitHub Packages。零运维、与现有 Git 工作流和 CI 天然整合，打 tag 自动发版。代价是每个新项目需在 `~/.m2/settings.xml` 配置一次 PAT。
- **未来开源**：迁移 Maven Central（需 GPG 签名与 namespace 验证），SDK 结构不变，仅调整 `distributionManagement`。
- **团队扩大后**：可平滑迁移自建 Nexus，同样是改 `distributionManagement` 地址，方案不锁死。

### 5.2 模块形态选型

| 决策点 | 方案A | 方案B | 结论 |
|--------|-------|-------|------|
| 工程形态 | 单模块大 jar | 多模块 + BOM + starter | 方案B。单 jar 依赖传染、无法排除、升级粒度粗 |
| 能力注入方式 | 纯类库（使用方手动注册 Bean） | Spring Boot 自动装配 + 开关 | 方案B。开箱即用且可关闭，兼顾灵活性 |
| 版本管理 | 各业务项目自写版本号 | BOM 统一 import | 方案B。版本全局唯一，升级一处生效 |

---

## 六、版本与发布策略

### 6.1 版本规则（语义化版本）

- `1.x.y` 正式版：只做向后兼容变更，生产项目一律使用；
- `1.x.y-SNAPSHOT`：日常迭代通道，可被同名覆盖，仅用于联调；
- 破坏性变更（如 `ApiResponse` 字段调整）升主版本，老版本保留重叠期并标注 `@Deprecated`。

### 6.2 发布流水线

```
git push → main 分支     → CI 执行 mvn deploy → 发 SNAPSHOT
git tag  → v1.2.0        → CI 执行 mvn deploy -P release → 发正式版
                            同时生成 GitHub Release + Changelog
```

### 6.3 依赖治理原则

- SDK 对 Spring Boot / MyBatis-Plus 相关依赖尽量声明为 `optional`/`provided`，版本交给使用方的 Boot BOM 管理，避免版本传导污染；
- SDK 父 pom 通过 `dependencyManagement` 引入 Spring Boot BOM 对齐自身编译基线；
- PAT 凭据只存在于本机 `~/.m2/settings.xml` 与 CI secrets，不进 Git（与 `LIGHT_DEPLOY_AES_KEY` 治理思路一致）。

---

## 七、性能与容量

本方案为纯 Java 基础库，不引入网络 I/O 与额外运行时开销（过滤器、异常处理器为请求链路内的轻量组件），无独立性能基准需求。迁移阶段以「Light Deploy 现有接口行为回归一致」作为验证标准，不引入新性能指标。

> 说明：暂无可靠第三方基准数据，本方案不引用孤立性能数字。

---

## 八、安全与合规

1. SDK 源码、pom、文档中不得出现任何 AES key、密码、Token；
2. crypto 模块 key 仅通过外部环境变量/配置注入，禁止写入源码、数据库、日志；
3. 响应对象序列化默认排除密码、密文、私钥、Token 等敏感字段（`WRITE_ONLY` 约定文档化）；
4. 日志中禁止输出敏感信息（延续项目日志约定）；
5. `settings.xml` PAT 与 CI secrets 分离管理，定期轮换。

---

## 九、风险与降级

| 风险 | 概率 | 影响 | 降级方案 | 负责人 |
|------|------|------|---------|--------|
| 依赖版本传导污染业务项目 | 中 | 高 | 相关依赖声明 optional/provided，版本交由使用方 BOM 管理；发版前用 `mvn dependency:tree` 审查 | 开发（木子） |
| 自动装配绑架使用方 | 中 | 中 | 全部装配点提供 enabled 开关 + `@ConditionalOnMissingBean` 覆盖点 | 开发（木子） |
| Light Deploy 迁移期间行为不一致 | 中 | 高 | 迁移采用「先并存后切换」：新增 SDK 依赖、逐类替换、每步 `mvn -o -q -DskipTests compile` 验证，最后删除旧实现并回归接口 | 开发（木子） |
| GitHub Packages PAT 泄露 | 低 | 中 | PAT 仅存本机与 CI secrets，最小权限（read:packages/write:packages），泄漏即吊销重发 | 开发（木子） |
| SDK 与某业务项目 Spring Boot 版本不兼容 | 低 | 中 | 基线锁定 Boot 4.x；不兼容项目继续使用旧版本 SDK 不升级；必要时提供兼容分支 | 开发（木子） |
| 后续开源需要迁移仓库 | 低 | 低 | 仓库地址集中在 `distributionManagement`，一处修改即可切换 | 开发（木子） |

---

## 十、实施计划

| 阶段 | 内容 | 产出 | 验证标准 |
|------|------|------|---------|
| 1. 骨架搭建 | 建多模块工程 + BOM + 空模块 | 可 `mvn install` 的骨架 | 全模块编译通过 |
| 2. 代码迁移 | 从 Light Deploy 抽取五类代码，Light Deploy 改依赖 SDK | 两边编译通过、行为不变 | `mvn -o -q -DskipTests compile` + 接口回归 |
| 3. starter 化 | 自动装配 + 条件开关 + 配置元数据 | 引入即生效、可关闭 | 引入空项目验证装配与关闭开关 |
| 4. 仓库接入 | GitHub Packages、distributionManagement、Actions 发版流水线 | `mvn deploy` 跑通 | SNAPSHOT 与正式版均可拉取 |
| 5. 试点接入 | Light Deploy 正式切换 + 下一个新项目开箱验证 | 接入文档 + Changelog 机制 | 新项目 10 分钟内完成基座搭建 |

---

## 十一、待确认决策点

| 编号 | 决策点 | 选项 | 影响 |
|------|--------|------|------|
| D-01 | SDK 定位 | 私有（GitHub Packages）/ 开源（Maven Central） | 直接决定仓库方案与发版流程 |
| D-02 | 兼容基线 | 仅 Java 21 + Boot 4.x / 向下兼容 Boot 3.x | 决定 core 模块可用的语言与 API 特性 |
| D-03 | groupId 命名 | `com.lx` / 个人域名反向 / 其他 | 决定所有坐标，发 Central 前必须确定 |

---

> 本方案遵循「先轻量落地、再持续沉淀」原则：第一版只迁移 Light Deploy 已验证的通用能力，不提前引入未使用的模块。后续通用能力（如 Redis 封装、日志切面等）在实际项目中第二次出现时再并入 SDK。
