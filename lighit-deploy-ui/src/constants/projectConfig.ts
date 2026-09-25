/**
 * 项目枚举与三段配置 schema（构建 / 运行 / 健康检查）。
 *
 * 键名契约以 docs/项目模块.md §2.3 为准，后端消费类是
 * BuildConfig / DeployConfig / HealthConfig（@JsonProperty 显式绑定）：
 * schema 里没有的键后端会静默忽略，但编辑器会保留它们（见 ConfigEditor 的未知键合并）。
 *
 * 被使用的位置：项目列表页（枚举映射）、项目编辑页 / 详情页（schema 渲染）、ConfigEditor 组件。
 */

// ==================== 运行时与构建模式枚举 ====================

/** 运行时展示映射（与后端 RuntimeType 枚举一致） */
export const RUNTIME_TYPE_MAP: Record<string, string> = {
  DOCKER: 'Docker容器',
  JAR: 'Java JAR',
  STATIC: '静态产物'
}

/** 运行时选项（表单单选用） */
export const RUNTIME_TYPE_OPTIONS: { value: string; label: string }[] = [
  { value: 'DOCKER', label: 'Docker容器' },
  { value: 'JAR', label: 'Java JAR' },
  { value: 'STATIC', label: '静态产物' }
]

/**
 * 构建模式选项（与后端 BuildMode 枚举一一对应，枚举变更时必须同步本数组）。
 *
 * label 用于编辑页表单（空间充足，展示完整名称）；
 * shortLabel 用于搜索下拉与列表列（宽度有限，必须用短名）；
 * runtimes 是该构建模式允许的运行时，镜像自后端 RuntimeType.supports()：Docker 类构建模式
 * 产出镜像、只配 DOCKER，脚本 / 仅发布产物产出文件、只配 JAR 与 STATIC（AGENTS.md §23.4）。
 */
export const BUILD_MODE_OPTIONS: { value: string; label: string; shortLabel: string; runtimes: string[] }[] = [
  { value: 'PLATFORM_TEMPLATE', label: '平台 Dockerfile 模板', shortLabel: '平台模板', runtimes: ['DOCKER'] },
  { value: 'PROJECT_DOCKERFILE', label: '项目自带 Dockerfile', shortLabel: '项目Dockerfile', runtimes: ['DOCKER'] },
  { value: 'LAYERED_DOCKER', label: '分层构建（base + app）', shortLabel: '分层构建', runtimes: ['DOCKER'] },
  { value: 'SCRIPT_DEPLOY', label: '脚本部署', shortLabel: '脚本部署', runtimes: ['JAR', 'STATIC'] },
  { value: 'ARTIFACT_ONLY', label: '仅发布已有产物', shortLabel: '仅发布产物', runtimes: ['JAR', 'STATIC'] }
]

/** 构建模式展示映射，从 BUILD_MODE_OPTIONS 派生，用于列表列渲染 */
export const BUILD_MODE_MAP: Record<string, string> = Object.fromEntries(
  BUILD_MODE_OPTIONS.map((item) => [item.value, item.shortLabel])
)

// ==================== 配置字段 schema ====================

/** 单个配置字段的渲染定义 */
export interface ConfigField {
  /** JSON 键名（snake_case，与后端 @JsonProperty 一致） */
  key: string
  /** 页面展示名 */
  label: string
  /** 控件类型 */
  type: 'input' | 'number' | 'select'
  /** 分组标题，表单模式按分组分区渲染 */
  group: string
  /** 占位提示 */
  placeholder?: string
  /** select 类型的选项 */
  options?: { value: string; label: string }[]
  /** 字段下方的小字说明 */
  tip?: string
  /**
   * 字段值示例（label 后「?」图标悬浮展示）。
   * 用于不容易凭空写出正确格式的字段，如构建命令：按构建模式给出可直接抄的完整命令。
   */
  examples?: { label: string; text: string }[]
  /** 栅格宽度，默认 8（一行三列），长字段用 12 / 24 */
  span?: number
  /** 是否必填（表单模式提交前校验；语义校验仍以后端为准） */
  required?: boolean
  /**
   * 联动显隐条件：键是上下文名（runtimeType / buildMode），值是命中的编码列表。
   * 不配置则始终显示。
   */
  showIf?: Record<string, string[]>
  /**
   * 跨字段一致性校验：提交前在 ConfigEditor 内执行（仅表单模式可见字段）。
   *
   * 用于「单个字段都合法、组合起来必然失败」的情况，例如 module_dir 与构建命令里的
   * -pl/--projects 同时存在时，平台先 cd 进模块目录、Maven reactor 里就找不到该模块。
   * test 返回 true 表示命中问题，按 message 直接拦截提交。
   */
  checks?: { test: (values: Record<string, any>) => boolean; message: string }[]
}

/** 健康检查探测方式选项（与后端 HealthChecker 支持的方式一致：HTTP 2xx / TCP 可连通） */
const HEALTH_TYPE_OPTIONS = [
  { value: 'HTTP', label: 'HTTP 探测' },
  { value: 'TCP', label: 'TCP 探活' }
]

/** 构建配置 schema（对应 project.build_config_json，消费方 BuildTaskHandler） */
export const BUILD_CONFIG_FIELDS: ConfigField[] = [
  {
    key: 'module_name',
    label: '子模块名',
    type: 'input',
    group: '模块定位',
    span: 8,
    placeholder: '多模块仓库名，如 auth-tool（单模块可留空）',
    tip: '默认 -pl 参数、默认模块目录名与默认镜像名的来源'
  },
  {
    key: 'module_dir',
    label: '模块目录',
    type: 'input',
    group: '模块定位',
    span: 8,
    placeholder: '相对仓库根目录，默认等于子模块名',
    tip: '子模块相对仓库根目录；单模块仓库可省略。平台会先 cd 到该目录再执行构建命令，因此命令里不要再写 -pl 指定同一模块',
    checks: [
      {
        test: (values) =>
          Boolean(values.module_dir) &&
          /(^|\s)(-pl|--projects)(\s|=)/.test(String(values.build_command || '')),
        message:
          '「模块目录」与构建命令里的 -pl/--projects 冲突：平台会先 cd 到模块目录再执行命令，'
          + 'Maven reactor 里找不到该模块（报 Could not find the selected project in the reactor）。'
          + '两者二选一——清空模块目录、从仓库根用 -pl 指定子模块，或保留模块目录、命令里去掉 -pl。'
      }
    ]
  },
  {
    key: 'pom_path',
    label: 'pom 路径',
    type: 'input',
    group: '模块定位',
    span: 8,
    placeholder: '默认 {模块目录}/pom.xml',
    tip: 'Maven 构建入口 pom 路径，通常留空'
  },
  {
    key: 'build_command',
    label: '构建命令',
    type: 'input',
    group: '构建执行',
    span: 24,
    placeholder: '如 mvn -B clean package -pl auth-tool -am -DskipTests',
    tip: '脚本部署必填；Docker 模板构建时填写可覆盖平台推导的构建命令。'
      + '命令在仓库根或模块目录下执行（见「模块目录」），因此 -pl 只在「模块目录留空」时使用；'
      + '工具不在 PATH 里时优先在服务器档案的「构建环境初始化脚本」补 PATH，其次写绝对路径',
    examples: [
      {
        label: '脚本部署 · 多模块从仓库根构建（配合「模块目录」留空）',
        text: 'mvn -B clean package -pl auth-tool -am -DskipTests'
      },
      {
        label: '脚本部署 · 已填「模块目录」时（平台先 cd 进模块，不要再带 -pl）',
        text: 'mvn -B clean package -DskipTests'
      },
      {
        label: '脚本部署 · 构建机默认 JDK 与项目不符时，命令前缀指定 JDK',
        text: 'JAVA_HOME=/usr/lib/jdk1.8.0_121 mvn -B clean package -pl auth-tool -am -DskipTests'
      },
      {
        label: '脚本部署 · 单模块仓库',
        text: 'mvn -B clean package -DskipTests'
      },
      {
        label: '脚本部署 · 前端静态产物（STATIC 运行时）',
        text: 'npm run build'
      },
      {
        label: '平台 Dockerfile 模板 · 覆盖平台推导的构建命令（留空则平台自动执行 mvn package）',
        text: 'mvn -B clean package -DskipTests'
      },
      {
        label: '项目自带 Dockerfile / 分层构建 · 通常留空（平台直接 docker build）',
        text: '（留空）'
      }
    ]
  },
  {
    key: 'command_timeout_seconds',
    label: '构建超时（秒）',
    type: 'number',
    group: '构建执行',
    span: 8,
    placeholder: '默认 1800（30 分钟）',
    tip: '构建命令的等待上限，超时后平台会终止远端构建进程组并把已收到的输出留在日志里。'
      + '首次构建要从仓库下载全部依赖（分钟级），增量构建通常几十秒，差一个数量级；留空用平台默认值'
  },
  {
    key: 'artifact_path',
    label: '产物路径',
    type: 'input',
    group: '构建产物',
    span: 12,
    placeholder: '相对仓库根，支持 * 通配，如 target/app.jar',
    tip: '脚本部署 / 仅发布产物必填；先按仓库根查找，再按模块目录回落'
  },
  {
    key: 'config_paths',
    label: '配置文件路径',
    type: 'input',
    group: '构建产物',
    span: 12,
    placeholder: '如 src/main/resources/application.yml，多个用英文逗号分隔',
    tip: '随产物发布的配置文件，构建时转存到产物同级 config 目录；JAR 运行时消费，Docker 不消费'
  },
  {
    key: 'dockerfile_path',
    label: 'Dockerfile 路径',
    type: 'input',
    group: 'Docker 构建',
    span: 12,
    placeholder: '相对仓库根，如 Dockerfile',
    tip: '项目自带 Dockerfile 构建模式必填',
    showIf: { buildMode: ['PROJECT_DOCKERFILE'] }
  },
  {
    key: 'base_dockerfile_path',
    label: '基础层 Dockerfile',
    type: 'input',
    group: 'Docker 构建',
    span: 12,
    placeholder: '相对仓库根的基础层 Dockerfile 路径',
    tip: '分层构建模式必填',
    showIf: { buildMode: ['LAYERED_DOCKER'] }
  },
  {
    key: 'image_repository',
    label: '镜像仓库地址',
    type: 'input',
    group: 'Docker 构建',
    span: 12,
    placeholder: '不含 tag，如 registry.cn-hangzhou.aliyuncs.com/ns/app',
    tip: '留空时回落到项目绑定的镜像仓库（仅 Docker 构建模式消费）',
    showIf: { buildMode: ['PLATFORM_TEMPLATE', 'PROJECT_DOCKERFILE', 'LAYERED_DOCKER'] }
  }
]

/** 运行配置 schema（对应 project.runtime_config_json，消费方 DeployTaskHandler） */
export const RUNTIME_CONFIG_FIELDS: ConfigField[] = [
  {
    key: 'work_dir',
    label: '固定工作目录',
    type: 'input',
    group: 'JAR 运行时',
    span: 12,
    placeholder: '留空回落到 {部署根目录}/apps/{项目编码}',
    tip: '目录固定不变，发版原地替换 jar，systemd 单元只在首次部署生成',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'deploy_base',
    label: '部署根目录覆盖',
    type: 'input',
    group: 'JAR 运行时',
    span: 12,
    placeholder: '留空使用服务器档案的部署根目录 + /apps',
    tip: '覆盖 work_dir 未填写时的默认落点，一般不需要填',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'service_name',
    label: 'systemd 服务名',
    type: 'input',
    group: 'JAR 运行时',
    span: 8,
    placeholder: '不含 .service，默认取项目编码',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'java_bin',
    label: 'java 路径',
    type: 'input',
    group: 'JAR 运行时',
    span: 8,
    placeholder: '如 /usr/bin/java，留空自动探测',
    tip: '多 JDK 环境建议显式写绝对路径',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'jvm_options',
    label: 'JVM 参数',
    type: 'input',
    group: 'JAR 运行时',
    span: 8,
    placeholder: '如 -Xms256m -Xmx512m',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'spring_profiles_active',
    label: '激活 Profile',
    type: 'input',
    group: 'JAR 运行时',
    span: 8,
    placeholder: '默认 prod',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'config_dir',
    label: '配置目录名',
    type: 'input',
    group: 'JAR 运行时',
    span: 8,
    placeholder: '默认 config',
    tip: '工作目录下存放 application.yml 的子目录',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'config_file',
    label: '配置文件名',
    type: 'input',
    group: 'JAR 运行时',
    span: 8,
    placeholder: '默认 application.yml',
    tip: '须与构建配置 config_paths 指向的文件名一致',
    showIf: { runtimeType: ['JAR'] }
  },
  {
    key: 'container_name',
    label: '容器名',
    type: 'input',
    group: 'Docker 运行时',
    span: 8,
    placeholder: '默认取项目编码',
    showIf: { runtimeType: ['DOCKER'] }
  },
  {
    key: 'port_mappings',
    label: '端口映射',
    type: 'input',
    group: 'Docker 运行时',
    span: 8,
    placeholder: '如 8080:8080，多个用英文逗号分隔',
    tip: '留空表示不映射端口',
    showIf: { runtimeType: ['DOCKER'] }
  },
  {
    key: 'extra_docker_args',
    label: '附加 docker 参数',
    type: 'input',
    group: 'Docker 运行时',
    span: 8,
    placeholder: '如 -e KEY=VALUE --network xxx',
    tip: '原样拼到 docker run 命令行',
    showIf: { runtimeType: ['DOCKER'] }
  },
  {
    key: 'static_root',
    label: 'Nginx 根目录',
    type: 'input',
    group: '静态产物',
    span: 12,
    placeholder: '默认 /usr/share/nginx/html/{项目编码}',
    showIf: { runtimeType: ['STATIC'] }
  }
]

/** 健康检查配置 schema（对应 project.health_config_json，消费方 HealthChecker） */
export const HEALTH_CONFIG_FIELDS: ConfigField[] = [
  {
    key: 'type',
    label: '探测方式',
    type: 'select',
    group: '探测方式',
    span: 8,
    options: HEALTH_TYPE_OPTIONS,
    placeholder: '默认 HTTP',
    tip: 'HTTP 2xx 视为健康；TCP 端口可连通即健康；整段留空则跳过健康检查'
  },
  {
    key: 'port',
    label: '探测端口',
    type: 'number',
    group: '探测方式',
    span: 8,
    required: true,
    placeholder: '业务端口，如 8080',
    tip: '探测在平台侧发起，平台需能访问目标机该端口'
  },
  {
    key: 'path',
    label: 'HTTP 路径',
    type: 'input',
    group: '探测方式',
    span: 8,
    placeholder: '默认 /，仅 HTTP 探测使用',
    showIf: { type: ['HTTP'] }
  },
  {
    key: 'timeout_seconds',
    label: '单次超时（秒）',
    type: 'number',
    group: '探测策略',
    span: 8,
    placeholder: '默认 3'
  },
  {
    key: 'retry_count',
    label: '最大探测次数',
    type: 'number',
    group: '探测策略',
    span: 8,
    placeholder: '默认 3，任一次通过即健康'
  },
  {
    key: 'interval_seconds',
    label: '探测间隔（秒）',
    type: 'number',
    group: '探测策略',
    span: 8,
    placeholder: '默认 3，给应用留出启动时间'
  }
]

// ==================== 三段配置示例（JSON 模式的占位提示） ====================

/**
 * 构建配置示例（多模块仓库只构建 auth-tool）。
 *
 * 这里演示「module_dir + 模块内命令」这一种写法：平台会先 cd 到 auth-tool 再执行命令，
 * 所以命令里**不带** -pl（带上会与 module_dir 冲突，表单模式提交时会被拦截）。
 * 若偏好从仓库根构建，则清空 module_dir、命令改成 `mvn -B clean package -pl auth-tool -am -DskipTests`。
 */
export const BUILD_CONFIG_EXAMPLE = `{
  "module_name": "auth-tool",
  "module_dir": "auth-tool",
  "build_command": "mvn -B clean package -DskipTests",
  "artifact_path": "auth-tool/linux/auth-tool-7888/auth-tool.jar",
  "config_paths": "auth-tool/linux/auth-tool-7888/application.yml"
}`

/**
 * 运行配置示例（JAR：固定工作目录 + 原地替换，jar 与配置分离）
 *
 * 注意：deploy_dir / ports 这类写法不被消费，JAR 用 work_dir，DOCKER 用 container_name 与 port_mappings。
 */
export const RUNTIME_CONFIG_EXAMPLE = `{
  "work_dir": "/lx/jarapps/auth-tool-7888",
  "service_name": "auth-tool-7888",
  "java_bin": "/usr/lib/jdk1.8.0_121/bin/java",
  "jvm_options": "-Xmx512m",
  "spring_profiles_active": "prod",
  "config_dir": "config",
  "config_file": "application.yml"
}`

/** 健康检查配置示例（整段留空则跳过健康检查；探测在平台侧发起，平台需能访问目标机业务端口） */
export const HEALTH_CONFIG_EXAMPLE = `{
  "type": "TCP",
  "port": 7888,
  "timeout_seconds": 3,
  "retry_count": 20,
  "interval_seconds": 5
}`
