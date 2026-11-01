/**
 * AIops 联动的展示映射。
 *
 * 与后端 `AiopsEventType`、`AiopsEventStatus` 一一对应，**只在这里维护一份**：
 * 事件类型的短名由后端下发（`eventTypeLabel`，枚举是权威定义），
 * 前端只维护「用哪个颜色」，避免两处各写一份中文名导致漂移。
 */

/** 事件类型 → 标签与时间线圆点的颜色 */
export const AIOPS_EVENT_TAG: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  DEPLOYMENT_PREPARED: 'info',
  DEPLOYMENT_STARTED: 'primary',
  DEPLOYMENT_HOST_SUCCESS: 'success',
  DEPLOYMENT_HOST_FAILED: 'danger',
  DEPLOYMENT_SUCCEEDED: 'success',
  DEPLOYMENT_FAILED: 'danger',
  DEPLOYMENT_SILENCE_CLOSED: 'info',
  DEPLOYMENT_ROLLBACK_SUCCEEDED: 'success',
  DEPLOYMENT_ROLLBACK_FAILED: 'danger',
  DEPLOYMENT_CANCELLED: 'warning'
}

/** 未知事件类型的兜底颜色（历史数据可能有已废弃的编码） */
export const AIOPS_EVENT_TAG_FALLBACK = 'info' as const

/**
 * 投递状态 → 标签颜色。
 *
 * 注意它只表示「事件送到 AIops 了没有」，与事件本身的业务含义无关：
 * 一条 HOST_FAILED 事件只要送达，投递状态就是成功。
 */
export const AIOPS_DELIVERY_TAG: Record<number, 'success' | 'warning' | 'danger' | 'info'> = {
  0: 'info',
  1: 'success',
  2: 'warning',
  3: 'danger'
}

/** 静默窗状态 → 标签颜色（已过期时按 2 走） */
export const AIOPS_SILENCE_TAG: Record<number, 'success' | 'info' | 'warning' | 'danger'> = {
  0: 'success',
  1: 'info',
  2: 'warning'
}
