<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="名称">
          <el-input v-model="searchForm.serverName" placeholder="请输入服务器名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="主机">
          <el-input v-model="searchForm.host" placeholder="请输入 IP 或域名" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="环境标签">
          <el-select v-model="searchForm.envTag" placeholder="请选择" clearable style="width: 120px">
            <el-option label="开发" :value="1" />
            <el-option label="测试" :value="2" />
            <el-option label="生产" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 120px">
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
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增服务器</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="serverName" label="服务器名称" min-width="140" show-overflow-tooltip />
      <el-table-column prop="host" label="主机地址" min-width="140" show-overflow-tooltip />
      <el-table-column prop="sshPort" label="SSH端口" width="90" align="center" />
      <el-table-column prop="username" label="登录用户" width="110" show-overflow-tooltip />
      <el-table-column prop="authType" label="认证类型" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.authType === 1 ? 'primary' : 'warning'">
            {{ row.authType === 1 ? '密码' : '私钥' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="envTag" label="环境标签" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="ENV_TAG_MAP[row.envTag]?.tag || 'info'">
            {{ ENV_TAG_MAP[row.envTag]?.label || '未知' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="description" label="描述" min-width="150" show-overflow-tooltip />
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
        <el-form-item label="服务器名称" prop="serverName">
          <el-input v-model="formData.serverName" placeholder="请输入服务器名称" />
        </el-form-item>
        <el-form-item label="主机地址" prop="host">
          <el-input v-model="formData.host" placeholder="IP 地址或域名" />
        </el-form-item>
        <el-form-item label="SSH端口" prop="sshPort">
          <el-input-number v-model="formData.sshPort" :min="1" :max="65535" style="width: 160px" />
        </el-form-item>
        <el-form-item label="登录用户" prop="username">
          <el-input v-model="formData.username" placeholder="SSH 登录用户名" />
        </el-form-item>
        <el-form-item label="认证类型" prop="authType">
          <el-radio-group v-model="formData.authType">
            <el-radio :value="1">密码认证</el-radio>
            <el-radio :value="2">私钥认证</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="环境标签" prop="envTag">
          <el-select v-model="formData.envTag" style="width: 160px">
            <el-option label="开发" :value="1" />
            <el-option label="测试" :value="2" />
            <el-option label="生产" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="formData.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item :label="isEdit ? '新凭据' : '凭据'" prop="credential">
          <el-input
            v-model="formData.credential"
            type="textarea"
            :rows="3"
            :placeholder="isEdit ? '不修改凭据请留空，填写后将重新加密保存' : '请输入 SSH 密码或私钥内容（AES-GCM 加密后保存）'"
            show-password
          />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="服务器用途说明（选填）" />
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
 * 服务器管理页面。
 *
 * 对接后端 /server 标准六接口。业务约定：
 * 1. SSH 凭据（credential）只写不读 —— 新增必填，编辑留空表示保留旧凭据，
 *    后端 AES-GCM 加密后落库，列表永远不返回密文；
 * 2. 环境标签只用于展示筛选，正式发布目标以部署目标绑定为准；
 * 3. 删除为逻辑删除，同 host+端口 在软删除后可重新登记。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { pageServer, createServer, updateServer, deleteServer } from '@/api/server.ts'
import type { ServerVo, ServerDto } from '@/api/deployTypes.ts'

defineOptions({ name: 'ServerList' })

/** 环境标签展示映射（与后端 EnvironmentTag 枚举一致） */
const ENV_TAG_MAP: Record<number, { label: string; tag: string }> = {
  1: { label: '开发', tag: 'info' },
  2: { label: '测试', tag: 'warning' },
  3: { label: '生产', tag: 'danger' }
}

// ==================== 搜索 ====================
const searchForm = reactive({ serverName: '', host: '', envTag: undefined as number | undefined, status: undefined as number | undefined })

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.serverName = ''
  searchForm.host = ''
  searchForm.envTag = undefined
  searchForm.status = undefined
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<ServerVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    // 后端分页参数为 pageNum/pageSize，条件字段为空时不参与查询
    const res = await pageServer({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      serverName: searchForm.serverName || undefined,
      host: searchForm.host || undefined,
      envTag: searchForm.envTag,
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

const formData = reactive<ServerDto & { credential: string }>({
  serverName: '',
  host: '',
  sshPort: 22,
  username: '',
  authType: 1,
  credential: '',
  envTag: 1,
  status: 1,
  description: ''
})

const formRules: FormRules = {
  serverName: [{ required: true, message: '请输入服务器名称', trigger: 'blur' }],
  host: [{ required: true, message: '请输入主机地址', trigger: 'blur' }],
  username: [{ required: true, message: '请输入登录用户', trigger: 'blur' }],
  // 新增时凭据必填；编辑时留空表示保留旧凭据（由下方自定义校验处理）
  credential: [{
    validator: (_rule: any, value: string, callback: (err?: Error) => void) => {
      if (!isEdit.value && !value) {
        callback(new Error('新增服务器必须填写 SSH 密码或私钥'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }]
}

const dialogTitle = computed(() => (isEdit.value ? '编辑服务器' : '新增服务器'))

function handleAdd() {
  isEdit.value = false
  dialogVisible.value = true
}

/**
 * 打开编辑对话框并回填非敏感字段。
 *
 * @param row 待编辑的服务器行数据；凭据不回显（后端不返回密文），留空即保留旧凭据
 */
function handleEdit(row: ServerVo) {
  isEdit.value = true
  editId.value = row.id
  // 回填非敏感字段；凭据不回显（后端不返回密文），留空即保留旧凭据
  formData.serverName = row.serverName
  formData.host = row.host
  formData.sshPort = row.sshPort
  formData.username = row.username
  formData.authType = row.authType
  formData.envTag = row.envTag
  formData.status = row.status
  formData.description = row.description || ''
  formData.credential = ''
  dialogVisible.value = true
}

const editId = ref('')

function resetForm() {
  formData.serverName = ''
  formData.host = ''
  formData.sshPort = 22
  formData.username = ''
  formData.authType = 1
  formData.envTag = 1
  formData.status = 1
  formData.description = ''
  formData.credential = ''
  editId.value = ''
  formRef.value?.resetFields()
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    // 编辑时 credential 为空则不传该字段，后端保留数据库旧密文
    const payload: ServerDto = { ...formData }
    if (isEdit.value && !payload.credential) {
      delete payload.credential
    }
    if (isEdit.value) {
      await updateServer(editId.value, payload)
      ElMessage.success('修改成功')
    } else {
      await createServer(payload)
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
 * 删除服务器（逻辑删除）。
 *
 * @param row 待删除的服务器行数据；删除后同主机端口可重新登记
 */
async function handleDelete(row: ServerVo) {
  await ElMessageBox.confirm(`确定要删除服务器「${row.serverName}」吗？删除后同主机端口可重新登记。`, '提示', { type: 'warning' })
  await deleteServer(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
</style>
