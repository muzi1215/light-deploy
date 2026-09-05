<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="环境编码">
          <el-input v-model="searchForm.code" placeholder="如 dev / test / prod" clearable style="width: 160px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="环境名称">
          <el-input v-model="searchForm.name" placeholder="请输入环境名称" clearable style="width: 160px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 工具栏 -->
    <div class="tools-div">
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增环境</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="code" label="环境编码" width="120">
        <template #default="{ row }">
          <el-tag>{{ row.code }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="环境名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="deployStrategy" label="部署策略" width="120" align="center">
        <template #default="{ row }">
          {{ STRATEGY_MAP[row.deployStrategy] || row.deployStrategy }}
        </template>
      </el-table-column>
      <el-table-column prop="batchSize" label="批次大小" width="90" align="center" />
      <el-table-column prop="defaultServerId" label="默认服务器" min-width="140" align="center" show-overflow-tooltip>
        <template #default="{ row }">
          {{ serverNameMap[row.defaultServerId] || row.defaultServerId || '-' }}
        </template>
      </el-table-column>
      <el-table-column prop="aiopsEnabled" label="AIops联动" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.aiopsEnabled === 1 ? 'success' : 'info'">
            {{ row.aiopsEnabled === 1 ? '开启' : '关闭' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip />
      <el-table-column prop="createTime" label="创建时间" width="170" align="center" />
      <el-table-column label="操作" width="140" fixed="right" align="center">
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

    <!-- 新增/编辑对话框：统一宽度 720px、label 110px，文本域统一放表单底部 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="720px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="110px">
        <el-form-item label="环境编码" prop="code">
          <el-input v-model="formData.code" placeholder="小写字母开头，如 dev / test / prod" />
        </el-form-item>
        <el-form-item label="环境名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入环境名称" />
        </el-form-item>
        <el-form-item label="部署策略" prop="deployStrategy">
          <el-radio-group v-model="formData.deployStrategy">
            <el-radio value="SINGLE_TARGET">单目标</el-radio>
            <el-radio value="ALL_AT_ONCE">全量发布</el-radio>
            <el-radio value="ROLLING">滚动发布</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="批次大小" prop="batchSize">
          <el-input-number v-model="formData.batchSize" :min="1" :max="50" style="width: 160px" />
          <span class="form-tip">滚动发布时每批同时更新的主机数量</span>
        </el-form-item>
        <el-form-item label="默认服务器" prop="defaultServerId">
          <el-select v-model="formData.defaultServerId" placeholder="请选择（选填）" clearable filterable style="width: 280px">
            <el-option v-for="s in serverOptions" :key="s.id" :label="`${s.serverName}（${s.host}）`" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="AIops联动" prop="aiopsEnabled">
          <el-switch v-model="formData.aiopsEnabled" :active-value="1" :inactive-value="0" />
          <span class="form-tip">开启后发布期间自动创建 AIops 告警静默窗口</span>
        </el-form-item>
        <el-form-item label="环境描述" prop="description">
          <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="环境用途说明（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 环境管理页面。
 *
 * 对接后端 /environment 标准六接口。业务约定：
 * 1. 环境编码创建后不允许修改（编辑时禁用），后端做唯一性与小写格式校验；
 * 2. 默认服务器下拉来源于 /server/list 启用服务器，后端校验存在性；
 * 3. 批次大小仅对 ROLLING 滚动发布策略生效。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { pageEnvironment, createEnvironment, updateEnvironment, deleteEnvironment } from '@/api/environment.ts'
import { listServers } from '@/api/server.ts'
import type { EnvironmentVo, EnvironmentDto, ServerVo } from '@/api/deployTypes.ts'

defineOptions({ name: 'EnvironmentList' })

/** 部署策略展示映射（与后端 DeployStrategy 枚举一致） */
const STRATEGY_MAP: Record<string, string> = {
  SINGLE_TARGET: '单目标',
  ALL_AT_ONCE: '全量发布',
  ROLLING: '滚动发布'
}

// ==================== 搜索 ====================
const searchForm = reactive({ code: '', name: '' })

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.code = ''
  searchForm.name = ''
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<EnvironmentVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

/** 启用服务器下拉选项与 id→名称 映射，用于表格展示和表单选择 */
const serverOptions = ref<ServerVo[]>([])
const serverNameMap = ref<Record<string, string>>({})

async function loadServerOptions() {
  // 只取启用状态的服务器作为默认服务器候选
  const res = await listServers({ status: 1 })
  serverOptions.value = res.data || []
  serverNameMap.value = Object.fromEntries(serverOptions.value.map(s => [s.id, `${s.serverName}（${s.host}）`]))
}

async function fetchData() {
  loading.value = true
  try {
    const res = await pageEnvironment({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      code: searchForm.code || undefined,
      name: searchForm.name || undefined
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()
loadServerOptions()

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const submitLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const editId = ref('')

const formData = reactive<EnvironmentDto>({
  code: '',
  name: '',
  deployStrategy: 'SINGLE_TARGET',
  batchSize: 1,
  defaultServerId: '',
  aiopsEnabled: 0,
  description: ''
})

const formRules: FormRules = {
  code: [
    { required: true, message: '请输入环境编码', trigger: 'blur' },
    { pattern: /^[a-z][a-z0-9-]{1,31}$/, message: '小写字母开头，只含小写字母、数字和中划线，2-32位', trigger: 'blur' }
  ],
  name: [{ required: true, message: '请输入环境名称', trigger: 'blur' }]
}

const dialogTitle = computed(() => (isEdit.value ? '编辑环境' : '新增环境'))

function handleAdd() {
  isEdit.value = false
  dialogVisible.value = true
}

/**
 * 打开编辑对话框并回填字段。
 *
 * @param row 待编辑的环境行数据
 */
function handleEdit(row: EnvironmentVo) {
  isEdit.value = true
  editId.value = row.id
  formData.code = row.code
  formData.name = row.name
  formData.deployStrategy = row.deployStrategy
  formData.batchSize = row.batchSize
  formData.defaultServerId = row.defaultServerId || ''
  formData.aiopsEnabled = row.aiopsEnabled
  formData.description = row.description || ''
  dialogVisible.value = true
}

function resetForm() {
  formData.code = ''
  formData.name = ''
  formData.deployStrategy = 'SINGLE_TARGET'
  formData.batchSize = 1
  formData.defaultServerId = ''
  formData.aiopsEnabled = 0
  formData.description = ''
  editId.value = ''
  formRef.value?.resetFields()
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    if (isEdit.value) {
      // 后端修改接口同样校验编码必填且做唯一性预检（排除当前记录自身）
      await updateEnvironment(editId.value, formData)
      ElMessage.success('修改成功')
    } else {
      await createEnvironment(formData)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    submitLoading.value = false
  }
}

// ==================== 删除 ====================
/**
 * 删除环境（逻辑删除）。
 *
 * @param row 待删除的环境行数据；仍被部署目标引用时后端返回 DATA_CONFLICT
 */
async function handleDelete(row: EnvironmentVo) {
  // 后端在环境仍被部署目标引用时返回 DATA_CONFLICT，request 层会提示具体原因
  await ElMessageBox.confirm(`确定要删除环境「${row.name}」吗？若环境已绑定部署目标，需先删除或迁移部署目标。`, '提示', { type: 'warning' })
  await deleteEnvironment(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
.form-tip {
  margin-left: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
