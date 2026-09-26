/**
 * 路由配置 - 通用后台管理模版
 *
 * 支持两种菜单模式：
 * 1. 动态菜单：后端返回菜单 JSON，前端动态渲染（默认模式）
 * 2. 静态菜单：在 constantRoutes 中添加 children 即可
 *
 * 如需切换到静态菜单模式，将后端的 getInfo 接口 routers 返回空数组即可。
 */
import { createRouter, createWebHashHistory, type RouteRecordRaw } from 'vue-router'
import type { AppRouteRecordRaw } from '@/types/router'

/* Layout */
import Layout from '@/layout/index.vue'

/** 静态路由：不需要权限的基础路由 */
export const constantRoutes: AppRouteRecordRaw[] = [
  {
    path: '/login',
    component: () => import('@/views/login/index.vue'),
    hidden: true,
    meta: { title: '登录' }
  },
  {
    path: '/',
    component: Layout,
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/dashboard/index.vue'),
        meta: { title: '首页', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    // 服务器管理（一级目录）：纳管部署目标 Linux 服务器（SSH 凭据加密落库）
    path: '/server',
    component: Layout,
    redirect: '/server/list',
    alwaysShow: true,
    meta: { title: '服务器管理', icon: 'tree' },
    children: [
      {
        path: 'list',
        name: 'ServerList',
        component: () => import('@/views/server/index.vue'),
        meta: { title: '服务器列表' }
      }
    ]
  },
  {
    // 项目管理（一级目录）：项目、环境与部署目标的发布拓扑配置
    path: '/project',
    component: Layout,
    redirect: '/project/list',
    alwaysShow: true,
    meta: { title: '项目管理', icon: 'form' },
    children: [
      {
        path: 'list',
        name: 'ProjectList',
        component: () => import('@/views/project/index.vue'),
        meta: { title: '项目列表' }
      },
      {
        // 新增 / 编辑 / 详情走独立页面（hidden 不进菜单）：表单字段多，弹框空间不够
        path: 'add',
        name: 'ProjectAdd',
        component: () => import('@/views/project/edit.vue'),
        meta: { title: '新增项目', noCache: true },
        hidden: true
      },
      {
        path: 'edit/:id',
        name: 'ProjectEdit',
        component: () => import('@/views/project/edit.vue'),
        meta: { title: '编辑项目', noCache: true },
        hidden: true
      },
      {
        path: 'detail/:id',
        name: 'ProjectDetail',
        component: () => import('@/views/project/detail.vue'),
        meta: { title: '项目详情', noCache: true },
        hidden: true
      },
      {
        path: 'environment',
        name: 'EnvironmentList',
        component: () => import('@/views/environment/index.vue'),
        meta: { title: '环境管理' }
      },
      {
        path: 'deploy-target',
        name: 'DeployTargetList',
        component: () => import('@/views/deployTarget/index.vue'),
        meta: { title: '部署目标' }
      }
    ]
  },
  {
    // 任务管理（一级目录）：异步任务查询与执行日志
    path: '/task',
    component: Layout,
    redirect: '/task/list',
    alwaysShow: true,
    meta: { title: '任务管理', icon: 'example' },
    children: [
      {
        path: 'list',
        name: 'TaskList',
        component: () => import('@/views/task/index.vue'),
        meta: { title: '任务列表' }
      }
    ]
  },
  {
    // 构建管理（一级目录）：构建记录查询、实时日志与产物查看
    path: '/build',
    component: Layout,
    redirect: '/build/list',
    alwaysShow: true,
    meta: { title: '构建管理', icon: 'Loading' },
    children: [
      {
        path: 'list',
        name: 'BuildList',
        component: () => import('@/views/build/index.vue'),
        meta: { title: '构建列表' }
      }
    ]
  },
  {
    // 发布管理（一级目录）：部署记录查询、目标明细、实时日志与回滚
    path: '/deployment',
    component: Layout,
    redirect: '/deployment/list',
    alwaysShow: true,
    meta: { title: '发布管理', icon: 'Promotion' },
    children: [
      {
        path: 'list',
        name: 'DeploymentList',
        component: () => import('@/views/deploy/index.vue'),
        meta: { title: '发布记录' }
      }
    ]
  },
  {
    // 系统设置（一级目录）：全局资源，不属于某个项目
    // 凭据供 Git 拉取与镜像仓库推送使用；镜像仓库只服务于 DOCKER 运行时（AGENTS.md §23.4）
    path: '/system',
    component: Layout,
    redirect: '/system/credential',
    alwaysShow: true,
    meta: { title: '系统设置', icon: 'Tools' },
    children: [
      {
        path: 'credential',
        name: 'CredentialList',
        component: () => import('@/views/credential/index.vue'),
        meta: { title: '凭据管理' }
      },
      {
        path: 'registry',
        name: 'RegistryList',
        component: () => import('@/views/registry/index.vue'),
        meta: { title: '镜像仓库' }
      }
    ]
  }
]

/** 创建路由实例 */
const router = createRouter({
  history: createWebHashHistory(),
  scrollBehavior: () => ({ top: 0 }),
  routes: constantRoutes as RouteRecordRaw[]
})

/**
 * 重置路由 - 只移除动态路由，保留静态路由
 * 通常在退出登录时调用
 */
export function resetRouter(): void {
  const staticRouteNames = new Set<string>()
  function collectNames(routes: any[]) {
    for (const r of routes) {
      if (r.name) staticRouteNames.add(r.name as string)
      if (r.children) collectNames(r.children)
    }
  }
  collectNames(constantRoutes)

  const currentRoutes = router.getRoutes()
  currentRoutes.forEach(route => {
    if (route.name && !staticRouteNames.has(route.name as string)) {
      router.removeRoute(route.name)
    }
  })
}

export default router
