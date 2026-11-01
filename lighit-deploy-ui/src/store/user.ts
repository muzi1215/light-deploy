/**
 * User Store - 用户认证与权限
 *
 * 职责：
 * 1. 登录/登出状态管理
 * 2. 登录标志位持久化（Cookie，仅用于路由守卫判定「是否已登录」）
 * 3. 当前用户信息（id/name）存储 —— 随请求通过 X-Operator-Id/Name 头带给后端操作日志切面
 * 4. 动态菜单（routers）存储；本项目返回空数组，走前端静态菜单模式
 *
 * 鉴权模型：后端使用 HttpSession（Redis 托管），浏览器自动携带 SESSION Cookie。
 * 前端不持有 Token 作为接口凭据；此处 token 仅作为「是否已登录」的哨兵标志。
 */
import { defineStore } from 'pinia'
import { login, logout, getInfo } from '@/api/user.ts'
import { getToken, setToken, removeToken } from '@/utils/auth.ts'
import { resetRouter } from '@/router'
import type { RouteRecordItem } from '@/types/api'

/** 登录哨兵：仅用于路由守卫判定已登录，无接口鉴权含义。 */
const LOGGED_IN_SENTINEL = 'session-auth'

export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken() || '',
    id: '',
    name: '',
    avatar: '',
    buttons: [] as string[],
    menus: [] as RouteRecordItem[]
  }),

  actions: {
    /** 用户登录：调用后端 /auth/login，成功后写入登录标志位（会话由后端 SESSION Cookie 维持） */
    async login(userInfo: { username: string; password: string }) {
      const { username, password } = userInfo
      const response = await login({ username: username.trim(), password })
      const { data } = response
      this.token = LOGGED_IN_SENTINEL
      setToken(LOGGED_IN_SENTINEL)
      // 登录成功即先记录基础信息，供首屏请求携带操作者头；完整信息由守卫后续 getInfo 补全。
      this.id = data.id
      this.name = data.nickname || data.username
    },

    /** 获取用户信息及菜单权限：调用后端 /auth/me */
    async getInfo() {
      const response = await getInfo()
      const { data } = response

      if (!data) {
        throw new Error('Verification failed, please Login again.')
      }

      const { id, username, nickname, buttons, routers } = data
      this.id = id
      this.name = nickname || username
      this.avatar = ''
      this.buttons = buttons || []
      // routers 为后端返回的动态菜单 JSON；本项目返回空数组，前端走 constantRoutes 静态菜单模式。
      this.menus = routers || []
    },

    /** 退出登录 */
    async logout() {
      await logout()
      removeToken()
      resetRouter()
      this.$reset()
    },

    /** 重置登录态（Token 失效/会话过期时调用） */
    async resetToken() {
      removeToken()
      this.$reset()
    }
  }
})
