<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="项目编码">
          <el-input v-model="searchForm.code" placeholder="请输入项目编码" clearable style="width: 160px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="项目名称">
          <el-input v-model="searchForm.name" placeholder="请输入项目名称" clearable style="width: 160px" @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="运行时">
          <el-select v-model="searchForm.runtimeType" placeholder="请选择" clearable style="width: 130px">
            <el-option label="Docker容器" value="DOCKER" />
            <el-option label="Java JAR" value="JAR" />
            <el-option label="静态产物" value="STATIC" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 120px">
            <el-option label="启用" value="ENABLED" />
            <el-option label="停用" value="DISABLED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 工具栏 -->
    <div class="tools-div">
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增项目</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
      <el-table-column prop="code" label="项目编码" width="140" show-overflow-tooltip />
      <el-table-column prop="name" label="项目名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="runtimeType" label="运行时" width="110" align="center">
        <template #default="{ row }">
          <el-tag>{{ RUNTIME_TYPE_MAP[row.runtimeType] || row.runtimeType }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="sourceType" label="代码来源" width="100" align="center">
        <template #default="{ row }">
          {{ row.sourceType === 'GIT' ? 'Git仓库' : '本地上传' }}
        </template>
      </el-table-column>
      <el-table-column prop="buildMode" label="构建模式" width="130" align="center">
        <template #default="{ row }">
          {{ BUILD_MODE_MAP[row.buildMode] || row.buildMode }}
        </template>
      </el-table-column>
      <el-table-column prop="gitBranch" label="Git分支" width="120" show-overflow-tooltip />
      <el-table-column prop="retainReleaseCount" label="保留版本" width="90" align="center" />
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'ENABLED' ? 'success' : 'info'">
            {{ row.status === 'ENABLED' ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="170" align="center" />
      <el-table-column label="操作" width="140" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
          <el-button link type="danger" :icon="Delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-model:current-page="pagination.pageNum"
      v-model:page-size="pagination.pageSize"
      :total="pagination.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top:16px;justify-content:flex-end"
      @size-change="fetchData"
      @current-change="fetchData"
    />

    <!-- 新增/编辑对话框：统一宽度 720px、label 110px，文本域统一放表单底部 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="720px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="110px">
        <el-form-item label="项目编码" prop="code">
          <el-input v-model="formData.code" placeholder="字母、数字、下划线和中划线，2-64位" />
        </el-form-item>
        <el-form-item label="项目名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入项目名称" />
        </el-form-item>
        <el-form-item label="运行时" prop="runtimeType">
          <el-radio-group v-model="formData.runtimeType">
            <el-radio value="DOCKER">Docker容器</el-radio>
            <el-radio value="JAR">Java JAR</el-radio>
            <el-radio value="STATIC">静态产物</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="代码来源" prop="sourceType">
          <el-radio-group v-model="formData.sourceType">
            <el-radio value="GIT">Git仓库</el-radio>
            <el-radio value="UPLOAD">本地上传</el-radio>
          </el-radio-group>
        </el-form-item>
        <template v-if="formData.sourceType === 'GIT'">
          <el-form-item label="Git仓库地址" prop="gitUrl">
            <el-input v-model="formData.gitUrl" placeholder="https://git.example.com/group/repo.git" />
          </el-form-item>
          <el-form-item label="Git分支" prop="gitBranch">
            <el-input v-model="formData.gitBranch" placeholder="默认分支，如 main（选填）" />
          </el-form-item>
        </template>
        <el-form-item label="构建模式" prop="buildMode">
          <el-select v-model="formData.buildMode" style="width: 260px">
            <el-option label="平台 Dockerfile 模板" value="PLATFORM_TEMPLATE" />
            <el-option label="项目自带 Dockerfile" value="PROJECT_DOCKERFILE" />
            <el-option label="分层构建（base + app）" value="LAYERED_DOCKER" />
            <el-option label="仅发布已有产物" value="ARTIFACT_ONLY" />
          </el-select>
        </el-form-item>
        <el-form-item label="保留版本数" prop="retainReleaseCount">
          <el-input-number v-model="formData.retainReleaseCount" :min="1" :max="100" style="width: 160px" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="formData.status">
            <el-radio value="ENABLED">启用</el-radio>
            <el-radio value="DISABLED">停用</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="项目描述" prop="description">
          <el-input v-model="formData.description" type="textarea" :rows="2" placeholder="项目用途说明（选填）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 项目管理页面。
 *
 * 对接后端 /project 标准六接口。业务约定：
 * 1. 项目编码创建后不允许修改（编辑时输入框禁用），由后端做唯一性校验；
 * 2. GIT 来源必须填写 Git 仓库地址（后端强制校验，表单联动显示 Git 字段）；
 * 3. 构建配置/运行配置/健康检查三段 JSON 属于高级配置，第一版由后续
 *    构建与发布模块消费，表单暂不提供编辑入口，避免误填。
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { pageProject, createProject, updateProject, deleteProject } from '@/api/project.ts'
import type { ProjectVo, ProjectDto } from '@/api/deployTypes.ts'

defineOptions({ name: 'ProjectList' })

/** 运行时展示映射（与后端 RuntimeType 枚举一致） */
const RUNTIME_TYPE_MAP: Record<string, string> = {
  DOCKER: 'Docker容器',
  JAR: 'Java JAR',
  STATIC: '静态产物'
}

/** 构建模式展示映射（与后端 BuildMode 枚举一致） */
const BUILD_MODE_MAP: Record<string, string> = {
  PLATFORM_TEMPLATE: '平台模板',
  PROJECT_DOCKERFILE: '项目Dockerfile',
  LAYERED_DOCKER: '分层构建',
  ARTIFACT_ONLY: '仅发布产物'
}

// ==================== 搜索 ====================
const searchForm = reactive({ code: '', name: '', runtimeType: '', status: '' })

function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

function handleReset() {
  searchForm.code = ''
  searchForm.name = ''
  searchForm.runtimeType = ''
  searchForm.status = ''
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<ProjectVo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    const res = await pageProject({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
      code: searchForm.code || undefined,
      name: searchForm.name || undefined,
      runtimeType: searchForm.runtimeType || undefined,
      status: searchForm.status || undefined
    })
    tableData.value = res.data.records
    pagination.total = Number(res.data.total)
  } finally {
    loading.value = false
  }
}

fetchData()

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const submitLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const editId = ref('')

const formData = reactive<ProjectDto>({
  code: '',
  name: '',
  runtimeType: 'DOCKER',
  sourceType: 'GIT',
  gitUrl: '',
  gitBranch: '',
  buildMode: 'PLATFORM_TEMPLATE',
  retainReleaseCount: 5,
  status: 'ENABLED',
  description: ''
})

const formRules: FormRules = {
  code: [
    { required: true, message: '请输入项目编码', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_-]{2,64}$/, message: '只允许字母、数字、下划线和中划线，2-64位', trigger: 'blur' }
  ],
  name: [{ required: true, message: '请输入项目名称', trigger: 'blur' }],
  runtimeType: [{ required: true, message: '请选择运行时', trigger: 'change' }],
  // GIT 来源必须填写仓库地址，与后端校验规则一致
  gitUrl: [{
    validator: (_rule: any, value: string, callback: (err?: Error) => void) => {
      if (formData.sourceType === 'GIT' && !value) {
        callback(new Error('Git 来源项目必须填写 Git 仓库地址'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }]
}

const dialogTitle = computed(() => (isEdit.value ? '编辑项目' : '新增项目'))

function handleAdd() {
  isEdit.value = false
  dialogVisible.value = true
}

/**
 * 打开编辑对话框并回填字段。
 *
 * @param row 待编辑的项目行数据
 */
function handleEdit(row: ProjectVo) {
  isEdit.value = true
  editId.value = row.id
  formData.code = row.code
  formData.name = row.name
  formData.runtimeType = row.runtimeType
  formData.sourceType = row.sourceType
  formData.gitUrl = row.gitUrl || ''
  formData.gitBranch = row.gitBranch || ''
  formData.buildMode = row.buildMode
  formData.retainReleaseCount = row.retainReleaseCount
  formData.status = row.status
  formData.description = row.description || ''
  dialogVisible.value = true
}

function resetForm() {
  formData.code = ''
  formData.name = ''
  formData.runtimeType = 'DOCKER'
  formData.sourceType = 'GIT'
  formData.gitUrl = ''
  formData.gitBranch = ''
  formData.buildMode = 'PLATFORM_TEMPLATE'
  formData.retainReleaseCount = 5
  formData.status = 'ENABLED'
  formData.description = ''
  editId.value = ''
  formRef.value?.resetFields()
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    if (isEdit.value) {
      // 后端修改接口同样校验编码必填且做唯一性预检（排除当前记录自身）
      await updateProject(editId.value, formData)
      ElMessage.success('修改成功')
    } else {
      await createProject(formData)
      ElMessage.success('新增成功')
    }
    dialogVisible.value = false
    fetchData()
  } finally {
    submitLoading.value = false
  }
}

// ==================== 删除 ====================
/**
 * 删除项目（逻辑删除）。
 *
 * @param row 待删除的项目行数据；仍被部署目标引用时后端返回 DATA_CONFLICT
 */
async function handleDelete(row: ProjectVo) {
  // 后端在项目仍被部署目标引用时返回 DATA_CONFLICT，request 层会提示具体原因
  await ElMessageBox.confirm(`确定要删除项目「${row.name}」吗？若项目已绑定部署目标，需先删除或迁移部署目标。`, '提示', { type: 'warning' })
  await deleteProject(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
</style>
