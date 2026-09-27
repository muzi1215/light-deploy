/**
 * 时间格式化工具函数 - Vue2 → Vue3 无框架相关依赖，直接迁移为 TS
 */

/** 格式化时间 */
export function parseTime(time: any, cFormat?: string): string | null {
  if (arguments.length === 0 || !time) {
    return null
  }
  const format = cFormat || '{y}-{m}-{d} {h}:{i}:{s}'
  let date: Date
  if (typeof time === 'object') {
    date = time
  } else {
    if (typeof time === 'string') {
      if (/^[0-9]+$/.test(time)) {
        time = parseInt(time)
      } else {
        time = time.replace(new RegExp(/-/gm), '/')
      }
    }

    if (typeof time === 'number' && time.toString().length === 10) {
      time = time * 1000
    }
    date = new Date(time)
  }
  const formatObj: Record<string, any> = {
    y: date.getFullYear(),
    m: date.getMonth() + 1,
    d: date.getDate(),
    h: date.getHours(),
    i: date.getMinutes(),
    s: date.getSeconds(),
    a: date.getDay()
  }
  const timeStr = format.replace(/{([ymdhisa])+}/g, (result, key) => {
    const value = formatObj[key]
    if (key === 'a') { return ['日', '一', '二', '三', '四', '五', '六'][value] }
    return value.toString().padStart(2, '0')
  })
  return timeStr
}

/** 相对时间格式化 */
export function formatTime(time: number | string, option?: string): string {
  let t: number
  if (('' + time).length === 10) {
    t = parseInt(time as string) * 1000
  } else {
    t = +time
  }
  const d = new Date(t)
  const now = Date.now()
  const diff = (now - d.getTime()) / 1000

  if (diff < 30) {
    return '刚刚'
  } else if (diff < 3600) {
    return Math.ceil(diff / 60) + '分钟前'
  } else if (diff < 3600 * 24) {
    return Math.ceil(diff / 3600) + '小时前'
  } else if (diff < 3600 * 24 * 2) {
    return '1天前'
  }
  if (option) {
    return parseTime(time, option) || ''
  } else {
    return d.getMonth() + 1 + '月' + d.getDate() + '日' + d.getHours() + '时' + d.getMinutes() + '分'
  }
}

/**
 * 把日期区间选择器的值拆成后端查询参数。
 *
 * 全平台列表的创建时间筛选都走这一份转换：区间值在页面上是 [开始, 截止] 一个数组，
 * 到后端是两个独立参数，拆的动作散在各个页面就会出现「有的页面漏传 end」这类偏差。
 *
 * @param range 日期区间值，形如 ['2026-09-01 00:00:00', '2026-09-30 23:59:59']；允许为空
 * @returns 查询参数对象；未选区间时两个字段都是 undefined（不参与查询）
 */
export function splitTimeRange(range?: string[] | null): {
  createTimeBegin?: string
  createTimeEnd?: string
} {
  if (!range || range.length < 2) {
    return { createTimeBegin: undefined, createTimeEnd: undefined }
  }
  return { createTimeBegin: range[0] || undefined, createTimeEnd: range[1] || undefined }
}

/** URL 参数解析为对象 */
export function param2Obj(url: string): Record<string, string> {
  const search = decodeURIComponent(url.split('?')[1]).replace(/\+/g, ' ')
  if (!search) {
    return {}
  }
  const obj: Record<string, string> = {}
  const searchArr = search.split('&')
  searchArr.forEach(v => {
    const index = v.indexOf('=')
    if (index !== -1) {
      const name = v.substring(0, index)
      const val = v.substring(index + 1, v.length)
      obj[name] = val
    }
  })
  return obj
}