/**
 * 演示只读模式 Store。
 *
 * 定位：在权限中心尚未接入前，为「上线演示」提供一个前端的全局总开关。
 * 开启后，所有挂了 v-readonly 指令的写操作按钮（新增 / 编辑 / 删除 / 发布 / 回滚等）
 * 会被置灰禁用，平台进入「仅供查看」状态。
 *
 * 开关取值优先级（从高到低）：
 * 1. localStorage 运行时覆盖（导航栏锁图标点击后写入，刷新后沿用）；
 * 2. 编译期环境变量 VITE_DEMO_READONLY（生产 / 演示构建默认 true，开发默认 false）。
 *
 * 重要边界：这是纯前端 UI 开关，不是安全边界。后端接口本身未做拦截，
 * 懂技术的人直接调 API 仍可写数据；对演示场景足够，真正防误改需待权限中心接入。
 */
import { defineStore } from 'pinia'

const STORAGE_KEY = 'light-deploy-demo-readonly'

/** 读取初始只读状态：运行时本地覆盖优先，否则回落到编译期环境变量 */
function readInitial(): boolean {
  const local = localStorage.getItem(STORAGE_KEY)
  if (local === '1') return true
  if (local === '0') return false
  return import.meta.env.VITE_DEMO_READONLY === 'true'
}

export const useDemoStore = defineStore('demo', {
  state: () => ({
    /** 演示只读模式：true 时全平台写操作按钮置灰禁用 */
    readonly: readInitial()
  }),

  actions: {
    /** 运行时切换只读模式，并持久化到 localStorage，下次启动沿用 */
    toggleReadonly() {
      this.readonly = !this.readonly
      localStorage.setItem(STORAGE_KEY, this.readonly ? '1' : '0')
    },

    /** 强制设定只读模式（供设置入口调用） */
    setReadonly(value: boolean) {
      this.readonly = value
      localStorage.setItem(STORAGE_KEY, value ? '1' : '0')
    }
  }
})
