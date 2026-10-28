<template>
  <div class="pc-page">
    <PageHeader
      title="优化任务"
      subtitle="跨 catalog 的 Spark 优化批次：定时调度与手动触发都会先创建任务记录，再异步提交到 k8s 执行，逐表回调上报结果。一个批次 = 同一 catalog 里同时到期的一组表。"
    >
      <template #actions>
        <!--
          画面 6：没配执行集群时这一屏要能自己解释「为什么一条批次都没有」。
          数据来自 session 里那份 catalog 列表（每行都带 executionCluster 摘要），
          不额外发请求：这是提示，不是判据——真正的拦截在服务端（策略保存 400、
          出批跳过），这里只是提前把原因说出来。

          为什么不是一条横幅：这段说明有五行，而它出现的场景恰恰是「列表空着」——
          横幅把统计卡与空态一起挤到折叠线以下，用户看到的第一屏全是解释。
          收成页头一颗叹号：图标常驻（异常不会被忽略），文字悬停即现（说明不丢）。
          用 popover 而不是 tooltip，是因为里面有一个「去 Catalogs 配置」——
          气泡在指针移入时不关闭，按钮点得到；藏进纯 tooltip 就等于把这个出口丢了。
        -->
        <el-popover
          v-if="blockedCatalogs.length"
          placement="bottom-end"
          :width="400"
          trigger="hover"
          :show-after="0"
          :hide-after="120"
        >
          <template #reference>
            <!-- tabindex 让键盘用户也能到这颗图标上：el-popover 的 triggerKeys
                 （回车/空格）不看 trigger 类型，聚焦后按一下同样能展开 -->
            <span
              class="pc-opt-gap"
              role="note"
              tabindex="0"
              :aria-label="`${blockedCatalogs.length} 个 catalog 没有可用的执行集群，悬停查看详情`"
            >
              <el-icon :size="15"><WarningFilled /></el-icon>
            </span>
          </template>
          <div class="pc-opt-gap-title">
            <el-icon class="pc-opt-gap-title-icon"><WarningFilled /></el-icon>
            <span>{{ blockedCatalogs.length }} 个 catalog 没有可用的执行集群</span>
          </div>
          <p class="pc-opt-gap-text">
            <span class="pc-mono pc-break">{{ blockedCatalogs.join('、') }}</span>
            的优化任务不会提交：出批时整组跳过，<b>游标不推进</b>——配好执行集群之后的下一拍就会
            立刻把该跑的表跑掉（错过的调度不补）。已保存的策略不会被删除，但给这些 catalog
            保存「启用」的策略现在会被拒绝（400）。
          </p>
          <el-button size="small" text type="primary" class="pc-opt-gap-go" @click="gotoCatalogs">
            去 Catalogs 配置
          </el-button>
        </el-popover>
        <!-- 定时刷新（开关 + 节拍）在手动刷新左边：两者是同一件事的自动/手动两种模式 -->
        <AutoRefreshControl
          v-model:enabled="autoEnabled"
          v-model:seconds="autoSeconds"
          :stale-error="staleError"
          :last-at="autoRefreshedAt"
        />
        <!-- 只绑表格那一路的 loading：统计卡是另一次请求，让它也转圈会把
             「列表在刷」说成「整页在刷」 -->
        <el-button size="small" :loading="loading" @click="reload">
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
        <span class="pc-list-title">批次（{{ total }}）</span>
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
          过滤在服务端做：表格按页码取，四个条件都进查询参数，因此筛的是全部批次而不是当前页。
          统计卡仍看<b>最近 {{ LIMIT }} 个批次</b>这个窗口（另一次请求），不随上面的筛选变化——
          它们的口径不同，卡片脚下也写着这一点。
          <br /><br />
          「动作」按批次里出现过的动作过滤：一个批次可能同时做 Compaction 与快照清理。
        </TipNote>
        <span class="pc-spacer" />
        <span class="pc-dim">共 {{ total }} 个批次</span>
      </div>

      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        empty-text="还没有优化批次。在表详情的「存储优化」页签里开启策略，或直接点「立即优化」。"
      >
        <el-table
          :data="rows"
          size="small"
          row-key="id"
          :expand-row-keys="expandedKeys"
          @expand-change="onExpandChange"
        >
          <el-table-column type="expand" fixed="left">
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
          <el-table-column label="操作" width="100" align="right" fixed="right">
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

        <TablePager
          :total="total"
          :page="page"
          :page-size="pageSize"
          @page-change="onPageChange"
          @size-change="onSizeChange"
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
import { useRoute, useRouter } from 'vue-router'

import { optimizationApi } from '@/api/optimization.js'
import AutoRefreshControl from '@/components/AutoRefreshControl.vue'
import CopyText from '@/components/CopyText.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import {
  confirmDanger,
  notifyError,
  notifySuccess,
  useAutoRefresh,
  useRemotePaging,
  useResource,
} from '@/composables/index.js'
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
const router = useRouter()

/** 一次拉多少个批次。服务端上限 200，这里取同一个值以免「以为要了更多」。 */
const LIMIT = 200

/**
 * 当前「有策略却不会跑」的 catalog：没有可派发的执行集群。
 *
 * <p>判据就是服务端在 catalog 列表里给的那一列（`executionCluster.status`），
 * 只取 `NOT_CONFIGURED` / `DISABLED` / `FAILING` 三态——`UNVERIFIED` 是可派发的
 * （「刚配好还没验」是正常中间状态），把它算进来会让横幅在每次保存后误报。
 *
 * <p>数据来自 `session` 里那份 catalog 列表，因此**这一屏打开之前它就被加载过**
 * （`session.bootstrap()`），不额外发请求。代价是列表可能略旧；横幅本来就只是提示，
 * 真正的拦截在服务端。
 */
const blockedCatalogs = computed(() => (session.state.catalogs || [])
  .filter((catalog) => {
    const status = catalog.executionCluster?.status
    return status === 'NOT_CONFIGURED' || status === 'DISABLED' || status === 'FAILING'
  })
  .map((catalog) => catalog.name))

function gotoCatalogs() {
  router.push({ name: 'catalogs' })
}

/** 过滤器的候选状态。服务端新增状态时这里是空档——但表格仍会如实显示那个状态。 */
const STATUS_OPTIONS = ['PENDING', 'SUBMITTING', 'RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELED']

const filter = reactive({ status: '', action: '', catalog: '', trigger: '' })

/**
 * 统计卡的窗口：最近 {@link LIMIT} 个批次，**不带分页、不受筛选影响**。
 *
 * <p>为什么要另取一次而不是复用表格那一页：卡片回答的是「这幅系统现在什么状态」，
 * 看的是最近的批次；筛选回答的是「我在找某个批次」。把两者绑在一起，
 * 一筛状态卡片就全变 0，读起来像系统出故障了。
 */
const statsSource = useResource(() => optimizationApi.jobs({ limit: LIMIT }))
const allRows = computed(() => statsSource.data.value?.jobs || [])

/**
 * 表格：远程分页 + 服务端过滤。四个过滤条件全部下推——
 * 只筛当前页等于没筛，而「共 N 个批次」也只能来自服务端的 `total`。
 */
const {
  rows,
  total,
  page,
  pageSize,
  loading,
  error,
  staleError: rowsStaleError,
  load,
  reload: reloadRows,
  silentLoad: silentLoadRows,
  onPageChange,
  onSizeChange,
} = useRemotePaging(({ page: current, pageSize: size }) => optimizationApi
  .jobs({
    catalog: filter.catalog,
    status: filter.status,
    action: filter.action,
    trigger: filter.trigger,
    page: current,
    pageSize: size,
  })
  .then((payload) => ({ items: payload.jobs || [], total: payload.page?.total ?? 0 })))

// 任一过滤条件变化都回第一页重取（页码留在第 3 页会看到「空表」）
watch(() => [filter.status, filter.action, filter.catalog, filter.trigger], () => reloadRows())

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
  statsSource.load()
  reloadRows()
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

async function loadItems(jobId, { silent = false } = {}) {
  const previous = detailOf(jobId)
  if (!silent) {
    details[jobId] = { ...previous, loading: true, error: '' }
  }
  try {
    const payload = await optimizationApi.job(jobId)
    details[jobId] = { loading: false, error: '', items: payload.items || [], job: payload }
  } catch (error) {
    // 静默刷新失败时**留着上一次的明细**：展开行正被读着，把它换成
    // 一行红字比不刷新更糟（用户会以为批次明细没了）
    if (silent) return
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
    reload()
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

// ------------------------------------------------------------------ 定时刷新
//
// 这一屏是「等状态变」的页面（提交中 → 运行中 → 部分成功），批次从创建到结束
// 通常几分钟：不自动刷新的话，用户只能反复点「刷新」看进度。
//
// 三处都走静默路径，否则每 5 秒会出现三次可见的抖动：表格遮罩、统计卡遮罩、
// 展开行里的「正在加载明细…」。失败时一律保留上一份数据（见各 composable 的说明），
// 原因收在控件那颗角标里。
const {
  enabled: autoEnabled,
  seconds: autoSeconds,
  lastAt: autoRefreshedAt,
} = useAutoRefresh(async () => {
  await Promise.all([
    statsSource.silentLoad(),
    silentLoadRows(),
    // 展开中的批次跟着刷（明细才是「跑到哪张表了」的答案），但同样不出加载态
    ...expandedKeys.value.map((id) => loadItems(id, { silent: true })),
  ])
}, {
  page: 'optimization-jobs',
  // 读详情抽屉时不刷列表：抽屉里那份是打开时的快照，让背后的表格跳来跳去
  // 只会让人以为「我点开的不是这一行」
  blocked: () => drawer.open,
})

/**
 * 定时刷新失败的原因（列表或统计任一）。合成一条给控件：
 * 两处各自弹一次提示，同一个故障会在屏幕上出现两条同样的红字。
 */
const staleError = computed(() => rowsStaleError.value || statsSource.staleError.value)
</script>

<style scoped>
/* 「没有可用的执行集群」收成页头一颗叹号（见模板注释）。
   尺寸按页头那颗「刷新」按钮的 32px 对齐，否则它会比旁边的控件矮一截；
   圆角与底色沿用站内的「一档浅色提示」做法（参考 .pc-db-item.is-active），
   深色主题下 color-mix 会跟着 --el-color-warning 一起变亮。 */
.pc-opt-gap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  height: 32px;
  padding: 0 7px;
  border-radius: var(--pc-radius);
  color: var(--el-color-warning);
  background: color-mix(in srgb, var(--el-color-warning) 12%, transparent);
  cursor: help;
  transition: background-color 0.15s;
}

.pc-opt-gap:hover {
  background: color-mix(in srgb, var(--el-color-warning) 22%, transparent);
}

/* 键盘走到这颗图标上时要看得见（它没有按钮的默认焦点环） */
.pc-opt-gap:focus-visible {
  outline: 2px solid var(--el-color-warning);
  outline-offset: 1px;
}

/* 气泡正文：字体与全站提示一致（12.5px / 1.7），标题用语义色而不是通篇红——
   这一段是「配置没做好」，不是「系统出错了」。
   外框的内边距用 el-popover 的默认值（12px）：再自定义就得写全局选择器，
   而 `pc-opt-*` 是这一屏的私有前缀，全局样式里不允许出现（见 verify-console）。 */
.pc-opt-gap-title {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--el-color-warning);
}

.pc-opt-gap-title-icon {
  flex: none;
}

.pc-opt-gap-text {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--pc-text);
}

/* 气泡末尾的出口：与正文隔开一点，左侧不留白（和正文左边缘对齐） */
.pc-opt-gap-go {
  margin-top: 2px;
  margin-left: -10px;
}

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
