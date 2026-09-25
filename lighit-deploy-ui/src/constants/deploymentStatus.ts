/**
 * 部署状态展示映射。
 *
 * 统一维护「后端数字编码 → 中文文案 + el-tag 颜色」，避免部署列表、目标抽屉、回滚确认等
 * 多处各自维护同一套映射后出现文案或颜色不一致（AGENTS.md §20.7）。
 *
 * 与后端定义的对应关系：
 * - 部署主任务状态 → com.lx.deploy.common.constants.DeploymentStatus
 *   （0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 回滚中 / 6 已回滚）
 * - 部署目标状态 → com.lx.deploy.common.constants.DeploymentTargetStatus
 *   （0 排队 / 1 发布中 / 2 成功 / 3 失败 / 4 已取消 / 5 已跳过）
 * - 健康检查状态 → com.lx.deploy.common.constants.HealthStatus（0 未检查 / 1 健康 / 2 不健康）
 */
import type { TagStyleItem } from './tagStyle.ts'

/** 部署状态编码：成功、失败、已取消、已回滚。进入终态后允许删除。 */
export const DEPLOY_STATUS_SUCCESS = 2
export const DEPLOY_STATUS_FAILED = 3
export const DEPLOY_STATUS_CANCELED = 4
export const DEPLOY_STATUS_ROLLING_BACK = 5
export const DEPLOY_STATUS_ROLLED_BACK = 6

/**
 * 部署主任务状态映射（对应后端 DeploymentStatus）。
 *
 * 颜色口径：排队/已取消＝info、发布中/回滚中＝primary、成功/已回滚＝success、失败＝danger。
 */
export const DEPLOY_STATUS_MAP: Record<number, TagStyleItem> = {
  0: { label: '排队', tag: 'info' },
  1: { label: '发布中', tag: 'primary' },
  2: { label: '成功', tag: 'success' },
  3: { label: '失败', tag: 'danger' },
  4: { label: '已取消', tag: 'info' },
  5: { label: '回滚中', tag: 'primary' },
  6: { label: '已回滚', tag: 'success' }
}

/** 部署目标状态映射（对应后端 DeploymentTargetStatus）。已跳过用 warning 突出「这台没动过」。 */
export const DEPLOY_TARGET_STATUS_MAP: Record<number, TagStyleItem> = {
  0: { label: '排队', tag: 'info' },
  1: { label: '发布中', tag: 'primary' },
  2: { label: '成功', tag: 'success' },
  3: { label: '失败', tag: 'danger' },
  4: { label: '已取消', tag: 'info' },
  5: { label: '已跳过', tag: 'warning' }
}

/** 健康检查状态映射（对应后端 HealthStatus）。 */
export const HEALTH_STATUS_MAP: Record<number, TagStyleItem> = {
  0: { label: '未检查', tag: 'info' },
  1: { label: '健康', tag: 'success' },
  2: { label: '不健康', tag: 'danger' }
}

/** 按部署主任务状态编码取展示项。 */
export function getDeployStatusStyle(status?: number | null): TagStyleItem {
  if (status === undefined || status === null) {
    return { label: '未知', tag: 'info' }
  }
  return DEPLOY_STATUS_MAP[status] || { label: '未知', tag: 'info' }
}

/** 按部署目标状态编码取展示项。 */
export function getDeployTargetStatusStyle(status?: number | null): TagStyleItem {
  if (status === undefined || status === null) {
    return { label: '未知', tag: 'info' }
  }
  return DEPLOY_TARGET_STATUS_MAP[status] || { label: '未知', tag: 'info' }
}

/** 按健康检查状态编码取展示项；未配置健康检查（null）同样渲染为「未检查」。 */
export function getHealthStatusStyle(status?: number | null): TagStyleItem {
  if (status === undefined || status === null) {
    return { label: '未检查', tag: 'info' }
  }
  return HEALTH_STATUS_MAP[status] || { label: '未知', tag: 'info' }
}

/** 判断部署是否处于终态（成功 / 失败 / 已取消 / 已回滚），终态允许删除。 */
export function isDeployFinal(status?: number | null): boolean {
  return (
    status === DEPLOY_STATUS_SUCCESS ||
    status === DEPLOY_STATUS_FAILED ||
    status === DEPLOY_STATUS_CANCELED ||
    status === DEPLOY_STATUS_ROLLED_BACK
  )
}

/** 判断部署是否可回滚：只有发布成功的部署有「新版本」可退。 */
export function canRollback(status?: number | null): boolean {
  return status === DEPLOY_STATUS_SUCCESS
}

/** 判断部署是否可取消：排队中与发布中可取消，回滚中不允许（回滚必须走完）。 */
export function canCancel(status?: number | null): boolean {
  return status === 0 || status === 1
}

/** 部署策略展示映射（对应后端 DeployStrategy 枚举）。 */
export const DEPLOY_STRATEGY_MAP: Record<string, string> = {
  SINGLE_TARGET: '单目标',
  ALL_AT_ONCE: '全量发布',
  ROLLING: '滚动发布'
}

/** 部署策略下拉选项（新建部署对话框使用）。 */
export const DEPLOY_STRATEGY_OPTIONS = Object.entries(DEPLOY_STRATEGY_MAP).map(([value, label]) => ({
  value,
  label
}))
