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
- `STATIC`：前端静态产物配合 Nginx 部署。**平台只替换静态根目录下的文件内容（目录本身不动，见 §3.2），不碰 nginx**——
  不生成站点配置、不改 `root`、**不执行 `nginx -t` 与 `nginx -s reload`**。三条理由：
  ① nginx 每次请求重新解析路径，目录内容替换后新内容即时生效，reload 不是必需动作；
  ② nginx 未必由目标机上的那个二进制在跑（常见于容器内 nginx + 宿主机挂载目录，
     或由另一台机器承担），用 `command -v nginx` 判别「该不该 reload」本身就不成立；
  ③ reload 失败会把一次「文件已成功替换」的发布判成失败，结论与磁盘实际状态相反，
     即「发布失败但文件是新的」这种最难排查的组合。真需要 reload 由 nginx 侧自己解决；

Docker 构建模式预留：

- `PROJECT_DOCKERFILE`：项目自带 Dockerfile；
- `LAYERED_DOCKER`：`Dockerfile.base + Dockerfile.app` 分层构建；
- `SCRIPT_DEPLOY`：脚本部署，执行项目自带的部署脚本，不走 Docker 构建；
- `ARTIFACT_ONLY`：直接发布已有 JAR 或静态产物。
- `JAR_PREBUILD`：平台先用 Maven 容器打出 jar，再用项目提供的极简 Dockerfile 包成镜像（目标机只做纯 `docker run`，无需在目标机装构建工具链）。

上述取值与 `BuildMode` 枚举一一对应，枚举类是唯一权威定义，数据库、接口、前端与文档必须与枚举保持一致（见 §22）。

AIops 项目使用分层构建：基础依赖镜像推送到阿里云镜像仓库，业务镜像基于基础镜像构建，Docker Compose 使用业务镜像启动。

### 3.1 JAR 脚本部署形态（固定工作目录 + 原地替换）

JAR 运行时采用「固定工作目录 + 原地替换」，**不按版本建子目录**：

- 工作目录由 `runtime_config_json.work_dir` 指定（如 `/lx/jarapps/auth-tool-7888`），**填了即完全覆盖服务器档案的部署根目录**——平台按字面使用、不拼接不转换，未配置时才回落到「目标机部署根目录 `/apps` / 项目编码」；
- `work_dir` **必须写绝对路径**：平台不校验存在性也不做规范化（只去首尾空白与结尾斜杠），写成 `./lx/...` 这类相对路径会相对 SSH 登录用户的家目录解析（root 即 `/root/lx/...`），与填写时的预期位置完全不同；
- **systemd 单元只在首次部署生成，之后不再重写**——运维手工调整过的启动参数（`-Xmx`、`--spring.config.location` 等）必须被尊重；
- 发版动作固定为四步：备份旧 jar 到 `exec/bak` → 投放新 jar → `systemctl restart` → `is-active` 确认。回滚走同一路径，只是投放 `old_version` 的 jar；
- 投放前先在目标机比对现有 jar 的 `sha256`：与本次版本一致时**跳过备份与上传**（重复发布、失败重试都会命中），两种传输方式与日志拆分要求见 §24.7；
- jar 与配置分离：配置文件放在工作目录的 `config/` 下，由 `build_config_json.config_paths` 随产物转存，**平台不覆盖目标机已存在的配置**；
- 备份命名为 `{jar名}_bak_{时间戳}_{版本号}.jar`（如 `auth-tool_bak_20260925222851_auth-7888-v6-c9bb25d3.jar`），**被替换包的版本号内嵌在文件名里**，`ls` 一眼就能看出每个备份是哪个版本。取不到版本时省略版本段，退化为 `{jar名}_bak_{时间戳}.jar`；同一秒同版本重名时追加 `_2`、`_3`——该序号**只是防撞名，与版本无关**。三种形态 `jar-update.py` / `jar-fallback.py` 都必须能识别；
- **版本段逐字保留平台版本号，不把其中的连字符换成下划线**：`release_record.version`、Docker 镜像 tag、`VERSION` 文件里写的都是 `auth-7888-v7-1521739f`，文件名必须与之字面一致，才能直接复制去查库、搜日志、比对镜像。若为「统一下划线」把 `-` 换成 `_`，读回时就得按段回译，而项目编码本身允许含 `_`，回译不再双射——拿一个隐式约定换一点视觉整齐，不划算。文件名里 `-` 与 `_` 混用**是有意为之**：`_` 分隔备份名的结构段，`-` 属于版本号本身；
- 部署脚本内置在 `resources/scripts/jar-deploy/`，由平台上传执行。这类脚本必须**非交互（不接受 input）且返回明确退出码**：SSH 执行没有 TTY，交互式脚本会挂起到超时，而退出码恒为 0 会让平台把失败判成成功。

本形态只约束「JAR + 脚本构建」链路。**DOCKER 不受影响**：仍按 `dockerfile_path` 构建镜像、`docker pull + run` 发布，不使用工作目录与上述脚本；`config_paths` 也不在 DOCKER 分支消费。

### 3.2 STATIC 静态发布形态（固定目录 + 内容替换）

STATIC 运行时采用「固定目录 + 内容替换」，**不按版本建子目录，也不换目录**：

- `static_root` 是 nginx `root` 指向的那个目录，平台只替换它**里面的文件**；
- **禁止 rename / 删除 `static_root` 本身**（2026-09-28 定稿，血泪教训）：目录的 inode 必须全程恒定。
  原因——Docker 的 bind mount 是容器启动时按宿主机路径**解析一次并绑定 inode** 的，
  源目录一旦被 rename 走，容器仍握着旧 inode，容器内看到的就是空目录（源 inode 被彻底删除时），
  nginx 报 `directory index of "..." is forbidden`、站点全站 403。
  此时 `nginx -s reload` 无效（reload 不重新挂载），**只能重建容器**才能恢复。
  静态站点跑在容器里是常规形态，这条没有例外；
- 因此切换动作是「旧内容整体搬到备份目录 → 新内容搬进来」两批按条目 rename，
  由 `StaticDeployService.MOVE_CONTENTS` 里的 shell 函数 `_ld_move` 完成。
  代价是切换期间存在极短的目录为空窗口；可接受的原因是两种失败模式不对等：
  **内容替换**最坏结果是瞬时 404 且自愈，**换目录**最坏结果是容器陷入失效挂载、必须重建容器；
- **整体搬走旧内容，不做增量覆盖**：只有整体搬走才能保证新版本是**精确替换**——
  旧版本独有的文件（改名过的 js/css）会随备份一起离开，不会残留在站点里；
- 备份命名为 `{目录名}_bak_{时间戳}_{版本号}`，与 §3.1 同构，落在 `static_backup_dir`
  （默认 `static_root` 同级的 `static-bak`）。回滚优先用目标机本地备份 `cp -a` 还原，不过构建机；
- **不执行 `nginx -t` / `nginx -s reload`**：平台不修改 nginx 配置，
  且 nginx 未必由目标机上的那个二进制在跑（它在容器里、或在另一台机器上），
  用 `command -v nginx` 判别「该不该 reload」这件事本身就不成立。
  目录内容替换后 nginx 每次请求重新解析路径，新内容即时生效。
  唯一例外是 nginx 开了 `open_file_cache`，那是 nginx 侧配置，在 nginx 那边解决，不由发布链路代劳；
- 每次切换在任务日志里输出 `static_root` 的 inode（发布前 → 发布后），
  让「容器内看到空目录」这类故障能直接在日志里定性，不必登机器对比；
- 产物解压后为空时**中止发布**（站点此时还没被碰过），不允许「清空站点却报发布成功」。
- 产物从构建机到目标机的完整分发逻辑（两跳中转、传输通道抉择、每一步的失败语义）见 `docs/产物分发链路.md`。

### 3.3 产物上传形态（只接受单个压缩包）

来源为 `UPLOAD` + 构建模式为 `ARTIFACT_ONLY` 的项目，产物由运维在页面上直接上传：

- **上传只接受单个文件**：`.zip` / `.tar.gz` / `.tgz`（静态产物）与 `.jar`（JAR 运行时），单文件 500MB；
- **不做文件夹上传**：目录选择要同时付出三项代价——前端 `webkitdirectory` 另传一份相对路径数组、后端放开文件名白名单允许 `/`、接口从单个 `MultipartFile` 改成数组，最后还要回答「要不要剥掉最外层目录」，怎么做都是隐式约定。**压缩包把这三项全部消掉**；
- **压缩包推荐平铺打包，但不是硬要求**：在产物目录内打包（`cd dist && zip -r app.zip .`）解开后直接是 `index.html`；在父目录打包（`zip -r aiops.zip aiops`）会多出一层，由 `StaticDeployService.stripTopLevelDir` **自动剥离**（等价于 `tar --strip-components=1`），不必为打包姿势重传。剥壳与 `static_root` 指向哪一层无关——它作用在解压后的暂存目录上，剥完再替换 `static_root` 的**内容**（目录本身不动，见 §3.2）；
  - **剥壳判据三条全中才动**：根下恰好一个条目 + 该条目是目录 + 该目录内存在 `index.html`。只满足「单目录」就剥会误伤根下只有 `assets/` 这类包；要求内层有 `index.html` 后，平铺包天然不满足第一条，不会被误剥；
  - 判据不满足且形态异常时由 `warnIfNested` 告警（**只告警不阻断**：少数站点入口文件不叫 index.html，平台不该替运维做这个决定）；
- 备份目录名**禁止以点开头**：曾经叫 `.static-bak`，本意是让 nginx 不容易直接暴露它，但隐藏目录在终端 `ls` 里看不见，回滚翻备份、清磁盘找目录都受影响，**看不见比被访问到更麻烦**。安全那条由「把备份目录配到 web 根之外」解决，不靠隐藏。暂存目录同理；
- 构建机上已经是目录的产物，由平台自己 `tar -czf pkg -C {目录} .` 打包，同样是平铺的，与上传侧口径一致。

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

时间列必须使用 `DATETIME`，**禁止使用 `TIMESTAMP`**：

- `TIMESTAMP` 会按会话时区隐式换算（写入时按会话时区转 UTC、读取时再转回来），一旦 JDBC 连接时区与服务端不一致，同一行数据在不同连接下显示的时间会相差整小时，排查成本极高；`DATETIME` 按字面值存取，不参与时区换算；
- `TIMESTAMP` 的取值上限是 `2038-01-19`，属于先天性缺陷；
- `create_time` 必须 `NOT NULL DEFAULT CURRENT_TIMESTAMP`；`update_time` 必须 `NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`，该属性是 MyBatis-Plus 填充之外的最后兜底（见 §10.2），不能省；
- 早期手工建库把这两列建成了 `TIMESTAMP` 时，执行 `src/main/resources/db/upgrade-base-time-to-datetime.sql`（幂等）收敛，再用 `verify-schema-columns.sql` 的第三个结果集确认已对齐。

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

### 4.5 状态字段统一数字编码

所有业务表的**状态、结果**类字段一律使用 `TINYINT` 保存数字编码，**禁止使用 `VARCHAR` 保存 `ENABLED` / `DISABLED` / `SUCCESS` 这类字符串编码**。数字编码在开发、联调、SQL 排查中都更简洁通用，也避免字符串大小写与拼写差异导致的隐式错误。

- 字段 `COMMENT` 必须写明每个数字的含义，多值字段按数字升序列出全部取值，例如 `COMMENT '状态：0停用，1启用'`；
- 字段类型统一 `TINYINT`，非空字段必须给 `DEFAULT`，默认取语义上最保守的取值；
- Java 侧禁止用枚举表达状态，统一使用 `com.lx.deploy.common.constants` 下的常量类（`EnableStatus`、`TaskStatus` 等）做 `int` 比较；常量类必须提供 `isValid(Integer)` 校验方法与 `describe(Integer)` 中文描述方法，业务代码不允许散落 `status == 0` 这类无含义字面量；
- 实体 / DTO / VO 的状态字段类型统一 `Integer`，JavaDoc 必须逐个数字写明含义（如「0 停用 / 1 启用」），并标注哪些取值由服务端维护；
- 前端状态字段类型统一 `number`，下拉选项、标签映射、默认值与查询条件全部按数字取值，禁止传入字符串；
- 本节只约束「状态 / 结果」类字段；「类型 / 分类」类字段（如 `runtime_type`、`source_type`、`build_mode`、`deploy_strategy`、`auth_type`、`env_tag`）沿用既有编码方式（字符串编码或数字编码 + `common.enums` 枚举），不强制改造。

当前已确认的状态编码总表，新增状态字段时在此追加，并同步登记对应的 Java 常量类：

| 表.字段 | 编码含义 |
| --- | --- |
| `user.status` | 0 停用 / 1 启用 / 2 锁定 |
| `server.status` | 0 停用 / 1 启用 |
| `project.status` | 0 停用 / 1 启用 |
| `project_environment_target.status` | 0 停用 / 1 启用 |
| `registry.status` | 0 停用 / 1 启用 |
| `lds_task.status` | 0 排队中 / 1 执行中 / 2 成功 / 3 失败 / 4 已取消 |
| `build.status` | 0 排队中 / 1 构建中 / 2 成功 / 3 失败 |
| `release_record.status` | 0 构建中 / 1 可发布 / 2 已废弃 |
| `deployment.status` | 0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 回滚中 / 6 已回滚 |
| `deployment_target.status` | 0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 已跳过 |
| `deployment_target.health_status` | 0 未检查 / 1 健康 / 2 不健康 |
| `aiops_event.status` | 0 待发送 / 1 成功 / 2 失败 / 3 已放弃 |
| `audit_log.result` | 0 失败 / 1 成功 |

状态字段新增取值或调整含义时，必须在同一次提交内同步：`schema.sql` 的字段注释、Java 常量类、实体 / DTO / VO 的 JavaDoc、模块文档（`docs/Xxx模块.md`）与前端 `deployTypes.ts` 及页面映射。已有数据库需要迁移时，升级脚本放在 `src/main/resources/db/` 下手工执行，脚本必须采用「新增临时列 → 数据映射 → 删除旧列 → 重命名」的方式（参考 `upgrade-status-to-numeric.sql`），禁止直接改列类型依赖隐式转换或截断。

**数据库列改造脚本的硬性要求**（`upgrade-*.sql`），缺一不可：

- **幂等**：脚本必须能反复执行且结果一致，每一段都用 `information_schema` 判断「表是否存在、列是否还是字符串类型」，表不存在就整段跳过，不允许因为重复执行或表未建而中断；
- **收敛到最新定义**：无论列是刚改造完还是早已是数字类型，结尾都要用 `MODIFY COLUMN` 把类型、`NOT NULL` / `DEFAULT`、`COMMENT` 覆盖成 `schema.sql` 的当前定义，兼顾「改类型」与「改注释」两件事；
- **索引不能丢**：`DROP COLUMN` 会删除该列上的单列索引、把多列索引裁剪掉这一列，脚本必须在改列后按 `schema.sql` 检查并补回索引（如 `idx_project_status`、`idx_target_server (server_id, status)`）；
- **配套只读校验脚本**：每个改造脚本必须同时提供 `verify-*.sql`（参考 `verify-schema-columns.sql`），用 `information_schema` 逐列输出「实际类型 / 注释 / 索引 与期望值的比对结论」，让开发者执行后一眼看到是否还有残留；
- **脚本头部写清用法**：执行命令、所需权限、旧值映射表、回退默认值策略、执行后抽查 SQL，都要写在文件头注释里；
- 脚本只改列定义与状态取值映射，不得删除业务记录；改造后的列会移动到表末尾（MySQL 保留列序需重建表），与 `schema.sql` 的列顺序可能不同，属正常现象。

### 4.6 事务边界

**一次业务动作要写多张表时，Service 方法必须加 `@Transactional(rollbackFor = Exception.class)`**，取「全成功或全回滚」，不允许留下半截数据。典型场景：

- 构建创建：`build` 记录 + BUILD 任务；
- 部署创建 / 回滚：`deployment` 主记录 + `deployment_target` 快照 + DEPLOY 任务。

反面教材（真实事故）：`createAndTrigger` 早期没有事务，`deployment_target` 插入失败时主记录已经落库，留下一条**永远不会有关联任务**的孤儿部署；更糟的是它的排队中状态会命中「该项目环境下已有进行中的部署」防重判断，把后续所有发布挡在门外，只能人工取消才能恢复。

- `rollbackFor = Exception.class` 必须显式写出（默认只对 `RuntimeException` 回滚），与 `ProjectEnvironmentTargetService` 保持一致；
- 只在 Service 的公开方法上声明事务，Controller 不开事务；
- 事务方法内**禁止**发起远程调用与耗时 IO：SSH、构建、健康检查都在任务执行器里做，不进事务；
- 只读多表查询不依赖事务注解；跨模块只注入 Mapper 与对方 Service，不为了「共用一个事务」去注入对方的 Service 内部实现。

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

环境标签表示服务器的默认环境归属，**仅用于服务器列表展示和筛选**。它与 `environment` 表**没有外键、没有一致性校验**：把 `env_tag=1（开发）` 的机器绑到 prod 环境，平台既不拒绝也不警告。正式发布目标只认 `project_environment_target`（详见 §24.11）。

### 6.3 服务器状态

数据库使用数字编码，Java 侧使用 `com.lx.deploy.common.constants.EnableStatus` 常量，不使用枚举（见 §4.5）：

```text
0 = 停用
1 = 启用
```

状态只表示服务器是否允许被平台连接和参与部署，不能用来表达开发、测试或生产环境。

### 6.4 机器目录配置

平台在机器上使用的目录按机器配置，保存在 `server` 表三列，由服务器页面维护：

```text
build_workspace_dir  构建工作区根目录，仅构建机使用：每次构建在其下建独立子目录，结束后清理
build_artifact_dir   构建产物持久目录，仅构建机使用：产物转存于此供发布下载，不随工作区清理
agent_base_dir       平台在该机器上的部署根目录：连通性测试检查其存在且可写，应用部署在其 apps 子目录下
```

硬性要求：

- **禁止在 Java 代码或 `application.yaml` 中写任何机器上的绝对路径**，包括所谓"默认值"：默认值由数据库列的 `DEFAULT` 定义，代码只负责读取；
- 执行器（构建、连通性测试、发布）一律从服务器档案读取目录，不使用 `@Value` 注入路径；
- 读取时去首尾空白；为空时抛出指向「去服务器页面配置」的校验错误，**不静默回落到某个猜出来的路径**；
- Service 保存时把空白视为未配置并置为 `null`，交给数据库默认值填充，保证默认值只有一处定义；
- `apps` 是平台约定的子目录名（常量 `APPS_SUBDIR`），由部署根目录拼接得到，不单独提供配置项；
- 新增这类列必须同步 DDL（`schema.sql`）+ 幂等升级脚本 + 只读校验脚本（见 §4.4）。

> 背景：这三个目录最初是配置项，靠 `@Value("${...:/opt/...}")` 兜底。问题是它们都是"某台机器上的目录"——
> 不同构建机挂的数据盘不同、不同目标机的部署根目录也可能不同，按机器配置才符合运维现实；
> 而且改一个目录要动配置文件并重启平台，属于不该有的运维成本。

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

### 7.1 四类凭据的填写口径

`credential.credential`（明文）往里填什么由 `type` 决定，四类**完全不同**，填错的表现也不一样（填写示例、三平台设置入口见 [凭据模块](docs/凭据模块.md) §6.6 / §6.7）：

| 类型 | 明文内容 | 消费方 |
| --- | --- | --- |
| `GIT_TOKEN` | Git 访问令牌；`用户名:令牌` 或**只填令牌**（平台补 `oauth2` 占位） | `BuildTaskHandler.buildCloneCommand` |
| `SSH_KEY` | 完整 PEM 私钥（含首尾标识行、保留换行） | 同上（写临时文件 + `GIT_SSH_COMMAND`） |
| `REGISTRY` | `用户名:密码`，按**第一个**冒号切分 | `BuildTaskHandler.dockerPush`（`docker login -u/-p`） |
| `SSH_PASSWORD` | —— | **没有消费方**，服务器 SSH 密码在 `server` 表 |

- **HTTPS 仓库的令牌必须落在 URL 的密码位**（`https://用户名:令牌@主机/路径`）。用户名位不参与认证，但**不允许为空**——GitLab 官方口径「任意非空字符串，GitLab 不校验该值」、GitHub 官方口径「用户名不用于认证，但不填会报凭据无效」、Gitee 用 `oauth2`。三个平台因此统一：只填令牌时补 `oauth2:令牌`。**令牌只放在用户名位（`https://令牌@主机`，GitHub 的习惯写法）在 Gitee 上必然失败**；
- **凭据类型与仓库协议必须双向校验**：`GIT_TOKEN` 只允许 `https://` 仓库，`SSH_KEY` 只允许 SSH 形式仓库（`git@主机:组/仓库.git`）。校验放在拼装克隆命令时做，报错直说协议不匹配，不允许让它们跑起来后才在克隆时报「仓库不存在」这类误导性错误；
- **克隆命令前必须加 `GIT_TERMINAL_PROMPT=0`**：构建机上的克隆跑在非交互 SSH 会话里，git 一旦转去索要凭据，报错会是 `could not read Password ... No such device or address`——**把真正的原因（URL 里缺密码段）掩盖成认证失败**，排查时会被带偏；
- **凭据拼装实现全平台只有一份**：Git 地址拼装、镜像仓库登录命令、命令脱敏分别收敛在
  `com.lx.deploy.common.util.GitAuthSupport` / `RegistryAuthSupport` / `SecretMasker`，
  构建链路（`BuildTaskHandler`）与项目连通性测试（`ProjectConnectTestTaskHandler`）**共用**它们。
  两处各写一套必然在「谁补 `oauth2`」「密码怎么传」上分叉，表现为最难查的「测试通过、构建失败」；
- 镜像仓库密码一律走 `--password-stdin`，**不用** `docker login -p <密码>`：后者会把明文密码留在构建机
  进程列表（`ps -ef`）里。密码先写到构建机临时文件（权限 600），用完在 `finally` 里删除；
- **临时凭据文件（私钥 / 仓库密码）用完必删**，且清理**不得抛异常**——在 `finally` 里抛出会覆盖真正的失败原因。


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

所有响应应携带 `requestId`，用于关联接口请求和服务端日志。

## 10. MyBatis-Plus 配置

必须保留：

- MySQL 分页插件；
- 下划线转驼峰；
- `ASSIGN_UUID` 主键策略；
- 数据库主键生成统一使用 `IdGenerator.generateUUID()`，禁止在业务代码中直接调用 `UUID.randomUUID()`；主键生成的两个入口（业务代码 / MyBatis-Plus 插槽）必须指向同一个 `IdGenerator`，见 §10.3；
- `is_deleted` 逻辑删除；
- `createTime`、`updateTime` 自动填充；
- 通用 `PageQuery` 和 `PageResult`。

分页接口每页最大数量为 100，避免无边界查询。

### 10.1 更新语义与「可清空字段」

MyBatis-Plus 的 `updateById` 默认策略是 `NOT_NULL`：实体里为 `null` 的字段不会出现在 `SET` 子句里。这对「未传就不改」的字段是正确的，但会让**允许被调用方清空的字段出现「能设置、不能清空」的 bug**（前端提交空串、Service 归一为 `null`，数据库里旧值依然保留）。

- 凡是由表单维护、允许清空的字段，必须在实体上显式声明：

```java
// 清空时必须真的写入 NULL，否则 MyBatis-Plus 默认忽略 null 字段
@TableField(value = "build_config_json", updateStrategy = FieldStrategy.ALWAYS)
private String buildConfigJson;
```

- 当前已按该策略声明的字段：
  - `project.build_config_json` / `runtime_config_json` / `health_config_json`（三段 JSON 配置）；
  - `project.git_url` / `git_branch` / `git_credential_id` / `registry_id` / `description`
    —— 表单可以清空或换绑，其中 `git_credential_id` 与 `registry_id` 是「解除绑定」的唯一手段；
  - **判断标准**：只要前端表单能把它清空（或某个运行时下不再提交它），就必须标 ALWAYS。
    未标注时 `updateById` 会跳过 `null`，表现为「设置得上、清不掉」，并且会留下与状态矛盾的脏值
    （典型：DOCKER 项目改成 JAR 后 `registry_id` 仍然挂着仓库绑定）；
- **禁止**在 `application.yaml` 里全局设置 `mybatis-plus.global-config.db-config.update-strategy: always`：那会把请求未覆盖的字段（`created_by` / `updated_by` 等审计字段、`server.ciphertext` 这类服务端维护的密文）一并置空；
- 由服务端维护、不允许被请求清空的字段（审计字段、加密凭据、状态流转字段）保持默认 `NOT_NULL` 策略；
- 新增「可清空字段」时同步补充本节清单，并在模块文档的数据表小节注明该字段可以被清空。

### 10.2 update_time 刷新语义（禁止 strictUpdateFill）

「改了数据但 `update_time` 不变」的根因不在数据库，而在自动填充器的写法，已定位并修复（2026-09-23）。

修改类 Service 的统一写法是「先查出实体 → 把 DTO 字段覆盖上去 → `updateById`」。此时实体上的 `updateTime` 已经是**从库里读出来的旧值（非 `null`）**，而 `strictUpdateFill` 只在字段为 `null` 时才填充，于是旧值被显式写进了 `SET` 子句。MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 只在「该列没有被显式赋值」时才生效，被显式赋值（哪怕赋的就是原值）时不生效，最终表现为数据确实改了、`update_time` 却停在上一次的时间。

因此 `MybatisPlusConfig#metaObjectHandler` 的 `updateFill` 必须**无条件覆盖** `updateTime`：

- **禁止**改回 `strictUpdateFill`，也禁止给 `updateTime` 加任何「仅当 null 才填充」的判断；
- **禁止**在 Service 里手工 `entity.setUpdateTime(...)`，更新时间只由填充器维护；
- **禁止**在 Mapper / 自定义 SQL 里显式写 `update_time = ...`；
- 只有走实体参数的 `updateById(entity)` 才会触发填充；`update(LambdaUpdateWrapper)` 这类不传实体的写法不触发填充，靠数据库的 `ON UPDATE CURRENT_TIMESTAMP` 兜底，所以 `update_time` 的 `ON UPDATE` 属性不能省（§4.3）；
- 目标行为：任何一次「字段值确实发生变化」的更新都必须让 `update_time` 前进；MySQL 仅在「所有列都被赋成原值」时才不刷新，属预期行为，业务不接受时由 Service 自行判断是否真的发生变化。

### 10.3 主键生成的两个入口必须指向同一个 IdGenerator

主键格式的权威定义只有一处：`com.lx.deploy.common.util.IdGenerator.generateUUID()`，产出 32 位不带连字符的小写 UUID。平台有**两个**入口会生成主键，两者必须指向同一个工具类，不允许出现第二套生成逻辑：

| 入口 | 位置 | 要求 |
| --- | --- | --- |
| 业务代码显式赋值 | Service / 生成器模板 | 调 `IdGenerator.generateUUID()`；新增时也可 `setId(null)`，交给框架插槽 |
| 框架插槽 | `config/UuidIdentifierGenerator`（实现 MyBatis-Plus `IdentifierGenerator`） | `nextUUID(Object)` 必须调 `IdGenerator.generateUUID()` |

- 框架插槽这条链路是隐式的：Spring Boot 自动发现该 Bean 并注册为全局主键生成器，实体上标 `IdType.ASSIGN_UUID` 时由它生成主键，业务代码里看不到调用点。因此**只改业务代码一个入口是不够的**，两处必须同时指向 `IdGenerator`；
- **禁止**在 `nextUUID` 里直接调 `UUID.randomUUID()`：那会产出带连字符的 36 位主键，与 `VARCHAR(32)` 列宽和全库主键格式都不一致；
- **禁止**新增第二个 `IdentifierGenerator` Bean：MyBatis-Plus 全局只生效一个实例，多 Bean 会让「实际生效的是哪个」不可预测；
- `UuidIdentifierGenerator#nextId(Object)`（`ASSIGN_ID` 数值主键分支）当前没有实体使用，属于**兼容保留**：保留它是为了将来出现数值主键的扩展实体时不被本 Bean 影响。该分支必须继续委托 `DefaultIdentifierGenerator`，其返回类型是 `Number`，**不得**改成返回 UUID 字符串，否则破坏 MyBatis-Plus 的接口契约；
- 新增实体一律 `VARCHAR(32)` 主键 + `ASSIGN_UUID`。确需数值主键属于例外，必须先在模块文档里说明理由；
- 校验方式：编译通过只能证明业务代码侧这条链路，插槽侧要实际新增一条记录后确认落库 `id` 是 32 位小写十六进制（长度不对说明两处格式已经分叉）；修改 `IdGenerator` 的产出格式时，必须同步检查 `nextUUID` 这条链路。

### 10.4 列表查询统一支持创建时间区间

**所有带搜索区的分页列表都必须支持「创建时间区间」筛选**，三处必须成套出现，缺一处即视为未完成：

| 位置 | 实现 | 说明 |
| --- | --- | --- |
| 后端 DTO | 继承 `common.model.TimeRangeQuery` | 提供 `createTimeBegin` / `createTimeEnd`，字段上带 `@DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")` |
| 后端 Service | `TimeRangeSupport.apply(wrapper, Xxx::getCreateTime, condition)` | 闭区间 `ge` / `le`；开始晚于截止抛 `VALIDATION_ERROR`「开始时间不能晚于截止时间」 |
| 前端页面 | `<CreateTimeRange v-model="timeRange" />` + `...splitTimeRange(timeRange.value)` | 组件在 `components/CreateTimeRange.vue`，参数拆在 `utils/index.ts` |

约束：

- **只筛 `create_time`，不引入业务时间维度**：构建有 `started_at` / `finished_at`、任务有开始与结束时间，但这些列在排队或执行中时是空的，按它们筛会让「明明有数据却查不到」变成常态。创建时间对任何一行都存在，语义也最直白——「这段时间里新增了哪些」。业务时间照常作为列展示，只是不参与筛选；
- **只传一端按单边过滤**，两端都不传等于不限；**区间是闭区间**（`>=` 与 `<=`），用开区间会让恰好落在边界上的记录消失，而这类消失几乎不可能被复现；
- **禁止各 Service 自己写 `ge` / `le` 与区间校验**：复制十遍的判空与校验，第十一遍总会漏掉「开始晚于截止」这类边界，而漏掉的表现是「查出 0 条」而不是报错，属于最难发现的一类问题；
- **前端不要各页复制 `el-date-picker`**：`default-time`、 `value-format` 这类细节一旦分叉，表现是「选同一天查不到数据」——既不报错也难以复现。组件的默认时刻固定为开始 `00:00:00`、截止 `23:59:59`；
- **列表必须有「创建时间」列**（流水型页面原先只有开始 / 结束时间列的要补上）：筛选项与展示列对不上，用户无法核对筛选是否生效；
- 新增列表页时同步补齐这三处，并在模块文档的「接口清单」里注明支持该条件。

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
- 生成器不产出枚举：枚举由人工按 §22 放到 `com.lx.deploy.common.enums.{module}`，生成后不新建业务包内的 `enums` 目录；
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

Controller 自动生成（路径映射必须遵守 §12.2 的「动作在前、{id} 在最后」规范）：

```text
GET    /{module}/page
GET    /{module}/list
GET    /{module}/detail/{id}
POST   /{module}/create
PUT    /{module}/update/{id}
DELETE /{module}/delete/{id}
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
- 返回 `requestId`。

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

### 12.2 接口路径映射规范

所有接口路径必须显式书写动作，且路径变量 `{id}` 永远放在路径最后一段。禁止出现以下三种反模式：只写 `/{id}` 不写动作、映射注解不写路径、路径参数写在动作前面。

标准 CRUD 路径（与 §11.2 一致）：

- 查询详情：`GET /{module}/detail/{id}`（禁止 `GET /{module}/{id}`）；
- 新增：`POST /{module}/create`（禁止裸 `@PostMapping` 不写路径）；
- 修改：`PUT /{module}/update/{id}`（禁止 `PUT /{module}/{id}`）；
- 删除：`DELETE /{module}/delete/{id}`（禁止 `DELETE /{module}/{id}`）；
- 集合级操作（无 id）保持 `/page`、`/list`、`/pull` 等。

自定义动作与子资源（动作 / 子资源名一律写在 `{id}` 前面）：

- 自定义动作（取消、测试等）：`POST/PUT /{module}/{action}/{id}`，例如 `PUT /task/cancel/{id}`、`POST /server/test/{id}`（禁止 `/{module}/{id}/cancel` 这种「id 在动作前」的写法）；
- 子资源集合（如某构建的产物）：`GET /{module}/{sub}/{id}`，例如 `GET /build/artifacts/{id}`（禁止 `/build/{id}/artifacts`）。

反例（一律禁止）：

```java
@GetMapping("/{id}")          // 错误：只写参数不写动作
@PostMapping                    // 错误：映射注解不写路径
@PutMapping("/{id}")           // 错误：id 在动作前且无动作
@PutMapping("/{id}/cancel")    // 错误：id 在动作前
```

正例：

```java
@GetMapping("/detail/{id}")
@PostMapping("/create")
@PutMapping("/update/{id}")
@PutMapping("/cancel/{id}")
```

控制器方法上的 `@PathVariable String id` 无需改动，只要路径模板里 `{id}` 是最后一段即可。该规范同时约束手写 Controller 与 `controller.java.ftl` 代码生成模板；新增接口与该模板产出必须一致，否则视为不合格。

## 13. 注释规范

新增或生成的类必须有类级 JavaDoc，说明职责、边界和使用方式。**所有新建的 Java 类（含枚举、DTO、VO、Service、Controller）统一使用以下模板**：

```java
/**
 * 异步任务接口。
 *
 * <p>Controller 请求统一使用 DTO，响应统一使用 VO，不直接暴露数据库实体。</p>
 *
 * @author lx
 * @since 2026-09-03 02:01:00
 */
```

- 第一行用一句话说明类的职责，以「。」结尾，不写「这个类是用来……」「工具类」这类空话；
- 空一行后用 `<p>……</p>` 写明边界、约束或协作关系（例如 Controller 只用 DTO/VO、实体只在 Service/Mapper 内使用、状态机方向、软删除行为），不允许省略该段或用无信息量的句子填充；
- `@author` 统一 `lx`；`@since` 为类的创建时间，格式 `yyyy-MM-dd HH:mm:ss`；
- 代码生成器模板（`src/main/resources/templates/*.ftl`）已内置该结构，手工新建的类必须补齐，不得只写一行职责描述；
- 修改已有类的职责或边界时，同步更新类级 JavaDoc，不允许注释与实现脱节。

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

`build_config_json` / `runtime_config_json` / `health_config_json` 等配置字段的解析与生成属于「跨模块契约代码」，注释要求高于普通业务代码，必须逐行说明意图：

- 每读取一个配置键都要写明键名、业务含义、单位与缺失时的默认值（例如「取 `health_config_json.interval_seconds`，缺省 10 秒」）；
- 每个分支都要写明触发条件：键不存在、类型不符、值为空、取值范围越界、命令参数与运行时不匹配；
- 命令拼接、路径拼接、模板变量替换必须逐行说明拼接后的最终形态，避免「看起来对但少一个空格」的问题；
- 兼容旧键名或旧格式时必须写明兼容原因与淘汰条件，不允许留下无说明的隐式兼容分支。

### 13.1 模型层字段注释约定

实体（Entity）、DTO、VO 的字段不允许只保留代码生成器输出的一行式注释，每个字段必须补充说明：

- 业务含义和用途（为什么存在这个字段）；
- 取值约束：枚举字段注明对应枚举类（如"取值见 TaskStatus 枚举"）和合法取值，数值字段注明范围（如进度 0-100）；
- 维护方：由服务端维护、调用方传入无效的字段（主键、审计字段、状态流转字段、自动分配的序号等）必须显式标注；
- 类级 JavaDoc 必须说明该模型在整个业务流程中的角色和关键规则（如状态机方向、软删除行为）。

代码生成器的产物视为骨架，模型层精化是模块交付的必要步骤，不属于可选优化。

### 13.2 模块文档约定

每完成一个业务模块，必须同步编写对应的模块文档，与代码同批交付：

- 文档统一存放在 `docs/` 目录，**模块文档文件名统一使用中文命名**，命名规则为「Xxx模块.md」，例如 `docs/服务器模块.md`、`docs/项目模块.md`；禁止使用英文或拼音命名模块文档；
- 文档结构统一包含以下小节：
  1. 模块定位（回答该模块在整个平台中的角色）；
  2. 数据表结构；
  3. 枚举设计；
  4. 代码结构（包目录树）；
  5. 接口清单；
  6. 核心业务规则与代码逻辑（含关键代码片段和设计理由）；
  7. 与其他模块的关系；
  8. 已知边界与后续演进。
- 文档头部标注模块包名、对应数据表和当前状态（如"已完成并编译通过"）；
- 注释回答"这段代码为什么这么写"，文档回答"这个模块在平台里扮演什么角色"，两者互补不重复；
- 模块发生结构性变更（新增表、变更业务规则、接口变化）时，必须同步更新对应文档，保持文档与代码一致。

除模块文档外，维护一份项目总览文档 `docs/项目总览.md`，记录整体架构、模块进度（完成/进行中/未开始）和后续路线，每个模块交付后更新其进度状态。

另维护一份**疑问与解答文档 `docs/疑问与解答.md`**，记录开发过程中提出的疑问、当时的结论与代码依据，供日后追溯「当初为什么这么定」：

- 定位是**解释与佐证，不新增约束**；与本文冲突时一律以 `AGENTS.md` 为准；
- 产生新疑问时**必须在当轮追加条目**：按提问先后编号（`Q-编号`）、**追加在文末**，每条含「提问 / 结论 / 落点 / 遗留」四段，并在文首索引表补一行；
- 疑问的结论一旦形成正式约束，**必须同时落进 `AGENTS.md` 对应章节**，不能只留在解答文档里；
- 文末维护与其他文档的分工表，避免同一件事在两处各写一套口径。

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
- 日志格式必须包含 `requestId`；
- `logs/` 不提交 Git。

日志中禁止输出密码、私钥、Token、密文、完整环境变量或其他敏感信息。

### 14.1 requestId 约定

`RequestTraceFilter` 为每个请求生成或透传 `X-Request-Id`，并同时写入请求属性、响应头和 SLF4J MDC。`ApiResponse.requestId` 返回同一个值，日志格式通过 `%X{requestId}` 输出。

因此，前端只需要把错误响应中的 `requestId` 提供给运维人员，即可在 `logs/info.log` 或 `logs/error.log` 中检索同一次请求。调用方也可以主动传入 `X-Request-Id`，用于网关、AIops 和部署平台之间的链路关联。requestId 只用于排查和关联，不代表用户身份、权限或业务主键。

> 该约定成立的前提是**失败请求确实写了日志**——访问日志见 §14.2，异常日志口径见 §14.3。

### 14.2 访问日志

`RequestTraceFilter` 在每个请求结束时（`finally`）输出一行 INFO：

```text
2026-09-25 18:30:12.345 [http-nio-8080-exec-3] INFO  [a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6] c.l.d.c.web.RequestTraceFilter - POST /deploy/deployment/create -> 400，耗时 37 ms
```

- 内容固定为「HTTP 方法 + 请求路径 + 状态码 + 耗时 ms」，**requestId 由 MDC 自动带入日志格式，不手工拼接**；
- **只记请求路径，不记查询串**：查询串可能携带凭据或敏感业务参数；
- 状态码取 `response.getStatus()`，异常处理器通过 `@ResponseStatus` 写入的 400/500 会如实反映，因此「某个请求失败了」在日志里一眼可见；
- 该行写在 `finally` 中且**先于 `MDC.remove` 输出**，下游抛异常时同样有记录；
- **高频轮询接口降噪**：前端日志查看器每 1.5 秒拉一次 `/taskLog/pull`，一个几分钟的任务就是上百行访问日志，真正有价值的业务请求反被淹没。`light-deploy.access-log.silent-paths`（默认 `- /taskLog/pull`）维护静默路径**前缀**清单，命中时响应 `< 400` 降为 DEBUG、`>= 400` 仍记 WARN——**轮询成功没有阅读价值，但接口出错、任务不存在必须留痕，禁止把某个路径配成完全静默**；
- 静默清单写**业务路径**（不含上下文路径 `/deploy`），匹配前会剥掉 context-path，日志里仍输出完整 URI；前缀不能写得太短（配 `/task` 会把整个任务模块的接口一起静默掉）。新增轮询接口时只改配置、不改代码；
- 需要关闭整个访问日志时只调本类 logger 的级别，不要改动实现：
  ```yaml
  logging:
    level:
      com.lx.deploy.common.web.RequestTraceFilter: warn
  ```

### 14.3 异常日志口径

**原则：任何被「吃掉」的异常都必须至少留下一条日志。** 只把异常转成统一响应、或只把失败写进数据库而不写日志，会让故障在服务端完全不可见，`requestId` 的检索价值也随之落空（§14.1）。**禁止**恢复成「只 `return ApiResponse.failure(...)` 不写日志」或「只 `failTask(...)` 不写日志」的写法。

| 场景 | 落点 | 级别 | 堆栈 |
| --- | --- | --- | --- |
| 业务异常 `DeployException`（校验不通过、状态冲突、不可回滚等） | `GlobalExceptionHandler.handleBusiness` | WARN | **不打**——预期内的拒绝，堆栈只会淹没真正的故障 |
| 参数校验失败 | `GlobalExceptionHandler.handleValidation` | WARN | 不打 |
| 未预期异常 | `GlobalExceptionHandler.handleUnexpected` | ERROR | **打**——唯一能定位代码缺陷的地方 |
| 异步任务失败（业务性） | `TaskExecutor.executeTask` | WARN | 不打 |
| 异步任务失败（未预期） | `TaskExecutor.executeTask` | ERROR | 打 |

接口层的日志行必须带 `uri` 与业务码/原因；任务层必须带 `taskId` 与任务类型。判定标准是「只给一个 requestId 或 taskId，能否从日志定位到失败原因」。

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

鉴权中心与动态菜单的接入点、解耦要求和变更纪律见 §21。

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
docs/代码生成器使用说明.md
```

默认必须保持 `generator.overwrite=false`。只有在代码已经提交或备份且明确需要按新模板重生成时才可临时开启覆盖，生成后必须检查 Git diff。

## 20. 前端工程约定

前端工程目录为 `lighit-deploy-ui`（Vue3 + Vite + TypeScript + Element Plus + Pinia），基于通用后台管理模板开发。后续前端页面开发必须遵守本节约定。

### 20.1 菜单与路由结构

- 侧边栏菜单一律使用二级结构：**一级是分组目录（不挂页面），页面挂在二级菜单下**；
- 一级目录路由必须设置 `alwaysShow: true` 并配置 `meta.title` 和 `meta.icon`（模板对单子级菜单默认折叠为直接链接，`alwaysShow` 强制渲染为可展开目录）；
- 二级路由的 `meta.title` 才是实际页面名称，例如「服务器管理 → 服务器列表」「项目管理 → 项目列表 / 环境管理 / 部署目标」；
- 当前使用静态菜单（`constantRoutes` + mock `getInfo` 返回空 routers）；接入统一权限中心后由后端菜单 JSON 动态渲染，新增页面时需同步规划两级归属，并保证组件路径与菜单 JSON 的 `component` 约定一致（见 §21.3）。

### 20.2 弹窗表单样式

所有新增/编辑对话框（el-dialog）统一使用同一套布局骨架，不随页面各自定义。标准结构：

```vue
<el-dialog
  v-model="dialogVisible"
  :title="dialogTitle"
  width="800px"
  :close-on-click-modal="false"
  @close="resetForm"
>
  <!-- 内容区滚动容器：顶部与标题栏留间隔；左右 padding 抵消 el-row gutter 负边距并预留滚动条宽度 -->
  <div style="max-height: 60vh; overflow-y: auto; padding: 16px 18px 0 10px;">
    <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px" size="small">
      <!-- 一行两列：普通字段，el-col :span="12" -->
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="服务器名称" prop="serverName">
            <el-input v-model="formData.serverName" placeholder="请输入服务器名称" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="12">
          <el-form-item label="环境标签" prop="envTag">
            <el-select v-model="formData.envTag" clearable placeholder="请选择" style="width: 100%">
              <el-option label="开发" :value="1" />
            </el-select>
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 只读展示字段：disabled 输入框 + 枚举文本映射，不用可交互控件 -->
      <el-row :gutter="20">
        <el-col :span="12">
          <el-form-item label="任务状态" prop="status">
            <el-input :value="statusText" disabled style="width: 100%" />
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 最后一行：文本域，el-col :span="24" 占满整行 -->
      <el-row :gutter="20">
        <el-col :span="24">
          <el-form-item label="备注说明" prop="remark">
            <el-input v-model="formData.remark" type="textarea" :rows="4" />
          </el-form-item>
        </el-col>
      </el-row>
    </el-form>
  </div>
  <template #footer>
    <el-button size="small" @click="resetForm">取 消</el-button>
    <el-button type="primary" size="small" @click="handleSubmit">确 定</el-button>
  </template>
</el-dialog>
```

必须遵守的规则：

- 对话框宽度统一 `width="800px"`，统一 `:close-on-click-modal="false"`，不随页面各自定义；
- 表单 `label-width` 统一 `120px`、`size="small"`，保证输入框左右对齐、纵向成列；
- 表单外层必须包一层滚动容器：`style="max-height: 60vh; overflow-y: auto; padding: 16px 18px 0 10px;"`，字段超出时页脚按钮不被挤出可视区；
- 滚动容器的 `padding-top` 固定 `16px`，保证第一个字段与标题栏之间留出间隔，不能让首行字段紧贴分隔线；
- 滚动容器的左右 `padding` 必须能抵消 `el-row :gutter` 的负边距（gutter 20 对应左右各 `-10px`），并额外预留滚动条宽度：左侧 `10px`、右侧 `18px`。只给 `padding-right: 8px` 会让表单内容比容器宽，底部出现多余的横向滚动条；
- 字段一律用 `el-row :gutter="20"` + `el-col` 行式排布，普通字段 `:span="12"`（一行两列），文本域等长内容 `:span="24"`（独占一行）；不同行按业务分组配对，不把一个字段单独拆成半行；
- 所有输入控件宽度统一 `style="width: 100%"`，不使用固定像素宽度（如 `160px`），两列才对得齐；
- 控件排列顺序：普通输入框/数字框 → 下拉选择 → 单选/开关 → **文本域（textarea）一律放表单最底部**（如凭据、描述、JSON 配置）；
- 文本域之后的字段不允许再出现普通输入控件；
- 条件显示字段（`v-if`）紧跟其控制项的下一行，允许打破上述排序（例如"代码来源"选中 GIT 后才出现的两个 Git 字段）；
- 字段说明文案用 `<span class="form-tip">`，样式为块级显示，换行落在控件下方，不挤占同一列的控件宽度；
- **复杂配置字段（JSON 配置、命令模板、正则等）必须在 label 上加「?」图标 + `el-tooltip` 悬浮展示完整示例**，图标用 `QuestionFilled`，示例用 `#content` 插槽 + `<pre class="tooltip-json">` 保留换行。示例文本与输入框 `placeholder` 共用同一个 `<script setup>` 常量，禁止两处各写一份：

  ```vue
  <el-form-item prop="buildConfigJson">
    <template #label>
      <span>构建配置</span>
      <el-tooltip placement="top" effect="dark" :show-after="100">
        <template #content>
          <pre class="tooltip-json">{{ generateExample(BUILD_CONFIG_FIELDS, editorContext) }}</pre>
        </template>
        <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
      </el-tooltip>
    </template>
    <el-input v-model="formData.buildConfigJson" type="textarea" :rows="4" :placeholder="generateExample(BUILD_CONFIG_FIELDS, editorContext)" />
  </el-form-item>
  ```

- `.form-label-tip`（label 上的问号图标）与 `.tooltip-json`（提示内容块）定义在全局 `styles/index.scss`：tooltip 内容被 teleport 到 body，页面 scoped 样式对它无效，不允许写在页面 `<style scoped>` 里；
- **下拉面板高度有全局兜底，禁止局部放开**：`styles/index.scss` 对 `.el-select-dropdown__wrap` 统一 `max-height: min(274px, 45vh) !important`，超出的选项在面板内部滚动。下拉面板被 teleport 到 body 且随内容自由撑高——弹窗低处的下拉展开长列表时，面板会探出视口被屏幕截断（新建部署的「发布版本」「部署策略」下拉先后中招）。带 `!important` 是因为它必须压过一切撑高面板的规则，是展示完整性的最后兜底；
- **弹窗内表单底部字段（下方还有字段提示或紧贴页脚）的下拉必须显式处理「弹窗边界」**：popper 的翻转只对视口边界负责——视口内放得下面板就不翻转，它不知道弹窗的边界，于是向下展开会盖住 `form-tip` 与页脚按钮、底部戳出弹窗边缘（新建部署的「部署策略」下拉中招）。两种做法二选一，都必须显式写出：**① 向上展开**——`placement="top-start"`，代价是面板盖住上方刚填好的字段；**② 固定向下**——用 `:popper-options` 关掉 flip（`{ modifiers: [{ name: 'flip', enabled: false }] }`），并在弹窗底部预留整块面板的落位空间（`styles/index.scss` 的 `.deploy-create-dialog` 为 3 个两行选项预留 `112px`），代价是下拉关闭时这段是空档。选 ② 时**必须**同时按「输入框底边 → 视口底部」给面板限高（同文件的 `.strategy-option-popper .el-select-dropdown__wrap`）：关掉 flip 后 popper 不再自己兜底，矮窗口里面板会被屏幕底部截断。新建部署的「部署策略」下拉用的就是 ②（`deploy/index.vue` 的 `STRATEGY_POPPER_OPTIONS`）；
- **下拉选项内容超过一行（标题 + 描述）时必须放开 Element Plus 的固定行高**：`.el-select-dropdown__item` 默认 `height: 34px; line-height: 34px; overflow: hidden`，那是按单行文本设计的；两行内容（14px 标题 + 12px 描述约 39px）装不进 34px 的盒子，描述行会被下边界裁掉、并紧贴下一项标题（新建部署的「发布版本」「部署策略」两个下拉先后中招）。做法：给下拉加 `popper-class`（如 `strategy-option-popper`），在 `styles/index.scss` 里按该类名把选项改成 `height: auto; min-height: 34px; line-height: 1.5; padding: 6px 32px 6px 20px`。**禁止**直接改全局 `.el-select-dropdown__item`，那会把全站单行选项一并撑高；
- **语义容易误解的业务字段（部署策略、批次大小、发布顺序等）同样要在控件或 label 上加悬浮说明**，文案**集中放在 `src/constants/` 下的共享常量**（如 `deploymentStatus.ts` 的 `DEPLOY_STRATEGY_TIPS` / `BATCH_SIZE_TIPS` / `DEPLOY_ORDER_TIPS`），表格列、表单控件、新建对话框三处引用同一份。禁止每个页面各写一套说法——分叉后的表现是「同一个策略在两个页面解释不一样」，很难被发现。多行提示内容用 `.tooltip-lines`（全局样式，同样因为 teleport 不能写 scoped）；
- 悬浮提示只承担「这个字段填什么、传什么示例」，字段约束（必填、范围、范围来源）仍由 `form-tip` 文案与校验规则表达，两边不重复同一段话；
- 只读展示字段用 `disabled` 输入框，枚举值先经文本映射（如 `statusText`）再展示，不用可交互控件承担只读展示；
- 页脚统一使用 `#footer` 插槽，"取 消 / 确 定" 两个按钮统一 `size="small"`，确定按钮 `type="primary"`；
- 表单校验规则必须与后端 Service 校验规则镜像（必填、正则、数值下限），前端先拦截，后端为准。

### 20.3 接口对接约定

- 请求统一走 `src/utils/request.ts` 的 axios 实例，`code === 200` 为成功，业务码非 200 由 request 层统一 ElMessage 提示，页面不重复弹错误提示；
- 后端错误响应中的 `requestId` 需要在提示或控制台中可追溯，用于关联后端日志；
- 新模块接口封装放在 `src/api/{module}.ts`，类型定义统一维护在 `src/api/deployTypes.ts`，与后端 DTO/VO 字段一一对应；
- 开发环境通过 vite proxy 将 `/api` 代理到 `http://localhost:2027/deploy`，前端代码不出现后端主机地址。

### 20.4 验证要求

修改前端代码后至少执行：

```bash
npm run typecheck
```

交付前执行生产构建：

```bash
npm run build
```

类型错误必须清零，不允许带着 `vue-tsc` 报错提交。

### 20.5 搜索表单约定

所有列表页的搜索区域统一遵守：

- 文本输入框必须绑定回车键查询：`@keyup.enter="handleSearch"`，回车与查询按钮行为完全一致；
- 触发查询时必须把页码重置为第一页，避免停留在超出结果范围的页码上；
- 重置按钮清空全部搜索条件并回到第一页重新查询；
- 下拉选择、日期等控件不强制绑定回车，选择后由用户点击查询按钮触发。

### 20.6 前端注释规范

前端方法与参数必须有注释，标准与后端 JavaDoc 对齐：

- `<script setup>` 顶部必须有文件级注释块，说明页面对接的后端接口前缀和关键业务约定；
- 每个方法必须有注释说明用途和关键业务规则；**带参数的方法必须使用 `@param` 逐个说明参数含义与取值范围**，返回 Promise 的说明返回的数据结构；
- API 模块（src/api/*.ts）的每个函数必须注释对应的后端接口和特殊行为（如凭据只写、软删除等）；
- 常量映射对象必须注释与后端哪个枚举对应，保证两侧枚举变更时可以同步排查；
- 不允许出现"获取数据""处理点击"这类没有信息量的注释。

### 20.7 标签与状态颜色约定

同一个后端枚举在所有页面必须显示成同一种标签颜色，禁止各页面自行定义。

**环境标签（对应后端 `EnvironmentTag` 枚举）为已确认口径，固定如下：**

```text
1 开发环境  → primary
2 测试环境  → info
3 生产环境  → success
```

- 环境标签的文案与颜色唯一维护在 `lighit-deploy-ui/src/constants/tagStyle.ts` 的 `ENV_TAG_MAP`，页面通过 `getEnvTagStyle(envTag)` 取值；
- 任何页面需要展示环境标签时必须复用该映射，**不允许在页面内重新定义颜色或文案**；后端枚举增删值时只改这一处，页面代码不动。

**通用标签颜色口径（其他枚举按此对齐）：**

```text
状态类：启用 / 正常 / 成功  → success
状态类：停用 / 未知 / 兜底值 → info
状态类：进行中 / 主流程动作  → primary
状态类：警告 / 已取消        → warning
状态类：失败 / 危险 / 主目标互斥 → danger
```

- 新增枚举展示映射时，先确认是否已有共享映射可复用；同一枚举只允许定义一次；
- 状态类枚举必须显式指定 `type`，未匹配到值时用 `info` 兜底（如 `STATUS_TAG_MAP[row.status] || 'info'`）；
- 标识类标签（环境编码、运行时名称等纯文本标签）不参与状态配色，统一不加 `type`，使用 Element Plus 默认样式；
- 类型类标签（认证类型、任务类型）按业务语义固定取 `primary` / `warning` / `info` 中的一个，同一枚举在所有页面必须同色；
- 页面模板中不允许出现硬编码的颜色字面量（如 `style="color: #F56C6C"`），颜色一律走 `el-tag` 的 `type`；
- 标签颜色口径变更时，先改 `src/constants/tagStyle.ts` 与本节，再改页面。

### 20.8 列表页与搜索表单统一骨架

所有列表页（`lighit-deploy-ui/src/views/*/index.vue`）统一以 `views/server/index.vue` 为基准骨架，不允许各页面自行定义搜索区、工具栏、表格与分页的写法。

**搜索区：**

- 外层 `<div class="search-div">`，内部 `<el-form :model="searchForm" inline>`，不写 `@submit.prevent`；
- 文本输入框宽度统一 `style="width: 180px"`，下拉 / 日期控件统一 `style="width: 160px"`；
- 所有文本输入框必须绑定 `@keyup.enter="handleSearch"` 并带 `clearable`（见 §20.5）；
- 末尾按钮固定为 `<el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>` + `<el-button :icon="Refresh" @click="handleReset">重置</el-button>`。

**工具栏：**

- 外层 `<div class="tools-div">`，新增按钮为 `type="primary"` + `:icon="Plus"`，文案为「新增Xxx」。

**表格：**

```vue
<el-table v-loading="loading" :data="tableData" border stripe>
  <el-table-column prop="name" label="名称" min-width="140" show-overflow-tooltip align="center" />
  <el-table-column prop="status" label="状态" min-width="130" align="center" />
  <el-table-column prop="createTime" label="创建时间" min-width="200" align="center" />
  <el-table-column prop="description" label="描述" min-width="150" align="center" show-overflow-tooltip />
  <el-table-column label="操作" min-width="260" fixed="right" align="center">
    <template #default="{ row }">
      <el-button link type="primary" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
      <el-button link type="danger" :icon="Delete" @click="handleDelete(row)">删除</el-button>
    </template>
  </el-table-column>
</el-table>
```

- 表格统一 `v-loading="loading" :data="tableData" border stripe`；**每一列都必须显式写 `align="center"`**，不允许依赖默认左对齐；
- **所有列一律使用 `min-width`，禁止使用固定 `width`**：表格由 Element Plus 按容器宽度和各列 `min-width` 比例分配剩余空间，写死 `width` 会让窄屏被挤压、宽屏留下右侧空白；`min-width` 只作为该列的下限值；
- 定长字段（编码、状态、类型、端口、顺序、标签列）给一个能放下表头与最短取值的下限（常用 `min-width="90"` ~ `min-width="130"`）；名称、地址、描述等可变长文本用更大的 `min-width` 并配 `show-overflow-tooltip`；
- 可变长文本列（名称、地址、描述、失败原因等）必须加 `show-overflow-tooltip`，超长内容以省略号显示并悬浮展示全文；
- 「创建时间」列 `min-width="200"`，位置排在所有业务列之后；
- 表中存在描述字段时，描述列固定放在创建时间列之前，`min-width="150"` + `show-overflow-tooltip`；
- 操作列固定 `min-width="260" fixed="right" align="center"`（`min-width` 与 `fixed` 可以同时使用）；操作按钮统一 `el-button link`，「编辑」为 `type="primary"` + `:icon="Edit"`，「删除」为 `type="danger"` + `:icon="Delete"`。

**分页：**

```vue
<el-pagination
  v-model:current-page="pagination.pageNum"
  v-model:page-size="pagination.pageSize"
  :total="pagination.total"
  :page-sizes="[10, 20, 50, 100]"
  layout="total, sizes, prev, pager, next, jumper"
  style="margin-top:16px;justify-content:flex-end"
  @size-change="fetchData"
  @current-change="fetchData"
/>
```

- 分页状态固定使用 `pageNum` / `pageSize` / `total` 三个字段，`page-sizes` 固定 `[10, 20, 50, 100]`（对齐后端每页上限 100，见 §10）；
- 新增列表页时直接复制 `views/server/index.vue` 的骨架，再替换字段、枚举映射与接口调用，禁止重新设计布局。

### 20.9 实时日志查看约定（抽屉 + 增量拉取）

任务执行过程、连通性测试等实时日志统一用「右侧抽屉 `el-drawer` + 终端风格控制台」承载：

- 抽屉宽度统一 `800px`，与弹窗规格对齐；控制台样式统一使用全局类 `.log-console` / `.log-line`（定义在 `styles/index.scss` 的「任务执行日志控制台」小节），页面**禁止**再写一份 scoped 样式；
- 日志区需要自动滚动到最新一行（`nextTick` 后设置 `scrollTop = scrollHeight`）；
- 必须展示任务的「状态 + 进度 + 当前阶段 + 失败原因」，失败原因用 `el-alert` 单独提示，不能只埋在日志里；

日志刷新**禁止**用分页接口轮询（`/taskLog/page`）：日志在持续追加，分页轮询必然出现重复、漏读与错序。统一使用增量拉取：

```text
GET /taskLog/pull?taskId={taskId}&lastSeq={lastSeq}&limit=200
```

- 首次 `lastSeq=0`，此后每次把响应里的 `lastSeq` 作为下一次的锚点；日志只按返回顺序追加，**不做排序、不去重**；
- 停止条件必须写成「`finished === true` **且** 本批返回条数 `< limit`」：本批正好满一页说明还有剩余日志，必须再拉一次把尾部取完，否则最后一屏会丢；
- 轮询间隔 1.5 秒；上一轮请求未返回时不得重复发起（用请求中标记挡住）；
- 抽屉关闭（`@closed`）与组件卸载（`onUnmounted`）都必须清理定时器；
- 任务已是终态时不得启动轮询（秒级失败的任务在首次拉取时可能就已经结束）；
- 状态与日志必须取自**同一次** pull 响应，不要为了拿最新状态再单独查一次任务详情，否则页面会出现「状态已成功、日志还停在执行中」这类不一致；
- 增量锚点必须用 `sequence_no`（任务内递增），**禁止**用日志主键 `id`：主键是 32 位 UUID，不具备单调递增性，`id > lastId` 没有意义；
- 后续若改为 SSE 推送，必须保持本接口响应字段语义不变（`status` / `statusText` / `finished` / `progress` / `currentStage` / `errorMessage` / `logs` / `lastSeq`），以便前端平滑切换；
- **查看器逻辑只有一份实现**：轮询、增量锚点、自动滚动与下载统一收在 `src/composables/useTaskLogViewer.ts`，页面只渲染控制台与外层容器。当前有 5 处使用（任务列表、构建列表、发布列表、服务器连通性测试、项目触发构建），**禁止**再复制一份轮询代码到新页面；
- 日志时间格式化同样只有一份（同文件导出的 `formatLogTime`），页面不再各写一个 `formatXxxLogTime`；
- 列表页需要在任务结束时刷新表格时，用查看器的 `onFinished` 回调，不要自行监听状态。

**容器不拦截关闭——任何状态下都允许关闭**：任务状态为 0（排队中）或 1（执行中）时同样可以关，查看器**不提供**关闭拦截函数，5 处视图都**禁止**出现 `:before-close`、`:show-close="!running"`、`:close-on-press-escape="!running"`。

- **理由**：日志轮询跑在浏览器里，任务跑在服务端执行器里，两者互不依赖。关掉容器只是停掉前端这段轮询，**任务该跑多久还是跑多久、日志一条不少**，重新打开会从服务端重新拉全量。锁住关闭入口换不来任何安全性，只会把用户困在弹窗里干等任务结束；
- 页脚在任务执行中显示「任务执行中，关闭窗口不影响执行」——把「能关且不影响」讲明白，而不是让用户去猜自己会不会打断发布；
- **关闭路径必须继续停轮询**：`@closed`（`el-drawer`）或 `@close`（`el-dialog`）一律绑查看器的 `stop`。不拦关闭之后这条更容易被忽略，而漏掉它就是「窗口关了接口还在打」；
- `running` 保留但**只用于页脚文案**，不再参与任何可关闭性判定。

**日志下载**：统一走后端导出接口 `GET /taskLog/export/{taskId}`（`text/plain` 附件，含任务头部摘要与全量日志），前端不自己拼文本：

- `responseType: 'blob'`，`request.ts` 对 blob 响应不做业务码校验，因此**调用方必须自己判断响应类型**——后端异常时返回的是 JSON 错误体，当成附件存下来会得到一个内容是报错信息的「日志文件」；
- 文件名以响应头 `Content-Disposition` 为准（服务端按任务编号生成，中文走 RFC 5987 的 `filename*`），前端不另拼一套命名规则，解析失败时才用任务编号兜底。

任务列表页早期用的是 `/taskLog/list` 一次性拉取 + 手动「刷新」按钮，已统一到增量拉取：日志在持续追加，一次性快照既没有刷新时机也无法表达进度，手动刷新按钮在 1.5 秒轮询下是死 UI。

### 20.10 列表页缓存与刷新策略

页面由 `AppMain` 的 `<keep-alive :include="cachedViews">` 缓存（路由 `meta.noCache` 为 true 的页面除外），**切走再切回不重跑 setup**，所以每个列表页都必须显式决定「回到本页要不要重新拉数据」，二选一：

- **数据由服务端或平台自己产生**（任务列表、构建列表、部署记录）→ 在 `onActivated` 里无条件重新拉取。任务尤其典型：构建一次、发布一次各建一条任务，页面不刷新就会出现「平台明明建了任务，任务管理里却没有记录」的假象；
- **数据只被本站自己的编辑页改动**（项目列表）→ 走 `store/pageRefresh.ts`：编辑页保存成功后 `markDirty('<list-key>')`，列表页在 `onActivated` 里 `consumeDirty` 命中才刷新，普通切标签不产生多余请求。

两条硬性细节：

- 首次挂载后会紧接着触发一次 `onActivated`，此刻 setup / `onMounted` 已经拉过一次数据，必须用「只跳过一次」的标记避免重复请求（`ProjectList` 因为 `consumeDirty` 天然规避）；
- 提交后用 `tagsViewStore.delView` 关闭编辑页标签、并打刷新标记，不能指望用户手动刷新（见 §20.1）。

## 21. 鉴权中心与动态菜单接入约定

用户、角色、权限码和菜单的权威数据都在统一权限中心，本项目不自建 RBAC。因此所有涉及登录、Token、用户信息、菜单、按钮权限的代码都必须按"实现可整体替换"的方式编写，不允许把过渡期实现扩散到业务模块。

### 21.1 总体原则

- 本项目只保留外部用户 ID 和操作者信息，不复制权限模型、不在本地维护角色与权限码；
- 当前 `user` 表与前端 `src/api/user.ts` 的假登录是**过渡实现**，只允许出现在鉴权/登录相关代码中，业务模块不得引用；
- 接入权限中心时只允许替换三处实现：**后端鉴权过滤器**、**前端 `src/api/user.ts`**、**菜单数据来源**；
- 如果接入权限中心需要修改业务模块代码，说明抽象没做对，应先补抽象再接入，不允许直接改业务逻辑迁就鉴权实现。

### 21.2 后端接入点

- 鉴权集中在过滤层：统一鉴权过滤器与 `RequestTraceFilter` 同级放在 `common/web`；Controller 与 Service 不写鉴权判断、不解析 Token、不读请求头里的身份信息；
- 当前用户上下文统一由 `CurrentUserContext`（ThreadLocal，请求结束时必须清理）提供，业务代码只取外部用户 ID 与展示名，不读权限明细；
- 权限判定用声明式：需要权限的接口通过注解或配置声明所需权限码，禁止在 Controller 里写角色名、用户名的 `if` 分支；
- 服务间调用（AIops 回调、权限中心同步等）使用独立的服务间凭据，不复用用户 Token 通道；
- `RequestTraceFilter` 与身份解耦：requestId 只用于链路关联，不能作为身份或权限判断依据；
- 本地联调需要跳过鉴权时用配置开关（如 `light-deploy.auth.enabled`）控制过滤器装配，禁止在业务代码里写"本地环境跳过鉴权"的分支；
- 业务模块不得依赖 `user` 表实体，接入权限中心后该表可直接下线。

### 21.3 前端接入点

- 登录、获取用户信息、退出登录只允许经 `src/api/user.ts` 调用；页面与组件不得直接调 `src/utils/request.ts` 获取身份数据；
- Token 读写只允许走 `src/utils/auth.ts`（`getToken` / `setToken` / `removeToken`）；`request.ts` 只负责附加 `Authorization` 头，其它模块不得直接读写 Cookie；
- 菜单来源与渲染解耦：`constantRoutes` 只保留登录页、404 等基础路由；新增业务页面必须同时满足两点 —— 能在 `constantRoutes` 中静态访问，且组件路径与菜单 JSON 的 `component` 字段一致（相对 `src/views/`、不带 `.vue`，如 `server/index`），保证切到动态菜单时不需要改页面代码；
- 菜单 JSON 转路由只允许在 `src/permission.ts` 的 `filterAsyncRouter` 中实现，页面组件不感知自己是被静态注册还是动态注册；
- 菜单 JSON 字段结构以 `src/types/api.d.ts` 的 `RouteRecordItem` 为准（`path` / `component` / `name` / `meta{title,icon}` / `children` / `alwaysShow` / `hidden` / `redirect`）；权限中心字段变化时必须同步修改该类型与 `filterAsyncRouter`，两侧保持一致；
- 按钮级权限只允许通过统一指令或 `userStore.buttons` 判断，禁止在模板里硬编码用户名、角色名；
- 新增页面的两级菜单归属必须与权限中心一致（一级目录不挂页面、页面挂二级，见 §20.1）；
- 前端不得出现权限中心的主机地址、密钥或环境差异，环境差异只能通过环境变量表达。

### 21.4 变更要求

- 涉及登录、Token、用户信息、菜单、按钮权限的改动，必须在提交说明里写明对上述接入点的影响；
- 新增或调整页面时必须核对 `component` 路径与菜单 JSON 的约定，避免接入动态菜单时才暴露路径不一致；
- 代码生成器只产出 `api/{module}.ts` 与 `views/{entityPath}/index.vue`，不生成路由与菜单：生成页面后必须手工补 `constantRoutes` 归属，并记录该页面对应的菜单 `component` 路径；
- 新增需要权限控制的操作时，先确认权限码由权限中心下发，不在前端或后端硬编码权限清单。

## 22. 枚举与常量约定

### 22.1 存放位置

所有业务枚举统一放在 `com.lx.deploy.common.enums` 下，按模块划分二级子包，不再散落在各业务包中：

```text
com.lx.deploy.common.enums
├── server/    ServerAuthType、EnvironmentTag
├── project/   RuntimeType、SourceType、BuildMode、DeployStrategy
└── task/      TaskType、TaskLogLevel

com.lx.deploy.common.constants
├── EnableStatus   0 停用 / 1 启用（server、registry、project、project_environment_target 共用）
└── TaskStatus     0 排队中 / 1 执行中 / 2 成功 / 3 失败 / 4 已取消（lds_task）
```

- 业务包（`server` / `project` / `task` / 后续模块）**不再保留各自的 `enums` 子包**，新增枚举一律加到 `common.enums.{module}`；
- 后续模块同理新建子包（如 `build`、`release`、`registry`、`aiops`），子包名与业务包名保持一致，便于按模块检索；
- 枚举不依赖任何业务实体或 Service，只允许依赖 JDK 与 Lombok，保证 `common` 层可被任意模块引用。

**状态 / 结果类字段不进 `common.enums`**：这类字段一律用数字编码 + `common.constants` 常量类表达，规则见 §4.5 与 §22.4。

### 22.2 枚举编写规范

- 枚举类必须有类级 JavaDoc，并按 §13 模板标注 `@author` / `@since`；每个常量必须有注释说明业务含义；
- 枚举常量名即数据库与接口使用的字符串编码（如 `PROJECT_DOCKERFILE`），禁止额外维护数字编码到字符串的映射表；
- 每个枚举提供 `fromCode(String code)` 静态解析方法：编码为空或不受支持时返回 `null`，由 Service 统一转成 `ValidationError`，不允许在枚举内抛业务异常；
- 需要面向页面的中文说明时在枚举内提供 `message` 字段，页面文案与枚举保持一致；
- 枚举字段的实体 / DTO / VO 注释必须写明「取值见 XxxEnum 枚举」及合法取值。

### 22.3 枚举变更的同步范围

新增或修改枚举取值时，必须在同一次提交内同步以下位置，缺一不可：

1. 数据库 `schema.sql` 中对应字段的 `COMMENT`；
2. 模块文档（`docs/Xxx模块.md`）的「枚举设计」小节；
3. 前端展示映射：`src/api/deployTypes.ts` 的类型注释，以及页面内的 `*_MAP` / `*_TAG` 映射（标签颜色见 §20.7）；
4. 受影响的 Service 校验分支与错误提示文案（错误提示必须列出全部合法取值）。

不允许出现「后端枚举已加、前端/文档未同步」的中间状态。

### 22.4 状态字段用常量类，不用枚举

- 「状态 / 结果」类字段禁止建枚举，统一在 `com.lx.deploy.common.constants` 下新建常量类，类名形如 `XxxStatus`；
- 常量类结构固定：`public final class` + 私有构造 + `public static final int` 常量 + `isValid(Integer)` + `describe(Integer)`；
- 常量类必须按 §13 模板写类级 JavaDoc，并注明「对应数据库字段」（如 `project.status`、`project_environment_target.status`）；
- Service 中出现状态字面量（如 `status == 1`）必须替换成常量引用；涉及状态集合判断时（如「是否为终态」）在常量类里提供静态方法（如 `TaskStatus.isFinal`），不允许把状态组合散落在 Service 分支里；
- 模块文档的「枚举设计」小节必须同时登记该模块用到的常量类与每个数字的含义（§13.2）。

## 23. 项目建模与多模块仓库约定

### 23.1 一个 project = 一个可部署的服务模块

`project` 表登记的是**一个可独立构建、独立发布、独立回滚的服务模块（发布单元）**，不是「一个 Git 仓库」，也不是「一个代码仓库对应一条记录」。

- 同一个 `git_url` + `git_branch` 允许创建多条 project 记录，每条对应仓库中的一个子模块；
- 典型场景：`auth-system-Iteration` 父工程下有 `auth-system` / `auth-rest` / `auth-tool` 三个可独立部署的服务，则建三条 project，`git_url` 相同，靠 `build_config_json.module_name` 区分；
- 不要求为此拆分 Git 仓库，也不要求给 project 表增加子模块字段或新建子模块表：仓库与子模块的对应关系只通过 `build_config_json` 表达；
- 同一子模块在不同环境的差异（目标服务器、批次、运行参数）走 `environment` + `project_environment_target`，不允许通过复制 project 表达；
- 模块列表页与表单必须允许「同一 Git 地址 + 分支」的重复录入，唯一性只由 `code` 保证，不得对 `git_url` 加唯一约束。

### 23.2 三段 JSON 配置字段的职责边界

| 字段 | 消费方 | 回答的问题 |
| --- | --- | --- |
| `build_config_json` | 构建模块 | 仓库拉下来之后，定位哪个子模块、用什么命令、产出什么产物、打成什么镜像 |
| `runtime_config_json` | 发布模块 | 产物在目标服务器上怎么跑起来：部署目录、端口、环境变量、启动命令、JVM 参数、Nginx 站点根 |
| `health_config_json` | 健康检查 | 发布后怎么判定服务可用：检查方式、端口、路径、间隔、超时、重试、观察期 |

- 三段 JSON 的键名 schema、默认值与完整示例统一维护在 `docs/项目模块.md` §2.3，修改 schema 必须同步更新该文档；
- 当前 Service 只做 JSON 合法性校验（Jackson `readTree()`），不做语义解析；语义校验、默认值填充与兼容处理由消费方模块实现，并遵守 §13 关于配置契约代码的逐行注释要求；
- JSON 字段的键名统一 `snake_case`，与数据库列命名风格一致，禁止同一份配置里混用驼峰。

### 23.3 多模块仓库的构建解析约定

1. **仓库只拉一次**：同一 `git_url` + 分支的不同子模块共享一次 `git clone` 与工作区缓存，产物目录按 `project.code` + 版本号隔离；
2. **按模块构建，`module_dir` 与 `-pl` 只能二选一**：执行器先 `cd` 到 `module_dir`（未填则 `module_name`，都没填则仓库根），再执行 `build_command`。因此填了 `module_dir` 时命令里**禁止**再写 `-pl {module_name}`（Maven 从子模块 pom 起构建，reactor 里找不到该模块，必报 `Could not find the selected project in the reactor`）；要改用 `-pl` 就从仓库根构建并清空 `module_dir`。前端表单已按此规则拦截提交；
3. **产物定位**：按 `artifact_path`（支持 `*` 通配）取产物，一个 project 只产出一个发布产物；
4. **依赖顺序**：模块间依赖由 Maven / Gradle 依赖图保证，构建模块负责编排顺序或先 `install` 被依赖模块，project 表不表达模块依赖关系；
5. **禁止**在 project 表新增「子模块列表」字段或子模块表，也禁止把同一子模块拆成多条 project 来绕过构建逻辑（违反 §18 不混用两套数据模型）；
6. **构建命令的执行环境由平台保证**：命令执行前固定 `. /etc/profile`（引入登录态 PATH，输出丢弃）并执行构建机档案的 `build_env_script`，随后预检命令首词是否可执行（`command -v` / `test -x`，识别并跳过 `KEY=VALUE` 前缀），不可执行直接判失败并报出原因与三种处理办法。**禁止**在代码里硬编码 mvn/java/docker 的绝对路径，「机器上工具装在哪」属于服务器档案的职责（§6.4）。

### 23.4 镜像仓库只服务于 DOCKER 运行时

平台的构建方式分两类，**是否经过镜像仓库是这两类最本质的区别**：

| 类别 | BuildMode 取值 | 产物 | 是否经过 registry |
| --- | --- | --- | --- |
| Docker 镜像类 | `PROJECT_DOCKERFILE` / `LAYERED_DOCKER` / `JAR_PREBUILD` | 容器镜像（含 base 镜像；JAR_PREBUILD 先打 jar 再包） | **是**，构建后推仓库、发布时拉仓库 |
| 脚本 / 产物类 | `SCRIPT_DEPLOY` / `ARTIFACT_ONLY` | JAR、静态产物、部署脚本 | **否**，产物直接送达目标机，没有镜像 tag / digest 概念 |

- `registry` 与 `registry.credential_id` 只对 `project.runtime_type = DOCKER` 有意义：绑定镜像仓库的表单、校验和构建分支都必须落在 DOCKER 这一支，**禁止**要求脚本部署（JAR / STATIC 运行时 + `SCRIPT_DEPLOY`）的项目配置镜像仓库；
- 项目与仓库的绑定通过 `project.registry_id` 表达（可空，非 DOCKER 运行时必须为空），前端入口只在运行时为 Docker 时出现；
  Service 校验仓库存在且启用，切换运行时会清空该绑定（字段策略见 §10.1），且仓库被绑定时不允许删除；
- `build_artifact.artifact_type` 的 `BASE_IMAGE` / `APP_IMAGE` 属于镜像类产物（`artifact_tag` / `artifact_digest` 只对它们有意义），`JAR` / `STATIC` 属于文件类产物，不要给文件类产物编造 tag；
- 发布模块同理分两条链路：DOCKER 走「拉镜像 + compose/run」，脚本部署走「上传产物 + 执行脚本」，两条链路的回滚与健康检查方式不同，不要强行合并成一套；
- **组合校验已落地（2026-09-23）**，允许矩阵如下，规则的唯一来源是 `RuntimeType` 枚举常量上声明的 `supportedBuildModes` 与 `supports(BuildMode)`：

| 运行时 | 允许的构建模式 | 理由 |
| --- | --- | --- |
| `DOCKER` | `PROJECT_DOCKERFILE` / `LAYERED_DOCKER` / `JAR_PREBUILD` | 产物是镜像，必须经过 registry（JAR_PREBUILD 虽先打 jar，终态仍是镜像） |
| `JAR` | `SCRIPT_DEPLOY` / `ARTIFACT_ONLY` | 产物是 JAR 文件，脚本产出或直接发布 |
| `STATIC` | `SCRIPT_DEPLOY` / `ARTIFACT_ONLY` | 产物是静态目录（dist） |

  - 后端在 `ProjectService.validateBusinessRule` 校验，拒绝时返回 VALIDATION_ERROR(4001) 并列出该运行时的可选模式；
  - 前端在项目表单用同一份矩阵做下拉联动（`BUILD_MODE_OPTIONS[].runtimes`），运行时切换时自动校正非法组合，并清空镜像仓库绑定；
  - **新增构建模式时必须同时改两处**（`RuntimeType` 枚举 + 前端 `BUILD_MODE_OPTIONS`），禁止在任何其它地方再写一份组合判断；
  - 历史数据若存在非法组合（例如早期建的 `STATIC` + `LAYERED_DOCKER`），不会被自动修正，但再次编辑保存时会被拦下；
- 查询/统计构建相关数据时，脚本部署类的“构建”本质是「产出产物」，不要用镜像仓库维度的指标（推送次数、镜像大小）去统计它。

### 23.5 发布版本号的生成规则

构建成功时由 `BuildService.resolveReleaseVersion` 生成发布版本号，格式固定为 `{项目编码}-v{版本序号}-{提交短码}`，例如 `auth-7888-v3-887b603c`；无提交（仅重发已有产物的场景）时提交部分退化为 `yyyyMMddHHmmss` 时间戳。

**这个字符串被四处共用**，因此必须在克隆完成、拿到 commit 后**一次性定稿并全程传递**（`BuildTaskHandler.execute` 里算一次，往下传给镜像构建、产物转存与版本记录组装）：

| 落点 | 具体形式 |
| --- | --- |
| Docker 镜像 tag | `imageRepo:tag`；`LAYERED_DOCKER` 另有基础镜像 `base-{tag}`；`JAR_PREBUILD` 同样使用 `imageRepo:tag`（无基础镜像） |
| 构建机产物持久目录 | `{build_artifact_dir}/{项目编码}/{版本号}` |
| 产物目录下的 `VERSION` 标识文件 | `{build_artifact_dir}/{项目编码}/{版本号}/VERSION`，含 `version=` 行（见 §24.7） |
| `release_record.version` | 版本记录本体的版本号 |

**为什么必须有序号**：改造前版本号只有「项目编码 + 提交短码」，同一个 commit 重复构建时三处全部同名——镜像被第二次 push 覆盖、产物持久目录被 `cp -r` 覆盖，并在 `release_record` 上撞唯一键 `uk_release_project_version (project_id, version, is_deleted)`。而 `completeSuccess` **没有事务**，顺序是「置 build 成功 → 插入产物 → 插入版本」，撞键炸在最后一步，结果是**构建记录显示成功、任务显示失败、版本列表里没有它**，该产物永远发不出去。序号让每次构建的产物彼此独立。

因此必须遵守：

1. **取号规则**：默认取项目内最大序号 +1。`ReleaseRecordMapper.selectMaxVersionSeq` 刻意**不过滤 `is_deleted`**，避免软删除后序号被重用（否则版本库里会出现两个 `v3`，排序与追溯失效）；
2. **允许人工指定**：`build.version_seq` 承接调用方指定的序号，且**必须在创建构建时校验项目内未被占用**，不许拖到构建跑完才在版本落库阶段失败；
3. **禁止中途重新生成版本号**：镜像 tag 与产物目录已按定稿值落地，重新生成会让版本记录指向不存在的产物（`assembleRelease` 只消费传入值，不得自行调用取号）；
4. **禁止改写历史版本号**：`deployment_target` 的 `old_version` / `new_version` 与历史 `image_tag` 存的都是旧值，改名会让历史部署与镜像对不上。升级前产生的版本保持 `项目编码-提交短码` 原样，版本列表中新旧格式并存属于预期；
5. 列定义与升级脚本：`release_record.version_seq`、`build.version_seq`、索引 `idx_release_project_seq` 见 `schema.sql`，升级走 `db/upgrade-release-version-seq.sql`。

### 23.6 发布版本保留策略（`retain_release_count`）

`project.retain_release_count` 由**构建链路**在构建成功后消费（`ReleaseRetentionService` + `BuildTaskHandler.applyRetentionPolicy`），不是发布链路。必须遵守：

1. **顺序固定为「先删磁盘产物，再置记录废弃」**。反过来会让记录已不可见、磁盘却还占着，运维只能上机手工找目录；
2. **按版本自己的 `build_server_id` 删**，不按项目当前绑定的构建机。产物是磁盘文件，只在当初那台机器上存在；按项目当前构建机去找会让所有历史版本找不到包。历史版本可能产自另一台构建机，必要时额外开 SSH；
3. **保护窗口**：最近 20 次部署引用过的版本一律不回收。回滚按 `old_version` 重新发布，把某台机器正跑着的版本清掉会让回滚与新发同时断掉——宁可少删，不可错删；
4. **路径白名单**：待删目录只能由「构建机产物持久目录 / 项目编码 / 版本号」三段拼成，版本号须匹配 `[A-Za-z0-9._-]`。`rm -rf` 的目标即使来自库记录也要校验，杜绝路径穿越；
5. **回收失败不影响构建结论**：算不出清单、连不上构建机、`rm` 失败只记日志（未预期异常 ERROR 带堆栈）。把「磁盘清理不顺」报成「构建失败」是错的；
6. **保留数为 null 或 ≤ 0 时不做任何回收**，没有明确配额就删东西风险太高；
7. **置「已废弃」而非物理删除**，废弃版本不再出现在新建部署的版本下拉里（下拉只列「可发布」），避免出现「看得到却发不了」。早期约定「历史版本永不置已废弃」在保留策略接入后不再无条件成立——超出保留数且不在保护窗口内的版本会被废弃，「手工换包后补一次新建部署重发对应版本」要趁那个版本还在窗口内做。
8. **镜像类版本（DOCKER）不占配额、不参与回收**，由 `ReleaseRetentionService.occupiesBuildDisk` 在**配额计数之前**过滤：
   - 理由：DOCKER 的产物是镜像仓库里的 tag，构建不往产物持久目录落任何文件，`artifactDir()` 恒返回 null，走完回收流程也只是「置废弃、删了个空气」。省不下一字节磁盘，却让一个镜像仍在仓库、随时可再次发布的版本从下拉里消失；
   - 更隐蔽的危害是**挤占名额**：名额池固定，不占磁盘的 DOCKER 版本每多占一个，就有一个真正占磁盘的文件类版本被提前清掉。因此过滤必须在 `plan` 计算 `all.size() <= retain` 之前完成，**事后过滤等于没修**；
   - 判据写在 Java 侧而非 SQL：`runtime_type <> 'DOCKER'` 对该列为 NULL 的行求值是 NULL（不成立），会把运行时为空的历史记录整批漏掉；
   - `runtime_type` 为 NULL 的记录按「占磁盘」处理：它们基本是早期文件类版本，放行会让构建机上的产物目录永远失去回收机会；
   - 本条与回滚入口对 DOCKER 的 status 豁免（§24.5）是两处**独立**防护：已被误废弃的存量 DOCKER 版本仍能回滚。若要恢复其下拉可见性，手工把记录改回可发布即可——`DEPRECATED` 全项目只有 `markDeprecated` 一个写入点，不存在「本就该废弃」的歧义。

### 23.7 健康检查默认在目标机本地探测

`health_config_json.probe_from` 决定探测从哪里发起，**缺省与任何非 `PLATFORM` 的取值都按 `TARGET` 处理**：

| 取值 | 行为 | 判定的是什么 | 前置条件 |
| --- | --- | --- | --- |
| `TARGET`（默认） | 平台通过 SSH 登录目标机，TCP 用 `ss` 判端口监听（无 `ss` 时回落 bash `/dev/tcp`），HTTP 用 python3 请求 `127.0.0.1` | 服务有没有起来 | 只需 SSH 可达，业务端口无需对平台放行 |
| `PLATFORM` | 平台 JVM 直接连 `server.host:port` | 外部客户端能不能访问 | 平台必须能直达业务端口 |

必须遵守：

1. **默认只能是 `TARGET`**。发布后健康检查的目的就是确认应用活了，这个事实只取决于目标机自身。默认走平台侧会把「云防火墙/安全组没放行」报成发布失败——应用明明在跑、进程正常，却要求运维去放行端口，而放行本身不是部署的必要条件，还扩大了暴露面；
2. **TCP 探测判「端口处于监听状态」，不判「能否连上回环地址」**：服务可能只绑在具体网卡 IP 上，此时连 `127.0.0.1:port` 会失败但服务是好的。`ss -lntn` 与绑定地址无关，是更准确的判据；
3. **`TARGET` 模式下一次检查只建一条 SSH 连接**，多轮重试复用同一会话，不能把 `retry_count` 放大成同等数量的握手；
4. **不要因为「目标机可能没装 curl / nc」就退回平台侧**：目标机必然有 python3（JAR 部署脚本本身就是 python 的，初始化阶段还会显式探测），bash 的 `/dev/tcp` 也零依赖；
5. **`PLATFORM` 保留给「确实要验证外部可达性」的场景**，它的结论受平台所在网络出口影响——在笔记本上跑平台与在服务器上跑平台，对同一个端口可能得到相反结论。用 `PLATFORM` 排查失败时，必须在**平台那台机器**上复测，开发机的结果不作数；
6. **JAR 运行时健康检查失败时补 systemd 诊断**（`DeploySupport.appendServiceDiagnosis`）：`systemctl is-active = active` 只说明进程活着，不代表端口在监听。不把 `systemctl status` 的尾部输出写进任务日志，运维就只能登机器翻 journalctl，而发布日志本身会显示「服务启动成功」紧接「健康检查失败」这种无从解释的矛盾。

### 23.8 Dockerfile 一律由项目仓库提供（平台不生成模板）

构建镜像的 Dockerfile **只能来自项目仓库**，平台不渲染、不生成任何 Dockerfile。必须遵守：

1. **Docker 构建模式共三种**：`PROJECT_DOCKERFILE`（仓库自带单个 Dockerfile）、
   `LAYERED_DOCKER`（`Dockerfile.base` + `Dockerfile.app` 分层）、
   `JAR_PREBUILD`（项目提供极简 Dockerfile，平台先用 Maven 容器打出 jar 再包成镜像）。
   已下线的 `PLATFORM_TEMPLATE`（平台按语言渲染模板）连同与之配套的代码与配置字段**一并删除，不得加回**——
   语言探测、模板泛化这套机制的维护成本远高于「让项目自己写 Dockerfile」；
2. **不要重新引入语言探测**：JVM 参数、依赖安装方式、启动命令这类信息只有项目自己最清楚，
   平台猜错会产出「能构建成功、运行即崩」的镜像，失败点离原因极远；
3. **容器怎么跑也由项目说了算**：端口、启动命令写在项目自己的 Dockerfile / `docker-compose.yml` 里，
   平台只负责构建与分发，不推导默认值。不要再往 `build_config_json` 加 `app_port` / `start_command`
   这类字段去做二次推导；
4. **遇到不受支持的构建模式必须指名该怎么改**：`BuildTaskHandler` 解析 `build_mode` 失败时，
   错误文案要明确点出改用「项目Dockerfile」「分层构建」或「JAR预构建镜像」，而不是笼统的「构建模式非法」。
   存量库若仍有 `build_mode='PLATFORM_TEMPLATE'` 的记录，需要在库里一次性订正（MySQL 变更一律手工执行）。

### 23.9 compose 由项目提供，平台只做版本注入

Docker 的 Dockerfile 与 docker-compose.yml **统一来自项目仓库**（与 §23.8 同一个立场：平台不生成部署文件）。
载体与判据：`ComposeInjection`（常量与校验）、`DeployForm`（形态枚举）、`DockerDeployService`（下发与启动）、
`ShellQuote.heredocWrite`（写文件）。必须遵守：

1. **平台不渲染、不改写 compose 的任何内容**。需要随版本变动的镜像由项目写成
   `image: 仓库地址/镜像名:${IMAGE_TAG}`，平台把本次标签放进变量文件注入。**占位符与变量名只有一份定义**
   （`ComposeInjection.PLACEHOLDER` / `VARIABLE`），构建侧校验与发布侧注入必须共用它，
   分头写迟早漂移成「构建认 A、发布注入 B」；
2. **构建期与发布期各校验一次占位符**。缺了它容器会永远拉同一个 tag——发布报成功、
   线上没变化，是极难发现的一类故障。**禁止静默放行**（发布侧还要再校验一次，
   因为存在「校验上线之前」构建出来的存量版本）；
3. **compose 内容快照到 `release_record.compose_content`，不放构建机产物目录**。
   它是几 KB 的编排描述而非可分发制品：落盘要多付配额、回收与两跳传输的全部代价，
   存库则随版本精确回溯、发布时省掉一次传输。这条也保证了 §23.6 第 8 条
   「DOCKER 不占构建机磁盘」的配额判据继续成立。**代价必须写进文档告诉用户：改了 compose 要重新构建**；
4. **下发文件一律走 `ShellQuote.heredocWrite`（quoted heredoc），禁止手工拼 `cat >`**。
   少一层单引号 shell 就会把 `${IMAGE_TAG}` 提前吃掉，症状是 compose 里出现一串空值、
   容器起不来，而报错指向完全无关的地方；分隔符必须循环加后缀直到不出现在内容中
   （写死某个名字会让文件被静默截断）。该行为由 `ShellQuoteTests` 用真实 `sh -c` 执行来钉住；
5. **`compose_file_path` 显式配置却读不到 → 构建失败**；未配置且默认 `docker-compose.yml` 不存在 → 静默跳过。
   两种情形语义不同：前者是用户明确声明有这个文件，拦下是因为错误推迟到发布阶段更难排查；
   后者是绝大多数 `docker run` 项目的常态；
6. **`deploy_form` 默认 `RUN`**，形态切换会改变发布链路，默认值必须保证「不动配置 = 行为不变」。
   COMPOSE 形态下 `container_name` / `port_mappings` / `extra_docker_args` 不参与——
   它们表达的是 `docker run` 的参数，套到 compose 上只会造成「配了却没生效」；
7. **不做 compose 的全量覆盖与备份**：`exec/bak`、`static-bak` 那套针对的是「平台产出、可能被运维改乱」的文件，
   而 compose 由项目维护、每次随版本下发，平台也不充当它的真相来源。这条与 §24.12 是不同口径，不要套用。

**存量库升级**（MySQL 一律手工执行，平台不自动跑）：

```sql
ALTER TABLE release_record
    ADD COLUMN compose_content TEXT NULL COMMENT 'docker-compose.yml 原文快照，构建阶段从仓库读取'
    AFTER image_digest;
```

## 24. 任务执行与远程执行层约定

### 24.1 任务执行器

- **触发方式**：轮询 `lds_task` 中状态为 0（排队中）的任务，按 `create_time` 升序领取。不引入消息队列——任务表本身就是队列，应用重启后排队任务不会丢；
- **领取必须原子抢占**：通过 `TaskService.markRunning` 的「带状态条件的更新」完成 0 → 1 流转，**禁止**「先查状态再 update」的写法；抢不到的一方必须拿到明确异常并跳过，这是同一个任务不被重复执行的根本保证；
- **并发上限**：由 `light-deploy.task.pool-size` 决定（默认 6，对应 §1 的服务器规模基线）；领取前先取并发令牌，保证线程池内不存在等待执行的任务；
- **互斥键**：互斥键相同的任务串行执行，用于落实「同一服务器同一时刻只执行一个任务」（§1）。新增任务类型必须实现 `lockKey`，服务器类任务的互斥键为 `server:{serverId}`；
- **状态只能由流转方法修改**：`markRunning` / `markSuccess` / `markFailed` / `cancel` / `updateProgress` 是唯一入口，执行器是这些方法的唯一调用方；业务代码、Controller 和普通更新接口都不得直接写 `status`；
- **取消语义**：取消不会中断已经发出的远程操作。处理器必须在每个阶段开始前检查任务是否仍在执行中，一旦不是就停止后续远程操作；
- **重启收敛**：进程重启后残留的「执行中」任务不可能续跑，启动时必须统一标记为失败并写明原因，不允许页面永远显示执行中；
- **日志写入失败只记 WARN**，日志是过程留痕，不能成为任务失败的原因；
- **轮询失败要有节流**：数据库暂时不可用时只打印一次 WARN 并按间隔重试，恢复后再次失败才重新打印，禁止每秒刷一条错误日志。

### 24.2 任务处理器（TaskHandler）

- 一种任务类型对应一个实现类，注册为 Spring Bean，执行器自动发现；同一类型出现两个实现必须在启动时失败，不能靠覆盖顺序决定行为；
- 处理器只负责业务本身，**不修改任务状态**：正常返回即成功，抛出异常即失败（异常消息会作为失败原因展示给用户，因此禁止携带凭据、密文、Token）；
- 处理器负责汇报阶段与进度（`updateProgress`）并写执行日志；
- 未知任务类型也必须被领取并标记失败，不能让它永远停留在排队中。

### 24.3 SSH 执行层（`com.lx.deploy.ssh`）

- `SshSessionFactory` 是凭据明文**唯一**出现的地方：传入 `server` 实体，建立连接前才解密，认证完成后不再持有明文引用；明文不进日志、不进异常消息、不缓存到字段（§7）；
- 会话必须用 try-with-resources 关闭；一次远程操作对应一个会话（含其 SshClient），不共享客户端、不做跨任务复用；
- 远程命令的**非零退出码不抛异常**：是否失败由上层按业务语义判断（例如 `test -d` 返回非零本身就是一个有效结论）；只有通道打开失败、等待超时、读写异常才抛 `REMOTE_EXECUTION_ERROR`；
- 命令输出在进入日志与接口响应前必须脱敏（`SshSession.exec` 内部已调用 `maskSecrets`，禁止绕过 SSH 层自行拼命令输出）；
- 连接、认证、命令超时与输出上限一律走 `light-deploy.ssh.*` 配置，禁止硬编码；
- **命令按耗时分流，禁止一套超时打天下**：秒级命令（探活、检目录、`systemctl is-active`）用 `exec`（`command-timeout-seconds`，默认 120 秒）；耗时命令（克隆仓库、构建、镜像构建/推送、发布重启）必须用 `execLong`（`long-command-timeout-seconds`，默认 1800 秒）。首次构建要从仓库下载上百 MB 依赖，用秒级超时会把正常构建误判成卡死；
- **超时不得吞掉已收到的输出**：超时抛 `SshCommandTimeoutException` 并携带输出快照，调用方必须把快照落进任务日志（构建类命令按构建日志行数上限）。原来超时直接丢弃缓冲区，页面上只剩一句「执行超时」，无法区分「慢」与「卡死」；
- **长输出命令要实时回传**：`exec(cmd, timeout, consumer)` 会把 stdout 按行回调，用于构建这类长任务（页面能看到进度滚动）。回调必须走 SSH 层内置的队列 + 独立消费线程，**禁止**在读线程里直接写库；逐行回传必须封顶（构建类 500 行），超出后改为汇总补录；
- **超时要终止远端进程**：需要超时终止的执行（构建命令）必须以 `setsid -w sh -c '<脚本>'` 启动并把进程组组长 PID 记到 /tmp，超时后 `kill -9 -PGID` 再清理工作区；不得在远端进程可能仍在写的情况下直接 `rm -rf` 工作区；
- 私钥当前只支持未加密的 OpenSSH / PKCS#8 文本，遇到带口令私钥必须给出明确提示，不能静默失败。

### 24.4 业务记录与任务的关联

- 业务表**不冗余存 `task_id`**：任务与业务记录只靠 `lds_task` 的「`task_type` + `biz_id`」关联，避免两处状态互相打架。需要「从业务记录打开执行日志」时，由业务模块自己按这两个字段反查**最新一条**任务；
- 反查必须落在业务 VO 的统一转换方法里（如 `BuildService.toVo` / `DeploymentService.toVo`），**列表与详情共用同一入口**。只给详情接口回填 `taskId` 是不够的：前端列表行的「查看日志」按钮直接读列表数据，漏填时只能弹一句「未关联异步任务」，日志入口形同虚设；
- 一个业务记录可能对应多条任务（部署回滚会再建一条 DEPLOY 任务），一律按 `create_time` 倒序取最新；
- 反向（任务 → 业务记录）由任务类型决定，`TaskHandler` 不得绕过 `biz_id` 去猜业务记录；
- **失败结论必须与业务记录一致，且日志级别要能对上**：处理器表达失败的唯一方式是抛异常。**禁止**「写了 ERROR 日志却正常返回」——任务会显示「成功」，日志末尾却说「任务执行成功」，中间夹着 ERROR，运维无法判断这次到底成没成。有失败目标/失败步骤时，除了收敛业务记录状态，还必须抛异常让任务一并失败（失败原因取第一条明细，完整清单留在业务明细里）。反过来，不属于失败的负面情况（可选探活项未安装、目录缺失、命令非零退出但语义正常）只能写 INFO/WARN，不许用 ERROR。
  真实事故：`DeployTaskHandler` 汇总出失败目标后只把 deployment 置为 FAILED、自身正常返回，任务管理里因此显示「成功」，与部署记录和日志内容自相矛盾；

### 24.5 回滚的前置条件、预览与日志口径

回滚是「把线上退回到一个已知版本」的动作，代价高、不可逆，因此**入口必须拦住不能回滚的情况，日志必须能独立交代这次回滚做了什么**。

**前置条件：状态成功只是必要条件，不是充分条件**

- 只有「成功」的部署允许回滚（`DeploymentStatus.canRollback`）；
- 还要**至少一个目标存在可用的历史版本**。判定由 `DeploymentService.evaluateRollback` 统一给出，取三种原因中优先级最高的一条：① 状态不是成功；② 变更过的目标全都没有 `old_version`（首次发布）；③ 有 `old_version` 但版本记录查不到，或该版本缺少分发所需信息（DOCKER 缺 `image_tag`；JAR/STATIC 缺 `build_server_id` 或 `artifact_path`）；
- **不可回滚时必须明确拒绝，禁止静默成功**。曾经的做法是「一个目标都回滚不了时直接把状态置为已回滚」——使用者看到「已回滚」，机器上却一个字节都没变，页面上也找不到任何说明。现在 `rollback()` 按判定结论抛 `DeployException`；
- **判定结果必须与按钮共用**：`rollbackable` / `rollbackBlockReason` 随列表一起返回（在 `toVo` 里填充），前端据此置灰按钮并用 tooltip 说明原因。**禁止**前端自己拿 `status === 2` 推断可回滚——那正是「点了才被拒绝」的来源；
- 允许**部分回滚**：没有历史版本或历史版本产物已缺失的目标标记为「已跳过」并写明原因，保持原版本不动，不判失败、不把从未更新过的机器回退。

**提交前必须让使用者看到逐台影响**

「可回滚 / 不可回滚」只是一句话，回答不了「点下去到底会动哪几台、各自退到哪个版本」。因此：

- 提供只读接口 `GET /deployment/rollback-preview/{id}`（`DeploymentService.rollbackPreview`），返回总结论 + 逐目标明细：服务器名/地址、`当前版本 → 将回滚到的版本`、是否可回滚、跳过原因；
- **预览与提交必须共用同一份判定**。`evaluateRollback` 是唯一判定入口，逐目标明细由 `inspectTargets` 产出**一份**，`rollback()` 的目标重置与预览清单都消费它。**禁止**另写一套判定条件，否则会出现「预览说能退、提交时说退不了」；
- 列表侧同时返回 `rollbackTargetCount` / `rollbackSkippedCount`（判定时已算出，**零额外查询**），tooltip 直接给出台数，不必为每一行去调预览接口；
- 预览是**只读接口**：不得改变任何状态、不得触发任务；不可回滚时同样返回结论而不是抛异常，前端把 `blockReason` 展示在弹窗里，而不是只弹一句「操作失败」。

**版本字段的展示口径：`version` 是快照，「当前版本」另算**

`deployment.version`（取自 `release_id`）回答的是「这次动作针对哪个发布版本」，属于**记录创建时的快照，回滚后也不改写**——改了就与发布日志、产物路径、`release_record` 全部对不上。而「机器上现在跑的是哪个版本」是另一个事实，必须单独承载：

- `DeploymentVo.currentVersion` 由 `DeploymentService.resolveCurrentVersion` 在 `toVo` 里用**已加载的目标列表**算出（零额外查询）：发布成功取各目标的 `new_version`，已回滚取 `old_version`（回滚就是把机器退回老版本）。**只有全部成功目标一致时才返回**，多目标不一致（部分回滚、部分首次发布）时返回 null，页面不显示——一个版本号被当成「所有机器都是它」比不显示更糟；
- 部署列表的版本列：已回滚时在版本号下方补一行「当前 xxx」并给 tooltip 说明两者差别；其他状态不补（要么 `version` 就是当前版本，要么还没有确定结论）；
- 目标明细的版本对照**统一按「当前 → 目标」表述**，两种场景都自解释、方向永远与事实一致：发布 = `old_version → new_version`，回滚 = `new_version → old_version`。**禁止**按字段顺序直接拼 `oldVersion → newVersion`——回滚执行的恰恰是把 `new_version` 换回 `old_version`，箭头会反向；而这两个字段名不含角色信息，看的人只能猜「哪个是现在的」；
- 回滚预览接口里的 `currentVersion` / `targetVersion` 取法与此同源（`currentVersion` 取 `new_version`、`targetVersion` 取 `old_version`，见 `toRollbackTargetVo`），**三处口径必须一致**。
- **「动作完成后机器在跑什么」一律取 `DeployContext.targetVersionOf`，禁止用 `currentVersionOf`**（2026-09-28 修）。
  两者的方向是相反的：`targetVersionOf` = 回滚 ? `old_version` : `new_version`（本次写上去的、也就是动作结束后在跑的）；
  `currentVersionOf` = 回滚 ? `new_version` : `old_version`（本次被换掉的、只用于备份命名）。
  用反的表现极其隐蔽：**首次发布那次 `old_version` 为空 → 推断出「这台机器没有纳管版本」
  → 之后每一次发布的 `old_version` 都为空 → 回滚永远被判成「首次发布，无法回滚」，
  且备份名永远不带版本段**。涉及的三处：`DeploymentTargetPlanner.resolveCurrentVersions`（推断下次的 `old_version`）、
  `DeploymentVoAssembler.resolveCurrentVersion`（页面当前版本）、`DeployContext` 自身的 JavaDoc 警示。

**关于目标机 `exec/bak`**：平台回滚走的是「重新发布 `old_version`」，靠**版本记录 + 构建机上的产物**，**不读** `exec/bak` 里的手工备份包。因此判断「能不能回滚」要看历史版本产物是否还在（`historyArtifactAvailable`），不能看目标机上有没有备份包。`exec/bak` 的定位是运维断网/平台不可用时手工应急（`jar-fallback.py`）；注意**备份名里的时间戳指向「替换它的那次发布」，不是包里的版本**，版本对照靠**备份文件名内嵌的版本段**或 SHA256 比对（详见 §24.7）。

**回滚日志必须能回答六个问题**（`DeployTaskHandler`）：

| 必须出现 | 落点 |
|---|---|
| 开始回滚 + **开始时间** | 开场日志，含策略与「本次回滚 N/M 个目标」，M>N 时点明其余目标保持原版本 |
| **当前版本** | 每个目标的「当前版本 X → 回滚到 Y」，X 取 `new_version`（本次发布上线、正在跑的），Y 取 `old_version` |
| **回滚到什么版本** | 同上 |
| **时间** | 目标级起始时间与收尾完成时间，格式 `yyyy-MM-dd HH:mm:ss`，**写进日志正文**，不能只依赖日志行的落库时间 |
| **回滚成功 / 失败** | 收尾单独一条结论：「回滚成功：已成功回滚 N 个目标到历史版本…完成时间」/「回滚失败：…完成时间…请人工确认目标机当前版本」 |
| **是否启动成功** | `restartService` 通过后必须写一条「服务 X 启动成功（systemctl is-active = active）」，成功也要留痕；目标级成功文案写明「回滚成功，服务启动正常（当前版本 Y）」 |

**禁止**只靠目标行零散的 INFO 加上执行器结尾那句「任务执行成功」充当回滚结论——那句话不区分发布与回滚，运维无法据此判断版本退到哪了。回滚失败时任务与部署记录必须同时失败（见 §24.4），并提示人工确认目标机当前版本。

### 24.6 「重新构建」与「重新发布」的语义约束

两者都容易被当成「把上次那个动作再跑一遍」，但实现上都不是，**禁止在 UI 文案、接口注释、文档里把它们描述成重放**。

**构建行的「重新构建」是新建，不是重放。**

- 它只把 `projectId` 交给 `BuildService.createAndTrigger`（`views/build/index.vue` 的 `handleRebuild` 只取 `row.projectId`），业务语义是「针对该项目再发一次构建」：新 build 记录 + 新工作区 `{项目编码}_{buildId}` + 新 BUILD 任务；
- 克隆命令**只带分支、不带提交**：`git clone [--branch <project.git_branch>] <gitUrl> <workspace>`，全程没有 `checkout`，因此**永远构建分支最新 HEAD**。旧记录那次构建的产物不会被重建；
- **`build.git_commit` 只用于溯源与版本号生成，当前全项目没有任何读取点**，不能作为「按提交重建」的依据。后续若要做「按指定 commit 重建」，必须显式改造克隆命令（`fetch` + `checkout <commit>`）并处理分支 force push 后旧 commit 不可达的情形；
- 因此「重新构建」不能承诺产物可复现。要复现旧代码只能走部署侧（回滚 / 重发旧 `release_record` 版本）。

**部署记录是「一次执行的不可变快照」，禁止在原记录上重跑。**

- 目标列表在创建时快照到 `deployment_target`，这是回滚语义成立的前提（见 `DeploymentService` 类注释）。在原 `deployment` 上重发需要重置目标行，会覆盖首次执行的 `targetTotal / targetSuccess / targetFailed` 与起止时间，第一次执行的结论永久丢失；
- 更致命的是 `old_version` 与 `new_version` 会变成同一个值——`createAndTrigger` 正是靠这两个字段做「发布前版本 → 本次版本」的对照，相等后目标行再也说不清「从哪来、到哪去」，`historyArtifactAvailable` 的判定会指向自己；
- `resolveCurrentVersions` 依赖「一条记录 = 一次真实且不重复的状态迁移」反推每台机器当前版本（`OLD_VERSION_SCAN_LIMIT = 10`），原地重发会造成推断链自指；
- 回滚能在同一记录上执行，是因为它是**同一批目标的状态切换**（`ROLLING_BACK` → `ROLLED_BACK`），不是第二次独立执行，**两者不可类比**；
- 需要「重新发布」时，正确形态是**复制出一条新部署记录**（新 `deployment` + 目标快照 + 新 DEPLOY 任务，旧记录一字不动），产物可用性复用 `DeploymentService.historyArtifactAvailable` 判定；目标集取「当时快照」还是「当前启用绑定」需在实施前拍板；
- 「重发同版本」当前通过「新建部署」手选历史版本即可完成——`ReleaseStatus.DEPRECATED` 全项目零引用，历史版本永远处于「可发布」。**未经同步评估该链路，禁止引入版本自动废弃策略。**

### 24.7 目标机版本标识、产物完整性校验与手工回退

平台可达时，「这台机器在跑哪个版本」看部署记录即可；但平台宕机、或运维直接登录机器排查时，机器上只有一个不带任何标识的 jar，回退与排障都只能靠时间戳猜。本节约束三件配套能力，对 JAR 运行时它们是**发布流程的一部分，不是可选增强**。

**一、版本标识（`VERSION` 文件 + 备份名版本段）**

同一份 `key=value` 文本在两个位置出现，`version=` 行一律写平台**原始版本号**（如 `auth-7888-v3-887b603c`），与 `release_record.version` 同源，**禁止**写美化文案或提交短码；另有一个只有版本号、不带其它字段的落点——备份文件名：

| 位置 | 写入方 | 含义 |
| --- | --- | --- |
| `{build_artifact_dir}/{项目编码}/{版本号}/VERSION` | `BuildTaskHandler.writeArtifactVersionMarker` | 这份产物是哪个版本 |
| `{workdir}/VERSION` | `DeployTaskHandler.writeVersionMarker` | 这台机器上盘的 jar 是哪个版本 |
| `exec/bak/{jar名}_bak_{时间戳}_{版本号}.jar` | `DeployTaskHandler.backupCurrentJar` | 这个备份包里装的是哪个版本 |

- **禁止靠重写 jar 的 `MANIFEST.MF` 实现**：Spring Boot fat jar 的嵌套 jar 依赖 ZIP 的 STORED 布局，用 `jar --update` 重打包一旦改变压缩方式，会静默产出「启动即失败」的产物；且重打包会改变 SHA256，与下面登记的摘要语义冲突。用外部标识文件，产物一个字节都不动；
- **时序固定为「jar 上盘 → 写标识 → 重启服务」**：标识必须与磁盘一致。写在重启之后，重启失败会让标识停在旧版本、与磁盘错位，反而误导排查；
- 写标识失败**只记 INFO 日志、不影响发布结论**——它是辅助信息，不该挡住一次已经成功的发布；
- **备份名必须内嵌被替换包的版本号**：备份名里的时间戳是执行替换的时刻（指向替换者），只留时间戳会让每个备份都无法对应到平台版本。平台与 `jar-update.py` 都必须把版本段写进名字，取不到才省略该段。**禁止**改成「另存一个 `{备份名}.version` 伴随文件」：信息虽等价，但 `ls` 看不见、要多养一个文件，且会让 `.version` 看起来像是 `.jar` 的后缀；版本段取的是目标机 `VERSION`（磁盘事实），**不取平台库快照**——库快照在手工换包后会失真。写入文件名前必须清洗为 `[A-Za-z0-9._-]`，防止被改脏的 `VERSION` 带出 `/` 把备份写到别的目录；
- **版本段逐字保留平台版本号，不把其中的连字符换成下划线**（同 §3）：文件名里的 `auth-7888-v7-1521739f` 必须与 `release_record.version`、Docker 镜像 tag、`VERSION` 文件字面一致，才能直接复制去查库、搜日志、比对镜像。改成 `auth_7888_v7_1521739f` 之后，读回时得按段把 `_` 回译成 `-`，而项目编码本身允许含 `_`，回译不再双射。文件名里 `-` 与 `_` 混用**是有意为之**：`_` 分隔备份名的结构段，`-` 属于版本号本身；
- 标识文件面向人工与目标机脚本，**平台自身不以它为判定依据**：能否回滚仍由 `historyArtifactAvailable` 查 `release_record` 决定。

**二、产物完整性校验（`sha256`）**

`release_record.sha256` 由构建机 `sha256sum` 算得。产物要经「构建机 → 平台 → 目标机」两跳传输，因此 **JAR 发布必须在目标机复核摘要**（`DeployTaskHandler.verifyJarDigest`）：

- 校验点在**替换 jar 之后、重启服务之前**：jar 已上盘、服务尚未重启，此刻发现损坏可以无损还原；
- 不一致时**先还原发布前的备份，再抛异常**，不允许把损坏的包留在工作目录。只抛异常会让机器停在「服务跑着旧版本、磁盘放着坏包」的错位状态——下次重启必然起不来，而失败原因只写着校验失败，很难联想到磁盘；
- 还原动作本身失败**只记 ERROR 日志、不覆盖校验失败这个根因**；无可还原备份时必须明确提示「工作目录中的 jar 已损坏，请手工从平台重新发布」；
- 登记摘要为空（历史版本）或目标机算不出摘要（缺 `sha256sum`）时**跳过并写明原因**，不阻断发布——这两种情况都不是产物损坏；
- 校验通过**也要写 INFO 日志**并带上摘要值，让「检查确实执行过」可被追溯。日志级别只有 INFO / ERROR（见 `TaskLogLevel`），跳过类信息归 INFO。

**二之二、产物投放：摘要比对跳过、传输方式与日志拆分**

- **重复发布同一版本时跳过上传**：投放前在目标机算现有 jar 的 `sha256`，与 `release_record.sha256` 一致就跳过备份与上传（`JarDeployService.artifactUpToDate`）。产物要经两跳传输，74 MB 在窄带链路上要传数分钟，而比对只是一次目标机本地 `sha256sum`。命中时既不备份也不上传——被替换者与替换者是同一个包，备份没有意义。版本未登记摘要、目标机还没有 jar、目标机算不出摘要三种情况一律按「需要上传」处理，**保守方向不能反**；
- **大文件走流式管道，SFTP 只作回落**：`SshSession.uploadLarge` 优先用 exec 通道 `cat > 文件` 写标准输入，任何失败都回落 `sftpUpload`。SFTP 是带窗口与逐包确认的应用层协议，高延迟链路上吞吐被往返时间压住（实测 74 MB 走 SFTP 需 325 s，约 0.23 MB/s），而管道由 TCP 自己做流控。两条路径写完同样要过上面的 `sha256` 校验，不会静默写入半截文件；
- **投放日志必须拆「开始 / 完成」两条**：`开始投放 x.jar（74.01 MB）到 …` 与 `投放完成（流式管道，耗时 xx s，x.xx MB/s）`。只写一条「投放 … 到 …」会让上传耗时看起来凭空消失——其余步骤都是**完成时**措辞，唯独这条是**进行时**，按字面排时间线必然把上传耗时算到别的步骤头上；
- **健康检查通过时同样输出 `systemctl status`**（`DeploySupport.appendServiceDiagnosis` 的 `healthy` 参数）：通过时是「留证」，INFO 级、只保留能证明服务在跑的头部 8 行；失败时是「取证」，ERROR 级、放宽到 30 行，因为要找的原因往往在尾部。只在失败时输出，等于成功时没有任何证据表明服务真的起来了。
**三、手工回退的版本识别口径**

运维在目标机手工回退时，按可靠性依次使用：

1. **SHA256 比对（精确，权威口径）**：`sha256sum {workdir}/{jar名} exec/bak/*.jar`，与平台 `release_record.sha256` 对照（`SELECT version, version_seq, sha256 FROM release_record WHERE project_id = ? AND runtime_type = 'JAR' AND is_deleted = 0 ORDER BY version_seq DESC`），或直接在构建机 `sha256sum {build_artifact_dir}/{项目编码}/*/{jar名}` 得到「版本 ↔ 摘要」对照表（产物目录名即版本号）。备份是 `cp -p` 的字节级复制，摘要必然一致。**版本段与时间戳都是辅助信息——载体是普通文件或文件名，可被手工换包弄脏**；严肃确认（回滚前、追责时）一律以 SHA256 为准；
2. **备份名版本段（直接）**：`exec/bak/{jar名}_bak_{时间戳}_{版本号}.jar` 的版本段直接给出包内版本，`ls` 即可读出；`jar-fallback.py` 已据此打印「当前版本 → 回退到版本」；
3. **时间戳反查（兜底）**：备份名时间戳 → 平台该时刻附近的部署记录 → 该记录的 `old_version`。**改造前产生的备份名里没有版本段**（形如 `{jar名}_bak_{时间戳}v{序号}.jar`，末尾 `v{序号}` 是防撞名序号而非版本），只能走这条或第 1 条。

**两个时间戳不要混**：文件名里的时间戳是**备份时刻**（执行替换的时刻），`ls -l` 显示的 `mtime` 是**包被发布到工作目录的时刻**——`cp -p` 保留了源 jar 的元数据。判断「哪个是最新备份」必须看**文件名时间戳**；为此 `jar-fallback.py` 的 `--steps` 排序按**文件名时间戳**而不是 `mtime`（后者虽与版本序一致，但语义上是「上次发布的时刻」，容易被读错）。

**备份名的版本段有一次发布的预热期**：版本段取自 `{workdir}/VERSION`，而改造前的工作目录里并不存在这个文件，因此**重启后的第一次发布拿不到版本段**（名字退化为 `{jar名}_bak_{时间戳}.jar`），该次发布结束才写入 `VERSION`，**再下一次**发布的备份才带版本段。这是**预期行为，不是缺陷**（改动生效的前提是平台后端已重启，否则连 `VERSION` 都不会写）。

手工换包后**平台状态会与机器实际脱节**：`resolveCurrentVersions` 读的是数据库快照，下次发布算出的 `old_version` 会错。因此手工回退后**应在平台补一次「新建部署、重发对应版本」**，把平台状态对齐。

**适用范围**：本节三项能力目前只覆盖 **JAR** 运行时。DOCKER 的版本就是镜像 tag（`docker images` 即可识别），无需额外标识；STATIC **有本地备份**（`static_backup_dir` 下 `{目录名}_bak_{时间戳}_{版本号}`，见 §3.2），但**没有落在目标机上的版本标识文件**（无 `VERSION` 等价物），`current_version` 仍来自平台数据库快照；其手工回退就是直接把备份目录 `cp -a` 回 `static_root`，若要让平台识别「目标机当前是哪个版本」，则需引入站点内的版本标识文件，属独立改造，不在本节范围内。

### 24.8 项目连通性测试（`PROJECT_TEST`）

构建链路里最容易「第一次跑才暴雷」的三件事（Git 仓库不可达 / 凭据失效 / 分支名写错、镜像仓库登录失败、
构建机缺工具），只有在真正点「构建」时才会依次撞上。因此在构建之前先测一遍，入口 `POST /project/test/{id}`。

- **必须在项目的构建机上执行**（`project.build_server_id`），**不允许**拿平台本机代替：平台机器常常不装
  git / docker，网络策略也与构建机不同，测出来的结论说明不了构建能否成功；
- 项目未绑定构建机时**直接拒绝提交**，而不是回落到「测平台自己」；
- 校验全程**只读**：`git ls-remote` 而非真克隆（走完整网络 + 认证链路，但不下载对象、不占磁盘、秒级返回），
  `docker login` 用完即 `logout`，临时凭据文件用完即删；
- 分支不存在时 `git ls-remote --heads <url> <branch>` 会**成功返回且输出为空**，
  因此必须同时判退出码与空输出，只看退出码会漏掉「分支名写错」这个最常见的失败；
- **互斥键取构建机**（`build-server:{serverId}`，与 BUILD 同键）：测试期 `docker login` 会写同机
  `~/.docker/config.json`，与正在推送的构建并发会互相登出，必须串行；
- 提交侧用 `TaskService.hasUnfinishedTask(PROJECT_TEST, projectId)` 防重复提交；
- 判定与日志口径沿用 §24.1 / §24.2 与 [任务模块 §6.8](docs/任务模块.md)：必需项失败即任务失败并给出
  「怎么补」的可操作原因，可选项（`mvn` 未装、`curl` 不可用）只写 INFO。

`lds_task.task_type` 是字符串列，新增任务类型**不需要 DDL**。完整字段语义与探活项见
[项目模块](docs/项目模块.md) §6.7。

### 24.9 部署目标的执行顺序（发布顺序快照）

发布顺序是部署目标配置（`project_environment_target.deploy_order`）的属性，但**执行用的顺序必须随
`deployment_target` 快照一起固化**，不允许执行时现查。

- 创建部署时把 `binding.deployOrder` 写入 `deployment_target.deploy_order`；执行器
  （`DeployTaskHandler`）与页面目标明细（`DeploymentService.listTargets`）一律按
  `deploy_order ASC` 取顺序，`create_time` 仅作历史数据（`deploy_order IS NULL`）的兜底键；
- **禁止只用 `create_time` 排序**：同一次创建的多条目标在同一秒插入，而 `create_time` 是秒级
  `DATETIME`，排序键**完全并列**；MySQL 对并列行不保证稳定顺序，实际退化为随机 UUID 主键顺序。
  现象是「部署目标配了顺序 1，滚动发布却先发顺序 2」，`ROLLING` + `batchSize` 分批时后果最明显
  （顺序直接等于批次划分）。注意这条更隐蔽的形态：`ORDER BY create_time` 本意是保证顺序，
  一旦写出显式排序，InnoDB 反而从「插入的物理顺序」切到索引/filesort 顺序，把随机性引了进来；
- **必须是快照，不得实时关联 `project_environment_target`**：那张表是可变的（可改序、可解绑），
  回头去读它会让后续的配置变更**倒过来改写已经发生过的历史部署**，与 §24.6「deployment 是一次执行的
  不可变快照」直接冲突；
- 该字段只服务于「一次部署内几条目标」的排序，结果集为个位数，**不建索引**；
- DDL 见 `src/main/resources/db/upgrade-deployment-target-order.sql`，配套只读校验
  `verify-deployment-target-order.sql`。历史部署的数据**故意不回填**：回填只能依据并列的
  `create_time`，等于把随机顺序固化成看似可信的数据，反而误导排查；`NULL` 的语义明确表示
  「该字段上线前的部署未记录顺序」。

### 24.10 回滚产物的来源：构建机为准，本机备份为应急

**平台回滚取产物的正式来源只有一个：构建机的产物持久目录** `{build_artifact_dir}/{项目编码}/{版本号}/`
（路径由 `release_record.artifact_path` 记录；`build_artifact_dir` 来自**服务器档案按机器配置**，
不是平台全局配置，DDL 默认 `/opt/light-deploy/artifacts`）。JAR 与 STATIC 皆然，Docker 不在此体系内。

而目标机上的 `exec/bak`（JAR）与 `static-bak`（STATIC）是**应急副本，不是版本仓库**。它有三个消费者，
必须分清，否则会误判「回滚到底走的哪条路」：

| 消费者 | 谁在用 | 触发时机 | 代码 |
| --- | --- | --- | --- |
| 平台自愈 | 平台自动 | 发布中途失败（JAR 完整性校验失败 / STATIC 新内容就位失败）时还原回发布前 | `JarDeployService.restorePreviousJar`、`StaticDeployService.switchDirectory` 的失败分支 |
| 平台加速 | 平台自动 | **仅 STATIC 回滚**：先查本机备份，命中即 `cp -a` 还原、不过构建机 | `StaticDeployService.restoreFromBackup` |
| 人工兜底 | 运维上机 | 平台状态与磁盘脱节时手工恢复 | 目标机脚本 `jar-fallback.py`；备份名内嵌版本号可直接辨认 |

几条硬性要求：

- **判定顺序必须是「先确认构建机产物可用 → 再考虑本机快路径」**，不能反过来。
  `DeploymentRollbackService.historyArtifactAvailable` 要求 JAR/STATIC 的 `buildServerId + artifactPath`
  都在，缺失即在入口判不可回滚 —— 也就是说**本机备份救不了「产物已被保留策略回收」的版本**，
  判定反了会出现「按钮可点、执行到一半才失败，机器已经动了半截」；
- **本机快路径只能用 `cp -a`，禁止 `mv`**：备份必须保留，否则回滚一次后再回滚同一版本就得重新走构建机；
- 备份命名**必须内嵌平台版本号**（`{名}_bak_{时间戳}_{版本}`），取不到版本时退化为不带版本段——不猜版本；
- 本机备份的产生是**每次发布新增一份**（回滚还会再新增一份，见 §24.12）。静态 dist 体积大、
  文件多，涨得比 JAR 快。清理策略与 `retain_release_count` 解耦，统一由 `scripts/cleanup-bak.sh`
  承担，规则见 §24.12，**不得**顺手把 `retain_release_count` 套到本机备份上；
- **改构建机的 `build_artifact_dir` 是高危操作**：历史 `release_record.artifact_path` 存的是绝对路径，
  改完会让所有历史版本指向不存在的路径，须先确认没有仍在使用的历史版本；
- **JAR 不查本机备份回滚是「未实现」而非「不可行」**。该类曾以「exec/bak 命名只有时间戳、
  无法对应平台版本」为由排除本机路径，该理由随 §24.7 备份名内嵌版本号后已失效。
  若要补这条快路径，与 STATIC 同构即可，且**不得**因此放宽入口判定。

### 24.11 发布发到哪台机器：只看部署目标绑定，环境不决定机器

发布时选环境，平台只是拿 `(projectId, environmentId)` 去查 `project_environment_target`，**环境本身不持有机器**。完整说明与运维操作手册见 `docs/环境与部署目标.md`。

- **唯一的发布依据是 `project_environment_target`**（项目 × 环境 × 服务器，`status=1` 启用才参与）。
  `DeploymentService.createAndTrigger` 只查这一张表；环境不提供任何机器，也没有任何兜底逻辑。
- **`environment.default_server_id` 已于 2026-09-28 移除，不得再加回来**。
  它原先只有环境的 CRUD 与列表展示读取，发布链路零消费；运维常误以为设了它机器就会被发布到。
  更根本的问题是**维度错误**：环境是全局共享的（被多个项目引用），而各项目在该环境下绑哪些机器
  由 `project_environment_target` 决定，「某个环境的默认服务器」这个语义本身不成立。
  需要表达「主要发这一台」请用 `project_environment_target.is_primary`（项目×环境维度，且真的生效）。
  存量库可手工清理（不执行也不影响运行，MyBatis-Plus 只按实体字段生成 SELECT 列）：
  `ALTER TABLE environment DROP COLUMN default_server_id;`
- **`server.env_tag` 同样不参与**（§6.2）。
- **策略会二次裁剪**：`SINGLE_TARGET` 只发 1 台（主目标优先，无主目标取 `deploy_order` 最小），
  绑了 N 台也只发这一台——这是「以为发了全部其实只发一台」的唯一成因，页面与日志都必须把台数显示出来。
- **`server.status=0`（服务器停用）是失败不是跳过**：被选中的目标里含停用服务器时，
  `createAndTrigger` 直接抛错「部署目标服务器 X 已停用，不允许发布」。要摘机器应**停用绑定**
  （`project_environment_target.status=0`），它保留绑定关系且不影响其他项目。
- **`deployment_target` 不存 host 快照**，只存 `server_id`。改服务器 IP 会让历史部署记录显示的 IP
  跟着变，事后无法从平台确认「当时发到了哪个 IP」——换 IP 前先摘除该绑定。
- **不要给每台服务器各建一个环境**：版本链按「项目 + 环境」推断 `old_version`，拆成两个环境会让
  两条链路互不可见（永远判首次发布、无法回滚），且防重失效（进行中部署的判定同样按项目 + 环境）。


### 24.12 本机备份的清理：独立双上限，不与版本保留策略联动

目标机上的 `exec/bak`（JAR）与 `static-bak`（STATIC）**不由 `project.retain_release_count` 管理**。
两者裁的是不同维度，且必须避免共因失效：

| | `retain_release_count`（构建机产物） | 本机备份（`exec/bak`、`static-bak`） |
| --- | --- | --- |
| 裁的是什么 | **版本号维度**：保留最近 N 个可发布版本 | **份数 / 时间维度**：最近多少个包、多少天内的包 |
| 组织形式 | 一版本一目录，可按版本号精确取回 | 「某次发布时被换下去的那个包」，同一版本可重复出现 |
| 回收执行者 | `ReleaseRetentionService.plan` + 构建机删目录 | `scripts/cleanup-bak.sh`（默认 dry-run） |

- **禁止把 `retain_release_count` 套到本机备份上**：两者联动清理会在「构建机产物被回收」的同时
  清掉「本机兜底」，两条恢复路径同时失效——而本机备份存在的意义恰恰是构建机那条路断了。
- **清理不影响回滚的可行性，只影响回滚的速度**。JAR 回滚 100% 走构建机（§24.10），
  本机备份被清到 0 份回滚照样成功；STATIC 被清空只是不再命中快路径，回落构建机后照样成功。
  因此保留数应按「能接受多慢」来定，不必按「怕不能回滚」来定。
- 清理统一走 `scripts/cleanup-bak.sh`，两条硬规则：
  - **份数上限 + 天数上限，任一超限即清最旧的**（默认 `-n 10 -t 30`）；
  - **最近 1 份永不删除**（`-k`，默认 1）——它是发布失败时手工回滚的唯一救命稻草。
- 执行时机必须放在**发布成功之后**，不能放在发布之前（发布前清理可能删掉紧接着要用的那一份）。
  **清理失败只记日志，绝不阻断发布**。
- 脚本只处理名字匹配 `*_bak_[0-9]*` 的一级条目，**绝不递归、绝不触碰同目录的其他文件**，且默认 dry-run。

> ⚠️ 该脚本带 `rm -rf`，属高危操作：新目录首次运行必须先不带 `--apply` 预演，
> 确认候选清单无误后再执行。

**回滚入口判定必须看版本状态**（2026-09-28 起由 `probeRollbackArtifact` 保证）：
JAR / STATIC 只有 `release_record.status = 可发布` 才判可回滚 —— 被 `ReleaseRetentionService`
置「已废弃」时构建机产物目录已被删除，而 `artifact_path` 字段的值仍留着，
只判字段非空会出现「按钮可点、执行到一半才失败」。两类不可用的原因文案必须各自成文，
不要笼统说「产物已不存在」。**DOCKER 不看 status**：它的产物是镜像仓库里的 tag，
置废弃时不删任何东西（其 `buildServerId` 为空，`artifactDir()` 返回 null），
对它加 status 判定会无端砍掉一个镜像还在、本来能回滚的版本。
详见 `docs/产物分发链路.md` §8.7。


## 25. 内置脚本约定

平台内置在 `src/main/resources/scripts/jar-deploy/` 下的脚本（`jar-init.py` / `jar-update.py` / `jar-fallback.py`）会被投放到目标机并执行，属于**平台资产**：

- **必须兼容 Python 3.5+**：目标机的 `python3` 常见是 3.6（CentOS 7 一类），**禁止**使用 3.7 才有的参数与语法（`subprocess` 的 `capture_output` / `text` 必须写成 `stdout=PIPE, stderr=PIPE, universal_newlines=True`）。真实事故：`run()` 用了 `capture_output=True, text=True`，在老机器上抛 `__init__() got an unexpected keyword argument 'capture_output'`，初始化在最后一步 `systemctl daemon-reload` 前中断，整台目标被判失败，而报错信息里没有任何版本线索；
- 换行符与编码固定 `\n` + UTF-8，脚本头部保留 `# -*- coding: utf-8 -*-`；
- 脚本必须**幂等**：重复执行只补齐缺失部分，不覆盖已有的 jar、配置与 systemd 单元（运维手工调过的启动参数必须被尊重）；
- 退出码语义固定：`0` 成功，`1` 失败且失败原因写 stderr —— 平台按 stderr 首行生成「目标失败」原因，因此 stderr 文案要面向运维可读；
- **每次发布都重新投放**三份脚本（与是否首次部署无关）：脚本版本必须随平台走，只在首次投放会让目标机长期停留在一份旧脚本上，而应急发版/回滚恰恰依赖它们；
- 执行前先确认目标机有 `python3` 并回显 `python3 --version`，让解释器版本进入任务日志，避免下次再靠猜；
- 脚本内禁止第三方依赖，只用标准库；禁止把凭据、Token 写进脚本或参数。

**版本信息的读写口径**（详见 §24.7）：

- `exec/deploy.conf` 必须含 `version=` 行，表示**本机当前运行的版本**。首次初始化写空（此刻确实没有发布过任何版本），之后由平台发布流程、`jar-update.py`、`jar-fallback.py` 三方刷新，**三者语义必须一致**，不得一处写版本号、另一处写提交短码；
- `jar-init.py` 重复执行时**必须保留已登记的 `version`**：init 可能被运维手工重跑，重置它会让「这台机器在跑什么」凭空丢失，而平台要到下次发布才会写回；
- `exec/` 下「待投放版本」的标识固定为 **`exec/VERSION`**（与构建机产物目录里的文件同名同格式，运维把产物连它的 `VERSION` 一起拷进 `exec/` 即可，**无需改名**），随 jar 一起被 `jar-update.py` 消费掉，避免下次误用旧值；
- 脚本**禁止猜版本号**：`jar-update.py` 的版本来源固定为「`--version` 参数 > `exec/VERSION` > 空」，取不到就写空并打印提示。**记错版本比不记版本更危险**；被替换掉的旧版本始终能从**备份文件名的版本段**找回；
- 重写 `deploy.conf` 用「剔除旧 `version=` 行 → 追加新行 → 原子替换」，**不使用 `sed -i`**（各发行版 `-i` 参数与转义规则不一致，且版本号可能含特殊字符）；
- 版本对照**必须来自文件或文件名**（`{workdir}/VERSION` 与备份名的版本段），不允许靠备份文件时间戳反推；旧格式备份名里没有版本段时只报「未登记」并提示用 `sha256sum` 与平台比对。回退到这类备份后，`VERSION` 与 `deploy.conf` 一并写空——磁盘上确实是「未登记版本」，**留着旧值反而与磁盘错位**；


## 26. 操作日志约定（AOP + 注解）

「谁在什么时候、通过哪个接口、改了什么」由 `audit_log` 记录，写入方式固定为 **`@OperLog` 注解 +
`OperLogAspect` 切面**，业务代码不手工写审计日志。

- **只有被 `@OperLog` 标注的方法会留痕**，且只标**会改变状态**的接口（新增 / 修改 / 删除 /
  发布 / 回滚 / 取消 / 触发构建 / 连通性测试）。查询类接口不标注：量大且没有追溯价值，
  全量记录只会让操作日志退化成第二份访问日志；
- **失败同样落库**：一次被拒绝的新增往往比成功的更值得查，因此切面 catch 分支记 `result=0`
  并带上脱敏后的失败原因。**异常原样向上抛**，切面不吞异常、不改变业务结果；
- **落库失败只记 ERROR 不抛异常**（§14.3）：审计是旁路，写不进去最多是「这次没留痕」，
  绝不能反过来让操作本身失败；
- **敏感字段先脱敏再落库**：请求参数过 `SecretMasker.maskJsonFields`（按
  password / credential / secret / token / privateKey / encrypted 键名递归替换值），异常消息过
  `SecretMasker.maskSecret`。服务器、凭据这类**参数整体就是凭据**的接口必须显式写
  `recordParam = false`——脱敏是兜底，不是把明文收进数据库的理由；
- **只记 URI 不记查询串**，与访问日志 `RequestTraceFilter` 的口径一致（§14）；
- **本表只追加**：Service 只暴露分页与详情，不提供新增 / 修改 / 删除接口——审计记录被改写
  就不再是审计，与其靠约定禁止，不如让接口层根本没有入口；
- **操作人来自请求头** `X-Operator-Id` / `X-Operator-Name`（前端拦截器统一带出），取不到时记
  `system`。过渡期没有登录态（§16），接入统一权限中心后改为从认证信息填充，切面读取逻辑不变；
- **`trace_id` 必须落库**：它与响应头 `X-Request-Id`、日志里的 `%X{requestId}` 是同一个值，
  是「一条操作日志 → 当次请求全部日志」的唯一线索；
- 切面用 `@Around` 而不是「`@AfterReturning` + `@AfterThrowing`」：耗时与成败需要统一收口，
  拆成两个通知要靠 ThreadLocal 传状态，更容易在异常路径上漏写；
- **绝不注入容器管理的 `ObjectMapper`**：Spring Boot 4 起容器只自动配置 Jackson 3
  （`tools.jackson.databind.ObjectMapper`），而项目代码引用的是 Jackson 2
  （`com.fasterxml.jackson.databind.ObjectMapper`），按惯例注入会直接让上下文启动失败。
  序列化统一走 `SecretMasker.toJson / toTree / parseTree`（内部静态实例），**不再新增注入点**。
  另一个理由是审计文本不该跟随业务序列化器的全局配置变动——命名策略一改，历史日志的可读性就跟着变。
  **该静态实例必须注册 `JavaTimeModule`**，否则序列化含 `java.time` 字段的对象会被吞成 null（§26.3）；
- **返回结果同样过 `SecretMasker.maskJsonFields`**：统一响应里可能带回凭据类字段，
  只脱敏请求参数等于防线只修了一半；
- 字段扩展 DDL 见 `src/main/resources/db/upgrade-audit-log-operation.sql`（操作字段）、
  `upgrade-audit-log-application.sql`（应用系统字段），配套只读校验 `verify-audit-log-operation.sql`，
  完整设计见 [操作日志模块](docs/操作日志模块.md)。

### 26.3 应用系统字段与审计序列化

`audit_log.application` 回答「这条操作属于哪个系统」，取值来自配置 **`light-deploy.application-name`**
（默认「轻量部署发布系统」），由 `OperLogAspect` 构造注入并在写日志时统一填充：

- **禁止在 `@OperLog` 注解上逐个写系统名**：它是实例级事实，28 个接口各写一遍，改名时要改 28 处
  且必然漏，漏改的表现是同一张表里出现两种系统名，比字段缺失更难解释。改名只改配置一行，
  历史记录不受影响（历史行由 `upgrade-audit-log-application.sql` 回填一次）；
- 该列**不加索引**：取值在整个实例里高度重复（通常只有一种），索引只会增加写入成本，
  筛选用的是时间、操作人与模块（已有 `idx_audit_create_time` / `idx_audit_operator`）。

审计链路的序列化**必须显式注册 `JavaTimeModule`**（`SecretMasker` 内的静态 Jackson 2 实例）：

- Jackson 2.21 起 `java.time` 类型默认要求显式处理器，未注册时序列化 `LocalDateTime` 直接抛
  `InvalidDefinitionException`，而 `toJson / toTree` 会把它吞成 null；
- 后果是双重的且都**静默**：统一响应 `ApiResponse.timestamp` 序列化失败 → `json_result` 全空
  （页面显示「无返回结果」）；实体快照 `toTree` 失败 → `changed_fields` 也永远采不到
  （实体基类 `BaseEntity` 自带 `create_time` / `update_time`）。两者都不会报错，只会「少了东西」；
- 因此 `ResourceDiffRecorder.snapshot` 在快照为 null 但实体非 null 时必须留一条 WARN（§14.3），
  排查时优先看 `verify-audit-log-operation.sql` 结果集 4 的「返回结果」列与结果集 6 的空值占比。

### 26.2 字段级变更明细

修改与删除类操作在 `@OperLog` 上额外声明 `resourceType`（Server `server`、部署目标
`projectEnvironmentTarget`、凭据 `credential`、项目 `project`、环境 `environment`、
镜像仓库 `registry`、发布 `deployment`、构建 `build`），切面据此在 `changed_fields` 里记下
「哪个字段从什么改成了什么」：

- **前置快照必须在 `proceed()` 之前取**：业务方法一执行旧值就被覆盖，之后再查只会比出空。
  这也是把采集放在切面层而不是各 Service 上报的原因——漏写一处就永久少一个模块的明细；
- **只有「修改 / 删除」声明 `resourceType`**：新增没有旧值可比，比出来只会是满屏
  「- → 新值」的噪音，因此 insert 一律不声明；
- **资源定位走 Mapper 索引**：`ResourceDiffRecorder` 用
  `getBeansOfType(BaseMapper.class)` 扫描，bean 名去掉 `Mapper` 后缀即为资源类型，
  新增模块无需改动该类。**不要改成构造器注入 `Map<String, BaseMapper<?>>`**——那依赖容器对泛型
  通配符的可赋值判定，判定失败就是启动崩溃；
- **两类字段不进明细**：基础字段（`update_time` 每次必变却无业务含义）与敏感字段
  （凭据、私钥、令牌，判定与请求参数脱敏共用 `SecretMasker.isSensitiveKey`）；
- **删除操作**查不到变更后记录时，旧值全部以 `(已删除)` 作为新值呈现，回答「删掉的是什么」；
- 字段名中文映射在前端 `src/constants/auditLog.ts` 的 `fieldLabel()`，三级回落到原字段名——
  翻译表是展示层职责，放后端会让每次加字段都要改后端。

### 26.1 新建部署的选环境下拉

新建部署弹窗的**环境下拉只列该项目已绑定启用目标的环境**，选项格式为「生产环境（2 台）」：

- 数据源是 `GET /projectEnvironmentTarget/env-options?projectId=`，只返回至少有一台**启用**
  绑定的环境，台数也只数启用绑定——与发布模块展开目标的过滤条件
  （`DeploymentService.createAndTrigger` 取 `EnableStatus.ENABLED`）一致，否则会出现
  「下拉写着 2 台、提交后说没有启用目标」的矛盾；
- 与搜索区的环境下拉**不共用数据**：搜索要能筛到「任何环境的历史记录」，新建部署只能选
  「这个项目真能发的环境」，两者语义不同，共用一个列表必然有一边是错的；
- 台数在 Service 内按环境分组统计，不新增 Mapper 方法：一个项目下的绑定通常只有几台。
