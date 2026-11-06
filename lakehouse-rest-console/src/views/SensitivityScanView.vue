<template>
  <div class="pc-page">
    <PageHeader
      title="发起扫描"
      subtitle="按范围扫一遍，把「哪些列可能是身份证 / 手机号 / 姓名」识别出来。元数据档只看列名与类型，采样档还会读值套校验器。"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'sensitivityOverview' })">
          <el-icon><ArrowLeft /></el-icon>
          <span class="pc-btn-text">返回总览</span>
        </el-button>
      </template>
    </PageHeader>

    <!--
      这一屏的第一件事是把「这一发会读什么」说在前面，而不是藏在提交按钮之后：
      采样是被明确承认的「合法高权限读」（§5.4），调用方在按下按钮之前就必须知道
      自己将要读多少明文。因此这段提示放在表单**上方**，并且对元数据档要有一句明确的
      「本档不读数据」——否则按 mockup 的采样文案照抄会往人心里种一个不存在的顾虑；
      对采样档则要写明「读明文、要 ALTER、落库只落打码样本」，否则按下按钮的人
      不知道自己刚授权了什么。
    -->
    <TipAlert type="info" class="pc-hint">
      <template #title>两档的差别：读不读列值</template>
      元数据档（<code>METADATA_ONLY</code>）只看列名、注释与类型，秒级完成，
      <b>不读任何明文</b>；但它的天花板也在这里：<b>得分上限 0.45 &lt; 自动应用阈值 0.85</b>，
      所以只会产出「命中」供人审。采样档（<code>SAMPLED</code>）额外按列读前 N 行原值，
      套正则与校验位（模 11 / Luhn / 模 31），<b>强格式类型因此能到 0.85 并自动落标</b>；
      代价是这一次扫描会读明文，需要 <code>CATALOG:ALTER</code>，且<b>样本只以打码形式落库</b>
      （头 3 尾 4，至多 5 个）。
    </TipAlert>

    <PanelCard title="扫描参数">
      <el-form label-position="top" class="pc-scan-form">
        <!-- 范围：三段级联。层级由 scope 显式给出，不靠「哪几个字段有值」反推——
             表名恰好等于库名这种巧合不该改变语义（服务端 ScanRequest 的注释写的就是这条）。 -->
        <el-form-item label="扫描范围">
          <el-radio-group v-model="form.scope">
            <el-radio-button value="CATALOG">整个 catalog</el-radio-button>
            <el-radio-button value="DATABASE">单个库</el-radio-button>
            <el-radio-button value="TABLE">单张表</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <div class="pc-scan-grid">
          <el-form-item label="Catalog">
            <el-select v-model="form.catalog" placeholder="选择 catalog" @change="onCatalogChange">
              <el-option
                v-for="catalog in session.state.catalogs"
                :key="catalog.name"
                :label="catalog.name"
                :value="catalog.name"
              />
            </el-select>
          </el-form-item>

          <el-form-item v-if="form.scope !== 'CATALOG'" label="库">
            <el-select
              v-model="form.database"
              placeholder="选择库"
              :loading="loadingDatabases"
              :disabled="!form.catalog"
              @change="onDatabaseChange"
            >
              <el-option v-for="name in databases" :key="name" :label="name" :value="name" />
            </el-select>
          </el-form-item>

          <el-form-item v-if="form.scope === 'TABLE'" label="表">
            <el-select
              v-model="form.table"
              placeholder="选择表"
              :loading="loadingTables"
              :disabled="!form.database"
              filterable
            >
              <el-option v-for="name in tables" :key="name" :label="name" :value="name" />
            </el-select>
          </el-form-item>
        </div>

        <!-- 模式：两档现在都能选。原来采样档是灰的（501），P1 之后它真的会跑——
             灰着反而会让「这列叫 c1、内容是身份证」这类情况永远判不出来。 -->
        <el-form-item>
          <template #label>
            <FieldLabel label="识别模式" tip="元数据档看列名 / 注释 / 类型；采样档还要读值来套正则与校验位。" />
          </template>
          <el-radio-group v-model="form.mode">
            <el-radio-button value="METADATA_ONLY">METADATA_ONLY · 只跑列名 / 注释 / 类型</el-radio-button>
            <el-radio-button value="SAMPLED">SAMPLED · 再加值模式（读明文样本）</el-radio-button>
          </el-radio-group>
          <div class="pc-scan-note">
            采样档当前只跑 D1–D3（值模式）；D4 字典与 D5 统计特征属 <b>P4</b>，因此
            本档的得分上限就是 <code>0.35 + 0.10 + 0.40 = 0.85</code>——恰好等于阈值。
            读样本走的是<b>数据预览那条只读通道</b>（同一个并发池），原值不出服务端，
            落库的只有打码样本。
          </div>
        </el-form-item>

        <!-- 执行位置：SPARK 置灰（属 P4）。默认档是「跟随 catalog 默认」而不是一个具体值——
             写死 IN_PROCESS 等于把 catalog 级 defaultExecution 作废，而三层覆盖正是
             验收要走的路径（与调度建议屏产出的请求体同一口径）。 -->
        <el-form-item>
          <template #label>
            <FieldLabel label="执行位置" tip="三层覆盖的生效值：全局 yml < catalog 的 defaultExecution < 本次请求。" />
          </template>
          <el-radio-group v-model="form.execution">
            <el-radio-button value="">跟随 catalog 默认（不指定）</el-radio-button>
            <el-radio-button value="IN_PROCESS">进程内</el-radio-button>
            <el-radio-button value="SPARK" disabled>下推 Spark</el-radio-button>
          </el-radio-group>
          <div class="pc-scan-note">
            <b>不指定</b>时服务端按三层覆盖取值：全局
            <code>lakehouse.rest.sensitivity.scan.execution</code> &lt; catalog 的
            <code>defaultExecution</code> &lt; 本次请求。选一个具体值就是「这一次只管这一次」。
            <br />
            下推 Spark 复用表优化任务的提交 / 探活 / 跟踪链路，属 <b>P4</b>；元数据档下选它会得到
            <code>400</code>（fail-closed 而不静默退回进程内）。
          </div>
        </el-form-item>

        <!-- 采样量：元数据档下不生效，但仍显示出来并说明「本档不读值」，
             因为它是采样档的参数、把控件藏起来会让人以为这一屏没有这个能力。 -->
        <div class="pc-scan-grid">
          <el-form-item>
            <template #label>
              <FieldLabel label="采样行数上限" tip="硬上限 10000；有效样本 < 200 行时置信度 × 0.7。" />
            </template>
            <el-input-number
              v-model="form.sampleSize"
              :min="0"
              :max="10000"
              :step="500"
              :disabled="form.mode !== 'SAMPLED'"
              controls-position="right"
            />
            <div class="pc-scan-note">
              元数据档下不生效（不读值）。有效样本不足 200 行时整体 × 0.7——
              样本太少时「全命中」也可能是抽样抽得太巧。
            </div>
          </el-form-item>

          <el-form-item>
            <template #label>
              <FieldLabel label="血缘传播深度" tip="yml 可配 sensitivity.propagation.max-depth（默认 5）；超过深度停在「待确认」。" />
            </template>
            <el-input-number v-model="form.propagationMaxDepth" :min="1" :max="10" disabled controls-position="right" />
            <div class="pc-scan-note">血缘传播属 <b>P2</b>；当前只读 catalog 级设置里的值，不参与判定。</div>
          </el-form-item>
        </div>
      </el-form>
    </PanelCard>

    <!-- 本次会跳过什么：把「扫了 100 张、其中 3 张读失败」这类事后解释搬到事前。
         五条现在都真的生效（一条 P0、四条 P1），因此表头 hint 用「现在真的生效」
         而不是「其中只有一条成立」——这张表的价值全在于「写下来的就是会发生的」。
         治「扫完没发现」与「漏扫」的混淆，靠的正是这类事先声明。 -->
    <PanelCard title="本次会跳过什么" hint="这五条现在都真的生效">
      <el-table :data="SKIPS" size="small">
        <el-table-column prop="case" label="情形" min-width="200" />
        <el-table-column prop="handling" label="处置" min-width="220" />
        <el-table-column prop="stage" label="落地期" width="90" />
      </el-table>
    </PanelCard>

    <div class="pc-scan-actions">
      <el-button type="primary" :loading="submitting" :disabled="!canSubmit" @click="submit">
        <el-icon><Search /></el-icon>
        <span class="pc-btn-text">开始扫描</span>
      </el-button>
      <el-button @click="$router.push({ name: 'sensitivityOverview' })">取消</el-button>
      <!-- 一处「本来如此」而非「还没做」的入口：这一屏发起的是**手动**的一发
           （triggerType=MANUAL），而「多久扫一次」不在这一屏决定。调度本身属 P4
           （已拍板自建，与表优化调度同形），现在的调度屏是**过渡态的只读建议页**。
           把出口留在这里，是因为想定时的人此刻正站在这张表单前面。 -->
      <el-button @click="$router.push({ name: 'sensitivitySchedules' })">
        <el-icon><Timer /></el-icon>
        <span class="pc-btn-text">改为定时？看调度建议</span>
      </el-button>
      <span class="pc-dim">
        需要 <code>CATALOG:ALTER</code> 权限（挂 SELECT 上等于任何能查表的人都能让服务端替他扫全库）。
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'

import { catalogApi } from '@/api/catalog.js'
import { notifyError, notifySuccess } from '@/composables/index.js'
import { sensitivityApi } from '@/api/sensitivity.js'
import { session } from '@/store/session.js'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import TipAlert from '@/components/TipAlert.vue'

/**
 * 发起扫描（mockup `#scan`；设计稿 §11 / §5.6 / §12）。
 *
 * <p><b>两档都能跑，剩下两样仍置灰。</b>P1 之后采样档真的有服务端支撑，
 * 因此它从「灰着 + 写明 501」变成可选；仍未落地的两项继续**置灰 + 写明原因 + 写明落地期**，
 * 而不是拿掉：
 *
 * <ul>
 *   <li><b>SAMPLED</b> → 可选（P1 起 D1–D3 都跑；D4/D5 属 P4，因此上限恰为 0.85）；</li>
 *   <li><b>SPARK</b> 属 P4 → 元数据档下服务端返回 400（元数据档恒走进程内）；</li>
 *   <li><b>血缘传播</b>属 P2 → 控件只读。</li>
 * </ul>
 *
 * <p>把不可用的选项**画出来再说清为什么**，比藏起来更有价值：藏起来会让这一屏看起来
 * 「本来就没有这个能力」，而实际上它是「这条路还没通」——两者对使用者的含义完全不同
 * （前者是产品缺口、后者是进度）。这也是既有各屏的一贯做法。
 *
 * <p>提交成功回 <b>202</b>（长任务，不是 201），响应体只有 `{scanId, catalog, status,
 * execution}`。这里跳回总览看「最近扫描」，而不是留在原地——这一屏的职责是发起，
 * 结果在别处看。
 */

const router = useRouter()

const form = reactive({
  scope: 'CATALOG',
  catalog: session.state.selectedCatalog || '',
  database: '',
  table: '',
  mode: 'METADATA_ONLY',
  /**
   * 空串 = **省略 `execution` 字段**，让服务端按三层覆盖取值
   * （请求 > catalog 的 `defaultExecution` > 全局 `lakehouse.rest.sensitivity.scan.execution`）。
   * 这是默认档：写死 `IN_PROCESS` 等于把 catalog 级设置作废，而「不指定」恰恰是
   * 验收要走的那条路径（与调度建议屏产出的请求体同一口径）。
   */
  execution: '',
  sampleSize: 2000,
  propagationMaxDepth: 5,
})

const submitting = ref(false)
const databases = ref([])
const tables = ref([])
const loadingDatabases = ref(false)
const loadingTables = ref(false)

/**
 * 只有三层都填齐才让提交。**这不是前端校验代替服务端**——服务端按 `scope` 决定
 * database / table 是否必填，猜错会拿到 400；这里只是让「按钮点不了」比
 * 「点了报错」早一步发生，而判据与服务端同一套（scope 决定层级）。
 */
const canSubmit = computed(() => {
  if (!form.catalog) return false
  if (form.scope === 'DATABASE') return Boolean(form.database)
  if (form.scope === 'TABLE') return Boolean(form.database && form.table)
  return true
})

/**
 * 会跳过什么。五条现在都真的生效：第一条是 P0 的，后四条随 P1 的采样通道一起成立。
 *
 * <p>口径改动过一次：原来「降级 LIMIT 且置信度 × 0.85」是凭印象写的，
 * 与 `SensitivityScorer` 的实际规则不一致（没有 0.85 这个折损系数）。
 * 现在改成两件真实存在的事：采样方式恒 `HEAD`、样本不足 200 行才 × 0.7。
 *
 * <p>最后一条刻意不写成「无 TABLESAMPLE 能力时降级」：当前读通道（数据预览）
 * 根本没有抽样算子，所以**每一次采样都是 HEAD**，不是异常分支。写成条件句会让人
 * 以为正常情况下能拿到无偏抽样——那会让「前 2000 行恰好都是测试数据」这类结果
 * 被当成全表结论。
 */
const SKIPS = [
  {
    case: 'Lance 表且 schema 缺失 / external 表未刷新',
    handling: '跳过并标 METADATA_MISSING，不猜',
    stage: 'P0',
  },
  {
    case: '人工已拒绝的列',
    handling: '永久抑制，重扫不再产生 finding',
    stage: 'P1',
  },
  {
    case: '表里没有数据文件（刚建的空表）',
    handling: '读到 0 行 → 值层不出分，回落到元数据档的下界',
    stage: 'P1',
  },
  {
    case: '采样读失败（权限 / 存储抖动）',
    handling: '计入 tables_failed 并跳过这张表 —— 不把漏扫说成「干净」',
    stage: 'P1',
  },
  {
    case: '采样恒为顺序读（读通道无抽样算子）',
    handling: '按文件顺序读前 N 行，finding 上标 sampling_mode=HEAD',
    stage: 'P1',
  },
]

function onCatalogChange() {
  form.database = ''
  form.table = ''
  tables.value = []
  loadDatabases()
}

function onDatabaseChange() {
  form.table = ''
  loadTables()
}

async function loadDatabases() {
  if (!form.catalog) {
    databases.value = []
    return
  }
  loadingDatabases.value = true
  try {
    const payload = await catalogApi.listDatabases(form.catalog)
    databases.value = (payload.databases || []).map((item) => item.name || item)
  } catch (cause) {
    notifyError(cause)
    databases.value = []
  } finally {
    loadingDatabases.value = false
  }
}

async function loadTables() {
  if (!form.catalog || !form.database) {
    tables.value = []
    return
  }
  loadingTables.value = true
  try {
    const payload = await catalogApi.listTables(form.catalog, form.database)
    tables.value = (payload.tables || payload.identifiers || [])
      .map((item) => item.name || item.table || item)
      .filter(Boolean)
  } catch (cause) {
    notifyError(cause)
    tables.value = []
  } finally {
    loadingTables.value = false
  }
}

/**
 * 提交。
 *
 * <p>`catalog` / `scope` / `database` / `table` 四个字段都**显式给值**（该空的填空串）：
 * 服务端不靠「哪几个字段有值」反推层级，表名恰好等于库名这种巧合不该改变语义。
 *
 * <p>`execution` 反过来——**空就不带这个键**。它不是「范围」的一部分，而是一层
 * 可以被 catalog 设置接住的覆盖：传 `IN_PROCESS` 会把 catalog 的 `defaultExecution`
 * 遮住，而界面上「跟随 catalog 默认」这个选项本身就表达了「不指定」。
 */
async function submit() {
  submitting.value = true
  try {
    const body = {
      catalog: form.catalog,
      scope: form.scope,
      database: form.scope === 'CATALOG' ? '' : form.database,
      table: form.scope === 'TABLE' ? form.table : '',
      mode: form.mode,
      sampleSize: form.sampleSize,
      triggerType: 'MANUAL',
    }
    if (form.execution) {
      body.execution = form.execution
    }
    const result = await sensitivityApi.startScan(body)
    notifySuccess(`扫描已发起（${result.scanId}）。扫描是长任务，结果在「总览 → 最近扫描」里看。`)
    router.push({ name: 'sensitivityOverview' })
  } catch (cause) {
    notifyError(cause)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (!session.state.catalogsLoaded) {
    session.loadCatalogs().catch(() => {})
  }
  if (form.catalog && form.scope !== 'CATALOG') {
    loadDatabases()
  }
})
</script>

<style scoped>
.pc-scan-form :deep(.el-form-item__label) {
  padding-bottom: 4px;
}

/* 三段级联：宽屏并排、窄屏堆叠。用 grid 而不是 el-row/el-col：
   列数随 scope 在 1–3 之间变，grid 的 auto-fit 不用为每种组合写断点。 */
.pc-scan-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0 16px;
}

.pc-scan-note {
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--el-text-color-secondary);
}

.pc-scan-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 4px;
}

.pc-scan-actions .pc-dim {
  font-size: 12px;
}
</style>
