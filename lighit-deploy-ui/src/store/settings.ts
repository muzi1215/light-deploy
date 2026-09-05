/**
 * Settings Store - 应用设置
 * Vue2 → Vue3 变化：
 * - Vuex mutations → Pinia actions 直接修改 state
 * - 初始值从 settings.ts 导入
 */
import { defineStore } from 'pinia'
import defaultSettings from '@/settings.ts'

export const useSettingsStore = defineStore('settings', {
  state: () => ({
    showSettings: false,
    fixedHeader: defaultSettings.fixedHeader,
    sidebarLogo: defaultSettings.sidebarLogo,
    tagsView: defaultSettings.tagsView
  }),

  actions: {
    /** 修改设置项 */
    changeSetting({ key, value }: { key: string; value: any }) {
      if (Object.prototype.hasOwnProperty.call(this.$state, key)) {
        ;(this as any)[key] = value
      }
    }
  }
})