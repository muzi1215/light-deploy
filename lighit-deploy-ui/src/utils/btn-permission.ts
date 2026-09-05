import { useUserStore } from '@/store/user.ts'

/**
 * 判断当前用户是否有此按钮权限
 * Vue3 + Pinia 版本：在模块顶层获取 store 实例
 * 使用方式：在组件中调用 `hasBtnPermission('bnt.xxx.add')`
 */
export default function hasBtnPermission(permission: string): boolean {
  const userStore = useUserStore()
  return userStore.buttons.indexOf(permission) !== -1
}