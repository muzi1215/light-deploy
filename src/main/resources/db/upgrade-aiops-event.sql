-- =============================================================================
-- upgrade-aiops-event.sql
-- 新增 aiops_event 表（AIops 联动事件队列）
--
-- 【为什么需要】
-- 发布静默窗与发布生命周期事件都要推给 AIops。推送是旁路动作，失败了不能反过来阻断发布，
-- 但也不能就这么丢了——「这次发布发了什么版本、哪台机器失败」是事后归因的唯一证据。
-- 因此每次推送先落库（待发送），再调对端；成功置 1，失败置 2 并排下次重试时间，
-- 由 AiopsRetryScheduler 定时补发；重试耗尽或超过有效期（默认 2 小时）置 3 已放弃。
--
-- 双键设计（不要合并）：
--   event_id        本次投递的唯一标识，作为 HTTP Idempotency-Key，重试时必须保持不变；
--   idempotency_key 业务幂等键（部署ID:事件类型[:服务器ID]），用于本地识别同一业务事件。
--   混用会导致重试时用旧 event_id 发新事件，对端把两条当成一条。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-aiops-event.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-aiops-event.sql;
--
-- 【所需权限】
--   CREATE（建表）。
--
-- 【幂等性】
--   CREATE TABLE IF NOT EXISTS：表已存在时整段跳过，可反复执行。
--
-- 【说明】
--   deployment.aiops_silence_id 列在更早的 schema.sql 中已存在（v1 仅占位），
--   本次不新增列，只是开始真正写入静默窗 ID，因此没有配套的 ALTER。
--
-- 【执行后抽查】
--   SHOW CREATE TABLE aiops_event;
--   SELECT status, COUNT(*) FROM aiops_event GROUP BY status;
--   再执行配套校验脚本：
--   SOURCE src/main/resources/db/verify-aiops-event.sql;
-- =============================================================================

CREATE TABLE IF NOT EXISTS aiops_event (
    id VARCHAR(64) NOT NULL COMMENT 'AIops事件主键',
    deployment_id VARCHAR(64) NOT NULL COMMENT '部署任务ID',
    event_id VARCHAR(128) NOT NULL COMMENT '事件ID，作为HTTP Idempotency-Key，重试保持不变',
    event_type VARCHAR(50) NOT NULL COMMENT '事件类型：DEPLOYMENT_PREPARED/STARTED/HOST_SUCCESS/...',
    idempotency_key VARCHAR(150) NOT NULL COMMENT '业务幂等键：部署ID:事件类型[:服务器ID]',
    request_body JSON NULL COMMENT '请求内容快照，重试时据此还原报文',
    response_body JSON NULL COMMENT '响应内容快照',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '调用状态：0待发送，1成功，2失败，3已放弃',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_time DATETIME NULL COMMENT '下次重试时间，空表示不需要重试',
    error_message TEXT NULL COMMENT '错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_aiops_event_key (idempotency_key, is_deleted),
    KEY idx_aiops_deployment (deployment_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AIops联动事件';
