<template>
  <div class="pc-page">
    <PageHeader
      :title="`表：${table}`"
      :subtitle="icebergCatalog
        ? `${prefix} / ${database} / ${table}　这个 catalog 是 Iceberg 协议面，本页不适用（见下方说明）。`
        : `${prefix} / ${database} / ${table}　这里看到的结构与提交历史就是引擎客户端读写的那一份；删除表不可撤销。`"
    >
      <template #actions>
        <el-button size="small" @click="$router.push({ name: 'browse', query: { catalog: prefix, database } })">
          <el-icon><Back /></el-icon>
          <span class="pc-btn-text">返回列表</span>
        </el-button>
        <el-button size="small" :loading="overview.loading.value" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <!-- Iceberg catalog 下这个按钮会打 Paimon 的删表端点（拿 404），不显示 -->
        <el-button v-if="!icebergCatalog" size="small" type="danger" plain @click="confirmDrop">删除表</el-button>
      </template>
    </PageHeader>

    <ResourceState v-if="!icebergCatalog" :loading="overview.loading.value" :error="overview.error.value" />

    <!--
      Iceberg catalog：本页读的是 Paimon 协议面的表端点
      （`/v1/{prefix}/databases/{db}/tables/{tbl}`），对一个 Iceberg catalog
      发出去会被服务端以「格式不匹配」拒绝。这里不发那个请求，也不显示一屏错误：
      把「本页不适用」与「Iceberg 版表详情页在哪儿」一起说出来。
    -->
    <PanelCard v-else>
      <el-alert type="info" :closable="false" show-icon>
        <template #title>catalog「{{ prefix }}」是 Iceberg 协议面，请使用 Iceberg 版表详情页</template>
        <div>
          本页读的是 Paimon 协议面的表端点
          （<code class="pc-mono">/v1/{prefix}/databases/{database}/tables/{table}</code>），
          而 Iceberg 客户端走 <code class="pc-mono">/v1/{prefix}/namespaces/**</code>，
          两者不是同一套资源，因此这里不做拼接式的「换一套路径再看」——
          图的含义、快照的代数与视图的形状都不一样。
          Iceberg 表的详细信息 / 快照 / 文件列表在 Iceberg 版表详情页里。
        </div>
      </el-alert>
      <div class="pc-tools-row">
        <el-button
          size="small"
          type="primary"
          @click="$router.push({ name: 'icebergTable', params: { prefix, database, table } })"
        >
          <el-icon><View /></el-icon>
          <span class="pc-btn-text">打开 Iceberg 表详情</span>
        </el-button>
        <el-button size="small" @click="$router.push({ name: 'catalogDetail', params: { catalogName: prefix } })">
          <el-icon><Coin /></el-icon>
          <span class="pc-btn-text">打开 catalog 详情</span>
        </el-button>
        <el-button size="small" @click="$router.push({ name: 'browse', query: { catalog: prefix } })">
          <el-icon><Back /></el-icon>
          <span class="pc-btn-text">返回目录浏览</span>
        </el-button>
      </div>
    </PanelCard>

    <template v-if="!icebergCatalog && table0">
      <PanelCard flush>
        <el-tabs v-model="tab" class="pc-tabs" @tab-change="onTabChange">
          <el-tab-pane label="详细信息" name="details" />
          <!-- 血缘：唯一一个「数据不在本页 composable 里」的页签（面板挂载即查，见 LineagePanel）。
               它不跟着 isPaimonTable 伸缩——依赖关系由引擎上报，Format / Object 表同样可能被
               Spark 写过；没采集到时面板自己给空态，比「页签凭空消失」更能说明问题 -->
          <el-tab-pane label="血缘" name="lineage" />
          <!-- 数据预览放在「详细信息」之后、「快照」之前：它是详情页里第二个"第一眼要看"的东西。
               只给快照托管表（Paimon / table）——Format / Object / Lance 表的数据文件不是
               快照托管的 parquet 族，显示一个"不支持"不如不显示（与快照/标签/分支同一条伸缩规则） -->
          <el-tab-pane v-if="isPaimonTable" label="数据预览" name="preview" />
          <!-- 快照 / 标签 / 分支 / 数据访问都是快照托管表的产物：非 Paimon 表不出现这几个页签 -->
          <el-tab-pane v-if="isPaimonTable" label="快照" name="snapshots" />
          <el-tab-pane v-if="isPaimonTable" label="标签" name="tags" />
          <el-tab-pane v-if="isPaimonTable" label="分支" name="branches" />
          <el-tab-pane label="文件列表" name="files" />
          <el-tab-pane v-if="isPaimonTable" label="数据访问" name="access" />
          <!-- 数据策略（行过滤 / 列脱敏）不按表格式伸缩：策略按三段名挂在任何表上，
               Format / Object 表同样可能被引擎读到、同样值得保护（与「文件列表」同一条规则） -->
          <el-tab-pane label="数据策略" name="policy" />
          <!-- 存储优化同样按表格式伸缩：本页只展示 Paimon 表（Iceberg 表走 IcebergTableView，
               那边挂的是同一个组件、传 format="ICEBERG"）。
               外部 Paimon 表仍会出现这个页签——它由服务端回答「不支持」并说明原因，
               比「页签凭空消失」更容易理解（见 TableOptimizationPanel） -->
          <el-tab-pane v-if="isPaimonTable" label="存储优化" name="optimization" />
        </el-tabs>

        <div class="pc-tab-body">
          <!------------------------------ 详细信息 -------------------------------->
          <template v-if="tab === 'details'">
            <el-alert
              v-if="!isPaimonTable"
              type="info"
              :closable="false"
              show-icon
              class="pc-hint"
            >
              {{ kindNote }}
            </el-alert>

            <!-- 四张卡片全部来自最新快照：非 Paimon 表没有快照，整块不出现 -->
            <div class="pc-stats pc-stats-tight" v-if="latest && isPaimonTable">
              <div class="pc-stat">
                <div class="pc-stat-label">记录数（最新快照）</div>
                <div class="pc-stat-value">{{ formatNumber(latest.recordCount) }}</div>
                <div class="pc-stat-foot">累计写入，含历史文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件数</div>
                <div class="pc-stat-value">{{ formatNumber(latest.fileCount) }}</div>
                <div class="pc-stat-foot">
                  {{ missingFileStats ? '快照未带文件统计' : '最新快照引用的文件' }}
                </div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件大小</div>
                <div class="pc-stat-value">{{ formatBytes(latest.fileSizeInBytes) }}</div>
                <div class="pc-stat-foot">
                  {{ missingFileStats ? '提交时未附 statistics' : '不含快照与清单文件' }}
                </div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">最近文件写入</div>
                <div class="pc-stat-value pc-stat-value-sm">
                  {{ latest.lastFileCreationTime ? relativeTime(latest.lastFileCreationTime) : '—' }}
                </div>
                <div class="pc-stat-foot">最新快照 ID：{{ latest.snapshot?.id ?? '—' }}</div>
              </div>
            </div>

            <div class="pc-detail-grid">
              <dl class="pc-kv">
                <dt>表 ID</dt>
                <dd><CopyText :text="table0.id" /></dd>
                <dt>是否外部表</dt>
                <dd>{{ table0.isExternal ? '是（位置由外部给定，本服务不碰它的目录）' : '否（由本服务管理）' }}</dd>
                <dt>表格式</dt>
                <dd>{{ kindLabel }}</dd>
                <dt>Schema 版本</dt>
                <dd class="pc-mono">{{ table0.schemaId ?? '—' }}</dd>
                <dt>路径</dt>
                <dd><CopyText :text="table0.path" /></dd>
                <dt>Owner</dt>
                <dd>{{ orPlaceholder(table0.owner) }}</dd>
                <dt>创建</dt>
                <dd><TimeText :millis="table0.createdAt" /> · {{ orPlaceholder(table0.createdBy) }}</dd>
                <dt>更新</dt>
                <dd><TimeText :millis="table0.updatedAt" /> · {{ orPlaceholder(table0.updatedBy) }}</dd>
                <dt>表注释</dt>
                <dd>{{ orPlaceholder(table0.schema?.comment) }}</dd>
              </dl>
              <JsonBlock
                :value="table0.schema?.options || {}"
                label="表属性（options）"
                empty-text="没有表属性"
                max-height="300px"
              />
            </div>

            <!-- 字段列表 / 分区列表：合并进详细信息页（原先是两个独立页签）。
                 二级页签按需加载：字段来自 overview 里的 schema，分区切过去才请求。
                 分区列表只有 Paimon 表有（分区状态来自快照提交）；其余表格式只留下字段列表，
                 此时二级页签栏整个不出现——一行只有一个页签时它只是一道多余的分隔线。 -->
            <el-tabs
              v-if="isPaimonTable"
              v-model="detailsSection"
              class="pc-subtabs"
              @tab-change="onDetailsSectionChange"
            >
              <el-tab-pane label="字段列表" name="fields" />
              <el-tab-pane name="partitions">
                <template #label>
                  分区列表
                  <TipNote>
                    分区的统计口径是最新快照的活跃数据：只计算被最新快照引用的文件，
                    因此各分区之和可能小于仓库实际占用。
                  </TipNote>
                </template>
              </el-tab-pane>
            </el-tabs>

            <template v-if="detailsSection === 'fields'">
              <div class="pc-filter-row">
                <el-input
                  v-model="schemaFilterInput"
                  size="small"
                  class="pc-prefix-input"
                  placeholder="输入字段名称过滤"
                  clearable
                />
              </div>

              <ResourceState
                :empty="!filteredFields.length && (table0.schema?.fields || []).length"
                :empty-text="`没有名称包含「${schemaFilter}」的字段`"
              />

              <el-table :data="fieldRows" size="small">
                <el-table-column prop="id" label="序号" width="64" align="right" />
                <el-table-column prop="name" label="字段名称" min-width="150" />
                <el-table-column label="类型" min-width="170">
                  <template #default="{ row }">
                    <span class="pc-mono">{{ typeText(row.type) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="非空" width="70" align="center">
                  <template #default="{ row }">{{ isNotNull(row) ? '是' : '否' }}</template>
                </el-table-column>
                <!-- Object 表的列由 Paimon 固定（path / name / length / mtime / atime / owner），
                     没有主键也不分区，这两列对它恒为「否」——留着只是两栏噪音 -->
                <el-table-column v-if="!isObjectTable" label="主键" width="70" align="center">
                  <template #default="{ row }">{{ isPrimaryKey(row) ? '是' : '否' }}</template>
                </el-table-column>
                <el-table-column v-if="!isObjectTable" label="分区字段" width="80" align="center">
                  <template #default="{ row }">{{ isPartitionKey(row) ? '是' : '否' }}</template>
                </el-table-column>
                <el-table-column prop="description" label="描述" min-width="130">
                  <template #default="{ row }">{{ orPlaceholder(row.description) }}</template>
                </el-table-column>
                <el-table-column prop="defaultValue" label="默认值" min-width="100">
                  <template #default="{ row }">{{ orPlaceholder(row.defaultValue) }}</template>
                </el-table-column>
              </el-table>

              <TablePager
                :total="fieldTotal"
                :page="fieldPage"
                :page-size="fieldPageSize"
                @page-change="onFieldPageChange"
                @size-change="onFieldSizeChange"
              />

            </template>

            <template v-else-if="isPaimonTable">
              <div class="pc-filter-row">
                <el-input
                  v-model="partitionFilterInput"
                  size="small"
                  class="pc-prefix-input"
                  placeholder="按分区名前缀过滤，如 dt=2024"
                  clearable
                />
              </div>

              <ResourceState
                :loading="partitionsLoading"
                :error="partitionsError"
                :empty="partitionsLoaded && !totalPartitions"
                :empty-text="partitionFilterInput.trim()
                  ? `没有以「${partitionFilterInput.trim()}」开头的分区`
                  : '这张表没有分区，或还没有数据'"
              />

              <el-table
                v-if="partitionsRows.length || partitionsLoading"
                v-loading="partitionsLoading"
                :data="partitionsRows"
                size="small"
                empty-text="这张表没有分区，或还没有数据"
              >
                <el-table-column label="分区名" min-width="200">
                  <template #default="{ row }">
                    <span class="pc-mono pc-break">{{ inlineMap(row.spec) }}</span>
                  </template>
                </el-table-column>
                <el-table-column label="记录数" width="110" align="right">
                  <template #default="{ row }">{{ formatNumber(row.recordCount) }}</template>
                </el-table-column>
                <el-table-column label="文件数" width="90" align="right">
                  <template #default="{ row }">{{ formatNumber(row.fileCount) }}</template>
                </el-table-column>
                <el-table-column label="大小" width="110" align="right">
                  <template #default="{ row }">{{ formatBytes(row.fileSizeInBytes) }}</template>
                </el-table-column>
                <el-table-column label="桶数" width="80" align="right">
                  <template #default="{ row }">{{ orPlaceholder(row.totalBuckets) }}</template>
                </el-table-column>
                <el-table-column label="已完成" width="90">
                  <template #default="{ row }">
                    <el-tag size="small" :type="row.done ? 'success' : 'info'" effect="plain">
                      {{ row.done ? '是' : '否' }}
                    </el-tag>
                  </template>
                </el-table-column>
              </el-table>

              <TablePager
                :total="totalPartitions"
                :page="partitionPage"
                :page-size="partitionPageSize"
                @page-change="onPartitionPageChange"
                @size-change="onPartitionSizeChange"
              />
            </template>
          </template>

          <!-------------------------------- 快照 ---------------------------------->
          <template v-else-if="tab === 'snapshots'">
            <div class="pc-tools-row">
              <span class="pc-dim">按版本号查询指定快照：</span>
              <el-input v-model="snapshotVersion" size="small" placeholder="版本号，如 3" class="pc-version-input" />
              <el-button size="small" :loading="versionLoading" @click="fetchVersion">查询</el-button>
            </div>
            <el-table
              v-loading="snapshots.loading.value"
              :data="snapshots.data.value?.snapshots || []"
              size="small"
              empty-text="这张表还没有快照（还没有写入过数据）"
            >
              <el-table-column label="快照 ID" min-width="170">
                <template #default="{ row }"><span class="pc-mono">{{ row.id }}</span></template>
              </el-table-column>
              <el-table-column prop="commitKind" label="提交类型" width="120" />
              <el-table-column prop="commitUser" label="提交者" min-width="110" />
              <el-table-column label="提交时间" width="170">
                <template #default="{ row }"><TimeText :millis="row.timeMillis" /></template>
              </el-table-column>
              <el-table-column label="总记录数" width="110" align="right">
                <template #default="{ row }">{{ formatNumber(row.totalRecordCount) }}</template>
              </el-table-column>
              <el-table-column label="增量记录数" width="110" align="right">
                <template #default="{ row }">{{ formatNumber(row.deltaRecordCount) }}</template>
              </el-table-column>
              <el-table-column label="" width="110" align="right">
                <template #default="{ row }">
                  <IconAction icon="View" label="详情" @click="showSnapshot(row)" />
                  <IconAction icon="RefreshLeft" label="回滚" type="danger" @click="rollbackToSnapshot(row)" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 标签 ---------------------------------->
          <template v-else-if="tab === 'tags'">
            <div class="pc-tools-row">
              <span class="pc-dim">标签把某个快照固定下来，使其不受快照过期清理影响。</span>
              <span class="pc-spacer" />
              <el-button size="small" @click="openCreateTag">
                <el-icon><Plus /></el-icon>
                <span class="pc-btn-text">新建标签</span>
              </el-button>
            </div>
            <el-table
              v-loading="tags.loading.value"
              :data="tags.data.value?.tags || []"
              size="small"
              empty-text="还没有标签"
            >
              <el-table-column prop="tagName" label="标签名" min-width="200" />
              <el-table-column label="指向快照" width="180">
                <template #default="{ row }">
                  <span class="pc-mono">{{ row.snapshotId ?? '最新' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="创建时间" width="170">
                <template #default="{ row }"><TimeText :millis="row.tagCreateTime" /></template>
              </el-table-column>
              <el-table-column label="保留时长" width="110">
                <template #default="{ row }">{{ orPlaceholder(row.tagTimeRetained) }}</template>
              </el-table-column>
              <el-table-column label="" width="110" align="right">
                <template #default="{ row }">
                  <IconAction icon="RefreshLeft" label="回滚" type="danger" @click="rollbackToTag(row.tagName)" />
                  <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropTag(row.tagName)" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 分支 ---------------------------------->
          <template v-else-if="tab === 'branches'">
            <div class="pc-tools-row">
              <span class="pc-dim">分支是一条可写的历史线，常用于隔离试验或并发写入。</span>
              <span class="pc-spacer" />
              <el-button size="small" @click="openCreateBranch">
                <el-icon><Plus /></el-icon>
                <span class="pc-btn-text">新建分支</span>
              </el-button>
            </div>
            <el-table
              v-loading="branches.loading.value"
              :data="branches.data.value?.branches || []"
              size="small"
              empty-text="这张表还没有分支（主分支不在此列表中）"
            >
              <el-table-column prop="branchName" label="分支名" min-width="200">
                <template #default="{ row }">{{ row.branchName || row.name || row }}</template>
              </el-table-column>
              <el-table-column label="操作" width="100" align="right" fixed="right">
                <template #default="{ row }">
                  <IconAction icon="EditPen" label="重命名" @click="openRenameBranch(branchName(row))" />
                  <IconAction icon="DArrowRight" label="推进到最新" @click="forwardBranch(branchName(row))" />
                  <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropBranch(branchName(row))" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!------------------------------ 文件列表 -------------------------------->
          <template v-else-if="tab === 'files' && table0">
            <div class="pc-tools-row">
              <el-breadcrumb separator="/" class="pc-file-crumbs">
                <el-breadcrumb-item>
                  <a class="pc-link" @click.prevent="openTableDirectory('')">表目录</a>
                </el-breadcrumb-item>
                <el-breadcrumb-item v-for="crumb in tableCrumbs" :key="crumb.path">
                  <a class="pc-link" @click.prevent="openTableDirectory(crumb.path)">{{ crumb.name }}</a>
                </el-breadcrumb-item>
              </el-breadcrumb>
              <span class="pc-spacer" />
              <el-button size="small" :disabled="!tableParentPath" @click="openTableDirectory(tableParentPath)">
                <el-icon><Top /></el-icon>
                <span class="pc-btn-text">返回上级</span>
              </el-button>
            </div>

            <el-alert
              v-if="tableFileListing?.truncated"
              type="warning"
              :closable="false"
              show-icon
              class="pc-hint"
            >
              <template #title>只显示了前 {{ tableFileListing.maxEntries }} 条（共 {{ tableFileListing.total }} 条）</template>
              <div>
                排序是目录优先、名称升序，因此被截掉的是尾部的文件。下钻到更具体的子目录，
                或调大 <span class="pc-mono">lakehouse.rest.file-browser.max-entries</span>。
              </div>
            </el-alert>

            <ResourceState
              :loading="tableFilesLoading"
              :error="tableFilesError"
              :empty="tableFilesLoaded && !totalTableEntries"
              :empty-text="tableFilePrefix ? `没有以「${tableFilePrefix}」开头的条目` : '这个目录里没有文件'"
            />

            <div class="pc-filter-row">
              <el-input
                v-model="tableFilePrefixInput"
                size="small"
                class="pc-prefix-input"
                placeholder="文件名前缀（回车过滤）"
                clearable
                @keyup.enter="applyTablePrefix"
                @clear="applyTablePrefix"
              />
              <el-button size="small" @click="applyTablePrefix">查询</el-button>
              <TipNote>
                这里只看这张表自己目录下的文件，出不了表目录（服务端按表目录为根拦住了
                <code>..</code> 与绝对路径）。要浏览整个仓库（包括库里其它表与残留目录），
                去目录（Catalog）详情页的「文件列表」。当前目录相对表目录为
                <code>{{ tableFilePath || '（表目录）' }}</code>。
              </TipNote>
              <span class="pc-spacer" />
              <span class="pc-dim">
                共 {{ totalTableEntries }} 条<span v-if="tableFileListing?.truncated">（已达上限
                {{ tableFileListing.maxEntries }}，用前缀过滤缩小范围）</span>
              </span>
            </div>

            <el-table v-if="tableEntries.length || tableFilesLoading" :data="tableEntries" size="small">
              <el-table-column label="名称" min-width="280">
                <template #default="{ row }">
                  <!-- 目录名可点，点进去看下一层；文件没有可点的动作（这一页只读） -->
                  <a v-if="row.directory" class="pc-link pc-file-name" @click.prevent="openTableDirectory(row.path)">
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
              <el-table-column label="相对表目录的路径" min-width="260">
                <template #default="{ row }">
                  <span class="pc-mono pc-dim pc-break">{{ row.path }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="52" align="center" fixed="right">
                <template #default="{ row }">
                  <!-- 只有文件可下载：目录用列表下钻；表目录里没有「下载整个目录」 -->
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
              :total="totalTableEntries"
              :page="tableFilePage"
              :page-size="tableFilePageSize"
              @page-change="onTableFilePageChange"
              @size-change="onTableFileSizeChange"
            />
          </template>

          <!------------------------------- 存储优化 -------------------------------->
          <!-- 这个页签的内容在独立组件里：它是唯一一块「可写的表单 + 历史」，
               与其余只读页签不同量级（约 400 行），留在本文件只会把表详情页的
               主线（概览 + 只读页签）淹掉 -->
          <template v-else-if="tab === 'optimization'">
            <TableOptimizationPanel
              ref="optimizationPanel"
              :catalog="prefix"
              :database="database"
              :table-name="table"
            />
          </template>

          <!------------------------------- 血缘 -------------------------------->
          <!-- 面板自己管数据（挂载即查一跳、展开按需、列映射切过去才发），父组件只给一个 ref。
               ref 是点拼全名 `catalog.namespace.table`：服务端按这个口径存边，两处必须一致。
               注意不是 `:ref`——那是模板 ref 的保留属性，传不进 prop -->
          <template v-else-if="tab === 'lineage'">
            <LineagePanel :table-ref="lineageRef" />
          </template>

          <!------------------------------- 数据预览 -------------------------------->
          <!-- 面板自身不判断格式：格式差异（读取器、分区拼法、hidden 分区）全在服务端。
               因此这里只传 tableId 与全量列名（投影多选框的选项来源） -->
          <template v-else-if="tab === 'preview' && table0">
            <TablePreviewPanel :table-id="table0.id" :columns="previewColumns" />
          </template>

          <!------------------------------- 数据策略 -------------------------------->
          <!-- 与存储优化同构：内容在独立组件里（可写表单，约 500 行），父组件只给 ref
               与一声「该加载了」。列选项复用 previewColumns 的来源（全量 schema） -->
          <template v-else-if="tab === 'policy'">
            <TablePolicyPanel
              ref="policyPanel"
              :catalog="prefix"
              :database="database"
              :table-name="table"
              :columns="policyColumns"
            />
          </template>

          <!------------------------------- 数据访问 -------------------------------->
          <template v-else>
            <div class="pc-access">
              <PanelCard title="数据访问令牌" hint="GET .../token">
                <p class="pc-dim pc-access-text">
                  服务端按 catalog 的存储配置下发临时凭据。若服务端以
                  <code class="pc-mono">lakehouse.rest.credential-manager.type=noop</code> 运行，
                  这里会返回 501——那种部署下由引擎自带的云凭据链负责鉴权。
                </p>
                <el-button size="small" :loading="tokenLoading" @click="fetchToken">
                  <el-icon><Key /></el-icon>
                  <span class="pc-btn-text">获取令牌</span>
                </el-button>
                <div v-if="tokenError" class="pc-access-error">{{ tokenError }}</div>
                <JsonBlock v-if="tokenData" :value="tokenData" class="pc-access-json" max-height="300px" />
              </PanelCard>

              <PanelCard title="查询级授权规则" hint="POST .../auth">
                <p class="pc-dim pc-access-text">
                  传入要查询的列，服务端按调用主体的授权返回行过滤表达式与列脱敏规则。
                  这两项是引擎侧生效的，控制台只负责展示服务端给出了什么。
                </p>
                <el-input
                  v-model="authSelect"
                  size="small"
                  type="textarea"
                  :rows="3"
                  placeholder="要查询的列名，逗号或换行分隔；留空表示查询全部列"
                />
                <el-button size="small" class="pc-access-btn" :loading="authLoading" @click="fetchAuth">
                  查询规则
                </el-button>
                <div v-if="authError" class="pc-access-error">{{ authError }}</div>
                <JsonBlock v-if="authData" :value="authData" class="pc-access-json" max-height="300px" />
              </PanelCard>
            </div>
          </template>
        </div>
      </PanelCard>
    </template>

    <!-- 快照详情 -->
    <el-drawer v-model="snapshotDrawer.open" :title="snapshotDrawer.title" size="620px">
      <JsonBlock :value="snapshotDrawer.payload" />
    </el-drawer>

    <!-- 新建标签 -->
    <el-dialog v-model="tagDialog.open" title="新建标签" width="480px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="标签名" required>
          <el-input v-model="tagDialog.tagName" />
        </el-form-item>
        <el-form-item label="快照 ID">
          <el-input v-model="tagDialog.snapshotId" placeholder="留空表示指向最新快照" />
        </el-form-item>
        <el-form-item label="保留时长（timeRetained）">
          <el-input v-model="tagDialog.timeRetained" placeholder="例如 7d / 12h / 30m" />
        </el-form-item>
        <el-form-item label="已存在时忽略（ignoreIfExists）">
          <el-switch v-model="tagDialog.ignoreIfExists" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="tagDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="tagDialog.saving" @click="saveTag">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建分支 -->
    <el-dialog v-model="branchDialog.open" title="新建分支" width="460px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="分支名" required>
          <el-input v-model="branchDialog.branch" />
        </el-form-item>
        <el-form-item label="从标签创建（fromTag）">
          <el-input v-model="branchDialog.fromTag" placeholder="留空表示从主分支最新快照创建" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="branchDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="branchDialog.saving" @click="saveBranch">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, defineAsyncComponent, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import CopyText from '@/components/CopyText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TableOptimizationPanel from '@/components/TableOptimizationPanel.vue'
import TablePolicyPanel from '@/components/TablePolicyPanel.vue'
import TablePreviewPanel from '@/components/TablePreviewPanel.vue'
import TablePager from '@/components/TablePager.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { catalogApi } from '@/api/catalog.js'
import { consoleApi } from '@/api/console.js'
import { describeError } from '@/api/client.js'
import {
  confirmDanger,
  notifyError,
  notifySuccess,
  useEmbeddedPaging,
  useRemotePaging,
  useResource,
} from '@/composables/index.js'
import { session } from '@/store/session.js'
import { isIcebergCatalog } from '@/utils/catalogFormat.js'
import {
  formatBytes,
  formatNumber,
  inlineMap,
  orPlaceholder,
  relativeTime,
} from '@/utils/format.js'
import { isSnapshotManaged, tableKindLabel, tableKindOf } from '@/utils/tableKind.js'

/**
 * 表详情。页签随表格式伸缩：Paimon 表有七个——五个对应 catalog API 里以单张表为作用域的端点，
 * 外加组合页签「详细信息」（基本信息 + 快照统计 + options + 字段列表 / 分区列表两个二级页签）
 * 与「存储优化」（策略配置 + 执行历史，唯一一块可写的内容）；
 * Format 表 / Object 表 / Lance 表只留「详细信息」与「文件列表」，因为快照、标签、分支、
 * 数据访问四项都是快照托管表的产物，存储优化的三项动作也都建立在 Paimon 的快照与清单文件上
 * （理由与表格见下方 `tableKind`）。
 *
 * <p>授权不在这张表上做：表级权限统一到「权限管理」页（按 database + table 定位，
 * 一级资源一个表单），这里不再重复一份 catalog 角色的授权界面。
 *
 * <p>改表结构不在控制台上做：服务端的 `alter` 端点仍在（HTTP 与 `lakehouse-rest-spark`
 * 的 SQL 都能用），控制台里那份「变更构造器」已经撤掉——表结构变更是一次性的重决策，
 * 一个逐条添加再提交的表单既给不了预览也给不了回退，不如让它在 SQL 里显式发生。
 *
 * <p>「详细信息」（字段）/「数据访问」只用 overview 已有数据，
 * 其余按需加载：切到某个页签才发它的请求；详细信息里的「分区列表」二级页签同理。
 * 打开一张表就发两个请求（非 Paimon 表只有一个，见 `overview`），
 * 在远端仓库上会让页面明显变慢，所以页签之外的请求一个都不多发。
 */

const route = useRoute()

const prefix = computed(() => route.params.prefix)
const database = computed(() => route.params.database)
const table = computed(() => route.params.table)

/**
 * 血缘面板按需加载。
 *
 * <p>它是本页最重的组件（Canvas 渲染 + 布局 + 顶点/边表 + 图纸抽派），而大多数人打开表详情
 * 并不看血缘。同步 import 会让「打开一张表的成本」因为多了一个页签而变高——这与
 * 本页「页签之外一个请求都不多发」是同一条理由。异步组件把它的代码一起推迟到切过去那一刻。
 */
const LineagePanel = defineAsyncComponent(() => import('@/components/lineage/LineagePanel.vue'))

/**
 * 传给血缘面板的表名：`catalog.database.table`（点拼）。
 *
 * <p>服务端按这个口径存边（`LineageRefs`），不能在这里换成「内部 id」或「带引号的名字」——
 * 血缘记录由引擎侧上报，它只知道名字。
 */
const lineageRef = computed(() => [prefix.value, database.value, table.value].join('.'))

/**
 * 这个 catalog 是不是 Iceberg 协议面。
 *
 * <p>**本页整个建立在 Paimon 协议面上**：表详情、快照、标签、分支、分区与数据访问
 * 都是 `/v1/{prefix}/databases/{db}/tables/{tbl}/**` 这套路径。Iceberg catalog
 * 服务的是 `/v1/{prefix}/namespaces/{ns}/tables/{tbl}`，两套资源不是「换个路径再看一眼」
 * 的关系（图的含义、快照的代数、视图的形状都不同）。因此这里不拼路径，
 * 而是不去发那个注定被拒的请求，直接给出说明（见模板）。
 *
 * <p>值从 session 的 catalog 列表里读——`catalogFormat` 只在那里，见
 * `session.catalogByName`。首屏它可能还没到，见 `overview` 里的 `ensureCatalogs()`。
 */
const icebergCatalog = computed(() => isIcebergCatalog(session.catalogByName(prefix.value)))

/**
 * 首屏可能来不及：`catalogsLoaded` 之前读格式只会得到「不是 Iceberg」，
 * 于是会对着一个 Iceberg catalog 发出注定被拒的请求。
 *
 * <p>与顶栏的 `session.bootstrap()` 可能并发发出同一个列表请求——代价是一次
 * 便宜的 GET，换来的是「不猜格式」。
 */
async function ensureCatalogs() {
  if (session.state.catalogsLoaded) return
  await session.loadCatalogs().catch(() => null)
}

const tab = ref('details')

/**
 * 不需要父组件替它发请求的页签：切过去不触发 load。
 *
 * <p>「血缘」在这里是因为数据由面板自己拉（挂载即查，见 `LineagePanel` 的 `loadGraph`）：
 * 它不是 `useResource` 的一份数据，父组件没有可调的 `load()`。
 */
const SELF_CONTAINED_TABS = new Set(['details', 'access', 'lineage'])

/**
 * 概览：表详情 +（仅 Paimon 表）最新快照。
 *
 * <p>两个请求**串行**，因为要不要问快照得先知道表格式，而表格式就在表详情里
 * （规格的 `GetTableResponse` 没有独立类型字段，只能从 `schema.options.type` 读）。
 * 代价是 Paimon 表首屏多一个往返；换来的是 Format / Object / Lance 表不去打
 * 注定查不到东西的快照端点——那既不产生数据，又会在服务端日志里留下
 * 一串看着像故障的记录。原先这里是把两个请求 `Promise.all` 并发发出去再吞掉错误。
 *
 * <p>`immediate: false`：下面的路由 watch 已经带着 `immediate: true` 在装载时调一次
 * `overview.load()`，这里再由 `onMounted` 触发一次就是每个表详情页都重复发一遍请求——
 * 表详情是控制台里最常打开、也最重的读路径，重复的代价不该由它付。
 */
const overview = useResource(async () => {
  // 先确认 catalog 格式（见 icebergCatalog / ensureCatalogs 的说明）
  await ensureCatalogs()
  if (icebergCatalog.value) {
    // 不打注定被拒的 Paimon 路径；模板会在 `!icebergCatalog` 的那一支里给出说明
    return null
  }
  const detail = await catalogApi.getTable(prefix.value, database.value, table.value)
  const snapshot = isSnapshotManaged(detail)
    ? await catalogApi.tableSnapshot(prefix.value, database.value, table.value).catch(() => null)
    : null
  return { detail, snapshot }
}, { immediate: false })

const table0 = computed(() => overview.data.value?.detail || null)
const latest = computed(() => overview.data.value?.snapshot?.snapshot || null)

/**
 * 表格式：Paimon 表在 options 里没有这个键，另外三种是 Paimon `CoreOptions.TYPE`
 * 的取值（format-table / object-table / lance-table），由建表对话框写入
 * （读取与显示名在 `@/utils/tableKind.js`，目录浏览的列表用同一份）。
 *
 * <p>页签随它收敛。三种非 Paimon 表都不受快照管理——没有快照、标签、分支，
 * 分区状态与文件统计也就无从谈起——所以只保留「详细信息」与「文件列表」，
 * 也不用去问快照端点（见 `overview`）。
 *
 * <p>Object 表的列由 Paimon 固定（建表时不接受用户声明，服务端把
 * `ObjectTable.SCHEMA` 那六列写进 schema），详情页照 schema 展示即可——
 * 展示的是「这张表实际有哪些列」，与当初有没有手填无关。
 */
const tableKind = computed(() => tableKindOf(table0.value))
const kindLabel = computed(() => tableKindLabel(table0.value))
const isPaimonTable = computed(() => isSnapshotManaged(table0.value))

/**
 * 数据预览的列名来源：全量 schema。
 *
 * <p>预览响应里只有**本次投影**的那几列，而「列投影」多选框要能一次点回被隐藏的列，
 * 所以选项来自这里、不是来自响应。类型一并带上——面板据此提前把不可能的算子藏起来
 * （真正判定在服务端，前端只是体贴，见设计文档 §6.1）。
 */
const previewColumns = computed(() =>
  (table0.value?.schema?.fields || []).map((field) => ({ name: field.name, type: field.type })),
)
const isObjectTable = computed(() => tableKind.value === 'object-table')

/** 数据策略编辑器的列选项来源：与预览同一份全量 schema（列名 + 类型）。 */
const policyColumns = computed(() =>
  (table0.value?.schema?.fields || []).map((field) => ({ name: field.name, type: field.type })),
)

/** 少了几个页签的原因写在页面上，免得看起来像加载失败。 */
const kindNote = computed(() => (isPaimonTable.value
  ? ''
  : `${kindLabel.value}（type=${tableKind.value}）不受快照管理：没有快照、标签与分支，也没有提交累计出来的文件统计，因此这里只保留「详细信息」与「文件列表」。`))

/**
 * 快照缺文件统计：那不是「0 个文件」，而是服务端没收到过统计——
 * 客户端提交时没带 statistics（或那一行写在统计落库之前）。两者要分开说。
 */
const missingFileStats = computed(() => {
  const value = latest.value
  return value !== null && value.fileCount == null && value.fileSizeInBytes == null
})

const snapshots = useResource(
  () => catalogApi.listSnapshots(prefix.value, database.value, table.value),
  { immediate: false },
)
const tags = useResource(
  () => catalogApi.listTags(prefix.value, database.value, table.value),
  { immediate: false },
)
const branches = useResource(
  () => catalogApi.listBranches(prefix.value, database.value, table.value),
  { immediate: false },
)

// ------------------------------------------------------------------ 文件列表

/**
 * 表目录的文件列表：路径与前缀是页面状态，不是路由参数（与 CatalogDetailView 同一取舍）。
 *
 * <p>根是表目录而不是仓库——端点（`GET /api/console/v1/tables/{tableId}/files`）
 * 天生列不出表外的东西，下钻出界由服务端挡下；要看整个仓库去 Catalog 详情页。
 * 加载器里判一次 table0：概览还在加载时不会发出 id 为 undefined 的请求。
 *
 * <p>前缀过滤与翻页都在服务端（回车触发一次请求）：这一层有多少条目、第 M 页是什么，
 * 只有列完才知道；本地切片会先被 `max-entries` 截一刀，界面上的总数就比真实值小。
 */
const tableFilePath = ref('')
const tableFilePrefixInput = ref('')
const tableFilePrefix = ref('')

/** 最近一次列表响应：`maxEntries` / `truncated` / `total` 不是行级字段，单独留一份。 */
const tableFileListing = ref(null)
/** 是否成功列过至少一次目录：空态提示只在这个前提下出现，否则和「还没加载」分不开。 */
const tableFilesLoaded = ref(false)

async function fetchTableFiles({ page, pageSize }) {
  // 概览还没回来时没有 tableId，先不发请求（返回空页而不是抛错）
  if (!table0.value) return { items: [], total: 0 }
  const payload = await consoleApi.listTableFiles(
    table0.value.id,
    tableFilePath.value,
    tableFilePrefix.value,
    { page, pageSize },
  )
  tableFileListing.value = payload
  tableFilesLoaded.value = true
  return { items: payload.entries || [], total: payload.page?.total ?? payload.total ?? 0 }
}

const {
  loading: tableFilesLoading,
  error: tableFilesError,
  rows: tableEntries,
  total: totalTableEntries,
  page: tableFilePage,
  pageSize: tableFilePageSize,
  load: loadTableFiles,
  reload: reloadTableFiles,
  onPageChange: onTableFilePageChange,
  onSizeChange: onTableFileSizeChange,
} = useRemotePaging(fetchTableFiles, { immediate: false })

const tableCrumbs = computed(() => {
  const segments = tableFilePath.value.split('/').filter(Boolean)
  return segments.map((name, index) => ({ name, path: segments.slice(0, index + 1).join('/') }))
})

const tableParentPath = computed(
  () => tableCrumbs.value.slice(0, -1).map((crumb) => crumb.name).join('/'),
)

function openTableDirectory(path) {
  tableFilePath.value = path
  // 换了目录就换了集合，页码必须归位（停在第 3 页会看到空表，而目录其实有 5 条）
  reloadTableFiles()
}

/** 回车或点「查询」才发请求：逐字符过滤会把每次按键都变成一次存储列举。 */
function applyTablePrefix() {
  tableFilePrefix.value = tableFilePrefixInput.value.trim()
  reloadTableFiles()
}

/**
 * 下载表目录下的一个文件。路径原样回传（响应里已经是相对表目录的形状），
 * 文件名由服务端的 `Content-Disposition` 给出；失败走同一套错误提示。
 */
async function downloadEntry(row) {
  try {
    await consoleApi.downloadTableFile(table0.value.id, row.path)
  } catch (error) {
    notifyError(describeError(error))
  }
}

const loadedTabs = new Set()

/**
 * 「存储优化」页签的组件引用。
 *
 * <p>这个页签的加载不走 `useResource` 的 `onMounted`：它要等父组件切过来才请求
 * （与其余页签同一条按需加载规则），因此由父组件显式调它的 `load()`。
 * 组件内部自己管策略与历史两份数据，父组件只负责「什么时候加载」。
 */
const optimizationPanel = ref(null)

/**
 * 攒下的那次加载请求。
 *
 * <p>「存储优化」的组件不在 `@tab-change` 那一刻存在：页签内容按 `tab` 渲染，而
 * Element Plus 在 `v-model` 更新后**同步**抛出该事件，此时 DOM 还没更新，
 * `optimizationPanel` 仍是空的——直接调用会被 `?.` 静默吃掉，而 `loadedTabs`
 * 已经记下这个页签，切回来也不会补，页面就永远停在空白。
 *
 * <p>其余页签没这个问题：它们的数据在父组件的 composable 里，不需要等 DOM 挂载。
 */
const optimizationPending = ref(false)

/** 同一套机制服务「数据策略」页签：面板还没挂载时先把请求攒着，挂载后立刻发。 */
const policyPending = ref(false)
const policyPanel = ref(null)

watch(policyPanel, (panel) => {
  if (panel && policyPending.value) {
    policyPending.value = false
    panel.load()
  }
})

function loadPolicy() {
  if (policyPanel.value) {
    policyPanel.value.load()
  } else {
    policyPending.value = true
  }
}

/** 面板挂载后立刻把攒下的请求发出去；已挂载时（切换回来、换表）直接调。 */
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

// ------------------------------------------------------------------ 详细信息：字段列表 / 分区列表

/**
 * 详细信息页里的二级页签。分区仍然按需加载（切过去才请求）：
 * 合并页签不等于把打开页面的成本从两个请求变成三个。
 */
const detailsSection = ref('fields')

/** 字段来自 overview 里的 schema，不过滤时原样展示；分区数据量大，同样本地过滤 + 分页。 */
const schemaFilterInput = ref('')
const filteredFields = computed(() => {
  const keyword = schemaFilterInput.value.trim().toLowerCase()
  const fields = table0.value?.schema?.fields || []
  if (!keyword) return fields
  return fields.filter((field) => field.name.toLowerCase().includes(keyword))
})
// 字段表跟着 `GET .../tables/{t}` 一次性回来，没有列表端点可翻——只做切片
const {
  page: fieldPage,
  pageSize: fieldPageSize,
  rows: fieldRows,
  total: fieldTotal,
  onPageChange: onFieldPageChange,
  onSizeChange: onFieldSizeChange,
} = useEmbeddedPaging(filteredFields)

/**
 * 分区过滤：**前缀**，不是子串。
 *
 * <p>服务端的分区名模式走 {@code Patterns.matches}——SQL LIKE 的「只支持前缀」实现，
 * 于是 `dt=2024` 收成 `dt=2024%`。这与字段表的子串过滤不一样，提示文案里写清楚了：
 * 分区名本来就是 `dt=2024-01-01/hour=12` 这种按键排序的路径，前缀比子串更接近
 * 「我要看 2024 年的分区」这个意图，也不会把关键字中间的 `=` 当成通配。
 */
const partitionFilterInput = ref('')
const partitionPattern = ref('')
const partitionsLoaded = ref(false)

async function fetchPartitions({ page, pageSize }) {
  const payload = await catalogApi.listPartitions(prefix.value, database.value, table.value, {
    partitionNamePattern: partitionPattern.value,
    page,
    pageSize,
  })
  partitionsLoaded.value = true
  return { items: payload.partitions || [], total: payload.total ?? 0 }
}

const {
  loading: partitionsLoading,
  error: partitionsError,
  rows: partitionsRows,
  total: totalPartitions,
  page: partitionPage,
  pageSize: partitionPageSize,
  load: loadPartitions,
  reload: reloadPartitions,
  onPageChange: onPartitionPageChange,
  onSizeChange: onPartitionSizeChange,
} = useRemotePaging(fetchPartitions, { immediate: false })

// 逐字符过滤会把每次按键都变成一次分区查询：300ms 内的连续输入只在最后发一次
let partitionFilterTimer = null
watch(partitionFilterInput, (value) => {
  if (partitionFilterTimer) clearTimeout(partitionFilterTimer)
  partitionFilterTimer = setTimeout(() => {
    const keyword = value.trim()
    partitionPattern.value = keyword ? `${keyword}%` : ''
    // 过滤条件变了，页码必须归位——停在第 3 页会得到一张空表
    reloadPartitions()
  }, 300)
})

function onDetailsSectionChange(name) {
  if (name === 'partitions' && !partitionsLoaded.value && !partitionsLoading.value) {
    reloadPartitions()
  }
}

/** 非空藏在类型串里（`BIGINT NOT NULL`）；嵌套类型是对象，如实按「否」处理。 */
function isNotNull(field) {
  return typeof field.type === 'string' && /not\s+null/i.test(field.type)
}

function isPrimaryKey(field) {
  return (table0.value?.schema?.primaryKeys || []).includes(field.name)
}

function isPartitionKey(field) {
  return (table0.value?.schema?.partitionKeys || []).includes(field.name)
}

function onTabChange(name) {
  // 数据策略 / 存储优化：数据在面板组件实例里，而页签内容是 v-else-if——切走即销毁，
  // 切回来是全新实例（loaded=false、detail=null）。对这两个页签「已加载只拉一次」
  // 不成立，每次切到都要触发 load（面板已挂载就直接调，未挂载攒 pending）。
  // load 是幂等的单表 GET，重复触发的代价是回页签时一次轻量请求，换来的是永远不空白
  if (name === 'policy') { loadPolicy(); return }
  if (name === 'optimization') { loadOptimization(); return }
  if (loadedTabs.has(name)) return
  loadedTabs.add(name)
  if (name === 'snapshots') snapshots.load()
  if (name === 'tags') tags.load()
  if (name === 'branches') branches.load()
  // 文件列表要等概览回来才有 tableId：概览没到时先不请求（加载器会返回 null），
  // 等下次切到这个页签、或概览加载完成后的 reloadAll 再拉
  if (name === 'files' && table0.value && !tableFilesLoaded.value && !tableFilesLoading.value) {
    reloadTableFiles()
  }
}

function reloadAll() {
  overview.load()
  loadedTabs.clear()
  onTabChange(tab.value)
  // 文件列表不在 onTabChange 的首次加载判定里：刷新要真的重新拉一次，
  // 否则刷新按钮在文件页签上什么也不做（数据还在，被判定成「已加载」）
  if (tab.value === 'files') loadTableFiles()
  // 分区列表同理：数据在就重拉，让刷新在这个二级页签上也有效
  if (tab.value === 'details' && detailsSection.value === 'partitions') loadPartitions()
}

watch([prefix, database, table], () => {
  loadedTabs.clear()
  // 换了表，文件列表的浏览位置与过滤条件一并打回初始：旧路径在新表目录里通常不存在，
  // 沿用只会换来一个 404；data 显式置空，否则页签会因为 data 非空而跳过首次加载
  tableFilePath.value = ''
  tableFilePrefix.value = ''
  tableFilePrefixInput.value = ''
  tableFileListing.value = null
  tableFilesLoaded.value = false
  // 分区列表同样换表即作废；二级页签回到「字段列表」（默认落点）
  partitionFilterInput.value = ''
  partitionPattern.value = ''
  partitionsLoaded.value = false
  detailsSection.value = 'fields'
  // 深链接：`?tab=<name>` 直达某个页签（数据保护总览的「详情」用 ?tab=policy 跳进来）。
  // 只认真实存在的页签名，不认识的参数静默忽略——它是便利入口，不是协议
  const requested = String(route.query.tab || '')
  if (['details', 'lineage', 'preview', 'snapshots', 'tags', 'branches', 'files',
    'access', 'policy', 'optimization'].includes(requested)) {
    tab.value = requested
  }
  overview.load()
  // 注意：这里只负责触发，不替 onTabChange 登记 loadedTabs——它的第一行就是
  // 「已登记则跳过」，先 add 再调等于把首次加载静默吞掉（2026-10-25 修复：深链接
  // 落在策略 / 优化等懒加载页签时整页空白，再点页签也不补，因为 loadedTabs 已标记）
  if (!SELF_CONTAINED_TABS.has(tab.value)) {
    onTabChange(tab.value)
  }
}, { immediate: true })

function typeText(type) {
  if (type === null || type === undefined) return '—'
  return typeof type === 'string' ? type : JSON.stringify(type)
}

// ------------------------------------------------------------------ 快照

const snapshotDrawer = reactive({ open: false, title: '', payload: null })
const snapshotVersion = ref('')
const versionLoading = ref(false)

function showSnapshot(row) {
  snapshotDrawer.title = `快照：版本 ${row.version}`
  snapshotDrawer.payload = row
  snapshotDrawer.open = true
}

async function fetchVersion() {
  const version = snapshotVersion.value.trim()
  if (!version) return
  versionLoading.value = true
  try {
    const payload = await catalogApi.getSnapshotByVersion(prefix.value, database.value, table.value, version)
    const snapshot = payload?.snapshot || payload
    snapshotDrawer.title = `快照：版本 ${version}`
    snapshotDrawer.payload = snapshot
    snapshotDrawer.open = true
  } catch (error) {
    notifyError(error)
  } finally {
    versionLoading.value = false
  }
}

/**
 * 回滚到指定快照。
 *
 * <p>这是本页面破坏性最强的操作，确认框里必须说清两件事：目标快照之后的提交会被丢弃，
 * 以及数据文件不会被回收（回滚只是把指针退回去）。第二点尤其容易被误解为「撤销写入」。
 */
async function rollbackToSnapshot(row) {
  const ok = await confirmDanger({
    title: '回滚表',
    target: `把表回滚到快照 ${row.id}（版本 ${row.version}）？`,
    detail: '目标快照之后的全部提交记录会被删除，这些提交无法恢复。'
      + '回滚只移动指针，不会删除已写入的数据文件——它们会变成不再被引用的孤儿文件。'
      + '若有客户端正在写入这张表，请在回滚前先停止写入。',
    confirmText: '回滚',
  })
  if (!ok) return
  await submitRollback({ type: 'snapshot', snapshotId: row.id })
}

async function rollbackToTag(tagName) {
  const ok = await confirmDanger({
    title: '回滚表',
    target: `把表回滚到标签「${tagName}」指向的快照？`,
    detail: '目标快照之后的全部提交记录会被删除，这些提交无法恢复。'
      + '标签本身保留，仍指向它创建时的快照。',
    confirmText: '回滚',
  })
  if (!ok) return
  await submitRollback({ type: 'tag', tagName })
}

async function submitRollback(instant) {
  try {
    await catalogApi.rollbackTable(prefix.value, database.value, table.value, instant)
    notifySuccess('已回滚')
    overview.load()
    snapshots.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 标签

const tagDialog = reactive({
  open: false,
  tagName: '',
  snapshotId: '',
  timeRetained: '',
  ignoreIfExists: true,
  saving: false,
})

function openCreateTag() {
  Object.assign(tagDialog, {
    open: true,
    tagName: '',
    snapshotId: '',
    timeRetained: '',
    ignoreIfExists: true,
  })
}

async function saveTag() {
  if (!tagDialog.tagName.trim()) {
    notifyError(new Error('请填写标签名'))
    return
  }
  tagDialog.saving = true
  try {
    const body = { tagName: tagDialog.tagName.trim() }
    if (tagDialog.snapshotId.trim()) body.snapshotId = Number(tagDialog.snapshotId.trim())
    if (tagDialog.timeRetained.trim()) body.timeRetained = tagDialog.timeRetained.trim()
    body.ignoreIfExists = tagDialog.ignoreIfExists
    await catalogApi.createTag(prefix.value, database.value, table.value, body)
    notifySuccess(`已创建标签 ${tagDialog.tagName.trim()}`)
    tagDialog.open = false
    tags.load()
  } catch (error) {
    notifyError(error)
  } finally {
    tagDialog.saving = false
  }
}

async function confirmDropTag(name) {
  const ok = await confirmDanger({
    title: '删除标签',
    target: `确认删除标签「${name}」？`,
    detail: '删除后，该快照不再受标签保护，快照过期时可能被清理。',
  })
  if (!ok) return
  try {
    await catalogApi.dropTag(prefix.value, database.value, table.value, name)
    notifySuccess(`已删除标签 ${name}`)
    tags.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 分支

const branchDialog = reactive({ open: false, branch: '', fromTag: '', saving: false })

function branchName(row) {
  return row.branchName || row.name || String(row)
}

function openCreateBranch() {
  Object.assign(branchDialog, { open: true, branch: '', fromTag: '' })
}

async function saveBranch() {
  if (!branchDialog.branch.trim()) {
    notifyError(new Error('请填写分支名'))
    return
  }
  branchDialog.saving = true
  try {
    const body = { branch: branchDialog.branch.trim() }
    if (branchDialog.fromTag.trim()) body.fromTag = branchDialog.fromTag.trim()
    await catalogApi.createBranch(prefix.value, database.value, table.value, body)
    notifySuccess(`已创建分支 ${body.branch}`)
    branchDialog.open = false
    branches.load()
  } catch (error) {
    notifyError(error)
  } finally {
    branchDialog.saving = false
  }
}

async function openRenameBranch(name) {
  const next = window.prompt(`把分支「${name}」重命名为`, name)
  if (!next || next === name) return
  try {
    await catalogApi.renameBranch(prefix.value, database.value, table.value, name, { toBranch: next })
    notifySuccess('已重命名分支')
    branches.load()
  } catch (error) {
    notifyError(error)
  }
}

async function forwardBranch(name) {
  const ok = await confirmDanger({
    title: '推进分支',
    target: `把分支「${name}」推进到主分支的最新快照？`,
    detail: '推进后该分支的历史会指向新的快照，原分支上的独立提交将成为不可达的历史。',
    confirmText: '推进',
  })
  if (!ok) return
  try {
    await catalogApi.forwardBranch(prefix.value, database.value, table.value, name, { branch: name })
    notifySuccess('已推进分支')
    branches.load()
  } catch (error) {
    notifyError(error)
  }
}

async function confirmDropBranch(name) {
  const ok = await confirmDanger({
    title: '删除分支',
    target: `确认删除分支「${name}」？`,
    detail: '只删除分支指针，其指向的快照是否保留取决于是否另有标签引用。',
  })
  if (!ok) return
  try {
    await catalogApi.dropBranch(prefix.value, database.value, table.value, name)
    notifySuccess(`已删除分支 ${name}`)
    branches.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 数据访问

const tokenLoading = ref(false)
const tokenError = ref('')
const tokenData = ref(null)

async function fetchToken() {
  tokenLoading.value = true
  tokenError.value = ''
  try {
    tokenData.value = await catalogApi.tableToken(prefix.value, database.value, table.value)
  } catch (error) {
    tokenData.value = null
    tokenError.value = describeError(error)
  } finally {
    tokenLoading.value = false
  }
}

const authSelect = ref('')
const authLoading = ref(false)
const authError = ref('')
const authData = ref(null)

async function fetchAuth() {
  authLoading.value = true
  authError.value = ''
  try {
    const select = authSelect.value
      .split(/[,\n]/)
      .map((item) => item.trim())
      .filter(Boolean)
    authData.value = await catalogApi.tableAuth(
      prefix.value,
      database.value,
      table.value,
      select.length ? { select } : {},
    )
  } catch (error) {
    authData.value = null
    authError.value = describeError(error)
  } finally {
    authLoading.value = false
  }
}

// ------------------------------------------------------------------ 删表

async function confirmDrop() {
  const ok = await confirmDanger({
    title: '删除表',
    target: `确认删除表「${table.value}」？`,
    detail: '元数据与快照历史会被移除，数据文件不会自动清理。',
  })
  if (!ok) return
  try {
    await catalogApi.dropTable(prefix.value, database.value, table.value)
    notifySuccess('已删除表')
    window.location.assign(`/console/browse?catalog=${encodeURIComponent(prefix.value)}&database=${encodeURIComponent(database.value)}`)
  } catch (error) {
    notifyError(error)
  }
}
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

.pc-schema-meta {
  margin-top: 16px;
}

.pc-detail-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(280px, 1fr);
  gap: 16px;
  margin-top: 16px;
}

.pc-version-input {
  width: 150px;
}

.pc-access {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.pc-access-text {
  font-size: 13px;
  line-height: 1.7;
  margin: 0 0 10px;
}

.pc-access-btn {
  margin-top: 8px;
}

.pc-access-error {
  margin-top: 10px;
  color: var(--el-color-danger);
  font-size: 13px;
  line-height: 1.6;
}

.pc-access-json {
  margin-top: 12px;
}

.pc-access-text code {
  padding: 1px 4px;
  background: var(--pc-surface-2);
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
}

@media (max-width: 1080px) {
  .pc-access,
  .pc-detail-grid {
    grid-template-columns: 1fr;
  }
}

/* --- 文件列表页签 -------------------------------------------------------
 * 与 CatalogDetailView 的文件列表同名同形：同一套浏览交互在两个页面各出现一次，
 * 但各自留在自己的视图里（styles/index.css 对页签内边距就是这么约定的）。 */
.pc-file-crumbs {
  font-size: 13.5px;
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

/* 二级页签（详细信息里的字段列表 / 分区列表）：比主页签轻一档，不占 flush 卡的边距 */
.pc-subtabs {
  margin-top: 16px;
}

.pc-subtabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
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

</style>
