-- =============================================================================
-- upgrade-release-build-server.sql
-- release_record 增加 build_server_id 列（产物所在构建机）
--
-- 【为什么需要】
-- JAR / STATIC 运行时没有镜像仓库，产物文件只存在于构建机的某个绝对路径上
-- （release_record.artifact_path）。发布模块要把产物送到目标机，必须知道去哪台机器
-- 下载这个文件——构建机 ID 之前只保存在 build 表，而 release_record 与 build 是
-- 松耦合（无 build_id 外键，见 AGENTS.md §19 / ReleaseRecord 类注释），
-- 因此把构建机 ID 冗余到发布版本上，让发布链路只依赖 release_record 就能完成分发。
-- DOCKER 运行时该列无意义，保持 NULL。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-release-build-server.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-release-build-server.sql;
--
-- 【所需权限】
--   ALTER（新增列）+ 对 information_schema 的读取权限（元数据判断）。
--
-- 【幂等性】
--   脚本先用 information_schema 判断「表是否存在」「列是否已存在」：
--   - 表不存在：整段跳过，不会因库未初始化而中断；
--   - 列已存在：跳过 ADD COLUMN，只执行结尾的 MODIFY COLUMN 收敛定义；
--   因此可以反复执行，结果一致。
--
-- 【回退默认值策略】
--   新增列允许为 NULL，无需填充默认值：既有的发布版本即使没有构建机 ID 也不会被破坏，
--   只是这些版本在 JAR / STATIC 场景下无法完成分发（页面提示需重新构建）。
--   不存在「旧值映射」——本列是新增能力，没有对应的旧列。
--
-- 【执行后抽查】
--   SELECT id, version, runtime_type, build_server_id FROM release_record ORDER BY create_time DESC LIMIT 10;
--   再执行配套校验脚本：
--   SOURCE src/main/resources/db/verify-release-build-server.sql;
-- =============================================================================

-- 当前库名与改造目标，后续所有元数据判断都以此为条件，避免改到别的库。
SET @db_name = DATABASE();
SET @table_name = 'release_record';
SET @column_name = 'build_server_id';

-- 目标表是否存在：表都没建时不能执行 ALTER，否则脚本会因报错中断。
SET @table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name
);

-- 目标列是否已存在：已存在时跳过新增，只做定义收敛。
SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND COLUMN_NAME = @column_name
);

-- 第一步：仅当「表存在且列不存在」时新增列，位置紧跟 project_id，与 schema.sql 保持一致。
SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE release_record ADD COLUMN build_server_id VARCHAR(64) NULL COMMENT ''产物所在构建机ID，指向server表，JAR/STATIC部署时用于SFTP下载产物'' AFTER project_id',
    'SELECT ''skip add column'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

-- 第二步：无条件收敛列定义（表存在时）。
-- 这一步兼顾两种场景：刚新增的列要确认类型与注释；早已存在的列可能注释过时，
-- 一并覆盖成 schema.sql 的当前定义，避免「改过类型但改漏注释」。
SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE release_record MODIFY COLUMN build_server_id VARCHAR(64) NULL COMMENT ''产物所在构建机ID，指向server表，JAR/STATIC部署时用于SFTP下载产物''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- 索引检查：build_server_id 不作为查询条件，schema.sql 中也没有为它建索引，此处无需补索引。
-- 若将来出现「按构建机查历史版本」的需求，再随需求一起加索引并同步 schema.sql。
