<template>
  <div class="pc-page">
    <PageHeader
      :title="`表：${table}`"
      :subtitle="`${prefix} / ${database} / ${table}　读的是 Iceberg 协议面（GET /v1/${prefix}/namespaces/{ns}/tables/${table}），看到的 metadata 就是 Iceberg 客户端读到的那一份。`"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'browse', query: { catalog: prefix, database } })">
          <el-icon><Back /></el-icon>
          <span class="pc-btn-text">返回列表</span>
        </el-button>
        <!-- 权限页是全 catalog 一张清单（多级 namespace 用点号显示），因此这个入口只负责
             带上 catalog——不按当前表过滤：授予/撤销总是要看到周围几条才判断得清 -->
        <el-button size="small" @click="$router.push({ name: 'permissions', query: { catalog: prefix } })">
          <el-icon><Lock /></el-icon>
          <span class="pc-btn-text">权限</span>
        </el-button>
        <el-button size="small" :loading="overview.loading.value" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <!-- 删除 / 注销留在浏览页：这里没有「列表上下文」，误触的代价比那边高 -->
      </template>
    </PageHeader>

    <!-- 格式还没确认（深链接进来、catalog 列表在路上）：先给加载态，不发任何请求 -->
    <ResourceState v-if="icebergCatalog === null" :loading="overview.loading.value" :error="overview.error.value" />

    <!-- 深链接敲到 Paimon catalog 上：整页建立在 namespaces/** 上，对 Paimon catalog 注定被拒 -->
    <PanelCard v-else-if="!icebergCatalog">
      <el-alert type="info" :closable="false" show-icon>
        <template #title>catalog「{{ prefix }}」是 Paimon 协议面，本页不适用</template>
        <div>
          本页读的是 Iceberg 协议面的表端点
          （<code class="pc-mono">/v1/{prefix}/namespaces/{ns}/tables/{table}</code>），
          而 Paimon catalog 走 <code class="pc-mono">databases/**</code>（同一个
          <code class="pc-mono">/v1/{prefix}</code> 基址）。
          Paimon 表的详情（快照 / 标签 / 分支 / 分区 / 数据访问）在
          <b>Paimon 版表详情页</b>里看。
        </div>
      </el-alert>
      <div class="pc-tools-row">
        <el-button size="small" @click="$router.push({ name: 'table', params: { prefix, database, table } })">
          <el-icon><Document /></el-icon>
          <span class="pc-btn-text">打开 Paimon 表详情</span>
        </el-button>
      </div>
    </PanelCard>

    <PanelCard v-else flush>
      <el-tabs v-model="tab" class="pc-tabs" @tab-change="onTabChange">
        <el-tab-pane label="详细信息" name="details" />
        <el-tab-pane label="数据预览" name="preview" />
        <el-tab-pane label="快照" name="snapshots" />
        <el-tab-pane label="文件列表" name="files" />
        <el-tab-pane label="存储优化" name="optimization" />
      </el-tabs>

      <div class="pc-tab-body">
        <ResourceState :loading="overview.loading.value" :error="overview.error.value" />

        <template v-if="metadata">
          <!------------------------------ 详细信息 -------------------------------->
          <template v-if="tab === 'details'">
            <!-- 四张卡片来自当前快照的 summary：还没有提交时是「无快照」，
                 不是 0——那两句话在排障时是两件事 -->
            <div v-if="currentSnapshot" class="pc-stats pc-stats-tight">
              <div class="pc-stat">
                <div class="pc-stat-label">记录数（当前快照）</div>
                <div class="pc-stat-value">{{ formatNumber(summaryNumber(currentSnapshot, 'total-records')) }}</div>
                <div class="pc-stat-foot">快照提交时统计的累计值</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件数</div>
                <div class="pc-stat-value">{{ formatNumber(summaryNumber(currentSnapshot, 'total-data-files')) }}</div>
                <div class="pc-stat-foot">当前快照引用的数据文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件大小</div>
                <div class="pc-stat-value">{{ formatBytes(summaryNumber(currentSnapshot, 'total-files-size')) }}</div>
                <div class="pc-stat-foot">不含元数据与清单文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">元数据更新</div>
                <div class="pc-stat-value pc-stat-value-sm">
                  {{ metadata['last-updated-ms'] ? relativeTime(metadata['last-updated-ms']) : '—' }}
                </div>
                <div class="pc-stat-foot">format-version：{{ metadata['format-version'] ?? '—' }}</div>
              </div>
            </div>
            <el-alert v-else type="info" :closable="false" show-icon class="pc-hint">
              <template #title>还没有任何提交（current-snapshot-id = -1）</template>
              <div>
                建表后还没写入过数据时，Iceberg 用 <code class="pc-mono">-1</code> 表示「无快照」，
                不是快照 ID。写入后这里的统计与「快照」页签会有内容。
              </div>
            </el-alert>

            <div class="pc-detail-grid">
              <dl class="pc-kv">
                <dt>表 UUID</dt>
                <dd><CopyText :text="metadata['table-uuid']" /></dd>
                <dt>location</dt>
                <dd><CopyText :text="metadata.location" /></dd>
                <dt>metadata-location</dt>
                <dd><CopyText :text="metadataLocation" /></dd>
                <dt>format-version</dt>
                <dd class="pc-mono">{{ metadata['format-version'] ?? '—' }}</dd>
                <dt>当前 schema</dt>
                <dd class="pc-mono">schema-id {{ metadata['current-schema-id'] ?? '—' }}</dd>
                <dt>当前分区 spec</dt>
                <dd class="pc-mono">spec-id {{ metadata['default-spec-id'] ?? '—' }}</dd>
                <dt>当前快照</dt>
                <dd class="pc-mono">{{ currentSnapshotId > 0 ? currentSnapshotId : '（无）' }}</dd>
                <dt>last-sequence-number</dt>
                <dd class="pc-mono">{{ metadata['last-sequence-number'] ?? '—' }}</dd>
              </dl>
              <JsonBlock
                :value="metadata.properties || {}"
                label="表属性（properties）"
                empty-text="没有表属性"
                max-height="300px"
              />
            </div>

            <SectionTitle title="字段（当前 schema）">
              <template #tip>
                <TipNote>
                  Iceberg 用**字段 id** 而不是名字追踪字段：改名不改变 id，
                  下游读取与分区引用靠的都是它。「必填」对应 <code>required</code>，
                  写入时不允许为 null。
                </TipNote>
              </template>
            </SectionTitle>
            <el-table :data="fields" size="small">
              <el-table-column prop="id" label="ID" width="64" align="right" />
              <el-table-column prop="name" label="字段名称" min-width="150" />
              <el-table-column label="类型" min-width="170">
                <template #default="{ row }">
                  <span class="pc-mono">{{ typeText(row.type) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="必填" width="70" align="center">
                <template #default="{ row }">{{ row.required ? '是' : '否' }}</template>
              </el-table-column>
              <el-table-column prop="doc" label="描述（doc）" min-width="130">
                <template #default="{ row }">{{ orPlaceholder(row.doc) }}</template>
              </el-table-column>
            </el-table>

            <!-- 分区 spec 是 transform 而不是字段勾选：bucket[16] / day 这些写法本身就是分区定义 -->
            <template v-if="partitionFields.length">
              <SectionTitle title="分区（当前 partition-spec）" />
              <el-table :data="partitionFields" size="small">
                <el-table-column prop="name" label="分区字段名" min-width="160">
                  <template #default="{ row }"><span class="pc-mono">{{ row.name }}</span></template>
                </el-table-column>
                <el-table-column label="transform" min-width="140">
                  <template #default="{ row }"><span class="pc-mono">{{ row.transform }}</span></template>
                </el-table-column>
                <el-table-column prop="source-id" label="源字段 ID" width="110" align="right" />
                <el-table-column prop="field-id" label="field-id" width="100" align="right" />
              </el-table>
            </template>
          </template>

          <!-------------------------------- 快照 ---------------------------------->
          <!-- refs（标签 / 分支）与快照都来自这一份 metadata：Iceberg 协议面没有
               「按版本查快照」的独立端点，Paimon 面那个「输入版本号查单个」的表单在这里没有对应物 -->
          <template v-else-if="tab === 'snapshots'">
            <div class="pc-tools-row">
              <span class="pc-dim">
                快照列表来自 <code class="pc-mono">metadata.snapshots</code>；
                每个快照的 summary 是提交时写入的统计（记录数、文件数、大小）。
              </span>
            </div>

            <el-table v-loading="overview.loading.value" :data="snapshotRows" size="small"
              empty-text="这张表还没有快照（还没有写入过数据）">
              <el-table-column label="快照 ID" min-width="150">
                <template #default="{ row }"><span class="pc-mono">{{ row['snapshot-id'] }}</span></template>
              </el-table-column>
              <el-table-column label="提交时间" width="170">
                <template #default="{ row }"><TimeText :millis="row['timestamp-ms']" /></template>
              </el-table-column>
              <el-table-column label="操作" width="110" fixed="right">
                <template #default="{ row }">
                  <el-tag size="small" effect="plain">{{ row.summary?.operation || '—' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="父快照" width="150">
                <template #default="{ row }">
                  <span class="pc-mono">{{ row['parent-snapshot-id'] ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="新增记录" width="100" align="right">
                <template #default="{ row }">{{ formatNumber(summaryNumber(row, 'added-records')) }}</template>
              </el-table-column>
              <el-table-column label="删除记录" width="100" align="right">
                <template #default="{ row }">{{ formatNumber(summaryNumber(row, 'deleted-records')) }}</template>
              </el-table-column>
              <el-table-column label="" width="70" align="right">
                <template #default="{ row }">
                  <IconAction icon="View" label="详情" @click="showSnapshot(row)" />
                </template>
              </el-table-column>
            </el-table>

            <SectionTitle title="refs（标签与分支）">
              <template #tip>
                <TipNote>
                  Iceberg 的标签与分支都是「给快照起名字」（ref）：tag 固定不动，
                  branch 可以继续提交。它们在 metadata 里是一张表，
                  不像 Paimon 面那样拆成两个页签。新建与管理要用 Iceberg 客户端
                  （协议面的更新提交 `POST .../tables/{table}` 不在控制台能力内，见 §7 说明）。
                </TipNote>
              </template>
            </SectionTitle>
            <el-table :data="refRows" size="small" empty-text="没有标签或分支（main 分支不在此列表中）">
              <el-table-column label="名称" min-width="180">
                <template #default="{ row }"><span class="pc-mono">{{ row.name }}</span></template>
              </el-table-column>
              <el-table-column label="类型" width="110">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.type === 'tag' ? 'warning' : 'primary'" effect="plain">
                    {{ row.type }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="指向快照" width="180">
                <template #default="{ row }"><span class="pc-mono">{{ row['snapshot-id'] }}</span></template>
              </el-table-column>
            </el-table>
          </template>

          <!------------------------------ 文件列表 -------------------------------->
          <template v-else-if="tab === 'files'">
            <!-- 表不在 warehouse 之下（登记的外部表最常见）：catalog 级端点以 warehouse 为根，
                 出不了这个范围——这时给出去处，而不是一个注定 404 的列表 -->
            <el-alert v-if="!filesRootComputed" type="warning" :closable="false" show-icon class="pc-hint">
              <template #title>这张表的 location 不在 catalog 仓库之内，这里列不了</template>
              <div>
                文件浏览以 catalog 的仓库（<code class="pc-mono">{{ warehouse || '未取得' }}</code>）为根，
                而这张表的 <code class="pc-mono">location</code> 是
                <code class="pc-mono">{{ metadata.location }}</code>。
                登记进来的外部表经常这样。要看它的文件，去 catalog 详情页的「文件列表」
                （能列到仓库根能及的任意目录）。
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
                    <a class="pc-link" @click.prevent="openFilesDirectory(filesRootComputed)">表目录</a>
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

              <el-alert
                v-if="fileListing?.truncated"
                type="warning"
                :closable="false"
                show-icon
                class="pc-hint"
              >
                <template #title>只显示了前 {{ fileListing.maxEntries }} 条（共 {{ fileListing.total }} 条）</template>
                <div>
                  排序是目录优先、名称升序，因此被截掉的是尾部的文件。下钻到更具体的子目录，
                  或调大 <span class="pc-mono">lakehouse.rest.file-browser.max-entries</span>。
                </div>
              </el-alert>

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
                  这里只看这张表自己目录下的文件，出不了表目录。要浏览整个仓库，
                  去 catalog 详情页的「文件列表」。当前目录相对表目录为
                  <code>{{ relativeFilesPath || '（表目录）' }}</code>。
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
                <el-table-column label="操作" width="90" align="center" fixed="right">
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

          <!------------------------------- 数据预览 -------------------------------->
          <!-- 与 Paimon 表详情页**同一个组件**：只传 tableId 与全量列名，格式差异
               全在服务端（`TablePreviewFactory` 按 catalog 格式分派）。
               本页比那一页多一步：按名字换内部 id——这里的数据来自 `loadTable`，
               而 `LoadTableResult` 是 Iceberg 规格的形状，里面没有本服务端的 id -->
          <template v-else-if="tab === 'preview'">
            <ResourceState :error="previewLookup.error">
              <TablePreviewPanel
                v-if="previewTableId"
                :key="previewTableId"
                :table-id="previewTableId"
                :columns="previewColumns"
              />
              <div v-else class="pc-dim">正在解析这张表的内部 id…</div>
            </ResourceState>
          </template>

          <!------------------------------- 存储优化 -------------------------------->
          <!-- 与 Paimon 表详情页同一个组件：三项动作的名字与执行流程两种格式一致，
               差别只在参数键与指标口径，而那两样都在 utils/optimization.js 与执行侧，
               组件本身只按 format 取定义。format 显式传进来，首屏就不必等策略响应 -->
          <TableOptimizationPanel
            v-else
            ref="optimizationPanel"
            :catalog="prefix"
            :database="database"
            :table-name="table"
            format="ICEBERG"
          />
        </template>
      </div>
    </PanelCard>

    <!-- 快照详情：整行 + summary 原文（统计键都是提交时写入的，照原文展示比挑着翻靠谱） -->
    <el-drawer v-model="snapshotDrawer.open" :title="snapshotDrawer.title" size="640px">
      <JsonBlock :value="snapshotDrawer.payload" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import CopyText from '@/components/CopyText.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TablePager from '@/components/TablePager.vue'
import TableOptimizationPanel from '@/components/TableOptimizationPanel.vue'
import TablePreviewPanel from '@/components/TablePreviewPanel.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { describeError } from '@/api/client.js'
import { consoleApi } from '@/api/console.js'
import { icebergApi, namespaceLevels } from '@/api/iceberg.js'
import { managementApi } from '@/api/management.js'
import { previewApi } from '@/api/preview.js'
import { notifyError, useRemotePaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { isIcebergCatalog } from '@/utils/catalogFormat.js'
import {
  formatBytes,
  formatNumber,
  orPlaceholder,
  relativeTime,
} from '@/utils/format.js'

/**
 * Iceberg 表详情。与 Paimon 版 `TableView` 平行的一页——不是它的分支：
 * Paimon 那一页的七个页签全部挂在 databases/** 那套表端点上，
 * 而 Iceberg 的详情数据几乎全部来自**一次** `loadTable`（`LoadTableResult` 里
 * 的 metadata 是整份 `TableMetadata` JSON：schema、分区 spec、快照、refs、属性都在），
 * 按需加载的单位因此完全不同。
 *
 * <p>四个页签的数据来源：
 *
 * - **详细信息 / 快照**：`metadata`（current snapshot 的 summary 给统计卡，
 *   `snapshots[]` 给快照列表，`refs` 给标签与分支——Iceberg 把两者合成一张表）。
 * - **文件列表**：Iceberg 协议面没有「按表名列文件」的扩展端点（Paimon 面那条
 *   `/api/console/v1/tables/{tableId}/files` 要的是 Paimon 内部 id，Iceberg 的
 *   LoadTableResult 里没有它），因此走 **catalog 级**的文件端点
 *   （根 = warehouse），初始路径由 `metadata.location` 减去 warehouse 前缀算出。
 *   location 不在 warehouse 下（登记的外部表最常见）时给出去处而不是 404。
 * - **存储优化**：复用 Paimon 表详情页的同一个组件（`TableOptimizationPanel`），
 *   `format="ICEBERG"` 让它取 Iceberg 那套参数集。策略、立即执行与历史都走
 *   `/api/management/v1/optimization/tables/{catalog}/{database}/{table}/**`——
 *   服务端按 `table_type` 分派参数与执行语句（`rewrite_data_files` /
 *   `expire_snapshots` / `remove_orphan_files`），任务信息表两种格式共用。
 */

const route = useRoute()

const prefix = computed(() => route.params.prefix)
const database = computed(() => route.params.database)
const table = computed(() => route.params.table)

/**
 * 命名空间层级：URL 上是点号拼接的显示名，按点号切。
 *
 * <p>与浏览页的深链接同一口径（`namespaceLevels` 的注释）：层级名本身含点时
 * 这里会切错——浏览页在列表到手后会换成准确的层级数组，本页没有那份列表，
 * 只能按点号兜底。多级且层级名含点的 namespace 在实践里极少，错了会得到一个
 * 明确的 404，而不是静默的数据错乱。
 */
const levels = computed(() => namespaceLevels(database.value))

/**
 * catalog 格式与 Paimon 版同一套护栏：深链接进来时列表可能还没到，
 * 先 `ensureCatalogs()` 再判断，不猜。
 */
const icebergCatalog = computed(() => {
  if (!session.state.catalogsLoaded) return null
  return isIcebergCatalog(session.catalogByName(prefix.value))
})

async function ensureCatalogs() {
  if (session.state.catalogsLoaded) return
  await session.loadCatalogs().catch(() => null)
}

const tab = ref('details')

/**
 * 概览 = 一次 loadTable。格式没确认前不发请求（见 icebergCatalog）；
 * catalog 不是 Iceberg 时返回 null，模板里给说明。
 */
const overview = useResource(async () => {
  await ensureCatalogs()
  if (!icebergCatalog.value) return null
  return icebergApi.loadTable(prefix.value, levels.value, table.value)
})

const metadata = computed(() => overview.data.value?.metadata || null)
const metadataLocation = computed(() => overview.data.value?.['metadata-location'] || '—')

/** `current-snapshot-id = -1` 是「无快照」哨兵，不是 ID——所有涉及它的显示都要过这一道。 */
const currentSnapshotId = computed(() => metadata.value?.['current-snapshot-id'] ?? -1)
const currentSnapshot = computed(() => {
  if (!(currentSnapshotId.value > 0)) return null
  return (metadata.value?.snapshots || []).find((s) => s['snapshot-id'] === currentSnapshotId.value) || null
})

const fields = computed(() => {
  const md = metadata.value
  if (!md) return []
  const schema = (md.schemas || []).find((s) => s['schema-id'] === md['current-schema-id'])
  return schema?.fields || []
})

const partitionFields = computed(() => {
  const md = metadata.value
  if (!md) return []
  const spec = (md['partition-specs'] || []).find((s) => s['spec-id'] === md['default-spec-id'])
  return spec?.fields || []
})

/**
 * refs 在 TableMetadata JSON 里是 **map**（名字 → ref），不是数组；
 * 这里两种形状都接受，免得服务端序列化方式的细节漏进模板。
 * main 分支通常不写进 refs，列表里没有它是正常的（空态文案里说明）。
 */
const refRows = computed(() => {
  const refs = metadata.value?.refs
  if (!refs) return []
  if (Array.isArray(refs)) return refs
  return Object.entries(refs).map(([name, ref]) => ({ name, ...ref }))
})

/** 快照按时间倒序：看快照的人几乎总在找最近一次提交。 */
const snapshotRows = computed(() => [...(metadata.value?.snapshots || [])]
  .sort((a, b) => (b['timestamp-ms'] || 0) - (a['timestamp-ms'] || 0)))

/** summary 的统计值是字符串（Iceberg 的序列化约定），统一转成数字再交给格式化函数。 */
function summaryNumber(snapshot, key) {
  const value = snapshot?.summary?.[key]
  const parsed = value === undefined || value === null ? NaN : Number(value)
  return Number.isNaN(parsed) ? null : parsed
}

function typeText(type) {
  if (type === null || type === undefined) return '—'
  return typeof type === 'string' ? type : JSON.stringify(type)
}

// ------------------------------------------------------------------ 快照详情

const snapshotDrawer = reactive({ open: false, title: '', payload: null })

function showSnapshot(row) {
  snapshotDrawer.title = `快照：${row['snapshot-id']}`
  snapshotDrawer.payload = row
  snapshotDrawer.open = true
}

// ------------------------------------------------------------------ 文件列表

/**
 * warehouse 与表目录的相对路径。
 *
 * <p>warehouse 只在文件页签第一次打开时才取（管理 API 的一次 GET）：
 * 其余三个页签用不到它，打开页面就发这个请求是把代价付给了不需要它的人。
 * 取法与 `CatalogDetailView` 同一约定：`storageConfigInfo.allowedLocations[0]`，
 * file 存储退回 `properties.warehouse`。
 */
const catalogInfo = useResource(() => managementApi.getCatalog(prefix.value), { immediate: false })
const warehouse = computed(() => {
  const info = catalogInfo.data.value?.storageConfigInfo
  return info?.allowedLocations?.[0] || catalogInfo.data.value?.properties?.warehouse || ''
})

/**
 * 表目录相对仓库根的路径；location 不在 warehouse 下时是 null。
 *
 * <p>两端都先去掉尾斜杠再比：warehouse 配置里带不带尾斜杠、
 * Iceberg 写 location 时带不带，两边的归一化差异不该让用户看见。
 */
const filesRoot = computed(() => {
  const location = metadata.value?.location
  if (!location || !warehouse.value) return null
  const root = warehouse.value.replace(/\/+$/, '')
  const target = location.replace(/\/+$/, '')
  if (target === root) return ''
  if (target.startsWith(`${root}/`)) return target.slice(root.length + 1)
  return null
})

/** filesRoot 还没算出来（catalog 信息没到）时模板需要一个非空占位，避免 v-else 分支闪烁。 */
const filesRootComputed = computed(() => filesRoot.value)

const filePath = ref('') // 相对仓库根
const filePrefixInput = ref('')
const filePrefix = ref('')

/** 最近一次列表响应：`maxEntries` / `truncated` / `total` 不是行级字段，单独留一份。 */
const fileListing = ref(null)
/** 是否成功列过至少一次目录：空态提示只在这个前提下出现，否则和「还没加载」分不开。 */
const fileLoaded = ref(false)

async function fetchFiles({ page, pageSize }) {
  if (!metadata.value) return { items: [], total: 0 }
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

/** 文件页签第一次打开时：loader 内部自己保证 catalog 信息先到（见 files 的注释）。 */
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
 * 页面就永远停在空白。Paimon 版表详情页（`TableView`）踩过同一个坑，这里照搬它的解法。
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

// ------------------------------------------------------------------ 数据预览

/** 这张表的内部 id：本页只有名字，预览端点要 id（换一次，换到就存着）。 */
const previewTableId = ref(null)

const previewLookup = reactive({ loading: false, error: '' })

/** 面板里列多选框的选项来源；`type` 只用于表头展示，取值口径与 Paimon 页一致。 */
const previewColumns = computed(() => {
  const fields = metadata.value?.schema?.fields || []
  return fields.map((field) => ({
    name: field.name,
    type: typeof field.type === 'string' ? field.type : JSON.stringify(field.type),
  }))
})

/**
 * 切到数据预览时先做一次「名字 → 内部 id」，换到才挂面板。
 *
 * <p>为什么不把 id 放进路由或首屏请求里：本页的数据全部来自一次 `loadTable`，
 * 拿 id 只是为了调预览端点；把它塞进 URL 会让"已存在的深链接"全部失效。
 * 失败时（没有 SELECT 权限、表在库里找不到）错误就地显示，面板不挂——
 * 那样用户看到的是"为什么不能看"，而不是一个空表格。
 */
async function loadPreview() {
  if (previewTableId.value || previewLookup.loading) return
  previewLookup.loading = true
  previewLookup.error = ''
  try {
    const payload = await previewApi.lookupTable(prefix.value, database.value, table.value)
    previewTableId.value = payload.id
  } catch (error) {
    previewLookup.error = describeError(error)
  } finally {
    previewLookup.loading = false
  }
}

function onTabChange(name) {
  if (loadedTabs.has(name)) return
  loadedTabs.add(name)
  if (name === 'files') loadFilesIfFirst()
  // 切到存储优化时才拉策略与历史：打开一张表的成本不该因为多了一个页签而变高
  if (name === 'optimization') loadOptimization()
  if (name === 'preview') loadPreview()
}

function reloadAll() {
  overview.load()
  loadedTabs.clear()
  // 刷新时把 id 与面板一起作废：数据可能已经换了快照，重新换一次 id 最省事
  previewTableId.value = null
  previewLookup.error = ''
  onTabChange(tab.value)
  if (tab.value === 'files') loadFiles()
}

watch([prefix, database, table], () => {
  loadedTabs.clear()
  // 换表即作废文件浏览的位置与过滤：旧路径在新表目录里通常不存在
  filePath.value = ''
  filePrefix.value = ''
  filePrefixInput.value = ''
  fileListing.value = null
  fileLoaded.value = false
  // 换表也要作废内部 id：拿旧表的 id 去预览新表会读到别人的数据
  previewTableId.value = null
  previewLookup.error = ''
  overview.load()
  if (tab.value !== 'details') {
    loadedTabs.add(tab.value)
    onTabChange(tab.value)
  }
  // 换表后策略与历史必须重拉。面板按 props 自己拼请求，不依赖 overview，
  // 因此可以立刻发；文件页签则要等新的 metadata（它按 location 算根目录），
  // 那条路径由 overview 回来后用户点刷新或切页签触发。
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

.pc-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

@media (max-width: 1080px) {
  .pc-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
