<template>
  <div class="pc-page">
    <PageHeader
      title="发起扫描"
      subtitle="按范围扫一遍元数据，把「哪些列可能是身份证 / 手机号 / 姓名」识别出来。P0 只跑元数据档（列名 / 注释 / 类型），不读数据。"
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
      自己将要读多少明文。因此这段提示放在表单**上方**，且它在 P0 有一句明确的
      「本档不读数据」——否则按 mockup 的采样文案照抄会往人心里种一个不存在的顾虑。
    -->
    <TipAlert type="info" class="pc-hint">
      <template #title>P0 只跑元数据档：不读任何列值</template>
      元数据档（<code>METADATA_ONLY</code>）只看列名、注释与类型，秒级完成，因此
      <b>不需要</b>采样授权、也不会读到明文。但它的天花板也在这里：
      <b>得分上限 0.45 &lt; 自动应用阈值 0.85</b>，所以这一档只会产出「命中」供人审，
      永远不会自动落成标签。要让「列名叫 <code>c1</code> 但内容是身份证」这类情况也被识别，
      需要值模式探测——那是 P1 的采样档。
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

        <!-- 模式：采样档置灰。灰掉的原因**必须写在选项旁边**，不能只把控件禁用——
             一个点不动的单选框，读起来是「这里我可能没权限」，而事实是「这一档还没做」。 -->
        <el-form-item>
          <template #label>
            <FieldLabel label="识别模式" tip="元数据档看列名 / 注释 / 类型；采样档还要读值来套正则与校验位。" />
          </template>
          <el-radio-group v-model="form.mode">
            <el-radio-button value="METADATA_ONLY">METADATA_ONLY · 只跑列名 / 注释 / 类型</el-radio-button>
            <el-radio-button value="SAMPLED" disabled>SAMPLED · 增加值模式与统计特征</el-radio-button>
          </el-radio-group>
          <div class="pc-scan-note">
            采样档需要读列值样本，<b>属 P1</b>（D3 值探测器与校验器还没实现）。现在选它会得到
            <code>501 未实现</code>，所以这里直接置灰——避免「发起了、没报错、也没结论」。
          </div>
        </el-form-item>

        <!-- 执行位置：P0 元数据档恒走进程内。SPARK 置灰，理由同上。 -->
        <el-form-item>
          <template #label>
            <FieldLabel label="执行位置" tip="三层覆盖的生效值：全局 yml < catalog 的 defaultExecution < 本次请求。" />
          </template>
          <el-radio-group v-model="form.execution">
            <el-radio-button value="IN_PROCESS">进程内（P0 唯一可用）</el-radio-button>
            <el-radio-button value="SPARK" disabled>下推 Spark</el-radio-button>
          </el-radio-group>
          <div class="pc-scan-note">
            <b>元数据档恒走进程内</b>：下推一个只读元数据的扫描没有收益，只多一次作业调度。
            下推 Spark 复用表优化任务的提交 / 探活 / 跟踪链路，属 <b>P4</b>；现在选它会得到
            <code>400</code>（元数据档不允许 SPARK，fail-closed 而不静默退回进程内）。
            <br />
            这里显示的是<b>生效值</b>，来自三层覆盖：全局默认
            <code>lakehouse.rest.sensitivity.scan.execution</code> &lt; catalog 的
            <code>defaultExecution</code> &lt; 本次请求。不是三个独立开关——只配一层也能跑。
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
            <div class="pc-scan-note">元数据档下不生效（不读值）。</div>
          </el-form-item>

          <el-form-item>
            <template #label>
              <FieldLabel label="血缘传播深度" tip="yml 可配 sensitivity.propagation.max-depth（默认 5）；超过深度停在「待确认」。" />
            </template>
            <el-input-number v-model="form.propagationMaxDepth" :min="1" :max="10" disabled controls-position="right" />
            <div class="pc-scan-note">血缘传播属 <b>P2</b>；P0 只读 catalog 级设置里的值，不参与判定。</div>
          </el-form-item>
        </div>
      </el-form>
    </PanelCard>

    <!-- 本次会跳过什么：把「扫了 100 张、其中 3 张读失败」这类事后解释搬到事前。
         它在 P0 只有一条真实条目（列清单读不到），其余是 P1/P2 才成立的情形——
         但**仍然列出来并标注落地期**：治「扫完没发现」与「漏扫」的混淆，
         靠的就是这类事先声明。 -->
    <PanelCard title="本次会跳过什么" hint="P0 生效的只有第一条">
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
           （triggerType=MANUAL），而「多久扫一次」不在这一屏决定——推荐的路线是
           外部调度器按 cron 调同一个端点（§5.6.2）。把出口留在这里，是因为
           想定时的人此刻正站在这张表单前面。 -->
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
 * 发起扫描（mockup `#scan`；设计稿 §11 / §5.6 / §12 P0）。
 *
 * <p><b>这一屏的形态由 P0 的边界决定，而不是由设计稿的八画面原型决定。</b>
 * 原型里那些可选项（采样档、下推 Spark、血缘传播）在 P0 都没有服务端支撑，
 * 因此它们在这里是**置灰 + 写明原因 + 写明落地期**，而不是拿掉：
 *
 * <ul>
 *   <li><b>SAMPLED</b> 属 P1（D3 值探测器 / 校验器还没实现）→ 服务端返回 501；</li>
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
  execution: 'IN_PROCESS',
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

/** P0 会跳过什么。第一条是真实生效的，其余三条是后续期的情形，标注了落地期。 */
const SKIPS = [
  {
    case: 'Lance 表且 schema 缺失 / external 表未刷新',
    handling: '跳过并标 METADATA_MISSING，不猜',
    stage: 'P0',
  },
  {
    case: '人工已拒绝的列',
    handling: '永久抑制，不再产生 finding',
    stage: 'P1',
  },
  {
    case: '无 TABLESAMPLE 能力',
    handling: '降级 LIMIT，标 sampling_mode=HEAD 且置信度 × 0.85',
    stage: 'P1',
  },
  {
    case: '列名是用例名 / 注释为空',
    handling: '名称与地址两层都拿不到分，靠值模式兜',
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
 * <p>三个字段都**显式给值**（该空的填空串）：服务端不靠「哪几个字段有值」反推层级，
 * 表名恰好等于库名这种巧合不该改变语义。`execution` 也显式传——虽然省略会走三层
 * 覆盖，但这一屏把「生效值」直接摆在表单上，传的就是用户看到的那一个。
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
      execution: form.execution,
      sampleSize: form.sampleSize,
      triggerType: 'MANUAL',
    }
    const result = await sensitivityApi.startScan(body)
    notifySuccess(`扫描已发起（${result.scanId}）。元数据档是长任务，结果在「总览 → 最近扫描」里看。`)
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
