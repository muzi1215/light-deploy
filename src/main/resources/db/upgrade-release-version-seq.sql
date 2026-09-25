-- =============================================================================
-- upgrade-release-version-seq.sql
-- release_record 与 build 各增加一列：version_seq（版本序号）
--
-- 【为什么需要】
-- 改前发布版本号由 BuildService.generateReleaseVersion 生成为「项目编码-提交短码」
-- （如 auth-7888-887b603c），完全没有序号信息：使用者看不出这是第几个版本，也无法排序。
--
-- 更要紧的是，这一个字符串被**三处复用**：
--   1. Docker 镜像 tag（imageRepo:tag，分层构建还有 base-{tag}）；
--   2. 构建机产物持久目录 {build_artifact_dir}/{项目编码}/{版本号}；
--   3. release_record.version。
-- 于是同一个 commit 重复构建时三处同名：镜像被第二次 push 覆盖、持久目录被 cp -r 覆盖，
-- 并在 release_record 上撞唯一键 uk_release_project_version (project_id, version, is_deleted)，
-- 抛 DuplicateKeyException。而 BuildService.completeSuccess 没有事务，
-- 顺序是「置 build 成功 → 插产物 → 插版本」，炸在最后一步，结果是：
-- 构建记录显示成功、任务显示失败、版本列表里没有它，该产物永远发不出去。
--
-- 改造后版本号格式为 {项目编码}-v{版本序号}-{提交短码}（如 auth-7888-v3-887b603c）：
--   序号在项目内递增，天然唯一、可排序，同时让每次构建的产物互不覆盖。
--
-- 【两个列的职责】
--   release_record.version_seq：本次版本在项目内的序号（权威值，版本号字符串由此拼出）；
--   build.version_seq        ：发起构建时人工指定的序号，留空表示构建成功时自动取号。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-release-version-seq.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-release-version-seq.sql;
--
-- 【所需权限】
--   ALTER（新增列与索引）+ CREATE（新增索引）+ 对 information_schema 的读取权限。
--
-- 【幂等性】
--   先判断「表是否存在」「列/索引是否已存在」：表不存在整段跳过；已存在则跳过创建，
--   只做定义收敛。可反复执行，结果一致。
--
-- 【历史数据回填】
--   只在「version_seq 列本次新加出来」的那一次执行，按 create_time 升序为既有版本
--   回填 1..N，使新构建从 N+1 继续编号，避免出现「明明构建过 3 次，新版本却是 v1」。
--   回填只写 version_seq，**不修改 version 字符串**——deployment_target 的 old_version/
--   new_version 与历史 image_tag 存的都是旧值，改版本号会让历史部署与镜像对不上。
--   因此升级后列表里新旧格式会并存，这属于预期。
--
-- 【执行后抽查】
--   再执行配套校验脚本：
--   SOURCE src/main/resources/db/verify-release-version-seq.sql;
-- =============================================================================

SET @db_name = DATABASE();

-- -----------------------------------------------------------------------------
-- 一、release_record.version_seq：本次版本在项目内的序号
-- -----------------------------------------------------------------------------
SET @table_name = 'release_record';
SET @column_name = 'version_seq';

SET @table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name
);

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND COLUMN_NAME = @column_name
);

-- 新增列，位置紧跟 version，与 schema.sql 保持一致。
SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE `release_record` ADD COLUMN version_seq INT NULL COMMENT ''版本序号：项目内递增，版本号据此排序与追溯'' AFTER version',
    'SELECT ''skip add release_record.version_seq'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

-- 历史数据回填：仅列刚新增时执行一次。序号按 create_time 升序，同秒用 id 打破并列。
SET @backfill_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'UPDATE release_record r SET r.version_seq = (SELECT COUNT(*) FROM (SELECT r2.id FROM release_record r2 WHERE r2.project_id = r.project_id AND (r2.create_time < r.create_time OR (r2.create_time = r.create_time AND r2.id <= r.id))) ranked) WHERE r.version_seq IS NULL',
    'SELECT ''skip backfill release_record.version_seq'' AS result'
);
PREPARE backfill_stmt FROM @backfill_sql;
EXECUTE backfill_stmt;
DEALLOCATE PREPARE backfill_stmt;

-- 序号是取号查询（MAX(version_seq)）的条件列，补组合索引避免全表扫描。
SET @index_name = 'idx_release_project_seq';
SET @index_exists = (
    SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND INDEX_NAME = @index_name
);
SET @index_sql = IF(
    @table_exists = 1 AND @index_exists = 0,
    'ALTER TABLE `release_record` ADD INDEX idx_release_project_seq (project_id, version_seq)',
    'SELECT ''skip add idx_release_project_seq'' AS result'
);
PREPARE index_stmt FROM @index_sql;
EXECUTE index_stmt;
DEALLOCATE PREPARE index_stmt;

-- -----------------------------------------------------------------------------
-- 二、build.version_seq：发起构建时人工指定的版本序号
-- -----------------------------------------------------------------------------
SET @table_name = 'build';
SET @column_name = 'version_seq';

SET @table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name
);

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND COLUMN_NAME = @column_name
);

SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE `build` ADD COLUMN version_seq INT NULL COMMENT ''版本序号：发起构建时可手动指定，为空则由构建成功时自动递增取号'' AFTER git_commit',
    'SELECT ''skip add build.version_seq'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

-- 本列不由查询条件使用（只按主键读单条构建），不建索引，与 schema.sql 保持一致。
