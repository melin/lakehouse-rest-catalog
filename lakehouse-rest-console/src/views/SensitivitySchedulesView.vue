<template>
  <div class="pc-page">
    <PageHeader
      title="扫描调度"
      subtitle="把「多久扫一次」交给外部调度器（Airflow / k8s CronJob），这一页负责生成那一行可直接复制的发起命令。"
    >
      <template #actions>
        <el-button size="small" :loading="loading" :disabled="!form.catalog" @click="refresh">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <!--
      这一屏现在**是过渡态**：调度已拍板改为 catalog 自建（与表优化调度同形，属 P4），
      因此在第 6 张表 `lakehouse_sensitivity_schedule` 与 `/sensitivity-schedules` 的
      5 条端点落地之前，这里给的是「手动打一发」的那一行 curl —— 运维照着写进
      Airflow / k8s CronJob 就能用，而不是等一个还不存在的调度项。
      所以这一屏**没有「新建调度」按钮不是漏做**，而是「CRUD 还没到」：
      判定依据是代码现状，表与端点建好那天，这一屏整块的形态要一起换。
    -->
    <TipAlert type="info" class="pc-gap">
      <template #title>这一屏为什么没有「新建调度」</template>
      调度已拍板改为 <strong>catalog 自建</strong>（与表优化调度同形），属 <strong>P4</strong>：
      届时落第 6 张表 <code>lakehouse_sensitivity_schedule</code> 与
      <code>/sensitivity-schedules</code> 的 5 条端点，这一屏改成调度项 CRUD。
      在那之前它给的是<strong>过渡态</strong>的那一行命令——服务端只有一个发起入口
      （<code>POST /sensitivity-scans</code>），cron 由外部系统自己管，照着下面的 curl
      写进 Airflow / k8s CronJob 就能立刻用起来。代价与要动的地方见页面末尾那张卡。
    </TipAlert>

    <!-- ============================ 1. 调度建议（可复制的一发） ============================ -->
    <PanelCard title="调度建议" hint="一行 curl，原样交给 Airflow / k8s CronJob">
      <div class="pc-sched-form">
        <div class="pc-sched-field">
          <FieldLabel label="catalog" tip="必填。发起的扫描会读这个 catalog 下所有表的元数据" />
          <el-select
            v-model="form.catalog"
            size="small"
            filterable
            placeholder="选择 catalog"
            class="pc-sched-catalog"
            @change="onCatalogChange"
          >
            <el-option
              v-for="catalog in catalogNames"
              :key="catalog"
              :label="catalog"
              :value="catalog"
            />
          </el-select>
        </div>

        <div class="pc-sched-field">
          <FieldLabel
            label="范围"
            tip="层级不靠「哪几个字段有值」反推：TABLE 必须给库名 + 表名，DATABASE 必须给库名，缺一个服务端回 400"
          />
          <el-select v-model="form.scope" size="small" class="pc-sched-scope">
            <el-option label="整个 catalog" value="CATALOG" />
            <el-option label="单个库" value="DATABASE" />
            <el-option label="单张表" value="TABLE" />
          </el-select>
        </div>

        <div v-if="form.scope !== 'CATALOG'" class="pc-sched-field">
          <FieldLabel text="库" />
          <el-input v-model="form.database" size="small" placeholder="database" class="pc-sched-input" />
        </div>

        <div v-if="form.scope === 'TABLE'" class="pc-sched-field">
          <FieldLabel text="表" />
          <el-input v-model="form.table" size="small" placeholder="table" class="pc-sched-input" />
        </div>

        <div class="pc-sched-field">
          <FieldLabel
            text="模式"
            tip="元数据档只读列名 / 注释 / 类型（D1 + D2），不碰数据；采样档再加值层，会读前 N 行明文"
          />
          <el-select v-model="form.mode" size="small" class="pc-sched-mode">
            <el-option label="仅元数据（D1 + D2）" value="METADATA_ONLY" />
            <el-option label="采样（再加 D3 值层）" value="SAMPLED" />
          </el-select>
        </div>

        <div class="pc-sched-field">
          <FieldLabel
            text="执行位置"
            tip="省略该字段 = 跟着生效值走（请求 > catalog 的 defaultExecution > 全局）。显式写 SPARK 目前不可用：元数据档回 400、采样档回 501"
          />
          <el-select v-model="form.execution" size="small" class="pc-sched-exec">
            <el-option label="跟随生效值（省略字段）" value="" />
            <el-option label="进程内 IN_PROCESS" value="IN_PROCESS" />
            <el-option label="下推 SPARK（属 P4，尚未可用）" value="SPARK" />
          </el-select>
        </div>
      </div>

      <div class="pc-sched-warn">
        <el-icon><WarnTriangleFilled /></el-icon>
        <span>
          上面这些控件<strong>不落库</strong>：它们只决定下面那一行的内容。
          真正决定「多久跑一次」的是调度器里的 cron，控制台不参与。
        </span>
      </div>

      <div class="pc-sched-code">
        <el-button class="pc-sched-copy" size="small" text @click="copyCurl">复制</el-button>
        <pre class="pc-sched-pre"><code>{{ curlText }}</code></pre>
      </div>

      <div class="pc-sched-meta">
        <div>
          接口：<code class="pc-mono">{{ scanMethod }} {{ scanPath }}</code>
          ，成功回 <strong>202</strong> 与
          <code class="pc-mono">{scanId, catalog, status, execution}</code>
          ——按 <code class="pc-mono">scanId</code> 轮询
          <code class="pc-mono">GET /sensitivity-scans/{scanId}</code> 取结果。
        </div>
        <div>
          权限：<code class="pc-mono">CATALOG:ALTER</code>。
          cron 触发的执行体必须持有一个能发起扫描的凭据——
          静态令牌（<code class="pc-mono">lakehouse.rest.auth.tokens</code>，给脚本用的降级入口）
          或一个 client-credentials 主体，不能用某个人的登录令牌（它会过期，而 cron 不会）。
        </div>
        <div>
          令牌用的是占位符 <code class="pc-mono">&lt;主体令牌&gt;</code>，页面不会把真实令牌写进这段文本。
          服务端地址取自「连接设置」；留空时用当前控制台的地址（同源部署下两者相同）。
        </div>
      </div>

      <div class="pc-sched-cron">
        <span class="pc-dim">cron 只是个节拍示例：</span>
        <code class="pc-mono">*/30 * * * *</code>
        <span class="pc-dim">
          「每 30 分钟发起一次」。<strong>「只触发一次」由调度器自己保证</strong>
          （k8s CronJob 的 <code class="pc-mono">concurrencyPolicy: Forbid</code>、
          Airflow 的 <code class="pc-mono">max_active_runs=1</code>），
          服务端另有一道同表闸门兜底——两者互不假设（§13）。
        </span>
      </div>
    </PanelCard>

    <!-- ============================ 2. 最近由调度触发的扫描 ============================ -->
    <!--
      只读的建议页为什么还要一张表：§12 对 P4 的验收是「外部调度按 cron 触发一次扫描
      且只触发一次」——这条断言只能在扫描历史里被看见。而 `triggerType` 正是为此存在的列
      （§4.2 落库、`#overview` 的最近扫描已经带它）。没有这一块，「我把 Airflow 接上了」
      就只能靠翻日志证明。
    -->
    <PanelCard flush class="pc-sched-list" title="最近由调度触发的扫描" hint="triggerType=SCHEDULE，最多 10 条">
      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!loading && !error && !rows.length"
        empty-text="还没有 SCHEDULE 触发的扫描。上面那一行 curl 跑通一次，这里就会出现第一条。"
      >
        <el-table v-loading="loading" :data="rows" size="small">
          <el-table-column label="扫描" width="150">
            <template #default="{ row }">
              <code class="pc-mono">{{ shortId(row.id) }}</code>
            </template>
          </el-table-column>
          <el-table-column label="范围" min-width="220">
            <template #default="{ row }">
              <span class="pc-mono">{{ scopeText(row) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="模式" width="130">
            <template #default="{ row }">
              <span class="pc-dim">{{ row.mode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="执行" width="110">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.execution }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusType(row.status)" size="small">{{ row.status }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="扫到的表 / 列" width="130">
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
          <el-table-column label="结束" width="190">
            <template #default="{ row }">
              <TimeText :millis="row.finishedAt || row.startedAt || row.createdAt" />
            </template>
          </el-table-column>
        </el-table>
      </ResourceState>
      <div class="pc-sched-list-foot">
        <span class="pc-dim">
          只取最近 10 条、不分页：这一页看的是「调度到底有没有在发」，不是完整的扫描历史
          （完整历史在「敏感数据」总览的最近扫描里）。
        </span>
      </div>
    </PanelCard>

    <!-- ============================ 3. 下推 SPARK 的对照卡（§11 明确要求） ============================ -->
    <PanelCard
      class="pc-sched-card"
      title="下推 Spark 时，服务端把这一发变成什么"
      hint="复用表优化任务链路的哪几段"
    >
      <el-table :data="SPARK_REUSE" size="small" class="pc-sched-table">
        <el-table-column label="环节" width="230" prop="stage" />
        <el-table-column label="复用程度" width="190">
          <template #default="{ row }">
            <span :class="row.degree.startsWith('原样') ? 'pc-sched-strong' : ''">{{ row.degree }}</span>
          </template>
        </el-table-column>
        <el-table-column label="说明" prop="note" />
      </el-table>

      <SectionTitle title="三处不能照搬的语义">
        <TipNote>
          这才是这一节的重点：机械部分可以复用，但扫描的输入输出与优化任务不是一回事。
        </TipNote>
      </SectionTitle>
      <ol class="pc-sched-ol">
        <li v-for="item in SPARK_DIFFERENT" :key="item.title">
          <strong>{{ item.title }}</strong>
          {{ item.note }}
        </li>
      </ol>

      <TipAlert type="warning" class="pc-sched-subtip">
        <template #title>两条由复用带来的硬约束</template>
        <p>
          <strong>fail-closed：</strong>给一个没配执行集群的 catalog 建
          <code class="pc-mono">execution=SPARK</code> 的扫描必须回
          <strong>400</strong>，不静默退回进程内——静默降级会让「我以为它在集群上跑」
          变成一条查不出来的行为。
        </p>
        <p>
          <strong>闸门共用：</strong>下推的扫描作业与优化批次共用执行集群那一把
          <code class="pc-mono">maxConcurrentJobs</code>，没有独立的集群侧
          <code class="pc-mono">maxConcurrentScans</code>——各开一把闸门时，两侧各自都在额度内，
          合起来正好把同一台集群放到两份额度的驱动数，而这个总和没有任何地方在守。
          代价是两类任务会互相排队，这是有意的。
        </p>
      </TipAlert>
    </PanelCard>

    <!-- ============================ 4. 并发闸门 ============================ -->
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
        各自产出一份命中。服务端的原话是
        「<em>a scan is already &lt;STATUS&gt; for &lt;c&gt;.&lt;d&gt;.&lt;t&gt; (scan &lt;id&gt;);
        two scans of the same table would produce two pending findings for the same conclusion</em>」，
        而 <code class="pc-mono">ACTIVE_STATUSES</code> 是
        <code class="pc-mono">{PENDING, SUBMITTING, RUNNING}</code>。
        要「同一张表不重复扫」，得自己在调度器里按表拆调度项。
      </TipAlert>
    </PanelCard>

    <!-- ============================ 5. 换成自建调度要动什么 ============================ -->
    <PanelCard
      class="pc-sched-card"
      title="换成 catalog 自建调度要动什么"
      hint="这是另一条路线，不是这一屏还没做完"
    >
      <ol class="pc-sched-ol">
        <li>
          <strong>多出第 6 张表</strong>
          <code class="pc-mono">lakehouse_sensitivity_schedule</code>：实体数 5 → 6，
          DDL、快照与 Flyway 迁移基线都要跟着走一遍。
        </li>
        <li>
          <strong>多出三个端点</strong>
          <code class="pc-mono">GET / PUT / DELETE /sensitivity-schedules</code>。
        </li>
        <li>
          <strong>登记表与计数要同步</strong>：控制台
          <code class="pc-mono">src/api/endpoints.js</code> 加三条，
          校验脚手架里的扩展端点白名单与「N 组 M 项」计数一起改。
          <span class="pc-sched-failed">漏改会让守卫直接红</span>。
        </li>
        <li>
          <strong>多一套分布式租约</strong>：多副本下
          <code class="pc-mono">@Scheduled</code> 会变成 N 个副本各跑一次，
          要么加租约、要么容忍重复触发。优化任务已经为「必须内跑」付过这份成本，
          调度本身不必再付一遍——这就是推荐外部调度的理由。
        </li>
      </ol>

      <SectionTitle title="那第 6 张表的字段（§5.6.3）">
        <TipNote>
          列在这里不是为了现在就建，而是为了让「自建调度」的代价可估：
          它是一次建表 + 三个端点 + 一套租约，不是加个开关。
        </TipNote>
      </SectionTitle>
      <ul class="pc-sched-fields">
        <li v-for="field in SCHEDULE_FIELDS" :key="field.name">
          <code class="pc-mono">{{ field.name }}</code>
          <span class="pc-dim">{{ field.note }}</span>
        </li>
      </ul>

      <TipAlert type="info" class="pc-sched-subtip">
        <template #title>一条刻意的省略</template>
        这张表<strong>不带 <code class="pc-mono">last_scan_id</code></strong>：
        一次调度可能因范围变化对应多条扫描，用「扫描表里
        <code class="pc-mono">trigger_type=SCHEDULE</code> + <code class="pc-mono">schedule_name</code>」
        回查更准，也避免两张表互相更新。
      </TipAlert>
    </PanelCard>

    <TipAlert type="info" class="pc-gap">
      <template #title>哪些组合现在能跑通</template>
      两种模式都能跑（<code class="pc-mono">METADATA_ONLY</code> 与
      <code class="pc-mono">SAMPLED</code>：后者多读值层，采样要 <code class="pc-mono">CATALOG:ALTER</code>）。
      真正还跑不通的是 <code class="pc-mono">execution=SPARK</code>，而且它<strong>不是一个状态码</strong>：
      元数据档下服务端回 <strong>400</strong>（下推只读元数据的扫描没有收益），
      采样档下回 <strong>501</strong>（下推链路属 P4，还没接）。
      两者都是**显式拒绝而不是静默降级**——「我明明选了 SPARK」不该变成一条查不出来的行为。
    </TipAlert>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'

import FieldLabel from '@/components/FieldLabel.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipAlert from '@/components/TipAlert.vue'
import TipNote from '@/components/TipNote.vue'
import { describe } from '@/api/endpoints.js'
import { getBaseUrl } from '@/api/client.js'
import { sensitivityApi } from '@/api/sensitivity.js'
import { notifyError, notifySuccess, onConsoleRefresh } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * 扫描调度（mockup `#schedules`，设计稿 §5.6 / §11 L649 / §12 P4）。
 *
 * <p><b>这一屏现在是过渡态。</b>调度已拍板改为 **catalog 自建**（与表优化调度同形，
 * §5.6.3），属 P4；在第 6 张表 `lakehouse_sensitivity_schedule` 与
 * `/sensitivity-schedules` 的 5 条端点落地之前，这里给的是**过渡态**的那一行 curl：
 * cron 由外部系统自己管，运维照着写进 Airflow / k8s CronJob 就能立刻用。
 * 所以「没有新建调度按钮」的成因是「CRUD 还没到」，不是「设计里没有这一屏」——
 * 表与端点建好那天，这一屏整块形态要一起换（`docs/console.md` 那条也标了目标形态）。
 *
 * <p>它与「识别结果审核」里那两枚处置按钮的处理方式**必须分开讲**：那里的成因是
 * 「还没通」（P1 已通，按钮现在可点），这里的成因是「调度能力还没建」。
 * 同一句「按钮是灰的」，两种成因不能共用一句解释。
 *
 * <p><b>唯一的服务端数据是「最近由 `SCHEDULE` 触发的扫描」。</b>只读建议页本可以一个字都不取，
 * 但那样「我把 Airflow 接上了」就只能靠翻日志证明——而 §12 对 P4 的验收断言正是
 * 「外部调度按 cron 触发一次扫描且只触发一次」，它只能在扫描历史里被看见。
 * `trigger_type` 这一列在 §4.2 就落库，`#overview` 的最近扫描也带着它，这里只是把范围收窄到
 * `SCHEDULE`。判权随后端走 `CATALOG:DESCRIBE`（读历史，与读 catalog 本身同判法）。
 *
 * <p><b>那段 curl 里的路径不是抄来的</b>：它由 `describe('startSensitivityScan')`
 * 从端点登记表取出，因此服务端改路径时这一页跟着变。同理，`triggerType` 写的是
 * <strong>`SCHEDULE`</strong>——`ScanTrigger` 的三个值是 `MANUAL` / `LIFECYCLE` / `SCHEDULE`，
 * 写成 `SCHEDULED` 服务端会回 400（`UnknowntriggerType …; supported: MANUAL, LIFECYCLE, SCHEDULE`）。
 */
const [scanMethod, scanPath] = describe('startSensitivityScan').split(' ')

// ------------------------------------------------------------------ 表单（只影响可复制的那一行）

const form = reactive({
  catalog: session.state.selectedCatalog || '',
  scope: 'CATALOG',
  database: '',
  table: '',
  mode: 'METADATA_ONLY',
  /**
   * 空串 = **省略 `execution` 字段**，让服务端按三层覆盖取值
   * （请求 > catalog 的 `defaultExecution` > 全局 `lakehouse.rest.sensitivity.scan.execution`）。
   * 这一档是默认值：调度器是长期跑的东西，写死一个位置等于把 catalog 级设置作废。
   */
  execution: '',
})

const catalogNames = computed(() => (session.state.catalogs || []).map((catalog) => catalog.name))

function onCatalogChange(value) {
  // 写回全局上下文，与「敏感标签」等页同一口径：catalog 是控制台的核心上下文。
  session.selectCatalog(value || '')
  load()
}

// ------------------------------------------------------------------ 可复制的那一段

/**
 * 服务端地址。默认部署是**同源**（控制台由服务端自己托管），此时
 * `getBaseUrl()` 是空串，真正的地址就是当前页面的 origin——把 origin 写出来比
 * 留一个 `<server-url>` 占位更有用，因为它就是对的。只有在「连接设置」里显式配了
 * 别处，才用那个值。
 */
const baseUrl = computed(() => {
  const configured = getBaseUrl()
  if (configured) {
    return configured
  }
  return typeof window === 'undefined' ? 'http://<catalog-host>:8080' : window.location.origin
})

/**
 * 请求体。四个显式填空的字段是有意的：服务端**不靠「哪几个字段有值」反推层级**
 * （表名恰好等于库名这种巧合不该改语义），所以 `database` / `table` 该空的也要出现。
 */
const bodyJson = computed(() => {
  const payload = {
    catalog: form.catalog || '<catalog>',
    scope: form.scope,
    database: form.scope === 'CATALOG' ? '' : form.database.trim(),
    table: form.scope === 'TABLE' ? form.table.trim() : '',
    mode: form.mode,
  }
  if (form.execution) {
    payload.execution = form.execution
  }
  // 恒写 SCHEDULE（不是 SCHEDULED）：这一屏产出的每一发都是定时触发的。
  payload.triggerType = 'SCHEDULE'
  // 紧凑单行 JSON：`-d` 里换行虽然合法，但复制出来还要再折一遍才能一眼看完。
  return JSON.stringify(payload)
})

const curlText = computed(() =>
  [
    `curl -sS -X ${scanMethod} '${baseUrl.value}${scanPath}' \\`,
    `  -H 'Authorization: Bearer <主体令牌>' \\`,
    `  -H 'Content-Type: application/json' \\`,
    `  -d '${bodyJson.value}'`,
  ].join('\n'),
)

async function copyCurl() {
  try {
    await writeClipboard(curlText.value)
    notifySuccess('已复制发起命令')
  } catch {
    notifyError(new Error('浏览器拒绝了剪贴板访问，请手动选中这段文本复制'))
  }
}

/**
 * 写剪贴板。`navigator.clipboard` 只在安全上下文（https 或 localhost）可用，
 * 而这一屏产的片段正好是给内网 http 部署用的——所以留一条 `execCommand` 的退路，
 * 与 `components/CopyText.vue` 同一套做法（那边是行内小段文本，这里是多行代码块，
 * 因此没有直接复用组件）。
 */
async function writeClipboard(text) {
  if (navigator.clipboard?.writeText) {
    await navigator.clipboard.writeText(text)
    return
  }
  const area = document.createElement('textarea')
  area.value = text
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  const ok = document.execCommand('copy')
  document.body.removeChild(area)
  if (!ok) {
    throw new Error('execCommand copy failed')
  }
}

// ------------------------------------------------------------------ 最近由调度触发的扫描

const loading = ref(false)
const error = ref('')
const rows = ref([])

async function load() {
  if (!form.catalog) {
    rows.value = []
    return
  }
  loading.value = true
  error.value = ''
  try {
    const payload = await sensitivityApi.listScans({
      catalog: form.catalog,
      triggerType: 'SCHEDULE',
      page: 1,
      pageSize: 10,
    })
    rows.value = payload?.items || []
  } catch (cause) {
    error.value = describeError(cause)
  } finally {
    loading.value = false
  }
}

function refresh() {
  return form.catalog ? load() : Promise.resolve(null)
}

onMounted(refresh)
onConsoleRefresh(refresh)

// ------------------------------------------------------------------ 展示辅助

function shortId(id) {
  const text = String(id || '')
  return text.length > 12 ? `${text.slice(0, 12)}…` : text
}

/** 范围列：catalog 级只显示 catalog 名，库 / 表级补上后半段（与 `ScanSummary` 的三段字段对应）。 */
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

// ------------------------------------------------------------------ 静态对照内容（全部逐字来自 §5.6.5 / §5.6.3）

/** 下推 Spark 时的复用边界（§5.6.5 的表格）。 */
const SPARK_REUSE = [
  {
    stage: '执行集群配置与客户端',
    degree: '原样复用',
    note: 'resolve / clientFor / dispatchable 全部按 catalog 生效；扫描不引入第二套「集群配置」概念，并发闸门也共用同一把 maxConcurrentJobs。',
  },
  {
    stage: '探活（五问）',
    degree: '原样复用',
    note: '它问的是「这个集群能不能收下并跑起一个 Job」，与 Job 内容无关；第 4 步 dry-run 用的仍是同一份报文构造。',
  },
  {
    stage: 'k8s 报文骨架',
    degree: '复用骨架，换容器名',
    note: '前缀 paimon-scan-、容器名 scanner；backoffLimit=0 / restartPolicy=Never / imagePullSecrets / serviceAccountName 逐条照抄。TTL 与超时取扫描自己那组（scanJobTimeoutSeconds），不读优化那组。',
  },
  {
    stage: 'Spark 参数',
    degree: '复用注入位置，取值独立',
    note: '同一份注入点实现，但读的是扫描那一组参数（scan_spark_conf）——优化任务的参数改了不该悄悄改变扫描的资源诉求。',
  },
  {
    stage: '提交器的幂等 / 状态折算 / 取消',
    degree: '原样复用',
    note: '「先查同名再建」「先看 conditions 再看计数」「Background 删除」三条判据都不含优化语义。',
  },
  {
    stage: '调度租约',
    degree: '复用模式，新增一个通道',
    note: 'sensitivity.dispatch 必须登记进通道清单，否则多副本下每台都会提交一遍。',
  },
  {
    stage: '回调客户端',
    degree: '复用形状，另开两个端点',
    note: '令牌换成 X-Sensitivity-Scan-Token（作用域 = 这一次扫描），端点换成「清单」与「结果」两个。',
  },
  {
    stage: 'Spark 会话构建',
    degree: '抽公共类，不许复制',
    note: '三套配置（Paimon 的 data-token、Iceberg 的 access-delegation、Lance 的 parent + Authorization）与镜像里 arrow 的类路径排序都是编译器看不见的约定；复制一份就是下次升级 connector 时必分叉。',
  },
  {
    stage: '镜像',
    degree: '同一个优化器镜像',
    note: '扫描入口是同一个 jar 里的另一个 main class（SensitivityScannerJob）：三种格式的运行时与 Lance 的 arrow 排序白拿，单独打镜像省下的体积换不来那两处修复的分叉风险。',
  },
]

/** 三处「机械部分能复用、语义不能照搬」的地方（§5.6.5）。 */
const SPARK_DIFFERENT = [
  {
    title: '一个扫描 = 一个 Job，不要「组批」。',
    note:
      '优化任务的批次是「N 张表的集合」，因为每张表的压缩独立且失败可分。扫描没有这个对应物：' +
      '同一张表同时只允许一个 RUNNING 扫描，而组批会让两个调度周期各自抓走同一张表、落在两个 Job 里——互斥就被绕过了。' +
      '表内并行由任务内部做（逐表 try/catch + 逐表回调），不靠多开 Job。',
  },
  {
    title: '「没结果」必须上报成结果。',
    note:
      '优化任务里「k8s 已终态但某张表没上报」按失败处理。扫描里「没上报」有两种含义——扫了但没命中、压根没跑——' +
      '而覆盖率正是靠这个区分才成立。因此任务必须对每张表回一条 item，哪怕 findings=0、哪怕该表被跳过。',
  },
  {
    title: '明文的出网口只允许一条。',
    note:
      '优化任务全程只搬文件、不读内容。扫描任务的收口要强一档：原始行不出 Pod，回调只带打码样本与命中率；' +
      '并且任务侧禁止把样本值写进日志——优化任务的日志里可以有语句和指标，扫描的日志里不能有值。',
  },
]

/** 四道闸门（§5.6.4 / §5.6.5 / 本次实现）。 */
const GATES = [
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
    gate: '同一 catalog（B 路线）',
    detail:
      '下推的 Spark 作业计入执行集群那把 maxConcurrentJobs，与优化批次共用、一起排队；没有独立的集群侧扫描并发。',
  },
  {
    gate: '一次扫描的表数',
    detail:
      '上限 5000（max-tables-per-scan），超了返 400 并提示「把范围缩到一个库或一张表」。',
  },
  {
    gate: '采样量',
    detail:
      '默认 2000，上限 10000；写一个超过上限的值返 400，不静默截断。METADATA_ONLY 下这个数字不起作用。',
  },
]

/** 第 6 张表的字段（§5.6.3，逐条来自设计稿）。 */
const SCHEDULE_FIELDS = [
  { name: 'id', note: 'bigint 主键，自增' },
  { name: 'name', note: 'varchar(128) 调度项名，唯一键' },
  { name: 'cron', note: 'varchar(64)，5 或 6 段（秒级可选）' },
  { name: 'catalog_name / database_name / table_name', note: 'varchar，可空 = 上层范围，与扫描表同口径' },
  { name: 'mode / sample_size / execution', note: '复用扫描参数，含执行位置' },
  { name: 'enabled_flag', note: 'boolean，停用' },
  { name: 'last_run_at / next_run_at', note: 'epoch 毫秒，next_run_at 由服务端算' },
]
</script>

<style scoped>
.pc-gap {
  margin-bottom: 12px;
}

/* ---------------------------------------------------------------- 调度建议卡 */

.pc-sched-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
}

.pc-sched-field {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.pc-sched-catalog {
  width: 190px;
}

.pc-sched-scope {
  width: 150px;
}

.pc-sched-input {
  width: 170px;
}

.pc-sched-mode {
  width: 190px;
}

.pc-sched-exec {
  width: 210px;
}

/* 与其余页面的「一行提示」同一观感：图标 + 一句结论，不抢标题的注意力 */
.pc-sched-warn {
  display: flex;
  align-items: flex-start;
  gap: 6px;
  margin: 14px 0 8px;
  color: var(--el-color-warning);
  font-size: 12.5px;
  line-height: 1.7;
}

.pc-sched-code {
  position: relative;
}

/* 深色代码块：与「引擎接入配置」那一处逐字同款（同一份观感，
   右侧留出复制按钮的宽度，免得长路径从按钮底下穿过去） */
.pc-sched-pre {
  margin: 0;
  padding: 10px 68px 10px 12px;
  overflow: auto;
  border-radius: var(--pc-radius);
  background: #0f1729;
  color: #d6e2f5;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
  line-height: 1.75;
}

.pc-sched-copy {
  position: absolute;
  top: 4px;
  right: 6px;
  color: #cbd5e1;
  /* 深色底上的按钮要自己的底色：滚动时下面的文本会从它旁边掠过 */
  background: rgba(15, 23, 41, 0.92);
  border-radius: 4px;
}

.pc-sched-copy:hover {
  color: #fff;
}

.pc-sched-meta {
  margin-top: 10px;
  color: var(--pc-text-dim);
  font-size: 12.5px;
  line-height: 1.9;
}

.pc-sched-meta > div + div {
  margin-top: 4px;
}

.pc-sched-cron {
  margin-top: 10px;
  padding-top: 10px;
  border-top: 1px dashed var(--pc-border);
  font-size: 12.5px;
  line-height: 1.9;
}

.pc-sched-cron code {
  margin: 0 4px;
}

/* ---------------------------------------------------------------- 最近扫描 */

.pc-sched-list {
  margin-top: 12px;
}

.pc-sched-list-foot {
  padding: 10px 14px 2px;
  font-size: 12px;
  line-height: 1.7;
}

.pc-sched-failed {
  color: var(--el-color-danger);
}

/* ---------------------------------------------------------------- 说明卡 */

.pc-sched-card {
  margin-top: 12px;
}

.pc-sched-table {
  margin-bottom: 4px;
}

.pc-sched-strong {
  font-weight: 600;
}

.pc-sched-ol,
.pc-sched-fields {
  margin: 0;
  padding-left: 20px;
  font-size: 12.5px;
  line-height: 1.95;
}

.pc-sched-ol li + li,
.pc-sched-fields li + li {
  margin-top: 6px;
}

.pc-sched-fields {
  list-style: none;
  padding-left: 0;
}

.pc-sched-fields li {
  display: flex;
  gap: 10px;
}

.pc-sched-fields code {
  flex: 0 0 auto;
}

/* TipAlert 的正文收进气泡里，因此这里的 p 只影响气泡内的排版 */
.pc-sched-subtip {
  margin-top: 12px;
}

.pc-sched-subtip p + p {
  margin-top: 8px;
}
</style>
