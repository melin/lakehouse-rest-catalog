<template>
  <div class="pc-opt">
    <ResourceState :loading="policy.loading.value" :error="policy.error.value" />

    <!-- 非 Paimon 表与外部表本来进不到这个页签（页签按表格式伸缩），这条分支兜的是
         「表在别处被改动过」的情况：策略接口仍会回答，页面要说清为什么没有开关 -->
    <el-alert v-if="loaded && !supported" type="info" :closable="false" show-icon class="pc-hint">
      <template #title>{{ unsupportedReason || '这张表不支持存储优化' }}</template>
      <div>
        三项动作（Compaction / Snapshot Expire / Orphan File Remove）都建立在 Paimon
        的快照与清单文件之上，另外三种表格式没有这些东西；外部表的位置也不归本服务所有。
      </div>
    </el-alert>

    <template v-else-if="loaded">
      <!-------------------------------- 总开关 --------------------------------->
      <PanelCard>
        <div class="pc-opt-head">
          <el-switch v-model="master.enabled" :loading="masterSaving" @change="saveMaster" />
          <div class="pc-opt-head-text">
            <div class="pc-opt-title">
              存储优化
              <TipNote>
                开启后按下面的策略、按表的调度节拍创建 Spark 批次任务，对本表执行
                Compaction、过期快照清理与孤儿文件清理。同一 catalog 里同时到期的表会被
                合并进<strong>同一个批次</strong>执行，不会一表一个任务。
                <br /><br />
                三项动作各自的「执行间隔」是另一个维度：调度节拍决定「多久看一次」，
                执行间隔决定「多久真做一次」——快照清理每天一次足够，Compaction 值得跟着
                小时级的节拍走。
              </TipNote>
            </div>
            <div class="pc-dim">到期的表会被合并进一个批次，先落库再异步提交到 k8s 执行。</div>
          </div>
          <span class="pc-spacer" />
          <el-button
            type="primary"
            size="small"
            :loading="running"
            :disabled="!enabledActions.length"
            @click="runNow"
          >
            <el-icon><VideoPlay /></el-icon>
            <span class="pc-btn-text">立即优化</span>
          </el-button>
        </div>

        <div class="pc-opt-meta">
          <span class="pc-opt-meta-item">
            调度间隔
            <el-input-number
              v-model="master.scheduleMinutes"
              size="small"
              class="pc-opt-number"
              controls-position="right"
              :min="1"
              :max="1440"
              :step="5"
            />
            <span class="pc-dim">分钟</span>
            <el-button v-if="masterDirty" size="small" type="primary" :loading="masterSaving" @click="saveMaster">
              保存
            </el-button>
          </span>
          <span class="pc-opt-dot">·</span>
          <span class="pc-opt-meta-item">
            最近批次
            <el-tooltip v-if="policy0.lastJobId" :content="policy0.lastJobId" placement="top">
              <RouterLink
                class="pc-link pc-mono"
                :to="{ name: 'optimizationJobs', query: { job: policy0.lastJobId } }"
              >
                {{ shortId(policy0.lastJobId) }}
              </RouterLink>
            </el-tooltip>
            <span v-else class="pc-dim">—</span>
          </span>
          <span class="pc-opt-dot">·</span>
          <span class="pc-opt-meta-item">
            下次调度
            <TimeText v-if="policy0.nextRunAt" :millis="policy0.nextRunAt" mode="relative" />
            <span v-else class="pc-dim">{{ master.enabled ? '未安排' : '未开启' }}</span>
          </span>
          <span class="pc-spacer" />
          <TipNote>
            手动执行<strong>不受总开关约束</strong>：开关管的是「要不要定期自动做」，
            点这个按钮就是在说「现在就做」。但它只跑已启用的动作，
            被关掉的动作不会被悄悄带上。
          </TipNote>
        </div>
      </PanelCard>

      <!-------------------------------- 三项动作 -------------------------------->
      <SectionTitle title="动作与参数">
        <template #tip>
          <TipNote>
            每项动作两档：「默认设置」表示不在这张表上写任何参数，跟着 catalog 默认值与
            内置默认走（运维调过默认值，这张表会自动跟上）；「自定义」在这张表上钉住参数。
            <br /><br />
            保存只提交<strong>改动过</strong>的键，因此自定义一项不会顺手把其它项也钉死。
            切回「默认设置」等于清空这张表的差异。
          </TipNote>
        </template>
      </SectionTitle>

      <PanelCard v-for="spec in ACTIONS" :key="spec.action">
        <div class="pc-opt-head">
          <el-checkbox
            :model-value="cards[spec.action].enabled"
            @update:model-value="(value) => { cards[spec.action].enabled = value }"
          />
          <div class="pc-opt-head-text">
            <div class="pc-opt-title">{{ spec.label }}</div>
            <div class="pc-dim">{{ spec.summary }}</div>
          </div>
          <span class="pc-spacer" />
          <el-radio-group v-model="cards[spec.action].mode" size="small" class="pc-opt-mode">
            <el-radio-button value="default">默认设置</el-radio-button>
            <el-radio-button value="custom">自定义</el-radio-button>
          </el-radio-group>
          <el-button
            size="small"
            type="primary"
            :loading="cards[spec.action].saving"
            :disabled="!isDirty(spec)"
            @click="saveAction(spec)"
          >
            保存
          </el-button>
        </div>

        <!-- 默认档：只读展示生效值。它可能来自内置默认，也可能来自 catalog 默认设置，
             两者在页面上不做区分——用户要回答的是「现在会用什么参数」 -->
        <dl v-if="cards[spec.action].mode === 'default'" class="pc-kv pc-opt-readonly">
          <template v-for="field in visibleFields(spec)" :key="field.key">
            <dt>{{ field.label }}</dt>
            <dd>
              <span>{{ displayValue(spec, field) }}</span>
              <span v-if="field.unit && field.type === 'number'" class="pc-dim pc-opt-unit">{{ field.unit }}</span>
            </dd>
          </template>
        </dl>

        <div v-else class="pc-form pc-form-grid pc-opt-form">
          <el-form-item v-for="field in visibleFields(spec)" :key="field.key">
            <template #label>
              <FieldLabel :label="field.label" :tip="field.tip" />
            </template>
            <div class="pc-opt-control">
              <el-select
                v-if="field.type === 'select'"
                v-model="cards[spec.action].values[field.key]"
                size="small"
                class="pc-full"
              >
                <el-option
                  v-for="choice in field.choices"
                  :key="choice.value"
                  :label="choice.label"
                  :value="choice.value"
                />
              </el-select>
              <!-- 布尔值在服务端存的是字符串 `true` / `false`（策略是一组字符串键值对），
                   因此这里用字符串档位而不是布尔档位，提交时不必再多一层转换。
                   说明文字不放进开关的 active-text：那两句有十几个字，会把开关本身
                   挤成一条——说在开关旁边更清楚。两件事包在一个 template 里，
                   避免它们各自成为一个 v-if 分支而把下面那条链断掉 -->
              <template v-else-if="field.type === 'switch'">
                <el-switch
                  v-model="cards[spec.action].values[field.key]"
                  active-value="true"
                  inactive-value="false"
                />
                <span class="pc-dim pc-opt-unit">
                  {{ cards[spec.action].values[field.key] === 'true' ? field.on : field.off }}
                </span>
              </template>
              <el-input-number
                v-else-if="field.type === 'number'"
                v-model="cards[spec.action].values[field.key]"
                size="small"
                class="pc-opt-number"
                controls-position="right"
                :min="field.min"
                :max="field.max"
              />
              <el-input
                v-else
                v-model="cards[spec.action].values[field.key]"
                size="small"
                :placeholder="field.placeholder"
              />
              <span v-if="field.unit && field.type === 'number'" class="pc-dim pc-opt-unit">{{ field.unit }}</span>
            </div>
          </el-form-item>
        </div>

        <!-- 干跑是三项里唯一「现在不会删东西」的档位，值得单独一句；
             关掉它的那一刻起，扫描到的东西就被真删了 -->
        <el-alert
          v-if="spec.action === 'orphan' && cards.orphan.values['dry-run'] === 'false'"
          type="warning"
          :closable="false"
          show-icon
          class="pc-hint"
        >
          <template #title>干跑预览已关闭：孤儿文件清理会真的删除文件</template>
          <div>
            它删除的依据是「没有被任何快照引用」，因此清理窗口必须大于这张表的最大提交间隔。
            建议先开着干跑跑一轮，核对将要删除的文件之后再关掉。
          </div>
        </el-alert>
      </PanelCard>

      <!-------------------------------- 执行历史 -------------------------------->
      <SectionTitle title="执行历史">
        <template #tip>
          <TipNote>
            这里只显示<strong>这张表</strong>的记录（最多 100 条，本页每项动作显示最近
            {{ HISTORY_LIMIT }} 条）。一次执行同时做了几项动作时，它在几张表里都会出现——
            指标按动作分开记，因此各表只显示自己那一项的读数。
          </TipNote>
        </template>
      </SectionTitle>

      <PanelCard
        v-for="spec in ACTIONS"
        :key="`history-${spec.action}`"
        :title="`${spec.label} 历史`"
        :hint="historyHint(spec)"
        flush
      >
        <div v-if="history.error.value" class="pc-opt-history-error">{{ history.error.value }}</div>
        <template v-else>
          <el-table :data="historyRows(spec)" size="small" empty-text="这张表还没有该动作的执行记录">
            <el-table-column label="开始时间" width="180">
              <template #default="{ row }">
                <TimeText :millis="row.startedAt || row.reportedAt" />
              </template>
            </el-table-column>
            <el-table-column label="批次" width="110">
              <template #default="{ row }">
                <el-tooltip :content="row.jobId" placement="top">
                  <RouterLink
                    class="pc-link pc-mono"
                    :to="{ name: 'optimizationJobs', query: { job: row.jobId } }"
                  >
                    {{ shortId(row.jobId) }}
                  </RouterLink>
                </el-tooltip>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag size="small" effect="plain" :type="itemStatus(row.status).tag">
                  {{ itemStatus(row.status).label }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="指标" min-width="240">
              <template #default="{ row }">
                <span class="pc-mono">{{ metricSummary(row, spec.action) || '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="耗时" width="110" align="right">
              <template #default="{ row }">
                <span class="pc-mono">{{ metricDuration(row, spec.action) }}</span>
              </template>
            </el-table-column>
            <!-- 错误信息固定单行、截断省略，完整内容悬浮可见（OVERFLOW_TIP）：
                 历史表要的是可扫读，一条错误把行撑到四五行就扫不动了 -->
            <el-table-column label="说明" min-width="200" :show-overflow-tooltip="OVERFLOW_TIP">
              <template #default="{ row }">
                <span v-if="row.errorMessage" class="pc-opt-error-text">{{ row.errorMessage }}</span>
                <span v-else class="pc-dim">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="historyOf(spec.action).length > HISTORY_LIMIT" class="pc-opt-more pc-dim">
            共 {{ historyOf(spec.action).length }} 条，仅显示最近 {{ HISTORY_LIMIT }} 条；
            更早的记录从「优化任务」页的对应批次里看。
          </div>
        </template>
      </PanelCard>
    </template>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'

import { optimizationApi } from '@/api/optimization.js'
import FieldLabel from '@/components/FieldLabel.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { confirmDanger, notifyError, notifySuccess, useResource } from '@/composables/index.js'
import { relativeTime } from '@/utils/format.js'
import {
  ACTIONS,
  actionsOf,
  itemStatus,
  metricDuration,
  metricSummary,
  OVERFLOW_TIP,
  shortId,
} from '@/utils/optimization.js'

/**
 * 表详情页的「存储优化」页签。
 *
 * <p>三项动作（Compaction / Snapshot Expire / Orphan File Remove）的参数表单由
 * `@/utils/optimization.js` 的 `ACTIONS` 描述。字段定义放在那里而不是这里，是因为
 * 参数键名必须与服务端的 `OptimizationOptions.KNOWN_KEYS` 逐字一致，而键名写错的表现是
 * 保存时吃一个 400——`scripts/verify-console.py` 会拿那份定义与服务端逐字比对，
 * 因此这里只负责渲染，不再自己列一遍字段。
 *
 * <p><b>「默认设置 / 自定义」不是布尔开关，而是「差异为不为空」。</b>表策略只存用户
 * 显式改过的键（三层合并的最后一层），所以切回默认等于清空差异。保存时也只提交
 * **改动过**的键加原本就配过的键：整份回填会把当前的默认值一并抄成差异，
 * 于是「运维调了 catalog 默认值，没配过的表自动跟上」这条就失效了。
 *
 * <p>加载由父组件触发（首次切到这个页签才请求），与这个页面的其余页签一样按需加载：
 * 打开一张表的成本不该因为多了一个页签而变高。
 */
const props = defineProps({
  /** catalog 名（即 prefix），策略接口按名字定位。 */
  catalog: { type: String, required: true },
  database: { type: String, required: true },
  tableName: { type: String, required: true },
})

/** 每项动作的历史表最多显示多少行。 */
const HISTORY_LIMIT = 20

const policy = useResource(
  () => optimizationApi.policy(props.catalog, props.database, props.tableName),
  { immediate: false },
)
const history = useResource(
  () => optimizationApi.history(props.catalog, props.database, props.tableName),
  { immediate: false },
)

const loaded = computed(() => policy.data.value !== null)
const supported = computed(() => policy.data.value?.supported !== false)
const unsupportedReason = computed(() => policy.data.value?.unsupportedReason || '')
/** 策略对象；未加载时给一份空壳，模板里就不必到处判空。 */
const policy0 = computed(() => policy.data.value || {})

// ------------------------------------------------------------------ 总开关

const master = reactive({ enabled: false, scheduleMinutes: 60 })
/**
 * 调度间隔在界面上以**分钟**为单位：服务端按秒存（下限 60），而用户想的是
 * 「每小时一次」——把秒暴露出来只会让人在心算里打转。
 */
const masterBaseline = ref('')
const masterSaving = ref(false)
const running = ref(false)

const masterDirty = computed(() => JSON.stringify({
  enabled: master.enabled,
  minutes: master.scheduleMinutes,
}) !== masterBaseline.value)

function resetMaster() {
  const data = policy.data.value
  if (!data) return
  master.enabled = data.enabled
  master.scheduleMinutes = Math.max(1, Math.round((data.scheduleIntervalSeconds || 3600) / 60))
  masterBaseline.value = JSON.stringify({ enabled: master.enabled, minutes: master.scheduleMinutes })
}

async function saveMaster() {
  masterSaving.value = true
  try {
    await optimizationApi.updatePolicy(props.catalog, props.database, props.tableName, {
      enabled: master.enabled,
      scheduleIntervalSeconds: Math.round(master.scheduleMinutes * 60),
    })
    notifySuccess('已保存调度设置')
  } catch (error) {
    notifyError(error)
  } finally {
    masterSaving.value = false
    // 成功与失败都重新拉一次：成功是为了拿到服务端算出来的 nextRunAt，
    // 失败是为了把本地那份已经不可信的编辑副本复位成服务端的当前值
    await policy.load()
  }
}

// ------------------------------------------------------------------ 三项动作的编辑副本

/** 动作名 → 编辑副本：当前值、初值（= 生效值）与服务端已存的差异。 */
const cards = reactive({})

/**
 * 数值字段在表单里用数字控件（`el-input-number` 拿到字符串会有奇怪的行为），
 * 因此初始化时转成数字；策略在服务端本来就是「字符串键值对」，提交时再转回去。
 * 转换只发生在这两处，别处一律按表单里的类型使用。
 */
function normalizeValue(field, raw) {
  if (field.type === 'number') {
    const parsed = Number(raw)
    return Number.isFinite(parsed) ? parsed : undefined
  }
  if (raw === undefined || raw === null) return ''
  return String(raw)
}

function resetCards() {
  // 允许 data 还没到：`immediate` 的那一次调用就发生在加载之前，先用空壳把三张卡
  // 建出来，模板里就不必到处判 `cards[action]` 存不存在
  const data = policy.data.value
  for (const spec of ACTIONS) {
    const action = (data?.actions || []).find((item) => item.action === spec.action) || {}
    const effective = action.effective || {}
    const values = {}
    for (const field of spec.fields) {
      values[field.key] = normalizeValue(field, effective[field.key])
    }
    cards[spec.action] = {
      enabled: action.enabled === true,
      // 差异为空就是「默认设置」：这不是一个可以单独记的开关，而是 config 的另一种说法
      mode: action.usingDefaults === false ? 'custom' : 'default',
      values,
      initial: { ...values },
      diff: { ...(action.config || {}) },
      saving: false,
    }
  }
}

function actionPolicy(action) {
  return (policy.data.value?.actions || []).find((item) => item.action === action) || {}
}

function isVisible(field, values) {
  return field.showWhen ? field.showWhen(values) : true
}

function visibleFields(spec) {
  const values = cards[spec.action]?.values || {}
  return spec.fields.filter((field) => isVisible(field, values))
}

/** 默认档下的只读展示：布尔与枚举说人话，其余原样（单位挂在后面）。 */
function displayValue(spec, field) {
  const value = cards[spec.action]?.values?.[field.key]
  if (value === undefined || value === '') return '—'
  if (field.type === 'switch') {
    return value === 'true' ? `是（${field.on}）` : `否（${field.off}）`
  }
  if (field.type === 'select') {
    return (field.choices.find((choice) => choice.value === value) || {}).label || value
  }
  return String(value)
}

/**
 * 这次要提交的差异。
 *
 * <p>三条规则：改动过的键照原样提交；服务端本来就配过的键保留（那是有意钉住的值，
 * 用户没碰它不代表要放开）；既没改也没配过的键<strong>不提交</strong>——提交它们等于把
 * 当前的默认值抄成这张表的差异，运维之后调默认值就再也影响不到它。
 * 清空一个输入框表示「这一项不要钉住」，该键因此从差异里去掉（回落默认）。
 */
function configToSubmit(spec, card) {
  const config = {}
  for (const field of spec.fields) {
    const raw = card.values[field.key]
    const value = raw === undefined || raw === null ? '' : String(raw).trim()
    if (!value) continue
    const initial = card.initial[field.key]
    const initialText = initial === undefined || initial === null ? '' : String(initial).trim()
    if (value !== initialText || Object.prototype.hasOwnProperty.call(card.diff, field.key)) {
      config[field.key] = value
    }
  }
  return config
}

/** 键值比较不看顺序：服务端返回的差异是 JSON 对象，键序与表单的字段序无关。 */
function sameConfig(left, right) {
  const names = (value) => Object.keys(value || {}).sort()
  const keys = names(left)
  const other = names(right)
  if (keys.length !== other.length || keys.some((key, index) => key !== other[index])) return false
  return keys.every((key) => String(left[key]) === String(right[key]))
}

function isDirty(spec) {
  const card = cards[spec.action]
  const action = actionPolicy(spec.action)
  if (card.enabled !== (action.enabled === true)) return true
  if (card.mode !== (action.usingDefaults === false ? 'custom' : 'default')) return true
  if (card.mode !== 'custom') return false
  return !sameConfig(configToSubmit(spec, card), card.diff)
}

async function saveAction(spec) {
  const card = cards[spec.action]
  const action = actionPolicy(spec.action)
  const update = { action: spec.action, enabled: card.enabled }
  if (card.mode === 'custom') {
    update.config = configToSubmit(spec, card)
  } else if (action.usingDefaults === false) {
    // 切回「默认设置」= 清空这张表的差异（而不是保留原样）
    update.config = {}
  }
  card.saving = true
  try {
    await optimizationApi.updatePolicy(props.catalog, props.database, props.tableName, { actions: [update] })
    notifySuccess(`${spec.label} 已保存`)
  } catch (error) {
    notifyError(error)
  } finally {
    card.saving = false
    await policy.load()
  }
}

/** 已启用的动作：决定「立即优化」会跑什么，以及按钮能不能点。 */
const enabledActions = computed(() => {
  const data = policy.data.value
  if (!data) return []
  return (data.actions || []).filter((item) => item.enabled).map((item) => item.action)
})

async function runNow() {
  const labels = enabledActions.value.map((action) => {
    const spec = ACTIONS.find((item) => item.action === action)
    return spec ? spec.label : action
  })
  const ok = await confirmDanger({
    title: '立即优化',
    target: `对 ${props.tableName} 立即执行 ${labels.join('、')}？`,
    detail: '会创建一个优化批次，由服务端在下一个提交节拍（默认 10 秒）投到 k8s 执行。'
      + '批次与本表的历史都会记在「优化任务」页里；不需要了可以取消。',
    confirmText: '立即优化',
  })
  if (!ok) return
  running.value = true
  try {
    const job = await optimizationApi.run(props.catalog, props.database, props.tableName, null)
    notifySuccess(`已创建批次 ${shortId(job?.id)}，等下一个提交节拍投到集群`)
  } catch (error) {
    notifyError(error)
  } finally {
    running.value = false
    await policy.load()
    await history.load()
  }
}

// ------------------------------------------------------------------ 执行历史

const historyItems = computed(() => history.data.value?.items || [])

/** 一次执行同时做了几项动作时，它在几张表里都会出现——指标按动作分开记。 */
function historyOf(action) {
  return historyItems.value
    .filter((item) => actionsOf(item.actions).includes(action))
    .sort((left, right) => (right.startedAt || right.reportedAt || 0)
      - (left.startedAt || left.reportedAt || 0))
}

function historyRows(spec) {
  return historyOf(spec.action).slice(0, HISTORY_LIMIT)
}

function historyHint(spec) {
  if (history.loading.value) return '加载中'
  const rows = historyOf(spec.action)
  if (!rows.length) return ''
  const last = rows[0]
  return `最近一次 ${relativeTime(last.startedAt || last.reportedAt)} · ${itemStatus(last.status).label}`
}

// ------------------------------------------------------------------ 加载

/** 由父组件在首次切到这个页签（以及点刷新）时调用。 */
function load() {
  policy.load()
  history.load()
}

defineExpose({ load })

// 策略重新加载后重建编辑副本：保存成功也会触发一次加载，于是表单自动回到
// 「与服务端一致」的干净状态。编辑期间被顶掉同理——保留过期的副本比覆盖它更危险。
watch(() => policy.data.value, () => {
  resetMaster()
  resetCards()
}, { immediate: true })
</script>

<style scoped>
.pc-opt-head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

/* 右侧的动作组（档位切换、保存、立即优化）与左侧「标题 + 描述」两行文本整体
   垂直居中：顶部对齐会让按钮悬在第一行标题上，与下面紧跟着的参数行也脱节。
   勾选框 / 总开关不跟着居中——它们属于标题行（align-items: flex-start 已经
   把它们钉在标题那一行），跟着居中反而会悬在标题与描述之间。 */
.pc-opt-head > .pc-opt-mode,
.pc-opt-head > .el-button {
  align-self: center;
}

.pc-opt-head-text {
  min-width: 0;
}

.pc-opt-title {
  font-size: 14.5px;
  font-weight: 600;
  display: flex;
  align-items: center;
  gap: 4px;
}

/* 元信息行：调度间隔 / 最近批次 / 下次调度，用细点分隔 */
.pc-opt-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--pc-border);
  font-size: 13px;
}

.pc-opt-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.pc-opt-dot {
  color: var(--pc-text-dim);
}

/* 数字输入：默认宽度在栅格里会把一行撑爆，钉一个放得下四位数加步进器的宽度 */
.pc-opt-number {
  width: 118px;
}

/* 控件行要占满字段格：el-select 没有固有宽度（选中项是绝对定位的），
   这行若是 auto 基准，select 的 width:100% 就反过来依赖行宽，塌成只剩一个箭头。
   el-input 有 min-content 兜底看不出来，select 是唯一会露馅的。 */
.pc-opt-control {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: 1;
  min-width: 0;
}

.pc-opt-unit {
  font-size: 12.5px;
  white-space: nowrap;
}

.pc-opt-mode :deep(.el-radio-button__inner) {
  padding: 6px 12px;
}

/* 默认档的只读参数：排两列，省得一张卡拉出一屏高 */
.pc-opt-readonly {
  grid-template-columns: minmax(150px, max-content) 1fr minmax(150px, max-content) 1fr;
  margin-top: 12px;
  padding: 12px 14px;
  background: var(--pc-surface-2);
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  font-size: 13px;
}

.pc-opt-form {
  margin-top: 12px;
}

/* 参数行是内联标签（标签在控件左侧，字段组横向排开），而全局 `.pc-form` 的
 * 标签规则是按「标签在控件上方」写的：Element Plus 给标签钉死了 32px 的盒子
 * （height: 32px），全局又加了 line-height: 1.6 与 4px 的底部间距——文字被顶在
 * 盒子上沿，而控件在行里垂直居中，于是每个字段组的标签都比控件高出几像素，
 * 一行看过去高低不齐。这里把标签盒子收回随文字自适应，并与控件垂直居中。
 * 特异性写成三类连用，不依赖全局样式与组件样式的加载顺序（同 PermissionsView）。 */
.pc-form.pc-form-grid.pc-opt-form :deep(.el-form-item) {
  align-items: center;
}

.pc-form.pc-form-grid.pc-opt-form :deep(.el-form-item__label) {
  height: auto;
  padding: 0;
  line-height: 1.5;
}

.pc-opt-error-text {
  color: var(--el-color-danger);
  overflow-wrap: anywhere;
}

.pc-opt-history-error {
  padding: 12px 16px;
  color: var(--el-color-danger);
  font-size: 13px;
}

.pc-opt-more {
  padding: 10px 16px 2px;
  font-size: 12.5px;
}

@media (max-width: 1100px) {
  .pc-opt-readonly {
    grid-template-columns: minmax(130px, max-content) 1fr;
  }
}
</style>
