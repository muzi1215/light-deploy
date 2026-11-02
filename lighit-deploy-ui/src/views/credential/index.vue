<template>
  <div class="app-container">
    <!-- 搜索区域：文本输入框支持回车触发查询（约定见 AGENTS.md §20.5） -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="凭据名称">
          <el-input v-model="searchForm.name" placeholder="请输入凭据名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="凭据类型">
          <el-select v-model="searchForm.type" placeholder="请选择" clearable style="width: 160px">
            <el-option v-for="item in CREDENTIAL_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <CreateTimeRange v-model="timeRange" />
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 工具栏 -->
    <div class="tools-div">
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增凭据</el-button>
    </div>

    <!-- 数据表格：所有列居中、统一 min-width、操作列固定右侧（约定见 AGENTS.md §20.8） -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="name" label="凭据名称" min-width="170" show-overflow-tooltip fixed="left" align="center" />
      <el-table-column prop="type" label="凭据类型" min-width="140" align="center">
        <template #default="{ row }">
          <el-tag :type="CREDENTIAL_TYPE_TAG[row.type] || 'info'">
            {{ CREDENTIAL_TYPE_MAP[row.type] || row.type }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="metadataJson" label="元数据" min-width="200" align="center" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.metadataJson || '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="200" align="center" />
      <el-table-column label="操作" min-width="160" fixed="right" align="center">
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
          <!-- 第一行：名称 + 类型 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="凭据名称" prop="name">
                <el-input v-model="formData.name" placeholder="如：gitee 只读令牌" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="凭据类型" prop="type">
                <el-select v-model="formData.type" placeholder="请选择" style="width: 100%">
                  <el-option v-for="item in CREDENTIAL_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第二行：类型说明，帮助区分「Git 令牌」与「镜像仓库凭据」的使用场景 -->
          <el-row :gutter="20">
            <el-col :span="24">
              <span class="form-tip">{{ currentTypeTip }}</span>
            </el-col>
          </el-row>

          <!-- 第三行：明文凭据（敏感字段，只写不读，编辑时留空表示不修改） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="凭据内容" prop="credential">
                <el-input
                  v-model="formData.credential"
                  type="textarea"
                  :rows="4"
                  :placeholder="credentialPlaceholder"
                />
                <span class="form-tip">提交后由后端 AES-GCM 加密落库，任何查询接口都不会回显；私钥请粘贴完整 PEM 内容</span>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 最后一行：非敏感元数据 -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item prop="metadataJson">
                <template #label>
                  <span>元数据</span>
                  <el-tooltip placement="top" effect="dark" :show-after="100">
                    <template #content>
                      <pre class="tooltip-json">{{ METADATA_EXAMPLE }}</pre>
                    </template>
                    <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
                  </el-tooltip>
                </template>
                <el-input v-model="formData.metadataJson" type="textarea" :rows="3" :placeholder="METADATA_EXAMPLE" />
                <span class="form-tip">非敏感信息，仅供页面展示（如给凭据备注一个账号名），不参与登录或克隆；禁止填写密码、令牌或私钥（选填，须为合法 JSON）</span>
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
 * 凭据管理页面（系统设置 → 凭据管理）。
 *
 * 对接后端 /credential 标准六接口（page / list / {id} / POST / PUT / DELETE）。
 * 关键约定：
 * 1. 明文凭据只写不读：新增必填，编辑留空表示保留原凭据（后端 CredentialService 同口径）；
 * 2. 列表接口不返回密文，页面也不展示任何凭据内容，只展示名称、类型与非敏感元数据；
 * 3. 删除前后端会检查项目与镜像仓库的引用，被引用时返回 4005 由 request 层统一提示；
 * 4. 类型取值与展示统一走 constants/credentialType.ts，与后端 CredentialType 枚举一一对应。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, QuestionFilled } from '@element-plus/icons-vue'
import { pageCredential, createCredential, updateCredential, deleteCredential } from '@/api/credential.ts'
import type { CredentialVo, CredentialDto } from '@/api/deployTypes.ts'
import { CREDENTIAL_TYPE_OPTIONS, CREDENTIAL_TYPE_MAP, CREDENTIAL_TYPE_TAG } from '@/constants/credentialType.ts'

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

defineOptions({ name: 'CredentialList' })

/** 元数据示例：同时用于 placeholder 与 label 上「?」图标的悬浮提示，保证示例只有一份来源 */
const METADATA_EXAMPLE = `{
  "username": "light-deploy",
  "remark": "阿里云个人仓库"
}`

// ==================== 搜索 ====================
const searchForm = reactive({ name: '', type: '' })

/** 创建时间区间：与后端 createTimeBegin / createTimeEnd 对应，未选择时两个参数都不传 */
const timeRange = ref<string[]>([])

/** 触发查询：页码重置为第一页后重新加载数据 */

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

/** 重置全部搜索条件并回到第一页查询 */
function handleReset() {
  searchForm.name = ''
  searchForm.type = ''
  timeRange.value = []
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<CredentialVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

/** 加载凭据分页数据；查询条件为空时后端返回全部未删除记录 */
async function fetchData() {
  loading.value = true
  try {
    const res = await pageCredential({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      name: searchForm.name || undefined,
      type: searchForm.type || undefined,
      ...splitTimeRange(timeRange.value),
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

const dialogTitle = computed(() => (isEdit.value ? '编辑凭据' : '新增凭据'))

/** 当前选中类型的用途说明，取值来自 constants/credentialType.ts 的 tip */
const currentTypeTip = computed(
  () => CREDENTIAL_TYPE_OPTIONS.find((item) => item.value === formData.type)?.tip || '请选择凭据类型'
)

/**
 * 凭据内容输入框的占位文案。
 *
 * 编辑态优先提示「留空表示不修改」；新增态按类型给出各自的填写形态——四类凭据往里填的东西
 * 完全不同（令牌 / PEM 私钥 / 用户名:密码），一句通用文案不足以说清，填错类型只能等到构建或
 * 推送时才暴露。口径见 docs/凭据模块.md §6.6。
 */
const credentialPlaceholder = computed(() => {
  if (isEdit.value) {
    return '留空表示不修改现有凭据；填写则整体覆盖'
  }
  switch (formData.type) {
    case 'GIT_TOKEN':
      return '令牌，或「用户名:令牌」，如 wangan2022:a1b2c3d4e5f6'
    case 'SSH_KEY':
      return '粘贴完整 PEM 私钥，含 -----BEGIN / -----END 两行'
    case 'REGISTRY':
      return '「用户名:密码」，如 light-deploy:Abc123456'
    default:
      return '该类型当前没有消费方，填了也不会被使用'
  }
})

/**
 * 表单数据。
 *
 * type 默认 GIT_TOKEN：凭据最常见的用途是 Git 拉取；credential 是明文，仅在提交时传给后端。
 */
const formData = reactive({
  name: '',
  type: 'GIT_TOKEN',
  credential: '',
  metadataJson: ''
})

/**
 * 表单校验规则（与后端 CredentialService 镜像）。
 *
 * 与后端一致的三条：名称必填且不超过 100 字符、类型必填、元数据必须是合法 JSON。
 * 明文凭据只在新增时必填：编辑留空表示保留原凭据（后端 excludeId != null 时不强制）。
 */
const formRules = computed<FormRules>(() => ({
  name: [
    { required: true, message: '请输入凭据名称', trigger: 'blur' },
    { max: 100, message: '凭据名称长度不能超过 100 个字符', trigger: 'blur' }
  ],
  type: [{ required: true, message: '请选择凭据类型', trigger: 'change' }],
  credential: isEdit.value
    ? []
    : [{ required: true, message: '请输入凭据内容', trigger: 'blur' }],
  metadataJson: [{ validator: validateMetadataJson, trigger: 'blur' }]
}))

/**
 * 校验元数据是否为合法 JSON。
 *
 * @param _rule 表单校验规则（未使用，Element Plus 校验器签名要求）
 * @param value 输入框当前文本
 * @param callback 校验结果回调：传入 Error 表示校验失败
 */
function validateMetadataJson(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (!value) {
    callback()
    return
  }
  try {
    JSON.parse(value)
    callback()
  } catch {
    callback(new Error('元数据必须是合法 JSON 格式'))
  }
}

/** 打开新增对话框并重置表单 */
function handleAdd() {
  resetForm()
  isEdit.value = false
  dialogVisible.value = true
}

/**
 * 打开编辑对话框并回填字段。
 *
 * @param row 待编辑的凭据行数据（不含密文，因此凭据内容输入框始终为空）
 */
function handleEdit(row: CredentialVo) {
  resetForm()
  isEdit.value = true
  editId.value = row.id
  formData.name = row.name
  formData.type = row.type
  formData.metadataJson = row.metadataJson || ''
  dialogVisible.value = true
}

/** 重置表单字段与校验状态；对话框关闭与打开新增时都会调用 */
function resetForm() {
  formData.name = ''
  formData.type = 'GIT_TOKEN'
  formData.credential = ''
  formData.metadataJson = ''
  editId.value = ''
  formRef.value?.resetFields()
}

/** 提交表单：按编辑/新增分支调用对应接口，成功后关闭对话框并刷新列表 */
async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    const payload = buildSubmitPayload()
    if (isEdit.value) {
      await updateCredential(editId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await createCredential(payload)
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
 * 元数据未填写时提交 undefined（后端会把空白值归一为 null）；
 * 凭据内容为空时提交 undefined，语义为「不修改现有凭据」。
 *
 * @returns 与后端 CredentialDto 字段一一对应的请求对象
 */
function buildSubmitPayload(): CredentialDto {
  return {
    name: formData.name,
    type: formData.type,
    credential: formData.credential || undefined,
    metadataJson: formData.metadataJson || undefined
  }
}

/**
 * 删除凭据。
 *
 * @param row 待删除的凭据行数据；仍被项目或镜像仓库引用时后端返回 4005
 */
async function handleDelete(row: CredentialVo) {
  // 后端在凭据仍被项目或镜像仓库引用时返回 DATA_CONFLICT，request 层会提示具体原因
  const confirmed = await ElMessageBox.confirm(
    `确定删除凭据「${row.name}」吗？被项目或镜像仓库引用的凭据无法删除。`,
    '提示',
    { type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  await deleteCredential(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
</style>
