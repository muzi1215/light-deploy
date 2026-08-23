/*
 * 数据库表清理脚本，仅在确认数据库备份完成后手工执行。
 * 本脚本不会被 Spring Boot 自动执行，也不会被 schema.sql 引用。
 * 当前 schema.sql 使用无普通前缀表名，任务域使用 lds_ 前缀。
 *
 * 重要：DROP TABLE 不可逆。执行前必须确认数据库连接地址、库名和备份文件。
 */
SET FOREIGN_KEY_CHECKS = 0;

/* 当前 schema.sql 创建的正式表。 */
DROP TABLE IF EXISTS audit_log;
DROP TABLE IF EXISTS aiops_event;
DROP TABLE IF EXISTS deployment_target;
DROP TABLE IF EXISTS deployment;
DROP TABLE IF EXISTS release_record;
DROP TABLE IF EXISTS build_artifact;
DROP TABLE IF EXISTS build;
DROP TABLE IF EXISTS project_environment_target;
DROP TABLE IF EXISTS environment;
DROP TABLE IF EXISTS project;
DROP TABLE IF EXISTS registry;
DROP TABLE IF EXISTS credential;
DROP TABLE IF EXISTS lds_task_log;
DROP TABLE IF EXISTS lds_task;
DROP TABLE IF EXISTS `server`;
DROP TABLE IF EXISTS `user`;

/* 早期版本遗留的 ld_ 前缀表。不存在时不会报错。 */
DROP TABLE IF EXISTS ld_audit_log;
DROP TABLE IF EXISTS ld_aiops_event;
DROP TABLE IF EXISTS ld_deployment_target;
DROP TABLE IF EXISTS ld_deployment;
DROP TABLE IF EXISTS ld_release;
DROP TABLE IF EXISTS ld_build_artifact;
DROP TABLE IF EXISTS ld_build;
DROP TABLE IF EXISTS ld_project_environment_target;
DROP TABLE IF EXISTS ld_environment;
DROP TABLE IF EXISTS ld_project;
DROP TABLE IF EXISTS ld_registry;
DROP TABLE IF EXISTS ld_credential;
DROP TABLE IF EXISTS ld_task_log;
DROP TABLE IF EXISTS ld_task;
DROP TABLE IF EXISTS ld_server;
DROP TABLE IF EXISTS ld_user;
SET FOREIGN_KEY_CHECKS = 1;
