# SSO 身份接入与主体供给：A+C 产品化落地设计

**状态：MVP 已落地（2026-11-03）。** 目标是把「平台 SSO 登录」与「catalog 授权主体」连成一条
可运营的链路：**OIDC 登录 → JIT 建主体 → 组→角色同步 → 令牌交换给引擎**。

落地形态与本文的对应关系（读文档时先看这一块，避免把「设计」当成「待做」）：

| 本文 | 代码 |
| --- | --- |
| §4 JIT 供给 | `OidcLoginService`（建主体、同名判定、邮箱域白名单、失败语义） |
| §5 组同步 | `GroupRoleSyncService`（`preview` 与 `sync` 共用一份 `desired` 计算） |
| §6 C 侧收尾 | 未改：`TokenExchangeService` 一行都没动，且**不调用**供给（§5.6） |
| §7 管理面 | `GroupMappingController`、`PrincipalController#sync`、`PrincipalService#list(source, …)` |
| §7.3 审计 | `principal.login` / `principal.provisioned` / `principal.roles_synced` / `group_mapping.changed` |
| §8 控制台 | `lakehouse-rest-console/` 的五个画面（`#login` / `#idp` / `#principals` / `#groups` / `#audit`）；OIDC 配置只读，见 §8.4 |
| §9 测试 | `SsoIdentityProvisioningTests`（16 例）+ `GroupMappingApiTests`（9 例） |

> 与本文的两处偏差，都在实现时定下的，理由写在各节：
> ① `last_login_at` 用 `bigint`（epoch 毫秒）而不是 `datetime(6)`，与 `created_at` 一致；
> ② 手动同步端点**要求显式传 `groups`**（服务端不保存上次登录的组快照）。

前置阅读：[`console-auth.md`](console-auth.md)（认证链与三种登录方式）、
[`authorization.md`](authorization.md)（授权链）、
[`engine-token-refresh-design.md`](engine-token-refresh-design.md)（令牌交换）、
[`data-model.md`](data-model.md)（表结构）。
配套原型：[`sso-catalog-identity-mockup.html`](sso-catalog-identity-mockup.html)（五画面，评审产物；真实实现在 `lakehouse-rest-console/`，落地位置见 §8.4）。

---

## 0. 结论先行

1. **A+C 的桥已经通了。** `TokenExchangeService.exchange()` 的 `subject_token` 走的是
   `TokenAuthenticationService.authenticate()`，而它按「静态令牌 → 控制台 JWT → **OIDC 令牌**」
   三路合流。也就是说：拿 IdP 签发的 OIDC 访问令牌直接换一张 catalog 访问令牌，
   **今天就能跑**，不需要新的交换实现。
2. **缺的不是 token，是供给。** 登录只产出一个字符串主体名；`lakehouse_principal` 里没有行、
   没有任何 grant，于是「登录成功、每个页面 403」。JIT 与组映射才是产品化的主工作量。
3. **MVP 是五件事**：主体表加四个列（`display_name` 先存不用）、角色成员表加一个列、
   新增一张组映射表、登录时 JIT + 组同步、组映射的 CRUD + 预览 + 手动同步端点。
   其余（多 IdP、audience 收窄、SCIM）属于 P1/P2。
4. **三条不可破坏的不变量**：
   - 交换**不放大**权限（`sub` 取自认证结果、TTL 不延长、scope 只有 `PRINCIPAL_ROLE:ALL`）；
   - 组同步**只删自己建的**行（`granted_by=IDP`），永不动手工授权；
   - `groups` claim 缺失时**默认不清空**（否则 IdP 一次抖动就会削掉所有人的权限）。

---

## 1. 事实基线（代码级）

### 1.1 登录侧：验得很完整，供给为零

`OidcService.verify()` 依次做五件事：算法白名单（拒 `none`/未知）、`iss` 与发现文档一致、
JWKS 验签（未知 `kid` 强制重取一次）、时间窗（`exp`/`nbf` + `clock-skew`）、`aud` 匹配；
最后从 **`principal-claim`（默认 `sub`）** 取出主体名，返回 `AuthenticatedToken(name, expiresAt)`。

**它到此为止。** 没有建主体、没有读 `groups`、没有写任何表。
`RestServerProperties.Auth.Oidc` 里也没有 `group-claim` 这个字段（已核对）。

> 本节是**动工前**的事实基线，留着是为了说明「为什么需要供给」。
> 落地后 `OidcService` 多了 `identity(decoded)`（取 `groups` / `email` / `sub`），
> 配置多了 `group-claim` 等 7 项，见 §5。

### 1.2 授权侧：只按名字查库

授权链不读令牌里的任何 claim，只按**主体名**查：

```
principal.name
  → lakehouse_principal_role_grant(principal_id, principal_role_id)   // granted_by 目前不存在
  → role:<principal role 名>                                          // 候选串
  → lakehouse_permission_assignment(resource, access, principal)      // 唯一的授权表
```

两个直接推论：

- **令牌里带不带 `groups` 不影响授权**。角色是每次请求查库算出来的，所以
  「IdP 里撤掉一个人的组」在**下一次请求**就生效（不必等令牌过期）——这是本设计最有利的性质。
- **主体不存在 = 零权限**。`permission_assignment.principal` 是原文匹配，
  没有行就是什么都不命中，接口回 403（不是 500）。

### 1.3 交换侧：四条不放大已经写死

`TokenExchangeService` 的四条保证（都有用例）：
`sub` 取自认证结果（请求里没有「换成谁」）、TTL 固定 `access-token.ttl`、
scope 与 `client_credentials` 共用归一化（只认 `PRINCIPAL_ROLE:ALL`）、
`subject_token` 必须仍然有效；另有 `max-session` 链龄上限、按 IP 限速、审计父 `jti`。
`actor_token` 显式拒绝（没有「代他人操作」模型）。

### 1.4 今天首次 SSO 登录会发生什么

```
浏览器 → IdP 登录 → 回到控制台 → GET /api/console/v1/auth（带 id_token）
  ├─ 验签通过、principal = "9f3c…"（sub）
  ├─ 查 lakehouse_principal.name = "9f3c…" → 查不到
  └─ 返回 authenticated=true, principal="9f3c…"
控制台开始拉目录 → 403（每条链路都查不到授权）
```

现象是「登录成功、界面全空」，日志里只有授权未命中。**这正是 JIT 要消掉的体验。**

### 1.5 四个已经存在、但没连起来的点

| 已经有 | 缺的那一脚 |
| --- | --- |
| OIDC 票可以作 `subject_token` 换票 | 没有人告诉平台「登录后该拿哪张票去换」 |
| `role:<名字>` 候选串参与判定 | 组名 → 角色名没有映射入口 |
| `lakehouse_audit_event` 有 `PRINCIPAL` 类别 | 登录 / 供给 / 同步没有事件类型 |
| 控制台 `/auth` 引导会下发 `session.principal` | 前端不知道这个主体是刚建的、还是本来就有 |

---

## 2. 目标与非目标

| | 内容 |
| --- | --- |
| **目标** | ① 一次 SSO 登录；② 首次登录自动建主体（JIT）；③ IdP 组自动映射到 principal role；④ 引擎用同一身份换票，不配长期密钥；⑤ 登录 / 供给 / 同步可审计 |
| **非目标（本期）** | SCIM 全量同步、多 IdP 按 `iss` 路由、刷新令牌、组名通配符、审批流、行级 ABAC（已有独立策略层）、工作负载身份（CI/CD 的机器身份） |

**为什么不做 SCIM。** SCIM 的前提是「catalog 是用户目录的主人之一」，而本设计的立场相反：
**IdP 是唯一事实源，catalog 只缓存「这个人现在该有哪些角色」**。登录时重算已经能收敛，
SCIM 带来的是「删除用户 / 停用账号」这类能力——那属于 P2，且要先想清楚「停用后已签发的票怎么办」。

---

## 3. 身份模型

### 3.1 主体名从哪个 claim 来

`principal-claim` 现在可配，默认 `sub`。三个候选的取舍：

| claim | 稳定性 | 可读性 | 问题 |
| --- | --- | --- | --- |
| `sub`（默认） | **最高**，IdP 内不变 | 差（UUID） | 审计里全是 `9f3c…`，人认不出 |
| `email` | 中，改邮箱会变 | 好 | 改名 = 换主体 = 丢授权；且邮箱可被回收 |
| `preferred_username` | 中 | 好 | 可改；多 IdP 下可能重名 |

**定案：`name` 用 `sub`，另存 `display_name` 用 `email`。** 授权绑定稳定标识，
界面与审计显示人名。`display_name` 属于 P1（MVP 先只加列、不读）。

> **不要用邮箱当主体名。** 一旦某人改邮箱，旧授权全部落空，而现象是「昨天还能看，今天 403」——
> 排查时几乎不会想到是改名。如果组织坚持要可读主体名，必须在 IdP 侧把邮箱设为不可变。

### 3.2 本地主体与 OIDC 主体不能同名劫持

`lakehouse_principal.name` 是唯一键。如果 JIT 直接 `find-or-create(name)`，
一个 OIDC 用户只要 `sub` 恰好等于某个**本地主体名**（例如 `root`、`admin`），
就会**继承那个主体的全部授权**——这是提权，不是便利。

三条规则：

1. 已存在且 `source=LOCAL` → **拒绝登录**，错误码 `principal_conflict`，
   提示管理员「改名或显式绑定」；
2. 已存在且 `source=OIDC` 且 `external_id` 相同 → 正常放行（老用户）；
3. 已存在且 `source=OIDC` 但 `external_id` 不同 → 也拒绝（同名的另一个 IdP 用户）。

「显式绑定」（把本地主体转成 OIDC 主体）属于 P2，需要管理面二次确认；本期不做，
**宁可拒绝也不静默接管**。

### 3.3 `external_id` 与改名

`external_id = issuer + "|" + sub`。它不参与授权，只用于：

- 判断「同名是不是同一个人」（§3.2 第 2/3 条）；
- IdP 换域名 / 换 issuer 时，运维能一眼看出哪些主体失联；
- 审计里可追溯到具体 IdP。

`name` 变了（例如从 `sub` 改成邮箱）等于换主体，**不做自动迁移**——迁移是一次
「旧主体授权 → 新主体」的运维动作，必须显式做，不能靠猜。

### 3.4 表结构变更

三处，都是**加列**；只有一张新表。

**`lakehouse_principal`（+4 列）**

| 列 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `source` | `varchar(16)` not null | `LOCAL` | `LOCAL` / `OIDC`；存量行全是 `LOCAL` |
| `external_id` | `varchar(512)` null | — | `issuer + "\|" + sub`，仅 OIDC |
| `display_name` | `varchar(255)` null | — | `email` / `preferred_username`，P1 才读 |
| `last_login_at` | `bigint` null | — | 最后一次成功认证时刻（epoch 毫秒，与 `created_at` 同单位） |

**`lakehouse_principal_role_grant`（+1 列）**

| 列 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `granted_by` | `varchar(16)` not null | `MANUAL` | `MANUAL` / `IDP`；同步只删 `IDP` |

**`lakehouse_group_role_mapping`（新表）**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `varchar(64)` | 主键 |
| `provider_name` | `varchar(128)` not null default `default` | 预留多 IdP，MVP 恒 `default` |
| `idp_group` | `varchar(255)` not null | IdP 里的组名，**精确匹配** |
| `role_name` | `varchar(255)` not null | principal role 名 |
| `enabled` | `boolean` not null default true | 停用而不删，便于回滚 |
| `created_by` / `updated_by` / `created_at` / `updated_at` | 审计列 | 继承 `AuditedEntity` |

唯一键：`uk_group_role_mapping(provider_name, idp_group, role_name)`。
索引：`ix_group_role_mapping_group(provider_name, idp_group)`（登录时的热路径）。

> **为什么从第一天就带 `provider_name`。** 加列容易，改唯一键要重建索引；
> 多 IdP 是明确会来的需求，先留一个恒为 `default` 的列，比 P1 做一次数据迁移便宜。

**为什么不新建授权表。** 组映射的产物就是
`lakehouse_principal_role_grant` 里的行（`granted_by=IDP`）；再建一张
「组授权表」等于让判定链多一跳，而 `role:<名字>` 候选串已经能表达全部语义。
新增的表只解决「组名 → 角色名」这一层翻译。

### 3.5 存量数据

- 所有既有 `lakehouse_principal` 行：`source` 默认 `LOCAL`，`granted_by` 默认 `MANUAL`；
- 这两个默认值保证**升级后行为逐字不变**：没有 JIT、没有同步、手工授权不受影响；
- DDL 由实体重新生成（`gen-mysql-ddl.sh` / `gen-postgres-ddl.sh`），
  `ddl-auto: validate` 会在升级时直接拦住「加了列却没迁移」的实例。

---

## 4. JIT 供给

### 4.1 流程

```
认证成功（OidcService.verify）
  → principal = claim(principal-claim)
  → find by name
      ├─ 不存在：insert（source=OIDC, external_id=issuer|sub, 无 client_id / secret）
      ├─ 存在且 source=LOCAL：拒绝（principal_conflict）
      ├─ 存在且 source=OIDC 且 external_id 相同：放行
      └─ 存在且 source=OIDC 且 external_id 不同：拒绝
  → 组同步（§5）
  → 写 principal.login 事件
  → 返回 authenticated / principal
```

**JIT 建出来的主体没有凭据。** `client_id` / `secret_hash` 留空，
`credential_rotation_required=false`。理由：它是人，不是机器；要机器凭据应由管理员
显式调「轮换凭据」端点。**不要在 JIT 路径里顺手生成 secret**——那会把一个可登录的人
变成一个可被程序冒用的账号。

### 4.2 幂等

唯一键在 `name` 上，但「先查后插」在并发首登下会两个都查不到。收敛方式是
**捕获唯一键冲突再重读**，而不是加锁：

```java
try {
    repository.saveAndFlush(principal);
} catch (DataIntegrityViolationException e) {
    // 另一个请求先建了：按名字回读，再走 §3.2 的三条判定
    principal = repository.findByName(name).orElseThrow(...);
}
```

> **为什么不用 `SELECT ... FOR UPDATE`。** 行还不存在，锁不住「不存在」；
> 间隙锁在不同隔离级别下行为不同，而且会把登录路径变成串行。唯一键是数据库已经付过钱的
> 互斥原语，冲突概率极低，捕获一次比预锁便宜。

### 4.3 开关与安全默认

| 配置 | 默认 | 理由 |
| --- | --- | --- |
| `oidc.auto-provision` | **`true`** | 关掉就回到「装好进不去」。新建主体**零权限**，本身不构成风险 |
| `oidc.allow-adopt-local` | **`false`** | 见 §3.2，默认拒绝同名接管 |
| `oidc.allowed-email-domains` | 空（不限制） | 多租户 / 对外部署时用来收口，例如 `corp.example.com` |
| `oidc.default-roles` | 空 | 给所有人生成一个基线角色（例如 `readonly`）时要显式配 |

**为什么 `auto-provision` 默认为真也不算危险。** 新建的主体：
没有 `permission_assignment`、没有角色成员、拿不到任何资源。它能做的只有
「让 `lakehouse_principal` 多一行」。真正的风险是**主体表被刷**，
用 `allowed-email-domains` + 登录限速（已有 `LoginAttemptLimiter`）兜住。

### 4.4 失败语义

| 情况 | 行为 |
| --- | --- |
| 建主体时数据库不可用 | **登录失败**（500，带 correlation id）。不能放行一个「认证过了但身份没落库」的人——下一次请求它仍然零权限，而用户会以为是权限问题 |
| 建主体成功、组同步失败 | **登录成功**，角色保持上一次的样子，记 warn + 审计 `result=partial`。理由是同步失败不清空（§5.3），最坏是「旧权限多留一会儿」，不是「突然全空」 |
| 同名冲突 | 401 / 403 + `principal_conflict`，响应里不透露「这个名字已存在」的更多信息（避免枚举） |

---

## 5. 组 → 角色同步

### 5.1 映射与匹配

- 组名来自 `group-claim`（默认 `groups`，通常是字符串数组；单个字符串按一元组处理）；
- **精确匹配**，区分大小写，不 trim、不做前缀/通配（P2 再说）；
- 映射到的 `role_name` 必须是已存在的 principal role；不存在则**跳过并告警**，
  不让登录 500（管理员改错一个名字不该导致全员登不上）；
- 同一组可以映射多个角色；同一角色也可以被多个组映射（取并集）。

### 5.2 同步算法

```
desired   = { m.role_name | g ∈ groups, m = mapping(provider, g), m.enabled }
currentIdp = { g.principal_role_id | g.principal_id = p.id, g.granted_by = 'IDP' }

toAdd    = desired   − currentIdp      → insert(granted_by='IDP')
toRemove = currentIdp − desired        → delete where granted_by='IDP'
// MANUAL 行：永远不读、不删、不参与差集
```

三条不变量：

1. **手工授权不可见**：差集只由 `granted_by=IDP` 的行构成；
2. **幂等**：同样的 groups 跑两次，第二次 `toAdd`/`toRemove` 都为空；
3. **只增删成员关系**，不创建 / 删除 principal role 本身。

### 5.3 三种 `sync-mode` 与「claim 缺失不删」

| 模式 | `groups` 缺失时 | 适用 |
| --- | --- | --- |
| `ON_LOGIN`（默认） | **不动**任何 IDP 角色 | 绝大多数 IdP：`groups` 只在特定 scope/令牌类型里出现 |
| `STRICT` | 视为空集，**清空** IDP 角色 | 合规要求「离开组必须立刻失去权限」，且已确认 IdP 恒定下发 `groups` |
| `OFF` | 不同步 | 灰度 / 排障 |

**默认 `ON_LOGIN` 的理由。** 很多 IdP 的 `groups` 只在
`scope` 含 `groups`、或只在 access_token（不是 id_token）里出现。把「这次没带」
解释成「这个人不在任何组」，会在一次 scope 配错后**削掉所有人的权限**——
而它看起来像「IdP 出问题了」，排查方向完全错。宁可多留一会儿，也不要误删。

> 控制台的 OIDC `scope` 需要显式加 `groups`（Keycloak 还要求 client 的
> group membership mapper 打开）。这一条要写进管理员接入指南，否则默认配置下
> `groups` 根本不会出现。

### 5.4 收敛与漂移

- **同步时机只有登录**。不做定时任务：本期没有「已登录但长期不登录」的收敛需求，
  而定时任务要解决「多副本谁跑」「IdP 分页」「失败重试」三件事，收益不抵。
- **P1 补 `last_sync_at`**：在 `lakehouse_principal` 上加一列，超过 N 天未同步的主体
  在控制台标黄。这是「漂移可见」，不是「自动修」。
- **撤权延迟的上界是「下一次请求」**（§1.2），不是令牌 TTL；但**前提是组同步已经跑过**。
  也就是说：IdP 撤组 → 用户下次登录 → 同步 → 之后每个请求立即生效。
  如果用户一直不登录，撤权不会发生——这是本设计明确的边界，`STRICT` + 定时同步属于 P2。

### 5.5 危险映射

| 情况 | 处置 |
| --- | --- |
| 映射到 `service-admins` 成员 | 保存时**二次确认**（前端红色横幅 + 后端要求 `confirmAdminMapping=true`） |
| 映射到持有 `MANAGE` / `DELETE` 类 access 的角色 | 控制台标红，列出该角色当前带的 access 词 |
| 组名像 `all` / `everyone` / `*` | 提示「这通常意味着全员」，但不拦（合法场景存在） |

**为什么不直接禁止。** 「全员只读」是常见且合理的；禁止会把人逼去手工授权，
反而更不可审计。做法是**让它可见、要一次确认**，而不是假装它不存在。

### 5.6 交换不触发同步

`TokenExchangeService` **不调用** JIT，也不调用组同步。理由：

- 交换不是登录，没有 `groups` claim 可用（新票只有 `sub`）；
- 授权本来就从库里读，交换前后角色集合相同；
- 长作业每小时换一次票，若每次同步一遍，等于把登录路径的开销乘以作业时长。

这条要写进代码注释，否则将来很容易「顺手」加进去。

---

## 6. C 侧收尾

### 6.1 已满足的不变量（不要破坏）

| # | 不变量 | 现有保证 |
| --- | --- | --- |
| 1 | `sub` 不来自请求 | `exchange()` 只从认证结果取 |
| 2 | TTL 不延长 | 固定 `access-token.ttl` |
| 3 | scope 不收窄也不放大 | 只认 `PRINCIPAL_ROLE:ALL` |
| 4 | 父票必须有效 | 走同一条认证链 |
| 5 | **只接受 `access_token` 类型** | `requireAccessTokenType`；`urn:ietf:params:oauth:token-type:jwt` 仍回 400 |

新增任何参数（`audience` / `resource` / `scope` 变体）都必须有对应的提权测试。

### 6.2 `audience` / `resource` 收窄（P1）

现在请求里带 `audience` 会被忽略（不在签名里）。P1 的做法：

- 配了 `access-token.audience` 时，新票写 `aud`；
- 请求带的 `audience` 与之不符 → `invalid_target`（RFC 8693 §2.2.2）；
- 未配置时保持现状（不写 `aud`），**保证 Iceberg 客户端零改动**。

### 6.3 错误可诊断

现状：登录失败在浏览器里只有 401，原因在服务端 debug 日志。
本期补一条**安全的**原因通道：

- 响应里只回分类（`signature` / `issuer` / `audience` / `expired` / `claim_missing`），
  不回具体 claim 值；
- 同时写审计 `principal.login failed`，带 `reason` 与 `correlationId`；
- 运维能用 `correlationId` 在日志里定位到那一次请求。

**为什么不在响应里回 claim 值。** 那等于给攻击者一个「这个 IdP 的令牌长什么样」的探针；
分类已经够定位配置问题了。

### 6.4 引擎配置模板（只给配置，不写代码）

Paimon（`token.provider=oauth2`）与 Iceberg（`rest.auth.type=oauth2`）的续期链已落地
（见 `engine-token-refresh-design.md`）。本期只补两份**可复制**的模板：

```properties
# Spark + Paimon：短票 + 自动交换
spark.sql.catalog.lakehouse.type=paimon
spark.sql.catalog.lakehouse.uri=http://catalog:8080
spark.sql.catalog.lakehouse.warehouse=paimon
spark.sql.catalog.lakehouse.token.provider=oauth2
spark.sql.catalog.lakehouse.token=eyJ...          # 用户 OIDC 票或控制台票
spark.sql.catalog.lakehouse.token.exchange-endpoint=http://catalog:8080/v1/oauth/tokens
```

```properties
# Iceberg：只加 scope，刷新走 RFC 8693
spark.sql.catalog.iceberg.rest.auth.type=oauth2
spark.sql.catalog.iceberg.rest.auth.oauth2.token-endpoint=http://catalog:8080/v1/oauth/tokens
spark.sql.catalog.iceberg.rest.auth.oauth2.scope=PRINCIPAL_ROLE:ALL
```

模板要注明：**引擎拿到的是用户身份的票**，不是平台服务账号；否则「谁跑的都算平台」。

---

## 7. 管理面 API

### 7.1 端点

| Method | Path | 作用 | 权限 |
| --- | --- | --- | --- |
| `GET` | `/api/management/v1/group-mappings` | 列出映射（可按 `provider` / `group` 过滤） | `service-admins` |
| `POST` | `/api/management/v1/group-mappings` | 新建（组 + 角色） | `service-admins` |
| `PUT` | `/api/management/v1/group-mappings/{id}` | 改角色 / 启停 | `service-admins` |
| `DELETE` | `/api/management/v1/group-mappings/{id}` | 删除（不影响已同步的成员行） | `service-admins` |
| `POST` | `/api/management/v1/group-mappings/preview` | 给定 `groups`，回「会得到哪些角色 / 哪些组没映射」 | `service-admins` |
| `POST` | `/api/management/v1/principals/{name}/sync` | 手动重算一个主体的 IDP 角色（**请求体必须带 `groups`**，见下） | `service-admins` |
| `GET` | `/api/management/v1/principals?source=OIDC` | 现有列表端点加一个 `source` 过滤 | 现有判权 |
| `GET` / `PUT` | `/api/management/v1/identity-provider` | P1：IdP 配置从 yml 搬到库 | `service-admins` |

> **为什么权限只挂 `service-admins`。** 身份接入是全局配置，不属于任何 catalog，
> 现有 14 项 access 词表是资源级的，没有合适的位置。P1 若要细分，再加
> `MANAGE_IDENTITY`，并同步「权限点并列不蕴含」那条规矩。
>
> **手动同步为什么必须带 `groups`（实现时定的）。** 服务端不保存「这个人上次登录时在哪些组」：
> 那是一次快照，存下来就多一份会过期的状态，而它唯一的用途是省掉这一次参数。
> 也正因为不保存，这个端点**不会**在缺参数时退化成「按空组重算」——那是撤权，
> 不是同步，方向只有一个且不可逆。管理员的真实场景是「刚加完一条映射，想立刻生效」，
> 而那条映射的组名就在他手上。
>
> **`provisionedBy=jit` 没有单独实现。** 本期 `source=OIDC` 与「JIT 建出来的」是同一批行——
> OIDC 主体只可能由 JIT 产生（管理面创建的主体一律 `LOCAL`）。多一个等价的过滤参数
> 只会让控制台多一个要维护的下拉框。

### 7.2 预览端点

```http
POST /api/management/v1/group-mappings/preview
{"provider":"default","groups":["data-eng","analysts","contractors"]}
```

```json
{
  "desiredRoles": ["analyst", "editor"],
  "unmappedGroups": ["contractors"],
  "unknownRoles": [],
  "warnings": ["group \"contractors\" has no mapping"]
}
```

预览**不写库**，用于控制台在保存前给管理员看效果；它与登录时的算法必须是
**同一份实现**（抽一个纯函数），否则预览与实际会漂移。

### 7.3 审计事件

在 `PRINCIPAL` 类别下新增三个类型（总数以 `AuditEventType` 为准，不在文档里写死）：

| 类型 | 触发 | 关键属性 |
| --- | --- | --- |
| `principal.login` | 每次认证成功 / 失败 | `source=oidc`、`issuer`、`result`、`reason`、`jitCreated` |
| `principal.provisioned` | JIT 建主体 | `externalId`、`issuer` |
| `principal.roles.synced` | 同步完成 | `added` / `removed` 角色名、`mode`、`groupsCount` |

**不发**「每次请求的授权判定」——那是访问日志，不是领域事件。

### 7.4 幂等与并发

- `POST /group-mappings` 撞唯一键 → 409，不回 500；
- 两个管理员同时改同一行 → 用现有 `entity_version` 乐观锁（`AuditedEntity` 之外，
  `PrincipalEntity` 已有 `entity_version`；映射表同样加）；
- `sync` 端点对同一主体并发调用 → 结果相同，允许重复执行（差集算法天然幂等）。

---

## 8. 控制台（对应原型）

### 8.1 五个画面

| # | 画面 | hash | 要点 |
| --- | --- | --- | --- |
| 1 | 登录页 | `#login` | SSO 页签 + **失败诊断**（分类原因，不回 claim 值） |
| 2 | OIDC 配置 | `#idp` | issuer / clientId / audience / claim 映射 / sync-mode / auto-provision；右侧「连通性测试」 |
| 3 | 主体清单 | `#principals` | 来源徽标（OIDC / 本地）、外部标识、最后登录、角色、JIT 标记 |
| 4 | 组映射 | `#groups` | 组 → 角色表、未映射组提示、**同步预览**（当前 → 期望，含 MANUAL 保护） |
| 5 | 审计 | `#audit` | 登录 / 供给 / 同步三类事件，失败带原因 |

### 8.2 关键交互

- **OIDC 配置页的「令牌样例」**：把最近一次登录的 claim 结构脱敏展示
  （`sub` / `email` / `groups` 的**个数**与示例值），让管理员当场确认
  「`group-claim` 填对了没有」——这是最高频的配置错误。
- **组映射页的 sync-mode 切换**要联动预览：切到 `STRICT` 时，
  预览要显示「`groups` 缺失将清空 IDP 角色」这条后果，而不是只改一个单选。
- **主体清单页不提供「新建密码」**。OIDC 主体是登录时自动建的；
  在这里新建会让「这个人是 IdP 来的还是本地建的」失去意义。要建机器主体，走原有主体页。
- **危险映射**（§5.5）在保存时出红色确认，不在列表里静默标红。

### 8.3 不做什么

- 不在控制台管理 IdP 侧的用户 / 组（只读 claim，不写回 IdP）；
- 不做「一键把本地主体转 OIDC」（P2 的显式绑定）；
- 不做主体删除的级联提示以外的事——删主体仍然走现有管理面。

### 8.4 落地位置

原型里的 `#hash` 在控制台是一个真实路由，五个画面摊成**三页 + 两个已有页面**：

| 画面 | 路由 | 组件 | 端点 |
| --- | --- | --- | --- |
| 登录（SSO 页签 + 失败诊断） | `/login` | `LoginView` + `SsoDiagnosis` | `GET /api/console/v1/auth` |
| 回调（失败诊断） | `/auth/callback` | `AuthCallbackView` + `SsoDiagnosis` | `POST /api/catalog/v1/oauth/tokens` |
| OIDC 配置 | `/identity` | `IdentityProviderView` | 三条，只读 |
| 主体清单 | `/principals` | `PrincipalsView`（来源列 / 外部标识 / 最后登录 / 按组同步） | 列表带 `?source=`，同步走 `POST …/principals/{name}/sync` |
| 组映射 | `/group-mappings` | `GroupMappingsView` | CRUD 四条 + `POST …/preview` |
| 审计 | `/identity-audit` | `IdentityAuditView` | `GET /api/console/v1/events`（`category` / `type` 下推） |

**OIDC 配置页为什么只有读。** 这三条端点（配置、探活、claim 样例）里没有一条能改配置：

| 端点 | 用途 | 为什么不能写 |
| --- | --- | --- |
| `GET /identity-provider` | 当前生效配置（15 个字段，不含任何密钥） | 字段来自 yml / ConfigMap；在线可改等于「已登录的人改自己的准入规则」 |
| `POST /identity-provider/probe` | 五步探活：`discovery` / `endpoints` / `jwks` / `mapping` / `provisioning` | 纯读，且**跳过缓存**——登录页要缓存（否则闪），探活要「现在」 |
| `GET /identity-provider/claim-samples` | 最近 20 次登录的 claim **形状**（打码） | 只读替身数据，`processLocal=true` 说明多副本各记各的 |

探活的判据里有两处是刻意的：**配置类步骤不因连接失败而跳过**（IdP 连不上时仍要能看见
「redirect-uri 没配」），`jwks` 依赖发现文档、失败时给 `SKIPPED` 而不是 `FAIL`（那不是它的错）。

**失败分类是纯函数。** `utils/ssoDiagnostics.js` 把服务端 / IdP 的原文映射成
`{code, title, why, action, tone}`，**原文一律保留在 `raw` 里并显示在折叠区**——分类是为了
给出下一步，不是为了把证据删掉。`tone` 只有 `blocked`（要改配置）与 `retry`（再试一次）两档。
登录页与回调页共用这一个函数和同一张卡：两处各写一份文案时，同一个原因会有两种说法，
而照着做的人只会做错。单测在 `tools/sso-tests/`（19 例，`npm run test:sso`）。

**两个不可逆动作都要二次确认。** 组映射保存到 `service-admins` 时服务端回 400 且消息含
`confirmAdminMapping`，前端据此弹红色确认后带 `confirmAdminMapping=true` 重试——判据必须来自
服务端（要读「这个角色是不是 service-admins 持有」），前端自己写一份名单，漂移的方向是
**少确认一次**。手动同步留空同理：服务端不保存「上次登录在哪些组」，留空的自然读法是「不填」。
但它的后果**不是一律撤权**——`ON_LOGIN`（默认）下服务端刻意一个都不删、只补默认角色
并回一个 `skippedReason`，`STRICT` 下才是按「不在任何组」重算。控制台因此把两种后果
都写在确认框里，回执再按 `skippedReason` 区分「刻意跳过」与「跑完了没变化」。

**OIDC 配置页与组映射页分成两页。** 前者改一次要重新部署，后者是日常运维（人来了、组改了），
两者频次差一个量级；混在一页会让「我今天要做什么」变得不清楚。侧边栏因此多一个「身份」分组，
审计页归到已有的「审计」组（它与「事件」是同一张 `audit_event` 表、同一个端点）。

---

## 9. 测试矩阵

| # | 用例 | 断言 |
| --- | --- | --- |
| 1 | 首次 SSO 登录 | 建主体（`source=OIDC`）、无凭据、零权限 → 403 而非 500 |
| 2 | 并发首登（两线程） | 只建一行；两边都拿到同一主体 |
| 3 | 同名 `LOCAL` 主体 | 拒绝，`principal_conflict` |
| 4 | 同名 `OIDC` 但 `external_id` 不同 | 拒绝 |
| 5 | `groups` 缺失 + `ON_LOGIN` | 不删任何 IDP 角色 |
| 6 | `groups` 缺失 + `STRICT` | 清空 IDP 角色，MANUAL 保留 |
| 7 | 组被移出 | 对应 IDP 角色删除；MANUAL 角色不动 |
| 8 | 映射指向不存在的角色 | 跳过 + warn，登录成功 |
| 9 | 映射到 `service-admins` | 未确认时保存 400；确认后成功 |
| 10 | 一个组映射两个角色 | 两个角色都到位 |
| 11 | 交换用 OIDC 票 | `sub` 不变；**不触发** JIT / 同步 |
| 12 | 交换后 `max-session` | 仍按链龄拒绝 |
| 13 | 登录失败原因 | 响应只回分类；审计有 `reason` + `correlationId` |
| 14 | 组名大小写差异 | 精确匹配，不命中（不静默 trim 成命中） |
| 15 | 存量升级 | `source` / `granted_by` 默认值让行为与升级前一致 |

前 10 条是服务端切片测试（H2）；11–12 扩 `TokenExchangeTests`；13–14 走 MockMvc；
15 用生成的两份 DDL 交叉验证默认值。

**落地后的实际分布**（`lakehouse-rest-server/src/test/...`）：

| 用例 | 落在哪个类 |
| --- | --- |
| 1–10、14、15 | `SsoIdentityProvisioningTests`（16 例；含 `groups` 缺失、`STRICT`、未知角色、大小写、停用映射、邮箱域、`auto-provision=false`、并发首登） |
| 9（危险映射）、预览、409、乐观锁、手动同步 | `GroupMappingApiTests`（9 例） |
| 11–12 | 既有 `TokenExchangeTests` / `TokenSessionLimitTests` **未改动**——正因为交换链路一行代码都没改，它们继续有效就是最好的回归 |

> 用例只用「构造出来的 `OidcIdentity` + H2」，不连真实 IdP：要验证的是供给与同步，
> 而验签已由 `OidcServiceTests` 覆盖。真实 IdP 的那一次联调已做成可复现脚本（§10.1），
> 它证明的是另一件事——**真实 Keycloak 的令牌形状与服务端期望的形状一致**。

---

## 10. 分期

| 阶段 | 内容 | 验收 |
| --- | --- | --- |
| **MVP（已落地）** | 5 列 + 1 表；JIT + 组同步（`ON_LOGIN`）；组映射 CRUD + 预览 + 手动同步；4 个审计事件；5 个控制台画面 | §9 的 1–10、13–15 全绿；真实 Keycloak 跑通一次登录→建主体→拿角色→换票（**已跑通**，`scripts/e2e-keycloak-sso.sh` 65 项，见 §10.1） |
| **P1** | IdP 配置入库（`lakehouse_identity_provider`）、多 IdP 按 `iss` 路由、`display_name`、`last_sync_at`、`audience/resource` 收窄 | 两套 IdP 同时接入；旧 yml 配置仍可读（迁移开关） |
| **P2** | SCIM / 定时同步、停用与删除的收敛、组名通配、显式绑定本地主体 | 离职账号在 N 分钟内失去权限 |

**MVP 为什么不含多 IdP。** 多 IdP 的难点不在表，在**认证链要先按未验签的 `iss`
选 provider、再验签**（否则拿 A 的密钥验 B 的票）。这是一次认证链重构，
和 JIT 混在一起做会让两边都测不透。

### 10.1 真实 Keycloak 联调（已跑通，可复现）

单测里的 OIDC 令牌是**自己拿本地 RSA 密钥签的**：它能证明「验签分支写得对」，
证明不了「真实 IdP 签出来的令牌长什么样」。而后者才是配置错的来源——`aud` 到底
是客户端还是资源、`groups` 是不是数组、组名带不带前导斜杠，每一项配错的表现**一模一样**：
登录成功、一个角色都没有、每个页面 403。因此这一步看代码没用，只能对真实 IdP 发真实请求。

`scripts/e2e-keycloak-sso.sh` 把这次联调固化下来（**65 项断言**，任一项不一致即非 0 退出）。
它做两件事：先用 Keycloak 管理 API **重建**一个 realm（客户端 / 组映射器 / 组 / 用户），
清理服务端上一轮的痕迹，然后逐段验证下面这条链。

```bash
# 1) Keycloak 26.x（管理账号 admin/admin）
docker run -d --name kc-dev -p 8081:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  quay.io/keycloak/keycloak:26.7.4 start-dev

# 2) 服务端：认证 + 授权 + OIDC 全开（完整命令见脚本头部注释）
#    两个参数不显然，理由在上面那个脚本里：--server.port 与 principals[admin]=root
java -jar lakehouse-rest-server-*.jar --server.port=8080 --spring.profiles.active=h2 \
  --lakehouse.rest.auth.enabled=true --lakehouse.rest.authorization.enabled=true \
  --lakehouse.rest.auth.console.oidc.enabled=true \
  --lakehouse.rest.auth.console.oidc.issuer-uri=http://127.0.0.1:8081/realms/lakehouse \
  --lakehouse.rest.auth.console.oidc.client-id=paimon-console \
  --lakehouse.rest.auth.console.oidc.principal-claim=preferred_username \
  --lakehouse.rest.auth.console.oidc.audience=paimon-console

# 3) 跑
./scripts/e2e-keycloak-sso.sh
```

脚本的七段与它证明的事：

| 段 | 内容 |
| --- | --- |
| 1 | 在 Keycloak 里建 realm / **公开客户端（强制 S256）** / **组映射器** / 组 / 用户 |
| 2 | 服务端侧：`admin→root` 拿到服务管理员、建 principal role 与组映射、**预览与登录用同一段计算** |
| 3 | 浏览器侧：不打开浏览器，但完整走**授权码 + PKCE**（取登录页表单 → 提交 → 拿 `code` → 换令牌），断言 `state` 原样返回 |
| 4 | 服务端认领：带 `id_token` 请求 `GET /api/console/v1/auth`，断言 JIT 建出主体、`external_id = issuer\|sub`、`last_login_at` 被刷新 |
| 5 | 组 → 角色：主体名下出现 `analyst`；审计里 `principal.login` / `principal.provisioned` / `principal.roles_synced` 三类都在 |
| 6 | 换票：`token-exchange` 换出的票 `sub` 不变、`iss` 变成本服务端，拿它能读到 `/v1/paimon/databases` |
| 7 | 再登录一次：`jitCreated=false`、主体清单仍只有一行（幂等） |

**四个只有真 IdP 才暴露的结论**（都写进了脚本的注释与断言里）：

1. **id_token 与 access_token 不是一个受众。** Keycloak 的 id_token `aud` 是 client_id，
   而 access_token 是 `account`。控制台优先取 id_token（`src/api/oidc.js` 的注释）不是偏好，
   而是配了 `audience` 之后**唯一能过的那条路**：脚本第 6 段专门断言拿 access_token 换票
   会被 400 `invalid_grant` 拒掉。反过来说，`audience` 留空时两者都能过——
   那等于让「发给别的应用的票」也能拿来访问本服务端。
2. **组名可能带前导斜杠。** 组映射器的 `full.path` 关掉产出 `sso-analysts`，打开就是
   `/sso-analysts`——同一个 realm 下各建一个 mapper 实测过两种都成立。而映射表的组名匹配
   是**精确**的（不 trim、不加前缀），差这一个斜杠就是一个角色都同步不过去，
   而现象与「没配映射」完全一样。脚本把 `full.path=false` 显式写死：不是因为它是默认，
   而是因为这个开关能把「同一个人在 IdP 与映射表里的名字」变成两个值。
3. **`SERVER_PORT` 会抢走端口。** 如果环境里有这个变量，即使 yml 写了 `server.port: 8080`，
   Tomcat 也会去绑它。而 Keycloak 侧登记的 `redirect_uri` 是写死的 8080，
   两边对不上时浏览器只会看到一句 `invalid_redirect_uri`。启动命令里显式给 `--server.port`。
4. **控制台密码账号默认映射成 `admin`，而 `admin` 不是服务管理员。**
   `service-admins` 默认只有 `root`。配置 `principals[admin]=root` 之前，
   校验脚本拿到的令牌能登录、但每个管理端点 403——很像「授权没生效」。

**与单测的分工**（三者不重叠，缺一个都会漏）：`OidcServiceTests` 证明验签分支
（算法白名单 / iss / aud / 时间窗 / 未知 kid 重取）；`SsoIdentityProvisioningTests`
证明供给的准入判定（同名本地主体、`external_id` 不一致、邮箱域）；本脚本证明
**真实 IdP 的令牌形状与服务端期望一致**，以及这四步连起来是通的。

> 脚本会删掉并重建 realm，只该指向本地开发用的 Keycloak；服务端侧它会自己清理上一轮的
> 角色 / 映射 / 主体，所以对着同一个 h2 实例可以反复跑。

---

## 11. 风险与已知边界

| 风险 | 说明 / 处置 |
| --- | --- |
| **组映射漂移** | 只在登录时同步。长期不登录的人，撤权不会发生。P1 用 `last_sync_at` 让它可见，P2 用定时同步收敛 |
| **JIT 刷主体** | 用 `allowed-email-domains` + 登录限速；新建主体零权限，刷出来也没有数据面能力 |
| **IdP 侧组可自助加入** | 若 IdP 允许用户自己加入组，组映射就成了自助提权。这是 IdP 配置问题，接入指南要写明「映射到高权角色的组必须由管理员维护」 |
| **改名 / 换 issuer** | 主体名与 `external_id` 都不自动迁移；表现为「新主体零权限」。用 `display_name` 与审计降低排查成本 |
| **无撤销** | 令牌仍然不能单点撤销（自包含 JWT 的固有代价）；但**角色变更下一次请求生效**，这是比「等 TTL」更好的性质 |
| **`groups` 不在 id_token 里** | 默认 `ON_LOGIN` 不会误删；控制台 scope 要加 `groups`，并在接入页给出检查项 |
| **管理员误配** | 映射预览 + 危险映射二次确认 + 未知角色跳过（不是 500） |

---

## 12. 与现有文档的关系

| 文档 | 关系 |
| --- | --- |
| [`console-auth.md`](console-auth.md) | 认证链与三种登录方式的现状；本文只补「登录之后」 |
| [`authorization.md`](authorization.md) | 授权判定；本文不改判定，只补「谁该有哪些角色」 |
| [`engine-token-refresh-design.md`](engine-token-refresh-design.md) | 交换的实现与引擎侧续期；本文不重复 |
| [`data-model.md`](data-model.md) | 现有表结构；本文的 5 列 + 1 表在落地时同步进去 |
| [`event-framework.md`](event-framework.md) | 审计事件框架；本文新增 3 个 `PRINCIPAL` 类型 |

> **落地时的顺序**：先加列与表 → 再写 JIT + 同步（纯服务层，可单测）→
> 再接登录路径 → 最后做控制台。反过来会让「页面有了、数据没有」的状态持续很久。

