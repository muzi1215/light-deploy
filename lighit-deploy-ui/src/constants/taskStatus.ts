/**
 * 任务状态与任务日志级别展示映射。
 *
 * 统一维护「后端数字编码 → 中文文案 + el-tag 颜色」，避免任务列表、服务器连通性测试抽屉等
 * 多个页面各自维护同一套映射后出现文案或颜色不一致（AGENTS.md §20.7）。
 *
 * 与后端定义的对应关系：
 * - 任务状态 → com.lx.deploy.common.constants.TaskStatus
 *   （0 排队中 / 1 执行中 / 2 成功 / 3 失败 / 4 已取消）
 * - 日志级别 → com.lx.deploy.common.enums.task.TaskLogLevel
 *   （DEBUG / INFO / WARN / ERROR）
 */
import type { TagType } from './tagStyle.ts'
import type { TagStyleItem } from './tagStyle.ts'

/** 任务终态编码：成功、失败、已取消。进入终态后任务不再流转，前端应停止轮询。 */
export const TASK_STATUS_SUCCESS = 2
export const TASK_STATUS_FAILED = 3
export const TASK_STATUS_CANCELED = 4

/**
 * 任务状态映射（对应后端 TaskStatus 常量类）。
 *
 * 颜色口径：排队中＝info、执行中＝primary、成功＝success、失败＝danger、已取消＝warning。
 */
export const TASK_STATUS_MAP: Record<number, TagStyleItem> = {
  0: { label: '排队中', tag: 'info' },
  1: { label: '执行中', tag: 'primary' },
  2: { label: '成功', tag: 'success' },
  3: { label: '失败', tag: 'danger' },
  4: { label: '已取消', tag: 'warning' }
}

/**
 * 按任务状态编码取展示项。
 *
 * @param status 任务状态编码，取值见后端 TaskStatus（0/1/2/3/4）
 * @returns 匹配的展示项；编码为空或未知时返回兜底项（未知 + info），保证页面不留空白
 */
export function getTaskStatusStyle(status?: number | null): TagStyleItem {
  if (status === undefined || status === null) {
    return { label: '未知', tag: 'info' }
  }
  return TASK_STATUS_MAP[status] || { label: '未知', tag: 'info' }
}

/**
 * 判断任务状态是否为终态。
 *
 * @param status 任务状态编码，取值见后端 TaskStatus（0/1/2/3/4）
 * @returns true 表示任务已结束（成功/失败/已取消），此时应停止轮询日志
 */
export function isTaskFinal(status?: number | null): boolean {
  return (
    status === TASK_STATUS_SUCCESS ||
    status === TASK_STATUS_FAILED ||
    status === TASK_STATUS_CANCELED
  )
}

/** 日志级别对应的标签颜色（对应后端 TaskLogLevel 枚举）。 */
export const LOG_LEVEL_TAG: Record<string, TagType> = {
  DEBUG: 'info',
  INFO: 'primary',
  WARN: 'warning',
  ERROR: 'danger'
}
