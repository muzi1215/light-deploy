import type { RouteRecordRaw } from 'vue-router'

/** 路由元信息 - 扩展 vue-router 的 RouteMeta */
declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题 */
    title?: string
    /** 图标 */
    icon?: string
    /** 是否固定标签 */
    affix?: boolean
    /** 是否隐藏面包屑 */
    breadcrumb?: boolean
    /** 高亮菜单路径 */
    activeMenu?: string
    /** 是否不缓存 */
    noCache?: boolean
    /** 角色权限 */
    roles?: string[]
  }
}

/** 应用路由配置 */
export interface AppRouteRecordRaw extends Omit<RouteRecordRaw, 'meta'> {
  meta?: RouteMeta
  hidden?: boolean
  alwaysShow?: boolean
  children?: AppRouteRecordRaw[]
}