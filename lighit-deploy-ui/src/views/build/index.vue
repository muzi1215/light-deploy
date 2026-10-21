<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="项目">
          <el-select v-model="searchForm.projectId" placeholder="请选择项目" clearable filterable style="width: 180px">
            <el-option v-for="item in projectOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
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
        <CreateTimeRange v-model="timeRange" />
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
        <el-form-item>
          <!-- 上传产物：面向「本地已打包好」的 JAR / 静态产物项目，不走 clone + 构建 -->
          <el-button type="success" :icon="UploadFilled" @click="openUpload">上传产物</el-button>
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
      <el-table-column prop="createTime" label="创建时间" min-width="170" align="center" />
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

    <!-- 上传产物对话框 -->
    <!-- 场景：前端 npm run build 后的 dist 包、本机打好的 jar，不走 Git 拉取与构建流程。
         后端只做「存产物到构建机 + 登记版本」，因此这里同步等待即可，不必再轮询任务日志。 -->
    <el-dialog v-model="uploadVisible" title="上传产物" width="720px" :close-on-click-modal="false" @closed="resetUpload">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        style="margin-bottom: 16px"
        title="上传前请确认为本地已构建好的产物"
        description="仅支持来源为「本地上传」、构建模式为「仅发布产物」的 JAR / 静态产物项目；DOCKER 的产物是镜像，请通过构建推送到镜像仓库。"
      />
      <el-form ref="uploadFormRef" :model="uploadForm" label-width="110px">
        <el-form-item label="目标项目" prop="projectId" required>
          <el-select v-model="uploadForm.projectId" placeholder="请选择项目" filterable style="width: 100%">
            <el-option
              v-for="item in uploadableProjects"
              :key="item.id"
              :label="`${item.name}（${item.code}）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="版本序号">
          <el-input-number v-model="uploadForm.versionSeq" :min="1" controls-position="right" style="width: 200px" />
          <span class="muted" style="margin-left: 12px">留空由服务端自动取号（项目内最大序号 + 1）</span>
        </el-form-item>
        <el-form-item label="产物文件" required>
          <!-- 手动上传：auto-upload=false，由提交按钮统一发 multipart，
               避免选完文件就立刻把请求打出去（此时项目可能还没选） -->
          <el-upload
            ref="uploadRef"
            :auto-upload="false"
            :limit="1"
            :file-list="uploadFileList"
            accept=".zip,.tar.gz,.tgz,.jar"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
          >
            <el-button :icon="UploadFilled">选择文件</el-button>
            <template #tip>
              <div class="muted" style="line-height: 1.6">
                支持 .zip / .tar.gz / .tgz（静态产物）与 .jar（JAR 运行时），单文件上限 500MB。<br />
                静态产物<b>推荐进入产物目录后再打包</b>（<code>cd dist &amp;&amp; zip -r app.zip .</code>），
                解压后直接是 index.html；在父目录打包（<code>zip -r app.zip aiops</code>）多出一层时，
                平台会自动剥离这一层，不会把站点发到 static_root 的下一级。
              </div>
            </template>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">上传并登记版本</el-button>
      </template>
    </el-dialog>

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
import { Search, Refresh, Delete, Document, Monitor, Download, UploadFilled } from '@element-plus/icons-vue'
import { pageBuild, deleteBuild, createBuild, getBuildArtifacts, uploadArtifact } from '@/api/build.ts'
import type { BuildVo, BuildArtifactVo, ProjectVo } from '@/api/deployTypes.ts'
import { listProjects } from '@/api/project.ts'
// 任务状态与日志级别的文案、颜色集中在常量模块，避免与任务列表页出现两套口径
import { LOG_LEVEL_TAG, getTaskStatusStyle } from '@/constants/taskStatus.ts'
// 构建状态映射同样集中在常量模块
import { getBuildStatusStyle } from '@/constants/buildStatus.ts'
// 日志查看器与日志时间格式化：5 处日志视图共用同一份实现
import { useTaskLogViewer, formatLogTime } from '@/composables/useTaskLogViewer.ts'

/** 构建模式展示映射（与后端 BuildMode 枚举一致，仅用于列表展示） */
const BUILD_MODE_MAP: Record<string, string> = {
  PROJECT_DOCKERFILE: '项目Dockerfile',
  LAYERED_DOCKER: '分层构建',
  SCRIPT_DEPLOY: '脚本部署',
  ARTIFACT_ONLY: '仅发布产物',
  JAR_PREBUILD: 'JAR预构建'
}

const BUILD_MODE_OPTIONS = Object.entries(BUILD_MODE_MAP).map(([value, label]) => ({ value, label }))

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

defineOptions({ name: 'BuildList' })

// ==================== 搜索 ====================
const searchForm = reactive({ projectId: '', buildMode: '', status: undefined as number | undefined })

/** 项目下拉选项：搜索区「项目」筛选用，加载全部项目即可（禁用项对筛选无意义）。 */
const projectOptions = ref<ProjectVo[]>([])
function loadProjectOptions() {
  listProjects()
    .then((res) => { projectOptions.value = res.data || [] })
    .catch(() => { projectOptions.value = [] })
}
loadProjectOptions()

/** 创建时间区间：与后端 createTimeBegin / createTimeEnd 对应，未选择时两个参数都不传 */
const timeRange = ref<string[]>([])

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.projectId = ''
  searchForm.buildMode = ''
  searchForm.status = undefined
  timeRange.value = []
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
      projectId: searchForm.projectId || undefined,
      buildMode: searchForm.buildMode || undefined,
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
/**
 * 上传对话框状态。
 *
 * 项目下拉在打开对话框时才拉取：可用项目本身很少变动，
 * 放在页面初始化里会为一次大概率不发生的动作多加一次请求。
 */
const uploadVisible = ref(false)
const uploading = ref(false)
const uploadFormRef = ref()
const uploadRef = ref()
const uploadableProjects = ref<ProjectVo[]>([])
/** 当前选中的原始文件：独立于 el-upload 的 file-list，避免依赖组件内部状态提交 */
const uploadFile = ref<File | null>(null)
const uploadFileList = ref<any[]>([])
const uploadForm = reactive<{ projectId: string; versionSeq?: number }>({ projectId: '', versionSeq: undefined })

/**
 * 打开上传对话框并加载候选项目。
 *
 * 只保留满足后端准入条件的项目（来源 UPLOAD + 构建模式 ARTIFACT_ONLY + 运行时 JAR/STATIC）：
 * 让不满足条件的项目出现在下拉里，用户选完提交才被拒，是一次纯粹的浪费。
 */
async function openUpload() {
  uploadVisible.value = true
  if (uploadableProjects.value.length > 0) {
    return
  }
  try {
    const res = await listProjects()
    uploadableProjects.value = (res.data || []).filter(
      (item: ProjectVo) =>
        item.status === 1 &&
        item.sourceType === 'UPLOAD' &&
        item.buildMode === 'ARTIFACT_ONLY' &&
        (item.runtimeType === 'JAR' || item.runtimeType === 'STATIC')
    )
    if (uploadableProjects.value.length === 0) {
      ElMessage.warning('暂无可用项目：上传产物要求项目来源为「本地上传」、构建模式为「仅发布产物」，且运行时为 JAR / 静态产物')
    }
  } catch (e) {
    ElMessage.error('加载项目列表失败')
  }
}

/** 记录用户选择的文件；多文件时只保留最后一个，后端每次只登记一个产物 */
function handleFileChange(file: any) {
  uploadFile.value = file.raw as File
  uploadFileList.value = [file]
}

/** 移除已选文件 */
function handleFileRemove() {
  uploadFile.value = null
  uploadFileList.value = []
}

/** 关闭对话框后清空表单，避免残留的文件被下一次上传误带出去 */
function resetUpload() {
  uploadFile.value = null
  uploadFileList.value = []
  uploadForm.projectId = ''
  uploadForm.versionSeq = undefined
}

/**
 * 提交上传。
 *
 * 前端只做三件必要校验（项目、文件、扩展名），其余交给后端：
 * 版本号是否占用、构建机是否可用这些服务端状态，前端判断必然滞后，
 * 与其在两边各写一套规则，不如让后端返回明确的中文错误直接展示。
 */
async function submitUpload() {
  if (!uploadForm.projectId) {
    ElMessage.warning('请选择目标项目')
    return
  }
  if (!uploadFile.value) {
    ElMessage.warning('请选择要上传的产物文件')
    return
  }
  const formData = new FormData()
  formData.append('projectId', uploadForm.projectId)
  formData.append('file', uploadFile.value)
  if (uploadForm.versionSeq) {
    formData.append('versionSeq', String(uploadForm.versionSeq))
  }
  uploading.value = true
  try {
    await uploadArtifact(formData)
    ElMessage.success('产物上传成功，已登记为可发布版本')
    uploadVisible.value = false
    fetchData()
  } catch (e: any) {
    // 后端已给出可读的错误文案（版本占用 / 构建机未配置等），这里不重复包装。
    ElMessage.error(e?.message || '上传失败')
  } finally {
    uploading.value = false
  }
}

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
