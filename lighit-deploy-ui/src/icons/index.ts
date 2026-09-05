/**
 * SVG 图标注册 - Vue3 版本
 * Vue2 中使用 require.context 自动导入 SVG
 * Vue3 + Vite 中使用 import.meta.glob 替代
 */
import 'virtual:svg-icons-register'

// 使用 Vite 的 import.meta.glob 实现 SVG 图标的热加载
// 替代 Vue2 中的 require.context('./svg', false, /\.svg$/)
const svgModules = import.meta.glob('./svg/*.svg', { eager: true })
console.log('已加载 SVG 图标:', Object.keys(svgModules).length)