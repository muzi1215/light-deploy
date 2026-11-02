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
        <CreateTimeRange v-model="timeRange" />
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 工具栏 -->
    <div class="tools-div">
      <el-button v-readonly type="primary" :icon="Plus" @click="handleAdd">新增服务器</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="serverName" label="服务器名称" min-width="140" show-overflow-tooltip fixed="left" align="center"/>
      <el-table-column prop="host" label="主机地址" min-width="140" show-overflow-tooltip fixed="left" align="center"/>
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

          <!-- 第六行：部署根目录（决定应用最终跑在这台机器的哪个目录下） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item prop="agentBaseDir">
                <template #label>
                  <span>部署根目录
                    <el-tooltip placement="top" :show-after="120">
                      <template #content>
                        <div style="max-width: 420px; line-height: 1.7">
                          <div><b>作用</b>：平台在这台机器上的根目录。项目最终跑在哪，<b>默认</b>由它决定。</div>
                          <div style="margin-top: 4px">· JAR：默认工作目录 <code>{该目录}/apps/{项目编码}</code>，目录固定不变、发版原地替换 jar，systemd 从该目录启动。</div>
                          <div>· STATIC：<b>不使用该目录</b>，默认解压到 <code>/usr/share/nginx/html/{项目编码}</code>（可在项目里用 static_root 覆盖）。</div>
                          <div>· DOCKER：容器由 Docker 管理，不使用该目录，但目录仍需存在且可写。</div>
                          <div style="margin-top: 4px; color: #E6A23C">· JAR 项目在项目运行配置里填了「固定工作目录 work_dir」后，会<b>完全改到那个目录</b>，这里的部署根目录对它不再生效。</div>
                          <div style="margin-top: 4px; color: #E6A23C">连通性测试只检查它是否存在且可写，<b>不会自动创建</b>；不存在时测试结果会给 WARN，需在机器上手工创建并授权。</div>
                        </div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-input v-model="formData.agentBaseDir" placeholder="平台在该机器上的根目录，应用部署在其 apps 子目录下" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第七行：构建机专用目录（纯部署目标机保持默认即可，不影响部署） -->
          <el-row :gutter="20">
            <el-col :span="12">
              <el-form-item prop="buildWorkspaceDir">
                <template #label>
                  <span>构建工作区
                    <el-tooltip placement="top" :show-after="120">
                      <template #content>
                        <div style="max-width: 400px; line-height: 1.7">
                          <div><b>作用</b>：构建临时目录，<b>仅在这台机器被当作构建机时生效</b>；纯部署目标机填什么都不影响。</div>
                          <div style="margin-top: 4px">· 每次构建在其下建 <code>{该目录}/{项目编码}_{构建ID}/</code>，拉代码、执行构建命令。</div>
                          <div>· 构建结束（成功或失败）<b>自动删除</b>，不要在这里放任何需要保留的东西。</div>
                          <div>· 要求登录用户可读写，空间按单次构建体积预留。</div>
                        </div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-input v-model="formData.buildWorkspaceDir" placeholder="仅构建机使用：构建临时目录，结束后清理" style="width: 100%" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item prop="buildArtifactDir">
                <template #label>
                  <span>产物持久目录
                    <el-tooltip placement="top" :show-after="120">
                      <template #content>
                        <div style="max-width: 400px; line-height: 1.7">
                          <div><b>作用</b>：存放构建产物，<b>仅构建机使用</b>；发布时平台从这里把产物拉到目标机。</div>
                          <div style="margin-top: 4px">· JAR / STATIC 构建成功后转存到 <code>{该目录}/{项目编码}/{版本号}/</code>。</div>
                          <div>· DOCKER 不使用：产物是镜像，直接推到镜像仓库。</div>
                          <div style="margin-top: 4px; color: #E6A23C"><b>必须落在构建工作区之外</b>，否则会被构建收尾的清理删除，发布时下载到不存在的路径。</div>
                          <div style="margin-top: 4px">改这个值不影响历史版本：产物绝对路径在构建时已快照，老版本仍从原路径发布与回滚。</div>
                        </div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-input v-model="formData.buildArtifactDir" placeholder="仅构建机使用：须在工作区之外，否则会被清理" style="width: 100%" />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第八行：构建环境初始化脚本（构建机专用，单行命令，整行宽度） -->
          <el-row :gutter="20">
            <el-col :span="24">
              <el-form-item prop="buildEnvScript">
                <template #label>
                  <span>构建环境初始化脚本
                    <el-tooltip placement="top" :show-after="120">
                      <template #content>
                        <div style="max-width: 430px; line-height: 1.7">
                          <div><b>作用</b>：执行构建命令前，在当前 shell 里先执行这段命令，<b>仅在这台机器被当作构建机时生效</b>。</div>
                          <div style="margin-top: 6px"><b>为什么需要</b>：平台通过 SSH 执行构建，走的是<b>非交互式 shell</b>，不加载 /etc/profile、~/.bashrc，PATH 里往往没有「已安装但靠 profile 导出路径」的工具，最典型的是 mvn —— 报错只有 <code>bash: mvn: command not found</code>，很难定位。</div>
                          <div style="margin-top: 6px"><b>平台已有的兜底</b>：每条构建命令前会自动执行一次 <code>. /etc/profile</code>（输出丢弃，不污染构建日志）。本字段用于补 profile 里也没有的路径。</div>
                          <div style="margin-top: 6px"><b>常见写法</b>（单行，多个用 <code>;</code> 分隔）：</div>
                          <div><code>export PATH=/opt/apache-maven-3.8.8/bin:$PATH</code></div>
                          <div><code>export JAVA_HOME=/usr/lib/jdk1.8.0_121; export PATH=$JAVA_HOME/bin:$PATH</code></div>
                          <div style="margin-top: 6px">留空即只走平台兜底。改这个值只影响之后发起的构建，不影响历史版本。</div>
                        </div>
                      </template>
                      <el-icon class="tip-icon"><QuestionFilled /></el-icon>
                    </el-tooltip>
                  </span>
                </template>
                <el-input
                  v-model="formData.buildEnvScript"
                  placeholder="仅构建机使用，如 export PATH=/opt/apache-maven-3.8.8/bin:$PATH（选填，单行）"
                  style="width: 100%"
                />
              </el-form-item>
            </el-col>
          </el-row>

          <!-- 第九行：描述（文本域，固定放表单最底部） -->
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
        <el-button v-readonly type="primary" size="small" :loading="submitLoading" @click="handleSubmit">确 定</el-button>
      </template>
    </el-dialog>

    <!-- 连通性测试抽屉：上方任务状态与进度，下方实时日志控制台 -->
    <!-- 日志使用增量拉取（/taskLog/pull + lastSeq 锚点），不做分页轮询，避免日志重复与错乱 -->
    <!-- 关闭不拦截：只停前端轮询，后端探活任务照跑，重新打开会重新拉全量日志 -->
    <el-drawer
      v-model="testDrawerVisible"
      :title="`连通性测试 - ${testServer?.serverName || ''}`"
      size="800px"
      :close-on-click-modal="false"
      @closed="stop"
    >
      <!-- 任务状态区：状态标签、任务编号、当前阶段，与日志区留出间隔 -->
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
      <!-- 失败原因单独提示：失败任务只在日志区显示容易被忽略 -->
      <el-alert
        v-if="task?.errorMessage"
        :title="task.errorMessage"
        type="error"
        :closable="false"
        show-icon
        style="margin-bottom: 12px"
      />

      <!-- 实时日志控制台：等宽字体 + 终端底色，自动滚动到最新一行 -->
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
        <el-button
          size="small"
          type="primary"
          :icon="Refresh"
          :disabled="!isTaskFinal(task?.status)"
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
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete, Connection, QuestionFilled, Download } from '@element-plus/icons-vue'
import { pageServer, createServer, updateServer, deleteServer, testServerConnectivity } from '@/api/server.ts'
import type { ServerVo, ServerDto } from '@/api/deployTypes.ts'
// 环境标签的文案与颜色统一来自常量模块，页面不再自行定义（约定见 AGENTS.md §20.7）
import { getEnvTagStyle } from '@/constants/tagStyle.ts'
// 任务状态与日志级别的文案、颜色同样集中在常量模块，避免与任务列表页出现两套口径
import { LOG_LEVEL_TAG, getTaskStatusStyle, isTaskFinal } from '@/constants/taskStatus.ts'
// 日志查看器与日志时间格式化：5 处日志视图共用同一份实现
import { useTaskLogViewer, formatLogTime } from '@/composables/useTaskLogViewer.ts'

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

defineOptions({ name: 'ServerList' })

// ==================== 搜索 ====================
const searchForm = reactive({ serverName: '', host: '', envTag: undefined as number | undefined, status: undefined as number | undefined })

/** 创建时间区间：与后端 createTimeBegin / createTimeEnd 对应，未选择时两个参数都不传 */
const timeRange = ref<string[]>([])

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.serverName = ''
  searchForm.host = ''
  searchForm.envTag = undefined
  searchForm.status = undefined
  timeRange.value = []
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

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const submitLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()

/**
 * 三个目录的默认值，与 server 表对应列的 DEFAULT 保持一致。
 *
 * 目录路径由页面按机器维护、数据库列提供默认值，代码里不保留第二套兜底：
 * 这里的值只用于新增表单的预填，真正生效的永远是提交后存进数据库的值。
 */
const DEFAULT_AGENT_BASE_DIR = '/opt/light-deploy'
const DEFAULT_BUILD_WORKSPACE_DIR = '/opt/light-deploy/build-workspace'
const DEFAULT_BUILD_ARTIFACT_DIR = '/opt/light-deploy/artifacts'

const formData = reactive<ServerDto & { credential: string }>({
  serverName: '',
  host: '',
  sshPort: 22,
  username: '',
  authType: 1,
  credential: '',
  envTag: 1,
  status: 1,
  description: '',
  agentBaseDir: DEFAULT_AGENT_BASE_DIR,
  buildWorkspaceDir: DEFAULT_BUILD_WORKSPACE_DIR,
  buildArtifactDir: DEFAULT_BUILD_ARTIFACT_DIR,
  // 构建环境初始化脚本：无默认值，留空即只用平台的 . /etc/profile 兜底
  buildEnvScript: ''
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
  // 目录按机器回填实际值；历史数据可能为空，此时回落到默认常量，保证表单不出现空路径。
  formData.agentBaseDir = row.agentBaseDir || DEFAULT_AGENT_BASE_DIR
  formData.buildWorkspaceDir = row.buildWorkspaceDir || DEFAULT_BUILD_WORKSPACE_DIR
  formData.buildArtifactDir = row.buildArtifactDir || DEFAULT_BUILD_ARTIFACT_DIR
  // 环境脚本无默认值：库里是 null 时表单显示空串
  formData.buildEnvScript = row.buildEnvScript || ''
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
  formData.agentBaseDir = DEFAULT_AGENT_BASE_DIR
  formData.buildWorkspaceDir = DEFAULT_BUILD_WORKSPACE_DIR
  formData.buildArtifactDir = DEFAULT_BUILD_ARTIFACT_DIR
  formData.buildEnvScript = ''
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

/** 日志查看器：轮询、增量锚点、自动滚动、下载与运行中禁止关闭统一由它负责 */
const { bodyRef, logs, task, running, downloading, progressStatus, start, stop, download } =
  useTaskLogViewer()

/**
 * 发起连通性测试并打开日志抽屉。
 *
 * @param row 目标服务器行数据，必须处于启用状态（停用服务器后端会拒绝）
 */
async function handleConnectivityTest(row: ServerVo) {
  testServer.value = row
  testSubmitting.value = true
  try {
    const res = await testServerConnectivity(row.id)
    testDrawerVisible.value = true
    // 任务由本次请求刚创建，返回值本身就是任务对象，状态与进度可直接作初值。
    start({ taskId: res.data.id, taskNo: res.data.taskNo, status: res.data.status, progress: res.data.progress })
  } finally {
    testSubmitting.value = false
  }
}

/**
 * 重新测试：以同一台服务器再发起一次，抽屉里的日志由查看器重置。
 *
 * 仅在任务已结束时可用，避免与正在执行的任务重复提交（后端也会返回数据冲突）。
 */
async function retryConnectivityTest() {
  if (!testServer.value) return
  await handleConnectivityTest(testServer.value)
}

/** 关闭日志抽屉；@closed 会触发 stop 停掉轮询，后端探活任务不受影响 */
function closeLog() {
  testDrawerVisible.value = false
}
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

// 目录配置项标签后的问号图标：提示有悬浮说明，鼠标移上去看含义与作用
.tip-icon {
  margin-left: 4px;
  font-size: 14px;
  color: #909399;
  cursor: help;
  vertical-align: -2px;
}

.test-meta {
  font-size: 13px;
  color: var(--text-secondary);
}
</style>
