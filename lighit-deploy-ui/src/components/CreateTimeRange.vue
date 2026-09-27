<template>
  <!--
    列表搜索区的「创建时间」区间选择器。

    全平台所有带搜索区的列表共用这一个组件：时间区间在每个页面的语义完全相同
    （筛选 create_time），各自复制一份 el-date-picker 必然会在 default-time、
    value-format 这类细节上出现分歧，而分歧的表现是「同一天查不到数据」这种
    既不报错也难复现的问题。
  -->
  <el-form-item label="创建时间">
    <el-date-picker
      v-model="model"
      type="datetimerange"
      range-separator="至"
      start-placeholder="开始时间"
      end-placeholder="截止时间"
      value-format="YYYY-MM-DD HH:mm:ss"
      :default-time="DEFAULT_TIME"
      :clearable="true"
      style="width: 360px"
      @change="handleChange"
    />
  </el-form-item>
</template>

<script setup lang="ts">
/**
 * 创建时间区间选择器。
 *
 * 对外只暴露一个 v-model（[开始, 截止] 两个字符串，格式与后端 @DateTimeFormat 一致），
 * 父组件用 splitTimeRange 把它拆成 createTimeBegin / createTimeEnd 两个查询参数。
 */
import { ref } from 'vue'

defineOptions({ name: 'CreateTimeRange' })

const model = defineModel<string[]>({ default: () => [] })

/**
 * 选完日期后的默认时刻：开始取 00:00:00、截止取 23:59:59。
 *
 * 不设置时两端都会是 00:00:00，用户选「9 月 1 日 至 9 月 1 日」实际查的是
 * [09-01 00:00:00, 09-01 00:00:00]，当天数据一条都查不到——这是日期区间筛选
 * 最典型也最容易被忽略的坑。
 */
const DEFAULT_TIME = ref<Date[]>([
  new Date(2000, 1, 1, 0, 0, 0),
  new Date(2000, 1, 1, 23, 59, 59)
])

/**
 * 清空时把值收敛成空数组。
 *
 * @param value 当前值；Element Plus 清空后会给 null，直接落到查询参数里会变成 "null"
 */
function handleChange(value: string[] | null) {
  if (!value) {
    model.value = []
  }
}
</script>
