<#-- 前端列表页模板：生成标准 CRUD 页面（搜索+分页+新增/编辑弹窗），遵守 AGENTS.md §20 约定 -->
<template>
  <div class="app-container">
    <!-- 搜索区域：文本输入框支持回车触发查询 -->
    <div class="search-div">
      <el-form :model="searchForm" inline @submit.prevent>
<#list searchFields as field>
        <el-form-item label="${field.label}">
          <el-input v-model="searchForm.${field.camelName}" placeholder="请输入${field.label}" clearable style="width: 180px" @keyup.enter="handleSearch" />
        </el-form-item>
</#list>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <!-- 工具栏 -->
    <div class="tools-div">
      <el-button type="primary" :icon="Plus" @click="handleAdd">新增${comment}</el-button>
    </div>

    <!-- 数据表格 -->
    <el-table v-loading="loading" :data="tableData" border stripe>
<#list tableFields as field>
      <el-table-column prop="${field.camelName}" label="${field.label}" min-width="120" show-overflow-tooltip />
</#list>
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
<#list formFields as field>
        <el-form-item label="${field.label}" prop="${field.camelName}">
<#if field.control == "textarea">
          <el-input v-model="formData.${field.camelName}" type="textarea" :rows="3" placeholder="请输入${field.label}" />
<#elseif field.control == "number">
          <el-input-number v-model="formData.${field.camelName}" style="width: 160px" />
<#elseif field.control == "radio">
          <el-radio-group v-model="formData.${field.camelName}">
            <el-radio :value="1">是</el-radio>
            <el-radio :value="0">否</el-radio>
          </el-radio-group>
<#else>
          <el-input v-model="formData.${field.camelName}" placeholder="请输入${field.label}" />
</#if>
        </el-form-item>
</#list>
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
 * ${comment}管理页面（由代码生成器生成，拷入 lighit-deploy-ui/src/views/ 后人工精化）。
 *
 * 对接后端 /${entityPath} 标准六接口。约定：
 * 1. 搜索框回车触发查询，查询时页码重置为第一页；
 * 2. 对话框 720px / label 110px，文本域放表单最底部；
 * 3. 表单校验规则须与后端 Service 校验镜像，前端先拦截，后端为准；
 * 4. 路由注册片段（src/router/index.ts，二级菜单结构）：
 *    {
 *      path: '/${entityPath}',
 *      component: Layout,
 *      redirect: '/${entityPath}/list',
 *      alwaysShow: true,
 *      meta: { title: '${comment}管理', icon: 'tree' },
 *      children: [
 *        { path: 'list', name: '${entityName}List',
 *          component: () => import('@/views/${viewDir}/index.vue'),
 *          meta: { title: '${comment}列表' } }
 *      ]
 *    }
 */
import { ref, reactive, computed } from 'vue'
import type { FormInstance, FormRules } from 'element-plus'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Edit, Delete } from '@element-plus/icons-vue'
import { page${entityName}, create${entityName}, update${entityName}, delete${entityName} } from '@/api/${module}.ts'
import type { ${entityName}Vo, ${entityName}Dto } from '@/api/${module}.ts'

defineOptions({ name: '${entityName}List' })

// ==================== 搜索 ====================
const searchForm = reactive({
<#list searchFields as field>
  ${field.camelName}: '',
</#list>
})

/** 触发查询：页码重置为第一页后重新加载数据 */
function handleSearch() {
  pagination.pageNum = 1
  fetchData()
}

/** 重置全部搜索条件并回到第一页查询 */
function handleReset() {
<#list searchFields as field>
  searchForm.${field.camelName} = ''
</#list>
  handleSearch()
}

// ==================== 表格数据 ====================
const loading = ref(false)
const tableData = ref<${entityName}Vo[]>([])
const pagination = reactive({ pageNum: 1, pageSize: 10, total: 0 })

/** 加载${comment}分页数据，搜索条件为空时不传给后端 */
async function fetchData() {
  loading.value = true
  try {
    const res = await page${entityName}({
      pageNum: pagination.pageNum,
      pageSize: pagination.pageSize,
<#list searchFields as field>
      ${field.camelName}: searchForm.${field.camelName} || undefined,
</#list>
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

/** 表单数据对象（与后端 DTO 字段对应，字段名见 src/api/${module}.ts） */
const formData = reactive<${entityName}Dto>({
<#list formFields as field>
  ${field.camelName}: undefined,
</#list>
})

<#if formFields?has_content>
/** 表单校验规则：须与后端 Service 校验镜像，按业务精化必填项 */
const formRules: FormRules = {
<#list formFields as field>
  <#if field.required>
  ${field.camelName}: [{ required: true, message: '请输入${field.label}', trigger: 'blur' }],
  </#if>
</#list>
}
<#else>
const formRules: FormRules = {}
</#if>

/** 对话框标题：编辑时显示"编辑"，新增时显示"新增" */
const dialogTitle = computed(() => (isEdit.value ? '编辑${comment}' : '新增${comment}'))

/** 打开新增对话框 */
function handleAdd() {
  isEdit.value = false
  dialogVisible.value = true
}

/**
 * 打开编辑对话框并回填字段。
 *
 * @param row 待编辑的${comment}行数据
 */
function handleEdit(row: ${entityName}Vo) {
  isEdit.value = true
  editId.value = row.id
<#list formFields as field>
  formData.${field.camelName} = row.${field.camelName} as any
</#list>
  dialogVisible.value = true
}

/** 关闭对话框后清空表单与编辑状态 */
function resetForm() {
<#list formFields as field>
  formData.${field.camelName} = undefined
</#list>
  editId.value = ''
  formRef.value?.resetFields()
}

/** 提交新增/编辑表单，成功后关闭对话框并刷新列表 */
async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  submitLoading.value = true
  try {
    if (isEdit.value) {
      await update${entityName}(editId.value, formData)
      ElMessage.success('修改成功')
    } else {
      await create${entityName}(formData)
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
 * 删除${comment}（逻辑删除）。
 *
 * @param row 待删除的${comment}行数据；仍被其他数据引用时后端返回 DATA_CONFLICT
 */
async function handleDelete(row: ${entityName}Vo) {
  await ElMessageBox.confirm(`确定要删除该${comment}记录吗？`, '提示', { type: 'warning' })
  await delete${entityName}(row.id)
  ElMessage.success('删除成功')
  fetchData()
}
</script>

<style lang="scss" scoped>
// 使用全局工具类 .app-container / .search-div / .tools-div
// 定义在 styles/index.scss 中
</style>
