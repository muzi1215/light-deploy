/**
 * 任务模块 API。
 *
 * 对应后端 com.lx.deploy.task.controller 包下的 TaskController（前缀 /task）
 * 与 TaskLogController（前缀 /taskLog），统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type { AxiosResponse } from 'axios'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  TaskVo,
  TaskDto,
  TaskLogVo,
  TaskLogDto,
  TaskLogPullVo
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
  return request({ url: `/task/detail/${id}`, method: 'get' })
}

/** 新增任务，任务编号不传时后端自动生成，状态强制初始化为 QUEUED */
export function createTask(data: TaskDto): Promise<ApiResponse<TaskVo>> {
  return request({ url: '/task/create', method: 'post', data })
}

/** 修改任务，状态/起止时间等流转字段后端不允许通过该接口修改 */
export function updateTask(id: string, data: TaskDto): Promise<ApiResponse<TaskVo>> {
  return request({ url: `/task/update/${id}`, method: 'put', data })
}

/** 逻辑删除任务，仅终态任务（成功/失败/已取消）允许删除 */
export function deleteTask(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/task/delete/${id}`, method: 'delete' })
}

/** 人工取消任务，排队中/执行中允许取消，终态返回 DATA_CONFLICT */
export function cancelTask(id: string): Promise<ApiResponse<TaskVo>> {
  return request({ url: `/task/cancel/${id}`, method: 'put' })
}

/** 条件分页查询任务执行日志（通常按 taskId 过滤） */
export function pageTaskLog(params: PageParams): Promise<ApiResponse<PageResult<TaskLogVo>>> {
  return request({ url: '/taskLog/page', method: 'get', params })
}

/** 条件列表查询任务执行日志，结果按 sequenceNo 升序返回 */
export function listTaskLogs(params?: ListParams): Promise<ApiResponse<TaskLogVo[]>> {
  return request({ url: '/taskLog/list', method: 'get', params })
}

/**
 * 增量拉取任务执行日志（实时查看执行过程使用，对应后端 GET /taskLog/pull）。
 *
 * 增量锚点用日志顺序号而不是日志主键：主键是 32 位 UUID，不具备单调性。
 * 响应中的 finished 为 true 时说明任务已结束，调用方应停止轮询；注意还要把本批
 * 剩余日志拉完（返回条数等于 limit 时说明可能还有剩余）再停止，避免漏日志。
 *
 * @param taskId 任务主键
 * @param lastSeq 已获取到的最大日志顺序号；首次拉取传 0
 * @param limit 单次最多返回条数，后端上限 500，默认 200
 * @returns 增量日志、任务状态、进度与下一次拉取的锚点 lastSeq
 */
export function pullTaskLog(
  taskId: string,
  lastSeq: number,
  limit = 200
): Promise<ApiResponse<TaskLogPullVo>> {
  return request({ url: '/taskLog/pull', method: 'get', params: { taskId, lastSeq, limit } })
}

/**
 * 导出任务执行日志为文本附件（对应后端 GET /taskLog/export/{taskId}）。
 *
 * 必须声明 responseType: 'blob'，否则 axios 会试图按 JSON 解析二进制正文；
 * request.ts 对 blob 响应不做业务码校验、直接返回原始响应，因此**调用方必须自己判断
 * 响应类型**：任务不存在时后端返回的是标准 JSON 错误体，如果把它当附件存下来，
 * 用户会得到一个内容是报错信息的「日志文件」。
 *
 * 文件名以响应头 Content-Disposition 为准（后端按任务编号生成），
 * 不要在前端另拼一套命名规则。
 *
 * @param taskId 任务主键
 * @returns axios 原始响应：正文为 Blob，响应头携带 Content-Disposition
 */
export function exportTaskLog(taskId: string): Promise<AxiosResponse<Blob>> {
  return request({ url: `/taskLog/export/${taskId}`, method: 'get', responseType: 'blob' })
}
