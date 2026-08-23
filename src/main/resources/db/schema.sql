/*
 * Light Deploy 当前最小数据库结构。
 * 统一字符集：utf8mb4；统一排序规则：utf8mb4_unicode_ci。
 * 普通业务表不增加前缀，任务域表使用 lds_ 前缀。
 */

CREATE TABLE IF NOT EXISTS `user` (
    id VARCHAR(32) NOT NULL COMMENT '用户主键，MyBatis-Plus生成的32位UUID',
    username VARCHAR(64) NOT NULL COMMENT '登录用户名',
    password_hash VARCHAR(255) NOT NULL COMMENT '密码哈希，不保存明文密码',
    nickname VARCHAR(100) NULL COMMENT '用户昵称',
    email VARCHAR(150) NULL COMMENT '邮箱地址',
    mobile VARCHAR(30) NULL COMMENT '手机号码',
    avatar_url VARCHAR(500) NULL COMMENT '头像地址',
    external_user_id VARCHAR(100) NULL COMMENT '统一权限中心用户ID，后续迁移使用',
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '用户状态：ENABLED/DISABLED/LOCKED',
    last_login_time DATETIME NULL COMMENT '最后登录时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_username (username, is_deleted),
    KEY idx_user_external_id (external_user_id),
    KEY idx_user_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='临时用户表，接入统一权限中心后逐步停用';

CREATE TABLE IF NOT EXISTS `server` (
    id VARCHAR(32) NOT NULL COMMENT '服务器主键，MyBatis-Plus生成的32位UUID',
    server_name VARCHAR(100) NOT NULL COMMENT '服务器名称',
    host VARCHAR(255) NOT NULL COMMENT '服务器IP地址或域名',
    ssh_port INT NOT NULL DEFAULT 22 COMMENT 'SSH端口',
    username VARCHAR(100) NOT NULL COMMENT 'SSH登录用户',
    auth_type TINYINT NOT NULL DEFAULT 1 COMMENT 'SSH认证类型：1密码，2私钥',
    ciphertext TEXT NOT NULL COMMENT 'AES-GCM加密后的SSH密码或私钥内容',
    env_tag TINYINT NOT NULL DEFAULT 1 COMMENT '环境标签：1开发，2测试，3生产',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '服务器状态：0停用，1启用',
    description VARCHAR(500) NULL COMMENT '服务器描述',
    created_by VARCHAR(100) NULL COMMENT '创建人用户ID',
    updated_by VARCHAR(100) NULL COMMENT '更新人用户ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_server_host_port (host, ssh_port, is_deleted),
    KEY idx_server_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部署服务器';

CREATE TABLE IF NOT EXISTS lds_task (
    id VARCHAR(32) NOT NULL COMMENT '任务主键，MyBatis-Plus生成的32位UUID',
    task_no VARCHAR(64) NOT NULL COMMENT '任务编号',
    task_type VARCHAR(30) NOT NULL COMMENT '任务类型：SERVER_TEST/BUILD/DEPLOY',
    biz_id VARCHAR(32) NULL COMMENT '关联业务主键',
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED' COMMENT '任务状态：QUEUED/RUNNING/SUCCESS/FAILED/CANCELED',
    current_stage VARCHAR(30) NULL COMMENT '当前执行阶段',
    progress INT NOT NULL DEFAULT 0 COMMENT '任务进度，0到100',
    started_at DATETIME NULL COMMENT '任务开始时间',
    finished_at DATETIME NULL COMMENT '任务结束时间',
    error_message TEXT NULL COMMENT '任务失败原因',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lds_task_no (task_no, is_deleted),
    KEY idx_lds_task_biz (task_type, biz_id),
    KEY idx_lds_task_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='异步任务';

CREATE TABLE IF NOT EXISTS lds_task_log (
    id VARCHAR(32) NOT NULL COMMENT '任务日志主键，MyBatis-Plus生成的32位UUID',
    task_id VARCHAR(32) NOT NULL COMMENT '任务主键',
    sequence_no BIGINT NOT NULL COMMENT '同一任务内的日志顺序号',
    stage VARCHAR(30) NULL COMMENT '日志所属任务阶段',
    log_level VARCHAR(10) NOT NULL DEFAULT 'INFO' COMMENT '日志级别：DEBUG/INFO/WARN/ERROR',
    content TEXT NOT NULL COMMENT '日志内容',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_lds_task_log_sequence (task_id, sequence_no),
    KEY idx_lds_task_log_task (task_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='任务执行日志';

CREATE TABLE IF NOT EXISTS credential (
    id VARCHAR(32) NOT NULL COMMENT '凭据主键',
    name VARCHAR(100) NOT NULL COMMENT '凭据名称',
    type VARCHAR(30) NOT NULL COMMENT '凭据类型：SSH_PASSWORD/SSH_KEY/GIT_TOKEN/REGISTRY',
    encrypted_value TEXT NOT NULL COMMENT 'AES-GCM加密后的凭据内容',
    metadata_json JSON NULL COMMENT '非敏感凭据元数据',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_credential_name (name, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='加密凭据';

CREATE TABLE IF NOT EXISTS registry (
    id VARCHAR(32) NOT NULL COMMENT '镜像仓库主键',
    name VARCHAR(100) NOT NULL COMMENT '仓库名称',
    registry_url VARCHAR(255) NOT NULL COMMENT '仓库地址',
    namespace VARCHAR(150) NULL COMMENT '仓库命名空间',
    credential_id VARCHAR(32) NULL COMMENT '仓库凭据ID',
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    description VARCHAR(500) NULL COMMENT '仓库描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_registry_name (name, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='镜像仓库';

CREATE TABLE IF NOT EXISTS project (
    id VARCHAR(32) NOT NULL COMMENT '项目主键',
    code VARCHAR(64) NOT NULL COMMENT '项目编码',
    name VARCHAR(100) NOT NULL COMMENT '项目名称',
    runtime_type VARCHAR(20) NOT NULL COMMENT '运行时：DOCKER/JAR/STATIC',
    source_type VARCHAR(20) NOT NULL DEFAULT 'GIT' COMMENT '代码来源：GIT/UPLOAD',
    git_url VARCHAR(500) NULL COMMENT 'Git仓库地址',
    git_branch VARCHAR(100) NULL COMMENT 'Git分支',
    git_credential_id VARCHAR(32) NULL COMMENT 'Git凭据ID',
    build_mode VARCHAR(30) NOT NULL DEFAULT 'PLATFORM_TEMPLATE' COMMENT '构建模式',
    build_config_json JSON NULL COMMENT '构建配置',
    runtime_config_json JSON NULL COMMENT '运行配置',
    health_config_json JSON NULL COMMENT '健康检查配置',
    retain_release_count INT NOT NULL DEFAULT 5 COMMENT '保留发布版本数量',
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    description VARCHAR(500) NULL COMMENT '项目描述',
    created_by VARCHAR(100) NULL COMMENT '创建人',
    updated_by VARCHAR(100) NULL COMMENT '更新人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_code (code, is_deleted),
    KEY idx_project_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部署项目';

CREATE TABLE IF NOT EXISTS environment (
    id VARCHAR(32) NOT NULL COMMENT '环境主键',
    code VARCHAR(32) NOT NULL COMMENT '环境编码：dev/test/prod',
    name VARCHAR(100) NOT NULL COMMENT '环境名称',
    deploy_strategy VARCHAR(30) NOT NULL DEFAULT 'SINGLE_TARGET' COMMENT '部署策略',
    batch_size INT NOT NULL DEFAULT 1 COMMENT '滚动发布批次大小',
    default_server_id VARCHAR(32) NULL COMMENT '默认目标服务器ID',
    aiops_enabled TINYINT NOT NULL DEFAULT 0 COMMENT '是否启用AIops联动',
    description VARCHAR(500) NULL COMMENT '环境描述',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_environment_code (code, is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部署环境';

CREATE TABLE IF NOT EXISTS project_environment_target (
    id VARCHAR(32) NOT NULL COMMENT '项目环境目标主键',
    project_id VARCHAR(32) NOT NULL COMMENT '项目ID',
    environment_id VARCHAR(32) NOT NULL COMMENT '环境ID',
    server_id VARCHAR(32) NOT NULL COMMENT '目标服务器ID',
    is_primary TINYINT NOT NULL DEFAULT 0 COMMENT '是否为主目标',
    deploy_order INT NOT NULL DEFAULT 1 COMMENT '多目标发布顺序',
    runtime_config_json JSON NULL COMMENT '目标差异化运行配置',
    status VARCHAR(20) NOT NULL DEFAULT 'ENABLED' COMMENT '状态：ENABLED/DISABLED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_project_env_server (project_id, environment_id, server_id, is_deleted),
    KEY idx_target_server (server_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='项目环境部署目标';

CREATE TABLE IF NOT EXISTS build (
    id VARCHAR(32) NOT NULL COMMENT '构建主键',
    project_id VARCHAR(32) NOT NULL COMMENT '项目ID',
    deployment_id VARCHAR(32) NULL COMMENT '部署任务ID',
    build_mode VARCHAR(30) NOT NULL COMMENT '构建模式',
    build_server_id VARCHAR(32) NULL COMMENT '构建服务器ID',
    git_commit VARCHAR(100) NULL COMMENT 'Git提交ID',
    dependency_hash VARCHAR(128) NULL COMMENT '依赖文件摘要',
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED' COMMENT '构建状态',
    started_at DATETIME NULL COMMENT '开始时间',
    finished_at DATETIME NULL COMMENT '结束时间',
    error_message TEXT NULL COMMENT '错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_build_project (project_id, create_time),
    KEY idx_build_deployment (deployment_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建任务';

CREATE TABLE IF NOT EXISTS build_artifact (
    id VARCHAR(32) NOT NULL COMMENT '构建产物主键',
    build_id VARCHAR(32) NOT NULL COMMENT '构建任务ID',
    artifact_type VARCHAR(20) NOT NULL COMMENT '产物类型：BASE_IMAGE/APP_IMAGE/JAR/STATIC',
    artifact_name VARCHAR(255) NOT NULL COMMENT '产物名称',
    artifact_tag VARCHAR(255) NULL COMMENT '镜像或产物标签',
    artifact_digest VARCHAR(255) NULL COMMENT '镜像摘要',
    dependency_hash VARCHAR(128) NULL COMMENT '依赖摘要',
    dockerfile_path VARCHAR(255) NULL COMMENT 'Dockerfile路径',
    metadata_json JSON NULL COMMENT '产物元数据',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_artifact_build (build_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='构建产物';

CREATE TABLE IF NOT EXISTS release_record (
    id VARCHAR(32) NOT NULL COMMENT '发布版本主键',
    project_id VARCHAR(32) NOT NULL COMMENT '项目ID',
    version VARCHAR(100) NOT NULL COMMENT '发布版本号',
    commit_id VARCHAR(100) NULL COMMENT 'Git提交ID',
    artifact_path VARCHAR(500) NULL COMMENT 'JAR或静态产物路径',
    image_tag VARCHAR(255) NULL COMMENT 'Docker镜像标签',
    image_digest VARCHAR(255) NULL COMMENT 'Docker镜像摘要',
    sha256 VARCHAR(128) NULL COMMENT '产物SHA256摘要',
    runtime_type VARCHAR(20) NOT NULL COMMENT '运行时类型',
    status VARCHAR(20) NOT NULL DEFAULT 'BUILT' COMMENT '版本状态',
    created_by VARCHAR(100) NULL COMMENT '创建人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_release_project_version (project_id, version, is_deleted),
    KEY idx_release_project (project_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='发布版本';

CREATE TABLE IF NOT EXISTS deployment (
    id VARCHAR(32) NOT NULL COMMENT '部署主任务主键',
    project_id VARCHAR(32) NOT NULL COMMENT '项目ID',
    environment_id VARCHAR(32) NOT NULL COMMENT '环境ID',
    release_id VARCHAR(32) NULL COMMENT '发布版本ID',
    strategy VARCHAR(30) NOT NULL DEFAULT 'SINGLE_TARGET' COMMENT '部署策略',
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED' COMMENT '部署状态',
    operator_id VARCHAR(100) NULL COMMENT '操作者ID',
    operator_name VARCHAR(100) NULL COMMENT '操作者名称',
    aiops_silence_id VARCHAR(100) NULL COMMENT 'AIops静默窗口ID',
    started_at DATETIME NULL COMMENT '开始时间',
    finished_at DATETIME NULL COMMENT '结束时间',
    error_message TEXT NULL COMMENT '错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_deployment_project_env (project_id, environment_id, create_time),
    KEY idx_deployment_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部署主任务';

CREATE TABLE IF NOT EXISTS deployment_target (
    id VARCHAR(32) NOT NULL COMMENT '部署目标主键',
    deployment_id VARCHAR(32) NOT NULL COMMENT '部署主任务ID',
    server_id VARCHAR(32) NOT NULL COMMENT '目标服务器ID',
    status VARCHAR(30) NOT NULL DEFAULT 'QUEUED' COMMENT '目标任务状态',
    old_version VARCHAR(100) NULL COMMENT '旧版本',
    new_version VARCHAR(100) NULL COMMENT '新版本',
    image_tag VARCHAR(255) NULL COMMENT '镜像标签',
    health_status VARCHAR(30) NULL COMMENT '健康检查状态',
    error_message TEXT NULL COMMENT '错误信息',
    started_at DATETIME NULL COMMENT '开始时间',
    finished_at DATETIME NULL COMMENT '结束时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_deployment_target_deploy (deployment_id),
    KEY idx_deployment_target_server (server_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='部署目标任务';

CREATE TABLE IF NOT EXISTS aiops_event (
    id VARCHAR(32) NOT NULL COMMENT 'AIops事件主键',
    deployment_id VARCHAR(32) NOT NULL COMMENT '部署任务ID',
    event_id VARCHAR(128) NOT NULL COMMENT '事件ID',
    event_type VARCHAR(50) NOT NULL COMMENT '事件类型',
    idempotency_key VARCHAR(150) NOT NULL COMMENT '幂等键',
    request_body JSON NULL COMMENT '请求内容',
    response_body JSON NULL COMMENT '响应内容',
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '调用状态',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '重试次数',
    next_retry_time DATETIME NULL COMMENT '下次重试时间',
    error_message TEXT NULL COMMENT '错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_aiops_event_key (idempotency_key, is_deleted),
    KEY idx_aiops_deployment (deployment_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AIops联动事件';

CREATE TABLE IF NOT EXISTS audit_log (
    id VARCHAR(32) NOT NULL COMMENT '审计日志主键',
    operator_id VARCHAR(100) NULL COMMENT '操作者ID',
    operator_name VARCHAR(100) NULL COMMENT '操作者名称',
    action VARCHAR(50) NOT NULL COMMENT '操作动作',
    resource_type VARCHAR(50) NULL COMMENT '资源类型',
    resource_id VARCHAR(100) NULL COMMENT '资源ID',
    request_uri VARCHAR(500) NULL COMMENT '请求地址',
    request_body JSON NULL COMMENT '请求内容',
    result VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT '操作结果',
    error_message VARCHAR(1000) NULL COMMENT '错误信息',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    is_deleted TINYINT NOT NULL DEFAULT 0 COMMENT '软删除标记：0未删除，1已删除',
    PRIMARY KEY (id),
    KEY idx_audit_operator (operator_id, create_time),
    KEY idx_audit_resource (resource_type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='操作审计日志';
