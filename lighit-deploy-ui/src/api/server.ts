/**
 * 服务器模块 API。
 *
 * 对应后端 com.lx.deploy.server.controller.ServerController，
 * 接口前缀 /server，统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  ServerVo,
  ServerDto,
  TaskVo
} from './deployTypes.ts'

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
  return request({ url: `/server/detail/${id}`, method: 'get' })
}

/** 新增服务器，credential 必填，后端 AES-GCM 加密后落库 */
export function createServer(data: ServerDto): Promise<ApiResponse<ServerVo>> {
  return request({ url: '/server/create', method: 'post', data })
}

/** 修改服务器，credential 选填（不传保留旧凭据） */
export function updateServer(id: string, data: ServerDto): Promise<ApiResponse<ServerVo>> {
  return request({ url: `/server/update/${id}`, method: 'put', data })
}

/**
 * 提交一次服务器连通性测试（对应后端 POST /server/test/{id}）。
 *
 * 后端只创建异步任务并立即返回，任务状态为 0（排队中）；真正的 SSH 连接与探活命令
 * 由任务执行器执行。调用方拿到 taskId 后应轮询 pullTaskLog(taskId, lastSeq) 展示实时日志。
 * 同一服务器已有未完成任务时后端返回 4005，由 request 层统一提示。
 *
 * @param id 目标服务器主键
 * @returns 新建的异步任务（含 id 与 taskNo）
 */
export function testServerConnectivity(id: string): Promise<ApiResponse<TaskVo>> {
  return request({ url: `/server/test/${id}`, method: 'post' })
}

/** 逻辑删除服务器 */
export function deleteServer(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/server/delete/${id}`, method: 'delete' })
}
