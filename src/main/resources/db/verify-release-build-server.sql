-- =============================================================================
-- verify-release-build-server.sql（只读校验）
-- 配合 upgrade-release-build-server.sql 使用，逐项输出「实际值 vs 期望值」的比对结论。
--
-- 【执行方式】
--   USE <db_name>;
--   SOURCE src/main/resources/db/verify-release-build-server.sql;
--
-- 【结果阅读】
--   每行最后一列为结论：OK 表示与期望一致；NEED_FIX 表示仍需执行升级脚本或手工处理；
--   TABLE_MISSING 表示表尚未创建（先执行 schema.sql 建库）。
-- =============================================================================

-- 结果集一：表是否存在。
SELECT
    'release_record' AS table_name,
    CASE WHEN COUNT(*) = 1 THEN 'OK' ELSE 'TABLE_MISSING' END AS conclusion
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release_record';

-- 结果集二：列的实际类型、可空性与注释，与 schema.sql 期望值逐项比对。
SELECT
    c.COLUMN_NAME                                        AS column_name,
    c.COLUMN_TYPE                                        AS actual_type,
    'varchar(64)'                                        AS expected_type,
    c.IS_NULLABLE                                        AS actual_nullable,
    'YES'                                                AS expected_nullable,
    c.COLUMN_COMMENT                                     AS actual_comment,
    '产物所在构建机ID，指向server表，JAR/STATIC部署时用于SFTP下载产物' AS expected_comment,
    CASE
        WHEN c.COLUMN_NAME IS NULL THEN 'NEED_FIX（列不存在，请执行 upgrade-release-build-server.sql）'
        WHEN LOWER(c.COLUMN_TYPE) = 'varchar(64)'
             AND c.IS_NULLABLE = 'YES'
             AND c.COLUMN_COMMENT = '产物所在构建机ID，指向server表，JAR/STATIC部署时用于SFTP下载产物'
            THEN 'OK'
        ELSE 'NEED_FIX（类型/可空性/注释与期望不一致，请重跑升级脚本）'
    END AS conclusion
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE()
  AND c.TABLE_NAME = 'release_record'
  AND c.COLUMN_NAME = 'build_server_id';

-- 结果集三：本列上没有索引是否符合预期（schema.sql 未为该列建索引，因此期望为 0）。
SELECT
    'build_server_id' AS column_name,
    COUNT(*)          AS index_count,
    CASE WHEN COUNT(*) = 0 THEN 'OK（无需索引）' ELSE 'NEED_FIX（存在多余索引，请与 schema.sql 对齐）' END AS conclusion
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'release_record'
  AND COLUMN_NAME = 'build_server_id';
