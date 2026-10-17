# Catalog API 端点清单

LakeHouse Catalog API 在本服务端的全部端点，按资源分组列出。
本服务端**同时服务两套协议面**（同一个进程、同一个 `/v1` 基址、同一套元数据库）：

| 协议面 | 规格来源 | 端点 |
| --- | --- | --- |
| **Paimon**（本文档第 1–10 节） | Apache Paimon `docs/static/rest-catalog-open-api.yaml`（副本 `spec/rest-catalog-open-api.yaml`，运行时可从 `/rest-catalog-open-api.yaml` 下载） | **60 个**（规格全部实现）、144 个 schema |
| **Iceberg**（本文档末尾） | Apache Iceberg `rest-catalog-open-api.yaml`（副本 `spec/rest-catalog-open-api-iceberg.yaml`） | **26 个**（规格共 35 个「路径 × 方法」组合，9 个不声明） |

一个 catalog 只有一种格式（`paimon_catalog.catalog_format`，创建时定下、**不可变**），
两套路径因此互不重叠地落在各自的 prefix 上。走错面的请求会拿到**指名原因的 404**
（例如「prefix `sales` 是 ICEBERG catalog，Paimon REST 路径需要 PAIMON catalog」），
而不是一句「没有这个东西」——否则客户端会以为是自己把库名写错了，
再试一百次也不会对。分派与错误模型见
[`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §2。

- 基址：`{scheme}://{host}/v1`。除 `GET /v1/config` 与 `POST /v1/oauth/tokens` 外，
  全部端点都带 `{prefix}`——它是 catalog 的对外标识，由 `GET /v1/config` 下发
- 授权映射：`CatalogAccessRules` 把下面的路径映射到所需权限，
  **未登记映射的端点直接拒绝而不是放行**；测试从运行时请求映射枚举全部端点核对覆盖率，
  新增端点若忘记登记会让构建失败。详见 [`authorization.md`](authorization.md)

> 另有一个不在规格内的扩展端点 `POST /api/catalog/v1/oauth/tokens`
> （OAuth 2.0 客户端凭据换访问令牌，路径与 Polaris 对齐），见
> [`console-auth.md`](console-auth.md)。

> 还有三个 Paimon **官方 REST Management API** 的权限端点，同在 `/v1/{prefix}` 之下
> （wire 契约来自官方 `rest-management-open-api.yaml`，不在本仓库规格副本里）：
> `GET /v1/{prefix}/permissions`、`POST /v1/{prefix}/permissions/grant`、
> `POST /v1/{prefix}/permissions/revoke`。授权直接面向 principal
> （`user:alice` / `role:sales_writer`），与下方 Polaris 管理面 RBAC 并行生效，
> 服务端判定取两路并集；实现见 `PermissionController` 与 `PaimonPermissionModel`，
> 控制台入口是「权限管理」页。授权要求：三端点都要求 catalog 级
> `CATALOG_MANAGE_ACCESS`（官方语义的 GRANT，收敛到 catalog 级只会更严）。

---

## Paimon 协议面（60 个端点）

下面各组（配置 / database / 表与快照 / 分区 / 分支与标签 / 消费者 / 视图 / 函数 /
语义视图 / 建表）全部属于 Paimon 面；Iceberg 面在文档末尾单独成章。

## 配置（1）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/config` |

## database（5）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases` |
| POST | `/v1/{prefix}/databases` |
| GET | `/v1/{prefix}/databases/{database}` |
| POST | `/v1/{prefix}/databases/{database}` |
| DELETE | `/v1/{prefix}/databases/{database}` |

## 表与快照（18）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables` |
| POST | `/v1/{prefix}/databases/{database}/tables` |
| GET | `/v1/{prefix}/databases/{database}/table-details` |
| POST | `/v1/{prefix}/databases/{database}/register` |
| GET | `/v1/{prefix}/tables` |
| GET | `/v1/{prefix}/tables/id/{tableId}` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}` |
| DELETE | `/v1/{prefix}/databases/{database}/tables/{table}` |
| POST | `/v1/{prefix}/tables/rename` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/commit` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/rollback` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/rollback-schema` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/snapshot` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/snapshots` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/snapshots/{version}` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/token` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/auth` |

## 分区（6）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/partitions` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/drop` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/mark` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/list-by-names` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/partitions/list-by-filter` |

## 分支（5）与标签（4）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/branches` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/branches` |
| DELETE | `/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/rename` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/branches/{branch}/forward` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/tags` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/tags` |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}` |
| DELETE | `/v1/{prefix}/databases/{database}/tables/{table}/tags/{tag}` |

## 消费者（2）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/tables/{table}/consumers` |
| POST | `/v1/{prefix}/databases/{database}/tables/{table}/consumers/reset` |

## 视图（8）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/views` |
| POST | `/v1/{prefix}/databases/{database}/views` |
| GET | `/v1/{prefix}/databases/{database}/view-details` |
| GET | `/v1/{prefix}/views` |
| GET | `/v1/{prefix}/databases/{database}/views/{view}` |
| POST | `/v1/{prefix}/databases/{database}/views/{view}` |
| DELETE | `/v1/{prefix}/databases/{database}/views/{view}` |
| POST | `/v1/{prefix}/views/rename` |

## 函数（7）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/functions` |
| POST | `/v1/{prefix}/databases/{database}/functions` |
| GET | `/v1/{prefix}/databases/{database}/function-details` |
| GET | `/v1/{prefix}/functions` |
| GET | `/v1/{prefix}/databases/{database}/functions/{function}` |
| POST | `/v1/{prefix}/databases/{database}/functions/{function}` |
| DELETE | `/v1/{prefix}/databases/{database}/functions/{function}` |

## 语义视图（4，实验性）

| 方法 | 路径 |
| --- | --- |
| GET | `/v1/{prefix}/databases/{database}/semantic-views` |
| GET | `/v1/{prefix}/databases/{database}/semantic-views/{semanticView}` |
| POST | `/v1/{prefix}/databases/{database}/semantic-views/{semanticView}` |
| DELETE | `/v1/{prefix}/databases/{database}/semantic-views/{semanticView}` |

---

覆盖率不靠人工比对：`scripts/api-sweep.sh` 对运行中的实例逐个调用这 60 个 operation
（会创建并清理 database `sales`，因此要求目标实例为初始状态）。
本服务端在元数据之外还会把 schema 与快照物化到 Paimon 表目录
（`<table>/schema/schema-<n>`、`<table>/snapshot/snapshot-<n>`），因此写入可用，
绕开服务端直读仓库也能看到完整元数据；`commit` / `rollback` 的物化行为与三档开关见
[`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) 第 4 节，
其余边界见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」。

`GET /v1/{prefix}/databases/{db}/tables/{t}/snapshots/{version}` 里 `version` 的解析顺序与
Paimon 客户端一致（见 `RESTApi.loadSnapshot(identifier, version)` 的契约）：
`EARLIEST` 取最早、`LATEST` 取最新、**数字即快照 id**、其余按标签名查。
数字不是 `Snapshot.version`——那是快照文件格式版本，客户端每个快照都送同一个值。

`GET /v1/{prefix}/databases/{db}/tables/{t}/snapshot` 回的是 **`TableSnapshot`**：
快照本身加上表级统计。`recordCount` 就是快照的 `totalRecordCount`，而
`fileCount` / `fileSizeInBytes` / `lastFileCreationTime` **不在快照里**——快照只记清单指针，
这三个数由 `commit` 请求随带的 `statistics`（各分区在本次提交里的增量）累计而来。
因此客户端没送 `statistics` 时它们为 `null`，与「0 个文件」不是一回事（页面上的呈现见
[`console-table-detail.md` §2.1](console-table-detail.md#21-详细信息)）。
统计落库之前写的快照行三项也是 `null`——该端点只回**最新**快照，此时分区合计与它引用的
文件一致，所以读路径按分区合计现算兜底，老表升级后立即有值；历史快照没有这个兜底。

## 建表

`POST /v1/{prefix}/databases/{database}/tables` 的请求体是
`{identifier, schema{fields, partitionKeys, primaryKeys, options, comment}}`。
表格式写在 `schema.options.type` 里——规格的 `Schema` 没有独立的类型字段，
Paimon 自己就是这么表达的（`CoreOptions.TYPE`，键 `type`，默认 `table`），
SQL 侧同样是 `WITH ('type' = 'format-table')`。本服务端认四种取值：

| `options.type` | 表格式 | 建表时的处理 |
| --- | --- | --- |
| 缺席（或 `table`） | Paimon 表 | 原样落库；**不写** `path`，位置由目录决定 |
| `format-table` | Format 表 | 表位置 = 客户端给的 `path`（没给就按模板分配）；**禁止主键**（400） |
| `object-table` | Object 表 | 同上；用户声明的字段被**忽略**，落库的是固定六列 |
| `lance-table` | Lance 表 | 同上；字段、非空、主键与 Paimon 表一致（**能建不能读写**，见下） |

其余取值一律 400。写进库的话，引擎读表时才在 `CoreOptions.TYPE` 的枚举上炸，
出错现场离原因太远，所以宁可在这里回一句「不认识的类型」。

### 表位置与 `path`

表位置就是 `GetTableResponse.path`，也是引擎真正去读的那个目录。规则按表格式分两类：

| 表格式 | 表位置 | 客户端给的 `path` |
| --- | --- | --- |
| Paimon 表 | 按 `paimon.rest.path-template` 分配，恒定 | 不参与（要指向一份已经存在的 Paimon 数据，走 `register`） |
| Format / Object / Lance 表 | 客户端给了就用它，没给才按模板分配 | 就是表位置，同时写进 `options.path` |

非 Paimon 表为什么必须这样做：Paimon 的 REST 客户端在 load 表时，用响应里的表位置
**覆盖** `schema.options.path`（`RESTCatalog.toTableMetadata` 用的是 `put`，不是
`putIfAbsent`）。所以「把客户端的目录只留在 `options` 里」是没用的——引擎读的仍会是
模板分配的那个目录，对象目录里的东西一个也看不到。本服务端因此把客户端的目录
**同时**写成表位置与 `options.path`，两者始终一致（`path` 只给空白视为没给）。

客户端给了目录的表按**外部表**登记（`isExternal=true`）：那批文件不归本服务端管，
因此不往里写 `schema/schema-0`、删表时也不删它的目录——与 `register` 同一套取舍
（见 `TableMetadataService`）。

> **Object 表的数据怎么进去。** 它没有 `INSERT`：Spark 侧 `SparkObjectTable` 只声明了
> `BATCH_READ`（写语句在分析阶段就被拒），Paimon 侧的 `ObjectTableImpl` 是 `ReadonlyTable`。
> 它的行是**读的时候现列目录**算出来的（固定六列），所以「写入」= 用存储侧的手段把对象
> 放进那个目录：`aws s3 cp`、`hdfs dfs -put`、应用自己的 SDK PUT，或 Spark 把 DataFrame
> 写成普通文件。放进去下一次查询就能看到，**不需要 refresh**——上游 1.0 时代 Flink 的
> `CALL sys.refresh_object_table(...)` 与 `object-location` 选项在 2.0.0 里已经不存在
> （2.0.0 的所有 paimon jar 里都搜不到这两个字符串）。实测见
> [spark-paimon-rest-e2e.md §4.4](spark-paimon-rest-e2e.md#44-三种非-paimon-表format--object--lance-的能力边界)。

> **Lance 表能建、不能读写。** Paimon 2.0.0 尚未实现它的读写，因此接口这层**不拦**
> 建表请求（拦了会连「先登记、后用别的引擎读」这个用法一起堵死），只在控制台的
> 新建表表单里不给入口（见 [console-catalog-detail.md](console-catalog-detail.md) §3.5）。
> 能力边界与证据：[spark-paimon-rest-e2e.md](spark-paimon-rest-e2e.md) §4.4。

三条规则都来自上游，这里只是把它们放到本服务端的建表路径上（实现见 `support/TableKinds`）：

- **`path` 缺省时才补**：引擎构造这三种表时只读 `options.path`
  （`CatalogUtils.toFormatTable` / `toObjectTable` / `toLanceTable`）。客户端不给就用目录
  按模板分配的位置，给了就用客户端的（且它同时是表位置，见上一节）——与上游
  `JdbcCatalog.createObjectTable` 的 `options.putIfAbsent(PATH, tablePath)` 同一个次序：
  先客户端、后目录。
- **Object 表固定六列**：`path` / `name` / `length` / `mtime` / `atime` / `owner`
  （取 `ObjectTable.SCHEMA`，不手抄）。上游 `AbstractCatalog.buildObjectTableSchema`
  同样忽略用户字段，好处是落库的 schema、仓库里的 `schema-0`、引擎读到的列三者一致；
  只顾着写用户字段的话，仓库里会留下一张没有列的 Paimon 表文件。
- **Format 表禁主键**：上游 `CatalogUtils.validateFormatTableOptions` 里的硬约束
  （`Cannot define primaryKey for format table.`）。本服务端不经由 `AbstractCatalog` 建表，
  因此得自己拦下，否则 schema 文件要等引擎读的时候才报错。

`TableMetadataService` 的 schema 物化对这三种表也分了档：Format 表与 Lance 表**不写**
`schema/schema-0`（Format 表的读路径把表目录下所有非隐藏文件当数据，写进去的 JSON
会混进查询结果；它的写路径 `FormatTableCommit` 也不需要 schema 文件。Lance 目录
布局由 lance 自己管理），Object 表**照写**（镜像上游 `JdbcCatalog.createObjectTable`，
代价是查询结果会列出这份 schema 文件自己）。但**外部表一律不写**：客户端指定了数据目录的
表登记成外部表，那个目录里放的是用户自己的对象，写元数据进去既污染对象列表，
也会在开了 `purge-on-drop` 时被连目录一起删掉。缘由与实测见
[`spark-paimon-rest-e2e.md` §4.4](spark-paimon-rest-e2e.md#44-三种非-paimon-表format--object--lance-的能力边界)。

`paimon_table.table_type` 列存的就是这个取值，`GET .../table-details?tableType=` 因此能按
格式过滤（Paimon 表的历史值是 `PAIMON`）；控制台据此伸缩页签，见
[`console-table-detail.md` §1.1](console-table-detail.md#11-页签随表格式伸缩)。

### catalog 级默认表属性：`table-default.*`

catalog 的 `properties` 里以 `table-default.` 开头的项，是这个 catalog 下
**新建 Paimon 表**默认带上的表属性（前缀在写入时去掉）。名字与语义都照 Paimon 自己来
（`Catalog.TABLE_DEFAULT_OPTION_PREFIX`），所以客户端不必知道本服务端的实现：

```
PUT /api/management/v1/catalogs/paimon
{"properties": {"table-default.deletion-vectors.enabled": "true"}}

POST /v1/paimon/databases/ods/tables
{"identifier": {...}, "schema": {"fields": [...], "options": {"bucket": "4"}}}
→ 落库的 options：{"bucket": "4", "deletion-vectors.enabled": "true"}
```

四条边界（实现见 `support/TableDefaults`，逐条有用例）：

| 规则 | 为什么 |
| --- | --- |
| 表级参数优先 | 默认值是「省事的缺省」而不是约束；要强制取值应该用 catalog 的 `overrides` |
| 只对 Paimon 表生效 | Format / Object / Lance 的 `options` 描述各自的表格式，不读 Paimon 表属性；写进去只会在仓库的 `schema-0` 里留下噪音 |
| `type` 不接受默认 | 表格式由建表请求决定；否则一次 catalog 配置会让此后所有新表换格式 |
| 只在建表时合并 | 已建成的表不受影响——改它的属性是 `alter` 的范畴 |

默认值放在 `properties` 而不是 `defaults` / `overrides`：后两者是随 `GET /v1/config`
下发给引擎的**连接参数**（合并顺序 defaults → 客户端属性 → overrides），改它影响的是
客户端能否连上；`properties` 才是 catalog 的通用属性面，也是管理 API 与控制台「配置」页
读写的那一份。Spark SQL 建表走的同样是这个 `POST .../tables`，因此引擎侧不需要各做一遍。

## 列举表：两个端点怎么选

| 端点 | 返回 | 适用 |
| --- | --- | --- |
| `GET .../databases/{database}/tables` | 表名数组（`ListTablesResponse.tables`） | 只要名字，例如做存在性判断 |
| `GET .../databases/{database}/table-details` | 完整 `GetTableResponse`（`tableDetails`）：`name` / `isExternal` / `createdAt` / `createdBy` / `updatedAt` / `updatedBy` / `schema` | 要展示或判断表格式、表类型与时间 |

两者分页约定一致（`maxResults` + `nextPageToken`，服务端按 `max-page-size` 截断），
`table-details` 多出的只是每行的字段。`GetTableResponse` **没有单独的类型字段**：
表格式从 `schema.options.type` 推（缺席即 Paimon 表），表类型从 `isExternal` 推。
`updatedAt` / `updatedBy` 是 `table.touch(principal, now)` 的结果，`create` / `alter` /
`rollbackSchema` / `rename` / `commit` / `rollback` 都会推动它——所以「元数据最新更新时间」
包含快照提交与回滚，不只是 schema 改动。控制台的表列表走的是 `table-details`，
见 [`console.md`](console.md) 的 `/browse` 行。

---

## Iceberg 协议面（26 个端点）

规格 `spec/rest-catalog-open-api-iceberg.yaml` 共 **35 个「路径 × 方法」组合**，
本服务端一期实现 **26 个**，其余 9 个**不声明**（见本节末尾）。
基址与 Paimon 面相同（`/v1`），`{prefix}` 的含义也相同——只是能落在它上面的 catalog
必须是 Iceberg 格式。`{namespace}` 是**多级**拼接段：规格默认分隔符 `0x1F`
（URL 编码 `%1F`），解析时同时接受点号。

### 装配层（2）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/v1/config` | 与 Paimon 的 `/v1/config` **同一条路径**，响应体按 catalog 格式二选一：Iceberg 侧多一份 `endpoints` 能力清单 |
| POST | `/v1/oauth/tokens` | OAuth2 客户端凭据换令牌（与 Paimon 侧共用） |

`endpoints` **必须发**：Iceberg 规格写明服务端不返回它时，客户端会假定一整套默认端点存在，
而那份默认清单里含 `POST /v1/{prefix}/transactions/commit`（多表事务）——
本服务端不做多表事务，不发声明等于承诺了一个不存在的能力，
故障现场会离原因很远（客户端一路正常，直到用户真的发起一次多表提交）。
清单的内容是 `IcebergEndpoints.SUPPORTED`，与控制器映射逐条对应，
由 `scripts/verify-iceberg-endpoints.py` 机械校验。

### namespace（6）

| 方法 | 路径 | 成功 |
| --- | --- | --- |
| GET | `/v1/{prefix}/namespaces` | 200（可选 `?parent=` 只列直接子级） |
| POST | `/v1/{prefix}/namespaces` | 200（重名 409） |
| GET | `/v1/{prefix}/namespaces/{namespace}` | 200 |
| HEAD | `/v1/{prefix}/namespaces/{namespace}` | **204** |
| DELETE | `/v1/{prefix}/namespaces/{namespace}` | 204；**非空时 409 `NamespaceNotEmptyException`** |
| POST | `/v1/{prefix}/namespaces/{namespace}/properties` | 200（`updated` / `removed` / `missing` 三份清单） |

namespace ↔ `paimon_database` 是同一个东西的两个名词：Iceberg 的多级 namespace
按点号拼进 `name` 列，所以 `["accounting","tax"]` 在库里是 `accounting.tax`，
与控制台、与 Spark SQL 的三段名读法一致。

**删除不级联**，与 Paimon 面 `DELETE /databases/{db}` 的行为**刻意不同**：
规格要求非空时回 409。Iceberg 客户端拿到 409 的动作是「先把表删掉」，
而静默级联会让它以为删完了、实际上数据文件成了孤儿。计数会把五种从属对象都数一遍
（表 / 视图 / 函数 / 语义视图），因为客户端只知道自己那几个。

### table（11）

| 方法 | 路径 | 成功 |
| --- | --- | --- |
| GET | `/v1/{prefix}/namespaces/{namespace}/tables` | 200（`identifiers[]`，`namespace` 是数组） |
| POST | `/v1/{prefix}/namespaces/{namespace}/tables` | 200（`stage-create` 报 400） |
| POST | `/v1/{prefix}/namespaces/{namespace}/register` | 200（登记已存在于对象存储的表，标记为外部表） |
| GET | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | 200（可选 `?snapshots=all\|refs`） |
| HEAD | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | **204** |
| POST | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | 200（一轮 update；并发冲突 409 `CommitFailedException`） |
| DELETE | `/v1/{prefix}/namespaces/{namespace}/tables/{table}` | **204**（可选 `?purgeRequested=`） |
| POST | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/unregister` | **200**（不是 204） |
| GET | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/credentials` | 200（无条件下发凭据） |
| POST | `/v1/{prefix}/namespaces/{namespace}/tables/{table}/metrics` | 204 |
| POST | `/v1/{prefix}/tables/rename` | **204**（Paimon 同路径是 200） |

几处与 Paimon 面不同、且客户端会据以判断结果的地方：

- **写路径是「一轮 update 作用在当前元数据上」**，不是「提交一个新快照」。
  `updates` / `requirements` 的元素交给 `iceberg-core` 的
  `MetadataUpdateParser` / `UpdateRequirementParser` 解析，服务端只做校验与应用——
  update 有 25 种、requirement 有 8 种，手写等于把上游的元数据代数重写一遍。
- **元数据文件真的落到仓库**：`<表目录>/metadata/<5 位序号>-<uuid>.metadata.json`，
  序号从上一代推出来（`ls metadata/` 是排障第一步，序号不连续会变成要读代码才能回答的问题）。
  次序是**先写仓库、后落库**：失败时最多留一个孤儿文件（无害，`remove_orphan_files` 能清），
  而反过来会让库里指向一个不存在的文件、表从此读不开。
- **`X-Iceberg-Access-Delegation: vended-credentials` 是「请把凭据给我」**，
  不问不给；`/credentials` 那条端点本身就是请求，不必再看头。两者都复用
  Paimon 侧那套 `StorageCredentialManager` 与缓存（键同源，于是同一张表从两个面各取一次
  会命中同一份——否则密钥轮换时会表现成一半请求失败）。
- **`snapshots=all`（含不传参数）回库里存的**原文**，不是重新序列化一遍**：
  引擎读到的那份文件与库里的必须逐字节相同，中间过一遍 parse/serialize 就可能把
  本服务端不认识但必须保真的字段（`next-row-id` / `encryption-keys`）弄丢。
  `refs` 时按上游 `suppressHistoricalSnapshots()` **重建**一份，而不是自己删数组元素。
- **`metrics` 只校验 + 记一条 warn，不写投影**：报告里的是**本次提交的增量**，
  而投影列存的是**表的当前总量**，写进去会让控制台的统计卡片立刻变成错的数。
  它唯一能给出的确定结论是「报告里的 `snapshot-id` 不在本目录的投影里」——
  那说明有人绕开本服务端往仓库里写了。

### view（7）

| 方法 | 路径 | 成功 |
| --- | --- | --- |
| GET | `/v1/{prefix}/namespaces/{namespace}/views` | 200 |
| POST | `/v1/{prefix}/namespaces/{namespace}/views` | 200 |
| GET | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | 200 |
| HEAD | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | **204** |
| POST | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | 200（一轮 update 作用在 `ViewMetadata` 上） |
| DELETE | `/v1/{prefix}/namespaces/{namespace}/views/{view}` | 204 |
| POST | `/v1/{prefix}/views/rename` | **204** |

视图与 Paimon 视图共用 `paimon_view` 表，但 `view_schema_doc` 存的是整份
`ViewMetadata`（Iceberg 形状），因此两边互不认对方的行——一个通过 Paimon 面建的视图
在 Iceberg 面会 404，反之亦然，且在报错里说清原因（而不是一句「视图不存在」）。

**删视图只摘目录条目，不碰仓库，也不受 `purge-on-drop` 影响**：视图不拥有数据目录
（它只是一段 SQL），它的位置甚至可能与别的对象共用，按目录删会误删别人的文件。
代价是留一个几 KB 的孤儿 metadata 文件。

### 一期不做的 9 个（因此不声明）

| 端点 | 归入 |
| --- | --- |
| `GET /v1/{prefix}/namespaces/{namespace}/functions` | 二期（只读投影 `FunctionMetadata`） |
| `GET /v1/{prefix}/namespaces/{namespace}/functions/{function}` | 二期 |
| `POST /v1/{prefix}/namespaces/{namespace}/register-view` | 二期 |
| `POST /v1/{prefix}/transactions/commit` | 二期（跨表原子提交 + `requirements` 对齐） |
| `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan` | 不做（需要常驻扫描规划引擎） |
| `GET /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}` | 不做 |
| `DELETE /v1/{prefix}/namespaces/{namespace}/tables/{table}/plan/{plan-id}` | 不做 |
| `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/tasks` | 不做 |
| `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign` | 不做（远程签名要 KMS） |

这份清单在代码里有两处副本，**必须与上表逐字一致**：`IcebergEndpoints` 的类注释
（哪 9 个不声明）与 `scripts/verify-iceberg-endpoints.py` 的 `NOT_IMPLEMENTED`。
校验脚本是从规格里读出「路径 × 方法」来比的，不是照抄上表——
`plan` 有三个方法（`POST` 建 / `GET` 拉 / `DELETE` 取消）、`tasks` 是 `POST`（不是 `GET`），
这两处容易照记忆写错。

### 错误模型

Iceberg 路径的错误响应是**嵌套一层**的 `{"error": {"message", "type", "code"}}`，
与 Paimon 面的扁平形状不同。判据是**路径形状优先、catalog 格式兜底**：
两个规格选了不同的资源名词（`databases` / `namespaces`），绝大多数请求只看路径就能定。
形状选错的后果很具体——Spark 的 Iceberg 客户端拿不到 `error` 字段，
只会报一句没有 message 的「Unable to process」，看不出真正的原因。

`error.type` 是一个类名字符串（客户端不按它分支，只是展示给使用者）：
`NoSuchNamespaceException` / `NoSuchTableException` / `NoSuchViewException` /
`AlreadyExistsException` / `CommitFailedException` / `NamespaceNotEmptyException` /
`UnsupportedOperationException` / `BadRequestException` / `ForbiddenException`。
`code` 用 HTTP 状态码本身。详见 [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §2.3。
