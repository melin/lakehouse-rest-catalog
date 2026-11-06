<template>
  <div class="pc-tsens">
    <ResourceState :loading="loading" :error="error" />

    <template v-if="loaded">
      <!-- ============================ 列 → 标签 / 级别 / 来源 ============================ -->
      <PanelCard flush>
        <div class="pc-list-head">
          <span class="pc-list-title">逐列分类状态</span>
          <span class="pc-list-tools pc-dim">
            <template v-if="coverage.totalColumns">
              覆盖率 <strong>{{ coveragePercent }}</strong>
              （{{ coverage.classifiedColumns }} / {{ coverage.totalColumns }} 列）
            </template>
            <template v-else>—</template>
          </span>
          <span class="pc-spacer" />
          <el-input
            v-model="filterInput"
            size="small"
            clearable
            class="pc-tsens-filter"
            placeholder="过滤列名 / 类型"
          />
        </div>

        <!-- 覆盖率的分母只覆盖这一张表：服务端在 table 参数给出时按单表算
             （ColumnTagService#coverage 的注释写的就是「本屏在表详情页里也用」）。
             这一点必须在界面上说明，否则从「标签清单」屏跳过来的人会以为
             这个百分比是 catalog 级的、进而以为治理率掉了。 -->
        <div v-if="coverage.totalColumns" class="pc-tsens-cov">
          <span class="pc-dim">
            分母是<b>这一张表</b>的列数（不是整个 catalog）——服务端在给了表名时按单表算覆盖率。
            筛级别不会改变它：覆盖率算的是「范围里有多少列」，与当前筛选无关。
          </span>
        </div>

        <ResourceState
          :empty="!rows.length"
          :empty-text="columns.length
            ? `没有名称或类型包含「${filter}」的列`
            : '这张表读不到列清单（未刷新元数据的外部表 / Lance 表），因此无法逐列判定。'"
        >
          <!-- 未分类列显式标灰：它们既不是「已保护」，也不是「确认不敏感」。
               设计稿 §13 的口径是「覆盖率是分母问题，不能靠沉默掩盖」。 -->
          <el-table :data="rows" size="small" :row-class-name="rowClass">
            <el-table-column label="列" min-width="200">
              <template #default="{ row }">
                <span class="pc-mono" :class="{ 'pc-tsens-unclassified': !row.classified }">
                  {{ row.name }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="类型" min-width="130">
              <template #default="{ row }">
                <span class="pc-mono pc-dim">{{ row.type || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="敏感标签" min-width="240">
              <template #default="{ row }">
                <template v-if="row.tags.length">
                  <el-tag
                    v-for="tag in row.tags"
                    :key="tag.id"
                    size="small"
                    class="pc-tsens-chip"
                    :type="levelTagType(tag.level)"
                    effect="plain"
                  >
                    <span class="pc-mono">{{ tag.tagKey }}</span>
                    <template v-if="tag.tagValue"> · {{ tag.tagValue }}</template>
                  </el-tag>
                </template>
                <el-tag v-else size="small" type="info" effect="plain" class="pc-tsens-chip">未分类</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="级别" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.level" size="small" :type="levelTagType(row.level)">
                  {{ row.level }}
                </el-tag>
                <span v-else class="pc-dim">—</span>
              </template>
            </el-table-column>
            <el-table-column label="来源" width="90">
              <template #default="{ row }">
                <el-tag v-if="row.source" size="small" :type="sourceTagType(row.source)">
                  {{ sourceLabel(row.source) }}
                </el-tag>
                <span v-else class="pc-dim">—</span>
              </template>
            </el-table-column>
            <!-- 「当前脱敏」这一列同时要回答两件事：这一列现在受不受保护、以及保护它的是
                 哪条规则。P0 只有显式策略是真实生效的——标签驱动脱敏（§8.1）属 P3，
                 今天把「有标签」显示成「已脱敏」是假的。 -->
            <el-table-column label="当前脱敏" min-width="220">
              <template #default="{ row }">
                <template v-if="row.hidden">
                  <span class="pc-mono">不可见</span>
                  <el-tag size="small" type="info" class="pc-tsens-chip">显式</el-tag>
                </template>
                <template v-else-if="row.maskSql">
                  <span class="pc-mono">{{ row.maskSql }}</span>
                  <el-tag size="small" type="primary" class="pc-tsens-chip">显式</el-tag>
                </template>
                <template v-else-if="levelRank(row.level) >= 3">
                  <span class="pc-dim">明文</span>
                  <el-tag size="small" type="danger" class="pc-tsens-chip">风险</el-tag>
                </template>
                <span v-else class="pc-dim">明文</span>
              </template>
            </el-table-column>
          </el-table>
        </ResourceState>

        <div class="pc-tsens-note">
          未分类列显式标灰，而不是当成「扫过、没敏感」——<b>覆盖率是分母问题，不能靠沉默掩盖</b>。
          「当前脱敏」列里的<b>标签绑定</b>一档属 P3（标签 → 脱敏动作的绑定与 resolve 展开），
          今天不会出现：有标签不等于已脱敏，把两者画成同一个徽标会让人误以为那列已经安全。
        </div>
      </PanelCard>

      <!-- ============================ 判据 + 操作 ============================ -->
      <div class="pc-tsens-grid">
        <PanelCard title="这张表能对外吗" hint="判据不是「是 / 否」，而是「还缺什么」">
          <el-table :data="verdictRows" size="small">
            <el-table-column label="判据" min-width="170" />
            <el-table-column label="结果" min-width="200">
              <template #default="{ row }">
                <el-tag v-if="row.tag" size="small" :type="row.tagType">{{ row.value }}</el-tag>
                <span v-else>{{ row.value }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div class="pc-tsens-verdict" :class="`is-${verdict.tone}`">
            {{ verdict.verdict }}
          </div>
        </PanelCard>

        <PanelCard title="操作">
          <div class="pc-tsens-actions">
            <!-- 重新扫描这一张表：直接调同一个发起端点，scope=TABLE。
                 不做成「跳到发起扫描屏再选一遍」——站在一张表的详情页里，
                 范围已经确定了，再让人选一次是把上下文扔掉。 -->
            <el-button size="small" :loading="scanning" @click="rescanTable">
              <el-icon><Search /></el-icon>
              <span class="pc-btn-text">重新扫描这张表（METADATA_ONLY）</span>
            </el-button>
            <el-button
              size="small"
              @click="$router.push({ name: 'sensitivityTags', query: { catalog, table: tableRef } })"
            >
              <el-icon><PriceTag /></el-icon>
              <span class="pc-btn-text">手工新增标签</span>
            </el-button>
            <!-- 待人审条数来自 findings（不是 tags）：审核队列问的是「扫描判过什么」，
                 与「现在是什么」在 P0 是两件事（前者有未被裁决的命中）。 -->
            <el-button
              v-if="pendingFindings > 0"
              size="small"
              type="primary"
              @click="$router.push({ name: 'sensitivityFindings', query: { catalog } })"
            >
              <el-icon><Check /></el-icon>
              <span class="pc-btn-text">去审核这张表的 {{ pendingFindings }} 条待确认</span>
            </el-button>
          </div>
          <div class="pc-tsens-note">
            表级标签 <code class="pc-mono">sensitivity.level</code> 只用于检索与粗判，
            <b>不参与策略展开</b>——脱敏始终按列。重新扫描按
            <code class="pc-mono">scope=TABLE</code> 提交，与「发起扫描」屏走同一个端点；
            同一张表在跑时它会回 409（同表并发闸门，§5.6.4）。
          </div>
        </PanelCard>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import { describeError } from '@/api/client.js'
import { policyApi } from '@/api/policy.js'
import { sensitivityApi } from '@/api/sensitivity.js'
import { notifyError, notifySuccess } from '@/composables/index.js'
import {
  buildRows,
  exposureVerdict,
  levelRank,
  levelTagType,
  sourceLabel,
  sourceTagType,
} from '@/utils/sensitivityTable.js'

/**
 * 表详情页的「敏感数据」页签（mockup `#table`；设计稿 §11）。
 *
 * <p><b>这一屏回答的问题与「标签清单」屏不同。</b>标签清单是「有哪些治理结论」——
 * 数据源是标签表，天然只有命中的列；这一屏是「**这张表**现在是什么状态」——骨架是
 * 这张表的列，标签只是左连接上来的一侧。因此这里<strong>不按标签分页</strong>：列数
 * 是几十到几百量级（一张宽表），一次取回全部反而让「哪些列没被分类」这个问题看得见。
 *
 * <p><b>三份数据各取各的。</b>
 * <ul>
 *   <li>{@code GET /column-tags?catalog=&table=库.表} → 标签 + 这张表的覆盖率；</li>
 *   <li>{@code GET /sensitivity-findings?catalog=&table=库.表} → 待人审条数（只为按钮文案）；</li>
 *   <li>{@code GET /policies/tables/{c}/{db}/{t}} → 显式脱敏与整列不可见。</li>
 * </ul>
 * 合成一个请求要在服务端造一个只为这一页存在的聚合端点，而三者的判权与刷新节奏本就不一样。
 *
 * <p><b>策略那一份失败不打断整屏</b>：它是「有没有显式脱敏」这一点，读不到时页签照样
 * 能回答分类状态——把它算作整屏错误，会让人以为标签功能坏了。反之另外两份是主干，
 * 任一失败就是错误态。
 *
 * <p><b>加载由父组件触发</b>（首次切到这个页签才请求），与其余页签同一套按需加载。
 */
const props = defineProps({
  /** catalog 名（即 prefix）。 */
  catalog: { type: String, required: true },
  database: { type: String, required: true },
  tableName: { type: String, required: true },
  /** 全量列 `[{name, type}]`，来自表详情已取回的 schema。表格的骨架就是它。 */
  columns: { type: Array, default: () => [] },
})

/** 服务端按 `database + "." + table` 整体比较表名（只给表名匹配不上）。 */
const tableRef = computed(() => `${props.database}.${props.tableName}`)

// ------------------------------------------------------------------ 加载

const loading = ref(false)
const error = ref('')
const loaded = ref(false)
const scanning = ref(false)

const tags = ref([])
const coverage = ref({ classifiedColumns: 0, totalColumns: 0 })
const masks = ref([])
const hiddenColumns = ref([])
/** 策略读取失败时为 true：界面上不再说「没有显式脱敏」，而是说「读不到策略」。 */
const policyUnavailable = ref(false)
const pendingFindings = ref(0)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const payload = await sensitivityApi.listTags({
      catalog: props.catalog,
      table: tableRef.value,
    })
    tags.value = payload.items || []
    coverage.value = payload.coverage || { classifiedColumns: 0, totalColumns: 0 }

    // 待审条数与策略都是**加分项**，各自容错：一个 KPI 或一条策略读不到，
    // 不该把整屏（逐列分类状态）打成错误态。
    const [findings, policy] = await Promise.all([
      sensitivityApi
        .listFindings({ catalog: props.catalog, state: 'PENDING', table: tableRef.value })
        .catch(() => null),
      policyApi.get(props.catalog, props.database, props.tableName).catch(() => null),
    ])
    pendingFindings.value = findings?.meta?.total ?? (findings?.items || []).length
    if (policy && policy.table) {
      masks.value = policy.masks || []
      hiddenColumns.value = policy.hiddenColumns || []
      policyUnavailable.value = false
    } else {
      masks.value = []
      hiddenColumns.value = []
      // 读不到策略 ≠ 没有策略：前者要说明「这一列不知道」，后者才能说「明文」。
      // 只有真正拿到「没有策略」的信号（`policy` 非空但没有 `table` 字段）才当明文——
      // 拿不到时为 null，此时置为 true，界面据此把话说清。
      policyUnavailable.value = policy === null
    }
    loaded.value = true
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

// ------------------------------------------------------------------ 行与判据

const filterInput = ref('')
const filter = computed(() => filterInput.value.trim().toLowerCase())

const allRows = computed(() =>
  buildRows(props.columns, tags.value, masks.value, hiddenColumns.value),
)

const rows = computed(() => {
  if (!filter.value) return allRows.value
  return allRows.value.filter(
    (row) => row.name.toLowerCase().includes(filter.value)
      || String(row.type || '').toLowerCase().includes(filter.value),
  )
})

/**
 * 判据行。
 *
 * <p>「L4 列是否全部受保护」这一条在**读不到策略时**不能给出「否」——那是一个会让人
 * 去建脱敏规则的结论，而实际原因可能只是这次请求失败了。因此这一档显示「未知」。
 */
const verdictRows = computed(() => {
  const verdict = exposureVerdict(allRows.value)
  const secretAll = verdict.secretTotal
  const secretProtected = verdict.secretProtected
  return [
    {
      criterion: '表级最高级别',
      value: verdict.topLevel || '未分类',
      tag: Boolean(verdict.topLevel),
      tagType: levelTagType(verdict.topLevel),
    },
    {
      criterion: '机密列数（L4 / 重要 / 核心）',
      value: String(secretAll),
      tag: false,
    },
    {
      criterion: '机密列是否全部受保护',
      value: policyUnavailable.value
        ? '未知（读不到这张表的策略）'
        : `${secretProtected} / ${secretAll}`,
      tag: !policyUnavailable.value && secretAll > 0,
      tagType: secretProtected === secretAll ? 'success' : 'danger',
    },
    {
      criterion: '未分类列',
      value: `${verdict.unclassifiedTotal} 列 / 共 ${verdict.totalColumns} 列`,
      tag: verdict.unclassifiedTotal > 0,
      tagType: 'warning',
    },
  ]
})

const verdict = computed(() => exposureVerdict(allRows.value))

const coveragePercent = computed(() => {
  const total = coverage.value.totalColumns || 0
  if (!total) return '—'
  return `${((coverage.value.classifiedColumns / total) * 100).toFixed(1)}%`
})

/** 未分类整行标灰：它是「结论缺失」而不是「结论是安全」。 */
function rowClass({ row }) {
  return row.classified ? '' : 'pc-tsens-row-muted'
}

// ------------------------------------------------------------------ 动作

/**
 * 重新扫描这一张表。
 *
 * <p>`triggerType=MANUAL` 是这一发的真实来源（不是 `SCHEDULE`）——事件流里
 * 「谁在什么时候读了明文」要能仅凭 `triggerType` 回答，从界面上点出来的必须标 MANUAL。
 * `execution=IN_PROCESS` 显式给：元数据档恒走进程内（下推 Spark 会在服务端吃 400），
 * 传它只是把「我这一发跑在哪」说清楚。
 */
async function rescanTable() {
  scanning.value = true
  try {
    await sensitivityApi.startScan({
      catalog: props.catalog,
      scope: 'TABLE',
      database: props.database,
      table: props.tableName,
      mode: 'METADATA_ONLY',
      execution: 'IN_PROCESS',
      triggerType: 'MANUAL',
    })
    notifySuccess('已发起扫描，结果在「识别结果审核」里看（元数据档不产标签）。')
    await load()
  } catch (cause) {
    // 409（同表已有扫描在跑）在这里是最常见的一类，原样把服务端的说明抛出去，
    // 它比任何前端文案都具体
    notifyError(cause)
  } finally {
    scanning.value = false
  }
}

watch(() => [props.catalog, props.database, props.tableName], () => {
  // 换表即作废：旧表的标签与策略落到新表上会得到一份看起来很确定的错结论
  tags.value = []
  coverage.value = { classifiedColumns: 0, totalColumns: 0 }
  masks.value = []
  hiddenColumns.value = []
  pendingFindings.value = 0
  policyUnavailable.value = false
  loaded.value = false
  filterInput.value = ''
})

defineExpose({ load })
</script>

<style scoped>
.pc-tsens-filter {
  width: 200px;
}

.pc-tsens-cov {
  padding: 0 16px 8px;
  font-size: 12px;
  line-height: 1.7;
}

.pc-tsens-chip {
  margin: 2px 4px 2px 0;
}

.pc-tsens-unclassified {
  color: var(--el-text-color-placeholder);
}

.pc-tsens-note {
  padding: 8px 16px 0;
  font-size: 12px;
  line-height: 1.8;
  color: var(--el-text-color-secondary);
}

.pc-tsens-grid {
  display: grid;
  grid-template-columns: minmax(320px, 1fr) minmax(320px, 1fr);
  gap: 12px;
  margin-top: 12px;
}

.pc-tsens-actions {
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: flex-start;
}

.pc-tsens-verdict {
  margin-top: 10px;
  padding: 8px 10px;
  border-radius: 4px;
  font-size: 12px;
  line-height: 1.7;
}

.pc-tsens-verdict.is-ok {
  background: var(--el-color-success-light-9);
  color: var(--el-color-success);
}

.pc-tsens-verdict.is-warn {
  background: var(--el-color-warning-light-9);
  color: var(--el-color-warning);
}

.pc-tsens-verdict.is-risk {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
}

:deep(.pc-tsens-row-muted) {
  background: var(--el-fill-color-lighter);
}

@media (max-width: 1100px) {
  .pc-tsens-grid {
    grid-template-columns: 1fr;
  }
}
</style>
