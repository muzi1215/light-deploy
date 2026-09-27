/**
 * Light Deploy 后端接口通用类型定义。
 *
 * 与后端 com.lx.deploy.common 包中的 ApiResponse / PageResult / PageQuery 一一对应。
 * 后端契约：
 * - 统一响应 code === 200 表示成功，message 为提示信息，requestId 用于日志关联；
 * - 分页参数 pageNum / pageSize（pageNum 从 1 开始，pageSize 最大 100）；
 * - 分页结果返回 pageNum、pageSize、total、pages、records。
 */

/** 后端统一响应结构 */
export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
  timestamp?: string
  requestId?: string
}

/** 后端统一分页结果结构（PageResult.java） */
export interface PageResult<T = any> {
  pageNum: number
  pageSize: number
  total: number
  pages: number
  records: T[]
}

/** 分页查询参数（PageQuery.java） */
export interface PageParams {
  pageNum: number
  pageSize: number
  /** 创建时间起始（含），yyyy-MM-dd HH:mm:ss；全平台列表统一口径，见 AGENTS.md §10.4 */
  createTimeBegin?: string
  /** 创建时间截止（含），yyyy-MM-dd HH:mm:ss */
  createTimeEnd?: string
  [key: string]: any
}

/** 列表查询条件（不分页） */
export interface ListParams {
  [key: string]: any
}

// ==================== 服务器模块类型 ====================

/** 服务器 VO（不包含密文） */
export interface ServerVo {
  id: string
  createTime: string
  updateTime: string
  serverName: string
  host: string
  sshPort: number
  username: string
  authType: number // 1 密码，2 私钥
  envTag: number // 1 开发，2 测试，3 生产
  status: number // 0 停用，1 启用
  description: string
  /** 构建工作区根目录，仅构建机使用：每次构建在其下建临时子目录，结束后清理 */
  buildWorkspaceDir: string
  /** 构建产物持久目录，仅构建机使用：产物转存于此供发布下载，不随工作区清理 */
  buildArtifactDir: string
  /** 平台在该服务器上的部署根目录，应用部署在其 apps 子目录下 */
  agentBaseDir: string
  /** 构建环境初始化脚本，仅构建机使用：执行构建命令前在当前 shell 执行，用于补 PATH 等环境变量；未配置为 null */
  buildEnvScript: string | null
  createdBy: string
  updatedBy: string
}

/** 服务器查询/新增/修改 DTO（credential 只写不读） */
export interface ServerDto {
  serverName: string
  host: string
  sshPort?: number
  username: string
  authType?: number
  /** SSH 密码或私钥明文；新增必填，修改选填（不传保留旧凭据） */
  credential?: string
  envTag?: number
  status?: number
  description?: string
  /** 构建工作区根目录，仅构建机使用；留空则使用数据库默认值 */
  buildWorkspaceDir?: string
  /** 构建产物持久目录，仅构建机使用；留空则使用数据库默认值 */
  buildArtifactDir?: string
  /** 平台在该服务器上的部署根目录；留空则使用数据库默认值 */
  agentBaseDir?: string
  /** 构建环境初始化脚本，仅构建机使用；留空表示不执行额外脚本（平台仍会 `. /etc/profile` 兜底） */
  buildEnvScript?: string
}

// ==================== 项目模块类型 ====================

/** 项目 VO */
export interface ProjectVo {
  id: string
  createTime: string
  updateTime: string
  code: string
  name: string
  runtimeType: string // DOCKER / JAR / STATIC
  sourceType: string // GIT / UPLOAD
  gitUrl?: string
  gitBranch?: string
  gitCredentialId?: string
  buildMode: string // PLATFORM_TEMPLATE / PROJECT_DOCKERFILE / LAYERED_DOCKER / SCRIPT_DEPLOY / ARTIFACT_ONLY
  registryId?: string // 镜像仓库 ID，仅 DOCKER 运行时项目有值
  /**
   * 项目档案绑定的构建机（服务器 ID），选填。
   *
   * 它是**构建与连通性测试的执行位置**：构建时留空由后端回落到系统默认构建机，
   * 连通性测试则必须有值——没有执行目标就测不出「这台机器能否拉到代码」。
   */
  buildServerId?: string
  /** 构建机名称，由服务端按 buildServerId 批量回填，仅用于展示 */
  buildServerName?: string
  buildConfigJson?: string
  runtimeConfigJson?: string
  healthConfigJson?: string
  retainReleaseCount: number
  status: number // 0 停用，1 启用
  description?: string
  createdBy?: string
  updatedBy?: string
}

/** 项目查询/新增/修改 DTO */
export interface ProjectDto {
  code: string
  name: string
  runtimeType?: string
  sourceType?: string
  gitUrl?: string
  gitBranch?: string
  gitCredentialId?: string
  buildMode?: string
  /** 镜像仓库 ID；仅 DOCKER 运行时可填，脚本部署 / 仅发布产物传了会被后端拒绝 */
  registryId?: string
  /**
   * 构建机（服务器 ID），选填：留空表示构建时回落到系统默认构建机。
   * 绑定后，新建构建默认带出该机器，连通性测试也在这台机器上执行。
   */
  buildServerId?: string
  buildConfigJson?: string
  runtimeConfigJson?: string
  healthConfigJson?: string
  retainReleaseCount?: number
  status?: number
  description?: string
}

// ==================== 环境模块类型 ====================

/**
 * 环境 VO。
 *
 * 不含任何服务器字段：环境是全局共享的，某项目在该环境下发哪些机器由部署目标决定，
 * 环境层面给不出有意义的「默认服务器」（详见 AGENTS.md §24.11）。
 */
export interface EnvironmentVo {
  id: string
  createTime: string
  updateTime: string
  code: string // dev / test / prod
  name: string
  deployStrategy: string // SINGLE_TARGET / ALL_AT_ONCE / ROLLING
  batchSize: number
  aiopsEnabled: number // 0 / 1
  description?: string
}

/**
 * 项目已绑定环境的下拉选项。
 *
 * 只含「选环境」需要的字段：环境身份 + 该项目在该环境下的启用目标台数，
 * 页面渲染为「生产环境（2 台）」。台数为 0 的环境不会出现在列表里。
 */
export interface EnvironmentOptionVo {
  id: string
  code: string
  name: string
  targetCount: number // 该项目在该环境下的启用目标台数，至少为 1
  deployStrategy: string // 环境默认策略，用于展示"临时覆盖"的对照
  batchSize: number
}

/** 环境查询/新增/修改 DTO */
export interface EnvironmentDto {
  code: string
  name: string
  deployStrategy?: string
  batchSize?: number
  aiopsEnabled?: number
  description?: string
}

// ==================== 部署目标模块类型 ====================

/** 项目环境部署目标 VO */
export interface DeployTargetVo {
  id: string
  createTime: string
  updateTime: string
  projectId: string
  environmentId: string
  serverId: string
  isPrimary: number // 0 / 1
  deployOrder: number
  runtimeConfigJson?: string
  status: number // 0 停用，1 启用
}

/** 部署目标查询/新增/修改 DTO */
export interface DeployTargetDto {
  projectId: string
  environmentId: string
  serverId: string
  isPrimary?: number
  deployOrder?: number
  runtimeConfigJson?: string
  status?: number
}

// ==================== 任务模块类型 ====================

/** 异步任务 VO */
export interface TaskVo {
  id: string
  createTime: string
  updateTime: string
  taskNo: string
  taskType: string // SERVER_TEST / BUILD / DEPLOY
  bizId?: string
  status: number // 0 排队中，1 执行中，2 成功，3 失败，4 已取消
  currentStage?: string
  progress: number // 0-100
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
}

/** 异步任务查询/新增/修改 DTO */
export interface TaskDto {
  taskNo?: string
  taskType?: string
  bizId?: string
  status?: number
  currentStage?: string
  progress?: number
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
}

/** 任务执行日志 VO */
export interface TaskLogVo {
  id: string
  createTime: string
  updateTime: string
  taskId: string
  sequenceNo: number
  stage?: string
  logLevel: string // INFO / ERROR
  content: string
}

/**
 * 任务日志增量拉取结果（对应后端 TaskLogPullVo）。
 *
 * 后端 /taskLog/pull 的响应：logs 只包含 sequenceNo 大于请求 lastSeq 的增量日志，
 * finished 为 true 表示任务已进入终态（成功/失败/已取消），前端应停止轮询。
 */
export interface TaskLogPullVo {
  taskId: string
  status: number // 0 排队中，1 执行中，2 成功，3 失败，4 已取消
  statusText: string // 服务端统一给出的状态中文描述
  finished: boolean // true 表示终态，停止轮询
  currentStage?: string
  progress: number // 0-100
  errorMessage?: string
  logs: TaskLogVo[]
  /** 本次返回的最大顺序号，下次请求原样作为 lastSeq 传入 */
  lastSeq: number
}

/** 任务执行日志查询/新增/修改 DTO */
export interface TaskLogDto {
  taskId?: string
  sequenceNo?: number
  stage?: string
  logLevel?: string
  content?: string
}

// ==================== 凭据模块类型 ====================

/**
 * 凭据 VO。
 *
 * 与后端 CredentialVo 一一对应：**不含密文与明文**。后端 credential.encrypted_value
 * 从不在任何接口返回，页面编辑凭据时留空即表示不修改（AGENTS.md §7）。
 */
export interface CredentialVo {
  id: string
  createTime: string
  updateTime: string
  name: string
  type: string // GIT_TOKEN / SSH_KEY / REGISTRY / SSH_PASSWORD（保留）
  metadataJson?: string | null
}

/** 凭据查询/新增/修改 DTO */
export interface CredentialDto {
  name?: string
  type?: string
  /** 明文凭据（Git 令牌 / 私钥 / 仓库密码）：新增必填，修改选填且留空保留原凭据 */
  credential?: string
  /** 非敏感元数据 JSON，如 {"username":"light-deploy"}；禁止放明文密码 */
  metadataJson?: string
}

// ==================== 镜像仓库模块类型 ====================

/**
 * 镜像仓库 VO。
 *
 * 与后端 RegistryVo 一一对应：只返回 credentialId（凭据引用），不返回凭据内容。
 * 镜像仓库只服务于 DOCKER 运行时（AGENTS.md §23.4）。
 */
export interface RegistryVo {
  id: string
  createTime: string
  updateTime: string
  name: string
  registryUrl: string
  namespace?: string | null
  credentialId?: string | null
  status: number // 0 停用，1 启用
  description?: string | null
}

/** 镜像仓库查询/新增/修改 DTO */
export interface RegistryDto {
  name?: string
  registryUrl?: string
  namespace?: string
  /** 登录仓库的凭据 ID，必须是类型为 REGISTRY 的凭据；公共仓库留空 */
  credentialId?: string
  status?: number
  description?: string
}

// ==================== 构建模块类型 ====================

/** 构建记录 VO（对应后端 BuildVo） */
export interface BuildVo {
  id: string
  createTime: string
  updateTime: string
  projectId: string
  projectName?: string
  buildMode: string // PLATFORM_TEMPLATE / PROJECT_DOCKERFILE / LAYERED_DOCKER / SCRIPT_DEPLOY / ARTIFACT_ONLY
  buildServerId?: string
  buildServerName?: string
  /** 实际克隆到的 Git 提交号（短哈希），构建完成时回填 */
  gitCommit?: string
  status: number // 0 排队中，1 构建中，2 成功，3 失败（对应后端 BuildStatus）
  /** 服务端统一给出的构建状态中文描述 */
  statusText?: string
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
  /** 关联的异步构建任务 ID，用于拉取实时日志 */
  taskId?: string
  taskNo?: string
}

/** 构建触发/修改 DTO（对应后端 BuildDto） */
export interface BuildDto {
  /** 目标项目主键，必填 */
  projectId: string
  /** 指定专用构建机主键；不传时由后端在项目配置或默认构建机中解析 */
  buildServerId?: string
  /** 版本序号（可选）：留空由后端自动递增取号；填写则生成 {项目编码}-v{序号}-{提交短码}，需该项目内未被占用 */
  versionSeq?: number
}

/** 构建产物 VO（对应后端 BuildArtifactVo） */
export interface BuildArtifactVo {
  id: string
  createTime: string
  updateTime: string
  buildId: string
  /** 产物类型：BASE_IMAGE / APP_IMAGE / JAR / STATIC */
  artifactType: string
  /** 服务端给出的产物类型中文描述 */
  artifactTypeText?: string
  artifactName: string
  artifactTag?: string
  /** 镜像摘要或文件 SHA256，构建完成时回填 */
  artifactDigest?: string
  /** 依赖指纹（基础镜像 / 依赖层的缓存 key），用于命中分层构建缓存 */
  dependencyHash?: string
  dockerfilePath?: string
  /** 产物相关的附加元数据 JSON（如镜像大小、仓库地址等） */
  metadataJson?: string
}

// ==================== 发布版本模块类型 ====================

/** 发布版本 VO（对应后端 ReleaseRecordVo） */
export interface ReleaseRecordVo {
  id: string
  projectId: string
  version: string
  commitId?: string
  /** JAR / 静态产物在构建机上的绝对路径，仅文件类运行时有值 */
  artifactPath?: string
  /** Docker 镜像标签，仅 DOCKER 运行时有值 */
  imageTag?: string
  imageDigest?: string
  sha256?: string
  runtimeType: string // DOCKER / JAR / STATIC
  status: number // 0 构建中，1 可发布，2 已废弃
  statusText?: string
  /** 产物所在构建机 ID，JAR / STATIC 分发时用于 SFTP 下载 */
  buildServerId?: string
  /**
   * 产物所在构建机名称，服务端批量回填。
   * 产物在哪台机器上是发布能否成功的前提：构建机换过或产物被回收后版本号还在但发不出去，
   * 因此下拉里一并展示，让运维在点发布前就能判断。
   */
  buildServerName?: string
  createdBy?: string
  createTime: string
  updateTime: string
}

/** 发布版本查询条件（只读接口，无写字段） */
export interface ReleaseRecordDto {
  projectId?: string
  status?: number
}

// ==================== 部署（发布）模块类型 ====================

/** 部署主任务 VO（对应后端 DeploymentVo） */
export interface DeploymentVo {
  id: string
  createTime: string
  updateTime: string
  projectId: string
  projectName?: string
  environmentId: string
  environmentName?: string
  releaseId: string
  /** 本次发布的版本号，由服务端按 releaseId 关联查出 */
  version?: string
  /**
   * 各目标机器当前实际运行的版本，列表在「已回滚」时用它补一行「当前 xxx」。
   *
   * 与 version 的区别：version 是「这次动作针对的发布版本」（记录创建时的快照，回滚后也不改写），
   * 本字段回答「机器上现在跑的是什么」。正常发布后两者相同，回滚后不同。
   * 多目标版本不一致（部分回滚、部分首次发布）时服务端返回空，此时不显示副行。
   */
  currentVersion?: string
  strategy: string // SINGLE_TARGET / ALL_AT_ONCE / ROLLING
  strategyText?: string
  status: number // 0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 回滚中 / 6 已回滚
  statusText?: string
  operatorName?: string
  aiopsSilenceId?: string
  /** 目标总数 / 成功数 / 失败数，服务端汇总后返回，页面不必二次计算 */
  targetTotal?: number
  targetSuccess?: number
  targetFailed?: number
  startedAt?: string
  finishedAt?: string
  errorMessage?: string
  /**
   * 是否允许发起回滚，由服务端按「状态是否成功 + 是否存在可用历史版本」判定。
   *
   * 与 status 是两个维度：status 为成功只说明「有可退的新版本」，还要历史版本记录与产物
   * 仍在才真的能退回去。因此按钮的可用性只能看本字段，不能自己拿 status 判断。
   */
  rollbackable?: boolean
  /** 不可回滚的原因，可回滚时为空；直接展示在回滚按钮的悬浮提示里 */
  rollbackBlockReason?: string
  /** 真正可回滚的目标数；不可回滚时为 0。由服务端判定时一并给出，页面不必再调预览接口 */
  rollbackTargetCount?: number
  /** 变更过但退不回去、将保持原版本的目标数 */
  rollbackSkippedCount?: number
  /** 关联的异步任务 ID，用于拉取发布日志 */
  taskId?: string
  taskNo?: string
}

/** 回滚预览中的单个目标（对应后端 RollbackTargetPreviewVo） */
export interface RollbackTargetPreviewVo {
  targetId: string
  serverId: string
  serverName?: string
  serverHost?: string
  /** 当前正在运行的版本，即本次部署推上线的版本 */
  currentVersion?: string
  /** 本次回滚要退回去的版本 */
  targetVersion?: string
  rollbackable: boolean
  /** 不可回滚的原因，可回滚时为空 */
  skipReason?: string
}

/** 回滚预览结果（对应后端 RollbackPreviewVo） */
export interface RollbackPreviewVo {
  rollbackable: boolean
  /** 不可回滚的原因，可回滚时为空；与接口拒绝时的文案完全一致 */
  blockReason?: string
  rollbackCount: number
  skippedCount: number
  /** 本次部署的目标总数，用于表达「N / M 台」 */
  targetTotal: number
  /** 逐目标明细，仅含本次真正变更过的目标 */
  targets: RollbackTargetPreviewVo[]
}

/** 部署创建/修改/查询 DTO（对应后端 DeploymentDto） */
export interface DeploymentDto {
  projectId: string
  environmentId: string
  releaseId: string
  /** 可选覆盖环境默认策略 */
  strategy?: string
  /** 仅作为查询条件，创建时由服务端维护 */
  status?: number
}

/** 部署目标 VO（对应后端 DeploymentTargetVo） */
export interface DeploymentTargetVo {
  id: string
  createTime: string
  updateTime: string
  deploymentId: string
  serverId: string
  serverName?: string
  serverHost?: string
  /** 发布顺序快照，创建部署时从部署目标配置固化；历史部署为空 */
  deployOrder?: number
  status: number // 0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 已跳过
  statusText?: string
  /** 发布前版本，回滚依据；首次发布为空 */
  oldVersion?: string
  newVersion?: string
  imageTag?: string
  healthStatus?: number // 0 未检查 / 1 健康 / 2 不健康
  healthStatusText?: string
  errorMessage?: string
  startedAt?: string
  finishedAt?: string
}

/** 操作日志 VO（对应后端 AuditLogVo） */
export interface AuditLogVo {
  id: string
  /** 请求追踪ID，与响应头 X-Request-Id、日志文件里的 requestId 一致 */
  traceId?: string
  /** 应用系统名称，由后端按配置 light-deploy.application-name 统一写入 */
  application?: string
  /** 操作者ID；过渡期没有登录态时为 system */
  operId?: string
  operName?: string
  /** 操作模块，来自后端 @OperLog 注解的 title */
  title?: string
  /** 操作类型编码，见 constants/auditLog.ts 的 BUSINESS_TYPE_MAP */
  businessType?: string
  businessTypeText?: string
  /** 资源类型，只有采集了变更明细的操作才有值 */
  resourceType?: string
  /** 被操作记录的主键 */
  resourceId?: string
  /** 被切的方法，格式「类全名#方法名」 */
  method?: string
  requestMethod?: string
  /** 请求地址，不含查询串 */
  operUrl?: string
  operIp?: string
  os?: string
  browser?: string
  /** 请求参数 JSON，敏感键已被后端替换为 *** */
  operParam?: string
  /** 返回结果 JSON，超长已截断 */
  jsonResult?: string
  /** 0 失败 / 1 成功 */
  result: number
  resultText?: string
  errorMessage?: string
  /** 操作耗时（毫秒） */
  costTime?: number
  /** 字段级变更明细；空数组表示本次没有可比的字段变化 */
  changedFields: AuditFieldChange[]
  createTime: string
}

/** 字段级变更明细（对应后端 AuditFieldChangeVo） */
export interface AuditFieldChange {
  /** 实体属性名，中文名由 constants/auditLog.ts 的 fieldLabel 映射 */
  field: string
  /** 变更前的展示值，取不到时为占位符 - */
  oldValue: string
  /** 变更后的展示值，删除操作时为 (已删除) */
  newValue: string
}

/** 操作日志查询条件（对应后端 AuditLogDto） */
export interface AuditLogQuery {
  pageNum: number
  pageSize: number
  /** 操作者名称，模糊匹配 */
  operName?: string
  /** 操作模块，模糊匹配 */
  title?: string
  businessType?: string
  requestMethod?: string
  result?: number
  createTimeBegin?: string
  createTimeEnd?: string
}
