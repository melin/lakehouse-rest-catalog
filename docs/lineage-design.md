# SQL 血缘设计：从「执行过的 INSERT」里抽出表级与列级依赖

> 状态：**设计稿，未实现**。本文只定方案、模型与契约，不含代码。
> 一句话结论：**血缘必须在引擎侧抽，服务端只做接收、归一、存储与查询**——因为只有引擎手里
> 有「已解析、已展开、真正执行了」的那棵计划树；服务端重解析一遍只能得到近似值，而近似值
> 在血缘这种「拿去做影响面判断」的元数据上等于错误值。
>
> 覆盖范围：Spark（3.5.9 / 4.1.2）与 Flink（1.20.1 / 2.3.0）的 `INSERT` 家族语句；服务端的归一化、
> 存储与查询；控制台的图与列映射展现（§9，含渲染性能、上下游很多、字段很多三种场景）。
> 与已有文档的关系：`event-framework.md`（**不塞进 `audit_event`**，理由见 §7.6）、
> `data-model.md`（落地后表数 20 → 23）、`spark-sql-extension.md`（SQL 文本的唯一来源，
> 见 §2.4）、`flink-rest-catalog.md`（Flink 侧怎么连）、`console-table-detail.md`（页签伸缩规则）。

---

## 0. 能力边界（先看这张表）

| 问题 | 本期 | 说明 |
| --- | --- | --- |
| 支持哪些语句 | `INSERT INTO` / `INSERT OVERWRITE` / `INSERT ... BY NAME` / `CTAS` / `REPLACE TABLE AS` / `MERGE INTO`（只取写目标） | 统一叫「INSERT 家族」，判定依据是**计划节点类型**而不是 SQL 前缀 |
| 粒度 | 表级 + **列级**（含表达式文本） | 列级是本期的主要价值，也是唯一必须依赖引擎的理由 |
| 展现 | 表级 DAG（Canvas，≤ 500 节点）+ 列映射虚拟列表；超预算自动降级为表格 | 图只画结构、列级走列表；布局是 `(图, root)` 的纯函数，位置稳定（§9） |
| 采集时机 | **执行成功后** | 失败的 INSERT 不进图：它没有让任何数据依赖成立 |
| 两种表格式 | Paimon / Iceberg 一视同仁 | 血缘的键是「目录里那个名字」，与底层格式无关 |
| 非目录对象 | 临时视图、文件路径、`VALUES`、外部连接器表 → `EXTERNAL` 节点 | 用 `~` 前缀与真表名隔离（§3.1） |
| P2（二期） | 服务端 best-effort 解析兜底（只表级，`confidence=LOW`）、`AS_WRITTEN` 口径、下游影响面传播 | 见 §1.3、§3.6、§12 |
| 不做 | 行级 / 文件级血缘、执行期数据面采样、自动从查询日志反推、血缘图的定时重算、图上的手工增删边 | 见 §12 |

三个数字先摆出来，免得后面反复解释：

- **一次 INSERT = 一行 `lineage_run`**（历史事件）+ **N 行表级边** + **M 行列级边**（当前状态，upsert）。
- 边表是**状态**不是日志：同一对 `(源, 目标)` 只存在一行，重复执行只更新 `run_count` / `last_seen`。
- 上报是**异步且可丢**的：队列满就丢，绝不因为上报失败让作业失败（§6.4、§6.6）。

---

## 1. 为什么采集点在引擎侧

### 1.1 一次 INSERT 在两个引擎里分别是什么

| | Spark | Flink |
| --- | --- | --- |
| 采集点 | `QueryExecutionListener#onSuccess(funcName, qe, durationNs)` | 执行前 `TableEnvironmentInternal#getParser().parse(sql)` |
| 计划形态 | 已分析逻辑计划（`qe.analyzed()`） | `ModifyOperation` + `QueryOperation` 树 |
| 目标表 | `InsertIntoStatement#table` 等节点里的 `DataSourceV2Relation` / `HiveTableRelation` | `SinkModifyOperation#getContextResolvedTable().getIdentifier()` |
| 目标列清单 | `userSpecifiedCols()` / `byName()` / `partitionSpec()` | `getTargetColumns()`（`INSERT INTO t (a,b)` 时非空） |
| 列映射的原料 | `AttributeReference.exprId` 贯穿整棵树 | `ProjectQueryOperation#getProjectList()` 里的 `ResolvedExpression`，`FieldReferenceExpression#getInputIndex()/getFieldIndex()` |
| SQL 文本 | **拿不到**（`funcName` 是动作名，不是 SQL），要从 §2.4 的解析器扩展顺手留一份 | 本来就有（是它自己传进去的） |

两边的原料都已经是「名字解析完、列对齐完、类型转换完」的形态。这就是本期方案的立足点：
**我们不解析 SQL，我们读引擎解析完的结果**。

### 1.2 服务端重解析会错在哪

不是「精度差一点」，而是**错**，且有五个具体的错法：

| 反例 | 服务端纯文本解析会得到什么 | 真实执行的是什么 |
| --- | --- | --- |
| `INSERT INTO t SELECT * FROM s`，其间 `s` 加过一列 | 按服务端库里 `s` 的**当前** schema 对齐 → 列序与执行时刻不一致 | 按执行时刻的 schema 展开，Spark 与 Flink 对「多出来的列」处理还不一样 |
| `INSERT INTO t PARTITION (dt) SELECT ...` | `dt` 是动态分区，它的值来自查询的最后一列——纯文本看不出是哪一列 | `dt` 按普通列解析，映射到 query 的对应输出 |
| `INSERT INTO t SELECT * FROM v`（v 是视图） | 要么展开视图（＝自己实现一个 analyzer），要么把 v 当源表 | 视图按定义时刻的 schema 展开成底层表 |
| `SELECT a FROM x UNION SELECT b FROM y` | 按位置猜对齐；隐式类型转换不知道 | 引擎按位置对齐并插入 cast，列映射可能经过 `CAST` |
| Flink 的 `EXECUTE STATEMENT SET BEGIN ... END` | 完全不认识 | 一个提交里多个 sink，需要 run 1:N 目标 |

血缘的下游用法是「这张表变了，谁受影响」。一个错了列序的映射会把影响面指到无关的列上，
比没有血缘更糟——**没有血缘时人会去查，有错误血缘时人不会查**。

### 1.3 那服务端还解析吗

留一条**降级路径**，但不作为主路径：

- 触发条件：`POST /lineage/runs` 里没有列级信息、只有 `sqlText`（例如调度平台的 SQL 日志接入）。
- 能力上限：**只出表级边**，`confidence=LOW`，`source=SQL_TEXT`，图上一律显示为虚线。
- 技术选型：JSqlParser（只认 `INSERT INTO ... SELECT` / `CTAS` 的常见形状），不引入 Calcite——
  服务端目前完全不带引擎依赖，引入 Calcite 等于把 Flink 的方言包袱搬进元数据服务。
- 优先级：P2。**只有在前端埋点不可行时**（第三方调度器只留了 SQL 日志）才做。

### 1.4 为什么不是「SQL 文本 + 目录元数据自己算」

这是第 1.2 节的同义反复，但值得单独说一句：这会得到一个**看起来能用**的实现——
90% 的 ETL 是 `INSERT INTO ... SELECT ... FROM ... WHERE`，手写解析器两天就能覆盖。
剩下 10% 的错会集中在最复杂的那些 SQL 上（多级 CTE、窗口、`UNION`、视图、动态分区），
而那些恰恰是最需要血缘的场景。这条路的失败方式是「静默地错」，因此排除。

---

## 2. 两个引擎的原料（事实核对）

### 2.1 Spark：`QueryExecutionListener`

```scala
// Spark 3.5.9 与 4.1.2 的签名一致（javap 核对过）
public interface QueryExecutionListener {
  void onSuccess(String funcName, QueryExecution qe, long durationNs);
  void onFailure(String funcName, QueryExecution qe, Exception exception);
}
```

注册方式：`spark.listenerManager.register(listener)`（`org.apache.spark.sql.util.ExecutionListenerManager`）。
`funcName` 是动作名（`command` / `collect` / `saveAsTable` / `insertInto`），**不含 SQL**。

`qe.analyzed()` 给的是 `LogicalPlan`，本期要认的节点（3.5.9 与 4.1.2 的 `spark-catalyst` 里
**八个类都在**，已用 javap 核对）：

| 节点 | 场景 | 目标表在哪 |
| --- | --- | --- |
| `InsertIntoStatement` | `INSERT INTO` / `OVERWRITE`（V1 与 V2 统一后的形态） | `table()` |
| `AppendData` | V2 追加（DataFrame `writeTo` 也会走到） | `table()` |
| `OverwriteByExpression` | V2 带条件覆盖 | `table()` |
| `OverwritePartitionsDynamic` | V2 动态分区覆盖 | `table()` |
| `CreateTableAsSelect` | `CREATE TABLE ... AS SELECT` | `name()`（**表刚建，可能还不在目录响应里**） |
| `ReplaceTableAsSelect` | `REPLACE TABLE ... AS SELECT` | `name()` |
| `InsertIntoHadoopFsRelationCommand` | V1 `saveAsTable` 落到文件关系 | `outputPath` + `catalogTable` |
| `MergeIntoTable` | `MERGE INTO` | `targetTable` |

### 2.2 Flink：`Parser` → `Operation` 树

```java
// TableEnvironmentInternal 在 1.20.1 与 2.3.0 都存在，且都暴露 getParser()
public interface TableEnvironmentInternal extends TableEnvironment {
    Parser getParser();
    CatalogManager getCatalogManager();
    ...
}
// Parser#parse(String) → List<Operation>
```

关键发现：**Flink 侧不需要 planner 内部类**。`flink-table-api-java` 公开的
`org.apache.flink.table.operations` 包里已经有完整的结构化操作树：

| Operation | 用途 | 关键访问器 |
| --- | --- | --- |
| `SinkModifyOperation` | `INSERT INTO` | `getContextResolvedTable()`、`getTargetColumns()`、`getChild()`、`isOverwrite()` |
| `CreateTableASOperation` | `CREATE TABLE ... AS SELECT` | `getCreateTableOperation()`、`getChild()` |
| `QueryOperation`（接口） | 所有查询节点 | `getResolvedSchema()`、`getChildren()` |
| `ProjectQueryOperation` | `SELECT` 列表 | `getProjectList(): List<ResolvedExpression>` |
| `FilterQueryOperation` / `SortQueryOperation` | 只影响行不影响列 | 透传 `getChildren().get(0)` |
| `JoinQueryOperation` | 连接 | `getJoinType()`、`getCondition()`、`isCorrelated()` |
| `AggregateQueryOperation` | 聚合 | `getGroupingExpressions()`、`getAggregateExpressions()` |
| `SetQueryOperation` | `UNION` / `INTERSECT` / `EXCEPT` | `getType()`、`isAll()` |
| `ValuesQueryOperation` | `VALUES` | `getValues()` |
| `SourceQueryOperation` | 叶子表 | `getContextResolvedTable()` |

`ResolvedExpression`（`flink-table-common`）的两个访问器让列级映射变成纯结构遍历，不用碰字符串：

```java
public interface ResolvedExpression extends Expression {
    DataType getOutputDataType();
    List<ResolvedExpression> getResolvedChildren();
}
// FieldReferenceExpression：既给字段名，也给「来自第几个输入、第几列」
public final class FieldReferenceExpression implements ResolvedExpression {
    public String getName();
    public int getInputIndex();   // 对应 getChildren() 的下标
    public int getFieldIndex();   // 对应那个 child 的 getResolvedSchema() 下标
}
```

### 2.3 两个引擎的对照

| 维度 | Spark | Flink |
| --- | --- | --- |
| 能拿到列级映射 | 能（`exprId` 贯穿） | 能（`inputIndex`/`fieldIndex`） |
| 能拿到「按 SQL 写的源」 | 不能（analyzed 已展开视图） | 部分能（Operation 层保留视图对象） |
| 能拿到 SQL 文本 | 不能，需旁路留存 | 能（本来就是入参） |
| 需要版本隔离的代码 | 计划节点匹配（小） | Operation 类型集合（小，2.3 有类被删） |
| 多语句提交 | 一次一条 | `STATEMENT SET` 一次多 sink |

### 2.4 为什么不在 Spark 的 `injectParser` 里做

本仓已经有 `ManagementSparkExtensions`（`docs/spark-sql-extension.md`），看起来顺手——
**但解析器拿到的只是文本**，此时名字未解析、`SELECT *` 未展开、视图未内联、类型未转换。
从文本到「哪一列来自哪一列」之间差着整个 analyzer。

那这个既有扩展在血缘里扮演什么角色？**只做一件事：把最近一次 `parsePlan` 的 SQL 文本留在
`ThreadLocal` 里**，供同线程随后触发的 `onSuccess` 取走（`spark.sql(...)` 是同步的，
解析与执行同线程；`DataFrame` 写入路径没有解析，`sqlText` 留空即可）。
一句话：解析器负责「记事」，监听器负责「算账」。

### 2.5 为什么不用物理计划或执行统计

- **物理计划**（`qe.executedPlan()`）：列信息已被折叠成表达式与分区裁剪，`Attribute` 的名字
  与来源表已经丢失（只剩 `Exchange` / `Scan` 的 `output`），反推列级血缘比逻辑计划难一个数量级。
- **执行统计 / metrics**（`numOutputRows` 之类）：回答的是「写了多少行」而不是「依赖谁」，
  属于 `metrics-design.md` 的范畴，不要混进来。

---

## 3. 归一化模型

两个引擎的原料形状完全不同，**归一化必须在客户端完成**——服务端不认识 `AttributeReference`，
也不该认识。归一化后的模型只有三个概念：**节点、表级边、列级边**。

### 3.1 节点：`TableRef`

```
{ref} = {catalog}.{namespace...}.{table}
```

| 规则 | 说明 |
| --- | --- |
| 三段式，namespace 可多级 | Paimon 的多级 namespace 用点号拼成一段（与 `NamespacePaths` 同口径），因此 `ref` 是「点号拼接的路径」而不是「正好三段」 |
| catalog 必填 | Spark 里没写 catalog 时取会话默认 catalog（`spark.sql.defaultCatalog`）；Flink 取 `getCurrentCatalog()`。**不允许省略**，否则同名表在两个 catalog 下会合并成一条边 |
| 大小写 | 原样保留。不做小写化——两个引擎的大小写折叠规则不同，归一化时统一折叠等于替引擎做决定 |
| 保留前缀 `~` | `~temp:<名字>`（临时视图 / 临时表）、`~file:<sha256 前 12 位>`（裸路径）、`~values`（纯常量源）、`~external:<标识>`（外部连接器表）。**`~` 是保留位**：服务端在建库 / 建表时拒绝 `~` 开头的名字（一行校验），保证不会与真表名冲突 |

为什么不是 `TABLE` / `VIEW` 两种节点，而只有 `TABLE` / `EXTERNAL`：

- Spark 的 analyzed 计划里视图已经展开成底层表，**根本见不到视图节点**；
- Flink 的 Operation 树里可能保留视图对象，若记成 `VIEW`，两边口径就不一致了。

统一口径：**节点只有「目录里点得开的东西」与「点不开的东西」两类**。视图算前者（它有名字、
有定义、能点开），只是它出现在血缘里的那一侧取决于引擎行为。这处不一致单列在 §3.6。

### 3.2 表级边

一条边表达「**目标表的数据依赖源表**」，方向固定为 `source → target`：

```
(sources[]) ──WRITE_FROM──▶ targets[0]
                              └── targets[1]   （Flink STATEMENT SET 一次多 sink 时）
```

- 存两列：`source_ref` / `target_ref`，查询时按 `direction` 决定用哪一列做过滤。
- 边带 `kind`：`TABLE`（直接读表）/ `EXTERNAL`（读的是外部节点）。混合时以 `TABLE` 为准，
  外部节点单独成边——这样「这条 ETL 依赖的是目录里的表还是某个 parquet 目录」一眼能看出来。
- **`WRITE_MODE`**：`APPEND`（`INSERT INTO`）/ `OVERWRITE`（`INSERT OVERWRITE`）/ `UPSERT`
  （`MERGE INTO`）/ `CREATE`（CTAS）。它不影响图的形状，但影响人怎么读这张图。

### 3.3 列级边：五种 `kind`

| kind | 含义 | 判定依据 |
| --- | --- | --- |
| `DIRECT` | 一对一直接搬列 | 目标表达式就是一个列引用，且能定位到具体输入列 |
| `DERIVED` | 经表达式变换的一对多 / 多对一 | 叶子能定位到 1..n 个输入列（`a+b`、`UPPER(name)`、`CAST(x AS ...)`） |
| `AGGREGATE` | 聚合结果 | 目标列来自聚合表达式（`SUM(x)`、`COUNT(*)`），`sources` 是聚合的入参列 |
| `CONSTANT` | 常量 / 静态分区值 | 叶子为空（`1`、`'x'`、`PARTITION (dt='2026-10-20')` 的 `dt`） |
| `UNRESOLVED` | 定位不到具体列 | 子树里有不可分析 / 不可定位的节点（UDF、窗口、相关连接），退化为「该子树引用到的列全集」 |

判定顺序就是上表从上到下，**先命中先算**。`UNRESOLVED` 是兜底，不是常态；它的存在是为了让
「不知道」这件事显式化——一条 `UNRESOLVED` 边在控制台上标灰并提示「该列经过无法解析的表达式」，
比编一个 `DIRECT` 出来诚实。

`expression` 字段存引擎给的规范化文本（Spark 的 `expr.sql`、Flink 的
`ResolvedExpression#asSerializableString()`），**截断到 512 字符**，仅供人看，不参与任何判定。

### 3.4 一个端到端例子

```sql
INSERT OVERWRITE TABLE paimon.dws.order_daily PARTITION (dt='2026-10-20')
SELECT o.user_id,
       SUM(o.amount)                        AS gmv,
       COUNT(DISTINCT o.order_id)           AS order_cnt
FROM paimon.ods.orders o
JOIN paimon.dim.users u ON o.user_id = u.id
WHERE u.is_active
GROUP BY o.user_id
```

归一化后：

```json
{
  "statementType": "INSERT",
  "writeMode": "OVERWRITE",
  "targets": [{
    "table": "paimon.dws.order_daily",
    "columns": [
      {"targetColumn": "user_id", "kind": "DIRECT",
       "expression": "o.user_id",
       "sources": [{"table": "paimon.ods.orders", "column": "user_id"}]},
      {"targetColumn": "gmv", "kind": "AGGREGATE",
       "expression": "sum(o.amount)",
       "sources": [{"table": "paimon.ods.orders", "column": "amount"}]},
      {"targetColumn": "order_cnt", "kind": "AGGREGATE",
       "expression": "count(distinct o.order_id)",
       "sources": [{"table": "paimon.ods.orders", "column": "order_id"}]},
      {"targetColumn": "dt", "kind": "CONSTANT",
       "expression": "'2026-10-20'", "sources": []}
    ]
  }],
  "sources": [
    {"table": "paimon.ods.orders", "kind": "TABLE"},
    {"table": "paimon.dim.users", "kind": "TABLE"}
  ]
}
```

注意两处容易漏的：

- `dim.users` 只出现在 `JOIN` / `WHERE` 里，**没有任何一列流进目标表**——它仍是表级依赖
  （这张表变了，结果可能变），但列级边上不出现。表级与列级不是同一组信息的粗细版本。
- `dt` 是静态分区，`sources` 为空，但它确实被写了，所以必须有这一行。

### 3.5 分区列与常量

三个引擎细节必须在这里定死，否则两边会算出不同的东西：

| 场景 | 处理 |
| --- | --- |
| 静态分区 `PARTITION (dt='x')` | 目标列 `dt` = `CONSTANT`，`sources` 空 |
| 动态分区 `PARTITION (dt)` / 不写分区 | `dt` 是 `query` 输出里的一列，按普通列解析 |
| 目标列多于 query 输出 | 多出来的列（通常是分区列）记 `CONSTANT`，`expression` 留空并标 `implicit=true` |
| 目标列少于 query 输出 | 不允许（引擎会报错，能走到 `onSuccess` 就不会出现） |

### 3.6 视图的两种口径（本期只做一种）

| 口径 | 含义 | 谁提供 | 本期 |
| --- | --- | --- | --- |
| `RESOLVED` | 引擎真正读的物理表（视图已展开） | Spark `analyzed` / Flink Operation 树 | ✅ 唯一入库口径 |
| `AS_WRITTEN` | SQL 文本里写的那个名字（可能是视图） | Spark `qe.logical()` / Flink parser 的原始 `Operation` | ❌ P2 |

选 `RESOLVED` 的理由：列级映射只有它有。代价要写在这里免得以后被当成 bug：
**Spark 侧写 `INSERT INTO t SELECT * FROM v` 时，图上看不到 `v` 这个节点**，只看到 `v` 的底表。
需要「按 SQL 写的」那一层时，P2 补 `AS_WRITTEN` 边（`origin=SQL`，与 `RESOLVED` 边并存不替换）。

---

## 4. Spark 侧算法

### 4.1 入口

```
QueryExecutionListener#onSuccess(funcName, qe, durationNs)
    │
    ├── 1. 判语句类型：qe.analyzed() 的根节点是否为 §2.1 的八类之一（`CTAS` 等）
    │      不是 → 直接返回（SELECT / DDL / DCL 全部跳过）
    ├── 2. 抽目标表与目标列（§4.3）
    ├── 3. 建 exprId 索引（§4.4）
    ├── 4. 逐目标列解析表达式（§4.5）
    ├── 5. 取 SQL 文本（ThreadLocal，可能为空）
    └── 6. 组装 LineageRun → 入队（§6.4）
```

第 1 步要认的节点见 §2.1 的表。**只认节点类型，不认 SQL 前缀**：`INSERT` 的文本长得像
`INSERT` 的语句，经 analyzer 之后可能变成 `AppendData`（`writeTo`）或
`InsertIntoHadoopFsRelationCommand`（`saveAsTable`），按前缀判断会漏。

### 4.2 目标表

| 节点 | 目标表来源 | 备注 |
| --- | --- | --- |
| `InsertIntoStatement` | `table()`（类型是 `LogicalPlan`，分析后为 `DataSourceV2Relation` 或 `HiveTableRelation`，从中取 `identifier` / `tableMeta.identifier`） | 已解析，直接拿三段名 |
| `AppendData` / `OverwriteByExpression` / `OverwritePartitionsDynamic` | `table()`（声明类型就是 `NamedRelation`，实际是 `DataSourceV2Relation`） | `AppendData` 另有 `isByName()` |
| `CreateTableAsSelect` / `ReplaceTableAsSelect` | `name()`（`LogicalPlan`，分析后是 `DataSourceV2Relation`；未解析时退化为 `tableName()` 的 `Identifier`） | 表刚建好，**可能还没进目录**：上报时按 `CREATE` 处理，服务端允许「目标表尚不存在」 |
| `InsertIntoHadoopFsRelationCommand` | `catalogTable`（`Option[CatalogTable]`）里的 `identifier`；为 `None` 时退化为 `~file:` | V1 裸路径写入 |
| `MergeIntoTable` | `targetTable` | 只取写目标，`MERGE` 的 `UPDATE`/`INSERT` 分支不拆分 |

### 4.3 目标列解析

目标列必须与 `query` 的输出列**按引擎的规则**配对，四种形状：

| 形状 | 判定字段 | 对齐规则 |
| --- | --- | --- |
| 全列按位置 | `userSpecifiedCols` 为空、`byName=false` | `table.output` 与 `query.output` 逐下标对齐 |
| 指定列清单 | `userSpecifiedCols` 非空 | 名单里的每个名字去 `table.output` 里查，再与 `query.output` 逐下标对齐 |
| 按名字 | `byName=true`（`AppendData` 上是 `isByName()`） | 目标列名 ↔ `query.output` 的同名列，**与顺序无关** |
| 带分区 | `partitionSpec` 非空 | 静态分区先落 `CONSTANT`；动态分区按普通列处理 |

`byName=true` 是最容易写错的一条：`INSERT INTO t BY NAME SELECT b, a` 里目标 `a` 来自
query 的第 2 列而不是第 1 列。按位置对齐会得到一张完全反了的图。

### 4.4 源列定位：`exprId` 索引

```
遍历 qe.analyzed() 下所有叶子 relation（DataSourceV2Relation / HiveTableRelation / 文件关系）
   对每个 output 属性 attr：
       index[attr.exprId] = (TableRef, attr.name)
```

`exprId` 在整棵逻辑计划里稳定：`Project` / `Aggregate` / `Join` / `Filter` 都不会给已有的
`AttributeReference` 换 `exprId`（换了就是新列）。因此「目标列 ← 某表达式 ← 叶子属性」这条链
可以一路回到底表，这是 Spark 侧能精确到列的全部根据。

`Union` 是**唯一的例外**：它的 `output` 是**新分配的** `exprId`，与两侧 child 的都不同。处理办法是
把 `Union` 的每个输出列展开成「child[0] 的第 i 列 + child[1] 的第 i 列 + …」，即一条目标列对多张源表。

### 4.5 表达式分类

对每个目标列 `i`，取 `query.output(i)` 的产出表达式，递归：

```
resolve(expr):
  AttributeReference(exprId)   → index 命中 ? DIRECT(该表该列) : UNRESOLVED(subtree.references)
  Alias / Cast / 其它一元包装     → resolve(child) 并把 kind 提升为 DERIVED
  Literal                      → CONSTANT
  AggregateExpression / 聚合函数 → AGGREGATE(递归 children 收集属性)
  Unevaluable（UDF / Window 等） → UNRESOLVED(subtree.references)
  其它                          → DERIVED(收集 subtree 里的全部 Attribute)
```

兜底用 `subtree.references`（`TreeNode` 自带，给出子树引用到的全部 `Attribute`）：
定位不到具体列时，「它可能读了这些列」比「什么都没读到」有用得多，但要如实标成 `UNRESOLVED`。

### 4.6 3.5 与 4.1 的差异

| 项 | 3.5.9 | 4.1.2 | 影响 |
| --- | --- | --- | --- |
| `InsertIntoStatement` 七个字段 | `(table, partitionSpec, userSpecifiedCols, query, overwrite, ifPartitionNotExists, byName)` | **完全相同**（javap 核对） | 抽取器可以共享一份 |
| `userSpecifiedCols()` 返回类型 | `scala.collection.Seq` | `scala.collection.immutable.Seq` | Scala 2.12 / 2.13 集合体系不同 → 调用处按 `Seq` 抽象写，别写死 `immutable` |
| `withNewChildrenInternal` 参数 | `IndexedSeq` | `immutable.IndexedSeq` | 只在人工构造 plan 的测试里出现 |

结论：**Spark 侧的抽取器可以放在 `shared/`**（这个模块本来就依赖 Spark，与
`ManagementSparkExtensions` 同处），不需要像 `ParserInterface` 那样在版本模块里分裂。
若 4.x 后续把 `InsertIntoStatement` 换成别的节点，只改匹配处，抽取逻辑不动。

---

## 5. Flink 侧算法

### 5.1 入口

```java
List<Operation> ops = ((TableEnvironmentInternal) tableEnv).getParser().parse(sql);
for (Operation op : ops) {
    if (op instanceof SinkModifyOperation || op instanceof CreateTableASOperation) { ... }
}
```

三点要注意：

1. `parse` 是**纯解析**，不执行、不连 catalog 的远端（表解析走 `CatalogManager`，会调
   `Catalog#getTable`，但那是读元数据不是跑作业）。因此可以在**执行之前**拿到完整 Operation 树。
2. 一次 `parse` 可能返回多个 `Operation`（`EXECUTE STATEMENT SET BEGIN ... END`）→ run 1:N。
3. `Parser` 在 `TableEnvironmentInternal` 上（`@Internal` 接口，但在公开 jar 里且两个版本都在）。
   这是本期唯一用到的「内部 API」，风险已写进 §12。

### 5.2 目标表与目标列

| 访问器 | 给出什么 |
| --- | --- |
| `getContextResolvedTable().getIdentifier()` | `ObjectIdentifier`：catalog / database / objectName 三段，`~` 前缀判定也在这一层 |
| `getTargetColumns()` | `INSERT INTO t (a, b)` 的列清单；**`null` 表示按位置全列对齐**（javadoc 原文："return null when no column list specified"） |
| `getStaticPartitions()` | 静态分区，`Map<String,String>` → 直接落 `CONSTANT` |
| `isOverwrite()` | `APPEND` / `OVERWRITE` |

`getTargetColumns()` 的类型是 `int[][]` 且标了 `@Internal`，**精确形状按 Flink 源码定**。
一期只依赖两种形状：`null`（按位置）与「与 query 输出等长的清单」（按清单）。
其它形状（重排 / 部分列）在实现时补齐，补不齐就整条 run 降级为表级。

### 5.3 `QueryOperation` 递归

对每个目标列下标 `i`，从 `getChild()` 递归回溯。每个节点的规则：

| 节点 | 输出列 → 上游 | 备注 |
| --- | --- | --- |
| `SourceQueryOperation` | 第 `i` 列 ← 该表的第 `i` 列 | 叶子，递归终点；`ContextResolvedTable` 给出 `TableRef` |
| `ProjectQueryOperation` | 第 `i` 列 ← `getProjectList().get(i)` 递归 | `FieldReferenceExpression(inputIndex, fieldIndex)` 指向 `getChildren().get(inputIndex)` 的第 `fieldIndex` 列 |
| `FilterQueryOperation` / `SortQueryOperation` | 改名透传：第 `i` 列 ← child 的第 `i` 列 | 只影响行 |
| `JoinQueryOperation` | 左表字段在前、右表字段在后，按 `getResolvedSchema()` 的字段序定位 | `isCorrelated()` 为真时顺序不保证 → 整列标 `UNRESOLVED` |
| `AggregateQueryOperation` | 前 N 列 = `getGroupingExpressions()`，其后 = `getAggregateExpressions()` | 前者 `DIRECT`/`DERIVED`，后者 `AGGREGATE` |
| `SetQueryOperation` | 按位置：第 `i` 列 ← 两个 child 各自的第 `i` 列 | `UNION` 类型不同会出现隐式 cast → 标 `DERIVED` |
| `ValuesQueryOperation` | 全部 `CONSTANT` | 纯 `VALUES` 源 |
| 其它（含 2.3 新增的） | `UNRESOLVED` + 该节点 `getChildren()` 的属性全集 | 兜底，**不能抛异常**——新版本多一个节点类型不该让整条血缘丢掉 |

`FieldReferenceExpression` 是这套算法的枢纽：`getInputIndex()` 直接告诉你「第几个 child」，
`getFieldIndex()` 告诉你「那个 child 的第几列」。两个整数，没有任何字符串解析。

### 5.4 1.20 与 2.3 的差异

用两个版本的 `flink-table-api-java` 包清单核对过：

| 类 | 1.20.1 | 2.3.0 | 影响 |
| --- | --- | --- | --- |
| `SinkModifyOperation` | ✅ | ✅ | 主入口稳定 |
| `ProjectQueryOperation` / `FilterQueryOperation` / `JoinQueryOperation` / `AggregateQueryOperation` / `SetQueryOperation` / `ValuesQueryOperation` / `SourceQueryOperation` | ✅ | ✅ | 递归主干稳定 |
| `CalculatedQueryOperation` | ✅ | **❌ 已移除** | 它对应「由 `DataStream` 转换来的表」。2.3 里这类源会以别的形态出现（或干脆不可解析）→ 兜底分支必须存在，且**不能在 switch 里写死枚举** |
| `CreateTableASOperation` | ✅ | ✅ | CTAS 支持 |

结论：**版本相关代码极小**（一个 `instanceof` 链 + 兜底），放
`lakehouse-rest-flink/shared` 会违反「shared 主源码不 import Flink」的既有约束（见
`docs/flink-rest-catalog.md` §1）。因此：

- `shared/src/main`：归一化模型 + 上报器 + JSON 序列化（**零 Flink import**，与
  `LakehouseRestFlinkCatalogs` 同一层）；
- `lakehouse-rest-flink-1.20` / `-2.3` 各自的 `src/main`：Operation 遍历器（约 200 行，
  两份）。这与 `LakehouseRestSqlParser` 在两版本各有一份的分裂方式一致——有先例。

---

## 6. 上报

### 6.1 端点

```
POST /api/management/v1/lineage/runs          # 批量上报，单次最多 50 条 run
GET  /api/management/v1/lineage/tables        # 表级上下游（§8.1）
GET  /api/management/v1/lineage/columns       # 列级映射（§8.2）
GET  /api/management/v1/lineage/runs          # 运行历史（§8.3）
```

为什么挂在**管理 API** 而不是 catalog API（`/v1/{prefix}/...`）：

1. 血缘是**跨 catalog** 的元数据，`{prefix}` 只是其中一个维度；
2. 客户端已经有管理 API 的地址与令牌（`spark.paimon.rest.management.url` /
   `spark.paimon.rest.token`），Flink 侧也只是多一个 HTTP 客户端，不需要新的配置世界；
3. catalog API 的每个端点都必须在 `CatalogAccessRules` 登记权限映射、并在
   `CatalogEndpointAuthorizationTests` 里覆盖——血缘不该挤进那张表（它是「关于表的关系」，
   不是「表上的操作」）。

### 6.2 报文

```json
{
  "runs": [{
    "runId": "9f2c1e7a-4b3d-4c8e-9f01-2a3b4c5d6e7f",
    "engine": "SPARK",
    "engineVersion": "3.5.9",
    "statementType": "INSERT",
    "writeMode": "OVERWRITE",
    "sqlHash": "sha256:6b1f...",
    "sqlText": "INSERT OVERWRITE TABLE ...",
    "startedAt": 1760943000000,
    "finishedAt": 1760943012345,
    "mode": "BATCH",
    "targets": [{
      "table": "paimon.dws.order_daily",
      "columns": [
        {"targetColumn": "user_id", "kind": "DIRECT", "expression": "o.user_id",
         "sources": [{"table": "paimon.ods.orders", "column": "user_id"}]}
      ]
    }],
    "sources": [{"table": "paimon.ods.orders", "kind": "TABLE"}],
    "attributes": {"spark.app.id": "app-20261020150000-0001"}
  }]
}
```

| 字段 | 必填 | 约束 |
| --- | --- | --- |
| `runId` | ✅ | 客户端生成的 UUID，**幂等键**（§6.3） |
| `engine` | ✅ | `SPARK` / `FLINK`，未知值 400 |
| `engineVersion` | ✅ | 引擎自报版本；不同版本的列映射规则不同，排障时要靠它 |
| `statementType` | ✅ | `INSERT` / `MERGE` / `CTAS` |
| `sqlText` | ❌ | 截断到 8KB；拿不到就省略（Spark 的 DataFrame 路径） |
| `sqlHash` | ❌ | 有 `sqlText` 时由客户端算（SHA-256），用于「同一条 SQL 反复跑」的聚合 |
| `mode` | ✅ | `BATCH` / `STREAMING`（§12 第 8 条：流式作业的单位是「提交」不是「每次写入」） |
| `targets[].columns` | ❌ | 缺省即只有表级；超过 `max-columns-per-target` 时服务端降级为表级并标 `truncated` |
| `attributes` | ❌ | 自由 JSON，同 `audit_event.attributes_json` 的口径 |

**`principal` 不在报文里**：由服务端从令牌解析。客户端自报身份等于让人自己填工号。

### 6.3 幂等

- 唯一键 `run_id`。重复 `POST` 同 `runId` → `200` + `{"deduplicated": true}`，**不重复写边**。
- 客户端失败重试（网络抖动、5xx）不会产生两条 run，也不会让 `run_count` 多加。
- `409` 只用于「同 runId 但内容不同」——这一定是客户端 bug（UUID 撞了），直接报错而不是覆盖。

### 6.4 异步、背压、丢弃

```
onSuccess（driver 线程）                    reporter 线程（单线程，守护）
    │  offer(run)                              │
    ├── 队列未满 → 立即返回，作业继续           ├── 批量取（最多 50，或攒 2 秒）
    └── 队列满   → 丢弃 + dropped 计数 + WARN   ├── POST（超时 5s）
                                               ├── 5xx / 超时 → 指数退避重试 2 次
                                               └── 最终失败 → failed 计数 + WARN，丢弃
```

三条硬约束：

1. **`onSuccess` 里只做 `offer`**，绝不做网络 IO。`onSuccess` 在作业收尾路径上，同步 POST
   等于把 5 秒超时加到每个 `spark.sql` 的尾部延迟上。
2. **绝不抛异常到上层**。整个回调包一层 `try/catch(Throwable)`：监听器抛异常会让
   `spark.sql` 报一个与 SQL 无关的错，那是灾难性的——用户会去查 SQL。
3. **队列满就丢**。血缘是「可以缺一条，不能拖慢作业」的数据。丢弃计数通过客户端的
   日志与（将来的）指标暴露；服务端也能从 `run_count` 的异常分布看出漏报。

### 6.5 谁可以上报（判权）

| 检查 | 规则 | 不满足 |
| --- | --- | --- |
| 认证 | `BearerAuthInterceptor` 解析出主体（与目录 API 同一套） | `401` |
| 目标表 | 对每个 `targets[].table` 需 `TABLE_WRITE_DATA` | `403`，报文里指出**第一个**不足的资源 |
| 源表 | 对每个 `sources[].table` 需 `TABLE_READ_PROPERTIES` | 同上 |
| 外部节点（`~` 前缀） | 不判权——它不是目录里的东西 | — |

判权复用 `AuthorizationService.coversScope` + `NamespacePaths` 那套（与 Iceberg 侧的
`…/tables/{t}/metrics` 判 `TABLE_WRITE_DATA` 同口径），不新造一套判定。

两条刻意的取舍：

1. **整条 run 拒绝，不做部分接受。** 部分接受会让图出现「只有半边」的假象：目标表有边、
   源表没边，看起来像「这张表凭空被写」。宁可整条不入库，让人看到 403。
2. **不校验血缘的真伪。** 血缘是客户端自述，服务端无法验证「这条 SQL 真的这么写」——
   能做到的上限是「确认你有权动这些表」，也就是「能写这张表的人才允许声称写了它」。
   伪造的危害是误导下游使用者，缓解手段是：`principal` 由服务端解析（不采信报文）、
   边带 `engine` 与 `lastSeen`、`sql_hash` 可回溯。**不做签名**——这是内网元数据服务的合理边界。

### 6.6 上报失败绝不影响作业

这条要单独强调，因为它是整个设计的**优先级声明**：血缘是元数据，作业是生产。
任何「为了记血缘而让 ETL 失败」的实现都是错的，包括：令牌过期时抛异常、
队列满时阻塞、JSON 序列化失败时冒泡。失败一律降级为一条 WARN 日志。

---

## 7. 存储

### 7.1 两张语义不同的表

| | `lineage_run` | 边表（`lineage_table_edge` / `lineage_column_edge`） |
| --- | --- | --- |
| 语义 | **事件**：某时某刻跑了一次 | **状态**：现在谁依赖谁 |
| 写 | 只插入 | upsert（唯一键命中则更新 `run_count` / `last_seen`） |
| 增长 | 与执行次数成正比（1e4/天） | 与图规模成正比（1e3~1e4 稳态） |
| 保留 | 90 天（可配），过期删 | 长期；表被删时级联删边 |
| 查询 | 「这张表最近谁写过」 | 「这张表的上游 / 下游是谁」 |

这个划分是整份设计里最影响成本的决定。若边也按 run 存（一次执行一组边），一年就是
**上亿行**，而其中 99% 是重复的同一对表；图查询还要 `DISTINCT` 去重。反过来若 run 不存，
「谁在什么时候写的」就查不到了——那是审计与排障最需要的一条信息。

代价如实写在这里：**看不到「历史上某次执行时的列映射」**。只有「当前映射」+「最近一次是谁写的」
（`last_run_id` 指回 run，90 天内可下钻）。

### 7.2 三张表

均放 `lakehouse-rest-core`（被协议面与 server 共用，理由同 `data-model.md`：按「谁读它」分家）。
表名接 `lakehouse_` 前缀，落地后 `data-model.md` 的表数从 20 变 23。

**`lakehouse_lineage_run`**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint 自增 PK | 排障用的顺序号 |
| `run_id` | varchar(64) | 客户端 UUID，`uk_lineage_run` |
| `engine` | varchar(16) | `SPARK` / `FLINK` |
| `engine_version` | varchar(32) | 引擎自报版本 |
| `statement_type` | varchar(16) | `INSERT` / `MERGE` / `CTAS` |
| `write_mode` | varchar(16) | `APPEND` / `OVERWRITE` / `UPSERT` / `CREATE` |
| `mode` | varchar(16) | `BATCH` / `STREAMING` |
| `catalog` | varchar(255) | 主 catalog（目标表所在的那个），供按目录过滤 |
| `principal` | varchar(255) | 服务端从令牌解析，归一化走 `AuditPrincipal` |
| `sql_hash` | char(64) | 可空；有 SQL 文本时才有 |
| `sql_text` | text | 截断 8KB |
| `started_at` / `finished_at` | bigint | epoch 毫秒 |
| `attributes_json` | text | 自由属性，`ObjectMap` 转换器（与 `audit_event` 同款） |

索引：`uk (run_id)`、`(catalog, finished_at)`、`(finished_at)`（清理用）。

**`lakehouse_lineage_table_edge`**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint 自增 PK | |
| `source_ref` | varchar(1024) | 全名，含 `~` 前缀的外部节点 |
| `source_ref_hash` | char(64) | `source_ref` 的 SHA-256，**唯一键的一部分** |
| `target_ref` / `target_ref_hash` | varchar(1024) / char(64) | 同上 |
| `edge_kind` | varchar(16) | `TABLE` / `EXTERNAL` |
| `engine` | varchar(16) | 同一对表可能被 Spark 与 Flink 各自写过 → 参与唯一键 |
| `run_count` | bigint | 累计次数 |
| `first_seen` / `last_seen` | bigint | epoch 毫秒 |
| `last_run_id` | varchar(64) | 下钻到 run |

唯一键 `uk_lineage_table_edge (source_ref_hash, target_ref_hash, engine)`。
**为什么用哈希而不是全名字段本身**：`varchar(1024)` 在 utf8mb4 下是 4096 字节，超过 MySQL
单列索引上限（3072），两个拼起来更不可能。这跟 `data-model.md` 里既有的「摘要列」是同一个手法。

索引：`(target_ref_hash)` 支撑「下游」、`(source_ref_hash)` 支撑「上游」。

**`lakehouse_lineage_column_edge`**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint 自增 PK | |
| `source_ref` / `source_ref_hash` | varchar(1024) / char(64) | 上游表 |
| `source_column` | varchar(255) | 上游列 |
| `target_ref` / `target_ref_hash` | varchar(1024) / char(64) | 下游表 |
| `target_column` | varchar(255) | 下游列 |
| `transform_kind` | varchar(16) | `DIRECT` / `DERIVED` / `AGGREGATE` / `CONSTANT` / `UNRESOLVED` |
| `expression` | varchar(512) | 最近一次的规范化表达式文本，仅供展示 |
| `last_run_id` | varchar(64) | 下钻 |
| `last_seen` | bigint | |

唯一键 `uk_lineage_column_edge (source_ref_hash, source_column, target_ref_hash, target_column)`。
`kind` 与 `expression` 取**最近一次**（同一对列在不同 run 里可能先 `DIRECT` 后 `DERIVED`）。

### 7.3 为什么不做成一张表

一条 run 里「表级边」与「列级边」是 1:N，列级边又挂在表级边下；合成一张表要么重复存
`run_id`（列级边 × 列数），要么把列映射塞进 JSON 列（图查询就退化成全表扫描 + 反序列化）。
三张表 + 两个唯一键是能同时支撑「按表查上下游」与「按 run 下钻」的最小结构。

### 7.4 容量估算

假设：日 1 万次 INSERT，平均每次 1 个目标、3 张源表、30 条列级边，图稳态 5000 张表。

| 表 | 日增 | 稳态行数（含 90 天 run） |
| --- | --- | --- |
| `lineage_run` | 1 万 | 90 万 |
| `lineage_table_edge` | 去重后趋零 | ≈ 图边数，1~5 万 |
| `lineage_column_edge` | 去重后趋零 | ≈ 图列边数，20~50 万 |

写入压力集中在 `lineage_run`（1 万/天 ≈ 0.12 TPS），边表是 upsert 命中为主。
这个量级对 MySQL 没有压力——**前提是边表按状态存**，按 run 存的话日增就是 30 万行。

### 7.5 保留与清理

- `lineage_run`：`lakehouse.rest.lineage.run-retention-days`（默认 90），由优化调度那套
  定时任务模式（`OptimizationSchedulingConfig`）挂一个每日清理。**不塞进 `OptimizationJob`**，
  那是表存储优化批次，语义不同。
- 边表：不按时间清。表被删（`table.dropped`）时由事件监听器顺手删掉相关边——
  这是本仓唯一适合复用 `CatalogEventListener` 的地方（`AuditEventPersistListener` 的邻居）。
  注意：**只删边，不删 run**（run 是历史事实，「这张表当年被谁写过」仍然有价值）。
- 孤儿边：清理任务顺带扫「`target_ref` / `source_ref` 指向不存在的 catalog」的边并计数报日志，
  **不自动删**——catalog 被临时下线时误删一整片图，比留着脏数据糟。

### 7.6 为什么不塞进 `audit_event`

| 维度 | `audit_event` | 血缘 |
| --- | --- | --- |
| 语义 | 「谁对目录做了什么」 | 「数据从哪来、到哪去」 |
| 量级 | 变更次数（千/天） | 执行次数（万/天） |
| 写模式 | 只插入，从不更新 | 边要 upsert（状态） |
| 查询 | 按游标增量拉 | 按表名做图遍历 |
| 订阅 | 有 `enabled-event-types` 订阅语义 | 无人会「订阅一条血缘」 |

硬塞进去会同时破坏两边：事件流被 ETL 淹没（游标拉取要翻几十页才看到一条真正的目录变更），
而血缘要的 upsert 语义在「只插入」的表上根本表达不了。

代价：`lineage_run.principal` 是唯一记录「谁上报了血缘」的地方，审计面上看不到。
接受——血缘不是变更，它不改任何持久状态。

---

## 8. 查询 API

### 8.1 表级上下游

```
GET /api/management/v1/lineage/tables?ref=paimon.dws.order_daily&direction=upstream&depth=2
```

| 参数 | 默认 | 说明 |
| --- | --- | --- |
| `ref` | 必填 | 归一化后的全名；含 `~` 前缀也接受（查外部节点） |
| `direction` | `downstream` | `upstream` / `downstream` / `both` |
| `depth` | `1` | 1..`max-depth`（默认 5），超了 400 而不是静默截断 |
| `engine` | 空 = 全部 | 按引擎过滤 |

用查询参数而不是路径段：多级 namespace 会让 `/tables/a.b.c.d` 的段数不定，
路径参数要拼编码，查询参数不用。

响应：

```json
{
  "root": "paimon.dws.order_daily",
  "nodes": [{"ref": "paimon.ods.orders", "objectType": "TABLE", "format": "PAIMON"}],
  "edges": [{"source": "paimon.ods.orders", "target": "paimon.dws.order_daily",
             "kind": "TABLE", "engine": "SPARK", "lastSeen": 1760943012345, "runCount": 132}],
  "truncated": false
}
```

`nodes` + `edges`（而不是嵌套树）：血缘图**可能有环**（`A → B → A` 的回刷是真实存在的），
嵌套结构在环上会无限递归或需要人为截断。扁平结构 + `visited` 集合去重是唯一稳的表示。

### 8.2 列级映射

```
GET /api/management/v1/lineage/columns?ref=paimon.dws.order_daily&direction=upstream
```

响应按**目标列分组**（人看血缘时想的是「我这个字段是从哪来的」）：

```json
{"ref": "paimon.dws.order_daily",
 "columns": [{"column": "gmv", "kind": "AGGREGATE",
              "upstream": [{"table": "paimon.ods.orders", "column": "amount",
                            "expression": "sum(amount)"}]}]}
```

同一列在多张源表都有贡献时，`upstream` 是数组——这正是 `JOIN` 之后的常见形态。

### 8.3 运行历史

```
GET /api/management/v1/lineage/runs?ref=paimon.dws.order_daily&engine=SPARK&since=1760900000000&limit=50
```

按 `finished_at` 倒序，游标用 `(finished_at, id)` 复合游标（同毫秒的并发写入靠 `id` 定序，
与 `event-framework.md` §4 的游标口径一致）。列表只回摘要（时间、主体、语句类型、`run_id`），
`sqlText` 与列级明细要单独 `GET /runs/{runId}`——列表页不该拖着 8KB 的 SQL。

### 8.4 遍历口径

| 情况 | 处理 |
| --- | --- |
| 环 | `visited` 集合，命中即不再展开；结果里保留形成环的那条边 |
| 同一对表被 Spark 与 Flink 各写过 | 两条边（`engine` 不同），不合并——「哪个引擎在写」是有效信息 |
| 表已从目录删除但边还在 | 节点标 `objectType=TABLE` + `stale=true`，边照常返回 |
| `depth` 超限 / 结果条数超 `max-nodes` | 返回已展开部分 + `truncated=true`，**不报错** |

### 8.5 鉴权

- 读：走管理 API 的既有门禁（与 `/api/management/v1/**` 同口径）。**不做表级 RBAC**——
  与事件页、`/meta` 一致；能看见目录的人能看见这张表的上游。真要细分，是在这一层前面
  加「按 catalog 过滤」，而不是给边表加 ACL。
- 写：见 §6.5 的判权（能写目标表 + 能读源表），逐条 run 判，不满足整条拒绝。

### 8.6 图客户端需要的额外契约（预算 / 紧凑编码 / `more` 标志 / ETag）

§8.1 的响应是**为表格设计的**：节点是对象、边是对象、表名在每条边上重复一遍。图的需求不同——
它要「一次拿够、别再请求」，而表格要「分页、按需」。追加四个参数，都不改变默认行为：

| 参数 | 默认 | 作用 |
| --- | --- | --- |
| `maxNodes` | 300 | 一次最多返回多少节点（先按跳数、再按 `runCount` 取）。硬上限 2000，超了 400 而不是静默截断 |
| `compact` | `0` | `1` 时节点/边改用「数组 + 整数下标」编码（见下），省掉重复的表名与键名 |
| `columnCount` | `0` | `1` 时边的 `columnCount` 由服务端聚合返回（`lineage_column_edge` 按表边 `GROUP BY`），图用它算线宽 |
| `If-None-Match` | — | 标准条件请求；`ETag = hash(max(lastSeen), nodeCount, edgeCount)`，命中回 304 |

响应追加一个 `budget` 块。**没有它，前端画不出「还有 812 张表没画」**——这是 9.4 折叠节点的数据来源：

```json
{"root": "paimon.dws.order_daily",
 "nodes": [...], "edges": [...],
 "budget": {"upstream": 812, "downstream": 3, "returned": 300, "truncated": true}}
```

`upstream` / `downstream` 是**真实总数**（服务端 `COUNT(DISTINCT)`，走 `(target_ref, last_seen)` 索引），
不是本次返回的条数，也不随 `maxNodes` 变化。

`depth` **边界层**的节点还带一个 `more` 标志：该方向再走一跳是否还有邻居。由服务端一条
`SELECT <方向列>, 1 ... WHERE <方向列> IN (边界节点集合) GROUP BY <方向列>` 得出，每个节点最多扫一行。
前端据此决定画不画 `›`：不画，图看起来"到头了"但其实没有；全靠猜，每次点击都可能白请求一次。

**图不分页。** 翻页会得到两个互不连通的碎片，而图的价值恰恰在连通性。要"分批"就用
`maxNodes` + 按需展开（9.4），它在语义上是「先给我邻居，我再决定往哪走」，而不是「第 2 页」。

`compact=1` 的形状（`v` 是版本号，将来加字段靠它分辨）：

```json
{"v": 1, "root": 0,
 "n": [["paimon.ods.orders", "PAIMON", "T", 0], ["paimon.dws.order_daily", "PAIMON", "T", 1]],
 "e": [[0, 1, "T", "A", "SPARK", 1760943012345, 132, 14]],
 "budget": {"upstream": 1, "downstream": 0, "returned": 2, "truncated": false}}
```

`n` 的四个位置：`[ref, format, kind, more]`；`e` 的八个位置：
`[src, dst, kind, writeMode, engine, lastSeen, runCount, columnCount]`，
枚举压成单字母（`kind`：`T`=`TABLE` / `E`=`EXTERNAL`；`writeMode`：`A`/`O`/`U`/`C`，见 §3.2）。

省掉的是重复字符串与重复键名。**gzip 之前能省 55% 左右，gzip 之后只省 15% 左右**（重复字符串本来
就被压缩掉了）。所以 `compact` 的真实收益在**解析**：`JSON.parse` 一个 300 项数组比解析 900 个带键
对象快，低端机上差 20–40ms。若将来真要省带宽，该换的是列式编码（每字段一个数组），不是继续压对象
——但那是 5000 节点以上才值得做的事，本方案不做。

---

## 9. 展现

> 本节推翻了初稿「不做图可视化」的结论，理由在 9.0。图是必做项，但必须带预算：**节点预算、
> 帧预算、列预算**。三条里任何一条不设，"看一眼全链路"就会变成"把浏览器卡死"。

### 9.0 为什么要推翻初稿的结论

本文初稿在这一节写的是「一张 3 列表格能回答的问题比一个力导向图多」。这话对了一半：**只看 1 跳时对，
看多跳时错**。

- 一张 DWS 表的 2 跳上游常有 30–80 张表。表格里它们是 80 行互不相干的记录，「哪几张表汇进了
  `gmv`」这个**结构**读不出来，得靠人脑在 80 行里拼；而结构恰恰是血缘唯一不可替代的输出——
  表名和列名在目录页也能查，查不到的只有「它们怎么连起来的」。
- 图的第二个价值是**共享节点的可见性**：`ods.orders` 同时喂 5 张 DWS，这件事在表格里要点开 5 张
  表才能拼出来，在图上是一条竖线。

初稿的成本估计（「画布成本是表格的十倍」）是在写代码之前拍的数字。逐项核过之后，真正贵的不是
绘制，是三样与「用不用图库」无关的东西：**布局稳定性、标签避让、折叠语义**。而这三样正是本节
要定死的（9.2 / 9.3 / 9.4）。

### 9.1 三种视图与自动降级

| 视图 | 什么时候是默认 | 规模上限 | 渲染方式 |
| --- | --- | --- | --- |
| 图 | 节点 ≤ 500（默认就是这条路） | 500 节点 / 2000 边 | Canvas 2D，单张画布 |
| 邻接表 | 节点 > 500，或用户手动切 | 无 | `el-table-v2` 虚拟滚动 |
| 列映射表 | 切到「列」页签时（与图的规模无关） | 无 | `el-table-v2` 虚拟滚动 |

三条硬规矩：

1. **三视图共用同一份数据**。图与邻接表是**同一次 `GET /lineage/tables` 的响应**
   （`compact` 解出来的那张图），切换**零请求、零重算**，只是换渲染器。否则「切过去看一眼再切回来」
   要白等两次网络，用户就不会切了。列映射表是另一次请求（§8.2），只在切到「列」页签时发。
2. **规模规则优先于偏好**。用户选过的视图记在 `localStorage`（`lakehouse.console.lineage.view`），
   但节点 > 500 时无论偏好都落表格，并在顶部提示条给一个「仍要看图」按钮——点了就画前 500 个节点
   + 一个 `+312` 占位节点（9.4 的折叠机制复用）。节点到服务端上限 2000 时这个按钮不再给：
   服务端已经截断，画出来是**假图**，比不画更糟。
3. **`prefers-reduced-motion` 只关动画，不换视图**。布局切换、展开折叠的过渡全部置零，图照画。

页签本身沿用 `console-table-detail.md` 那套「页签随表格式伸缩」的机制，但血缘这一页例外：
**它与表格式无关**（Paimon / Iceberg / 甚至 `format-table` 都能有），所以它在所有表上都出现。

### 9.2 布局：跳数即层号

血缘图有一个通用 DAG 没有的性质：**根的位置是给定的，而且"离根几跳"本身就是最有意义的横坐标**。
所以分层不用算，直接读出来：

```
rank(v) = +hops(v)   // 下游，画在根右边
rank(v) = -hops(v)   // 上游，画在根左边
```

三条细则：

- **多路径取 `min |rank|`**。同一节点可有两条路径、跳数不同，取最短的。否则一条 12 跳的长链会把
  图横向拉到 25 层，中间全是空的。代价是那条真实长链被压成一条**跨层长边**（跨过中间层画曲线），
  视觉上可接受。
- **环**：BFS 的 `visited` 天然阻断，回边画成向左的曲线并加 `↺` 角标，且**不参与 rank 计算**。
  业务上 `A → B → A` 的回刷是真实存在的，不能当错误处理。
- **层内顺序**：初值按 `ref` 字典序（为了确定性，见下），然后做**两遍重心排序**（barycenter）：
  上游层按下游邻居的平均 y 排，下游层按上游邻居的平均 y 排。两遍足够——第三遍的边际收益在视觉上
  不可辨，而每遍是 O(n log n)。

坐标：

```
x = rank * (W + gapX)                    W = 200, gapX = 80
y = (i - (n-1)/2) * (H + gapY)           H = 44,  gapY = 16
```

`y` 里减去 `(n-1)/2` 是**层居中**：单节点层不会贴在画布顶上。

**节点尺寸固定 200×44，不按内容自适应。** 固定尺寸让裁剪、命中测试、空间索引全部退化成整数比较；
代价是长表名要截断——用**中间省略**（`paimon.dws.…order_daily`），保留首段与末段。`paimon.dws.order_daily_…`
这种尾部截断看着像"又一张 order_daily"，中间省略至少能看出是哪张。

**为什么不 Sugiyama（dagre / ELK）**：通用分层布局要做「拆长边成虚拟节点 + 多层交叉最小化 +
坐标分配」三步，本图的层已经给定，只剩最后一小步。引 dagre 是 60KB 换一个用不上的中间阶段
（9.7 的取舍表）。

**为什么不是力导向**：结果不确定（每次打开都不一样）、需要 O(n²) 迭代上百轮、节点一多就成毛球。
力导向的前提是"无先验结构"，而血缘图有向、有层次、根固定——前提恰好不成立。

**确定性要单列**，因为它是交互的地基：图的空间记忆靠位置，刷新一次位置全变，用户就得重新找。
所以布局是 `(nodes, edges, root, filters)` 的**纯函数**：无随机数、无时间戳、无 `Math.random()` 打散。
三条可测的推论（§11.6）：

1. 同输入跑两次，坐标数组逐项相等；
2. 打乱输入顺序，坐标集合相同（排序必须用 `ref` 做 tie-break，不能依赖数组顺序）；
3. 展开某层后，**已放置节点的坐标不变**（9.4）。

### 9.3 渲染：单张 Canvas + 三档 LOD

| 方案 | 500 节点 / 1500 边重绘 | 命中测试 | 无障碍 | 额外体积 |
| --- | --- | --- | --- | --- |
| DOM 节点 + SVG 边 | 40–120ms（样式重算 + 布局 + 1500 个 `path`） | 免费 | 有 | 0 |
| **单张 Canvas 2D** | **3–8ms（批量 path）** | 空间网格，O(1) | **无** | 0 |
| WebGL | 1–2ms | 自己写 pick | 无 | 引擎 100KB+ |

（表里的毫秒是估算量级，测法与预算见 9.8。）

选 Canvas。WebGL 在这个量级省不下可感知的时间，却要付出"文字走纹理"的复杂度；DOM 方案在 500 节点
时已经越过 16ms 的帧预算。代价是 **Canvas 没有 DOM，就没有无障碍、没有 CSS 继承、没有 `Ctrl+F`**
——三样都得单独补（9.6 / 下面的主题处理）。

**一帧的管线**：

1. **早退**：`view`（pan/zoom/hover）与 `data` 都没变 → 不画；
2. **裁剪**：空间网格，cell = 256px，只取与视口相交的 cell 里的节点与边；
3. **边**：按（颜色, 线宽）分组，每组一个 `Path2D`，最后一次 `stroke()`——1500 条边从 1500 次
   draw call 降到 ≤ 4 次。这是单点收益最大的优化；
4. **节点**：按 LOD 选形状，`roundRect` / `fillRect`；
5. **高亮层**：hover 的节点与边最后画，保证压在最上面。

```js
// 一帧的骨架（伪代码）
function frame() {
  if (!dirty) return;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, cssW, cssH);
  ctx.translate(-view.x, -view.y);
  ctx.scale(view.k, view.k);
  const vis = grid.query(viewBounds());                    // 视口内的节点 / 边
  for (const [style, edges] of groupByStyle(vis.edges)) strokeBatch(edges, style);
  for (const n of vis.nodes) drawNode(n, lodFor(view.k));   // 标签在 drawNode 里按避让决定画不画
  drawHighlight(hover);
  dirty = false;
}
```

**三档 LOD**（缩放决定画什么），阈值带**滞回**，避免在临界点抖动：

| 缩放 k | 节点 | 标签 | 边 | 视口内图元 |
| --- | --- | --- | --- | --- |
| k ≥ 0.9（退档：0.86） | 圆角矩形 + 格式色条 + 引擎角标 | 全名，> 24 字符中间省略 | 2px 贝塞尔 + 箭头 | ≤ 60 |
| 0.55 ≤ k < 0.9（退档：0.52） | 矩形 + 格式色条 | 只画末段名 | 1.5px | ≤ 200 |
| k < 0.55 | 纯色矩形（高 12px） | 不画 | 1px，无箭头 | ≤ 800 |

**标签避让**：LOD ≥ 0.55 时，同层按 y 排序贪心放置，与已放置的标签框相交就**这一帧不画该标签**
（悬停时补画）。O(n log n)。不做全局最优——那是 O(n²)，在密集图上也就多放 5% 的标签，而人不看。

**位图缓存只开一种情况**：节点 ≤ 300 且图尺寸 ≤ 2048×2048 时，把静态层（边 + 节点底）预渲染到
离屏 canvas，pan 时 `drawImage` 裁一块；zoom 时先缩放位图（糊），停手 200ms 后重画矢量清晰版——
**先动起来，再变清晰**。大图不开缓存：4096² 的 canvas 是 64MB 显存，而裁剪已经把绘制量压到视口内
（≤ 200 图元），重画本来就只要几毫秒。缓存是给「整图都在视口内」的场景用的，大图恰好不是。

**DPR**：`canvas.width = cssW * devicePixelRatio` + `ctx.scale(dpr, dpr)`，`ResizeObserver` 上重建。
不处理的话 Retina 上文字发虚——这类细节不做，图一眼就是"业余"。

**主题**：Canvas 拿不到 CSS 继承。启动时用 `getComputedStyle(document.documentElement)` 读一次
主题变量缓存成对象，store 里 `theme` 变化时重读并置 `dirty`。**格式色必须与目录页的格式徽标同源**
（同一组 CSS 变量），不在渲染器里另写一套色值——两处色不一样，用户会以为是两种东西。

### 9.4 表很多：先少画，再谈画得快

「上下游表很多」的正确解法不是把字号调小，是**控制画多少个**。五个手段按介入顺序：

**(1) 默认 1 跳 + 按需展开**

首次请求 `depth=1`。节点朝外的方向若带 `more` 标志（§8.6），画一个 `›` 徽标；点它只拉**这个节点**的
1 跳（`GET /lineage/tables?ref=X&depth=1`），增量合并进现有图。

这样首屏规模永远是「root 的邻居数」，而不是「某张 DWS 表的 2 跳子图」（常见 200–800 个）。

增量合并两条规矩：新节点插入对应 rank 的**末尾**；**已放置节点坐标锁定**（不重排）。代价是多次
展开后布局不如全量重算紧凑（会出现"绕一下"的边）——接受，**空间稳定性比紧凑度重要**，因为用户
已经记住位置了。

**(2) 层内折叠（阈值 8）**

单层真实节点 > 8 时，按 `runCount` 降序取前 8 个，其余折叠成一个 `+42` 占位节点；点它原地展开
该层全部（此后该层不再折叠）。

阈值 8 不是拍的，是从「典型最坏情况」倒推的：一张 DWS 表上下游各 5 跳、每层 50 个节点 → 展开是
11 层 × 50 = 550 个，纵向约 `50 × 60px = 3000px`，而一屏约 600px，**一次只能看 1/5**；折叠到 8
之后是 11 层 × 8 = 88 个，纵向约 480px，**正好一屏**。

**(3) 先自动 fit 一次**

布局完若图高 > 视口 × 1.5，自动把 `k` 设为 `视口高 / 图高`（下限 0.25，到底了就只保证横向滚动）。
给的是**全貌**，而不是让用户手工缩——大多数人不缩，看到半张图就关掉了。用户一旦手动缩放就置
`autoFit = false`，直到点「适应窗口」。

**(4) 过滤（纯前端）**

| 过滤项 | 默认 | 作用 |
| --- | --- | --- |
| 引擎 | 全选 | 只看 Spark 写的链路 |
| 节点类型 | 全选 | 只看目录里点得开的表，隐藏 `~file:` / `~temp:` / `~external:` |
| 最近写过 | 不限 | > 30 天没更新的边**淡化**（不隐藏） |
| 有列级映射 | 关 | 只留 `columnCount > 0` 的边——**这条最有用**，它滤掉的全是「跑过但没记到列」的弱关系 |

过滤在前端做（数据已在内存），**不重新请求**：过滤是探索动作，一分钟要做几十次。

「淡化」而不是「隐藏」是个刻意的选择：隐藏会改变布局（节点消失、整图重排），淡化不改。这一条
在 9.5 的列级聚焦里同样适用。

**(5) 迷你图**：图尺寸 > 视口 2 倍时，右下角 160×120 缩略图（节点画 1px 点 + 一个视口矩形），
点击/拖拽定位。缩略图 `throttle` 到 200ms 一帧——它不需要跟手。

### 9.5 字段很多：聚合 + 聚焦 + 虚拟列表

一张 300 列的表，上下游各 300 列，笛卡尔展开是 **90000 条列边**。这不是"渲染慢"，是**语义上没有人
能看**。所以列级不做「全展开的图」，做三层：

**(1) 表层永远不画列边**

表与表之间一条边，线宽 ∝ `log10(columnCount + 1)`（1 列 → 1px、10 → 2px、100 → 3.5px、1000 → 5px）。
任何规模下边数都是 O(表数)，与列数无关。

**(2) 列链路只画「聚焦的那一列」**

点表节点 → 右侧抽屉列出该表的列；选中一列 → 只画它自己的上游链路（通常 3–10 个节点、5–20 条列边），
**其余边淡化到 8% 不透明度**（不隐藏，理由同 9.4）。

列边的端点不落在节点中点，而是按列在该表中的**相对行号**分布纵向偏移（节点高 44px，最少留 3 行
间距）。否则 20 条列边全挤在节点中心，看起来像一束。

**(3) 字段列表才是主视图，且「直通列」默认折叠**

| 分组 | 默认 | 为什么 |
| --- | --- | --- |
| 变换列（`DERIVED` / `AGGREGATE`） | 展开 | 人真正要看的：这列是谁算出来的 |
| 直通列（`DIRECT` 且同名） | **折叠成一行**：`38 个同名列直通` | 数量最多（宽表里常占 70%）、信息量最低，但恰好是它把列表撑到 300 行 |
| 常量列（`CONSTANT`） | 折叠成一行 | 分区列通常走这里 |
| 未解析（`UNRESOLVED`） | 展开并标灰 | 它代表「我不知道」，必须显眼，否则会被误读成「没有上游」 |

列表用 `el-table-v2`（Element Plus 自带虚拟滚动，**不引新依赖**）。列定义：列名 / 类型 / `kind` 徽标 /
上游（最多 2 个 `表.列` + `+3`）/ 最近一次运行。

- 搜索框本地模糊匹配（300 行不需要服务端）；
- 排序：列序（默认，与表结构一致）/ 按 `kind` / 按上游表数；
- **行内展开不做**，明细走抽屉。`el-table-v2` 的虚拟滚动建立在定高假设上，行内展开会破坏它——
  展开三行就把后续所有行的位置算错。

**(4) 列边捆绑（P1）**

聚焦列的多个上游列来自同一张表时（`JOIN` 之后的常见形态），把共线段合成一条「主干」，到源节点前
40px 再扇出成短分支。绘制从 5 条全长曲线变成 1 条 + 5 段短线，视觉上「它们来自同一张表」也一眼
可见。

### 9.6 交互、URL 状态与可访问性

| 动作 | 结果 |
| --- | --- |
| 单击节点 | 右侧抽屉：表摘要 + 「打开表详情」「以它为根」。**不直接跳页**——图上点一下就离开是惩罚 |
| 双击节点 | 以它为根重查，面包屑 `… › orders › order_daily` 可回退 |
| hover 节点 | 高亮它的直接边与邻居，其余淡化到 12% |
| hover 边 | tooltip：`engine` / `writeMode` / `runCount` / `lastSeen` / `columnCount` |
| 滚轮 | 以光标为锚点缩放，`k ∈ [0.25, 2]` |
| 拖拽空白 / 中键拖 | 平移 |
| 搜索框 | 按表名在**当前图里**定位并居中（不上服务端） |

**URL 状态**：`?tab=lineage&lref=<ref>&ldir=both&ldepth=1&lfocus=<ref>&lview=graph`。
可分享、可后退；`lfocus ≠ lref` 表示「钻进某个上游表」的状态，回退一步回 root。写状态只在
`replaceState` 里做——别把每一次 pan 都塞进历史栈。

**可访问性**（Canvas 对屏幕阅读器是空白，三条兜底缺一不可）：

1. **「表格视图」不是降级，是无障碍路径**：同一份数据、真实 DOM、可 Tab、可复制；
2. canvas 上加 `role="img"` + `aria-label="血缘图：上游 12 张表，下游 3 张表"`，旁边挂一个
   `sr-only` 的邻接列表；
3. 键盘：`←/→` 换焦点节点、`Enter` 打开抽屉、`Esc` 关抽屉。

**空态与错误态**一律用 `ResourceState`（`el-empty`），且**三种文案必须分开**：

| 情况 | 文案 | 附带的动作 |
| --- | --- | --- |
| 没采集到 | 「还没有血缘。执行一条 `INSERT` 后回到这里。」 | 无（不要说"无数据"——用户不知道数据从哪来） |
| 被过滤空了 | 「本图有 12 张表，当前过滤条件隐藏了全部」 | 「清除过滤」 |
| 加载失败 | 错误信息原文 | 「重试」 |

`ResourceState` 命中空态时只渲染 `el-empty`、**默认插槽整个不渲染**，所以过滤条与视图切换按钮必须
写在它外面（`docs/console.md` §10，`verify-console.py` 第 17 组钉着这条）——图页同样适用。

### 9.7 不引入图库（以及什么时候该反悔）

| 方案 | 体积（gzip，估） | 层次布局 | 定制成本 | 结论 |
| --- | --- | --- | --- | --- |
| `@antv/g6` | ~180KB | 有 | 中（主题、交互都要覆盖一遍） | 现在不用 |
| `cytoscape` + `dagre` 插件 | ~120KB + 60KB | 插件 | 中 | 现在不用 |
| `dagre` / `d3-dag`（只借布局） | ~30–60KB | 自带 | 低（只换 9.2 那一层） | P1 若布局不满意再换，接口留好 |
| 手写（9.2 + 9.3） | 0 | ~150 行 | 一次性 | **P0 选它** |

理由：本图只需要「固定 rank 的分层 + 三次贝塞尔 + 拖拽缩放 + 折叠 + LOD」。图库 90% 的能力
（多布局、子图编辑、自动避障路由、动画编排）用不上，而体积与主题适配成本是实打实的——控制台现在
只有 4 个运行期依赖（`vue` / `vue-router` / `element-plus` / icons），加一个 180KB 的图库，等于把
「依赖少、升级无痛」这个特性稀释掉。

**该反悔的三个信号**（先写清楚，免得以后争）：

1. 需要「节点内富文本 / 多行表结构预览」——图库的节点模板系统比手写省太多；
2. 需要布局动画过渡（节点飞过去）——手写要做补间与打断，图库自带；
3. 独立血缘页要做「自由画布 + 手工拖布局」——那已经不是"看血缘"，是"编辑图"。

### 9.8 性能预算与实测方法

预算是目标值，不是实测值；测法一列是"怎么证明达标"：

| 指标 | 目标 | 怎么测 |
| --- | --- | --- |
| 首帧（300 节点 / 900 边，冷缓存） | ≤ 600ms | puppeteer 注入合成数据，`performance.now()` 到第一次 `frame()` 结束 |
| 拖拽帧耗时 | 平均 ≤ 12ms，p95 ≤ 24ms | rAF 里记时间戳差值，跑 3 秒取分位 |
| 缩放停手到清晰重绘 | ≤ 120ms | 同上，取 LOD 切换后的那一帧 |
| 聚焦一列并上色 | ≤ 50ms（无网络） | 本地数据，纯前端计算 |
| JS 堆（2000 节点 / 6000 边） | ≤ 150MB | CDP `Memory.getHeapUsage` |
| 响应载荷（300 节点 / 900 边） | ≤ 40KB gzip | `Content-Length`；非 `compact` 也应在 60KB 内 |

**跑两张图**：100 节点看**固定开销**（组件挂载、主题读取、字体就绪），1000 节点看**斜率**（单节点成本、
裁剪收益）。只跑一张，永远分不清慢在初始化还是慢在循环。

合成数据生成器 `lakehouse-rest-console/tools/lineage-bench/gen.mjs`（与既有 `tools/console-login-check`
同构）：给定「层数 × 每层节点数 × 每节点边数」生成 `nodes` / `edges`，用 `?fixture=` 让控制台直接吃。
**这条入口必须包在 `import.meta.env.DEV` 里**，生产构建里不能存在——否则一个 URL 参数就能让任何人
把控制台拖死。

**不进 CI。** 这些数字对机器敏感（CI 容器普遍慢 2–4 倍），一进 CI 就变成"经常红、没人看"。改为改渲染层
时手动跑一次、把结果写进 PR 描述。这与本仓既有做法一致：`verify-console.py` 钉的是「有没有」，不钉
「多快」。

### 9.9 分阶段

| 阶段 | 内容 | 为什么这么切 |
| --- | --- | --- |
| P0 | 表级图（1 跳、无折叠、LOD 只做前两档）+ 列映射表（虚拟滚动）+ 三视图切换 | 1 跳图就能回答 80% 的问题；折叠与第三档 LOD 只在 > 100 节点时才有意义，而 P0 阶段大多数图是 3–20 个节点 |
| P1 | 按需展开、层内折叠、第三档 LOD、迷你图、列边捆绑、`compact` + `ETag`、列级聚焦上色 | 折叠阈值 8 要拿真实数据校准——先上线再调参，别在文档里空想 |
| P2 | 独立血缘页（全局搜索 + 多根对比）、时间轴回放（用 `lineage_run` 重建历史图）、导出 PNG/SVG、影响面传播高亮 | 都要求「图已经稳定」作为前提 |

### 9.10 明确不做

1. **力导向布局**：结果不确定，与「空间记忆」直接冲突（9.2）。
2. **图上的手工增删边**：手工维护的血缘会与真实执行漂移，而且没有人会维护（也列进 §12 的不做清单）。
3. **WebGL / 3D**：< 5000 个 2D 图元的量级用不上 GPU 管线，换来的是着色器与文字纹理的复杂度。
4. **轮询 / SSE 实时刷新**：血缘是慢变量（一次 `INSERT` 一行），进页面拉一次 + 手动刷新足够。
5. **自动「关键路径」高亮**：先得定义"关键"，而"关键"依赖业务权重，服务端没有这个信息——编一个
   默认权重等于给出错误暗示。

---

## 10. 配置

### 10.1 服务端（`lakehouse.rest.lineage.*`，进 `RestServerProperties`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关。默认关：没有客户端埋点就没有数据，且写入量要先评估 |
| `run-retention-days` | `90` | `lineage_run` 保留天数 |
| `max-runs-per-batch` | `50` | 单次上报最多几条 run，超了 400 |
| `max-columns-per-target` | `2000` | 单目标列级边上限，超了降级为表级并标 `truncated` |
| `max-depth` | `5` | 图遍历深度上限 |
| `max-nodes` | `500` | 单次图查询返回节点上限 |

默认关的另一个原因：血缘表一旦开始写，就**只能靠时间清理**（边表不按时间清），
先在测试环境把量跑准再开。

### 10.2 Spark 侧（`spark.paimon.rest.lineage.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 关时**不注册监听器**（零开销） |
| `column-level` | `true` | 关掉只上报表级（宽表场景省带宽） |
| `max-columns` | `2000` | 超过则本地降级为表级 |
| `queue-size` | `500` | 内存队列长度，满了丢弃 |
| `flush-interval-ms` | `2000` | 攒批间隔 |
| `url` / `token` | 空 | 留空则复用 `spark.paimon.rest.management.url` / `.token` |

安装方式沿用现有扩展：`spark.sql.extensions` 已经指向 `ManagementSparkExtensions`，
**不新增配置项**——监听器由该扩展在第一次 `injectParser` 时懒注册（`apply(extensions)` 拿不到
session，但 `injectParser` 的工厂函数有 session）。

### 10.3 Flink 侧

Flink 没有 `spark.sql.extensions` 那种全局钩子，只能显式包一层：

```java
// 与 LakehouseRestFlinkCatalogs 同一层：只拼参数，不碰 Flink 类型
Map<String, String> opts = LakehouseRestFlinkCatalogs.paimonOptions(uri, prefix, token);
LineageReporter reporter = LakehouseRestFlinkLineage.reporter(managementUrl, token);
reporter.recordBefore(env, sql);   // 解析 → 抽血缘（执行前）
env.executeSql(sql);               // 照常执行
reporter.recordAfter(runId, success);  // 只改状态，不发第二份血缘
```

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `LAKEHOUSE_LINEAGE_ENABLED` | `false` | 关时 `recordBefore` 直接返回 |
| `LAKEHOUSE_LINEAGE_URL` | 空 | 留空时从 catalog 的 REST 基址推导（`/v1` → `/api/management/v1`） |
| `LAKEHOUSE_LINEAGE_TOKEN` | 空 | 空则上报必然 401，日志提示一次后不再重试 |

「执行前抽、执行后改状态」是刻意的：Flink 的 `INSERT` 提交后作业就异步跑了（尤其流式），
`executeSql` 返回时数据还没写完。血缘依赖的是**计划**，执行前就确定了；状态字段后补。

---

## 11. 测试与验收

### 11.1 抽取器必须是纯函数

```
LineageExtractor.extract(plan: LogicalPlan, sqlText: String): LineageRun    // Spark
LineageExtractor.extract(op: Operation, sqlText: String): LineageRun        // Flink
```

输入计划 / 操作树，输出归一化模型，**不做 IO、不查 catalog、不认识 HTTP**。
这是整个测试策略的地基：纯函数可以喂手工构造的计划树，不需要集群、不需要服务端。

### 11.2 Spark 单测（不起集群）

两层：

1. **分类逻辑**：手工构造 `Project` / `Aggregate` / `Union` 子树，断言 `kind` 与 `sources`。
   覆盖 `DIRECT` / `DERIVED` / `CONSTANT` / `UNRESOLVED` 四个分支。
2. **解析逻辑**：`local[1]` 的 `SparkSession` + 本地 warehouse（`spark.sql.warehouse.dir`
   指向 `target/`），`CREATE TABLE ... USING parquet` 建两张表 → `spark.sql("INSERT ...")`
   → 从队列里取 run，断言边。**这条会真的写文件**，但几十秒可接受。

`byName`、静态分区、`UNION` 三列对齐各一条用例——这三处是最容易写反的。

### 11.3 Flink 单测（不连服务端、不跑作业）

```sql
CREATE TEMPORARY TABLE src (id INT, amount DECIMAL(10,2), dt STRING) WITH ('connector'='datagen');
CREATE TEMPORARY TABLE snk (id INT, total DECIMAL(10,2), dt STRING) WITH ('connector'='blackhole');
-- 只 parse，不 execute
INSERT INTO snk SELECT id, SUM(amount), dt FROM src GROUP BY id, dt
```

`Parser#parse` 会把临时表解析成 `SourceQueryOperation` / `SinkModifyOperation`，
**`blackhole` 连接器保证即使误执行也不落数据**。两个版本模块跑同一组用例（§11.1 的纯函数
签名相同），覆盖 `CalculatedQueryOperation` 在 2.3 消失这类差异——1.20 多一条、2.3 走兜底分支。

### 11.4 e2e

`scripts/e2e-lineage.sh`（骨架照抄 `e2e-flink-sql.sh`）：

```
起 h2（16082，开 lineage）→ 建 PAIMON / ICEBERG 各一个 catalog
  ├── Spark：建表 + 一条 INSERT ... SELECT（走 spark-sql，带 extensions）
  ├── Flink 1.20：建表 + 一条 INSERT
  ├── Flink 2.3：同上
  └── 断言：GET /lineage/tables?ref=... 三类边都在；GET /lineage/columns 的 kind 正确
```

无 `-De2e.management.url` 时整体 skip（与现有两个 e2e 脚本同规矩）。

### 11.5 文档与契约一致性

本仓的做法是「文档里的数字与清单由脚本钉住」，这份文档同样要：

- `scripts/verify-lineage-doc.py`：比对本文档 §6.1 / §8 的端点表与
  `LineageController` 上的 `@*Mapping`（漏写一个端点 → 构建红）；
- 表数：落地后 `data-model.md` 的「20 张表」→ 23，`README.md` §1 同步；
- 文档计数：`docs/` 21 → 22 份，`AGENTS.md` §3 表与 `README.md` §9 表各加一行。

### 11.6 展现层

三组纯函数单测（不需要 DOM）：

1. **布局确定性**：同一份 `(nodes, edges, root)` 跑两次，坐标数组逐项相等；打乱输入顺序后坐标
   集合相同——排序必须用 `ref` 做 tie-break，不能依赖数组顺序；
2. **折叠**：单层 37 个节点 → 8 个真实节点 + 1 个 `+29` 占位；展开后 37 个，且**已锁定节点坐标
   一位不动**（9.4 的增量合并规矩）；
3. **LOD 滞回**：k 从 1.0 降到 0.87 仍属高档、降到 0.85 落中档、再升回 0.88 仍属中档——不能抖。

**渲染本身不进单测**：Canvas 要 jsdom + node-canvas，成本远超收益。像素级问题（发虚、错位、色差）
靠 `tools/lineage-bench` 手测 + 一张截图人眼过；`verify-console.py` 只断言「血缘页签存在」「能切到
表格视图」这类有没有，不碰像素。

---

## 12. 风险与已知边界

| # | 风险 | 应对 |
| --- | --- | --- |
| 1 | Spark 4.x 后续把 `InsertIntoStatement` 换掉 | 匹配处集中在版本模块的一处 `match`；用同一组「SQL 文本 → 期望边」用例两版本都跑，换节点时用例会红 |
| 2 | `QueryExecutionListener` 也收到 DataFrame 写入（`saveAsTable` / `insertInto`） | 接受。这类 run 的 `sqlText` 为空、`statementType=INSERT`，表级与列级仍然准确（analyzed 计划一样有） |
| 3 | 视图口径：Spark 展开、Flink 可能不展开 | §3.6 已声明；P2 补 `AS_WRITTEN` 边。**这是本期最大的语义缺口**，控制台需在血缘页脚注一句「视图已展开为底层表」 |
| 4 | 宽表 / 深嵌套 join 的列边爆炸（1000 列 × 5 表） | `max-columns` 本地降级 + 服务端 `max-columns-per-target` 二次兜底，标 `truncated` |
| 5 | 上报影响作业尾延迟 | 异步队列 + 攒批 + 队列满丢弃（§6.4） |
| 6 | 伪造血缘（声称写了不属于自己的表） | 判权（§6.5）+ `principal` 由服务端解析 + 图上的边带 `engine` 与 `lastSeen` 供追溯；**不做签名**，这是内网元数据服务的合理边界 |
| 7 | Flink `STATEMENT SET` 一次提交多 sink | 模型层面 run 1:N `targets`，不是「一次提交只能一个目标」 |
| 8 | 流式作业：`INSERT` 提交 ≠ 数据写完 | `mode=STREAMING` 标记；控制台**不显示「最后写入时间」**，只显示「最后提交时间」，避免把长跑作业读成「一直在写」 |
| 9 | `TableEnvironmentInternal` / `getTargetColumns()` 是 `@Internal` | 版本固定（1.20.1 / 2.3.0）+ 两版本各一份遍历器；升级 Flink 时这两个是最先要复核的 |
| 10 | `MERGE INTO` 的 `UPDATE` / `INSERT` 分支不拆分 | 只记「目标表依赖源表」+ 列映射取 `UPDATE SET` 与 `INSERT` 列的并集，不表达「哪行走了哪个分支」 |
| 11 | 边表不按时间清 → 图会累积历史脏边（表删了、任务下线了） | 表删除时级联删边；「任务下线」无从得知 → 用 `last_seen` 在控制台标注「半年未更新」，不做自动清理 |
| 12 | 单图节点过多把浏览器拖死（一张 DWS 表 2 跳 800 个节点是常态） | 服务端 `maxNodes`（默认 300 / 上限 2000）+ 前端 500 阈值自动切表格 + 默认 1 跳按需展开（§8.6、§9.1、§9.4） |
| 13 | 宽表列边爆炸（300 列 × 300 列 = 90000 条） | 表层只画一条聚合边；列链路只在「聚焦一列」时画；`DIRECT` 直通列折叠（§9.5）。与 #4 的 `max-columns` 是两道独立的闸——前者管「画多少」，后者管「存多少」 |

**不做的事**（写清楚免得被反复问）：

1. **不做行级 / 文件级血缘**：那是执行期数据面的事（读 manifest、比 row id），与「SQL 声明了什么」是两个问题。
2. **不从查询日志反推**：日志里有 SQL 但没有解析后的计划，反推等于 §1.2。
3. **不做血缘的定时重算**：边是「最近一次执行」的事实，重算没有输入（历史 SQL 已经丢了）。
4. **不做跨集群血缘**：一个服务端实例的图只含上报到它的那些执行；多云 / 多集群要合并是另一个课题。
5. **不做图上的手工增删边**：手工维护的血缘会与真实执行漂移，而没有人会维护它（§9.10）。
