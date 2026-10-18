# 目录事件框架（对标 Apache Polaris Event Listener）

> 状态：**服务端、控制台事件页与增量拉取已实现**。
> 对标对象：Polaris 的事件框架（1.0 实验、1.5 起多监听器 + 按事件类型过滤）。
> 取舍：Polaris 在每次操作前后各发一个事件（BEFORE/AFTER 两类），监听器可以参与校验；
> 本框架只发 **AFTER**（变更成功后的事实记录），不做 BEFORE 校验——校验已经有了自己的
> 归口（服务层的参数校验与 RBAC 判定），把「事前拦截」塞进事件框架等于给它加第二套
> 责任不明的钩子。我们的框架回答的是「**谁在什么时候对目录做了什么**」。

---

## 1. 领域事件清单（7 类）

只收录「控制台与运维真正会问」的变更，不做全量 CDC。判据：这条事件值得一个人去翻——
要么是资源动了（表）、要么是权限动了（授权）、要么是钱和资源动了（优化）。

| 事件类型（wire 值） | 含义 | Paimon 侧触发点 | Iceberg 侧触发点 |
| --- | --- | --- | --- |
| `table.created` | 表进入目录 | `POST .../databases/{db}/tables`（`TableService.create`）；`register` 同样发本事件，属性带 `registered=true` | `POST .../namespaces/{ns}/tables`（`IcebergTableService.create`）；`register` 同上 |
| `table.dropped` | 表离开目录 | `DELETE .../tables/{t}`（`TableService.drop`） | `DELETE .../tables/{t}`（`drop`，属性带 `purged`）；`unregister` 同发本事件，属性带 `unregistered=true` |
| `table.renamed` | 表改名/换库 | `POST /tables/rename`（`TableService.rename`） | 同路径（`IcebergTableService.rename`） |
| `table.committed` | 元数据指针推进 | `POST .../tables/{t}/commit`；`alter` 与 `rollback-schema` 同发本事件，属性带 `change=alter/rollback-schema` | `POST .../tables/{t}`（update 端点，`commit`）；空 updates 与 CAS 失败不发 |
| `grant.changed` | 直授授权变化 | `POST .../permissions/grant` / `revoke`（`PermissionManagementService`，revoke 只在确实删掉一条时发） | 同一套端点（权限管理一页覆盖两种格式，授权是 catalog 级实体） |
| `optimization.policy_changed` | 优化策略变化 | `PUT/POST` 表策略（`TableOptimizationService.updatePolicy`，属性带 `scope=table`）与 catalog 默认设置（`updateSettings`，`scope=catalog`） | 同一套服务，两格式共用 |
| `optimization.job_finished` | 优化批次到达终态 | `OptimizationJobService` 的 `finishFromSubmitter` / `markSubmitFailed` / `markTimedOut` / `cancel` | 同一套服务（任务表不分格式） |

不发的事件，与理由：

- **namespace / database 的创建与删除**：目录浏览页每次都全量拉库列表，缺少它不产生
  错觉；先留着，等出现真实的消费方再加。
- **凭据下发、load 表**：读路径。每次查询都发事件会把审计表变成请求日志，而请求日志
  应该由访问日志（access log）承担。
- **Iceberg 的 metrics 回报**：它是数据面写入，不是目录结构变化；且频率高。

## 2. `audit_event` 表

一行一个事件，**只插入、从不更新**——事件是事实记录，「改写历史」本身就是错误信号。
因此它不继承 `AuditedEntity`（那套 `updatedBy` / `updatedAt` 的更新语义在这里没有意义），
列集是自己的：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint 自增 PK | **增量拉取的游标**。不用 `event_id` 做游标：UUID 无序，`ORDER BY` 它分页不稳定 |
| `event_id` | varchar(64) | 事件的 UUID，幂等键与跨系统对账用 |
| `type` | varchar(64) | 事件类型（§1 的 wire 值），有索引 |
| `catalog` | varchar(255) | catalog 名（prefix），与 `id` 组成联合索引，支撑按目录增量拉 |
| `principal` | varchar(255) | 触发者（经 `AuditPrincipal.of` 归一化） |
| `request_id` | varchar(64) | 请求关联 ID，可空（调度路径没有 HTTP 请求） |
| `occurred_at` | bigint | epoch 毫秒 |
| `target_type` | varchar(32) | `TABLE` / `NAMESPACE` / `CATALOG` / `GRANT` / `JOB` |
| `target` | varchar(1024) | 展示用定位符（`db.table`、授权资源、批次 id） |
| `attributes_json` | text | 自由属性（`format`、`from`/`to`、`snapshotId`、`state`…），`ObjectMap` 转换器 |

属性集合刻意**开放**（自由 JSON）而不是逐列建：两种表格式的同名事件携带的属性不同
（Paimon 提交带 `snapshotId`，Iceberg 带 `updates` 数），逐列建模会让「哪列属于哪种格式」
变成第二张对照表要维护。事件框架的契约是「七个固定列 + 一段开放属性」。

## 3. 发布与监听链路

```
Service 方法（事务内，变更成功后）
    │  CatalogEventPublisher.publish(type, catalog, targetType, target, attributes)
    │  —— 补齐 eventId / occurredAt / principal / requestId，缺了不猜
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
3. **只在真正发生变更后发布。** Iceberg 空 `updates` 直接返回当前状态、CAS 冲突返回
   `success=false`、revoke 没找到对应赋权——这些都不发事件。事件流里没有「空操作」，
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
GET /api/console/v1/events?since=<id>&catalog=<name>&limit=100
```

- 响应：`{ events: [...升序...], nextCursor, hasMore }`；`nextCursor` 取最后一条的 `id`。
- **首次不带 `since`**：返回当前最新的 `limit` 条（内部先倒序取再反转成升序），
  游标从「现在」开始——事件页要的是「接下来发生了什么」，不是回放全部历史。
- `since` 给了：返回 `id > since` 的升序列表，`hasMore = 条数打满 limit`。
- `catalog` 过滤可选；两种表格式的事件都在同一张表、同一个流里，用 catalog 列区分，
  控制台的下拉筛选只是查询参数，不是两套 API。
- 鉴权走控制台门禁（`/api/console/v1/**` 的 Bearer 拦截），不做 catalog 级 RBAC——
  与 `/meta`、文件浏览同一口径，理由见 `ConsoleDtos` 的类注释。

控制台「事件」页（`EventsView.vue`）按这个契约做**游标增量拉**：本地保存游标，
「刷新」只拉新增；换目录筛选或点「从头回放」才重置游标。其余页面的全量刷新
保持不变——那些页面要的是「当前状态」，只有事件页要的是「发生了什么」，
两种语义不该共用一种刷新策略。

## 5. 配置

| 配置项 | 默认 | 说明 |
| --- | --- | --- |
| `paimon.rest.event.db-enabled` | `true` | DB listener：事件落 `audit_event` |
| `paimon.rest.event.log-enabled` | `true` | 日志 listener：一行摘要进应用日志 |

两个都关掉时 `CatalogEventPublisher` 仍在（发布本身零成本），只是没有 listener 接。
Kafka / Webhook 接入时新增 listener 实现类 + 自己的配置开关，不改本框架。

## 6. 边界与不做的事

1. **不做事件订阅 API**（Polaris 的 events 拉取端点、webhook 订阅）：控制台的增量拉取
   就是唯一的消费方，需要第二个消费方时再把「游标 + 过滤」从 console API 抽成正式契约。
2. **不做 BEFORE 事件**：见文首。校验失败本来就不该出现在事件流里。
3. **多实例部署下的游标**：`id` 是单库自增，事件顺序即写入顺序；多实例并发时同毫秒
   事件的先后由自增号定，不保证与业务提交顺序一致（乐观并发下本来就没有全序）。
4. **事件不外发**：DB + 日志只在本机。把事件推到外部系统属于 listener 的实现细节，
   框架不规定投递语义（至少一次 / 至多一次由实现方声明）。
5. **表保留策略**：事件表无自动清理，量级 = 变更次数（而非请求数），增长慢；
   需要时用优化任务的孤儿文件思路另做清理任务，不塞进本框架。
