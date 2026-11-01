import axios, { type AxiosInstance, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user.ts'

/**
 * 会话失效业务码。
 *
 * <p>后端 {@code AuthInterceptor} 在会话缺失时抛出 {@code UNAUTHORIZED(4002)}，由
 * {@code GlobalExceptionHandler} 以 HTTP 400 返回。因此它<b>一定走 axios 的错误分支</b>，
 * 成功分支里的 code 判断拦不到它——两处都要处理，否则页面组件的业务请求会话失效时会卡在首页。</p>
 */
const CODE_UNAUTHORIZED = 4002

/**
 * 判断是否登录接口。
 *
 * <p>登录失败与会话失效共用 4002，必须按 URL 区分：前者要弹提示，后者要跳登录页。</p>
 */
function isLoginRequest(url?: string): boolean {
  return (url || '').includes('/auth/login')
}

/**
 * 清理登录态并回到登录页。
 *
 * <p>登录标志位（Cookie 哨兵）与 Pinia 用户态一并清除；已在登录页时不重复跳转，避免死循环。
 * 使用动态 import 取 router，避免 request ↔ router ↔ store 形成静态循环依赖。</p>
 */
function redirectToLogin(): void {
  const userStore = useUserStore()
  userStore.resetToken()

  const hash = window.location.hash.slice(1) || '/'
  if (hash.startsWith('/login')) {
    return
  }
  import('@/router').then((m) => {
    m.default.replace(`/login?redirect=${encodeURIComponent(hash)}`)
  })
}

/** 创建 axios 实例 - Vue3 中不再依赖 Vue.prototype，直接使用模块单例 */
const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_BASE_API, // Vite 环境变量使用 import.meta.env
  timeout: 500000, // 请求超时时间
  // 后端用 HttpSession 鉴权，登录态靠浏览器自动携带 SESSION Cookie 维持。
  // 当前前端与 /api 同源（Nginx 反代或 Vite 代理），默认就会带；
  // 显式打开可避免日后改成跨域部署时静默丢 Cookie（表现为「登录成功但下个请求 4002」）。
  withCredentials: true
})

/** 请求拦截器 - Vue3 中 store 在模块顶层直接使用 */
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 在 Vue3 + Pinia 中，需要在拦截器内部获取 store 实例，避免循环依赖
    const userStore = useUserStore()
    // 操作人随请求带出：后端操作日志切面读这两个头落审计记录（audit_log.operator_id/name）。
    // 本项目用 HttpSession 鉴权（浏览器自动携带 SESSION Cookie），不发送 Authorization Token；
    // 缺失操作人头时后端记 system，不会让请求失败。
    if (userStore.id) {
      config.headers['X-Operator-Id'] = userStore.id
    }
    if (userStore.name) {
      config.headers['X-Operator-Name'] = userStore.name
    }
    return config
  },
  (error: any) => {
    console.log(error)
    return Promise.reject(error)
  }
)

/** 响应拦截器 */
service.interceptors.response.use(
  (response) => {
    // 文件下载（responseType: 'blob'）不做业务码校验，直接返回原始响应：
    // 二进制正文上不存在 code 字段，若继续走下面的判断会恒为「非 200」，
    // 把正常的日志导出误判成错误。响应类型由调用方自行判断（见 api/task.ts#exportTaskLog）。
    if (response.config.responseType === 'blob') {
      return response
    }

    const res = response.data
    if (res.code !== 200) {
      // 兜底：个别接口可能以 HTTP 200 + 业务码 4002 返回（如未来调整异常状态码），
      // 这里同样回到登录页；主路径见下方错误分支。
      if (res.code === CODE_UNAUTHORIZED) {
        redirectToLogin()
        return Promise.reject(new Error(res.message || '登录已失效，请重新登录'))
      }
      ElMessage({
        message: res.message || 'Error',
        type: 'error',
        duration: 5000
      })
      return Promise.reject(new Error(res.message || 'Error'))
    } else {
      return res
    }
  },
  (error) => {
    const data = error?.response?.data

    if (data?.code === CODE_UNAUTHORIZED) {
      // 登录接口自身的 4002 是「用户名或密码错误」，不是会话失效：
      // 登录页的 catch 是空实现，提示完全依赖这里，因此必须弹出来，绝不能跳转（否则登录页会自己刷掉输入）。
      if (isLoginRequest(error?.config?.url)) {
        const msg = data?.message || '用户名或密码错误'
        ElMessage({ message: msg, type: 'error', duration: 5000 })
        return Promise.reject(new Error(msg))
      }

      // 其余接口的 4002 才是会话失效：清登录态并回登录页，不弹错误提示，
      // 避免「登录页 + 错误弹窗」双份噪音。守卫在导航时会给出一次提示。
      redirectToLogin()
      return Promise.reject(new Error(data?.message || '登录已失效，请重新登录'))
    }

    // ✅ 优先取后端返回的 message
    const msg =
      data?.message ||
      error?.message ||
      'Error'

    ElMessage({
      message: msg,
      type: 'error',
      duration: 5000
    })

    return Promise.reject(error)
  }
)

export default service
