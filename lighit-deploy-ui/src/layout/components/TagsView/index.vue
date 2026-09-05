<!--
  TagsView 标签页视图 - Vue3 版本
  修复：关闭标签、右键菜单、激活状态判断等问题
  新增：左右箭头滚动按钮
-->
<template>
  <div id="tags-view-container" class="tags-view-container">
    <!-- 左箭头 -->
    <span v-show="showArrow" class="arrow-btn arrow-left" @click="scrollLeft">
      <el-icon><ArrowLeft /></el-icon>
    </span>
    <!-- 标签滚动区域 -->
    <div ref="scrollContainerRef" class="tags-scroll-wrapper" @wheel.prevent="handleWheel">
      <div ref="scrollContentRef" class="tags-scroll-content">
        <router-link
          v-for="tag in visitedViews"
          :key="tag.path"
          :ref="(el: any) => setTagRef(el, tag)"
          :class="isActive(tag) ? 'active' : ''"
          :to="{ path: tag.path, query: tag.query }"
          class="tags-view-item"
          @click.middle="!isAffix(tag) && closeSelectedTag(tag)"
          @contextmenu.prevent="openMenu(tag, $event)"
        >
          {{ tag.title }}
          <el-icon v-if="!isAffix(tag)" class="tag-close-icon" @click.prevent.stop="closeSelectedTag(tag)">
            <Close />
          </el-icon>
        </router-link>
      </div>
    </div>
    <!-- 右箭头 -->
    <span v-show="showArrow" class="arrow-btn arrow-right" @click="scrollRight">
      <el-icon><ArrowRight /></el-icon>
    </span>
    <ul v-show="visible" :style="{ left: left + 'px', top: top + 'px' }" class="contextmenu">
      <li @click="refreshSelectedTag(selectedTag)">刷新</li>
      <li v-if="!isAffix(selectedTag)" @click="closeSelectedTag(selectedTag)">关闭当前标签</li>
      <li @click="closeOthersTags">关闭其他标签</li>
      <li @click="closeAllTags">关闭所有标签</li>
    </ul>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useTagsViewStore } from '@/store/tagsView.ts'
import { Close, ArrowLeft, ArrowRight } from '@element-plus/icons-vue'
import ScrollPane from './ScrollPane.vue'

defineOptions({ name: 'TagsView' })

const route = useRoute()
const router = useRouter()
const tagsViewStore = useTagsViewStore()

const visible = ref(false)
const top = ref(0)
const left = ref(0)
const selectedTag = ref<any>({})
const affixTags = ref<any[]>([])
const scrollContainerRef = ref<HTMLElement>()
const scrollContentRef = ref<HTMLElement>()
const showArrow = ref(false)
const scrollLeftVal = ref(0)

const visitedViews = computed(() => tagsViewStore.visitedViews)

// 获取所有路由（静态 + 动态）
const allRoutes = computed(() => {
  return [...router.options.routes, ...((window as any).antRouter || [])]
})

// 监听路由变化
watch(() => route.path, () => {
  addTags()
  moveToCurrentTag()
})

// 监听标签列表变化，更新箭头显示
watch(visitedViews, () => {
  nextTick(() => checkArrow())
}, { deep: true })

// 右键菜单
watch(visible, (val) => {
  if (val) {
    document.body.addEventListener('click', closeMenu)
  } else {
    document.body.removeEventListener('click', closeMenu)
  }
})

onMounted(() => {
  initTags()
  addTags()
  nextTick(() => checkArrow())
  window.addEventListener('resize', checkArrow)
})

onBeforeUnmount(() => {
  document.body.removeEventListener('click', closeMenu)
  window.removeEventListener('resize', checkArrow)
})

/** 检测是否需要显示箭头 */
function checkArrow() {
  const container = scrollContainerRef.value
  const content = scrollContentRef.value
  if (container && content) {
    showArrow.value = content.scrollWidth > container.clientWidth
  }
}

/** 向左滚动 */
function scrollLeft() {
  const container = scrollContainerRef.value
  if (container) {
    const scrollAmount = 200
    container.scrollLeft -= scrollAmount
  }
}

/** 向右滚动 */
function scrollRight() {
  const container = scrollContainerRef.value
  if (container) {
    const scrollAmount = 200
    container.scrollLeft += scrollAmount
  }
}

/** 鼠标滚轮滚动 */
function handleWheel(e: WheelEvent) {
  const container = scrollContainerRef.value
  if (container) {
    e.preventDefault()
    container.scrollLeft += e.deltaY
  }
}

/** 滚动到当前标签 */
function moveToCurrentTag() {
  nextTick(() => {
    checkArrow()
  })
}

/** 设置标签引用 */
function setTagRef(el: any, tag: any) {
  // 用于滚动到指定标签
}

function isActive(tag: any): boolean {
  return tag.path === route.path
}

function isAffix(tag: any): boolean {
  return tag.meta?.affix
}

function filterAffixTags(routes: any[], basePath = '/'): any[] {
  let tags: any[] = []
  routes.forEach(route => {
    if (route.meta?.affix) {
      const tagPath = basePath === '/' ? `/${route.path}` : `${basePath}/${route.path}`
      tags.push({
        fullPath: tagPath,
        path: tagPath,
        name: route.name,
        meta: { ...route.meta }
      })
    }
    if (route.children) {
      const childTags = filterAffixTags(route.children, route.path)
      if (childTags.length) {
        tags = [...tags, ...childTags]
      }
    }
  })
  return tags
}

function initTags() {
  affixTags.value = filterAffixTags(allRoutes.value)
  for (const tag of affixTags.value) {
    if (tag.name) {
      tagsViewStore.addVisitedView(tag)
    }
  }
}

function addTags() {
  const { name, path, meta, query, fullPath } = route
  if (name) {
    tagsViewStore.addView({
      path,
      name: name as string,
      meta,
      query,
      fullPath,
      title: (meta.title as string) || (name as string)
    })
  }
}

function refreshSelectedTag(view: any) {
  tagsViewStore.delCachedView(view)
  nextTick(() => {
    router.replace({ path: '/redirect' + view.fullPath } as any)
  })
}

function closeSelectedTag(view: any) {
  tagsViewStore.delView(view)
  if (isActive(view)) {
    toLastView(tagsViewStore.visitedViews, view)
  }
}

function closeOthersTags() {
  router.push(selectedTag.value.path)
  tagsViewStore.delOthersViews(selectedTag.value)
}

function closeAllTags() {
  tagsViewStore.delAllViews()
  if (affixTags.value.some((t: any) => t.path === selectedTag.value.path)) {
    return
  }
  toLastView(tagsViewStore.visitedViews, selectedTag.value)
}

function toLastView(visitedViews: any[], view: any) {
  const latestView = visitedViews.slice(-1)[0]
  if (latestView) {
    router.push(latestView.fullPath || latestView.path)
  } else {
    router.push('/')
  }
}

function openMenu(tag: any, e: MouseEvent) {
  const menuMinWidth = 105
  const container = document.getElementById('tags-view-container')
  if (!container) return

  const offsetLeft = container.getBoundingClientRect().left
  const offsetWidth = container.offsetWidth
  const maxLeft = offsetWidth - menuMinWidth
  const clickLeft = e.clientX - offsetLeft + 15

  left.value = clickLeft > maxLeft ? maxLeft : clickLeft
  top.value = e.clientY - 50
  visible.value = true
  selectedTag.value = tag
}

function closeMenu() {
  visible.value = false
}

function handleScroll() {
  closeMenu()
}
</script>

<style lang="scss" scoped>
.tags-view-container {
  height: 34px;
  width: 100%;
  background: var(--tags-view-bg);
  border-bottom: 1px solid var(--border-color);
  box-shadow: 0 1px 3px 0 rgba(0, 0, 0, .08);
  display: flex;
  align-items: center;
  position: relative;
}

/* 箭头按钮 */
.arrow-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 34px;
  cursor: pointer;
  flex-shrink: 0;
  color: var(--text-secondary);
  transition: all 0.2s;
  z-index: 10;
  background: var(--tags-view-bg);

  &:hover {
    color: var(--menu-active-text);
    background: var(--menu-hover);
  }

  .el-icon {
    font-size: 14px;
  }
}

.arrow-left {
  border-right: 1px solid var(--border-color);
}

.arrow-right {
  border-left: 1px solid var(--border-color);
}

/* 滚动区域 */
.tags-scroll-wrapper {
  flex: 1;
  overflow: hidden;
  white-space: nowrap;
  scroll-behavior: smooth;

  &::-webkit-scrollbar {
    display: none;
  }
}

.tags-scroll-content {
  display: inline-flex;
  align-items: center;
  height: 34px;
}

.tags-view-item {
  display: inline-flex;
  align-items: center;
  position: relative;
  cursor: pointer;
  height: 26px;
  line-height: 26px;
  border: 1px solid var(--border-color);
  color: var(--text-regular);
  background: var(--card-bg);
  padding: 0 8px;
  font-size: 12px;
  margin-left: 5px;
  text-decoration: none;
  flex-shrink: 0;
  border-radius: 3px;
  transition: all 0.2s;

  &:first-of-type {
    margin-left: 8px;
  }

  &.active {
    background-color: #42b983;
    color: #fff;
    border-color: #42b983;

    &::before {
      content: '';
      background: #fff;
      display: inline-block;
      width: 8px;
      height: 8px;
      border-radius: 50%;
      position: relative;
      margin-right: 2px;
    }
  }

  .tag-close-icon {
    width: 16px;
    height: 16px;
    vertical-align: middle;
    border-radius: 50%;
    text-align: center;
    cursor: pointer;
    transition: all .3s cubic-bezier(.645, .045, .355, 1);
    margin-left: 4px;
    font-size: 12px;

    &:hover {
      background-color: #b4bccc;
      color: #fff;
    }
  }
}

.contextmenu {
  margin: 0;
  background: var(--card-bg);
  z-index: 3000;
  position: absolute;
  list-style-type: none;
  padding: 5px 0;
  border-radius: 4px;
  font-size: 12px;
  font-weight: 400;
  color: var(--text-primary);
  box-shadow: 2px 2px 3px 0 rgba(0, 0, 0, .3);
  border: 1px solid var(--border-color);

  li {
    margin: 0;
    padding: 7px 16px;
    cursor: pointer;

    &:hover {
      background: var(--menu-hover);
    }
  }
}
</style>