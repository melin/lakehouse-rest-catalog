<template>
  <div class="pc-page">
    <PageHeader
      title="数据保护"
      subtitle="按表绑定的行过滤与列脱敏策略。策略在 Spark 会话内生效，改动后新查询 60 秒内自动加载。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="$router.push({ name: 'policyWizard' })">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建策略</span>
        </el-button>
      </template>
    </PageHeader>

    <!-- 强制等级是部署属性不是策略属性（设计 §7.1 / §2.3）：徽标只能看不能改。
         「整列不可见」故意不放入口：注入点晚于 SELECT * 展开，做不了（§5.8）——
         放一个开关然后默默不生效，比不放更糟。 -->
    <el-alert type="warning" :closable="false" show-icon class="pc-gap">
      <template #title>强制等级 L0 · 引擎内强制</template>
      策略只改写 Spark 的执行计划，<strong>存储凭据仍在客户端</strong>：持有凭据的程序
      <code class="pc-mono">spark.read.parquet()</code> 直读文件仍能看到明文。要升级到 L1
      （网关代理、用户只有 SQL 连接），需要把 Spark 会话收进网关。控制台自己的三个口子
      （数据预览、文件下载、列统计）已按策略收口。
    </el-alert>

    <!-- KPI：跨页统计，因此列表一次取全量（治理对象量级是「几十」，见 data 注释） -->
    <div class="pc-policy-kpis">
      <div class="pc-kpi">
        <div class="pc-kpi-label">受保护表</div>
        <div class="pc-kpi-value">{{ rows.length }}</div>
      </div>
      <div class="pc-kpi">
        <div class="pc-kpi-label">带行过滤</div>
        <div class="pc-kpi-value">{{ kpi.withRowFilter }}</div>
        <div class="pc-kpi-dim">其余表只做列脱敏</div>
      </div>
      <div class="pc-kpi">
        <div class="pc-kpi-label">脱敏列</div>
        <div class="pc-kpi-value">{{ kpi.maskedColumns }}</div>
        <div class="pc-kpi-dim">分布在 {{ kpi.maskedTables }} 张表</div>
      </div>
      <div class="pc-kpi">
        <div class="pc-kpi-label">豁免主体</div>
        <div class="pc-kpi-value">{{ kpi.exempts }}</div>
        <div class="pc-kpi-dim">豁免优先于一切判定</div>
      </div>
    </div>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">策略列表</span>
        <span class="pc-list-tools">
          <el-input
            v-model="keyword"
            size="small"
            clearable
            placeholder="按表名过滤"
            class="pc-policy-search"
          />
          <el-select v-model="statusFilter" size="small" class="pc-policy-status">
            <el-option label="全部状态" value="all" />
            <el-option label="生效中" value="enabled" />
            <el-option label="已停用" value="disabled" />
          </el-select>
        </span>
      </div>
      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!filtered.length"
        empty-text="还没有任何表绑定数据策略。新建一条，或去表详情的「数据策略」页签。"
      >
        <template #actions>
          <el-button size="small" type="primary" @click="$router.push({ name: 'policyWizard' })">
            新建策略
          </el-button>
        </template>
        <el-table v-loading="loading" :data="pagedRows" size="small">
          <el-table-column label="表" min-width="220">
            <template #default="{ row }">
              <RouterLink
                class="pc-link pc-mono"
                :to="{ name: 'table', params: { prefix: catalogOf(row.table), database: databaseOf(row.table), table: tableNameOf(row.table) }, query: { tab: 'policy' } }"
              >
                {{ row.table }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column label="行过滤" min-width="240">
            <template #default="{ row }">
              <code v-if="row.rowFilterSql" class="pc-mono pc-policy-sql-inline">
                {{ row.rowFilterSql }}
              </code>
              <span v-else class="pc-dim">未设置（全表行可见）</span>
            </template>
          </el-table-column>
          <el-table-column label="列脱敏" min-width="180">
            <template #default="{ row }">
              <template v-if="(row.maskColumns || []).length">
                <el-tag
                  v-for="column in row.maskColumns"
                  :key="column"
                  size="small"
                  type="warning"
                  class="pc-policy-chip"
                >
                  {{ column }}
                </el-tag>
              </template>
              <span v-else class="pc-dim">—</span>
              <template v-if="(row.hiddenColumns || []).length">
                <el-tag
                  v-for="column in row.hiddenColumns"
                  :key="'hidden-' + column"
                  size="small"
                  type="info"
                  class="pc-policy-chip"
                >
                  {{ column }} · 不可见
                </el-tag>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <!-- 「已停用」与「悬空」人机来源不同：前者是人做的，后者是系统发现的。
                   悬空（引用列已删）会让整张表读死，REST 总览暂不判——详情与试算会暴露。 -->
              <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                {{ row.enabled ? '生效中' : '已停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="版本" width="70">
            <template #default="{ row }">
              <span class="pc-mono pc-dim">v{{ row.version }}</span>
            </template>
          </el-table-column>
          <el-table-column label="最后修改" width="200">
            <template #default="{ row }">
              <TimeText :millis="row.updatedAt" />
              <template v-if="row.updatedBy"> · {{ row.updatedBy }}</template>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="76" fixed="right">
            <template #default="{ row }">
              <IconAction icon="View" label="试算" @click="openDryRun(row)" />
              <IconAction icon="Edit" label="详情" type="primary" @click="openDetail(row)" />
            </template>
          </el-table-column>
        </el-table>
        <TablePager
          :total="filtered.length"
          :page="page"
          :page-size="pageSize"
          @page-change="onPageChange"
          @size-change="onSizeChange"
        />
      </ResourceState>
    </PanelCard>

    <!-- 默认语义必须显式说（mockup 画面 1 说明 6） -->
    <el-alert type="info" :closable="false" show-icon class="pc-gap">
      <template #title>默认语义 · 没有策略 = 不保护</template>
      没有绑定策略的表对所有主体明文。「取不到策略就拒绝」只发生在策略服务不可达或
      版本校验失败时（fail-closed）——两者在语义上必须分开表达，否则运维会以为
      「建了策略就全拦住了」。
    </el-alert>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'

import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import { policyApi } from '@/api/policy.js'
import { describeError } from '@/api/client.js'
import { notifyError, useEmbeddedPaging } from '@/composables/index.js'

/**
 * 数据保护总览（mockup 画面 1）。入口放治理分组，与「权限管理」并列：
 * 权限管「谁能读」，数据保护管「读到什么」——两者是同一层治理决策，
 * 不该藏进表详情（表详情的「数据策略」页签仍保留，负责单表的编辑）。
 *
 * <p>分页用 `useEmbeddedPaging`（与常规列表页的 useRemotePaging 相反）：
 * 总览要跨页 KPI 与「状态 + 表名」组合过滤，而服务端列表只有 catalog / refTable
 * 两个过滤维度；策略是治理对象、量级是「几十」（DataPolicyService.list 的注释原文），
 * 一次取全量再本地切页，分页复杂度不花在永远不会大的集合上。
 */
const router = useRouter()

const loading = ref(false)
const error = ref('')
const rows = ref([])

const keyword = ref('')
const statusFilter = ref('all')

async function load() {
  loading.value = true
  error.value = ''
  try {
    const payload = await policyApi.list()
    rows.value = payload.items || []
  } catch (cause) {
    error.value = describeError(cause)
    notifyError(cause)
  } finally {
    loading.value = false
  }
}

onMounted(load)

// ------------------------------------------------------------------ 过滤与切页（先过滤再切页）

const filtered = computed(() => {
  const text = keyword.value.trim().toLowerCase()
  return rows.value.filter((row) => {
    if (statusFilter.value === 'enabled' && !row.enabled) return false
    if (statusFilter.value === 'disabled' && row.enabled) return false
    if (text && !String(row.table || '').toLowerCase().includes(text)) return false
    return true
  })
})

const {
  page,
  pageSize,
  rows: pagedRows,
  onPageChange,
  onSizeChange,
} = useEmbeddedPaging(filtered)

const kpi = computed(() => {
  let withRowFilter = 0
  let maskedColumns = 0
  let maskedTables = 0
  let exempts = 0
  for (const row of rows.value) {
    if (row.hasRowFilter) withRowFilter += 1
    const count = (row.maskColumns || []).length
    if (count > 0) maskedTables += 1
    maskedColumns += count
    exempts += row.exemptCount || 0
  }
  return { withRowFilter, maskedColumns, maskedTables, exempts }
})

// ------------------------------------------------------------------ 三段名拆解与跳转

function catalogOf(table) {
  return String(table || '').split('.')[0] || ''
}

function databaseOf(table) {
  // 库名本身可能带点号（多级 namespace 的显示名）：去掉首段 catalog 与尾段表名，
  // 中间整体算库名——与服务端 NamespacePaths 的点号口径一致
  const parts = String(table || '').split('.')
  return parts.length >= 3 ? parts.slice(1, -1).join('.') : parts[1] || ''
}

function tableNameOf(table) {
  const parts = String(table || '').split('.')
  return parts[parts.length - 1] || ''
}

function openDetail(row) {
  router.push({
    name: 'table',
    params: {
      prefix: catalogOf(row.table),
      database: databaseOf(row.table),
      table: tableNameOf(row.table),
    },
    query: { tab: 'policy' },
  })
}

function openDryRun(row) {
  router.push({
    name: 'policyDryRun',
    params: {
      catalog: catalogOf(row.table),
      database: databaseOf(row.table),
      table: tableNameOf(row.table),
    },
  })
}
</script>

<style scoped>
.pc-gap {
  margin-bottom: 12px;
}

.pc-policy-kpis {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 12px;
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

.pc-kpi-dim {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.pc-policy-search {
  width: 200px;
}

.pc-policy-status {
  width: 120px;
}

.pc-policy-sql-inline {
  display: inline-block;
  padding: 2px 6px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  word-break: break-all;
}

.pc-policy-chip {
  margin-right: 6px;
  margin-bottom: 4px;
}

@media (max-width: 900px) {
  .pc-policy-kpis {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>
