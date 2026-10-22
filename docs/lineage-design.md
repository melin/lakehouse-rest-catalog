# Spark SQL 血缘设计：从「执行过的 INSERT」里抽出表级与列级依赖

> 状态：**服务端与控制台已实现**（三张表、五个端点、表详情页「血缘」页签；落地清单与
> 与本文不一致的几处见 §13）。**§5 的 Spark 侧抽取器还没写**——它属于客户端埋点
> （`spark.lakehouse.rest.lineage.*`，见 §10.2），放在引擎仓库而不是本仓；因此现在
> 「上报方」这一环是空的，接收、归一、存储、查询与展现都已跑通并有测试。
> 参考实现：[`apache/kyuubi` 的 `kyuubi-spark-lineage`](https://github.com/apache/kyuubi/tree/master/extensions/spark/kyuubi-spark-lineage)
> ——§3 把它逐节点拆了一遍（算法骨架、16 行节点规则、三个占位属性、表名归一化），
> §5.8 列出「抄了什么、改了什么、为什么改」。
> 一句话结论：**血缘必须在引擎侧抽，服务端只做接收、归一、存储与查询**——因为只有引擎手里
> 有「已解析、已展开、真正执行了」的那棵计划树；服务端重解析一遍只能得到近似值，而近似值
> 在血缘这种「拿去做影响面判断」的元数据上等于错误值。
>
> 覆盖范围：**只做 Spark**（3.5.9 / 4.1.2）的 `INSERT` 家族语句；服务端的归一化、存储与查询；
> 控制台的图与列映射展现（§9，含渲染性能、上下游很多、字段很多三种场景；§9.11–9.14 是前端
> 落地视角：组件清单与依赖、表详情页集成、数据流，配套效果图
> [`docs/lineage-mockup.html`](lineage-mockup.html)——表详情页新增「血缘」页签的五个状态）。
> **Flink 本期不做**：理由与「将来接进来要不要改模型」见 §10.3（结论：不用改）。
> 与已有文档的关系：`event-framework.md`（**不塞进 `audit_event`**，理由见 §7.6）、
> `data-model.md`（表数 20 → 23，三张表见其 §2.3）
> 见 §2.4）、`console-table-detail.md`（页签伸缩规则）。

---

## 0. 能力边界（先看这张表）

| 问题 | 本期 | 说明 |
| --- | --- | --- |
| 支持哪些语句 | `INSERT INTO` / `INSERT OVERWRITE` / `INSERT ... BY NAME` / `CTAS` / `REPLACE TABLE AS` / `MERGE INTO`（只取写目标） | 统一叫「INSERT 家族」，判定依据是**计划节点类型**而不是 SQL 前缀 |
| 粒度 | 表级 + **列级**（含表达式文本） | 列级是本期的主要价值，也是唯一必须依赖引擎的理由 |
| 展现 | 表详情页新增「血缘」页签：表级 DAG（Canvas，≤ 500 节点）+ 列映射虚拟列表；超预算自动降级为表格 | 图只画结构、列级走列表；布局是 `(图, root)` 的纯函数，位置稳定（§9；组件与依赖见 §9.11，效果图 `docs/lineage-mockup.html`） |
| 采集时机 | **执行成功后** | 失败的 INSERT 不进图：它没有让任何数据依赖成立。`onFailure` 只打一条 WARN，不上报（§5.1） |
| 两种表格式 | Paimon / Iceberg 一视同仁 | 血缘的键是「目录里那个名字」，与底层格式无关 |
| 非目录对象 | 临时视图、文件路径、`VALUES`、外部连接器表 → `EXTERNAL` 节点 | 用 `~` 前缀与真表名隔离（§4.1） |
| 引擎数 | **只有 Spark** | Flink 的采集点在 `Parser`（执行前），与 Spark 的 `QueryExecutionListener`（执行后）不是同一套 API，硬凑一份抽象只会得到一个空壳（§10.3） |
| P2（二期） | 服务端 best-effort 解析兜底（只表级，`confidence=LOW`）、`AS_WRITTEN` 口径、下游影响面传播 | 见 §1.3、§4.6、§12 |
| 不做 | 行级 / 文件级血缘、执行期数据面采样、自动从查询日志反推、血缘图的定时重算、图上的手工增删边 | 见 §12 |

三个数字先摆出来，免得后面反复解释：

- **一次 INSERT = 一行 `lineage_run`**（历史事件）+ **N 行表级边** + **M 行列级边**（当前状态，upsert）。
- 边表是**状态**不是日志：同一对 `(源, 目标)` 只存在一行，重复执行只更新 `run_count` / `last_seen`。
- 上报是**异步且可丢**的：队列满就丢，绝不因为上报失败让作业失败（§6.4、§6.6）。

---

## 1. 为什么采集点在引擎侧

### 1.1 一次 INSERT 在 Spark 里是什么

| 项 | Spark（我们与参考实现取的是同一个点） |
| --- | --- |
| 采集点 | `QueryExecutionListener#onSuccess(funcName, qe, durationNs)` |
| 计划形态 | 已分析逻辑计划（`qe.analyzed()`） |
| 目标表 | `InsertIntoStatement#table` 等节点里的 `DataSourceV2Relation` / `HiveTableRelation` |
| 目标列清单 | `userSpecifiedCols()` / `byName()` / `partitionSpec()` |
| 列映射的原料 | `AttributeReference.exprId` 贯穿整棵树 |
| SQL 文本 | **拿不到**（`funcName` 是动作名，不是 SQL），要从 §2.4 的解析器扩展顺手留一份 |

这个原料已经是「名字解析完、列对齐完、类型转换完」的形态。这就是本期方案的立足点：
**我们不解析 SQL，我们读引擎解析完的结果**。

顺带说明为什么这件事看起来「抄一下就行」而实际上不是：参考实现整整 600 行只干这一件事，
其中 400 行是**逐节点的特例**（`Union` 的 exprId 新分配、`LeftSemi` 的右表、`count(*)` 的空引用、
`Expand` 的转置、缓存表要反查 `logicalLink`……）。§3 会一个个点名。

### 1.2 服务端重解析会错在哪

不是「精度差一点」，而是**错**，且有五个具体的错法：

| 反例 | 服务端纯文本解析会得到什么 | 真实执行的是什么 |
| --- | --- | --- |
| `INSERT INTO t SELECT * FROM s`，其间 `s` 加过一列 | 按服务端库里 `s` 的**当前** schema 对齐 → 列序与执行时刻不一致 | 按执行时刻的 schema 展开 |
| `INSERT INTO t PARTITION (dt) SELECT ...` | `dt` 是动态分区，它的值来自查询的最后一列——纯文本看不出是哪一列 | `dt` 按普通列解析，映射到 query 的对应输出 |
| `INSERT INTO t SELECT * FROM v`（v 是视图） | 要么展开视图（＝自己实现一个 analyzer），要么把 v 当源表 | 视图按定义时刻的 schema 展开成底层表 |
| `SELECT a FROM x UNION SELECT b FROM y` | 按位置猜对齐；隐式类型转换不知道 | 引擎按位置对齐并插入 cast，列映射可能经过 `CAST` |
| `WITH cte AS (SELECT ...) INSERT INTO t SELECT ... FROM cte` | 要自己内联 CTE（含嵌套、递归、名字遮蔽） | analyzer 展开；**而 `analyzed` 里它还是 `WithCTE` 节点**，得再跑一次 optimizer（§2.2） |

最后一行值得单说：它连「引擎给的计划树」都不够用——`analyzed` 只是「名字解析完」，
CTE 内联发生在 optimize 阶段。参考实现因此在 `WithCTE` 分支里显式调了
`sessionState.optimizer.execute(p)`。**这就是「血缘抽取没法靠猜」的最好证据**。

血缘的下游用法是「这张表变了，谁受影响」。一个错了列序的映射会把影响面指到无关的列上，
比没有血缘更糟——**没有血缘时人会去查，有错误血缘时人不会查**。

### 1.3 那服务端还解析吗

留一条**降级路径**，但不作为主路径：

- 触发条件：`POST /lineage/runs` 里没有列级信息、只有 `sqlText`（例如调度平台的 SQL 日志接入）。
- 能力上限：**只出表级边**，`confidence=LOW`，`source=SQL_TEXT`，图上一律显示为虚线。
- 技术选型：JSqlParser（只认 `INSERT INTO ... SELECT` / `CTAS` 的常见形状）。**不引入 Calcite**——
  服务端目前完全不带引擎依赖，为了兜底解析把一整套 SQL 方言引擎搬进元数据服务，
  换来的是「表级、低置信、只覆盖 90% 形状」，不划算。
- 优先级：P2。**只有在前端埋点不可行时**（第三方调度器只留了 SQL 日志）才做。

### 1.4 为什么不是「SQL 文本 + 目录元数据自己算」

这是第 1.2 节的同义反复，但值得单独说一句：这会得到一个**看起来能用**的实现——
90% 的 ETL 是 `INSERT INTO ... SELECT ... FROM ... WHERE`，手写解析器两天就能覆盖。
剩下 10% 的错会集中在最复杂的那些 SQL 上（多级 CTE、窗口、`UNION`、视图、动态分区），
而那些恰恰是最需要血缘的场景。这条路的失败方式是「静默地错」，因此排除。

---

## 2. Spark 侧的原料（事实核对）

### 2.1 `QueryExecutionListener`

```scala
// Spark 3.5.9 与 4.1.2 的签名一致（javap 核对过）
public interface QueryExecutionListener {
  void onSuccess(String funcName, QueryExecution qe, long durationNs);
  void onFailure(String funcName, QueryExecution qe, Exception exception);
}
```

两种注册方式，效果相同：

| 方式 | 写法 | 适用 |
| --- | --- | --- |
| 配置项 | `spark.sql.queryExecutionListeners=全限定类名`（参考实现走这条，零代码接入） | 用户自己起 `spark-sql` / `spark-submit` |
| API | `sparkSession.listenerManager.register(listener)`（`ExecutionListenerManager`） | 我们能拿到 session 的地方（扩展里懒注册，§10.2） |

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

参考实现还认了 `InsertIntoHiveTable` / `InsertIntoDataSourceCommand` / `SaveIntoDataSourceCommand` /
`ReplaceData` / `WriteDelta`——都是 Hive 或 Delta 的路径。**我们一个都不认**：这个仓只有
Paimon 与 Iceberg（都走 V2 `AppendData` 家族），认了反而会为不存在的场景维护分支。

### 2.2 为什么是 `analyzed`，不是 `optimized` / `withCachedData`

`analyzed` 是「名字解析完、类型对齐完、`*` 展开完」的最小完备形态；再往前一步就丢信息：

| 计划 | 多了什么 | 丢了什么 | 结论 |
| --- | --- | --- | --- |
| `analyzed()` | — | CTE 还没内联（`WithCTE` 节点还在） | **采用**，CTE 处补一次 optimizer |
| `optimized()` / `withCachedData()` | CTE 内联、常量折叠、谓词下推、列裁剪 | `SubqueryAlias` 被折叠、未用到的列整个消失、`Union` 的两侧可能被裁成不同长度 | 列裁剪会**删掉真实存在的依赖**：`INSERT INTO t SELECT a FROM s` 里 `s.b` 没被读，但 SQL 语义上 `s` 的 schema 变了不该影响 `t`——这条其实「裁掉更正确」，问题是它让「源表集合」随优化器版本漂移 |

一句话：**`analyzed` 保证「SQL 写了什么」都还在，`optimized` 保证的是「执行时真读了什么」**。
血缘要做的是前者（影响面判断），口径见 §2.2 与 §4.6。

但 `analyzed` 有两个已知的坑，参考实现都撞过并绕开了，我们照抄：

1. **`WithCTE` 没内联**：`sessionState.optimizer.execute(plan)` 再递归。只对 CTE 节点这么做，
   不对整棵树预处理——否则就退化成 `optimized` 了。
2. **`CommandResult` 包了一层**：`spark.sql("INSERT ...")` 的根节点有时是 `CommandResult`，
   真正计划在私有字段 `commandLogicalPlan` 里，得反射取（`ReflectUtils`）。

另外 `InMemoryRelation`（缓存表）参考实现专门写了分支（从 `cacheBuilder.cachedPlan` 反查
`logicalLink` 拿到原始逻辑计划）。我们保留这个分支：**它在实测路径上会出现**，
但不假设它一定出现——出现就展开，不出现就走普通的 relation 分支。

### 2.3 我们与参考实现的采集点对照

| 维度 | 我们 | `kyuubi-spark-lineage` |
| --- | --- | --- |
| 钩子 | `QueryExecutionListener#onSuccess` | 同一个 |
| 计划 | `qe.analyzed()` | 同一个 |
| 触发开关 | `spark.sql.queryExecutionListeners` 或扩展里注册 | `spark.sql.queryExecutionListeners` |
| 抽取失败 | 计数 + WARN，不上报 | WARN，返回 `None`（静默丢一条） |
| 产物 | 归一化 `LineageRun` + 队列异步上报 | `Lineage` 交给 dispatcher（Spark 事件总线 / Kyuubi 事件总线 / Atlas） |
| 幂等 | `runId` + 边 upsert | 无（去重交给下游 Atlas） |

**结论：原料一样，差别全在产物**——参考实现把结果扔给 Atlas/Kyuubi 事件总线，谁爱怎么存怎么存；
我们要自己存，所以必须补 `run`、`kind`、表达式文本、幂等键这几样它不需要的东西（§5.8）。

### 2.4 为什么不在 Spark 的 `injectParser` 里做

本仓已经有 `ManagementSparkExtensions`（`docs/spark-sql-extension.md`），看起来顺手——
**但解析器拿到的只是文本**，此时名字未解析、`SELECT *` 未展开、视图未内联、类型未转换。
从文本到「哪一列来自哪一列」之间差着整个 analyzer。

那这个既有扩展在血缘里扮演什么角色？**只做一件事：把最近一次 `parsePlan` 的 SQL 文本记在旁路里**，
供随后触发的 `onSuccess` 取走（`DataFrame` 写入路径没有解析，`sqlText` 留空即可）。
一句话：解析器负责「记事」，监听器负责「算账」。

原稿说「留在 `ThreadLocal` 里，因为 `spark.sql(...)` 解析与执行同线程」——**这条不成立**：
`QueryExecutionListener` 的回调是被 `ExecutionListenerManager` 投递到
`spark-listener-group-shared` 线程上的。落地改成一张按**计划实例**索引的表
（`SqlTextRegistry`，`IdentityHashMap`），键就是 `spark.sql` 交给 `QueryExecution` 的那个对象
（§13.7 第 10 条）。

参考实现没有这个问题——它寄生在 Kyuubi 服务里，SQL 文本本来就在自己的 `statement` 上。
我们是独立部署的 REST catalog，只能靠这条旁路。

### 2.5 为什么不用物理计划或执行统计

- **物理计划**（`qe.executedPlan()`）：列信息已被折叠成表达式与分区裁剪，`Attribute` 的名字
  与来源表已经丢失（只剩 `Exchange` / `Scan` 的 `output`），反推列级血缘比逻辑计划难一个数量级。
- **执行统计 / metrics**（`numOutputRows` 之类）：回答的是「写了多少行」而不是「依赖谁」，
  属于 `metrics-design.md` 的范畴，不要混进来。

---

## 3. 参考实现拆解：`kyuubi-spark-lineage`

这一节是把源码读完之后的事实清单——**它做了什么、为什么非要那么做**。§5 才讲我们怎么改。

### 3.1 结构：一个监听器 + 一个递归函数 + 三个出口

```
SparkOperationLineageQueryExecutionListener        ~40 行  钩子，只做转发
  └── SparkSQLLineageParseHelper（trait LineageParser）  ~600 行  全部算法在这里
        └── Lineage                               ~50 行  产物（3 个字段）
  └── LineageDispatcher（SPARK_EVENT / KYUUBI_EVENT / ATLAS）
```

`onSuccess` 的全部内容就是两行（这不是简化，是原文）：

```scala
val lineage = SparkSQLLineageParseHelper(qe.sparkSession).transformToLineage(qe.id, qe.analyzed)
dispatchers.foreach(_.send(qe, lineage))
```

三个可以立刻借鉴的判断：

1. **抽取器是「拿 session 的纯函数」**：`transformToLineage(executionId, plan)` 不碰 IO、不查
   catalog、不认识 HTTP。所有版本差异都被压在 `plan` 的形状上，测试可以直接喂手工构造的计划树
   （这正是 §11.1 的测试策略来源）。
2. **失败只留一条日志**：`Try(parse).recover { case e => logWarning(...); throw e }.toOption`。
   上层拿到 `None` 就什么都不发。**解析失败绝不影响 SQL 结果**，这条优先级与我们 §6.6 一致。
3. **出口可插拔**：dispatcher 是 trait，配置项选实现。我们没有 Atlas、也没有 Kyuubi 事件总线，
   出口只有一个（HTTP 上报），所以不需要这层抽象——但「出口」这个概念保留（`LineageReporter`）。

### 3.2 产物太薄：只有三个字段

```scala
class Lineage(
  val inputTables: List[String],
  val outputTables: List[String],
  val columnLineage: List[ColumnLineage])          // ColumnLineage(column, originalColumns: Set[String])
```

- 表名与列名都是**已经拼好的字符串**（`catalog.database.table` / `catalog.database.table.column`）；
- **没有** `kind`（直通 / 派生 / 聚合 / 常量）、**没有**表达式文本、**没有** run、**没有**时间与执行者。

这不是疏漏，是场景不同：它把结果丢给 Atlas，Atlas 只想要「A.column → B.column」的边；
而我们要自己存、自己画图，图上要区分「直通」与「算出来的」、要能展开看表达式、要能回答
「最近一次是谁写的」。所以产物必须加厚（§5.7、§6.2），但**算法可以照抄**。

### 3.3 算法骨架：把「输出列」和「源列」用属性集连起来

唯一的递归函数是 `extractColumnsLineage(plan, parentColumnsLineage, inputTablesByPlan)`，
第二个参数是关键：**一张「当前层的输出属性 → 它引用的下层属性集合」的映射**，从上往下传。

```
extract(node, parent: Map[Attribute, Set[Attribute]], inputs: mutable.Set[String]):

  Project / Aggregate / Window / Expand / Generate:
      # 1. 从本层的表达式算出「本层输出属性 → 本层引用属性」
      child = getSelectColumnLineage(node.projectList)   # Alias 取 child.references；裸 Attribute 取自身
      # 2. 用 exprId 把 parent 里的「本层属性」替换成 child 里的「下层属性」
      next  = join(parent, child)
      # 3. 递归，各 child 结果求并
      return children.map(extract(_, next, inputs)).reduce(merge)

  Union:
      # output 的 exprId 是新分配的，exprId 查不到 → 只能按位置 zip
      perChild[i] = children.map(extract(_, 空, inputs)).map(_.values)   # 每个 child 用空 parent 走
      merged[i]   = perChild[i].reduce(_ ++ _)                          # 第 i 列合并各 child 的第 i 列
      return join(parent, node.output.zip(merged))                      # parent 不再下推

  叶子（LogicalRelation / HiveTableRelation / DataSourceV2ScanRelation / DataSourceV2Relation）:
      inputs += tableName
      if parent 非空:  return parent.map { (k, attrs) => k -> attrs.map(_.withQualifier(tableName)) }
      else:            return node.output.map { a => a -> {tableName.a} }   # 顶层直接落表
```

`join`（源码里的 `joinColumnsLineage`）是整个算法的枢纽，它只做一件事——**用 `exprId` 做替换**：

```
join(parent, child):
    parent 为空        → child
    child 为空         → 空（注：count(*) 会掉进这里，见 §3.5）
    否则              → parent.map { (k, attrs) =>
                            k -> attrs.flatMap(a => child.get(a.exprId).getOrElse(空))
                        }
```

为什么不是「从目标列出发、递归展开它的表达式」？那种写法（本文旧稿第 4 节就是这么写的）在四种情况下会错：

| 情况 | 「反查表达式」写法 | 「属性集下推」写法 |
| --- | --- | --- |
| `Union` | 目标列的表达式是 `Union.output(i)`，它不是任何 child 的列，展开不下去 | 按位置 zip，天然正确 |
| `count(*)` | 表达式 `Count(1)` 的 `references` 为空 → 判成 `CONSTANT`（错，它依赖行数） | 占位属性 `__count__` 保住这条边（§3.5） |
| 同一列被多层 `Alias` 包 | 每层都要解包，`Alias` 里套 `ScalarSubquery` 就断 | 集合逐层替换，层数无关 |
| `JOIN` 里只出现在 `ON`/`WHERE` 的表 | 从目标列反查根本走不到它 | 走查时每个叶子都 `inputs +=`，不漏 |

最后一条正是我们模型里「表级依赖 ≠ 列级来源」的算法来源（§4.2、§5.7）。

### 3.4 逐节点规则（源码里 34 个 `match` 分支，归并成 16 行）

| 节点 | 处理 | 为什么必须是特例 |
| --- | --- | --- |
| `Project` / `Aggregate` | `selectLineage` + `join` | 主体逻辑，唯一「正常」的分支 |
| `Expand`（`GROUPING SETS` / `ROLLUP`） | `projections.transpose.map(_.flatMap(_.references))` | 一个输出列对应**多个** projection 位置，要转置后求并 |
| `Generate`（`explode` / `LATERAL VIEW`） | 生成列 → `p.references` | 输出列不在 child 的 output 里 |
| `Window` | `windowExpressions` 单独一张表，父层引用被替换 | 窗口列由 `partitionBy`/`orderBy` 决定，与 `Project` 不同 |
| `Join`（`LeftSemi` / `LeftAnti`） | **先单独走一遍 right**（只为收集 `inputs`），再把 parent 传给 left | 右侧列不出现，但右表**是**输入表；直接按普通 Join 走会把右表丢掉一半 |
| `Union` | 按位置 zip 各 child 的列集合 | `output` 是新 `exprId`，`join` 查不到 |
| `LogicalRelation` / `HiveTableRelation` | `inputs += tableName`，再给 parent 的属性打 qualifier | 唯一的「落地」点 |
| `DataSourceV2ScanRelation` / `DataSourceV2Relation` | 同上，名字取 `catalog + namespace.mkString(".") + name` | V2 的 `identifier` 是多级 namespace，不能只取 `name` |
| `LocalRelation`（`VALUES` / `SELECT 1`） | 记成 `__local__` 伪表 | 否则这条血缘没有源，图上会画成「凭空产生」 |
| `OneRowRelation` | 只把 `__subquery__` qualifier 去掉 | `WHERE x IN (SELECT max(y) FROM ...)` 里常见 |
| `View` | 临时视图递归内联；永久视图由 `skip.parsing.permanent.view.enabled` 决定「当源表」或「展开」 | 两种口径都有人要，做成开关 |
| `InMemoryRelation`（cache） | 从 `cacheBuilder.cachedPlan` 找 `logicalLink`，拿到原始计划再递归 | 缓存表的 `output` 与底表 `exprId` 无关，不展开就断链 |
| `WithCTE` | `sessionState.optimizer.execute(p)` 后重来 | `analyzed` 里 CTE 没内联（§2.2） |
| `CommandResult` | 反射取 `commandLogicalPlan` 再递归 | 根节点被包了一层 |
| 写节点（`AppendData` / `CreateTableAsSelect` / `InsertIntoHiveTable` / …） | 取 `query`/`plan`，把输出列名前面加上目标表名 | 「目标」这一侧就是在这里确定的 |
| `MergeRows` / `MergeIntoTable` | 从 `matchedInstructions` / `notMatchedInstructions` 抽 assignment 的 `references`，合成一张 map；过滤元数据列 | `MERGE` 的目标列来自多个分支，`UPDATE SET` 与 `INSERT` 各有自己的源 |

### 3.5 三个占位属性：让「空的引用」不丢边

`join` 查不到 `exprId` 就把这条依赖丢掉——这是对的（大部分时候），但有三种情况会让本该存在的边
凭空消失，参考实现用三个占位名字兜住：

| 占位 | 场景 | 不加会怎样 |
| --- | --- | --- |
| `__count__` | `COUNT(*)` / `COUNT(1)`：`references` 为空 | 目标列被判成常量，`INSERT INTO t SELECT count(*) FROM s` 里 `s` 从列级边消失 |
| `__subquery__` | 标量子查询：子计划的属性不属于当前层 | 子查询里读的表连不到目标列（能连到表级，连不到列级） |
| `__local__` | `VALUES` / 常量 `SELECT` | 目标列没有任何源，看起来像「凭空算出来」 |

实现手法很朴素：需要时给属性换一个特殊的 qualifier/name，走到叶子时再改回来
（`attr.qualifier.init`、`attr.withQualifier(qualifier)`）。**这三个名字是保留字，不能与真实列名冲突**——
我们入库时要做一次断言（真实列名以 `__` 开头直接判 `UNRESOLVED`，见 §5.5）。

还有一类反向操作：`isMetadataAttr` 过滤掉 `__row_operation`、`__original_row_id_` 这类
**写入路径注入的元数据列**（`MERGE` / 行级更新会产生）。不过滤的话，图上会多出一堆用户没写过的列。

### 3.6 表名归一化：`catalog.namespace(点拼).table`

```scala
val catalog  = relation.catalog.map(_.name()).getOrElse(DEFAULT_CATALOG)   // spark.sql.defaultCatalog
val database = relation.identifier.get.namespace().mkString(".")           // 多级 namespace 用点拼
val table    = relation.identifier.get.name()
s"$catalog.$database.$table"
```

V1 表（`qualifiedName` 只有 `db.table`）走补全：`Seq(DEFAULT_CATALOG, db, table).filter(_.nonEmpty).mkString(".")`。

**这与我们的 `NamespacePaths` 口径完全一致**（多级 namespace 点号拼成一段路径），所以
`TableRef` 的字符串可以直接对齐，不需要中间转换层。这也是为什么 §4.1 敢说「`ref` 是点号拼接的路径
而不是正好三段」。

### 3.7 `inputTables` 的两种口径（默认已改成「按计划收集」）

配置 `spark.kyuubi.plugin.lineage.legacy.collectInputTablesByColumn`（默认 `false`）：

| 口径 | 来源 | 结果 |
| --- | --- | --- |
| 按列级边反推（legacy） | `columnLineage` 里每个源列名的前缀 | **会漏**只出现在 `JOIN` / `WHERE` 里的表 |
| 按计划收集（默认） | 走查时每个叶子 relation 都 `inputs +=` | 完整 |

这条改名本身就是一份设计证据：**用列级边的副产物当表级边，一定会漏**，
因为他们「默认」从 `false` 改过来（注释写着 `internal`，说明是修 bug 修的）。
我们直接把两件事分开：表级源从「走查时收集的 relation 集合」出，列级源从「列级边」出（§5.7）。

### 3.8 它的六个短板（我们不照抄的部分）

| # | 短板 | 后果 | 我们的处理 |
| --- | --- | --- | --- |
| 1 | 没有 `kind` 与表达式文本 | 图上分不出「直通」和「算出来的」，排障时看不到 `sum(amount)` | 加 `transform_kind` + `expression`（§5.5、§7.2） |
| 2 | 没有 run 概念 | 「最近一次谁写的、什么时候」无从回答 | 加 `lineage_run` + `last_run_id`（§7.1） |
| 3 | 列名是字符串拼接，无 schema 校验 | 列改名后旧边成孤儿，图上挂着一条指向不存在列的边 | 服务端 upsert 时按 `TableEntity` 的当前 schema 校验，不存在的列标 `STALE` 而不是删（§7.5） |
| 4 | 解析失败 `None` 静默丢弃 | 漏报无从发现 | 计数 + WARN + 可选上报「跳过原因」（§6.4） |
| 5 | 大量反射取私有字段（`getField[LogicalPlan](plan, "query")`） | 版本升级风险集中，且没有编译期保护 | 照用反射，但**集中在一个 `PlanFields` 对象里**，并用「同一组 SQL → 期望边」的用例在两版本上跑（§11.3） |
| 6 | 视图口径二选一（开关） | 切开关会让历史边与新边混在一起，图形状突变 | 本期只做 `RESOLVED`，`AS_WRITTEN` 作为 P2 的**并存**边（`origin` 区分，不是替换，§4.6） |

---

## 4. 归一化模型

引擎给的原料与我们要存的模型形状完全不同，**归一化必须在客户端完成**——服务端不认识
`AttributeReference`，也不该认识。归一化后的模型只有三个概念：**节点、表级边、列级边**。

### 4.1 节点：`TableRef`

```
{ref} = {catalog}.{namespace...}.{table}
```

| 规则 | 说明 |
| --- | --- |
| 三段式，namespace 可多级 | Paimon 的多级 namespace 用点号拼成一段（与 `NamespacePaths` 同口径），因此 `ref` 是「点号拼接的路径」而不是「正好三段」 |
| catalog 必填 | 没写 catalog 时取会话默认 catalog（`spark.sql.defaultCatalog`，与参考实现同一个取值）。**不允许省略**，否则同名表在两个 catalog 下会合并成一条边 |
| 大小写 | 原样保留。不做小写化——折叠规则属于会话配置（`spark.sql.caseSensitive`），统一折叠等于替引擎做决定 |
| 保留前缀 `~` | `~temp:<名字>`（临时视图 / 临时表）、`~file:<sha256 前 12 位>`（裸路径）、`~values`（纯常量源）、`~external:<标识>`（外部连接器表）。**`~` 是保留位**：服务端在建库 / 建表时拒绝 `~` 开头的名字（一行校验），保证不会与真表名冲突 |

为什么不是 `TABLE` / `VIEW` 两种节点，而只有 `TABLE` / `EXTERNAL`：

- `analyzed` 计划里视图已经展开成底层表，**根本见不到视图节点**；
- 参考实现里永久视图可以选择「当源表」不展开（一个开关），但它仍然是同一种节点类型，
  只是出现在血缘的哪一侧不同。

统一口径：**节点只有「目录里点得开的东西」与「点不开的东西」两类**。视图算前者（它有名字、
有定义、能点开），只是它出现在血缘里的哪一侧取决于我们怎么配（§4.6）。

### 4.2 表级边

一条边表达「**目标表的数据依赖源表**」，方向固定为 `source → target`：

```
(sources[]) ──WRITE_FROM──▶ targets[0]
                              └── targets[1]   （`FROM s INSERT INTO t1 … INSERT INTO t2 …` 多路插入时）
```

- 存两列：`source_ref` / `target_ref`，查询时按 `direction` 决定用哪一列做过滤。
- 边带 `kind`：`TABLE`（直接读表）/ `EXTERNAL`（读的是外部节点）。混合时以 `TABLE` 为准，
  外部节点单独成边——这样「这条 ETL 依赖的是目录里的表还是某个 parquet 目录」一眼能看出来。
- **`WRITE_MODE`**：`APPEND`（`INSERT INTO`）/ `OVERWRITE`（`INSERT OVERWRITE`）/ `UPSERT`
  （`MERGE INTO`）/ `CREATE`（CTAS）。它不影响图的形状，但影响人怎么读这张图。

### 4.3 列级边：五种 `kind`

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

`expression` 字段存引擎给的规范化文本（`Expression#sql`），**截断到 512 字符**，仅供人看，
不参与任何判定。

### 4.4 一个端到端例子

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

### 4.5 分区列与常量

三个细节必须在这里定死，否则列级边、表级边与图上的口径会互相打架：

| 场景 | 处理 |
| --- | --- |
| 静态分区 `PARTITION (dt='x')` | 目标列 `dt` = `CONSTANT`，`sources` 空 |
| 动态分区 `PARTITION (dt)` / 不写分区 | `dt` 是 `query` 输出里的一列，按普通列解析 |
| 目标列多于 query 输出 | 多出来的列（通常是分区列）记 `CONSTANT`，`expression` 留空并标 `implicit=true` |
| 目标列少于 query 输出 | 不允许（引擎会报错，能走到 `onSuccess` 就不会出现） |

### 4.6 视图的两种口径（本期只做一种）

| 口径 | 含义 | 谁提供 | 本期 |
| --- | --- | --- | --- |
| `RESOLVED` | 引擎真正读的物理表（视图已展开） | `analyzed`（`WithCTE` 处再走一次 optimizer） | ✅ 唯一入库口径 |
| `AS_WRITTEN` | SQL 文本里写的那个名字（可能是视图） | `qe.logical()`（视图未展开） | ❌ P2 |

选 `RESOLVED` 的理由：列级映射只有它有。代价要写在这里免得以后被当成 bug：
写 `INSERT INTO t SELECT * FROM v` 时，**图上看不到 `v` 这个节点**，只看到 `v` 的底表。
需要「按 SQL 写的」那一层时，P2 补 `AS_WRITTEN` 边（`origin=SQL`，与 `RESOLVED` 边并存不替换）。

---

## 5. Spark 侧算法

### 5.1 入口与时序

```
QueryExecutionListener#onSuccess(funcName, qe, durationNs)     ← spark-listener-group-shared 线程
    │
    ├── 0. 总开关关着 → 立刻返回（监听器根本没注册，§10.2）
    ├── 1. 判语句类型：qe.analyzed 的根节点（或 CommandResult 里的 commandLogicalPlan）
    │      不是 §2.1 的八类之一 → 返回（SELECT / DDL / DCL 全部跳过）
    ├── 1b. 是命令的就地执行（qe.logical eq qe.analyzed）→ 记为待定（§13.7 第 11 条）
    ├── 2. 走查计划，得到：列级映射 + 源表集合（§5.2）
    ├── 3. 取目标表与目标列，按 §5.3 对齐，补静态分区常量
    ├── 4. 取 SQL 文本（按计划实例索引的旁路，可能为空）
    └── 5. 组装 LineageRun → 入队（§6.4）
```

第 1 步**只认节点类型，不认 SQL 前缀**：`INSERT` 的文本经 analyzer 之后可能变成 `AppendData`
（`writeTo` 路径）或 `InsertIntoHadoopFsRelationCommand`（`saveAsTable` 路径），按前缀判断会漏。

第 1b 步是落地时才补的：**一次写入会回调两次**。外层的 `logical` 是解析出来的
`InsertIntoStatement`，内层由 `QueryExecution#eagerlyExecuteCommands` 产生——Dataset 创建期把
命令就地执行一遍，另走一次 `SQLExecution.withNewExecutionId`，于是又回调一次（`funcName` 是
`QueryExecution#commandExecutionName` 给的 `append` / `create` / `overwrite`）。两次说的是同一次
写入。

去重不能简单地「跳过内层」：`spark.sql("INSERT ...")` 不接 `collect()` 时，就地执行就是
**唯一一次**回调，跳过它 = 这次写入的血缘整个丢了（线上报障正是这个形态）。落地的规矩是
「待定 + 合并 + 兜底」：就地回调先记为待定 run（此刻取不到 SQL 文本，旁路的键是解析产物）；
语句级回调到达时撤掉待定、上报带文本的那条；宽限期（2s）内语句级回调没来，由守护定时器把
待定 run 兜底上报。判据见 §13.7 第 11 条。

`onFailure` 什么都不做——除了打一条 `WARN`（`executionId` + 异常摘要）。理由：失败的 INSERT
没有让任何依赖成立；记进去只会让图上出现「一次没写完却存在的边」。参考实现在 `onFailure` 里
也发事件（带 `exception`），那是给 Kyuubi 的运维面板用的，我们不抄。

**整个回调包一层 `try/catch(Throwable)`**：监听器抛异常会让 `spark.sql` 报一个与 SQL 无关的错，
用户会去查 SQL（§6.6）。

### 5.2 骨架：属性集下推 + 叶子回填

直接采用 §3.3 的形态（它比旧稿的「从目标列反查表达式」稳，理由见 §3.3 的四行对照表），
只加两样我们要的东西：**每个目标列的表达式文本**、**每个目标列的 `kind` 证据链**。

```
extract(node, parent: Map[Attribute, AttributeSet], inputs: Set[String]): Map[Attribute, AttributeSet]
```

用 §4.4 那条 SQL 走一遍（计划形状与真实 `analyzed` 一致）：

```
InsertIntoStatement(paimon.dws.order_daily, partitionSpec=(dt -> '2026-10-20'))
└── Project [user_id#1, gmv#5, order_cnt#6, '2026-10-20' AS dt#9]        ← 目标列对齐 + 静态分区
    └── Aggregate [user_id#1, sum(amount#2) AS gmv#5, count(distinct order_id#3) AS order_cnt#6]
        └── Join Inner (o.user_id = u.id)
            ├── Relation paimon.ods.orders  (user_id#1, amount#2, order_id#3, ...)
            └── Relation paimon.dim.users   (id#10, is_active#11, ...)
```

| 走到的节点 | `parent`（输出属性 → 源属性集） | 发生了什么 |
| --- | --- | --- |
| `InsertIntoStatement` | `{user_id#1→{user_id#1}, gmv#5→{gmv#5}, order_cnt#6→{order_cnt#6}}` | 目标列名建立；`dt` 是静态分区 → 不进下推，直接落 `CONSTANT` |
| `Project` | 同上（exprId 未变） | `dt#9` 的表达式是 `Literal` → `CONSTANT`；其余原样下推 |
| `Aggregate` | `{user_id#1→{user_id#1}, gmv#5→{amount#2}, order_cnt#6→{order_id#3}}` | `sum(amount)` 的 `references` 是 `{amount#2}`；`count(distinct order_id)` 同理 |
| `Join Inner` | 两份拷贝分别传给两侧 | `join` 时用 exprId 查不到就丢 |
| `orders`（叶子） | `{amount#2→paimon.ods.orders.amount, order_id#3→…order_id, user_id#1→…user_id}` | `inputs += paimon.ods.orders`，属性打上 qualifier |
| `users`（叶子） | 空 | parent 里没有 `users` 的属性 → **但 `inputs += paimon.dim.users`** |

产出与 §4.4 那份 JSON 完全一致，`dim.users` 只在表级、不在列级。**这个「漏」是算法结构的直接结果，
不是遗漏**：`Join` 传递 `parent` 给两侧，`users` 没有任何一列被引用，自然没有列边。

两个实现细节容易写错：

1. **`AttributeSet` 要用 `exprId` 做身份，不是 `name`**。`o.user_id` 与 `u.id` 在 `ON` 之后可能同名，
   按名字合并会把两条链焊在一起。
2. **`Project` 里 `Alias` 的 `references` 已经在 `getSelectColumnLineage` 阶段算好了**，不要在走查里
   再递归表达式——递归会重复计算 `ScalarSubquery` 的子计划，而子计划的 `inputs` 收集只能做一次
   （做两次会在 `inputs` 里留重复项，虽然 `Set` 能去重，但子查询的列级边会被挂到错误的父层）。

### 5.3 目标表与目标列

**目标表**（比 §2.1 的表多两列，说明怎么取三段名）：

| 节点 | 目标表来源 | 备注 |
| --- | --- | --- |
| `InsertIntoStatement` | `table()`（类型 `LogicalPlan`，分析后为 `DataSourceV2Relation` 或 `HiveTableRelation`，从中取 `identifier` / `tableMeta.identifier`） | 已解析，直接拿三段名 |
| `AppendData` / `OverwriteByExpression` / `OverwritePartitionsDynamic` | `table()`（声明类型就是 `NamedRelation`，实际是 `DataSourceV2Relation`） | `AppendData` 另有 `isByName()` |
| `CreateTableAsSelect` / `ReplaceTableAsSelect` | `name()`（分析后是 `DataSourceV2Relation`；未解析时退化为 `tableName()` 的 `Identifier`） | 表刚建好，**可能还没进目录**：上报时按 `CREATE`，服务端允许「目标表尚不存在」 |
| `InsertIntoHadoopFsRelationCommand` | `catalogTable`（`Option[CatalogTable]`）里的 `identifier`；为 `None` 时退化为 `~file:` | V1 裸路径写入 |
| `MergeIntoTable` | `targetTable` | 只取写目标，`MERGE` 的 `UPDATE`/`INSERT` 分支不拆分（§12 #8） |

**目标列**必须与 `query` 的输出列按引擎规则配对，四种形状：

| 形状 | 判定字段 | 对齐规则 |
| --- | --- | --- |
| 全列按位置 | `userSpecifiedCols` 为空、`byName=false` | `table.output` 与 `query.output` 逐下标对齐 |
| 指定列清单 | `userSpecifiedCols` 非空 | 名单里的每个名字去 `table.output` 里查，再与 `query.output` 逐下标对齐 |
| 按名字 | `byName=true`（`AppendData` 上是 `isByName()`） | 目标列名 ↔ `query.output` 的同名列，**与顺序无关** |
| 带分区 | `partitionSpec` 非空 | 静态分区先落 `CONSTANT`；动态分区按普通列处理 |

`byName=true` 是最容易写错的一条：`INSERT INTO t BY NAME SELECT b, a` 里目标 `a` 来自
query 的第 2 列而不是第 1 列。按位置对齐会得到一张完全反了的图。

参考实现在这里做得比我们松：它直接把 `query` 的输出列名前面拼上目标表名，**不做目标列对齐**
（`userSpecifiedCols` 只在 `CreateViewCommand` 分支里用到）。之所以它对，是因为它只回答
「`t.a` 来自谁」，而 `t.b` 有没有被写它不管；我们要回答「`t` 的每一列分别来自谁」，
包括静态分区那一列，所以必须自己对齐。

### 5.4 逐节点：留、去、补

| 参考实现的分支 | 我们 | 说明 |
| --- | --- | --- |
| `Project` / `Aggregate` / `Window` / `Expand` / `Generate` | 留 | 逐字照抄，`Expand` 的转置与 `Window` 的独立映射都是踩过坑的形状 |
| `Join` 的 `LeftSemi` / `LeftAnti` | 留 | 不这么写，`WHERE EXISTS` 的右表会从表级依赖里消失 |
| `Union` 按位置 zip | 留 | 唯一能处理新 `exprId` 的办法 |
| `WithCTE` → 跑一次 optimizer | 留 | 强制要求：`analyzed` 里 CTE 没内联 |
| `CommandResult` → 反射取 `commandLogicalPlan` | 留 | 否则 `spark.sql("INSERT ...")` 一条都抓不到 |
| `InMemoryRelation` → `cacheBuilder.cachedPlan` | 留 | 缓存表不展开就断链 |
| `LocalRelation` / `OneRowRelation` | 留（改名为 `~values`） | 见 §5.6 |
| `View`（临时视图内联 / 永久视图开关） | **改**：永久视图一律内联（等价于 `skip=false`），不提供开关 | 开关会让历史边与新边混在一起，图形状会突变（§3.8 #6） |
| `MergeRows` / `MergeIntoTable` | 留 | `MERGE` 的目标列来自多分支，必须合并 |
| `InsertIntoHiveTable` / `InsertIntoDataSourceCommand` / `SaveIntoDataSourceCommand` / `ReplaceData` / `WriteDelta` | **去** | Hive / Delta 路径，本仓不存在 |
| `__count__` / `__subquery__` 占位 | 留 | 少一个就丢一类边 |
| `isMetadataAttr` 过滤 | 留 | 不加会在图上冒出用户没写过的列 |
| — | **补**：`kind` 判定 | 参考实现没有（§3.8 #1），但图上要区分直通与派生（§5.5） |
| — | **补**：表达式文本 | 同上；只在目标层记录，不逐层记录 |
| — | **补**：`run` 与幂等键 | 参考实现交给 Atlas（§7.1） |
| — | **补**：`~` 前缀映射表 | 参考实现直接丢掉无 `identifier` 的关系（§5.6） |

落地位置：抽取器放 `lakehouse-rest-spark/shared/src/main/scala`（与 `ManagementSparkExtensions`
同处，本来就有 Scala 依赖）；上报器与队列放 `lakehouse-rest-spark-common`（纯 Java，
复用已有的 `ManagementApiClient`，**不依赖 Spark**，将来别的引擎也能用）。
版本差异若出现，落 `-3.5` / `-4.1` 各自的 `src/main/scala`（与 `LakehouseRestSqlParser` 同一层）。

### 5.5 列 `kind` 怎么判

`kind` 是**我们额外要的**：它不影响边是否存在，只影响人怎么读。判定输入是两样东西——
目标层的表达式（`Project#projectList` 里那一个）与下推回填后的源属性集合：

| kind | 判定 | 例子 |
| --- | --- | --- |
| `DIRECT` | 表达式是裸 `Attribute`（或 `Alias(Attribute)`），源集合恰好 1 个 | `SELECT o.user_id` |
| `DERIVED` | 源集合非空，表达式不是裸属性 | `a + b`、`UPPER(name)`、`CAST(x AS BIGINT)`、`COALESCE(a, b)` |
| `AGGREGATE` | 源集合的产出经过 `AggregateExpression`（`SUM`/`COUNT`/`MAX`/`AVG`/`COLLECT_LIST`…） | `SUM(o.amount)`、`COUNT(DISTINCT o.order_id)` |
| `CONSTANT` | 源集合为空，且表达式是 `Literal` / `CurrentDate` 之类无引用表达式 / 静态分区 | `'2026-10-20'`、`current_date()` |
| `UNRESOLVED` | 源集合为空但表达式**不是**常量；或源属性里带保留占位名；或目标是 `__` 开头的列 | UDF 返回值、`MONOTONICALLY_INCREASING_ID()`、`__row_operation` |

判定顺序就是上表从上到下，**先命中先算**。`UNRESOLVED` 是兜底，不是常态；它的存在是为了让
「不知道」这件事显式化——一条 `UNRESOLVED` 边在控制台上标灰并提示「该列经过无法解析的表达式」，
比编一个 `DIRECT` 出来诚实。

怎么拿到「经过聚合」这个证据：走查时不能只看表达式，`Aggregate` 节点的 `aggregateExpressions`
才是权威。做法是在 `Aggregate` 分支里额外记一张 `output → isAggregate` 的旁表（`__count__` 占位
也在这张表里标上），走到目标层时查一下即可。**这是本文与参考实现在数据结构上的唯一分歧**：
它只有 `Map[Attribute, AttributeSet]`，我们多一张 `Map[Attribute, KindHint]`。

### 5.6 名称归一化：从 `Attribute` 到 `ref`

| 引擎对象 | `ref` | 备注 |
| --- | --- | --- |
| `DataSourceV2ScanRelation` / `DataSourceV2Relation`（有 `identifier`） | `{catalog}.{ns1.ns2}.{table}` | catalog 为空取 `spark.sql.defaultCatalog`；与 `NamespacePaths` 同口径 |
| `LogicalRelation` + `catalogTable`（V1） | 补 default catalog 后同上 | `qualifiedName` 只有 `db.table` |
| `LocalRelation` | `~values` | 所有 `VALUES` / 常量 `SELECT` 共用一个节点 |
| `OneRowRelation` | 不产生节点 | 常用于 `WHERE x IN (SELECT max(y) …)`，只影响行 |
| `InsertIntoHadoopFsRelationCommand` 且 `catalogTable = None` | `~file:{path 的 sha256 前 12 位}` | 裸路径写入（`save("/tmp/x")`） |
| 临时视图（`isTempView`） | 不产生节点（已内联） | P2 的 `AS_WRITTEN` 才记 `~temp:{name}` |
| 有 `identifier` 但不是 Paimon/Iceberg 的表（外部连接器） | 照常三段名 | 它就在目录里，能点开 |
| 无 `identifier` 的 `DataSourceV2Relation`（`spark.read.format(...)`） | `~external:{provider}` | 点不开，但要知道「依赖了一个外部源」 |

三条规则：

1. **大小写原样保留**。`Attribute#name` 已经是去引号、按引擎规则折叠后的真实列名；
   再折叠一次等于替引擎做决定（`SELECT "A" FROM t` 在大小写敏感的表上会指错列）。
2. **`~` 是保留位**：服务端在建库 / 建表时拒绝 `~` 开头的名字（一行校验），保证不会与真表名冲突。
3. **列名以 `__` 开头一律 `UNRESOLVED`**：`__count__` / `__subquery__` / `__local__` 是占位，
   不落库（§3.5）。

### 5.7 表级源与列级源：两个都得记，且不一致是有信息的

| | 来源 | 内容 |
| --- | --- | --- |
| 表级 `sources[]` | 走查时每个叶子 relation 的 `ref` | 含**只出现在 `JOIN` / `WHERE` 里**的表 |
| 列级边 | 回填后的 `Map[Attribute, AttributeSet]` | 只含真的流进目标列的表 |

两者不一致的两种情况，方向决定了含义：

- **在 `sources` 里、不在列级边里** → 这张表只影响「行」（过滤 / 连接条件），不影响「列」。
  控制台画一条**虚线边**并标注「只影响行过滤」。这是参考实现做不到的（它只有一份 `inputTables`，
  默认口径下图上会显示成实线，读者会以为有列映射）。
- **在列级边里、不在 `sources` 里** → **是 bug**。上报前本地断言（`assert` 级别，只打日志不抛）：
  这类不一致一定是 `inputs` 收集漏了某个叶子分支（`LeftSemi` 与 `Union` 是历史高发区）。

### 5.8 与参考实现的差异清单

| # | 项 | 参考实现 | 我们 | 为什么 |
| --- | --- | --- | --- | --- |
| 1 | 产物 | `Lineage(inputTables, outputTables, columnLineage)` | `LineageRun` + 表级边 + 列级边（带 `kind` / `expression` / `runId`） | 它交给 Atlas，我们自存（§3.8 #1/#2） |
| 2 | 表级源口径 | 默认按计划，legacy 按列（配置切） | 固定按计划，并把「列边未覆盖」标成虚线边 | 一份数据两种读法，不靠配置（§5.7） |
| 3 | `Union` / `count(*)` / `LeftSemi` / `WithCTE` / `CommandResult` / `InMemoryRelation` | 各自特例 | **逐字照抄** | 这些是「不这么写就错」的形状，没有改进空间 |
| 4 | 目标列对齐 | 不做（只拼目标表名） | 做（`userSpecifiedCols` / `byName` / `partitionSpec`） | 我们要回答「每一列来自谁」，包括静态分区列（§5.3） |
| 5 | 视图 | 开关二选一 | 只有 `RESOLVED`；P2 加并存的 `AS_WRITTEN` | 开关会让图突变（§4.6） |
| 6 | 出口 | 3 个 dispatcher（Spark 事件总线 / Kyuubi 事件总线 / Atlas） | 1 个 HTTP 上报器 + 内存队列 | 我们只有一个下游（§6.4） |
| 7 | 失败处理 | `None` + WARN，静默丢 | 计数 + WARN + 定期上报「跳过原因」 | 漏报要能被发现（§3.8 #4） |
| 8 | 反射 | 散落在 600 行里的 `getField` | 集中到一个 `PlanFields` 对象（约 12 个访问器） | 升级 Spark 时只改一处，且这 12 个能被单测逐个钉住（§11.3） |
| 9 | 语言/模块 | Scala，单模块（另有一个 `-shaded`） | Scala 抽取器放 `shared/`，Java 上报器放 `-common` | 与本仓既有分层一致（§5.4） |
| 10 | 列名可信度 | 直接落库 | upsert 时按当前 schema 校验，不匹配标 `STALE` | 列改名后不留孤儿边（§7.5） |

### 5.9 3.5 与 4.1 的差异

| 项 | 3.5.9 | 4.1.2 | 影响 |
| --- | --- | --- | --- |
| `InsertIntoStatement` 七个字段 | `(table, partitionSpec, userSpecifiedCols, query, overwrite, ifPartitionNotExists, byName)` | **完全相同**（javap 核对） | 抽取器可以共享一份 |
| `userSpecifiedCols()` 返回类型 | `scala.collection.Seq` | `scala.collection.immutable.Seq` | Scala 2.12 / 2.13 集合体系不同 → 调用处按 `Seq` 抽象写，别写死 `immutable` |
| `withNewChildrenInternal` 参数 | `IndexedSeq` | `immutable.IndexedSeq` | 只在人工构造 plan 的测试里出现 |

结论：**抽取器放 `shared/`**，不需要像 `ParserInterface` 那样在版本模块里分裂。
若 4.x 后续把 `InsertIntoStatement` 换成别的节点，只改匹配处与 `PlanFields` 的访问器。

---

## 6. 上报

### 6.1 端点

```
POST /api/management/v1/lineage/runs          # 批量上报，单次最多 50 条 run
GET  /api/management/v1/lineage/tables        # 表级上下游（§8.1）
GET  /api/management/v1/lineage/columns       # 列级映射（§8.2）
GET  /api/management/v1/lineage/column-edges  # 图视图的批量列边（§8.7）
GET  /api/management/v1/lineage/runs          # 运行历史（§8.3）
GET  /api/management/v1/lineage/runs/{runId}  # 一条 run 的明细（含 SQL 与列映射，§8.3）
```

为什么挂在**管理 API** 而不是 catalog API（`/v1/{prefix}/...`）：

1. 血缘是**跨 catalog** 的元数据，`{prefix}` 只是其中一个维度；
2. 客户端已经有管理 API 的地址与令牌（`spark.lakehouse.rest.management.url` /
   `spark.lakehouse.rest.token`），上报器直接复用那个 HTTP 客户端（`ManagementApiClient`），
   不需要新的配置世界；
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
| `engine` | ✅ | 本期唯一取值 `SPARK`；枚举留扩展位，未知值 400 |
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

**JVM 退出兜底（2026-10-22 补，短命作业的最后一块）**。守护线程不阻塞作业的另一面是：
「语句结束 → JVM 退出」的窗口比「监听总线投递 → 待定宽限期 2s → 攒批间隔 2s → POST」短时
（单元测试、`spark.sql` 一条 INSERT 就收工的脚本作业），最后一批 run 会被守护线程的死一起
带走。两处修复，都在实测复现之后落地（`PaimonS3Test` 连续 5 次丢 run）：

1. **退出兜底 hook**（`LakehouseLineageListener#armShutdownHook`）：扩展路径在注册监听器时
   挂 JVM shutdown hook，等「已解析写语句 → 已送达回调」的差值归零（正常毫秒级，上限 5s、
   `SparkContext.isStopped` 止损），再把仍在宽限期里的待定 run 立即入队并 `close()` 上报器
   （flushRemaining 会把积压发完）。解析是同步的，`SqlTextRegistry.record` 顺手记下
   「一条 run 应该会来」——回调走异步总线，JVM 退出时可能来不及，这个差值是退出时刻
   唯一可靠的「还有 run 在路上」信号。
2. **重试不吃中断位**（`LineageReporter#send`）：`close()` 用 interrupt 叫醒 poll，但
   interrupt 若落在 worker 正在发送的窗口，残留的中断位会让 `HttpClient.send` 立刻抛
   InterruptedException，且 sleep 刻意保留中断位——三次重试全部秒废，表现为「hook 明明
   入了队、close 也调了，run 还是没到」。每次尝试前 `Thread.interrupted()` 清位。
   实测复现的失败信息：`interrupted while calling the management API`。

### 6.5 谁可以上报（判权）

| 检查 | 规则 | 不满足 |
| --- | --- | --- |
| 认证 | `BearerAuthInterceptor` 解析出主体（与目录 API 同一套） | `401` |
| 目标表 | 对每个 `targets[].table` 需 `TABLE_WRITE_DATA` | `403`，报文里指出**第一个**不足的资源 |
| 源表 | 对每个 `sources[].table` 需 `TABLE_READ_PROPERTIES` | 同上 |
| 外部节点（`~` 前缀） | 不判权——它不是目录里的东西 | — |

判权复用 `AuthorizationService.coversScope` + `NamespacePaths` 那套（与 Iceberg 侧的
`…/tables/{t}/metrics` 判 `TABLE_WRITE_DATA` 同口径），不新造一套判定。

**落地时的权限名映射**：上表的 `TABLE_WRITE_DATA` / `TABLE_READ_PROPERTIES` 是 Polaris 原始
权限名，本仓的 `Access` 枚举只有 12 项，落地取的是**目标表 `Access.UPDATE` + 源表
`Access.DESCRIBE`**——与 Iceberg 侧 `…/tables/{t}/metrics` 判 `UPDATE`、读表结构判 `DESCRIBE`
同一口径（见 [`authorization.md`](authorization.md) §2.4）。判权收在
`LineageService#requirePermission` 一处：`authorizationService.enabled()` 为假时整段跳过
（与目录 API 一致——鉴权关掉的部署不该只有血缘报 403），外部 `~` 节点直接放行。

**读端点不判表级权限**：`GET /tables|columns|runs` 只走管理 API 的既有门禁（认证 +
服务管理员），与事件页、`/meta` 一致——能看见目录的人就能看见这张表的上游。若哪天要按
「看不见的表不出现在图里」收紧，得在 `LineageService` 里逐节点判、并且**先过滤再算预算**
（否则 `budget` 会泄露不可见邻居的数量），这是 §8.6 那套契约的一次性改动。
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
表名接 `lakehouse_` 前缀，落地后 `data-model.md` 的表数从 20 变 23（已落地，见其 §2.3）。

**`lakehouse_lineage_run`**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint 自增 PK | 排障用的顺序号 |
| `run_id` | varchar(64) | 客户端 UUID，`uk_lineage_run` |
| `engine` | varchar(16) | `SPARK`（本期唯一取值） |
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
| `target_refs` | text | **本文原先没有这一列**（见 §13.1）：目标表全名列表，存成 `|ref1|ref2|`，供 `GET /runs?ref=` 过滤——run 自己不存表名的话，「历史」这个问题只能靠边表答「最近一次」 |
| `columns_truncated` | boolean | 列级边是否因 `max-columns-per-target` 被丢弃（降级为表级）。上报侧的事实，落地成列而不是塞 `attributes_json`：响应里要回它，索引里不用，但查询要看 |

索引：`uk (run_id)`、`(catalog, finished_at)`、`(finished_at)`（清理用）。

**`lakehouse_lineage_table_edge`**

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `id` | bigint 自增 PK | |
| `source_ref` | varchar(1024) | 全名，含 `~` 前缀的外部节点 |
| `source_ref_hash` | char(64) | `source_ref` 的 SHA-256，**唯一键的一部分** |
| `target_ref` / `target_ref_hash` | varchar(1024) / char(64) | 同上 |
| `edge_kind` | varchar(16) | `TABLE` / `EXTERNAL` |
| `engine` | varchar(16) | 同一对表可能被不同引擎写过 → 预留进唯一键，将来加引擎不用改表。**本期恒为 `SPARK`，但列必须在**：唯一键里少一列，将来加引擎就是一次数据迁移 |
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
| 同一对表被不同引擎各写过（将来） | 两条边（`engine` 不同），不合并——「哪个引擎在写」是有效信息 |
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

### 8.7 图视图的批量列边

```
GET /api/management/v1/lineage/column-edges?refs=paimon.ods.orders,paimon.dws.order_daily
```

§8.2 回答的是「**一张表**的列从哪来」（单表视角，按目标列分组）；图上要画「**展示中的这些表**
之间，列与列怎么连」（图视角），那是另一个问题——所以单独一条端点，不往 `/columns` 上堆参数。

节点集合由**调用方给**（逗号分隔、服务端去重、最多 64 个，超了 400）：控制台上的图已经
经过过滤、展开与截断，服务端按根重算一遍得到的集合与它对不上——多出来的边在图上没有落点，
少了的边让卡片缺行。集合由持有最新图的一方给，是最不容易错的口径。列级图本来就只为小图
准备（§9.5(3)），64 个 ref 足够。

响应沿用紧凑约定，`refs` 的下标即节点号：

```json
{"refs": ["paimon.ods.orders", "paimon.dws.order_daily"],
 "edges": [[0, "amount", 1, "gmv", "AGGREGATE"], [0, "id", 1, "id", "DIRECT"]]}
```

每条边是 `[源下标, 源列, 目标下标, 目标列, kind]`，按 `(目标下标, 目标列, 源下标, 源列)`
排序（确定性，§9.2 同一条规矩）。**常量列 `s = -1`、`sc` 为空串**：它们不产生画出来的线，
但让目标卡片的列清单里有一行「被写过、无上游」的列——缺了它，`PARTITION (dt=...)` 的
分区列会从卡片上消失，用户会以为这条 INSERT 没写它。**源不在集合里的边不回**：
那半条边在图上没有落点；表级图不受影响（`/tables` 照常返回这条边）。

---

## 9. 展现

> 本节推翻了初稿「不做图可视化」的结论，理由在 9.0。图是必做项，但必须带预算：**节点预算、
> 帧预算、列预算**。三条里任何一条不设，"看一眼全链路"就会变成"把浏览器卡死"。
> 9.1–9.10 定行为，9.11–9.14 定落地（组件 / 依赖 / 页签集成 / 数据流 / 效果图）。

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

初稿还有一个「只看有列级映射」（`columnCount > 0`）的开关，**已删**：卡片列清单默认就在图上，
「哪些边真的带了列」看卡片有没有列、有没有列连线即可判断；再放一个过滤器等于让用户
为一个已经被看见的事实多做一次操作。

过滤在前端做（数据已在内存），**不重新请求**：过滤是探索动作，一分钟要做几十次。

「淡化」而不是「隐藏」是个刻意的选择：隐藏会改变布局（节点消失、整图重排），淡化不改。这一条
在 9.5 的列级聚焦里同样适用。

**(5) 迷你图**：图尺寸 > 视口 2 倍时，右下角 160×120 缩略图（节点画 1px 点 + 一个视口矩形），
点击/拖拽定位。缩略图 `throttle` 到 200ms 一帧——它不需要跟手。

### 9.5 字段很多：聚合 + 聚焦 + 虚拟列表

一张 300 列的表，上下游各 300 列，笛卡尔展开是 **90000 条列边**。这不是"渲染慢"，是**语义上没有人
能看**。所以列级不做「全展开的图」，做三层：

**(1) 表层默认不画列边，列边是小图上的显式开关**

表与表之间一条边，线宽 ∝ `log10(columnCount + 1)`（1 列 → 1px、10 → 2px、100 → 3.5px、1000 → 5px）。
任何规模下边数都是 O(表数)，与列数无关。

图视图有一个「列级血缘」开关（§9.13 的 `LineageFilterBar`），**默认开**：它只管**画不画列-列连线**。
卡片（列清单、类型图标、`N列` 开关）与开关无关——关掉只是少一层线，节点形态、位置、高度一个都不变，
同一张图的两种读法。形状随之变化会让「刚才找到的那张表」在切换瞬间跳位，那种闪断比多看一层线更贵。
有列连线时表级边淡化成引导线；没有列连线时表级边按正常强度画（0.15 透明度的引导线在没有列边的图里
等于看不见关系）。**卡片只在节点数 ≤ 24 时出现**——超过它，卡片高度会把布局撑到不可读，这正是本节开头
90000 条边结论的另一种表述；那时退回表模式并顺手关掉列级开关（没有卡片就没有线可指）。
数据来自 §8.7 的批量端点（一次请求，不分表逐个拉）。落地时对初稿的修正：初稿写
「表与表之间永远不画列边」，实际小图（几张大宽表核对字段映射）恰恰是最需要列边的场景，
一刀切禁掉是把「防 90000 条边」错放大成「防一切列边」。

卡片（§9.5(1) 的落地形态）自上而下三块：

| 区域 | 内容 | 交互 |
| --- | --- | --- |
| 标题行 | 表**短名**（catalog 与库名不占标题）+ `↑上游数 ↓下游数`（按去重后的邻居表算，不按边算） | 悬停 tooltip 补全 `catalog.namespace`；点击选中：**只给表头**（标题行 + 元信息行）叠一层很淡的强调色 + 强调色描边，列清单区不着色，点空白取消 |
| 元信息行 | 库徽标（去掉 catalog 的 `namespace` 前缀）+ `N列 ⌄` 开关 | 点 `N列` 展开/收起；根卡默认展开；过滤条上另有「展开全部列 / 收起全部列」一键到底（几十张卡不该一张张点） |
| 滚动体 | 类型图标 + 列名 + `kind` 点；超 `MAX_CARD_ROWS` 收 `+N` 行 | **固定高度**（6 行封顶），列多时滚轮在卡片内滚（不缩放画布）；列连线锚点随滚动移动，滚出视口的钳回体沿 |

选中为什么只染表头：点选之后人要看的是**这张卡的列清单**（以及它两侧的列边），整卡铺满强调色
只会把真正的内容压成反白小字；表头是「我点的是这张表」的标识，染它就够了。

列类型图标分五类：数字 `#`、字符 `T`、boolean `B`、复杂类型 `{}`（array / map / struct / binary 等）、
其余 `·`。类型不在 §8.7 的列边报文里——是面板**第二轮**并行拉各 ref 的表详情（`schema.fields`）
现查的：卡片先出（无图标），类型到了再补；表不在目录里（外部源）就那一列不画图标，不为它失败整批。

**(2) 列链路只画「聚焦的那一列」**

点表节点 → 右侧抽屉列出该表的列；选中一列 → 只画它自己的上游链路（通常 3–10 个节点、5–20 条列边），
**其余边淡化到 8% 不透明度**（不隐藏，理由同 9.4）。

列边的端点不落在节点中点，而是按列在该表中的**相对行号**分布纵向偏移（节点高 44px，最少留 3 行
间距）。否则 20 条列边全挤在节点中心，看起来像一束。

**(3) 字段列表才是主视图，且默认全部展开（2026-10-22 按用户要求改）**

| 分组 | 默认 | 为什么 |
| --- | --- | --- |
| 变换列（`DERIVED` / `AGGREGATE`） | 展开 | 人真正要看的：这列是谁算出来的 |
| 直通列（`DIRECT` 且同名） | 展开（可一键折叠成 `38 个同名列直通`） | 初稿按「数量最多、信息量最低」默认折叠，实际核对字段时逐列都在更符合直觉——折叠降级为可选的降噪按钮 |
| 常量列（`CONSTANT`） | 展开（可一键折叠成一行） | 分区列通常走这里 |
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
| 滚轮 | 以光标为锚点缩放，`k ∈ [0.25, 2]`；指针落在展开卡的滚动体上时改为滚卡片内容（不缩放画布） |
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

（9.10 以上是行为边界；9.11 起回到落地视角：组件怎么拆、要引什么、页签怎么挂、数据怎么流。
对应效果图 `docs/lineage-mockup.html`。）

### 9.11 组件清单与依赖

前面九小节定的是**行为**，这一节定**结构**。总原则只有一条：控制台现有 4 个运行期依赖
（`vue` / `vue-router` / `element-plus` / `@element-plus/icons-vue`），血缘**一个都不加**——
图库的否决表在 9.7，这里把剩下两个常见候选一并写掉，免得将来重新争论。

**引入清单（一张表说清）：**

| 类别 | 结论 |
| --- | --- |
| 运行期 npm 依赖 | **0 个新增**。第 5 个运行期依赖都不加 |
| 开发期 npm 依赖 | **0 个新增**。纯函数模块的单测用 Node 22 内置的 `node --test` 跑（`tools/lineage-tests/*.test.mjs`，`package.json` 加一条 `test:lineage` 脚本），不引 vitest——要测的只有两个无依赖的 ESM 模块，为此挂一个测试框架不划算 |
| 复用的 Element Plus 组件 | `el-table-v2`（邻接表与列映射，自带虚拟滚动）、`el-drawer`（节点抽屉，overlay 不挤压画布）、`el-select`（引擎 / 深度 / 最近写过）、`el-switch`（「列级血缘」开关）、`el-button`（「展开全部列 / 收起全部列」）、`el-tooltip`（边 hover）、`el-alert`（超预算降级提示）、icons |
| 复用的自有组件 | `ResourceState`（空 / 错 / 载三态；空态时动作条必须写在它外面，`docs/console.md` §10 钉着）、`TimeText`（`lastSeen` 的「3 分钟前」口径）、`CopyText`（抽屉里的 ref 复制） |
| 浏览器 API | Canvas 2D、`ResizeObserver`（DPR 重建）、`matchMedia('prefers-reduced-motion')`。不上 Web Worker——布局 O(n log n)，300 节点 < 5ms，`postMessage` 的结构化克隆比计算本身还贵 |

**常见候选的否决表**（9.7 的补充，覆盖 Vue 生态里的新候选）：

| 候选 | 体积（gzip，估） | 否决理由 |
| --- | --- | --- |
| `@vue-flow/core` | ~110KB | Vue 3 原生，但节点是 DOM——500 节点正是 9.3 表格里越过帧预算的那个方案；且它解决的是「流程编辑器」，我们只要「读图」 |
| `elkjs` | ~1.5MB（WASM） | 只为省 9.2 那 ~150 行分层代码，代价不成比例 |
| `vueuse` | ~15KB tree-shaken | 只为一条 `useResizeObserver`，手写 12 行 |
| `dagre` / `d3-dag` | ~30–60KB | **唯一保留的备选**（同 9.7）：P1 若布局不满意，只替换 `lineageLayout.js` 一个模块，接口已留好 |

**新增自研文件清单**（`lakehouse-rest-console/src/`，合计约 1900 行，含注释）：

```
src/
├── api/
│   ├── lineage.js               # 新增：§8.1 / §8.2 / §8.3 三个读取端点的薄封装
│   └── endpoints.js             # 追加 3 条（lineageTables / lineageColumns / lineageRuns）
├── components/lineage/
│   ├── LineagePanel.vue         # 页签入口：一次请求、三视图切换、ResourceState
│   ├── LineageGraph.vue         # Canvas 宿主：pan/zoom/hover、命中测试、MORE 徽标
│   ├── LineageAdjacency.vue     # 邻接表（同一份图数据，零请求）
│   ├── LineageColumnTable.vue   # 列映射表（§8.2，直通/常量可折叠汇总行）
│   ├── LineageNodeDrawer.vue    # 节点抽屉（表摘要 + 以它为根 / 打开表详情）
│   ├── LineageFilterBar.vue     # 过滤条（纯受控，不发请求）
│   └── LineageLegend.vue        # 图例 + budget 条（§8.6 的预算块在这里显形）
├── composables/
│   └── lineage.js               # useLineageView：数据、展开、过滤、URL 状态、视图偏好
└── utils/
    ├── lineageGraph.js          # 纯函数：compact 解码、邻接索引、过滤、聚焦链
    ├── lineageLayout.js         # 纯函数：9.2 的布局（rank→坐标、重心排序、折叠、增量合并）
    └── lineageRender.js         # 纯函数：9.3 的绘制（drawFrame(ctx, snapshot)，无状态）
```

职责与契约（行数是含注释的估算，用于判断「有没有写厚」）：

| 文件 | 职责 | 关键契约 | 行数 |
| --- | --- | --- | --- |
| `LineagePanel.vue` | 页签入口。挂载时发**一次** `GET /lineage/tables?compact=1&columnCount=1&depth=1&maxNodes=300`，此后展开按需 | props: `ref`（归一化全名）；切「列」页签才发 §8.2 | ~180 |
| `LineageGraph.vue` | 唯一持有可变交互状态（`view` / `hover`）的地方；把 DOM 事件翻译成语义 | emits: `select` / `expand` / `reroot` / `hover-edge`；**不 import 任何 element-plus 组件**（渲染层与 UI 库解耦，这条会进 verify-console） | ~260 |
| `LineageAdjacency.vue` | `el-table-v2` 渲染同一份图数据；超 500 节点自动降级的落点，也是无障碍路径 | props: `graph, filters` | ~120 |
| `LineageColumnTable.vue` | §8.2 按目标列分组；直通/常量默认展开、按钮一键折叠成汇总行（9.5 的四行分组表） | props: `columns` | ~170 |
| `LineageNodeDrawer.vue` | 表摘要 + 入边最近三次 + 两个动作。`el-drawer` 是浮层，**画布布局按「让位后的可用宽」重算**而不是被压扁 | props: `node`；emits: `reroot` / `open-detail` | ~140 |
| `LineageFilterBar.vue` | 9.4 的四项过滤 + 视图切换 + 图内搜索，全部受控 | `v-model:filters` / `v-model:view`；过滤**不重新请求** | ~110 |
| `LineageLegend.vue` | 图例 + budget 显形（「上游 812 张、已画 4」） | props: `budget, counts` | ~80 |
| `lineageGraph.js` | 数据整形 | `decodeCompact(payload)` / `applyFilters(graph, filters)` / `focusChain(graph, column)` | ~200 |
| `lineageLayout.js` | 9.2 全部内容 + 9.4 的折叠与增量合并 | `layout(graph, {root, collapsed}) → positions`；**同输入同坐标**（§11.6 的确定性用例测它） | ~220 |
| `lineageRender.js` | 9.3 全部内容：LOD（带滞回）、批量 `Path2D`、标签避让、DPR、主题缓存 | `drawFrame(ctx, snapshot)`；格式色只读 CSS 变量，**不写死色值**（同上，verify-console 钉） | ~280 |
| `useLineageView.js` | 状态编排：数据 + 展开 + 过滤 + `localStorage`（`lakehouse.console.lineage.view`）+ URL 同步 | 展开期间禁止并发第二次 `expand`（忽略 + 提示） | ~180 |

### 9.12 表详情页集成

**页签位置**：Paimon 页（`TableView.vue`）与 Iceberg 页（`IcebergTableView.vue`）都插在
「详细信息」之后、「数据预览」之前：

```
详细信息 · 血缘 · 数据预览 · 快照 · 标签 · 分支 · 文件列表 · 数据访问 · 存储优化
详细信息 · 血缘 · 数据预览 · 快照 · 文件列表 · 存储优化            （Iceberg 页）
```

理由有三：① 血缘**与表格式无关**（9.1 已声明），所以它不在任何 `v-if="isPaimonTable"` 组里、
位置固定不随格式跳动——连 `format-table` / `object-table` 也出现，只是大概率落在「还没采集到」
的空态；② 它回答「这张表从哪来」，属于表的元数据，和「详细信息」同层，而预览 / 快照 / 文件
是「表里有什么」；③ 两个详情页同位，从 Iceberg 表切到 Paimon 表不用重新找。

**加载方式**：

```js
const LineagePanel = defineAsyncComponent(() => import('@/components/lineage/LineagePanel.vue'))
```

- Canvas 布局与渲染（约 700 行）走独立 chunk，**不进表详情首屏包**——表详情是控制台最常打开的
  读路径，它的首屏体积不为一个大多数人只看一眼的页签买单。切到页签那一刻才拉（估 8–10KB gzip）。
- 两个详情页把 `lineage` 加进各自的「自包含页签」集合（Paimon 页的 `SELF_CONTAINED_TABS`、
  Iceberg 页的 `loadedTabs` 同机制）：组件自己按 `ref` 发请求，父页面只传名字，不替它编排加载时序。
- 换表（`watch([prefix, database, table])`）时**作废面板状态**（同数据预览换表作废内部 id 的做法），
  但保留 `?tab=lineage`——用户可能是在连续核对多张表的上游。

**ref 归一化**：路由参数是 `prefix / database / table`，血缘的 ref 按 §4.1 的点拼口径取
`[prefix, ...namespace 段, table].join('.')`，与 `NamespacePaths` 同源。两个边界：

- 多级 namespace：控制台的详情路由本来就逐级导航，`database` 段拿到的已是完整 namespace，直接拼；
- `~` 开头的外部节点不在目录里，**只能从图上进入**（「以它为根」），表详情页永远是三段真名。

**URL 状态**（9.6 的 `l*` 参数在表详情页怎么落）：分两步，避免一次改动触碰所有既有页签的刷新语义——

1. P0：只在切到血缘页签时 `replaceState` 写 `?tab=lineage`（挂载时读）；血缘内部状态用
   `ldir` / `ldepth` / `lfocus` / `lview` 前缀参数双向同步。其余页签维持现状（不进 URL）。
2. 将来统一：所有页签的 `tab` 进 URL（单独一次改动，与血缘无关）。

`lfocus ≠ lref` 表示「钻进了某个上游表」，浏览器后退一步回 root（9.6）；`replaceState` 只在
**状态变化**时调用，pan / zoom 一律不进历史栈。

### 9.13 数据流与状态边界

```
LineagePanel
  │  useLineageView(loader)   ← 一次 §8.1（compact=1 & columnCount=1 & maxNodes=300）
  ▼
lineageGraph.decodeCompact  → { nodes, edges, budget }      纯函数
lineageLayout.layout        → positions                     纯函数（同输入同坐标）
lineageRender.drawFrame     → 像素                          纯函数（不持有任何状态）
LineageGraph                → view / hover                  唯一的可变态，组件私有
```

六条规矩（每条都对应一个曾经吵过的方案）：

1. **数据与视图状态分离**。`graph` 不可变：展开、过滤都生成新对象；`view`（pan/zoom/hover）
   是 `LineageGraph` 的私有可变态。两者任一变化都置 `dirty`，而一帧的早退条件是「都没变」。
2. **纯函数边界就是测试边界**。`lineageGraph` / `lineageLayout` 不碰 DOM、不碰 UI 库，
   `node --test` 直接测（§11.6 的三组用例）；`lineageRender` 不进单测（jsdom + node-canvas
   成本远超收益），靠 9.8 的 bench 工具 + 人眼。
3. **展开是一次增量合并，不是重查**。`expand(ref)` → `GET ?ref=X&depth=1` →
   `merge()`：新节点插对应 rank 的**末尾**、已放置节点**坐标锁定**（9.4 的两条规矩在这里落地）。
   合并期间到达的第二次 `expand` 被忽略并提示——排队会让图在用户眼前连续跳两次。
4. **过滤链的顺序固定**：`decode → applyFilters → layout → draw`。「隐藏」类过滤（引擎、
   节点类型）**改变布局**——过滤是探索动作，重排比留白可读；「淡化」类（30 天未更新、
   hover 降不透明度）**不改布局**。两者的分界就是 9.4 里那句话：隐藏改布局，淡化不改。
5. **请求编排收敛在面板**。§8.1 一次 + 每次 expand 一次；§8.2 只在「列映射」首次切入时发；
   §8.3 运行历史只在抽屉里按需点开。ETag / `compact` 的 P1 项（9.9）不提前。
6. **主题与格式色的唯一来源**。`lineageRender` 启动时 `getComputedStyle` 读一次缓存，
   store 的 theme 变化时重读并置 `dirty`（9.3）。格式色来自三个 CSS 变量
   `--fmt-paimon` / `--fmt-iceberg` / `--fmt-external`（随本功能进 `index.css`）——将来目录页
   做格式徽标时复用同一组变量，**两处色不一样用户就会以为是两种东西**。渲染器里不允许出现
   硬编码色值（verify-console 钉这条）。

**空态细化**（补 §9.6 的三分文案）：「还没采集到」时工具条一起隐藏——没有节点，过滤与视图切换
没有对象；「被过滤空了」时工具条**保留**（用户得能点「清除过滤」）；加载失败保留「重试」。
ResourceState 的用法不变，差别在动作条放不放。

### 9.14 效果图与验收

**效果图**：`docs/lineage-mockup.html`（与 `table-data-preview-mockup.html` 同一套外壳：
侧边栏 + 面包屑 + 表详情卡片 + 页签行，右上角切状态）。表名编的是一张典型 DWS 宽表
`paimon.dws.order_daily`（上游 12 张、下游 45 张），五个状态对应方案里五个关键判断：

| 状态 | 展示的判断 | 对应小节 |
| --- | --- | --- |
| ① 首屏（双向 1 跳） | 首屏规模 = root 的邻居数，不是 2 跳子图；折叠节点 `+8` / `+43`；选中根节点的高亮态 | 9.4(1)(2)、9.6 |
| ② 展开 2 跳 | 按需展开后 5 层；`›` MORE 徽标；budget 条（上游 12 / 下游 45）；节点抽屉是 overlay；回刷环画在层外并带 `↺`；k=0.58 自动落中档 LOD（只画末段名） | 9.4(1)、8.6、9.2、9.3 |
| ③ 邻接表 | > 500 节点自动降级：顶部提示条 + 「仍要看图」出口；同一份数据零请求；外部节点「不可点开」 | 9.1、8.4 |
| ④ 列映射 | 按目标列分组；`gmv` 的 AGGREGATE 明细与表达式；直通列默认展开、可一键折叠；`UNRESOLVED` 标灰显眼 | 9.5 |
| ⑤ 空态 | 「还没有血缘」+ 为什么（只记执行成功的 INSERT）；采集点脚注 | 9.6、§12 #3 |

图里的数据是编的，但**渲染是真的 Canvas**——布局、贝塞尔边、LOD、徽标都按 9.2 / 9.3 的规则
绘制，所以它不只是视觉稿，还是渲染规则的图形化说明（实现时对着它核对第一帧）。

**verify-console 第 21 组**（17 条，已落地；下面五条都在里面，另有异步组件、格式色变量、布局纯函数、LOD 单点实现、`Path2D` 批量描边、只经 `lineageApi` 取数、列映射按需加载、500 阈值、画布让位、五个端点、`If-None-Match`/304）：

1. 两个表详情页都以 `defineAsyncComponent` 挂 `LineagePanel`，页签名「血缘」、name=`lineage`；
2. `SELF_CONTAINED_TABS`（Paimon）/ `loadedTabs`（Iceberg）包含 `lineage`；
3. `LineageGraph.vue` 与 `lineageRender.js` **不 import element-plus**（渲染层与 UI 库解耦）；
4. `lineageRender.js` 中不出现硬编码格式色值（只读 `--fmt-*` 变量）；
5. `?fixture=` 的 bench 入口包在 `import.meta.env.DEV` 里（生产构建中不存在）。

**性能验收**按 9.8：两张图（100 节点看固定开销、1000 节点看斜率）+ `tools/lineage-bench`，
数字写进 PR 描述，不进 CI。

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
| `cleanup-cron` | `0 30 3 * * *` | 过期 run 的清理节拍（cron）。`@Scheduled` 的占位符里也出现一次，改两处 |

默认关的另一个原因：血缘表一旦开始写，就**只能靠时间清理**（边表不按时间清），
先在测试环境把量跑准再开。

### 10.2 Spark 侧（`spark.lakehouse.rest.lineage.*`）

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 关时**不注册监听器**（零开销） |
| `column-level` | `true` | 关掉只上报表级（宽表场景省带宽） |
| `max-columns` | `2000` | 超过则本地降级为表级 |
| `queue-size` | `500` | 内存队列长度，满了丢弃 |
| `flush-interval-ms` | `2000` | 攒批间隔 |
| `url` / `token` | 空 | 留空则复用 `spark.lakehouse.rest.management.url` / `.token` |

两种安装方式，**推荐第一种**（与参考实现一致，用户零代码）：

| 方式 | 写法 | 说明 |
| --- | --- | --- |
| 配置项 | `spark.sql.queryExecutionListeners=io.github.melin.lakehouse.rest.spark.lineage.LakehouseLineageListener` | 用户自己起 `spark-sql` / `spark-submit` 时唯一可行的方式 |
| 扩展懒注册 | `ManagementSparkExtensions` 注入的解析器在**第一次 `parsePlan`** 时 `listenerManager.register(...)` | 只对用了本仓扩展的会话生效。不能在 `injectParser` 的工厂里注册：那一刻 `sessionState` 正在构建，而 `listenerManager` 又要读 `sessionState`，会栈溢出（§13.7 第 6 条）。**代价：纯 DataFrame 作业（一次 SQL 都没解析过）不会被自动挂载**，那种场景请用上一种 |

`enabled=false` 时两条路都不注册——**监听器本身有成本**（每次查询都进一次回调），
关掉要真的不挂上去，不能靠回调里 `if (!enabled) return`。

**`enabled` 只能来自 `SparkConf`**（`--conf`、`spark-submit --conf`、`SparkSession.builder().config(...)`），
`spark.conf.set` 改不动它：扩展式注册发生在**会话状态构建期**（`injectParser` 的工厂里），
那一刻只有 `SparkConf` 读得到——读 SQLConf 会重入构建流程并栈溢出（§13.7 第 6 条）。
其余各项（`column-level`、`queue-size`、`url`）在第一次回调时才读，那时两处都能读，
仍是「SQLConf 优先、`SparkConf` 兜底」。

### 10.3 为什么这一期不做 Flink（以及将来接进来要改什么）

不是「排期不够」，是**这一期想先把一个引擎做对**。Flink 的接入点在完全不同的位置：

| | Spark（本期） | Flink（不做） |
| --- | --- | --- |
| 采集时机 | 执行**后**：`QueryExecutionListener#onSuccess` | 执行**前**：`TableEnvironmentInternal#getParser().parse(sql)` |
| 原料 | `analyzed` 逻辑计划 | `ModifyOperation` + `QueryOperation` 树 |
| 列映射 | `AttributeReference.exprId` 贯穿 | `FieldReferenceExpression#getInputIndex()/getFieldIndex()` |
| 钩子 | 有全局配置项，零代码 | 没有等价物，只能显式包一层 `recordBefore` / `recordAfter` |
| 一次提交多个目标 | 单目标（多路插入除外） | `EXECUTE STATEMENT SET` 一次多 sink |

硬凑一份「引擎无关的抽取器」会得到一个只有 `extract()` 一个方法签名的空壳，
真正的逻辑仍然要分两份写——而这个空壳会让「为什么 Spark 的这份不做 X」这类问题
永远答不清。

**将来接进来不用改模型**，这是本期刻意留的：

1. 报文里 `engine` 已经是必填枚举，边表唯一键里已经含 `engine`（§7.2）——加引擎不改表结构；
2. `targets[]` 已经是数组，`EXECUTE STATEMENT SET` 的多 sink 装得下（§4.2）；
3. `mode=BATCH|STREAMING` 已经预留，流式作业的「提交 ≠ 写完」有地方表达（§12 #7）；
4. 上报器（`-common` 里的 Java HTTP 客户端）**不依赖 Spark**，Flink 侧换个抽取器就能复用。

届时唯一要新增的是：两个版本模块（1.20 / 2.3）各一份 `Operation` 遍历器，
以及 `flink-rest-catalog.md` 里那套「`shared` 主源码不 import Flink」的约束下的分层（约 200 行/版本）。

---

## 11. 测试与验收

### 11.1 抽取器必须是纯函数

```
LineageExtractor.extract(plan: LogicalPlan, sqlText: Option[String]): Option[LineageRun]
```

输入计划，输出归一化模型，**不做 IO、不查 catalog、不认识 HTTP**。
这是整个测试策略的地基：纯函数可以喂手工构造的计划树，不需要集群、不需要服务端。
参考实现的 `SparkSQLLineageParseHelper(sparkSession)` 就是这个形状（它需要 session 只为了
`optimizer.execute` 与 `SQLConf`），我们照抄。

### 11.2 表格驱动的用例矩阵（不起集群）

参考实现把用例分成三个 suite——`SparkSQLLineageParserHelperSuite`（表级 + 列级）、
`TableCatalogLineageParserSuite`、`RowLevelCatalogLineageParserSuite`——每个用例都是
「一段 SQL + 期望的 `(inputTables, outputTables, columnLineage)`」。

**这种形状值得逐字照抄**：它不依赖任何内部数据结构，重构算法时用例不用改；
反过来说，如果算法改了用例就得改，说明这个用例在测实现而不是测行为。

我们的矩阵（`local[1]` + `spark.sql.warehouse.dir` 指向 `target/`，建 Parquet 表）：

| SQL | 断言的重点 |
| --- | --- |
| `INSERT INTO t SELECT * FROM s` | 全列按位置；每列 `DIRECT` |
| `INSERT INTO t (a, b) SELECT x, y FROM s` | 列清单在 `analyzed` 里已被展开：`t` 的其余列是 `CONSTANT` + `CAST(NULL AS INT)`（§13.7 第 5 条） |
| `INSERT INTO t BY NAME SELECT b, a FROM s` | 名字对齐（**顺序反了也必须对**，这是最容易写反的一条） |
| `INSERT OVERWRITE t PARTITION (dt='2026-10-20')` | `dt` 是 `CONSTANT`，`sources` 为空 |
| `INSERT INTO t PARTITION (dt) SELECT ..., dt FROM s` | 动态分区按普通列解析 |
| `SELECT a FROM x UNION SELECT b FROM y` | 一列对两张表（位置 zip） |
| `INSERT INTO t SELECT count(*) FROM s` | `__count__` 保住 `s` 的列级边，`kind=AGGREGATE` |
| `... WHERE EXISTS (SELECT 1 FROM u WHERE ...)` | `LeftSemi`：`u` 出现在**表级**、不出现列级 |
| `WITH cte AS (...) INSERT INTO t SELECT ... FROM cte` | CTE 内联（`analyzed` 里还是 `WithCTE`） |
| `... WHERE x IN (SELECT max(y) FROM s)` | `__subquery__` 不落库，`s` 的边正确 |
| `SELECT explode(arr) ...` | `Generate` 的输出列能连回 `arr` |
| `GROUP BY GROUPING SETS ((a),(b))` | `Expand` 的转置 |
| `SELECT CAST(a AS BIGINT) + b ...` | `DERIVED`，两个源列 |
| `MERGE INTO t USING s ON ... WHEN MATCHED THEN UPDATE SET ...` | 多分支 assignment 合并；元数据列被过滤 |
| `CREATE TABLE t AS SELECT ... FROM s` | 目标表「尚不存在」也能上报 |
| `FROM s INSERT INTO t1 SELECT ... INSERT INTO t2 SELECT ...` | 两个目标，`targets[]` 长度 2 |
| 永久视图 / 临时视图作为源 | 都内联到底表（`RESOLVED` 口径） |
| `df.cache()` 后的表作为源 | `InMemoryRelation` 展开 |
| `df.writeTo("t").append()` | 无 `sqlText`，但列级仍准确 |

`byName`、静态分区、`UNION` 三列对齐各一条用例——这三处是最容易写反的。

### 11.3 反射访问器单测（版本升级的第一道闸）

参考实现用反射取私有字段（`InsertIntoStatement#query`、`CommandResult#commandLogicalPlan`、
`MergeRows#matchedInstructions`…）。反射没有编译期保护，升级 Spark 时**编译不会红、运行时才炸**。

对策：把所有反射收敛到 `PlanFields`（约 12 个访问器），每个访问器一条单测——
用 `spark.sql(...)` 真跑一条语句拿到 plan，断言访问器返回值与公开访问器一致（或在预期位置非空）。
这 12 条用例在两版本模块（`-3.5` / `-4.1`）里各跑一遍，升级时先红在这里。

### 11.4 e2e

`scripts/e2e-lineage.sh`（骨架照抄 `spark-paimon-rest-e2e.sh`，用例是
`LineageLiveServerTests`——4 例，默认跳过，需要 `-De2e.lineage.enabled=true` 与一个开了血缘的服务端）：

```
起 h2（18081，开 lineage、auto-create-catalog）→ 一个 PAIMON catalog
  ├── 建一个随机后缀的库 + src/dst/agg 三张 Paimon 表（每个用例各建一份）
  ├── 4 例：列级 kind（含 CAST）、count(*) 的 AGGREGATE、SQL 文本旁路、不接 collect() 的写入
  └── 断言：GET /lineage/runs|tables|columns 上的报文与 §4 的模型一致
```

无 `-De2e.management.url` 时整体 skip（与现有两个 e2e 脚本同规矩）。

**只在 3.5 上能跑。** 4.1 侧卡在建会话那一刻：Paimon 2.0.0 没有 Spark 4.1 的 bundle，4.1 模块
用 `paimon-spark-4.0_2.13`，它的 Spark 扩展加载 `Spark4Shim` 失败（`Unable to get public no-arg
constructor` → `Error while instantiating 'SessionStateBuilder'`），一条语句都发不出去。既有的
`ManagementSqlLiveServerTests` 在 4.1 上一模一样地红，可以拿它做对照——**这不是血缘的问题**。
4.1 侧靠 24 条抽取器单测（不建整会话）覆盖。Paimon 发布 4.1 bundle 后把脚本里的
`VERSIONS` 改回 `"3.5 4.1"` 即可（`docs/maven-module-design.md` §13.6）。

### 11.5 文档与契约一致性

本仓的做法是「文档里的数字与清单由脚本钉住」，这份文档同样要：

- `scripts/verify-lineage-doc.py`（已落地）：端点双向一致——`LineageController` 上的每个 `@*Mapping` 都必须在本文档里出现（漏写一个端点 → 红），文档里写的每个血缘端点也必须在控制器上（写了没实现 → 红）；§10.1 的配置键与默认值逐一对照`RestServerProperties.Lineage` 的字段（改名不改文档 → 红）；
- 表数：`data-model.md` 已是 23 张（§2.3 记血缘三表），`README.md` §1 同步；
- 文档计数：本文件已在 `docs/`（23 份）里，**加内容不改计数**；但 `AGENTS.md` §3 与
  `README.md` §9 那两行的描述要跟着改（已改成「三层都已实现」，抽取器落地后不能再写「§5 未做」）。

### 11.6 展现层

三组纯函数单测（不需要 DOM；跑法用 Node 22 内置的 `node --test`，`tools/lineage-tests/`，
**不引测试框架**——被测的只有 `lineageGraph.js` / `lineageLayout.js` 两个无依赖 ESM 模块，
见 9.11）：

1. **布局确定性**：同一份 `(nodes, edges, root)` 跑两次，坐标数组逐项相等；打乱输入顺序后坐标
   集合相同——排序必须用 `ref` 做 tie-break，不能依赖数组顺序；
2. **折叠**：单层 37 个节点 → 8 个真实节点 + 1 个 `+29` 占位；展开后 37 个，且**已锁定节点坐标
   一位不动**（§9.4 的增量合并规矩）；
3. **LOD 滞回**：k 从 1.0 降到 0.87 仍属高档、降到 0.85 落中档、再升回 0.88 仍属中档——不能抖。

**渲染本身不进单测**：Canvas 要 jsdom + node-canvas，成本远超收益。像素级问题（发虚、错位、色差）
靠 `tools/lineage-bench` 手测 + 一张截图人眼过；`verify-console.py` 只断言「血缘页签存在」「能切到
表格视图」这类有没有，不碰像素。

---

## 12. 风险与已知边界

| # | 风险 | 应对 |
| --- | --- | --- |
| 1 | Spark 4.x 后续把 `InsertIntoStatement` 换掉 | 匹配处集中在 `shared` 的一处 `match`；反射访问器集中在 `PlanFields`，两版本各跑一遍矩阵（§11.3），换节点时用例会红 |
| 2 | `QueryExecutionListener` 也收到 DataFrame 写入（`saveAsTable` / `insertInto`） | 接受。这类 run 的 `sqlText` 为空、`statementType=INSERT`，表级与列级仍然准确（`analyzed` 一样有） |
| 3 | 视图口径：图上只能看到底表，看不到 SQL 里写的视图名 | §4.6 已声明；P2 补 `AS_WRITTEN` 边（并存不替换）。**这是本期最大的语义缺口**，控制台需在血缘页脚注一句「视图已展开为底层表」 |
| 4 | 宽表 / 深嵌套 join 的列边爆炸（1000 列 × 5 表） | `max-columns` 本地降级 + 服务端 `max-columns-per-target` 二次兜底，标 `truncated` |
| 5 | 上报影响作业尾延迟 | 异步队列 + 攒批 + 队列满丢弃（§6.4） |
| 6 | 伪造血缘（声称写了不属于自己的表） | 判权（§6.5）+ `principal` 由服务端解析 + 图上的边带 `engine` 与 `lastSeen` 供追溯；**不做签名**，这是内网元数据服务的合理边界 |
| 7 | 流式作业：`INSERT` 提交 ≠ 数据写完 | `mode=STREAMING` 标记；控制台**不显示「最后写入时间」**，只显示「最后提交时间」，避免把长跑作业读成「一直在写」 |
| 8 | `MERGE INTO` 的 `UPDATE` / `INSERT` 分支不拆分 | 只记「目标表依赖源表」+ 列映射取 `UPDATE SET` 与 `INSERT` 列的并集，不表达「哪行走了哪个分支」 |
| 9 | 边表不按时间清 → 图会累积历史脏边（表删了、任务下线了） | 表删除时级联删边（§7.5）；「任务下线」无从得知 → 用 `last_seen` 在控制台标注「半年未更新」，不做自动清理 |
| 10 | 单图节点过多把浏览器拖死（一张 DWS 表 2 跳 800 个节点是常态） | 服务端 `maxNodes`（默认 300 / 上限 2000）+ 前端 500 阈值自动切表格 + 默认 1 跳按需展开（§8.6、§9.1、§9.4） |
| 11 | 宽表列边爆炸（300 列 × 300 列 = 90000 条） | 表层只画一条聚合边；列链路只在「聚焦一列」时画；`DIRECT` 直通列折叠（§9.5）。与 #4 的 `max-columns` 是两道独立的闸——前者管「画多少」，后者管「存多少」 |
| 12 | 参考实现的算法本身有已知未覆盖形状（如 `PIVOT`、`LATERAL` 子查询、`INSERT ... REPLACE WHERE`） | 未知形状不会被静默算错：走查落到「其它节点」分支时只透传 `parent` 给 children，最终表现为「源集合为空」→ 判 `UNRESOLVED` 并标灰，而不是编一条边。**这一条要在单测里显式覆盖**（喂一个 `PIVOT` 计划，断言 `UNRESOLVED` 而不是 `DIRECT`） |

**不做的事**（写清楚免得被反复问）：

1. **不做行级 / 文件级血缘**：那是执行期数据面的事（读 manifest、比 row id），与「SQL 声明了什么」是两个问题。
2. **不从查询日志反推**：日志里有 SQL 但没有解析后的计划，反推等于 §1.2。
3. **不做血缘的定时重算**：边是「最近一次执行」的事实，重算没有输入（历史 SQL 已经丢了）。
4. **不做跨集群血缘**：一个服务端实例的图只含上报到它的那些执行；多云 / 多集群要合并是另一个课题。
5. **不做图上的手工增删边**：手工维护的血缘会与真实执行漂移，而没有人会维护它（§9.10）。
6. **不做 Flink**：见 §10.3——不是永久不做，是这一期只把一个引擎做对。

---

## 13. 实现记录

落地范围：**服务端 + 控制台已完成**（三张表、五个端点、表详情页「血缘」页签，
外加删表级联与保留清理）；**§5 的 Spark 侧抽取器未写**——它是客户端埋点
（`spark.lakehouse.rest.lineage.*`，见 §10.2），归属引擎仓库。没有上报方时三个读端点
返回空图而不是报错，因此这一环缺着也不影响已落地的部分被验证。

### 13.1 与本文不一致的四处（都是落地时才发现的）

1. **`lineage_run` 多了 `target_refs` 与 `columns_truncated` 两列**（已补进 §7.2）。
   §7.2 原定的列集里 run 不存表名，于是 `GET /runs?ref=…`（§8.3）只能「先查边表拿
   `lastRunId` 再回查 run」——那只答得了「最近一次」，答不出历史，而这个端点存在的
   理由就是历史。存成 `|ref1|ref2|` 而不是 JSON 数组，是为了让等值过滤退化成一次
   `like '%|ref|%'`：两端各带一个分隔符，`paimon.dw.t1` 就不会命中 `paimon.dw.t10`。
   `columns_truncated` 同理是上报侧的事实，要回给客户端（「你的列映射被降级了」），
   不该沉在 `attributes_json` 里。
2. **权限名映射**：`TABLE_WRITE_DATA` / `TABLE_READ_PROPERTIES` 落到本仓 12 项模型上是
   `Access.UPDATE`（目标表）/ `Access.DESCRIBE`（源表），见 §6.5 的补注；
   **读端点不判表级权限**，只走管理 API 的既有门禁。
3. **紧凑节点从 4 位扩到 5 位**：`n = [ref, format, kind, more, stale]`。§8.6 原定 4 位，
   但服务端在目录里查不到某张表时给的是 `stale = true` 而不是报错（图的半边比 404 有用），
   而图只走紧凑编码——不带这一位，前端就永远画不出「这张表可能已被删」。
   `e` 仍是 8 位：`[src, dst, kind, writeMode, engine, lastSeen, runCount, columnCount]`。
4. **控制台不调 `POST /runs`、也不调 `GET /runs`**：写入口在管理 API 上，但「控制台
   手工上报一条依赖」等于伪造血缘；运行历史这一期没有页面，也就不在 `endpoints.js`
   登记——第 4 组「无死条目」会把声明了却没调用的端点判死。两件事都写进了
   `src/api/lineage.js` 的注释。

### 13.2 落到了哪些文件

| 位置 | 内容 |
| --- | --- |
| `lakehouse-rest-core/**/domain/entity/Lineage{Run,TableEdge,ColumnEdge}Entity.java` | 三张表（`@Table(comment=…)` 与每列注释齐全，`MysqlDdlGeneratorTests` 会查） |
| `lakehouse-rest-core/**/domain/repo/Lineage*Repository.java` | 三个仓储（边表按 ref 摘要查、run 按 `(finishedAt, id)` 倒序翻页） |
| `lakehouse-rest-core/**/support/LineageRefs.java` | `catalog.namespace.table` 的解析与归一化、`~` 保留前缀、`of`/`parse` 往返 |
| `lakehouse-rest-core/**/dto/LineageDtos.java` | 上报报文、`GraphPayload`、`CompactNode` / `CompactEdge`。两者都标了 `@JsonFormat(shape = ARRAY)`——**record 默认序列化成对象**，前端按数组下标解码会全崩 |
| `lakehouse-rest-core/**/service/LineageService.java` | 幂等入库、边 upsert、宽表降级、判权、图遍历、预算与 `more`、ETag 摘要 |
| `lakehouse-rest-core/**/event/LineageEdgeCleanupListener.java` | 订阅目录事件，删表/删库时按前缀清边（这就是三表放 core 的原因） |
| `lakehouse-rest-server/**/web/LineageController.java` | 五个端点 + `If-None-Match` 短路（`Cache-Control: no-cache`，304 不带 body） |
| `lakehouse-rest-server/**/service/LineageRetentionJob.java` | 按 `cleanup-cron` 删过期 run，只删 run 不动边 |
| `lakehouse-rest-console/src/api/lineage.js`、`api/endpoints.js` | 只有两个读方法；不发 `If-None-Match`（见文件头注释：`client.js` 把 304 当成功，`decode({})` 会得到一张空图） |
| `lakehouse-rest-console/src/utils/lineageGraph.js` / `lineageLayout.js` / `lineageRender.js` | 解码、布局、绘制三层，全是纯函数（不读 DOM、无随机数、无时钟） |
| `lakehouse-rest-console/src/components/lineage/*` | `LineagePanel` + `LineageGraph` / `LineageAdjacency` / `LineageColumnTable` / `LineageFilterBar` / `LineageLegend` / `LineageNodeDrawer`，**0 个新增 npm 依赖** |
| `lakehouse-rest-console/src/views/TableView.vue` / `IcebergTableView.vue` | 「血缘」页签，面板用 `defineAsyncComponent` 挂（两种格式都有这个页签，因为它不跟表格式伸缩） |

### 13.3 验收

| 层 | 用例 | 钉住什么 |
| --- | --- | --- |
| 仓储/服务（`LineageApiTests`，11 例） | 入库拆表/列边、重复上报幂等、第二次上报累加同一行、宽表降级为表级、未知引擎与语句类型 400、列图按目标列归组、`budget` 报真实邻居数且 `depth` 被上限截断、紧凑形状与 ETag 304、运行历史按表过滤与翻页、清理只删 run、删边不留悬空节点 | 语义，不是形状 |
| 开关（`LineageDisabledTests`） | `enabled=false` 时 `POST /runs` → 403，`GET /tables` → 200 空图 | 「没打开」与「没采集到」在界面上可区分 |
| 归一化（`LineageRefsTests`，8 例） | 多级 namespace、两段名、空段、外部节点、大小写、`~` 保留前缀、`of`/`parse` 往返 | 名称归一化是全部边的前提 |
| 抽取器（`LineageExtractorTests` 24 + `PlanFieldsTests` 4 例） | 24 张表驱动的计划 → 模型（§11.2）、`kind` 五种口径、`CAST`/`Alias` 不算变换、子查询只出表级边、V1/V2/CTAS/多路插入、常量的「最内层表达式」判据；反射访问器 4 例钉版本差异 | 计划 → 模型这一步，两个 Spark 版本各跑一遍 |
| 上报器（`LineageReporterTests`，4 例） | 攒批、队列满就丢、失败重试两次后计数、关机前 flush | 队列语义；不依赖网络 |
| 端到端（`LineageLiveServerTests`，4 例，需真服务端） | 列级 `kind` 一路报到服务端、`count(*)` 的 `AGGREGATE`、SQL 文本旁路、**一条语句一条 run**、**不接 `collect()` 也有 run** | 三段拼起来还对不对（见 §13.7 第 10/11 条） |
| 前端纯函数（`npm run test:lineage`，29 例） | 两种编码形状、索引、隐藏与淡化两类过滤、跳数即层号、多路径取 `min abs`、打乱输入坐标不变、环进 `ring`、折叠 `37 → 8 + "+29"`、坐标锁定、LOD 三档与滞回、边宽对数、`ellipsizeMiddle` | 布局与绘制可以脱离浏览器测 |
| 机械一致性（`scripts/verify-console.py` 第 21 组，17 条） | 异步组件、页签名与正文条件一致、不 import element-plus、格式色只读 CSS 变量、布局纯函数、LOD 只有一处实现、`Path2D` 批量描边、只经 `lineageApi` 读、不声明写入口、列映射按需加载、500 阈值、画布让位、五个端点、`If-None-Match`/304 | 这些错法都不会报错 |

`MysqlDdlGeneratorTests` 的表数从 20 改为 23，`sql/schema-mysql.sql` 重新生成
（23 张表 / 21 条唯一约束 / 30 条索引）。

### 13.4 默认配置

```yaml
lakehouse:
  rest:
    lineage:
      enabled: false              # 默认关：没有客户端埋点就没有数据
      run-retention-days: 90      # 只清 run，边不按时间清
      max-runs-per-batch: 50      # 超了 400，不静默截断
      max-columns-per-target: 2000
      max-depth: 5                # 参数超上限 400
      max-nodes: 500              # 硬上限 2000
      cleanup-cron: "0 30 3 * * *"
```

`cleanup-cron` 在 `@Scheduled` 的占位符里也出现一次（注解占位符在 bean 绑定之前解析），
改的时候两处都要动。

### 13.5 还没做的

> **§5 的 Spark 抽取器已经落地**（原第 1 条已完成）：`lakehouse-rest-spark` 里
> `shared/` 一份源码 + `-common` 纯 Java，`-3.5` / `-4.1` 两个版本模块各跑 24 + 4 + 4 例，
> 端到端在 `scripts/e2e-lineage.sh`。与前面几节**不一致的地方统一记在 §13.7**——
> 那些是落地时才知道的，照原稿写会写出错的东西。剩下三条：

1. **视图口径（§4.6 的 `AS_WRITTEN` 边）**：仍只有「展开到底表」一种口径，
   所以图上看得见底表、看不见 SQL 里写的视图名。控制台已在血缘页脚注说明。
2. **运行历史页面**：端点与服务端能力都在（`GET /runs`、`GET /runs/{runId}`），
   控制台只是不给入口——手工上报更不该给。
3. **Flink**：仍按 §10.3，模型不用改，只是不做。

### 13.6 一个踩过的坑：`@Modifying(clearAutomatically = true)` 会吞掉同一个事务里的删表

`LineageEdgeCleanupListener` 是**同步**监听器（`AuditEventPersistListener` 的邻居），
所以 `dropEdges` 跑在**删表那个事务里**。而删边的两个仓储方法最初写的是
`@Modifying(clearAutomatically = true)`：

- 外层事务此时刚排完队：`delete projections`、`delete table`（都还只在持久化上下文里，
  没到数据库）；
- `clear()` 把整个持久化上下文清空——那两条 delete 一起消失；
- 提交时什么都没删，而 HTTP 仍然返回 204/200。

症状的迷惑性在于**日志全绿**：`Dropped lineage edges of ice.db.orders: 0 table edges`，
没有异常、没有回滚。只有"删完再读还在"这个断言能抓住它——`IcebergRestCatalogApiTests`
与 `IcebergClientEndToEndTests` 正是这么红的（13 分钟的全量跑里就这两条 + 两条既有失败）。

结论：这两个 `deleteByRef`（以及 `deleteOlderThan`）**不加 `clearAutomatically`**。
"删完边后读到脏实体"在这里不成立——边是按 ref 现查的，上下文里本来就没有它们；
而"清空上下文"的代价是让调用方的事务静默失效。

> 这条也给全仓提了个醒：**凡是会在别人事务里被调用（事件监听器、`@Transactional` 服务内部复用）
> 的 bulk 操作，都不要 `clearAutomatically`。** 需要清的话，让调用方在外层事务结束后自己清。

### 13.7 抽取器落地时与前面章节不一致的地方

写代码时才发现的，逐条列出，免得下一个读 §5 的人照着实现又踩一遍。

**1. `kind` 的判定顺序必须反过来（§4.3 / §5.5 的表格）**

原稿顺序是 `DIRECT` → `DERIVED` → `AGGREGATE` → `CONSTANT` → `UNRESOLVED`，那样
`SUM(x)` 会先命中 `DERIVED`，`AGGREGATE` 永远判不出来。落地顺序：

```
DIRECT → AGGREGATE → DERIVED → CONSTANT → UNRESOLVED
```

`AGGREGATE` 还必须排在「`sources` 为空 → `CONSTANT`」之前，因为 `count(*)` 一个源列都没有。

**2. `CAST` / `Alias` 不算「变换」**

analyzer 会给每次写入补一层「对齐到目标表类型」的 `Project`：`INSERT INTO t SELECT a FROM s`
里 `t.x` 那个位置上是 `CAST(s.a AS INT) AS x`。若严格按「不是裸属性就是 `DERIVED`」判，
**几乎所有真实边都会变成 `DERIVED`**，图上再也看不出哪几列是抄过去的。落地按：
剥掉 `Alias` / `Cast` 后只剩一个属性 → `DIRECT`。真正的变换里仍有 `Add` 之类的运算，
剥完还是 `DERIVED`。

**3. `CommandResult#commandLogicalPlan` 不需要反射（§5.4 说「反射取」）**

`javap` 核对 3.5.9 与 4.1.2：两个版本上它都是 **public** 方法，直接调用即可。
`PlanFieldsTests` 把这个事实钉住了——将来某版本把它改小，用例先红。

**4. `InMemoryRelation` 背后的逻辑计划在 3.5 上取不到**

原稿说「反射 12 个访问器」。实际只需两处：`InMemoryRelation` 的 `output`（读不到再退回反射），
以及它背后的逻辑计划——4.1 在 `CachedRDDBuilder.logicalPlan`（private，反射可取），
3.5 的 `cacheBuilder.cachedPlan` 已经是 `SparkPlan`、`logicalLink` 已删，**取不到就返回 `None`**。
后果：3.5 上 `df.cache()` 之后的写入，血缘会在缓存那一层断掉（少一层，不是错一层）。

**5. 列清单在 `analyzed` 里已经被展开**

`INSERT INTO t (x, z) SELECT ...` 分析之后，`userSpecifiedCols` / `outputColumnNames` 都是空，
取而代之的是 `Project` 里多出一列 `CAST(NULL AS INT) AS y`。所以上报的是**全部目标列**，
没写的那几列是 `CONSTANT` + 表达式 `CAST(NULL AS INT)`。

不按 SQL 原文只报 x、z 是刻意的：这一趟确实把 `y` 写成了 NULL，漏报它会让历史上那条
`y ← 某列` 的边看起来还活着。表达式文本也让「这列为什么是常量」在图上自解释。

**6. 扩展式注册只能在「第一次解析 SQL」时做（原稿说 `injectParser` 工厂里就能注册）**

`injectParser` 的工厂由 `BaseSessionStateBuilder#sqlParser` 调用，也就是 `sessionState` 正在
构建的时刻。而：

- 读 `session.sessionState.conf` 会重新进入构建流程；
- 连 `session.listenerManager` 也不行——它内部就是 `sessionState.listenerManager`。

于是 `sessionState → buildParser → 工厂 → listenerManager → sessionState` 成环，表现为
`StackOverflowError`，栈顶落在 `ClassLoader.defineClass1` 上，看起来像类加载问题。**两次**都栽在
这里：先以为是配置读早了（改成只读 `SparkConf`），仍然溢出，才发现注册本身就要 `sessionState`。

落地做法：注册挪到 `AbstractLakehouseRestSqlParser#armListener`——会话第一次 `parsePlan` 时调
`registerIfEnabled`。那时会话状态已建好，且**它一定早于第一条语句的执行**，所以第一条语句的
`onSuccess` 也能被采到。

两条推论，都要记住：

1. `enabled` 只能写在 `SparkConf`（`--conf` / `.config(...)`），`spark.conf.set` 改不动它；
2. **纯 DataFrame 作业不会被自动挂载**（一次 `parsePlan` 都不走）——那种场景用
   `spark.sql.queryExecutionListeners`，也就是 §10.2 里本来就推荐的方式。

**7. `max-columns` 是本地先截**

超上限时客户端就不发列级了（而不是发过去让服务端截）。理由很实际：宽表 2000 列 × 5 源表
的报文要先序列化、走网络、再被服务端丢掉，代价全在客户端这边。服务端仍会独立判一次
（防的是别的客户端）。

**8. `analyzed` 里的写命令比 §5.4 列的多**

V1 表的 `INSERT` 不是 `AppendData`，而是 `InsertIntoHadoopFsRelationCommand`；
V1 的 CTAS 是 `CreateDataSourceTableAsSelectCommand`（不是 `CreateTableAsSelect`）；
多路插入（`FROM s INSERT INTO a ... INSERT INTO b ...`）是一个 **`Union` 套着两个写命令**，
不是「`InsertIntoStatement` 套 `InsertIntoStatement`」。`writeOf` 因此认十种节点 + `Union`，
`isWritePlan` 同步（解析阶段的 CTAS 还是 `CreateTableAsSelectStatement`，与解析后不同名，
按类名补了一条）。

**9. 子查询的表级依赖要靠 `plan.expressions` 找**

`Filter#condition` 里的 `EXISTS (...)` / `IN (SELECT ...)` **不是 `children`** 的一员。
只走 `children` 会让右表彻底消失，可它确实让「结果可能变」。落地在 `walk` 的入口统一扫
`plan.expressions` 里的 `SubqueryExpression`，用**空 parent** 下去：叶子登记表级 `inputs`，
产生不出列级边——正好是 §5.7 要的「有表级、无列级」。

**10. SQL 文本旁路要按「计划实例」关联，不能按线程（§2.4 / §5.1）**

原稿的 `ThreadLocal` 方案基于一个错误前提：`spark.sql(...)` 解析完就地执行、同线程。实际上
`QueryExecutionListener` 的回调由 `ExecutionListenerManager` 投递到
**`spark-listener-group-shared`** 线程——解析在 `main`，取文本在监听线程，`ThreadLocal` 永远读到
空。症状极具误导性：图完全正确，只有 `sqlText`（和 `sqlHash`）是空的，看上去像服务端没存。

改成 `IdentityHashMap[LogicalPlan, String]`，键是 `spark.sql` 交给 `QueryExecution` 的那个对象
（监听器里就是 `qe.logical`）。键必须按身份——`LogicalPlan#equals` 会做结构比较，既慢，又会把两条
一模一样但先后执行的 SQL 合成一条。容量上限 64，超了整体清空：宁可丢几条文本，也不要让它无限攒。

诊断方法也记一下：在 `record` 与 `onSuccess` 各打一行线程名 + 计划身份，一眼就能看出「记录」与
「取」不在同一个键上——这比盯着服务端日志猜快得多。

**11. 一次写入会回调两次，就地那次不能直接跳过（§5.1）**

`INSERT INTO` 会产生两次 `onSuccess`：

| | `qe.logical` | `qe.analyzed` | `funcName` | SQL 文本 |
| --- | --- | --- | --- | --- |
| 就地执行（Dataset 创建期） | `AppendData` | 同一个实例 | `append` | 无（键对不上） |
| 语句级（用户的 action） | `InsertIntoStatement` | `AppendData` | `collect` | 有 |

内层来自 `QueryExecution#eagerlyExecuteCommands`：`spark.sql(...)` 创建 Dataset 时就把命令就地
执行掉，另走一次 `SQLExecution.withNewExecutionId`，于是再回调一次。不去重的后果是运行历史里
每条 `INSERT` 两条、边上 `run_count` 翻倍。

**但去重不能是「跳过就地那次」。**第一版就是这么写的（判据 `qe.logical eq qe.analyzed`，
就地拿到的是分析后计划里的命令节点，两个字段是同一个实例），线上立刻翻车：
`spark.sql("INSERT ...")` **不接 `collect()`** 时，就地执行就是唯一一次回调——跳过它，
这次写入的血缘整个丢了，而图、运行历史什么都对，只是少一条。两种情况（后面还有没有
语句级回调）在回调那一刻分不出来。

落地的规矩是「**待定 + 合并 + 兜底**」：

- 就地回调：把 run 先算好**记为待定**（此刻取不到 SQL 文本——旁路的键是解析产物
  `InsertIntoStatement`，就地回调手里只有 `AppendData`），宽限期 2s 内不上报；
- 语句级回调：SQL 文本只有它取得到。到达时撤掉同一次写入的待定（`qe.analyzed` 是同一个
  实例，按身份配对），上报带文本的这条；
- 宽限期过完语句级回调还没来 → 用户就是不接 `collect()`，守护定时器把待定 run 兜底上报
  （`sqlText` 为空——文本旁路确实够不着，可接受的损失）。

已知的不完美：`sql()` 之后隔了很久才 `collect()`（超过宽限期）会出两条 run。宁可偶发重复
也不能丢——血缘丢边是图错了，重复只是运行历史里多一行。

判据本身（`qe.logical eq qe.analyzed`，不看 `funcName`、不看计划形状；纯 DataFrame 的
`df.writeTo(t).append()` 同样成立）不变，变的只是对命中它的回调的处理。e2e 里
`sqlTextSurvivesTheParserSideChannel` 钉住「一条语句一条 run」，
`insertWithoutCollectIsStillReported` 钉住「不接 `collect()` 也有 run」。
