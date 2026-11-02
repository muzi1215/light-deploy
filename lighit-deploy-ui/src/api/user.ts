/**
 * 认证 API —— 对接真实后端过渡期鉴权（基于 Spring Session Cookie，无 Token）。
 *
 * 后端接口（经 request.ts 的 /api 前缀 + Vite 代理重写到 /deploy）：
 *   POST /auth/login  - 账号密码登录，成功后由后端写入 SESSION Cookie；返回 { code:200, data:{ id, username, nickname, email, externalUserId } }
 *   GET  /auth/me     - 获取当前登录用户；未登录返回 code:4002
 *   POST /auth/logout - 失效会话
 *
 * 说明：
 * - 本项目使用 HttpSession（Redis 托管）鉴权，浏览器自动携带 SESSION Cookie，无需前端管理 Token。
 *   但路由守卫仍依赖一个登录标志位，故登录成功后写入一个哨兵 token 到 Cookie，仅用于「是否已登录」判定，
 *   不作为接口鉴权凭据（接口鉴权靠后端会话）。
 * - 登录用户昵称优先用 nickname，缺省回落 username。
 */
import request from '@/utils/request'
import type { ApiResponse } from '@/types/api'

/** 登录用户快照（与后端 LoginUser 一致）。 */
export interface LoginUser {
  id: string
  username: string
  nickname: string | null
  email?: string | null
  externalUserId?: string | null
}

/** 登录。 */
export async function login(data: { username: string; password: string }): Promise<ApiResponse<LoginUser>> {
  return request({
    url: '/auth/login',
    method: 'post',
    data
  })
}

/** 获取当前登录用户（用于路由守卫鉴权与菜单）。 */
export async function getInfo(): Promise<
  ApiResponse<LoginUser & { buttons: string[]; routers: any[] }>
> {
  return request({
    url: '/auth/me',
    method: 'get'
  })
}

/** 退出登录。 */
export async function logout(): Promise<ApiResponse> {
  return request({
    url: '/auth/logout',
    method: 'post'
  })
}
