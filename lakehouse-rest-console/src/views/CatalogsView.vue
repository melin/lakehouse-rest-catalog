<template>
  <div class="pc-page">
    <PageHeader
      title="Catalogs"
      subtitle="catalog 是元数据与数据的命名空间，每个 catalog 指向一处存储位置。名称同时是客户端连接时用的 prefix，创建后不能改名；表格式（Paimon / Iceberg）决定它服务哪一套 REST 协议，同样是创建时选定、之后不能改。"
    >
      <template #actions>
        <el-button size="small" :loading="table.loading.value" @click="table.load">
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
          已登记的 catalog（{{ rows.length }}<template v-if="formatFilter"> / {{ allRows.length }}</template>）
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
          <span class="pc-mono pc-dim">管理 API /catalogs</span>
        </span>
      </div>
      <ResourceState
        :loading="table.loading.value"
        :error="table.error.value"
        :empty="!rows.length"
        :empty-text="emptyText"
      >
        <template #actions>
          <el-button size="small" type="primary" @click="openCreate">
            <el-icon><Plus /></el-icon>
            <span class="pc-btn-text">新建 Catalog</span>
          </el-button>
        </template>
        <el-table v-loading="table.loading.value" :data="rows" size="small">
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
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <IconAction icon="Edit" label="编辑" type="primary" @click="openEdit(row)" />
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
      </ResourceState>
    </PanelCard>

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
          <div class="pc-opt-grid">
            <div
              v-for="option in formatOptions"
              :key="option.value"
              class="pc-opt"
              :class="{ 'is-on': dialog.catalogFormat === option.value }"
              @click="dialog.catalogFormat = option.value"
            >
              <div class="pc-opt-name">
                <span class="pc-opt-dot" />
                {{ option.label }}
              </div>
              <div class="pc-opt-desc">{{ option.desc }}</div>
              <div class="pc-opt-meta pc-mono">{{ option.path }}</div>
            </div>
          </div>
          <div class="pc-field-note">{{ catalogFormatNote(dialog.catalogFormat) }}</div>
          <!-- 选 Iceberg 时再多一条：下面的说明讲的是「协议面不一样」，
               这条讲的是「控制台这一期只跟到哪」，两件事不能合成一句——
               合并之后，用户会把「界面没做」读成「Iceberg 不支持」。 -->
          <el-alert
            v-if="isIcebergCatalog(dialog.catalogFormat)"
            class="pc-form-alert"
            type="warning"
            :closable="false"
            show-icon
            title="控制台一期不提供 Iceberg catalog 的表管理与数据浏览"
            description="创建后可以在 catalog 详情查看协议与能力，表数据请通过 Spark 等 Iceberg REST 客户端访问。"
          />
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

      <el-alert
        v-if="!supportedTypes.length"
        type="warning"
        :closable="false"
        show-icon
        class="pc-dialog-alert"
      >
        <template #title>未能读取服务端支持的存储类型</template>
        <div>
          存储类型的可选范围来自 <code class="pc-mono">GET /api/console/v1/meta</code>。
          当前读不到，表单只能给出保守的默认值（FILE），提交时可能被服务端以
          「本部署未接入该存储实现」拒绝。可先到「连接设置」确认地址与令牌。
        </div>
      </el-alert>

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
import { computed, reactive, ref } from 'vue'
import { RouterLink, useRouter } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import StorageConfigFields from '@/components/StorageConfigFields.vue'
import TimeText from '@/components/TimeText.vue'
import { managementApi } from '@/api/management.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh, useResource } from '@/composables/index.js'
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
  PAIMON,
} from '@/utils/catalogFormat.js'

const table = useResource(() => managementApi.listCatalogs())

const router = useRouter()

const allRows = computed(() => table.data.value?.catalogs || [])

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
 * 按筛选值过一遍。
 *
 * <p>判据用 `catalogFormatOf`（认不出的取值按 PAIMON）而不是裸读字段：
 * 筛出来的集合必须与徽标显示的集合**逐行一致**，否则会出现
 * 「筛 Paimon 时那一行不出现、但它在表里写着 Paimon」这种自相矛盾的界面。
 */
const rows = computed(() => (formatFilter.value
  ? allRows.value.filter((row) => catalogFormatOf(row) === formatFilter.value)
  : allRows.value))

/** 筛出 0 条与「一个都没登记」是两件事，空态文案要分开。 */
const emptyText = computed(() => (formatFilter.value
  ? `没有 ${catalogFormatLabel(formatFilter.value)} 格式的 catalog。把上面的筛选切回「全部格式」可以看到其余 catalog。`
  : '还没有登记任何 catalog。新建一个，引擎客户端就能用它连接了。'))

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
    await table.load()
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
    await table.load()
    await session.loadCatalogs()
  } catch (error) {
    notifyError(error)
  }
}

onConsoleRefresh(() => table.load())
</script>
