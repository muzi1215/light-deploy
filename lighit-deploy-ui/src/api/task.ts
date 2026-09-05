/**
 * 任务模块 API。
 *
 * 对应后端 com.lx.deploy.task.controller 包下的 TaskController（前缀 /task）
 * 与 TaskLogController（前缀 /taskLog），统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  TaskVo,
  TaskDto,
  TaskLogVo,
  TaskLogDto
} from './deployTypes.ts'

/** 条件分页查询异步任务 */
export function pageTask(params: PageParams): Promise<ApiResponse<PageResult<TaskVo>>> {
  return request({ url: '/task/page', method: 'get', params })
}

/** 条件列表查询任务（下拉选择场景，不分页） */
export function listTasks(params?: ListParams): Promise<ApiResponse<TaskVo[]>> {
  return request({ url: '/task/list', method: 'get', params })
}

/** 根据主键查询任务详情 */
export function getTask(id: string): Promise<ApiResponse<TaskVo>> {
  return request({ url: `/task/${id}`, method: 'get' })
}

/** 新增任务，任务编号不传时后端自动生成，状态强制初始化为 QUEUED */
export function createTask(data: TaskDto): Promise<ApiResponse<TaskVo>> {
  return request({ url: '/task', method: 'post', data })
}

/** 修改任务，状态/起止时间等流转字段后端不允许通过该接口修改 */
export function updateTask(id: string, data: TaskDto): Promise<ApiResponse<TaskVo>> {
  return request({ url: `/task/${id}`, method: 'put', data })
}

/** 逻辑删除任务，仅终态任务（成功/失败/已取消）允许删除 */
export function deleteTask(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/task/${id}`, method: 'delete' })
}

/** 人工取消任务，排队中/执行中允许取消，终态返回 DATA_CONFLICT */
export function cancelTask(id: string): Promise<ApiResponse<TaskVo>> {
  return request({ url: `/task/${id}/cancel`, method: 'put' })
}

/** 条件分页查询任务执行日志（通常按 taskId 过滤） */
export function pageTaskLog(params: PageParams): Promise<ApiResponse<PageResult<TaskLogVo>>> {
  return request({ url: '/taskLog/page', method: 'get', params })
}

/** 条件列表查询任务执行日志，结果按 sequenceNo 升序返回 */
export function listTaskLogs(params?: ListParams): Promise<ApiResponse<TaskLogVo[]>> {
  return request({ url: '/taskLog/list', method: 'get', params })
}
