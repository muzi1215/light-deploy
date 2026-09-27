/**
 * 操作日志常量：操作类型、请求方式、结果的展示映射。
 *
 * 编码与后端 BusinessType 枚举一一对应（audit_log.action）；
 * 结果编码 0 失败 / 1 成功，与后端 AuditResult 常量、数据库字段注释一致（AGENTS.md §4.5）。
 */

/** 操作类型编码 → 中文文案与标签样式 */
export const BUSINESS_TYPE_MAP: Record<string, { label: string; tag: 'success' | 'warning' | 'danger' | 'primary' | 'info' }> = {
  INSERT: { label: '新增', tag: 'success' },
  UPDATE: { label: '修改', tag: 'warning' },
  DELETE: { label: '删除', tag: 'danger' },
  EXPORT: { label: '导出', tag: 'success' },
  IMPORT: { label: '导入', tag: 'primary' },
  EXECUTE: { label: '执行', tag: 'warning' },
  ROLLBACK: { label: '回滚', tag: 'danger' },
  CANCEL: { label: '取消', tag: 'warning' },
  OTHER: { label: '其他', tag: 'info' }
}

/** 操作类型下拉选项（与后端枚举取值保持一致） */
export const BUSINESS_TYPE_OPTIONS = Object.entries(BUSINESS_TYPE_MAP).map(([value, item]) => ({
  value,
  label: item.label
}))

/** HTTP 请求方式 → 标签样式 */
export const REQUEST_METHOD_TAG: Record<string, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  GET: 'primary',
  POST: 'success',
  PUT: 'warning',
  DELETE: 'danger',
  PATCH: 'info'
}

/** 操作结果：0 失败，1 成功 */
export const AUDIT_RESULT_FAILURE = 0
export const AUDIT_RESULT_SUCCESS = 1

/** 取操作类型的中文文案，未识别时回落「其他」 */
export function businessTypeText(value?: string): string {
  if (!value) return '其他'
  return BUSINESS_TYPE_MAP[value]?.label || '其他'
}

/** 取操作类型的标签样式，未识别时回落 info */
export function businessTypeTag(value?: string): 'success' | 'warning' | 'danger' | 'primary' | 'info' {
  if (!value) return 'info'
  return BUSINESS_TYPE_MAP[value]?.tag || 'info'
}

/** 取请求方式的标签样式 */
export function requestMethodTag(value?: string): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (!value) return 'info'
  return REQUEST_METHOD_TAG[value] || 'info'
}

/**
 * 资源级字段中文名：`资源类型.字段名` → 中文。
 *
 * 只登记「同名但含义不同」的字段：通用含义的字段放 FIELD_LABELS，
 * 两边维护会导致同一份翻译写两次、改一处漏一处。
 */
const SCOPED_FIELD_LABELS: Record<string, string> = {
  'deployment.releaseId': '发布版本',
  'deployment.strategy': '部署策略',
  'deployment.status': '部署状态',
  'build.status': '构建状态',
  'project.status': '项目状态',
  'server.status': '服务器状态',
  'credential.status': '凭据状态',
  'registry.status': '仓库状态',
  'environment.status': '环境状态',
  'projectEnvironmentTarget.status': '启用状态',
  'server.environmentTag': '环境标签',
  'server.authType': '认证类型',
  'projectEnvironmentTarget.environmentId': '所属环境',
  'project.runtimeType': '运行时类型',
  'project.buildMode': '构建模式'
}

/** 通用字段中文名（跨资源含义一致，直接按字段名命中） */
const FIELD_LABELS: Record<string, string> = {
  serverName: '服务器名称',
  projectName: '项目名称',
  projectCode: '项目编码',
  environmentName: '环境名称',
  name: '名称',
  host: '主机地址',
  sshPort: 'SSH端口',
  username: '登录用户',
  repoUrl: '仓库地址',
  branch: '分支',
  remark: '备注',
  description: '描述',
  address: '地址',
  deployOrder: '发布顺序',
  isPrimary: '主目标',
  deployStrategy: '默认策略',
  batchSize: '批次大小',
  versionSeq: '版本序号',
  gitCommit: '提交短码',
  workDir: '工作目录',
  healthConfigJson: '健康检查配置',
  runtimeConfigJson: '差异化运行配置',
  dockerfilePath: 'Dockerfile路径',
  javaVersion: 'JDK版本',
  activeProfile: '激活配置'
}

/**
 * 取变更明细里字段的展示名。
 *
 * 三级回落：资源级翻译 → 通用翻译 → 原字段名。
 * 最后一级不能省也不能换成「未知」：字段名本身是可用的信息，
 * 伪翻译会让排查的人对着一个猜不出来源的名字发呆。
 */
export function fieldLabel(resourceType?: string, field?: string): string {
  if (!field) return '-'
  const scoped = resourceType ? SCOPED_FIELD_LABELS[`${resourceType}.${field}`] : undefined
  return scoped || FIELD_LABELS[field] || field
}

/** 变更明细里表示「没有值」的占位符，由后端统一约定 */
export const EMPTY_VALUE = '-'

/** 变更明细里表示「记录已删除」的占位符，由后端统一约定 */
export const DELETED_VALUE = '(已删除)'
