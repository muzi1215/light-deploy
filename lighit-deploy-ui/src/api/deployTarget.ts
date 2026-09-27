/**
 * 部署目标模块 API。
 *
 * 对应后端 com.lx.deploy.project.controller.ProjectEnvironmentTargetController，
 * 接口前缀 /projectEnvironmentTarget，统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  DeployTargetVo,
  DeployTargetDto,
  EnvironmentOptionVo
} from './deployTypes.ts'

/** 条件分页查询部署目标（支持 projectId / environmentId / serverId 过滤） */
export function pageDeployTarget(params: PageParams): Promise<ApiResponse<PageResult<DeployTargetVo>>> {
  return request({ url: '/projectEnvironmentTarget/page', method: 'get', params })
}

/** 条件列表查询部署目标 */
export function listDeployTargets(params?: ListParams): Promise<ApiResponse<DeployTargetVo[]>> {
  return request({ url: '/projectEnvironmentTarget/list', method: 'get', params })
}

/**
 * 查询指定项目已绑定启用目标的环境选项（新建部署的选环境下拉）。
 *
 * 只返回能真正发布的环境（至少一台启用绑定），并带回目标台数；
 * 项目没有启用绑定时返回空数组，而不是全量环境。
 */
export function listProjectEnvironments(
  projectId: string
): Promise<ApiResponse<EnvironmentOptionVo[]>> {
  return request({ url: '/projectEnvironmentTarget/env-options', method: 'get', params: { projectId } })
}

/** 根据主键查询部署目标详情 */
export function getDeployTarget(id: string): Promise<ApiResponse<DeployTargetVo>> {
  return request({ url: `/projectEnvironmentTarget/detail/${id}`, method: 'get' })
}

/** 新增部署目标（同一项目环境同一服务器唯一；主目标互斥由后端保证） */
export function createDeployTarget(data: DeployTargetDto): Promise<ApiResponse<DeployTargetVo>> {
  return request({ url: '/projectEnvironmentTarget/create', method: 'post', data })
}

/** 修改部署目标 */
export function updateDeployTarget(id: string, data: DeployTargetDto): Promise<ApiResponse<DeployTargetVo>> {
  return request({ url: `/projectEnvironmentTarget/update/${id}`, method: 'put', data })
}

/** 逻辑删除部署目标 */
export function deleteDeployTarget(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/projectEnvironmentTarget/delete/${id}`, method: 'delete' })
}
