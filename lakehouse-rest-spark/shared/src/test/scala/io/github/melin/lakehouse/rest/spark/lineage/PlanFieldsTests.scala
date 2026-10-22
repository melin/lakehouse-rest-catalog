package io.github.melin.lakehouse.rest.spark.lineage

import io.github.melin.lakehouse.rest.spark.support.{LineageTestSchema, SharedTestSession}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.columnar.InMemoryRelation
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

/**
 * 反射访问器的单测（§11.3）。
 *
 * <p>反射没有编译期保护：升级 Spark 时**编译不会红、运行时才炸**。因此这里把
 * {@link PlanFields} 的每个访问器都用真实计划钉一遍——升级时先红在这里，而不是在某个
 * 作业的血缘悄悄变空之后。
 *
 * <p>顺带钉住两个「与设计稿不一致、但落地核对过」的事实：`CommandResult#commandLogicalPlan`
 * 是 public（不需要反射）；`InMemoryRelation` 在 3.5 上取不到原始逻辑计划（返回 `None`），
 * 4.1 上取得到。所以下面的断言是「委托关系」而不是「一定有值」。
 */
class PlanFieldsTests {

  private val session: SparkSession = SharedTestSession.session

  @Test
  def privateFieldReadsAPrivateFieldAndReturnsNoneWhenAbsent(): Unit = {
    val relation = cachedRelation()
    val output = PlanFields.privateField(relation, "output")

    assertTrue(output.isDefined, "InMemoryRelation.output 是 private 字段，应该读得到")
    assertTrue(output.get.isInstanceOf[Seq[_]], "读出来的应该是列序列")

    // 字段不存在时返回 None，而不是抛异常：反射失败等价于「这一版没有这个字段」
    assertTrue(PlanFields.privateField(new Object, "noSuchField").isEmpty)
    assertTrue(PlanFields.privateField(null, "output").isEmpty, "null 也要安全")
  }

  @Test
  def logicalPlanOfDelegatesToTheCachedBuildersPrivateField(): Unit = {
    val relation = cachedRelation()
    val fromBuilder = PlanFields.privateField(relation.cacheBuilder, "logicalPlan")
      .collect { case plan: LogicalPlan => plan }

    // 只比「同一个实例」或「都没有」，不做结构比较：缓存关系背后的计划可能自引用，
    // 深度递归的 equals 会栈溢出（这条踩过）
    (fromBuilder, PlanFields.logicalPlanOf(relation)) match {
      case (Some(expected), Some(actual)) =>
        assertTrue(expected eq actual, "logicalPlanOf 就是「读 builder 的私有字段」，不该有别的来源")
      case (None, None) =>
        assertTrue(true, "这一版取不到，两处都返回 None 才是对的")
      case other =>
        fail(s"两处口径不一致：$other")
    }
  }

  @Test
  def commandResultExposesItsPlanThroughAPublicAccessor(): Unit = {
    // 3.5.9 与 4.1.2 的 `commandLogicalPlan` 都是 public（javap 核对过），
    // 因此 extractor 直接调用它——这条用例只是把这个事实写进测试里
    val method = classOf[org.apache.spark.sql.catalyst.plans.logical.CommandResult]
      .getMethods.find(_.getName == "commandLogicalPlan")
    assertTrue(method.isDefined, "CommandResult#commandLogicalPlan 必须存在")
    assertTrue(java.lang.reflect.Modifier.isPublic(method.get.getModifiers),
      "必须是 public：设计稿 §5.4 说它要反射取，落地核对后不需要")
  }

  // ------------------------------------------------------------------ SQL 文本旁路

  @Test
  def sqlTextIsRememberedOnlyForWritePlans(): Unit = {
    val sql = "INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s"

    SqlTextRegistry.clear()
    val insert = analyze(sql)
    SqlTextRegistry.record(sql, insert)
    assertEquals(Some(sql), SqlTextRegistry.take(insert))
    assertEquals(None, SqlTextRegistry.take(insert), "取一次就清空，不会挂到下一条语句上")

    val select = analyze("SELECT * FROM lineage_db.s")
    SqlTextRegistry.record("SELECT * FROM lineage_db.s", select)
    assertEquals(None, SqlTextRegistry.take(select), "读语句不该留下文本")
  }

  /**
   * 旁路按**计划实例**关联，不是按线程、也不是按结构相等。
   *
   * <p>两条都是踩出来的：`QueryExecutionListener` 的回调跑在
   * `spark-listener-group-shared` 上（与解析不同线程，`ThreadLocal` 必空）；
   * 而 `LogicalPlan#equals` 是结构比较，会跟实例无关地把两条一样的 SQL 认成一条。
   */
  @Test
  def sqlTextIsKeyedByPlanIdentityNotByShape(): Unit = {
    val sql = "INSERT INTO lineage_db.t2 (x, y) SELECT a, b FROM lineage_db.s"
    SqlTextRegistry.clear()

    val parsed = analyze(sql)
    val twin = analyze(sql)
    assertNotSame(parsed, twin)

    SqlTextRegistry.record(sql, parsed)
    assertEquals(None, SqlTextRegistry.take(twin), "结构相同但不是同一次解析，不该命中")
    assertEquals(Some(sql), SqlTextRegistry.take(parsed), "同一次解析的那个实例才命中")
  }

  // ------------------------------------------------------------------ 夹具

  /**
   * 一个真的在计划里的 `InMemoryRelation`。
   *
   * <p>取的是 **optimized** 而不是 analyzed：把 `LogicalRelation` 换成分列缓存的
   * `InMemoryRelation` 是优化器 `Substitute` 阶段做的（`CacheManager.useCachedData`），
   * `analyzed` 里根本还没有它。
   */
  private def cachedRelation(): InMemoryRelation = {
    LineageTestSchema.ensure()
    val df = session.table("lineage_db.s").filter("a > 0")
    df.cache()
    df.count()
    val optimized = df.queryExecution.optimizedPlan
    findRelation(optimized).getOrElse(
      fail(s"没有在计划里找到 InMemoryRelation：${optimized.getClass.getSimpleName}").asInstanceOf[InMemoryRelation])
  }

  private def findRelation(plan: LogicalPlan): Option[InMemoryRelation] =
    plan.collectFirst { case r: InMemoryRelation => r }

  private def analyze(sql: String): LogicalPlan = {
    LineageTestSchema.ensure()
    val parsed = session.sessionState.sqlParser.parsePlan(sql)
    session.sessionState.executePlan(parsed).analyzed
  }
}
