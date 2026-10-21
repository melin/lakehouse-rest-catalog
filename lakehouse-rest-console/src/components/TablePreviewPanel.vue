<template>
  <div class="tp">
    <!-- ------------------------------- 工具行 -------------------------------->
    <div class="tp-tools">
      <el-popover
        v-if="partitionFields.length"
        placement="bottom-start"
        :width="360"
        trigger="click"
        popper-class="tp-pop"
        @show="loadPartitions"
      >
        <template #reference>
          <button type="button" class="tp-part" :class="{ 'tp-part-on': partitionLabel }">
            <span class="tp-part-label">{{ partitionLabel || '全部分区' }}</span>
            <span v-if="partition && partition.auto" class="tp-auto">自动</span>
            <el-icon class="tp-caret"><ArrowDown /></el-icon>
          </button>
        </template>

        <div class="pp">
          <div class="pp-head">
            <el-input
              v-model="partitionKeyword"
              size="small"
              placeholder="按分区值过滤"
              clearable
              @input="loadPartitions"
            />
          </div>
          <div class="pp-list">
            <div
              v-for="row in partitions"
              :key="rowKey(row)"
              class="pp-row"
              :class="{ 'pp-row-empty': !row.files }"
              @click="pickPartition(row)"
            >
              <span class="pp-value">{{ partitionText(row) }}</span>
              <span class="pp-meta">
                <template v-if="row.files">
                  {{ row.files }} 个 · {{ formatBytes(row.bytes) }}
                </template>
                <template v-else>暂无数据</template>
              </span>
            </div>
            <div v-if="!partitions.length" class="pp-none">
              {{ partitionsLoading ? '正在读取分区…' : '没有分区' }}
            </div>
          </div>
          <div class="pp-foot">
            <button type="button" class="tp-link" @click="clearPartition">全部分区</button>
            <button type="button" class="tp-link" @click="useLatest">只看最新（自动）</button>
          </div>
        </div>
      </el-popover>

      <el-select
        v-model="columnSelection"
        multiple
        collapse-tags
        collapse-tags-tooltip
        clearable
        filterable
        size="small"
        class="tp-cols"
        placeholder="全部列"
        @change="reload"
      >
        <el-option v-for="column in columnOptions" :key="column.name" :label="column.name" :value="column.name" />
      </el-select>

      <el-button size="small" :loading="loading" @click="reload">
        <el-icon><Refresh /></el-icon>
        <span class="pc-btn-text">刷新</span>
      </el-button>
    </div>

    <!-- ------------------------------- 条件条 -------------------------------->
    <div class="tp-filters">
      <span v-if="partitionLabel" class="tp-chip tp-chip-part">
        <span>{{ partitionLabel }}</span>
        <!-- × 的含义是「取消分区限制、看全部分区」，不是「删掉这条自动条件」：
             自动条件是服务端加的还是用户加的，用户不该需要知道 -->
        <button type="button" class="tp-chip-x" title="改为全部分区" @click="clearPartition">×</button>
      </span>

      <span v-for="(filter, index) in filters" :key="index" class="tp-chip">
        <span>{{ filter.column }} {{ opLabel(filter.op) }}{{ needsValue(filter.op) ? ` ${filter.value}` : '' }}</span>
        <button type="button" class="tp-chip-x" title="移除条件" @click="removeFilter(index)">×</button>
      </span>

      <div class="tp-compose">
        <el-select v-model="draft.column" size="small" placeholder="列" class="tp-w-col" filterable>
          <el-option v-for="column in filterColumns" :key="column.name" :label="column.name" :value="column.name" />
        </el-select>
        <el-select v-model="draft.op" size="small" placeholder="条件" class="tp-w-op">
          <el-option v-for="op in draftOps" :key="op" :label="opLabel(op)" :value="op" />
        </el-select>
        <el-input
          v-if="needsValue(draft.op)"
          v-model="draft.value"
          size="small"
          class="tp-w-value"
          placeholder="值"
          @keyup.enter="addFilter"
        />
        <el-button size="small" type="primary" plain :disabled="!canAddFilter" @click="addFilter">添加</el-button>
      </div>
    </div>

    <!-- ------------------------------- 提示条 -------------------------------->
    <!-- 采样提示是硬性要求：不标注的预览一定会被当成全表结论 -->
    <div v-if="sampled" class="tp-banner tp-banner-warn">
      <el-icon><WarningFilled /></el-icon>
      <span>
        只读了最近 <b>{{ scanned.filesRead }}</b> / <b>{{ scanned.filesInScope }}</b> 个文件{{
          scanned.filesInScope < scanned.filesInTable ? '（本分区）' : ''
        }}，全表 <b>{{ scanned.filesInTable }}</b> 个。下面的行不是全表样例，别据此下全表结论。
      </span>
    </div>
    <div v-if="partition && partition.fallback" class="tp-banner tp-banner-info">
      <el-icon><InfoFilled /></el-icon>
      <span>{{ partition.fallback.detail }}</span>
    </div>
    <div v-for="(warning, index) in warnings" :key="index" class="tp-banner tp-banner-info">
      <el-icon><InfoFilled /></el-icon>
      <span>{{ warning }}</span>
    </div>

    <!-- -------------------------------- 状态 --------------------------------->
    <div v-if="error" class="tp-state">
      <el-alert :title="error.message" :type="error.type" :closable="false" show-icon>
        <template #default>
          <div class="tp-state-text">{{ error.hint }}</div>
        </template>
      </el-alert>
      <el-button size="small" class="tp-state-btn" @click="reload">重试</el-button>
    </div>

    <div v-else-if="!loading && !rows.length" class="tp-state">
      <el-empty :description="emptyText" :image-size="72" />
    </div>

    <!-- -------------------------------- 数据 --------------------------------->
    <el-table
      v-else
      ref="tableRef"
      v-loading="loading && !rows.length"
      :data="rows"
      size="small"
      border
      height="440"
      class="tp-table"
    >
      <el-table-column
        v-for="column in columns"
        :key="column.name"
        :prop="column.name"
        :min-width="columnWidth(column)"
        show-overflow-tooltip
      >
        <template #header>
          <div class="tp-th">
            <span class="tp-th-name">
              <el-icon v-if="column.primaryKey" class="tp-key"><Key /></el-icon>
              {{ column.name }}
            </span>
            <span class="tp-th-type">{{ column.type }}</span>
          </div>
        </template>
        <template #default="{ row }">
          <span v-if="row[column.name] === null || row[column.name] === undefined" class="tp-null">null</span>
          <span v-else-if="typeof row[column.name] === 'object'" class="tp-json">{{ asJson(row[column.name]) }}</span>
          <span v-else>{{ row[column.name] }}</span>
        </template>
      </el-table-column>
    </el-table>

    <!-- -------------------------------- 底部 --------------------------------->
    <div v-if="rows.length || hasMore" class="tp-foot">
      <span class="tp-foot-stat">
        已加载 {{ rows.length }} 行 · 读 {{ scanned.rowsRead }} 行 ·
        {{ scanned.filesRead }}/{{ scanned.filesInScope }} 个文件（全表 {{ scanned.filesInTable }} 个）
        <template v-if="scanned.elapsedMs !== null">· {{ scanned.elapsedMs }} ms</template>
      </span>
      <el-button v-if="hasMore" size="small" :loading="loading" @click="loadMore">加载更多</el-button>
      <span v-else class="tp-foot-done">已到末尾</span>
    </div>
  </div>
</template>

<script setup>
/**
 * 表详情页的「数据预览」面板——两个表详情页（`TableView` 的 Paimon 侧、`IcebergTableView`）
 * 挂的是同一个组件，只传 `tableId` 与全量列名。
 *
 * <p><b>它不判断格式。</b>格式差异（走哪个读取器、分区怎么拼、hidden 分区怎么取）全在服务端，
 * 这里只认一个契约。这也是为什么「加载更多」只靠 `cursor`：「这一页读的是哪一段」
 * 是服务端的知识。
 *
 * <p><b>它不自己算「最新分区」。</b>服务端回什么就显示什么（`partition.label`）。
 * 前端再算一遍的后果是两处实现迟早不一致，而用户看到的是"刷新一次分区变了"。
 *
 * <p><b>三个文件数分开显示。</b>`filesRead` / `filesInScope` / `filesInTable` 说的是三件事，
 * 合并成一个分母会把「分区已经把范围缩小了」说反：`3/12` 被读成 `3/128`，
 * 用户会以为采样比实际激进得多。
 *
 * <p>请求只打 `previewApi`（预览 + 分区列表）——不拼下载端点、不直连协议面：
 * 那两条路径的判权与限额是另一套（见 `docs/table-data-preview-design.md` §10）。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { ArrowDown, InfoFilled, Key, Refresh, WarningFilled } from '@element-plus/icons-vue'
import { describeError } from '@/api/client.js'
import { previewApi } from '@/api/preview.js'

const props = defineProps({
  /** 表的内部 id（表详情响应里的 `id`） */
  tableId: { type: String, required: true },
  /** 全量列名 `[{ name, type }]`：投影多选框的选项来源（响应里只有被投影的那几列） */
  columns: { type: Array, default: () => [] },
})

/** 每页行数。上限 200 是服务端的物理约束（`skip` 的代价与页大小同阶），不从界面放开。 */
const PAGE_SIZE = 100

const OPS = {
  number: ['eq', 'ne', 'gt', 'ge', 'lt', 'le', 'isNull', 'isNotNull'],
  temporal: ['eq', 'ne', 'gt', 'ge', 'lt', 'le', 'isNull', 'isNotNull'],
  text: ['eq', 'ne', 'gt', 'ge', 'lt', 'le', 'contains', 'startsWith', 'isNull', 'isNotNull'],
  boolean: ['eq', 'isNull', 'isNotNull'],
  binary: ['isNull', 'isNotNull'],
  complex: ['isNull', 'isNotNull'],
}

const OP_LABELS = {
  eq: '=',
  ne: '≠',
  gt: '>',
  ge: '≥',
  lt: '<',
  le: '≤',
  contains: '包含',
  startsWith: '开头是',
  isNull: '为空',
  isNotNull: '不为空',
}

const MAX_FILTERS = 5

// ------------------------------------------------------------------ 状态

const rows = ref([])
const columns = ref([])
const partition = ref(null)
const scanned = reactive({
  filesRead: 0,
  filesInScope: 0,
  filesInTable: 0,
  bytesRead: 0,
  rowsRead: 0,
  elapsedMs: null,
})
const sampled = ref(false)
const warnings = ref([])
const cursor = ref(null)
const hasMore = ref(false)
const loading = ref(false)
const error = ref(null)

/** 分区模式：`auto`（服务端锁最新）/ `all`（不裁）/ `explicit`（用户选的那个） */
const partitionMode = ref('auto')
const partitionValues = ref([])
const partitionFields = ref([])
const partitions = ref([])
const partitionsLoading = ref(false)
const partitionKeyword = ref('')

const filters = ref([])
const draft = reactive({ column: '', op: '', value: '' })
const columnSelection = ref([])

const partitionLabel = computed(() => partition.value?.label || null)

/** 过滤条里可选的列：优先用响应里带类型的列，退而用父级给的全量列名。 */
const filterColumns = computed(() => {
  if (columns.value.length) {
    return columns.value
  }
  return props.columns
})

const columnOptions = computed(() => (props.columns.length ? props.columns : columns.value))

const draftColumn = computed(() => filterColumns.value.find((item) => item.name === draft.column) || null)

const draftOps = computed(() => OPS[draftColumn.value?.kind] || OPS.text)

const canAddFilter = computed(
  () =>
    !!draft.column &&
    !!draft.op &&
    filters.value.length < MAX_FILTERS &&
    (!needsValue(draft.op) || draft.value !== ''),
)

const emptyText = computed(() => {
  if (scanned.filesInTable === 0) {
    return '这张表还没有数据文件（还没有成功提交过写快照）'
  }
  if (filters.value.length) {
    return '没有匹配的行，换一个条件或清空过滤条'
  }
  if (partition.value?.resolved?.length) {
    return `${partitionLabel.value || '这个分区'} 里没有数据，换一个分区看看`
  }
  return '这张表没有数据'
})

// ------------------------------------------------------------------ 读取

function baseRequest() {
  const request = {
    limit: PAGE_SIZE,
    columns: columnSelection.value.length ? columnSelection.value : undefined,
    filters: filters.value.length ? filters.value : undefined,
    partition: {
      mode: partitionMode.value,
      values: partitionMode.value === 'explicit' ? partitionValues.value : undefined,
    },
  }
  return request
}

/** 重新取第一页：条件变了、快照变了、用户点了刷新，都走这条。 */
async function reload() {
  cursor.value = null
  hasMore.value = false
  await fetchPage(false)
}

/** 追加下一页：带游标，条件原样重发（服务端会校验没变）。 */
async function loadMore() {
  if (!hasMore.value || loading.value) {
    return
  }
  await fetchPage(true)
}

async function fetchPage(append) {
  loading.value = true
  error.value = null
  try {
    const request = baseRequest()
    if (append && cursor.value) {
      request.cursor = cursor.value
    }
    const response = await previewApi.preview(props.tableId, request)
    apply(response, append)
  } catch (failure) {
    error.value = describePreviewError(failure)
  } finally {
    loading.value = false
  }
}

function apply(response, append) {
  const next = toRecords(response.rows, response.columns)
  rows.value = append ? rows.value.concat(next) : next
  columns.value = response.columns
  partition.value = response.partition || null
  partitionFields.value = (response.partition?.resolved || []).map((value) => value.field)
  sampled.value = !!response.sampled
  warnings.value = response.warnings || []
  cursor.value = response.hasMore ? response.cursor : null
  hasMore.value = !!response.hasMore
  Object.assign(scanned, {
    filesRead: response.scanned?.filesRead ?? 0,
    filesInScope: response.scanned?.filesInScope ?? 0,
    filesInTable: response.scanned?.filesInTable ?? 0,
    bytesRead: response.scanned?.bytesRead ?? 0,
    rowsRead: response.scanned?.rowsRead ?? 0,
    elapsedMs: response.scanned?.elapsedMs ?? null,
  })
}

/**
 * 把「数组行」变成「按列名索引的对象」，好让 `el-table` 用 `prop` 取值。
 *
 * <p>服务端回的是数组（省带宽、列顺序由 `columns` 决定）；表格需要对象。
 * 转换放在这里而不是服务端，是因为列顺序属于响应契约的一部分——前端拿它当唯一真源。
 */
function toRecords(rawRows, rawColumns) {
  if (!rawRows?.length) {
    return []
  }
  return rawRows.map((values) => {
    const record = {}
    rawColumns.forEach((column, index) => {
      record[column.name] = values[index]
    })
    return record
  })
}

// ------------------------------------------------------------------ 分区

async function loadPartitions() {
  partitionsLoading.value = true
  try {
    const page = await previewApi.partitions(props.tableId, {
      page: 1,
      pageSize: 50,
      keyword: partitionKeyword.value || undefined,
    })
    partitions.value = page.partitions || []
  } catch (failure) {
    partitions.value = []
    const described = describePreviewError(failure)
    ElMessage.error(described.message)
  } finally {
    partitionsLoading.value = false
  }
}

/** 选中一个分区：切到 `explicit` 并把该分区的字段取值逐个带上。 */
function pickPartition(row) {
  const values = []
  Object.entries(row.fields || {}).forEach(([field, value]) => {
    values.push({ field, transform: 'identity', value: value === null ? '<null>' : String(value) })
  })
  if (!values.length) {
    return
  }
  partitionMode.value = 'explicit'
  partitionValues.value = values
  reload()
}

/** 「全部分区」：不做任何分区裁剪（仍受 `maxFiles` 采样约束，界面会标注）。 */
function clearPartition() {
  partitionMode.value = 'all'
  partitionValues.value = []
  reload()
}

/** 「只看最新（自动）」：把决定权交回服务端（它按文件数预算级联）。 */
function useLatest() {
  partitionMode.value = 'auto'
  partitionValues.value = []
  reload()
}

function partitionText(row) {
  return partitionFieldsOfCurrentTable()
    .map((field) => `${field} = ${row.fields?.[field] === null ? '<null>' : row.fields?.[field]}`)
    .join(' / ')
}

/**
 * 分区文本用哪几个字段：优先用分区列表每行自带的全量键（那是表的分区字段全集），
 * 退而用上次响应里 `resolved` 出现过的字段。**不排序、不取 max**——"最新"是服务端的判断，
 * 前端再算一遍就会两处不一致。
 */
function partitionFieldsOfCurrentTable() {
  const first = partitions.value[0]
  if (first?.fields) {
    return Object.keys(first.fields)
  }
  return partitionFields.value
}

// ------------------------------------------------------------------ 过滤

function needsValue(op) {
  return op !== 'isNull' && op !== 'isNotNull'
}

function opLabel(op) {
  return OP_LABELS[op] || op
}

function addFilter() {
  if (!canAddFilter.value) {
    return
  }
  filters.value = filters.value.concat({
    column: draft.column,
    op: draft.op,
    value: needsValue(draft.op) ? draft.value : undefined,
  })
  draft.value = ''
  reload()
}

function removeFilter(index) {
  filters.value = filters.value.filter((_, position) => position !== index)
  reload()
}

// 换列时把不适用的算子清掉：否则会留下「TEXT 列 + contains」这种在 BOOLEAN 列上
// 不成立的组合，点「添加」后被服务端 400，而用户看不出是自己换了列没换条件
watch(draftColumn, () => {
  if (draft.op && !draftOps.value.includes(draft.op)) {
    draft.op = draftOps.value[0] || ''
  }
})

// ------------------------------------------------------------------ 展示

function formatBytes(bytes) {
  if (!bytes) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 10 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

function columnWidth(column) {
  return column.kind === 'text' || column.kind === 'complex' ? 220 : 140
}

function asJson(value) {
  try {
    return JSON.stringify(value)
  } catch {
    return String(value)
  }
}

function rowKey(row) {
  return Object.values(row.fields || {}).join('/')
}

function rowKeyOf(row) {
  return Object.values(row).join('\u0001')
}

/**
 * 把失败翻译成「页签内的一句话 + 一句下一步」。
 *
 * <p>五类分开写而不是共用一句"加载失败"：403 要指向权限、400 要指向格式或条件、
 * 404 要指向快照被清理、409 要说明"表刚提交过"、504 要给"换小一点的范围"。
 * 混成一句的话，用户唯一能做的就是刷新，而其中至少两类刷新是没用的。
 */
function describePreviewError(failure) {
  const status = failure?.status
  if (status === 403) {
    return {
      type: 'warning',
      message: '没有读这张表数据的权限',
      hint: '预览需要 TABLE 级 SELECT。到「权限」页申请后再回来。',
    }
  }
  if (status === 404) {
    return {
      type: 'error',
      message: '表或快照不存在（快照可能已被清理）',
      hint: '切回最新快照，或到「快照」页确认这个版本还在。',
    }
  }
  if (status === 409) {
    return {
      type: 'warning',
      message: '表在预览过程中又提交过，结果已重置',
      hint: '点「重试」从第一页重新取。',
    }
  }
  if (status === 504) {
    return {
      type: 'warning',
      message: '这次预览超时了',
      hint: '换一个更小的分区，或加一个过滤条件再试。',
    }
  }
  if (status === 429) {
    return {
      type: 'warning',
      message: '正在预览中，请稍后再试',
      hint: '同一账号同时最多两个预览，等其中一个结束。',
    }
  }
  if (status === 400) {
    return {
      type: 'error',
      message: describeError(failure),
      hint: '如果是「这张表的数据格式不支持预览」，去「文件列表」页看它的物理文件格式。',
    }
  }
  return {
    type: 'error',
    message: describeError(failure) || '读不到数据',
    hint: '对象存储凭据或网络可能有问题；服务端日志里有上游原因。',
  }
}

// ------------------------------------------------------------------ 生命周期

watch(
  () => props.tableId,
  () => {
    rows.value = []
    columns.value = []
    partition.value = null
    cursor.value = null
    hasMore.value = false
    reload()
  },
)

onMounted(reload)
</script>

<style scoped>
.tp {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* ------------------------------- 工具行 -------------------------------- */
.tp-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tp-part {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 8px;
  border: 1px solid #e2e7f0;
  border-radius: 2px;
  background: #fff;
  color: #1f2937;
  font-size: 12px;
  cursor: pointer;
}

.tp-part:hover {
  border-color: #1d4ed8;
}

.tp-part-on {
  background: #eff4ff;
  border-color: #c7d7fe;
  color: #1d4ed8;
}

.tp-part-label {
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tp-auto {
  padding: 0 4px;
  border-radius: 2px;
  background: #1d4ed8;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
}

.tp-caret {
  color: #8a94a6;
}

.tp-cols {
  width: 220px;
}

/* ------------------------------- 条件条 -------------------------------- */
.tp-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.tp-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 24px;
  padding: 0 6px;
  border: 1px solid #e2e7f0;
  border-radius: 2px;
  background: #f7f9fc;
  color: #1f2937;
  font-size: 12px;
}

.tp-chip-part {
  background: #eff4ff;
  border-color: #c7d7fe;
  color: #1d4ed8;
}

.tp-chip-x {
  border: none;
  background: transparent;
  color: #8a94a6;
  cursor: pointer;
  font-size: 13px;
  line-height: 1;
  padding: 0 2px;
}

.tp-chip-x:hover {
  color: #dc2626;
}

.tp-compose {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.tp-w-col {
  width: 150px;
}

.tp-w-op {
  width: 104px;
}

.tp-w-value {
  width: 150px;
}

/* ------------------------------- 提示条 -------------------------------- */
.tp-banner {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 2px;
  font-size: 12px;
  line-height: 1.6;
}

.tp-banner-warn {
  background: #fffbeb;
  border: 1px solid #fde68a;
  color: #92400e;
}

.tp-banner-info {
  background: #f5f7fb;
  border: 1px solid #e2e7f0;
  color: #4b5563;
}

/* -------------------------------- 状态 --------------------------------- */
.tp-state {
  padding: 12px 0;
}

.tp-state-text {
  margin-top: 4px;
  font-size: 12px;
  color: #6b7280;
}

.tp-state-btn {
  margin-top: 8px;
}

/* -------------------------------- 数据 --------------------------------- */
.tp-table {
  width: 100%;
}

.tp-th {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}

.tp-th-name {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  font-weight: 600;
}

.tp-th-type {
  font-size: 10px;
  font-weight: 400;
  color: #9aa4b2;
}

.tp-key {
  color: #b45309;
  font-size: 11px;
}

.tp-null {
  color: #b0b8c4;
  font-style: italic;
}

.tp-json {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 11px;
  color: #4b5563;
}

/* -------------------------------- 底部 --------------------------------- */
.tp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 2px;
  font-size: 12px;
  color: #6b7280;
}

.tp-foot-done {
  color: #9aa4b2;
}

.tp-link {
  border: none;
  background: transparent;
  color: #1d4ed8;
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

/* ------------------------------ 分区弹出层 ------------------------------ */
/* 弹层挂在 body 上，`scoped` 到不了它的内部，因此这里的类名靠 `tp-pop` 命名空间隔离 */
:global(.pp) {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

:global(.pp-head) {
  padding-bottom: 2px;
}

:global(.pp-list) {
  max-height: 260px;
  overflow: auto;
}

:global(.pp-row) {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 5px 6px;
  border-radius: 2px;
  cursor: pointer;
  font-size: 12px;
  color: #1f2937;
}

:global(.pp-row:hover) {
  background: #eff4ff;
}

:global(.pp-row-empty) {
  color: #9aa4b2;
}

:global(.pp-value) {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}

:global(.pp-meta) {
  color: #8a94a6;
  white-space: nowrap;
}

:global(.pp-none) {
  padding: 8px 6px;
  font-size: 12px;
  color: #9aa4b2;
}

:global(.pp-foot) {
  display: flex;
  gap: 12px;
  padding-top: 6px;
  border-top: 1px solid #eef1f6;
}
</style>
