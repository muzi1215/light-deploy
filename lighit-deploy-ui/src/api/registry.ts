/**
 * 镜像仓库模块 API。
 *
 * 对应后端 com.lx.deploy.registry.controller.RegistryController，接口前缀 /registry，
 * 统一经 request.ts 的 axios 实例走 /api 代理。
 *
 * 关键约定（AGENTS.md §23.4）：镜像仓库只服务于 DOCKER 运行时，脚本部署与仅发布产物
 * 不产出镜像、不绑定仓库；项目通过 project.registryId 绑定仓库，被绑定的仓库不允许删除。
 */
import request from '@/utils/request.ts'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  RegistryVo,
  RegistryDto
} from './deployTypes.ts'

/** 条件分页查询镜像仓库 */
export function pageRegistry(params: PageParams): Promise<ApiResponse<PageResult<RegistryVo>>> {
  return request({ url: '/registry/page', method: 'get', params })
}

/**
 * 条件列表查询镜像仓库（项目表单的仓库下拉使用）。
 *
 * @param params 查询条件；传 status=1 只返回启用中的仓库
 * @returns 仓库列表，不含凭据内容
 */
export function listRegistries(params?: ListParams): Promise<ApiResponse<RegistryVo[]>> {
  return request({ url: '/registry/list', method: 'get', params })
}

/** 根据主键查询镜像仓库详情 */
export function getRegistry(id: string): Promise<ApiResponse<RegistryVo>> {
  return request({ url: `/registry/${id}`, method: 'get' })
}

/** 新增镜像仓库；credentialId 填写时必须是类型为 REGISTRY 的凭据 */
export function createRegistry(data: RegistryDto): Promise<ApiResponse<RegistryVo>> {
  return request({ url: '/registry', method: 'post', data })
}

/** 修改镜像仓库 */
export function updateRegistry(id: string, data: RegistryDto): Promise<ApiResponse<RegistryVo>> {
  return request({ url: `/registry/${id}`, method: 'put', data })
}

/** 逻辑删除镜像仓库；仍被项目绑定时后端拒绝（4005） */
export function deleteRegistry(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/registry/${id}`, method: 'delete' })
}
