<!--
  Link 链接组件 - Vue3 版本
  Vue2 → Vue3 变化：
  - component :is 动态组件用法不变
  - props 和 computed 使用 Composition API
-->
<template>
  <component :is="type" v-bind="linkProps(to)">
    <slot />
  </component>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { isExternal } from '@/utils/validate.ts'

defineOptions({ name: 'AppLink' })

const props = defineProps({
  to: { type: String, required: true }
})

const isExternalLink = computed(() => isExternal(props.to))

const type = computed(() => isExternalLink.value ? 'a' : 'router-link')

function linkProps(to: string): Record<string, string> {
  if (isExternalLink.value) {
    return { href: to, target: '_blank', rel: 'noopener' }
  }
  return { to }
}
</script>