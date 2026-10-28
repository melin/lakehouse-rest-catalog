# Lance REST Catalog：把 Lance 数据集纳入同一目录服务

> 状态：**P0 + P1 + P2 里能做的都已落地**（2026-10-28）。P0 的 13 个 operation、
> P1 的 2 个版本只读、P2 里 `CreateTableVersion` / `BatchDeleteTableVersions` 两个写端点
> 与标签只读的 2 个（`ListTableTags` / `GetTableTagVersion`），以及「未实现 operation
> 恒 406」的兜底控制器都已实现并通过测试
> （`LanceRestCatalogApiTests` / `LanceVersionApiTests` / `LanceTagApiTests` /
> `LanceManifestReaderTests` / `LanceIdParsingTests` / `LanceEndpointAuthorizationTests`
> + `verify-lance-endpoints.py`）。
> **仍未做的三处**：`BatchCreateTableVersions`（路径不带表标识 → 授权层无法定位资源，
> 理由见 §12.6）、索引的只读代理（规范没定义 `_indices/` 的盘上格式，§12.8）、
> 控制台只读那一节（§7）。
> 下面的正文仍是当时的设计口径，
> 与实现有偏差的地方在 [§12 实测偏差](#12-实测偏差落地后回填) 里逐条记着。
> 对标：Apache Gravitino 1.3.1 的 [Lance REST service](https://gravitino.apache.org/docs/1.3.1/lance-rest-service)，
> 规范以 [Lance Namespace / REST Catalog](https://lance.org/format/namespace/) 为准（OpenAPI 文档版本 `1.0.0`，
> operation 版本 `V1`）。**入库的规范副本有 54 个 operation**，比本文写作时的 48 多 6 个
> （branches 三个、materialized_view 两个、`update_field_metadata` 一个，全部落在「不做」一栏），
> 逐条见 [§12.5](#125-规范副本是-54-个-operation-不是-48)。
> 范围：服务端新增第三个协议面 `/lance/v1/**`（13 个 operation 必达、2 个可选）、
> `catalog_format=LANCE`、凭据下发、控制台只读；**不做数据面**，也不写仓库里的任何文件。
> 一句话结论：**能支持，而且比 Iceberg 那一面更省事——因为 Lance 的规范自己就把「catalog 只做登记处」写成了推荐实现。**
> 但有一条必须先说清：Lance 的「表」不是一个 schema 文档，而是一个带版本目录的数据集，
> 所以这一面能承诺的是**「表在哪、叫什么、谁能用」**，不是「表长什么样」。

---

## 0. 结论速览

| 问题 | 定案 |
| --- | --- |
| 要不要做 | **做**，作为第三个协议面，与 `/v1/{prefix}`（Paimon / Iceberg）**并列而不合并** |
| 挂在哪 | 新基址 `/lance/v1/**`；Lance 客户端只配 `uri` + `delimiter`，**没有 prefix 握手** |
| 怎么和现有 catalog 对上 | `{id}` 的**第一级就是我们的 catalog**（Gravitino 同形：metalake 配在服务端，catalog 是第一级） |
| 格式怎么区分 | `lakehouse_catalog.catalog_format` 增加 `LANCE`，创建时必选、**不可变**（沿用 Iceberg 的定案） |
| 数据模型 | **加 3 列、1 张新表**（`properties_json` / `table_version` / `managed_versioning`；`lakehouse_lance_table_version` 版本账，P2 加） |
| 做多少端点 | P0 **13 个**（5 个 namespace + 8 个 table），P1 再 **2 个**（版本只读），P2 再 **4 个**（版本写 2 + 标签只读 2） |
| 剩下 35 个怎么办 | **全部恒 `406` + `{"code":0,"error":"..."}`**，不是 404、不是 500，更不是「假装成功」；逐个理由见 §3.1（为什么不做数据面、为什么连索引的只读也不做，见 §12.8） |
| 数据面（query / insert / index） | **不做**。规范原文把这类操作列为「catalog 不该承担」，我们照抄这条理由 |
| `CreateTable` / `DropTable` | **不做**，只做 `DeclareTable` / `DeregisterTable`（规范专门写了「Why Not」一节） |
| `managed_versioning` | 表级开关，**默认 `false`**；`true` 时版本写端点才可用（P2 已落地，§6.3） |
| 仓库里的物化产物 | **一个字节都不写**。与 Paimon（写 schema 文件）、Iceberg（写 metadata.json）相反 |
| 权限 | 复用现有 12 个 access 词与 8 个资源类型，新增一张 `LanceAccessRules` 映射表，**失败关闭** |
| 凭据 | 复用 `StorageCredentialManager`，把 `token` 摊进 `storage_options`，并补 `expires_at_millis` |
| 控制台 | 只读：catalog 格式徽标、表详情三个页签（详细信息 / 文件列表 / 血缘），**不给建表入口** |

---

## 1. 事实基线

### 1.1 规范有两层，我们只做第二层

Lance 的规范文档把同一套数据模型用在两处，这一点决定了工作量：

| 层 | 是什么 | 我们关心吗 |
| --- | --- | --- |
| **Namespace Client spec** | 抽象操作集合（`DescribeTable`、`ListTables`……）与请求/响应模型，`id` 必须写在请求体里 | 否——我们不实现客户端 |
| **REST Catalog spec** | 把上面那套模型**直接当 HTTP body 用**，`id` 挪到路径里，少数操作改走 query / header | **是，这就是我们要实现的** |
| 具体实现 | Gravitino / LanceDB / directory / HMS 适配器 | 参照，不照抄 |

规范明说两者是 duality（对偶）：同一份 schema 既做客户端模型又做 HTTP body。所以**我们不需要设计任何 DTO 的语义**，只需要按它给的字段名逐字实现——这与 Iceberg 那一面要引 `iceberg-core` 手写 25 种 `TableUpdate` 的处境完全不同。**Lance 面没有「元数据代数」可写**，这是它简单一半的原因。

### 1.2 路由：没有 prefix 握手，id 在路径里

Paimon / Iceberg 的客户端都要先 `GET /v1/config` 拿 `prefix`，之后每条路径都带它。Lance **没有这个动作**：

```
POST /lance/v1/namespace/{id}/create          ← 客户端配的 uri 是 http://host:port/lance
GET  /lance/v1/namespace/{id}/table/list
POST /lance/v1/table/{id}/describe
```

规范给路由定的原则值得抄进我们自己的评审材料：*「所有反代（负载均衡、认证、鉴权）需要的信息都必须不用反序列化 body 就能拿到」*——所以 `CreateTable` 的路径是 `/v1/table/{id}/create` 而不是 `/v1/table`。这与本仓库把权限映射做成「路径 → access 一张表」的做法是同一个思路，能直接对上。

`{id}` 是**用分隔符拼起来的完整路径**，默认分隔符 `$`，在 URL 里要写成 `%24`：

```
逻辑标识：  my_catalog.sales.orders
路由形态：  my_catalog$sales$orders
URL 里：    my_catalog%24sales%24orders
```

分隔符可由客户端配置（`delimiter`，例如 `::`），所以**服务端不能假设一定是 `$`**：`delimiter` 是多个操作上的 query 参数，缺省才是 `$`。

规范还有一条硬要求，直接影响我们的校验逻辑：**路径里的 `id` 与 body 里的 `id` 同时出现且不一致时必须 400**；body 里没写就以路径为准。

### 1.3 命名空间只有两级

Lance 允许实现自定义层级，但生态里的事实标准是**两级**（Gravitino 的文档把它写成了硬限制）：

```
catalog        ← 第一级
└── schema     ← 第二级
    └── table
```

- 表只能挂在 schema 下；`catalog/schema/sub_schema` 这种三级命名空间要被拒；
- schema 只有在 catalog 已存在时才能建；
- 删除命名空间在 Gravitino 的实现里是**递归删数据**——这一条我们不跟（见 §5.1）。

对我们的意义：**我们的三层模型（catalog → database → table）与它的一一对应，不需要任何嵌套或扁平化技巧**。这也解释了为什么 §4.1 选「catalog 当第一级」而不是「database 当第一级」。

### 1.4 错误模型：22 个 code，一张映射表

响应体是 RFC 7807 风格的四字段，不是 Paimon / Iceberg 那种 `{"error": {"message","type","code"}}`：

```json
{
  "error": "Table 'orders' not found in namespace 'sales'",
  "code": 4,
  "detail": "…可选，调试用…",
  "instance": "/lance/v1/table/my_catalog$sales$orders/describe"
}
```

`code` 是全局整数枚举，**HTTP 状态由它算出来**，不能各自决定：

| code | 名字 | HTTP |
| --- | --- | --- |
| 0 | Unsupported | **406** |
| 1 / 4 / 6 / 8 / 10 / 11 / 12 | NamespaceNotFound / TableNotFound / TableIndexNotFound / TableTagNotFound / TransactionNotFound / TableVersionNotFound / TableColumnNotFound | 404 |
| 2 / 3 / 5 / 7 / 9 / 14 / 19 | NamespaceAlreadyExists / NamespaceNotEmpty / TableAlreadyExists / TableIndexAlreadyExists / TableTagAlreadyExists / ConcurrentModification / InvalidTableState | 409 |
| 13 / 20 | InvalidInput / TableSchemaValidationError | 400 |
| 15 | PermissionDenied | 403 |
| 16 | Unauthenticated | 401 |
| 17 | ServiceUnavailable | 503 |
| 18 | Internal | 500 |
| 21 | Throttling | 429 |

两处必须注意：

1. **`code 0 → 406` 是这套规范里最有用的一个位置。** 它让「本实现不支持」有一个规范内的表达，而不是逼我们编一个假成功或 501。本方案所有不做的端点都用它。
2. **code 12 在旧文档里是 `TableVersionAlreadyExists`，现行规范里是 `TableColumnNotFound`。** 我们按现行规范实现，但在兼容矩阵（§1.7）里记一笔——这类漂移会在客户端版本差异时表现为「错误码对不上」。

我们的 `GlobalExceptionHandler` 现在是「按请求路径判格式，Paimon 与 Iceberg 各一套形状」。加第三套时要注意：`/lance/**` 下**连 404、405、参数校验失败、未捕获异常都要走 Lance 形状**，否则客户端拿到 Spring 的默认错误页会解析失败。

### 1.5 规范自己划的最小集（这条最关键）

规范里有一节 **Recommended Basic Operations**，把「能用的最小集」定为 8 个操作，并给了**两条明确的降级许可**。原文要点：

> **DropNamespace**：只要求 `Restrict` 行为——命名空间必须为空才能删；递归的 `Cascade` **不是基本实现的必要条件**。
> **DescribeTable**：只要求 `load_detailed_metadata=false` 与 `check_declared=false`（默认值）——**实现只需要返回表的 `location`，不需要打开数据集**；返回 `version` / `schema` / `stats` 这些要开数据集才能拿到的字段，不是必需的。

以及专门的一节 **Why Not `CreateTable` and `DropTable`**，理由三条（长耗时数据操作、原子性难以保证、CTAS 需要暂存或跨语句事务），结论是：catalog 只需做 `DeclareTable` / `DeregisterTable` 这类「快、小、原子」的元数据操作，**建表与删表由 Lance SDK 在客户端完成**。

这三段话把本方案最容易被质疑的两个点（「你们怎么连 schema 都不给」和「为什么不实现建表」）从「我们的取舍」变成了**规范推荐形态**。文档里必须把这句话写前面，否则每次评审都要重讲一遍。

> 反过来说，这也给了我们一条**可验证的边界**：只要 P0 的 13 个端点覆盖了这 8 个基本操作 + 生态里常用的列表/存在性/重命名，就是一个合规实现，而不是「残缺实现」。

### 1.6 客户端在哪

| 客户端 | 入口 | 对我们的意义 |
| --- | --- | --- |
| Python | `lance.namespace.connect("rest", uri=…, delimiter=…, headers=…)` | 最容易做 e2e 的验证器 |
| Java | `org.lance:lance-namespace-apache-client`（OpenAPI 生成） | 与反代/鉴权链路一起验 |
| Spark | `LanceNamespaceSparkCatalog`（`lance-spark-bundle-3.5_2.12`，DSv2） | 与 `generic-table-design.md` §6.4 里那份连接器是同一个包 |
| Gravitino | 它自己的 Lance REST service（`/lance` 前缀） | 参照实现，也是兼容性对照 |

认证统一是 **Bearer token**（规范的 `security` 是 OAuth2 / Bearer）。这与我们现有的 `BearerAuthInterceptor` 同构，不需要新的认证机制。

### 1.7 版本漂移：Gravitino 1.3.1 与 lance.org 现行 spec 有四处不同

这一节是给实现者的「别照抄参考实现」清单。Gravitino 的文档与 Lance 官方现行规范在以下四处不一致，**以 lance.org 为准，对旧形态做兼容**：

| 事项 | Gravitino 1.3.1 / 旧 spec | lance.org 现行 spec | 我们的处理 |
| --- | --- | --- | --- |
| 基址 | 固定 `/lance` + `/v1` | 基址任意（`servers` 可配） | 用 `/lance/v1`，与它一致 |
| 建空表 | `POST …/table/{id}/create-empty` | `POST …/table/{id}/declare` | 只实现 `declare`；`create-empty` 做 301/别名可选（P2） |
| `CreateTable` 的 `location` / `properties` | 请求头 `x-lance-table-location` / `x-lance-table-properties` | 同一份页面写**请求头**；另一份中文文档写**query 参数** | 我们不做 `CreateTable`，但若将来做：**两种都接受，头优先** |
| `ListTableVersions` | `GET …/version/list` | `POST …/version/list` | P1 实现时两种方法都接 |
| code 12 的含义 | `TableVersionAlreadyExists` | `TableColumnNotFound` | 按现行规范，兼容矩阵里记一笔 |

> 还有一处不是漂移而是**约定**：Gravitino 的层级是 metalake → catalog → schema → table，但它把 Lance 的第一、二级映射成「catalog、schema」，metalake 隐含在服务实例里。我们照这个形状做（§4.1），第一级用我们的 catalog 名。

**结论：P0 的第一步不是写代码，是抓包**（见 §8 风险 1）。参考实现支持的操作集合与现行规范不同，客户端实际发什么参数只能实测。

---

## 2. 三个「Lance」别搞混

仓库里现在有三种东西都叫 Lance，混了就会得出错误的结论：

| | ① Paimon 的 `lance-table` | ② 通用表的 `format=lance` | ③ **本文档：Lance REST Catalog** |
| --- | --- | --- | --- |
| 谁解释这张表 | Paimon（`type=lance-table` 是 Paimon schema 里的表类型） | 没人。catalog 只存信封，schema 由引擎扫 | Lance 自己（manifest 在数据集里） |
| 走哪个协议面 | `/v1/{prefix}`（Paimon 的 60 个端点） | Polaris 风格的 `generic-tables` 4 个端点 | `/lance/v1/**`（48 个 operation） |
| 客户端 | Paimon / Spark（Paimon bundle） | Spark + lance 连接器 | Lance SDK / Lance Spark catalog |
| 我们的现状 | **已支持建表，不能读写**（Paimon 2.1.0 仍未实现 Lance 读写，见 `catalog-api-endpoints.md` §4.3） | **设计稿，未实现**（`generic-table-design.md`） | 本文档 |
| 表的身份 | `lakehouse_table` 一行（`table_type='lance-table'`） | `lakehouse_table` 一行（`table_kind='GENERIC'`） | `lakehouse_table` 一行（`catalog_format=LANCE`） |

三条必须写进实现约束的结论：

1. **一个 catalog 只能有一种格式**，所以 ① 与 ③ 永远不会出现在同一个 catalog 里。①的表在 Paimon catalog 下，③的表在 Lance catalog 下，二者同名也不冲突（`uk_table` 带上 `catalog_id`）。
2. **`table_type` 的字符串不能再复用。** ①用 `lance-table`（Paimon 的词），③用 `LANCE`（我们的格式词）。控制台按 `catalog_format` 而不是按 `table_type` 判页签，否则 ③ 会被当成 ①。
3. **②与③会争同一块地**：同一份 `_versions/` 目录既可以被通用表登记（`format=lance`，靠引擎读），也可以被 Lance catalog 登记（靠 Lance SDK 读）。两者对「谁是权威」的回答不同。建议**互斥**：Lance catalog 的表不允许再被通用表接口登记（P2 再讨论，见 §8 决策 D6）。

---

## 3. 定位：只做元数据面与凭据面

### 3.1 operation 的取舍总表（写作时 48 个，入库副本 54 个）

| 分组 | operation | 我们 |
| --- | --- | --- |
| Namespace（5） | Create / List / Describe / Drop / Exists | **P0 全做** |
| 表元数据（8） | ListTables、ListAllTables、DescribeTable、TableExists、RegisterTable、DeregisterTable、DeclareTable、RenameTable | **P0 全做** |
| 表版本（5） | ListTableVersions、DescribeTableVersion | **P1 已做这 2 个**（只读目录，不写） |
| | CreateTableVersion、BatchDeleteTableVersions | **P2 已做这 2 个**（只在 `managed_versioning=true` 时可用） |
| | BatchCreateTableVersions | **不做**（406）：路径不带表标识，授权层无法定位资源，见 §12.6 |
| 建表/删表（2） | CreateTable、DropTable | **不做**（406），规范自己劝退（§1.5） |
| 数据面（11） | InsertIntoTable、MergeInsertIntoTable、UpdateTable、DeleteFromTable、QueryTable、CountTableRows、ExplainTableQueryPlan、AnalyzeTableQueryPlan、RefreshMaterializedView、GetTableStats、RestoreTable | **不做**（406） |
| 列演进（4） | AlterTableAddColumns、AlterTableAlterColumns、AlterTableBackfillColumns、AlterTableDropColumns | **不做**（406）——改 schema 就是改数据集 |
| Schema 元数据（1） | UpdateTableSchemaMetadata | **不做**（406）——同上 |
| 索引（5） | CreateTableIndex、CreateTableScalarIndex、ListTableIndices、DescribeTableIndexStats、DropTableIndex | **不做**（406）——索引住在 `_indices/`，是数据面；**连只读也不做**，因为规范没定义 `_indices/{UUID}/` 里的文件格式（§12.8） |
| 标签（5） | ListTableTags、GetTableTagVersion | **P2 已做这 2 个**（只读代理：读 `_refs/tags/*.json`，见 §6.3 L2-2 与 §12.8） |
| | CreateTableTag、DeleteTableTag、UpdateTableTag | **不做**（406）——写标签等于替客户端提交 |
| 事务（2） | DescribeTransaction、AlterTransaction | **不做**（406）——Lance 的事务文件是提交协调的产物，我们不是协调者 |

合计（按本文写作时的 48 个算）48 = 13（P0）+ 2（P1）+ 5（P2）+ 28（恒不做，406）；
**落地时按入库副本的 54 个算**是 54 = 19（已做）+ 35（恒不做，406），
多出来的 6 个 operation 全在「恒不做」那一栏（§12.5）。

**一张图说清边界**：请求进来，我们只回答两类问题——「这个标识存在吗」「它的位置与凭据是什么」。任何要求我们**打开数据集、算一个数、写一个版本**的调用，都在 406 的那一栏。

### 3.2 为什么不做 `CreateTable` / `DropTable`

三个理由，前两个是规范给的，第三个是我们的：

1. **它们是数据操作**。`CreateTable` 的 body 是 Arrow IPC 流（`application/vnd.apache.arrow.stream`），要在指定位置写出 `_versions/1.manifest` 与数据文件；`DropTable` 要删掉整个目录。这都不是「快、小、原子」的元数据操作。
2. **失败会留下半成品**。写到一半失败，目录里可能既有 manifest 又缺数据文件；catalog 没有回收机制，只能在文档里写「请自行清理」。
3. **我们的进程不该持有这份写权限**。本仓库的存储凭据是「按需签发」的（`StorageCredentialManager.vend`），服务端自己**从不写仓库**（Paimon 的 schema 物化是既有例外，见 §4.7）。一旦为了 `CreateTable` 让服务端拿到写权限，这条边界就没了，而它正是 `row-filter-column-mask-design.md` §7.1 那条硬边界的前提。

替代路径（这也是 Lance SDK 自己的流程）：

```
客户端 DeclareTable        → 我们返回 location + storage_options
客户端用 Lance SDK 建数据集  → 直接写对象存储，我们不知情也不需要知情
客户端（可选）RegisterTable → 位置里已有合法数据集时，登记进目录
```

### 3.3 为什么不做数据面

除了「catalog 不该算数据」这条原则，还有三条具体理由：

1. **协议形状不同**：`QueryTable` 的响应是 Arrow IPC **文件**（`application/vnd.apache.arrow.file`），`CountTableRows` 返回**裸整数**，`ExplainTableQueryPlan` 返回**裸字符串**。这不是 JSON 层能糊过去的东西，要真读数据。
2. **需要查询引擎**：Lance 的读路径要解释 V1/V2 两种数据文件格式、deletion vector、索引。我们的 `table-data-preview-design.md` 已经为了「预览」引了 `parquet-avro`；再引一套 Lance reader（JNI）与「服务端零 native 依赖」的既有约定冲突。
3. **长事务**：`MergeInsertIntoTable` 的 `timeout` 参数是分钟级的。HTTP 连接等不了，异步化又要引入任务队列与状态机——那是优化任务（`table-optimization.md`）那一层的复杂度，不该在目录面里再长一遍。

### 3.4 为什么不做 `managed_versioning`

`DescribeTableResponse.managed_versioning` 是一个**双向约定**：

- `false` / 缺省：版本由 Lance 原生机制管（客户端写 `_versions/{n}.manifest`，靠对象存储的 put-if-not-exists 抢）；
- `true`：**客户端不改原生版本**，而是先上传 manifest 到暂存路径，再调 `CreateTableVersion` 让我们记账，版本号由我们分配。

我们在 P0 恒返回 `false`（准确说：**不返回该字段**）。理由是：

1. 它是**硬承诺**：一旦有客户端看到 `true`，它的写入路径就完全依赖我们；我们挂 5 分钟，整条写链路就断。
2. 它把我们变成**提交协调者**：要做真正的并发正确性，需要 `(table_id, version)` 唯一约束 + CAS + 对暂存 manifest 的可见性判断。我们确实有事务数据库（这是少数几个能真做协调的 catalog），但这属于「catalog 开始参与数据面」，与 §3.2 的边界冲突。
3. **它需要 `CreateTableVersion` 系列配套**：3 个写端点（`CreateTableVersion` / `BatchCreateTableVersions` / `BatchDeleteTableVersions`），连同 2 个读端点一共 5 个；错误语义（`ConcurrentModification` / `InvalidTableState` / `12` 号）都得实打实做对。

> 反过来看，这是我们**唯一比 Gravitino 更有优势的方向**：它的 namespace 后端是 Gravitino 自己的 KV，做不了强一致的版本记账；我们的 DB 可以。所以这一项放在 P2，且必须单独评审——**不该在「支持 Lance」这个目标里偷偷带进来**。

### 3.5 与既有哲学的关系

本仓库已有两条线：

- **Paimon / Iceberg**：DB 是元数据的真相源，仓库里同步物化出引擎能读的文件（`schema-0`、`metadata.json`）。
- **通用表（设计稿）**：catalog 只存信封，不知道 schema，也不假装知道。

Lance 面属于第二条线，而且比通用表**更彻底**：连「schema 快照」都不存。原因是 Lance 的 schema 在 manifest 里、随每次提交变化，我们存一份就必然撒谎。

所以三条面并列之后，`data-model.md` 里的说法要补一句：**「catalog 知道什么」是由格式决定的**——Paimon/Iceberg 是权威，Lance 是登记处，通用表是信封。

---

## 4. 映射与落点

### 4.1 基址的三种选法

| 方案 | 形态 | 优点 | 否决理由 |
| --- | --- | --- | --- |
| **A（推荐）** | `uri=/lance`，`{id}` 第一级 = catalog | 与 Gravitino 一致；一个 `uri` 服务所有 catalog；不发明新路径形态 | — |
| B | `uri=/lance/{prefix}`，catalog 隐含在基址里 | 与现有 `/v1/{prefix}` 习惯一致 | 每个 catalog 一个 `uri`；第一级命名空间与基址重复（`/lance/foo/v1/namespace/foo/sales`）；「删 catalog」变成删第一级命名空间，语义纠缠 |
| C | `uri=/lance`，`{id}` 只有 schema 与 table（单级） | 路径最短 | 与生态全部示例（含 Gravitino、LanceDB 文档）的一级 catalog 约定不符；客户端会把 `sales` 当 catalog，建 schema 时撞墙 |

选 A。代价是**每条请求都带 catalog 名**（多一段 URL），收益是「一个基址、多 catalog、与参考实现同形」——后者决定了用户能不能直接复制 Gravitino 的示例。

由此得到的基址清单（新增一行，与现有四面并列）：

| 面 | 基址 | 规格来源 |
| --- | --- | --- |
| Catalog API（Paimon / Iceberg） | `/v1/{prefix}/...` + `/v1/config` | 两份 OpenAPI（`spec/`） |
| **Lance Catalog API** | **`/lance/v1/...`** | Lance Namespace REST spec（`spec/lance-namespace-rest-openapi.yaml`，待补） |
| Management API | `/api/management/v1/...` | Paimon REST Management API |
| Console API | `/api/console/v1/...` | 自有 |

### 4.2 id 解析与校验

一个解析器，五条规则，全部要有单测（`LanceIdParsingTests`）：

| 规则 | 行为 | 为什么 |
| --- | --- | --- |
| `delimiter` 缺省 `$`，可被 query 覆盖 | 按 `delimiter` 切分，而不是硬编码 `$` | 规范允许客户端换分隔符（`::` / `__delim__`） |
| URL 解码后再切分 | `%24` → `$`；遇到编码的 `/`（`%2F`）直接 400 | 防止路径穿越与「一段 id 装两条路径」 |
| 段数 | namespace 操作 1–2 段；table 操作恰好 3 段 | 规范两级；表只能在 schema 下 |
| 空段 / 末尾分隔符 | 400 `InvalidInput` | `a$$b` 是我们无法解释的输入 |
| 路径与 body 的 `id` | 都出现且不等 → 400；body 缺省以路径为准 | 规范明文 |

段内字符：沿用现有 `CatalogUtils` 的标识符校验（字母数字与 `_ - .`），**白名单之外一律 400**。理由不是洁癖：`id` 会进 URL、进日志、进 `uk_table`，任何通配字符都会在某处变成注入面。

### 4.3 `catalog_format` 加 `LANCE`，以及跨格式怎么答

- 枚举加 `LANCE`（`CatalogFormat`），创建时必选、不可变——理由与 Iceberg 那一面完全相同（`iceberg-rest-catalog-design.md` §3.1）：同一个 prefix 下的表，元数据代数、物化产物、端点集合都不同。
- **`/lance/v1` 下的请求命中 `catalog_format != LANCE` 的 catalog 时，回 `404 NamespaceNotFound`**，不是 400、不是 403。

这条要单独解释，因为它看起来像信息丢失：Lance 的错误模型里没有「格式不匹配」这个 code；而「这个命名空间在我的协议面上不存在」恰好是**唯一**不会让客户端误判的答案——与 Polaris 用 `TableNotFoundException` 回答「你走错了 API」是同一个取舍（`generic-table-design.md` §1.3 第 5 条）。

反过来，`/v1/{prefix}` 下访问 LANCE catalog 的表，也按 Paimon/Iceberg 的「表不存在」处理。**两个面互不越界**。

### 4.4 数据模型：加 2 列、0 张新表

`lakehouse_catalog` / `lakehouse_database` / `lakehouse_table` 三层完全复用，不加表。理由与 Iceberg 一致：三层结构本来就是同构的，另开表会让「列出 catalog 下的表」变成 union。

**P0 加 1 列**：

| 列 | 表 | 类型 | 用途 | 为什么必须加 |
| --- | --- | --- | --- | --- |
| `properties_json` | `lakehouse_table` | `varchar(65535)`，JSON map | Lance 的 `properties`（`DescribeTable.properties` / `DeclareTable` 入参） | 现有表**没有**通用属性列：`metadata_doc` 是 Iceberg `TableMetadata` 的真相源，往里塞 Lance 属性会让两个格式的语义互相污染；`options` 在 Paimon 侧是 schema 的一部分，复用会进仓库 |

**P1 再加 1 列**（只在做 `load_detailed_metadata` 时需要）：

| 列 | 类型 | 用途 |
| --- | --- | --- |
| `table_version` | `bigint` | 最近一次读到的 Lance 版本号，**纯缓存**：它是展示位，不参与任何判定，写失败只记日志（L1-4） |

**P2 再加 1 列 + 1 张表**（`managed_versioning` 落地时才需要）：

| 落点 | 用途 |
| --- | --- |
| `lakehouse_table.managed_versioning`（bit，非空） | 「版本由服务端记账」这个硬承诺的落点。开关由 `declare` / `register` 的透传属性键 `lance.managed_versioning` 给定 |
| `lakehouse_lance_table_version`（新表，唯一约束 `(table_id, version)`） | 版本账。唯一约束是 `put_if_not_exists` 真正的保证（先查再插有窗口），也是 `ListTableVersions` 与范围删除的检索键 |

> 于是「0 张新表」这句话的适用范围是 **P0 + P1**：它们不记账，版本由 Lance 原生机制管。
> P2 把「版本分配」接到服务端，代价就是这一张表——这正是本文把 P2 单列出来评审的原因。

`path` 存表的 `location`（数据集根），`external` 标记 `register` 进来的表——两者现成正好。`table_type` 填 `LANCE`（与 Paimon 的 `lance-table` 区分，见 §2）。

> 这 2 列对通用表（`generic-table-design.md` §4）同样是缺的：它的 `properties` 与「创建时 schema 快照」也需要落处。所以这 2 列是**两个特性共用的一次改动**，谁先做谁先加，另一个直接复用。

### 4.5 授权映射

现有 `CatalogAccessRules` 只覆盖 `/v1/**`，且是「未登记即拒绝」。加一面就要加一张表，形状照抄：

| 端点 | 资源类型 | access | 备注 |
| --- | --- | --- | --- |
| `CreateNamespace`（第二级 = schema） | `CATALOG` | `CREATEDATABASE` | 与 Paimon 建库同一个词 |
| `CreateNamespace`（第一级 = catalog） | — | — | 默认拒绝，见决策 D2 |
| `ListNamespaces` | `CATALOG_ALL` | `LIST` | 集合操作落 `*_ALL`，官方的模型如此 |
| `DescribeNamespace` / `NamespaceExists` | `DATABASE`（一级为 `CATALOG`） | `DESCRIBE` | 先判存在、再判权限，见下 |
| `DropNamespace` | `DATABASE` / `CATALOG` | `DROP` | |
| `ListTables` | `DATABASE_ALL` | `LIST` | |
| `ListAllTables` | `CATALOG_ALL` | `LIST` | 强制要求带 catalog 前缀，见 §5.2 |
| `DescribeTable` / `TableExists` | `TABLE` | `DESCRIBE` | |
| `DeclareTable` / `RegisterTable` | `DATABASE` | `CREATETABLE` | |
| `DeregisterTable` | `TABLE` | `DROP` | |
| `RenameTable` | `TABLE`（源）+ `DATABASE`（目标） | `ALTER` + `CREATETABLE` | 两次判定都要过 |
| `ListTableVersions` / `DescribeTableVersion` | `TABLE` | `SELECT` | 版本信息是数据面画像，用读权限 |

两个细节：

- **`Exists` 类端点的信息泄漏**：权限不足时回 403、不存在时回 404，二者必须分开，否则「403 与 404 的差异」会变成枚举工具。规范里 `NamespaceExists` / `TableExists` 允许 1/4 号错误，说明 404 是预期形态。
- **`/lance/**` 必须进认证拦截器的路径清单**（现在只配了 `/v1/**`、`/api/**`）。漏了这一步，整个 Lance 面就是匿名可调——这是本方案最容易漏的一个洞，用 `LanceEndpointAuthorizationTests` 钉住。

### 4.6 凭据下发

`storage_options` 是**这一面唯一的数据面控制点**：客户端拿它去开数据集，之后我们再也看不到它。所以策略要与 Iceberg 面一致，不能放宽。

```
DescribeTable / DeclareTable 的请求参数 vend_credentials=true
        │
        ├─ 取 catalog 的 StorageConfigInfo
        ├─ StorageCredentialManager.vend(storage, tableId, tablePath)
        │      → VendedStorageCredential{token, ttlSeconds, storageType, source}
        └─ storage_options = token + {"expires_at_millis": now + ttl*1000}
```

三个要点：

1. **`expires_at_millis` 是 Lance 的键名**（规范只要求「临时凭据要带这个键」）。我们的 `VendedStorageCredential` 已有 `ttlSeconds`，直接算毫秒时间戳，不需要新机制。
2. **不传 `vend_credentials` 时也要返回 `storage_options`**，只是里面只有定位信息（region、endpoint）。理由：客户端不传这个参数不代表它不需要知道 endpoint；但**绝不**在这种情况下塞长期密钥。
3. **`expires_at_millis` 与 `ttlSeconds` 必须同源**，否则会出现「签名 15 分钟有效、声明 1 小时有效」的错配——这正是缓存层最容易出的 bug，用单测把两者绑在一起。

### 4.7 我们不在仓库里写任何东西

| 格式 | 服务端往仓库写什么 | 为什么 |
| --- | --- | --- |
| Paimon | `schema/schema-0`（表结构） | Paimon 的 catalog 就是 schema 的权威 |
| Iceberg | `metadata/00003-*.metadata.json` | 同上，metadata 是真相源 |
| **Lance** | **什么都不写** | manifest 由客户端写；我们连 SQL 都不参与 |

这条差别带来两个后果，都要在文档里说清，否则运维会以为「表建好了但目录是空的」是 bug：

- `DeclareTable` **不碰存储**。它只落一行 DB 记录，返回位置与凭据。表在客户端真正写入之前，`is_only_declared` 应为 `true`。
- `RegisterTable` 只做**弱校验**：列一下 `<location>/_versions/` 有没有 `*.manifest`，有就登记。**不解析 manifest**（那是 P1 的事），也**不验证数据文件存在**。文档里要写明这是弱校验——因为「有 manifest 但数据不全」的表我们验不出来。

存储访问只发生在三处，都在我们的 `StorageRuntimePolicy` 之内：`RegisterTable` 的存在性探测、`check_declared=true` 时的声明态判断、P1 的 manifest 读取。**没有写路径**。

---

## 5. 端点契约（P0 的 13 个 + P1 的 2 个）

### 5.1 namespace 5 个

| 端点 | 语义（我们的口径） | 错误 |
| --- | --- | --- |
| `POST /lance/v1/namespace/{id}/create` | `mode`：`create`（默认，已存在则 409）/ `exist_ok`（已存在返回 200）/ `overwrite`（**清属性但不清数据**，见下） | 1 父不存在、2 已存在、13 参数非法 |
| `GET /lance/v1/namespace/{id}/list` | 列直接子节点；`page_token` / `limit` | 1 |
| `POST /lance/v1/namespace/{id}/describe` | 返回 `properties`（我们的 `options` + `comment`） | 1 |
| `POST /lance/v1/namespace/{id}/drop` | **只支持 `Restrict`**：非空回 `3 NamespaceNotEmpty`；`Cascade` 回 `406 Unsupported` | 1、3、0 |
| `POST /lance/v1/namespace/{id}/exists` | 存在 200（空 body），不存在 404 | 1 |

两条要展开：

**（1）`DropNamespace` 拒绝 `Cascade` 是有意的。** Gravitino 的实现会「递归删除子命名空间与表，并删掉 Lance 数据文件，不可恢复」。我们不做，三个理由：

- 它把一个 HTTP 调用变成可能持续几分钟的批量删对象存储；
- 它删的是**我们从不写的目录**——「谁创建谁删除」的边界一旦破了，`DropTable` 也守不住（§3.2）；
- 规范自己说 `Restrict` 就够（§1.5）。

所以 `Cascade` 回 `406`，并在 `error` 文案里给出替代路径：先 `DeregisterTable`，再由调用方自己清目录。

**（2）`overwrite` 只重置**我们**的元数据。** 它不改数据集里的任何东西——因为命名空间的属性（`location`、`comment`）只是我们 DB 里的字段。这一点必须写在文档里：客户端看到 `overwrite` 成功会以为「清理干净了」，实际只清了我们这层。

### 5.2 table 8 个

| 端点 | 语义 | 关键细节 | 错误 |
| --- | --- | --- | --- |
| `GET …/namespace/{id}/table/list` | 列 schema 下的表 | `tables` 里装**完整 id** 还是**裸表名**，野外两种都有（Gravitino 回 `cat$schema$table`，另一份实现回 `users`）→ 默认回完整 id，M0 抓包确认后定稿 | 1 |
| `GET /lance/v1/table/?page_token=&limit=` | 跨命名空间列表 | **必须带 catalog 前缀**，否则 400：全库扫描既慢又越权（`CATALOG_ALL` 也挡不住「列了别的 catalog」的期待） | 13 |
| `POST /lance/v1/table/{id}/describe` | 返回 `location` + `properties` + `storage_options` | `load_detailed_metadata` / `check_declared` / `with_table_uri` 三个 query 参数的处理见下 | 1、4、0 |
| `POST /lance/v1/table/{id}/exists` | 不存在即 404 | 与 Paimon 的「返回 bool」不同，照规范 | 1、4 |
| `POST /lance/v1/table/{id}/declare` | **登记意图**：分配/接受 location，返回凭据 | 不写存储；位置生成规则见下 | 1、5、14、13 |
| `POST /lance/v1/table/{id}/register` | 登记已有数据集 | 弱校验 `_versions/*.manifest` 存在 | 1、5、14、13 |
| `POST /lance/v1/table/{id}/deregister` | **只删登记**，数据留着 | 返回 `location` + `properties`（规范要求），便于调用方自己清理 | 1、4 |
| `POST /lance/v1/table/{id}/rename` | 同一 catalog 内改名 / 换 schema | 跨 catalog 一律 400（我们的 rename 是 catalog 内操作） | 1、4、5、14 |

四个必须写清的口径：

**a. `declare` 的位置生成规则。** 三档，按顺序判：

1. 请求体给了 `location` → 用它（但要过 `allowedLocations` 白名单，越界 400）；
2. 没给 → 用 `<warehouse>/<schema 目录>/<table 名>.lance`（与 Iceberg 面的 `path` 生成共用同一个模板函数）；
3. 都没有（catalog 无 warehouse）→ 400，文案要求显式给 `location`。

**b. `describe` 的三个 query 参数。**

| 参数 | 默认 | 我们的行为 |
| --- | --- | --- |
| `load_detailed_metadata` | `false` | `false`：只回 `location`/`properties`/`storage_options`。`true`：**P1 已打开**——读最新 manifest 补 `schema`/`version`/`stats`/`table`/`namespace`；只登记过的表（还没有 manifest）回 `409 + code 19`，manifest 读不出则**降级**（这几项不给，但 `location` 照回） |
| `check_declared` | `false` | `true`：探测 `_versions/` 是否存在，填 `is_only_declared`；一次 LIST，成本可接受 |
| `with_table_uri` | `false` | `true`：把 `location` 规范化成合法 URI 填进 `table_uri`（`file:` 补 scheme、`s3://` 原样） |

`load_detailed_metadata=true` 在 P0 回 406 必须有**明确的文案**，告诉调用方「传 `false` 或省略」。因为这是客户端最可能踩的一脚（§8 风险 1）。

**c. `is_only_declared` 的三态。** `true` = 登记了但存储里没有数据集；`false` = 有；`null` = 没检查。默认返回 `null`（不探测），只有 `check_declared=true` 时才给真值。三态不能拍平成两态——「不知道」和「没有」对客户端是不同的决策。

**d. `register` 与 `declare` 的 `mode`。** `create`（默认，已存在 409）/ `overwrite`（覆盖登记，**不动数据**）。`overwrite` 不删旧数据这一点同 §5.1（2）。

### 5.3 分页、错误、幂等三件套

| 项 | 定案 | 理由 |
| --- | --- | --- |
| 分页 | `limit` 默认 100、上限 1000；`page_token` 是**不透明字符串**（base64 的 `{排序键, 偏移, 查询指纹}`） | 与 `table-data-preview-design.md` 的无状态游标同构；带查询指纹是为了「换了前缀还拿旧 token」时不出错位结果 |
| 错误 | 全部走 `ErrorResponse` 四字段；`instance` 填请求路径 | 客户端的 OpenAPI 生成代码按字段名反序列化，多一个字段就报错 |
| 幂等 | `create` 不幂等（409）、`exist_ok` 幂等、`declare` 幂等（同 location 重复调用返回同一结果）、`deregister` 幂等（第二次 404） | 客户端重试策略是按操作语义定的，不能一刀切 |

---

## 6. 需求清单（能做的）

### 6.1 P0：可用的元数据面（13 个 operation）

| 编号 | 需求 | 验收标准 | 依赖 |
| --- | --- | --- | --- |
| L0-1 | 规范副本与端点清单 | `spec/lance-namespace-rest-openapi.yaml` 入库；`scripts/verify-lance-endpoints.py` 能算出「48 个 operation / 实现 13 / 显式 406 30 / 预留 5」，与代码不一致即红 | 能下载到 spec（当前网络受限，见 §9） |
| L0-2 | `CatalogFormat.LANCE` + 建 catalog 校验 | 管理 API 可建 `catalogFormat=LANCE`；对 LANCE catalog 走 `/v1/{prefix}` 的请求回「表不存在」；反向亦然 | 无 |
| L0-3 | 路由与 id 解析 | `/lance/v1/**` 通配；`delimiter` 可覆盖；`%24` 正确解码；段数/空段/`%2F`/body-路径不一致 各自有单测（`LanceIdParsingTests`） | L0-2 |
| L0-4 | namespace 5 个端点 | 与 `lance` Python 客户端手工对照：建 schema、列、描述、存在性、非空拒删；`Cascade` 得 406 | L0-3 |
| L0-5 | table 8 个端点 | `declare`→（客户端写数据集）→`register` 往返；`describe` 三种参数组合各自正确；`deregister` 后数据仍在；`rename` 跨 catalog 被拒 | L0-4 |
| L0-6 | 错误模型 | 每个 `code` 到 HTTP 的映射逐条有测试（`LanceErrorModelTests`）；`/lance/**` 下 404/405/校验失败/未捕获异常都返回四字段 | L0-3 |
| L0-7 | 授权 | `/lance/**` 进认证拦截器；`LanceAccessRules` 覆盖 13 个端点；未登记即 403；`LanceEndpointAuthorizationTests` 全绿 | L0-5 |
| L0-8 | 凭据下发 | `vend_credentials=true` 时 `storage_options` 含临时凭据与 `expires_at_millis`；`false` 时不含任何密钥；`ttlSeconds` 与 `expires_at_millis` 由同一个值算出 | L0-5 |
| L0-9 | 控制台只读 | catalog 列表出现 `LANCE` 徽标；表详情只显示「详细信息 / 文件列表 / 血缘」；无建表入口；`verify-console` 白名单同步 | L0-5 |
| L0-10 | 端到端 | `scripts/e2e-lance-rest.sh`：起服务端 → 建 LANCE catalog → 用真客户端建 schema → `declare` → 客户端写一个最小数据集 → `describe` 能读回 location → `deregister` → 数据仍在 | L0-9 |

### 6.2 P1：把「表长什么样」补上一半（**已落地**）

| 编号 | 需求 | 验收标准 | 状态 / 依据 |
| --- | --- | --- | --- |
| L1-1 | `load_detailed_metadata=true` | 返回 `schema`（JSON Arrow）、`version`、`table`、`namespace`；`stats` 可空 | ✅ 已实现；只登记过的表回 `409 + code 19`，manifest 读不出则降级 |
| L1-2 | manifest 读取器 | 纯 Java protobuf 解析 `_versions/` 最新 manifest：支持 V1（`{version}.manifest`）与 V2（`u64::MAX-version` 补零 20 位）两种命名 | ✅ `LanceManifest` + `LanceVersionNaming`；**没有**用 `latest_version_hint.json`（§12.7） |
| L1-3 | `ListTableVersions` / `DescribeTableVersion` | 列版本号与时间；`descending` / `limit` / `page_token` 生效；`GET` 与 `POST` 两种方法都接 | ✅ 已实现；版本来自**存储与库两个来源的并集**（§12.7） |
| L1-4 | 版本缓存 | `table_version` 列只做展示缓存，**不参与任何判定**；manifest 读失败时降级为「不回 schema」并记 `warn` | ✅ 已实现；缓存写失败只记 debug，不让读失败 |

**P1 的风险要在开工前量化**：manifest 是 Lance 格式的核心结构，字段随版本增长（`reader_feature_flags` 已到 `1<<16`）。我们只解 `fields` / `version` / `timestamp`，其余**不认识的字段一律忽略**（protobuf 的天然特性）。但「忽略」意味着我们读到的 schema 可能不完整（例如 `fields` 与 `covering_fields` 分离声明的那一版）——所以 P1 的验收里要有一条：**用当前 Lance 版本写出的表能读对，且读不出时明确降级而不是给错**。

### 6.3 P2：需要单独评审的两项（**L2-1 部分落地、L2-2 的标签已落地**）

| 编号 | 需求 | 为什么单独评审 | 状态 |
| --- | --- | --- | --- |
| L2-1 | `managed_versioning=true` + 3 个版本写端点 | 把 catalog 变成提交协调者（§3.4），需要唯一约束 + CAS + 暂存 manifest 可见性；与「服务端不写仓库」的边界冲突 | **做了 2 个**：`CreateTableVersion` + `BatchDeleteTableVersions`，唯一约束 `(table_id, version)` 就是那个 CAS；`BatchCreateTableVersions` **不做**（§12.6） |
| L2-2a | 标签的**只读**代理（`ListTableTags` / `GetTableTagVersion`） | 读 `_refs/tags/*.json` 是纯读，但会让人以为我们管这些对象；要先把「只读代理」与「登记」的措辞定清 | ✅ **已做**：盘上格式规范写死了（`_refs/tags/{name}.json`，字段 `branch` / `version` / `manifestSize`），因此读得起。措辞定为「代理」：不落库、不校验版本存在、写端点仍 406（§12.8） |
| L2-2b | 索引的**只读**代理（`ListTableIndices` / `DescribeTableIndexStats`） | 同上 | **仍未做**：规范对 `_indices/{UUID}/` 只写了一句「different for each index type」，连文件名都不定；`IndexContent` 要的 `index_name` / `columns` / `status` 只能靠解析具体索引文件，凭目录名编字段就是「假装成功」 |

### 6.4 明确不做（写进 README，避免被当成待办）

- `CreateTable` / `DropTable` / `RestoreTable`（数据操作）
- 全部数据面：`query` / `insert` / `merge-insert` / `update` / `delete` / `count-rows` / `explain` / `analyze` / `refresh` / `stats`
- 列演进 4 个 + `UpdateTableSchemaMetadata`
- 索引 5 个、标签 5 个、事务 2 个
- 递归删除（`DropNamespace` 的 `Cascade`）
- 通过 Lance API 创建 catalog（默认关闭，见 D2）

**一个下游能力**：Lance 表的 compaction / 版本清理（Spark `OPTIMIZE` / `VACUUM`）单独成文
——[`lance-table-optimization-design.md`](lance-table-optimization-design.md)。它复用现有的存储优化链路
（0 张新表、0 个新端点），但**以本文的 L0-5 为前置**：`describe` 拿不到 `location` + `storage_options`，
优化任务的 Spark 会话就建不起来。反过来它对本文有两个反馈：L0-5 的 `describe` 会被**除客户端以外**的
第二个调用方使用（优化任务的每次巡检），以及 L1-3 的 `ListTableVersions` 会用来做「清理前预览」。

---

## 7. 控制台与事件

| 位置 | 改动 | 理由 |
| --- | --- | --- |
| catalog 列表 / 详情 | `catalog_format` 徽标加 `LANCE`；「协议与能力」卡片显示基址 `/lance` 与「21 条端点 / 19 个 operation 已实现、其余 35 个不支持」 | 与 Iceberg catalog 的卡片同形（`console-catalog-detail.md` §3.1.1） |
| 表详情 | 只显示「详细信息 / 文件列表 / 血缘」三个页签；**不显示**快照、数据预览、数据策略。启用存储优化后再加第四个「存储优化」页签（见 [`lance-table-optimization-design.md`](lance-table-optimization-design.md) §7.5） | Lance 表没有我们的快照概念；预览需要 Lance reader（§3.3）；行过滤在 Lance 侧无落点 |
| 建表表单 | **不给 Lance 入口**（与 `lance-table` 的处理一致） | 建表要写数据集，我们没有写路径 |
| 文件列表 | 复用现有的对象存储浏览（`_versions/`、`data/` 直接可见） | 这是唯一能看到「表里到底有什么」的地方，成本为零 |
| 事件 | 复用现有表生命周期事件，payload 增加 `format=LANCE`；**不新增事件类型** | 事件消费者是按 `category/type` 订阅的，新增类型会让所有 listener 漏事件 |

---

## 8. 风险与未决

| # | 风险 | 影响 | 应对 |
| --- | --- | --- | --- |
| 1 | **真客户端可能默认带 `load_detailed_metadata=true`** | P0 的「只回 location」会被真实客户端打穿，用户看到 406 就以为不可用 | P0 第一步抓包（Python + Spark 两条链路）；若命中，**P1 提前到 P0**，这是本方案最可能变的排期 |
| 2 | Spark 连接器可能直接调 `drop_table` / `create_table` | 我们在 406 上被当成"服务坏了" | 抓包确认；文档给出「用 `DeregisterTable` + 自行清理」的替代；必要时提供「`drop` 只删登记」的兼容开关（默认关） |
| 3 | `delimiter` 非 `$` 时路径解析 | 客户端配 `::` 后全部 404 | 解析器按 `delimiter` 切分而不是硬编码；单测覆盖 `$` / `::` / `__delim__` 三种 |
| 4 | 第一级 namespace 创建 = 建 catalog，绕过存储配置 | 越权建仓库、`allowedLocations` 失效 | 默认关（D2）；开启时强制要求 `properties.location` 且落在服务端白名单 |
| 5 | manifest 格式漂移（P1） | 读出的 schema 字段缺失或类型错 | 只解必要字段、未知字段忽略、读不出即降级；每条新 Lance 版本加一次回归表 |
| 6 | 与通用表争同一份目录（§2） | 两个面登记同一张表，谁改谁不知道 | 建议互斥（D6），至少在控制台标「已由 X 面登记」 |
| 7 | `expires_at_millis` 与 `ttlSeconds` 不同源 | 客户端在凭据过期后仍尝试用，报 403 且难排查 | 同一个值算出两个字段 + 单测绑定 |
| 8 | `/lance/**` 漏进认证拦截器 | 整个面匿名可调 | 用 `LanceEndpointAuthorizationTests` 钉死；`ApiDocEndpointTests` 同步 |

**要拍板的六件事**：

| # | 决策 | 建议 | 备选 |
| --- | --- | --- | --- |
| D1 | 基址形态 | A：`/lance` + catalog 作第一级 | B（per-catalog 基址）/ C（单级 namespace） |
| D2 | 是否允许通过 Lance API 建 catalog | **否**，406 + 指向管理 API | 允许，但强制 `properties.location` |
| D3 | `DropNamespace` 是否支持 `Cascade` | **否**，406 | 支持，但要求 `?confirm=true` 且只删登记 |
| D4 | `managed_versioning` | **做成表级开关，默认 `false`**（已落地）：`false` 时与「恒否」行为完全一致，`true` 才开放版本写端点。见 §6.3 与 §12.6 | 常量 `false`——已否决，因为那样 `CreateTableVersion` 永远不可用 |
| D5 | P1 的 manifest 读取器 | **做**，但只在抓包证明必需时才排期 | 不做，`load_detailed_metadata=true` 永久 406 |
| D6 | Lance 表与通用表是否允许登记同一目录 | **不允许**（后者拒绝登记已存在的 Lance 表） | 允许，靠文档说明 |

---

## 9. 机械校验与验收

| 脚本 / 测试 | 钉住什么 | 何时跑 |
| --- | --- | --- |
| `scripts/verify-lance-endpoints.py`（已落地，13 项） | 规范副本的 operation 数（54）与控制器台账（21 条端点 / 19 个 operation）逐条对齐；`WebConfig` 里 `/lance/v1/**` 必须登记两次；`BatchCreateTableVersions` 必须在「不做」一栏 | 改端点、改 spec 副本时 |
| `LanceEndpointAuthorizationTests`（新增） | 各端点的 access 映射；未登记即 403；`/lance/**` 不被匿名访问；版本端点（读 / 写）同样要判权 | 改授权时 |
| `LanceVersionApiTests`（新增，10 项） | P1 的读两个 + P2 的写两个：详细元数据、版本列举 / 描述、`put_if_not_exists` 的 409、范围删除、deregister 只删账 | 改版本端点时 |
| `LanceTagApiTests`（新增，9 项） | 标签只读代理：空 map ≠ 404、`manifestSize` 是驼峰、map 形状、分页、坏标签「列举跳过 / 指名 409」、标签名路径穿越被拒、写标签仍 406 | 改标签端点时 |
| `LanceManifestReaderTests`（新增，8 项） | protobuf 解析（版本 / 时间戳 / 嵌套 schema / 未知字段跳过）、JSON Arrow 映射、V1 / V2 命名的往返 | 改解析器时 |
| `LanceErrorModelTests`（新增） | 22 个 code → HTTP 的映射；`/lance/**` 下 404/405/校验/未捕获异常的形状 | 改错误处理时 |
| `LanceIdParsingTests`（新增） | §4.2 的五条规则 + 三种分隔符 | 改解析器时 |
| `scripts/e2e-lance-rest.sh`（新增） | 真服务端 + 真客户端：declare → 写数据集 → describe → deregister | 发版前 |
| `verify-console.py` / `verify-k8s-manifests.py` / `verify-iceberg-endpoints.py` / `verify-lineage-doc.py` / `verify-spark-sql-doc.py`（既有 5 个） | 不受影响，但新增控制台白名单后总数要同步 | 每次改动 |

**前置阻塞已解除**：`spec/lance-namespace-rest-openapi.yaml` 已入库（2026-10-28 抓自
`lance-format/lance-namespace@main` 的 `docs/src/spec.yaml`，228 KB）。L0-1 因此完成，
端点覆盖率**可以机械校验**了——`verify-lance-endpoints.py` 现在比对的是这份副本而不是本文的清单。

---

## 10. 里程碑

| 阶段 | 内容 | 出口条件 |
| --- | --- | --- |
| **M0 抓包** | Python `lance-namespace` + Spark `LanceNamespaceSparkCatalog` 对 Gravitino（或本地 stub）跑一遍，记录全部请求：路径、query、header、body | 得到「真客户端实际调用集合」；回答风险 1 / 2；决定 P1 是否提前 |
| **M1 只读面** | 13 个端点里先做 6 个读操作（list/describe/exists × 2 层）+ 错误模型 + 授权 | 客户端能「看见」表；写操作还不可用 |
| **M2 写登记** | `create` / `drop(restrict)` / `declare` / `register` / `deregister` / `rename` + 凭据 | `e2e-lance-rest.sh` 全绿 |
| **M3 文档与控制台** | 本文档回填实测偏差、README / AGENTS 文档索引、控制台三页签、事件 payload | 五个既有校验脚本 + 两个新脚本全 PASS |
| **M4 P1/P2** | manifest 读取器（按 M0 结论）、版本只读；P2 两项另行评审 | **已完成**：P1 两项 + P2 的 `CreateTableVersion` / `BatchDeleteTableVersions`；`BatchCreateTableVersions` 与标签 / 索引只读**未做**（§12.6） |

---

## 11. 术语

| 词 | 含义 | 别混 |
| --- | --- | --- |
| Lance Namespace | 抽象的目录接口（客户端侧） | 不是我们的 namespace |
| Lance REST Catalog | 该接口的 HTTP 实现（我们做的这一层） | 不是 Paimon 的 REST Catalog |
| `id` | 用 `delimiter` 拼起来的多级标识，`cat$schema$table` | 不是单个表名 |
| `delimiter` | 拼 `id` 的分隔符，默认 `$`，URL 里编码为 `%24` | 不是路径分隔符 |
| `location` | 数据集根目录（`s3://…/x.lance`） | 与 `table_uri` 不同：后者必须是完整合法 URI |
| manifest | `_versions/` 下的 protobuf，一个版本一份，含 schema 与 fragments | 不是 Iceberg 的 `metadata.json` |
| `managed_versioning` | 版本由 catalog 记账（`true`）还是 Lance 原生（`false`） | 是**表级开关**，默认 `false`；不是常量，别在代码里写死 |
| declared-only | 登记了但存储里还没有数据集 | 不等于「空表」（空表也有 manifest） |

---

## 12. 实测偏差（落地后回填）

P0 落地时与本文口径不一致、或本文没写到但实现必须决定的地方，都记在这里。
**后面的项目若要改这一面，先读这一节**——它的信息密度比上面任何一节都高。

### 12.1 已落地 / 未落地

| 项 | 状态 |
| --- | --- |
| P0 的 13 个 operation | 已实现（`LanceNamespaceController` + `LanceTableController`） |
| P1 的 2 个（版本只读） | 已实现（`LanceVersionController` + `LanceVersionService` 的读路径） |
| P2 的 3 个版本写端点 | 做了 2 个（`CreateTableVersion` / `BatchDeleteTableVersions`）；`BatchCreateTableVersions` **不做**（§12.6） |
| P2 的标签只读 2 个 | 已实现（`LanceTagController` + `LanceTagService` + `LanceTagFiles`）；索引只读**不做**（§12.8） |
| 其余 35 个 operation | `LanceUnsupportedController`（最低优先级兜底）恒回 **406 + code 0** |
| 控制台只读（§7） | 未做；只把 `LANCE` 加进了 `catalogFormat.js`（徽标与新建表单的取值集合），目录浏览仍是 Paimon 那一套 |
| `properties_json` / `table_version` / `managed_versioning` | 三列都已加（`lakehouse_table`） |
| `lakehouse_lance_table_version`（版本账） | 已加；唯一约束 `(table_id, version)` |

### 12.2 四处「静默放行」——都是设计稿没写、实现里才撞上的

1. **`/lance/**` 不在 `/v1/**` 之下。** `BearerAuthInterceptor` 与 `AuthorizationInterceptor`
   原本各只登记 `/v1/**`，漏任何一处 = 整个面不认证 / 不授权，**且既有测试一条都不红**。
   是 `LanceEndpointAuthorizationTests` 把它抓出来的（匿名请求拿到 200）。
2. **`getRequestURI()` 给的是编码后的串。** catalog 名会变成 `cat%24sales`，而不存在的
   catalog 在授权判定里是「跳过」而不是「拒绝」——于是又一个静默放行。授权层解析 id
   必须逐段 `URLDecoder.decode`。
3. **未登记的 Lance 路径要回退到 `CATALOG:DESCRIBE` 而不是返回 `null`。** 返回 `null` 会让
   拦截器抛 403，于是「这个 operation 不支持」变成「你没权限」，406 那条语义就没了。
4. **兜底控制器的优先级必须最低。** `/lance/v1/**` 与具体端点同路径前缀，用
   `@Order` 之外的手段（映射优先级的天然顺序）才能保证不被它吃掉；否则 13 个端点全变 406。

### 12.3 三处与本文写法不同

- **`is_only_declared` 是三态**（`true` / `false` / `null`），`null` = 这次没做存在性检查
  （没带 `check_declared` 或存储不可达）。它是「has dataset」的**取反**，第一次实现时
  写反了方向。
- **`declare` 不落任何文件，`register` 只做弱检查**（探 `_versions/1.manifest` 与
  `_versions/latest_version_hint.json`），探不到不报错——存储不可达时是 `null` 而不是失败。
- **`LanceException` 刻意不继承 `ApiException`**：两者「真值字段」不同（Lance 是 `code` 决定
  状态码），继承下去会让 `GlobalExceptionHandler` 的那条分派链多一个要猜的分支。

### 12.4 测试里两个只有 MockMvc 才有的坑

- `MockMvcRequestBuilders.post(String)` 会把 `%24` 再编码成 `%2524`；构造请求必须走
  `post(URI.create(path))`。
- 表 id 里的 `delimiter` 缺省时不能是 `null`，否则 `join()` NPE——`LanceIds.Id.join()`
  现在对 null / 空串回落到默认 `$`。

### 12.5 规范副本是 54 个 operation，不是 48

副本落在 `spec/lance-namespace-rest-openapi.yaml`（228 KB，OpenAPI **3.1.1**，operation 版本 `V1`），
取的是 jsDelivr 的 `lance-format/lance-namespace@main`——`raw.githubusercontent.com` 直连超时。
入库是为了让校验脚本能**数**出条数，而不是靠本文这张表记住它。

多出来的 6 个（写作时还没进规范）：

| 路径 | 来源 |
| --- | --- |
| `/v1/table/{id}/branches/list` · `create` · `delete` | 分支（Git 式多版本线），同一套模型 |
| `/v1/materialized_view/{id}/create` · `refresh` | 物化视图 |
| `/v1/table/{id}/update_field_metadata` | 字段描述改写的独立路径 |

三条全落在「恒不做」那一栏，于是**「恒 406」的实际条数是 37**（54 − 17 已实现），
不是 §0 里写的 30 / 35。`verify-lance-endpoints.py` 直接解析 spec 数路径，
所以以后规范再涨条数，脚本会先红，本文这张表反而可以慢一步改。

### 12.6 `BatchCreateTableVersions` 为什么不做

路径是 `/v1/table/version/batch-create`——**不带 `{id}`**。表标识只出现在 body 的 `versions[]`
里，而且每个元素可以指向不同 catalog 下的不同表。

我们的授权在拦截器里做，只看路径：§1.2 抄的就是规范自己那条原则（「反代需要的信息必须不用
反序列化 body 就能拿到」）。要判权就得先读 body，等于为了这一个端点把原则破掉；更麻烦的是
一次请求会横跨多张表，语义从「成功 / 失败」变成「部分成功」，partial failure 的错误模型规范
里没有定义。

于是它留在兜底控制器：**406 + code 0（Unsupported）**，语义是对的，而且
`verify-lance-endpoints.py` 断言它始终在不支持清单里。真要做，得等规范把路径改成
`/v1/table/{id}/version/batch-create`——那样它就是 `CreateTableVersion` 的循环，没什么额外成本。

另外两个版本写端点不受影响：`CreateTableVersion` / `BatchDeleteTableVersions` 都带 `{id}`，
按「表级 ALTER」判权，已在 `LanceAccessRules` 登记。

### 12.7 版本发现：不读 `latest_version_hint.json`

规范里 `latest_version_hint.json` 明说是 **hint**：写漏、写旧都不算错，旧版 Lance 根本不写。
拿它当「最新版本号」会得到比实际小的答案，而且错得很安静。

所以实现只做一件事：**LIST `_versions/` 一次，按 version 排序取最大**。hint 文件只在 `register`
的弱存在性检查里被当作「这里像个数据集」的信号之一（与 `_versions/1.manifest` 二选一即可），
**不参与版本发现**。

命名有两套，混用是错的：

| 套 | 名字 | 为什么 |
| --- | --- | --- |
| V1 | `{version}.manifest` | 直白 |
| V2 | `{u64::MAX - version}` 补零 **20 位** | 字典序 = 版本倒序，scan 时最新在前 |

换算不用 BigInteger：`u64::MAX` 的补码就是 `-1`，所以 `version = -1 - name`。
但打印必须走 `Long.toUnsignedString(-1L - version)` 再补零——`String.format("%020d", …)`
会先打出一个负号，长度还对，看着完全正常，实际永远匹配不上。这条踩过一次。
同一目录里两种命名同时出现 → `InvalidTableState`（code 19 / 409），**不猜**。

版本有两个来源，`list` 取并集按 version 去重，**库里那条赢**：

1. 存储 LIST 出来的（原生与 managed 都会有）；
2. `lakehouse_lance_table_version` 里记的（只有 `managed_versioning=true` 才可能有），
   它额外带 `metadata` / `manifest_size` / `e_tag`。

排序一律按 version 数值，不受 V2 命名倒序影响。

`lakehouse_table.table_version` 只是最近一次读到的版本号，**纯展示缓存**：写失败只 `log.debug`，
绝不参与「表是否存在」「这是不是最新版本」的任何判定——它过期了最多让控制台少显示一个数字。

### 12.8 标签读得起、索引读不起：区别是「规范写没写」，不是难易

L2-2 的两半在同一份规范里待遇不同，值得单独记一笔，因为它决定了以后「能不能再加一个只读端点」：

| | 标签 | 索引 |
| --- | --- | --- |
| 位置 | `_refs/tags/{name}.json`（**永远在根级**，不论指向哪个分支） | `_indices/{UUID}/…` |
| 规范写了什么 | 文件名、扩展名、每个字段（`branch` / `version` / `manifestSize` / `createdAt` / `updatedAt` / `metadata`） | 一句「different for each index type」，连文件名都不定 |
| 结论 | **读得起** | 读不起：`IndexContent` 要 `index_name` / `columns` / `status`，只能靠解析具体索引文件 |

判据是这一条：**只读代理需要的字段是否都能从盘上唯一地算出来**。算得出就做，算不出就 406——
按目录名编字段的响应会让调用方以为索引是健康的。

四个写错过一次才会知道的点：

1. **`manifestSize` 是驼峰**，规范里唯一一处没跟 snake_case（`TableVersion` 用的是
   `manifest_size`）。写错的后果不是编译失败，是客户端读到一个 `null`，
   看上去像「这个标签没有 size」。
2. **`tags` 是 map 不是数组**（名字 → 内容）。按数组实现，客户端会拿不到任何标签。
3. **没有 `_refs/tags/` 是空 map，不是 404**。空 map 说「这里确实没有标签」；
   只有指着某个具体标签问不到，才是 `TableTagNotFound`（code 8 / 404）。
4. **坏标签的两种处置不一样**：`tags/list` 跳过并 `warn`（一个坏标签不该把其余标签一起藏起来），
   `tags/version` 回 `InvalidTableState`（19 / 409）。那次没有「跳过」这个选项，
   说成「不存在」就是撒谎——文件在那儿，只是读不懂。

还有一条只在授权层出现的坑：**`/table/{id}/tags/list` 的最后一段是 `list`，不是 `tags`**。
规则若只看最后一段，它会落进表级 switch 的 `default`（那里没有 `list` 这个分支），
于是判权退化成 `CATALOG:DESCRIBE`——「看得见 catalog 就能读这张表的标签」。
判断必须看**倒数第二段**（`LanceAccessRules` 里 tags 与 version 两个分支都是这个形状）。

还有一处**性能**上的决定：`tags/list` 必须**一次 FileIO 读完整个目录**，不能逐个读。
`StorageFileAccess` 刻意不缓存 FileIO（缓存凭据会导致「管理 API 换过钥匙后服务端还在用旧的」），
因此每读一个文件就重建一次 FileIO 并重新解析凭据——在对象存储上那是几百毫秒一次。
一百个标签就是一百次。为此 storage 侧加了 `readTexts(catalogId, paths)`，
它的契约是「同一存储的一批小文件共用一次 FileIO，不存在的跳过」。

---

## 附：参考

- Lance Namespace 规范总览：<https://lance.org/format/namespace/>
- Lance REST Catalog 路由与非标准操作：<https://lance.org/format/catalog/rest/>
- 存储布局与 manifest 命名：<https://lance.org/format/table/layout/>、<https://lance.org/format/table/transaction/>
- 标签与分支的盘上格式（`_refs/tags/*.json` 的字段）：<https://lance.org/format/table/branch_tag/>
- 错误码与 `ErrorResponse`：<https://lance.org/format/namespace/operations/errors/>
- 参考实现：<https://gravitino.apache.org/docs/1.3.1/lance-rest-service>
- 相关设计文档：[`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md)（双协议面的做法与代价）、[`generic-table-design.md`](generic-table-design.md)（信封式的另一条路）、[`data-model.md`](data-model.md)、[`authorization.md`](authorization.md)、[`console-catalog-detail.md`](console-catalog-detail.md)
