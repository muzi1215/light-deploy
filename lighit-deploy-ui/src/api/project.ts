/**
 * 项目模块 API。
 *
 * 对应后端 com.lx.deploy.project.controller.ProjectController，
 * 接口前缀 /project，统一经 request.ts 的 axios 实例走 /api 代理。
 */
import request from '@/utils/request.ts'
import type { ApiResponse, PageResult, PageParams, ListParams, ProjectVo, ProjectDto, TaskVo } from './deployTypes.ts'

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
  return request({ url: `/project/detail/${id}`, method: 'get' })
}

/** 新增项目 */
export function createProject(data: ProjectDto): Promise<ApiResponse<ProjectVo>> {
  return request({ url: '/project/create', method: 'post', data })
}

/** 修改项目 */
export function updateProject(id: string, data: ProjectDto): Promise<ApiResponse<ProjectVo>> {
  return request({ url: `/project/update/${id}`, method: 'put', data })
}

/**
 * 提交一次项目连通性测试任务。
 *
 * 测试在项目的构建机上执行，校验构建机工具链、Git 仓库可否拉取、镜像仓库可否登录，
 * 把这些配置问题提前暴露，而不是拖到真正构建时才以「构建失败」的形式出现。
 * 接口只创建异步任务并立即返回，调用方用返回的 taskId 轮询 /taskLog/pull 查看执行日志。
 *
 * @param id 目标项目主键
 * @returns 新建的异步任务（状态为 0 排队中）
 */
export function testProjectConnectivity(id: string): Promise<ApiResponse<TaskVo>> {
  return request({ url: `/project/test/${id}`, method: 'post' })
}

/** 逻辑删除项目（仍被部署目标引用时后端返回 DATA_CONFLICT） */
export function deleteProject(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/project/delete/${id}`, method: 'delete' })
}
