<template>
  <div class="pc-page pc-browse">
    <PageHeader
      title="Catalog"
      :subtitle="`浏览 Iceberg catalog「${prefix || '未选择'}」下的命名空间与表。读的是 Iceberg REST 协议面（/v1/${prefix || '{prefix}'}/namespaces），与 Spark、PyIceberg 等客户端看到的是同一份元数据。`"
    >
      <template #actions>
        <el-button size="small" @click="reloadAll">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button size="small" type="primary" @click="openCreateNamespace">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建命名空间</span>
        </el-button>
      </template>
    </PageHeader>

    <el-alert v-if="!prefix" type="warning" :closable="false" show-icon class="pc-hint">
      <template #title>还没有可浏览的 catalog</template>
      <div>先在「Catalogs」里创建一个 Iceberg catalog。</div>
    </el-alert>

    <div v-else class="pc-browse-body">
      <!-- 左：命名空间（平铺，点号拼接；一期不做层级树） -->
      <aside class="pc-db-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">命名空间</span>
          <span class="pc-dim">{{ rows.length }}</span>
        </div>
        <div class="pc-pane-tools">
          <el-input v-model="nsFilter" size="small" placeholder="过滤命名空间" clearable>
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <div class="pc-db-list pc-scroll">
          <el-skeleton v-if="namespacesResource.loading.value" :rows="4" animated class="pc-db-skeleton" />
          <el-alert
            v-else-if="namespacesResource.error.value"
            type="error"
            :closable="false"
            show-icon
            :title="namespacesResource.error.value"
          />
          <el-empty
            v-else-if="!filteredRows.length"
            :image-size="54"
            :description="nsFilter.trim() ? `没有匹配「${nsFilter.trim()}」的命名空间` : '这个 catalog 下还没有命名空间'"
          />
          <template v-else>
            <div
              v-for="row in filteredRows"
              :key="row.label"
              class="pc-db-item"
              :class="{ 'is-active': row.label === currentLabel }"
              :title="row.levels.join(' / ')"
              @click="selectNamespace(row.label)"
            >
              <el-icon class="pc-db-icon"><Folder /></el-icon>
              <span class="pc-db-name">{{ row.label }}</span>
            </div>
          </template>
        </div>
      </aside>

      <!-- 右：表 / 视图 -->
      <section class="pc-obj-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">{{ currentLabel || '未选择命名空间' }}</span>
          <el-tag size="small" effect="plain" type="primary">Iceberg</el-tag>
        </div>

        <!-- 语义视图是 Paimon 面的能力：Iceberg 页签是表 / 视图 / 函数（函数只读，写路径在 Paimon 面） -->
        <el-tabs v-model="tab" class="pc-tabs">
          <el-tab-pane label="表" name="tables" />
          <el-tab-pane label="视图" name="views" />
          <el-tab-pane label="函数" name="functions" />
        </el-tabs>

        <div class="pc-obj-body">
          <!-- 工具条放在 ResourceState 之外：空态时组件里只剩 el-empty，
               而「这个命名空间还没有视图」正是最需要「新建视图」的时刻。 -->
          <div v-if="tab === 'tables'" class="pc-tools-row">
            <el-input v-model="tableFilter" size="small" placeholder="在当前页过滤表名" clearable class="pc-filter">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <span class="pc-spacer" />
            <el-button size="small" @click="openRegisterTable">登记已有表</el-button>
            <el-button size="small" type="primary" @click="openCreateTable">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">新建表</span>
            </el-button>
          </div>
          <div v-else-if="tab === 'views'" class="pc-tools-row">
            <span class="pc-dim">
              视图定义按 Iceberg 的 <code class="pc-mono">ViewMetadata</code> 整篇保存。
              新建时控制台按「字段 + SQL」拼出 schema 与第一个 view-version（方言可选 Spark / Trino）；
              多方言、多版本的视图仍建议用 Spark 或客户端提交，这里只做常见形状。
            </span>
            <span class="pc-spacer" />
            <el-button size="small" @click="openRegisterView">登记已有视图</el-button>
            <el-button size="small" type="primary" @click="openCreateView">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">新建视图</span>
            </el-button>
          </div>
          <div v-else class="pc-tools-row">
            <span class="pc-dim">
              函数在 Iceberg 协议面是<strong>只读</strong>的（规格只定义了两个 GET）：
              写路径在 Paimon 面、且那条面只服务 Paimon catalog，所以 Iceberg catalog 上
              这份列表通常为空——Paimon catalog 里的函数可通过这条只读通道以
              <code class="pc-mono">FunctionMetadata</code> 的形状读出
              （只有 SQL 定义能投影，<code class="pc-mono">file</code> /
              <code class="pc-mono">lambda</code> 不显示）。
              要管理函数请到 Paimon catalog 的浏览页「函数」页签。
            </span>
          </div>

          <ResourceState
            :loading="currentList.loading.value"
            :error="currentList.error.value"
            :empty="!visibleRows.length"
            :empty-text="emptyText"
          >
            <template v-if="tab === 'tables'">
              <el-table v-loading="tablesList.loading.value" :data="filteredTables" size="small">
                <el-table-column label="表名" min-width="220">
                  <template #default="{ row }">
                    <!-- 表名可点：进 Iceberg 版表详情页（详细信息 / 快照 / 文件列表 / 存储优化） -->
                    <a class="pc-link pc-mono" @click.prevent="openTable(row.name)">{{ row.name }}</a>
                  </template>
                </el-table-column>
                <el-table-column label="操作" width="152" align="right" fixed="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="详情" @click="openTable(row.name)" />
                    <IconAction icon="EditPen" label="重命名" @click="openRenameTable(row.name)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropTable(row.name)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <template v-else-if="tab === 'views'">
              <el-table v-loading="viewsList.loading.value" :data="viewsList.rows.value" size="small">
                <el-table-column prop="name" label="视图名" min-width="220" />
                <el-table-column label="操作" width="110" align="right" fixed="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="详情" @click="showView(row)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropView(row.name)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <template v-else>
              <el-table v-loading="functionsList.loading.value" :data="functionsList.rows.value" size="small">
                <el-table-column prop="name" label="函数名" min-width="220" />
                <el-table-column label="操作" width="90" align="right" fixed="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="详情" @click="showFunction(row)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <TablePager
              :total="currentList.total.value"
              :page="currentList.page.value"
              :page-size="currentList.pageSize.value"
              @page-change="currentList.onPageChange"
              @size-change="currentList.onSizeChange"
            />
          </ResourceState>
        </div>
      </section>
    </div>

    <!-- 新建命名空间 -->
    <el-dialog v-model="nsDialog.open" title="新建命名空间" width="520px" :close-on-click-modal="false" destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item required>
          <template #label>
            <FieldLabel label="名称（namespace）">
              单层直接写名字；需要多级时用点号分隔（<code class="pc-mono">accounting.tax</code>）。
              层级名本身含点时请用客户端建——点号在这里被当作分隔符。
            </FieldLabel>
          </template>
          <el-input v-model="nsDialog.name" placeholder="例如 ods，或 accounting.tax" />
        </el-form-item>
      </el-form>
      <SectionTitle title="属性（properties）" />
      <PropertiesEditor v-model="nsDialog.properties" hint="例如 owner；留空即可" />
      <template #footer>
        <el-button @click="nsDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="nsDialog.saving" @click="saveNamespace">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建表：Iceberg 形状（字段含 required / doc，分区是 transform） -->
    <el-dialog
      v-model="tableDialog.open"
      title="新建表（Iceberg）"
      width="900px"
      :close-on-click-modal="false"
      top="6vh"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="表名" required>
          <el-input v-model="tableDialog.name" placeholder="例如 orders" />
        </el-form-item>
      </el-form>

      <SectionTitle title="字段（schema）">
        <template #tip>
          <TipNote>
            字段 id 由行序决定（第 1 行是 1），不需要填写：Iceberg 用 id 而不是名字追踪字段，
            改名不改变 id，下游读取靠的就是它。
            「必填」对应 Iceberg 的 <code>required</code>：写入时不允许为 null。
            类型按 Iceberg 的类型写法填（<code>long</code>、<code>string</code>、
            <code>decimal(10, 2)</code>、<code>timestamp</code> 等），不是 Paimon 的 SQL 类型名。
          </TipNote>
        </template>
      </SectionTitle>
      <div class="pc-ice-grid pc-ice-head">
        <span>ID</span>
        <span>列名称</span>
        <span class="pc-ice-check">必填</span>
        <span>数据类型</span>
        <span>描述（doc）</span>
        <span class="pc-ice-op">操作</span>
      </div>
      <div v-for="(field, index) in tableDialog.fields" :key="index" class="pc-ice-grid">
        <span class="pc-dim pc-mono">{{ index + 1 }}</span>
        <el-input v-model="field.name" size="small" placeholder="请输入名称" />
        <el-checkbox v-model="field.required" size="small" class="pc-ice-check" />
        <el-select
          v-model="field.type"
          filterable
          allow-create
          default-first-option
          size="small"
          placeholder="如 long / string"
        >
          <el-option v-for="type in ICEBERG_TYPES" :key="type" :label="type" :value="type" />
        </el-select>
        <el-input v-model="field.doc" size="small" placeholder="可选的字段说明" />
        <span class="pc-ice-op">
          <el-button size="small" text type="danger" @click="tableDialog.fields.splice(index, 1)">删除</el-button>
        </span>
      </div>
      <el-button size="small" text type="primary" @click="tableDialog.fields.push(emptyField())">
        <el-icon><Plus /></el-icon>
        <span class="pc-repeat-add">添加一项</span>
      </el-button>

      <SectionTitle title="分区（partition-spec）">
        <template #tip>
          <TipNote>
            留空即不分区。分区字段必须是上面某个字段，transform 决定怎么切：
            <code>identity</code> 按原值、<code>bucket[n]</code> 按哈希桶、
            <code>truncate[n]</code> 按前缀、<code>year/month/day/hour</code> 按时间粒度。
            partition field id 由行序分配（从 1000 起），不需要填写。
          </TipNote>
        </template>
      </SectionTitle>
      <div v-for="(part, index) in tableDialog.partitions" :key="`p-${index}`" class="pc-ice-part">
        <el-select v-model="part.source" size="small" placeholder="分区字段" class="pc-ice-part-field">
          <el-option
            v-for="field in namedFields"
            :key="field.name"
            :label="field.name"
            :value="field.name"
          />
        </el-select>
        <el-select v-model="part.transform" size="small" class="pc-ice-part-transform">
          <el-option v-for="item in TRANSFORMS" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input
          v-if="needsParam(part.transform)"
          v-model="part.param"
          size="small"
          class="pc-ice-part-param"
          :placeholder="part.transform === 'bucket' ? '桶数，如 16' : '截断长度，如 4'"
        />
        <span v-else class="pc-ice-part-param pc-dim">—</span>
        <el-button size="small" text type="danger" @click="tableDialog.partitions.splice(index, 1)">删除</el-button>
      </div>
      <el-button size="small" text type="primary" @click="tableDialog.partitions.push({ source: '', transform: 'identity', param: '' })">
        <el-icon><Plus /></el-icon>
        <span class="pc-repeat-add">添加分区字段</span>
      </el-button>

      <SectionTitle title="表属性（properties）" />
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item>
          <template #label>
            <FieldLabel label="format-version">
              留空则由服务端（Iceberg 核心库）定。填 1 / 2 / 3 会写进
              <code class="pc-mono">properties['format-version']</code>；服务端不校验取值范围，
              填错会在读表时才暴露，所以这里只给这三个选项。
              v3 加了默认值、行删除与多参数 spec 等能力，但部分引擎还不读写 v3——
              没有明确需求时留空（当前核心库默认 v2）。
            </FieldLabel>
          </template>
          <el-select v-model="tableDialog.formatVersion" clearable placeholder="留空（推荐）">
            <el-option label="1（v1）" value="1" />
            <el-option label="2（v2）" value="2" />
            <el-option label="3（v3）" value="3" />
          </el-select>
        </el-form-item>
      </el-form>
      <PropertiesEditor
        v-model="tableDialog.properties"
        hint="例如 write.format.default = parquet；留空即可。table location 由服务端按仓库路径规则分配"
      />

      <template #footer>
        <el-button @click="tableDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="tableDialog.saving" @click="saveTable">创建</el-button>
      </template>
    </el-dialog>

    <!-- 登记已有表 -->
    <el-dialog v-model="registerDialog.open" title="登记已有表" width="560px" :close-on-click-modal="false" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon class="pc-hint">
        <template #title>登记只写经目录，不搬动数据</template>
        <div>
          <code class="pc-mono">metadata-location</code> 必须指向仓库里一份可读的
          <code class="pc-mono">metadata.json</code>；登记进来的表对外标记为外部表，
          删除时服务端不会清理它的文件。
        </div>
      </el-alert>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="表名" required>
          <el-input v-model="registerDialog.name" />
        </el-form-item>
        <el-form-item label="metadata-location" required>
          <el-input
            v-model="registerDialog.metadataLocation"
            class="pc-pre"
            placeholder="s3://warehouse/orders/metadata/00001-....metadata.json"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="registerDialog.saving" @click="saveRegister">登记</el-button>
      </template>
    </el-dialog>

    <!-- 登记已有视图：与登记表同一套「内容以仓库为准」的语义，但没有 overwrite（规格没给） -->
    <el-dialog v-model="viewRegisterDialog.open" title="登记已有视图" width="560px" :close-on-click-modal="false" destroy-on-close>
      <el-alert type="info" :closable="false" show-icon class="pc-hint">
        <template #title>登记只写经目录，不搬动数据</template>
        <div>
          <code class="pc-mono">metadata-location</code> 必须指向仓库里一份可读的
          <code class="pc-mono">ViewMetadata</code> 文件；视图重名时服务端会拒绝（409），
          没有「覆盖」选项——规格的请求体里没有这个字段。
        </div>
      </el-alert>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="视图名" required>
          <el-input v-model="viewRegisterDialog.name" />
        </el-form-item>
        <el-form-item label="metadata-location" required>
          <el-input
            v-model="viewRegisterDialog.metadataLocation"
            class="pc-pre"
            placeholder="s3://warehouse/orders/metadata/00001-....metadata.json"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="viewRegisterDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="viewRegisterDialog.saving" @click="saveRegisterView">登记</el-button>
      </template>
    </el-dialog>

    <!-- 新建视图：Iceberg 建视图要 schema + view-version 两段元数据，表单只覆盖常见形状
         （一份 SQL 表示 + 字段列表）。多方言、多版本的视图仍走 Spark / 客户端。 -->
    <el-dialog
      v-model="viewDialog.open"
      title="新建视图（Iceberg）"
      width="820px"
      :close-on-click-modal="false"
      top="8vh"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="视图名" required>
          <el-input v-model="viewDialog.name" placeholder="例如 recent_orders" />
        </el-form-item>
        <el-form-item label="查询语句（SQL 表示）" required>
          <el-input
            v-model="viewDialog.sql"
            type="textarea"
            :rows="5"
            placeholder="SELECT id, amount FROM orders WHERE amount > 0"
          />
        </el-form-item>
        <el-form-item>
          <template #label>
            <FieldLabel label="方言（dialect）">
              写进 <code class="pc-mono">representations[0].dialect</code>。Spark 与 Trino 的
              SQL 方言不同，按这条语句实际来自哪个引擎选；服务端不解析语句，只原样存。
            </FieldLabel>
          </template>
          <el-select v-model="viewDialog.dialect">
            <el-option label="spark" value="spark" />
            <el-option label="trino" value="trino" />
          </el-select>
        </el-form-item>
      </el-form>

      <SectionTitle title="字段（schema）">
        <template #tip>
          <TipNote>
            视图<strong>结果列</strong>的声明，服务端不校验它与 SQL 是否吻合——对不上会在引擎读视图时报错。
            字段 id 由行序决定（第 1 行是 1），类型按 Iceberg 的写法填
            （<code>long</code>、<code>decimal(10, 2)</code>、<code>timestamp</code> 等）。
          </TipNote>
        </template>
      </SectionTitle>
      <div class="pc-ice-grid pc-ice-head">
        <span>ID</span>
        <span>列名称</span>
        <span class="pc-ice-check">必填</span>
        <span>数据类型</span>
        <span>描述（doc）</span>
        <span class="pc-ice-op">操作</span>
      </div>
      <div v-for="(field, index) in viewDialog.fields" :key="index" class="pc-ice-grid">
        <span class="pc-dim pc-mono">{{ index + 1 }}</span>
        <el-input v-model="field.name" size="small" placeholder="请输入名称" />
        <el-checkbox v-model="field.required" size="small" class="pc-ice-check" />
        <el-select
          v-model="field.type"
          filterable
          allow-create
          default-first-option
          size="small"
          placeholder="如 long / string"
        >
          <el-option v-for="type in ICEBERG_TYPES" :key="type" :label="type" :value="type" />
        </el-select>
        <el-input v-model="field.doc" size="small" placeholder="可选的字段说明" />
        <span class="pc-ice-op">
          <el-button size="small" text type="danger" @click="viewDialog.fields.splice(index, 1)">删除</el-button>
        </span>
      </div>
      <el-button size="small" text type="primary" @click="viewDialog.fields.push(emptyField())">
        <el-icon><Plus /></el-icon>
        <span class="pc-repeat-add">添加一项</span>
      </el-button>

      <template #footer>
        <el-button @click="viewDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="viewDialog.saving" @click="saveView">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameDialog.open" :title="renameDialog.title" width="440px" :close-on-click-modal="false" destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="新名称" required>
          <el-input v-model="renameDialog.value" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="renameDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="renameDialog.saving" @click="saveRename">确定</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉：视图的 LoadViewResponse。表详情在独立页（路由 icebergTable），
         那里有快照 / 文件 / 优化整页的舞台，抽屉装不下 -->
    <el-drawer v-model="detail.open" :title="detail.title" size="640px">
      <div v-if="detail.summary.length" class="pc-summary">
        <div v-for="item in detail.summary" :key="item.label" class="pc-summary-row">
          <span class="pc-summary-label">{{ item.label }}</span>
          <span class="pc-summary-value pc-mono">{{ item.value }}</span>
        </div>
      </div>
      <JsonBlock :value="detail.payload" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'

import FieldLabel from '@/components/FieldLabel.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TablePager from '@/components/TablePager.vue'
import TipNote from '@/components/TipNote.vue'
import { icebergApi, listAllNamespaces, namespaceLabel, namespaceLevels, namespacePath } from '@/api/iceberg.js'
import { confirmDanger, notifyError, notifySuccess, useRemotePaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'

/**
 * Iceberg catalog 的目录浏览。
 *
 * <p><b>为什么不和 Paimon 页共用一个组件。</b>两套契约的差异不是参数级的：
 * 资源叫 namespace 而不是 database、标识符是层级数组而不是两个字符串、
 * 表列表只回 `identifiers`（没有表格式 / 外部标记 / 时间）、视图定义整篇是
 * `ViewMetadata`。硬塞进一个组件的结果是每一屏都要 `v-if` 两遍，
 * 而 Paimon 那一路已经稳定，改动它的风险远大于多一个文件。
 *
 * <p><b>能读到什么、不能读到什么。</b>列表读 `.../tables` 只拿得到名字——
 * location、schema、快照都在单表的 `LoadTableResult` 里。控制台因此不在列表里
 * 伪造这些列（那要靠 N 次 load 才拼得出来），而是把「详情」做成按需打开的抽屉。
 */

const route = useRoute()
const router = useRouter()

/**
 * Iceberg 的类型写法。
 *
 * <p>只列常用项，`allow-create` 让人填任意合法写法（`decimal(38, 18)`、
 * `fixed[16]`、`map<string, int>` 都能填）——控制台不打算复刻 Iceberg 的类型文法，
 * 猜错一次就会拦掉一个合法 schema。
 */
const ICEBERG_TYPES = [
  'boolean', 'int', 'long', 'float', 'double',
  'decimal(10, 2)', 'date', 'time', 'timestamp', 'timestamptz',
  'string', 'uuid', 'fixed[16]', 'binary',
]

/** 分区 transform。带 `[...]` 的两项要再给一个数字。 */
const TRANSFORMS = [
  { value: 'identity', label: 'identity（按原值）' },
  { value: 'bucket', label: 'bucket[n]（哈希桶）' },
  { value: 'truncate', label: 'truncate[n]（截断）' },
  { value: 'year', label: 'year' },
  { value: 'month', label: 'month' },
  { value: 'day', label: 'day' },
  { value: 'hour', label: 'hour' },
]

/**
 * 命名空间的递归深度上限。
 *
 * <p>`GET /namespaces` 只回**直接子级**，要平铺就得按层递归：父级数 n 时
 * 请求数是「层的节点数」而不是固定值。Iceberg 的多级命名空间在实践中很浅
 * （`accounting.tax` 这种），设 4 层足够；真正的护栏是下面的条目上限——
 * 它挡住「某天有人建了 5000 个 namespace，打开页面发 5000 个请求」。
 */
const MAX_NAMESPACE_DEPTH = 4
const MAX_NAMESPACE_COUNT = 500

const tab = ref('tables')
const nsFilter = ref('')
const tableFilter = ref('')

const prefix = computed(() => route.query.catalog || session.state.selectedCatalog || '')
const currentLabel = computed(() => route.query.database || '')

const pageSize = computed(() => session.state.meta?.service?.maxPageSize || 100)

// ------------------------------------------------------------------ 命名空间

/**
 * 平铺列出全部命名空间。
 *
 * <p>按层的递归与两道上限（深度、条目数）都在 `listAllNamespaces` 里——权限页也要
 * 同一份命名空间列表当候选，两处各写一遍 BFS 迟早会给出不同的集合。
 */
async function loadNamespaces() {
  return listAllNamespaces(prefix.value, { pageSize: pageSize.value })
}

const namespacesResource = useResource(loadNamespaces, { immediate: false })

/** 行：`{levels, label}`。label 是平铺显示名，levels 是发请求用的层级数组。 */
const rows = computed(() => (namespacesResource.data.value || []).map((levels) => ({
  levels,
  label: namespaceLabel(levels),
})))

const filteredRows = computed(() => {
  const keyword = nsFilter.value.trim().toLowerCase()
  if (!keyword) return rows.value
  return rows.value.filter((row) => row.label.toLowerCase().includes(keyword))
})

/**
 * 当前命名空间的层级。
 *
 * <p>优先用列表里那一份（能区分「层级名里带点」），列表还没到时才按点号切
 * ——与服务端 `NamespacePaths.parse` 的兜底口径一致。深链接直接打开本页时
 * 会走到后者，列表到手后 computed 自动换成前者。
 */
const currentLevels = computed(() => {
  const found = rows.value.find((row) => row.label === currentLabel.value)
  return found ? found.levels : namespaceLevels(currentLabel.value)
})

function selectNamespace(label) {
  router.replace({ name: 'browse', query: { catalog: prefix.value, database: label } })
}

// ------------------------------------------------------------------ 表 / 视图

/**
 * 三个对象列表的取数函数：端点不同，回包字段同名（都是 `identifiers`）。
 *
 * <p><b>远程分页</b>：页码与页大小下推。Iceberg 规格里的翻法是 `pageSize` +
 * `next-page-token`，服务端另外认 `page` 这个控制台扩展参数（给了就切页码模式、
 * 回 `total`）——两者互斥，见 `IcebergDtos.ListTablesResponse` 的注释。
 *
 * <p>没有选中命名空间时不发请求：`/namespaces//tables` 会被服务端当成 404，
 * 页面上一串红色错误，看起来像故障而不像「还没选」。
 */
async function fetchIdentifiers(call, { page, pageSize }) {
  if (!prefix.value || !currentLevels.value.length) {
    return { items: [], total: 0 }
  }
  const payload = await call(currentLevels.value, { page, pageSize })
  return { items: payload?.identifiers || [], total: payload?.total ?? 0 }
}

const tablesList = useRemotePaging(
  (paging) => fetchIdentifiers((levels, query) => icebergApi.listTables(prefix.value, levels, query), paging),
  { immediate: false },
)
const viewsList = useRemotePaging(
  (paging) => fetchIdentifiers((levels, query) => icebergApi.listViews(prefix.value, levels, query), paging),
  { immediate: false },
)
const functionsList = useRemotePaging(
  (paging) => fetchIdentifiers((levels, query) => icebergApi.listFunctions(prefix.value, levels, query), paging),
  { immediate: false },
)

const lists = { tables: tablesList, views: viewsList, functions: functionsList }
const currentList = computed(() => lists[tab.value] || tablesList)

/**
 * 表名过滤**只作用于当前页**。
 *
 * <p>Iceberg 的列表端点没有 `tableNamePattern` 这种参数——规格里翻页只认
 * `pageSize` + `next-page-token`，过滤不在清单里。所以这里只能筛手上这一页，
 * 输入框的提示写明了这一点：写「过滤表名」会让人以为筛的是整个命名空间，
 * 而那需要服务端支持，不该由前端假装。
 */
const filteredTables = computed(() => {
  const keyword = tableFilter.value.trim().toLowerCase()
  if (!keyword) return tablesList.rows.value
  return tablesList.rows.value.filter((row) => row.name.toLowerCase().includes(keyword))
})

const visibleRows = computed(() => (tab.value === 'tables' ? filteredTables.value : currentList.value.rows.value))

const emptyText = computed(() => {
  // 还没选命名空间时「没有表」是假话：右侧面板此刻什么都没有，因为左侧还没点。
  // 两种情况给不同的下一步，避免用户去查是不是权限出了问题。
  if (!currentLabel.value) return '先选择左侧的命名空间'
  if (tab.value === 'tables' && tableFilter.value.trim() && currentList.value.rows.value.length) {
    return `没有匹配「${tableFilter.value.trim()}」的表`
  }
  return {
    tables: '这个命名空间下还没有表',
    views: '这个命名空间下还没有视图',
    functions: '这个命名空间下还没有函数',
  }[tab.value] || '暂无数据'
})

/** 哪些页签已经拉过：切来切去不该重复请求，也不该把页码打回第一页。 */
const loadedTabs = new Set()

function reloadAll() {
  namespacesResource.load()
  currentList.value.reload()
}

// 两个 watch 分开：换命名空间不需要重新拉整棵命名空间树（那是 N 次请求），
// 而换 catalog 两者都要重来。
watch(prefix, () => {
  if (!prefix.value) return
  namespacesResource.load()
}, { immediate: true })

// 列表跟着「层级」走而不是跟着 URL 上的显示名走：深链接进来时层级先由点号兜底切出来，
// 命名空间列表到手后再换成列表里那份——两种情况下发出去的路径可能是同一个，
// 也可能不是（层级名含点时），而这里只关心「要请求的层级变了没有」。
watch(
  [prefix, () => namespacePath(currentLevels.value)],
  ([, levels]) => {
    if (!prefix.value || !levels) return
    // 换了命名空间，三个列表全部作废：页码、已加载标记都要归零
    loadedTabs.clear()
    tablesList.reload()
    viewsList.reload()
    functionsList.reload()
  },
  { immediate: true },
)

// 列表到手后若 URL 上没有命名空间，落到第一个：打开页面右侧直接有内容
watch(rows, (list) => {
  if (!currentLabel.value && list.length) {
    router.replace({ name: 'browse', query: { catalog: prefix.value, database: list[0].label } })
  }
})

watch(tab, (name) => {
  // 首次切到某个页签时才拉数据；上面那个 watch 已经把三个都拉过了，这里只是兜底
  if (!loadedTabs.has(name)) {
    loadedTabs.add(name)
  }
})

// ------------------------------------------------------------------ 命名空间增删

const nsDialog = reactive({ open: false, name: '', properties: {}, saving: false })

function openCreateNamespace() {
  nsDialog.name = ''
  nsDialog.properties = {}
  nsDialog.open = true
}

async function saveNamespace() {
  const text = nsDialog.name.trim()
  if (!text) {
    notifyError(new Error('请填写命名空间名称'))
    return
  }
  const levels = text.split('.').map((level) => level.trim()).filter(Boolean)
  if (!levels.length) {
    notifyError(new Error('命名空间名称不能为空'))
    return
  }
  nsDialog.saving = true
  try {
    // **逐级补齐**（类似 mkdir -p），不是一条请求把 a.b 建出来。
    //
    // 服务端把命名空间按层级存成 `a\u001Fb` 一行，而 `GET /namespaces` 只回
    // **直接子级**（`isDirectChild` 要求正好深一层）。因此只建 `a.b` 而不建 `a`
    // 时，`a.b` 在顶层列表里永远不出现，本页的 BFS 也走不到它——建成功了却看不见。
    // 已存在的层级回 409，跳过即可（那是预期，不是错误）。
    for (let depth = 1; depth <= levels.length; depth += 1) {
      const prefixLevels = levels.slice(0, depth)
      try {
        await icebergApi.createNamespace(prefix.value, {
          namespace: prefixLevels,
          // 属性只落在最深一级：中间层级是「容器」，把 owner 之类挂上去会让
          // 用户以为那是目标命名空间的属性。
          properties: depth === levels.length ? nsDialog.properties : {},
        })
      } catch (error) {
        if (error?.status === 409) continue
        throw error
      }
    }
    notifySuccess(`已创建命名空间 ${namespaceLabel(levels)}`)
    nsDialog.open = false
    namespacesResource.load()
  } catch (error) {
    notifyError(error)
  } finally {
    nsDialog.saving = false
  }
}

// ------------------------------------------------------------------ 建表

const tableDialog = reactive({
  open: false,
  name: '',
  fields: [],
  partitions: [],
  properties: {},
  formatVersion: '',
  saving: false,
})

function emptyField() {
  return { name: '', type: '', doc: '', required: false }
}

/** 已经填了名字的字段：分区下拉只列它们，避免指向一个不存在的 source-id。 */
const namedFields = computed(() => tableDialog.fields.filter((field) => field.name.trim()))

function needsParam(transform) {
  return transform === 'bucket' || transform === 'truncate'
}

function openCreateTable() {
  tableDialog.name = ''
  tableDialog.fields = [
    { ...emptyField(), name: 'id', type: 'long', required: true },
    { ...emptyField(), name: 'data', type: 'string' },
  ]
  tableDialog.partitions = []
  tableDialog.properties = {}
  tableDialog.formatVersion = ''
  tableDialog.open = true
}

/**
 * 校验并拼出 Iceberg 的 schema JSON。
 *
 * <p>只拦「服务端会以更难懂的方式拒绝」的那些：空字段名、空类型、重名、
 * 分区指向不存在的字段、`bucket[]` 缺数字。类型写法本身不校验——
 * 那等于在前端复刻 Iceberg 的类型文法，猜错就拦掉合法 schema。
 */
function buildSchema(fields = tableDialog.fields) {
  const valid = fields.filter((field) => field.name.trim() && field.type.trim())
  if (!valid.length) {
    throw new Error('至少填写一个字段（字段名与类型都不能为空）')
  }
  const names = valid.map((field) => field.name.trim())
  const duplicated = [...new Set(names.filter((name, index) => names.indexOf(name) !== index))]
  if (duplicated.length) {
    throw new Error(`字段名不能重复：${duplicated.join(', ')}`)
  }
  return {
    type: 'struct',
    'schema-id': 0,
    fields: valid.map((field, index) => ({
      id: index + 1,
      name: field.name.trim(),
      required: Boolean(field.required),
      type: field.type.trim(),
      ...(field.doc.trim() ? { doc: field.doc.trim() } : {}),
    })),
  }
}

function buildPartitionSpec(schema) {
  const byName = new Map(schema.fields.map((field) => [field.name, field]))
  const parts = tableDialog.partitions.filter((part) => part.source)
  if (!parts.length) return null
  const fields = parts.map((part, index) => {
    const source = byName.get(part.source)
    if (!source) {
      throw new Error(`分区字段「${part.source}」不在字段列表里`)
    }
    const transform = needsParam(part.transform)
      ? `${part.transform}[${(part.param || '').trim()}]`
      : part.transform
    if (needsParam(part.transform) && !/^\d+$/.test((part.param || '').trim())) {
      throw new Error(`分区字段「${part.source}」的 ${part.transform} 需要填一个正整数`)
    }
    return {
      name: part.transform === 'identity' ? source.name : `${part.transform}_${source.name}`,
      transform,
      'source-id': source.id,
      'field-id': 1000 + index,
    }
  })
  return { 'spec-id': 0, fields }
}

async function saveTable() {
  const name = tableDialog.name.trim()
  if (!name) {
    notifyError(new Error('请填写表名'))
    return
  }
  let schema
  let partitionSpec
  try {
    schema = buildSchema()
    partitionSpec = buildPartitionSpec(schema)
  } catch (error) {
    notifyError(error)
    return
  }
  const properties = { ...tableDialog.properties }
  if (tableDialog.formatVersion) {
    properties['format-version'] = tableDialog.formatVersion
  }
  tableDialog.saving = true
  try {
    await icebergApi.createTable(prefix.value, currentLevels.value, {
      name,
      schema,
      ...(partitionSpec ? { 'partition-spec': partitionSpec } : {}),
      properties,
    })
    notifySuccess(`已创建表 ${name}`)
    tableDialog.open = false
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    tableDialog.saving = false
  }
}

// ------------------------------------------------------------------ 登记 / 重命名 / 删除

const registerDialog = reactive({ open: false, name: '', metadataLocation: '', saving: false })

function openRegisterTable() {
  registerDialog.name = ''
  registerDialog.metadataLocation = ''
  registerDialog.open = true
}

async function saveRegister() {
  const name = registerDialog.name.trim()
  const location = registerDialog.metadataLocation.trim()
  if (!name || !location) {
    notifyError(new Error('表名与 metadata-location 都不能为空'))
    return
  }
  registerDialog.saving = true
  try {
    await icebergApi.registerTable(prefix.value, currentLevels.value, {
      name,
      'metadata-location': location,
    })
    notifySuccess(`已登记表 ${name}`)
    registerDialog.open = false
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    registerDialog.saving = false
  }
}

const renameDialog = reactive({ open: false, title: '', value: '', original: '', saving: false })

function openRenameTable(table) {
  renameDialog.title = `重命名表：${table}`
  renameDialog.value = table
  renameDialog.original = table
  renameDialog.open = true
}

async function saveRename() {
  const next = renameDialog.value.trim()
  if (!next) {
    notifyError(new Error('新名称不能为空'))
    return
  }
  if (next === renameDialog.original) {
    renameDialog.open = false
    return
  }
  renameDialog.saving = true
  try {
    // identifier 的 namespace 是数组：与 Paimon 的扁平 {database, object} 不同，
    // 这也正是两条 rename 路径（同一条 URL）能分开的原因。
    await icebergApi.renameTable(
      prefix.value,
      { namespace: currentLevels.value, name: renameDialog.original },
      { namespace: currentLevels.value, name: next },
    )
    notifySuccess('已重命名')
    renameDialog.open = false
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    renameDialog.saving = false
  }
}

async function confirmDropTable(name) {
  const ok = await confirmDanger({
    title: '删除表',
    target: `确认删除表「${name}」？`,
    detail: '默认只摘目录条目；是否连带清理数据文件由服务端的 purge-on-drop 开关决定，'
      + '外部登记的表永不清。需要保留文件时请用「注销」（unregister）而不是删除。',
  })
  if (!ok) return
  try {
    await icebergApi.dropTable(prefix.value, currentLevels.value, name)
    notifySuccess(`已删除表 ${name}`)
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

async function confirmDropView(name) {
  const ok = await confirmDanger({
    title: '删除视图',
    target: `确认删除视图「${name}」？`,
    detail: '只删除视图定义，底表不受影响。',
  })
  if (!ok) return
  try {
    await icebergApi.dropView(prefix.value, currentLevels.value, name)
    notifySuccess(`已删除视图 ${name}`)
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 新建视图

const viewDialog = reactive({
  open: false,
  name: '',
  sql: '',
  dialect: 'spark',
  fields: [],
  saving: false,
})

function openCreateView() {
  viewDialog.name = ''
  viewDialog.sql = ''
  viewDialog.dialect = 'spark'
  viewDialog.fields = [
    { ...emptyField(), name: 'id', type: 'long' },
    { ...emptyField(), name: 'amount', type: 'double' },
  ]
  viewDialog.open = true
}

/**
 * 建视图：把「字段 + SQL」拼成 Iceberg 要的两段元数据。
 *
 * <p>`view-version` 的 `schema-id` 只是占位：`ViewMetadata.Builder#setCurrentVersion`
 * 分配 schema 时会覆盖成真实的 id。`default-namespace` 填当前命名空间、`default-catalog`
 * 填 catalog 名，这样视图里不带限定名的表名也能按「建它的人当时看到的上下文」解析。
 */
async function saveView() {
  const name = viewDialog.name.trim()
  const sql = viewDialog.sql.trim()
  if (!name || !sql) {
    notifyError(new Error('视图名与查询语句都不能为空'))
    return
  }
  let schema
  try {
    schema = buildSchema(viewDialog.fields)
  } catch (error) {
    notifyError(error)
    return
  }
  viewDialog.saving = true
  try {
    await icebergApi.createView(prefix.value, currentLevels.value, {
      name,
      schema,
      'view-version': {
        'version-id': 1,
        'timestamp-ms': Date.now(),
        'schema-id': 0,
        summary: { 'engine-name': 'lakehouse-console' },
        representations: [{ type: 'sql', sql, dialect: viewDialog.dialect }],
        'default-catalog': prefix.value || null,
        'default-namespace': [...currentLevels.value],
      },
    })
    notifySuccess(`已创建视图 ${name}`)
    viewDialog.open = false
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    viewDialog.saving = false
  }
}

// ------------------------------------------------------------------ 登记视图 / 函数详情

const viewRegisterDialog = reactive({ open: false, name: '', metadataLocation: '', saving: false })

function openRegisterView() {
  viewRegisterDialog.name = ''
  viewRegisterDialog.metadataLocation = ''
  viewRegisterDialog.open = true
}

async function saveRegisterView() {
  const name = viewRegisterDialog.name.trim()
  const location = viewRegisterDialog.metadataLocation.trim()
  if (!name || !location) {
    notifyError(new Error('视图名与 metadata-location 都不能为空'))
    return
  }
  viewRegisterDialog.saving = true
  try {
    await icebergApi.registerView(prefix.value, currentLevels.value, {
      name,
      'metadata-location': location,
    })
    notifySuccess(`已登记视图 ${name}`)
    viewRegisterDialog.open = false
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    viewRegisterDialog.saving = false
  }
}

/**
 * 函数详情：抽屉里放整份 `FunctionMetadata` 投影。
 *
 * <p>与视图详情共用同一个抽屉壳；函数没有 metadata-location（只读投影、没有
 * 元数据文件），摘要列只给 uuid 与定义数。
 */
async function showFunction(row) {
  try {
    const payload = await icebergApi.loadFunction(prefix.value, currentLevels.value, row.name)
    const metadata = payload?.metadata || {}
    detail.title = `函数：${row.name}`
    detail.summary = [
      { label: 'function-uuid', value: metadata['function-uuid'] || '—' },
      { label: 'format-version', value: String(metadata['format-version'] ?? '—') },
      { label: '定义数', value: String((metadata.definitions || []).length) },
    ]
    detail.payload = payload
    detail.open = true
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 详情

const detail = reactive({ open: false, title: '', payload: null, summary: [] })

/**
 * 表详情去独立页（`IcebergTableView`）：快照、文件、优化这些页签需要一个整页的舞台，
 * 抽屉装不下——路由 `icebergTable` 的 `database` 段用点号显示名，与深链接同一口径。
 */
function openTable(name) {
  router.push({ name: 'icebergTable', params: { prefix: prefix.value, database: currentLabel.value, table: name } })
}

async function showView(row) {
  try {
    const payload = await icebergApi.loadView(prefix.value, currentLevels.value, row.name)
    const metadata = payload?.metadata || {}
    detail.title = `视图：${row.name}`
    detail.summary = [
      { label: 'metadata-location', value: payload?.['metadata-location'] || '—' },
      { label: '视图版本', value: String(metadata['current-version-id'] ?? '—') },
      { label: '版本数', value: String((metadata.versions || []).length) },
    ]
    detail.payload = payload
    detail.open = true
  } catch (error) {
    notifyError(error)
  }
}
</script>

<style scoped>
/* 建表的字段栅格：ID / 列名 / 必填 / 类型 / 描述 / 操作。
   与 Paimon 的那张表不是同一组列（这里没有主键与分区勾选），因此另起一套类名，
   不去改 BrowseView 里那套的列定义。 */
.pc-ice-grid {
  display: grid;
  grid-template-columns: 34px minmax(0, 1.1fr) 44px minmax(0, 1fr) minmax(0, 1.2fr) 60px;
  gap: 8px;
  align-items: center;
  padding: 6px 4px;
  border-bottom: 1px solid var(--pc-border);
}

.pc-ice-head {
  font-size: 12px;
  font-weight: 600;
  color: var(--pc-text-dim);
  padding-top: 0;
  padding-bottom: 7px;
}

.pc-ice-check,
.pc-ice-op {
  display: flex;
  align-items: center;
  justify-content: center;
}

.pc-ice-grid :deep(.el-checkbox) {
  margin-right: 0;
  height: auto;
}

.pc-ice-part {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 4px 0;
}

.pc-ice-part-field {
  flex: 0 0 26%;
}

.pc-ice-part-transform {
  flex: 0 0 34%;
}

.pc-ice-part-param {
  flex: 0 0 22%;
}

/* metadata-location 这类值很长且是路径：等宽字让它在截断处也读得出来 */
.pc-pre :deep(input) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
}

/* 详情抽屉头部的摘要：一眼看清 metadata 的骨架，完整原文在下面 */
.pc-summary {
  margin-bottom: 12px;
  padding: 10px 12px;
  background: var(--pc-surface-2);
  border: 1px solid var(--pc-border);
  border-radius: var(--pc-radius);
}

.pc-summary-row {
  display: flex;
  gap: 12px;
  font-size: 12.5px;
  line-height: 1.9;
}

.pc-summary-label {
  flex: 0 0 132px;
  color: var(--pc-text-dim);
}

.pc-summary-value {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
</style>
