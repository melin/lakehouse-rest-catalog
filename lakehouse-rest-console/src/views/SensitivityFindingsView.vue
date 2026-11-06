<template>
  <div class="pc-page">
    <PageHeader
      title="识别结果审核"
      subtitle="扫描判过的每一列在这里留一行：命中了哪个类型、置信度多少、各层各自贡献了什么。这一屏回答「为什么这么判」，落成标签与否的裁决另说。"
    >
      <template #actions>
        <!--
          两个「处置」动作刻意**放在页面上而不是藏起来**：它们代表这一屏的完整形态，
          但今天是置灰的。藏掉会让人以为审核工作流就只有「看」；可点再报错会更糟——
          服务端根本没有这个端点，报出来的是 404，读起来像「是不是我点错了」。
          置灰 + 悬停说出理由 + 写明归属期，是这里唯一诚实的表达。
        -->
        <el-tooltip content="属 P1：需要 POST /sensitivity-findings:decide（服务端尚无此端点）" placement="bottom">
          <span class="pc-finding-disabled-wrap">
            <el-button size="small" disabled>
              <el-icon><Check /></el-icon>
              <span class="pc-btn-text">批量确认</span>
            </el-button>
          </span>
        </el-tooltip>
        <el-tooltip content="属 P1：拒绝要同时写永久抑制项，否则下一轮扫描会把同一条结论重新提出来" placement="bottom">
          <span class="pc-finding-disabled-wrap">
            <el-button size="small" disabled>
              <el-icon><Close /></el-icon>
              <span class="pc-btn-text">批量拒绝</span>
            </el-button>
          </span>
        </el-tooltip>
        <el-button size="small" :loading="loading" :disabled="!catalogName" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>      </template>
    </PageHeader>

    <TipAlert type="info" class="pc-hint">
      <template #title>这一屏当前只读：P0 只产候选，不产标签</template>
      元数据档（只跑 D1 名称 / D2 类型）的得分上限是
      <b>0.35 + 0.10 = 0.45</b>，够不到默认的自动应用阈值
      <b>0.85</b>，所以 P0 的每一行都停在
      <code class="pc-mono">PENDING</code>——不会有「已自动落标」的行，也不会有「已拒绝」的行。
      「批量确认 / 批量拒绝」两个动作因此今天是置灰的：它们依赖
      <code class="pc-mono">POST /sensitivity-findings:decide</code>（属 P1），
      而拒绝还依赖一张抑制项表（按 <code class="pc-mono">列 + 类型</code> 记，否则下一轮扫描
      会把同一条结论重新提出来）。这条链路的落地期写在「为什么这么判」里那一行，不靠口头约定。
    </TipAlert>

    <PanelCard flush>
      <div class="pc-list-head">
        <el-select
          v-model="catalogName"
          size="small"
          class="pc-finding-catalog"
          placeholder="选择 catalog（必填）"
          @change="onCatalogChange"
        >
          <el-option
            v-for="catalog in session.state.catalogs"
            :key="catalog.name"
            :label="catalog.name"
            :value="catalog.name"
          />
        </el-select>
        <el-select v-model="filters.state" size="small" class="pc-finding-filter" @change="reload">
          <el-option label="全部状态" value="" />
          <el-option
            v-for="spec in STATE_OPTIONS"
            :key="spec.value"
            :label="spec.label"
            :value="spec.value"
          />
        </el-select>
        <el-input
          v-model="filters.type"
          size="small"
          clearable
          class="pc-finding-filter"
          placeholder="类型编码，如 CN_ID_CARD"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-input
          v-model="filters.table"
          size="small"
          clearable
          class="pc-finding-filter pc-finding-filter-wide"
          placeholder="表（库.表），如 default.orders"
          @keyup.enter="reload"
          @clear="reload"
        />
        <el-button size="small" :disabled="!catalogName" @click="reload">查询</el-button>
        <TipNote>
          四个条件<b>全部下推服务端</b>（先过滤再切页），因此筛的是这个 catalog 里全部命中，
          不是当前页。两个文本框而不是下拉框，是有具体理由的：
          <br /><br />
          · <b>类型不做候选下拉</b>——候选只能来自 <code class="pc-mono">GET /sensitive-types</code>，
          而那个端点判的是<b>服务管理员</b>（类型是全局识别能力）。为了一个下拉框把整页的门槛
          抬到服务级，会让只持有某个 catalog 的治理员打不开这一屏，而本页判的是
          <code class="pc-mono">CATALOG:DESCRIBE</code>。<br />
          · <b>表名要写「库.表」</b>——服务端按
          <code class="pc-mono">database + "." + table</code> 整体比较，只给表名匹配不上。
          <br /><br />
          <code class="pc-mono">state</code> 只认四个大写取值，给一个认不出的值服务端直接
          <b>400</b>（不静默忽略——静默忽略会让「按待审筛」回一份全部，而调用方以为自己在看
          待审队列）。
        </TipNote>
        <span class="pc-spacer" />
        <span class="pc-dim">
          <template v-if="catalogName">共 {{ total }} 条命中</template>
          <template v-else>未选 catalog</template>
        </span>
      </div>

      <ResourceState
        :loading="loading"
        :error="error"
        :empty="!rows.length"
        :empty-text="catalogName
          ? '这个 catalog 还没有命中记录。去「敏感数据」页发起一次元数据扫描。'
          : '请先选择 catalog：没有它这个接口就成了「一次读到所有 catalog 的识别结论」，而识别结论本身就是敏感信息，服务端因此硬要求这个参数。'"
      >
        <template #actions>
          <el-button
            v-if="catalogName"
            size="small"
            type="primary"
            @click="$router.push({ name: 'sensitivityOverview' })"
          >
            去发起扫描
          </el-button>
        </template>
        <el-table
          v-loading="loading"
          :data="rows"
          size="small"
          row-key="id"
          :expand-row-keys="expandedKeys"
          @expand-change="onExpandChange"
        >
          <el-table-column type="expand" fixed="left">
            <template #default="{ row }">
              <div class="pc-finding-why">
                <div class="pc-finding-why-head">
                  <span class="pc-list-title">为什么这么判</span>
                  <el-tag size="small" effect="plain" :type="modeSpec(row.samplingMode).tag">
                    {{ modeSpec(row.samplingMode).label }}
                  </el-tag>
                  <span class="pc-dim">
                    置信度 {{ percent(row.confidence) }} —— 各层得分按层系数加权求和
                  </span>
                  <span class="pc-spacer" />
                  <span class="pc-dim pc-mono">{{ row.columnRef }}</span>
                </div>

                <el-table v-if="layerRows(row).length" :data="layerRows(row)" size="small">
                  <el-table-column label="层" width="150">
                    <template #default="{ row: layer }">
                      <span>{{ layer.label }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="判什么" min-width="200">
                    <template #default="{ row: layer }">
                      <span class="pc-dim">{{ layer.note }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="本层得分" width="110" align="right">
                    <template #default="{ row: layer }">
                      <span class="pc-mono">{{ layer.score.toFixed(2) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="层系数" width="90" align="right">
                    <template #default="{ row: layer }">
                      <span class="pc-mono pc-dim">{{ layer.weight.toFixed(2) }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="贡献" width="110" align="right">
                    <template #default="{ row: layer }">
                      <span class="pc-mono" :class="{ 'pc-finding-zero': layer.contribution === 0 }">
                        {{ layer.contribution.toFixed(3) }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column label="说明" min-width="240">
                    <template #default="{ row: layer }">
                      <span v-if="layer.inactive" class="pc-finding-off">{{ layer.inactive }}</span>
                      <span v-else class="pc-dim">——</span>
                    </template>
                  </el-table-column>
                </el-table>
                <el-empty v-else description="无分层明细（这一行读不出各层得分）" :image-size="60" />

                <p v-if="layerRows(row).length" class="pc-finding-sum">
                  <span class="pc-dim">分项之和</span>
                  <span class="pc-mono">{{ layerSum(row).toFixed(3) }}</span>
                  <span class="pc-dim">，服务端记的置信度</span>
                  <span class="pc-mono">{{ row.confidence.toFixed(3) }}</span>
                  <span v-if="discounted(row)" class="pc-finding-off">
                    两者不等：采样档下有效样本不足 200 行时服务端会把总分整体乘 0.7，
                    分项之和因此会**大于**置信度
                  </span>
                  <span v-else class="pc-dim">
                    （元数据档不做样本量折损，两者一致）
                  </span>
                </p>

                <p class="pc-hint">
                  <b>层系数是代码常量、不可按类型改</b>（0.35 / 0.10 / 0.40 / 0.10 / 0.05）；
                  类型级可改的是<b>层内权重</b>与正则 / 校验器（在「敏感类型」页）。
                  把两者混成一句，会让人以为「把名称层系数改成 0.20」也能做，
                  而服务端不接受这个字段——表现是「改了、存了、没报错、也没变化」。
                  某一层<b>得分 0</b> 有两层含义：「没配这一层」与「配了但没命中」在加权和里同值，
                  这里看不出区别——要区分得去「敏感类型」页看那个类型的探测器配置。
                </p>

                <div class="pc-finding-facts">
                  <span>
                    <span class="pc-dim">扫描</span>
                    <span class="pc-mono pc-break">{{ row.scanId }}</span>
                  </span>
                  <span>
                    <span class="pc-dim">列</span>
                    <span class="pc-mono">{{ row.database }}.{{ row.table }}.{{ row.column }}</span>
                  </span>
                  <span>
                    <span class="pc-dim">判定时间</span>
                    <span class="pc-mono"><TimeText :millis="row.createdAt" /></span>
                  </span>
                  <span v-if="row.decidedBy || row.decidedAt">
                    <span class="pc-dim">裁决</span>
                    <span class="pc-mono">
                      {{ row.decidedBy || '—' }} · <TimeText :millis="row.decidedAt" />
                    </span>
                  </span>
                </div>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="列引用" min-width="250">
            <template #default="{ row }">
              <RouterLink
                v-if="tableRouteFor(row)"
                class="pc-link pc-mono"
                :to="tableRouteFor(row)"
              >
                {{ row.columnRef }}
              </RouterLink>
              <!-- 拿不到格式（catalog 列表未到或已删除）或 Lance 表：照实显示成文本。
                   画一个点了 404 的链接，比「不可点」更容易被误当成故障 -->
              <span v-else class="pc-mono pc-dim">{{ row.columnRef }}</span>
            </template>
          </el-table-column>

          <el-table-column label="类型" width="190">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.typeCode }}</span>
            </template>
          </el-table-column>

          <el-table-column label="置信度" width="160">
            <template #default="{ row }">
              <div class="pc-finding-conf">
                <el-progress
                  :percentage="Math.round(row.confidence * 100)"
                  :show-text="false"
                  :stroke-width="6"
                  :color="confidenceColor(row.confidence)"
                />
                <span class="pc-mono">{{ percent(row.confidence) }}</span>
              </div>
            </template>
          </el-table-column>

          <el-table-column label="命中探测器" min-width="220">
            <template #default="{ row }">
              <template v-if="hitLayers(row).length">
                <el-tag
                  v-for="layer in hitLayers(row)"
                  :key="layer.key"
                  size="small"
                  effect="plain"
                  class="pc-finding-chip"
                >
                  {{ layer.short }} {{ layer.score.toFixed(2) }}
                </el-tag>
              </template>
              <span v-else class="pc-dim">无分层明细</span>
            </template>
          </el-table-column>

          <el-table-column label="打码样本" min-width="200">
            <template #default="{ row }">
              <template v-if="row.samples && row.samples.length">
                <span
                  v-for="(sample, index) in row.samples.slice(0, 3)"
                  :key="index"
                  class="pc-mono pc-finding-sample"
                >
                  {{ sample }}
                </span>
                <span v-if="row.samples.length > 3" class="pc-dim">
                  等 {{ row.samples.length }} 条
                </span>
              </template>
              <span v-else class="pc-dim">
                {{ row.samplingMode === 'SAMPLED' ? '这一列没有有效样本' : '元数据档不读列值，因此无样本' }}
              </span>
            </template>
          </el-table-column>

          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <el-tag size="small" :type="stateSpec(row.state).tag">
                {{ stateSpec(row.state).label }}
              </el-tag>
            </template>
          </el-table-column>

          <el-table-column label="判定时间" width="190">
            <template #default="{ row }">
              <TimeText :millis="row.createdAt" />
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

    <TipAlert type="info" class="pc-hint">
      <template #title>这一页是「历史」，不是「现状」</template>
      一次扫描给每个命中列留一行，<b>重扫会再留一行</b>——同一个结论在多轮扫描里各有一行，
      这是 finding 表的本性（它记的是判断发生过什么）。
      问「这一列<b>现在</b>是什么」要看
      <RouterLink class="pc-link" :to="{ name: 'sensitivityTags' }">敏感标签</RouterLink>
      那一屏（它读 <code class="pc-mono">/column-tags</code>，是治理事实）。
      另外，打码样本是<b>打码后</b>的值——任何路径都不写原值，所以这里看不到明文，
      这不是显示问题。
    </TipAlert>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'

import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipAlert from '@/components/TipAlert.vue'
import TipNote from '@/components/TipNote.vue'
import { sensitivityApi } from '@/api/sensitivity.js'
import { onConsoleRefresh, useRemotePaging } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { ICEBERG, PAIMON, catalogFormatOf } from '@/utils/catalogFormat.js'

/**
 * 识别结果审核（人审队列的读侧，mockup `#findings`）。
 *
 * <p><b>为什么这一屏在 P0 就做。</b>P0 的扫描只产候选、不产标签，于是「扫完之后看什么」
 * 成了唯一的反馈回路：没有这一屏，治理员发起一次扫描只能看到 `findingsCreated=12`
 * 这个数字，无法回答「它凭什么判这一列是身份证」。而验收断言里那条
 * 「改一个内置类型的探测器权重后，下一轮扫描的判定随之改变」也要靠这一屏
 * **看出来**——前后两次展开同一列的「为什么这么判」，各层贡献的差就是证据。
 *
 * <p><b>写侧（确认 / 拒绝）不在这一屏。</b>`POST /sensitivity-findings:decide` 属 P1，
 * 它不是一个「加个按钮」的事：拒绝要同时落一张抑制项表（按 `column_ref_hash + type_code`
 * 记），否则下一轮扫描会把同一条结论重新提出来——「我说了不是，它每次还问」。
 * 那会让 §12 写明的「P0 = 五实体」变成六实体，因此 P0 只做读。按钮留在页面上置灰，
 * 是为了让「这一屏的完整形态长什么样」一眼可见（见模板里的注释）。
 *
 * <p><b>分页走远程（`useRemotePaging`）。</b>命中的量级是「每轮扫描每命中列一行」，
 * 而扫描是每天重跑的，两个维度都会长。这一屏的用法又是「逐条看为什么」——
 * 需要页码、页大小、过滤全部下推。这里与服务端 `Paging.paged` 的口径一致：
 * 传了 `page` 才切页，`total` 来自 `meta.total`。
 *
 * <p><b>`catalog` 是硬必填</b>（服务端 `SensitivityScanService.findings` 会 `badRequest`）。
 * 页面因此把它做成一个显式选择器而不是「从全局上下文偷偷取」：不选时**不发请求**、
 * 也把刷新按钮置灰，空态直接说清为什么——识别结论（类型 + 置信度）本身就是敏感信息，
 * 没有这个参数这个端点就成了「一次读到所有 catalog 的识别结论」。
 *
 * <p>初值三级取值：`?catalog=`（总览的「待人审」卡跳过来时带的）> 全局上下文
 * （`session.state.selectedCatalog`，与其他屏同一口径）> 空。第一级存在是因为
 * 跳转方**此刻就拿着那个值**，让用户再看一次选择器是多余动作；但它只是一次
 * 预填、不是协议，后续改动照样写回全局上下文（见 `onCatalogChange`）。
 */
const route = useRoute()
const catalogName = ref(String(route.query.catalog || '') || session.state.selectedCatalog || '')

/**
 * 「列引用」跳哪一页表详情：**三个协议面各有自己的表详情路由**。
 *
 * <p>不能一律用 `name: 'table'`——那一条是 Paimon 面的路径（`/tables/:prefix/:database/:table`），
 * 它的数据全部来自 `/databases/**` 那套端点；一张 Iceberg 表的库表名喂进去只会得到
 * 404，而界面上表现为「这条 finding 的链接坏了」，看不出是路由选错了。
 *
 * <p>判据取自 catalog 列表（`catalogFormat` 只在这一份数据里，见 `session.catalogByName`）。
 * 列表可能还没到（深链接直接进来），此时 `catalogFormatOf(null)` 回落 `PAIMON`——
 * 与服务端 `CatalogFormat.of()` 同一个方向，也是本特性引入前的默认行为。
 *
 * <p>**Lance 表没有表详情页**，这里给 `null`，模板据此把列引用显示成不可点的文本
 * （不是坏链接）。也能兜住「catalog 列表里查不到这个名字」的情况：拿不到格式就不宣称
 * 自己有跳转能力，比猜一个格式、跳过去 404 好。
 */
function tableRouteFor(row) {
  const catalog = session.catalogByName(row.catalog)
  if (!catalog) return null
  const format = catalogFormatOf(catalog)
  if (format === PAIMON) {
    return { name: 'table', params: { prefix: row.catalog, database: row.database, table: row.table }, query: { tab: 'sensitivity' } }
  }
  if (format === ICEBERG) {
    return { name: 'icebergTable', params: { prefix: row.catalog, database: row.database, table: row.table }, query: { tab: 'sensitivity' } }
  }
  return null
}

const filters = reactive({ state: '', type: '', table: '' })

/** 四个状态取值与服务端 `FindingState` 逐字对应（小写或写错都是 400，不是空结果）。 */
const STATE_OPTIONS = [
  { value: 'PENDING', label: '待人工确认' },
  { value: 'AUTO_APPLIED', label: '已自动落标' },
  { value: 'CONFIRMED', label: '已确认' },
  { value: 'REJECTED', label: '已拒绝' },
]

const expandedKeys = ref([])

/**
 * 五层的展示元数据：层系数是**代码常量**（`SensitivityScorer.WEIGHT_*`），
 * 不是可以在这里改的东西——所以它是常量表而不是响应里的字段。
 *
 * <p>顺序按层号（D1→D5），与服务端 `DetectorKind` 的声明顺序一致。
 * 层系数抄在这里有漂移风险，`verify-console.py` 有断言盯着它与 Java 常量逐字对齐。
 */
const LAYERS = [
  { key: 'name', short: 'D1', label: 'D1 名称·注释', note: '列名 / 列注释 / 表名上下文', weight: 0.35 },
  { key: 'type', short: 'D2', label: 'D2 类型·形状', note: 'SQL 类型 + 长度 / 精度', weight: 0.1 },
  {
    key: 'value',
    short: 'D3',
    label: 'D3 值模式',
    note: '采样值正则 + 校验器',
    weight: 0.4,
    inactive: 'P0 不跑这一层（要采样才有效值，属 P1）',
  },
  {
    key: 'dict',
    short: 'D4',
    label: 'D4 字典',
    note: '小基数枚举对照敏感字典',
    weight: 0.1,
    inactive: 'P0 不跑这一层（要读列值，属 P1）',
  },
  {
    key: 'stat',
    short: 'D5',
    label: 'D5 统计特征',
    note: '基数比 / 长度分布 / 空值率',
    weight: 0.05,
    inactive: 'P0 不跑这一层（要读列值，属 P1）',
  },
]

const paging = useRemotePaging(
  async ({ page, pageSize }) => {
    const payload = await sensitivityApi.listFindings({
      catalog: catalogName.value,
      state: filters.state,
      table: filters.table.trim(),
      type: filters.type.trim(),
      page,
      pageSize,
    })
    const items = payload?.items || []
    // `meta` 在没有传 `page` 时会是 null（服务端「不传就不分页」的老路径）。
    // 这里恒传 `page`，但兜底成 `items.length` 而不是 0——退化成 0 会让分页器整块消失，
    // 表格看起来只有一页，而数据其实还有。
    return { items, total: payload?.meta?.total ?? items.length }
  },
  { pageSize: 20, immediate: false },
)

const { loading, error, rows, total, page, pageSize, load, reload, onPageChange, onSizeChange } = paging

function onCatalogChange(value) {
  // 写回全局上下文：`catalog` 是控制台的核心上下文，切了不写回会让「目录浏览」等页面
  // 停在旧值上，而这一屏看起来已经切过去了。
  session.selectCatalog(value || '')
  expandedKeys.value = []
  reload()
}

function onExpandChange(row, expanded) {
  // 手风琴式：一次只展开一行。审核的用法是「把这一行看懂」，同时展开三行反而更难比对；
  // 而 `expand-row-keys` 是受控的，不自己维护会与服务端换页后的行 key 对不上。
  expandedKeys.value = expanded.length ? [row.id] : []
}

/** 一行的分层明细（缺的层按 0 补齐，与服务端 `normalize` 的口径一致）。 */
function layerRows(row) {
  const detectors = row.detectors || {}
  const keys = Object.keys(detectors)
  if (!keys.length) {
    return []
  }
  return LAYERS.map((layer) => {
    const raw = detectors[layer.key]
    const score = typeof raw === 'number' && Number.isFinite(raw) ? raw : 0
    return { ...layer, score, contribution: score * layer.weight }
  })
}

/** 只有**得分大于 0** 的层进「命中探测器」列：把 0 分的层也列出来会让每一行看起来都命中五层。 */
function hitLayers(row) {
  return layerRows(row).filter((layer) => layer.score > 0)
}

function layerSum(row) {
  return layerRows(row).reduce((sum, layer) => sum + layer.contribution, 0)
}

/**
 * 分项之和是否**小于**置信度。
 *
 * <p>采样档下有效样本不足 200 行时服务端整体乘 0.7（`SMALL_SAMPLE_FACTOR`），
 * 而 `effectiveRows` 不进 finding（它是扫描级的统计，不是这一列的性质），
 * 前端因此**算不出**那个折损系数，只能判断「两者不等」并说明原因。
 * 元数据档不做折损，两者必然一致——不一致只有三种可能：采样折损、
 * 某一层是 P0 不跑的层却带着非 0 分（配置残留）、或者这一行是脚本直接写进去的。
 * 三种都值得看见，所以这里不做静默归零。
 */
function discounted(row) {
  return Math.abs(layerSum(row) - row.confidence) > 0.001
}

function percent(value) {
  return `${Math.round((value || 0) * 100)}%`
}

/** 置信度越高越该被关注（不是「越好」）。用主色到橙色的两段，不用红绿——红绿在这里会被读成对错。 */
function confidenceColor(value) {
  const ratio = value || 0
  if (ratio >= 0.85) return '#c45656'
  if (ratio >= 0.6) return '#e6a23c'
  return 'var(--el-color-primary)'
}

function stateSpec(state) {
  return {
    PENDING: { label: '待人工确认', tag: 'warning' },
    AUTO_APPLIED: { label: '已自动落标', tag: 'success' },
    CONFIRMED: { label: '已确认', tag: 'success' },
    REJECTED: { label: '已拒绝', tag: 'info' },
  }[state] || { label: state || '—', tag: 'info' }
}

function modeSpec(mode) {
  return mode === 'SAMPLED'
    ? { label: '采样档（D1–D5）', tag: 'success' }
    : { label: '元数据档（只跑 D1/D2）', tag: 'info' }
}

onMounted(() => {
  // catalog 列表要就位：`tableRouteFor` 靠它取 catalogFormat 才能选对表详情路由。
  // 这里只为「列引用」能不能点，不影响本屏主数据——失败也不拦（`.catch(() => null)`）
  session.loadCatalogs().catch(() => null)
  if (catalogName.value) {
    load()
  }
})

/**
 * 顶栏「刷新」与手动刷新共用同一个入口，且都**先判 catalog**。
 *
 * <p>不判的话，顶栏刷新会把 `catalog=''` 发出去——`buildPath` 会丢掉空查询参数，
 * 服务端于是收到一个没有 catalog 的请求并回 400（`catalog is required`）。
 * 而这条错误会盖在空态上，把「请先选 catalog」这句真正有用的引导换成一个报错。
 */
function refresh() {
  return catalogName.value ? load() : Promise.resolve(null)
}

onConsoleRefresh(refresh)
</script>

<style scoped>
.pc-finding-catalog {
  width: 180px;
}

.pc-finding-filter {
  width: 170px;
}

.pc-finding-filter-wide {
  width: 200px;
}

/* 置灰按钮的悬停提示要靠外层 span：disabled 的按钮不派发鼠标事件，
   tooltip 挂在按钮上会一直不出现——「置灰且问不出原因」是最糟的一种置灰。 */
.pc-finding-disabled-wrap {
  display: inline-flex;
}

.pc-finding-why {
  padding: 10px 14px 6px;
}

.pc-finding-why-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.pc-finding-sum {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.9;
}

.pc-finding-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 22px;
  margin-top: 8px;
  font-size: 12.5px;
}

.pc-finding-conf {
  display: flex;
  align-items: center;
  gap: 8px;
}

.pc-finding-conf :deep(.el-progress) {
  flex: 1;
}

.pc-finding-chip {
  margin: 0 4px 4px 0;
}

.pc-finding-sample {
  display: inline-block;
  margin: 0 6px 4px 0;
  padding: 0 5px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  font-size: 12px;
}

.pc-finding-zero {
  color: var(--pc-text-dim);
}

/* 「P0 不跑这一层」用弱化的橙色而不是灰：它是**还没通**，不是**本来没有**。
   两者的处置完全不同——前者等 P1、后者要改配置。 */
.pc-finding-off {
  color: var(--el-color-warning);
  font-size: 12.5px;
}
</style>
