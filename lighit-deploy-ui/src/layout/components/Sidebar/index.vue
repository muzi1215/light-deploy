<!--
  Sidebar 侧边栏 - Vue3 版本
  Vue2 → Vue3 变化：
  - mapGetters → 直接使用 Pinia store
  - SCSS variables 导入从 @/styles/variables.scss → CSS 变量
  - this.$router → useRouter()
  - this.$route → useRoute()
  - this.$store → useAppStore()
  - el-submenu → el-sub-menu (Element Plus 组件名变更)
-->
<template>
  <div :class="{ 'has-logo': showLogo }">
    <logo v-if="showLogo" :collapse="isCollapse" />
    <el-scrollbar wrap-class="scrollbar-wrapper">
      <el-menu
        :key="themeStore.current"
        :default-active="activeMenu"
        :collapse="isCollapse"
        :background-color="variables.menuBg"
        :text-color="variables.menuText"
        :unique-opened="false"
        :active-text-color="variables.menuActiveText"
        :collapse-transition="false"
        mode="vertical"
      >
        <sidebar-item v-for="route in routes" :key="route.path" :item="route" :base-path="route.path" />
      </el-menu>
    </el-scrollbar>
  </div>
</template>

<script setup lang="ts">
/**
 * Vue3 Composition API - 替代 Vue2 Options API
 * 使用 computed 和 store 直接访问状态
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAppStore } from '@/store/app.ts'
import { useSettingsStore } from '@/store/settings.ts'
import { useThemeStore } from '@/store/theme.ts'
import { variables } from '@/styles/variables.ts'
import Logo from './Logo.vue'
import SidebarItem from './SidebarItem.vue'

defineOptions({ name: 'Sidebar' })

const route = useRoute()
const router = useRouter()
const appStore = useAppStore()
const settingsStore = useSettingsStore()
const themeStore = useThemeStore()

// 计算属性
const sidebar = computed(() => appStore.sidebar)
const isCollapse = computed(() => !sidebar.value.opened)
const showLogo = computed(() => settingsStore.sidebarLogo)

/** 路由列表 - 合并静态路由和动态路由 */
const routes = computed(() => {
  const staticRoutes = router.options.routes
  const antRouter = (window as any).antRouter || []
  return [...staticRoutes, ...antRouter]
})

/** 当前激活菜单 */
const activeMenu = computed(() => {
  const { meta, path } = route
  // 如果配置了 activeMenu，高亮指定路径
  if (meta.activeMenu) {
    return meta.activeMenu as string
  }
  return path
})
</script>