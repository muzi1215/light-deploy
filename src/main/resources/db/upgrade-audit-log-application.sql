/*
 * audit_log 应用系统字段脚本（幂等，可重复执行；执行前建议先备份数据库）。
 *
 * 背景：操作日志需要回答「这条操作属于哪个系统」。该取值与具体接口无关——同一个平台实例里
 *       所有接口的取值必然相同——所以不能落在 @OperLog 注解上（28 个接口各写一遍，
 *       系统改名时要改 28 处且必然漏），改由配置 light-deploy.application-name 统一提供，
 *       切面在写日志时填充 audit_log.application。
 *
 * 配套改动：
 *   1. application.yaml 新增 light-deploy.application-name: 轻量部署发布系统
 *   2. OperLogAspect 构造注入该配置，写日志时统一填充
 *   3. 已有行按本脚本回填为默认名（如需别的名字，改下面 UPDATE 里的字面量后再执行）
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-audit-log-application.sql
 *
 * 执行后校验（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-audit-log-operation.sql
 *   （校验脚本结果集 1、2 已包含 application 列）
 *
 * 注意事项：
 * 1. 只新增列并回填 NULL 行，**不删除任何记录**，也不改写已有非空取值。
 * 2. 字符集 utf8mb4_unicode_ci，与 AGENTS.md §4.2 一致。
 * 3. 不加索引：该列取值在整个实例里高度重复（通常只有一种），索引只会增加写入成本，
 *    真正筛选用的是时间、操作人与模块（已有 idx_audit_create_time / idx_audit_operator）。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_audit_log_application;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_audit_log_application()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log') THEN

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'application') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN application VARCHAR(64) NULL COMMENT '应用系统名称，来自配置 light-deploy.application-name' AFTER trace_id;
        END IF;

        -- 收敛到 schema.sql 的定义（类型 / 可空 / 注释），反复执行可对齐
        ALTER TABLE `audit_log`
            MODIFY COLUMN application VARCHAR(64) NULL COMMENT '应用系统名称，来自配置 light-deploy.application-name';

        -- 回填历史行：application 字段上线前写入的日志该列为空，页面会显示成「—」。
        -- 这些记录本来就发生在同一个系统里，统一补成默认名；如需其他名字，改这里的字面量再执行。
        UPDATE `audit_log`
        SET application = '轻量部署发布系统'
        WHERE application IS NULL OR application = '';
    END IF;
END$$

DELIMITER ;

CALL lds_upgrade_audit_log_application();

DROP PROCEDURE lds_upgrade_audit_log_application;

SELECT 'audit_log.application 已就位并完成历史回填，可执行 verify-audit-log-operation.sql 校验' AS result;
