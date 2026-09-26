<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="项目">
          <el-select v-model="searchForm.projectId" placeholder="请选择项目" clearable filterable style="width: 160px">
            <el-option v-for="p in projectOptions" :key="p.id" :label="`${p.name}（${p.code}）`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="searchForm.environmentId" placeholder="请选择环境" clearable filterable style="width: 160px">
            <el-option v-for="e in environmentOptions" :key="e.id" :label="`${e.name}（${e.code}）`" :value="e.id" />
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
      <el-button type="primary" :icon="Plus" @click="handleAdd">绑定部署目标</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column label="项目" min-width="150" show-overflow-tooltip align="center">
        <template #default="{ row }">
          {{ projectNameMap[row.projectId] || row.projectId }}
        </template>
      </el-table-column>
      <el-table-column label="环境" min-width="140" show-overflow-tooltip align="center">
        <template #default="{ row }">
          {{ environmentNameMap[row.environmentId] || row.environmentId }}
        </template>
      </el-table-column>
      <el-table-column label="目标服务器" min-width="160" show-overflow-tooltip align="center">
        <template #default="{ row }">
          {{ serverNameMap[row.serverId] || row.serverId }}
        </template>
      </el-table-column>
      <el-table-column prop="isPrimary" label="主目标" min-width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.isPrimary === 1 ? 'danger' : 'info'">
            {{ row.isPrimary === 1 ? '主目标' : '普通' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="deployOrder" label="发布顺序" min-width="100" align="center">
        <template #default="{ row }">
          <el-tooltip placement="top">
            <template #content>
              <div class="tooltip-lines">
                <div v-for="(line, i) in DEPLOY_ORDER_TIPS" :key="i">{{ line }}</div>
              </div>
            </template>
            <span class="hover-underline">{{ row.deployOrder }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" min-width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="200" align="center" />
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

    <!-- 新增/编辑对话框：统一 800px + label 120px + 两列布局（约定见 AGENTS.md §20.2） -->
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
          <!-- 第一行：项目 + 环境 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="项目" prop="projectId">
                <el-select v-model="formData.projectId" placeholder="请选择项目" filterable style="width: 100%">
                  <el-option v-for="p in projectOptions" :key="p.id" :label="`${p.name}（${p.code}）`" :value="p.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="环境" prop="environmentId">
                <el-select v-model="formData.environmentId" placeholder="请选择环境" filterable style="width: 100%">
                  <el-option v-for="e in environmentOptions" :key="e.id" :label="`${e.name}（${e.code}）`" :value="e.id" />
                </el-select>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第二行：目标服务器 + 发布顺序 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="目标服务器" prop="serverId">
                <el-select v-model="formData.serverId" placeholder="仅列出启用状态的服务器" filterable style="width: 100%">
                  <el-option v-for="s in serverOptions" :key="s.id" :label="`${s.serverName}（${s.host}）`" :value="s.id" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="发布顺序" prop="deployOrder">
                <el-tooltip placement="top">
                  <template #content>
                    <div class="tooltip-lines">
                      <div v-for="(line, i) in DEPLOY_ORDER_TIPS" :key="i">{{ line }}</div>
                    </div>
                  </template>
                  <el-input-number v-model="formData.deployOrder" :min="1" style="width: 100%" />
                </el-tooltip>
                <span class="form-tip">数字小的先发；所有策略都按此顺序展开目标，不只是滚动发布</span>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第三行：主目标开关 + 启用状态 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="主目标" prop="isPrimary">
                <el-switch v-model="formData.isPrimary" :active-value="1" :inactive-value="0" />
                <span class="form-tip">
                  同一项目同一环境最多一个主目标，设为主目标时自动取消旧主目标；单目标策略下只发主目标
                </span>
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
 * 部署目标管理页面。
 *
 * 对接后端 /projectEnvironmentTarget 标准六接口。业务约定：
 * 1. 绑定关系为「项目 × 环境 → 服务器」，同一组合只能绑定一次（后端唯一键约束）；
 * 2. 目标服务器下拉只展示启用状态的服务器（后端校验存在性与启用状态）；
 * 3. 主目标互斥：设为主目标时后端在同一事务内自动取消旧主目标；
 * 4. 表格中的项目/环境/服务器均只存 ID，页面通过 list 接口做本地名称映射。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { pageDeployTarget, createDeployTarget, updateDeployTarget, deleteDeployTarget } from '@/api/deployTarget.ts'
import { listProjects } from '@/api/project.ts'
import { listEnvironments } from '@/api/environment.ts'
import { listServers } from '@/api/server.ts'
import type { DeployTargetVo, DeployTargetDto, ProjectVo, EnvironmentVo, ServerVo } from '@/api/deployTypes.ts'
import { DEPLOY_ORDER_TIPS } from '@/constants/deploymentStatus.ts'

defineOptions({ name: 'DeployTargetList' })

// ==================== 搜索 ====================
const searchForm = reactive({ projectId: '', environmentId: '', status: undefined as number | undefined })

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.projectId = ''
  searchForm.environmentId = ''
  searchForm.status = undefined
  handleSearch()
}

// ==================== 下拉选项与名称映射 ====================
const projectOptions = ref<ProjectVo[]>([])
const environmentOptions = ref<EnvironmentVo[]>([])
const serverOptions = ref<ServerVo[]>([])

const projectNameMap = ref<Record<string, string>>({})
const environmentNameMap = ref<Record<string, string>>({})
const serverNameMap = ref<Record<string, string>>({})

async function loadOptions() {
  // 项目与环境取全部，服务器只取启用状态（与表单约束一致）
  const [projects, environments, servers] = await Promise.all([
    listProjects(),
    listEnvironments(),
    listServers({ status: 1 })
  ])
  projectOptions.value = projects.data || []
  environmentOptions.value = environments.data || []
  serverOptions.value = servers.data || []
  projectNameMap.value = Object.fromEntries(projectOptions.value.map(p => [p.id, `${p.name}（${p.code}）`]))
  environmentNameMap.value = Object.fromEntries(environmentOptions.value.map(e => [e.id, `${e.name}（${e.code}）`]))
  serverNameMap.value = Object.fromEntries(serverOptions.value.map(s => [s.id, `${s.serverName}（${s.host}）`]))
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<DeployTargetVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    const res = await pageDeployTarget({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      projectId: searchForm.projectId || undefined,
      environmentId: searchForm.environmentId || undefined,
      status: searchForm.status
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()
loadOptions()

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const submitLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const editId = ref('')

const formData = reactive<DeployTargetDto>({
  projectId: '',
  environmentId: '',
  serverId: '',
  isPrimary: 0,
  deployOrder: 1,
  status: 1
})

const formRules: FormRules = {
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }],
  environmentId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  serverId: [{ required: true, message: '请选择目标服务器', trigger: 'change' }]
}

const dialogTitle = computed(() => (isEdit.value ? '编辑部署目标' : '绑定部署目标'))

function handleAdd() {
  isEdit.value = false
  dialogVisible.value = true
}

/**
 * 打开编辑对话框并回填字段。
 *
 * @param row 待编辑的部署目标行数据
 */
function handleEdit(row: DeployTargetVo) {
  isEdit.value = true
  editId.value = row.id
  formData.projectId = row.projectId
  formData.environmentId = row.environmentId
  formData.serverId = row.serverId
  formData.isPrimary = row.isPrimary
  formData.deployOrder = row.deployOrder
  formData.status = row.status
  dialogVisible.value = true
}

function resetForm() {
  formData.projectId = ''
  formData.environmentId = ''
  formData.serverId = ''
  formData.isPrimary = 0
  formData.deployOrder = 1
  formData.status = 1
  editId.value = ''
  formRef.value?.resetFields()
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    if (isEdit.value) {
      await updateDeployTarget(editId.value, formData)
      ElMessage.success('修改成功')
    } else {
      await createDeployTarget(formData)
      ElMessage.success('绑定成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    submitLoading.value = false
  }
}

// ==================== 删除 ====================
/**
 * 解除部署目标绑定（逻辑删除）。
 *
 * @param row 待删除的部署目标行数据
 */
async function handleDelete(row: DeployTargetVo) {
  const target = `${projectNameMap.value[row.projectId] || row.projectId} → ${serverNameMap.value[row.serverId] || row.serverId}`
  await ElMessageBox.confirm(`确定要解除绑定吗？\n${target}`, '提示', { type: 'warning' })
  await deleteDeployTarget(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div / .form-tip
// 定义在 styles/index.scss 中

// 带悬浮说明的单元格：虚下划线提示这里可以悬浮
.hover-underline {
  border-bottom: 1px dashed var(--el-border-color-darker);
  cursor: help;
}
</style>
