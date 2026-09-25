-- =============================================================================
-- verify-task-log-level.sql（只读校验）
-- 配合 upgrade-task-log-level.sql 使用，逐项输出「实际值 vs 期望值」的比对结论。
--
-- 【执行方式】
--   USE <db_name>;
--   SOURCE src/main/resources/db/verify-task-log-level.sql;
--
-- 【结果阅读】
--   每行最后一列为结论：OK 表示与期望一致；NEED_FIX 表示仍需执行升级脚本或手工处理；
--   TABLE_MISSING 表示表尚未创建（先执行 schema.sql 建库）。
-- =============================================================================

-- 结果集一：表是否存在。
SELECT
    'lds_task_log' AS table_name,
    CASE WHEN COUNT(*) = 1 THEN 'OK' ELSE 'TABLE_MISSING' END AS conclusion
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task_log';

-- 结果集二：列的实际类型、默认值与注释，与 schema.sql 期望值逐项比对。
SELECT
    c.COLUMN_NAME                AS column_name,
    c.COLUMN_TYPE                AS actual_type,
    'varchar(10)'                AS expected_type,
    c.COLUMN_DEFAULT             AS actual_default,
    'INFO'                       AS expected_default,
    c.COLUMN_COMMENT             AS actual_comment,
    '日志级别：INFO/ERROR'        AS expected_comment,
    CASE
        WHEN c.COLUMN_NAME IS NULL THEN 'NEED_FIX（列不存在，请执行 schema.sql 建表）'
        WHEN LOWER(c.COLUMN_TYPE) = 'varchar(10)'
             AND c.COLUMN_DEFAULT = 'INFO'
             AND c.COLUMN_COMMENT = '日志级别：INFO/ERROR'
            THEN 'OK'
        ELSE 'NEED_FIX（类型/默认值/注释与期望不一致，请重跑 upgrade-task-log-level.sql）'
    END AS conclusion
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE()
  AND c.TABLE_NAME = 'lds_task_log'
  AND c.COLUMN_NAME = 'log_level';

-- 结果集三：实际取值分布，期望只剩 INFO 与 ERROR。
-- 出现 DEBUG / WARN / 空值等均属未归一化，需要重跑升级脚本。
SELECT
    log_level                    AS actual_value,
    COUNT(*)                     AS row_count,
    CASE
        WHEN log_level IN ('INFO', 'ERROR') THEN 'OK'
        ELSE 'NEED_FIX（残留旧级别，请执行 upgrade-task-log-level.sql）'
    END AS conclusion
FROM lds_task_log
GROUP BY log_level
ORDER BY row_count DESC;

-- 结果集四：本列上没有索引是否符合预期（schema.sql 未为该列建索引，因此期望为 0）。
SELECT
    'log_level' AS column_name,
    COUNT(*)    AS index_count,
    CASE WHEN COUNT(*) = 0 THEN 'OK（无需索引）' ELSE 'NEED_FIX（存在多余索引，请与 schema.sql 对齐）' END AS conclusion
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'lds_task_log'
  AND COLUMN_NAME = 'log_level';
