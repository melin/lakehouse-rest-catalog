import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import { describeError } from '@/api/client.js'

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
  const data = ref(null)
  let sequence = 0

  async function load() {
    const current = ++sequence
    loading.value = true
    error.value = ''
    try {
      const result = await loader()
      if (current === sequence) {
        data.value = result
      }
      return result
    } catch (cause) {
      if (current === sequence) {
        error.value = describeError(cause)
      }
      return null
    } finally {
      if (current === sequence) {
        loading.value = false
      }
    }
  }

  if (immediate) {
    onMounted(load)
  }
  return { loading, error, data, load }
}

/** 订阅顶栏的刷新广播，组件卸载时自动退订。 */
export function onConsoleRefresh(handler) {
  onMounted(() => window.addEventListener(REFRESH_EVENT, handler))
  onUnmounted(() => window.removeEventListener(REFRESH_EVENT, handler))
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
  const rows = ref([])
  const total = ref(0)
  const page = ref(1)
  const size = ref(pageSize)
  let sequence = 0

  async function load() {
    const current = ++sequence
    loading.value = true
    error.value = ''
    try {
      const result = await loader({ page: page.value, pageSize: size.value })
      if (current !== sequence) {
        return null
      }
      rows.value = result?.items || []
      total.value = result?.total || 0
      // 删掉最后一页的最后一行后，页码会停在已经不存在的页上。往前退到最后一页，
      // 而不是把表格清空——清空会被读成「数据没了」，而实际上只是少了一页。
      if (!rows.value.length && total.value > 0 && page.value > 1) {
        page.value = Math.max(1, Math.ceil(total.value / size.value))
        return load()
      }
      return result
    } catch (cause) {
      if (current === sequence) {
        error.value = describeError(cause)
        rows.value = []
        total.value = 0
      }
      return null
    } finally {
      if (current === sequence) {
        loading.value = false
      }
    }
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

  return { loading, error, rows, total, page, pageSize: size, load, reload, onPageChange, onSizeChange }
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
