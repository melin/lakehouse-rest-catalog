<template>
  <div class="pc-page">
    <PageHeader
      title="Catalogs"
      subtitle="catalog 是元数据与数据的命名空间，每个 catalog 指向一处存储位置。名称同时是客户端连接时用的 prefix，创建后不能改名；表格式（Paimon / Iceberg）决定它服务哪一套 REST 协议，同样是创建时选定、之后不能改。"
    >
      <template #actions>
        <!-- 定时刷新（开关 + 节拍）在手动刷新左边：两者是同一件事的自动/手动两种模式 -->
        <AutoRefreshControl
          v-model:enabled="autoEnabled"
          v-model:seconds="autoSeconds"
          :stale-error="staleError"
          :last-at="autoRefreshedAt"
        />
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建 Catalog</span>
        </el-button>
      </template>
    </PageHeader>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">
          已登记的 catalog（{{ total }}<template v-if="formatFilter"> / {{ unfilteredTotal }}</template>）
        </span>
        <span class="pc-list-tools">
          <!-- 判定接口失败时只弱化这一列，不让整个列表跟着报错：
               catalog 列表本身是好的，缺的只是「凭据从哪来」这一个标注 -->
          <span
            v-if="credentialSourceError"
            class="cred-source-warn"
            :title="credentialSourceError"
          >凭据来源暂不可用</span>
          <!--
            格式筛选。放在清单表头而不是页头：它只影响这张表的内容，
            跟着表格走才不会被读成「换一个全局的当前格式」。
            「全部」是一个真的取值（空串）而不是「不显示筛选器」——
            选完格式之后要能回到全量，而筛出 0 条时用户更需要看到这个入口。
          -->
          <el-select v-model="formatFilter" size="small" class="pc-format-filter">
            <el-option
              v-for="option in FORMAT_FILTERS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </span>
      </div>
      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        :empty-text="emptyText"
      >
        <template #actions>
          <!--
            「列表为空」有两种完全不同的成因，而响应体里没有区分它们的字段
            （服务端刻意不返回「过滤掉了 N 条」，否则不可见的 catalog 会通过条数泄露出去）。
            因此这里的文案只做条件式说明：可能真的没登记，也可能是当前主体看不见。
          -->
          <p v-if="!formatFilter" class="pc-empty-hint">
            如果服务端确实登记过 catalog，多半是当前登录主体
            <span class="pc-mono">{{ auth.state.principal || '（未认证）' }}</span>
            在任何一个 catalog 上都没有赋权：开启授权后，服务端会把不可见的 catalog
            从列表里过滤掉，于是「库里有数据、页面是空」是一个正常结论，不是查询失败。
            要让它出现，把该主体加入
            <span class="pc-mono">lakehouse.rest.authorization.service-admins</span>，
            或在某个 catalog 上给它授权。
          </p>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>
            <span class="pc-btn-text">新建 Catalog</span>
          </el-button>
        </template>
        <el-table v-loading="loading" :data="rows" size="small">
          <el-table-column label="名称" min-width="150">
            <template #default="{ row }">
              <RouterLink class="pc-link" :to="{ name: 'catalogDetail', params: { catalogName: row.name } }">
                {{ row.name }}
              </RouterLink>
            </template>
          </el-table-column>
          <!--
            表格式紧跟在名称后面（效果图的列序），「类型」保持在它右边：
            两条正交的属性，`EXTERNAL + ICEBERG`（外部 Iceberg 仓库的入口）
            是「类型」那一列单独表达不了的状态，因此两列都要有。
          -->
          <el-table-column label="表格式" width="112">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="catalogFormatTagType(row)">
                {{ catalogFormatLabel(row) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="type" label="类型" width="100" />
          <!--
            执行集群。两行表达两件事：能不能跑（状态 chip）＋跑在哪（类型 · namespace）。
            未配置的行副行写「优化不会运行」——这一列存在的全部意义就是这件事：
            在此之前，一个 catalog 的优化会不会跑，只能靠「点进去看看」才知道。
          -->
          <el-table-column label="执行集群" width="196">
            <template #default="{ row }">
              <div class="exec-cell">
                <el-tooltip :content="execCell(row).hint" placement="top" :show-after="300">
                  <el-tag size="small" effect="plain" :type="execCell(row).tag">
                    {{ execCell(row).label }}
                  </el-tag>
                </el-tooltip>
                <span
                  class="exec-meta"
                  :class="{ 'is-missing': !execCell(row).configured }"
                >{{ execCell(row).detail }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="存储类型" width="104">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">
                {{ row.storageConfigInfo?.storageType || '—' }}
              </el-tag>
            </template>
          </el-table-column>
          <!--
            凭据来源。这一列的存在理由只有一条：**后两档在客户端配置上完全一样**
            （转发长期密钥 / 短期签发都不用改 Spark 配置），差别全在服务端发的是哪把钥匙。
            如果只在详情的折叠区里写，所有人的认知都会停在默认值上
            （docs/credential-vending-design.md §10.1）。
          -->
          <el-table-column label="凭据来源" width="188">
            <template #default="{ row }">
              <div class="cred-cell">
                <el-tooltip :content="credCell(row).hint" placement="top" :show-after="300">
                  <el-tag size="small" effect="plain" :type="credCell(row).tag">
                    {{ credCell(row).label }}
                  </el-tag>
                </el-tooltip>
                <span class="cred-meta" :title="credCell(row).detail">{{ credCell(row).detail }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="仓库位置" min-width="230">
            <template #default="{ row }">
              <span class="pc-mono pc-break">{{ warehouseOf(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="160">
            <template #default="{ row }">
              <TimeText :millis="row.lastUpdateTimestamp" mode="relative" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <IconAction icon="Edit" label="编辑" type="primary" @click="openEdit(row)" />
              <!--
                未配置时用主色描边：那一刻它是**待办**，不是可选动作。
                配好之后退回默认样式，因为此后的常用动作是「验证」而不是「再配一遍」。
              -->
              <IconAction
                icon="Platform"
                label="执行集群配置"
                :type="execCell(row).configured ? '' : 'primary'"
                @click="openExecutionCluster(row)"
              />
              <IconAction
                icon="Files"
                label="浏览库与对象"
                @click="openBrowse(row)"
              />
              <IconAction
                icon="Lock"
                label="权限管理"
                @click="openPermissions(row)"
              />
              <IconAction icon="Delete" label="删除" type="danger" @click="confirmDelete(row)" />
            </template>
          </el-table-column>
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

    <!-- 执行集群配置（优化任务提交到哪个 k8s） -->
    <ExecutionClusterDrawer
      v-model="execDrawer.open"
      :catalog-name="execDrawer.catalog"
      @saved="onExecutionClusterSaved"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import AutoRefreshControl from '@/components/AutoRefreshControl.vue'
import ExecutionClusterDrawer from '@/components/ExecutionClusterDrawer.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import { consoleApi } from '@/api/console.js'
import { managementApi } from '@/api/management.js'
import {
  confirmDanger,
  notifyError,
  notifySuccess,
  onConsoleRefresh,
  useAutoRefresh,
  useRemotePaging,
} from '@/composables/index.js'
import { session } from '@/store/session.js'
import {
  catalogFormatLabel,
  catalogFormatTagType,
  ICEBERG,
  PAIMON,
} from '@/utils/catalogFormat.js'
import { clusterCell } from '@/utils/executionCluster.js'
import { credentialSourceCell, indexCredentialSources } from '@/utils/credentialSource.js'

/**
 * catalog 表走远程分页。格式筛选**下推服务端**（`?format=`）：
 * 客户端手里只有一页，在浏览器里筛只能筛出「这一页里的 Paimon」，
 * 与「全站的 Paimon」是两回事——而且总数会随页码变化，看着就像分页坏了。
 */
const {
  rows,
  total,
  page,
  pageSize,
  loading,
  error,
  staleError,
  load,
  reload,
  silentLoad,
  onPageChange,
  onSizeChange,
} = useRemotePaging(({ page: current, pageSize: size }) => managementApi
  .listCatalogs({ page: current, pageSize: size, format: formatFilter.value })
  .then((payload) => ({
    items: payload.catalogs || [],
    total: payload.page?.total ?? (payload.catalogs || []).length,
  })))

const router = useRouter()

/**
 * 不带筛选时的总数。只有筛选中才显示，用来在标题里说清「筛出来几个 / 一共几个」。
 * 单独取一次（不分页，`total` 就是全量条数），筛过之后这个数不该跟着变。
 */
const unfilteredTotal = ref(0)

// 一次轻量请求取回「一共几个 catalog」（`pageSize=1` 只为拿 `page.total`）。
// 筛选之后标题要能写出「筛出 3 / 共 12」，而这个分母不能跟着筛选变。
async function loadUnfilteredTotal() {
  try {
    const payload = await managementApi.listCatalogs({ page: 1, pageSize: 1 })
    unfilteredTotal.value = payload.page?.total ?? 0
  } catch {
    // 分母取不到就不显示——比显示一个 0 强
    unfilteredTotal.value = 0
  }
}

onMounted(loadUnfilteredTotal)

/**
 * 顶部格式筛选器的候选。空串是「全部」，不是「不筛选」——见模板里的说明。
 *
 * <p>标签写「Paimon 协议面 / Iceberg 协议面」而不是光写名字：这一列筛的是
 * **catalog 服务哪套 REST 协议**，而「Paimon」在同一个界面上也可能被读成表格式
 * （Format / Object / Lance 那些是表的格式，与这里无关）。
 */
const FORMAT_FILTERS = [
  { value: '', label: '全部格式' },
  { value: PAIMON, label: `${catalogFormatLabel(PAIMON)} 协议面` },
  { value: ICEBERG, label: `${catalogFormatLabel(ICEBERG)} 协议面` },
]

const formatFilter = ref('')

/**
 * 筛选值一变就回第一页重取。`total` 也随之变成「筛出来的条数」——
 * 这正是界面要的：标题里写的是「已登记的 catalog」，筛选之后问的是
 * 「Paimon 有几个」。
 */
watch(formatFilter, () => {
  reload()
})

/**
 * 筛出 0 条、一个都没登记、以及「登记了但当前主体看不见」是三件事。
 *
 * <p>第三种以前被写成了第一、二种之一（「还没有登记任何 catalog」）——那句断言在
 * 开着授权、调用者又没有任何赋权时是**假话**：库里明明有行，页面却说他没登记过。
 * 现在主文案只说「可见」范围内的结论，成因交给空态里的条件式说明去解释。
 */
const emptyText = computed(() => (formatFilter.value
  ? `没有 ${catalogFormatLabel(formatFilter.value)} 格式的 catalog。把上面的筛选切回「全部格式」可以看到其余 catalog。`
  : '没有可见的 catalog。'))

function warehouseOf(row) {
  return row.storageConfigInfo?.allowedLocations?.[0] || row.properties?.warehouse || '—'
}

/**
 * 执行集群那一格的内容。
 *
 * <p>抽成函数而不是在模板里内联三次：同一行里 chip、副行、tooltip 与按钮样式都要用
 * 同一份判断，各算一次迟早出现「chip 说未配置、按钮不是主色」这种前后不一致。
 * 判定本身仍在服务端（见 `utils/executionCluster.js` 的说明）。
 */
function execCell(row) {
  return clusterCell(row.executionCluster)
}

/**
 * 「凭据来源」那一列。
 *
 * <p>判定不在这里做，只在 `utils/credentialSource.js` 里翻译：判据要读服务端配置
 * （具名存储、默认凭据、启用的 provider），前端拿不到；而把优先级抄进 JS 一定会
 * 与凭据管理器漂移，漂移的表现是「界面说在签发、实际在转发」。
 */
const credentialSources = ref({})

/** 判定接口失败时的原因。**不能让它把列表带下水**：catalog 列表本身是好的。 */
const credentialSourceError = ref('')

async function loadCredentialSources() {
  try {
    credentialSources.value = indexCredentialSources(await consoleApi.credentialSources())
    credentialSourceError.value = ''
  } catch (err) {
    // 保留上一次的结果：行还在，旧判定比空白更接近事实（配置变化本来就不频繁）
    credentialSourceError.value = err?.message || '凭据来源判定接口不可用'
  }
}

function credCell(row) {
  return credentialSourceCell(credentialSources.value[row.name])
}

// 每次列表拿到新的一页就跟一次判定：这件事不访问存储、不调云，
// 但它的结果会随「刚保存了 roleArn / 刚换了具名存储」而变，缓存久了就成假话。
// 手动刷新与定时刷新都走 reload()/load()（见文件末尾的 onConsoleRefresh），
// 因此这里只需要盯着 rows 的替换，不必再挂一次刷新回调。
watch(rows, (list) => {
  if (list?.length) {
    loadCredentialSources()
  }
})

/**
 * 执行集群抽屉的状态。
 *
 * <p>记 catalog 名而不是整行对象：抽屉打开期间列表可能被刷新（定时刷新、别处改了配置），
 * 持有旧行对象会让抽屉里的 `entityVersion` 变成陈旧的，保存时得到一个莫名其妙的 409。
 */
const execDrawer = reactive({ open: false, catalog: '' })

function openExecutionCluster(row) {
  execDrawer.catalog = row.name
  execDrawer.open = true
}

/**
 * 抽屉保存/探活之后刷新列表。
 *
 * <p>刷新是为了让那一列的 chip 跟上（保存会让状态回到「待验证」，探活会把它变成
 * 「正常」或「异常」）。这里只重取当前页：`reload()` 保留分页与筛选，
 * 换成 `load()` 会把用户翻到第 3 页的位置丢掉。
 */
function onExecutionClusterSaved() {
  reload()
}

/**
 * 新建与编辑都跳到独立页面（`CatalogFormView`），不再开弹窗。
 *
 * <p>理由见那一页的模块注释：这一页要给的不只是表单，还有右栏常驻的判定摘要与
 * 可复制的接入配置，而弹窗一滚就把它们滚没了。
 */
function openCreate() {
  router.push({ name: 'catalogCreate' })
}

/**
 * 跳到「目录浏览」并把这个 catalog 设为工作目录。
 *
 * <p>浏览页的当前 catalog 读的是 `route.query.catalog`，缺省回落到
 * `session.selectedCatalog`。两处都写：只写 query 的话，顶栏的目录选择器
 * 会停在旧值上；只写 session 的话，深链接与刷新拿不到这个 catalog。
 */
function openBrowse(row) {
  session.selectCatalog(row.name)
  router.push({ name: 'browse', query: { catalog: row.name } })
}

/**
 * 跳到「权限管理」并把这个 catalog 设为工作目录。
 *
 * <p>权限管理页读的是 `session.selectedCatalog`（它自己也是一个全局选择器的目标），
 * 所以这里先切目录再跳转，否则落地后会停在另一个 catalog 上。
 */
function openPermissions(row) {
  session.selectCatalog(row.name)
  router.push({ name: 'permissions' })
}

function openEdit(row) {
  router.push({ name: 'catalogEdit', params: { catalogName: row.name } })
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除 Catalog',
    target: `确认删除 catalog「${row.name}」？`,
    detail: '服务端会一并删除这个 catalog 下的角色与授权关系。仓库里的数据文件不受影响，'
      + '但如果仍有客户端在用这个 prefix，它们的请求会开始失败。',
  })
  if (!ok) return
  try {
    await managementApi.deleteCatalog(row.name)
    notifySuccess(`已删除 catalog ${row.name}`)
    await reload()
    await session.loadCatalogs()
  } catch (error) {
    notifyError(error)
  }
}

onConsoleRefresh(() => reload())

// ------------------------------------------------------------------ 定时刷新
//
// 这一屏最该自动刷新的东西是「执行集群」那一列：探活与保存都在别处发生，
// 而这张表是唯一能一眼看到「哪个 catalog 的优化不会跑」的地方。
//
// 两处静默：
// ① 列表走 `silentLoad`（不出遮罩、失败不清空表格）；
// ② 标题里的分母只在**筛选生效时**才跟着取（没筛选时它不显示，
//    每 5 秒多打一次请求纯属浪费）。
const {
  enabled: autoEnabled,
  seconds: autoSeconds,
  lastAt: autoRefreshedAt,
} = useAutoRefresh(async () => {
  await silentLoad()
  if (formatFilter.value) {
    await loadUnfilteredTotal()
  }
}, {
  page: 'catalogs',
})
</script>

<style scoped>
/* 空态里的成因说明：比 el-empty 的 description 长，因此单独一段并限宽，
 * 不让它把「新建 Catalog」按钮推得很远。 */
.pc-empty-hint {
  max-width: 560px;
  margin: 0 auto 12px;
  font-size: 12px;
  line-height: 1.75;
  color: var(--pc-text-dim);
  text-align: left;
}

/* 执行集群那一格：chip 在上、位置在下。
 * 用两行而不是一行「正常 · EKS · paimon-opt」是刻意的：这一列回答的是两个不同的问题——
 * 「会不会跑」与「跑在哪」，前者要能一眼扫过去（对齐的 chip），后者是次要信息。 */
.exec-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  align-items: flex-start;
}

.exec-meta {
  font-size: 12px;
  color: var(--pc-text-dim);
  overflow-wrap: anywhere;
}

/* 未配置时的副行用主色而不是灰：这一行的优化永远不会跑，是待办而不是「没有信息」 */
.exec-meta.is-missing {
  color: var(--el-color-primary);
}

/* 凭据来源那一格：与执行集群同一套两行结构（chip 在上、分支说明在下）。
 * 副行限两行：说明可能很长（「转发具名存储 xxx 的密钥…」），
 * 三行会把行高撑到跟别的行不齐，完整内容留给 tooltip。 */
.cred-cell {
  display: flex;
  flex-direction: column;
  gap: 3px;
  align-items: flex-start;
}

.cred-meta {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.5;
  color: var(--pc-text-dim);
  overflow-wrap: anywhere;
}

/* 判定接口不可用：只在表头出现一小行灰字。列表本身是好的，
 * 因此不用 TipAlert、不用红色——那会把「一个标注缺失」读成「整页出错」。 */
.cred-source-warn {
  font-size: 12px;
  color: var(--pc-text-dim);
  cursor: help;
}
</style>
