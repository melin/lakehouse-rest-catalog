<template>
  <div class="pc-page">
    <PageHeader
      :title="`表：${table}`"
      :subtitle="`${prefix} / ${database} / ${table}　这里看到的结构与提交历史就是引擎客户端读写的那一份；删除表不可撤销。`"
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
        <el-button size="small" type="danger" plain @click="confirmDrop">删除表</el-button>
      </template>
    </PageHeader>

    <ResourceState :loading="overview.loading.value" :error="overview.error.value" />

    <template v-if="table0">
      <PanelCard flush>
        <el-tabs v-model="tab" class="pc-tabs" @tab-change="onTabChange">
          <el-tab-pane label="详细信息" name="details" />
          <el-tab-pane label="Schema" name="schema" />
          <el-tab-pane label="变更" name="changes" />
          <el-tab-pane label="快照" name="snapshots" />
          <el-tab-pane label="标签" name="tags" />
          <el-tab-pane label="分支" name="branches" />
          <el-tab-pane label="分区" name="partitions" />
          <el-tab-pane label="文件列表" name="files" />
          <el-tab-pane label="数据访问" name="access" />
        </el-tabs>

        <div class="pc-tab-body">
          <!------------------------------ 详细信息 -------------------------------->
          <template v-if="tab === 'details'">
            <div class="pc-stats pc-stats-tight" v-if="latest">
              <div class="pc-stat">
                <div class="pc-stat-label">记录数（最新快照）</div>
                <div class="pc-stat-value">{{ formatNumber(latest.recordCount) }}</div>
                <div class="pc-stat-foot">累计写入，含历史文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件数</div>
                <div class="pc-stat-value">{{ formatNumber(latest.fileCount) }}</div>
                <div class="pc-stat-foot">最新快照引用的文件</div>
              </div>
              <div class="pc-stat">
                <div class="pc-stat-label">数据文件大小</div>
                <div class="pc-stat-value">{{ formatBytes(latest.fileSizeInBytes) }}</div>
                <div class="pc-stat-foot">不含快照与清单文件</div>
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
                <dd>{{ table0.isExternal ? '是（注册进来的表）' : '否（由本服务管理）' }}</dd>
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
              </dl>
              <JsonBlock
                :value="table0.schema?.options || {}"
                label="表属性（options）"
                empty-text="没有表属性"
                max-height="300px"
              />
            </div>
          </template>

          <!-------------------------------- Schema -------------------------------->
          <template v-else-if="tab === 'schema'">
            <el-table :data="table0.schema?.fields || []" size="small">
              <el-table-column prop="id" label="ID" width="64" align="right" />
              <el-table-column prop="name" label="字段名" min-width="160" />
              <el-table-column label="类型" min-width="180">
                <template #default="{ row }">
                  <span class="pc-mono">{{ typeText(row.type) }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="description" label="注释" min-width="140" />
              <el-table-column prop="defaultValue" label="默认值" min-width="110" />
            </el-table>

            <dl class="pc-kv pc-schema-meta">
              <dt>分区键</dt>
              <dd>{{ (table0.schema?.partitionKeys || []).join(', ') || '（未分区）' }}</dd>
              <dt>主键</dt>
              <dd>{{ (table0.schema?.primaryKeys || []).join(', ') || '（无主键表）' }}</dd>
              <dt>表注释</dt>
              <dd>{{ orPlaceholder(table0.schema?.comment) }}</dd>
            </dl>
          </template>

          <!-------------------------------- 变更 ---------------------------------->
          <template v-else-if="tab === 'changes'">
            <el-alert type="info" :closable="false" show-icon class="pc-hint">
              <template #title>变更会一次性提交</template>
              <div>
                服务端的 alter 接口接收一组变更并原子应用。先在这里逐条添加，
                确认无误后再提交——提交后无法撤销，只能反向再做一次变更。
              </div>
            </el-alert>

            <div class="pc-change-form">
              <el-select v-model="builder.action" size="small" class="pc-change-action">
                <el-option
                  v-for="option in CHANGE_ACTIONS"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <template v-for="field in builderFields" :key="field.key">
                <el-checkbox v-if="field.type === 'bool'" v-model="builder.values[field.key]" size="small">
                  {{ field.label }}
                </el-checkbox>
                <el-input
                  v-else
                  v-model="builder.values[field.key]"
                  size="small"
                  :placeholder="field.label"
                  class="pc-change-input"
                />
              </template>
              <el-button size="small" type="primary" @click="addChange">
                <el-icon><Plus /></el-icon>
                <span class="pc-btn-text">添加</span>
              </el-button>
            </div>

            <el-table :data="pendingChanges" size="small" empty-text="还没有待提交的变更">
              <el-table-column label="变更" min-width="420">
                <template #default="{ row }">
                  <span class="pc-mono pc-break">{{ compact(row) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70" align="right">
                <template #default="{ $index }">
                  <IconAction
                    icon="Close"
                    label="移除"
                    type="danger"
                    @click="pendingChanges.splice($index, 1)"
                  />
                </template>
              </el-table-column>
            </el-table>

            <div class="pc-change-foot">
              <el-button size="small" :disabled="!pendingChanges.length" @click="pendingChanges = []">
                清空
              </el-button>
              <el-button
                size="small"
                type="primary"
                :loading="submitting"
                :disabled="!pendingChanges.length"
                @click="submitChanges"
              >
                提交 {{ pendingChanges.length }} 项变更
              </el-button>
            </div>
          </template>

          <!-------------------------------- 快照 ---------------------------------->
          <template v-else-if="tab === 'snapshots'">
            <el-alert type="warning" :closable="false" show-icon class="pc-hint">
              <template #title>回滚会删除目标之后的提交记录</template>
              <div>
                回滚把最新快照指针退回到选定的快照，其后的提交记录随即被删除，无法恢复。
                数据文件不会被回收，它们会变成不再被引用的孤儿文件。
              </div>
            </el-alert>
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
              <el-table-column label="操作" width="160" align="right">
                <template #default="{ row }">
                  <IconAction icon="EditPen" label="重命名" @click="openRenameBranch(branchName(row))" />
                  <IconAction icon="DArrowRight" label="推进到最新" @click="forwardBranch(branchName(row))" />
                  <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropBranch(branchName(row))" />
                </template>
              </el-table-column>
            </el-table>
          </template>

          <!-------------------------------- 分区 ---------------------------------->
          <template v-else-if="tab === 'partitions'">
            <el-alert type="info" :closable="false" show-icon class="pc-hint">
              <template #title>这里的统计口径是最新快照的活跃数据</template>
              <div>分区级统计只计算被最新快照引用的文件，因此各分区之和可能小于仓库实际占用。</div>
            </el-alert>
            <el-table
              v-loading="partitions.loading.value"
              :data="partitions.data.value?.partitions || []"
              size="small"
              empty-text="这张表没有分区，或还没有数据"
            >
              <el-table-column label="分区" min-width="230">
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
              v-if="tableFiles.data.value?.truncated"
              type="warning"
              :closable="false"
              show-icon
              class="pc-hint"
            >
              <template #title>只显示了前 {{ tableFiles.data.value.maxEntries }} 条（共 {{ tableFiles.data.value.total }} 条）</template>
              <div>
                排序是目录优先、名称升序，因此被截掉的是尾部的文件。下钻到更具体的子目录，
                或调大 <span class="pc-mono">paimon.rest.file-browser.max-entries</span>。
              </div>
            </el-alert>

            <ResourceState
              :loading="tableFiles.loading.value"
              :error="tableFiles.error.value"
              :empty="!tableEntries.length"
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
              <span class="pc-spacer" />
              <span class="pc-dim">
                共 {{ tableEntries.length }} 条<span v-if="tableFiles.data.value?.truncated">（已达上限
                {{ tableFiles.data.value.maxEntries }}，用前缀过滤缩小范围）</span>
              </span>
            </div>

            <el-table v-if="tableEntries.length || tableFiles.loading.value" :data="pagedTableEntries" size="small">
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
              <el-table-column label="操作" width="90" align="center">
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

            <el-pagination
              v-if="tableEntries.length > tableFilePageSize"
              v-model:current-page="tableFilePage"
              v-model:page-size="tableFilePageSize"
              :page-sizes="[10, 50, 100, 200, 500]"
              :total="totalTableEntries"
              layout="total, sizes, prev, pager, next"
              size="small"
              class="pc-pager"
            />

            <p class="pc-tip">
              这里只看这张表自己目录下的文件，出不了表目录（服务端按表目录为根拦住了
              <span class="pc-mono">..</span> 与绝对路径）。要浏览整个仓库（包括库里其它表与残留目录），
              去目录（Catalog）详情页的「文件列表」。当前目录相对表目录为
              <span class="pc-mono">{{ tableFilePath || '（表目录）' }}</span>。
            </p>
          </template>

          <!------------------------------- 数据访问 -------------------------------->
          <template v-else>
            <div class="pc-access">
              <PanelCard title="数据访问令牌" hint="GET .../token">
                <p class="pc-dim pc-access-text">
                  服务端按 catalog 的存储配置下发临时凭据。若服务端以
                  <code class="pc-mono">paimon.rest.credential-manager.type=noop</code> 运行，
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
import { computed, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import CopyText from '@/components/CopyText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import { catalogApi } from '@/api/catalog.js'
import { consoleApi } from '@/api/console.js'
import { describeError } from '@/api/client.js'
import { confirmDanger, notifyError, notifySuccess, useLocalPaging, useResource } from '@/composables/index.js'
import {
  formatBytes,
  formatNumber,
  inlineMap,
  orPlaceholder,
  relativeTime,
} from '@/utils/format.js'

/**
 * 表详情。九个页签：七个对应 catalog API 里以单张表为作用域的端点，
 * 另有两个组合页签——「详细信息」汇总单表元数据（基本信息 + 快照统计 + options），
 * 「文件列表」看表目录在存储上的物理内容（根是表目录，出不了表外）。
 *
 * <p>授权不在这张表上做：表级权限统一到「权限管理」页（按 database + table 定位，
 * 一级资源一个表单），这里不再重复一份 catalog 角色的授权界面。
 *
 * <p>「详细信息」「Schema」「变更」「数据访问」只用 overview 已有数据，
 * 其余页签按需加载：切到某个页签才发它的请求。
 * 打开一张表就发两个请求，在远端仓库上会让页面明显变慢。
 */

const route = useRoute()

const prefix = computed(() => route.params.prefix)
const database = computed(() => route.params.database)
const table = computed(() => route.params.table)

const tab = ref('details')

/** 不需要额外请求的页签：切过去不触发 load。 */
const SELF_CONTAINED_TABS = new Set(['details', 'schema', 'changes', 'access'])

const overview = useResource(async () => {
  const [detail, snapshot] = await Promise.all([
    catalogApi.getTable(prefix.value, database.value, table.value),
    catalogApi.tableSnapshot(prefix.value, database.value, table.value).catch(() => null),
  ])
  return { detail, snapshot }
})

const table0 = computed(() => overview.data.value?.detail || null)
const latest = computed(() => overview.data.value?.snapshot?.snapshot || null)

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
const partitions = useResource(
  () => catalogApi.listPartitions(prefix.value, database.value, table.value),
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
 * <p>前缀过滤走服务端（回车触发一次请求）：过滤后的结果还要按同一个
 * `max-entries` 上限截断；翻页在本地做（服务端一次给一批，默认上限 1000 条）。
 */
const tableFilePath = ref('')
const tableFilePrefixInput = ref('')
const tableFilePrefix = ref('')
const tableFiles = useResource(() => {
  if (!table0.value) return Promise.resolve(null)
  return consoleApi.listTableFiles(table0.value.id, tableFilePath.value, tableFilePrefix.value)
}, { immediate: false })
const tableEntries = computed(() => tableFiles.data.value?.entries || [])
const {
  page: tableFilePage,
  pageSize: tableFilePageSize,
  paged: pagedTableEntries,
  total: totalTableEntries,
} = useLocalPaging(tableEntries)

const tableCrumbs = computed(() => {
  const segments = tableFilePath.value.split('/').filter(Boolean)
  return segments.map((name, index) => ({ name, path: segments.slice(0, index + 1).join('/') }))
})

const tableParentPath = computed(
  () => tableCrumbs.value.slice(0, -1).map((crumb) => crumb.name).join('/'),
)

function openTableDirectory(path) {
  tableFilePath.value = path
  tableFiles.load()
}

/** 回车或点「查询」才发请求：逐字符过滤会把每次按键都变成一次存储列举。 */
function applyTablePrefix() {
  tableFilePrefix.value = tableFilePrefixInput.value.trim()
  tableFiles.load()
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

function onTabChange(name) {
  if (loadedTabs.has(name)) return
  loadedTabs.add(name)
  if (name === 'snapshots') snapshots.load()
  if (name === 'tags') tags.load()
  if (name === 'branches') branches.load()
  if (name === 'partitions') partitions.load()
  // 文件列表要等概览回来才有 tableId：概览没到时先不请求（加载器会返回 null），
  // 等下次切到这个页签、或概览加载完成后的 reloadAll 再拉
  if (name === 'files' && table0.value && !tableFiles.data.value && !tableFiles.loading.value) {
    tableFiles.load()
  }
}

function reloadAll() {
  overview.load()
  loadedTabs.clear()
  onTabChange(tab.value)
  // 文件列表不在 onTabChange 的首次加载判定里：刷新要真的重新拉一次，
  // 否则刷新按钮在文件页签上什么也不做（数据还在，被判定成「已加载」）
  if (tab.value === 'files') tableFiles.load()
}

watch([prefix, database, table], () => {
  loadedTabs.clear()
  // 换了表，文件列表的浏览位置与过滤条件一并打回初始：旧路径在新表目录里通常不存在，
  // 沿用只会换来一个 404；data 显式置空，否则页签会因为 data 非空而跳过首次加载
  tableFilePath.value = ''
  tableFilePrefix.value = ''
  tableFilePrefixInput.value = ''
  tableFiles.data.value = null
  tableFiles.error.value = ''
  overview.load()
  if (!SELF_CONTAINED_TABS.has(tab.value)) {
    loadedTabs.add(tab.value)
    onTabChange(tab.value)
  }
}, { immediate: true })

function typeText(type) {
  if (type === null || type === undefined) return '—'
  return typeof type === 'string' ? type : JSON.stringify(type)
}

// ------------------------------------------------------------------ 变更

const CHANGE_ACTIONS = [
  { value: 'setOption', label: '设置表属性（setOption）', fields: [
    { key: 'key', label: '键' }, { key: 'value', label: '值' }] },
  { value: 'removeOption', label: '删除表属性（removeOption）', fields: [{ key: 'key', label: '键' }] },
  { value: 'updateComment', label: '修改表注释（updateComment）', fields: [{ key: 'comment', label: '注释' }] },
  { value: 'addColumn', label: '新增列（addColumn）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'dataType', label: '类型，如 BIGINT' },
    { key: 'comment', label: '注释（可选）' }] },
  { value: 'dropColumn', label: '删除列（dropColumn）', fields: [{ key: 'fieldName', label: '列名' }] },
  { value: 'renameColumn', label: '重命名列（renameColumn）', fields: [
    { key: 'fieldName', label: '原列名' }, { key: 'newName', label: '新列名' }] },
  { value: 'updateColumnType', label: '修改列类型（updateColumnType）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'newDataType', label: '新类型' }] },
  { value: 'updateColumnComment', label: '修改列注释（updateColumnComment）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'newComment', label: '新注释' }] },
  { value: 'updateColumnNullability', label: '修改列可空性（updateColumnNullability）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'newNullability', label: '允许为空', type: 'bool' }] },
  { value: 'move', label: '调整列位置（updateColumnPosition）', fields: [
    { key: 'fieldName', label: '列名' }, { key: 'referenceFieldName', label: '参照列' }] },
]

const builder = reactive({ action: 'setOption', values: {} })

const builderFields = computed(
  () => CHANGE_ACTIONS.find((action) => action.value === builder.action)?.fields || [],
)

const pendingChanges = ref([])
const submitting = ref(false)

/** 按动作名把表单值收敛成规格里的 `SchemaChange` 形状。 */
function toChange(action, values) {
  const text = (key) => String(values[key] ?? '').trim()
  switch (action) {
    case 'setOption':
      return { action, key: text('key'), value: text('value') }
    case 'removeOption':
      return { action, key: text('key') }
    case 'updateComment':
      return { action, comment: text('comment') || null }
    case 'addColumn': {
      const change = { action, fieldNames: [text('fieldName')], dataType: text('dataType') }
      if (text('comment')) change.comment = text('comment')
      return change
    }
    case 'dropColumn':
      return { action, fieldNames: [text('fieldName')] }
    case 'renameColumn':
      return { action, fieldNames: [text('fieldName')], newName: text('newName') }
    case 'updateColumnType':
      return { action, fieldNames: [text('fieldName')], newDataType: text('newDataType') }
    case 'updateColumnComment':
      return { action, fieldNames: [text('fieldName')], newComment: text('newComment') }
    case 'updateColumnNullability':
      return { action, fieldNames: [text('fieldName')], newNullability: Boolean(values.newNullability) }
    case 'move':
      return { action: 'updateColumnPosition', move: {
        fieldName: text('fieldName'),
        referenceFieldName: text('referenceFieldName'),
        type: 'after',
      } }
    default:
      return { action }
  }
}

/** 变更项的必填校验：缺了关键字段就在前端拦住，不要留到服务端报 400。 */
function addChange() {
  const fields = builderFields.value.filter((field) => field.type !== 'bool'
    && !field.label.includes('可选')
    && !['comment', 'newComment', 'value'].includes(field.key))
  for (const field of fields) {
    if (!String(builder.values[field.key] ?? '').trim()) {
      notifyError(new Error(`请填写「${field.label}」`))
      return
    }
  }
  pendingChanges.value.push(toChange(builder.action, builder.values))
  builder.values = {}
}

function compact(change) {
  const rest = { ...change }
  delete rest.action
  return `${change.action} ${JSON.stringify(rest)}`
}

async function submitChanges() {
  submitting.value = true
  try {
    await catalogApi.alterTable(prefix.value, database.value, table.value, {
      changes: pendingChanges.value,
    })
    notifySuccess(`已提交 ${pendingChanges.value.length} 项变更`)
    pendingChanges.value = []
    builder.values = {}
    overview.load()
  } catch (error) {
    notifyError(error)
  } finally {
    submitting.value = false
  }
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

.pc-change-form {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 14px;
}

.pc-change-action {
  width: 260px;
}

.pc-change-input {
  width: 180px;
}

.pc-change-foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;
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
  border-radius: 4px;
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

/* 分页条靠右；只在条目多于一页时才渲染（见模板里的 v-if） */
.pc-pager {
  margin-top: 12px;
  justify-content: flex-end;
}
</style>
