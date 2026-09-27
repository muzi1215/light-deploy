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
        <CreateTimeRange v-model="timeRange" />
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
          <el-progress :percentage="row.progress" :status="rowProgressStatus(row)" :stroke-width="8" />
        </template>
      </el-table-column>
      <el-table-column prop="currentStage" label="当前阶段" min-width="120" show-overflow-tooltip align="center" />
      <el-table-column prop="createTime" label="创建时间" min-width="170" align="center" />
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

    <!-- 执行日志对话框：与构建、发布、连通性测试、触发构建四处共用 useTaskLogViewer -->
    <!-- 关闭不拦截：只停前端轮询，后端任务照跑，重新打开会重新拉全量日志（AGENTS.md §20.9） -->
    <el-dialog
      v-model="logVisible"
      :title="`执行日志 - ${task?.taskNo || ''}`"
      width="800px"
      :close-on-click-modal="false"
      @closed="stop"
    >
      <!-- 任务状态区：状态标签、任务编号、当前阶段，与下方日志区留出间隔 -->
      <div class="test-summary">
        <el-tag :type="getTaskStatusStyle(task?.status).tag">
          {{ getTaskStatusStyle(task?.status).label }}
        </el-tag>
        <span class="test-meta">任务编号：{{ task?.taskNo || '-' }}</span>
        <span v-if="task?.currentStage" class="test-meta">当前阶段：{{ task.currentStage }}</span>
      </div>
      <el-progress
        :percentage="task?.progress ?? 0"
        :status="progressStatus"
        :stroke-width="8"
        style="margin-bottom: 16px"
      />
      <el-alert
        v-if="task?.errorMessage"
        :title="task.errorMessage"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />
      <div ref="bodyRef" class="log-console">
        <el-empty v-if="logs.length === 0" description="正在等待执行日志…" :image-size="60" />
        <div v-for="log in logs" :key="log.id" class="log-line">
          <span class="log-seq">#{{ log.sequenceNo }}</span>
          <el-tag size="small" :type="LOG_LEVEL_TAG[log.logLevel] || 'info'" class="log-level">
            {{ log.logLevel }}
          </el-tag>
          <span v-if="log.stage" class="log-stage">[{{ log.stage }}]</span>
          <span class="log-content">{{ log.content }}</span>
          <span class="log-time">{{ formatLogTime(log.createTime) }}</span>
        </div>
      </div>
      <template #footer>
        <el-text v-if="running" size="small" type="info" style="margin-right: 12px">任务执行中，关闭窗口不影响执行</el-text>
        <el-button size="small" :icon="Download" :loading="downloading" @click="download">下载日志</el-button>
        <el-button size="small" type="primary" @click="closeLog">关 闭</el-button>
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
 * 3. 执行日志对话框与其余四处日志视图共用 useTaskLogViewer：增量拉取实时日志、可直接下载导出，
 *    任务执行中禁止关闭容器（日志还在输出，关掉就再也看不到这段过程）；
 * 4. 任务执行器已落地：任务会被自动领取执行，状态与进度由执行器更新，页面按 1.5 秒轮询；
 * 5. 状态与日志级别的文案/颜色统一取自 @/constants/taskStatus.ts，不在页面内重复定义。
 */
import { ref, reactive, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Document, Download, CircleClose, Delete } from '@element-plus/icons-vue'
import { pageTask, deleteTask, cancelTask } from '@/api/task.ts'
import type { TaskVo } from '@/api/deployTypes.ts'
import { LOG_LEVEL_TAG, getTaskStatusStyle, isTaskFinal } from '@/constants/taskStatus.ts'
import { useTaskLogViewer, formatLogTime } from '@/composables/useTaskLogViewer.ts'

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

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

/** 创建时间区间：与后端 createTimeBegin / createTimeEnd 对应，未选择时两个参数都不传 */
const timeRange = ref<string[]>([])

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
  timeRange.value = []
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
      status: searchForm.status,
      ...splitTimeRange(timeRange.value),
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()

// 本页被 keep-alive 缓存（切走再切回不重跑 setup），而任务几乎都是平台自己产生的
// （构建、发布各建一条任务），回到本页时重新拉一次：
// 只靠初次的 setup 拉取，会让刚触发的任务与执行中的进度一直不可见。
// 首次激活发生在挂载之后，此时上面已经拉过数据，跳过以免重复请求。
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  fetchData()
})

/**
 * 计算表格列的进度条展示状态。
 *
 * 与日志对话框里查看器返回的 progressStatus 不是同一个：那个绑的是对话框内当前任务，
 * 这个绑的是表格行，因此单独起名避免冲突。
 *
 * @param row 当前行任务数据
 * @returns 成功返回 success，失败返回 exception，其余不指定
 */
function rowProgressStatus(row: TaskVo): string | undefined {
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
const logVisible = ref(false)

/** 日志查看器：轮询、增量锚点、自动滚动、下载与运行中禁止关闭统一由它负责 */
const { bodyRef, logs, task, running, downloading, progressStatus, start, stop, download } =
  useTaskLogViewer()

/**
 * 打开执行日志对话框。
 *
 * 任务状态与进度用列表行的值做初值，首次拉取返回后立刻以服务端为准；
 * 任务执行中本页保持轮询，容器也不会放行关闭。
 *
 * @param row 目标任务行数据，日志按该任务 ID 查询
 */
function handleShowLogs(row: TaskVo) {
  logVisible.value = true
  start({ taskId: row.id, taskNo: row.taskNo, status: row.status, progress: row.progress })
}

/** 关闭执行日志对话框；@closed 会触发 stop 停掉轮询，后端任务不受影响 */
function closeLog() {
  logVisible.value = false
}
</script>

<style lang="scss" scoped>
// 执行日志控制台样式统一定义在 styles/index.scss 的「任务执行日志控制台」小节，
// 任务列表页与服务器连通性测试抽屉共用，页面不重复定义
</style>
