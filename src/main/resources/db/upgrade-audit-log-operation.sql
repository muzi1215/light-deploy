/*
 * audit_log 操作日志字段扩展脚本（幂等，可重复执行；执行前建议先备份数据库）。
 *
 * 背景：audit_log 早期只建了表结构（操作人 / 动作 / 资源 / 请求地址 / 结果），没有任何 Java 代码写入，
 *       等于一张空表。平台缺少「谁在什么时候改了什么」的追溯能力——部署目标的环境归属被改之后，
 *       只能靠 update_time 猜。本轮接入 AOP 操作日志（@OperLog 注解 + 切面）后，需要补齐
 *       trace_id / title / method / request_method / oper_ip / os / browser / json_result / cost_time 这些列。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-audit-log-operation.sql
 *
 * 执行后校验（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> -e "SHOW FULL COLUMNS FROM audit_log"
 *   或直接执行配套只读脚本 verify-audit-log-operation.sql
 *
 * 注意事项：
 * 1. 只新增列与调整列定义，**不删除任何业务记录**，也不改已有数据。
 * 2. action 列由 NOT NULL 放宽为 NULL：切面只写「注解声明过的操作」，未声明的接口不落日志，
 *    保留 NOT NULL 会逼着切面给无关接口编造动作编码。已有行的取值不受影响。
 * 3. request_body 沿用 JSON 类型：切面写入的一定是合法 JSON（用 Jackson 序列化），
 *    非 JSON 文本会在 MySQL 侧直接报错，这是有意的强约束。
 * 4. 新增 idx_audit_create_time：操作日志页默认按时间倒序分页，没有该索引会退化成全表排序。
 *    索引名在脚本内先判重再建，重复执行不会报错。
 * 5. 字符集统一 utf8mb4_unicode_ci：与 AGENTS.md §4.2 一致（早期参考脚本里的 utf8mb3 不采用）。
 * 6. 脚本只在 audit_log 表存在时执行；结尾的 MODIFY COLUMN 用于收敛类型、NULL 与注释，
 *    保证「改类型」与「改注释」两件事都能通过反复执行对齐到 schema.sql。
 * 7. changed_fields 用 JSON 类型：内容由 Jackson 序列化，一定是合法 JSON 数组；
 *    非 JSON 文本会在 MySQL 侧直接报错，而不是静默存成坏数据——这是有意的强约束。
 *    与 request_body 保持同一口径（一条记录里两种 JSON 列类型不一致会让人怀疑哪边是权威）。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_audit_log_operation;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_audit_log_operation()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log') THEN

        -- trace_id：与响应头 X-Request-Id、日志文件里的 %X{requestId} 同一个值，用于把操作日志和访问日志串起来
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'trace_id') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN trace_id VARCHAR(64) NULL COMMENT '请求追踪ID，与响应头 X-Request-Id、日志 %X{requestId} 一致' AFTER id;
        END IF;

        -- title：操作模块（如「服务器管理」），来自注解
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'title') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN title VARCHAR(100) NULL COMMENT '操作模块，来自 @OperLog 注解的 title' AFTER operator_name;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'method') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN method VARCHAR(200) NULL COMMENT '操作方法，格式 类全名#方法名' AFTER resource_id;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'request_method') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN request_method VARCHAR(10) NULL COMMENT 'HTTP请求方法：GET/POST/PUT/DELETE' AFTER method;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'oper_ip') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN oper_ip VARCHAR(128) NULL COMMENT '操作IP，优先取 X-Forwarded-For 首段' AFTER request_uri;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'os') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN os VARCHAR(64) NULL COMMENT '操作系统，由 User-Agent 粗粒度解析' AFTER oper_ip;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'browser') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN browser VARCHAR(64) NULL COMMENT '浏览器，由 User-Agent 粗粒度解析' AFTER os;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'json_result') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN json_result TEXT NULL COMMENT '返回结果，超长截断' AFTER request_body;
        END IF;

        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'cost_time') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN cost_time INT NULL COMMENT '操作耗时（毫秒）' AFTER error_message;
        END IF;

        -- changed_fields：字段级变更明细，回答「把哪个字段从什么改成了什么」
        IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND COLUMN_NAME = 'changed_fields') THEN
            ALTER TABLE `audit_log`
                ADD COLUMN changed_fields JSON NULL COMMENT '字段级变更明细 [{field,oldValue,newValue}]，无变更或未声明资源类型时为空' AFTER cost_time;
        END IF;

        -- 收敛到 schema.sql 的当前定义（列位置由 ADD COLUMN 的 AFTER 决定，这里只收敛类型/可空性/注释）
        ALTER TABLE `audit_log`
            MODIFY COLUMN trace_id VARCHAR(64) NULL COMMENT '请求追踪ID，与响应头 X-Request-Id、日志 %X{requestId} 一致',
            MODIFY COLUMN operator_name VARCHAR(100) NULL COMMENT '操作者名称，来自请求头 X-Operator-Name，缺省记 system',
            MODIFY COLUMN title VARCHAR(100) NULL COMMENT '操作模块，来自 @OperLog 注解的 title',
            MODIFY COLUMN action VARCHAR(50) NULL COMMENT '操作类型：INSERT/UPDATE/DELETE/EXPORT/IMPORT/EXECUTE/ROLLBACK/CANCEL/OTHER',
            MODIFY COLUMN resource_type VARCHAR(50) NULL COMMENT '资源类型，来自 @OperLog 的 resourceType，空表示不采集变更明细',
            MODIFY COLUMN resource_id VARCHAR(100) NULL COMMENT '资源主键，来自被操作方法的 id 入参',
            MODIFY COLUMN method VARCHAR(200) NULL COMMENT '操作方法，格式 类全名#方法名',
            MODIFY COLUMN request_method VARCHAR(10) NULL COMMENT 'HTTP请求方法：GET/POST/PUT/DELETE',
            MODIFY COLUMN oper_ip VARCHAR(128) NULL COMMENT '操作IP，优先取 X-Forwarded-For 首段',
            MODIFY COLUMN os VARCHAR(64) NULL COMMENT '操作系统，由 User-Agent 粗粒度解析',
            MODIFY COLUMN browser VARCHAR(64) NULL COMMENT '浏览器，由 User-Agent 粗粒度解析',
            MODIFY COLUMN json_result TEXT NULL COMMENT '返回结果，超长截断',
            MODIFY COLUMN cost_time INT NULL COMMENT '操作耗时（毫秒）',
            MODIFY COLUMN changed_fields JSON NULL COMMENT '字段级变更明细 [{field,oldValue,newValue}]，无变更或未声明资源类型时为空';

        -- 操作日志页按时间倒序分页，补时间索引
        IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                       WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'audit_log'
                         AND INDEX_NAME = 'idx_audit_create_time') THEN
            ALTER TABLE `audit_log` ADD KEY idx_audit_create_time (create_time);
        END IF;
    END IF;
END$$

DELIMITER ;

CALL lds_upgrade_audit_log_operation();

DROP PROCEDURE lds_upgrade_audit_log_operation;

SELECT 'audit_log 操作日志字段已对齐 schema.sql，可执行 verify-audit-log-operation.sql 校验结果' AS result;
