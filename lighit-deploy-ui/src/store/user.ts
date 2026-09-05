/**
 * User Store - 用户认证与权限
 *
 * 职责：
 * 1. 登录/登出状态管理
 * 2. Token 持久化（Cookie）
 * 3. 动态菜单（routers）存储
 * 4. 按钮权限（buttons）存储
 */
import { defineStore } from 'pinia'
import { login, logout, getInfo } from '@/api/user.ts'
import { getToken, setToken, removeToken } from '@/utils/auth.ts'
import { resetRouter } from '@/router'
import type { RouteRecordItem } from '@/types/api'

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
    /** 用户登录 */
    async login(userInfo: { username: string; password: string }) {
      const { username, password } = userInfo
      const response = await login({ username: username.trim(), password })
      const { data } = response
      this.token = data.token
      setToken(data.token)
    },

    /** 获取用户信息及菜单权限 */
    async getInfo() {
      const response = await getInfo()
      const { data } = response

      if (!data) {
        throw new Error('Verification failed, please Login again.')
      }

      const { id, name, avatar, buttons, routers } = data
      this.id = id
      this.name = name
      this.avatar = avatar
      this.buttons = buttons || []
      // routers 为后端返回的动态菜单 JSON
      // 若为空数组，则使用前端的 constantRoutes 静态菜单模式
      this.menus = routers || []
    },

    /** 退出登录 */
    async logout() {
      await logout()
      removeToken()
      resetRouter()
      this.$reset()
    },

    /** 重置 Token（Token 失效时调用） */
    async resetToken() {
      removeToken()
      this.$reset()
    }
  }
})
