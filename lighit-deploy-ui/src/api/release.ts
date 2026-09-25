/**
 * 发布版本 API（只读）。
 *
 * 对应后端 com.lx.deploy.release.controller.ReleaseController，接口前缀 /release。
 * 发布版本由构建成功自动创建，前端只用于「选择要发哪个版本」：
 * 发布页按项目 + status=1（可发布）拉取下拉数据。
 */
import request from '@/utils/request.ts'
import type { ApiResponse, PageResult, PageParams, ListParams, ReleaseRecordVo } from './deployTypes.ts'

/** 条件分页查询发布版本 */
export function pageRelease(params: PageParams): Promise<ApiResponse<PageResult<ReleaseRecordVo>>> {
  return request({ url: '/release/page', method: 'get', params })
}

/**
 * 查询发布版本列表（发布页版本下拉使用）。
 *
 * @param params 查询条件，通常传 { projectId, status: 1 }
 */
export function listReleases(params?: ListParams): Promise<ApiResponse<ReleaseRecordVo[]>> {
  return request({ url: '/release/list', method: 'get', params })
}

/** 根据主键查询单个发布版本 */
export function getRelease(id: string): Promise<ApiResponse<ReleaseRecordVo>> {
  return request({ url: `/release/detail/${id}`, method: 'get' })
}
