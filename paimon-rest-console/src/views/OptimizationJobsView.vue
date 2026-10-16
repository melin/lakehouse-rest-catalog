<template>
  <div class="pc-page">
    <PageHeader
      title="优化任务"
      subtitle="跨 catalog 的 Spark 优化批次：定时调度与手动触发都会先创建任务记录，再异步提交到 k8s 执行，逐表回调上报结果。一个批次 = 同一 catalog 里同时到期的一组表。"
    >
      <template #actions>
        <el-button size="small" :loading="jobs.loading.value" @click="reload">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <div class="pc-stats">
      <div v-for="item in stats" :key="item.label" class="pc-stat">
        <div class="pc-stat-label">{{ item.label }}</div>
        <div class="pc-stat-value" :class="item.tone ? `pc-opt-tone-${item.tone}` : ''">{{ item.value }}</div>
        <div class="pc-stat-foot">{{ item.foot }}</div>
      </div>
    </div>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">批次（{{ rows.length }}）</span>
        <el-select v-model="filter.status" size="small" class="pc-opt-filter" placeholder="状态">
          <el-option label="全部状态" value="" />
          <el-option v-for="item in STATUS_OPTIONS" :key="item" :label="jobStatus(item).label" :value="item" />
        </el-select>
        <el-select v-model="filter.action" size="small" class="pc-opt-filter" placeholder="动作">
          <el-option label="全部动作" value="" />
          <el-option v-for="spec in ACTIONS" :key="spec.action" :label="spec.label" :value="spec.action" />
        </el-select>
        <el-select v-model="filter.catalog" size="small" class="pc-opt-filter" placeholder="Catalog">
          <el-option label="全部 Catalog" value="" />
          <el-option
            v-for="catalog in session.state.catalogs"
            :key="catalog.name"
            :label="catalog.name"
            :value="catalog.name"
          />
        </el-select>
        <el-select v-model="filter.trigger" size="small" class="pc-opt-filter" placeholder="触发方式">
          <el-option label="全部触发方式" value="" />
          <el-option label="定时调度" value="SCHEDULED" />
          <el-option label="手动" value="MANUAL" />
        </el-select>
        <TipNote>
          过滤在浏览器里做：批次接口一次只给最近 {{ LIMIT }} 个批次，因此这里的统计与过滤
          都基于这个窗口。状态、动作、catalog 在服务端已有过滤条件（列出的是同一种结果），
          控制台统一在本地过滤是为了让「统计卡」与「列表」口径一致——统计数的是同一个窗口，
          不是另一次请求的结果。
          <br /><br />
          「动作」按批次里出现过的动作过滤：一个批次可能同时做 Compaction 与快照清理。
        </TipNote>
        <span class="pc-spacer" />
        <span class="pc-dim">共 {{ rows.length }} 个批次</span>
      </div>

      <ResourceState
        :loading="jobs.loading.value"
        :error="jobs.error.value"
        :empty="!rows.length"
        empty-text="还没有优化批次。在表详情的「存储优化」页签里开启策略，或直接点「立即优化」。"
      >
        <el-table
          :data="pagedRows"
          size="small"
          row-key="id"
          :expand-row-keys="expandedKeys"
          @expand-change="onExpandChange"
        >
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="pc-opt-detail">
                <div class="pc-opt-detail-head">
                  <span class="pc-list-title">批次明细</span>
                  <span class="pc-dim">
                    逐表回调上报（{{ actionsLabel(row.actions) }}）；一张表失败不会拖死同批其它表
                  </span>
                  <span class="pc-spacer" />
                  <el-button size="small" text type="primary" @click="openDetail(row)">批次详情</el-button>
                </div>
                <div v-if="detailOf(row.id).loading" class="pc-dim pc-opt-detail-state">正在加载明细…</div>
                <div v-else-if="detailOf(row.id).error" class="pc-opt-detail-state pc-opt-error-text">
                  {{ detailOf(row.id).error }}
                </div>
                <el-table v-else :data="detailOf(row.id).items" size="small" empty-text="这个批次没有明细">
                  <el-table-column label="表" min-width="240">
                    <template #default="{ row: item }">
                      <span class="pc-mono pc-break">{{ item.table }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="动作" width="210">
                    <template #default="{ row: item }">{{ actionsLabel(item.actions) }}</template>
                  </el-table-column>
                  <el-table-column label="状态" width="100">
                    <template #default="{ row: item }">
                      <el-tag size="small" effect="plain" :type="itemStatus(item.status).tag">
                        {{ itemStatus(item.status).label }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="指标" min-width="260">
                    <template #default="{ row: item }">
                      <span class="pc-mono">{{ itemSummary(item) || '—' }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="耗时" width="100" align="right">
                    <template #default="{ row: item }">
                      <span class="pc-mono">{{ itemDuration(item) }}</span>
                    </template>
                  </el-table-column>
                  <!-- 与执行历史同一条规则（OVERFLOW_TIP）：单行省略，悬浮看全文 -->
                  <el-table-column label="说明" min-width="220" :show-overflow-tooltip="OVERFLOW_TIP">
                    <template #default="{ row: item }">
                      <span v-if="item.errorMessage" class="pc-opt-error-text">{{ item.errorMessage }}</span>
                      <span v-else class="pc-dim">—</span>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="批次" width="110">
            <template #default="{ row }">
              <el-tooltip :content="row.id" placement="top">
                <span class="pc-mono">{{ shortId(row.id) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="Catalog" width="120">
            <template #default="{ row }">
              <RouterLink class="pc-link" :to="{ name: 'browse', query: { catalog: row.catalog } }">
                {{ row.catalog }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column label="动作" min-width="200">
            <template #default="{ row }">{{ actionsLabel(row.actions) }}</template>
          </el-table-column>
          <el-table-column label="表数" width="80" align="right">
            <template #default="{ row }">{{ formatNumber(row.tableCount) }}</template>
          </el-table-column>
          <el-table-column label="触发方式" width="110">
            <template #default="{ row }">
              {{ triggerText(row.triggerType) }}
              <span v-if="row.retryOf" class="pc-dim">（重试）</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="jobStatus(row.status).tag">
                {{ jobStatus(row.status).label }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="创建时间" width="180">
            <template #default="{ row }">
              <TimeText :millis="row.createdAt" />
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="100" align="right">
            <template #default="{ row }">
              <span class="pc-mono">{{ jobElapsed(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="运行标识" min-width="220">
            <template #default="{ row }">
              <span v-if="row.resource" class="pc-mono pc-dim pc-break">{{ row.resource }}</span>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" align="right">
            <template #default="{ row }">
              <IconAction icon="View" label="详情" @click="openDetail(row)" />
              <IconAction
                v-if="canRetry(row)"
                icon="RefreshRight"
                label="重试失败的表"
                type="primary"
                @click="retryJob(row)"
              />
              <IconAction
                v-if="isActiveJob(row.status)"
                icon="CircleClose"
                label="取消"
                type="danger"
                @click="cancelJob(row)"
              />
            </template>
          </el-table-column>
          <!-- 首次加载时列表还没数据，ResourceState 走 loading 分支会照常渲染表格，
               这里把 Element 自带的空文案顶掉，免得它在遮罩下闪一下 -->
          <template #empty>
            <span />
          </template>
        </el-table>

        <el-pagination
          v-if="rows.length > pageSize"
          v-model:current-page="page"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="rows.length"
          layout="total, sizes, prev, pager, next"
          size="small"
          class="pc-pager"
        />
      </ResourceState>
    </PanelCard>

    <!-- 批次详情 -->
    <el-drawer v-model="drawer.open" :title="drawer.title" size="680px">
      <template v-if="drawer.job">
        <dl class="pc-kv">
          <dt>批次 ID</dt>
          <dd><CopyText :text="drawer.job.id" empty-text="—" /></dd>
          <dt>Catalog</dt>
          <dd>{{ drawer.job.catalog }}</dd>
          <dt>触发方式</dt>
          <dd>
            {{ triggerText(drawer.job.triggerType) }}
            <template v-if="drawer.job.retryOf">
              · 重试自 <span class="pc-mono">{{ shortId(drawer.job.retryOf) }}</span>
            </template>
          </dd>
          <dt>动作</dt>
          <dd>{{ actionsLabel(drawer.job.actions) }}</dd>
          <dt>状态</dt>
          <dd>
            <el-tag size="small" effect="plain" :type="jobStatus(drawer.job.status).tag">
              {{ jobStatus(drawer.job.status).label }}
            </el-tag>
            <span class="pc-dim"> · {{ formatNumber(drawer.job.tableCount) }} 张表</span>
          </dd>
          <dt>提交器</dt>
          <dd>{{ orPlaceholder(drawer.job.submitter) }}<span v-if="drawer.job.namespace" class="pc-dim"> · {{ drawer.job.namespace }}</span></dd>
          <dt>执行侧资源</dt>
          <dd><CopyText :text="drawer.job.resource" empty-text="还没提交" /></dd>
          <dt>Spark 应用</dt>
          <dd>{{ orPlaceholder(drawer.job.sparkAppId) }}</dd>
        </dl>

        <SectionTitle title="时间线">
          <template #tip>
            <TipNote>
              「截止时间」是服务端判超时的时刻，由配置里的批次超时决定；超过它之后
              跟踪节拍会把批次判失败并清理执行侧资源——清理只是不让它继续占资源，
              已经写完的表不会回退。
            </TipNote>
          </template>
        </SectionTitle>
        <dl class="pc-kv">
          <dt>创建</dt>
          <dd><TimeText :millis="drawer.job.createdAt" /></dd>
          <dt>提交</dt>
          <dd><TimeText :millis="drawer.job.submittedAt" /></dd>
          <dt>开始</dt>
          <dd><TimeText :millis="drawer.job.startedAt" /></dd>
          <dt>结束</dt>
          <dd><TimeText :millis="drawer.job.finishedAt" /></dd>
          <dt>截止</dt>
          <dd><TimeText :millis="drawer.job.deadlineAt" /></dd>
        </dl>

        <el-alert v-if="drawer.job.errorMessage" type="error" :closable="false" show-icon class="pc-hint">
          <template #title>批次错误</template>
          <div class="pc-break">{{ drawer.job.errorMessage }}</div>
        </el-alert>

        <!-- 批次令牌只在有权查看时由服务端下发（授权关闭时一律给出，开启时只给服务管理员）。
             它的用途是手工重跑：优化任务拉清单与上报结果都要带它 -->
        <el-alert v-if="drawer.job.callbackToken" type="info" :closable="false" show-icon class="pc-hint">
          <template #title>回调令牌</template>
          <div>
            k8s 里的优化任务用这个令牌拉执行清单、逐表上报结果（请求头
            <span class="pc-mono">X-Optimization-Token</span>）。它只对这一个批次有效，
            重跑一个批次时手工带上它即可。
          </div>
          <div class="pc-opt-token">
            <CopyText :text="drawer.job.callbackToken" />
          </div>
        </el-alert>

        <SectionTitle title="原始响应" />
        <JsonBlock :value="drawerRaw" />
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { optimizationApi } from '@/api/optimization.js'
import CopyText from '@/components/CopyText.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { confirmDanger, notifyError, notifySuccess, useLocalPaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { formatNumber, orPlaceholder } from '@/utils/format.js'
import {
  ACTIONS,
  actionsLabel,
  elapsedText,
  isActiveJob,
  itemDuration,
  itemStatus,
  itemSummary,
  jobStatus,
  OVERFLOW_TIP,
  shortId,
  triggerText,
} from '@/utils/optimization.js'

/**
 * 全局「优化任务」页：跨表跨库的唯一批次入口。
 *
 * <p><b>为什么列表与统计都基于同一个窗口。</b>批次接口一次只返回最近的若干条
 * （服务端上限 200），因此统计卡数的是「这个窗口里的批次」而不是全库历史。
 * 若把统计换成另一次（不带过滤的）请求，它和列表就会是两个口径——
 * 用户看到「失败 3 个」却在列表里只数得出 1 个，那比数字本身不精确更糟。
 * 窗口写在卡片脚下，不藏起来。
 *
 * <p>表级明细按需加载：列表不带明细（一个批次可能有几十上百张表），
 * 展开某一行、或从表详情页跳过来时才拉那一个批次的详情。
 */
const route = useRoute()

/** 一次拉多少个批次。服务端上限 200，这里取同一个值以免「以为要了更多」。 */
const LIMIT = 200

/** 过滤器的候选状态。服务端新增状态时这里是空档——但表格仍会如实显示那个状态。 */
const STATUS_OPTIONS = ['PENDING', 'SUBMITTING', 'RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELED']

const jobs = useResource(() => optimizationApi.jobs({ limit: LIMIT }))

const filter = reactive({ status: '', action: '', catalog: '', trigger: '' })

const allRows = computed(() => jobs.data.value?.jobs || [])

const rows = computed(() => allRows.value.filter((job) => {
  if (filter.status && job.status !== filter.status) return false
  if (filter.action && !(job.actions || []).includes(filter.action)) return false
  if (filter.catalog && job.catalog !== filter.catalog) return false
  if (filter.trigger && job.triggerType !== filter.trigger) return false
  return true
}))

const { page, pageSize, paged: pagedRows } = useLocalPaging(rows)

/** 统计只看时间窗口，不看过滤器：过滤是「我在找某个批次」，不该把总数改掉。 */
const stats = computed(() => {
  const list = allRows.value
  const active = list.filter((job) => job.status === 'RUNNING' || job.status === 'SUBMITTING').length
  const pending = list.filter((job) => job.status === 'PENDING').length
  const dayStart = new Date().setHours(0, 0, 0, 0)
  const today = list.filter((job) => (job.createdAt || 0) >= dayStart).length
  const dayAgo = Date.now() - 86_400_000
  const failed = list.filter((job) => job.status === 'FAILED' && (job.createdAt || 0) >= dayAgo).length
  const foot = `最近 ${LIMIT} 个批次内`
  return [
    { label: '运行中', value: formatNumber(active), tone: 'run', foot },
    { label: '排队中', value: formatNumber(pending), foot },
    { label: '今日批次', value: formatNumber(today), foot },
    { label: '失败（24 小时）', value: formatNumber(failed), tone: failed ? 'fail' : '', foot },
  ]
})

function jobElapsed(job) {
  const end = job.finishedAt || (isActiveJob(job.status) ? Date.now() : 0)
  return elapsedText(job.startedAt || job.submittedAt || job.createdAt, end)
}

function canRetry(job) {
  // 只有确实有失败表的批次能重试：服务端对其余情况返回 400
  return job.status === 'PARTIAL_SUCCESS' || job.status === 'FAILED'
}

function reload() {
  jobs.load()
  // 展开中的批次一起重拉：刷新后明细还停在上一次的结果上会误导人
  for (const id of expandedKeys.value) {
    loadItems(id)
  }
}

// ------------------------------------------------------------------ 表级明细（按需加载）

const expandedKeys = ref([])
/** 批次 id → { loading, error, items }。展开过才请求，收起不丢（再展开直接用缓存）。 */
const details = reactive({})

function detailOf(jobId) {
  return details[jobId] || { loading: false, error: '', items: [] }
}

async function loadItems(jobId) {
  details[jobId] = { ...detailOf(jobId), loading: true, error: '' }
  try {
    const payload = await optimizationApi.job(jobId)
    details[jobId] = { loading: false, error: '', items: payload.items || [], job: payload }
  } catch (error) {
    details[jobId] = { loading: false, error: error?.message || String(error), items: [] }
  }
}

function onExpandChange(row, expanded) {
  const isExpanded = Array.isArray(expanded)
    ? expanded.some((item) => item.id === row.id)
    : expanded === true
  const keys = new Set(expandedKeys.value)
  if (isExpanded) {
    keys.add(row.id)
  } else {
    keys.delete(row.id)
  }
  expandedKeys.value = [...keys]
  if (isExpanded) {
    loadItems(row.id)
  }
}

/**
 * 从别的页面跳过来时展开对应批次（表详情页的历史里有批次号）。
 *
 * <p>用查询参数而不是路由参数：这一页没有「当前批次」的概念，
 * 深链接指向的是「打开并展开这一行」，`?job=` 表达的正是这件事，
 * 关掉展开状态也不必改动 URL。
 */
watch(() => route.query.job, (jobId) => {
  if (!jobId || typeof jobId !== 'string') return
  if (!expandedKeys.value.includes(jobId)) {
    expandedKeys.value = [...expandedKeys.value, jobId]
  }
  loadItems(jobId)
}, { immediate: true })

// ------------------------------------------------------------------ 详情抽屉

const drawer = reactive({ open: false, title: '', job: null })

/** 抽屉里给原始响应，但**去掉明细**：一个批次几十上百张表，明细在展开行里看。 */
const drawerRaw = computed(() => {
  const job = drawer.job
  if (!job) return null
  const { items, ...rest } = job
  return { ...rest, itemCount: items?.length }
})

function openDetail(row) {
  drawer.title = `批次 ${shortId(row.id)}`
  // 先给列表里那份，明细加载完之后替换成详情那份（详情里才有 callbackToken 与明细）
  drawer.job = details[row.id]?.job || row
  drawer.open = true
  optimizationApi.job(row.id)
    .then((payload) => {
      details[row.id] = { loading: false, error: '', items: payload.items || [], job: payload }
      // 抽屉还开着才替换：用户可能已经把它关掉、或换看另一个批次了
      if (drawer.open && drawer.title === `批次 ${shortId(row.id)}`) {
        drawer.job = payload
      }
    })
    .catch((error) => notifyError(error))
}

// ------------------------------------------------------------------ 重试与取消

async function retryJob(row) {
  const ok = await confirmDanger({
    title: '重试失败的表',
    target: `把批次 ${shortId(row.id)} 里失败的表重新跑一遍？`,
    detail: '会创建一个新批次（原批次保留），只包含失败的表——成功过的表不会重跑。'
      + '参数取这张表当前的策略，因此先改配置再重试是有效的。',
    confirmText: '重试',
  })
  if (!ok) return
  try {
    const created = await optimizationApi.retry(row.id)
    notifySuccess(`已创建重试批次 ${shortId(created?.id)}`)
    jobs.load()
  } catch (error) {
    notifyError(error)
  }
}

async function cancelJob(row) {
  const ok = await confirmDanger({
    title: '取消批次',
    target: `取消批次 ${shortId(row.id)}（${formatNumber(row.tableCount)} 张表）？`,
    detail: '还没提交的直接作废；已经在跑的会连带删掉执行侧资源。'
      + '已经写完的表不会回退，未执行完的表在历史里记为失败。',
    confirmText: '取消批次',
  })
  if (!ok) return
  try {
    await optimizationApi.cancel(row.id)
    notifySuccess('已取消')
    reload()
  } catch (error) {
    notifyError(error)
  }
}

// 进这一页时确保 catalog 列表已加载：过滤器里的 Catalog 候选来自它，
// 而从表详情页直接跳过来时未必拉过列表（那一步在布局挂载时做，正常已经拉过）
if (!session.state.catalogsLoaded && !session.state.loadingCatalogs) {
  session.loadCatalogs().catch(() => {})
}
</script>

<style scoped>
/* 过滤器的宽度：四个下拉并排，每个放得下最长的那项（PARTIAL_SUCCESS 的显示名是「部分成功」） */
.pc-opt-filter {
  width: 140px;
}

/* 统计数的语义色：与状态标签同一套（运行中是主色、失败是危险色），
   数字为 0 时不染色——「0 个失败」不该看起来像个告警 */
.pc-opt-tone-run {
  color: var(--el-color-primary);
}

.pc-opt-tone-fail {
  color: var(--el-color-danger);
}

.pc-opt-detail {
  padding: 8px 16px 14px;
}

.pc-opt-detail-head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
}

.pc-opt-detail-state {
  padding: 8px 0;
  font-size: 13px;
}

.pc-opt-error-text {
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}

.pc-opt-token {
  margin-top: 8px;
  font-size: 13px;
}

.pc-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
