/*
 * audit_log 操作日志字段只读校验脚本（不改任何数据）。
 *
 * 用途：执行 upgrade-audit-log-operation.sql 之后，逐项确认新增列是否与 schema.sql 的
 *       当前定义一致（类型 / 可空 / 注释），以及时间索引是否已建立。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-audit-log-operation.sql
 *
 * 结果解读：
 *   结果集 1 —— 逐列比对「实际类型 / 可空 / 注释」与期望值，结论为「一致」即通过；
 *   结果集 2 —— 缺失列清单（为空表示全部到位）；
 *   结果集 3 —— 索引检查，idx_audit_create_time 应存在；
 *   结果集 4 —— 人工抽查：最近 20 条操作日志，接入切面后应有 application / title / action / 操作人，
 *                并顺带看「返回结果」列是否为空（空说明序列化仍失败，见 §26.3 的 java.time 处理）；
 *   结果集 5 —— 变更明细抽查：有 changed_fields 的记录，确认内容是 {field,oldValue,newValue} 数组；
 *   结果集 6 —— 成功请求里 json_result 为空的占比：修复 java.time 序列化后，新产生的记录不应再有空值。
 */

-- 结果集 1：逐列比对定义与期望值
SELECT
    c.COLUMN_NAME                               AS `字段`,
    c.COLUMN_TYPE                               AS `实际类型`,
    c.IS_NULLABLE                               AS `允许为空`,
    c.COLUMN_COMMENT                            AS `实际注释`,
    CASE
        WHEN c.COLUMN_NAME = 'trace_id'
            AND c.COLUMN_TYPE = 'varchar(64)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '请求追踪ID，与响应头 X-Request-Id、日志 %X{requestId} 一致' THEN '一致'
        WHEN c.COLUMN_NAME = 'application'
            AND c.COLUMN_TYPE = 'varchar(64)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '应用系统名称，来自配置 light-deploy.application-name' THEN '一致'
        WHEN c.COLUMN_NAME = 'title'
            AND c.COLUMN_TYPE = 'varchar(100)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '操作模块，来自 @OperLog 注解的 title' THEN '一致'
        WHEN c.COLUMN_NAME = 'action'
            AND c.COLUMN_TYPE = 'varchar(50)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '操作类型：INSERT/UPDATE/DELETE/EXPORT/IMPORT/EXECUTE/ROLLBACK/CANCEL/OTHER' THEN '一致'
        WHEN c.COLUMN_NAME = 'method'
            AND c.COLUMN_TYPE = 'varchar(200)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '操作方法，格式 类全名#方法名' THEN '一致'
        WHEN c.COLUMN_NAME = 'request_method'
            AND c.COLUMN_TYPE = 'varchar(10)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = 'HTTP请求方法：GET/POST/PUT/DELETE' THEN '一致'
        WHEN c.COLUMN_NAME = 'oper_ip'
            AND c.COLUMN_TYPE = 'varchar(128)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '操作IP，优先取 X-Forwarded-For 首段' THEN '一致'
        WHEN c.COLUMN_NAME = 'os'
            AND c.COLUMN_TYPE = 'varchar(64)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '操作系统，由 User-Agent 粗粒度解析' THEN '一致'
        WHEN c.COLUMN_NAME = 'browser'
            AND c.COLUMN_TYPE = 'varchar(64)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '浏览器，由 User-Agent 粗粒度解析' THEN '一致'
        WHEN c.COLUMN_NAME = 'json_result'
            AND c.COLUMN_TYPE = 'text' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '返回结果，超长截断' THEN '一致'
        WHEN c.COLUMN_NAME = 'resource_type'
            AND c.COLUMN_TYPE = 'varchar(50)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '资源类型，来自 @OperLog 的 resourceType，空表示不采集变更明细' THEN '一致'
        WHEN c.COLUMN_NAME = 'resource_id'
            AND c.COLUMN_TYPE = 'varchar(100)' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '资源主键，来自被操作方法的 id 入参' THEN '一致'
        WHEN c.COLUMN_NAME = 'cost_time'
            AND c.COLUMN_TYPE = 'int' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '操作耗时（毫秒）' THEN '一致'
        WHEN c.COLUMN_NAME = 'changed_fields'
            AND c.COLUMN_TYPE = 'json' AND c.IS_NULLABLE = 'YES'
            AND c.COLUMN_COMMENT = '字段级变更明细 [{field,oldValue,newValue}]，无变更或未声明资源类型时为空' THEN '一致'
        ELSE '不一致，请重跑 upgrade-audit-log-operation.sql'
    END                                         AS `结论`
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE()
  AND c.TABLE_NAME = 'audit_log'
  AND c.COLUMN_NAME IN ('trace_id', 'application', 'title', 'action', 'resource_type', 'resource_id', 'method',
                        'request_method', 'oper_ip', 'os', 'browser', 'json_result', 'cost_time',
                        'changed_fields')
ORDER BY c.ORDINAL_POSITION;

-- 结果集 2：缺失列清单（应为空）
SELECT x.COLUMN_NAME AS `缺失字段`, '请执行 upgrade-audit-log-operation.sql' AS `处理建议`
FROM (SELECT 'trace_id' AS COLUMN_NAME
      UNION ALL SELECT 'application'
      UNION ALL SELECT 'title'
      UNION ALL SELECT 'method'
      UNION ALL SELECT 'request_method'
      UNION ALL SELECT 'oper_ip'
      UNION ALL SELECT 'os'
      UNION ALL SELECT 'browser'
      UNION ALL SELECT 'json_result'
      UNION ALL SELECT 'cost_time'
      UNION ALL SELECT 'changed_fields') x
WHERE NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS c
                  WHERE c.TABLE_SCHEMA = DATABASE()
                    AND c.TABLE_NAME = 'audit_log'
                    AND c.COLUMN_NAME = x.COLUMN_NAME);

-- 结果集 3：索引检查
SELECT s.INDEX_NAME                                        AS `索引名`,
       s.COLUMN_NAME                                       AS `索引列`,
       CASE WHEN s.INDEX_NAME IS NULL THEN '缺失' ELSE '存在' END AS `结论`
FROM information_schema.STATISTICS s
WHERE s.TABLE_SCHEMA = DATABASE()
  AND s.TABLE_NAME = 'audit_log'
  AND s.INDEX_NAME IN ('idx_audit_create_time', 'idx_audit_operator', 'idx_audit_resource');

-- 结果集 4：人工抽查（最近 20 条操作日志）
SELECT application   AS `应用系统`,
       operator_name AS `操作人`,
       title         AS `操作模块`,
       action        AS `操作类型`,
       request_method AS `请求方式`,
       request_uri   AS `请求地址`,
       result        AS `结果（0失败，1成功）`,
       cost_time    AS `耗时ms`,
       CASE WHEN json_result IS NULL OR json_result = '' THEN '空' ELSE '有值' END AS `返回结果`,
       create_time  AS `操作时间`
FROM audit_log
WHERE is_deleted = 0
ORDER BY create_time DESC
LIMIT 20;

-- 结果集 5：变更明细抽查（只列有明细的记录，确认结构与适用范围）
SELECT title                AS `操作模块`,
       action               AS `操作类型`,
       resource_type        AS `资源类型`,
       resource_id          AS `资源主键`,
       JSON_LENGTH(changed_fields) AS `变更字段数`,
       changed_fields       AS `明细内容`,
       create_time         AS `操作时间`
FROM audit_log
WHERE is_deleted = 0
  AND changed_fields IS NOT NULL
ORDER BY create_time DESC
LIMIT 20;

-- 结果集 6：成功请求里返回结果为空的占比（修复 java.time 序列化后，新记录应趋近于 0）
SELECT COUNT(*)                                                                   AS `成功记录数`,
       SUM(CASE WHEN json_result IS NULL OR json_result = '' THEN 1 ELSE 0 END)    AS `返回结果为空`,
       MAX(create_time)                                                            AS `最近一条时间`
FROM audit_log
WHERE is_deleted = 0
  AND result = 1;
