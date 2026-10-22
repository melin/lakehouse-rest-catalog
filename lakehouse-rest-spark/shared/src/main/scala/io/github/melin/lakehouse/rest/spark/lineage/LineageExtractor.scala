package io.github.melin.lakehouse.rest.spark.lineage

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.analysis.NamedRelation
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateExpression
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.execution.columnar.InMemoryRelation
import org.apache.spark.sql.execution.command.CreateDataSourceTableAsSelectCommand
import org.apache.spark.sql.execution.datasources.InsertIntoHadoopFsRelationCommand
import org.slf4j.LoggerFactory

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

/**
 * 从 `analyzed` 逻辑计划里抽出「哪一列来自哪一列」（§5）。
 *
 * <p><b>为什么是纯函数。</b>输入计划、输出归一化模型，不做 IO、不查 catalog、不认识 HTTP。
 * 这是整个测试策略的地基：可以喂真跑一遍得到的计划树，不需要集群也不需要服务端（§11.1）。
 * 唯一的会话依赖是 CTE 那一步（`optimizer.execute`），所以构造函数收一个 `SparkSession`
 * ——与参考实现的 `SparkSQLLineageParseHelper` 同一个形状。
 *
 * <p><b>算法：属性集下推 + 叶子回填</b>（§5.2）。自上而下带着一张
 * 「当前层输出属性 → 目标属性集合」的表走计划，每遇到改变属性的节点（`Project` /
 * `Aggregate` / `Window` / `Expand` / `Generate` / `Union`）按自己的规则改写这张表；
 * 走到叶子关系时，把「这一列在哪个表的哪一列」记到对应的目标属性上。中间不改变属性的
 * 节点（`Filter` / `Sort` / `SubqueryAlias` / `Join`）只需按子节点输出过滤一遍。
 *
 * <p>为什么不是「从目标列出发反查表达式」：表达式树里的 `Attribute` 是**当前层**的引用，
 * 反向查要逐层知道定义，等价于把同一件事做两遍；而且遇到 `Union`（新 exprId）与
 * `Expand`（行列转置）根本倒推不回来。
 *
 * <p><b>产出为什么分「表级源」与「列级源」两份</b>（§5.7）：`JOIN` / `WHERE` 里出现的表
 * 不影响任何一列，但它让「结果可能变」——那是表级依赖，不是列级。两者不一致是信息，
 * 不是 bug（反过来的不一致才是）。
 */
final class LineageExtractor(
    session: SparkSession,
    columnLevel: Boolean = true,
    maxColumnsPerTarget: Int = LineageExtractor.DEFAULT_MAX_COLUMNS) {

  import LineageExtractor._

  private val log = LoggerFactory.getLogger(classOf[LineageExtractor])

  /**
   * @param plan       `qe.analyzed()`；容忍外面包一层 `CommandResult`
   * @param sqlText    SQL 文本（DataFrame 路径拿不到；拿不到就不写）
   * @param startedAt  开始时间（毫秒）
   * @param finishedAt 结束时间（毫秒）
   * @return 抽不出东西时返回 `None`（不是写入语句、或计划形状不认识）——**不抛异常**
   */
  def extract(plan: LogicalPlan,
              sqlText: Option[String] = None,
              startedAt: Long = System.currentTimeMillis(),
              finishedAt: Long = System.currentTimeMillis()): Option[LineageRun] = {
    if (plan == null) {
      return None
    }
    try {
      writeOf(unwrap(plan)).map { write =>
        new LineageRun(
          UUID.randomUUID().toString,
          LineageRun.ENGINE_SPARK,
          session.version,
          write.statementType,
          write.writeMode,
          sqlText.map(sha256).orNull,
          sqlText.map(_.take(MAX_SQL_TEXT)).orNull,
          startedAt,
          finishedAt,
          LineageRun.BATCH,
          targetsOf(write, plan),
          write.sources.asJava,
          Map[String, Object]("spark.app.id" -> session.sparkContext.applicationId).asJava)
      }
    } catch {
      case NonFatal(e) =>
        // 抽取失败只影响这一条 run：WARN 一条，绝不让它冒到 spark.sql 上（§6.6）
        log.warn(s"Failed to extract lineage from ${plan.nodeName} plan: $e")
        None
    }
  }

  /**
   * 目标与列级边的最终形状。
   *
   * <p>两处降级都在这里，不在上报时才做：
   * `column-level=false` 是用户主动关掉（宽表省带宽），列数超上限是本地先截
   * （服务端还会再判一次，但那时报文已经传过去了）。
   */
  private def targetsOf(write: Write, plan: LogicalPlan): java.util.List[LineageRun.Target] =
    write.targets.map { target =>
      val columns =
        if (!columnLevel) null
        else if (target.columns.size > maxColumnsPerTarget) {
          log.debug(s"Lineage of ${target.ref} has ${target.columns.size} columns, "
            + s"above the local limit $maxColumnsPerTarget; reporting table level only")
          null
        } else {
          target.columns.asJava
        }
      new LineageRun.Target(target.ref, columns)
    }.asJava

  // ------------------------------------------------------------------ 命令识别

  /**
   * 剥掉 `CommandResult`（`spark.sql("INSERT ...")` 常见），只留下真正的写命令。
   *
   * <p>`commandLogicalPlan` 在 3.5.9 与 4.1.2 上都是 **public**（`javap` 核对过），
   * 因此这里不需要反射——设计稿 §5.4 记的「反射取」在落地时不成立（§13.7）。
   */
  private def unwrap(plan: LogicalPlan): LogicalPlan = plan match {
    case c: CommandResult => unwrap(c.commandLogicalPlan)
    case other => other
  }

  /** 只认节点类型，不认 SQL 前缀：`INSERT` 经 analyzer 之后可能是三种不同节点（§5.1）。 */
  private def writeOf(command: LogicalPlan): Option[Write] = command match {
    case i: InsertIntoStatement =>
      Some(Write(LineageRun.INSERT, writeModeOf(i), multiInsert(i).flatMap(insertTarget)))
    case a: AppendData =>
      insertLike(a.table, a.query, a.isByName, Nil, Map.empty, LineageRun.APPEND)
    case o: OverwriteByExpression =>
      insertLike(o.table, o.query, o.isByName, Nil, Map.empty, LineageRun.OVERWRITE)
    case o: OverwritePartitionsDynamic =>
      insertLike(o.table, o.query, o.isByName, Nil, Map.empty, LineageRun.OVERWRITE)
    case c: CreateTableAsSelect =>
      Some(ctas(LineageNames.refOf(c.tableName, defaultCatalog), c.query))
    case r: ReplaceTableAsSelect =>
      Some(ctas(LineageNames.refOf(r.tableName, defaultCatalog), r.query))
    case c: CreateDataSourceTableAsSelectCommand =>
      // V1 的 CTAS：`spark_catalog` 下建 Parquet/ORC 表走的就是这条（§5.4）
      Some(ctas(LineageNames.refOfCatalogTable(c.table, defaultCatalog, currentDatabase), c.query))
    case m: MergeIntoTable =>
      merge(m)
    case h: InsertIntoHadoopFsRelationCommand =>
      Some(v1Insert(h))
    case u: Union if u.children.nonEmpty =>
      // 多路插入（`FROM s INSERT INTO a ... INSERT INTO b ...`）：`analyzed` 里是
      // Union 套着 N 个写命令，一个分支一个目标（§5.4）。普通
      // `INSERT ... SELECT ... UNION ALL SELECT ...` 的 Union 在 query 内部，
      // 顶层是写命令本身，走不到这里。
      val branches = u.children.map(writeOf)
      if (branches.forall(_.isDefined)) {
        val writes = branches.flatten
        Some(Write(writes.head.statementType, writes.head.writeMode,
          writes.flatMap(_.targets).toList))
      } else {
        None
      }
    case _ => None
  }

  /**
   * 多路插入（`FROM s INSERT INTO t1 ... INSERT INTO t2 ...`）。
   *
   * <p>`analyzed` 里它是 `InsertIntoStatement` 套着另一个，外层只是第一个目标；
   * 沿 `query` 往下剥即可。单目标时循环只转一圈。
   */
  private def multiInsert(root: InsertIntoStatement): List[InsertIntoStatement] = {
    val buffer = mutable.ListBuffer.empty[InsertIntoStatement]
    var current: LogicalPlan = root
    while (current.isInstanceOf[InsertIntoStatement] && buffer.size < MAX_TARGETS) {
      buffer += current.asInstanceOf[InsertIntoStatement]
      current = current.asInstanceOf[InsertIntoStatement].query
    }
    buffer.toList
  }

  private def writeModeOf(insert: InsertIntoStatement): String =
    if (insert.overwrite) LineageRun.OVERWRITE else LineageRun.APPEND

  // ------------------------------------------------------------------ 各命令的目标

  private def insertTarget(insert: InsertIntoStatement): Option[Target] = {
    val ref = LineageNames.refOfRelation(insert.table, defaultCatalog, currentDatabase)
    val staticParts = insert.partitionSpec.collect { case (k, Some(v)) => k -> v }.toMap
    ref.flatMap(r => align(r, insert.table.output, insert.query, insert.userSpecifiedCols.toSeq,
      insert.byName, staticParts))
  }

  private def insertLike(table: NamedRelation, query: LogicalPlan, byName: Boolean,
                         userSpecified: Seq[String], staticParts: Map[String, String],
                         writeMode: String): Option[Write] = {
    val ref = LineageNames.refOfRelation(table, defaultCatalog, currentDatabase)
    val target = ref.flatMap(r => align(r, table.output, query, userSpecified, byName, staticParts))
    Some(Write(LineageRun.INSERT, writeMode, target.toList))
  }

  /**
   * CTAS：目标表刚建好、可能还没进目录，所以 `ref` 由**命令里的标识符**归一化，不查 catalog。
   *
   * <p>列级边按 `query.output` 逐列取：CTAS 的新表列名/列序就是 query 的输出，
   * 没有「目标列对齐」这一步。
   */
  private def ctas(ref: String, query: LogicalPlan): Write = {
    val walker = new Walk
    walker.run(query)
    val columns = query.output.map { attr =>
      column(attr.name, attr.exprId, walker, walker.sourcesOf(attr.exprId))
    }.toList
    Write(LineageRun.CTAS, LineageRun.CREATE, List(Target(ref, columns, walker.inputs.toList)))
  }

  /**
   * `MERGE INTO`：目标列来自多个分支的 assignment，必须合并（§5.4）。
   *
   * <p>与插入不同，这里没有「目标列对齐」这一步——`Assignment#key` 就是目标列。
   * 源列用一张**全局属性表**（叶子关系的每一列）反查，而不是属性集下推：
   * assignment 的右值直接引用源表列，中间没有需要穿过的重命名层。
   *
   * <p>刻意不按 `WHEN MATCHED` / `WHEN NOT MATCHED` 拆成两条边：两者写的是同一张表的
   * 同一列，拆开只会在图上得到两条指向同一处的边（§12 #8）。
   */
  private def merge(m: MergeIntoTable): Option[Write] = {
    LineageNames.refOfRelation(m.targetTable, defaultCatalog, currentDatabase).map { ref =>
      val walker = new Walk
      walker.run(m.sourceTable)
      val attributes = collectAttributes(m.sourceTable)

      val assignments: Seq[Assignment] =
        m.matchedActions.collect { case u: UpdateAction => u.assignments }.flatten ++
          m.notMatchedActions.collect { case i: InsertAction => i.assignments }.flatten

      // 同一个目标列在多个分支里被赋值时后一个覆盖前一个，与执行语义一致
      val byColumn = mutable.LinkedHashMap.empty[String, LineageRun.Column]
      assignments.foreach { assignment =>
        val sources = assignment.value.references.toSeq.flatMap(r => attributes.get(r.exprId)).distinct
        val targetColumn = assignment.key match {
          case a: Attribute => a.name
          case other => other.sql
        }
        byColumn += targetColumn -> columnOf(targetColumn, assignment.value, sources, walker)
      }
      Write(LineageRun.MERGE, LineageRun.UPSERT, List(Target(ref, byColumn.values.toList, walker.inputs.toList)))
    }
  }

  /** V1 的 `saveAsTable` / 裸路径写入。 */
  private def v1Insert(command: InsertIntoHadoopFsRelationCommand): Write = {
    val ref = command.catalogTable
      .map(t => LineageNames.refOfCatalogTable(t, defaultCatalog, currentDatabase))
      .getOrElse(LineageNames.file(command.outputPath.toString))
    val tableAttrs = command.catalogTable
      .map(_.schema.map(f => AttributeReference(f.name, f.dataType, f.nullable)()))
      .getOrElse(command.query.output)
    val writeMode = command.mode match {
      case org.apache.spark.sql.SaveMode.Overwrite => LineageRun.OVERWRITE
      case _ => LineageRun.APPEND
    }
    val target = align(ref, tableAttrs, command.query, command.outputColumnNames, byName = false,
      command.staticPartitions)
    Write(LineageRun.INSERT, writeMode, target.toList)
  }

  // ------------------------------------------------------------------ 目标列对齐

  /**
   * 目标列与 `query` 输出列按引擎规则配对（§5.3），四种形状：
   *
   * <pre>
   * 全列按位置   userSpecifiedCols 空、byName=false   逐下标
   * 指定列清单   userSpecifiedCols 非空               名单去目标表里查，再按位置
   * 按名字       byName=true                          目标列名 ↔ query 的同名列
   * 带静态分区   partitionSpec 有具体值                先落 CONSTANT，不参与对齐
   * </pre>
   *
   * <p>`byName` 是最容易写反的一条：`INSERT INTO t BY NAME SELECT b, a` 里目标 `a` 来自
   * query 的第 2 列。按位置对齐会得到一张完全反了的图。
   */
  private def align(ref: String, tableAttrs: Seq[Attribute], query: LogicalPlan,
                    userSpecified: Seq[String], byName: Boolean,
                    staticPartitions: Map[String, String]): Option[Target] = {
    val walker = new Walk
    walker.run(query)
    val queryOut = query.output

    val staticNames = staticPartitions.keySet
    val candidates: Seq[(String, Option[Attribute])] =
      if (userSpecified.nonEmpty) userSpecified.map(name => name -> tableAttrs.find(_.name == name))
      else tableAttrs.filterNot(a => staticNames.contains(a.name)).map(a => a.name -> Some(a))

    val aligned: Seq[(String, Option[Attribute])] =
      if (byName) candidates.map { case (name, _) => name -> queryOut.find(_.name == name) }
      else candidates.zipWithIndex.map { case ((name, _), index) => name -> queryOut.lift(index) }

    val statics: Seq[(String, Option[Attribute])] =
      tableAttrs.filter(a => staticNames.contains(a.name)).map(a => a.name -> None)

    val columns = (aligned ++ statics).map {
      case (name, Some(attr)) =>
        column(name, attr.exprId, walker, walker.sourcesOf(attr.exprId))
      case (name, None) =>
        // 静态分区，或「目标列多于 query 输出」的那几列：确实被写了，但没有源（§4.5）
        new LineageRun.Column(name, CONSTANT, staticPartitions.get(name).orNull,
          java.util.Collections.emptyList[LineageRun.ColumnSource]())
    }.toList

    Some(Target(ref, columns, walker.inputs.toList))
  }

  // ------------------------------------------------------------------ 列

  /** 插入/CTAS 路径：表达式从目标层登记的记录里取。 */
  private def column(name: String, exprId: ExprId, walker: Walk,
                     sources: Seq[(String, String)]): LineageRun.Column = {
    val expr = walker.expressionOf(exprId)
    val kind = kindOf(name, exprId, expr, sources, walker)
    new LineageRun.Column(name, kind, expr.map(_.sql.take(MAX_EXPRESSION)).orNull,
      sources.map { case (table, columnName) => new LineageRun.ColumnSource(table, columnName) }.asJava)
  }

  /** MERGE 路径：表达式由 assignment 直接给出，没有「目标层记录」可查。 */
  private def columnOf(name: String, expr: Expression, sources: Seq[(String, String)],
                       walker: Walk): LineageRun.Column = {
    val kind =
      if (name.startsWith("__")) UNRESOLVED
      else if (expr.collect { case _: AggregateExpression => true }.nonEmpty) AGGREGATE
      else if (sources.nonEmpty) {
        if (sources.size == 1 && isPassthrough(Some(expr))) DIRECT else DERIVED
      } else if (expr.references.isEmpty) CONSTANT
      else UNRESOLVED
    new LineageRun.Column(name, kind, expr.sql.take(MAX_EXPRESSION),
      sources.map { case (table, columnName) => new LineageRun.ColumnSource(table, columnName) }.asJava)
  }

  /**
   * 五种 `kind`（§5.5）。
   *
   * <p>判定顺序：`DIRECT` → `AGGREGATE` → `DERIVED` → `CONSTANT` → `UNRESOLVED`。
   *
   * <p>注意设计稿 §4.3 / §5.5 的表格把 `DERIVED` 排在 `AGGREGATE` 前面——那样
   * `SUM(x)` 会先命中 `DERIVED`，`AGGREGATE` 永远判不出来。落地按「先判更具体的那个」，
   * 这条偏离记在 §13.7。
   */
  private def kindOf(name: String, exprId: ExprId, expr: Option[Expression],
                     sources: Seq[(String, String)], walker: Walk): String = {
    if (name.startsWith("__")) {
      UNRESOLVED
    } else if (walker.isAggregate(exprId)) {
      // 先判聚合：`count(*)` 一个源列都没有，否则会落成 CONSTANT
      AGGREGATE
    } else if (sources.nonEmpty) {
      if (sources.size == 1 && isPassthrough(expr)) DIRECT else DERIVED
    } else if (walker.isConstantLike(exprId)) {
      // 常量列、静态分区、`current_date()` 之类无引用表达式
      CONSTANT
    } else {
      // 有引用却定位不到源（相关子查询的属性、或走查没能到达的叶子）：显式标「不知道」
      UNRESOLVED
    }
  }

  /**
   * 剥掉 `Alias` / `Cast` 之后只剩一个属性 —— 这一列就是从那一列来的。
   *
   * <p><b>为什么要剥 `Cast`。</b>analyzer 会给每条写入补一层「对齐到目标表类型」的
   * `Project`：`INSERT INTO t SELECT a FROM s` 的计划里，`tx` 那个位置上是
   * `CAST(s.a AS INT) AS x`。若不剥，几乎所有真实边都会变成 `DERIVED`，图上看不出
   * 「哪几列是抄过去的」——这不是更精确，是把噪音当信息。真正的变换
   * （`CAST(a AS BIGINT) + b`）里仍然有 `Add`，剥完还是 `DERIVED`。
   *
   * <p>没有登记到表达式（`None`）也按直传算：走查只会穿过透明节点才留下边，
   * 能留下一条边就说明中间没有别的运算。
   */
  private def isPassthrough(expr: Option[Expression]): Boolean = expr match {
    case None => true
    case Some(_: Attribute) => true
    case Some(a: Alias) => isPassthrough(Some(a.child))
    case Some(c: Cast) => isPassthrough(Some(c.child))
    case Some(_) => false
  }

  // ------------------------------------------------------------------ 属性集下推

  /**
   * 一趟走查：维护「当前层输出属性 → 目标属性集合」的表，走到叶子时回填。
   *
   * <p>键用 `ExprId` 而不是 `Attribute`：`o.user_id` 与 `u.id` 在 `ON` 之后可能同名，
   * 按名字合并会把两条链焊在一起（§5.2 的实现细节 1）。
   *
   * <p>表里的**值**始终是「目标层属性的 exprId」集合：从根出发时是它自己，向下只做
   * 透传与合并，因此不需要在每层重建身份。
   */
  private final class Walk {
    val inputs: mutable.LinkedHashSet[String] = mutable.LinkedHashSet.empty
    private val edges = mutable.Map.empty[ExprId, mutable.LinkedHashSet[(String, String)]]
    private val expressions = mutable.Map.empty[ExprId, Expression]
    /** 沿同一条链**最后**登记的那份表达式：判「这一列到底有没有入参」用它，不是用外层。 */
    private val innermost = mutable.Map.empty[ExprId, Expression]
    private val aggregates = mutable.Set.empty[ExprId]
    private var cteDepth = 0
    private var subqueryDepth = 0

    def run(query: LogicalPlan): Unit =
      walk(query, query.output.map(a => a.exprId -> Set(a.exprId)).toMap)

    def sourcesOf(id: ExprId): Seq[(String, String)] =
      edges.get(id).map(_.toSeq).getOrElse(Nil)

    def expressionOf(id: ExprId): Option[Expression] = expressions.get(id)

    /**
     * 这一列是不是「不需要任何入参」。
     *
     * <p>用**最内层**那份表达式判：`SELECT 42` 的计划是
     * `Project[`42` AS x] → Project[42 AS `42`] → OneRowRelation`，外层那份的 `references`
     * 指向内层别名（非空），只有走到最内层才看得出「底下是字面量」。用外层判会把常量列
     * 判成 `UNRESOLVED`（§13.7）。
     *
     * <p>没有登记过任何表达式时也算「无入参」：这一列要么没走到叶子、要么本就无源，
     * 两种情况都不该谎称「有引用但定位不到」。
     */
    def isConstantLike(id: ExprId): Boolean = innermost.get(id).forall(_.references.isEmpty)

    def isAggregate(id: ExprId): Boolean = aggregates.contains(id)

    private def walk(plan: LogicalPlan, parent: Map[ExprId, Set[ExprId]]): Unit = {
      subqueries(plan)
      plan match {
      case c: CommandResult => walk(c.commandLogicalPlan, parent)
      case p: Project => walk(p.child, descend(p.projectList, p.child.output, parent))
      case a: Aggregate => walk(a.child, descend(a.aggregateExpressions, a.child.output, parent))
      case w: Window => walk(w.child, window(w, parent))
      case e: Expand => walk(e.child, expand(e, parent))
      case g: Generate => walk(g.child, generate(g, parent))
      case u: Union => u.children.foreach(child => walk(child, union(u, child, parent)))
      case cte: WithCTE => expandCte(cte, parent)
      case relation: InMemoryRelation =>
        // 缓存表不展开就断链。3.5 起 InMemoryRelation 不再保留逻辑计划，取不到就放弃这一段
        PlanFields.logicalPlanOf(relation).foreach { inner =>
          walk(inner, filterTo(inner.output, parent))
        }
      case other =>
        val ref = LineageNames.refOfRelation(other, defaultCatalog, currentDatabase)
        ref.foreach(inputs += _)
        if (other.children.isEmpty) {
          fill(other, parent, ref)
        } else {
          other.children.foreach(child => walk(child, filterTo(child.output, parent)))
        }
      }
    }

    /**
     * 子查询里出现的表只算表级依赖（§5.7）。
     *
     * <p><b>`EXISTS` / `IN (SELECT ...)` 的右表不在 `children` 里</b>——它在 `Filter#condition`
     * 这个表达式的内部。只走 `children` 会让右表彻底消失，可是右表确实让「结果可能变」，
     * 那是表级依赖。这里用**空 parent** 下去：叶子照样登记 `inputs`，但产生不出任何列级边
     * ——正是「有表级、无列级」想要的形状。
     *
     * <p>标量子查询（`SELECT (SELECT max(x) FROM u)`）同样只留表级：它的值进了表达式，
     * 而不是某个目标列的自变量，列级上说不清它「属于哪一列」。
     */
    private def subqueries(plan: LogicalPlan): Unit = {
      if (subqueryDepth >= MAX_SUBQUERY_DEPTH) {
        return
      }
      val nested = plan.expressions.flatMap(_.collect { case s: SubqueryExpression => s.plan })
      if (nested.isEmpty) {
        return
      }
      subqueryDepth += 1
      try {
        nested.foreach(sub => walk(sub, Map.empty))
      } finally {
        subqueryDepth -= 1
      }
    }

    /**
     * `Window`：输出 = 子节点输出 + 窗口表达式；前者原样透过，后者按入参列下推。
     *
     * <p>`partitionSpec` / `orderSpec` 里的列只影响「分到哪个窗口」，不流进输出列——
     * 它们因此只出现在表级依赖里。
     */
    private def window(w: Window, parent: Map[ExprId, Set[ExprId]]): Map[ExprId, Set[ExprId]] = {
      val childIds = w.child.output.map(_.exprId).toSet
      val next = passThrough(parent, childIds)
      w.windowExpressions.foreach { e =>
        parent.get(e.exprId).foreach { roots =>
          note(e, roots)
          e.references.filter(r => childIds.contains(r.exprId))
            .foreach(r => next.getOrElseUpdate(r.exprId, mutable.LinkedHashSet.empty) ++= roots)
        }
      }
      freeze(next)
    }

    /**
     * `Expand`：`GROUPING SETS` 的行列转置。
     *
     * <p>输出属性的 exprId 是新的，而每个投影里同一下标对应同一个语义列。因此按下标把
     * **所有**投影的引用并起来——只看第一条会在多组 grouping set 下漏源。
     */
    private def expand(e: Expand, parent: Map[ExprId, Set[ExprId]]): Map[ExprId, Set[ExprId]] = {
      val next = mutable.Map.empty[ExprId, mutable.LinkedHashSet[ExprId]]
      e.output.zipWithIndex.foreach { case (out, index) =>
        parent.get(out.exprId).foreach { roots =>
          e.projections.foreach { projection =>
            projection.lift(index).foreach(_.references.foreach { r =>
              next.getOrElseUpdate(r.exprId, mutable.LinkedHashSet.empty) ++= roots
            })
          }
        }
      }
      freeze(next)
    }

    /** `Generate`：`explode(arr)` 的输出列连回 `arr`。 */
    private def generate(g: Generate, parent: Map[ExprId, Set[ExprId]]): Map[ExprId, Set[ExprId]] = {
      val childIds = g.child.output.map(_.exprId).toSet
      val next = passThrough(parent, childIds)
      val fromGenerator = g.generator.children.flatMap(_.references).filter(r => childIds.contains(r.exprId))
      g.generatorOutput.foreach { out =>
        parent.get(out.exprId).foreach { roots =>
          fromGenerator.foreach(r => next.getOrElseUpdate(r.exprId, mutable.LinkedHashSet.empty) ++= roots)
        }
      }
      freeze(next)
    }

    /** `Union`：按位置 zip（`byName` 时按名字），唯一能处理新 exprId 的办法。 */
    private def union(u: Union, child: LogicalPlan,
                      parent: Map[ExprId, Set[ExprId]]): Map[ExprId, Set[ExprId]] = {
      val pairs =
        if (u.byName) u.output.flatMap(out => child.output.find(_.name == out.name).map(out -> _))
        else u.output.zip(child.output)
      val next = mutable.Map.empty[ExprId, mutable.LinkedHashSet[ExprId]]
      pairs.foreach { case (out, childAttr) =>
        parent.get(out.exprId).foreach { roots =>
          next.getOrElseUpdate(childAttr.exprId, mutable.LinkedHashSet.empty) ++= roots
        }
      }
      freeze(next)
    }

    /**
     * `WithCTE`：`analyzed` 里 CTE 还没内联，跑一次 optimizer 再递归。
     *
     * <p>只对这一个节点优化，不对整棵树预处理——否则就退化成 `optimized`，列裁剪会
     * 删掉真实存在的依赖（§2.2）。优化失败或没有变化时退回走 `plan` 本身：
     * 宁可少一层 CTE 的边，也不要抛异常。
     */
    private def expandCte(cte: WithCTE, parent: Map[ExprId, Set[ExprId]]): Unit = {
      if (cteDepth >= MAX_CTE_DEPTH) {
        walk(cte.plan, filterTo(cte.plan.output, parent))
        return
      }
      cteDepth += 1
      try {
        val optimized = session.sessionState.optimizer.execute(cte)
        val target = if (optimized.fastEquals(cte) || optimized.isInstanceOf[WithCTE]) cte.plan else optimized
        walk(target, filterTo(target.output, parent))
      } catch {
        case NonFatal(_) => walk(cte.plan, filterTo(cte.plan.output, parent))
      } finally {
        cteDepth -= 1
      }
    }

    /** `Project` / `Aggregate` 共用：登记证据，再把引用传给子节点。 */
    private def descend(list: Seq[NamedExpression], childOutput: Seq[Attribute],
                        parent: Map[ExprId, Set[ExprId]]): Map[ExprId, Set[ExprId]] = {
      val childIds = childOutput.map(_.exprId).toSet
      val next = mutable.Map.empty[ExprId, mutable.LinkedHashSet[ExprId]]
      list.foreach { e =>
        parent.get(e.exprId).foreach { roots =>
          note(e, roots)
          e.references.filter(r => childIds.contains(r.exprId)).foreach { r =>
            next.getOrElseUpdate(r.exprId, mutable.LinkedHashSet.empty) ++= roots
          }
        }
      }
      freeze(next)
    }

    /**
     * 登记「目标属性这一列是怎么算出来的」。
     *
     * <p><b>键必须是 `roots`（目标层属性），不能是 `e.exprId`。</b>`roots` 里装的是「流过
     * 这一层、最终到达目标列」的那些 exprId；`e` 只是当前层的某个表达式，它的 exprId
     * 与目标列的 exprId **通常不同**——analyzer 每加一层 `Project` 就换一批 exprId。
     * 早先按 `e.exprId` 登记、又用 `roots.contains(e.exprId)` 当门禁，这个判定恒为假，
     * 于是聚合、常量、直传三类证据一条都记不上，`sum(b)` / `count(*)` / `SELECT 42`
     * 全落成 `UNRESOLVED`（§13.7）。
     *
     * <p>表达式保留**最外层**那一份（`getOrElseUpdate`）：用户写 `SUM(b) AS total`，
     * 到目标层已经被包成 `CAST(x AS INT) AS x`，外层那份才是引擎真正写进去的形态；
     * 而 `aggregates` 是无条件累加的——聚合信息在内层，外层看不出来。`innermost`
     * 反向保留**最后**登记的一份，专门回答「这一列到底有没有入参」。
     */
    private def note(e: NamedExpression, roots: Set[ExprId]): Unit =
      roots.foreach { id =>
        expressions.getOrElseUpdate(id, e)
        innermost.put(id, e)
        if (e.collect { case _: AggregateExpression => true }.nonEmpty) {
          aggregates += id
        }
      }

    /** 叶子回填：`attribute` 这一列在 `ref` 这张表里，流进了 `parent(attribute)` 的目标属性。 */
    private def fill(leaf: LogicalPlan, parent: Map[ExprId, Set[ExprId]], ref: Option[String]): Unit =
      ref.foreach { table =>
        leaf.output.foreach { attribute =>
          parent.get(attribute.exprId).foreach { roots =>
            roots.foreach(id => edges.getOrElseUpdate(id, mutable.LinkedHashSet.empty) += ((table, attribute.name)))
          }
        }
      }

    /** 不改属性的节点：把父表里属于子节点输出的那部分原样传下去。 */
    private def passThrough(parent: Map[ExprId, Set[ExprId]], childIds: Set[ExprId])
        : mutable.Map[ExprId, mutable.LinkedHashSet[ExprId]] = {
      val next = mutable.Map.empty[ExprId, mutable.LinkedHashSet[ExprId]]
      parent.filter { case (id, _) => childIds.contains(id) }
        .foreach { case (id, roots) => next.getOrElseUpdate(id, mutable.LinkedHashSet.empty) ++= roots }
      next
    }

    private def filterTo(attrs: Seq[Attribute], parent: Map[ExprId, Set[ExprId]]): Map[ExprId, Set[ExprId]] = {
      val ids = attrs.map(_.exprId).toSet
      parent.filter { case (id, _) => ids.contains(id) }
    }

    private def freeze(next: mutable.Map[ExprId, mutable.LinkedHashSet[ExprId]]): Map[ExprId, Set[ExprId]] =
      next.map { case (id, roots) => id -> roots.toSet }.toMap
  }

  /**
   * 计划里每一列「在哪个表的哪一列」。
   *
   * <p>只给 `MERGE` 的 assignment 用：那里没有从目标到源的下推路径，右值直接引用源列。
   * 取第一个遇到的（同一 exprId 在整棵树里唯一，重复出现只是同一列被多处引用）。
   */
  private def collectAttributes(plan: LogicalPlan): Map[ExprId, (String, String)] = {
    val result = mutable.Map.empty[ExprId, (String, String)]
    def visit(node: LogicalPlan): Unit = {
      LineageNames.refOfRelation(node, defaultCatalog, currentDatabase).foreach { ref =>
        node.output.foreach(a => result.getOrElseUpdate(a.exprId, (ref, a.name)))
      }
      node.children.foreach(visit)
    }
    visit(plan)
    result.toMap
  }

  private def defaultCatalog: String =
    session.conf.get("spark.sql.defaultCatalog", "spark_catalog")

  private def currentDatabase: String =
    try session.catalog.currentDatabase catch { case NonFatal(_) => "default" }
}

object LineageExtractor {

  /** 列级边的五种 `kind`（§4.3）。取值与服务端的 `KINDS` 一致。 */
  val DIRECT = "DIRECT"
  val DERIVED = "DERIVED"
  val AGGREGATE = "AGGREGATE"
  val CONSTANT = "CONSTANT"
  val UNRESOLVED = "UNRESOLVED"

  /** SQL 文本上限，与服务端 `MAX_SQL_TEXT` 一致。 */
  val MAX_SQL_TEXT = 8192

  /** 表达式文本上限，与服务端 `MAX_EXPRESSION` 一致。 */
  val MAX_EXPRESSION = 512

  /** 单目标列数上限（`spark.paimon.rest.lineage.max-columns`）。 */
  val DEFAULT_MAX_COLUMNS = 2000

  /**
   * 这个计划是不是「写」。
   *
   * <p>只给 SQL 文本旁路用（`SqlTextRegistry`），判断依据是**节点类型**，不是 SQL 前缀：
   * 解析器手里已经有计划了，比文本可靠。不是写就返回 `false`，文本不会被记下来。
   *
   * <p>解析阶段（analyzer 之前）的 CTAS 是 `CreateTableAsSelectStatement` 这种
   * `ParsedStatement`，与解析后的节点不同名，类型匹配不上——按类名补一条。
   */
  def isWritePlan(plan: LogicalPlan): Boolean = plan match {
    case c: CommandResult => isWritePlan(c.commandLogicalPlan)
    case _: InsertIntoStatement => true
    case _: AppendData => true
    case _: OverwriteByExpression => true
    case _: OverwritePartitionsDynamic => true
    case _: CreateTableAsSelect => true
    case _: ReplaceTableAsSelect => true
    case _: MergeIntoTable => true
    case _: CreateDataSourceTableAsSelectCommand => true
    case _: InsertIntoHadoopFsRelationCommand => true
    case u: Union => u.children.nonEmpty && u.children.forall(isWritePlan)
    case other => PARSED_WRITE_STATEMENTS.contains(other.getClass.getSimpleName)
  }

  /** 只存在于**解析阶段**的写语句节点名（analyzer 之后就换成了别的类型）。 */
  private val PARSED_WRITE_STATEMENTS = Set(
    "CreateTableAsSelectStatement",
    "ReplaceTableAsSelectStatement")

  /** 一次语句里最多认几个写目标（多路插入）；防御性上限，正常不会到。 */
  private val MAX_TARGETS = 16

  /** CTE 展开的递归上限：`optimizer.execute` 万一没内联，不至于无限递归。 */
  private val MAX_CTE_DEPTH = 3

  /** 子查询下钻的递归上限：`EXISTS` 套 `EXISTS` 也就两三层，防的是意料之外的环。 */
  private val MAX_SUBQUERY_DEPTH = 5

  /** 一个写目标的产物。 */
  private case class Target(ref: String, columns: List[LineageRun.Column], sources: List[String])

  /** 一次写操作。 */
  private case class Write(statementType: String, writeMode: String, targets: List[Target]) {
    /** 表级源：同一张表在多个目标里出现时只留一条（`distinct` 保住首次顺序）。 */
    def sources: List[LineageRun.Source] =
      targets.flatMap(_.sources).distinct.map(ref => new LineageRun.Source(ref, LineageNames.kindOf(ref)))
  }

  private def sha256(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.digest(value.getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 0xff}%02x").mkString
  }
}
