<template>
  <div class="pc-page">
    <PageHeader
      title="扫描调度"
      subtitle="catalog 自建调度：每条一行 cron，服务端到点发起扫描。多副本只触发一次，不依赖外部调度器。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建调度项</span>
        </el-button>
      </template>
    </PageHeader>

    <!--
      这一屏的三条口径都必须写出来，因为三条的**错法都不报错**：
      ① 「只触发一次」若只靠租约，一次慢扫描撞上租约到期就会双触发；
      ② 若把「停用」做成删除，历史扫描里那些 schedule_name 还在、规则却查不到了；
      ③ 若前端自己解析 cron 算「下次运行」，它展示的时刻与服务端真正触发的时刻
         会在某一次改 cron 之后悄悄分叉（两边都不报错）。
    -->
    <TipAlert type="info" class="pc-gap">
      <template #title>三条口径</template>
      <p>
        <strong>一次调度只触发一次。</strong>
        两道闸门一起生效：在租约通道 <code class="pc-mono">sensitivity.scan</code> 上，
        只有一台实例读调度表，它只降低重复概率；正确性落在
        <code class="pc-mono">next_run_at</code> 的条件更新上——抢占成功的那一台把该值
        推进到下一个 cron 点，且下一次从<strong>「现在」</strong>算（从旧值算会在进程重启后
        变成追赶式连发）。
      </p>
      <p>
        <strong>停用不等于删除。</strong>
        停用是「规则还在、只是暂时不跑」，历史扫描照旧按
        <code class="pc-mono">schedule_name</code> 可查；删除是「不再需要这条规则」。
        另外<strong>改名不被支持</strong>（服务端 400）：扫描表存的是名字字符串而不是外键，
        改名会让「这条扫描是哪条调度项触发的」失去指向。要换名字，新建一条、停用旧的。
      </p>
      <p>
        <strong>下次运行时刻由服务端算。</strong>
        新建、改 cron、重新启用三处都在服务端写事务里重算并回传，控制台不解析 cron——
        「同一个表达式在两处各算一遍」迟早给出不同答案，而那一刻没有任何报错。
      </p>
    </TipAlert>

    <!-- ============================ 1. 调度项清单 ============================ -->
    <PanelCard flush title="调度项" hint="停用的也列出来（它们仍持有历史）">
      <template #actions>
        <el-select
          v-model="catalog"
          size="small"
          placeholder="catalog"
          class="pc-sched-catalog"
          @change="onCatalogChange"
        >
          <el-option label="全部 catalog" value="" />
          <el-option v-for="name in catalogNames" :key="name" :label="name" :value="name" />
        </el-select>
      </template>

      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && !rows.length"
        empty-text="还没有调度项。点右上角「新建调度项」给这个 catalog 排一条 cron。"
      >
        <el-table v-loading="loading" :data="rows" size="small">
          <el-table-column label="调度项" min-width="180">
            <template #default="{ row }">
              <div class="pc-sched-name">{{ row.name }}</div>
              <div class="pc-mono pc-dim pc-sched-id">{{ row.catalog }}</div>
            </template>
          </el-table-column>

          <el-table-column label="cron" width="150">
            <template #default="{ row }">
              <code class="pc-mono">{{ row.cron }}</code>
            </template>
          </el-table-column>

          <el-table-column label="范围" min-width="210">
            <template #default="{ row }">
              <el-tag size="small" type="info" class="pc-sched-scope-tag">{{ row.scope }}</el-tag>
              <span class="pc-mono">{{ scopeText(row) }}</span>
            </template>
          </el-table-column>

          <el-table-column label="模式" width="140">
            <template #default="{ row }">
              <span class="pc-dim">{{ row.mode }}</span>
              <div v-if="row.mode === 'SAMPLED'" class="pc-dim pc-sched-sub">采样 {{ row.sampleSize }}</div>
            </template>
          </el-table-column>

          <el-table-column label="执行" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.execution === 'SPARK' ? 'warning' : 'info'">
                {{ executionLabel(row.execution) }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="启用" width="80">
            <template #default="{ row }">
              <el-switch
                :model-value="row.enabled"
                size="small"
                :loading="toggling === row.id"
                @change="(value) => toggleEnabled(row, value)"
              />
            </template>
          </el-table-column>

          <el-table-column label="上次运行" width="140">
            <template #default="{ row }">
              <TimeText v-if="row.lastRunAt" :millis="row.lastRunAt" mode="relative" />
              <span v-else class="pc-dim">没跑过</span>
            </template>
          </el-table-column>

          <el-table-column label="下次运行" width="170">
            <template #default="{ row }">
              <TimeText v-if="row.enabled && row.nextRunAt" :millis="row.nextRunAt" />
              <span v-else class="pc-dim">已停用</span>
            </template>
          </el-table-column>

          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button size="small" text @click="showHistory(row)">历史</el-button>
              <el-button size="small" text type="danger" @click="confirmDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>

      <div class="pc-sched-foot">
        <span class="pc-dim">
          「全部 catalog」需要服务管理员（不带 catalog 的列表是一条跨 catalog 的路）；
          普通治理员请先在上方选一个 catalog。
        </span>
      </div>
    </PanelCard>

    <!-- ============================ 2. 这条调度项的历史 ============================ -->
    <!--
      验收断言是「按 cron 触发一次且只触发一次」，而它只能在扫描历史里被看见。
      因此这一块不是装饰：`triggerType=SCHEDULE` 单独筛出来的是**所有**调度项的历史，
      点某一行「历史」才会带上 `scheduleName`——否则「我配的这条跑了吗」仍然答不出来。
    -->
    <PanelCard flush class="pc-sched-list" title="由调度触发的扫描" hint="triggerType=SCHEDULE，最多 10 条">
      <template #actions>
        <el-tag v-if="historySchedule" size="small" closable class="pc-sched-chip" @close="clearHistory">
          只看 {{ historySchedule }}
        </el-tag>
      </template>

      <ResourceState
        :loading="historyLoading"
        :error="historyError"
        :empty="!historyLoading && !historyError && !historyRows.length"
        empty-text="还没有调度触发的扫描。等到下一次 cron 点，或者把这条调度项的 cron 临时改成近几分钟。"
      >
        <el-table v-loading="historyLoading" :data="historyRows" size="small">
          <el-table-column label="扫描" width="140">
            <template #default="{ row }">
              <code class="pc-mono">{{ shortId(row.id) }}</code>
            </template>
          </el-table-column>
          <el-table-column label="调度项" width="170">
            <template #default="{ row }">
              <span v-if="row.scheduleName" class="pc-mono">{{ row.scheduleName }}</span>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
          <el-table-column label="范围" min-width="200">
            <template #default="{ row }">
              <span class="pc-mono">{{ scopeText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="模式" width="130">
            <template #default="{ row }">
              <span class="pc-dim">{{ row.mode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="扫到的表 / 列" width="140">
            <template #default="{ row }">
              {{ row.tablesScanned }} / {{ row.columnsScanned }}
              <template v-if="row.tablesFailed">
                <span class="pc-sched-failed">· {{ row.tablesFailed }} 张失败</span>
              </template>
            </template>
          </el-table-column>
          <el-table-column label="命中" width="80">
            <template #default="{ row }">{{ row.findingsCreated }}</template>
          </el-table-column>
          <el-table-column label="发起人" width="170">
            <template #default="{ row }">
              <span class="pc-mono pc-dim">{{ row.triggeredBy }}</span>
            </template>
          </el-table-column>
          <el-table-column label="结束" width="180">
            <template #default="{ row }">
              <TimeText :millis="row.finishedAt || row.startedAt || row.createdAt" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>

      <div class="pc-sched-foot">
        <span class="pc-dim">
          只取最近 10 条、不分页：这一页看的是「调度到底有没有在发」，不是完整历史
          （完整历史在「敏感数据」总览的最近扫描里）。调度触发的主体是
          <code class="pc-mono">system:sensitivity-scheduler</code>，不是某个人。
        </span>
      </div>
    </PanelCard>

    <!-- ============================ 3. 两道闸门与一条只能选进程内的路 ============================ -->
    <PanelCard
      class="pc-sched-card"
      title="cron 反复打全 catalog 会撞到什么"
      hint="四道闸门，其中一道只挡表级"
    >
      <el-table :data="GATES" size="small" class="pc-sched-table">
        <el-table-column label="约束" width="200" prop="gate" />
        <el-table-column label="口径" prop="detail" />
      </el-table>

      <!--
        这条必须单列出来：它是一道**只挡表级**的闸门。
        按 catalog 每小时跑一次的人会以为自己在被保护，实际那道闸门连看都不会看他一眼。
      -->
      <TipAlert type="warning" class="pc-sched-subtip">
        <template #title>同表闸门只对 scope=TABLE 生效</template>
        409 的判据是「这个 catalog 下这张表有没有在跑的扫描」，因此
        <strong>catalog 级与库级的重扫不会被挡</strong>——两次全量扫描会并行跑、
        各自产出一份命中。要「同一张表不重复扫」，得自己在调度器里按表拆调度项。
      </TipAlert>

      <TipAlert type="info" class="pc-sched-subtip">
        <template #title>执行位置决定「谁来扫」</template>
        <code class="pc-mono">IN_PROCESS</code> 在服务端进程内读样本，守的是
        <code class="pc-mono">maxConcurrentScans</code>；<code class="pc-mono">SPARK</code>
        把「一个扫描 = 一个 Job」提交到 catalog 的执行集群，与优化批次共用同一把
        <code class="pc-mono">maxConcurrentJobs</code>。两条路的判定逻辑是同一份字节码，
        差别只在哪些机器读数据。
        服务端对「建 SPARK 调度项」有两条 fail-closed 校验（元数据档 400、catalog 不可派发 400）；
        通过之后，触发那一刻建出来的扫描是 <code class="pc-mono">PENDING</code>，
        由调度节拍提交成 Job，作业逐表回调结果。
      </TipAlert>
    </PanelCard>

    <!--
      对话框必须有 `destroy-on-close`：它是常驻 DOM 的，第二次打开（编辑另一条）时
      表单会停在上一次的值上，保存就会把 A 调度项改写成 B——与「类型」那一屏同一个坑。
    -->
    <el-dialog
      v-model="dialog.open"
      :title="dialog.editing ? `编辑调度项：${dialog.name}` : '新建调度项'"
      width="680px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <div class="pc-sched-row">
          <el-form-item class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="名称"
                tip="唯一。建成之后不能改名——历史扫描按这个名字回查触发来源。换名字请新建一条、停用旧的"
              />
            </template>
            <el-input
              v-model="dialog.name"
              :disabled="dialog.editing"
              placeholder="如 nightly_pii_scan"
              class="pc-mono"
            />
            <div v-if="dialog.editing" class="pc-hint">
              改名会让历史失去指向，因此这里只读。
            </div>
          </el-form-item>

          <el-form-item class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="cron"
                tip="5 段（分 时 日 月 周）或 6 段（秒 分 时 日 月 周）。不支持 Quartz 的 ? # L W，也不接受永不匹配的表达式（如 2 月 30 日）"
              />
            </template>
            <el-input v-model="dialog.cron" placeholder="0 3 * * *" class="pc-mono" />
            <div class="pc-hint">
              原样保存你写的这一份（5 段不会被改写成 6 段）。时区取服务端所在时区。
            </div>
          </el-form-item>
        </div>

        <div class="pc-sched-row">
          <el-form-item class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="catalog"
                tip="扫描只读这个 catalog 下的表。写操作的判权按它来（CATALOG:ALTER）"
              />
            </template>
            <el-select v-model="dialog.catalog" filterable placeholder="选择 catalog">
              <el-option v-for="name in catalogNames" :key="name" :label="name" :value="name" />
            </el-select>
          </el-form-item>

          <el-form-item class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="范围"
                tip="层级不靠「哪几个字段有值」反推：TABLE 必须给库名 + 表名，DATABASE 必须给库名，服务端缺一个就 400"
              />
            </template>
            <el-select v-model="dialog.scope">
              <el-option label="整个 catalog" value="CATALOG" />
              <el-option label="单个库" value="DATABASE" />
              <el-option label="单张表" value="TABLE" />
            </el-select>
          </el-form-item>
        </div>

        <div v-if="dialog.scope !== 'CATALOG'" class="pc-sched-row">
          <el-form-item class="pc-sched-col">
            <template #label><FieldLabel label="库名" tip="scope=DATABASE / TABLE 必填" /></template>
            <el-input v-model="dialog.database" placeholder="database" class="pc-mono" />
          </el-form-item>

          <el-form-item v-if="dialog.scope === 'TABLE'" class="pc-sched-col">
            <template #label><FieldLabel label="表名" tip="scope=TABLE 必填" /></template>
            <el-input v-model="dialog.table" placeholder="table" class="pc-mono" />
          </el-form-item>
        </div>

        <div class="pc-sched-row">
          <el-form-item class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="模式"
                tip="元数据档只读列名 / 注释 / 类型（D1 + D2），不碰数据；采样档再加值层，会读前 N 行明文"
              />
            </template>
            <el-select v-model="dialog.mode">
              <el-option label="仅元数据（D1 + D2）" value="METADATA_ONLY" />
              <el-option label="采样（再加 D3 值层）" value="SAMPLED" />
            </el-select>
          </el-form-item>

          <el-form-item class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="执行位置"
                tip="留空 = 跟随生效值（catalog 的 defaultExecution > 全局），服务端把生效值落库。选 SPARK 要求这个 catalog 的执行集群可派发，且模式不能是 METADATA_ONLY"
              />
            </template>
            <el-select v-model="dialog.execution">
              <el-option label="跟随生效值" value="" />
              <el-option label="进程内 IN_PROCESS" value="IN_PROCESS" />
              <el-option label="下推 SPARK（一个扫描一个 Job）" value="SPARK" />
            </el-select>
          </el-form-item>
        </div>

        <div class="pc-sched-row">
          <el-form-item v-if="dialog.mode === 'SAMPLED'" class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="采样量"
                tip="每个候选列读前 N 行。上限由服务端配置（lakehouse.rest.sensitivity.scan.max-sample-size），超了 400 而不静默截断"
              />
            </template>
            <el-input-number
              v-model="dialog.sampleSize"
              :min="1"
              :step="100"
              controls-position="right"
              class="pc-sched-num"
            />
          </el-form-item>

          <el-form-item class="pc-sched-col">
            <template #label>
              <FieldLabel
                label="启用"
                tip="停用只是不跑，规则与历史都在；要「不再需要这条规则」用删除"
              />
            </template>
            <el-switch v-model="dialog.enabled" />
          </el-form-item>
        </div>
      </el-form>

      <TipAlert type="warning" class="pc-sched-dialog-tip">
        <template #title>保存时服务端会做三件事</template>
        <p>
          按 cron 重算 <code class="pc-mono">next_run_at</code>（停用再启用也会重排，
          否则「一启用就立刻跑一次」往往发生在深夜）；校验范围递进（更宽的层级会把库名 /
          表名置空）；校验 <code class="pc-mono">SPARK</code> 的两条 fail-closed。
        </p>
        <p>
          两个治理员同时改同一条时，<code class="pc-mono">entityVersion</code> 不匹配会回
          <strong>409</strong>，控制台<strong>不自动重试</strong>——调度项的改动要么按你的，
          要么按他的，得由人裁决。
        </p>
      </TipAlert>

      <template #footer>
        <el-button size="small" @click="dialog.open = false">取消</el-button>
        <el-button size="small" type="primary" :loading="dialog.saving" @click="save">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'

import FieldLabel from '@/components/FieldLabel.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import TipAlert from '@/components/TipAlert.vue'
import { describeError } from '@/api/client.js'
import { sensitivityApi } from '@/api/sensitivity.js'
import { confirmDanger, notifyError, notifySuccess, onConsoleRefresh } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * 扫描调度（mockup `#schedules`，设计稿 §5.6.2 / §5.6.3 / §12 P4）。
 *
 * <p><b>它是 CRUD，不是「生成一行 curl」。</b>调度已拍板 catalog 自建：第 6 张表
 * `lakehouse_sensitivity_schedule` + `/sensitivity-schedules` 的五条端点都已经落地，
 * 于是「多久扫一次」不再交给外部调度器，而是这一屏的一行 cron。过渡态那一版
 * （粘贴 curl 进 Airflow / k8s CronJob）随之作废——留着会让同一件事有两个入口，
 * 而两个入口的语义并不相同（外部 CronJob 每台都会发一次，服务端这里靠租约 + CAS）。
 *
 * <h3>三处「不能在前端做」的事</h3>
 * - **不解析 cron、不自己算下次运行时刻**：`next_run_at` 由服务端在写事务里算好回传。
 *   两边各算一遍，改 cron 之后就会给出不同答案，而那一刻没有任何报错。
 * - **不提供改名**：`lakehouse_sensitivity_scan.schedule_name` 存的是名字字符串
 *   （不建外键，§4.6），改名会让历史扫描失去指向。编辑器里名称只读，
 *   文案要写清「换名字的正确做法」。
 * - **不把「停用」实现成删除**：停用是 `PUT enabled=false`（规则还在），删除是真删。
 *   两者在清单上必须长得不一样，否则「我删了它怎么还在」就成了一个正确行为被当成 bug。
 *
 * <h3>PUT 是整份替换，这是这一屏最容易踩的一脚</h3>
 * 启停走的是 `PUT`，而它**不是**逐字段语义：`scope` / `mode` / `sampleSize` 缺省时
 * 分别落回 `CATALOG` / `METADATA_ONLY` / 全局默认。因此「只发 `{enabled:false}`」
 * 会把一条表级采样调度项悄悄改成 catalog 级元数据档，还回 200。
 * 所以 `toggleEnabled` 必须先按当前行拼出完整请求体（`bodyOf`），只覆盖 `enabled`。
 *
 * <h3>为什么还要一块「由调度触发的扫描」</h3>
 * §12 对 P4 的验收是「按 cron 触发一次、且只触发一次」，它只能在扫描历史里被看见。
 * `triggerType=SCHEDULE` 筛出来的是**所有**调度项的历史，所以每一行还带一个「历史」
 * 动作，用 `scheduleName` 再收窄一次——这正是读模型里补 `scheduleName` 的原因。
 */
const catalogNames = computed(() => (session.state.catalogs || []).map((item) => item.name))

/** 列表过滤用的 catalog（空 = 跨 catalog，需要服务管理员）。 */
const catalog = ref(session.state.selectedCatalog || '')

// ------------------------------------------------------------------ 清单

const loading = ref(false)
const error = ref('')
const rows = ref([])
const toggling = ref(null)

async function load() {
  loading.value = true
  error.value = ''
  try {
    const payload = await sensitivityApi.listSchedules({ catalog: catalog.value })
    rows.value = payload?.items || []
  } catch (cause) {
    error.value = describeError(cause)
    rows.value = []
  } finally {
    loading.value = false
  }
}

function onCatalogChange(value) {
  // 只在选中具体 catalog 时写回全局上下文：catalog 是控制台的核心上下文，但
  // 「全部 catalog」只是这一屏的过滤视图——把它写进全局会让别的页面失去上下文
  // （它们大多按选中项取数，选空之后要么 400 要么 403）。
  if (value) {
    session.selectCatalog(value)
  }
  // 换 catalog 时清掉「只看某条调度项」：那个名字属于上一个 catalog，
  // 留着会让历史列表永远为空，而看起来像「这条调度项从没跑过」。
  historySchedule.value = ''
  load()
  loadHistory()
}

// ------------------------------------------------------------------ 启停

/**
 * 把一条读模型拼成 `PUT` 的整份请求体。
 *
 * <p>**不要在这上面偷懒**：`PUT` 缺 `scope` / `mode` 时服务端会分别按 `CATALOG` /
 * `METADATA_ONLY` 处理（那两处默认值是为 `POST` 准备的），于是「只发 enabled」
 * 会改写这条规则的范围与模式，而且返回 200。请求体一律从当前行整份拼出来。
 */
function bodyOf(row, overrides = {}) {
  const body = {
    name: row.name,
    cron: row.cron,
    catalog: row.catalog,
    scope: row.scope,
    database: row.database || '',
    table: row.table || '',
    mode: row.mode,
    execution: row.execution || '',
    sampleSize: row.sampleSize,
    enabled: row.enabled,
    entityVersion: row.entityVersion,
    ...overrides,
  }
  return body
}

async function toggleEnabled(row, value) {
  toggling.value = row.id
  try {
    // 传整份而不是 `{enabled}`：见 bodyOf 的注释（缺省值会静默改写范围与模式）。
    // 每次 PUT 都会重算 next_run_at，因此「重新启用」不会立刻触发一次旧时刻。
    await sensitivityApi.updateSchedule(row.id, bodyOf(row, { enabled: value }))
    notifySuccess(value ? `已启用 ${row.name}` : `已停用 ${row.name}（历史仍在）`)
    await load()
  } catch (cause) {
    notifyError(cause)
    // 失败时重新拉一次：`el-switch` 用的是 `:model-value`（单向绑定），
    // 界面上的位置由 `row.enabled` 决定，重新拉列表就是最准确的对账。
    await load()
  } finally {
    toggling.value = null
  }
}

// ------------------------------------------------------------------ 新建 / 编辑

const dialog = reactive({
  open: false,
  editing: false,
  saving: false,
  id: null,
  name: '',
  cron: '0 3 * * *',
  catalog: '',
  scope: 'CATALOG',
  database: '',
  table: '',
  mode: 'METADATA_ONLY',
  execution: '',
  sampleSize: 2000,
  enabled: true,
  entityVersion: 0,
})

function openCreate() {
  dialog.editing = false
  dialog.id = null
  dialog.name = ''
  dialog.cron = '0 3 * * *'
  dialog.catalog = catalog.value || session.state.selectedCatalog || ''
  dialog.scope = 'CATALOG'
  dialog.database = ''
  dialog.table = ''
  dialog.mode = 'METADATA_ONLY'
  dialog.execution = ''
  dialog.sampleSize = 2000
  dialog.enabled = true
  dialog.entityVersion = 0
  dialog.open = true
}

/**
 * 打开编辑。取一次详情而不是直接用列表那一行：列表是几百毫秒前的快照，
 * 而编辑器要的是「这一份配置的当前值」——顺带也让 `getSensitivitySchedule` 真的被调用，
 * 而不是一条没人用的端点声明。
 */
async function openEdit(row) {
  try {
    const detail = await sensitivityApi.getSchedule(row.id)
    applyToDialog(detail, true)
    dialog.open = true
  } catch (cause) {
    notifyError(cause)
  }
}

function applyToDialog(detail, editing) {
  dialog.editing = editing
  dialog.id = detail.id
  dialog.name = detail.name || ''
  dialog.cron = detail.cron || ''
  dialog.catalog = detail.catalog || ''
  dialog.scope = detail.scope || 'CATALOG'
  dialog.database = detail.database || ''
  dialog.table = detail.table || ''
  dialog.mode = detail.mode || 'METADATA_ONLY'
  // 执行位置原样带出来，不在这里归一化成 IN_PROCESS：编辑一条调度项时顺手改掉它的
  // 执行位置，是一次用户没要求的改动。空串仍然是「跟随生效值」，与 null 不同义。
  dialog.execution = detail.execution || ''
  dialog.sampleSize = detail.sampleSize || 2000
  dialog.enabled = detail.enabled !== false
  dialog.entityVersion = detail.entityVersion || 0
}

/** 换层级时清掉不该留的字段：界面与「保存后服务端会置空」的表现要一致。 */
watch(() => dialog.scope, (scope) => {
  if (scope === 'CATALOG') {
    dialog.database = ''
    dialog.table = ''
  } else if (scope === 'DATABASE') {
    dialog.table = ''
  }
})

/**
 * 保存。
 *
 * <p>别名 / cron / 范围三项先在本地挡一道，但**判定以服务端为准**：
 * 这里的校验只为让「少填一个字段」不变成一次往返。
 */
async function save() {
  const name = dialog.name.trim()
  if (!dialog.editing && !name) {
    notifyError(new Error('名称不能为空'))
    return
  }
  if (!dialog.cron.trim()) {
    notifyError(new Error('cron 不能为空'))
    return
  }
  if (!dialog.catalog) {
    notifyError(new Error('请选择 catalog'))
    return
  }
  if (dialog.scope !== 'CATALOG' && !dialog.database.trim()) {
    notifyError(new Error(dialog.scope === 'TABLE' ? '表级范围要填库名与表名' : '库级范围要填库名'))
    return
  }
  if (dialog.scope === 'TABLE' && !dialog.table.trim()) {
    notifyError(new Error('表级范围要填库名与表名'))
    return
  }

  const body = {
    name: dialog.editing ? dialog.name : name,
    cron: dialog.cron.trim(),
    catalog: dialog.catalog,
    scope: dialog.scope,
    database: dialog.database.trim(),
    table: dialog.table.trim(),
    mode: dialog.mode,
    execution: dialog.execution,
    sampleSize: dialog.mode === 'SAMPLED' ? dialog.sampleSize : undefined,
    enabled: dialog.enabled,
  }

  dialog.saving = true
  try {
    if (dialog.editing) {
      body.entityVersion = dialog.entityVersion
      await sensitivityApi.updateSchedule(dialog.id, body)
      notifySuccess(`${dialog.name} 已保存，下次运行时刻已按新 cron 重排`)
    } else {
      await sensitivityApi.createSchedule(body)
      notifySuccess(`${name} 已创建，下次运行时刻由服务端算好`)
    }
    dialog.open = false
    // 新建的调度项可能属于另一个 catalog：把列表切过去，否则「保存成功但列表里没有」
    // 会被当成失败（切过来还会顺带把它的历史一起刷出来）。
    if (body.catalog && body.catalog !== catalog.value) {
      catalog.value = body.catalog
      session.selectCatalog(body.catalog)
      historySchedule.value = ''
    }
    await load()
    await loadHistory()
  } catch (cause) {
    notifyError(cause)
  } finally {
    dialog.saving = false
  }
}

async function confirmDelete(row) {
  const ok = await confirmDanger({
    title: '删除调度项',
    target: `${row.name}（${row.cron}）`,
    detail: '删除是真删：这条规则不再跑。历史扫描仍在（按名字存的字符串），'
      + '但之后再也查不到「这条规则当时长什么样」。只是暂时不想跑，请用「停用」。',
  })
  if (!ok) return
  try {
    await sensitivityApi.deleteSchedule(row.id)
    notifySuccess(`${row.name} 已删除`)
    if (historySchedule.value === row.name) {
      historySchedule.value = ''
    }
    await load()
    await loadHistory()
  } catch (cause) {
    notifyError(cause)
  }
}

// ------------------------------------------------------------------ 历史

const historyLoading = ref(false)
const historyError = ref('')
const historyRows = ref([])
/** 只看某一条调度项的历史（空 = 这个 catalog 下所有调度项）。 */
const historySchedule = ref('')

async function loadHistory() {
  if (!catalog.value) {
    // 跨 catalog 的扫描历史要服务管理员；没有选中 catalog 时不主动去撞 403。
    historyRows.value = []
    historyError.value = ''
    return
  }
  historyLoading.value = true
  historyError.value = ''
  try {
    const payload = await sensitivityApi.listScans({
      catalog: catalog.value,
      triggerType: 'SCHEDULE',
      scheduleName: historySchedule.value,
      page: 1,
      pageSize: 10,
    })
    historyRows.value = payload?.items || []
  } catch (cause) {
    historyError.value = describeError(cause)
    historyRows.value = []
  } finally {
    historyLoading.value = false
  }
}

function showHistory(row) {
  historySchedule.value = row.name
  loadHistory()
}

function clearHistory() {
  historySchedule.value = ''
  loadHistory()
}

// ------------------------------------------------------------------ 展示辅助

function scopeText(row) {
  const parts = [row.catalog || '']
  if (row.database) {
    parts.push(row.database)
  }
  if (row.table) {
    parts.push(row.table)
  }
  return parts.filter(Boolean).join('.')
}

function executionLabel(value) {
  if (value === 'SPARK') {
    return '下推 Spark'
  }
  if (value === 'IN_PROCESS') {
    return '进程内'
  }
  return value || '进程内'
}

function shortId(id) {
  const text = String(id || '')
  return text.length > 12 ? `${text.slice(0, 12)}…` : text
}

function statusType(status) {
  switch (status) {
    case 'SUCCEEDED':
      return 'success'
    case 'FAILED':
      return 'danger'
    case 'RUNNING':
      return 'warning'
    case 'CANCELLED':
      return 'info'
    default:
      // PENDING / SUBMITTING：还没开始读表，是 B 路线的两个前置态
      return 'info'
  }
}

// ------------------------------------------------------------------ 生命周期

function refresh() {
  return Promise.all([load(), loadHistory()])
}

onMounted(refresh)
onConsoleRefresh(refresh)

/** 四道闸门（§5.6.4 / §5.6.5）。 */
const GATES = [
  {
    gate: '同一条调度项',
    detail:
      '一个 cron 点只触发一次：租约只让一台实例读表，正确性靠 next_run_at 的条件更新；抢占时就把该值推进到下一个 cron 点，下一次从「现在」算。',
  },
  {
    gate: '同一张表',
    detail:
      '同时只允许一个在跑的扫描（PENDING / SUBMITTING / RUNNING），重入返 409——但**只对 scope=TABLE 生效**，见下。',
  },
  {
    gate: '同一 catalog（A 路线）',
    detail:
      '默认 2，超了返 429。默认值来自 lakehouse.rest.sensitivity.scan.max-concurrent，可由 catalog 的 maxConcurrentScans 覆盖，**单次请求不能覆盖**（它是资源保护，不是本次请求的参数）。',
  },
  {
    gate: '一次扫描的表数',
    detail:
      '上限 5000（max-tables-per-scan），超了返 400 并提示「把范围缩到一个库或一张表」。catalog 级 cron 最容易撞到这一条。',
  },
  {
    gate: '采样量',
    detail:
      '默认 2000，上限 10000；写一个超过上限的值返 400，不静默截断。METADATA_ONLY 下这个数字不起作用。',
  },
]
</script>

<style scoped>
.pc-gap {
  margin-bottom: 12px;
}

.pc-sched-catalog {
  width: 190px;
}

.pc-sched-name {
  font-weight: 500;
}

.pc-sched-id {
  font-size: 12px;
}

.pc-sched-sub {
  font-size: 12px;
}

.pc-sched-scope-tag {
  margin-right: 6px;
}

.pc-sched-foot {
  padding: 10px 14px 2px;
  font-size: 12px;
  line-height: 1.7;
}

.pc-sched-list {
  margin-top: 12px;
}

.pc-sched-chip {
  max-width: 260px;
}

.pc-sched-failed {
  color: var(--el-color-danger);
}

.pc-sched-card {
  margin-top: 12px;
}

.pc-sched-table {
  margin-bottom: 4px;
}

.pc-sched-subtip {
  margin-top: 12px;
}

.pc-sched-subtip p + p {
  margin-top: 8px;
}

.pc-sched-row {
  display: flex;
  gap: 12px;
}

.pc-sched-col {
  flex: 1;
  min-width: 0;
}

.pc-sched-num {
  width: 100%;
}

.pc-sched-dialog-tip {
  margin-top: 4px;
}

.pc-sched-dialog-tip p + p {
  margin-top: 8px;
}

@media (max-width: 900px) {
  .pc-sched-row {
    flex-direction: column;
    gap: 0;
  }
}
</style>
