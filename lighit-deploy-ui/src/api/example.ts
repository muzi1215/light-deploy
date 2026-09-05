/**
 * 示例 CRUD API
 *
 * 此文件展示标准的 API 封装模式。所有 API 文件应遵循此模式：
 * - 使用 request 实例（已配置 baseURL + token 拦截）
 * - 每个方法返回 Promise
 * - 分页接口使用 page/limit 参数
 *
 * 后端需返回统一格式：
 * { code: 200, message: "success", data: { ... } }
 */
import request from '@/utils/request.ts'

interface ExampleItem {
  id: number
  name: string
  status: number
  createTime?: string
}

export default {
  /** 分页列表 */
  getPageList(page: number, limit: number, params?: Record<string, any>) {
    return request({
      url: `/example/list/${page}/${limit}`,
      method: 'get',
      params
    })
  },

  /** 根据 ID 查询 */
  getById(id: number) {
    return request({
      url: `/example/${id}`,
      method: 'get'
    })
  },

  /** 新增 */
  save(data: Partial<ExampleItem>) {
    return request({
      url: '/example/save',
      method: 'post',
      data
    })
  },

  /** 更新 */
  updateById(data: Partial<ExampleItem>) {
    return request({
      url: '/example/update',
      method: 'put',
      data
    })
  },

  /** 删除 */
  removeById(id: number) {
    return request({
      url: `/example/remove/${id}`,
      method: 'delete'
    })
  },

  /** 批量删除 */
  removeByIds(ids: number[]) {
    return request({
      url: '/example/remove/batch',
      method: 'delete',
      data: ids
    })
  }
}
