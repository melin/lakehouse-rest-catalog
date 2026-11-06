<template>
  <div class="pc-page">
    <PageHeader
      title="敏感数据"
      subtitle="列级分类分级与识别结果的治理视图。识别只回答「这一列像不像某类敏感数据」，落标与否由决策带与白名单决定。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load()">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button
          size="small"
          type="primary"
          :loading="scanning"
          :disabled="!catalog"
          @click="startMetadataScan"
        >
          <el-icon><Search /></el-icon>
          <span class="pc-btn-text">扫描元数据</span>
        </el-button>
      </template>
    </PageHeader>

    <!-- 这一屏的定位必须先说清：P0 只有 D1/D2（名称 + 类型）两层元数据探测器，
         置信度上限 0.45，落不到默认阈值 0.85，因此**一次 tag 都不会自动产生**。
         把这句话藏起来的话，界面会显得「扫了没效果」。 -->
    <TipAlert type="info" class="pc-gap">
      <template #title>当前阶段：只扫元数据，只产命中不产标签</template>
      元数据档（D1 名称 + D2 类型）的得分上限是 <code class="pc-mono">0.35 + 0.10 = 0.45</code>，
      低于默认阈值 <code class="pc-mono">0.85</code>——因此这一档的命中**永远进不了自动落标**，
      结果是「有待确认」而不是「已保护」。要看真标签，走
      <RouterLink class="pc-link" :to="{ name: 'sensitivityTags' }">标签清单</RouterLink> 手工打标。
    </TipAlert>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">治理范围</span>
        <span class="pc-list-tools">
          <el-select
            v-model="catalog"
            size="small"
            class="pc-sens-catalog"
            placeholder="选择 catalog"
            @change="onCatalogChange"
          >
            <el-option
              v-for="item in catalogs"
              :key="item.name"
              :label="item.name"
              :value="item.name"
            />
          </el-select>
        </span>
      </div>
      <div class="pc-sens-scope">
        <span v-if="catalog" class="pc-dim">
          下面的覆盖率、标签数与最近扫描都限定在 <code class="pc-mono">{{ catalog }}</code>
        </span>
        <span v-else class="pc-dim">
          先在右上角选一个 catalog。识别结论（类型 + 置信度）本质上是敏感信息，
          服务端按 catalog 判权，不选就等于要求看全库。
        </span>
      </div>
    </PanelCard>

    <div v-if="catalog" class="pc-sens-kpis">
      <div class="pc-kpi">
        <div class="pc-kpi-label">列分类覆盖率</div>
        <div class="pc-kpi-value">{{ coveragePercent }}</div>
        <div class="pc-kpi-dim">
          已分类 {{ coverage.classifiedColumns }} / {{ coverage.totalColumns }} 列
        </div>
      </div>
      <div class="pc-kpi">
        <div class="pc-kpi-label">敏感列</div>
        <div class="pc-kpi-value pc-kpi-red">{{ sensitiveColumns }}</div>
        <div class="pc-kpi-dim">{{ sensitiveBreakdown }}</div>
      </div>
      <div class="pc-kpi">
        <div class="pc-kpi-label">待人审</div>
        <div class="pc-kpi-value pc-kpi-orange">{{ pendingFindings }}</div>
        <div class="pc-kpi-dim">置信度落在决策带的中间段，需人工裁决</div>
      </div>
      <div class="pc-kpi">
        <div class="pc-kpi-label">打标表数</div>
        <div class="pc-kpi-value">{{ coverage.tablesWithTags }}</div>
        <div class="pc-kpi-dim">
          统计了 {{ coverage.tablesCounted }} / {{ coverage.totalTables }} 张表
        </div>
      </div>
    </div>

    <div v-if="catalog" class="pc-sens-grid">
      <PanelCard title="分级分布" hint="按列计 · 对齐《网络数据分类分级指引》1–4 级">
        <div v-for="band in levelBands" :key="band.value" class="pc-sens-lvrow">
          <span class="pc-sens-lvname">{{ band.label }}</span>
          <span class="pc-sens-track">
            <i :style="{ width: band.percent + '%', background: band.color }"></i>
          </span>
          <span class="pc-sens-lvval">{{ band.count }} 列</span>
        </div>
        <!-- CORE / IMPORTANT 必须单独说明：它们是「目录认定」档，不由扫描产出。
             原型把这一句写在下钻区，因为「为什么这两档是 0」是最容易被当成 bug 的地方。 -->
        <div class="pc-sens-note">
          <div class="pc-sens-note-line">
            <strong>CORE</strong> 核心 / <strong>IMPORTANT</strong> 重要（GB/T 43697-2024）：
            <strong>0 列自动识别</strong>
          </div>
          <div class="pc-dim pc-sens-note-body">
            这两档按国家与行业目录认定，不由扫描产出——把「一张含身份证的业务表」自动判成重要数据，
            是把监管责任当算法输出。catalog 预留这两个取值供人工打标。
          </div>
        </div>
        <div v-if="topTypes.length" class="pc-sens-note">
          <!-- 「Top N」的口径必须写在标题里：服务端的覆盖率只给「已分类 / 总数」两
               个数，不给类型维度，因此这个排行是按**拉回来的前 200 条标签**聚合的。
               改写成「全部」会让标注数在标签上千时明显偏小，而看的人无从察觉。 -->
          <div class="pc-dim pc-sens-note-title">
            敏感类型 Top {{ topTypes.length }}（按前 {{ tags.length }} 条标签聚合）
          </div>
          <el-tag
            v-for="item in topTypes"
            :key="item.value"
            size="small"
            :type="item.tag"
            class="pc-sens-chip"
          >
            {{ item.label }} {{ item.count }}
          </el-tag>
        </div>
      </PanelCard>

      <PanelCard title="未分类缺口" :hint="`${gapRows.length} 类`">
        <ResourceState
          :empty="!gapRows.length"
          empty-text="本次范围内没有缺口记录。"
        >
          <el-table :data="gapRows" size="small">
            <el-table-column prop="source" label="来源" width="130" />
            <el-table-column prop="count" label="列数" width="80" />
            <el-table-column prop="hint" label="说明" />
          </el-table>
        </ResourceState>
        <div class="pc-sens-note pc-sens-note-body">
          「元数据缺失」不计入覆盖率分子，也不当成「已扫无敏感」——它单独计数。
        </div>
      </PanelCard>
    </div>

    <PanelCard v-if="catalog" flush class="pc-gap-top">
      <div class="pc-list-head">
        <span class="pc-list-title">最近扫描</span>
        <span class="pc-list-tools pc-dim">
          「执行位置」「触发方式」两列，用来回答「谁在什么时候读了明文」
        </span>
      </div>
      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!scans.length"
        empty-text="这个 catalog 还没有扫描记录。点右上角「扫描元数据」发起一次。"
      >
        <template #actions>
          <el-button
            size="small"
            type="primary"
            :loading="scanning"
            :disabled="!catalog"
            @click="startMetadataScan"
          >
            扫描元数据
          </el-button>
        </template>
        <el-table v-loading="loading" :data="scans" size="small">
          <el-table-column label="扫描" width="150">
            <template #default="{ row }">
              <code class="pc-mono pc-sens-id">{{ shortId(row.id) }}</code>
            </template>
          </el-table-column>
          <el-table-column label="范围" min-width="150">
            <template #default="{ row }">
              <span class="pc-mono">{{ scopeLabel(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="模式" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="row.mode === 'SAMPLED' ? 'primary' : 'info'">
                {{ row.mode }}
              </el-tag>
            </template>
          </el-table-column>
          <!-- 执行位置：P0 恒为进程内（SPARK 那条路到 P4 才通），但列必须在，
               否则这一屏的结构与设计稿对不上，且「先有列再有值」是既定的落地口径。 -->
          <el-table-column label="执行位置" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="row.execution === 'SPARK' ? 'warning' : 'info'">
                {{ executionLabel(row.execution) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="触发" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="triggerTag(row.triggerType)">
                {{ triggerLabel(row.triggerType) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="表 / 列" width="120">
            <template #default="{ row }">
              <span class="pc-mono">
                {{ row.tablesScanned ?? 0 }} / {{ row.columnsScanned ?? 0 }}
              </span>
              <span v-if="row.tablesFailed" class="pc-sens-failed">
                · {{ row.tablesFailed }} 张读失败
              </span>
            </template>
          </el-table-column>
          <el-table-column label="新增命中" width="90">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.findingsCreated ?? 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <el-tag size="small" :type="statusTag(row.status)">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="发起人" width="110">
            <template #default="{ row }">
              <span class="pc-dim">{{ row.triggeredBy || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="耗时" width="100">
            <template #default="{ row }">
              <span class="pc-dim">{{ duration(row) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
    </PanelCard>

    <PanelCard
      v-if="catalog"
      class="pc-gap-top"
      title="catalog 级设置"
      hint="GET / PUT /sensitivity-settings · 变更进审计"
    >
      <el-table :data="settingsRows" size="small">
        <el-table-column label="项" min-width="200">
          <template #default="{ row }">
            {{ row.label }} <code class="pc-mono pc-dim">{{ row.key }}</code>
            <el-tag v-if="row.badge" size="small" type="info" class="pc-sens-chip">{{ row.badge }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="本 catalog 生效值" width="170">
          <template #default="{ row }">
            <span class="pc-mono">{{ row.resolved }}</span>
            <el-tag v-if="row.overridden" size="small" type="primary" class="pc-sens-chip">覆盖</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="全局默认" width="130">
          <template #default="{ row }">
            <span class="pc-mono pc-dim">{{ row.global }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="260">
          <template #default="{ row }">
            <span class="pc-dim">{{ row.hint }}</span>
          </template>
        </el-table-column>
      </el-table>
      <div class="pc-sens-note pc-sens-note-body">
        三项都是「catalog 自己的默认」，不是「全世界唯一的开关」——同一个服务端下，
        一个 catalog 走进程内、另一个（几千张表）默认下推，两边都不需要改别人的设置。
      </div>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'

import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TipAlert from '@/components/TipAlert.vue'
import { describeError } from '@/api/client.js'
import { sensitivityApi } from '@/api/sensitivity.js'
import { notifyError, notifySuccess } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * 敏感数据总览（mockup `#overview`）。挂治理分组，与「数据保护」并列：
 * 数据保护配「怎么保护」，这一屏看「哪些列需要保护」——前半段是策略，后半段是分类。
 *
 * <p>这一屏**一次取三份数据**（标签+覆盖率、扫描历史、catalog 设置），因为它们各有各的
 * 判权与刷新节奏，合成一个请求就得在服务端造一个只为这一个页面存在的聚合端点。
 *
 * <p>不做远程分页：覆盖率与「待人审」是跨页统计，扫描列表按 catalog 过滤后量级是
 * 「几十到几百」（每次扫描一行，且服务端已按时间倒序）。用一次取全量再本地切页的
 * 口径与审计页一致——把分页复杂度花在这里换不到什么。
 */
const catalog = ref('')
const loading = ref(false)
const scanning = ref(false)
const error = ref('')

const coverage = ref({
  classifiedColumns: 0,
  totalColumns: 0,
  tablesWithTags: 0,
  tablesCounted: 0,
  totalTables: 0,
  byLevel: {},
  bySource: {},
})
const tags = ref([])
const scans = ref([])
const settings = ref(null)

/**
 * 「待人审」用服务端的 `meta.total`（服务端已按 state 过滤并计数），不是当前页条数——
 * 因此这一项刻意**只取一页一条**（pageSize=1），拿总数就够，不把队列内容拉进总览。
 */
const pendingFindings = ref(0)

const catalogs = computed(() => session.state.catalogs || [])

/** 首屏用当前工作目录开局：这一屏几乎总是「接着刚才那个 catalog 看」。 */
onMounted(() => {
  catalog.value = session.state.selectedCatalog || ''
  if (catalog.value) load()
})

function onCatalogChange(value) {
  session.selectCatalog(value)
  load()
}

async function load() {
  if (!catalog.value) return
  loading.value = true
  error.value = ''
  const name = catalog.value
  try {
    // 设置与「待人审」各自独立容错：前者读的是 catalog 的 properties（可能没有），
    // 后者只是一个 KPI。让任一个失败把整屏（含覆盖率与扫描历史）打成错误态，
    // 是把「少一个数字」放大成「这一页坏了」——两者的处置完全不同。
    const [tagPayload, scanPayload, findingPayload, settingsPayload] = await Promise.all([
      sensitivityApi.listTags({ catalog: name, pageSize: 200 }),
      sensitivityApi.listScans({ catalog: name, pageSize: 20 }),
      sensitivityApi.listFindings({ catalog: name, state: 'PENDING', pageSize: 1 }).catch(() => null),
      sensitivityApi.settings(name).catch(() => null),
    ])
    // 切过一次 catalog 之后的响应可能属于上一个 catalog：名字对不上就丢掉。
    if (name !== catalog.value) return
    tags.value = tagPayload.items || []
    coverage.value = tagPayload.coverage || coverage.value
    scans.value = scanPayload.items || []
    pendingFindings.value = findingPayload?.meta?.total ?? (findingPayload?.items || []).length
    settings.value = settingsPayload
  } catch (cause) {
    if (name !== catalog.value) return
    error.value = describeError(cause)
    notifyError(cause)
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------ KPI 与分布

const coveragePercent = computed(() => {
  const total = coverage.value.totalColumns || 0
  if (!total) return '—'
  return `${((coverage.value.classifiedColumns / total) * 100).toFixed(1)}%`
})

/** 敏感列 = byLevel 里 L2 及以上的和（L1 公开不算「敏感」）。 */
const sensitiveColumns = computed(() => {
  const byLevel = coverage.value.byLevel || {}
  return ['L4', 'L3', 'L2'].reduce((sum, key) => sum + (byLevel[key] || 0), 0)
})

const sensitiveBreakdown = computed(() => {
  const byLevel = coverage.value.byLevel || {}
  const parts = []
  if (byLevel.L4) parts.push(`L4 机密 ${byLevel.L4}`)
  if (byLevel.L3) parts.push(`L3 敏感 ${byLevel.L3}`)
  if (byLevel.L2) parts.push(`L2 内部 ${byLevel.L2}`)
  return parts.length ? parts.join(' · ') : '暂无分级标签'
})

const LEVEL_BANDS = [
  { value: 'L4', label: 'L4 机密', color: '#f56c6c' },
  { value: 'L3', label: 'L3 敏感', color: '#e6a23c' },
  { value: 'L2', label: 'L2 内部', color: '#409eff' },
  { value: 'L1', label: 'L1 公开', color: '#909399' },
]

/**
 * 四个分级的横条。宽度按**最大档**归一（不是按总和）：L1 通常是几千列，
 * 按总和归一会让 L4 变成一根看不见的线——而「机密有多少」正是这一屏要回答的。
 */
const levelBands = computed(() => {
  const byLevel = coverage.value.byLevel || {}
  const counts = LEVEL_BANDS.map((band) => byLevel[band.value] || 0)
  const max = Math.max(1, ...counts)
  return LEVEL_BANDS.map((band, index) => ({
    ...band,
    count: counts[index],
    percent: Math.round((counts[index] / max) * 100),
  }))
})

const TYPE_LABELS = {
  CN_ID_CARD: { label: '身份证', tag: 'danger' },
  CN_MOBILE: { label: '手机号', tag: 'primary' },
  BANK_CARD: { label: '银行卡', tag: 'danger' },
  CN_USCC: { label: '统一社会信用代码', tag: 'warning' },
  EMAIL: { label: '邮箱', tag: 'warning' },
  IP: { label: 'IP 地址', tag: 'info' },
  CREDENTIAL: { label: '密码 / 密钥', tag: 'danger' },
  PERSON_NAME: { label: '姓名', tag: 'primary' },
  ADDRESS: { label: '地址', tag: 'warning' },
  SALARY: { label: '薪资', tag: 'danger' },
  MEDICAL_DIAGNOSIS: { label: '病历诊断', tag: 'danger' },
}

/** 类型 Top：按 `typeCode` 聚合当前标签（覆盖率接口不给类型维度）。 */
const topTypes = computed(() => {
  const counts = new Map()
  for (const tag of tags.value) {
    const code = tag.typeCode
    if (!code) continue
    counts.set(code, (counts.get(code) || 0) + 1)
  }
  return [...counts.entries()]
    .sort((left, right) => right[1] - left[1])
    .slice(0, 6)
    .map(([code, count]) => ({
      value: code,
      count,
      label: TYPE_LABELS[code]?.label || code,
      tag: TYPE_LABELS[code]?.tag || 'info',
    }))
})

/**
 * 未分类缺口。服务端的覆盖率只给「已分类 / 总数」两个数，缺口按**来源**分是三行，
 * 但 P0 的响应里没有这个维度——因此这里只摆得出「还没分类的列数」这一行，
 * 另外两类（采样失败 / 元数据缺失）在 P0 无数据源（采样要 P1、Lance 的 schema 缺失
 * 只体现在扫描的 `tablesFailed` 里）。**宁可少一行，也不编一个数出来**。
 */
const gapRows = computed(() => {
  const rows = []
  const total = coverage.value.totalColumns || 0
  const classified = coverage.value.classifiedColumns || 0
  if (total > classified) {
    rows.push({
      source: '未分类列',
      count: total - classified,
      hint: '已纳入统计但还没有任何标签的列（含表级标签不参与分子）',
    })
  }
  const failed = scans.value.reduce((sum, row) => sum + (row.tablesFailed || 0), 0)
  if (failed) {
    rows.push({
      source: '扫描读失败',
      count: failed,
      hint: '最近若干次扫描里读取失败的次数合计（表级计数，不是列数）',
    })
  }
  return rows
})

/** 设置表：每行同时给「覆盖值」与「全局默认」，`overridden` 决定是否显示「覆盖」徽标。 */
const settingsRows = computed(() => {
  const data = settings.value || {}
  return [
    {
      key: 'defaultExecution',
      label: '默认执行位置',
      resolved: executionLabel(data.resolvedExecution || data.defaultExecution),
      global: 'IN_PROCESS',
      overridden: !!data.defaultExecution,
      hint: '三层覆盖：全局 < 此处 < 单次请求。改这里不影响「已发起」的扫描',
      badge: '',
    },
    {
      key: 'maxConcurrentScans',
      label: '并发上限',
      resolved: String(data.resolvedMaxConcurrentScans ?? '—'),
      global: '2',
      overridden: data.maxConcurrentScans != null,
      hint: '只管 A 路线（进程内）扫描——下推的扫描作业在集群上跑，计的是执行集群那把 maxConcurrentJobs',
      badge: 'A 路线进程内',
    },
    {
      key: 'propagationMaxDepth',
      label: '血缘传播深度',
      resolved: String(data.resolvedPropagationMaxDepth ?? '—'),
      global: '5',
      overridden: data.propagationMaxDepth != null,
      hint: 'yml 键 sensitivity.propagation.max-depth；深链数仓可调到 8–10，同时应收紧自动应用白名单',
      badge: '',
    },
  ]
})

// ------------------------------------------------------------------ 枚举展示

function executionLabel(value) {
  if (value === 'SPARK') return '下推 Spark'
  if (value === 'IN_PROCESS') return '进程内'
  return value || '进程内'
}

function triggerLabel(value) {
  return { MANUAL: '手动', LIFECYCLE: '生命周期', SCHEDULE: '定时' }[value] || value || '手动'
}

function triggerTag(value) {
  return { MANUAL: 'primary', LIFECYCLE: 'warning', SCHEDULE: 'success' }[value] || 'info'
}

function statusTag(value) {
  return {
    SUCCEEDED: 'success',
    FAILED: 'danger',
    RUNNING: 'primary',
    SUBMITTING: 'primary',
    PENDING: 'info',
    CANCELLED: 'info',
  }[value] || 'info'
}

function shortId(id) {
  const text = String(id || '')
  return text.length > 12 ? `${text.slice(0, 8)}…` : text || '—'
}

/** 扫描范围：scope 决定哪几段有意义，空段不显示（`database` 与 `table` 都是空串表示未限定）。 */
function scopeLabel(row) {
  const parts = [row.catalog].filter(Boolean)
  if (row.database) parts.push(row.database)
  if (row.table) parts.push(row.table)
  return parts.join('.') || '—'
}

/** 耗时：`finishedAt - startedAt`；还没跑完就显示「进行中」而不是算一个假数。 */
function duration(row) {
  if (!row.startedAt) return '—'
  if (!row.finishedAt) return '进行中'
  const millis = row.finishedAt - row.startedAt
  if (millis < 1000) return `${millis}ms`
  if (millis < 60000) return `${(millis / 1000).toFixed(1)}s`
  const minutes = Math.floor(millis / 60000)
  const seconds = Math.round((millis % 60000) / 1000)
  return `${minutes}m ${String(seconds).padStart(2, '0')}s`
}

// ------------------------------------------------------------------ 发起扫描

/**
 * 发起一次**目录级元数据扫描**。
 *
 * <p>`execution` 刻意**不传**：省略即用生效的三层覆盖值（请求 > catalog > 全局），
 * 这正是「三层覆盖生效」这条验收要走的路径；传了反而把 catalog 的设置遮住了。
 *
 * <p>`mode` 传 `METADATA_ONLY`：P0 只有 D1/D2，采样档（`SAMPLED`）要 P1 才有值探测，
 * 现在发采样扫描会真的去读明文却拿不到额外结论——成本与风险都白花。
 */
async function startMetadataScan() {
  if (!catalog.value) return
  scanning.value = true
  try {
    const accepted = await sensitivityApi.startScan({
      catalog: catalog.value,
      scope: 'CATALOG',
      database: '',
      table: '',
      mode: 'METADATA_ONLY',
      triggerType: 'MANUAL',
    })
    notifySuccess(
      `已受理扫描 ${shortId(accepted.scanId)}（${executionLabel(accepted.execution)}）`,
    )
    await load()
  } catch (cause) {
    notifyError(cause)
  } finally {
    scanning.value = false
  }
}
</script>

<style scoped>
.pc-gap {
  margin-bottom: 12px;
}

.pc-gap-top {
  margin-top: 12px;
}

.pc-sens-catalog {
  width: 220px;
}

.pc-sens-scope {
  padding: 0 14px 12px;
  font-size: 12px;
}

.pc-sens-kpis {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin: 12px 0;
}

.pc-kpi {
  padding: 12px 14px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}

.pc-kpi-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pc-kpi-value {
  font-size: 24px;
  font-weight: 600;
  line-height: 34px;
}

.pc-kpi-red {
  color: var(--el-color-danger);
}

.pc-kpi-orange {
  color: var(--el-color-warning);
}

.pc-kpi-dim {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pc-sens-grid {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}

.pc-sens-lvrow {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.pc-sens-lvname {
  width: 74px;
  font-size: 12px;
  color: var(--el-text-color-regular);
}

.pc-sens-track {
  flex: 1;
  height: 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  overflow: hidden;
}

.pc-sens-track i {
  display: block;
  height: 100%;
  border-radius: 4px;
}

.pc-sens-lvval {
  width: 78px;
  text-align: right;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pc-sens-note {
  margin-top: 12px;
  padding-top: 10px;
  border-top: 1px dashed var(--el-border-color-lighter);
}

.pc-sens-note-line {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}

.pc-sens-note-title {
  margin-bottom: 6px;
}

.pc-sens-note-body {
  font-size: 12px;
  line-height: 1.7;
}

.pc-sens-chip {
  margin-right: 6px;
  margin-bottom: 4px;
}

.pc-sens-id {
  font-size: 12px;
}

.pc-sens-failed {
  color: var(--el-color-warning);
  font-size: 12px;
}

@media (max-width: 900px) {
  .pc-sens-kpis {
    grid-template-columns: repeat(2, 1fr);
  }

  .pc-sens-grid {
    grid-template-columns: 1fr;
  }
}
</style>
