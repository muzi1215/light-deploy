<template>
  <div class="app-container">
    <!-- 搜索区域：文本输入框支持回车触发查询 -->
    <div class="search-div">
      <el-form :model="searchForm" inline @submit.prevent>
        <el-form-item label="任务编号">
          <el-input v-model="searchForm.taskNo" placeholder="请输入任务编号" clearable style="width: 200px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="任务类型">
          <el-select v-model="searchForm.taskType" placeholder="请选择" clearable style="width: 160px">
            <el-option label="服务器连通性测试" value="SERVER_TEST" />
            <el-option label="构建任务" value="BUILD" />
            <el-option label="发布任务" value="DEPLOY" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 130px">
            <el-option label="排队中" value="QUEUED" />
            <el-option label="执行中" value="RUNNING" />
            <el-option label="成功" value="SUCCESS" />
            <el-option label="失败" value="FAILED" />
            <el-option label="已取消" value="CANCELED" />
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
      <el-table-column prop="taskNo" label="任务编号" min-width="200" show-overflow-tooltip />
      <el-table-column prop="taskType" label="任务类型" width="140" align="center">
        <template #default="{ row }">
          <el-tag :type="TASK_TYPE_TAG[row.taskType] || 'info'">
            {{ TASK_TYPE_MAP[row.taskType] || row.taskType }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG_MAP[row.status] || 'info'">
            {{ STATUS_LABEL_MAP[row.status] || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="进度" width="150" align="center">
        <template #default="{ row }">
          <el-progress :percentage="row.progress" :status="progressStatus(row)" :stroke-width="8" />
        </template>
      </el-table-column>
      <el-table-column prop="currentStage" label="当前阶段" min-width="120" show-overflow-tooltip />
      <el-table-column prop="startedAt" label="开始时间" width="170" align="center" />
      <el-table-column prop="finishedAt" label="结束时间" width="170" align="center" />
      <el-table-column prop="errorMessage" label="失败原因" min-width="150" show-overflow-tooltip />
      <el-table-column label="操作" width="180" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Document" @click="handleShowLogs(row)">执行日志</el-button>
          <el-button v-if="!isFinal(row.status)" link type="warning" :icon="CircleClose" @click="handleCancel(row)">取消</el-button>
          <el-button v-if="isFinal(row.status)" link type="danger" :icon="Delete" @click="handleDelete(row)">删除</el-button>
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

    <!-- 执行日志对话框：按 sequenceNo 升序展示任务执行过程 -->
    <el-dialog
      v-model="logDialogVisible"
      :title="`执行日志 - ${currentTask?.taskNo || ''}`"
      width="720px"
      :close-on-click-modal="false"
    >
      <div v-loading="logLoading" class="task-log-body">
        <el-empty v-if="!logLoading && logList.length === 0" description="该任务还没有执行日志" />
        <div v-for="log in logList" :key="log.id" class="task-log-line">
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
        <el-button @click="logDialogVisible = false">关闭</el-button>
        <el-button type="primary" :loading="logLoading" @click="fetchLogs">刷新</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 任务列表页面。
 *
 * 对接后端 /task 与 /taskLog 接口。业务约定：
 * 1. 任务由系统各业务模块创建（连通性测试/构建/发布），状态机为
 *    QUEUED → RUNNING → SUCCESS/FAILED，QUEUED/RUNNING 可被人工取消；
 * 2. 终态任务（SUCCESS/FAILED/CANCELED）才显示删除按钮，非终态显示取消按钮；
 * 3. 执行日志按 sequenceNo 升序展示，与实际执行顺序一致；
 * 4. 任务执行器尚未落地，当前页面主要用于查询与人工取消。
 */
import { ref, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Document, CircleClose, Delete } from '@element-plus/icons-vue'
import { pageTask, deleteTask, cancelTask, listTaskLogs } from '@/api/task.ts'
import type { TaskVo, TaskLogVo } from '@/api/deployTypes.ts'

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

/** 任务状态展示映射（对应后端 TaskStatus 枚举） */
const STATUS_LABEL_MAP: Record<string, string> = {
  QUEUED: '排队中',
  RUNNING: '执行中',
  SUCCESS: '成功',
  FAILED: '失败',
  CANCELED: '已取消'
}

/** 任务状态对应的标签颜色（对应后端 TaskStatus 枚举） */
const STATUS_TAG_MAP: Record<string, string> = {
  QUEUED: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'danger',
  CANCELED: 'warning'
}

/** 日志级别对应的标签颜色（对应后端 TaskLogLevel 枚举） */
const LOG_LEVEL_TAG: Record<string, string> = {
  DEBUG: 'info',
  INFO: 'primary',
  WARN: 'warning',
  ERROR: 'danger'
}

// ==================== 搜索 ====================
const searchForm = reactive({ taskNo: '', taskType: '', status: '' })

/** 触发查询：页码重置为第一页后重新加载数据 */
function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

/** 重置全部搜索条件并回到第一页查询 */
function handleReset() {
  searchForm.taskNo = ''
  searchForm.taskType = ''
  searchForm.status = ''
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
      status: searchForm.status || undefined
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()

/**
 * 判断任务是否为终态（成功/失败/已取消）。
 *
 * @param status 任务状态编码，与后端 TaskStatus 枚举一致
 * @returns 终态返回 true，终态任务只显示删除按钮
 */
function isFinal(status: string): boolean {
  return status === 'SUCCESS' || status === 'FAILED' || status === 'CANCELED'
}

/**
 * 计算进度条展示状态。
 *
 * @param row 当前行任务数据
 * @returns 成功返回 success，失败返回 exception，其余不指定
 */
function progressStatus(row: TaskVo): string | undefined {
  if (row.status === 'SUCCESS') return 'success'
  if (row.status === 'FAILED') return 'exception'
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
// 执行日志展示区：等宽字体 + 终端风格底色，模拟控制台输出
.task-log-body {
  max-height: 420px;
  overflow-y: auto;
  background: #1e1e1e;
  border-radius: 4px;
  padding: 12px;
  font-family: 'JetBrains Mono', Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.8;
}

.task-log-line {
  color: #d4d4d4;
  word-break: break-all;
}

.log-seq {
  color: #6a9955;
  margin-right: 8px;
}

.log-level {
  margin-right: 8px;
}

.log-stage {
  color: #9cdcfe;
  margin-right: 8px;
}

.log-time {
  color: #808080;
  margin-left: 8px;
  float: right;
}
</style>
