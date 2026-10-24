<template>
  <div class="pc-page">
    <PageHeader
      title="新建数据策略"
      subtitle="发布后对新查询生效；同一张表只有一条策略，改动会生成新版本。"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'policies' })">取消</el-button>
        <el-button
          size="small"
          type="primary"
          :loading="saving"
          :disabled="!ready"
          @click="publish"
        >
          发布策略
        </el-button>
      </template>
    </PageHeader>

    <el-alert type="warning" :closable="false" show-icon class="pc-gap">
      <template #title>发布前请确认 · 这一步之后没有「预览模式」</template>
      策略一发布就对新查询生效。要小范围验证，先去「试算」里换几个主体看结果，再发布。
      保存是<strong>整份替换</strong>：本次提交的行过滤、脱敏列、豁免名单、主体范围就是全部。
    </el-alert>

    <div class="pc-wizard-split">
      <div>
        <!-- ============ 步骤 1 · 目标与主体 ============ -->
        <PanelCard title="1 · 目标与主体" class="pc-gap">
          <div class="pc-form-row">
            <span class="pc-form-label">目标表</span>
            <div class="pc-form-body">
              <div class="pc-wizard-target">
                <el-select
                  v-model="target.catalog"
                  filterable
                  placeholder="catalog"
                  class="pc-wizard-seg"
                  :loading="catalogLoading"
                >
                  <el-option v-for="name in catalogs" :key="name" :label="name" :value="name" />
                </el-select>
                <span class="pc-wizard-dot">.</span>
                <el-select
                  v-model="target.database"
                  filterable
                  placeholder="database"
                  class="pc-wizard-seg"
                  :loading="databaseLoading"
                  :disabled="!target.catalog"
                >
                  <el-option v-for="name in databases" :key="name" :label="name" :value="name" />
                </el-select>
                <span class="pc-wizard-dot">.</span>
                <el-select
                  v-model="target.table"
                  filterable
                  placeholder="table"
                  class="pc-wizard-seg"
                  :loading="tableLoading"
                  :disabled="!target.database"
                >
                  <el-option v-for="name in tables" :key="name" :label="name" :value="name" />
                </el-select>
              </div>
              <div v-if="columns.length" class="pc-dim pc-wizard-note">
                {{ columns.length }} 列 · schema 已加载
              </div>
              <p class="pc-dim pc-wizard-note">
                策略按 <code class="pc-mono">catalog.database.table</code> 绑定：表被
                <code class="pc-mono">DROP</code> 时策略级联删除，<code class="pc-mono">RENAME</code>
                时跟随改名，同名重建<strong>不会</strong>继承旧策略。
              </p>
              <el-alert
                v-if="existing"
                type="error"
                :closable="false"
                show-icon
                class="pc-wizard-note"
              >
                <template #title>这张表已绑定策略（v{{ existing.version }}）</template>
                一张表最多一条策略——请去表详情的「数据策略」页签编辑现有策略，而不是在这里再建一条。
                <el-button size="small" text type="primary" @click="goDetail">去编辑</el-button>
              </el-alert>
            </div>
          </div>
          <div class="pc-form-row">
            <span class="pc-form-label">主体范围</span>
            <div class="pc-form-body">
              <el-radio-group v-model="subjectMode">
                <el-radio value="all">全部主体（推荐）</el-radio>
                <el-radio value="list">仅以下主体</el-radio>
              </el-radio-group>
              <template v-if="subjectMode === 'list'">
                <!-- el-select-free-input：主体名是自由输入（allow-create），候选来自治理侧、不维护静态清单 -->
                <el-select
                  v-model="draft.subjects"
                  multiple
                  filterable
                  allow-create
                  default-first-option
                  :reserve-keyword="false"
                  placeholder="输入主体名（如 user:zhangwei、role:etl），回车确认"
                  class="pc-wizard-tags"
                />
              </template>
              <p class="pc-dim pc-wizard-note">
                选择「全部主体」时，任何能通过 <code class="pc-mono">TABLE:SELECT</code>
                判权的人都按这套规则读数据。单个主体的例外要写在豁免名单里——同一张表不叠加多条策略。
              </p>
            </div>
          </div>
          <div class="pc-form-row">
            <span class="pc-form-label">豁免名单</span>
            <div class="pc-form-body">
              <!-- el-select-free-input：主体名是自由输入（allow-create），候选来自治理侧、不维护静态清单 -->
              <el-select
                v-model="draft.exemptPrincipals"
                multiple
                filterable
                allow-create
                default-first-option
                :reserve-keyword="false"
                placeholder="需要原样明文的主体（如 owner、审计），回车确认"
                class="pc-wizard-tags"
              />
              <p class="pc-dim pc-wizard-note">
                名单内的主体读这张表时完全绕过本策略。豁免优先于一切判定，只给明确需要原值的角色。
              </p>
            </div>
          </div>
          <div class="pc-form-row">
            <span class="pc-form-label">整列不可见</span>
            <div class="pc-form-body">
              <el-select
                v-model="draft.hiddenColumns"
                multiple
                filterable
                placeholder="选择要整列隐藏的列（可选）"
                class="pc-wizard-tags"
              >
                <el-option
                  v-for="field in columns"
                  :key="field.name"
                  :label="field.name"
                  :value="field.name"
                />
              </el-select>
              <p class="pc-dim pc-wizard-note">
                清单内的列对非豁免主体<strong>整个不可见</strong>：SELECT * 只返回其余列、
                显式引用直接失败——比脱敏更严。与脱敏互斥：同一列只能二选一。
                行过滤仍可引用这些列（按原值判定）。
              </p>
            </div>
          </div>
          <div class="pc-form-row">
            <span class="pc-form-label">失败行为</span>
            <div class="pc-form-body">
              <el-tag type="success">fail-closed · 拒绝查询</el-tag>
              <span class="pc-dim pc-wizard-note-inline">
                策略取不到 / 版本校验失败 / 表达式引用的列不存在 → 查询报错，不降级为明文。不可关闭。
              </span>
            </div>
          </div>
        </PanelCard>

        <!-- ============ 步骤 2 · 行过滤 ============ -->
        <PanelCard title="2 · 行过滤（可选 · 不设置则全表行可见）" class="pc-gap">
          <div v-for="(cond, index) in draft.conditions" :key="index" class="pc-policy-cond">
            <el-select v-model="cond.column" filterable placeholder="列" class="pc-policy-col">
              <el-option
                v-for="field in columns"
                :key="field.name"
                :label="field.name"
                :value="field.name"
              />
            </el-select>
            <el-select v-model="cond.op" class="pc-policy-op">
              <el-option v-for="op in ROW_OPS" :key="op.value" :label="op.label" :value="op.value" />
            </el-select>
            <template v-if="needsValue(cond.op)">
              <el-select v-if="cond.op === 'in'" v-model="cond.valueKind" class="pc-policy-kind">
                <el-option label="字面量列表" value="lit" />
                <el-option label="调用者的角色名单（$principal_roles）" value="roles" />
              </el-select>
              <el-select v-else v-model="cond.valueKind" class="pc-policy-kind">
                <el-option label="字面量" value="lit" />
                <el-option label="当前调用者（current_user）" value="principal" />
              </el-select>
              <el-input
                v-if="cond.valueKind === 'lit'"
                v-model="cond.literal"
                :placeholder="cond.op === 'in' ? '逗号分隔，如 CN,US' : '值'"
                class="pc-policy-value"
              />
            </template>
            <el-button size="small" text type="danger" @click="draft.conditions.splice(index, 1)">
              移除
            </el-button>
          </div>
          <div class="pc-tools-row">
            <el-button size="small" @click="addCondition">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">添加条件</span>
            </el-button>
            <el-radio-group v-if="draft.conditions.length > 1" v-model="draft.combine" size="small">
              <el-radio-button value="and">全部满足（AND）</el-radio-button>
              <el-radio-button value="or">任一满足（OR）</el-radio-button>
            </el-radio-group>
            <span v-if="draft.conditions.length === 0" class="pc-dim">不设行过滤 = 不限制行。</span>
          </div>
          <p class="pc-dim pc-wizard-note">
            条件结果为空（NULL）时按「不通过」处理：服务端会自动包一层
            <code class="pc-mono">coalesce(..., false)</code>——否则「被过滤」与「条件写错了」
            在结果里长得一样。
          </p>
        </PanelCard>

        <!-- ============ 步骤 3 · 列脱敏 ============ -->
        <PanelCard title="3 · 列脱敏（可选）" class="pc-gap">
          <div v-for="(mask, index) in draft.masks" :key="index" class="pc-policy-mask">
            <el-select v-model="mask.column" filterable placeholder="列" class="pc-policy-col">
              <el-option
                v-for="field in columns"
                :key="field.name"
                :label="field.name"
                :value="field.name"
              />
            </el-select>
            <el-select v-model="mask.fn" class="pc-policy-fn" @change="mask.arg = defaultArg(mask.fn)">
              <el-option v-for="kind in MASK_KINDS" :key="kind.fn" :label="kind.label" :value="kind.fn" />
            </el-select>
            <template v-if="argKindOf(mask.fn) === 'int'">
              <el-input-number
                v-model="mask.arg"
                :min="0"
                :step="1"
                controls-position="right"
                class="pc-policy-arg"
              />
            </template>
            <el-select v-else-if="argKindOf(mask.fn) === 'select'" v-model="mask.arg" class="pc-policy-arg">
              <el-option v-for="item in HASH_ALGORITHMS" :key="item" :label="item" :value="item" />
            </el-select>
            <el-input
              v-else-if="argKindOf(mask.fn) === 'string'"
              v-model="mask.arg"
              placeholder="固定值"
              class="pc-policy-arg"
            />
            <el-checkbox v-model="mask.allowNullOnRequired">允许对必填列产生 NULL</el-checkbox>
            <el-button size="small" text type="danger" @click="draft.masks.splice(index, 1)">
              移除
            </el-button>
          </div>
          <div class="pc-tools-row">
            <el-button size="small" @click="addMask">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">添加脱敏列</span>
            </el-button>
            <span v-if="duplicateMaskColumns" class="pc-policy-warn">
              同一列配置了多次：{{ duplicateMaskColumns }}
            </span>
          </div>
          <p class="pc-dim pc-wizard-note">
            哈希算法必须显式选：不提供「跟随集群配置」，否则同一份策略在两个集群给出不同结果，
            且不会报错。逃生口表达式（<code class="pc-mono">{"expr": ...}</code>）不在界面里编辑，
            走 REST API 或 Spark SQL。
          </p>
        </PanelCard>
      </div>

      <!-- ============ 右栏：覆盖面说明 ============ -->
      <div class="pc-wizard-rail">
        <PanelCard title="这份策略能挡住什么" class="pc-gap">
          <ul class="pc-wizard-impact">
            <li>
              <span>Spark 读写</span>
              <span class="pc-ok">按策略返回（本方案覆盖）</span>
            </li>
            <li>
              <span>控制台数据预览</span>
              <span class="pc-ok">按策略渲染</span>
            </li>
            <li>
              <span>文件下载</span>
              <span class="pc-ok">拒绝（403）</span>
            </li>
            <li>
              <span>列 min/max 统计</span>
              <span class="pc-ok">拒绝（可反推原值范围）</span>
            </li>
            <li>
              <span>直读文件 / Trino / Flink</span>
              <span class="pc-bad">不覆盖</span>
            </li>
          </ul>
          <p class="pc-dim pc-wizard-note">
            前三条是控制台自己收的口子——它们都不经过 Spark 计划，注入再正确也管不到；
            最后一行是强制等级 L0 的边界（见总览页说明）。
          </p>
        </PanelCard>
        <PanelCard title="就绪检查">
          <ul class="pc-wizard-check">
            <li :class="target.catalog && target.database && target.table ? 'pc-ok' : 'pc-dim'">
              目标表已选
            </li>
            <li :class="columns.length ? 'pc-ok' : 'pc-dim'">schema 已加载（编辑器要有列名）</li>
            <li :class="!existing ? 'pc-ok' : 'pc-bad'">这张表还没有策略</li>
            <li :class="!duplicateMaskColumns ? 'pc-ok' : 'pc-bad'">脱敏列没有重复</li>
          </ul>
        </PanelCard>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'

import { catalogApi } from '@/api/catalog.js'
import { managementApi } from '@/api/management.js'
import { policyApi } from '@/api/policy.js'
import PanelCard from '@/components/PanelCard.vue'
import PageHeader from '@/components/PageHeader.vue'
import { notifyError, notifySuccess } from '@/composables/index.js'
import {
  HASH_ALGORITHMS,
  MASK_KINDS,
  ROW_OPS,
  argKindOf,
  buildMasksPayload,
  buildRowFilterAst,
  defaultArg,
  duplicateMaskColumnText,
  emptyCondition,
  emptyMask,
} from '@/utils/policyAst.js'

/**
 * 新建数据策略的向导页（mockup 画面 2）。表详情的「数据策略」页签只管「当前表」，
 * 跨表入口在这里：目标表三段选择 + 同一份结构化编辑器（逻辑抽在
 * `utils/policyAst.js`，与本页和 `TablePolicyPanel` 共用，两处建出来的 AST 形状一致）。
 *
 * <p>表上已有策略时**不给建**：一张表最多一条，PUT 是整份替换——在这里再「新建」
 * 等于把现有策略整个顶掉，误操作的代价是治理口径静默丢失，所以引导去表详情编辑。
 */
const router = useRouter()

// ------------------------------------------------------------------ 目标表（三段级联）

const target = reactive({ catalog: '', database: '', table: '' })

const catalogs = ref([])
const databases = ref([])
const tables = ref([])
const catalogLoading = ref(false)
const databaseLoading = ref(false)
const tableLoading = ref(false)

async function loadCatalogs() {
  catalogLoading.value = true
  try {
    const payload = await managementApi.listCatalogs({ page: 1, pageSize: 500 })
    catalogs.value = (payload.catalogs || []).map((item) => item.name)
  } catch (cause) {
    notifyError(cause)
  } finally {
    catalogLoading.value = false
  }
}

loadCatalogs()

watch(() => target.catalog, async (prefix) => {
  databases.value = []
  tables.value = []
  target.database = ''
  target.table = ''
  if (!prefix) return
  databaseLoading.value = true
  try {
    const payload = await catalogApi.listDatabases(prefix, { page: 1, pageSize: 2000 })
    databases.value = (payload.databases || []).map((item) =>
      typeof item === 'string' ? item : item.name)
  } catch (cause) {
    notifyError(cause)
  } finally {
    databaseLoading.value = false
  }
})

watch(() => target.database, async (database) => {
  tables.value = []
  target.table = ''
  if (!database || !target.catalog) return
  tableLoading.value = true
  try {
    const payload = await catalogApi.listTables(target.catalog, database, { page: 1, pageSize: 2000 })
    tables.value = (payload.tables || []).map((item) =>
      typeof item === 'string' ? item : item.name)
  } catch (cause) {
    notifyError(cause)
  } finally {
    tableLoading.value = false
  }
})

// ------------------------------------------------------------------ schema 与既有策略

const columns = ref([])
/** 表上已有的策略（有 = 不让在这里新建）。`detail.table` 区分「有策略」与 204。 */
const existing = ref(null)

watch(() => [target.catalog, target.database, target.table], async ([prefix, database, table]) => {
  columns.value = []
  existing.value = null
  if (!prefix || !database || !table) return
  try {
    const detail = await catalogApi.getTable(prefix, database, table)
    columns.value = (detail?.schema?.fields || []).map((field) => ({
      name: field.name,
      type: field.type,
    }))
  } catch (cause) {
    notifyError(cause)
    return
  }
  try {
    const policy = await policyApi.get(prefix, database, table)
    existing.value = policy && policy.table ? policy : null
  } catch (cause) {
    notifyError(cause)
  }
})

// ------------------------------------------------------------------ 草稿

const subjectMode = ref('all')
const saving = ref(false)

const draft = reactive({
  combine: 'and',
  conditions: [],
  masks: [],
  subjects: [],
  exemptPrincipals: [],
  hiddenColumns: [],
})

function addCondition() {
  draft.conditions.push(emptyCondition(columns.value[0]?.name || ''))
}

function addMask() {
  const used = new Set(draft.masks.map((mask) => mask.column))
  const column = columns.value.find((field) => !used.has(field.name))
  draft.masks.push(emptyMask(column?.name || columns.value[0]?.name || ''))
}

function needsValue(op) {
  return op !== 'is_null' && op !== 'is_not_null'
}

const duplicateMaskColumns = computed(() => duplicateMaskColumnText(draft.masks))

const ready = computed(() => target.catalog && target.database && target.table
  && columns.value.length > 0 && !existing.value && !duplicateMaskColumns.value)

// ------------------------------------------------------------------ 发布

async function publish() {
  if (!ready.value) return
  saving.value = true
  try {
    const saved = await policyApi.put(target.catalog, target.database, target.table, {
      enabled: true,
      rowFilter: buildRowFilterAst(draft.conditions, draft.combine),
      masks: buildMasksPayload(draft.masks),
      hiddenColumns: draft.hiddenColumns,
      exemptPrincipals: draft.exemptPrincipals,
      subjects: subjectMode.value === 'list' ? draft.subjects : [],
    })
    notifySuccess(`策略已发布（v${saved?.version ?? '?'}），新查询 60 秒内生效`)
    router.push({
      name: 'table',
      params: {
        prefix: target.catalog,
        database: target.database,
        table: target.table,
      },
      query: { tab: 'policy' },
    })
  } catch (cause) {
    // 409 在这里不该出现（新建不带 baseVersion，表上确认过没策略）；
    // 若真出现，多半是并发建策略——服务端的报错会指名说明
    notifyError(cause)
  } finally {
    saving.value = false
  }
}

function goDetail() {
  router.push({
    name: 'table',
    params: {
      prefix: target.catalog,
      database: target.database,
      table: target.table,
    },
    query: { tab: 'policy' },
  })
}
</script>

<style scoped>
.pc-gap {
  margin-bottom: 12px;
}

.pc-wizard-split {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 12px;
  align-items: start;
}

.pc-form-row {
  display: grid;
  grid-template-columns: 90px minmax(0, 1fr);
  gap: 12px;
  margin-bottom: 14px;
}

.pc-form-label {
  font-size: 13px;
  color: var(--el-text-color-regular);
  padding-top: 4px;
}

.pc-form-body {
  min-width: 0;
}

.pc-wizard-target {
  display: flex;
  align-items: center;
  gap: 6px;
}

.pc-wizard-seg {
  width: 200px;
}

.pc-wizard-dot {
  color: var(--el-text-color-secondary);
}

.pc-wizard-tags {
  width: 100%;
  margin-top: 6px;
}

.pc-wizard-note {
  margin: 6px 0 0;
  font-size: 12px;
}

.pc-wizard-note-inline {
  margin-left: 10px;
  font-size: 12px;
}

.pc-wizard-rail {
  display: flex;
  flex-direction: column;
}

.pc-wizard-impact {
  list-style: none;
  margin: 0;
  padding: 0;
}

.pc-wizard-impact li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 7px 0;
  border-bottom: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
}

.pc-wizard-impact li:last-child {
  border-bottom: none;
}

.pc-wizard-check {
  list-style: none;
  margin: 0;
  padding: 0;
}

.pc-wizard-check li {
  padding: 5px 0;
  font-size: 13px;
}

.pc-ok {
  color: var(--el-color-success);
}

.pc-bad {
  color: var(--el-color-danger);
}

.pc-policy-cond,
.pc-policy-mask {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.pc-policy-col {
  width: 200px;
}

.pc-policy-op {
  width: 190px;
}

.pc-policy-kind {
  width: 240px;
}

.pc-policy-value {
  width: 260px;
}

.pc-policy-fn {
  width: 260px;
}

.pc-policy-arg {
  width: 150px;
}

.pc-policy-warn {
  color: var(--el-color-danger);
  font-size: 12px;
}

@media (max-width: 1100px) {
  .pc-wizard-split {
    grid-template-columns: 1fr;
  }
}
</style>
