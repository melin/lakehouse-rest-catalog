# Catalog 详情页（`/catalogs/:catalogName`）

控制台里单个 catalog 的操作面。三个页签都已实现：概览、文件列表（只读），
以及**配置**——它这一轮先做「Paimon 表默认参数」（`table-default.*`）的编辑，
其余几项留在页面的待办表里，各自的阻塞点见 §3.3.3。

`catalogName` 就是 catalog 的 prefix（创建后不可改名），因此路径里的这一段
与客户端配置里的 `warehouse` 之外的另一个标识完全一致：这一页看到的东西，
引擎客户端连的是同一个 catalog。

整体介绍见 [`console.md`](console.md)，端点契约表见 [`console.md` §5](console.md#5-契约表与机械校验)。

---

## 1. 这一页解决什么

一半是「看 catalog 自己」：类型、存储类型、仓库位置、属性、版本、时间。
这半在 Catalogs 列表里挤成一行表格，要复制一个仓库路径、要看清某个属性的取值，
都得开编辑弹窗——而那是**写入**入口。详情页给这些只读信息一个该有的位置。

另一半是**用 REST 接口看不见的那一半**：仓库里的物理目录。

REST 协议的读路径全部经过本服务端，元数据在库里。于是有一类问题在接口上完全
不可见——「用 REST catalog 一切正常，绕开服务端看仓库却不对」。最典型的一次是
schema 物化没生效：客户端建表成功、写入时报
`Cannot get latest schema for table <表名>`，而真正的原因
（`<表目录>/schema/schema-0` 不存在）只能登机器或开对象存储控制台才看得到。

文件列表把「仓库里到底有什么」搬进控制台，与「表详情里的 Schema / 快照」互为印证：
**库里说的**与**盘上写的**对不对得上，一屏就能比。

---

## 2. 数据加载

打开页面时只发两个请求：

| 请求 | 用途 | 失败时 |
| --- | --- | --- |
| `GET /api/management/v1/catalogs/{catalogName}`（`getCatalog`） | 概览页签的全部读数 | 页面显示错误态，三个页签都不渲染 |
| `GET /v1/config?warehouse={catalogName}`（`config`） | 「协议与能力」卡片：协议面、下发给客户端的 `defaults`，Iceberg 侧还有 `endpoints` 能力清单 | **只有这一块失败**：卡片里显示读不到及原因，整页照常 |

**第二个请求为什么值得发。** 它问的是服务端**实际下发**给客户端的内容，而不是
服务端库里存了什么——「客户端连上来会看到哪些能力」这件事只有这个端点能回答。
它是发现端点，**不需要授权**（`CatalogAccessRules.isPublic`），因此不会因为
当前主体缺权限而失败；失败只可能是服务端没起来或令牌失效，那时卡片里如实显示原因。

**页签按需加载。** 文件列表不在这两个请求里：列目录要打一次存储
（对象存储上是一次前缀列举），不该在打开页面时就付这个代价。切到「文件列表」
才发第一次请求，之后同一个目录不重复请求。

「配置」页签不发任何请求：它列的是计划能力，数据是页面里的常量。

### 2.1 当前目录是页面状态，不是路由参数

下钻到第几层存在组件的 `filePath` 里，**不进 URL**：

- 刷新回到仓库根是合理的——一个「我上次翻到 ××× 的第三层」的深链接没有价值；
- 但**必须**在会话内保留，否则每次切页签回来都要重新钻一遍。

切换 catalog 时整体重置（`watch(catalogName)`）：标题、概览、当前目录都回到初始。
不清的话会出现「标题是 b、文件列表还是 a 的目录」，而且 `useResource` 只在挂载时
自动加载，换 catalog 后不重新拉取会停在旧数据上。路径一并打回仓库根：
`fe_x.db/orders` 在另一个 catalog 里通常并不存在，沿用它只会换来一个 404。

顶栏的 catalog 选择器也参与这件事：在详情页上换目录会**跳到新目录的详情页**
（而不是原地刷新——刷新出来的还是旧目录的数据，看起来像没生效）。同一规则覆盖
「目录浏览」与「表详情」：这两页的当前目录写在 URL 里（`?catalog=` / `:prefix`），
换目录必须**改 URL** 才会生效——目录浏览这条曾是 bug：它的路由自己是别人的父级
（没有 `meta.parent`），只判 `meta.parent === 'browse'` 会把它漏到刷新分支，
于是「选了另一个 catalog，页面纹丝不动」。

---

## 3. 三个页签

| 页签 | 数据来源 | 一句话 |
| --- | --- | --- |
| 概览 | `getCatalog` + `config` | 名称 / 类型 / **表格式** / 存储类型 / 仓库位置 / 属性 / 版本 / 创建 / 更新，右侧是 `storageConfigInfo` 与 `properties` 的原始 JSON；下方是「协议与能力」卡片 |
| 文件列表 | `listCatalogFiles` | 仓库物理目录的一层内容，目录名可点下钻 |
| 配置 | `getCatalog` + `updateCatalog` | 「Paimon 表默认参数（`table-default.*`）」可编辑；其余属性只读回填；末尾列出仍未实现的三项。**Iceberg catalog 下不出现**（见 §3.3） |

### 3.1 概览

左键值对、右原始 JSON 的分栏：左边是读数（人读），右边是服务端实际存的东西
（排查时读）。同一个事实出现两次是有意的——列表页也是这个套路，
读数和原始响应不是一回事，把它们合并成一种表示总会牺牲一边。

**仓库位置的取法只有一处答案**：`storageConfigInfo.allowedLocations[0]`，
`file` 存储的那一份没有 `allowedLocations`，位置这时记在 `properties.warehouse` 上。
与 `CatalogsView.warehouseOf` 同一个约定（两处写在一起，因为「位置从哪儿读」
这件事只有一个答案）。

**表格式**（`catalogFormat`）在这里以徽标显示，并注明**创建后不可更改**。
它与「类型」（INTERNAL / EXTERNAL）是两条正交的属性，因此是两行。
值取自 `session.state.catalogs`（列表在首屏就取回来了）：
为一个字段再发一次管理 API 请求不值当，而这一页本来就要等列表才有「当前 catalog」。

页签底部写明：属性与存储配置的修改走管理 API
（`PUT /api/management/v1/catalogs/{catalogName}`），**控制台目前只读展示**，
编辑入口在 Catalogs 列表的编辑弹窗里。

### 3.1.1 协议与能力

这一块回答的是排障时的第一个问题：**这个 prefix 服务哪一套协议、客户端能调哪些端点。**

| 行 | 来源 | 说明 |
| --- | --- | --- |
| 协议面 | `catalogFormat` | Paimon REST Catalog / Iceberg REST Catalog |
| 客户端基址 | 由 prefix 拼出 | `/v1/{catalogName}`，可一键复制 |
| namespace 分隔符 | 仅 Iceberg | `0x1F`（规格默认；解析时也接受 `.`，库里按点号拼接） |
| 凭据下发 | `meta.service.credentialManagerType` | `noop`（默认）时说明「端点在但返回 501 并说明原因」——**「端点在但没开」与「端点不存在」在排障时是两件事** |
| 能力清单 | Iceberg：`config` 的 `endpoints`；Paimon：一句说明 | 见下 |
| 格式说明 | `utils/catalogFormat.js` | 一句话影响面（客户端走哪段路径、控制台能显示什么） |

**能力清单读的是服务端下发的原文，前端不抄一份。** Iceberg 规格写明：服务端若不返回
`endpoints`，客户端会假定一整套默认端点存在（其中含本服务端不做的多表事务）；
客户端拿到清单后做的是**等值比较**，少一条就在连接阶段被拒。因此前端自己抄一份
端点表，抄错了会显示一个「看上去对、实际不生效」的清单——而这一块的全部价值
就是让人不用去读文档。清单是对的就够了，界面只负责把它列出来（等宽字体逐行，
高度封顶可滚）。

右栏下半是**不声明支持**的那几项。这一栏只能写在前端：服务端不会下发
「我不支持什么」，没有就是没有。它是可数的几个词组，因此铺成标签而不是一行一条。
其中「共 N 个操作」的 N 与 `scripts/verify-iceberg-endpoints.py` 里那份**字面量**
`NOT_IMPLEMENTED` 的条数由 `verify-console.py` 对齐——每多实现一个端点，它就该少一条，
而这是这个页面上唯一会随实现漂移的数字。

### 3.2 文件列表

界面三件套：面包屑（`仓库根` 起，每段可点）、「返回上级」按钮（根目录时禁用）、
条目表格。

| 列 | 说明 |
| --- | --- |
| 名称 | **目录名可点**（`<a>` 而非路由链接——路径不进 URL）；文件是纯文本，不可点 |
| 类型 | 目录 / 文件 |
| 大小 | 文件按字节格式化；**目录显示占位符 `—`**，不是 `0` |
| 修改时间 | 相对时间，悬浮显示完整时刻与毫秒值 |
| 相对仓库根的路径 | 服务端返回的 `path` 原样显示，与下钻时回传的字符串一致 |

目录长度语义是「不知道」而不是 0。服务端如实填 0、界面显示占位符，
两边都不假装它是个数：递归算大小要遍历整棵子树，不该藏在一次列表请求里。

**排序与截断**：目录优先、名称不区分大小写升序。超过上限时返回前 N 条并带
`truncated` 标记（不是报错——列表的价值在能下钻，为了完整性把它整个拒掉
反而让人看不到任何东西）。因为目录排在前面，被截掉的总是尾部的文件，
用户至少能看到所有目录。界面在表格上方给一条提示，写明「共 N 条，只显示了前 M 条」，
并指出两条出路：下钻到更具体的子目录，或调大 `max-entries`。

页面底部写明这一页**只读**及其理由（见 §6）。

### 3.3 配置

页面分三段：**Paimon 表默认参数**（可编辑）、**其它属性**（只读回填）、

**Iceberg catalog 下这个页签不出现。** `table-default.*` 的作用域是「在这个 catalog 下
新建 **Paimon 表**」（见页签内的三条边界），对 Iceberg 侧的建表请求不生效。
留一个点了不生效的页签比没有更糟，因此模板上直接 `v-if="!isIceberg"`。
若用户正停在它上面而当前 catalog 换成了 Iceberg，`watch(isIceberg)` 会把页签退回
「概览」——`v-model` 停在一个没有对应面板的名字上只会留下一片空白。

#### 3.3.1 Paimon 表默认参数（`table-default.*`）

编辑 catalog 的 `properties` 里 `table-default.` 开头的那些键：键值行编辑器，
前缀 `table-default.` 由界面固定显示（不让用户手打，省得打出
`table-default.deletion-vectors.enabled` 这种「带着前缀的键」），属性名与取值各一列。

语义与服务端一致，页面上用一条 `TipNote` 写清三条边界（表级参数优先、
只对 Paimon 表生效、只在建表时合并），以及 `type` 不接受默认。
判据与读写都在 `utils/tableDefaults.js`，与服务端 `support/TableDefaults` 一一对应。

**编辑的是本地副本，不是服务端返回的对象。** 直接绑会让「没保存」和「已保存」分不开，
而这一页的保存是**整体替换**（管理 API 的 PUT 语义），误触的代价是整个 catalog 的属性。
副本在概览数据到达时重建（`watch(catalog0)`），保存成功后 `overview.load()` 一回来
baseline 就推到新值，按钮回到「与服务端一致」。

**保存前拦三件事**，服务端不会替我们报：属性名重复（后者静默覆盖前者）、
属性名里又带了一次前缀（粘贴整键时的常见错误）、以及 `type`
（服务端会忽略它，写进去只会让人以为生效了）。

#### 3.3.2 其它属性（只读回填）

非 `table-default.*` 的键在这页只读展示（键值表），保存时**原样回填**：
管理 API 的更新是全量替换，不回填就等于把这些键删了。这条写在页面的 `TipNote` 里，
因为它解释了「为什么这页只改了默认参数，却要带上别的属性」。

### 3.4 新建表表单里的默认参数

「目录浏览」页的新建表对话框读同一个 `utils/tableDefaults.js`：打开时把当前 catalog 的
默认参数**去掉前缀**预填进表属性。这样表单里看得见它们，不会有「表怎么凭空多了个属性」
的疑问；服务端建表时也会合并同一份，表级参数优先，所以预填值与最终结果一致。

表格式切换时它们会跟着进出（`watch(tableDialog.kind)`）：切到 Format / Object 表
就移出——服务端**只对 Paimon 表**合并默认值，留在表单里会被当作表级参数真的写进那张表。
只移出「取值仍等于默认值」的那些：用户改过的说明他想显式写进这张表，保留。
无默认参数时，`TipNote` 会指出配它们的地方（Catalogs 页里该 catalog 的「配置」页签）。

同一处 `TipNote` 还说明了 `path` 对非 Paimon 表的含义：它是那张表的**数据目录**。
填了就指向那个目录（服务端把它当作表位置、并按外部表登记，因此不往里写 `schema-0`），
留空则由服务端按表位置分配。取舍与边界见
[`catalog-api-endpoints.md` §「表位置与 `path`」](catalog-api-endpoints.md#表位置与-path)。

### 3.5 为什么不给 Lance 表的建表入口

选择器里保留「Lance 表」这个选项，但标成不可选（`TABLE_KINDS` 里那条的 `disabled`）。
**不直接删掉它**：Paimon 支持的格式就是四种，删掉之后用户只会觉得「怎么少了一种」，
摆在那里加一句原因，才答得上这个问题。

原因是没有可用的读写能力：Paimon 2.0.0 的 `SparkLanceTable` 什么都不声明
（`capabilities` 为空集），查询与写入都在 Spark 的**分析阶段**被拒
（`does not support batch scan.` / `does not support append in batch mode.`），
`LanceTableImpl` 的 `newScan()` / `newRead()` 也都抛 `UnsupportedOperationException`。
**与依赖无关**：不是缺 `lance-core`。判据与证据见
[spark-paimon-rest-e2e.md](spark-paimon-rest-e2e.md) §4.4。

三条边界：

- **这是控制台策略，不是服务端限制。** 服务端照常接受 `type=lance-table` 的建表请求，
  Spark SQL 与 HTTP 都能建出来（e2e 里正有用例断言这件事）。控制台只是不给这个入口。
- **既有 Lance 表照常展示。** `utils/tableKind.js` 里的显示名不能跟着删：认不出
  `lance-table` 就会把它当成 Paimon 表，列表显示错格式，详情页还会去问快照端点。
- **拦截落在两处，原因只有一份。** 选择器标 `disabled`（界面状态）+ `saveTable` 开头
  按同一份 `TABLE_KINDS` 再拦一次（校验），免得状态被别的路径改掉、或以后新增不可选格式时
  只改了配置没接上拦截；原因文案只有 `disabledReason` 一处，选择器问号里那几句是拼出来的。

---

## 4. 服务端：端点与状态码

一条端点，**非 Polaris 规格**，登记在 `scripts/verify-console.py` 的
`EXTENSION_ENDPOINTS` 白名单里并写明了理由：

```
GET /api/console/v1/catalogs/{catalogName}/files?path=<相对仓库根>&prefix=<名称前缀>
```

`path` 省略或为空时列仓库根；`prefix` 省略或为空时不过滤。响应：

```json
{
  "catalog": "paimon",
  "warehouse": "s3://bucket/warehouse",
  "path": "fe_x.db/orders",
  "prefix": "",
  "entries": [{ "name": "schema", "path": "fe_x.db/orders/schema",
                "directory": true, "size": 0, "modificationTime": 1740000000000 }],
  "total": 12,
  "truncated": false,
  "maxEntries": 1000
}
```

`catalog` 与 `warehouse` 回显服务端实际用的值，`path` 与 `prefix` 是**归一化之后**
的过滤条件（界面的面包屑与过滤框按它们算，可点的路径段与这里一致）。

**前缀过滤在服务端做**（`prefix` 只与条目名比较，条目名不含 `/`）。理由不是性能
而是正确性：过滤后的结果还要按同一个 `max-entries` 上限截断，两边各过滤一次会让
「只显示了前 N 条（共 M 条）」这句话对不上。因此界面上「输入前缀 + 回车」就是
**重新发一次这个请求**（逐字符过滤会把每次按键都变成一次存储列举）。
`total` 是**过滤后**的条数。

**翻页在浏览器里做。** 服务端一次给一批（上限 `max-entries`，默认 1000），
前端切片分页。列目录本来就要把这一层读完才知道总数与排序，再切一次页不省任何事；
真正用来缩范围的是前缀过滤，那是唯一需要走服务端的。

| 情形 | 状态码 | 说明 |
| --- | --- | --- |
| 部署关掉了文件浏览 | 501 | 消息里带 `lakehouse.rest.file-browser.enabled`，否则现象是「页签永远在报错」 |
| catalog 不存在 | 404 | 按 prefix 查不到 |
| catalog 没有 warehouse | 400 | 消息里带 `warehouse` 与那条 `PUT` 的路径 |
| 路径是绝对的（`/x`、`s3://…`） | 400 | 分开两条消息：带 scheme 的是「另一个位置」，前导 `/` 的是「绝对路径」 |
| 路径含 `..` | 400 | 「必须留在仓库内」 |
| `prefix` 含 `/` | 400 | 条目名不含分隔符，那种写法永远匹配不到东西；消息里指明改用 `path` 下钻 |
| 目录不存在 | 404 | 消息里含相对于仓库根的路径 |
| 路径指向的是文件 | 400 | 「是文件不是目录」 |

同一套列举核心还有第二个出口：表详情页的
`GET /api/console/v1/tables/{tableId}/files?path=<相对表目录>`——根换成表元数据里的
`path`，响应形状见 `ConsoleDtos.TableFileListing`（`root` 即表路径，`..` 与绝对路径
同样被拒，因此出不了表目录）。取舍见
[`console-table-detail.md` §2.5](console-table-detail.md#25-文件列表)。

### 4.1 单文件下载

列举端点旁边各有一条下载端点（`.../files/download?path=<文件路径>`，两个根同上）：

- **`path` 必填且必须指到文件。** 给目录或仓库根是 400（「列它去」——没有
  「下载整个目录」这种操作，打包遍历既慢又容易变成变相的全仓库导出）。
- **流式、不缓冲。** 响应体直接从 `FileIO` 的输入流写到 socket，
  `Content-Length` 取自 `FileStatus`（浏览器能显示进度），文件名进
  `Content-Disposition`（RFC 5987 的 UTF-8 形式，中文不乱码）。
  数据文件动辄上百 MB，读进内存再回既慢又容易打爆堆。
- **仍是只读。** 下载是把已有的字节搬出去，不产生任何写入；开关与授权面与
  列举端点完全一致（`file-browser.enabled=false` 时一起 501）。

| 情形 | 状态码 |
| --- | --- |
| 部署关掉了文件浏览 | 501 |
| 文件不存在 | 404 |
| `path` 是目录 / 空路径（根本身） | 400 |
| `path` 含 `..` 或是绝对路径 | 400（与列举同一拦截） |
| 存储 IO 失败 | 500 | 消息里带异常链最里面那条的消息；堆栈落日志 |

**为什么放在 `/api/console/` 而不是 `/api/management/`。** 管理 API 那个命名空间
要与 `spec/polaris-management-service.yml` 一一对应，往里塞规格外端点会让这条
不变量需要额外解释。控制台扩展端点统一在 `/api/console/v1/**` 下（见 `console.md` §5）。

### 4.1 凭据来源必须与引擎一致

`StorageFileIoFactory` 是服务端「以 catalog 的身份读写仓库」的**唯一**装配点：
物化表元数据（`TableMetadataService`）与浏览仓库文件（`CatalogFileService`）
都用它。抽出来的理由不是省几十行，而是**两份凭据来源会漂移**——一旦哪次只改了
其中一处（例如新增一级具名存储），现象就是「引擎能写、控制台看不了那个目录」，
而两边报的错都只说认证失败，从现象反推不回原因。

选项由 `optionsFor` 算：`StorageCredentialManager.vend(storage, scopeId, scopePath)`
的产物（键名族 `s3.*` / `fs.obs.*` / `fs.oss.*` / `gcs.*` 本来就是 Paimon FileIO
的选项名）加上 `warehouse`。作用域标识用 **catalog id**（表元数据那条链路传 table id
是同一个约定，见 `StorageCredentialManager#vend`）。

**不用 `FileIO.get(path, context)`**：它构造 `ResolvingFileIO`，其 `configure`
要拿 Hadoop `Configuration`，本部署不带 Hadoop，于是直接失败。逐条列举的写法
同时让「这个 scheme 有没有实现」变成一个可以自己给出错误信息的问题
（`file://` 内置，`s3://` / `obs://` / `oss://` 是插件）。

**每次调用都新建 FileIO 实例**，不缓存：缓存凭据的代价是「管理 API 换过 catalog
凭据之后，这里还在用旧钥匙」。列表是人工点出来的低频操作，省下的开销换不来任何东西。
`loaders` 缓存的是**实现类**而不是实例——按 scheme 加载类这件事与配置无关。

---

## 5. 路径与安全

请求给的是**相对仓库根**的路径，服务端把它拼在 catalog 的 `warehouse` 上。

归一化：`//` 与 `.` 段直接丢掉（`a//b` 与 `a/./b` 是同一个位置，拒绝只会让界面上
一个手抖的 URL 报一条看不懂的错）；warehouse 末尾的 `/` 在拼之前去掉。

拒绝三类：

| 输入 | 为什么拒 |
| --- | --- |
| `s3://bucket/x` | 那是另一个位置。位置由 catalog 的 warehouse 决定，不由请求决定 |
| `/etc`、`C:\…` | 绝对路径等于让调用方用控制台读这台服务器能访问的任何位置 |
| `../other-bucket` | 逃出仓库根 |

**`..` 是这里唯一真正的安全点。** Paimon 的 `Path` 自己不做越界检查
（`new Path(root, "../../etc")` 会照拼），而本端点的用途只是「在仓库里下钻」。
因此不去做「拼完再判断前缀」的事后校验——那种写法要处理大小写、符号链接与
分隔符重复等一堆归一化差异，容易漏。直接拒掉 `..` 段更简单也更难写错。

---

## 6. 为什么只读

这一页不提供删除文件、建目录、上传。仓库里的对象归引擎与服务端自己管：
服务端写 schema 与快照，`purge-on-drop` 决定删表时清不清目录。从控制台手工改文件
会让「库里说的」与「盘上写的」对不上——而那正是这个页面要帮人**发现**的问题。

授权面与 `/api/console/v1/**` 的其余端点一致：只过认证拦截器，不做细粒度判定。
刻意如此——控制台用户是拿管理凭据进来的运维者，而细粒度判定要回答
「这个主体能不能看这个 catalog 的物理目录」，那需要把 catalog 级权限语义
延伸到文件系统层，本工程没有这个模型。部署不希望开放它时用配置整体关掉（见 §7）。

---

## 7. 配置项

| 配置 | 默认 | 作用 |
| --- | --- | --- |
| `lakehouse.rest.file-browser.enabled` | `true` | 关掉时端点回 501，消息里带这一项的名字 |
| `lakehouse.rest.file-browser.max-entries` | `1000` | 单次列出的条目上限；超出时返回前 N 条并标 `truncated` |

默认开着，是因为排查「表建出来了却写不进去」这类问题时正是要看仓库目录；
但它属于**运维面而不是数据面**的能力（本工程其余端点读的都是元数据），
所以留一个开关和一条上限。

**关掉它不改变任何数据面行为**：仓库仍由引擎与服务端读写，这个开关只挡
「控制台看目录」这一件事。

---

## 8. 校验与测试

`scripts/verify-console.py` 第 1、2 组盯着这个端点：路径必须在扩展白名单里
（说明它确实是规格外的），且白名单里的每条都必须被控制台真的调用过。
计数从 77 条变成 78 条。

服务端分两层，**分开写**：

| 测试类 | 用例 | 管什么 |
| --- | --- | --- |
| `CatalogFileBrowserTests` | 21 | 服务语义：下钻、目录优先排序、冗余分隔符与 `.` 归一化、`..` 与绝对路径拒绝、不存在 404、文件非目录 400、未知 catalog 404、无 warehouse 400、截断标记、关开关 501；表目录为根与名称前缀过滤的用例见 `console-table-detail.md` |
| `CatalogFileEndpointTests` | 6 | 接口形状：URL、`path` / `prefix` 查询参数、状态码、响应字段名（列举与下载共四条端点；下载钉 `Content-Disposition` 与 `Content-Length`，过滤钉回显与 `total` 口径） |

两者分开是因为这些错误各自独立：服务能把目录列对，不代表端点拼对了
（URL 错一位照样 404）；反之端点形状对了，服务也可能把「文件」当成目录列。

`CatalogFileBrowserTests` 直接构造带不同 `FileBrowser` 配置的服务实例
（`serviceWith(Consumer<FileBrowser>)`），而不是为每种配置各起一个 Spring 上下文
——一个「关掉开关」的用例不值得付一次上下文启动。

一个实现细节：**建库不产生物理目录**。`databaseService.create(...)` 只在元数据里
登记，物理目录在**建表**（写 `schema/schema-0`）时才出现。测试里的用例因此要
先 `Files.createDirectories(...)`，否则报的是 `NoSuchFile` 而不是它想测的那件事。

---

## 9. 已知边界

- **看不了文件内容。** 只列目录与元信息（名称、类型、大小、修改时间），
  不读文件、不预览、不下载。要读内容得走引擎或对象存储客户端。
- **不做递归统计。** 目录大小恒为 0（界面显示 `—`），没有「这个库占多少空间」
  这一类读数。真要做应当是独立端点，且要接受它是一次全树遍历。
- **不做过滤与搜索。** 目录内条目按排序原样列出，没有关键字过滤。
  条目多到需要过滤时，先调 `max-entries`——但更该做的是下钻。
- **「配置」页签只做了一部分。** 见 §3.3：`table-default.*` 可编辑，其余属性只读回填，
  待办表里列着剩下三项与它们各自的阻塞点。
- **路径不进 URL。** 当前目录只在会话内保留，深链接只能到 catalog，
  不能到某个具体子目录。
- **切换 catalog 会重置当前目录。** 有意为之（见 §2.1），代价是同一层级
  换了 catalog 后要重新钻。
- **Iceberg catalog 上，catalog 详情只有「概览」与「文件列表」两页是自己的。**
  「文件列表」读的是仓库物理目录，与协议面无关，因此对 Iceberg catalog 同样可用。
  反过来，「配置」页签（`table-default.*`）只对新建 Paimon 表生效，Iceberg catalog
  下整块收起。**目录浏览**已按格式分成两套实现（Iceberg 走 `IcebergBrowseView`
  的 `namespaces/**`，见 `console.md` §4.6），而**表详情页**仍然只有 Paimon 版：
  它整页挂在 Paimon 的表端点上，因此在 Iceberg catalog 下**在 loader 里就返回空、
  不发请求**，只给出说明与去向；Iceberg 表的详情在浏览页的抽屉里看。
  理由见 `iceberg-rest-catalog-design.md` §11.1 第 11 条。
