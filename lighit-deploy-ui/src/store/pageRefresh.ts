/**
 * 页面刷新标记 Store。
 *
 * 场景：列表页被 keep-alive 缓存（切走再切回不会重新执行 setup / onMounted），
 * 编辑页保存成功后跳回列表时需要让列表重新拉数据。编辑页 markDirty，列表页
 * onActivated 里 consumeDirty 判断，命中才刷新——普通标签页切换不产生多余请求。
 */
import { defineStore } from 'pinia'

export const usePageRefreshStore = defineStore('pageRefresh', {
  state: () => ({
    dirtyPages: [] as string[]
  }),

  actions: {
    /** 标记某个列表页需要在下次激活时刷新 */
    markDirty(page: string) {
      if (!this.dirtyPages.includes(page)) {
        this.dirtyPages.push(page)
      }
    },

    /**
     * 查询并清除标记。
     *
     * @returns 命中返回 true（调用方应立即刷新数据）
     */
    consumeDirty(page: string): boolean {
      const index = this.dirtyPages.indexOf(page)
      if (index > -1) {
        this.dirtyPages.splice(index, 1)
        return true
      }
      return false
    }
  }
})
