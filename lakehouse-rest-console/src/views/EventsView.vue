<template>
  <div class="pc-page">
    <PageHeader
      title="事件"
      subtitle="目录的事件流：表创建/删除/重命名、提交、授权变更、优化策略变更与任务终态都记进同一张 audit_event 表，Paimon 与 Iceberg 两种表格式在同一个流里。这里的「刷新」是带游标的增量拉取，只取上次之后新发生的事件。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="refresh">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" :loading="loading" @click="resetCursor">
          <el-icon><VideoPlay /></el-icon>
          <span class="pc-btn-text">重置游标</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">事件（{{ rows.length }}）</span>
        <el-select v-model="filter.catalog" size="small" class="pc-evt-filter" placeholder="Catalog">
          <el-option label="全部 Catalog" value="" />
          <el-option
            v-for="catalog in session.state.catalogs"
            :key="catalog.name"
            :label="catalog.name"
            :value="catalog.name"
          />
        </el-select>
        <el-select v-model="filter.type" size="small" class="pc-evt-filter" placeholder="事件类型">
          <el-option label="全部类型" value="" />
          <el-option v-for="item in TYPES" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <TipNote>
          <b>增量契约</b>（docs/event-framework.md §4）：首次进入显示当前最新的一页，
          游标落在「现在」；之后每次刷新只取比游标新的事件，新事件插到列表最前面。
          这与其它页面的「全量重拉」不同——事件页要的是「接下来发生了什么」，
          服务端不提供往回翻页；要看更早的历史，点「重置游标」重新取最新一页
          （窗口是最近 {{ LIMIT }} 条，不是全部历史）。
          <br /><br />
          「目录」筛选在服务端做（查询参数），「事件类型」筛选在浏览器里做——
          事件流本身只有一个 type 字段，不值得为它再发一次请求。
        </TipNote>
        <span class="pc-spacer" />
        <span class="pc-dim">游标 #{{ cursor === null ? '—' : cursor }}</span>
      </div>

      <ResourceState
        :loading="loading"
        :error="error"
        :empty="loaded && !filteredRows.length"
        empty-text="窗口内没有事件。做一次建表、授权或等一个优化任务跑完，这里就会出现记录。"
      >
        <el-table :data="pagedRows" size="small" row-key="id">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="pc-evt-detail">
                <dl class="pc-kv">
                  <dt>事件 ID</dt>
                  <dd><CopyText :text="row.eventId" empty-text="—" /></dd>
                  <dt>请求 ID</dt>
                  <dd>
                    <!-- 同一次 API 调用（同一个 X-Request-Id）产生的所有事件共享它，
                         排查「这次调用到底动了什么」时按它串起来 -->
                    <CopyText :text="row.requestId" empty-text="—" />
                  </dd>
                  <dt>类型</dt>
                  <dd><span class="pc-mono">{{ row.type }}</span></dd>
                </dl>
                <SectionTitle title="属性" />
                <JsonBlock :value="row.attributes" />
              </div>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">
              <TimeText :millis="row.occurredAt" />
            </template>
          </el-table-column>
          <el-table-column label="事件" width="170">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="typeOf(row.type).tag">
                {{ typeOf(row.type).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="目录" width="140">
            <template #default="{ row }">
              <RouterLink v-if="row.catalog" class="pc-link" :to="{ name: 'browse', query: { catalog: row.catalog } }">
                {{ row.catalog }}
              </RouterLink>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="目标" min-width="240">
            <template #default="{ row }">
              <span class="pc-dim">{{ targetLabel(row.targetType) }}</span>
              <span v-if="row.target" class="pc-evt-target pc-mono pc-break">{{ row.target }}</span>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="主体" width="150">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.principal || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="属性" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">
              <span v-if="attrSummary(row)" class="pc-mono pc-dim">{{ attrSummary(row) }}</span>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <!-- 首次加载时列表还没数据，ResourceState 走 loading 分支会照常渲染表格，
               这里把 Element 自带的空文案顶掉，免得它在遮罩下闪一下 -->
          <template #empty>
            <span />
          </template>
        </el-table>

        <el-pagination
          v-if="filteredRows.length > pageSize"
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="filteredRows.length"
          layout="total, sizes, prev, pager, next"
          size="small"
          class="pc-pager"
        />
      </ResourceState>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

import { consoleApi } from '@/api/console.js'
import { describeError } from '@/api/client.js'
import CopyText from '@/components/CopyText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { notifyError, notifySuccess, onConsoleRefresh, useLocalPaging } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * 目录事件页：事件流的「看」入口。
 *
 * <p><b>为什么刷新不是全量重拉。</b>事件是只增不改的事实（append-only），
 * 增量拉取（`?since=游标`）既省流量，也不会在全量重拉的间隙里漏掉
 * 「拉取瞬间还在写入」的事件——漏了就再也补不回来，而增量流的游标
 * 保证每个事件至少被看到一次。刷新把新事件插到列表最前面（显示是新在前），
 * 列表封顶 {@link MAX_ROWS} 条，超出从尾部裁掉——要回看更早的历史，
 * 「重置游标」重新取最新一页，或者直接查库。
 *
 * <p><b>为什么 catalog 换挡要重置游标。</b>游标是全局自增 id，不带目录语义：
 * 换了筛选目录还沿用旧游标是对的（服务端会按 catalog 过滤增量），但用户
 * 换目录时想看到的是「这个目录现在最新的一页」，沿用游标只会得到
 * 「上次之后这个目录发生的事」——两者相差的正是窗口里那一页。
 */
const LIMIT = 200

/** 显示列表的封顶条数：增量一直插到最前面，不裁的话无限刷新会无限涨。 */
const MAX_ROWS = 1000

/** 七类事件的展示元数据。服务端新增类型时这里是空档——表格仍会如实显示 wire 值。 */
const TYPES = [
  { value: 'table.created', label: '表创建', tag: 'success' },
  { value: 'table.dropped', label: '表删除', tag: 'danger' },
  { value: 'table.renamed', label: '表重命名', tag: 'warning' },
  { value: 'table.committed', label: '表提交', tag: 'primary' },
  { value: 'grant.changed', label: '授权变更', tag: 'warning' },
  { value: 'optimization.policy_changed', label: '优化策略变更', tag: 'info' },
  { value: 'optimization.job_finished', label: '优化任务终态', tag: 'info' },
]

/** 目标类型的显示名。与 `CatalogEvent` 的 TARGET_* 常量对应（键带引号，verify-console 逐字比对）。 */
const TARGET_LABELS = {
  'TABLE': '表',
  'NAMESPACE': 'Namespace',
  'CATALOG': 'Catalog',
  'GRANT': '授权',
  'JOB': '任务',
}

const loading = ref(false)
const error = ref('')
/** 是否完成过至少一次成功加载：空态提示只在这个前提下出现，否则和加载失败分不开。 */
const loaded = ref(false)
/** 显示顺序：新在前。事件按 id 升序到达，进来先反转。 */
const rows = ref([])
/** 增量游标（audit_event.id）。null 表示还没建立游标——下一次拉取按「首次」走。 */
const cursor = ref(null)

const filter = reactive({ catalog: '', type: '' })

const filteredRows = computed(() => rows.value.filter((event) => {
  if (filter.type && event.type !== filter.type) return false
  return true
}))

const { page, pageSize, paged: pagedRows } = useLocalPaging(filteredRows)

function typeOf(value) {
  return TYPES.find((item) => item.value === value) || { label: value, tag: 'info' }
}

function targetLabel(value) {
  return TARGET_LABELS[value] || value || ''
}

/** 属性压成一行 `key=value`：表格里扫一眼用，展开行里才有完整 JSON。 */
function attrSummary(event) {
  const entries = Object.entries(event.attributes || {})
  if (!entries.length) return ''
  return entries
    .map(([key, value]) => `${key}=${typeof value === 'object' ? JSON.stringify(value) : value}`)
    .join('  ')
}

/** 首次（或重置后）拉取：不带 since，取当前最新的一页，游标落在「现在」。 */
async function initialLoad() {
  loading.value = true
  error.value = ''
  try {
    const payload = await consoleApi.listEvents({ catalog: filter.catalog, limit: LIMIT })
    rows.value = [...payload.events].reverse()
    cursor.value = payload.nextCursor
    loaded.value = true
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

/**
 * 刷新：带游标增量拉，hasMore 为真就继续翻（目录过滤下某一页可能刚好拉满、
 * 后面还有同目录的事件），封顶十页防失控。成功只在新事件确实出现时提示——
 * 「没有新事件」是常态，弹一次提示就成噪音了。
 */
async function refresh() {
  if (cursor.value === null) {
    return initialLoad()
  }
  loading.value = true
  try {
    let since = cursor.value
    let added = []
    for (let round = 0; round < 10; round += 1) {
      const payload = await consoleApi.listEvents({ catalog: filter.catalog, since, limit: LIMIT })
      added = added.concat(payload.events)
      since = payload.nextCursor
      if (!payload.hasMore) break
    }
    cursor.value = since
    if (added.length) {
      rows.value = [...added].reverse().concat(rows.value).slice(0, MAX_ROWS)
      notifySuccess(`新增 ${added.length} 条事件`)
    }
  } catch (cause) {
    // 刷新失败不清空已有列表：旧数据还在，只是拿不到新增——报出来让人自己决定重试
    notifyError(cause)
  } finally {
    loading.value = false
  }
}

/** 重置游标：清掉列表与游标，重新取最新一页。不是「回放全部历史」。 */
async function resetCursor() {
  rows.value = []
  cursor.value = null
  await initialLoad()
}

// catalog 是服务端过滤条件，换挡必须重置游标（见组件注释）；类型过滤是本地的，不动流。
// 换挡重拉走 initialLoad 而不是 resetCursor：换目录要的是「这个目录现在最新的一页」，
// 不是清屏等结果。分页归位交给 useLocalPaging（它监听数据整体替换）。
watch(() => filter.catalog, () => {
  initialLoad()
})

// 顶栏的「刷新」也走增量拉取：事件页的刷新语义就是「把新事件补进来」，
// 与页头那个刷新按钮是同一个动作
onConsoleRefresh(() => refresh())

// 进这一页时确保 catalog 列表已加载：过滤器里的 Catalog 候选来自它
if (!session.state.catalogsLoaded && !session.state.loadingCatalogs) {
  session.loadCatalogs().catch(() => {})
}

initialLoad()
</script>

<style scoped>
/* 过滤器宽度：两个下拉并排，放得下「全部 Catalog」与最长的类型名 */
.pc-evt-filter {
  width: 150px;
}

/* 目标列：类型标签与定位符之间留一道缝（「表 evt_demo.orders」读起来才是两个词） */
.pc-evt-target {
  margin-left: 6px;
}

.pc-evt-detail {
  padding: 8px 16px 14px;
}

.pc-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
