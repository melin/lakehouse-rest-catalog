# Catalog API 端点清单

Paimon Rest Catalog API 在本服务端的全部 **60 个端点**，按资源分组列出。

- 规格来源：Apache Paimon 的 `docs/static/rest-catalog-open-api.yaml`
  （随仓库副本 `spec/rest-catalog-open-api.yaml`，运行时也可从 `/rest-catalog-open-api.yaml` 下载）
- 规格规模：**60 个端点、144 个 schema**
- 基址：`{scheme}://{host}/v1`。除 `GET /v1/config` 外，全部端点都带 `{prefix}`——
  它是 catalog 的对外标识，由 `GET /v1/config` 下发
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
| `format-table` | Format 表 | 补 `path` = 表位置；**禁止主键**（400） |
| `object-table` | Object 表 | 补 `path` = 表位置；用户声明的字段被**忽略**，落库的是固定六列 |
| `lance-table` | Lance 表 | 补 `path` = 表位置；字段、非空、主键与 Paimon 表一致 |

其余取值一律 400。写进库的话，引擎读表时才在 `CoreOptions.TYPE` 的枚举上炸，
出错现场离原因太远，所以宁可在这里回一句「不认识的类型」。

三条规则都来自上游，这里只是把它们放到本服务端的建表路径上（实现见 `support/TableKinds`）：

- **补 `path`**：引擎构造这三种表时只读 `options.path`
  （`CatalogUtils.toFormatTable` / `toObjectTable` / `toLanceTable`），而客户端不会送它——
  替内部表决定位置本就是 catalog 的职责，与 `JdbcCatalog.createObjectTable` 的
  `options.putIfAbsent(PATH, tablePath)` 一致。客户端若要指向别处的目录，
  自己在 `options.path` 里给，服务端只在缺席时补。
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
代价是查询结果会列出这份 schema 文件自己）。缘由与实测见
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
