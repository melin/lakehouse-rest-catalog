# Flink 通过 REST Catalog 访问 Lakehouse

Flink SQL 直接用 LakeHouse Catalog 的 REST 面读写 Paimon 与 Iceberg，不经过本项目
自己的任何代码——引擎侧是 Paimon 的 Flink 连接器与 Iceberg 的 Flink bundle，
服务端只按 REST 契约应答。这个模块存在的意义是：把「怎么连」「怎么验」固定下来，
并让两个 Flink 版本各跑一遍同一份用例。

## 1. 模块结构

```
lakehouse-rest-flink/
├── shared/src/main/java/…/LakehouseRestFlinkCatalogs.java   两套 catalog 选项 + CREATE CATALOG 拼装
├── shared/src/test/java/…/FlinkE2eSupport.java               起 catalog、批模式 TableEnvironment、跑 SQL
├── shared/src/test/java/…/PaimonFlinkSqlTests.java           建表 / 读写 / 主键更新
├── shared/src/test/java/…/IcebergFlinkSqlTests.java          建表 / 读写 / 二次写入
├── lakehouse-rest-flink-1.20/   Flink 1.20.1 + Paimon 2.1.0 + Iceberg 1.11.0
└── lakehouse-rest-flink-2.3/    Flink 2.3.0 + Paimon 2.1.0（2.2 连接器）+ Iceberg 1.12.0
```

**为什么拆两个模块**：Flink 的 `CatalogFactory` / Table 工厂是按版本编译的，
1.20 与 2.x 的字节码不兼容，一份 class 装不进两边；症状不是「找不到类」，
而是 `NoSuchMethodError` 出现在 Catalog 初始化深处。所以沿用 Spark 模块的做法：
源码只有一份（`shared/`），两个版本模块各自用自己的 Flink / 连接器编译与跑测试。

**主源码不 import 任何 Flink 类型**：一旦引了，`shared/` 就必须选一个 Flink 版本编译，
「一份源码」的意义就没了。`LakehouseRestFlinkCatalogs` 只产出 `Map` 与 SQL 字符串。

## 2. 两套 catalog 选项

两边的键名**完全不同**，写混了不会报「参数不认识」，而是静默退化成别的实现：

| | Paimon | Iceberg |
| --- | --- | --- |
| 发现标识 | `type=paimon` | `type=iceberg` |
| 走 REST | `metastore=rest` | `catalog-type=rest` |
| 地址 | `uri` | `uri` |
| 仓库名 | `warehouse=<catalog prefix>` | `warehouse=<catalog prefix>` |
| 认证 | `token.provider=bear`（或 `oauth2`，见 §2.1）+ `token` | `rest.auth.type=oauth2` + `token` + **`scope=PRINCIPAL_ROLE:ALL`** |
| 其它 | `data-token.enabled=true` | `cache-enabled=false` |

两条最容易错的：

- **`warehouse` 是 catalog 的 prefix，不是仓库路径。** 服务端 `GET /v1/config?warehouse=<prefix>`
  拿它换回 prefix，客户端后续所有路径都以它开头。写成真实目录不会报错，只会在服务端
  找不到同名 catalog，症状是 404 或「catalog 不存在」。
- **Paimon 的令牌提供者标识是 `bear`，不是 `bearer`。**

### 2.1 令牌续期（`token.provider=oauth2`）

`bear` 把令牌原样贴上去，不续期：服务端签的票默认一小时到期，跑得比它久的作业
会在某个请求上开始 401。要续期就换成 `oauth2`，选项与 Spark 侧完全同形
（`token` / `oauth2.credential` / `oauth2.token-endpoint` / `oauth2.refresh-window`），
规则见 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) §1.2.1。

**Flink 侧额外注意一件事：provider jar 必须放 `lib/`，不能用 `-j`。**
`FactoryUtil.discoverFactory` 用的是 `AuthProviderFactory` 自己的 ClassLoader，
不是线程上下文 loader；`-j` 的 jar 进的是用户的子 loader，那里注册的服务文件看不见。
放错的症状只有一句 `Could not find any factory for identifier 'oauth2'`，
它不会提示 classloader——这一条实测过，写在这里是为了省掉一次盲查。

Iceberg 侧的续期同样需要 `scope=PRINCIPAL_ROLE:ALL`（上表已列）：客户端默认发的是
`catalog`，服务端只认「不限制」那个取值，缺了它，第一次刷新才报 `invalid_scope`。

Iceberg 的 prefix 必须**先**用管理 API 建成 `ICEBERG`：让客户端撞 `/v1/config` 的自动登记
会按部署默认值建成 PAIMON，此后每次调用都会以 `is a PAIMON catalog` 失败，
而且这个错是第一次请求自己留下的（见 `FlinkE2eSupport#ensureCatalog`）。

## 3. 怎么跑

```bash
# 全量（自己起服务端；默认 18081，跑完自动停）
./scripts/e2e-flink-sql.sh

# 只跑某个版本
ONLY=1.20 ./scripts/e2e-flink-sql.sh
ONLY=2.3  ./scripts/e2e-flink-sql.sh

# 手工指定已有服务端（须已建好 flink_paimon / flink_iceberg 两个 catalog，
# 且能经管理 API 建 FILE 仓库；用例自己会建）
mvn -o -pl :lakehouse-rest-flink-1.20 test \
  -Dtest=PaimonFlinkSqlTests,IcebergFlinkSqlTests \
  -De2e.management.url=http://127.0.0.1:8080/api/management/v1 \
  -De2e.management.token=root
```

没有给 `-De2e.management.url` 时用例**整体 skip**，`mvn test` 在没有服务端时仍是绿的——
不会把「没起服务端」误报成回归。

## 4. 实测结论

| 组合 | 建表 | 写入 / 读回 | 主键更新 | 服务端元数据 |
| --- | --- | --- | --- | --- |
| Flink 1.20.1 + Paimon 2.1.0 | ✅ | ✅ | ✅ | `primaryKeys` 落库 |
| Flink 1.20.1 + Iceberg 1.11.0 | ✅ | ✅ | —（Iceberg 无 upsert） | `metadata-location` / `format-version=2` |
| Flink 2.3.0 + Paimon（2.2 连接器） | ✅ | ✅ | ✅ | `primaryKeys` 落库 |
| Flink 2.3.0 + Iceberg 1.12.0 | ✅ | ✅ | — | `metadata-location` / `format-version=2` |

**主键更新必须配 `sequence.field`。** Paimon 主键表按 sequence 判「谁更新」；
批量 `INSERT` 不指定时两条同键记录的 sequence 相同，先后是**未定义**的。
实测症状：同一份用例在 1.20 上碰巧读到新值、在 2.3 上读到旧值。
用例里把 `updated_at` 声明成 `'sequence.field'`，语义才变成「时间晚的胜出」，
两个版本都确定。线上写主键表同理——不指定 sequence 就等于把结果交给文件顺序。

**Iceberg 没有主键 / upsert。** 要改一行得走 `UPDATE` / `MERGE` 或重写数据文件，
属另一条链路；本模块只验「建表 → 写入 → 读回 → 再写一批」的主干。

## 5. 踩过的坑（都实测）

- **Flink 发行版不带 Hadoop。** Paimon 的 `RESTCatalog` 构造时调用
  `CatalogContext#hadoopConf()`，缺 Hadoop 直接抛
  `Hadoop configuration is not available for this CatalogContext`——报错完全没提 Hadoop。
  只加 `hadoop-common` 还不够：`HadoopUtils` 会显式加载 `HdfsConfiguration`，
  缺 `hadoop-hdfs-client` 时抛 `NoClassDefFoundError` 被吞成同一句；
  读数据时还要 `hadoop-mapreduce-client-core`（`FileInputFormat`）。
- **`flink-connector-base` / `flink-connector-files` 不是 `flink-streaming-java` 的传递依赖。**
  缺前者：`SingleThreadMultiplexSourceReaderBase` 找不到；缺后者：
  `BulkFormat$RecordIterator` 找不到。两者都表现为「写成功、一读就崩」，
  而且崩在 mini cluster 的任务线程里。
- **JDK 17 要一组 `add-opens`。** Flink 的 Kryo 序列化器反射 `Arrays$ArrayList`，
  模块系统拒绝时抛 `InaccessibleObjectException`（从 `com.twitter.chill` 里冒出来），
  与 SQL 写错完全无关。Iceberg 的 `INSERT` 会走到 Kryo，Paimon 那条正好不经过。
  已写进两个模块的 surefire `argLine`。
- **`DROP TABLE IF EXISTS` 兜不住「库不存在」。** Iceberg 的 Flink catalog 会先解析表，
  库不存在直接抛 `NoSuchNamespaceException`。用例的顺序因此是
  「先 `CREATE DATABASE IF NOT EXISTS` → 删表 → 删库 → 建库」。
- **Flink 2.3 没有 Paimon 连接器。** Maven Central 上只到 `paimon-flink-2.2`。
  2.2 的连接器实测在 2.3.0 上可用（Paimon 只用 Table/Catalog 工厂的稳定面），
  但这是**实测结论不是保证**：Paimon 发布 2.3 构件后应换回官方版本。
- **1.20 钉 1.20.1 而不是 1.20.5。** Paimon 2.1.0 的连接器仍是按 1.20.1 编译的
  （pom 里 `flink-streaming-java` / `flink-table-common` 都是 1.20.1、provided）。
  1.20.x 内部兼容，但既然连接器就是用它编的，测试也钉同一个值，省掉一类排查。
- **Iceberg 版本要按 Flink 版本选。** `iceberg-flink-runtime-2.3` 从 1.12.0 才存在，
  1.20 侧则跟服务端对齐用 1.11.0。

## 6. 与 Spark 侧的差异

| | Spark（`lakehouse-rest-spark`） | Flink（本模块） |
| --- | --- | --- |
| 会话 | 一个 JVM 只能一个 `SparkSession`，且 `spark.sql.extensions` 建会话时定死 | `TableEnvironment` 可并存，每个自带 mini cluster |
| 配置方式 | `spark.sql.catalog.<name>.*` 逐项配 | `CREATE CATALOG ... WITH (...)` 一次配 |
| catalog 注册 | 必须先用管理 API 建 ICEBERG（同） | 必须先建（同） |
| 主键 / upsert | Paimon 有，Iceberg 无 | 同（Paimon 需 `sequence.field` 才确定） |
| 管理语句扩展 | 本项目的 SQL 扩展（16 条语句） | **没有**——Flink 侧只做数据面，管理走 REST / 控制台 |

Flink 侧不做管理语句扩展是有意的：Flink 的 SQL 解析入口（`SqlParser`）与 Spark 的
`ParserInterface` 不是一回事，复制一套只会多一处需要跟着服务端词表改的地方；
需要管理动作时用控制台或管理 API。
