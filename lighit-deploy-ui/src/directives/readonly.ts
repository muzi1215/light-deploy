/**
 * v-readonly 指令：声明某个元素是「写操作入口」。
 *
 * 当全局演示只读模式（useDemoStore().readonly）开启时，元素被置灰且不可点击：
 * - el-button：加 disabled 属性 + is-disabled 类，原生按钮不再触发 click；
 * - 其余可点击元素（如带 @click 的 span）：加 pointer-events:none 阻止点击。
 *
 * 通过 watch 监听 store.readonly，运行时在导航栏切换开关会即时作用于所有已挂载的按钮，
 * 无需刷新页面。关闭只读时自动还原元素状态。
 *
 * 用法：
 *   <el-button v-readonly @click="onEdit">编辑</el-button>           // 置灰禁用
 *   <el-button v-readonly="{ hide: true }" @click="onDelete">删除</el-button>  // 直接隐藏
 */
import type { Directive } from 'vue'
import { watch } from 'vue'
import { useDemoStore } from '@/store/demo.ts'

const DEFAULT_TIP = '演示模式，禁止操作'

/** 已挂载元素的 watch 停止函数，用于 unmounted 时回收 */
const stopMap = new WeakMap<HTMLElement, () => void>()

function applyState(el: HTMLElement, readonly: boolean, binding: { hide?: boolean; tip?: string } | undefined) {
  const hide = binding?.hide
  const tip = binding?.tip ?? DEFAULT_TIP

  if (readonly && hide) {
    el.style.display = 'none'
    return
  }

  if (readonly) {
    el.setAttribute('disabled', 'disabled')
    el.classList.add('is-disabled')
    el.setAttribute('title', tip)
    el.style.pointerEvents = 'none'
    el.style.opacity = '0.6'
    el.style.cursor = 'not-allowed'
  } else {
    el.style.display = ''
    el.removeAttribute('disabled')
    el.classList.remove('is-disabled')
    el.style.removeProperty('pointer-events')
    el.style.removeProperty('opacity')
    el.style.removeProperty('cursor')
    if (el.getAttribute('title') === tip) el.removeAttribute('title')
  }
}

const readonlyDirective: Directive<HTMLElement, { hide?: boolean; tip?: string } | undefined> = {
  mounted(el, binding) {
    const store = useDemoStore()
    const apply = () => applyState(el, store.readonly, binding.value)
    apply()
    const stop = watch(() => store.readonly, apply)
    stopMap.set(el, stop)
  },
  unmounted(el) {
    stopMap.get(el)?.()
    stopMap.delete(el)
  }
}

export default readonlyDirective
