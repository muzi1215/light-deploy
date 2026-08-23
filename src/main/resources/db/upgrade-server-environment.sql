/*
 * 已存在 server 表的升级脚本。
 * 执行前请确认已经备份 server 表；新建数据库直接执行 schema.sql 即可。
 */
ALTER TABLE `server`
    DROP COLUMN IF EXISTS labels_json,
    DROP COLUMN IF EXISTS credential_id,
    ADD COLUMN IF NOT EXISTS auth_type TINYINT NOT NULL DEFAULT 1
        COMMENT 'SSH认证类型：1密码，2私钥' AFTER username,
    ADD COLUMN IF NOT EXISTS ciphertext TEXT NULL
        COMMENT 'AES-GCM加密后的SSH密码或私钥内容' AFTER auth_type,
    ADD COLUMN IF NOT EXISTS environment_tag TINYINT NOT NULL DEFAULT 1
        COMMENT '环境标签：1开发，2测试，3生产' AFTER ciphertext,
    MODIFY COLUMN auth_type TINYINT NOT NULL DEFAULT 1
        COMMENT 'SSH认证类型：1密码，2私钥',
    MODIFY COLUMN status TINYINT NOT NULL DEFAULT 1
        COMMENT '服务器状态：0停用，1启用';
