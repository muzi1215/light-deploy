/**
 * TagsView Store - 标签页视图管理
 * Vue2 → Vue3 变化：
 * - Vuex 的 mutations 和 actions 合并到 Pinia actions 中
 * - 不再需要 new Promise 包装（Pinia 动作天然支持异步）
 * - 直接修改 state 而非通过 commit
 */
import { defineStore } from 'pinia'
import type { RouteLocationNormalized } from 'vue-router'

interface TagView extends Partial<RouteLocationNormalized> {
  title?: string
  meta?: any
}

export const useTagsViewStore = defineStore('tagsView', {
  state: () => ({
    visitedViews: [] as TagView[],
    cachedViews: [] as string[]
  }),

  actions: {
    /** 添加标签页 */
    addView(view: TagView) {
      this.addVisitedView(view)
      this.addCachedView(view)
    },

    addVisitedView(view: TagView) {
      if (this.visitedViews.some(v => v.path === view.path)) return
      this.visitedViews.push({
        ...view,
        title: (view.meta?.title as string) || 'no-name'
      })
    },

    addCachedView(view: TagView) {
      if (this.cachedViews.includes(view.name as string)) return
      if (!view.meta?.noCache) {
        this.cachedViews.push(view.name as string)
      }
    },

    /** 删除标签页 */
    delView(view: TagView) {
      this.delVisitedView(view)
      this.delCachedView(view)
      return {
        visitedViews: [...this.visitedViews],
        cachedViews: [...this.cachedViews]
      }
    },

    delVisitedView(view: TagView) {
      for (const [i, v] of this.visitedViews.entries()) {
        if (v.path === view.path) {
          this.visitedViews.splice(i, 1)
          break
        }
      }
    },

    delCachedView(view: TagView) {
      const index = this.cachedViews.indexOf(view.name as string)
      index > -1 && this.cachedViews.splice(index, 1)
    },

    /** 删除其他标签页 */
    delOthersViews(view: TagView) {
      this.delOthersVisitedViews(view)
      this.delOthersCachedViews(view)
      return {
        visitedViews: [...this.visitedViews],
        cachedViews: [...this.cachedViews]
      }
    },

    delOthersVisitedViews(view: TagView) {
      this.visitedViews = this.visitedViews.filter(v => {
        return v.meta?.affix || v.path === view.path
      })
    },

    delOthersCachedViews(view: TagView) {
      const index = this.cachedViews.indexOf(view.name as string)
      if (index > -1) {
        this.cachedViews = this.cachedViews.slice(index, index + 1)
      } else {
        this.cachedViews = []
      }
    },

    /** 删除所有标签页 */
    delAllViews() {
      this.delAllVisitedViews()
      this.delAllCachedViews()
      return {
        visitedViews: [...this.visitedViews],
        cachedViews: [...this.cachedViews]
      }
    },

    delAllVisitedViews() {
      const affixTags = this.visitedViews.filter(tag => tag.meta?.affix)
      this.visitedViews = affixTags
    },

    delAllCachedViews() {
      this.cachedViews = []
    },

    /** 更新标签页 */
    updateVisitedView(view: TagView) {
      for (let i = 0; i < this.visitedViews.length; i++) {
        if (this.visitedViews[i].path === view.path) {
          this.visitedViews[i] = { ...this.visitedViews[i], ...view }
          break
        }
      }
    }
  }
})