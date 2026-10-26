# 用 Spark SQL 通过 LakeHouse Catalog 建表

本文说明怎么把 Spark 接到本服务端的 LakeHouse Catalog 上、用 SQL 建 Paimon 表，
以及哪些能力已经可用、哪些还不在范围内。

会用到本文的场景：

- 想知道 Spark 侧的连接配置怎么写；
- 想用 SQL 建表并确认元数据真的落到了服务端；
- 遇到报错想快速对照原因（见第 5 节）。

可执行的验收用例在 `PaimonTableDdlTests`（Paimon 表）与 `NonPaimonTableSqlTests`
（Format / Object / Lance 三种非 Paimon 表），跑法见第 6 节。

---

## 1. Spark 侧需要什么

### 1.1 依赖

| 构件 | 版本 | 说明 |
| --- | --- | --- |
| `org.apache.paimon:paimon-spark-3.5_2.12` | `2.0.0` | Paimon 的 Spark bundle，自带 Paimon 核心与文件系统实现，并把 Guava / Jackson / netty 等 shade 到 `org.apache.paimon.shade.*`，因此不会与 Spark 自带的依赖冲突 |
| `org.apache.spark:spark-hive_2.12` | 与 Spark 同版本 | Paimon 的 Spark 扩展在初始化时引用其中的 `HiveUDFExpressionBuilder`。用 `spark-submit` / `spark-sql` 时发行版自带，直接跑 JUnit 时要显式引入 |

构件名带 Scala 二进制版本后缀（`_2.12`），换 Spark 或 Scala 版本时必须同步换。

本项目把这两项声明为 `lakehouse-rest-spark` 的**测试**依赖（`lakehouse-rest-spark/pom.xml`），
发布产物里不含它们——本模块自己只做管理语句扩展，不依赖 Paimon 运行时。

**warehouse 落在对象存储上时还要补一样**：对应 scheme 的 FileIO 实现。
`paimon-spark` bundle 只带本地文件系统与 Hadoop 回退，catalog 的仓库是 `s3://…` 时，
客户端会以

```
org.apache.paimon.fs.UnsupportedSchemeException: Could not find a file io implementation
for scheme 's3' in the classpath. Hadoop FileSystem also cannot access this path 's3://…'
```

失败。这条报错来自**引擎的类路径，与服务端无关**：服务端做不做 S3 都改变不了它。
S3 加 `org.apache.paimon:paimon-s3:2.0.0`（OBS / OSS 分别是 `paimon-obs` / `paimon-oss`），
生产上放进 `spark/jars`，本仓库的测试已经在 `lakehouse-rest-spark/pom.xml` 里声明。

### 1.2 会话配置

```properties
# 两个扩展都要装，缺 Paimon 那个会被它自己的启动检查拦下
spark.sql.extensions=io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions,org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions

spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog
spark.sql.catalog.paimon.metastore=rest
spark.sql.catalog.paimon.uri=http://catalog-host:8080
# 这里填的是 catalog 的 prefix，不是文件路径：
# 客户端先请求 GET /v1/config?warehouse=paimon，服务端用仓库名换回 prefix，之后所有路径都以它开头
spark.sql.catalog.paimon.warehouse=paimon
spark.sql.catalog.paimon.token.provider=bear
spark.sql.catalog.paimon.token=<调用者主体的令牌>
# 仓库在对象存储（s3:// / obs:// / oss://）上时**必须**打开，见 1.4
spark.sql.catalog.paimon.data-token.enabled=true
```

两个扩展的顺序有讲究：本项目的扩展先用自己的解析器识别管理语句，识别不了就原样交回
Spark 原生解析器，与 Paimon 注入的规则不重叠，因此谁在前都能工作，但把它放在前面更符合
「先尝试扩展语法、失败即回落」的读法。

`spark.sql.extensions` 只在**建会话那一刻**生效，会话建好之后再设不会生效。

### 1.3 手工验证

```bash
spark-sql \
  --jars paimon-spark-3.5_2.12-2.0.0.jar \
  --conf spark.sql.extensions=io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions,org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions \
  --conf spark.sql.catalog.paimon=org.apache.paimon.spark.SparkCatalog \
  --conf spark.sql.catalog.paimon.metastore=rest \
  --conf spark.sql.catalog.paimon.uri=http://127.0.0.1:8080 \
  --conf spark.sql.catalog.paimon.warehouse=paimon \
  --conf spark.sql.catalog.paimon.token.provider=bear \
  --conf spark.sql.catalog.paimon.token=root
```

### 1.4 对象存储的凭据从哪来

仓库在 `s3://`（或 `obs://` / `oss://`）上时，客户端自己**没有任何密钥**——
端点和密钥都由服务端下发，这正是 REST 目录存在的意义。链路是：

1. 类路径里要有对应 scheme 的 FileIO 实现，见 1.1；
2. 会话里要打开 `data-token.enabled`，见 1.2；
3. 客户端在新建 FileIO 之前请求
   `GET /v1/{prefix}/databases/{db}/tables/{t}/token`，服务端按 catalog 的
   `storageConfigInfo` 返回一份短时效令牌（键值对 + 有效期）。

服务端返回的令牌里，S3 的**密钥有两个名字**，都要给：

```
s3.access-key-id / s3.secret-access-key   # 规格（Polaris / Iceberg）的叫法
s3.access-key    / s3.secret-key          # Paimon 引擎的叫法
```

Paimon 拿到令牌后会把所有 `s3.*` 键按前缀整体翻译成 Hadoop 的 `fs.s3a.*`
（`s3.access-key` → `fs.s3a.access-key`），再做一次小范围镜像
（`fs.s3a.access-key` → `fs.s3a.access.key`）。于是 `s3.access-key-id` 只会变成
`fs.s3a.access-key-id`——Hadoop 不认识这个键，镜像表里也没有它，**密钥被静默丢弃**，
引擎退回默认凭据链（环境变量 / 实例身份），在测试机上必然认证失败。
定位类键名同理：`s3.endpoint` → `fs.s3a.endpoint`、`s3.region` → `fs.s3a.region`
都能对上，`s3.path-style-access` 会被镜像成 Hadoop 的
`fs.s3a.path.style.access`。

> **`endpoint` 写裸 IP 时注意路径风格。** 关掉 path-style 后，客户端会按虚拟主机风格
> 把桶名拼进主机名（`<bucket>.<endpoint>`）去解析；`endpoint` 是
> `http://172.18.6.181:9330` 这种裸 IP 时，拼出来的主机名解析不了。
> 这类对象存储上的 catalog 一般要把 `storageConfigInfo.pathStyleAccess` 设为 `true`。

---

## 2. 能执行的 SQL

**库名与表名一律用全限定名**（`paimon.<库>.<表>`）。`USE <库>` 只切换当前库、
不切换 catalog，漏掉 catalog 前缀会把表建到 `spark_catalog` 里，后续操作会以
「paimon is not a valid Spark SQL Data Source」收场（见第 5 节）。

### 2.1 建库

```sql
CREATE DATABASE IF NOT EXISTS paimon.demo;
```

### 2.2 建分区追加表

```sql
CREATE TABLE IF NOT EXISTS paimon.demo.orders (
  id     BIGINT        COMMENT '订单号',
  amount DECIMAL(10,2),
  dt     STRING        COMMENT '分区日'
) USING paimon
PARTITIONED BY (dt)
COMMENT '订单表'
TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id');
```

`'bucket-key'` 不能省：追加表用固定 bucket 时 Paimon 要求显式指定分桶键，否则建表被拒
（`You should define a 'bucket-key' for bucketed append mode`）。想用动态分桶就写
`'bucket' = '-1'`。

### 2.3 建主键表

```sql
CREATE TABLE IF NOT EXISTS paimon.demo.dim_user (
  user_id BIGINT,
  region  STRING,
  name    STRING
) USING paimon
TBLPROPERTIES ('primary-key' = 'user_id', 'bucket' = '1');
```

主键必须用表选项写。Spark 3.5 的解析器不接受把主键写在列定义里
（`..., PRIMARY KEY (user_id) NOT ENFORCED)` 会直接 `ParseException`），
这一点与 Paimon 文档里 Flink 侧的写法不同。

### 2.4 其他形态

```sql
-- 未分区表
CREATE TABLE paimon.demo.flat (id BIGINT, note STRING) USING paimon
TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id');

-- 未知选项原样保留（Paimon 的表选项是开放的）
CREATE TABLE paimon.demo.with_opts (id BIGINT) USING paimon
TBLPROPERTIES ('bucket' = '1', 'bucket-key' = 'id',
               'custom.retention' = '7d', 'snapshot.num-retained.min' = '3');

-- 复制结构（含分区键），不复制数据
CREATE TABLE paimon.demo.dst LIKE paimon.demo.orders;
```

### 2.5 查看

```sql
SHOW TABLES IN paimon.demo;
DESCRIBE paimon.demo.orders;
SHOW CREATE TABLE paimon.demo.orders;
```

时间旅行：

```sql
-- 数字是**快照 id**（客户端从 1 开始递增分配），不是「第几个版本」，
-- 也不是快照 JSON 里的 version——后者是快照文件格式版本，每个快照都是同一个值（当前 3）
SELECT * FROM paimon.demo.orders VERSION AS OF 2;

-- 标签名也可以放在这里：标签本身就是「给某个快照起的名字」
SELECT * FROM paimon.demo.orders VERSION AS OF 'v1';
```

服务端对 `version` 的解析顺序与 Paimon 客户端一致：`EARLIEST` / `LATEST` / 数字（快照 id）/
标签名，前三者之外的一律按标签名查，取不到回 404。写成 `VERSION AS OF 3` 而表里只有快照
1 与 2 时，得到的是 404，而不是「第 3 版」——这一点容易与「版本号」的直觉搞混。

---

## 3. 服务端保存了什么

建表请求由 Paimon 客户端发出，服务端按 catalog OpenAPI 规格接收。用 HTTP 直接读元数据
看到的形状（节选自真实响应）：

```bash
curl -H 'Authorization: Bearer root' \
  http://127.0.0.1:8080/v1/paimon/databases/demo/tables/orders
```

```json
{
  "database": "demo",
  "name": "orders",
  "path": "file:///tmp/paimon-warehouse/demo.db/orders",
  "isExternal": false,
  "schemaId": 0,
  "schema": {
    "comment": "订单表",
    "fields": [
      { "id": 0, "name": "id",     "type": "BIGINT",        "description": "订单号" },
      { "id": 1, "name": "amount", "type": "DECIMAL(10, 2)" },
      { "id": 2, "name": "dt",     "type": "STRING",        "description": "分区日" }
    ],
    "options": { "bucket": "1", "bucket-key": "id" },
    "partitionKeys": ["dt"],
    "primaryKeys": []
  }
}
```

三点值得留意：

- **表注释与列注释都真的传到了服务端**：客户端侧 `SHOW CREATE TABLE` 能显示注释，
  但那是它自己拼的字符串，只有读服务端元数据才能确认注释没有在路上丢。
- **`path` 由服务端的仓库位置推导**：管理 API 改过 catalog 的 `storageConfigInfo` 之后，
  新建的表就会落到新位置下——这是管理面与数据面共享同一份配置的证据。
- **`primaryKeys` 与 `options` 是两个独立字段**：主键用表选项表达时，服务端会把它
  归一化进 `primaryKeys`。

---

## 4. 服务端会物化 Paimon 表目录

本服务端保存**服务端数据库里的元数据**，并把其中引擎读写必需的那一部分
**物化到 Paimon 表目录**——具体是 `schema/schema-<n>` 与 `snapshot/snapshot-<n>`
（外加 `snapshot/LATEST` 提示文件）。因此：

| 能力 | 状态 |
| --- | --- |
| 建库、建表（含分区、主键、选项、注释、`LIKE`） | 可用 |
| `SHOW TABLES` / `DESCRIBE` / `SHOW CREATE TABLE` | 可用（读的是服务端元数据） |
| `DROP TABLE` / `DROP DATABASE` | 可用 |
| `INSERT` / `CREATE TABLE ... AS SELECT` / `SELECT` 数据 | 可用 |
| 绕开服务端、直接读仓库的读端（Paimon CLI、以文件系统为源的快照枚举等） | 可用（元数据已落进仓库） |

### 4.1 为什么必须物化 schema 与快照文件

`INSERT` 走到提交快照那一步时，引擎会在**仓库**里读 `<table>/schema/schema-<n>`
取 schemaId。这个文件不在，就抛：

```
RuntimeException: Exception occurs when preparing snapshot #1 by user <uuid> with hash
  9223372036854775807 and kind APPEND. Clean up.
  -> RuntimeException: Cannot get latest schema for table orders
```

并回滚这次提交，把已经写好的 parquet 文件与 manifest 留在目录里当孤儿。
所以缺的从来不是「服务端拒绝写入」，而是「服务端把 schema 存在自己库里、
却没落到引擎要看的那个位置」。

**这份责任为什么在服务端。** Paimon 的 REST Catalog 客户端是瘦客户端：
`RESTCatalog.createTable` 只把 schema POST 给服务端，客户端代码里根本没有写
`schema/schema-<n>` 的路径。对照之下，`HiveCatalog`（自己就是表的拥有者）
的 `createTableImpl` 会先 `SchemaManager.createTable` 写目录与 schema 文件、
再登记 HMS。换成 REST 形态，这一步就落到了服务端——官方 REST 服务端是内嵌一个
文件系统 catalog 来做的，本工程按同样的分工实现，见 `TableMetadataService`。

**号必须与数据库里的 `schemaId` 逐号对应**，这不是格式问题而是语义问题：

| 动作 | 写出的文件 | `schemaId` |
| --- | --- | --- |
| 建表 | `schema-0` | `0` |
| `ALTER TABLE` | `schema-<n>` | `n`（递增） |
| `rollback-schema` | 把旧内容写到 `schema-<新号>` | 回滚在版本号上也是**向前一步**，不复用旧号 |

落库与落仓库的顺序也固定：**先落库再落仓库**。严格模式（见 4.2）下仓库写入失败会
让整个事务回滚，catalog 里不会留下一张没有 schema 文件的表；反过来先写仓库再落库，
失败时留下的是仓库里的孤儿目录，而那种残留没有任何接口能清理。

挂接点是 `TableService` 的 create / alter / rollback-schema / drop / commit / rollback
六处。`register` **刻意不挂**：它的语义是「仓库里已经有一张表，本服务端只登记它的位置」，
往那个目录里写 schema 等于用服务端的空 schema 覆盖那张表的真实元数据，是数据损坏
而不是补全。`external=true` 的表在后续所有写入路径上同样被跳过。

**快照是同一件事的另一半，而且它的缺失更隐蔽。** 提交快照时，`SnapshotCommit` 实现的选择在
`CatalogEnvironment.snapshotCommit`：`catalogLoader != null && supportsVersionManagement`
时用 `CatalogSnapshotCommit`（把 `Snapshot` 对象 POST 给 `/tables/{t}/commit`），
否则才用 `RenamingSnapshotCommit` 自己写 `snapshot/snapshot-<n>`。
`RESTCatalog.supportsVersionManagement()` **恒返回 true**，所以走 REST catalog 的客户端
从不写快照文件，`RenamingSnapshotCommit` 在这条链路上根本不会被实例化。

不写会怎样：**用 REST catalog 读写一切正常**——读路径也走服务端，
`SnapshotLoaderImpl.load()` 调的就是 `catalog.loadSnapshot(identifier)`，
查的是服务端数据库。于是「查得出来数据」与「仓库里没有 snapshot 目录」可以同时成立，
只有绕开服务端直接看仓库时才会露出来。本服务端因此把
`RenamingSnapshotCommit.commit` 的两步（原子写 `snapshot/snapshot-<id>` + 更新
`snapshot/LATEST`）在服务端重做一遍。

写进仓库的内容是**客户端发来的那一段 JSON 原文**，不是按服务端数据库字段重新拼的：
规格里的 `Snapshot` 只建模了一部分字段，客户端的 `org.apache.paimon.Snapshot` 还带
`properties`（序列号水位）、`operation`、`nextRowId` 等。按 DTO 重拼会静默丢字段，
而丢 `properties` 尤其阴——它不会解析失败，只会让读端重算序列号，表现为去重与变更日志
语义悄悄改变。快照文件同样先解析一遍 Paimon 自己的模型再落盘：写进去的东西至少要能被
Paimon 读回来。

回滚快照（`rollback`）会把目标之后的 `snapshot-<id>` 删掉、把 `LATEST` 指回目标。
只改库不删文件，留下的是「数据库说只有快照 1、仓库里有 1/2/3 且 `LATEST` 指着 3」。

### 4.2 三档开关

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `lakehouse.rest.table-metadata.enabled` | `true` | 是否物化 schema 与快照。`external` 表始终不物化 |
| `lakehouse.rest.table-metadata.fail-on-error` | `false` | 物化失败时是否让请求失败（500 且事务回滚）。默认「尽力而为」：记一条 WARN，元数据照常落库；打开后建表与提交快照会因仓库不可写而直接失败 |
| `lakehouse.rest.table-metadata.purge-on-drop` | `false` | `DROP TABLE` 时是否连表目录一起删。默认只删元数据、保留数据，删错不可逆 |

**仓库在对象存储上时，服务端自己也要有对应 scheme 的 FileIO 实现。**
这一步与第 1.1 节客户端那件事是**两件独立的事**，缺任何一件都在各自的进程里失败：

- 客户端缺 → `UnsupportedSchemeException`，报在 Spark 侧；
- 服务端缺 → 物化失败，报在服务端日志里（`fail-on-error=false` 时只是一条 WARN，
  于是表现又回到 `Cannot get latest schema`）。

`paimon-core` 自带的 SPI 实现只有 `file` / `hdfs` / `viewfs` 三种（启动后日志会打一行
`discovered Paimon FileIO implementations for schemes [...]`，可以据此确认）。
`s3://` / `obs://` / `oss://` 要按 Paimon 的插件机制额外把对应构件放进服务端类路径，
例如 `org.apache.paimon:paimon-s3:<版本>`。

**对象存储上还有第二个前提：服务端运行时必须是 JDK 17，不能是 18 及以上。**
JDK 18 的 JEP 418 给 `InetAddress` 加了解析器 SPI，`InetAddress.loadResolver()` 会
`ServiceLoader` 找一个 `InetAddressResolverProvider`；而 `paimon-s3` 插件包把 dnsjava 那个
provider 的**声明**放在插件根、**实现类**放在 `META-INF/versions/18/`（多版本 jar 布局，
解成插件目录后不再生效），`PluginFileIO` 又把线程上下文类加载器切成插件 loader，
于是那块作用域里**任何** `InetAddress` 解析都抛
`ServiceConfigurationError: ... DnsjavaInetAddressResolverProvider not found`，
触发点是 Hadoop `MetricsSystemImpl.getHostname()`。后果是 `s3://` 上的
`<table>/schema` 与 `<table>/snapshot` 一个文件都写不出去——而因为是插件隔离的异常类，
现象与 S3 看着毫无关系。`Dockerfile` 的运行镜像钉 17 就是为了这条。

### 4.3 这两个边界用例为什么会翻转

`PaimonTableDdlTests.createTableAsSelectWritesDataAndReadsItBack`
与 `PaimonRestCatalogTest.insertWritesThroughAndIsReadable` 曾经断言的是写入**失败**，
并且把失败链钉在某一步上。那是刻意的**守卫用例**：断言「当前不可用」而不是
「期望不可用」，边界一移动就先响，提醒改动者回来同步本节。

schema 物化补上之后它们已经翻转成断言写入成功。这个类保留下来的、仍然压着
「失败停在哪儿」的部分是**别的东西**：写入必然要碰 `s3://` 上的 `<table>/schema`
与 `<table>/snapshot`，因此它也是客户端第一次真正访问对象存储的地方——停在
`UnsupportedSchemeException` 说明客户端类路径缺 FileIO 实现（见 1.1）；
停在一句 `UnknownReason` 说明凭据没按引擎认的键名送达（见 1.4）。

### 4.4 三种非 Paimon 表（Format / Object / Lance）的能力边界

`NonPaimonTableSqlTests` 把这三种表在 Spark + REST catalog 组合下的真实能力钉住了。
边界都来自 Paimon 自己的实现（`SparkCatalog.loadSparkTable` 的分派），不是本服务端的发明：

| 表类型 | 建表 | 写入 | 查询 | 说明 |
| --- | --- | --- | --- | --- |
| Format 表 | ✅ | ✅ | ✅ | 数据就是表目录下同格式的普通文件（`file.format` 决定 csv / parquet…），无快照、无 manifest |
| Object 表 | ✅ | ❌ | ✅ | 列固定为 path / name / length / mtime / atime / owner，用户声明的字段被服务端替换（见 `TableKinds`）；查询 = 递归列出**对象目录**里的文件（目录由建表时的 `path` 指定，见下） |
| Lance 表 | ✅ | ❌ | ❌ | Spark 侧包装类的 capabilities 为空集，读写都在分析阶段被拒（`Table <x> does not support batch scan.` / `does not support append in batch mode.`），与 lance 运行时在不在类路径无关（理由见下） |

**为什么 Lance 表是空集——不是缺 jar，是上游还没实现。** 三处证据：

- `org.apache.paimon.table.lance.LanceTable` 的接口 Javadoc 原文：*"A lance table format
  table, paimon does not support read and write operation on this table yet."*
- `LanceTableImpl implements ReadonlyTable, LanceTable`，但 `newScan()` 与 `newRead()`
  都直接抛 `UnsupportedOperationException`，两条消息还互相指路（`use newRead() instead` /
  `use newScan() instead`）——两个方向都是占位，没有实现。
- Spark 侧 `SparkTable.scala` 里 `case class SparkLanceTable(table: Table) extends BaseTable`
  既不覆写 `capabilities()`（继承 `BaseTable` 的空集），也没有 `newScanBuilder`。
  同一文件里的对照：`SparkObjectTable extends BaseTable with SupportsRead` 声明
  `JEnumSet.of(BATCH_READ)` 并挂 `ObjectTableScanBuilder`；`PaimonFormatTable` 声明
  `EnumSet.of(BATCH_READ, BATCH_WRITE, OVERWRITE_DYNAMIC, OVERWRITE_BY_FILTER)`。
  （同样空壳的还有 `SparkIcebergTable`。）

两条报错出自 Spark 的 `TableCapabilityCheck` 规则（`spark-sql`），消息由
`QueryCompilationErrors.unsupportedTableOperationError` 按
`Table <x> does not support <operation>.` 拼出，`operation` 取 `batch scan` /
`append in batch mode`。也就是说它们在**分析期**就抛出——Paimon 的 `LanceTableImpl`
根本没被调用，更没走到文件系统或 lance 的原生代码。补 `com.lancedb:lance-core`
之类的 jar 不会改变结论：上游 2.0.0 的 `paimon-lance` 模块（真正的 lance 集成）在
`paimon-spark-common` 的 pom 里是 `test` scope 且主动 exclude 了 `lance-core`，
本项目 `lakehouse-rest-spark` 的依赖树里也完全没有 lance 相关 artifact。
要读 Lance 数据得用 Lance 生态自己的 Spark 连接器，拿 `LanceTable.location()` 指过去；
Paimon 的 Spark 连接器在当前版本不解。

两个相关的服务端行为，测试连同理由一起钉住：

- **Format / Lance 表不物化 `schema/schema-0`**。Format 表的读路径把表目录下所有
  非隐藏文件当数据，写进去的 JSON schema 文件会混进查询结果（parquet 直接报
  `not a Parquet file`，CSV 更糟——不报错、当数据行返回）；它的写路径
  （`FormatTableCommit`）是纯文件系统级提交，也不需要 schema 文件。Lance 目录
  布局由 lance 自己管理，同样不写。见 `TableMetadataService.schemaFileIsForeign`。
- **Object 表物化 `schema/schema-0`**。这镜像上游 `JdbcCatalog.createObjectTable` 的
  行为——代价是查询结果会列出这份 schema 文件自己（`path = schema/schema-0`），
  测试按这个真实行为断言，防止有人单方面改掉。**例外**：建表时客户端自己给了 `path`
  的表按外部表登记，一律不物化（那个目录里是用户自己的对象，见下）。

### 4.4.1 Object 表的数据怎么进去，以及表位置由谁决定

Object 表没有 `INSERT`，也不需要 `INSERT`：它不存数据，行是**读的时候现列目录**
算出来的（`ObjectTableImpl.ObjectRead` → `fileIO.listFilesIterative`，一次扫描只带
一个目录位置、不带快照）。所以「写入」= 用存储侧的手段把对象放进那个目录：
`aws s3 cp`、`hdfs dfs -put`、应用自己的 SDK PUT，或 Spark 把 DataFrame 写成普通文件。
放进去下一次查询就能看到，**没有 refresh 这一步**——上游 1.0 时代 Flink 连接器里的
`CALL sys.refresh_object_table(...)` 与 `object-location` 选项在 2.0.0 里已不存在
（2.0.0 的所有 paimon jar 里都搜不到这两个字符串），Spark 侧从来就没有过刷新入口。

那么「那个目录」是哪个目录？通过 REST catalog，它是**服务端回给客户端的表位置**
（`GetTableResponse.path`），不是客户端留在 `options.path` 里的值：

```java
// org.apache.paimon.rest.RESTCatalog#toTableMetadata
Map<String, String> options = new HashMap<>(schema.options());
options.put(PATH.key(), response.getPath());   // put，不是 putIfAbsent
```

即客户端 load 表时，`options.path` 会被响应里的表位置**覆盖**。这条曾经造成过一个
真实的坑：服务端只在 `options` 里留着客户端给的目录，引擎读的却是按 `path-template`
分配的那个目录，于是往对象目录里放的东西一个也查不到（实测能看到的只有服务端自己
写的 `schema/schema-0`）。现在服务端把客户端给的目录**同时**写成表位置与
`options.path`，两者恒一致；这样 `CREATE TABLE ... TBLPROPERTIES ('path' = '<对象目录>')`
就能索引一个已经存在的目录（S3 上的图片前缀、HDFS 上的文件目录），与上游
`JdbcCatalog.createObjectTable` 的 `putIfAbsent` 语义对齐。两点后果一起被用例钉住：
表按外部表登记（`isExternal=true`），因此不往对象目录写 `schema-0`、删表也不删它的目录。

不给 `path` 时行为不变：位置按模板分配，Object 表照旧物化 `schema-0`
（所以查询会把这行元数据也列出来）。

---

## 5. 常见报错对照

| 报错 | 原因 | 处理 |
| --- | --- | --- |
| `paimon is not a valid Spark SQL Data Source` | 表被建到了 `spark_catalog` 而非 `paimon` catalog。`USE <库>` 只切库不切 catalog，漏写全限定名就会走到 `spark_catalog` 的 V1 数据源解析 | 用 `paimon.<库>.<表>` 全限定名。**不要**靠 `spark.sql.sources.useV1SourceList` 加 `paimon` 来绕：那会让 `CREATE TABLE ... USING paimon` 反而走 V1 路径而失败 |
| `When using Paimon, it is necessary to configure 'spark.sql.extensions' and ensure that it includes 'org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions'` | `spark.sql.extensions` 里少了 Paimon 的扩展 | 两个扩展都写上，见 1.2 |
| `ParseException`（无正文），语句里含 `PRIMARY KEY (...) NOT ENFORCED` | Spark 3.5 的 `CREATE TABLE` 语法不接受列定义里的主键 | 改用 `TBLPROPERTIES ('primary-key' = '<列>')` |
| `You should define a 'bucket-key' for bucketed append mode` | 追加表**显式**给了固定 `bucket` 却没给分桶键。不给 `bucket` 时 Paimon 用动态分桶，不需要它 | 加 `'bucket-key'`，或改成 `'bucket' = '-1'` |
| `ParseException: ... is a reserved table property` | `TBLPROPERTIES` 里写了 Spark 自己的保留属性（如 `owner`）。这条在客户端解析阶段就被拒，请求根本没到服务端 | 删掉该属性。`owner` 由服务端按提交者自己补，不是客户端该传的字段 |
| `Cannot get latest schema for table <表名>` | 引擎在仓库里读不到 `<table>/schema/schema-<n>`。服务端已负责物化它（见第 4 节），因此这条报错现在只意味着**物化那一步没成功** | 看服务端日志里 `TableMetadataService` 的 WARN：若提示缺对应 scheme 的 FileIO 实现，把 `paimon-s3` / `paimon-obs` / `paimon-oss` 加进服务端类路径；若服务端根本不该写仓库，把 `lakehouse.rest.table-metadata.enabled` 设为 `false` 并把 `fail-on-error` 打开，让它在建表时就明确失败，而不是拖到写入 |
| 服务端 WARN 里出现 `ServiceConfigurationError: java.net.spi.InetAddressResolverProvider: Provider org.xbill.DNS.spi.DnsjavaInetAddressResolverProvider not found` | 服务端跑在 JDK 18+ 上，而 `paimon-s3` 插件包把 dnsjava 那个 provider 的实现类放在 `META-INF/versions/18/`，插件 loader 加载不到 → 该作用域内任何 `InetAddress` 解析都失败（触发点是 Hadoop 度量系统的 `getHostname`） | **把服务端运行时换成 JDK 17**，见 4.2。这是唯一可靠的处理；这条 WARN 会让对象存储上的 schema / 快照都写不出去，而 REST 读写看着一切正常 |
| `Cannot get latest schema for table <表名>`（服务端日志里**没有**对应 WARN） | 物化那一步压根没被触发：`lakehouse.rest.table-metadata.enabled=false`，或者表是**外部表**——`register` 登记进来的，或建表时客户端自己给了 `path` 的非 Paimon 表（见 4.4.1） | 前者打开开关；后者是有意不物化的（位置不归本服务端所有），需要引擎侧自己保证目录里有 schema |
| `Content-Type 'text/plain;charset=UTF-8' is not supported` | 服务端未放宽 JSON 转换器。Paimon 客户端（Apache HttpClient 5）把 JSON 请求体标成 `text/plain`，早期版本会因此 415 | 已由 `JsonContentTypeConfig` 修复；若仍遇到，确认服务端版本 |
| `Table <库.表> does not support batch scan.` / `does not support append in batch mode.` | 表的 `capabilities()` 里没有 `BATCH_READ` / `BATCH_WRITE`。Lance 表（连同 Iceberg 表）在 Paimon 2.0.0 里就是空集——上游只搭了 API 骨架，`LanceTableImpl` 的 `newScan()` / `newRead()` 都抛 `UnsupportedOperationException`，见 4.4 | **不是缺 jar**，补 `lance-core` 也没用。要读 Lance 数据用 Lance 生态自己的 Spark 连接器，拿 `LanceTable.location()` 指过去；Paimon 的 Spark 连接器在当前版本不解 |

---

## 6. 怎么验证

一键跑完文档校验与全部端到端用例（自动起服务端、跑完自动停）：

```bash
./scripts/e2e-spark-sql.sh
```

它执行的六个测试类必须**同批运行**：一个 JVM 只能有一个 SparkContext，而
`spark.sql.extensions` 只在建会话时生效，因此它们共用 `LiveSparkSession` 的会话。
把用桩服务端的 `ManagementSqlExecutionTests` 拉进同一批会先建会话、把端点与扩展都错位，
`LiveSparkSession` 会就此直接报错（而不是静默跑错）。

| 类 | 验什么 |
| --- | --- |
| `ManagementSqlLiveServerTests` | 逐条管理语句对真实服务端验管理 API 契约 |
| `PaimonTableDdlTests` | 建表、写入、读回，并核对落到服务端的 schema / 分区 / 表选项 / 路径 |
| `NonPaimonTableSqlTests` | Format / Object / Lance 三种非 Paimon 表的建表、写入与查询边界，含 Object 表「对象放进目录即可查到」与表位置由客户端 `path` 决定（见 4.4） |
| `IcebergTableDdlTests` | Iceberg 那一侧的同一条链：namespace / 分区变换 / 写入与快照 / 更新代数 / 重命名 / 非空 namespace 不可删，以及 CTAS 的边界（见 [`spark-iceberg-rest-e2e.md`](spark-iceberg-rest-e2e.md) §0.1） |
| `SparkSqlAuthorizationTests` | 同一个 catalog、两种身份下的权限边界；**Paimon 与 Iceberg 两种格式各两条**（见 6.1） |
| `SparkSqlDocExamplesTests` | 执行 `spark-sql-reference.md` 第 10 节的示例 |

**同一个会话里两个 catalog。** `LiveSparkSession` 同时注册
`spark.sql.catalog.paimon_catalog`（Paimon）与 `spark.sql.catalog.iceberg_catalog`（Iceberg），
因为 `spark.sql.extensions` 建会话之后改不了。两种格式的元数据本来就互不可见
（一个 catalog 一种格式），共用一个会话不会互相干扰；Iceberg 那个 catalog 由
`ensureIcebergCatalog()` 先用管理 API 建成 `ICEBERG`——`/v1/config` 那条自动登记
会按部署默认值建成 Paimon，之后每次调用都 404。

只跑建表用例：

```bash
# 先起一个服务端
java -jar lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar \
  --spring.profiles.active=h2 --server.port=18080 \
  --lakehouse.rest.auth.enabled=true --lakehouse.rest.auth.tokens[0]=root \
  --lakehouse.rest.authorization.enabled=true \
  --lakehouse.rest.authorization.service-admins[0]=root \
  --lakehouse.rest.authorization.bootstrap-principal=root

# 对着它跑建表与写入用例
mvn -o -pl :lakehouse-rest-spark-3.5 test -Dtest=PaimonTableDdlTests \
  -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
  -De2e.management.token=root
```

`-De2e.paimon.url` 可显式指定 catalog API 基址；不指定时由
`-De2e.management.url` 去掉 `/api/management/v1` 得到——两者本来就挂在同一个服务端上。

### 6.1 权限边界怎么验

`SparkSqlAuthorizationTests` 用**同一个 catalog、同一张表**跑两种身份：令牌 `alice` 建库建表
并读写，令牌 `bob` 只能读。这类用例要覆盖的不是「授权判定对不对」——那是服务端
`AuthorizationTests` 与 `CatalogEndpointAuthorizationTests` 的活——而是**引擎发出的请求**：
403 会不会被 Paimon 客户端改写成别的错、被拒绝的写入到底有没有落数据。

**这套用例现在两种格式各跑一遍**（4 条）：Paimon 那两条走 `/databases/**`，
Iceberg 那两条（`icebergCatalog.*`）走 `/namespaces/**`，同一批主体、另一套 catalog role。
Iceberg 侧有一条只从它那一侧才看得出来的差异——写数据要 `UPDATE` + `ALTER`
（指标上报与提交是两个端点、两种权限），详见
[`spark-iceberg-rest-e2e.md`](spark-iceberg-rest-e2e.md) §1.5 与
[`authorization.md`](authorization.md) §5.1。

因此服务端要多配两个令牌，并把它们映射到两个主体（`token-principals`）：

```bash
java -jar lakehouse-rest-spring-parent/lakehouse-rest-server/target/lakehouse-rest-server-1.0.0.jar \
  --spring.profiles.active=h2 --server.port=18080 \
  --lakehouse.rest.auto-create-catalog=true \
  --lakehouse.rest.auth.enabled=true \
  --lakehouse.rest.auth.tokens[0]=root \
  --lakehouse.rest.auth.tokens[1]=alice --lakehouse.rest.auth.token-principals.alice=e2e_alice \
  --lakehouse.rest.auth.tokens[2]=bob   --lakehouse.rest.auth.token-principals.bob=e2e_bob \
  --lakehouse.rest.authorization.enabled=true \
  --lakehouse.rest.authorization.service-admins[0]=root \
  --lakehouse.rest.authorization.bootstrap-principal=root

mvn -o -pl :lakehouse-rest-spark-3.5 test -Dtest=SparkSqlAuthorizationTests -DfailIfNoTests=false \
  -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
  -De2e.management.token=root
```

两件事容易踩：

- **两个身份共用一份 SparkContext，只换令牌。** `LiveSparkSession.sessionAs(token)` 调
  `newSession()` 拿到独立 SQLConf 再覆盖 `spark.sql.catalog.<catalog>.token`。
  一个 JVM 只能有一个 SparkContext，`SparkSession.builder().getOrCreate()` 会直接复用已有会话，
  因此不能靠重建会话换身份。**两个 catalog 的令牌一起换**（`paimon_catalog` 与
  `iceberg_catalog`）：身份是「这个会话对服务端是谁」，只换一半会得到最坏的结果——
  Paimon 侧被拦、Iceberg 侧仍以 root 畅通，用例看起来「只读身份写不了 Paimon」，
  其实从没验过 Iceberg。
- **只读身份也必须有 `NAMESPACE_READ_PROPERTIES`。** `SparkCatalog.initialize` 在 Spark 首次
  解析到该 catalog 的限定名时懒加载它，而加载过程会去读默认命名空间 `default` 的属性
  （`GET /v1/{prefix}/databases/default`）。缺了这一项，连第一句 `SELECT` 都到不了表——
  它和「读不读数据」无关，属于「用得了这个 catalog」的门槛。用例的角色定义就是按这条给的。
