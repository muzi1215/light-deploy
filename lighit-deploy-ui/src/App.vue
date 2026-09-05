<!--
  App.vue - 根组件 (Vue3 版本)
  Vue2 → Vue3 变化：
  - 模板语法基本兼容，无需改动
  - <script setup> 是 Vue3 的 Composition API 语法糖
  - 不再需要显式 export default
-->
<template>
  <div id="app">
    <router-view />
  </div>
</template>


<script setup lang="ts">
// Vue3 Composition API：使用 <script setup> 语法糖，无需 export default
// 组件名从 name 选项改为通过文件名自动推断，或使用 defineOptions
defineOptions({
  name: 'App'
})
</script>