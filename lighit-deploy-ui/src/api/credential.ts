/**
 * 凭据模块 API。
 *
 * 对应后端 com.lx.deploy.credential.controller.CredentialController，接口前缀 /credential，
 * 统一经 request.ts 的 axios 实例走 /api 代理。
 *
 * 关键约定（与后端 CredentialService 一致，AGENTS.md §7）：
 * - 明文凭据只写不读：新增必填（credential 字段），修改留空表示保留旧密文；
 * - 任何查询接口都不返回密文，CredentialVo 里没有对应字段；
 * - 删除前若凭据仍被项目或镜像仓库引用，后端返回 4005，由 request 层统一提示。
 */
import request from '@/utils/request.ts'
import type {
  ApiResponse,
  PageResult,
  PageParams,
  ListParams,
  CredentialVo,
  CredentialDto
} from './deployTypes.ts'

/** 条件分页查询凭据 */
export function pageCredential(params: PageParams): Promise<ApiResponse<PageResult<CredentialVo>>> {
  return request({ url: '/credential/page', method: 'get', params })
}

/**
 * 条件列表查询凭据（下拉选择场景，不分页）。
 *
 * @param params 查询条件；传 type 可按凭据类型过滤（如 GIT_TOKEN 用于项目 Git 凭据下拉）
 * @returns 凭据列表，不含密文
 */
export function listCredentials(params?: ListParams): Promise<ApiResponse<CredentialVo[]>> {
  return request({ url: '/credential/list', method: 'get', params })
}

/** 根据主键查询凭据详情（不含密文） */
export function getCredential(id: string): Promise<ApiResponse<CredentialVo>> {
  return request({ url: `/credential/detail/${id}`, method: 'get' })
}

/** 新增凭据，credential 必填，后端 AES-GCM 加密后落库 */
export function createCredential(data: CredentialDto): Promise<ApiResponse<CredentialVo>> {
  return request({ url: '/credential/create', method: 'post', data })
}

/** 修改凭据，credential 选填（留空保留原凭据） */
export function updateCredential(id: string, data: CredentialDto): Promise<ApiResponse<CredentialVo>> {
  return request({ url: `/credential/update/${id}`, method: 'put', data })
}

/** 逻辑删除凭据；仍被项目或镜像仓库引用时后端拒绝（4005） */
export function deleteCredential(id: string): Promise<ApiResponse<void>> {
  return request({ url: `/credential/delete/${id}`, method: 'delete' })
}
