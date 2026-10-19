# 目录事件框架（对标 Apache Polaris Event Listener）

> 状态：**服务端、控制台事件页、增量拉取、按监听器的类别/类型订阅已实现**。
> 对标对象：Polaris 1.8 的 event listener（1.5 起多监听器，1.5 起 `enabled-event-types` /
> `enabled-event-categories`）。
> 取舍：Polaris 在每次操作前后各发一个事件（BEFORE/AFTER 成对），监听器可以参与校验；
> 本框架只发 **AFTER**（变更成功后的事实记录），不做 BEFORE 校验——校验已经有了自己的
> 归口（服务层的参数校验与 RBAC 判定），把「事前拦截」塞进事件框架等于给它加第二套
> 责任不明的钩子。我们的框架回答的是「**谁在什么时候对目录做了什么**」。

---

## 1. 领域事件清单（10 类 38 型）

事件按**类别**（{@code CatalogEventCategory}）分组，类别是监听器订阅的粒度（§5）；
每个类别下是若干个**类型**（{@code CatalogEventType}），类型是事件流里的契约字符串。
「表」与「视图」的两种存储格式（Paimon / Iceberg）在同一个类型、同一股流里，
靠属性 `format` 区分。

### 1.1 与 Polaris 的映射

Polaris 的 `PolarisEventType` 有 15 个类别、一百多个常量，是 **CRUD × BEFORE/AFTER**
的请求级遥测——`BEFORE_GET_TABLE` / `AFTER_LIST_TABLES` 这类读事件占了一半。
照抄会得到两个后果：事件表变成访问日志（量级从「变更次数」变成「请求数」），
以及每条业务路径上多一次无意义的发布。因此映射规则是：
**取 Polaris 的 AFTER + 变更，丢掉 BEFORE 与读路径。**

| Polaris 类别 | 我们 | 说明 |
| --- | --- | --- |
| `CATALOG` | ✅ `CATALOG` | create / update / delete 三条 AFTER |
| `NAMESPACE` | ✅ `NAMESPACE` | create / update-properties / drop；`LIST` / `LOAD` / `EXISTS` 不收 |
| `TABLE` | ✅ `TABLE` | create / register / drop / rename / update（= 提交）；`LOAD` / `REFRESH` 不收 |
| `VIEW` | ✅ `VIEW` | create / replace（= 更新）/ drop / rename；`LOAD` 不收 |
| `POLICY` | ✅ `POLICY` | 我们只有优化策略（upsert），因此一条 `optimization.policy_updated`，没有 attach/detach |
| `PRINCIPAL` | ✅ `PRINCIPAL` | 增删改 + 凭据轮换/重置 + 角色授予/收回 |
| `PRINCIPAL_ROLE` | ✅ `PRINCIPAL_ROLE` | 增删改（它只是分组，没有「绑定 catalog role」这一步了） |
| `CATALOG_ROLE` | ❌ | catalog role 整层移除，没有对应物 |
| `CREDENTIAL` | ❌ | `LOAD_CREDENTIALS` 是读路径：每次下发都发事件等于把审计表变成凭据访问日志 |
| `TRANSACTION` / `NOTIFICATION` / `CONFIG` / `TASK_EXECUTION` / `RATE_LIMITING` | ❌ | 我们没有这些对象（优化批次的对齐见 §1.3） |
| （无） | ✅ `GRANT` | Polaris 把授权挂在 `CATALOG_ROLE` 下；我们只有官方那一套三元组赋权，审计上更该单列一类 |
| （无） | ✅ `OPTIMIZATION` | 本地扩展：优化策略与批次终态（Polaris 无对应物） |

### 1.2 类型清单

**CATALOG**（3）

| wire 值 | 含义 | 发布点 |
| --- | --- | --- |
| `catalog.created` | 新建 catalog | `ManagementCatalogService.create` |
| `catalog.updated` | 改配置（属性 / 存储） | `ManagementCatalogService.update` |
| `catalog.dropped` | 删 catalog（含级联范围） | `ManagementCatalogService.delete` |

**NAMESPACE**（3）

| wire 值 | 含义 | 发布点 |
| --- | --- | --- |
| `namespace.created` | 建库 / namespace | Paimon `DatabaseService.create`、Iceberg `IcebergNamespaceService.create` |
| `namespace.updated` | 改属性 | 两侧的 `alter` / `updateProperties`（**一个键都没变时不发**） |
| `namespace.dropped` | 删库 | 两侧的 `drop`（Paimon 侧级联删表；Iceberg 侧非空拒绝） |

**TABLE**（6）

| wire 值 | 含义 | 发布点 |
| --- | --- | --- |
| `table.created` | 建表 | Paimon `TableService.create`、Iceberg `IcebergTableService.create` |
| `table.registered` | 注册已存在的表（数据不归我们管） | 两侧的 `register` |
| `table.dropped` | 删表 | 两侧的 `drop`（Iceberg 属性带 `purged`） |
| `table.unregistered` | 只从目录摘掉登记，不动仓库 | Iceberg `unregister`（Paimon 侧没有这个动作） |
| `table.renamed` | 改名 / 换库 | 两侧的 `rename`（目标在 target，来源在属性 `from`） |
| `table.committed` | 元数据指针推进 | `commit`；Paimon 的 `alter` / `rollback-schema` 带 `change` 属性 |

**VIEW**（4）

| wire 值 | 含义 | 发布点 |
| --- | --- | --- |
| `view.created` | 建视图 | Paimon `ViewService.create`、Iceberg `IcebergViewService.create` |
| `view.updated` | 改定义 | 两侧的 `alter` / `commit`（空 changes / 空 updates 不发） |
| `view.dropped` | 删视图 | 两侧的 `drop` |
| `view.renamed` | 改名 / 换库 | 两侧的 `rename` |

**GRANT**（2）

| wire 值 | 含义 | 发布点 |
| --- | --- | --- |
| `grant.granted` | 授予权限 | `PermissionManagementService.grant`（幂等命中不发） |
| `grant.revoked` | 撤销权限 | `PermissionManagementService.revoke`（**没删掉任何一条时不发**） |

收权方一律是 principal 字符串，属性里带 `grantee` / `resourceType` / `database` /
`object` / `access`。`role:<名>` 形式的收权方表示「这条赋权由该分组的成员共享」。

**PRINCIPAL**（7）

| wire 值 | 含义 |
| --- | --- |
| `principal.created` / `principal.updated` / `principal.dropped` | 主体的增删改 |
| `principal.credentials_rotated` / `principal.credentials_reset` | 轮换 / 重置凭据（**不含 clientId 与密钥**） |
| `principal.role_granted` / `principal.role_revoked` | 把主体角色授予 / 收回主体 |

**PRINCIPAL_ROLE**（3）

| wire 值 | 含义 |
| --- | --- |
| `principal_role.created` / `principal_role.updated` / `principal_role.dropped` | 主体角色的增删改 |

**POLICY**（1）

| wire 值 | 含义 | 发布点 |
| --- | --- | --- |
| `optimization.policy_updated` | 表策略或 catalog 级默认设置变化 | `TableOptimizationService.updatePolicy`（`scope=table`）/ `updateSettings`（`scope=catalog`） |

**OPTIMIZATION**（4）

| wire 值 | 对应批次终态 |
| --- | --- |
| `optimization.job_succeeded` | `SUCCESS` |
| `optimization.job_partially_succeeded` | `PARTIAL_SUCCESS` |
| `optimization.job_failed` | `FAILED`（提交失败、超时、全部表失败） |
| `optimization.job_canceled` | `CANCELED` |

按终态分型而不是「一条 `job_finished` + `state` 属性」，是因为消费方最常写的一条规则是
「失败就告警」——分型之后那是订阅一个类型，不用再解析报文。要「所有终态」也有现成的类别。

**主体与角色是 realm 级资源，不属于任何 catalog**：这些事件的 `catalog` 列留空串，
控制台在「全部 Catalog」下能看见、按目录筛时看不见。

### 1.3 不发的事件与理由

- **BEFORE 事件**：见文首。校验失败本来就不该出现在事件流里。
- **读路径**（`load` / `list` / `HEAD` / metrics 回报 / 凭据下发）：事件表会退化成访问日志。
  访问日志有它自己的层（access log），不该塞进审计表。
- **幂等空操作**：撤销不存在的授权、空 `updates` 的提交、CAS 冲突、`removals` 全是
  「本来就没有」的键——这些调用没有让任何持久状态改变，进事件流就是幻影事件。
- **级联删除的逐级事件**：删 catalog 会级联删掉库与表，但只发一条 `catalog.dropped`
  （属性带删除数量）。逐级补发会让「一次删目录」在事件流里膨胀成几百条，淹没真正要看的那条。
  同理，Paimon 删库级联删表也只发 `namespace.dropped`。
- **优化批次的中间状态**（PENDING / SUBMITTING / RUNNING）：批次列表页已经在轮询这些状态；
  事件流只记终态，避免一条优化在审计里留下四条噪音。
- **`table.refreshed`**（Polaris 有）：它是读操作，且是最高频的那一个。

## 2. `audit_event` 表

一行一个事件，**只插入、从不更新**——事件是事实记录，「改写历史」本身就是错误信号。
因此它不继承 `AuditedEntity`（那套 `updatedBy` / `updatedAt` 的更新语义在这里没有意义），
列集是自己的：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint 自增 PK | **增量拉取的游标**。不用 `event_id` 做游标：UUID 无序，`ORDER BY` 它分页不稳定 |
| `event_id` | varchar(64) | 事件的 UUID，幂等键与跨系统对账用 |
| `type` | varchar(64) | 事件类型（§1.2 的 wire 值），有索引 |
| `category` | varchar(32) | 事件类别，**由 type 决定的冗余列**：让「只看权限变更」走索引，而不是在 SQL 里列一长串 wire 值做 `IN`。同步点只有 `AuditEventEntity.of` 一处 |
| `catalog` | varchar(255) | catalog 名（prefix）；realm 级事件为空串。与 `id` 组成联合索引 |
| `principal` | varchar(255) | 触发者（经 `AuditPrincipal.of` 归一化） |
| `request_id` | varchar(64) | 请求关联 ID，可空（调度路径没有 HTTP 请求） |
| `occurred_at` | bigint | epoch 毫秒 |
| `target_type` | varchar(32) | `TABLE` / `NAMESPACE` / `CATALOG` / `VIEW` / `GRANT` / `PRINCIPAL` / `ROLE` / `JOB` |
| `target` | varchar(1024) | 展示用定位符（`db.table`、授权资源、批次 id、主体名） |
| `attributes_json` | text | 自由属性（`format`、`from`/`to`、`snapshotId`、`state`…），`ObjectMap` 转换器 |

属性集合刻意**开放**（自由 JSON）而不是逐列建：两种表格式的同名事件携带的属性不同
（Paimon 提交带 `snapshotId`，Iceberg 带 `updates` 数），逐列建模会让「哪列属于哪种格式」
变成第二张对照表要维护。事件框架的契约是「九个固定列 + 一段开放属性」。

索引三条：`(catalog, id)` 支撑按目录增量拉、`(category, id)` 支撑按类别增量拉、
`type` 单列供排障时按类型查。

## 3. 发布与监听链路

```
Service 方法（事务内，变更成功后）
    │  CatalogEventPublisher.publish(type, catalog, targetType, target, attributes)
    │  —— 补齐 eventId / occurredAt / principal / requestId，缺了不猜
    │  —— 按 EventListenerSettings 逐个 listener 判「是否启用 / 是否订阅」
    ▼
CatalogEventListener（Spring 收集全部 bean，逐个分发）
    ├── AuditEventPersistListener（db）：写 audit_event —— 与业务同事务，回滚即不落
    └── LoggingEventListener（log）：一行 INFO 摘要进应用日志
        （未来的 Kafka / Webhook 监听器实现同一接口即可接入，发布侧零改动）
```

四条刻意定下的规则：

1. **同步分发，且每个 listener 单独 try/catch。** 监听器失败只记 ERROR，绝不让它把
   已经成功的业务变更拖成 500——事件是业务的旁观者，不是参与者。DB listener 与业务
   同事务是唯一例外，那正是想要的性质（变更回滚，事件也不存在）。
2. **发布点在 Service 层，不在 Controller 层。** 同一变更可能有多个入口（Paimon 的
   `commit` 就有三个重载），入口各自发事件必然重复或遗漏；服务方法内部收口处只有一个。
3. **只在真正发生变更后发布。** 见 §1.3 的空操作清单。事件流里没有空操作，
   消费方才敢按事件做增量。
4. **不做异步、不做缓冲。** 每个变更一次 insert，成本可控；先上 Kafka/Webhook 的那
   些场景将来由各自的 listener 自己决定异步策略（接口上写明「实现方自行保证顺序与
   重试」），发布侧不背这层复杂度。

### request id

`RequestContext` 增加 requestId（独立的 ThreadLocal，与主体名同一生命周期）：
`BearerAuthInterceptor` 在 `preHandle` 里取 `X-Request-Id` 请求头，没有就生成 UUID，
`afterCompletion` 里一并清理。事件、审计字段与日志可以按它串起来；客户端不带这个头
时服务端自己生成，因此这一列**永远有值**（调度路径除外）。

## 4. 增量拉取契约

```
GET /api/console/v1/events?since=<id>&catalog=<name>&category=<name>&limit=100
```

- 响应：`{ events: [...升序...], nextCursor, hasMore }`；`nextCursor` 取最后一条的 `id`。
- **首次不带 `since`**：返回当前最新的 `limit` 条（内部先倒序取再反转成升序），
  游标从「现在」开始——事件页要的是「接下来发生了什么」，不是回放全部历史。
- `since` 给了：返回 `id > since` 的升序列表，`hasMore = 条数打满 limit`。
- `catalog` / `category` 过滤可选、可同时给。`category` 大小写不敏感，
  **未知取值回 400 而不是空页**——空页会被读成「这一类还没发生过」，是最难排查的静默失败。
- 查询用 `JpaSpecificationExecutor` 动态拼谓词：目录 × 类别 × 游标是组合条件，
  派生方法要写八条，加维度还得改接口。
- 鉴权走控制台门禁（`/api/console/v1/**` 的 Bearer 拦截），不做 catalog 级 RBAC——
  与 `/meta`、文件浏览同一口径，理由见 `ConsoleDtos` 的类注释。

控制台「事件」页（`EventsView.vue`）按这个契约做**游标增量拉**：本地保存游标，
「刷新」只拉新增；换目录 / 换类别或点「重置游标」才重置游标。其余页面的全量刷新
保持不变——那些页面要的是「当前状态」，只有事件页要的是「发生了什么」，
两种语义不该共用一种刷新策略。

## 5. 配置：启用哪些监听器、各自订阅什么

配置形状**照搬 Polaris**（只把根前缀换成本项目）：

```yaml
lakehouse:
  rest:
    event-listener:
      types: db,log
      log:
        enabled-event-categories: TABLE,GRANT
      db:
        enabled-event-types: catalog.dropped
```

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `lakehouse.rest.event-listener.types` | 空 = 全部 | 启用的监听器名（`CatalogEventListener.name()`，现有 `db` / `log`） |
| `lakehouse.rest.event-listener.<name>.enabled-event-categories` | 空 = 全部 | 按类别订阅，取值见 `CatalogEventCategory` |
| `lakehouse.rest.event-listener.<name>.enabled-event-types` | 空 = 全部 | 按具体类型订阅，取值是 wire 值 |

语义（与 Polaris 1.8 一致，见其 Configuration Reference）：

- **什么都不配 = 全订阅**。这是「默认安全」的方向：漏配导致少记审计，比多记更难发现。
- **类别与类型同时配 = 并集**：`enabled-event-categories=TABLE` +
  `enabled-event-types=catalog.dropped` 表示「表这一类 + 删目录这一条」。
- **启动期校验**：`types` 里写了没注册的监听器、类别名拼错、wire 值写错，都直接让应用
  起不来并指出是哪个键哪个值。配置错误的后果是「该记的没记」，运行期才发现通常已经丢了审计。
- **为什么不用 `@ConfigurationProperties`**：Spring 的 Map 绑定要求动态键落在一个固定节点下
  （`...listeners.<name>.x`），而 Polaris 的形状是监听器名直接挂在 `event-listener` 前缀下、
  与 `types` 平级——同一前缀里既有标量又有动态子键，绑定器表达不了。因此用 `Environment`
  显式读，键名与 Polaris 完全对齐。

> 上一版的 `lakehouse.rest.event.db-enabled` / `log-enabled` 已并入 `types`：
> `types=log` 就等价于「只要日志监听器」。两个开关并存会让「到底关没关」有两个答案。

Kafka / Webhook 接入时新增一个 `CatalogEventListener` 实现并给它起个名字，
配置里把名字写进 `types`、按需配订阅范围即可，发布侧与其余监听器零改动。

## 6. 边界与不做的事

1. **不做事件订阅 API**（Polaris 的 events 拉取端点、webhook 订阅管理）：控制台的增量拉取
   就是唯一的消费方，需要第二个消费方时再把「游标 + 过滤」从 console API 抽成正式契约。
2. **不做 BEFORE 事件**：见文首。校验失败本来就不该出现在事件流里。
3. **不做事件级的访问控制**：能读事件页的人能读全部事件（含主体与授权信息）。
   这与控制台其他页面的口径一致；真要细分，该在 `/api/console/v1/events` 前加一层
   「按 catalog 过滤」的授权，而不是在事件表上加 ACL。
4. **多实例部署下的游标**：`id` 是单库自增，事件顺序即写入顺序；多实例并发时同毫秒
   事件的先后由自增号定，不保证与业务提交顺序一致（乐观并发下本来就没有全序）。
5. **事件不外发**：DB + 日志只在本机。把事件推到外部系统属于 listener 的实现细节，
   框架不规定投递语义（至少一次 / 至多一次由实现方声明）。
6. **表保留策略**：事件表无自动清理，量级 = 变更次数（而非请求数），增长慢；
   需要时用优化任务的孤儿文件思路另做清理任务，不塞进本框架。
