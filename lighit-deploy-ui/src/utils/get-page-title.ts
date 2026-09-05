import defaultSettings from '@/settings.ts'

const title = defaultSettings.title || 'AI运维平台'

/** 获取页面标题 - Vue3 中保持纯函数工具不变 */
export default function getPageTitle(pageTitle?: string): string {
  if (pageTitle) {
    return `${pageTitle} - ${title}`
  }
  return title
}