/**
 * 构建模块 API。
 *
 * 对应后端 com.lx.deploy.build.controller.BuildController，接口前缀 /build，
 * 统一经 request.ts 的 axios 实例走 /api 代理。
 *
 * 业务约定：
 * 1. 构建是「触发即创建」的异步流程：POST /build 只会创建 build 记录并同步生成一条
 *    BUILD 异步任务，立即返回（含 taskId）；真正的克隆 / 构建 / 推送由后端任务执行器完成，
 *    页面用 /taskLog/pull 增量拉取实时日志；
 * 2. 构建记录只在「排队中」状态允许修改构建机，「终态（成功 / 失败）」才允许删除；
 * 3. 构建成功后端会自动生成一条「可发布」的发布版本（release_record），产物列表即为本次构建产出。
 */
import request from '@/utils/request.ts'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  BuildVo,
  BuildDto,
  BuildArtifactVo
} from './deployTypes.ts'

/** 条件分页查询构建记录 */
export function pageBuild(params: PageParams): Promise<ApiResponse<PageResult<BuildVo>>> {
  return request({ url: '/build/page', method: 'get', params })
}

/** 条件列表查询构建记录（下拉选择场景，不分页） */
export function listBuilds(params?: ListParams): Promise<ApiResponse<BuildVo[]>> {
  return request({ url: '/build/list', method: 'get', params })
}

/** 根据主键查询构建详情 */
export function getBuild(id: string): Promise<ApiResponse<BuildVo>> {
  return request({ url: `/build/detail/${id}`, method: 'get' })
}

/**
 * 触发一次构建（创建即执行）。
 *
 * 后端只落 build 记录并生成 BUILD 异步任务后立即返回，真正的构建过程异步进行。
 * 调用方拿到 taskId 后应轮询 pullTaskLog(taskId, lastSeq) 展示实时日志。
 *
 * @param data 构建触发参数，projectId 必填，buildServerId 选填
 * @returns 新建的构建记录（含 id 与 taskId）
 */
export function createBuild(data: BuildDto): Promise<ApiResponse<BuildVo>> {
  return request({ url: '/build/create', method: 'post', data })
}

/** 修改构建，仅「排队中」状态允许改构建机；其余字段由服务端维护，不可通过此接口修改 */
export function updateBuild(id: string, data: BuildDto): Promise<ApiResponse<BuildVo>> {
  return request({ url: `/build/update/${id}`, method: 'put', data })
}

/** 逻辑删除构建记录，仅终态（成功 / 失败）允许删除 */
export function deleteBuild(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/build/delete/${id}`, method: 'delete' })
}

/** 查询某次构建的产物清单（镜像 / JAR / 静态产物） */
export function getBuildArtifacts(id: string): Promise<ApiResponse<BuildArtifactVo[]>> {
  return request({ url: `/build/artifacts/${id}`, method: 'get' })
}
