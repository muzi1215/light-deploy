<template>
  <div class="home-container">
    <!-- 平台简介 -->
    <el-card class="hero-card" shadow="never">
      <div class="hero">
        <div class="hero-main">
          <h1 class="hero-title">Light Deploy</h1>
          <p class="hero-subtitle">轻量化部署发布平台</p>
          <p class="hero-desc">
            面向中小企业的轻量化部署发布平台：通过 SSH/SFTP 纳管多台目标服务器，统一支持 JAR、Docker、静态产物等多种运行时，
            提供构建、发布、回滚与实时监控，并与 AIops 智能运维平台联动，让每一次发布更稳、更省心。
          </p>
          <div class="hero-actions">
            <el-button type="primary" :icon="Promotion" @click="goProject">开始管理项目</el-button>
            <el-button :icon="View" @click="goDeployment">查看发布记录</el-button>
          </div>
        </div>
        <div class="hero-art">
          <div class="runtime-badges">
            <el-tag type="primary" effect="dark" size="large" class="runtime-badge">JAR</el-tag>
            <el-tag type="success" effect="dark" size="large" class="runtime-badge">Docker</el-tag>
            <el-tag type="warning" effect="dark" size="large" class="runtime-badge">Static</el-tag>
          </div>
        </div>
      </div>
    </el-card>

    <!-- 核心能力 -->
    <h2 class="section-title">核心能力</h2>
    <el-row :gutter="20" class="feature-row">
      <el-col v-for="f in features" :key="f.title" :xs="24" :sm="12" :md="8">
        <div class="feature-card">
          <el-icon :size="36" :style="{ color: f.color }"><component :is="f.icon" /></el-icon>
          <h3 class="feature-title">{{ f.title }}</h3>
          <p class="feature-desc">{{ f.desc }}</p>
        </div>
      </el-col>
    </el-row>

    <!-- 快捷入口 -->
    <h2 class="section-title">快捷入口</h2>
    <el-row :gutter="20" class="quick-row">
      <el-col v-for="q in quickEntries" :key="q.path" :xs="12" :sm="8" :md="6">
        <div class="quick-card" @click="goPath(q.path)">
          <el-icon :size="28" :style="{ color: q.color }"><component :is="q.icon" /></el-icon>
          <span class="quick-label">{{ q.label }}</span>
        </div>
      </el-col>
    </el-row>

    <!-- AIops 实时状态 -->
    <h2 class="section-title">AIops 实时状态</h2>
    <el-row :gutter="20">
      <el-col :span="24">
        <AiopsStatusCard :url="aiopsUrl" />
      </el-col>
    </el-row>

    <!-- 开源与生态 -->
    <h2 class="section-title">开源与生态</h2>
    <el-row :gutter="20" class="eco-row">
      <el-col :xs="24" :md="12">
        <el-card class="eco-card" shadow="hover">
          <div class="eco-head">
            <svg class="eco-icon" viewBox="0 0 24 24" width="28" height="28" aria-hidden="true">
              <path fill="currentColor" d="M12 .5C5.73.5.5 5.73.5 12c0 5.08 3.29 9.39 7.86 10.91.58.11.79-.25.79-.56 0-.28-.01-1.02-.02-2-3.2.7-3.88-1.54-3.88-1.54-.52-1.33-1.28-1.69-1.28-1.69-1.05-.72.08-.7.08-.7 1.16.08 1.77 1.19 1.77 1.19 1.03 1.77 2.7 1.26 3.36.96.1-.75.4-1.26.73-1.55-2.56-.29-5.25-1.28-5.25-5.7 0-1.26.45-2.29 1.19-3.1-.12-.29-.52-1.46.11-3.05 0 0 .97-.31 3.18 1.18a11.1 11.1 0 0 1 5.8 0c2.2-1.49 3.17-1.18 3.17-1.18.63 1.59.23 2.76.11 3.05.74.81 1.19 1.84 1.19 3.1 0 4.43-2.69 5.41-5.26 5.69.41.36.78 1.07.78 2.16 0 1.56-.01 2.82-.01 3.2 0 .31.21.68.8.56A11.51 11.51 0 0 0 23.5 12C23.5 5.73 18.27.5 12 .5z" />
            </svg>
            <span class="eco-name">开源代码仓库</span>
          </div>
          <p class="eco-desc">Light Deploy 已在 GitHub 开源，欢迎 Star、提交 Issue 与 PR。</p>
          <a class="eco-link" :href="githubUrl" target="_blank" rel="noopener noreferrer">
            <el-icon><Link /></el-icon>
            <span class="eco-url">{{ githubUrl }}</span>
          </a>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="12">
        <el-card class="eco-card" shadow="hover">
          <div class="eco-head">
            <el-icon class="eco-icon" :size="28" color="#409EFF"><Connection /></el-icon>
            <span class="eco-name">联动 AIops 智能运维平台</span>
          </div>
          <p class="eco-desc">
            发布前后的告警静默、指标日志分析与风险建议由 AIops 提供，与 Light Deploy 深度联动。
          </p>
          <a class="eco-link" :href="aiopsUrl" target="_blank" rel="noopener noreferrer">
            <el-icon><Link /></el-icon>
            <span class="eco-url">{{ aiopsUrl }}</span>
          </a>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup lang="ts">
/**
 * 首页（概览）。
 *
 * 替换原模板示例仪表盘，贴合 Light Deploy 真实业务：
 * - 平台简介与三种运行时（JAR / Docker / Static）；
 * - 核心能力卡片；
 * - 快捷入口跳转到真实业务页面（路由与 router/index.ts 的 constantRoutes 对齐）；
 * - AIops 实时状态：轮询 /aiops/status，展示联动总闸与对端连通状态；
 * - 开源与生态：GitHub 仓库与联动的 AIops 平台外链（新窗口打开）。
 */
import { useRouter } from 'vue-router'
import {
  Promotion, View, Cpu, Connection, Monitor, Share,
  Files, Loading, Key, Box, List, Document, Link
} from '@element-plus/icons-vue'
import AiopsStatusCard from '@/components/AiopsStatusCard.vue'

defineOptions({ name: 'Dashboard' })

const router = useRouter()

/** 开源与联动平台外链 */
const githubUrl = 'https://github.com/muzi1215/light-deploy.git'
const aiopsUrl = 'https://lxrelife.xyz/aiops/'

/** 核心能力卡片 */
const features = [
  { title: '多运行时支持', desc: '统一管控 JAR（systemd）、Docker（镜像 / Compose）与静态产物（Nginx）三种运行时。', icon: Cpu, color: '#409EFF' },
  { title: 'SSH 纳管与加密凭据', desc: '通过 SSH/SFTP 纳管目标服务器，密码 / 私钥 AES-GCM 加密落库，杜绝明文。', icon: Connection, color: '#67C23A' },
  { title: '构建与发布流水线', desc: '克隆、构建、推送、发布、回滚一站式，支持多种构建模式与多服务器并发发布。', icon: Promotion, color: '#E6A23C' },
  { title: '实时监控与回滚', desc: '实时日志、任务进度与发布记录一屏掌握，异常可一键回滚到历史版本。', icon: Monitor, color: '#F56C6C' },
  { title: 'AIops 智能运维联动', desc: '发布窗口告警静默、指标日志分析与风险建议，由联动的 AIops 平台提供。', icon: Share, color: '#9254DE' },
  { title: '轻量可落地', desc: 'Spring Boot 单体 + Vue3 前端，轻量化设计，中小企业开箱即用。', icon: Files, color: '#13C2C2' }
]

/** 快捷入口：path 与 router/index.ts 的 constantRoutes 对齐 */
const quickEntries = [
  { label: '服务器管理', path: '/server/list', icon: Cpu, color: '#409EFF' },
  { label: '项目管理', path: '/project/list', icon: Files, color: '#67C23A' },
  { label: '构建管理', path: '/build/list', icon: Loading, color: '#E6A23C' },
  { label: '发布记录', path: '/deployment/list', icon: Promotion, color: '#F56C6C' },
  { label: '凭据管理', path: '/system/credential', icon: Key, color: '#9254DE' },
  { label: '镜像仓库', path: '/system/registry', icon: Box, color: '#13C2C2' },
  { label: '任务管理', path: '/task/list', icon: List, color: '#2F54EB' },
  { label: '操作日志', path: '/system/oper-log', icon: Document, color: '#595959' }
]

function goPath(path: string) {
  router.push(path)
}

function goProject() {
  router.push('/project/list')
}

function goDeployment() {
  router.push('/deployment/list')
}
</script>

<style lang="scss" scoped>
.home-container {
  padding: 16px;
  background: var(--page-bg);
  min-height: calc(100vh - 84px);
}

.section-title {
  margin: 24px 0 16px;
  font-size: 17px;
  font-weight: 600;
  color: var(--text-primary);
  padding-left: 10px;
  border-left: 4px solid var(--el-color-primary, #409EFF);
}

/* 平台简介 */
.hero-card {
  border: none;
  background: linear-gradient(135deg, #1677ff 0%, #2B6CB0 100%);
  color: #fff;
  margin-bottom: 8px;
}

.hero {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 24px;
  padding: 12px 8px;
}

.hero-title {
  margin: 0;
  font-size: 34px;
  font-weight: 800;
  letter-spacing: 1px;
}

.hero-subtitle {
  margin: 6px 0 14px;
  font-size: 16px;
  opacity: 0.9;
}

.hero-desc {
  margin: 0 0 20px;
  max-width: 620px;
  font-size: 14px;
  line-height: 1.9;
  opacity: 0.92;
}

.hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;

  /* 主按钮：白底蓝字，在蓝色渐变 hero 上高对比、浅色主题也清晰 */
  .el-button--primary {
    --el-button-bg-color: #ffffff;
    --el-button-border-color: #ffffff;
    --el-button-text-color: #1677ff;
    --el-button-hover-bg-color: #f0f5ff;
    --el-button-hover-border-color: #f0f5ff;
    --el-button-hover-text-color: #1677ff;
  }

  /* 次按钮：蓝色渐变背景上的幽灵按钮（透明底 + 白边 + 白字），浅色主题下不再白字白底 */
  .el-button:not(.el-button--primary) {
    --el-button-bg-color: transparent;
    --el-button-border-color: rgba(255, 255, 255, 0.85);
    --el-button-text-color: #ffffff;
    --el-button-hover-bg-color: rgba(255, 255, 255, 0.15);
    --el-button-hover-border-color: #ffffff;
    --el-button-hover-text-color: #ffffff;
  }
}

.hero-art {
  flex-shrink: 0;
}

.runtime-badges {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.runtime-badge {
  width: 110px;
  text-align: center;
  font-weight: 700;
  letter-spacing: 1px;
}

/* 核心能力 */
.feature-row {
  margin-bottom: 8px;
}

.feature-card {
  height: 100%;
  padding: 22px;
  margin-bottom: 20px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  transition: transform 0.2s, box-shadow 0.2s;

  &:hover {
    transform: translateY(-3px);
    box-shadow: 0 6px 16px rgba(0, 0, 0, 0.08);
  }
}

.feature-title {
  margin: 14px 0 8px;
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.feature-desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

/* 快捷入口 */
.quick-row {
  margin-bottom: 8px;
}

.quick-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 22px 10px;
  margin-bottom: 20px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.2s, transform 0.2s;

  &:hover {
    background: var(--el-color-primary-light-9, #ecf5ff);
    transform: translateY(-2px);
  }
}

.quick-label {
  font-size: 13px;
  color: var(--text-secondary);
}

/* 开源与生态 */
.eco-card {
  margin-bottom: 20px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
}

.eco-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.eco-icon {
  color: #24292f;
}

.eco-name {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.eco-desc {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--text-secondary);
}

.eco-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--el-color-primary, #409EFF);
  text-decoration: none;
  word-break: break-all;

  &:hover {
    text-decoration: underline;
  }

  .eco-url {
    font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  }
}
</style>
