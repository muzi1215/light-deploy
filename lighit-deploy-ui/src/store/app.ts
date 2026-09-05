/**
 * App Store - 应用全局状态
 * Vue2 → Vue3 变化：
 * - Vuex module with state/mutations/actions → Pinia defineStore
 * - state 是函数返回对象
 * - mutations 改为直接修改 state 的函数
 * - actions 中 this 可直接访问 state
 * - namespaced 自动处理，不需要手动配置
 */
import { defineStore } from 'pinia'
import Cookies from 'js-cookie'

export const useAppStore = defineStore('app', {
  state: () => ({
    sidebar: {
      opened: Cookies.get('sidebarStatus') ? !!+Cookies.get('sidebarStatus')! : true,
      withoutAnimation: false
    },
    device: 'desktop' as 'desktop' | 'mobile'
  }),

  actions: {
    /** 切换侧边栏 */
    toggleSideBar() {
      this.sidebar.opened = !this.sidebar.opened
      this.sidebar.withoutAnimation = false
      if (this.sidebar.opened) {
        Cookies.set('sidebarStatus', '1')
      } else {
        Cookies.set('sidebarStatus', '0')
      }
    },

    /** 关闭侧边栏 */
    closeSideBar(withoutAnimation: boolean) {
      Cookies.set('sidebarStatus', '0')
      this.sidebar.opened = false
      this.sidebar.withoutAnimation = withoutAnimation
    },

    /** 切换设备类型 */
    toggleDevice(device: 'desktop' | 'mobile') {
      this.device = device
    }
  }
})