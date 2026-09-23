/**
 * 枚举展示映射与标签颜色常量。
 *
 * 统一维护"后端枚举 → 中文文案 + el-tag 颜色"的对应关系，避免各页面各自定义导致
 * 同一枚举在不同页面显示成不同颜色。颜色取值只使用 Element Plus 支持的
 * primary / success / info / warning / danger 五种。
 *
 * 与后端枚举的对应关系（AGENTS.md §20.7）：
 * - 环境标签 → com.lx.deploy.server.enums.EnvironmentTag（1 开发 / 2 测试 / 3 生产）
 */

/** el-tag 支持的标签颜色类型，与 Element Plus 的 type 取值一致。 */
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

/** 枚举展示项：中文文案 + 标签颜色。 */
export interface TagStyleItem {
  /** 页面展示的中文文案 */
  label: string
  /** el-tag 的 type 取值 */
  tag: TagType
}

/**
 * 服务器环境标签映射（对应后端 EnvironmentTag 枚举）。
 *
 * 颜色为项目确认口径：开发＝primary、测试＝info、生产＝success。
 * 所有展示环境标签的页面必须复用本映射，不允许页面内重新定义。
 */
export const ENV_TAG_MAP: Record<number, TagStyleItem> = {
  1: { label: '开发', tag: 'primary' },
  2: { label: '测试', tag: 'info' },
  3: { label: '生产', tag: 'success' }
}

/**
 * 按环境标签编码取展示项。
 *
 * @param envTag 环境标签编码，取值见后端 EnvironmentTag 枚举（1/2/3）
 * @returns 匹配的展示项；编码为空或未知时返回兜底项（未知 + info），保证页面不留空白
 */
export function getEnvTagStyle(envTag?: number | null): TagStyleItem {
  if (envTag === undefined || envTag === null) {
    return { label: '未知', tag: 'info' }
  }
  return ENV_TAG_MAP[envTag] || { label: '未知', tag: 'info' }
}
