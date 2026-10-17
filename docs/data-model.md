# 数据模型

服务端把 catalog 元数据与 RBAC 授权关系全部放进关系库，共 **22 张表、22 个 JPA 仓储接口**。
本文件说明表的划分，以及三个容易看错或踩坑的设计点：摘要列、审计列、JSON 文档列。

- 实体与仓储按模块分两处（模块划分见 [`maven-module-design.md`](maven-module-design.md) §8，
  目录层级见其 §9.1；下面两个路径里的 `lakehouse-rest-spring-parent/` 就是那层嵌套）：
  - `lakehouse-rest-spring-parent/lakehouse-rest-core/.../domain/entity/` —— **19 个实体**（catalog 侧 12 + 管理侧 7），
    另含 `AuditedEntity` 基类与 `JsonConverters`；同级 `domain/repo/` 有 **19 个仓储接口**
  - `lakehouse-rest-spring-parent/lakehouse-rest-server/.../domain/entity/` —— **3 个表存储优化实体**
    （`TableOptimization` / `OptimizationJob` / `OptimizationJobItem`）；同级 `domain/repo/` 有 3 个仓储接口
  - 合计仍是 22 个实体 + 22 个仓储接口。分家的依据是**谁读它**而不是谁写它：
    优化三表只有 server 侧的调度与控制器会读，留在 server；其余实体被协议层（`protocol-paimon`）
    的面复用，所以下沉到 core，否则协议层要反向依赖 server
- **表名的 `paimon_` 前缀是历史遗留，与表格式无关。** 服务端早期只服务 Paimon 一种格式，
  表名就带了这个前缀；加 Iceberg REST 支持时**不改**这些表名、也不另建一套元数据表，
  而是在 `paimon_catalog` 上加一列 `catalog_format`（PAIMON / ICEBERG，建 catalog 时确定、不可变）
  来做格式分派——**不复用**已有的 `catalog_type`（那是**托管模式**：INTERNAL / EXTERNAL，
  与格式正交；混用会让「外部 Iceberg 仓库的入口」这种状态没有地方表达）。
  详见 [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §3.1–3.2
- 建表语句：[`../sql/schema-mysql.sql`](../sql/schema-mysql.sql)（由实体生成，改实体后必须重跑）
- 库结构由 `ddl-auto: validate` 守住：实体与库不一致时应用**直接启动失败**，
  代价是实体变更后要自行迁移存量数据（见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 14 条）

---

## 1. Catalog 侧（12 张）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `paimon_catalog` | catalog | prefix、warehouse、defaults / overrides |
| `paimon_database` | database | 命名空间、位置、属性 |
| `paimon_table` | table | 当前 schema、路径、类型、最新快照指针 |
| `paimon_table_schema` | schema 历史版本 | 支撑 `rollback-schema` |
| `paimon_snapshot` | snapshot / TableSnapshot | 清单指针 + 聚合统计 |
| `paimon_branch` | branch | 指向快照的命名历史线 |
| `paimon_tag` | tag | 快照的稳定别名与保留时长 |
| `paimon_partition` | partition | spec、统计、done 标记、选项 |
| `paimon_view` | view | 字段、查询语句、方言映射 |
| `paimon_function` | function | 入参/出参/定义体/属性 |
| `paimon_consumer` | consumer | 流式消费位点 |
| `paimon_semantic_view` | semantic view | 格式 + 全文定义 |

### 1.1 Iceberg 协议面新增的 9 个列

加 Iceberg REST Catalog 支持时**一张表都没新建**，只在三张表上加了 9 个列
（取舍见 [`iceberg-rest-catalog-design.md`](iceberg-rest-catalog-design.md) §4）：

| 表 | 列 | 类型 | 说明 |
| --- | --- | --- | --- |
| `paimon_catalog` | `catalog_format` | `varchar(32)`，默认 `PAIMON` | 协议判别列，建 catalog 时确定、**不可变**；格式不进任何键 |
| `paimon_table` | `metadata_doc` | `mediumtext` | **Iceberg 整份 `TableMetadata` JSON**，Iceberg 表的真相源 |
| `paimon_table` | `metadata_location` | `varchar(1024)` | 元数据文件路径；**同时是乐观并发的 CAS 条件** |
| `paimon_table` | `table_uuid` | `varchar(64)` | Iceberg `table-uuid`，跨重命名与改位置不变 |
| `paimon_table` | `format_version` | `int` | Iceberg `format-version`（1/2/3）；Paimon 表为 `null` |
| `paimon_snapshot` | `parent_snapshot_id` | `bigint` | Iceberg 快照链的前驱 |
| `paimon_snapshot` | `sequence_number` | `bigint` | Iceberg v2 的提交次序；refs 保留与快照过期按它排序 |
| `paimon_snapshot` | `summary_json` | `text` | Iceberg `summary` 原样存（键集合随版本漂移，因此不挑键建列） |
| `paimon_view` | `metadata_location` | `varchar(1024)` | Iceberg 视图的 `ViewMetadata` 文件指针兼 CAS 条件；Paimon 视图不写元数据文件，为 `null` |

两边的资源模型在这三张表上本来就是同构的，因此**既有列一个都没改**，只是复用：

| 既有列 | Iceberg 侧的来源 |
| --- | --- |
| `paimon_table.path_value` | `location`（表目录） |
| `paimon_table.schema_id` + `schema_doc` | `current-schema-id` + 当前 schema |
| `paimon_table.table_type` | Iceberg 用 `ICEBERG`（与 `format-table` / `object-table` 同列，因此按 `tableType` 过滤照旧可用） |
| `paimon_table_schema`（一行一个 schema 版本） | `schemas[]` 里逐个 `schema-id` |
| `paimon_snapshot.base_manifest_list` | `manifest-list`（Iceberg 每个快照只有一个清单） |
| `paimon_snapshot.total_record_count` | `summary["total-records"]` |
| `paimon_branch` / `paimon_tag` | `refs` 里 `type=branch` / `type=tag`（不含 `main`） |
| `paimon_view.view_schema_doc` | Iceberg 用同一列存整份 `ViewMetadata` JSON，因此视图**不需要任何投影行** |

`paimon_partition` / `paimon_consumer` / `paimon_semantic_view` / `paimon_function`
在 Iceberg 侧不写：分区由 manifest 决定（REST catalog 不逐分区登记）、Iceberg 没有消费位点、
没有语义视图，`FunctionMetadata` 的只读投影是二期。

## 2. 管理侧（7 张，RBAC + Paimon 原生权限）

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `paimon_principal` | principal | 主体；只保存密钥摘要，明文仅在创建 / 轮换时返回一次 |
| `paimon_principal_role` | principal role | 服务级角色，与 catalog 无关 |
| `paimon_catalog_role` | catalog role | 归属于某个 catalog 的角色 |
| `paimon_principal_role_grant` | 主体 ↔ principal role | 多对多关联 |
| `paimon_catalog_role_grant` | principal role ↔ catalog role | 多对多关联 |
| `paimon_resource_grant` | catalog role 的资源授权 | 权限取值 + 资源类型 + 命名空间 + 对象名 |
| `paimon_permission_assignment` | Paimon 原生权限的 principal 直授 | 官方词表 access + 资源定位符 + principal 原文 + 过期时间；与 `paimon_resource_grant` 是并行的两套授权来源 |

两级角色都是多对多，链路为「主体 → principal role → catalog role → 资源授权」。
`paimon_permission_assignment` 是第二路授权来源：授权直接面向 principal 字符串
（`user:alice` / `role:sales_writer`），判定时与 RBAC 链取并集，
见 [`authorization.md`](authorization.md) §2.5。

## 2.1 表存储优化（3 张）

三张表都**不是** Paimon 元数据的一部分，而是本服务端自己的运维记录：优化策略、优化批次、
批次里每张表的结果。它们挂在表实体上（`table_id`），但不参与 catalog API 的任何响应。

| 表 | 对应资源 | 说明 |
| --- | --- | --- |
| `paimon_table_optimization` | 表存储优化策略 | 与表 1:1（`uk_table_optimization`）：总开关、调度间隔、调度游标、三项动作各自的开关与**配置差异** |
| `paimon_optimization_job` | 优化批次 | 一个批次 = 一次 k8s 提交；组批以 catalog 为界，一个批次含多张表 |
| `paimon_optimization_job_item` | 批次内单表结果 | 批次 1:N；每张表的动作、状态、前后指标、错误原因 |

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
  走 `table_id` 反查两张表既慢又没必要。它与 `paimon_table.catalog_id` 由写入方保持一致。

对外接口一律用 catalog 名与「catalog.库.表」三段名，实体里存的是 id —— 转换只发生在
`OptimizationJobService` 一处，见 [`table-optimization.md`](table-optimization.md) §5。

---

## 3. 为什么有两个摘要列

`paimon_partition.spec_hash` 与 `paimon_resource_grant.namespace_hash` 是各自「键」字段的
SHA-256 定长摘要，唯一约束落在摘要上而不是原文上。原因是 MySQL 的索引键上限是
**3072 字节**（utf8mb4 下 768 个字符）：

- `spec_key` 声明为 1024 字符 → 与 `table_id` 相加远超上限；
- `namespace_key` 是命名空间路径的拼接，而规格对层级数没有上限，长度本就无界；
- 这两者与 `catalog_role_id` / `object_name` 等列合起来更放不下。

直接建索引会被 MySQL 拒绝：

```
ERROR 1071 (42000): Specified key was too long; max key length is 3072 bytes
```

改法是「原文入库 + 摘要入索引」：`spec_key` / `namespace_key` 保留，供等值查询使用；
唯一约束改用定长 64 字符的摘要，索引长度与值的实际长度解耦，唯一性语义不变。
摘要在 `@PrePersist` / `@PreUpdate` 里由原文重算（见 `Digests`），因此不依赖调用方是否记得规范化。

## 3.1 行大小上限：配置列用 `text` 而不是 `varchar(8192)`

§3 讲的是**索引键**上限（3072 字节），这里是另一个被同一件事放大的上限——
utf8mb4 按 4 字节/字符计入，于是**整行**不能超过 65535 字节。两者互相独立，
且都只在真库上才现形。

`paimon_table_optimization` 的三个配置列原本都是 `varchar(8192)`。单看每一列都合法
（8192 × 4 = 32768 < 65535），但同一张表放三个就合计 98304，建表直接失败：

```
ERROR 1118 (42000): Row size too large. The maximum row size for the used table type,
not counting BLOBs, is 65535. ... You have to change some columns to TEXT or BLOBs
```

改成 `text` 即可：Hibernate 按 `@Column(length)` 选类型，写成 65535 就是 `text`，
这类列只计入 9~12 字节的指针，整表预算从 102484 降到 4216 字节。

- **为什么不担心装不下**：这张表只存**差异**，键集由 `OptimizationOptions.KNOWN_KEYS`
  限定，值也都很短；65535 字节（utf8mb4 下约 1.6 万字符）远超需要。
- **`paimon_optimization_job_item.metrics_json` 保持 `varchar(8192)`**：那张表合计
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
- 超长时启动日志告警一次，给出的解法是配 `paimon.rest.auth.token-principals`
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
`paimon.rest.storage.credential-secret-key`，所以**换密钥等于换密文**：
旧密文解不开会明确报 500，需要重新提交一次凭据
（见 [`../README.md`](../README.md) 第 6 节「实现说明与已知边界」第 20 条）。
