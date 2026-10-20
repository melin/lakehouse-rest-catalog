<template>
  <div class="pc-sto">
    <div class="pc-tools-row">
      <el-tag size="small" effect="plain">{{ format === 'ICEBERG' ? 'Iceberg 表' : 'Paimon 表' }}</el-tag>
      <span class="pc-dim pc-sto-source">{{ sourceNote }}</span>
      <span class="pc-spacer" />
      <el-select
        v-model="database"
        size="small"
        clearable
        filterable
        placeholder="按库过滤"
        class="pc-sto-db"
      >
        <el-option v-for="name in databases" :key="name" :label="name" :value="name" />
      </el-select>
      <!-- Format / Object / Lance 表没有快照，混进统计里只会变成一列「无快照」；
           Paimon catalog 才有这几种表，所以开关只在这一侧出现。 -->
      <el-checkbox v-if="format === 'PAIMON'" v-model="onlyPaimon" size="small">只统计 Paimon 表</el-checkbox>
      <span v-if="loading" class="pc-dim pc-mono pc-sto-progress">
        已统计 {{ progress.done }} / {{ progress.total || '?' }}
      </span>
      <el-button size="small" :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span class="pc-btn-text">重新统计</span>
      </el-button>
    </div>

    <el-alert v-if="state.truncated" type="warning" :closable="false" show-icon class="pc-hint">
      <template #title>只统计了前 {{ state.scanned }} 张表（这个 catalog 的表比这更多）</template>
      <div>
        逐表取统计是「一张表一次请求」，不设上限会让打开这一页变成对上百分接口的一次扫描。
        超过 {{ MAX_TABLES }} 张时按库名与表名的稳定顺序取前 {{ MAX_TABLES }} 张——列表阶段数到第
        {{ MAX_TABLES + 1 }} 张就停，因此这里说不出总共有多少张，也不该编一个。
        下面所有合计都是这一批的合计，不是整个 catalog 的合计。
      </div>
    </el-alert>

    <ResourceState
      :loading="loading"
      :error="error"
      :empty="!loading && !error && !state.rows.length"
      empty-text="这个 catalog 下还没有表。建表并写入之后，这里会显示文件数与存储分布。"
    >
      <div class="pc-stats">
        <div v-for="card in cards" :key="card.label" class="pc-stat">
          <div class="pc-stat-label">{{ card.label }}</div>
          <div class="pc-stat-value">{{ card.value }}</div>
          <div class="pc-stat-foot">{{ card.foot }}</div>
        </div>
      </div>

      <p v-if="summary.unknown" class="pc-dim pc-sto-gap">
        {{ summary.unknown }} 张表有快照但没带文件统计。Paimon 侧服务端已经用分区合计兜底过一次
        （<code>TableService#getTableSnapshot</code>），两者都缺才是空；Iceberg 侧是当前快照的
        <code>summary</code> 里没有 <code>total-data-files</code>（早期客户端写的元数据）。
        它们不计入平均文件大小与小文件判定——把「不知道」当 0 或当大表都会让这一页的两个比例同时失真。
      </p>

      <div class="pc-sto-grid">
        <PanelCard title="按库的存储" :hint="dbHint">
          <div class="pc-sto-bars">
            <div v-for="row in databaseRows" :key="row.db" class="pc-sto-bar">
              <div class="pc-sto-bar-head">
                <span class="pc-sto-bar-label" :title="row.db">{{ row.db }}</span>
                <span class="pc-sto-bar-value">{{ formatBytes(row.size) }}</span>
              </div>
              <div class="pc-sto-bar-track">
                <i :class="{ 'is-small': row.small > 0 }" :style="{ width: barWidth(row.size, dbMax) }" />
              </div>
              <div class="pc-sto-bar-sub">
                {{ formatNumber(row.files) }} 个文件 · {{ row.tables }} 张表
                <template v-if="row.small"> · 小文件 {{ row.small }} 张</template>
                <template v-if="row.unknown"> · 缺统计 {{ row.unknown }} 张</template>
              </div>
            </div>
          </div>
        </PanelCard>

        <PanelCard title="存储 Top 10 表" hint="按存储降序；右侧是平均文件大小，低于 100 MB 标黄">
          <div class="pc-sto-bars">
            <div v-for="row in topRows" :key="`${row.db}.${row.table}`" class="pc-sto-bar">
              <div class="pc-sto-bar-head">
                <span class="pc-sto-bar-label" :title="`${row.db}.${row.table}`">{{ row.table }}</span>
                <span class="pc-sto-bar-value">{{ formatBytes(row.size) }}</span>
              </div>
              <div class="pc-sto-bar-track">
                <i :class="{ 'is-small': row.small }" :style="{ width: barWidth(row.size, topMax) }" />
              </div>
              <div class="pc-sto-bar-sub">
                <span class="pc-mono">{{ row.db }}</span>
                · {{ formatNumber(row.files) }} 个文件
                · 平均 {{ formatBytes(row.avgSize) }}
                <el-tag v-if="row.small" size="small" type="warning" effect="plain" class="pc-sto-chip">小文件</el-tag>
              </div>
            </div>
            <div v-if="!topRows.length" class="pc-dim">还没有任何表写入了文件。</div>
          </div>
        </PanelCard>
      </div>

      <PanelCard title="表平均文件大小的分布" :hint="histogramHint">
        <div v-if="!histogram.counted" class="pc-dim pc-sto-empty">
          没有可统计的表：所有表都是空的，或者文件统计缺失。
        </div>
        <svg
          v-else
          class="pc-sto-hist"
          :viewBox="`0 0 ${HIST_WIDTH} ${HIST_HEIGHT}`"
          preserveAspectRatio="xMidYMid meet"
          role="img"
          aria-label="按表平均文件大小分桶的表的数量分布"
        >
          <!-- 刻度线：只画横线，不画竖线——竖线会和柱子抢视线 -->
          <g class="pc-sto-hist-axis">
            <line
              v-for="tick in histogram.ticks"
              :key="`t${tick.v}`"
              :x1="HIST_PAD.left"
              :x2="HIST_WIDTH - HIST_PAD.right"
              :y1="tick.y"
              :y2="tick.y"
            />
            <text
              v-for="tick in histogram.ticks"
              :key="`l${tick.v}`"
              :x="HIST_PAD.left - 8"
              :y="tick.y + 4"
              text-anchor="end"
            >{{ tick.v }}</text>
          </g>

          <!-- 100 MB 分界：左侧三个桶就是「小文件表」 -->
          <g class="pc-sto-hist-threshold">
            <line
              :x1="histogram.thresholdX"
              :x2="histogram.thresholdX"
              :y1="HIST_PAD.top - 8"
              :y2="histogram.baseline"
            />
            <text :x="histogram.thresholdX" :y="HIST_PAD.top - 12" text-anchor="middle">100 MB</text>
          </g>

          <g v-for="bar in histogram.bars" :key="bar.label">
            <rect
              :x="bar.x"
              :y="bar.y"
              :width="bar.w"
              :height="Math.max(bar.h, bar.count ? 2 : 0)"
              rx="1"
              :class="bar.small ? 'pc-sto-hist-bar is-small' : 'pc-sto-hist-bar'"
            />
            <text v-if="bar.count" :x="bar.cx" :y="bar.y - 6" text-anchor="middle" class="pc-sto-hist-count">
              {{ bar.count }}
            </text>
            <text :x="bar.cx" :y="histogram.baseline + 18" text-anchor="middle" class="pc-sto-hist-label">
              {{ bar.label }}
            </text>
          </g>
        </svg>
        <div class="pc-dim pc-sto-hist-foot">
          共 {{ histogram.counted }} 张有数据的表；
          <span class="pc-sto-legend"><i class="is-small" />低于 100 MB（小文件表）</span>
          <span class="pc-sto-legend"><i />不低于 100 MB</span>
          <template v-if="histogram.unknown">· {{ histogram.unknown }} 张缺统计，未计入</template>
        </div>
      </PanelCard>

      <PanelCard title="逐表明细" :hint="detailHint" flush>
        <template #actions>
          <el-input
            v-model="keyword"
            size="small"
            placeholder="按库名或表名过滤"
            clearable
            class="pc-sto-search"
          />
          <el-checkbox v-model="onlySmall" size="small">只看小文件表</el-checkbox>
        </template>
        <!-- 排序用 custom：数据一加分页，el-table 自带的排序就只能排当前页那 20 行——
             「点存储排序，最大的一张表不见了」这种错必须拦在 computed 里。 -->
        <el-table
          :data="pagedRows"
          size="small"
          :default-sort="sortState"
          @sort-change="onSortChange"
        >
          <el-table-column label="表" min-width="180">
            <template #default="{ row }">
              <RouterLink class="pc-link" :to="tableLink(row)">{{ row.table }}</RouterLink>
            </template>
          </el-table-column>
          <el-table-column prop="db" label="库" min-width="120" show-overflow-tooltip />
          <el-table-column label="文件数" width="100" align="right" prop="files" sortable="custom">
            <template #default="{ row }">{{ formatNumber(row.files) }}</template>
          </el-table-column>
          <el-table-column label="存储" width="110" align="right" prop="size" sortable="custom">
            <template #default="{ row }">{{ formatBytes(row.size) }}</template>
          </el-table-column>
          <el-table-column label="平均文件大小" width="130" align="right" prop="avgSize" sortable="custom">
            <template #default="{ row }">{{ formatBytes(row.avgSize) }}</template>
          </el-table-column>
          <el-table-column label="记录数" width="110" align="right" prop="records" sortable="custom">
            <template #default="{ row }">{{ formatNumber(row.records) }}</template>
          </el-table-column>
          <el-table-column label="最后写入" width="170" prop="updatedAt" sortable="custom">
            <template #default="{ row }">
              <TimeText :millis="row.updatedAt" mode="relative" />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag v-if="row.empty" size="small" effect="plain" type="info">无快照</el-tag>
              <el-tag v-else-if="row.files === null" size="small" effect="plain" type="info">缺统计</el-tag>
              <el-tag v-else-if="row.small" size="small" effect="plain" type="warning">小文件</el-tag>
              <span v-else class="pc-dim">正常</span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="detailRows.length" class="pc-sto-page">
          <el-pagination
            v-model:current-page="page"
            v-model:page-size="pageSize"
            :total="detailRows.length"
            :page-sizes="[20, 50, 100]"
            layout="total, sizes, prev, pager, next"
            size="small"
            background
          />
        </div>
      </PanelCard>
    </ResourceState>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'

import { describeError } from '@/api/client.js'
import { MAX_TABLES, loadCatalogStorage } from '@/api/storageHealth.js'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import { formatBytes, formatNumber } from '@/utils/format.js'
import {
  SMALL_FILE_THRESHOLD,
  decorate,
  fileSizeBuckets,
  groupByDatabase,
  summarize,
  topTables,
} from '@/utils/storageHealth.js'

/**
 * Catalog 详情页的「存储」页签。
 *
 * <p>指标口径来自 AWS《Monitoring Iceberg workloads》：活跃文件数、活跃存储、平均文件大小、
 * 小文件占比，加上「按库」这一级汇总。**但数据来源与那篇不同**——它让客户端跑
 * `select count(*) from t.files`（元数据表只在引擎侧存在），这里用的是目录接口里已有的统计。
 * 三处口径差异（加权平均、表级近似、空表不进平均）写在 `utils/storageHealth.js` 的文件头，
 * 页面上也留了说明，因为算错的人不会去读源码。
 *
 * <p><b>只读。</b>这一页不提供 compaction / 删快照这类动作：它们走表页的「存储优化」面板，
 * 那边有批次、并发与回滚。统计页只回答「该看哪几张表」，让它能动手会多出一条没有审计的写路径。
 */
const props = defineProps({
  catalogName: { type: String, required: true },
  /** `PAIMON` / `ICEBERG`，决定数据来源与表详情页的跳转目标。 */
  format: { type: String, default: 'PAIMON' },
})

const loading = ref(false)
const error = ref('')
const progress = ref({ done: 0, total: 0 })
const state = ref({ rows: [], total: 0, scanned: 0, truncated: false, failed: 0 })

// 库名全量列表来自采集层（列库便宜、没有早停），与 loading 状态分开存：
// 统计失败重试时下拉里已有的选项不该闪没。
const databases = ref([])

const database = ref('')
/** 只统计 Paimon 表。Format / Object / Lance 表没有快照，默认不进统计（Iceberg 侧没有这个概念）。 */
const onlyPaimon = ref(true)

const keyword = ref('')
const onlySmall = ref(false)

// ---------------------------------------------------------------- 明细分页与排序
// 数据侧已经有两个上限（MAX_TABLES 截断 + 早停），分页是**本地**的：把 200 行一次
// 渲染成 DOM 才是要避免的事，不是把请求切碎。
const page = ref(1)
const pageSize = ref(20)
const sortState = ref({ prop: 'size', order: 'descending' })

async function reload() {
  if (!props.catalogName) return
  loading.value = true
  error.value = ''
  progress.value = { done: 0, total: 0 }
  try {
    const result = await loadCatalogStorage({
      catalogName: props.catalogName,
      format: props.format,
      database: database.value,
      onlyPaimon: onlyPaimon.value,
      onProgress: (value) => { progress.value = value },
    })
    state.value = result
    databases.value = result.databases || []
  } catch (cause) {
    error.value = describeError(cause)
    state.value = { rows: [], total: 0, scanned: 0, truncated: false, failed: 0 }
  } finally {
    loading.value = false
  }
}

onMounted(reload)
// 切 catalog 时组件不会重建（同一条路由、只有参数变），所以这里要显式重来一次。
// 库名属于 catalog，先清掉——清掉本身会触发下面那个 watcher 去重新统计，别来两次。
watch(() => [props.catalogName, props.format], () => {
  if (database.value) database.value = ''
  else reload()
})
// 库与表格式开关改变**采集范围**，所以要重新统计；关键字与小文件过滤只是前端筛选。
watch([database, onlyPaimon], () => {
  page.value = 1
  reload()
})
watch([keyword, onlySmall], () => { page.value = 1 })

defineExpose({ reload })

const sourceNote = computed(() => (props.format === 'ICEBERG'
  ? '每张表读一次表元数据（loadTable），取当前快照 summary 里的文件数、大小与记录数'
  : '每张表读一次快照（/snapshot），取该快照累计的文件数、大小与记录数'))

const summary = computed(() => summarize(state.value.rows))

const cards = computed(() => {
  const item = summary.value
  const emptyFoot = item.empty ? `其中 ${formatNumber(item.empty)} 张还没有快照` : '全部表都有快照'
  return [
    {
      label: '有数据的表',
      value: `${formatNumber(item.withData)} / ${formatNumber(item.tables)}`,
      foot: emptyFoot,
    },
    {
      label: '活跃数据文件',
      value: formatNumber(item.files),
      foot: item.deleteFiles
        ? `另有 ${formatNumber(item.deleteFiles)} 个 delete 文件（Iceberg 的定位/等值删除）`
        : '最新快照引用的文件',
    },
    {
      label: '活跃存储',
      value: formatBytes(item.size),
      foot: '不含清单、元数据与孤儿文件',
    },
    {
      label: '平均文件大小',
      value: item.avgFileSize === null ? '—' : formatBytes(item.avgFileSize),
      foot: item.filesWithSize
        ? `加权：${formatBytes(item.size)} ÷ ${formatNumber(item.filesWithSize)} 个文件`
        : '没有拿到文件大小的表',
    },
    {
      label: '小文件表',
      value: item.withData ? `${(item.smallShare * 100).toFixed(1)}%` : '—',
      foot: item.withData
        ? `${item.smallTables} / ${item.withData} 张，合计 ${formatBytes(item.smallSize)}`
        : '没有可判定的表',
    },
  ]
})

const dbHint = computed(() => {
  const count = new Set(state.value.rows.map((row) => row.db)).size
  return count > databaseRows.value.length ? `按存储降序，${count} 个库里只画前 ${databaseRows.value.length} 行` : '按存储降序'
})

const databaseRows = computed(() => groupByDatabase(state.value.rows))
const dbMax = computed(() => Math.max(1, ...databaseRows.value.map((row) => row.size)))

const topRows = computed(() => topTables(decorate(state.value.rows), 10, 'size'))
const topMax = computed(() => Math.max(1, ...topRows.value.map((row) => row.size)))

function barWidth(value, max) {
  return `${Math.max(1.5, (value / max) * 100)}%`
}

// ---------------------------------------------------------------- 直方图几何
// 尺寸写死成 viewBox 再交给 CSS 缩放：这一页只有一张图，用 ResizeObserver 去量容器
// 换来的只是「文字永远 11px」这一点好处，代价是一个随窗口抖动重排的观察者。
const HIST_WIDTH = 760
const HIST_HEIGHT = 200
const HIST_PAD = { left: 38, right: 16, top: 26, bottom: 34 }

const histogram = computed(() => {
  const { buckets, counted, unknown } = fileSizeBuckets(state.value.rows)
  const innerWidth = HIST_WIDTH - HIST_PAD.left - HIST_PAD.right
  const innerHeight = HIST_HEIGHT - HIST_PAD.top - HIST_PAD.bottom
  const slot = innerWidth / buckets.length
  const barWidthPx = Math.min(64, Math.max(26, slot * 0.44))
  const peak = Math.max(1, ...buckets.map((bucket) => bucket.count))
  // 刻度取整：峰值 7 时按 1 步长会画 8 条线，按 ceil(7/4)=2 就只画 4 条。
  const step = Math.max(1, Math.ceil(peak / 4))
  const ceiling = Math.max(step, Math.ceil(peak / step) * step)
  const scale = (value) => Math.round((value / ceiling) * innerHeight)
  const baseline = HIST_PAD.top + innerHeight
  const bars = buckets.map((bucket, index) => {
    const height = scale(bucket.count)
    return {
      ...bucket,
      x: HIST_PAD.left + index * slot + (slot - barWidthPx) / 2,
      y: baseline - height,
      w: barWidthPx,
      h: height,
      cx: HIST_PAD.left + index * slot + slot / 2,
    }
  })
  const ticks = []
  for (let value = 0; value <= ceiling; value += step) {
    ticks.push({ v: value, y: baseline - scale(value) })
  }
  return {
    bars,
    ticks,
    counted,
    unknown,
    baseline,
    // 第三个桶的上界正好是 100 MB，分界线就落在这里。
    thresholdX: HIST_PAD.left + 3 * slot,
  }
})

const histogramHint = computed(() => `分桶按「表的平均文件大小」；阈值 ${formatBytes(SMALL_FILE_THRESHOLD)} 与上面小文件判定同一个来源`)

const detailHint = computed(() => {
  const failed = state.value.failed ? `，${state.value.failed} 张读取失败` : ''
  return `统计了 ${state.value.scanned} 张表的当前状态${failed}`
})

/** 排序键归一：`null`（没拿到）排最后，不参与大小比较时当 -1。 */
function numOf(key, row) {
  const value = row[key]
  return value === null || value === undefined ? -1 : value
}

function onSortChange({ prop, order }) {
  // 点第三下取消排序时回默认：按存储降序。
  sortState.value = order ? { prop, order } : { prop: 'size', order: 'descending' }
  page.value = 1
}

const detailRows = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  const { prop, order } = sortState.value
  const dir = order === 'ascending' ? 1 : -1
  return decorate(state.value.rows)
    .filter((row) => (onlySmall.value ? row.small : true))
    .filter((row) => !text || `${row.db}.${row.table}`.toLowerCase().includes(text))
    .sort((left, right) => (numOf(prop, left) - numOf(prop, right)) * dir)
})

const pagedRows = computed(() => (
  detailRows.value.slice((page.value - 1) * pageSize.value, page.value * pageSize.value)
))

function tableLink(row) {
  return props.format === 'ICEBERG'
    ? { name: 'icebergTable', params: { prefix: props.catalogName, database: row.db, table: row.table } }
    : { name: 'table', params: { prefix: props.catalogName, database: row.db, table: row.table } }
}
</script>

<style scoped>
.pc-sto {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

/* 这一页固定五张卡：全局的 auto-fit 在 1440 宽下排成 4+1，第五张孤行很扎眼 */
.pc-stats {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.pc-sto-source {
  font-size: 12.5px;
}

.pc-sto-progress {
  font-size: 12.5px;
}

.pc-sto-gap {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
}

.pc-sto-grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 14px;
}

/* 全局 `.pc-card + .pc-card` 的堆叠边距管的是纵向堆叠，会误伤并排的栅格：
   右卡被推下 16px，顶部错位、底边也不齐（栅格负责拉等高，管不了边距）。
   收回边距后两卡由栅格 stretch 等高、内容从顶部起排。 */
.pc-sto-grid > :deep(.pc-card + .pc-card) {
  margin-top: 0;
}

.pc-sto-db {
  width: 190px;
}

.pc-sto-page {
  display: flex;
  justify-content: flex-end;
  padding: 12px 16px;
  border-top: 1px solid var(--pc-border);
}

/* 条形图：标签与数值同一行，条在下——比「左标签 + 中条 + 右数值」更耐长名字。
   库名和表名都可能很长，三列布局下它们只能被截断成「ods_order_det…」。 */
.pc-sto-bars {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.pc-sto-bar-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12.5px;
}

.pc-sto-bar-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
}

.pc-sto-bar-value {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  color: var(--pc-text-dim);
}

.pc-sto-bar-track {
  height: 8px;
  margin: 4px 0 3px;
  background: var(--pc-border);
  border-radius: var(--pc-radius);
  overflow: hidden;
}

.pc-sto-bar-track > i {
  display: block;
  height: 100%;
  background: var(--pc-accent);
  border-radius: var(--pc-radius);
}

/* 小文件不是错误，是「该做 compaction」的信号，所以用警示色而不是错误色 */
.pc-sto-bar-track > i.is-small {
  background: #d97706;
}

.pc-sto-bar-sub {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--pc-text-dim);
}

.pc-sto-chip {
  margin-left: 2px;
}

.pc-sto-hist {
  display: block;
  width: 100%;
  height: auto;
}

.pc-sto-hist-axis line {
  stroke: var(--pc-border);
  stroke-width: 1;
}

.pc-sto-hist-axis text {
  fill: var(--pc-text-dim);
  font-size: 11px;
  font-variant-numeric: tabular-nums;
}

.pc-sto-hist-threshold line {
  stroke: #d97706;
  stroke-width: 1;
  stroke-dasharray: 3 3;
}

.pc-sto-hist-threshold text {
  fill: #d97706;
  font-size: 11px;
}

.pc-sto-hist-bar {
  fill: var(--pc-accent);
  opacity: 0.85;
}

.pc-sto-hist-bar.is-small {
  fill: #d97706;
  opacity: 0.9;
}

.pc-sto-hist-count {
  fill: var(--pc-text);
  font-size: 11.5px;
  font-weight: 600;
}

.pc-sto-hist-label {
  fill: var(--pc-text-dim);
  font-size: 11px;
}

.pc-sto-hist-foot {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  margin-top: 6px;
  font-size: 12px;
}

.pc-sto-legend {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.pc-sto-legend > i {
  width: 9px;
  height: 9px;
  border-radius: 1px;
  background: var(--pc-accent);
  opacity: 0.85;
}

.pc-sto-legend > i.is-small {
  background: #d97706;
}

.pc-sto-empty {
  padding: 24px 0;
  text-align: center;
}

.pc-sto-search {
  width: 200px;
}

@media (max-width: 1080px) {
  .pc-sto-grid {
    grid-template-columns: 1fr;
  }

  .pc-stats {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
