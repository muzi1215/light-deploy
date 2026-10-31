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
 * 构建模式选项 —— 前端唯一权威定义（与后端 `BuildMode` 枚举一一对应，枚举变更时必须同步本数组）。
 *
 * <p><b>铁律（AGENTS.md §23.4）</b>：新增/删除构建模式只改两处 —— 后端 `BuildMode` 枚举与
 * `RuntimeType.supportedBuildModes`，以及本数组。除此之外任何地方都不得再写一份模式清单：
 * 列表页的展示映射、搜索下拉都从本数组派生。历史上 `build/index.vue` 曾自己手写一份映射，
 * 结果与这里不同步，漏掉了 `JAR_PREBUILD`，导致编辑页无法选择该模式（详见
 * docs/构建模式与执行链路.md §6「变更检查清单」）。</p>
 *
 * <p><b>数组顺序有语义</b>：`edit.vue` 在运行时切换后若发现当前模式不被支持，会回退到该运行时的
 * <b>第一个</b>可选模式。因此每个运行时至少要有一个兜底项，且兜底项应选「最不容易静默出错」的那个
 * （DOCKER 目前是 `PROJECT_DOCKERFILE`）。</p>
 *
 * <p><b>label / shortLabel / runtimes 三个字段缺一不可</b>：</p>
 * <ul>
 *   <li>{@code label} —— 编辑页表单展示（空间充足，写清「平台会做什么」）；</li>
 *   <li>{@code shortLabel} —— 搜索下拉与列表列（宽度有限，只能短名）；</li>
 *   <li>{@code runtimes} —— 该模式允许的运行时，镜像自后端 {@code RuntimeType.supports()}：
 *       Docker 类模式产出镜像、只配 DOCKER；脚本 / 仅发布产物产出文件、只配 JAR 与 STATIC。
 *       <b>漏填 runtimes 会让该模式在任何运行时下都选不到</b>。</li>
 * </ul>
 */
export const BUILD_MODE_OPTIONS: { value: string; label: string; shortLabel: string; runtimes: string[] }[] = [
  {
    value: 'PROJECT_DOCKERFILE',
    label: '项目自带 Dockerfile（Dockerfile 内自行构建产物）',
    shortLabel: '项目Dockerfile',
    runtimes: ['DOCKER']
  },
  {
    // 分层构建同样是「Dockerfile 内自行构建产物」，只是拆成 base + app 两层复用依赖。
    value: 'LAYERED_DOCKER',
    label: '分层构建（Dockerfile.base + Dockerfile.app）',
    shortLabel: '分层构建',
    runtimes: ['DOCKER']
  },
  {
    // 【易错点】脚本部署不产出镜像，只在构建机执行项目自带脚本；目标机按产物文件型发布。
    value: 'SCRIPT_DEPLOY',
    label: '脚本部署（执行项目自带构建脚本）',
    shortLabel: '脚本部署',
    runtimes: ['JAR', 'STATIC']
  },
  {
    // 【易错点】不做任何构建，产物由页面直接上传（UPLOAD 来源专用）。
    value: 'ARTIFACT_ONLY',
    label: '仅发布已有产物（不做构建）',
    shortLabel: '仅发布产物',
    runtimes: ['JAR', 'STATIC']
  },
  {
    // 【易错点】B1：平台先用 Maven 容器打出 jar（遵循 pom 的 finalName / outputDirectory），
    // 再交给项目的「极简 Dockerfile」包成镜像。与该模式搭配的 Dockerfile 只做 COPY <jar>，
    // 自己不具备构建能力 —— 所以 JAR_PREBUILD 与 PROJECT_DOCKERFILE 之间不可互换：
    // 用「极简 Dockerfile」配 PROJECT_DOCKERFILE，Dockerfile 里的 COPY 必然找不到 jar。
    // 反过来用「多阶段 Dockerfile」配 JAR_PREBUILD 会构建两次，浪费一半时间。
    value: 'JAR_PREBUILD',
    label: 'JAR 预构建镜像（平台先打 jar，再用极简 Dockerfile 包镜像）',
    shortLabel: 'JAR预构建',
    runtimes: ['DOCKER']
  }
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
  /** 控件类型；switch 用于布尔键（输出真布尔值，不是 "true" 字符串） */
  type: 'input' | 'number' | 'select' | 'switch'
  /** 分组标题，表单模式按分组分区渲染 */
  group: string
  /** 占位提示 */
  placeholder?: string
  /** select 类型的选项 */
  options?: { value: string; label: string }[]
  /** 字段下方的小字说明 */
  tip?: string
  /**
   * switch 类型的默认展示值。
   *
   * <p>只影响表单初始状态，不写进配置：用户不动它时该键不会出现在 JSON 里，
   * 后端按自己的默认值处理。填这里是为了让开关位置与「不配置时实际会怎样」一致，
   * 避免默认开启的能力在页面上显示成关闭。必须与后端默认值保持一致。</p>
   */
  defaultValue?: boolean
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
    placeholder: '单模块可留空；多模块仓库填子模块名，如 auth-tool',
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
    tip: '仅「脚本部署」消费该键（该模式必填）。项目自带 Dockerfile / 分层构建 / JAR 预构建的构建命令'
      + '由平台内置（docker build / Maven 容器），填了也不会执行。'
      + '命令在仓库根或模块目录下执行（见「模块目录」），因此 -pl 只在「模块目录留空」时使用；'
      + '工具不在 PATH 里时优先在服务器档案的「构建环境初始化脚本」补 PATH，其次写绝对路径',
    showIf: { buildMode: ['SCRIPT_DEPLOY'] },
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
      + '首次构建要从仓库下载全部依赖（分钟级），增量构建通常几十秒，差一个数量级；留空用平台默认值',
    showIf: { buildMode: ['SCRIPT_DEPLOY', 'JAR_PREBUILD'] }
  },
  {
    key: 'artifact_path',
    label: '产物路径',
    type: 'input',
    group: '构建产物',
    span: 12,
    placeholder: '相对仓库根，支持 * 通配，如 target/app.jar',
    tip: '脚本部署 / JAR 预构建必填；先按仓库根查找，再按模块目录回落。'
      + '项目自带 Dockerfile / 分层构建不需要（镜像由 Dockerfile 产出，不取现成产物）',
    showIf: { buildMode: ['SCRIPT_DEPLOY', 'JAR_PREBUILD'] }
  },
  {
    key: 'config_paths',
    label: '配置文件路径',
    type: 'input',
    group: '构建产物',
    span: 12,
    placeholder: '如 src/main/resources/application.yml，多个用英文逗号分隔',
    tip: '随版本发布的配置文件：构建时转存到版本目录 config/，发布时投放到目标机（JAR → 工作目录 config，'
      + 'Docker → work_dir/config），目标机已存在则不覆盖'
  },
  {
    key: 'dockerfile_path',
    label: 'Dockerfile 路径',
    type: 'input',
    group: 'Docker 构建',
    span: 12,
    placeholder: '相对仓库根，如 Dockerfile',
    tip: '必填，相对仓库根（构建上下文就是仓库根，COPY 不能写 ../）。'
      + '项目自带 / 分层构建填「自己会构建产物」的 Dockerfile；'
      + 'JAR 预构建填「只 COPY jar、自己不构建」的极简 Dockerfile（如 Dockerfile.jre）。'
      + '分层构建下它与基础层 Dockerfile 配套使用',
    showIf: { buildMode: ['PROJECT_DOCKERFILE', 'LAYERED_DOCKER', 'JAR_PREBUILD'] }
  },
  {
    key: 'compose_file_path',
    label: 'compose 文件路径',
    type: 'input',
    group: 'Docker 构建',
    span: 12,
    placeholder: '相对仓库根，默认 docker-compose.yml',
    tip: '需要在目标机上用 docker compose 启动时填写。填写后构建成功会把文件原文快照进发布版本，'
      + '发布时原样下发，因此改了这份文件必须重新构建才会生效。'
      + '文件里随版本变化的那个镜像要写成「image: 仓库地址/镜像名:${IMAGE_TAG}」，'
      + '平台会把本次版本号作为同名变量注入；redis、mysql 这类不随版本变的中间件保持原样即可',
    examples: [
      {
        label: '最小可用：平台注入 IMAGE_TAG',
        text: 'services:\n  app:\n    image: registry.cn-hangzhou.aliyuncs.com/ns/app:${IMAGE_TAG}\n    container_name: app\n    ports:\n      - \"8080:8080\"\n    restart: always'
      },
      {
        label: '带中间件与数据卷（中间件不写占位符）',
        text: 'services:\n  app:\n    image: registry.cn-hangzhou.aliyuncs.com/ns/app:${IMAGE_TAG}\n    volumes:\n      - ./logs:/app/logs\n    depends_on:\n      - redis\n  redis:\n    image: redis:7-alpine'
      }
    ],
    showIf: { buildMode: ['PROJECT_DOCKERFILE', 'LAYERED_DOCKER', 'JAR_PREBUILD'] }
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
    key: 'rebuild_base_image',
    label: '默认重建基础镜像',
    type: 'switch',
    group: 'Docker 构建',
    span: 8,
    defaultValue: false,
    tip: '分层构建专属：默认关闭（跳过基础镜像重建，复用仓库中已存在的稳定基础镜像 {镜像仓库}:base，省时省带宽）。'
      + '基础层内容变更时，在「触发构建」弹窗里临时勾选「重新构建基础镜像」覆盖本默认值。'
      + '首次使用稳定 base 前必须勾选一次以创建它，否则复用会因仓库里没有该 tag 而失败',
    showIf: { buildMode: ['LAYERED_DOCKER'] }
  },
  {
    key: 'image_repository',
    label: '镜像仓库地址',
    type: 'input',
    group: 'Docker 构建',
    span: 12,
    placeholder: '不含 tag，如 registry.cn-hangzhou.aliyuncs.com/ns/app',
    tip: '留空时回落到项目绑定的镜像仓库（DOCKER 运行时消费：项目自带 / 分层 / JAR 预构建都要推镜像）',
    showIf: { buildMode: ['PROJECT_DOCKERFILE', 'LAYERED_DOCKER', 'JAR_PREBUILD'] }
  },
  {
    key: 'maven_settings_path',
    label: 'Maven settings 路径',
    type: 'input',
    group: 'Docker 构建',
    span: 12,
    placeholder: '相对仓库根，如 Docker/settings.xml，可不填',
    tip: '仅「JAR 预构建」使用：平台打 jar 时把该文件作为 mvn -s 参数，典型用途是切阿里云 Maven 镜像加速。'
      + '留空走 Maven 默认配置（公网 Maven Central，构建机在内网时极慢）',
    showIf: { buildMode: ['JAR_PREBUILD'] }
  },
  {
    key: 'docker_verify_on_build',
    label: '构建机启动级验证',
    type: 'switch',
    group: 'Docker 构建',
    span: 8,
    defaultValue: true,
    tip: '构建完成后、推送仓库前，先在构建机把镜像跑起来，确认「构建成功但一启动就崩」的缺陷镜像不会上推'
      + '（后台启动 + 逐秒 inspect 判活，不看退出码）。'
      + '只有「应用启动就必须连外部依赖、而构建机网络不通」的项目才关掉，改由目标机业务级验证兜底',
    showIf: { buildMode: ['PROJECT_DOCKERFILE', 'LAYERED_DOCKER', 'JAR_PREBUILD'] }
  },
  {
    key: 'docker_verify_timeout_seconds',
    label: '验证观察窗口（秒）',
    type: 'number',
    group: 'Docker 构建',
    span: 8,
    placeholder: '默认 30',
    tip: '窗口内容器持续运行即视为启动成功。太短会误判启动慢的应用，太长会拖慢每次构建；按应用冷启动耗时调整',
    showIf: { buildMode: ['PROJECT_DOCKERFILE', 'LAYERED_DOCKER', 'JAR_PREBUILD'] }
  }
]

/** 运行配置 schema（对应 project.runtime_config_json，消费方 DeployTaskHandler） */
export const RUNTIME_CONFIG_FIELDS: ConfigField[] = [
  {
    key: 'work_dir',
    label: '固定工作目录',
    type: 'input',
    group: 'JAR / Docker 运行时',
    span: 12,
    placeholder: '绝对路径，如 /lx/jarapps/auth-tool-7888',
    tip: '填了即完全覆盖服务器档案里的部署根目录；目录固定不变，发版原地替换 jar',
    // 覆盖关系与「相对路径陷阱」是这个字段最容易踩的两处，label 上的「?」悬浮给出完整解释
    examples: [
      {
        label: '填了 = 覆盖服务器上的部署根目录',
        text:
          'JAR 最终就发到这个目录，不再拼接服务器档案里的部署根目录。\n' +
          '填 /lx/jarapps/auth-tool-7888 → 工作目录就是 /lx/jarapps/auth-tool-7888。'
      },
      {
        label: '不填 = 回落到部署根目录下的 apps',
        text:
          '回落到 {部署根目录}/apps/{项目编码}。\n' +
          '部署根目录 /lx/light-deploy/agent + 项目编码 auth-tool-7888 → /lx/light-deploy/agent/apps/auth-tool-7888。'
      },
      {
        label: '必须写绝对路径',
        text:
          '平台按字面使用这个值，不拼接、不转换、不校验存在性。\n' +
          '写成 ./lx/jarapps/auth-tool-7888 这类相对路径，会相对 SSH 登录用户的家目录解析（root 就是 /root/lx/jarapps/...），不是你以为的 /lx/jarapps/...。'
      }
    ],
    checks: [
      {
        test: (values) => Boolean(values.work_dir) && !String(values.work_dir).startsWith('/'),
        message: '固定工作目录必须写绝对路径（以 / 开头）：相对路径会相对 SSH 登录用户的家目录解析，跟填的时候想的位置不一样'
      }
    ],
    showIf: { runtimeType: ['JAR', 'DOCKER'] }
  },
  {
    key: 'deploy_base',
    label: '部署根目录覆盖',
    type: 'input',
    group: 'JAR 运行时',
    span: 12,
    placeholder: '留空使用服务器档案的部署根目录 + /apps',
    tip: '当前版本后端未消费该键（读作服务器档案的部署根目录），留空即可；要指定目录请填「固定工作目录」',
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
    // 这一项最容易踩：它是项目级配置，会把同一条路径套到所有目标机上
    tip: '项目级配置，对所有目标机生效；各机器 JDK 路径不同请留空由平台按机器自动探测，或在具体部署目标上单独覆盖',
    examples: [
      {
        label: '为什么两台机器会一成功一失败',
        text:
          '填了绝对路径，就等于要求每台目标机上都得有这个文件。\n' +
          'A 机 JDK 在 /usr/lib/jdk1.8.0_121/bin/java、B 机在别处时，B 机的 systemd 单元里 ExecStart 指向不存在的路径，\n' +
          '服务以 status=203/EXEC 起不来，而单元只在首次部署生成、之后不会因配置变更重写。'
      },
      {
        label: '推荐：留空，让平台逐台探测',
        text:
          '留空时平台在每台目标机上执行 command -v java，各自用自己 PATH 里的 java。\n' +
          '只有某台机器的 java 不在 PATH 里时，才在「部署目标」那条记录的运行配置里单独填 java_bin。'
      },
      {
        label: '已生成的服务不会自动改',
        text:
          '改这个配置不会重写目标机上已存在的 systemd 单元。\n' +
          '已经发过的机器要改 java 路径，得上机改 /etc/systemd/system/{服务名}.service 后 daemon-reload 重启。'
      }
    ],
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
    key: 'deploy_form',
    label: '部署形态',
    type: 'select',
    group: 'Docker 运行时',
    span: 8,
    options: [
      { value: 'RUN', label: 'docker run（单容器）' },
      { value: 'COMPOSE', label: 'docker compose（用项目的 compose 文件）' }
    ],
    placeholder: '默认 RUN',
    tip: 'RUN：由平台拼 docker run 命令，端口映射与容器名用本页配置。'
      + 'COMPOSE：用构建配置里快照的 compose 文件启动，端口、卷、网络、服务依赖完全由项目文件决定，'
      + '平台只注入镜像版本号 —— 多容器或编排较复杂的项目应该选它。'
      + '选 COMPOSE 后「端口映射」「附加 docker 参数」「容器名」不再生效，这些是 docker run 的参数',
    showIf: { runtimeType: ['DOCKER'] }
  },
  {
    key: 'container_name',
    label: '容器名',
    type: 'input',
    group: 'Docker 运行时',
    span: 8,
    placeholder: '默认取项目编码',
    tip: '仅 RUN 形态生效：COMPOSE 形态下容器名由项目自己的 compose 文件决定',
    showIf: { runtimeType: ['DOCKER'] }
  },
  {
    key: 'port_mappings',
    label: '端口映射',
    type: 'input',
    group: 'Docker 运行时',
    span: 8,
    placeholder: '如 8080:8080，多个用英文逗号分隔',
    tip: '留空表示不映射端口；仅 RUN 形态生效，COMPOSE 形态请在 compose 文件里写 ports',
    showIf: { runtimeType: ['DOCKER'] }
  },
  {
    key: 'extra_docker_args',
    label: '附加 docker 参数',
    type: 'input',
    group: 'Docker 运行时',
    span: 8,
    placeholder: '如 -e KEY=VALUE --network xxx',
    tip: '原样拼到 docker run 命令行；仅 RUN 形态生效',
    showIf: { runtimeType: ['DOCKER'] }
  },
  {
    key: 'static_root',
    label: 'Nginx 根目录',
    type: 'input',
    group: '静态产物',
    span: 12,
    placeholder: '默认 /usr/share/nginx/html/{项目编码}',
    tip: '填到「站点文件所在的那一层」：该目录里应直接是 index.html 等站点文件，不要再多套一层 dist/。'
      + '平台发布时只替换这个目录里面的文件，目录本身不删不换（若该目录被 Docker bind mount 进容器，'
      + '换目录会让容器里的挂载失效、全站 403，且只能重建容器恢复）。'
      + '压缩包若多包了一层（解开是 aiops/index.html），平台会自动剥离那一层，这里填什么不受影响。',
    showIf: { runtimeType: ['STATIC'] },
    examples: [
      {
        label: '填法：nginx 站点根 + 项目目录（最常用的真实场景）',
        text: '宿主机 nginx 根：/lx/docker/lx-nginx/www/html/\n'
          + 'aiops 前端项目：/lx/docker/lx-nginx/www/html/aiops   ← 填这一整行\n\n'
          + '不要把 nginx 根当成这里的值，否则发布会把该目录下别的站点内容一起搬走。'
      },
      {
        label: '该目录里应该长什么样（直接放站点文件，不多套一层）',
        text: '/lx/docker/lx-nginx/www/html/aiops\n'
          + '├── index.html\n'
          + '├── favicon.svg\n'
          + '└── static/          ← 前端打包产物的 assets 目录\n\n'
          + '若你上传的压缩包解开是 aiops/dist/index.html，则应填 .../aiops/dist。'
      },
      {
        label: '留空时的默认值',
        text: '/usr/share/nginx/html/{项目编码}\n\n'
          + '只在你的 nginx 确实按这个约定配 root 时才适用，否则必须显式填写。'
      }
    ]
  },
  {
    key: 'static_backup_dir',
    label: '静态产物备份目录',
    type: 'input',
    group: '静态产物',
    span: 12,
    placeholder: '默认 {Nginx 根目录的父目录}/static-bak',
    tip: '发布前把静态目录现有内容整体搬到这里（目录本身不动），回滚优先从这里还原，不走构建机。'
      + '必须与 Nginx 根目录在同一挂载分区，否则搬迁会退化成跨分区复制、切换窗口变长。'
      + '改动 static_root 会让默认备份目录随之变化，届时历史备份不再命中快路径（会改走构建机）。'
      + '目录名不以点开头，终端 ls 能直接看到，翻备份和清磁盘都方便。',
    showIf: { runtimeType: ['STATIC'] },
    examples: [
      {
        label: '默认位置（可用，但在 nginx 站点根的同级，见下方风险）',
        text: 'Nginx 根目录：/lx/docker/lx-nginx/www/html/aiops\n'
          + '默认备份：    /lx/docker/lx-nginx/www/html/static-bak'
      },
      {
        label: '建议：显式配到 web 根之外（强烈推荐）',
        text: '/lx/docker/lx-nginx/backup/aiops\n\n'
          + '默认位置落在 nginx 可访问范围内，http://host/static-bak/ 能取到历史版本，\n'
          + '其中可能含 source map，等于把源码结构暴露出去。\n'
          + '配到 web 根外即彻底规避；若只能留在 web 根内，在 nginx 加一条\n'
          + 'location ^~ /static-bak/ { deny all; } 兜底。'
      }
    ]
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
    tip: 'HTTP 2xx 视为健康，能反映应用是否真的可用；TCP 端口可连通即健康，只证明进程在监听、探不出假死；整段留空则跳过健康检查'
  },
  {
    key: 'probe_from',
    label: '探测位置',
    type: 'select',
    group: '探测方式',
    span: 8,
    options: [
      { value: 'TARGET', label: '目标机本地（默认）' },
      { value: 'PLATFORM', label: '平台侧' }
    ],
    placeholder: '默认 TARGET',
    tip: 'TARGET：平台 SSH 登录目标机检查端口是否监听，结果不受云防火墙/安全组影响，业务端口无需对平台放行；PLATFORM：从平台所在机器直连 host:port，验证外部可达性，要求平台能访问该端口，否则应用正常也会被判失败'
  },
  {
    key: 'port',
    label: '探测端口',
    type: 'number',
    group: '探测方式',
    span: 8,
    required: false,
    placeholder: '业务端口，如 8080',
    tip: '探测默认在目标机本地发起（TARGET），平台无需访问该端口；只有探测位置选了 PLATFORM 才要求平台能直达 host:port'
  },
  {
    key: 'path',
    label: 'HTTP 路径',
    type: 'input',
    group: '探测方式',
    span: 8,
    placeholder: '默认 /，仅 HTTP 探测使用',
    showIf: { type: ['HTTP'] },
    tip: '必须是免鉴权、直接返回 200 的接口；平台不跟随重定向，302/401/403 一律判为不健康，不要填 / 这类会跳登录页的路径'
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

// ==================== JSON 模式示例（按当前运行时 / 构建模式动态生成） ====================

/**
 * 字段在当前联动上下文下是否可见（与 ConfigEditor 的 isFieldVisible 同一份 showIf 语义）。
 *
 * @param field 字段 schema
 * @param context 联动上下文（runtimeType / buildMode / type 等）
 */
export function isFieldVisibleByContext(field: ConfigField, context: Record<string, string> = {}): boolean {
  if (!field.showIf) return true
  return Object.entries(field.showIf).every(([ctxKey, allowed]) => {
    const current = context[ctxKey]
    return current !== undefined && allowed.includes(current)
  })
}

/** 从文本里取第一个整数（用于 number 字段的默认值示例） */
function extractFirstNumber(text?: string): number | null {
  if (!text) return null
  const m = text.match(/-?\d+/)
  return m ? Number(m[0]) : null
}

/**
 * 为单个字段挑一个「可直接抄」的示例值。
 *
 * 优先级：select 取首个选项；input 优先取 placeholder 里的「如 …」片段，其次取「默认 …」片段
 * （去掉大括号与「取 / 留空 / 等于」这类非值措辞）；number 取 placeholder 里的首个整数。
 * 拿不到干净值的字段返回 skip，由调用方排除——这类字段服务端有默认值，不要求用户抄写。
 */
function sampleValue(field: ConfigField): { skip?: boolean; value?: unknown } {
  if (field.type === 'switch') {
    // 开关类字段输出真布尔值；没有声明默认值的开关不进示例，避免给出「看不出该填什么」的示例
    return field.defaultValue === undefined ? { skip: true } : { value: field.defaultValue }
  }
  if (field.type === 'select') {
    return field.options?.length ? { value: field.options[0].value } : { skip: true }
  }
  if (field.type === 'number') {
    const n = extractFirstNumber(field.placeholder)
    return n !== null ? { value: n } : { skip: true }
  }
  const ph = field.placeholder || ''
  const ru = ph.match(/如\s*([^，。\n]+)/)
  if (ru) return { value: ru[1].trim() }
  const mr = ph.match(/默认\s+([^{，。\n]+)/)
  if (mr) {
    const v = mr[1].trim()
    if (!v.includes('{') && !/[取留空等于]/.test(v)) return { value: v }
  }
  return { skip: true }
}

/**
 * 按当前联动上下文，为 JSON 模式生成「只有本运行时 / 构建模式相关字段」的示例。
 *
 * 不再写死一份混合所有运行时的 blob（那样 DOCKER 项目切到 JSON 会看到 JAR / STATIC 的键，
 * 误导填写）。字段可见性判定与 ConfigEditor 完全一致，示例值随 visibleFields 取 sampleValue。
 * 无可见字段时返回空串，文本域不塞模板。
 *
 * @param fields 字段 schema
 * @param context 联动上下文（runtimeType / buildMode / type 等），由父页面传入或表单内已填
 * @returns 格式化的 JSON 字符串
 */
export function generateExample(fields: ConfigField[], context: Record<string, string> = {}): string {
  const sample: Record<string, unknown> = {}
  for (const field of fields) {
    if (!isFieldVisibleByContext(field, context)) continue
    const v = sampleValue(field)
    if (!v.skip) sample[field.key] = v.value
  }
  return Object.keys(sample).length > 0 ? JSON.stringify(sample, null, 2) : ''
}
