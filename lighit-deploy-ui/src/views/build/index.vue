<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="项目名称">
          <el-input v-model="searchForm.projectName" placeholder="请输入项目名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="构建模式">
          <el-select v-model="searchForm.buildMode" placeholder="请选择" clearable style="width: 160px">
            <el-option v-for="item in BUILD_MODE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 160px">
            <el-option label="排队中" :value="0" />
            <el-option label="构建中" :value="1" />
            <el-option label="成功" :value="2" />
            <el-option label="失败" :value="3" />
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
      <el-table-column prop="projectName" label="项目" min-width="150" show-overflow-tooltip align="center" />
      <el-table-column prop="buildMode" label="构建模式" min-width="130" align="center">
        <template #default="{ row }">
          <el-tag>{{ BUILD_MODE_MAP[row.buildMode] || row.buildMode }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="buildServerName" label="构建机" min-width="140" show-overflow-tooltip align="center" />
      <el-table-column prop="gitCommit" label="Git提交" min-width="120" show-overflow-tooltip align="center">
        <template #default="{ row }">
          <span v-if="row.gitCommit">{{ row.gitCommit }}</span>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" min-width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="getBuildStatusStyle(row.status).tag">
            {{ row.statusText || getBuildStatusStyle(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="startedAt" label="开始时间" min-width="180" align="center" />
      <el-table-column prop="finishedAt" label="结束时间" min-width="180" align="center" />
      <el-table-column prop="taskNo" label="关联任务" min-width="160" show-overflow-tooltip align="center" />
      <el-table-column label="操作" min-width="280" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Document" @click="handleViewArtifacts(row)">产物</el-button>
          <el-button link type="success" :icon="Monitor" @click="handleViewLog(row)">构建日志</el-button>
          <el-button link type="warning" :icon="Refresh" @click="handleRebuild(row)">重新构建</el-button>
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

    <!-- 构建日志抽屉：上方任务状态与进度，下方实时日志控制台 -->
    <!-- 关闭不拦截：只停前端轮询，后端构建任务照跑，重新打开会重新拉全量日志 -->
    <el-drawer
      v-model="logDrawerVisible"
      :title="`构建日志 - ${logBuild?.projectName || ''}`"
      size="900px"
      :close-on-click-modal="false"
      @closed="stop"
    >
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
        <el-button size="small" @click="closeLog">关 闭</el-button>
      </template>
    </el-drawer>

    <!-- 产物抽屉：展示本次构建产出的镜像 / JAR / 静态产物 -->
    <el-drawer
      v-model="artifactDrawerVisible"
      :title="`构建产物 - ${artifactBuild?.projectName || ''}`"
      size="900px"
      :close-on-click-modal="false"
    >
      <el-table v-loading="artifactLoading" :data="artifacts" border stripe>
        <el-table-column prop="artifactTypeText" label="类型" min-width="120" align="center" />
        <el-table-column prop="artifactName" label="名称" min-width="180" show-overflow-tooltip align="center" />
        <el-table-column prop="artifactTag" label="标签" min-width="190" show-overflow-tooltip align="center" />
        <el-table-column prop="artifactDigest" label="摘要 / SHA256" min-width="200" show-overflow-tooltip align="center">
          <template #default="{ row }">
            <span v-if="row.artifactDigest" class="digest">{{ row.artifactDigest }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!artifactLoading && artifacts.length === 0" description="本次构建暂无产物记录" />
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 构建管理页面。
 *
 * 对接后端 /build 标准六接口（含 /{id}/artifacts）。业务约定：
 * 1. 构建是异步流程：POST /build 创建 build 并生成 BUILD 异步任务后立即返回，
 *    真正的克隆 / 构建 / 推送由后端任务执行器完成，页面用 /taskLog/pull 增量拉取实时日志；
 * 2. 构建成功后端自动生成一条「可发布」发布版本，产物列表即本次构建产出；
 * 3. 重新构建会针对同一项目再次触发一次构建，并立即打开日志抽屉；
 * 4. 删除只放开给终态（成功 / 失败）构建，排队中 / 构建中的记录不允许删除；
 * 5. 日志抽屉与其余四处日志视图共用 useTaskLogViewer：增量拉取、下载导出、
 *    任务执行中禁止关闭容器；日志拉取使用「顺序号锚点 + 增量返回」，不在前端做去重（AGENTS.md §20.9）。
 */
import { ref, reactive, onActivated } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Delete, Document, Monitor, Download } from '@element-plus/icons-vue'
import { pageBuild, deleteBuild, createBuild, getBuildArtifacts } from '@/api/build.ts'
import type { BuildVo, BuildArtifactVo } from '@/api/deployTypes.ts'
// 任务状态与日志级别的文案、颜色集中在常量模块，避免与任务列表页出现两套口径
import { LOG_LEVEL_TAG, getTaskStatusStyle } from '@/constants/taskStatus.ts'
// 构建状态映射同样集中在常量模块
import { getBuildStatusStyle } from '@/constants/buildStatus.ts'
// 日志查看器与日志时间格式化：5 处日志视图共用同一份实现
import { useTaskLogViewer, formatLogTime } from '@/composables/useTaskLogViewer.ts'

/** 构建模式展示映射（与后端 BuildMode 枚举一致，仅用于列表展示） */
const BUILD_MODE_MAP: Record<string, string> = {
  PLATFORM_TEMPLATE: '平台模板',
  PROJECT_DOCKERFILE: '项目Dockerfile',
  LAYERED_DOCKER: '分层构建',
  SCRIPT_DEPLOY: '脚本部署',
  ARTIFACT_ONLY: '仅发布产物'
}

const BUILD_MODE_OPTIONS = Object.entries(BUILD_MODE_MAP).map(([value, label]) => ({ value, label }))

defineOptions({ name: 'BuildList' })

// ==================== 搜索 ====================
const searchForm = reactive({ projectName: '', buildMode: '', status: undefined as number | undefined })

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.projectName = ''
  searchForm.buildMode = ''
  searchForm.status = undefined
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<BuildVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    const res = await pageBuild({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      projectName: searchForm.projectName || undefined,
      buildMode: searchForm.buildMode || undefined,
      status: searchForm.status
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()

// 本页被 keep-alive 缓存（切走再切回不重跑 setup），构建状态在服务端持续变化，
// 回到本页时重新拉一次，避免列表停留在离开时的旧状态。
// 首次激活发生在挂载之后，此时上面已经拉过数据，跳过以免重复请求。
let activatedOnce = false
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  fetchData()
})

// ==================== 删除 ====================
/**
 * 删除构建记录（逻辑删除）。
 *
 * @param row 待删除的构建记录；仅终态（成功 / 失败）后端允许删除，非终态会返回 DATA_CONFLICT
 */
async function handleDelete(row: BuildVo) {
  await ElMessageBox.confirm(`确定要删除构建记录「${row.projectName || row.id}」吗？仅终态构建可删除。`, '提示', { type: 'warning' })
  await deleteBuild(row.id)
  ElMessage.success('删除成功')
  fetchData()
}

// ==================== 重新构建 ====================
/**
 * 针对同一项目再次触发一次构建，并立即打开日志抽屉。
 *
 * @param row 当前构建记录，取其 projectId 作为触发参数
 */
async function handleRebuild(row: BuildVo) {
  try {
    const res = await createBuild({ projectId: row.projectId })
    ElMessage.success('已触发重新构建')
    openLog(res.data)
  } catch (error: any) {
  }
}

// ==================== 构建日志 ====================
const logDrawerVisible = ref(false)
/** 当前查看日志的构建记录，用于抽屉标题 */
const logBuild = ref<BuildVo | null>(null)

/** 日志查看器：轮询、增量锚点、自动滚动、下载与运行中禁止关闭统一由它负责 */
const { bodyRef, logs, task, running, downloading, progressStatus, start, stop, download } =
  useTaskLogViewer()

/**
 * 打开日志抽屉并拉取实时日志。
 *
 * @param build 构建记录，需携带 taskId 才能拉取日志（缺失时查看器会给出提示）
 */
function openLog(build: BuildVo) {
  logBuild.value = build
  logDrawerVisible.value = true
  start({ taskId: build.taskId, taskNo: build.taskNo, status: build.status })
}

function handleViewLog(row: BuildVo) {
  openLog(row)
}

/** 关闭日志抽屉；@closed 会触发 stop 停掉轮询，后端构建任务不受影响 */
function closeLog() {
  logDrawerVisible.value = false
}

// ==================== 构建产物 ====================
const artifactDrawerVisible = ref(false)
const artifactLoading = ref(false)
/** 当前查看产物的构建记录，用于抽屉标题 */
const artifactBuild = ref<BuildVo | null>(null)
const artifacts = ref<BuildArtifactVo[]>([])

/**
 * 打开产物抽屉并加载本次构建的产物清单。
 *
 * @param row 构建记录，取其 id 查询产物
 */
async function handleViewArtifacts(row: BuildVo) {
  artifactBuild.value = row
  artifactDrawerVisible.value = true
  artifactLoading.value = true
  artifacts.value = []
  try {
    const res = await getBuildArtifacts(row.id)
    artifacts.value = res.data
  } finally {
    artifactLoading.value = false
  }
}
</script>

<style lang="scss" scoped>
// 日志控制台复用全局 .log-console / .log-line 样式，与服务器连通性测试抽屉保持一致

// 抽屉顶部任务状态区：与下方日志控制台留出统一间隔
.test-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.test-meta {
  font-size: 13px;
  color: var(--text-secondary);
}

// 摘要 / 短哈希等次要文字
.muted {
  color: var(--text-secondary);
}

// 镜像摘要 / 文件 SHA256 较长，允许换行避免横向撑开
.digest {
  word-break: break-all;
  font-family: var(--font-mono, monospace);
  font-size: 12px;
}
</style>
