# Spark SQL 管理语法参考

`lakehouse-rest-spark` 把 LakeHouse Catalog 的管理 API 接进 Spark SQL：主体（principal）、
principal role 与资源授权可以用 16 条 SQL 语句管理，不必手写 HTTP 请求。
catalog role 没有 SQL 入口——它只剩管理 API 一条路（控制台也没有它的页面）。

本文是**语句级参考**：每条语句的语法、参数、等价 REST 调用、结果集、错误与注意事项。
设计动机、语法扩展的实现方式、测试分层见 [`spark-sql-extension.md`](spark-sql-extension.md)；
管理 API 的端点与字段定义见 [`management-api-contract.md`](management-api-contract.md)。

```sql
CREATE PRINCIPAL IF NOT EXISTS svc_etl PROPERTIES ('owner' = 'data-platform');
CREATE PRINCIPAL ROLE etl_engineer;
GRANT PRINCIPAL ROLE etl_engineer TO PRINCIPAL svc_etl;
GRANT SELECT, DESCRIBE ON TABLE default.orders IN CATALOG paimon TO USER svc_etl;
SHOW PERMISSIONS IN CATALOG paimon FOR USER svc_etl;
```

---

## 1. 启用与配置

前置两件事：`lakehouse-rest-spark` 的 jar 在 Spark 的 classpath 上；管理服务可达。

### 1.1 配置方式

```bash
spark-sql \
  --conf spark.sql.extensions=io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions \
  --conf spark.paimon.rest.management.url=http://catalog-host:8080/api/management/v1 \
  --conf spark.paimon.rest.token=<执行这些语句的主体的令牌>
```

### 1.2 编程方式

```scala
val spark = PaimonRestManagement.install(SparkSession.builder())
  .config(PaimonRestManagement.MANAGEMENT_URL, "http://catalog-host:8080/api/management/v1")
  .config(PaimonRestManagement.TOKEN, token)
  .getOrCreate()
```

`install` 必须在 `getOrCreate()` 之前调用。

### 1.3 配置项

| 配置键 | 默认值 | 说明 |
| --- | --- | --- |
| `spark.paimon.rest.management.url` | `http://127.0.0.1:8080/api/management/v1` | 管理 API 基址，须含 `/api/management/v1` 前缀。末尾多余的 `/` 会被去掉 |
| `spark.paimon.rest.catalog.url` | 空（从管理 API 基址推导） | catalog API 基址，资源授权语句（第 7 节）用它。空值时按「去掉 `/api/management/v1`」推导；推导不出来（基址不以该前缀结尾）会报错并点名这个键 |
| `spark.paimon.rest.token` | 空 | 作为 `Authorization: Bearer <token>` 发出；为空时**不发送**该请求头 |
| `spark.paimon.rest.management.timeoutSeconds` | `30` | 单次请求超时秒数；非数字或非正数时退化为默认值，不会让会话启动失败 |

配置读取顺序是「会话 SQL 配置 → `SparkConf` → 默认值」，因此 `spark.conf.set(...)`、
`.config(...)` 与命令行 `--conf` 三种写法都有效。

> **令牌应当填「执行这些语句的主体」的令牌，而不是管理员令牌。**
> 管理服务按调用者身份判定权限，用管理员令牌会让所有 SQL 绕过权限判定，
> 也就失去了用 SQL 管理权限的意义。

> **配置必须在第一条管理语句之前设好。** 客户端在会话的第一条管理语句到来时创建、
> 之后整会话复用；之后再改 URL 或令牌不会生效，需要重建会话。

---

## 2. 语句总览

共 16 条语句（第 15 行是 3 种 `SHOW` 形态的合并写法）。`[]` 表示可选，`|` 表示二选一。
**每条语句对应一次或多次 REST 调用**，下表列出全部映射。

| # | 语句 | REST 调用 |
| --- | --- | --- |
| 1 | `CREATE PRINCIPAL [IF NOT EXISTS] n [PROPERTIES (...)]` | `POST /principals` |
| 2 | `DROP PRINCIPAL [IF EXISTS] n` | `DELETE /principals/{n}` |
| 3 | `ALTER PRINCIPAL n SET PROPERTIES (...)` | `GET` + `PUT /principals/{n}` |
| 4 | `ALTER PRINCIPAL n UNSET PROPERTIES (...)` | `GET` + `PUT /principals/{n}` |
| 5 | `RESET PRINCIPAL n` | `POST /principals/{n}/reset` |
| 6 | `ROTATE PRINCIPAL n` | `POST /principals/{n}/rotate` |
| 7 | `CREATE PRINCIPAL ROLE [IF NOT EXISTS] n [PROPERTIES (...)]` | `POST /principal-roles` |
| 8 | `DROP PRINCIPAL ROLE [IF EXISTS] n` | `DELETE /principal-roles/{n}` |
| 9 | `ALTER PRINCIPAL ROLE n SET PROPERTIES (...)` | `GET` + `PUT /principal-roles/{n}` |
| 10 | `ALTER PRINCIPAL ROLE n UNSET PROPERTIES (...)` | `GET` + `PUT /principal-roles/{n}` |
| 11 | `GRANT PRINCIPAL ROLE r TO PRINCIPAL p` | `PUT /principals/{p}/principal-roles` |
| 12 | `REVOKE PRINCIPAL ROLE r FROM PRINCIPAL p` | `DELETE /principals/{p}/principal-roles/{r}` |
| 13 | `GRANT <access>[,...] ON <资源> TO <授权对象> [UNTIL '<有效期>']` | `POST /v1/{prefix}/permissions/grant`，每项 access 一次 |
| 14 | `REVOKE <access>[,...] ON <资源> FROM <授权对象>` | `POST /v1/{prefix}/permissions/revoke`，每项 access 一次 |
| 15 | `SHOW ...`（`CATALOGS` / `PRINCIPALS` / `PRINCIPAL ROLES`，3 种形态） | 见第 8 节 |
| 16 | `SHOW PERMISSIONS IN CATALOG c [FOR <授权对象>]` | `GET /v1/{prefix}/permissions`，带 `FOR` 时加 `?principal=` |

两点容易看错的地方：

- **资源授权打在 catalog API 而不是管理 API 上。** 第 13、14 行的端点是
  `/v1/{prefix}/permissions/{grant,revoke}`——官方把权限端点归在管理 API 里，但路径挂在
  catalog API 之下。因此这两条语句的基址由 `spark.paimon.rest.management.url` **推导**
  （去掉 `/api/management/v1`），需要指向别处时用 `spark.paimon.rest.catalog.url` 显式覆盖；
  推不出来（基址不以 `/api/management/v1` 结尾）会在发出请求前报错并点名这个配置项。
- **`ALTER` 是两次调用。** 规格的 `PUT` 整体替换 `properties`，而 SQL 的 `ALTER` 是增量语义，
  客户端先 `GET` 当前值与 `entityVersion`、合并后再 `PUT` 回写。

### 结果集形态

| 语句 | 结果 |
| --- | --- |
| `CREATE PRINCIPAL`、`RESET PRINCIPAL`、`ROTATE PRINCIPAL` | 1 行，3 列（含一次性明文密钥） |
| 4 种 `SHOW` 语句 | 0 行或多行，列定义见第 8 节 |
| 其余 11 条 | **0 行 0 列**，与 Spark 原生 `CREATE TABLE` 这类命令一致 |

---

## 3. 词法与标识符

### 3.1 大小写

关键字大小写不敏感（`create principal`、`Create Principal`、`CREATE PRINCIPAL` 等价）。
标识符（主体名、角色名、catalog 名）**保留大小写**，不做折叠。

### 3.2 标识符

| 形态 | 规则 | 示例 |
| --- | --- | --- |
| 裸标识符 | 首字符为字母或下划线，其后为字母、数字或下划线；不含 `-`、`.`、空格 | `svc_etl`、`reader_v2` |
| 反引号标识符 | 任意字符；反引号本身写成两个连续反引号 | `order-reader`、含点的对象名 |

反引号写法示例（表格里写不清楚，单独列出）：

```sql
CREATE PRINCIPAL `order-reader`;   -- 裸标识符不允许 -，用反引号包起来
GRANT SELECT ON TABLE `my.db`.`orders` IN CATALOG paimon TO ROLE reader;  -- 库名可以带点
CREATE PRINCIPAL `a``b`;           -- 名称是 a`b：反引号写成两个反引号
```

### 3.3 关键字是保留字

管理关键字不能作为裸标识符使用。`ALTER`、`CATALOG`、`CATALOG_ALL`、`CATALOGS`、`COLUMN`、
`CREATE`、`DATABASE`、`DATABASE_ALL`、`DROP`、`EXCEPT`、`EXISTS`、`FOR`、`FROM`、`FUNCTION`、
`GRANT`、`GROUP`、`IF`、`IN`、`NOT`、`ON`、`PERMISSIONS`、`PRINCIPAL`、`PRINCIPALS`、
`PROPERTIES`、`RESET`、`REVOKE`、`ROLE`、`ROLES`、`ROTATE`、`SET`、`SHOW`、`TABLE`、`TO`、
`UNSET`、`UNTIL`、`USER`、`VIEW`
这 37 个词出现在名称位置时必须加反引号：

```sql
CREATE PRINCIPAL `table`;                                       -- 裸写 TABLE 会解析失败
GRANT SELECT ON TABLE `default`.`user` IN CATALOG paimon TO ROLE reader;
```

**access 取值不是关键字。** `ALL`、`SELECT`、`UPDATE`、`LIST`、`DESCRIBE`、`CREATETABLE`
这些词在语法里按普通标识符处理，因此可以作为主体名、角色名、库名裸写。
`GRANT`、`ALTER`、`DROP` 三项例外——它们既是 access 取值也是本语法的关键字，
`GRANT ALTER ON DATABASE ...` 能解析，但 `CREATE PRINCIPAL alter` 不行。

`NAMESPACE`、`POLICY`、`SEMANTIC`、`MODEL` 曾经是保留字，随着资源授权改用官方
八种资源定位器（第 7.3 节）已经不再保留，现在可以当作普通名称使用。

### 3.4 字面量与注释

| 元素 | 写法 |
| --- | --- |
| 字符串 | 单引号或双引号；内部同类引号写成两个：`'it''s'`、`"say ""hi"""` |
| 数字 | 可选负号 + 数字，可带小数：`2`、`-1`、`0.5` |
| 行注释 | `-- 到行尾` |
| 块注释 | `/* ... */` |
| 语句结尾 | 分号可选，允许重复（`;;`） |

一次调用只解析**一条**语句。把多条语句用 `;` 串在一个字符串里会失败——
这与 Spark 原生行为一致。

---

## 4. DDL：主体

主体是调用管理 API 的身份载体，创建时返回一次性的 `client_id` 与明文 `client_secret`。

### 4.1 `CREATE PRINCIPAL`

```sql
CREATE PRINCIPAL [IF NOT EXISTS] <name> [PROPERTIES (<key> = <value>, ...)]
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `<name>` | 是 | 主体名；已存在时返回 `409` |
| `IF NOT EXISTS` | 否 | 已存在时静默跳过（吞掉 `409`），不返回结果行 |
| `PROPERTIES` | 否 | 自定义属性键值对；键须为字符串字面量或标识符 |

**结果列**：`principal`、`client_id`、`client_secret`。属性不出现在结果里。

```sql
CREATE PRINCIPAL svc_etl PROPERTIES ('owner' = 'data-platform', 'tier' = silver);
```

```text
+-----------+---------------------+---------------------+
|principal  |client_id            |client_secret        |
+-----------+---------------------+---------------------+
|svc_etl    |3fcb2c8e-...         |rY8sK...             |
+-----------+---------------------+---------------------+
```

> **明文密钥只出现在这一行结果里。** 服务端不保存明文，错过这一行只能靠
> `ROTATE PRINCIPAL` 重新签发。

属性值可以只写键不写值，此时等价于设置成空串：

```sql
CREATE PRINCIPAL svc_etl PROPERTIES (owner);   -- owner = ""
```

### 4.2 `DROP PRINCIPAL`

```sql
DROP PRINCIPAL [IF EXISTS] <name>
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `<name>` | 是 | 主体名；不存在时返回 `404` |
| `IF EXISTS` | 否 | 不存在时静默跳过（吞掉 `404`） |

无结果行。删除主体不会自动撤销它持有的角色装配，需要先
`REVOKE PRINCIPAL ROLE ... FROM PRINCIPAL ...`。

### 4.3 `ALTER PRINCIPAL`

```sql
ALTER PRINCIPAL <name> SET   PROPERTIES (<key> = <value>, ...)
ALTER PRINCIPAL <name> UNSET PROPERTIES (<key>, ...)
```

| 参数 | 必填 | 说明 |
| --- | --- | --- |
| `SET PROPERTIES` | 二选一 | 增量设置：列出的键被写入或覆盖，未列出的键保持不变 |
| `UNSET PROPERTIES` | 二选一 | 增量移除：列出的键被删除，未列出的键保持不变 |

无结果行。`UNSET` 的列表中只写键名，不写 `= 值`。

```sql
ALTER PRINCIPAL svc_etl SET PROPERTIES ('tier' = 'gold');
ALTER PRINCIPAL svc_etl SET PROPERTIES ('owner' = 'risk');   -- tier 仍在
ALTER PRINCIPAL svc_etl UNSET PROPERTIES (tier);             -- owner 仍在
```

一次语句只能 `SET` 或 `UNSET`，不能同时写两者。

### 4.4 `RESET` / `ROTATE PRINCIPAL`

```sql
RESET  PRINCIPAL <name>
ROTATE PRINCIPAL <name>
```

| 语句 | 端点 | 结果列 |
| --- | --- | --- |
| `RESET PRINCIPAL` | `POST /principals/{name}/reset` | `principal`、`client_id`、`client_secret` |
| `ROTATE PRINCIPAL` | `POST /principals/{name}/rotate` | 同上 |

两条语句重新签发凭据并返回新的明文密钥，结果形态与 `CREATE PRINCIPAL` 相同。
规格未定义二者在语义上的差异（[待确认]），本实现把它们当作同义操作，
只对应不同的端点。

```sql
ROTATE PRINCIPAL svc_etl;
```

规格允许 `reset` 接受调用方指定的 `clientId` / `clientSecret`（是否接受由服务端配置决定），
**SQL 层不暴露这个能力**，始终请求服务端生成随机凭据。

---

## 5. DDL：principal role

principal role 是跨 catalog 的权限集合，通过「主体 → principal role → catalog role」两级装配
挂到具体资源上。**本章只覆盖第一级**：catalog role 本身与第二级装配都没有 SQL 语句，
只能用管理 API（`/catalogs/{c}/catalog-roles/**`）。

### 5.1 `CREATE PRINCIPAL ROLE`

```sql
CREATE PRINCIPAL ROLE [IF NOT EXISTS] <name> [PROPERTIES (<key> = <value>, ...)]
```

无结果行。`PROPERTIES` 的位置在名称之后。

```sql
CREATE PRINCIPAL ROLE etl_engineer PROPERTIES ('purpose' = 'etl');
```

### 5.2 `DROP PRINCIPAL ROLE`

```sql
DROP PRINCIPAL ROLE [IF EXISTS] <name>
```

无结果行。

### 5.3 `ALTER PRINCIPAL ROLE`

```sql
ALTER PRINCIPAL ROLE <name> SET   PROPERTIES (<key> = <value>, ...)
ALTER PRINCIPAL ROLE <name> UNSET PROPERTIES (<key>, ...)
```

无结果行。增量语义与 `ALTER PRINCIPAL` 相同。

---

## 6. DCL：角色装配

装配是两级多对多关系。**SQL 只覆盖第一级**「主体 ↔ principal role」这一条边；
第二级「principal role ↔ catalog role」没有语句，只能用管理 API。

### 6.1 主体 ↔ principal role

```sql
GRANT  PRINCIPAL ROLE <role> TO   PRINCIPAL <principal>
REVOKE PRINCIPAL ROLE <role> FROM PRINCIPAL <principal>
```

无结果行。重复授予同一条边是幂等的（服务端接受）。

```sql
GRANT PRINCIPAL ROLE etl_engineer TO PRINCIPAL svc_etl;
REVOKE PRINCIPAL ROLE etl_engineer FROM PRINCIPAL svc_etl;
```

---

## 7. DCL：资源授权

资源授权按**官方权限模型**表达：赋权身份是 `(资源, access, principal)` 三元组，
授权直接面向 principal 不透明字符串，与「权限管理」页同一套语义、同一个端点。

### 7.1 语法

```sql
GRANT  <access>[, <access>...] ON <资源> TO   <授权对象> [UNTIL '<有效期>']
REVOKE <access>[, <access>...] ON <资源> FROM <授权对象>
```

无结果行。目标 catalog **不单独书写**，而是从资源描述中取得——
`ON TABLE ns.t IN CATALOG paimon` 已经写明 catalog 是 `paimon`，避免同一语句里重复两次。

### 7.2 授权对象（4 种写法）

| 写法 | 折成的 principal | 说明 |
| --- | --- | --- |
| `USER <name>` | `user:<name>` | 认证主体 |
| `ROLE <name>` | `role:<name>` | 判定层把它解析为「该主体在这个 catalog 下持有的**同名 catalog role**」 |
| `GROUP <name>` | `group:<name>` | 没有成员关系可解析，**不生效**（见下） |
| `PRINCIPAL '<字面量>'` | 原样透传 | 给不在上面三种约定里的主体留口子 |

> **判定层能匹配到的 principal 只有三种**：裸名、`user:<名>`、以及
> `role:<该主体在这个 catalog 下持有的 catalog role 名>`。因此 `GROUP ...` 与自定义的
> `PRINCIPAL '...'` 只会被**记录下来而不生效**——它们需要服务端有对应的组成员解析规则，
> 本工程没有。要给一组主体授权，正解是建一个 catalog role、把授权写给 `ROLE <该角色>`，
> 再把这组主体的 principal role 装配到该 catalog role 上。
> **这条链的建档与装配都没有 SQL 语句**（见第 6 节、第 11 节第 6 条），只能用管理 API。

`ROLE <name>` 是四种写法里唯一**依赖服务端状态**的一种：它要生效，前提是该主体在这个 catalog
下真的持有名为 `<name>` 的 catalog role。角色不存在时授权照样写得进去、清单里也看得见
（`principal` 列是 `role:<name>`），但判定时匹配不上任何人——因此**纯 SQL 场景优先用
`USER <name>`**，`ROLE` 留给「角色确实由管理 API 建好了」的场合。

### 7.3 八种资源写法

| 资源写法 | ResourceType | 作用范围 |
| --- | --- | --- |
| `CATALOG <catalog>` | `CATALOG` | 该 catalog **自身** |
| `CATALOG_ALL <catalog>` | `CATALOG_ALL` | 该 catalog 及其全部后代（含此后新建的库与表） |
| `DATABASE <库> IN CATALOG <catalog>` | `DATABASE` | 该库自身 |
| `DATABASE_ALL <库> IN CATALOG <catalog>` | `DATABASE_ALL` | 该库及其全部后代 |
| `TABLE <库>.<对象名> IN CATALOG <catalog>` | `TABLE` | 一张表 |
| `VIEW <库>.<对象名> IN CATALOG <catalog>` | `VIEW` | 一个视图 |
| `FUNCTION <库>.<对象名> IN CATALOG <catalog>` | `FUNCTION` | 一个函数 |
| `COLUMN <库>.<对象名> (<列>, ...) IN CATALOG <catalog>` | `COLUMN` | 一张表的若干列（列范围见 7.5） |

```sql
GRANT CREATEDATABASE                 ON CATALOG paimon       TO ROLE reader;
GRANT LIST, DESCRIBE, SELECT         ON CATALOG_ALL paimon   TO ROLE reader;
GRANT CREATETABLE                    ON DATABASE sales       IN CATALOG paimon TO ROLE reader;
GRANT SELECT                         ON TABLE raw.events.orders IN CATALOG paimon TO ROLE reader;
GRANT SELECT                         ON VIEW default.v_orders   IN CATALOG paimon TO ROLE reader;
GRANT SELECT                         ON FUNCTION default.f_clean IN CATALOG paimon TO ROLE reader;
GRANT SELECT                         ON COLUMN sales.orders (id, amount) IN CATALOG paimon TO ROLE reader;
```

库名可以是多级（`raw.events`）。对象级资源（`TABLE` / `VIEW` / `FUNCTION` / `COLUMN`）
**必须写成 `<库>.<对象名>`，至少两段**：库是授权作用域的一部分，单段名字无法确定作用域，
解析期直接报错，不做「猜默认库」的处理。

```text
ManagementSqlException: table grants require a qualified name of the form
<database>.<name>, got 'orders'
```

### 7.4 access 词表（12 项）

一项 access 在不同资源类型上的可用性不同，**`ON` 后面写哪种资源就按哪个类型校验**：

| 资源类型 | 允许授予的 access |
| --- | --- |
| `CATALOG` | `ALL`、`ALTER`、`DROP`、`GRANT`、`CREATEDATABASE` |
| `CATALOG_ALL` | `ALL`、`DESCRIBE`、`ALTER`、`DROP`、`GRANT`、`CREATETABLE`、`CREATEVIEW`、`CREATEFUNCTION`、`LIST`、`SELECT`、`UPDATE` |
| `DATABASE` | `ALL`、`DESCRIBE`、`ALTER`、`DROP`、`GRANT`、`CREATETABLE`、`CREATEVIEW`、`CREATEFUNCTION`、`LIST` |
| `DATABASE_ALL` | `ALL`、`SELECT`、`UPDATE`、`ALTER`、`DROP`、`GRANT` |
| `TABLE` | `ALL`、`SELECT`、`UPDATE`、`ALTER`、`DROP`、`GRANT`、`DESCRIBE` |
| `VIEW` | `ALL`、`SELECT`、`ALTER`、`DROP`、`GRANT`、`DESCRIBE` |
| `FUNCTION` | `ALL`、`SELECT`、`ALTER`、`DROP`、`GRANT`、`DESCRIBE` |
| `COLUMN` | `SELECT` |

`ALL` 是该资源类型允许的全部 access 的并集。
`CREATETABLE` / `CREATEVIEW` / `CREATEFUNCTION` / `CREATEDATABASE` 就是官方词表里的
**不带下划线**的写法，不是 `CREATE_TABLE`。

**两个最容易踩的地方：**

- **`CREATEDATABASE` 只在 `CATALOG` 上可用，而 `LIST` / `SELECT` / `UPDATE` /
  `CREATETABLE` / `CREATEVIEW` / `CREATEFUNCTION` 只在 `CATALOG_ALL` 上可用。**
  想让一个角色「能建库、又能读整个 catalog 下的表」，必须写两条语句：
  一条 `ON CATALOG`、一条 `ON CATALOG_ALL`。
- **旧的 Polaris 权限名不再被接受。** `TABLE_READ_DATA`、`NAMESPACE_LIST` 这类取值属于
  另一套模型（授权挂在 catalog role 上，走管理 API），SQL 侧已经换掉；把它们写在合法形状里
  会得到服务端 400（`Unknown access: TABLE_READ_DATA`），而旧的整句写法
  `... TO CATALOG ROLE r` 更是在语法阶段就失败（`TO` 后面只认第 7.2 节的四种授权对象）。

access 的合法性由服务端判定，SQL 层只认形状、不复制一份词表——写错取值得到的是服务端的
400，而不是解析期的报错。

### 7.5 列范围

```sql
GRANT SELECT ON COLUMN sales.orders (id, amount) IN CATALOG paimon TO ROLE analyst;
GRANT SELECT ON COLUMN sales.orders EXCEPT (card_no) IN CATALOG paimon TO ROLE analyst;
```

`(列...)` 是白名单，`EXCEPT (列...)` 是黑名单，两者互斥：一条语句只能给一种。
列名只做去重，服务端**不校验列是否真的在表上**。

**列范围不属于赋权身份。** 身份是 `(资源, access, principal)` 三元组，于是：

- 对同一个身份再授一次列范围是**整体替换**，不是并存——先授的白名单会被后来的黑名单顶掉，
  清单里始终只有一条；
- `REVOKE` 时列范围**不参与匹配**。语法要求 `COLUMN` 定位器必须带列范围，但服务端按身份
  删整条，写哪几列都会被忽略。

### 7.6 有效期

```sql
GRANT SELECT ON TABLE sales.orders IN CATALOG paimon TO ROLE analyst UNTIL '2026-11-30';
```

| 写法 | 含义 |
| --- | --- |
| `UNTIL '2026-11-30'` | 该日**整日有效**：换算成当天 23:59:59.999（本地时区） |
| `UNTIL '2026-11-30T15:59:59.999Z'` | 完整的 ISO-8601 instant，原样使用 |
| 不写 `UNTIL` | 永不过期 |

换算在**解析期**完成，格式不合法的值在发出任何请求之前就失败：

```text
ManagementSqlException: expire time must be a date (2026-11-30) or an ISO-8601 instant
(2026-11-30T15:59:59.999Z), got '2026/11/30'
```

用毫秒而不是纳秒：权限管理页写的就是毫秒，同一条授权经页面授与经 SQL 授，
在清单里应当逐字相同。

`REVOKE` 不接受 `UNTIL`——撤销没有期限可言。过期的授权仍留在清单里
（`SHOW PERMISSIONS` 看得到、`expire_time` 有值），只是不再参与判定。

### 7.7 一次授予多项

access 之间用逗号分隔，会**展开成多次请求**（每项 access 一个 `POST .../grant`）：

```sql
GRANT LIST, DESCRIBE, SELECT ON CATALOG_ALL paimon TO ROLE etl_reader;
```

上例发出 3 次请求。规格没有批量授权端点，因此这件事**不是原子的**：中途失败时
前面的已经生效，SQL 层不做伪原子化，失败直接抛出，由调用方按需补偿。

撤销同理——`REVOKE LIST, SELECT ON CATALOG_ALL paimon FROM ROLE etl_reader;` 发两次
`POST .../revoke`。撤销是**幂等**的：撤一个不存在的授权也返回成功，不会 404。

---

## 8. SHOW

4 种 `SHOW` 形态，其中 3 种带 `FOR` 变体，结果列随之变化。

前 3 种（`SHOW CATALOGS` / `SHOW PRINCIPALS` / `SHOW PRINCIPAL ROLES`）读的是**管理 API**，
第 4 种 `SHOW PERMISSIONS` 读的是**官方权限模型**的直授——与「权限管理」页同一份数据。

### 8.1 `SHOW CATALOGS`

```sql
SHOW CATALOGS
```

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `name` | string，非空 | catalog 名 |
| `type` | string | catalog 类型，如 `INTERNAL` |

### 8.2 `SHOW PRINCIPALS`

```sql
SHOW PRINCIPALS
SHOW PRINCIPALS FOR PRINCIPAL ROLE <principalRole>
```

| 形态 | 列 | 说明 |
| --- | --- | --- |
| 无 `FOR` | `name`、`client_id` | 列出全部主体 |
| `FOR PRINCIPAL ROLE` | `principal` | 列出持有该 principal role 的主体 |

### 8.3 `SHOW PRINCIPAL ROLES`

```sql
SHOW PRINCIPAL ROLES
SHOW PRINCIPAL ROLES FOR PRINCIPAL <principal>
```

| 形态 | 列 | 说明 |
| --- | --- | --- |
| 无 `FOR` | `name`、`federated` | 列出全部 principal role；`federated` 为 `true` / `false` / `null` |
| `FOR PRINCIPAL` | `principal_role` | 列出该主体持有的 principal role |

### 8.4 `SHOW PERMISSIONS`

```sql
SHOW PERMISSIONS IN CATALOG <catalog>
SHOW PERMISSIONS IN CATALOG <catalog> FOR <授权对象>
```

读到的是第 7 节那套直授——与「权限管理」页同一份数据。

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `catalog` | string，非空 | catalog 名（回显语句里的 catalog） |
| `principal` | string，非空 | 授权对象折成的 principal 字符串，如 `role:sales_writer` |
| `type` | string，非空 | 资源类型，8 种：`CATALOG` / `CATALOG_ALL` / `DATABASE` / `DATABASE_ALL` / `TABLE` / `COLUMN` / `VIEW` / `FUNCTION` |
| `resource` | string | 拼好的资源名：catalog 级写 catalog 名，库级写库名，对象级写 `库.对象名` |
| `database` | string | 库名；`CATALOG` / `CATALOG_ALL` 为 `null` |
| `object_name` | string | 对象名；`CATALOG` / `CATALOG_ALL` / `DATABASE` / `DATABASE_ALL` 为 `null` |
| `columns` | string | 只有列级授权有值：白名单写 `amount, id`，黑名单写 `EXCEPT card_no` |
| `access` | string，非空 | access 取值，见第 7.4 节 |
| `expire_time` | string | ISO-8601 instant；没写 `UNTIL` 时为 `null` |

```text
+--------+------------------+------------+-------------+----------+-------------+------------+---------------+-------------------------+
|catalog |principal         |type        |resource     |database  |object_name  |columns     |access         |expire_time              |
+--------+------------------+------------+-------------+----------+-------------+------------+---------------+-------------------------+
|paimon  |role:sales_writer |CATALOG     |paimon       |null      |null         |null        |CREATEDATABASE |null                     |
|paimon  |role:sales_writer |CATALOG_ALL |paimon       |null      |null         |null        |SELECT         |null                     |
|paimon  |role:sales_writer |TABLE       |sales.orders |sales     |orders       |null        |UPDATE         |2026-11-30T15:59:59.999Z |
|paimon  |role:analyst      |COLUMN      |sales.orders |sales     |orders       |amount, id  |SELECT         |null                     |
+--------+------------------+------------+-------------+----------+-------------+------------+---------------+-------------------------+
```

两点值得留意：

- **`FOR` 的过滤发生在服务端**（查询串 `?principal=`），客户端只把授权对象折成同一个
  principal 串带过去。`FOR ROLE sales_writer` 得到的是 principal 为 `role:sales_writer`
  的授权——直授本来就以 role principal 为归属，不是「该 catalog role 名下的一切」。
- **`SHOW` 会跟随分页取完全部。** 服务端默认每页 100、上限 1000，客户端一路跟
  `nextPageToken` 直到取完；若服务端始终返回新 token（异常行为），在第 100 页抛异常，
  而不是死循环。

---

## 9. 错误处理

### 9.1 两类失败

| 阶段 | 异常 | 触发条件 |
| --- | --- | --- |
| 解析 | `ManagementSqlException` | 语句是管理语句，但形状非法（如对象级授权缺库名、`UNTIL` 不是合法日期）。**不发起任何请求** |
| 解析 | `org.apache.spark.sql.catalyst.parser.ParseException` | 语句以管理关键字开头但语法不完整（如 `CREATE PRINCIPAL`）。由 Spark 原生解析器给出信息 |
| 执行 | `ManagementApiException` | 服务端返回非预期状态码（含 `400` 取值非法），或网络不可达 |

### 9.2 `ManagementApiException` 的判定方法

| 方法 | 含义 |
| --- | --- |
| `status()` | HTTP 状态码；网络层失败（连不上、超时）时为 `0` |
| `method()` / `path()` | 失败请求的方法与路径 |
| `notFound()` | `status == 404` |
| `alreadyExists()` | `status == 409` |
| `forbidden()` | `status == 403` 或 `401` |
| `resourceType()` / `resourceName()` | 服务端 `ErrorResponse` 的 `type` / `name`，可能为 `null` |
| `describe()` | 拼好的可读描述：状态码 + 方法 + 路径 + 服务端消息 |
| `getMessage()` | 服务端 `ErrorResponse` 的 `message`；无法解析时是原始响应文本 |

### 9.3 `IF EXISTS` / `IF NOT EXISTS` 只吞一种错误

`IF EXISTS` 只吞 `404`，`IF NOT EXISTS` 只吞 `409`。**`403` 不会被吞掉**：

```python
# 无权限时即使写了 IF NOT EXISTS 也会抛异常
spark.sql("CREATE PRINCIPAL IF NOT EXISTS svc_etl").collect()
# ManagementApiException: 403 ... svc_etl is not allowed to create principals
```

这是刻意的：把权限不足当成「已存在」会让权限配置错误被静默掩盖。

### 9.4 常见状态码

| 状态码 | 典型原因 |
| --- | --- |
| `400` | 请求形状对但取值不合法：access 与资源类型不匹配、`expireTime` 不是 ISO-8601 |
| `403` / `401` | 调用者令牌对应的主体没有管理该类资源的权限 |
| `404` | 目标主体 / 角色 / catalog 不存在；`DROP` 时未加 `IF EXISTS` |
| `409` | 名称已存在；或 `ALTER` 的 `entityVersion` 不匹配（并发修改） |
| `0` | 管理服务不可达：URL 写错、服务未启动、网络不通 |

---

## 10. 完整示例

下面这段按顺序执行即可跑通全部 16 条语句，可直接粘贴进 `spark-sql`。
前置：catalog `paimon` 存在（服务端默认配置会预置）。

<!-- doc-example:begin -->
```sql
-- ① 主体：创建、改属性、查、重置、轮换、删除
DROP PRINCIPAL IF EXISTS doc_demo_etl;
CREATE PRINCIPAL doc_demo_etl PROPERTIES ('owner' = 'data-platform', 'tier' = 'silver');
ALTER PRINCIPAL doc_demo_etl SET PROPERTIES ('tier' = 'gold');
ALTER PRINCIPAL doc_demo_etl UNSET PROPERTIES ('tier');
SHOW PRINCIPALS;
RESET PRINCIPAL doc_demo_etl;
ROTATE PRINCIPAL doc_demo_etl;

-- ② 角色：principal role
CREATE PRINCIPAL ROLE IF NOT EXISTS doc_demo_engineer PROPERTIES ('purpose' = 'docs');
ALTER PRINCIPAL ROLE doc_demo_engineer SET PROPERTIES ('tier' = 'gold');
ALTER PRINCIPAL ROLE doc_demo_engineer UNSET PROPERTIES (tier);
SHOW PRINCIPAL ROLES;

-- ③ 装配：主体 → principal role
GRANT PRINCIPAL ROLE doc_demo_engineer TO PRINCIPAL doc_demo_etl;
SHOW PRINCIPAL ROLES FOR PRINCIPAL doc_demo_etl;
SHOW PRINCIPALS FOR PRINCIPAL ROLE doc_demo_engineer;

-- ④ 资源授权：四种作用范围 + 多 access 展开 + 列范围 + 有效期 + 撤销
GRANT CREATEDATABASE ON CATALOG paimon TO USER doc_demo_etl;
GRANT LIST, DESCRIBE, SELECT ON CATALOG_ALL paimon TO USER doc_demo_etl;
GRANT UPDATE ON TABLE default.orders IN CATALOG paimon TO USER doc_demo_etl;
GRANT SELECT ON COLUMN default.orders (id, amount) IN CATALOG paimon TO USER doc_demo_etl UNTIL '2030-01-01';
SHOW PERMISSIONS IN CATALOG paimon FOR USER doc_demo_etl;
REVOKE DESCRIBE ON CATALOG_ALL paimon FROM USER doc_demo_etl;
SHOW PERMISSIONS IN CATALOG paimon FOR USER doc_demo_etl;

-- ⑤ 清理：先撤授权与装配，再删角色与主体
REVOKE CREATEDATABASE ON CATALOG paimon FROM USER doc_demo_etl;
REVOKE LIST, SELECT ON CATALOG_ALL paimon FROM USER doc_demo_etl;
REVOKE UPDATE ON TABLE default.orders IN CATALOG paimon FROM USER doc_demo_etl;
REVOKE SELECT ON COLUMN default.orders (id, amount) IN CATALOG paimon FROM USER doc_demo_etl;
SHOW PERMISSIONS IN CATALOG paimon FOR USER doc_demo_etl;
REVOKE PRINCIPAL ROLE doc_demo_engineer FROM PRINCIPAL doc_demo_etl;
DROP PRINCIPAL ROLE doc_demo_engineer;
DROP PRINCIPAL doc_demo_etl;
SHOW CATALOGS;
```
<!-- doc-example:end -->

按 step 取用：

```sql
-- 用 SQL 授予只读权限：看得见 + 读得到，覆盖整个 catalog
CREATE PRINCIPAL IF NOT EXISTS svc_analyst;
GRANT LIST, DESCRIBE, SELECT ON CATALOG_ALL paimon TO USER svc_analyst;

-- 审计：这个主体名下现在有什么
SHOW PERMISSIONS IN CATALOG paimon FOR USER svc_analyst;

-- 收权：撤掉写数据，读权限不受影响
REVOKE UPDATE ON TABLE sales.orders IN CATALOG paimon FROM USER svc_analyst;
```

Python 取值：

```python
row = spark.sql("CREATE PRINCIPAL svc_etl").collect()[0]
print(row["principal"], row["client_id"], row["client_secret"])

# 写类语句返回空列表，与 Spark 原生 DDL 一致
assert spark.sql("CREATE PRINCIPAL ROLE data_engineer").collect() == []

spark.sql("SHOW PERMISSIONS IN CATALOG paimon FOR USER svc_analyst").show(truncate=False)
```

---

## 11. 已知边界

1. **管理关键字在名称位置必须加反引号。** 见第 3.3 节的完整保留字清单。
   这与 Spark 原生 SQL 的保留字行为一致，但需要在使用时留意。
2. **`ALTER ... SET PROPERTIES` 是「读-合并-回写」。** 规格的 `PUT` 整体替换属性，
   而 SQL 语义是增量，因此客户端先读当前值与 `entityVersion`、合并后再回写。
   并发修改下会有一次请求拿到 `409`，需要重试——这是乐观并发的正常表现，不是缺陷。
3. **多 access 授权不是原子的。** 见第 7.7 节。
4. **列范围不参与撤销匹配，重复授予整体替换。** 见第 7.5 节。
5. **`GROUP` 与自定义 `PRINCIPAL` 字面量的授权不会生效。** 见第 7.2 节——
   判定层只认裸名、`user:<名>`、`role:<catalog role 名>` 三种 principal。
6. **catalog role 在 SQL 侧没有任何语句。** 建档（`CREATE` / `DROP` / `ALTER CATALOG ROLE`）、
   装配（`GRANT` / `REVOKE CATALOG ROLE`）、清点（`SHOW CATALOG ROLES` / `SHOW GRANTS`）
   共 7 条语法已全部移除，只能用管理 API（`/catalogs/{c}/catalog-roles/**`）。
   旧的 Polaris 授权写法 `GRANT` / `REVOKE <权限> ON <资源> TO|FROM CATALOG ROLE r` 同样不再解析：
   `TO` 后面只认第 7.2 节的四种授权对象，语法失败后回落给 Spark 原生解析器，得到 `ParseException`。
   授权改用第 7 节的语句；挂在那条链上的**存量**授权仍然生效，只是再没有界面能清点它。
7. **`RESET` 不支持调用方指定凭据。** 见第 4.4 节；始终由服务端生成随机凭据。
8. **access 取值的合法性由服务端判定。** SQL 层只做形状校验（例如对象级授权必须是
   `<库>.<对象名>`），不复制一份 12 项 access 词表——那只会多一处会漂移的副本。
   写错取值会得到服务端的 400，而不是在解析期被拦下。
9. **每个会话一个 REST 客户端。** 配置必须在第一条管理语句之前设好，见第 1.3 节。
10. **一次调用一条语句。** 不支持在一个字符串里用 `;` 串联多条。
11. **令牌映射是服务端的静态配置。** 用 SQL 新建的主体不能立即取到可用的调用令牌，
    详见 [`authorization.md`](authorization.md) 的「已知边界」。
12. **`IF EXISTS` / `IF NOT EXISTS` 不吞 `403`。** 见第 9.3 节。
