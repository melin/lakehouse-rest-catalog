# 指标（Metrics）设计：Polaris 的 telemetry 能借哪一半

> 状态：**设计稿，未实现**（本文只给方案，不含代码）。  
> 对标：Apache Polaris 的 telemetry（Micrometer + Prometheus，1.7 的管理端口 8182 `/q/metrics`），  
> 以及 main 分支里 API / 持久化 / 缓存三层的指标和 Iceberg 用量上报。  
> 范围：服务端五个模块（core / storage / protocol-paimon / protocol-iceberg / server）。  
> 不含：控制台前端埋点、分布式追踪（trace）、日志规范——那是另外三件事。  
> 结论：**Polaris 值得抄的是「三层拆账 + 高基数标签默认关 + SLO 桶按需开」这套骨架，  
> 不是它那一百多个 `@Timed` 注解。** 我们的端点只有 130 个、租户维度只有 catalog，  
> 照抄注解会得到一份随代码漂移的清单；用切面 + 指标名常量表，才能让「文档里的指标名」  
> 和「代码里的指标名」由同一个真源生成。

---

## 0. 能力边界（先看这张表）

| 能力                            | 本设计       | 说明                                       |
| ----------------------------- | --------- | ---------------------------------------- |
| HTTP 层指标（QPS / 延迟 / 状态码）      | ✅         | 用框架自带的 `http.server.requests`，不自己造（§4.2） |
| 每个 API 操作的延迟 / 异常             | ✅         | 一个切面，自动覆盖全部 `@RestController`（§7.3）      |
| 授权 / 认证失败计数                   | ✅         | 在现有两个拦截器里记账（§7.4）                        |
| catalog / 表提交 / 事件 / 优化任务指标   | ✅         | 域指标，带 `catalog` / `format` 等低基数标签（§4.3）  |
| 指标端点                          | ✅         | `/actuator/prometheus`，独立管理端口（§8）        |
| 公共标签（环境 / 区域 / 应用名）           | ✅         | `lakehouse.rest.metrics.tags.*`（§6）      |
| 按 catalog 打标签                 | ✅ **有上限** | 默认 50，超出折叠成 `__other__`（§5.2）            |
| 按 principal 打标签               | ✅ **默认关** | 与 Polaris 同默认；打开前先算基数（§5.3）              |
| 按表名 / namespace 打标签           | ❌ **禁止**  | 这是把指标变成日志；审计查事件表（§5.1）                   |
| 延迟直方图 / SLO 桶                 | ✅ **默认关** | 与 Polaris 同默认，按需配置（§5.4）                 |
| Iceberg 客户端上报的 scan/commit 报告 | ✅ **P2**  | 端点已存在，只是当前只校验不落库（§4.3.7）                 |
| 分布式追踪（OTel）                   | ❌ 本文不涉及   | Polaris 也是两条独立链路，trace 默认关               |
| 审计（谁在什么时候改了什么）                | ❌ 不在指标里   | 已有事件框架（9 类 33 型）+ `audit_event` 表        |

---

## 1. Polaris 的 metrics 到底有什么（事实基线）

### 1.1 采集与导出链路

```
业务代码 / 框架 binder
   → Micrometer MeterRegistry（进程内）
   → Prometheus registry（文本格式 0.0.4）
   → Quarkus 管理接口 :8182 /q/metrics   ← 与业务端口分开
   → Prometheus 抓取（Helm 里有独立 management Service + ServiceMonitor）
```

三个开关默认就是开的（`runtime/defaults/src/main/resources/application.properties`）：

```properties
quarkus.management.enabled=true
quarkus.micrometer.enabled=true
quarkus.micrometer.export.prometheus.enabled=true
```

要点：**指标走管理端口，不混在业务端口上**。业务端口只服务 catalog / 管理 API，  
管理端口只服务 `/q/*`（metrics、health）。这条在 k8s 里意味着两个 Service、两条网络策略。

### 1.2 指标目录：Polaris 有四个来源

| 来源        | 指标形态                                                                                       | 标签                                                          | 谁产生                                                               |
| --------- | ------------------------------------------------------------------------------------------ | ----------------------------------------------------------- | ----------------------------------------------------------------- |
| HTTP 服务器  | `http_server_requests_seconds`（Timer）                                                      | `method` `uri` `status` `outcome` `exception`，可选 `realm_id` | Quarkus 的 JAX-RS/MVC binder，框架自动                                  |
| 每个 API 操作 | `polaris_<baseName>_<nickname>_seconds`（Timer）                                             | `class` `method` `exception`，可选 `realm_id` `principal`      | OpenAPI 生成模板统一注入 `@Timed("polaris.<baseName>.<nickname>")`        |
| 持久化后端     | `polaris_persistence_nosql_<op>_seconds` + `<op>_total`                                    | 方法级，无业务维度                                                   | `ObservingBackend` 装饰器，每个操作 `@Timed(histogram=true)` + `@Counted` |
| 缓存        | `cache_capacity`、`cache_capacity_admitted`、`cache_weight_reported`、`cache_rejected_weight` | cache 名                                                     | Caffeine 后端自己注册 Gauge / DistributionSummary                       |

API 层那个指标名值得抄一遍：模板里写的是 `@Timed("{{metricsPrefix}}.{{baseName}}.{{nickname}}")`，  
导出到 Prometheus 就是 **`polaris_principals_getPrincipal_seconds`**（点换下划线、Timer 加 `_seconds`）。  
测试 `MetricsTestBase` 直接断言这个名字和六个标签，说明它是**契约**，不是实现细节。

一个细节：`realm_id` 与 `principal` 用 `@MeterTag(key=..., expression=...)` 挂在方法参数上；  
**关闭时标签仍然存在，值是空字符串**（测试断言 `Map.entry("realm_id", "")`）。  
这是 Micrometer `@MeterTag` 的行为，不是 Polaris 的选择——我们在 §5.3 会改成「关闭就不加这个键」。

### 1.3 标签与基数控制：Polaris 最值得抄的一页

| 机制                                                          | 默认                       | 说明                                            |
| ----------------------------------------------------------- | ------------------------ | --------------------------------------------- |
| `polaris.metrics.tags.*`                                    | 只有 `application=Polaris` | 任意公共标签，如 `environment` / `region` / `service` |
| `polaris.metrics.realm-id-tag.enable-in-api-metrics`        | `false`                  | API 指标上加 `realm_id`                           |
| `polaris.metrics.realm-id-tag.enable-in-http-metrics`       | `false`                  | HTTP 指标上加 `realm_id`；**单独一个开关**，因为 HTTP 基数更高  |
| `polaris.metrics.realm-id-tag.http-metrics-max-cardinality` | `100`                    | HTTP 指标里最多记 100 个不同的 realm                    |
| 超限行为                                                        | 只告警一次，**此后不再记录 HTTP 指标** | `OnlyOnceLoggingDenyMeterFilter`，不是静默截断       |

超限的选择很关键：它宁可**丢掉整条 HTTP 指标**，也不让标签无限增长把进程拖死。  
代价是「从某一刻起没有 HTTP 指标」，所以必须配一条告警（Polaris 只写日志）。  
我们的做法在 §5.2 改成「折叠成 `__other__`」——保留总量，牺牲长尾的可分辨性，运维上更好用。

### 1.4 直方图与 SLO 桶

默认只导出 Timer 的 `count` / `sum` / `max`：能算平均值和最坏值，**算不出跨实例可聚合的 p95/p99**  
（每个实例的 max 取平均没有意义）。要百分位就得导桶：

```properties
polaris.metrics.http-server-requests.histogram-slos=10ms,50ms,100ms,1s,5s
```

实现是一个 `MeterFilter`：只对 `http.server.requests` 的 Timer 注入  
`DistributionStatisticConfig.serviceLevelObjectives(...)`，其它 meter 原样放过。  
注意这是**显式桶**（SLO），不是 `percentiles`——前者可跨实例聚合，后者不行。

### 1.5 另一套「metrics」：Iceberg 客户端上报的用量报告

Polaris 有两个同名不同物：

|    | 运维指标（§1.1–1.4）      | Iceberg 用量报告（本节）                              |
| -- | ------------------- | --------------------------------------------- |
| 方向 | 服务端 → 监控系统          | 客户端 → 服务端                                     |
| 内容 | 延迟、QPS、异常           | `ScanReport` / `CommitReport`（扫了多少文件、提交了多少数据） |
| 形态 | Micrometer meter    | JSON 报告，SPI 消费                                |
| 配置 | `polaris.metrics.*` | `polaris.iceberg-metrics.reporting.type`      |

SPI 长这样：`IcebergMetricsReporter.reportMetric(MetricsReportEnvelope)`，  
信封里带 `catalogName` / `catalogId` / `table` / `tableId` / `MetricType`（SCAN | COMMIT）/  
原始报告 / 接收时间。实现按 `@Identifier` 选择，配置值是 `default` / `no-op` / `logging` / `persisting`；  
`persisting` 把报告写进 JDBC 的两张表（`*_metrics_report`），表在主 schema 里。

**这一套不是监控，是用量分析。** 混进 Micrometer 会得到「以表名为标签」的指标——正好是 §5.1 禁止的事。  
所以我们的设计把它单列为 P2（§4.3.7），复用现有端点，但不进指标目录。

### 1.6 部署配套

- Helm 里 `service-mgmt.yaml` 单独暴露管理端口；`servicemonitor.yaml` 用 Prometheus Operator 抓  
  `port: management`、`path: /q/metrics`。
- 生产开关示例：`serviceMonitor.enabled=true` + `interval`（默认 30s）。
- 指标端口**不鉴权**：靠网络策略限制；业务端口才走 OAuth。

### 1.7 Polaris 没做的（别以为是漏了）

- 没有「catalog 数量 / 表数量」这类**库存 gauge**：这类数要查库，放指标里既不准也贵。
- 没有按表名 / namespace / principal 的标签（只有默认关闭的 `principal`）。
- 没有把事件流当指标：事件是审计，量级是「变更次数」；指标是聚合，量级是「请求次数」。
- 没有内置 dashboard / alert 规则：只给 ServiceMonitor，面板各家用各自的。

---

## 2. 我们现状与差距

| 能力                       | 现状                                                           | 差距                             |
| ------------------------ | ------------------------------------------------------------ | ------------------------------ |
| Micrometer / Actuator 依赖 | **没有**（`grep micrometer` 在 5 个 POM 里为 0）                     | 要加两个依赖（§7.1）                   |
| 指标端点                     | 无                                                            | 加管理端口 + `/actuator/prometheus` |
| HTTP 指标                  | 无                                                            | 加 Actuator 即有                  |
| API 操作指标                 | 无                                                            | 切面补（§7.3）                      |
| 授权 / 认证可观测性              | 只有异常与日志                                                      | 计数器（§7.4）                      |
| 事件                       | 9 类 33 型，`CatalogEventPublisher` 已收口                         | 在发布器里加计数（§7.5）                 |
| 优化任务                     | `OptimizationJobService` / `OptimizationBatchService` 状态在 DB | 加计数 / 时长 / 运行中 gauge（§7.6）     |
| Iceberg 上报端点             | `POST .../tables/{t}/metrics` 已实现，**只校验 + 打日志**              | 加 SPI 与计数（§7.7，P2）             |
| 持久化指标                    | 无                                                            | 先用 Hikari 自带，P2 再谈方法级（§4.2）    |
| 标签基数控制                   | 无                                                            | 本设计第一次引入（§5）                   |
| 文档与代码一致性                 | 已有 `verify-*.py` 文化                                          | 指标名也纳入校验（§10.3）                |

---

## 3. 设计原则

1. **一个指标只回答一个问题。** 延迟看 Timer，次数看 Counter，库存看 Gauge；  
   不拿一个 Timer 的 `count` 同时当「请求数」和「成功数」（成功的分母要用标签拆开）。
2. **标签值必须来自有限集合。** 允许：`format`、`result`、`operation`、`resource_type`、`access`、  
   事件 `category`/`type`、有上限的 `catalog`。禁止：表名、namespace、路径、请求 id、SQL。
3. **框架已经给的不要重造。** HTTP 用 Boot 的 `http.server.requests`，连接池用 Hikari 的，  
   JVM 用 Micrometer 的 binder。我们只加**业务语义**那一层。
4. **开关默认取「安全侧」。** 高基数标签默认关，SLO 桶默认关（有成本），  
   指标本身默认开（不采集就没法排障）。
5. **指标名是契约。** 名字集中在一个常量类里，文档从它生成/校验；改名字等于改契约，要走评审。

---

## 4. 指标目录 v1

### 4.1 命名规范

Micrometer 里用点分层，Prometheus 导出自动转下划线：

| 层       | Micrometer 名                          | Prometheus 名                          |
| ------- | ------------------------------------- | ------------------------------------- |
| 域指标     | `lakehouse.<domain>.<subject>`        | `lakehouse_<domain>_<subject>`        |
| Counter | `lakehouse.catalog.operations`        | `lakehouse_catalog_operations_total`  |
| Timer   | `lakehouse.table.commit`              | `lakehouse_table_commit_seconds`      |
| Gauge   | `lakehouse.optimization.jobs.running` | `lakehouse_optimization_jobs_running` |

规则：

- 单位进名字：时间一律 `_seconds`（Micrometer 的 Timer 自动加，不要自己写 `_ms`），  
  字节用 `_bytes`，次数用 `_total`（Counter 自动加）。
- 标签键一律 snake_case（与 Polaris 的 `realm_id` 一致），值一律小写下划线  
  （`format=iceberg`、`result=unknown_snapshot`），只有 `catalog` / `principal` 保留原值。
- **不加 `lakehouse_` 前缀给框架指标**：`http.server.requests` 就叫这个名字，  
  改名会让所有现成面板失效（Polaris 也没改）。

### 4.2 框架自带（不写代码，只开开关）

| 指标                                                       | 类型    | 标签                                            | 用途                                                                    |
| -------------------------------------------------------- | ----- | --------------------------------------------- | --------------------------------------------------------------------- |
| `http.server.requests`                                   | Timer | `method` `uri` `status` `outcome` `exception` | 全站 QPS、延迟、错误率；`uri` 是模板（`/v1/{prefix}/namespaces/{namespace}`），不是真实路径 |
| `hikaricp.connections.*`                                 | Gauge | `pool`                                        | 连接池活跃 / 空闲 / 等待                                                       |
| `jvm.memory.used` / `jvm.gc.pause` / `process.cpu.usage` | 多种    | —                                             | Micrometer binder，排查「是不是 GC」                                          |

### 4.3 我们的域指标

下表的「标签」列即**全部**标签，实现时不得私自加维度（加维度要改本节 + 基数预算表）。

#### 4.3.1 API 操作

| Micrometer 名             | 类型    | 标签                                                                                                   | 用途                                   |
| ------------------------ | ----- | ---------------------------------------------------------------------------------------------------- | ------------------------------------ |
| `lakehouse.api.requests` | Timer | `class`（控制器简单类名）`method` `outcome`（`success`/`client_error`/`server_error`）`exception`（简单类名或 `none`） | 「哪个操作慢 / 哪个操作在抛异常」，与 `uri` 解耦，改名不断指标 |

> 与 Polaris 的差别：它用 FQCN 做 `class` 标签，我们用简单类名（值更短、跨模块移动不炸）；  
> 它把 `exception` 与状态码分开，我们额外加了 `outcome`，这样「4xx 算不算错」在 PromQL 里一眼可辨。

#### 4.3.2 catalog 写操作

| Micrometer 名                   | 类型      | 标签                                                                                                                  | 用途          |
| ------------------------------ | ------- | ------------------------------------------------------------------------------------------------------------------- | ----------- |
| `lakehouse.catalog.operations` | Counter | `catalog` `format`（`paimon`/`iceberg`）`operation`（`create`/`update`/`delete`）`result`（`success`/`conflict`/`error`） | 管理面变更速率与失败率 |

#### 4.3.3 表提交

| Micrometer 名             | 类型    | 标签                                              | 用途                                      |
| ------------------------ | ----- | ----------------------------------------------- | --------------------------------------- |
| `lakehouse.table.commit` | Timer | `format` `result`（`success`/`conflict`/`error`） | 提交延迟与冲突率；**不加 catalog**（提交量最大的维度，先保住基数） |

#### 4.3.4 授权 / 认证

| Micrometer 名                        | 类型      | 标签                                                                       | 用途              |
| ----------------------------------- | ------- | ------------------------------------------------------------------------ | --------------- |
| `lakehouse.authorization.decisions` | Counter | `resource_type`（8 种）`access`（12 项）`result`（`allowed`/`denied`）           | 拒绝率突增＝权限配错或有人在扫 |
| `lakehouse.authentication.failures` | Counter | `reason`（`missing_token`/`invalid_token`/`expired`/`insufficient_scope`） | 令牌问题分类          |

> `resource_type` × `access` 理论上 96 组合，实际合法组合约 30（`Access.allowedFor` 已限死），  
> 加上 `result` 最多 60 条序列——比按 principal 打标签便宜三个数量级。

#### 4.3.5 事件

| Micrometer 名                         | 类型      | 标签                          | 用途                           |
| ------------------------------------ | ------- | --------------------------- | ---------------------------- |
| `lakehouse.events.published`         | Counter | `category`（9 种）`type`（33 种） | 「变更在发生吗」；控制台事件页的写入速率         |
| `lakehouse.events.listener.failures` | Counter | `listener` `category`       | 某个监听器坏了（发布器已逐监听器兜底，但坏了自己不会喊） |

#### 4.3.6 优化任务

| Micrometer 名                          | 类型      | 标签                                                                                                           | 用途                                                                         |
| ------------------------------------- | ------- | ------------------------------------------------------------------------------------------------------------ | -------------------------------------------------------------------------- |
| `lakehouse.optimization.jobs`         | Counter | `action`（`compact`/`expire`/`orphan`）`result`（`submitted`/`succeeded`/`partial_success`/`failed`/`canceled`） | 提交动作 + 终态分布；四个终态与 `OptimizationStatus.Job` 一一对应（`submitted` 不是状态，是提交成功那一刻） |
| `lakehouse.optimization.job.duration` | Timer   | `action` `result`                                                                                            | 端到端时长（从 `created_at` 到终态，不只是 k8s 启动）                                       |
| `lakehouse.optimization.jobs.running` | Gauge   | `action`                                                                                                     | 运行中任务数（从 DB 计数，定时刷新，见 §7.6）                                                |

#### 4.3.7 Iceberg 用量报告（P2）

| Micrometer 名                        | 类型      | 标签                                                                               | 用途                                         |
| ----------------------------------- | ------- | -------------------------------------------------------------------------------- | ------------------------------------------ |
| `lakehouse.iceberg.metrics.reports` | Counter | `report_type`（`scan`/`commit`）`result`（`accepted`/`unknown_snapshot`/`rejected`） | 客户端上报量；`unknown_snapshot` 即「有人绕开本服务端写」的告警源 |

报告正文不进指标（那是 §1.5 的 SPI，落日志或落库），指标只记「收了几份、几份可疑」。


### 4.4 基数预算

单实例序列数上限按 **5000** 算（Prometheus 侧单 target 的常见舒适区）。按上表估算：

| 指标 | 组合上限 | 说明 |
| --- | --- | --- |
| `http.server.requests` | ~300 | 130 个端点 × 少量状态码 |
| `lakehouse.api.requests` | ~200 | 130 个方法 × 2 outcome × 常见异常 |
| `lakehouse.authorization.decisions` | ~60 | 见 4.3.4 |
| `lakehouse.catalog.operations` | 50×2×3×2 = 600 | 最坏情况；`catalog` 有 50 上限 |
| `lakehouse.table.commit` | 2×3 = 6 | — |
| `lakehouse.events.*` | 33 + 9 | — |
| `lakehouse.optimization.*` | 3×5（jobs）+ 3×5（duration）+ 3（gauge）≈ 35 | 动作 × 终态 |
| **合计** | **< 1500** | 留三倍余量给 label 组合的自然增长 |

新增指标的 PR 必须在本表加一行并给出估算；超过 3000 的组合要重新设计（先降维度，而不是调上限）。

---

## 5. 标签规则（硬约束）

### 5.1 黑名单

以下值**永远不能**出现在标签里，看到就是 bug：

- 表名 / namespace / 路径 / warehouse 位置（基数无界，且泄露业务信息）；
- principal 名 / 令牌 / 请求 id / 客户端 IP（同上，且涉及隐私）；
- 异常 message（只允许异常类名）；
- HTTP 的原始 `uri`（必须用模板；Boot 默认已经是模板，别关掉）。

理由不是「怕贵」一条：指标是**长期保留**的时序数据，一旦写进去就删不掉；
而审计需求已经由事件表承担，两者不要互相污染。

### 5.2 `catalog` 标签：允许，但有闸门

`catalog` 是我们要保留的业务维度（多目录部署时，出问题第一句话是「哪个 catalog」）。
控制方式与 Polaris 的 realm 不同——**折叠而不是丢弃**：

| 项 | 取值 |
| --- | --- |
| 开关 | `lakehouse.rest.metrics.catalog-tag.enabled`，默认 `true` |
| 上限 | `catalog-tag.max-cardinality`，默认 `50` |
| 超限 | 新出现的 catalog 值统一写成 `__other__`；记一条 WARN（只记一次），并给 `lakehouse.catalog.tag.overflow` 计数（内部诊断用，标签只有 `__other__`） |
| 实现 | `MeterFilter.map(id -> ...)`，只处理 `lakehouse.*` 且带 `catalog` 标签的 meter |

折叠而不是丢弃的好处：总量、成功率、拒绝率这些聚合仍然正确，只是长尾合并；
Polaris 那种「停止记录」在故障时最需要数据，反而没了。

### 5.3 `principal` 标签：默认关

| 项 | 取值 |
| --- | --- |
| 开关 | `lakehouse.rest.metrics.principal-tag.enabled`，默认 `false` |
| 上限 | `principal-tag.max-cardinality`，默认 `100`（开了才生效） |
| 只作用于 | `lakehouse.api.requests`，**不作用于** HTTP 与域指标 |
| 关闭时的行为 | 标签键**不出现**（与 Polaris 的空字符串不同；空值会白白多一条时间线） |

打开前先算一笔账：principal 数量 ≈ 用户数 + 服务账号数。超过 100 时，
「谁慢」这个问题应该去 trace / 访问日志回答，不是指标。

### 5.4 直方图：默认不给桶

| 项 | 取值 |
| --- | --- |
| 配置 | `lakehouse.rest.metrics.http.histogram-slos`，默认空 |
| 生效范围 | 只对 `http.server.requests` 的 Timer 注入 SLO |
| 建议值 | `10ms,50ms,100ms,500ms,1s,5s`（与 Polaris 文档示例同量级，加了 500ms） |
| 不给桶时 | 只有 `count`/`sum`/`max`；`avg` 可算，p95/p99 不可算 |

`lakehouse.table.commit` 与 `lakehouse.optimization.job.duration` 是**业务关键延迟**，
建议直接用 `publishPercentileHistogram()`（Micrometer 默认桶），不走 SLO 配置——
它们的量级（秒到分钟）与 HTTP 不同，共用一套桶只会浪费。

---

## 6. 配置

全部挂在现有前缀下（`RestServerProperties` 是 `lakehouse.rest`）：

```yaml
lakehouse:
  rest:
    metrics:
      enabled: true                 # 关掉只停我们的域指标，框架指标由 Actuator 自己管
      tags:                         # 公共标签，进每一条 meter
        application: lakehouse-rest
        environment: prod
      api:
        enabled: true               # 切面是否生效
      catalog-tag:
        enabled: true
        max-cardinality: 50
      principal-tag:
        enabled: false
        max-cardinality: 100
      http:
        histogram-slos: 10ms,50ms,100ms,500ms,1s,5s   # 留空 = 不给桶
```

| 键 | 默认 | 类型 | 说明 |
| --- | --- | --- | --- |
| `lakehouse.rest.metrics.enabled` | `true` | bool | 域指标总开关；`false` 时切面与计数器变成空实现 |
| `lakehouse.rest.metrics.tags.*` | `application=lakehouse-rest` | map | 公共标签，值不能含空格 |
| `lakehouse.rest.metrics.api.enabled` | `true` | bool | 是否注册 `lakehouse.api.requests` 切面 |
| `lakehouse.rest.metrics.catalog-tag.enabled` | `true` | bool | §5.2 |
| `lakehouse.rest.metrics.catalog-tag.max-cardinality` | `50` | int ≥ 1 | §5.2 |
| `lakehouse.rest.metrics.principal-tag.enabled` | `false` | bool | §5.3 |
| `lakehouse.rest.metrics.principal-tag.max-cardinality` | `100` | int ≥ 1 | §5.3 |
| `lakehouse.rest.metrics.http.histogram-slos` | 空 | duration list | §5.4 |

校验规则（`@Validated`，与 `RestServerProperties` 现有风格一致）：
`max-cardinality` 为 0 或负数 → **启动失败**，不是运行时静默降级。
理由：一个写错的上限会让折叠逻辑把所有 catalog 都写成 `__other__`，指标看起来正常但全是废的。

---

## 7. 实现落点

### 7.1 依赖与装配

`lakehouse-rest-server/pom.xml` 加两个（版本由 Boot BOM 管，不写 `<version>`）：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

只加在 **server**：core / storage / 两个协议面都不该依赖 Actuator——
它们要的只是 `MeterRegistry` 接口，由 Spring 注入。这样 `-protocol-iceberg` 的单元测试
可以继续不启动容器，用 `SimpleMeterRegistry` 就能测。

### 7.2 类与职责

| 类 | 模块 | 职责 |
| --- | --- | --- |
| `MetricsProperties` | core/config | 绑定 §6 的键；`@Validated` |
| `MetricsNames` | core/metrics | **指标名与标签键的唯一真源**（`public static final String`），文档校验脚本读它 |
| `MetricsConfiguration` | core/config | `MeterRegistryCustomizer` 加公共标签；注册 §5.2 / §5.4 两个 `MeterFilter`；`@ConditionalOnProperty` 挂 `enabled` |
| `ApiMetricsAspect` | server/metrics | `@Around` 切 `@RestController`，产出 `lakehouse.api.requests` |
| `AuthMetrics` | core/config | 给两个拦截器用的薄封装（`counter(...).increment()`），避免拦截器直接依赖 Micrometer API |
| `EventMetrics` | core/event | 在 `CatalogEventPublisher.publish` 与监听器兜底处计数 |
| `OptimizationMetrics` | server/service | 任务终态计数、时长、运行中 gauge |
| `IcebergReportMetrics` | protocol-iceberg | 上报端点计数（P2） |

`MetricsNames` 是关键设计：指标名散落在各处时，「文档写的」和「代码发的」必然漂移
（本仓已经吃过一次亏——校验项总数 37 vs 38）。集中常量 + 校验脚本（§10.3）把这类漂移变成构建失败。

### 7.3 API 切面（替代 Polaris 的逐端点 `@Timed`）

```java
@Aspect
@Component
@ConditionalOnProperty(prefix = "lakehouse.rest.metrics.api", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ApiMetricsAspect {

    private final MeterRegistry registry;

    @Around("@within(org.springframework.web.bind.annotation.RestController)")
    public Object time(ProceedingJoinPoint pjp) throws Throwable {
        Timer.Sample sample = Timer.start(registry);
        String outcome = "success";
        String exception = "none";
        try {
            return pjp.proceed();
        } catch (Throwable t) {
            exception = t.getClass().getSimpleName();
            outcome = isClientError(t) ? "client_error" : "server_error";
            throw t;
        } finally {
            sample.stop(Timer.builder(MetricsNames.API_REQUESTS)
                    .tag("class", pjp.getSignature().getDeclaringType().getSimpleName())
                    .tag("method", pjp.getSignature().getName())
                    .tag("outcome", outcome)
                    .tag("exception", exception)
                    .register(registry));
        }
    }
}
```

为什么不用 `@Timed` 注解逐端点加：
- 130 个端点，注解会漏、会随重构漂移，且只能靠人 review；
- 切面对**新加的控制器自动生效**，这正是我们想要的「加端点不用记得加指标」；
- 端点级指标与 `http.server.requests` 的 `uri` 有重叠，切面只补 `class`/`method`/`exception` 三个维度。

`client_error` 的判定：`ApiException` 带 4xx 状态、`MethodArgumentNotValidException`、
`HttpMessageNotReadableException` 等 → `client_error`；其余 → `server_error`。
判定表放进 `MetricsNames` 旁边的 `OutcomeClassifier`，单测直接覆盖这张表。

### 7.4 授权 / 认证

- `BearerAuthInterceptor`（core/config）：四个 `reason` 分支各加一次 `lakehouse.authentication.failures`。
  现状是抛 `ApiException.unauthorized(...)`，在抛之前记账。
- `AuthorizationInterceptor`（core/config）：`preHandle` 成功＝`allowed`；
  `ApiException.forbidden` 与「未登记规则」＝`denied`。这里能拿到
  `requirement.resourceType()` 与 `requirement.access()`，正好是标签值。
- **管理 API 的判权不经过这个拦截器**（在控制器里判），因此那部分要在
  `PermissionManagementService` / 各控制器的 `requireCatalog` 调用点补同样的计数——
  实现时用一个 `AuthorizationService` 内部的钩子统一收口，别在控制器里散着加。

### 7.5 事件

`CatalogEventPublisher.publish` 已收口了「什么算事件」，计数放这里最准：

```java
registry.counter(MetricsNames.EVENTS_PUBLISHED,
        "category", type.category().name().toLowerCase(),
        "type", type.wire()).increment();
```

`category` 是枚举名（`CATALOG`…），`type` 是 wire 值（`catalog.created`…）。
监听器兜底处（catch 块）加 `lakehouse.events.listener.failures`，标签 `listener` 用
`listener.getClass().getSimpleName()`。

### 7.6 优化任务

- 提交：`OptimizationDispatcher` 调 `OptimizationJobService.markSubmitted(jobId, ...)` 成功后
  `lakehouse.optimization.jobs{action,result=submitted}`。（`createJob` 只落库、还没提交，
  计数放这里会把「排上了但没发出去」算成已提交。）
- 终态：`OptimizationJobService` 里三处写终态的位置各记一次（`OptimizationStatus.Job`）：
  `finishFromSubmitter`（`SUCCESS` / `PARTIAL_SUCCESS` / `FAILED`）、`markSubmitFailed` 与
  `markTimedOut`（`FAILED`）、`cancel`（`CANCELED`）。标签值 `succeeded` / `partial_success` /
  `failed` / `canceled`，同时用 `Timer.builder(...).tag("action", ...)` 记录从 `created_at`
  到终态的时长。**`skipped` 不是批次状态**——跳过算成功（没有该做的事不等于失败），别再拆一个标签值。
- 运行中：`@Scheduled(fixedDelay = 30s)` 查一次未终态批次
  （`select actions from lakehouse_optimization_job where status_value in ('PENDING','SUBMITTING','RUNNING')`，
  对应 `OptimizationStatus.Job.active()`），按 `actions` 字段（逗号分隔）拆分后每个动作各 +1，
  更新 `Gauge`。**不要**用 `registry.gauge(collection)` 直接给一个会被 GC 的集合——
  这是 Micrometer 最常见的坑。

### 7.7 Iceberg 用量报告（P2）

现有 `IcebergTableService#reportMetrics` 已经做了两件有价值的事：校验 `report-type`、
用 `snapshot-id` 判断「是不是绕开本服务端写的」。P2 把它拆成 SPI：

```java
public interface IcebergMetricsReporter {
    void report(MetricsReportEnvelope envelope);
}
```

| 实现 | `lakehouse.rest.iceberg-metrics.reporting.type` | 行为 |
| --- | --- | --- |
| `NoOpIcebergMetricsReporter` | `no-op` | 丢弃（当前行为） |
| `LoggingIcebergMetricsReporter` | `logging` | DEBUG 一行摘要（当前行为） |
| `PersistingIcebergMetricsReporter` | `persisting` | 落 `lakehouse_iceberg_metrics_report`（P2 才建表） |

与 Polaris 的差别：它默认 `default`（= logging），我们默认 `no-op`——
在本服务端，这个端点的主要价值是**发现绕写**，日志已经能覆盖；落库要先算清楚保留期。

无论开哪个实现，`lakehouse.iceberg.metrics.reports{report_type,result}` 都要计数；
`result=unknown_snapshot` 的那条建议直接配告警（§9）。

---

## 8. 暴露与部署

### 8.1 Spring Boot 侧

```yaml
management:
  server:
    port: 8081                    # 与业务端口分开
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  endpoint:
    health:
      probes:
        enabled: true             # k8s liveness/readiness
```

- 业务端口不暴露 `/actuator/**`（`management.server.port` 设了就不会挂在业务端口）；
- Prometheus 端点是 `text/plain;version=0.0.4`，`/actuator/prometheus`；
- **不鉴权**：管理端口只在内网，靠 NetworkPolicy 限制来源；不要把 8081 放进 Ingress。

### 8.2 Kubernetes

管理端口单独一个 Service（与 Polaris 的 `service-mgmt` 同形）：

```yaml
apiVersion: v1
kind: Service
metadata:
  name: lakehouse-rest-mgmt
spec:
  selector:
    app: lakehouse-rest
  ports:
    - name: management
      port: 8081
      targetPort: 8081
```

ServiceMonitor：

```yaml
apiVersion: monitoring.coreos.com/v1
kind: ServiceMonitor
metadata:
  name: lakehouse-rest
spec:
  selector:
    matchLabels:
      app: lakehouse-rest
  endpoints:
    - port: management
      path: /actuator/prometheus
      interval: 30s
      metricRelabelings:
        # 只保留需要的系列，避免把 JVM 全量指标灌进长期存储
        - sourceLabels: [__name__]
          regex: 'lakehouse_.*|http_server_requests_seconds.*|hikaricp_.*'
          action: keep
```


### 8.3 抓取与保留

- 抓取间隔 30s：够用，且 `rate()` 在 4 个点上有稳定结果。
- 域指标保留 30 天，`http_server_requests` 保留 15 天（量最大、排障窗口最短）。
- 多实例部署时，**不要**在 Prometheus 侧对 Counter 直接 `sum` 后算比率——
  先 `rate()` 再 `sum()`（Counter 重启会归零，直接 sum 会得到锯齿）。

---

## 9. 告警与看板（示例）

### 9.1 建议告警

| 告警 | PromQL（示意） | 阈值 | 为什么 |
| --- | --- | --- | --- |
| 提交延迟恶化 | `histogram_quantile(0.95, sum by (le) (rate(lakehouse_table_commit_seconds_bucket[5m])))` | > 2s 持续 10m | 客户端会超时重试，重试又加剧 |
| 授权拒绝率突增 | `sum(rate(lakehouse_authorization_decisions_total{result="denied"}[5m])) / sum(rate(lakehouse_authorization_decisions_total[5m]))` | > 5% | 权限配错或在被扫 |
| 优化任务连续失败 | `sum(rate(lakehouse_optimization_jobs_total{result="failed"}[15m]))` | > 0 | 失败会静默堆积在 DB |
| 有人绕写 | `sum(rate(lakehouse_iceberg_metrics_reports_total{result="unknown_snapshot"}[10m]))` | > 0 | 绕过授权/审计/优化 |
| 连接池排队 | `hikaricp_connections_pending` | > 0 持续 5m | 数据库侧即将成为瓶颈 |
| 指标本身失效 | `absent(http_server_requests_seconds_count)` | — | 抓取断了要有人知道 |

### 9.2 看板四块

1. **RED**：QPS（`rate(http_server_requests_seconds_count)`）、错误率（`outcome` 拆分）、
   p95/p99（SLO 桶 + `histogram_quantile`）。
2. **操作面**：`lakehouse.api.requests` 按 `class`/`method` 的 top-N 慢操作。
3. **域变更**：catalog 操作、表提交、事件发布速率——回答「系统在忙什么」。
4. **优化**：提交/成功/失败速率 + 运行中任务数 + 端到端时长。

### 9.3 原型：控制台的「监控」页

看板落在**控制台**而不是 Grafana——Grafana 的告警规则与面板导出照旧（§9.1 的 PromQL 原样可用），
控制台这一页的价值是「少一次跳转」：排障的人本来就在控制台里，为看一眼拒绝率再去开 Grafana
这个动作不会发生。原型：`outputs/metrics-console-mockup.html`（可交互：时间范围、深浅色、
「抓取中断」降级形态），静态截图 `outputs/metrics-console-mockup.png`。

页面自上而下五排，每个面板都钉死到指标名（实现时不允许出现目录之外的指标）：

| 面板 | 指标 / PromQL（示意） | 对应章节 |
| --- | --- | --- |
| KPI：请求速率 | `sum(rate(http_server_requests_seconds_count[1m]))` | §4.2 |
| KPI：HTTP p95 | `histogram_quantile(0.95, …_bucket[5m])`，SLO 500ms | §4.2 + §5.4 |
| KPI：错误率 | `outcome` 拆分（`client_error` / `server_error`），不按状态码 | §7.3 |
| KPI：表提交 p95 | `lakehouse_table_commit_seconds`（业务关键延迟，独立百分位直方图） | §4.3.3 + §5.4 |
| 请求速率与结果 | 速率（左轴）+ 错误率（右轴），事故时间窗画底色带 | §9.2 第 1 块 |
| 延迟分位 | p50 / p95 / p99 三线 + SLO 参考线 | §9.2 第 1 块 |
| SLO 桶分布 | 6 个桶的累计占比 | §5.4 |
| 最慢的操作 | `topk(5, …)` 按 `class` + `method` | §9.2 第 2 块 |
| 域变更速率 | `catalog.operations` / `table.commit` / `events.published` 三行小折线 | §9.2 第 3 块 |
| 授权与认证 | `authorization.decisions` 的 allowed/denied + `authentication.failures` 按 `reason` | §4.3.4 |
| 优化任务 | 四个终态分布 + `jobs.running` gauge + `job.duration` p95 | §4.3.6 |
| 告警 | §9.1 的六条原样列出，规则本身留在 Prometheus | §9.1 |
| 指标自身健康 | 单实例序列数 / 5000 预算 + 按指标分布 + `__other__` 折叠提示 | §4.4 + §5.2 |

三条实现约定（原型里已经体现，实现时不得走样）：

1. **没开 SLO 桶时，分位面板降级为 avg 并标注「p95 不可算」**，而不是显示一条假的 0 线——
   `count/sum/max` 里根本不存在分位，编一个出来比空着更糟（§5.4）。
2. **抓取中断显示「无数据」而不是 0**。`absent()` 告警触发时页面顶部出横幅，所有面板置灰；
   把「没有数据」画成 0 会把故障读成「系统很闲」。
3. **基数是一个常驻面板而不是一行日志**。`__other__` 折叠发生时在这里给 chip 提示（折叠了几个），
   超过 3000 序列的预警也落在这里——这是本设计独有的自监控点，别处没有数据源。

KPI 的数值口径：取「最近几分钟的窗口值」而不是时间线上最后一个点（抓取间隔 30s，
尾点天然带半分钟滞后），与告警的 `[5m]` 窗口保持同一口径，避免「KPI 说没事、告警在响」。

---

## 10. 测试策略

### 10.1 单元测试（不启容器）

`SimpleMeterRegistry` + 直接调切面/发布器：

- `ApiMetricsAspect`：正常返回 → `outcome=success`；抛 4xx → `client_error`；
  抛 5xx → `server_error`；异常类名进 `exception` 标签。
- `CatalogEventPublisher`：发一条 `TABLE` 事件 → 对应 counter +1，其它 category 不变。
- `CatalogTagCardinalityFilter`（`MetricsConfiguration` 里注册的那个 `MeterFilter`）：
  注册 60 个 catalog → 第 51 个开始变 `__other__`，序列总数 ≤ 51，且只 WARN 一次。

### 10.2 集成测试

`@SpringBootTest(webEnvironment = RANDOM_PORT)` + `TestRestTemplate`：

1. 打一次 `GET /v1/config`（公开端点）→ `http.server.requests` 有 series，
   `uri=/v1/config`（模板值）。
2. 带错令牌打一次 → `lakehouse.authentication.failures{reason="invalid_token"}` +1。
3. 打 `/actuator/prometheus` → 文本里能找到 §4.3 的每个名字（**断言字符串，不断言顺序**）。

### 10.3 文档与代码一致性（本仓的惯用手段）

新增 `scripts/verify-metrics-doc.py`，照 `verify-spark-sql-doc.py` 的样子做三件事：

1. 从 `MetricsNames.java` 抽常量，检查本文 §4.3 的每个名字都出现、且没有多余项；
2. 检查 §6 的配置键与 `MetricsProperties` 的字段一一对应；
3. 检查 §0 的「✅/❌」与 §4 的实际条目一致（防止「写了不做」）。

这样「文档里的指标名」和「代码里的指标名」只有一份真源；改名字忘了改文档 → 构建红。

---

## 11. 分期

| 期 | 内容 | 验收 |
| --- | --- | --- |
| **P0** | 依赖 + `MetricsProperties` + `MetricsNames` + Actuator/管理端口 + API 切面 + 认证/授权计数 | `/actuator/prometheus` 能抓到 `lakehouse_api_requests_seconds` 与两个计数；`mvn test` 全绿 |
| **P1** | catalog/表提交/事件/优化四组域指标 + `catalog` 基数过滤 + SLO 桶 + 看板 | 基数预算表实测不超 1500；四个看板可用 |
| **P2** | Iceberg 报告 SPI（logging/persisting）+ `unknown_snapshot` 告警 + 方法级持久化计时（若 Hikari 不够） | 端点上报后能在 `lakehouse_iceberg_metrics_reports_total` 看到；落库表有保留期策略 |

---

## 12. 与 Polaris 的差异（一张表看完）

| 维度 | Polaris | 本设计 | 为什么 |
| --- | --- | --- | --- |
| 端点级指标 | 生成模板逐端点 `@Timed` | 一个 `@RestController` 切面 | 130 个端点，注解会漂；切面对新端点自动生效 |
| `class` 标签 | FQCN | 简单类名 | 值更短；跨模块搬类不炸面板 |
| 高基数标签超限 | 停止记录 HTTP 指标 | 折叠成 `__other__` | 故障时更需要数据；聚合仍然正确 |
| 关闭 principal 标签 | 标签键存在、值为空 | 键不出现 | 空值会多一条时间线，且 `group by` 时要过滤 |
| 多租户维度 | `realm_id`（默认关） | `catalog`（默认开、有上限） | 我们是单租户，catalog 就是最有用的那一维 |
| Iceberg 用量报告 | `default`（=logging） | `no-op`（P2 再谈落库） | 先保证「绕写」能被发现，落库要先定保留期 |
| 持久化方法级指标 | `ObservingBackend` 全量装饰 | 先用 Hikari 自带，P2 再评估 | 我们的 DAO 走 Spring Data，装饰成本高、收益待证 |
| 指标命名 | `polaris_*` | `lakehouse_*`；HTTP 沿用 `http.server.requests` | 与应用名一致；框架指标不改名 |

---

## 13. 明确不采纳

1. **不给每个端点写注解**——理由见 §7.3。
2. **不做库存 gauge**（catalog 数、表数、快照数）：这些要查库，且随时变；
   要看就查管理 API 或控制台。
3. **不把事件流当指标**：事件是「变更日志」，量级与指标不同；两者混在一起会让
   「QPS 掉了」和「没人改表」这两件事分不清。
4. **不记录带表名的指标**：见 §5.1。真要看「哪张表提交慢」，用 `lakehouse.table.commit`
   的 `format` 定位到格式，再查事件表或客户端日志。
5. **不在指标里做权限判定**：指标是观测，不是策略点；拒绝计数只反映结果。
6. **不引入 trace 与 metrics 的自动关联**（`trace_id` 标签）：基数爆炸，且我们还没上 OTel。

---

## 14. 验收清单

- [ ] `GET /actuator/prometheus` 返回文本，含 `lakehouse_api_requests_seconds_count`
- [ ] 业务端口访问 `/actuator/prometheus` 得 404（只在管理端口）
- [ ] 一次 `GET /v1/config` 后，`http_server_requests_seconds_count{uri="/v1/config"}` = 1
- [ ] 一次错误令牌请求后，`lakehouse_authentication_failures_total{reason="invalid_token"}` = 1
- [ ] 一次拒绝授权后，`lakehouse_authorization_decisions_total{result="denied"}` = 1
- [ ] 建一个 catalog 后，`lakehouse_catalog_operations_total{operation="create",result="success"}` = 1
- [ ] 注册 60 个 `catalog` 标签值后，出现 `__other__`，且 WARN 只出现一次
- [ ] `lakehouse.rest.metrics.catalog-tag.max-cardinality=0` 时**启动失败**（不是静默）
- [ ] 关 `principal-tag` 时，导出文本里没有 `principal=` 这个标签键
- [ ] `verify-metrics-doc.py` 通过（名字、配置键、能力表三处一致）
