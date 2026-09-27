/*
 * deployment_target.deploy_order 新增脚本（幂等，可重复执行；执行前建议先备份数据库）。
 *
 * 背景：部署目标配置（project_environment_target）里有「发布顺序 deploy_order」，
 *       但创建部署时只把顺序用在了「按序展开目标」这一步，快照表里没有留下顺序。
 *       执行器于是按 create_time 排序取执行顺序，而同一秒批量插入的多条目标
 *       create_time 完全并列（DATETIME 只到秒），MySQL 对并列行不保证稳定顺序，
 *       实际退化成随机 UUID 主键顺序。表现为：部署目标配了「顺序 1 / 顺序 2」，
 *       滚动分批发布时却先发了顺序 2 的那台。本列把顺序随快照固化，执行器改按它排序。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-deployment-target-order.sql
 *
 * 执行后校验（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> -e "SHOW FULL COLUMNS FROM deployment_target LIKE 'deploy_order'"
 *   或直接执行配套只读脚本 verify-deployment-target-order.sql
 *
 * 注意事项：
 * 1. 该列可空，且**故意不回填历史数据**：历史部署早已执行完毕，顺序不再影响任何行为；
 *    而回填只能依据 create_time，并列行的回填结果同样是随机的，等于把随机顺序固化成
 *    看似可信的数据，反而误导排查。执行器与页面对 NULL 的处理是「排在有值之后，
 *    并列时回落 create_time」，语义明确：该字段上线前的部署未记录顺序。
 * 2. 不建索引：该列只用于「一次部署的几条目标」内部排序，单次结果集为个位数，
 *    加索引没有收益。
 * 3. 脚本只在 deployment_target 表存在时执行，可反复执行；
 *    结尾的 MODIFY COLUMN 用于收敛类型、NULL 与注释，并校正列位置与 schema.sql 一致。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_deployment_target_order;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_deployment_target_order()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target') THEN
        -- 仅当列不存在时才新增，保证重复执行不报错
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target'
                         AND COLUMN_NAME = 'deploy_order') THEN
            ALTER TABLE `deployment_target`
                ADD COLUMN deploy_order INT NULL COMMENT '发布顺序快照，创建部署时从部署目标配置固化；历史数据为空' AFTER server_id;
        END IF;
        -- 收敛到 schema.sql 的当前定义：兼顾「改类型」与「改注释」，并校正列位置
        ALTER TABLE `deployment_target`
            MODIFY COLUMN deploy_order INT NULL COMMENT '发布顺序快照，创建部署时从部署目标配置固化；历史数据为空' AFTER server_id;
    END IF;
END$$

DELIMITER ;

CALL lds_upgrade_deployment_target_order();

DROP PROCEDURE lds_upgrade_deployment_target_order;

SELECT 'deployment_target.deploy_order 已对齐 schema.sql，可执行 verify-deployment-target-order.sql 校验结果' AS result;
