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

    <!-- 新建 / 编辑 -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.mode === 'create' ? '新建 Catalog' : `编辑 Catalog：${dialog.name}`"
      width="620px"
      :close-on-click-modal="false"
      top="6vh"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item v-if="dialog.mode === 'create'" required>
          <template #label>
            <FieldLabel label="名称（name / prefix）" tip="创建后不可改名，改名等同于删除重建。" />
          </template>
          <el-input v-model="dialog.name" placeholder="例如 paimon，之后所有 /v1/{prefix}/... 都用它" />
        </el-form-item>

        <!--
          表格式。**只在创建时可选**：服务端把它当不可变列（改格式 = 删除重建），
          编辑态因此不是一个禁用的下拉，而是一行只读展示——一个灰着的选择框
          会让人以为「再点点看能不能改」，而这件事在服务端根本不存在。

          用选项卡片而不是下拉（效果图见 `outputs/iceberg-rest-catalog/mockup.html`）：
          两个选项各自的**后果**要能在选之前读完——客户端走哪段路径、控制台能显示什么。
          下拉里塞不下这些，于是它们只能退化成 tooltip，而没人会为了选一个值去点问号。
        -->
        <el-form-item v-if="dialog.mode === 'create'" required>
          <template #label>
            <FieldLabel
              label="表格式（catalogFormat）"
              tip="决定这个 prefix 服务哪一套 REST 协议：Paimon REST 或 Iceberg REST。创建后不可更改，要换格式请删除重建。"
            />
          </template>
          <div class="pc-pick-grid">
            <div
              v-for="option in formatOptions"
              :key="option.value"
              class="pc-pick"
              :class="{ 'is-on': dialog.catalogFormat === option.value }"
              @click="dialog.catalogFormat = option.value"
            >
              <div class="pc-pick-name">
                <span class="pc-pick-dot" />
                {{ option.label }}
              </div>
              <div class="pc-pick-desc">{{ option.desc }}</div>
              <div class="pc-pick-meta pc-mono">{{ option.path }}</div>
            </div>
          </div>
          <div class="pc-field-note">{{ catalogFormatNote(dialog.catalogFormat) }}</div>
          <!-- 选 Iceberg 时再多一条：下面的说明讲的是「协议面不一样」，
               这条讲的是「控制台这一期只跟到哪」，两件事不能合成一句——
               合并之后，用户会把「界面没做」读成「Iceberg 不支持」。 -->
          <TipAlert
            v-if="isIcebergCatalog(dialog.catalogFormat)"
            class="pc-form-alert"
            type="warning"
            title="控制台一期不提供 Iceberg catalog 的表管理与数据浏览"
          >
            创建后可以在 catalog 详情查看协议与能力，表数据请通过 Spark 等 Iceberg REST 客户端访问。
          </TipAlert>
          <!-- Lance 的提示与 Iceberg 那条分开写：Iceberg 是「控制台一期没做」，
               Lance 是「这台服务端本来就不写数据集」——前者以后会补，后者是设计如此。 -->
          <TipAlert
            v-if="isLanceCatalog(dialog.catalogFormat)"
            class="pc-form-alert"
            type="warning"
            title="控制台不提供 Lance catalog 的库表浏览"
          >
            Lance 数据集由客户端写进仓库，服务端只做登记（declare / register）。创建后可以在
            catalog 详情看协议与能力、在「文件列表」里看 <code class="pc-mono">_versions/</code> 与
            <code class="pc-mono">data/</code>；库与表请用 Lance 客户端访问。
          </TipAlert>
        </el-form-item>

        <el-form-item v-if="dialog.mode === 'edit'">
          <template #label>
            <FieldLabel
              label="表格式（catalogFormat）"
              tip="创建后不可更改：同一个 prefix 下两种格式的元数据代数、仓库产物与客户端可见的端点都不同，改一个列等于让所有已建对象的语义整体漂移。要换格式请删除重建。"
            />
          </template>
          <div class="pc-readonly-row">
            <el-tag size="small" effect="plain" :type="catalogFormatTagType(dialog.catalogFormat)">
              {{ catalogFormatLabel(dialog.catalogFormat) }}
            </el-tag>
            <span class="pc-dim">创建后不可更改</span>
          </div>
        </el-form-item>

        <el-form-item v-if="dialog.mode === 'create'" required>
          <template #label>
            <FieldLabel
              label="类型（type）"
              tip="INTERNAL 由本服务管理元数据；EXTERNAL 只作为已有仓库的入口。与表格式正交，可组合。"
            />
          </template>
          <el-select v-model="dialog.type" class="pc-full">
            <el-option v-for="type in catalogTypes" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>

        <el-form-item v-if="dialog.mode === 'edit'">
          <template #label>
            <FieldLabel
              label="当前版本（currentEntityVersion）"
              tip="并发保护：版本与服务端不一致时更新会被拒绝（409），此时请刷新后重试。"
            />
          </template>
          <el-input :model-value="String(dialog.entityVersion)" disabled />
        </el-form-item>
      </el-form>

      <TipAlert
        v-if="!supportedTypes.length"
        type="warning"
        class="pc-dialog-alert"
      >
        <template #title>未能读取服务端支持的存储类型</template>
        <div>
          存储类型的可选范围来自 <code class="pc-mono">GET /api/console/v1/meta</code>。
          当前读不到，表单只能给出保守的默认值（FILE），提交时可能被服务端以
          「本部署未接入该存储实现」拒绝。可先到「连接设置」确认地址与令牌。
        </div>
      </TipAlert>

      <SectionTitle title="存储配置（storageConfigInfo）" />
      <!--
        key 让每次打开都拿到一个新实例：本组件的表单是内部副本，
        靠 props 同步（见组件内的说明），这里是第三层保险——极端情况下
        （例如同一份 storageConfigInfo 对象被连续两次传入、引用未变）
        重建实例比依赖 watch 更不容错。
      -->
      <StorageConfigFields
        :key="dialog.mode === 'create' ? 'create' : `edit:${dialog.name}`"
        ref="storageForm"
        v-model="dialog.storageConfigInfo"
        :storage-types="storageTypes"
        :supported-types="supportedTypes"
        :static-credentials-enabled="staticCredentialsEnabled"
      />

      <SectionTitle title="目录属性（properties）" />
      <PropertiesEditor v-model="dialog.properties" hint="例如 owner、warehouse；留空即可" />

      <template #footer>
        <el-button @click="dialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dialog.saving" @click="save">
          {{ dialog.mode === 'create' ? '创建' : '保存' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import TipAlert from '@/components/TipAlert.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import AutoRefreshControl from '@/components/AutoRefreshControl.vue'
import ExecutionClusterDrawer from '@/components/ExecutionClusterDrawer.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import StorageConfigFields from '@/components/StorageConfigFields.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import { managementApi } from '@/api/management.js'
import {
  confirmDanger,
  notifyError,
  notifySuccess,
  onConsoleRefresh,
  useAutoRefresh,
  useRemotePaging,
} from '@/composables/index.js'
import { auth } from '@/store/auth.js'
import { session } from '@/store/session.js'
import {
  CATALOG_FORMATS,
  catalogFormatLabel,
  catalogFormatNote,
  catalogFormatOf,
  catalogFormatOption,
  catalogFormatTagType,
  ICEBERG,
  isIcebergCatalog,
  isLanceCatalog,
  PAIMON,
} from '@/utils/catalogFormat.js'
import { clusterCell } from '@/utils/executionCluster.js'

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

const meta = computed(() => session.state.meta)
const catalogTypes = computed(() => meta.value?.enums?.catalogTypes || ['INTERNAL', 'EXTERNAL'])
const storageTypes = computed(() => meta.value?.enums?.storageTypes || ['S3', 'GCS', 'AZURE', 'OBS', 'OSS', 'FILE'])
const supportedTypes = computed(() => meta.value?.enums?.supportedStorageTypes || [])
/**
 * 本部署能否保存静态凭据（服务端配了落库加密密钥）。
 *
 * <p>取不到元数据时按 false 处理：乐观放开的代价是用户填完 AK/SK 再吃一个 400，
 * 而保守禁用的代价只是「暂时不能填」，且表单会说明原因。
 */
const staticCredentialsEnabled = computed(() => meta.value?.enums?.staticCredentialsEnabled === true)

/**
 * 可选的表格式，来自 `GET /api/console/v1/meta` 的 `enums.catalogFormats`
 * （服务端 `CatalogFormat` 枚举的下发）。
 *
 * <p>读不到元数据时用 `CATALOG_FORMATS` 兜底，与上面的存储类型同一个策略：
 * 这两个取值是协议面的名字，不是部署相关的配置，兜底不会选出一个服务端不认的值。
 */
const catalogFormats = computed(() => meta.value?.enums?.catalogFormats || CATALOG_FORMATS)

/**
 * 选项卡片的内容：服务端给候选，前端只补「怎么描述它」。
 *
 * <p>**候选不在这里写死**（服务端以后多加一种格式时，界面应当把它列出来），
 * 描述走 `catalogFormatOption` 的兜底——认不出的取值照实显示取值本身，
 * 而不是套用 Paimon 的说明。
 */
const formatOptions = computed(() => catalogFormats.value.map((value) => ({
  value,
  ...catalogFormatOption(value),
})))

/**
 * 本部署建 catalog 时未指定格式用的是哪种（`lakehouse.rest.default-catalog-format`）。
 *
 * <p>用它**预选**新建表单里的那一项，而不是恒选第一项：默认值最终由服务端决定，
 * 前端猜一个在部署改过默认时会与服务端不一致，表现为
 * 「表单里选的是 Paimon，建出来却是 Iceberg」。
 */
const defaultCatalogFormat = computed(() => meta.value?.service?.defaultCatalogFormat || PAIMON)

const storageForm = ref(null)

const dialog = reactive({
  open: false,
  mode: 'create',
  saving: false,
  name: '',
  type: 'INTERNAL',
  catalogFormat: PAIMON,
  entityVersion: 0,
  properties: {},
  storageConfigInfo: {},
})

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

function openCreate() {
  dialog.mode = 'create'
  dialog.name = ''
  dialog.type = 'INTERNAL'
  dialog.catalogFormat = defaultCatalogFormat.value
  dialog.entityVersion = 0
  dialog.properties = {}
  dialog.storageConfigInfo = { storageType: supportedTypes.value[0] || 'FILE', allowedLocations: [] }
  dialog.open = true
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
  dialog.mode = 'edit'
  dialog.name = row.name
  dialog.type = row.type
  // 只读展示用（编辑表单不回传它）：服务端把格式当不可变列，改的话返回 400
  dialog.catalogFormat = catalogFormatOf(row)
  dialog.entityVersion = row.entityVersion ?? 0
  dialog.properties = { ...(row.properties || {}) }
  dialog.storageConfigInfo = row.storageConfigInfo
    ? JSON.parse(JSON.stringify(row.storageConfigInfo))
    : fallbackStorage(row)
  dialog.open = true
}

/**
 * 服务端没有返回 `storageConfigInfo` 时的兜底（早期版本登记的 catalog）。
 *
 * <p>不能直接给一个空对象：表单会在 `allowedLocations` 为空的校验上卡住，
 * 用户被迫手填一个本来就已知的位置。仓库位置通常就在 `properties.warehouse` 里，
 * 把它回填成首个允许位置——**回填而不是改写**，两者语义不同，
 * 用户看到值并确认，比表单凭空替他决定要好。
 */
function fallbackStorage(row) {
  const warehouse = row.properties?.warehouse
  return {
    storageType: supportedTypes.value[0] || 'FILE',
    allowedLocations: warehouse ? [warehouse] : [],
  }
}

async function save() {
  if (dialog.mode === 'create' && !dialog.name.trim()) {
    notifyError(new Error('请填写 catalog 名称'))
    return
  }
  const invalid = storageForm.value?.validate()
  if (invalid) {
    notifyError(new Error(invalid))
    return
  }
  const storageConfigInfo = storageForm.value?.build() ?? dialog.storageConfigInfo

  dialog.saving = true
  try {
    if (dialog.mode === 'create') {
      await managementApi.createCatalog({
        type: dialog.type,
        name: dialog.name.trim(),
        // 格式创建后不可变，因此**必须**在建表这一步就带上；
        // 不带的话服务端会用部署默认值，于是「表单里看到的」与「建出来的」可能不是同一个
        catalogFormat: dialog.catalogFormat,
        properties: dialog.properties,
        storageConfigInfo,
      })
      notifySuccess(`已创建 catalog ${dialog.name.trim()}（${catalogFormatLabel(dialog.catalogFormat)} 协议面）`)
    } else {
      // 编辑**不带** catalogFormat：服务端把改格式当 400 拒绝，
      // 前端连字段都不发，免得看起来像是「发了但没生效」
      await managementApi.updateCatalog(dialog.name, {
        currentEntityVersion: dialog.entityVersion,
        properties: dialog.properties,
        storageConfigInfo,
      })
      notifySuccess(`已更新 catalog ${dialog.name}`)
    }
    dialog.open = false
    await reload()
    await session.loadCatalogs()
  } catch (error) {
    notifyError(error)
  } finally {
    dialog.saving = false
  }
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
  // 新建/编辑表单打开时不刷：表格在弹层后面没人看，而刷新只会让
  // 行里的 `entityVersion` 与表单里那份悄悄分叉，保存时得到一个莫名其妙的 409
  blocked: () => dialog.open,
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
</style>
