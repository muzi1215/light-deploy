/*
 * project.owner_id / owner_name 新增脚本（幂等，可重复执行；执行前建议先备份数据库）。
 *
 * 背景：平台需要「项目负责人」概念，约束只有负责人能对项目发起构建、发布、回滚与取消，
 * 防止项目被随便发版。owner_id 指向 user 表主键 UUID，owner_name 为展示用名称。
 * schema.sql 的 project 表已包含这两列，早期建库的库需要执行本脚本补齐。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-project-owner.sql
 *
 * 执行后校验（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> -e "SHOW FULL COLUMNS FROM project LIKE 'owner_id'"
 *
 * 注意事项：
 * 1. 两列均可空：不指派时按过渡期策略放行（见 OwnerGuard），存量数据无需回填；
 * 2. 脚本只在 project 表存在且列缺失时执行 ALTER，可反复执行；
 * 3. 列位置与 schema.sql 一致（位于 updated_by 之后），便于结构比对。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_project_owner;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_project_owner()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project') THEN
        -- 仅当列不存在时才新增，保证重复执行不报错
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                         AND COLUMN_NAME = 'owner_id') THEN
            ALTER TABLE `project`
                ADD COLUMN owner_id VARCHAR(100) NULL COMMENT '项目负责人ID，指向user表，为空表示尚未指派（过渡期放行）' AFTER updated_by;
        END IF;
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                         AND COLUMN_NAME = 'owner_name') THEN
            ALTER TABLE `project`
                ADD COLUMN owner_name VARCHAR(100) NULL COMMENT '项目负责人名称，展示用' AFTER owner_id;
        END IF;
    END IF;
END$$

DELIMITER ;

CALL lds_upgrade_project_owner();

DROP PROCEDURE lds_upgrade_project_owner;

SELECT 'project.owner_id / owner_name 已对齐 schema.sql，可执行 SHOW FULL COLUMNS FROM project LIKE ''owner_%'' 校验结果' AS result;
