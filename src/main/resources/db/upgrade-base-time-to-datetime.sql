/*
 * 基础时间字段类型收敛脚本（幂等，可重复执行；执行前仍建议先备份数据库）。
 *
 * 背景：schema.sql 与 AGENTS.md §4.3 规定所有业务表的 create_time / update_time 使用 DATETIME。
 *       早期手工建库时这两列被建成了 TIMESTAMP，需要收敛到 DATETIME，原因有三：
 *       1. TIMESTAMP 会按会话时区隐式换算（写入按会话时区转 UTC、读取再转回），一旦 JDBC 连接的
 *          时区设置与服务端不一致，同一行数据在不同连接下显示的时间会相差整小时；
 *          DATETIME 按字面值存取，不参与时区换算，本地单时区部署下更可预期；
 *       2. TIMESTAMP 的取值上限是 2038-01-19，属于先天性缺陷，DATETIME 无此限制；
 *       3. 数据库列类型与 schema.sql 不一致时，全新环境（按 schema.sql 建库）与开发库会逐渐分叉，
 *          后续任何「字段一致性」核对都会持续报差异。
 *
 * 本脚本收敛到 schema.sql 的定义，可反复执行：
 *   1. 已是 DATETIME 时，MODIFY 是空操作，不会改变任何取值；
 *   2. TIMESTAMP → DATETIME 转换按当前会话时区换算，会话时区与系统时区一致时取值完全不变
 *      （执行前可用 SELECT @@session.time_zone, NOW(), UTC_TIMESTAMP(); 确认）；
 *   3. 表不存在（未建表）的表段直接跳过，不影响其他表；
 *   4. 脚本只改列类型与注释，不删除任何业务记录、不改动索引。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-base-time-to-datetime.sql
 *
 * 执行后校验（第三个结果集会逐列比对时间列类型与 ON UPDATE 属性）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-schema-columns.sql
 *
 * 注意事项：
 * 1. TIMESTAMP → DATETIME 需要重建表（ALGORITHM=COPY），大表请在低峰期执行；
 * 2. 脚本使用存储过程，需要 CREATE ROUTINE 权限（root 账号即可）；没有该权限时，
 *    可按本文件中的 ALTER 语句逐表手工执行，结果一致；
 * 3. 执行后建议抽查，确认取值没有被时区换算改动，例如：
 *    SELECT id, create_time, update_time FROM project;
 */

DROP PROCEDURE IF EXISTS lds_upgrade_base_time;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_base_time()
BEGIN
    -- ============================================================
    -- user：用户表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user') THEN
        -- 已是 DATETIME 时该语句是空操作，可反复执行（幂等的关键）
        ALTER TABLE `user`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- server：目标服务器表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server') THEN
        ALTER TABLE `server`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- lds_task：部署任务表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task') THEN
        ALTER TABLE `lds_task`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- lds_task_log：任务日志表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'lds_task_log') THEN
        ALTER TABLE `lds_task_log`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- credential：加密凭据表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'credential') THEN
        ALTER TABLE `credential`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- registry：镜像仓库表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'registry') THEN
        ALTER TABLE `registry`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- project：部署项目表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project') THEN
        ALTER TABLE `project`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- environment：环境表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'environment') THEN
        ALTER TABLE `environment`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- project_environment_target：项目环境部署目标表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project_environment_target') THEN
        ALTER TABLE `project_environment_target`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- build：构建记录表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'build') THEN
        ALTER TABLE `build`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- build_artifact：构建产物表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'build_artifact') THEN
        ALTER TABLE `build_artifact`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- release_record：发布版本表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'release_record') THEN
        ALTER TABLE `release_record`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- deployment：发布单表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment') THEN
        ALTER TABLE `deployment`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- deployment_target：发布单目标表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'deployment_target') THEN
        ALTER TABLE `deployment_target`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- aiops_event：AIops 事件表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'aiops_event') THEN
        ALTER TABLE `aiops_event`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;

    -- ============================================================
    -- audit_log：审计日志表
    -- ============================================================
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log') THEN
        ALTER TABLE `audit_log`
            MODIFY COLUMN `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
            MODIFY COLUMN `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间';
    END IF;
END$$

DELIMITER ;

-- 执行改造
CALL lds_upgrade_base_time();

-- 清理存储过程，不在业务库里留残留对象
DROP PROCEDURE lds_upgrade_base_time;

SELECT '基础时间字段 DATETIME 收敛完成，请执行 verify-schema-columns.sql 校验结果' AS result;
