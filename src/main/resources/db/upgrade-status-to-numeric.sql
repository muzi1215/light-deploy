/*
 * 状态字段数字编码改造脚本（幂等，可重复执行；执行前仍建议先备份数据库）。
 *
 * 背景：状态 / 结果字段统一为 TINYINT 数字编码，字符串编码（ENABLED / QUEUED / SUCCESS 等）不再使用。
 *       代码侧的实体 / DTO / VO 已统一为 Integer（AGENTS.md §4.5），若数据库列仍是 VARCHAR，
 *       接口读取时会发生类型转换错误，因此必须先把库改到位。
 *
 * 本脚本收敛到 schema.sql 的定义，可反复执行：
 *   1. 字符串类型的状态列 → 按旧值映射改成 TINYINT；
 *   2. 已是数字类型但注释 / 默认值过旧的列 → 用 MODIFY 覆盖成最新定义；
 *   3. 表不存在（未建表）的表段直接跳过，不影响其他表；
 *   4. 状态列被删除会连带删除该列上的索引（多列索引会被裁剪掉这一列），
 *      脚本会按 schema.sql 把索引补回成完整定义。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-status-to-numeric.sql
 *
 * 执行后校验（逐列比对实际类型 / 注释与期望值）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-schema-columns.sql
 *
 * 注意事项：
 * 1. 脚本使用存储过程，需要 CREATE ROUTINE 权限（root 账号即可）；没有该权限时，
 *    可按本文件中的 ALTER 语句逐表手工执行，结果一致；
 * 2. 旧值不在映射表内时回退到该列的默认值，执行后建议抽查，例如：
 *    SELECT status, COUNT(*) FROM lds_task GROUP BY status;
 * 3. 字符串改造后的列会移动到表末尾（MySQL 保留列序需重建表），与 schema.sql 的列顺序
 *    可能不同，不影响读写；
 * 4. 脚本只改列定义与状态取值映射，不删除任何业务记录。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_status;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_status()
BEGIN
    -- ============================================================
    -- user.status：0 停用 / 1 启用 / 2 锁定
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `user` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 1 COMMENT '用户状态：0停用，1启用，2锁定';
            UPDATE `user` SET status_num = CASE `status`
                WHEN 'ENABLED' THEN 1
                WHEN 'DISABLED' THEN 0
                WHEN 'LOCKED' THEN 2
                ELSE 1 END;
            ALTER TABLE `user` DROP COLUMN `status`;
            ALTER TABLE `user` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 1 COMMENT '用户状态：0停用，1启用，2锁定';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `user` SET `status` = 1 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `user` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '用户状态：0停用，1启用，2锁定';
        -- 索引 idx_user_status 的列数不符（旧列被删除会裁剪索引）时先删除，再按定义重建
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                     AND INDEX_NAME = 'idx_user_status')
           AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                  AND INDEX_NAME = 'idx_user_status') <> 1 THEN
            ALTER TABLE `user` DROP INDEX idx_user_status;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user'
                         AND INDEX_NAME = 'idx_user_status') THEN
            ALTER TABLE `user` ADD INDEX idx_user_status (`status`);
        END IF;
    END IF;

    -- ============================================================
    -- server.status：0 停用 / 1 启用（该列历史上已是 TINYINT）
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `server` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 1 COMMENT '服务器状态：0停用，1启用';
            UPDATE `server` SET status_num = CASE `status`
                WHEN 'ENABLED' THEN 1
                WHEN 'DISABLED' THEN 0
                ELSE 1 END;
            ALTER TABLE `server` DROP COLUMN `status`;
            ALTER TABLE `server` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 1 COMMENT '服务器状态：0停用，1启用';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `server` SET `status` = 1 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `server` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '服务器状态：0停用，1启用';
    END IF;

    -- ============================================================
    -- lds_task.status：0 排队中 / 1 执行中 / 2 成功 / 3 失败 / 4 已取消
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `lds_task` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 0 COMMENT '任务状态：0排队中，1执行中，2成功，3失败，4已取消';
            UPDATE `lds_task` SET status_num = CASE `status`
                WHEN 'QUEUED' THEN 0
                WHEN 'RUNNING' THEN 1
                WHEN 'SUCCESS' THEN 2
                WHEN 'FAILED' THEN 3
                WHEN 'CANCELED' THEN 4
                ELSE 0 END;
            ALTER TABLE `lds_task` DROP COLUMN `status`;
            ALTER TABLE `lds_task` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 0 COMMENT '任务状态：0排队中，1执行中，2成功，3失败，4已取消';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `lds_task` SET `status` = 0 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `lds_task` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '任务状态：0排队中，1执行中，2成功，3失败，4已取消';
        -- 索引 idx_lds_task_status 的列数不符（旧列被删除会裁剪索引）时先删除，再按定义重建
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task'
                     AND INDEX_NAME = 'idx_lds_task_status')
           AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task'
                  AND INDEX_NAME = 'idx_lds_task_status') <> 1 THEN
            ALTER TABLE `lds_task` DROP INDEX idx_lds_task_status;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task'
                         AND INDEX_NAME = 'idx_lds_task_status') THEN
            ALTER TABLE `lds_task` ADD INDEX idx_lds_task_status (`status`);
        END IF;
    END IF;

    -- ============================================================
    -- registry.status：0 停用 / 1 启用
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'registry') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'registry'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `registry` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
            UPDATE `registry` SET status_num = CASE `status`
                WHEN 'ENABLED' THEN 1
                WHEN 'DISABLED' THEN 0
                ELSE 1 END;
            ALTER TABLE `registry` DROP COLUMN `status`;
            ALTER TABLE `registry` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `registry` SET `status` = 1 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `registry` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
    END IF;

    -- ============================================================
    -- project.status：0 停用 / 1 启用
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `project` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
            UPDATE `project` SET status_num = CASE `status`
                WHEN 'ENABLED' THEN 1
                WHEN 'DISABLED' THEN 0
                ELSE 1 END;
            ALTER TABLE `project` DROP COLUMN `status`;
            ALTER TABLE `project` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `project` SET `status` = 1 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `project` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
        -- 索引 idx_project_status 的列数不符（旧列被删除会裁剪索引）时先删除，再按定义重建
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                     AND INDEX_NAME = 'idx_project_status')
           AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                  AND INDEX_NAME = 'idx_project_status') <> 1 THEN
            ALTER TABLE `project` DROP INDEX idx_project_status;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                         AND INDEX_NAME = 'idx_project_status') THEN
            ALTER TABLE `project` ADD INDEX idx_project_status (`status`);
        END IF;
    END IF;

    -- ============================================================
    -- project_environment_target.status：0 停用 / 1 启用
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_environment_target') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_environment_target'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `project_environment_target` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
            UPDATE `project_environment_target` SET status_num = CASE `status`
                WHEN 'ENABLED' THEN 1
                WHEN 'DISABLED' THEN 0
                ELSE 1 END;
            ALTER TABLE `project_environment_target` DROP COLUMN `status`;
            ALTER TABLE `project_environment_target` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `project_environment_target` SET `status` = 1 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `project_environment_target` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：0停用，1启用';
        -- 索引 idx_target_server 的列数不符（旧列被删除会裁剪索引）时先删除，再按定义重建
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_environment_target'
                     AND INDEX_NAME = 'idx_target_server')
           AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_environment_target'
                  AND INDEX_NAME = 'idx_target_server') <> 2 THEN
            ALTER TABLE `project_environment_target` DROP INDEX idx_target_server;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_environment_target'
                         AND INDEX_NAME = 'idx_target_server') THEN
            ALTER TABLE `project_environment_target` ADD INDEX idx_target_server (`server_id`, `status`);
        END IF;
    END IF;

    -- ============================================================
    -- build.status：0 排队中 / 1 构建中 / 2 成功 / 3 失败
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'build') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'build'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `build` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 0 COMMENT '构建状态：0排队中，1构建中，2成功，3失败';
            UPDATE `build` SET status_num = CASE `status`
                WHEN 'QUEUED' THEN 0
                WHEN 'RUNNING' THEN 1
                WHEN 'SUCCESS' THEN 2
                WHEN 'FAILED' THEN 3
                ELSE 0 END;
            ALTER TABLE `build` DROP COLUMN `status`;
            ALTER TABLE `build` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 0 COMMENT '构建状态：0排队中，1构建中，2成功，3失败';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `build` SET `status` = 0 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `build` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '构建状态：0排队中，1构建中，2成功，3失败';
    END IF;

    -- ============================================================
    -- release_record.status：0 构建中 / 1 可发布 / 2 已废弃
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release_record') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release_record'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `release_record` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 1 COMMENT '版本状态：0构建中，1可发布，2已废弃';
            UPDATE `release_record` SET status_num = CASE `status`
                WHEN 'BUILT' THEN 1
                WHEN 'BUILDING' THEN 0
                WHEN 'DISCARDED' THEN 2
                ELSE 1 END;
            ALTER TABLE `release_record` DROP COLUMN `status`;
            ALTER TABLE `release_record` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 1 COMMENT '版本状态：0构建中，1可发布，2已废弃';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `release_record` SET `status` = 1 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `release_record` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 1 COMMENT '版本状态：0构建中，1可发布，2已废弃';
    END IF;

    -- ============================================================
    -- deployment.status：0 排队中 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 回滚中 / 6 已回滚
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `deployment` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 0 COMMENT '部署状态：0排队中，1发布中，2成功，3失败，4已取消，5回滚中，6已回滚';
            UPDATE `deployment` SET status_num = CASE `status`
                WHEN 'QUEUED' THEN 0
                WHEN 'RUNNING' THEN 1
                WHEN 'SUCCESS' THEN 2
                WHEN 'FAILED' THEN 3
                WHEN 'CANCELED' THEN 4
                WHEN 'ROLLING_BACK' THEN 5
                WHEN 'ROLLED_BACK' THEN 6
                ELSE 0 END;
            ALTER TABLE `deployment` DROP COLUMN `status`;
            ALTER TABLE `deployment` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 0 COMMENT '部署状态：0排队中，1发布中，2成功，3失败，4已取消，5回滚中，6已回滚';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `deployment` SET `status` = 0 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `deployment` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '部署状态：0排队中，1发布中，2成功，3失败，4已取消，5回滚中，6已回滚';
        -- 索引 idx_deployment_status 的列数不符（旧列被删除会裁剪索引）时先删除，再按定义重建
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment'
                     AND INDEX_NAME = 'idx_deployment_status')
           AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment'
                  AND INDEX_NAME = 'idx_deployment_status') <> 1 THEN
            ALTER TABLE `deployment` DROP INDEX idx_deployment_status;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment'
                         AND INDEX_NAME = 'idx_deployment_status') THEN
            ALTER TABLE `deployment` ADD INDEX idx_deployment_status (`status`);
        END IF;
    END IF;

    -- ============================================================
    -- deployment_target.status：0 排队中 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 已跳过
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `deployment_target` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 0 COMMENT '目标任务状态：0排队中，1发布中，2成功，3失败，4已取消，5已跳过';
            UPDATE `deployment_target` SET status_num = CASE `status`
                WHEN 'QUEUED' THEN 0
                WHEN 'RUNNING' THEN 1
                WHEN 'SUCCESS' THEN 2
                WHEN 'FAILED' THEN 3
                WHEN 'CANCELED' THEN 4
                WHEN 'SKIPPED' THEN 5
                ELSE 0 END;
            ALTER TABLE `deployment_target` DROP COLUMN `status`;
            ALTER TABLE `deployment_target` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 0 COMMENT '目标任务状态：0排队中，1发布中，2成功，3失败，4已取消，5已跳过';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `deployment_target` SET `status` = 0 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `deployment_target` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '目标任务状态：0排队中，1发布中，2成功，3失败，4已取消，5已跳过';
        -- 索引 idx_deployment_target_server 的列数不符（旧列被删除会裁剪索引）时先删除，再按定义重建
        IF EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target'
                     AND INDEX_NAME = 'idx_deployment_target_server')
           AND (SELECT COUNT(*) FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target'
                  AND INDEX_NAME = 'idx_deployment_target_server') <> 2 THEN
            ALTER TABLE `deployment_target` DROP INDEX idx_deployment_target_server;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target'
                         AND INDEX_NAME = 'idx_deployment_target_server') THEN
            ALTER TABLE `deployment_target` ADD INDEX idx_deployment_target_server (`server_id`, `status`);
        END IF;
    END IF;

    -- ============================================================
    -- deployment_target.health_status：0 未检查 / 1 健康 / 2 不健康（可空）
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target'
                     AND COLUMN_NAME = 'health_status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `deployment_target` ADD COLUMN health_status_num TINYINT NULL COMMENT '健康检查状态：0未检查，1健康，2不健康';
            UPDATE `deployment_target` SET health_status_num = CASE `health_status`
                WHEN 'HEALTHY' THEN 1
                WHEN 'UNHEALTHY' THEN 2
                WHEN 'UNKNOWN' THEN 0
                ELSE NULL END;
            ALTER TABLE `deployment_target` DROP COLUMN `health_status`;
            ALTER TABLE `deployment_target` CHANGE COLUMN health_status_num `health_status` TINYINT NULL COMMENT '健康检查状态：0未检查，1健康，2不健康';
        END IF;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `deployment_target` MODIFY COLUMN `health_status` TINYINT NULL COMMENT '健康检查状态：0未检查，1健康，2不健康';
    END IF;

    -- ============================================================
    -- aiops_event.status：0 待发送 / 1 成功 / 2 失败 / 3 已放弃
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aiops_event') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aiops_event'
                     AND COLUMN_NAME = 'status'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `aiops_event` ADD COLUMN status_num TINYINT NOT NULL DEFAULT 0 COMMENT '调用状态：0待发送，1成功，2失败，3已放弃';
            UPDATE `aiops_event` SET status_num = CASE `status`
                WHEN 'PENDING' THEN 0
                WHEN 'SUCCESS' THEN 1
                WHEN 'FAILED' THEN 2
                WHEN 'ABANDONED' THEN 3
                ELSE 0 END;
            ALTER TABLE `aiops_event` DROP COLUMN `status`;
            ALTER TABLE `aiops_event` CHANGE COLUMN status_num `status` TINYINT NOT NULL DEFAULT 0 COMMENT '调用状态：0待发送，1成功，2失败，3已放弃';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `aiops_event` SET `status` = 0 WHERE `status` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `aiops_event` MODIFY COLUMN `status` TINYINT NOT NULL DEFAULT 0 COMMENT '调用状态：0待发送，1成功，2失败，3已放弃';
    END IF;

    -- ============================================================
    -- audit_log.result：0 失败 / 1 成功
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log') THEN
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                     AND COLUMN_NAME = 'result'
                     AND DATA_TYPE IN ('varchar', 'char', 'text', 'tinytext', 'mediumtext', 'longtext')) THEN
            -- 字符串 → 数字：先写临时列再改名，避免 MySQL 把 'ENABLED' 静默截断成 0
            ALTER TABLE `audit_log` ADD COLUMN result_num TINYINT NOT NULL DEFAULT 1 COMMENT '操作结果：0失败，1成功';
            UPDATE `audit_log` SET result_num = CASE `result`
                WHEN 'SUCCESS' THEN 1
                WHEN 'FAILED' THEN 0
                ELSE 1 END;
            ALTER TABLE `audit_log` DROP COLUMN `result`;
            ALTER TABLE `audit_log` CHANGE COLUMN result_num `result` TINYINT NOT NULL DEFAULT 1 COMMENT '操作结果：0失败，1成功';
        END IF;
        -- 补齐 NULL 值，避免下面的 MODIFY NOT NULL 失败
        UPDATE `audit_log` SET `result` = 1 WHERE `result` IS NULL;
        -- 类型已正确但注释 / 默认值过旧时，以本语句为准覆盖成最新定义（幂等的关键）
        ALTER TABLE `audit_log` MODIFY COLUMN `result` TINYINT NOT NULL DEFAULT 1 COMMENT '操作结果：0失败，1成功';
    END IF;
END$$

DELIMITER ;

-- 执行改造
CALL lds_upgrade_status();

-- 清理存储过程，不在业务库里留残留对象
DROP PROCEDURE lds_upgrade_status;

SELECT '状态字段数字编码改造完成，请执行 verify-schema-columns.sql 校验结果' AS result;
