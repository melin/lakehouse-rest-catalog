<template>
  <div class="lc-wrap">
    <div class="lc-head">
      <span class="pc-dim">
        按目标列分组（{{ groups.length }} 列），来源表不限；直通与常量列默认展开，可一键折叠。
      </span>
      <el-button v-if="foldableCount" size="small" text @click="toggleFolded">
        {{ showFolded ? `折叠 ${foldableCount} 列直通/常量` : `展开 ${foldableCount} 列直通/常量` }}
      </el-button>
    </div>
    <ResourceState :loading="loading" :error="error" :empty="!loading && !error && rows.length === 0"
                   empty-text="这张表还没有列级映射。执行一条带列级血缘的 INSERT 后回到这里。">
      <div ref="host" class="lc-host">
        <el-table-v2
          :columns="columns"
          :data="rows"
          :width="width"
          :height="420"
          :row-height="44"
          :header-height="38"
          fixed
        />
      </div>
    </ResourceState>
  </div>
</template>

<script setup>
/**
 * 列映射表（§9.5(3)）。
 *
 * <p><b>字段列表才是主视图。</b>表与表之间永远不画列边（300 列 × 300 列的笛卡尔展开
 * 是 90000 条，语义上没有人能看），列级只在「列」这个页签里以列表形式出现。
 *
 * <p>四类列的处理不同：变换列（DERIVED / AGGREGATE）展开——那是人真正要看的
 * 「这列是谁算出来的」；`DIRECT` 同名列与常量列**默认也展开**（按用户要求改的：
 * 核对字段时逐列都在，折叠只作为可选的降噪手段留在按钮里）；`UNRESOLVED`
 * 展开并标灰——它代表「我不知道」，必须显眼，否则会被误读成「没有上游」。
 *
 * <p>不做行内展开：`el-table-v2` 的虚拟滚动建立在定高假设上，
 * 展开三行就会把后续所有行的位置算错（§9.5）。
 */
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'

import ResourceState from '@/components/ResourceState.vue'

const props = defineProps({
  /** `GET /lineage/columns` 的 `columns`。 */
  groups: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' },
})

const host = ref(null)
const width = ref(820)
/** true = 直通/常量也逐列展开（默认）；按钮切到 false 收成汇总行降噪。 */
const showFolded = ref(true)
let observer = null

const DIRECT_HINT = '同名列直通'

/** 可折叠的两类：同名直通（DIRECT 且源列名 == 目标列名）与常量。 */
function foldCategory(group) {
  const upstream = group.upstream || []
  if (group.kind === 'CONSTANT') return 'constant'
  if (group.kind === 'DIRECT' && upstream.length === 1 && upstream[0].column === group.column) return 'direct'
  return ''
}

const foldableCount = computed(() => props.groups.filter((group) => foldCategory(group)).length)

const rows = computed(() => {
  const list = []
  const folded = { direct: [], constant: [] }
  for (const group of props.groups) {
    const category = foldCategory(group)
    if (category && !showFolded.value) {
      folded[category].push(group)
      continue
    }
    list.push(toRow(group))
  }
  if (folded.direct.length) {
    list.push({
      key: 'fold:direct',
      column: `${folded.direct.length} 个同名列直通`,
      kind: 'DIRECT',
      expression: '',
      upstream: [],
      summary: true,
    })
  }
  if (folded.constant.length) {
    list.push({
      key: 'fold:constant',
      column: `${folded.constant.length} 个常量 / 静态分区列`,
      kind: 'CONSTANT',
      expression: '',
      upstream: [],
      summary: true,
    })
  }
  return list
})

function toRow(group) {
  const upstream = group.upstream || []
  return {
    key: group.column,
    column: group.column,
    kind: group.kind,
    expression: group.expression || '',
    upstream,
    summary: false,
  }
}

function toggleFolded() {
  showFolded.value = !showFolded.value
}

const KINDS = {
  DIRECT: { text: '直通', type: 'info' },
  DERIVED: { text: '派生', type: 'primary' },
  AGGREGATE: { text: '聚合', type: 'success' },
  CONSTANT: { text: '常量', type: 'info' },
  UNRESOLVED: { text: '未解析', type: 'warning' },
}

const columns = [
  {
    key: 'column',
    dataKey: 'column',
    title: '列',
    width: 220,
    cellRenderer: ({ rowData }) => h('div', { class: 'lc-col' }, [
      h('span', { class: rowData.summary ? 'lc-summary' : 'lc-name' }, rowData.column),
      rowData.summary ? null : h('span', { class: 'lc-kind' }, kindOf(rowData.kind).text),
    ]),
  },
  {
    key: 'kind',
    dataKey: 'kind',
    title: '类型',
    width: 90,
    cellRenderer: ({ rowData }) => (rowData.summary ? h('span', { class: 'pc-dim' }, '折叠')
      : h('span', { class: `lc-badge lc-badge-${kindOf(rowData.kind).type}` }, kindOf(rowData.kind).text)),
  },
  {
    key: 'upstream',
    dataKey: 'upstream',
    title: '上游（最多 2 个 + N）',
    width: 300,
    cellRenderer: ({ rowData }) => {
      const upstream = rowData.upstream || []
      if (!upstream.length) {
        return h('span', { class: 'pc-dim' }, rowData.kind === 'CONSTANT' ? '常量，无上游' : '—')
      }
      const shown = upstream.slice(0, 2).map((link, index) => h('div', { key: index, class: 'lc-up' }, [
        h('span', { class: 'lc-up-table' }, link.table),
        h('span', { class: 'lc-up-dot' }, '.'),
        h('span', null, link.column),
      ]))
      if (upstream.length > 2) {
        shown.push(h('div', { key: 'more', class: 'pc-dim' }, `+${upstream.length - 2}`))
      }
      return h('div', null, shown)
    },
  },
  {
    key: 'expression',
    dataKey: 'expression',
    title: '表达式',
    width: 260,
    cellRenderer: ({ rowData }) => h('span', {
      class: 'lc-expr',
      title: rowData.expression || '',
    }, rowData.expression || '—'),
  },
]

function kindOf(kind) {
  return KINDS[kind] || KINDS.UNRESOLVED
}

onMounted(() => {
  observer = new ResizeObserver(() => {
    if (host.value) width.value = Math.max(560, Math.round(host.value.clientWidth))
  })
  observer.observe(host.value)
  width.value = Math.max(560, Math.round(host.value.clientWidth))
})

onBeforeUnmount(() => observer?.disconnect())
</script>

<style scoped>
.lc-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
  font-size: 12px;
}

.lc-host {
  width: 100%;
}

.lc-col {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.lc-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lc-summary {
  font-size: 12px;
  color: var(--pc-text-dim);
  font-style: italic;
}

.lc-kind {
  color: var(--pc-text-dim);
  font-size: 11px;
}

.lc-badge {
  display: inline-block;
  padding: 0 6px;
  border-radius: 2px;
  font-size: 11px;
  line-height: 17px;
  border: 1px solid var(--pc-border);
}

.lc-badge-primary {
  color: var(--pc-accent);
  border-color: var(--pc-accent);
}

.lc-badge-success {
  color: #0f9d8f;
  border-color: #0f9d8f;
}

.lc-badge-warning {
  color: #b45309;
  border-color: #b45309;
}

.lc-up {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  color: var(--pc-text);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.lc-up-table {
  color: var(--pc-text-dim);
}

.lc-up-dot {
  color: var(--pc-text-dim);
}

.lc-expr {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  color: var(--pc-text-dim);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  display: block;
}
</style>
