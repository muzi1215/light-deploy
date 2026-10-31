-- =============================================================================
-- upgrade-deployment-force-overwrite-config.sql
-- deployment 增加 force_overwrite_config 列（部署时是否强制覆盖目标机已有配置文件）
--
-- 【为什么需要】
-- 配置文件随版本转存到构建机产物目录，发布时投放到目标机 <work_dir>/config。
-- 默认策略是「目标机已存在同名文件则跳过，不覆盖」——保护运维手动改过的现场配置
-- （例如改了库地址 / 账号）。但这条规则有个副作用：当配置本就是仓库管理的，
-- 运维改了仓库配置 → 重建 → 重发，平台发现旧文件还在就跳过，新配置传不下去。
--
-- 因此加一个显式开关：勾选时平台在投放前先比对内容，内容不一致则把目标机旧配置
-- 备份到 <config>-bak-<时间戳> 再写入新配置（留退路，符合容灾底线）；
-- 内容一致则不动作。不勾选时维持「存在即跳过」的保守语义。
-- 开关在创建部署时固化进 deployment 记录，异步任务执行时再读，避免任务排队期间语义漂移。
--
-- DOCKER 与 JAR 共用这一列与同一条投放逻辑（见 DeploySupport.uploadConfigFiles）。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-deployment-force-overwrite-config.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-deployment-force-overwrite-config.sql;
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
--   新增列 NOT NULL DEFAULT 0（false）。既有部署记录一律视为「不强制覆盖」，
--   与升级前的行为完全一致，不会因加列而意外改变任何一次发布的结果。
--
-- 【执行后抽查】
--   SELECT id, version, status, force_overwrite_config FROM deployment ORDER BY create_time DESC LIMIT 10;
--   再执行配套校验脚本：
--   SOURCE src/main/resources/db/verify-deployment-force-overwrite-config.sql;
-- =============================================================================

-- 当前库名与改造目标，后续所有元数据判断都以此为条件，避免改到别的库。
SET @db_name = DATABASE();
SET @table_name = 'deployment';
SET @column_name = 'force_overwrite_config';

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

-- 第一步：仅当「表存在且列不存在」时新增列，位置紧跟 strategy（部署策略）之后，语义上同属「本次发布的执行选项」。
SET @add_sql = IF(
    @table_exists = 1 AND @column_exists = 0,
    'ALTER TABLE deployment ADD COLUMN force_overwrite_config TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''部署时是否强制覆盖目标机已有配置文件：0不覆盖(跳过) 1覆盖(内容不一致则先备份)'' AFTER strategy',
    'SELECT ''skip add column'' AS result'
);
PREPARE add_stmt FROM @add_sql;
EXECUTE add_stmt;
DEALLOCATE PREPARE add_stmt;

-- 第二步：无条件收敛列定义（表存在时）。
-- 兼顾两种场景：刚新增的列确认类型与注释；早已存在的列可能注释过时，一并覆盖。
SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE deployment MODIFY COLUMN force_overwrite_config TINYINT(1) NOT NULL DEFAULT 0 COMMENT ''部署时是否强制覆盖目标机已有配置文件：0不覆盖(跳过) 1覆盖(内容不一致则先备份)''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- 索引检查：本列不作为查询条件，无需建索引。
