# 用 Spark SQL 通过 LakeHouse Catalog 读写 Lance 表

Lance 这一面挂在自己的前缀下（`/lance/v1/**`），客户端是 Lance 官方的 Spark
connector。这份文档回答四件事：会话怎么配、能跑哪些语句、**哪里跑不了**、
以及两侧各自往存储里写了什么。

另两份同源文档：[`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md)（Paimon）、
[`spark-iceberg-rest-e2e.md`](spark-iceberg-rest-e2e.md)（Iceberg）。三面的差别不在
SQL，在**谁负责建表**——这一面是唯一「服务端不建表」的。

## 0. 验证状态（先说清楚，别把没验过的当验过的）

用真客户端跑的，不是桩：

| 项 | 值 |
| --- | --- |
| connector | `org.lance:lance-spark-bundle-3.5_2.12:0.8.0` 与 `org.lance:lance-spark-bundle-4.1_2.13:0.8.0`（bundle 里 shade 了 `lance-core` 12.0.0 与原生库） |
| Spark / Scala | 3.5.9 / 2.12 与 4.1.2 / 2.13，JDK 21 |
| 用例 | `LanceCatalogLiveTests`，7 个（源码在 `lakehouse-rest-spark/shared`，两个模块各跑一遍） |
| 脚本 | `./scripts/e2e-lance-spark.sh`（起服务端 → 台账校验 → 3.5 与 4.1 各跑一批 → 停服务端） |

验过的七件事：建命名空间（两侧列举一致）、建表 + 写入 + 逐行读回、`SHOW TABLES` 的
完整 id 形状、`OPTIMIZE` / `VACUUM` 两条维护语句、schema 不存在时建表失败、
只读令牌被这一面拒掉、以及 `declare` 之后 `is_only_declared=true` 那个状态。

**两个 Spark 版本都是真跑过的**（0.8.0 起），而且用的是同一份用例源码：

| | 3.5 | 4.1 |
| --- | --- | --- |
| connector 构件 | `lance-spark-bundle-3.5_2.12` | `lance-spark-bundle-4.1_2.13` |
| 结果 | 7/7 | 7/7 |
| 额外处置 | 给 `spark-catalyst` 排除 arrow（§5.1） | 不用排除（Spark 4.1 自带 arrow 18.3.0，实测不冲突） |

**没验的**，别当成验过：

- **0.8.0 之前的 connector**：0.4.0 时只有 3.5 那边跑过，4.1 没跑；升级后没有回退验证
  旧版本，也没有承诺旧版本的行为（§5.2 那条 `NoSuchMethodError` 只在 0.4.0 上出现过）。
- **对象存储**：用例全部落在 `file://` 的临时目录里。S3 / OBS 的差异只在凭据，
  与这一面的报文无关（`storage_options` 的下发逻辑另有一组 MockMvc 用例）。
- **索引与标签的写**：这一面只读索引（`_indices/` 的格式规范没有定义），
  标签可读不可写（见 [`lance-rest-catalog-design.md`](lance-rest-catalog-design.md) §7）。
- **服务端建表**：不存在，见 §3。

## 1. Spark 侧需要什么

### 1.1 依赖

只要一个 jar：`lance-spark-bundle-3.5_2.12`（bundle，不是薄包 `lance-spark-3.5_2.12`；
4.1 那边是 `lance-spark-bundle-4.1_2.13`）。薄包不含 `org.lance.Dataset`、原生库与
arrow，运行时会在第一次打开数据集时炸在 `UnsatisfiedLinkError` 上。

生产上它进 `spark/jars`；本仓库两个模块（`lakehouse-rest-spark-3.5` / `-4.1`）都用
test scope 声明各自那一份，构件名由根 POM 的 `lance.spark.artifact` 统一指过来。
两个类路径坑（arrow 两份、`NamedArgument` 两份）与它们在生产上的同一形态见 §5——
**这两个坑都表现为与本服务端毫无关系的报错**，先看完那一节再排查业务。

### 1.2 会话配置

```properties
spark.sql.catalog.lance_catalog                              org.lance.spark.LanceNamespaceSparkCatalog
spark.sql.catalog.lance_catalog.impl                         rest
spark.sql.catalog.lance_catalog.uri                          http://host:18081/lance
spark.sql.catalog.lance_catalog.delimiter                    $
spark.sql.catalog.lance_catalog.parent                       lance_catalog
spark.sql.catalog.lance_catalog.single_level_ns              false
spark.sql.catalog.lance_catalog.header.Authorization         Bearer <token>

spark.sql.extensions  io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions,org.lance.spark.extensions.LanceSparkSessionExtensions
```

逐项的理由（每一条配错都是「一条语句都不通」）：

| 配置 | 为什么是它 |
| --- | --- |
| `uri` 到 `/lance` 为止 | 客户端自己拼 `/v1/namespace/...`。服务端把这一面挂在 `/lance` 前缀下（另两面共用 `/v1/{prefix}`），多写或少写一段都会 404 |
| `delimiter=$` | 与服务端 `LanceIds.DEFAULT_DELIMITER` 一致。`id` 是**拼接串**（`cat$schema$table`），两边不一致时每个请求都会解析成别的东西 |
| `parent=lance_catalog` | 服务端 `ListNamespaces` 回的是**相对名字**（`ns`），客户端拿它拼 id 时会缺第一段；`parent` 把 catalog 段补回去，于是 SQL 里照常写三段名，而 `SHOW NAMESPACES` 也不会多一层 |
| `single_level_ns=false` | **必须显式关掉**。`impl=rest` 时 connector 会先拿一个**不带 id** 的 `ListNamespaces` 探测后端是否扁平，探不通就套一个虚拟的 `default` 前缀——我们的 `SHOW NAMESPACES` 会显示成 `default.ns`，看着像服务端凭空多了一层。而这一面本来就没有「列根」入口（id 第一段永远是 catalog），探测注定失败、注定误判 |
| `header.*` | Lance 的 REST namespace 把 `header.` 前缀的属性直接铺成 HTTP 头，没有 Iceberg 那套 oauth2 协商 |
| 扩展 | `LanceSparkSessionExtensions` 提供 `OPTIMIZE` / `VACUUM` 两条 parser 级命令（§2.4） |

**扩展要不要装**：只有跑维护语句才需要。它会改变「解析失败」时的报文（原生解析器
抛一次、它再抛一次自己语法的失败），因此本仓库默认不装，只在
`e2e-lance-spark.sh` 那一批打开（`-De2e.lance.enabled=true`）。

### 1.3 catalog 必须先建

另两面都有连接期握手（`GET /v1/config`），服务端在那一刻按默认格式把 catalog 登记出来；
**Lance 面没有这个端点**，客户端第一个请求就是 `GET /lance/v1/namespace/{id}/list`，
prefix 不存在时只会得到 404——看起来像「base uri 配错了」，其实是服务端还没有这个 catalog。

所以先用管理 API 建一个 LANCE 格式的 catalog（`scripts/e2e-lance-spark.sh` 里的
`LiveSparkSession.lanceEnsureCatalog()` 做的就是这件事）：

```bash
curl -X POST http://host:18081/api/management/v1/catalogs \
  -H 'Authorization: Bearer root' -H 'Content-Type: application/json' \
  -d '{"catalog":{"type":"INTERNAL","name":"lance_catalog","catalogFormat":"LANCE",
       "properties":{},
       "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/lance-wh"]}}}'
```

`allowedLocations` 不是可选项：`declare` 时客户端不给 `location`，服务端会按路径模板
分配 `<warehouse>/<schema>.db/<table>.lance`，那个路径必须落在白名单里——`declare`、
`register`、`CreateTableVersion` 三处都判，越界一律 400（Lance 面是 400 + `LanceException`
的错误体，不是 Spring 那条通道）。

判定按**段边界**（`Paths.isWithin`，与 Iceberg、Paimon 面同一份实现）：等于白名单某一项，
或写在它下面（前缀 + `/`）；`scheme` 与 host 大小写不敏感、`path` 敏感，尾斜杠等价。
`file:///tmp/lance-wh2` 不会被 `file:///tmp/lance-wh` 放行。白名单为空表示不限制，
但 LANCE 面不建议留空：`declare` 的默认位置就是从 warehouse 推出来的，留空等于让
「仓库在哪」与「允许写哪」脱钩。

### 1.4 认证

`header.Authorization=Bearer <token>`，令牌就是管理 API 用的那个（签发方式见
[`authorization.md`](authorization.md)）。授权按 catalog 判：读要 `DESCRIBE`，
写要 `CATALOG_WRITE`（`declare` / `deregister` 都算写）。

## 2. 能执行的 SQL

### 2.1 建命名空间

```sql
CREATE NAMESPACE lance_catalog.sales;
SHOW NAMESPACES IN lance_catalog;   -- sales
```

服务端 `ListNamespaces` 回的是**名字数组**（`["sales"]`），不是对象数组、也不是
`cat$sales`。回完整 id 会让客户端拼成四段而 400——这一条第一次跑真客户端就踩到了（§4.4）。

### 2.2 建表与写入

```sql
CREATE TABLE lance_catalog.sales.orders (id BIGINT, name STRING);
INSERT INTO lance_catalog.sales.orders VALUES (1, 'a'), (2, 'b');
```

`CREATE TABLE` 在服务端只走 `declare`（登记意图），但**connector 会紧接着在分配到的
位置上写一个 0 fragment 的空数据集**——所以建完表 `is_only_declared` 就是 `false`。
「只登记」那一态要用裸 REST 观察（§4.3）。

### 2.3 读回与 `SHOW TABLES`

```sql
SELECT id, name FROM lance_catalog.sales.orders ORDER BY id;
SHOW TABLES IN lance_catalog.sales;   -- 显示 lance_catalog$sales$orders
```

`SHOW TABLES` 那列是**完整 id**（规范原文：full identifier in string form），connector
把它直接当表名显示。别拿它去 `SELECT`——那会拼出四段 id 而 400。查数据用三段名。

### 2.4 维护语句（`OPTIMIZE` / `VACUUM`）

两条都是 **parser 级命令，不是 `CALL`**，由 `LanceSparkSessionExtensions` 提供：

```sql
OPTIMIZE `lance_catalog`.`sales`.`orders` WITH (
  target_rows_per_fragment = 1000, max_rows_per_group = 100,
  materialize_deletions = TRUE, materialize_deletions_threshold = 0.5,
  defer_index_remap = FALSE);
-- 输出列：fragments_removed, fragments_added, files_removed, files_added

VACUUM `lance_catalog`.`sales`.`orders` WITH (
  before_timestamp_millis = 1793198845963,
  delete_unverified = FALSE, error_if_tagged_old_versions = TRUE);
-- 输出列：bytes_removed, old_versions
```

三条语法规则，违反时都是**解析期的语法错误**，看不出是「差一个小数点」：

- 语句里**不能有分号与注释**（Scala 那种多行字符串拼接留下的换行也要先折成空格）；
- 浮点**必须带小数点**：`target_rows_per_fragment = 1000` 是 BIGINT，
  `materialize_deletions_threshold = 1` 会在 connector 侧以 `ClassCastException` 收场；
- `before_timestamp_millis` 是 **epoch 毫秒**（三个面里唯一不用操心时区的那个），
  给 `now` 就等于「清掉所有历史版本，只留最新」。

这两条语句与优化任务渲染出来的文本逐字一致（`docs/lance-table-optimization-design.md`）。
本用例是那份渲染规则的**唯一执行方**：优化任务只渲染、从不真的执行。

### 2.5 表建在不存在的 schema 下

```sql
CREATE TABLE lance_catalog.no_such_ns.t (id BIGINT);
-- NoSuchNamespaceException / "not found"
```

服务端回 404 `NamespaceNotFound`（code 1）。这条必须失败得「早」：漏了它客户端会在本地
造一张只存在于本次会话的表，下一次 `SELECT` 才以一句无关的话失败。

## 3. 能力边界：这一面**没有服务端建表**

规范里 `CreateTable`（`POST /lance/v1/table/{id}/create`）要求服务端自己造数据集，
本实现不做（恒 406）。Spark 的 `CREATE TABLE` 因此走的是：

```
declare（登记一行元数据 + 分配 location） → 客户端在 location 上写数据集
```

同一个道理，**`DROP TABLE` 也不可用**——`DropTable` 同样在我们不做的清单里。
清理表要用 `deregister`（移除登记、保留数据）：

```bash
curl -X POST http://host:18081/lance/v1/table/lance_catalog%24sales%24orders/deregister \
  -H 'Authorization: Bearer root'
```

这是这一面**真实**的能力边界，不是偷懒：`declare` 的定义就是「登记意图、不碰存储」，
数据集由客户端写（规范在 `InsertIntoTable` 里明写「对已 declare 未 create 的表，
本操作连同数据一起建出来」）。服务端若自己去写 Lance 文件，就得在 core 里引入格式实现，
撞上「三面互不依赖」那条模块不变量。

其余恒 406 的端点（规范 54 个 operation 里我们做 19 个、21 条端点）见
`scripts/verify-lance-endpoints.py` 的台账与
[`lance-rest-catalog-design.md`](lance-rest-catalog-design.md) §3.1。索引只读、标签只读
这两条也记在那儿。

### 3.1 凭据：`storage_options` 只被读一次，长作业会过期

服务端在 `describe?vend_credentials=true` 时把凭据摊进 `storage_options` 并给 `expires_at_millis`。
但 connector **0.8.0 的 `BaseLanceNamespaceSparkCatalog` 只读一次**：它把
`DescribeTableResponse.getStorageOptions()` 存成 `LanceSparkReadOptions` 里的静态 map，整个查询
生命周期不再换。0.4.0 时 `LanceSparkReadOptions` / `LanceRuntime` 还引用
`LanceNamespaceStorageOptionsProvider`（`StorageOptionsProvider`，实现 `fetchStorageOptions()`
供 Lance 内核回调），0.8.0 这条线没有接到 Spark 侧。

**因此：作业时长一旦超过 `lakehouse.rest.credential.ttl-seconds`（默认 3600 秒），后半程会以
`InvalidToken` / 403 失败，而且报错指向存储、与 catalog 无关。** 三条现状对策：把 TTL 配到作业
时长之上（代价是泄露窗口同样变长）、把长作业分片、或者干脆在会话里配静态 AK/SK（就是 §1.2 里
那四行——它们今天仍然必要）。完整分析见
[`credential-vending-design.md`](credential-vending-design.md) §7.4。

## 4. 两侧各自往存储里写了什么

### 4.1 版本账：V2 命名

客户端写 `_versions/{u64::MAX - version}.manifest`（补零 20 位，**按无符号数**），
所以版本 1 的文件名是 `18446744073709551614.manifest`——字典序越大越旧。
服务端只 LIST `_versions/` 来发现版本，不读 `latest_version_hint.json`：
提示文件是给「列举代价高」的客户端准备的加速手段，当第二个真相源只会引入不一致。

### 4.2 manifest 是**容器**，不是裸 protobuf

真文件长这样（`lance-table/src/io/manifest.rs` 的 `do_write_manifest`）：

```
[索引段]      [u32 长度][IndexSection protobuf]      ← 有索引才有
[内联事务段]  [u32 长度][Transaction protobuf]       ← 有事务才有
[manifest 段] [u32 长度][Manifest protobuf]          ← 我们要的那一段
[footer 16B]  [段偏移 i64][major u16][minor u16][LANC]
```

两个必须按这个布局读的理由：

- **manifest 不一定从第 0 字节开始**。`InsertIntoTable` 第一次写入就带内联事务，
  那时第 0 字节是事务段。按 0 解会得到一份字段名乱码、却不抛异常的 protobuf——
  schema 悄悄错，比报错难查得多。所以按 footer 的偏移取，且严格校验不越界。
- **`Field` 里已经没有 `type` 了**。4.x 的写入方不再写这个字段（Rust 侧的结构体里
  它已经不存在），树形改由 `logical_type` 表达：`struct` 是父节点，
  `list` / `large_list` / `fixed_size_list:...` 是可重复节点，其余是叶子。
  而 protobuf 对「没写」读出来是枚举的 0，0 恰好是 `PARENT`——照它解释会把
  **每一个叶子列当成 struct**，schema 于是变成一张空表（不报错）。
  顶层的写法也换了主子：老的用 `parent_id = 0`，4.x 起用 `-1`。

服务端的处置在 `LanceManifest`（容器 + 角色判定）与 `LanceJsonArrow`（建树）。
读不出时**降级**：只回 `location`，不回 schema——给一份空 schema 等于告诉客户端
「这张表有 0 列」。

### 4.3 `is_only_declared` 是三态，且探测要看 V2 命名

`true` = 登记了但存储里没有数据集、`false` = 有、`null` = 这次没检查（默认不检查，
只有 `check_declared=true` 才给真值）。三态不能拍平成两态——「不知道」与「没有」
对客户端是两个完全不同的决策。

探测逻辑是「列举 `_versions/`，认出任意一个 V1/V2 manifest 名就算有数据集」。
早先它只探 `_versions/1.manifest` 与提示文件，那等于同时假设「客户端按 V1 命名」
与「客户端会写提示文件」——4.x 两条都不成立，于是**表里已经有数据，服务端仍回
「只有登记」**。文件名不是契约，列举结果才是。

### 4.4 HTTP 契约的三处修正（第一次跑真客户端时暴露的）

| 症状 | 真因 | 修正 |
| --- | --- | --- |
| 建库/建表每个请求都 `400 Malformed request`（code 13） | 请求体里的 `id` 规范定义为**层级数组** `["cat","schema"]`，服务端按 `String` 绑定，反序列化直接失败 | DTO 收 `JsonNode`，`LanceIds.levelsOfBody` 认数组 / 拼接串 / 缺键三态 |
| 客户端连反序列化都过不了 | `ListNamespaces` 的响应规范是**字符串数组**（相对名字），服务端回的是 `[{id, properties}]` | 回 `["ns"]`，并新增 `LanceIds` 的层级比对 |
| `DropNamespace` 的级联一直 406 | `behavior` 在**请求体**里（`{"behavior":"Cascade"}`），服务端只看 query 的 `mode` | 两者都认 |

只做 MockMvc 是发现不了这三处的：那些用例断言的是「我们按自己的理解回了一份规范的
报文」，而错位恰恰发生在**规范理解与客户端实际发送之间**。

## 5. 类路径上的两个坑（都表现为「与本服务端无关」的报错）

### 5.1 arrow 两份

3.5 侧：`spark-catalyst` 传递依赖 `arrow-vector` / `arrow-memory-core` 12.0.1，
Lance 的 bundle 里 shade 了另一批 arrow（`org.apache.arrow.*`，未 relocate；0.8.0 里是
**15.0.2**）。谁先加载取决于 jar 顺序：12 在前时，Lance 打开数据集走的是 12 的
`MemoryUtil.directBuffer`，报

```
UnsupportedOperationException: sun.misc.Unsafe or
java.nio.DirectByteBuffer.<init>(long, int) not available
```

栈里没有一句话提到「类路径上有两份 arrow」。本仓库在 `spark-catalyst` 上排除四个
arrow 构件（测试期不用 pandas UDF），优化任务的镜像则用 `spark.driver.extraClassPath`
把 bundle 前置——两个做法同一目的：**让第一个命中的是 bundle 里那份 arrow**。

「只剩一份」得加个限定：排除掉 12 之后类路径上仍有两个 arrow 版本，只是都排在 bundle
自身之后、不会被加载。`dependency:tree -Dincludes=org.apache.arrow` 在 0.8.0 上的实测：

```
\- org.lance:lance-spark-bundle-3.5_2.12:jar:0.8.0:test
   +- org.lance:lance-core:jar:12.0.0:test
   |  +- org.apache.arrow:arrow-vector:jar:18.3.0:test
   |  +- org.apache.arrow:arrow-memory-netty:jar:18.3.0:test
   |  \- org.apache.arrow:arrow-dataset:jar:18.3.0:test
   \- org.lance:lance-namespace-core:jar:0.11.1:test
      \- org.apache.arrow:arrow-memory-core:jar:15.0.0:test
```

（bundle shade 的是 15.0.2，`lance-core` 12.0.0 的 POM 又带进 18.3.0。18 与 15 混搭没炸，
只因为谁先加载已定；换 connector 版本要重跑一遍再下同样的结论。）

4.1 侧是另一种情况：Spark 4.1.2 自带 arrow **18.3.0**（`provided` 作用域，本来就排在
bundle 前面），而 0.8.0 的 bundle 里是 15.0.2。**这一版没有加排除，7 个用例全绿**
（含写入与 `OPTIMIZE`，都真的走 arrow）。不加的理由：4.1 的 `ArrowConverters` 还在用
Spark 自己那份，摘掉的破坏面比 3.5 大；既然不炸就不动它。这条是**实测结论**，不是
推断——换 connector 版本时要重跑一遍再下同样的结论。

### 5.2 `NamedArgument` 两份（0.8.0 起不再发生）

0.4.0 时 `lance-spark-bundle` 与 `iceberg-spark-runtime` 都自带
`org.apache.spark.sql.catalyst.plans.logical.NamedArgument`，**签名还不同**：
bundle 那份是从 Spark 4.0 `statements.scala` 拷来的 `(String, Object)`
（`OPTIMIZE ... WITH (k = v)` 的具名参数用它），Iceberg 那份是 `(String, Expression)`
（给 `CALL` 用）。同名类谁生效只取决于谁先加载，排在后面时 `OPTIMIZE` 就以

```
NoSuchMethodError: 'void org.apache.spark.sql.catalyst.plans.logical
.NamedArgument.<init>(java.lang.String, java.lang.Object)'
```

失败。**0.8.0 的 bundle 不再自带这个类**（3.5 与 4.1 两份 jar 都核对过），这条症状随之
消失；Spark 4.1 自己就有这个类，3.5 的 `spark-catalyst` 里没有、也不再需要。

测试期的声明顺序（bundle 排在 Paimon / Iceberg 之前）**仍然保留**：它现在守的是 §5.1
那半（arrow 谁先加载），而真集群上仍靠 `spark.driver.extraClassPath` 把 bundle 前置
（与 §5.1 同一个开关）。

## 6. 常见报错对照

| 现象 | 真因 | 处置 |
| --- | --- | --- |
| 每个请求 404，报文是 `NamespaceNotFound` | 服务端没有这个 catalog（这一面没有 `/v1/config` 握手，撞不出来） | 先用管理 API 建 LANCE catalog（§1.3） |
| 404 且 prefix 存在 | 同名 prefix 是别的格式（Paimon / Iceberg） | 换名字；同一个 prefix 不能承载两种格式 |
| `SHOW NAMESPACES` 显示 `default.ns` | 少了 `single_level_ns=false` | 显式关掉（§1.2） |
| 建表 400 `Malformed request` | 客户端发数组 `id`、服务端按字符串收 | 已修（§4.4）；旧服务端才会这样 |
| 建表后 `SELECT` 报四段 id 非法 | 用了 `SHOW TABLES` 那列当表名 | 用三段名（§2.3） |
| `OPTIMIZE` 报 `NamedArgument.<init>` | 0.4.0 的 bundle 自带这个类，被 Iceberg 那份同名签名抢走 | 升到 0.8.0（bundle 不再自带，§5.2）；旧版本只能把 bundle 前置 |
| `sun.misc.Unsafe or DirectByteBuffer not available` | 3.5 侧类路径上有 arrow 12 与 15 两份 | 排除 arrow 12（§5.1） |
| `load_detailed_metadata` 回 409 / code 19 | 表只有登记、没有 manifest | 先写一次数据；这是规范允许的失败 |
| schema 回来是空 `fields` | 服务端旧版按 `Field.type` 判角色 | 已修：角色改由 `logical_type` 决定（§4.2） |

## 7. 怎么验证

一个命令，它会自己起服务端、跑台账校验与两批用例（3.5 与 4.1 各 7 个）、然后停掉服务端：

```bash
mvn -o install -DskipTests      # 脚本用 server 的 jar，先打出来
./scripts/e2e-lance-spark.sh    # 默认端口 18081，Spark 3.5 与 4.1 各跑一遍
SPARK_VERSIONS=4.1 ./scripts/e2e-lance-spark.sh            # 只跑 4.1
SPARK_VERSIONS="4.1 3.5" ./scripts/e2e-lance-spark.sh      # 调换顺序
PORT=19091 ./scripts/e2e-lance-spark.sh    # 换端口
KEEP=1 ./scripts/e2e-lance-spark.sh        # 保留服务端，便于接着手工调
```

脚本做的事：

1. 用 h2 profile 起服务端（关闭即丢数据，不依赖本机 MySQL），开鉴权与授权，
   两个令牌：`root`（服务管理员）、`limited`（映射到没有任何授权的 `e2e_limited`）；
2. 跑 `scripts/verify-lance-endpoints.py`（13 项，只读规范副本与控制器映射，不需要 PyYAML）；
3. 按 `SPARK_VERSIONS` 逐个 `mvn -pl :lakehouse-rest-spark-<v> test -Dtest=LanceCatalogLiveTests`，
   带 `-De2e.lance.enabled=true -De2e.lance.limited-token=limited`；
4. 有版本失败就**继续跑另一个**，最后把失败的版本一起报出来——两批要的是同一份契约，
   只看第一个失败的版本会丢掉「是这一版特有还是两版都错」这个信息。

只想跑 Spark 侧的用例（服务端已就绪时）：

```bash
# 3.5
mvn -o -pl :lakehouse-rest-spark-3.5 test -Dtest=LanceCatalogLiveTests \
  -De2e.management.url=http://127.0.0.1:18081/api/management/v1 \
  -De2e.management.token=root \
  -De2e.lance.enabled=true -De2e.lance.limited-token=limited

# 4.1：同一个类（源码在 shared），只换模块名
mvn -o -pl :lakehouse-rest-spark-4.1 test -Dtest=LanceCatalogLiveTests \
  -De2e.management.url=http://127.0.0.1:18081/api/management/v1 \
  -De2e.management.token=root \
  -De2e.lance.enabled=true -De2e.lance.limited-token=limited
```

两个模块共用**同一个** LANCE catalog 也没问题：`lanceEnsureCatalog()` 对 409 幂等，
只在第一次建，第二次核对格式（读回来必须是 `LANCE`）——这不只是省事，格式对不上时
后面每个请求都是 404，错因与表面现象无关，所以宁可在这里说清楚。

服务端侧那一批（报文形状、端点台账、授权、版本与标签）另在
`lakehouse-rest-server` 的 `LanceRestCatalogApiTests` / `LanceVersionApiTests` /
`LanceTagApiTests` / `LanceEndpointAuthorizationTests` 里，不需要 Spark：

```bash
mvn -o -pl :lakehouse-rest-server test -Dtest=LanceRestCatalogApiTests,LanceVersionApiTests,LanceTagApiTests,LanceEndpointAuthorizationTests
```

**这一批与 Spark 那批不是替代关系**：前者证明「我们按规范回报文」，后者证明
「真客户端认这份报文」。§4.4 那三处错位，只做前者永远发现不了。
