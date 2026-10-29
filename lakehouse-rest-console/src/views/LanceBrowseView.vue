<template>
  <div class="pc-page pc-browse">
    <PageHeader
      title="Catalog"
      :subtitle="`浏览 Lance catalog「${prefix || '未选择'}」下的命名空间与表。读的是 Lance Namespace REST（/lance/v1/namespace/{id}），与客户端看到的是同一份登记。命名空间可以在这里建，表与数据仍在客户端。`"
    >
      <template #actions>
        <el-button size="small" :loading="namespaces.loading.value || tables.loading.value" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" :disabled="!prefix" @click="openCreateNamespace">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建命名空间</span>
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
          >
            <!-- 空态就是最需要建的时候：过滤中没有这个入口（那时用户找的是已有项，不是新建） -->
            <el-button v-if="!nsFilter.trim()" size="small" type="primary" @click="openCreateNamespace">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">新建命名空间</span>
            </el-button>
          </el-empty>
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

      <!-- 右：表清单。结构与 Paimon / Iceberg 两页**同一套**（卡片头 → 过滤条 → 表格 → 翻页），
           只是数据少：`ListTables` 只回 id，位置与版本都在详情页按需读 -->
      <section class="pc-obj-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">{{ selectedLabel || '未选择命名空间' }}</span>
          <el-tag size="small" effect="plain" type="success">Lance</el-tag>
        </div>

        <div class="pc-obj-body">
          <div class="pc-tools-row">
            <el-input
              v-model="tableFilter"
              size="small"
              placeholder="在当前页过滤表名"
              clearable
              class="pc-filter"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <span class="pc-spacer" />
            <span class="pc-dim">
              表由客户端登记（<code class="pc-mono">declare</code> / <code class="pc-mono">register</code>），
              本页不提供「新建表」
            </span>
          </div>

          <!-- 过滤是本地做的（服务端只给 id，没有可下推的过滤条件）：
               先过滤再切页，翻页只切已经过滤好的那一份，页码由 useEmbeddedPaging 兜住 -->
          <ResourceState
            :loading="tables.loading.value"
            :error="tables.error.value"
            :empty="!paged.rows.value.length"
            :empty-text="emptyText"
          >
            <el-table :data="paged.rows.value" size="small">
              <el-table-column label="表名" min-width="200">
                <template #default="{ row }">
                  <a class="pc-link pc-mono" @click.prevent="openTable(row)">{{ row.label }}</a>
                </template>
              </el-table-column>
              <!-- 完整 id 单独一列而不是塞进 title：它是**客户端要用的那个字符串**
                   （`catalog$schema$table`），复制给 Spark / lance-java 时要用它，
                   藏在悬停提示里等于让人记住再手打一遍 -->
              <el-table-column label="id（客户端用的完整串）" min-width="300">
                <template #default="{ row }">
                  <span class="pc-mono pc-dim pc-break">{{ row.id }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="76" align="right" fixed="right">
                <template #default="{ row }">
                  <IconAction icon="View" label="详情" @click="openTable(row)" />
                </template>
              </el-table-column>
            </el-table>

            <TablePager
              :total="paged.total.value"
              :page="paged.page.value"
              :page-size="paged.pageSize.value"
              @page-change="paged.onPageChange"
              @size-change="paged.onSizeChange"
            />
          </ResourceState>
        </div>
      </section>
    </div>

    <!-- 新建命名空间：本页唯一的写操作（表与数据仍在客户端） -->
    <el-dialog
      v-model="nsDialog.open"
      title="新建命名空间"
      width="520px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item required>
          <template #label>
            <FieldLabel label="名称">
              Lance 的命名空间只有两级（catalog 下再一层），所以这里填的就是那一层，
              通常叫 schema。同一个 catalog 下重名会被服务端拒掉（409）。
            </FieldLabel>
          </template>
          <el-input
            v-model="nsDialog.name"
            placeholder="例如 ods"
            @keyup.enter="saveNamespace"
          />
          <!-- 拼出来的 id 必须**看得见**：它是客户端要用的那个字符串，
               藏进气泡里等于让人建完再去别处拼一遍 -->
          <div class="pc-lance-name-hint">
            <span class="pc-mono">id：{{ createPreview }}</span>
            <span class="pc-dim">
              只能是字母、数字、下划线、连字符与点号；<code class="pc-mono">$</code> 是 id 的分隔符，不能出现
            </span>
          </div>
        </el-form-item>
      </el-form>

      <TipAlert type="info" class="pc-hint" title="只登记命名空间：不建存储目录，也不建表">
        这一下调的是 <code class="pc-mono">POST /lance/v1/namespace/{id}/create</code>，
        服务端只落一行 database 记录（带一个按 <code class="pc-mono">path-template</code>
        算出来的预计位置），盘上什么都不会多出来。表仍然由客户端 declare / register。
      </TipAlert>

      <SectionTitle title="属性（properties）" />
      <PropertiesEditor v-model="nsDialog.properties" hint="例如 owner；留空即可" />

      <template #footer>
        <el-button @click="nsDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="nsDialog.saving" @click="saveNamespace">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import IconAction from '@/components/IconAction.vue'
import PageHeader from '@/components/PageHeader.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TablePager from '@/components/TablePager.vue'
import TipAlert from '@/components/TipAlert.vue'
import { LANCE_NAME_PATTERN, lanceApi, lanceFullIdOf, lanceId, lanceLeaf, lanceNameOf } from '@/api/lance.js'
import { notifyError, notifySuccess, onConsoleRefresh, useEmbeddedPaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * Lance catalog 的目录浏览：命名空间 + 表清单，**唯一的写操作是建命名空间**。
 *
 * <p>为什么与 Paimon / Iceberg 两页都不共用一个组件：Lance 的 id 是拼接串
 * （`catalog$schema$table`）、列举只回名字、没有 `/v1/config` 握手。这些差异
 * 不是参数级的，硬塞进一个组件会让每处都是 `v-if`。但**版式是同一套**——
 * 左栏命名空间、右栏卡片里的表清单、过滤框、表格、翻页器都照 Iceberg 那一页，
 * 三页看起来才是同一个控制台。
 *
 * <p>为什么能建命名空间、却不能建表：命名空间是**纯元数据登记**（服务端
 * `LanceNamespaceService.create` 落一行 database 记录，带一个预计位置，不碰存储），
 * 而建表要写数据集——这台服务端对 Lance 没有那条路径，declare / register /
 * deregister 都由客户端发起（`docs/lance-rest-catalog-design.md` §3.2）。页面若给出
 * 「新建表」，会让人以为「在这里建的表」和「盘上的数据集」是一回事，而它们的错位
 * 要几个小时后才在客户端查询里显形。
 *
 * <p>表详情去独立页（`LanceTableView`）：版本、标签、文件、存储优化这些页签需要
 * 一个整页的舞台，而且它们的加载时机各不相同（版本要读 `_versions/`、存储优化要
 * 走管理 API）——塞进抽屉会让「打开一张表」变成一次拉齐所有东西。
 */

const route = useRoute()
const router = useRouter()

/** 与 Paimon / Iceberg 两页同一个取法：URL 上的 catalog 优先，其次是顶栏选中的。 */
const prefix = computed(() => route.query.catalog || session.state.selectedCatalog || '')

const nsFilter = ref('')
const tableFilter = ref('')
const selected = ref('')

const namespaces = useResource(() => lanceApi.listNamespaces(prefix.value), { immediate: false })

/**
 * 命名空间面板的一行。
 *
 * <p>`ListNamespaces` 回的是**裸名字**（相对父级的子级名，规范如此），不是完整 id
 * 也不是对象——id 要按当前 catalog 拼回来。这一页曾经按 `item.id` 读，服务端也曾经
 * 回对象；两边一起改是因为真客户端（Spark connector）按字符串数组反序列化，
 * 回对象它连读都读不了。
 *
 * <p>{@link lanceNameOf} 是**兜底归一**，不是因为规范里元素可以是对象：跑在本页的
 * 服务端可能是更早的版本（那时回 `[{id, properties}]`）。不归一的话，对象会被 Vue
 * 渲染成 `[object Object]`，再拼进 id 发给服务端，报出来的是
 * `namespace id level "[object Object]" contains characters outside [A-Za-z0-9_.-]`
 * ——像「id 里有非法字符」，其实是「拿对象当字符串用了」。
 */
const namespaceRows = computed(() => (namespaces.data.value?.namespaces ?? [])
  .map(lanceNameOf)
  .filter(Boolean)
  .map((name) => ({
    id: lanceId(prefix.value, name),
    label: name,
  })))

const filteredNamespaces = computed(() => {
  const keyword = nsFilter.value.trim().toLowerCase()
  if (!keyword) return namespaceRows.value
  return namespaceRows.value.filter((row) => row.label.toLowerCase().includes(keyword))
})

/** 选中的命名空间的显示名（右栏卡片头）。 */
const selectedLabel = computed(() => {
  const row = namespaceRows.value.find((item) => item.id === selected.value)
  return row ? row.label : ''
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
 * 单独一列与 describe 的入参里：直接拿显示名去调 describe 会拼出
 * `cat$db$cat$db$tbl` 这种解不出来的 id。
 *
 * <p>与命名空间那一侧同理，`lanceFullIdOf` 兜住「元素是对象」的旧服务端；这里要的是
 * 完整 id（describe 的入参），所以不能只取末段。
 */
const tableRows = computed(() => (tables.data.value?.tables ?? [])
  .map(lanceFullIdOf)
  .filter(Boolean)
  .map((id) => ({
    id,
    label: lanceLeaf(id),
  })))

const filteredTables = computed(() => {
  const keyword = tableFilter.value.trim().toLowerCase()
  if (!keyword) return tableRows.value
  return tableRows.value.filter((row) => row.label.toLowerCase().includes(keyword))
})

/**
 * 分页在浏览器里切。
 *
 * <p>服务端的 `ListTables` 有 `limit` / `page_token`，但**没有 `total`**——
 * 页码模式需要总数（Paimon 与 Iceberg 那两个面有），Lance 这一面没有。
 * 拿一个假的「还有下一页吗」当前缀去凑，表现是「翻到最后一页才看出到底有多少张表」，
 * 而命名空间下的表通常只有几十张：一次拉全量、本地过滤、本地切片更好读，
 * 也不必在两套翻页口径之间做区分。
 */
const paged = useEmbeddedPaging(filteredTables, { pageSize: 20 })

const emptyText = computed(() => {
  if (tableFilter.value.trim()) return `没有匹配「${tableFilter.value.trim()}」的表`
  if (!selected.value) return '先在左边选一个命名空间'
  return '这个命名空间下还没有登记表'
})

/** 进 Lance 版表详情页。路由用两段**叶子名**，与优化接口的 database / table 同口径。 */
function openTable(row) {
  router.push({
    name: 'lanceTable',
    params: {
      prefix: prefix.value,
      namespace: lanceLeaf(selected.value),
      table: row.label,
    },
  })
}

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

// 切换命名空间：重新拉表清单，并把过滤框清空（旧关键词在新命名空间里通常没有意义）
watch(selected, (value) => {
  tableFilter.value = ''
  if (value) tables.load()
})

// ------------------------------------------------------------------ 新建命名空间

const nsDialog = ref({ open: false, name: '', properties: {}, saving: false })

/** 弹窗里那句「id 会是什么」：让「名字会被拼成 cat$name」这件事在提交前就看见。 */
const createPreview = computed(() => `${lanceId(prefix.value, nsDialog.value.name.trim() || '<名称>')}`)

function openCreateNamespace() {
  nsDialog.value = { open: true, name: '', properties: {}, saving: false }
}

/**
 * 建命名空间。
 *
 * <p>名称先本地挡两道：空、以及字符集。字符集不是洁癖——服务端 `LanceIds` 的白名单是
 * `[A-Za-z0-9_.-]+`，超了回 `code 13`；而 `$` 尤其要挡在本地，它是 id 的分隔符，
 * 放过去等于在名字里开了一层，客户端拿到的 id 与你填的名字就不是一回事了。
 *
 * <p>不带 `mode`（缺省 `create`）：已存在时服务端 409，界面把那句话原样显示。
 * 用 `overwrite` 让人「点创建却改掉了别人的属性」更难查。
 */
async function saveNamespace() {
  const name = nsDialog.value.name.trim()
  if (!name) {
    notifyError(new Error('请填写命名空间名称'))
    return
  }
  if (!LANCE_NAME_PATTERN.test(name)) {
    notifyError(new Error('名称只能用字母、数字、下划线、连字符与点号（$ 是 id 的分隔符）'))
    return
  }
  if (namespaceRows.value.some((row) => row.label === name)) {
    notifyError(new Error(`命名空间「${name}」已经在了`))
    return
  }
  const id = lanceId(prefix.value, name)
  nsDialog.value.saving = true
  try {
    await lanceApi.createNamespace(id, nsDialog.value.properties)
    notifySuccess(`已创建命名空间 ${name}`)
    nsDialog.value.open = false
    await namespaces.load()
    selected.value = id
  } catch (cause) {
    notifyError(cause)
  } finally {
    nsDialog.value.saving = false
  }
}

function reloadAll() {
  if (!prefix.value) return
  namespaces.load()
  if (selected.value) tables.load()
}

onConsoleRefresh(reloadAll)
</script>

<style scoped>
/* 名称下方那两行：拼出来的 id 与字符集。挤在一行会折行成两句半，分两行读起来清楚 */
.pc-lance-name-hint {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 5px;
  font-size: 12px;
  line-height: 1.6;
}
</style>
