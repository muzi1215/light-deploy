import axios, { type AxiosInstance, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/store/user.ts'

/** 创建 axios 实例 - Vue3 中不再依赖 Vue.prototype，直接使用模块单例 */
const service: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_BASE_API, // Vite 环境变量使用 import.meta.env
  timeout: 500000 // 请求超时时间
})

/** 请求拦截器 - Vue3 中 store 在模块顶层直接使用 */
service.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 在 Vue3 + Pinia 中，需要在拦截器内部获取 store 实例，避免循环依赖
    const userStore = useUserStore()
    if (userStore.token) {
      config.headers['Authorization'] = `Bearer ${userStore.token}`
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
    const res = response.data
    if (res.code !== 200) {
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
    // ✅ 优先取后端返回的 message
    const msg =
      error?.response?.data?.message ||
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

// service.interceptors.response.use(
//   (response: any) => {
//     // 判断是否是文件下载请求
//     if (response.config.responseType === 'blob') {
//       return response
//     }

//     const res = response.data

//     // 后端返回 code !== 200 视为业务错误
//     if (res.code !== 200) {
//       // Vue3 中 Element Plus 消息提示使用 ElMessage (函数式调用)
//       ElMessage({
//         message: res.message || 'Error',
//         type: 'error',
//         duration: 5 * 1000
//       })
//       return Promise.reject(new Error(res.message || 'Error'))
//     } else {
//       return res
//     }
//   },
//   (error: any) => {
//     console.log('err' + error)
//     ElMessage({
//       message: error.message,
//       type: 'error',
//       duration: 5 * 1000
//     })
//     return Promise.reject(error)
//   }
// )

export default service