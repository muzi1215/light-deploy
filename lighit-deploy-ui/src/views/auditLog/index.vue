<template>
  <div class="app-container">
    <!-- 搜索区域：文本输入框支持回车触发查询（约定见 AGENTS.md §20.5） -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="操作人">
          <el-input
            v-model="searchForm.operName"
            placeholder="操作人，模糊匹配"
            clearable
            style="width: 160px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="操作模块">
          <el-input
            v-model="searchForm.title"
            placeholder="如：部署目标，模糊匹配"
            clearable
            style="width: 180px"
            @keyup.enter="handleSearch"
          />
        </el-form-item>
        <el-form-item label="操作类型">
          <el-select v-model="searchForm.businessType" placeholder="请选择" clearable style="width: 140px">
            <el-option v-for="item in BUSINESS_TYPE_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.result" placeholder="请选择" clearable style="width: 120px">
            <el-option label="成功" :value="AUDIT_RESULT_SUCCESS" />
            <el-option label="失败" :value="AUDIT_RESULT_FAILURE" />
          </el-select>
        </el-form-item>
        <!-- 创建时间区间：与其他列表共用同一个组件，避免各页在默认时刻这类细节上分叉 -->
        <CreateTimeRange v-model="timeRange" />
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 数据表格：居中、min-width、操作列固定右侧（约定见 AGENTS.md §20.8） -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column type="index" label="序号" width="70" align="center" />
      <el-table-column prop="operName" label="操作人" min-width="120" align="center">
        <template #default="{ row }">
          {{ row.operName || '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="title" label="操作模块" min-width="130" align="center" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.title || '—' }}
        </template>
      </el-table-column>
      <el-table-column prop="businessType" label="操作类型" min-width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="businessTypeTag(row.businessType)" size="small">
            {{ row.businessTypeText || businessTypeText(row.businessType) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="requestMethod" label="请求方式" min-width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="requestMethodTag(row.requestMethod)" size="small">
            {{ row.requestMethod || '—' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="operUrl" label="请求地址" min-width="180" align="center" show-overflow-tooltip />
      <el-table-column prop="operIp" label="操作IP" min-width="130" align="center" />
      <el-table-column prop="costTime" label="耗时" min-width="90" align="center">
        <template #default="{ row }">
          {{ row.costTime == null ? '—' : `${row.costTime} ms` }}
        </template>
      </el-table-column>
      <el-table-column prop="result" label="状态" min-width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.result === AUDIT_RESULT_SUCCESS ? 'success' : 'danger'" size="small">
            {{ row.resultText || (row.result === AUDIT_RESULT_SUCCESS ? '成功' : '失败') }}
          </el-tag>
        </template>
      </el-table-column>
      <!-- 变更列：先于「详情」就知道有没有实质改动，省掉逐个点开的成本 -->
      <el-table-column label="变更" min-width="90" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.changedFields?.length" type="warning" size="small">
            {{ row.changedFields.length }} 项
          </el-tag>
          <span v-else style="color: var(--el-text-color-placeholder, #c0c4cc)">—</span>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="操作时间" min-width="180" align="center" />
      <el-table-column label="操作" width="110" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="View" @click="handleDetail(row)">详情</el-button>
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

    <!-- 详情对话框：只展示，无表单，无需校验（约定见 AGENTS.md §20.2） -->
    <el-dialog v-model="detailVisible" title="操作日志详情" width="720px" :close-on-click-modal="false">
      <div style="max-height: 60vh; overflow-y: auto; padding-right: 10px">
        <!-- 基本信息 -->
        <el-card shadow="never" style="margin-bottom: 15px">
          <template #header>
            <span style="font-weight: bold">基本信息</span>
          </template>
          <el-row :gutter="20">
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">应用系统</div>
                <!-- 取值由后端按配置统一写入，页面只展示不提供编辑 -->
                <div>{{ current.application || '—' }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">操作模块</div>
                <div>{{ current.title || '—' }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">操作类型</div>
                <div>
                  <el-tag :type="businessTypeTag(current.businessType)" size="small">
                    {{ current.businessTypeText || businessTypeText(current.businessType) }}
                  </el-tag>
                </div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">操作人</div>
                <div>{{ current.operName || '—' }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">操作IP</div>
                <div>{{ current.operIp || '—' }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">操作系统</div>
                <div>{{ current.os || '—' }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">浏览器</div>
                <div>{{ current.browser || '—' }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">操作时间</div>
                <div>{{ current.createTime || '—' }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">耗时</div>
                <div>{{ current.costTime == null ? '—' : `${current.costTime} ms` }}</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">结果</div>
                <div>
                  <el-tag :type="current.result === AUDIT_RESULT_SUCCESS ? 'success' : 'danger'" size="small">
                    {{ current.resultText || (current.result === AUDIT_RESULT_SUCCESS ? '成功' : '失败') }}
                  </el-tag>
                </div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="detail-item">
                <div class="detail-label">追踪ID</div>
                <div style="word-break: break-all">{{ current.traceId || '—' }}</div>
              </div>
            </el-col>
            <!-- 失败原因只在失败时出现：成功时留一排空格子没有意义 -->
            <el-col v-if="current.result === AUDIT_RESULT_FAILURE" :span="24">
              <div class="detail-item">
                <div class="detail-label">失败原因</div>
                <div style="color: var(--el-color-danger); word-break: break-all">
                  {{ current.errorMessage || '—' }}
                </div>
              </div>
            </el-col>
          </el-row>
        </el-card>

        <!-- 请求信息 -->
        <el-card shadow="never" style="margin-bottom: 15px">
          <template #header>
            <span style="font-weight: bold">请求信息</span>
          </template>
          <div class="detail-item">
            <div class="detail-label">请求地址</div>
            <div style="word-break: break-all">{{ current.operUrl || '—' }}</div>
          </div>
          <div class="detail-item">
            <div class="detail-label">请求方式</div>
            <div>
              <el-tag :type="requestMethodTag(current.requestMethod)" size="small">
                {{ current.requestMethod || '—' }}
              </el-tag>
            </div>
          </div>
          <div class="detail-item">
            <div class="detail-label">操作方法</div>
            <div style="font-family: monospace; word-break: break-all">{{ current.method || '—' }}</div>
          </div>
        </el-card>

        <!-- 变更明细：回答「哪个字段从什么改成了什么」，是详情页里信息密度最高的部分 -->
        <el-card shadow="never" style="margin-bottom: 15px">
          <template #header>
            <div style="display: flex; justify-content: space-between; align-items: center; gap: 10px">
              <span style="font-weight: bold">变更明细</span>
              <span v-if="current.resourceType" style="font-size: 12px; color: var(--el-text-color-secondary)">
                {{ current.resourceType }} · {{ current.resourceId || '—' }}
              </span>
            </div>
          </template>
          <el-table v-if="current.changedFields?.length" :data="current.changedFields" size="small" border stripe>
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column label="字段" min-width="110" align="center">
              <template #default="{ row }">
                {{ fieldLabel(current.resourceType, row.field) }}
              </template>
            </el-table-column>
            <el-table-column label="变更前" min-width="150">
              <template #default="{ row }">
                <span :style="mutedStyle(row.oldValue)" style="word-break: break-all">{{ row.oldValue }}</span>
                <span v-if="showRawField(row.field)" class="field-hint">{{ row.field }}</span>
              </template>
            </el-table-column>
            <el-table-column label="变更后" min-width="150">
              <template #default="{ row }">
                <span :style="dangerStyle(row.newValue)" style="word-break: break-all">{{ row.newValue }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-else class="empty-tip">
            本次没有记录到字段变更（未声明资源类型、无实际改动，或操作对象已被删除）
          </div>
        </el-card>

        <!-- 参数信息 -->
        <el-card shadow="never">
          <template #header>
            <span style="font-weight: bold">参数信息</span>
          </template>
          <el-tabs>
            <el-tab-pane label="请求参数">
              <pre v-if="current.operParam" class="json-block">{{ formatJson(current.operParam) }}</pre>
              <div v-else class="empty-tip">无请求参数（含凭据的接口不记录参数）</div>
            </el-tab-pane>
            <el-tab-pane label="返回结果">
              <pre v-if="current.jsonResult" class="json-block">{{ formatJson(current.jsonResult) }}</pre>
              <div v-else class="empty-tip">无返回结果</div>
            </el-tab-pane>
          </el-tabs>
        </el-card>
      </div>

      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 操作日志列表。
 *
 * 数据全部来自后端 AOP 切面写入的 audit_log：只有标注了 @OperLog 的接口才会留痕，
 * 因此这里看不到查询类操作是预期行为，不是漏记。
 */
import { ref, reactive, onMounted } from 'vue'
import { Search, Refresh, View } from '@element-plus/icons-vue'
import CreateTimeRange from '@/components/CreateTimeRange.vue'
import { splitTimeRange } from '@/utils/index.ts'
import { pageAuditLog } from '@/api/auditLog.ts'
import type { AuditLogVo, AuditLogQuery } from '@/api/deployTypes.ts'
import {
  BUSINESS_TYPE_OPTIONS,
  AUDIT_RESULT_SUCCESS,
  AUDIT_RESULT_FAILURE,
  businessTypeText,
  businessTypeTag,
  requestMethodTag,
  fieldLabel,
  EMPTY_VALUE,
  DELETED_VALUE
} from '@/constants/auditLog.ts'

defineOptions({ name: 'AuditLogList' })

// ==================== 搜索与表格 ====================
const searchForm = reactive({
  operName: '' as string,
  title: '' as string,
  businessType: '' as string,
  result: undefined as number | undefined
})
/** 时间范围单独存放：提交前拆成 createTimeBegin / createTimeEnd 两个字段 */
const timeRange = ref<string[]>([])
const loading = ref(false)
const tableData = ref<AuditLogVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.operName = ''
  searchForm.title = ''
  searchForm.businessType = ''
  searchForm.result = undefined
  timeRange.value = []
  handleSearch()
}

async function fetchData() {
  loading.value = true
  try {
    const params: AuditLogQuery = {
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      operName: searchForm.operName || undefined,
      title: searchForm.title || undefined,
      businessType: searchForm.businessType || undefined,
      result: searchForm.result,
      ...splitTimeRange(timeRange.value)
    }
    const res = await pageAuditLog(params)
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

// ==================== 详情 ====================
const detailVisible = ref(false)
/** 详情弹窗的初始值：字段写全而不是靠类型断言绕过，缺字段会让模板的首帧渲染多一次无谓判空 */
function emptyAuditLog(): AuditLogVo {
  return { id: '', result: AUDIT_RESULT_SUCCESS, createTime: '', changedFields: [] }
}

const current = ref<AuditLogVo>(emptyAuditLog())

function handleDetail(row: AuditLogVo) {
  // 后端保证非空，这里再兜一次：历史数据在明细功能上线前没有该字段。
  current.value = { ...row, changedFields: row.changedFields ?? [] }
  detailVisible.value = true
}

/**
 * 取「变更前」值的文字样式。
 *
 * @param value 展示值
 * @return 占位值（-）降低视觉权重，避免和真实空值混淆
 */
function mutedStyle(value: string): Record<string, string> {
  return value === EMPTY_VALUE ? { color: 'var(--el-text-color-placeholder)' } : {}
}

/**
 * 取「变更后」值的文字样式。
 *
 * @param value 展示值
 * @return 删除标记转红，让「这条记录被删了」一眼可见
 */
function dangerStyle(value: string): Record<string, string> {
  return value === DELETED_VALUE ? { color: 'var(--el-color-danger)' } : {}
}

/**
 * 判断是否需要把原始字段名作为副注展示。
 *
 * @param field 字段名
 * @return true 表示翻译命中（中文名与原字段名不同），此时附上原名便于对照文档与检索日志
 */
function showRawField(field: string): boolean {
  return fieldLabel(current.value.resourceType, field) !== field
}

/**
 * 把后端存的 JSON 文本格式化展示。
 *
 * 解析失败时原样输出：截断过的文本确实不是合法 JSON，此时原样展示至少还能看到前半段，
 * 比显示「解析失败」有用。
 */
function formatJson(json: string): string {
  if (!json) return '无数据'
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

onMounted(fetchData)
</script>

<style scoped>
.detail-item {
  margin-bottom: 10px;
}

.detail-label {
  color: var(--el-text-color-secondary, #909399);
  font-size: 13px;
}

.json-block {
  margin: 0;
  padding: 10px;
  border-radius: 4px;
  background: var(--el-fill-color-light, #f5f7fa);
  font-family: monospace;
  font-size: 12px;
  max-height: 200px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}

.empty-tip {
  color: var(--el-text-color-secondary, #909399);
  text-align: center;
  padding: 20px;
}

/* 字段名原文：翻译命中后回落到副注，保持可按字段名检索 */
.field-hint {
  display: block;
  margin-top: 2px;
  color: var(--el-text-color-placeholder, #c0c4cc);
  font-family: monospace;
  font-size: 12px;
  word-break: break-all;
}
</style>
