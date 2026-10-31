-- =============================================================================
-- verify-deployment-force-overwrite-config.sql（只读校验）
-- 配合 upgrade-deployment-force-overwrite-config.sql 使用，逐项输出「实际值 vs 期望值」的比对结论。
--
-- 【执行方式】
--   USE <db_name>;
--   SOURCE src/main/resources/db/verify-deployment-force-overwrite-config.sql;
--
-- 【结果阅读】
--   每行最后一列为结论：OK 表示与期望一致；NEED_FIX 表示仍需执行升级脚本或手工处理；
--   TABLE_MISSING 表示表尚未创建（先执行 schema.sql 建库）。
-- =============================================================================

-- 结果集一：表是否存在。
SELECT
    'deployment' AS table_name,
    CASE WHEN COUNT(*) = 1 THEN 'OK' ELSE 'TABLE_MISSING' END AS conclusion
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment';

-- 结果集二：列的实际类型、可空性、默认值与注释，与期望逐项比对。
SELECT
    c.COLUMN_NAME                                              AS column_name,
    c.COLUMN_TYPE                                              AS actual_type,
    'tinyint(1)'                                              AS expected_type,
    c.IS_NULLABLE                                             AS actual_nullable,
    'NO'                                                      AS expected_nullable,
    c.COLUMN_DEFAULT                                          AS actual_default,
    '0'                                                       AS expected_default,
    c.COLUMN_COMMENT                                          AS actual_comment,
    '部署时是否强制覆盖目标机已有配置文件：0不覆盖(跳过) 1覆盖(内容不一致则先备份)' AS expected_comment,
    CASE
        WHEN c.COLUMN_NAME IS NULL THEN 'NEED_FIX（列不存在，请执行 upgrade-deployment-force-overwrite-config.sql）'
        WHEN LOWER(c.COLUMN_TYPE) = 'tinyint(1)'
             AND c.IS_NULLABLE = 'NO'
             AND c.COLUMN_DEFAULT = '0'
             AND c.COLUMN_COMMENT = '部署时是否强制覆盖目标机已有配置文件：0不覆盖(跳过) 1覆盖(内容不一致则先备份)'
            THEN 'OK'
        ELSE 'NEED_FIX（类型/可空性/默认值/注释与期望不一致，请重跑升级脚本）'
    END AS conclusion
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE()
  AND c.TABLE_NAME = 'deployment'
  AND c.COLUMN_NAME = 'force_overwrite_config';
