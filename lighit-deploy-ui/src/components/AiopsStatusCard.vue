<!--
  AIops 实时状态卡（首页）。

  数据源：GET /aiops/status（平台级只读探针，不绑定任何部署）。
  三档状态：
    1. 未启用联动  —— 总闸关闭（enabled=false），平台根本不连 AIops；
    2. 联动正常    —— 总闸开且对端可达（reachable=true），绿点呼吸动画；
    3. 后端不可达  —— 总闸开但对端探测失败，红点，需排障。

  轮询：挂载即拉一次，之后按 intervalMs（默认 30 秒）周期调用 /aiops/status；
        “刷新于 X 秒前”由 1s 心跳文案驱动（心跳只改文案，不发请求）。
        【改间隔】父组件传 :interval-ms（毫秒，传 0 关闭轮询），或改组件默认值 30_000。
  本卡位于白色 el-card 上，按钮用普通 primary link 样式（幽灵按钮仅用于蓝色渐变背景，见 docs/前端工程.md）。
-->
<template>
  <el-card class="aiops-status-card" shadow="never">
    <div class="status-head">
      <span class="status-dot" :class="stateClass" />
      <span class="status-title">AIops 联动状态</span>
      <span class="status-text" :class="stateClass">{{ stateText }}</span>
    </div>

    <el-descriptions :column="1" border size="small" class="status-desc">
      <el-descriptions-item label="联动总闸">
        <el-tag :type="status?.enabled ? 'success' : 'info'" size="small" effect="light">
          {{ status?.enabled ? '已开启' : '未启用' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="后端连通">
        <el-tag :type="backendTagType" size="small" effect="light">
          {{ backendText }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="对端地址">
        <span class="mono">{{ status?.baseUrl || '—' }}</span>
      </el-descriptions-item>
    </el-descriptions>

    <div class="status-foot">
      <span class="refresh-hint">{{ refreshText }}</span>
      <el-button type="primary" link :icon="Link" @click="openPlatform">
        前往 AIops 平台
      </el-button>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { Link } from '@element-plus/icons-vue'
import { getAiopsStatus } from '@/api/aiops.ts'
import type { AiopsStatusVo } from '@/api/deployTypes.ts'

defineOptions({ name: 'AiopsStatusCard' })

const props = withDefaults(
  defineProps<{
    /** AIops 平台外链地址（「前往 AIops 平台」按钮使用） */
    url?: string
    /**
     * 轮询间隔（毫秒），默认 30000（30 秒）。
     *
     * 【怎么改刷新频率】三种改法，任选其一：
     *  1. 父组件传参（推荐，不动本组件）：<AiopsStatusCard :interval-ms="10000" />
     *  2. 直接改本处默认值 30_000；
     *  3. 传 0（或负数）→ 不轮询，只在挂载时拉一次。
     *
     * 换算：1 秒 = 1000，5 秒 = 5000，1 分钟 = 60000。
     * 选 30s 的理由：联动总闸 / 对端存活属于低频状态，再快没有收益，
     * 只会给 AIops 的 /openapi/v1/health 添无谓探测压力。
     */
    intervalMs?: number
  }>(),
  { intervalMs: 30_000 }
)

/**
 * 实际生效的轮询间隔（毫秒）。
 *
 * `Math.max(0, ...)` 把 0 / 负数归零，避免 `setInterval(0)` 变成「疯狂刷接口」；
 * 归零后 onMounted 会跳过 setInterval，即「只在挂载时拉一次」。
 */
const pollIntervalMs = Math.max(0, props.intervalMs)

const status = ref<AiopsStatusVo | null>(null)
const loadError = ref(false)
const fetchedAt = ref<number>(0)
const now = ref<number>(Date.now())

let pollTimer: ReturnType<typeof setInterval> | null = null
let tickTimer: ReturnType<typeof setInterval> | null = null

type State = 'disabled' | 'online' | 'offline'

/** 三档状态：总闸关 → 未启用；开且可达 → 正常；开不可达 → 不可达。 */
const state = computed<State>(() => {
  if (!status.value?.enabled) {
    return 'disabled'
  }
  return status.value.reachable ? 'online' : 'offline'
})

const stateClass = computed(() => `is-${state.value}`)
const stateText = computed(() => {
  switch (state.value) {
    case 'online':
      return '联动正常'
    case 'offline':
      return '后端不可达'
    default:
      return '未启用联动'
  }
})

const backendTagType = computed<'success' | 'danger' | 'info'>(() => {
  if (!status.value?.enabled) {
    return 'info'
  }
  return status.value.reachable ? 'success' : 'danger'
})

const backendText = computed(() => {
  if (!status.value?.enabled) {
    return '总闸关闭'
  }
  return status.value.reachable ? '正常' : '不可达'
})

const refreshText = computed(() => {
  if (loadError.value && !status.value) {
    return '状态获取失败'
  }
  if (!fetchedAt.value) {
    return '加载中…'
  }
  const sec = Math.max(0, Math.floor((now.value - fetchedAt.value) / 1000))
  if (sec < 60) {
    return `${sec} 秒前刷新`
  }
  return `${Math.floor(sec / 60)} 分钟前刷新`
})

/**
 * 拉取一次平台级 AIops 状态（`GET /aiops/status`）。
 *
 * 调用时机：onMounted 立即拉一次，之后由 setInterval 按 `pollIntervalMs`
 * （默认 30000ms = 30 秒；改法见上方 `intervalMs` 注释）周期调用。
 *
 * 失败处理：接口本身恒 200（总闸关 / 对端不可达都返回正常响应），
 * 走进 catch 多半是网络或会话问题——此时**保留上一次数据**，只把
 * `loadError` 置真，由 refreshText 兜底提示，不打断页面其它内容。
 */
async function load() {
  try {
    const res = await getAiopsStatus()
    status.value = res.data ?? null
    loadError.value = false
    fetchedAt.value = Date.now()
  } catch {
    // 接口本身恒 200，进这里多半是网络/会话问题；保留上次状态，只标记失败。
    loadError.value = true
  }
}

function openPlatform() {
  if (props.url) {
    window.open(props.url, '_blank', 'noopener,noreferrer')
  }
}

onMounted(() => {
  // 挂载立即拉一次，避免首屏空白等满一个轮询周期
  load()
  // pollIntervalMs=0 表示不轮询（只保留挂载时那一次）
  if (pollIntervalMs > 0) {
    pollTimer = setInterval(load, pollIntervalMs)
  }
  // 每秒 tick 一次，仅驱动「X 秒前刷新」文案，不触发接口请求
  tickTimer = setInterval(() => {
    now.value = Date.now()
  }, 1000)
})

onUnmounted(() => {
  if (pollTimer) {
    clearInterval(pollTimer)
  }
  if (tickTimer) {
    clearInterval(tickTimer)
  }
})
</script>

<style lang="scss" scoped>
.aiops-status-card {
  margin-bottom: 20px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
}

.status-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}

.status-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.status-text {
  margin-left: auto;
  font-size: 13px;
  font-weight: 600;
}

.status-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #909399;

  &.is-online {
    background: #67c23a;
    animation: aiops-pulse 1.6s infinite;
  }

  &.is-offline {
    background: #f56c6c;
  }

  &.is-disabled {
    background: #909399;
  }
}

@keyframes aiops-pulse {
  0% {
    box-shadow: 0 0 0 0 rgba(103, 194, 58, 0.5);
  }
  70% {
    box-shadow: 0 0 0 8px rgba(103, 194, 58, 0);
  }
  100% {
    box-shadow: 0 0 0 0 rgba(103, 194, 58, 0);
  }
}

.status-desc {
  margin-bottom: 12px;
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  word-break: break-all;
}

.status-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.refresh-hint {
  font-size: 12px;
  color: var(--text-secondary);
}

/* 三档状态文字配色（与状态点一致） */
.is-online {
  color: #67c23a;
}

.is-offline {
  color: #f56c6c;
}

.is-disabled {
  color: #909399;
}
</style>
