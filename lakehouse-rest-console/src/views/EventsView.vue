<template>
  <div class="pc-page">
    <PageHeader
      title="事件"
      subtitle="目录的事件流：catalog / 库 / 表 / 视图 / 授权 / 主体与角色 / 优化策略与批次终态都记进同一张 audit_event 表，Paimon 与 Iceberg 两种表格式在同一个流里。这里的「刷新」是带游标的增量拉取，只取上次之后新发生的事件。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">事件（{{ total }}）</span>
        <el-select v-model="filter.catalog" size="small" class="pc-evt-filter" placeholder="Catalog">
          <el-option label="全部 Catalog" value="" />
          <el-option
            v-for="catalog in session.state.catalogs"
            :key="catalog.name"
            :label="catalog.name"
            :value="catalog.name"
          />
        </el-select>
        <el-select v-model="filter.category" size="small" class="pc-evt-filter" placeholder="类别">
          <el-option label="全部类别" value="" />
          <el-option v-for="item in CATEGORIES" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="filter.type" size="small" class="pc-evt-filter" placeholder="事件类型">
          <el-option label="全部类型" value="" />
          <el-option-group v-for="group in TYPE_GROUPS" :key="group.label" :label="group.label">
            <el-option v-for="item in group.items" :key="item.value" :label="item.label" :value="item.value" />
          </el-option-group>
        </el-select>
        <TipNote>
          <b>分页契约</b>（docs/event-framework.md §4）：按发生时间倒序（id 倒序）
          <b>远程分页</b>，新事件在第一页；「刷新」重取当前页，总条数随
          <code>page.total</code> 一起回来，所以能一直往回翻到最早一条。
          <br /><br />
          「目录」「类别」「事件类型」三个筛选都在服务端做（查询参数），
          任一变化回到第一页重取——本地筛只筛得到当页，「优化失败」筛出空页
          并不等于窗口里没有失败。
          <br /><br />
          只增不改的增量流（<code>?since=</code> 游标）仍留在服务端给脚本和
          引擎客户端用，两种翻法互斥；页面表格走的是页码模式。
        </TipNote>
        <span class="pc-spacer" />
      </div>

      <ResourceState
        :loading="loading"
        :error="error"
        :empty="loaded && !total"
        empty-text="窗口内没有事件。做一次建表、授权或等一个优化任务跑完，这里就会出现记录。"
      >
        <el-table :data="rows" size="small" row-key="id">
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
                  <dt>类别</dt>
                  <dd><span class="pc-mono">{{ row.category }}</span></dd>
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

        <TablePager
          :total="total"
          :page="page"
          :page-size="pageSize"
          @page-change="onPageChange"
          @size-change="onSizeChange"
        />
      </ResourceState>
    </PanelCard>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

import { consoleApi } from '@/api/console.js'
import CopyText from '@/components/CopyText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { onConsoleRefresh, useRemotePaging } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * 目录事件页：事件流的「看」入口。
 *
 * <p><b>为什么从游标增量改成远程分页。</b>事件是只增不改的事实（append-only），
 * 增量拉取（`?since=游标`）看起来更省流量，但它有个治不好的毛病：<b>只能往前看，
 * 回不去</b>。想找「上周那次误删表」只能反复重置游标碰运气，而窗口只有 200 条。
 * 页码模式把「第 N 页」交给服务端（按 id 倒序，新在前），总数随 `page.total`
 * 回来，「看最新」与「往回翻」于是成了同一张表上的两件事。增量模式仍留在
 * 服务端给脚本与引擎客户端用（两种翻法互斥，见 `ConsoleEventController` 的注释），
 * 页面不再用它。
 *
 * <p><b>为什么三个筛选都要下推。</b>本地筛只筛得到当前这一页——「类型 = 优化失败」
 * 筛出空页时，读的人会以为窗口里没有失败，而实际上失败事件都在第 7 页。
 * 所以 catalog / category / type 全部进查询参数，任一变化回第一页重取。
 */

/**
 * 事件类别：与服务端 `CatalogEventCategory` 一一对应，也是服务端过滤参数 `category`
 * 的取值（键带引号，verify-console 逐字比对）。
 */
const CATEGORIES = [
  { value: 'CATALOG', label: '目录' },
  { value: 'NAMESPACE', label: '库 / Namespace' },
  { value: 'TABLE', label: '表' },
  { value: 'VIEW', label: '视图' },
  { value: 'GRANT', label: '授权' },
  { value: 'POLICY', label: '策略' },
  { value: 'PRINCIPAL', label: '主体' },
  { value: 'PRINCIPAL_ROLE', label: '主体角色' },
  { value: 'CATALOG_ROLE', label: 'Catalog 角色' },
  { value: 'OPTIMIZATION', label: '优化' },
]

/**
 * 事件类型的展示元数据，与服务端 `CatalogEventType` 的 wire 值一一对应
 * （verify-console 会拿枚举逐条比对：漏一条的那种事件就显示成 wire 原文）。
 *
 * `category` 用于把类型下拉按类别分组，也是「换类别时清掉不匹配的类型筛选」的依据。
 */
const TYPES = [
  { value: 'catalog.created', label: '创建目录', tag: 'success', category: 'CATALOG' },
  { value: 'catalog.updated', label: '修改目录', tag: 'warning', category: 'CATALOG' },
  { value: 'catalog.dropped', label: '删除目录', tag: 'danger', category: 'CATALOG' },
  { value: 'namespace.created', label: '建库', tag: 'success', category: 'NAMESPACE' },
  { value: 'namespace.updated', label: '改库属性', tag: 'warning', category: 'NAMESPACE' },
  { value: 'namespace.dropped', label: '删库', tag: 'danger', category: 'NAMESPACE' },
  { value: 'table.created', label: '建表', tag: 'success', category: 'TABLE' },
  { value: 'table.registered', label: '注册表', tag: 'info', category: 'TABLE' },
  { value: 'table.committed', label: '表提交', tag: 'primary', category: 'TABLE' },
  { value: 'table.renamed', label: '表重命名', tag: 'warning', category: 'TABLE' },
  { value: 'table.unregistered', label: '注销表', tag: 'info', category: 'TABLE' },
  { value: 'table.dropped', label: '删表', tag: 'danger', category: 'TABLE' },
  { value: 'view.created', label: '建视图', tag: 'success', category: 'VIEW' },
  { value: 'view.updated', label: '改视图', tag: 'warning', category: 'VIEW' },
  { value: 'view.renamed', label: '视图重命名', tag: 'warning', category: 'VIEW' },
  { value: 'view.dropped', label: '删视图', tag: 'danger', category: 'VIEW' },
  { value: 'grant.granted', label: '授予权限', tag: 'primary', category: 'GRANT' },
  { value: 'grant.revoked', label: '撤销权限', tag: 'warning', category: 'GRANT' },
  { value: 'principal.created', label: '建主体', tag: 'success', category: 'PRINCIPAL' },
  { value: 'principal.updated', label: '改主体', tag: 'warning', category: 'PRINCIPAL' },
  { value: 'principal.credentials_rotated', label: '轮换凭据', tag: 'warning', category: 'PRINCIPAL' },
  { value: 'principal.credentials_reset', label: '重置凭据', tag: 'warning', category: 'PRINCIPAL' },
  { value: 'principal.role_granted', label: '授予角色', tag: 'primary', category: 'PRINCIPAL' },
  { value: 'principal.role_revoked', label: '收回角色', tag: 'warning', category: 'PRINCIPAL' },
  { value: 'principal.dropped', label: '删主体', tag: 'danger', category: 'PRINCIPAL' },
  { value: 'principal_role.created', label: '建主体角色', tag: 'success', category: 'PRINCIPAL_ROLE' },
  { value: 'principal_role.updated', label: '改主体角色', tag: 'warning', category: 'PRINCIPAL_ROLE' },
  { value: 'principal_role.catalog_role_granted', label: '绑定 Catalog 角色', tag: 'primary', category: 'PRINCIPAL_ROLE' },
  { value: 'principal_role.catalog_role_revoked', label: '解绑 Catalog 角色', tag: 'warning', category: 'PRINCIPAL_ROLE' },
  { value: 'principal_role.dropped', label: '删主体角色', tag: 'danger', category: 'PRINCIPAL_ROLE' },
  { value: 'catalog_role.created', label: '建 Catalog 角色', tag: 'success', category: 'CATALOG_ROLE' },
  { value: 'catalog_role.updated', label: '改 Catalog 角色', tag: 'warning', category: 'CATALOG_ROLE' },
  { value: 'catalog_role.dropped', label: '删 Catalog 角色', tag: 'danger', category: 'CATALOG_ROLE' },
  { value: 'optimization.policy_updated', label: '优化策略变更', tag: 'info', category: 'POLICY' },
  { value: 'optimization.job_succeeded', label: '优化成功', tag: 'success', category: 'OPTIMIZATION' },
  { value: 'optimization.job_partially_succeeded', label: '优化部分成功', tag: 'warning', category: 'OPTIMIZATION' },
  { value: 'optimization.job_failed', label: '优化失败', tag: 'danger', category: 'OPTIMIZATION' },
  { value: 'optimization.job_canceled', label: '优化取消', tag: 'info', category: 'OPTIMIZATION' },
]

/** 类型下拉按类别分组：38 个类型平铺成一列太长，分组后是「先想类别、再想动作」。 */
const TYPE_GROUPS = computed(() => CATEGORIES
  .map((category) => ({
    label: category.label,
    items: TYPES.filter((item) => item.category === category.value),
  }))
  .filter((group) => group.items.length))

/** 目标类型的显示名。与 `CatalogEvent` 的 TARGET_* 常量对应（键带引号，verify-console 逐字比对）。 */
const TARGET_LABELS = {
  'TABLE': '表',
  'NAMESPACE': 'Namespace',
  'CATALOG': 'Catalog',
  'VIEW': '视图',
  'GRANT': '授权',
  'PRINCIPAL': '主体',
  'ROLE': '角色',
  'JOB': '任务',
}

const filter = reactive({ catalog: '', category: '', type: '' })

/**
 * 是否完成过至少一次成功加载：空态提示只在这个前提下出现，否则和加载失败
 * 分不开——页面挂载的第一次请求还没回来时 `total` 本来就是 0。
 */
const loaded = ref(false)

/**
 * 一页事件的取数函数，交给 {@link useRemotePaging} 按当前页码调用。
 *
 * <p>服务端已经按 id 倒序（新在前）排好，前端不再反转：分页契约的一部分就是
 * 排序稳定，否则翻到第 2 页会看到第 1 页漏掉的行。
 */
async function fetchPage({ page: index, pageSize: size }) {
  const payload = await consoleApi.listEvents({
    catalog: filter.catalog,
    category: filter.category,
    type: filter.type,
    page: index,
    pageSize: size,
  })
  loaded.value = true
  return { items: payload.events || [], total: payload.page?.total ?? 0 }
}

const {
  loading,
  error,
  rows,
  total,
  page,
  pageSize,
  load,
  reload,
  onPageChange,
  onSizeChange,
} = useRemotePaging(fetchPage)

function typeOf(value) {
  return TYPES.find((item) => item.value === value) || { label: value, tag: 'info', category: undefined }
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

/**
 * 换筛选就回第一页重取。
 *
 * <p>三个条件合成一个 watch：换 `category` 时会把不匹配的 `type` 清掉（见下一个
 * watch），拆成两个 watch 会各发一次请求；合成后 Vue 的调度器只跑一次，且读到
 * 的是 cleared 之后的最终值。
 */
watch(() => [filter.catalog, filter.category, filter.type], () => reload())

// 换类别时把不匹配的类型筛选清掉：留着它只会得到一张空表，而看的人会以为
// 「这个类别下没有事件」
watch(() => filter.category, (category) => {
  if (filter.type && typeOf(filter.type).category !== category) {
    filter.type = ''
  }
})

// 顶栏的刷新与页头那个刷新按钮是同一个动作：重取当前页。事件 append-only，
// 这一页不会出现「被改过」的行，但新事件会把旧的下挤一页——刷新后看到的仍
// 是最新那一屏。
onConsoleRefresh(() => load())

// 进这一页时确保 catalog 列表已加载：过滤器里的 Catalog 候选来自它
if (!session.state.catalogsLoaded && !session.state.loadingCatalogs) {
  session.loadCatalogs().catch(() => {})
}
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
