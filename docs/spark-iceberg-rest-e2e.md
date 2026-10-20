# 用 Spark SQL 通过 LakeHouse Catalog 建 Iceberg 表

本文说明怎么把 Spark 接到本服务端的 **Iceberg 协议面**上、用 SQL 建 Iceberg 表，
以及哪些能力已经可用、哪些还不在范围内。

会用到本文的场景：

- 想知道 Spark 连 Iceberg REST 的配置怎么写（和 Paimon 那套差在哪）；
- 想用 SQL 建表并确认元数据真的落到了服务端；
- 遇到报错想快速对照原因（第 4 节）；
- 想知道「一期不做的 9 个端点」实际会以什么形式撞上来（第 5 节）。

Paimon 侧的同名文档是 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md)，
两套配置放在一起看差异最清楚（第 3 节给了一张对照表）。

---

## 0. 验证状态（先说清楚，别把没验过的当验过的）

本文分三层，**三层都是实测过的**：

| 层 | 验的是什么 | 状态与证据 |
| --- | --- | --- |
| 协议层 | 30 个端点回什么：状态码、字段名、`metadata.json` 有没有真的落进仓库、错误体的形状 | **已实测**：`IcebergRestCatalogApiTests`（10 个用例，HTTP 行为） |
| 客户端协商层 | 官方 Java 客户端能不能连上：`/v1/config` 的能力清单协商、嵌套错误体翻不翻得成具体异常、更新代数（`requirements` + `updates`）真跑不跑得通 | **已实测**：`IcebergClientEndToEndTests`（3 个用例，真 HTTP + `org.apache.iceberg.rest.RESTCatalog`） |
| 引擎层 | Spark 起会话、跑 SQL、写数据文件、再读回来 | **已实测**：`IcebergTableDdlTests`（10 个用例，真 `SparkSession` + 真服务端 + 真仓库，见第 0.1 节） |

第 3 层此前只有静态核对（`CatalogUtil` / `ResourcePaths` / `AuthManagers` / `RESTUtil`
的字节码与源码）。补上它之后，**§2.1–§2.3 与 §2.6 的 SQL 都是跑过的**；
§2.4（分支与标签）与 §2.5（视图）本轮没有加用例，仍按规格与
`IcebergRestCatalogApiTests` 的端点行为写成参考。CTAS 的边界见 §5。

### 0.1 引擎层验了什么（`IcebergTableDdlTests`）

跑法见 §6。用例与本文档的对应关系：

| 用例 | 验的事 |
| --- | --- |
| `namespaceAndPartitionedTableRoundTripThroughServer` | 建 namespace 与分区表；`DESCRIBE` / `SHOW CREATE TABLE` 的渲染，以及服务端 `metadata` 里的 `schemas[].fields[].doc`（列注释）、`properties.comment`（表注释）、`partition-specs[].transform`、表位置模板 |
| `createDatabaseIsTheSameAsCreateNamespace` | `CREATE DATABASE` 与 `CREATE NAMESPACE` 同义；服务端 `GET /namespaces` 与 `SHOW NAMESPACES` 看到同一份 |
| `insertWritesSnapshotsAndIsReadable` | `INSERT` → 读回；服务端 `current-snapshot-id` 前进、`snapshots[].summary` 里有 `operation=append` / `total-records` / `added-data-files` |
| `createTableAsSelectIsRejectedWithANamedReason` | CTAS 的边界：被拒且报文点名 `stage-create` 与 `transactions/commit`（§5） |
| `schemaEvolutionAddsAndRenamesColumns` | `ADD COLUMN` / `RENAME COLUMN` 走更新代数，服务端 `current-schema-id` 前进、历史 schema 保留 |
| `partitionTransformLandsInTheSpec` | `days(ts)` 在元数据里是 `transform = "day"`、字段名带后缀（`ts_day`） |
| `renameKeepsLocationAndRemovesTheOldName` | 重命名只改标识不动位置；旧名字 404 |
| `nonEmptyNamespaceCannotBeDropped` | 非空 namespace 删不掉，客户端翻成 `NamespaceNotEmptyException` |
| `creatingTableInMissingNamespaceFails` | 404 被翻成 Iceberg 自己的 `NoSuchNamespaceException`（与 catalyst 那个同名不同类） |
| `duplicateCreateIsRejected` | 重复建表报 already exists，且没有改动已有结构 |

**测出来的四处「文档没写、跑一次才知道」**，都已写进第 4 / 5 节：

1. CTAS 在当前服务端上不可用（staged create 没有提交路径）；
2. `ALTER TABLE ... RENAME TO` 的目标不能写全限定名，否则 catalog 名会被当成第一级 namespace；
3. 表的 decimal 类型在服务端返回的元数据里已被规范化成字符串 `decimal(18, 2)`，
   而客户端刚生成的形态是对象（`{"type":"decimal","precision":18,"scale":2}`）；
4. `SELECT * FROM <表>.snapshots` 这类**元数据表**用四段名（`catalog.ns.table.snapshots`）
   在 Spark 3.5 + Iceberg 1.11 里解析不出来：Spark 把 `ns.table` 当成两级 namespace、
   把 `snapshots` 当成表名，服务端如实回「namespace `ns.table` 不存在」。
   要看快照就用 `IcebergTableView`（控制台）或直接读 `metadata.snapshots`，
   别把文档里的 `SELECT * FROM t.snapshots` 当成能跑。

跑第 2 层那三个用例：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -o -pl :lakehouse-rest-server -am \
    -Dtest='IcebergClientEndToEndTests' -Dsurefire.failIfNoSpecifiedTests=false test
```

---

## 1. Spark 侧需要什么

### 1.1 依赖

| 构件 | 版本 | 说明 |
| --- | --- | --- |
| `org.apache.iceberg:iceberg-spark-runtime-3.5_2.12` | `1.11.0` | Iceberg 的 Spark bundle。它与本工程服务端用的 `iceberg-core` **同版本**，见根 `pom.xml` 的 `iceberg.version` |
| `org.apache.spark:spark-sql_2.12` | 3.5.x | 构件名后缀 `_2.12` 是 Scala 二进制版本，换 Spark 或 Scala 版本时 `iceberg-spark-runtime` 也要跟着换（4.x 对应 `iceberg-spark-runtime-4.1_2.13`） |

**不需要额外声明 hadoop。** Spark 发行版自带 Hadoop，而 Iceberg 客户端的默认
`io-impl`（`ResolvingFileIO`）通过 `HadoopConfigurable` 依赖它——所以这条路在 Spark 里是通的，
但在一个**裸 JVM** 里连不通（`NoClassDefFoundError: org.apache.hadoop.conf.Configurable`）。
本工程的 `IcebergClientEndToEndTests` 正是因为这个才显式换掉 `io-impl`（见该类的注释与
`support/TestOnlyFileIo`）。这是**客户端环境**上的差异，与服务端无关；记在这里是为了
「我用 Spark 能连，为什么用一段 Java 代码连不上」这个问题能一眼找到答案。

`iceberg-spark-runtime` 是 shaded 的，不会与 Spark 自带的依赖打架。
它在 `lakehouse-rest-spark` 里是**test scope**——那个模块只负责管理语句的语法扩展，
运行期不编译也不打包 Iceberg；引入它是为了让 `IcebergTableDdlTests` 能真的建出
Iceberg 表（`CREATE TABLE ... USING iceberg` 由 Iceberg 自己的 catalog 执行）。
版本与本工程服务端用的 `iceberg-core` 同一个 `iceberg.version`。

### 1.2 会话配置

```properties
# 两个扩展都要装：本工程的扩展负责自己的管理语句，Iceberg 的扩展负责
# ALTER TABLE ... CREATE BRANCH 这类 Iceberg 专有 DDL
spark.sql.extensions=io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions,org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions

spark.sql.catalog.lakehouse=org.apache.iceberg.spark.SparkCatalog
# 这一行是「走 REST 而不是 hive / hadoop」的开关，CatalogUtil 按它选实现
spark.sql.catalog.lakehouse.type=rest
# 基址，**不要带 /v1**：客户端自己会拼 v1/config、v1/{prefix}/namespaces……
spark.sql.catalog.lakehouse.uri=http://catalog-host:8080
# 这里填的是 catalog 的 prefix（= catalog 名），不是文件路径：
# 客户端先请求 GET /v1/config?warehouse=<prefix>，服务端用名字换回
# defaults.prefix 与 defaults.warehouse，之后所有路径以它开头。
# 真正的仓库路径由服务端按 catalog 配置给出，客户端不需要知道。
spark.sql.catalog.lakehouse.warehouse=iceberg_demo
```

三行值得单独说：

| 配置 | 为什么 |
| --- | --- |
| `uri` 不带 `/v1` | `ResourcePaths` 把 `v1` 与 prefix 拼在 URI 之后。写成 `http://host:8080/v1` 会得到 `/v1/v1/config`，报 404 |
| `warehouse` 填 catalog 名 | 服务端把 `warehouse` 当 **prefix** 用（`CatalogService.resolveForConfig`）。填成 `file:///tmp/warehouse` 会找不到 catalog。留空则落到部署配置的 `lakehouse.rest.default-prefix` |
| 不用配 `io-impl` | Spark 自带 Hadoop，默认的 `ResolvingFileIO` 能装上。裸 JVM 才需要显式指定 |

**还有一个容易踩的：不要给建表报文塞 `location`。** 表位置由服务端按
`lakehouse.rest.path-template` 推（默认 `{warehouse}/{database}.db/{table}`），
`CREATE TABLE ... LOCATION '...'` 走的路子要求那个位置落在 catalog 的
`allowedLocations` 里，否则被拒。

### 1.3 认证

本服务端默认**不要求令牌**（`lakehouse.rest.auth.enabled=false`），
连不上的第一嫌疑不是认证。要按部署形态选：

| 部署形态 | Spark 侧配置 |
| --- | --- |
| 默认（数据面匿名） | 什么都不用配。服务端把认不出主体的请求记为 `lakehouse.rest.auth.principal`（默认 `anonymous`） |
| 数据面要求令牌（`auth.enabled=true`） | `spark.sql.catalog.lakehouse.token=<令牌>`，并显式加 `spark.sql.catalog.lakehouse.rest.auth.type=oauth2` |
| 用主体凭据换令牌 | `spark.sql.catalog.lakehouse.credential=<clientId>:<clientSecret>` **加** `spark.sql.catalog.lakehouse.scope=PRINCIPAL_ROLE:ALL` |

两个坑，都是名字或取值级别的：

- **属性全名是 `rest.auth.type`，不是 `auth.type`**（`AuthProperties.AUTH_TYPE`）。
  只写后者不会切换认证方式；客户端会因为看到 `token` 而**猜**成 oauth2 并打一条
  `Inferring rest.auth.type=oauth2` 的警告，功能上能用，但日志里会一直有这一行。
  另外 `spark.sql.catalog.<名>.*` 里除开 Spark 自己认识的键，其余都会作为 catalog
  属性发给服务端，`auth.type` 就会变成一个被传过去但没人看的属性。
- **`credential=` 必须同时配 `scope=PRINCIPAL_ROLE:ALL`。** 客户端默认发的 scope 是
  `catalog`（`OAuth2Properties.CATALOG_SCOPE`），而本服务端的令牌端点只接受
  `PRINCIPAL_ROLE:ALL`，其余取值一律 `400 invalid_scope`。
  这不是谁的 bug：本工程按 Polaris 的约定用 scope 表示「以哪个 principal role 的身份访问」，
  但它的授权判定是把主体的**全部**角色并起来算（没有「挑一个角色」这一层），
  所以只认「不限制」那个取值，而拒绝而不是忽略——接受了却不生效会让调用方
  以为「我只授予了只读角色」。Iceberg 客户端那边 `catalog` 是它自己的 scope 语义。
  两边想说的是不同的事，因此取值对不上属于**必须照做**的配置项。

`oauth2-server-uri` 不用配：客户端默认就是 `<uri>/v1/oauth/tokens`
（`AuthConfig.oauth2ServerUri` 的默认值取自 `ResourcePaths.tokens()`），
正是本服务端的路径。

### 1.4 手工验证（不装 Spark 也能先验一遍）

```bash
# 1) 服务端认自己是 Iceberg 面，且给出能力清单
curl -s "http://localhost:8080/v1/config?warehouse=iceberg_demo" \
  | jq '{prefix: .defaults.prefix, warehouse: .defaults.warehouse, endpoints: (.endpoints | length)}'
# 期望：prefix 与 warehouse 都是服务端推的路径，endpoints 长度 26

# 2) 建一个 namespace（Iceberg 的叫法，等价于 Paimon 的 database）
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"namespace":["ice"],"properties":{"owner":"me"}}' \
  http://localhost:8080/v1/iceberg_demo/namespaces

# 3) 列出它
curl -s http://localhost:8080/v1/iceberg_demo/namespaces
```

这三步与 `IcebergClientEndToEndTests` 里的第 1 个用例是同一件事，
只是那个用例走的是官方客户端而不是 curl。

### 1.5 授权：写数据为什么要给 `ALTER`

认证解决「你是谁」，授权解决「能做什么」。Iceberg 面的路径与 Paimon 面用的是**同一套判定**
（`AuthorizationService` + `CatalogAccessRules.namespaces`），但「写一张表」落到的端点不同，
因此需要的 access 也不同：

| 动作 | Paimon 面 | Iceberg 面 |
| --- | --- | --- |
| 写数据 | `POST …/databases/{db}/tables/{t}/commit` → `TABLE_WRITE_DATA` | ① `POST …/namespaces/{ns}/tables/{t}/metrics` → `TABLE_WRITE_DATA`<br>② `POST …/namespaces/{ns}/tables/{t}` → `TABLE_WRITE_PROPERTIES` |
| 改 schema / 属性 | 该表自己的端点 → `TABLE_WRITE_PROPERTIES` | 同一个 `POST …/namespaces/{ns}/tables/{t}` → `TABLE_WRITE_PROPERTIES` |

也就是**在 Iceberg 面「能写数据」= `UPDATE` + `ALTER`**（官方权限词表里 `UPDATE` 给
`TABLE_WRITE_DATA`、`ALTER` 给 `TABLE_WRITE_PROPERTIES`），比 Paimon 多一项。原因不在映射：
Iceberg 没有独立的提交端点，数据提交与属性变更都是 `POST …/tables/{t}` 上的一个 `updates`
数组，判定发生在解析请求体之前，服务端分不清这次是哪一类。想只给「能写数据、不能改属性」，
在当前端点上做不到——要再细分只能解析 `updates` 之后二次判定。

装配方式与 Paimon 那套一样（官方权限模型 + `CATALOG_ALL`），只是 **catalog role 要在
`iceberg_catalog` 这个 catalog 下另建一份**：catalog role 是 catalog 内的实体，跨格式借用会
得到「角色不在此 catalog 下」，而那种错与「没授权」在报文里几乎一样。

```sql
-- 前提：catalog role 已用管理 API 建好，并挂到 principal role 上
GRANT CREATEDATABASE ON CATALOG iceberg_catalog TO ROLE rw;
GRANT LIST, DESCRIBE, SELECT, UPDATE, ALTER, CREATETABLE
  ON CATALOG_ALL iceberg_catalog TO ROLE rw;   -- 写入靠 UPDATE + ALTER，见上表
GRANT LIST, DESCRIBE, SELECT ON CATALOG_ALL iceberg_catalog TO ROLE ro;
```

被拒时服务端报的是路径 + 主体 + 缺的那项权限，例如：

```
Forbidden: The caller does not have permission to POST
/v1/iceberg_catalog/namespaces/ice/tables/orders/metrics:
principal e2e_bob lacks TABLE_WRITE_DATA on table ice.orders in catalog iceberg_catalog
```

这条链路由 `SparkSqlAuthorizationTests` 的 `icebergCatalog.*` 两条用例真跑（`docs/authorization.md`
§5.1 有两侧的对照）。

### 1.6 对象存储的凭据从哪来

两处都可以给，语义不同：

- **引擎侧**（客户端自己读文件）：`ResolvingFileIO` 按 scheme 选实现，
  `s3://` 要 `org.apache.iceberg:iceberg-aws-bundle`。
  缺它会以「找不到该 scheme 的 FileIO」失败，报错来自**引擎的类路径**，与服务端无关。
- **服务端侧**（服务端下发短期凭据）：在会话上加一个请求头
  `spark.sql.catalog.lakehouse.header.X-Iceberg-Access-Delegation=vended-credentials`。
  客户端侧的属性名带 `header.` 前缀（`RESTUtil.configHeaders` 就是按这个前缀筛的），
  服务端认出来之后在响应里回 `storage-credentials`，其 `prefix` 指向这张表的位置。
  本服务端「不问不给」：不带头时这一项**缺席**（而不是空数组）——规格里两者含义不同。

---

## 2. 能执行的 SQL

`lakehouse` 是 1.2 里配的 catalog 名。Iceberg 把「库」叫 namespace，
`CREATE DATABASE` 与 `CREATE NAMESPACE` 是同一个命令的两个拼法。

下面用「库.表」两段名，前提是先切到 Iceberg catalog；写三段全限定名
（`lakehouse.ice.orders`）也一样，但**不要**放进 `RENAME TO` 后面（见 §2.6）：

```sql
USE lakehouse;
```

### 2.1 建库与建表

```sql
CREATE NAMESPACE IF NOT EXISTS ice;
SHOW NAMESPACES;

-- 服务端默认写 format-version 2，与 Iceberg 自己的默认值一致
CREATE TABLE ice.orders (
  id   bigint,
  data string
) USING iceberg;
```

多级 namespace 用点号拼（`acct.tax`）。服务端同时接受规格默认的分隔符 `0x1F`，
库里按点号存——**也就是说层级里的点号会被当成分隔符**，别用点号做层级名的一部分。

**`CREATE TABLE ... AS SELECT` 在当前服务端上不可用**（引擎层实测）：
Spark 把它翻成 Iceberg 的 **staged create**，提交要走
`POST /v1/{prefix}/transactions/commit`——一期未实现的端点之一。
服务端回的是指名原因的 400：

```text
stage-create is not supported by this catalog: multi-table transactions
(POST /v1/{prefix}/transactions/commit) are not implemented,
so a staged create has no commit path
```

要「建表并写第一批数据」，用 `CREATE TABLE` + `INSERT`（两条都验过，见 §2.2）。
`IcebergTableDdlTests.createTableAsSelectIsRejectedWithANamedReason` 把这个边界钉住了：
`transactions/commit` 一旦实现，那条用例会先响，提醒把断言翻成「CTAS 成功」。

### 2.2 写入与读取

```sql
INSERT INTO ice.orders VALUES (1, 'a'), (2, 'b');
SELECT * FROM ice.orders;
```

服务端为每次提交物化一份 `metadata/00001-<uuid>.metadata.json`，快照随提交一起落库。
写入之后服务端的 `metadata.snapshots[].summary` 里能看到
`operation=append` / `total-records` / `added-data-files`——这是
`IcebergTableDdlTests` 的断言对象。

**元数据表（`.snapshots` / `.files` / `.refs`）不要用 SQL 查。** 引擎层实测：
`SELECT * FROM lakehouse.ice.orders.snapshots` 在 Spark 3.5 + Iceberg 1.11 下解析不出来——
Spark 把 `ice.orders` 当成两级 namespace、把 `snapshots` 当成表名，服务端如实回
`The given namespace does not exist: ice.orders`。看快照请用控制台的
Iceberg 表详情页（`IcebergTableView`），或直接读服务端返回的 `metadata`。

### 2.3 改 schema

```sql
ALTER TABLE ice.orders ADD COLUMN extra string;
ALTER TABLE ice.orders RENAME COLUMN data TO payload;
DESCRIBE ice.orders;
```

这一条链是**客户端生成 `requirements` + `updates`、服务端校验后应用并落库**
（Iceberg 的「更新代数」）。`IcebergClientEndToEndTests` 里实测过
`updateSchema().addColumn(...).commit()` 走通，包括服务端返回的新一代元数据能被客户端解析。

### 2.4 分支与标签（参考，本轮无用例）

```sql
ALTER TABLE ice.orders CREATE BRANCH b1;
ALTER TABLE ice.orders CREATE TAG t1;
```

这两条由 `IcebergSparkSessionExtensions` 解析、走更新代数提交，
端点与改 schema 是同一条（`POST .../tables/{t}`）。`ice.orders.refs` 是元数据表，
按 §2.2 末的说明不要用 SQL 查。

### 2.5 视图（参考，本轮无用例）

```sql
CREATE VIEW ice.v AS SELECT id FROM ice.orders;
SHOW VIEWS IN ice;
DROP VIEW ice.v;
```

### 2.6 重命名与清理

```sql
ALTER TABLE ice.orders RENAME TO ice.orders_v2;
DROP TABLE ice.orders_v2;              -- 只注销 catalog 条目，文件留着
DROP NAMESPACE ice;                    -- 非空时被拒（见第 4 节）
```

**`RENAME TO` 后面不要写全限定名**（引擎层实测）。Spark 3.5 只对**源**标识剥 catalog 前缀，
目标是原样交给 catalog 的：写成 `RENAME TO lakehouse.ice.orders_v2`，
catalog 收到的名字空间是 `["lakehouse", "ice"]`，服务端会回
`The given namespace does not exist: lakehouse.ice`。正确写法是源写全、目标写「库.表」。
`IcebergTableDdlTests.renameKeepsLocationAndRemovesTheOldName` 用的就是这个写法。

**`DROP TABLE` 与 Paimon 侧要分开看。** Iceberg 规格把「注销」与「删数据」分成两条端点：
本服务端上 `DROP TABLE`（不带 `PURGE`）只去掉 catalog 条目、**不碰仓库里的文件**，
响应是 204；`PURGE` 才走删数据那条路。这一点在两个层次都测过
（`IcebergRestCatalogApiTests` 断言外部表 purge 后文件仍在）。

---

## 3. 与 Paimon 那套的差异对照

| 维度 | Paimon 侧 | Iceberg 侧 |
| --- | --- | --- |
| Spark catalog 实现 | `org.apache.paimon.spark.SparkCatalog` + `metastore=rest` | `org.apache.iceberg.spark.SparkCatalog` + `type=rest` |
| 扩展 | `PaimonSparkSessionExtensions` | `IcebergSparkSessionExtensions` |
| `uri` | 同样是基址，不带 `/v1` | 同 |
| `warehouse` | 同样是 **prefix** | 同（两侧共用一套 prefix 约定） |
| 建的「库」叫什么 | database | namespace（`CREATE NAMESPACE` / `CREATE DATABASE` 都能用） |
| 建表语法 | `USING paimon`，分区用 `partitionKeys` 表达式、主键用 `primary key` | `USING iceberg`，分区用 `PARTITIONED BY` 的 transform、没有主键概念 |
| 改表 | catalog 自己一套端点 | 「更新代数」：`requirements` + `updates` |
| 删表 | 一次 DELETE | `DROP TABLE` = 注销（不删文件）/ `PURGE` = 删数据 |
| 非空库能删吗 | — | 不能：规格要求 409 `NamespaceNotEmptyException`，**不做静默级联** |
| 表位置谁定 | 服务端按 `path-template` 推 | 同（默认 `{warehouse}/{database}.db/{table}`，数据库名即 namespace） |
| 元数据文件 | 服务端物化 `schema/schema-<n>`、`snapshot/snapshot-<n>` | 服务端物化 `metadata/00001-<uuid>.metadata.json`（序号 5 位补零，方便 `ls`） |

**同一个仓库目录不能同时是两种格式。** 一个 catalog 一种格式、建完不可改
（`catalogFormat` 不可变）。想让同一个仓库路径两边都能读，做不到：那是两个 catalog，
各自的元数据互相不可见。这条写进了 README 的已知边界。

**走错面的表现是「指名原因的 404」**：拿 Iceberg 路径请求一个 Paimon catalog，
报文会说 `prefix "xxx" is a PAIMON catalog; ICEBERG REST paths need an ICEBERG catalog`，
而不是让你对着一个空白结果猜。

---

## 4. 常见报错对照

| 现象 | 原因 | 怎么办 |
| --- | --- | --- |
| `UnsupportedOperationException: Server does not support endpoint <…>` | 客户端要用的端点不在 `/v1/config` 下发的 `endpoints` 清单里 | 一期不做的 9 个端点就是这样（第 5 节）。剩下的情况说明清单与实现不一致，那是服务端的 bug |
| 启动时 `Unable to process` / 报错里没有 message | 该请求所属的**面**判错了，拿到另一种形状的错误体 | 检查 `uri` 后面有没有多写 `/v1`，以及 `warehouse` 是不是拼错了 catalog 名 |
| 404，报文含 `is a PAIMON catalog` | 用 Iceberg 客户端连了一个 Paimon catalog | 管理 API 里建一个 `catalogFormat=ICEBERG` 的 catalog，`warehouse` 填它的名字 |
| `NoSuchNamespaceException` / `NoSuchTableException` | 404 的两种细分，被客户端翻成了具体异常 | 这就是对的：说明错误体接上了。名字或 namespace 拼错了 |
| `NamespaceNotEmptyException` | 删一个还有表的 namespace | 先删表。规格要求如此，服务端不做级联 |
| `AlreadyExistsException` | 建重名的 namespace / 表 | — |
| `400 invalid_scope: … only accepts PRINCIPAL_ROLE:ALL` | 用了 `credential=` 而没配 `scope` | 见 1.3 第 2 个坑：加 `spark.sql.catalog.<名>.scope=PRINCIPAL_ROLE:ALL` |
| `NoClassDefFoundError: org.apache.hadoop.conf.Configurable` | 在**裸 JVM** 里用 Iceberg 客户端，默认 `io-impl` 的 `ResolvingFileIO` 需要 Hadoop | 换 `io-impl`，或把 Hadoop 加进客户端的类路径。Spark 里不会遇到 |
| `IllegalArgumentException: Invalid codec name: <路径>` | 不会出现在客户端——这是服务端曾经的一个 bug，已在 `IcebergMetadataFiles` 修掉，记在这里只为报错搜索能命中 | 升级到含该修复的版本 |
| 一次 `UPDATE` / `INSERT` 之后仍然读到旧数据 | 客户端开了元数据缓存 | `spark.sql.catalog.<名>.cache-enabled=false` 关掉，或刷新会话 |
| `400 Malformed request: stage-create is not supported by this catalog: multi-table transactions (POST /v1/{prefix}/transactions/commit) are not implemented` | 用了 `CREATE TABLE ... AS SELECT`。Spark 把它翻成 staged create，提交要走未实现的 `transactions/commit` | 拆成 `CREATE TABLE` + `INSERT`（§2.1） |
| `NoSuchNamespaceException: The given namespace does not exist: <catalog>.<库>` | `ALTER TABLE ... RENAME TO` 的目标写了三段全限定名，Spark 不剥 catalog 前缀 | 目标只写「库.表」（§2.6） |
| `NoSuchNamespaceException: The given namespace does not exist: <库>.<表>` | 用 SQL 查元数据表（`…<表>.snapshots`），Spark 把「库.表」当成两级 namespace | 元数据表别用 SQL 查（§2.2 末） |

---

## 5. 已知边界：一期不做的 9 个端点

规格共 35 个「路径 × 方法」组合，一期实现 26 个。**不做的 9 个不出现在
`/v1/config` 的 `endpoints` 清单里**，因此客户端会在本地就拒掉相应操作——
这是有意的：声明了就等于承诺了它存在。

| 端点 | 归入 | 撞上时的表现 |
| --- | --- | --- |
| `GET …/namespaces/{ns}/functions` | 二期（只读投影） | 客户端拒在本地 |
| `GET …/namespaces/{ns}/functions/{fn}` | 二期 | 同上 |
| `POST …/namespaces/{ns}/register-view` | 二期 | 同上 |
| `POST …/transactions/commit` | 二期（跨表原子提交 + `requirements` 对齐） | 客户端拒在本地。**多表事务不可用**；单表 `INSERT` / 改 schema 不受影响，但 **CTAS 受影响**——Spark 的 `CREATE TABLE ... AS SELECT` 走 staged create，提交路径正是这条端点，因此会被服务端指名拒绝（§2.1） |
| `POST …/tables/{t}/plan` | 不做（要常驻扫描规划引擎） | 同上 |
| `GET …/tables/{t}/plan/{plan-id}` | 不做 | 同上 |
| `DELETE …/tables/{t}/plan/{plan-id}` | 不做 | 同上 |
| `POST …/tables/{t}/tasks` | 不做 | 同上 |
| `POST …/tables/{t}/sign` | 不做（远程签名 KMS） | 同上 |

这条清单在代码与校验脚本里各有一份副本，逐字一致由
`scripts/verify-iceberg-endpoints.py` 守（它做的是「规格 ↔ 声明的清单 ↔ 控制器映射」
三方比对，不是照抄）。想确认某个能力在不在，读一次 `/v1/config` 就行，不用查文档：

```bash
curl -s "http://localhost:8080/v1/config?warehouse=iceberg_demo" | jq -r '.endpoints[]'
```

控制台的 catalog 详情页会把这份清单摆到页面上（设计见
[`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §7）。

---

## 6. 怎么验证

| 想验的 | 跑什么 |
| --- | --- |
| 端点的形状（状态码、字段名、落盘、错误体） | `IcebergRestCatalogApiTests`（10 个用例） |
| 官方客户端连得上、能力清单协商、错误体翻异常、更新代数 | `IcebergClientEndToEndTests`（3 个用例，真 HTTP） |
| Spark 真的建表 / 写入 / 改 schema / 重命名 | `IcebergTableDdlTests`（10 个用例）。它是 `scripts/e2e-spark-sql.sh` 那一批里的一类，**必须与另外五个类同批运行**（一个 JVM 只能有一个 SparkContext），见 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) §6 |
| 两个身份在 Iceberg 面各能做什么（含被拒报文点名哪项权限） | `SparkSqlAuthorizationTests` 的两条 `icebergCatalog.*` 用例（同批 4 条：Paimon 两条 + Iceberg 两条） |
| 两个协议面共存的静态一致性 | `scripts/verify-iceberg-endpoints.py` |
| 每个端点都在授权映射里登记过 | `CatalogEndpointAuthorizationTests` |

只跑本文这一层（先起一个服务端，见 `spark-paimon-rest-e2e.md` §6 的启动命令）：

```bash
mvn -o -pl :lakehouse-rest-spark-3.5 test -Dtest=IcebergTableDdlTests \
  -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
  -De2e.management.token=root
```

单独跑这一层时，`LiveSparkSession` 会在会话里同时配好 Paimon 与 Iceberg 两个 catalog
（扩展只在建会话时生效，一个 JVM 只有一个 SparkContext），因此它不依赖同批的其它类；
反过来，整批跑的时候它共用同一个会话。

跑服务端全量：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -o -pl :lakehouse-rest-server -am test
```
