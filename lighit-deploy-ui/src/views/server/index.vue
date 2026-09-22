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
          <el-select v-model="searchForm.envTag" placeholder="请选择" clearable style="width: 160px">
            <el-option label="开发" :value="1" />
            <el-option label="测试" :value="2" />
            <el-option label="生产" :value="3" />
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
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增服务器</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="serverName" label="服务器名称" min-width="140" show-overflow-tooltip align="center"/>
      <el-table-column prop="host" label="主机地址" min-width="140" show-overflow-tooltip align="center"/>
      <el-table-column prop="sshPort" label="SSH端口" min-width="130" align="center" />
      <el-table-column prop="username" label="登录用户" min-width="130" show-overflow-tooltip align="center"/>
      <el-table-column prop="authType" label="认证类型" min-width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="row.authType === 1 ? 'primary' : 'warning'">
            {{ row.authType === 1 ? '密码' : '私钥' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="envTag" label="环境标签" min-width="130" align="center">
        <template #default="{ row }">
          <el-tag :type="getEnvTagStyle(row.envTag).tag">
            {{ getEnvTagStyle(row.envTag).label }}
          </el-tag>
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
      <el-table-column prop="description" label="描述" min-width="150" align="center" show-overflow-tooltip />
      <el-table-column label="操作" min-width="340" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="success" :icon="Connection" @click="handleConnectivityTest(row)">连通性测试</el-button>
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
      <!-- 内容区滚动容器：顶部与标题栏留出间隔；左右 padding 抵消 el-row gutter 负边距并预留滚动条宽度 -->
      <div style="max-height: 60vh; overflow-y: auto; padding: 16px 18px 0 10px;">
        <el-form ref="formRef" :model="formData" :rules="formRules" label-width="120px" size="small">
          <!-- 第一行：服务器名称 + 主机地址 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="服务器名称" prop="serverName">
                <el-input v-model="formData.serverName" placeholder="请输入服务器名称" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="主机地址" prop="host">
                <el-input v-model="formData.host" placeholder="IP 地址或域名" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第二行：SSH 端口 + 登录用户 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="SSH端口" prop="sshPort">
                <el-input-number v-model="formData.sshPort" :min="1" :max="65535" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="登录用户" prop="username">
                <el-input v-model="formData.username" placeholder="SSH 登录用户名" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第三行：环境标签 + 认证类型 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="环境标签" prop="envTag">
                <el-select v-model="formData.envTag" placeholder="请选择" style="width: 100%">
                  <el-option label="开发" :value="1" />
                  <el-option label="测试" :value="2" />
                  <el-option label="生产" :value="3" />
                </el-select>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="认证类型" prop="authType">
                <el-radio-group v-model="formData.authType">
                  <el-radio :value="1">密码认证</el-radio>
                  <el-radio :value="2">私钥认证</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第四行：启用状态 -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item label="状态" prop="status">
                <el-radio-group v-model="formData.status">
                  <el-radio :value="1">启用</el-radio>
                  <el-radio :value="0">停用</el-radio>
                </el-radio-group>
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第五行：凭据（文本域，只写不读，编辑留空表示保留旧凭据） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item :label="isEdit ? '新凭据' : '凭据'" prop="credential">
                <el-input
                  v-model="formData.credential"
                  type="textarea"
                  :rows="3"
                  :placeholder="isEdit ? '不修改凭据请留空，填写后将重新加密保存' : '请输入 SSH 密码或私钥内容（AES-GCM 加密后保存）'"
                  show-password
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第六行：描述（文本域，固定放表单最底部） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item label="描述" prop="description">
                <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="服务器用途说明（选填）" />
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

    <!-- 连通性测试抽屉：上方任务状态与进度，下方实时日志控制台 -->
    <!-- 日志使用增量拉取（/taskLog/pull + lastSeq 锚点），不做分页轮询，避免日志重复与错乱 -->
    <el-drawer
      v-model="testDrawerVisible"
      :title="`连通性测试 - ${testServer?.serverName || ''}`"
      size="800px"
      :close-on-click-modal="false"
      @closed="handleTestDrawerClosed"
    >
      <!-- 任务状态区：状态标签、任务编号、当前阶段，与日志区留出间隔 -->
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
      <!-- 失败原因单独提示：失败任务只在日志区显示容易被忽略 -->
      <el-alert
        v-if="testTask?.errorMessage"
        :title="testTask.errorMessage"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <!-- 实时日志控制台：等宽字体 + 终端底色，自动滚动到最新一行 -->
      <div ref="testLogBodyRef" class="log-console">
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
        <el-button size="small" @click="testDrawerVisible = false">关 闭</el-button>
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
 * 服务器管理页面。
 *
 * 对接后端 /server 标准六接口。业务约定：
 * 1. SSH 凭据（credential）只写不读 —— 新增必填，编辑留空表示保留旧凭据，
 *    后端 AES-GCM 加密后落库，列表永远不返回密文；
 * 2. 环境标签只用于展示筛选，正式发布目标以部署目标绑定为准；
 * 3. 删除为逻辑删除，同 host+端口 在软删除后可重新登记；
 * 4. 连通性测试是异步任务：POST /server/{id}/test 只创建任务并返回 taskId，
 *    真正的 SSH 连接与探活由后端任务执行器完成，页面用 /taskLog/pull 增量拉取日志；
 * 5. 日志拉取使用「顺序号锚点 + 增量返回」而不是分页轮询：每次只取 sequenceNo 大于 lastSeq 的
 *    增量，因此不会出现日志重复、乱序或漏读，也不需要在前端做去重（AGENTS.md §20.3）。
 */
import { ref, reactive, computed, onUnmounted, nextTick } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, Connection } from '@element-plus/icons-vue'
import { pageServer, createServer, updateServer, deleteServer, testServerConnectivity } from '@/api/server.ts'
import { pullTaskLog } from '@/api/task.ts'
import type { ServerVo, ServerDto, TaskVo, TaskLogVo } from '@/api/deployTypes.ts'
// 环境标签的文案与颜色统一来自常量模块，页面不再自行定义（约定见 AGENTS.md §20.7）
import { getEnvTagStyle } from '@/constants/tagStyle.ts'
// 任务状态与日志级别的文案、颜色同样集中在常量模块，避免与任务列表页出现两套口径
import { LOG_LEVEL_TAG, getTaskStatusStyle, isTaskFinal } from '@/constants/taskStatus.ts'

/** 连通性测试日志每页拉取条数，与后端 /taskLog/pull 的 limit 对应 */
const TEST_LOG_PAGE_SIZE = 200

/** 连通性测试日志轮询间隔（毫秒）：1.5 秒是「看起来实时」与数据库压力的折中 */
const TEST_LOG_POLL_INTERVAL = 1500

defineOptions({ name: 'ServerList' })

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

// ==================== 连通性测试 ====================
const testDrawerVisible = ref(false)
const testSubmitting = ref(false)
/** 当前测试对应的服务器行，用于抽屉标题与「重新测试」 */
const testServer = ref<ServerVo | null>(null)
/** 当前测试任务，状态、进度、失败原因都取自它 */
const testTask = ref<TaskVo | null>(null)
/** 已拉取到的日志，按顺序号追加，不做排序与去重（后端保证增量且有序） */
const testLogs = ref<TaskLogVo[]>([])
/** 增量锚点：已拉取到的最大日志顺序号 */
const testLastSeq = ref(0)
/** 日志控制台容器，用于自动滚动到底部 */
const testLogBodyRef = ref<HTMLElement | null>(null)
/** 轮询定时器；抽屉关闭或任务结束必须清理，否则会一直请求后端 */
let testPollTimer: ReturnType<typeof setInterval> | null = null
/** 请求中的标记：上一轮还没返回时不重复发起，避免慢响应时请求堆积 */
let testPolling = false

/** 进度条状态：成功绿色、失败红色，其余保持主题色 */
const testProgressStatus = computed(() => {
  if (testTask.value?.status === 2) return 'success'
  if (testTask.value?.status === 3) return 'exception'
  return undefined
})

/**
 * 发起连通性测试并打开日志抽屉。
 *
 * @param row 目标服务器行数据，必须处于启用状态（停用服务器后端会拒绝）
 */
async function handleConnectivityTest(row: ServerVo) {
  // 每次测试都重置上一次的日志与状态，避免两次测试的日志混在同一个控制台里
  testServer.value = row
  testTask.value = null
  testLogs.value = []
  testLastSeq.value = 0
  testSubmitting.value = true
  try {
    const res = await testServerConnectivity(row.id)
    testTask.value = res.data
    testDrawerVisible.value = true
    // 立即拉一次日志，让用户不用等第一个轮询周期
    await pullLogs()
    startPolling()
  } finally {
    testSubmitting.value = false
  }
}

/**
 * 重新测试：关闭抽屉后以同一台服务器再发起一次。
 *
 * 仅在任务已结束时可用，避免与正在执行的任务重复提交（后端也会返回数据冲突）。
 */
async function retryConnectivityTest() {
  if (!testServer.value) return
  await handleConnectivityTest(testServer.value)
}

/** 启动日志轮询；已在轮询时不重复启动，任务已结束时不启动 */
function startPolling() {
  stopPolling()
  // 首次拉取时任务可能就已经结束（例如秒级失败），此时不应再启动轮询
  if (isTaskFinal(testTask.value?.status)) return
  testPollTimer = setInterval(pullLogs, TEST_LOG_POLL_INTERVAL)
}

/** 停止日志轮询并清理定时器 */
function stopPolling() {
  if (testPollTimer !== null) {
    clearInterval(testPollTimer)
    testPollTimer = null
  }
}

/**
 * 增量拉取一次执行日志。
 *
 * 用上次响应的 lastSeq 作为锚点，只取新增日志并按顺序追加；任务进入终态后停止轮询。
 * 停止条件写成「已结束 且 本批日志不足一页」：本批正好满一页说明可能还有剩余日志，
 * 需要再拉一次把尾巴取完，否则最后一屏日志会丢。
 */
async function pullLogs() {
  if (!testTask.value || testPolling) return
  testPolling = true
  try {
    const res = await pullTaskLog(testTask.value.id, testLastSeq.value, TEST_LOG_PAGE_SIZE)
    const data = res.data
    // 状态与进度以本次响应为准，保证状态与日志来自同一时刻的快照
    if (testTask.value) {
      testTask.value.status = data.status
      testTask.value.progress = data.progress
      testTask.value.currentStage = data.currentStage
      testTask.value.errorMessage = data.errorMessage
    }
    if (data.logs.length > 0) {
      testLogs.value.push(...data.logs)
      testLastSeq.value = data.lastSeq
      await scrollLogToBottom()
    }
    if (data.finished && data.logs.length < TEST_LOG_PAGE_SIZE) {
      stopPolling()
    }
  } catch {
    // 网络或后端异常时停止轮询，避免持续刷错误提示；用户可重新点「重新测试」
    stopPolling()
  } finally {
    testPolling = false
  }
}

/** 日志追加后滚动到最新一行，等待 DOM 更新完成再设置滚动位置 */
async function scrollLogToBottom() {
  await nextTick()
  const body = testLogBodyRef.value
  if (body) {
    body.scrollTop = body.scrollHeight
  }
}

/**
 * 抽屉关闭后的清理。
 *
 * 必须停止轮询：抽屉已不可见，继续请求只会浪费后端与数据库资源。
 */
function handleTestDrawerClosed() {
  stopPolling()
}

/**
 * 裁剪日志时间，控制台只显示到秒。
 *
 * @param createTime 后端返回的日志创建时间，形如 2026-09-23T01:44:56.608429
 * @returns 形如 01:44:56 的时间片段；格式不符合预期时原样返回
 */
function formatLogTime(createTime?: string): string {
  if (!createTime) return ''
  const timePart = createTime.split('T')[1]
  return timePart ? timePart.slice(0, 8) : createTime
}

// 组件卸载时兜底清理，避免页面切换后定时器继续运行
onUnmounted(() => {
  stopPolling()
})
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div，定义在 styles/index.scss 中
// 连通性测试的日志控制台复用全局 .log-console / .log-line 样式，与任务列表页保持一致

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
</style>
