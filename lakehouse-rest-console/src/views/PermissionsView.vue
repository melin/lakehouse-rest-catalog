<template>
  <div class="pc-page">
    <PageHeader
      title="权限管理"
      subtitle="当前 catalog 的授权清单：谁被允许对哪个 catalog、库（Iceberg 为 namespace）、表或视图做什么，逐条可撤销。这里把权限直接授给用户、用户组或角色；服务端另有一条「主体 → 角色 → catalog 角色」的授权链（由 Spark SQL 与管理 API 维护），实际权限取两者并集。"
    >
      <template #actions>
        <el-button type="primary" size="small" :disabled="!catalogName" @click="openDialog">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">添加授权</span>
        </el-button>
        <el-button size="small" :disabled="!catalogName" :loading="listLoading" @click="loadList">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="!catalogName" type="warning" :closable="false" show-icon>
      <template #title>请先在右上角选择一个 catalog</template>
      <div>权限按 catalog 隔离，列表与授权都作用在当前选中的 catalog 上。</div>
    </el-alert>

    <template v-else>
      <!------------------------------ 授权清单 ------------------------------>
      <PanelCard flush>
        <div class="pc-list-head">
          <span class="pc-list-title">授权清单（{{ listTotal }}）</span>
          <el-input
            v-model="filterText"
            size="small"
            clearable
            placeholder="按 principal / 库 / 表过滤"
            class="pc-filter"
          />
          <span class="pc-mono pc-dim">catalog API /{{ catalogName }}/permissions</span>
        </div>

        <ResourceState
          :loading="listLoading"
          :error="listError"
          :empty="!filteredRows.length"
          :empty-text="emptyText"
        >
          <el-table
            v-loading="listLoading"
            :data="filteredRows"
            size="small"
          >
            <el-table-column label="Principal" min-width="210">
              <template #default="{ row }">
                <span class="pc-mono">{{ row.principal }}</span>
                <el-tag size="small" effect="plain" :type="row.typeTag" class="pc-kind">
                  {{ row.typeLabel }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="作用范围" min-width="240">
              <template #default="{ row }">
                <span class="pc-mono">{{ row.scope }}</span>
                <span class="pc-scope-kind">{{ row.scopeKind }}</span>
              </template>
            </el-table-column>
            <!-- 一行 = 一个主体 × 一个作用范围，权限并列成标签；
                 撤销跟着标签走（× 号），一次只撤一项权限 -->
            <el-table-column label="权限" min-width="260">
              <template #default="{ row }">
                <div class="pc-access-tags">
                  <el-tag
                    v-for="item in row.items"
                    :key="item.access"
                    size="small"
                    effect="plain"
                    type="info"
                    closable
                    @close="revokeAccess(row, item)"
                  >
                    {{ item.label }}
                  </el-tag>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="过期时间" width="200">
              <template #default="{ row }">
                <!-- 授权按「到哪一天」授予，清单也就按日期读：完整时刻放进 tooltip -->
                <template v-if="row.expireMillis">
                  <el-tooltip :content="formatTime(row.expireMillis)" placement="top">
                    <span class="pc-mono">{{ formatDate(row.expireMillis) }}</span>
                  </el-tooltip>
                  <el-tag v-if="isExpired(row.expireMillis)" size="small" type="danger" effect="plain" class="pc-kind">
                    已过期
                  </el-tag>
                  <span v-else-if="daysUntil(row.expireMillis) <= 7" class="pc-scope-kind">
                    （{{ daysUntil(row.expireMillis) }} 天后）
                  </span>
                  <span v-if="row.expireMixed" class="pc-scope-kind">（取最早）</span>
                </template>
                <span v-else-if="row.expireRaw" class="pc-mono">{{ row.expireRaw }}</span>
                <span v-else class="pc-dim">长期有效</span>
              </template>
            </el-table-column>
            <!-- 首次加载时列表还没数据、ResourceState 走 loading 分支照常渲染表格，
                 这里把 Element 自带的空文案顶掉，免得它在遮罩下闪一下 -->
            <template #empty>
              <span />
            </template>
          </el-table>

          <TablePager
            :total="listTotal"
            :page="page"
            :page-size="pageSize"
            @page-change="onPageChange"
            @size-change="onSizeChange"
          />

          <template #actions>
            <el-button size="small" type="primary" @click="openDialog">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">添加授权</span>
            </el-button>
          </template>
        </ResourceState>
      </PanelCard>

      <!------------------------------ 添加授权（弹窗） ------------------------------>
      <el-dialog
        v-model="dialogOpen"
        title="添加授权"
        width="660px"
        :close-on-click-modal="false"
        destroy-on-close
      >
        <el-form label-position="top" size="small" class="pc-form pc-form-grid pc-grant-form">
          <el-form-item label="授权对象" required>
            <el-select
              v-model="form.grantees"
              multiple
              filterable
              allow-create
              default-first-option
              class="pc-full"
              placeholder="选择或直接输入 principal，如 user:alice / role:analyst"
            >
              <el-option-group v-for="group in granteeGroups" :key="group.label" :label="group.label">
                <el-option v-for="item in group.options" :key="item.value" :label="item.label" :value="item.value" />
              </el-option-group>
            </el-select>
          </el-form-item>

          <!-- 「作用范围」就是提交时的资源定位器：四级对应官方的
               CATALOG / DATABASE / TABLE / VIEW 四种资源类型，各自的权限词表不同，
               因此先定层级、再选对象、最后勾权限。 -->
          <el-form-item label="作用范围" required>
            <el-radio-group v-model="form.scope" @change="onScopeChange">
              <el-radio-button value="catalog">Catalog</el-radio-button>
              <el-radio-button value="database">{{ scopeTerm }}</el-radio-button>
              <el-radio-button value="table">Table</el-radio-button>
              <el-radio-button value="view">View</el-radio-button>
            </el-radio-group>
          </el-form-item>

          <!-- catalog 级授权不需要再选库：定位符就是 catalog 自己 -->
          <el-form-item v-if="form.scope !== 'catalog'" :label="scopeTerm" required>
            <el-select
              v-model="form.database"
              filterable
              class="pc-full"
              :placeholder="`选择${scopeTerm}`"
              :loading="databasesLoading"
              @change="onDatabaseChange"
            >
              <el-option v-for="name in databases" :key="name" :label="name" :value="name" />
            </el-select>
            <!-- Iceberg 的 namespace 是多级的（`accounting.tax`）：点号形式既是显示口径，
                 也是提交给 `resource.database` 的值——服务端就是按点号把它拼进库名那一列的 -->
            <div v-if="icebergCatalog" class="pc-scope-kind pc-field-hint">
              多级 namespace 用点号显示（如 <span class="pc-mono">accounting.tax</span>），
              授在上级即覆盖其全部下级。
            </div>
          </el-form-item>

          <el-form-item
            v-if="isObjectScope"
            :label="form.scope === 'view' ? 'View' : 'Table'"
            required
          >
            <el-select
              v-model="form.objects"
              multiple
              filterable
              clearable
              collapse-tags
              collapse-tags-tooltip
              class="pc-full"
              :placeholder="form.database ? '选择要授权的对象（可多选）' : `先选择${scopeTerm}`"
              :disabled="!form.database"
              :loading="objectsLoading"
            >
              <el-option v-for="name in objectOptions" :key="name" :label="name" :value="name" />
            </el-select>
          </el-form-item>

          <!-- 权限项跟着作用范围走：四级各一组（标签也随范围变），
               因此不需要再写一段「选了表就只能授表权限」的说明。 -->
          <el-form-item :label="permLabel">
            <div class="pc-perms">
              <template v-if="form.scope === 'catalog'">
                <el-checkbox v-model="catalogPerms.CREATEDATABASE" size="small">Create database</el-checkbox>
                <el-checkbox v-model="catalogPerms.ALTER" size="small">Alter</el-checkbox>
                <el-checkbox v-model="catalogPerms.DROP" size="small">Drop</el-checkbox>
                <el-checkbox v-model="catalogPerms.GRANT" size="small">Grant</el-checkbox>
              </template>
              <template v-else-if="form.scope === 'database'">
                <el-checkbox v-model="dbPerms.CREATETABLE" size="small">Create table</el-checkbox>
                <el-checkbox v-model="dbPerms.ALTER" size="small">Alter</el-checkbox>
                <el-checkbox v-model="dbPerms.DROP" size="small">Drop</el-checkbox>
                <el-checkbox v-model="dbPerms.DESCRIBE" size="small">Describe</el-checkbox>
              </template>
              <template v-else-if="form.scope === 'view'">
                <el-checkbox v-model="viewPerms.SELECT" size="small">Select</el-checkbox>
                <el-checkbox v-model="viewPerms.DESCRIBE" size="small">Describe</el-checkbox>
                <el-checkbox v-model="viewPerms.ALTER" size="small">Alter</el-checkbox>
                <el-checkbox v-model="viewPerms.DROP" size="small">Drop</el-checkbox>
              </template>
              <template v-else>
                <el-checkbox v-model="tablePerms.SELECT" size="small">Select</el-checkbox>
                <el-checkbox v-model="tablePerms.INSERT" size="small">Insert</el-checkbox>
                <el-checkbox v-model="tablePerms.DELETE" size="small">Delete</el-checkbox>
                <el-checkbox v-model="tablePerms.DESCRIBE" size="small">Describe</el-checkbox>
                <el-checkbox v-model="tablePerms.ALTER" size="small">Alter</el-checkbox>
                <el-checkbox v-model="tablePerms.DROP" size="small">Drop</el-checkbox>
              </template>
            </div>
          </el-form-item>

          <!-- 有效期：默认长期有效。「指定日期」按当天 23:59:59（本地时区）换算成
               官方要求的 UTC instant——选「到 10 月 20 日」就该在 20 日整日有效，
               而不是 20 日 00:00 就失效。 -->
          <el-form-item label="有效期">
            <div class="pc-perms">
              <el-radio-group v-model="form.expireMode">
                <el-radio-button value="permanent">长期有效</el-radio-button>
                <el-radio-button value="date">指定日期</el-radio-button>
              </el-radio-group>
              <el-date-picker
                v-if="form.expireMode === 'date'"
                v-model="form.expireDate"
                type="date"
                size="small"
                value-format="YYYY-MM-DD"
                placeholder="授权到哪一天"
                :disabled-date="isPastDate"
                class="pc-expire-date"
              />
            </div>
          </el-form-item>
        </el-form>

        <template #footer>
          <div class="pc-dialog-footer">
            <span class="pc-dim pc-preview">{{ footerHint }}</span>
            <div>
              <el-button size="small" @click="dialogOpen = false">取消</el-button>
              <el-button
                size="small"
                type="primary"
                :loading="submitting"
                :disabled="pendingCount === 0"
                @click="submit"
              >
                授予（{{ pendingCount }}）
              </el-button>
            </div>
          </div>
        </template>
      </el-dialog>
    </template>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import { catalogApi } from '@/api/catalog.js'
import { icebergApi, listAllNamespaces, namespaceLabel, namespaceLevels } from '@/api/iceberg.js'
import { managementApi } from '@/api/management.js'
import { permissionApi } from '@/api/permission.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh, useRemotePaging } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { isIcebergCatalog } from '@/utils/catalogFormat.js'
import { formatDate, formatTime } from '@/utils/format.js'

/**
 * 权限管理页（Paimon 原生权限模型）。
 *
 * <p>控制台唯一的授权入口。授权直接面向 principal（`user:alice` / `group:eng` /
 * `role:sales_writer`），覆盖 catalog / database / table / view 四级资源；
 * 服务端另有一条 Polaris 的 RBAC 链路（主体 → 角色 → catalog 角色 → 资源授权），
 * 由管理 API 与 Spark SQL 维护，没有控制台页面——判定时两路取并集。
 *
 * <p><b>两种 catalog 格式共用这一页。</b>授权的落库形状与判定口径是格式无关的
 * （`/v1/{prefix}/permissions` 在任何 catalog 上都可用），差别只在**候选从哪来**：
 *
 * - Paimon：库是扁平的单级名，候选走 `databases/**`；
 * - Iceberg：namespace 是多级数组，候选要走 `namespaces/**` 并按层递归，显示成
 *   `accounting.tax` 这样的点号形式。提交时 `resource.database` 就填这个点号形式
 *   ——服务端正是把多级 namespace 按点号拼进那一列的，判定层也按拼接后的路径比对
 *   （`AuthorizationService.isPrefix`），因此「授 `accounting` 覆盖 `accounting.tax`」
 *   在两套协议面上都成立。
 *
 * <p>术语跟着格式走：Iceberg 页面上的「Namespace」不是文案洁癖——Iceberg 客户端里
 * 那个层级的名字就叫 namespace，写成 database 会让人以为要填的是 Paimon 的库。
 *
 * <p>表单按用户要求做了简化——权限只暴露
 * database 四项 / table 六项 / view 四项，Insert 与 Delete 在官方词表里同为 UPDATE，
 * 提交前去重；视图没有写数据权限，所以视图那一组没有 Insert / Delete。
 *
 * <p>表单收在弹窗里（页头「添加授权」按钮打开）：页面主体只留授权清单，
 * 授权是低频离散动作，不需要长期占据首屏。
 *
 * <p>有效期默认「长期有效」，也可选「指定日期」——按所选当天 23:59:59（本地时区）
 * 换算成官方要求的 UTC instant 提交；「到 10 月 20 日」就是 20 日整日有效。
 * 清单里的过期条目不会被隐藏（那里反映落库内容），但标注「已过期」——判定层按
 * 过期时间排除它，不标注会让人以为权限还在。
 *
 * <p>清单只回答一个问题——「谁，被允许对什么做什么」。一行 = 一个主体 × 一个作用范围：
 * 主体名 + 类型合成一列，资源定位符（库、库.表）合成一列，同一范围的权限并列成标签、
 * 逐个可撤销；权限用表单里的同一套词（Insert / Delete 而不是服务端的 UPDATE）。
 * catalog 是全页常量（右上角选的那一个），不进表格。
 */

const route = useRoute()
const router = useRouter()

/**
 * 当前 catalog：跟随顶栏的选择器（session），URL 查询串只作深链回填。
 * 变化时同步回查询串，让刷新/分享链接能落在同一个 catalog 上。
 */
const catalogName = computed(() => session.state.selectedCatalog || route.query.catalog || '')

function resetForCatalog() {
  dialogOpen.value = false
  resetForm()
  listRows.value = []
}

/** 清空表单：授权对象、作用范围、有效期、已勾选权限一起复位。 */
function resetForm() {
  form.grantees = []
  form.scope = 'table'
  form.database = ''
  form.objects = []
  form.expireMode = 'permanent'
  form.expireDate = ''
  objectOptions.value = []
  clearPerms()
}

/** 勾选项复位。切作用范围时也要调——四级的勾选项不是同一组，留着会串味。 */
function clearPerms() {
  Object.keys(catalogPerms).forEach((key) => { catalogPerms[key] = false })
  Object.keys(dbPerms).forEach((key) => { dbPerms[key] = false })
  Object.keys(tablePerms).forEach((key) => { tablePerms[key] = false })
  Object.keys(viewPerms).forEach((key) => { viewPerms[key] = false })
}

/** 打开弹窗时才拉候选：授权对象与 database 都可能在这期间被新建，进弹窗即取一次最新的。 */
function openDialog() {
  dialogOpen.value = true
  loadGranteeOptions()
  loadDatabases()
}

onConsoleRefresh(() => {
  if (catalogName.value) {
    loadList()
  }
})

// ------------------------------------------------------------------ 授权对象

/** 用户与角色候选：来自管理面的主体与 **principal role**，也允许直接手输任意 principal。 */
const principals = ref([])
const roles = ref([])

const granteeGroups = computed(() => [
  {
    label: '用户（user:）',
    options: principals.value.map((name) => ({ label: name, value: `user:${name}` })),
  },
  {
    label: '角色（role:）',
    options: roles.value.map((name) => ({ label: name, value: `role:${name}` })),
  },
])

async function loadGranteeOptions() {
  if (!catalogName.value) return
  try {
    // 「角色」候选是 **principal role**（`role:<名>` 是有效的授权主体串），
    // 不是 Polaris 的 catalog 角色——后者在本服务端不存在，调它会直接抛
    // `listCatalogRoles is not a function`。
    const [principalPayload, rolePayload] = await Promise.all([
      managementApi.listPrincipals().catch(() => null),
      managementApi.listPrincipalRoles().catch(() => null),
    ])
    principals.value = (principalPayload?.principals || []).map((item) => item.name)
    roles.value = (rolePayload?.roles || []).map((item) => item.name)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 资源范围

/**
 * 当前 catalog 是不是 Iceberg。
 *
 * <p>先 `ensureCatalogs()` 再判断，不猜——深链接进来时 catalog 列表可能还没到，
 * 猜错的后果是拿 Paimon 的 `databases/**` 去打一个 Iceberg catalog（一串 404，
 * 看着像故障而不像「格式不同」）。
 */
const icebergCatalog = computed(() => isIcebergCatalog(session.catalogByName(catalogName.value)))

async function ensureCatalogs() {
  if (session.state.catalogsLoaded) return
  await session.loadCatalogs().catch(() => null)
}

/** 那一层的叫法：Iceberg 叫 namespace，Paimon 叫 database。 */
const scopeTerm = computed(() => (icebergCatalog.value ? 'Namespace' : 'Database'))

const databases = ref([])
const databasesLoading = ref(false)
/** 对象候选：按当前选中的对象类型，是表列表或视图列表。 */
const objectOptions = ref([])
const objectsLoading = ref(false)

/** 列表请求用的页大小：Iceberg 侧叫 `pageSize`，与服务端 meta 里的上限对齐。 */
const maxPageSize = computed(() => session.state.meta?.service?.maxPageSize || 100)

/**
 * 库 / namespace 候选。
 *
 * <p>两条来源按 catalog 格式选：Paimon 的 `databases` 是扁平列表；Iceberg 的
 * namespace 是**多级**的，端点一次只回直接子级，因此走 `listAllNamespaces`（按层递归，
 * 带深度与条数上限），显示成点号拼接的 `accounting.tax`——与提交给服务端的值、
 * 与 SQL 里的三段名同一口径。
 */
async function loadDatabases() {
  if (!catalogName.value) return
  databasesLoading.value = true
  try {
    await ensureCatalogs()
    if (icebergCatalog.value) {
      const levels = await listAllNamespaces(catalogName.value, { pageSize: maxPageSize.value })
      databases.value = levels.map(namespaceLabel)
    } else {
      const payload = await catalogApi.listDatabases(catalogName.value, { maxResults: 1000 })
      databases.value = payload.databases || []
    }
  } catch (error) {
    databases.value = []
    notifyError(error)
  } finally {
    databasesLoading.value = false
  }
}

/** 换库：对象候选整批作废，重新拉一次。 */
async function onDatabaseChange() {
  form.objects = []
  await loadObjects()
}

/** 换作用范围：已选对象与已勾权限都失去意义，一起清掉再拉新候选。 */
async function onScopeChange() {
  form.objects = []
  clearPerms()
  await loadObjects()
}

async function loadObjects() {
  objectOptions.value = []
  if (!isObjectScope.value || !catalogName.value || !form.database) return
  objectsLoading.value = true
  try {
    await ensureCatalogs()
    if (icebergCatalog.value) {
      // 层级数组由显示名还原：列表里的层级名可能含点，理论上会切错，但这里的候选
      // 就来自 loadDatabases 的列表，同一个命名空间不会既在这里又在别处
      const levels = namespaceLevels(form.database)
      const isView = form.scope === 'view'
      const payload = isView
        ? await icebergApi.listViews(catalogName.value, levels, { pageSize: maxPageSize.value })
        : await icebergApi.listTables(catalogName.value, levels, { pageSize: maxPageSize.value })
      // Iceberg 的列表响应是 identifiers（`{namespace, name}`），不是名字数组
      objectOptions.value = (payload?.identifiers || []).map((item) => item.name)
    } else {
      // 两个端点的列表字段名不同：表是 tables、视图是 views（与 catalog API 一致）
      const isView = form.scope === 'view'
      const payload = isView
        ? await catalogApi.listViews(catalogName.value, form.database, { maxResults: 1000 })
        : await catalogApi.listTables(catalogName.value, form.database, { maxResults: 1000 })
      objectOptions.value = (isView ? payload.views : payload.tables) || []
    }
  } catch (error) {
    objectOptions.value = []
    notifyError(error)
  } finally {
    objectsLoading.value = false
  }
}

// ------------------------------------------------------------------ 权限表单

const form = reactive({ grantees: [], scope: 'table', database: '', objects: [], expireMode: 'permanent', expireDate: '' })

/** 只有表 / 视图两级要选具体对象；catalog 与 database 两级直接落在整层上。 */
const isObjectScope = computed(() => form.scope === 'table' || form.scope === 'view')

/** 新增授权表单放在弹窗里（页面上只留「添加授权」按钮），避免列表被长表单挤到首屏之外。 */
const dialogOpen = ref(false)

/** catalog 级的四项：官方词表里 CATALOG 只接受 ALTER / DROP / GRANT / CREATEDATABASE。 */
const catalogPerms = reactive({ CREATEDATABASE: false, ALTER: false, DROP: false, GRANT: false })
const dbPerms = reactive({ CREATETABLE: false, ALTER: false, DROP: false, DESCRIBE: false })
const tablePerms = reactive({ SELECT: false, INSERT: false, DELETE: false, DESCRIBE: false, ALTER: false, DROP: false })
/** 视图没有 Insert / Delete：官方词表里 VIEW 只接受 SELECT/ALTER/DROP/GRANT/DESCRIBE。 */
const viewPerms = reactive({ SELECT: false, DESCRIBE: false, ALTER: false, DROP: false })

const PERM_LABELS = {
  catalog: 'Catalog 权限',
  database: 'Database 权限',
  table: 'Table 权限',
  view: 'View 权限',
}

/** 中间那一层的叫法跟着格式走（Iceberg 页面上写 Database 会让人以为要填库名）。 */
const permLabel = computed(() => (form.scope === 'database'
  ? `${scopeTerm.value} 权限`
  : PERM_LABELS[form.scope]))

/**
 * 勾选 → 官方 access 集合。Insert 与 Delete 同为 UPDATE：勾选任一或两者都只产生一条
 * UPDATE 赋权（服务端词表里写数据是一体的），因此这里用 Set 去重。
 */
const selectedAccesses = computed(() => {
  const accesses = new Set()
  if (form.scope === 'catalog') {
    if (catalogPerms.CREATEDATABASE) accesses.add('CREATEDATABASE')
    if (catalogPerms.ALTER) accesses.add('ALTER')
    if (catalogPerms.DROP) accesses.add('DROP')
    if (catalogPerms.GRANT) accesses.add('GRANT')
  } else if (form.scope === 'database') {
    if (dbPerms.CREATETABLE) accesses.add('CREATETABLE')
    if (dbPerms.ALTER) accesses.add('ALTER')
    if (dbPerms.DROP) accesses.add('DROP')
    if (dbPerms.DESCRIBE) accesses.add('DESCRIBE')
  } else if (form.scope === 'view') {
    if (viewPerms.SELECT) accesses.add('SELECT')
    if (viewPerms.DESCRIBE) accesses.add('DESCRIBE')
    if (viewPerms.ALTER) accesses.add('ALTER')
    if (viewPerms.DROP) accesses.add('DROP')
  } else {
    if (tablePerms.SELECT) accesses.add('SELECT')
    if (tablePerms.INSERT || tablePerms.DELETE) accesses.add('UPDATE')
    if (tablePerms.DESCRIBE) accesses.add('DESCRIBE')
    if (tablePerms.ALTER) accesses.add('ALTER')
    if (tablePerms.DROP) accesses.add('DROP')
  }
  return [...accesses]
})

const submitting = ref(false)

/**
 * 有效期 → 官方要求的 UTC instant。选中「到 2027-01-01」表示这一整天有效，
 * 因此取当天 23:59:59.999（本地时区）再转 UTC；长期有效返回空串，
 * 提交时不带 expireTime 字段（官方语义：省略即永不过期）。
 */
const expireInstant = computed(() => {
  if (form.expireMode !== 'date' || !form.expireDate) return ''
  const end = new Date(`${form.expireDate}T23:59:59.999`)
  return Number.isFinite(end.getTime()) ? end.toISOString() : ''
})

/** 日期选择器禁掉今天之前的日期：补授一个已经过期的权限没有意义。 */
function isPastDate(date) {
  const start = new Date()
  start.setHours(0, 0, 0, 0)
  return date.getTime() < start.getTime()
}

/** 有效期的显示词，页脚预览与空态提示共用一处说法。 */
const expireLabel = computed(() => (form.expireMode === 'permanent' ? '长期有效' : `至 ${form.expireDate || '未选择'}`))

const pendingCount = computed(() => form.grantees.length * selectedAccesses.value.length
  * Math.max(1, form.objects.length))

const previewScope = computed(() => {
  if (!scopeReady.value) return ''
  const target = targetLabel.value
  return `：${form.grantees.length} 个 principal × ${selectedAccesses.value.length} 项权限 × ${target}`
})

/** 作用范围是否已选全：catalog 级不需要 database，表 / 视图级还需要至少一个对象。 */
const scopeReady = computed(() => {
  if (form.scope === 'catalog') return true
  if (!form.database) return false
  return !isObjectScope.value || form.objects.length > 0
})

const targetLabel = computed(() => {
  // catalog 的全限定名就是右上角选中的那个 catalog（页面常量），这里不再重复写出来
  if (form.scope === 'catalog') return '整个 catalog'
  if (form.scope === 'database') return `${scopeTerm.value.toLowerCase()} ${form.database}`
  return `${form.scope === 'view' ? '视图' : '表'} ${form.objects.join('、')}`
})

/** 弹窗页脚左侧的提交预览：没填完时退化为放置提示，不让页脚空着。 */
const footerHint = computed(() => (pendingCount.value > 0
  ? `将授予 ${pendingCount.value} 条授权${previewScope.value}（${expireLabel.value}）`
  : '选择授权对象与作用范围后再授予'))

function buildResource(object) {
  // 四级各对应官方的资源类型：catalog 不带限定、database 带库名、
  // 表 / 视图再逐条加对象名——对象名放在各自的字段里（table / view）
  if (form.scope === 'catalog') return { type: 'CATALOG' }
  if (form.scope === 'database') return { type: 'DATABASE', database: form.database }
  if (form.scope === 'view') return { type: 'VIEW', database: form.database, view: object }
  return { type: 'TABLE', database: form.database, table: object }
}

async function submit() {
  if (!form.grantees.length) {
    notifyError(new Error('请选择或输入授权对象'))
    return
  }
  if (form.scope !== 'catalog' && !form.database) {
    notifyError(new Error(`请选择 ${scopeTerm.value}`))
    return
  }
  if (isObjectScope.value && !form.objects.length) {
    notifyError(new Error(`请选择要授权的${form.scope === 'view' ? '视图' : '表'}，或把作用范围改成 Database`))
    return
  }
  if (!selectedAccesses.value.length) {
    notifyError(new Error('请至少勾选一项权限'))
    return
  }
  if (form.expireMode === 'date' && !form.expireDate) {
    notifyError(new Error('请选择授权到期的日期，或把有效期改回「长期有效」'))
    return
  }
  // disabled-date 只管日历面板，手输的日期要在这里挡一次
  if (form.expireMode === 'date' && Date.parse(expireInstant.value) <= Date.now()) {
    notifyError(new Error('到期日期需要晚于当前时间'))
    return
  }
  submitting.value = true
  const targets = isObjectScope.value ? form.objects : [null]
  let done = 0
  try {
    for (const principal of form.grantees) {
      for (const object of targets) {
        for (const access of selectedAccesses.value) {
          await permissionApi.grant(catalogName.value, {
            resource: buildResource(object),
            access,
            principal,
            // 长期有效不带该字段（省略即永不过期）；重复授予同一身份会覆盖过期时间
            expireTime: expireInstant.value || undefined,
          })
          done++
        }
      }
    }
    notifySuccess(`已授予 ${done} 条授权（${expireLabel.value}）`)
    // 成功后关窗并整体复位：表单在弹窗里，关窗就是这次授予的结束，
    // 不保留资源范围（下次授予多半换对象，留着反而容易提交到错的库上）
    dialogOpen.value = false
    resetForm()
    await loadList()
  } catch (error) {
    notifyError(error)
    await loadList()
  } finally {
    submitting.value = false
  }
}

// ------------------------------------------------------------------ 授权清单

const filterText = ref('')

/**
 * 官方 access → 界面用词。与弹窗里的勾选项逐字一致：同一件事在表单里叫 Insert / Delete，
 * 在清单里就不该叫服务端的 UPDATE；只有「通过其他途径授予」的 access 才回落成原文。
 */
const ACCESS_LABELS = {
  ALL: 'All',
  CREATEDATABASE: 'Create database',
  DESCRIBE: 'Describe',
  ALTER: 'Alter',
  DROP: 'Drop',
  CREATETABLE: 'Create table',
  CREATEVIEW: 'Create view',
  CREATEFUNCTION: 'Create function',
  LIST: 'List',
  SELECT: 'Select',
  UPDATE: 'Insert / Delete',
  GRANT: 'Grant',
}

function accessLabel(access) {
  return ACCESS_LABELS[access] || access
}

/**
 * 过期条目的两个注解。列表反映落库内容——过期条目不会被隐藏，
 * 但必须一眼看出它已经不生效了（判定层按过期时间排除它）。
 */
function isExpired(millis) {
  return Number(millis) < Date.now()
}

/** 距到期还有几天（不足一天算 0）。 */
function daysUntil(millis) {
  return Math.max(0, Math.ceil((Number(millis) - Date.now()) / 86_400_000))
}

/** 对象级资源类型的注解词。定位符里只写了名字，类型靠这一处补上。 */
const OBJECT_KINDS = {
  TABLE: '（单表）',
  VIEW: '（单视图）',
  FUNCTION: '（单函数）',
  COLUMN: '（表内列）',
}

/**
 * 取清单的一页。
 *
 * <p>**一行由服务端折叠好**（同主体的同范围多条权限合成一行），因此这里不再
 * 自己分组：客户端只有一页，跨页分组必然错——同一组被页边界切成两行，
 * 界面上就是同一个主体出现两次。折叠与分页都在服务端，`total` 也就是「多少行」。
 * 这正是为什么分页不能留在浏览器里：折叠与切页的顺序反了，`total` 与「一行」
 * 的含义就都变了。
 *
 * <p>关键字也下推（`keyword`）：筛选要作用于全站，而不是这一页。
 */
async function fetchScopeRows({ page, pageSize }) {
  if (!catalogName.value) return { items: [], total: 0 }
  const payload = await permissionApi.scopes(catalogName.value, {
    keyword: filterText.value.trim() || undefined,
    page,
    pageSize,
  })
  return { items: (payload.scopes || []).map(toScopeRow), total: payload.page?.total ?? 0 }
}

// `immediate: false`：首次加载由下面的 catalog watch 触发，否则挂载时会发两次。
const {
  loading: listLoading,
  error: listError,
  rows: listRows,
  total: listTotal,
  page,
  pageSize,
  load: loadList,
  reload: reloadList,
  onPageChange,
  onSizeChange,
} = useRemotePaging(fetchScopeRows, { immediate: false })

/** 关键字变化：回第一页重取（服务端筛，不是筛当前页）。 */
let keywordTimer = null
watch(filterText, () => {
  clearTimeout(keywordTimer)
  keywordTimer = setTimeout(() => reloadList(), 300)
})

/**
 * 服务端折叠好的一行 → 界面行。
 *
 * <p>只补文案：作用范围由服务端算好（`scope`），这里加类型注解与过期时间的
 * 合并规则。撤销按**逐项**身份处理，所以 `items` 里保留每条权限自己的原始对象。
 */
function toScopeRow(scope) {
  const principal = scope.principal || ''
  const type = principal.startsWith('user:')
    ? { label: '用户', tag: '' }
    : principal.startsWith('role:')
      ? { label: '角色', tag: 'warning' }
      : principal.startsWith('group:')
        ? { label: '组', tag: 'success' }
        : { label: '主体', tag: 'info' }
  const catalogWide = scope.resourceType === 'CATALOG' || scope.resourceType === 'CATALOG_ALL'
  const items = (scope.accesses || []).map((item) => {
    const expireMillis = item.expireTime ? Date.parse(item.expireTime) : NaN
    return {
      access: item.access || '',
      label: accessLabel(item.access),
      expireRaw: item.expireTime || '',
      expireMillis: Number.isFinite(expireMillis) ? expireMillis : null,
      identity: item,
    }
  })
  const stamps = [...new Set(items.map((item) => item.expireMillis).filter((m) => m != null))]
  return {
    principal,
    typeLabel: type.label,
    typeTag: type.tag,
    resourceType: scope.resourceType,
    database: scope.database || '',
    object: scope.object || '',
    scope: scope.scope,
    scopeKind: catalogWide
      ? '（整 catalog）'
      : (scope.object
        ? (OBJECT_KINDS[scope.resourceType] || '（单个对象）')
        : (icebergCatalog.value ? '（整 namespace）' : '（整库）')),
    items,
    expireMillis: stamps.length ? Math.min(...stamps) : null,
    expireRaw: items.every((item) => item.expireRaw === items[0]?.expireRaw)
      ? (items[0]?.expireRaw || '')
      : '',
    expireMixed: stamps.length > 1
      || (stamps.length > 0 && items.some((item) => item.expireMillis == null)),
  }
}

/**
 * 清单的当前页。服务端已按 (principal, scope) 排好序，这里不再排序、不再折叠——
 * 那些都在服务端做过一次了。
 */
const filteredRows = computed(() => listRows.value)

/**
 * 空态的两种含义要分开说：筛掉了 / 一条都还没有，后者给出下一步动作。
 */
const emptyText = computed(() => (filterText.value.trim()
  ? `没有匹配「${filterText.value.trim()}」的授权`
  : '这个 catalog 上还没有任何授权'))

/** 撤销行内的单项权限：标签上的 × 触发，一次只撤一个（资源 + access + principal）。 */
async function revokeAccess(row, item) {
  const ok = await confirmDanger({
    title: '撤销授权',
    target: `撤销 ${row.principal} 的 ${item.label}？`,
    detail: `资源：${row.resourceType} ${row.database || ''}${row.object ? ' / ' + row.object : ''}\n`
      + '撤销按赋权身份（资源 + access + principal）整条移除，幂等。',
    confirmText: '撤销',
  })
  if (!ok) return
  try {
    // 只回传身份三元组：expireTime 不参与定位，columns 属于列范围细节
    const resource = { type: row.resourceType }
    if (row.database) resource.database = row.database
    if (row.object) {
      if (row.resourceType === 'VIEW') resource.view = row.object
      else if (row.resourceType === 'FUNCTION') resource.function = row.object
      else resource.table = row.object
    }
    await permissionApi.revoke(catalogName.value, {
      resource,
      access: item.access,
      principal: row.principal,
    })
    notifySuccess(`已撤销 ${row.principal} 的 ${item.label}`)
    await loadList()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 初始化

// watch 放在所有声明之后：immediate 回调会在 setup 期间同步执行，
// 提前注册会踩到尚未初始化的 form / 分页状态（TDZ）
watch(catalogName, (name, previous) => {
  if (previous !== undefined && name) {
    // 顶栏切换 catalog：同步进查询串，深链/刷新不丢上下文
    router.replace({ name: 'permissions', query: { catalog: name } })
  }
  if (!name) return
  resetForCatalog()
  loadGranteeOptions()
  loadDatabases()
  // 换 catalog 是换集合：页码必须归位，否则停在第 3 页会看到空表
  reloadList()
}, { immediate: true })
</script>

<style scoped>
/*
 * 弹窗表单走全局基元（.pc-form 的标签与字段间距、.pc-form-grid 的自动折行），
 * 这里只钉列数：授权表单固定单列——作用范围是四个「层级」按钮，跟谁并排都挤，
 * 而其余字段（授权对象、Database、表 / 视图、权限）都与它同级，全宽下来左边缘
 * 与右边缘天然对齐。写成 `.pc-form.pc-grant-form` 而不是单类，是为了特异性
 * 高于全局的 .pc-form.pc-form-grid，不依赖样式文件的先后顺序。
 */
.pc-form.pc-grant-form {
  grid-template-columns: minmax(0, 1fr);
}

.pc-perms {
  display: flex;
  align-items: center;
  gap: 4px 14px;
  flex-wrap: wrap;
}

.pc-preview {
  font-size: 12.5px;
}

/* 弹窗页脚：Element Plus 默认整体右对齐，这里改成「预览在左、按钮在右」 */
.pc-dialog-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.pc-filter {
  width: 280px;
}

/* 字段下方的一行说明（Iceberg 的 namespace 是多级的，点号形式要解释一句）。
   与控件同宽、不折行到标签那一列：它说的是「怎么填」，不是字段名的一部分。 */
.pc-field-hint {
  margin-left: 0;
  margin-top: 4px;
  line-height: 1.5;
}

/* 表格里的两个「补语」：主体类型标签、作用范围是整库还是单表。
   同一行右侧，字号比正文小一档、颜色降级——它们是注解，不是要读的内容。 */
.pc-kind {
  margin-left: 8px;
  vertical-align: 1px;
}

.pc-scope-kind {
  margin-left: 6px;
  font-size: 12px;
  color: var(--pc-text-dim);
}

/* 合并后的权限标签：一行放不下时折行，行距收紧贴住标签间距 */
.pc-access-tags {
  display: flex;
  align-items: center;
  gap: 4px 6px;
  flex-wrap: wrap;
}

/* 有效期那一行：模式按钮在左、日期选择器紧随其后，两者同宽线对齐 */
.pc-expire-date {
  width: 180px;
}
</style>
