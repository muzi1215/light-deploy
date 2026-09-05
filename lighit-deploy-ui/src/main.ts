/**
 * 应用入口 - 通用后台管理模版
 * Vue3 + Vite + TypeScript + Element Plus + Pinia
 */
import { createApp } from 'vue'

// 样式导入
import 'normalize.css'
import 'element-plus/dist/index.css'
import '@/styles/index.scss'

// Element Plus
import ElementPlus from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'

// 核心依赖
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

// 图标和权限
import '@/icons'
import '@/permission.ts'
import hasBtnPermission from '@/utils/btn-permission.ts'

// 创建 Vue3 应用实例
const app = createApp(App)

// 使用插件
const pinia = createPinia()
app.use(pinia)
app.use(router)
app.use(ElementPlus, { locale: zhCn })

// 初始化主题（在 Pinia 创建后）
import { useThemeStore } from '@/store/theme.ts'
const themeStore = useThemeStore()
themeStore.initTheme()

// 全局属性挂载 - 按钮权限
app.config.globalProperties.$hasBP = hasBtnPermission

// 全局注册所有 Element Plus 图标
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
for (const [iconName, iconComp] of Object.entries(ElementPlusIconsVue)) {
  app.component(iconName, iconComp)
}

// 挂载应用
app.mount('#app')
