-- =============================================================================
-- verify-release-version-seq.sql
-- 校验 upgrade-release-version-seq.sql 的执行结果
--
-- 【执行方式】
--   mysql -u<user> -p <db_name> < src/main/resources/db/verify-release-version-seq.sql
-- 或在 MySQL 客户端中：USE <db_name>; SOURCE src/main/resources/db/verify-release-version-seq.sql;
--
-- 【结果解读】
--   全部语句应当返回「无异常」。出现非空结果时，按每段上方的说明处理。
-- =============================================================================

-- 1) 列定义：两个 version_seq 都应存在，类型 INT、允许为 NULL。
--    预期两行；缺任何一行说明升级脚本未执行成功。
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND COLUMN_NAME = 'version_seq'
  AND TABLE_NAME IN ('release_record', 'build')
ORDER BY TABLE_NAME;

-- 2) 索引：release_record 上应有 (project_id, version_seq) 组合索引。
--    预期一行；缺失会让取号查询退化为全表扫描。
SELECT INDEX_NAME, SEQ_IN_INDEX, COLUMN_NAME
FROM information_schema.STATISTICS
WHERE TABLE_SCHEMA = DATABASE()
  AND TABLE_NAME = 'release_record'
  AND INDEX_NAME = 'idx_release_project_seq'
ORDER BY SEQ_IN_INDEX;

-- 3) 回填完整性：升级后不应再有 version_seq 为 NULL 的版本。
--    若返回行数 > 0，说明回填未生效，可重跑升级脚本（回填段落只在列新增那一次执行，
--    已加过列时需手工执行 verify 结果中的这条 UPDATE）。
SELECT COUNT(*) AS release_null_seq_count
FROM release_record
WHERE version_seq IS NULL;

-- 4) 序号重复：同一项目内 version_seq 必须唯一。
--    预期返回空结果。若有重复，说明历史回填异常或并发取号撞车，需人工核对后再调整。
SELECT project_id, version_seq, COUNT(*) AS dup_count, GROUP_CONCAT(version ORDER BY create_time SEPARATOR ' | ') AS versions
FROM release_record
GROUP BY project_id, version_seq
HAVING COUNT(*) > 1;

-- 5) 抽样核对：版本号、序号、提交短码的对应关系。
--    老数据 version 形如 auth-7888-887b603c（无序号段），新数据形如 auth-7888-v4-887b603c。
SELECT project_id, version_seq, version, commit_id, status, create_time
FROM release_record
ORDER BY project_id, version_seq DESC
LIMIT 30;

-- 6) 指定了序号的构建是否都已落地为版本。
--    正常情况下 build 成功即生成版本；若某条构建成功却没有对应序号的版本，
--    说明构建收尾阶段失败（版本未落库），该产物无法被部署。
SELECT b.id AS build_id, b.version_seq, b.status, b.git_commit, r.version, r.version_seq AS release_seq
FROM build b
LEFT JOIN release_record r
       ON r.project_id = b.project_id
      AND r.version_seq = b.version_seq
WHERE b.version_seq IS NOT NULL
ORDER BY b.create_time DESC
LIMIT 30;
