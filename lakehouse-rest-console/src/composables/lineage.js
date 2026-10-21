import { computed, reactive, ref } from 'vue'

import { describeError } from '@/api/client.js'
import {
  applyFilters,
  decode,
  defaultFilters,
  emptyGraph,
  mergeGraph,
} from '@/utils/lineageGraph.js'

/**
 * 血缘页的状态编排：数据、展开、过滤、视图偏好（§9.13）。
 *
 * <p><b>数据与视图状态分离。</b>这里的 {@code graph} 是不可变的（展开与过滤都生成新对象），
 * 而 pan / zoom / hover 是 `LineageGraph` 的私有可变态。两者任一变化都会让渲染置脏，
 * 但只有这里的东西会影响「画什么」。
 *
 * <p><b>展开是一次增量合并，不是重查。</b>`expand(ref)` 只拉这个节点的 1 跳，然后并进现有图；
 * 已放置节点的坐标由布局层锁定（§9.4(1)）。这样首屏规模永远是「根的邻居数」，
 * 而不是「一张 DWS 表 2 跳的子图」（常见 200–800 个节点）。
 *
 * <p><b>展开期间拒绝第二次展开。</b>不是排队——排队会让图在用户眼前连续跳两次，
 * 第二次跳完他找不回刚才看的那个节点。
 */

/** 视图偏好的本地键。节点超过 500 时无论偏好都落表格（规模规则优先于偏好，§9.1）。 */
const VIEW_KEY = 'lakehouse.console.lineage.view'

/** 前端自动降级为邻接表的阈值（§9.1）。 */
export const GRAPH_NODE_LIMIT = 500

function readView() {
  const stored = localStorage.getItem(VIEW_KEY)
  return stored === 'table' ? 'table' : 'graph'
}

/**
 * @param {(params: {ref: string, depth: number}) => Promise<object>} loader
 *        取图数据；由面板注入（这样这个模块不认识 HTTP）
 * @param {object} [options]
 * @param {number} [options.maxNodes=300] 单次图查询的节点预算（§8.6 的默认值）
 */
export function useLineageView(loader, options = {}) {
  const maxNodes = options.maxNodes ?? 300

  const graph = ref(emptyGraph())
  const loading = ref(false)
  const error = ref('')
  const expanding = ref('')
  const notice = ref('')
  const filters = reactive(defaultFilters())
  const expandedLayers = ref(new Set())
  const view = ref(readView())
  /** 当前图的根：展开 / 换根之后它跟着变，URL 与面包屑都读它。 */
  const root = ref('')

  let sequence = 0
  /** 换根之后旧的展开响应必须作废，否则会把上一张表的邻居并进这一张。 */
  let epoch = 0

  /**
   * 拉一次图（默认 1 跳）。
   *
   * @param {string} ref 图的根
   * @param {object} [options]
   * @param {number} [options.depth=1] 跳数；首屏永远是 1，深图只能靠展开
   */
  async function load(ref, { depth = 1 } = {}) {
    const current = ++sequence
    const currentEpoch = ++epoch
    loading.value = true
    error.value = ''
    notice.value = ''
    expanding.value = ''
    try {
      const payload = await loader({ ref, depth, maxNodes })
      if (current !== sequence) {
        return null
      }
      graph.value = decode(payload)
      root.value = graph.value.root || ref
      expandedLayers.value = new Set()
      return graph.value
    } catch (cause) {
      if (current === sequence) {
        error.value = describeError(cause)
        graph.value = emptyGraph(ref)
        root.value = ref
      }
      return null
    } finally {
      if (current === sequence) {
        loading.value = false
      }
    }
  }

  /** 展开一个节点的下一跳。已合并过的节点仍然会再请求一次——它可能有了新的上游。 */
  async function expand(ref) {
    if (!ref) return null
    if (expanding.value) {
      notice.value = `正在展开 ${shortName(expanding.value)}，请稍候`
      return null
    }
    const currentEpoch = epoch
    expanding.value = ref
    notice.value = ''
    try {
      const payload = await loader({ ref, depth: 1, maxNodes })
      if (currentEpoch !== epoch) {
        // 期间换了根：这条响应属于上一张图，丢掉
        return null
      }
      graph.value = mergeGraph(graph.value, decode(payload))
      return graph.value
    } catch (cause) {
      notice.value = describeError(cause)
      return null
    } finally {
      expanding.value = ''
    }
  }

  /** 点开某一层的「+N」：该层此后不再折叠。 */
  function unfoldLayer(rank) {
    const next = new Set(expandedLayers.value)
    next.add(rank)
    expandedLayers.value = next
  }

  function setView(mode) {
    view.value = mode === 'table' ? 'table' : 'graph'
    localStorage.setItem(VIEW_KEY, view.value)
  }

  /** 重置过滤（「被过滤空了」那条空态上的动作）。 */
  function clearFilters() {
    Object.assign(filters, defaultFilters())
  }

  const filtered = computed(() => applyFilters(graph.value, filters))

  const nodeCount = computed(() => filtered.value.graph.nodes.length)
  const budget = computed(() => graph.value.budget)

  return {
    graph,
    root,
    loading,
    error,
    expanding,
    notice,
    filters,
    view,
    filtered,
    nodeCount,
    budget,
    expandedLayers,
    load,
    expand,
    unfoldLayer,
    setView,
    clearFilters,
  }
}

/** 抽屉与提示里的短名：末段，够用且不会把一行撑开。 */
export function shortName(ref) {
  if (!ref) return ''
  const parts = ref.split('.')
  return parts[parts.length - 1]
}
