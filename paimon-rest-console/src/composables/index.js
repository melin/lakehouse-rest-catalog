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
 * 本地分页：服务端一次给一批，翻页在浏览器里做。
 *
 * <p><b>为什么不服务端分页。</b>列目录的端点按目录列举，本来就要把这一层读完才知道
 * 总数与排序，再切一次页等于让服务端对同一次列举多走一遍。这批数据有上限
 * （`max-entries`，默认 1000），在浏览器里切片足够了；真正用来缩范围的是
 * **名称前缀过滤**，而那是唯一需要走服务端的东西（见 `?prefix=`）。
 *
 * @param {import('vue').Ref<Array>} entries 当前这批条目
 * @returns `page` / `pageSize`（双向绑定给 el-pagination）、`paged`（当前页的切片）、
 *          `total`（这批的条数，即分页总数）
 */
export function useLocalPaging(entries) {
  const page = ref(1)
  const pageSize = ref(10)
  const total = computed(() => entries.value.length)
  const paged = computed(() => {
    const start = (page.value - 1) * pageSize.value
    return entries.value.slice(start, start + pageSize.value)
  })

  // 换目录或换过滤条件后回到第一页。判据是「这批数据被整体替换」而不是「条数变了」：
  // 后者会漏掉「新的这一批恰好也是 20 条」的情形，那时页码留在第 2 页，
  // 界面表现是「明明有数据却停在空页上」。
  watch(entries, () => {
    page.value = 1
  })

  return { page, pageSize, paged, total }
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
