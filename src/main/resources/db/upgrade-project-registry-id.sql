/*
 * project.registry_id 新增脚本（幂等，可重复执行；执行前建议先备份数据库）。
 *
 * 背景：AGENTS.md §23.4 明确「镜像仓库只服务于 DOCKER 运行时」，项目需要显式绑定镜像仓库
 *       才能把构建出的镜像推走，并让「仓库被哪些项目绑定」在删除时可以被拦截。
 *       schema.sql 里的 project 表已包含该列，早期建库的库需要执行本脚本补齐。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-project-registry-id.sql
 *
 * 执行后校验：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-schema-columns.sql
 *
 * 注意事项：
 * 1. 该列可空，非 DOCKER 运行时项目保持 NULL，不涉及存量数据回填；
 * 2. 脚本只在 project 表存在且列缺失时执行 ALTER，可反复执行；
 * 3. 列位置与 schema.sql 一致（位于 build_mode 之后），便于结构比对。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_project_registry_id;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_project_registry_id()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project') THEN
        -- 仅当列不存在时才新增，保证重复执行不报错
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                         AND COLUMN_NAME = 'registry_id') THEN
            ALTER TABLE `project`
                ADD COLUMN registry_id VARCHAR(32) NULL COMMENT '镜像仓库ID，仅 DOCKER 运行时使用，指向 registry 表' AFTER build_mode;
        END IF;
    END IF;
END$$

DELIMITER ;

CALL lds_upgrade_project_registry_id();

DROP PROCEDURE lds_upgrade_project_registry_id;

SELECT 'project.registry_id 已对齐 schema.sql，请执行 verify-schema-columns.sql 校验结果' AS result;
