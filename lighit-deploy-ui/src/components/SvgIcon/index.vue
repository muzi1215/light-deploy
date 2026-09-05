<!--
  SvgIcon SVG 图标组件 - Vue3 版本
  Vue2 → Vue3 变化：
  - v-on="$listeners" → Vue3 中 $listeners 已移除，自动合并到 $attrs
  - props 使用 defineProps
  - computed 使用 computed()
  - xlink:href → href (SVG 2 标准)
-->
<template>
  <div v-if="isExternalIcon" :style="styleExternalIcon" class="svg-external-icon svg-icon" />
  <svg v-else :class="svgClass" aria-hidden="true">
    <use :href="iconName" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { isExternal } from '@/utils/validate.ts'

defineOptions({ name: 'SvgIcon' })

const props = defineProps({
  iconClass: { type: String, required: true },
  className: { type: String, default: '' }
})

const isExternalIcon = computed(() => isExternal(props.iconClass))

const iconName = computed(() => `#icon-${props.iconClass}`)

const svgClass = computed(() => {
  if (props.className) {
    return 'svg-icon ' + props.className
  }
  return 'svg-icon'
})

const styleExternalIcon = computed(() => ({
  mask: `url(${props.iconClass}) no-repeat 50% 50%`,
  '-webkit-mask': `url(${props.iconClass}) no-repeat 50% 50%`
}))
</script>

<style scoped>
.svg-icon {
  width: 1em;
  height: 1em;
  vertical-align: -0.15em;
  fill: currentColor;
  overflow: hidden;
}

.svg-external-icon {
  background-color: currentColor;
  mask-size: cover !important;
  display: inline-block;
}
</style>