<template>
  <div class="pc-page">
    <PageHeader
      :title="`Catalog：${catalogName}`"
      :subtitle="`${catalogName}　这里的元数据与引擎客户端读到的是同一份；文件列表看的是仓库里的物理目录，用来核对「库里说的」与「盘上写的」是否一致。`"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'catalogs' })">
          <el-icon><Back /></el-icon>
          <span class="pc-btn-text">返回列表</span>
        </el-button>
        <el-button size="small" @click="$router.push({ name: 'browse', query: { catalog: catalogName } })">
          <el-icon><Files /></el-icon>
          <span class="pc-btn-text">浏览库与对象</span>
        </el-button>
        <el-button size="small" :loading="overview.loading.value" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <ResourceState :loading="overview.loading.value" :error="overview.error.value" />

    <template v-if="catalog0">
      <PanelCard flush>
        <el-tabs v-model="tab" class="pc-tabs" @tab-change="onTabChange">
          <el-tab-pane name="overview">
            <template #label>
              概览
              <TipNote>
                这一页只读。属性与存储配置的修改走管理 API
                （<code>PUT /api/management/v1/catalogs/{{ catalogName }}</code>）：
                「配置」页签能改其中的 <code>table-default.*</code>（此后新建表的默认属性），
                其余属性与控制台的编辑弹窗在「Catalogs」页。
              </TipNote>
            </template>
          </el-tab-pane>
          <el-tab-pane name="storage">
            <template #label>
              存储
              <TipNote>
                只读的一页：回答「哪些表该做 compaction」。指标口径取自 AWS 的
                <em>Monitoring Iceberg workloads</em>，但数据来源是本服务端已有的读端点
                ——Paimon 读每张表的快照，Iceberg 读表元数据里当前快照的 <code>summary</code>，
                不依赖只有引擎才能查的元数据表（<code>t.files</code> 那类）。
                因此两处口径要留意：平均文件大小是<b>加权</b>的（总大小 ÷ 总文件数，不是各表平均再平均），
                小文件是<b>表级近似</b>（按表的平均文件大小判，看不出「大表里混着一批小文件」）。
              </TipNote>
            </template>
          </el-tab-pane>
          <el-tab-pane name="files">
            <template #label>
              文件列表
              <TipNote>
                这一页只读：仓库里的对象由引擎与服务端自己管（服务端写 schema 与快照，
                <code>purge-on-drop</code> 决定删表时清不清目录），
                从这里手工改文件会让「库里说的」与「盘上写的」对不上。
                当前目录相对仓库根为 <code>{{ filePath || '（仓库根）' }}</code>。
              </TipNote>
            </template>
          </el-tab-pane>
          <!--
            两种格式的「配置」页签刻意做成同一套设计：
            - 都能编辑 `table-default.*`（此后新建表的默认属性；两格式同名同义，
              都由服务端在建表时合并，表级显式声明的同名属性优先）；
            - Iceberg 额外只读展示客户端连上来实际读到的 `/v1/config`
              （defaults / overrides）——那是**连接参数**，与表属性是两件事。
            catalog 自身的属性（warehouse 等）两种格式都在「Catalogs」页的编辑弹窗里改。
          -->
          <el-tab-pane name="config">
            <template #label>
              配置
              <TipNote>
                两种格式都编辑 <code>table-default.*</code>：此后在这个 catalog 下新建的表
                默认带上的表属性，只在建表时合并。Iceberg 下面另附一份只读的
                <code>/v1/config</code>（客户端连接时实际读到的 <code>defaults</code> /
                <code>overrides</code>）。catalog 自身的属性（<code>warehouse</code> 等）
                都在「Catalogs」页的编辑弹窗里改。
              </TipNote>
            </template>
          </el-tab-pane>
        </el-tabs>

        <div class="pc-tab-body">
          <!------------------------------ 概览 ------------------------------------>
          <template v-if="tab === 'overview'">
            <div class="pc-detail-grid">
              <dl class="pc-kv">
                <dt>名称（prefix）</dt>
                <dd>{{ catalog0.name }}</dd>
                <dt>类型</dt>
                <dd>{{ catalog0.type === 'EXTERNAL' ? 'EXTERNAL（外部同步，只读）' : 'INTERNAL（本服务托管元数据）' }}</dd>
                <dt>表格式</dt>
                <dd>
                  <el-tag size="small" effect="plain" :type="catalogFormatTagType(catalog0)">
                    {{ catalogFormatLabel(catalog0) }}
                  </el-tag>
                  <span class="pc-dim pc-format-note">创建后不可更改</span>
                </dd>
                <dt>存储类型</dt>
                <dd>{{ catalog0.storageConfigInfo?.storageType || '—' }}</dd>
                <dt>仓库位置</dt>
                <dd><CopyText :text="warehouse" /></dd>
                <dt>属性</dt>
                <dd class="pc-mono pc-break">{{ inlineMap(catalog0.properties) }}</dd>
                <dt>版本</dt>
                <dd class="pc-mono">{{ catalog0.entityVersion ?? '—' }}</dd>
                <dt>创建</dt>
                <dd><TimeText :millis="catalog0.createTimestamp" /></dd>
                <dt>更新</dt>
                <dd><TimeText :millis="catalog0.lastUpdateTimestamp" /></dd>
              </dl>
              <div class="pc-side">
                <JsonBlock
                  :value="catalog0.storageConfigInfo || {}"
                  label="存储配置（storageConfigInfo）"
                  empty-text="没有存储配置"
                  max-height="260px"
                />
                <JsonBlock
                  :value="catalog0.properties || {}"
                  label="Catalog 属性（properties）"
                  empty-text="没有属性"
                  max-height="260px"
                />
              </div>
            </div>

            <!--
              「协议与能力」卡片。它的作用不是展示配置，而是回答排障时的第一个问题：
              **这个 prefix 到底服务哪一套协议、客户端能调哪些端点。**
              Iceberg 侧的答案就在 `GET /v1/config` 的 `endpoints` 里——那份清单是
              服务端下发给客户端的原文，客户端拿它做等值比较，因此它是这一页唯一
              可信的来源；前端不另抄一份端点表（抄了就会与实现漂移）。
            -->
            <SectionTitle title="协议与能力">
              <template #tip>
                <TipNote>
                  这一块读的是 <code>GET /v1/config?warehouse={{ catalogName }}</code>，
                  也就是客户端连接时第一个调的发现端点。这里显示的是服务端<strong>实际下发</strong>的内容，
                  不是文档里抄来的清单。
                </TipNote>
              </template>
            </SectionTitle>

            <div class="pc-detail-grid">
              <dl class="pc-kv">
                <dt>协议面</dt>
                <dd>
                  <el-tag size="small" effect="plain" :type="catalogFormatTagType(catalog0)">
                    {{ catalogFormatLabel(catalog0) }}
                  </el-tag>
                  <span class="pc-dim pc-format-note">{{ protocolName }}</span>
                </dd>
                <dt>客户端基址</dt>
                <dd><CopyText :text="clientBase" /></dd>
                <!-- 多级 namespace 的分隔符只有 Iceberg 侧存在：Paimon 的库名是一级字符串 -->
                <template v-if="isIceberg">
                  <dt>namespace 分隔符</dt>
                  <dd class="pc-break">
                    <span class="pc-mono">0x1F</span>（规格默认；解析时也接受
                    <span class="pc-mono">.</span>，库里按点号拼接存储）
                  </dd>
                </template>
                <dt>凭据下发</dt>
                <dd class="pc-break">{{ credentialNote }}</dd>
                <dt>能力清单</dt>
                <dd>
                  <template v-if="isIceberg">
                    <template v-if="capabilities.error.value">
                      <span class="pc-dim">读不到（{{ capabilities.error.value }}）</span>
                    </template>
                    <template v-else-if="capabilities.loading.value">
                      <span class="pc-dim">读取中…</span>
                    </template>
                    <template v-else>
                      已声明 <strong>{{ declaredEndpoints.length }}</strong> 个端点
                      <span class="pc-dim">（规格里一共 35 个，其余是一期不做的）</span>
                    </template>
                  </template>
                  <template v-else>
                    <span class="pc-dim">
                      Paimon 协议面不下发端点清单（规格里没有这个字段），
                      客户端按规格调它需要的路径。本服务端实现了 60 个端点。
                    </span>
                  </template>
                </dd>
                <dt>格式说明</dt>
                <dd class="pc-break">{{ catalogFormatNote(catalog0) }}</dd>
              </dl>
              <div class="pc-side">
                <template v-if="isIceberg">
                  <div class="pc-endpoints">
                    <div class="pc-endpoints-head">
                      已声明的端点（{{ declaredEndpoints.length }}）
                      <span class="pc-dim">客户端会拿这份清单判断某项能力能不能用</span>
                    </div>
                    <ul class="pc-endpoints-list pc-scroll">
                      <li v-for="endpoint in declaredEndpoints" :key="endpoint" class="pc-mono">{{ endpoint }}</li>
                    </ul>
                    <div class="pc-endpoints-foot">
                      <div class="pc-foot-title">
                        不声明支持
                        <span class="pc-dim">客户端在能力发现阶段就知道不该调</span>
                      </div>
                      <div class="pc-tag-grid">
                        <span v-for="item in NOT_DECLARED" :key="item" class="pc-tag">{{ item }}</span>
                      </div>
                      <div class="pc-dim pc-foot-note">
                        这 {{ NOT_DECLARED_TOTAL }} 个操作<strong>不写进</strong>清单，因此不会出现
                        「跑到一半才 404」。逐条与理由见
                        <code>docs/iceberg-rest-catalog-design.md</code> §6。
                      </div>
                    </div>
                  </div>
                </template>
                <JsonBlock
                  v-else
                  :value="configDefaults"
                  label="客户端连接时读到的 defaults"
                  empty-text="没有下发的默认值"
                  max-height="300px"
                />
              </div>
            </div>
          </template>

          <!------------------------------ 存储 -------------------------------------->
          <template v-else-if="tab === 'storage'">
            <CatalogStoragePanel
              ref="storagePanel"
              :catalog-name="catalogName"
              :format="isIceberg ? 'ICEBERG' : 'PAIMON'"
            />
          </template>

          <!------------------------------ 文件列表 -------------------------------->
          <template v-else-if="tab === 'files'">
            <div class="pc-tools-row">
              <el-breadcrumb separator="/" class="pc-file-crumbs">
                <el-breadcrumb-item>
                  <a class="pc-link" @click.prevent="openDirectory('')">仓库根</a>
                </el-breadcrumb-item>
                <el-breadcrumb-item v-for="crumb in crumbs" :key="crumb.path">
                  <a class="pc-link" @click.prevent="openDirectory(crumb.path)">{{ crumb.name }}</a>
                </el-breadcrumb-item>
              </el-breadcrumb>
              <span class="pc-spacer" />
              <el-button size="small" :disabled="!parentPath" @click="openDirectory(parentPath)">
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
                排序是目录优先、名称升序，因此被截掉的是尾部的文件。需要看全部的话，
                下钻到更具体的子目录，或调大 <span class="pc-mono">lakehouse.rest.file-browser.max-entries</span>。
              </div>
            </el-alert>

            <ResourceState
              :loading="filesLoading"
              :error="filesError"
              :empty="fileLoaded && !totalEntries"
              :empty-text="filePrefix ? `没有以「${filePrefix}」开头的条目` : '这个目录里没有文件'"
            />

            <div class="pc-filter-row">
              <el-input
                v-model="filePrefixInput"
                size="small"
                class="pc-prefix-input"
                placeholder="文件名前缀（回车过滤）"
                clearable
                @keyup.enter="applyPrefix"
                @clear="applyPrefix"
              />
              <el-button size="small" @click="applyPrefix">查询</el-button>
              <span class="pc-spacer" />
              <span class="pc-dim">
                共 {{ totalEntries }} 条<span v-if="fileListing?.truncated">（已达上限
                {{ fileListing.maxEntries }}，用前缀过滤缩小范围）</span>
              </span>
            </div>

            <el-table v-if="fileRows.length || filesLoading" :data="fileRows" size="small">
              <el-table-column label="名称" min-width="280">
                <template #default="{ row }">
                  <!-- 目录名可点，点进去看下一层；文件没有可点的动作（这一页只读） -->
                  <a v-if="row.directory" class="pc-link pc-file-name" @click.prevent="openDirectory(row.path)">
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
                <template #default="{ row }">
                  {{ row.directory ? '目录' : '文件' }}
                </template>
              </el-table-column>
              <el-table-column label="大小" width="110" align="right">
                <template #default="{ row }">
                  <!-- 目录没有长度语义：显示占位符而不是 0，免得被读成「空目录」 -->
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
                  <!-- 只有文件可下载：目录用列表下钻，仓库根/子目录没有「下载整个目录」 -->
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
              :total="totalEntries"
              :page="filePage"
              :page-size="filePageSize"
              @page-change="onFilePageChange"
              @size-change="onFileSizeChange"
            />
          </template>

          <!-------------------------------- 配置 ---------------------------------->
          <template v-else-if="tab === 'config'">
            <!-- 两种格式共用一套：table-default.* 的编辑 -->
            <SectionTitle :title="`${isIceberg ? 'Iceberg' : 'Paimon'} 表默认参数（table-default.*）`">
              <template #tip>
                <TipNote v-if="isIceberg">
                  这里配的属性会在这个 catalog 下<strong>新建 Iceberg 表</strong>时自动带上，前缀在写入表属性时去掉：
                  例如配 <code>write.format.default=parquet</code>，新表就自带
                  <code>write.format.default=parquet</code>，不必每次建表都声明一遍。
                  <br /><br />
                  三条边界：<br />
                  1. <strong>表级参数优先</strong>——建表请求里显式给了同名属性时以表的为准；<br />
                  2. <strong>对本 catalog 的所有表生效</strong>——Iceberg catalog 只建 Iceberg 表，
                  不像 Paimon 侧还要区分表格式；<br />
                  3. <strong>只在建表时合并</strong>——已建成的表不受影响，改它的属性是
                  <code>ALTER TABLE</code> 的事。
                  <br /><br />
                  <code>type</code> 不接受默认：它由建表请求决定（对 Iceberg 也不是合法表属性）。
                  <br /><br />
                  保存只替换 <code>table-default.*</code>，其余 catalog 属性<strong>原样回填</strong>
                  （管理 API 的更新是全量替换），所以这一页不会弄丢别的键；它们的完整内容看「概览」页。
                  保存期间别人改过属性会以版本冲突（409）拒绝——宁可不写，也不静默覆盖。
                </TipNote>
                <TipNote v-else>
                  这里配的属性会在这个 catalog 下<strong>新建 Paimon 表</strong>时自动带上，前缀在写入表属性时去掉：
                  例如配 <code>deletion-vectors.enabled=true</code>，新表就自带
                  <code>deletion-vectors.enabled=true</code>，不必每次建表都声明一遍。
                  <br /><br />
                  三条边界：<br />
                  1. <strong>表级参数优先</strong>——建表请求里显式给了同名属性时以表的为准；<br />
                  2. <strong>只对 Paimon 表生效</strong>——Format / Object / Lance 表的选项由各自的表格式定义，
                  不接受这些 Paimon 表属性；<br />
                  3. <strong>只在建表时合并</strong>——已建成的表不受影响，改它的属性是 alter 的事。
                  <br /><br />
                  表格式（<code>type</code>）不接受默认：它由建表请求决定，配在这里会被忽略。
                  <br /><br />
                  保存只替换 <code>table-default.*</code>，其余 catalog 属性<strong>原样回填</strong>
                  （管理 API 的更新是全量替换），所以这一页不会弄丢别的键；它们的完整内容看「概览」页。
                  保存期间别人改过属性会以版本冲突（409）拒绝——宁可不写，也不静默覆盖。
                </TipNote>
              </template>
            </SectionTitle>

            <div class="pc-defaults">
              <div class="pc-defaults-row pc-defaults-head">
                <span>属性名</span>
                <span>取值</span>
                <span class="pc-defaults-op">操作</span>
              </div>
              <div v-for="(row, index) in defaultRows" :key="index" class="pc-defaults-row">
                <el-input v-model="row.name" size="small" placeholder="如 deletion-vectors.enabled">
                  <template #prepend>table-default.</template>
                </el-input>
                <el-input v-model="row.value" size="small" placeholder="如 true" />
                <el-button size="small" text type="danger" @click="removeDefaultRow(index)">删除</el-button>
              </div>
              <div v-if="!defaultRows.length" class="pc-defaults-empty pc-dim">
                没有默认参数：新表只会带上建表请求里自己声明的属性。
              </div>

              <div class="pc-defaults-foot">
                <el-button size="small" text type="primary" @click="addDefaultRow">
                  <el-icon><Plus /></el-icon>
                  <span class="pc-btn-text">添加一项</span>
                </el-button>
                <span class="pc-spacer" />
                <span class="pc-dim pc-defaults-state">
                  {{ defaultsDirty ? '有未保存的改动' : '与服务端一致' }}
                </span>
                <el-button
                  size="small"
                  type="primary"
                  :loading="defaultsSaving"
                  :disabled="!defaultsDirty"
                  @click="saveDefaults"
                >
                  保存
                </el-button>
              </div>
            </div>

            <!-- Iceberg 侧另附：客户端连接配置（只读）。
                 连接参数与表属性是两件事：前者决定「怎么连」，后者决定「新表带什么」 -->
            <template v-if="isIceberg">
              <SectionTitle title="客户端连接配置（/v1/config）">
                <template #tip>
                  <TipNote>
                    这是客户端连上来时<strong>实际读到</strong>的配置：服务端把
                    <code>warehouse</code>、<code>prefix</code> 与 catalog 自身的属性拼成
                    <code>defaults</code> 下发；<code>overrides</code> 是服务端强制、客户端改不了的项。
                    两者在控制台都只读——<code>warehouse</code> 决定数据写在哪，改它等于换存储位置；
                    其余属性去「Catalogs」页的编辑弹窗改（<code>PUT</code> 全量替换，带
                    <code>entityVersion</code> 做乐观并发）。
                  </TipNote>
                </template>
              </SectionTitle>

              <div class="pc-detail-grid">
                <JsonBlock
                  :value="configDefaults"
                  label="defaults（客户端默认值）"
                  empty-text="没有下发的默认值"
                  max-height="320px"
                />
                <JsonBlock
                  :value="configOverrides"
                  label="overrides（服务端强制项）"
                  empty-text="没有强制项"
                  max-height="320px"
                />
              </div>

              <p class="pc-drawer-note pc-dim">
                这一组是<strong>连接参数</strong>：决定客户端怎么连、连到哪个仓库（改错的表现是「连不上」）；
                上面的 <code>table-default.*</code> 是<strong>表属性</strong>（改错只影响新表）。
                两者都挂在 catalog 上，但走两条不同的下发路径——这里只读，因为它们由
                <code>warehouse</code> 与 catalog 的 <code>defaults</code> / <code>overrides</code>
                拼出来，要改去「Catalogs」页。
              </p>
            </template>
          </template>
        </div>
      </PanelCard>
    </template>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import { consoleApi } from '@/api/console.js'
import { describeError } from '@/api/client.js'
import { catalogApi } from '@/api/catalog.js'
import { managementApi } from '@/api/management.js'
import CatalogStoragePanel from '@/components/CatalogStoragePanel.vue'
import CopyText from '@/components/CopyText.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { notifyError, notifySuccess, onConsoleRefresh, useRemotePaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import {
  catalogFormatLabel,
  catalogFormatNote,
  catalogFormatTagType,
  isIcebergCatalog,
} from '@/utils/catalogFormat.js'
import { formatBytes, inlineMap } from '@/utils/format.js'
import { composeProperties, isTableDefaultKey, tableDefaultsOf } from '@/utils/tableDefaults.js'

const route = useRoute()

/** catalog 的标识就是路径里的 prefix，页面本身不改它（改名等同删除重建）。 */
const catalogName = computed(() => route.params.catalogName || '')

const tab = ref('overview')

/**
 * 「存储」页签的统计。它按「一张表一次请求」取数，比概览重一个数量级，
 * 因此只在页签切过去时由组件自己加载一次；顶栏刷新时若正停在这一页，由下面
 * 的 reloadAll 显式驱动（组件没挂载时 ref 是 null，正好跳过）。
 */
const storagePanel = ref(null)

const overview = useResource(() => managementApi.getCatalog(catalogName.value))
const catalog0 = computed(() => overview.data.value)

/**
 * 仓库位置。
 *
 * <p>与 `CatalogsView.warehouseOf` 同一个约定：管理规格里它落在
 * `storageConfigInfo.allowedLocations[0]`，而 `file` 存储的那一份没有 allowedLocations，
 * 位置这时记在 `properties.warehouse` 上（服务端创建 catalog 时就是这么写的）。
 * 两处取法写在一起，是因为「位置从哪儿读」这件事只有一个答案。
 */
const warehouse = computed(() => {
  const info = catalog0.value?.storageConfigInfo
  return info?.allowedLocations?.[0] || catalog0.value?.properties?.warehouse || '—'
})

/**
 * 表格式：`catalogFormat`（PAIMON / ICEBERG），**创建后不可更改**。
 *
 * <p>它决定这个页面上的三件事：协议面那一栏怎么读、「配置」页签里要不要多一段
 * （`table-default.*` 编辑器两格式都有，Iceberg 另附 `/v1/config` 的只读展示）、
 * 以及「文件列表」以外那些 Paimon 专有的东西要不要显示。
 *
 * <p>值从 `session.state.catalogs` 里读（列表在首屏就取回来了），而不是为了
 * 一个字段再发一次管理 API 请求——见 `session.catalogByName` 的说明。
 */
const isIceberg = computed(() => isIcebergCatalog(catalog0.value))
const protocolName = computed(() => (isIceberg.value ? 'Iceberg REST Catalog' : 'Paimon REST Catalog'))

/** 客户端基址：`/v1/{prefix}`，而 prefix 就是 catalog 名（创建后不可改名）。 */
const clientBase = computed(() => `/v1/${catalogName.value}`)

/**
 * 目录发现端点（`GET /v1/config?warehouse={prefix}`）。
 *
 * <p>为什么要读它而不是把端点清单写在前端：Iceberg 侧的能力清单是服务端
 * **实际下发**给客户端的那一份，客户端拿它做等值比较（少一条就在连接阶段被拒）。
 * 前端再抄一份，抄错了会显示一个「看上去对、实际不生效」的清单——
 * 而这一页的全部价值就是「让排障的人不用去读文档也能知道这台服务端支持什么」。
 *
 * <p>它同时也是唯一一处能看到 `defaults` 的地方：客户端连上来读到的
 * `warehouse` / `prefix` 这些值是服务端拼给它的，与页面上「仓库位置」那一栏
 * 是两件事（前者是连接参数，后者是 catalog 的存储位置）。
 *
 * <p>这个端点**不需要授权**（见服务端 `CatalogAccessRules.isPublic`），
 * 但可能因为服务端未启动/令牌失效而失败，因此错误照常显示在这一栏里，
 * 不让整页挂掉。
 */
const capabilities = useResource(() => catalogApi.config(catalogName.value))
const declaredEndpoints = computed(() => capabilities.data.value?.endpoints || [])
const configDefaults = computed(() => capabilities.data.value?.defaults || {})
/**
 * 服务端强制项。Iceberg 侧「配置」页签要用它，Paimon 侧的概览只展示 `defaults`。
 * 一起读回来就不再发第二次 `/v1/config`：那次请求只有排障时才有意义，
 * 而这一页每次加载都会读一次，多读一次纯属浪费。
 */
const configOverrides = computed(() => capabilities.data.value?.overrides || {})

/**
 * 不做、因此**不声明**的端点，按能力分组显示。
 *
 * <p>为什么这份清单在前端：它是「**没有**出现在 `endpoints` 里的东西」——
 * 服务端不会下发「我不支持什么」，没有就是没有。因此它只能写在这里，
 * 并与来源保持同步：条数由 `scripts/verify-console.py` 与
 * `scripts/verify-iceberg-endpoints.py` 的 `NOT_IMPLEMENTED` 对齐
 * （分组是给人看的，条数是给机器比的）。
 *
 * <p>为什么值得摆在页面上：排障时「某项能力为什么报 UnsupportedOperation」的答案
 * 就在这两栏里——清单里有没有它。要读文档才能回答的话，这一页就白做了。
 */
const NOT_DECLARED = [
  'plan / tasks（扫描规划）',
  'sign（远程签名）',
]
/** 不声明的操作总数（分组不是一对一，所以单独写一个数）。 */
const NOT_DECLARED_TOTAL = 5

/**
 * 凭据下发：由 `credential-manager.type` 决定，读 `meta.service`。
 *
 * <p>写这一行是因为它**最容易被误解**：凭据端点在两套协议里都存在
 * （Paimon 的 `/token`、Iceberg 的 `/credentials`），但默认部署（`noop`）下
 * 它返回 501 并说明原因——「端点在但没开」与「端点不存在」在排障时是两件事。
 */
const credentialNote = computed(() => {
  const type = session.state.meta?.service?.credentialManagerType || ''
  if (!type || type === 'noop') {
    return '不代发凭据（credential-manager.type=noop）：客户端的凭据端点存在，但返回 501 并说明原因，凭据由客户端自己配置'
  }
  return `由 ${type} 代发`
})

/**
 * 文件列表：路径与前缀都是页面状态，不是路由参数。
 *
 * <p>下钻到第几层不那么值得进 URL（刷新回到仓库根是合理的），但**必须**在会话内保留，
 * 否则每次切页签回来都要重新钻一遍。切换 catalog 时重置，见下面的 watch。
 *
 * <p><b>前缀过滤与翻页都在服务端</b>（回车触发一次请求）。这一层到底有多少条目，
 * 只有列完才知道；「共 N 条、第 M 页」也只能由服务端算——本地切片在对象存储上
 * 会先被 `max-entries` 截一刀，界面上的总数就比真实值小。
 */
const filePath = ref('')
const filePrefixInput = ref('')
const filePrefix = ref('')

/**
 * 最近一次列表响应。`maxEntries` / `truncated` / `total` 不是行级字段，
 * 混不进 `rows`，单独留一份给提示条用。
 */
const fileListing = ref(null)
/** 是否成功列过至少一次目录：空态提示只在这个前提下出现，否则和「还没加载」分不开。 */
const fileLoaded = ref(false)

async function fetchFiles({ page, pageSize }) {
  const payload = await consoleApi.listCatalogFiles(
    catalogName.value,
    filePath.value,
    filePrefix.value,
    { page, pageSize },
  )
  fileListing.value = payload
  fileLoaded.value = true
  return { items: payload.entries || [], total: payload.page?.total ?? payload.total ?? 0 }
}

// `immediate: false`：概览页自己就能回答大部分问题，列目录要打一次存储
// （对象存储上是一次前缀列举），不该在打开页面时就付这个代价——首次切到
// 「文件列表」页签才发请求，见 `onTabChange`。
const {
  loading: filesLoading,
  error: filesError,
  rows: fileRows,
  total: totalEntries,
  page: filePage,
  pageSize: filePageSize,
  load: loadFiles,
  reload: reloadFiles,
  onPageChange: onFilePageChange,
  onSizeChange: onFileSizeChange,
} = useRemotePaging(fetchFiles, { immediate: false })

const crumbs = computed(() => {
  const segments = filePath.value.split('/').filter(Boolean)
  return segments.map((name, index) => ({ name, path: segments.slice(0, index + 1).join('/') }))
})

const parentPath = computed(() => crumbs.value.slice(0, -1).map((crumb) => crumb.name).join('/'))

function openDirectory(path) {
  filePath.value = path
  // 换了目录就换了集合，页码必须归位：停在第 3 页会看到空表，而那个目录
  // 其实有 5 条——「空」和「不在这一页」是两件事。
  reloadFiles()
}

/** 回车或点「查询」才发请求：逐字符过滤会把每次按键都变成一次存储列举。 */
function applyPrefix() {
  filePrefix.value = filePrefixInput.value.trim()
  reloadFiles()
}

/**
 * 下载一个文件。路径原样回传（响应里已经是相对仓库根的形状），
 * 文件名由服务端的 `Content-Disposition` 给出；失败（404 / 关开关的 501 等）
 * 走与列表加载同一套错误提示。
 */
async function downloadEntry(row) {
  try {
    await consoleApi.downloadCatalogFile(catalogName.value, row.path)
  } catch (error) {
    notifyError(describeError(error))
  }
}

/**
 * catalog 变了就整体重置。
 *
 * <p>同一个组件实例会被两条 `/catalogs/:catalogName` 复用（vue-router 按组件判等），
 * 不清的话会出现「标题是 b、文件列表还是 a 的目录」——而且 `useResource` 只在挂载时
 * 自动加载，换 catalog 后不重新拉取会停在旧数据上。
 *
 * <p>路径一并打回仓库根：`fe_x.db/orders` 在另一个 catalog 里通常并不存在，
 * 沿用它只会换来一个 404。
 */
watch(catalogName, () => {
  filePath.value = ''
  filePrefix.value = ''
  filePrefixInput.value = ''
  // 显式清空，否则「文件列表」页签会因为已经加载过而跳过首次加载
  fileListing.value = null
  fileLoaded.value = false
  // 能力清单同理：它是按 prefix 问出来的，留着上一个 catalog 的那份会显示错清单
  capabilities.data.value = null
  capabilities.error.value = ''
  overview.load()
  capabilities.load()
  if (tab.value === 'files') {
    reloadFiles()
  }
})

/**
 * 首次切到「文件列表」时才请求：概览页自己就能回答大部分问题，
 * 而列目录要打一次存储（对象存储上是一次前缀列举），不该在打开页面时就付这个代价。
 */
function onTabChange(name) {
  if (name === 'files' && !fileLoaded.value && !filesLoading.value) {
    reloadFiles()
  }
}

function reloadAll() {
  overview.load()
  capabilities.load()
  if (tab.value === 'files') {
    loadFiles()
  }
  if (tab.value === 'storage') {
    storagePanel.value?.reload()
  }
}

onConsoleRefresh(reloadAll)

// ------------------------------------------------------------ 配置：默认表属性

/**
 * 「Paimon 表默认参数」的编辑副本。
 *
 * <p>编辑的是**本地副本**：直接绑服务端返回的 `properties` 会让「没保存」和「已保存」
 * 分不开，而这一页的保存是整体替换，误触的代价是整个 catalog 的属性。
 * 副本在概览数据到达时重建（见下面的 watch），保存成功后也靠它回到干净状态。
 */
const defaultRows = ref([]);
const defaultsSaving = ref(false);
/** 上一次「与服务端一致」的形态，用于判断有没有未保存的改动。 */
const defaultsBaseline = ref('');

/** 行的键序即保存时的写入顺序，用 JSON 比较天然带上顺序，不需要另存一份快照列表。 */
function rowsToDefaults() {
  const result = {};
  for (const row of defaultRows.value) {
    const name = (row.name || '').trim();
    if (!name) continue;
    result[name] = row.value == null ? '' : String(row.value);
  }
  return result;
}

const defaultsDirty = computed(() => JSON.stringify(rowsToDefaults()) !== defaultsBaseline.value);

function resetDefaultRows() {
  defaultRows.value = Object.entries(tableDefaultsOf(catalog0.value?.properties))
    .map(([name, value]) => ({ name, value }));
  defaultsBaseline.value = JSON.stringify(rowsToDefaults());
}

// 概览数据（重）加载后重建编辑副本：保存成功会触发 overview.load()，
// 于是这里也顺手把 baseline 推到新值，按钮回到「与服务端一致」。
// 编辑期间的刷新同理——保留过期的副本比覆盖它更危险。
watch(catalog0, resetDefaultRows, { immediate: true });

function addDefaultRow() {
  defaultRows.value.push({ name: '', value: '' });
}

function removeDefaultRow(index) {
  defaultRows.value.splice(index, 1);
}

/**
 * 保存默认参数。
 *
 * <p>三处先拦下来再发请求，因为服务端不会替我们报这些错：
 * 属性名重复（后者会静默覆盖前者）、属性名里再带一次前缀（粘贴整键时常见）、
 * 以及 `type`（表格式由建表请求决定，服务端会忽略它，写进去只会让人以为生效了）。
 */
async function saveDefaults() {
  const defaults = rowsToDefaults();
  const names = Object.keys(defaults);
  const duplicated = names.find((name, index) => names.indexOf(name) !== index);
  if (duplicated) {
    notifyError(`属性名重复：${duplicated}`);
    return;
  }
  const prefixed = names.find((name) => isTableDefaultKey(name));
  if (prefixed) {
    notifyError(`属性名不要带前缀：${prefixed} —— 前缀由这一页统一添加`);
    return;
  }
  if (names.includes('type')) {
    notifyError('type 不能作为默认值：表格式由建表请求决定，服务端会忽略它');
    return;
  }

  defaultsSaving.value = true;
  try {
    const current = catalog0.value;
    await managementApi.updateCatalog(catalogName.value, {
      currentEntityVersion: current.entityVersion,
      properties: composeProperties(current.properties, names.length ? defaults : null),
    });
    notifySuccess(`已保存 ${names.length} 项默认参数`);
    // 顶栏与「新建表」表单读的是 catalog 列表里的 properties，保存后要一起刷新
    await session.loadCatalogs();
    await overview.load();
  } catch (error) {
    if (error?.status === 409) {
      // 别人先改过：刷新到最新版本再看，避免拿着旧值重试把对方的改动覆盖掉
      notifyError(`${describeError(error)} —— 已被他人改动，页面已刷新为最新配置`);
      await overview.load();
    } else {
      notifyError(describeError(error));
    }
  } finally {
    defaultsSaving.value = false;
  }
}
</script>

<style scoped>
/* 页签容器的内边距按布局各自定义，不统一（理由见 styles/index.css 里
   「flush 卡片里的页签」那段）：本页是全宽页签，与 TableView 同一个 16px 栅格 */
.pc-tabs {
  padding: 0 16px;
}

.pc-tabs :deep(.el-tabs__header) {
  margin-bottom: 0;
}

.pc-tab-body {
  padding: 14px 16px;
}

/* 文件列表的面包屑：字号与顶栏那条一致，但独立成类——它嵌在工具行里，
   两处共用 `.pc-crumbs` 会让「改顶栏字号」意外改到这里 */
.pc-file-crumbs {
  font-size: 13.5px;
}

/* 概览左右分栏：左边是读数（键值对），右边是原始 JSON */
.pc-detail-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 18px;
  align-items: start;
}

.pc-side {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

/* 「表格式」那一行的补充说明：紧跟徽标，与值同排 */
.pc-format-note {
  margin-left: 8px;
}

/* --- 协议与能力：端点清单 ------------------------------------------------
 * 清单是按行读的（排障时在找某一条），因此用等宽字体逐行列出而不是塞进表格：
 * 表格会按内容分配列宽，而这些字符串长且同构，多一列反而更难扫。
 * 高度封顶 + 滚动，避免一个 catalog 的 26 行把整页顶下去。
 * ---------------------------------------------------------------------- */
.pc-endpoints {
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
  overflow: hidden;
}

.pc-endpoints-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 8px 12px;
  font-size: 13px;
  font-weight: 600;
  background: var(--pc-surface-2);
  border-bottom: 1px solid var(--pc-border);
}

.pc-endpoints-head .pc-dim {
  font-size: 12px;
  font-weight: 400;
}

.pc-endpoints-list {
  max-height: 240px;
  overflow: auto;
  margin: 0;
  padding: 6px 12px;
  list-style: none;
  font-size: 12.5px;
  line-height: 1.9;
}

.pc-endpoints-list li {
  overflow-wrap: anywhere;
}

.pc-endpoints-foot {
  padding: 8px 12px;
  font-size: 12px;
  line-height: 1.7;
  border-top: 1px solid var(--pc-border);
}

.pc-foot-title {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--pc-text);
}

/* 不声明的能力按标签铺开：它们是可数的几个词组，一行一个太占高度，
   换行铺开才看得出「一共就这么几块」 */
.pc-tag-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin: 6px 0;
}

.pc-tag {
  padding: 1px 7px;
  font-size: 11.5px;
  color: var(--pc-text-dim);
  background: var(--pc-surface-2);
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
}

.pc-foot-note {
  margin-top: 2px;
}

/* 默认表属性的编辑区：表头与数据行共用同一套栅格（属性名 / 取值 / 操作）。
   属性名那一列宽一点——它带着「table-default.」这个固定前缀，留给真正的属性名 */
.pc-defaults-row {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr) 56px;
  gap: 10px;
  align-items: center;
}

.pc-defaults-head {
  padding-bottom: 6px;
  font-size: 12.5px;
  color: var(--pc-text-dim);
}

.pc-defaults-head .pc-defaults-op {
  text-align: right;
}

.pc-defaults-row + .pc-defaults-row {
  margin-top: 8px;
}

.pc-defaults-empty {
  padding: 12px 0 4px;
  font-size: 13px;
}

.pc-defaults-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 12px;
}

.pc-defaults-state {
  font-size: 12.5px;
}

/* 文件名与目录名前的图标；目录与文件用同一个宽度，名称才对得齐 */
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

/* 过滤行：前缀输入 + 查询按钮，右侧显示这一批的条数 */
.pc-filter-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.pc-prefix-input {
  width: 240px;
}

@media (max-width: 1100px) {
  .pc-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
