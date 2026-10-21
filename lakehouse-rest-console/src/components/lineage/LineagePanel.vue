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
        :has-columns="hasColumns"
        :search="search"
        @update:filters="patchFilters"
        @update:view="changeView"
        @update:search="search = $event"
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
          @select="select"
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
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import ResourceState from '@/components/ResourceState.vue'
import LineageAdjacency from '@/components/lineage/LineageAdjacency.vue'
import LineageColumnTable from '@/components/lineage/LineageColumnTable.vue'
import LineageFilterBar from '@/components/lineage/LineageFilterBar.vue'
import LineageGraph from '@/components/lineage/LineageGraph.vue'
import LineageLegend from '@/components/lineage/LineageLegend.vue'
import LineageNodeDrawer from '@/components/lineage/LineageNodeDrawer.vue'
import { describeError } from '@/api/client.js'
import { lineageApi } from '@/api/lineage.js'
import { GRAPH_NODE_LIMIT, useLineageView } from '@/composables/lineage.js'
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
}

function select(ref) {
  drawer.tableRef = ref
  drawer.open = true
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
  locked: locked.value,
}))

/**
 * 布局算完就把坐标记下来，供下一次（展开、过滤）锁定。
 *
 * <p>**必须有这个相等判断**：写回 `locked` 会让 `layoutResult` 失效，于是又算一遍、
 * 又想写回。没有判断就是一个无限重算的循环（Vue 不会报错，只会卡住主线）。
 * 收敛过程是最多两遍：第一遍用上一轮的锁，第二遍的产物与锁一致。
 */
watch(layoutResult, (value) => {
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
  // 换表：作废面板状态（列映射与抽屉），但保留 ?tab=lineage——用户可能是在连续核对多张表
  columnState.loaded = false
  columnState.groups = []
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
