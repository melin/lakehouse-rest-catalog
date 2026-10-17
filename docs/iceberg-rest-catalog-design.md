# 同时服务 Paimon 与 Iceberg：REST Catalog 双协议方案

本服务端现在是 **Apache Paimon 的 REST Catalog**（60 个端点、144 个 schema，
见 [`catalog-api-endpoints.md`](catalog-api-endpoints.md)）。本文件给出让它
**同一个基址、同一份元数据、同时服务 Iceberg REST Catalog** 的方案——
不做代码，只做定案与取舍。

动因很实在：中国用 Paimon 多、国外用 Iceberg 广，同一个湖仓团队常常两边都要接；
把「谁的数据在哪」分给两套服务，等于把权限、审计、优化、控制台各做两遍。
Iceberg 侧的能力清单以 Apache Iceberg 官方规格为准
（本次核对基于 2026-10 拉取的 `open-api/rest-catalog-open-api.yaml`，共 **35 个端点**，
`TableMetadata` / `TableUpdate` / `TableRequirement` 等 256 个 schema 条目；
副本已放进 [`../spec/rest-catalog-open-api-iceberg.yaml`](../spec/rest-catalog-open-api-iceberg.yaml)，
实现时以它做端点覆盖率的机械校验）。

用户的两个原则是本方案的两条主线，下文按它们组织：

1. **在创建 catalog 时就区分 Paimon 还是 Iceberg**；
2. **数据库表尽量统一**。

---

## 1. 结论

| 问题 | 定案 |
| --- | --- |
| 要不要拆成两个服务 | **不拆**。一个进程、一个 `/v1` 基址、一套元数据库 |
| 怎么区分 | `paimon_catalog` 新增**格式判别列** `catalog_format`（`PAIMON` / `ICEBERG`），创建时必选、**不可变** |
| 数据表怎么统一 | **加 9 个列、0 张新表**。22 张表全部复用（§4.1 逐表列出） |
| 协议怎么分派 | 路径 → `{prefix}` → catalog → `catalog_format` → 协议处理链。两套规格的路径**几乎不重叠**，只有 2 条真冲突（§2.1） |
| 元数据真相源 | DB 存整份格式原生文档（Iceberg：`TableMetadata` JSON；Paimon：schema 文档 + 快照行），仓库里**同步物化**成引擎能读的文件 |
| Iceberg 侧要不要引 `iceberg-core` | **要**。`TableUpdate` 有 25 种、`TableRequirement` 有 8 种，手写等于重写上游语义（§5.4） |
| 一期做到哪 | namespace / table / view / register / rename / metrics / credentials / oauth 全通，**Spark 能建表、能写、能读**；多表事务与异步扫描规划不做但要**显式声明不支持**（§6） |

---

## 2. 为什么一个 `/v1` 能同时挂两套协议

### 2.1 路径层面的实测比对

把两份规格的路径逐条对齐（Paimon 60 条、Iceberg 35 条）后的结果：

| 类别 | 条数 | 说明 |
| --- | --- | --- |
| **完全同路径** | 1 | `GET /v1/config`（两边的响应结构本身兼容，见 2.3） |
| **语义同路径但请求体不同** | 2 | `POST /v1/{prefix}/tables/rename`、`POST /v1/{prefix}/views/rename` |
| Paimon 独有 | 57 | `databases/**`、`table-details`、`commit`、`rollback(-schema)`、`snapshot(s)`、`token` / `auth`、`partitions/**`、`branches/**`、`tags/**`、`consumers/**`、`view-details`、`function*` 全量 CRUD、`semantic-views/**` |
| Iceberg 独有 | 32 | `namespaces/**`、`namespaces/{ns}/properties`、`register` / `register-view` / `unregister`、`credentials`、`sign`、`metrics`、`plan` / `tasks`、`transactions/commit`、`views/**`（Iceberg 形态）、`functions` 只读 |

关键在于**两个规格选的资源名词不同**：Paimon 用 `databases`，Iceberg 用
`namespaces`。于是 `/v1/{prefix}/databases/...` 与 `/v1/{prefix}/namespaces/...`
天然各走各的，一个 `@RequestMapping` 树里放两套控制器不会互相抢路由。

真正要动手的只有三处：

**（1）两条 `rename` 的同路径分歧。** 两边的请求体是同一个形状、
不同字段名——这是能判开的，而且判据是现成的：

| | Paimon | Iceberg |
| --- | --- | --- |
| 请求体 | `{source: Identifier{database, object}, destination: Identifier}` | `{source: TableIdentifier{namespace:[…], name}, destination: …}` |
| 谁的路径 | `/v1/{prefix}/tables/rename`、`/v1/{prefix}/views/rename` | `/v1/{prefix}/tables/rename`、`/v1/{prefix}/views/rename` |

解法有两层，都要用上：**主判据是 prefix 指向的 catalog 格式**
（一个 catalog 只有一种格式，见 §3），**兜底再按请求体字段名分流**
（出现 `object`/`database` → Paimon，出现 `namespace`/`name` → Iceberg）。
第二层不是冗余：`paimon.rest.auto-create-catalog` 开着时，一个敲错 prefix 的
客户端会先自动建出一个 catalog，此时唯一的线头就是请求体本身。

**（2）`/v1/config` 要按格式下发不同内容。** 见 §3.3。

**（3）`POST /v1/oauth/tokens`。** Iceberg 把它放在 `/v1` 下且**不带 prefix**
（它在客户端拿到 catalog 配置之前就要用）。本服务端已经有一个同语义的扩展端点
`POST /api/catalog/v1/oauth/tokens`（Polaris 对齐，见
[`console-auth.md`](console-auth.md)），需要再补一条 `/v1/oauth/tokens`
的别名路由，并把它加进 `CatalogAccessRules.isPublic` 的豁免名单——
它与 `GET /v1/config` 同类：**认证凭据还没换到，就必须可达**。

### 2.2 分派键：prefix

两套客户端的行为是一致的：先 `GET /v1/config` 拿到 `prefix`，
之后所有资源路径都以它开头。Paimon 与 Iceberg 都这么干，因此
**prefix 是天然的、也是唯一需要的分派键**：

```
请求 /v1/{prefix}/namespaces/sales/tables/orders
        │
        ├─ CatalogService.resolve(prefix) ─→ CatalogEntity
        │       catalog_format = ICEBERG
        │
        └─ IcebergProtocolHandler  ←── 按格式选
                └─ IcebergTableService.load(namespace=sales, table=orders)
```

实现上落成一个**格式枚举 + 策略接口**，而不是在控制器里写 `if`：

- `CatalogFormat { PAIMON, ICEBERG }`，挂在 `CatalogEntity` 上；
- `CatalogProtocol`（`paimon` / `iceberg` 两个实现）提供 `config(...)`、
  `namespaceOf(...)`、`tableOf(...)` 等同名能力；
- 控制器树两棵：现有 `web/*Controller`（Paimon，不动）+
  新增 `web/iceberg/*Controller`（Iceberg）；
- 两条树共用的只有 `CatalogService` / `AuthorizationService` /
  `StorageCredentialManager` / `TableMetadataService`（物化）四处。

### 2.3 三处必须逐字节对齐的差异

**错误模型。** 两边不一样，而且不能只挑一种：

| | 形状 |
| --- | --- |
| Paimon（现实现，`GlobalExceptionHandler`） | `{message, resourceType, resourceName, code}`（扁平） |
| Iceberg | `{error: {message, type, code, stack}}`（嵌套一层） |

Spark 的 Iceberg 客户端会把响应体反序列化成 `ErrorResponse`，拿不到
`error` 字段就报不出原因。解法：`GlobalExceptionHandler` 增加一条
**按请求所属 catalog 格式选择响应体**的分支（Iceberg 请求走嵌套形状，
其余保持现状），`ApiException` 的 `status` 映射到 Iceberg 的 `code`
（400/401/403/404/409/422/500 一一对应）。

**`endpoints` 能力声明（一定要发，不能省）。** Iceberg 规格写明：服务端若不返回
`endpoints`，客户端会**假定一整套默认端点存在**，而那份默认清单里**包含
`POST /v1/{prefix}/transactions/commit`**（多表事务）。不发声明，就等于承诺了
一个我们不做的东西；等到客户端真的发起多表提交才 404，故障现场离原因很远。
因此 `/v1/config`（Iceberg 格式）必须回一份**显式的 `endpoints` 清单**，
只列一期真正实现的路径。

**namespace 的分隔符。** Iceberg 的多级 namespace 在路径里用
「namespace separator」拼接，由 `/config` 的 override `namespace-separator`
声明，**默认是 `0x1F`（URL 编码 `%1F`）**；规格另要求：服务端即便声明了别的
分隔符，**也必须同时接受 `0x1F`**（兼容旧客户端）。方案见 §4.6。

---

## 3. 需求一：创建 catalog 时区分格式

### 3.1 一个列，不是一张表

`paimon_catalog` 新增一列：

```sql
catalog_format varchar(32) NOT NULL DEFAULT 'PAIMON'   -- PAIMON | ICEBERG
```

存量数据零迁移：默认值就是现状（全部 `PAIMON`）。加列不改任何唯一约束与索引
（`uk_catalog_prefix` 仍然只看 `prefix_value`）——**一个 catalog 一种格式**，
格式不进键。

**为什么不复用已有的 `catalog_type`。** 那张表上已经有一个 `catalog_type`，
语义是**托管模式**：`INTERNAL`（元数据由本服务托管）/ `EXTERNAL`（外部仓库的只读入口），
它已经出现在管理 API、控制台的新建表单与 `GET /api/console/v1/meta` 的
`catalogTypes` 枚举里。两条正交的属性挤进一个列会立刻出问题：
`EXTERNAL + ICEBERG`（外部 Iceberg 仓库的入口）是两个列都表达不了的状态。
因此**新列新名**（`catalog_format`，管理 API 字段 `catalogFormat`），
并让它与 `catalog_type` 各自独立。

### 3.2 两个字段的组合语义

| `catalog_type` | `catalog_format` | 含义 | 一期是否支持 |
| --- | --- | --- | --- |
| INTERNAL | PAIMON | 今天的样子：元数据与服务端自己管、能写 | ✅ |
| INTERNAL | ICEBERG | Iceberg 表由本服务管元数据（写 `metadata.json`），能建能写 | ✅ |
| EXTERNAL | PAIMON | 已有 Paimon 仓库的只读入口（`register` 已有） | ✅ |
| EXTERNAL | ICEBERG | 已有 Iceberg 仓库的入口：走 `register-table` 登记、元数据以仓库里的 `metadata.json` 为准 | ⏳ 二期 |

### 3.3 创建之后的不可变，与它带来的一个坑

**格式不可变**（改格式 = 删除重建）。理由不是保守：同一个 prefix 下的表，
两种格式的元数据、物化产物、客户端可见的几个端点都不一样，
改一个列等于让所有已建对象的语义整体漂移；而且 Iceberg 的
`table-uuid` / Paimon 的 snapshot 文件都会留在仓库里，两边对不上。

不可变带来一个必须处理的地方——**自动登记**。当前 `resolve(prefix)` 在未登记
且 `paimon.rest.auto-create-catalog=true` 时会自动建一个 catalog。两格式之后，
一个 Iceberg 客户端敲了一个未登记的 prefix，会被自动建成 **Paimon** catalog，
于是它此后每一次调用都 404，且错误信息是「Unknown catalog prefix」——
client 会以为是自己的 prefix 写错了，而真正的原因是**格式不对**。两条对策：

1. 新增配置 `paimon.rest.default-catalog-format`（默认 `PAIMON`），
   让部署能选择自动登记时建哪种格式；
2. Iceberg 路径命中的 catalog 格式不匹配时，**报明确的错**：
   `404 NoSuchNamespaceError`，消息形如
   `prefix "paimon" is a PAIMON catalog; Iceberg REST paths need an ICEBERG catalog`。
   反向同理（Paimon 路径命中 Iceberg catalog）。

### 3.4 管理 API 与控制台

`POST /api/management/v1/catalogs` 与 `PUT` 增加 `catalogFormat` 字段；
`GET /api/console/v1/meta` 增加 `catalogFormats: ["PAIMON","ICEBERG"]`
（与既有 `catalogTypes` 并列），控制台的新建表单据此渲染。
控制台清单页增加一列「格式」徽标 + 一个筛选器（见效果图 §7）。

---

## 4. 需求二：数据库表尽量统一

### 4.1 复用矩阵（22 张表逐张过）

| 表 | Paimon 侧语义 | Iceberg 侧语义 | 改动 |
| --- | --- | --- | --- |
| `paimon_catalog` | catalog | catalog + **格式判别** | **+1 列** `catalog_format` |
| `paimon_database` | database | **namespace**（多级，§4.6） | **0** |
| `paimon_table` | 表（schema、路径、表格式） | 表（**location、metadata-location、table-uuid、format-version**） | **+4 列** |
| `paimon_table_schema` | schema 历史版本 | **`schemas[]` 逐个 schema-id 一行** | **0** |
| `paimon_snapshot` | 快照 + 聚合统计 | **`snapshots[]`**（manifest-list、sequence-number、parent） | **+3 列** |
| `paimon_branch` | 分支 | **`refs` 里 `type=branch`**（不含 `main`） | **0** |
| `paimon_tag` | 标签 | **`refs` 里 `type=tag`** | **0** |
| `paimon_partition` | 分区（spec + 统计 + done 标记） | 不适用（分区由 manifest 决定，REST catalog 不需要逐分区登记） | **0**（Iceberg 表不写） |
| `paimon_view` | 视图（字段 / 查询语句 / 方言） | 视图（`ViewMetadata`：versions / version-log） | **+1 列** |
| `paimon_function` | 函数（入参/出参/定义体） | Iceberg 也有 `FunctionMetadata` | **0**（二期做只读投影） |
| `paimon_consumer` | 流式消费位点 | 不适用（Iceberg 无消费位点） | **0**（页签隐藏） |
| `paimon_semantic_view` | 语义视图 | 不适用 | **0**（页签隐藏） |
| 管理侧 7 张（principal / role / grant…） | RBAC + Paimon 原生权限 | **同一套 RBAC**：`ResourceGrant(namespace, objectName)` 的形状本来就与 Iceberg 的 namespace/table 一一对应 | **0** |
| 优化 3 张 | 策略 / 批次 / 明细 | Compaction → `rewrite_data_files`、Snapshot Expire → `expire_snapshots`、Orphan Remove → `remove_orphan_files` | **0** |

**合计 +9 列、0 张新表。**

这张表里最值钱的四行是 `paimon_table_schema`、`paimon_snapshot`、
`paimon_branch` / `paimon_tag`：**两边的资源模型在这三处本来就是同构的**——
Iceberg 的 `schemas[]` ↔ schema 版本表、`snapshots[]` ↔ 快照表、
`refs{type: branch|tag}` ↔ 分支表 + 标签表。这不是巧合：Iceberg 的 ref 与
Paimon 的 branch/tag 都源自「给某个快照起名字」这一件事。

### 4.2 要加的 9 个列

**`paimon_catalog`（1）**

| 列 | 类型（实体声明） | 说明 |
| --- | --- | --- |
| `catalog_format` | `varchar(32)`，默认 `PAIMON` | 协议判别列；见 §3 |

**`paimon_table`（4）**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `metadata_doc` | `length = 1048576`（mediumtext） | **Iceberg 整份 `TableMetadata` JSON**，真相源 |
| `metadata_location` | `varchar(1024)` | 当前元数据文件路径（`<location>/metadata/00003-<uuid>.metadata.json`）。**同时是乐观并发的 CAS 条件**，见 §4.5 |
| `table_uuid` | `varchar(64)` | Iceberg `table-uuid` |
| `format_version` | `int` | Iceberg `format-version`（1/2/3）；Paimon 表为 `null` |

`paimon_table` 的既有列在 Iceberg 侧**全部有对应**，因此一个都不改：
`path_value` ↔ Iceberg `location`（表目录）、`schema_id` + `schema_doc` ↔
`current-schema-id` + 当前 schema、`table_type` ↔ `ICEBERG`（与 `format-table` /
`object-table` 同一列，`GET .../table-details?tableType=` 因此照旧能按格式过滤）。

**`paimon_snapshot`（3）**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `parent_snapshot_id` | `bigint` | Iceberg `parent-snapshot-id` |
| `sequence_number` | `bigint` | Iceberg v2 的 `sequence-number`（refs 与快照过期要按它排序） |
| `summary_json` | `length = 65535`（text） | Iceberg `summary` 原样（`added-records`/`total-files-size`/…） |

既有列继续复用，映射关系明确：

| 既有列 | Iceberg 来源 |
| --- | --- |
| `snapshot_id` / `schema_id` / `time_millis` | `snapshot-id` / `schema-id` / `timestamp-ms` |
| `base_manifest_list` | **`manifest-list`**（Iceberg 每个快照只有一个清单，复用 Paimon 的「base」列，其余三列留空） |
| `total_record_count` | `summary["total-records"]` |
| `file_count` / `file_size_in_bytes` | `summary["total-data-files"]` / `summary["total-files-size"]`（v1 无前者 → `null`） |
| `commit_user` | **认证主体**（服务端自己知道是谁发的 commit，比从 summary 里猜可靠） |

这一列映射让控制台「详细信息」的四张统计卡片对 Iceberg 表**零改动就能出数**。

**`paimon_view`（1）**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `metadata_location` | `varchar(1024)` | Iceberg view 的元数据文件路径 |

`view_schema_doc` 复用为 **`ViewMetadata` JSON**（Iceberg 的 `versions[]` 与
Paimon 的「字段 + 查询语句 + 各方言语句」表达的是同一件事，各自按格式解析）。

### 4.3 已知会撑到行大小上限的风险，先算清

上一轮刚被 `ERROR 1118: Row size too large` 打中过一次
（见 [`data-model.md` §3.1](data-model.md)），新增列必须按同一口径核一遍。
`paimon_table` 加完四列后的实测估算（utf8mb4，`varchar(N)` 计 `4N`、
`text` 家族只计 9~12 字节指针）：

| | 加列前 | 加列后 |
| --- | --- | --- |
| 整行估算 | ≈ 10.6 KB | ≈ **15.0 KB** |
| 占 65535 预算 | 16% | **23%** |

结论：安全。两条纪律写进实施要求——**所有新 JSON 文档列一律 `mediumtext`**
（`length = 1048576`，与既有 `schema_doc` 同档），**绝不写 `varchar(8192)` 这种
「看起来够大」的值**；`MysqlDdlGeneratorTests#everyTableFitsTheMysqlRowSizeLimit`
这条既有守门断言会自动覆盖新列，不需要新增断言。

### 4.4 真相源与投影：项目里已有先例

Iceberg 的 `TableMetadata` 是一份**整体快照**（每次 commit 生成一份新的 JSON），
而 Paimon 侧是**逐资源建行**。本方案的落法是：

> **`metadata_doc` 是唯一真相源；行（快照/标签/分支）是它的派生投影。**

这不是新发明：`paimon_catalog.storage_config_json` 就是真相源，而
`storage_type` / `allowed_locations_json` 是它的投影列，`data-model.md` 里
写得很明白——「读取时一律以本字段为准」。Iceberg 侧照抄这一套：

- **写**：`IcebergTableService.commit(...)` 拿到新 `TableMetadata` →
  写一份文档 → 从文档派生快照/refs 的增删 → 同一个事务里落库；
- **读**（控制台列表、统计、排序、分页）：走投影行，SQL 里解决，
  不把几十 MB 的文档搬进内存；
- **读**（引擎）：走仓库里的 `metadata.json`，服务端只负责给出
  `metadata-location`；
- **自检**：`ConsoleFileService` 的「文件列表」页已经在做「库里说的 vs 盘上写的」
  比对，Iceberg 表加一条同口径的检查——文档、投影行、仓库文件三方一致。

### 4.5 写入次序：先写仓库，后落库

Iceberg 的 commit 会**同时**产生两处写入：仓库里的新 `metadata.json`、
DB 里的文档与投影行。次序不能随便选：

| 次序 | 失败时的后果 |
| --- | --- |
| 先落库、后写仓库 | DB 指向一个**不存在的**元数据文件 → 表从此读不开，必须人工修 |
| **先写仓库、后落库**（采用） | 仓库里多一个**孤儿** metadata 文件 → 无害（Iceberg 本来就允许、且 `remove_orphan_files` 能清），DB 仍指旧版本，客户端重试即可 |

并发也在这里解决：Iceberg 客户端用 `requirements`（`assert-table-uuid` /
`assert-ref-snapshot-id` / `assert-last-assigned-field-id` …）做乐观并发，
服务端**必须逐条校验**，而在自己这侧再加一道 CAS：

```sql
update paimon_table set metadata_location = :new, metadata_doc = :doc, ...
 where id = :id and metadata_location = :expected
```

影响 0 行 = 期间被别人提交过 → 回 `409` 并带上 `AssertTableUUID` 式的
`CommitFailedException` 语义。这与优化批次里
「`update ... where id = ? and next_run_at = ?` 抢占」是同一个手法
（见 [`data-model.md` §2.1](data-model.md)），多副本部署下不引入任何锁组件。

### 4.6 namespace 的层级与分隔符

Iceberg 的 namespace 是**多级**的（`["accounting","tax"]`），Paimon 的
database 是单级字符串。`paimon_database.name` 是 `varchar(512)` 且
唯一约束是 `(catalog_id, name)`——**多级 namespace 直接拼成点号串存进去即可，
列结构不用动**：`["accounting","tax"]` ↔ `accounting.tax`。

三处细节要定死：

1. **对外分隔符照规格来**：`/config` 的 `overrides["namespace-separator"]`
   不声明（即保持规格默认 `0x1F`），**解析时同时接受 `.`**。
   反过来（对外声明 `.`）也能让 URL 更好读，但那要求把 `.` 当分隔符解，
   而 Iceberg 允许单个 level 里含点号，会引入歧义；默认值最安全。
2. **库里存点号拼接形式**：与 Spark SQL 的 `catalog.db.table` 三段名、
   与 `paimon_resource_grant.namespace_key`（本来就是「命名空间路径的拼接」）
   口径一致，控制台与 SQL 里都直接可读。
3. **控制台一期平铺**：库列表里显示 `accounting.tax` 一行（与库里的存储形态一致），
   不做层级树的展开/折叠——层级树是二期的事，一期的收益是**零改动就能看见多级库**。

### 4.7 优化功能顺带通了（已落地）

`paimon_table_optimization` 与表 1:1、`paimon_optimization_job_item` 按表记账，
这套结构对 Iceberg 表同样成立，只是三项动作在 optimizer 侧换成 Iceberg procedures
（`rewrite_data_files` / `expire_snapshots` / `remove_orphan_files`）。
落地时**没有新建表、也没有加列**：策略表的三组 `*_enabled_flag` + `*_config_json` 存的是
键值对，键由格式决定，两种格式够用；`optimization_job` / `optimization_job_item` 本来就
与格式无关，格式只在读的时候从 catalog / table 推导。

`OptimizationOptions.KNOWN_KEYS` 没有扩成一张大表——那会让两种格式的键互相可见。
换成按格式分派：`OptimizationOptions` 加一个 format 重载，参数键 / 默认值 / 校验 / 调度间隔
的唯一真相源是 `IcebergOptimizationOptions`（Paimon 仍是原处的常量表）。
指标口径不做翻译：`metrics_json` 里存 procedure 的真实输出列
（`rewritten-files` / `added-files` / `rewritten-size`、`deleted-data-files` 等），
控制台按「键存在就显示」渲染，因此不需要认识格式。

这是「表统一」最直接的收益：**优化功能不用为 Iceberg 重写一遍**——重写的只有语句怎么生成
（`IcebergProcedures`）和会话怎么建（`TableOptimizerJob.spark()`），两处都在执行侧。
细节与参数差异见 [table-optimization.md](table-optimization.md) §2.5 / §6.5。

---

## 5. 协议实现要点

### 5.1 Iceberg 端点 × 实现方式

35 个端点，按实现方式分四类：

| 类别 | 端点 | 实现方式 |
| --- | --- | --- |
| **直接映射**（namespace ↔ database） | `GET/POST /namespaces`、`GET/HEAD/DELETE /namespaces/{ns}`、`POST /namespaces/{ns}/properties` | 落到现有 `DatabaseService`，属性读写用 `options` |
| **直接映射**（table ↔ table） | `GET/POST /namespaces/{ns}/tables`、`GET/POST/DELETE/HEAD /namespaces/{ns}/tables/{t}`、`POST /tables/rename`、`POST /namespaces/{ns}/register` | 落到新 `IcebergTableService`；`register` 复用现有 `register` 语义（登记已有目录 = 外部表） |
| **新的小端点** | `POST .../tables/{t}/metrics`、`POST .../tables/{t}/unregister`、`GET .../tables/{t}/credentials`、`POST /views/rename`、`POST /namespaces/{ns}/register-view` | 各自一个方法；`credentials` 复用 `StorageCredentialManager`（含 `X-Iceberg-Access-Delegation` 头） |
| **一期不做**（不声明） | `POST .../tables/{t}/plan`、`GET/DELETE .../plan/{plan-id}`、`POST .../tasks`、`POST .../tables/{t}/sign`、`POST /transactions/commit`、`GET .../functions(/{f})`、`/namespaces/{ns}/register-view` 视排期 | 见 §6 |

### 5.2 `/v1/config` 的差异化下发

同一个 `ConfigController`，按 catalog 格式组装两份响应：

| 字段 | Paimon catalog | Iceberg catalog |
| --- | --- | --- |
| `defaults.prefix` | ✅ 现状 | ✅（Iceberg 客户端同样需要它拼路径） |
| `defaults.warehouse` | ✅ 现状 | ✅ |
| `defaults` / `overrides` | 现状（`table-default.*` 等） | 新增：`io-impl`、云存储端点等在 catalog 属性里配的连接参数 |
| `endpoints` | 不下发（Paimon 规格没有这个字段，客户端忽略未知字段） | **必须下发显式清单**（见 2.3） |
| `idempotency-key-lifetime` | 不适用 | **不声明**：不声明即「不支持 Idempotency-Key」，客户端不会重放 |
| `namespace-separator` | 不适用 | 不声明（保持规格默认 `0x1F`） |

`?warehouse=` 的处理保持现状（当 prefix 解析，未登记时回落到默认 prefix）。
Iceberg 规格允许此参数找不到时回 `404 NoSuchWarehouseError`，与现有 `resolve()`
的 404 行为一致，不需要特殊处理。

### 5.3 Iceberg 侧的表操作走「更新代数」

Iceberg 的写路径不是「提交一个新快照」，而是**「一轮 update 作用在当前元数据上」**：

```
POST /v1/{prefix}/namespaces/sales/tables/orders
{ "identifier": {...}, "requirements": [...], "updates": [ AddSchemaUpdate, SetCurrentSchemaUpdate, ... ] }
→ 200 { "metadata-location": ".../metadata/00003-x.metadata.json", "metadata": { ... } }
```

要支持的正确子集（覆盖 Spark / Flink / PyIceberg 的常规写入）：

- `assign-uuid`、`upgrade-format-version`、`add-schema`、`set-current-schema`、
  `add-partition-spec`、`set-default-spec`、`add-sort-order`、`set-default-sort-order`、
  `add-snapshot`、`set-snapshot-ref`、`remove-snapshots`、`remove-snapshot-ref`、
  `set-location`、`set-properties`、`remove-properties`、
  `set-statistics` / `remove-statistics`、`set-partition-statistics` / `remove-…`、
  `remove-schemas`、`remove-partition-specs`；
- **不支持**（明确报错而不是静默忽略）：`add-encryption-key` / `remove-encryption-key`、
  `add-view-version` / `set-current-view-version` 出现在**表**提交里（那是 view 的 update）。

「明确报错而不是静默忽略」这条要有：静默吞掉一个 update，等于让服务端返回一份
与客户端预期不符的元数据，客户端会拿它继续往下算。

### 5.4 依赖取舍：要不要引 `iceberg-core`

**要。** 理由是量级：`TableUpdate` 25 种、`TableRequirement` 8 种，
每种都有精确的语义（尤其是 `add-snapshot` 对 `sequence-number`、
`set-snapshot-ref` 对 `retention` 的处置），加上 `TableMetadata` 的 schema 校验。
手写等于把上游的元数据代数重写一遍，而且**随 Iceberg 版本漂移**——
Iceberg 1.9/1.10 之间就新增了 `next-row-id`、`encryption-keys`、
`read-restrictions` 等字段。

取舍：

| 方案 | 评价 |
| --- | --- |
| **引 `org.apache.iceberg:iceberg-core`**（Apache-2.0） | 采用。`TableMetadataParser` + `TableMetadata.Builder` + `MetadataUpdate` 直接用，语义与客户端同源；代价是带上 avro/jackson（约 10 MB 级） |
| 自己实现 update 代数 | 拒绝：可维护性差，且升级 Iceberg 版本要重写 |
| 只做「登记外部 Iceberg 表」（不做 commit） | 拒绝：那样 Spark 只能读不能写，等于半个 catalog |

版本策略：与「我们要支持的客户端版本区间」对齐并写进 README；
Iceberg 规格副本已在 `spec/rest-catalog-open-api-iceberg.yaml`，
端点覆盖率与 Paimon 侧一样能被机械校验（见 §9）。

### 5.5 授权与凭据

- **授权**：`CatalogAccessRules` 是按路径登记的映射表，`Requirement` 的形状是
  `(catalog, resourceType, namespace[…], objectName, privilege)`——**它本来就是
  namespace 形状的**，Iceberg 的 `/v1/{prefix}/namespaces/{ns}/tables/{t}`
  逐条登记即可，`GrantType` 的 catalog/database/table/view 四级完全够用。
  Iceberg 侧没有「原生权限词表」（规格不管授权，认证靠 OAuth scope），
  因此授权判定只有 RBAC 与 Paimon 直授两路，而**那两路都是格式无关的**。
- **直授端点也服务 Iceberg catalog**（落地时比方案乐观的一处）。原方案写的是
  「`POST /v1/{prefix}/permissions/grant` 保持 Paimon 专属」，实际上
  `PermissionManagementService` 只认 `catalog + database + 对象名` 这三个字符串，
  根本不看 catalog 格式；`{prefix}` 是任意 catalog 名。因此控制台「权限管理」页
  **一页覆盖两种格式**，Iceberg 下只是把「库」那一级改叫 Namespace、候选按层递归
  （[console.md](console.md) §4.8）。
  代价是**判权时必须按拼起来的路径比较**：直授把 `accounting.tax` 存成一个字符串，
  RBAC 存成 `["accounting","tax"]` 两段，逐段比会静默不命中——这条落在
  `AuthorizationService.isPrefix` 与 `PermissionManagementTests.namespaceGrantMatchesMultiLevelNamespace`
  里，理由见 [authorization.md](authorization.md) §2.6。
- **凭据委托**：Iceberg 的 `LoadTableResult` 有 `storage-credentials` 字段、
  请求侧有 `X-Iceberg-Access-Delegation: vended-credentials` 头。
  这套语义与 Paimon 的 `/token`、`/auth` 端点**同一件事**，
  复用 `StorageCredentialManager` 与 `StorageCredentialCache`；
  `paimon.rest.credential-manager.type=noop` 时同样如实返回 501/不带凭据，
  与控制台「数据访问」页的现状一致。
- **认证**：Iceberg 客户端默认用 OAuth2（`/v1/oauth/tokens`）或直接 Bearer；
  两条路服务端都已经有（`OAuthTokenController` + `BearerAuthInterceptor`）。
  把 `/v1/oauth/tokens` 加进公开路径白名单（§2.1）。

---

## 6. 能力边界：一期做什么、不做什么

**一期（Spark / Flink / PyIceberg 建表 + 写入 + 读取可用）**

| 能力 | 说明 |
| --- | --- |
| namespace 全套 | 增删查改 + properties 批量增删 |
| table 全套 | 建（含 `stage-create`）/ 查（含 `snapshots=refs\|all`）/ 提交 / 删除 / 存在性 / 列表 / 重命名 / 注册（`register`） |
| view | 列表 / 建 / 查 / 提交 / 删除 / 重命名 |
| metrics 上报 | 接收 `ReportMetricsRequest`，落到快照与分区的统计投影 |
| credentials | 凭据下发（`X-Iceberg-Access-Delegation`） |
| oauth/tokens | 客户端凭据换令牌 |
| 元数据物化 | `metadata/vN-<uuid>.metadata.json` 写进表目录 |
| 控制台 | 格式徽标、Iceberg 表详情（页签随格式伸缩）、Iceberg 建表表单（§7） |
| 格式版本 | **v1 / v2 全支持**；v3 的 `next-row-id` / `encryption-keys` 透传即可，不保证 |

**二期**

多表事务 `POST /transactions/commit`（要在 DB 里做跨表原子提交，
复用现有事务边界即可，但**必须与 Iceberg 的 `requirements` 语义对齐**）；
只读 `functions`（投影 `FunctionMetadata`）；`register-view`；
`EXTERNAL + ICEBERG`（外部 Iceberg 仓库入口）；namespace 层级树控制台。

（原列在这里的「优化三项动作的 Iceberg procedures」已在落地时随一期完成：
它们不在协议面上，而是在 optimizer 侧用 Spark 的 `CALL` 执行，见 §4.7。）

**不做（并显式声明不支持）**

`plan` / `tasks`（扫描规划要一个常驻执行引擎，是另一个产品的份量）、
`sign`（远程签名 KMS）、`labels` / `read-restrictions`（新 spec 的实验特性）、
Iceberg 的 `delete namespace` 级联删表（规格要求 namespace 非空时回 409
`NamespaceNotEmptyError`，本方案遵循规格，不做静默级联）。

### 6.1 一期不做的 9 个端点（逐条）

规格共 35 个「路径 × 方法」组合，一期实现 26 个，剩下 9 个是：

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
| `POST /v1/{prefix}/namespaces/{namespace}/tables/{table}/sign` | 不做（远程签名 KMS） |

这份清单在代码里有两处副本，都**必须**与上表逐字一致：`IcebergEndpoints` 的类注释
（哪 9 个不声明）与 `scripts/verify-iceberg-endpoints.py` 的 `NOT_IMPLEMENTED`
（它把「规格 − 声明」算出来与这份清单比对，因此要么实现、要么改文档，两者不能只改一边）。

表中 `plan` 有三个方法（`POST` 建计划、`GET` 拉计划、`DELETE` 取消计划）、
`tasks` 是 `POST`（不是 `GET`）——这两处容易照记忆写错，
所以校验脚本是从 `spec/rest-catalog-open-api-iceberg.yaml` 里读出来比的，而不是照抄本表。

---

## 7. 控制台改动（效果图见 `outputs/iceberg-rest-catalog/mockup.html`）

落地状态见下表最后一列：**七行已全部落地**。目录浏览、Iceberg 建表表单
与表详情页在上一轮补齐——它们当初被判「要先有一层控制台能读 Iceberg 表的接线」，那层接线的做法是
新增 `src/api/iceberg.js` + 13 条端点登记，而不是把 26 条路径全塞进控制台；
表详情页顺着同一条接线加了 `IcebergTableView`（数据几乎全部来自一次 `loadTable` 的整份 metadata）。
存储优化最后一行也接上了：**不是另做一个面板**，而是让表详情页挂上与 Paimon 表同一个
`TableOptimizationPanel` 并显式传 `format="ICEBERG"`——动作名与执行顺序格式无关，
只有参数键与默认值按格式切换（§4.7）。
**不做的一律在页面上说清楚并给出去向**（详见 §11.1 第 11 条），而不是让用户对着一屏 404 猜。

| 页面 | 改动 | 落地 |
| --- | --- | --- |
| 新建 Catalog 弹窗 | 在「名称」与「类型」之间插入**「表格式」**选择（Paimon / Iceberg），选项下方带一行影响面说明：选 Iceberg 后客户端只能走 Iceberg REST 路径、表详情页签会不同；**创建后不可改** | ✅ 用选项卡片（不是下拉）：每个选项要能读出「客户端走哪段路径」与一句话影响面。选中 Iceberg 时额外弹一条说明，把「控制台一期不浏览 Iceberg 表」说在提交之前 |
| Catalog 列表 | 新增「格式」列（`Paimon` / `Iceberg` 徽标）+ 顶部格式筛选；「类型」列保持 INTERNAL/EXTERNAL | ✅ 列序按效果图放在「名称」之后；筛选用**空串**表示「全部」而不是隐藏筛选器；筛选与徽标共用同一个归属函数（否则会出现「筛 Paimon 时那一行不出现、但它写着 Paimon」） |
| Catalog 详情 | 新增「协议与能力」卡片：本 catalog 的基址（`/v1/{prefix}`）、协议版本、**已声明端点清单**与**不支持的能力**（把 §6 的边界摆到页面上，排障时不用去读文档） | ✅ 清单读的是 `GET /v1/config?warehouse=` **下发原文**（前端不抄），另加 namespace 分隔符、凭据下发两行；「协议版本」没做——服务端不校验 `format-version`（客户端给几就存几），写「v1/v2 支持、v3 透传」是一句无法从代码核实的断言 |
| 表详情 | 页签随格式伸缩：Iceberg 表 = 详细信息 / 快照 / 标签 / 分支 / 文件列表 / 存储优化（**没有**分区、消费者、语义视图、数据访问的 Paimon 语义）；「详细信息」多两行：`metadata-location`、`format-version`，并在「metadata 版本」处给一个看原始 JSON 的入口（复用现有 `JsonBlock`） | ✅ **独立页 `IcebergTableView`**（路由 `/iceberg-tables/...`），与 Paimon 版平行而不是同一页加 `v-if`——数据几乎全部来自一次 `loadTable` 的整份 metadata，按需加载的单位与 Paimon 版完全不同。实际页签四块：**详细信息**（当前快照 summary 统计卡 + 元数据 KV + 字段表 + 分区 spec）、**快照**（`metadata.snapshots[]`；标签与分支在 Iceberg 里合成一张 `refs` map，不再拆两个页签）、**文件列表**（catalog 级文件端点，根 = warehouse，初始路径 = `metadata.location` 减 warehouse；location 在仓库外时给出去处）、**存储优化**（挂共用面板 `format="ICEBERG"`，见下一行）。快照回滚 / 过期不做：要走协议面的更新提交，控制台拼不出一轮合法的 `updates` / `requirements`。页头另有「权限」按钮跳「权限管理」页并带 `?catalog=` |
| 新建表表单 | 按格式换形态：Iceberg 走「字段（含 id、required、doc）+ 分区 transform + 可配 format-version / properties」，Paimon 保持现状（`partitionKeys` 表达式 + 主键） | ✅ 形态按图落地，但**不是一个表单切两种形态，而是两个表单**：Iceberg 的那份在 `IcebergBrowseView` 里（Paimon 的留在 `BrowseView`）。理由与目录浏览同一件事——建表报文的形状（`schema` vs 字段列表、`partition-spec` vs 分区键表达式）没有共享部分，合在一个对话框里只剩两套 `v-if`。`format-version` 给「留空 / 1 / 2 / 3」：服务端不校验取值，填错的代价在读表时才暴露
（v3 已实测可用：iceberg-core 1.11 接受 `properties['format-version']='3'` 并落出 `format-version: 3`） |
| 目录浏览 | 库（namespace）一期平铺显示点号拼接名，不做层级树 | ✅ 新增 `IcebergBrowseView`（按 catalog 格式换页，不是同一页加 `v-if`）：命名空间平铺（按层递归，深度上限 4 层 / 条目上限 500）、建/删（**非空 409** 与 Paimon 的级联删除不同，确认框里写明）、**建表**（Iceberg 的 schema / partition-spec，字段含 id / required / doc）、登记、改名、删除、注销、视图读与删。表名与「详情」跳 Iceberg 表详情页（`IcebergTableView`），视图详情走抽屉。建视图不做：要 schema + view-version 两段元数据，控制台拼不出可靠的一份 |
| 存储优化 | 对 Iceberg 表可用其三项动作与策略表单，参数键按格式切换（§4.7） | ✅ 页签挂 `TableOptimizationPanel` 并显式传 `format="ICEBERG"`；`actionsFor(format)` 取参数定义，`metricSummary()` 不判格式、只判键存在（Iceberg 报的是 procedure 真实输出列）。当初的阻塞点找到了真正的原因，且**不是** controller 守卫：`TableOptimizationService` 借道 Paimon 的 `TableLookup` → `DatabaseService.resolveForFormat(prefix, PAIMON)`，对 Iceberg catalog 必 404——连读策略都进不去。改为按 catalog/库/表直查仓储后，策略 / 立即执行 / 历史三个端点对两种格式都回答。页面侧重用的解法见 [console.md](console.md) §4.7（页签懒加载的 `watch(ref)` 版本） |

---

## 8. 风险与对策

| # | 风险 | 对策 |
| --- | --- | --- |
| 1 | 两条 `rename` 同路径，靠 prefix 判据；`auto-create-catalog` 开着时 prefix 可能是错的 | 双层判据（catalog 格式 + 请求体字段名）；格式不匹配时给**指名原因的 404**（§3.3） |
| 2 | 不声明 `endpoints` = 默认承诺了 `transactions/commit` | `/v1/config` 显式下发清单；契约测试断言清单与实现一致 |
| 3 | 错误模型不同，Spark 侧报不出原因 | 按 catalog 格式选错误响应体（§2.3），两侧各有一条契约测试 |
| 4 | `metadata_doc` 体积增长（大表 metadata 含 snapshot-log / metadata-log） | 列类型 mediumtext（16 MB 上限）；`metadata-log` 由服务端按 `paimon.rest.metadata-log.max-entries` 截断（只保留最近 N 条），与 Iceberg 默认语义一致 |
| 5 | 文档 / 投影行 / 仓库文件三方不一致 | 文档是唯一真相源；投影由唯一写入入口派生；「文件列表」页加同口径自检（§4.4） |
| 6 | 并发提交互相覆盖 | 服务端 CAS（`metadata_location`）+ 客户端 `requirements` 双向校验（§4.5） |
| 7 | 引 `iceberg-core` 带来的依赖体积与版本漂移 | 依赖只落在 `lakehouse-rest-server`，版本与支持区间写进 README；`iceberg-core` 不进 spark/optimizer 模块 |
| 8 | 一个 catalog 一种格式，团队既想共用一个仓库目录又想两边都能读 | 明确不支持：同一仓库目录两种格式 = 两个 catalog，元数据互不可见（写进 README 的已知边界） |
| 9 | 行大小上限 | 新列一律 text 家族；既有守门断言自动覆盖（§4.3） |
| 10 | namespace 分隔符歧义（level 内含点号） | 对外用规格默认 `0x1F`、解析兼容 `.`；库里存点号拼接（§4.6） |

---

## 9. 怎么证明做对了

| 手段 | 守什么 |
| --- | --- |
| `scripts/api-sweep.sh` 扩成两套 | Paimon 60 个 operation 保持现状；新增 Iceberg 35 个 operation 的逐条调用（会建 `ns`/`tbl` 并清理），覆盖率不靠人工比对 |
| `CatalogAccessRules` 覆盖率测试 | 既有机制：未登记的 `/v1/**` 端点直接 403；Iceberg 路径登记漏了会立刻暴露 |
| `endpoints` 契约测试 | `/v1/config` 下发的清单 ↔ 实际存在 handler 的路径集合，双向比对 |
| **三层端到端** | ① 官方 **Java 客户端**走真 HTTP（`IcebergClientEndToEndTests`）——协商、错误模型、更新代数；② 26 个端点的 HTTP 行为（`IcebergRestCatalogApiTests`）；③ Spark 各连一次：Paimon `spark-paimon-rest-e2e.md` 那套照旧，Iceberg 侧见 `docs/spark-iceberg-rest-e2e.md`（**该文档只到 ① / ②，Spark 侧未跑，第 0 节显式标注**） |
| 错误模型契约测试 | Iceberg 路径下故意触发 404/409，断言响应体是 `{error:{type,code}}` 形状 |
| 行大小守门 | 既有 `everyTableFitsTheMysqlRowSizeLimit` 自动覆盖新列（§4.3） |
| 投影一致性自检 | 造一次「文档与投影行不一致」，断言自检报出来（**变异测试**，不空跑） |
| 控制台 `verify-console.py` | 新增第 12 组共 **12 项**：`CATALOG_FORMATS` 与服务端枚举双向一致；meta 下发的候选与默认值都走枚举本身；新建表单从 meta 取候选与默认值且提交体带 `catalogFormat`、**编辑不回传它**；选项下方有影响面说明；列表有徽标与筛选（筛选与徽标共用同一个归属函数）；三个页面按格式伸缩**且在 loader 里挡住请求**（判据不是「模板里有 `v-if`」——只在模板上藏内容，请求照样发得出去，页面上留下一串 404，看起来像故障而不像「一期不支持」）；详情页的能力清单读 `/v1/config` **且前端源码里没有端点字面量**；「不声明支持」的条数与 `verify-iceberg-endpoints.py` 的 `NOT_IMPLEMENTED` 一致（那一栏只能写在前端，因此那个数字是唯一会随实现漂移的地方） |

---

## 10. 实施拆分（工作项，不含工期）

| # | 工作项 | 产出 |
| --- | --- | --- |
| 0 | **模块布局与命名**（[`maven-module-design.md`](maven-module-design.md)） | 坐标改名（`paimon-rest-*` → `lakehouse-rest-*`）+ 服务端拆成 core / storage / protocol-paimon / protocol-iceberg / server。**必须先于工作项 3–7**：边界定好之后，Iceberg 的控制器与元数据代数从第一天就落在 `protocol-iceberg` 里，否则要在实现完之后再搬一次 |
| 1 | 数据模型改动（+9 列） | 实体 + `sql/schema-mysql.sql` 重新生成 + `data-model.md` 回填；**验证 `validate` 能起** |
| 2 | `catalog_format` 落地 | 管理 API / 控制台 meta / 新建表单 / 列表徽标 + 筛选；格式不匹配的明确报错 |
| 3 | 协议分派骨架 | `CatalogFormat`、`CatalogProtocol`、Iceberg 控制器树、`/v1/config` 差异化、错误模型分支、`/v1/oauth/tokens` |
| 4 | Iceberg 表读路径 | `load` / `exists` / `list` / namespace 全套；`metadata.json` 物化 |
| 5 | Iceberg 表写路径 | `create` / `commit`（update 代数）/ `drop` / `rename` / `register`；CAS 与 requirements |
| 6 | 投影与统计 | 快照 / refs 投影派生 + metrics 上报落库 + 一致性自检 |
| 7 | view | view 全套 |
| 8 | 授权与凭据 | `CatalogAccessRules` 增补 + 凭据委托 |
| 9 | 控制台 | §7 的六处改动 + `verify-console.py` 新组 |
| 10 | 文档 | 本文件 + `catalog-api-endpoints.md` 增补 Iceberg 章节 + `spark-iceberg-rest-e2e.md` + README 已知边界 |

---

## 11. 落地记录（与 §5–§9 不一致的几处）

代码已落地。这一节只记**与上面方案不同**的地方与原因——一致的部分不再复述，
看代码更快。每条都指向一处具体的实现，便于对照。

### 11.1 与方案不同的十六处

| # | 方案里写的 | 实际落的 | 为什么 |
| --- | --- | --- | --- |
| 1 | 「core 通过 Spring 收集 `List<CatalogProtocol>`」——没说谁持有 | 注册表在叶子 bean **`CatalogProtocols`**，不在 `CatalogService` | 直接放进 `CatalogService` 会形成构造器环 `CatalogService → List<CatalogProtocol> → PaimonProtocol → TableService → CatalogService`。Spring Boot 2.6+ 默认拒绝构造器环，**实测是 184 个用例的 ApplicationContext 全部加载失败**。上移之后依赖方向变成「控制器 → `CatalogProtocols` → 协议面 → 业务服务 → `CatalogService`」，单向 |
| 2 | §5.4 / §8 风险 7：「`iceberg-core` 依赖只落在 `lakehouse-rest-server`」 | 声明在 **`protocol-iceberg`** | `maven-module-design.md` §3.3 的不变量 3（`iceberg-core` 只出现在 `protocol-iceberg`）更严，而且它**能用 enforcer 在编译期守住**。server 通过 `server → protocol-iceberg` 传递地拿到它，于是「可执行产物里有它」与「只有一处声明它」不矛盾——enforcer 的 `bannedDependencies` 对 server 关掉了传递检查正是为此 |
| 3 | §6：metrics「落到快照与分区的统计投影」 | **只校验 + 记一条 warn，不写投影** | `CommitReport` 里的是**本次提交的增量**（`added-records` / `added-data-files`），而投影列（`total_record_count` / `file_count`）存的是**表的当前总量**。把增量写进去，控制台的四张统计卡片会立刻变成错的数，而且错得看不出来。总量的权威来源是快照的 `summary`，它随提交一起到手，不需要等这份报告。端点仍然有用：报告里的 `snapshot-id` 不在投影里 = 有人绕开本服务端往仓库里写（直连文件系统、或另一个 catalog 指向了同一个目录） |
| 4 | `stage-create` 报 501（`IcebergDtos` 的初版注释） | 报 **400** | 501 说的是「这个**服务端**没实现这个能力」，而 `stage-create` 是**报文里的一个字段取值**——服务端实现的能力是「建表」。客户端处置也不同：400 表示改报文可重试，501 表示得换服务端 |
| 5 | §5.3 没提 `view-version` 怎么解析 | 包一层 `add-view-version` update 走 `MetadataUpdateParser` | 上游 `ViewVersionParser` 只公开 `fromJson(JsonNode)`，而那个 `JsonNode` 是 **Jackson 2** 的类型；本模块对外是 Jackson 3。为这一处引进 Jackson 2 的 databind，等于让两套 Jackson 的边界从「Iceberg 内部」扩散到本模块。`add-view-version` 的外层形态在更新代数里有定义，包成它即复用上游解析器 |
| 6 | §8 风险 7 的另一半没写 | `storage` 的 `paimon-core` 必须 **`<optional>true</optional>`** | enforcer 的 `bannedDependencies` 查整棵依赖树。storage 的**对外契约没有任何 Paimon 类型**（它只暴露「路径 + 文本」），所以 optional 才是它真正的语义；不写它，`protocol-iceberg` 会因为传递地拿到 `paimon-core` 而被自己的规则误杀 |
| 7 | §2.3：「靠 prefix 判据」——没说落点 | 两条 `rename` 上移到 core 的 **`CatalogRenameController`** + `CatalogProtocol#rename` | 逐字同路径 + 两个协议面禁止互相 import（enforcer 守着），所以两边都不能各写一个映射——Spring 会以 `Ambiguous mapping` **拒绝启动**。状态码也各按各的规格：Paimon 200、Iceberg 204 |
| 8 | §8 风险 1 的对策：「双层判据（catalog 格式 + 请求体字段名）」 | 两层**分开用**：错误形状按**路径**定，业务分派按 **prefix + 请求体**定（请求体那层只服务两条 `rename`） | 异常处理器不该为了挑错误形状去解析**已经失败过一次**的请求体（`HttpMessageNotReadableException` 正是最常见的失败之一）。另外判「某面独有」必须按**第一段**比较：漏掉集合路径本身（`/v1/{prefix}/namespaces`）不是「少判一条」，而是**判给另一个面**——那个请求会拿到形状不对的报文，客户端只能报一句没有 message 的「Unable to process」 |
| 9 | §2.3 没说 `endpoints` 清单怎么存 | **硬编码字符串**，不引用 `Endpoint` 常量 | 引用它会拉进 `org.apache.hc.core5.http.Method`——实测缺 httpclient5 时 `Endpoint` 的静态块直接 `NoClassDefFoundError`。服务端为了**声明**一份清单去依赖客户端的 HTTP 实现，是把客户端的世界拖进服务端。字符串与 `Endpoint#toString()` 逐字一致这条由校验脚本守 |
| 10 | §? 只说「`/config` 要下发清单」 | 三个存在性检查端点是**显式**的 `HEAD` 映射 | 规格要求它们的成功码是 **204**，而 Spring 对 `@GetMapping` 的隐式 HEAD 回落只给 200。显式登记不会歧义（读 Spring 7.0.9 字节码确认：`compareTo` 里 HEAD-only 优于 GET-only）。**代价是既有的 `CatalogEndpointAuthorizationTests` 当场失败**（`IllegalArgumentException: HEAD`）——它从运行时映射表枚举端点，那个枚举确实覆盖了新协议面，只是造请求的工厂只有四个方法 |
| 11 | §7 控制台七处改动 | 起初只有前三处按图落地（新建弹窗的**表格式选项卡片**、列表的格式徽标与筛选、详情页的「协议与能力」），后四处**有意不做**；随后补了**目录浏览**、**Iceberg 建表表单**、**表详情页**（`IcebergTableView`）与**存储优化页签**（见下一条），七处现已全部落地 | 各处的共同前提是「控制台能读 Iceberg 的表」。这条接线在补目录浏览时做了，但**不是**当初设想的「把 26 条路径逐条登记进 `EXTENSION_ENDPOINTS`」，而是新增 `src/api/iceberg.js` 并登记**实际会用到的 13 条**：没被调用的端点登记进来就是死条目（`verify-console.py` 第 4 组会报），而第 1 组现在把 Iceberg 规格也并进比对，因此不需要用扩展白名单来「放行」它们。表详情页顺着同一条接线落地——数据几乎全部来自一次 `loadTable` 的整份 metadata，文件列表复用 catalog 级文件端点（`metadata.location` 减 warehouse 得根），不需要新端点。**判断标准没变**：不做的能力在页面上说明并给出去向（不发注定被拒的请求），不把 404 换个地方发生。边界记在 `docs/console.md` §10 与 §4.7，判据由 `verify-console.py` 第 12 组（伸缩）与第 13 组（Iceberg 契约形状，含表详情页）守 |
| 12 | §9：「`api-sweep.sh` 扩成两套，Iceberg 35 个 operation 逐条调用」 | 换成 `scripts/verify-iceberg-endpoints.py`（**15 项**）+ `IcebergRestCatalogApiTests`（26 个端点的 HTTP 行为） | 逐条调用脚本要为 35 个操作维护 35 段会过期的期望值。静态三方比对（**规格 ↔ 声明的能力清单 ↔ 控制器映射**）+ 行为测试覆盖同样的错，且不会随实现漂移。脚本里那 9 条「不声明」写成**字面量**、不从「规格 − 声明」推——推出来的清单永远自洽，就永远发现不了漏实现 |
| 13 | §9：「双客户端端到端，Iceberg 侧新建 `docs/spark-iceberg-rest-e2e.md`——落一份实测记录」 | 文档已建，但**实测记录只到客户端层为止**：用官方 **Java 客户端**（`org.apache.iceberg.rest.RESTCatalog`）走真 HTTP 的端到端已落（`IcebergClientEndToEndTests`，3 个用例）；**Spark 侧仍未跑过** | 两层要分开说。跑通官方客户端之后，「协议层」与「客户端协商层」已是实测：能力清单协商、嵌套错误体翻成具体异常、更新代数（`requirements` + `updates`）全过。剩下没验的只有 Spark 自己那一层（SQL 解析、`SparkWrite` 写文件、配置键在真实会话里能否生效），而 Spark 的 `SparkCatalog` 并不实现协议——它把 SQL 翻成 `Catalog` 调用，REST 那一层就是被测过的那个实现。因此文档按「已实测 / 未实测」分层标注，SQL 与连接配置照写但标明是参考而非实测记录。写「Spark 已实测」仍是假话 |
| 14 | §9：「错误模型契约测试」 | 已落，并且**两个方向都比** | 不只验 Iceberg 路径的 404 是嵌套形状，也验「Iceberg 路径落在 Paimon catalog 上」的消息点出 `PAIMON catalog`（`IcebergRestCatalogApiTests`）。方向对了才说明形状是按**请求所属的面**选的，而不是按 catalog 的格式选的 |
| 15 | §4.7：「`OptimizationOptions.KNOWN_KEYS` 需要补 Iceberg 专属参数」 | **不往一张表里加键**，改成按格式分派：`OptimizationOptions` 加一个 format 重载，Iceberg 的键表独立成 `IcebergOptimizationOptions` | 键表合并会让两种格式的参数互相可见——界面上会冒出「Paimon 表也能填 `delete-file-threshold`」这种既存不下去、也不会报错的字段，而控制台的参数定义又是从「键是否存在」推的。分开之后 `verify-console.py` 第 11 组多了一条断言：`iceberg_frontend_keys != paimon_frontend_keys`（防复制粘贴），两侧的键集合还各自与服务端常量逐字比对 |
| 16 | §3.4/§5.5 隐含「权限管理与格式无关」，但没说判定怎么比 | `AuthorizationService.coversScope` 对 namespace **按点号拼成路径再比**（`NamespacePaths.STORAGE_SEPARATOR`），不逐段比 | 两条授权链存的形状不同：RBAC 存层级数组 `["accounting","tax"]`，Paimon 直授存一个字符串 `["accounting.tax"]`（`paimon_database.name` 的形状）。逐段比会让「授 `accounting.tax`、访问 `["accounting","tax"]`」**永远不命中**，且两端都不报错——页面显示已授权、客户端一直 403。反向的「在翻译层切层级」也不行：那会把**本来就含点号**的 Paimon 库名切坏。因此「怎么比」收敛到判定这一处，`PermissionManagementTests.namespaceGrantMatchesMultiLevelNamespace` 钉住覆盖后代 / 不反向覆盖 / `sales` 不命中 `salesforce` 三条 |

### 11.2 落地后新增的三条机器守卫

| 守卫 | 守什么 | 为什么不能省 |
| --- | --- | --- |
| `scripts/verify-iceberg-endpoints.py` | 规格 35 条 ↔ `IcebergEndpoints.SUPPORTED` 26 条 ↔ 控制器映射，三方比对；`NOT_IMPLEMENTED` 的 9 条写成字面量 | 「声明了但没实现」与「实现了但没声明」两种错各自都会让客户端在**错误的时机**失败（前者是用户真的用到了那个能力时，后者是连接阶段就被拒）。另外它**先做过变异测试**：首跑就抓出三处既有问题（脚本自身的 `tasks` 写成 GET、`plan` 漏了 `DELETE`、控制器注释里的 `{@code @GetMapping}` 被当成映射），证明它不是空跑 |
| `IcebergRestCatalogApiTests` | 26 个端点的**行为**：规格要求的成功码（`rename` 204 / `unregister` 200 / 两个 `HEAD` 204）、kebab-case 字段名、`metadata.json` 真的落到仓库、错误体嵌一层 `error`、外部表不被 purge、视图删除不碰仓库 | 静态校验与授权映射都只读**形状**（路径、方法、清单），从不发一个请求。状态码错了、字段名写成驼峰、文件没落盘，前两条守卫一条都发现不了 |
| `IcebergClientEndToEndTests` | 用官方 `org.apache.iceberg.rest.RESTCatalog` 走**真 HTTP**（`RANDOM_PORT` + JDK `HttpClient` 建 catalog）验三件 MockMvc 看不见的事：① `/v1/config` 下发的 `endpoints` 与客户端自需端点**等值协商**（清单少了操作在本地就被拒、多了会真发出去拿到 404，两个方向 MockMvc 都发现不了）；② 嵌套错误体翻不翻得成 `NoSuchTableException` 等**具体**异常（形状对而 `type` 名字错在 MockMvc 里是绿的）；③ 更新代数真跑通（客户端自己生成 `requirements` 与 `updates`，服务端解、校验、应用、落库，客户端再解析回元数据） | 前两条守卫都只读**形状**，第三条只验服务端自己造出来的报文。只有真客户端才会去用服务端**发出去**的报文——清单、错误体、元数据文档这三样都是「服务端的输出被客户端消费」，只有这一层能证 |

**后两条守卫都不是理论上需要，首跑都抓到了真问题。** `IcebergRestCatalogApiTests` 抓到
`IcebergMetadataFiles` 的 codec 参数用错（见下）；`IcebergClientEndToEndTests`
抓到两处**客户端环境**上的坑，都不是服务端的 bug，但不写下来下一个人还要再踩一遍：

- **裸 JVM 里默认的 `io-impl` 装不上。** `ResolvingFileIO` 声明了
  `implements HadoopConfigurable`，**类加载**阶段就要
  `org.apache.hadoop.conf.Configurable`，而本仓库刻意不让 Hadoop 客户端栈进服务端类路径
  （见 `lakehouse-rest-spring-parent/pom.xml` 对 `paimon-format` 的排除）。
  为了一个用例推翻那条不变量不划算，因此测试用 `io-impl` 指到一个测试桩
  （`support/TestOnlyFileIo`，三个方法都抛而不是返回 `null`——将来某条路径真的开始读
  数据文件时，它会立刻炸而不是静默地拿到 `null` 跑出一片绿）。
- **`Map.of(...)` 会让上游的 `createNamespace` 抛 NPE。**
  `CreateNamespaceRequest.Builder.setProperties` 会做 `props.containsKey(null)` 来挡 null 键，
  而 Java 的不可变 Map 对 `containsKey(null)` 是抛 NPE 而不是返回 `false`。
  Spark / Flink 传的都是可变 map，所以这条只有手写客户端的人会踩到。

**codec 那个真 bug 的细节：** `IcebergMetadataFiles` 推下一代
元数据文件的位置时写了 `TableMetadataParser.getFileExtension(previousLocation)`——
而上游那个方法的签名是 `getFileExtension(String codecName)`，收的是 **codec 名**
（`"none"` / `"gzip"`），不是文件位置。传一个位置进去能编译通过，运行时抛
`IllegalArgumentException: Invalid codec name: <整个路径>`，而且**只在第二份元数据之后才炸**：
建表那一步 `previousLocation` 是 `null`、根本不走这条分支。于是它的唯一表现是
「建表成功、第一次写入失败」——也就是**每一个真实 commit 都失败**。
两条静态守卫（端点清单、授权映射）都看不见它，因为这不是形状问题；
测试一跑就抓到，正确写法是 `Codec.fromFileName(fileName)`（顺带认得住历史的
`.metadata.json.gz` 写法）。

### 11.3 存储优化与权限管理落地时补的两处守卫

这两块功能不是新的协议面，因此没有新增「三方比对」型脚本——它们各有一个已有的守卫被扩写：

| 守卫 | 扩成什么 | 守什么 |
| --- | --- | --- |
| `scripts/verify-console.py` 第 11 组 | 从「一张 `ACTIONS` 对照服务端 `KNOWN_KEYS`」变成**两套各一条**：`server_option_keys(source, wire_of)` + `console_field_keys(source, marker)` + `compare_option_keys(label, ...)`，Paimon 与 Iceberg 各比一次，另外断言两侧键集合**不相等** | 前端参数定义与服务端常量是两份手写清单，漂移后的表现是「界面上有这个框、填了保存不进去」或「服务端支持、界面上填不了」，两种都不会报错。第 11 组此前只盯 Paimon，Iceberg 的 8 个参数键属于新增的漂移面。断言「不相等」是防复制粘贴：把 Paimon 的键表复制一份改名，比对照样全绿，但 Iceberg 侧会拿错键去调 procedure |
| `PermissionManagementTests.namespaceGrantMatchesMultiLevelNamespace` | 新增一个用例 + 一个辅助方法 `namespacePrivileges(principal, levels)` 直接调 `authorizationService.effectivePrivileges(..., GrantType.NAMESPACE, levels, null)` | 多级 namespace 的判权有一条**静默**失效路径（见 11.1 第 16 条）：逐段比较时授权记录与请求都不报错，只是永不命中。这种错不会让任何端点失败，只能靠「授一次、查一次」的断言。顺带钉住 `sales` / `salesforce` 的前缀边界——那里错一位就是把权限放大到别的库 |

### 11.4 结论：方案里的三处取舍经受住了落地

- **不拆服务、一套元数据**：Iceberg 与 Paimon 两个面共享 22 张表，`+9 列、0 张新表` 按计划落地（DDL 恰为 9 个 `+` 行）。
- **写序先仓库、后落库**：`metadata/vN-<uuid>.metadata.json` 先进表目录再提交元数据行，CAS + `requirements` 双向校验。测试实测「响应回来时那份文件已经在仓库里」——这是「失败的提交不留半成品」这条取舍的可观测形式。
- **一个 catalog 一种格式**：`catalog_format` 非空 + 库级默认 `'PAIMON'`，管理 API 改它 400。跨格式请求的报错**指名原因**（`prefix "x" is a PAIMON catalog; ICEBERG REST paths need an ICEBERG catalog`），两个方向都实测过。

真正需要改方案的只有 11.1 的第 1 条（持有点选错会让服务起不来）与第 7 条（路径冲突必须上移到 core）；
其余都是「方案没说清落点」或「落地时发现更严的约束已经在别处守着了」。

---

## 附：与北极星类产品的关系

本方案与 [`polaris-capabilities-and-design.md`](polaris-capabilities-and-design.md)
的关系是**互补而不重叠**：Polaris 的定位是「Iceberg 的 catalog 服务」，
没有 Paimon 侧；本服务端的定位是「**同一个元数据面上同时服务两种表格式**」，
因此把 Iceberg 做成同一份数据模型上的第二个协议面，而不是再引入一个外部组件。
