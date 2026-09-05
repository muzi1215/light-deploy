<!--
  Breadcrumb 面包屑 - Vue3 版本
  Vue2 → Vue3 变化：
  - data() → ref()
  - watch → watch()
  - created → 直接在 setup 中执行
  - methods → 普通函数
  - this.$route → useRoute()
  - this.$router → useRouter()
  - path-to-regexp 新版 API 变化
-->
<template>
  <el-breadcrumb class="app-breadcrumb" separator="/">
    <transition-group name="breadcrumb">
      <el-breadcrumb-item v-for="(item, index) in levelList" :key="item.path">
        <span v-if="item.redirect === 'noRedirect' || index === levelList.length - 1" class="no-redirect">
          {{ item.meta.title }}
        </span>
        <a v-else @click.prevent="handleLink(item)">{{ item.meta.title }}</a>
      </el-breadcrumb-item>
    </transition-group>
  </el-breadcrumb>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { compile } from 'path-to-regexp'

defineOptions({ name: 'Breadcrumb' })

const route = useRoute()
const router = useRouter()

const levelList = ref<any[]>([])

// 获取面包屑列表
function getBreadcrumb() {
  let matched = route.matched.filter(item => item.meta && item.meta.title)

  const first = matched[0]
  if (!isDashboard(first)) {
    matched = [{ path: '/dashboard', meta: { title: 'Dashboard' } } as any, ...matched]
  }

  levelList.value = matched.filter(
    item => item.meta && item.meta.title && item.meta.breadcrumb !== false
  )
}

function isDashboard(route: any) {
  const name = route && route.name
  if (!name) return false
  return name.trim().toLowerCase() === 'Dashboard'.toLowerCase()
}

function pathCompile(path: string) {
  const { params } = route
  const toPath = compile(path)
  return toPath(params)
}

function handleLink(item: any) {
  const { redirect, path } = item
  if (redirect) {
    router.push(redirect)
    return
  }
  router.push(pathCompile(path))
}

// Vue3 中 watch 和 created 逻辑
watch(
  () => route.path,
  () => getBreadcrumb(),
  { immediate: true }
)
</script>

<style lang="scss" scoped>
.app-breadcrumb.el-breadcrumb {
  display: inline-block;
  font-size: 14px;
  line-height: 50px;
  margin-left: 8px;

  .no-redirect {
    color: var(--text-secondary);
    cursor: text;
  }
}
</style>