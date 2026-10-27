<template>
  <div class="pc-policy">
    <ResourceState :loading="policyLoading" :error="policyError" />

    <template v-if="loaded">
      <!-- ============================ 编辑态（新建 / 修改共用一份草稿） ============================ -->
      <template v-if="editing">
        <PanelCard :title="isNew ? '新建数据策略' : `编辑数据策略（将生成 v${currentVersion + 1}）`">
          <template #actions>
            <el-button size="small" @click="cancelEdit">取消</el-button>
            <el-button size="small" type="primary" :loading="saving" @click="save">
              {{ isNew ? '发布策略' : '保存' }}
            </el-button>
          </template>

          <TipAlert type="info" class="pc-hint">
            <template #title>保存即整份替换</template>
            这里提交的是<strong>完整策略</strong>：行过滤、脱敏列、豁免名单、主体白名单都以本次
            保存为准——「这次少了一列脱敏」就是删掉那一列，不是保持原样。并发修改时后保存的
            一方会收到冲突提示，需要刷新后重看最新版本再决定怎么改。
          </TipAlert>

          <!-- ---------------- 行过滤 ---------------- -->
          <SectionTitle>行过滤</SectionTitle>
          <p class="pc-dim pc-policy-note">
            查询这张表时只返回满足条件的行。条件之间用
            <code class="pc-mono">{{ draft.combine.toUpperCase() }}</code> 组合；
            值按字面量提交（纯数字自动按数值），「当前调用者」在下发时折成访问者的主体名。
          </p>

          <el-alert
            v-if="draft.advancedRowFilter"
            type="warning"
            :closable="false"
            show-icon
            class="pc-hint"
          >
            <template #title>现有行过滤超出结构化编辑器的表达范围</template>
            它包含编辑器不支持的结构（嵌套组合、函数表达式、CASE WHEN 等），下方编辑器对它
            <strong>原样保留</strong>：不改动这里就直接保存，改动则会用编辑器内容整体替换。
            要精细调整请走 REST API 或 Spark SQL（参考文档第 11 节）。
            <div class="pc-policy-sql">{{ draft.rawRowFilterSql || '(无 SQL 文本)' }}</div>
          </el-alert>

          <template v-if="!draft.advancedRowFilter">
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
                <el-option
                  v-for="op in ROW_OPS"
                  :key="op.value"
                  :label="op.label"
                  :value="op.value"
                />
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
              <el-button
                size="small"
                text
                type="danger"
                @click="draft.conditions.splice(index, 1)"
              >
                移除
              </el-button>
            </div>
            <div class="pc-tools-row">
              <el-button size="small" @click="addCondition">
                <el-icon><Plus /></el-icon>
                <span class="pc-btn-text">添加条件</span>
              </el-button>
              <el-radio-group
                v-if="draft.conditions.length > 1"
                v-model="draft.combine"
                size="small"
              >
                <el-radio-button value="and">全部满足（AND）</el-radio-button>
                <el-radio-button value="or">任一满足（OR）</el-radio-button>
              </el-radio-group>
              <span v-if="draft.conditions.length === 0" class="pc-dim">
                不设行过滤 = 不限制行。
              </span>
            </div>
          </template>

          <!-- ---------------- 列脱敏 ---------------- -->
          <SectionTitle>列脱敏</SectionTitle>
          <p class="pc-dim pc-policy-note">
            每列至多一种脱敏方式；被脱敏列的列统计不再下发，预览与 Spark 读取都会按同样的
            表达式改写返回值。「允许对必填列产生 NULL」只对会置空值的方式有意义。
          </p>
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
              <el-option
                v-for="kind in MASK_KINDS"
                :key="kind.fn"
                :label="kind.label"
                :value="kind.fn"
              />
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
            <el-select
              v-else-if="argKindOf(mask.fn) === 'select'"
              v-model="mask.arg"
              class="pc-policy-arg"
            >
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

          <!-- ---------------- 整列不可见（P3 §5.8） ---------------- -->
          <div>
            <SectionTitle>整列不可见</SectionTitle>
            <p class="pc-dim pc-policy-note">
              清单内的列对非豁免主体<strong>整个不可见</strong>：SELECT * 只返回其余列、
              显式引用与过滤条件直接失败——比脱敏更严（脱敏改列值，列还在）。
              与脱敏互斥：同一列只能二选一。行过滤仍可引用这些列（按原值判定）。
            </p>
            <el-select
              v-model="draft.hiddenColumns"
              multiple
              filterable
              placeholder="选择要整列隐藏的列"
              class="pc-policy-tags"
            >
              <el-option
                v-for="field in columns"
                :key="field.name"
                :label="field.name"
                :value="field.name"
              />
            </el-select>
          </div>

          <!-- ---------------- 主体范围 / 豁免名单 ---------------- -->
          <div class="pc-policy-two-col">
            <div>
              <SectionTitle>主体范围</SectionTitle>
              <p class="pc-dim pc-policy-note">
                留空 = 策略对<strong>全部主体</strong>生效；填了名字就只对名单内的主体生效。
                主体名与权限页一致（如 <code class="pc-mono">user:zhangwei</code>、
                <code class="pc-mono">role:etl</code>、<code class="pc-mono">group:bi</code>）。
              </p>
              <!-- el-select-free-input：主体名是自由输入（allow-create），候选来自治理侧、不维护静态清单 -->
              <el-select
                v-model="draft.subjects"
                multiple
                filterable
                allow-create
                default-first-option
                :reserve-keyword="false"
                placeholder="输入或选择主体，回车确认"
                class="pc-policy-tags"
              />
            </div>
            <div>
              <SectionTitle>豁免名单</SectionTitle>
              <p class="pc-dim pc-policy-note">
                名单内的主体读这张表时<strong>完全绕过本策略</strong>（原样明文）。
                豁免优先于一切判定，只给明确需要原值的角色（如 owner、审计）。
              </p>
              <!-- el-select-free-input：主体名是自由输入（allow-create），候选来自治理侧、不维护静态清单 -->
              <el-select
                v-model="draft.exemptPrincipals"
                multiple
                filterable
                allow-create
                default-first-option
                :reserve-keyword="false"
                placeholder="输入或选择主体，回车确认"
                class="pc-policy-tags"
              />
            </div>
          </div>
        </PanelCard>
      </template>

      <!-- ============================ 已绑定：只读概览 ============================ -->
      <template v-else-if="detail">
        <PanelCard title="数据策略">
          <template #actions>
            <el-tag :type="detail.enabled ? 'success' : 'info'" size="small">
              {{ detail.enabled ? '生效中' : '已停用' }}
            </el-tag>
            <span class="pc-dim pc-policy-version">v{{ detail.version }}</span>
            <el-button size="small" :loading="policyLoading" @click="load">刷新</el-button>
            <el-button size="small" type="primary" plain @click="startEdit(false)">编辑</el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :loading="deleting"
              @click="removePolicy"
            >
              删除策略
            </el-button>
          </template>

          <div class="pc-policy-grid">
            <div class="pc-policy-item">
              <div class="pc-policy-label">行过滤</div>
              <div>
                <code v-if="detail.rowFilterSql" class="pc-mono pc-policy-sql">
                  {{ detail.rowFilterSql }}
                </code>
                <span v-else class="pc-dim">未设置（不限制行）</span>
              </div>
            </div>
            <div class="pc-policy-item">
              <div class="pc-policy-label">列脱敏</div>
              <div>
                <template v-if="(detail.masks || []).length">
                  <el-tag
                    v-for="mask in detail.masks"
                    :key="mask.column"
                    size="small"
                    class="pc-policy-chip"
                  >
                    {{ mask.column }} · {{ mask.sql }}
                    <template v-if="mask.allowNullOnRequired"> · 允许NULL</template>
                  </el-tag>
                </template>
                <span v-else class="pc-dim">未设置（全部列明文）</span>
              </div>
            </div>
            <div class="pc-policy-item">
              <div class="pc-policy-label">整列不可见</div>
              <div>
                <template v-if="(detail.hiddenColumns || []).length">
                  <el-tag
                    v-for="column in detail.hiddenColumns"
                    :key="column"
                    size="small"
                    type="info"
                    class="pc-policy-chip"
                  >
                    {{ column }} · 不可见
                  </el-tag>
                </template>
                <span v-else class="pc-dim">未设置（全部列可见）</span>
              </div>
            </div>
            <div class="pc-policy-item">
              <div class="pc-policy-label">主体范围</div>
              <div>
                <template v-if="(detail.subjects || []).length">
                  <el-tag
                    v-for="subject in detail.subjects"
                    :key="subject"
                    size="small"
                    type="warning"
                    class="pc-policy-chip"
                  >
                    {{ subject }}
                  </el-tag>
                </template>
                <span v-else>全部主体</span>
              </div>
            </div>
            <div class="pc-policy-item">
              <div class="pc-policy-label">豁免名单</div>
              <div>
                <template v-if="(detail.exemptPrincipals || []).length">
                  <el-tag
                    v-for="principal in detail.exemptPrincipals"
                    :key="principal"
                    size="small"
                    type="danger"
                    class="pc-policy-chip"
                  >
                    {{ principal }}
                  </el-tag>
                </template>
                <span v-else class="pc-dim">无豁免</span>
              </div>
            </div>
            <div class="pc-policy-item">
              <div class="pc-policy-label">最后修改</div>
              <div>
                <TimeText :millis="detail.updatedAt" />
                <template v-if="detail.updatedBy"> · {{ detail.updatedBy }}</template>
              </div>
            </div>
          </div>

          <TipAlert type="info" class="pc-hint pc-policy-footnote">
            <template #title>预览已按策略渲染，文件下载已禁用</template>
            本页「数据预览」的行与列都按这份策略改写（与服务端预览求值器同一份逻辑）；
            文件下载对带策略的表一律 403。注意这是<strong>引擎内强制</strong>
            （enforcedScope = {{ detail.enforcedScope || 'engine' }}）：拿到存储凭据的程序
            直读底层文件时策略不生效，详见设计文档第 7.1 节。
          </TipAlert>
        </PanelCard>
      </template>

      <!-- ============================ 未绑定：空态 ============================ -->
      <PanelCard v-else>
        <el-empty description="这张表还没有数据策略——所有行、所有列对所有主体都是明文。">
          <div class="pc-policy-empty-actions">
            <el-button type="primary" @click="startEdit(true)">为这张表新建策略</el-button>
          </div>
          <p class="pc-dim pc-policy-note">
            策略是「一张表一条」：行过滤（谁能看到哪些行）+ 列脱敏（哪些列改成什么）+
            豁免名单 + 主体范围。跨表的治理与对比由 REST API 提供，本页只管当前表。
          </p>
        </el-empty>
      </PanelCard>
    </template>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessageBox } from 'element-plus'

import { ApiError, describeError } from '@/api/client.js'
import { policyApi } from '@/api/policy.js'
import TipAlert from '@/components/TipAlert.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import { confirmDanger, notifyError, notifySuccess } from '@/composables/index.js'
// 结构化编辑器的共享逻辑（与新建向导同一份，见 utils/policyAst.js 的模块注释）
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
  parseConditionAst,
} from '@/utils/policyAst.js'

/**
 * 表详情页的「数据策略」页签（设计稿 row-filter-column-mask-design.md §8.3）。
 *
 * <p>页签内只做「当前绑定 + 编辑」，不做跨表总览（mockup 画面 5 的取舍：避免与
 * 治理页重复维护两套 UI）。编辑器是<strong>结构化</strong>的——下拉选列、选算子、
 * 填值——不是自由 SQL 框：一个 SQL 框等于在控制台里开一个求值口子，算子、函数、
 * 注入面全要重守；结构化编辑 + 服务端白名单校验已经够用，写不出的形状
 * （嵌套组合、函数表达式、CASE WHEN）走 REST API 或 Spark SQL（§13）。
 *
 * <p><b>与服务端语义对齐的三件事</b>（细节见 `api/policy.js` 的模块注释）：
 * 保存是整份替换；新建不带 `baseVersion`、更新必须带（409 不自动重试，弹窗提示刷新）；
 * 标准脱敏集的参数形状逐字对齐服务端 `parseStandardMask`——写错键名的表现是
 * 保存时吃一个 400，而服务端的报错会指名写清期望什么。
 *
 * <p><b>加载由父组件触发</b>（首次切到这个页签才请求），与其余页签同一套按需加载。
 */
const props = defineProps({
  /** catalog 名（即 prefix）。 */
  catalog: { type: String, required: true },
  database: { type: String, required: true },
  tableName: { type: String, required: true },
  /** 全量列（name + type），编辑器的列选项来源。 */
  columns: { type: Array, default: () => [] },
})

// ------------------------------------------------------------------ 加载

const policyLoading = ref(false)
const policyError = ref('')
/** GET 的 204 归一化成 `{}`：用 `table` 字段区分「没有策略」与「有策略」。 */
const detail = ref(null)
const loaded = ref(false)

async function load() {
  policyLoading.value = true
  policyError.value = ''
  try {
    const result = await policyApi.get(props.catalog, props.database, props.tableName)
    detail.value = result && result.table ? result : null
    loaded.value = true
  } catch (cause) {
    policyError.value = describeError(cause)
  } finally {
    policyLoading.value = false
  }
}

// ------------------------------------------------------------------ 编辑草稿

const editing = ref(false)
const saving = ref(false)
const deleting = ref(false)
const isNew = ref(false)

const draft = reactive({
  enabled: true,
  combine: 'and',
  conditions: [],
  masks: [],
  subjects: [],
  exemptPrincipals: [],
  hiddenColumns: [],
  /** 服务端已有行过滤超出编辑器表达范围时为 true：原样保留，改动才整体替换。 */
  advancedRowFilter: false,
  rawRowFilterSql: '',
})

const currentVersion = computed(() => detail.value?.version ?? 0)

function startEdit(create) {
  isNew.value = create
  draft.advancedRowFilter = false
  draft.rawRowFilterSql = ''
  if (create || !detail.value) {
    draft.enabled = true
    draft.combine = 'and'
    draft.conditions = []
    draft.masks = []
    draft.subjects = []
    draft.exemptPrincipals = []
    draft.hiddenColumns = []
  } else {
    const source = detail.value
    draft.enabled = !!source.enabled
    draft.subjects = [...(source.subjects || [])]
    draft.exemptPrincipals = [...(source.exemptPrincipals || [])]
    draft.hiddenColumns = [...(source.hiddenColumns || [])]
    // 行过滤：编辑器认得出的形状拍平进表单；认不出的原样保留（见 advancedRowFilter）
    const conditions = source.rowFilter ? parseConditionAst(source.rowFilter) : null
    if (conditions) {
      draft.advancedRowFilter = false
      draft.conditions = conditions.map((cond) => ({ ...cond }))
      draft.combine = conditions.length > 1 && source.rowFilter?.op === 'or' ? 'or' : 'and'
    } else if (source.rowFilter) {
      draft.advancedRowFilter = true
      draft.rawRowFilterSql = source.rowFilterSql || ''
      draft.conditions = []
    } else {
      draft.conditions = []
      draft.combine = 'and'
    }
    // mask：标准集回填进表单；逃生口（{"expr":...}）原样保留
    draft.masks = (source.masks || []).map((entry) => {
      const standard = entry.mask && MASK_KINDS.some((kind) => kind.fn === entry.mask.fn)
      return {
        column: entry.column,
        fn: standard ? entry.mask.fn : '',
        arg: standard ? (entry.mask.args?.[0] ?? defaultArg(entry.mask.fn)) : '',
        allowNullOnRequired: !!entry.allowNullOnRequired,
        raw: standard ? null : entry.mask,
      }
    })
  }
  editing.value = true
}

function cancelEdit() {
  editing.value = false
}

function addCondition() {
  draft.conditions.push(emptyCondition(props.columns[0]?.name || ''))
}

function addMask() {
  const used = new Set(draft.masks.map((mask) => mask.column))
  const column = props.columns.find((field) => !used.has(field.name))
  draft.masks.push(emptyMask(column?.name || props.columns[0]?.name || ''))
}

const duplicateMaskColumns = computed(() => duplicateMaskColumnText(draft.masks))

// ------------------------------------------------------------------ 保存 / 删除

async function save() {
  if (duplicateMaskColumns.value) {
    notifyError(new Error(`同一列只能有一种脱敏方式：${duplicateMaskColumns.value}`))
    return
  }
  const body = {
    enabled: draft.enabled,
    rowFilter: draft.advancedRowFilter && detail.value
      ? detail.value.rowFilter
      : buildRowFilterAst(draft.conditions, draft.combine),
    masks: buildMasksPayload(draft.masks),
    hiddenColumns: draft.hiddenColumns,
    exemptPrincipals: draft.exemptPrincipals,
    subjects: draft.subjects,
  }
  if (!isNew.value && detail.value) {
    body.baseVersion = detail.value.version
  }
  saving.value = true
  try {
    const saved = await policyApi.put(props.catalog, props.database, props.tableName, body)
    notifySuccess(`策略已保存（v${saved?.version ?? '?'}）`)
    editing.value = false
    await load()
  } catch (cause) {
    if (cause instanceof ApiError && cause.status === 409) {
      // 乐观并发失败：不自动重试（与 Spark SQL 侧 §13.3 同一取舍）。后保存的人
      // 应先看最新版本再决定怎么改，静默覆盖只会把别人的修改悄悄抹掉
      ElMessageBox.alert(
        '这份策略刚被其他人修改过。请刷新查看最新版本，再决定如何合并你的修改。',
        '版本冲突',
        { confirmButtonText: '刷新重看', type: 'warning' },
      ).then(() => load()).catch(() => load())
    } else {
      notifyError(cause)
    }
  } finally {
    saving.value = false
  }
}

async function removePolicy() {
  const ok = await confirmDanger({
    title: '删除数据策略',
    target: `${props.catalog}.${props.database}.${props.tableName}`,
    detail: '整条策略（行过滤、列脱敏、豁免名单、主体范围）将被删除，'
      + '所有主体恢复为明文读取。此操作不可撤销。',
    confirmText: '删除策略',
  })
  if (!ok) return
  deleting.value = true
  try {
    await policyApi.delete(props.catalog, props.database, props.tableName)
    notifySuccess('策略已删除')
    editing.value = false
    await load()
  } catch (cause) {
    notifyError(cause)
  } finally {
    deleting.value = false
  }
}

defineExpose({ load })
</script>

<style scoped>
.pc-policy-note {
  margin: 4px 0 10px;
  font-size: 12px;
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

.pc-policy-tags {
  width: 100%;
}

.pc-policy-two-col {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}

.pc-policy-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

.pc-policy-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 4px;
}

.pc-policy-chip {
  margin-right: 6px;
  margin-bottom: 4px;
}

.pc-policy-version {
  margin-left: 8px;
  font-size: 12px;
}

.pc-policy-tag {
  margin-left: 8px;
}

.pc-policy-sql {
  display: inline-block;
  padding: 4px 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  word-break: break-all;
}

.pc-policy-warn {
  color: var(--el-color-danger);
  font-size: 12px;
}

.pc-policy-empty-actions {
  margin-bottom: 8px;
}

.pc-policy-footnote {
  margin-top: 16px;
}

@media (max-width: 900px) {
  .pc-policy-two-col {
    grid-template-columns: 1fr;
  }
}
</style>
