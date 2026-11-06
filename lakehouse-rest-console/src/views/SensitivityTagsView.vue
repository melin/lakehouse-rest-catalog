<template>
  <div class="pc-page">
    <PageHeader
      title="敏感标签"
      subtitle="列级分类分级的真相源。识别只产命中，标签是治理结论——手工打标与自动落标写的是同一张表。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" :disabled="!catalog" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">手工打标</span>
        </el-button>
      </template>
    </PageHeader>

    <!-- P0 的删除语义必须先说清：抑制项要 P1 才有，因此「删了会不会复活」在今天是
         「不适用」而不是「不会」。写成「删除后不会被重新识别」是假的。 -->
    <TipAlert type="warning" class="pc-gap">
      <template #title>删标签的语义（当前阶段）</template>
      删除只移除这条治理结论，<strong>不会写抑制项</strong>——抑制项依赖「有人拒绝过这个识别结果」，
      而当前阶段既没有拒绝路径、元数据档也够不到自动落标阈值，所以「删了会复活」暂时不成立。
      这句话在识别结果审核上线后会变，届时界面文案要一起改。
    </TipAlert>

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">筛选</span>
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
          <el-select v-model="levelFilter" size="small" class="pc-sens-level" clearable placeholder="全部级别">
            <el-option v-for="item in LEVEL_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-input
            v-model="tableFilter"
            size="small"
            clearable
            class="pc-sens-table"
            placeholder="按表过滤（库.表）"
          />
          <el-button size="small" :disabled="!catalog" @click="load">查询</el-button>
        </span>
      </div>

      <!-- 覆盖率与筛选解耦：覆盖率的分母是「这个范围里有多少列」，筛标签键或级别
           不会改变范围的列数。因此「筛 L4 后覆盖率还是 86%」是正确的，要在界面上
           把这两个数分开摆，不能让它们看起来是一回事。 -->
      <div v-if="catalog" class="pc-sens-cov">
        <span class="pc-sens-cov-item">
          覆盖率 <strong>{{ coveragePercent }}</strong>
          <span class="pc-dim">（{{ coverage.classifiedColumns }} / {{ coverage.totalColumns }} 列）</span>
        </span>
        <span class="pc-sens-cov-item">
          打标表 <strong>{{ coverage.tablesWithTags }}</strong>
          <span class="pc-dim">/ {{ coverage.tablesCounted }}</span>
        </span>
        <span class="pc-sens-cov-item pc-dim">
          覆盖率不受下方筛选影响——筛级别改的是「列出哪些标签」，不是「范围里有多少列」
        </span>
      </div>
    </PanelCard>

    <PanelCard v-if="catalog" flush class="pc-gap-top">
      <div class="pc-list-head">
        <span class="pc-list-title">列级标签</span>
        <span class="pc-list-tools pc-dim">{{ totalLabel }}</span>
      </div>
      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        empty-text="这个范围里还没有任何标签。手工打一条，或去总览页发起一次元数据扫描。"
      >
        <template #actions>
          <el-button size="small" type="primary" :disabled="!catalog" @click="openCreate">
            手工打标
          </el-button>
        </template>
        <el-table v-loading="loading" :data="rows" size="small">
          <el-table-column label="列" min-width="260">
            <template #default="{ row }">
              <template v-if="row.tableLevel">
                <span class="pc-mono">{{ row.table }}</span>
                <el-tag size="small" type="info" class="pc-sens-chip">表级</el-tag>
              </template>
              <template v-else>
                <span class="pc-mono pc-dim">{{ row.database }}.{{ row.table }}.</span>
                <span class="pc-mono pc-sens-col">{{ row.column }}</span>
              </template>
              <div class="pc-sens-ref pc-dim">{{ row.columnRef }}</div>
            </template>
          </el-table-column>
          <el-table-column label="标签键" min-width="180">
            <template #default="{ row }">
              <code class="pc-mono">{{ row.tagKey }}</code>
              <div v-if="row.tagValue" class="pc-dim pc-sens-val">{{ row.tagValue }}</div>
            </template>
          </el-table-column>
          <el-table-column label="级别" width="100">
            <template #default="{ row }">
              <el-tag v-if="row.level" size="small" :type="levelTag(row.level)">
                {{ row.level }}
              </el-tag>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="来源" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="sourceTag(row.source)">{{ sourceLabel(row.source) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="置信度" width="90">
            <template #default="{ row }">
              <span v-if="row.confidence == null" class="pc-dim">—</span>
              <span v-else class="pc-mono">{{ row.confidence.toFixed(2) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
                {{ row.enabled ? '生效' : '停用' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="更新时间" width="180">
            <template #default="{ row }">
              <TimeText :millis="row.updatedAt" />
              <template v-if="row.updatedBy"> · {{ row.updatedBy }}</template>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="96" fixed="right">
            <template #default="{ row }">
              <IconAction icon="Edit" label="改" type="primary" @click="openEdit(row)" />
              <!-- 表级标签不给删除按钮：它的值是「该表所有列标签 max(level)」的派生结论，
                   删掉它并不会让这个结论消失（下一轮聚合又会算出来），只会让「这张表
                   最敏感的一列是什么」在目录检索里查不到。要改就改列级标签。 -->
              <IconAction
                v-if="!row.tableLevel"
                icon="Delete"
                label="删"
                type="danger"
                @click="confirmDelete(row)"
              />
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

    <!-- 打标 / 改标对话框。更新必须带 entityVersion（服务端不匹配回 409，控制台不自动
         重试——标签是人工治理结论，两个人同时改必须由人裁决）。 -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.mode === 'create' ? '手工打标' : '修改标签'"
      width="560px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="catalog">
          <el-input :model-value="dialog.catalog" disabled />
        </el-form-item>
        <el-form-item label="库" required>
          <el-input v-model="dialog.database" placeholder="数据库名" />
        </el-form-item>
        <el-form-item label="表" required>
          <el-input v-model="dialog.table" placeholder="表名" />
        </el-form-item>
        <el-form-item label="列">
          <el-input
            v-model="dialog.column"
            placeholder="留空 = 表级标签（不参与策略展开，仅用于检索）"
          />
          <div class="pc-dim pc-sens-hint">
            列名不做存在性校验：Lance 表不写元数据、external 表注册后未刷新时都读不到列清单，
            强制校验等于「这张表上的标签永远打不上」。
          </div>
        </el-form-item>
        <el-form-item label="标签键" required>
          <el-input v-model="dialog.tagKey" placeholder="例如 PII.CN_ID_CARD、owner.team" />
          <div class="pc-dim pc-sens-hint">
            敏感类型的键形如 <code class="pc-mono">类别.类型编码</code>；表级级别用
            <code class="pc-mono">sensitivity.level</code>。
          </div>
        </el-form-item>
        <el-form-item label="标签值">
          <el-input v-model="dialog.tagValue" placeholder="例如 L4（分级值）或团队名" />
        </el-form-item>
        <el-form-item v-if="dialog.mode === 'edit'" label="当前版本（entityVersion）">
          <el-input :model-value="String(dialog.entityVersion)" disabled />
        </el-form-item>
        <el-form-item v-if="dialog.mode === 'edit'" label="状态">
          <el-switch v-model="dialog.enabled" active-text="生效" inactive-text="停用" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button size="small" @click="dialog.open = false">取消</el-button>
        <el-button size="small" type="primary" :loading="dialog.saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipAlert from '@/components/TipAlert.vue'
import { describeError } from '@/api/client.js'
import { sensitivityApi } from '@/api/sensitivity.js'
import { confirmDanger, notifyError, notifySuccess } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * 敏感标签清单（mockup `#tags`）。设计稿 §8.4 的对外主接口在前端的一个消费者。
 *
 * <p>用**远程分页**而不是本地切页：标签是「只为命中的列落」（设计稿 §13 的末条风险），
 * 量级随表数与列数增长，界面上必须强制分页 + 按 catalog 过滤。分页参数进
 * `params.query`，client.js 会把顶层 `query` 当场抛错。
 */
const LEVEL_OPTIONS = [
  { value: 'L4', label: 'L4 机密' },
  { value: 'L3', label: 'L3 敏感' },
  { value: 'L2', label: 'L2 内部' },
  { value: 'L1', label: 'L1 公开' },
  { value: 'IMPORTANT', label: 'IMPORTANT 重要' },
  { value: 'CORE', label: 'CORE 核心' },
]

const catalog = ref('')
const levelFilter = ref('')
/**
 * 表过滤器（`库.表` 两段，服务端按整体比较）。
 *
 * <p>`?table=` 预填的来源是表详情「敏感数据」页签的「手工新增标签」按钮——
 * 站在一张表里点它，落点就应当是那张表的标签。空串表示不带过滤（服务端认空串）。
 * 只做**文本框预填**，不在挂载时自动弹对话框：深链接是「我已经站在这张表前」，
 * 该看到的是这张表已有的标签，建不建由人决定。
 */
const tableFilter = ref(String(useRoute().query.table || ''))

const loading = ref(false)
const error = ref('')
const rows = ref([])
const total = ref(0)
const page = ref(1)
const pageSize = ref(20)
const coverage = ref({
  classifiedColumns: 0,
  totalColumns: 0,
  tablesWithTags: 0,
  tablesCounted: 0,
  totalTables: 0,
  byLevel: {},
  bySource: {},
})

const catalogs = computed(() => session.state.catalogs || [])

const dialog = reactive({
  open: false,
  mode: 'create',
  saving: false,
  id: null,
  catalog: '',
  database: '',
  table: '',
  column: '',
  tagKey: '',
  tagValue: '',
  entityVersion: 0,
  enabled: true,
})

onMounted(() => {
  // `?catalog=` 优先于全局选中项：表详情页跳过来时带的是**那张表所在的 catalog**，
  // 而全局选中项可能已经被切到别处（切了就会看到另一批标签，还以为是这张表的）。
  catalog.value = String(useRoute().query.catalog || '') || session.state.selectedCatalog || ''
  if (catalog.value) load()
})

function onCatalogChange(value) {
  session.selectCatalog(value)
  page.value = 1
  rows.value = []
  load()
}

async function load() {
  if (!catalog.value) return
  loading.value = true
  error.value = ''
  const name = catalog.value
  try {
    const payload = await sensitivityApi.listTags({
      catalog: name,
      table: tableFilter.value.trim(),
      level: levelFilter.value,
      page: page.value,
      pageSize: pageSize.value,
    })
    // 切过 catalog 之后的迟到响应直接丢掉，否则会看到上一个 catalog 的标签。
    if (name !== catalog.value) return
    rows.value = payload.items || []
    total.value = payload.meta?.total ?? rows.value.length
    if (payload.coverage) coverage.value = payload.coverage
  } catch (cause) {
    if (name !== catalog.value) return
    error.value = describeError(cause)
    notifyError(cause)
  } finally {
    loading.value = false
  }
}

function onPageChange(next) {
  page.value = next
  load()
}

function onSizeChange(size) {
  pageSize.value = size
  page.value = 1
  load()
}

const totalLabel = computed(() => {
  if (loading.value) return '加载中'
  return `共 ${total.value} 条`
})

const coveragePercent = computed(() => {
  const totalColumns = coverage.value.totalColumns || 0
  if (!totalColumns) return '—'
  return `${((coverage.value.classifiedColumns / totalColumns) * 100).toFixed(1)}%`
})

// ------------------------------------------------------------------ 展示映射

/** 来源徽标：`AUTO` 自动 / `MANUAL` 人工 / `INHERITED` 继承——人工与自动必须一眼可分。 */
function sourceLabel(value) {
  return { AUTO: '自动', MANUAL: '人工', INHERITED: '继承' }[value] || value || '—'
}

function sourceTag(value) {
  return { AUTO: 'primary', MANUAL: 'success', INHERITED: 'warning' }[value] || 'info'
}

function levelTag(value) {
  return {
    L4: 'danger',
    L3: 'warning',
    L2: 'primary',
    L1: 'info',
    IMPORTANT: 'danger',
    CORE: 'danger',
  }[value] || 'info'
}

// ------------------------------------------------------------------ 打标 / 改标

function resetDialog() {
  dialog.id = null
  dialog.catalog = catalog.value
  dialog.database = ''
  dialog.table = ''
  dialog.column = ''
  dialog.tagKey = ''
  dialog.tagValue = ''
  dialog.entityVersion = 0
  dialog.enabled = true
}

function openCreate() {
  resetDialog()
  dialog.mode = 'create'
  // 从筛选框里带一个表名进来：手工打标几乎总是「看着某张表的行去补一条」。
  const parts = tableFilter.value.trim().split('.')
  if (parts.length === 2) {
    dialog.database = parts[0]
    dialog.table = parts[1]
  }
  dialog.open = true
}

function openEdit(row) {
  dialog.mode = 'edit'
  dialog.id = row.id
  dialog.catalog = row.catalog
  dialog.database = row.database
  dialog.table = row.table
  dialog.column = row.column || ''
  dialog.tagKey = row.tagKey
  dialog.tagValue = row.tagValue || ''
  dialog.entityVersion = row.entityVersion
  dialog.enabled = row.enabled
  dialog.open = true
}

async function save() {
  if (!dialog.database.trim() || !dialog.table.trim() || !dialog.tagKey.trim()) {
    notifyError(new Error('库、表、标签键都必须填写'))
    return
  }
  dialog.saving = true
  try {
    const body = {
      catalog: dialog.catalog,
      database: dialog.database.trim(),
      table: dialog.table.trim(),
      // 空串 = 表级标签（服务端的 TableLevelColumn 就是空串），因此这里刻意不 trim 成 null。
      column: dialog.column.trim(),
      tagKey: dialog.tagKey.trim(),
      tagValue: dialog.tagValue,
    }
    if (dialog.mode === 'edit') {
      // 更新必须带版本：不带服务端按 409 处理（缺版本 == 有并发风险），
      // 这不是「可选优化」而是契约。
      body.entityVersion = dialog.entityVersion
      body.enabled = dialog.enabled
    }
    await sensitivityApi.upsertTag(body)
    notifySuccess(dialog.mode === 'create' ? '标签已写入' : '标签已更新')
    dialog.open = false
    await load()
  } catch (cause) {
    // 409 不自动重试：标签是治理结论，冲突必须由人裁决。页面把冲突原文显示出来，
    // 让人刷新后重新决策（而不是替他选一个值）。
    notifyError(cause)
  } finally {
    dialog.saving = false
  }
}

async function confirmDelete(row) {
  const target = row.tableLevel ? row.table : `${row.database}.${row.table}.${row.column}`
  const ok = await confirmDanger({
    title: '删除标签',
    target,
    detail: `将删除标签「${row.tagKey}」。这不会写抑制项——下一次扫描若再次命中，`
      + '该列可能重新出现标签。',
  })
  if (!ok) return
  try {
    await sensitivityApi.deleteTag(row.id)
    notifySuccess('标签已删除')
    await load()
  } catch (cause) {
    notifyError(cause)
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
  width: 200px;
}

.pc-sens-level {
  width: 140px;
}

.pc-sens-table {
  width: 200px;
}

.pc-sens-cov {
  display: flex;
  flex-wrap: wrap;
  gap: 18px;
  padding: 0 14px 12px;
  font-size: 12px;
}

.pc-sens-cov-item strong {
  font-weight: 600;
}

.pc-sens-col {
  font-weight: 600;
}

.pc-sens-ref {
  font-size: 11.5px;
  word-break: break-all;
}

.pc-sens-val {
  font-size: 12px;
}

.pc-sens-chip {
  margin-left: 6px;
}

.pc-sens-hint {
  margin-top: 4px;
  font-size: 11.5px;
  line-height: 1.6;
}
</style>
