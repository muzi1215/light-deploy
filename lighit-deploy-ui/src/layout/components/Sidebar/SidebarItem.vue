<!--
  SidebarItem 侧边栏菜单项 - Vue3 版本
  Vue2 → Vue3 变化：
  - mixins: [FixiOSBug] → 直接导入 FixiOSBug 函数
  - this.onlyOneChild = null in data() → 在函数内定义变量
  - methods 改为普通函数
  - el-submenu → el-sub-menu (Element Plus)
  - slot="title" → #title
-->
<template>
  <div v-if="!item.hidden">
    <template v-if="hasOneShowingChild(item.children, item) && (!onlyOneChild.children || onlyOneChild.noShowingChildren) && !item.alwaysShow">
      <app-link v-if="onlyOneChild.meta" :to="resolvePath(onlyOneChild.path)">
        <el-menu-item :index="resolvePath(onlyOneChild.path)" :class="{ 'submenu-title-noDropdown': !isNest }">
          <item :icon="onlyOneChild.meta.icon || (item.meta && item.meta.icon)" :title="onlyOneChild.meta.title" />
        </el-menu-item>
      </app-link>
    </template>

    <el-sub-menu v-else ref="subMenuRef" :index="resolvePath(item.path)" popper-append-to-body>
      <template #title>
        <item v-if="item.meta" :icon="item.meta && item.meta.icon" :title="item.meta.title" />
      </template>
      <sidebar-item
        v-for="child in item.children"
        :key="child.path"
        :is-nest="true"
        :item="child"
        :base-path="resolvePath(child.path)"
        class="nest-menu"
      />
    </el-sub-menu>
  </div>
</template>

<script setup lang="ts">
import path from 'path-browserify'
import { isExternal } from '@/utils/validate.ts'
import Item from './Item.vue'
import AppLink from './Link.vue'

defineOptions({ name: 'SidebarItem' })

// Props - Vue3 中使用 defineProps
const props = defineProps({
  item: { type: Object, required: true },
  isNest: { type: Boolean, default: false },
  basePath: { type: String, default: '' }
})

// 用于存储 onlyOneChild
let onlyOneChild: any = null

/** 判断是否只有一个可见子路由 */
function hasOneShowingChild(children: any[] = [], parent: any): boolean {
  const showingChildren = children.filter(item => {
    if (item.hidden) {
      return false
    } else {
      onlyOneChild = item
      return true
    }
  })

  if (showingChildren.length === 1) {
    return true
  }

  if (showingChildren.length === 0) {
    onlyOneChild = { ...parent, path: '', noShowingChildren: true }
    return true
  }

  return false
}

/** 解析路径 */
function resolvePath(routePath: string): string {
  if (isExternal(routePath)) {
    return routePath
  }
  if (isExternal(props.basePath)) {
    return props.basePath
  }
  return path.resolve(props.basePath, routePath)
}
</script>