<!--
  StatusDisplay 状态显示组件 - Vue3 版本
  Vue2 → Vue3 变化：
  - props 使用 defineProps
  - computed 使用 computed() 函数
  - el-icon-xxx → Element Plus 图标组件
-->
<template>
  <div>
    <div :style="{ color: currentColor }">
      <el-icon v-if="currentIcon === 'warning'"><Warning /></el-icon>
      <el-icon v-else-if="currentIcon === 'time'"><Clock /></el-icon>
      <el-icon v-else><CircleCheck /></el-icon>
      {{ mainText }}
    </div>
    <div v-if="subText" style="font-size:10px; color:#909399; margin-top:2px;">
      {{ subText }}
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { Warning, Clock, CircleCheck } from '@element-plus/icons-vue'

defineOptions({ name: 'StatusDisplay' })

const props = defineProps({
  value: { type: Number, required: true },
  type: { type: String, required: true },
  mainText: { type: String, default: '' },
  subText: { type: String, default: '' },
  thresholds: {
    type: Object,
    default: () => ({ high: 85, medium: 70 })
  }
})

const currentColor = computed(() => {
  const { high, medium } = props.thresholds as { high: number; medium: number }
  if (props.value > high) return '#F56C6C'
  if (props.value > medium) return '#E6A23C'
  return '#67C23A'
})

const currentIcon = computed(() => {
  const { high, medium } = props.thresholds as { high: number; medium: number }
  if (props.value > high) return 'warning'
  if (props.value > medium) return 'time'
  return 'circle-check'
})
</script>