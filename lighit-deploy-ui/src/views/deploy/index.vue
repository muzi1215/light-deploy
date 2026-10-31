<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="项目">
          <el-select v-model="searchForm.projectId" placeholder="请选择" clearable filterable style="width: 180px">
            <el-option v-for="item in projectOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="环境">
          <el-select v-model="searchForm.environmentId" placeholder="请选择" clearable style="width: 160px">
            <el-option v-for="item in environmentOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 160px">
            <el-option v-for="(item, key) in DEPLOY_STATUS_MAP" :key="key" :label="item.label" :value="Number(key)" />
          </el-select>
        </el-form-item>
        <CreateTimeRange v-model="timeRange" />
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
          <el-button type="primary" :icon="Promotion" @click="openCreate">新建部署</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="projectName" label="项目" min-width="140" show-overflow-tooltip align="center" />
      <el-table-column prop="environmentName" label="环境" min-width="110" align="center" />
      <!--
        版本列显示「本次动作针对的发布版本」。已回滚时再补一行「当前 xxx」——回滚后机器跑的
        是历史版本，只显示这一列会让人以为机器还在跑新版本。快照字段本身不改写。
      -->
      <el-table-column prop="version" label="版本" min-width="190" align="center">
        <template #default="{ row }">
          <el-tooltip v-if="row.version" placement="top" :content="versionCellTip(row)">
            <div class="version-cell">
              <span>{{ row.version }}</span>
              <span v-if="showCurrentVersion(row)" class="version-current">当前 {{ row.currentVersion }}</span>
            </div>
          </el-tooltip>
          <span v-else class="muted">-</span>
        </template>
      </el-table-column>
      <el-table-column prop="strategy" label="策略" min-width="120" align="center">
        <template #default="{ row }">
          <!-- 悬浮说明本次实际采用的策略语义；留空落库后后端会填上解析结果，这里兼容未落库的情况 -->
          <el-tooltip placement="top">
            <template #content>
              <div class="tooltip-lines">
                <div class="tip-title">{{ DEPLOY_STRATEGY_MAP[row.strategy] || row.strategy }}</div>
                <div v-for="(line, i) in DEPLOY_STRATEGY_TIPS[row.strategy] || []" :key="i">{{ line }}</div>
              </div>
            </template>
            <el-tag class="hover-underline">{{ DEPLOY_STRATEGY_MAP[row.strategy] || row.strategy }}</el-tag>
          </el-tooltip>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" min-width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="getDeployStatusStyle(row.status).tag">
            {{ row.statusText || getDeployStatusStyle(row.status).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="目标进度" min-width="120" align="center">
        <template #default="{ row }">
          <span>{{ row.targetSuccess ?? 0 }} / {{ row.targetTotal ?? 0 }}</span>
          <span v-if="(row.targetFailed ?? 0) > 0" class="failed-count">（失败 {{ row.targetFailed }}）</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="170" align="center" />
      <el-table-column prop="startedAt" label="开始时间" min-width="170" align="center" />
      <el-table-column prop="finishedAt" label="结束时间" min-width="170" align="center" />
      <el-table-column prop="taskNo" label="关联任务" min-width="160" show-overflow-tooltip align="center" />
      <el-table-column label="操作" min-width="340" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Document" @click="handleViewTargets(row)">目标明细</el-button>
          <el-button link type="success" :icon="Monitor" @click="handleViewLog(row)">发布日志</el-button>
          <!--
            回滚按钮的可用性只认服务端的 rollbackable：状态为「成功」只说明有可退的新版本，
            还要历史版本的记录与产物仍在才真的退得回去。置灰而不是隐藏，是为了让使用者
            看到入口的同时知道「为什么现在不能点」——原因由 rollbackBlockReason 给出。
            tooltip 必须包一层 span：disabled 的按钮不派发鼠标事件，直接挂上去不会触发。
          -->
          <el-tooltip
            v-if="canRollback(row.status)"
            :content="rollbackTooltip(row)"
            placement="top"
          >
            <span class="rollback-button-wrapper">
              <el-button
                link
                type="warning"
                :icon="RefreshLeft"
                :disabled="!row.rollbackable"
                @click="handleRollback(row)"
              >
                回滚
              </el-button>
            </span>
          </el-tooltip>
          <el-button v-if="canCancel(row.status)" link type="warning" :icon="CircleClose" @click="handleCancel(row)">
            取消
          </el-button>
          <el-button v-if="isDeployFinal(row.status)" link type="danger" :icon="Delete" @click="handleDelete(row)">
            删除
          </el-button>
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

    <!-- 新建部署对话框：项目 → 环境 → 版本 三级联动，策略留空即用环境默认 -->
    <!-- class="deploy-create-dialog"：给「部署策略」下拉的下展开面板预留落位空间（styles/index.scss） -->
    <el-dialog
      v-model="createVisible"
      title="新建部署"
      width="720px"
      class="deploy-create-dialog"
      :close-on-click-modal="false"
      @closed="resetCreate"
    >
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="110px">
        <el-form-item label="项目" prop="projectId">
          <el-select
            v-model="createForm.projectId"
            placeholder="请选择项目"
            filterable
            style="width: 100%"
            @change="handleProjectChange"
          >
            <el-option v-for="item in projectOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <!--
          环境下拉只列「该项目已绑定启用目标」的环境，选项带目标台数（如 生产环境（2 台））。
          全量环境下拉在这里没有意义：绝大多数环境与当前项目无关，选了只会在提交阶段才报错。
        -->
        <el-form-item label="环境" prop="environmentId">
          <el-select v-model="createForm.environmentId" placeholder="请先选择项目" style="width: 100%">
            <el-option
              v-for="item in projectEnvOptions"
              :key="item.id"
              :label="`${item.name}（${item.targetCount} 台）`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="发布版本" prop="releaseId">
          <!--
            popper-class="strategy-option-popper"：本下拉选项是「版本号 + 构建机」两行内容，
            Element Plus 默认把每个选项固定成 34px 高（.el-select-dropdown__item），装不下两行，
            副标题会被下边界裁掉并与下一项标题挤在一起。样式定义在 styles/index.scss（面板 teleport 到 body）。
          -->
          <el-select
            v-model="createForm.releaseId"
            placeholder="请选择可发布版本"
            popper-class="strategy-option-popper"
            style="width: 100%"
          >
            <el-option v-for="item in releaseOptions" :key="item.id" :label="item.version" :value="item.id">
              <div class="strategy-option">
                <span>{{ item.version }}</span>
                <span class="strategy-option-desc">{{ releaseServerLabel(item) }}</span>
              </div>
            </el-option>
          </el-select>
        </el-form-item>
        <!--
          部署策略：这里的选项是「临时覆盖」，环境档案里的默认值才是长期配置。
          label 后面的「?」图标说明两者关系，下拉选项带副标题说明三种策略的差别。
        -->
        <el-form-item prop="strategy">
          <template #label>
            <span>
              部署策略
              <el-tooltip placement="top">
                <template #content>
                  <div class="tooltip-lines">
                    <div v-for="(line, i) in DEPLOY_STRATEGY_OVERRIDE_TIPS" :key="i">{{ line }}</div>
                  </div>
                </template>
                <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
              </el-tooltip>
            </span>
          </template>
          <!--
            本字段的面板只向下展开（STRATEGY_POPPER_OPTIONS 关掉了 popper 的 flip 修正）：
            向上展开会盖住刚选完的项目 / 环境 / 发布版本，而弹窗底部已经预留了面板整块的落位空间，
            向下展开不会戳出弹窗。popper-class 的作用与「发布版本」下拉相同：放开选项的固定行高。
          -->
          <el-select
            v-model="createForm.strategy"
            placeholder="留空则使用环境默认策略"
            clearable
            placement="bottom-start"
            :popper-options="STRATEGY_POPPER_OPTIONS"
            popper-class="strategy-option-popper"
            style="width: 100%"
          >
            <el-option v-for="item in DEPLOY_STRATEGY_OPTIONS" :key="item.value" :label="item.label" :value="item.value">
              <div class="strategy-option">
                <span>{{ item.label }}</span>
                <span class="strategy-option-desc">{{ item.desc }}</span>
              </div>
            </el-option>
          </el-select>
          <!-- 选中环境后回显它的默认策略，让「覆盖」这件事有对照物 -->
          <span v-if="envDefaultStrategyText" class="form-tip">
            环境默认：{{ envDefaultStrategyText }}；此处留空即按默认值执行
          </span>
        </el-form-item>
        <!--
          强制覆盖配置文件：默认不勾选（保守，保护运维现场改动）。
          勾选后，若仓库配置与现场内容不一致，平台先备份旧配置再写入新配置。
          用于「改了仓库配置想下发」的场景——否则默认的「存在即跳过」会让新配置传不下去。
          仅对本次部署生效，属于执行选项而非长期配置。
        -->
        <el-form-item label="配置文件">
          <el-checkbox v-model="createForm.forceOverwriteConfig">强制覆盖目标机已有配置</el-checkbox>
          <el-tooltip placement="top" style="margin-left: 8px">
            <template #content>
              <div class="tooltip-lines">
                <div>默认不覆盖：目标机已存在同名配置时跳过，保护运维手动改动。</div>
                <div>勾选后：若仓库配置与现场内容不一致，先备份旧配置再写入新配置。</div>
                <div>仅对本次部署生效，用于「改了仓库配置想下发」的场景。</div>
              </div>
            </template>
            <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
          </el-tooltip>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="createSubmitting" @click="submitCreate">立即发布</el-button>
      </template>
    </el-dialog>

    <!-- 目标明细抽屉：展示每台机器的发布前后版本与健康检查结果 -->
    <el-drawer
      v-model="targetVisible"
      :title="`部署目标 - ${current?.projectName || ''} ${current?.version || ''}`"
      size="1100px"
      :close-on-click-modal="false"
    >
      <el-table v-loading="targetLoading" :data="targets" border stripe>
        <el-table-column prop="serverName" label="服务器" min-width="140" show-overflow-tooltip align="center" />
        <el-table-column prop="serverHost" label="地址" min-width="130" align="center" />
        <!--
          发布顺序来自创建部署时固化的快照，与执行器决定「谁先发」的顺序同源：
          滚动/分批发布按它从前往后执行，页面上看到的顺序就是实际执行顺序。
          历史部署（该字段上线前创建）未记录顺序，显示为「—」，其顺序由 create_time 兜底。
        -->
        <el-table-column label="发布顺序" width="100" align="center">
          <template #default="{ row }">
            <el-tooltip
              placement="top"
              content="本次部署固化的发布顺序，滚动/分批发布按此顺序从前往后执行；历史部署未记录该顺序"
            >
              <span v-if="row.deployOrder !== null && row.deployOrder !== undefined">{{ row.deployOrder }}</span>
              <span v-else class="muted">—</span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" min-width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="getDeployTargetStatusStyle(row.status).tag">
              {{ row.statusText || getDeployTargetStatusStyle(row.status).label }}
            </el-tag>
          </template>
        </el-table-column>
        <!--
          统一按「当前 → 目标」表述，两种场景都自解释、方向永远与事实一致：
          发布 = 当前(发布前) 到 目标(本次发布版本)；回滚 = 当前(回滚前) 到 目标(要退回的历史版本)。
          不能直接用 oldVersion → newVersion：回滚执行的恰恰是把 new_version 换回 old_version，
          箭头会反向；而且这两个字段名不含角色信息，看的人只能猜「哪个是现在的」。
        -->
        <el-table-column label="版本变化" min-width="250" align="center">
          <template #default="{ row }">
            <el-tooltip placement="top" :content="versionChangeTip(row)">
              <span class="version-change">
                <span class="version-role">当前</span>
                <span class="muted">{{ versionChangeFrom(row) || '无' }}</span>
                <span class="arrow"> → </span>
                <span class="version-role">目标</span>
                <span>{{ versionChangeTo(row) || '-' }}</span>
              </span>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column prop="healthStatus" label="健康检查" min-width="110" align="center">
          <template #default="{ row }">
            <el-tag :type="getHealthStatusStyle(row.healthStatus).tag">
              {{ row.healthStatusText || getHealthStatusStyle(row.healthStatus).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="失败原因" min-width="200" show-overflow-tooltip align="center">
          <template #default="{ row }">
            <span v-if="row.errorMessage" class="failed-count">{{ row.errorMessage }}</span>
            <span v-else class="muted">-</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!targetLoading && targets.length === 0" description="本次部署没有目标记录" />
    </el-drawer>

    <!-- 发布日志抽屉：上方任务状态与进度，下方实时日志控制台 -->
    <!-- 关闭不拦截：只停前端轮询，后端任务照跑，重新打开会重新拉全量日志 -->
    <el-drawer
      v-model="logVisible"
      :title="`发布日志 - ${logCurrent?.projectName || ''}`"
      size="800px"
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

    <!--
      回滚预览：提交前把逐台影响摆清楚。
      预览与提交共用后端的同一份判定，所以这里列出的清单就是执行时真正会发生的清单；
      不可回滚时同样打开弹窗并展示原因，而不是只弹一条「操作失败」。
    -->
    <el-dialog
      v-model="rollbackPreviewVisible"
      :title="`回滚预览 - ${rollbackRow?.projectName || ''}`"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-alert
        v-if="rollbackPreview && !rollbackPreview.rollbackable"
        :title="rollbackPreview.blockReason || '本次部署当前不支持回滚'"
        type="warning"
        :closable="false"
        show-icon
      />
      <template v-else-if="rollbackPreview">
        <el-alert
          :title="rollbackSummary(rollbackPreview)"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        />
        <el-table :data="rollbackPreview.targets" size="small" border max-height="360">
          <el-table-column label="目标服务器" min-width="180">
            <template #default="{ row }">
              <div>{{ row.serverName || row.serverId }}</div>
              <div class="muted">{{ row.serverHost || '-' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="版本变化" min-width="230" align="center">
            <template #default="{ row }">
              <template v-if="row.rollbackable">
                <span class="muted">{{ row.currentVersion || '无' }}</span>
                <span class="arrow"> → </span>
                <span>{{ row.targetVersion || '-' }}</span>
              </template>
              <span v-else class="muted">保持原版本不变</span>
            </template>
          </el-table-column>
          <el-table-column label="结果" min-width="200">
            <template #default="{ row }">
              <el-tag v-if="row.rollbackable" size="small" type="warning">可回滚</el-tag>
              <span v-else class="muted">{{ row.skipReason || '跳过回滚' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="rollbackPreviewVisible = false">取 消</el-button>
        <el-button
          type="warning"
          :disabled="!rollbackPreview?.rollbackable"
          :loading="rollbackSubmitting"
          @click="confirmRollback"
        >
          确认回滚
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 部署（发布）管理页面。
 *
 * 对接后端 /deployment 接口。业务约定：
 * 1. 部署是异步流程：创建后只落记录与目标快照并生成 DEPLOY 任务，真正的分发 / 启动 / 健康检查
 *    由后端执行器完成，页面用 /taskLog/pull 增量拉取实时日志；
 * 2. 新建部署按「项目 → 环境 → 可发布版本」三级联动选择，策略留空即沿用环境默认策略；
 * 3. 回滚只放开给「成功」的部署，取消只放开给「排队 / 发布中」，删除只放开给终态；
 *    按钮按状态显隐，避免用户点了之后被后端拒绝；
 * 3.1 「成功」只是回滚的必要条件，不是充分条件：还要有历史版本、且历史版本的记录与产物仍在
 *    （后端 rollbackable / rollbackBlockReason）。因此回滚按钮在成功行里也可能置灰，
 *    置灰时用 tooltip 说明原因，而不是把入口藏起来让用户猜；
 * 3.2 点回滚不直接提交，先调 rollback-preview 打开预览弹窗，逐台列出「当前版本 → 将回滚到的版本」
 *    与跳过原因，确认后才真正回滚。预览与提交共用后端同一份判定，所以清单即实际影响；
 * 4. 目标明细抽屉展示每台机器的「发布前版本 → 本次版本」，这是回滚时判断依据的关键信息；
 * 5. 发布日志抽屉与其余四处日志视图共用 useTaskLogViewer：增量拉取、下载导出、
 *    任务执行中禁止关闭容器；任务结束时回调整理列表（AGENTS.md §20.9）。
 */
import { ref, reactive, computed, onMounted, onActivated } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search,
  Refresh,
  Delete,
  Document,
  Monitor,
  Promotion,
  RefreshLeft,
  CircleClose,
  QuestionFilled,
  Download
} from '@element-plus/icons-vue'
import {
  pageDeployment,
  createDeployment,
  rollbackDeployment,
  previewRollback,
  cancelDeployment,
  deleteDeployment,
  getDeploymentTargets
} from '@/api/deployment.ts'
import { listReleases } from '@/api/release.ts'
import { listProjects } from '@/api/project.ts'
import { listEnvironments } from '@/api/environment.ts'
import { listProjectEnvironments } from '@/api/deployTarget.ts'
import type {
  DeploymentVo,
  DeploymentTargetVo,
  RollbackPreviewVo,
  ProjectVo,
  EnvironmentVo,
  EnvironmentOptionVo,
  ReleaseRecordVo
} from '@/api/deployTypes.ts'
import { LOG_LEVEL_TAG, getTaskStatusStyle } from '@/constants/taskStatus.ts'
import {
  DEPLOY_STATUS_SUCCESS,
  DEPLOY_STATUS_ROLLING_BACK,
  DEPLOY_STATUS_ROLLED_BACK,
  DEPLOY_STATUS_MAP,
  DEPLOY_STRATEGY_MAP,
  DEPLOY_STRATEGY_OPTIONS,
  DEPLOY_STRATEGY_TIPS,
  DEPLOY_STRATEGY_OVERRIDE_TIPS,
  getDeployStatusStyle,
  getDeployTargetStatusStyle,
  getHealthStatusStyle,
  isDeployFinal,
  canRollback,
  canCancel
} from '@/constants/deploymentStatus.ts'
// 日志查看器与日志时间格式化：5 处日志视图共用同一份实现
import { useTaskLogViewer, formatLogTime } from '@/composables/useTaskLogViewer.ts'

import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'

defineOptions({ name: 'DeploymentList' })

// ==================== 下拉数据 ====================
const projectOptions = ref<ProjectVo[]>([])
/** 搜索区用：全量环境，便于按环境筛选历史发布记录（不限项目） */
const environmentOptions = ref<EnvironmentVo[]>([])
/**
 * 新建部署用：只含当前项目已绑定启用目标的环境。
 *
 * 与搜索区分开是有意的——搜索要能筛到「任何环境的历史记录」，新建部署只能选「这个项目真能发的环境」，
 * 两者的数据源和语义都不同，共用一个列表必然有一边是错的。
 */
const projectEnvOptions = ref<EnvironmentOptionVo[]>([])
const releaseOptions = ref<ReleaseRecordVo[]>([])

async function loadOptions() {
  const [projects, environments] = await Promise.all([listProjects(), listEnvironments()])
  projectOptions.value = projects.data
  environmentOptions.value = environments.data
}

// ==================== 搜索与表格 ====================
const searchForm = reactive({
  projectId: '' as string,
  environmentId: '' as string,
  status: undefined as number | undefined
})
const loading = ref(false)
const tableData = ref<DeploymentVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

/** 创建时间区间：与后端 createTimeBegin / createTimeEnd 对应，未选择时两个参数都不传 */
const timeRange = ref<string[]>([])

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.projectId = ''
  searchForm.environmentId = ''
  searchForm.status = undefined
  timeRange.value = []
  handleSearch()
}

async function fetchData() {
  loading.value = true
  try {
    const res = await pageDeployment({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      projectId: searchForm.projectId || undefined,
      environmentId: searchForm.environmentId || undefined,
      status: searchForm.status,
      ...splitTimeRange(timeRange.value),
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

// ==================== 新建部署 ====================
const createVisible = ref(false)
const createSubmitting = ref(false)
const createFormRef = ref<FormInstance | null>(null)
const createForm = reactive({
  projectId: '' as string,
  environmentId: '' as string,
  releaseId: '' as string,
  strategy: '' as string,
  // 强制覆盖配置文件：默认不勾选（保守，保护运维现场改动）。
  forceOverwriteConfig: false as boolean
})

/** 校验规则与后端 Service 校验镜像：必填项先由前端拦截，后端仍是最终依据 */
const createRules: FormRules = {
  projectId: [{ required: true, message: '请选择项目', trigger: 'change' }],
  environmentId: [{ required: true, message: '请选择环境', trigger: 'change' }],
  releaseId: [{ required: true, message: '请选择发布版本', trigger: 'change' }]
}

/**
 * 「部署策略」下拉的 popper.js 参数：关掉 flip，让面板永远向下展开。
 *
 * 默认行为是「视口内放不下就翻到输入框上方」，弹窗最底部这个字段翻上去正好盖住刚填好的三个字段；
 * popper 的翻转判定只对视口负责，不知道弹窗边界，所以这里显式关掉、固定向下。
 * 由此产生的两个约束分别在别处兜住：弹窗底部预留面板落位空间（styles/index.scss 的 .deploy-create-dialog），
 * 视口底部放不下时按可用空间限高、面板内部滚动（下面的 strategyPanelMaxHeight 修正器）。
 */
const STRATEGY_POPPER_OPTIONS = {
  modifiers: [
    { name: 'flip', enabled: false },
    {
      name: 'strategyPanelMaxHeight',
      enabled: true,
      phase: 'main',
      requiresIfExists: ['offset'],
      /**
       * 按「输入框底边 → 视口底部」的实际空间限制面板高度。
       *
       * 关掉 flip 之后 popper 不再自己兜底，矮窗口里向下展开的面板会被屏幕底部截断；
       * 限高后超出的选项在面板内部滚动，至少保证每一屏都能看全一部分选项。
       *
       * 变量写在 popper 根元素上而不是 state.styles.popper：main 阶段该对象还没由
       * computeStyles 建立，直接写会抛 TypeError（变量由 .strategy-option-popper 下的样式读取）。
       *
       * @param state popper.js 的当前状态：reference 是下拉输入框，popper 是面板根元素
       */
      fn: ({ state }: { state: { elements: { reference: Element; popper: HTMLElement } } }) => {
        const referenceBottom = state.elements.reference.getBoundingClientRect().bottom
        const room = window.innerHeight - referenceBottom - 24
        state.elements.popper.style.setProperty('--strategy-panel-max-height', `${Math.max(120, room)}px`)
      }
    }
  ]
}

/**
 * 当前选中环境的默认策略文案，作为「临时覆盖」的对照物展示在下拉下方。
 *
 * 滚动发布额外带上批次大小，因为批次只在滚动下生效，顺便提醒它取的是环境上的值、这里改不了。
 */
const envDefaultStrategyText = computed(() => {
  const env = projectEnvOptions.value.find(item => item.id === createForm.environmentId)
  if (!env?.deployStrategy) {
    return ''
  }
  const label = DEPLOY_STRATEGY_MAP[env.deployStrategy] || env.deployStrategy
  return env.deployStrategy === 'ROLLING' && env.batchSize
    ? `${label}（批次 ${env.batchSize}）`
    : label
})

function openCreate() {
  createVisible.value = true
}

/**
 * 切换项目后重新加载该项目「能发什么」：可发布版本 + 已绑定启用目标的环境。
 *
 * 版本列表只取「可发布」状态；环境只取已绑定启用目标的，两者都是提交前就能确定的前置条件，
 * 与其让用户在提交时才收到后端报错，不如在下拉里就不给错误选项。
 */
async function handleProjectChange(projectId: string) {
  createForm.releaseId = ''
  createForm.environmentId = ''
  releaseOptions.value = []
  projectEnvOptions.value = []
  if (!projectId) return
  const [releaseRes, envRes] = await Promise.all([
    listReleases({ projectId, status: 1 }),
    listProjectEnvironments(projectId)
  ])
  releaseOptions.value = releaseRes.data
  projectEnvOptions.value = envRes.data
  if (releaseOptions.value.length === 0) {
    ElMessage.warning('该项目暂无可发布版本，请先完成一次成功的构建')
  }
  if (projectEnvOptions.value.length === 0) {
    ElMessage.warning('该项目还没有绑定启用状态的部署目标，请先在「部署目标」中绑定服务器')
  }
}

/**
 * 版本下拉里跟在版号后面的构建机说明。
 *
 * 产物在哪台机器决定了这个版本能不能发出去：没记构建机的历史版本一定发不了，
 * 说在前面，省得点了发布才在日志里看到「未记录构建机」。
 */
function releaseServerLabel(item: ReleaseRecordVo): string {
  if (!item.buildServerId) return '未记录构建机（多为历史版本）'
  return `构建机 ${item.buildServerName || item.buildServerId}`
}

function resetCreate() {
  createForm.projectId = ''
  createForm.environmentId = ''
  createForm.releaseId = ''
  createForm.strategy = ''
  createForm.forceOverwriteConfig = false
  releaseOptions.value = []
  projectEnvOptions.value = []
  createFormRef.value?.resetFields()
}

async function submitCreate() {
  const form = createFormRef.value
  if (!form) return
  await form.validate()
  createSubmitting.value = true
  try {
    const res = await createDeployment({
      projectId: createForm.projectId,
      environmentId: createForm.environmentId,
      releaseId: createForm.releaseId,
      strategy: createForm.strategy || undefined,
      forceOverwriteConfig: createForm.forceOverwriteConfig || undefined
    })
    ElMessage.success('已触发部署')
    createVisible.value = false
    fetchData()
    // 创建成功后直接打开日志抽屉，让发布过程可见。
    openLog(res.data)
  } finally {
    createSubmitting.value = false
  }
}

// ==================== 目标明细 ====================
const targetVisible = ref(false)
const targetLoading = ref(false)
const current = ref<DeploymentVo | null>(null)
const targets = ref<DeploymentTargetVo[]>([])

async function handleViewTargets(row: DeploymentVo) {
  current.value = row
  targetVisible.value = true
  targetLoading.value = true
  targets.value = []
  try {
    const res = await getDeploymentTargets(row.id)
    targets.value = res.data
  } finally {
    targetLoading.value = false
  }
}

// ==================== 版本展示 ====================
/**
 * 当前目标明细抽屉对应的部署是不是回滚动作。
 *
 * 判定依据只有主任务状态：回滚中（5）与已回滚（6）。回滚是在原部署记录上原地执行的
 * （状态 5 → 6），所以这两个状态就代表「这次动作是回滚」，此时目标行的 old_version /
 * new_version 的角色与正常发布相反：new_version 是「回滚前机器上的版本」，
 * old_version 才是「这次要退回去的目标版本」。
 */
function isRollbackScene() {
  const status = current.value?.status
  return status === DEPLOY_STATUS_ROLLING_BACK || status === DEPLOY_STATUS_ROLLED_BACK
}

/** 版本对照的「当前」侧：发布场景是发布前的 oldVersion，回滚场景是回滚前的 newVersion */
function versionChangeFrom(row: DeploymentTargetVo) {
  return isRollbackScene() ? row.newVersion : row.oldVersion
}

/** 版本对照的「目标」侧：发布场景是本次发布版本 newVersion，回滚场景是要退回的历史版本 oldVersion */
function versionChangeTo(row: DeploymentTargetVo) {
  return isRollbackScene() ? row.oldVersion : row.newVersion
}

/** 版本变化列的悬浮说明，同时讲清两个版本的来路，避免只看到箭头两头却不知道谁是现在 */
function versionChangeTip(row: DeploymentTargetVo) {
  const from = versionChangeFrom(row) || '无'
  const to = versionChangeTo(row) || '未知'
  return isRollbackScene()
    ? `本次回滚把该机器从 ${from} 退回 ${to}（当前＝回滚前机器上跑的版本，目标＝要退回的历史版本）`
    : `本次发布把该机器从 ${from} 更新到 ${to}（当前＝发布前机器上跑的版本，目标＝本次要发布的版本）`
}

/**
 * 列表版本列是否补「当前 xxx」副行。
 *
 * 三个条件缺一不可：已回滚、服务端给出了当前版本、它确实不同于本次发布版本。
 * 多目标版本不一致（部分回滚、部分首次发布）时服务端不返回 currentVersion，
 * 此时不显示副行——避免以偏概全，逐台真实版本到目标明细里看。
 */
function showCurrentVersion(row: DeploymentVo) {
  return (
    row.status === DEPLOY_STATUS_ROLLED_BACK &&
    !!row.currentVersion &&
    row.currentVersion !== row.version
  )
}

/** 列表版本列的悬浮说明：把「本次发布版本」与「机器当前实际运行的版本」两个概念分开讲 */
function versionCellTip(row: DeploymentVo) {
  if (showCurrentVersion(row)) {
    return `本次发布版本 ${row.version}；机器当前实际运行 ${row.currentVersion}（已回滚到历史版本）`
  }
  if (row.status === DEPLOY_STATUS_SUCCESS) {
    return `本次发布版本 ${row.version}，机器当前实际运行的就是它`
  }
  return `本次发布的目标版本 ${row.version}；实际是否换上去以状态与目标明细为准`
}

// ==================== 回滚 / 取消 / 删除 ====================
/**
 * 回滚按钮的悬浮提示。
 *
 * 可回滚时给出台数（含将保持原版本的数量），不可回滚时给出原因——两者都取自服务端判定，
 * 页面不自己拿 status 推断，避免提示与实际执行结果不一致。
 */
function rollbackTooltip(row: DeploymentVo) {
  if (!row.rollbackable) {
    return row.rollbackBlockReason || '本次发布没有可回滚的历史版本'
  }
  const base = `可回滚 ${row.rollbackTargetCount ?? 0} 个目标`
  const skipped = row.rollbackSkippedCount ?? 0
  return skipped > 0 ? `${base}，另有 ${skipped} 个目标将保持原版本` : base
}

/**
 * 预览弹窗顶部的结论行。
 *
 * 先给「几台会退」，再补「几台不动」——使用者最关心的是这次到底会动哪些机器。
 */
function rollbackSummary(preview: RollbackPreviewVo) {
  const base = `共 ${preview.targetTotal} 个目标，本次回滚 ${preview.rollbackCount} 个到历史版本`
  return preview.skippedCount > 0
    ? `${base}；另有 ${preview.skippedCount} 个保持原版本不变（原因见下表）`
    : base
}

/** 回滚预览弹窗是否可见 */
const rollbackPreviewVisible = ref(false)
/** 预览结果：总结论 + 逐目标版本对照 */
const rollbackPreview = ref<RollbackPreviewVo | null>(null)
/** 当前正在预览回滚的部署记录 */
const rollbackRow = ref<DeploymentVo | null>(null)
/** 确认回滚的提交中状态，避免重复点击发出两次回滚 */
const rollbackSubmitting = ref(false)

async function handleRollback(row: DeploymentVo) {
  // 二次守卫：按钮虽然已按 rollbackable 置灰，但列表可能是旧数据（例如另一个页面刚把历史版本清掉），
  // 这里再拦一次并说明原因，避免发一个注定被后端拒绝的请求。
  if (!row.rollbackable) {
    ElMessage.warning(row.rollbackBlockReason || '本次发布没有可回滚的历史版本')
    return
  }
  // 先取预览再确认：让使用者看清「哪几台退到哪个版本、哪几台会跳过」，
  // 而不是只看到一句通用提示就点了确认。预览是只读接口，不会改变任何状态。
  const res = await previewRollback(row.id)
  rollbackPreview.value = res.data
  rollbackRow.value = row
  rollbackPreviewVisible.value = true
}

async function confirmRollback() {
  const row = rollbackRow.value
  if (!row) {
    return
  }
  rollbackSubmitting.value = true
  try {
    const res = await rollbackDeployment(row.id)
    rollbackPreviewVisible.value = false
    ElMessage.success('已发起回滚')
    fetchData()
    openLog(res.data)
  } finally {
    rollbackSubmitting.value = false
  }
}

async function handleCancel(row: DeploymentVo) {
  await ElMessageBox.confirm('确定要取消本次部署吗？已开始的目标不会被强制中断。', '取消确认', { type: 'warning' })
  await cancelDeployment(row.id)
  ElMessage.success('已取消')
  fetchData()
}

async function handleDelete(row: DeploymentVo) {
  await ElMessageBox.confirm(`确定要删除部署记录「${row.projectName || row.id}」吗？仅终态部署可删除。`, '提示', {
    type: 'warning'
  })
  await deleteDeployment(row.id)
  ElMessage.success('删除成功')
  fetchData()
}

// ==================== 发布日志 ====================
const logVisible = ref(false)
/** 当前查看日志的部署记录，用于抽屉标题 */
const logCurrent = ref<DeploymentVo | null>(null)

// 日志查看器：轮询、增量锚点、自动滚动、下载与运行中禁止关闭统一由它负责。
// 这里**刻意不传 status**：部署记录的 status 是 DeploymentStatus（含 5 回滚中 / 6 已回滚），
// 与 TaskStatus 的编码只是部分重合，拿来当初值会让「运行中」判定出错，状态一律以首次拉取为准。
// onFinished 用于在任务结束时刷新列表：抽屉里状态已是终态，表格还停在打开日志前的旧数据。
const { bodyRef, logs, task, running, downloading, progressStatus, start, stop, download } =
  useTaskLogViewer({ onFinished: fetchData })

/**
 * 打开发布日志抽屉。
 *
 * @param row 部署记录，需携带 taskId 才能拉取日志（缺失时查看器会给出提示）
 */
function openLog(row: DeploymentVo) {
  logCurrent.value = row
  logVisible.value = true
  start({ taskId: row.taskId, taskNo: row.taskNo })
}

function handleViewLog(row: DeploymentVo) {
  openLog(row)
}

/** 关闭发布日志抽屉；@closed 会触发 stop 停掉轮询，后端任务不受影响 */
function closeLog() {
  logVisible.value = false
}

onMounted(async () => {
  await loadOptions()
  fetchData()
})

// 本页被 keep-alive 缓存（切走再切回不重跑 setup），部署状态与项目/环境下拉都会变化，
// 回到本页时重新加载选项与列表，避免新建的项目/环境选不到、部署进度停在旧值。
// 首次激活发生在挂载之后，此时上面已经加载过，跳过以免重复请求。
let activatedOnce = false
onActivated(async () => {
  if (!activatedOnce) {
    activatedOnce = true
    return
  }
  await loadOptions()
  fetchData()
})
</script>

<style lang="scss" scoped>
// 与构建页、服务器测试抽屉共用同一套抽屉顶部样式
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

.muted {
  color: var(--text-secondary);
}

.failed-count {
  color: var(--el-color-danger);
}

.arrow {
  margin: 0 4px;
  color: var(--text-secondary);
}

/** 版本单元格：主行是本次发布版本，副行（仅已回滚时出现）是机器当前实际版本 */
.version-cell {
  display: flex;
  flex-direction: column;
  line-height: 1.4;
}

/** 副行弱化显示，不抢主版本的视觉重心 */
.version-current {
  font-size: 12px;
  color: var(--el-color-warning);
}

/** 版本对照的「当前 / 目标」角色词，与版本号区分开，避免被当成版本号的一部分 */
.version-role {
  margin-right: 4px;
  font-size: 12px;
  color: var(--text-secondary);
}

/**
 * 回滚按钮的 tooltip 包裹层。
 * inline-block 让 span 跟着按钮走，不额外撑高操作列；
 * disabled 的 el-button 不派发鼠标事件，tooltip 只能挂在这层包装上。
 */
.rollback-button-wrapper {
  display: inline-block;
}
</style>
