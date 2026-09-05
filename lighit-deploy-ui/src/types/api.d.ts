/** 后端API统一响应结构 */
export interface ApiResponse<T = any> {
  code: number
  message: string
  data: T
}

/** 分页查询结果 */
export interface PageResult<T = any> {
  data: T[]
  totalCount: number
  totalPage?: number
  pageSize?: number
}

/** 分页查询参数 */
export interface PageParams {
  page: number
  limit: number
  [key: string]: any
}

/** 用户信息 */
export interface UserInfo {
  id: string
  username: string
  name: string
  avatar?: string
  phone?: string
  email?: string
  deptName?: string
  postName?: string
  status: number
  createTime?: string
  buttons?: string[]
  routers?: RouteRecordItem[]
}

/** 角色信息 */
export interface RoleInfo {
  id: string
  roleName: string
  roleCode: string
  roleType: number
  createTime?: string
}

/** 菜单信息 */
export interface MenuInfo {
  id: string
  parentId: string
  name: string
  type: number
  path: string
  component: string
  perms: string
  icon: string
  sortValue: number
  status: number
  children: MenuInfo[]
  isSelect?: boolean
  select?: boolean
}

/** 部门信息 */
export interface DeptInfo {
  id: string
  name: string
  children: DeptInfo[]
}

/** 服务器信息 */
export interface ServerInfo {
  id: string
  hostname: string
  ip: string
  intranetIp?: string
  env: string
  tag: string
  leaderId: string
  leader: string
  status: number
  remark: string
  syncStatus: number
  lastSyncTime: string
  patrolEnabled: number
  alarmEmailEnabled: number
  aliyunSyncEnabled: number
  createTime: string
}

/** 告警信息 */
export interface AlarmInfo {
  id: string
  serverId: string
  serverHostname: string
  serverIp: string
  alarmTypeName: string
  alarmLevel: string
  alarmContent: string
  status: number
  sendStatus: number
  sendRecipient: string
  aggregationCount: number
  createTime: string
}

/** 路由配置项（后端返回） */
export interface RouteRecordItem {
  path: string
  component: string
  name?: string
  meta?: RouteMeta
  children?: RouteRecordItem[]
  hidden?: boolean
  alwaysShow?: boolean
  redirect?: string
}