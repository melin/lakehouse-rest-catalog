<template>
  <div class="pc-page pc-browse">
    <!-- Iceberg 分支整页换成 IcebergBrowseView，本页的页头与对话框都不该再出现：
         页头里的动作（刷新 / 新建数据库）是 Paimon 语义的，留下只会出现
         「同一屏两个页头、两套按钮」。IcebergBrowseView 自带页头与对话框。 -->
    <PageHeader
      v-if="!icebergCatalog"
      title="Catalog"
      :subtitle="`浏览 catalog「${prefix || '未选择'}」下的库与对象。这里看到的元数据与引擎客户端读到的是同一份，改动会立刻反映到它们的查询上。`"
    >
      <template #actions>
        <el-button size="small" @click="reloadDatabases">
          <el-icon><Refresh /></el-icon>
          <span class="pc-btn-text">刷新</span>
        </el-button>
        <el-button v-if="!lanceCatalog" size="small" type="primary" :disabled="icebergCatalog" @click="openCreateDatabase">
          <el-icon><Plus /></el-icon>
          <span class="pc-btn-text">新建数据库</span>
        </el-button>
      </template>
    </PageHeader>

    <TipAlert v-if="!prefix" type="warning" class="pc-hint">
      <template #title>还没有可浏览的 catalog</template>
      <div>
        服务端没有登记任何 catalog。先在「Catalogs」里创建一个，
        或确认 <code class="pc-mono">lakehouse.rest.auto-create-catalog</code> 是否开启。
      </div>
    </TipAlert>

    <!--
      Iceberg catalog：整页换成 Iceberg 协议面的浏览（namespace / tables / views）。
      两套契约的差异不是参数级的（资源名、标识符形状、分页字段名都不同），
      因此是另一个组件而不是本页里的一串 v-if；本页剩下一件事——
      **别把 Paimon 的请求发出去**，那由下面 loader 里的守卫保证。
    -->
    <IcebergBrowseView v-if="icebergCatalog" />

    <!--
      Lance catalog：本页不浏览。
      Lance 的 namespace / 表走 `/lance/v1/**`（不在 `/v1/{prefix}` 下），而这台服务端
      对 Lance 只做**元数据登记**（declare / register），没有 CreateTable、也不写数据集。
      因此这里不给一个「看起来能建库建表」的界面——建出来的东西与盘上的数据集对不上。
      能看的是仓库里的物理目录（`_versions/` 与 `data/`），在 catalog 详情的「文件列表」。
    -->
    <div v-else-if="lanceCatalog" class="pc-browse-body">
      <PanelCard>
        <SectionTitle title="Lance catalog 不在控制台浏览">
          <template #tip>
            <TipNote>
              控制台的目录浏览调的是 <code>/v1/{prefix}/databases/**</code>（Paimon 协议面），
              而 Lance 的资源树在 <code>/lance/v1/namespace/{id}</code> 与
              <code>/lance/v1/table/{id}</code>，两者不是参数级的差别。
              一期不做 Lance 的浏览页，是为了不给一个「看起来能建库建表」的入口——
              这台服务端对 Lance 没有写入路径。
            </TipNote>
          </template>
        </SectionTitle>
        <ul class="pc-lance-points">
          <li>
            看「表里到底有什么」：回到 catalog 详情的<b>文件列表</b>，从仓库根往下钻，
            <code class="pc-mono">_versions/</code> 里是 manifest、
            <code class="pc-mono">data/</code> 里是数据文件。
          </li>
          <li>
            看元数据：用 Lance 客户端连 <code class="pc-mono">/lance/v1</code>，
            或在服务端直接调 <code class="pc-mono">ListTableVersions</code> /
            <code class="pc-mono">ListTableTags</code>（这两个 operation 已实现）。
          </li>
          <li>
            这次登记的库与表仍在服务端的元数据里：<b>事件页</b>能看到 declare / register /
            deregister 的完整记录（payload 带 <code class="pc-mono">format=LANCE</code>）。
          </li>
        </ul>
        <div class="pc-lance-actions">
          <el-button size="small" @click="$router.push({ name: 'catalogDetail', params: { catalogName: prefix } })">
            <el-icon><Back /></el-icon>
            <span class="pc-btn-text">去 catalog 详情看文件列表</span>
          </el-button>
          <el-button size="small" @click="$router.push({ name: 'events' })">
            <el-icon><Files /></el-icon>
            <span class="pc-btn-text">看事件流</span>
          </el-button>
        </div>
      </PanelCard>
    </div>

    <div v-else class="pc-browse-body">
      <!-- 左：数据库 -->
      <aside class="pc-db-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">数据库</span>
          <span class="pc-dim">{{ databases.length }}</span>
        </div>
        <div class="pc-pane-tools">
          <el-input v-model="dbFilter" size="small" placeholder="过滤数据库" clearable>
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>
        <div class="pc-db-list pc-scroll">
          <el-skeleton v-if="dbLoading" :rows="4" animated class="pc-db-skeleton" />
          <el-alert v-else-if="dbError" type="error" :closable="false" show-icon :title="dbError" />
          <!-- 空态要说清是「本来就没有」还是「过滤掉了」：两种情况的下一步不同 -->
          <el-empty
            v-else-if="!filteredDatabases.length"
            :image-size="54"
            :description="dbFilter.trim() ? `没有匹配「${dbFilter.trim()}」的数据库` : '这个 catalog 下还没有数据库'"
          />
          <template v-else>
            <div
              v-for="name in filteredDatabases"
              :key="name"
              class="pc-db-item"
              :class="{ 'is-active': name === database }"
              @click="selectDatabase(name)"
            >
              <el-icon class="pc-db-icon"><Folder /></el-icon>
              <span class="pc-db-name" :title="name">{{ name }}</span>
              <el-icon class="pc-db-drop" title="删除数据库" @click.stop="confirmDropDatabase(name)">
                <Delete />
              </el-icon>
            </div>
          </template>
        </div>
      </aside>

      <!-- 右：对象 -->
      <section class="pc-obj-pane pc-card">
        <div class="pc-pane-head">
          <span class="pc-pane-title">
            {{ database || '未选择数据库' }}
          </span>
          <span class="pc-spacer" />
          <el-button
            v-if="database"
            size="small"
            text
            type="danger"
            @click="confirmDropDatabase(database)"
          >
            删除数据库
          </el-button>
        </div>

        <el-tabs v-model="tab" class="pc-tabs">
          <el-tab-pane label="表" name="tables" />
          <el-tab-pane label="视图" name="views" />
          <el-tab-pane label="函数" name="functions" />
        </el-tabs>

        <div class="pc-obj-body">
          <!-- 工具条放在 ResourceState 之外：空态时组件里只剩 el-empty，工具条跟着
               进去的话，「这个库里还没有视图」恰恰是最需要「新建视图」的时刻，
               按钮却消失了。表页签以前靠页头那颗按钮躲过这一点，现在三类对象
               一视同仁——工具条常驻，表格自己走加载 / 失败 / 空三态。 -->
          <div v-if="tab === 'tables'" class="pc-tools-row">
            <el-input v-model="tableFilter" size="small" placeholder="按表名前缀过滤" clearable class="pc-filter">
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <!-- 与其余页签一样把动作推到右边：那一行左边是「看什么」，右边是「做什么」 -->
            <span class="pc-spacer" />
            <el-button size="small" @click="openLocateTable">按表 ID 定位</el-button>
            <el-button size="small" @click="openRegisterTable">注册已有表</el-button>
            <el-button size="small" type="primary" :disabled="!database" @click="openCreateTable">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">新建表</span>
            </el-button>
          </div>
          <div v-else-if="tab === 'views'" class="pc-tools-row">
            <span class="pc-dim">SQL 视图，定义由服务端整篇保存。</span>
            <span class="pc-spacer" />
            <el-button size="small" type="primary" @click="openCreateView">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">新建视图</span>
            </el-button>
          </div>
          <div v-else class="pc-tools-row">
            <span class="pc-dim">函数定义支持 file / sql / lambda 三种，控制台按 JSON 原样提交。</span>
            <span class="pc-spacer" />
            <el-button size="small" type="primary" @click="openCreateFunction">
              <el-icon><Plus /></el-icon>
              <span class="pc-btn-text">新建函数</span>
            </el-button>
          </div>

          <ResourceState
            :loading="currentList.loading.value"
            :error="currentList.error.value"
            :empty="!visibleRows.length"
            :empty-text="emptyText"
          >
            <!-- 表 -->
            <template v-if="tab === 'tables'">
              <el-table v-loading="tablesList.loading.value" :data="tablesList.rows.value" size="small">
                <el-table-column label="表名" min-width="160">
                  <template #default="{ row }">
                    <RouterLink
                      class="pc-link"
                      :to="{ name: 'table', params: { prefix, database, table: row.name } }"
                    >
                      {{ row.name }}
                    </RouterLink>
                  </template>
                </el-table-column>
                <el-table-column label="表格式" width="100">
                  <template #default="{ row }">
                    <el-tag size="small" effect="plain">{{ tableKindLabel(row) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="表类型" width="96">
                  <template #default="{ row }">
                    <!-- Managed：本服务管它的元数据（schema-0 也由服务端写）；
                         外部表：位置由外部给定——register 登记进来的，或建表时自带 path 的
                         Object / Format / Lance 表（那批文件不归本服务管，服务端不往里写东西） -->
                    <span :title="row.isExternal
                      ? '外部表：位置由外部给定（register 登记，或建表时自带 path 的非 Paimon 表），服务端只记录位置、不写它的目录'
                      : 'Managed：元数据由本服务管理，建表时服务端还会写出 schema-0'">
                      {{ row.isExternal ? '外部表' : 'Managed' }}
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="创建时间" width="170">
                  <template #default="{ row }">
                    <!-- 操作人（createdBy / updatedBy）不进单元格：形如 sha256:… 的主体名
                         截断后只剩一两个字母，看不出信息还挤占时间；完整的时间与操作人
                         一起放在悬停提示里 -->
                    <span :title="`创建于 ${formatTime(row.createdAt)} · ${orPlaceholder(row.createdBy)}`">
                      <TimeText :millis="row.createdAt" />
                    </span>
                  </template>
                </el-table-column>
                <el-table-column label="元数据更新时间" width="170">
                  <template #default="{ row }">
                    <!-- updatedAt 是服务端这条表记录的最后改动时间：建表、改 schema、
                         重命名、快照提交与回滚都会推动它 -->
                    <span
                      title="服务端这条表记录的最后改动时间：建表 / 改 schema / 重命名 / 快照提交与回滚都会更新。悬停可看操作人"
                    >
                      <TimeText :millis="row.updatedAt" />
                    </span>
                  </template>
                </el-table-column>
                <!-- 列数变多后右面板在窄屏上会横向滚动，操作固定在右侧，
                     否则「重命名 / 删除」要滚到最右边才够得着 -->
                <el-table-column label="操作" width="76" align="right" fixed="right">
                  <template #default="{ row }">
                    <IconAction icon="EditPen" label="重命名" @click="openRenameTable(row.name)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropTable(row.name)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 视图 -->
            <template v-else-if="tab === 'views'">
              <el-table v-loading="viewsList.loading.value" :data="viewsList.rows.value" size="small">
                <el-table-column prop="name" label="视图名" min-width="200" />
                <el-table-column label="操作" width="100" align="right" fixed="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="详情" @click="showView(row)" />
                    <IconAction icon="EditPen" label="重命名" @click="openRenameView(row.name)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropView(row.name)" />
                  </template>
                </el-table-column>
              </el-table>
            </template>

            <!-- 函数 -->
            <template v-else-if="tab === 'functions'">
              <el-table v-loading="functionsList.loading.value" :data="functionsList.rows.value" size="small">
                <el-table-column prop="name" label="函数名" min-width="200" />
                <el-table-column label="操作" width="76" align="right" fixed="right">
                  <template #default="{ row }">
                    <IconAction icon="View" label="详情" @click="showFunction(row)" />
                    <IconAction icon="Delete" label="删除" type="danger" @click="confirmDropFunction(row.name)" />
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

    <!-- 新建数据库 -->
    <el-dialog v-model="dbDialog.open" title="新建数据库" width="480px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="名称" required>
          <el-input v-model="dbDialog.name" placeholder="例如 ods、dwd" />
        </el-form-item>
      </el-form>
      <SectionTitle title="选项（options）" />
      <PropertiesEditor v-model="dbDialog.options" hint="例如 owner；留空即可" />
      <template #footer>
        <el-button @click="dbDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="dbDialog.saving" @click="saveDatabase">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建表 -->
    <el-dialog
      v-model="tableDialog.open"
      title="新建表"
      width="880px"
      :close-on-click-modal="false"
      top="6vh"
      destroy-on-close
    >
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="表名" required>
          <el-input v-model="tableDialog.name" placeholder="例如 orders" />
        </el-form-item>
        <!-- 表格式决定这个对话框剩下的形状：字段要不要声明、能不能带主键与分区，
             以及提交时写进 options.type 的取值（Paimon 表不写，与服务端缺省一致）。
             每种格式的说明收进标签旁的问号图标，不在表单里铺开一段文字。
             Lance 表留在这一排里但不可选：直接删掉它，用户只会觉得「Paimon 支持的
             四种格式怎么少了一种」；摆在那里并说明原因，才答得上这个问题。 -->
        <el-form-item>
          <template #label>
            <FieldLabel label="表格式（type）" :tip="kindTip" />
          </template>
          <el-radio-group v-model="tableDialog.kind">
            <el-radio-button
              v-for="kind in TABLE_KINDS"
              :key="kind.value"
              :value="kind.value"
              :disabled="kind.disabled"
            >
              {{ kind.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="isFormatTable" label="文件格式（file.format）">
          <el-select v-model="tableDialog.fileFormat">
            <el-option v-for="format in FILE_FORMATS" :key="format" :label="format" :value="format" />
          </el-select>
        </el-form-item>
      </el-form>

      <!-- Object 表的列由 Paimon 固定（path / name / length / mtime / atime / owner），
           建表时不接受用户声明，所以这里不出现字段表格；提交时 fields 送空数组，
           服务端按表格式把固定列补上。详情页会把这六列展示出来。 -->
      <template v-if="isObjectTable">
        <SectionTitle title="字段信息（fields）">
          <template #tip>
            <TipNote>
              Object 表建表时不需要填写字段：它对外暴露的是目录里对象的元数据，
              列由 Paimon 固定为 path / name / length / mtime / atime / owner。
              这六列在表详情页的「字段列表」里可以看到。
            </TipNote>
          </template>
        </SectionTitle>
      </template>

      <template v-else>
        <!-- 字段列表把主键 / 非空 / 分区收进同一张表：对齐参考设计（列名称、主键、
             非空、分区字段、数据类型、描述、操作一排），勾选即声明，不再单独设下拉。
             Format 表没有这三列——Paimon 不允许 format 表带主键，分区也不由 catalog 管，
             因此表头与行一起收起这三列，栅格另走四列版。 -->
        <SectionTitle title="字段信息（fields）">
          <template #tip>
            <TipNote v-if="supportsKeys">
              主键字段自动非空且不可取消；非主键字段勾「非空」会在创建时被拦下——Paimon 的字段没有
              独立的非空属性，该约束只能通过主键隐式成立。字段 id 由服务端按顺序补齐，这里不需要填。
            </TipNote>
            <TipNote v-else>
              Format 表只描述列：它的数据是目录里按 {{ tableDialog.fileFormat }} 格式存好的文件，
              没有主键，分区也不由 catalog 管理。字段 id 由服务端按顺序补齐，这里不需要填。
            </TipNote>
          </template>
        </SectionTitle>
        <div class="pc-field-table">
          <div class="pc-field-grid pc-field-head" :class="{ 'pc-field-grid-plain': !supportsKeys }">
            <span>列名称</span>
            <template v-if="supportsKeys">
              <span class="pc-field-check" title="勾选后该字段进入 primaryKeys，表即为主键表；主键字段自动非空且不可取消">主键</span>
              <span class="pc-field-check" title="非空约束只能通过主键隐式成立：勾选非主键字段的非空，创建时会被拦下">非空</span>
              <span class="pc-field-check" title="勾选后该字段进入 partitionKeys；填了主键时，分区字段必须是主键的子集">分区字段</span>
            </template>
            <span>数据类型</span>
            <span>描述</span>
            <span class="pc-field-op">操作</span>
          </div>
          <div
            v-for="(field, index) in tableDialog.fields"
            :key="index"
            class="pc-field-grid"
            :class="{ 'pc-field-grid-plain': !supportsKeys }"
          >
            <el-input v-model="field.name" size="small" placeholder="请输入名称" />
            <template v-if="supportsKeys">
              <el-checkbox
                v-model="field.primary"
                size="small"
                class="pc-field-check"
                @change="onPrimaryChange(field)"
              />
              <!-- 主键字段的非空是 Paimon 的隐式约束：勾主键时代替用户勾上并锁住，
                   不能取消；取消主键后恢复可编辑，是否保留非空由用户决定 -->
              <el-checkbox v-model="field.nonNull" size="small" class="pc-field-check" :disabled="field.primary" />
              <el-checkbox v-model="field.partition" size="small" class="pc-field-check" />
            </template>
            <!-- 不用原生 datalist：Chrome 会给带 list 的 input 自带一个下拉指示器，
                 嵌在 Element 的 wrapper 里位置错乱；el-select 的 allow-create 同样支持手工输入。 -->
            <el-select
              v-model="field.type"
              filterable
              allow-create
              default-first-option
              size="small"
              placeholder="如 BIGINT / VARCHAR(64)"
            >
              <el-option v-for="type in PAIMON_TYPES" :key="type" :label="type" :value="type" />
            </el-select>
            <el-input v-model="field.description" size="small" placeholder="请输入描述信息" />
            <span class="pc-field-op">
              <el-button size="small" text type="danger" @click="removeField(index)">删除</el-button>
              <!-- 上移 / 下移只在自己合法的行出现（首行没有上移、末行没有下移），
                   与参考设计一致；字段顺序决定字段 id 的分配顺序，也是分区键列表的排列。 -->
              <template v-if="index > 0">
                <span class="pc-field-sep">|</span>
                <el-button size="small" text type="primary" @click="moveField(index, -1)">上移</el-button>
              </template>
              <template v-if="index < tableDialog.fields.length - 1">
                <span class="pc-field-sep">|</span>
                <el-button size="small" text type="primary" @click="moveField(index, 1)">下移</el-button>
              </template>
            </span>
          </div>
          <el-button
            size="small"
            text
            type="primary"
            @click="tableDialog.fields.push(emptyField())"
          >
            <el-icon><Plus /></el-icon>
            <span class="pc-repeat-add">添加一项</span>
          </el-button>
        </div>
      </template>

      <SectionTitle title="表注释（可选）" />
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="表注释（comment）">
          <el-input v-model="tableDialog.comment" />
        </el-form-item>
      </el-form>

      <SectionTitle title="表属性（options）">
        <template #tip>
          <TipNote>
            表级属性。Paimon 表的属性会跟着 schema 写进仓库；Format 表的文件格式由上面那个下拉决定
            （<code>file.format</code>）。非 Paimon 表的 <code>path</code> 就是它的<strong>数据目录</strong>：
            可以指向一个已经存在的目录（比如对象存储里放图片的那个前缀），留空则由服务端按表位置分配。
            <br /><br />
            <template v-if="defaultOptionNames.length">
              当前 catalog 配了 {{ defaultOptionNames.length }} 项默认参数（<code>table-default.*</code>），
              已按<strong>去掉前缀</strong>的形式预填在下面：{{ defaultOptionNames.join('、') }}。
              可以直接改或删；服务端建表时只补「表里没写的」那些，<strong>表级参数优先</strong>。
            </template>
            <template v-else>
              当前 catalog 没有配默认参数（<code>table-default.*</code>）。要配的话，
              去「Catalogs」页里这个 catalog 的「配置」页签。
            </template>
          </TipNote>
        </template>
      </SectionTitle>
      <PropertiesEditor
        v-model="tableDialog.options"
        hint="例如 bucket = 4、merge-engine = deduplicate；type 由上面的表格式决定，非 Paimon 表的 path 留空时由服务端按表位置补齐"
      />

      <template #footer>
        <el-button @click="tableDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="tableDialog.saving" @click="saveTable">创建</el-button>
      </template>
    </el-dialog>

    <!-- 按表 ID 定位 -->
    <el-dialog v-model="locateDialog.open" title="按表 ID 定位" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item required>
          <template #label>
            <FieldLabel label="表 ID">
              表 ID 在表详情页与引擎日志里都会出现。定位不需要知道它在哪个库，
              服务端会返回它所属的 database 与表名。
            </FieldLabel>
          </template>
          <el-input v-model="locateDialog.tableId" placeholder="例如 8f2c1e0a-..." />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="locateDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="locateDialog.saving" @click="locateTable">定位</el-button>
      </template>
    </el-dialog>

    <!-- 注册已有表 -->
    <el-dialog v-model="registerDialog.open" title="注册已有表" width="520px" :close-on-click-modal="false"
      destroy-on-close>
      <TipAlert type="info" class="pc-hint">
        <template #title>注册只写元数据，不搬动数据</template>
        <div>路径下必须已存在一张完整的 Paimon 表（含 schema 与快照目录），否则读取时会失败。</div>
      </TipAlert>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="表名" required>
          <el-input v-model="registerDialog.name" />
        </el-form-item>
        <el-form-item label="表路径（path）" required>
          <el-input v-model="registerDialog.path" placeholder="file:///tmp/paimon-warehouse/default.db/orders" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="registerDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="registerDialog.saving" @click="saveRegister">注册</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 -->
    <el-dialog v-model="renameDialog.open" :title="renameDialog.title" width="440px" :close-on-click-modal="false"
      destroy-on-close>
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

    <!-- 新建视图 -->
    <el-dialog v-model="viewDialog.open" title="新建视图" width="640px" :close-on-click-modal="false"
      destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="视图名" required>
          <el-input v-model="viewDialog.name" />
        </el-form-item>
        <el-form-item label="查询语句（query）" required>
          <el-input v-model="viewDialog.query" type="textarea" :rows="5" placeholder="SELECT id, amount FROM orders WHERE amount > 0" />
        </el-form-item>
        <el-form-item label="注释（comment）">
          <el-input v-model="viewDialog.comment" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="viewDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="viewDialog.saving" @click="saveView">创建</el-button>
      </template>
    </el-dialog>

    <!-- 新建函数 -->
    <el-dialog v-model="functionDialog.open" title="新建函数" width="680px" :close-on-click-modal="false" top="6vh"
      destroy-on-close>
      <el-form label-position="top" size="small" class="pc-form">
        <el-form-item label="函数名" required>
          <el-input v-model="functionDialog.name" />
        </el-form-item>
        <el-form-item label="入参（inputParams）">
          <div class="pc-repeat">
            <div v-for="(param, index) in functionDialog.inputParams" :key="index" class="pc-repeat-row">
              <el-input v-model="param.name" size="small" placeholder="参数名" class="pc-field-name" />
              <el-input v-model="param.type" size="small" placeholder="类型" class="pc-field-type" />
              <el-button size="small" text type="danger" @click="functionDialog.inputParams.splice(index, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text type="primary" @click="functionDialog.inputParams.push({ name: '', type: '' })">
              <el-icon><Plus /></el-icon>
              <span class="pc-repeat-add">添加参数</span>
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="返回参数（returnParams）">
          <div class="pc-repeat">
            <div v-for="(param, index) in functionDialog.returnParams" :key="index" class="pc-repeat-row">
              <el-input v-model="param.name" size="small" placeholder="参数名" class="pc-field-name" />
              <el-input v-model="param.type" size="small" placeholder="类型" class="pc-field-type" />
              <el-button size="small" text type="danger" @click="functionDialog.returnParams.splice(index, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button size="small" text type="primary" @click="functionDialog.returnParams.push({ name: '', type: '' })">
              <el-icon><Plus /></el-icon>
              <span class="pc-repeat-add">添加参数</span>
            </el-button>
          </div>
        </el-form-item>
        <el-form-item label="确定性的（deterministic）">
          <el-switch v-model="functionDialog.deterministic" />
        </el-form-item>
        <el-form-item required>
          <template #label>
            <FieldLabel label="函数定义（definitions）">
              键名是定义名，值形如 <code>{type, definition}</code>；
              <code>type</code> 取 file / sql / lambda。
            </FieldLabel>
          </template>
          <el-input
            v-model="functionDialog.definitions"
            type="textarea"
            :rows="7"
            class="pc-pre"
            placeholder='{"sql": {"type": "sql", "definition": "SELECT ..."}}'
          />
        </el-form-item>
        <el-form-item label="注释（comment）">
          <el-input v-model="functionDialog.comment" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="functionDialog.open = false">取消</el-button>
        <el-button type="primary" :loading="functionDialog.saving" @click="saveFunction">创建</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="detail.open" :title="detail.title" size="620px">
      <JsonBlock :value="detail.payload" />
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'

import TipAlert from '@/components/TipAlert.vue'
import FieldLabel from '@/components/FieldLabel.vue'
import IconAction from '@/components/IconAction.vue'
import JsonBlock from '@/components/JsonBlock.vue'
import PageHeader from '@/components/PageHeader.vue'
import PanelCard from '@/components/PanelCard.vue'
import PropertiesEditor from '@/components/PropertiesEditor.vue'
import ResourceState from '@/components/ResourceState.vue'
import SectionTitle from '@/components/SectionTitle.vue'
import TimeText from '@/components/TimeText.vue'
import TipNote from '@/components/TipNote.vue'
import TablePager from '@/components/TablePager.vue'
import IcebergBrowseView from '@/views/IcebergBrowseView.vue'
import { catalogApi } from '@/api/catalog.js'
import { confirmDanger, notifyError, notifySuccess, useRemotePaging, useResource } from '@/composables/index.js'
import { session } from '@/store/session.js'
import { isIcebergCatalog, isLanceCatalog } from '@/utils/catalogFormat.js'
import { formatTime, orPlaceholder, prettyJson } from '@/utils/format.js'
import { tableKindLabel } from '@/utils/tableKind.js'
import { tableDefaultsOf } from '@/utils/tableDefaults.js'

/**
 * 目录浏览器：左侧数据库，右侧对象。
 *
 * <p><b>右侧三个列表（表 / 视图 / 函数）走远程分页</b>：页码、页大小、表名前缀
 * 全部下推，页面只持有一页，底部是统一的分页器。以前是「`maxResults` + 加载更多」
 * 的追加式翻页——它的毛病是「共几个」永远说不出来，翻过第 3 次之后手上是哪一段
 * 也说不清。左侧数据库面板是个很窄的侧栏，塞页码没有意义，仍旧一次取满并在
 * 浏览器里按关键字过滤（见 `filteredDatabases`）。
 */

const PAIMON_TYPES = [
  'BOOLEAN', 'TINYINT', 'SMALLINT', 'INT', 'BIGINT', 'FLOAT', 'DOUBLE',
  'DECIMAL(10, 2)', 'CHAR(10)', 'VARCHAR(64)', 'STRING', 'BINARY(16)', 'VARBINARY(64)', 'BYTES',
  'DATE', 'TIME', 'TIMESTAMP', 'TIMESTAMP_LTZ',
  'ARRAY<STRING>', 'MAP<STRING, STRING>', 'ROW<a STRING, b INT>',
]

/**
 * 表格式。取值就是 Paimon `CoreOptions.TYPE` 的取值，服务端的 `TableKinds` 按同一组字符串识别，
 * 提交时写进 `schema.options.type`。
 *
 * <p>Paimon 表（`table`）刻意不写这个键：服务端把「没有 type」当作 Paimon 表，
 * 少一个可以写错的地方，也与既有表一致（它们都没有这个选项）。
 *
 * <p>`disabled` 是**控制台自己的策略**，不是服务端的限制：服务端照常接受
 * `type=lance-table` 的建表请求（Spark SQL 与 HTTP 都通，见
 * `docs/spark-paimon-rest-e2e.md` §4.4），这里只是不给这个入口。
 */
const TABLE_KINDS = [
  {
    value: 'table',
    label: 'Paimon 表',
    hint: '快照托管的数据表：支持主键、非空与分区字段，写入与更新都走 Paimon 的提交协议。',
  },
  {
    value: 'format-table',
    label: 'Format 表',
    hint: '目录里已经存在的同一格式文件（CSV / Parquet / ORC / JSON / text），不经过快照管理：字段只描述列，没有主键、非空与分区设置。',
  },
  {
    value: 'object-table',
    label: 'Object 表',
    hint: '目录里非结构化对象的元数据表：列由 Paimon 固定，不需要填写字段。',
  },
  {
    value: 'lance-table',
    label: 'Lance 表',
    hint: 'Lance 数据集表。',
    disabled: true,
    disabledReason: 'Paimon 2.0.0 还没实现 Lance 表的读写——Spark 侧的包装类没有声明任何读写能力，查询与写入都会在分析阶段被拒（不是缺依赖）。控制台因此不给建表入口；已存在的 Lance 表仍会正常列出与展示。',
  },
]

/** Format 表的文件格式（file.format）。默认值跟着 Paimon 走（parquet）。 */
const FILE_FORMATS = ['parquet', 'orc', 'csv', 'json', 'text']

const route = useRoute()
const router = useRouter()

const tab = ref('tables')
const dbFilter = ref('')
const tableFilter = ref('')

/** 当前 catalog：URL 上的 `catalog` 优先，其次全局选择。 */
const prefix = computed(() => route.query.catalog || session.state.selectedCatalog || '')
const database = computed(() => route.query.database || '')

/**
 * 这个 catalog 是不是 Iceberg 协议面。
 *
 * <p>**本页只服务 Paimon 协议面**：它调的是 `/v1/{prefix}/databases/**`，
 * 而 Iceberg catalog 服务的是 `/v1/{prefix}/namespaces/**`。对 Iceberg catalog
 * 打这些路径会被服务端以「格式不匹配」拒绝（一句指名原因的 404，见
 * `docs/iceberg-rest-catalog-design.md` §3.3），所以模板整页换成
 * `IcebergBrowseView`，同时**loader 里也挡一道**——watch 与「刷新 / 重试」
 * 都会绕过模板直接调 `load()`，只靠 v-if 仍会把请求发出去。
 *
 * <p>格式读的是 session 里的 catalog 列表（`catalogFormat` 只在那里），
 * 而首屏它可能还没取回来，见下面 watch 里的 `ensureCatalogs()`。
 */
const icebergCatalog = computed(() => isIcebergCatalog(session.catalogByName(prefix.value)))

/**
 * 这个 catalog 是不是 Lance 协议面。
 *
 * <p>Lance 与 Iceberg 的共同点是「本页一条路径都不适用」，差别是：Iceberg 换一个组件
 * （`IcebergBrowseView` 走 `namespaces/**`），Lance 换成一段说明——这台服务端对 Lance
 * 只做元数据登记，没有 CreateTable、也不写数据集，给一个能建库建表的界面是错误的承诺。
 *
 * <p>同样要**在 loader 里再挡一道**：`refresh` 与 watch 都会绕过模板直接调 load()。
 */
const lanceCatalog = computed(() => isLanceCatalog(session.catalogByName(prefix.value)))

/**
 * 首屏可能来不及：`catalogsLoaded` 之前读格式只会得到「不是 Iceberg」，
 * 于是会对着一个 Iceberg catalog 发出注定 404 的请求。等它一次。
 *
 * <p>与顶栏的 `session.bootstrap()` 可能并发发出同一个列表请求——代价是一次
 * 便宜的 GET，换来的是「不猜格式」。猜错的表现是页面上出现一串 404，
 * 而那看起来像故障、不像「一期不支持」。
 */
async function ensureCatalogs() {
  if (session.state.catalogsLoaded) return
  await session.loadCatalogs().catch(() => null)
}

// ------------------------------------------------------------------ 数据库

// 由下面的 watch 驱动首次加载，这里不再 immediate，
// 否则挂载时会与 watch 的首次回调各发一次请求。
const databaseResource = useResource(async () => {
  if (!prefix.value) return []
  // 这一层守卫是必要的（而不是只靠 watch 里的那一处）：refresh 与「重试」
  // 都会直接调 load()，绕过 watch。守卫写在 loader 里，任何入口都盖得住。
  if (icebergCatalog.value || lanceCatalog.value) return []
  // 侧栏一次取满（服务端 max-page-size）：它是个窄栏，塞页码没有意义
  const payload = await catalogApi.listDatabases(prefix.value, {
    maxResults: session.state.meta?.service?.maxPageSize || 1000,
  })
  return payload.databases || []
}, { immediate: false })

const databases = computed(() => databaseResource.data.value || [])
const dbLoading = computed(() => databaseResource.loading.value)
const dbError = computed(() => databaseResource.error.value)

const filteredDatabases = computed(() => {
  const keyword = dbFilter.value.trim().toLowerCase()
  if (!keyword) return databases.value
  return databases.value.filter((name) => name.toLowerCase().includes(keyword))
})

function reloadDatabases() {
  databaseResource.load()
  loadCurrentList()
}

function selectDatabase(name) {
  router.replace({ name: 'browse', query: { catalog: prefix.value, database: name } })
}

// ------------------------------------------------------------------ 对象列表

/**
 * 表名过滤：**前缀**，不是子串。
 *
 * <p>服务端的 `tableNamePattern` 走 `Patterns.matches`——SQL LIKE 的「只支持前缀」实现，
 * 于是 `ord` 收成 `ord%`。这与类型成串的库名不同：表名有命名前缀（`dim_` / `ods_` /
 * `ads_`）的习惯，前缀过滤正好对得上；子串过滤要服务端改匹配语义，而那条路会被
 * 引擎客户端共用，不该为控制台的一格输入框改。
 */
function tableNamePattern() {
  const keyword = tableFilter.value.trim()
  return keyword ? `${keyword}%` : undefined
}

/**
 * 三个对象列表共用的取数函数：只差回包里的字段名与调用的端点。
 *
 * <p>Iceberg catalog 这一层短路是必要的——这三条路径都是 Paimon 协议面的，
 * 对一个 Iceberg catalog 发出去只会拿到 404。守卫收在 loader 里，页签切换、
 * 刷新、「重试」都盖得住。
 */
async function fetchObjects(field, call, { page, pageSize }) {
  if (!prefix.value || !database.value || icebergCatalog.value || lanceCatalog.value) {
    return { items: [], total: 0 }
  }
  const payload = await call(prefix.value, database.value, {
    tableNamePattern: tableNamePattern(),
    page,
    pageSize,
  })
  return { items: payload[field] || [], total: payload.total ?? 0 }
}

/**
 * 表列表走 `table-details` 而不是只回表名的 `/tables`：列表要显示表格式、表类型
 * （Managed / 外部表）与创建、更新时间，这些都在 `GetTableResponse` 里。
 * 两者的页码模式完全一致，换成它不改变翻页行为。
 */
const tablesList = useRemotePaging(
  (paging) => fetchObjects('tableDetails', catalogApi.listTableDetails, paging),
  { immediate: false },
)
const viewsList = useRemotePaging(
  (paging) => fetchObjects('views', catalogApi.listViews, paging),
  { immediate: false },
)
const functionsList = useRemotePaging(
  (paging) => fetchObjects('functions', catalogApi.listFunctions, paging),
  { immediate: false },
)

const lists = {
  tables: tablesList,
  views: viewsList,
  functions: functionsList,
}

const currentList = computed(() => lists[tab.value] || tablesList)

const emptyText = computed(() => {
  // 表页签带过滤框，其余页签没有：过滤到空时要说明是过滤的结果，
  // 否则「这个数据库里还没有表」会让人以为真的没建表
  if (tab.value === 'tables' && tableFilter.value.trim() && currentList.value.rows.value.length) {
    return `没有匹配「${tableFilter.value.trim()}」的表`
  }
  return {
    tables: '这个数据库里还没有表',
    views: '这个数据库里还没有视图',
    functions: '这个数据库里还没有函数',
  }[tab.value] || '暂无数据'
})

/** 当前页签要展示的行：过滤与切页都在服务端，这里就是服务端给的这一页。 */
const visibleRows = computed(() => currentList.value.rows.value)

/** 哪些页签已经拉过：切来切去不该重复请求，也不该把页码打回第一页。 */
const loadedTabs = new Set()

/** 拉当前页签的第一页，并记下「这个页签已经拉过」。 */
function loadCurrentList() {
  loadedTabs.add(tab.value)
  currentList.value.reload()
}

// 表名前缀变化：回第一页重取。逐字符发请求会把每次按键都变成一次全表扫描，
// 300ms 内的连续输入只在最后发一次。
let tableFilterTimer = null
watch(tableFilter, () => {
  if (tableFilterTimer) clearTimeout(tableFilterTimer)
  tableFilterTimer = setTimeout(() => {
    loadedTabs.add('tables')
    tablesList.reload()
  }, 300)
})

watch([prefix, database], async ([nextPrefix]) => {
  if (nextPrefix && nextPrefix !== session.state.selectedCatalog) {
    session.selectCatalog(nextPrefix)
  }
  // 先确认 catalog 格式再决定发不发请求：格式在 catalog 列表里，
  // 而深链接直接打开本页时它可能还没到（见 ensureCatalogs 的说明）。
  await ensureCatalogs()
  // 换了 catalog 或库，三个列表全部作废：页码、过滤条件、已加载标记都要归零
  loadedTabs.clear()
  databaseResource.load()
  loadCurrentList()
}, { immediate: true })

// 数据库列表到手后，若 URL 上没有指定库，自动落到第一个：
// 打开「目录浏览」时右侧直接有内容，不用先点一下。
watch(databases, (list) => {
  if (!database.value && list.length) {
    router.replace({ name: 'browse', query: { catalog: prefix.value, database: list[0] } })
  }
})

watch(tab, (name) => {
  // 首次切到某个页签时才拉数据，避免打开页面就发四个请求
  if (!loadedTabs.has(name)) {
    loadCurrentList()
  }
})

// ------------------------------------------------------------------ 新建数据库

const dbDialog = reactive({ open: false, name: '', options: {}, saving: false })

function openCreateDatabase() {
  dbDialog.name = ''
  dbDialog.options = {}
  dbDialog.open = true
}

async function saveDatabase() {
  if (!dbDialog.name.trim()) {
    notifyError(new Error('请填写数据库名称'))
    return
  }
  dbDialog.saving = true
  try {
    await catalogApi.createDatabase(prefix.value, {
      name: dbDialog.name.trim(),
      options: dbDialog.options,
    })
    notifySuccess(`已创建数据库 ${dbDialog.name.trim()}`)
    dbDialog.open = false
    databaseResource.load()
  } catch (error) {
    notifyError(error)
  } finally {
    dbDialog.saving = false
  }
}

async function confirmDropDatabase(name) {
  const ok = await confirmDanger({
    title: '删除数据库',
    target: `确认删除数据库「${name}」？`,
    detail: '数据库下的表会一并从元数据中移除。catalog API 的 drop 不会删除底层数据文件，'
      + '但这些文件将不再被任何 catalog 引用。',
  })
  if (!ok) return
  try {
    await catalogApi.dropDatabase(prefix.value, name)
    notifySuccess(`已删除数据库 ${name}`)
    if (database.value === name) {
      router.replace({ name: 'browse', query: { catalog: prefix.value } })
    }
    databaseResource.load()
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 表

const tableDialog = reactive({
  open: false,
  name: '',
  kind: 'table',
  fileFormat: 'parquet',
  fields: [],
  comment: '',
  options: {},
  saving: false,
})

/** 当前表格式的说明文案，显示在选择器下方。 */
const currentKind = computed(
  () => TABLE_KINDS.find((item) => item.value === tableDialog.kind) || TABLE_KINDS[0],
)

/**
 * 表格式问号里的话：当前选项的说明，后面接不可选项（目前只有 Lance 表）的原因。
 *
 * <p>为什么把两边拼在一起：禁用的选项点不中，`currentKind.hint` 就永远轮不到它，
 * 而「怎么少了一种格式」恰恰是用户在这个问号下最想问的。拼装写成从 `TABLE_KINDS`
 * 里筛 `disabled`，以后谁再禁用一种，说明自动跟上。
 */
const kindTip = computed(() => {
  const blocked = TABLE_KINDS.filter((kind) => kind.disabled)
  if (!blocked.length) {
    return currentKind.value.hint
  }
  return `${currentKind.value.hint} ${blocked
    .map((kind) => `${kind.label}（${kind.value}）不可选：${kind.disabledReason}`)
    .join(' ')}`
})

const isFormatTable = computed(() => tableDialog.kind === 'format-table')
const isObjectTable = computed(() => tableDialog.kind === 'object-table')

/**
 * 主键 / 非空 / 分区三列只在 Paimon 表出现。
 *
 * <p>Format 表没有主键语义（Paimon 的 `CatalogUtils.validateFormatTableOptions`
 * 明确禁止），分区也不由 catalog 管理（分区来自目录结构），所以这三列连同表头一起收起；
 * Object 表压根不渲染字段表格（列由 Paimon 固定）；Lance 表虽然与 Paimon 表同构，
 * 但已不在可选之列（见 `TABLE_KINDS`），走不到这里。
 */
const supportsKeys = computed(() => tableDialog.kind !== 'format-table')

// ------------------------------------------------- catalog 默认表属性（table-default.*）

/**
 * 当前 catalog 的默认表属性（键已去前缀），打开建表对话框时预填进表属性。
 *
 * <p>读的是 catalog 列表里的 `properties`（保存默认参数后
 * `CatalogDetailView` 会 `session.loadCatalogs()`，这里跟着就更新了），
 * 因此不需要为它多发一个请求。
 */
const catalogDefaults = computed(() => tableDefaultsOf(session.currentCatalog()?.properties))

/** 预填进表单的那一份快照；切表格式时用它区分「默认值」与「用户自己填的」。 */
const prefilledDefaults = ref({})

/** 预填的默认参数名，供说明文案列出——只对 Paimon 表成立。 */
const defaultOptionNames = computed(() => Object.keys(prefilledDefaults.value))

/**
 * 表格式变了，默认参数跟着进出表单。
 *
 * <p>为什么要动它：服务端**只对 Paimon 表**合并默认参数（见服务端 `TableDefaults`），
 * 而表单里的表属性是原样发送的。切到 Format / Object 表后若把预填值留在原地，
 * 它们就成了表级参数被真的写进去——与服务端的规则对不上，看起来还会像「默认值也进了 Format 表」。
 *
 * <p>只删「取值仍等于默认值」的那些：用户改过的说明他想显式写进这张表，保留。
 */
watch(() => tableDialog.kind, (kind) => {
  const defaults = prefilledDefaults.value
  if (kind === 'table') {
    for (const [name, value] of Object.entries(defaults)) {
      if (!(name in tableDialog.options)) {
        tableDialog.options[name] = value
      }
    }
    return
  }
  for (const [name, value] of Object.entries(defaults)) {
    if (tableDialog.options[name] === value) {
      delete tableDialog.options[name]
    }
  }
})

/** 一行新字段的初始形状：三个勾选默认全不选，由用户按需勾。 */
function emptyField() {
  return { name: '', type: '', description: '', primary: false, nonNull: false, partition: false }
}

/**
 * 删除一个字段行。主键 / 非空 / 分区都是行内的勾选状态，随行一起消失，
 * 不再像旧的下拉方案那样要回头清理「指向已删字段的残留值」。
 */
function removeField(index) {
  tableDialog.fields.splice(index, 1)
}

/**
 * 上移 / 下移：与相邻行整体对调，勾选状态跟着字段走。
 *
 * <p>字段顺序是有语义的：字段 id 由服务端按行序分配，分区键列表的排列
 * 也来自行序，所以这个操作不是纯外观，调完即生效、无需额外提交。
 * 按钮的可见性在模板里按行号裁剪（首行无上移、末行无下移），
 * 这里不做越界防御——非法调用本来就不该发生。
 */
function moveField(index, direction) {
  const target = index + direction
  const fields = tableDialog.fields
  ;[fields[index], fields[target]] = [fields[target], fields[index]]
}

/**
 * 勾上主键时把非空一并勾上并锁住。
 *
 * <p>Paimon 的字段没有独立的非空属性（REST 的 DataField 只有
 * id / name / type / description / defaultValue），主键字段的非空是引擎隐式强制的，
 * 不存在「主键但可空」的表。所以这一步不是便利，而是如实表达约束：
 * 模板里同时把非空勾选框设为 `:disabled="field.primary"`，锁住不让取消。
 * 取消主键后恢复可编辑，是否保留非空由用户决定。
 */
function onPrimaryChange(field) {
  if (field.primary) {
    field.nonNull = true
  }
}

function openCreateTable() {
  tableDialog.name = ''
  tableDialog.kind = 'table'
  tableDialog.fileFormat = 'parquet'
  tableDialog.fields = [
    { ...emptyField(), name: 'id', type: 'BIGINT' },
    { ...emptyField(), name: 'data', type: 'STRING' },
  ]
  tableDialog.comment = ''
  // Paimon 表预填当前 catalog 的默认参数（table-default.*，去前缀）：表单里看得见，
  // 才不会有「我的表怎么凭空多了个属性」的疑问；服务端同样会合并它们，
  // 表级参数优先，所以预填的值与最终结果一致。切到非 Paimon 表格式时会被移出，见上面的 watch。
  prefilledDefaults.value = { ...catalogDefaults.value }
  tableDialog.options = { ...prefilledDefaults.value }
  tableDialog.open = true
}

async function saveTable() {
  // 表格式的选择器已把不可选项标成 disabled，但那是界面状态，不是校验：
  // 判据落在 `TABLE_KINDS` 上再拦一次，免得哪天状态被别的路径改掉、或者
  // 新增不可选格式时只改了配置没接上拦截。服务端不做这个限制（见 `TABLE_KINDS` 注释）。
  const blockedKind = TABLE_KINDS.find((kind) => kind.value === tableDialog.kind)
  if (blockedKind?.disabled) {
    notifyError(new Error(`${blockedKind.label}暂不可建：${blockedKind.disabledReason}`))
    return
  }
  const name = tableDialog.name.trim()
  if (!name) {
    notifyError(new Error('请填写表名'))
    return
  }
  // Object 表不声明字段：它的列由 Paimon 固定（TableKinds 在服务端补上），
  // 因此下面的字段校验与主键 / 分区收集整段跳过，请求里的 fields 是空数组。
  let fields = []
  let primaryKeys = []
  let partitionKeys = []
  if (!isObjectTable.value) {
    const rows = tableDialog.fields
    // 勾了任一开关的字段必须填写完整：勾选是「这个字段要进 primaryKeys /
    // partitionKeys」的声明，协议里这些键是纯字段名列表，指向一行残缺数据
    // 只能由 Paimon 报错，在这里提前拦下能指出具体是哪个字段。
    const flaggedIncomplete = rows.filter(
      (row) => (row.primary || row.partition || row.nonNull) && !(row.name.trim() && row.type.trim()),
    )
    if (flaggedIncomplete.length) {
      notifyError(
        new Error(`勾选了主键 / 非空 / 分区的字段，字段名与类型都必须填写完整，请修正：${flaggedIncomplete.map((row) => row.name.trim() || `第 ${rows.indexOf(row) + 1} 行`).join(', ')}`),
      )
      return
    }
    const valid = rows.filter((row) => row.name.trim() && row.type.trim())
    if (!valid.length) {
      notifyError(new Error('至少填写一个字段（字段名与类型都不能为空）'))
      return
    }
    // 重名检查只针对被勾选的字段：主键 / 分区在协议里是字段名列表，两个同名行
    // 都勾了主键时，同一个名字会出现两次，语义不明。没勾选的暂时重名仍允许
    // （用户可能还没改完），真正的唯一性由 Paimon 在提交时把关。
    const flaggedNames = valid
      .filter((row) => row.primary || row.partition)
      .map((row) => row.name.trim())
    const duplicated = flaggedNames.filter((key, index) => flaggedNames.indexOf(key) !== index)
    if (duplicated.length) {
      notifyError(
        new Error(`主键与分区字段不能指向重名字段，请修正：${[...new Set(duplicated)].join(', ')}`),
      )
      return
    }
    // 勾选即声明：主键 / 分区从行内勾选状态收集，按行序排列。
    primaryKeys = valid.filter((row) => row.primary).map((row) => row.name.trim())
    partitionKeys = valid.filter((row) => row.partition).map((row) => row.name.trim())
    // 非空的唯一落点是主键：REST 的 DataField 没有非空属性，勾了非空又不在
    // 主键里的字段没有任何地方可存，静默丢弃等于骗人，在这里明确拦下。
    const nonPrimaryNonNull = valid
      .filter((row) => row.nonNull && !row.primary)
      .map((row) => row.name.trim())
    if (nonPrimaryNonNull.length) {
      notifyError(
        new Error(`Paimon 只支持主键隐式非空，这些字段的「非空」无法保存，请去掉勾选或加入主键：${nonPrimaryNonNull.join(', ')}`),
      )
      return
    }
    // Paimon 的约束：主键非空时，分区键必须是主键的子集。提前拦下，
    // 用户看到的是「哪几个字段不对」，而不是 Paimon 抛出的一行英文。
    const outsidePrimary = primaryKeys.length
      ? partitionKeys.filter((key) => !primaryKeys.includes(key))
      : []
    if (outsidePrimary.length) {
      notifyError(
        new Error(`Paimon 要求分区键是主键的一部分，请把这些字段也加进主键：${outsidePrimary.join(', ')}`),
      )
      return
    }
    fields = valid.map((row, index) => ({
      id: index + 1,
      name: row.name.trim(),
      type: row.type.trim(),
      description: row.description?.trim() || null,
    }))
  }
  // 表格式由选择器决定，表属性里手填的 type 一律让位——否则会出现
  // 「表单选 Paimon 表、实际建出 Format 表」这种自相矛盾的结果。
  // Paimon 表不写这个键：服务端把「没有 type」当作 Paimon 表。
  const options = { ...tableDialog.options }
  delete options.type
  if (tableDialog.kind !== 'table') {
    options.type = tableDialog.kind
  }
  if (isFormatTable.value) {
    options['file.format'] = tableDialog.fileFormat
  }
  tableDialog.saving = true
  try {
    await catalogApi.createTable(prefix.value, database.value, {
      identifier: { database: database.value, object: name },
      schema: {
        fields,
        partitionKeys,
        primaryKeys,
        options,
        comment: tableDialog.comment || null,
      },
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

const registerDialog = reactive({ open: false, name: '', path: '', saving: false })

/**
 * 按表 ID 定位。
 *
 * <p>存在的理由是排查场景：日志里只有一个表 ID，而 `/tables/id/{tableId}` 能绕过
 * 「先知道库再知道表」的层级直接反查。定位成功后跳到表详情页——
 * 控制台里对表的操作全在那一页。
 */
const locateDialog = reactive({ open: false, tableId: '', saving: false })

function openLocateTable() {
  locateDialog.tableId = ''
  locateDialog.open = true
}

async function locateTable() {
  const tableId = locateDialog.tableId.trim()
  if (!tableId) {
    notifyError(new Error('请填写表 ID'))
    return
  }
  locateDialog.saving = true
  try {
    const payload = await catalogApi.getTableById(prefix.value, tableId)
    locateDialog.open = false
    if (!payload?.database || !payload?.name) {
      notifyError(new Error('服务端没有返回该表所属的库与表名，无法跳转'))
      return
    }
    router.push({
      name: 'table',
      params: { prefix: prefix.value, database: payload.database, table: payload.name },
    })
  } catch (error) {
    notifyError(error)
  } finally {
    locateDialog.saving = false
  }
}

function openRegisterTable() {
  registerDialog.name = ''
  registerDialog.path = ''
  registerDialog.open = true
}

async function saveRegister() {
  const name = registerDialog.name.trim()
  const path = registerDialog.path.trim()
  if (!name || !path) {
    notifyError(new Error('表名与路径都不能为空'))
    return
  }
  registerDialog.saving = true
  try {
    await catalogApi.registerTable(prefix.value, database.value, {
      identifier: { database: database.value, object: name },
      path,
    })
    notifySuccess(`已注册表 ${name}`)
    registerDialog.open = false
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    registerDialog.saving = false
  }
}

async function confirmDropTable(name) {
  const ok = await confirmDanger({
    title: '删除表',
    target: `确认删除表「${name}」？`,
    detail: '元数据与快照历史会被移除。catalog API 的 drop 不删除数据文件，'
      + '需要回收磁盘空间时请另外清理仓库目录。',
  })
  if (!ok) return
  try {
    await catalogApi.dropTable(prefix.value, database.value, name)
    notifySuccess(`已删除表 ${name}`)
    tablesList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 通用重命名

const renameDialog = reactive({ open: false, title: '', value: '', original: '', saving: false, apply: null })

function openRenameTable(table) {
  renameDialog.title = `重命名表：${table}`
  renameDialog.value = table
  renameDialog.original = table
  renameDialog.apply = async (next) => catalogApi.renameTable(
    prefix.value,
    { database: database.value, object: table },
    { database: database.value, object: next },
  )
  renameDialog.open = true
}

function openRenameView(view) {
  renameDialog.title = `重命名视图：${view}`
  renameDialog.value = view
  renameDialog.original = view
  renameDialog.apply = async (next) => catalogApi.renameView(
    prefix.value,
    { database: database.value, object: view },
    { database: database.value, object: next },
  )
  renameDialog.open = true
}

async function saveRename() {
  const next = renameDialog.value.trim()
  if (!next) {
    notifyError(new Error('新名称不能为空'))
    return
  }
  if (next === renameDialog.original) {
    // 名字没改：服务端会把「目标名已存在」判成 409，报出来的是一句对用户没有意义的
    // 「表已存在」。这里直接当作无操作，不发那趟请求，也不报错。
    renameDialog.open = false
    return
  }
  renameDialog.saving = true
  try {
    await renameDialog.apply(next)
    notifySuccess('已重命名')
    renameDialog.open = false
    tablesList.load(true)
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    renameDialog.saving = false
  }
}

// ------------------------------------------------------------------ 视图

const viewDialog = reactive({ open: false, name: '', query: '', comment: '', saving: false })

function openCreateView() {
  viewDialog.name = ''
  viewDialog.query = ''
  viewDialog.comment = ''
  viewDialog.open = true
}

async function saveView() {
  const name = viewDialog.name.trim()
  if (!name || !viewDialog.query.trim()) {
    notifyError(new Error('视图名与查询语句都不能为空'))
    return
  }
  viewDialog.saving = true
  try {
    await catalogApi.createView(prefix.value, database.value, {
      identifier: { database: database.value, object: name },
      schema: {
        query: viewDialog.query,
        comment: viewDialog.comment || null,
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

async function confirmDropView(name) {
  const ok = await confirmDanger({
    title: '删除视图',
    target: `确认删除视图「${name}」？`,
    detail: '只删除视图定义，底表不受影响。',
  })
  if (!ok) return
  try {
    await catalogApi.dropView(prefix.value, database.value, name)
    notifySuccess(`已删除视图 ${name}`)
    viewsList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 函数

const functionDialog = reactive({
  open: false,
  name: '',
  inputParams: [],
  returnParams: [],
  deterministic: true,
  definitions: '',
  comment: '',
  saving: false,
})

const SQL_FUNCTION_EXAMPLE = '{\n  "sql": {\n    "type": "sql",\n    "definition": "SELECT $1 + $2"\n  }\n}'

function openCreateFunction() {
  functionDialog.name = ''
  functionDialog.inputParams = [{ name: 'a', type: 'INT' }, { name: 'b', type: 'INT' }]
  functionDialog.returnParams = [{ name: 'result', type: 'INT' }]
  functionDialog.deterministic = true
  functionDialog.definitions = SQL_FUNCTION_EXAMPLE
  functionDialog.comment = ''
  functionDialog.open = true
}

async function saveFunction() {
  const name = functionDialog.name.trim()
  if (!name) {
    notifyError(new Error('请填写函数名'))
    return
  }
  let definitions
  try {
    definitions = JSON.parse(functionDialog.definitions)
  } catch (cause) {
    notifyError(new Error(`函数定义不是合法 JSON：${cause.message}`))
    return
  }
  const toParams = (list) => list
    .filter((param) => param.name.trim() && param.type.trim())
    .map((param) => ({ name: param.name.trim(), type: param.type.trim() }))

  functionDialog.saving = true
  try {
    await catalogApi.createFunction(prefix.value, database.value, {
      name,
      inputParams: toParams(functionDialog.inputParams),
      returnParams: toParams(functionDialog.returnParams),
      deterministic: functionDialog.deterministic,
      definitions,
      comment: functionDialog.comment || null,
      options: {},
    })
    notifySuccess(`已创建函数 ${name}`)
    functionDialog.open = false
    functionsList.load(true)
  } catch (error) {
    notifyError(error)
  } finally {
    functionDialog.saving = false
  }
}

async function confirmDropFunction(name) {
  const ok = await confirmDanger({
    title: '删除函数',
    target: `确认删除函数「${name}」？`,
    detail: '引用该函数的视图不会自动失效，需要自行检查。',
  })
  if (!ok) return
  try {
    await catalogApi.dropFunction(prefix.value, database.value, name)
    notifySuccess(`已删除函数 ${name}`)
    functionsList.load(true)
  } catch (error) {
    notifyError(error)
  }
}

// ------------------------------------------------------------------ 详情

const detail = reactive({ open: false, title: '', payload: null })

async function showView(row) {
  try {
    const payload = await catalogApi.getView(prefix.value, database.value, row.name)
    detail.title = `视图：${row.name}`
    detail.payload = { ...payload, schema: { ...payload.schema, query: payload.schema?.query } }
    detail.open = true
  } catch (error) {
    notifyError(error)
  }
}

async function showFunction(row) {
  try {
    const payload = await catalogApi.getFunction(prefix.value, database.value, row.name)
    detail.title = `函数：${row.name}`
    detail.payload = { ...payload, definitionsPreview: prettyJson(payload.definitions) }
    detail.open = true
  } catch (error) {
    notifyError(error)
  }
}

defineExpose({ loadCurrentList })
</script>

<style scoped>
/* Lance 那一页的两段内容：一个「能去哪儿看」的列表 + 两个跳转按钮。
   它只在本页出现，因此留在 scoped 里；页面骨架仍走 styles/index.css 的通用基元。 */
.pc-lance-points {
  margin: 0 0 16px;
  padding-left: 20px;
  line-height: 1.9;
}

.pc-lance-actions {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

/* 页面骨架（左列表 + 右对象面板）在 styles/index.css 里：Iceberg 浏览页共用同一套，
   放在任一个组件的 scoped 里另一页就用不上。这里只剩 Paimon 建表表单独有的部分。 */

/* 字段编辑器：容器与行走全局基元（.pc-repeat / .pc-repeat-row），
   这里只决定三列各占多宽 */
.pc-field-name {
  flex: 0 0 26%;
}

.pc-field-type {
  flex: 0 0 32%;
}

/* 新建表的字段列表：表头 + 数据行共用同一套栅格，勾选列窄、
   输入列宽，列对齐关系与参考设计一致（列名称 / 主键 / 非空 / 分区字段 /
   数据类型 / 描述 / 操作）。 */
.pc-field-table {
  display: flex;
  flex-direction: column;
  gap: 0;
}

.pc-field-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) 44px 44px 64px minmax(0, 1.05fr) minmax(0, 1fr) 108px;
  gap: 8px;
  align-items: center;
  padding: 6px 4px;
  border-bottom: 1px solid var(--pc-border);
}

/* Format 表没有主键 / 非空 / 分区三列，栅格只换列定义——
   间距、对齐、悬停与分隔线仍与七列版共用同一套规则。 */
.pc-field-grid-plain {
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1.05fr) minmax(0, 1fr) 108px;
}

/* 悬停淡染一行，帮用户在宽表里对准正在编辑的那行 */
.pc-field-grid:not(.pc-field-head):hover {
  background: var(--pc-surface-2);
}

.pc-field-head {
  font-size: 12px;
  font-weight: 600;
  color: var(--pc-text-dim);
  padding-top: 0;
  padding-bottom: 7px;
  border-bottom-color: color-mix(in srgb, var(--pc-border) 60%, transparent);
}

/* 三个勾选列与其表头都居中：栅格列是定宽的，内容默认拉伸到起点 */
.pc-field-head .pc-field-check,
.pc-field-head .pc-field-op {
  text-align: center;
}

.pc-field-grid :deep(.el-checkbox) {
  justify-content: center;
  margin-right: 0;
  height: auto;
}

/* 操作列：删除 | 上移 | 下移 三个文本链接，竖线分隔与参考设计一致 */
.pc-field-op {
  display: flex;
  align-items: center;
  justify-content: center;
  white-space: nowrap;
}

.pc-field-op :deep(.el-button) {
  margin: 0;
  padding: 0 2px;
  height: auto;
}

.pc-field-sep {
  color: var(--pc-border);
  font-size: 12px;
  user-select: none;
}

/* 行间不再留 gap（分隔靠横线），「添加一项」补回与表格的间距 */
.pc-field-table > .el-button {
  margin-top: 8px;
  align-self: flex-start;
}

.pc-pre :deep(textarea) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 13px;
}
</style>
