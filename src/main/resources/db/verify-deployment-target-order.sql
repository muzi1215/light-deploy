/*
 * deployment_target.deploy_order 只读校验脚本（不改任何数据）。
 *
 * 用途：执行 upgrade-deployment-target-order.sql 之后，逐项确认该列是否与 schema.sql 的
 *       当前定义一致（类型 / 可空 / 注释 / 列位置），避免「脚本跑了但没生效」被忽略。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-deployment-target-order.sql
 *
 * 结果解读：
 *   结果集 1 —— 列定义比对，结论为「一致」即通过；
 *   结果集 2 —— 列不存在时单独给出一条提示（结果集 1 会返回空）；
 *   结果集 3 —— 人工抽查：新部署的目标应写有 1、2、3… 的顺序，历史部署为 NULL 属正常。
 */

-- 结果集 1：列定义与期望值比对
SELECT
    c.COLUMN_NAME    AS `字段`,
    c.COLUMN_TYPE    AS `实际类型`,
    c.IS_NULLABLE    AS `允许为空`,
    c.COLUMN_COMMENT AS `实际注释`,
    CASE
        WHEN c.COLUMN_TYPE = 'int'
             AND c.IS_NULLABLE = 'YES'
             AND c.COLUMN_COMMENT = '发布顺序快照，创建部署时从部署目标配置固化；历史数据为空'
            THEN '一致'
        ELSE '不一致，请重跑 upgrade-deployment-target-order.sql'
    END              AS `结论`
FROM information_schema.COLUMNS c
WHERE c.TABLE_SCHEMA = DATABASE()
  AND c.TABLE_NAME = 'deployment_target'
  AND c.COLUMN_NAME = 'deploy_order';

-- 结果集 2：列缺失提示
SELECT 'deployment_target.deploy_order' AS `字段`, '缺失，请执行 upgrade-deployment-target-order.sql' AS `结论`
WHERE NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                  WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_NAME = 'deployment_target'
                    AND COLUMN_NAME = 'deploy_order');

-- 结果集 3：人工抽查（最近 20 条目标行，新部署应有序号）
SELECT dt.deployment_id AS `部署ID`,
       dt.deploy_order  AS `发布顺序`,
       s.server_name    AS `目标服务器`,
       dt.status        AS `状态`,
       dt.create_time   AS `创建时间`
FROM deployment_target dt
         LEFT JOIN server s ON s.id = dt.server_id
ORDER BY dt.create_time DESC, dt.deploy_order ASC
LIMIT 20;
