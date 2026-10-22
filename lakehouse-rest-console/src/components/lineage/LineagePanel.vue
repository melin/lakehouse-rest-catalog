<template>
  <div class="lp-panel">
    <!--
      工具条必须写在 ResourceState **外面**：它命中空态时只渲染 el-empty，
      默认插槽整个不渲染（docs/console.md §10，verify-console 第 17 组钉着）。
      但「还没采集到」时工具条一起隐藏——没有节点，过滤与视图切换没有对象。
    -->
    <div v-if="showToolbar" class="lp-toolbar" :class="{ 'lp-shifted': drawer.open }">
      <LineageFilterBar
        :filters="filters"
        :view="view"
        :engines="engines"
        :search="search"
        :column-mode="columnMode"
        :column-available="hasColumns && cardMode"
        :card-available="cardMode"
        :all-expanded="allRowsExpanded"
        @update:filters="patchFilters"
        @update:view="changeView"
        @update:search="search = $event"
        @update:column-mode="toggleColumnMode"
        @toggle-all-rows="toggleAllRows"
      />
      <div class="lp-actions">
        <el-button size="small" :loading="loading" @click="reload">刷新</el-button>
      </div>
    </div>

    <div v-if="notice" class="lp-notice">{{ notice }}</div>

    <!-- 规模规则优先于偏好：节点 > 500 时落表格，但给一个「仍要看图」的出口（§9.1） -->
    <el-alert
      v-if="overLimit && view === 'graph' && !forceGraph"
      class="lp-alert"
      type="info"
      :closable="false"
      show-icon
      :title="`这张图有 ${nodeCount} 个节点，已自动切到邻接表（图的上限是 500）`"
    >
      <el-button size="small" text type="primary" @click="forceGraph = true">仍要看图</el-button>
    </el-alert>
    <el-alert
      v-else-if="view === 'graph' && !forceGraph && layout.budget.truncated"
      class="lp-alert"
      type="warning"
      :closable="false"
      show-icon
      :title="`服务端按节点预算截断了这张图（返回 ${nodeCount} 个）。按需展开可以看到更多。`"
    />

    <ResourceState
      :loading="loading"
      :error="error"
      :empty="emptyKind !== ''"
      :empty-text="emptyText"
    >
      <template #actions>
        <el-button v-if="emptyKind === 'filtered'" size="small" @click="clearFilters">清除过滤</el-button>
      </template>

      <div class="lp-canvas" :class="{ 'lp-shifted': drawer.open && view === 'graph' }">
        <LineageGraph
          v-if="view === 'graph' && !tableFallback"
          ref="graphRef"
          :graph="filtered.graph"
          :layout="layout"
          :faded="filtered.faded"
          :focus-ref="focusRef"
          :cards="cardMode ? cardState.cards : null"
          :column-edges="columnMode ? cardState.edges : []"
          :selected-ref="selectedRef"
          :expanded-refs="expandedRefs"
          :degrees="degrees"
          @select="select"
          @deselect="deselect"
          @toggle-rows="toggleRows"
          @expand="expand"
          @fold="unfoldLayer"
          @reroot="reroot"
        />
        <LineageAdjacency
          v-else-if="view === 'table' || tableFallback"
          :graph="filtered.graph"
          :root="root"
          @select="select"
        />
        <LineageColumnTable
          v-else
          :groups="columnState.groups"
          :loading="columnState.loading"
          :error="columnState.error"
        />
      </div>

      <LineageLegend
        v-if="view !== 'columns'"
        :budget="layout.budget"
        :counts="nodeCount"
      />
    </ResourceState>

    <div v-if="error" class="lp-retry">
      <el-button size="small" @click="reload">重试</el-button>
    </div>

    <p class="lp-footnote">
      血缘来自执行成功的 INSERT 语句（引擎侧采集）。视图已展开为底层表，因此图上看到的是
      视图的底表，而不是视图名本身。
    </p>

    <LineageNodeDrawer
      v-model:open="drawer.open"
      :table-ref="drawer.tableRef"
      :graph="filtered.graph"
      @reroot="reroot"
    />
  </div>
</template>

<script setup>
/**
 * 血缘面板：表详情页「血缘」页签的内容（§9.11 / §9.12）。
 *
 * <p><b>挂载时只发一次请求</b>（`GET /lineage/tables?compact=1&columnCount=1&depth=1&maxNodes=300`），
 * 此后展开按需；列映射只在切到「列」页签时才发第二次。请求编排全部收敛在这里，
 * 子组件不认识 HTTP（§9.13 规则 5）。
 *
 * <p><b>三种视图共用同一份数据。</b>图与邻接表是同一次响应的两种渲染器，切换零请求、
 * 零重算；否则「切过去看一眼再切回来」要白等两次网络，用户就不会切了（§9.1）。
 *
 * <p><b>空态三分文案。</b>「还没采集到」/「被过滤空了」/「加载失败」的下一步完全不同：
 * 第一种要去跑一条 INSERT，第二种点「清除过滤」，第三种重试。混成一句「暂无数据」
 * 时，用户唯一能做的就是刷新，而其中两种刷新是没用的（§9.6）。
 */
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ResourceState from '@/components/ResourceState.vue'
import LineageAdjacency from '@/components/lineage/LineageAdjacency.vue'
import LineageColumnTable from '@/components/lineage/LineageColumnTable.vue'
import LineageFilterBar from '@/components/lineage/LineageFilterBar.vue'
import LineageGraph from '@/components/lineage/LineageGraph.vue'
import LineageLegend from '@/components/lineage/LineageLegend.vue'
import LineageNodeDrawer from '@/components/lineage/LineageNodeDrawer.vue'
import { catalogApi } from '@/api/catalog.js'
import { describeError } from '@/api/client.js'
import { lineageApi } from '@/api/lineage.js'
import { GRAPH_NODE_LIMIT, useLineageView } from '@/composables/lineage.js'
import { buildCards, decodeColumnEdges } from '@/utils/lineageColumns.js'
import { engineOptions, hasColumnInfo } from '@/utils/lineageGraph.js'
// 别名成 `computeLayout`：本组件里还要声明一个同名的计算属性（模板与子组件都叫它 `layout`），
// 直接 import 会撞名——`<script setup>` 的顶层作用域是同一个
import { layout as computeLayout } from '@/utils/lineageLayout.js'

const props = defineProps({
  /** 这张表的归一化全名：`catalog.namespace.table`（§4.1 的点拼口径）。
   *
   *  <p>名字不叫 `ref`：那是 Vue 的模板 ref 保留属性，写在组件标签上会被编译器吃掉
   *  （`<LineagePanel :ref="x" />` 拿到的是组件实例，传不进 prop）。同一个坑在
   *  `LineageNodeDrawer` 上也踩过一次。 */
  tableRef: { type: String, required: true },
})

const route = useRoute()
const router = useRouter()

/** 图查询的 loader：只有这一处拼请求参数（compact + columnCount 是图的默认契约，§8.6）。 */
async function loadGraph({ ref, depth, maxNodes }) {
  return lineageApi.tables({
    ref,
    depth,
    maxNodes,
    compact: 1,
    columnCount: 1,
    direction: 'both',
  })
}

const {
  graph, root, loading, error, expanding, notice, filters, filtered, nodeCount, budget,
  expandedLayers, load, expand, unfoldLayer, setView, clearFilters,
  view: viewPref,
} = useLineageView(loadGraph, { maxNodes: 300 })

/**
 * 本次页面的视图：`graph` / `table` / `columns`。
 *
 * <p>与 `viewPref`（持久偏好，只有 graph / table 两种）分开：
 * 「列映射」是一次性的核对动作，不该被记住——记了下次打开表详情就落在一张字段表上，
 * 而绝大多数人打开血缘是想看**关系**。初始值取偏好，之后由这个页签自己管。
 */
const view = ref(viewPref.value)
const search = ref('')
const forceGraph = ref(false)
const graphRef = ref(null)
const drawer = reactive({ open: false, tableRef: '' })
const columnState = reactive({ loading: false, error: '', groups: [], loaded: false })

/**
 * 列级连线开关（§9.5(1)）：**只决定画不画列-列连线**。
 *
 * <p>卡片（列清单 + 类型图标 + 「N列」开关）与它无关——一进来就有，关掉它只是少一层线。
 * 两种状态是同一张图的两种读法，不是两张图：形状变化会让「我刚才看到的那张表」
 * 在切换瞬间跳位，那种闪断比多看一层线更贵。
 *
 * <p>默认**开**：来看血缘的人九成是在核对「这一列是从哪来的」，默认关掉等于让每个
 * 人都先点一下。只在**小图**上可用（不超过 {@link COLUMN_MODE_LIMIT} 个节点）：宽表的
 * 卡片高度会把布局撑到不可读，这正是设计稿 §9.5 开头 90000 条边结论的另一种表述。
 * 数据走 §8.7 的批量端点——一次请求带回展示集合内的全部列边，不分表逐个拉。
 */
const COLUMN_MODE_LIMIT = 24
const columnMode = ref(true)
const cardState = reactive({ seq: 0, loading: false, error: '', edges: [], cards: new Map() })

/** 图上有卡片（列清单已拉到）。表模式的回落判据，模板里也要用。 */
const cardMode = computed(() => cardState.cards.size > 0)

/**
 * 「N列」开关已点开的卡片（§9.5(1)）：布局高度与渲染都跟着它走，所以归面板持有。
 * 默认展开根卡——打开列级模式就能直接看到根写了哪些列，不用每次先点一下。
 */
const expandedRefs = ref(new Set())

/** 全部卡片都展开（决定「展开全部列 / 收起全部列」按钮的文案与动作）。 */
const allRowsExpanded = computed(() => cardMode.value
  && [...cardState.cards.keys()].every((ref) => expandedRefs.value.has(ref)))

/** 点选高亮的表（整卡强调色）。点空白取消；不影响抽屉的开关。 */
const selectedRef = ref('')

function toggleColumnMode(value) {
  // 布局与卡片都不动，只是多一层线或少一层线；重绘由 `columnEdges` 的 watch 触发
  columnMode.value = value
  if (!value) notice.value = ''
}

/** 全量展开 / 收起列清单（§9.5(1)）：几十张卡一张张点太慢。展开后把图拉回可视区。 */
function toggleAllRows(expand) {
  expandedRefs.value = expand ? new Set(cardState.cards.keys()) : new Set()
  nextTick(() => graphRef.value?.fit())
}

/**
 * 每张表的上下游表数（§9.5(1)）：按**去重后的邻居表**算，不按边算——
 * 同一对表跑过十次的 INSERT 也只是「1 张上游表」。
 */
const degrees = computed(() => {
  const source = filtered.value.graph
  const ups = new Map()
  const downs = new Map()
  for (const edge of source.edges) {
    if (!downs.has(edge.source)) downs.set(edge.source, new Set())
    downs.get(edge.source).add(edge.target)
    if (!ups.has(edge.target)) ups.set(edge.target, new Set())
    ups.get(edge.target).add(edge.source)
  }
  const result = new Map()
  for (const node of source.nodes) {
    result.set(node.ref, {
      up: ups.get(node.ref)?.size || 0,
      down: downs.get(node.ref)?.size || 0,
    })
  }
  return result
})

/** 卡片数据拉失败时的提示文案（成功时按它清掉，不误清别的提示）。 */
const CARD_ERROR_NOTICE = '列信息没拉到，图按表模式显示（列清单与列连线都缺）'

/**
 * 拉一次列边。节点集合就是**当前展示的**节点（§8.7：图已被过滤/展开/截断过，
 * 服务端按根重算的集合与它对不上）；响应里的下标以服务端去重后的 `refs` 为准，
 * 这里把它解码回表名再交给卡片与渲染层。
 *
 * <p>**不看 `columnMode`**：卡片是图的常态，列-列连线才是开关。因此这里照常拉，
 * 只有渲染层按开关决定画不画线（`drawFrame` 的 `columnFlow`）。
 *
 * <p>列类型是第二次往返：卡片先出（无图标），随后并行拉各 ref 的表详情把类型
 * 并进卡片（`fetchColumnTypes`）。类型图标是锦上添花，不值得让首屏多等一轮。
 */
async function loadColumnEdges() {
  const nodes = filtered.value.graph.nodes
  if (!nodes.length) return
  const seq = ++cardState.seq
  cardState.loading = true
  cardState.error = ''
  try {
    const payload = await lineageApi.columnEdges({ refs: nodes.map((node) => node.ref) })
    if (seq !== cardState.seq) return
    const names = payload.refs || []
    const decoded = decodeColumnEdges(payload)
    cardState.edges = decoded.edges
      .filter((edge) => edge.s >= 0)
      .map((edge) => ({
        s: names[edge.s],
        sCol: edge.sCol,
        t: names[edge.t],
        tCol: edge.tCol,
        kind: edge.kind,
      }))
      .filter((edge) => edge.s && edge.t)
    cardState.cards = buildCards(names, decoded.edges)
    // 根卡默认展开（§9.5(1)）；根没有列边时不在卡片里，就全部收起
    expandedRefs.value = new Set(cardState.cards.has(root.value) ? [root.value] : [])
    if (notice.value === CARD_ERROR_NOTICE) notice.value = ''
    fetchColumnTypes(nodes, names, decoded.edges, seq)
  } catch (cause) {
    if (seq === cardState.seq) {
      cardState.error = describeError(cause)
      // 卡片是默认形态：拉不到就得说一声，否则用户以为这些表没有列信息
      notice.value = CARD_ERROR_NOTICE
    }
  } finally {
    if (seq === cardState.seq) cardState.loading = false
  }
}

/**
 * 并行拉各 ref 的表详情，把列类型并进卡片（类型图标的数据源）。
 *
 * <p>ref 是 `catalog.namespace.table`（§4.1 的点拼口径）；`~file:` 这类外部 ref
 * 与不在目录里的表（外部源、已注销）拉不到 schema——那一列就不画图标，
 * 不为它失败整批。
 */
async function fetchColumnTypes(nodes, names, rawEdges, seq) {
  const types = new Map()
  await Promise.all(nodes.map(async (node) => {
    const parts = node.ref.split('.')
    if (parts.length < 2 || node.kind === 'EXTERNAL') return
    const catalog = parts[0]
    const table = parts[parts.length - 1]
    const database = parts.slice(1, -1).join('.')
    try {
      const detail = await catalogApi.getTable(catalog, database, table)
      const fields = detail?.schema?.fields || []
      if (fields.length) types.set(node.ref, new Map(fields.map((field) => [field.name, field.type])))
    } catch {
      // 没有 schema 就没有类型图标；卡片其余能力不受影响
    }
  }))
  if (seq !== cardState.seq || !types.size) return
  const typesOf = (ref, colName) => types.get(ref)?.get(colName) || ''
  cardState.cards = buildCards(names, rawEdges, { typesOf })
}

/** 「N列」开关：展开/收起一张卡的滚动体。换新 Set 实例——布局与渲染靠引用变化感知。 */
function toggleRows(ref) {
  const next = new Set(expandedRefs.value)
  if (next.has(ref)) next.delete(ref)
  else next.add(ref)
  expandedRefs.value = next
}

/** 上一轮是因为节点超限被自动关掉的列级开关：图变小了要自己开回来（默认是开的）。 */
let columnSuppressed = false

/**
 * 卡片跟着「展示集合」走：图换根、展开、过滤，任何一样变了都要重拉（§8.7）。
 *
 * <p>不看 `columnMode` —— 卡片的获取与「画不画列线」是两件事。节点太多时卡片撑不下
 * （布局高度按行数算），退回纯表模式，并把列级开关一起关掉：那时候开关打开也没有线可画。
 */
watch([filtered, root], () => {
  const nodes = filtered.value.graph.nodes
  if (!nodes.length) {
    cardState.seq += 1
    cardState.cards = new Map()
    cardState.edges = []
    return
  }
  if (nodes.length > COLUMN_MODE_LIMIT) {
    cardState.seq += 1
    cardState.cards = new Map()
    cardState.edges = []
    columnSuppressed = true
    columnMode.value = false
    notice.value = `这张图有 ${nodes.length} 个节点，卡片列清单只支持不超过 ${COLUMN_MODE_LIMIT} 个节点的图`
    return
  }
  loadColumnEdges()
  if (columnSuppressed) {
    columnSuppressed = false
    columnMode.value = true
  }
})

// 重新打开列级开关而卡片还没拉到（上次失败过）时补一次。加载中不重入：
// 上面那条 watch 已经发出去的那次会带回卡片
watch(columnMode, (on) => {
  if (on && !cardState.cards.size && !cardState.loading
    && filtered.value.graph.nodes.length <= COLUMN_MODE_LIMIT) {
    loadColumnEdges()
  }
})

/**
 * URL 状态：`?tab=lineage&lview=table&lfocus=<ref>`。
 *
 * <p>只写 `replaceState`：pan / zoom 一律不进历史栈（那会让后退按钮变成「撤销一次拖拽」）。
 * `lfocus ≠ 当前表` 表示「钻进了某个上游表」，浏览器后退一步回根（§9.6 / §9.12）。
 */
function syncUrl(patch) {
  const query = { ...route.query, ...patch }
  // 清掉空值，避免 URL 里留一堆 `lfocus=` 
  for (const key of Object.keys(query)) {
    if (query[key] === '' || query[key] === null || query[key] === undefined) delete query[key]
  }
  router.replace({ query }).catch(() => null)
}

function changeView(next) {
  view.value = next
  // 列映射不进偏好：它不是「我习惯看哪种」，而是「这次要核对字段」
  if (next !== 'columns') setView(next)
  if (next === 'columns') loadColumns()
  syncUrl({ lview: next === 'graph' ? undefined : next })
}

function patchFilters(changes) {
  Object.assign(filters, changes)
}

function reload() {
  columnState.loaded = false
  load(root.value || props.tableRef)
  if (view.value === 'columns') loadColumns()
  // 卡片不用显式重拉：新图落到 `filtered` 会触发上面那条 watch
}

function select(ref) {
  // 点击即高亮（§9.5(1)）；抽屉照常打开，两者不冲突——
  // 高亮是图上的状态，抽屉是这要张表的详情
  selectedRef.value = ref
  drawer.tableRef = ref
  drawer.open = true
}

/** 点空白取消高亮。抽屉不动：关抽屉是抽屉自己的交互。 */
function deselect() {
  selectedRef.value = ''
}

function reroot(ref) {
  drawer.open = false
  load(ref)
  syncUrl({ lfocus: ref === props.tableRef ? undefined : ref })
}

async function loadColumns() {
  if (columnState.loading) return
  columnState.loading = true
  columnState.error = ''
  try {
    const payload = await lineageApi.columns({ ref: root.value || props.tableRef, direction: 'upstream' })
    columnState.groups = payload.columns || []
    columnState.loaded = true
  } catch (cause) {
    columnState.error = describeError(cause)
  } finally {
    columnState.loading = false
  }
}

const engines = computed(() => engineOptions(graph.value))
const hasColumns = computed(() => hasColumnInfo(graph.value))
const overLimit = computed(() => nodeCount.value > GRAPH_NODE_LIMIT)
const tableFallback = computed(() => view.value === 'graph' && overLimit.value && !forceGraph.value)

/**
 * 已放置节点的坐标。展开时锁住它们：新节点接在下面，已有节点不动（§9.4(1)）。
 *
 * <p>为什么它值得一个 ref 而不是每次从零算：图的空间记忆靠位置。展开一次就把整张图
 * 重排，用户刚才找到的那张表就跑到别处了——他会以为展开的是另一张表。
 */
const locked = ref(new Map())

const layoutResult = computed(() => computeLayout(filtered.value.graph, {
  root: root.value,
  expandedLayers: expandedLayers.value,
  // 卡片模式：布局按卡片高度算，旧的表级坐标锁没有意义（卡片一变高就错位）
  locked: cardMode.value ? undefined : locked.value,
  cardOf: cardMode.value ? cardState.cards : null,
  // 「N列」开关点开的卡多一块固定高度的滚动体，高度跟着展开集合走
  expandedRefs: cardMode.value ? expandedRefs.value : undefined,
}))

/**
 * 布局算完就把坐标记下来，供下一次（展开、过滤）锁定。
 *
 * <p>**必须有这个相等判断**：写回 `locked` 会让 `layoutResult` 失效，于是又算一遍、
 * 又想写回。没有判断就是一个无限重算的循环（Vue 不会报错，只会卡住主线）。
 * 收敛过程是最多两遍：第一遍用上一轮的锁，第二遍的产物与锁一致。
 * 卡片模式不记——坐标跟着卡片高度走，锁了反而把新卡钉在错位上。
 */
watch(layoutResult, (value) => {
  if (cardMode.value) return
  const previous = locked.value
  let changed = value.positions.size !== previous.size
  if (!changed) {
    for (const [ref, at] of value.positions) {
      const before = previous.get(ref)
      if (!before || before.y !== at.y) {
        changed = true
        break
      }
    }
  }
  if (!changed) return
  const next = new Map()
  for (const [ref, at] of value.positions) next.set(ref, { x: at.x, y: at.y })
  locked.value = next
})
const layout = computed(() => ({
  ...layoutResult.value,
  budget: budget.value,
}))

const focusRef = computed(() => {
  const query = search.value.trim().toLowerCase()
  if (!query) return ''
  const hit = filtered.value.graph.nodes.find((node) => node.ref.toLowerCase().includes(query))
  return hit?.ref || ''
})

const emptyKind = computed(() => {
  if (graph.value.nodes.length === 0) return 'none'
  if (filtered.value.graph.nodes.length === 0) return 'filtered'
  return ''
})

const emptyText = computed(() => (emptyKind.value === 'filtered'
  ? `本图有 ${graph.value.nodes.length} 个节点，当前过滤条件隐藏了全部`
  : '还没有血缘。执行一条 INSERT 后回到这里。'))

const showToolbar = computed(() => !(emptyKind.value === 'none' && !loading.value))

watch(() => props.tableRef, (next) => {
  // 换表：作废面板状态（列映射、卡片与抽屉），但保留 ?tab=lineage——用户可能是在连续核对多张表
  columnState.loaded = false
  columnState.groups = []
  // 列级连线回到默认（开）：卡片换表后照样会拉，开关状态没有理由跟着上一张表走
  columnMode.value = true
  columnSuppressed = false
  cardState.seq += 1
  cardState.edges = []
  cardState.cards = new Map()
  expandedRefs.value = new Set()
  selectedRef.value = ''
  drawer.open = false
  forceGraph.value = false
  search.value = ''
  // 新根是另一张图：旧坐标没有意义，留着会把新图的节点钉在旧位置上
  locked.value = new Map()
  const fromUrl = route.query.lfocus
  load(fromUrl && fromUrl !== next ? String(fromUrl) : next)
}, { immediate: true })

watch(() => route.query.lfocus, (value) => {
  if (!value || value === root.value) return
  load(String(value))
})

// 图被过滤到没有节点时，抽屉里的那张表已经不在了，关掉它（否则读的是一份旧图）
watch(() => filtered.value.graph, (value) => {
  if (drawer.open && !value.byRef.has(drawer.tableRef)) drawer.open = false
})
</script>

<style scoped>
.lp-panel {
  display: flex;
  flex-direction: column;
}

.lp-toolbar {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.lp-actions {
  margin-left: auto;
  flex: none;
}

.lp-notice {
  margin-bottom: 8px;
  color: var(--pc-text-dim);
  font-size: 12px;
}

.lp-alert {
  margin-bottom: 10px;
}

.lp-canvas {
  transition: padding-right 0.18s ease;
}

/* 抽屉是浮层，画布按「让位后的可用宽」重排（§9.11）——被压扁的图比不看更糟 */
.lp-shifted {
  padding-right: 340px;
}

.lp-retry {
  margin-top: 8px;
}

.lp-footnote {
  margin: 10px 0 0;
  color: var(--pc-text-dim);
  font-size: 11.5px;
}

@media (prefers-reduced-motion: reduce) {
  .lp-canvas {
    transition: none;
  }
}
</style>
