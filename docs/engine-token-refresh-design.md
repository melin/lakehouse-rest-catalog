# 引擎侧主体令牌续期：Spark / Flink 的 Paimon 与 Iceberg catalog

**状态：P0-1…P2 全部落地。** 服务端 RFC 8693 令牌交换（`TokenExchangeTests` 21 条）、
引擎侧 `lakehouse-rest-engine-auth`（`RefreshingAuthProviderTests` 30 条）、治理通道的
`TokenSource`（`TokenSourceTests` 8 条 + `ManagementApiClientTests` 31 条）、Iceberg 只加配置、
控制台片段与校验、`max-session` 护栏与「过期 / 无效」归因（`TokenSessionLimitTests` 6 条）。
逐项状态见 §8，落地时改主意的六处见 §9。
下面的 §1 与 §2 保留**落地之前**的事实描述——它们是这份文档的论证基础，改掉就看不出「为什么必须这么改」；
每处都已标注现在的状态。

**要解决的问题：** 引擎注册 Paimon / Iceberg REST catalog 时填的是**主体令牌**（`Authorization: Bearer <JWT>`），
而它的有效期只有 `lakehouse.rest.auth.access-token.ttl`（默认 **1 小时**）。跑得比 1 小时久的作业，
在第 61 分钟的下一次元数据调用上开始 401——重启就好，所以一线反馈常常是「偶发失败」而不是「令牌过期」。

**结论先行：**

1. **服务端加一个 OAuth 2.0 令牌交换（RFC 8693）** `grant_type=urn:ietf:params:oauth:grant-type:token-exchange`。
   这是让 **Iceberg 原生刷新链**从「必然失败」变成「可用」的最小改动，且引擎配置里仍然只有短票、没有长期密钥。
2. **Paimon 侧加一个自定义 `AuthProvider`**（`token.provider=oauth2`）。Paimon 的 SPI 每请求都调
   `mergeAuthHeader`，但内置的 `bear` 只在构造期读一次票——这是「框架支持续期、provider 没利用」的典型。
3. **Spark 治理通道**（`spark.lakehouse.rest.token` → `ManagementApiClient`）要单独做：它和 catalog 无关，
   是另一条会静默失效的链路（血缘 fail-open，症状是「图后半段没有边」而不是报错）。
4. **Lance 面不做**（connector 0.8 没有续期钩子）；**数据面凭据不动**（`/token`、`storage-credentials`
   本来就能续，见 §2.5——把这两件事混为一谈是这份文档最想避免的误解）。

---

## 1. 事实基线

### 1.1 一个令牌是什么

`AccessTokenService.issue()` 签的是自包含 JWT（HS256），载荷只有五项：

```
iss=paimon-rest   sub=<主体名>   iat=<签发秒>   exp=<iat+ttl>   jti=<随机>   [scope]
```

三个性质直接决定本方案的空间：

- **无状态**：服务端不存令牌，因此**没有撤销**。`ttl` 是唯一的暴露窗口上限。
- **`sub` 是唯一身份**：授权判定按 `sub` 查授权链路，所以续期**不能换主体**（换了就是提权）。
- **TTL 是全局的**：`lakehouse.rest.auth.access-token.ttl` 一个值，没有「控制台 1h / 引擎 30d」这种分档。
  这是有意为之（`RestServerProperties.AccessToken` 的注释：`ttl` 就是暴露窗口上限），
  本方案**不引入第二档 TTL**——要长作业就续期，不要靠调大 TTL。

### 1.2 令牌可以来自三处，换票只有一处

`TokenAuthenticationService` 的认证链依次尝试：**静态令牌**（`lakehouse.rest.auth.tokens`，无过期）
→ **自签 JWT**（控制台 / 引擎用主体凭据换的票）→ **OIDC**（外部 IdP）。

换票入口只有一个，`OAuthTokenController`：`POST /v1/oauth/tokens` 与 `POST /api/catalog/v1/oauth/tokens`
（同一处理方法的两个名字，后者是 Polaris 对齐路径）。它**只认 `client_credentials`**
（P0-1 之后认两个 grant：这个加上 RFC 8693 的 `token-exchange`，见 §4.1），
凭据是主体的 `clientId` / `clientSecret`，`scope` **只认 `PRINCIPAL_ROLE:ALL`**（本工程按主体全部角色并集判权，
没有「挑一个角色」这一层，因此其余 scope 一律 `invalid_scope` 拒绝而不是忽略）。

响应是 RFC 6749 第 5.1 节的形状：`access_token` / `token_type=bearer` / `expires_in` / `scope`。
**`expires_in` 是真的**（等于 `ttlSeconds()`），这一点后面很关键：Iceberg 靠它（或 JWT 的 `exp`）决定何时刷新。

### 1.3 「服务端自动续」为什么不存在

JWT 无状态 + 服务端不知道客户端是否还在用 → 只有两条路能「自动续」：

- **滑动过期**：每次请求都重签并下发新票。代价是令牌寿命变成无上限（等于没有 `exp`），
  且每个中间层（缓存、日志、网关）都会看到不断变化的 `Authorization`。**否决**。
- **服务端定时给所有人续**：需要一张「谁在线」的状态表，等于把无状态改回有状态，
  还要求服务端知道引擎的作业生命周期。**否决**。

所以续期**只能由客户端发起**。本方案的全部内容就是「客户端在什么时机、拿什么去换」。

---

## 2. 断点逐条（现象 ← 代码）

### 2.1 Paimon：框架每请求调认证，但 `bear` 的票是 final

```
RESTApi 构造（一次）           createAuthProvider(options)          → BearTokenAuthProvider(token)
每个 HTTP 请求                  RESTAuthFunction.apply(param)        → authProvider.mergeAuthHeader(...)
```

`AuthProvider` 的签名是 `Map<String,String> mergeAuthHeader(Map baseHeader, RESTAuthParameter param)`——
**它每个请求都会被调用一次**，参数里带着方法、路径、body。也就是说：

> **Paimon 已经给了「每请求动态认证」的钩子，只是内置的 `bear` 没有用它。**
> `BearTokenAuthProvider.token` 是构造期赋值的字段，此后永不变化。

`token.provider` 是 SPI 取值（`AuthProviderFactory.createAuthProvider` → `FactoryUtil.discoverFactory`），
所以我们可以提供自己的 provider，**不改 Paimon 一行代码**。

后果：TTL 到点时，下一次元数据调用（`getTable`、`commit`、`listPartitions`…）拿到 401。
Flink CDC 入湖、流式读、以及任何超过 1 小时的批作业都是必现场景。

### 2.2 Paimon 的「重复实例化」陷阱：缓存不能放在实例字段

`RESTTokenFileIO.refreshToken()`（数据票续期）里有这么一行：

```java
apiInstance = new RESTApi(catalogContext.options(), false);   // 每次刷新数据票都新建一个 RESTApi
```

于是 `createAuthProvider(options)` **会被反复执行**。如果我们的 provider 把当前票缓存在实例字段里，
新实例拿到的只有配置里那张**初始票**——早已过期。所以：

> **缓存必须是进程级、按「端点 + 主体身份」分组的静态表**，而不是 provider 实例的字段。

这不是洁癖：不这么做的话，症状是「目录能读、但读表 1 小时后开始 401」，
而目录那条路（同一个 provider 实例）明明还在正常续期——两条路径表现不一致，极难归因。

### 2.3 Iceberg：看起来有刷新，实际每次刷新都 400

这条最值得单独写，因为它的表象具有欺骗性：**Iceberg 确实会调度刷新，也确实发了请求，只是每次都失败。**

链路（iceberg-core 1.11.0）：

```
AuthManagers.loadAuthManager("oauth2")
  → OAuth2Manager → OAuth2Util.AuthSession.fromToken(...)
      expiresAtMillis = OAuth2Util.expiresAtMillis(token)     // 解析 JWT 的 exp——我们签的正是 JWT
      scheduleTokenRefresh(exp - min(ttl/10, 5min))           // 到期前最多 5 分钟开始刷
        → session.refresh(client)
            → refreshCurrentToken → refreshToken(...)
                exchangeEnabled() 默认 true
                → POST {uri}/v1/oauth/tokens
                     grant_type=urn:ietf:params:oauth:grant-type:token-exchange
                     subject_token=<当前票>&subject_token_type=urn:...:access_token
```

我们的 `OAuthTokenController` 只认 `client_credentials` → 回 **400 `unsupported_grant_type`** →
Iceberg 记 `Failed to refresh token` 并重试 5 次 → `refresh()` 返回 null →
`scheduleTokenRefresh` **只在 `expiration != null` 时继续排下一次** → 于是**不再重试** → 到期即 401。

> **P0-1 之后**：服务端认了这个 grant，上面这条链的 400 那一环消失，Iceberg 侧的刷新**不经任何客户端改动**就能成功
> （`OAuthTokenController` → `TokenExchangeService`）。本节余下的描述保留原样——它记录的是「为什么必须加这个 grant」。

三条附带结论：

1. **静态令牌（非 JWT）反而「不会失败」**：`expiresAtMillis` 解析不出来 → 完全不调度 → 根本不去刷新，
   只是到点后同样 401。两种情况的区别只在日志里有没有 `Failed to refresh token`。
2. **`scope` 的默认值是 `catalog`**（`OAuth2Properties.CATALOG_SCOPE`），而我们只认 `PRINCIPAL_ROLE:ALL`。
   所以「用 `credential` 走 client_credentials 兜底」这条路，如果不同时配 `scope`，
   会在第一次换票就吃 `invalid_scope`——而它发生在客户端启动阶段还是 1 小时后，取决于从哪条路进。
3. **刷新窗口是 `min(ttl/10, 5min)`**：1 小时的票在到期前 5 分钟开始刷。这意味着一个作业「跑 55 分钟没问题」
   会让人误判为「没问题」。

### 2.4 Spark 治理通道：另一条会静默失效的链路

`snippet` 里的治理四行包括 `spark.lakehouse.rest.token`，它被 `PaimonRestManagement.clientFor(session)`
读成 `ManagementApiClient(baseUrl, token, ...)`，而后者把 `token` 存成 **`final String`**，
`HttpClient` 与这个字符串一样是「每会话一个、之后复用」。

会话活过 TTL 之后：血缘上报 401、`GRANT ... TO USER` / `SHOW PERMISSIONS` 401。
**血缘是 fail-open**（异步、队列满即丢、异常吞掉），所以症状是「在血缘图里，作业运行一段时间之后的边消失了」，
而不是任何一条报错——这类问题在数据平台上通常被当成「血缘不准」而不是「令牌过期」。

### 2.5 不属于本方案的两件事（先说清，避免范围蔓延）

| 链路 | 今天的状态 | 本方案 |
| --- | --- | --- |
| **数据面**：Paimon `/v1/{prefix}/.../token`（`data-token.enabled`） | 已实现续期：`RESTToken.expireAtMillis` + `TOKEN_EXPIRATION_SAFE_TIME_MILLIS`，每次读表重取 | **不动** |
| **数据面**：Iceberg `storage-credentials` | 有 `client.refresh-credentials-endpoint` 时会升级成可刷新（服务端下发 config 时才给） | **不动** |
| **数据面**：Lance `storage_options` | connector 0.8 只在 `describe` 时读一次，作业时长必须小于有效期 | **不动**（已知缺口） |
| **控制面**：Lance `header.Authorization` | 静态字符串，无钩子 | **不做**（要等 connector 提供 provider 或重试钩子） |

一句话：**数据面的钥匙能换，控制面的门票不能换**。用户问的「长时间任务令牌过期」是后者。

---

## 3. 方案空间与取舍

| # | 方案 | 密钥落在哪 | 能否撤销 | 服务端改动 | 客户端改动 | 长作业 | 失败恢复 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| A | 调大 `access-token.ttl` | 无 | 轮换密钥（全局） | 一行配置 | 无 | 有限（TTL 之内） | 重启 |
| B | 静态令牌 `auth.tokens` + `token-principals` | 服务端配置（明文） | 删配置项 | 无 | 无 | 是 | 不需要 |
| C | 客户端凭据（`credential=id:secret` 每 TTL 换一次） | **作业配置里放长期密钥** | 轮换密钥 | 无 | Iceberg 原生支持 | 是 | 自动 |
| D | **令牌交换（RFC 8693）** | 只有短票 | 轮换签名密钥（全局） | **一个 grant** | Paimon 要 provider | 是 | 自动，但有上界 |

**选 D 为主，C 为兜底，B 只留给运维脚本，A 只作为临时缓解。**

三条理由：

1. **D 是唯一让 Iceberg 零客户端改动就能续期的方案。** Iceberg 的刷新链已经写好了，缺的只是服务端认这个 grant。
   走 C 也能用，但代价是把**长期密钥**放进每一个作业的配置里——这种「为了续期而把密钥铺开」的交换不划算。
2. **D 不产生新密钥。** 交换用的是同一套签名密钥、同一条认证链、同一个限速窗口与审计通道；
   加了 D 之后，「谁能取票」这件事仍然只有一个答案。
3. **D 自带寿命上界，而 C 没有。** `subject_token` 必须**仍然有效**才能换（`verify()` 会判 `exp`），
   所以作业挂起超过一个 TTL 就续不上、必须重启换票。这是特性不是缺陷：它保证「一张泄露的票」最多被链式续到
   「持有者一直活着」为止，而不是永不过期。

被否决的两条（写下来是因为它们最容易被再次提出）：

- **滑动过期**：见 §1.3。它把「可续期」偷换成「无有效期」。
- **给引擎发「长期令牌」**：需要第二档 TTL + 第二套语义，且这类票一旦泄露就是长期后门；
  在没有撤销机制的前提下，唯一处理手段是轮换签名密钥——那是把所有控制台用户一起踢下线。

---

## 4. 定案

### 4.1 服务端：加 RFC 8693 令牌交换

**请求**（沿用现有端点的「form-urlencoded 与 JSON 都接」）：

```
grant_type         = urn:ietf:params:oauth:grant-type:token-exchange
subject_token      = <当前有效的访问令牌>
subject_token_type = urn:ietf:params:oauth:token-type:access_token
scope              = PRINCIPAL_ROLE:ALL        # 可省；省则沿用 subject 的 scope
requested_token_type = urn:ietf:params:oauth:token-type:access_token   # 可省
```

**响应**：现有 `TokenResponse` 加一个 `issued_token_type` 字段（RFC 8693 要求，Iceberg 允许它缺省但会用到），
`access_token` / `token_type=bearer` / `expires_in` / `scope` 不变。
**不改 `TokenResponse` 的形状给老客户端**是硬要求：它是 RFC 6749 端点，加字段是兼容的，改字段名不是。

**七条判定规则**（每条都要有对应用例）：

| # | 规则 | 理由 |
| --- | --- | --- |
| 1 | `subject_token` 走**同一条**认证链（静态 / 自签 JWT / OIDC），失败 → 400 `invalid_grant` | 复用 `TokenAuthenticationService.authenticate`；失败码选 `invalid_grant` 是因为调用方该做的是「拿新票重试」，与 `invalid_client`（凭据不对）区分开 |
| 2 | 新票 `sub` **必须**等于 subject 的 `sub` | 否则就是提权。本工程 `scope` 只有一档，实际等价于「原样沿用」 |
| 3 | 新票 TTL = `access-token.ttl`，**不放大** | 交换不是「延期」，是「换一张同寿命的新票」 |
| 4 | `scope` 只接受 `PRINCIPAL_ROLE:ALL` 或缺省（缺省沿用 subject）；其余 → `invalid_scope` | 与 `client_credentials` **同一套归一化**。Iceberg 每次换票都会带上 `config.scope()`，缺省值是 `catalog`——这条规则决定了「`scope` 那一行必须配」（§4.3） |
| 5 | `requested_token_type` 只接受 `…:access_token`；`actor_token` 一律 400 `invalid_request` | 我们还没有「代他人操作」的模型。**静默忽略一个声明等于假装支持它**——这与 `invalid_scope` 的取舍同一个理由 |
| 6 | 审计日志记 `grant=token-exchange` + 父票的 `jti` | 没有撤销机制时，「这张票从哪来」是唯一的事后线索 |
| 7 | 失败计入 `LoginAttemptLimiter`（与 `client_credentials` 共用窗口）；成功不限速 | 与今天一致：匿名端点的防爆破只针对失败 |

**已落地的形态**（`TokenExchangeService` + `OAuthTokenController` 的 grant 分支 + `TokenResponse.issued_token_type`，
用例在 `TokenExchangeTests`，17 条）。实现时定下、文档里原来没写的四件事：

- **没有开关。** 不留 `exchange.enabled`：它唯一能产出的是与调用方手里的票**等权**的另一张票，
  关掉不增加安全性，却会让长作业在一小时后失败——正是本功能要消除的故障。要「总时长上限」做 P2 的
  `max-session`，不要靠关续期。
- **限速桶与 `client_credentials` 分开。** 规则 7 说的「共用窗口」是共用**阈值配置**
  （`console.client-credentials.rate-limit.*`），不是共用一个桶：交换在认证成功之前不知道调用方是谁，
  只能按来源 IP 分桶（键是 `ip|token-exchange`）。混在一个桶里会让「有人在刷登录」把长作业的续期一起锁掉。
- **规则 4 的「缺省沿用 subject 的 scope」在本工程等价于 `PRINCIPAL_ROLE:ALL`。**
  因为 `ClientCredentialsService` 对空 scope 也归一成它，本服务端签出的每张票都带着这个值；
  而外部 OIDC 票的 `scope` claim 是 IdP 的语义（`openid profile` 之类），原样搬进我们签的票只会在
  下一次换票时变成 `invalid_scope`——那时距离真正的故障已过一个 TTL。
- **`subject_token_type` 缺省放行、取值不对拒绝。** 「没说」与「说了另一种类型」要区别对待：
  前者只是省了参数，后者说明它期望的行为（如 `id_token`）我们没实现，必须让它看见拒绝。

**P2 可选护栏（默认关）**：`ses` claim（会话起点）+ `lakehouse.rest.auth.access-token.max-session`
（默认 `0` = 不限）。换票时 `ses' = 父票的 ses ?: 父票 iat`，若 `now - ses' > max-session` 则拒绝。
这一条把「无限续期」收成「最长 N 小时」，给需要「作业不能永久持有访问权」的合规场景一个旋钮。
算式简单，但**默认不开**：开了会让超过 N 小时的长作业必须重启，而我们今天连 1 小时都过不去。

### 4.2 Paimon：`token.provider=oauth2`

**配置**（Spark 与 Flink 同形，只是前缀不同）：

```
token.provider=oauth2
token=<主体令牌>                                  # 起始票
# 可选：起始票不可用（已过期 / 没填）时用凭据兜底
oauth2.credential=<clientId>:<clientSecret>
oauth2.token-endpoint=<uri>/v1/oauth/tokens       # 不配则由 uri 推导
oauth2.refresh-window=300s                        # 默认 min(ttl*0.15, 300s)
```

**行为定义**（这几条比配置项本身重要）：

1. `mergeAuthHeader` 每次被调 → 查进程级缓存；未进刷新窗口直接返回；进窗口则换票（**single-flight**：
   双检 + 锁，多个并发请求只发一次换票）。
2. **换票失败返回旧票，不抛异常。** 旧票可能还剩几十秒，抛出去会把「本来能成功的请求」变成 500；
   连续失败按 1s/2s/4s 退避（上限 30s）并打 WARN。
3. `expires_in` 缺失时读 JWT 的 `exp`；两者都没有 → 判定「不可续」，首次取票后打一条 WARN 说明
   「这张票不会自动续期」。**不要假装能续**。
4. 只有 `credential` 没有 `token` → 首次调用时直接走 `client_credentials` 取票（冷启动路径）。
5. 缓存 key = `endpoint + "|" + (credential ?: token 的 sub)`；理由见 §2.2。
6. 时钟走可注入的 `Clock`：测试要把「窗口」调到毫秒级，否则这类用例只能靠等。
7. **不碰数据票**：`data-token.enabled` 的语义、`/token` 的调用次数、`RESTTokenFileIO` 的行为一行都不变。

**为什么 identifier 用 `oauth2` 而不是 `exchange` / `refresh`**：与 Iceberg 的 `rest.auth.type=oauth2` 同名。
文档里读者只需要记住一个词。（这是可改的命名决定，见 §9。）

**模块与 SPI 落点**：

- 新增纯 Java 模块 `lakehouse-rest-engine-auth`（`provided` 依赖：`paimon-common` 仅供 SPI 接口、
  `jackson` 解析换票响应、`slf4j`），并登记 `META-INF/services/org.apache.paimon.factories.Factory`。
- 不塞进 `lakehouse-rest-spark-common`：那份模块的定位是「管理 API 的 REST 客户端」，
  让 `lakehouse-rest-flink-*` 依赖一个叫 `spark-common` 的模块，按 `maven-module-design.md` §4 的判据不划算。
  同一个模块里也放 §4.4 的 `TokenSource`——两者是同一件事（拿票、续票）的两个消费面。
- **ClassLoader 约束（部署时唯一的硬坑）**：`FactoryUtil.discoverFactory` 用的是
  `AuthProviderFactory.class.getClassLoader()`，**不是**线程上下文 loader。因此服务文件必须与
  `paimon-bundle` 处在**同一个 ClassLoader** 里：
  - Spark：provider jar 与 paimon bundle 都要在 `--jars` / 分发的 `jars/` 里（同一 loader），
    **不能**一个在 `jars/`（父 loader）一个在 `--jars`（子 loader）；
  - Flink：放 `lib/`；用 `-j`（用户 jar → 子 loader）会看不见。
  - 症状是一句 `Could not find any factory for identifier 'oauth2'`，不会提示 classloader——
    这句话要写进 `flink-rest-catalog.md` 与两份 e2e 文档的报错表。
- **两个 Paimon 版本共用一个 provider**：`lakehouse-rest-flink-1.20`（Paimon 2.1.0）与
  `lakehouse-rest-flink-2.3`（2.2 连接器）的 `AuthProvider` SPI 签名一致，但这是**实测结论、不是承诺**：
  升级 Paimon 时要重看一眼 `AuthProvider#mergeAuthHeader` 的签名与 `AuthProviderFactory` 的
  `identifier()` 约定，变了就得同版本提供两份 provider（与 Spark 3.5/4.1 用 `shared/` 的处置方式相同）。

### 4.3 Iceberg：不加代码，只加两行配置

```properties
spark.sql.catalog.<name>.rest.auth.type=oauth2
spark.sql.catalog.<name>.token=<主体令牌>
spark.sql.catalog.<name>.scope=PRINCIPAL_ROLE:ALL      # 必须有，理由见下
```

- **`scope` 这一行不是可选的。** Iceberg 的 `AuthConfig.scope()` 默认 `catalog`，而服务端只认
  `PRINCIPAL_ROLE:ALL`。缺了它，换票会被 `invalid_scope` 拒——而报错发生在「55 分钟之后」
  （第一次刷新，见 §2.3 的刷新窗口），排查成本极高。控制台片段必须带上它。
- `token-exchange-enabled` 保持默认 `true`（我们要的正是 exchange）；`token-refresh-enabled`
  保持默认 `true`。
- **服务端还没升级时的兜底三行**（分批升级期间必须能用）：

  ```properties
  spark.sql.catalog.<name>.credential=<clientId>:<clientSecret>
  spark.sql.catalog.<name>.token-exchange-enabled=false
  spark.sql.catalog.<name>.scope=PRINCIPAL_ROLE:ALL
  ```

  此时 `refreshToken` 走 `fetchToken`（client_credentials），`expires_in` 也在，同样能续。
  代价是长期密钥进了作业配置——**这是兜底，不是首选**。
- **不做**自定义 `AuthManager`（`AuthManagers` 的 `default:` 分支支持填 FQCN）。能做，但原生 oauth2
  已经覆盖同一件事，多一个类就多一份要跟着 Iceberg 版本走的兼容面。
- **版本基线**：上面的链路读的是 **iceberg-core 1.11.0**（Spark 侧）。Flink 2.3 模块用 1.12.0，
  未逐行核对；实现时以 `OAuth2Util#refreshToken` / `#scheduleTokenRefresh` 与 `AuthConfig#scope` 三处为准，
  升级 Iceberg 时优先重看这三处——它们是「刷新会不会发生」「刷新走哪个 grant」「scope 发什么」的全部判据。

### 4.4 Spark 治理通道：`TokenSource`

- `ManagementApiClient` 的 `final String token` 换成 `TokenSource`；默认 `TokenSource.fixed(token)`
  时**行为与今天逐字节相同**（这是「不破坏既有部署」的判据，要有用例钉住）。
- 新增配置（都在 `spark.lakehouse.rest.management.*` 下，与既有四键同族）：

  ```
  spark.lakehouse.rest.management.credential=<clientId>:<clientSecret>
  spark.lakehouse.rest.management.refresh-window=300s
  ```

- **401 重放**：第一次 401「本次请求还没用过刷新后的票」→ 强制换票 + 重放一次；第二次 401 原样抛。
  只重放一次的理由：把 401 变成重试风暴比 401 本身更糟。
- 血缘上报那条路继续 fail-open（不让用户的 SQL 因为上报失败而失败），但它不该再「因为票过期而永远失败」。

### 4.5 控制台片段（`engineSnippet.js`）

改动草案，四项：

1. **Paimon 两面**：`token.provider` **默认仍是 `bear`**。理由：老 classpath 上没有我们的 jar，
   写 `oauth2` 会让 `getOrCreate()` 直接失败（`Could not find any factory`）。改成「按开关二选一」，
   开关默认关，旁边一行注释说明切换后需要什么 jar。
2. **Iceberg 两面**：加 `scope=PRINCIPAL_ROLE:ALL`；note 里把
   「delegation 只在服务端下发 refresh-credentials-endpoint 时才升级成可刷新」拆成两句——
   **主体令牌的续期**与**存储凭据的续期**是两件事，前者靠 exchange，后者靠 delegation。
3. **`RENEWAL` 表拆两列**：现在这张表把「数据票能续」写成了「能」，而用户问的是控制面。
   改成 `{control, data}` 两列，Paimon 是「能 / 能」，Iceberg 是「能（服务端支持 exchange 时）/ 要看客户端」，
   Lance 是「不能 / 不能」。
4. **新的机械校验**：片段里的 `PRINCIPAL_ROLE:ALL` 必须与
   `ClientCredentialsService.SCOPE_ALL` 同字面量（脚本直接读 Java 常量），
   否则「改一处忘另一处」会变成「配置永远报 invalid_scope」。
   另加一条反向断言：片段里**不得**出现 `clientSecret` 之类的值（只有占位符）。

---

## 5. 失败模式与观测

| 现象 | 根因 | 判据 |
| --- | --- | --- |
| 作业跑到约 1 小时后开始 401，重启即恢复 | 控制面票过期且没续上 | 服务端日志里该主体在某一刻之后全是 401；`jti` 不变（说明没换过票） |
| Iceberg 日志每个 TTL 出现一次 `Failed to refresh token`，随后 401 | exchange 不通（服务端未升级 / 被前置网关拦掉这个 grant） | 抓包看 `/v1/oauth/tokens` 的 `grant_type` 与响应码 |
| Paimon 启动即失败：`Could not find any factory for identifier 'oauth2'` | provider jar 与 paimon bundle 不在同一 ClassLoader | 见 §4.2 末段 |
| Paimon 目录能读、读表 1 小时后 401 | 缓存放在了 provider 实例字段（§2.2） | 换票日志一条都没有，而数据票刷新日志在涨——「同一个主体，两条路径一个在续一个没续」就是它 |
| 血缘图「前半段有边、后半段没有」 | 治理票过期 + fail-open | 治理通道手工发一次请求看是否 401 |
| 换票后立刻 401 | 交换返回的 `sub` 与原票不一致（不该发生） | 用例钉住；一旦出现当作缺陷 |

**建议的观测点（P2）**：

- `lakehouse_auth_token_exchanged_total{grant,result}`：交换量。持续增长是好现象；突然为 0 说明「某处不再续了」。
- `lakehouse_auth_token_refresh_failure_total{cause}`：跨 TTL 持续为 0 才算健康。
- 服务端 401 响应里带上「过期」与「无效」的区分（现在两者都是 401 且报文相同）。
  不改状态码，只在 `WWW-Authenticate` 或错误体里给一个标记——作业日志里能一眼看出是「票过期」而不是「权限不对」。

---

## 6. 兼容与迁移

| 组合 | 结果 |
| --- | --- |
| 只升服务端（引擎不动） | 老配置（`bear` / `token` / 静态令牌）行为不变；Iceberg 的刷新链**自动开始工作**（它本来就在发 exchange）——这是「升了服务端就悄悄修好一半」的那一半 |
| 只升引擎（服务端没 exchange） | Paimon provider 把 `token.provider` 改成 `oauth2` 后，换票会 400；必须在配置里加 `oauth2.credential` 走 client_credentials 兜底，否则退化成「不续期」 |
| 两者都升 | 全链路可续；引擎配置里只有短票（或用凭据兜底，按安全偏好二选一） |
| 已有静态令牌部署 | 不受影响。静态令牌在认证链第一档命中，不进入 JWT 分支，因此与 exchange 完全正交 |

**升级顺序建议**：服务端 → 控制台片段（带兜底注释）→ 引擎侧发版。

---

## 7. 安全

- **交换不产生新密钥**：同一套签名密钥、同一条认证链、同一个限速窗口。可攻击面没有变宽。
- **不放大**：`sub` 固定、TTL 不变、`scope` 不扩。交换在授权上的等价物是「重新登录一次」。
- **上界**：`subject_token` 必须仍然有效（§3 理由 3）。挂了超过一个 TTL 就得重启。
- **链式续期的风险**：一张泄露的票可以在整个 TTL 内不断换新票。三条缓解：P2 的 `max-session`、
  审计里的 `parent jti`（能追到链的起点）、轮换签名密钥（一次性作废全部，代价是所有用户重登）。
- **凭据泄露风险（兜底路线 C 才有）**：`oauth2.credential` 里是长期密钥。Spark 会把含 `secret` 的配置项
  在 UI 上打码，但 `--conf` 在进程列表里可见；推荐用 secret 挂载 + 启动时 `spark.conf.set`。
  这也是「C 只做兜底」的理由之一。

---

## 8. 实施计划与验收

### P0（能让长作业活下来）

| # | 内容 | 状态 | 验收用例 |
| --- | --- | --- | --- |
| P0-1 | 服务端 exchange：`TokenExchangeService` + `OAuthTokenController` 分支 + `TokenResponse.issued_token_type` | **已落地** | `TokenExchangeTests`（17 条）：正常换票 / 换出的票能被受保护端点接受 / 过期 subject → `invalid_grant` / `scope` 不支持 → `invalid_scope` / `actor_token` → `invalid_request` / `requested_token_type` 与 `subject_token_type` 不支持 → `invalid_request` / 换出的票 `sub` 与原票一致且 `exp - iat` 不放大 / 连换两级主体不变 / 静态令牌可换 / 失败限速 / client_credentials 响应不多出 `issued_token_type` / JSON 体与两个路径都通 |
| P0-2 | Paimon `OAuth2AuthProvider` + `Factory` + SPI 文件 | **已落地** | `RefreshingAuthProviderTests`（30 条）：未进窗口不换票 / 进窗口换一次（并发 8 线程只发 1 次请求）/ 换票失败返回旧票且不抛 / `expires_in` 缺失时读 JWT `exp` / 两者都缺失时不换且打 WARN / 窗口被压到 TTL 的 90% / 重建 provider 后复用已续期的票 / 缓存键不含令牌 / SPI 文件确实存在 / 起始票已过期时直接取票 |
| P0-3 | Iceberg 配置落地：两份 e2e 文档加 `scope` 那一行与对照表，无代码 | **已落地**（文档） | 短 TTL e2e：`ttl=90s`，跑 5 分钟反复 `select`，跨过 90 秒仍成功；日志里有 exchange 记录。步骤写进 `README.md` §2.3 |
| P0-4 | `TokenSource` + `ManagementApiClient` 改造 | **已落地** | `TokenSourceTests`（8 条）：`fixed` 行为与今天一致（逐字节：同一 `Authorization` 头）/ 到期前换票 / 401 只重放一次 / 第二次 401 原样抛 / `invalidate()` 为 false 时不重放；`ManagementApiClientTests`（31 条）全绿 |

### P1（让人不用读文档也能用对）

**已落地。** 控制台四片段改造 + `RENEWAL` 两列 + `PRINCIPAL_ROLE:ALL` 字面量对齐断言：

- Paimon 两面 `token.provider` 默认仍是 `bear`，详情页给一个「主体令牌自动续期」开关
  （默认关，只在 Paimon 面显示），打开后片段变 `oauth2` 并带上「需要哪个 jar、放在哪」的注释；
- Iceberg 两面加 `scope=PRINCIPAL_ROLE:ALL`，note 拆成「主体令牌的续期」与「存储凭据的续期」两句；
- `RENEWAL` 拆成 `{control, data}` 两列，页面上分两行显示；
- `tools/snippet-tests` 加 4 条（默认 `bear` / `oauth2` 只在显式要求时出现 / Iceberg 两面都有 `scope` /
  片段里只有占位符没有真实密钥）；`verify-console.py` 加第 26 组（8 项），
  其中 `scope` 的字面量**直接读** `ClientCredentialsService.SCOPE_ALL` 比对，不抄第二份。

### P2（收边界）

**已落地**，三件事：

- **`max-session` 护栏**（`lakehouse.rest.auth.access-token.max-session`，默认 `0` = 不限）：
  令牌多一个 `ses` claim，换票时 `ses' = 父票 ses ?: 父票 iat`，超过上限拒绝交换，
  但**已签发的票仍可用到期**——否则跑满 N 小时的作业会在某个请求上毫无预兆地 401。
  只在打开时写 `ses`，关掉时令牌形状与改造前完全一致（`TokenSessionLimitTests` 6 条钉住，
  含「上限打开之前签出的票用 `iat` 兜底算年龄」这条升级路径）。
- **指标**：服务端 `lakehouse.auth.token.exchanged{grant,result}`（`TokenIssuanceMetrics`，
  按 `metrics-design.md` 已定的名字计数，每 10 分钟把增量打进日志——仓库还没有指标注册表，
  新的控制台端点要连带登记权限点与校验脚本，为两个计数器做这些不划算）；
  引擎侧 `lakehouse.auth.token.refresh.failure{cause}`（`TokenRefreshMetrics`），
  累计次数与原因写进**同一条** WARN：单条 WARN 分不清「偶发一次」与「每个请求都失败」。
  同一个类里还有 `lakehouse.auth.token.legacy-key{kid}`：**用非当前签发钥验过的次数**，
  它是「那把旧钥现在能删了吗」的唯一判据（连续一个窗口无新增才删）。
  记在验签那一层而不是端点层，因为验签发生在每个带 Bearer 的请求上——
  它与 `exchanged` 是同一问题的两面：前者说「还在续」，后者说「还有谁没续上来」。
- **401 的「过期 / 无效」区分**：响应体一字不差（那是既有的安全取舍），区别放在
  `WWW-Authenticate`——`Bearer error="invalid_token", error_description="the access token has expired"`
  与 `… "the access token is not valid"`。先验签再判过期：签名不对时 `exp` 是不可信输入。
  没带令牌时按 RFC 6750 §3.1 只回 `Bearer`，不给 `error` 码。
- Lance 面等 connector 支持后再说（本期不做）。

### 验收的唯一有效方法

> **把 `lakehouse.rest.auth.access-token.ttl` 调到 90 秒，跑一个 ≥ 5 分钟的作业。**

理由：默认 1 小时的用例没人会在 CI 里跑，而在 TTL 之内「配置看起来对」与「真的续上了」**完全无法区分**——
Iceberg 的失败甚至要等到 55 分钟后才出现。短 TTL 是唯一能把「没续上」变成 2 分钟内可复现的手段。
这条要写进 `README.md` 的本地验证段落，否则下一个人还会用 1 小时的 TTL 去验。

---

## 9. 落地时定下的事（原「未决问题」）

1. ~~**`token.provider` 的取值**：`oauth2` 还是 `exchange`？~~
   **已定：`oauth2`**（与 Iceberg 的 `rest.auth.type` 同名，读者只需记一个词）。
2. ~~**失败码**：exchange 的 subject 无效用 `invalid_grant`（RFC 6749 语义）还是 `invalid_token`（RFC 6750）？~~
   **已定：`invalid_grant`。** RFC 6749 §5.2 把它定义为「授权许可无效」，而 subject token 在这次交换里
   就是授权许可；调用方该做的是「重新拿票」。`invalid_token`（RFC 6750）留给
   `BearerAuthInterceptor` 那条路——「访问受保护资源时令牌不管用」。两者混用会让作业日志里的
   401/400 分不清是「续期失败」还是「权限不对」。
3. ~~**兜底路线的默认**：没配 `credential` 且起始票已过期时，抛错还是打 WARN 继续用？~~
   **已定：既不抛也不「继续用过期票」——起始票过期但有 `credential` 时直接冷启动取票。**
   只配 `credential` 不配 `token` 也走同一条路（第一次请求就 `client_credentials`）。
   没有 `credential` 时按 b) 处理：不动那张票，让服务端回 401，Paimon 侧只记一条 WARN。
   理由：a) 会让「作业排队一小时后再启动」直接起不来，而那种场景下凭据本来就能救；
   而 b) 的代价只是一条 401，它恰好出现在日志里、指向真正的原因。
4. ~~**控制台默认档**：续期开关默认关还是默认开？~~
   **已定：默认关 + 开关旁写明需要什么。** 默认开会让所有还没有 provider jar 的部署在建
   catalog 时直接失败，而那句报错（`Could not find any factory`）指向的是「版本对不对」，
   不是「少了一个 jar」。
5. ~~**`max-session` 要不要现在就做**：~~
   **已定：做，但默认 `0`（不限）。** 算式简单、边界清楚，且「有旋钮」与「默认打开」是两件事：
   打开它会让跑超过 N 小时的作业必须重启，而在本功能落地之前长作业连一小时都过不去。
   打开时**已签发的票仍可用到期**，不让在跑的作业无预兆 401。
6. **Flink 侧的部署约束要不要进镜像**（仍未定）：provider jar 必须与 paimon bundle 同 loader
   这条约束，现在只写在 `flink-rest-catalog.md` §2.1 与 `spark-sql-extension.md` §1 的部署表里。
   进镜像要动 `deploy/` 的 Dockerfile，而本仓库的 Flink 用例不跑对象存储、也不需要续期——
   没有能验收它的用例之前，改镜像只是把一个没验过的假设固化下来。

---

## 10. 与既有文档的关系

- 令牌的三种来源、控制台登录、`WWW-Authenticate` 的取舍：[`console-auth.md`](console-auth.md)。
- 两侧 catalog 选项与实测结论（含报错对照表）：[`flink-rest-catalog.md`](flink-rest-catalog.md)、
  [`spark-paimon-rest-e2e.md`](spark-paimon-rest-e2e.md)、[`spark-iceberg-rest-e2e.md`](spark-iceberg-rest-e2e.md)。
- **数据面**凭据下发（谁拿什么钥匙、能不能续）：[`credential-vending-design.md`](credential-vending-design.md)、
  [`catalog-storage-auth-design.md`](catalog-storage-auth-design.md)。
  本文件只讲**控制面**（主体令牌），两份文档里的「续期」说的都是数据面，不要互相引用错。
- 片段生成器的三条硬约束（纯函数、不判档位、注释写进片段）：
  `lakehouse-rest-console/src/utils/engineSnippet.js` 顶层注释。
