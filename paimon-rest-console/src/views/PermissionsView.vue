<template>
  <div class="pc-page">
    <PageHeader
      title="权限管理"
      subtitle="Paimon 官方权限模型：授权直接面向 principal（user:alice / role:sales_writer），资源按 CATALOG / DATABASE / TABLE 定位。与「Catalog 角色与授权」页的 Polaris 模型并行生效，服务端判定取两路并集。"
    >
      <template #actions>
        <el-button type="primary" size="small" :disabled="!catalogName" @click="openDialog">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">添加授权</span>
        </el-button>
        <el-button size="small" :disabled="!catalogName" :loading="listState.loading" @click="loadList">
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
      <!------------------------------ 权限列表 ------------------------------>
      <PanelCard flush>
        <div class="pc-list-tools">
          <span class="pc-list-title">Data permissions（{{ filteredRows.length }}）</span>
          <el-input
            v-model="filterText"
            size="small"
            clearable
            placeholder="按 principal / database / 表过滤"
            class="pc-filter"
          />
        </div>

        <el-alert v-if="listState.error" type="error" :closable="false" show-icon class="pc-hint">
          <template #title>加载权限列表失败</template>
          <div>{{ listState.error }}</div>
        </el-alert>

        <el-table v-loading="listState.loading" :data="filteredRows" size="small" empty-text="还没有任何赋权">
          <el-table-column label="Principal" min-width="190">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.principal }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="90">
            <template #default="{ row }">
              <el-tag size="small" effect="plain" :type="row.typeTag">{{ row.typeLabel }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="资源" width="130">
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{ row.resourceType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="Database" min-width="120">
            <template #default="{ row }">{{ row.database || '-' }}</template>
          </el-table-column>
          <el-table-column label="Table" min-width="120">
            <template #default="{ row }">{{ row.object || '-' }}</template>
          </el-table-column>
          <el-table-column label="Catalog" min-width="110">
            <template #default="{ row }">{{ row.catalog }}</template>
          </el-table-column>
          <el-table-column label="权限" min-width="170">
            <template #default="{ row }">
              <span class="pc-mono">{{ accessLabel(row.access) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="过期时间" width="180">
            <template #default="{ row }">{{ row.expireTime || '—' }}</template>
          </el-table-column>
          <el-table-column label="" width="70" align="right" fixed="right">
            <template #default="{ row }">
              <IconAction icon="Remove" label="撤销" type="danger" @click="revokeRow(row)" />
            </template>
          </el-table-column>
        </el-table>
      </PanelCard>

      <!------------------------------ 新增授权（弹窗） ------------------------------>
      <el-dialog
        v-model="dialogOpen"
        title="新增授权"
        width="660px"
        :close-on-click-modal="false"
        destroy-on-close
      >
        <div class="pc-form-row">
          <span class="pc-form-label">授权对象</span>
          <el-select
            v-model="form.grantees"
            multiple
            filterable
            allow-create
            default-first-option
            size="small"
            class="pc-grantees"
            placeholder="选择或直接输入 principal，如 user:alice / role:analyst"
          >
            <el-option-group v-for="group in granteeGroups" :key="group.label" :label="group.label">
              <el-option v-for="item in group.options" :key="item.value" :label="item.label" :value="item.value" />
            </el-option-group>
          </el-select>
        </div>

        <!-- Database 与 Tables 分两行：并排时 Tables 下拉会被挤到第二行，标签悬在半空 -->
        <div class="pc-form-row">
          <span class="pc-form-label">Database</span>
          <el-select
            v-model="form.database"
            filterable
            size="small"
            class="pc-database"
            placeholder="选择 database"
            :loading="databasesLoading"
            @change="onDatabaseChange"
          >
            <el-option v-for="name in databases" :key="name" :label="name" :value="name" />
          </el-select>
        </div>

        <div class="pc-form-row">
          <span class="pc-form-label">Tables（可选）</span>
          <el-select
            v-model="form.tables"
            multiple
            filterable
            clearable
            collapse-tags
            collapse-tags-tooltip
            size="small"
            class="pc-tables"
            :placeholder="form.database ? '不选 = database 级授权' : '先选择 database'"
            :disabled="!form.database"
            :loading="tablesLoading"
          >
            <el-option v-for="name in tableOptions" :key="name" :label="name" :value="name" />
          </el-select>
        </div>

        <div class="pc-form-row">
          <span class="pc-form-label">{{ form.tables.length ? 'Table 权限' : 'Database 权限' }}</span>
          <div class="pc-perms">
            <template v-if="!form.tables.length">
              <el-checkbox v-model="dbPerms.CREATETABLE" size="small">Create table</el-checkbox>
              <el-checkbox v-model="dbPerms.ALTER" size="small">Alter</el-checkbox>
              <el-checkbox v-model="dbPerms.DROP" size="small">Drop</el-checkbox>
              <el-checkbox v-model="dbPerms.DESCRIBE" size="small">Describe</el-checkbox>
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
        </div>

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

import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import { catalogApi } from '@/api/catalog.js'
import { managementApi } from '@/api/management.js'
import { permissionApi } from '@/api/permission.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * 权限管理页（Paimon 官方权限模型）。
 *
 * <p>与「Catalog 角色与授权」页（Polaris 模型）的区别：那里把授权挂在 catalog role 上，
 * 这里直接面向 principal 字符串。表单按用户要求做了简化——权限只暴露
 * database 四项 / table 六项，Insert 与 Delete 在官方词表里同为 UPDATE，提交前去重。
 *
 * <p>表单收在弹窗里（页头「添加授权」按钮打开）：页面主体只留权限列表，
 * 授权是低频离散动作，不需要长期占据首屏。
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
  listState.rows = []
  listState.error = ''
}

/** 清空表单：授权对象、资源范围、已勾选权限三者一起复位。 */
function resetForm() {
  form.grantees = []
  form.database = ''
  form.tables = []
  tableOptions.value = []
  Object.keys(dbPerms).forEach((key) => { dbPerms[key] = false })
  Object.keys(tablePerms).forEach((key) => { tablePerms[key] = false })
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

/** 用户与角色候选：来自管理面的主体与 catalog 角色，也允许直接手输任意 principal。 */
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
    const [principalPayload, rolePayload] = await Promise.all([
      managementApi.listPrincipals().catch(() => null),
      managementApi.listCatalogRoles(catalogName.value).catch(() => null),
    ])
    principals.value = (principalPayload?.principals || []).map((item) => item.name)
    roles.value = (rolePayload?.roles || []).map((item) => item.name)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 资源范围

const databases = ref([])
const databasesLoading = ref(false)
const tableOptions = ref([])
const tablesLoading = ref(false)

async function loadDatabases() {
  if (!catalogName.value) return
  databasesLoading.value = true
  try {
    const payload = await catalogApi.listDatabases(catalogName.value, { maxResults: 1000 })
    databases.value = payload.databases || []
  } catch (error) {
    databases.value = []
    notifyError(error)
  } finally {
    databasesLoading.value = false
  }
}

async function onDatabaseChange() {
  form.tables = []
  tableOptions.value = []
  if (!form.database) return
  tablesLoading.value = true
  try {
    const payload = await catalogApi.listTables(catalogName.value, form.database, { maxResults: 1000 })
    tableOptions.value = payload.tables || []
  } catch (error) {
    tableOptions.value = []
    notifyError(error)
  } finally {
    tablesLoading.value = false
  }
}

// ------------------------------------------------------------------ 权限表单

const form = reactive({ grantees: [], database: '', tables: [] })

/** 新增授权表单放在弹窗里（页面上只留「添加授权」按钮），避免列表被长表单挤到首屏之外。 */
const dialogOpen = ref(false)

const dbPerms = reactive({ CREATETABLE: false, ALTER: false, DROP: false, DESCRIBE: false })
const tablePerms = reactive({ SELECT: false, INSERT: false, DELETE: false, DESCRIBE: false, ALTER: false, DROP: false })

/**
 * 勾选 → 官方 access 集合。Insert 与 Delete 同为 UPDATE：勾选任一或两者都只产生一条
 * UPDATE 赋权（服务端词表里写数据是一体的），因此这里用 Set 去重。
 */
const selectedAccesses = computed(() => {
  const accesses = new Set()
  if (form.tables.length) {
    if (tablePerms.SELECT) accesses.add('SELECT')
    if (tablePerms.INSERT || tablePerms.DELETE) accesses.add('UPDATE')
    if (tablePerms.DESCRIBE) accesses.add('DESCRIBE')
    if (tablePerms.ALTER) accesses.add('ALTER')
    if (tablePerms.DROP) accesses.add('DROP')
  } else {
    if (dbPerms.CREATETABLE) accesses.add('CREATETABLE')
    if (dbPerms.ALTER) accesses.add('ALTER')
    if (dbPerms.DROP) accesses.add('DROP')
    if (dbPerms.DESCRIBE) accesses.add('DESCRIBE')
  }
  return [...accesses]
})

const submitting = ref(false)

const pendingCount = computed(() => form.grantees.length * selectedAccesses.value.length
  * Math.max(1, form.tables.length))

const previewScope = computed(() => {
  if (!form.database) return ''
  const target = form.tables.length ? `表 ${form.tables.join('、')}` : `database ${form.database}`
  return `：${form.grantees.length} 个 principal × ${selectedAccesses.value.length} 项权限 × ${target}`
})

/** 弹窗页脚左侧的提交预览：没填完时退化为放置提示，不让页脚空着。 */
const footerHint = computed(() => (pendingCount.value > 0
  ? `将提交 ${pendingCount.value} 条赋权${previewScope.value}`
  : '选择授权对象与资源范围后提交'))

function buildResource(table) {
  // database 级与表级都走 TABLE 定位器族：database 级用 DATABASE，
  // 表级按选中表逐条 TABLE——官方对照表里 CREATE TABLE 等库级操作正是 DATABASE 资源
  if (table) {
    return { type: 'TABLE', database: form.database, table }
  }
  return { type: 'DATABASE', database: form.database }
}

async function submit() {
  if (!form.grantees.length) {
    notifyError(new Error('请选择或输入授权对象'))
    return
  }
  if (!form.database) {
    notifyError(new Error('请选择 database'))
    return
  }
  if (!selectedAccesses.value.length) {
    notifyError(new Error('请至少勾选一项权限'))
    return
  }
  submitting.value = true
  const targets = form.tables.length ? form.tables : [null]
  let done = 0
  try {
    for (const principal of form.grantees) {
      for (const table of targets) {
        for (const access of selectedAccesses.value) {
          await permissionApi.grant(catalogName.value, {
            resource: buildResource(table),
            access,
            principal,
          })
          done++
        }
      }
    }
    notifySuccess(`已提交 ${done} 条赋权`)
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

// ------------------------------------------------------------------ 权限列表

const listState = reactive({ loading: false, rows: [], error: '' })
const filterText = ref('')

/** 官方 access → 展示名。UPDATE 括注 Insert/Delete，与表单的勾选语义对上。 */
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
  UPDATE: 'Update（Insert/Delete）',
  GRANT: 'Grant',
}

function accessLabel(access) {
  return ACCESS_LABELS[access] || access
}

/**
 * 拉全量赋权：端点按页返回，这里循环翻页取完（赋权是「人 × 资源」量级，
 * 全量拉回在控制台侧过滤，与服务端不做下推的口径一致）。
 */
async function loadList() {
  if (!catalogName.value) return
  listState.loading = true
  listState.error = ''
  const rows = []
  try {
    let pageToken = ''
    for (let page = 0; page < 50; page++) {
      const payload = await permissionApi.list(catalogName.value, {
        maxResults: 200,
        pageToken: pageToken || undefined,
      })
      for (const item of payload?.assignments || []) {
        rows.push(toRow(item))
      }
      pageToken = payload?.nextPageToken || ''
      if (!pageToken) break
    }
    listState.rows = rows
  } catch (error) {
    listState.error = describeError(error)
  } finally {
    listState.loading = false
  }
}

function toRow(item) {
  const resource = item.resource || {}
  const principal = item.principal || ''
  // principal 是官方语义下的不透明字符串，类型只按前缀展示；前缀只是约定，认不出就归为「主体」
  const type = principal.startsWith('user:')
    ? { label: '用户', tag: '' }
    : principal.startsWith('role:')
      ? { label: '角色', tag: 'warning' }
      : principal.startsWith('group:')
        ? { label: '组', tag: 'success' }
        : { label: '主体', tag: 'info' }
  return {
    principal,
    typeLabel: type.label,
    typeTag: type.tag,
    resourceType: resource.type || '',
    database: resource.database || '',
    // 对象定位符按类型放在不同字段，展示时归一成一列
    object: resource.table || resource.view || resource.function || '',
    catalog: catalogName.value,
    access: item.access || '',
    expireTime: item.expireTime || '',
    identity: item,
  }
}

const filteredRows = computed(() => {
  const keyword = filterText.value.trim().toLowerCase()
  if (!keyword) return listState.rows
  return listState.rows.filter((row) => `${row.principal} ${row.database} ${row.object}`
    .toLowerCase().includes(keyword))
})

async function revokeRow(row) {
  const ok = await confirmDanger({
    title: '撤销授权',
    target: `撤销 ${row.principal} 的 ${accessLabel(row.access)}？`,
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
      access: row.access,
      principal: row.principal,
    })
    notifySuccess(`已撤销 ${row.principal} 的赋权`)
    await loadList()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 初始化

// watch 放在所有声明之后：immediate 回调会在 setup 期间同步执行，
// 提前注册会踩到尚未初始化的 form / listState（TDZ）
watch(catalogName, (name, previous) => {
  if (previous !== undefined && name) {
    // 顶栏切换 catalog：同步进查询串，深链/刷新不丢上下文
    router.replace({ name: 'permissions', query: { catalog: name } })
  }
  if (!name) return
  resetForCatalog()
  loadGranteeOptions()
  loadDatabases()
  loadList()
}, { immediate: true })
</script>

<style scoped>
.pc-form-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.pc-form-label {
  font-size: 13px;
  color: var(--pc-text-dim);
  /* min-width 而非 width：标签不换行（white-space: nowrap），「Database 权限」这类
     长标签按自身宽度撑开，短标签仍对齐在 72px 栅格上 */
  min-width: 72px;
  white-space: nowrap;
  flex-shrink: 0;
}

.pc-grantees {
  flex: 1;
  min-width: 320px;
}

.pc-database {
  width: 200px;
}

.pc-tables {
  flex: 1;
  min-width: 260px;
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

.pc-list-tools {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px 4px;
}

.pc-list-title {
  font-size: 14px;
  font-weight: 600;
}

.pc-filter {
  width: 280px;
}
</style>
