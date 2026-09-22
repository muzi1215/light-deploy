<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="项目编码">
          <el-input v-model="searchForm.code" placeholder="请输入项目编码" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="项目名称">
          <el-input v-model="searchForm.name" placeholder="请输入项目名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="运行时">
          <el-select v-model="searchForm.runtimeType" placeholder="请选择" clearable style="width: 160px">
            <el-option label="Docker容器" value="DOCKER" />
            <el-option label="Java JAR" value="JAR" />
            <el-option label="静态产物" value="STATIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="构建模式">
          <el-select v-model="searchForm.buildMode" placeholder="请选择" clearable style="width: 160px">
            <!-- 选项取自 BUILD_MODE_OPTIONS，与新增/编辑表单共用同一份取值，避免两处枚举漂移 -->
            <el-option v-for="item in BUILD_MODE_OPTIONS" :key="item.value" :label="item.shortLabel" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 160px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 工具栏 -->
    <div class="tools-div">
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增项目</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="code" label="项目编码" min-width="170" show-overflow-tooltip align="center" />
      <el-table-column prop="name" label="项目名称" min-width="160" show-overflow-tooltip align="center" />
      <el-table-column prop="runtimeType" label="运行时" min-width="110" align="center">
        <template #default="{ row }">
          <el-tag>{{ RUNTIME_TYPE_MAP[row.runtimeType] || row.runtimeType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceType" label="代码来源" min-width="100" align="center">
        <template #default="{ row }">
          {{ row.sourceType === 'GIT' ? 'Git仓库' : '本地上传' }}
        </template>
      </el-table-column>
      <el-table-column prop="buildMode" label="构建模式" min-width="130" align="center">
        <template #default="{ row }">
          {{ BUILD_MODE_MAP[row.buildMode] || row.buildMode }}
        </template>
      </el-table-column>
      <el-table-column prop="gitBranch" label="Git分支" min-width="120" show-overflow-tooltip align="center" />
      <el-table-column prop="retainReleaseCount" label="保留版本" min-width="90" align="center" />
      <el-table-column prop="status" label="状态" min-width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="200" align="center" />
      <el-table-column prop="description" label="描述" min-width="150" align="center" show-overflow-tooltip />
      <el-table-column label="操作" min-width="260" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
          <el-button link type="danger" :icon="Delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-model:current-page="pagination.pageNum"
      v-model:page-size="pagination.pageSize"
      :total="pagination.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top:16px;justify-content:flex-end"
      @size-change="fetchData"
      @current-change="fetchData"
    />

    <!-- 新增/编辑对话框：统一 800px + label 120px + 两列布局，文本域统一放表单底部（约定见 AGENTS.md §20.2） -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="800px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <!-- 内容区滚动容器：顶部与标题栏留出间隔；左右 padding 抵消 el-row gutter 负边距并预留滚动条宽度 -->
      <div style="max-height: 60vh; overflow-y: auto; padding: 16px 18px 0 10px;">
        <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px" size="small">
          <!-- 第一行：项目编码 + 项目名称 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="项目编码" prop="code">
                <el-input v-model="formData.code" placeholder="字母、数字、下划线和中划线，2-64位" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="项目名称" prop="name">
                <el-input v-model="formData.name" placeholder="请输入项目名称" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第二行：保留版本数 + 构建模式 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="保留版本数" prop="retainReleaseCount">
                <el-input-number v-model="formData.retainReleaseCount" :min="1" :max="100" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="构建模式" prop="buildMode">
                <el-select v-model="formData.buildMode" placeholder="请选择" style="width: 100%">
                  <!-- 选项随运行时变化：Docker 类构建模式产出镜像，脚本 / 仅发布产物产出文件，不可交叉（AGENTS.md §23.4） -->
                  <el-option v-for="item in buildModeOptions" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第三行：运行时（三个选项，独占整行） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="运行时" prop="runtimeType">
                <el-radio-group v-model="formData.runtimeType" @change="handleRuntimeTypeChange">
                  <el-radio value="DOCKER">Docker容器</el-radio>
                  <el-radio value="JAR">Java JAR</el-radio>
                  <el-radio value="STATIC">静态产物</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第四行：代码来源 + 启用状态 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="代码来源" prop="sourceType">
                <el-radio-group v-model="formData.sourceType">
                  <el-radio value="GIT">Git仓库</el-radio>
                  <el-radio value="UPLOAD">本地上传</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="状态" prop="status">
                <el-radio-group v-model="formData.status">
                  <el-radio :value="1">启用</el-radio>
                  <el-radio :value="0">停用</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第五、六行：Git 仓库信息，条件字段紧跟控制它的"代码来源"，仅在 GIT 来源下出现 -->
          <template v-if="formData.sourceType === 'GIT'">
            <!-- 第五行：仓库地址独占整行，地址较长，半行放不下 -->
            <el-row :gutter="20">
              <el-col :span="24">
                <el-form-item label="Git仓库地址" prop="gitUrl">
                  <el-input v-model="formData.gitUrl" placeholder="https://gitee.com/group/repo.git" style="width: 100%" />
                </el-form-item>
              </el-col>
            </el-row>
            <!-- 第六行：分支 + 凭据 ID（凭据只存外键引用，不含凭据内容） -->
            <el-row :gutter="20">
              <el-col :span="12">
                <el-form-item label="Git分支" prop="gitBranch">
                  <el-input v-model="formData.gitBranch" placeholder="默认分支，如 master（选填）" style="width: 100%" />
                </el-form-item>
              </el-col>
              <el-col :span="12">
                <el-form-item label="Git凭据" prop="gitCredentialId">
                  <el-select v-model="formData.gitCredentialId" placeholder="私有仓库凭据（选填）" clearable style="width: 100%">
                    <el-option
                      v-for="item in gitCredentials"
                      :key="item.id"
                      :label="`${item.name}（${CREDENTIAL_TYPE_MAP[item.type] || item.type}）`"
                      :value="item.id"
                    />
                  </el-select>
                </el-form-item>
              </el-col>
            </el-row>
          </template>

          <!-- 第六行补充：镜像仓库绑定，仅 DOCKER 运行时出现（脚本部署与仅发布产物不产出镜像） -->
          <el-row v-if="formData.runtimeType === 'DOCKER'" :gutter="20">
            <el-col :span="24">
              <el-form-item label="镜像仓库" prop="registryId">
                <el-select v-model="formData.registryId" placeholder="选择镜像推送目标（选填）" clearable style="width: 100%">
                  <el-option
                    v-for="item in registries"
                    :key="item.id"
                    :label="item.namespace ? `${item.name}（${item.registryUrl}/${item.namespace}）` : `${item.name}（${item.registryUrl}）`"
                    :value="item.id"
                  />
                </el-select>
                <span class="form-tip">构建出的镜像推送到该仓库；只在「运行时 = Docker容器」时可配置（AGENTS.md §23.4）</span>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第七至九行：三段 JSON 高级配置（文本域统一放表单底部，键名 schema 见 docs/项目模块.md §2.3） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <!-- label 上的 ? 图标悬浮展示完整示例，与 placeholder 共用同一个常量 -->
              <el-form-item prop="buildConfigJson">
                <template #label>
                  <span>构建配置</span>
                  <el-tooltip placement="top" effect="dark" :show-after="100">
                    <template #content>
                      <pre class="tooltip-json">{{ BUILD_CONFIG_EXAMPLE }}</pre>
                    </template>
                    <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-input v-model="formData.buildConfigJson" type="textarea" :rows="4" :placeholder="BUILD_CONFIG_EXAMPLE" />
                <span class="form-tip">构建模块消费：多模块仓库用它指定子模块与构建命令，单模块仓库可留空（选填，须为合法 JSON）</span>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item prop="runtimeConfigJson">
                <template #label>
                  <span>运行配置</span>
                  <el-tooltip placement="top" effect="dark" :show-after="100">
                    <template #content>
                      <pre class="tooltip-json">{{ RUNTIME_CONFIG_EXAMPLE }}</pre>
                    </template>
                    <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-input v-model="formData.runtimeConfigJson" type="textarea" :rows="4" :placeholder="RUNTIME_CONFIG_EXAMPLE" />
                <span class="form-tip">发布模块消费：部署目录、端口、环境变量、启动命令等，按运行时类型取用（选填，须为合法 JSON）</span>
              </el-form-item>
            </el-col>
          </el-row>
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item prop="healthConfigJson">
                <template #label>
                  <span>健康检查配置</span>
                  <el-tooltip placement="top" effect="dark" :show-after="100">
                    <template #content>
                      <pre class="tooltip-json">{{ HEALTH_CONFIG_EXAMPLE }}</pre>
                    </template>
                    <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-input v-model="formData.healthConfigJson" type="textarea" :rows="3" :placeholder="HEALTH_CONFIG_EXAMPLE" />
                <span class="form-tip">健康检查消费：探活方式、端口、路径、间隔、超时、重试（选填，须为合法 JSON；不填按 TCP 端口探活兜底）</span>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第十行：项目描述（文本域，固定放表单最底部） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="项目描述" prop="description">
                <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="项目用途说明（选填）" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
      <template #footer>
        <el-button size="small" @click="dialogVisible = false">取 消</el-button>
        <el-button type="primary" size="small" :loading="submitLoading" @click="handleSubmit">确 定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目管理页面。
 *
 * 对接后端 /project 标准六接口。业务约定：
 * 1. 项目编码创建后不允许修改（编辑时输入框禁用），由后端做唯一性校验；
 * 2. GIT 来源必须填写 Git 仓库地址（后端强制校验，表单联动显示 Git 字段）；
 * 3. 一个 project = 一个可部署的服务模块：同一 Git 地址分支下允许存在多条记录，
 *    多模块仓库靠 buildConfigJson.module_name 区分（见 docs/项目模块.md §2.3.1）；
 * 4. 构建配置/运行配置/健康检查三段 JSON 为高级配置，表单提供原始文本编辑入口，
 *    前端只镜像后端做 JSON 语法校验，键名 schema 见 docs/项目模块.md §2.3。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, QuestionFilled } from '@element-plus/icons-vue'
import { pageProject, createProject, updateProject, deleteProject } from '@/api/project.ts'
import { listCredentials } from '@/api/credential.ts'
import { listRegistries } from '@/api/registry.ts'
import type { ProjectVo, ProjectDto, CredentialVo, RegistryVo } from '@/api/deployTypes.ts'
import { CREDENTIAL_TYPE_MAP, GIT_CREDENTIAL_TYPES } from '@/constants/credentialType.ts'

defineOptions({ name: 'ProjectList' })

/** 运行时展示映射（与后端 RuntimeType 枚举一致） */
const RUNTIME_TYPE_MAP: Record<string, string> = {
  DOCKER: 'Docker容器',
  JAR: 'Java JAR',
  STATIC: '静态产物'
}

/**
 * 构建模式选项（与后端 BuildMode 枚举一一对应，枚举变更时必须同步本数组）。
 *
 * label 用于新增/编辑表单（空间充足，展示完整名称）；
 * shortLabel 用于搜索下拉与列表列（宽度只有 160px / 130px，必须用短名）；
 * runtimes 是该构建模式允许的运行时，镜像自后端 RuntimeType.supports()：Docker 类构建模式
 * 产出镜像、只配 DOCKER，脚本 / 仅发布产物产出文件、只配 JAR 与 STATIC（AGENTS.md §23.4）。
 */
const BUILD_MODE_OPTIONS: { value: string; label: string; shortLabel: string; runtimes: string[] }[] = [
  { value: 'PLATFORM_TEMPLATE', label: '平台 Dockerfile 模板', shortLabel: '平台模板', runtimes: ['DOCKER'] },
  { value: 'PROJECT_DOCKERFILE', label: '项目自带 Dockerfile', shortLabel: '项目Dockerfile', runtimes: ['DOCKER'] },
  { value: 'LAYERED_DOCKER', label: '分层构建（base + app）', shortLabel: '分层构建', runtimes: ['DOCKER'] },
  { value: 'SCRIPT_DEPLOY', label: '脚本部署', shortLabel: '脚本部署', runtimes: ['JAR', 'STATIC'] },
  { value: 'ARTIFACT_ONLY', label: '仅发布已有产物', shortLabel: '仅发布产物', runtimes: ['JAR', 'STATIC'] }
]

/**
 * 按运行时过滤后的构建模式选项（表单下拉使用）。
 *
 * 未选运行时时返回全部选项，避免出现空下拉。
 */
const buildModeOptions = computed(() => {
  // 取到局部变量再做过滤：reactive 对象的字段在回调里无法保持类型收窄
  const runtimeType = formData.runtimeType
  if (!runtimeType) {
    return BUILD_MODE_OPTIONS
  }
  return BUILD_MODE_OPTIONS.filter((item) => item.runtimes.includes(runtimeType))
})

/** 构建模式展示映射，从 BUILD_MODE_OPTIONS 派生，用于列表列渲染 */
const BUILD_MODE_MAP: Record<string, string> = Object.fromEntries(
  BUILD_MODE_OPTIONS.map((item) => [item.value, item.shortLabel])
)

// ==================== 三段 JSON 配置的示例文案 ====================
// 同时用于输入框 placeholder 与 label 上「?」图标的悬浮提示，保证示例只有一份来源（AGENTS.md §20.2）。
// 键名 schema 以 docs/项目模块.md §2.3 为准，改动示例必须同步该文档。

/** 构建配置示例（多模块仓库：同一仓库下只构建 auth-tool 子模块） */
const BUILD_CONFIG_EXAMPLE = `{
  "module_name": "auth-tool",
  "module_dir": "auth-tool",
  "build_command": "mvn clean package -pl auth-tool -am -DskipTests",
  "dockerfile_path": "auth-tool/Dockerfile",
  "artifact_path": "auth-tool/target/auth-tool.jar"
}`

/** 运行配置示例（发布模块消费，按运行时类型取对应键） */
const RUNTIME_CONFIG_EXAMPLE = `{
  "deploy_dir": "/opt/apps/auth-tool",
  "container_name": "auth-tool",
  "ports": ["8080:8080"],
  "env": { "SPRING_PROFILES_ACTIVE": "prod" },
  "restart_policy": "unless-stopped"
}`

/** 健康检查配置示例（不配置时按 TCP 端口探活兜底） */
const HEALTH_CONFIG_EXAMPLE = `{
  "type": "HTTP",
  "port": 8080,
  "path": "/actuator/health",
  "expected_status": 200,
  "interval_seconds": 10,
  "timeout_seconds": 3,
  "retries": 3,
  "start_period_seconds": 30
}`

// ==================== 下拉数据源 ====================
/** Git 凭据下拉数据，只含类型为 GIT_TOKEN / SSH_KEY 的凭据（与后端 Git 类型校验一致） */
const gitCredentials = ref<CredentialVo[]>([])

/** 镜像仓库下拉数据，只含启用中的仓库（停用仓库不允许被新项目绑定） */
const registries = ref<RegistryVo[]>([])

/**
 * 加载表单下拉需要的外部资源。
 *
 * 凭据与仓库都属于「系统设置」下的全局资源，本页面只读取列表用于下拉选择：
 * 凭据不返回密文，仓库不返回凭据内容，因此这里不存在敏感信息泄漏面。
 */
async function fetchFormOptions() {
  // 凭据接口支持按类型过滤，这里取全量后在前端按 Git 类型筛选，避免发两次请求
  const credentialRes = await listCredentials()
  gitCredentials.value = credentialRes.data.filter((item) => GIT_CREDENTIAL_TYPES.includes(item.type))
  // 仓库下拉只列启用中的仓库
  const registryRes = await listRegistries({ status: 1 })
  registries.value = registryRes.data
}

fetchFormOptions()

// ==================== 搜索 ====================
const searchForm = reactive({
  code: '',
  name: '',
  runtimeType: '',
  buildMode: '',
  status: undefined as number | undefined
})

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.code = ''
  searchForm.name = ''
  searchForm.runtimeType = ''
  searchForm.buildMode = ''
  searchForm.status = undefined
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<ProjectVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    const res = await pageProject({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      code: searchForm.code || undefined,
      name: searchForm.name || undefined,
      runtimeType: searchForm.runtimeType || undefined,
      buildMode: searchForm.buildMode || undefined,
      status: searchForm.status
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const submitLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const editId = ref('')

const formData = reactive<ProjectDto>({
  code: '',
  name: '',
  runtimeType: 'DOCKER',
  sourceType: 'GIT',
  gitUrl: '',
  gitBranch: '',
  gitCredentialId: '',
  buildMode: 'PLATFORM_TEMPLATE',
  registryId: '',
  buildConfigJson: '',
  runtimeConfigJson: '',
  healthConfigJson: '',
  retainReleaseCount: 5,
  status: 1,
  description: ''
})

/**
 * 校验文本是否为合法 JSON。
 *
 * 与后端 ProjectService.validateJsonFormat 规则一致：空白视为未配置直接放行，
 * 否则必须是合法 JSON；前端只校验语法，不校验内部键名（语义由下游模块负责）。
 *
 * @param label 字段中文名，用于错误提示
 * @returns Element Plus 表单校验器
 */
function buildJsonValidator(label: string) {
  return (_rule: any, value: string, callback: (err?: Error) => void) => {
    if (!value) {
      callback()
      return
    }
    try {
      JSON.parse(value)
      callback()
    } catch {
      callback(new Error(`${label}不是合法的 JSON 格式`))
    }
  }
}

const formRules: FormRules = {
  code: [
    { required: true, message: '请输入项目编码', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_-]{2,64}$/, message: '只允许字母、数字、下划线和中划线，2-64位', trigger: 'blur' }
  ],
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  runtimeType: [{ required: true, message: '请选择运行时', trigger: 'change' }],
  // GIT 来源必须填写仓库地址，与后端校验规则一致
  gitUrl: [{
    validator: (_rule: any, value: string, callback: (err?: Error) => void) => {
      if (formData.sourceType === 'GIT' && !value) {
        callback(new Error('Git 来源项目必须填写 Git 仓库地址'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }],
  // 三段 JSON 高级配置：只做语法校验，与后端一致
  buildConfigJson: [{ validator: buildJsonValidator('构建配置'), trigger: 'blur' }],
  runtimeConfigJson: [{ validator: buildJsonValidator('运行配置'), trigger: 'blur' }],
  healthConfigJson: [{ validator: buildJsonValidator('健康检查配置'), trigger: 'blur' }]
}

const dialogTitle = computed(() => (isEdit.value ? '编辑项目' : '新增项目'))

function handleAdd() {
  isEdit.value = false
  dialogVisible.value = true
}

/**
 * 运行时切换时校正构建模式与镜像仓库绑定。
 *
 * 两个字段都受运行时约束（AGENTS.md §23.4）：
 * 1. 当前构建模式不被新运行时支持时，自动切到该运行时的第一个可选模式，避免提交必然失败的组合；
 * 2. 运行时不是 DOCKER 时清空镜像仓库绑定，因为脚本 / 仅发布产物的项目没有镜像推送目标。
 *
 * @param value 切换后的运行时编码（DOCKER / JAR / STATIC），由 el-radio-group 的 change 事件传入
 */
function handleRuntimeTypeChange(value: string | number | boolean | undefined) {
  const runtimeType = String(value ?? '')
  if (!BUILD_MODE_OPTIONS.find((item) => item.value === formData.buildMode)?.runtimes.includes(runtimeType)) {
    formData.buildMode = BUILD_MODE_OPTIONS.find((item) => item.runtimes.includes(runtimeType))?.value || ''
  }
  if (runtimeType !== 'DOCKER') {
    formData.registryId = ''
  }
}

/**
 * 打开编辑对话框并回填字段。
 *
 * @param row 待编辑的项目行数据
 */
function handleEdit(row: ProjectVo) {
  isEdit.value = true
  editId.value = row.id
  formData.code = row.code
  formData.name = row.name
  formData.runtimeType = row.runtimeType
  formData.sourceType = row.sourceType
  formData.gitUrl = row.gitUrl || ''
  formData.gitBranch = row.gitBranch || ''
  formData.gitCredentialId = row.gitCredentialId || ''
  formData.buildMode = row.buildMode
  formData.registryId = row.registryId || ''
  formData.buildConfigJson = row.buildConfigJson || ''
  formData.runtimeConfigJson = row.runtimeConfigJson || ''
  formData.healthConfigJson = row.healthConfigJson || ''
  formData.retainReleaseCount = row.retainReleaseCount
  formData.status = row.status
  formData.description = row.description || ''
  dialogVisible.value = true
}

function resetForm() {
  formData.code = ''
  formData.name = ''
  formData.runtimeType = 'DOCKER'
  formData.sourceType = 'GIT'
  formData.gitUrl = ''
  formData.gitBranch = ''
  formData.gitCredentialId = ''
  formData.buildMode = 'PLATFORM_TEMPLATE'
  formData.registryId = ''
  formData.buildConfigJson = ''
  formData.runtimeConfigJson = ''
  formData.healthConfigJson = ''
  formData.retainReleaseCount = 5
  formData.status = 1
  formData.description = ''
  editId.value = ''
  formRef.value?.resetFields()
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    const payload = buildSubmitPayload()
    if (isEdit.value) {
      // 后端修改接口同样校验编码必填且做唯一性预检（排除当前记录自身）
      await updateProject(editId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await createProject(payload)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    submitLoading.value = false
  }
}

/**
 * 组装提交给后端的请求体。
 *
 * 文本域（Git 分支、Git 凭据 ID、三段 JSON、项目描述）未填写时统一提交 undefined，
 * 避免把空串写进后端 JSON 列；后端 ProjectService 仍会把空白值归一为 null 兜底。
 *
 * @returns 与后端 ProjectDto 字段一一对应的请求对象
 */
function buildSubmitPayload(): ProjectDto {
  return {
    code: formData.code,
    name: formData.name,
    runtimeType: formData.runtimeType,
    sourceType: formData.sourceType,
    // 仅 GIT 来源提交仓库信息，UPLOAD 来源保持为空
    gitUrl: formData.sourceType === 'GIT' ? formData.gitUrl || undefined : undefined,
    gitBranch: formData.sourceType === 'GIT' ? formData.gitBranch || undefined : undefined,
    gitCredentialId: formData.sourceType === 'GIT' ? formData.gitCredentialId || undefined : undefined,
    buildMode: formData.buildMode,
    // 只有 DOCKER 运行时提交镜像仓库绑定；其它运行时即使残留值也不提交（后端同样会拒绝）
    registryId: formData.runtimeType === 'DOCKER' ? formData.registryId || undefined : undefined,
    buildConfigJson: formData.buildConfigJson || undefined,
    runtimeConfigJson: formData.runtimeConfigJson || undefined,
    healthConfigJson: formData.healthConfigJson || undefined,
    retainReleaseCount: formData.retainReleaseCount,
    status: formData.status,
    description: formData.description || undefined
  }
}

// ==================== 删除 ====================
/**
 * 删除项目（逻辑删除）。
 *
 * @param row 待删除的项目行数据；仍被部署目标引用时后端返回 DATA_CONFLICT
 */
async function handleDelete(row: ProjectVo) {
  // 后端在项目仍被部署目标引用时返回 DATA_CONFLICT，request 层会提示具体原因
  await ElMessageBox.confirm(`确定要删除项目「${row.name}」吗？若项目已绑定部署目标，需先删除或迁移部署目标。`, '提示', { type: 'warning' })
  await deleteProject(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
</style>
