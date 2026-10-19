# 通用表（Generic Table）设计：把 parquet / orc / lance / vortex 放进同一个目录

> 状态：**设计稿，未实现**（本文只给方案，不含代码）。
> 对标：Apache Polaris 的 Generic Table（1.2 起 Beta，至 main 分支仍是 Beta）。
> 范围：服务端 4 个端点 + 4 种格式的 Spark 3.5 只读接入 + 控制台只读视图；
> Flink / StarRocks 的接入可行性另见 §15（不打进 v1）。
> 结论：**能借鉴，但不能照抄。** Polaris 的 generic table 是「名字 + 格式 + 位置 + 属性」的信封，
> schema 与提交全交给引擎；我们这四种格式里，parquet / orc 连 schema 都要靠扫文件尾部，
> lance / vortex 的连接器还带 native 库。设计的关键不是抄那 5 个字段，而是把
> **「catalog 知道什么、不知道什么、不假装知道什么」**这条线划清楚。

---

## 0. 能力边界（先看这张表）

| 能力 | v1 | 说明 |
| --- | --- | --- |
| 建表 / 删表 / 列出 / 读取元数据 | ✅ | 4 个端点，Polaris 同形 |
| 四种格式 `parquet` / `orc` / `lance` / `vortex` | ✅ | 格式是可配的允许清单，不是硬编码枚举 |
| Spark SQL `SELECT * FROM cat.ns.t` | ✅ | `lakehouse-rest-spark` 里新增 `LakehouseCatalog` |
| Flink 读取（仅 parquet / orc） | **v2** | 客户端 `Catalog` SPI，只读；服务端零改动（§15） |
| StarRocks 读取 | ❌ **不做适配** | 没有可扩展的 catalog 类型，只能走 `FILES()` 表函数（§15） |
| 通过 catalog 写入（`INSERT` / `CTAS`） | ❌ | 没有提交协调，先不给假承诺（§6.5） |
| 改名 / 改位置 / 改属性 | ❌ | 与 Polaris 一致：drop + create（§4.4） |
| schema 作为权威元数据 | ❌ | 只在创建时接受一份「快照」，不校验、不演进（§5.3） |
| 权限 | ✅ | 复用 `TABLE_CREATE/DROP/LIST/READ_PROPERTIES`，不新增权限词（§4.5） |
| 凭据下发（vended credentials） | ✅ | 复用 Iceberg 侧那套；**这是本特性唯一的数据面控制点**（§4.5） |
| 数据文件删除（purge） | ❌ | drop 只删元数据（§3.4 不变量 5） |
| 优化 / 统计 / 格式转换 | ❌ | 服务端不解释这些表的数据，谈不上优化（§13） |

---

## 1. Polaris 怎么做的（事实基线）

### 1.1 数据模型

`GenericTable` 只有 5 个字段，其中 `name` 与 `format` 必填：

| 字段 | 必填 | 含义 |
| --- | --- | --- |
| `name` | ✅ | 命名空间内唯一 |
| `format` | ✅ | 自由字符串，官方举例 `delta` / `csv`；**不是枚举** |
| `base-location` | ❌ | 表的数据根路径；不给就由客户端自己管位置 |
| `properties` | ❌ | 自由键值，**没有保留键**，解释权归引擎 |
| `doc` | ❌ | 描述 |

规格原文对 `base-location` 有一句关键约束：*「一个包含 base 之外文件（disjoint locations）的表，
不符合当前 Polaris 的 generic table 支持」*。这句话等于承认：catalog 无法验证位置里到底有什么。

### 1.2 API 形状

所有端点挂在 **`/polaris/v1/{prefix}/namespaces/{namespace}/generic-tables`**，不是 Iceberg 的 `/v1`。

| 方法 | 路径 | 语义 | 成功码 |
| --- | --- | --- | --- |
| `GET` | `…/generic-tables` | 列出该命名空间下的通用表（`identifiers` + `next-page-token`） | 200 |
| `POST` | `…/generic-tables` | 创建，返回 `{"table": {…}}` | **200**（不是 201） |
| `GET` | `…/generic-tables/{generic-table}` | 读取，返回 `{"table": {…}, "storage-access-configs": […]}` | 200 |
| `DELETE` | `…/generic-tables/{generic-table}` | 删除元数据 | 204 |

两个额外细节，后面我们会照抄：

- **请求头 `Polaris-Generic-Table-Access-Delegation: vended-credentials`**：客户端声明「我支持凭据下发」。
  规格写明：服务端**要么按这个机制给，要么直接失败**，不能悄悄降级——否则客户端会以为拿到了临时凭据，
  实际拿到的是空。
- **`storage-access-configs`** 是 `[{prefix, config}]` 数组，`prefix` 表示「哪段路径适用这份配置」，
  客户端按最长公共前缀选。这是 Iceberg 侧已有的形状，不是新发明。

### 1.3 它明确不管的事

Polaris 文档自己列的局限（原文要点）：

1. **没有 schema、没有分区信息**——「Limited spec information」；
2. **没有提交协调、没有更新能力**——catalog 除了几个松散字段之外对底层表一无所知，
   delta log 的序列化/反序列化/更新全部发生在客户端；
3. **删除再重建是唯一的修改方式**；
4. **通用表与 Iceberg 表共享命名空间，名字必须唯一**；
5. **走错 API 得到 `TableNotFoundException`**——用 Iceberg 的 loadTable 读通用表，报的是「表不存在」，
   不是「类型不对」。

第 5 条值得单独记：这不是偷懒，是唯一安全的回答。因为 Iceberg 客户端的 loadTable 期待一份
`TableMetadata`，服务端给不出，任何「更友好」的错误都会让客户端去猜。

### 1.4 Polaris 的 Spark 客户端

`org.apache.polaris.spark.SparkCatalog`：一个 Spark `TableCatalog`，内部包 Iceberg 的 REST catalog，
`loadTable` 先试 Iceberg，失败再试通用表；建表时按 `provider` 分流。它自己的限制：

| 限制 | 后果 |
| --- | --- |
| 只支持 Iceberg + Delta | csv / json 等格式在 REST 层能建、在 Spark 里读不了 |
| Delta 不支持 CTAS | `saveAsTable` 也不可用（它依赖 CTAS） |
| 建 Delta 表必须显式 `LOCATION` | 无位置的表建不出来 |
| 不支持 rename、`ALTER TABLE … SET LOCATION` | 只能删了重建 |
| **通用表接口不支持凭据下发** | 客户端必须自带长期密钥——数据面绕过了 catalog |

最后一条是它最实质的缺口，也是我们最容易补上的一处：凭据下发我们在 Iceberg 侧已经做完了。

---

## 2. 四种格式的物理形态（这决定了设计）

| | parquet | orc | lance | vortex |
| --- | --- | --- | --- | --- |
| 目录里有什么 | `*.parquet`（可含 `k=v/` 分区目录） | `*.orc` | `_versions/*.manifest` + `*.lance` | `*.vortex` |
| schema 从哪来 | 文件 footer | 文件 footer（postscript） | manifest + Arrow schema | 文件 footer（Arrow schema） |
| 自带版本 / 提交 | ❌ 无 | ❌ 无 | ✅ version / branch，乐观并发 | ⚠️ 有 layout 版本，但连接器按「文件集合」读 |
| 多文件 schema 演进 | 靠 Spark 的 merge 策略（不可靠） | 同上 | manifest 里一份 | footer 不一致时以最后一份为准 |
| Spark 连接器 | 内置（**DSv1**） | 内置（**DSv1**） | `org.lance:lance-spark-bundle-3.5_2.12`（DSv2） | `dev.vortex:vortex-spark_2.12:0.85.0:all`（DSv2 + JNI） |
| 服务端能否自己读 schema | ✅ `parquet-hadoop` footer | ✅ `orc-core` | ❌ 需 JNI | ❌ 需 JNI |
| 目录列举成本 | 每次查询 LIST 一遍 | 同 | manifest 一次 | 同 parquet |

由此得到三条**不可回避**的结论，后面所有设计都从这里推：

1. **catalog 当不了 schema 的权威。** parquet/orc 的 schema 是「文件们的共识」，不是元数据；
   lance/vortex 的 schema 在引擎侧。任何「服务端存一份 schema 并据此校验」的设计，都会在
   第一次引擎侧演进后变成谎言。
2. **catalog 协调不了提交。** 没有事务日志就没有 CAS 的对象；parquet/orc 甚至没有「提交」这个动作。
   写入一致性只能由写它的引擎负责。
3. **Spark 侧必须按格式分派。** parquet/orc 是 DSv1，lance/vortex 是 DSv2，一套代码打不了天下。

---

## 3. 领域模型

### 3.1 wire 形态

请求体与 Polaris 逐字对齐，另加两个**可选扩展字段**（不写就完全等同 Polaris）：

```json
{
  "name": "events",
  "format": "parquet",
  "base-location": "s3://bucket/warehouse/analytics/events",
  "doc": "埋点明细，由 Flink 每小时落地",
  "properties": { "owner": "data-infra", "partition-keys": "dt" },
  "schema": "{\"type\":\"struct\",\"fields\":[…]}",
  "schema-source": "CATALOG"
}
```

| 扩展字段 | 为什么加 |
| --- | --- |
| `schema` | 控制台要展示列；lance/vortex 服务端读不了 footer，只能由创建者给。**标记为「创建时快照」，不参与任何校验** |
| `schema-source` | 回显 `CATALOG`（创建时给了）或 `ENGINE`（没给，由引擎推断）。两个值都不是「服务端验证过」 |

`schema` 用 Iceberg 的 JSON 形态而不是 Spark DDL：控制台与 BI 都能解析，且与 Iceberg 侧同一个展示组件。

### 3.2 存储形态：复用 `lakehouse_table` + 判别列

三个方案，逐条比：

| 方案 | 同名互斥 | Iceberg 侧风险 | 代价 |
| --- | --- | --- | --- |
| **A. 复用 `lakehouse_table` 加 `table_kind`** | ✅ 现有 `uk_table (catalog_id, database_id, name)` 直接生效，无并发窗口 | ⚠️ 所有 Iceberg 侧读必须加过滤，漏一处 = 客户端看到读不了的表 | 4 个新列，其中 3 列对其它 kind 为 null |
| B. 新表 `lakehouse_generic_table` | ❌ 唯一约束管不到另一张表，要跨表检查 + 仍有并发窗口 | ✅ 零改动 | 列表要 union；重名检查有 TOCTOU |
| C. 新表 + 名字登记表 | ✅ | ⚠️ 改名路径要同步维护登记表，动到已测过的 Iceberg rename | 多一张表、多一条不变量 |

选 **A**。理由不是「改动小」，而是 **B 放弃的那条不变量（同名互斥）恰恰是 Polaris 唯一硬性规定的一条**：
通用表与 Iceberg 表共享命名空间、名字必须唯一。用单表 + 唯一约束是唯一能在并发下真正保证它的做法；
C 也能保证，但要改动 rename 这条已经稳定的路径，收益不抵风险。

A 的代价用两条纪律压住：

1. Iceberg 侧**不允许**直接调 `tableRepository`，只走 `IcebergTableDao.requireIceberg(...)` 与
   `listIcebergTables(...)` 两个方法（内部固定 `table_kind = 'ICEBERG'`）；`grep` 能查、review 能看。
2. `IcebergTableService.load` 遇到 `table_kind = 'GENERIC'` 时返回 **404 + `NoSuchTableException`**，
   与 Polaris 同语义（§1.3 第 5 条）。**不允许**返回 400 或「这是通用表」——客户端会去猜。

### 3.3 列清单

`lakehouse_table` 新增 4 列（沿用「加列不建表」的既有先例：Iceberg 侧那四列就是这么加的）：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `table_kind` | `varchar(16) not null` | `PAIMON` / `ICEBERG` / `GENERIC`。**与 `table_type` 是两件事**（见下） |
| `generic_format` | `varchar(32)` | `parquet` / `orc` / `lance` / `vortex`；仅 `GENERIC` 行有值 |
| `properties_doc` | `mediumtext` | 创建时传入的 `properties`（JSON）。选 mediumtext 不选 `varchar(8192)`：本工程被 `Data too long` 教训过一次，属性虽然通常很小，但没有上界 |
| `doc_text` | `varchar(2048)` | 描述 |

已有列直接复用：`path_value` 就是 `base-location`（本来就是「表的物理根路径」），`external_flag = true`
（通用表按定义都是外部写入），`schema_id = 0`，`schema_doc` / `metadata_doc` / `metadata_location` 为 null。

> **`table_kind` 不是 `table_type`。**
> `table_type` 是 Paimon schema options 里的 `type`（`table` / `format-table` / `object-table` / `lance-table`），
> 是 **Paimon 协议内部**的表种类；`table_kind` 是「这一行由哪个面解释」。两者名字像，语义完全无关，
> 列注释与实体注释都必须写明，否则下一个人一定会混。

### 3.4 不变量

1. **同名互斥**：同一 `(catalog_id, database_id)` 下，表 / 通用表 / 视图三者名字两两不冲突。
   表与通用表靠 `uk_table`，视图靠一条显式检查（`lakehouse_view` 是另一张表，唯一约束管不到；
   现有 Iceberg 表与视图之间本来就有这个缺口，本特性不扩大它，只保证「新增的通用表不撞视图」）。
2. **位置不重叠**：同一 catalog 内，任何两张表（含通用表）的 `path_value` 互不为前缀。
   **按路径段边界比较，不是字符串前缀**：`s3://b/a` 与 `s3://b/ab` 不冲突，`s3://b/a` 与 `s3://b/a/t` 冲突。
   归一化规则：scheme 与 host 小写，去掉末尾 `/`，路径部分大小写敏感（S3 的 key 是敏感的）。
3. **位置在允许范围内**：复用 `StorageRuntimePolicy` 的允许位置校验，不另写一套。
4. **`format` 与 `base-location` 创建后不可变**：v1 没有 update 端点，改就 drop + create。
5. **drop 只删元数据，不删数据**：响应 204，事件属性里带 `dataRetained=true`。
   不给 `?purge=true`——见 §13。
6. **通用表不产生派生行**：不进 `lakehouse_snapshot` / `lakehouse_table_schema` / `lakehouse_table_optimization`，
   不出现在 Iceberg 的 `/tables`，不参与优化调度。

### 3.5 位置重叠的判定

```text
overlaps(a, b):
    na, nb = normalize(a), normalize(b)          # scheme/host 小写、去尾斜杠
    return na == nb
        || na.startsWith(nb + "/")
        || nb.startsWith(na + "/")
```

三个必须写进测试的边界：

- `s3://b/a` vs `s3://b/a/` → 冲突（归一化后相等）
- `s3://b/a` vs `s3://b/ab` → 不冲突（段边界）
- `s3://b/a` vs `s3://B/a` → 冲突（host 大小写不敏感）

检查范围是**同一个 catalog**，跨 catalog 不查：不同 catalog 可能挂到同一个仓库根（测试环境常见），
跨 catalog 比对会把「两个环境共用一块盘」误判成冲突；同时跨 catalog 的比对也没有事务边界可用。

**已知窗口**：两个并发请求用不同名字指向同一个位置，可能都通过检查。这不是数据损坏（只是两个别名
指向同一份文件），且加分布式锁的故障面远大于收益。写进 §12 的风险表，不假装解决了。

### 3.6 升级 SQL

`sql/schema-mysql.sql` 是**全量初始化脚本**（`ddl-auto=validate`，重复执行会因表已存在而失败），
所以存量库要单独执行一次：

```sql
alter table lakehouse_table add column table_kind varchar(16) null after name;
alter table lakehouse_table add column generic_format varchar(32) null after table_kind;
alter table lakehouse_table add column properties_doc mediumtext null after schema_doc;
alter table lakehouse_table add column doc_text varchar(2048) null after properties_doc;

update lakehouse_table t join lakehouse_catalog c on c.id = t.catalog_id
   set t.table_kind = case when c.catalog_format = 'ICEBERG' then 'ICEBERG' else 'PAIMON' end
 where t.table_kind is null;

alter table lakehouse_table modify column table_kind varchar(16) not null;
create index ix_table_kind on lakehouse_table (catalog_id, table_kind);
```

先加可空列、回填、再收紧，是为了让「加列」与「回填」分成两次可回滚的操作；顺序反过来会在
存量行上直接失败。索引 26 → 27。

---

## 4. API 设计

### 4.1 端点

| 方法 | 路径 | 权限 | 语义 |
| --- | --- | --- | --- |
| `GET` | `/v1/{prefix}/namespaces/{namespace}/generic-tables` | `TABLE_LIST`（namespace 作用域） | 只列通用表，**不含** Iceberg 表；支持 `page-token` / `page-size` |
| `POST` | `/v1/{prefix}/namespaces/{namespace}/generic-tables` | `TABLE_CREATE` | 创建；返回 200 + `{"table": …}`（与 Polaris 一致，不用 201） |
| `GET` | `/v1/{prefix}/namespaces/{namespace}/generic-tables/{generic-table}` | `TABLE_READ_PROPERTIES` | 读取；不存在或是 Iceberg 表 → 404 |
| `DELETE` | `/v1/{prefix}/namespaces/{namespace}/generic-tables/{generic-table}` | `TABLE_DROP` | 204；数据保留 |

`namespace` 用现成的 `NamespacePaths`（`0x1F` 与点号都认）。**不提供** `POST …/{t}`（更新）：
不登记、不返回 405，靠授权拦截器的「未登记 → 403」失败关闭（与 `register-view` 同处理）。

### 4.2 为什么挂在 `/v1` 而不是 `/polaris/v1`

Polaris 用 `/polaris/v1` 是因为它要**声明自己超出了 Iceberg REST 规格**，同时不污染 `/v1` 的语义。
我们照抄这个路径会付出两笔额外成本：

- 授权拦截器按 `/v1/**` 失败关闭，另起 `/polaris/v1` 要第二套拦截与第二套路径解析；
- 我们的客户端是自己写的 Spark 插件，不需要与 `polaris-spark` 二进制兼容。

所以主路径挂在 `/v1`，与现有 26 个 catalog 端点同一套授权与错误处理。
**但保留一个开关** `lakehouse.rest.generic-table.polaris-path-alias=true`：把同一组 handler 再挂一份到
`/polaris/v1/…`，让现成的 Polaris curl 脚本、`polaris-spark` 客户端能直接指过来。默认关闭——
多挂一个路径就是多一个要授权的入口，不需要时不开口子。

### 4.3 响应与凭据下发

```json
{
  "table": {
    "name": "events",
    "format": "parquet",
    "base-location": "s3://bucket/warehouse/analytics/events",
    "doc": "埋点明细",
    "properties": { "owner": "data-infra" },
    "schema": null,
    "schema-source": "ENGINE"
  },
  "storage-access-configs": [
    { "prefix": "s3://bucket/warehouse/analytics/events",
      "config": { "s3.access-key-id": "…", "s3.secret-access-key": "…", "s3.session-token": "…" } }
  ]
}
```

- 只有请求头 `Polaris-Generic-Table-Access-Delegation: vended-credentials` 存在时才返回第二段；
- **请求了但给不出 → 400**，不降级为空数组（规格要求「要么给、要么失败」）；
- 实现直接复用 `IcebergCredentialService`：它已经处理了「按位置找存储配置、按最具体前缀下发」，
  通用表只是把作用域从「表的 metadata 目录」换成 `base-location`。

### 4.4 错误语义

| 场景 | 码 | 消息要点 |
| --- | --- | --- |
| 未知 `format` | 400 | 列出当前允许清单（与事件类别 400 同一个手法：**不静默接受**） |
| `format` 允许但缺 `base-location` | 400 | 「该格式必须给位置；catalog 不替你选路径」 |
| 位置不在允许范围 | 400 | 指出 catalog 的允许前缀 |
| 位置与已有表重叠 | 409 | **点名冲突的表**，不要只说 conflict |
| 名字已存在（表 / 通用表 / 视图） | 409 | 指出已存在的类型 |
| namespace 不存在 | 404 | `NoSuchNamespaceException` |
| 走错 API（用 Iceberg loadTable 读通用表） | 404 | `NoSuchTableException`，与 Polaris 同 |
| 更新 / 改名 | — | 端点不存在，授权层 403（失败关闭） |

### 4.5 权限与安全边界：这条必须写清楚

映射到**现有**权限词，不新增（Polaris 也是复用 `TABLE_*`）：

| 端点 | 权限 |
| --- | --- |
| 列表 | `TABLE_LIST` |
| 创建 | `TABLE_CREATE` |
| 读取 | `TABLE_READ_PROPERTIES`（与 Iceberg `loadTable` 同口径；Polaris 的通用表复用 `TABLE_*` 四项、其中元数据读取对应 `TABLE_FULL_METADATA`，我们不跟——同一语义两个档位只会让授权更难解释） |
| 删除 | `TABLE_DROP` |

> **没有凭据下发时，RBAC 只保护「目录」，不保护「数据」。**
> 通用表的数据面不经过服务端：客户端拿到 `base-location` 后直连对象存储。`TABLE_READ_DATA`
> 在这个链路上**没有执行点**——授了它不会让数据更安全，不授它也不会让数据更安全。
> 真正的数据面控制点是 `storage-access-configs`：临时凭据的作用域就是 `base-location`，
> 拿不到凭据就拿不到数据。所以凭据下发在本特性里是 **v1 必做**，不是可选增强；
> 关掉它等于宣布「能看元数据的人可以读全部数据」，控制台要在建表页明确提示这一点。

### 4.6 `/v1/config` 的能力声明

两处改动：

- `endpoints` 增加 4 条（`GET/POST …/generic-tables`、`GET/DELETE …/generic-tables/{generic-table}`）。
  这是 Iceberg 规格里唯一的扩展点，客户端按字符串匹配，不认识的会忽略。
- `defaults` 增加 `lakehouse.generic-table.formats = "parquet,orc,lance,vortex"`。
  让**允许清单只有一份**（服务端配置），Spark 插件与控制台都从 `/v1/config` 读，不各配一遍。

> 为什么放 `defaults` 而不是自定义顶层字段：`defaults` 按规格就是自由键值，Iceberg 客户端会把不认识的键
> 原样带在 properties 里、不影响任何行为；自定义顶层字段要靠 `ignoreUnknown` 兜底，各版本行为不一定一致。

---

## 5. 服务端格式注册表

### 5.1 `GenericFormat`

```java
public enum GenericFormat {
    PARQUET("parquet", RequiresLocation.YES, SchemaProbe.FOOTER,  SparkConnector.BUILTIN),
    ORC    ("orc",     RequiresLocation.YES, SchemaProbe.FOOTER,  SparkConnector.BUILTIN),
    LANCE  ("lance",   RequiresLocation.YES, SchemaProbe.NONE,    SparkConnector.EXTERNAL),
    VORTEX ("vortex",  RequiresLocation.YES, SchemaProbe.NONE,    SparkConnector.EXTERNAL);
}
```

`format` 在 wire 上是**自由字符串**（Polaris 的 `delta`/`csv` 先例说明它必须可扩展），
但写入时按 `lakehouse.rest.generic-table.formats` 的允许清单校验。这样：

- 部署没装 lance/vortex 连接器时，把这两个从清单里去掉，用户在建表阶段就得到明确拒绝，
  而不是在 Spark 里 `SELECT` 时报 `ClassNotFound`；
- 清单里加一个新格式不需要改枚举（改配置 + 客户端有连接器即可），但需要引擎侧真的能读。

### 5.2 创建路径的校验顺序

顺序是设计的一部分（决定用户先看到哪个错误）：

```text
0. generic-table.enabled                     → 否则控制器不注册（404），先于一切
1. catalog 存在且 format=ICEBERG            → 否则 404 / 400（Paimon catalog 不支持通用表）
2. namespace 存在                            → 否则 404
3. format 在允许清单内                        → 否则 400（列出清单）
4. base-location 存在且通过 StorageRuntimePolicy → 否则 400
5. 名字未被表/通用表/视图占用                  → 否则 409
6. 位置不与同 catalog 已有表重叠               → 否则 409（点名冲突表）
7. 落库（uk_table 兜底并发）                   → 冲突时 409
```

前两步先做，是因为「catalog 不存在」与「格式不支持」的修复动作完全不同（一个找管理员、一个换格式）。

### 5.3 为什么 v1 不做服务端 schema 探测

parquet/orc 用 `parquet-hadoop` / `orc-core` 读 footer 是可行的（只读几十 KB），但代价是：

- 服务端 classpath 多两个数据面依赖，且要处理各版本 footer 的兼容；
- 控制台每打开一个表就要出网读一次对象存储——把一个「元数据服务」变成「会扫数据文件的服务」，
  部署侧的出网策略、限流、超时都要重新评估。

所以 v1：**`schema` 只作为创建时的快照存储与展示**，字段旁永远标注来源（`CATALOG` / `ENGINE`），
控制台在没有快照时显示「由引擎推断」，并给一条可直接复制的 `DESC EXTENDED` 命令。
v2 再评估「按需探测一次」（按钮触发、结果缓存、开关默认关闭）。

---

## 6. Spark 接入

### 6.1 结构

```text
lakehouse-rest-spark/shared/src/main/{java,scala}/io/github/melin/lakehouse/rest/spark/catalog/
  LakehouseCatalog.java         // TableCatalog + SupportsNamespaces：分流器
  GenericTableClient.java       // 4 个端点的 HTTP（含凭据下发头、/v1/config 缓存）
  GenericTableHandle.java       // 响应 → 不可变元数据（name/format/location/properties/doc/schema）
  GenericTableFactory.java      // format → Table
  ProviderTables.java           // ServiceLoader<TableProvider> 适配（lance / vortex）
  SparkCatalogOptions.java      // 配置解析
org/apache/spark/sql/lakehouse/FileFormatTable.scala   // ← 唯一一处依赖 Spark 内部 API 的类
```

`LakehouseCatalog` **内部持有一个 Iceberg `SparkCatalog` 实例并委托**，不继承：继承会把 Iceberg 的
`initialize` / `setConf` 时序变成我们的问题，而组合只要求「先初始化它，再把四个方法转过去」。

### 6.2 `loadTable` 的回退顺序

```text
loadTable(ident):
    try   → iceberg.loadTable(ident)          # 覆盖 99% 的调用，且走 Iceberg 自己的元数据缓存
    catch NoSuchTableException
          → generic = client.get(ns, name)    # 404 时把 Iceberg 的异常原样抛出
          → GenericTableFactory.create(generic)
```

`listTables` 返回两者并集并按名字排序（顺序稳定，避免 `SHOW TABLES` 抖动）。
`tableExists` 先问 Iceberg（本地缓存命中率高），再问通用表，并对「不存在」做 60s 负缓存——
Spark 的 DDL 路径会反复调它。

### 6.3 parquet / orc：`FileTable` 子类

这两个格式在 Spark 里是 **DSv1**，没有公开的 DSv2 `TableProvider` 可用；而自研 `Scan` 需要
`ParquetRowConverter` / `OrcDeserializer` 这些 `private[sql]` 类才能把文件解成 `InternalRow`。
唯一务实的做法是**继承 Spark 的 `FileTable`**（它已经把分区发现、列裁剪、谓词下推、metrics 全做完了）：

```scala
package org.apache.spark.sql.lakehouse            // ← 必须在这个包下才能看见 private[sql]

private[lakehouse] class ParquetGenericTable(...)
    extends FileTable(new ParquetFileFormat, options, Seq(location), Some(schema), "parquet", spark)
```

四个要点：

1. **这个类用 Scala 写，不用 Java。** `private[sql]` 只在 Scala 编译期成立；用 Java 写能编过
   （字节码里是 public），但等于绕过检查——Spark 升级删掉这个类时会**静默**变成 `NoClassDefFound`，
   而不是编译失败。
2. **必须按 Spark 小版本锁**（当前 3.5.x）。`FileTable` 的构造签名在 4.0 变过（分区参数），
   这是本设计唯一一处「升级 Spark 必须回来看」的地方，单独列进 §12。
3. **schema 从哪来**：优先用创建时快照；没有就用 `ParquetFileFormat.inferSchema` 扫一遍（与
   `spark.read.parquet` 完全同路径），结果缓存 60s。
4. **分区发现与列裁剪是白捡的**：`FileTable` 已经把 `k=v/` 目录的分区列、`_metadata.file_path`
   这类排查用的列、以及列裁剪与谓词下推都接好了——这些正是自研 `Scan` 要重写一遍的东西。

### 6.4 lance / vortex：`ServiceLoader<TableProvider>`

这两个格式都有 DSv2 连接器，且都实现了 `TableProvider` + `DataSourceRegister`。所以不写死类名，
按 `shortName()` 找：

```text
provider = ServiceLoader.load(TableProvider.class, ctxClassLoader)
                     .first(p -> p.shortName().equalsIgnoreCase(format))
if (provider == null) → 报错：「classpath 里没有 <format> 的 Spark 连接器」，并列出已发现的 shortName
table = provider.getTable(schema, partitioning, Map("path" -> baseLocation) + properties)
```

这样做的好处：

- **零编译期依赖**：lance/vortex 的 JNI 与 native 库不进服务端、也不进插件的编译依赖；
- 换连接器版本不用改我们的代码；
- 找不到时的错误可诊断（列出 classpath 上究竟有哪些 provider），比 `ClassNotFound` 友好得多。

两个必须写进文档的运维细节：

- **vortex 必须用 `-all` 分类器**：`--packages dev.vortex:vortex-spark_2.12:0.85.0` 会解析到 thin jar，
  运行时 `NoClassDefFoundError: dev/vortex/relocated/...`——`--packages` 选不了 classifier，只能 `--jars`。
- **native 库的平台矩阵**：vortex 的 `-all` jar 含 Linux x86_64/aarch64、macOS aarch64、Windows x86_64。
  ARM 镜像 + x86 jar 的组合会在第一次 `SELECT` 时炸，构建镜像时要对齐。
- **Spark 3.5 不需要 `VortexSessionCatalog`**：那个类是为了让 *内置* session catalog 能解析 DSv2-only 的表；
  我们直接把 `Table` 造出来，绕过了 session catalog 的解析。

### 6.5 写路径：v1 只读，给三条逃生口

`SupportsWrite` 不实现。`INSERT INTO` / CTAS 会得到 Spark 的
「Table does not support writes」——**信息量不够**，所以 `createTable` 在「无 LOCATION 的 CTAS」上
提前拒绝，并给出三条替代：

| 逃生口 | 适用 | 代价 |
| --- | --- | --- |
| ① 用格式自己的写入路径：vortex 的 path catalog（`INSERT INTO vortex.\`/path/to/data\``）、lance 的 `LanceNamespaceSparkCatalog` | 单格式批量写 | 绕过 RBAC，靠存储凭据兜底 |
| ② 先写 Iceberg / Paimon，再用引擎的格式转换 | 需要事务与演进 | 多一步作业 |
| ③ 外部引擎写，catalog 只登记 | 生产常态（Flink/Spark 作业写 S3） | catalog 不知道什么时候写完 |

v2 再考虑「委托写」：`SupportsWrite` 转发给格式连接器，但不做提交协调，只加一个
`POST …/generic-tables/{t}/refresh` 让 catalog 重新读一次 schema/version。前提是想清楚
「写成功但 refresh 失败」的语义——现在没有想清楚，所以不做。

### 6.6 会话配置

```properties
spark.sql.catalog.lakehouse=io.github.melin.lakehouse.rest.spark.catalog.LakehouseCatalog
spark.sql.catalog.lakehouse.uri=http://catalog:8080
spark.sql.catalog.lakehouse.warehouse=demo
spark.sql.catalog.lakehouse.credential=<client-id>:<client-secret>
spark.sql.catalog.lakehouse.scope=PRINCIPAL_ROLE:ALL
spark.sql.catalog.lakehouse.token-refresh-enabled=true
spark.sql.catalog.lakehouse.header.X-Iceberg-Access-Delegation=vended-credentials
spark.sql.catalog.lakehouse.generic-table.credential-vending=vended-credentials
spark.sql.catalog.lakehouse.generic-table.cache-ttl=60s
# 留空 = 从 /v1/config 的 defaults 读；只在需要覆盖时才写
spark.sql.catalog.lakehouse.generic-table.formats=
```

`uri` 只到根（`http://catalog:8080`）：Iceberg 客户端会自己拼 `/v1/…`，我们的客户端拼
`/v1/{warehouse}/…`；带上 `/v1` 会出现 `/v1/v1`。

### 6.7 SQL 示例

```sql
USE lakehouse.analytics;

-- parquet：登记一个外部写入的目录（不写任何文件）
CREATE TABLE events (id BIGINT, dt STRING, payload STRING)
USING parquet LOCATION 's3://bucket/warehouse/analytics/events';

-- orc
CREATE TABLE clicks (id BIGINT, url STRING)
USING orc LOCATION 's3://bucket/warehouse/analytics/clicks';

-- lance（需要 lance-spark bundle 在 classpath）
CREATE TABLE embeddings (id BIGINT, vec ARRAY<FLOAT>)
USING lance LOCATION 's3://bucket/warehouse/analytics/embeddings.lance';

-- vortex（需要 vortex-spark-*-all.jar）
CREATE TABLE logs (ts TIMESTAMP, level STRING, msg STRING)
USING vortex LOCATION 's3://bucket/warehouse/analytics/logs';

SELECT count(*) FROM events WHERE dt = '2026-10-01';
DESC EXTENDED events;                       -- 看 format / location / schema-source
```

普通 `CREATE TABLE t (id INT)`（不写 `USING`、不给 `LOCATION`）会**报错**并提示两条出路：
写 `USING iceberg`，或给出 `LOCATION`。这一条是刻意的：Spark 的默认 provider 是 `parquet`，
若照单全收，所有人都会在自己以为建了 Iceberg 表的地方建出一张 parquet 目录表。

### 6.8 与 Polaris Spark client 的差异

| | Polaris | 本设计 |
| --- | --- | --- |
| 格式 | 仅 Iceberg + Delta | Iceberg + parquet/orc/lance/vortex（可配） |
| 凭据下发 | ❌ 通用表不支持 | ✅ 复用 Iceberg 侧那套 |
| CTAS | ❌ | ❌（同） |
| 无 LOCATION 建表 | ❌ | ❌（同，且提前报错） |
| rename / SET LOCATION | ❌ | ❌（同） |
| Spark 4 | 只到 3.5 | 3.5 优先；4.x 需要换 vortex `_2.13` 与 Iceberg 4 runtime，列为 v2 |

---

## 7. 控制台

- **浏览树**：namespace 下第三种节点「通用表」，带格式徽标（parquet / orc / lance / vortex）。
  与「表」「视图」并列，但从 `GET …/generic-tables` 单独取——不能靠 Iceberg 的 `/tables` 拿。
- **详情页**（`GenericTableView.vue`，路由 `/generic-tables/...`，与 `IcebergTableView.vue` 同构）：
  格式、位置（可复制 + 一键生成 `CREATE TABLE` 片段）、描述、属性表、
  schema（有快照则展示并标注「创建时快照」，否则「由引擎推断」）、事件时间线（按 target 过滤）。
- **建表页（v1.1，v1 只读）**：格式下拉读 `/v1/config` 的 `defaults["lakehouse.generic-table.formats"]`；
  位置必填、前端做一次「段边界前缀」的软校验（服务端仍会硬校验）；
  勾选「下发临时凭据」时给出「关掉它 = 能看元数据的人可读全部数据」的提示。
- **优化页**：通用表不出现（`TableOptimizationService` 固定过滤 `table_kind='ICEBERG'`），
  并在页脚加一句「通用表不参与优化：服务端不解释它们的提交」。
- **事件页**：新增类别 `GENERIC_TABLE`，类型下拉随之多两项（§8）。

---

## 8. 事件与审计

新增 1 个类别、2 个类型（与 Polaris 的类别名对齐，但只保留 AFTER + 变更）：

| 类别 | 类型 | 发布点 | 属性 |
| --- | --- | --- | --- |
| `GENERIC_TABLE` | `generic_table.created` | `IcebergGenericTableService.create` | `format`、`path`、`schemaSource` |
| `GENERIC_TABLE` | `generic_table.dropped` | `IcebergGenericTableService.drop` | `format`、`path`、`dataRetained=true` |

计数影响：类别 10 → **11**，类型 38 → **40**。以下位置都要同步（本工程的机械一致性要求）：

- `CatalogEventCategory` / `CatalogEventType`；
- 控制台 `EventsView.vue` 的 `CATEGORIES` / `TYPES`；
- `docs/event-framework.md` §1.1 映射表（Polaris 的 `GENERIC_TABLE` 从 ❌ 改 ✅）与 §1.2 清单；
- `verify-console.py` 第 15 组的条数与文档计数（若为通用表新增校验项，「16 组 72 项」要同步改）。

**不发布** `generic_table.loaded`：那是读路径，与「只收变更」的判据冲突（同一个理由已经写在
`event-framework.md` §1.3）。

---

## 9. 影响面清单

| # | 文件 | 改动 |
| --- | --- | --- |
| 1 | `core/domain/entity/TableEntity.java` | +4 列 + `TableKind` 注释 |
| 2 | `core/support/TableKind.java`（新） | `PAIMON/ICEBERG/GENERIC`，null 时按 catalog 格式回落 |
| 3 | `core/domain/repo/TableRepository.java` | 新增 `requireIceberg` / `listIceberg` / `listGeneric` 派生方法 |
| 4 | `core/dto/GenericTableDtos.java`（新） | 5 个 record，字段名与 Polaris 逐字一致 |
| 5 | `protocol-iceberg/web/GenericTableController.java`（新） | 4 个映射 |
| 6 | `protocol-iceberg/service/IcebergGenericTableService.java`（新） | 校验顺序、位置重叠、事件发布 |
| 7 | `protocol-iceberg/service/IcebergTableService.java` | `list`/`load` 过滤 GENERIC |
| 8 | `protocol-iceberg/service/IcebergNamespaceService.java` | drop 前的「非空」检查要把通用表算进去 |
| 9 | `core/config/CatalogAccessRules.java` | `namespaces()` 加 `generic-tables` 分支（4 条） |
| 10 | `core/config/RestServerProperties.java` | `genericTable` 配置段（enabled / formats / 别名 / 重叠检查） |
| 11 | `core/web/ConfigController.java` | `endpoints` +4，`defaults` +1 |
| 12 | `core/event/CatalogEventCategory.java` / `CatalogEventType.java` | +1 类 / +2 型 |
| 13 | `server/service/ManagementCatalogService.java` | 删 catalog 时级联删通用表行 |
| 14 | `server/service/TableOptimizationService.java` | 固定排除 GENERIC |
| 15 | `console/api/iceberg.js`、`IcebergBrowseView.vue`、`GenericTableView.vue`（新）、`router` | 浏览 + 详情 + 建表 |
| 16 | `spark/.../catalog/**`（6 个类）+ `org/apache/spark/sql/lakehouse/FileFormatTable.scala` | Spark 接入 |
| 17 | `spec/polaris-generic-tables-api.yaml`（新） | 从 Polaris 仓库 vendored（Apache-2.0，保留许可头） |
| 18 | `scripts/verify-iceberg-endpoints.py` | 新增 `EXTENSION_ENDPOINTS`（4 条）与 `EXTENSION_TOTAL`；`SUPPORTED_TOTAL` 仍为 26 |
| 19 | `scripts/verify-console.py` | 事件计数、通用表页面断言 |
| 20 | `sql/schema-mysql.sql` | 重生成（+4 列 +1 索引，表数仍 23） |
| 21 | `lakehouse-rest-flink/`（新模块，v2） | `LakehouseCatalog` / `LakehouseCatalogFactory` / `GenericTableClient` / `SchemaProbe` / `FlinkTableMapper`；只读 parquet / orc（§15.8） |
| 22 | 根 `pom.xml` | `modules` 增加 `lakehouse-rest-flink`（v2；不影响服务端构建） |
| 23 | 文档 8 份 | 见下 |

文档同步：`README.md`（文档索引 + 端点/用例数）、`AGENTS.md`（文档索引）、`docs/console.md`（页面 + 组数）、
`docs/data-model.md`（`lakehouse_table` 四列与 `table_kind`）、`docs/event-framework.md`（类别/类型）、
`docs/iceberg-rest-catalog-design.md` §6（端点表 + 扩展端点说明）、
`docs/polaris-capabilities-and-design.md`（Generic Table 从 ❌ 改 ✅）、`docs/maven-module-design.md`
（`lakehouse-rest-spark` 新增 catalog 子包的职责）。

> **注意** `verify-iceberg-endpoints.py` 的失败关闭：`iceberg/web` 下新增控制器的端点会被
> 「实现的端点都已声明」这条直接拦下。所以 `EXTENSION_ENDPOINTS` 不是可选项——不加脚本就红。

---

## 10. 测试计划

| 测试 | 钉住什么 |
| --- | --- |
| `GenericTableApiTests` | 4 个端点的正常路径；`format` 未知 400（消息含清单）；缺 location 400；重名 409；位置重叠 409（含 `a` vs `ab` 的边界）；namespace 不存在 404 |
| `GenericTableIsolationTests` | 建通用表后：Iceberg `/tables` 不含它；`loadTable` 返回 404；`listTables`（Spark）含它；drop namespace 被拒（非空） |
| `GenericTableAuthorizationTests` | 4 个端点 × 权限矩阵（缺权限 403、越权 namespace 403）；未登记的 `POST …/{t}` 403 |
| `GenericTableCredentialTests` | 带 `vended-credentials` 头 → 返回作用域正确的配置；无存储配置 → 400 而不是空数组 |
| `GenericTableEventTests` | 两个事件类型、`dataRetained` 属性、类别过滤 `?category=GENERIC_TABLE` |
| `GenericFormatTests` | 允许清单收窄后建表被拒；`format` 大小写不敏感 |
| `GenericTableSparkTests`（Spark 本地） | parquet/orc 的 `SELECT` / `DESC`；无 LOCATION 的 CTAS 报错信息；rename 明确不支持；lance/vortex 用 `Assumptions.assumeTrue`（连接器不在 classpath 时跳过，不算绿） |
| `verify-iceberg-endpoints.py` | 规格 35 / 声明 26 / 扩展 4，三方一致 |
| `verify-console.py` | 类别 11、类型 40、页面断言 |

---

## 11. 分期

| 期 | 内容 | 为什么这样切 |
| --- | --- | --- |
| **v1** | 服务端 4 端点 + 4 格式 + Spark 只读（parquet/orc 必达，lance/vortex 依赖连接器）+ 凭据下发 + 控制台只读 + 事件 | 读路径是 80% 的价值（「我的表在目录里能被找到、能被授权」），且不引入写语义 |
| **v1.1** | `/polaris/v1` 别名、`schema` 快照展示、控制台建表/删表 | 补齐运维动作，风险低 |
| **v2** | 写路径（委托 + `refresh` 端点）、按需 schema 探测、位置索引（单 catalog 表数上万时）、**Flink Catalog 只读接入（§15）** | 每一个都要求先回答一个语义问题，见 §6.5 / §5.3 / §15 |
| **v3** | 提交协调（只对 lance 这种自带 version 的格式记账）、统计、格式转换 | 与前两期的性质不同，属于「catalog 开始参与数据面」，需要单独评审 |

---

## 12. 风险与已知窗口

| 风险 | 触发条件 | 影响 | 缓解 |
| --- | --- | --- | --- |
| `FileTable` 内部 API 漂移 | 升 Spark 小版本 | parquet/orc 读不了（`NoClassDefFound` / 编译失败） | Scala 写（编译期暴露）+ 版本锁 + `GenericTableSparkTests` 必跑 |
| 漏加 `table_kind` 过滤 | 新增一处 Iceberg 侧查询 | 客户端在 `/tables` 里看到读不了的表 | 收敛到两个 DAO 方法 + `GenericTableIsolationTests` |
| 位置重叠的并发窗口 | 两个请求同时指向同一位置 | 两个别名指同一份数据 | 已知窗口，写文档；不加分布式锁（故障面更大） |
| 数据面绕行 | 关闭凭据下发 | 能读元数据的人能读全部数据 | 文档 + 控制台提示；把 `vended-credentials` 列为推荐配置 |
| parquet 目录 LIST 成本 | 单表十万级文件 | 每次 `SELECT` 多一次 LIST，秒级延迟 | 建议分区目录 + 文档写明「catalog 不缓存文件列表」 |
| native 库平台不匹配 | ARM 镜像 + x86 vortex jar | 首次查询崩溃 | 镜像构建检查 + `-all` 分类器说明 |
| 通用表与视图同名 | 先建视图再建同名通用表 | Spark 里两个对象同名 | 创建时显式检查；现有表/视图的缺口不在本期扩大 |
| 升级漏跑 ALTER | 存量库直接升代码 | `ddl-auto=validate` 启动失败 | §3.6 的脚本 + 发布说明里单列一步 |
| Flink 侧 schema 漂移 | 引擎改了 parquet 列而快照没更新 | `DESC` / `SELECT` 仍用旧列（Flink 按列名匹配），缺列直接报错 | 文档写明「快照就是快照」；v2 给「重新探测」按钮；流作业重启即重新拉（§15.3） |
| Flink 流作业凭据过期 | 依赖 vended credentials 且作业运行超过 TTL | 恢复后读 S3 403 | 文档写明流作业用 IAM Role / 实例角色；建表页提示（§15.5） |

---

## 13. 明确不做的

1. **不做 schema 的权威元数据**。存了就会漂，漂了比没有更糟（有人会拿它生成 DDL）。
2. **不做提交协调**。parquet/orc 没有提交这个动作；lance 有，但把 version 记账进来就等于承诺
   「catalog 认为的最新版本是对的」，而写它的引擎可能根本不通过我们。
3. **不做 update / rename**（与 Polaris 一致）。rename 一个 parquet 目录表在语义上是「换个名字指向
   同一个目录」，收益极低，却要把「同名互斥」的检查再做一遍。
4. **不做 purge / 递归删除**。`DELETE` 只删元数据行，不删文件：服务端无法证明目录里每个文件都属于这张表
   （Polaris 的「disjoint locations 不合规」那句话就是承认这点），一次误删的代价不可逆。
5. **不把通用表放进 Paimon catalog**。Paimon 面的 `databases/{db}/tables` 会列出它、然后
   Paimon 的 `loadTable` 读不了——制造一个必然会踩的坑。通用表只属于 `catalog_format = ICEBERG` 的 catalog。
6. **不做 Iceberg API 的兼容**（走错 API 就是 404）。
7. **不做格式转换**（「把 parquet 表变成 Iceberg 表」是迁移工具的事，不是 catalog 的运行时职责）。

---

## 14. 附：与 Polaris 的逐项对照

| 项 | Polaris | 本设计 | 差异理由 |
| --- | --- | --- | --- |
| 路径 | `/polaris/v1/{prefix}/…` | `/v1/{prefix}/…`（+ 可选别名） | 复用现有授权拦截器；不承诺 `polaris-spark` 二进制兼容 |
| `format` | 自由字符串 | 自由字符串 + 允许清单 | 拒绝必须在建表时发生，不能拖到查询时 |
| `base-location` | 可选 | **必填**（四种格式都必需） | 没有位置就没有可读的东西；「客户端自己管」在 Spark 里表现为一张查不出错误的空表 |
| `schema` | ❌ | 可选快照（不校验） | 控制台需要展示；lance/vortex 服务端读不到 footer |
| 凭据下发 | ❌（Spark 客户端不支持） | ✅ | 已有 `IcebergCredentialService`，这是唯一的数据面控制点 |
| 更新 | ❌（drop+create） | ❌（同） | 语义问题没想清楚之前不开口 |
| 删除 | 删元数据 | 删元数据 + 事件带 `dataRetained` | 审计上要能区分「删行为」与「删数据」 |
| 权限 | `TABLE_CREATE/DROP/LIST/FULL_METADATA` | `TABLE_CREATE/DROP/LIST/READ_PROPERTIES` | 与本工程 Iceberg `loadTable` 同口径 |
| 与 Iceberg 同名 | 禁止 | 禁止（`uk_table` 兜底） | 同 |
| 走错 API | 404 | 404 | 同：给不出 `TableMetadata` 就不要编 |
| 事件 | `GENERIC_TABLE` 类别（BEFORE/AFTER） | `GENERIC_TABLE` 类别（AFTER 的 created/dropped） | 全局判据：只收变更 |
| `properties` | 无保留键 | 保留键 `partition-keys`，禁止键 `connector` / `path` / `format` | 见 §15.4：Flink 必须显式声明分区列；三个禁止键是防止「改属性就能改指向」 |

---

## 15. 其它引擎：Flink 与 StarRocks 是否可行

> 结论先给：**服务端一行不用改，可行性完全由客户端决定。** 判据不是「我们的 4 个端点够不够通用」，
> 而是 **「这个引擎有没有给用户留一个在进程内自己写 catalog 的扩展点」**。
> Flink 有（`Catalog` SPI），所以可行且比 Spark 侧便宜；StarRocks 没有，所以不可行。

### 15.1 判定矩阵

| 引擎 | 接入点 | parquet | orc | lance | vortex | 代价 |
| --- | --- | --- | --- | --- | --- | --- |
| Spark 3.5 | `TableCatalog`（本工程自己的插件） | ✅ | ✅ | ✅ 需 bundle | ✅ 需 `-all` jar | 已设计（§6） |
| Flink 1.18 / 1.20 / 2.x | `Catalog` + `CatalogFactory` SPI | ✅ | ✅ 需 `flink-orc`（2.x 需实测） | ❌ 无连接器 | ❌ 无连接器 | 新模块约 5 个类，只读 |
| StarRocks 3.x / 4.x | **无用户 SPI**，只有 `FILES()` 表函数 | ✅ | ✅ | ❌ | ❌ | 零开发，但它不是「表」 |

三条硬边界：

1. **lance / vortex 只可能被 Spark 读**。社区没有这两种格式的 Flink 连接器（本文写作时），
   StarRocks 更没有。想在 Flink 里读 lance，唯一办法是先转成 parquet 或 Iceberg ——
   那是迁移工具的活，§13.7 已经排除在本特性之外。
2. **StarRocks 拿不到「表」这个对象**。它的 external catalog 类型是编译期闭集
   （hive / iceberg / hudi / delta / paimon / jdbc / …），没有「文件目录」这一类，
   也没有让我们注册新类型的 SPI。所以 `SELECT * FROM lakehouse.db.t` 在 StarRocks 侧不存在。
3. **Flink 侧同样不做提交协调**（与 v1 一致）。但 Flink 有一个 Spark 没有的东西：
   filesystem sink 的 partition commit 支持 `success-file` 策略，写完落一个 `_SUCCESS`。
   这正是 §6.5 逃生口 ③ 缺的那个「写完的信号」，也是 v3 真正做提交协调时最便宜的起点
   （不需要改写入方，只需要一个约定）。

> 同一套推理对 Trino 也成立（它有 `ConnectorFactory` SPI，理论上可做），但 Trino 的 split 管理与谓词下推
> 要自己写一遍，成本远高于 Flink 的「翻译成 connector DDL」。不在本期评估范围。

### 15.2 为什么 Flink 能做，而且比 Spark 侧便宜

Flink 的 `Catalog` 抽象天生就是一封「元数据 → connector 选项」的翻译层：

```java
public class LakehouseCatalog extends AbstractCatalog {
    @Override public List<String> listDatabases();           // ↔ /v1/{prefix}/namespaces
    @Override public CatalogBaseTable getTable(ObjectPath p); // ↔ …/generic-tables/{t}
}
```

`getTable` 只需要拼出三样东西：

| `CatalogTable` 的字段 | 从哪来 |
| --- | --- |
| 列（`Schema`） | schema 快照；没有就由**客户端**扫 footer（§15.3） |
| `getPartitionKeys()` | `properties["partition-keys"]`（§15.4） |
| `getOptions()` | `connector=filesystem`、`path=base-location`、`format=generic_format`，再叠加用户 `properties` 与凭据 |

**不需要**碰任何 `private[flink]` 的类，不需要为 DSv1 / DSv2 分派，也不需要 §6.2 那种
「先试 Iceberg 再回退」的两段式加载 —— Flink 的 catalog 只回答「这是什么表」，读交给 `filesystem` connector。
所以 Flink 适配没有 §12 里那条最危险的风险（内部 API 漂移），代价只是多一个模块。

唯一要照抄 Spark 侧的是**分流**：Iceberg 表必须仍然返回 Iceberg 的 `CatalogTable`
（`iceberg-flink-runtime` 已经提供 `FlinkCatalog`）。两种做法：

- **A. 委托**（选这个）：内部持有 Iceberg `FlinkCatalog`，`getTable` 先问它，
  抛 `TableNotExistException` 再问通用表端点 —— 与 §6.2 同构，连异常语义都一样。
- B. 只做通用表，让用户建两个 catalog。便宜，但 `SHOW TABLES` 看不到另一半，用户会困惑。

### 15.3 schema：这一刀必须补，但补在客户端

这是本节最重要的结论。Flink 的 `CatalogTable` **必须能给出列**，而 filesystem connector
**不做 schema 推断**（DDL 里得写全列）。于是：没有 schema 快照的通用表，Flink 侧连 `DESC` 都出不来。

而 §5.3 拒绝「服务端探测 schema」的三条理由 —— 多两个数据面依赖、服务端出网、限流要重新评估 ——
**在客户端一条都不成立**：客户端本来就要出网读数据，`parquet-hadoop` / `orc-core`
也已经在 `format=parquet` / `format=orc` 的 classpath 里。

所以：**探测放在 Flink 插件里，服务端仍然不扫数据文件。§5.3 的立场不用改，只补一句
「探测可以由客户端做，服务端不做」。**

两个实现细节：

- parquet 用 `ParquetFileReader.readFooter` 取 `MessageType`，orc 用 `OrcFile.createReader().getSchema()`；
  取目录里**最新一个文件**的 footer，不做多文件 merge（多文件不一致时按 §2 的「以最后一份为准」）；
- 结果按 `generic-table.cache-ttl` 缓存，并接受「引擎侧已演进、缓存内仍是旧 schema」这个窗口。
  footer 读不到（目录空、权限不足）时**不要编造 schema**：报「该表没有 schema 快照且目录不可读，
  请用控制台补一份快照」，与 §13.1 同一条纪律。

### 15.4 分区：唯一需要新增的保留键，以及三个禁止键

Spark 侧的分区列是白捡的（`FileTable` 从 `k=v/` 目录推出来，类型猜 `string`）。Flink 侧不行：
**分区列必须显式声明在 schema 里**，否则 `dt=2026-10-01` 这一层目录不会被当成分区值
（`PARTITIONED BY` 只能引用已声明的列；分区**值**才是自动发现的）。

于是 `properties` 的规则从「无保留键」（§1.1 记录的 Polaris 事实）改成：

| 键 | 规则 |
| --- | --- |
| `partition-keys` | **保留键**：逗号分隔的列名，由外层到内层。被 catalog 消费成 `PARTITIONED BY`，不进 connector options |
| `connector` / `path` / `format` | **禁止出现**：它们决定「读什么」，只能由 `generic_format` 与 `base-location` 填。允许覆盖等于允许把这张表指向别处 —— 一个 `path` 覆盖就能绕过 §3.4 的位置不重叠检查 |
| 其余 | 原样透传给 connector options（`parquet.*`、`orc.*`、`s3.*` 等） |

连带两条约束，进 §5.2 的校验顺序（插在第 3 步之后）：

1. 出现禁止键 → **400**，点名是哪个键（不静默忽略，用户会以为自己生效了）；
2. 给了 `partition-keys` 时 `schema` **必填**，且每个 key 必须出现在 schema 里 ——
   footer 里没有分区列，探测也补不上，所以"先建表后补 schema"在这条路径上走不通。

`partition-keys` 的顺序服务端**不校验**（要 LIST 才知道目录嵌套），只在控制台建表页用占位提示说明。

### 15.5 凭据：Flink / StarRocks 侧退化成「启动时快照」

§4.5 说「凭据下发是唯一的数据面控制点」，那只在客户端"边拿边用"时成立。
Flink 与 StarRocks 都把凭据放在**静态选项**里（`CatalogTable.getOptions()` / `FILES(…)` 的参数），
而临时凭据有 TTL：

| 引擎 | 凭据怎么进 | 长跑的结局 |
| --- | --- | --- |
| Flink 批作业 | `getTable` 时取一次，写进 options | 短作业没问题；超过 TTL 的作业在 checkpoint 恢复后 403 |
| Flink 流作业 | 同上（`getTable` 只调一次） | **必然过期** —— 只能靠实例角色 / IAM Role |
| StarRocks `FILES()` | SQL 文本字面量 | 每次查询重写 SQL 可以换；但凭据落进审计日志与慢查询表 |

文档与建表页都要写清楚：**这两条路径的用户是「元数据面受控」，不是「数据面受控」。**
这不是缺陷，是接入方式的性质 —— 想数据面受控，只能用 Spark 侧的 vended credentials。

### 15.6 StarRocks：三条路，只有一条能查，但不是「表」

| 路 | 语法 | 能查 | 是「表」吗 | 说明 |
| --- | --- | --- | --- | --- |
| `FILES()` 表函数（推荐） | `SELECT * FROM FILES("path"="s3://b/p/*.parquet","format"="parquet",…)` | ✅ | ❌ 每次查询写全 | v3.1 起；自动推断 schema；v3.2 起 `columns_from_path="dt"` 能接 `k=v/` 分区；v4.1.2 起可用 `schema=` 显式声明 |
| 内表 + `INSERT … SELECT FROM FILES()` | `INSERT INTO t SELECT * FROM FILES(…)` | ✅ 但是**拷贝** | ✅ 内表 | 数据进 StarRocks 本地存储，不是读通用表 |
| Iceberg / Paimon external catalog | `CREATE EXTERNAL CATALOG … ("type"="iceberg")` | ✅ | ✅ | **要求底层真是 Iceberg / Paimon 表**。通用表在 `/tables` 里看不到（§4.1 的设计结果），所以这条路是「换表」，不是「适配」 |

`FILES()` 的三个约束必须一起写（否则会被当成万能方案）：

1. 它是**表函数**，不出现在 `SHOW TABLES` 与权限体系里 —— 我们的 RBAC 在 StarRocks 侧**没有执行点**，
   比 §4.5 更彻底：连元数据都不经过我们；
2. `path` 是目录或通配（`*.parquet`），没有「多文件 schema merge」的概念。
   跨版本文件类型不一致时 StarRocks 直接报错，而 Spark 侧是按 §2 的「以最后一份为准」——语义不完全等价；
3. 凭据是 SQL 字面量。控制台生成 SQL 片段时**不能带真实 AK/SK**，只能给占位符。

所以对 StarRocks 的正式建议只有一句：**要「像表一样查」，就别用通用表，用 Iceberg 表**
（StarRocks 的 Iceberg external catalog 支持 REST 类型，能直接读我们这边建的表）；
通用表的价值是「登记一个别人写的目录」，不是「给 StarRocks 提供表」。

### 15.7 控制台要加的「引擎可读性矩阵」

§7 的建表页只校验格式在允许清单内。加上 Flink / StarRocks 之后，「允许」与「能读」不再等价：

| 格式 | Spark | Flink | StarRocks `FILES()` |
| --- | --- | --- | --- |
| parquet | ✅ | ✅ | ✅ |
| orc | ✅ | ✅（需 `flink-orc`） | ✅ |
| lance | ✅（需 bundle） | ❌ | ❌ |
| vortex | ✅（需 `-all` jar） | ❌ | ❌ |

建表页选中 lance / vortex 时给一行陈述：「只有 Spark 能读这张表」。不是警告 ——
用户可能就是故意的（Spark 侧做向量检索）。

### 15.8 分期与影响面

| 期 | 内容 |
| --- | --- |
| v2 | `lakehouse-rest-flink` 新模块：`LakehouseCatalog` / `LakehouseCatalogFactory` / `GenericTableClient` / `SchemaProbe` / `FlinkTableMapper`。只读，parquet / orc |
| v2 | 服务端：`partition-keys` 保留键 + 三个禁止键 + §5.2 插入两步校验（§15.4） |
| v2 | `/v1/config` 的 `defaults` 增加 `lakehouse.generic-table.engines`（形如 `spark:parquet,orc,lance,vortex;flink:parquet,orc;starrocks:parquet,orc`），控制台与 Flink 插件都从这里读，能力矩阵只有一份 |
| v3 | `_SUCCESS` 约定 → 可见性 / 刷新（§15.1 第 3 条） |
| — | StarRocks：**不做适配**。只给 `FILES()` 片段生成器（详情页一个按钮），一行 Java 都不用 |

端点侧无新增：Flink 插件是客户端，`verify-iceberg-endpoints.py` 的 35 / 26 / 4 三个数字都不受影响。

### 15.9 一句话结论

**Flink 可行且值得做（成本在客户端，服务端零改动）；StarRocks 不可行（缺可扩展点），
只能用 `FILES()` 表函数看，或者改用 Iceberg 表；lance / vortex 永远只有 Spark 一条路。**
