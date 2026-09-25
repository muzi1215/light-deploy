-- =============================================================================
-- upgrade-server-build-env.sql
-- server 表增加一列：build_env_script（构建环境初始化脚本，仅构建机使用）
--
-- 【为什么需要】
-- 平台通过 SSH 执行构建命令，走的是**非交互式 shell**：不加载 /etc/profile、
-- /etc/profile.d/*.sh 与 ~/.bashrc，PATH 通常只有 /usr/bin:/bin 等基础目录。
-- 于是构建机上「已经装好但靠 profile 导出 PATH」的工具（最典型的是 mvn，
-- 常装在 /opt/apache-maven-*/bin）在构建时找不到，日志只留下
-- `bash: mvn: command not found`，用户很难定位。
--
-- 应对分两层：
--   1. 平台侧固定兜底：执行构建命令前先 `. /etc/profile`（见 BuildTaskHandler），
--      把登录态 PATH 引入非交互会话；另增加命令可执行性预检，首词找不到时给出
--      可操作的错误提示，而不是等 bash 报 command not found；
--   2. 本列：机器级的补充环境脚本。当工具的 PATH 既不在 profile 里、也不方便写进
--      每个项目的 build_command 时，在这里统一配置，例如：
--        export PATH=/opt/apache-maven-3.8.8/bin:$PATH
--      它是「某台构建机上的环境」，属于机器属性，因此落在 server 表而不是项目配置里。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-server-build-env.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-server-build-env.sql;
--
-- 【所需权限】
--   ALTER（新增列）+ 对 information_schema 的读取权限（元数据判断）。
--
-- 【幂等性】
--   先判断「表是否存在」「列是否已存在」：表不存在整段跳过；列已存在则跳过 ADD COLUMN、
--   只执行 MODIFY COLUMN 收敛定义。可反复执行，结果一致。
--
-- 【回退默认值策略】
--   本列允许为 NULL 且**不给默认值**：脚本内容与具体机器强相关，任何默认值都是错的。
--   既有行升级后为 NULL，表示「不使用额外环境脚本」，行为与升级前完全一致。
--
-- 【执行后抽查】
--   SELECT id, server_name, build_env_script FROM server WHERE is_deleted = 0 LIMIT 10;
--   再执行配套校验脚本：
--   SOURCE src/main/resources/db/verify-server-build-env.sql;
-- =============================================================================

-- 当前库名与改造目标，后续所有元数据判断都以此为条件，避免改到别的库。
SET @db_name = DATABASE();
SET @table_name = 'server';
SET @column_name = 'build_env_script';

SET @table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name
);

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND COLUMN_NAME = @column_name
);

-- 第一步：仅当「表存在且列不存在」时新增列，位置紧跟 agent_base_dir，与 schema.sql 保持一致。
SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE `server` ADD COLUMN build_env_script VARCHAR(255) NULL COMMENT ''构建环境初始化脚本，仅构建机使用：执行构建命令前在当前 shell 执行，用于引入 PATH 等环境变量（如 export PATH=/opt/apache-maven/bin:$PATH）'' AFTER agent_base_dir',
    'SELECT ''skip add column'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

-- 第二步：无条件收敛列定义（表存在时）。同为可空无默认，注释过时时一并覆盖。
SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE `server` MODIFY COLUMN build_env_script VARCHAR(255) NULL COMMENT ''构建环境初始化脚本，仅构建机使用：执行构建命令前在当前 shell 执行，用于引入 PATH 等环境变量（如 export PATH=/opt/apache-maven/bin:$PATH）''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- 索引检查：本列是配置值，不作为查询条件，schema.sql 中也不为其建索引，此处无需补索引。
