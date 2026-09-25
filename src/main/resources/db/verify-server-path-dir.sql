-- =============================================================================
-- verify-server-path-dir.sql（只读校验）
-- 配合 upgrade-server-path-dir.sql 使用，逐项输出「实际值 vs 期望值」的比对结论。
--
-- 【执行方式】
--   USE <db_name>;
--   SOURCE src/main/resources/db/verify-server-path-dir.sql;
--
-- 【结果阅读】
--   每行最后一列为结论：OK 表示与期望一致；NEED_FIX 表示仍需执行升级脚本或手工处理；
--   TABLE_MISSING 表示表尚未创建（先执行 schema.sql 建库）。
--   结果集三额外检查既有数据：代码已不再兜底默认路径，空路径会直接导致构建或部署失败。
-- =============================================================================

-- 结果集一：表是否存在。
SELECT
    'server' AS table_name,
    CASE WHEN COUNT(*) = 1 THEN 'OK' ELSE 'TABLE_MISSING' END AS conclusion
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server';

-- 结果集二：三列的实际类型、可空性、默认值与注释，与 schema.sql 期望值逐项比对。
-- 用期望清单左连实际列，保证「列缺失」也能显示为 NEED_FIX，而不是静默不输出行。
SELECT
    e.column_name                          AS column_name,
    IFNULL(c.COLUMN_TYPE, '（列不存在）')    AS actual_type,
    e.expected_type                        AS expected_type,
    IFNULL(c.IS_NULLABLE, '（列不存在）')    AS actual_nullable,
    'NO'                                   AS expected_nullable,
    IFNULL(c.COLUMN_DEFAULT, '（列不存在）') AS actual_default,
    e.expected_default                     AS expected_default,
    IFNULL(c.COLUMN_COMMENT, '（列不存在）') AS actual_comment,
    e.expected_comment                     AS expected_comment,
    CASE
        WHEN c.COLUMN_NAME IS NULL THEN 'NEED_FIX（列不存在，请执行 upgrade-server-path-dir.sql）'
        WHEN LOWER(c.COLUMN_TYPE) = e.expected_type
             AND c.IS_NULLABLE = 'NO'
             AND c.COLUMN_DEFAULT = e.expected_default
             AND c.COLUMN_COMMENT = e.expected_comment
            THEN 'OK'
        ELSE 'NEED_FIX（类型/可空性/默认值/注释与期望不一致，请重跑升级脚本）'
    END AS conclusion
FROM (
    SELECT 'build_workspace_dir' AS column_name,
           'varchar(255)'        AS expected_type,
           '/opt/light-deploy/build-workspace' AS expected_default,
           '构建工作区根目录，仅构建机使用：每次构建在其下创建独立子目录，结束后清理' AS expected_comment
    UNION ALL SELECT 'build_artifact_dir',
           'varchar(255)',
           '/opt/light-deploy/artifacts',
           '构建产物持久目录，仅构建机使用：产物转存于此供发布阶段下载，不随工作区清理'
    UNION ALL SELECT 'agent_base_dir',
           'varchar(255)',
           '/opt/light-deploy',
           '平台在该服务器上的部署根目录，连通性测试检查其存在且可写，应用部署在其 apps 子目录下'
) e
LEFT JOIN information_schema.COLUMNS c
    ON c.TABLE_SCHEMA = DATABASE()
   AND c.TABLE_NAME = 'server'
   AND c.COLUMN_NAME = e.column_name;

-- 结果集三：既有数据的路径是否可用。
-- 代码侧已删除 @Value 兜底，空路径会在构建或部署时报校验错误，这里提前暴露需要补全的行。
SELECT
    id,
    server_name,
    build_workspace_dir,
    build_artifact_dir,
    agent_base_dir,
    CASE
        WHEN TRIM(build_workspace_dir) = '' OR TRIM(build_artifact_dir) = '' OR TRIM(agent_base_dir) = ''
            THEN 'NEED_FIX（存在空路径，请在服务器页面补全）'
        ELSE 'OK'
    END AS conclusion
FROM `server`
WHERE is_deleted = 0
ORDER BY create_time DESC
LIMIT 20;

-- 索引检查：三列都是配置值，不作为查询条件，schema.sql 中也没有为它们建索引，期望无索引。
SELECT
    e.column_name AS column_name,
    COUNT(s.COLUMN_NAME) AS index_count,
    CASE WHEN COUNT(s.COLUMN_NAME) = 0 THEN 'OK（无需索引）'
         ELSE 'NEED_FIX（存在多余索引，请与 schema.sql 对齐）' END AS conclusion
FROM (
    SELECT 'build_workspace_dir' AS column_name
    UNION ALL SELECT 'build_artifact_dir'
    UNION ALL SELECT 'agent_base_dir'
) e
LEFT JOIN information_schema.STATISTICS s
    ON s.TABLE_SCHEMA = DATABASE()
   AND s.TABLE_NAME = 'server'
   AND s.COLUMN_NAME = e.column_name
GROUP BY e.column_name;
