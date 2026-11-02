<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="环境编码">
          <el-input v-model="searchForm.code" placeholder="如 dev / test / prod" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="环境名称">
          <el-input v-model="searchForm.name" placeholder="请输入环境名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
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
      <el-button v-readonly type="primary" :icon="Plus" @click="handleAdd">新增环境</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="code" label="环境编码" min-width="120" fixed="left" align="center">
        <template #default="{ row }">
          <el-tag>{{ row.code }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="环境名称" min-width="140" show-overflow-tooltip align="center" />
      <el-table-column prop="deployStrategy" label="部署策略" min-width="130" align="center">
        <template #default="{ row }">
          <!-- 悬浮展示该策略的完整语义：行为、风险与适用场景，文案取自共享常量 -->
          <el-tooltip placement="top">
            <template #content>
              <div class="tooltip-lines">
                <div class="tip-title">{{ STRATEGY_MAP[row.deployStrategy] || row.deployStrategy }}</div>
                <div v-for="(line, i) in DEPLOY_STRATEGY_TIPS[row.deployStrategy] || []" :key="i">{{ line }}</div>
              </div>
            </template>
            <span class="hover-underline">
              {{ STRATEGY_MAP[row.deployStrategy] || row.deployStrategy }}
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="batchSize" label="批次大小" min-width="100" align="center">
        <template #default="{ row }">
          <el-tooltip placement="top">
            <template #content>
              <div class="tooltip-lines">
                <div v-for="(line, i) in BATCH_SIZE_TIPS" :key="i">{{ line }}</div>
              </div>
            </template>
            <span class="hover-underline">{{ row.batchSize }}</span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="aiopsEnabled" label="AIops联动" min-width="110" align="center">
        <template #default="{ row }">
          <!-- 悬浮说明：这里配的是「环境级」开关，平台总闸关掉时开了也不建窗 -->
          <el-tooltip placement="top">
            <template #content>
              <div class="tooltip-lines">
                <div v-for="(line, i) in AIOPS_ENABLED_TIPS" :key="i">{{ line }}</div>
              </div>
            </template>
            <span class="hover-underline">
              <el-tag :type="row.aiopsEnabled === 1 ? 'success' : 'info'">
                {{ row.aiopsEnabled === 1 ? '开启' : '关闭' }}
              </el-tag>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="200" align="center" />
      <el-table-column prop="description" label="描述" min-width="140" show-overflow-tooltip align="center" />
      <el-table-column label="操作" min-width="260" fixed="right" align="center">
        <template #default="{ row }">
          <el-button v-readonly link type="primary" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
          <el-button v-readonly link type="danger" :icon="Delete" @click="handleDelete(row)">删除</el-button>
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
          <!-- 第一行：环境编码 + 环境名称 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="环境编码" prop="code">
                <el-input v-model="formData.code" placeholder="小写字母开头，如 dev / test / prod" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="环境名称" prop="name">
                <el-input v-model="formData.name" placeholder="请输入环境名称" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第二行：批次大小 + AIops 联动 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="批次大小" prop="batchSize">
                <el-tooltip placement="top">
                  <template #content>
                    <div class="tooltip-lines">
                      <div v-for="(line, i) in BATCH_SIZE_TIPS" :key="i">{{ line }}</div>
                    </div>
                  </template>
                  <el-input-number v-model="formData.batchSize" :min="1" :max="50" style="width: 100%" />
                </el-tooltip>
                <span class="form-tip">仅滚动发布读取；每批包含的目标数，必须小于目标总数才有分批效果</span>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="AIops联动" prop="aiopsEnabled">
                <el-tooltip placement="top">
                  <template #content>
                    <div class="tooltip-lines">
                      <div v-for="(line, i) in AIOPS_ENABLED_TIPS" :key="i">{{ line }}</div>
                    </div>
                  </template>
                  <el-switch v-model="formData.aiopsEnabled" :active-value="1" :inactive-value="0" />
                </el-tooltip>
                <!-- 两句必须都留：只说「建窗」会让人以为开了就一定建，实际还要看平台总闸 -->
                <span class="form-tip">
                  发布期间自动创建 AIops 静默窗；需平台配置里 AIops 联动总闸同时开启才生效
                </span>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第三行：部署策略（三个选项，独占整行） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="部署策略" prop="deployStrategy">
                <!-- 每个选项单独悬浮：选的那一刻就能看清它在干什么，而不是选完才发现不对 -->
                <el-radio-group v-model="formData.deployStrategy">
                  <el-tooltip
                    v-for="opt in strategyOptions"
                    :key="opt.value"
                    placement="top"
                  >
                    <template #content>
                      <div class="tooltip-lines">
                        <div class="tip-title">{{ opt.label }}</div>
                        <div v-for="(line, i) in DEPLOY_STRATEGY_TIPS[opt.value] || []" :key="i">{{ line }}</div>
                      </div>
                    </template>
                    <el-radio :value="opt.value">{{ opt.label }}</el-radio>
                  </el-tooltip>
                </el-radio-group>
                <!-- 明确这里配的是「默认值」，避免与新建部署里的临时覆盖混淆 -->
                <span class="form-tip">
                  该环境的默认策略；新建部署时可在表单里临时覆盖，覆盖只影响那一次，不改这里
                </span>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第四行：环境描述（文本域，固定放表单最底部） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="环境描述" prop="description">
                <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="环境用途说明（选填）" />
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
      </div>
      <template #footer>
        <el-button size="small" @click="dialogVisible = false">取 消</el-button>
        <el-button v-readonly type="primary" size="small" :loading="submitLoading" @click="handleSubmit">确 定</el-button>
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
 * 2. 环境不持有服务器：某项目在该环境下发哪些机器由「项目 → 部署目标」绑定决定，
 *    环境层面的「默认服务器」语义不成立（详见 AGENTS.md §24.11）；
 * 3. 批次大小仅对 ROLLING 滚动发布策略生效。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { pageEnvironment, createEnvironment, updateEnvironment, deleteEnvironment } from '@/api/environment.ts'
import type { EnvironmentVo, EnvironmentDto } from '@/api/deployTypes.ts'
import {
  DEPLOY_STRATEGY_MAP,
  DEPLOY_STRATEGY_OPTIONS,
  DEPLOY_STRATEGY_TIPS,
  BATCH_SIZE_TIPS,
  AIOPS_ENABLED_TIPS
} from '@/constants/deploymentStatus.ts'

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

defineOptions({ name: 'EnvironmentList' })

/** 部署策略展示映射（与后端 DeployStrategy 枚举一致），与部署列表页共用同一份常量 */
const STRATEGY_MAP = DEPLOY_STRATEGY_MAP

/** 部署策略选项，表单里逐个渲染并为每个选项挂悬浮说明 */
const strategyOptions = DEPLOY_STRATEGY_OPTIONS

// ==================== 搜索 ====================
const searchForm = reactive({ code: '', name: '' })

/** 创建时间区间：与后端 createTimeBegin / createTimeEnd 对应，未选择时两个参数都不传 */
const timeRange = ref<string[]>([])

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.code = ''
  searchForm.name = ''
  timeRange.value = []
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<EnvironmentVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    const res = await pageEnvironment({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      code: searchForm.code || undefined,
      name: searchForm.name || undefined,
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

const formData = reactive<EnvironmentDto>({
  code: '',
  name: '',
  deployStrategy: 'SINGLE_TARGET',
  batchSize: 1,
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
  formData.aiopsEnabled = row.aiopsEnabled
  formData.description = row.description || ''
  dialogVisible.value = true
}

function resetForm() {
  formData.code = ''
  formData.name = ''
  formData.deployStrategy = 'SINGLE_TARGET'
  formData.batchSize = 1
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
// 使用全局工具类 .app-container / .search-div / .tools-div / .form-tip
// 定义在 styles/index.scss 中

// 带悬浮说明的单元格：虚下划线提示这里可以悬浮，与表单 label 的「?」图标同一语义
.hover-underline {
  border-bottom: 1px dashed var(--el-border-color-darker);
  cursor: help;
}
</style>
