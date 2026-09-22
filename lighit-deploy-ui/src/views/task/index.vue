<template>
  <div class="app-container">
    <!-- 搜索区域：文本输入框支持回车触发查询 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="任务编号">
          <el-input v-model="searchForm.taskNo" placeholder="请输入任务编号" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="任务类型">
          <el-select v-model="searchForm.taskType" placeholder="请选择" clearable style="width: 160px">
            <el-option label="服务器连通性测试" value="SERVER_TEST" />
            <el-option label="构建任务" value="BUILD" />
            <el-option label="发布任务" value="DEPLOY" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 160px">
            <el-option label="排队中" :value="0" />
            <el-option label="执行中" :value="1" />
            <el-option label="成功" :value="2" />
            <el-option label="失败" :value="3" />
            <el-option label="已取消" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="taskNo" label="任务编号" min-width="200" show-overflow-tooltip align="center" />
      <el-table-column prop="taskType" label="任务类型" min-width="140" align="center">
        <template #default="{ row }">
          <el-tag :type="TASK_TYPE_TAG[row.taskType] || 'info'">
            {{ TASK_TYPE_MAP[row.taskType] || row.taskType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" min-width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="getTaskStatusStyle(row.status).tag">
            {{ getTaskStatusStyle(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" min-width="150" align="center">
        <template #default="{ row }">
          <el-progress :percentage="row.progress" :status="progressStatus(row)" :stroke-width="8" />
        </template>
      </el-table-column>
      <el-table-column prop="currentStage" label="当前阶段" min-width="120" show-overflow-tooltip align="center" />
      <el-table-column prop="startedAt" label="开始时间" min-width="170" align="center" />
      <el-table-column prop="finishedAt" label="结束时间" min-width="170" align="center" />
      <el-table-column prop="errorMessage" label="失败原因" min-width="150" show-overflow-tooltip align="center" />
      <el-table-column label="操作" min-width="260" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Document" @click="handleShowLogs(row)">执行日志</el-button>
          <el-button v-if="!isTaskFinal(row.status)" link type="warning" :icon="CircleClose" @click="handleCancel(row)">取消</el-button>
          <el-button v-if="isTaskFinal(row.status)" link type="danger" :icon="Delete" @click="handleDelete(row)">删除</el-button>
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

    <!-- 执行日志对话框：按 sequenceNo 升序展示任务执行过程；宽度与页脚按钮沿用统一弹窗规格（AGENTS.md §20.2） -->
    <el-dialog
      v-model="logDialogVisible"
      :title="`执行日志 - ${currentTask?.taskNo || ''}`"
      width="800px"
      :close-on-click-modal="false"
    >
      <div v-loading="logLoading" class="log-console" style="margin-top: 16px">
        <el-empty v-if="!logLoading && logList.length === 0" description="该任务还没有执行日志" />
        <div v-for="log in logList" :key="log.id" class="log-line">
          <span class="log-seq">#{{ log.sequenceNo }}</span>
          <el-tag size="small" :type="LOG_LEVEL_TAG[log.logLevel] || 'info'" class="log-level">
            {{ log.logLevel }}
          </el-tag>
          <span v-if="log.stage" class="log-stage">[{{ log.stage }}]</span>
          <span class="log-content">{{ log.content }}</span>
          <span class="log-time">{{ log.createTime }}</span>
        </div>
      </div>
      <template #footer>
        <el-button size="small" @click="logDialogVisible = false">关 闭</el-button>
        <el-button type="primary" size="small" :loading="logLoading" @click="fetchLogs">刷 新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 任务列表页面。
 *
 * 对接后端 /task 与 /taskLog 接口。业务约定：
 * 1. 任务由系统各业务模块创建（连通性测试/构建/发布），状态为数字编码：
 *    0 排队中 → 1 执行中 → 2 成功 / 3 失败，0/1 可被人工取消，4 为已取消；
 * 2. 终态任务（2 成功 / 3 失败 / 4 已取消）才显示删除按钮，非终态显示取消按钮；
 * 3. 执行日志按 sequenceNo 升序展示，与实际执行顺序一致；
 * 4. 任务执行器已落地：任务会被自动领取执行（当前支持 SERVER_TEST），状态与进度由执行器更新；
 * 5. 状态与日志级别的文案/颜色统一取自 @/constants/taskStatus.ts，不在页面内重复定义。
 */
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Document, CircleClose, Delete } from '@element-plus/icons-vue'
import { pageTask, deleteTask, cancelTask, listTaskLogs } from '@/api/task.ts'
import type { TaskVo, TaskLogVo } from '@/api/deployTypes.ts'
import { LOG_LEVEL_TAG, getTaskStatusStyle, isTaskFinal } from '@/constants/taskStatus.ts'

defineOptions({ name: 'TaskList' })

/** 任务类型展示映射（对应后端 TaskType 枚举） */
const TASK_TYPE_MAP: Record<string, string> = {
  SERVER_TEST: '连通性测试',
  BUILD: '构建任务',
  DEPLOY: '发布任务'
}

/** 任务类型对应的标签颜色（对应后端 TaskType 枚举） */
const TASK_TYPE_TAG: Record<string, string> = {
  SERVER_TEST: 'info',
  BUILD: 'primary',
  DEPLOY: 'warning'
}


// ==================== 搜索 ====================
const searchForm = reactive({ taskNo: '', taskType: '', status: undefined as number | undefined })

/** 触发查询：页码重置为第一页后重新加载数据 */
function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

/** 重置全部搜索条件并回到第一页查询 */
function handleReset() {
  searchForm.taskNo = ''
  searchForm.taskType = ''
  searchForm.status = undefined
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<TaskVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

/** 加载任务分页数据，搜索条件为空时不传给后端 */
async function fetchData() {
  loading.value = true
  try {
    const res = await pageTask({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      taskNo: searchForm.taskNo || undefined,
      taskType: searchForm.taskType || undefined,
      status: searchForm.status
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()

/**
 * 计算进度条展示状态。
 *
 * @param row 当前行任务数据
 * @returns 成功返回 success，失败返回 exception，其余不指定
 */
function progressStatus(row: TaskVo): string | undefined {
  if (row.status === 2) return 'success'
  if (row.status === 3) return 'exception'
  return undefined
}

// ==================== 取消与删除 ====================

/**
 * 人工取消任务。
 *
 * @param row 待取消的任务行数据，排队中/执行中状态才允许取消
 */
async function handleCancel(row: TaskVo) {
  await ElMessageBox.confirm(`确定要取消任务「${row.taskNo}」吗？`, '提示', { type: 'warning' })
  await cancelTask(row.id)
  ElMessage.success('任务已取消')
  fetchData()
}

/**
 * 删除终态任务。
 *
 * @param row 待删除的任务行数据，仅终态任务可删除（软删除）
 */
async function handleDelete(row: TaskVo) {
  await ElMessageBox.confirm(`确定要删除任务「${row.taskNo}」吗？`, '提示', { type: 'warning' })
  await deleteTask(row.id)
  ElMessage.success('删除成功')
  fetchData()
}

// ==================== 执行日志 ====================
const logDialogVisible = ref(false)
const logLoading = ref(false)
const logList = ref<TaskLogVo[]>([])
const currentTask = ref<TaskVo | null>(null)

/**
 * 打开执行日志对话框并加载日志。
 *
 * @param row 目标任务行数据，日志按该任务 ID 查询
 */
async function handleShowLogs(row: TaskVo) {
  currentTask.value = row
  logDialogVisible.value = true
  await fetchLogs()
}

/** 加载当前任务的执行日志，后端按 sequenceNo 升序返回 */
async function fetchLogs() {
  if (!currentTask.value) return
  logLoading.value = true
  try {
    const res = await listTaskLogs({ taskId: currentTask.value.id })
    logList.value = res.data || []
  } finally {
    logLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
// 执行日志控制台样式统一定义在 styles/index.scss 的「任务执行日志控制台」小节，
// 任务列表页与服务器连通性测试抽屉共用，页面不重复定义
</style>
