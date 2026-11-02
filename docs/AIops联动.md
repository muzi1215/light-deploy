# AIops 联动模块文档

> 模块包：`com.lx.deploy.aiops` ｜ 状态：已完成并编译通过（v1：静默窗 + 事件上报 + 两个补偿任务）

## 1. 模块定位

AIops 联动负责发布期间的**告警静默**与**事件上报**：发布前开一个带自动过期时间的静默窗，发布过程中把阶段事件（准备/单目标成功/失败/静默关闭/回滚）推给 AIops；发布收尾或回滚稳定后关窗。这样发布期间的预期抖动不会被告警系统误报。

- 本模块提供：静默窗开/关（`AiopsSilenceService`）、事件推送（`AiopsEventPublisher`）、HTTP 客户端（`AiopsClient`，基于 Spring `RestClient`）、两个补偿定时任务（对账/重试）；
- 本模块不提供：风险分析、自动回滚决策（AIops 后端负责，v1 平台侧只做联动）；具体发布动作（由 deployment 模块执行）。
- **fail-open 原则**：AIops 不可用 / 调用失败**绝不阻断发布**——静默窗开不了、事件推不动，发布照样继续（AGENTS.md：fail-open 降级绝不让发布失败）。

## 1.1 调用链路（发布线程内同步 HTTP）

AIops 联动**不是事件驱动、没有 MQ**：它是发布线程内**同步 HTTP 调用**，由 `DeployTaskHandler` 在发布各阶段直接调用。

```text
DeployTaskHandler.execute(task)              // deployment/executor/DeployTaskHandler.java:232
  │
  ├─ AiopsSilenceService.openForDeployment(deployment)   // aiops/service/AiopsSilenceService.java:154
  │     └─ AiopsClient.createSilence(request)            // aiops/client/AiopsClient.java:94
  │           POST /openapi/v1/silences        （把 silenceId 写回 deployment.aiops_silence_id）
  │
  ├─ AiopsEventPublisher.publish(type, deployment, target, ...)  // aiops/service/AiopsEventPublisher.java:91
  │     └─ 先插 aiops_event(PENDING) → deliver() → AiopsClient.pushEvent()  // AiopsClient.java:161
  │           POST /openapi/v1/deployment-events   （带 Idempotency-Key）
  │
  └─ AiopsSilenceService.closeForDeployment(id, reason)  // AiopsSilenceService.java:221
        └─ AiopsClient.closeSilence(id, reason)          // AiopsClient.java:116
              POST /openapi/v1/silences/{id}/close
```

触发点（在 `DeployTaskHandler` 内）：开静默窗 @300、关静默窗 `closeSilenceWindow()@560`；事件推送见 `publish(...)` 调用（准备 @304、单目标成功 @457、单目标失败 @472、静默关闭 @574）。

**独立补偿（与发布触发无关，两个 `@Scheduled`）：**

```text
AiopsSilenceReconciler.reconcileSilences()   // aiops/service/AiopsSilenceReconciler.java  @Scheduled
  └─ 周期性核对/补关静默窗（发布平台自身撞重启导致窗口挂到 TTL 时兜底）
        → silenceService.closeForDeployment(deploymentId, reason)  @122

AiopsRetryScheduler                          // aiops/service/AiopsRetryScheduler.java  @Scheduled
  └─ 扫描 aiops_event 失败/待重试记录 → AiopsEventPublisher.retry()
```

## 2. 两级开关与触达条件

静默窗/事件**不会无条件发送**，需同时满足两级开关：

| 开关 | 位置 | 说明 |
| --- | --- | --- |
| 平台总闸 | `light-deploy.aiops.enabled`（默认 false） | 关闭则全平台不联动 |
| 环境开关 | `environment.aiops_enabled`（默认 0） | 存量环境默认全 0，排障先查此项 |

`AiopsSilenceService.openForDeployment` 先做两级开关判定，未开启直接返回 `skipped`（不推 PREPARED）。因此「平台配了 AIops 但环境没开」时，发布侧看不到任何静默窗——这是预期行为。

## 3. 关键类与行号

| 类 | 职责 | 行号 |
| --- | --- | --- |
| `AiopsSilenceService` | 静默窗开/关 + 两级开关判定 + fail-open 降级 | `aiops/service/AiopsSilenceService.java:154`（开）、`:221`（关） |
| `AiopsEventPublisher` | 事件「先落库再发」可靠投递 + 异常吞掉不反向上抛 | `aiops/service/AiopsEventPublisher.java:91` |
| `AiopsClient` | Spring `RestClient` 封装，`RestClient.builder()` @62 | `aiops/client/AiopsClient.java:94`（createSilence）/ `:116`（closeSilence）/ `:161`（pushEvent） |
| `AiopsSilenceReconciler` | `@Scheduled` 静默窗对账补关 | `aiops/service/AiopsSilenceReconciler.java:122` |
| `AiopsRetryScheduler` | `@Scheduled` 失败事件重试 | `aiops/service/AiopsRetryScheduler.java` |
| `AiopsLinkController` | 只读查询：`GET /aiops/link/{deploymentId}` @53、`GET /aiops/status` @68 | `aiops/controller/AiopsLinkController.java` |

> 静默窗 TTL 默认 15min（原 40）：TTL 只兜「关窗+补关都够不着」的极端情况，正常由收尾 + 对账关闭。

## 4. 数据表结构

| 表 | 关键字段 | 说明 |
| --- | --- | --- |
| `aiops_event` | id / deployment_id / event_type / status / payload / idempotency_key / retry_count | 事件投递表：`PENDING`→`SENT`/`FAILED`；`idempotency_key` 防重复 |
| `aiops_silence`（或映射 deployment.aiops_silence_id） | silence_id / deployment_id / status / expire_at | 静默窗映射；`expire_at` 由 AIops 后端保证自动过期 |

> 具体 DDL 以 `src/main/resources/db/schema.sql` 为准。

## 5. 枚举设计

| 枚举 | 取值 | 说明 |
| --- | --- | --- |
| `AiopsEventType` | PREPARED / HOST_SUCCESS / HOST_FAILED / SILENCE_CLOSED / ROLLBACK_STARTED / ROLLBACK_SUCCESS / ROLLBACK_FAILED | 事件类型中文名只有两处（平台 `getLabel()` 与 AIops 后端 `EVENT_TYPE_OPTIONS`），前端渲染用下发 label，禁写第三份 |

## 6. 接口清单（只读）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/aiops/link/{deploymentId}` | 本次部署的 AIops 联动状态（静默窗/事件） |
| GET | `/aiops/status` | 平台级 AIops 状态（`AiopsStatusVo`：enabled / reachable / baseUrl / checkedAt） |

## 7. 核心业务规则

- **同步 HTTP、非事件**：发布线程内直接调 `AiopsClient`，无 MQ、无 `@EventListener`（全项目缺失这两类机制）；
- **先落库再发**：`AiopsEventPublisher` 先把事件存 `aiops_event(PENDING)` 再推，推送成功置 `SENT`，失败留 `FAILED` 待补偿；
- **异常绝不反向上抛**：事件推送任何异常都吞掉，不影响 `DeployTaskHandler` 对发布成败的判定（AGENTS.md：fail-open）；
- **两级开关 + 环境开关**：未开启返回 skipped，不推 PREPARED；
- **静默窗自动过期**：即使平台挂了没关窗，AIops 后端按 `expire_at` 自动失效。

## 8. 与其他模块的关系

| 方向 | 关系 |
| --- | --- |
| ← deployment 模块 | `DeployTaskHandler` 在发布各阶段调本模块开/关静默窗与推事件（见 §1.1） |
| → AIops 后端（独立服务） | 通过 `AiopsClient` 调其 `/openapi/v1/*` 接口 |
| → 定时任务线程池 | `AiopsSchedulingConfig` 显式定义 2 线程 `aiopsTaskScheduler`，补偿任务串行发 HTTP 不饿死事件补发 |

## 9. 已知边界与后续演进

- **v1 仅占位闭环**：静默窗 + 事件上报已落地，风险建议（回滚建议等）由 AIops 后端深化，平台侧不自动执行回滚；
- **环境开关默认全关**：存量环境 `aiops_enabled=0`，联调前需在环境管理页开启；
- **补偿只补关不重开**：`AiopsSilenceReconciler` 仅对账「该关没关」的窗口，不创建新窗口。
