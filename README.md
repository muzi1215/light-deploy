# Light Deploy · 轻量部署发布平台

> **把手工 SSH 部署脚本，变成一个团队都能安全使用的平台。**

Light Deploy 是面向中小企业的**轻量化部署发布平台**。基于 Spring Boot 单体架构，通过 SSH/SFTP 纳管多台 Linux 目标服务器，支持 **DOCKER / JAR / STATIC** 三种运行时的构建与发布。运维在页面上完成「登记服务器 → 建项目 → 绑定目标机 → 选版本一键发布」，全程无需人工登录目标机；发布过程日志按阶段实时滚动，健康检查通过才标记成功，并支持回滚到任意保留版本。

---

## 目录

- [特性](#特性)
- [技术栈](#技术栈)
- [整体架构](#整体架构)
- [核心概念](#核心概念)
- [快速开始](#快速开始)
- [使用流程](#使用流程)
- [构建模式说明](#构建模式说明)
- [模块进度](#模块进度)
- [开源与密钥安全](#开源与密钥安全)
- [后续路线](#后续路线)
- [License](#license)

---

## 特性

| 特性 | 说明 |
| --- | --- |
| **三种运行时** | DOCKER / JAR / STATIC 一套平台统一纳管，各走最优发布链路 |
| **构建发布分离** | 专用构建机执行克隆 / 构建 / 推送，平台中转产物到目标机，目标机无需持有构建机免密 |
| **实时日志** | 构建 / 发布任务按阶段增量拉取日志（`/taskLog/pull`），不轮询分页 |
| **版本回滚** | 每次发布成功自动登记「可发布版本」，可回滚到任意保留版本（`retain_release_count` 控制保留数） |
| **凭据加密** | Git 令牌 / 私钥 / 镜像仓库密码统一 AES-GCM 加密落库，明文只写不读 |
| **并发发布** | 线程池并发令牌 + 同服务器互斥键，支持多台服务器并行发布、同机串行 |
| **健康检查** | HTTP / TCP 探针，不通过即判目标失败，避免「发布成功但服务起不来」 |
| **操作审计** | AOP 全链路操作日志（`audit_log`），含字段级 diff 与脱敏 |

---

## 技术栈

| 层次 | 选型 |
| --- | --- |
| 语言 / 框架 | Java 21 + Spring Boot 4.1.0 |
| ORM | MyBatis-Plus 3.5.17（ASSIGN_UUID 主键、逻辑删除、自动填充） |
| 存储 | MySQL（utf8mb4）+ Redis（任务状态 / 分布式锁 / 进度） |
| 远程执行 | Apache SSHD / SFTP 2.18.0 |
| 前端 | Vue 3 + Vite + TypeScript + Element Plus + Pinia |
| 交付 | Docker / Docker Compose |

---

## 整体架构

```text
┌──────────────────────────────────────────────────────────────┐
│                      运维 / 开发 浏览器                        │
│              Vue3 前端（Vite 开发 :9850 / Nginx 托管）          │
└───────────────────────────────┬──────────────────────────────┘
                                │  HTTP  /api  →  :2027/deploy
                                ▼
┌──────────────────────────────────────────────────────────────┐
│                   Light Deploy 平台（单实例）                   │
│  Controller(DTO/VO) ─ Service(业务规则/加解密) ─ Mapper(MP)     │
│  构建机调度(BuildTaskHandler)   发布编排(DeployTaskHandler)      │
│  凭据加密(CredentialCrypto)     任务执行器(并发令牌+互斥键)       │
└───────┬───────────────────────────────────────┬──────────────┘
        │ SSH/SFTP（命令执行 + 文件分发）          │ HTTP（静默窗口/建议）
        ▼                                        ▼
┌──────────────────┐  ┌────────────────────┐  ┌─────────────┐
│ 专用构建机        │  │ 6+ 台目标 Linux    │  │  AIops 系统  │
│ Maven/Docker 构建 │  │ 服务器（D/J/S 运行）│  │（告警联动）  │
└──────────────────┘  └────────────────────┘  └─────────────┘
```

数据落 MySQL；Redis 承担任务状态、分布式锁与实时进度。平台自身以「后端 fat-jar（systemd 注册）+ 前端静态资源（Nginx 反代）」为主要交付形态，也提供 `Docker/` 容器化部署（见 §6）。

---

## 核心概念

| 概念 | 说明 |
| --- | --- |
| **服务器 (server)** | 纳管的 Linux 目标机或构建机，记录 SSH 主机 / 端口 / 认证类型（密码 / 私钥）/ 环境标签 / 工作区与部署根目录 |
| **凭据 (credential)** | Git 令牌 / SSH 私钥 / 镜像仓库密码，AES-GCM 加密落库，执行前短暂解密 |
| **镜像仓库 (registry)** | 镜像推送目标（地址 / 命名空间 / 凭据），仅服务于 DOCKER 运行时 |
| **项目 (project)** | 一个可部署的服务模块；含运行时、源码来源、构建模式、三段 JSON 配置 |
| **环境 (environment)** | 如 dev / test / prod，项目可绑定到多个环境 |
| **部署目标 (target)** | 项目在某环境下命中的具体服务器（含主目标标识），发布时快照固化 |
| **发布版本 (release)** | 构建成功自动生成的「可发布」记录（镜像 digest / 文件 SHA256），回滚依据 |
| **发布策略** | 单目标 / 全量 / 滚动 三种；滚动失败即中止后续批次 |

---

## 快速开始

### 环境要求

- **JDK 21**（必须，Spring Boot 4 要求）
- **Maven 3.9+**（或用仓库自带 `./mvnw`）
- **Node.js 18+**（前端开发 / 构建）
- **MySQL 5.7+ / 8.x**（utf8mb4）+ **Redis 6+**

### 1. 初始化数据库

应用启动**不会**自动执行任何 SQL（`spring.sql.init.mode=never`，避免误删数据）。全新环境执行建表脚本：

```bash
# 建库并导入全部 15 张表结构
mysql -h127.0.0.1 -uroot -p -e "CREATE DATABASE deploy CHARACTER SET utf8mb4;"
mysql -h127.0.0.1 -uroot -p deploy < src/main/resources/db/schema.sql
```

> 已有旧库请改跑 `src/main/resources/db/upgrade-*.sql`（幂等，可重复执行），详见 `docs/项目总览.md` §5。

### 2. 配置密钥（必填）

平台用 **环境变量** 注入 AES-256 密钥，缺失则凭据无法加解密：

```bash
export LIGHT_DEPLOY_AES_KEY="$(head -c 32 /dev/urandom | base64)"
```

该密钥必须是 Base64 解码后恰好 32 字节的随机串（AES-256）。**请妥善保管，更换密钥后已落库密文将无法解密。**

### 3. 启动后端

```bash
# 默认监听 2027 端口，context-path 为 /deploy
./mvnw -o spring-boot:run
# 或本地已装 maven：
mvn -o spring-boot:run
```

健康检查：`curl "http://127.0.0.1:2027/deploy/server/page?pageNum=1&pageSize=10"` 返回 JSON 即成功。

### 4. 启动前端

```bash
cd lighit-deploy-ui
npm install
npm run dev          # 开发模式，自动打开 http://localhost:9850
# 生产构建：npm run build  → 产物在 dist/，交给 Nginx 托管
```

Vite 开发代理已配置：`/api` → `http://localhost:2027`，并改写为 `/deploy`（即 `/api/server/page` → `:2027/deploy/server/page`）。生产部署时由 Nginx 反向代理到后端 `:2027/deploy`。

### 5. 访问

浏览器打开 `http://localhost:9850`（开发）或 Nginx 地址（生产）。当前前端为「假登录 + 静态菜单」演示态，任意账号可进入。

### 6. Docker 部署（生产推荐，jar 本地构建）

平台自身的容器化部署文件在 `Docker/` 目录：**不在镜像里编译**，只把本地构建好的 jar 装进 JRE 镜像。

```bash
# 1. 本地（或构建机）打包
mvn -DskipTests package
cp target/light-deploy-0.0.1-SNAPSHOT.jar Docker/light-deploy.jar

# 2. 整个 Docker 目录上传到目标机，目录里内容：
#    Dockerfile  docker-compose.yml  application.yaml  light-deploy.jar  logs/

# 3. 目标机一次性准备
docker network create lx-app-net          # 平台不创建网络，external
mkdir -p logs && chown -R 1000:1000 logs  # 容器内以 appuser(1000) 运行

# 4. 起服务（换 jar 后要带 --build，只 restart 不会更新镜像）
docker compose up -d --build
```

要点：

- **配置外置**：`application.yaml` 只写「与容器不同」的键（MySQL/Redis 地址、AIops 地址），挂载到容器内 `/app/application.yaml`。改完 `docker compose restart` 生效，不用重建镜像；未写的键继续沿用 jar 内配置。
- **网络**：加入 external 网络 `lx-app-net`，容器间按容器名互访（如 AIops 后端 `aiops-backend:2029`）；`extra_hosts: host.docker.internal` 用于访问宿主机上裸跑的 MySQL/Redis。
- **端口**：`2027:2027`，容器内端口由 `server.port` 决定，两边要一起改。
- **日志**：落盘到宿主机 `./logs`（`LOG_PATH=/app/logs`），容器删了日志还在。
- **密钥**：容器必须能拿到 `LIGHT_DEPLOY_AES_KEY` 环境变量（compose 注入或宿主机环境透传），否则启动报「缺少 AES 密钥」、凭据无法解密。该变量与 §2 同源，部署时一并注入。

---

## 使用流程

```text
① 登记服务器    →  ② 登记凭据 / 镜像仓库  →  ③ 建项目（选运行时+构建模式+绑环境目标）
        ↓                  ↓                        ↓
④ 触发构建（代码→可发布产物，自动登记 release）  →  ⑤ 选版本一键发布（实时日志+健康检查）
                                                        ↓
                                              ⑥ 失败可回滚到任意保留版本
```

1. **服务器**：在「服务器管理」登记目标机 / 构建机的 SSH 信息（密码或私钥）。
2. **凭据 / 镜像仓库**：DOCKER 项目需先登记镜像仓库及登录凭据；Git 来源需登记令牌 / 私钥。
3. **项目**：新建项目，选择 `runtimeType`（DOCKER / JAR / STATIC）、`sourceType`（GIT / UPLOAD / ARTIFACT_ONLY）、`buildMode`（见下），绑定到环境的部署目标机。
4. **构建**：项目页「构建」按钮触发异步构建，页面实时滚动日志；成功自动生成可发布版本。
5. **发布**：选版本 + 策略（单目标 / 全量 / 滚动）一键发布，按阶段实时日志 + 健康检查。
6. **回滚**：发布记录页对成功版本执行回滚，复用同一处理器回退到 `old_version`。

---

## 构建模式说明

`runtimeType` 与 `buildMode` 的允许组合由 `RuntimeType.supportedBuildModes` 唯一决定：

| 构建模式 | 运行时 | 说明 |
| --- | --- | --- |
| `PROJECT_DOCKERFILE` | DOCKER | 使用项目自带的 `docker-compose.yml` / `Dockerfile` 构建 |
| `LAYERED_DOCKER` | DOCKER | 平台分层（`base` + `app`）构建，加速镜像推送 |
| `JAR_PREBUILD` | DOCKER | 平台在构建机打 fat-jar，再包成运行镜像（无需项目写 Dockerfile） |
| `SCRIPT_DEPLOY` | JAR / STATIC | 构建机执行脚本产出 jar / 静态文件，计算 SHA256 |
| `ARTIFACT_ONLY` | JAR / STATIC | 仅登记并发布已存在的产物（不上构建机执行构建） |

> `PLATFORM_TEMPLATE` 为早期「平台猜测语言生成 Dockerfile」的方案，已整体下线——它产出的镜像「能构建成功、运行即崩」，失败点离原因极远，故砍掉。保留此注记以解释历史代码与文档。

> **架构一致性（Docker 构建）**：本平台 Docker 构建发布**暂不支持跨架构**——构建机与目标机需同为 amd64（生产环境即如此，无冲突）。若本地用 arm64 构建机（如 M 系列 Mac 上的 arm64 虚拟机）打镜像、amd64 服务器运行，会 `exec format error`。规避方式见 `docs/Docker镜像构建发布方案评估.md` §11.1。JAR / 脚本 / STATIC 部署不受架构影响。

---

## 模块进度

| 模块 | 包 | 状态 |
| --- | --- | --- |
| common 基座 / MyBatis-Plus 配置 | `com.lx.deploy.common` / `config` | ✅ 完成 |
| 服务器模块 | `com.lx.deploy.server` | ✅ 完成 |
| 项目模块 | `com.lx.deploy.project` | ✅ 完成 |
| 任务模块 + 状态机 + 执行器 | `com.lx.deploy.task` | ✅ 完成，真机验证通过 |
| SSH 执行层 | `com.lx.deploy.ssh` | ✅ 完成（密码 / 私钥，真机验证） |
| 凭据模块 | `com.lx.deploy.credential` | ✅ 完成 |
| 镜像仓库模块 | `com.lx.deploy.registry` | ✅ 完成 |
| 构建模块 | `com.lx.deploy.build` | ✅ 完成 |
| 发布模块 | `com.lx.deploy.deployment` | ✅ 完成 |
| 发布版本查询（只读） | `com.lx.deploy.release` | ✅ 完成 |
| 操作日志（审计） | `com.lx.deploy.audit` | ✅ 完成 |
| 前端工程 | `lighit-deploy-ui` | ✅ 完成（项目管理 / 构建 / 发布 / 审计等页面，Nginx 托管生产前端） |
| 用户模块 | `com.lx.deploy.user` | ⏳ 过渡期（Model/Mapper/Service，无 Controller，仅开发联调登录） |
| AIops 集成（发布联动） | `openapi` / `deployLink` | ✅ 完成（发布静默窗口 + 事件推送 + 前端联动抽屉） |

状态图例：✅ 已完成 ｜ ⏳ 进行中/占位 ｜ ⬜ 未开始

详细约定与每模块文档见 `docs/` 目录与根目录 `AGENTS.md`（项目唯一权威约束来源）。

---

## 开源与密钥安全

仓库当前的 `src/main/resources/application.yaml` 含**占位用的明文密钥**，提交到 GitHub 前必须清理：

| 项 | 位置 | 处理 |
| --- | --- | --- |
| 数据库密码 | `spring.datasource.password` | 改为环境变量 / 外部配置，勿提交真实密码 |
| AES 密钥 | `light-deploy.crypto.aes-key` | **删除明文**，改用 `LIGHT_DEPLOY_AES_KEY` 环境变量注入 |
| 默认构建机 ID | `light-deploy.build.server-id` | 生产用外部配置设置，勿提交真实服务器 ID |

建议做法：保留 `aes-key: ${LIGHT_DEPLOY_AES_KEY:}` 这类占位，将真实值放在运行环境的系统环境变量或 `application-prod.yaml`（并加入 `.gitignore`）。

> ⚠️ 已落库的凭据密文与 AES 密钥强绑定，泄露密钥等同于泄露全部服务器 / 仓库登录凭据。开源前务必重置。

---

## 后续路线

1. ✅ 任务执行器 + SSH 连通性测试（真机验证）
2. ✅ 凭据 + 镜像仓库模块
3. ✅ 构建模块（含 JAR_PREBUILD）
4. ✅ 发布模块（三策略 + 健康检查 + 回滚）
5. ✅ AIops 集成（发布静默窗口 + 事件推送 + 前端联动抽屉）
6. ⏳ 鉴权中心接入（统一登录 + 动态菜单）
7. ✅ 收尾（audit_log 审计、Docker 化交付、Nginx 托管前端）

---

## License

本项目采用 **MIT License** 开源。详见 `LICENSE` 文件（若缺失请自行添加）。
