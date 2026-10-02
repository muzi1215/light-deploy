-- 发布版本新增：DOCKER 构建上下文 tarball 路径
-- 背景：COMPOSE 形态且指定目标机 work_dir 时，构建阶段把构建上下文（仓库源码快照，排除 .git 与
--       构建产物目录）打成 tarball 落构建机产物目录，发布阶段随 compose 一起下发到目标机并解包，
--       方便运维上机 docker build 再次构建。
-- 兼容：存量版本该列恒为空，发布阶段据此跳过上下文下发；镜像仍从仓库拉取，回滚不受影响。
-- 执行：部署链路不自动执行 DDL，本文件须由开发者确认后手工在 MySQL 执行（一次即可，幂等可重复跑）。
ALTER TABLE release_record
    ADD COLUMN context_artifact_path VARCHAR(500) NULL
    COMMENT 'DOCKER 运行时构建上下文 tarball 在构建机上的绝对路径' AFTER compose_content;
