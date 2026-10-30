# 访问控制：模型、判定与 catalog API 授权映射

本文说明本服务如何实现 Paimon 官方 REST Management API 的权限模型，以及这套模型
如何作用到 LakeHouse Catalog API（`/v1/**`）与管理面扩展端点（`/api/management/v1/**`）上。

- 规格基线：`spec/rest-management-open-api.yaml`（Paimon 官方 REST Management API）
- 权限语义出处：<https://paimon.apache.org/docs/master/concepts/rest/management-api>
- 历史对照：`docs/management-api-contract.md`（Polaris 规格快照，**不是当前契约**；规格文件已从仓库移除）

> **只有一套权限模型。** Polaris 的 64 项 privilege、catalog role、
> `lakehouse_resource_grant` 已全部移除。授权唯一的落库形态是
> `lakehouse_permission_assignment` 上的三元组 `(resource, access, principal)`，
> access 取官方那 12 项，另加本工程的两个凭据扩展词（`USE_CREDENTIAL` / `USE_SECRET`）。

---

## 1. 认证与授权是两件事

服务端把它们分成两层，分别由两组配置控制：

| 层 | 回答的问题 | 实现 | 配置前缀 |
| --- | --- | --- | --- |
| 认证 | 调用者是谁 | `BearerAuthInterceptor` 取 `Authorization: Bearer <token>`，交给 `TokenAuthenticationService` 认证成主体名，写入 `RequestContext` | `lakehouse.rest.auth.*` |
| 授权 | 这个主体能做什么 | catalog API 在 `AuthorizationInterceptor` 统一判定；权限端点与 lifecycle 端点各自在控制器内判定 | `lakehouse.rest.authorization.*` |

认证是**一条链**，令牌来自三个来源之一（顺序即尝试顺序）：

1. **静态令牌**（`lakehouse.rest.auth.tokens`）——服务端配置的长期凭据，给机器用。
   `lakehouse.rest.auth.token-principals` 给出「令牌 → 主体名」映射；
   **未登记的令牌退化为「令牌本身即主体名」**。
2. **控制台访问令牌**——本服务端签发（HS256）的 JWT，有两条路换来：
   主体用 `clientId` / `clientSecret` 走 OAuth 2.0 客户端凭据，
   或者用 `lakehouse.rest.auth.console.password.users` 里配置的**控制台账号 + 密码**。
   **两条路签发的令牌完全一样**，对授权链路没有区别。
3. **OIDC 令牌**——外部身份提供方签发的 JWT（RS256 / ES256），本服务端拉 JWKS 验签。

三种来源在 `TokenAuthenticationService` 合流，**下游只看主体名**：
授权判定与审计字段都不需要知道令牌是配置里的字符串、控制台签的 JWT，还是 IdP 签的 JWT。

> **控制台账号与主体的边界。** 配置里的控制台账号**不是主体**：它是「开门的钥匙」，
> 账号名默认直接当主体名用（可用 `console.password.principals` 映射成别的主体名）。
> 因此用 `admin/admin` 登录成功之后**什么也看不到是正常的**——除非 `admin`
> 被列进 `service-admins` 或被授予了某个 principal role。
> 服务端会为这种「账号在授权链路里查不到」的情况在启动日志里告警。

`lakehouse.rest.auth.enabled=false`（仅 h2 即用即丢 profile 与测试 profile）时，数据面
（`/v1/**`、`/api/catalog/v1/**`、`/api/management/v1/**`）的请求都以
`lakehouse.rest.auth.principal`（默认 `anonymous`）通过。

---

## 2. 授权模型

### 2.1 唯一的三元组

```
(resource, access, principal)  ──落库──>  lakehouse_permission_assignment
```

没有中间层。**principal role 是纯分组**：它自己不携带任何权限，
只把授予 `role:<name>` 的那条赋权转发给成员。这一点决定了判定只有一条路径——
「找到主体的候选 principal 串 → 取这些串上的赋权 → 看有没有一条覆盖目标资源」。

对比改造前的模型：

| | 改造前（Polaris） | 现在（官方） |
| --- | --- | --- |
| 授权表 | catalog role 授权 + 资源授权（3 张表） | `lakehouse_permission_assignment`（1 张） |
| 权限词表 | 64 项 privilege + 蕴含展开 | 14 项 access（官方 12 + 两个凭据词）+ 只认 `ALL` |
| 主体解析 | principal → principal role → catalog role | principal → 裸名 / `user:` / `role:` |
| 判定 | 展开后再比 | 相等，或授予的是 `ALL` |

### 2.2 14 项 access：官方的 12 项 + 两个凭据扩展词

`ALL / CREATEDATABASE / DESCRIBE / ALTER / DROP / CREATETABLE / CREATEFUNCTION /
CREATEVIEW / LIST / SELECT / UPDATE / GRANT`
——以上 12 项是官方词表，逐字一致。

`USE_CREDENTIAL / USE_SECRET` 是**本工程加的两个词**，官方没有：

| 词 | 能做什么 | 不能做什么 |
| --- | --- | --- |
| `USE_CREDENTIAL` | 调 `/token`、`/credentials` 取票；让 `loadTable` 的委派带回凭据 | 票里不能含目录里存的长期密钥对 |
| `USE_SECRET` | 票里**可以**含那对长期密钥 | **取不到票**——它不含 `USE_CREDENTIAL` |

为什么不能合成一个词：合成之后「能跑查询」和「能拿走长期密钥」就是同一件事，
而前者是每个分析师的日常、后者一次就够（拿到的那对密钥没有时限、也没有 policy 收窄到表，
等于该身份在这台存储上能做的一切）。官方在 Java 侧靠两个方法区分
（`getCredentials()` / `getSecrets()`），本工程的协议面各只有一个端点，区分只能落在
**这次要发什么**上。两个词是**并列**关系，判定层不做蕴含推断——见 §3.7。

创建类权限用**不带下划线**的持久化名（`CREATETABLE`，不是 `CREATE_TABLE`），与官方逐字一致。

**不做蕴含推断。** 官方只定义 `ALL` 一个聚合值（「All accesses applicable to the
resource」），没有给出 `ALTER` 含 `SELECT` 这种关系。判定因此是：

```java
granted.equals(required.name()) || Access.ALL.name().equals(granted)
```

自己发明蕴含关系会造出规格里并不存在的授权语义，而且失效方向是**放大**权限。

### 2.3 8 种 ResourceType 与作用域覆盖

官方把两个 `_ALL` 明确写成**后代作用域**（`CatalogAllResource`: covering database,
table, view, and function descendants；`DatabaseAllResource`: covering table, view,
and function descendants of one database），其余类型只覆盖自身：

| 赋权的作用域 | 覆盖什么 | 不覆盖什么 |
| --- | --- | --- |
| `CATALOG` | catalog 实体（改属性、删 catalog、建库） | 库、表、视图、函数 |
| `CATALOG_ALL` | catalog 下的库、表、视图、函数 | **catalog 实体本身** |
| `DATABASE` | 该库本身 + 该库的集合操作（列库内的表/视图/函数） | 库里的具体表 |
| `DATABASE_ALL` | 该库的后代 | **库实体本身** |
| `TABLE` / `VIEW` / `FUNCTION` | 同名对象 | 任何别的东西 |
| `COLUMN` | 列级目标 | 表级判定（见下） |

两个 `_ALL` 不含容器本身是官方描述的直接结果。代价要说清楚：
「改 catalog 属性」要 `CATALOG:ALTER`、「建库」要 `CATALOG:CREATEDATABASE`、
「改库属性」要 `DATABASE:ALTER`。引导角色因此**同时**持有 `CATALOG/ALL` 与
`CATALOG_ALL/ALL` 两条——少一条就会在某一类端点上 403。

**列级赋权不参与表级判定。** `COLUMN:SELECT` 只覆盖 `COLUMN` 目标；
否则「只授权了 a、b 两列」会被判成「整表可读」，这是权限提升。
`AuthorizationTests.columnGrantDoesNotAuthorizeTheWholeTable` 把这条钉住。

各类型允许授予的 access 来自官方对照表（`PermissionDtos.Access.allowedFor`），
服务端按它做入参校验：`CREATETABLE` 授到 `TABLE` 上会得到 400。
两个凭据扩展词只挂在能**定位到表**的三种类型上（`TABLE` 与两个 `_ALL` 后代作用域）；
`DATABASE` 刻意不给——它的覆盖规则不含库里的具体表，授上去会看起来成功、实际对表级判定
毫无作用，而一个能被授出却不生效的权限点比没有它更坏。

### 2.4 主体解析

`principal` 在官方语义里是**不透明字符串**，解析是服务端职责。本服务的约定是三条候选串：

1. 裸名 `alice`；
2. `user:alice`（与裸名等价，两种写法都能命中）；
3. `role:<principal role 名>`——主体通过成员关系拿到的分组。

`group:*` 没有成员关系可解析，明确不匹配（宁可不生效，不放大）。

### 2.5 自举与服务管理员

官方 12 项 access 里没有「管理主体、角色、catalog」这一类，它把这三者的生命周期归给
服务端实现。本服务用两部分表达：

- `lakehouse.rest.authorization.service-admins`（默认 `[root]`）：名单内的主体直接放行，
  用于 `/api/management/v1/**` 上主体、principal role、catalog 的生命周期操作；
- `lakehouse.rest.authorization.bootstrap-principal-role`（默认 `service_admin`）：
  启动时把 `role:<该名>` 授成 `CATALOG/ALL` + `CATALOG_ALL/ALL`（幂等）。

没有这条引导链，全新部署里没有任何主体能在 `catalog API` 上做成第一件事。

### 2.6 多级 namespace（Iceberg）：按「拼起来的路径」比

Iceberg 的 namespace 是**多级**的（`["accounting","tax"]`），Paimon 的 database 是单级字符串；
落库只有 `database_name` 一个字符串列，多级按 `NamespacePaths.STORAGE_SEPARATOR`（点号）拼。

**判定时不能逐段比较。** 逐段比会让「授 `accounting.tax`、访问 `["accounting","tax"]`」
永远不命中：两边段数不同，第一段就已经不等。这种失效是静默的——权限页显示已授权、
客户端一直 403，两端都不报错。因此 `covers` 把两边都拼成一个字符串再比：

```java
private boolean isPathPrefix(String prefix, String candidate) {
    if (prefix.isEmpty()) return true;                                   // 空前缀 = catalog 级
    return candidate.equals(prefix) || candidate.startsWith(prefix + "."); // 边界落在点号上
}
```

三条语义随之确定，`PermissionManagementTests.namespaceGrantMatchesMultiLevelNamespace` 逐条钉住：

1. `DATABASE_ALL` 授在 `sales` 覆盖 `sales.2026` 这条路径上的表；
2. 不反向覆盖：授 `accounting.tax` 不覆盖 `accounting`；
3. 边界落在点号上：授 `sales` 不覆盖 `salesforce`。

---

## 3. catalog API 的授权映射

catalog API 的「路径 → 所需 access」映射集中在 `CatalogAccessRules` 一张表里，
而不是分散到各控制器的注解上。这样做的理由是**未登记的路径会被拒绝，而不是被放行**：

- 拦截器解析出 `Requirement` 后调用判定；解析不出映射时直接 403，
  并在消息里说明该端点没有登记授权规则；
- 忘记给新端点登记，会在第一次调用时立刻暴露为 403，而不是静默放行；
- `CatalogEndpointAuthorizationTests` 从运行时的 `RequestMappingHandlerMapping` 枚举
  全部 `/v1/{prefix}/**` 端点，逐一核对是否已登记映射，新增端点未登记会让构建失败。

### 3.1 Paimon 形态（`databases/**`）

| 端点形态 | 资源类型 | access |
| --- | --- | --- |
| `GET /v1/config`、`POST /v1/oauth/tokens` | — | 豁免（凭据还没换到，必须可达） |
| `GET /databases` | `CATALOG_ALL` | `LIST` |
| `POST /databases` | `CATALOG` | `CREATEDATABASE` |
| `GET /databases/{db}` | `DATABASE` | `DESCRIBE` |
| `POST /databases/{db}` | `DATABASE` | `ALTER` |
| `DELETE /databases/{db}` | `DATABASE` | `DROP` |
| `GET .../tables`、`.../table-details` | `TABLE` | `LIST` |
| `POST .../tables`、`.../register` | `DATABASE` | `CREATETABLE` |
| `GET .../tables/{t}` | `TABLE` | `DESCRIBE` |
| `POST .../tables/{t}` | `TABLE` | `ALTER` |
| `DELETE .../tables/{t}` | `TABLE` | `DROP` |
| `.../tables/{t}/commit`、`/rollback` | `TABLE` | `UPDATE` |
| `.../tables/{t}/rollback-schema` | `TABLE` | `ALTER` |
| `.../tables/{t}/token` | `TABLE` | `USE_CREDENTIAL`（取票，见 §3.7） |
| `.../tables/{t}/auth` | `TABLE` | `SELECT`（只回行过滤与脱敏规则，不签发凭据） |
| `.../partitions/list*`、`/mark*` | `TABLE` | `SELECT` |
| `.../partitions/drop` 等写操作 | `TABLE` | `UPDATE` |
| `.../branches`、`.../tags`、`.../snapshot(s)`、`.../consumers` | `TABLE` | 读 `DESCRIBE` / 写 `ALTER` |
| `.../views/**` | `VIEW` | 列举 `LIST`、读 `DESCRIBE`、改 `ALTER`、删 `DROP`；建走 `DATABASE:CREATEVIEW` |
| `.../semantic-views/**` | `VIEW` | 同上（见 §3.3） |
| `.../functions/**` | `FUNCTION` | 列举 `LIST`、读 `DESCRIBE`、改 `ALTER`、删 `DROP`；建走 `DATABASE:CREATEFUNCTION` |
| `GET /tables`（跨库列举） | `CATALOG_ALL` | `LIST` |
| `POST /tables/rename` | `CATALOG_ALL` | `ALTER` |
| `GET /tables/id/{id}` | `CATALOG_ALL` | `DESCRIBE` |
| `GET /permissions` | `CATALOG_ALL` | `GRANT` |
| `GET /permissions/scopes` | `CATALOG_ALL` | `GRANT`（控制台清单视图，只读，见下） |
| `POST /permissions/grant`、`/revoke` | 请求体里的资源 | `GRANT`（deferred，见 §3.4） |

`/permissions/scopes` 与 `/permissions` **判权同口径**（`CATALOG_ALL` 上的 `GRANT`），
差别只在响应形状：前者是「主体 × 作用范围」折叠后的一行一份清单，带 `page` 元数据，
供控制台翻页；后者是规格定义的赋权元素，Spark 过程也在读。两条都只读，
权限不足时返回同一类 403，不会因为「换了条端点」放宽或收紧。

「同一个表集合路径，读与写落在不同资源类型」不是笔误：官方对照表里 `LIST` 只在
`CATALOG_ALL` / `DATABASE` / `TABLE` 上，`CREATEDATABASE` 只在 `CATALOG` 上，
`CREATETABLE` / `CREATEVIEW` / `CREATEFUNCTION` 只在 `DATABASE` 与 `CATALOG_ALL` 上。

### 3.2 Iceberg 形态（`namespaces/**`）

与 `databases/**` 是**同一件事的两个名词**，映射同构；差别是 namespace 为多级，
先按 `NamespacePaths.parse` 切成层级，再按 §2.6 的「拼起来比」。

| 端点形态 | 资源类型 | access |
| --- | --- | --- |
| `GET /namespaces` | `CATALOG_ALL` | `LIST` |
| `POST /namespaces` | `CATALOG` | `CREATEDATABASE` |
| `GET/HEAD /namespaces/{ns}` | `DATABASE` | `DESCRIBE` |
| `POST /namespaces/{ns}/properties` | `DATABASE` | `ALTER` |
| `DELETE /namespaces/{ns}` | `DATABASE` | `DROP` |
| `GET /namespaces/{ns}/tables` | `TABLE` | `LIST` |
| `POST /namespaces/{ns}/tables`、`.../register` | `DATABASE` | `CREATETABLE` |
| `GET/HEAD /namespaces/{ns}/tables/{t}` | `TABLE` | `DESCRIBE` |
| `POST .../tables/{t}` | `TABLE` | `ALTER` |
| `DELETE .../tables/{t}`、`.../unregister` | `TABLE` | `DROP` |
| `POST .../tables/{t}/metrics`、`/tasks` | `TABLE` | `UPDATE`（上报统计＝改这张表的数据状态） |
| `GET .../tables/{t}/credentials` | `TABLE` | `USE_CREDENTIAL`（与 Paimon 的 `/token` 同一件事） |
| `POST .../tables/{t}/plan` | `TABLE` | `SELECT`（只回扫描计划，不带密钥） |
| `GET .../tables/{t}/sign` | `TABLE` | `DESCRIBE` |
| `.../namespaces/{ns}/views/**` | `VIEW` | 同 Paimon 视图 |

`POST /namespaces` 的父级在请求体里，路径只有一段，作用域只能定在 `CATALOG`；
`POST /namespaces/{ns}`（更新 namespace 本身）Iceberg 没有这个端点，因此不登记——
落到「未登记即拒绝」并说明原因，不是漏登记。

`functions` 与 `register-view` 是一期不做的端点，同样不登记。

### 3.3 语义视图按 `VIEW` 授权

`SEMANTIC_MODEL_*` 六项权限已随 Polaris 词表整体移除，官方 12 项里没有 semantic-model
这一层。语义视图的读写路径与普通视图同构，因此映射到 `VIEW` 资源：
授权口径与视图完全一致。**功能本身保留**（`/v1/{prefix}/databases/{db}/semantic-views`）。

### 3.4 deferred 端点：目标资源在请求体里

`POST .../permissions/grant` 与 `/revoke` 的路径上只有「这是授权操作」这一层含义，
被授权的资源在请求体里，映射表算不出作用域。这两个端点被标成 `deferred`，
拦截器放行，由 `PermissionController` 拿着解析后的 `PermissionResource` 调
`AuthorizationService.requireGrantOn`：

- 在 `CATALOG_ALL` 上持 `GRANT` 的人覆盖其下一切；
- 在单表上持 `GRANT` 的人只能管那张表的赋权。

比「一律要求 catalog 级 `GRANT`」更精确，也没有放宽：作用域判定与其它 access 完全同一条路径。

### 3.5 管理面扩展端点的读权限

`GET /api/management/v1/catalogs/{name}` 判 `CATALOG_ALL:DESCRIBE`，而不是
`CATALOG:DESCRIBE`——官方对照表里 `CATALOG` 只有 `ALL / ALTER / DROP / GRANT /
CREATEDATABASE`，**没有任何读权限**，而「能读这个 catalog 里的东西」正是该端点的语义。
改/删 catalog 仍判 `CATALOG:ALTER|DROP`。

### 3.6 403 与 404 的优先级

判定在 catalog 不存在时**先行放行**，让后续流程返回 404。理由是：如果把「资源不存在」
也判成 403，调用者将无法区分「我没有权限」和「这个 catalog 根本不存在」。
catalog 存在但主体无权时仍返回 403。

### 3.7 凭据的两个权限点：端点判一个，载荷再判一个

凭据下发是唯一**同一条路径可能有两种要求**的地方，因此它的判定分两步：

| 步骤 | 判什么 | 在哪判 | 不通过时 |
| --- | --- | --- | --- |
| 1 | `USE_CREDENTIAL` | Paimon `/token`、Iceberg `/credentials`：拦截器按 §3.1 / §3.2 的映射表 | 403 |
| 1 | `USE_CREDENTIAL` | Iceberg `loadTable` 的委派：`IcebergCredentialService#mayVend`；Lance 的 `vend_credentials=true`：`LanceCatalogSupport#requireVending` | 委派：**不带凭据** + WARN；Lance：403 |
| 2 | `USE_SECRET`（仅当票里含长期密钥对） | 三个协议面的凭据服务，拿到凭据之后 | 同上（与步骤 1 同一条路径的姿势） |

**第二步为什么不能进映射表。** 判据是「这次到底发了什么」，而同一个 `/token` 发短期票
（STS 现签）还是发目录里存的 AK/SK，取决于 provider 的**签发结果**，不取决于路径或配置。
用配置反推（「配了 `s3-token` 就是短期」）会造出「开关说走 STS、其实退回了转发」这类错位，
而那正是设计稿 §5.8 否掉 mode 开关的理由。判据落在
`VendedStorageCredential#carriesLongTermKeyPair()`：**真的发了密钥**（`hasSecrets()`）
且**它没有时限**（`!shortLived()`）。只发定位元数据（Azure）、`sts-unavailable`、
交给引擎自己的环境凭据链，三种都不要求 `USE_SECRET`。

**两处失败姿势不同，是有意的。** `/token` 与 `/credentials` 存在的唯一目的就是签发凭据，
静默回一个空响应会让引擎拿着空配置去读数据，错误现场离原因很远；而 `loadTable` 同时承载
元数据读取，Iceberg 规格明确允许服务端对 `X-Iceberg-Access-Delegation` 一个机制都不选，
所以那里只能「不给」，不能 403——否则「看表结构」会跟着失败。

**Lance 面是第三个落点，且两道都在服务层。** 那一面的取票不是一个端点，而是
`describe` / `rename` 上的 `vend_credentials=true`——路径完全相同，`LanceAccessRules`
只拿得到路径与 `delimiter`，看不到 query 参数。因此两道判定都落在
`LanceCatalogSupport#requireVending`，姿势取「抛 403」：这个参数本身就是
客户端在说「我要凭据」，与 `/token` 同性质，静默返回空的直接后果是客户端拿着没有密钥的
`storage_options` 去开数据集。不带这个参数时不取票、不判权，`storage_options` 里只有定位信息。

**只有 `USE_SECRET` 不给票。** 两个词并列，判定层不做蕴含推断（§2.2）。让「授少了一条」
成为一个看起来正常、实则可用的状态，就等于把安全决定交给直觉。

> **升级注意（一次有意的行为变更）。** `.../tables/{t}/token` 与 `.../tables/{t}/credentials`
> 的判据从 `TABLE:SELECT` 换成了 `TABLE:USE_CREDENTIAL`，且长期密钥还要 `TABLE:USE_SECRET`。
> 持有 `ALL` 的主体（含引导角色 `service_admin`）不受影响；**只授了细粒度 `SELECT` 的部署
> 会在升级后于取票处收到 403**，需要补一条 `USE_CREDENTIAL`（若走转发档还要
> `USE_SECRET`），或者把 catalog 切到短期签发 provider 从而只需前者。这是这次拆分的
> 全部意义所在——旧行为下，「能读这张表」的每个分析师都握着一份没有时限、
> 不受 policy 约束的云凭据。

---

## 4. 配置项

| 配置 | 默认值 | 说明 |
| --- | --- | --- |
| `lakehouse.rest.auth.enabled` | `true` | 是否要求 `Authorization: Bearer <token>`；h2 / 测试 profile 显式为 `false` |
| `lakehouse.rest.auth.principal` | `anonymous` | 认证关闭或未带令牌时使用的主体名 |
| `lakehouse.rest.auth.tokens` | 空 | 允许的静态令牌列表 |
| `lakehouse.rest.auth.token-principals` | 空 | 静态令牌 → 主体名映射；未登记的令牌退化为「令牌即主体名」（审计列超长的记摘要，见 §6 第 7 条） |
| `lakehouse.rest.auth.access-token.ttl` | `1h` | 控制台签发的访问令牌有效期 |
| `lakehouse.rest.auth.access-token.issuer` | `paimon-rest` | 访问令牌的 `iss` |
| `lakehouse.rest.auth.access-token.signing-key` | 空 | HS256 签名密钥（Base64，≥32 字节）；留空则每次启动随机生成 |
| `lakehouse.rest.auth.access-token.signing-key-id` | 空 | 写进令牌头部当 `kid`；留空则头部不写，令牌形状与旧版一致 |
| `lakehouse.rest.auth.access-token.verify-only-keys` | 空 | 只验不签的旧钥匙 `kid → Base64`，只在轮换窗口里配 |
| `lakehouse.rest.auth.console.required` | `true` | 浏览器进控制台是否必须先登录；**只作用于界面，不是安全边界** |
| `lakehouse.rest.auth.console.password.enabled` | `true` | 是否允许用配置里的账号密码换令牌 |
| `lakehouse.rest.auth.console.password.users` | `admin: admin` | 控制台账号表（明文）；**生产必须改** |
| `lakehouse.rest.auth.console.password.principals` | 空（仓库自带 `admin: root`） | 账号名 → 主体名映射；不填则两者同名。自带配置映射到服务管理员 `root`，让本地开发能看到 catalog 列表；生产应删掉 |
| `lakehouse.rest.auth.console.client-credentials.enabled` | `true` | 是否允许用主体凭据换令牌 |
| `lakehouse.rest.auth.console.oidc.*` | 关闭 | 外部身份提供方登录；见 [`console-auth.md`](console-auth.md) |
| `lakehouse.rest.authorization.enabled` | `true` | 是否启用授权判定；h2 / 测试 profile 显式为 `false` |
| `lakehouse.rest.authorization.service-admins` | `[root]` | 服务管理员主体名单 |
| `lakehouse.rest.authorization.bootstrap-principal` | `root` | 启动时预置的引导主体；留空则不预置 |
| `lakehouse.rest.authorization.bootstrap-principal-role` | `service_admin` | 引导角色名；启动时把 `role:<该名>` 授成 `CATALOG/ALL` + `CATALOG_ALL/ALL` |

`authorization.enabled=false` 时不做判定，认证仍然生效——即所有请求以读取到的
主体名通过，便于在既有部署上先只做认证。**两个开关要么都开、要么都关：**
`auth.enabled=false` 时「认不出的令牌退化为令牌即主体名」，`Bearer root` 就是管理员，
只开授权没有安全意义；只开认证不开授权则所有请求以真实主体名全量放行（审计仍归属正确）。

---

## 5. 走通一次授权

服务端以 `root` 为服务管理员启动（`authorization.enabled=true`）。

```bash
M=http://127.0.0.1:8080/api/management/v1
AUTH='Authorization: Bearer root'
J='Content-Type: application/json'

# 1) 建主体（返回的一次性 clientSecret 只出现这一次）
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"principal":{"name":"etl","properties":{"team":"data"}}}' "$M/principals"

# 2) 分个组（可选）。principal role 只是分组，自己不带权限
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"principalRole":{"name":"etl_reader"}}' "$M/principal-roles"
curl -s -X PUT -H "$AUTH" -H "$J" \
  -d '{"principalRole":{"name":"etl_reader"}}' "$M/principals/etl/principal-roles"

# 3) 授权：给 role:etl_reader 授「default 库下全部后代可读」，外加取票权
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"resource":{"type":"DATABASE_ALL","database":"default"},
       "access":"SELECT","principal":"role:etl_reader"}' \
  "http://127.0.0.1:8080/v1/paimon/permissions/grant"
# 取票单独一个词（§3.7）：仓库自带的 default catalog 是本地文件系统、没有密钥可发，
# 因此只需要 USE_CREDENTIAL；对象存储的转发档还要 USE_SECRET，否则 /token 会 403
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"resource":{"type":"DATABASE_ALL","database":"default"},
       "access":"USE_CREDENTIAL","principal":"role:etl_reader"}' \
  "http://127.0.0.1:8080/v1/paimon/permissions/grant"

# 4) 核对
curl -s -H "$AUTH" "http://127.0.0.1:8080/v1/paimon/permissions?principal=role:etl_reader"

# 5) 用该主体访问 catalog API（取数据令牌要 TABLE:USE_CREDENTIAL，
#    票里含长期密钥时还要 TABLE:USE_SECRET）
TOKEN=$(curl -s -X POST http://127.0.0.1:8080/api/catalog/v1/oauth/tokens \
  -d grant_type=client_credentials \
  -d client_id="$CLIENT_ID" -d client_secret="$CLIENT_SECRET" | jq -r .access_token)
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8080/v1/paimon/databases/default/tables/orders/token"
# -> 200

# 6) 但建表不在 DATABASE_ALL 的词表里，要单独授 DATABASE:CREATETABLE
curl -s -X POST -H "$AUTH" -H "$J" \
  -d '{"resource":{"type":"DATABASE","database":"default"},
       "access":"CREATETABLE","principal":"role:etl_reader"}' \
  "http://127.0.0.1:8080/v1/paimon/permissions/grant"
```

同一件事在 Spark SQL 里也有对应语句
（`GRANT SELECT ON DATABASE_ALL default TO ROLE etl_reader`，取票再补一条
`GRANT USE_CREDENTIAL ON DATABASE_ALL default TO ROLE etl_reader`，
见 [`spark-sql-reference.md`](spark-sql-reference.md) 第 7 节）——
页面、curl、SQL 打的是同一批端点，没有翻译层。

### 5.1 从引擎侧验一遍：两个身份跑真 SQL

上面的 curl 验的是**服务端自己的判定**。同一件事在引擎侧还有一层要验：请求是 Paimon 的
`SparkCatalog` 发出来的，403 会不会被客户端改写成别的错、被拒绝的写入有没有落数据，
只有真 SQL 才知道。`SparkSqlAuthorizationTests` 做的就是这件事——同一个 catalog、
同一张表，令牌 `alice` 建库建表加读写，令牌 `bob` 只能读，且断言拒绝报文点名了
**缺失的那一项 access**（`CREATETABLE` / `UPDATE` / `CREATEDATABASE`）、被拒之后数据没变。

跑法与所需的两个令牌见 [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md) §6.1。
两个身份共用一份 SparkContext、只换 `spark.sql.catalog.<catalog>.token`；
只读身份也必须有读元数据的能力——`SparkCatalog` 懒加载时会去读默认命名空间 `default`
的属性（对应 `DESCRIBE`），这不是「读数据」的权限。

**Iceberg 面同样是这组用例，只是换了 catalog**（`SparkSqlAuthorizationTests` 里
`icebergCatalog.*` 三个方法）。有一处**只能从这一侧看出来的差异**：

| 动作 | Paimon 的闸门 | Iceberg 的闸门 |
| --- | --- | --- |
| 写数据 | `POST …/tables/{t}/commit` → `UPDATE` | ① `POST …/tables/{t}/metrics` → `UPDATE`<br>② `POST …/tables/{t}` → `ALTER` |
| 改 schema / 属性 | 各自的端点 | 同一个 `POST …/tables/{t}` → `ALTER` |

也就是说**在 Iceberg 面「能写数据」= `UPDATE` + `ALTER`**，比 Paimon 多一项。
原因是 Iceberg 没有独立的提交端点：数据提交与属性变更都是
`POST /namespaces/{ns}/tables/{t}` 上的 `updates` 数组，判定发生在解析请求体之前。
用例对这一点留了容错（拒绝报文点名两者之一即通过），两个都是真闸门。

---

## 6. 已知边界

按优先级排列，均为当前实现有意留出的边界：

1. **静态令牌的映射是静态配置。** `token-principals` 决定「静态令牌 → 主体名」，
   改映射要重启。但**新建主体不受此限**：创建时会一次性返回 `clientId` 与明文
   `clientSecret`，用它们换一个访问令牌即可立即调用管理 API。
2. **访问令牌无法即时撤销。** 服务端不保存会话，令牌在 `access-token.ttl` 内一直有效；
   要立刻失效只能轮换 `access-token.signing-key`（会作废所有人的令牌）。
   轮换本身可以不停服：配 `signing-key-id` 后令牌头部带 `kid`，服务端能「用一把签、
   用两把验」，按 [`../README.md`](../README.md) §9.1 的三步走，滚动重启期间零 401。
3. **两个 `_ALL` 不含容器本身。** 这是官方描述的直译。想「一条授权管全部」做不到，
   因为 `CATALOG_ALL` / `DATABASE_ALL` 的词表里没有 `CREATEDATABASE` /
   `CREATETABLE` / `DESCRIBE` 这些容器级取值。要建库、建表、读 catalog 详情，
   必须额外授 `CATALOG:*` / `DATABASE:*`。
4. **没有 access 蕴含关系。** 只有 `ALL` 是聚合值。因此 `ALTER` 不给 `SELECT`、
   `DESCRIBE` 不给 `SELECT`——这是官方词表的形状，不是漏写。
   两个凭据扩展词同样并列：`USE_SECRET` 不给 `USE_CREDENTIAL`（§2.2、§3.7）。
5. **行过滤与列脱敏策略未实现。** `auth` 端点返回空的过滤表达式与脱敏映射，只做列存在性校验。
6. **授权不校验被授权对象是否存在。** 可以对尚未创建的表授予 `SELECT`。
7. **审计列里超过 255 字符的主体名记的是摘要。** 判定用的是原始主体名（长度不限），
   落库到 `owner` / `created_by` / `updated_by` 时才归一化：放得下的原样存，
   放不下的记 `sha256:<前 12 位>`。起因是「认不出的令牌会退化为令牌即主体名」，
   而控制台访问令牌长 272 个字符。
8. **层级里含点号的 namespace 与两级 namespace 无法区分。** 多级 namespace 落库时按点号拼接
   （`["a","b"]` → `a.b`），因此一个 level 字面就叫 `a.b` 的 namespace 与两级的 `a`/`b`
   在库里是同一行。详见 [`iceberg-rest-catalog-design.md` §8](iceberg-rest-catalog-design.md)。
9. **权限页的 namespace 候选是「按层递归列举」的结果，有上限。** Iceberg 没有「列举全部
   namespace」的端点，只能从根开始逐层 `?parent=` 递归，深度上限 4 层、条数上限 500 条。
   超出的 namespace 不会出现在下拉框里，但手工填写路径仍可授权——候选列表是便利性，
   不是权限边界。
10. **Iceberg 面分不开「写数据」与「改属性」。** 两者共用
    `POST /v1/{prefix}/namespaces/{ns}/tables/{t}`，判定只能要求 `ALTER`，
    因此「能写数据」必须同时给 `UPDATE`（`…/metrics`）与 `ALTER`。
11. **列级赋权不参与表级判定，也没有列级端点。** 服务端存了列范围
    （`columns_json`），但列级判定只对 `COLUMN` 目标生效；而 Paimon / Iceberg 的
    协议面都没有「按列读」的端点，所以这条能力目前只用于表达「授了哪几列」。
