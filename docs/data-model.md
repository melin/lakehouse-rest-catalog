# 数据模型

服务端把 catalog 元数据与授权关系全部放进关系库，共 **20 张表、20 个 JPA 仓储接口**。
本文件说明表的划分，以及三个容易看错或踩坑的设计点：摘要列、审计列、JSON 文档列。

- 实体与仓储按模块分两处（模块划分见 [`maven-module-design.md`](maven-module-design.md) §8，
  目录层级见其 §9.1；下面两个路径里的 `lakehouse-rest-spring-parent/` 就是那层嵌套）：
  - `lakehouse-rest-spring-parent/lakehouse-rest-core/.../domain/entity/` —— **17 个实体**
    （catalog 侧 12 + 管理侧 4 + 事件审计 1），
    另含 `AuditedEntity` 基类与 `JsonConverters`；同级 `domain/repo/` 有 **17 个仓储接口**
  - `lakehouse-rest-spring-parent/lakehouse-rest-server/.../domain/entity/` —— **3 个表存储优化实体**
    （`TableOptimization` / `OptimizationJob` / `OptimizationJobItem`）；同级 `domain/repo/` 有 3 个仓储接口
  - 合计 20 个实体 + 20 个仓储接口。分家的依据是**谁读它**而不是谁写它：
    优化三表只有 server 侧的调度与控制器会读，留在 server；其余实体被协议层（`protocol-paimon`）
    的面复用，所以下沉到 core，否则协议层要反向依赖 server
- **表名统一用 `lakehouse_` 前缀**（2026-10 由 `paimon_` 迁来，见 **§6**）。服务端早期只服务
  Paimon 一种格式，表名带的是 `paimon_`；加 Iceberg REST 支持时**没有另建一套元数据表**，
  而是在 `lakehouse_catalog` 上加一列 `catalog_format`（PAIMON / ICEBERG，
  建 catalog 时确定、不可变）来做格式分派——**不复用**已有的 `catalog_type`（那是**托管模式**：
  INTERNAL / EXTERNAL，与格式正交；混用会让「外部 Iceberg 仓库的入口」这种状态没有地方表达）。
  详见 [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §3.1–3.2
- 建表语句：[`../sql/schema-mysql.sql`](../sql/schema-mysql.sql)（由实体生成，改实体后必须重跑）
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
| `lakehouse_permission_assignment` | **唯一的授权表** | 官方三元组 `(resource, access, principal)`：8 种资源定位符 + 12 项 access + principal 原文 + 过期时间 + 列范围 |

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
| `lakehouse_optimization_job` | 优化批次 | 一个批次 = 一次 k8s 提交；组批以 catalog 为界，一个批次含多张表 |
| `lakehouse_optimization_job_item` | 批次内单表结果 | 批次 1:N；每张表的动作、状态、前后指标、错误原因 |

## 2.2 事件审计（1 张）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `audit_event` | 目录领域事件 | 10 类 38 个领域事件（catalog / 库 / 表 / 视图 / 授权 / 主体与角色 / 优化）的 append-only 事实记录；自增 `id` 兼作增量拉取的游标，`(catalog, id)` 与 `(category, id)` 两条联合索引分别支撑按目录、按类别增量拉 |

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

---

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

## 6. 表前缀：`lakehouse_`（已从 `paimon_` 迁完）

### 6.1 为什么改

三件事同时成立，构成了唯一的便宜窗口：

1. **前缀已经在说谎**。表里既存 Paimon 也存 Iceberg（`catalog_format` 分派），
   `lakehouse_table` 里躺着 `table_kind='ICEBERG'` 的行（通用表方案落地后还会多一类）。
2. **表数还少**。23 张表、23 个实体、一个库（当时的口径）。每加一张表就多一处要改的点，
   而 `generic-table-design.md` 已经预告了下一步要动这张表。
3. **没有外部契约**。客户端（Spark / Flink / 控制台 / PyIceberg）全部走 REST，不直连库；
   表名只在服务端内部、DDL、文档与运维脚本里出现。真正被打破的是**运维侧**
   的手写 SQL、监控、备份脚本、CDC 订阅——这些要给映射表（6.8），不是给兼容层。

反过来，不改的代价是：通用表落地后 `lakehouse_table` 会同时装 Paimon 表、Iceberg 表、
parquet/orc/lance/vortex 目录表，名字与内容彻底脱钩，那时再改要动的面更大。

### 6.2 命名决策

表名选 `lakehouse_`：与 `lakehouse.rest.*` 配置前缀、`io.github.melin.lakehouse.rest` 包名、
`lakehouse-rest-*` artifact 是同一套词。不用无前缀方案，是因为 `table` / `database` /
`schema` / `view` / `function` / `partition` 全是 MySQL 保留字，全库都得加反引号；
`lh_` 短但不自解释、又与配置前缀对不上。

库名同步 `paimon_catalog` → `lakehouse_catalog`（只换词，保持 DBA 的授权语句形状不变）。
代价是出现 `lakehouse_catalog.lakehouse_catalog` 这种叠词；若嫌难看，库名可用
`lakehouse_meta`，与表名解耦——**改动只在 jdbc url 与迁移脚本的目标库名**。

`audit_event` 一并改成 `lakehouse_audit_event`：它是唯一没带前缀的表，留着它，
下一个人一定会问「为什么它特殊」。

### 6.3 落地了什么

> 下表记录的是**迁移当时**的状态（23 张表）。之后删掉 catalog role 整层，
> 表数降到 20（见 §2），`paimon_catalog_role` / `paimon_catalog_role_grant` /
> `paimon_resource_grant` 三行已不存在。

| 位置 | 改动 |
| --- | --- |
| `@Table(name = …)` | **23 处**：core 19 + server 3，外加 `audit_event` → `lakehouse_audit_event` |
| Javadoc / 注释里的 `{@code paimon_xxx}` | 连同实体注解共 **42 个 Java 文件**（23 个实体 + 19 个只改注释的文件） |
| `sql/schema-mysql.sql` | 全量重生成（生成物，不手改）；表数仍 23、唯一约束 21、索引 26 |
| `scripts/gen-mysql-ddl.sh` | `DB_NAME` 默认值 → `lakehouse_catalog` |
| `application.yml` / `application-postgresql.yml` | jdbc url 的库名 + 2 处注释 |
| `deploy/kubernetes/configmap.yaml` | url 库名 |
| 控制台源码 | `LoginView.vue` / `SettingsView.vue` / `api/console-auth.js` 的表名提示（产物需重建） |
| 文档 | 本文 + 11 份（含 README / AGENTS 的索引行） |
| 新增 | `sql/migrate-table-prefix.sql`、`sql/rollback-table-prefix.sql` |
| **没改** | 索引名与唯一约束名（`uk_*` / `ix_*` 本就不带前缀，且 MySQL 索引名是表级命名空间，rename 时跟着表走）、外键（全库没有） |

**明确不动的东西**（改错这里比不改更危险）：

| 不动 | 为什么 |
| --- | --- |
| `<paimon.version>`、`org.apache.paimon:*` 依赖坐标 | 上游项目坐标，改了构建就废 |
| `PAIMON` / `ICEBERG` 枚举值、`catalog_format` 列的取值 | 是**表格式**，不是表前缀 |
| `spark.paimon.rest.{token,management.url,catalog.url}` | SparkConf 命名空间，客户端契约 |
| k8s 资源名 `paimon-rest-server` / `paimon-mysql` / ConfigMap 名 | 「重命名部署单元」要动 StatefulSet 与 PVC 引用，单独一轮 |
| Spark 测试里名为 `paimon_catalog` 的 catalog | 那是 catalog 名（`spark.sql.catalog.paimon_catalog`），不是库名 |

> ⚠️ **不要用 `sed s/paimon/lakehouse/` 全局替换。** 会同时改掉 `paimon.version`、
> `org.apache.paimon:paimon-spark-3.5_2.12`、`PAIMON` 枚举、`spark.paimon.rest.*`、
> `paimon-rest-*` 资源名。只替换 **`paimon_`（带下划线、小写）**，改完用
> `git diff | grep -c 'PAIMON\|paimon\.version\|org\.apache\.paimon'` 断言为 0。

### 6.4 存量库怎么迁移

脚本：[`../sql/migrate-table-prefix.sql`](../sql/migrate-table-prefix.sql)（回滚：
[`../sql/rollback-table-prefix.sql`](../sql/rollback-table-prefix.sql)）。

**用一条多表 `RENAME TABLE`，不是「建新表 + 灌数据」。** MySQL 的多表 rename 是**原子的**
（要么全成、要么全不成），不会停在「一半新一半旧」这种最难排查的中间态；自增计数器、
索引、约束、表统计都跟着表走，而建新表方案会把 AUTO_INCREMENT 重置成 1。

两个必踩的坑，脚本里已经写成步骤：

- **库级授权不跟库走**。原授权是 `on paimon_catalog.*`，搬过去要重新 `grant` 一次，
  否则应用连得上但第一条 `SELECT` 就是 `1142`。
- **回滚时必须先建旧库**。`RENAME` 只搬表，不会把库带回来；漏了这一步，
  回滚脚本第一条就失败，而现场已经是「新库被搬空」的状态。

降级方案（A′）：DBA 不给建库权限，就只做同库内改名——把脚本里的目标库名换回
`paimon_catalog`、跳过建库与 grant。代价是库名还叫 `paimon_catalog`，问题只解决一半，
但**代码侧改动完全一样**，将来补库名不用再动代码。

Postgres 侧：仓库里没有 PG 的 DDL（只有 MySQL 生成器，`application-postgresql.yml`
只是连接配置）。若确实在用，`begin; alter table … rename to …;` × 23 + `commit;`
即可——PG 的 DDL 本身是事务性的。但 PG **不能跨库 rename**，也不能 `RENAME DATABASE`，
所以 PG 环境直接采用 A′。

### 6.5 执行窗口

`ddl-auto: validate` 决定了**代码与表名必须同时生效**：新 jar 配旧表名 → 启动失败；
旧 jar 配新表名 → 第一个查询就是 `1146 Table doesn't exist`。所以滚动升级不成立，
需要约 **1 分钟**的停机窗口：

| 步 | 动作 | 预计 |
| --- | --- | --- |
| 1 | 公告窗口，确认没有长事务（脚本第 0 步查 `information_schema.innodb_trx`） | — |
| 2 | `mysqldump --single-transaction --databases paimon_catalog > backup.sql` | 分钟后 |
| 3 | 停服务端（`kubectl scale --replicas=0` 或停进程） | 秒 |
| 4 | 跑脚本第 1~3 步（建库 → rename → 授权） | **毫秒级**（纯元数据） |
| 5 | 跑脚本第 4 步自检：应为 23 行，且没有非 `lakehouse_` 前缀的表 | 秒 |
| 6 | 换新镜像 / 新 jar 启动（`validate` 通过即证明实体与库一致） | 10–30 秒 |
| 7 | 冒烟：控制台登录 → catalog 列表 → 建表 → loadTable → 事件页 | 分钟 |

窗口内所有 catalog 操作（包括引擎的 `loadTable`）都会失败——引擎每次都打服务端，
不存在「已缓存的作业不受影响」。

k8s 部署单独提一句：`deploy/kubernetes/mysql.yaml` 的 `seed-initdb` 只在 MySQL
**首次初始化**（数据目录为空）时被 entrypoint 执行，所以**存量集群不会自动迁移**，
必须手工跑脚本；新集群直接用新 DDL，天然就是 `lakehouse_`。

**回滚顺序是先回滚库、再回滚 jar**：反过来会让旧代码对着新库启动，`validate` 直接失败。
另外旧库在 rename 之后是空的，「保留旧库 24 小时以防万一」没有意义（里面没有数据）；
有意义的是保留备份与这两个脚本。

### 6.6 零停机变体（只在「完全不能停」时用）

若停 1 分钟都不可接受，可以用视图别名过渡，代价是短期存在两套名字：

1. 先建 `lakehouse_catalog`，把 23 张表 rename 过去；
2. 在 `paimon_catalog` 里建 23 个**单表可更新视图**：
   `create view paimon_table as select * from lakehouse_catalog.lakehouse_table;`
   —— 旧实例的读写继续走视图（MySQL 的单表视图可 INSERT/UPDATE/DELETE，自增由基表给）；
3. 滚动升级到新版本（新代码用新库），全部切换完后 `drop database paimon_catalog`。

不推荐的原因写在明处：视图在 `information_schema` 里是 `VIEW` 类型，DBA 会看到两张皮；
`SHOW CREATE TABLE` 返回的是建视图语句、看不到索引与自增；出问题时排查要多跳一层。
**它买到的是「不用停机」，卖掉的是一天的可读性。**

### 6.7 不做的

1. **不做双写**。元数据服务的写路径不值得为一次改名加一层影子表。
2. **不做 Hibernate `PhysicalNamingStrategy` 隐式前缀**。前缀写在 `@Table` 里，
   `grep "@Table"` 与 DDL、文档三处能对上；隐式前缀会让三者永远差一层，
   而 native SQL / DBA 脚本根本不经过 Hibernate。
3. **不重构表名语义**。`lakehouse_catalog_role_grant` 本来就该叫
   `catalog_role_assignment`，但那是另一件事（改语义），与换前缀混在一起，
   review 时无法判断哪处是「必须改」哪处是「顺手改」。
4. **不顺手改 k8s 资源名**，理由见 6.3。
5. **不在 rename 之后立刻 drop 旧库**（至少在验证过一轮备份可恢复之前）。

### 6.8 验收

| 检查 | 判据 |
| --- | --- |
| DDL 重生成 | `./scripts/gen-mysql-ddl.sh` 输出「建表语句 23 条」，diff 里除表名外**列、索引、约束一行都不该变** |
| 迁移脚本自洽 | 脚本里的 23 个目标表名与 `@Table` 集合完全相等（源 23 = 目标 23，差集为空） |
| 残留 | `grep -rn "paimon_" sql/ lakehouse-rest-*/src --exclude-dir=static` 只剩迁移脚本；`grep -rn "{@code paimon_"` 为空 |
| 误伤 | `git diff \| grep '^-.*PAIMON\|^-.*paimon\.version\|^-.*org\.apache\.paimon'` 为空 |
| 单测 | `mvn -o test -pl :lakehouse-rest-server -am`（**必须带 `-am`**，否则用 `~/.m2` 的旧 core jar） |
| 真库 | 对迁移后的库启动，`ddl-auto: validate` 通过（最硬的一条：实体与库不一致就起不来） |
| 控制台 | 登录 → catalog / 表 / 快照 / 事件页各点一遍；`scripts/verify-console.py` 全绿（产物**必须先清空再重建**） |
| 运维 | 监控 SQL、备份脚本、CDC 订阅里的旧表名按下表改完 |

运维侧映射（表名换词即可，逐条对应）：

| 旧 | 新 |
| --- | --- |
| `paimon_catalog`（库） | `lakehouse_catalog` |
| `paimon_catalog`（表） | `lakehouse_catalog` |
| `paimon_database` / `paimon_table` / `paimon_table_schema` | `lakehouse_database` / `lakehouse_table` / `lakehouse_table_schema` |
| `paimon_snapshot` / `paimon_branch` / `paimon_tag` / `paimon_partition` | `lakehouse_snapshot` / `lakehouse_branch` / `lakehouse_tag` / `lakehouse_partition` |
| `paimon_view` / `paimon_function` / `paimon_consumer` / `paimon_semantic_view` | `lakehouse_view` / `lakehouse_function` / `lakehouse_consumer` / `lakehouse_semantic_view` |
| `paimon_principal` / `paimon_principal_role` / `paimon_catalog_role` | `lakehouse_principal` / `lakehouse_principal_role` / `lakehouse_catalog_role` |
| `paimon_principal_role_grant` / `paimon_catalog_role_grant` | `lakehouse_principal_role_grant` / `lakehouse_catalog_role_grant` |
| `paimon_resource_grant` / `paimon_permission_assignment` | `lakehouse_resource_grant` / `lakehouse_permission_assignment` |
| `paimon_table_optimization` / `paimon_optimization_job` / `paimon_optimization_job_item` | `lakehouse_table_optimization` / `lakehouse_optimization_job` / `lakehouse_optimization_job_item` |
| `audit_event` | `lakehouse_audit_event` |

### 6.9 风险

| 风险 | 触发 | 影响 | 应对 |
| --- | --- | --- | --- |
| 全局 `sed s/paimon/lakehouse/` | 图快 | 依赖坐标、`PAIMON` 枚举、`spark.paimon.rest.*` 一起被改，构建或客户端直接废 | 只替换 `paimon_`；6.8 的「误伤」检查 |
| 分多条 rename | 逐表执行 | 一半新一半旧，`validate` 失败且难以判断进度 | 单条 `RENAME TABLE`（原子） |
| 库级授权漏授 | 换库没重授 | 应用连上但 `SELECT` 被拒（`1142`） | 脚本第 3 步 + 冒烟里的建表动作 |
| 下游订阅旧表名 | DBA 的监控 / Debezium / 备份 | 静默断流，几周后才发现 | 6.8 的映射表直接发给运维 |
| 长事务卡住 rename | 有慢查询在跑 | rename 等待 metadata lock，期间新请求排队 | 脚本第 0 步先查 `innodb_trx` |
| 前端产物没重建 | 只改了 `.vue` 源码 | 页面还提示 `paimon_principal`，与库不一致 | 清空 `static/console` 再 `./scripts/build-console.sh` |
