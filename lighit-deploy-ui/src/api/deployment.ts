/**
 * 部署（发布）模块 API。
 *
 * 对应后端 com.lx.deploy.deployment.controller.DeploymentController，接口前缀 /deployment，
 * 统一经 request.ts 的 axios 实例走 /api 代理。
 *
 * 业务约定：
 * 1. 部署是「触发即创建」的异步流程：POST /deployment/create 只落 deployment 与目标快照，
 *    并同步生成一条 DEPLOY 异步任务后立即返回（含 taskId）；真正的分发 / 启动 / 健康检查
 *    由后端任务执行器完成，页面用 /taskLog/pull 增量拉取实时日志；
 * 2. 只有「排队中」的部署允许更换版本或策略，「成功」的部署才允许回滚，
 *    「排队中 / 发布中」允许取消，「终态」才允许删除；
 * 3. 目标明细用 /deployment/targets/{id} 查询，展示每台机器的发布前后版本与健康检查结果。
 */
import request from '@/utils/request.ts'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  DeploymentVo,
  DeploymentDto,
  DeploymentTargetVo,
  RollbackPreviewVo
} from './deployTypes.ts'

/** 条件分页查询部署记录 */
export function pageDeployment(params: PageParams): Promise<ApiResponse<PageResult<DeploymentVo>>> {
  return request({ url: '/deployment/page', method: 'get', params })
}

/** 条件列表查询部署记录（不分页） */
export function listDeployments(params?: ListParams): Promise<ApiResponse<DeploymentVo[]>> {
  return request({ url: '/deployment/list', method: 'get', params })
}

/** 根据主键查询部署详情（含目标汇总与关联任务） */
export function getDeployment(id: string): Promise<ApiResponse<DeploymentVo>> {
  return request({ url: `/deployment/detail/${id}`, method: 'get' })
}

/** 查询一次部署的全部目标明细 */
export function getDeploymentTargets(id: string): Promise<ApiResponse<DeploymentTargetVo[]>> {
  return request({ url: `/deployment/targets/${id}`, method: 'get' })
}

/**
 * 创建并触发一次部署。
 *
 * @param data 部署参数，projectId / environmentId / releaseId 必填，strategy 选填（覆盖环境默认）
 * @returns 新建的部署记录（含 id 与 taskId）
 */
export function createDeployment(data: DeploymentDto): Promise<ApiResponse<DeploymentVo>> {
  return request({ url: '/deployment/create', method: 'post', data })
}

/** 修改部署，仅「排队中」允许更换版本或策略 */
export function updateDeployment(id: string, data: DeploymentDto): Promise<ApiResponse<DeploymentVo>> {
  return request({ url: `/deployment/update/${id}`, method: 'put', data })
}

/** 回滚一次已成功的部署；后端复用同一批目标按 old_version 重新发布 */
export function rollbackDeployment(id: string): Promise<ApiResponse<DeploymentVo>> {
  return request({ url: `/deployment/rollback/${id}`, method: 'put' })
}

/**
 * 预览一次回滚会发生什么（只读，不改变任何状态）。
 *
 * 回滚按钮在提交前先调本接口，把「哪几台退回哪个版本、哪几台跳过及原因」摆给使用者确认。
 * 判定与后端 rollback 同源，因此预览里的清单就是提交后真正会执行的清单。
 */
export function previewRollback(id: string): Promise<ApiResponse<RollbackPreviewVo>> {
  return request({ url: `/deployment/rollback-preview/${id}`, method: 'get' })
}

/** 取消一次排队中或发布中的部署 */
export function cancelDeployment(id: string): Promise<ApiResponse<DeploymentVo>> {
  return request({ url: `/deployment/cancel/${id}`, method: 'put' })
}

/** 逻辑删除部署记录，仅终态允许删除 */
export function deleteDeployment(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/deployment/delete/${id}`, method: 'delete' })
}
