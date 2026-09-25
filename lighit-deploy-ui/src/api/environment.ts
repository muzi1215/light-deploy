/**
 * 环境模块 API。
 *
 * 对应后端 com.lx.deploy.project.controller.EnvironmentController，
 * 接口前缀 /environment，统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type { ApiResponse, PageResult, PageParams, ListParams, EnvironmentVo, EnvironmentDto } from './deployTypes.ts'

/** 条件分页查询环境 */
export function pageEnvironment(params: PageParams): Promise<ApiResponse<PageResult<EnvironmentVo>>> {
  return request({ url: '/environment/page', method: 'get', params })
}

/** 条件列表查询环境（下拉选择场景，不分页） */
export function listEnvironments(params?: ListParams): Promise<ApiResponse<EnvironmentVo[]>> {
  return request({ url: '/environment/list', method: 'get', params })
}

/** 根据主键查询环境详情 */
export function getEnvironment(id: string): Promise<ApiResponse<EnvironmentVo>> {
  return request({ url: `/environment/detail/${id}`, method: 'get' })
}

/** 新增环境 */
export function createEnvironment(data: EnvironmentDto): Promise<ApiResponse<EnvironmentVo>> {
  return request({ url: '/environment/create', method: 'post', data })
}

/** 修改环境 */
export function updateEnvironment(id: string, data: EnvironmentDto): Promise<ApiResponse<EnvironmentVo>> {
  return request({ url: `/environment/update/${id}`, method: 'put', data })
}

/** 逻辑删除环境（仍被部署目标引用时后端返回 DATA_CONFLICT） */
export function deleteEnvironment(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/environment/delete/${id}`, method: 'delete' })
}
