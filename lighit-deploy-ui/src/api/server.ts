/**
 * 服务器模块 API。
 *
 * 对应后端 com.lx.deploy.server.controller.ServerController，
 * 接口前缀 /server，统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type { ApiResponse, PageResult, PageParams, ListParams, ServerVo, ServerDto } from './deployTypes.ts'

/** 条件分页查询服务器 */
export function pageServer(params: PageParams): Promise<ApiResponse<PageResult<ServerVo>>> {
  return request({ url: '/server/page', method: 'get', params })
}

/** 条件列表查询服务器（下拉选择场景，不分页） */
export function listServers(params?: ListParams): Promise<ApiResponse<ServerVo[]>> {
  return request({ url: '/server/list', method: 'get', params })
}

/** 根据主键查询服务器详情 */
export function getServer(id: string): Promise<ApiResponse<ServerVo>> {
  return request({ url: `/server/${id}`, method: 'get' })
}

/** 新增服务器，credential 必填，后端 AES-GCM 加密后落库 */
export function createServer(data: ServerDto): Promise<ApiResponse<ServerVo>> {
  return request({ url: '/server', method: 'post', data })
}

/** 修改服务器，credential 选填（不传保留旧凭据） */
export function updateServer(id: string, data: ServerDto): Promise<ApiResponse<ServerVo>> {
  return request({ url: `/server/${id}`, method: 'put', data })
}

/** 逻辑删除服务器 */
export function deleteServer(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/server/${id}`, method: 'delete' })
}
