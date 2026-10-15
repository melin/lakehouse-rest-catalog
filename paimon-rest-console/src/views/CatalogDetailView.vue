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
                「配置」页签能改其中的 <code>table-default.*</code>（新建 Paimon 表的默认参数），
                其余属性与控制台的编辑弹窗在「Catalogs」页。
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
          <el-tab-pane name="config">
            <template #label>
              配置
              <TipNote>
                为什么还不做：这几项改下去都会被引擎直接读到（<code>defaults</code> /
                <code>overrides</code> 参与客户端配置合并），写错的表现是「客户端连不上」或
                「连上了但读到别的仓库」；而写入必须带 <code>entityVersion</code> 做乐观并发，
                否则两个人同时改会静默覆盖。要先有这三件事：版本冲突的交互、凭据字段的脱敏与回填、
                以及改动前后的差异确认。
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
              v-if="files.data.value?.truncated"
              type="warning"
              :closable="false"
              show-icon
              class="pc-hint"
            >
              <template #title>只显示了前 {{ files.data.value.maxEntries }} 条（共 {{ files.data.value.total }} 条）</template>
              <div>
                排序是目录优先、名称升序，因此被截掉的是尾部的文件。需要看全部的话，
                下钻到更具体的子目录，或调大 <span class="pc-mono">paimon.rest.file-browser.max-entries</span>。
              </div>
            </el-alert>

            <ResourceState
              :loading="files.loading.value"
              :error="files.error.value"
              :empty="!entries.length"
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
                共 {{ entries.length }} 条<span v-if="files.data.value?.truncated">（已达上限
                {{ files.data.value.maxEntries }}，用前缀过滤缩小范围）</span>
              </span>
            </div>

            <el-table v-if="entries.length || files.loading.value" :data="pagedEntries" size="small">
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
              <el-table-column label="操作" width="90" align="center">
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

            <el-pagination
              v-if="entries.length > filePageSize"
              v-model:current-page="filePage"
              v-model:page-size="filePageSize"
              :page-sizes="[10, 50, 100, 200, 500]"
              :total="totalEntries"
              layout="total, sizes, prev, pager, next"
              size="small"
              class="pc-pager"
            />
          </template>

          <!-------------------------------- 配置 ---------------------------------->
          <template v-else-if="tab === 'config'">
            <SectionTitle title="Paimon 表默认参数（table-default.*）">
              <template #tip>
                <TipNote>
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

            <SectionTitle title="其它属性（properties）">
              <template #tip>
                <TipNote>
                  本页只编辑 <code>table-default.*</code>。其余属性在这里只读展示，
                  保存时会<strong>原样回填</strong>（管理 API 的更新是全量替换），
                  因此不会因为这一页而丢失别的键；若保存期间有人改过它们，
                  服务端会以版本冲突（409）拒绝——宁可不写，也不静默覆盖。
                </TipNote>
              </template>
            </SectionTitle>

            <el-table :data="otherProperties" size="small">
              <el-table-column prop="key" label="属性名" min-width="240" />
              <el-table-column prop="value" label="取值" min-width="240">
                <template #default="{ row }">
                  <span class="pc-mono pc-break">{{ row.value || '—' }}</span>
                </template>
              </el-table-column>
              <template #empty>这个 catalog 没有其它属性</template>
            </el-table>
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
import { managementApi } from '@/api/management.js'
import CopyText from '@/components/CopyText.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import { notifyError, notifySuccess, onConsoleRefresh, useLocalPaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { formatBytes, inlineMap } from '@/utils/format.js'
import { composeProperties, isTableDefaultKey, otherPropertiesOf, tableDefaultsOf } from '@/utils/tableDefaults.js'

const route = useRoute()

/** catalog 的标识就是路径里的 prefix，页面本身不改它（改名等同删除重建）。 */
const catalogName = computed(() => route.params.catalogName || '')

const tab = ref('overview')

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
 * 文件列表：路径与前缀都是页面状态，不是路由参数。
 *
 * <p>下钻到第几层不那么值得进 URL（刷新回到仓库根是合理的），但**必须**在会话内保留，
 * 否则每次切页签回来都要重新钻一遍。切换 catalog 时重置，见下面的 watch。
 *
 * <p><b>前缀过滤走服务端</b>（回车触发一次请求），因为过滤后的结果还要按同一个
 * `max-entries` 上限截断；**翻页在本地做**——服务端一次给一批（默认上限 1000 条），
 * 在浏览器里切片足够，且列目录本来就要把这一层读完才知道总数与排序。
 */
const filePath = ref('')
const filePrefixInput = ref('')
const filePrefix = ref('')
const files = useResource(
  () => consoleApi.listCatalogFiles(catalogName.value, filePath.value, filePrefix.value),
  { immediate: false },
)
const entries = computed(() => files.data.value?.entries || [])
const { page: filePage, pageSize: filePageSize, paged: pagedEntries, total: totalEntries } =
  useLocalPaging(entries)

const crumbs = computed(() => {
  const segments = filePath.value.split('/').filter(Boolean)
  return segments.map((name, index) => ({ name, path: segments.slice(0, index + 1).join('/') }))
})

const parentPath = computed(() => crumbs.value.slice(0, -1).map((crumb) => crumb.name).join('/'))

function openDirectory(path) {
  filePath.value = path
  files.load()
}

/** 回车或点「查询」才发请求：逐字符过滤会把每次按键都变成一次存储列举。 */
function applyPrefix() {
  filePrefix.value = filePrefixInput.value.trim()
  files.load()
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
  // data 显式置空，否则「文件列表」页签会因为 data 非空而跳过首次加载
  files.data.value = null
  files.error.value = ''
  overview.load()
  if (tab.value === 'files') {
    files.load()
  }
})

/**
 * 首次切到「文件列表」时才请求：概览页自己就能回答大部分问题，
 * 而列目录要打一次存储（对象存储上是一次前缀列举），不该在打开页面时就付这个代价。
 */
function onTabChange(name) {
  if (name === 'files' && !files.data.value && !files.loading.value) {
    files.load()
  }
}

function reloadAll() {
  overview.load()
  if (tab.value === 'files') {
    files.load()
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

/** 只读展示的其它属性；表格不接受对象，转成数组。 */
const otherProperties = computed(() =>
  Object.entries(otherPropertiesOf(catalog0.value?.properties)).map(([key, value]) => ({ key, value })));

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

/* 分页条靠右；只在条目多于一页时才渲染（见模板里的 v-if） */
.pc-pager {
  margin-top: 12px;
  justify-content: flex-end;
}

@media (max-width: 1100px) {
  .pc-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
