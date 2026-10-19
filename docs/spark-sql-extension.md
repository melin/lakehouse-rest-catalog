# Spark SQL 管理语法扩展

`lakehouse-rest-spark` 子模块把管理 API 的能力接进 Spark SQL：主体、principal role 与资源授权
可以用 SQL 语句管理，而不必手写 HTTP 请求。**catalog role 不在其中**——它的建档、装配与清点
共 7 条语法都已移除，只能用管理 API。

```sql
CREATE PRINCIPAL svc_etl PROPERTIES ('owner' = 'data-platform');
CREATE PRINCIPAL ROLE etl_engineer;
GRANT PRINCIPAL ROLE etl_engineer TO PRINCIPAL svc_etl;
GRANT LIST, DESCRIBE, SELECT ON CATALOG_ALL paimon TO USER svc_etl;
SHOW PERMISSIONS IN CATALOG paimon FOR USER svc_etl;
```

---

## 1. 启用

前置：`lakehouse-rest-spark` 的 jar 在 Spark 的 classpath 上，管理服务可达。

```bash
spark-sql \
  --conf spark.sql.extensions=io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions \
  --conf spark.paimon.rest.management.url=http://catalog-host:8080/api/management/v1 \
  --conf spark.paimon.rest.token=<执行这些语句的主体的令牌>
```

编程方式等价：

```scala
val spark = PaimonRestManagement.install(SparkSession.builder())
  .config(PaimonRestManagement.MANAGEMENT_URL, "http://catalog-host:8080/api/management/v1")
  .config(PaimonRestManagement.TOKEN, token)
  .getOrCreate()
```

> **旧包名仍然可用。** 包名已从 `io.github.melin.paimonrest` 改到 `io.github.melin.lakehouse.rest`
> （中间经过一次 `io.github.melin.lakehouse.rest`，那版未发布），
> 但模块里保留了一个旧包名下的空壳，所以既有配置写的
> `io.github.melin.paimonrest.spark.ManagementSparkExtensions` **不用改也能启动**。
> 新部署请写上面那个新包名——空壳只是过渡，确认没有部署再用旧名字之后就会删掉。
> 这条兼容是必须的：`spark.sql.extensions` 的值是**启动那一刻**就要解析的全限定类名，
> 写错了整个会话都建不起来，而报错信息离「升级了服务端依赖」很远。

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `spark.paimon.rest.management.url` | `http://127.0.0.1:8080/api/management/v1` | 管理 API 基址，须含 `/api/management/v1` 前缀 |
| `spark.paimon.rest.catalog.url` | 空（从管理 API 基址推导） | catalog API 基址，资源授权语句用它；空值时按「去掉 `/api/management/v1`」推导 |
| `spark.paimon.rest.token` | 空 | 作为 `Authorization: Bearer <token>` 发出；为空时不发送该头 |
| `spark.paimon.rest.management.timeoutSeconds` | `30` | 单次请求超时；非数字或非正数时退化为默认值 |

> **令牌应当填「执行这些语句的主体」的令牌，而不是管理员令牌。** 管理服务按调用者身份判定
> 权限，用管理员令牌会让所有 SQL 绕过权限判定，也就失去了用 SQL 管理权限的意义。

> **配置必须在第一条管理语句之前设好。** 客户端在会话的第一条管理语句到来时创建，之后整会话
> 复用；之后再改 URL 或令牌不会生效，需要重建会话。这样做的原因是 `HttpClient` 自带连接池与
> 选择器线程，每条语句新建一个并不划算。

---

## 2. 语句清单

共 16 条语句，分四类。`[]` 表示可选，`|` 表示二选一。

本节只做索引；每条语句的完整语法、参数、结果列、错误与注意事项见
[`spark-sql-reference.md`](spark-sql-reference.md)。

### DDL：主体

| 语句 | 说明 |
| --- | --- |
| `CREATE PRINCIPAL [IF NOT EXISTS] <name> [PROPERTIES (k = v, ...)]` | 返回 `principal`、`client_id`、`client_secret`；**明文密钥只在这一行结果里出现** |
| `DROP PRINCIPAL [IF EXISTS] <name>` | 无结果行 |
| `ALTER PRINCIPAL <name> SET PROPERTIES (k = v, ...)` | 增量设置 |
| `ALTER PRINCIPAL <name> UNSET PROPERTIES (k, ...)` | 增量移除 |
| `RESET PRINCIPAL <name>` | 重签凭据，返回 `client_id` 与新的 `client_secret` |
| `ROTATE PRINCIPAL <name>` | 与 `RESET` 同义（对应规格的 `/rotate` 与 `/reset` 两个端点） |

### DDL：角色

| 语句 | 说明 |
| --- | --- |
| `CREATE PRINCIPAL ROLE [IF NOT EXISTS] <name> [PROPERTIES (...)]` | |
| `DROP PRINCIPAL ROLE [IF EXISTS] <name>` | |
| `ALTER PRINCIPAL ROLE <name> SET PROPERTIES (...)` / `UNSET PROPERTIES (...)` | |

catalog role 的三条 DDL（`CREATE` / `DROP` / `ALTER CATALOG ROLE`）没有 SQL 语句，只能用管理 API。

### DCL：装配与授权

| 语句 | 说明 |
| --- | --- |
| `GRANT PRINCIPAL ROLE <role> TO PRINCIPAL <principal>` | |
| `REVOKE PRINCIPAL ROLE <role> FROM PRINCIPAL <principal>` | |
| `GRANT <access>[, <access>...] ON <resource> TO <grantee> [UNTIL '<有效期>']` | 一项 access 展开成一次授权请求 |
| `REVOKE <access>[, <access>...] ON <resource> FROM <grantee>` | 撤销幂等 |

资源授权走的是**官方权限模型**（与「权限管理」页同一条链路、同一个端点），
赋权身份是 `(资源, access, principal)` 三元组。授权对象四种写法折成 principal 不透明字符串：

```sql
USER alice            -- user:alice
ROLE sales_writer     -- role:sales_writer（判定层解析为主体持有的同名 catalog role）
GROUP eng             -- group:eng（没有成员关系可解析，不生效）
PRINCIPAL 'svc:etl'   -- 原样透传
```

`<resource>` 的八种写法：

```sql
CATALOG paimon
CATALOG_ALL paimon
DATABASE sales IN CATALOG paimon
DATABASE_ALL sales IN CATALOG paimon
TABLE default.orders IN CATALOG paimon
VIEW default.v1 IN CATALOG paimon
FUNCTION default.f_clean IN CATALOG paimon
COLUMN default.orders (id, amount) IN CATALOG paimon
```

`_ALL` 后缀表示「该资源及其全部后代，含此后新建的」，不带后缀的写法只覆盖资源自身。
对象级资源（`TABLE` / `VIEW` / `FUNCTION` / `COLUMN`）必须写成 `<库>.<对象名>`：
库是授权作用域的一部分，单段名字无法确定作用域，因此解析期直接报错，不做「猜默认库」的处理。

目标 catalog 不单独书写，而是从资源描述中取得——`ON TABLE ns.t IN CATALOG paimon`
已经写明 catalog 是 `paimon`，避免同一语句里重复两次。

### SHOW

| 语句 | 结果列 |
| --- | --- |
| `SHOW CATALOGS` | `name`、`type` |
| `SHOW PRINCIPALS [FOR PRINCIPAL ROLE <role>]` | `name`、`client_id`；带 `FOR` 时为 `principal` |
| `SHOW PRINCIPAL ROLES [FOR PRINCIPAL <principal>]` | `name`、`federated`；带 `FOR` 时为 `principal_role` |
| `SHOW PERMISSIONS IN CATALOG <catalog> [FOR <grantee>]` | `catalog`、`principal`、`type`、`resource`、`database`、`object_name`、`columns`、`access`、`expire_time` |

`SHOW PERMISSIONS` 的 `resource` 列把库与对象名拼成可读的完整名，同时保留 `database` 与
`object_name` 两列以便筛选：

| 作用范围 | `type` | `resource` | `database` | `object_name` |
| --- | --- | --- | --- | --- |
| catalog 级 | `CATALOG` / `CATALOG_ALL` | `paimon` | `null` | `null` |
| 库级 | `DATABASE` / `DATABASE_ALL` | `default` | `default` | `null` |
| 对象级 | `TABLE` / `VIEW` / `FUNCTION` / `COLUMN` | `default.orders` | `default` | `orders` |

### 写入语句不返回结果行

除 `CREATE PRINCIPAL`、`RESET` / `ROTATE PRINCIPAL` 与 `SHOW` 之外，其余语句返回 0 行，
与 Spark 原生 `CREATE TABLE` 这类命令一致，便于脚本编排：

```python
spark.sql("CREATE PRINCIPAL ROLE etl_reader").collect()   # []
spark.sql("SHOW PRINCIPALS").show()                        # 有结果
```

`CREATE PRINCIPAL` 返回的一次性明文密钥必须在这一行结果里取走：

```python
row = spark.sql("CREATE PRINCIPAL svc_etl").collect()[0]
print(row["client_id"], row["client_secret"])
# 服务端不保存明文；错过这一行只能靠 ROTATE PRINCIPAL 重新签发
```

---

## 3. 实现方式与两个必须知道的约束

### 3.1 为什么自带一份语法

Spark 3.5 的 `SqlBase.g4` 是**封闭**的：它的 `statement` 规则没有类似 `.*? #unsupported` 的
兜底分支，因此形如 `CREATE PRINCIPAL x` 的语句在词法 / 语法阶段就会失败，无法靠 visitor 拦下来；
官方扩展点 `ParserInterface` 也不提供「往已有语法里加规则」的能力。

因此本模块自带一份只描述管理语句的小语法
（`lakehouse-rest-spark/src/main/antlr4/io/github/melin/lakehouse/rest/spark/parser/ManagementSql.g4`），
在 `ParserInterface.parsePlan` 里**先试自己的解析器**：

- 解析成功 → 执行管理操作；
- 解析失败（`ParseCancellationException`）→ 原样交回 Spark 原生解析器。

判断依据是「整条语句能否匹配到 EOF」而不是字符串前缀猜测，因此不会误吞
`SHOW TABLES`、`SHOW DATABASES`、`CREATE TABLE ...` 这类原生语句，也不会因为残篇
（如 `CREATE PRINCIPAL`）而给出两套错误信息。

### 3.2 ANTLR 版本必须与目标 Spark 发行版一致

**这是本模块最容易踩、后果最重的一个约束。** ANTLR 的 ATN 序列化格式随版本变化，
代码生成版本与运行时版本不一致时，序列化版本号会对不上：

```
java.io.InvalidClassException: org.antlr.v4.runtime.atn.ATN;
  Could not deserialize ATN with version 3 (expected 4)
    at org.apache.spark.sql.catalyst.parser.SqlBaseLexer.<clinit>
```

Spark 3.5.9 的 `spark-sql-api` 依赖 `antlr4-runtime` **4.9.3**，其自带的
`SqlBaseLexer` / `SqlBaseParser` 就是用 4.9.3 生成的。如果本模块把 `antlr4-runtime` 抬到
4.13.x（ATN 序列化版本 4），运行时会**把 Spark 自己的 antlr4-runtime 顶掉**，于是受损的不是
本扩展，而是整个会话的 SQL 解析——连 `SELECT 1` 都会失败。

因此 `antlr.runtime.version` 与 `antlr4-maven-plugin` 的版本都钉在 4.9.3，
**升级 `spark.version` 时必须同步核对这个值**（见根 `pom.xml` 的注释）。

### 3.3 客户端的响应体形状与状态码取自规格

管理规格对「单个资源」的响应**大多不套外壳**，只有主体相关的接口例外：

| 端点 | 响应 |
| --- | --- |
| `POST /principals`、`POST /principals/{n}/reset\|rotate` | `{"principal":{…},"credentials":{…}}` |
| `GET` / `PUT /principals/{n}` | 裸 `Principal` |
| `POST` / `GET` / `PUT /principal-roles/{n}` | 裸 `PrincipalRole` |
| `POST` / `GET` / `PUT /catalogs/{c}/catalog-roles/{n}` | 裸 `CatalogRole` |
| `POST /v1/{prefix}/permissions/{grant,revoke}` | 成功返回 **201**，无响应体 |
| 列表类接口 | 具名数组：`{"catalogs":[…]} `、`{"principals":[…]} `、`{"roles":[…]} `、`{"grants":[…]} `；权限列表多一层分页信封：`{"assignments":[…],"nextPageToken":"…"}` |

状态码同样以规格为准，其中两条容易写错：

- `POST /principals/{n}/reset|rotate` 成功返回 **200**（更新凭据），不是 201；
- `POST /principals`、`POST /principal-roles`、`POST .../catalog-roles`、授予类 `PUT` 返回 **201**；
  `DELETE` 返回 **204**。

读错响应形状的后果不是报错而是**静默取到空值**：例如从 `GET /principals/{n}` 上多读一层
`principal`，拿到的 `entityVersion` 会变成 0，下一次 `PUT` 就会因为版本不符得到 409
（消息形如 `expected 0, actual 1`）。这个缺陷正是由第 4 节第 4 层的真实服务端验收发现的。

---

## 4. 测试分层

四层测试，各自负责不同的问题；前两层便宜，第四层最贵但最能发现跨实现的契约偏差。

| 层 | 测试类 | 覆盖 |
| --- | --- | --- |
| 1 解析 | `ManagementSqlParsingTests`（23 个用例） | 16 条语句的解析结果、八种资源定位器的字段拆分、多 access 展开、列范围的白 / 黑名单、`UNTIL` 的日期换算、反引号与注释、**非管理语句必须交回原生解析器** |
| 2 客户端 | `ManagementApiClientTests`（31 个用例） | 请求方法与路径、请求体字段形状、路径段编码、`ALTER` 的读-合并-回写、**单资源裸对象与状态码**、权限端点的基址推导与分页跟随、错误映射（403/404/409） |
| 3 桩端到端 | `ManagementSqlExecutionTests`（12 个用例） | 真实 `SparkSession` + 进程内桩：扩展是否真被加载、`spark.sql` 是否真执行命令、结果行列名、原生 SQL 不受影响 |
| 4 真实服务端 | `ManagementSqlLiveServerTests`（4 个用例） | 对真实 LakeHouse Catalog Server 跑完整 SQL 链路：管理 API 的契约（裸对象、`entityVersion`、状态码）、官方权限端点的 grant / list / revoke 与分页过滤。**catalog role 的建档与装配已没有 SQL 语句**，这一类用例里那半截脚手架改走 `ManagementApiClient`，顺带守住「管理 API 在 SQL 撤出后仍然可用」 |
| 4 真实服务端 | `PaimonTableDdlTests`（10 个用例） | 用 Spark SQL 经 LakeHouse Catalog 建表再写入（分区 / 主键 / `LIKE` / 幂等 / 冲突 / CTAS / `INSERT`），并直接读服务端元数据核对 schema、注释、分区键、主键与表选项——建表与写入路径的契约一致性，详见 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) |
| 4 真实服务端 | `NonPaimonTableSqlTests`（6 个用例） | Format / Object / Lance 三种非 Paimon 表的能力边界：Format 表（csv / parquet）建-写-读全链路、Object 表固定六列与「对象放进目录即可查到（无需 refresh）」、客户端给的 `path` 成为表位置因而能索引既有对象目录（Format 表与 Lance 表同样受益）、Lance 表只有建表可行；能力矩阵、物化行为与表位置规则见 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) §4.4 / §4.4.1 |
| 4 真实服务端 | `IcebergTableDdlTests`（10 个用例） | 同一条「SQL → 引擎 → REST → 元数据」的链路走**另一个协议面**：Iceberg REST catalog（`type=rest`，catalog 先用管理 API 建成 `ICEBERG`）。验 namespace 与 `CREATE DATABASE` 同义、分区 transform、`INSERT` 后的快照与 `summary`、更新代数改 schema、重命名、非空 namespace 不可删，并钉住 CTAS 的边界（staged create 缺 `transactions/commit`，被指名拒绝）；详见 [`spark-iceberg-rest-e2e.md`](spark-iceberg-rest-e2e.md) §0.1 |
| 4 真实服务端 | `SparkSqlAuthorizationTests`（4 个用例） | **两种格式各两条**。Paimon 面：同一个 catalog、同一张表跑两个身份，有写权限的能建库建表读写，只读的读得到数据但建库 / 建表 / 写数据一律被拒；Iceberg 面（`icebergCatalog.*`）：同一批主体、另一套 catalog role，语句换成 `CREATE NAMESPACE` / `USING iceberg`。两边的拒绝报文都断言点名了缺失的那项权限、被拒之后数据没变。Iceberg 侧钉住一条只从那一侧看得见的差异：写数据要 `UPDATE` + `ALTER`（`…/metrics` 判 `TABLE_WRITE_DATA`、提交端点判 `TABLE_WRITE_PROPERTIES`）。服务端自己那两层授权测试走 MockMvc 直接打 REST，这一层验的是**引擎发出的请求**——403 会不会被客户端改写、拒绝后到底有没有落数据，跑法见 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) §6.1 |
| 4 真实服务端 | `SparkSqlDocExamplesTests`（1 个用例） | 抽出参考文档第 10 节的示例逐条执行，断言撤销语义与清理结果——**让文档里的 SQL 与代码同生共死** |

第 1、2 层不建 `SparkSession`（解析只依赖客户端实例，不依赖会话），第 3 层起本地会话，
第 4 层默认跳过、需要显式指向服务端：

```bash
./scripts/e2e-spark-sql.sh      # 先校验参考文档，再自动起服务端并跑第 4 层
```

参考文档另有一层不需要服务端的静态校验（`scripts/verify-spark-sql-doc.py`）：
比对第 2 节总览与第 10 节示例块是否覆盖语法的全部备选、第 7.4 节的 12 项 access 与 8 种
资源类型的可用集合（对照 `PermissionDtos`）、第 7.3 节的资源定位器、实现声明的结果列与配置键、
第 2 节的端点路径、以及第 3.3 节的保留字清单。
它刻意不复用任何生成逻辑，因此能发现「文档写得很像但不成立」这类人工读稿看不出的问题；
只读本仓源码，没有第三方依赖。

---

## 5. 已知边界

1. **管理关键字是保留字。** `CATALOG`、`TABLE`、`VIEW`、`COLUMN`、`DATABASE`、`FUNCTION`、`ROLE`、
   `SHOW`、`SET`、`ON`、`IN`、`FOR`、`TO`、`IF`、`NOT`、`EXISTS`、`ALTER`、`CREATE`、`DROP`、
   `RESET`、`ROTATE`、`GRANT`、`REVOKE`、`PROPERTIES`、`UNSET`、`FROM`、`USER`、`GROUP`、
   `UNTIL`、`EXCEPT`、`PERMISSIONS` 等词在名称位置必须加反引号，例如 ``ON TABLE `catalog`.orders``。
   access 取值（`SELECT`、`LIST`、`DESCRIBE` 等）不是保留字，只有 `GRANT` / `ALTER` / `DROP`
   三项既是取值也是关键字。这与 Spark 原生 SQL 的保留字行为一致，但需要在使用时留意。
2. **多 access 授权不是原子的。** 每条 access 是一个独立请求。
   `GRANT a, b, c ON ...` 会顺序发出三次调用，中途失败时前面的已经生效；
   SQL 层不做伪原子化，失败直接抛出，由调用方按需补偿。
3. **`ALTER ... SET PROPERTIES` 是「读-合并-回写」。** 规格的 `PUT` 是整体替换 `properties`，
   而 SQL 的语义是增量，因此客户端先读当前值与 `entityVersion`、合并后再回写。
   并发修改下会有一次请求拿到 409，需要重试——这是乐观并发的正常表现，不是缺陷。
4. **每个会话一个 REST 客户端。** 见第 1 节的说明。
5. **access 取值的合法性由服务端判定。** SQL 层只做形状校验（例如对象级授权必须是
   `<库>.<对象名>`），不复制一份 12 项 access 词表——那只会多一处会漂移的副本。
   写错取值会得到服务端的 400，而不是在解析期被拦下。
6. **catalog role 在 SQL 侧没有任何语句。** 建档（`CREATE` / `DROP` / `ALTER CATALOG ROLE`）、
   装配（`GRANT` / `REVOKE CATALOG ROLE`）、清点（`SHOW CATALOG ROLES` / `SHOW GRANTS`）共 7 条
   语法已全部移除，只能用管理 API。旧权限名（`TABLE_READ_DATA` 一类）与旧的整句写法
   （`... TO CATALOG ROLE r`）同样不再支持：前者写在合法形状里会被服务端按「未知 access」
   拒掉，后者在语法阶段就失败。挂在那条链上的存量授权仍然生效，只是再没有界面能清点它。
7. **令牌映射是服务端的静态配置。** 用 SQL 新建的主体不能立即取到可用的调用令牌，
   详见 `docs/authorization.md` 的「已知边界」。
