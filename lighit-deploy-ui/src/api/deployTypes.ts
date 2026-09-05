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
  buildMode: string // PLATFORM_TEMPLATE / PROJECT_DOCKERFILE / LAYERED_DOCKER / ARTIFACT_ONLY
  buildConfigJson?: string
  runtimeConfigJson?: string
  healthConfigJson?: string
  retainReleaseCount: number
  status: string // ENABLED / DISABLED
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
  buildConfigJson?: string
  runtimeConfigJson?: string
  healthConfigJson?: string
  retainReleaseCount?: number
  status?: string
  description?: string
}

// ==================== 环境模块类型 ====================

/** 环境 VO */
export interface EnvironmentVo {
  id: string
  createTime: string
  updateTime: string
  code: string // dev / test / prod
  name: string
  deployStrategy: string // SINGLE_TARGET / ALL_AT_ONCE / ROLLING
  batchSize: number
  defaultServerId?: string
  aiopsEnabled: number // 0 / 1
  description?: string
}

/** 环境查询/新增/修改 DTO */
export interface EnvironmentDto {
  code: string
  name: string
  deployStrategy?: string
  batchSize?: number
  defaultServerId?: string
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
  status: string // ENABLED / DISABLED
}

/** 部署目标查询/新增/修改 DTO */
export interface DeployTargetDto {
  projectId: string
  environmentId: string
  serverId: string
  isPrimary?: number
  deployOrder?: number
  runtimeConfigJson?: string
  status?: string
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
  status: string // QUEUED / RUNNING / SUCCESS / FAILED / CANCELED
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
  status?: string
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
  logLevel: string // DEBUG / INFO / WARN / ERROR
  content: string
}

/** 任务执行日志查询/新增/修改 DTO */
export interface TaskLogDto {
  taskId?: string
  sequenceNo?: number
  stage?: string
  logLevel?: string
  content?: string
}
