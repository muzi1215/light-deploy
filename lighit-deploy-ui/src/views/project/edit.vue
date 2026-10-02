<template>
  <div class="app-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span>{{ isEdit ? '编辑项目' : '新增项目' }}</span>
          <el-button size="small" :icon="Back" @click="goBack">返 回</el-button>
        </div>
      </template>

      <!-- 异步回填完成前不渲染表单：避免 ConfigEditor 先按空值初始化、回填时被打断 -->
      <el-form
        v-if="loaded"
        ref="formRef"
        :model="formData"
        :rules="formRules"
        label-width="120px"
        size="small"
      >
        <!-- 第一行：项目编码 + 项目名称 -->
        <el-row :gutter="20">
          <el-col :span="12">
            <el-form-item label="项目编码" prop="code">
              <!-- 编码用于产物目录、systemd 服务名与容器名：已产生构建 / 发布记录的项目不允许修改，避免历史产物失联 -->
              <el-input
                v-model="formData.code"
                :disabled="!codeEditable"
                placeholder="字母、数字、下划线和中划线，2-64位"
                style="width: 100%"
              />
              <span v-if="codeDisabledTip" class="form-tip">{{ codeDisabledTip }}</span>
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

        <!-- 第三行：运行时 -->
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item label="运行时" prop="runtimeType">
              <el-radio-group v-model="formData.runtimeType" @change="handleRuntimeTypeChange">
                <el-radio v-for="item in RUNTIME_TYPE_OPTIONS" :key="item.value" :value="item.value">{{ item.label }}</el-radio>
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

        <!-- Git 仓库信息：仅在 GIT 来源下出现 -->
        <template v-if="formData.sourceType === 'GIT'">
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="Git仓库地址" prop="gitUrl">
                <el-input v-model="formData.gitUrl" placeholder="https://gitee.com/group/repo.git" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>
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

        <!-- 镜像仓库绑定，仅 DOCKER 运行时出现 -->
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

        <!-- 构建机：构建与连通性测试的执行位置，选填（留空回落到系统默认构建机） -->
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item label="构建机" prop="buildServerId">
              <el-select v-model="formData.buildServerId" placeholder="选择构建机（选填）" clearable style="width: 100%">
                <el-option
                  v-for="s in servers"
                  :key="s.id"
                  :label="`${s.serverName}（${s.host}）`"
                  :value="s.id"
                />
              </el-select>
              <span class="form-tip">
                构建与「连通性测试」都在这台机器上执行，两者必须是同一台，否则测出来的结论说明不了构建能否成功；
                留空表示构建时回落到系统默认构建机，但连通性测试必须先绑定才能发起
              </span>
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">高级配置（表单 / JSON 双模式）</el-divider>

        <!-- 三段配置双模式编辑器：schema 见 constants/projectConfig.ts，键名契约见 docs/项目模块.md §2.3 -->
        <!-- 仅发布产物（ARTIFACT_ONLY）不做构建：产物由上传通道登记，构建参数全部无意义，
             整段折叠而不是渲染一张空表单（后者会让人以为漏填了什么）。 -->
        <ConfigEditor
          ref="buildEditorRef"
          v-model="buildConfigJson"
          title="构建配置"
          :fields="BUILD_CONFIG_FIELDS"
          :context="editorContext"
          :applicable="!isArtifactOnly"
          not-applicable-text="构建模式为「仅发布已有产物」时不执行构建，无需填写构建参数"
          hint="表单模式适用于常用字段修改；自定义键或整段粘贴请用 JSON 模式"
        />
        <!-- 运行配置与健康检查在仅发布产物模式下依然生效：
             前者决定产物放到哪、后者决定发布后怎么确认服务活着，都与是否有构建步骤无关。 -->
        <ConfigEditor
          ref="runtimeEditorRef"
          v-model="runtimeConfigJson"
          title="运行配置"
          :fields="RUNTIME_CONFIG_FIELDS"
          :context="editorContext"
          hint="表单模式适用于常用字段修改；自定义键或整段粘贴请用 JSON 模式"
        />
        <ConfigEditor
          ref="healthEditorRef"
          v-model="healthConfigJson"
          title="健康检查配置"
          :fields="HEALTH_CONFIG_FIELDS"
          :context="editorContext"
          hint="留空表示不检查；表单模式适用于常用字段修改，自定义键或整段粘贴请用 JSON 模式"
        />

        <!-- 项目描述（文本域，固定放表单最底部，约定见 AGENTS.md §20.2） -->
        <el-row :gutter="20">
          <el-col :span="24">
            <el-form-item label="项目描述" prop="description">
              <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="项目用途说明（选填）" />
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>

      <div class="form-footer">
        <el-button size="small" @click="goBack">取 消</el-button>
        <el-button type="primary" size="small" :loading="submitLoading" @click="handleSubmit">保 存</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目新增 / 编辑页（独立路由，替代原列表页内的弹框表单）。
 *
 * 路由：/project/add（新增）与 /project/edit/:id（编辑）共用本组件，按是否有 :id 区分。
 * 保存成功后跳回 /project/list。三段高级配置用 ConfigEditor 双模式编辑（表单 / JSON），
 * 字段 schema 与联动规则见 constants/projectConfig.ts，键名契约见 docs/项目模块.md §2.3。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage } from 'element-plus'
import { Back } from '@element-plus/icons-vue'
import { useTagsViewStore } from '@/store/tagsView.ts'
import { usePageRefreshStore } from '@/store/pageRefresh.ts'
import { getProject, createProject, updateProject } from '@/api/project.ts'
import { pageBuild } from '@/api/build.ts'
import { pageRelease } from '@/api/release.ts'
import { listCredentials } from '@/api/credential.ts'
import { listRegistries } from '@/api/registry.ts'
import { listServers } from '@/api/server.ts'
import type { ProjectDto, CredentialVo, RegistryVo, ServerVo } from '@/api/deployTypes.ts'
import { CREDENTIAL_TYPE_MAP, GIT_CREDENTIAL_TYPES } from '@/constants/credentialType.ts'
import {
  RUNTIME_TYPE_OPTIONS,
  BUILD_MODE_OPTIONS,
  BUILD_CONFIG_FIELDS,
  RUNTIME_CONFIG_FIELDS,
  HEALTH_CONFIG_FIELDS
} from '@/constants/projectConfig.ts'
import ConfigEditor from '@/components/ConfigEditor/index.vue'

defineOptions({ name: 'ProjectEdit' })

const route = useRoute()
const router = useRouter()
const tagsViewStore = useTagsViewStore()
const pageRefreshStore = usePageRefreshStore()

/** 编辑态由路由参数 :id 判定；新增页没有该参数 */
const isEdit = computed(() => Boolean(route.params.id))

// ==================== 表单状态 ====================
const formRef = ref<FormInstance>()
const submitLoading = ref(false)
/** 基本信息 + 下拉数据是否就绪：就绪前不渲染表单，避免 ConfigEditor 空初始化 */
const loaded = ref(false)

/**
 * 编辑态下项目编码是否可改。
 *
 * 编码不只是显示名：构建工作区 `{工作区}/{编码}_{构建ID}`、产物持久目录 `{持久目录}/{编码}/{版本}`、
 * JAR 默认工作目录 `apps/{编码}`、systemd 服务名与容器名的默认值都取自它。已产生构建 / 发布记录的
 * 项目改编码会让历史产物与新路径失联，因此只在「没有任何历史记录」时放开。
 */
const codeEditable = ref(true)
/** 编码被禁用时的原因说明，展示在输入框下方 */
const codeDisabledTip = ref('')

const formData = reactive<ProjectDto>({
  code: '',
  name: '',
  runtimeType: 'DOCKER',
  sourceType: 'GIT',
  gitUrl: '',
  gitBranch: '',
  gitCredentialId: '',
  buildMode: 'PROJECT_DOCKERFILE',
  registryId: '',
  buildServerId: '',
  retainReleaseCount: 5,
  status: 1,
  description: ''
})

// 三段配置的 JSON 值（v-model 绑定到 ConfigEditor，提交时也可从组件 getValue 取）
const buildConfigJson = ref('')
const runtimeConfigJson = ref('')
const healthConfigJson = ref('')

const buildEditorRef = ref<InstanceType<typeof ConfigEditor>>()
const runtimeEditorRef = ref<InstanceType<typeof ConfigEditor>>()
const healthEditorRef = ref<InstanceType<typeof ConfigEditor>>()

/** 按运行时过滤后的构建模式选项（未选运行时时返回全部，避免空下拉） */
const buildModeOptions = computed(() => {
  const runtimeType = formData.runtimeType
  if (!runtimeType) {
    return BUILD_MODE_OPTIONS
  }
  return BUILD_MODE_OPTIONS.filter((item) => item.runtimes.includes(runtimeType))
})

/** 编辑器的联动上下文：三段编辑器共享运行时与构建模式，健康检查的探测方式联动由组件内部合并 */
const editorContext = computed(() => ({
  runtimeType: formData.runtimeType || '',
  buildMode: formData.buildMode || ''
}))

/**
 * 构建模式是否为「仅发布已有产物」。
 *
 * 该模式下平台不执行任何构建步骤，产物由上传通道直接登记，
 * 因此构建参数（子模块、构建命令、产物路径等）全部不参与执行。
 */
const isArtifactOnly = computed(() => formData.buildMode === 'ARTIFACT_ONLY')

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
  }]
}

// ==================== 下拉数据源 ====================
/** Git 凭据下拉数据，只含类型为 GIT_TOKEN / SSH_KEY 的凭据（与后端 Git 类型校验一致） */
const gitCredentials = ref<CredentialVo[]>([])

/** 镜像仓库下拉数据，只含启用中的仓库（停用仓库不允许被新项目绑定） */
const registries = ref<RegistryVo[]>([])

/** 构建机下拉数据，只含启用中的服务器（停用服务器不允许参与构建） */
const servers = ref<ServerVo[]>([])

/** 加载表单下拉需要的外部资源（凭据、镜像仓库、服务器都是全局资源，页面只读列表） */
async function fetchFormOptions() {
  const credentialRes = await listCredentials()
  gitCredentials.value = credentialRes.data.filter((item) => GIT_CREDENTIAL_TYPES.includes(item.type))
  const registryRes = await listRegistries({ status: 1 })
  registries.value = registryRes.data
  const serverRes = await listServers({ status: 1 })
  servers.value = serverRes.data
}

/**
 * 编辑态回填：基本信息逐字段赋值，三段 JSON 交给 ConfigEditor 自行装载。
 */
async function loadProject() {
  const res = await getProject(String(route.params.id))
  const row = res.data
  formData.code = row.code
  formData.name = row.name
  formData.runtimeType = row.runtimeType
  formData.sourceType = row.sourceType
  formData.gitUrl = row.gitUrl || ''
  formData.gitBranch = row.gitBranch || ''
  formData.gitCredentialId = row.gitCredentialId || ''
  formData.buildMode = row.buildMode
  formData.registryId = row.registryId || ''
  formData.buildServerId = row.buildServerId || ''
  formData.retainReleaseCount = row.retainReleaseCount
  formData.status = row.status
  formData.description = row.description || ''
  buildConfigJson.value = row.buildConfigJson || ''
  runtimeConfigJson.value = row.runtimeConfigJson || ''
  healthConfigJson.value = row.healthConfigJson || ''
}

/**
 * 判断编辑态下编码能否修改：查该项目是否有构建 / 发布记录（各取 1 条即可，只看总数）。
 *
 * 查询失败时按「不可改」处理并给出说明，避免接口异常导致误改。
 */
async function loadCodeEditability() {
  if (!isEdit.value) {
    codeEditable.value = true
    return
  }
  try {
    const [buildRes, releaseRes] = await Promise.all([
      pageBuild({ pageNum: 1, pageSize: 1, projectId: String(route.params.id) }),
      pageRelease({ pageNum: 1, pageSize: 1, projectId: String(route.params.id) })
    ])
    const historyCount = Number(buildRes.data.total) + Number(releaseRes.data.total)
    if (historyCount === 0) {
      codeEditable.value = true
      codeDisabledTip.value = ''
    } else {
      codeEditable.value = false
      codeDisabledTip.value = '该项目已产生构建 / 发布记录：编码用于产物目录、systemd 服务名与容器名，修改会导致历史产物失联，故不可修改'
    }
  } catch {
    codeEditable.value = false
    codeDisabledTip.value = '无法确认该项目的构建 / 发布记录，暂不允许修改编码'
  }
}

onMounted(async () => {
  try {
    await Promise.all([fetchFormOptions(), isEdit.value ? loadProject() : Promise.resolve()])
    await loadCodeEditability()
  } finally {
    loaded.value = true
  }
})

// ==================== 联动与提交 ====================
/**
 * 运行时切换时校正构建模式与镜像仓库绑定（AGENTS.md §23.4）：
 * 1. 当前构建模式不被新运行时支持时，自动切到该运行时的第一个可选模式；
 * 2. 运行时不是 DOCKER 时清空镜像仓库绑定。
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

function goBack() {
  router.push('/project/list')
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // 三段配置交给各编辑器自校验（JSON 语法 + 可见必填字段）
  for (const editor of [buildEditorRef.value, runtimeEditorRef.value, healthEditorRef.value]) {
    const result = editor?.validate()
    if (result && !result.ok) {
      ElMessage.warning(result.message)
      return
    }
  }

  submitLoading.value = true
  try {
    const payload = buildSubmitPayload()
    if (isEdit.value) {
      // 后端修改接口同样校验编码必填且做唯一性预检（排除当前记录自身）
      await updateProject(String(route.params.id), payload)
      ElMessage.success('修改成功')
    } else {
      await createProject(payload)
      ElMessage.success('新增成功')
    }
    // 列表页被 keep-alive 缓存不会自动刷新，先打刷新标记再跳转
    pageRefreshStore.markDirty('project-list')
    // 关闭当前新增/编辑标签页（连同其 keep-alive 缓存），再跳回列表
    tagsViewStore.delView({ path: route.path, name: route.name as string, meta: route.meta })
    router.push('/project/list')
  } finally {
    submitLoading.value = false
  }
}

/**
 * 组装提交给后端的请求体。
 *
 * 可选文本（Git 分支、Git 凭据 ID、三段 JSON、项目描述）未填写时统一提交 undefined，
 * 避免把空串写进后端 JSON 列；后端 ProjectService 仍会把空白值归一为 null 兜底。
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
    buildServerId: formData.buildServerId || undefined,
    buildConfigJson: buildConfigJson.value || undefined,
    runtimeConfigJson: runtimeConfigJson.value || undefined,
    healthConfigJson: healthConfigJson.value || undefined,
    retainReleaseCount: formData.retainReleaseCount,
    status: formData.status,
    description: formData.description || undefined
  }
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .form-tip（定义在 styles/index.scss）

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.form-footer {
  margin-top: 8px;
  text-align: right;
}
</style>
