<!--
  Navbar 导航栏 - 通用后台管理模版
  包含：侧边栏折叠按钮、面包屑导航、主题切换、用户菜单
  可通过插槽扩展通知等自定义功能
-->
<template>
  <div class="navbar">
    <hamburger :is-active="sidebar.opened" class="hamburger-container" @toggle-click="toggleSideBar" />

    <breadcrumb class="breadcrumb-container" />

    <div class="right-menu">
      <!-- 演示只读模式开关：开启后全平台写操作按钮置灰禁用 -->
      <el-tooltip :content="demoStore.readonly ? '演示只读：已开启（点击解除锁定）' : '演示只读：已关闭（点击开启锁定）'" placement="bottom">
        <span class="theme-toggle" @click="demoStore.toggleReadonly()">
          <el-icon :size="18"><Unlock v-if="demoStore.readonly" /><Lock v-else /></el-icon>
        </span>
      </el-tooltip>

      <!-- 主题切换按钮 -->
      <el-tooltip :content="themeStore.current === 'dark' ? '切换浅色主题' : '切换深色主题'" placement="bottom">
        <span class="theme-toggle" @click="themeStore.toggleTheme()">
          <el-icon :size="18"><Sunny v-if="themeStore.current === 'dark'" /><Moon v-else /></el-icon>
        </span>
      </el-tooltip>

      <!-- 自定义扩展区域（如通知、全屏等） -->
      <slot name="right-extra" />

      <span class="username-text">{{ showUserName }}</span>
      <el-dropdown class="avatar-container" trigger="click">
        <div class="avatar-wrapper">
          <img :src="avatarUrl || defaultAvatar" class="user-avatar">
          <el-icon :size="12" class="caret-icon"><ArrowDown /></el-icon>
        </div>
        <template #dropdown>
          <el-dropdown-menu class="user-dropdown">
            <router-link to="/userCenter" v-if="hasUserCenter">
              <el-dropdown-item>个人中心</el-dropdown-item>
            </router-link>
            <el-dropdown-item divided @click="logout">
              <span style="display:block;">退出登录</span>
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAppStore } from '@/store/app.ts'
import { useUserStore } from '@/store/user.ts'
import { useThemeStore } from '@/store/theme.ts'
import { useDemoStore } from '@/store/demo.ts'
import { Sunny, Moon, ArrowDown, Lock, Unlock } from '@element-plus/icons-vue'
import jsCookie from 'js-cookie'
import Breadcrumb from '@/components/Breadcrumb/index.vue'
import Hamburger from '@/components/Hamburger/index.vue'

defineOptions({ name: 'Navbar' })

const router = useRouter()
const appStore = useAppStore()
const userStore = useUserStore()
const themeStore = useThemeStore()
const demoStore = useDemoStore()

const sidebar = computed(() => appStore.sidebar)
const avatarUrl = computed(() => {
  const src = userStore.avatar
  if (!src) return ''
  if (src.startsWith('data:')) return src
  return src + '?imageView2/1/w/80/h/80'
})

const defaultAvatar = computed(() => {
  const nameChar = (userStore.name || 'U').charAt(0).toUpperCase()
  return 'data:image/svg+xml,' + encodeURIComponent(
    '<svg xmlns="http://www.w3.org/2000/svg" width="80" height="80" viewBox="0 0 80 80">' +
    '<rect width="80" height="80" fill="#409EFF"/>' +
    '<text x="40" y="45" text-anchor="middle" dominant-baseline="middle" font-size="32" fill="white" font-family="sans-serif">' +
    nameChar + '</text></svg>'
  )
})

const showUserName = computed(() => jsCookie.get('username') || '')

/** 检测是否有个人中心路由，不存在则隐藏入口 */
const hasUserCenter = computed(() => {
  const routes = router.getRoutes()
  return routes.some(r => r.path === '/userCenter' || r.path === 'userCenter')
})

function toggleSideBar() {
  appStore.toggleSideBar()
}

async function logout() {
  await userStore.logout()
  router.push(`/login?redirect=${router.currentRoute.value.fullPath}`)
}
</script>

<style lang="scss" scoped>
.navbar {
  height: 50px;
  overflow: hidden;
  position: relative;
  background: var(--navbar-bg);
  box-shadow: var(--navbar-shadow);
  font-size: 25px;
  text-align: center;
  color: var(--text-primary);

  .hamburger-container {
    line-height: 46px;
    height: 100%;
    float: left;
    cursor: pointer;
    transition: background .3s;
    -webkit-tap-highlight-color: transparent;

    &:hover {
      background: rgba(0, 0, 0, .025)
    }
  }

  .breadcrumb-container {
    float: left;
  }

  .right-menu {
    float: right;
    height: 100%;
    line-height: 50px;
    display: flex;
    align-items: center;
    padding-right: 16px;

    &:focus {
      outline: none;
    }

    .theme-toggle {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      width: 36px;
      height: 36px;
      cursor: pointer;
      border-radius: 50%;
      margin-right: 4px;
      transition: all 0.3s;
      color: var(--text-regular);
      font-size: 18px;
      flex-shrink: 0;

      &:hover {
        background: rgba(0, 0, 0, 0.06);
        color: var(--menu-active-text);
      }
    }

    .username-text {
      font-size: 14px;
      color: var(--text-regular);
      margin-right: 8px;
    }

    .right-menu-item {
      display: inline-block;
      padding: 0 8px;
      height: 100%;
      font-size: 18px;
      color: #5a5e66;
      vertical-align: text-bottom;

      &.hover-effect {
        cursor: pointer;
        transition: background .3s;

        &:hover {
          background: rgba(0, 0, 0, .025)
        }
      }
    }

    .avatar-container {
      margin-right: 30px;
      line-height: normal;

      .avatar-wrapper {
        display: flex;
        align-items: center;
        cursor: pointer;

        .user-avatar {
          width: 36px;
          height: 36px;
          border-radius: 50%;
          object-fit: cover;
        }

        .caret-icon {
          cursor: pointer;
          font-size: 12px;
          margin-left: 4px;
          color: var(--text-secondary);
        }
      }
    }
  }
}
</style>
