import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { describeError } from '@/api/client.js'
import {
  autoRefreshKey,
  readPreference,
  shouldTick,
  writePreference,
} from '@/utils/autoRefresh.js'

/** 顶栏刷新按钮广播的事件名。 */
export const REFRESH_EVENT = 'console:refresh'

/**
 * 视图的加载状态封装。
 *
 * <p>用递增序号丢弃过期响应：连续切换 catalog 时，先发的请求可能后到，
 * 不做这个保护就会出现「列表和当前选中的目录对不上」这种偶发错乱，
 * 而且它只在慢网络下复现，很难查。
 */
export function useResource(loader, { immediate = true } = {}) {
  const loading = ref(false)
  const error = ref('')
  /**
   * 静默刷新（定时刷新）最近一次的失败原因。
   * 有值时表格里仍是**上一次成功**的数据——所以它是一个「数据可能旧了」的提示，
   * 不是错误态：用它去替换整张表，会让页面每 5 秒闪一次错误。
   */
  const staleError = ref('')
  const data = ref(null)
  let sequence = 0
  // 在途请求计数而不是布尔：静默刷新与手动刷新可能重叠，谁先回来都要等最后一个。
  let pending = 0

  async function fetchData({ silent = false } = {}) {
    const current = ++sequence
    pending += 1
    if (!silent) {
      loading.value = true
      error.value = ''
    }
    try {
      const result = await loader()
      if (current === sequence) {
        data.value = result
        error.value = ''
        staleError.value = ''
      }
      return result
    } catch (cause) {
      if (current === sequence) {
        if (silent) {
          staleError.value = describeError(cause)
        } else {
          error.value = describeError(cause)
        }
      }
      return null
    } finally {
      pending -= 1
      if (pending === 0) {
        loading.value = false
      }
    }
  }

  function load() {
    return fetchData()
  }

  /** 定时刷新用：不出遮罩、失败不清空、不切错误态。 */
  function silentLoad() {
    return fetchData({ silent: true })
  }

  if (immediate) {
    onMounted(load)
  }
  return { loading, error, staleError, data, load, silentLoad }
}

/** 订阅顶栏的刷新广播，组件卸载时自动退订。 */
export function onConsoleRefresh(handler) {
  onMounted(() => window.addEventListener(REFRESH_EVENT, handler))
  onUnmounted(() => window.removeEventListener(REFRESH_EVENT, handler))
}

/**
 * 定时刷新：按节拍调用 `tick`，开关与节拍由用户改、记在 localStorage。
 *
 * <p><b>默认开启、5 秒一次</b>（见 `utils/autoRefresh.js` 的说明）。`tick` 必须走
 * 静默刷新（`silentLoad`）：定时器触发的是「同一页数据的重取」，不是用户发起的
 * 一次操作，因此不该出遮罩、更不该在失败时把表格清空。
 *
 * <p>三件容易被忘掉、忘了也不报错的事，都在这里做掉：
 * ① 卸载时清定时器（否则离开页面后还在打接口）；
 * ② 后台标签页跳过（`visibilitychange`，切回前台时补一拍）；
 * ③ 上一拍没回来不发下一拍（慢网络下请求会堆叠成雪崩）。
 *
 * @param {() => Promise<unknown>|unknown} tick 一拍要做什么（通常是几个静默刷新）
 * @param {object} options
 * @param {string} options.page 偏好存储的页名（每个列表页一份，互不影响）
 * @param {() => boolean} [options.blocked] 页面自己的「现在不该刷新」判断
 * @returns 开关 `enabled`、节拍 `seconds`（秒，双向绑定给控件）、
 *          最近一次自动刷新的时刻 `lastAt`、手点一次 `tickNow`
 */
export function useAutoRefresh(tick, { page, blocked = () => false } = {}) {
  const key = autoRefreshKey(page)
  const stored = readPreference(window.localStorage, key)
  const enabled = ref(stored.enabled)
  const seconds = ref(stored.seconds)
  const lastAt = ref(0)
  let timer = null
  let inFlight = false

  function clear() {
    if (timer !== null) {
      window.clearInterval(timer)
      timer = null
    }
  }

  async function run() {
    const hidden = typeof document !== 'undefined' && document.visibilityState === 'hidden'
    if (!shouldTick({ enabled: enabled.value, hidden, inFlight, blocked: blocked() })) {
      return
    }
    inFlight = true
    try {
      await tick()
      lastAt.value = Date.now()
    } finally {
      inFlight = false
    }
  }

  function schedule() {
    clear()
    if (enabled.value) {
      timer = window.setInterval(run, seconds.value * 1000)
    }
  }

  // 改完立刻重排：不这样的话，把 5 秒改成 30 秒之后还要按旧节拍再跑一拍，
  // 看起来像「设置没生效」。
  watch(enabled, (value) => {
    writePreference(window.localStorage, key, { enabled: value, seconds: seconds.value })
    schedule()
    // 打开开关时立刻刷一次：否则用户按下开关后要盯着旧数据等满一拍
    // （最长 60 秒），而那正是「开了没反应」的样子
    if (value) {
      run()
    }
  })

  watch(seconds, (value) => {
    writePreference(window.localStorage, key, { enabled: enabled.value, seconds: value })
    schedule()
  })

  function onVisibilityChange() {
    // 回到前台补一拍：后台是不刷新的，等下一个整拍最长要 60 秒，
    // 而这段时间里页面看起来是「实时的」——那是最坏的一种旧数据。
    if (document.visibilityState === 'visible') {
      run()
    }
  }

  onMounted(() => {
    document.addEventListener('visibilitychange', onVisibilityChange)
    schedule()
  })
  onUnmounted(() => {
    clear()
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return { enabled, seconds, lastAt, tickNow: run }
}

/**
 * 远程分页：表格只持有一页，翻页 / 改页大小 / 过滤都下推服务端。
 *
 * <p><b>有列表端点的表格一律走这一套。</b>以前这里还有个通用的 {@code useLocalPaging}——
 * 服务端一次给一批、浏览器自己切片。它的问题不在实现，而在口径：页面上写的「共 N 条」是
 * **这一批**的条数，不是库里的条数；过滤与排序也只能作用于已经拿到的那一批，
 * 于是「筛出来 3 条」与「库里有 3000 条但只给了 200 条」在界面上长得一模一样。
 * 现在每条列表端点都接 {@code page}/{@code pageSize} 并回 {@code page.total}，
 * 客户端不再自己数。唯一的例外是**随详情一起下发、没有独立列表端点**的那份数据
 * （表 schema 的字段表是典型），见 {@link useEmbeddedPaging}。
 *
 * <p>拿到的一页由调用方从结果里取：服务端的响应形状各不相同（`catalogs` /
 * `principals` / `roles` / `jobs` / `events` …），所以 {@code loader} 负责把
 * 那一页与 {@code page} 元数据归一成 {@code {items, total}}，这个 composable
 * 只管页码状态与请求生命周期。
 *
 * @param {(paging: {page: number, pageSize: number}) => Promise<{items: Array, total: number}>} loader
 *        取一页；调用方在这里把 {@code page}/{@code pageSize} 塞进查询参数
 * @param {object} [options]
 * @param {number} [options.pageSize=20] 初始页大小
 * @param {boolean} [options.immediate=true] 挂载时是否立刻取第一页
 * @returns 页码状态（{@code page}/{@code pageSize} 双向绑定给 el-pagination）、
 *          本页数据 {@code rows}、总条数 {@code total}、加载与错误状态，
 *          以及 {@code load}（按当前页码重取）、{@code reload}（回第一页重取）、
 *          {@code onPageChange} / {@code onSizeChange}（直接绑到分页器事件）
 */
export function useRemotePaging(loader, { pageSize = 20, immediate = true } = {}) {
  const loading = ref(false)
  const error = ref('')
  /** 静默刷新（定时刷新）最近一次的失败原因，见 {@link useResource} 里的同名说明。 */
  const staleError = ref('')
  const rows = ref([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(pageSize)
  let sequence = 0
  let pending = 0

  /**
   * @param {object} [options]
   * @param {boolean} [options.silent] 定时刷新：不置 `loading`（表格不出遮罩、
   *        `ResourceState` 不切到加载态），失败时**保留上一页数据**只记 {@code staleError}
   */
  async function fetchPage({ silent = false } = {}) {
    const current = ++sequence
    pending += 1
    if (!silent) {
      loading.value = true
      error.value = ''
    }
    try {
      const result = await loader({ page: page.value, pageSize: size.value })
      if (current !== sequence) {
        return null
      }
      rows.value = result?.items || []
      total.value = result?.total || 0
      error.value = ''
      staleError.value = ''
      // 删掉最后一页的最后一行后，页码会停在已经不存在的页上。往前退到最后一页，
      // 而不是把表格清空——清空会被读成「数据没了」，而实际上只是少了一页。
      if (!rows.value.length && total.value > 0 && page.value > 1) {
        page.value = Math.max(1, Math.ceil(total.value / size.value))
        return fetchPage({ silent })
      }
      return result
    } catch (cause) {
      if (current === sequence) {
        if (silent) {
          // 静默刷新失败**不清空表格**：定时器每 5 秒把有数据的表换成空表，
          // 比不刷新糟得多（而且看起来像「数据被删了」）。
          staleError.value = describeError(cause)
        } else {
          error.value = describeError(cause)
          rows.value = []
          total.value = 0
        }
      }
      return null
    } finally {
      pending -= 1
      if (pending === 0) {
        loading.value = false
      }
    }
  }

  function load() {
    return fetchPage()
  }

  /** 定时刷新用：静默重取当前页（页码不动，用户翻到第 3 页就还是第 3 页）。 */
  function silentLoad() {
    return fetchPage({ silent: true })
  }

  function onPageChange(value) {
    page.value = value
    load()
  }

  function onSizeChange(value) {
    size.value = value
    page.value = 1
    load()
  }

  /** 过滤条件变了、或增删改之后：回到第一页重取（总数也变了）。 */
  function reload() {
    page.value = 1
    return load()
  }

  if (immediate) {
    onMounted(load)
  }

  return {
    loading, error, staleError, rows, total, page, pageSize: size,
    load, silentLoad, reload, onPageChange, onSizeChange,
  }
}

/**
 * 随详情一起下发的数据的分页：全站唯一还在浏览器里切片的地方。
 *
 * <p>判据是「有没有独立的列表端点」，不是「数据量大不大」。表 schema 的字段表跟着
 * `GET .../tables/{t}` 一次性回来，服务端没有 `?page=` 可给；为了一张几十行的字段表
 * 造一个列表端点，只会多一条要维护的契约。这类数据因此只做**纯切片**：不排序、
 * 不计数（总数就是数组长度），顺序完全由服务端那份结果决定。
 *
 * <p>与 {@link useRemotePaging} 的接口刻意对齐（`page` / `pageSize` / `rows` /
 * `total` / `onPageChange` / `onSizeChange`），所以同一个 {@code TablePager}
 * 既能接它也能接远程分页——页面不必关心数据是怎么来的。
 *
 * @param {import('vue').Ref<Array>} allRows 完整的行数组（通常是 computed）
 */
export function useEmbeddedPaging(allRows, { pageSize = 20 } = {}) {
  const size = ref(pageSize)
  const page = ref(1)

  const total = computed(() => allRows.value?.length || 0)

  const rows = computed(() => {
    const all = allRows.value || []
    const start = (page.value - 1) * size.value
    return all.slice(start, start + size.value)
  })

  // 过滤条件变了或详情换了，总数会缩水：停在已经不存在的页上会看到一张空表，
  // 而数据其实还在前面几页——「空」和「不在这一页」是两件事
  watch(total, (value) => {
    const last = Math.max(1, Math.ceil(value / size.value))
    if (page.value > last) {
      page.value = last
    }
  })

  function onPageChange(value) {
    page.value = value
  }

  function onSizeChange(value) {
    size.value = value
    page.value = 1
  }

  return { page, pageSize: size, rows, total, onPageChange, onSizeChange }
}

/** 请求顶栏刷新当前页面。 */
export function requestRefresh() {
  window.dispatchEvent(new Event(REFRESH_EVENT))
}

export function notifyError(cause) {
  ElMessage.error({ message: describeError(cause), duration: 5000, showClose: true })
}

export function notifySuccess(message) {
  ElMessage.success(message)
}

/**
 * 破坏性操作前的二次确认。
 *
 * <p>文案里带上资源定位信息：控制台的删除按钮密集，只写「确认删除？」的弹窗
 * 在连续操作时很容易点错对象。
 */
export async function confirmDanger({ title, target, detail, confirmText = '删除' }) {
  try {
    await ElMessageBox.confirm(
      detail ? `${target}\n\n${detail}` : String(target),
      title || '确认操作',
      {
        type: 'warning',
        confirmButtonText: confirmText,
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
        customStyle: { whiteSpace: 'pre-line' },
      },
    )
    return true
  } catch {
    return false
  }
}
