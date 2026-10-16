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

### 4.7 优化功能顺带通了

`paimon_table_optimization` 与表 1:1、`paimon_optimization_job_item` 按表记账，
这套结构对 Iceberg 表同样成立，只是三项动作在 optimizer 侧换成 Iceberg procedures
（`rewrite_data_files` / `expire_snapshots` / `remove_orphan_files`）。
`OptimizationOptions.KNOWN_KEYS` 需要补 Iceberg 专属参数
（`target-file-size-bytes` / `min-input-files` / `delete-file-threshold`…），
并与 `paimon_optimization_job_item.metrics_json` 的指标口径对齐——
这是「表统一」最直接的收益：**优化功能不用为 Iceberg 重写一遍**。

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
  因此 Iceberg 路径只走 RBAC 这一路；Paimon 特有的
  `POST /v1/{prefix}/permissions/grant`（官方 Management API）保持 Paimon 专属。
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
`EXTERNAL + ICEBERG`（外部 Iceberg 仓库入口）；namespace 层级树控制台；
优化三项动作的 Iceberg procedures。

**不做（并显式声明不支持）**

`plan` / `tasks`（扫描规划要一个常驻执行引擎，是另一个产品的份量）、
`sign`（远程签名 KMS）、`labels` / `read-restrictions`（新 spec 的实验特性）、
Iceberg 的 `delete namespace` 级联删表（规格要求 namespace 非空时回 409
`NamespaceNotEmptyError`，本方案遵循规格，不做静默级联）。

---

## 7. 控制台改动（效果图见 `outputs/iceberg-rest-catalog/mockup.html`）

| 页面 | 改动 |
| --- | --- |
| 新建 Catalog 弹窗 | 在「名称」与「类型」之间插入**「表格式」**选择（Paimon / Iceberg），选项下方带一行影响面说明：选 Iceberg 后客户端只能走 Iceberg REST 路径、表详情页签会不同；**创建后不可改** |
| Catalog 列表 | 新增「格式」列（`Paimon` / `Iceberg` 徽标）+ 顶部格式筛选；「类型」列保持 INTERNAL/EXTERNAL |
| Catalog 详情 | 新增「协议与能力」卡片：本 catalog 的基址（`/v1/{prefix}`）、协议版本、**已声明端点清单**与**不支持的能力**（把 §6 的边界摆到页面上，排障时不用去读文档） |
| 表详情 | 页签随格式伸缩：Iceberg 表 = 详细信息 / 快照 / 标签 / 分支 / 文件列表 / 存储优化（**没有**分区、消费者、语义视图、数据访问的 Paimon 语义）；「详细信息」多两行：`metadata-location`、`format-version`，并在「metadata 版本」处给一个看原始 JSON 的入口（复用现有 `JsonBlock`） |
| 新建表表单 | 按格式换形态：Iceberg 走「字段（含 id、required、doc）+ 分区 transform + 可配 format-version / properties」，Paimon 保持现状（`partitionKeys` 表达式 + 主键） |
| 目录浏览 | 库（namespace）一期平铺显示点号拼接名，不做层级树 |
| 存储优化 | 对 Iceberg 表可用其三项动作与策略表单，参数键按格式切换（§4.7） |

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
| **双客户端端到端** | Spark 各连一次：Paimon `spark-paimon-rest-e2e.md` 那套照旧；Iceberg 侧新建 `docs/spark-iceberg-rest-e2e.md`——建 namespace → 建表 → INSERT → SELECT → 快照/分支/标签 → 删表，落一份实测记录 |
| 错误模型契约测试 | Iceberg 路径下故意触发 404/409，断言响应体是 `{error:{type,code}}` 形状 |
| 行大小守门 | 既有 `everyTableFitsTheMysqlRowSizeLimit` 自动覆盖新列（§4.3） |
| 投影一致性自检 | 造一次「文档与投影行不一致」，断言自检报出来（**变异测试**，不空跑） |
| 控制台 `verify-console.py` | 新增几组：新建表单有格式选择且提交带 `catalogFormat`；表详情页签随格式伸缩（复用既有的「页签随格式伸缩」用例写法）；`GET /api/console/v1/meta` 的 `catalogFormats` 与服务端枚举一致 |

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

## 附：与北极星类产品的关系

本方案与 [`polaris-capabilities-and-design.md`](polaris-capabilities-and-design.md)
的关系是**互补而不重叠**：Polaris 的定位是「Iceberg 的 catalog 服务」，
没有 Paimon 侧；本服务端的定位是「**同一个元数据面上同时服务两种表格式**」，
因此把 Iceberg 做成同一份数据模型上的第二个协议面，而不是再引入一个外部组件。
