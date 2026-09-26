# Docker 镜像构建发布方案评估

> 状态：**评估稿，仅供评审**。本文不含已实现代码，所有接口/DDL 均为设计草案。
> 评估日期：2026-09-26 ｜ 评估范围：DOCKER 运行时的构建链路、发布链路、配置建模、扩展点设计
> 约束基线：AGENTS.md（§3 发布类型 / §4 数据库 / §23 项目建模 / §23.4 镜像仓库 / §23.5 版本号 / §23.6 保留策略）

---

## 0. 结论速览

| # | 提出的问题 | 评估结论 | 强度 |
|---|---|---|---|
| 1 | 现状是不是「拉镜像 + compose 启动」？ | **不是**。`DeployTaskHandler.deployDocker` 是 `docker pull` + `docker rm -f` + `docker run -d --restart=always`，**全项目零 compose 代码**。构建侧 build→push 链路已完整 | 事实 |
| 2 | 方式一（只拉现成镜像）vs 方式二（平台全承包） | **方式二为主链路**；方式一保留为一个独立构建模式，作为「镜像已在仓库、平台只管分发」的补充 | 推荐 |
| 3 | 方式二下构建机还需要吗？ | **需要，且是核心**。但平台模板模式下构建机只需 `git` + `docker`，**不需要 maven** | 推荐 |
| 4 | Java 是不是要先在构建机 maven 打成 jar 再打镜像？ | **不是**。平台模板走 Docker 多阶段构建（容器内 `mvn package`），maven 在镜像里。只有 `SCRIPT_DEPLOY`（JAR 运行时）才需要构建机装 maven | 事实澄清 |
| 5 | 推送前「仓库有没有这个镜像/版本」人工查还是平台查？ | **平台查连通性与登录；不查镜像存在性**。tag 由版本号规则天然唯一，不存在覆盖问题 | 推荐 |
| 6 | tag / 版本号人工设置还是平台定稿？ | **平台定稿，不改**。`{编码}-v{序号}-{短commit}` 四处共用（§23.5），人工只能指定序号 | 强约束 |
| 7 | 平台模板只做简单单体，复杂构建交给项目自带文件 | **同意**，并建议模板按「语言」再分一档（Java / Python / Node），不做 compose 生成 | 推荐 |
| 8 | 要不要工厂模式？ | 主链路**暂缓**（分支只有 2–3 个，switch 更可读）；**Dockerfile 模板生成层必须**用策略模式 | 推荐 |
| 9 | 要不要按运行时分表？ | **暂不分表**（对称三张表 ❌）。拆分落在**契约层**与 **UI 层**，存储层保持三段 JSON。触发条件见 §5.5 | 核心结论 |

---

## 1. 现状盘点（代码事实）

### 1.1 已经有的（可直接复用）

| 能力 | 位置 | 现状 |
|---|---|---|
| DOCKER 构建主链路 | `BuildTaskHandler.buildDocker`（L663–711） | `resolveImageRepository` → 按模式选 Dockerfile → `dockerBuild` → `dockerPush` → `dockerDigest` → 落 `build_artifact` |
| 三种 Docker 构建模式 | 同上 | `PROJECT_DOCKERFILE` 用仓库内 Dockerfile；`LAYERED_DOCKER` 先构建推送 `base-{tag}` 再构建 app（传 `--build-arg BASE_IMAGE=`）；`PLATFORM_TEMPLATE` 平台生成模板 |
| 平台模板内容 | `generatePlatformDockerfile`（L1493–1507） | **硬编码 Maven**：`maven:3.9-eclipse-temurin-21 AS build` → `eclipse-temurin:21-jre`。**这就是「只支持 Java」的真实现状** |
| 镜像仓库登录/登出 | `dockerPush` / `cleanupRegistryCredential`（L1344–1418） | 密码走临时文件 + `--password-stdin`，`finally` 里必清 |
| DOCKER 发布 | `DeployTaskHandler.deployDocker`（L506–540） | `docker pull` → `docker rm -f` → `docker run -d --restart=always` → `docker inspect` 确认运行态 |
| 版本记录 | `release_record.image_tag` / `image_digest` | 发布侧读 `image_tag` 拉镜像；**回滚 = 重发 `old_version` 的 tag** |
| 产物类型 | `ArtifactType` | `BASE_IMAGE` / `APP_IMAGE` / `JAR` / `STATIC` |
| 仓库档案 | `registry` 表 | `registry_url` + `namespace` + `credential_id` + `status` |
| 项目连通性预检 | `ProjectConnectTestTaskHandler` | 已做 `git ls-remote` + `docker login` + logout，**未做 push 权限与 namespace 探测** |
| 版本号规则 | `BuildService.resolveReleaseVersion` | `{编码}-v{序号}-{短commit}`，四处共用（镜像 tag / 产物目录 / VERSION 文件 / release_record） |

### 1.2 缺的（本次要补的）

| 缺口 | 影响 |
|---|---|
| compose 完全缺失 | AIops 这类多容器/带 volume/env 的项目只能退化成 `docker run`，`extra_docker_args` 会越写越长 |
| 平台模板只支持 Maven | Python（AIops/FastAPI）、Node 项目无法走平台模板 |
| `dependency_hash` 恒空 | 分层构建每次都重跑 base 镜像，AI 依赖体积大时构建很慢 |
| 无「镜像已在仓库，只分发」的模式 | 外部 CI 已推好镜像的场景无法接入 |
| 仓库无写权限预检 | namespace 不存在 / 无 push 权限要到首次 `docker push` 才暴露 |
| 配置无 schema 校验 | `build_config_json` 只在 Service 层 `readTree()` 验 JSON 合法性，语义靠执行器运行时报错 |
| 发布无「旧容器优雅停止」 | 现在直接 `docker rm -f`，无 `docker stop -t` 宽限期 |

---

## 2. 目标形态（方式二主链路）

```
┌─ 构建阶段（构建机，SSH）────────────────────────────────────┐
│  git clone（凭据注入 + 脱敏）                                │
│    → 定稿版本号 {编码}-v{序号}-{短commit}                    │
│    → 按 BuildMode 选 Dockerfile：                           │
│        PLATFORM_TEMPLATE  → 语言模板工厂生成（Java/Python/…）│
│        PROJECT_DOCKERFILE → 仓库内 Dockerfile               │
│        LAYERED_DOCKER     → Dockerfile.base → push          │
│                             Dockerfile.app  → push          │
│        IMAGE_PULL（新增） → 跳过构建，只登记镜像坐标          │
│    → docker build → docker login → docker push              │
│    → docker inspect 取 RepoDigests                          │
│    → 落 build_artifact(BASE_IMAGE/APP_IMAGE)                │
│    → 生成 release_record（image_tag + image_digest）         │
└─────────────────────────────────────────────────────────────┘
                            ↓ 镜像仓库（唯一分发通道）
┌─ 发布阶段（目标机，SSH）────────────────────────────────────┐
│  deploy_form = RUN（默认）                                   │
│    docker pull {image_tag} → docker rm -f → docker run       │
│  deploy_form = COMPOSE（新增）                               │
│    渲染/拉取 compose 文件 → IMAGE_TAG=xxx docker compose up -d│
│    → 健康检查 → 标记成功                                     │
│  回滚 = 用 old_version 的 image_tag 重跑同一条链路            │
└─────────────────────────────────────────────────────────────┘
```

**为什么构建机不能省**（方式二）：

1. 目标机不该有源码，也不该有 git 凭据；
2. 一台构建机给 6 台目标机供镜像，目标机只 `pull`——这正是镜像仓库存在的意义；
3. `docker build` 上下文（node_modules、.git、target）不该出现在生产机上；
4. 目标机磁盘/网络策略往往更严格，构建失败也不该污染生产机。

**为什么平台模板模式下构建机不需要 maven**：`generatePlatformDockerfile` 用的是多阶段构建，`mvn package` 在 `maven:3.9-eclipse-temurin-21` 容器里跑。构建机只需要 `git` + `docker`。这一点与你设想的「先从仓库拉源码 → 构建机 maven 打成 jar → 再打镜像」不同——**那个形态是 `SCRIPT_DEPLOY`，不是 `PLATFORM_TEMPLATE`**。

> 代价：容器内构建没有 `~/.m2` 缓存，每次全量下载依赖。首次构建可能 5–10 分钟。优化方向是构建机挂 `-v $HOME/.m2:/root/.m2`（需模板支持 build-arg 挂载，属于后续可选项）。

---

## 3. 架构决策记录（ADR）

### ADR-01：新增 `IMAGE_PULL` 构建模式，而非二选一

- **场景**：镜像已由外部 CI 推好，平台只想管分发与回滚。
- **决策**：`BuildMode` 新增 `IMAGE_PULL`（镜像直发）。该模式下**不拉代码、不构建、不推镜像**，直接按「仓库坐标 + 人工填写的 tag」生成 `release_record`。
- **理由**：它是方式一的合法落地形态，成本极低（不需要构建机、不需要 git 凭据）；与 `ARTIFACT_ONLY` 对称（后者是「产物直发」）。
- **代价**：版本号无 commit 短码，退化为 `{编码}-v{序号}-{时间戳}`（§23.5 已定义该退化规则）；`image_digest` 需要平台在构建机/目标机 `docker manifest inspect` 或 `docker pull` 后 `inspect` 获取——**建议登记时允许 digest 为空**，拉取时以 tag 为准。
- **状态**：提议

### ADR-02：平台模板按语言分档，不做 compose 生成

- **场景**：Java / Python（FastAPI）/ Node 都要能用平台模板。
- **决策**：`BuildConfig` 新增 `language`（`JAVA` / `PYTHON` / `NODE`，选填，未填时按仓库特征探测：`pom.xml`/`build.gradle` → JAVA，`requirements.txt`/`pyproject.toml` → PYTHON，`package.json` → NODE）。模板由策略类按 language 生成。
- **理由**：三者的构建命令、依赖安装、运行入口差异是**结构性的**，塞进一个模板会变成一堆 `if`。按语言拆是最自然的扩展点。
- **代价**：模板要维护三份；语言探测可能误判，因此 `language` 必须**允许显式覆盖**。
- **状态**：提议

### ADR-03：发布形态 `deploy_form` = RUN / COMPOSE

- **场景**：AIops 用 compose，多数中小企业是单容器 `docker run`。
- **决策**：`runtime_config_json.deploy_form` 取值 `RUN`（默认，保持现状）/ `COMPOSE`。COMPOSE 模式下 compose 文件**来自项目仓库**（`build_config_json.compose_file_path`），平台不生成。
- **理由**：平台生成 compose 是最复杂的部分（服务名、volume、network、env_file、depends_on 各家差异极大），生成出来的东西必然不够用。让仓库自带、平台只注入 `IMAGE_TAG` 环境变量，是最省事且不会出错的做法。
- **代价**：项目必须自己维护 compose 文件，且文件里镜像引用必须写成 `${IMAGE_TAG}`；平台要在执行前校验该占位符存在。
- **状态**：提议

### ADR-04：镜像 tag 继续由平台定稿，人工只指定序号

- **场景**：tag / 版本号谁来定。
- **决策**：**不改现有规则**。`{编码}-v{序号}-{短commit}`，人工只能通过 `build.version_seq` 指定序号。
- **理由**：该字符串四处共用（§23.5），一旦允许人工自由填写 tag，镜像 tag 与 `release_record.version` / 产物目录 / VERSION 文件就会脱节，回滚时按版本找不到镜像。
- **代价**：无法复用外部 CI 已打的 tag（`aiops-app:git-a1b2c3d` 这类）。这个场景走 ADR-01 的 `IMAGE_PULL` 模式解决，而不是放宽 tag 规则。
- **状态**：已接受（沿用 §23.5）

### ADR-05：仓库预检只查「连通 + 登录 + namespace 可写」，不查镜像存在性

- **场景**：推送前要不要检查仓库里有没有同名镜像。
- **决策**：
  - **不查镜像存在性**——tag 含项目内递增序号，天然不会撞；同一 commit 重复构建也因序号递增而互不覆盖。
  - **要查可写性**——在 `PROJECT_TEST`（项目连通性测试）里补一步：login 成功后，用 `docker build` 一个极小镜像（`FROM scratch` 不可用，用 `busybox` 或 `alpine`）push 到 `{namespace}/_ld_probe:{时间戳}`，成功即删。
- **理由**：namespace 不存在、账号无 push 权限是当前唯一的「首次 push 才炸」类问题（构建模块文档 §8 已列为遗留）。探测性 push 是唯一能提前暴露的手段。
- **代价**：预检多一次 build+push+删除，约 10–30 秒；需要清理失败兜底（删不掉只告警）。**必须做成可选**（`registry.probe_on_test`），避免每次连通性测试都变慢。
- **状态**：提议

### ADR-06：分层构建 v1 不做依赖指纹复用

- **场景**：AIops 的 `Dockerfile.base` 每次都重跑，AI 依赖体积大。
- **决策**：**v1 保持每次全量构建 base**（现状 `base-{tag}`）。`dependency_hash` 字段继续预留。
- **理由**：复用 base 意味着 app 镜像 `FROM` 的 base tag 变了，而 base tag 当前绑定版本号——要解耦就得引入 `base tag = dep-{指纹}` 的第二套命名，并把「base 镜像的生命周期」从「本次构建」变成「跨构建共享」。这会牵动 §23.6 的保留策略（删一个版本时不能删掉别人正在引用的 base）。
- **代价**：构建慢。**缓解**：v1.5 再引入 `dependency_files`（参与指纹的文件清单，如 `requirements.txt` / `pyproject.toml` / `Dockerfile.base`），指纹未变时复用最近一条 `BASE_IMAGE` 产物并**在 `build_artifact` 上记录引用关系**，回收时跳过被引用的 base。
- **状态**：提议

### ADR-07：配置存储层不分表，拆分落在契约层与 UI 层

- 详见 §5。**这是本文档最核心的结论。**
- **状态**：提议

### ADR-08：Dockerfile 模板生成用策略模式，主链路保持 switch

- 详见 §6。
- **状态**：提议

---

## 4. 三种运行时 × 构建模式 × 发布形态（目标矩阵）

| 运行时 | 构建模式 | 产物 | 走仓库 | 发布形态 | 构建机 |
|---|---|---|---|---|---|
| DOCKER | `PLATFORM_TEMPLATE` | APP_IMAGE | 是 | RUN / COMPOSE | 需要（git+docker） |
| DOCKER | `PROJECT_DOCKERFILE` | APP_IMAGE | 是 | RUN / COMPOSE | 需要（git+docker） |
| DOCKER | `LAYERED_DOCKER` | BASE_IMAGE + APP_IMAGE | 是 | RUN / COMPOSE | 需要（git+docker） |
| DOCKER | `IMAGE_PULL`（新增） | 无（外部已推） | 是（只读） | RUN / COMPOSE | **不需要** |
| JAR | `SCRIPT_DEPLOY` | JAR | 否 | systemd | 需要（git+maven） |
| JAR | `ARTIFACT_ONLY` | JAR（已有） | 否 | systemd | 不需要 |
| STATIC | `SCRIPT_DEPLOY` | STATIC | 否 | Nginx | 需要（git+node） |
| STATIC | `ARTIFACT_ONLY` | STATIC（已有） | 否 | Nginx | 不需要 |

> 组合校验的唯一来源仍是 `RuntimeType.supportedBuildModes`（AGENTS.md §23.4）。新增 `IMAGE_PULL` 时**必须同时改两处**：`RuntimeType` 枚举 + 前端 `BUILD_MODE_OPTIONS`。

---

## 5. 分表评估（核心）

### 5.1 先清点：参数到底有多少

当前 `project` 表的异构参数全在三段 JSON 里（`BuildConfig` 10 个 + `DeployConfig` 11 个 + 健康检查 6 个 = 27 个键，前端 `projectConfig.ts` 510 行）：

| 归属 | 字段数 | 字段 |
|---|---|---|
| 构建公共 | 5 | `module_name` `module_dir` `pom_path` `build_command` `command_timeout_seconds` |
| DOCKER 构建独占 | 4 | `dockerfile_path` `base_dockerfile_path` `image_repository` `language`（拟增） |
| JAR/STATIC 构建独占 | 2 | `artifact_path` `config_paths` |
| DOCKER 运行独占 | 3（拟 5） | `container_name` `port_mappings` `extra_docker_args` + `deploy_form` `compose_file_path` |
| JAR 运行独占 | 6 | `work_dir` `service_name` `jvm_options` `java_bin` `spring_profiles_active` `config_dir` `config_file` |
| STATIC 运行独占 | 1 | `static_root` |
| 公共运行 | 1 | `deploy_base` |

加上 compose 落地后会新增的（`compose_file_path` / `compose_service_name` / `env_file` / `stop_timeout_seconds` / `pull_policy` …），DOCKER 独占项约 **8–10 个**，JAR 约 **7 个**，STATIC 1 个。

**结论：差异确实大，但绝对数量并不大。真正的痛点是「JSON 没有类型、没有校验、表单长得没有边界」，不是「一张表装不下」。**

### 5.2 候选方案

| 方案 | 形态 | 优点 | 代价 |
|---|---|---|---|
| **A. 对称三张表** | `project_jar_config` / `project_docker_config` / `project_static_config` | 语义最清晰；列有类型/注释/非空约束；可直接 SQL 检索 | ① STATIC 只有 1 个参数，为它建表是纯负债；② 运行时切换要处理旧子表记录（删除 or 留孤儿）；③ 详情查询变 2 次或 LEFT JOIN；④ 新增/编辑要跨表事务（§4.6）；⑤ **每次加参数都要 DDL** |
| **B. 单张扩展表** | `project_runtime_config`（`runtime_type` + 并集列，可空） | 只需一张表；JOIN 固定；避免对称负债 | ① 列是三种运行时的并集，可空列一大堆，可读性反而更差；② 同样要 DDL；③ `runtime_type` 与列的合法性要靠应用层校验 |
| **C. 不分表 + 契约层类型化** | 存储仍是三段 JSON；Java 侧按运行时拆配置类 + 解析器 + 校验器；UI 按运行时分页签 | ① **加参数零 DDL**；② 完全不动 §23.2 已定契约；③ 校验/默认值/联动逻辑有唯一归属；④ 前端表单可按运行时精确渲染 | ① JSON 无数据库级约束，强校验全靠代码；② 无法用 SQL 按运行时参数检索 |

### 5.3 决定性论据：**DDL 成本**

AGENTS.md 已明确（记忆 §平台自身部署）：

> **MySQL 结构升级必须手工执行**——部署链路只换包重启，无 DDL 能力。

这条约束下：

- 方案 A / B：DOCKER 参数**每演进一次**（compose、build args、platform、资源限制、健康检查方式…），都要用户手工跑一次 SQL。而这个平台当前正处于 Docker 能力的**快速演进期**。
- 方案 C：加参数只改 Java 配置类 + 前端常量 + 文档，**换包重启即可**。

对一个个人开发者维护、面向中小企业、用户自己运维的平台，**"加一个输入框要用户去跑 SQL"是实打实的运维负担**，而"JSON 少一层数据库级校验"是可以靠代码补的。

### 5.4 推荐：方案 C

1. **存储层不动**。`project` 主表三段 JSON 保留（§23.2 契约不变）。
2. **契约层拆**：
   - `BuildConfig` 按运行时拆为 `DockerBuildConfig` / `JarOrStaticBuildConfig`（或保留 `BuildConfig` 基类 + 子类）；
   - `DeployConfig` 同理拆为 `DockerDeployConfig` / `JarDeployConfig` / `StaticDeployConfig`；
   - 新增 `RuntimeConfigResolver`：按 `runtime_type` 解析对应子类、**校验合法性、填充默认值、做字段冲突检查**（例如 `module_dir` 与 `-pl` 同现这类，现在靠前端拦，应下沉到后端）；
   - JSON 加 `schema_version` 字段（默认 1），为将来结构变更留迁移入口。
3. **UI 层必须拆**：项目编辑页改成 4 个页签——`基础信息` / `构建配置`（按 BuildMode 变） / `运行配置`（按 runtime_type 变） / `健康检查`。字段定义仍来自 `constants/projectConfig.ts`，但按 `runtimes` / `buildModes` 分组过滤（该文件已有这个机制，扩展即可）。

### 5.5 什么时候才该物理分表（触发条件）

满足**任一**即可重新评估拆表，且**只拆最痛的那一张**（`project_docker_config`），不做对称三张：

1. 单一运行时的独占配置项 **> 20 个**，且 JSON 已经出现「同一键在不同运行时含义不同」的冲突；
2. 出现**按运行时参数检索/统计**的需求（如「找出所有用了 compose 的项目」）；
3. 该运行时的配置需要**独立的历史版本或审计**；
4. Docker 参数进入**稳定期**（连续两个迭代没有新增字段）。

> 补充观察：现在 `project` 主表上已经有两个「只对 DOCKER 有意义」的列——`build_mode` 与 `registry_id`（AGENTS.md §23.4 明确「非 DOCKER 运行时必须为空」）。这本身就是分表需求的早期信号。**建议列入观察项，但不作为本次拆表的理由**——因为它们还承担「组合校验入口」的职责，挪走会让 `RuntimeType.supports` 的校验链路变长。

### 5.6 若坚持分表：最小代价的 DDL 草案（**未执行，仅作对照**）

```sql
CREATE TABLE IF NOT EXISTS project_docker_config (
    id VARCHAR(64) NOT NULL COMMENT '主键',
    project_id VARCHAR(64) NOT NULL COMMENT '项目ID，唯一，指向 project 表',
    build_mode VARCHAR(30) NOT NULL COMMENT '构建模式：PLATFORM_TEMPLATE/PROJECT_DOCKERFILE/LAYERED_DOCKER/IMAGE_PULL',
    language VARCHAR(20) NULL COMMENT '语言：JAVA/PYTHON/NODE，PLATFORM_TEMPLATE 时使用',
    registry_id VARCHAR(64) NULL COMMENT '镜像仓库ID，指向 registry 表',
    image_repository VARCHAR(255) NULL COMMENT '镜像仓库坐标（不含tag），留空由 registry 推导',
    dockerfile_path VARCHAR(255) NULL COMMENT '仓库内 Dockerfile 相对路径',
    base_dockerfile_path VARCHAR(255) NULL COMMENT '分层构建基础层 Dockerfile 路径',
    compose_file_path VARCHAR(255) NULL COMMENT '仓库内 compose 文件相对路径',
    deploy_form VARCHAR(20) NOT NULL DEFAULT 'RUN' COMMENT '发布形态：RUN/COMPOSE',
    container_name VARCHAR(100) NULL COMMENT '容器名，默认项目编码',
    port_mappings VARCHAR(255) NULL COMMENT '端口映射，多个逗号分隔',
    extra_docker_args VARCHAR(500) NULL COMMENT '附加 docker run 参数',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_docker_cfg_project (project_id, is_deleted),
    KEY idx_docker_cfg_registry (registry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目Docker构建运行配置';
```

**采用此方案必须同步处理**（缺一不可，否则会留下半截数据）：

| # | 事项 | 说明 |
|---|---|---|
| 1 | 事务边界 | `ProjectService.create/update` 必须 `@Transactional(rollbackFor = Exception.class)`（§4.6），主表与子表同生共死 |
| 2 | 运行时切换 | 从 DOCKER 改 JAR：子表记录**软删除**还是物理删？建议软删除 + 主表 `runtime_type` 为唯一真相来源；切回 DOCKER 时复用 |
| 3 | 数据迁移 | 存量 DOCKER 项目要把 `build_config_json` / `runtime_config_json` 里的 Docker 键搬到新表，升级脚本放在 `src/main/resources/db/`，**幂等 + 配 verify 脚本**（§4.5 硬性要求） |
| 4 | 字段归属 | `registry_id` / `build_mode` 从主表挪走后，`ProjectService.validateBusinessRule` 的组合校验要改读子表 |
| 5 | VO 组装 | `ProjectVo` 要额外查子表；列表页是否需要带 Docker 参数？不需要的话列表接口不能 JOIN |
| 6 | 文档同步 | AGENTS.md §23.2 / §23.4、项目模块文档、构建模块文档、发布模块文档、前端 `BUILD_MODE_OPTIONS` |
| 7 | 前端表单 | 编辑页改成「主表 + Docker 子表单」两段提交，或一个 DTO 带嵌套对象 |

---

## 6. 工厂 / 策略模式评估

### 6.1 不要抽象的地方（过度设计预警）

| 位置 | 现状 | 建议 |
|---|---|---|
| `DeployTaskHandler` 的 `switch (runtimeType)` | 3 个分支（DOCKER/JAR/STATIC） | **保持 switch**。三个分支的入参、产物、回滚方式完全不同，抽象成 `DeployStrategy` 接口得到的只是「一个方法三个实现」，反而多一层跳转 |
| `BuildTaskHandler` 的 `if (runtimeType == DOCKER)` | 2 个分支 | **保持 if/else**。同上 |
| `BuildMode` 的模式分支 | 3–4 个分支，在 `buildDocker` 内 | **保持**。各模式只差「Dockerfile 从哪来」，用 `if` 链最直观 |

**判断标准**：分支数 ≤ 4 且各分支无公共可复用步骤时，`switch` 比工厂更可读。当前全部命中，不要为「以后可能支持 Go/Rust」提前建工厂——那属于 AGENTS.md §18 明令禁止的「因以后可能需要提前创建实现类」。

### 6.2 必须抽象的地方：Dockerfile 模板生成

这才是真正会持续膨胀的扩展点。理由：

- 模板数量会随语言增长（Java / Python / Node / Go…），且每个模板的实现有 30–60 行字符串拼装；
- 模板有清晰的公共契约：输入「项目 + 构建配置」→ 输出「Dockerfile 文本」；
- 需要「探测 / 回退」逻辑：显式 `language` 优先，未填则按仓库文件特征探测，探测不到要报明确错误。

**设计草案（未实现）**：

```java
// common/enums/project/LanguageType.java —— 新增
public enum LanguageType {
    JAVA("JAVA", "Java", Set.of("pom.xml", "build.gradle", "build.gradle.kts")),
    PYTHON("PYTHON", "Python", Set.of("requirements.txt", "pyproject.toml", "Pipfile")),
    NODE("NODE", "Node.js", Set.of("package.json"));
    // probe(markerFiles) 按仓库根文件特征探测，多命中时按声明顺序取第一个
}

// build/template/DockerfileTemplate.java —— 新增
public interface DockerfileTemplate {
    LanguageType language();
    String render(Project project, BuildConfig config);   // 返回 Dockerfile 全文
    default String defaultBuildCommand() { ... }           // 各语言默认构建命令
}

// build/template/DockerfileTemplateFactory.java —— 新增
@Component
public class DockerfileTemplateFactory {
    private final Map<LanguageType, DockerfileTemplate> templates;
    // 构造器注入 List<DockerfileTemplate>，按 language() 建 Map
    public DockerfileTemplate resolve(Project project, BuildConfig config) {
        // 显式 language 优先 → 仓库特征探测 → 探测失败抛明确异常（列出受支持语言与支持的文件特征）
    }
}
```

**模板载体建议**：项目已有 Freemarker（`src/main/resources/templates/*.ftl`），把 Dockerfile 模板放 `resources/templates/docker/Dockerfile.java.ftl` 等，比 Java 字符串拼装更好维护。**需核实** Freemarker 依赖是否已在主作用域可用（代码生成器在用）。

### 6.3 扩展点清单（本次只开这三个）

| 扩展点 | 形态 | 时机 |
|---|---|---|
| 语言模板 | `DockerfileTemplate` + Factory | 本次（ADR-02） |
| 发布形态 RUN/COMPOSE | `deploy_form` 配置 + `deployDocker` 内分支 | 本次（ADR-03） |
| 运行时分支 | 保持 switch | **不动** |

---

## 7. 镜像仓库与版本号

### 7.1 tag 生成（沿用，不改）

```
镜像坐标 = build_config.image_repository
        ?: registry.registry_url + "/" + registry.namespace + "/" + (module_name ?: project.code)
镜像 tag  = {项目编码}-v{序号}-{短commit}          ← 与 release_record.version 同一个字符串
base tag  = base-{项目编码}-v{序号}-{短commit}      ← LAYERED_DOCKER 专用
```

### 7.2 预检设计（ADR-05）

| 检查项 | 时机 | 手段 | 失败处理 |
|---|---|---|---|
| 仓库地址可达 + 凭据可用 | `PROJECT_TEST`（已有） | `docker login` → logout | 直接失败，提示检查凭据 |
| namespace 存在 + 有 push 权限 | `PROJECT_TEST`（**新增，可选开关**） | 构建极小镜像 push 到 `{ns}/_ld_probe:{ts}`，成功后删 | 失败提示「无写权限或 namespace 不存在」；删除失败只告警 |
| registry 绑定存在 | 创建构建时（已有） | `BuildService.createAndTrigger` 校验 | 已有 |
| 镜像是否已存在 | **不做** | — | tag 含递增序号，天然唯一 |

> `_ld_probe` 探测镜像必须**异步清理失败也只告警**，且命名固定前缀便于人工排查残留。建议 `registry` 表加 `probe_enabled TINYINT DEFAULT 0` 开关——**这又是一列 DDL**，正好印证 §5.3 的论据。

### 7.3 分层构建的 base 镜像

现状：`base-{tag}`，每次构建全量重跑（ADR-06）。

| 项 | 现状 | v1.5 建议 |
|---|---|---|
| base tag | `base-{版本号}` | `base-{版本号}` 保留；若做指纹复用则改为 `dep-{指纹8位}` |
| 复用判断 | 无 | `dependency_files`（逗号分隔）内容 SHA256 → 查最近一条同指纹 `BASE_IMAGE` 产物 |
| 回收保护 | 按 §23.6 保护窗口 | **新增**：base 镜像被其它版本引用时不得回收 |
| 日志 | 无 | 复用时记一条「基础镜像命中缓存：{baseTag}，跳过构建」 |

---

## 8. compose 支持设计（ADR-03）

### 8.1 为什么 v1 先保 `docker run`

- 现有 `deployDocker` 已可用且经过设计（容器名幂等、`docker inspect` 二次确认运行态）；
- 中小企业大多数是单容器；
- compose 引入的是**文件来源 + 变量注入 + 多容器健康检查 + 回滚语义**四件事，一次性做完风险高。

### 8.2 落地设计（草案）

| 项 | 设计 |
|---|---|
| 文件来源 | 项目仓库内，`build_config_json.compose_file_path`（如 `deploy/docker-compose.yml`）；构建阶段随产物转存到构建机产物目录（与 JAR 的 `config_paths` 同一套机制） |
| 镜像引用 | compose 文件里必须写 `image: {仓库坐标}:${IMAGE_TAG}`，平台执行前校验该占位符存在，不存在直接报错并说明写法 |
| 发布命令 | `cd {工作目录} && IMAGE_TAG={tag} docker compose -p {项目编码} up -d --pull always` |
| 停止旧容器 | 发布前 `docker compose -p {项目编码} down --timeout {stop_timeout}`（默认 30s），**不用 `rm -f` 硬删** |
| 回滚 | 用 `old_version` 的 `image_tag` 重跑 `up -d`，与 RUN 形态共用回滚判定 |
| 环境变量 | 平台不注入业务 env；由 compose 文件自带 `env_file` 或 `environment`（平台注入 `IMAGE_TAG` 一个即可） |
| 项目名 | 固定 `-p {项目编码}`，保证多目标机上的 compose 栈名一致，避免 `up` 时找不到已有栈而新建 |

**必须校验的两点**（否则会静默出错）：

1. compose 文件里镜像引用必须含 `${IMAGE_TAG}`——写死 tag 会导致回滚失效（回滚时 `up` 拉到的还是旧文件里的固定 tag）；
2. `docker compose`（v2 插件语法）与 `docker-compose`（v1）兼容性——**建议**：先探测 `docker compose version`，失败再回落 `docker-compose`，并把实际使用的命令写进日志。

### 8.3 compose 与 RUN 的回滚差异

| 形态 | 回滚动作 | 风险 |
|---|---|---|
| RUN | `docker rm -f` + `docker run {old_tag}` | 无 |
| COMPOSE | `docker compose up -d` with `IMAGE_TAG={old_tag}` | compose 文件本身若随版本变化（新增服务等），回滚时用的是**旧版本转存的 compose 文件**，行为可能与新版本不一致。这一点要在文档里明示 |

---

## 9. 落地路线（建议 4 期，每期可独立交付）

| 期 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| **P1 打通最小闭环（真机验证）** | 现有 Docker 链路端到端跑通一次：Java 项目 `PLATFORM_TEMPLATE` 构建 → push → 目标机 pull → run。**不新增功能，只补洞**：构建日志、失败提示、仓库预检失败的可读性 | 无 | 一个真实 Java 项目全链路成功 |
| **P2 语言模板 + 策略模式** | `LanguageType` + `DockerfileTemplate` + Factory；Java / Python 两套模板；`build_config.language` 与仓库特征探测 | P1 | Python（FastAPI）项目走平台模板构建成功 |
| **P3 compose 发布** | `deploy_form`；compose 文件转存、`IMAGE_TAG` 注入、`docker compose up -d`；`docker compose` / `docker-compose` 兼容回落 | P1 | AIops 这类项目按 compose 发布并回滚成功 |
| **P4 镜像直发 + 仓库预检** | `IMAGE_PULL` 模式；`PROJECT_TEST` 增加可选的可写性探测 | P1 | 外部 CI 推好的镜像能被平台分发与回滚 |

**不建议同期做**：物理分表（§5）、依赖指纹复用（ADR-06）、多架构镜像（arm64/amd64）。

---

## 10. 风险与遗留

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 1 | 平台模板容器内构建无依赖缓存 | 首次构建 5–10 分钟，超时（默认 1800s）边缘 | 文档写明；提供 `command_timeout_seconds`；v1.5 挂 `~/.m2` / pip 缓存卷 |
| 2 | compose 文件写死镜像 tag | 回滚静默失效 | 执行前校验 `${IMAGE_TAG}` 占位符存在，缺失直接报错 |
| 3 | 目标机无 `docker compose` v2 插件 | P3 全部失败 | 命令探测 + 回落 v1，实际命令写日志 |
| 4 | 镜像仓库磁盘增长 | 仓库按 tag 累积，永不清理 | 平台只管推送不管仓库清理；文档提示用户在仓库侧配生命周期规则（阿里云 ACR 支持） |
| 5 | `IMAGE_PULL` 模式下 digest 可能为空 | 无法校验镜像一致性 | 允许为空但明示；可选在构建机 `docker manifest inspect` 补 digest |
| 6 | 语言探测误判 | 生成错误的 Dockerfile，构建失败 | `language` 允许显式覆盖；探测失败时报错并列出支持的文件特征，不猜 |
| 7 | 分表诉求被推迟 | 参数继续膨胀后 JSON 难维护 | §5.5 的 4 条触发条件；每期交付后复核一次 |
| 8 | 构建机磁盘 | 镜像 layer 缓存在构建机累积 | 定期 `docker system prune`；或把清理写进 `build_env_script`（需注意并发安全） |

---

## 11. 需要你拍板的 4 件事

1. **ADR-07 分表结论**：接受「不分表 + 契约层类型化 + UI 分页签」，还是坚持 `project_docker_config` 单表？（我的推荐是前者）
2. **P3 compose 的优先级**：是排在 P2 语言模板之前，还是之后？（AIops 是 Python + compose，若先做 compose 可以早点让 AIops 上线）
3. **ADR-06 分层构建**：v1 接受「每次全量重跑 base」吗？AI 依赖镜像体积多大、构建一次多久——这个数据决定要不要提前做指纹复用。
4. **平台模板要不要支持 Node**：现在只有 Java（Maven）。Python 是明确的（AIops），Node 是否本期一起做？
