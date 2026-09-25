/**
 * 构建状态展示映射。
 *
 * 统一维护「后端数字编码 → 中文文案 + el-tag 颜色」，避免构建列表、构建触发对话框等多个页面
 * 各自维护同一套映射后出现文案或颜色不一致（AGENTS.md §20.7）。
 *
 * 与后端定义的对应关系：
 * - 构建状态 → com.lx.deploy.common.constants.BuildStatus
 *   （0 排队中 / 1 构建中 / 2 成功 / 3 失败）
 */
import type { TagStyleItem } from './tagStyle.ts'

/** 构建终态编码：成功、失败。进入终态后构建记录不再流转，前端可展示删除入口。 */
export const BUILD_STATUS_SUCCESS = 2
export const BUILD_STATUS_FAILED = 3

/**
 * 构建状态映射（对应后端 BuildStatus 常量类）。
 *
 * 颜色口径：排队中＝info、构建中＝primary、成功＝success、失败＝danger。
 */
export const BUILD_STATUS_MAP: Record<number, TagStyleItem> = {
  0: { label: '排队中', tag: 'info' },
  1: { label: '构建中', tag: 'primary' },
  2: { label: '成功', tag: 'success' },
  3: { label: '失败', tag: 'danger' }
}

/**
 * 按构建状态编码取展示项。
 *
 * @param status 构建状态编码，取值见后端 BuildStatus（0/1/2/3）
 * @returns 匹配的展示项；编码为空或未知时返回兜底项（未知 + info），保证页面不留空白
 */
export function getBuildStatusStyle(status?: number | null): TagStyleItem {
  if (status === undefined || status === null) {
    return { label: '未知', tag: 'info' }
  }
  return BUILD_STATUS_MAP[status] || { label: '未知', tag: 'info' }
}

/**
 * 判断构建状态是否为终态。
 *
 * @param status 构建状态编码，取值见后端 BuildStatus（0/1/2/3）
 * @returns true 表示构建已结束（成功 / 失败），此时允许删除、可停止轮询
 */
export function isBuildFinal(status?: number | null): boolean {
  return status === BUILD_STATUS_SUCCESS || status === BUILD_STATUS_FAILED
}
