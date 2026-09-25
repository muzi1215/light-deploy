<!--
  AppMain 主内容区 - Vue3 版本
  Vue Router 4 中 <router-view> 不能直接放在 <transition> 或 <keep-alive> 内部
  需要使用 v-slot 插槽属性方式

  缓存机制说明：
  - keep-alive 的 include 匹配组件 name 选项，而非路由名称
  - 页面组件使用 <script setup> 但未设置 defineOptions({ name })
  - 通过 wrap 函数动态创建带路由名称的包装组件，使 keep-alive 能正确匹配
-->
<template>
  <section class="app-main">
    <router-view v-slot="{ Component, route: r }">
      <transition name="fade-transform" mode="out-in">
        <keep-alive :include="cachedViews">
          <component :is="wrap(Component, (r.name || r.path) as string)" :key="r.name || r.path" />
        </keep-alive>
      </transition>
    </router-view>
  </section>
</template>


<script setup lang="ts">
import { computed, h, defineComponent } from 'vue'
import { useTagsViewStore } from '@/store/tagsView.ts'

defineOptions({ name: 'AppMain' })

const tagsViewStore = useTagsViewStore()

const cachedViews = computed(() => tagsViewStore.cachedViews)

/**
 * 创建带名称的组件包装器
 * keep-alive 的 include 匹配的是组件 name 选项
 * 由于页面组件使用 <script setup> 未设置 defineOptions({ name })
 * 通过此包装器将路由名称注入为组件 name，使 keep-alive 能正确缓存
 */
function wrap(Component: any, name: string) {
  return defineComponent({
    name,
    render() {
      return h(Component)
    }
  })
}
</script>

<style lang="scss" scoped>
.app-main {
  /* 页面内容区自身滚动：顶栏与标签栏固定，滚动只发生在本区域内部（内部滚动条） */
  height: calc(100vh - 50px);
  width: 100%;
  position: relative;
  overflow-y: auto;
  overflow-x: hidden;
}

.fixed-header+.app-main {
  /* 开启 fixedHeader 时导航 fixed 脱离文档流，内容区从视口顶部开始：
     高度回到 100vh，用 padding 让出头部，滚动区域 = 100vh - 头部高度 */
  height: 100vh;
  padding-top: 50px;
}

.hasTagsView {
  .app-main {
    /* 84 = navbar + tags-view = 50 + 34 */
    height: calc(100vh - 84px);
  }

  .fixed-header+.app-main {
    height: 100vh;
    padding-top: 84px;
  }
}
</style>

<style lang="scss">
// fix css style bug in open el-dialog
.el-popup-parent--hidden {
  .fixed-header {
    padding-right: 15px;
  }
}
</style>