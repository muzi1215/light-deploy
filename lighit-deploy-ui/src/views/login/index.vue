<template>
  <div class="login-wrapper">
    <div class="login-left">
      <div class="brand-content">
        <h1 class="brand-title">{{ appTitle }}</h1>
        <p class="brand-subtitle">{{ appSubtitle }}</p>
        <div class="brand-features">
          <div class="feature-item"><span class="feature-dot" /><span>开箱即用</span></div>
          <div class="feature-item"><span class="feature-dot" /><span>动态菜单</span></div>
          <div class="feature-item"><span class="feature-dot" /><span>主题切换</span></div>
          <div class="feature-item"><span class="feature-dot" /><span>权限管理</span></div>
        </div>
      </div>
    </div>
    <div class="login-right">
      <div class="form-card">
        <div class="form-header">
          <h2 class="form-title">欢迎回来</h2>
          <p class="form-desc">登录您的账户以继续</p>
        </div>
        <el-form ref="loginFormRef" :model="loginForm" :rules="loginRules" class="login-form" @keyup.enter="handleLogin">
          <el-form-item prop="username">
            <el-input v-model="loginForm.username" placeholder="用户名" size="large" :prefix-icon="User" clearable />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="loginForm.password" type="password" placeholder="密码" size="large" :prefix-icon="Lock" show-password />
          </el-form-item>
          <el-button :loading="loading" type="primary" size="large" class="login-btn" @click="handleLogin">
            {{ loading ? '登录中...' : '登 录' }}
          </el-button>
        </el-form>
        <div class="form-footer">
          <span class="footer-hint">默认管理员：admin / admin123（首次启动请尽快改密）</span>
        </div>
      </div>
      <p class="copyright">{{ copyrightText }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, watch } from 'vue'
import { type FormInstance } from 'element-plus'
import { useRouter, useRoute } from 'vue-router'
import { useUserStore } from '@/store/user.ts'
import { User, Lock } from '@element-plus/icons-vue'
import jsCookie from 'js-cookie'

defineOptions({ name: 'Login' })

/**
 * ============================================================
 * 品牌配置 — 修改此处即可自定义登录页品牌信息
 * ============================================================
 */
const appTitle = import.meta.env.VITE_APP_TITLE || 'Admin Template'
const appSubtitle = import.meta.env.VITE_APP_SUBTITLE || '通用后台管理模版'
const copyrightText = import.meta.env.VITE_APP_COPYRIGHT || `2026 ${appTitle}`

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const loginFormRef = ref<FormInstance>()
const loginForm = reactive({ username: '', password: '' })

const validateUsername = (_r: any, v: string, cb: Function) => {
  v.length < 2 ? cb(new Error('用户名长度不小于2位')) : cb()
}

const validatePassword = (_r: any, v: string, cb: Function) => {
  v.length < 6 ? cb(new Error('密码长度不小于6位')) : cb()
}

const loginRules = {
  username: [{ required: true, trigger: 'blur', validator: validateUsername }],
  password: [{ required: true, trigger: 'blur', validator: validatePassword }]
}

const loading = ref(false)
const redirect = ref<string>()

watch(() => route.query, (q) => { redirect.value = q?.redirect as string }, { immediate: true })

function handleLogin() {
  loginFormRef.value?.validate(async (valid: any) => {
    if (valid) {
      loading.value = true
      jsCookie.set('username', loginForm.username)
      try {
        await userStore.login(loginForm)
        await router.replace({ path: redirect.value || '/' })
      } catch {} finally {
        loading.value = false
      }
    }
  })
}
</script>

<style lang="scss" scoped>
.login-wrapper {
  display: flex; min-height: 100vh; width: 100%;
  background: linear-gradient(135deg, #f0f5ff, #e8f0fe, #f8fafc, #eef4ff);
  overflow: hidden;
  font-family: -apple-system, BlinkMacSystemFont, 'PingFang SC', 'Microsoft YaHei', sans-serif;
}
.login-left {
  flex: 0 0 46%; display: flex; align-items: center; justify-content: center;
  background: linear-gradient(160deg, #1e3a5f, #2563eb, #3b82f6, #60a5fa);
  clip-path: ellipse(92% 100% at 0% 50%); padding: 60px 40px;
}
.brand-content { text-align: center; color: #fff; }
.brand-title { font-size: 42px; font-weight: 700; letter-spacing: 4px; margin: 0 0 8px; }
.brand-subtitle { font-size: 16px; font-weight: 300; opacity: 0.85; margin: 0 0 40px; letter-spacing: 6px; }
.brand-features { display: flex; flex-direction: column; gap: 16px; align-items: flex-start; width: fit-content; margin: 0 auto; }
.feature-item { display: flex; align-items: center; gap: 12px; font-size: 14px; opacity: 0.8; }
.feature-dot { width: 6px; height: 6px; border-radius: 50%; background: #93c5fd; flex-shrink: 0; }
.login-right { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; padding: 40px; }
.form-card { width: 420px; max-width: 90vw; background: rgba(255,255,255,0.85); backdrop-filter: blur(20px); border-radius: 20px; padding: 48px 40px; box-shadow: 0 4px 24px rgba(59,130,246,0.08); }
.form-header { text-align: center; margin-bottom: 36px; }
.form-title { font-size: 26px; font-weight: 700; color: #1e3a5f; margin: 0 0 8px; }
.form-desc { font-size: 14px; color: #94a3b8; margin: 0; }
.login-form :deep(.el-input__wrapper) { border-radius: 12px; background: #f8fafc !important; border: 1.5px solid #e2e8f0; box-shadow: none !important; transition: all 0.3s; }
.login-form :deep(.el-input__inner) { color: #1e3a5f !important; background: transparent !important; }
.login-form :deep(.el-input__wrapper.is-focus) { border-color: #3b82f6; background: #fff !important; box-shadow: 0 0 0 3px rgba(59,130,246,0.08); }
.login-btn { width: 100%; height: 48px; border-radius: 12px; font-size: 16px; font-weight: 600; letter-spacing: 4px; margin-top: 8px; background: linear-gradient(135deg, #3b82f6, #2563eb); box-shadow: 0 4px 14px rgba(59,130,246,0.3); border: none; }
.login-btn:hover { transform: translateY(-1px); box-shadow: 0 6px 20px rgba(59,130,246,0.4); }
.form-footer { text-align: center; margin-top: 28px; }
.footer-hint { font-size: 13px; color: #94a3b8; }
.copyright { position: absolute; bottom: 24px; font-size: 12px; color: #cbd5e1; }
@media (max-width: 768px) { .login-left { display: none; } .login-right { padding: 20px; } }
</style>
