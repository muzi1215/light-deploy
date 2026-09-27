<template>
  <div class="config-editor">
    <!-- 头部：标题 + 编辑方式切换 -->
    <div class="ce-header">
      <span class="ce-title" :class="{ 'ce-title-muted': !applicable }">{{ title }}</span>
      <el-radio-group
        v-if="!readonly && applicable"
        v-model="mode"
        size="small"
        @change="handleModeChange"
      >
        <el-radio-button value="form">表单编辑</el-radio-button>
        <el-radio-button value="json">JSON 编辑</el-radio-button>
      </el-radio-group>
      <el-tag v-if="!applicable" type="info" size="small" effect="plain">本次不适用</el-tag>
      <span class="ce-hint">{{ modeHint }}</span>
    </div>

    <!-- 整段不适用：当前构建模式 / 运行时组合下这一段没有意义，直接说明而非渲染空表单 -->
    <div v-if="!applicable" class="ce-na">
      <el-alert type="info" :closable="false" show-icon>
        <template #title>
          <span>{{ notApplicableText || '当前项目配置下这一段不生效，无需填写' }}</span>
        </template>
        <div v-if="!readonly" class="ce-na-detail">
          下方内容已折叠。如该段落此前填过值，平台保留原内容不会清空，只是本次不参与执行。
        </div>
      </el-alert>
    </div>

    <!-- JSON 模式：整段文本编辑，适合粘贴与 schema 外的自定义键 -->
    <el-input
      v-if="mode === 'json'"
      v-model="jsonText"
      type="textarea"
      :rows="jsonRows"
      :placeholder="example"
      :disabled="readonly"
      class="ce-json"
    />

    <!-- 表单模式：按 schema 分组渲染，已填值与未知键保留 -->
    <template v-else-if="applicable">
      <div v-for="group in visibleGroups" :key="group" class="ce-group">
        <div class="ce-group-title">{{ group }}</div>
        <el-row :gutter="16">
          <el-col v-for="field in fieldsOfGroup(group)" :key="field.key" :span="field.span ?? 8">
            <div class="ce-field">
              <div class="ce-field-label">
                <span v-if="field.required" class="ce-required">*</span>
                {{ field.label }}
                <!-- 复杂字段（如构建命令）的示例：悬浮展示可直接抄的完整写法；tooltip teleport 到 body，.tooltip-json 为全局样式 -->
                <el-tooltip v-if="field.examples?.length" placement="top" effect="dark" :show-after="100">
                  <template #content>
                    <pre class="tooltip-json">{{ examplesText(field) }}</pre>
                  </template>
                  <el-icon class="form-label-tip"><QuestionFilled /></el-icon>
                </el-tooltip>
              </div>
              <el-select
                v-if="field.type === 'select'"
                v-model="formModel[field.key]"
                :placeholder="field.placeholder || '请选择'"
                clearable
                :disabled="readonly"
                style="width: 100%"
              >
                <el-option v-for="opt in field.options" :key="opt.value" :label="opt.label" :value="opt.value" />
              </el-select>
              <el-input-number
                v-else-if="field.type === 'number'"
                v-model="formModel[field.key]"
                :placeholder="field.placeholder"
                controls-position="right"
                :disabled="readonly"
                style="width: 100%"
              />
              <el-input
                v-else
                v-model="formModel[field.key]"
                :placeholder="field.placeholder"
                clearable
                :disabled="readonly"
              />
              <div v-if="field.tip" class="ce-field-tip">{{ field.tip }}</div>
            </div>
          </el-col>
        </el-row>
      </div>

      <!-- schema 外的自定义键：表单模式不渲染，但保存时原样合并，不静默丢弃 -->
      <div v-if="extraKeyCount > 0" class="ce-extra">
        另有 {{ extraKeyCount }} 个自定义键（{{ extraKeyNames }}），保存时原样保留；如需修改请切换到 JSON 编辑
      </div>
      <el-empty
        v-if="visibleGroups.length === 0"
        description="当前运行时与构建模式下没有可填字段，可切换 JSON 编辑或留空"
        :image-size="60"
      />
    </template>
  </div>
</template>

<script setup lang="ts">
/**
 * 三段项目配置（构建 / 运行 / 健康检查）的双模式编辑器。
 *
 * 同一份 JSON 值提供两种编辑形态（告警配置同款交互，见 docs/前端工程.md）：
 * - 表单模式：按 schema 常量（constants/projectConfig.ts）渲染分组表单，
 *   字段按 runtimeType / buildMode / 探测方式联动显隐；
 * - JSON 模式：整段文本编辑，适合粘贴整份配置与写 schema 外的自定义键。
 *
 * 数据流约定：组件单向向外 emit（update:modelValue），父页面也可通过
 * getValue() / validate()（expose）在提交时取值校验。未知键的保护规则：
 * 表单模式只渲染 schema 内字段，compose 时把解析出的其余键原样合并回 JSON，
 * 避免后端「静默忽略」再叠加前端「静默丢弃」造成配置丢失。
 */
import { ref, reactive, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { QuestionFilled } from '@element-plus/icons-vue'
import type { ConfigField } from '@/constants/projectConfig.ts'

const props = withDefaults(
  defineProps<{
    /** JSON 字符串（可为空串，表示未配置） */
    modelValue?: string
    /** 字段 schema */
    fields: ConfigField[]
    /** 联动上下文：runtimeType / buildMode 等由父页面传入 */
    context?: Record<string, string>
    /** JSON 模式的占位示例 */
    example?: string
    /** 分区标题 */
    title?: string
    /** 头部提示语 */
    hint?: string
    /** 只读态（详情页使用）：控件禁用、不可切换 JSON 模式 */
    readonly?: boolean
    /**
     * 本段配置在当前组合下是否适用。
     *
     * false 时整段折叠为一句说明（如「仅发布产物」模式不需要构建参数），
     * 而不是渲染一张全空或全是无关字段的表单——后者会让人误以为漏填了什么。
     */
    applicable?: boolean
    /** 不适用时的说明文案，缺省用通用文案 */
    notApplicableText?: string
  }>(),
  {
    modelValue: '',
    context: () => ({}),
    example: '',
    title: '',
    hint: '',
    readonly: false,
    applicable: true,
    notApplicableText: ''
  }
)

const emit = defineEmits<{ (e: 'update:modelValue', value: string): void }>()

/** JSON 文本域行数 */
const jsonRows = 12

/** 当前编辑方式 */
const mode = ref<'form' | 'json'>('form')

/** 表单模式的数据载体：键为 schema 的 key，值为用户输入 */
const formModel = reactive<Record<string, any>>({})

/** schema 外的自定义键，表单模式不渲染但保存时原样合并 */
const extraKeys = reactive<Record<string, any>>({})

/** JSON 模式的文本内容 */
const jsonText = ref('')

const modeHint = computed(() => {
  if (props.readonly) return '只读内容，如需修改请点击列表中的「编辑」'
  if (!props.applicable) return '当前组合下不生效'
  if (props.hint) return props.hint
  return mode.value === 'form'
    ? '表单模式适用于常用字段修改；自定义键或整段粘贴请用 JSON 模式'
    : '直接编辑 JSON 文本，保存时按原文提交'
})

/** schema 外的自定义键个数 */
const extraKeyCount = computed(() => Object.keys(extraKeys).length)

/** 自定义键名列表（提示用，最多展示 4 个） */
const extraKeyNames = computed(() => {
  const names = Object.keys(extraKeys)
  return names.length > 4 ? names.slice(0, 4).join('、') + ' 等' : names.join('、')
})

/** 联动上下文：父页面传入的 runtimeType / buildMode + 表单内已填的键（如健康检查 type） */
const mergedContext = computed<Record<string, string>>(() => {
  const merged: Record<string, string> = { ...props.context }
  for (const field of props.fields) {
    const value = formModel[field.key]
    if (typeof value === 'string' && value) {
      merged[field.key] = value
    } else if (typeof value === 'number') {
      merged[field.key] = String(value)
    }
  }
  return merged
})

/** 字段是否满足联动显隐条件 */
function isFieldVisible(field: ConfigField): boolean {
  if (!field.showIf) return true
  return Object.entries(field.showIf).every(([contextKey, allowed]) => {
    const current = mergedContext.value[contextKey]
    return current !== undefined && allowed.includes(current)
  })
}

/** 当前上下文下可见字段 */
const visibleFields = computed(() => props.fields.filter(isFieldVisible))

/** 可见分组（保持 schema 声明顺序，去重） */
const visibleGroups = computed(() => {
  const groups: string[] = []
  for (const field of visibleFields.value) {
    if (!groups.includes(field.group)) groups.push(field.group)
  }
  return groups
})

/** 取某个分组下的可见字段 */
function fieldsOfGroup(group: string): ConfigField[] {
  return visibleFields.value.filter((field) => field.group === group)
}

/** 把字段的示例列表拼成 tooltip 文本：【场景】+ 示例值，多段空行分隔 */
function examplesText(field: ConfigField): string {
  return (field.examples || []).map((item) => `【${item.label}】\n${item.text}`).join('\n\n')
}

/**
 * 把解析出的 JSON 对象装载进表单与未知键集合。
 *
 * @param obj 已解析的 JSON 对象（非对象类型按空处理）
 */
function loadObject(obj: Record<string, any> | null) {
  for (const key of Object.keys(formModel)) {
    delete formModel[key]
  }
  for (const key of Object.keys(extraKeys)) {
    delete extraKeys[key]
  }
  if (!obj) return
  for (const [key, value] of Object.entries(obj)) {
    const field = props.fields.find((item) => item.key === key)
    if (field) {
      formModel[key] = value
    } else {
      extraKeys[key] = value
    }
  }
}

/** 把当前表单值 + 未知键合成为一个 JSON 对象；两者皆空时返回 null */
function composeObject(): Record<string, any> | null {
  const result: Record<string, any> = {}
  for (const field of props.fields) {
    const value = formModel[field.key]
    if (value !== undefined && value !== null && value !== '') {
      result[field.key] = value
    }
  }
  Object.assign(result, extraKeys)
  return Object.keys(result).length > 0 ? result : null
}

/** 当前值的 JSON 文本（格式化输出）；未配置任何内容时返回空串 */
function composeText(): string {
  const obj = mode.value === 'json' ? null : composeObject()
  return obj ? JSON.stringify(obj, null, 2) : ''
}

/** 表单值或 JSON 文本变化后向父页面同步 */
function emitValue() {
  const value = mode.value === 'json' ? jsonText.value.trim() : composeText()
  emit('update:modelValue', value)
}

/**
 * 切换编辑方式时的双向同步。
 *
 * 表单 → JSON：把表单值（含未知键）格式化后填入文本域；
 * JSON → 表单：解析文本重新装载，解析失败时提示并留在 JSON 模式。
 */
function handleModeChange() {
  if (mode.value === 'json') {
    const obj = composeObject()
    jsonText.value = obj ? JSON.stringify(obj, null, 2) : ''
    return
  }
  const text = jsonText.value.trim()
  if (!text) {
    loadObject(null)
    emitValue()
    return
  }
  try {
    const parsed = JSON.parse(text)
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      ElMessage.warning('配置值必须是 JSON 对象（形如 {\"key\": \"value\"}），不能是数组或标量')
      mode.value = 'json'
      return
    }
    loadObject(parsed)
    emitValue()
  } catch {
    ElMessage.error('JSON 语法有误，修正后才能切回表单模式')
    mode.value = 'json'
  }
}

// 表单模式下的任何输入都即时向父页面同步（编辑页用 v-model 也可，详情页只读不受影响）
watch(
  formModel,
  () => {
    if (mode.value === 'form') emitValue()
  },
  { deep: true }
)
watch(
  extraKeys,
  () => {
    if (mode.value === 'form') emitValue()
  },
  { deep: true }
)
watch(jsonText, () => {
  if (mode.value === 'json') emitValue()
})

// 外部值变化（编辑页异步回填）时重新装载；只在非 JSON 模式下生效，避免打断文本编辑。
// 装载前先比较内容：emit 会让父页面回写相同的 v-model 值，不比较会触发「装载 → emit → 再装载」死循环。
watch(
  () => props.modelValue,
  (value) => {
    if (mode.value !== 'form') return
    const text = (value || '').trim()
    if (!text) {
      if (composeObject() === null) return
      loadObject(null)
      return
    }
    let parsed: any
    try {
      parsed = JSON.parse(text)
    } catch {
      // 外部值非法 JSON（理论上不会出现）：保持空表单，提交校验会拦截
      parsed = null
    }
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
      if (composeObject() === null) return
      loadObject(null)
      return
    }
    // 内容等价（键序可能不同）则跳过装载，保证输入过程不被重置
    const current = composeObject()
    if (current && JSON.stringify(current) === JSON.stringify(normalizeOrder(parsed, props.fields))) {
      return
    }
    loadObject(parsed)
  },
  { immediate: true }
)

/**
 * 把外部 JSON 对象按「schema 字段在前、自定义键在后」的固定键序重排，用于与 composeObject 的输出做等价比较。
 */
function normalizeOrder(obj: Record<string, any>, fields: ConfigField[]): Record<string, any> {
  const result: Record<string, any> = {}
  for (const field of fields) {
    if (obj[field.key] !== undefined) result[field.key] = obj[field.key]
  }
  for (const key of Object.keys(obj)) {
    if (!fields.some((field) => field.key === key)) result[key] = obj[key]
  }
  return result
}

/**
 * 取当前编辑结果。
 *
 * @returns JSON 字符串；未配置或清空时返回空串
 */
function getValue(): string {
  return mode.value === 'json' ? jsonText.value.trim() : composeText()
}

/**
 * 提交前校验：JSON 语法 + 可见必填字段 + schema 声明的跨字段一致性规则（ConfigField.checks）。
 *
 * @returns 校验通过返回 { ok: true }；否则返回首个错误信息
 */
function validate(): { ok: boolean; message?: string } {
  // 整段不适用时不参与校验：既没有可填字段，也不该因为历史残留值拦住保存。
  if (!props.applicable) return { ok: true }
  const value = getValue()
  if (value) {
    try {
      const parsed = JSON.parse(value)
      if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
        return { ok: false, message: `${props.title || '配置'}必须是 JSON 对象` }
      }
    } catch {
      return { ok: false, message: `${props.title || '配置'}不是合法的 JSON 格式` }
    }
  }
  for (const field of visibleFields.value) {
    if (!field.required) continue
    const value1 = formModel[field.key]
    if (value1 === undefined || value1 === null || value1 === '') {
      return { ok: false, message: `${props.title || '配置'}的「${field.label}」为必填项` }
    }
  }
  // 跨字段一致性校验：单字段都合法、组合起来必然执行失败的组合，按 schema 里的 checks 拦截
  for (const field of visibleFields.value) {
    for (const check of field.checks || []) {
      if (check.test(formModel)) {
        return { ok: false, message: `${props.title || '配置'}：${check.message}` }
      }
    }
  }
  return { ok: true }
}

defineExpose({ getValue, validate })
</script>

<style lang="scss" scoped>
.config-editor {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
  padding: 14px 16px;
  margin-bottom: 16px;
}

.ce-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.ce-title {
  font-weight: 600;
  font-size: 14px;
}

.ce-title-muted {
  color: var(--el-text-color-secondary);
  font-weight: 500;
}

.ce-na {
  margin-top: 4px;
}

.ce-na-detail {
  font-size: 12px;
  color: var(--el-text-color-regular);
  margin-top: 4px;
  line-height: 1.5;
}

.ce-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.ce-json {
  margin-top: 4px;
}

.ce-group {
  margin-bottom: 4px;

  &:not(:last-child) {
    padding-bottom: 8px;
    border-bottom: 1px dashed var(--el-border-color-lighter);
  }
}

.ce-group-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--el-text-color-regular);
  margin: 4px 0 10px;
}

.ce-field {
  margin-bottom: 8px;
}

.ce-field-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.ce-required {
  color: var(--el-color-danger);
  margin-right: 2px;
}

.ce-field-tip {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  line-height: 1.4;
  margin-top: 2px;
}

.ce-extra {
  font-size: 12px;
  color: var(--el-color-warning);
  margin-top: 8px;
}
</style>
