package io.github.melin.lakehouse.rest.spark.lineage

import io.github.melin.lakehouse.rest.spark.support.{LineageTestSchema, SharedTestSession}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

import scala.collection.JavaConverters._

/**
 * 抽取器的表格驱动用例矩阵（§11.2）。
 *
 * <p><b>为什么不测内部数据结构。</b>每个用例都是「一段 SQL → 期望的
 * (表级源, 每列的 kind 与源列)」，不碰实现里的 Map。算法重写时用例不该改；
 * 反过来说，如果算法改了用例就得改，说明它在测实现而不是测行为。
 *
 * <p><b>为什么要真跑一遍。</b>计划树是 analyzer 的产物，手搓的树与真实形状差得远
 * （`INSERT` 会被改写成三种节点、`GROUP BY` 会插 `Expand`）。这里用一个 `local[1]`
 * 会话 + Parquet 表，不起集群、不需要服务端；每个用例先执行再分析，保证断言的是
 * 「引擎真的会给出这个计划」。
 *
 * <p><b>没有覆盖 `MERGE`。</b>`MERGE INTO` 需要一个支持行级操作的表（Paimon / Iceberg
 * 这类 V2 表），Parquet 表跑不了；没有服务端就构造不出那种计划。这条留在端到端用例里
 * （`scripts/e2e-lineage.sh`），代码本身在 `LineageExtractor#merge`。
 */
class LineageExtractorTests {

  import LineageExtractorTests._

  private val session: SparkSession = SharedTestSession.session
  private val extractor = new LineageExtractor(session)

  // ------------------------------------------------------------------ 插入：目标列对齐

  @Test
  def insertSelectStarMapsEveryColumnByPosition(): Unit = {
    val run = extract("INSERT INTO lineage_db.t SELECT a, b, c FROM lineage_db.s")

    assertEquals("INSERT", run.statementType)
    assertEquals("APPEND", run.writeMode)
    assertEquals(Seq("spark_catalog.lineage_db.s"), tableSources(run))
    assertEquals(Seq("x", "y", "z"), run.targets.get(0).columns.asScala.map(_.targetColumn).toSeq)

    val columns = columnsOf(run)
    assertEquals("DIRECT", columns("x").kind)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "a"), sourcesOf(columns("x")))
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "c"), sourcesOf(columns("z")))
  }

  /**
   * 列清单在 `analyzed` 里**已经被展开**：analyzer 会补一列 `CAST(NULL AS INT) AS y`，
   * 所以引擎真的写了三列，`y` 是常量 NULL。
   *
   * <p>上报口径跟着引擎走、不跟着 SQL 原文走：这一趟确实把 `y` 写成了 NULL，只报 x、z
   * 会让历史上那条 `y ← 某列` 的边看起来还活着。表达式文本 `CAST(NULL AS INT)` 也让
   * 「为什么这列是常量」在图上自解释（§13.7）。
   */
  @Test
  def insertWithColumnListFillsTheUnlistedColumnsWithNull(): Unit = {
    val run = extract("INSERT INTO lineage_db.t (x, z) SELECT a, c FROM lineage_db.s")

    assertEquals(Seq("x", "y", "z"),
      run.targets.get(0).columns.asScala.map(_.targetColumn).toSeq)

    val columns = columnsOf(run)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "a"), sourcesOf(columns("x")))
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "c"), sourcesOf(columns("z")))
    assertEquals("CONSTANT", columns("y").kind)
    assertTrue(sourcesOf(columns("y")).isEmpty, "没写的那列由 analyzer 补成 NULL")
  }

  /**
   * `BY NAME` 是最容易写反的一条：`SELECT b AS y, a AS x` 里目标 `x` 来自第 2 列。
   * 按位置对齐会得到 x←y、y←x 这种完全反了的图。
   */
  @Test
  def insertByNameMatchesColumnsByNameNotByPosition(): Unit = {
    val run = extract("INSERT INTO lineage_db.bn BY NAME SELECT b AS y, a AS x FROM lineage_db.s")

    val columns = columnsOf(run)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "a"), sourcesOf(columns("x")))
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "b"), sourcesOf(columns("y")))
  }

  @Test
  def staticPartitionBecomesAConstantWithNoSource(): Unit = {
    val run = extract(
      "INSERT OVERWRITE TABLE lineage_db.tp PARTITION (dt='2026-10-20') SELECT a, b FROM lineage_db.s")

    assertEquals("OVERWRITE", run.writeMode)
    val columns = columnsOf(run)
    assertEquals("CONSTANT", columns("dt").kind)
    assertTrue(sourcesOf(columns("dt")).isEmpty, "静态分区没有源列")
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "a"), sourcesOf(columns("x")))
  }

  @Test
  def dynamicPartitionIsParsedLikeANormalColumn(): Unit = {
    val run = extract("INSERT INTO lineage_db.tp PARTITION (dt) SELECT a, b, c FROM lineage_db.s")

    val columns = columnsOf(run)
    assertEquals("DIRECT", columns("dt").kind)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "c"), sourcesOf(columns("dt")))
  }

  // ------------------------------------------------------------------ 变换

  @Test
  def unionZipsPositionallySoOneColumnHasTwoSources(): Unit = {
    val run = extract(
      "INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s " +
        "UNION ALL SELECT a, b FROM lineage_db.u")

    assertEquals(Seq("spark_catalog.lineage_db.s", "spark_catalog.lineage_db.u").sorted, tableSources(run).sorted)
    val x = columnsOf(run)("x")
    assertEquals(Set("spark_catalog.lineage_db.s" -> "a", "spark_catalog.lineage_db.u" -> "a"),
      sourcesOf(x).toSet)
  }

  /** `count(*)` 没有源列，但它确实是聚合写出来的——`kind` 要能说出来。 */
  @Test
  def countStarIsAggregateWithoutColumnSources(): Unit = {
    val run = extract("INSERT INTO lineage_db.one (x) SELECT count(*) FROM lineage_db.s")

    val x = columnsOf(run)("x")
    assertEquals("AGGREGATE", x.kind)
    assertTrue(sourcesOf(x).isEmpty)
    assertEquals(Seq("spark_catalog.lineage_db.s"), tableSources(run))
  }

  @Test
  def sumKeepsItsInputColumnAndIsAggregate(): Unit = {
    val run = extract("INSERT INTO lineage_db.one (x) SELECT sum(b) FROM lineage_db.s")

    val x = columnsOf(run)("x")
    assertEquals("AGGREGATE", x.kind)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "b"), sourcesOf(x))
  }

  /** `EXISTS` 的右表只影响「行」，不影响「列」：表级有它、列级没有它（§5.7）。 */
  @Test
  def leftSemiJoinKeepsTheTableLevelDependencyOnly(): Unit = {
    val run = extract(
      "INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s " +
        "WHERE EXISTS (SELECT 1 FROM lineage_db.u WHERE u.a = s.a)")

    assertEquals(Set("spark_catalog.lineage_db.s", "spark_catalog.lineage_db.u"), tableSources(run).toSet)
    val columnTables = run.targets.get(0).columns.asScala
      .flatMap(_.sources.asScala.map(_.table)).toSet
    assertEquals(Set("spark_catalog.lineage_db.s"), columnTables,
      "u 只出现在 EXISTS 里，不该有列级边")
  }

  @Test
  def cteIsInlinedBeforeWalking(): Unit = {
    val run = extract(
      "WITH cte AS (SELECT a, b FROM lineage_db.s) " +
        "INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM cte")

    assertEquals(Seq("spark_catalog.lineage_db.s"), tableSources(run))
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "a"), sourcesOf(columnsOf(run)("x")))
  }

  @Test
  def subqueryInWhereKeepsTheOuterColumnLineage(): Unit = {
    val run = extract(
      "INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s " +
        "WHERE a IN (SELECT max(a) FROM lineage_db.u)")

    assertEquals(Set("spark_catalog.lineage_db.s", "spark_catalog.lineage_db.u"), tableSources(run).toSet)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "a"), sourcesOf(columnsOf(run)("x")))
  }

  @Test
  def generateConnectsTheExplodedColumnBackToTheArray(): Unit = {
    val run = extract("INSERT INTO lineage_db.one (x) SELECT explode(xs) FROM lineage_db.arr")

    assertEquals(Seq("spark_catalog.lineage_db.arr" -> "xs"), sourcesOf(columnsOf(run)("x")))
  }

  @Test
  def groupingSetsGoThroughExpand(): Unit = {
    val run = extract(
      "INSERT INTO lineage_db.t2 (x, y) SELECT a, sum(b) FROM lineage_db.s " +
        "GROUP BY a GROUPING SETS ((a))")

    val columns = columnsOf(run)
    assertEquals("DIRECT", columns("x").kind)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "a"), sourcesOf(columns("x")))
    assertEquals("AGGREGATE", columns("y").kind)
    assertEquals(Seq("spark_catalog.lineage_db.s" -> "b"), sourcesOf(columns("y")))
  }

  @Test
  def expressionOverTwoColumnsIsDerived(): Unit = {
    val run = extract("INSERT INTO lineage_db.one (x) SELECT CAST(a AS BIGINT) + b FROM lineage_db.s")

    val x = columnsOf(run)("x")
    assertEquals("DERIVED", x.kind)
    assertEquals(Set("spark_catalog.lineage_db.s" -> "a", "spark_catalog.lineage_db.s" -> "b"),
      sourcesOf(x).toSet)
  }

  @Test
  def constantExpressionIsConstant(): Unit = {
    val run = extract("INSERT INTO lineage_db.one (x) SELECT 42")

    val x = columnsOf(run)("x")
    assertEquals("CONSTANT", x.kind)
    assertTrue(sourcesOf(x).isEmpty)
  }

  // ------------------------------------------------------------------ 目标表

  /** 目标表刚建好、可能还不在目录里：只要有标识符就能报（§5.3）。 */
  @Test
  def ctasReportsATargetThatDoesNotExistYet(): Unit = {
    val sql = "CREATE TABLE lineage_db.ctas_t USING parquet AS SELECT a, b, c FROM lineage_db.s"
    execute("DROP TABLE IF EXISTS lineage_db.ctas_t")
    execute(sql)

    val run = extractor.extract(analyze(sql), Some(sql))
    assertTrue(run.isDefined)
    assertEquals("CTAS", run.get.statementType)
    assertEquals("CREATE", run.get.writeMode)
    assertEquals("spark_catalog.lineage_db.ctas_t", run.get.targets.get(0).table)
    assertEquals(Seq("DIRECT", "DIRECT", "DIRECT"),
      run.get.targets.get(0).columns.asScala.map(_.kind).toSeq)
  }

  @Test
  def multiInsertReportsEveryTarget(): Unit = {
    val run = extract(
      "FROM lineage_db.s " +
        "INSERT INTO lineage_db.t2 (x, y) SELECT a, b " +
        "INSERT INTO lineage_db.one (x) SELECT a")

    assertEquals(Set("spark_catalog.lineage_db.t2", "spark_catalog.lineage_db.one"),
      run.targets.asScala.map(_.table).toSet, "多路插入的两个目标都要在")
  }

  /** 临时视图在 `analyzed` 里已经内联，图上看到的是底表——这是 `RESOLVED` 口径的代价（§4.6）。 */
  @Test
  def tempViewIsExpandedToItsBaseTable(): Unit = {
    execute("CREATE OR REPLACE TEMP VIEW lineage_v AS SELECT a, b FROM lineage_db.s")
    val run = extract("INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_v")

    assertEquals(Seq("spark_catalog.lineage_db.s"), tableSources(run),
      "临时视图不该作为节点出现，只留底表")
  }

  // ------------------------------------------------------------------ 不认识的计划

  @Test
  def readOnlyStatementYieldsNoRun(): Unit = {
    assertTrue(extractor.extract(analyze("SELECT * FROM lineage_db.s")).isEmpty)
  }

  @Test
  def ddlWithoutQueryYieldsNoRun(): Unit = {
    execute("DROP TABLE IF EXISTS lineage_db.plain_t")
    val sql = "CREATE TABLE lineage_db.plain_t (x INT) USING parquet"
    assertTrue(extractor.extract(analyze(sql), Some(sql)).isEmpty)
  }

  @Test
  def isWritePlanRecognisesCommandsOnly(): Unit = {
    assertTrue(LineageExtractor.isWritePlan(analyze("INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s")))
    assertFalse(LineageExtractor.isWritePlan(analyze("SELECT * FROM lineage_db.s")))
  }

  // ------------------------------------------------------------------ 降级

  @Test
  def columnLevelCanBeTurnedOffLocally(): Unit = {
    val run = new LineageExtractor(session, columnLevel = false)
      .extract(analyze("INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s"))

    assertTrue(run.isDefined)
    assertNull(run.get.targets.get(0).columns, "关掉列级时 columns 不发，服务端按表级存")
    assertEquals(Seq("spark_catalog.lineage_db.s"), run.get.sources.asScala.map(_.table).toSeq)
  }

  @Test
  def tooManyColumnsIsDowngradedToTableLevel(): Unit = {
    val run = new LineageExtractor(session, columnLevel = true, maxColumnsPerTarget = 1)
      .extract(analyze("INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s"))

    assertNull(run.get.targets.get(0).columns)
  }

  @Test
  def sqlTextAndHashAreCarriedThrough(): Unit = {
    val sql = "INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s"
    val run = extract(sql)

    assertEquals(sql, run.sqlText)
    assertEquals(64, run.sqlHash.length, "sqlHash 是 SHA-256 的十六进制")
    assertEquals("SPARK", run.engine)
    assertNotNull(run.runId)
  }
}

object LineageExtractorTests {

  /** 库与表由 {@link LineageTestSchema} 建：与反射测试共用，不依赖类的执行顺序。 */
  private def session: SparkSession = SharedTestSession.session

  /** 先执行再分析：执行保证表与数据真的存在，分析给出「引擎真会产出」的计划。 */
  private def execute(sql: String): Unit = {
    LineageTestSchema.ensure()
    session.sql(sql).collect()
  }

  private def analyze(sql: String): LogicalPlan = {
    LineageTestSchema.ensure()
    val parsed = session.sessionState.sqlParser.parsePlan(sql)
    session.sessionState.executePlan(parsed).analyzed
  }

  private def extract(sql: String): LineageRun = {
    execute(sql)
    val run = new LineageExtractor(session).extract(analyze(sql), Some(sql))
    assertTrue(run.isDefined, s"应能从这条语句抽出 run: $sql")
    run.get
  }

  private def tableSources(run: LineageRun): Seq[String] =
    run.sources.asScala.map(_.table).toSeq

  private def columnsOf(run: LineageRun): Map[String, LineageRun.Column] =
    run.targets.get(0).columns.asScala.map(c => c.targetColumn -> c).toMap

  private def sourcesOf(column: LineageRun.Column): Seq[(String, String)] =
    column.sources.asScala.map(s => s.table -> s.column).toSeq
}
