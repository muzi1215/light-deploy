/**
 * 响应式处理 - Vue3 Composable 版本
 * Vue2 → Vue3 变化：
 * - Vue2 mixin → Vue3 composable (组合式函数)
 * - beforeMount → onBeforeMount
 * - beforeDestroy → onBeforeUnmount
 * - mounted → onMounted
 * - this.$_xxx → 普通函数
 * - store.dispatch → pinia store 直接调用
 */
import { onBeforeMount, onBeforeUnmount, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAppStore } from '@/store/app.ts'

const { body } = document
const WIDTH = 992 // Bootstrap 响应式断点

export function useResizeHandler() {
  const route = useRoute()
  const appStore = useAppStore()

  /** 判断是否为移动端 */
  function isMobile(): boolean {
    const rect = body.getBoundingClientRect()
    return rect.width - 1 < WIDTH
  }

  /** 窗口大小变化处理 */
  function resizeHandler(): void {
    if (!document.hidden) {
      const mobile = isMobile()
      appStore.toggleDevice(mobile ? 'mobile' : 'desktop')

      if (mobile) {
        appStore.closeSideBar(true)
      }
    }
  }

  // 挂载时注册事件监听
  onBeforeMount(() => {
    window.addEventListener('resize', resizeHandler)
  })

  // 挂载后判断设备类型
  onMounted(() => {
    if (isMobile()) {
      appStore.toggleDevice('mobile')
      appStore.closeSideBar(true)
    }
  })

  // 卸载时移除事件监听
  onBeforeUnmount(() => {
    window.removeEventListener('resize', resizeHandler)
  })

  // 监听路由变化 - 移动端自动关闭侧边栏
  watch(
    () => route.path,
    () => {
      if (appStore.device === 'mobile' && appStore.sidebar.opened) {
        appStore.closeSideBar(false)
      }
    }
  )
}