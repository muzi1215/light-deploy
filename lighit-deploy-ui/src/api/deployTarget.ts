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
  DeployTargetDto
} from './deployTypes.ts'

/** 条件分页查询部署目标（支持 projectId / environmentId / serverId 过滤） */
export function pageDeployTarget(params: PageParams): Promise<ApiResponse<PageResult<DeployTargetVo>>> {
  return request({ url: '/projectEnvironmentTarget/page', method: 'get', params })
}

/** 条件列表查询部署目标 */
export function listDeployTargets(params?: ListParams): Promise<ApiResponse<DeployTargetVo[]>> {
  return request({ url: '/projectEnvironmentTarget/list', method: 'get', params })
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
