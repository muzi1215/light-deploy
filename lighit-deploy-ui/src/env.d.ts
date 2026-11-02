/// <reference types="vite/client" />

// 声明 .vue 文件模块
declare module '*.vue' {
  import type { DefineComponent } from 'vue'
  const component: DefineComponent<{}, {}, any>
  export default component
}

// 环境变量类型声明
interface ImportMetaEnv {
  readonly VITE_BASE_API: string
  /** 演示只读模式编译期默认值：生产/演示构建设为 true，开发环境设 false；运行时可在导航栏开关覆盖 */
  readonly VITE_DEMO_READONLY: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}