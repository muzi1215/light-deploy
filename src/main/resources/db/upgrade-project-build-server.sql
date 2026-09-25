/*
 * project.build_server_id 新增脚本（幂等，可重复执行；执行前建议先备份数据库）。
 *
 * 背景：项目需要回答「这个项目在哪台机器上构建」，该信息有两个用途——
 *       1. 「项目连通性测试」据此确定去哪台机器验证 Git 仓库与镜像仓库是否可达（避免构建时才暴雷）；
 *       2. 新建构建时作为构建机的默认来源：请求显式指定 > 项目档案 > 系统默认配置
 *          （light-deploy.build.server-id）。
 *       schema.sql 里的 project 表已包含该列，早期建库的库需要执行本脚本补齐。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-project-build-server.sql
 *
 * 执行后校验（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> -e "SHOW FULL COLUMNS FROM project LIKE 'build_server_id'"
 *
 * 注意事项：
 * 1. 该列可空：不填时构建回落到系统默认构建机，因此存量数据无需回填；
 * 2. 脚本只在 project 表存在且列缺失时执行 ALTER，可反复执行；
 * 3. 列位置与 schema.sql 一致（位于 registry_id 之后），便于结构比对。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_project_build_server;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_project_build_server()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project') THEN
        -- 仅当列不存在时才新增，保证重复执行不报错
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'project'
                         AND COLUMN_NAME = 'build_server_id') THEN
            ALTER TABLE `project`
                ADD COLUMN build_server_id VARCHAR(64) NULL COMMENT '构建机服务器ID，指向server表，项目连通性测试与构建的执行目标' AFTER registry_id;
        END IF;
    END IF;
END$$

DELIMITER ;

CALL lds_upgrade_project_build_server();

DROP PROCEDURE lds_upgrade_project_build_server;

SELECT 'project.build_server_id 已对齐 schema.sql，可执行 SHOW FULL COLUMNS FROM project LIKE ''build_server_id'' 校验结果' AS result;
