/**
 * 任务执行日志查看器（组合式函数）。
 *
 * 平台里有 5 处展示任务执行日志：任务列表、构建列表、发布列表、服务器连通性测试、
 * 项目触发构建。它们的取日志方式、滚动行为与下载入口完全一致，
 * 差异只在容器（抽屉 / 弹窗）、标题与页脚上的额外按钮。把这段逻辑收在本文件里，
 * 页面只负责渲染控制台与外层容器，避免同一份轮询代码散在 5 个文件里、改一处漏四处。
 *
 * 遵守 AGENTS.md §20.9 的实时日志约定：
 * 1. 用 `/taskLog/pull` 做增量拉取，锚点是 sequenceNo（不是主键 id），日志只追加、不排序、不去重；
 * 2. 停止条件必须写成「finished === true 且本批条数 < pageSize」，本批正好填满一页说明还有剩余；
 * 3. 状态与日志取自同一次 pull 响应，不再单独查一次任务详情，避免状态与日志不同步；
 * 4. 容器关闭与组件卸载都要停轮询。
 *
 * **容器不拦截关闭**：日志轮询跑在浏览器里，而后端任务跑在服务端执行器里，
 * 关掉容器只是停掉前端这段轮询，任务该跑多久还是跑多久、该出的日志一条不少，
 * 重新打开会从服务端重新拉全量。锁住关闭入口只会把用户关在弹窗里等任务跑完，
 * 换不来任何安全性，因此这里不提供关闭拦截。
 *
 * @example
 * const { logs, task, running, bodyRef, start, stop, download } = useTaskLogViewer()
 * start({ taskId: row.taskId, taskNo: row.taskNo, status: row.status, progress: row.progress })
 */
import { computed, nextTick, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { exportTaskLog, pullTaskLog } from '@/api/task.ts'
import type { TaskLogVo } from '@/api/deployTypes.ts'
import { isTaskFinal } from '@/constants/taskStatus.ts'

/** 日志每页拉取条数，与后端 /taskLog/pull 的 limit 对应 */
const DEFAULT_PAGE_SIZE = 200

/** 日志轮询间隔（毫秒） */
const DEFAULT_POLL_INTERVAL = 1500

/** 日志视图绑定的异步任务：只承载展示所需字段，不是完整的任务详情 */
export interface TaskLogTask {
  /** 任务主键 */
  id: string
  /** 任务编号，用于标题与下载文件名兜底 */
  taskNo: string
  /**
   * 任务状态，取值见后端 TaskStatus（0 排队中 / 1 执行中 / 2 成功 / 3 失败 / 4 已取消）。
   *
   * 首次拉取成功前保持 undefined，页面显示「未知」；拉取失败时也会退回 undefined，
   * 如实表达「拿不到服务端结论」，而不是继续显示一个可能已经过期的状态。
   */
  status?: number
  /** 任务进度，0-100 */
  progress?: number
  /** 当前执行阶段 */
  currentStage?: string
  /** 失败原因，未失败时为空 */
  errorMessage?: string
}

/** 打开日志视图时的任务种子数据，通常直接取自列表行 */
export interface TaskLogSeed {
  /** 任务主键；为空表示该业务记录没有关联任务，此时不轮询也不允许下载 */
  taskId?: string
  /** 任务编号，用于标题与下载文件名兜底 */
  taskNo?: string
  /**
   * 任务状态初值。
   *
   * 只有在状态**本身就是任务状态**时才传（任务列表的行数据、连通性测试返回的 TaskVo）。
   * 发布记录的 status 是 DeploymentStatus（含 5 回滚中 / 6 已回滚），构建记录的 status 是
   * BuildStatus，它们的数字编码与 TaskStatus 只是部分重合，传进来会让首帧的状态标签与进度
   * 错位（例如回滚中的部署状态 5 会被当成「执行中」）。状态一律以首次 pull 的响应为准。
   */
  status?: number
  /** 任务进度初值，同上只在与任务状态同源时传 */
  progress?: number
}

/** 组合式函数可调参数 */
export interface UseTaskLogViewerOptions {
  /** 轮询间隔（毫秒），默认 1500 */
  pollInterval?: number
  /** 每次增量拉取条数，默认 200 */
  pageSize?: number
  /**
   * 任务进入终态时的回调。
   *
   * 日志视图所在的列表页往往把状态也画在表格里（构建状态、发布状态），
   * 任务刚结束的那一刻表格还是打开日志前的旧数据，需要借这个回调重新拉一次列表。
   * 回调只会在「轮询拉到终态」时触发一次，任务打开时已是终态的情况不会触发。
   */
  onFinished?: () => void
}

/**
 * 创建一个任务执行日志查看器。
 *
 * 返回的状态与方法由页面直接绑定到容器：`logs` / `task` 渲染控制台，
 * `running` 供页脚提示「任务仍在执行」，`bodyRef` 绑到日志滚动容器，
 * `start` 在打开时调用、`stop` 在容器关闭时调用，`download` 接下载按钮。
 * 容器不拦截关闭：关闭只停掉前端轮询，后端任务不受影响。
 *
 * @param options 轮询间隔与分页大小，通常保持默认
 * @returns 日志查看器的状态与方法
 */
export function useTaskLogViewer(options: UseTaskLogViewerOptions = {}) {
  const pollInterval = options.pollInterval ?? DEFAULT_POLL_INTERVAL
  const pageSize = options.pageSize ?? DEFAULT_PAGE_SIZE
  const onFinished = options.onFinished

  /** 日志控制台容器，追加日志后自动滚动到底部 */
  const bodyRef = ref<HTMLElement | null>(null)
  /** 已拉取到的日志，按顺序号追加，不做排序与去重（后端保证增量且有序） */
  const logs = ref<TaskLogVo[]>([])
  /** 当前日志对应的任务，状态、进度、阶段、失败原因都取自它 */
  const task = ref<TaskLogTask | null>(null)
  /** 下载中标记，避免重复点击触发多次下载 */
  const downloading = ref(false)

  /** 增量锚点：已拉取到的最大日志顺序号 */
  let lastSeq = 0
  /** 轮询定时器；容器关闭或任务进入终态必须清理 */
  let pollTimer: ReturnType<typeof setInterval> | null = null
  /** 请求中标记：上一轮还没返回时不重复发起，避免慢响应时请求堆积 */
  let polling = false

  /**
   * 任务是否仍在执行中（排队中或执行中）。
   *
   * 只用来给页脚加一句「任务还在跑」的提示。**不用它锁关闭入口**：容器关闭只停前端轮询，
   * 后端任务照跑不移，锁住反而把用户困在弹窗里等任务结束，没有任何实际收益。
   */
  const running = computed(() => task.value?.status === 0 || task.value?.status === 1)

  /** 进度条状态：成功绿色、失败红色，其余保持主题色 */
  const progressStatus = computed<string | undefined>(() => {
    if (task.value?.status === 2) return 'success'
    if (task.value?.status === 3) return 'exception'
    return undefined
  })

  /**
   * 打开日志视图：重置状态并按种子数据开始拉取。
   *
   * @param seed 任务种子数据；taskId 为空时不轮询，仅提示无法查看
   */
  function start(seed: TaskLogSeed) {
    reset()
    if (!seed.taskId) {
      ElMessage.warning('该记录未关联异步任务，无法查看执行日志')
      return
    }
    task.value = {
      id: seed.taskId,
      taskNo: seed.taskNo || '',
      // 列表行的状态只是初值：首次 pull 返回后立刻被服务端状态覆盖。
      status: seed.status,
      progress: seed.progress
    }
    pullLogs()
    startPolling()
  }

  /** 关闭日志视图：清理状态并停止轮询，容器关闭事件与页面卸载都调用它 */
  function reset() {
    stopPolling()
    logs.value = []
    task.value = null
    lastSeq = 0
    polling = false
  }

  /** 启动轮询；已在轮询时不重复启动，任务已是终态时不启动（秒级失败的任务首次拉取就已结束） */
  function startPolling() {
    stopPolling()
    if (isTaskFinal(task.value?.status)) return
    pollTimer = setInterval(pullLogs, pollInterval)
  }

  /** 停止轮询并清理定时器 */
  function stopPolling() {
    if (pollTimer !== null) {
      clearInterval(pollTimer)
      pollTimer = null
    }
  }

  /**
   * 增量拉取一次日志。
   *
   * 用上次响应的 lastSeq 作为锚点，只取新增日志并按顺序追加；状态与日志取自同一次响应，
   * 任务进入终态后停止轮询。请求异常时停止轮询避免持续刷错误提示，用户可重新打开日志。
   */
  async function pullLogs() {
    const current = task.value
    if (!current || polling) return
    polling = true
    try {
      const res = await pullTaskLog(current.id, lastSeq, pageSize)
      const data = res.data
      // 任务状态随日志同批返回，避免「状态已成功、日志还停在执行中」这类不一致。
      if (task.value) {
        task.value.status = data.status
        task.value.progress = data.progress
        task.value.currentStage = data.currentStage
        task.value.errorMessage = data.errorMessage
      }
      if (data.logs.length > 0) {
        logs.value.push(...data.logs)
        lastSeq = data.lastSeq
        await scrollToBottom()
      }
      if (data.finished && data.logs.length < pageSize) {
        stopPolling()
        // 终态回调同步列表：此刻表格里的状态还是打开日志前的旧数据。
        onFinished?.()
      }
    } catch {
      // 网络或后端异常时停止轮询，避免持续刷错误提示；用户可重新打开日志。
      stopPolling()
      // 拉取失败后状态已不可信，如实退回「未知」。宁可显示「未知」，
      // 也不要继续展示一个可能早已过期的状态（例如平台重启期间页面还在显示「执行中」）。
      if (task.value) {
        task.value.status = undefined
      }
    } finally {
      polling = false
    }
  }

  /**
   * 日志追加后滚动到最新一行。
   *
   * 必须先 nextTick：日志是响应式追加的，不等 DOM 更新完就取 scrollHeight，
   * 量到的还是追加前的高度，滚动会慢一行。
   */
  async function scrollToBottom() {
    await nextTick()
    const body = bodyRef.value
    if (body) {
      body.scrollTop = body.scrollHeight
    }
  }

  /**
   * 下载当前任务的完整执行日志。
   *
   * 除终态任务外，运行中也可以下载：导出的是服务端截至此刻的全量日志，
   * 比页面已渲染的部分更完整（页面按 200 条一页增量拉取，快照可能落后几秒）。
   */
  async function download() {
    const current = task.value
    if (!current?.id) {
      ElMessage.warning('该记录未关联异步任务，无法下载日志')
      return
    }
    downloading.value = true
    try {
      const response = await exportTaskLog(current.id)
      // 后端异常走全局异常处理器返回 JSON，而 blob 请求在 request.ts 里不校验业务码，
      // 必须在这里读出来提示，否则用户会存下一个内容是报错信息的「日志文件」。
      if (String(response.headers['content-type'] || '').includes('application/json')) {
        ElMessage.error(await readBlobErrorMessage(response.data))
        return
      }
      saveBlob(response.data, resolveFileName(response.headers['content-disposition'], current.taskNo))
    } catch {
      // 网络异常已由 request.ts 统一提示，这里不再重复弹窗
    } finally {
      downloading.value = false
    }
  }

  // 页面被 keep-alive 缓存时组件不会卸载，但路由切走仍可能触发卸载，这里统一兜底。
  onUnmounted(stopPolling)

  return {
    bodyRef,
    logs,
    task,
    running,
    downloading,
    progressStatus,
    start,
    reset,
    stop: stopPolling,
    download
  }
}

/**
 * 裁剪日志时间，控制台只显示到秒。
 *
 * 5 处日志控制台都要显示时间，格式必须一致，因此实现只留这一份，页面不再各抄一遍。
 *
 * @param createTime 后端返回的日志创建时间，形如 2026-09-23T01:44:56.608429
 * @returns 形如 01:44:56 的时间片段；格式不符合预期时原样返回
 */
export function formatLogTime(createTime?: string): string {
  if (!createTime) return ''
  const timePart = createTime.split('T')[1]
  return timePart ? timePart.slice(0, 8) : createTime
}

/**
 * 从 Content-Disposition 响应头解析下载文件名。
 *
 * 后端的文件名由服务端按任务编号生成，前端不另拼一套规则。中文文件名走 RFC 5987 的
 * `filename*=UTF-8''<百分号编码>`，优先取它；取不到时回落到普通 filename；
 * 响应头整体缺失（例如被反向代理改写）时用任务编号兜底，保证下载的文件仍能认出是哪条任务。
 *
 * @param disposition 响应头原始值，类型未知需运行时判断
 * @param taskNo 任务编号，作为最终兜底
 * @returns 可直接用于 a[download] 的文件名
 */
function resolveFileName(disposition: unknown, taskNo?: string): string {
  const header = typeof disposition === 'string' ? disposition : ''
  const encoded = /filename\*=UTF-8''([^;]+)/i.exec(header)
  if (encoded) {
    try {
      return decodeURIComponent(encoded[1])
    } catch {
      // 编码异常时继续尝试普通 filename，不因为一个坏头就让下载失败
    }
  }
  const plain = /filename="?([^";]+)"?/i.exec(header)
  if (plain) return plain[1]
  return `${taskNo || 'task'}-执行日志.txt`
}

/**
 * 读取后端 JSON 错误体里的提示信息。
 *
 * @param blob 出错响应被 axios 按 blob 收下的正文
 * @returns 后端 message；解析失败时返回通用文案
 */
async function readBlobErrorMessage(blob: Blob): Promise<string> {
  try {
    const parsed = JSON.parse(await blob.text())
    return parsed?.message || '导出执行日志失败'
  } catch {
    return '导出执行日志失败'
  }
}

/**
 * 触发浏览器下载。
 *
 * @param blob 附件正文
 * @param fileName 下载文件名
 */
function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // 立即回收会让部分浏览器来不及取到内容，推迟到下一轮事件循环再释放。
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
