/**
 * 侧边栏 CSS 变量对应的 JS 常量
 * el-menu 组件直接使用 CSS 变量（如 var(--menu-bg)），
 * CSS 变量通过 [data-theme] 切换实现主题响应
 */
export const variables = {
  menuText: 'var(--menu-text)',
  menuActiveText: 'var(--menu-active-text)',
  subMenuActiveText: 'var(--sub-menu-active-text)',
  menuBg: 'var(--menu-bg)',
  menuHover: 'var(--menu-hover)',
  subMenuBg: 'var(--sub-menu-bg)',
  subMenuHover: 'var(--sub-menu-hover)',
  sideBarWidth: '210px'
}