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
          <el-tab-pane label="概览" name="overview" />
          <el-tab-pane label="文件列表" name="files" />
          <el-tab-pane label="配置" name="config" />
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
            <p class="pc-tip">
              属性与存储配置的修改走管理 API（<span class="pc-mono">PUT /api/management/v1/catalogs/{{ catalogName }}</span>），
              控制台目前只读展示；编辑界面见「配置」页签的预留说明。
            </p>
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
              empty-text="这个目录里没有文件"
            />

            <el-table v-if="entries.length || files.loading.value" :data="entries" size="small">
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
            </el-table>

            <p class="pc-tip">
              这一页只读：仓库里的对象由引擎与服务端自己管（服务端写 schema 与快照，
              <span class="pc-mono">purge-on-drop</span> 决定删表时清不清目录），
              从这里手工改文件会让「库里说的」与「盘上写的」对不上。
              当前目录相对仓库根为 <span class="pc-mono">{{ filePath || '（仓库根）' }}</span>。
            </p>
          </template>

          <!------------------------------ 配置（预留） ---------------------------->
          <template v-else-if="tab === 'config'">
            <el-alert type="info" :closable="false" show-icon class="pc-hint">
              <template #title>预留设计，暂未实现</template>
              <div>
                这一页规划为 catalog 的可编辑配置，当前只展示计划承载的能力与依赖。
                改动配置的接口已经存在（管理 API 的 <span class="pc-mono">PUT /catalogs/{{ catalogName }}</span>），
                缺的是这一层的界面与它的并发、脱敏处理，取舍见
                <span class="pc-mono">docs/console-catalog-detail.md</span>。
              </div>
            </el-alert>

            <el-table :data="PLANNED_SECTIONS" size="small">
              <el-table-column prop="section" label="分区" width="170" />
              <el-table-column prop="content" label="计划承载" min-width="320" />
              <el-table-column label="现状" width="150">
                <template #default="{ row }">
                  <el-tag size="small" type="info" effect="plain">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="blocker" label="未做的前置依赖" min-width="300" />
            </el-table>

            <p class="pc-tip">
              为什么不现在做：这几项改下去都会被引擎直接读到（<span class="pc-mono">defaults</span> /
              <span class="pc-mono">overrides</span> 参与客户端配置合并），写错的表现是「客户端连不上」或
              「连上了但读到别的仓库」；而写入必须带 <span class="pc-mono">entityVersion</span> 做乐观并发，
              否则两个人同时改会静默覆盖。要先有这三件事：版本冲突的交互、凭据字段的脱敏与回填、
              以及改动前后的差异确认。
            </p>
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
import { managementApi } from '@/api/management.js'
import CopyText from '@/components/CopyText.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import ResourceState from '@/components/ResourceState.vue'
import TimeText from '@/components/TimeText.vue'
import { onConsoleRefresh, useResource } from '@/composables/index.js'
import { formatBytes, inlineMap } from '@/utils/format.js'

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
 * 文件列表：路径是页面状态，不是路由参数。
 *
 * <p>下钻到第几层不那么值得进 URL（刷新回到仓库根是合理的），但**必须**在会话内保留，
 * 否则每次切页签回来都要重新钻一遍。切换 catalog 时重置，见下面的 watch。
 */
const filePath = ref('')
const files = useResource(
  () => consoleApi.listCatalogFiles(catalogName.value, filePath.value),
  { immediate: false },
)
const entries = computed(() => files.data.value?.entries || [])

const crumbs = computed(() => {
  const segments = filePath.value.split('/').filter(Boolean)
  return segments.map((name, index) => ({ name, path: segments.slice(0, index + 1).join('/') }))
})

const parentPath = computed(() => crumbs.value.slice(0, -1).map((crumb) => crumb.name).join('/'))

function openDirectory(path) {
  filePath.value = path
  files.load()
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

/**
 * 「配置」页签里列出的计划能力。
 *
 * <p>写成数据而不是散文：这四项将来会被逐一实现或砍掉，一张表能直接当作验收清单，
 * 而且**未做的前置依赖**这一列会跟着一起改——那正是最容易在实现时忘掉的部分。
 */
const PLANNED_SECTIONS = [
  {
    section: '通用属性',
    content: 'properties 的键值编辑（服务端与运维自己用的标记）',
    status: '只读',
    blocker: '无；接口已支持全量替换',
  },
  {
    section: '客户端配置',
    content: 'defaults / overrides：下发给引擎的默认与强制配置（合并顺序 defaults → 客户端属性 → overrides）',
    status: '只读',
    blocker: '差异确认：改错会让客户端连不上或读错仓库',
  },
  {
    section: '存储配置',
    content: 'storageConfigInfo 的查看与修改（含允许位置、静态凭据）',
    status: '只读',
    blocker: '凭据脱敏与回填：读路径已脱敏，编辑时要做到「留空即保持」',
  },
  {
    section: '并发保护',
    content: '写入带 entityVersion，版本不匹配时提示「有人先改过」并给出差异',
    status: '未实现',
    blocker: '版本冲突的交互设计（服务端已返回 409）',
  },
]
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

@media (max-width: 1100px) {
  .pc-detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
