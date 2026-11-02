<!--
  Layout 主布局 - Vue3 版本
  Vue2 → Vue3 变化：
  - mixins: [ResizeMixin] → composable useResizeHandler()
  - this.$store.state.app.sidebar → useAppStore().sidebar
  - computed 改为 computed() 函数
  - methods 改为普通函数
-->
<template>
  <div :class="classObj" class="app-wrapper">
    <div v-if="device === 'mobile' && sidebar.opened" class="drawer-bg" @click="handleClickOutside" />
    <sidebar class="sidebar-container" />
    <div class="main-container">
      <div :class="{ 'fixed-header': fixedHeader }">
        <navbar />
      </div>
      <tags-view />
      <el-alert
        v-if="demoStore.readonly"
        type="warning"
        :closable="false"
        show-icon
        class="demo-readonly-banner"
        title="演示模式：编辑、删除、发布等写操作已锁定，当前仅供查看（导航栏锁图标可临时解除）"
      />
      <app-main />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * Vue3 Composition API：
 * - 使用 <script setup> 语法糖，无需 export default
 * - computed 从 vue 中导入，返回计算属性 ref
 * - store 通过 useXxxStore() 获取
 * - mixins 通过 composable 函数替代
 */
import { computed } from 'vue'
import { useAppStore } from '@/store/app.ts'
import { useSettingsStore } from '@/store/settings.ts'
import { useDemoStore } from '@/store/demo.ts'
import { useResizeHandler } from '@/composables/useResizeHandler.ts'
import Navbar from './components/Navbar.vue'
import Sidebar from './components/Sidebar/index.vue'
import AppMain from './components/AppMain.vue'
import TagsView from './components/TagsView/index.vue'

defineOptions({ name: 'Layout' })

// 使用 composable 替代 Vue2 mixin
useResizeHandler()

// Pinia store - 替代 Vue2 的 this.$store
const appStore = useAppStore()
const settingsStore = useSettingsStore()
const demoStore = useDemoStore()

// 计算属性 - Vue3 中使用 computed() 函数
const sidebar = computed(() => appStore.sidebar)
const device = computed(() => appStore.device)
const fixedHeader = computed(() => settingsStore.fixedHeader)

const classObj = computed(() => ({
  hideSidebar: !sidebar.value.opened,
  openSidebar: sidebar.value.opened,
  withoutAnimation: sidebar.value.withoutAnimation,
  mobile: device.value === 'mobile'
}))

// 方法 - Vue3 中直接定义为普通函数
function handleClickOutside() {
  appStore.closeSideBar(false)
}
</script>

<style lang="scss" scoped>
  @use "@/styles/mixin" as *;
  @use "@/styles/variables" as *;

  .app-wrapper {
    @include clearfix;
    position: relative;
    height: 100%;
    width: 100%;
    background: var(--page-bg);
    &.mobile.openSidebar{
      position: fixed;
      top: 0;
    }
  }
  .drawer-bg {
    background: #000;
    opacity: 0.3;
    width: 100%;
    top: 0;
    height: 100%;
    position: absolute;
    z-index: 999;
  }

  .fixed-header {
    position: fixed;
    top: 0;
    right: 0;
    z-index: 9;
    width: calc(100% - var(--side-bar-width));
    transition: width 0.28s;
  }

  .hideSidebar .fixed-header {
    width: calc(100% - 54px)
  }

  .mobile .fixed-header {
    width: 100%;
  }

  // 演示只读横幅：贴顶、不与内容重叠，提醒当前为查看态
  .demo-readonly-banner {
    border-radius: 0;
    border-left: none;
    border-right: none;
  }
</style>