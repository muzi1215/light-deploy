/**
 * 项目模块 API。
 *
 * 对应后端 com.lx.deploy.project.controller.ProjectController，
 * 接口前缀 /project，统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type { ApiResponse, PageResult, PageParams, ListParams, ProjectVo, ProjectDto } from './deployTypes.ts'

/** 条件分页查询项目 */
export function pageProject(params: PageParams): Promise<ApiResponse<PageResult<ProjectVo>>> {
  return request({ url: '/project/page', method: 'get', params })
}

/** 条件列表查询项目（下拉选择场景，不分页） */
export function listProjects(params?: ListParams): Promise<ApiResponse<ProjectVo[]>> {
  return request({ url: '/project/list', method: 'get', params })
}

/** 根据主键查询项目详情 */
export function getProject(id: string): Promise<ApiResponse<ProjectVo>> {
  return request({ url: `/project/${id}`, method: 'get' })
}

/** 新增项目 */
export function createProject(data: ProjectDto): Promise<ApiResponse<ProjectVo>> {
  return request({ url: '/project', method: 'post', data })
}

/** 修改项目 */
export function updateProject(id: string, data: ProjectDto): Promise<ApiResponse<ProjectVo>> {
  return request({ url: `/project/${id}`, method: 'put', data })
}

/** 逻辑删除项目（仍被部署目标引用时后端返回 DATA_CONFLICT） */
export function deleteProject(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/project/${id}`, method: 'delete' })
}
