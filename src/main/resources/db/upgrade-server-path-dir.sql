-- =============================================================================
-- upgrade-server-path-dir.sql
-- server 表增加三个路径配置列：build_workspace_dir / build_artifact_dir / agent_base_dir
--
-- 【为什么需要】
-- 这三个目录此前是代码里的配置项（application.yaml 的 light-deploy.build.workspace-base、
-- light-deploy.build.artifact-base、light-deploy.agent.base-dir），靠 @Value 的默认值兜底。
-- 问题在于它们都是「某台机器上的目录」：不同构建机挂的数据盘不同、不同目标机的部署根目录
-- 也可能不同，按机器配置才符合运维现实；而且改一个目录要动配置文件并重启平台，属于
-- 不该出现的运维成本。因此下沉为 server 表的列，在服务器页面按机器配置。
--   - build_workspace_dir：构建机上的临时工作区根目录，每次构建建子目录、结束后清理；
--   - build_artifact_dir：构建机上的产物持久目录，产物转存于此供发布阶段下载，不能随工作区清理；
--   - agent_base_dir：平台在该机器上的部署根目录，连通性测试检查其存在且可写，
--                     应用实际部署在其 apps 子目录下（由代码拼接，不单独配置）。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-server-path-dir.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-server-path-dir.sql;
--
-- 【所需权限】
--   ALTER（新增列）+ 对 information_schema 的读取权限（元数据判断）。
--
-- 【幂等性】
--   每列都先判断「表是否存在」「列是否已存在」：
--   - 表不存在：整段跳过，不会因库未初始化而中断；
--   - 列已存在：跳过 ADD COLUMN，只执行 MODIFY COLUMN 收敛定义；
--   因此可以反复执行，结果一致。
--
-- 【回退默认值策略】
--   三列均为 NOT NULL 且带 DEFAULT，新增时既有行由 MySQL 自动填入默认值，
--   不会因「既有数据缺少该列」而失败，也不会出现空目录路径导致构建/部署报错。
--   这里不存在旧值映射——三列是新增配置能力，此前只存在于配置文件中。
--   升级后如需让某台机器使用非默认目录，在服务器页面修改即可。
--
-- 【执行后抽查】
--   SELECT id, server_name, build_workspace_dir, build_artifact_dir, agent_base_dir
--   FROM server WHERE is_deleted = 0 ORDER BY create_time DESC LIMIT 10;
--   再执行配套校验脚本：
--   SOURCE src/main/resources/db/verify-server-path-dir.sql;
-- =============================================================================

-- 当前库名与改造目标，后续所有元数据判断都以此为条件，避免改到别的库。
SET @db_name = DATABASE();
SET @table_name = 'server';

-- ---------------------------------------------------------------------------
-- 列一：build_workspace_dir（构建工作区根目录，仅构建机使用）
-- ---------------------------------------------------------------------------
SET @column_name = 'build_workspace_dir';

SET @table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name
);

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND COLUMN_NAME = @column_name
);

-- 第一步：仅当「表存在且列不存在」时新增列，位置紧跟 description，与 schema.sql 保持一致。
SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE `server` ADD COLUMN build_workspace_dir VARCHAR(255) NOT NULL DEFAULT ''/opt/light-deploy/build-workspace'' COMMENT ''构建工作区根目录，仅构建机使用：每次构建在其下创建独立子目录，结束后清理'' AFTER description',
    'SELECT ''skip add column'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

-- 第二步：无条件收敛列定义（表存在时）。
-- 兼顾两种场景：刚新增的列确认类型与注释；早已存在的列可能注释过时，一并覆盖成当前定义。
SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE `server` MODIFY COLUMN build_workspace_dir VARCHAR(255) NOT NULL DEFAULT ''/opt/light-deploy/build-workspace'' COMMENT ''构建工作区根目录，仅构建机使用：每次构建在其下创建独立子目录，结束后清理''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- ---------------------------------------------------------------------------
-- 列二：build_artifact_dir（构建产物持久目录，仅构建机使用）
-- ---------------------------------------------------------------------------
SET @column_name = 'build_artifact_dir';

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND COLUMN_NAME = @column_name
);

SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE `server` ADD COLUMN build_artifact_dir VARCHAR(255) NOT NULL DEFAULT ''/opt/light-deploy/artifacts'' COMMENT ''构建产物持久目录，仅构建机使用：产物转存于此供发布阶段下载，不随工作区清理'' AFTER build_workspace_dir',
    'SELECT ''skip add column'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE `server` MODIFY COLUMN build_artifact_dir VARCHAR(255) NOT NULL DEFAULT ''/opt/light-deploy/artifacts'' COMMENT ''构建产物持久目录，仅构建机使用：产物转存于此供发布阶段下载，不随工作区清理''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- ---------------------------------------------------------------------------
-- 列三：agent_base_dir（平台在该服务器上的部署根目录）
-- ---------------------------------------------------------------------------
SET @column_name = 'agent_base_dir';

SET @column_exists = (
    SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name AND COLUMN_NAME = @column_name
);

SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE `server` ADD COLUMN agent_base_dir VARCHAR(255) NOT NULL DEFAULT ''/opt/light-deploy'' COMMENT ''平台在该服务器上的部署根目录，连通性测试检查其存在且可写，应用部署在其 apps 子目录下'' AFTER build_artifact_dir',
    'SELECT ''skip add column'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE `server` MODIFY COLUMN agent_base_dir VARCHAR(255) NOT NULL DEFAULT ''/opt/light-deploy'' COMMENT ''平台在该服务器上的部署根目录，连通性测试检查其存在且可写，应用部署在其 apps 子目录下''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- 索引检查：三列都是配置值，不作为查询条件，schema.sql 中也没有为它们建索引，此处无需补索引。
-- 若将来出现「按部署根目录筛选服务器」之类的需求，再随需求一起加索引并同步 schema.sql。
