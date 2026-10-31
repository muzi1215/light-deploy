-- =============================================================================
-- upgrade-build-rebuild-base-image.sql
-- build 增加 rebuild_base_image 列（分层构建是否重新构建基础镜像）
--
-- 【为什么需要】
-- LAYERED_DOCKER 把镜像拆成「基础层（OS + 运行时 + 依赖）」与「应用层」。
-- 基础层体积大、极少变化，绝大多数构建只需重建应用层；若每次都重建并推送基础层，
-- 既浪费构建机时间，又占用仓库带宽与存储。
--
-- 因此加一个开关，固化进 build 记录、由异步执行任务读取：
--   - false（默认）：跳过基础镜像的构建与推送，直接复用仓库中已存在的稳定基础镜像
--     （{镜像仓库}:base）；若仓库中尚无该稳定 base，执行器会 fail-fast 提示先重建一次；
--   - true：重新构建并推送稳定基础镜像（覆盖仓库中同名 tag），基础层内容变更时必须勾选。
--
-- 思路与 deployment.force_overwrite_config 一致：默认跳过/保护，需要时显式勾选覆盖。
-- 开关在创建构建时固化（见 BuildService.createAndTrigger），避免任务排队期间语义漂移。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-build-rebuild-base-image.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-build-rebuild-base-image.sql;
--
-- 【所需权限】
--   ALTER（新增列）+ 对 information_schema 的读取权限（元数据判断）。
--
-- 【幂等性】
--   脚本先用 information_schema 判断「表是否存在」「列是否已存在」：
--   - 表不存在：整段跳过；
--   - 列已存在：跳过 ADD COLUMN，只执行结尾的 MODIFY COLUMN 收敛定义；
--   因此可以反复执行，结果一致。
--
-- 【回退默认值策略】
--   新增列 NOT NULL DEFAULT 0（false）。既有构建记录一律视为「跳过基础重建」；
--   分层构建项目此前每次都重建基础层，升级后默认跳过——这正是本次优化的目标，
--   不会破坏任何已完成的构建记录（构建是只读回溯，基础层是否重建只影响未来构建）。
--
-- 【执行后抽查】
--   SELECT id, build_mode, status, rebuild_base_image FROM build ORDER BY create_time DESC LIMIT 10;
-- =============================================================================

-- 当前库名与改造目标，后续所有元数据判断都以此为条件，避免改到别的库。
SET @db_name = DATABASE();
SET @table_name = 'build';
SET @column_name = 'rebuild_base_image';

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

-- 第一步：仅当「表存在且列不存在」时新增列，放在 dependency_hash 之后（构建模式的执行选项）。
SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE build ADD COLUMN rebuild_base_image TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''分层构建是否重新构建基础镜像：0复用已有稳定base(跳过) 1重建并推送稳定base'' AFTER dependency_hash',
    'SELECT ''skip add column'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

-- 第二步：无条件收敛列定义（表存在时）。
-- 兼顾两种场景：刚新增的列确认类型与注释；早已存在的列可能注释过时，一并覆盖。
SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE build MODIFY COLUMN rebuild_base_image TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''分层构建是否重新构建基础镜像：0复用已有稳定base(跳过) 1重建并推送稳定base''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- 索引检查：本列不作为查询条件，无需建索引。
