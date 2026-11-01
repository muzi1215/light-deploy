-- =============================================================================
-- verify-aiops-event.sql（只读校验）
-- 配合 upgrade-aiops-event.sql 使用，逐项输出「实际值 vs 期望值」的比对结论。
--
-- 【执行方式】
--   USE <db_name>;
--   SOURCE src/main/resources/db/verify-aiops-event.sql;
--
-- 【结果阅读】
--   每行最后一列为结论：OK 表示与期望一致；NEED_FIX 表示仍需执行升级脚本；
--   TABLE_MISSING 表示表尚未创建（先执行 upgrade-aiops-event.sql 或 schema.sql）。
-- =============================================================================

-- 结果集一：表是否存在。
SELECT
    'aiops_event' AS table_name,
    CASE WHEN COUNT(*) = 1 THEN 'OK' ELSE 'TABLE_MISSING' END AS conclusion
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aiops_event';

-- 结果集二：关键列是否齐全（缺任何一列，事件队列都会在运行期报 SQL 错误）。
SELECT
    t.column_name,
    CASE WHEN c.COLUMN_NAME IS NULL THEN 'NEED_FIX（列不存在，请执行 upgrade-aiops-event.sql）' ELSE 'OK' END
        AS conclusion
FROM (
    SELECT 'deployment_id' AS column_name
    UNION ALL SELECT 'event_id'
    UNION ALL SELECT 'event_type'
    UNION ALL SELECT 'idempotency_key'
    UNION ALL SELECT 'request_body'
    UNION ALL SELECT 'status'
    UNION ALL SELECT 'retry_count'
    UNION ALL SELECT 'next_retry_time'
) t
LEFT JOIN information_schema.COLUMNS c
       ON c.TABLE_SCHEMA = DATABASE()
      AND c.TABLE_NAME = 'aiops_event'
      AND c.COLUMN_NAME = t.column_name;

-- 结果集三：幂等唯一键是否存在（缺了它，并发重试会产生重复事件）。
SELECT
    s.INDEX_NAME AS index_name,
    CASE WHEN s.INDEX_NAME IS NULL THEN 'NEED_FIX（缺唯一键 uk_aiops_event_key）' ELSE 'OK' END AS conclusion
FROM (SELECT 1) dummy
LEFT JOIN information_schema.STATISTICS s
       ON s.TABLE_SCHEMA = DATABASE()
      AND s.TABLE_NAME = 'aiops_event'
      AND s.INDEX_NAME = 'uk_aiops_event_key'
LIMIT 1;

-- 结果集四：当前积压情况（只读，用于确认补偿任务是否在工作）。
SELECT
    status,
    COUNT(*)                                                      AS total,
    SUM(CASE WHEN next_retry_time IS NOT NULL THEN 1 ELSE 0 END)   AS pending_retry,
    MIN(create_time)                                              AS earliest
FROM aiops_event
WHERE is_deleted = 0
GROUP BY status;
