<!--
  ScrollPane 滚动面板 - Vue3 版本
  Vue2 → Vue3 变化：
  - this.$refs → ref()
  - this.$parent → 不再推荐，改用 emit 或 props
  - mounted → onMounted
  - beforeDestroy → onBeforeUnmount
  - @wheel.native.prevent → @wheel.prevent
-->
<template>
  <el-scrollbar ref="scrollContainerRef" :vertical="false" class="scroll-container" @wheel.prevent="handleScroll">
    <slot />
  </el-scrollbar>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, nextTick, type ComponentPublicInstance } from 'vue'

defineOptions({ name: 'ScrollPane' })

const emit = defineEmits(['scroll'])

const tagAndTagSpacing = 4
const left = ref(0)
const scrollContainerRef = ref<any>()

// 计算属性
function getScrollWrapper(): HTMLElement {
  return scrollContainerRef.value?.$refs?.wrap as HTMLElement
}

onMounted(() => {
  const wrapper = getScrollWrapper()
  if (wrapper) {
    wrapper.addEventListener('scroll', emitScroll, true)
  }
})

onBeforeUnmount(() => {
  const wrapper = getScrollWrapper()
  if (wrapper) {
    wrapper.removeEventListener('scroll', emitScroll)
  }
})

function handleScroll(e: WheelEvent) {
  const eventDelta = (e as any).wheelDelta || -e.deltaY * 40
  const $scrollWrapper = getScrollWrapper()
  if ($scrollWrapper) {
    $scrollWrapper.scrollLeft = $scrollWrapper.scrollLeft + eventDelta / 4
  }
}

function emitScroll() {
  emit('scroll')
}

function moveToTarget(currentTag: any) {
  const $container = scrollContainerRef.value?.$el as HTMLElement
  if (!$container) return

  const $containerWidth = $container.offsetWidth
  const $scrollWrapper = getScrollWrapper()
  if (!$scrollWrapper) return

  // 在当前组件实例中查找 tag refs
  const tagList = (currentTag?.__vueParentComponent?.refs?.tag) || []

  let firstTag: any = null
  let lastTag: any = null

  if (tagList.length > 0) {
    firstTag = tagList[0]
    lastTag = tagList[tagList.length - 1]
  }

  if (firstTag === currentTag) {
    $scrollWrapper.scrollLeft = 0
  } else if (lastTag === currentTag) {
    $scrollWrapper.scrollLeft = $scrollWrapper.scrollWidth - $containerWidth
  } else {
    const currentIndex = tagList.findIndex((item: any) => item === currentTag)
    if (currentIndex > 0 && currentIndex < tagList.length - 1) {
      const prevTag = tagList[currentIndex - 1]
      const nextTag = tagList[currentIndex + 1]

      const afterNextTagOffsetLeft = nextTag.$el.offsetLeft + nextTag.$el.offsetWidth + tagAndTagSpacing
      const beforePrevTagOffsetLeft = prevTag.$el.offsetLeft - tagAndTagSpacing

      if (afterNextTagOffsetLeft > $scrollWrapper.scrollLeft + $containerWidth) {
        $scrollWrapper.scrollLeft = afterNextTagOffsetLeft - $containerWidth
      } else if (beforePrevTagOffsetLeft < $scrollWrapper.scrollLeft) {
        $scrollWrapper.scrollLeft = beforePrevTagOffsetLeft
      }
    }
  }
}

defineExpose({ moveToTarget, left })
</script>

<style lang="scss" scoped>
.scroll-container {
  white-space: nowrap;
  position: relative;
  overflow: hidden;
  width: 100%;
  :deep(.el-scrollbar__bar) {
    bottom: 0px;
  }
  :deep(.el-scrollbar__wrap) {
    height: 49px;
  }
}
</style>