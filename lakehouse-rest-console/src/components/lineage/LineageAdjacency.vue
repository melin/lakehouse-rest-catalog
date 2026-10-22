<template>
  <div ref="host" class="la-host">
    <el-table-v2
      :columns="columns"
      :data="rows"
      :width="width"
      :height="420"
      :row-height="40"
      :header-height="38"
      :row-event-handlers="{ onClick: onRowClick }"
      fixed
    />
  </div>
</template>

<script setup>
/**
 * 邻接表：与图**同一份数据**、零请求（§9.1）。
 *
 * <p>它有两个身份，缺一不可：节点超过 500 时的自动降级落点，以及**无障碍路径**——
 * Canvas 对屏幕阅读器是空白，而这里是真的 DOM，可 Tab、可复制、可被读屏软件读出
 * 「这张表的上游是谁」（§9.6）。
 *
 * <p>用 `el-table-v2` 而不是 `el-table`：后者会为每一行生成完整的 DOM，5000 行时
 * 打开页签要卡两秒；前者自带虚拟滚动，且不需要新依赖。
 */
import { computed, h, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  /** 过滤之后的图。 */
  graph: { type: Object, required: true },
  /** 当前根：表格里高亮它，避免「一屏都是表名，不知道自己在看谁」。 */
  root: { type: String, default: '' },
})

const emit = defineEmits(['select'])

const host = ref(null)
const width = ref(760)
let observer = null

const rows = computed(() => props.graph.nodes
  .map((node) => ({
    ref: node.ref,
    name: node.ref,
    kind: node.kind,
    format: node.format || '—',
    upstream: (props.graph.incoming.get(node.ref) || []).length,
    downstream: (props.graph.outgoing.get(node.ref) || []).length,
    more: node.more,
    stale: node.stale,
    lastSeen: Math.max(0, ...(props.graph.incoming.get(node.ref) || []).map((edge) => edge.lastSeen)),
  }))
  .sort((a, b) => b.upstream + b.downstream - (a.upstream + a.downstream) || a.ref.localeCompare(b.ref)))

const columns = [
  {
    key: 'name',
    dataKey: 'name',
    title: '表',
    width: 420,
    cellRenderer: ({ rowData }) => h('div', { class: 'la-name' }, [
      // 根用左侧色条标出来：这张表是「我现在看的是谁」
      h('span', { class: rowData.ref === props.root ? 'la-root' : '' }, rowData.ref),
      rowData.stale ? h('span', { class: 'la-stale' }, ' 已删除') : null,
      rowData.more ? h('span', { class: 'la-more', title: '还有未展开的邻居' }, ' ›') : null,
    ]),
  },
  {
    key: 'kind',
    dataKey: 'kind',
    title: '类型',
    width: 110,
    cellRenderer: ({ rowData }) => h('span',
      // 注意 props 必须是对象：把 class 字符串直接放进 props 位（h('span', 'la-dim', ...)）
      // 会把字符串原样写进 vnode.ref，生产构建的 setRef 对它解构时直接崩
      // （Cannot read properties of undefined (reading 'refs')，且图整个渲染不出来）。
      { class: rowData.kind === 'EXTERNAL' ? 'la-dim' : '' },
      rowData.kind === 'EXTERNAL' ? '外部（不可点开）' : '目录表'),
  },
  { key: 'upstream', dataKey: 'upstream', title: '上游', width: 80, align: 'right' },
  { key: 'downstream', dataKey: 'downstream', title: '下游', width: 80, align: 'right' },
]

function onRowClick({ row }) {
  if (row.kind === 'EXTERNAL') return
  emit('select', row.ref)
}

onMounted(() => {
  observer = new ResizeObserver(() => {
    if (host.value) width.value = Math.max(520, Math.round(host.value.clientWidth))
  })
  observer.observe(host.value)
  width.value = Math.max(520, Math.round(host.value.clientWidth))
})

onBeforeUnmount(() => observer?.disconnect())
</script>

<style scoped>
.la-host {
  width: 100%;
}

.la-name {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.la-root {
  font-weight: 600;
  border-left: 3px solid var(--pc-accent);
  padding-left: 6px;
}

.la-stale {
  color: var(--pc-text-dim);
  font-size: 11px;
}

.la-more {
  color: var(--pc-accent);
  font-weight: 600;
}

.la-dim {
  color: var(--pc-text-dim);
}
</style>
