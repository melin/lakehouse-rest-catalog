<template>
  <el-drawer :model-value="open" :size="340" :with-header="false" @update:model-value="$emit('update:open', $event)">
    <div v-if="node" class="ld-body">
      <div class="ld-head">
        <span class="ld-title">{{ short }}</span>
        <el-button text size="small" @click="$emit('update:open', false)">关闭</el-button>
      </div>
      <div class="ld-ref">
        <CopyText :text="node.ref" />
        <code>{{ node.ref }}</code>
      </div>

      <el-descriptions :column="1" size="small" border>
        <el-descriptions-item label="类型">
          {{ node.kind === 'EXTERNAL' ? '外部节点（不可点开）' : '目录表' }}
        </el-descriptions-item>
        <el-descriptions-item label="格式">{{ node.format || '—' }}</el-descriptions-item>
        <el-descriptions-item label="上游">{{ upstream.length }} 张</el-descriptions-item>
        <el-descriptions-item label="下游">{{ downstream.length }} 张</el-descriptions-item>
        <el-descriptions-item label="最近写入">
          <TimeText v-if="lastSeen" :millis="lastSeen" mode="relative" />
          <span v-else class="pc-dim">—</span>
        </el-descriptions-item>
      </el-descriptions>

      <div v-if="node.stale" class="ld-stale">
        目录里已经找不到这张表（边还在）。它可能被删除或改名了。
      </div>

      <template v-if="recent.length">
        <div class="ld-section">最近三次写入</div>
        <div v-for="edge in recent" :key="`${edge.source}-${edge.target}-${edge.engine}`" class="ld-edge">
          <div class="ld-edge-line">
            <span class="ld-edge-ref">{{ edge.source === node.ref ? edge.target : edge.source }}</span>
            <span class="pc-dim">{{ edge.source === node.ref ? '下游' : '上游' }}</span>
          </div>
          <div class="ld-edge-meta">
            {{ edge.engine }} · {{ edge.writeMode }} · 跑过 {{ edge.runCount }} 次
            · <TimeText :millis="edge.lastSeen" mode="relative" />
          </div>
        </div>
      </template>

      <div class="ld-actions">
        <el-button size="small" @click="$emit('reroot', node.ref)">以它为根</el-button>
        <el-button v-if="node.kind !== 'EXTERNAL'" size="small" type="primary" @click="openDetail">
          打开表详情
        </el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * 节点抽屉：表摘要 + 最近几次写入 + 两个动作（§9.6）。
 *
 * <p><b>单击节点只开抽屉，不直接跳页。</b>图上点一下就离开是惩罚——用户点开是想
 * 「看一眼这张表是什么」，不是想放弃当前这张图。跳页放在抽屉里的按钮上。
 *
 * <p>{@code el-drawer} 是浮层：它盖住画布右侧，因此**布局按「让位后的可用宽」重算**
 * 而不是被压扁（这一条在效果图里踩过：抽屉一开，下游两层被盖住，曲线全部向左挤）。
 * 让位的宽度由面板传进来（`--ld-width`），抽屉自己不管布局。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'

import CopyText from '@/components/CopyText.vue'
import TimeText from '@/components/TimeText.vue'
import { shortName } from '@/composables/lineage.js'

const props = defineProps({
  open: { type: Boolean, default: false },
  /** 选中的 ref（不是节点对象：换图之后同一个 ref 才是同一个人）。
   *  名字不叫 `ref`：那是 Vue 的模板 ref 保留属性，写在组件上会被编译器吃掉。 */
  tableRef: { type: String, default: '' },
  graph: { type: Object, required: true },
})

const emit = defineEmits(['update:open', 'reroot', 'open-detail'])

const router = useRouter()

const node = computed(() => props.graph.byRef.get(props.tableRef) || null)
const short = computed(() => shortName(props.tableRef))
const upstream = computed(() => props.graph.incoming.get(props.tableRef) || [])
const downstream = computed(() => props.graph.outgoing.get(props.tableRef) || [])

/** 最近三次：按 lastSeen 倒序，入边与出边混在一起看（「谁在写它、它又写了谁」）。 */
const recent = computed(() => [...upstream.value, ...downstream.value]
  .sort((a, b) => b.lastSeen - a.lastSeen)
  .slice(0, 3))

const lastSeen = computed(() => Math.max(0, ...upstream.value.map((edge) => edge.lastSeen)))

/**
 * 打开表详情。
 *
 * <p>ref 是 {@code catalog.namespace.table}，而路由参数是分开的三段。多级 namespace
 * 用点号拼成的 `database` 段正好是详情页的口径（与 `/browse` 的深链接同一个写法），
 * 因此这里只做一次切分，不做路径编码上的特殊处理。
 */
function openDetail() {
  const parts = props.tableRef.split('.')
  if (parts.length < 3) return
  const [prefix, ...rest] = parts
  const table = rest.pop()
  const database = rest.join('.')
  emit('open-detail', props.tableRef)
  router.push({
    name: node.value?.format === 'ICEBERG' ? 'icebergTable' : 'table',
    params: { prefix, database, table },
  })
}
</script>

<style scoped>
.ld-body {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.ld-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.ld-title {
  font-weight: 600;
  font-size: 14px;
}

.ld-ref {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.ld-ref code {
  font-size: 11.5px;
  color: var(--pc-text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ld-stale {
  padding: 6px 8px;
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  color: var(--pc-text-dim);
  font-size: 12px;
}

.ld-section {
  color: var(--pc-text-dim);
  font-size: 12px;
}

.ld-edge {
  border-top: 1px solid var(--pc-border);
  padding-top: 6px;
}

.ld-edge-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.ld-edge-ref {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ld-edge-meta {
  color: var(--pc-text-dim);
  font-size: 11.5px;
}

.ld-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}
</style>
