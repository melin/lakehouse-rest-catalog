<template>
  <div class="pc-page">
    <PageHeader
      title="主体视角试算"
      subtitle="以某个主体的视角看这张表 · 只读 · 不落库 · 服务端按与 Spark 完全相同的 AST 求值"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'policies' })">返回总览</el-button>
      </template>
    </PageHeader>

    <PanelCard class="pc-gap">
      <div class="pc-dryrun-form">
        <div>
          <div class="pc-dim pc-dryrun-label">主体</div>
          <!-- el-select-free-input：被模拟的主体是自由输入——「新建主体还没挂角色」
               正是试算要覆盖的场景（$principal 折名字字面量、角色名单查不到折 false） -->
          <el-select
            v-model="principal"
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            :loading="principalsLoading"
            placeholder="选择或输入主体名（user:/group:/role:）"
            class="pc-dryrun-subject"
          >
            <el-option
              v-for="name in principalNames"
              :key="name"
              :label="name"
              :value="name"
            />
          </el-select>
        </div>
        <div class="pc-dryrun-grow">
          <div class="pc-dim pc-dryrun-label">模拟语句（示意，试算按表级策略整表求值）</div>
          <el-input
            :model-value="`SELECT * FROM ${catalog}.${database}.${table} LIMIT 20`"
            readonly
            class="pc-mono"
          />
        </div>
        <el-button type="primary" :loading="running" :disabled="!principal" @click="run">
          运行试算
        </el-button>
      </div>
    </PanelCard>

    <!-- ============ 命中状态：四态与 resolve 同语义，措辞刻意分开 ============ -->
    <template v-if="result">
      <el-alert
        v-if="result.status === 'FOUND'"
        type="success"
        :closable="false"
        show-icon
        class="pc-gap"
      >
        <template #title>命中策略 v{{ result.version }}</template>
        <span class="pc-mono">{{ principal }}</span> 命中
        <span class="pc-mono">{{ catalog }}.{{ database }}.{{ table }}</span> 的策略，
        试算返回 <b>{{ result.preview?.rows?.length ?? 0 }}</b> 行。
        <code v-if="result.rowFilterSql" class="pc-mono pc-dryrun-sql">{{ result.rowFilterSql }}</code>
      </el-alert>
      <el-alert
        v-else-if="result.status === 'EXEMPT'"
        type="warning"
        :closable="false"
        show-icon
        class="pc-gap"
      >
        <template #title>豁免名单内 · 按原样明文返回</template>
        <span class="pc-mono">{{ principal }}</span> 在豁免名单里，读这张表完全绕过策略。
        豁免优先于一切判定——确认名单里应该有他。
      </el-alert>
      <el-alert
        v-else-if="result.status === 'DISABLED'"
        type="info"
        :closable="false"
        show-icon
        class="pc-gap"
      >
        <template #title>策略已停用 · 放行</template>
        这张表的策略存在但被停用（enabled=false），对该主体等价于没策略。
      </el-alert>
      <el-alert v-else type="error" :closable="false" show-icon class="pc-gap">
        <template #title>未命中策略 · 按默认放行，全部为明文</template>
        <span class="pc-mono">{{ principal }}</span> 没有匹配到这张表的任何策略（或不在主体
        白名单内）→ 按默认放行。这是最容易误解的一档：
        <strong>「没策略」与「策略服务不可达时 fail-closed 拒绝」是两件事</strong>。
      </el-alert>

      <!-- ============ 结果表 ============ -->
      <PanelCard :title="`返回 ${result.preview?.rows?.length ?? 0} 行`" class="pc-gap">
        <template #actions>
          <span v-if="result.preview?.scanned" class="pc-dim pc-dryrun-hint">
            试算耗时 {{ result.preview.scanned.elapsedMs }} ms
          </span>
        </template>
        <ResourceState
          :loading="running"
          :error="runError"
          :empty="!(result.preview?.rows || []).length"
          :empty-text="emptyText"
        >
          <el-table :data="tableRows" size="small" border>
            <el-table-column
              v-for="column in result.preview?.columns || []"
              :key="column.name"
              :label="columnLabel(column)"
              min-width="140"
            >
              <template #default="{ row }">
                <template v-if="isMasked(column.name)">
                  <span :class="{ 'pc-dryrun-null': cellOf(row, column.name) === null }">
                    {{ displayValue(cellOf(row, column.name)) }}
                  </span>
                  <el-tag size="small" type="warning" class="pc-dryrun-celltag">
                    {{ cellOf(row, column.name) === null ? '置空' : '脱敏' }}
                  </el-tag>
                </template>
                <span
                  v-else-if="cellOf(row, column.name) === null"
                  class="pc-dim"
                >
                  NULL
                </span>
                <span v-else class="pc-mono">{{ displayValue(cellOf(row, column.name)) }}</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="result.preview?.warnings?.length" class="pc-dryrun-warnings">
            <div v-for="(warning, index) in result.preview.warnings" :key="index" class="pc-dim">
              ⚠ {{ warning }}
            </div>
          </div>
        </ResourceState>
      </PanelCard>

      <el-alert type="info" :closable="false" show-icon>
        <template #title>一致性要求</template>
        试算结果与 Spark 实际返回必须<strong>逐行一致</strong>（同一份 AST、两套求值器）。
        不一致就是缺陷，不是「预览精度问题」。脱敏列的取值已按策略改写；角标标记哪些列被改了。
        试算只读、不落库——审计里记的是操作者本人（TABLE_PREVIEWED），不是被模拟的主体。
      </el-alert>
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'

import { describeError } from '@/api/client.js'
import { managementApi } from '@/api/management.js'
import { policyApi } from '@/api/policy.js'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import { notifyError } from '@/composables/index.js'

/**
 * 主体视角试算页（mockup 画面 4）：给治理方一个「用他的眼睛看一次」的工具，
 * 避免策略上线后才发现条件写反。数据来自
 * `POST /policies/tables/{c}/{d}/{t}/dry-run`——服务端按**被模拟主体**（不是当前
 * 登录者）判定四态并折叠 `$principal`，命中时用预览求值链读一页数据。
 *
 * <p>「未命中策略」必须是红色并明确说「明文」（mockup 说明 2）：含糊过去等于埋雷。
 * 脱敏单元格加角标区分「改了值」（脱敏）与「没了」（置空）——改写值的来源是
 * 策略详情里的 mask 列清单，试算响应本身不标（预览响应与普通预览共用一份形状）。
 */
const route = useRoute()

const catalog = computed(() => String(route.params.catalog || ''))
const database = computed(() => String(route.params.database || ''))
const table = computed(() => String(route.params.table || ''))

// ------------------------------------------------------------------ 主体候选

const principal = ref('')
const principalNames = ref([])
const principalsLoading = ref(false)

onMounted(async () => {
  principalsLoading.value = true
  try {
    const payload = await managementApi.listPrincipals({ page: 1, pageSize: 500 })
    principalNames.value = (payload.principals || []).map((item) => item.name)
  } catch {
    // 候选拉不到不挡路：主体框本来就允许自由输入
  } finally {
    principalsLoading.value = false
  }
})

// ------------------------------------------------------------------ 运行

const running = ref(false)
const runError = ref('')
const result = ref(null)
/** 策略详情里的脱敏列清单：结果表的角标判据。 */
const maskedColumns = ref([])

const emptyText = computed(() => {
  if (!result.value) return ''
  if (result.value.status === 'FOUND') {
    return '试算返回 0 行：行过滤把这页样本里的行都过滤掉了（或表本身没有数据）。确认条件的方向没写反。'
  }
  return '没有返回数据。'
})

async function run() {
  running.value = true
  runError.value = ''
  result.value = null
  maskedColumns.value = []
  try {
    const [dryRun, policy] = await Promise.all([
      policyApi.dryRun(catalog.value, database.value, table.value, principal.value),
      policyApi.get(catalog.value, database.value, table.value).catch(() => null),
    ])
    result.value = dryRun
    maskedColumns.value = (policy?.masks || []).map((entry) => entry.column)
  } catch (cause) {
    runError.value = describeError(cause)
    notifyError(cause)
  } finally {
    running.value = false
  }
}

// ------------------------------------------------------------------ 表格渲染

const tableRows = computed(() => (result.value?.preview?.rows || []).map((row, index) => ({
  __index: index,
  __values: row,
})))

function cellOf(row, columnName) {
  const columns = result.value?.preview?.columns || []
  const index = columns.findIndex((column) => column.name === columnName)
  return index >= 0 ? row.__values[index] : null
}

function isMasked(columnName) {
  return maskedColumns.value.includes(columnName)
}

function columnLabel(column) {
  return column.primaryKey ? `${column.name} 🔑` : column.name
}

function displayValue(value) {
  if (value === null || value === undefined) return 'NULL'
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
</script>

<style scoped>
.pc-gap {
  margin-bottom: 12px;
}

.pc-dryrun-form {
  display: flex;
  gap: 12px;
  align-items: flex-end;
  flex-wrap: wrap;
}

.pc-dryrun-grow {
  flex: 1;
  min-width: 260px;
}

.pc-dryrun-label {
  margin-bottom: 4px;
  font-size: 12px;
}

.pc-dryrun-subject {
  width: 260px;
}

.pc-dryrun-sql {
  display: block;
  margin-top: 4px;
  padding: 3px 6px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  word-break: break-all;
}

.pc-dryrun-hint {
  font-size: 12px;
}

.pc-dryrun-celltag {
  margin-left: 6px;
}

.pc-dryrun-null {
  color: var(--el-color-danger);
}

.pc-dryrun-warnings {
  margin-top: 8px;
}
</style>
