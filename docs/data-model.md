# 数据模型

服务端把 catalog 元数据与授权关系全部放进关系库，共 **27 张表、27 个 JPA 仓储接口**。
本文件说明表的划分，以及三个容易看错或踩坑的设计点：摘要列、审计列、JSON 文档列。

- 实体与仓储按模块分两处（模块划分见 [`maven-module-design.md`](maven-module-design.md) §8，
  目录层级见其 §9.1；下面两个路径里的 `lakehouse-rest-spring-parent/` 就是那层嵌套）：
  - `lakehouse-rest-spring-parent/lakehouse-rest-core/.../domain/entity/` —— **22 个实体**
    （catalog 侧 12 + 管理侧 4 + 事件审计 1 + 血缘 3 + 行过滤/列脱敏 2），
    另含 `AuditedEntity` 基类与 `JsonConverters`；同级 `domain/repo/` 有 **22 个仓储接口**
  - `lakehouse-rest-spring-parent/lakehouse-rest-server/.../domain/entity/` —— **4 个实体**
    （表存储优化 3：`TableOptimization` / `OptimizationJob` / `OptimizationJobItem`；
    加 catalog 执行集群 1：`CatalogExecutionCluster`）；同级 `domain/repo/` 有 4 个仓储接口
  - 合计 27 个实体 + 27 个仓储接口。分家的依据是**谁读它**而不是谁写它：
    优化三表只有 server 侧的调度与控制器会读，留在 server；执行集群表只被提交与探活链路读，
    同样留在 server；其余实体被协议层（`protocol-paimon`）的面复用，所以下沉到 core，
    否则协议层要反向依赖 server。血缘三表虽然只由 core 的
    `LineageService` 读，但**删表要顺手清边**——那件事挂在 core 的
    `LineageEdgeCleanupListener`（订阅目录事件）上，跟着事件框架留在 core 才不用把关系反过来
- **表名统一用 `lakehouse_` 前缀，元数据库为 `lakehouse_catalog`**；客户端全走 REST API，
  不直连这个库。加 Iceberg REST 支持时**没有另建一套元数据表**，
  而是在 `lakehouse_catalog` 上加一列 `catalog_format`（PAIMON / ICEBERG，
  建 catalog 时确定、不可变）来做格式分派——**不复用**已有的 `catalog_type`（那是**托管模式**：
  INTERNAL / EXTERNAL，与格式正交；混用会让「外部 Iceberg 仓库的入口」这种状态没有地方表达）。
  详见 [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §3.1–3.2
- 建表语句：[`../sql/schema-mysql.sql`](../sql/schema-mysql.sql)（由实体生成，改实体后必须重跑）。
  **表与列的注释也在实体上**（`@Table(comment = ...)` / `@Column(comment = ...)`）：
  注释随 DDL 一起生成，`show create table` 看到的就是实体里写的那句话。
  新增实体或列时漏写注释不会让构建失败，因此 `MysqlDdlGeneratorTests.everyTableAndColumnIsCommented`
  会把它挡在生成侧。
- 库结构由 `ddl-auto: validate` 守住：实体与库不一致时应用**直接启动失败**，
  代价是实体变更后要自行迁移存量数据（见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 14 条）

---

## 1. Catalog 侧（12 张）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `lakehouse_catalog` | catalog | prefix、warehouse、defaults / overrides |
| `lakehouse_database` | database | 命名空间、位置、属性 |
| `lakehouse_table` | table | 当前 schema、路径、类型、最新快照指针 |
| `lakehouse_table_schema` | schema 历史版本 | 支撑 `rollback-schema` |
| `lakehouse_snapshot` | snapshot / TableSnapshot | 清单指针 + 聚合统计 |
| `lakehouse_branch` | branch | 指向快照的命名历史线 |
| `lakehouse_tag` | tag | 快照的稳定别名与保留时长 |
| `lakehouse_partition` | partition | spec、统计、done 标记、选项 |
| `lakehouse_view` | view | 字段、查询语句、方言映射 |
| `lakehouse_function` | function | 入参/出参/定义体/属性 |
| `lakehouse_consumer` | consumer | 流式消费位点 |
| `lakehouse_semantic_view` | semantic view | 格式 + 全文定义 |

### 1.1 Iceberg 协议面新增的 9 个列

加 Iceberg REST Catalog 支持时**一张表都没新建**，只在三张表上加了 9 个列
（取舍见 [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §4）：

| 表 | 列 | 类型 | 说明 |
| --- | --- | --- | --- |
| `lakehouse_catalog` | `catalog_format` | `varchar(32)`，默认 `PAIMON` | 协议判别列，建 catalog 时确定、**不可变**；格式不进任何键 |
| `lakehouse_table` | `metadata_doc` | `mediumtext` | **Iceberg 整份 `TableMetadata` JSON**，Iceberg 表的真相源 |
| `lakehouse_table` | `metadata_location` | `varchar(1024)` | 元数据文件路径；**同时是乐观并发的 CAS 条件** |
| `lakehouse_table` | `table_uuid` | `varchar(64)` | Iceberg `table-uuid`，跨重命名与改位置不变 |
| `lakehouse_table` | `format_version` | `int` | Iceberg `format-version`（1/2/3）；Paimon 表为 `null` |
| `lakehouse_snapshot` | `parent_snapshot_id` | `bigint` | Iceberg 快照链的前驱 |
| `lakehouse_snapshot` | `sequence_number` | `bigint` | Iceberg v2 的提交次序；refs 保留与快照过期按它排序 |
| `lakehouse_snapshot` | `summary_json` | `text` | Iceberg `summary` 原样存（键集合随版本漂移，因此不挑键建列） |
| `lakehouse_view` | `metadata_location` | `varchar(1024)` | Iceberg 视图的 `ViewMetadata` 文件指针兼 CAS 条件；Paimon 视图不写元数据文件，为 `null` |

两边的资源模型在这三张表上本来就是同构的，因此**既有列一个都没改**，只是复用：

| 既有列 | Iceberg 侧的来源 |
| --- | --- |
| `lakehouse_table.path_value` | `location`（表目录） |
| `lakehouse_table.schema_id` + `schema_doc` | `current-schema-id` + 当前 schema |
| `lakehouse_table.table_type` | Iceberg 用 `ICEBERG`（与 `format-table` / `object-table` 同列，因此按 `tableType` 过滤照旧可用） |
| `lakehouse_table_schema`（一行一个 schema 版本） | `schemas[]` 里逐个 `schema-id` |
| `lakehouse_snapshot.base_manifest_list` | `manifest-list`（Iceberg 每个快照只有一个清单） |
| `lakehouse_snapshot.total_record_count` | `summary["total-records"]` |
| `lakehouse_branch` / `lakehouse_tag` | `refs` 里 `type=branch` / `type=tag`（不含 `main`） |
| `lakehouse_view.view_schema_doc` | Iceberg 用同一列存整份 `ViewMetadata` JSON，因此视图**不需要任何投影行** |

`lakehouse_partition` / `lakehouse_consumer` / `lakehouse_semantic_view` / `lakehouse_function`
在 Iceberg 侧不写：分区由 manifest 决定（REST catalog 不逐分区登记）、Iceberg 没有消费位点、
没有语义视图，`FunctionMetadata` 的只读投影是二期。

## 2. 管理侧（4 张）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `lakehouse_principal` | principal | 主体；只保存密钥摘要，明文仅在创建 / 轮换时返回一次 |
| `lakehouse_principal_role` | principal role | 纯分组，自己不携带任何权限 |
| `lakehouse_principal_role_grant` | 主体 ↔ principal role | 多对多关联 |
| `lakehouse_permission_assignment` | **唯一的授权表** | 官方三元组 `(resource, access, principal)`：8 种资源定位符 + 14 项 access（官方 12 + 两个凭据扩展词）+ principal 原文 + 过期时间 + 列范围 |

**改造前这里有 7 张表**（另含 `lakehouse_catalog_role` / `lakehouse_catalog_role_grant` /
`lakehouse_resource_grant`，即 Polaris 的 RBAC 链），已随 catalog role 整层移除。
授权现在只有一条链路：主体凭成员关系得到 `role:<名>` 候选串，再与裸名、`user:<名>`
一起参与判定，见 [`authorization.md`](authorization.md) §2.4。

## 2.1 表存储优化（3 张）

三张表都**不是** Paimon 元数据的一部分，而是本服务端自己的运维记录：优化策略、优化批次、
批次里每张表的结果。它们挂在表实体上（`table_id`），但不参与 catalog API 的任何响应。

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `lakehouse_table_optimization` | 表存储优化策略 | 与表 1:1（`uk_table_optimization`）：总开关、调度间隔、调度游标、三项动作各自的开关与**配置差异** |
| `lakehouse_optimization_job` | 优化批次 | 一个批次 = 一次 k8s 提交；组批以 catalog 为界，一个批次含多张表。另有三个**提交租约**列（`claim_owner` / `claim_expires_at` / `claim_attempt`），只在 `SUBMITTING` 期间有值 |
| `lakehouse_optimization_job_item` | 批次内单表结果 | 批次 1:N；每张表的动作、状态、前后指标、错误原因 |

**批次表那三个租约列的理由**（多副本调度，见
[`optimization-multi-instance-design.md`](optimization-multi-instance-design.md) §5.3）：
「提交中」这一步有外部副作用（建 k8s Job），进程在 `submit()` 与回写之间被杀时不能
永久卡住。`claim_owner` 记谁在提交，`claim_expires_at` 是它的到期时刻（默认 30 秒），
`claim_attempt` 是代数——回写与回收都带它，于是「上一代的写」会被条件更新挡在门外。
代数到 3 次仍反复中断就判失败，而不是无限重投。

## 2.2 事件审计（1 张）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `lakehouse_audit_event` | 目录领域事件 | 10 类 38 个领域事件（catalog / 库 / 表 / 视图 / 授权 / 主体与角色 / 优化）的 append-only 事实记录；自增 `id` 兼作增量拉取的游标，`(catalog, id)` 与 `(category, id)` 两条联合索引分别支撑按目录、按类别增量拉 |

两种表格式的事件在**同一张表、同一个流**里（`catalog` 只是查询参数）；`attributes_json`
用 JSON 单列承载开放属性，`category` 是 `type` 的冗余列（存下来让「只看权限变更」
这类过滤走索引，同步点只有 `AuditEventEntity.of` 一处；理由见
[`event-framework.md`](event-framework.md) §2）。
发布与消费链路、不发清单与增量契约都在 [`event-framework.md`](event-framework.md)，
本文件只记「它在库里长什么样」。这条事件**不继承 `AuditedEntity`**——它是不可变事实，
没有「最后修改人」可言（对比上面的业务表：那些表会被 UPDATE，所以要审计列）。

三个设计点值得单独说明，理由都在
[`table-optimization.md`](table-optimization.md) 里展开：

- **配置存「差异」而不是「全量快照」。** 默认值属于 catalog
  （`optimization.<动作>.<参数>` 前缀的 catalog 属性，与 `table-default.*` 同一套做法），
  表级只写用户显式改过的键，取值靠三层合并（内置默认 → catalog → 表）得到。
  存全量的话，「系统调整默认值」永远追不上已经建过策略的表。
- **`next_run_at` 既是调度游标也是分布式抢占的落点。** 出批前用
  「`update ... where id = ? and next_run_at = ?`」把它推到下一个周期，只有更新到 1 行的表
  才进这一批——多副本部署下靠这一条条件更新互斥，不引入额外锁或选举组件。
- **`catalog_id` 是冗余列。** 组批按 catalog 分组，扫描一次就要按它归并；
  走 `table_id` 反查两张表既慢又没必要。它与 `lakehouse_table.catalog_id` 由写入方保持一致。

对外接口一律用 catalog 名与「catalog.库.表」三段名，实体里存的是 id —— 转换只发生在
`OptimizationJobService` 一处，见 [`table-optimization.md`](table-optimization.md) §5。

## 2.3 血缘（3 张）

三张表都不是 catalog 元数据，而是**引擎上报的依赖关系**，只由 `LineageService` 读写；
`GET /lineage/tables|columns` 是它们的唯一出口（端点、判权与图的形状见
[`lineage-design.md`](lineage-design.md) §6–§7）。

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `lakehouse_lineage_run` | 一次执行（事件） | 一次 INSERT / MERGE / CTAS 一行，**只插入、不更新**（`uk_lineage_run(run_id)` 兼作幂等键）；记引擎与版本、语句与写模式、`target_refs`（竖线包夹，供 `?ref=` 过滤）、`columns_truncated` |
| `lakehouse_lineage_table_edge` | 表级边（状态） | 一对 `(源, 目标, 引擎)` 一行，重复执行只累加 `run_count` 并推进 `last_seen`；`uk_lineage_table_edge` 用两个 ref 的 SHA-256（`varchar(1024)` 在 utf8mb4 下超单列索引上限，见 §3） |
| `lakehouse_lineage_column_edge` | 列级边（状态） | 一对 `(源表.列, 目标表.列)` 一行，取**最近一次**的 `transform_kind` / `expression`；常量列与静态分区列没有源，用空 `source_ref` + 空 `source_column` 占一行（唯一键因此仍成立，「没有源」是显式的一行而不是「查不到」） |

两条语义值得单独记，理由都在 [`lineage-design.md`](lineage-design.md) §7：

- **run 是事件、边是状态，刻意分成两类表。** 合成一张的话，「同一对表被跑了 1 万次」
  就是 1 万行，而图上要的只是「谁依赖谁」加一个新鲜度。保留清理只按
  `lakehouse.rest.lineage.run-retention-days` 删 run，边按 `last_seen` 老化、
  由删表事件级联清——因此**清完 run 图还在**，这正是两张表分开的意义。
- **`engine` 进唯一键。** 本期恒为 `SPARK`，但同一对表将来可能被不同引擎写过，
  那是两条边（「哪个引擎在写」是有效信息）。唯一键里少一列，将来加引擎就是一次数据迁移。

## 2.4 行过滤 / 列脱敏（2 张）

两张表都是**策略定义**，不是 Paimon / Iceberg 元数据的一部分；控制台与管理 API 走
`/api/management/v1/policies/tables/{catalog}/{db}/{table}`（`GET` / `PUT` / `DELETE`，
另有 `/resolve` 给引擎读、`/dry-run` 做预览），引擎侧再由 `PolicyRelation` 逻辑节点
在查询计划里执行（算子语义与注入点见
[`row-filter-column-mask-design.md`](row-filter-column-mask-design.md) §5，权限映射见其 §8.1）。

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `lakehouse_data_policy` | 表级策略（一表一行） | `catalog_name` / `database_name` / `table_name` 三段真名 + `full_name_hash`（三段名的 SHA-256）做唯一键——索引长度与值长度解耦，表名再长也不会撞 `ERROR 1071`；`row_filter_json` 存白名单 AST（语义以它为准，`row_filter_sql` 只是展示渲染）、`subjects_json` 是主体白名单（SQL 的 `FOR` 子句）、`exempt_json` 是豁免名单、`hidden_json` 是整列不可见清单；`entity_version` 既是乐观锁又是下发给引擎的 etag |
| `lakehouse_column_mask` | 列脱敏定义（一列一行） | 挂在表级策略下（`policy_id` + `column_name` 唯一）：`mask_json` 是标准集 `fn/args` 或白名单表达式 `expr`；`allow_null_on_required` 是「置空 mask 落在非空列」时的显式确认，默认 `false` 即拒绝 |

两条值得单独记：

- **为什么用 hash 而不是三段名联合唯一键。** `varchar(1024)` 在 utf8mb4 下按 4 字节/字符算，
  单列索引就超过 InnoDB 的 3072 字节上限（与 §3 里 `lakehouse_lineage_table_edge` 同一个坑）；
  hash 列只有 64 字符，真名照旧成列供等值查询与人读。
- **策略层 fail-closed、语义层 fail-open 是刻意的。** 这里两张表读不到就让查询失败，
  而语义层（`lakehouse_semantic_view`）读不到只标记 stale 不拦查询——理由见
  [`semantic-layer-design.md`](semantic-layer-design.md) §3。

## 2.5 执行集群（1 张）

`lakehouse_catalog_execution_cluster` 是**本服务端自己的运维配置**：一个 catalog 的优化任务
提交到哪个 k8s 集群、用什么身份（类型即认证：`SELF_HOSTED`→kubeconfig、`EKS`→SigV4 预签名
STS 令牌、`AKS`→AAD 客户端凭据）。一行一 catalog（`catalog_id` 唯一 + 级联删除），
设计见 [`catalog-execution-cluster-design.md`](catalog-execution-cluster-design.md)。

| 列组 | 列 | 说明 |
| --- | --- | --- |
| 连接 | `cluster_type` / `api_server` / `context_name` / `namespace` / `ca_cert_pem` | `ca_cert_pem` 空 = 用 JVM 信任库；`context_name` 仅 `SELF_HOSTED` |
| 凭据（**只进不出**） | `kubeconfig_sealed` / `auth_config_sealed` | `CredentialCipher` 密封后的整份明文，读模型只回 `has*` 与可公开标识（AccessKeyId / tenantId / clientId），**永不回明文** |
| 运行位置 | `image` / `image_pull_policy` / `image_pull_secrets` / `service_account` / `callback_base_url` | 上一版散在 `lakehouse.rest.optimization` 里的提交参数，现在唯一来源就是这张表 |
| 批次策略 | `max_tables_per_job` / `max_concurrent_jobs` / `job_timeout_seconds` | 并发闸门与超时按 catalog 各自算，不再全局一套 |
| 可选数据面令牌 | `catalog_token_sealed` | 下发给任务的 `/v1/{prefix}` 凭据；只有它允许「清空」（其余凭据缺失等于配置不可用，只能删整份） |
| 探活 | `config_hash` / `probe_status` / `probe_reason` / `probe_message` / `probe_config_hash` / `probed_at` | 五态（未配置 / 停用 / 待验证 / 可用 / 不可用）由这两组列**派生**，不另存状态列：`config_hash` 与 `probe_config_hash` 不等即「改完没验证」 |

两个实现细节决定了这两组 hash 的用法：

- **`config_hash` 用密文算，不是明文。** 写入时明文没变就沿用旧密文（否则每次保存都换 IV，
  hash 必然变，配置会无故掉回「待验证」）。算法是 SHA-256 拼 13 段、以 `0x1f` 分隔。
- **「留空 = 保持原样」只对密文成立。** `caCertPem` 的空串是明确的「清空」语义，
  两者不能混用一套判空逻辑。

---

## 2.6 调度通道租约（1 张）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `lakehouse_scheduler_lease` | 调度通道的「谁跑这一拍」 | 一行一个通道（`optimization.scan` / `optimization.dispatch` / `lineage.cleanup`），`lease_owner` + `expires_at` 是租约，`fencing_token` 每次易主 +1 |

它**不是业务数据**，是并发控制的落点：行在启动时补齐（不写进 DDL 的种子数据，
因为 `sql/schema-mysql.sql` 是生成物）。选租约而不是锁，是因为多副本里最常见的故障是
进程不告而别——锁会永久留在那里，租约到期即失效，代价只是空窗一个 TTL。
正确性不依赖它（各处的条件更新才是裁判），所以这张表丢了、过期了、被手工清空了，
最坏结果都是「多跑几拍」而不是「重复执行」。

## 3. 为什么有两个摘要列

`lakehouse_partition.spec_hash` 与 `lakehouse_permission_assignment.locator_hash` 是各自
「键」字段的 SHA-256 定长摘要，唯一约束落在摘要上而不是原文上。
原因是 MySQL 的索引键上限是 **3072 字节**（utf8mb4 下 768 个字符）：

- `spec_key` 声明为 1024 字符 → 与 `table_id` 相加远超上限；
- `locator_hash` 的原文是 `database_name` 与 `object_name` 的拼接，而标识符长度没有上界
  （Iceberg 的 namespace 层级数也没有上限）；
- 这两者与 `catalog_id` / `access` / `principal` 等列合起来更放不下。

直接建索引会被 MySQL 拒绝：

```
ERROR 1071 (42000): Specified key was too long; max key length is 3072 bytes
```

改法是「原文入库 + 摘要入索引」：`spec_key` / `database_name` / `object_name` 保留，供等值查询使用；
唯一约束改用定长 64 字符的摘要，索引长度与值的实际长度解耦，唯一性语义不变。
摘要在 `@PrePersist` / `@PreUpdate` 里由原文重算（见 `Digests`），因此不依赖调用方是否记得规范化。

## 3.1 行大小上限：配置列用 `text` 而不是 `varchar(8192)`

§3 讲的是**索引键**上限（3072 字节），这里是另一个被同一件事放大的上限——
utf8mb4 按 4 字节/字符计入，于是**整行**不能超过 65535 字节。两者互相独立，
且都只在真库上才现形。

`lakehouse_table_optimization` 的三个配置列原本都是 `varchar(8192)`。单看每一列都合法
（8192 × 4 = 32768 < 65535），但同一张表放三个就合计 98304，建表直接失败：

```
ERROR 1118 (42000): Row size too large. The maximum row size for the used table type,
not counting BLOBs, is 65535. ... You have to change some columns to TEXT or BLOBs
```

改成 `text` 即可：Hibernate 按 `@Column(length)` 选类型，写成 65535 就是 `text`，
这类列只计入 9~12 字节的指针，整表预算从 102484 降到 4216 字节。

- **为什么不担心装不下**：这张表只存**差异**，键集由 `OptimizationOptions.KNOWN_KEYS`
  限定，值也都很短；65535 字节（utf8mb4 下约 1.6 万字符）远超需要。
- **`lakehouse_optimization_job_item.metrics_json` 保持 `varchar(8192)`**：那张表合计
  45116 字节，是全库最大的，仍在预算内（约 2/3）。不动它就省掉一次对已建库的列类型迁移。
- **守门**：`MysqlDdlGeneratorTests#everyTableFitsTheMysqlRowSizeLimit` 按 MySQL 的算法
  （`varchar(N)` 记 4N、`text` 家族记 12、其余类型按实际宽度，另加每行 64 字节开销）
  逐表求和，越界即构建失败，并把超宽的表与列打进失败信息。
  这条断言**必须在生成侧**：行大小上限是存储层的约束，Hibernate 生成 DDL 时不检查、
  在 H2 上跑测试也检查不到——不补断言就只会在真库上炸，而那时建表脚本已经交付出去了。

## 4. 审计列：主体名超过 255 字符时记摘要

`owner` / `created_by` / `updated_by` 是 `varchar(255)`，而主体名的长度没有上界：
认证链认不出令牌时会退化为「令牌即主体名」，控制台签发的访问令牌就有 272 个字符，
于是写入以 `Data too long for column 'created_by'` 失败——现象是「建表/建库报 500」，
从错误信息里看不出与认证有关。

落库前统一在 `AuditedEntity` 归一化（`AuditPrincipal.of`）：放得下的原样存，
放不下的记 `sha256:<前 12 位>`。

- **不截断**：截断会把凭据的前 255 个字符原样写进元数据库，
  而元数据是能被列表接口读出来的。
- 超长时启动日志告警一次，给出的解法是配 `lakehouse.rest.auth.token-principals`
  把令牌映射成真正的名字。
- 归一化放在实体层而非 `RequestContext`：授权判定读的是**未归一化**的主体名，两者互不影响。
- **两条写入路径，收口方式不同**（2026-10-22 补）：
  - 实体写入（`save`）：`touch` / `markCreated` 之外还有一处 `@PrePersist` / `@PreUpdate`
    回调兜底——Lombok 的 `setUpdatedBy` 是公开的，绕过 `touch` 直接赋值的调用点
    （例如按批次取消优化任务）也能被拦住。
  - **批量更新（JPQL `update ...`）不触发实体回调**：`TableRepository#updateIcebergMetadata`
    与 `ViewRepository#updateViewMetadata` 直接发 SQL，传什么进 `varchar(255)` 就是什么。
    这两处必须自己归一化，一律用 `RequestContext.auditPrincipal()`——曾经直接传
    `RequestContext.principal()`，于是 Iceberg 的每次 commit 都以
    `Data too long for column 'updated_by'` 失败，而错误信息里看不出与认证有关。
- **刻意不通过加宽这三列来解决**：它们会被接口原样返回，加宽等于让凭据明文入库并可被读回；
  且主体名长度没有上界，加宽只是把溢出推后，还要对 14 张表共 42 个列做迁移。

三列均无索引（全库只有主键索引），这一点对「加宽」有利，但不足以抵消上面的理由。
完整取舍见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 19 条。

## 5. 结构类字段以 JSON 存单列

schema、函数定义、分区 spec 等结构以 JSON 文本存进单列，实体字段保持强类型，
避免为每个属性建表；复杂结构通过 `AttributeConverter` 转换。
`storageConfigInfo` 同样整份存一列 JSON，`storage_type` 与
`allowed_locations_json` 是它的投影列，便于直接用 SQL 筛选。

这一列里唯一不是原样存储的是 catalog 自带静态凭据的 `secretAccessKey`：
写库前用 AES-GCM 加密成 `v1:<base64(iv ‖ 密文+tag)>` 再塞进这段 JSON，
因此直接查库看到的是密文，而接口层还会再抹一次（`StorageConfigs.withoutSecrets`）
——两处都是必要的，只做一处等于没做。加密密钥不落库，来自
`lakehouse.rest.storage.credential-secret-key`，所以**换密钥等于换密文**：
旧密文解不开会明确报 500，需要重新提交一次凭据
（见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 20 条）。

---
