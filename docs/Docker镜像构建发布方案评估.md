# Docker 镜像构建发布方案评估

> 状态：**评估稿 v5，方案已定稿，待排期实施**。本文不含已实现代码，所有接口/DDL/目录结构均为设计草案。
> 评估日期：2026-09-26（v5 修订 09-27）｜评估范围：DOCKER 运行时的构建链路、发布链路、配置建模、扩展点设计；附 STATIC 产物上传与版本化
> 约束基线：AGENTS.md（§3 发布类型 / §3.1 JAR 工作目录形态 / §4 数据库 / §23.4 镜像仓库 / §23.5 版本号 / §23.6 保留策略）

> **v2 修订**：v1 把 compose 判为「P3 可选项、文件来自仓库、平台不生成」，**该判断已作废**（ADR-03 / ADR-09）。
> **v3 修订**：compose 全量覆盖 + 强制备份（§8.5）；开放 `UPLOAD` + `ARTIFACT_ONLY`（ADR-10）；compose 单模板 + 可选段 + 场景预设（ADR-11）。另修正一处代码事实：`deployStatic` 早已实现，STATIC 只差产物入口。
> **v4 修订**：STATIC 一度选了「版本目录 + `current` 软链」方案（ADR-12 旧版）。
> **v5 修订（当前）**：**软链方案撤回**。改为 **方案 C「同级 `bak` 备份 + 目录整体替换」**——与 §3.1 JAR 链路完全同口径，**不要求改 nginx 配置**。理由见 §9.4.0。核心收益（回滚用目标机本地备份、不走构建机）完全保留。
> **v6 修订（2026-09-28）**：方案 C 的落地方向不变（同级 `bak` 备份、不动 nginx、不用软链），
> 但「**目录整体替换**」修正为「**目录内容替换**」——改为只搬目录**内**的条目，
> `static_root` 自身的 inode 必须恒定。起因是线上事故：容器里的 nginx 通过 bind mount 挂这个目录，
> 目录被 rename 走之后容器仍握着旧 inode，容器内目录为空 → 全站 403；
> `nginx -s reload` 无效（reload 不重新挂载），只能重建容器。代价是失去单次 rename 的原子性，
> 换来与容器部署形态兼容。硬约束见 AGENTS.md §3.2。

> **v7 修订（当前，2026-09-29）**：**ADR-03 的「平台渲染 compose」作废，改回 v1 判断——**
> compose 与 Dockerfile **统一由项目仓库提供**，平台不生成、不改写任何内容。
>
> 这与 v6 里 `PLATFORM_TEMPLATE` 整体下线是同一个理由：真实项目的 compose 有 20+ 个
> `environment`、`extra_hosts`、`external` 网络、数据卷与 depends_on 顺序，平台要渲染它
> 就得先把这些全部建模成配置项（ADR-11 的可选段、§7 的参数面 3→11）——
> **难的不在算法，而在建模成本**：用户每多用一个 compose 特性，都要回来求平台加一个字段；
> 而让项目直接给文件，成本是一次性的，且天然支持全部 compose 语法。
>
> 平台保留的唯一介入是**版本号注入**：约定一个 `${IMAGE_TAG}` 占位符，发布时以同名变量文件传入。
> 用变量而不是把占位符替换成具体 tag，是为了让「运维上机看到的 compose」与仓库里逐字一致——
> 否则排障时「文件里写的到底是啥」就不再可信。
>
> **连带作废**：ADR-11（单模板 + 可选段 + 场景预设）、§8.5 的「全量覆盖 + 强制备份」、
> §7 的 `ComposeRenderer` / `compose_preset` / 列表面参数（3→11 的参数扩容）、以及对应 DDL 列。
> ADR-03 的**场景论证**（为什么必须有 compose 形态）依然成立，只有「谁来产出文件」的结论变了。
>
> 已实现，当前口径见 [构建模块.md](构建模块.md)（快照时机）与 [发布模块.md](发布模块.md)（下发与启动）。
---
> 但「**目录整体替换**」修正为「**目录内容替换**」——改为只搬目录**内**的条目，
> `static_root` 自身的 inode 必须恒定。起因是线上事故：容器里的 nginx 通过 bind mount 挂这个目录，
> 目录被 rename 走之后容器仍握着旧 inode，容器内目录为空 → 全站 403；
> `nginx -s reload` 无效（reload 不重新挂载），只能重建容器。代价是失去单次 rename 的原子性，
> 换来与容器部署形态兼容。硬约束见 AGENTS.md §3.2。

---

## 0. 结论速览

| # | 提出的问题 | 评估结论 | 强度 |
|---|---|---|---|
| 1 | 现状是不是「拉镜像 + compose 启动」？ | **不是**。`DeployTaskHandler.deployDocker`（L506–540）是 `docker pull` + `docker rm -f` + `docker run -d --restart=always`，**全项目零 compose 代码**。构建侧 build→push 链路已完整 | 事实 |
| 2 | 方式一（只拉现成镜像）vs 方式二（平台全承包） | **方式二为主链路**；方式一保留为独立构建模式 `IMAGE_PULL` | 推荐 |
| 3 | 方式二下构建机还需要吗？ | **需要，且是核心**。平台模板模式下构建机只需 `git` + `docker`，**不需要 maven** | 推荐 |
| 4 | Java 是不是要先在构建机 maven 打成 jar？ | **不是**。平台模板走 Docker 多阶段构建（容器内 `mvn package`）。只有 JAR 的 `SCRIPT_DEPLOY` 才需要构建机装 maven | 事实澄清 |
| 5 | 推送前「仓库有没有这个镜像/版本」人工查还是平台查？ | **平台查连通性与登录；不查镜像存在性**。tag 由版本号规则天然唯一 | 推荐 |
| 6 | tag / 版本号人工设置还是平台定稿？ | **平台定稿，不改**。`{编码}-v{序号}-{短commit}` 四处共用（§23.5） | 强约束 |
| 7 | 平台模板只做简单单体，复杂交给项目自带文件 | **同意**，模板按「语言」分档（Java / Python），**不做 Node**（见 ADR-10） | 已定 |
| 8 | 要不要工厂模式？ | 主链路**保持 switch**；~~**Dockerfile 模板生成**用策略模式~~（平台模板 v6 已下线）。**compose 不用模板**（见 ADR-11，v7 已作废） | 推荐 |
| 9 | 要不要按运行时分表？ | **不分表**。拆分落在**契约层**与 **UI 层**，存储层保持三段 JSON | ✅ 已确认 |
| 10 | compose 是不是应该默认？平台生成难在哪？ | **应该有这个形态，但文件不该平台生成**（v7 修订）。难不在算法而在**建模成本**：用户每用一个 compose 特性，平台就要加一个配置项 | ✅ 已确认（v7 修订） |
| 11 | 生成的 Dockerfile / compose 要不要投放到目标机？ | **要**。与 §3.1 JAR 链路「固定工作目录 + 原地替换 + 平台挂了运维可手工」完全同构 | ✅ 已确认 |
| 12 | compose 覆盖策略：全量覆盖还是只改 image 行？ | ~~全量覆盖 + 强制备份~~ **v7 起不再适用**：文件由项目维护、每次发布随版本下发，平台不做「真相来源」也不必备份（§8.5 作废） | ✅ 已确认（v7 修订） |
| 13 | 前端静态：开放上传还是构建机装 Node？ | **开放 `UPLOAD` + `ARTIFACT_ONLY`** | ✅ 已确认 |
| 14 | compose 要不要按项目类型（Java/Python）生成不同模板？ | **不生成模板**（v7：文件由项目提供），本条与 ADR-11 一并作废 | ✅ 已确认（v7 修订） |
| 15 | 原 compose 里的 `build:` 块怎么处理？ | **不生成生效配置**，把 `context` / `dockerfile` 以**注释形式**保留在文件头 | ✅ 已确认 |
| 16 | STATIC 版本化与回滚形态？ | **方案 C：`static_root` 同级 `bak/` 备份 + 目录内容替换**。不动 nginx 配置、不用软链，与 JAR 的 `exec/bak` 完全同口径；**目录自身 inode 不变**（v6，兼容容器 bind mount） | ✅ v5 定稿 / v6 修正 |
| 17 | 静态版本化与上传是否同期做？ | **同期做**，一次交付完整静态部署能力 | ✅ 已确认 |

---

## 1. 现状盘点（代码事实）

### 1.1 已经有的（可直接复用）

| 能力 | 位置 | 现状 |
|---|---|---|
| DOCKER 构建主链路 | `BuildTaskHandler.buildDocker`（L663–711） | `resolveImageRepository` → 按模式选 Dockerfile → `dockerBuild` → `dockerPush` → `dockerDigest` → 落 `build_artifact` |
| 两种 Docker 构建模式 | 同上 | `PROJECT_DOCKERFILE` 用仓库内 Dockerfile；`LAYERED_DOCKER` 先构建推送 `base-{tag}` 再构建 app。**平台不再生成 Dockerfile 模板**（2026-09-28 下线） |
| 平台模板内容 | `DockerfileTemplateFactory` + `JavaDockerfileTemplate` / `PythonDockerfileTemplate`（已实现，2026-09-28） | 按语言分档：Java 走 Maven/Gradle 多阶段、Python 走单阶段 pip。原 `generatePlatformDockerfile`（硬编码 Maven）**已删除** |
| 镜像仓库登录/登出 | `dockerPush` / `cleanupRegistryCredential`（L1344–1418） | 密码走临时文件 + `--password-stdin`，`finally` 里必清 |
| DOCKER 发布 | `DeployTaskHandler.deployDocker`（L506–540） | `docker pull` → `docker rm -f` → `docker run -d --restart=always` → `docker inspect` 确认运行态 |
| STATIC 发布 | `DeployTaskHandler.deployStatic`（L1073–1119） | **已完整实现**：`prepareStaticPackage`（目录先 `tar -czf`）→ `downloadArtifact`（构建机→平台中转 SFTP）→ 目标机 `/tmp` → `tar -xzf -C {staticRoot}` → `nginx -t && nginx -s reload`。**但无备份、无版本概念** |
| 版本记录 | `release_record.image_tag` / `image_digest` / `artifact_path` / `sha256` / `build_server_id` | DOCKER 读 `image_tag`；JAR/STATIC 读 `artifact_path` + `build_server_id` 下载产物 |
| 产物类型 | `ArtifactType` | `BASE_IMAGE` / `APP_IMAGE` / `JAR` / `STATIC` |
| 仓库档案 | `registry` 表 | `registry_url` + `namespace` + `credential_id` + `status` |
| 项目连通性预检 | `ProjectConnectTestHandler` | 已做 `git ls-remote` + `docker login` + logout，**未做 push 权限与 namespace 探测** |
| 版本号规则 | `BuildService.resolveReleaseVersion` | `{编码}-v{序号}-{短commit}`；无提交时退化为时间戳（§23.5 已定义，覆盖上传场景） |
| 产物持久目录 | 构建机档案 `artifact_dir` | `{artifact_dir}/{项目编码}/{版本号}` + 目录下 `VERSION` |
| 来源类型 / 构建模式枚举 | `SourceType` / `BuildMode` | `UPLOAD` / `ARTIFACT_ONLY` **均已定义**，`RuntimeType.JAR/STATIC.supportedBuildModes` 已含 `ARTIFACT_ONLY` |
| **JAR 的可运维形态** | AGENTS.md §3.1 | 固定工作目录 + 原地替换 + systemd 只首次生成 + `exec/bak` 带版本号备份 + `VERSION` + 平台脚本投放 |

### 1.2 缺的（本次要补的）

| 缺口 | 影响 |
|---|---|
| **compose 完全缺失** | 容器配置无持久化、不可视化；`extra_docker_args` 会越写越长且不可读 |
| ~~平台模板只支持 Maven~~ ✅ 已解决（2026-09-28） | Python 模板已实现；Java 同时支持 Maven / Gradle 推导 |
| `dependency_hash` 恒空 | 分层构建每次重跑 base，AI 依赖体积大时慢 |
| 无「镜像已在仓库，只分发」的模式 | 外部 CI 已推好镜像的场景无法接入 |
| 仓库无写权限预检 | namespace 不存在 / 无 push 权限要到首次 `docker push` 才暴露 |
| 配置无 schema 校验 | `build_config_json` 只验 JSON 合法性，语义靠执行器运行时报错 |
| 发布无「旧容器优雅停止」 | 现在直接 `docker rm -f`，无 `docker stop` 宽限期 |
| **目标机没有可读的部署凭据** | 平台挂了以后，Docker 项目在服务器上留不下任何「怎么跑起来」的说明 |
| **`ARTIFACT_ONLY` / `UPLOAD` 执行未实现** | `BuildTaskHandler.execute`（L330–334、L457–461）直接抛「v1 暂未实现」 |
| **无 multipart 配置** | `src/main/resources` 下零配置，Spring Boot 默认单文件 1MB，大产物上传必失败 |
| **STATIC 无备份、无法回滚** | 覆盖解压有中间态、旧版本无从找回（ADR-12，v5 已定方案 C） |

---

## 2. 目标形态（方式二主链路）

```
┌─ 构建阶段（构建机，SSH）────────────────────────────────────┐
│  git clone（凭据注入 + 脱敏）                                │
│    → 定稿版本号 {编码}-v{序号}-{短commit}                    │
│    → 按 BuildMode 选 Dockerfile：                           │
│        （无此分支：Dockerfile 全部来自项目仓库）            │
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
│  固定工作目录 {deploy_base}/{项目编码}/                      │
│    docker-compose.yml   ← 平台渲染，image 写死具体 tag       │
│    Dockerfile           ← 构建文件副本（可应急）             │
│    VERSION              ← 版本标识，与 JAR 链路同格式         │
│    docker-compose.yml.bak_{时间戳}_{版本号}  ← 更新前备份     │
│                                                              │
│  动作：备份旧 compose → 投放新 compose → docker compose up -d │
│        → 健康检查 → 标记成功                                 │
│  回滚：渲染 old_version 的 tag → 同一条链路                   │
│  平台挂了：运维上机改 compose 里的 tag，up -d 即可            │
└─────────────────────────────────────────────────────────────┘
```

**为什么构建机不能省**（方式二）：

1. 目标机不该有源码，也不该有 git 凭据；
2. 一台构建机给 6 台目标机供镜像，目标机只 `pull`——这正是镜像仓库存在的意义；
3. `docker build` 上下文（node_modules、.git、target）不该出现在生产机上；
4. 目标机磁盘/网络策略往往更严格，构建失败也不该污染生产机。

**为什么平台模板模式下构建机不需要 maven**：`generatePlatformDockerfile` 用的是多阶段构建，`mvn package` 在 `maven:3.9-eclipse-temurin-21` 容器里跑，构建机只需要 `git` + `docker`。

> 代价：容器内构建无 `~/.m2` 缓存，每次全量下载依赖，首次可能 5–10 分钟。优化方向是构建机挂 `-v $HOME/.m2:/root/.m2`（需模板支持，属后续可选项）。

---

## 3. 架构决策记录（ADR）

### ADR-01：新增 `IMAGE_PULL` 构建模式，而非二选一

- **场景**：镜像已由外部 CI 推好，平台只想管分发与回滚。
- **决策**：`BuildMode` 新增 `IMAGE_PULL`（镜像直发）。不拉代码、不构建、不推镜像，直接按「仓库坐标 + 人工填写的 tag」生成 `release_record`。
- **理由**：它是方式一的合法落地形态，成本极低（不需要构建机、不需要 git 凭据）；与 `ARTIFACT_ONLY`（产物直发）对称。
- **代价**：版本号无 commit 短码，退化为 `{编码}-v{序号}-{时间戳}`（§23.5 已定义该退化）；`image_digest` 需 `docker manifest inspect` 补取，**允许为空**。
- **状态**：未实施（被 `JAR_PREBUILD` 取代）

> **实施结果（2026-10）**：该 ADR 提出的 `IMAGE_PULL` 未落地。实际新增的 DOCKER 构建模式是 `JAR_PREBUILD`——平台先用 Maven 容器打出 jar，再用项目提供的极简 Dockerfile 包成镜像推送（目标机只纯 run）。`IMAGE_PULL`（外部已推镜像直发）暂未实现，如需要应作为独立 ADR 重新评估。当前 `BuildMode` 枚举为 `PROJECT_DOCKERFILE` / `LAYERED_DOCKER` / `SCRIPT_DEPLOY` / `ARTIFACT_ONLY` / `JAR_PREBUILD`（权威定义见 AGENTS.md §3 / §23.4）。

### ADR-02：平台模板（Dockerfile）按语言分档

- **场景**：Java / Python（FastAPI）都要能用平台模板。
- **决策**：`BuildConfig` 新增 `language`（`JAVA` / `PYTHON`，选填，未填时按仓库特征探测：`pom.xml`/`build.gradle` → JAVA，`requirements.txt`/`pyproject.toml` → PYTHON）。模板由策略类按 language 生成。
- **理由**：两者构建命令、依赖安装、运行入口差异是结构性的，塞进一个模板会变成一堆 `if`。
- **代价**：模板要维护 N 份；探测可能误判，因此 `language` 必须**允许显式覆盖**，探测失败时报明确错误并列出支持的文件特征，不猜。
- **状态**：提议

### ADR-03：compose 是 DOCKER 的发布形态之一，文件由项目仓库提供、平台不渲染

> **⚠️ v7 修订**：本文原先判「v1 原判断作废」（即改由平台渲染），**该结论已于 2026-09-29 撤回，
> v1 判断恢复有效** —— compose 由仓库自带、平台只注入 `IMAGE_TAG`。详见文首 v7 修订。
> 下方第 2、3 条（平台渲染、image 写死具体 tag）**已作废**，保留原文仅为记录决策演进：

- **场景**：AIops 这类项目的 compose 有 `container_name` / `restart` / `extra_hosts` / `env_file` / 20+ 个 `environment` / `ports` / `networks(external)` / `volumes`；`docker run` 要表达同样的东西得写一长串 `-e -v -p --network --add-host`，**既不可持久化也不可视化**。
- **决策**：
  1. `runtime_config_json.deploy_form` 取值 `COMPOSE`（**默认**）/ `RUN`（兼容保留现有实现）；
  2. ~~compose 文件由**平台渲染**（Freemarker 模板，与 Dockerfile 模板同机制），**不是从仓库拷贝**~~；
  3. ~~渲染结果**投放到目标机固定工作目录**，文件里 `image` 写**具体 tag**（不是 `${IMAGE_TAG}` 占位符）~~；
  4. `build:` 块不生成生效配置（见 §8.8）。

> **v7 补记（当前实现的口径）**
>
> - **文件来源**：项目仓库。`docker-compose.yml` 由项目提供，Dockerfile 同理（见 v6）。
>   平台不渲染任何一个，也不做任何一处文案改写；
> - **版本注入**：compose 里写成 `image: 仓库地址/镜像名:${IMAGE_TAG}`，
>   平台额外生成一行变量文件注入本次版本号，由 docker compose 自己插值。
>   **缺了这个占位符就在构建期报错**——它能让容器永远拉同一个 tag，而发布流程全程报成功；
> - **选型**：`runtime_config_json.deploy_form` = `RUN`（默认，存量项目行为不变）/ `COMPOSE`；
> - **快照位置**：构建成功时把文件原文存进发布版本记录（`release_record.compose_content`）。
>   它是几 KB 的编排描述而非可分发制品，落构建机产物目录只会多一个需要回收的目录，
>   存库则能随版本精确回溯、回滚时省掉一次文件传输；
> - **不再存在的成本**：无需为 environment / volumes / networks 建配置项，也无需备份与覆盖策略——
>   文件由项目维护，「覆盖了运维的手工改动」这个问题从根上消失了。
- **理由**：compose 的**格式与 key 是固定的**（`services.<name>` 下的 `image` / `container_name` / `restart` / `environment` / `env_file` / `ports` / `volumes` / `networks` / `extra_hosts`），**变化的只有值**。骨架几乎固定、变的只是可选段，渲染是模板填充，没有算法复杂度。
- **代价**：
  1. 参数面变大（环境变量、卷、网络都是列表），但按 §5 结论这些进 JSON，不需要 DDL；
  2. 平台渲染的文件会覆盖运维的手工改动——已确认采用全量覆盖 + 强制备份（§8.5）；
  3. `restart` 默认值从 `--restart=always` 变成 `unless-stopped`，是行为变更，需明确告知。
- **状态**：✅ 已确认

### ADR-04：镜像 tag 继续由平台定稿，人工只指定序号

- **决策**：**不改**。`{编码}-v{序号}-{短commit}`，人工只能通过 `build.version_seq` 指定序号。
- **理由**：该字符串四处共用（§23.5），一旦允许人工自由填写 tag，镜像 tag 与 `release_record.version` / 产物目录 / VERSION 文件就会脱节，回滚时按版本找不到镜像。
- **代价**：无法复用外部 CI 已打的 tag。这个场景走 ADR-01 的 `IMAGE_PULL` 解决，而不是放宽 tag 规则。
- **状态**：已接受（沿用 §23.5）

### ADR-05：仓库预检只查「连通 + 登录 + namespace 可写」，不查镜像存在性

- **决策**：
  - **不查镜像存在性**——tag 含项目内递增序号，天然不会撞；同一 commit 重复构建也因序号递增而互不覆盖。
  - **要查可写性**——`PROJECT_TEST` 里补一步（可选开关）：login 成功后 build 一个极小镜像 push 到 `{namespace}/_ld_probe:{时间戳}`，成功后删。
- **理由**：namespace 不存在 / 无 push 权限是当前唯一的「首次 push 才炸」类问题。
- **代价**：预检多 10–30 秒；需要清理失败兜底（删不掉只告警）。**必须做成可选**（`registry.probe_enabled`，默认关）。
- **状态**：提议

### ADR-06：分层构建 v1 不做依赖指纹复用

- **场景**：AIops 的 `Dockerfile.base` 每次都重跑。
- **决策**：**v1 保持每次全量构建 base**（现状 `base-{tag}`）。`dependency_hash` 继续预留。
- **理由**：复用 base 意味着 app 镜像 `FROM` 的 base tag 变了，而 base tag 当前绑定版本号——解耦就要引入 `dep-{指纹}` 第二套命名，并把 base 生命周期从「本次构建」变成「跨构建共享」，会牵动 §23.6 的保留策略（删一个版本时不能删掉别人引用的 base）。
- **构建顺序确认**：**先跑 base，成功后再跑 app**——`buildDocker`（L683–694）现状正是如此：先 `dockerBuild(base)` + `dockerPush(base)` + 落 `BASE_IMAGE` 产物，再构建 app。base 失败直接抛异常终止整次构建。**这条现状已符合预期，无需改造。**
- **代价**：构建慢。v1.5 缓解方案：引入 `dependency_files` 指纹，未变则复用最近一条 `BASE_IMAGE` 并在 `build_artifact` 记引用关系，回收时跳过被引用的 base。
- **状态**：提议

### ADR-07：配置存储层不分表，拆分落在契约层与 UI 层

- 详见 §5。**已确认。**

### ADR-08：Dockerfile 模板生成用策略模式，主链路保持 switch

- 详见 §6。
- **状态**：提议

### ADR-09：构建与编排文件必须投放到目标机，保证「平台不可用」时仍可运维

- **场景**：「平台不能用了，运维还可以上服务器进行更新维护」。
- **决策**：DOCKER 运行时在目标机建立**固定工作目录**（与 §3.1 JAR 链路同构）：

  | 文件 | 用途 | 谁写 |
  |---|---|---|
  | `docker-compose.yml` | 当前生效的编排，**`image` 写死具体 tag** | 平台每次发布渲染投放 |
  | `Dockerfile`（+ `Dockerfile.base`） | 构建文件副本，应急可手工 build | 平台投放 |
  | `VERSION` | 版本标识，与 JAR 链路同格式（`version=` / `commit=` / `runtime=` / `image=`） | 平台发布后写 |
  | `docker-compose.yml.bak_{时间戳}_{版本号}` | 更新前的备份 | 平台发布前备份 |

- **理由**：与 §3.1 已定稿的 JAR 形态**完全同构**。DOCKER 侧甚至更省事：compose 文件本身就是运维的手工入口，改 tag 后 `docker compose -p {编码} up -d` 即可，不需要额外脚本。
- **状态**：✅ 已确认

### ADR-10：开放 `UPLOAD` 来源 + `ARTIFACT_ONLY`，前端本地构建后上传静态产物

- **决策**：**开放**。前端项目本地 `npm run build` 出 `dist`，打包上传，平台转存到构建机产物目录并登记版本，之后复用现有 STATIC 发布链路。
- **为什么构建机仍要参与**（不能只存平台本地）：`downloadArtifact`（L1137–1142）**强制要求 `release.build_server_id`**，缺失就报「未记录构建机（多为历史版本）」。产物统一落在构建机 `{artifact_dir}/{项目编码}/{版本号}/` 才能与 JAR/SCRIPT_DEPLOY 共享同一套分发与保留策略。
- **实现路径**（不写新链路，只补入口）：

  ```text
  1. POST /build/upload  multipart：projectId + file（.tar.gz/.tgz/.zip）
       → 平台存本地临时文件 → 立即返回 buildId（同步阶段只做收文件）
  2. 提交 BUILD 任务（异步，复用现有任务框架）
       → 定稿版本号 {编码}-v{序号}-{时间戳}      ← §23.5 已定义该退化
       → SFTP 上传到构建机 {artifact_dir}/{编码}/{版本号}/
       → 构建机 sha256sum → 落 build_artifact(STATIC)
       → 写 VERSION 文件
       → build 直接置成功（2）+ 生成 release_record
  3. 发布：复用 deployStatic 的分发段，落地形态改为 ADR-12 方案 C
  ```

- **必须处理的配置**：Spring Boot multipart 默认单文件 1MB，**必须在 `application.yml` 放开**（建议 `max-file-size: 500MB`、`max-request-size: 500MB`）。当前 `src/main/resources` 下**没有任何 multipart 配置**，这是硬缺口。
- **是否建 `build` 记录**：**建**。让 build 秒级置成功，版本列表、任务日志、产物列表全部复用，前端零改动；代价是 build 表里出现「没构建过的构建」。可接受，并在 `build` 上标注来源便于区分。
- **代价**：
  1. 版本号无 commit，无法从版本号反查代码——靠上传时**可选填写备注/来源说明**弥补；
  2. 产物大小不可控，构建机磁盘要纳入监控；
  3. 上传没有内容校验（解压出来是不是 index.html）→ 建议上传后做一次轻量校验（`tar -tzf | grep -c index.html`），失败即告警不阻断。
- **状态**：✅ 已确认

### ADR-11：compose 用「单模板 + 可选段 + 场景预设」，不按语言生成多套模板

- **场景**：Java 项目配置读 `application.yml`、几乎不需要 `environment`；Python（FastAPI）有一大堆 `environment`。是否按类型生成不同模板？
- **决策**：**不做多套 compose 模板**。只维护一份 `compose.yml.ftl`，差异通过两个机制承担：

  1. **可选段**：`environment` / `env_file` / `volumes` / `extra_hosts` / `healthcheck` / `networks` 都是**列表参数**，列表为空则整个 YAML 段不渲染。Java 项目不填 environment → 生成的 compose 里就没有这一段。
  2. **场景预设（`compose_preset`）**：不是模板，只是**表单默认值填充器**。

     | 预设 | 自动填充 | 面向 |
     |---|---|---|
     | `GENERIC`（默认） | 仅 `image` + `container_name` + `restart` | 通用 |
     | `JAVA_JAR` | `volumes: ["./config:/app/config"]`、`environment: [TZ=Asia/Shanghai, SPRING_PROFILES_ACTIVE=prod, JAVA_OPTS=...]` | Spring Boot 容器 |
     | `PYTHON_APP` | `env_file: [".env"]`、`volumes: ["./logs:/app/logs"]`、提示用 `environment` 注入非密配置 | FastAPI / Django |

- **理由**：
  1. **真正的差异维度不是「语言」，是「配置注入方式」**。同样 Java 也可能大量使用 `SPRING_REDIS_HOST` 这类环境变量（Spring Boot 宽松绑定很常见）；同样 Python 也可能整包配置挂卷。
  2. 结构完全一致。骨架（`services.<name>` → `image` / `container_name` / `restart`）任何语言都一样，为它维护 N 份是纯负债。
  3. 避免组合爆炸：Dockerfile 模板已按语言有 N 套（ADR-02），compose 再来 M 套，模板工厂要管 N×M 组合。
- **Java「配置读 application.yml」的正确落地**（与 §3.1 口径一致）：**挂载 config 目录**，不要往 environment 里塞配置。

  ```yaml
  volumes:
    - ./config:/app/config        # 平台渲染时转绝对路径
  environment:
    TZ: Asia/Shanghai
    SPRING_PROFILES_ACTIVE: prod
  ```

- **代价**：预设只是建议值，用户仍可能填出不妥的 environment → 靠保存时的**软提示**（敏感 key 提示改用 env_file，见 §8.4），不强制。
- **状态**：✅ 已确认

### ADR-12（v5 定稿 / v6 修正）：STATIC 用「同级 `bak` 备份 + 目录内容替换」（方案 C）

> **v4 的软链方案已撤回**，理由见 §9.4.0。

- **决策**：`static_root` 语义不变（就是 nginx 已配好的那个静态目录），**不引入软链、不改 nginx 配置**。发布与回滚都在目标机上做「备份 → 目录内容替换」（v6：只搬目录内的条目，目录自身 inode 不变）：

  ```text
  /usr/share/nginx/html/                 ← nginx 已配好，平台不动它
  ├── aiops/                             ← static_root，当前生效版本
  └── .static-bak/                       ← 同级备份目录（可配，建议放 web 根之外）
      ├── aiops_bak_20260927193000_aiops-v12-3f9a1c2/
      └── aiops_bak_20260926120000_aiops-v11-8b21e04/
  ```

- **备份命名**：`{目录名}_bak_{yyyyMMddHHmmss}_{版本号}`，与 §3.1 JAR 的 `{jar名}_bak_{时间戳}_{版本号}.jar` **完全同构**。
- **回滚**：按 `deployment_target.old_version` 在 `bak/` 里**按版本号精确匹配**（不是「找最近一个」），复制回临时目录后整体替换。
- **核心收益保留**：备份在目标机本地，**回滚不需要回构建机下载产物**——这正是 v4 软链方案想达到的效果，方案 C 用更低的复杂度拿到了。
- **状态**：✅ v5 定稿

---

## 4. 运行时 × 构建模式 × 发布形态（目标矩阵）

| 运行时 | 构建模式 | 产物 | 走仓库 | 发布形态 | 构建机 |
|---|---|---|---|---|---|
| DOCKER | `PROJECT_DOCKERFILE` | APP_IMAGE | 是 | **COMPOSE**（默认）/ RUN | 需要（git+docker） |
| DOCKER | `PROJECT_DOCKERFILE` | APP_IMAGE | 是 | **COMPOSE**（默认）/ RUN | 需要（git+docker） |
| DOCKER | `LAYERED_DOCKER` | BASE_IMAGE + APP_IMAGE | 是 | **COMPOSE**（默认）/ RUN | 需要（git+docker） |
| DOCKER | `JAR_PREBUILD`（2026-10 新增） | APP_IMAGE | 是 | **COMPOSE**（默认）/ RUN | 需要（git+maven 容器打 jar，再 docker build） |
| DOCKER | `IMAGE_PULL`（评估提议，**未实施**，被 `JAR_PREBUILD` 取代） | 无（外部已推） | 是（只读） | **COMPOSE**（默认）/ RUN | **不需要** |
| JAR | `SCRIPT_DEPLOY` | JAR | 否 | systemd | 需要（git+maven） |
| JAR | `ARTIFACT_ONLY` | JAR（已有） | 否 | systemd | 需要（暂存产物） |
| STATIC | `SCRIPT_DEPLOY` | STATIC | 否 | **Nginx + 同级 bak 备份** | 需要（git+node） |
| STATIC | `ARTIFACT_ONLY` + `UPLOAD` | STATIC（上传） | 否 | **Nginx + 同级 bak 备份** | 需要（暂存产物） |

> 组合校验的唯一来源仍是 `RuntimeType.supportedBuildModes`（AGENTS.md §23.4）。当前实际枚举为 `PROJECT_DOCKERFILE` / `LAYERED_DOCKER` / `SCRIPT_DEPLOY` / `ARTIFACT_ONLY` / `JAR_PREBUILD`；新增构建模式时**必须同时改两处**：`RuntimeType` 枚举 + 前端 `BUILD_MODE_OPTIONS`。
> `JAR_PREBUILD`（2026-10 新增，平台打 jar + 项目极简 Dockerfile 包镜像）已落入 `RuntimeType.DOCKER.supportedBuildModes`；`IMAGE_PULL` 仅为评估提议、未实施。

---

## 5. 分表评估（已确认：不分表）

### 5.1 参数清点

当前异构参数全在三段 JSON 里（`BuildConfig` 10 + `DeployConfig` 11 + 健康检查 6 = 27 个键，前端 `projectConfig.ts` 510 行）：

| 归属 | 字段数 | 字段 |
|---|---|---|
| 构建公共 | 5 | `module_name` `module_dir` `pom_path` `build_command` `command_timeout_seconds` |
| DOCKER 构建独占 | 4（拟 5） | `dockerfile_path` `base_dockerfile_path` `image_repository` `language` |
| JAR/STATIC 构建独占 | 2 | `artifact_path` `config_paths` |
| **DOCKER 运行独占（扩容后）** | **3 → 11** | `container_name` `port_mappings` `extra_docker_args` + `deploy_form` `compose_preset` `environment` `env_files` `volumes` `networks` `extra_hosts` `restart_policy` |
| JAR 运行独占 | 7 | `work_dir` `service_name` `jvm_options` `java_bin` `spring_profiles_active` `config_dir` `config_file` |
| STATIC 运行独占 | 2 | `static_root` `static_backup_dir` |
| 公共运行 | 1 | `deploy_base` |

> DOCKER 独占项约 **12–14 个**。仍远低于 §5.4 的触发阈值，且新增的都是**列表型**，用 JSON 数组表达比用列表达更自然。

### 5.2 决定性论据：DDL 成本

AGENTS.md 已明确：**MySQL 结构升级必须手工执行，部署链路只换包重启无 DDL 能力**。

- 分表：DOCKER 参数每演进一次都要用户手工跑 SQL，而这个平台正处 Docker 能力快速演进期；
- 不分表：加参数只改 Java 配置类 + 前端常量 + 文档，**换包重启即可**。

### 5.3 落地方式

1. **存储层不动**：`project` 主表三段 JSON 保留（§23.2 契约不变），加 `schema_version`（默认 1）。
2. **契约层拆**：
   - `BuildConfig` 按运行时拆为 `DockerBuildConfig` / `JarOrStaticBuildConfig`；
   - `DeployConfig` 拆为 `DockerDeployConfig`（含 compose 列表型参数 + `compose_preset`）/ `JarDeployConfig` / `StaticDeployConfig`；
   - 新增 `RuntimeConfigResolver`：按 `runtime_type` 解析对应子类、**校验合法性、填充默认值、做字段冲突检查**（如 `module_dir` 与 `-pl` 同现，现在靠前端拦，应下沉到后端）。
3. **UI 层必须拆**：项目编辑页 4 个页签——`基础信息` / `构建配置`（按 BuildMode 变） / `运行配置`（按 runtime_type 变；compose 参数以「键值对 + 增删行」编辑，预设下拉一键填充） / `健康检查`。

### 5.4 重新分表的触发条件

满足**任一**才重新评估，且**只拆 `project_docker_config` 一张**，不做对称三张：

1. 单一运行时独占配置项 **> 20 个**，或出现「同一键在不同运行时含义不同」的冲突；
2. 出现**按运行时参数检索/统计**的需求（如「找出所有用了 external 网络的项目」）；
3. 该运行时配置需要**独立的历史版本或审计**；
4. Docker 参数进入**稳定期**（连续两个迭代无新增字段）。

> **观察项**：`project` 主表上 `build_mode` / `registry_id` 两列只对 DOCKER 有意义（§23.4）。它们是分表需求的早期信号，但当前还承担「组合校验入口」职责，暂不动。

### 5.5 若坚持分表：DDL 草案（**未执行，仅作对照**）

```sql
CREATE TABLE IF NOT EXISTS project_docker_config (
    id VARCHAR(64) NOT NULL COMMENT '主键',
    project_id VARCHAR(64) NOT NULL COMMENT '项目ID，唯一，指向 project 表',
    build_mode VARCHAR(30) NOT NULL COMMENT '构建模式：PROJECT_DOCKERFILE/LAYERED_DOCKER/IMAGE_PULL',
    registry_id VARCHAR(64) NULL COMMENT '镜像仓库ID，指向 registry 表',
    image_repository VARCHAR(255) NULL COMMENT '镜像仓库坐标（不含tag），留空由 registry 推导',
    dockerfile_path VARCHAR(255) NULL COMMENT '仓库内 Dockerfile 相对路径',
    base_dockerfile_path VARCHAR(255) NULL COMMENT '分层构建基础层 Dockerfile 路径',
    deploy_form VARCHAR(20) NOT NULL DEFAULT 'COMPOSE' COMMENT '发布形态：COMPOSE/RUN',
    compose_preset VARCHAR(20) NULL COMMENT '编排预设：GENERIC/JAVA_JAR/PYTHON_APP，仅表单默认值',
    container_name VARCHAR(100) NULL COMMENT '容器名，默认项目编码',
    restart_policy VARCHAR(30) NULL COMMENT '重启策略：no/always/unless-stopped/on-failure，默认unless-stopped',
    port_mappings VARCHAR(255) NULL COMMENT '端口映射，多个逗号分隔',
    extra_docker_args VARCHAR(500) NULL COMMENT '附加 docker run 参数，仅 RUN 形态使用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_docker_cfg_project (project_id, is_deleted),
    KEY idx_docker_cfg_registry (registry_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目Docker构建运行配置';
```

> 注意：**列表型参数（environment / volumes / networks / extra_hosts / env_files）不在上表**——用列表达列表是倒退。这本身就是「列式存储在 DOCKER 场景并不划算」的证据。

---

## 6. 工厂 / 策略模式评估

### 6.1 不要抽象的地方

| 位置 | 现状 | 建议 |
|---|---|---|
| `DeployTaskHandler` 的 `switch (runtimeType)`（L459–461） | 3 个分支 | **保持 switch** |
| `BuildTaskHandler` 的 `if (runtimeType == DOCKER)` | 2 个分支 | **保持 if/else** |
| `BuildMode` 模式分支 | 3–4 个分支 | **保持** |
| **compose 渲染** | 无 | **单个 `ComposeRenderer` 类 + 一份 ftl**，不做策略工厂（ADR-11） |

**判断标准**：分支数 ≤ 4 且各分支无公共可复用步骤时，`switch` 比工厂更可读。为「以后支持 Go/Rust」提前建工厂是 §18 明令禁止的。

### 6.2 必须抽象的地方：Dockerfile 模板生成

只有 Dockerfile 因语言差异是**结构性**的，用策略模式：

```java
// common/enums/project/LanguageType.java —— 新增
public enum LanguageType {
    JAVA("JAVA", "Java", Set.of("pom.xml", "build.gradle", "build.gradle.kts")),
    PYTHON("PYTHON", "Python", Set.of("requirements.txt", "pyproject.toml", "Pipfile"));
    // probe(markerFiles)：按仓库根文件特征探测，多命中时按声明顺序取第一个
}

// build/template/DockerfileTemplate.java —— 新增
public interface DockerfileTemplate {
    LanguageType language();
    String render(Project project, BuildConfig config);   // 返回 Dockerfile 全文
}

// build/template/DockerfileTemplateFactory.java —— 新增
@Component
public class DockerfileTemplateFactory {
    private final Map<LanguageType, DockerfileTemplate> templates;
    // 构造器注入 List<DockerfileTemplate>，按 language() 建 Map
    public DockerfileTemplate resolve(Project project, BuildConfig config) { /* 显式 → 探测 → 报错 */ }
}

// deployment/template/ComposeRenderer.java —— 新增（无接口、无工厂）
// 输入 DockerDeployConfig（含 environment/volumes/networks/extra_hosts/env_files 列表）+ 镜像全名
// 输出 docker-compose.yml 全文；语言差异由「列表为空即不渲染」吸收，不建第二套
```

**模板载体**：`resources/templates/docker/Dockerfile.java.ftl`、`Dockerfile.python.ftl`、`compose.yml.ftl`。项目已有 Freemarker（代码生成器在用），**需核实**依赖是否在主作用域可用。

---

## 7. 镜像仓库与版本号

### 7.1 tag 生成（沿用，不改）

```
镜像坐标 = build_config.image_repository
        ?: registry.registry_url + "/" + registry.namespace + "/" + (module_name ?: project.code)
镜像 tag  = {项目编码}-v{序号}-{短commit}          ← 与 release_record.version 同一个字符串
base tag  = base-{项目编码}-v{序号}-{短commit}      ← LAYERED_DOCKER 专用
上传产物版本号 = {项目编码}-v{序号}-{yyyyMMddHHmmss}  ← 无 commit 时的退化（§23.5 已定义）
```

### 7.2 预检设计（ADR-05）

| 检查项 | 时机 | 手段 | 失败处理 |
|---|---|---|---|
| 仓库地址可达 + 凭据可用 | `PROJECT_TEST`（已有） | `docker login` → logout | 直接失败 |
| namespace 存在 + 有 push 权限 | `PROJECT_TEST`（新增，默认关） | build 极小镜像 push 到 `{ns}/_ld_probe:{ts}`，成功后删 | 提示「无写权限或 namespace 不存在」；删除失败只告警 |
| registry 绑定存在 | 创建构建时（已有） | `BuildService.createAndTrigger` | 已有 |
| 镜像是否已存在 | **不做** | — | tag 含递增序号，天然唯一 |

### 7.3 分层构建的 base 镜像（ADR-06）

| 项 | 现状 | v1.5 建议 |
|---|---|---|
| 构建顺序 | 先 base 后 app，base 失败即终止 | **保持不变**（已符合预期） |
| base tag | `base-{版本号}` | 保留；若做指纹复用则改 `dep-{指纹8位}` |
| 复用判断 | 无 | `dependency_files` 内容 SHA256 → 查最近同指纹 `BASE_IMAGE` |
| 回收保护 | 按 §23.6 保护窗口 | **新增**：base 被其它版本引用时不得回收 |
| 日志 | 无 | 复用时记「基础镜像命中缓存：{baseTag}，跳过构建」 |

---

## 8. compose 设计

> **⚠️ 本节描述的是「平台渲染 compose」方案，已于 v7（2026-09-29）作废。**
> 当前实现：**compose 文件由项目仓库提供**，构建期快照原文、发布期原样下发，
> 版本号经 `${IMAGE_TAG}` 变量注入。§8.5 的覆盖与备份策略随之不再适用。
> 下方原文保留仅用于追溯决策演进。

### 8.1 为什么之前判错了

| v1 判断 | 错在哪 |
|---|---|
| 「平台生成 compose 最复杂」 | 把**覆盖面**（要支持多少种写法）当成了**算法复杂度**。格式与 key 固定、变的只是值 → 渲染是模板填充，没有难度 |
| 「文件应来自项目仓库」 | 从仓库拷贝带出两个新问题：① 回滚时用哪个版本的 compose？② 镜像 tag 得靠 `${IMAGE_TAG}` 占位符注入，写死就回滚失效。平台渲染直接写死 tag 反而干净 |
| 「v1 先 run 后 compose」 | 忽略了「平台不可用」场景。文件在目标机上，运维才能手工维护——这正是 §3.1 JAR 链路已定稿的形态 |

### 8.2 AIops compose 逐块 → 平台参数映射

| compose 块 | AIops 实例 | 平台参数（JSON） | 说明 |
|---|---|---|---|
| `services.<name>` | `backend` | `service_name`（默认项目编码） | v1 只支持**单服务** |
| `build.context` / `build.dockerfile` | `.` / `Dockerfile.app` | **不生成生效配置**（见 §8.8） | 平台场景镜像已在仓库，渲染为 `image:` |
| `image` | — | `{repo}:{tag}` | **写死具体 tag**，不用占位符 |
| `container_name` | `aiops-backend` | `container_name` | 默认项目编码 |
| `restart` | `unless-stopped` | `restart_policy` | 默认 `unless-stopped`（见 §8.6） |
| `extra_hosts` | `host.docker.internal:host-gateway` | `extra_hosts: ["host.docker.internal:host-gateway"]` | 列表，空则不渲染 |
| `env_file` | `.env` | `env_files: [".env"]` | **只存路径，不存内容**（见 §8.4） |
| `environment` | 20+ 个 `${VAR:-default}` | `environment: [{key, value}]` | 列表，空则不渲染（Java 项目通常就是空） |
| `ports` | `2029:2029` | `ports: ["2029:2029"]` | 列表 |
| `volumes` | `./logs:/app/logs`、`/etc/localtime:/etc/localtime:ro` | `volumes: [...]` | 列表；**相对路径必须转绝对**（见 §8.7） |
| `networks`（service 级） | `lx-app-net` | `networks: [{name, external}]` | **必须支持 `external: true`** |
| `networks`（顶层） | `lx-app-net: {external: true, name: lx-app-net}` | 同上，平台渲染顶层块 | 见 §8.7 |

### 8.3 目标机目录与发布动作

```
{deploy_base}/{项目编码}/          ← deploy_base 沿用现有推导（服务器 agent_base_dir / apps）
├── docker-compose.yml             ← 平台渲染，image 写死 tag
├── Dockerfile                     ← 构建文件副本（应急可手工 build）
├── Dockerfile.base                ← LAYERED_DOCKER 时有
├── VERSION                        ← version=/commit=/runtime=/image=，与 JAR 同格式
└── docker-compose.yml.bak_{时间戳}_{版本号}
```

**发版动作**（COMPOSE 形态）：

```text
1. 备份当前 docker-compose.yml → .bak_{时间戳}_{版本号}
2. 渲染新 compose（image = 本次 tag）→ SFTP 投放覆盖
3. docker compose -p {项目编码} up -d --remove-orphans
4. docker compose -p {项目编码} ps 确认服务 running
5. 写 VERSION 文件
6. 健康检查（复用现有 HealthChecker）
```

**回滚**：渲染 `old_version` 的 tag → 同 1–5。**compose 形态的回滚天然幂等**。

**平台不可用时的手工路径**：

```bash
cd {deploy_base}/{项目编码}/
vi docker-compose.yml        # 把 image 的 tag 改成要回退的版本
docker compose -p {项目编码} up -d
```

不需要任何平台脚本——compose 文件本身就是运维入口。这比 JAR 链路（需要 `jar-fallback.py`）还简单。

### 8.4 敏感信息处理

AIops 的 compose 里有 `DB_PASSWORD: ${DB_PASSWORD:-sasa}`——**明文默认值写在仓库里**是既成事实，但**平台不能把它变成自己的责任**：

| 做法 | 结论 |
|---|---|
| 平台把密码存进 `environment` 参数 | ❌ 禁止。配置 JSON 会进数据库、可能进日志与响应 |
| 平台只记 `env_files` 路径，密文由运维放在目标机的 `.env` | ✅ 推荐 |
| 平台保存时检测 `environment` 值疑似密文（key 含 `PASSWORD`/`SECRET`/`KEY`/`TOKEN`） | ✅ 提示「建议改用 env_file」，**不强制拦截** |
| 任务日志回显 compose 内容 | ⚠️ 渲染后的 compose 含环境变量值，**落日志前必须脱敏**（复用 `SecretMasker`） |

### 8.5 覆盖策略（已确认：全量覆盖 + 强制备份）

| 方案 | 说明 | 结论 |
|---|---|---|
| **A. 每次发布全量覆盖 + 强制备份** | 平台是唯一真相来源；手工改动请回到平台配置 | ✅ **已确认采用** |
| B. 首次生成，后续只改 `image` 行 | 尊重手工改动，但平台侧参数变更（加端口）不生效 | 不采用 |
| C. 覆盖 + 保留手工标记区 | 文件内用注释分隔平台区与手工区 | 复杂度偏高，不采用 |

**与 §3.1「systemd 只首次生成」的口径不一致——这是有意为之**：

- systemd 单元是**命令式启动脚本**（`ExecStart` 一行命令），运维加 `-Xmx`、改 `--spring.config.location` 都是对同一行的微调，平台重新生成必然丢失；
- compose 文件是**声明式期望状态**，平台渲染的就是「这个服务应该长什么样」，运维改动同样应表达为期望状态并回到平台配置，否则平台与服务器会各自演化出一份真相；
- 一致性兜底是**备份**：`docker-compose.yml.bak_{时间戳}_{版本号}` 保证任何一次覆盖都能回到上一状态。

> 落地要求：① 生成的 compose 文件头必须写明「本文件由平台渲染，请勿手工修改，改动请到平台项目配置」；② 备份命名与 JAR 的 `exec/bak` 同构（时间戳 + 版本号）；③ 模块文档与页面帮助文案明确这条差异。

### 8.6 `restart` 默认值变更（行为变更，需知情）

| 形态 | 现状 | compose 默认 |
|---|---|---|
| `docker run` | `--restart=always` | — |
| compose | — | `unless-stopped` |

差异：`always` 在运维手工 `docker stop` 后仍可能把容器拉起来；`unless-stopped` 尊重手工 stop。生产环境 `unless-stopped` 更符合直觉。**这是行为变更**，发布到已有项目时会改变重启语义。处理：`restart_policy` 显式可配，默认 `unless-stopped`，变更时给一次提示。

### 8.7 三个必须处理的坑

| # | 坑 | 处理 |
|---|---|---|
| 1 | **external 网络** | AIops 用已存在的 `lx-app-net`。渲染必须支持 `external: true`，否则 compose 会尝试创建同名网络并因冲突失败 |
| 2 | **`docker compose` vs `docker-compose`** | v2 插件语法 vs v1 独立命令。执行前 `docker compose version` 探测，失败回落 `docker-compose`，**实际使用的命令写进日志** |
| 3 | **相对路径卷** | `./logs:/app/logs` 相对 compose 文件所在目录解析。渲染时**必须转成绝对路径**；平台已知工作目录，直接拼绝对前缀 |

补充：compose 命令固定带 `-p {项目编码}`，保证多目标机上的栈名一致，避免因目录名不同导致 compose 找不到已有栈而新建。

### 8.8 原 compose 的 `build:` 块：不生效，但以注释保留

原文件里的 `build: {context: ., dockerfile: Dockerfile.app}` **平台渲染时不生成生效配置**：

1. 目标机工作目录**没有源码**，`context: .` 指向空目录，`docker compose up` 会构建失败；
2. 即使构建成功也是绕过平台版本控制的「目标机本地构建」，版本号与镜像仓库脱节，回滚链路失效；
3. 平台链路的镜像已在构建机构建并推送，目标机只需 `pull`。

**但把构建信息以注释形式保留在文件头**，供应急手工重建：

```yaml
# ============================================================
# 本文件由 Light Deploy 渲染，请勿手工修改（下次发布会被覆盖）
# 项目：aiops        版本：aiops-v12-3f9a1c2
# 镜像：registry.cn-hangzhou.aliyuncs.com/lx/aiops-app:aiops-v12-3f9a1c2
# 构建上下文：.      构建文件：Dockerfile.app
# 应急重建：docker build -f Dockerfile.app -t {镜像}:{版本} .
#           然后改本文件 image 行的 tag，docker compose -p aiops up -d
# ============================================================
services:
  backend:
    image: registry.cn-hangzhou.aliyuncs.com/lx/aiops-app:aiops-v12-3f9a1c2
    container_name: aiops-backend
    restart: unless-stopped
    ...
```

---

## 9. 静态产物上传与版本化（ADR-10 + ADR-12 落地）

### 9.1 整体链路

```
[开发者本地] npm run build && tar -czf aiops-web.tar.gz -C dist .
      ↓ POST /build/upload (multipart)
[平台]  落本地临时目录（立即返回 buildId，避免大文件同步超时）
      ↓ 提交 BUILD 任务（异步）
      ↓ SFTP
[构建机] {artifact_dir}/{项目编码}/{版本号}/
      ↓ 生成 release_record（artifact_path + build_server_id + sha256）
      ↓ 复用 deployStatic 的分发段
[目标机] 解压到临时目录 → 备份当前目录到 bak/ → 整体替换 → nginx reload
```

### 9.2 必须改的配置

| 项 | 现状 | 需要 |
|---|---|---|
| `spring.servlet.multipart.max-file-size` | 未配置（默认 1MB） | **必须放开**（建议 500MB） |
| `spring.servlet.multipart.max-request-size` | 未配置（默认 10MB） | 同上 |
| 上传文件类型白名单 | 无 | `.tar.gz` / `.tgz` / `.zip`（`isArchive` 已支持 tar 系列，**zip 需确认解压命令**） |
| 平台本地临时目录清理 | — | 转存成功后必删（`finally`，不得抛异常，见 AGENTS.md §7） |
| `static_root` | Nginx 静态目录（默认 `/usr/share/nginx/html/{项目编码}`） | **语义不变**，填 nginx 已配好的路径即可 |
| `static_backup_dir`（新增） | 无 | 备份目录，默认 `{static_root 的父目录}/.static-bak`；**建议配到 web 根之外**（见 §9.4.5） |

### 9.3 与 Git 构建的差异（UI 上要体现）

| 维度 | GIT + SCRIPT_DEPLOY | UPLOAD + ARTIFACT_ONLY |
|---|---|---|
| 项目必填 | `git_url` / `branch` / 凭据 | 均不必填 |
| 构建耗时 | 分钟级 | 秒级（只转存 + 算 SHA256） |
| 版本号 | `-v{序号}-{短commit}` | `-v{序号}-{时间戳}` |
| 可追溯性 | 版本号 → commit → 代码 | 版本号 → 上传记录（**建议上传时填备注**） |
| 构建机要求 | git + node | 无（只需 SFTP + `sha256sum`） |

### 9.4 方案 C 落地：同级 `bak` 备份 + 目录内容替换（v6 修正）

#### 9.4.0 为什么撤回 v4 的软链方案

v4 选了「`releases/{版本号}/` + `current` 软链」，**该方案撤回**。复盘：

| v4 软链方案的代价 | 判断 |
|---|---|
| 要求把 nginx `root` 改成指向 `current` | ❌ **根本性错误**。nginx 配置是既有事实，平台不该为了迁就自己的实现去要求运维改。平台该做的是「填对路径」，不是「改配置」 |
| 引入软链 + 原子切换（`ln -s` + `mv -T`） | ❌ 概念负担。软链让「服务器上到底跑的是什么」多一层间接，`ls` 看不到版本，排查要 `readlink` |
| `releases/` 目录 + 保留策略 | ❌ 又一套生命周期要管 |
| 回滚用本地备份、不走构建机 | ✅ **这是真收益** |

而方案 C **用更低的复杂度拿到了同一个收益**：备份就在 `static_root` 同级，回滚直接拿本地备份替换，同样不需要回构建机。目录结构一眼能看懂，和 JAR 的 `exec/bak` 是同一套心智模型。

> 教训：为了「原子切换」这一个技术指标，引入 nginx 改造 + 软链 + 目录生命周期三样东西，对这个量级的平台不划算。两次 `mv` 之间的窗口是微秒级，远比多一套概念便宜。

#### 9.4.1 目录结构

```text
/usr/share/nginx/html/                        ← nginx 已配好，平台不动
├── aiops/                                    ← static_root，当前生效版本
│   ├── index.html
│   └── assets/
└── .static-bak/                              ← 备份目录（可配）
    ├── aiops_bak_20260927193000_aiops-v12-3f9a1c2/
    └── aiops_bak_20260926120000_aiops-v11-8b21e04/
```

备份命名 `{目录名}_bak_{yyyyMMddHHmmss}_{版本号}`，与 §3.1 JAR 的 `{jar名}_bak_{时间戳}_{版本号}.jar` 完全同构。

> **为什么版本号不能省**（你提的「文件名-时分秒」要补这一位）：
> 1. 平台回滚是按 `deployment_target.old_version` **精确回滚到某个版本**，不是「回到上一个」——只有时分秒无法定位；
> 2. 跨天后 `193000` 分不清是哪天，一天多次发布也分不清哪次；
> 3. 版本号是连接 `release_record.version`、构建机产物目录、备份目录三者的唯一钥匙。JAR 链路已经证明这个命名好用。

#### 9.4.2 发布动作序列

```bash
set -e
STATIC={static_root}                    # /usr/share/nginx/html/aiops
PARENT=$(dirname $STATIC)               # /usr/share/nginx/html
NAME=$(basename $STATIC)                # aiops
BAK={static_backup_dir}                 # 默认 $PARENT/.static-bak
TS=$(date +%Y%m%d%H%M%S)

# 1. 新版本先解压到临时目录（不在原地解压，避免逐文件覆盖的中间态）
mkdir -p $PARENT/.static-new-$TS
tar -xzf /tmp/{产物} -C $PARENT/.static-new-$TS

# 2. 备份当前目录（目录级 mv，同分区是瞬间 rename）
mkdir -p $BAK
mv $STATIC $BAK/${NAME}_bak_${TS}_{当前版本号}

# 3. 放入新版本（同样是 rename，瞬间）
mv $PARENT/.static-new-$TS $STATIC

# 4. nginx reload（开了 open_file_cache 时必需）
nginx -t && nginx -s reload
```

**关键点**：第 2、3 步都是**目录级 `mv`**，同分区下只是改目录项，微秒级完成。中间的窗口是两个 rename 之间的间隔，比「原地逐个文件覆盖」小几个数量级。

> ⚠️ **前提条件：`bak` 目录必须与 `static_root` 在同一分区**。跨分区 `mv` 会退化成「复制 + 删除」，那就真的有长时间中间态了。默认配置（父目录下 `.static-bak`）天然满足；**如果运维把 `static_backup_dir` 配到别的挂载点，平台应在首次发布时检测并告警**。

首次发布时 `static_root` 不存在 → 跳过第 2 步，直接 `mv` 临时目录就位。

#### 9.4.3 回滚动作序列

```bash
# 1. 在 bak 里按 old_version 精确匹配备份目录
BAK_DIR=$(ls -d $BAK/${NAME}_bak_*_{old_version})

# 2. 复制到临时目录（不能直接 mv，备份要保留，否则回滚一次后备份就没了）
cp -a $BAK_DIR $PARENT/.static-new-$TS

# 3. 先把当前版本再备份一次（保留现场）
mv $STATIC $BAK/${NAME}_bak_${TS}_{当前版本号}

# 4. 放入旧版本
mv $PARENT/.static-new-$TS $STATIC

# 5. nginx reload
nginx -t && nginx -s reload
```

回滚与发布共用同一套「临时目录 + 两次 mv」流程，**代码可以完全复用**，只是第 1 步的来源不同（发布是上传包/构建机产物，回滚是本地 bak）。

> 回滚**不需要回构建机下载产物**——这是方案 C 相对 v3「覆盖 + 重新解压」方案的核心改进，也是原本选软链方案想拿到的收益。

#### 9.4.4 失败与清理

| 情况 | 处理 |
|---|---|
| 解压失败 | 抛异常，**`static_root` 未被触碰**，现状保持 |
| 备份 `mv` 成功、放入新版本失败 | 尝试把备份 `mv` 回 `static_root` 自愈；自愈失败则记 ERROR 并明确提示「静态目录当前缺失，请从 bak 手工恢复：{备份路径}」 |
| 临时目录清理 | 成功失败都要清（`finally`），清理失败只记 WARN，不得抛异常覆盖真正的失败原因（AGENTS.md §7） |
| `nginx -s reload` 失败 | **保留已替换的目录不回滚**，判发布失败并明确提示「静态文件已切换到 {版本}，但 nginx reload 失败，请人工检查 nginx 配置」 |

#### 9.4.5 备份目录位置的安全提示（重要）

默认 `{static_root 父目录}/.static-bak` **可能落在 nginx 可访问路径下**（`/usr/share/nginx/html/.static-bak`），`http://host/.static-bak/` 理论上能被请求到。旧版本里可能有：

- 编译进前端的内部接口地址、测试环境配置；
- source map（`.map` 文件，泄露源码结构）；
- 历史版本的敏感配置片段。

三条建议（按优先级）：

1. **把 `static_backup_dir` 配到 web 根之外**，如 `/data/backup/static/{项目编码}`。这是推荐做法；
2. 若必须放同级，用 `.` 开头（nginx 多数配置对点开头不索引，但**不保证**拒绝访问）；
3. nginx 侧加一条兜底：`location ~ /\. { deny all; }`。

平台侧做法：`static_backup_dir` 显式可配，新建项目时给出提示文案，不强制。

#### 9.4.6 备份保留策略

| 项 | 决策 |
|---|---|
| 保留数量 | 默认最近 **5 个**（与 `retain_release_count` 取较大值），保证常用回滚点大概率命中 |
| 保护窗口 | 最近 N 条部署记录引用的版本不得删除（与 §23.6 同口径） |
| 删除失败 | 只记 WARN，不影响发布结论（与 JAR 备份清理同口径） |
| 构建机侧 | 仍按 §23.6 `retain_release_count` 管理产物目录，两者互不影响 |

#### 9.4.7 手工运维入口（平台不可用时）

```bash
cd /usr/share/nginx/html/
ls .static-bak/                          # 一眼看到所有历史版本（命名含版本号）
mv aiops .static-bak/aiops_manual_$(date +%Y%m%d%H%M%S)
cp -a .static-bak/aiops_bak_20260926120000_aiops-v11-8b21e04 aiops
nginx -s reload
```

和 JAR 链路的 `exec/bak` 回滚是同一套动作，运维不用学新概念。

---

## 10. 落地路线（v5 定稿）

| 期 | 内容 | 依赖 | 验收 |
|---|---|---|---|
| **P1 打通最小闭环** | 现有 Docker 链路端到端真机跑通一次（`PROJECT_DOCKERFILE` → push → 目标机 pull → run）。**不新增功能，只补洞** | 无 | 一个真实 Java 项目全链路成功 |
| **P2 compose 发布** | `deploy_form`；compose 参数建模（含 `compose_preset`）；`ComposeRenderer` + 单份 ftl；目标机目录 + 备份 + `VERSION`；`docker compose` 兼容回落；`build` 信息注释保留 | P1 | AIops 按 compose 发布并回滚成功；平台停掉后运维能手工改 tag 起服务 |
| **P2.5 静态产物上传 + 版本化**（**合并交付**） | `UPLOAD` + `ARTIFACT_ONLY`；上传接口 + multipart 配置 + 转存构建机 + 版本登记；同级 `bak` 备份 + 目录整体替换 + 备份保留策略 | 无（可与 P2 并行） | 本地 dist 上传后能发布到 Nginx；**回滚用本地备份，不走构建机** |
| ~~**P3 语言模板 + 策略模式**~~ ❌ **已取消（2026-09-28）** | 曾实现过 `LanguageType` + `DockerfileTemplate` + Factory 与 Java/Python 两套模板，**随后整体删除** | — | 不再做：让项目自己写 Dockerfile 比平台猜语言更省事也更可靠，详见「取消说明」 |
| **P4 镜像直发 + 仓库预检** | `IMAGE_PULL` 模式；`PROJECT_TEST` 可写性探测（默认关） | P1 | 外部 CI 推好的镜像能被分发与回滚 |

> ~~P2 排在 P3 之前：compose 是发布侧的形态问题……语言模板只影响走 `PLATFORM_TEMPLATE` 的项目。~~ **P3 已取消**（平台模板整体下线），现在 DOCKER 侧的排序是 P1 → P2 ∥ P2.5 → P4。
> P2.5 与 P2 无依赖，可并行；复用的都是现成链路，工作量主要在上传接口、multipart 配置与备份替换逻辑。

**不建议同期做**：物理分表（§5）、依赖指纹复用（ADR-06）、多架构镜像、Node 模板。

---

## 11. 风险与遗留

| # | 风险 | 影响 | 缓解 |
|---|---|---|---|
| 1 | 平台模板容器内构建无依赖缓存 | 首次构建 5–10 分钟，逼近默认超时（1800s） | 文档写明；提供 `command_timeout_seconds`；v1.5 挂缓存卷 |
| 2 | **compose 覆盖冲掉运维手工改动** | 运维在服务器上改的 env/volume 下次发布丢失 | 强制备份；文件头警示 + 文档明示「改动请回平台配置」 |
| 3 | 目标机无 `docker compose` v2 插件 | P2 全部失败 | 命令探测 + 回落 v1，实际命令写日志 |
| 4 | **external 网络未声明** | compose 尝试创建已存在网络 → 失败 | 渲染时 `networks` 必须带 `external: true`；失败信息明确指向该配置 |
| 5 | **相对路径卷解析错位** | `./logs` 挂到错误位置 | 渲染时统一转绝对路径 |
| 6 | **compose 内容含密文进日志** | 环境变量值泄露 | 回显前 `SecretMasker` 脱敏；敏感 key 提示改用 env_file |
| 7 | 镜像仓库磁盘增长 | 按 tag 累积，永不清理 | 平台不管仓库清理；文档提示在仓库侧配生命周期规则 |
| 8 | `IMAGE_PULL` 模式 digest 可能为空 | 无法校验镜像一致性 | 允许为空但明示；可选 `docker manifest inspect` 补取 |
| 9 | 语言探测误判 | 生成错误 Dockerfile | `language` 允许显式覆盖；探测失败报错并列出支持的文件特征，不猜 |
| 10 | `restart` 默认值变更 | 已有项目重启语义变化 | 显式可配；变更时提示一次 |
| 11 | 分表诉求被推迟 | 参数膨胀后 JSON 难维护 | §5.4 的 4 条触发条件；每期交付后复核 |
| 12 | 构建机磁盘 | 镜像 layer 缓存累积 | 定期 `docker system prune`（注意并发安全） |
| 13 | **大文件上传失败/超时** | 前端 dist 包几十 MB 常见 | multipart 放开到 500MB；上传后异步转存；临时文件 `finally` 清理 |
| 14 | **上传产物无 commit 可追溯** | 出问题时不知道这包是哪个版本代码 | 上传时可选填备注；版本列表展示来源标记 |
| 15 | **`bak` 与 `static_root` 跨分区** | `mv` 退化成复制+删除，出现长时间中间态 | 默认同父目录；显式配置时在首次发布检测并告警 |
| 16 | **备份目录落在 web 根下** | 旧版本资源被外部访问（含 source map） | `static_backup_dir` 可配，建议放 web 根外；文档提示 nginx 加 `location ~ /\. { deny all; }` |
| 17 | **`bak` 目录无限增长** | 目标机磁盘吃满 | 保留最近 5 个 + 保护窗口；删除失败只记 WARN |
| 18 | 目标机 SSH 用户无 `static_root` 父目录写权限 | `mv` 失败（注意：需要的是**父目录**写权限，不是 `static_root` 本身） | 首次发布时检测写权限并给出明确报错 |
| 19 | nginx `open_file_cache` 导致切换后仍返回旧内容 | 发布了但用户看到旧的 | 固定执行 `nginx -s reload`；reload 失败保留新目录 + 明确提示 |
| 20 | compose 预设误导 | 用户照抄 `JAVA_JAR` 预设但实际不用 config 卷 | 预设只是默认值可自由增删；文档说明预设含义 |

---

## 12. 已定稿清单与下一步

### 12.1 全部待拍板项已关闭

| 项 | 结论 |
|---|---|
| 分表 | **不分表**（§5） |
| compose 形态 | **文件由项目仓库提供 + 构建期快照 + 发布期下发 + `${IMAGE_TAG}` 变量注入**（v7 修订，ADR-03） |
| compose 模板 | **不提供**（v7 连 ADR-11 一并作废） |
| `build:` 块 | **不生效，注释保留**（§8.8） |
| 前端静态 | **开放 `UPLOAD` + `ARTIFACT_ONLY`**（ADR-10） |
| STATIC 版本化与回滚 | **方案 C：`static_root` 同级 `bak` 备份 + 目录内容替换**（目录 inode 不变），不动 nginx、不用软链（ADR-12 / §9.4） |
| 静态版本化排期 | **与上传同期交付**（§10 P2.5） |
| 分层构建顺序 | **先 base 后 app，保持不变**（ADR-06） |

### 12.2 下一步（按 P1 → P2 ∥ P2.5 → P4）

> **2026-09-28 重大调整：P3（语言模板）取消，平台模板模式 `PLATFORM_TEMPLATE` 整体下线。**
> 曾按 ADR-02 / ADR-08 实现过 Java / Python 两套 Dockerfile 模板 + 工厂 + 9 个单测，随后连同
> `LanguageType`、`build/template/` 包、`build_config_json` 里的 `language` / `app_port` / `start_command`
> 三个字段一并删除。理由见下。

**取消说明（为什么会走这段弯路）**

| 维度 | 做平台模板 | 项目自带 Dockerfile |
| --- | --- | --- |
| 适配成本 | 平台要猜语言、猜构建工具、猜启动命令；猜错产出「能构建成功、运行即崩」的镜像 | 项目自己写，天然正确 |
| 维护面 | 每加一门语言 = 一套模板 + 一套推导逻辑，永远追不上语言生态 | 平台只维护「指定哪个文件」 |
| 灵活性 | JVM 参数、多 worker、非标准入口要么加字段、要么只能放弃用平台模板 | 想怎么写怎么写 |
| 失败定位 | 容器起不来时，用户要判断是模板推导错还是自己代码错 | 责任边界清清楚楚 |

结论：**Dockerfile 与 `docker-compose.yml` 这类「只有项目自己最清楚」的东西不要平台代劳**，
平台退回它真正擅长的部分——编排构建、管 VERSION 与保留策略、把产物送到目标机。
约束已写进 AGENTS.md §23.8。

> ⚠️ **存量数据处理**：已删业务的库若有 `build_mode='PLATFORM_TEMPLATE'` 的项目记录，
> 改用新版本后这些项目无法再发起构建（会提示明确的改法）。升级时需在库里一次性订正，MySQL 变更一律手工执行：
>
> ```sql
> -- 先查受影响的记录
> SELECT id, code, name, build_mode FROM project WHERE build_mode = 'PLATFORM_TEMPLATE' AND is_deleted = 0;
>
> -- 确认为「仓库里有 Dockerfile」的项目后改过去（多数场景）
> UPDATE project SET build_mode = 'PROJECT_DOCKERFILE' WHERE build_mode = 'PLATFORM_TEMPLATE' AND is_deleted = 0;
> ```
>
> 同时 `build_config_json` 里的 `language` / `app_port` / `start_command` 字段已无消费方，可留可清（留着不报错）。

1. **P1 先做，别跳**：现有 Docker 链路写完到现在**没有端到端真机验证过**。先用 `PROJECT_DOCKERFILE`（项目自带 Dockerfile）跑通一个真实项目全流程，把暴露的洞补掉，再动新功能。
2. **P2 与 P2.5 可并行**：两条链路互不依赖，Docker 侧改 `DeployTaskHandler`，静态侧改 `BuildTaskHandler` + `deployStatic`。
3. **P2.5 没有 nginx 改造前置动作了**（方案 C 取消了这项），唯一要提前确认的是 `static_backup_dir` 放哪——建议直接配到 web 根之外。
4. **实施时同步更新**：AGENTS.md（STATIC 备份与替换形态、compose 覆盖口径、`restart` 默认值）、`docs/发布模块.md`、`docs/构建模块.md`、前端 `deployTypes.ts` 与项目编辑页。
