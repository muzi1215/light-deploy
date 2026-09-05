<template>
  <div class="app-container">
    <!-- 搜索区域 -->
    <div class="search-div">
      <el-form :model="searchForm" inline>
        <el-form-item label="名称">
          <el-input v-model="searchForm.name" placeholder="请输入名称" clearable />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="请选择" clearable style="width: 140px">
            <el-option label="启用" value="1" />
            <el-option label="禁用" value="0" />
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
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增</el-button>
      <el-button type="danger" :icon="Delete" :disabled="!selectedIds.length" @click="handleBatchDelete">批量删除</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table
      v-loading="loading"
      :data="tableData"
      border
      stripe
      @selection-change="(rows: any[]) => selectedIds = rows.map((r: any) => r.id)"
    >
      <el-table-column type="selection" width="50" align="center" />
      <el-table-column prop="id" label="ID" width="80" align="center" />
      <el-table-column prop="name" label="名称" min-width="160" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="创建时间" width="180" align="center" />
      <el-table-column label="操作" width="220" fixed="right" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :icon="Edit" @click="handleEdit(row)">编辑</el-button>
          <el-button link type="danger" :icon="Delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-model:current-page="pagination.page"
      v-model:page-size="pagination.limit"
      :total="pagination.total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      style="margin-top:16px;justify-content:flex-end"
      @size-change="handleSearch"
      @current-change="handleSearch"
    />

    <!-- 新增/编辑对话框 -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogTitle"
      width="560px"
      :close-on-click-modal="false"
      @close="resetForm"
    >
      <el-form ref="formRef" :model="formData" :rules="formRules" label-width="80px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="formData.name" placeholder="请输入名称" />
        </el-form-item>
        <el-form-item label="状态" prop="status">
          <el-radio-group v-model="formData.status">
            <el-radio :value="1">启用</el-radio>
            <el-radio :value="0">禁用</el-radio>
          </el-radio-group>
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
 * ============================================================
 * 示例 CRUD 页面 - 可作为新页面开发的起点
 *
 * 使用说明：
 * 1. 复制本文件到 src/views/{your-module}/list.vue
 * 2. 修改搜索条件、表格列、表单字段
 * 3. 替换 API 调用为你的实际接口
 * 4. 在 router 或后端菜单中注册路由
 * ============================================================
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'

defineOptions({ name: 'ExampleList' })

// ==================== 搜索 ====================
const searchForm = reactive({ name: '', status: '' })

function handleSearch() {
  fetchData()
}

function handleReset() {
  searchForm.name = ''
  searchForm.status = ''
  fetchData()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<any[]>([])
const selectedIds = ref<number[]>([])
const pagination = reactive({ page: 1, limit: 10, total: 0 })

async function fetchData() {
  loading.value = true
  try {
    // TODO: 替换为你的实际 API 调用
    // const res = await exampleApi.getPageList(pagination.page, pagination.limit, searchForm)
    // tableData.value = res.data.records
    // pagination.total = res.data.total

    // 模拟数据
    await new Promise(r => setTimeout(r, 300))
    tableData.value = [
      { id: 1, name: '示例数据 1', status: 1, createTime: '2024-01-01 12:00:00' },
      { id: 2, name: '示例数据 2', status: 0, createTime: '2024-01-02 15:30:00' }
    ]
    pagination.total = 2
  } finally {
    loading.value = false
  }
}

// 初始加载
fetchData()

// ==================== 新增/编辑 ====================
const dialogVisible = ref(false)
const submitLoading = ref(false)
const isEdit = ref(false)
const formRef = ref<FormInstance>()
const formData = reactive({ id: 0, name: '', status: 1 })
const formRules: FormRules = {
  name: [{ required: true, message: '请输入名称', trigger: 'blur' }]
}

const dialogTitle = computed(() => isEdit.value ? '编辑' : '新增')

function handleAdd() {
  isEdit.value = false
  dialogVisible.value = true
}

function handleEdit(row: any) {
  isEdit.value = true
  formData.id = row.id
  formData.name = row.name
  formData.status = row.status
  dialogVisible.value = true
}

function resetForm() {
  formData.id = 0
  formData.name = ''
  formData.status = 1
  formRef.value?.resetFields()
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    // TODO: 替换为你的实际 API 调用
    // if (isEdit.value) { await exampleApi.updateById(formData) }
    // else { await exampleApi.save(formData) }

    ElMessage.success(isEdit.value ? '编辑成功' : '新增成功')
    dialogVisible.value = false
    fetchData()
  } finally {
    submitLoading.value = false
  }
}

// ==================== 删除 ====================
async function handleDelete(row: any) {
  await ElMessageBox.confirm(`确定要删除 "${row.name}" 吗？`, '提示', { type: 'warning' })
  // TODO: 替换为你的实际 API 调用
  // await exampleApi.removeById(row.id)
  ElMessage.success('删除成功')
  fetchData()
}

async function handleBatchDelete() {
  await ElMessageBox.confirm(`确定要删除选中的 ${selectedIds.value.length} 条数据吗？`, '提示', { type: 'warning' })
  // TODO: 替换为你的实际 API 调用
  // await exampleApi.removeByIds(selectedIds.value)
  ElMessage.success('批量删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
</style>
