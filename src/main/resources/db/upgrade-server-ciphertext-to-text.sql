/*
 * server.ciphertext 列扩宽脚本（幂等，可重复执行；执行前建议先备份数据库）。
 *
 * 背景：AGENTS.md §6.1 与 schema.sql 要求 SSH 凭据密文使用 TEXT，但早期建库时该列是
 *       VARCHAR(130)。AES-GCM 加密后的私钥（如 2048 位 RSA PEM）Base64 后约 2300 字符，
 *       写入 VARCHAR(130) 会被 MySQL 直接拒绝（严格模式报 Data too long）或截断，
 *       表现为「私钥认证的服务器保存失败」。
 *
 * 执行方式（<库名> 换成实际数据库名）：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/upgrade-server-ciphertext-to-text.sql
 *
 * 执行后校验：
 *   mysql -h127.0.0.1 -uroot -p <库名> < src/main/resources/db/verify-schema-columns.sql
 *
 * 注意事项：
 * 1. VARCHAR → TEXT 属于扩宽，已有密文不会丢失，因此不需要临时列；
 * 2. 脚本只在 server 表存在、且列定义与 schema.sql 不一致时执行 ALTER，可反复执行；
 * 3. 脚本不修改任何业务数据，也不涉及明文凭据。
 */

DROP PROCEDURE IF EXISTS lds_upgrade_ciphertext;

DELIMITER $$

CREATE PROCEDURE lds_upgrade_ciphertext()
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.TABLES
               WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server') THEN
        -- 仅当类型不是 TEXT 家族或注释过旧时才扩宽，保证重复执行不产生多余的表重建
        IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'server'
                     AND COLUMN_NAME = 'ciphertext'
                     AND (DATA_TYPE NOT IN ('text', 'mediumtext', 'longtext')
                          OR COLUMN_COMMENT <> 'AES-GCM加密后的SSH密码或私钥内容')) THEN
            ALTER TABLE `server` MODIFY COLUMN ciphertext TEXT NOT NULL COMMENT 'AES-GCM加密后的SSH密码或私钥内容';
        END IF;
    END IF;
END$$

DELIMITER ;

CALL lds_upgrade_ciphertext();

DROP PROCEDURE lds_upgrade_ciphertext;

SELECT 'server.ciphertext 已对齐 schema.sql，请执行 verify-schema-columns.sql 校验结果' AS result;
