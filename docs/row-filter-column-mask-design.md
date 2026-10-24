# 行过滤与列脱敏（Row Filter / Column Mask）技术方案

> 状态：**服务端 P0、Spark 引擎侧与服务端 AST 求值器均已实现**。
> 服务端：两张策略表 + 表达式 AST 校验 + 管理与下发端点 + 文件下载收口 +
> 表生命周期联动（`DataPolicyApiTests` / `DataPolicyDisabledTests`）。
> 引擎侧：`PolicyRelation` 逻辑节点 + PostHocResolution 注入 + `PolicyEnforcementStrategy` 物理展开 +
> `PolicyProjectExec`，3.5.9 / 4.1.2 双版本测试全绿（`PolicySparkTests`，见 §9.1 实测差异）。
> 求值器：预览按策略逐行求值（`PolicyExpressionEvaluator` + `PolicyPreviewTests`，§6 P1）。
> 元数据打码（§7.3）：带 mask 时快照 `statistics` 置空、预览分区回显对被脱敏分区列打码、
> catalog 级文件下载补上收口（`DataPolicyApiTests`）。
> §13 的七条 SQL 管理语句已实现（`PolicySqlTests`，3.5.9 / 4.1.2 双版本全绿；保留字加了
> `ROW` / `FILTER` / `MASK` / `POLICY`，`spark-sql-reference.md` §11 与 `verify-spark-sql-doc.py` 同步）。
> `$principal_roles` 占位符已实现（§5.3.4.1：行过滤按调用者的 principal role 过滤，
> 服务端 `DataPolicyApiTests` + 引擎 `PolicySparkTests` 双侧覆盖）。
> 控制台「数据策略」页签已实现（§8.3：表详情新页签 + 结构化编辑器 `TablePolicyPanel`，
> 保存即整份替换、409 不自动重试；三条端点登记 `verify-console.py` 扩展白名单，131 项全绿）。
> 控制台三屏已实现（§8.3）：数据保护总览页（`/policies`，全量拉取 + 本地切页）、
> 新建策略向导（`/policies/new`，与页签共用 AST 编辑器 `src/utils/policyAst.js`）、
> 主体视角试算（dry-run 端点 + 四态横幅；`DataPolicyApiTests` 覆盖折叠与四态）。
> P3 两项已实现（§5.8 / §7.6 更新块）：整列不可见 `hiddenColumns`（引擎侧
> `PolicyHiddenColumnsRule` + 服务端 `hidden_json` 与预览收口）、`EXPLAIN` 打码
> `PolicyOpaque`；P2 的 `SupportsAccessControl` / Trino 两项外部阻塞、ABAC 出设计
> 草案（§14，未实现）。
> 本文先回答「能不能做、做到什么程度、代价在哪」，实现取舍与设计稿的差异以代码为准。
>
> 关联文档：[`authorization.md`](authorization.md)（12 项 access 与判权链）、  
> [`table-data-preview-design.md`](table-data-preview-design.md) §10/§15（预览的判权与「行级/列级列为后续」两条开放项）、  
> [`spark-sql-extension.md`](spark-sql-extension.md)（Spark 扩展怎么装、两版怎么编）、  
> [`lineage-design.md`](lineage-design.md)（引擎侧抽取器的既有形态，本方案复用它的模块划分）。
>
> 外部依据：[SPARK-59726](https://issues.apache.org/jira/browse/SPARK-59726)（SPIP: Pluggable Row-Filter
> and Column-Mask Enforcement in Catalyst，目标 5.0.0）。本文与它的逐条差距见附录 A，三家生产实现的
> 交叉验证见 §2.2，Ranger 迁移映射见附录 B。
>
> 配套产品原型：[`row-filter-column-mask-mockup.html`](row-filter-column-mask-mockup.html)——五个画面
> （策略总览 / 新建向导 / 策略详情 / 主体试算 / 表详情页签），带可开关的设计标注，标注编号对应上文的取舍。
> 其中**画面 1（总览）、画面 2（向导）、画面 4（主体试算）、画面 5（表详情页签）已实现为控制台真实页面**
> （§8.3 与 [console.md](console.md) §4）；画面 3（策略详情）由表详情的「数据策略」页签承担，不再单独立页。

---

## 1. 结论

### 1.1 一句话

**Spark 3.5 与 4.1 都做得到，但都不是「打开一个开关」，而是要自己写一个 Catalyst 扩展；  
而且真正决定这套东西是「访问控制」还是「便利贴」的，不是 Spark，是凭据怎么发。**

### 1.2 三个版本的事实（先说清，后面所有设计都建立在这上面）

| 事实                                  | 依据                                                                                                                                                                                                     |
| ----------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Spark **没有**公开的行过滤 / 列脱敏 API        | 本机 jar 实测：`spark-catalyst` 的 `org/apache/spark/sql/connector/{read,write,expressions}` 下，3.5.9 / 4.1.2 / **4.2.0** 三个版本都**没有** `RowFilter`、`ColumnMask`、`AccessControl`、`SupportsAccessControl` 中的任何一个 |
| 官方把这件事列为待做特性，且已明确指向 **5.0.0**       | [SPARK-59726](https://issues.apache.org/jira/browse/SPARK-59726) SPIP「Pluggable Row-Filter and Column-Mask Enforcement in Catalyst」，`Affects Version: 5.0.0`，`Fix Version: None`，状态 Unresolved         |
| 官方同时承认「现在只能靠扩展注入，而且这么干是不安全的」        | 同上，提案原文：*「a masking projection added naively can be removed or collapsed by the optimizer, silently returning unmasked data」*                                                                          |
| Databricks 的能力来自**引擎 + 元数据服务两侧一起改** | 见 §2。不是 DataSource 层能单独实现的东西                                                                                                                                                                           |
| 官方明确把**信任边界**排除在这件事之外 | 同上 SPIP 的 Q2 第一条：*「It does not define where code runs or any client/server trust boundary」*。所以 **P2「切到官方 API」不会解决 §7.1**，别指望 5.0 |

所以「3.5 能不能 / 4.1 能不能」的答案是：**两个都能，且能力等价**——因为两边都得靠同一套扩展机制。  
版本差异不体现在「有没有这个能力」，只体现在注入点的签名差异与若干 SQL 默认值上（§9）。

### 1.3 能做到什么、做不到什么

| 能力                                 | 本方案能否做到    | 说明                                                        |
| ---------------------------------- | ---------- | --------------------------------------------------------- |
| 行过滤：按表达式裁剪可见行                      | ✅ 做得到（需扩展） | 注入到 Catalyst，`FALSE` 的行不可见                                |
| 列脱敏：读取时替换列值                        | ✅ 做得到（需扩展） | 类型必须与原列一致，否则下游 DataFrame 会炸                               |
| `SELECT *` / 子查询 / CTE / 视图 都不可绕过  | ✅ 做得到      | 在分析阶段统一注入，位置在所有展开之后                                       |
| 谓词不能反推原值                           | ⚠️ 有条件     | 只有当谓词被改写到「脱敏后」的语义上时成立；靠注入形态保证，见 §5.6                      |
| 表 owner / 管理员豁免                    | ✅ 做得到      | 服务端下发时按调用者身份直接给空策略，引擎侧不需要知道豁免逻辑                           |
| 写路径不受影响（写入存原值）                     | ✅ 做得到      | 脱敏只在读侧；写侧显式跳过                                             |
| **直读存储文件不绕过**                      | ❌ **做不到**  | 谁拿到 S3/HDFS 凭据，`spark.read.parquet(...)` 就是明文。见 §5.1、§7.1 |
| 换引擎（Flink / Trino / 自研程序）仍生效       | ❌ 本方案不覆盖   | 每个引擎都要各自实现一遍；见 §7.4                                       |
| 整列不可见（`COLUMN:SELECT` 有执行点）      | ❌ v1 不做     | 注入点晚于 `*` 展开，做它要动分析入口或改协议 schema；见 §5.8。**现在这条权限只存不判** |
| 不依赖具体 catalog（Paimon / Iceberg 都行） | ✅ 做得到      | 判据是「这个 relation 解析到哪个 catalog 的哪张表」，与格式无关                 |

> **这张表里最重要的不是那些 ✅，是第 7 行。** 它决定了本方案的定位必须写成  
> 「**引擎内强制 + 存储层不设防**」，而不是「数据只有这些人能看」。这个前提要在控制台上  
> 写出来，不能让使用者以为是后者。

---

## 2. 三家生产实现给出的坐标系

### 2.1 Databricks 的十条语义

把 Databricks 的做法拆成可实现的条目，比对照功能名有用：

| #  | 语义                              | 具体表现                                                                                                                                                                       |
| -- | ------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1  | 行过滤是一个**逐行布尔函数**                | `ALTER TABLE t SET ROW FILTER f ON (col)`；函数返回 `FALSE` 的行不出现在结果里                                                                                                           |
| 2  | 列脱敏是一个**值变换函数**                 | `ALTER TABLE t ALTER COLUMN c SET MASK g`；返回值必须与原列类型相同或可 cast                                                                                                              |
| 3  | 一个列最多一个 mask，一张表最多一个 row filter | 多个同时命中时直接报错，而不是挑一个执行                                                                                                                                                       |
| 4  | 函数可以引用**其它列**                   | 例如按 `department` 决定 `salary` 是否可见                                                                                                                                          |
| 5  | 只作用于**读**                       | 写入照常写原值；脱敏后的用户读回来的仍是脱敏值                                                                                                                                                    |
| 6  | 引擎在**优化与保护冲突时选保护**              | 原文：*"When the query engine must choose between optimization and protecting against information leakage from filtered or masked values, it always makes the secure choice"* |
| 7  | 行过滤看的是**脱敏前**的原值                | 脱敏后的值不参与行裁剪判定                                                                                                                                                              |
| 8  | 管理权归表 owner / `MANAGE`          | 普通用户不能给自己加豁免                                                                                                                                                               |
| 9  | ABAC 只是**同一机制的分发**              | 用 tag 匹配把同一份策略套到多张表，执行机制没变                                                                                                                                                 |
| 10 | 性能建议：函数简单、参数少、mask 数量少          | 因为「UDF 参数里的列引用无法被优化掉」                                                                                                                                                      |

**第 6、7 条是本方案的技术核心**：它把「性能」明确排在「不泄露」后面。任何「为了下推而放宽」  
的优化，在这个语义下都是错的。

第 10 条反过来提示我们：**如果表达式语言只允许内置标量函数，上面这些性能坑大半不存在**——  
Databricks 的代价主要来自任意 UDF，而我们的方案里没有 UDF（见 §5.3）。

### 2.2 Kyuubi Spark AuthZ（Ranger）与 EMR + Lake Formation 的对照

上一节只对着 Databricks 推导，容易被反问「那是商业实现，开源没有先例」。三家放一起看，
形状高度一致，差异集中在**最后一列**：

| 维度 | Databricks UC | Kyuubi + Ranger | EMR + Lake Formation | 本方案 |
| --- | --- | --- | --- | --- |
| 策略从哪来 | 表上绑 SQL UDF（`ALTER TABLE ... SET ROW FILTER`） | Ranger Admin，插件按 `pollIntervalMs` 定时拉取并本地缓存 | Glue 元数据里的 data filter，随 `GRANT` 绑定 | REST 端点 + 60 s TTL（§5.4） |
| 行过滤形态 | UDF 返回 boolean，`ON (cols)` | `rowFilterExpr` 字符串 + 用户/组/标签宏 | PartiQL 的 `WHERE` 子集 | AST JSON（§5.3.1） |
| 列脱敏形态 | UDF，首参是列，`USING COLUMNS` 可带其它列 | 枚举：`MASK` / `MASK_SHOW_FIRST_4` / `MASK_SHOW_LAST_4` / `MASK_HASH` / `MASK_NULL` / `MASK_DATE_SHOW_YEAR` / `CUSTOM` | 列包含 / 排除，可到嵌套子列 | 白名单表达式，**建议收敛为标准 mask 集**（§5.3.2） |
| 用户条件 | `current_user()` / `is_account_group_member()` | `current_user()` / `current_role()` / `is_role_in_session()`，规划期折叠 | 按 IAM 角色 / session tag 解析 | `$principal` 占位符，**下发前折叠成字面量**（§5.3.4） |
| 单元格级 | 支持（UDF 引用其它列） | 支持（`CUSTOM`） | **原生是一等能力**：列包含 + 行表达式 = 单元格过滤 | 用「参数引用其它列的 mask」表达（§5.3.5） |
| 整列不可见 | 靠 schema / 权限 | 靠列级 authz | 列排除 | **v1 不做**（§5.8），原因见下 |
| 嵌套字段 | `USING COLUMNS` 仍按整列 | 部分 | **原生支持** `customer.customerName` | 不支持，且**建策略即报错**（§5.7） |
| 执行位置 | 引擎内 + Lakeguard 隔离 | 引擎内（但引擎在 Kyuubi 网关里） | 引擎内过滤 + LF 贩卖凭据 | 引擎内，**凭据在客户端** |
| **信任边界** | 用户代码与引擎不共享进程（Spark Connect + 容器沙箱） | 用户只有 JDBC，Spark 会话不暴露 | **凭据唯一来源**：用户自己的 IAM 身份对受管 S3 无权限，只能拿 LF 按权限裁剪的临时凭据 | **没有**（§5.1 / §7.1） |
| 失败方向 | fail-closed（官方：*"always makes the secure choice"*） | 不能映射即拒 | 列被排除时**查询成功、该列返回 NULL** | fail-closed，与 LF 相反（§11） |

这张表里信息量最大的一行是「信任边界」。三家之所以敢叫「访问控制」，**不是因为它们注入得更巧，
而是因为用户拿不到存储凭据**：Kyuubi 的用户根本没有 Spark 会话，LF 的用户自己的 IAM 身份什么也读不到，
UC 则在进程级别隔离。注入机制三家几乎一样（都是 `SparkSessionExtensions` + 改写计划），
差的只是这一行。这就是下一节要量化的东西。

### 2.3 强制等级：本方案在第 0 级

把「能不能拦住」拆成四级，比笼统说「引擎内强制」有用：

| 级 | 形态 | 谁做到 | 前提 | 代价 |
| --- | --- | --- | --- | --- |
| **L0** | 引擎内改计划，**凭据在客户端手上** | **本方案** | 无 | 绕过只需一行 `spark.read.parquet()`（§7.1） |
| L1 | 网关代理：用户只有 SQL 连接，引擎在服务端 | Kyuubi + Ranger | 部署一个网关，**禁止直连 Spark 集群** | 多一个组件；所有访问必须收口到网关，运维习惯要改 |
| L2 | 凭据唯一来源 + 存储层拒绝旁路 | EMR + Lake Formation | bucket policy 只认 LF 贩卖的凭据；客户端 IAM 无直读权限 | 绑定云厂商的存储与元数据服务，换云即重做 |
| L3 | 隔离执行：用户代码与引擎不共享进程 | Databricks Lakeguard | Spark Connect + 容器沙箱 | 最贵，且要改用户的接入方式 |

两个必须说清的推论：

1. **从 L0 升到 L1 是本方案唯一现实的升级路径**，因为它不需要改引擎代码，只需要改部署形态
   （把 Spark 会话关进 Kyuubi，用户只能 JDBC）。要比 §7.1 里「自研 Arrow Flight 代理扫描」便宜一个数量级。
2. **切到官方 `SupportsAccessControl` 是横向移动，不是纵向升级。** SPIP 的 Q2 第一条把
   client/server 信任边界明确排除在范围外，所以它落地之后我们仍然在 L0。

---



## 3. Spark 侧现状

### 3.1 没有公开 API，只能注入 Catalyst

官方原文已经说得很直接：今天要让一个 catalog 把访问策略挂到 scan 上并被正确执行，**没有受支持的 API**。  
现有做法有两类：

| 做法                              | 代表                                                                              | 问题                                             |
| ------------------------------- | ------------------------------------------------------------------------------- | ---------------------------------------------- |
| 扩展注入 Catalyst 规则，用包私有内部 API 改计划 | Apache Ranger 的 Kyuubi Spark AuthZ 插件、Iceberg 的一个 POC、阿里云 AnalyticDB 的 Spark 版本 | 跨版本容易断（内部 API 不保证兼容），且**注入方式不当时优化器会把 mask 吃掉** |
| 等官方 `SupportsAccessControl`     | SPARK-59726（目标 5.0.0）                                                           | 现在没有；我们的 3.5 / 4.1 都等不到                        |

结论：**要现在就有，只能选第一类**，并且把「mask 不被优化器吃掉」当成第一设计约束，  
而不是实现细节。

### 3.2 注入点：两版都够用，签名有差异

这是 `SparkSessionExtensions` 上 3.5.9 与 4.1.2 的实测差异（`javap` 逐个比对）：

| 注入点                                  | 3.5.9 | 4.1.2           | 本方案用途                                                 |
| ------------------------------------ | ----- | --------------- | ----------------------------------------------------- |
| `injectParser`                       | ✅     | ✅               | 已有（管理语法）                                              |
| `injectResolutionRule`               | ✅     | ✅               | 备选：分析早期                                               |
| `injectPostHocResolutionRule`        | ✅     | ✅               | **主注入点**：全部分析完成、`*` 已展开、视图/CTE 已内联                    |
| `injectCheckRule`                    | ✅     | ✅               | 兜底断言（解析期）                                             |
| `injectOptimizerRule`                | ✅     | ✅               | 不用——优化器规则会重复执行                                        |
| `injectPlannerStrategy`              | ✅     | ✅               | 自定义节点落地成物理计划                                          |
| `injectQueryPostPlannerStrategyRule` | ✅     | ✅               | 物理计划后置校验（可选）                                          |
| `injectHintResolutionRule`           | ❌     | ✅               | 不用                                                    |
| `injectFunction` 的参数类型               | `Seq` | `immutable.Seq` | **只有要用自定义函数时才撞上**：共享源码里得按版本各编一次（现有 3.5/4.1 双模块正是干这个的） |

> **为什么主注入点选 `injectPostHocResolutionRule` 而不是 `injectResolutionRule`：**  
> `Resolution` 批量里 `SELECT *` 还没展开成具体列、视图还没内联完。此时改写等于要自己处理  
> `Star`、`UnresolvedRelation`、`UnresolvedAttribute` 三种未解析形态——每一种都是一个漏判点。  
> `PostHocResolution` 批次之后，计划里只剩已解析的 `Attribute`，漏判面小得多。

### 3.3 为什么不能只加一个 `Project`

这是本方案最关键的技术判断，展开在 §5.6。先记住一条：  
**`Project(mask(col) AS col)` 这种「裸投影」在优化器眼里是可推导、可合并、可下推的普通表达式**，  
而 Spark 有 `CollapseProject`、`ColumnPruning`、`PushPredicateThroughNonJoin` 等一整批规则会动它。

### 3.4 Kyuubi 插件留下的四条具体教训

Kyuubi 的 AuthZ 插件是目前开源里跑得最久的同类实现（Ranger 策略 → 注入 Catalyst），它的 issue 与
线上经验比文档更有用。四条都直接影响本方案：

| # | 它踩的坑 | 对我们的意义 |
| --- | --- | --- |
| 1 | **DataSourceV2 是真空区**。[#3186](https://github.com/apache/kyuubi/issues/3186)：`RuleApplyRowFilterAndDataMasking` 覆盖 `HiveTableRelation` 和 `LogicalRelation`，但**不覆盖 `DataSourceV2Relation`**，于是 Iceberg 表的策略「写了、不生效、也不报错」 | 我们的表**全是 V2**（Paimon / Iceberg），这条不是参考而是主战场。更重要的推论：**漏判的默认症状是静默放行**，所以 §5.6 的 fail-closed 断言不是洁癖，是唯一防线 |
| 2 | 「没生效」和「没策略」在引擎侧长得一模一样 | 需要一个可观测判据：`EXPLAIN EXTENDED` 里出现 `PolicyRelation` 才算注入成功。这是 §10.2 第 13 条的来源 |
| 3 | 它保留了一个 `RowFilterAndDataMaskingMarker` 节点防重复注入；同时把行过滤**下推进了 `BatchScan.filters`**（`filters=id IS NOT NULL, id = 1`） | 前半段与 §5.6 方案 B 同构，说明这个形状不是我们发明的。后半段给了个重要区分：**策略行过滤下推到 scan 是好的**（scan 读的就是原值，能省 IO），**用户谓词穿过 mask 下推是坏的**。两件事必须分开处理（§5.7） |
| 4 | 它要靠 `spark.sql.catalogImplementation=hive` 才能解析 masking UDF；`MASK_HASH` 委托 Hive 的 `mask_hash`，算法随 `hive.masking.algo` 变（默认 SHA-256、可设 SHA-512、Hive 2.x 是 MD5） | 同一份策略在两个集群给出**不同的 hash**，而这类差异不报错。我们不用 UDF（内置表达式 + 算法写进参数）天然绕开，但要把「算法显式」升级成硬规则（§5.3.3） |

> 第 4 条的镜像经验：既然不用 UDF 就躲开了这些坑，那就**不要在 P1「顺手支持一下自定义函数」**。
> 那是把 Kyuubi 的全部问题重新买回来的最直接方式。

---

## 4. 参考实现踩过的坑

阿里云 AnalyticDB 的 Spark 版本是**策略 DDL + 用户绑定 + 分析阶段注入**这一路里文档最细的实现
（Kyuubi 的运维经验见 §3.4，三家横向对照见 §2.2）。它的限制清单几乎每条都值得抄进我们的验收项：

| 它的做法 / 限制                              | 对我们的意义                                        |
| -------------------------------------- | --------------------------------------------- |
| 分析阶段注入，子查询 / CTE / 视图（含临时视图）都绕不过       | 与 §3.2 的选择一致                                  |
| JOIN 里只对**绑定了策略的表**生效，未绑定的表不受影响        | 掩码是「表的属性」，不是「查询的属性」                           |
| 只覆盖**内置 catalog**，外部 catalog 的表不在范围内   | 我们正好相反：外部 catalog 是常态，必须按 relation→catalog 解析 |
| 默认**关闭**，且关闭时返回明文（fail-open）           | **我们要反过来**：默认关闭但一旦开启就 fail-closed（见 §5.4）     |
| 表达式只允许纯标量：禁聚合、禁窗口、禁 `DISTINCT`、禁引用其它表列 | 直接采用，且我们连 UDF 也禁（§5.3）                        |
| 脱敏后列的**类型可能变化**                        | **我们不接受**：类型必须与原列完全一致（§5.3.3）                 |
| 脱敏只影响读，写照常                             | 采用                                            |
| 策略变更有缓存，其它已启动的应用**最长 5 分钟**才生效         | 我们的默认 TTL 取更小值（60 s），并明确 `CACHE TABLE` 需手工失效  |
| 同表同列同用户命中多条策略时**报错中止**（fail-closed）    | 采用同样的「宁可拒绝，不要猜」                               |

---

## 5. 总体设计

### 5.1 分层与信任边界

```
        ┌──────────────────────────────────────────────────────────┐
        │ 控制面（本服务）                                          │
        │  · 策略的存储、版本、审计                                 │
        │  · 谁可以定义 / 谁被豁免（按调用者身份下发）               │
        │  · 不接触数据                                             │
        └───────────────┬──────────────────────────────────────────┘
                        │ ① 拉策略（带版本号，短 TTL）
        ┌───────────────▼──────────────────────────────────────────┐
        │ 引擎（Spark 扩展）                                        │
        │  · 把策略注入 Catalyst，生成带 mask/filter 的计划          │
        │  · 唯一执行点                                             │
        └───────────────┬──────────────────────────────────────────┘
                        │ ② 读文件（用 catalog 下发的存储凭据）
        ┌───────────────▼──────────────────────────────────────────┐
        │ 存储（S3 / HDFS / OSS）                                  │
        │  · 不做任何拦截                                           │
        └──────────────────────────────────────────────────────────┘
```

**这是本方案最重要的一张图**：②那一步的凭据一旦落到客户端手上，①③就都白做了。  
所以信任边界必须写清楚：

> **本方案的强制范围 = 「通过受控引擎、且引擎拿到了策略」的读取。**  
> 越过引擎（直读文件、换引擎、拿凭据写自己的程序）不在范围内，且**无法**在本层解决。

要真正收口，只有三条路，都不便宜（§7.1）：不下发凭据、上代理扫描（Arrow Flight / 自研 scan 服务）、  
存储层列级加密。**本方案不承诺其中任何一条**，只承诺把这个边界显式暴露出来。

### 5.2 数据模型

新增两张表（沿用 `AuditedEntity` 的审计列与 `lakehouse_` 前缀）：

| 表                       | 粒度             | 关键列                                                                                                            |
| ----------------------- | -------------- | -------------------------------------------------------------------------------------------------------------- |
| `lakehouse_data_policy` | 一行 = 一张表的一套策略  | `catalog_name` / `database_name` / `table_name`、`row_filter_json`（可空）、`version`（乐观锁 + 下发用的 etag）、`enabled`、审计列 |
| `lakehouse_column_mask` | 一行 = 一列一个 mask | `policy_id`、`column_name`、`mask_json`、审计列                                                                      |

设计取舍：

1. **不为「绑定用户」单独建表。** Databricks 的 ABAC 是「策略 × 主体 × 标签」，我们第一版只做  
   「表级一套策略 + 豁免名单」——豁免名单存 `lakehouse_data_policy.exempt_principals`（逗号分隔）。  
   理由：本仓的权限模型是「唯一的三元组」，再引入一套「策略 × 主体」的笛卡尔积会让判权出现  
   第二条路径，而 `docs/authorization.md` 花了一整节说明「只有一条路径」的价值。
2. **策略不是表属性。** 不写进 Paimon / Iceberg 的 table properties。理由有三：两种格式的属性合并  
   语义不同（`table-default.*` 只认前缀且 `putIfAbsent`）；表属性对所有客户端可见，会泄露  
   「这张表被保护了、保护成什么样」；DDL 一旦允许改属性，就等于允许 `ALTER TABLE` 改策略，  
   绕过了策略自己的判权。
3. **版本号必须下发。** 客户端缓存策略，就必须能判断「这份缓存过期了没有」。`version` 同时用作  
   乐观锁（改策略时 `WHERE version = ?`），一列两用。
4. **策略按「三名字符串」绑定，所以表生命周期必须联动。** Paimon 没有稳定的 table id，Iceberg 的
   `table-uuid` 又不能跨格式统一，第一版只能按 `catalog.database.table` 绑。后果很具体：
   `DROP TABLE` 之后同名重建，新表会**继承旧策略**——可能是意外保护，也可能是意外放行（旧策略
   引用的列已经不存在，于是 fail-closed 把表读死）。处置：在 catalog 的 DDL 处理里挂三条钩子
   （DROP 级联删策略、RENAME 跟随改名、CREATE 不继承），并且把「漏钩子」的最坏结果交给 §5.6 的
   fail-closed 兜。
5. **schema 变更要校验策略。** `DROP COLUMN` 掉一个有 mask 的列、`RENAME COLUMN` 改名，都会让策略
   悬空。前者拒绝（要求先删策略），后者跟随。理由同 §5.6：悬空策略的最坏结果不是报错，而是
   「列没了所以 mask 也没了」——静默降级。

### 5.3 表达式语言

#### 5.3.1 存 AST，不存 SQL 文本

策略里存的是**结构化表达式**（JSON），不是 `"dept = 'x'"` 这样的字符串：

```json
{"op": "eq", "left": {"col": "dept"}, "right": {"lit": "APAC"}}
```

| 存 SQL 文本                                             | 存 AST（选定）                                  |
| ---------------------------------------------------- | ------------------------------------------ |
| 客户端要自己 parse，parse 完还得校验 AST——两次工作，且两侧 parser 版本可能不同 | 客户端直接构造 Catalyst `Expression`，没有 parse 这一步 |
| 注入面：一段 `1=1 OR ...` 或子查询混进来，只靠字符串检查挡不住               | 结构上就不存在「子查询」「函数调用」这些形态                     |
| 服务端预览要重新实现一个求值器时，还得先 parse                           | 服务端求值器遍历同一棵 AST 即可（§6）                     |

#### 5.3.2 允许的算子（白名单）

| 类别      | 允许                                                                     | 说明                     |
| ------- | ---------------------------------------------------------------------- | ---------------------- |
| 引用      | 本表列、常量                                                                 | 不允许引用其它表的列             |
| 比较 / 逻辑 | `=` `<>` `<` `<=` `>` `>=`、`AND` `OR` `NOT`、`IN`                       |                        |
| 判空      | `IS NULL` / `IS NOT NULL`                                              |                        |
| 字符串     | `substr`、`concat`、`upper`、`lower`、`trim`、`regexp_replace`、`md5`、`sha2` | 全部是 Spark 内置确定性函数，两版都有 |
| 条件      | `CASE WHEN`、`coalesce`                                                 |                        |
| 类型      | `cast`（只允许放宽，如 `int→bigint`、任意→`string`）                               |                        |
| 占位符     | `$principal`、`$principal_roles`                                        | **服务端下发前替换成字面量**，见下    |

**禁止**：聚合、窗口、子查询、`DISTINCT`、非确定性函数（`rand`/`uuid`/`current_timestamp`）、  
自定义 UDF（Spark 侧根本没有服务端持久函数可调）、跨表引用。

上一版白名单漏了一整类：**日期/截断**。Ranger 的 `MASK_DATE_SHOW_YEAR`、SPIP 的
`TRUNCATE_TO_YEAR` / `TRUNCATE_TO_MONTH` 都是「只留年月」，而原来的算子表里连一个日期函数都没有，
这个最常见需求**根本写不出来**。补上：`date_trunc`、`year` / `month`（配合 `make_date` 重建）、
`to_date`、`date_format`。仍遵守「全部是两版都有的内置确定性函数」。

#### 5.3.2.1 建议把 mask 收成「标准集 + 逃生口」

参照 SPIP 的 `ColumnMasks`，mask 允许两种形态，前者优先：

```json
{"fn": "show_last_n", "args": [4]}      // 标准集：控制台是下拉 + 一个数字
{"expr": {"op": "concat", ...}}         // 逃生口：白名单表达式
```

标准集直接采用 SPIP 的名字，不自己发明：`mask_alphanum`、`show_first_n`、`show_last_n`、
`replace_with_null`、`mask_to_fixed_value`、`truncate_to_year`、`truncate_to_month`、`hash(算法)`。

三个理由：

1. **将来切换成本＝改名字，不是重构。** P2 把 `{fn, args}` 映射成 `ColumnMask(function(), arguments())`
   是一行转换。
2. **和 Ranger 的枚举能一一对照**（附录 B），迁移已有 Ranger 策略时有对照表可查，不用逐条读 SQL。
3. **控制台的默认路径从「自由表达式编辑器」变成「下拉 + 一个数字」**，注入面进一步缩小；
   需要 `CUSTOM` 的人才走逃生口，而逃生口本来就在白名单里被限死。

`mask_alphanum` 的字符类规则（大写→`X`、小写→`x`、数字→`n`，逐字符）与 Hive `mask()` 一致，
所以 Ranger 的 `MASK` 能原样对上——这条要在实现时按字符逐个核，别用正则一次性 replace（Unicode 会漏）。

#### 5.3.3 类型规则

| 规则                                   | 为什么                                                                                                                            |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| mask 结果类型必须**等于**原列类型（不接受「可 cast」）   | Databricks 允许 cast，代价是下游 `DataFrame` 的 schema 变了：`df.schema` 里那列类型变了、`case class` 反序列化直接炸。我们的定位是「对用户透明」，所以宁可在建策略时校验失败，也不接受隐式变型 |
| mask 结果一律**可空**，即使原列 `NOT NULL`      | 最常见的脱敏就是「置 NULL」。原列非空但脱敏后出现 NULL，是策略语义，不是数据质量问题。代价：下游若依赖非空约束会受影响。**但原列是 `NOT NULL` 时，置空要显式确认**——见下方与 SPIP 的冲突 |
| 行过滤表达式的结果类型必须是 `Boolean`，且不可为 `NULL` | `NULL` 在 `WHERE` 里等价 `FALSE`，但让人分不清「被过滤」和「表达式写错了」。建策略时要求显式 `coalesce(expr, false)`                                             |
| mask 与 row filter 必须是**确定性纯函数**，算法写进参数      | 见 §3.4 第 4 条：Ranger 的 `MASK_HASH` 随 `hive.masking.algo` 变，同一策略在两个集群给出不同 hash，且不报错。所以 `hash` 必须带算法参数，**任何「读集群/session 配置」的函数一律不允许** |

**`NOT NULL` 列 + 置空：与 SPIP 冲突，必须选边。** SPIP 的 fail-closed 清单里明确写了
「`REPLACE_WITH_NULL` on a required column aborts」，而本文档原先的口径是「一律允许，放宽 nullability」。
取舍如下：**在策略创建时要求显式确认**（请求体带 `allow_null_on_required: true`），默认拒绝。
理由：放宽 nullability 对读侧是安全的（只会让下游更保守），直接硬拒会让「身份证号置空」这个最常见的
需求变成不可能；但默认允许又会产出 P2 切官方 API 时**注定失效**的策略。让操作者勾一次，
比让他在 5.0 升级当天才发现策略不生效好。

**mask 参数引用其它列 → 展开一律按 child 全量 output。** 例如 `SELECT ssn`、而 `ssn` 的 mask 引用了
`region`：按上层投影裁剪会缺列。`PolicyRelation` 是不透明节点，`ColumnPruning` 穿不过去，所以只能由
我们自己保证——`Strategy` 里展开时**以 child 的完整 output 生成 Project**，让上层的裁剪自己裁。
代价是多算几列，属于 Databricks 说的 "secure choice"。

#### 5.3.4 `$principal` 占位符（不做 UDF 也能做「看自己的行」）

Databricks 的 RLS 几乎都长这样：`dept = current_user()`。我们没有可下发的 UDF，所以换成占位符：

```
row_filter: {"op": "eq", "left": {"col": "owner"}, "right": {"principal": true}}
```

服务端在**下发时**把它替换成调用者主体名的字符串字面量。三个好处：

1. 不需要注册任何函数（避开 §3.2 那个 `injectFunction` 签名差异），两版共用一份源码；
2. 客户端拿到的已经是字面量，改不了「我是谁」；
3. 服务端预览（§6）用同一份替换规则，双端语义天然一致。

代价：策略缓存必须**按主体分片**（不同人拿到的表达式不同）。这本来就是必需的——豁免名单也是按人算的。

#### 5.3.4.1 `$principal_roles`：把「组」折成角色名列表

`$principal` 解决「看自己的行」，但 Databricks 的 RLS 还有一半长这样：
`is_account_group_member('cn_reader')`——按**组**放行。我们没有可下发的组判断函数，
用第二个占位符补上：

```json
{"op": "in", "operand": {"col": "dt"}, "values": {"principal_roles": true}}
```

语义：**`$principal_roles` 在下发时折成调用者持有的 principal role 名单**（裸名、去重、
按字典序排），即 `{region} IN ['cn_reader', 'vip']`。三条钉死的规则：

1. **位置只有一处：`in` 的 `values`。** 它是字符串列表，出现在任何别的位置（比较的一侧、
   mask 参数、`CASE WHEN` 分支）语义要么含糊要么永远不成立——校验期就指名拒绝，不留给
   折叠期硬失败。因此它和 `$principal` 一样只允许出现在行过滤里（mask 读的是明文原值，
   按调用者变 mask 等于脱敏结果随人漂移）。
2. **名单为空折成字面量 `false`，整条 `in` 消失。** 「不属于任何角色」的正确方向是
   一行都看不到，不是报错也不是放行——fail-closed 的方向不会错。名单在
   `AuthorizationService.principalRoleNames(principal)` 算出来（裸名，区别于判权用的
   `role:` 前缀候选串）。
3. **引擎与求值器零改动。** 折叠发生在服务端（resolve / 预览共用 `foldPrincipal`），引擎
   收到的已经是 `in + 字面量列表`；两侧各留一条「占位符原样到达 → 指名拒绝」的防御分支。
   策略缓存本来就按 `(catalog, database, table, principal)` 分片，角色名单跟着主体走，
   缓存键不用动。

与 `$principal` 的一个差别：**SQL 侧写不出这个占位符**——`$` 不是 Spark SQL 标识符的
合法字符，§13 的表达式通道进不来；它只能从 REST API（最终是控制台）写入。这不是缺陷：
按主体装配角色本就是治理面的操作，把入口收在治理面是刻意的。

#### 5.3.5 求值顺序：单层投影，所有 mask 读原值

```
Project( mask_1(a1) AS a1, mask_2(a2, ref=b) AS a2, ... )   ← 单层；所有参数读原值
  Filter( rowFilter )                                        ← 读原值
    Scan
```

三条规则，都要写进验收：

1. **所有 mask 在同一层 Project 里，参数一律读原值，不链式。** `mask_2` 看到的 `b` 是原始 `b`，不是
   `mask_b(b)`。若允许链式，结果就依赖列的字母序或策略插入顺序——同一个策略重建两次给出不同结果，
   这种 bug 没法复现也没法解释。SPIP 的 `Project(masks, Filter(...))` 形状也是一层，语义一致。
2. **因此「单元格级安全」不需要新概念。** Lake Formation 的「列包含 + 行表达式 = 单元格过滤」，
   等价于「mask 的参数引用其它列」：`mask(col, ref=region)` 就是 `CASE WHEN region='US' THEN col
   ELSE NULL END`。LF 把它做成一等能力（还支持嵌套子列），我们用这一条组合表达，语义覆盖得住，
   只是语法更长。
3. **行过滤不能引用脱敏后的值。** `Filter` 在 `Project` 下面（§5.5），所以「先脱敏再筛」写不出来。
   要那个效果只能把条件塞进 mask：`CASE WHEN ... THEN col ELSE NULL END`，而不是两级 Filter。
   这不是缺陷——两级 filter 的中间结果会以「行数」的形式泄露原值分布。

### 5.4 下发与缓存

| 项             | 取值                                                                        | 理由                                                                                 |
| ------------- | ------------------------------------------------------------------------- | ---------------------------------------------------------------------------------- |
| 通道            | 新增只读端点 `GET /api/management/v1/policies?catalog=&database=&table=`（以及批量版） | 不放 `loadTable` 响应：那会让「策略」变成 Paimon / Iceberg 协议的一部分，两种格式都要改规格                      |
| 携带            | 表全名 → `{version, rowFilter, masks[], exempt}`                             | 一次请求可批量问多张表（一条 SQL 常常扫好几张）                                                         |
| 缓存            | 进程内，键 `(catalog, database, table, principal)`，TTL 默认 **60 s**，可配 `0` 关闭   | 与阿里云那套「5 分钟」相比更短：策略是安全配置，不能长时间不一致                                                  |
| 失败策略          | **fail-closed**：拿不到策略 → 该表**读失败**并给一条明确错误                                 | 与阿里云默认 fail-open 相反。理由：fail-open 的失效方向是「明文泄露」，fail-closed 的失效方向是「读不了」，后者可以修，前者收不回来 |
| 逃生开关          | `spark.lakehouse.rest.policy.fail-open=true`                              | 只在「策略服务临时挂了但业务不能停」的场合用；打开时每次查询打 WARN，日志里能查                                         |
| `CACHE TABLE` | 不自动失效；文档 + 控制台提示「改策略后要 `CLEAR CACHE`」                                     | 缓存里存的是脱敏后的数据，换策略后旧缓存仍然「安全但过期」——是正确性问题，不是泄露问题                                       |
| 生效延迟          | 最坏 = TTL                                                                  | 控制台改完策略提示「最多 60 秒后对已启动的会话生效」                                                       |


> **为什么不用 `version` 做每次查询的强校验：** 那等于每条 SQL 加一次 HTTP 往返，而且  
> 查询路径上多一个失败点。60 s 的窗口是显式接受的取舍，写在文档里比藏在实现里好。

### 5.5 引擎侧怎么改计划

```
                    PostHocResolution
                          │
   已解析计划 ────────────► 找 DataSourceV2Relation / LogicalRelation
                          │    ↓ 解析到 (catalog, database, table)
                          │    ↓ 查策略（缓存/HTTP，按 principal 分片）
                          │
                          ├─ 无策略 ──────────────────────► 原样返回
                          │
                          └─ 有策略 ──► PolicyRelation(原 relation, rowFilter, masks)
                                              │
                                    Optimizer（不认识这个节点 → 不动它）
                                              │
                                    PlannerStrategy
                                              │
                                              ▼
                              Filter(rowFilter)                    ← 先裁行（看原值）
                                Project(col1, mask2(col2) AS col2) ← 再脱敏
                                  Scan
```

三个必须钉住的顺序与形态：

1. **先裁行、再脱敏。** 行过滤表达式里可能引用**被脱敏的列**（`dept = 'x' AND salary > 100`）。  
   若先脱敏，`salary` 已被替换成 NULL，行过滤的结果就变了。Databricks 的第 7 条语义也是这个。
2. **行过滤表达式只能看到原值**，因此 `Filter` 必须在 `Project(mask)` **下面**。
3. **`PolicyRelation` 是不透明节点**：优化器没有它的规则，所以既不会被删、也不会被推穿（§5.6）。

### 5.6 为什么必须是自定义逻辑节点（三个方案对比）

| 方案                            | 形态                                                     | 优化器会怎么对它                                                                                                                                                             | 结论                                                                                                |
| ----------------------------- | ------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| A. 裸投影                        | `Project(mask(a) AS a)` 直接盖在 relation 上                | `ColumnPruning` 可能裁掉未引用的 mask（这个还好）；`PushPredicateThroughNonJoin` 会把用户的 `WHERE` **改写到 mask 下方**；`CollapseProject` 会把它和用户投影合并。每一步都「语义正确」，但**边界条件极难穷举**，任何一处漏判都是静默返回明文 | ❌ 不作为强制路径                                                                                         |
| B. 自定义逻辑节点 + Strategy（**选定**） | `PolicyRelation` 只描述「这里有策略」，落地时才展开成 `Filter + Project` | 优化器不认识它 → **没有任何规则能动它**；展开发生在策略之后的 planning 阶段，展开形态由我们完全控制                                                                                                           | ✅ 代价是要写 `LogicalPlan` 子类 + `Strategy`，并把 `output` / `computeStats` / `maxRows` 这些必须实现的东西补齐        |
| C. 等 `SupportsAccessControl`  | connector 直接声明策略                                       | 官方保证「mask 不会被优化掉」                                                                                                                                                    | ⏳ 目标 5.0.0，我们现在用不上。但**接口形状故意与它对齐**（`readableColumns` / `rowFilter` / `columnMasks` 三个概念），将来切换成本最低 |

> 方案的 A 不是「不够优雅」，是**不安全**。SPARK-59726 之所以存在，就是因为这条路上  
> 「naive 的注入会被优化器吃掉」已经是公认事实。我们如果走 A，就必须自己重写一遍那些规则——  
> 那不如直接写 B。

**B 的实现要点（写在这里，避免实现时漏）：**

- `PolicyRelation` 的 `output` 必须**等于**子节点的 `output`（列名、顺序、类型完全不变，只有  
  nullability 可放宽）。否则用户 `SELECT *` 的 DataFrame schema 会变，`case class` 反序列化会炸。
- `Strategy` 里展开成 `Project(masks, Filter(rowFilter, child))`，并对「同一张表在计划里出现多次」  
  （自连接、子查询重复引用）各自展开一份——**不要试图共享**，`ReuseExchange` 会在物理层做该做的事。
- 幂等：给已处理的子树打 `TreeNodeTag`（3.5 与 4.1 都有），避免「视图被内联后又分析一遍」  
  导致 mask 套两层（`mask(mask(x))` 在 `CASE WHEN` 类表达式上不是幂等的）。
- **fail-closed 断言**：在 `Strategy` 里如果解析不出表名、或表名存在但策略拉取失败、  
  或表达式里的列在 schema 里找不到——**抛错，不要跳过**。跳过等于放行明文。

### 5.7 语义对齐表

| 场景                                       | 行为                        | 备注                                                      |
| ---------------------------------------- | ------------------------- | ------------------------------------------------------- |
| `SELECT *`                               | 全列脱敏                      | 注入点在 `*` 展开之后                                           |
| `SELECT count(*)`                        | 行过滤生效，无列可脱敏               |                                                         |
| `WHERE` 落在被脱敏列上                          | 在**脱敏后的值**上比较             | 与 Databricks 的 secure choice 一致：`WHERE ssn='真实值'` 应当查不到 |
| `WHERE` 落在被脱敏列、且引擎想下推到 scan              | 改写为 `mask(col) = 'x'` 再下推 | 结果集等价，且**不泄露**：用户学到的是「脱敏值等于 x 的行」，不是原值                  |
| `JOIN`                                   | 只对带策略的那一侧生效               | 未带策略的表不受影响                                              |
| 子查询 / CTE / 视图（含临时视图）                    | 生效                        | 注入点在分析之后，这些都已内联                                         |
| `DESCRIBE` / `SHOW COLUMNS`              | 不脱敏（元数据）                  | 列名本身敏感时用既有的 `COLUMN` 级 `SELECT` 控制                      |
| `INSERT` / `UPDATE` / `DELETE` / `MERGE` | **不套策略**                  | 写侧看到原值。行过滤套到 DML 上会出现「看不见所以删不掉 / 改不了」                   |
| `INSERT INTO t SELECT * FROM t`          | 读到的是脱敏后的值                 | 这是用户自己选的语义，不是漏洞；文档写明                                    |
| `CACHE TABLE` 后再改策略                      | 旧缓存仍生效，直到 `CLEAR CACHE`   | 正确性问题，非泄露                                               |
| 广播 join（AQE 把表广播了）                       | 广播的是**脱敏后**的数据            | 因为广播发生在物理层，读的是已经 mask 的 scan                            |
| 整列不可见（`readableColumns`）                | **v1 不做**（§5.8）         | 显式 `SELECT col` 仍返回脱敏值，不是拒绝                |
| 嵌套字段（`struct.field`）                    | **不支持；建策略时报错**           | 静默忽略是最坏结果：用户以为保护了，实际没有               |
| 单元格级（列内部分行为 NULL）                     | 用「参数引用其它列的 mask」表达        | 对应 LF 的 cell-level security（§5.3.5）            |
| `EXPLAIN` / `EXPLAIN EXTENDED`           | 计划里会露出策略表达式文本             | 见 §7.6；逻辑计划只露一行 `PolicyRelation`，物理计划会展开   |

### 5.8 整列不可见为什么 v1 不做

`readableColumns()` 的语义要求两件事：`SELECT *` 只返回可读列、显式引用不可读列**直接失败**。
前者的前提是在 `*` **展开时**就知道哪些列不可读，而本方案的注入点是 `PostHocResolution`——
那时 `*` 已经展开完了（这正是 §3.2 选它的理由）。要在展开前裁剪，只有两条路：

| 路 | 做法 | 代价 |
| --- | --- | --- |
| A | 回到 `injectResolutionRule`，在 `Star` 还没展开时处理 | 把 §3.2 刻意避开的三种未解析形态（`Star`、`UnresolvedRelation`、`UnresolvedAttribute`）全部接回来，漏判面从「已解析 Attribute」退回到「三种未解析节点」 |
| B | 在 catalog 侧按主体裁 schema（`loadTable` 返回的 schema 少几列） | 干净，但等于把策略塞进 Paimon / Iceberg 的协议响应，违背 §5.2 取舍第 2 条，而且两种格式都要改 |

两条都不适合和 P0 挤在一起，所以 **v1 明确不做**。但这件事必须写进文档，因为它是
[`authorization.md`](authorization.md) §11 记录的那个已知空洞：

> 「列级赋权不参与表级判定，也没有列级端点。……所以这条能力目前只用于表达『授了哪几列』。」

也就是说 **`COLUMN:SELECT` 现在只存不判**。行过滤/列脱敏这套扩展正好是它的天然执行点，但只覆盖了
一半（脱敏 ≠ 不可见）。所以要说清：**启用本方案不等于把列级权限落实了**。列级不可见列为 P3，
做完之后 `authorization.md` §11 那条要改。

> **2026-10-24 更新：P3 已实现（`hiddenColumns`），但走的是独立能力，没有把
> `COLUMN:SELECT` 赋权接进来。** 实现要点与当年 §5.8 的判断的出入：
>
> - **注入点不变，仍是 `PostHocResolution`。** `*` 展开完的形态下，星号与
>   「显式全宽普通列表」（逐位等价 `relation.output`、全裸属性、无别名）无法区分——
>   这是选了 A 路也不可避免的。实现为两遍扫描（`PolicyHiddenColumnsRule`，注册在
>   `PolicyInjectionRule` 之后）：① `trimStars` 把全宽普通投影裁到可读列，其余显式
>   引用（投影/聚合/过滤/子查询）`PolicyEnforcementException` 指名列与表；② `curtail`
>   给带 hidden 的 `PolicyRelation` 自动包 `Project(readable)`，兜住
>   `spark.table()` 无投影读法与 join 侧的悬空引用（由 `CheckAnalysis` 抛
>   `AnalysisException`）。
> - **歧义取舍**：显式全宽列表按星号处理（被裁）。被裁不泄露；比 fail-closed
>   误伤每一条普通 `SELECT` 小得多。写进了规则类注释与本节。
> - **写路径跳过**（4.1 实测形状）：INSERT 顶层是 `AppendData`、UPDATE/DELETE
>   分析期重写为 `ReplaceData`，V1 还有命令形态。判据 =「`Command` 基类
>   （`ExplainCommand` 显式排除——EXPLAIN 的 child 要照常处理）+ V2 写节点全集」。
>   CTAS 因此与 `INSERT INTO t2 SELECT *` 同口径（写路径跳过、全列进新表，
>   需要 CREATE 特权）。
> - **与 mask 同列互斥**（服务端建策略时 400 指名拒绝）；行过滤**允许**引用
>   hidden 列（治理员按密级列过滤是标准用法）。
> - **SQL 管理语句（§13）不覆盖 hiddenColumns**，但 §13.6 的 GET→PUT 整份替换
>   必须原样带回（`PolicySqlCommands.hidden(detail)`），否则
>   `SET ROW FILTER` 等五条语句会静默清空 hidden 清单。
> - 服务端：`hidden_json` 列 + 四个 DTO 全链贯通 + 预览裁剪/拒绝语义
>   （`TablePreviewService.visibleOutput`：默认展开裁 hidden、显式勾选 400、
>   过滤条件引用 400）；引擎/服务端两侧行为由 `PolicySparkTests`、
>   `PolicyPreviewTests`、`DataPolicyApiTests` 锁住。

---

## 6. 服务端自己的读路径

服务端有三条读数据的路，**每一条都是绕过面**：

| 路径                                                          | 现状                                | 本方案处理                                                                                           |
| ----------------------------------------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------- |
| 表数据预览（`/api/console/v1/...preview`）                         | 直接用 Paimon / Iceberg Java API 读文件 | **P0：带策略的表直接拒绝预览**（403 + 说明）。**P1（已实现）**：AST 求值器（`PolicyExpressionEvaluator`，白名单函数子集）逐行过滤 + 列变换后再返回；求值顺序按 §5.3.5（先裁行、后同层 mask、参数读原值）；用户条件落到被脱敏列时**指名拒绝**（400）——引擎对这类谓词比较的是脱敏值（§5.7），预览在 reader 里按原值判，两条语义放一起就是静默分歧。**不做「悄悄返回明文」**——那等于控制台成了后门 |
| 文件列表下载（catalog 详情页可下载 parquet）                              | 可下载物理文件                           | **带策略的表禁止下载**（403）。这一条优先级最高：一个 parquet 原文件就是全表明文                                                |
| 表统计 / 元数据（nullCount、min/max、record_count、lower/upper bound） | 列级统计的唯一载体是快照 `statistics` 字段（优化器统计的序列化串） | **P1（已实现）**：策略带 mask 时快照响应的 `statistics` 整体置空（`DataPolicyService.hasActiveMask`，§7.3）；行数 / 文件数 / 字节数聚合值照常。行过滤不改变统计口径，但会让「总行数」与「可见行数」不一致，预览 warnings 里已标注 |

> P1 的求值器（Java 侧，遍历同一棵 AST）是这套设计里**唯一一处双实现**：Spark 侧用 Catalyst，  
> 服务端用自研求值器。风险是两边语义漂移（时区、NULL 排序、字符串截断、`regexp_replace` 的  
> 方言差异）。控制手段：同一份「用例表」（表达式 + 输入行 + 期望输出）在两个引擎上各跑一遍，  
> 差异即失败。这份用例表同时就是 §10 的验收清单。

---

## 7. 绕过面清单（按严重度）

### 7.1 存储凭据（致命，无法在本层解决）

本服务的 `/v1/{prefix}/.../storage-credential` 与 `loadTable` 都会把存储访问凭据交给客户端。  
拿到凭据之后：

```python
spark.read.parquet("s3a://bucket/warehouse/db.db/t/")   # 明文，没有策略
```

三个选项（都不便宜，**本方案不选**，只列出以便部署方决策）：

| 选项                      | 代价                                               | 效果         |
| ----------------------- | ------------------------------------------------ | ---------- |
| 带策略的表不下发凭据              | 这些表就只能走「服务端代理读」，而本服务不做代理扫描 → 等于不可用               | 强，但引入一个新服务 |
| 只发**短 TTL + 前缀限定**的临时凭据 | 已有能力，但对「用户自己写程序直读」无效                             | 弱，只缩小窗口    |
| 存储层列级加密 + 密钥只在引擎侧       | 需要 Parquet 模块化加密或 KMS 集成，Paimon / Iceberg 支持程度不一 | 强，工程量大     |
| **前置网关**（Kyuubi / 自研 SQL 网关），用户只能 JDBC | 部署形态改造，且必须禁止直连 Spark 集群；集群侧不再对业务方开放 | 强（升到 L1），与现有 Kyuubi + Ranger 生态同构 |

**设计上的处置：** 策略表上带一个 `enforced_scope` 字段，取值 `advisory` / `engine`。  
第一版一律 `engine`，并在控制台与文档里明确写出「这是引擎内强制，不是存储层强制」。  
不要让使用者以为是后者——**这类误解造成的后果，比没有这个功能更严重。**

两个维护要点：

1. **`enforced_scope` 表达的是「谁承诺执行」，不是「实际强度」。** 同一份 `engine` 策略，在裸 Spark
   集群上是 L0，在 Kyuubi 后面就是 L1。所以控制台要显示的是**当前部署等级**（§2.3 的 L0–L3），
   而不是只显示字段值——否则运维会以为改了字段就变强了。
2. **`SupportsAccessControl` 落地之后这一节不会变好。** SPIP 的 Q2 第一条明确把
   client/server 信任边界排除在范围外（原文：*"It does not define where code runs or any
   client/server trust boundary"*）。等 5.0 是 §5.6 方案 C 的事，不是 §7.1 的事。

**外部反证：Lake Formation 证明「不下发凭据」这条路真的走得通。** 它的做法不神奇——S3 bucket
policy 只认 LF 贩卖的临时凭据，用户自己的 IAM 身份对受管路径**没有任何权限**，所以「绕过去直读」
在权限上直接 403。这就是 §2.3 的 L2，代价是把自己钉在云厂商的存储与元数据服务上。
反过来看，Kyuubi 用零权限改造做到 L1：用户根本没有 Spark 会话，拿不到凭据也就谈不上绕过。
**两条路都不是引擎内能解决的，这正是 §5.1 那句「本层无法解决」的实证。**

### 7.2 物理文件下载（严重，可修）

catalog 详情页的下载功能会给出原始 parquet。带策略的表必须禁止下载（403），  
这与文件列表的「只看不改」不冲突——看得见文件名不等于拿得到内容。

### 7.3 元数据泄露（中，可修）

- 列级 `min`/`max`、`nullCount`：能反推被脱敏列的原值范围 → 对被脱敏列不下发（§6）。
  **实现落点（P1 已做）**：本仓的快照统计走 `TableSnapshotEntity.statistics`（Paimon
  优化器统计的序列化串）——它是唯一可能携带列级统计的载体，且是不透明串、没法按列
  挑着下发，所以「启用中策略带 mask」时**整体置空**（判据与理由见
  `DataPolicyService.hasActiveMask`）；纯行过滤策略不改列值，统计照常下发。行数 /
  文件数 / 字节数等聚合值不反推任何列的原值，不在打码范围。
- 分区值：如果分区列就是被脱敏列（少见但存在），分区列表本身就是明文 → 分区值对带策略表打码。
  **实现落点（P1 已做）**：分区列举端点维持 403（§6）；P1 起预览放开后，预览响应的
  分区回显（`resolved` / `label`）对「分区列 ∈ 脱敏目标」的表打码为占位符——只打回显
  这一份，游标与 fingerprint 用原值（不透明签名令牌 + 服务端一致性判据），分区裁剪照常。
- 文件名 / 路径：一般不含数据，但 Hive 风格的分区目录名含分区值 → 同上。
  **实现落点（P1 已做 / 记录残留）**：文件**下载**带策略表一律 403（§7.2，表级与
  catalog 级两扇门都锁）；文件**列举**保持开放——它是运维者视角的仓库浏览器（可整体
  关闭），目录名里的分区值只对「分区列恰是被脱敏列」的表泄露，为此打码目录条目会
  破坏导航（服务端无法把打码后的名字还原成请求路径），故**记录为已知残留**而非打码。

### 7.4 换引擎（中，本方案不覆盖）

Flink（本仓 `lakehouse-rest-flink` 两个模块）、Trino、自研程序都不认这套扩展。  
两条出路：各引擎各实现一遍（Trino 有 `AccessControl` 的 `filterTable` / `getColumnMask` 内建挂钩，  
是三个引擎里最省力的），或者把「策略只在 Spark 上生效」写进文档——后者更诚实。

### 7.5 表达式的边角（低，靠白名单挡）

时区（`cast('2024-01-01' AS timestamp)` 依赖会话时区）、字符集与 `md5` 编码、`regexp_replace`  
的贪婪匹配、浮点比较的精度。这些都是「两侧实现可能不一致」的点，全部收在 §6 的用例表里。

### 7.6 `EXPLAIN` 会打印策略文本本身（中，可修）

`EXPLAIN EXTENDED` 会把 `Filter` 的条件完整打印出来。如果策略是
`user_id NOT IN ('u1','u2')` 或者 `tenant = 'acme'`，用户就能读到**他不该知道的字面量**——
黑名单成员、别人的租户名、内部部门编码。三家同类实现都没把这个当问题（Ranger 有审计日志但没有
计划打码），因为它们的策略文本本来长得像机器码；我们的 AST 是结构化 JSON，展开后比它们更直白。

现状与处置：

| 位置 | 是否泄露 | 说明 |
| --- | --- | --- |
| 逻辑计划（`EXPLAIN` 默认输出上半段） | 否 | `PolicyRelation` 是不透明节点，只打印一行类名 |
| 物理计划（`EXPLAIN` 下半段） | **是** | `Strategy` 展开后的 `Filter` / `Project` 表达式会完整打印 |
| 控制台的「执行计划」页（若将来做） | 视实现 | 同上 |

两条可选路，推荐 a：

- **a. 用自定义表达式包装。** 展开时把 `Filter` 的条件和每个 mask 包一层自定义 `Expression`：
  `toString` 输出 `policy_expr(3 literals masked)`，`eval` / `doGenCode` 委托内层。代价是要自己
  实现 `doGenCode`（委托 inner 即可，不复杂），好处是 codegen 不退化、打印全链路一致。
- b. 只做逻辑计划收敛（现状），物理计划接受泄露。

**先按 b 交付，把这条写进边界说明；P1 做 a。** 不要假装看不见——用户第一次 `EXPLAIN` 就会问，
而「为什么我的脱敏规则能被同事看见」不是一个能靠文档含糊过去的问题。

> **2026-10-24 更新：方案 a 已实现（P3）。** `PolicyOpaque(child)`（shared 引擎侧，
> `UnaryExpression`）：`eval` / `doGenCode` 恒等委托 child（doGenCode 经公开的
> `child.genCode(ctx)` 委托——直接调 `child.doGenCode` 是 protected 跨包不可达；
> codegen 不退化），`dataType` 显式声明（4.x 的 `UnaryExpression` 不再提供默认），
> `toString` / `sql` 输出 `policy_expr(N literals masked)`（N = 内层 Literal 节点数，
> 打印时才算）。物理展开处 `Filter` 条件与每个 mask 的 `Alias` child 都包它；
> 逻辑侧 `PolicyRelation` 单行打印本就遮住（§7.6 表第 1 行）。测试：
> `PolicySparkTests.explainPhysicalPlanMasksPolicyLiterals`——`EXPLAIN EXTENDED`
> 文本含 `policy_expr`、不含策略字面量与 mask 函数名，且执行结果不受影响。

---

## 8. 权限、审计与控制台

### 8.1 谁能定义策略

12 项 access 是官方词表（`ALL / CREATEDATABASE / DESCRIBE / ALTER / DROP / CREATETABLE /
CREATEFUNCTION / CREATEVIEW / LIST / SELECT / UPDATE / GRANT`），**不能自己加**。  
所以「定义策略」不新增权限词，而是挂到既有语义上：

| 操作       | 需要的权限                                                       | 理由                           |
| -------- | ----------------------------------------------------------- | ---------------------------- |
| 读 / 写策略  | `TABLE:ALTER`                                               | 策略是表的属性（虽然不存进表属性），改它等于改表的可见性 |
| 被豁免      | 表 owner，或 `lakehouse.rest.authorization.service-admins` 名单内 | 与现有自举一致                      |
| 查看「谁被豁免」 | `TABLE:ALTER`                                               | 豁免名单本身是敏感信息                  |

> 不新增 `POLICY_*` 权限的理由：`docs/authorization.md` 明确写了「自己发明词表会造出规格里  
> 并不存在的授权语义，而且失效方向是放大权限」。策略挂在 `ALTER` 上，语义刚好。

### 8.2 审计

| 事件                              | 记什么                       | 不记什么               |
| ------------------------------- | ------------------------- | ------------------ |
| `POLICY_CHANGED`（策略增删改）         | 表、版本、变更前后摘要、操作者           | 表达式里的字面量值（可能就是手机号） |
| `POLICY_ENFORCED`（引擎侧命中）        | 表、策略版本、是否命中豁免、耗时          | SQL 文本、查询结果        |
| `POLICY_DENIED`（fail-closed 触发） | 表、失败原因（拉不到 / 解析失败 / 列不存在） | —                  |

`POLICY_ENFORCED` 走既有的 lineage 上报通道（`LineageReporter` 那套批量/关停兜底已经处理过  
「短命作业丢数据」的问题），不新开一条链路。

### 8.3 控制台

| 位置              | 内容                                           |
| --------------- | -------------------------------------------- |
| 表详情 • 新页签「数据策略」 | 行过滤表达式（结构化编辑器，不是自由 SQL 框）、列脱敏列表、豁免名单、版本与最近变更 |
| 表详情 • 现有各页签     | 带策略的表：预览页签置灰并给出原因；文件下载按钮置灰；统计里的被脱敏列显示「已脱敏」   |
| 权限页             | 复用 `TABLE:ALTER` 的既有展示，不新增列                  |
| 总览页 `/policies` | 四张 KPI 卡（受保护表 / 带行过滤 / 脱敏列 / 豁免主体）+ 策略列表（行过滤 SQL 展示、脱敏列 tags、状态、版本、操作者）；一次全量拉取 + 本地切页（策略量级「几十」，KPI 要跨页口径）；悬空策略一眼可见（chip 平铺 `rowFilterSql` + `maskColumns`，`PolicySummary` 为此扩了两个字段，判权不变） |
| 新建向导 `/policies/new` | 目标表三段级联 → 主体范围与豁免 → 行过滤 / 列脱敏编辑器（**与表详情页签共用 `src/utils/policyAst.js`**，两处建出的 AST 形状一致）→ 发布（PUT 整份替换）；已绑定策略的表不给新建（整份替换会静默顶掉现有策略），引导去表详情 |
| 主体试算 `/policies/:c/:d/:t/dryrun` | 按**被模拟主体**跑 `POST .../dry-run`，四态横幅（FOUND / EXEMPT / DISABLED / NONE——NONE 是红色「按默认放行，全部为明文」）；结果表按列渲染，脱敏列单元格带角标；被模拟主体不需要存在（`$principal` 折字面量、角色查不到折 false） |

**不做**自由 SQL 输入框——理由与 `table-data-preview-design.md` §1 的非目标一致：一个 SQL 框  
等于在控制台里开一个求值口子，算子、函数、注入面全要重守。结构化编辑器 + 白名单算子已经够用。

---

## 9. 版本矩阵与工作量

### 9.1 3.5 vs 4.1：能力等价，差异在边角

| 维度                            | Spark 3.5.9                            | Spark 4.1.2                      | 影响                                                                          |
| ----------------------------- | -------------------------------------- | -------------------------------- | --------------------------------------------------------------------------- |
| 行过滤 / 列脱敏原生 API               | 无                                      | 无                                | 两版都要自研扩展                                                                    |
| `injectPostHocResolutionRule` | 有                                      | 有                                | 主注入点一致                                                                      |
| `injectPlannerStrategy`       | 有                                      | 有                                | 自定义节点落地一致；但 4.0 起 `org.apache.spark.sql.Strategy` 被移除，**必须** `extends org.apache.spark.sql.execution.SparkStrategy`（该类两版本都在，3.5 上写 `Strategy` 恰好解析到它所以不报错，4.1 编译失败） |
| `TreeNodeTag`                 | 有                                      | 有                                | 幂等标记写法一致                                                                    |
| `injectFunction` 参数类型         | `Seq`                                  | `immutable.Seq`                  | 只有注册自定义函数时才有差异；本方案用占位符替身 → **不撞**                                           |
| `injectHintResolutionRule`    | 无                                      | 有                                | 不用                                                                          |
| ANSI 模式默认值                    | `false`                                | `true`                           | mask 表达式里的 `cast` 溢出：4.1 会抛错，3.5 返回 NULL。**用例表要覆盖**，并且在 4.1 上优先用 `try_cast` |
| 时间类型默认 API                    | `java.sql.*`（`java8API.enabled=false`） | `java.time` 为默认                  | 日期列脱敏（如只留年月）两版语义不同，**用例表要覆盖**                                               |
| V2 行级 DML                     | 部分（`RowLevelOperation` 在 3.5 已有）       | 完整（`SupportsRowLevelOperations`） | 与脱敏无关，因为写路径不套策略                                                             |

**结论：一份共享源码 + 两个版本模块各自编译**，与现有 `lakehouse-rest-spark` 的形态完全一致  
（`shared/` 一份，3.5 用 2.12、4.1 用 2.13 各编一遍）。这也是血缘抽取器已经在用的模式，不需要新的工程结构。

### 9.1.1 实现期的三个实测结论（计划级证据，写进了代码注释）

1. **`org.apache.spark.sql.Strategy` 在 4.0 被移除**。`injectPlannerStrategy` 现在要求
   `org.apache.spark.sql.execution.SparkStrategy`；该类两版本都存在（`planLater` 是它的具体方法），
   统一 `extends SparkStrategy` 即可双版本兼容。
2. **Spark 4.0 起 UPDATE / DELETE 在分析期就被改写为 `RowLevelWrite` 节点**（3.5 仍是 `UpdateTable`，
   优化器才改写）。注入规则必须对 `RowLevelWrite` 的 `table`（exprId 等值判断）豁免目标读取，
   只递归处理其 query/subquery；否则 UPDATE 的目标表读取被套策略，**脱敏值会被写回表**造成永久数据污染。
3. **ColumnPruning 会穿透不透明的 UnaryNode**：在 PolicyRelation 下插裁剪 Project，随后
   RemoveNoopOperators 删外层投影、V2ScanRelationPushDown 推进 scan，最终 mask 表达式因
   「列不在 schema 中」而崩。修法是 PolicyRelation 增加 `anchors: Seq[Expression] = fullRelation.output`
   字段——case class 字段自动进入 `expressions`/`references`，优化器认为每列都被本节点引用，裁剪停止。
   用 DummyA/DummyB 双变体探针测试实证：带 anchors 的变体完全阻止裁剪，且两变体叶子都能被
   pushdown 成可规划的 `DataSourceV2ScanRelation`。
   另注意：物理展开处的投影列表必须保留 mask 的 `Alias` 本体，**不能** `.map(_.toAttribute)`——
   那会把 mask 表达式抹回普通属性引用，投影退化成纯透传、脱敏静默失效。

### 9.2 粗估工作量

| 工作项                                                 | 人日        | 说明                           |
| --------------------------------------------------- | --------- | ---------------------------- |
| 服务端：两张表 + 实体 + 仓储 + 策略服务（含版本、豁免）                    | 4–6       | 复用 `AuditedEntity`、`Paging`  |
| 服务端：表达式模型 + 校验（白名单、类型、列存在性）                         | 3–4       | 校验必须比 Spark 侧更严，否则错误要到查询时才暴露 |
| 服务端：策略端点（批量查询）+ 控制台页签                               | 5–7       | 前端含结构化编辑器                    |
| 引擎：`PolicyRelation` + `Strategy` + 缓存 + fail-closed | 8–12      | 两版共用一份源码，差异只在编译              |
| 引擎：测试（plan 断言 + 端到端）                                | 4–6       | 见 §10                        |
| 服务端读路径收口（预览拒绝、下载拒绝、统计打码）                            | 2–3       | P0 只做拒绝                      |
| 文档 + verify 脚本                                      | 2–3       | 端点与文档双向一致，沿用现有校验风格           |
| **合计（P0 + P1）**                                     | **28–41** | 不含 §7.1 的存储层方案               |

---

## 10. 分期与验收

### 10.1 分期

| 期      | 内容                                                                        | 不做什么                      |
| ------ | ------------------------------------------------------------------------- | ------------------------- |
| **P0** | 策略模型 + 端点 + 控制台（页签与总览 / 向导 / 试算三屏均已落地，见 §8.3 与状态行）；Spark 侧行过滤 + 列脱敏（`PolicyRelation`）；服务端三条读路径**全部拒绝**带策略的表  | 不做服务端求值器；不做 Flink / Trino |
| **P1** | 服务端 AST 求值器（预览可用）+ 双端用例表对齐；元数据打码；策略管理语句（§13，7 条，含保留字同步）；`$principal_roles`——**全部已实现** | 不做存储层收口；SQL 不做试算（§13.9） |
| **P2** | `SupportsAccessControl` 与 Trino `AccessControl` **外部阻塞**（前者需 Spark 5.0 的公开 API，工程无 Trino 模块），升级路径见 §14.6；ABAC 出设计草案（§14，未实现） | 不在阻塞解除前排期 |
| **P3** | 整列不可见（`hiddenColumns`，§5.8 更新块——**已实现**，v1 的两条路都不是必经，靠形状判据 + 兜底裁剪在 `PostHocResolution` 完成）；`EXPLAIN` 表达式打码（§7.6 方案 a，**已实现**）。注意：`hiddenColumns` 是独立能力，`COLUMN:SELECT` 赋权仍未接入执行 | 不与列级赋权模型合并 |

### 10.2 验收清单（每条都要有对应测试）

| #  | 断言                                            | 手段                                                 |
| -- | --------------------------------------------- | -------------------------------------------------- |
| 1  | `SELECT *` 的敏感列等于 mask 表达式的结果，且**列名/顺序/类型不变** | 端到端 + schema 断言                                    |
| 2  | 行过滤只影响可见行，不影响列值                               | 端到端                                                |
| 3  | 行过滤表达式引用被脱敏列时，判定用的是**原值**                     | 构造 `salary > 100` + `mask(salary)=NULL` 的表         |
| 4  | 用户谓词落在被脱敏列上时，得到的是「脱敏值比较」的结果                   | 端到端                                                |
| 5  | 优化器跑完（`explain` 后）mask 仍在                     | 取 `optimizedPlan` 断言；**官方给的五个维度各一条**：① no-op 投影移除 ② 列裁剪 ③ 谓词下推与重排 ④ 子查询 / CTE 复用 ⑤ `CACHE TABLE` |
| 6  | 子查询 / CTE / 视图 / 自连接各自都生效                     | 四条 SQL                                             |
| 7  | `INSERT` / `UPDATE` / `DELETE` 看不到策略影响        | 三条 DML                                             |
| 8  | 策略服务不可达 → 读**失败**（fail-closed），不是返回明文         | 关掉端点后查询                                            |
| 9  | 改策略后 TTL 内旧值、TTL 后新值                          | 用 `TTL=0` 与 `TTL=60` 各测一次                          |
| 10 | 带策略的表：文件下载 403（表级 + catalog 级）、分区列举 403、带 mask 时快照 `statistics` 置空、预览分区回显对被脱敏分区列打码 | 各端点一条（预览自 P1 起按策略求值：行过滤 + mask，见 §6 与 `PolicyPreviewTests`）                                            |
| 11 | 豁免主体读到明文                                      | 两个主体对比                                             |
| 12 | 3.5 与 4.1 的用例表结果**逐行一致**                      | 同一份用例表跑两遍                                          |
| 13 | 有策略的表查了，计划里**必须**有 `PolicyRelation`；没有就是 bug | `EXPLAIN EXTENDED` 文本断言（§3.4 第 2 条）。这是防 Kyuubi #3186 那种「静默放行」的唯一判据 |
| 14 | 对含 `struct` 列建策略 → **建策略时被拒**                 | 服务端校验；不能静默忽略                                    |
| 15 | `SELECT ssn`（mask 引用 `region`）结果正确，不因列裁剪缺列   | 端到端（§5.3.3 的「按 child 全量 output 展开」）                 |
| 16 | DROP → 策略级联删；RENAME → 策略跟随；同名重建 → **不继承**  | 三条 DDL 各一条（§5.2 取舍 4）                                |
| 17 | 一份内存 V2 测试表把 1–16 全跑一遍（不依赖 Paimon / Iceberg） | 对应 SPIP Q8 的 reference integration；单测秒级，e2e 只做冒烟 |
| 18 | `EXPLAIN EXTENDED` 不泄露策略字面量与 mask 函数名，且执行结果不受影响 | `PolicySparkTests.explainPhysicalPlanMasksPolicyLiterals`（§7.6 方案 a） |
| 19 | `hiddenColumns`：星号只返回可读列、显式引用指名失败、DML 不受阻、豁免主体全列可见 | `PolicySparkTests` 四用例（§5.8 更新块）；两版本跑同一份 |
| 20 | hidden 的 SQL 兼容：`SET ROW FILTER` 等五条语句执行后 hidden 清单原样保留 | `PolicySqlTests`（GET→PUT 透传，§13.9） |

> 第 12 条是这套东西能不能长期维护的分水岭：两版一旦漂移，症状是「同一个策略在两个集群上  
> 给出不同的结果」，而这种差异不会报错，只会有人某天发现数对不上。

---

## 11. 未采纳的方案

| 方案                                        | 为什么不选                                                                                                    |
| ----------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| 动态视图（`CREATE VIEW ... WHERE ...`）         | 零开发，但：①视图要一一维护，表一多就崩；②用户仍可直接查基表，除非把基表权限全收掉；③视图定义里写死主体判断，等于把权限写进 SQL 文本。作为「应急手段」可以，作为机制不行                 |
| 只做在 REST 服务端                              | 数据不*经过服务端，服务端只能发*元数据。**物理上不可能**在服务端拦截 Spark 读到的行                                                         |
| 只做 Trino，不碰 Spark                         | 与现状相反：本仓的使用者主要用 Spark。Trino 的 `AccessControl` 挂钩确实更省力（内建 `filterTable` / `getColumnMask`），可以作为 P2 的第二个引擎 |
| 等官方 `SupportsAccessControl`               | 目标 5.0.0，`Fix Version: None`。等不起；但接口形状与之对齐，将来切换成本低（§5.6 方案 C）                                            |
| 用 Paimon / Iceberg 的 table properties 存策略 | 见 §5.2 取舍第 2 条：协议可见、两种格式语义不同、`ALTER TABLE` 会绕过策略判权                                                       |
| Lake Formation 式「列被排除时返回 NULL」 | LF 的列排除让查询成功、该列返回 NULL。我们选**报错**：静默 NULL 会让人以为「这列本来就是空的」，与 §5.4 的 fail-closed 同一个理由。三家只有 LF 这么选，而且它的执行点在凭据侧（列根本读不到），我们的执行点在上层，报错更诚实 |
| P0 就做 `readableColumns`（整列不可见）      | 见 §5.8：注入点晚于 `*` 展开，要么回到 `injectResolutionRule` 硬啃三种未解析形态，要么把策略塞进协议 schema 响应。都不适合和 P0 一起做 |

---

## 12. 一句话总结

**技术上 3.5 与 4.1 都能做，且做法相同（Catalyst 扩展 + 自定义逻辑节点）；真正的约束不是引擎版本，  
而是「凭据发到客户端之后，任何引擎内的强制都只是一种约定」。** 所以这套东西的正确交付方式，  
是把它连同它的边界一起交付——而不是只交付一个「已启用」的开关。

把三家生产实现（Databricks / Kyuubi + Ranger / EMR + Lake Formation）与 SPIP 放在一起看，
这句话有了更硬的形式：**它们注入机制几乎一样，差距全在"用户能不能拿到存储凭据"这一行。**
本方案处在 §2.3 的 L0，升级路径不是等 5.0，而是加一层网关（L1）或收掉凭据来源（L2）。

---

## 13. Spark SQL 语法设计

控制台与 REST 是策略的权威入口，但运维与数据工程师的第一反应仍是 SQL。本仓已经有
自带语法的先例——`ManagementSql.g4` 管着主体 / 角色 / 授权那 16 条语句
（见 [`spark-sql-extension.md`](spark-sql-extension.md)）——策略管理语句走同一条路：
**新语句进同一份语法文件，执行时解析成 AST、调 REST 端点，Spark 会话里不存任何策略状态。**

### 13.1 三条设计原则

1. **SQL 是 REST 的另一只手，不是第二个权威。** 语句不做本地存储、不做本地终裁：
   解析出形状 → 交给服务端。控制台改的和 SQL 改的是同一个版本化对象，版本号、审计、
   并发行为完全一致。推论：**SQL 侧没有「离线模式」**——服务端不可达时语句直接失败，
   与查询侧的 fail-closed 同一哲学。
2. **表达式片段不进 ANTLR 语法，交给 `delegate.parseExpression()`。** 行过滤谓词与
   mask 参数是真正的表达式。自己写表达式文法等于把 Catalyst 的字面量、cast、运算符
   优先级复制一份，还得复制两遍（3.5 / 4.1）；`parseExpression()` 是 `ParserInterface`
   上两版都有的成员。语法只负责「这句话是策略语句」的骨架，表达式内容取出后按
   §13.4 的白名单转 AST。
3. **关键字向 Databricks 对齐，形状向「值」对齐。** `SET ROW FILTER` / `SET MASK` /
   `DROP MASK` 与 UC 相同，降低迁移与教学成本；但 UC 绑定的是**命名函数**
   （策略 = 函数引用），我们绑定的是**值**（AST 对象）——因为没有服务端持久函数
   注册表（§3.4 第 4 条）。`ON (cols)` / `USING COLUMNS` 的显式列清单也不保留：
   目标列由 `ALTER COLUMN` 语境给出，mask 引用的其它列直接写在表达式里（§5.3.5）。

### 13.2 语句清单

```
-- 行过滤（表还没有策略时，这条语句负责创建 v1）
ALTER TABLE <db.table> IN CATALOG <catalog> SET ROW FILTER <谓词表达式> [FOR <主体清单>]
ALTER TABLE <db.table> IN CATALOG <catalog> DROP ROW FILTER

-- 列脱敏：标准集（函数名 + 字面量参数，目标列隐式）
ALTER TABLE <db.table> IN CATALOG <catalog> ALTER COLUMN <col> SET MASK <标准函数>(<字面量, ...>) [FOR <主体清单>]
-- 列脱敏：逃生口（白名单表达式，其它列直接按名引用）
ALTER TABLE <db.table> IN CATALOG <catalog> ALTER COLUMN <col> SET MASK <表达式> [FOR <主体清单>]
ALTER TABLE <db.table> IN CATALOG <catalog> ALTER COLUMN <col> DROP MASK

-- 主体范围（只改这一项，不携带任何表达式）
ALTER TABLE <db.table> IN CATALOG <catalog> SET POLICY FOR <主体清单>

-- 删除整条策略（物理删除；「停用」只在控制台提供）
ALTER TABLE <db.table> IN CATALOG <catalog> DROP POLICY

-- 查看（表上没有策略时返回 0 行，不报错）
SHOW POLICY ON TABLE <db.table> IN CATALOG <catalog>
```

主体清单复用授权语句的 `grantee` 形状：`USER a` / `ROLE r` / `GROUP g`，逗号分隔。
**省略 `FOR` 子句 = 全部主体**（与控制台默认一致）。不提供「继承上一次的 FOR」——
继承是隐式状态，两次 ALTER 之间容易把范围悄悄改没了。

`IN CATALOG` 是刻意的：它让策略语句与原生 `ALTER TABLE`（`SET TBLPROPERTIES` /
`SET SERDE`）在形状上互斥，沿用现有解析器「先试自己、失败即交回」的拦截策略时，
不会把普通 DDL 拦下来。

落点与机械联动：`statement` 备选加 7 条新规则；保留字新增
`ROW / FILTER / MASK / POLICY` 四个——**要同步 `spark-sql-extension.md` §3.3 的
保留字清单与数量声明**（`verify-spark-sql-doc.py` 会断言计数），实现时别漏。

| 语句 | REST 动作 | 版本 | 权限 |
| --- | --- | --- | --- |
| SET ROW FILTER | GET → 修改 → PUT（base version） | 无则 v1，有则 +1 | `TABLE:ALTER` |
| DROP ROW FILTER | 同上，行过滤置空 | +1 | `TABLE:ALTER` |
| ALTER COLUMN SET MASK | 同上，覆盖该列 mask | +1 | `TABLE:ALTER` |
| ALTER COLUMN DROP MASK | 同上，移除该列 mask | +1 | `TABLE:ALTER` |
| SET POLICY FOR | 同上，只改主体范围 | +1 | `TABLE:ALTER` |
| DROP POLICY | DELETE | — | `TABLE:ALTER` |
| SHOW POLICY | GET，展开为结果集 | — | `TABLE:DESCRIBE` |

### 13.3 语义逐条

- **SET ROW FILTER**：表达式转换失败 → 语句失败，不落任何变更。成功 = 替换行过滤部分。
- **DROP ROW FILTER**：表上没有策略 → **报错**，不是成功 no-op——no-op 会让人以为删过。
- **SET MASK**：同一语句内对同一列两次 SET → 解析期报错；跨语句重复 SET = 覆盖。
- **DROP MASK**：列不在 mask 清单里 → 报错。
- **SET POLICY FOR**：表上没有策略 → 报错（「只有主体范围」不构成策略）。
- **DROP POLICY**：物理删除、不可恢复，而 SQL 里没法优雅地二次确认。设计：该语句只在
  会话变量 `lakehouse.rest.sql.confirm-destructive=true` 时执行，缺省拒绝，报错信息给出
  替代路径（控制台）。防呆写进 §10.2 验收。
- **SHOW POLICY**：输出列 `version / subjects / row_filter / masked_columns /
  allow_null_confirmed / updated_by / updated_at`；`row_filter` 显示 AST 生成的
  SQL 原文（§5.3.1 同时存两者），不是 JSON。

### 13.4 表达式 → AST：转换与白名单

`parseExpression` 得到 Catalyst 表达式树后逐节点转换，**遇到白名单外的节点类，
报错并指认类名**（`Unsupported expression: Rand() ...`），不静默丢弃、不降级。

| Catalyst 节点 | AST | 备注 |
| --- | --- | --- |
| `EqualTo` / `LessThanOrEqual` / … | `eq` / `le` / … | 十个比较算子一一对应 |
| `And` / `Or` / `Not` | `and` / `or` / `not` | |
| `In` | `in` | 列表必须全为字面量 |
| `Like` | `like` | |
| `IsNull` / `IsNotNull` | `is_null` | |
| `CaseWhen` / `Coalesce` | 条件 | 「单元格级」依赖它们（§5.3.5） |
| `Substring` / `Concat` / `RegExpReplace` / … | 字符串函数 | 与 §5.3.2 算子表逐项对照 |
| `TruncDate` / `Year` / `Month` / … | 日期截断 | 注意 3.5 与 4.1 的函数类名有差异，按语义匹配而不是类名白名单 |
| `Md5` / `Sha2` | `hash` | `sha2(col, 256)` 的位参数映射成算法名；**算法必须是字面量** |
| `CurrentUser` | `$principal` | **转换期替换**（§5.3.4），AST 里不存函数调用 |
| 其余一切 | **拒绝** | 报错含节点类名 + 原始 SQL 片段 |

`current_user()` 返回的是**执行语句的人**——也就是策略作者。这正是想要的
（`region = current_user()` 的语义是「各人看各人的行」），但必须写明：落库的是
`$principal` 占位符，不是作者的名字面量；组成员判断等 P1 的 `$principal_roles`。

**两条会话依赖要钉死：**

1. `parseExpression` 在 ANSI / 非 ANSI 会话下对同一文本可能给出不同的树（整型溢出、
   隐式 cast）。转换在解析期完成，AST 随之定形——即**策略语义由建策略那台会话的
   配置决定**。处置：加一条测试，ANSI 开 / 关各建一次同一句谓词，AST 必须相同；
   若不同，把该写法拉黑（报错提示改写成显式 cast 的等价形式）。
2. 逃生口表达式里 `cast` 只允许放宽（§5.3.2），`cast(NULL AS string)` 这类
   「给 CASE 的 ELSE 分支定类型」是合法用法，不视为收窄。

### 13.5 标准集在 SQL 里的形状

`ALTER COLUMN customer_phone SET MASK show_first_n(3)`——**列是隐式的**，
参数全为字面量。识别规则（§5.3.2.1「标准集 vs 逃生口」在语法上的对应）：

- 表达式是**单个**函数调用、函数名 ∈ 标准集、参数全为字面量 → 标准集 mask，
  控制台显示为下拉 + 参数；
- 其它任何形状 → 逃生口表达式，控制台显示为**只读**代码块。

第二条有个重要的产品推论：**控制台不提供表达式编辑，逃生口只能从 SQL / API 进**
（§8.3 本来就定了控制台不做自由 SQL 框）。这样「标准集优先」不是一句建议，
而是界面结构本身：想写表达式的人必须换一个有审计记录的入口。

`hash('SHA-256')` 的算法必须写死为字面量（§3.4 第 4 条）——SQL 形状天然读不到
集群配置，这是它比 UDF 形状安全的地方。

**NOT NULL + 置空的确认，在 SQL 里怎么表达？** 控制台是勾选框（§5.3.3），SQL 不能
弹框。设计：`replace_with_null` 落在 NOT NULL 列时语句失败，报错给出两个出口——
控制台勾选，或显式写 `SET MASK replace_with_null ALLOW NULL ON REQUIRED`。
**把确认写进语法**，比会话变量或隐藏开关诚实：这条策略从 SQL 里也能一眼看出
「作者知道自己在做什么」。

### 13.6 版本、并发与缓存

- 每条语句版本 +1，PUT 带 base version（乐观锁）。两个会话同时 ALTER → 后提交者
  409，语句报错。**SQL 不自动重试**：策略变更是治理动作，静默重试可能把别人刚删的
  条件加回来。
- **本会话什么时候看到新策略？** 缓存 TTL 60s（§5.4），改完立刻查可能还是旧策略。
  除了等 TTL，把失效钩子挂到既有的 **`REFRESH TABLE`** 上——它本来就表示
  「这张表的缓存都别用了」。实现位置：`parsePlan` 交回 delegate 的结果里识别
  `RefreshTable` 计划，命中带策略的表就失效本会话的策略缓存。
- **管理语句不得产生血缘。** 抽取器只认对目标表的写命令，策略语句的计划不是写
  命令；但要有一条测试钉住——每次改策略都造一条假 run 是很烦人的回归。

### 13.7 与 Databricks 语法的对照

| Databricks (UC) | 本方案 | 为什么不同 |
| --- | --- | --- |
| `ALTER TABLE t SET ROW FILTER f ON (c1, c2)` | `SET ROW FILTER <谓词>`，涉及列从 AST 推导 | UC 绑命名函数；我们绑 AST 值。让用户报列清单就会漏报 |
| `ALTER TABLE t ALTER COLUMN c SET MASK f` | 同形 | |
| `SET MASK f USING COLUMNS (a, b)` | 把 `b` 直接写进表达式 | 参数引用其它列在 §5.3.5 是一等语义，不需要独立子句 |
| `CREATE FUNCTION ... RETURN CASE WHEN is_account_group_member(...)` | `SET ROW FILTER region = current_user()`；组判断等 `$principal_roles` | 不开放任意 UDF（§3.4 第 4 条） |
| `OR REPLACE` / `IF EXISTS` 一族 | 不做 | 每次 ALTER 都是新版本，版本历史本身就是替换记录 |

### 13.8 示例会话

```sql
-- 全部主体：只看中国区、非作废订单
ALTER TABLE sales.orders IN CATALOG lakehouse
  SET ROW FILTER region = 'CN' AND status <> 'void';

-- 标准集：手机号保留前 3 位；邮箱哈希
ALTER TABLE sales.orders IN CATALOG lakehouse
  ALTER COLUMN customer_phone SET MASK show_first_n(3);

ALTER TABLE sales.orders IN CATALOG lakehouse
  ALTER COLUMN email SET MASK hash('SHA-256');

-- 逃生口：非中国区整个字段不可见（白名单含 CASE WHEN，§5.3.2）
ALTER TABLE sales.orders IN CATALOG lakehouse
  ALTER COLUMN customer_name SET MASK
    CASE WHEN region <> 'CN' THEN cast(NULL AS string) ELSE customer_name END;

-- 主体范围收窄到两个人
ALTER TABLE sales.orders IN CATALOG lakehouse
  ALTER COLUMN amount SET MASK mask_to_fixed_value('***')
  FOR USER analyst_li, ROLE bi_readers;

SHOW POLICY ON TABLE sales.orders IN CATALOG lakehouse;
```

**错误的样子**（fail-closed 在管理语句上的三个体现）：

```sql
> ALTER TABLE sales.orders IN CATALOG lakehouse
    SET ROW FILTER region = 'CN' OR rand() > 0.5;
Error: Unsupported expression: Rand() —— 白名单不允许非确定性函数（§5.3.2）

> ALTER TABLE sales.orders IN CATALOG lakehouse
    ALTER COLUMN profile SET MASK mask_alphanum;
Error: profile 是 struct 列，嵌套字段不支持建 mask（§5.7）

> ALTER TABLE sales.orders IN CATALOG lakehouse DROP POLICY;
Error: DROP POLICY 需要会话开启 lakehouse.rest.sql.confirm-destructive=true（§13.3）
```

### 13.9 边界

- **不做 `CREATE POLICY <name>`**：策略不是一等命名实体，直接绑表（§5.2）。
  UC 的命名函数模型不搬。
- **`DESCRIBE TABLE EXTENDED` 不加「策略」行**：那是 table properties 的展示通道，
  策略已明确不进 properties（§11）。看策略用 `SHOW POLICY`。
- **不做 `GRANT ... WITH ROW FILTER` 一类的授权内联语法**：主体范围是策略属性，
  不是授权属性，两套模型不混。
- **不做「试算」语句**（`DRY RUN SELECT ...` 之类）：试算走控制台与 REST
  （§6），它需要服务端求值器与一致性用例表兜底，SQL 里再开一个口子只会多一处
  「看起来像预览、实际绕过了校验」的面。
- **`hiddenColumns` 不进 SQL 语句面**（P3）：§13.2 的语句清单不为它加语法，但
  GET→PUT 整份替换（§13.6）必须原样带回——`PolicySqlCommands.hidden(detail)` 在
  五个会改策略的 Command 里透传，漏一处就是「跑一条
  `SET ROW FILTER` 把整列不可见清单清空」的静默破坏。SQL 里也写不出 hidden 语义
  （它不是表达式，是列名清单），硬加语法只会得到一个半吊子形状。

---

## 14. ABAC：标签驱动的策略分发（设计草案，未实现）

> **状态**：本节是 P2 的设计草案，**没有任何一行实现**。先写下来的理由与 §5.8 当年
> 一样——这个方向的第一个实现者最容易犯的错是可以预见的（见 14.4），趁热记下来。
> P2 的另外两项（`SupportsAccessControl`、Trino `AccessControl`）是外部硬依赖，见 14.6。

### 14.1 动机：per-table 整份替换的运维半径

本方案的策略模型是「策略直接绑表、PUT 整份替换」（§5.2 取舍 4、§13.6）。表少时这是优点：
一个端点看全策略、没有命名实体、没有引用计数。表一多就是灾难——设想「所有
`tier=pii` 的表都要挂同一条 `region = current_user_region()` 行过滤 + 三个标准 mask」，
300 张表就是 300 份 JSON 的复制粘贴，改一处要批量 PUT 300 次，漏一张就是策略空洞。

ABAC（Attribute-Based Access Control）要解决的只有一件事：**把「策略内容」与「挂载范围」
解耦**。管理员写一次策略模板，用表的标签声明哪些表适用，挂载/摘除随标签自动发生。

### 14.2 数据模型（三个新对象，全部是特权对象）

| 对象 | 内容 | 谁能写 |
| --- | --- | --- |
| 表标签 | `<key, value>` 对，来源只有表 properties（`tier=pii`、`domain=orders`）——不建独立的标签表，properties 已经有事件联动（RENAME 跟随、DROP 级联） | 现有 ALTER TABLE 特权 |
| 策略模板 | 与 `PolicyUpsertRequest` 同形的 JSON（行过滤 + masks + hiddenColumns），但不绑表，绑模板 ID | 新特权 `POLICY_TEMPLATE:WRITE`，与 §8.1 的策略写特权分开 |
| 绑定规则 | 「标签表达式 → 模板 ID」的有序列表；标签表达式只做 `<key> = <value>` 与 `<key> IN (...)` 的合取，不做任意谓词（见 14.4 第 2 条） | 同上 |

绑定规则**不复制**策略内容，resolve 时合成。这保证「改一处模板 → 全部命中表生效」，
代价是 resolve 路径多一跳（见 14.5）。

### 14.3 resolve 顺序：显式策略永远优先

```
resolve(table, principal):
  explicit = per-table 策略（现有路径）
  if explicit 存在            -> 用 explicit，模板不参与（哪怕标签也命中）
  else:
    matched = 绑定规则按序匹配表标签
    if matched 非空           -> 合成：模板 JSON + 表 schema 校验（缺列 fail-closed，同 §5.3.3）
    else                     -> NONE（现语义不变）
```

这条顺序是刻意的：**显式策略是「例外」的逃生口**。某张 `tier=pii` 的表需要不一样的
规则时，管理员写一份 per-table 策略，不用动模板。反过来的优先级（模板覆盖显式）会让
「修一张表的策略」变成「先摘标签」，操作面反而更大。

合成结果对调用方**完全透明**：`ResolvedPolicy` 不区分来源，引擎侧零改动。
审计事件的 attributes 里加 `policySource: explicit | template(<id>)`。

### 14.4 四条可预见的错误做法（实现者必读）

1. **模板支持任意表达式引用标签**（如 `tier + '_' + env`）。标签表达式的求值发生在
   resolve 的热路径上，任意表达式等于把 §5.4 的缓存键打穿——缓存键必须能从
   (table, principal) 直接算出，所以标签匹配只能是等值/IN 的合取。
2. **允许模板内容含 `$table` / `$schema` 之类的表级占位符**。看起来很灵活，实际是
   把「策略内容」重新耦合回「具体表」，§14.1 解耦的目的作废；而且占位符展开发生在
   服务端，§13.4 的「表达式 → AST 白名单」要为每个占位符开洞。v1 不做，真有这种
   需求就写 per-table 策略。
3. **用模板合成结果反写 per-table 策略（物化）**。物化省掉 resolve 的模板跳，但
   「改模板不再生效」——必须同时给全部物化表发失效，等于把 14.1 的批量 PUT 换成
   批量失效，没有变简单，还多了一种状态（物化后模板改了、还没失效）。resolve 期合成，
   永远现算。
4. **模板不命中 = 报错**。不命中就是 NONE（该表没有策略），这是正常业务状态；
   要 fail-closed 的是「绑定规则命中的模板引用了表里不存在的列」（同 §5.3.3 的
   stale 判据：建时 fail-closed、读时标 stale 并拒绝）。

### 14.5 与现有机制的对账

| 现有机制 | ABAC 下的影响 |
| --- | --- |
| PUT 整份替换 + baseVersion 乐观锁（§13.6） | 只作用于显式策略；**SET POLICY 系列语句对模板合成结果必须报错**（"policy is managed by template T-7; alter the template"），否则一次 SQL PUT 就把模板挂载悄悄物化掉（14.4 第 3 条的运行时变体） |
| `PolicyCache` + TTL / `POLICY_CHANGED` 失效（§5.4） | 缓存键追加绑定规则版本号；改模板 / 改绑定规则 / 改表 properties 都走 `PolicyCache.invalidateAllLive()`（表 properties 变更事件已有监听路径可挂） |
| 表生命周期（DROP 级联删 / RENAME 跟随，§5.2 取舍 4） | 标签在 properties 里，天然跟随；DROP 后无表无策略，无需动作。模板与绑定规则不随任何表走 |
| SQL 管理语句（§13） | 不加模板语句（v1 模板只走 REST + 控制台）；`SHOW POLICY` 的响应加 `policySource` 一行，让用户知道眼前的策略是哪来的 |
| 控制台（§8.3） | 「数据策略」页签加只读的「来源」标记；模板管理是独立页面，不在表详情里改 |
| 行过滤允许引用 hidden 列 / hidden 与 mask 互斥（§5.8） | 校验发生在模板保存时（对**什么表都可能挂**的模板，只能校验「列名是合法标识符」级别），表级校验推迟到 resolve 合成期——缺列 fail-closed。这是模板与 per-table 策略在语义上唯一真正的差异 |

### 14.6 P2 的另外两项：外部依赖与升级路径

| 项 | 阻塞 | 升级路径 |
| --- | --- | --- |
| `SupportsAccessControl` | Spark 3.5/4.1/4.2 均无此公开 API（§1.2、jar 实测）；SPARK-59726 指 5.0，且其检查点在 catalog 层，信任边界与本方案的引擎侧注入不同（附录 A 第 4 条） | Spark 5.0 出来后做适配层：`PolicyRelation` 注入逻辑不动，把 catalog 回调转发到同一份 resolve。届时 §3.4 的四条教训全部仍然适用 |
| Trino `AccessControl` | 工程无 Trino 模块；引入一个完整引擎适配是独立项目级工作量 | 沿用本设计的协议面：Trino 的 `SystemAccessControl` + `ColumnMask` SPI 与本方案的 `ResolvedPolicy` JSON 一一对应，求值语义用 §6 的同一份用例表对齐（与 Spark/服务端求值器三方一致） |

---

## 附录 A. 与 SPIP（SPARK-59726）的差距清单

SPIP 是官方给出的「这件事正确做出来该长什么样」的参照物。逐条对一遍，剩下的差距就是我们要还的技术债：

| SPIP 承诺的东西 | 本方案现状 | 差距性质 | 收敛动作 |
| --- | --- | --- | --- |
| **Optimizer-safety**：注入的 filter / mask 永不被消除或重排到「更弱」的位置 | 靠 `PolicyRelation` 是不透明节点实现 | **等价但无保证**。我们的正确性完全依赖「优化器不认识这个节点」这一事实；Spark 若引入通用子树规则（`ReuseSubquery`、将来的 AQE 规则）仍可能动它 | 每次升 Spark 跑 §10.2 第 5 条的 5 维矩阵——这正是官方给的正确性矩阵，直接拿来当升级 checklist |
| **Fail-closed**：mask 名解析不到、非法 / 重复列引用、`REPLACE_WITH_NULL` 落在必需列、显式引用不可读列，全部中止查询 | 覆盖 3 条：拉不到策略、解析不出表、表达式引用的列不存在。缺「重复 mask」「必需列置空」「引用不可读列」 | 前两条补进建策略校验（§5.3.3）；第三条随 §5.8 延后 | §10.2 第 14 条 + §5.3.3 的 `allow_null_on_required` |
| **Engine-owned 标准 mask 集**：一套标准函数名，各集成方不必自己发明语义 | 只有白名单表达式，没有标准集 | 同一意图两种写法（`substr` vs `show_last_n`），审计对不上、迁移对不上 | §5.3.2.1：收敛成「标准集 + 逃生口」，名字直接用 SPIP 的 |
| **公开 API**：`SupportsAccessControl` / `AccessControl` / `ColumnMask`，不用包私有内部 API | 自研扩展 | 无法回避，`Affects 5.0.0` / `Fix Version: None` | P2 切；接口形状已按 `readableColumns` / `rowFilter` / `columnMasks` 三者对齐（§5.6 方案 C） |
| **信任边界** | §5.1 / §7.1 明确不管 | **SPIP 也不管**（Q2 第一条） | 不要指望 5.0 解决；要解决走 §2.3 的 L1 / L2 |
| **不做 policy 语言 / 不做 wire format** | 我们恰好做了（AST + REST 端点） | 这是 SPIP 划给 catalog 的部分，我们就是那个 catalog | 无差距，方向一致 |
| **写路径不在首期范围** | 我们也不做（§5.7） | 一致 | 无 |

一条要注意的**反向差距**：SPIP 说标准 mask 会「参与 whole-stage codegen」，我们的白名单表达式同样是内置
函数，codegen 不退化；但如果将来为了 §7.6 加一层自定义 `Expression` 包装，**必须委托 inner 的
`doGenCode`**，否则整个 mask 会退化成 interpreted 执行——性能会掉一个数量级，而且不掉在测试里。

## 附录 B. Ranger mask 枚举 → 本方案的 AST

迁移已有 Ranger 策略时用这张表。左边是 Ranger Hive service-def 的 `dataMaskType`，
中间是它真正的语义，右边是我们存的形状（标准集见 §5.3.2.1）：

| Ranger | 语义 | 本方案 |
| --- | --- | --- |
| `MASK` | 逐字符替换：大写→`X`、小写→`x`、数字→`n`（与 Hive `mask()` 一致） | `{"fn": "mask_alphanum"}` |
| `MASK_SHOW_FIRST_4` | 保留前 4 个字符，其余按 `MASK` 规则替换 | `{"fn": "show_first_n", "args": [4]}` |
| `MASK_SHOW_LAST_4` | 保留后 4 个字符 | `{"fn": "show_last_n", "args": [4]}` |
| `MASK_HASH` | 单向哈希；Ranger 委托 Hive `mask_hash`，算法随 `hive.masking.algo`（默认 SHA-256、可设 SHA-512、Hive 2.x 是 MD5） | `{"fn": "hash", "args": ["SHA-256"]}`——**算法必须写死**，不能读集群配置（§3.4 第 4 条） |
| `MASK_NULL` | 置空 | `{"fn": "replace_with_null"}` |
| `MASK_NONE` | 不脱敏（用于例外） | 不建这条 mask；不要用「空表达式」冒充 |
| `MASK_DATE_SHOW_YEAR` | 日期只保留年 | `{"fn": "truncate_to_year"}` |
| `CUSTOM` | 自定义 Hive UDF / 表达式 | 只有 `{"expr": ...}` 逃生口，且被白名单限死。**原本是任意 UDF 的必须人工改写**——我们不支持服务端持久函数（§5.3.2） |

两个迁移陷阱：

1. `MASK_HASH` 迁过来之后**结果会变**（如果原集群设过 `hive.masking.algo`）。这不是 bug，但下游
   如果有基于旧哈希做 join 或缓存的作业，会静默对不上。迁移前先在原集群确认这个配置。
2. `CUSTOM` 一律不能自动转换。控制台要把它标成「需人工处理」，而不是悄悄跳过——跳过的语义是
   **明文**。

## 附录 C. 本文档的外部依据

| 依据 | 用途 | 可信度说明 |
| --- | --- | --- |
| [SPARK-59726](https://issues.apache.org/jira/browse/SPARK-59726)（SPIP 全文） | §2.1 / §3.1 / 附录 A 的语义与 API 形状 | 官方提案，Q2 的非目标与 Q8 的验收矩阵是引用来源 |
| 本机 `~/.m2` 的 `spark-catalyst` 3.5.9 / 4.1.2 / 4.2.0 jar | §1.2 第一条「没有原生 API」 | **jar 实测**，比搜索结论可靠；复核命令见 §3.2 的 `javap` 做法 |
| [Kyuubi #3186](https://github.com/apache/kyuubi/issues/3186) | §3.4 第 1、2 条（DSv2 缺口、静默放行） | 真实 issue，含期望计划与物理计划对照 |
| Kyuubi AuthZ 插件与 Ranger Hive service-def 的公开资料 | §3.4 第 3、4 条、附录 B | 插件机制与 mask 枚举来自项目文档与实现；`hive.masking.algo` 的行为需在原集群复核 |
| [Lake Formation data filters](https://docs.aws.amazon.com/lake-formation/latest/dg/creating-data-filters.html) / [EMR 集成](https://docs.aws.amazon.com/emr/latest/ManagementGuide/emr-lake-formation.html) | §2.2 的单元格级、嵌套字段、凭据贩卖 | AWS 官方文档；EMR 侧的过滤位置（引擎内 vs record server）官方描述有出入，本文只采用「凭据贩卖 + 引擎内过滤」这一确定部分 |

