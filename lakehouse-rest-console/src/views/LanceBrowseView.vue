<template>
  <div class="pc-page pc-browse">
    <PageHeader
      title="Catalog"
      :subtitle="`浏览 Lance catalog「${prefix || '未选择'}」下的命名空间与表。读的是 Lance Namespace REST（/lance/v1/namespace/{id}），与客户端看到的是同一份登记。本页只读：建表与写数据都在客户端。`"
    >
      <template #actions>
        <el-button size="small" :loading="namespaces.loading.value || tables.loading.value" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
      </template>
    </PageHeader>

    <TipAlert v-if="!prefix" type="warning" class="pc-hint">
      <template #title>还没有可浏览的 catalog</template>
      <div>先在「Catalogs」里创建一个 Lance 格式的 catalog。</div>
    </TipAlert>

    <div v-else class="pc-browse-body">
      <!-- 左：命名空间。Lance 只有两级，面板里就是全部（`catalog$schema`） -->
      <aside class="pc-db-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">命名空间</span>
          <span class="pc-dim">{{ namespaceRows.length }}</span>
        </div>
        <div class="pc-pane-tools">
          <el-input v-model="nsFilter" size="small" placeholder="过滤命名空间" clearable>
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <div class="pc-db-list pc-scroll">
          <el-skeleton v-if="namespaces.loading.value" :rows="4" animated class="pc-db-skeleton" />
          <el-alert
            v-else-if="namespaces.error.value"
            type="error"
            :closable="false"
            show-icon
            :title="namespaces.error.value"
          />
          <el-empty
            v-else-if="!filteredNamespaces.length"
            :image-size="54"
            :description="nsFilter.trim()
              ? `没有匹配「${nsFilter.trim()}」的命名空间`
              : '这个 catalog 下还没有命名空间'"
          />
          <template v-else>
            <div
              v-for="row in filteredNamespaces"
              :key="row.id"
              class="pc-db-item"
              :class="{ 'is-active': row.id === selected }"
              :title="row.id"
              @click="selected = row.id"
            >
              <el-icon class="pc-db-icon"><Folder /></el-icon>
              <span class="pc-db-name">{{ row.label }}</span>
            </div>
          </template>
        </div>
      </aside>

      <!-- 右：表清单。ListTables 只回 id、不回位置与版本，那些在抽屉里按需读（见 api/lance.js 的注释） -->
      <PanelCard class="pc-obj-pane" flush>
        <div class="pc-pane-head">
          <span class="pc-pane-title">表</span>
          <span class="pc-dim">{{ tableRows.length }}</span>
          <el-input
            v-model="tableFilter"
            size="small"
            placeholder="过滤表名"
            clearable
            class="pc-lance-filter"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>

        <div v-loading="tables.loading.value" class="pc-lance-tables">
          <el-alert
            v-if="tables.error.value"
            type="error"
            :closable="false"
            show-icon
            :title="tables.error.value"
          />
          <el-empty
            v-else-if="!filteredTables.length"
            :image-size="70"
            :description="emptyText"
          >
            <div class="pc-lance-empty-note">
              表由客户端登记（<code class="pc-mono">declare</code> / <code class="pc-mono">register</code>），
              本服务端不写数据集，因此这里没有「新建表」。
              想知道盘上有什么，去 catalog 详情的「文件列表」看 <code class="pc-mono">_versions/</code> 与
              <code class="pc-mono">data/</code>。
            </div>
          </el-empty>
          <template v-else>
            <div
              v-for="row in filteredTables"
              :key="row.id"
              class="pc-lance-table"
              :title="row.id"
              @click="openDetail(row.id)"
            >
              <el-icon class="pc-lance-table-icon"><Grid /></el-icon>
              <span class="pc-db-name">{{ row.label }}</span>
              <span class="pc-dim pc-lance-table-more">详情</span>
            </div>
          </template>
        </div>
      </PanelCard>
    </div>

    <!-- 详情抽屉：先读一次便宜的 describe（位置 / 属性 / 是否只有登记），
         manifest 里的版本与 schema 要用户点了才读 -->
    <el-drawer
      v-model="detail.open"
      :title="detail.name ? `表：${detail.name}` : '表详情'"
      size="620px"
      destroy-on-close
    >
      <div v-loading="detail.loading" class="pc-lance-detail">
        <el-alert
          v-if="detail.error"
          type="error"
          :closable="false"
          show-icon
          :title="detail.error"
        />
        <template v-else-if="detail.data">
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="id">
              <span class="pc-mono">{{ detail.data.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="位置">
              <span class="pc-mono">{{ detail.data.location || '—' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag v-if="detail.data.is_only_declared" type="info" size="small">只登记，存储里还没有数据集</el-tag>
              <el-tag v-else-if="detail.data.is_only_declared === false" type="success" size="small">
                存储里已有数据集
              </el-tag>
              <span v-else class="pc-dim">未探测</span>
            </el-descriptions-item>
            <el-descriptions-item v-if="detail.data.managed_versioning" label="托管版本">
              是（版本由服务端记账，见「版本」页）
            </el-descriptions-item>
          </el-descriptions>

          <SectionTitle title="属性（properties）">
            <template #tip>
              <TipNote>
                登记时客户端给的透传属性，原样存在元数据里。控制台不解析、也不校验它们。
              </TipNote>
            </template>
          </SectionTitle>
          <el-table v-if="propertyRows.length" :data="propertyRows" size="small" border>
            <el-table-column prop="key" label="键" min-width="160" />
            <el-table-column prop="value" label="值" min-width="220" show-overflow-tooltip />
          </el-table>
          <div v-else class="pc-dim pc-lance-none">没有属性。</div>

          <SectionTitle title="版本与 schema">
            <template #tip>
              <TipNote>
                这两项要读仓库里的最新 manifest（<code>load_detailed_metadata=true</code>），
                一次请求换一次读盘，所以默认不做。只登记过、还没有数据集的表会失败——
                那是规范允许的结果，不是页面出错。
              </TipNote>
            </template>
          </SectionTitle>

          <el-button
            v-if="!detail.detailed && !detail.detailedError"
            size="small"
            :loading="detail.detailedLoading"
            @click="loadDetailed"
          >
            <el-icon><Refresh /></el-icon>
            <span class="pc-btn-text">读取详细元数据</span>
          </el-button>

          <el-alert
            v-if="detail.detailedError"
            type="info"
            :closable="false"
            show-icon
            :title="detail.detailedError"
          />

          <template v-if="detail.detailed">
            <el-descriptions :column="2" border size="small" class="pc-lance-gap">
              <el-descriptions-item label="版本">
                {{ detail.detailed.version ?? '—' }}
              </el-descriptions-item>
              <el-descriptions-item label="分片 / 删除行">
                {{ detail.detailed.stats?.num_fragments ?? '—' }} /
                {{ detail.detailed.stats?.num_deleted_rows ?? '—' }}
              </el-descriptions-item>
            </el-descriptions>
            <JsonBlock :value="detail.detailed.schema" label="schema" empty-text="manifest 里没有 schema" />
          </template>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'

import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TipAlert from '@/components/TipAlert.vue'
import TipNote from '@/components/TipNote.vue'
import { lanceApi, lanceLeaf } from '@/api/lance.js'
import { describeError } from '@/api/client.js'
import { onConsoleRefresh, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * Lance catalog 的目录浏览（**只读**）。
 *
 * <p>为什么与 Paimon / Iceberg 两页都不共用一个组件：Lance 的 id 是拼接串
 * （`catalog$schema$table`）、列举只回名字、没有 `/v1/config` 握手。这些差异
 * 不是参数级的，硬塞进一个组件会让每处都是 `v-if`。
 *
 * <p>为什么页面除了「刷新」没有任何按钮：这台服务端对 Lance 没有写入路径
 * （不建表、不写数据集，见 `docs/lance-rest-catalog-design.md` §3.2）。declare /
 * register / deregister 都由客户端发起；页面给出这些入口，等于让人以为
 * 「在这里建的表」和「盘上的数据集」是一回事，而它们的错位要几个小时后才显形。
 */

const route = useRoute()

/** 与 Paimon / Iceberg 两页同一个取法：URL 上的 catalog 优先，其次是顶栏选中的。 */
const prefix = computed(() => route.query.catalog || session.state.selectedCatalog || '')

const nsFilter = ref('')
const tableFilter = ref('')
const selected = ref('')

const namespaces = useResource(() => lanceApi.listNamespaces(prefix.value), { immediate: false })

/** 命名空间面板的一行：显示末段，`title` 里留完整 id（显示名是有损的）。 */
const namespaceRows = computed(() => (namespaces.data.value?.namespaces ?? []).map((item) => ({
  id: item.id,
  label: lanceLeaf(item.id),
})))

const filteredNamespaces = computed(() => {
  const keyword = nsFilter.value.trim().toLowerCase()
  if (!keyword) return namespaceRows.value
  return namespaceRows.value.filter((row) => row.label.toLowerCase().includes(keyword))
})

const tables = useResource(
  () => (selected.value ? lanceApi.listTables(selected.value) : Promise.resolve({ tables: [] })),
  { immediate: false },
)

/**
 * 表清单。
 *
 * <p><b>`ListTables` 回的是完整 id</b>（`catalog$schema$table`），不是短名——
 * 与服务端 `LanceTableService.list` 的实现一致。名字仍然是**末段**，完整 id 放在
 * `title` 与 describe 的入参里：直接拿显示名去调 describe 会拼出
 * `cat$db$cat$db$tbl` 这种解不出来的 id。
 */
const tableRows = computed(() => (tables.data.value?.tables ?? []).map((id) => ({
  id,
  label: lanceLeaf(id),
})))
const filteredTables = computed(() => {
  const keyword = tableFilter.value.trim().toLowerCase()
  if (!keyword) return tableRows.value
  return tableRows.value.filter((row) => row.label.toLowerCase().includes(keyword))
})

const emptyText = computed(() => {
  if (tableFilter.value.trim()) return `没有匹配「${tableFilter.value.trim()}」的表`
  if (!selected.value) return '先在左边选一个命名空间'
  return '这个命名空间下还没有登记表'
})

// 切换 catalog：清掉选择，重新拉命名空间
watch(prefix, (value) => {
  selected.value = ''
  if (value) namespaces.load()
}, { immediate: true })

// 命名空间到手（或刷新后条数变了）：选中项失效就落到第一个
watch(namespaceRows, (rows) => {
  if (!rows.some((row) => row.id === selected.value)) {
    selected.value = rows[0]?.id || ''
  }
}, { immediate: true })

// 切换命名空间：重新拉表清单
watch(selected, (value) => {
  if (value) tables.load()
})

const detail = ref({
  open: false,
  name: '',
  id: '',
  loading: false,
  error: '',
  data: null,
  detailed: null,
  detailedLoading: false,
  detailedError: '',
})

const propertyRows = computed(() => Object.entries(detail.value.data?.properties ?? {})
  .map(([key, value]) => ({ key, value: String(value) })))

/**
 * 打开详情：先发一次便宜的 describe。
 *
 * <p>带 `check_declared=true`——抽屉要回答的第一个问题就是「表到底建出来没有」，
 * 这个判断要探一下存储；不带的话服务端回 `is_only_declared: null`，界面上就成了
 * 「未探测」，等于把问题原样还给用户。
 */
async function openDetail(tableId) {
  detail.value = {
    open: true,
    name: lanceLeaf(tableId),
    id: tableId,
    loading: true,
    error: '',
    data: null,
    detailed: null,
    detailedLoading: false,
    detailedError: '',
  }
  try {
    detail.value.data = await lanceApi.describeTable(tableId)
  } catch (cause) {
    detail.value.error = describeError(cause)
  } finally {
    detail.value.loading = false
  }
}

/**
 * 读 manifest 换版本 / schema / 统计。
 *
 * <p>「只登记过」导致的失败是**预期结果**（规范允许 detailed 对 declared-only 失败），
 * 因此这里不当错误展示，而是把那句话写进提示里——用户看到的应该是
 * 「这张表还没有数据集」，不是「读取失败」。
 */
async function loadDetailed() {
  detail.value.detailedLoading = true
  detail.value.detailedError = ''
  try {
    const described = await lanceApi.describeTable(detail.value.id, { detailed: true })
    detail.value.detailed = described
  } catch (cause) {
    const onlyDeclared = cause?.payload?.code === 19
    detail.value.detailedError = onlyDeclared
      ? '这张表只有登记、还没有数据集：先由客户端写入数据，详细元数据才有内容。'
      : describeError(cause)
  } finally {
    detail.value.detailedLoading = false
  }
}

function reloadAll() {
  if (!prefix.value) return
  namespaces.load()
  if (selected.value) tables.load()
  if (detail.value.open && detail.value.name) openDetail(detail.value.name)
}

onConsoleRefresh(reloadAll)
</script>

<style scoped>
/* 右侧表清单：一个名字一行的列表，不是表格——`ListTables` 只回名字，
 * 用 el-table 会立刻出现四五列空占位（那是「还没加载」的假象）。 */
.pc-lance-filter {
  width: 180px;
  margin-left: auto;
}

/* 右侧与左侧同高：两栏都在一屏内，一张只有两行的卡片浮在半空会显得没做完。
 * 高度口径直接抄 `.pc-db-pane`（calc(100vh - 190px)），列表区自己滚。 */
.pc-obj-pane {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 190px);
  overflow: hidden;
}

.pc-lance-tables {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 8px 12px 14px;
}

.pc-lance-table {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: var(--pc-radius);
  font-size: 12.5px;
  cursor: pointer;
}

.pc-lance-table:hover {
  background: var(--pc-surface-2);
}

.pc-lance-table-icon {
  font-size: 13px;
  opacity: 0.75;
}

.pc-lance-table-more {
  font-size: 12px;
}

.pc-lance-empty-note {
  max-width: 460px;
  margin-top: 6px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--pc-text-dim);
}

.pc-lance-detail {
  min-height: 200px;
}

.pc-lance-none {
  padding: 6px 2px 10px;
  font-size: 12.5px;
}

.pc-lance-gap {
  margin-bottom: 10px;
}
</style>
