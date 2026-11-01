<!--
  AIops 联动抽屉：一次发布在 AIops 侧留下的全部痕迹。

  两块内容，两种数据形态，看的时候不要混淆：
  1. 静默窗（1 条）——状态型资源，同一行从头用到尾：建、到期、关、原因；
  2. 事件流水（多条）——一次发布是「4 条部署级 + 每台目标 1 条」，
     不是重复上报，条数公式见 docs/AIops联动.md §4。

  只读组件：不提供建窗/关窗按钮，那些动作只允许发布链路自己做（幂等与降级都在链路内部）。
-->
<template>
  <el-drawer
    v-model="visible"
    :title="drawerTitle"
    size="780px"
    :close-on-click-modal="false"
    @open="load"
  >
    <div v-loading="loading" class="aiops-link">
      <!-- 开关状态先于细节：没有窗口时，第一句要能回答「为什么没有」 -->
      <el-alert
        v-if="data && !data.globalEnabled"
        type="warning"
        :closable="false"
        show-icon
        title="平台 AIops 联动总闸未开启"
        description="light-deploy.aiops.enabled=false，整条联动链路短路：不建静默窗、不推送事件。这是配置决定，不是故障。"
      />
      <el-alert
        v-else-if="data && !data.environmentAiopsEnabled"
        type="info"
        :closable="false"
        show-icon
        title="该环境未开启 AIops 联动"
        description="环境级开关（environment.aiops_enabled）关闭时只跳过静默窗，事件仍会推送——审计视角与降噪视角是分开的。"
      />

      <!-- ==================== 静默窗 ==================== -->
      <div class="section">
        <div class="section-title">
          <span>静默窗</span>
          <span class="section-tip">一次发布最多一条，生命周期在这一行内演进</span>
        </div>

        <template v-if="data?.silence">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="窗口 ID">{{ data.silence.silenceId }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="silenceTag(data.silence)" size="small">
                {{ data.silence.statusText }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="生效开始">{{ fmt(data.silence.startAt) }}</el-descriptions-item>
            <el-descriptions-item label="到期时间">{{ fmt(data.silence.expiresAt) }}</el-descriptions-item>
            <el-descriptions-item label="关闭时间">{{ fmt(data.silence.closeTime) }}</el-descriptions-item>
            <el-descriptions-item label="关闭原因">{{ data.silence.closeReason || '—' }}</el-descriptions-item>
            <el-descriptions-item label="覆盖主机" :span="2">
              <template v-if="data.silence.hosts?.length">
                <el-tag v-for="host in data.silence.hosts" :key="host" size="small" effect="plain" class="inline-tag">
                  {{ host }}
                </el-tag>
              </template>
              <span v-else class="muted">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="静默类型" :span="2">
              <template v-if="data.silence.alertCategories?.length">
                <el-tag
                  v-for="item in data.silence.alertCategories"
                  :key="item"
                  size="small"
                  type="info"
                  class="inline-tag"
                >
                  {{ item }}
                </el-tag>
              </template>
              <span v-else class="muted">—</span>
            </el-descriptions-item>
            <el-descriptions-item label="项目 / 环境">
              {{ data.silence.projectCode || '—' }} / {{ data.silence.environmentCode || '—' }}
            </el-descriptions-item>
            <el-descriptions-item label="调用方">{{ data.silence.source || '—' }}</el-descriptions-item>
          </el-descriptions>
        </template>
        <el-empty
          v-else-if="!loading"
          :description="data?.silenceMessage || '本次发布没有静默窗'"
          :image-size="60"
        />
      </div>

      <!-- ==================== 事件流水 ==================== -->
      <div class="section">
        <div class="section-title">
          <span>事件流水（{{ data?.events?.length || 0 }} 条）</span>
          <span class="section-tip">部署级 4 条 + 每台目标 1 条；不是重复上报</span>
        </div>

        <el-timeline v-if="data?.events?.length" class="event-timeline">
          <el-timeline-item
            v-for="event in data.events"
            :key="event.id"
            :timestamp="fmt(event.occurredAt)"
            placement="top"
            :type="eventTag(event.eventType)"
            :hollow="event.status !== 1"
          >
            <div class="event-row">
              <el-tag :type="eventTag(event.eventType)" size="small">
                {{ event.sequence }} · {{ event.eventTypeLabel }}
              </el-tag>
              <el-tag v-if="event.host" size="small" effect="plain">{{ event.host }}</el-tag>
              <el-tag :type="deliveryTag(event.status)" size="small" effect="plain">
                投递 {{ event.statusText }}
              </el-tag>
              <span v-if="event.retryCount" class="muted">已重试 {{ event.retryCount }} 次</span>
            </div>
            <div v-if="event.nextRetryTime" class="event-note">下次重试：{{ fmt(event.nextRetryTime) }}</div>
            <div v-if="event.errorMessage" class="event-error">{{ event.errorMessage }}</div>
            <div v-if="event.deliveredAt" class="event-note">送达：{{ fmt(event.deliveredAt) }}</div>
          </el-timeline-item>
        </el-timeline>
        <el-empty
          v-else-if="!loading"
          description="本次发布没有联动事件（总闸关闭，或发布尚未开始）"
          :image-size="60"
        />
      </div>
    </div>
  </el-drawer>
</template>

<script setup lang="ts">
/**
 * 联动详情抽屉。
 *
 * <p>打开时才拉数据（`@open`），不用列表行数据预填：静默窗详情要实时向 AIops 查，
 * 列表里没有这份数据，预填只会让人以为「列表上有就是最新的」。</p>
 */
import { computed, ref } from 'vue'
import { getDeploymentAiopsLink } from '@/api/aiops.ts'
import type { AiopsLinkVo, AiopsSilenceVo, DeploymentVo } from '@/api/deployTypes.ts'
import {
  AIOPS_DELIVERY_TAG,
  AIOPS_EVENT_TAG,
  AIOPS_EVENT_TAG_FALLBACK,
  AIOPS_SILENCE_TAG
} from '@/constants/aiopsStatus.ts'

defineOptions({ name: 'AiopsLinkDrawer' })

const props = defineProps<{
  modelValue: boolean
  deployment: DeploymentVo | null
}>()

const emit = defineEmits<{ (e: 'update:modelValue', value: boolean): void }>()

/** 抽屉可见性由父组件控制，组件内只做双向代理 */
const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value)
})

const loading = ref(false)
const data = ref<AiopsLinkVo | null>(null)

const drawerTitle = computed(() => {
  const name = props.deployment?.projectName || ''
  const version = props.deployment?.version || ''
  return `AIops 联动 - ${name} ${version}`.trim()
})

/** 拉取联动详情：失败也把抽屉留在打开状态，页面提示由 request.ts 统一弹出 */
async function load() {
  if (!props.deployment?.id) {
    return
  }
  loading.value = true
  data.value = null
  try {
    const res = await getDeploymentAiopsLink(props.deployment.id)
    data.value = res.data ?? null
  } finally {
    loading.value = false
  }
}

/**
 * 时间展示。
 *
 * <p>后端 LocalDateTime 走 ISO 序列化（带 `T`），直接展示在表格里很难读；
 * 这里只做「T → 空格」的轻量处理，不引入额外的格式化库，也不改变原始精度。</p>
 */
function fmt(value?: string) {
  return value ? value.replace('T', ' ') : '—'
}

/** 事件类型颜色，未知类型回落为 info */
function eventTag(eventType: string) {
  return AIOPS_EVENT_TAG[eventType] || AIOPS_EVENT_TAG_FALLBACK
}

/** 投递状态颜色 */
function deliveryTag(status: number) {
  return AIOPS_DELIVERY_TAG[status] || 'info'
}

/**
 * 静默窗状态颜色。
 *
 * <p>已过期的窗口对端不落库（按到期时间实时算），所以先看 expired 再看 status。</p>
 */
function silenceTag(silence: AiopsSilenceVo) {
  if (silence.expired) {
    return AIOPS_SILENCE_TAG[2]
  }
  return AIOPS_SILENCE_TAG[silence.status] || 'info'
}
</script>

<style scoped>
.aiops-link {
  min-height: 200px;
}

.section {
  margin-top: 20px;
}

.section-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  padding-bottom: 8px;
  margin-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.section-tip {
  font-size: 12px;
  font-weight: normal;
  color: var(--el-text-color-secondary);
}

.inline-tag {
  margin-right: 6px;
  margin-bottom: 4px;
}

.event-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.event-note {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.event-error {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-color-danger);
  word-break: break-all;
}

.event-timeline {
  padding-left: 4px;
}

.muted {
  color: var(--el-text-color-secondary);
}
</style>
