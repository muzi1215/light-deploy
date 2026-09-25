-- =============================================================================
-- verify-server-build-env.sql（只读校验）
-- 配合 upgrade-server-build-env.sql 使用，校验 build_env_script 列是否符合期望定义。
--
-- 【执行方式】
--   USE <db_name>;
--   SOURCE src/main/resources/db/verify-server-build-env.sql;
--
-- 【结果阅读】
--   每行最后一列为结论：OK 表示与期望一致；NEED_FIX 表示需执行升级脚本或手工处理；
--   TABLE_MISSING 表示表尚未创建（先执行 schema.sql 建库）。
--   本列允许为 NULL（不配环境脚本即沿用升级前行为），因此空值不算问题，只统计有多少台机器在用。
-- =============================================================================

-- 结果集一：表是否存在。
SELECT
    'server' AS table_name,
    CASE WHEN COUNT(*) = 1 THEN 'OK' ELSE 'TABLE_MISSING' END AS conclusion
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server';

-- 结果集二：列的实际类型、可空性、注释，与 schema.sql 期望值逐项比对。
-- 期望「可空」且「无默认值」：脚本内容与机器强相关，任何默认值都是错的。
SELECT
    e.column_name                           AS column_name,
    IFNULL(c.COLUMN_TYPE, '（列不存在）')     AS actual_type,
    e.expected_type                         AS expected_type,
    IFNULL(c.IS_NULLABLE, '（列不存在）')     AS actual_nullable,
    'YES'                                   AS expected_nullable,
    IFNULL(c.COLUMN_DEFAULT, '（无默认值）')   AS actual_default,
    '（无默认值）'                            AS expected_default,
    IFNULL(c.COLUMN_COMMENT, '（列不存在）')   AS actual_comment,
    e.expected_comment                      AS expected_comment,
    CASE
        WHEN c.COLUMN_NAME IS NULL THEN 'NEED_FIX（列不存在，请执行 upgrade-server-build-env.sql）'
        WHEN LOWER(c.COLUMN_TYPE) = e.expected_type
             AND c.IS_NULLABLE = 'YES'
             AND c.COLUMN_DEFAULT IS NULL
             AND c.COLUMN_COMMENT = e.expected_comment
            THEN 'OK'
        ELSE 'NEED_FIX（类型/可空性/默认值/注释与期望不一致，请重跑升级脚本）'
    END AS conclusion
FROM (
    SELECT 'build_env_script' AS column_name,
           'varchar(255)'     AS expected_type,
           '构建环境初始化脚本，仅构建机使用：执行构建命令前在当前 shell 执行，用于引入 PATH 等环境变量（如 export PATH=/opt/apache-maven/bin:$PATH）' AS expected_comment
) e
LEFT JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = DATABASE()
   AND c.TABLE_NAME = 'server'
   AND c.COLUMN_NAME = e.column_name;

-- 结果集三：有多少台机器配置了环境脚本（NULL / 空串都视为未配置）。
-- 未配置不是错误：平台已固定执行 `. /etc/profile` 兜底，只有 profile 里也没有工具路径时才需要本字段。
SELECT
    COUNT(*)                                                     AS total_servers,
    SUM(CASE WHEN build_env_script IS NULL OR TRIM(build_env_script) = '' THEN 1 ELSE 0 END) AS without_env_script,
    SUM(CASE WHEN build_env_script IS NOT NULL AND TRIM(build_env_script) <> '' THEN 1 ELSE 0 END) AS with_env_script,
    'OK（未配置即沿用平台兜底行为）'                                AS conclusion
FROM `server`
WHERE is_deleted = 0;

-- 结果集四：列出已配置脚本的机器，便于人工核对内容是否符合该机器实际环境。
SELECT
    id,
    server_name,
    host,
    build_env_script
FROM `server`
WHERE is_deleted = 0
  AND build_env_script IS NOT NULL
  AND TRIM(build_env_script) <> ''
ORDER BY create_time DESC
LIMIT 20;

-- 索引检查：本列是配置值，不作为查询条件，期望无索引。
SELECT
    e.column_name AS column_name,
    COUNT(s.COLUMN_NAME) AS index_count,
    CASE WHEN COUNT(s.COLUMN_NAME) = 0 THEN 'OK（无需索引）'
         ELSE 'NEED_FIX（存在多余索引，请与 schema.sql 对齐）' END AS conclusion
FROM (SELECT 'build_env_script' AS column_name) e
LEFT JOIN information_schema.STATISTICS s
    ON s.TABLE_SCHEMA = DATABASE()
   AND s.TABLE_NAME = 'server'
   AND s.COLUMN_NAME = e.column_name
GROUP BY e.column_name;
