<template>
  <div class="app-container">
    <!-- 搜索区域：文本输入框支持回车触发查询（约定见 AGENTS.md §20.5） -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="仓库名称">
          <el-input v-model="searchForm.name" placeholder="请输入仓库名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="仓库地址">
          <el-input v-model="searchForm.registryUrl" placeholder="请输入仓库地址" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 160px">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
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
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增镜像仓库</el-button>
    </div>

    <!-- 数据表格：所有列居中、统一 min-width、操作列固定右侧（约定见 AGENTS.md §20.8） -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="name" label="仓库名称" min-width="160" show-overflow-tooltip align="center" />
      <el-table-column prop="registryUrl" label="仓库地址" min-width="240" show-overflow-tooltip align="center" />
      <el-table-column prop="namespace" label="命名空间" min-width="140" align="center">
        <template #default="{ row }">
          {{ row.namespace || '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="credentialId" label="登录凭据" min-width="170" align="center">
        <template #default="{ row }">
          {{ credentialName(row.credentialId) }}
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" min-width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="200" align="center" />
      <el-table-column prop="description" label="描述" min-width="150" align="center" show-overflow-tooltip />
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
      <div style="max-height: 60vh; overflow-y: auto; padding: 16px 18px 0 10px;">
        <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px" size="small">
          <!-- 第一行：名称 + 状态 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="仓库名称" prop="name">
                <el-input v-model="formData.name" placeholder="如：阿里云 ACR" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="状态" prop="status">
                <el-select v-model="formData.status" placeholder="请选择" style="width: 100%">
                  <el-option label="启用" :value="1" />
                  <el-option label="停用" :value="0" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第二行：地址 + 命名空间 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="仓库地址" prop="registryUrl">
                <el-input v-model="formData.registryUrl" placeholder="registry.cn-hangzhou.aliyuncs.com" style="width: 100%" />
                <span class="form-tip">不含 https:// 前缀</span>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="命名空间" prop="namespace">
                <el-input v-model="formData.namespace" placeholder="选填，如 light-deploy" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第三行：登录凭据（只列 REGISTRY 类型的凭据，与后端校验一致） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="登录凭据" prop="credentialId">
                <el-select v-model="formData.credentialId" placeholder="公共仓库留空" clearable style="width: 100%">
                  <el-option
                    v-for="item in registryCredentials"
                    :key="item.id"
                    :label="item.name"
                    :value="item.id"
                  />
                </el-select>
                <span class="form-tip">只能选择类型为「镜像仓库凭据」的凭据；私有仓库必填，公共仓库留空</span>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 最后一行：描述 -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="仓库描述" prop="description">
                <el-input v-model="formData.description" type="textarea" :rows="3" placeholder="仓库用途说明（选填）" />
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
 * 镜像仓库管理页面（系统设置 → 镜像仓库）。
 *
 * 对接后端 /registry 标准六接口（page / list / {id} / POST / PUT / DELETE）。
 * 关键约定（AGENTS.md §23.4）：
 * 1. 镜像仓库只服务于 DOCKER 运行时，脚本部署与仅发布产物不产出镜像、不绑定仓库；
 * 2. 登录凭据只能选择类型为 REGISTRY 的凭据（后端 RegistryService 同口径校验）；
 * 3. 仓库被项目（project.registryId）绑定时不允许删除，后端返回 4005；
 * 4. 列表的「登录凭据」列展示凭据名称，凭据列表由 CredentialVo 提供，不含任何密文。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { pageRegistry, createRegistry, updateRegistry, deleteRegistry } from '@/api/registry.ts'
import { listCredentials } from '@/api/credential.ts'
import type { RegistryVo, RegistryDto, CredentialVo } from '@/api/deployTypes.ts'
import { CREDENTIAL_TYPE_MAP, GIT_CREDENTIAL_TYPES } from '@/constants/credentialType.ts'

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

defineOptions({ name: 'RegistryList' })

// ==================== 搜索 ====================
const searchForm = reactive({
  name: '',
  registryUrl: '',
  status: undefined as number | undefined
})

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
  searchForm.registryUrl = ''
  searchForm.status = undefined
  timeRange.value = []
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<RegistryVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

/** 凭据列表，用于「登录凭据」下拉与列表列的名称映射（只取 REGISTRY 类型） */
const registryCredentials = ref<CredentialVo[]>([])

/** 加载镜像仓库分页数据；查询条件为空时后端返回全部未删除记录 */
async function fetchData() {
  loading.value = true
  try {
    const res = await pageRegistry({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      name: searchForm.name || undefined,
      registryUrl: searchForm.registryUrl || undefined,
      status: searchForm.status,
      ...splitTimeRange(timeRange.value),
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

/** 加载可用于登录镜像仓库的凭据（类型 REGISTRY），供下拉与列表名称映射使用 */
async function fetchRegistryCredentials() {
  const res = await listCredentials({ type: 'REGISTRY' })
  registryCredentials.value = res.data
}

fetchData()
fetchRegistryCredentials()

/**
 * 把凭据 ID 映射为凭据名称。
 *
 * @param credentialId 仓库绑定的凭据主键；为空表示公共仓库
 * @returns 凭据名称；凭据已删除或未绑定返回占位文本
 */
function credentialName(credentialId?: string | null): string {
  if (!credentialId) return '公共仓库'
  return registryCredentials.value.find((item) => item.id === credentialId)?.name || '凭据已删除'
}

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const submitLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const editId = ref('')

const dialogTitle = computed(() => (isEdit.value ? '编辑镜像仓库' : '新增镜像仓库'))

/** 表单数据：status 默认 1（启用），与后端 RegistryService 的默认值保持一致 */
const formData = reactive({
  name: '',
  registryUrl: '',
  namespace: '',
  credentialId: '',
  status: 1,
  description: ''
})

/**
 * 表单校验规则（与后端 RegistryService 镜像）。
 *
 * 名称必填且不超过 100 字符、地址必填且不超过 255 字符、状态必填；
 * 凭据引用由下拉限制取值范围，后端会再校验存在性与类型。
 */
const formRules: FormRules = {
  name: [
    { required: true, message: '请输入仓库名称', trigger: 'blur' },
    { max: 100, message: '仓库名称长度不能超过 100 个字符', trigger: 'blur' }
  ],
  registryUrl: [
    { required: true, message: '请输入仓库地址', trigger: 'blur' },
    { max: 255, message: '仓库地址长度不能超过 255 个字符', trigger: 'blur' }
  ],
  status: [{ required: true, message: '请选择状态', trigger: 'change' }]
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
 * @param row 待编辑的仓库行数据
 */
function handleEdit(row: RegistryVo) {
  resetForm()
  isEdit.value = true
  editId.value = row.id
  formData.name = row.name
  formData.registryUrl = row.registryUrl
  formData.namespace = row.namespace || ''
  formData.credentialId = row.credentialId || ''
  formData.status = row.status
  formData.description = row.description || ''
  dialogVisible.value = true
}

/** 重置表单字段与校验状态；对话框关闭与打开新增时都会调用 */
function resetForm() {
  formData.name = ''
  formData.registryUrl = ''
  formData.namespace = ''
  formData.credentialId = ''
  formData.status = 1
  formData.description = ''
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
      await updateRegistry(editId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await createRegistry(payload)
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
 * 命名空间、凭据、描述未填写时统一提交 undefined；后端 Service 会做兜底校验，
 * 尤其会拒绝「凭据类型不是 REGISTRY」的引用。
 *
 * @returns 与后端 RegistryDto 字段一一对应的请求对象
 */
function buildSubmitPayload(): RegistryDto {
  return {
    name: formData.name,
    registryUrl: formData.registryUrl,
    namespace: formData.namespace || undefined,
    credentialId: formData.credentialId || undefined,
    status: formData.status,
    description: formData.description || undefined
  }
}

/**
 * 删除镜像仓库。
 *
 * @param row 待删除的仓库行数据；仍被项目绑定时后端返回 4005
 */
async function handleDelete(row: RegistryVo) {
  // 后端在仓库仍被项目绑定时返回 DATA_CONFLICT，request 层会提示具体原因
  const confirmed = await ElMessageBox.confirm(
    `确定删除镜像仓库「${row.name}」吗？已被项目绑定的仓库需要先解除绑定。`,
    '提示',
    { type: 'warning' }
  ).then(() => true).catch(() => false)
  if (!confirmed) return

  await deleteRegistry(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
</style>
