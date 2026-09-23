/**
 * 凭据类型展示映射（与后端 com.lx.deploy.common.enums.credential.CredentialType 一一对应）。
 *
 * 后端枚举变更时必须同步本文件：新增类型要补 label/tagType，并确认是否属于 Git 可绑定类型。
 * 被使用的位置：凭据管理页（列表标签与表单下拉）、项目表单的 Git 凭据下拉。
 */

/** 凭据类型选项：value 是数据库存储的编码，label 是页面展示文本 */
export const CREDENTIAL_TYPE_OPTIONS: { value: string; label: string; tip: string }[] = [
  {
    value: 'GIT_TOKEN',
    label: 'Git 访问令牌',
    tip: 'HTTPS 形式的仓库地址使用，如 https://gitee.com/group/repo.git'
  },
  {
    value: 'SSH_KEY',
    label: 'Git 私钥',
    tip: 'SSH 形式的仓库地址使用，如 git@gitee.com:group/repo.git'
  },
  {
    value: 'REGISTRY',
    label: '镜像仓库凭据',
    tip: '登录私有镜像仓库使用，通常是用户名 + 密码；用户名建议写在元数据里'
  },
  {
    value: 'SSH_PASSWORD',
    label: 'SSH 密码（保留）',
    tip: '保留类型，当前没有消费方；服务器 SSH 密码在「服务器管理」里单独维护'
  }
]

/** 凭据类型编码到中文的映射，用于列表列渲染 */
export const CREDENTIAL_TYPE_MAP: Record<string, string> = Object.fromEntries(
  CREDENTIAL_TYPE_OPTIONS.map((item) => [item.value, item.label])
)

/** 凭据类型编码到标签颜色的映射（口径见 AGENTS.md §20.7） */
export const CREDENTIAL_TYPE_TAG: Record<string, string> = {
  GIT_TOKEN: 'primary',
  SSH_KEY: 'warning',
  REGISTRY: 'success',
  SSH_PASSWORD: 'info'
}

/**
 * 可用于 Git 拉取的凭据类型。
 *
 * 与后端 CredentialType.isGitType() 保持一致：项目表单的 Git 凭据下拉只列这两类，
 * 后端 ProjectService 也会拒绝其它类型（前端先拦，后端为准）。
 */
export const GIT_CREDENTIAL_TYPES: string[] = ['GIT_TOKEN', 'SSH_KEY']
