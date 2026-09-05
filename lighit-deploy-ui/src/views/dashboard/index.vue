<template>
  <div class="dashboard-container">
    <!-- 统计卡片 -->
    <el-row :gutter="20" class="stat-row">
      <el-col :xs="12" :sm="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #409EFF, #2B6CB0)">
          <div class="stat-card-icon">
            <el-icon :size="40"><UserFilled /></el-icon>
          </div>
          <div class="stat-card-info">
            <p class="stat-card-label">用户数</p>
            <p class="stat-card-value">{{ stats.users }}</p>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #67C23A, #38A169)">
          <div class="stat-card-icon">
            <el-icon :size="40"><Document /></el-icon>
          </div>
          <div class="stat-card-info">
            <p class="stat-card-label">订单数</p>
            <p class="stat-card-value">{{ stats.orders }}</p>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #E6A23C, #DD6B20)">
          <div class="stat-card-icon">
            <el-icon :size="40"><Money /></el-icon>
          </div>
          <div class="stat-card-info">
            <p class="stat-card-label">营收（万）</p>
            <p class="stat-card-value">{{ stats.revenue }}</p>
          </div>
        </div>
      </el-col>
      <el-col :xs="12" :sm="6">
        <div class="stat-card" style="background: linear-gradient(135deg, #F56C6C, #E53E3E)">
          <div class="stat-card-icon">
            <el-icon :size="40"><WarningFilled /></el-icon>
          </div>
          <div class="stat-card-info">
            <p class="stat-card-label">待处理</p>
            <p class="stat-card-value">{{ stats.pending }}</p>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- 快捷操作 -->
    <el-card class="quick-card" shadow="never">
      <template #header>
        <span class="card-header-title">快捷操作</span>
      </template>
      <el-row :gutter="20">
        <el-col :xs="12" :sm="6" :md="4" v-for="action in quickActions" :key="action.label">
          <div class="quick-action-item" @click="onQuickAction(action)">
            <el-icon :size="32" :style="{ color: action.color }"><component :is="action.icon" /></el-icon>
            <p>{{ action.label }}</p>
          </div>
        </el-col>
      </el-row>
    </el-card>

    <!-- 开发指引 -->
    <el-card class="guide-card" shadow="never">
      <template #header>
        <span class="card-header-title">开发指引</span>
      </template>
      <div class="guide-content">
        <el-steps direction="vertical" :space="60">
          <el-step title="1. 对接后端 API" description="修改 src/config/apiConfig.ts 中的 API 地址，对接你的后端服务" status="process" />
          <el-step title="2. 新增业务页面" description="在 src/views/ 下创建 .vue 页面文件，参考 src/views/example/list.vue" />
          <el-step title="3. 配置菜单" description="方式一（推荐）：后端返回动态菜单 JSON；方式二：在 src/router/index.ts 手动添加静态路由" />
          <el-step title="4. 开发 API 接口层" description="在 src/api/ 下创建 API 文件，参考 src/api/user.ts" />
          <el-step title="5. 开始业务开发" description="到此已完成基础搭建，可以专注于业务功能开发了" />
        </el-steps>
        <div class="guide-tips">
          <el-alert title="提示" type="success" :closable="false" show-icon>
            <template #default>
              <p>此页面为示例仪表盘，你可以根据自己的业务需求替换为本页内容。</p>
              <p style="margin-top:8px">详细文档请查看项目根目录的 README.md。</p>
            </template>
          </el-alert>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { reactive } from 'vue'
import { useRouter } from 'vue-router'
import { UserFilled, Document, Money, WarningFilled, Plus, Edit, Search, Setting } from '@element-plus/icons-vue'

defineOptions({ name: 'Dashboard' })

const router = useRouter()

/** 示例统计数据 — 替换为你自己的 API 调用 */
const stats = reactive({
  users: 0,
  orders: 0,
  revenue: 0,
  pending: 0
})

/** 快捷操作 */
const quickActions = [
  { label: '新增', icon: Plus, color: '#409EFF', path: '/example' },
  { label: '编辑', icon: Edit, color: '#67C23A', path: '/example' },
  { label: '搜索', icon: Search, color: '#E6A23C', path: '/example' },
  { label: '设置', icon: Setting, color: '#909399', path: '/example' }
]

function onQuickAction(action: typeof quickActions[0]) {
  if (action.path) {
    router.push(action.path)
  }
}
</script>

<style lang="scss" scoped>
.dashboard-container {
  padding: 16px;
  background: var(--page-bg);
  min-height: calc(100vh - 84px);
}

.stat-row { margin-bottom: 20px; }

.stat-card {
  display: flex;
  align-items: center;
  padding: 20px;
  border-radius: 8px;
  color: #fff;
  transition: transform 0.2s, box-shadow 0.2s;
  margin-bottom: 16px;

  &:hover {
    transform: translateY(-2px);
    box-shadow: 0 4px 12px rgba(0, 0, 0, .15);
  }
}

.stat-card-icon {
  opacity: 0.25;
  margin-right: 16px;
}

.stat-card-info { flex: 1; }
.stat-card-label { margin: 0; font-size: 13px; opacity: 0.85; }
.stat-card-value {
  margin: 6px 0;
  font-size: 28px;
  font-weight: 700;
  line-height: 1;
}

.quick-card, .guide-card {
  margin-bottom: 20px;
  background: var(--card-bg);
  border: 1px solid var(--border-color);
}

.card-header-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
}

.quick-action-item {
  text-align: center;
  padding: 20px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;

  &:hover {
    background: var(--table-row-hover);
  }

  p {
    margin: 8px 0 0;
    font-size: 13px;
    color: var(--text-secondary);
  }
}

.guide-content {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;

  .el-steps {
    flex: 1;
    min-width: 300px;
  }

  .guide-tips {
    flex: 1;
    min-width: 300px;
  }
}

.guide-tips p {
  margin: 0;
  font-size: 13px;
  color: var(--text-secondary);
  line-height: 1.8;
}
</style>
