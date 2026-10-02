<template>
  <div class="app-container">
    <el-card>
      <template #header>
        <div class="page-header">
          <span>项目详情</span>
          <div>
            <el-button size="small" type="primary" :icon="Edit" @click="goEdit">编 辑</el-button>
            <el-button size="small" :icon="Back" @click="goBack">返 回</el-button>
          </div>
        </div>
      </template>

      <el-descriptions v-if="project" :column="2" border>
        <el-descriptions-item label="项目编码">{{ project.code }}</el-descriptions-item>
        <el-descriptions-item label="项目名称">{{ project.name }}</el-descriptions-item>
        <el-descriptions-item label="运行时">{{ RUNTIME_TYPE_MAP[project.runtimeType] || project.runtimeType }}</el-descriptions-item>
        <el-descriptions-item label="构建模式">{{ BUILD_MODE_MAP[project.buildMode] || project.buildMode }}</el-descriptions-item>
        <el-descriptions-item label="代码来源">{{ project.sourceType === 'GIT' ? 'Git仓库' : '本地上传' }}</el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="project.status === 1 ? 'success' : 'info'">
            {{ project.status === 1 ? '启用' : '停用' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="Git仓库地址" :span="2">{{ project.gitUrl || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Git分支">{{ project.gitBranch || '-' }}</el-descriptions-item>
        <el-descriptions-item label="Git凭据">{{ credentialName || '-' }}</el-descriptions-item>
        <el-descriptions-item label="镜像仓库" :span="2">{{registryName || '-'}}， 仓库地址：{{ registryUrl || '-' }}</el-descriptions-item>
        <el-descriptions-item label="保留版本数">{{ project.retainReleaseCount }}</el-descriptions-item>
        <el-descriptions-item label="构建机">
          <span v-if="project.buildServerName">{{ project.buildServerName }}</span>
          <el-text v-else type="info" size="small">默认构建机（项目未绑定）</el-text>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ project.createTime }}</el-descriptions-item>
        <el-descriptions-item label="项目描述" :span="2">{{ project.description || '-' }}</el-descriptions-item>
      </el-descriptions>

      <!-- 三段配置只读展示：与编辑页同构（同一 schema 渲染），原始 JSON 以折叠块附带查看 -->
      <template v-if="project">
        <el-divider content-position="left">高级配置（只读）</el-divider>
        <ConfigEditor
          title="构建配置"
          :fields="BUILD_CONFIG_FIELDS"
          :model-value="project.buildConfigJson || ''"
          :context="editorContext"
          readonly
        />
        <ConfigEditor
          title="运行配置"
          :fields="RUNTIME_CONFIG_FIELDS"
          :model-value="project.runtimeConfigJson || ''"
          :context="editorContext"
          readonly
        />
        <ConfigEditor
          title="健康检查配置"
          :fields="HEALTH_CONFIG_FIELDS"
          :model-value="project.healthConfigJson || ''"
          :context="editorContext"
          readonly
        />

        <el-collapse class="raw-json">
          <el-collapse-item title="原始 JSON" name="raw">
            <div class="raw-json-block">
              <div class="raw-json-title">构建配置</div>
              <pre class="raw-json-pre">{{ prettyJson(project.buildConfigJson) || '（未配置）' }}</pre>
              <div class="raw-json-title">运行配置</div>
              <pre class="raw-json-pre">{{ prettyJson(project.runtimeConfigJson) || '（未配置）' }}</pre>
              <div class="raw-json-title">健康检查配置</div>
              <pre class="raw-json-pre">{{ prettyJson(project.healthConfigJson) || '（未配置）' }}</pre>
            </div>
          </el-collapse-item>
        </el-collapse>
      </template>
    </el-card>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目详情页（只读）。
 *
 * 与编辑页共用同一套 schema（constants/projectConfig.ts）渲染只读表单，
 * 用户只想查看时无需进入编辑页；原始 JSON 以折叠块附带展示。
 * 需要修改时点右上角「编辑」跳转 /project/edit/:id。
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Edit, Back } from '@element-plus/icons-vue'
import { getProject } from '@/api/project.ts'
import { listCredentials } from '@/api/credential.ts'
import { listRegistries } from '@/api/registry.ts'
import type { ProjectVo, CredentialVo, RegistryVo } from '@/api/deployTypes.ts'
import {
  RUNTIME_TYPE_MAP,
  BUILD_MODE_MAP,
  BUILD_CONFIG_FIELDS,
  RUNTIME_CONFIG_FIELDS,
  HEALTH_CONFIG_FIELDS
} from '@/constants/projectConfig.ts'
import ConfigEditor from '@/components/ConfigEditor/index.vue'

defineOptions({ name: 'ProjectDetail' })

const route = useRoute()
const router = useRouter()

const project = ref<ProjectVo | null>(null)

/** Git 凭据名称（详情只存 ID，展示时查全局凭据列表回显名称） */
const credentialName = ref('')

/** 镜像仓库名称（同上） */
const registryName = ref('')

/** 镜像仓库地址（同上） */
const registryUrl = ref('')

/** 编辑器联动上下文：详情按项目自身的运行时 / 构建模式渲染对应字段 */
const editorContext = computed(() => ({
  runtimeType: project.value?.runtimeType || '',
  buildMode: project.value?.buildMode || ''
}))

/** 格式化 JSON 文本；未配置或非法 JSON 时返回空串展示「未配置」 */
function prettyJson(value?: string): string {
  if (!value || !value.trim()) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

onMounted(async () => {
  const res = await getProject(String(route.params.id))
  project.value = res.data
  // 回显凭据与镜像仓库名称：列表查回来在前端按 ID 匹配，避免后端详情接口做联表
  const [credentialRes, registryRes] = await Promise.all([listCredentials(), listRegistries()])
  const credentials: CredentialVo[] = credentialRes.data
  const registries: RegistryVo[] = registryRes.data
  credentialName.value = credentials.find((item) => item.id === project.value?.gitCredentialId)?.name || ''
  registryName.value = registries.find((item) => item.id === project.value?.registryId)?.name || ''
  registryUrl.value = registries.find((item) => item.id === project.value?.registryId)?.registryUrl || ''
})

function goEdit() {
  router.push(`/project/edit/${route.params.id}`)
}

function goBack() {
  router.push('/project/list')
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container（定义在 styles/index.scss）

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.raw-json {
  margin-top: 8px;
}

.raw-json-title {
  font-size: 13px;
  font-weight: 600;
  margin: 8px 0 4px;
}

.raw-json-pre {
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
  overflow-x: auto;
  margin: 0 0 8px;
}
</style>
