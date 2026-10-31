<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="项目编码">
          <el-input v-model="searchForm.code" placeholder="请输入项目编码" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="项目名称">
          <el-input v-model="searchForm.name" placeholder="请输入项目名称" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="运行时">
          <el-select v-model="searchForm.runtimeType" placeholder="请选择" clearable style="width: 160px">
            <el-option label="Docker容器" value="DOCKER" />
            <el-option label="Java JAR" value="JAR" />
            <el-option label="静态产物" value="STATIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="构建模式">
          <el-select v-model="searchForm.buildMode" placeholder="请选择" clearable style="width: 160px">
            <!-- 选项取自 BUILD_MODE_OPTIONS，与新增/编辑表单共用同一份取值，避免两处枚举漂移 -->
            <el-option v-for="item in BUILD_MODE_OPTIONS" :key="item.value" :label="item.shortLabel" :value="item.value" />
          </el-select>
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
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增项目</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="code" label="项目编码" min-width="170" show-overflow-tooltip align="center" />
      <el-table-column prop="name" label="项目名称" min-width="160" show-overflow-tooltip align="center" />
      <el-table-column prop="runtimeType" label="运行时" min-width="110" align="center">
        <template #default="{ row }">
          <el-tag>{{ RUNTIME_TYPE_MAP[row.runtimeType] || row.runtimeType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceType" label="代码来源" min-width="100" align="center">
        <template #default="{ row }">
          {{ row.sourceType === 'GIT' ? 'Git仓库' : '本地上传' }}
        </template>
      </el-table-column>
      <el-table-column prop="buildMode" label="构建模式" min-width="130" align="center">
        <template #default="{ row }">
          {{ BUILD_MODE_MAP[row.buildMode] || row.buildMode }}
        </template>
      </el-table-column>
      <el-table-column prop="gitBranch" label="Git分支" min-width="120" show-overflow-tooltip align="center" />
      <el-table-column prop="buildServerName" label="构建机" min-width="140" show-overflow-tooltip align="center">
        <template #default="{ row }">
          <span v-if="row.buildServerName">{{ row.buildServerName }}</span>
          <!-- 未绑定时明确写出会走系统默认构建机，避免误以为配置缺失 -->
          <el-text v-else type="info" size="small">默认构建机</el-text>
        </template>
      </el-table-column>
      <el-table-column prop="retainReleaseCount" label="保留版本" min-width="90" align="center" />
      <el-table-column prop="status" label="状态" min-width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="200" align="center" />
      <el-table-column prop="description" label="描述" min-width="150" align="center" show-overflow-tooltip />
      <el-table-column label="操作" min-width="380" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="info" :icon="View" @click="handleDetail(row)">详情</el-button>
          <el-button link type="primary" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
          <el-button link type="success" :icon="Connection" @click="handleConnectivityTest(row)">连通性测试</el-button>
          <el-button link type="warning" :icon="VideoPlay" @click="handleBuild(row)">构建</el-button>
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

    <!-- 构建触发对话框：未触发时选择构建机，触发后切换为实时日志控制台 -->
    <!-- 新增 / 编辑 / 详情已迁移到独立页面（/project/add、/project/edit/:id、/project/detail/:id） -->
    <!-- 关闭不拦截：只停前端轮询，后端构建任务照跑，重新打开会重新拉全量日志 -->
    <el-dialog
      v-model="buildDialogVisible"
      title="触发构建"
      width="800px"
      :close-on-click-modal="false"
      @close="stop"
    >
      <!-- 配置阶段：展示项目基本信息并选择专用构建机（可选），未触发前显示 -->
      <div v-if="!buildStarted">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="项目">{{ buildProject?.name }}</el-descriptions-item>
          <el-descriptions-item label="项目编码">{{ buildProject?.code }}</el-descriptions-item>
          <el-descriptions-item label="运行时">{{ RUNTIME_TYPE_MAP[buildProject?.runtimeType || ''] || buildProject?.runtimeType }}</el-descriptions-item>
          <el-descriptions-item label="构建模式">{{ BUILD_MODE_MAP[buildProject?.buildMode || ''] || buildProject?.buildMode }}</el-descriptions-item>
        </el-descriptions>
        <el-form label-width="110px" style="margin-top:16px">
          <el-form-item label="版本序号">
            <el-input-number
              v-model="versionSeq"
              :min="1"
              :step="1"
              :precision="0"
              controls-position="right"
              placeholder="留空自动取号"
              style="width:100%"
            />
            <span class="form-tip">留空由平台自动递增取号；填写将生成 {项目编码}-v{序号}-{提交短码}，且需该项目内未被占用</span>
          </el-form-item>
          <el-form-item label="构建机">
            <el-select v-model="buildServerId" placeholder="不指定则使用默认构建机" clearable style="width:100%">
              <el-option v-for="s in buildServers" :key="s.id" :label="`${s.serverName}（${s.host}）`" :value="s.id" />
            </el-select>
            <span class="form-tip">指定专用构建机；留空由后端解析项目默认构建机</span>
          </el-form-item>
          <el-form-item v-if="buildProject?.buildMode === 'LAYERED_DOCKER'" label="重构基础镜像">
            <el-switch v-model="rebuildBaseImage" active-text="重建" inactive-text="复用" />
            <span class="form-tip">分层构建专属：默认「复用」仓库中已有的稳定基础镜像（{镜像仓库}:base），省时省带宽；
              基础层内容变更、或首次使用稳定 base 前，切到「重建」以创建/更新它</span>
          </el-form-item>
        </el-form>
      </div>

      <!-- 执行阶段：触发后显示任务状态与实时日志，日志使用增量拉取 -->
      <div v-else>
        <div class="build-summary">
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
      </div>

      <template #footer>
        <el-button v-if="!buildStarted" size="small" @click="buildDialogVisible = false">取 消</el-button>
        <el-button v-if="!buildStarted" type="primary" size="small" :loading="buildSubmitting" @click="submitBuild">开始构建</el-button>
        <template v-else>
          <el-text v-if="running" size="small" type="info" style="margin-right: 12px">构建执行中，关闭窗口不影响执行</el-text>
          <el-button size="small" :icon="Download" :loading="downloading" @click="download">下载日志</el-button>
          <el-button size="small" @click="closeBuildDialog">关 闭</el-button>
        </template>
      </template>
    </el-dialog>

    <!-- 连通性测试抽屉：上方任务状态与进度，下方实时日志控制台 -->
    <!-- 校验发生在项目的构建机上（与真实构建同一网络、同一环境），不是平台所在机器 -->
    <!-- 关闭不拦截：只停前端轮询，后端测试任务照跑，重新打开会重新拉全量日志 -->
    <el-drawer
      v-model="testDrawerVisible"
      :title="`连通性测试 - ${testProject?.name || ''}`"
      size="800px"
      :close-on-click-modal="false"
      @closed="stopTestLog"
    >
      <div class="test-summary">
        <el-tag :type="getTaskStatusStyle(testTask?.status).tag">
          {{ getTaskStatusStyle(testTask?.status).label }}
        </el-tag>
        <span class="test-meta">任务编号：{{ testTask?.taskNo || '-' }}</span>
        <span v-if="testTask?.currentStage" class="test-meta">当前阶段：{{ testTask.currentStage }}</span>
      </div>
      <el-progress
        :percentage="testTask?.progress ?? 0"
        :status="testProgressStatus"
        :stroke-width="8"
        style="margin-bottom: 16px"
      />
      <el-alert
        v-if="testTask?.errorMessage"
        :title="testTask.errorMessage"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <div ref="testBodyRef" class="log-console">
        <el-empty v-if="testLogs.length === 0" description="正在等待执行日志…" :image-size="60" />
        <div v-for="log in testLogs" :key="log.id" class="log-line">
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
        <el-text v-if="testRunning" size="small" type="info" style="margin-right: 12px">任务执行中，关闭窗口不影响执行</el-text>
        <el-button size="small" :icon="Download" :loading="testDownloading" @click="downloadTestLog">下载日志</el-button>
        <el-button size="small" @click="closeTestDrawer">关 闭</el-button>
        <el-button
          size="small"
          type="primary"
          :icon="Refresh"
          :disabled="!isTaskFinal(testTask?.status)"
          :loading="testSubmitting"
          @click="retryConnectivityTest"
        >
          重新测试
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目列表页。
 *
 * 对接后端 /project 标准六接口。业务约定：
 * 1. 新增 / 编辑 / 详情走独立页面（/project/add、/project/edit/:id、/project/detail/:id），
 *    本页只保留列表、搜索、删除与构建触发；
 * 2. 项目编码创建后不允许修改，由后端做唯一性校验；
 * 3. 一个 project = 一个可部署的服务模块：同一 Git 地址分支下允许存在多条记录，
 *    多模块仓库靠 buildConfigJson.module_name 区分（见 docs/项目模块.md §2.3.1）；
 * 4. 三段 JSON 高级配置的表单 / JSON 双模式编辑见 ConfigEditor 组件与 constants/projectConfig.ts。
 */
import { ref, reactive, onActivated } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, View, VideoPlay, Download, Connection } from '@element-plus/icons-vue'
import { pageProject, deleteProject, testProjectConnectivity } from '@/api/project.ts'
import { listServers } from '@/api/server.ts'
import { createBuild } from '@/api/build.ts'
import type { ProjectVo, BuildVo, ServerVo } from '@/api/deployTypes.ts'
import { LOG_LEVEL_TAG, getTaskStatusStyle, isTaskFinal } from '@/constants/taskStatus.ts'
import { RUNTIME_TYPE_MAP, BUILD_MODE_OPTIONS, BUILD_MODE_MAP } from '@/constants/projectConfig.ts'
import { usePageRefreshStore } from '@/store/pageRefresh.ts'
// 日志查看器与日志时间格式化：5 处日志视图共用同一份实现
import { useTaskLogViewer, formatLogTime } from '@/composables/useTaskLogViewer.ts'

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

defineOptions({ name: 'ProjectList' })

const router = useRouter()

// ==================== 搜索 ====================
const searchForm = reactive({
  code: '',
  name: '',
  runtimeType: '',
  buildMode: '',
  status: undefined as number | undefined
})

/** 创建时间区间：与后端 createTimeBegin / createTimeEnd 对应，未选择时两个参数都不传 */
const timeRange = ref<string[]>([])

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.code = ''
  searchForm.name = ''
  searchForm.runtimeType = ''
  searchForm.buildMode = ''
  searchForm.status = undefined
  timeRange.value = []
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<ProjectVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    const res = await pageProject({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      code: searchForm.code || undefined,
      name: searchForm.name || undefined,
      runtimeType: searchForm.runtimeType || undefined,
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

// 本页被 keep-alive 缓存（切走再切回不重跑 setup），新增/编辑页保存成功后会打刷新标记：
// 激活时消费标记决定是否重新拉数据，普通标签页切换不产生多余请求
const pageRefreshStore = usePageRefreshStore()
onActivated(() => {
  if (pageRefreshStore.consumeDirty('project-list')) {
    fetchData()
  }
})

// ==================== 新增 / 编辑 / 详情入口 ====================
/** 新增：跳独立页面（表单字段多，弹框空间不够，见 docs/前端工程.md） */
function handleAdd() {
  router.push('/project/add')
}

/**
 * 编辑：跳独立页面，表单数据由编辑页按 id 自行加载。
 *
 * @param row 待编辑的项目行数据
 */
function handleEdit(row: ProjectVo) {
  router.push(`/project/edit/${row.id}`)
}

/**
 * 查看详情：跳只读页面，不进编辑表单。
 *
 * @param row 待查看的项目行数据
 */
function handleDetail(row: ProjectVo) {
  router.push(`/project/detail/${row.id}`)
}

// ==================== 删除 ====================
/**
 * 删除项目（逻辑删除）。
 *
 * @param row 待删除的项目行数据；仍被部署目标引用时后端返回 DATA_CONFLICT
 */
async function handleDelete(row: ProjectVo) {
  // 后端在项目仍被部署目标引用时返回 DATA_CONFLICT，request 层会提示具体原因
  await ElMessageBox.confirm(`确定要删除项目「${row.name}」吗？若项目已绑定部署目标，需先删除或迁移部署目标。`, '提示', { type: 'warning' })
  await deleteProject(row.id)
  ElMessage.success('删除成功')
  fetchData()
}

// ==================== 构建触发 ====================
/**
 * 触发构建对话框状态。
 *
 * 与后端 /build 的「创建即执行」约定一致：POST /build 创建 build 并生成 BUILD 异步任务后立即返回，
 * 真正的克隆 / 构建 / 推送异步进行，本页在对话框内用 /taskLog/pull 增量展示实时日志。
 * 日志区与其余四处日志视图共用 useTaskLogViewer：支持下载导出，任务执行中不允许关闭对话框。
 */
const buildDialogVisible = ref(false)
const buildSubmitting = ref(false)
/** 是否已触发构建：false 显示「选择构建机」配置，true 切换到实时日志 */
const buildStarted = ref(false)
/** 当前触发构建的项目，用于对话框展示项目信息 */
const buildProject = ref<ProjectVo | null>(null)
/** 用户选择的专用构建机；留空由后端解析默认构建机 */
const buildServerId = ref('')
/**
 * 版本序号（可选）。
 *
 * 留空时由后端在构建成功时自动取号（项目内最大序号 +1），这是常规用法；
 * 填写则生成 {项目编码}-v{序号}-{提交短码}，后端在创建构建时即校验该项目内未被占用。
 */
const versionSeq = ref<number | undefined>(undefined)
/**
 * 分层构建是否重新构建基础镜像（仅 LAYERED_DOCKER 生效）。
 *
 * 默认取项目 build_config_json.rebuild_base_image（项目级默认值，false = 复用已有稳定 base）；
 * 打开对话框时按当前项目解析，用户可临时切到「重建」覆盖。
 */
const rebuildBaseImage = ref(false)
/** 启用中的服务器列表，供构建机下拉选择 */
const buildServers = ref<ServerVo[]>([])

/** 日志查看器：轮询、增量锚点、自动滚动、下载与运行中禁止关闭统一由它负责 */
const { bodyRef, logs, task, running, downloading, progressStatus, start, reset, stop, download } =
  useTaskLogViewer()

/**
 * 打开构建触发对话框，并加载启用中的服务器用于构建机下拉。
 *
 * @param row 待构建的项目行数据
 */
function handleBuild(row: ProjectVo) {
  buildProject.value = row
  // 默认带出项目档案绑定的构建机：多数项目固定在一台机器上构建，每次手选是纯负担。
  // 仍可清空或改选，后端解析顺序是「请求 > 项目档案 > 系统默认」。
  buildServerId.value = row.buildServerId || ''
  // 分层构建「是否重建基础镜像」：取项目 build_config_json.rebuild_base_image 作为默认值（缺省 false）。
  // 仅在该项目是 LAYERED_DOCKER 时有意义；其余模式对话框里也不会显示该开关。
  let defaultRebuild = false
  if (row.buildMode === 'LAYERED_DOCKER' && row.buildConfigJson) {
    try {
      const cfg = JSON.parse(row.buildConfigJson)
      if (typeof cfg.rebuild_base_image === 'boolean') defaultRebuild = cfg.rebuild_base_image
    } catch { /* 配置不是合法 JSON 时按默认 false 处理 */ }
  }
  rebuildBaseImage.value = defaultRebuild
  buildStarted.value = false
  buildSubmitting.value = false
  // 清掉上一次触发的日志与任务状态，避免两次构建的日志混在同一个控制台里
  reset()
  // 只列启用中的服务器，停用服务器不允许参与构建
  listServers({ status: 1 }).then((res) => { buildServers.value = res.data }).catch(() => {})
  buildDialogVisible.value = true
}

/**
 * 提交构建：调用 createBuild 后立即进入实时日志阶段。
 *
 * 后端只创建记录与异步任务后立即返回，因此这里拿到 taskId 就开始轮询日志，
 * 不再等待后端「构建完成」（构建是分钟级异步任务）。
 */
async function submitBuild() {
  if (!buildProject.value) return
  buildSubmitting.value = true
  try {
    const res = await createBuild({
    projectId: buildProject.value.id,
    buildServerId: buildServerId.value || undefined,
    // 分层构建：把「是否重建基础镜像」一并带上；非 LAYERED_DOCKER 时后端忽略该字段。
    rebuildBaseImage: buildProject.value.buildMode === 'LAYERED_DOCKER' ? rebuildBaseImage.value : undefined,
  })
    buildStarted.value = true
    const build: BuildVo = res.data
    // 任务由本次请求刚创建：状态、进度一律以首次拉取为准，不拿构建状态当任务状态。
    start({ taskId: build.taskId, taskNo: build.taskNo })
    ElMessage.success('已触发构建，可在「构建管理」查看完整进度')
  } finally {
    buildSubmitting.value = false
  }
}

/** 关闭构建对话框；@close 会触发 stop 停掉轮询，后端构建任务不受影响 */
function closeBuildDialog() {
  buildDialogVisible.value = false
}

// ==================== 项目连通性测试 ====================
/**
 * 连通性测试：在项目的**构建机**上验证构建前置条件。
 *
 * 校验项见后端 ProjectConnectTestTaskHandler：SSH 可达、git/docker 工具链、
 * Git 仓库可否 ls-remote（可达 + 凭据有效 + 分支存在）、镜像仓库可否 docker login。
 * 目的是把这些配置问题在「点构建之前」暴露，而不是以一次失败的构建来暴露。
 *
 * 与构建对话框的日志查看器**各用一份实例**：两个容器可能同时挂在 DOM 上，
 * 共用一个 bodyRef 与 logs 会让自动滚动与日志归属串台。
 */
const testDrawerVisible = ref(false)
const testSubmitting = ref(false)
const testProject = ref<ProjectVo | null>(null)

const {
  bodyRef: testBodyRef,
  logs: testLogs,
  task: testTask,
  running: testRunning,
  downloading: testDownloading,
  progressStatus: testProgressStatus,
  start: startTestLog,
  reset: resetTestLog,
  stop: stopTestLog,
  download: downloadTestLog
} = useTaskLogViewer()

/**
 * 打开连通性测试抽屉并立即提交一次测试任务。
 *
 * @param row 待测试的项目行数据；未绑定构建机时后端返回参数校验错误并提示先去绑定
 */
async function handleConnectivityTest(row: ProjectVo) {
  testProject.value = row
  testSubmitting.value = true
  resetTestLog()
  testDrawerVisible.value = true
  try {
    const res = await testProjectConnectivity(row.id)
    // 任务刚创建：状态与进度一律以首次 pull 的响应为准，这里只传任务主键与编号。
    startTestLog({ taskId: res.data.id, taskNo: res.data.taskNo })
  } catch {
    // 失败原因（未绑定构建机 / 已有未完成任务）由 request.ts 统一提示，这里收起抽屉
    testDrawerVisible.value = false
  } finally {
    testSubmitting.value = false
  }
}

/** 任务结束后重新发起一次测试，用于改完配置后立刻复验 */
async function retryConnectivityTest() {
  if (!testProject.value) return
  await handleConnectivityTest(testProject.value)
}

/** 关闭测试抽屉；@closed 会触发 stopTestLog 停掉轮询，后端任务不受影响 */
function closeTestDrawer() {
  testDrawerVisible.value = false
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
// 实时日志控制台复用全局 .log-console / .log-line 样式，与服务器连通性测试、构建列表页保持一致

// 构建对话框顶部任务状态区：与下方日志控制台留出统一间隔
.build-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

// 连通性测试抽屉顶部状态区：与服务器页的同名区块保持一致
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
</style>
