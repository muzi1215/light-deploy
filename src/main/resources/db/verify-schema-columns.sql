/*
 * 列定义对齐校验脚本（只读，不修改任何数据，可随时执行）。
 *
 * 作用：逐列比对数据库里的实际类型 / 注释 / 索引与 schema.sql 的期望定义，用于确认
 *       upgrade-status-to-numeric.sql、upgrade-server-ciphertext-to-text.sql、
 *       upgrade-base-time-to-datetime.sql 是否已执行到位。
 *
 * 覆盖范围：所有状态 / 结果列（status、result、health_status）、状态列上的索引、
 *           server.ciphertext（SSH 凭据密文，要求 TEXT），
 *           以及全部业务表的 create_time / update_time（要求 DATETIME + ON UPDATE）。
 *
 * 执行方式（<库名> 换成实际数据库名，必须带上库名，脚本依赖 DATABASE()）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-schema-columns.sql
 *
 * 结论列含义：
 *   OK           类型与注释（或索引列）都已一致，无需处理；
 *   需要迁移     仍是字符串类型或注释过旧，执行对应的 upgrade 脚本；
 *   索引缺失 / 索引列不匹配   状态列上的索引被旧列连带删除或裁剪，执行 upgrade-status-to-numeric.sql；
 *   列缺失 / 需要迁移（第三段）  时间列类型不是 DATETIME 或缺列，执行 upgrade-base-time-to-datetime.sql；
 *   表不存在     该模块尚未建表，跳过即可（脚本对该表段会自动忽略）。
 */

SELECT e.table_name AS `表`,
       e.column_name AS `列`,
       COALESCE(c.COLUMN_TYPE, '-') AS `实际类型`,
       e.expected_type AS `期望类型`,
       COALESCE(c.COLUMN_COMMENT, '-') AS `实际注释`,
       e.expected_comment AS `期望注释`,
       CASE
           WHEN c.COLUMN_NAME IS NULL THEN '表不存在'
           WHEN c.COLUMN_TYPE = e.expected_type AND c.COLUMN_COMMENT = e.expected_comment THEN 'OK'
           ELSE '需要迁移'
       END AS `结论`
FROM (
              SELECT 'user' AS table_name, 'status' AS column_name,
                     'tinyint' AS expected_type, '用户状态：0停用，1启用，2锁定' AS expected_comment
    UNION ALL SELECT 'server', 'status', 'tinyint', '服务器状态：0停用，1启用'
    UNION ALL SELECT 'lds_task', 'status', 'tinyint', '任务状态：0排队中，1执行中，2成功，3失败，4已取消'
    UNION ALL SELECT 'registry', 'status', 'tinyint', '状态：0停用，1启用'
    UNION ALL SELECT 'project', 'status', 'tinyint', '状态：0停用，1启用'
    UNION ALL SELECT 'project_environment_target', 'status', 'tinyint', '状态：0停用，1启用'
    UNION ALL SELECT 'build', 'status', 'tinyint', '构建状态：0排队中，1构建中，2成功，3失败'
    UNION ALL SELECT 'release_record', 'status', 'tinyint', '版本状态：0构建中，1可发布，2已废弃'
    UNION ALL SELECT 'deployment', 'status', 'tinyint',
              '部署状态：0排队中，1发布中，2成功，3失败，4已取消，5回滚中，6已回滚'
    UNION ALL SELECT 'deployment_target', 'status', 'tinyint',
              '目标任务状态：0排队中，1发布中，2成功，3失败，4已取消，5已跳过'
    UNION ALL SELECT 'deployment_target', 'health_status', 'tinyint', '健康检查状态：0未检查，1健康，2不健康'
    UNION ALL SELECT 'aiops_event', 'status', 'tinyint', '调用状态：0待发送，1成功，2失败，3已放弃'
    UNION ALL SELECT 'audit_log', 'result', 'tinyint', '操作结果：0失败，1成功'
    -- 早期建库该列是 VARCHAR(130)，私钥密文会被拒绝或截断，必须为 TEXT
    UNION ALL SELECT 'server', 'ciphertext', 'text', 'AES-GCM加密后的SSH密码或私钥内容'
) e
LEFT JOIN information_schema.COLUMNS c
       ON c.TABLE_SCHEMA = DATABASE()
      AND c.TABLE_NAME = e.table_name
      AND c.COLUMN_NAME = e.column_name
ORDER BY CASE
             WHEN c.COLUMN_NAME IS NULL THEN 2
             WHEN c.COLUMN_TYPE = e.expected_type AND c.COLUMN_COMMENT = e.expected_comment THEN 1
             ELSE 0
         END,
         e.table_name,
         e.column_name;

-- 第二个结果集：状态列上应有的索引是否完整。
-- DROP COLUMN 会删除该列上的单列索引、并把多列索引裁剪掉这一列，
-- 因此迁移后必须确认索引列与 schema.sql 一致，否则查询会退化成全表扫描。
SELECT e.table_name AS `表`,
       e.index_name AS `索引`,
       e.expected_columns AS `期望列`,
       COALESCE(a.actual_columns, '-') AS `实际列`,
       CASE
           WHEN t.TABLE_NAME IS NULL THEN '表不存在'
           WHEN a.actual_columns IS NULL THEN '索引缺失'
           WHEN a.actual_columns = e.expected_columns THEN 'OK'
           ELSE '索引列不匹配'
       END AS `结论`
FROM (
              SELECT 'user' AS table_name, 'idx_user_status' AS index_name, 'status' AS expected_columns
    UNION ALL SELECT 'server', 'idx_server_status', 'status'
    UNION ALL SELECT 'lds_task', 'idx_lds_task_status', 'status'
    UNION ALL SELECT 'project', 'idx_project_status', 'status'
    UNION ALL SELECT 'project_environment_target', 'idx_target_server', 'server_id,status'
    UNION ALL SELECT 'deployment', 'idx_deployment_status', 'status'
    UNION ALL SELECT 'deployment_target', 'idx_deployment_target_server', 'server_id,status'
) e
LEFT JOIN information_schema.TABLES t
       ON t.TABLE_SCHEMA = DATABASE()
      AND t.TABLE_NAME = e.table_name
LEFT JOIN (
    SELECT TABLE_NAME,
           INDEX_NAME,
           GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS actual_columns
    FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
    GROUP BY TABLE_NAME, INDEX_NAME
) a
       ON a.TABLE_NAME = e.table_name
      AND a.INDEX_NAME = e.index_name
ORDER BY CASE
             WHEN t.TABLE_NAME IS NULL THEN 2
             WHEN a.actual_columns = e.expected_columns THEN 1
             ELSE 0
         END,
         e.table_name,
         e.index_name;

-- 第三个结果集：基础时间列 create_time / update_time 必须是 DATETIME，且 update_time 带 ON UPDATE。
-- 早期手工建库把这两列建成了 TIMESTAMP，会随会话时区隐式换算，且存在 2038 上限；
-- 出现「需要迁移」或「列缺失」时执行 upgrade-base-time-to-datetime.sql。
SELECT e.table_name AS `表`,
       t.column_name AS `列`,
       COALESCE(c.COLUMN_TYPE, '-') AS `实际类型`,
       COALESCE(c.EXTRA, '-') AS `额外属性`,
       CASE
           -- 该模块尚未建表时整表跳过，与上面两个结果集的口径保持一致
           WHEN tb.TABLE_NAME IS NULL THEN '表不存在'
           WHEN c.COLUMN_NAME IS NULL THEN '列缺失'
           WHEN c.COLUMN_TYPE <> 'datetime' THEN '需要迁移'
           -- 更新时间必须由 MySQL 自动维护，漏掉 ON UPDATE 会导致「改了数据但 update_time 不变」
           WHEN t.column_name = 'update_time'
                AND c.EXTRA NOT LIKE '%on update CURRENT_TIMESTAMP%' THEN '需要迁移'
           ELSE 'OK'
       END AS `结论`
FROM (
              SELECT 'user' AS table_name
    UNION ALL SELECT 'server'
    UNION ALL SELECT 'lds_task'
    UNION ALL SELECT 'lds_task_log'
    UNION ALL SELECT 'credential'
    UNION ALL SELECT 'registry'
    UNION ALL SELECT 'project'
    UNION ALL SELECT 'environment'
    UNION ALL SELECT 'project_environment_target'
    UNION ALL SELECT 'build'
    UNION ALL SELECT 'build_artifact'
    UNION ALL SELECT 'release_record'
    UNION ALL SELECT 'deployment'
    UNION ALL SELECT 'deployment_target'
    UNION ALL SELECT 'aiops_event'
    UNION ALL SELECT 'audit_log'
) e
CROSS JOIN (
              SELECT 'create_time' AS column_name
    UNION ALL SELECT 'update_time'
) t
LEFT JOIN information_schema.TABLES tb
       ON tb.TABLE_SCHEMA = DATABASE()
      AND tb.TABLE_NAME = e.table_name
LEFT JOIN information_schema.COLUMNS c
       ON c.TABLE_SCHEMA = DATABASE()
      AND c.TABLE_NAME = e.table_name
      AND c.COLUMN_NAME = t.column_name
ORDER BY CASE
             WHEN tb.TABLE_NAME IS NULL THEN 2
             WHEN c.COLUMN_TYPE = 'datetime'
                  AND (t.column_name = 'create_time'
                       OR c.EXTRA LIKE '%on update CURRENT_TIMESTAMP%') THEN 1
             ELSE 0
         END,
         e.table_name,
         t.column_name;
