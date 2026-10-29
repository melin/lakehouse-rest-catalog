<template>
  <div class="pc-page">
    <PageHeader
      :title="`表：${table}`"
      :subtitle="`${prefix} / ${namespace} / ${table}　读的是 Lance Namespace REST（POST /lance/v1/table/{id}/describe，id = ${fullId}），看到的登记与 Lance 客户端读到的是同一份。`"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'browse', query: { catalog: prefix } })">
          <el-icon><Back /></el-icon>
          <span class="pc-btn-text">返回列表</span>
        </el-button>
        <el-button size="small" @click="$router.push({ name: 'permissions', query: { catalog: prefix } })">
          <el-icon><Lock /></el-icon>
          <span class="pc-btn-text">权限</span>
        </el-button>
        <el-button size="small" :loading="overview.loading.value" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <!-- 格式还没确认（深链接进来、catalog 列表在路上）：先给加载态，不发任何请求 -->
    <ResourceState
      v-if="lanceCatalog === null"
      :loading="overview.loading.value"
      :error="overview.error.value"
    />

    <!-- 深链接敲到别的协议面的 catalog 上：本页整页建立在 `/lance/v1/**` 上，对它们注定被拒 -->
    <PanelCard v-else-if="!lanceCatalog">
      <TipAlert type="info">
        <template #title>catalog「{{ prefix }}」不是 Lance 协议面，本页不适用</template>
        <div>
          本页读的是 <code class="pc-mono">/lance/v1/table/{{ fullId }}/describe</code>，
          而 Paimon catalog 走 <code class="pc-mono">databases/**</code>、Iceberg catalog 走
          <code class="pc-mono">namespaces/**</code>（同一个 <code class="pc-mono">/v1/{prefix}</code> 基址）。
          同名的表请到对应的表详情页看。
        </div>
      </TipAlert>
      <!-- 两颗按钮留在气泡外面：TipAlert 的正文收在问号的气泡里，那边点不到东西 -->
      <div class="pc-tools-row">
        <el-button size="small" @click="$router.push({ name: 'table', params: { prefix, database: namespace, table } })">
          <el-icon><Document /></el-icon>
          <span class="pc-btn-text">打开 Paimon 表详情</span>
        </el-button>
        <el-button size="small" @click="$router.push({ name: 'icebergTable', params: { prefix, database: namespace, table } })">
          <el-icon><Document /></el-icon>
          <span class="pc-btn-text">打开 Iceberg 表详情</span>
        </el-button>
      </div>
    </PanelCard>

    <PanelCard v-else flush>
      <el-tabs v-model="tab" class="pc-tabs" @tab-change="onTabChange">
        <el-tab-pane label="详细信息" name="details" />
        <el-tab-pane label="版本" name="versions" />
        <el-tab-pane label="标签" name="tags" />
        <el-tab-pane label="文件列表" name="files" />
        <el-tab-pane label="血缘" name="lineage" />
        <el-tab-pane label="存储优化" name="optimization" />
      </el-tabs>

      <div class="pc-tab-body">
        <ResourceState :loading="overview.loading.value" :error="overview.error.value" />

        <template v-if="described">
          <!------------------------------ 详细信息 -------------------------------->
          <template v-if="tab === 'details'">
            <div class="pc-stats pc-stats-tight">
              <div class="pc-stat">
                <div class="pc-stat-label">数据集状态</div>
                <div class="pc-stat-value pc-stat-value-sm">{{ stateLabel }}</div>
                <div class="pc-stat-foot">
                  {{ described.is_only_declared === true
                    ? '登记过，但盘上还没有数据集'
                    : 'describe 时探过存储（check_declared）' }}
                </div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">版本</div>
                <div class="pc-stat-value">{{ meta.version ?? '—' }}</div>
                <div class="pc-stat-foot">
                  {{ meta.version === undefined || meta.version === null
                    ? '要读 manifest 才知道（见下方按钮）'
                    : '最新 manifest 里的版本号' }}
                </div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">分片（fragments）</div>
                <div class="pc-stat-value">{{ formatNumber(meta.stats?.num_fragments) }}</div>
                <div class="pc-stat-foot">Lance 的「文件」单位，OPTIMIZE 合并的就是它</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">删除行</div>
                <div class="pc-stat-value">{{ formatNumber(meta.stats?.num_deleted_rows) }}</div>
                <div class="pc-stat-foot">软删除的行；COMPACT 会把它们真正物化掉</div>
              </div>
            </div>

            <div class="pc-detail-grid">
              <dl class="pc-kv">
                <dt>id</dt>
                <dd><CopyText :text="described.id || fullId" /></dd>
                <dt>位置</dt>
                <dd><CopyText :text="described.location" /></dd>
                <dt>namespace</dt>
                <dd class="pc-mono">{{ (described.namespace || [prefix, namespace]).join(' / ') }}</dd>
                <dt>表名</dt>
                <dd class="pc-mono">{{ described.table || table }}</dd>
                <dt>托管版本（managed_versioning）</dt>
                <dd>{{ described.managed_versioning === true ? '是（版本由服务端记账）' : '否（版本以盘上 manifest 为准）' }}</dd>
              </dl>
              <JsonBlock
                :value="described.properties || {}"
                label="属性（properties）"
                empty-text="没有属性"
                max-height="300px"
              />
            </div>

            <SectionTitle title="版本 / 统计 / schema">
              <template #tip>
                <TipNote>
                  这三项要读仓库里的最新 manifest（<code class="pc-mono">load_detailed_metadata=true</code>），
                  一次请求换一次读盘，所以默认不做。只登记过、还没有数据集的表会失败——
                  那是规范允许的结果，不是页面出错。
                </TipNote>
              </template>
            </SectionTitle>

            <el-button
              v-if="!detailed.data && !detailed.notice"
              size="small"
              :loading="detailed.loading"
              @click="loadDetailed"
            >
              <el-icon><Refresh /></el-icon>
              <span class="pc-btn-text">读取详细元数据</span>
            </el-button>

            <el-alert v-if="detailed.notice" type="info" :closable="false" show-icon :title="detailed.notice" />
            <el-alert v-if="detailed.error" type="error" :closable="false" show-icon :title="detailed.error" />

            <template v-if="detailed.data">
              <div class="pc-tools-row">
                <span class="pc-dim">
                  版本与统计已从 manifest 读出（下面这份 schema 是 JSON Arrow 的字段树）。
                </span>
                <span class="pc-spacer" />
                <el-button size="small" :loading="detailed.loading" @click="loadDetailed">
                  <el-icon><Refresh /></el-icon>
                  <span class="pc-btn-text">重新读取</span>
                </el-button>
              </div>
              <JsonBlock
                :value="detailed.data.schema"
                label="schema（JSON Arrow）"
                empty-text="manifest 里没有 schema"
                max-height="360px"
              />
            </template>
          </template>

          <!-------------------------------- 版本 ---------------------------------->
          <template v-else-if="tab === 'versions'">
            <div class="pc-tools-row">
              <span class="pc-dim">
                版本列表来自 <code class="pc-mono">POST /lance/v1/table/{id}/version/list</code>
                （倒序，最多 {{ VERSION_LIMIT }} 个）。这一页只读：写版本要理解 manifest 的
                盘上布局，那条路径属于 Lance 客户端。
              </span>
              <span class="pc-spacer" />
              <span class="pc-dim">{{ versionRows.length }} 个版本</span>
            </div>

            <ResourceState :loading="versions.loading.value" :error="versions.error.value" />
            <el-table
              v-if="!versions.error.value"
              v-loading="versions.loading.value"
              :data="versionRows"
              size="small"
              empty-text="这张表还没有版本（只登记过、或从没提交过数据）"
            >
              <el-table-column label="版本" width="90" align="right">
                <template #default="{ row }"><span class="pc-mono">{{ row.version }}</span></template>
              </el-table-column>
              <el-table-column label="提交时间" width="180">
                <template #default="{ row }">
                  <TimeText v-if="row.timestamp_millis" :millis="row.timestamp_millis" />
                  <span v-else class="pc-dim">—</span>
                </template>
              </el-table-column>
              <el-table-column label="manifest 大小" width="130" align="right">
                <template #default="{ row }">
                  <span class="pc-mono">
                    {{ row.manifest_size === undefined || row.manifest_size === null
                      ? '—' : formatBytes(row.manifest_size) }}
                  </span>
                </template>
              </el-table-column>
              <el-table-column label="E-Tag" width="120">
                <template #default="{ row }">
                  <span class="pc-mono pc-dim">{{ row.e_tag || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="manifest 路径" min-width="260">
                <template #default="{ row }">
                  <span class="pc-mono pc-dim pc-break">{{ row.manifest_path || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="" width="70" align="right" fixed="right">
                <template #default="{ row }">
                  <IconAction icon="View" label="详情" @click="showVersion(row)" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 标签 ---------------------------------->
          <template v-else-if="tab === 'tags'">
            <div class="pc-tools-row">
              <span class="pc-dim">
                标签读的是盘上的 <code class="pc-mono">_refs/tags/{name}.json</code>
                （<code class="pc-mono">POST /lance/v1/table/{id}/tags/list</code>）。
                没有标签是正常状态，不是这张表坏了——目录不存在时服务端回空 map 而不是 404。
              </span>
              <span class="pc-spacer" />
              <span class="pc-dim">{{ tagRows.length }} 个标签</span>
            </div>

            <ResourceState :loading="tags.loading.value" :error="tags.error.value" />
            <el-table
              v-if="!tags.error.value"
              v-loading="tags.loading.value"
              :data="tagRows"
              size="small"
              empty-text="这张表还没有标签"
            >
              <el-table-column label="标签名" min-width="200">
                <template #default="{ row }"><span class="pc-mono">{{ row.name }}</span></template>
              </el-table-column>
              <el-table-column label="指向版本" width="110" align="right">
                <template #default="{ row }"><span class="pc-mono">{{ row.version ?? '—' }}</span></template>
              </el-table-column>
              <el-table-column label="分支" width="140">
                <template #default="{ row }">
                  <span class="pc-mono">{{ row.branch || '主分支' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="manifest 大小" width="140" align="right">
                <template #default="{ row }">
                  <span class="pc-mono">
                    {{ row.manifestSize === undefined || row.manifestSize === null
                      ? '—' : formatBytes(row.manifestSize) }}
                  </span>
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!------------------------------ 文件列表 -------------------------------->
          <template v-else-if="tab === 'files'">
            <!-- 表不在仓库之下（客户端自己建的目录最常见）：catalog 级端点以 warehouse 为根，
                 出不了这个范围——这时给出去处，而不是一个注定 404 的列表 -->
            <el-alert v-if="filesRoot === null" type="warning" :closable="false" show-icon class="pc-hint">
              <template #title>这张表的位置不在 catalog 仓库之内，这里列不了</template>
              <div>
                文件浏览以 catalog 的仓库（<code class="pc-mono">{{ warehouse || '未取得' }}</code>）为根，
                而这张表的 <code class="pc-mono">location</code> 是
                <code class="pc-mono">{{ described.location || '（未登记）' }}</code>。
                Lance 表的数据目录通常由客户端创建，这很常见。要看它的文件，去 catalog 详情页的
                「文件列表」（能列到仓库根能及的任意目录）。
              </div>
              <div class="pc-tools-row">
                <el-button size="small"
                  @click="$router.push({ name: 'catalogDetail', params: { catalogName: prefix }, query: { tab: 'files' } })">
                  <el-icon><Coin /></el-icon>
                  <span class="pc-btn-text">打开 catalog 详情</span>
                </el-button>
              </div>
            </el-alert>

            <template v-else>
              <div class="pc-tools-row">
                <el-breadcrumb separator="/" class="pc-file-crumbs">
                  <el-breadcrumb-item>
                    <a class="pc-link" @click.prevent="openFilesDirectory(filesRoot)">表目录</a>
                  </el-breadcrumb-item>
                  <el-breadcrumb-item v-for="crumb in fileCrumbs" :key="crumb.path">
                    <a class="pc-link" @click.prevent="openFilesDirectory(crumb.path)">{{ crumb.name }}</a>
                  </el-breadcrumb-item>
                </el-breadcrumb>
                <span class="pc-spacer" />
                <el-button size="small" @click="openFilesDirectory(parentFilesPath)">
                  <el-icon><Top /></el-icon>
                  <span class="pc-btn-text">返回上级</span>
                </el-button>
              </div>

              <TipAlert v-if="fileListing?.truncated" type="warning" class="pc-hint">
                <template #title>只显示了前 {{ fileListing.maxEntries }} 条（共 {{ fileListing.total }} 条）</template>
                <div>
                  排序是目录优先、名称升序，因此被截掉的是尾部的文件。下钻到更具体的子目录，
                  或调大 <span class="pc-mono">lakehouse.rest.file-browser.max-entries</span>。
                </div>
              </TipAlert>

              <ResourceState
                :loading="filesLoading"
                :error="filesError"
                :empty="fileLoaded && !totalFileEntries"
                :empty-text="filePrefix ? `没有以「${filePrefix}」开头的条目` : '这个目录里没有文件'"
              />

              <div class="pc-filter-row">
                <el-input
                  v-model="filePrefixInput"
                  size="small"
                  class="pc-prefix-input"
                  placeholder="文件名前缀（回车过滤）"
                  clearable
                  @keyup.enter="applyFilePrefix"
                  @clear="applyFilePrefix"
                />
                <el-button size="small" @click="applyFilePrefix">查询</el-button>
                <TipNote>
                  这里只看这张表自己目录下的文件（<code class="pc-mono">_versions/</code> 与
                  <code class="pc-mono">data/</code> 都能下钻），出不了表目录。
                  当前目录相对表目录为 <code>{{ relativeFilesPath || '（表目录）' }}</code>。
                </TipNote>
                <span class="pc-spacer" />
                <span class="pc-dim">共 {{ totalFileEntries }} 条</span>
              </div>

              <el-table v-if="fileEntries.length || filesLoading" :data="fileEntries" size="small">
                <el-table-column label="名称" min-width="280">
                  <template #default="{ row }">
                    <a v-if="row.directory" class="pc-link pc-file-name" @click.prevent="openFilesDirectory(row.path)">
                      <el-icon class="pc-file-icon"><Folder /></el-icon>
                      {{ row.name }}
                    </a>
                    <span v-else class="pc-file-name pc-dim">
                      <el-icon class="pc-file-icon"><Document /></el-icon>
                      {{ row.name }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="类型" width="90">
                  <template #default="{ row }">{{ row.directory ? '目录' : '文件' }}</template>
                </el-table-column>
                <el-table-column label="大小" width="110" align="right">
                  <template #default="{ row }">
                    <span class="pc-mono">{{ row.directory ? '—' : formatBytes(row.size) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="修改时间" width="180">
                  <template #default="{ row }">
                    <TimeText :millis="row.modificationTime" mode="relative" />
                  </template>
                </el-table-column>
                <el-table-column label="相对仓库根的路径" min-width="260">
                  <template #default="{ row }">
                    <span class="pc-mono pc-dim pc-break">{{ row.path }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="52" align="center" fixed="right">
                  <template #default="{ row }">
                    <IconAction
                      v-if="!row.directory"
                      icon="Download"
                      label="下载"
                      type="primary"
                      @click="downloadEntry(row)"
                    />
                    <span v-else class="pc-dim">—</span>
                  </template>
                </el-table-column>
              </el-table>

              <TablePager
                :total="totalFileEntries"
                :page="filePage"
                :page-size="filePageSize"
                @page-change="onFilePageChange"
                @size-change="onFileSizeChange"
              />
            </template>
          </template>

          <!------------------------------- 存储优化 -------------------------------->
          <!-- 与 Paimon / Iceberg 两个详情页**同一个组件**：动作名与执行流程三种格式一致，
               差别只在参数键与指标口径，而那两样都在 utils/optimization.js 与执行侧。
               `format="LANCE"` 让它取 Lance 那套参数集（目标碎片**行数**、retention-hours、
               orphan 恒不可用），首屏就不必等策略响应。
               服务端按 `table_type=LANCE` 分派到 OPTIMIZE / VACUUM，并把
               retention-hours 抬到不低于 catalog 的 max-commit-interval-hours。 -->
          <TableOptimizationPanel
            v-else-if="tab === 'optimization'"
            ref="optimizationPanel"
            :catalog="prefix"
            :database="namespace"
            :table-name="table"
            format="LANCE"
          />
        </template>

        <!------------------------------- 血缘 -------------------------------->
        <!-- **在 `v-if="described"` 之外**：面板的数据来自 `/lineage/**`，与 describe 无关。
             放进那个 v-if 里，一次失败的 describe 会连带把一块本来能用的内容也藏掉 -->
        <LineagePanel v-if="tab === 'lineage'" :table-ref="lineageRef" />
      </div>
    </PanelCard>

    <!-- 版本详情：整条记录原样给出来（manifest 路径、E-Tag、metadata 都是排查时要看的） -->
    <el-drawer v-model="versionDrawer.open" :title="versionDrawer.title" size="640px">
      <JsonBlock :value="versionDrawer.payload" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import CopyText from '@/components/CopyText.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TableOptimizationPanel from '@/components/TableOptimizationPanel.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipAlert from '@/components/TipAlert.vue'
import TipNote from '@/components/TipNote.vue'
import { describeError } from '@/api/client.js'
import { consoleApi } from '@/api/console.js'
import { lanceApi, lanceId } from '@/api/lance.js'
import { managementApi } from '@/api/management.js'
import { notifyError, useRemotePaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { isLanceCatalog } from '@/utils/catalogFormat.js'
import { formatBytes, formatNumber } from '@/utils/format.js'

/**
 * Lance 表详情。与 Paimon 版 `TableView`、Iceberg 版 `IcebergTableView` 平行的一页，
 * 页签数量与版式照它们，数据来源按 Lance 面自己的能力给：
 *
 * <ul>
 *   <li><b>详细信息</b>：一次 `describe`（带 `check_declared`，否则「有没有数据集」
 *       恒为未探测）。版本 / 分片 / 删除行 / schema 要读 manifest，因此单独一个按钮——
 *       打开一张表的成本不该因为多了几个字段而变高。</li>
 *   <li><b>版本</b>：`version/list`（倒序）。**只读**：写版本要理解 manifest 的盘上
 *       布局，那条路径属于 Lance 客户端。</li>
 *   <li><b>标签</b>：`tags/list` 回的是 **map**（名字 → `{branch, version,
 *       manifestSize}`），`manifestSize` 是唯一一处驼峰。</li>
 *   <li><b>文件列表</b>：Lance 面没有「按表名列文件」的端点，走 catalog 级文件端点
 *       （根 = warehouse），初始路径由 `describe.location` 减去 warehouse 前缀算出。
 *       不在仓库下时给出去处而不是 404——Lance 表的数据目录通常由客户端建。</li>
 *   <li><b>存储优化</b>：复用 `TableOptimizationPanel`，`format="LANCE"`。服务端按
 *       `table_type=LANCE` 分派成 `OPTIMIZE`（合并碎片）与 `VACUUM`（删旧版本 +
 *       回收无引用文件）两条语句；`orphan` 在这张格式上恒不可用。</li>
 * </ul>
 *
 * <p><b>为什么没有「数据预览」页签。</b>服务端的预览工厂只有 Paimon 与 Iceberg 两个实现
 * （<code>TablePreviewFactory</code> 的两个 `*PreviewTableFactory`），Lance 没有——挂一个
 * 点开就 400 的页签比不给更糟。这条差距要等服务端补上 Lance 的预览实现再填。
 */

const route = useRoute()

const prefix = computed(() => route.params.prefix)
const namespace = computed(() => route.params.namespace)
const table = computed(() => route.params.table)

/** 拼出来的完整 id：describe / versions / tags 都按它调，两段叶子名只用于显示与路由。 */
const fullId = computed(() => lanceId(prefix.value, namespace.value, table.value))

const LineagePanel = defineAsyncComponent(() => import('@/components/lineage/LineagePanel.vue'))

/** 血缘里的表名：`catalog.namespace.table`（与引擎上报时用的名字一致）。 */
const lineageRef = computed(() => [prefix.value, namespace.value, table.value].join('.'))

/**
 * catalog 格式护栏：深链接进来时列表可能还没到，先 `ensureCatalogs()` 再判断，不猜。
 * 与 Iceberg 版同一套写法。
 */
const lanceCatalog = computed(() => {
  if (!session.state.catalogsLoaded) return null
  return isLanceCatalog(session.catalogByName(prefix.value))
})

async function ensureCatalogs() {
  if (session.state.catalogsLoaded) return
  await session.loadCatalogs().catch(() => null)
}

const tab = ref('details')

/** 概览 = 一次 describe（check_declared）。格式没确认前不发请求。 */
const overview = useResource(async () => {
  await ensureCatalogs()
  if (!lanceCatalog.value) return null
  return lanceApi.describeTable(fullId.value)
})

const described = computed(() => overview.data.value || null)

/** 详细元数据（读 manifest）：只在用户点了按钮之后才取。 */
const detailed = ref({ loading: false, data: null, notice: '', error: '' })

/**
 * 显示用的合并视图：详细元数据到手就用它的版本 / 统计，否则用基础 describe 的。
 * 两处都有 `version` / `stats` 字段，但只有 detailed 那一次会真的填上值。
 */
const meta = computed(() => ({
  version: detailed.value.data?.version ?? described.value?.version ?? null,
  stats: detailed.value.data?.stats ?? described.value?.stats ?? null,
}))

const stateLabel = computed(() => {
  if (described.value?.is_only_declared === true) return '只登记，存储里还没有数据集'
  if (described.value?.is_only_declared === false) return '存储里已有数据集'
  return '未探测'
})

/**
 * 读 manifest 换版本 / schema / 统计。
 *
 * <p>「只登记过」导致的失败是**预期结果**（规范允许 detailed 对 declared-only 失败，
 * `code 19`），因此这里不当错误展示，而是把那句话写进提示里——用户看到的应该是
 * 「这张表还没有数据集」，不是「读取失败」。
 */
async function loadDetailed() {
  detailed.value = { loading: true, data: null, notice: '', error: '' }
  try {
    detailed.value.data = await lanceApi.describeTable(fullId.value, { detailed: true })
  } catch (cause) {
    if (cause?.payload?.code === 19) {
      detailed.value.notice = '这张表只有登记、还没有数据集：先由客户端写入数据，详细元数据才有内容。'
    } else {
      detailed.value.error = describeError(cause)
    }
  } finally {
    detailed.value.loading = false
  }
}

// ------------------------------------------------------------------ 版本

/** 版本列表一次拉多少个。理由见 `api/lance.js` 的 `listVersions`。 */
const VERSION_LIMIT = 100

const versions = useResource(
  () => lanceApi.listVersions(fullId.value, { descending: true, limit: VERSION_LIMIT }),
  { immediate: false },
)

const versionRows = computed(() => versions.data.value?.versions || [])

const versionDrawer = reactive({ open: false, title: '', payload: null })

function showVersion(row) {
  versionDrawer.title = `版本：${row.version}`
  versionDrawer.payload = row
  versionDrawer.open = true
}

// ------------------------------------------------------------------ 标签

const tags = useResource(() => lanceApi.listTags(fullId.value), { immediate: false })

/** `tags` 是 map（名字 → 内容），这里摊平成行；空 map 与缺键都当「没有标签」。 */
const tagRows = computed(() => Object.entries(tags.data.value?.tags || {})
  .map(([name, content]) => ({ name, ...(content || {}) }))
  .sort((left, right) => left.name.localeCompare(right.name)))

// ------------------------------------------------------------------ 文件列表

/**
 * warehouse 只在文件页签第一次打开时才取（管理 API 的一次 GET）。
 * 取法与 `IcebergTableView` / `CatalogDetailView` 同一约定：
 * `storageConfigInfo.allowedLocations[0]`，file 存储退回 `properties.warehouse`。
 */
const catalogInfo = useResource(() => managementApi.getCatalog(prefix.value), { immediate: false })
const warehouse = computed(() => {
  const info = catalogInfo.data.value?.storageConfigInfo
  return info?.allowedLocations?.[0] || catalogInfo.data.value?.properties?.warehouse || ''
})

/**
 * 表目录相对仓库根的路径；location 不在 warehouse 下时是 null。
 *
 * <p>两端都先去掉尾斜杠再比：warehouse 配置里带不带尾斜杠、Lance 写 location 时带不带，
 * 两边的归一化差异不该让用户看见。
 */
const filesRoot = computed(() => {
  const location = described.value?.location
  if (!location || !warehouse.value) return null
  const root = warehouse.value.replace(/\/+$/, '')
  const target = location.replace(/\/+$/, '')
  if (target === root) return ''
  if (target.startsWith(`${root}/`)) return target.slice(root.length + 1)
  return null
})

const filePath = ref('') // 相对仓库根
const filePrefixInput = ref('')
const filePrefix = ref('')

/** 最近一次列表响应：`maxEntries` / `truncated` / `total` 不是行级字段，单独留一份。 */
const fileListing = ref(null)
/** 是否成功列过至少一次目录：空态提示只在这个前提下出现，否则和「还没加载」分不开。 */
const fileLoaded = ref(false)

async function fetchFiles({ page, pageSize }) {
  if (!described.value) return { items: [], total: 0 }
  // 列表依赖 warehouse（root 由 location 减它得出），而 catalog 信息要单独取一次：
  // 两个请求不能并发——并发时 root 还是 null，列表会静默返回空且不再重试
  if (!catalogInfo.data.value) {
    await catalogInfo.load()
  }
  if (filesRoot.value === null) return { items: [], total: 0 }
  // filePath 为空串表示「还没导航过」——默认落在表目录，而不是仓库根
  const target = filePath.value || filesRoot.value
  const payload = await consoleApi.listCatalogFiles(
    prefix.value, target, filePrefix.value, { page, pageSize },
  )
  fileListing.value = payload
  fileLoaded.value = true
  return { items: payload.entries || [], total: payload.page?.total ?? payload.total ?? 0 }
}

// 翻页由服务端切：这一层有多少条目、第 M 页是什么，都只有列完这一层才知道；
// 本地切片会先被 `max-entries` 截一刀，界面上的总数就比真实值小。
const {
  loading: filesLoading,
  error: filesError,
  rows: fileEntries,
  total: totalFileEntries,
  page: filePage,
  pageSize: filePageSize,
  load: loadFiles,
  reload: reloadFiles,
  onPageChange: onFilePageChange,
  onSizeChange: onFileSizeChange,
} = useRemotePaging(fetchFiles, { immediate: false })

/** 相对表目录的当前路径（面包屑与提示用；发请求用的是相对仓库根的 filePath）。 */
const relativeFilesPath = computed(() => {
  const root = filesRoot.value
  if (root === null) return ''
  const current = filePath.value || root
  return current === root ? '' : current.slice(root.length + 1)
})

const fileCrumbs = computed(() => {
  const segments = relativeFilesPath.value.split('/').filter(Boolean)
  return segments.map((name, index) => ({
    name,
    path: [filesRoot.value, ...segments.slice(0, index + 1)].filter(Boolean).join('/'),
  }))
})

/** 「返回上级」最多回到表目录：表详情的文件页签以表目录为根，出不了表外。 */
const parentFilesPath = computed(() => {
  const segments = relativeFilesPath.value.split('/').filter(Boolean)
  return [filesRoot.value, ...segments.slice(0, -1)].filter(Boolean).join('/')
})

function openFilesDirectory(path) {
  filePath.value = path
  // 换了目录就换了集合，页码必须归位（停在第 3 页会看到空表，而目录其实有 5 条）
  reloadFiles()
}

/** 回车或点「查询」才发请求：逐字符过滤会把每次按键都变成一次存储列举。 */
function applyFilePrefix() {
  filePrefix.value = filePrefixInput.value.trim()
  reloadFiles()
}

async function downloadEntry(row) {
  try {
    await consoleApi.downloadCatalogFile(prefix.value, row.path)
  } catch (error) {
    notifyError(describeError(error))
  }
}

/** 文件页签第一次打开时：loader 内部自己保证 catalog 信息先到（见 fetchFiles 的注释）。 */
function loadFilesIfFirst() {
  if (!fileLoaded.value && !filesLoading.value) {
    reloadFiles()
  }
}

// ------------------------------------------------------------------ 装载与刷新

const loadedTabs = new Set()

/** 存储优化页签的组件引用（它内部自己管策略与历史，父组件只管「什么时候加载」）。 */
const optimizationPanel = ref(null)

/**
 * 攒下的那次加载请求。
 *
 * <p>面板在 `@tab-change` 那一刻还不存在：页签内容按 `tab` 渲染，而 Element Plus 在
 * `v-model` 更新后**同步**抛出该事件，此时 DOM 还没更新，`optimizationPanel` 仍是空的——
 * 直接调用会被 `?.` 静默吃掉，而 `loadedTabs` 已经记下这个页签，切回来也不会补，
 * 页面就永远停在空白。Paimon / Iceberg 两个详情页踩过同一个坑，这里照搬它们的解法。
 */
const optimizationPending = ref(false)

watch(optimizationPanel, (panel) => {
  if (panel && optimizationPending.value) {
    optimizationPending.value = false
    panel.load()
  }
})

function loadOptimization() {
  if (optimizationPanel.value) {
    optimizationPanel.value.load()
  } else {
    optimizationPending.value = true
  }
}

function onTabChange(name) {
  if (loadedTabs.has(name)) return
  loadedTabs.add(name)
  if (name === 'versions') versions.load()
  if (name === 'tags') tags.load()
  if (name === 'files') loadFilesIfFirst()
  // 切到存储优化时才拉策略与历史：打开一张表的成本不该因为多了一个页签而变高
  if (name === 'optimization') loadOptimization()
}

function reloadAll() {
  overview.load()
  loadedTabs.clear()
  // 详细元数据与版本都可能已经换了（客户端刚提交过），一起作废
  detailed.value = { loading: false, data: null, notice: '', error: '' }
  onTabChange(tab.value)
  if (tab.value === 'files') loadFiles()
}

watch([prefix, namespace, table], () => {
  loadedTabs.clear()
  // 换表即作废文件浏览的位置与过滤：旧路径在新表目录里通常不存在
  filePath.value = ''
  filePrefix.value = ''
  filePrefixInput.value = ''
  fileListing.value = null
  fileLoaded.value = false
  detailed.value = { loading: false, data: null, notice: '', error: '' }
  overview.load()
  if (tab.value !== 'details') {
    loadedTabs.add(tab.value)
    onTabChange(tab.value)
  }
  // 换表后策略与历史必须重拉。面板按 props 自己拼请求，不依赖 overview，
  // 因此可以立刻发；文件页签则要等新的 location（它按 location 算根目录）
  if (tab.value === 'optimization') loadOptimization()
}, { immediate: true })
</script>

<style scoped>
.pc-tabs {
  padding: 0 16px;
}

.pc-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.pc-tab-body {
  padding: 14px 16px;
}

.pc-stats-tight {
  margin: 14px 0;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
}

.pc-stat-value-sm {
  font-size: 17px;
}

.pc-detail-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(280px, 1fr);
  gap: 16px;
  margin-top: 16px;
  margin-bottom: 4px;
}

.pc-file-crumbs {
  font-size: 13.5px;
}

.pc-file-icon {
  font-size: 13px;
  vertical-align: -2px;
  margin-right: 2px;
}

.pc-file-name {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  overflow-wrap: anywhere;
}

.pc-filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.pc-prefix-input {
  width: 240px;
}

@media (max-width: 1080px) {
  .pc-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
