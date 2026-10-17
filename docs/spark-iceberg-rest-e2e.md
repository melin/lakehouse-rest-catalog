# 用 Spark SQL 通过 LakeHouse Rest Catalog 建 Iceberg 表

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

本文分三层，**只有前两层是实测过的**：

| 层 | 验的是什么 | 状态与证据 |
| --- | --- | --- |
| 协议层 | 26 个端点回什么：状态码、字段名、`metadata.json` 有没有真的落进仓库、错误体的形状 | **已实测**：`IcebergRestCatalogApiTests`（10 个用例，HTTP 行为） |
| 客户端协商层 | 官方 Java 客户端能不能连上：`/v1/config` 的能力清单协商、嵌套错误体翻不翻得成具体异常、更新代数（`requirements` + `updates`）真跑不跑得通 | **已实测**：`IcebergClientEndToEndTests`（3 个用例，真 HTTP + `org.apache.iceberg.rest.RESTCatalog`） |
| 引擎层 | Spark 起会话、跑 SQL | **未实测**：只静态核对过客户端侧的配置键与行为（`CatalogUtil` / `ResourcePaths` / `AuthManagers` / `RESTUtil` 的字节码与源码），没有真的起过 Spark |

**为什么可以认为第 3 层已经证明了大半。** Spark 的 `SparkCatalog` 自己不实现协议——
它把 SQL 翻成 `org.apache.iceberg.catalog.Catalog` 的调用，REST 那一层由
`RESTCatalog` 承担，也就是 `IcebergClientEndToEndTests` 里跑的那个实现。
所以「建 namespace → 建表 → 改 schema → 重命名 → 删」这条链上的**协议部分**已经验过；
没验的是 Spark 自己的那一层：SQL 解析、`SparkWrite` 写数据文件、以及下面列的配置键
在真实会话里能否生效。

因此本文的 SQL **可以照着跑，但请把第 3 节当参考而不是当实测记录**。
按 `docs/iceberg-rest-catalog-design.md` §11.1 第 13 条的约定，
这里不写任何没跑过的「实测结果」。

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
它**不进本工程的任何模块**——`lakehouse-rest-spark` 只是管理语句的语法扩展，
不依赖任何 Iceberg 运行时（这条由校验脚本守着）。

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
| `warehouse` 填 catalog 名 | 服务端把 `warehouse` 当 **prefix** 用（`CatalogService.resolveForConfig`）。填成 `file:///tmp/warehouse` 会找不到 catalog。留空则落到部署配置的 `paimon.rest.default-prefix` |
| 不用配 `io-impl` | Spark 自带 Hadoop，默认的 `ResolvingFileIO` 能装上。裸 JVM 才需要显式指定 |

**还有一个容易踩的：不要给建表报文塞 `location`。** 表位置由服务端按
`paimon.rest.path-template` 推（默认 `{warehouse}/{database}.db/{table}`），
`CREATE TABLE ... LOCATION '...'` 走的路子要求那个位置落在 catalog 的
`allowedLocations` 里，否则被拒。

### 1.3 认证

本服务端默认**不要求令牌**（`paimon.rest.auth.enabled=false`），
连不上的第一嫌疑不是认证。要按部署形态选：

| 部署形态 | Spark 侧配置 |
| --- | --- |
| 默认（数据面匿名） | 什么都不用配。服务端把认不出主体的请求记为 `paimon.rest.auth.principal`（默认 `anonymous`） |
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

### 1.5 对象存储的凭据从哪来

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

### 2.2 写入与读取

```sql
INSERT INTO ice.orders VALUES (1, 'a'), (2, 'b');
SELECT * FROM ice.orders;

-- 服务端为每次提交物化一份 metadata.json，快照随提交一起落库
SELECT * FROM ice.orders.snapshots;
SELECT * FROM ice.orders.files;
```

### 2.3 改 schema

```sql
ALTER TABLE ice.orders ADD COLUMN extra string;
ALTER TABLE ice.orders RENAME COLUMN data TO payload;
DESCRIBE ice.orders;
```

这一条链是**客户端生成 `requirements` + `updates`、服务端校验后应用并落库**
（Iceberg 的「更新代数」）。`IcebergClientEndToEndTests` 里实测过
`updateSchema().addColumn(...).commit()` 走通，包括服务端返回的新一代元数据能被客户端解析。

### 2.4 分支与标签

```sql
ALTER TABLE ice.orders CREATE BRANCH b1;
ALTER TABLE ice.orders CREATE TAG t1;
SELECT * FROM ice.orders.refs;
```

### 2.5 视图

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
| `POST …/transactions/commit` | 二期（跨表原子提交 + `requirements` 对齐） | 同上。**多表事务不可用**，单表写入不受影响 |
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
| 两个协议面共存的静态一致性 | `scripts/verify-iceberg-endpoints.py` |
| 每个端点都在授权映射里登记过 | `CatalogEndpointAuthorizationTests` |
| Spark 真跑一遍 | **本文没有提供实测记录**（第 0 节）。要落这份记录，照 1.2 配好会话后把第 2 节的 SQL 依次执行，再把 `INSERT` / `SELECT` 的结果与仓库目录里的 `metadata/` 一并记下来 |

跑服务端全量：

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home
mvn -o -pl :lakehouse-rest-server -am test
```
