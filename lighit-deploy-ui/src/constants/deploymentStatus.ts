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

/**
 * 部署策略一句话说明。
 *
 * 作为下拉选项的副标题使用，让人在选的那一刻就能区分三者；完整解释见 DEPLOY_STRATEGY_TIPS。
 * 文案必须与后端行为一致：全量不是并发、滚动按批次且失败即中止。
 */
export const DEPLOY_STRATEGY_SUMMARY: Record<string, string> = {
  SINGLE_TARGET: '只发一台：主目标优先，无主目标取发布顺序最靠前的一台',
  ALL_AT_ONCE: '全部目标一起发，某台失败不中止，其余继续发完',
  ROLLING: '按批次大小分批发，任一批失败立即中止，后续目标保持旧版本'
}

/**
 * 部署策略完整说明（多行，用于鼠标悬浮提示）。
 *
 * 每行一条独立事实，按「行为 → 风险/注意 → 适用场景」排列，方便逐条读。
 * 三处页面（环境管理、新建部署、部署列表）共用这一份，避免各写一套说法。
 */
export const DEPLOY_STRATEGY_TIPS: Record<string, string[]> = {
  SINGLE_TARGET: [
    '只发布一台：优先发主目标（is_primary=1），没有主目标就取发布顺序最靠前的一台。',
    '这是策略层强制「只发一台」，不是因为只绑了一台——绑了 4 台它也只发主目标那台，其余三台不参与。',
    '适用：这个环境本来就是单机部署，或只想先发一台做验证。'
  ],
  ALL_AT_ONCE: [
    '所有启用目标算一批，批内逐台串行发布（不是并发），总耗时约等于各台之和。',
    '某台失败不中止，剩余目标继续发完；可能出现「部分新版本 + 部分旧版本」的混合态，失败那台要手工补。',
    '适用：无状态服务、可以接受一起上，或失败后另有兜底手段。'
  ],
  ROLLING: [
    '按环境的「批次大小」切批，批内逐台串行；任一批失败立即中止，后续目标置为已跳过、保持旧版本不动。',
    '批次大小只在滚动下生效，且必须小于目标总数才有分批效果；等于或大于目标数时只剩一批，行为退化成全量。',
    '失败的那台不会自动还原，需要走「新建部署选旧版本重发」收尾（回滚按钮只对成功记录开放）。',
    '适用：想「先发一批看看，炸了就停」，把影响面限制在已发的那几台。'
  ]
}

/** 批次大小悬浮说明：只对滚动生效，且必须小于目标数才有意义。 */
export const BATCH_SIZE_TIPS: string[] = [
  '只对「滚动发布」生效，单目标与全量发布不读这个值。',
  '表示每批包含几台目标；批内仍是逐台串行，不是同时更新。',
  '必须小于目标总数才有分批效果：3 台目标填 3 只剩一批，滚动会退化成全量，建议填 1 或 2。'
]

/** 发布顺序悬浮说明：所有策略都按它排序，不是只有滚动。 */
export const DEPLOY_ORDER_TIPS: string[] = [
  '数字小的先发布；启用目标先按此值升序，值相同再按绑定创建时间升序。',
  '所有策略都按这个顺序展开目标，不只是滚动发布。',
  '滚动发布下顺序即批次先后：填 1 的那台先发，发完再发下一批。',
  '发起部署时，这个顺序会随目标快照固化到那条部署记录上——之后再改这里，不会影响已经创建的部署。',
  '单目标策略下它还有第二重作用：没有主目标时，取顺序最靠前的那一台作为发布对象。'
]

/** 新建部署里的策略与环境默认策略的关系说明。 */
export const DEPLOY_STRATEGY_OVERRIDE_TIPS: string[] = [
  '这里只覆盖本次部署，不改环境档案。',
  '留空则使用环境管理里该环境配置的默认策略；环境也没配时退化为单目标。',
  '想改「以后每次都按什么策略发」，去环境管理改部署策略，改完只影响之后新建的部署。',
  '批次大小不能在这里覆盖，只能取环境上配置的值。'
]

/** 部署策略下拉选项（新建部署对话框使用），副标题带上三种策略的区分点。 */
export const DEPLOY_STRATEGY_OPTIONS = Object.entries(DEPLOY_STRATEGY_MAP).map(([value, label]) => ({
  value,
  label,
  desc: DEPLOY_STRATEGY_SUMMARY[value]
}))
