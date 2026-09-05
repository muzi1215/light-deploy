/**
 * API 环境配置 - 通用后台管理模版
 *
 * 通过 Vite 环境变量设置各环境地址：
 * - .env.development    开发环境
 * - .env.production     生产环境
 * - .env.staging        预发布环境（可选）
 */
const config: Record<string, Record<string, string>> = {
  development: {},
  production: {},
  staging: {}
}

const env = import.meta.env.MODE || 'development'
export default config[env] || {}
