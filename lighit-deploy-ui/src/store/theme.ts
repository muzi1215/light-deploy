/**
 * Theme Store - 主题切换管理
 * 支持深色（dark）和浅色（light）两种主题
 */
import { defineStore } from 'pinia'

export type ThemeName = 'dark' | 'light'

const THEME_KEY = 'admin-template-theme'

function getInitialTheme(): ThemeName {
  const stored = localStorage.getItem(THEME_KEY)
  if (stored === 'dark' || stored === 'light') return stored
  return 'light'
}

function applyTheme(theme: ThemeName) {
  document.documentElement.setAttribute('data-theme', theme)
}

export const useThemeStore = defineStore('theme', {
  state: () => ({
    current: getInitialTheme()
  }),

  actions: {
    /** 初始化主题（应用启动时调用） */
    initTheme() {
      applyTheme(this.current)
    },

    /** 切换主题 */
    toggleTheme() {
      this.current = this.current === 'dark' ? 'light' : 'dark'
      localStorage.setItem(THEME_KEY, this.current)
      applyTheme(this.current)
    },

    /** 设置指定主题 */
    setTheme(theme: ThemeName) {
      this.current = theme
      localStorage.setItem(THEME_KEY, theme)
      applyTheme(theme)
    }
  }
})