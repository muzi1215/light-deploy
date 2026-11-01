# AIops 联动（发布静默窗 + 发布事件）

> 本文是 §15「AIops 集成边界」的落地说明：**边界仍然是 §15 划的那条**，这里只回答「具体怎么实现、怎么验证、坏了怎么救」。
> 代码位置：平台 `com.lx.deploy.aiops`，AIops 侧 `aiops/deploylink`。

## 1. 解决什么问题

一次发布必然重启服务：`systemctl restart`、换 jar、重建容器。这些动作会产生**预期内的告警**——
接口 502、健康检查失败、端口短暂不可用。没有静默机制时：

1. 每次发布都推一堆告警，值班同学被噪音训练成「发布期间的告警先不管」；
2. 真正的故障（发布引起的数据库连接池耗尽、磁盘写满）恰好落在这个盲区里被忽略；
3. 事后复盘只有告警时间线，没有「哪个版本、哪台机器、几点开始几点结束」的对照。

联动做两件事：

- **静默窗**：发布期间只对「本次发布的目标机 + 预期内告警类型」压制**通知**，告警照常入库；
- **事件上报**：把发布生命周期（开始 / 单机成功失败 / 整体结论 / 回滚）推给 AIops，形成「发布 ↔ 告警」的对照时间线。

**静默 ≠ 丢弃**。窗口期内的告警一条不少地入库，只是 `send_status=3`（被发布静默抑制），事后可查、可统计。

## 2. 链路总览

```
DeployTaskHandler.execute
  │
  ├─(1) AiopsSilenceService.openForDeployment   ──POST──▶ AIops /openapi/v1/silences
  │        写回 deployment.aiops_silence_id                 └─ 落 ops_deploy_silence（带自动过期）
  ├─(2) publish PREPARED / STARTED
  │
  ├─(3) 逐目标执行 deployTarget
  │        ├─ 成功 → publish HOST_SUCCESS
  │        └─ 失败 → publish HOST_FAILED
  │
  ├─(4) DeploymentService.markSuccess/markFailed/markRolledBack/markRollbackFailed
  │        └─ publish SUCCEEDED / FAILED / ROLLBACK_SUCCEEDED / ROLLBACK_FAILED
  │
  └─(5) AiopsSilenceService.closeForDeployment  ──POST──▶ /openapi/v1/silences/{id}/close
            publish SILENCE_CLOSED

人工取消：DeploymentService.cancel → 先关窗，再 publish CANCELLED
```

所有对外调用都是 **先落 `aiops_event` 再发送**，失败进补偿队列（见 §5），因此 AIops 不可用只会让事件晚到，不会让发布失败。

## 3. 平台侧实现

### 3.1 包结构与职责

| 类 | 职责 |
| --- | --- |
| `config/AiopsProperties` | `light-deploy.aiops.*` 全部配置 |
| `config/AiopsSchedulingConfig` | 仅 `enabled=true` 时开 `@EnableScheduling`（平台其他模块没有定时任务） |
| `client/AiopsClient` | 只负责发报文、解 `{code,message,data}` 外壳；不做业务判断 |
| `client/AiopsDtos` | 嵌套 record 报文模型，字段名与对端驼峰输出严格对齐 |
| `client/AiopsException` | 外部系统异常，刻意区别于 `DeployException` |
| `service/AiopsSilenceService` | 建窗 / 关窗 / TTL 估算 / `failOpen` 降级决策 |
| `service/AiopsEventPublisher` | 事件组装 + 落库 + 投递 + 失败排期 + 重试 |
| `service/AiopsRetryScheduler` | `@Scheduled` 扫 `next_retry_time` 补发 |
| `model/AiopsEvent` | 表 `aiops_event` |
| `model/AiopsEventType` | 10 类事件编码 + 中文短名（`getLabel`，页面直接渲染，前端不再维护第二份映射） |
| `service/AiopsLinkService` | 只读聚合：本地事件流水 + 远程静默窗详情，远程失败降级为文案 |
| `controller/AiopsLinkController` | `GET /aiops/link/{deploymentId}`，供前端联动抽屉 |
| `model/vo/AiopsLinkVo` / `AiopsSilenceVo` / `AiopsEventVo` | 联动抽屉的响应模型 |

### 3.2 挂载点（改发布链路时必须同步的位置）

| 位置 | 动作 |
| --- | --- |
| `DeployTaskHandler.execute`（`markDeploying` 之前） | 开静默窗 → `PREPARED` |
| `DeploymentService.markDeploying` | `STARTED` |
| `DeployTaskHandler.deployTarget` 成功分支 | `HOST_SUCCESS` |
| `DeployTaskHandler.deployTarget` 失败分支 | `HOST_FAILED` |
| `DeployTaskHandler.closeSilenceWindow`（`finishDeployment` 内） | 关窗 → `SILENCE_CLOSED` |
| `DeploymentService.markSuccess/markFailed/markRolledBack/markRollbackFailed` | 对应终态事件 |
| `DeploymentService.cancel` | 关窗 → `CANCELLED` |

**顺序不可颠倒的两处：**

1. **开窗必须在第一台目标重启之前**。晚一步，重启引起的告警已经推给值班了，静默等于没做。
2. **关窗必须在状态落库之后**。先关窗再写结论，中间那几秒的告警既没被静默、也没有归因可查。

### 3.3 静默范围与时长

- **范围**：只含本次部署的目标机（`deployment_target.server_id` 对应 `server.host`），
  同环境其他机器照常告警。请求里同时给 `serverId`（平台主键）与 `host`（IP），
  **对端以 host 对齐**——两边服务器主键体系不同，只按 ID 匹配会永不命中。
- **类型**：由 `alert-categories` 指定（默认「服务异常」），取值沿用 AIops 的 `alarm_type_name`，不另建词汇表。
- **时长**：`静默基准时长 + 3 分钟 ×（目标数 - 1）`，封顶 120 分钟。
  超出部分会被对端硬上限截断，所以平台自己先算准，避免日志里出现「被截断」这种需要解释的告警。
- **幂等**：幂等键 `light-deploy:silence:{deploymentId}`。同一条部署重复建窗只回放已有窗口，不会开出第二个。

### 3.4 两级开关：总闸 + 环境开关

建窗不是「配了令牌就建」，而是两级开关与运算，任一级关着都**静默跳过**（不报错、不打【注意】）：

| 级别 | 位置 | 语义 | 关掉后的行为 |
| --- | --- | --- | --- |
| 一级（总闸） | `light-deploy.aiops.enabled`（`application.yaml`） | 平台有没有这个能力 | 整个联动停摆：不建窗、不关窗、不推事件、不注册补偿任务、不注册 `/openapi/aiops/**` |
| 二级（环境级） | `environment.aiops_enabled`（环境管理页面） | 这个环境的发布要不要建窗 | 只影响该环境：不建窗、不推 `PREPARED`，事件推送与关窗链路不受影响 |

判定顺序固定为：**总闸 → 是否已有窗（幂等）→ 环境开关 → 目标机是否存在**，
实现见 `AiopsSilenceService.openForDeployment`。

**为什么必须两级**：总闸承担「这次联调 / 这段灰度期先整体关掉」的运维诉求，改配置文件重启即可，
不用去逐个环境点开关；环境开关承担「生产建窗、测试不建窗」的差异化——
测试环境一天发十几次，压告警反而掩盖真问题。

**两条容易踩的语义：**

- 环境开关**不是快照**：它读的是当下 `environment` 表的值，不是部署记录上的值。
  发布进行中把环境开关关掉，只影响之后新建的部署，不影响正在跑的那条（那条的 `aiops_silence_id` 已经写进 `deployment` 表）。
- 环境开关关闭时**只跳过静默窗**，`STARTED / HOST_SUCCESS / SUCCEEDED` 等事件照常推送。
  也就是说 AIops 侧仍能查到「这次发布发生了」，只是没有静默窗——这是有意为之：
  事件是审计视角，静默窗是降噪视角，两者不该绑死。

### 3.5 页面展示：联动抽屉

入口在「发布管理 → 部署记录」操作列的 **AIops联动** 按钮，抽屉里两块内容对应 §4 的两种数据形态。

| 层 | 文件 | 说明 |
| --- | --- | --- |
| 接口 | `GET /aiops/link/{deploymentId}` | 聚合三处：`deployment.aiops_silence_id`、`aiops_event`、AIops 静默窗详情（远程只读） |
| 服务 | `AiopsLinkService.getByDeployment` | 远程失败只写 `silenceMessage`，**不抛异常** |
| 组件 | `lighit-deploy-ui/src/components/AiopsLinkDrawer.vue` | 静默窗信息卡 + 事件时间线 |
| 常量 | `constants/aiopsStatus.ts` | 只维护颜色；中文名由后端 `eventTypeLabel` 下发 |

**三档短路，顺序即「为什么没有窗口」的解释优先级**（`fillSilence`）：

1. 总闸关 → 「平台 AIops 联动总闸未开启」；
2. 没建过窗 → 环境关 / 建窗失败降级，两种文案分开；
3. 有窗口 ID 但远程查不到 → 「详情查询失败（原因）」，并说明窗口本身不受影响，会按到期自行失效。

**两个刻意的设计：**

- **远程查询失败不报错**：排障最需要这个页面的时候，往往正是 AIops 不可用的时候。
  详情拉不到就把原因写在页面上，事件流水照常展示（它在本地库）。
- **按钮常驻，不按「有没有窗口」显隐**：没有窗口时它恰恰最有用——直接把原因写出来，
  而不是让人去翻任务日志猜。

**为什么序号从 `request_body` 里解析**：`aiops_event` 是发送队列，只把投递必需的东西落成列
（类型、幂等键、状态），事件内容整体存 JSON 快照，**没有 `sequence` 列**。
展示层需要序号和主机时读快照即可，不为展示去改表结构——加列意味着一次手工 DDL，
而快照本来就是原始报文，解析它比多一列更不容易失真。

### 3.6 降级策略

| 场景 | 行为 |
| --- | --- |
| `enabled=false` | 所有联动逻辑短路：不建窗、不推事件、不注册定时任务 |
| `environment.aiops_enabled=0` | 跳过建窗（任务日志留一行「环境「X」未开启 AIops 联动，跳过静默窗」），发布照常 |
| 建窗失败 + `fail-open=true`（默认） | 记一条 `【注意】AIops 不可用，本次发布不静默` 到任务日志，发布继续 |
| 建窗失败 + `fail-open=false` | 抛异常阻断发布，仅用于「发布必须留痕」的严格环境 |
| 关窗失败 | 只记日志、不抛异常：窗口自带过期时间，最坏多静默几分钟 |
| 事件推送失败 | 落库置 `status=2` 并排重试；**绝不影响发布结论** |
| 事件组装异常 | `AiopsEventPublisher.publish` 内部 catch 全部异常，只留 WARN |

> 这一组取舍对应容灾底线：**服务可用性高于平台功能完整**。AIops 挂了不能连带让版本发不出去。

## 4. 记录粒度：一次发布到底落几条

### 4.1 作用

这一节专门回答一个必然被问到的问题：**「发布一次，为什么库里是好几条记录？」**
不写清楚，看数据的人会把 5 条事件当成「重复上报」，进而去查一个不存在的重复投递 bug；
或者反过来，以为一次发布只该有 1 条，把事件流水当冗余表想删掉。

它同时是**统计口径的依据**：要算「这个月发布失败了几台机器」，得知道该数
`event_type=DEPLOYMENT_HOST_FAILED` 的行，而不是数 `deployment` 的行。

### 4.2 实测：一次单目标发布（`f8a93cf5…`，2026-11-02 02:31）

| 表 | 条数 | 表类型 | 为什么是这个数 |
| --- | --- | --- | --- |
| `deployment` | **1** | 状态型 | 一次发布就是一个对象，字段原地改 |
| `deployment_target` | **1** | 状态型 | 目标快照，每台目标一行（本次 N=1） |
| `ops_deploy_silence`（AIops） | **1** | 状态型 | 一个窗口就是一个资源，生命周期在同一行内演进 |
| `aiops_event`（平台） | **5** | 流水型 | 本次发生了 5 个事件（见 4.3） |
| `ops_deploy_event`（AIops） | **5** | 流水型 | 与平台侧一一对应，`eventDbId` 回填在 `response_body` 里可对账 |
| `lds_task_log` | **30** | 流水型 | 任务日志按行追加，与事件无关 |

### 4.3 两类表，两种语义

- **状态型**（`deployment` / `deployment_target` / `ops_deploy_silence`）：
  一个业务对象固定一行，字段被反复改写。静默窗是最典型的——`start_time`、`expire_time`、
  `close_time`、`close_reason`、`status` 全在**同一行**上变化，所以窗口永远只有 1 条。
- **流水型**（`aiops_event` / `ops_deploy_event` / `lds_task_log`）：
  append-only，发生过什么就写一行，**每行代表一个时间点**，永不改写（只更新投递状态）。

**把这两类混为一谈，就会得出「5 条是重复」的错误结论。**

### 4.4 条数公式

```
静默窗条数  = 1                                    （开出来就有，没开就是 0）
事件条数    = 4 + N                                （N = 本次目标机数）
  ├─ 部署级固定 4 条：PREPARED / STARTED / 终态 / SILENCE_CLOSED
  └─ 主机级 N 条：每台目标一条 HOST_SUCCESS 或 HOST_FAILED
```

| 场景 | N | 事件条数 | 明细 |
| --- | --- | --- | --- |
| 单目标发布成功 | 1 | 5 | PREPARED、STARTED、HOST_SUCCESS、SUCCEEDED、SILENCE_CLOSED |
| 3 台滚动发布成功 | 3 | 7 | PREPARED、STARTED、HOST_SUCCESS×3、SUCCEEDED、SILENCE_CLOSED |
| 2 台全量发布，1 台失败 | 2 | 7 | PREPARED、STARTED、HOST_SUCCESS、HOST_FAILED、FAILED、SILENCE_CLOSED（+1 条被跳过的目标不产生事件） |
| 环境开关关闭 | 1 | 4 | 无静默窗，故无 PREPARED、无 SILENCE_CLOSED |
| 总闸 `enabled=false` | 1 | 0 | 全链路短路，`aiops_event` 一行都不写 |

> **「一次发布一条」在单目标下看起来成立，在多目标下必然不成立**——这就是当初不合并的设计原因。

### 4.5 为什么事件不做成一条

如果硬把 5 个时间点压成一条（比如用 JSON 数组字段存时间线），要付出四个代价：

1. **无法按类型统计**：算「HOST_FAILED 多少次」要先解析 JSON，索引也用不上；
2. **无法单独重试**：一条投递失败就得整包重发，对端看到的时序会乱；现在的补偿任务精确到单条事件；
3. **并发追加会丢数据**：多目标并行发事件时，「读-改-写」同一行必然相互覆盖；
4. **单点失败放大**：append-only 最坏丢一行，压成一条最坏丢整段历史。

反过来，事件多条的代价只是「行数变多」：按一年 1000 次发布、平均 5 条算，约 5000 行/年，
对 MySQL 完全不构成负担。

### 4.6 幂等键：靠它保证「不多不少」

| 级别 | 公式 | 例子 |
| --- | --- | --- |
| 部署级 | `{部署ID}:{事件类型}` | `f8a93cf5…:DEPLOYMENT_STARTED` |
| 主机级 | `{部署ID}:{事件类型}:{serverId}` | `f8a93cf5…:DEPLOYMENT_HOST_SUCCESS:908f73d9…` |
| 静默窗 | `light-deploy:silence:{部署ID}` | `light-deploy:silence:f8a93cf5…` |

- **同一条事件重推不会多一行**：`event_id` 换新、`idempotency_key` 不变，对端按 key 判重
  （回执里 `duplicated=true`），平台侧也不再插新行。
- **同一类型、不同主机天然是多行**：key 里带了 `serverId`，它们是不同的事实，不是重复。
- **窗口幂等键固定**：重复建窗对端回放原记录，不会开出第二个窗口。

### 4.7 由此确定的查询口径

| 想知道什么 | 怎么查 |
| --- | --- |
| 这次发布做过什么（时间线） | `aiops_event where deployment_id=? order by sequence` |
| 某台机器什么时候升级的 | `aiops_event where deployment_id=? and server_id=?` |
| 月度失败机器数 | `count(*) where event_type='DEPLOYMENT_HOST_FAILED'` |
| 事件有没有漏投 | `aiops_event.status in (0,2)`；已送达的对端 `ops_deploy_event` 按 `idempotency_key` 对账 |
| 静默窗还生效吗 | `ops_deploy_silence` 单行 + 实时算过期，**不要只看 `status` 列**（过期不落库） |

> **保留策略（未做）**：`aiops_event` / `ops_deploy_event` 目前没有清理任务，会随发布次数线性增长。
> 量级可控（万级），但长期跑建议补「按 deployment 保留 N 天」的清理，注意**不能按 `expire-hours` 清**
> ——那个配置只管「重试有效期」，不是留存期。

## 5. 事件队列与补偿

表 `aiops_event`，状态：`0 待发送 / 1 成功 / 2 失败（待重试）/ 3 已放弃`。

- **双键不可混用**：`event_id` 是本次投递标识（HTTP `Idempotency-Key`，重试时保持不变）；
  `idempotency_key` 是业务幂等键（`部署ID:事件类型[:服务器ID]`）。
  混用会导致重试时用旧 `event_id` 发新事件，对端把两条当成一条。
- **退避**：5s → 30s → 2m → 10m → 30m，最多 `retry-max=5` 次。
- **过期**：超过 `expire-hours=2` 不再补发（发布早已结束，补发只会污染对端时序），直接置 `3 已放弃`。
- **扫描**：`AiopsRetryScheduler` 每 `retry-interval-seconds`（默认 30s）扫一批（≤50 条），
  条件 `status IN (0,2) AND next_retry_time <= now`。
- **两个字段用 `updateStrategy = FieldStrategy.ALWAYS`**：`next_retry_time`、`error_message`
  在成功时必须真的被清成 NULL。默认 NOT_NULL 策略会跳过 null 字段，
  让「已成功」的事件仍挂着一个过去的重试时间，排查时极易误判成「还在排队」。

## 6. 接口契约

**整条链路只有这一个方向：平台调 AIops。** AIops 不回调平台，平台也不对 AIops 开放任何服务间接口——
发布的真值只在平台侧，静默与事件是平台单方向推过去的。

前缀 `/openapi/v1`，鉴权头：

```
Authorization: Bearer <DEPLOY_OPENAPI_TOKEN>
X-Service-Id: light-deploy
```

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/health` | 连通性探测，返回 `{status,service,time,caller}` |
| POST | `/silences` | 建静默窗（幂等） |
| GET | `/silences/{id}` | 查静默窗详情 |
| POST | `/silences/{id}/close` | 关静默窗（已关闭时幂等回放） |
| PATCH | `/silences/{id}` | 延长到期时间（受硬上限截断） |
| POST | `/deployment-events` | 上报发布事件（幂等） |

响应外壳与平台一致：`{code,message,totalCount,data}`，**业务失败也是 HTTP 200**，必须看 `code`。
平台侧 `AiopsClient.unwrap` 就是为此存在——只看 HTTP 状态码会把「令牌错误」当成成功。

建窗请求示例：

```json
{
  "idempotencyKey": "light-deploy:silence:7fe64f4d376e4e9690d058e0d0c2b2f5",
  "reason": "发布中：权限工具(auth-tool) 由 admin 发起发布",
  "scope": {
    "projectCode": "auth-tool",
    "environmentCode": "lx-prod",
    "servers": [{ "serverId": "908f73d9…", "host": "47.103.12.12" }]
  },
  "alertCategories": ["服务异常"],
  "neverSilence": ["系统资源", "内核", "网络", "MySQL连接池", "MySQL慢查询"],
  "startAt": "2026-11-02T00:51:02+08:00",
  "expiresAt": "2026-11-02T01:31:02+08:00"
}
```

时间一律带时区偏移的 ISO8601。平台固定用东八区，**不用系统默认时区**——服务器时区被误改成 UTC 时，静默窗不该跟着漂 8 小时。

事件类型（`eventType`）：

| 编码 | 触发点 |
| --- | --- |
| `DEPLOYMENT_PREPARED` | 静默已建、即将下发（降级时也发，用来标记「一次发布开始了」） |
| `DEPLOYMENT_STARTED` | 首个目标开始执行 |
| `DEPLOYMENT_HOST_SUCCESS` | 单台目标成功 |
| `DEPLOYMENT_HOST_FAILED` | 单台目标失败 |
| `DEPLOYMENT_SUCCEEDED` | 整体成功 |
| `DEPLOYMENT_FAILED` | 整体失败 |
| `DEPLOYMENT_SILENCE_CLOSED` | 静默窗已关闭 |
| `DEPLOYMENT_ROLLBACK_SUCCEEDED` | 回滚完成且健康检查通过 |
| `DEPLOYMENT_ROLLBACK_FAILED` | 回滚后仍不健康（必须恢复真实告警并升级） |
| `DEPLOYMENT_CANCELLED` | 人工取消 |

报文里 `status` 只放固定短词（`PREPARED/RUNNING/SUCCESS/FAILED/CLOSED/ROLLED_BACK/CANCELED`）——
对端该列限长 30 字符，失败原因在平台侧部署详情里看，不靠这个字段传递。

## 7. 配置

### 7.1 平台 `application.yaml` → `light-deploy.aiops`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关，关闭时全部短路 |
| `base-url` | `http://127.0.0.1:2029` | AIops 地址 |
| `service-token` | `${LIGHT_DEPLOY_AIOPS_TOKEN:}` | 调 AIops 的令牌，只从环境变量注入 |
| `service-id` | `light-deploy` | 调用方标识 |
| `connect-timeout-ms` / `read-timeout-ms` | 3000 / 5000 | 内网调用超时 |
| `retry-max` | 5 | 事件最大重试次数 |
| `retry-interval-seconds` | 30 | 补偿扫描间隔 |
| `expire-hours` | 2 | 事件有效期 |
| `fail-open` | `true` | 建窗失败是否继续发布 |
| `silence-ttl-minutes` | 40 | 静默基准时长 |
| `alert-categories` | `[服务异常]` | 静默的告警类型 |
| `never-silence` | 系统资源/内核/网络/MySQL连接池/MySQL慢查询 | 显式声明不静默项 |

> 以上是**一级总闸**。真正决定某次发布建不建窗的是环境上的 **二级开关**
> `environment.aiops_enabled`（见 §3.4），两者是「与」关系。

### 7.2 环境级开关

| 位置 | 列 | 默认 | 说明 |
| --- | --- | --- | --- |
| 环境管理页面 · AIops联动开关 | `environment.aiops_enabled` | `0`（关闭） | `1` 才在该环境的发布/回滚期间建静默窗 |
| API | `EnvironmentDto.aiopsEnabled` | — | `POST /environment/create`、`PUT /environment/update/{id}` 携带 |

存量环境**默认全为 0**（历史数据没配过），所以升级后必须逐个环境手动打开，否则总闸开着也不会有窗口——
这是最容易误判成「功能没做」的地方，排障时先查这一列。

### 7.3 AIops `.env`

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `DEPLOY_OPENAPI_TOKEN` | `light-deploy-dev-token` | 生产必须覆盖 |
| `DEPLOY_OPENAPI_SERVICE_IDS` | `light-deploy,manual` | 调用方白名单，逗号分隔 |
| `DEPLOY_OPENAPI_IP_WHITELIST` | 空 | 公网部署必须配置 |
| `DEPLOY_SILENCE_MAX_MINUTES` | 120 | 静默硬上限，平台请求无法突破 |
| `DEPLOY_SILENCE_EXCLUDE_TYPES` | `内核` | 类型级硬排除 |
| `DEPLOY_SILENCE_EXCLUDE_KEYWORDS` | `磁盘,disk,Disk,SSH,ssh,连接失败,数据库,MySQL` | 内容级硬排除 |

## 8. 数据库

| 库 | 表 | 说明 | 脚本 |
| --- | --- | --- | --- |
| `deploy` | `aiops_event` | 事件队列 | `db/upgrade-aiops-event.sql`（校验 `db/verify-aiops-event.sql`） |
| `ai_ops` | `ops_deploy_silence` | 静默窗 | `docs/sql/deploylink.sql` |
| `ai_ops` | `ops_deploy_event` | 发布事件留存 | `docs/sql/deploylink.sql` |

`deployment.aiops_silence_id` 列在更早的 schema 里就存在（原本只占位），本次开始真正写入，
**没有新增列，不需要 ALTER**。关窗后刻意不清空该列——留痕「这次发布用过哪个窗口」。

## 9. 运维手册

### 9.1 首次开启

1. 两侧建表：平台执行 `upgrade-aiops-event.sql`，AIops 执行 `docs/sql/deploylink.sql`；
2. 两侧配令牌：AIops `DEPLOY_OPENAPI_TOKEN=<随机串>`，平台 `LIGHT_DEPLOY_AIOPS_TOKEN=<同一个串>`；
3. 平台 `light-deploy.aiops.enabled: true`，`base-url` 指向 AIops；
4. 重启平台，看日志有无 `AIops` 相关失败；
5. 连通性自测：
   ```bash
   curl -H "Authorization: Bearer <令牌>" -H "X-Service-Id: light-deploy" \
        http://<aiops>:2029/openapi/v1/health
   ```
   预期 `{"code":200,...,"data":{"status":"UP",...}}`。

### 9.2 验证一次发布是否真的静默

发布后按顺序看三处，任何一处缺失都能定位到环节：

1. **平台任务日志**：应有 `已开静默窗 <id>，覆盖 N 台目标机，到期 …` 与 `已关闭 AIops 静默窗（<id>）`；
2. **平台 `deployment.aiops_silence_id`**：非空（幂等键里带 deploymentId，可直接反查）；
3. **AIops `ops_deploy_silence`**：`external_key = light-deploy:silence:<deploymentId>`，
   `hosts` 是目标机 IP，`status=1` 且 `close_reason` 为「发布成功/失败/回滚/人工取消」。

再看事件：`aiops_event`（平台，`status=1` 表示已送达）/ `ops_deploy_event`（AIops，按 `deployment_id` 查）。

### 9.3 临时关闭

**只想让某个环境不建窗**：环境管理里关掉该环境的 AIops联动开关即可，不用重启平台，也不影响其他环境。
适合「今天这个业务要连夜发十几版，别压告警」这类临时诉求。

**想整体停掉联动**：把 `light-deploy.aiops.enabled` 置 false 并重启：不建窗、不推事件、不注册定时任务。
已开的窗口**不会**因此关闭，会按自身过期时间失效；要立刻恢复通知就手工关：

```bash
curl -X POST -H "Authorization: Bearer <令牌>" -H "X-Service-Id: light-deploy" \
     -H "Content-Type: application/json" -d '{"reason":"人工关闭"}' \
     http://<aiops>:2029/openapi/v1/silences/<silenceId>/close
```

### 9.4 故障处理

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 任务日志出现 `【注意】AIops 不可用，本次发布不静默` | 网络/令牌/对端未起 | 发布不受影响；修好 AIops 后手动补建不必做，下次发布自动生效 |
| 任务日志出现 `环境「X」未开启 AIops 联动` | 二级开关没打开（存量环境默认全 0） | 环境管理里打开该环境开关；这不是故障，是配置 |
| 总闸开着、环境也开着，日志仍说未启用 | 平台进程读的还是旧配置（改 yaml 没重启） | 重启平台；确认日志启动行里 `light-deploy.aiops.enabled` 的实际取值 |
| 告警在窗口期内仍被推送 | ①类型不在 `alert-categories`；②主机不匹配；③命中硬排除（磁盘/SSH/数据库） | 查 AIops `ops_deploy_silence.hosts` 与实际告警主机；硬排除是底线，不要试图绕过 |
| 窗口关了但通知没恢复 | 关窗请求失败（日志有 WARN） | 等自动过期，或执行 8.3 的手工关窗命令 |
| `aiops_event` 堆积 `status=2` | AIops 长时间不可用 | 修好后等补偿扫描自动补发；超过 2 小时的事件会自动置 `3 已放弃`（不会无限重试） |
| 补发成功但 `next_retry_time` 仍有值 | 老版本 bug（已修，见 §5） | 升级后新事件不再出现 |
| 发布完成后 AIops 侧无事件 | `enabled=false`，或 `aiops_event.request_body` 为空 | 先确认开关；再查 `aiops_event` 是否有记录与 `error_message` |

## 10. 本次联调实测记录（2026-11-02）

环境：平台 `127.0.0.1:2027`（`context-path=/deploy`），AIops 联调实例 `127.0.0.1:2039`，MySQL/Redis 本机。

### 10.1 一次真实发布（含失败）的完整链路

被测部署：`auth-tool` / `lx-prod` / 目标 `47.103.12.12` / 版本 `auth-tool-v16-c8a298f4`。

平台任务日志（节选）：

```
INFO | 已开静默窗 26326262184，覆盖 1 台目标机，到期 2026-11-02T01:31:02
INFO | 开始在 阿里云轻量服务器(47.103.12.12) 上发布 auth-tool-v16-c8a298f4
ERROR| 目标 阿里云轻量服务器 发布失败：docker compose up 失败：… bind: address already in use（7888 端口被占用）
INFO | 已关闭 AIops 静默窗（26326262184），告警通知恢复正常
```

AIops `ops_deploy_silence`：

```
id=26326262184  external_key=light-deploy:silence:7fe64f4d…  project=auth-tool  env=lx-prod
server_ids=["908f73d9…"]  hosts=["47.103.12.12"]  alert_categories=["服务异常"]  source=light-deploy
status=1  start=00:51:02  expire=01:31:02  close=00:51:49  close_reason=发布失败
```

平台 `aiops_event`：5 条全部 `status=1`（PREPARED / STARTED / HOST_FAILED / FAILED / SILENCE_CLOSED）。
AIops `ops_deploy_event` 按 `sequence` 落库：

| sequence | event_type | server | host | version | previous_version |
| --- | --- | --- | --- | --- | --- |
| 1 | DEPLOYMENT_PREPARED | - | - | auth-tool-v16-c8a298f4 | - |
| 2 | DEPLOYMENT_STARTED | - | - | auth-tool-v16-c8a298f4 | - |
| 3 | DEPLOYMENT_HOST_FAILED | 908f73d9… | 47.103.12.12 | auth-tool-v16-c8a298f4 | auth-tool-v15-c8a298f4 |
| 4 | DEPLOYMENT_FAILED | - | - | auth-tool-v16-c8a298f4 | - |
| 5 | DEPLOYMENT_SILENCE_CLOSED | - | - | auth-tool-v16-c8a298f4 | - |

> 这次失败是真实故障（目标机 7888 端口被其他容器占用），不是构造的用例。
> 它恰好验证了失败路径：单机失败 → 整体失败 → 关窗，三步齐全。

### 10.2 静默匹配 6 用例（全 PASS）

| 用例 | 期望 | 实际 |
| --- | --- | --- |
| 按主机命中（服务器 ID 不同、主机相同） | 命中 | PASS |
| 按服务器 ID 命中 | 命中 | PASS |
| 主机与 ID 都不匹配 | 不命中 | PASS |
| 类型不在静默清单 | 不命中 | PASS |
| 内容含「磁盘使用率 96%」 | 不命中（硬排除） | PASS |
| 内容含「SSH 连接失败」 | 不命中（硬排除） | PASS |

### 10.3 补偿任务

构造一条 `status=2 / retry_count=1 / next_retry_time=过去` 的事件，等待一次扫描（间隔 10s）：

- 平台：`status=2 → 1`，`next_retry_time`、`error_message` 均被清成 NULL，`response_body` 记录对端回执；
- AIops：`ops_deploy_event` 按 `idempotency_key` 新增一条，`duplicated=false`。

### 10.4 两级开关判定（7 用例，全 PASS）

`AiopsSilenceServiceTests`（Mockito，不碰 HTTP/SSH）：

| 用例 | 期望 | 实际 |
| --- | --- | --- |
| 总闸 `enabled=false` | 跳过，且不查 environment/目标机、不调对端 | PASS |
| 环境 `aiops_enabled=0` | 跳过，文案含环境名，`degraded=false` | PASS |
| 环境 `aiops_enabled=NULL`（历史数据） | 按关闭处理，不拆箱 NPE | PASS |
| 环境记录不存在 | 跳过，不继续查目标机 | PASS |
| 部署已有 `aiops_silence_id` | 幂等短路，不查环境、不重建窗 | PASS |
| 两级都开 | 建窗、回填窗口 ID、请求带环境编码与主机 | PASS |
| 两级都开但建窗失败 + `fail-open=true` | `degraded=true`，不写窗口 ID、不阻断发布 | PASS |

未做的验证：没有为了这批用例重启在线实例做真实发布——两级开关的分支是纯早返回，
真实建窗/关窗链路已在 §10.1 用联调实例跑过；如需现场确认，把某环境 `aiops_enabled` 置 1
后重启平台发一次，任务日志首行即可看到「已开静默窗」或「环境「X」未开启 AIops 联动」。

## 11. 已知边界

1. 静默窗粒度为「主机 + 告警类型」，不精确到单个指标或服务实例。
2. 关窗是「发布结论落库后立即关闭」，没有观察期：健康检查通过即认为发布稳定，
   之后应用再崩会正常触发告警——这是有意为之，观察期会把真故障也一起静默掉。
3. 平台单实例假设：`AiopsRetryScheduler` 未做分布式锁。若将来多实例部署，
   需要给补偿任务加 Redis 锁，否则同一批事件会被重复投递（对端幂等能兜住，但会产生无谓日志）。
4. 联动是单向的：AIops 无法反查平台。运维想知道「这条告警是哪次发布引起的」，
   靠 AIops 侧的静默窗与事件表（`external_key` 带 deploymentId、事件带版本与主机）自己拼，
   或到平台按 deploymentId 查。这是刻意取舍，不是能力缺失。

## 12. 附录：事件类型比对脚本

事件类型的中文短名只存在两个后端（跨仓库无法共享代码）：

| 位置 | 文件 |
| --- | --- |
| 平台侧（权威） | `src/main/java/com/lx/deploy/aiops/model/AiopsEventType.java` 的 `getLabel()` |
| AIops 侧 | `aiops/deploylink/constants.py` 的 `EVENT_TYPE_OPTIONS` |

**编码以平台为准**（对端按 code 落库），中文名只是展示产物。新增/改名事件类型时两处都要改，
改完跑一次比对确认没有漂移：

```bash
python3 - <<'PY'
import io, re
java = io.open('src/main/java/com/lx/deploy/aiops/model/AiopsEventType.java', encoding='utf-8').read()
py = io.open('/Users/lixiong/py_work/aiops-platform/aiops/deploylink/constants.py', encoding='utf-8').read()
j = dict(re.findall(r'[A-Z_]+\(\s*"([A-Z_]+)"\s*,\s*"([^"]+)"\s*\)', java))
p = dict(re.findall(r'\("([A-Z_]+)",\s*"([^"]+)"\)', re.search(r'EVENT_TYPE_OPTIONS.*?= \[(.*?)\]', py, re.S).group(1)))
print("编码集合相同:", set(j) == set(p))
print("中文名不一致:", {k: (j.get(k), p.get(k)) for k in set(j) | set(p) if j.get(k) != p.get(k)} or "无")
PY
```

期望输出：`编码集合相同: True` + `中文名不一致: 无`（2026-11-02 实测 10 条全对）。

> 前端不写第三份映射：两个页面（平台联动抽屉、AIops 事件页）都渲染后端下发的 `eventTypeLabel`。
