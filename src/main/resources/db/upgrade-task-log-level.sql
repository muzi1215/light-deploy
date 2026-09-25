-- =============================================================================
-- upgrade-task-log-level.sql
-- 任务日志级别由四级（DEBUG/INFO/WARN/ERROR）精简为两级（INFO/ERROR）
--
-- 【为什么需要】
-- 任务日志面向页面展示执行过程，级别过多会让配色、过滤和检索都变复杂；而"警告"类信息
-- 在部署场景里要么可忽略、要么已经是失败，没有独立存在的中间态：
--   - WARN：提示类（如"服务器未配置部署根目录""输出被截断"）→ 归 INFO；
--   - DEBUG：代码里从未使用，且应用根日志级别为 INFO，本来也不会出现在控制台 → 归 INFO；
--   - ERROR：失败信息，保留。
-- 精简后后端枚举 TaskLogLevel 只剩 INFO / ERROR，页面标签与服务端日志检索都只需区分两种。
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/upgrade-task-log-level.sql
-- 或在 MySQL 客户端中：
--   USE <db_name>;
--   SOURCE src/main/resources/db/upgrade-task-log-level.sql;
--
-- 【所需权限】
--   UPDATE（改写历史日志级别）+ ALTER（收敛列定义）+ 对 information_schema 的读取权限。
--
-- 【幂等性】
--   先判断「表是否存在」，不存在则整段跳过，不会因库未初始化而中断；
--   数据改写用 NOT IN ('INFO','ERROR') 作为条件，已归一化的行不会被重复更新，
--   因此可以反复执行，结果一致。
--
-- 【旧值映射】
--   DEBUG → INFO、WARN → INFO、任何其他非法值 → INFO；ERROR 保持不变。
--   注意：改写只影响展示与检索口径，日志内容（content）与顺序号完全不动，
--   历史任务的执行过程可以照原样回放。
--
-- 【执行后抽查】
--   SELECT log_level, COUNT(*) FROM lds_task_log WHERE is_deleted = 0 GROUP BY log_level;
--   期望只剩 INFO 与 ERROR 两种取值。再执行配套校验脚本：
--   SOURCE src/main/resources/db/verify-task-log-level.sql;
-- =============================================================================

-- 当前库名与改造目标，后续所有元数据判断都以此为条件，避免改到别的库。
SET @db_name = DATABASE();
SET @table_name = 'lds_task_log';

SET @table_exists = (
    SELECT COUNT(*) FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = @db_name AND TABLE_NAME = @table_name
);

-- 第一步：把非 INFO / ERROR 的历史级别统一收敛为 INFO。
-- 用 IF 包裹是为了在表不存在时跳过，避免脚本因表缺失而中断。
SET @normalize_sql = IF(
    @table_exists = 1,
    'UPDATE `lds_task_log` SET log_level = ''INFO'' WHERE log_level NOT IN (''INFO'', ''ERROR'')',
    'SELECT ''skip normalize log_level'' AS result'
);
PREPARE normalize_stmt FROM @normalize_sql;
EXECUTE normalize_stmt;
DEALLOCATE PREPARE normalize_stmt;

-- 第二步：无条件收敛列定义（表存在时），让注释与新枚举口径一致。
SET @modify_sql = IF(
    @table_exists = 1,
    'ALTER TABLE `lds_task_log` MODIFY COLUMN log_level VARCHAR(10) NOT NULL DEFAULT ''INFO'' COMMENT ''日志级别：INFO/ERROR''',
    'SELECT ''skip modify column'' AS result'
);
PREPARE modify_stmt FROM @modify_sql;
EXECUTE modify_stmt;
DEALLOCATE PREPARE modify_stmt;

-- 索引检查：log_level 仅作为展示与等值过滤条件，schema.sql 中未为其建索引，此处无需补索引。
-- 若将来出现「只查 ERROR 日志」的高频需求，再随需求一起加索引并同步 schema.sql。
