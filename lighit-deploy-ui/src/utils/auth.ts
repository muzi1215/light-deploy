import Cookies from 'js-cookie'

/** Token 存储 Key - 与 Vue2 版本保持一致 */
const TokenKey = 'vue_admin_template_token'

/** 获取 Token - Vue3 中导出为命名函数 */
export function getToken(): string | undefined {
  return Cookies.get(TokenKey)
}

/** 设置 Token */
export function setToken(token: string): string | undefined {
  return Cookies.set(TokenKey, token)
}

/** 移除 Token */
export function removeToken(): void {
  Cookies.remove(TokenKey)
}