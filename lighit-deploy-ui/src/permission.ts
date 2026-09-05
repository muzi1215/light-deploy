/**
 * 权限守卫 - 通用后台管理模版
 *
 * 核心逻辑：
 * 1. Token 校验：无 token → 跳转登录页
 * 2. 动态路由加载：从后端 getInfo() 接口获取菜单 JSON，动态注册路由
 * 3. 静态路由兜底：若后端返回的 routers 为空，则仅渲染 constantRoutes
 */
import router from './router'
import { useUserStore } from '@/store/user.ts'
import { getToken } from '@/utils/auth.ts'
import { ElMessage } from 'element-plus'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import getPageTitle from '@/utils/get-page-title.ts'
import Layout from '@/layout/index.vue'
import ParentView from '@/components/ParentView/index.vue'
import type { RouteRecordItem } from '@/types/api'
import type { RouteRecordRaw } from 'vue-router'

NProgress.configure({ showSpinner: false })

/** 白名单 - 不需要登录即可访问的路由 */
const whiteList = ['/login']

// 使用 Vite 的 import.meta.glob 实现动态组件导入
const modules = import.meta.glob('./views/**/*.vue')

router.beforeEach(async (to, from) => {
  NProgress.start()

  document.title = getPageTitle(to.meta.title as string | undefined)

  const hasToken = getToken()

  if (hasToken) {
    if (to.path === '/login') {
      return { path: '/' }
    }

    const userStore = useUserStore()

    if (userStore.name) {
      return true
    }

    try {
      // 获取用户信息和菜单
      await userStore.getInfo()

      // 如果后端没有返回菜单（routers 为空），则使用静态路由模式
      // 可以在 constantRoutes 中手动添加业务路由
      if (userStore.menus.length < 1) {
        ;(window as any).antRouter = []
        return true
      }

      // 过滤动态路由 - 将后端 JSON 转为 Vue Router 路由记录
      const menus = filterAsyncRouter(userStore.menus)

      // 逐个添加动态路由
      menus.forEach((route: RouteRecordRaw) => {
        router.addRoute(route)
      })

      // 添加 404 兜底路由
      router.addRoute({
        path: '/:pathMatch(.*)*',
        redirect: '/404',
        ...{ hidden: true }
      } as any)

      ;(window as any).antRouter = menus

      // 重新触发导航，此时 userStore.name 已设置
      return { path: to.path, replace: true }
    } catch (error: any) {
      console.log(error)
      await userStore.resetToken()
      ElMessage.error((error as Error)?.message || 'Has Error')
      return `/login?redirect=${to.path}`
    }
  } else {
    if (whiteList.indexOf(to.path) !== -1) {
      return true
    }
    return `/login?redirect=${to.path}`
  }
})

router.afterEach(() => {
  NProgress.done()
})

/**
 * 过滤动态路由 - 将后端返回的路由数据转换为 Vue Router 路由对象
 *
 * 后端菜单格式（JSON）：
 * {
 *   path: '/system/user',
 *   component: 'system/user/list',   // 相对于 src/views/ 的路径（不含 .vue）
 *   name: 'UserList',
 *   meta: { title: '用户管理', icon: 'user' },
 *   children: [...]
 * }
 *
 * 特殊 component 值：
 * - 'Layout' → 使用布局容器
 * - 'ParentView' → 使用父级视图容器（只显示子路由）
 */
function filterAsyncRouter(asyncRouterMap: RouteRecordItem[]): RouteRecordRaw[] {
  const accessedRouters: RouteRecordRaw[] = []

  asyncRouterMap.forEach(route => {
    const newRoute: any = {
      path: route.path,
      name: route.name,
      meta: route.meta || {},
      hidden: route.hidden,
      alwaysShow: route.alwaysShow,
      redirect: route.redirect
    }

    if (route.component) {
      if (route.component === 'Layout') {
        newRoute.component = Layout
      } else if (route.component === 'ParentView') {
        newRoute.component = ParentView
      } else {
        // 使用 Vite 的 import.meta.glob 动态导入组件
        const componentPath = `./views/${route.component}.vue`
        let importFn = modules[componentPath]

        if (!importFn) {
          const altPath = `./views/${route.component}/index.vue`
          importFn = modules[altPath]
        }

        if (importFn) {
          newRoute.component = importFn
        } else {
          console.warn(`组件未找到: ${route.component}，使用 404 页面替代`)
          newRoute.component = () => import('./views/404.vue')
          newRoute.redirect = undefined
          newRoute.children = []
        }
      }
    }

    if (route.children && route.children.length > 0) {
      newRoute.children = filterAsyncRouter(route.children)
    }

    accessedRouters.push(newRoute as RouteRecordRaw)
  })

  return accessedRouters
}
