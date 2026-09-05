/**
 * 认证 API
 *
 * ============================================================
 * 当前为模拟登录模式，无需后端即可运行。
 * 对接真实后端时：
 *   1. 注释掉下面的 mock 函数
 *   2. 取消注释底部真实 API 调用的代码
 *   3. 确保后端返回统一格式：{ code: 200, message: "success", data: {...} }
 * ============================================================
 *
 * 后端需要实现的接口：
 * POST /login     - 登录，返回 { code: 200, data: { token: "xxx" } }
 * GET  /info      - 获取当前用户信息，返回 { code: 200, data: { id, name, avatar, buttons[], routers[] } }
 * GET  /logout    - 退出登录
 *
 * routers 字段为动态菜单 JSON 数组，格式参考 src/types/api.d.ts 中的 RouteRecordItem
 * 如果不需要动态菜单，让后端返回 routers: [] 即可，前端将仅渲染静态路由
 */
import type { ApiResponse } from '@/types/api'

// ==================== 模拟数据（无需后端） ====================

/** 模拟登录：任意用户名密码均可登录 */
export async function login(data: { username: string; password: string }): Promise<ApiResponse<{ token: string }>> {
  // 模拟网络延迟
  await new Promise(resolve => setTimeout(resolve, 300))
  const mockToken = 'mock_token_' + Date.now()
  return {
    code: 200,
    message: 'success',
    data: { token: mockToken }
  }
}

/** 模拟获取用户信息（过渡期假登录，返回空菜单走前端静态路由模式） */
export async function getInfo(): Promise<ApiResponse<{
  id: string
  name: string
  avatar: string
  buttons: string[]
  routers: any[]
}>> {
  await new Promise(resolve => setTimeout(resolve, 200))
  return {
    code: 200,
    message: 'success',
    data: {
      id: '1',
      name: 'admin',
      avatar: '',
      buttons: [],
      // routers 返回空数组：前端使用 constantRoutes 静态菜单模式。
      // 后续接入统一权限中心后，替换为后端返回的真实菜单 JSON。
      routers: []
    }
  }
}

/** 模拟退出登录 */
export async function logout(): Promise<ApiResponse> {
  await new Promise(resolve => setTimeout(resolve, 100))
  return { code: 200, message: 'success', data: null }
}

// ==================== 真实 API（对接后端时取消注释） ====================
//
// import request from '@/utils/request'
//
// export async function login(data: { username: string; password: string }): Promise<ApiResponse<{ token: string }>> {
//   return request({
//     url: '/login',
//     method: 'post',
//     data
//   })
// }
//
// export async function getInfo(): Promise<ApiResponse<{
//   id: string
//   name: string
//   avatar: string
//   buttons: string[]
//   routers: any[]
// }>> {
//   return request({
//     url: '/info',
//     method: 'get'
//   })
// }
//
// export async function logout(): Promise<ApiResponse> {
//   return request({
//     url: '/logout',
//     method: 'get'
//   })
// }
