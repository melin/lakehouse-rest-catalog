<template>
  <div class="pc-page">
    <PageHeader
      title="识别结果审核"
      subtitle="扫描判过的每一列在这里留一行：命中了哪个类型、置信度多少、各层各自贡献了什么。这一屏回答「为什么这么判」，落成标签与否的裁决另说。"
    >
      <template #actions>
        <!--
          P1 起两个处置动作可点。**没有选中行时置灰**（tooltip 说明原因）：
          批量接口的语义是「这一屏 20 条一起确认」，没有选中就没有动作对象，
          让按钮可点再报 400 只会制造一次「我点错了吗」的怀疑。
        -->
        <el-tooltip
          :content="selectedIds.length
            ? `把选中的 ${selectedIds.length} 条确认为敏感（落 source=MANUAL 的标签）`
            : '先勾选要确认的行：确认即落 MANUAL 标签，不会被下一轮扫描覆盖'"
          placement="bottom"
        >
          <span class="pc-finding-disabled-wrap">
            <el-button
              size="small"
              type="primary"
              :disabled="!selectedIds.length || deciding"
              :loading="deciding"
              @click="decide('CONFIRMED')"
            >
              <el-icon><Check /></el-icon>
              <span class="pc-btn-text">确认选中</span>
            </el-button>
          </span>
        </el-tooltip>
        <el-tooltip
          content="拒绝会同时写永久抑制项（按 列 + 类型 记）：下一轮扫描不再把这条结论提出来，直到列改名或治理员显式解禁"
          placement="bottom"
        >
          <span class="pc-finding-disabled-wrap">
            <el-button
              size="small"
              :disabled="!selectedIds.length || deciding"
              :loading="deciding"
              @click="decide('REJECTED')"
            >
              <el-icon><Close /></el-icon>
              <span class="pc-btn-text">拒绝选中</span>
            </el-button>
          </span>
        </el-tooltip>
        <el-button size="small" :loading="loading" :disabled="!catalogName" @click="load">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>      </template>
    </PageHeader>

    <TipAlert type="info" class="pc-hint">
      <template #title>这一屏现在是闭环的：确认落标签，拒绝落抑制项</template>
      <b>采样档</b>（D1–D3）的得分上限是
      <b>0.35 + 0.10 + 0.40 = 0.85</b>，恰好够到默认的自动应用阈值——因此
      <code class="pc-mono">AUTO_APPLIED</code> 只在「强格式类型 + 值层校验通过」时出现；
      <b>元数据档</b>（只跑 D1/D2）上限 0.45，恒停
      <code class="pc-mono">PENDING</code>。
      确认一条命中会落 <code class="pc-mono">source=MANUAL</code> 的标签（人工结论优先，
      自动结论不会覆盖它）；拒绝会同时写<b>永久抑制项</b>——否则下一轮扫描会把同一条结论
      重新提出来，「我说了不是，它每天还问一遍」。抑制项只在<b>列改名</b>（哈希变了）
      或治理员显式解禁后失效，解禁入口在本页下方的「已否决」卡片里。
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
          @selection-change="onSelectionChange"
        >
          <!-- 只有 PENDING / AUTO_APPLIED 可选：已确认 / 已拒绝的行再决策一次没有含义
               （服务端会把它算进 skipped，但让它进勾选集合会让人以为「这一条也要处理」）。 -->
          <el-table-column type="selection" width="42" :selectable="decidable" fixed="left" />
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

    <PanelCard flush>
      <div class="pc-list-head">
        <span class="pc-list-title">已否决（永久抑制项）</span>
        <TipNote>
          被拒绝的「列 + 类型」组合在这里留一行，下一轮扫描<b>直接跳过</b>它。
          解禁是置 <code class="pc-mono">enabled=false</code> 而<b>不删行</b>：
          删行等于删掉决策史，而「谁在什么时候否掉、谁又解禁」只能从这里读出来。
          解禁<b>不会立刻重扫</b>——下一轮扫描才会重新判这一列。
        </TipNote>
        <span class="pc-spacer" />
        <el-button size="small" :loading="suppressionsLoading" :disabled="!catalogName" @click="loadSuppressions">
          刷新
        </el-button>
      </div>
      <ResourceState
        :loading="suppressionsLoading"
        :error="suppressionsError"
        :empty="!suppressions.length"
        :empty-text="catalogName
          ? '还没有被否决的结论。拒绝一条命中就会在这里留一行，之后的重扫不再提它。'
          : '请先选择 catalog。'"
      >
        <el-table :data="suppressions" size="small" row-key="id">
          <el-table-column label="列引用" min-width="260">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.columnRef }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="190">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.typeCode }}</span>
            </template>
          </el-table-column>
          <el-table-column label="否决理由" min-width="200">
            <template #default="{ row }">
              <span v-if="row.reason">{{ row.reason }}</span>
              <span v-else class="pc-dim">未填理由</span>
            </template>
          </el-table-column>
          <el-table-column label="否决人" width="160">
            <template #default="{ row }">
              <span class="pc-mono">{{ row.decidedBy || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="否决时间" width="190">
            <template #default="{ row }">
              <TimeText :millis="row.decidedAt" />
            </template>
          </el-table-column>
          <el-table-column label="状态" width="150">
            <template #default="{ row }">
              <el-tag v-if="row.enabled" size="small" type="warning">生效中</el-tag>
              <el-tooltip v-else :content="`${row.releasedBy || '—'} 于 ${formatMillis(row.releasedAt)} 解禁`" placement="top">
                <el-tag size="small" type="info">已解禁</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" align="right" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="row.enabled"
                size="small"
                link
                type="primary"
                :loading="releasing === row.id"
                @click="release(row)"
              >
                解禁
              </el-button>
              <span v-else class="pc-dim">—</span>
            </template>
          </el-table-column>
        </el-table>
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
import { ElMessage, ElMessageBox } from 'element-plus'

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
 * <p><b>写侧（确认 / 拒绝）从 P1 起就在这一屏。</b>`POST /sensitivity-findings:decide`
 * 不是一个「加个按钮」的事：拒绝要同一事务里落一张抑制项表（按
 * `column_ref_hash + type_code` 记），否则下一轮扫描会把同一条结论重新提出来——
 * 「我说了不是，它每次还问」。因此按钮与抑制项卡片是同一件事的两半：
 * 上面的按钮产生否决，下面的卡片让否决可以被撤回（撤回 = 置 `enabled=false` 不删行）。
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
    inactive: '元数据档不跑这一层：它要采样才有值（采样档下这一层是唯一接近确证的证据）',
  },
  {
    key: 'dict',
    short: 'D4',
    label: 'D4 字典',
    note: '小基数枚举对照敏感字典',
    weight: 0.1,
    inactive: '本版不跑这一层（属 P4：需要业务字典，内置类型没有可对照的词表）',
  },
  {
    key: 'stat',
    short: 'D5',
    label: 'D5 统计特征',
    note: '基数比 / 长度分布 / 空值率',
    weight: 0.05,
    inactive: '本版不跑这一层（属 P4）',
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

// ---------------------------------------------------------------- 人审决策（P1）

const selectedIds = ref([])
const deciding = ref(false)

/** 只有待审 / 已自动落标的行可选：已决策的行再决策一次没有含义（服务端会算进 skipped）。 */
function decidable(row) {
  return row.state === 'PENDING' || row.state === 'AUTO_APPLIED'
}

function onSelectionChange(selection) {
  selectedIds.value = (selection || []).map((row) => row.id)
}

/**
 * 批量确认 / 拒绝。
 *
 * <p><b>整个批量一个请求。</b>服务端一次决策一个事务：逐条发会把「前 7 条成功、第 8 条失败」
 * 变成一个既不是开始也不是结束的界面，而人唯一能做的是再点一次（重复点击会撞上
 * 已经确认的那几条）。
 *
 * <p>拒绝时让用户填一句理由：抑制项是一句「这一列不是身份证」的断言，而断言要有人负责。
 * 取消输入 = 放弃这次操作（不是「无理由继续」）——理由在这里不是可选的装饰。
 */
async function decide(decision) {
  const ids = [...selectedIds.value]
  if (!ids.length) return
  const verb = decision === 'CONFIRMED' ? '确认' : '拒绝'
  let note = ''
  if (decision === 'REJECTED') {
    try {
      const input = await ElMessageBox.prompt(
        `将把选中的 ${ids.length} 条标记为「不是敏感」，并写入永久抑制项（下一轮扫描不再提示）。写一句理由：`,
        '拒绝这些命中',
        { confirmButtonText: '拒绝并抑制', cancelButtonText: '取消', inputPlaceholder: '例如：这是身份证后四位的掩码列' },
      )
      note = (input.value || '').trim()
    } catch {
      return
    }
  } else {
    try {
      await ElMessageBox.confirm(
        `确认后这 ${ids.length} 条会落成 source=MANUAL 的标签（后续扫描的自动结论不会覆盖它们）。`,
        '确认这些命中',
        { confirmButtonText: '确认', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }
  deciding.value = true
  try {
    const result = await sensitivityApi.decideFindings({ ids, decision, note })
    const extra = result?.skipped ? `，${result.skipped} 条已被决策过（跳过）` : ''
    ElMessage.success(`${verb} ${result?.decided ?? ids.length} 条${extra}`)
    selectedIds.value = []
    await Promise.all([load(), loadSuppressions()])
  } catch (exception) {
    ElMessage.error(exception?.message || `${verb}失败`)
  } finally {
    deciding.value = false
  }
}

// ---------------------------------------------------------------- 永久抑制项

const suppressions = ref([])
const suppressionsLoading = ref(false)
const suppressionsError = ref(null)
const releasing = ref(null)

async function loadSuppressions() {
  if (!catalogName.value) {
    suppressions.value = []
    return
  }
  suppressionsLoading.value = true
  suppressionsError.value = null
  try {
    const payload = await sensitivityApi.listSuppressions({ catalog: catalogName.value })
    suppressions.value = payload?.items || []
  } catch (exception) {
    suppressionsError.value = exception
  } finally {
    suppressionsLoading.value = false
  }
}

/**
 * 解禁一条抑制项。
 *
 * <p>二次确认写清代价：解禁之后<b>下一轮扫描会重新把这一列提出来</b>，
 * 也就是说这条否决会被推翻。不写这句，用户会以为「解禁 = 只是从列表里去掉」。
 */
async function release(row) {
  try {
    await ElMessageBox.confirm(
      `解禁后，下一轮扫描会重新判定 ${row.columnRef} 是不是 ${row.typeCode}。`
        + '如果判定结果不变，拒绝需要再做一次。',
      '解禁这条否决',
      { confirmButtonText: '解禁', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  releasing.value = row.id
  try {
    await sensitivityApi.releaseSuppression(row.id)
    ElMessage.success('已解禁；下一轮扫描会重新判定这一列')
    await loadSuppressions()
  } catch (exception) {
    ElMessage.error(exception?.message || '解禁失败')
  } finally {
    releasing.value = null
  }
}

/** 解禁时间在响应里是 epoch 毫秒（`releasedAt`），tooltip 里要给人看的时间。 */
function formatMillis(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString()
}

function onCatalogChange(value) {
  // 写回全局上下文：`catalog` 是控制台的核心上下文，切了不写回会让「目录浏览」等页面
  // 停在旧值上，而这一屏看起来已经切过去了。
  session.selectCatalog(value || '')
  expandedKeys.value = []
  selectedIds.value = []
  reload()
  // 抑制项是 catalog 级资源，换 catalog 必须重取——不取会让上一次的选择残留在这里，
  // 而「解禁」按钮点下去会操作到另一个 catalog 的行。
  loadSuppressions()
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
 * 某一层是当前不跑的层却带着非 0 分（配置残留）、或者这一行是脚本直接写进去的。
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
    ? { label: '采样档（D1–D3）', tag: 'success' }
    : { label: '元数据档（只跑 D1/D2）', tag: 'info' }
}

onMounted(() => {
  // catalog 列表要就位：`tableRouteFor` 靠它取 catalogFormat 才能选对表详情路由。
  // 这里只为「列引用」能不能点，不影响本屏主数据——失败也不拦（`.catch(() => null)`）
  session.loadCatalogs().catch(() => null)
  if (catalogName.value) {
    load()
    loadSuppressions()
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

/* 「这一层为什么是 0 分」用弱化的橙色而不是灰：它是**这一档不跑**（元数据档的 D3、
   属 P4 的 D4/D5），不是**本来没有**。两者的处置完全不同——前者换个模式或等一期，
   后者要改配置。 */
.pc-finding-off {
  color: var(--el-color-warning);
  font-size: 12.5px;
}
</style>
