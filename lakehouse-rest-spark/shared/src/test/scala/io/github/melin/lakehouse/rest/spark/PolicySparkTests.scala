package io.github.melin.lakehouse.rest.spark

import java.nio.file.Files

import scala.util.control.NonFatal

import io.github.melin.lakehouse.rest.spark.policy.PolicyEnforcementException
import io.github.melin.lakehouse.rest.spark.support.{SharedTestSession, StubManagementServer}
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

/**
 * 行过滤 / 列脱敏引擎侧的验收矩阵（{@code docs/row-filter-column-mask-design.md} §10.2
 * 里不依赖外部服务的条目；第 17 条「内存 V2 表」的落地）。
 *
 * <p><b>测试装置的形状：</b>一个独立的桩服务端（不与 {@code SharedTestSession.stub} 共用——
 * 它的 responder 会被别的测试类改写），一个由共享会话 {@code newSession()} 派生的子会话：
 * 子会话有自己的 SQLConf，可以在不重建 SparkContext 的前提下打开
 * {@code spark.lakehouse.rest.policy.enforced}。主表建在 Iceberg 的 hadoop 型 catalog
 * （测试 classpath 自带 bundle）——它是真 V2（{@code DataSourceV2Relation}），读路径
 * 全部语义与 DELETE / UPDATE / MERGE 的「写命令目标不套策略」（§5.7）都靠它：
 * 目标若被包进 PolicyRelation，{@code RewriteDeleteFromTable} 一族对
 * DataSourceV2Relation 的匹配就会失败，用例直接红。另有一张 spark_catalog 的
 * {@code USING parquet} 表仅作 INSERT 的写入目标（V1 relation 不在注入面，见 §3.4）。
 *
 * <p>3.5 与 4.1 各跑一遍同一份源码（§10.2 第 12 条）。
 *
 * <p><b>行过滤 AST 刻意带着服务端的外层 coalesce</b>：服务端落库前会包一层
 * {@code coalesce(expr, false)}（保证行过滤不产生 NULL），桩服务端回放下发协议的
 * 原样报文，所以测试里的 AST 与真实链路逐字节同形。
 */
class PolicySparkTests {

  import PolicySparkTests._

  // ------------------------------------------------------------------ 验收 §10.2

  /**
   * 第 1 / 2 / 13 条：{@code SELECT *} 的行被裁、敏感列被脱敏，且**列名 / 顺序 / 类型
   * 不变**；计划里必须有 {@code PolicyRelation}（防 Kyuubi #3186 那种静默放行的唯一判据）。
   */
  @Test
  def selectStarIsFilteredAndMaskedWithSchemaIntact(): Unit = {
    ensureTables()
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull), exempted = false)))

    val df = query(s"SELECT * FROM $icebergTable ORDER BY id")
    // 行过滤：只剩 CN 三行
    assertEquals(Seq(1L, 3L, 4L), idsOf(df), "行过滤应只放行 CN 区")
    // 列脱敏：phone 保留前 4 位、其余按 alphanum 替换；salary 全 NULL
    val rows = df.collect()
    assertEquals("1380nnnnnnn", rows(0).getString(2))
    assertEquals("1370nnnnnnn", rows(1).getString(2))
    assertNull(rows(0).get(4), "salary 应被置空")
    assertNull(rows(1).get(4))
    // name / region 原样
    assertEquals("alice", rows(0).getString(1))
    assertEquals("CN", rows(0).getString(3))
    // schema：列名 / 顺序 / 类型完全不变（§5.6 的透明性要求）
    assertSchemaUnchanged(df)
    // 第 13 条：优化后的计划里必须有 PolicyRelation
    assertPlanContainsPolicyRelation(s"SELECT * FROM $icebergTable")
  }

  /**
   * 第 3 条：行过滤引用被脱敏列时，判定用的是**原值**——先脱敏的话 salary 全 NULL，
   * 过滤结果会是 0 行；按原值判定应剩两行。
   */
  @Test
  def rowFilterSeesOriginalValuesOfMaskedColumns(): Unit = {
    ensureTables()
    usePolicy(Map(icebergRef ->
      policy(rowFilterSalaryGt100,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull), exempted = false)))
    assertEquals(Seq(2L, 3L), idsOf(query(s"SELECT id FROM $icebergTable")),
      "行过滤应基于脱敏前的原值（salary > 100）")
  }

  /** 第 4 条：用户谓词落在被脱敏列上，比较的是**脱敏后的值**（secure choice）。 */
  @Test
  def userPredicateComparesMaskedValues(): Unit = {
    ensureTables()
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull), exempted = false)))
    val rows = query(s"SELECT id FROM $icebergTable WHERE phone = '1380nnnnnnn'").collect()
    assertEquals(1, rows.length, "用脱敏后的值应当命中原手机号 13800000001 那一行")
    assertEquals(1L, rows(0).getLong(0))
  }

  /**
   * 第 6 条：子查询 / CTE / 视图 / 自连接各自生效——注入点在分析之后，
   * 这些形态全部已内联成 DataSourceV2Relation。
   */
  @Test
  def policySurvivesSubqueryCteViewAndSelfJoin(): Unit = {
    ensureTables()
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull), exempted = false)))

    // 子查询
    assertEquals(Seq(1L, 3L, 4L),
      idsOf(query(s"SELECT id FROM $icebergTable WHERE id IN (SELECT id FROM $icebergTable)")))
    // CTE
    assertEquals(Seq(1L, 3L, 4L),
      idsOf(query(s"WITH cte AS (SELECT * FROM $icebergTable) SELECT id FROM cte")))
    // 视图：读的时候按**读者**的身份重新注入（视图存 SQL 文本，读时再分析）
    query(s"CREATE OR REPLACE VIEW $parquetView AS SELECT * FROM $icebergTable")
    assertEquals(Seq(1L, 3L, 4L), idsOf(query(s"SELECT id FROM $parquetView")))
    // 自连接：两个实例各自包一份（不共享，物理层 ReuseExchange 做该做的事）
    assertEquals(Seq(1L, 3L, 4L),
      idsOf(query(s"SELECT a.id FROM $icebergTable a JOIN $icebergTable b ON a.id = b.id")))
  }

  /**
   * 第 5 条：优化器跑完（optimizedPlan）mask 仍在——官方给的五个维度各一条。
   * PolicyRelation 是不透明节点，任何优化规则都动不了它；这些断言在升级 Spark 时
   * 就是附录 A 说的「升级 checklist」。
   */
  @Test
  def optimizerCannotRemoveOrBypassThePolicy(): Unit = {
    ensureTables()
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull), exempted = false)))

    // ① no-op 投影移除 / ③ 谓词下推与重排：用户谓词推不穿 PolicyRelation
    assertPlanContainsPolicyRelation(s"SELECT id FROM $icebergTable WHERE region = 'CN'")
    // ② 列裁剪：只查一列，包装仍在（裁剪发生在 relation 之上）
    assertPlanContainsPolicyRelation(s"SELECT name FROM $icebergTable")
    // ④ 子查询复用（ReuseSubquery 的输入同样带着包装）
    assertPlanContainsPolicyRelation(
      s"SELECT id FROM $icebergTable WHERE id IN (SELECT id FROM $icebergTable WHERE salary = 300)")
    // 聚合之下仍在
    assertPlanContainsPolicyRelation(
      s"SELECT region, count(*) FROM $icebergTable GROUP BY region")
    // ⑤ CACHE TABLE：缓存里存的是脱敏后的数据（§5.4——正确性问题，非泄露）
    query(s"CACHE TABLE $cacheTable AS SELECT * FROM $icebergTable")
    try {
      assertEquals(Seq(1L, 3L, 4L), idsOf(query(s"SELECT id FROM $cacheTable")))
      assertEquals("1380nnnnnnn",
        query(s"SELECT phone FROM $cacheTable WHERE id = 1").collect()(0).getString(0))
    } finally {
      query(s"UNCACHE TABLE IF EXISTS $cacheTable")
    }
  }

  /**
   * 第 7 条：DML 目标表不套策略；第 15 条：mask 引用其它列时不因列裁剪缺列；
   * INSERT INTO ... SELECT 的 source 照常脱敏（用户自选的语义，§5.7）。
   *
   * <p>注意：本用例会改表数据（DELETE / UPDATE），<b>结尾必须恢复原始数据</b>——
   * 同类里的用例按字母序执行，排在后面的 optimizer / policySurvives / exempt
   * 都依赖 {1,2,3,4} 全量行。case_when 的 else 分支在行过滤之下不可见
   * （US 行已被裁掉），所以单独用一份不带行过滤的策略来测。
   */
  @Test
  def dmlTargetsAreUnaffectedAndMaskCanReferenceOtherColumns(): Unit = {
    ensureTables()
    usePolicy(Map(
      icebergRef ->
        policy(rowFilterRegionCN,
          Seq("name" -> maskNameByRegion, "phone" -> MaskPhoneFirst4,
            "salary" -> MaskSalaryNull), exempted = false)))

    // 第 15 条：SELECT name 只投影一列，而 mask 引用 region——按 child 全量 output 展开；
    // 可见行全是 CN，case_when 的 then 分支放行姓名
    val rows = query(s"SELECT name FROM $icebergTable ORDER BY id").collect()
    assertEquals("alice", rows(0).getString(0))
    assertEquals("carol", rows(1).getString(0), "CN 行的姓名应原样（then 分支）")
    // else 分支：换成不带行过滤的策略，US 行可见 → 姓名不可见
    usePolicy(Map(icebergRef ->
      policy(null, Seq("name" -> maskNameByRegion, "phone" -> MaskPhoneFirst4,
        "salary" -> MaskSalaryNull), exempted = false)))
    assertNull(query(s"SELECT name FROM $icebergTable WHERE id = 2").collect()(0).getString(0),
      "US 区的姓名应不可见（case_when 的 else 分支）")
    // 恢复带行过滤的策略
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("name" -> maskNameByRegion, "phone" -> MaskPhoneFirst4,
          "salary" -> MaskSalaryNull), exempted = false)))

    // INSERT INTO t2 SELECT * FROM t：source 被脱敏，写入的是脱敏后的值
    query(s"INSERT INTO $parquetTable2 SELECT * FROM $icebergTable")
    val inserted = query(s"SELECT id, name, phone, region, salary FROM $parquetTable2 ORDER BY id")
    val in2 = inserted.collect()
    assertEquals(Seq(1L, 3L, 4L), in2.map(_.getLong(0)).toSeq, "source 的行过滤应生效")
    assertNull(in2(0).get(4), "source 的 salary 应已置空")
    assertEquals("1370nnnnnnn", in2(1).getString(2), "source 的 phone 应已脱敏")

    // DELETE 的目标不套策略：目标若被包进 PolicyRelation，Iceberg 的行级重写
    // （RewriteDeleteFromTable 要求 DataSourceV2Relation）会直接失败——这就是断言
    query(s"DELETE FROM $icebergTable WHERE id = 4")
    // 读侧仍有行过滤（region='CN'）：原始 {1,2,3,4}，可见 {1,3}；id=4 被删掉说明
    // DELETE 没有被行过滤挡住（若目标被包装，行级重写阶段就会炸）
    assertEquals(Seq(1L, 3L),
      idsOf(query(s"SELECT id FROM $icebergTable")), "DELETE 成功且读侧行过滤仍生效")
    // UPDATE 同理：目标不套策略
    query(s"UPDATE $icebergTable SET salary = 999 WHERE id = 1")
    // MERGE：目标不套（若被包装，RewriteMergeIntoTable 直接失败）、source（同表）照常套。
    // 用 NOT MATCHED：所有 source 行都能命中目标 → 不产生写入，验证规划形状而不弄脏数据
    // （source 带 mask 的话 WHEN MATCHED UPDATE SET * 会把脱敏值写回目标表）
    query(s"MERGE INTO $icebergTable t USING $icebergTable s ON t.id = s.id" +
      " WHEN NOT MATCHED THEN INSERT *")
    // UPDATE 后读：id=1 仍在（CN），salary 被 mask 盖住
    val merged = query(s"SELECT id, salary FROM $icebergTable WHERE id = 1").collect()
    assertEquals(1, merged.length)
    assertNull(merged(0).get(1))

    // 恢复原始数据：optimizer / policySurvives / exempt 依赖 {1,2,3,4} 全量行与原始值。
    // 校验用无强制的会话读——带行过滤的策略会把 US 行（id=2）在读侧裁掉
    query(s"UPDATE $icebergTable SET salary = 100 WHERE id = 1")
    query(s"INSERT INTO $icebergTable VALUES (4, 'dave', '13600000004', 'CN', 50)")
    assertEquals(Seq(1L, 2L, 3L, 4L),
      plainSession.sql(s"SELECT id FROM $icebergTable").collect().map(_.getLong(0)).toSeq.sorted,
      "数据应恢复到原始状态（后续用例依赖）")
    // §5.7 的硬约束：UPDATE 的目标读取绝不能套策略——套了就会把脱敏值写回表里，
    // 数据被永久污染（phone 被脱敏一遍成 n…，恢复 UPDATE 再脱一遍成 x…）。
    // 这条断言在 Spark 4.x 上尤其关键：UPDATE 在分析期就被改写成 RowLevelWrite 节点，
    // 目标豁免稍有不严就会静默写脏
    assertEquals("13800000001",
      plainSession.sql(s"SELECT phone FROM $icebergTable WHERE id = 1").collect()(0).getString(0),
      "UPDATE 的目标读取被套了策略：脱敏值被写回了表（§5.7 目标豁免失守）")
  }

  /**
   * 第 8 条：策略服务不可达 → 读**失败**（fail-closed），不是返回明文；
   * 打开 fail-open 后按「无策略」放行并可用。
   */
  @Test
  def policyServiceOutageFailsClosedUnlessFailOpen(): Unit = {
    ensureTables()
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull), exempted = false)))
    // 先确认正常路径
    assertEquals(Seq(1L, 3L, 4L), idsOf(query(s"SELECT id FROM $icebergTable")))

    // 5xx → fail-closed：查询失败，报错能照做
    respondError(500, """{"message":"policy store is down"}""")
    val e = assertThrows(classOf[PolicyEnforcementException],
      () => query(s"SELECT id FROM $icebergTable").collect())
    assertTrue(e.getMessage.contains("failing closed"),
      "fail-closed 的报错要说明为什么拒读: " + e.getMessage)

    // fail-open：显式开关打开后放行（并按无策略处理——返回原始数据）
    session.conf.set("spark.lakehouse.rest.policy.fail-open", "true")
    try {
      assertEquals(Seq(1L, 2L, 3L, 4L), idsOf(query(s"SELECT id FROM $icebergTable")),
        "fail-open 放行的是原始数据")
    } finally {
      session.conf.set("spark.lakehouse.rest.policy.fail-open", "false")
    }
  }

  /** 第 11 条：豁免主体读到明文（同一张表，只换令牌）。 */
  @Test
  def exemptPrincipalReadsRawData(): Unit = {
    ensureTables()
    // 豁免在服务端判定（EXEMPT），引擎按无策略处理——本用例只回 EXEMPT
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull),
        exempted = true, status = "EXEMPT")))
    val df = exemptSession.sql(s"SELECT * FROM $icebergTable ORDER BY id")
    assertEquals(Seq(1L, 2L, 3L, 4L),
      df.collect().map(_.getLong(0)).toSeq, "豁免主体不被行过滤裁剪")
    assertEquals("13800000001",
      exemptSession.sql(s"SELECT phone FROM $icebergTable WHERE id = 1")
        .collect()(0).getString(0), "豁免主体读到原始值")
    assertSchemaUnchanged(df)
  }

  /**
   * 第 9 条的「关缓存」测法：TTL=0 时每次分析都拉最新策略——改完策略立刻生效；
   * 顺带验证 NONE（没策略）的表原样放行、计划里没有 PolicyRelation。
   */
  @Test
  def noPolicyMeansNoWrapping(): Unit = {
    ensureTables()
    usePolicy(Map.empty)
    val explain = query(s"EXPLAIN EXTENDED SELECT * FROM $icebergTable").head().getString(0)
    assertFalse(explain.contains("PolicyRelation"), "没策略的表不应出现 PolicyRelation")
    assertEquals(Seq(1L, 2L, 3L, 4L), idsOf(query(s"SELECT id FROM $icebergTable")))

    // 拉取失败在「没策略」语境下同样 fail-closed：拿不到 != 没策略（§5.4 的失败模型）
    respondError(503, """{"message":"down"}""")
    assertThrows(classOf[PolicyEnforcementException],
      () => query(s"SELECT id FROM $icebergTable").collect())
  }

  /** 扩展默认关闭：enforced=false 的会话（本仓其它测试的形态）完全不受影响。 */
  @Test
  def enforcementDefaultsToOff(): Unit = {
    ensureTables()
    usePolicy(Map(icebergRef ->
      policy(rowFilterRegionCN,
        Seq("phone" -> MaskPhoneFirst4, "salary" -> MaskSalaryNull), exempted = false)))
    val plain = plainSession
    val explain = plain.sql(s"EXPLAIN EXTENDED SELECT * FROM $icebergTable").head().getString(0)
    assertFalse(explain.contains("PolicyRelation"), "默认不开强制")
    assertEquals(Seq(1L, 2L, 3L, 4L),
      plain.sql(s"SELECT id FROM $icebergTable").collect().map(_.getLong(0)).toSeq.sorted)
  }

  // ------------------------------------------------------------------ 断言辅助

  private def query(sql: String): DataFrame = session.sql(sql)

  private def idsOf(df: DataFrame): Seq[Long] =
    df.collect().map(_.getLong(0)).toSeq.sorted

  private def assertPlanContainsPolicyRelation(sql: String): Unit = {
    val df = query(sql)
    val optimized = df.queryExecution.optimizedPlan.toString
    assertTrue(optimized.contains("PolicyRelation"),
      s"优化后的计划里必须有 PolicyRelation（§10.2 第 13 条）: $optimized")
  }

  private def assertSchemaUnchanged(df: DataFrame): Unit = {
    val names = df.schema.fields.map(_.name).toSeq
    assertEquals(Seq("id", "name", "phone", "region", "salary"), names)
    val types = df.schema.fields.map(_.dataType.simpleString).toSeq
    assertEquals(Seq("bigint", "string", "string", "string", "bigint"), types)
  }
}

/** 测试环境：独立桩服务端 + 两个身份的强制会话 + parquet / iceberg 各一张数据表。 */
object PolicySparkTests {

  // ------------------------------------------------------------------ 名字

  private val runId = System.nanoTime().toString.takeRight(8)

  private val parquetTable2 = s"pt2$runId"  // 仅作 INSERT 的写入目标（spark_catalog 的 parquet 表是 V1，不在注入面）
  private val parquetView = s"pv$runId"
  private val cacheTable = s"pc$runId"

  /** Iceberg 的 hadoop 型 catalog（测试 classpath 自带 iceberg-spark-runtime）。 */
  private val icebergCatalog = s"pol_ic$runId"
  private val icebergNamespace = "pol_db"
  val icebergTable = s"$icebergCatalog.$icebergNamespace.dmlt$runId"

  /**
   * 下发协议里 tables 参数的形状：db.table 两段（catalog 由查询参数单独给出，
   * 见 {@code PolicyFetcher#fetchAndFill}）。桩的 responder 与用例的策略键都用它。
   */
  private val icebergRef = s"$icebergNamespace.dmlt$runId"

  private val UserToken = "policy-user-token"
  private val ExemptToken = "policy-exempt-token"

  // ------------------------------------------------------------------ 数据与策略

  /** (id, name, phone, region, salary)。CN 三行、US 一行。 */
  private val Rows: Seq[(Long, String, String, String, Long)] = Seq(
    (1L, "alice", "13800000001", "CN", 100L),
    (2L, "bob", "13900000002", "US", 200L),
    (3L, "carol", "13700000003", "CN", 300L),
    (4L, "dave", "13600000004", "CN", 50L),
  )

  /** 行过滤：region = 'CN'，外层按服务端落库形状包 coalesce。 */
  private def rowFilterRegionCN: String =
    """{"fn":"coalesce","args":[{"op":"eq","left":{"col":"region"},"right":{"lit":"CN"}},{"lit":false}]}"""

  /** 行过滤：salary > 100（引用被脱敏列，验证「行过滤看原值」，§10.2 第 3 条）。 */
  private def rowFilterSalaryGt100: String =
    """{"fn":"coalesce","args":[{"op":"gt","left":{"col":"salary"},"right":{"lit":100}},{"lit":false}]}"""

  /** mask：手机号保留前 4 位，其余按 alphanum 替换。 */
  private val MaskPhoneFirst4 = """{"fn":"show_first_n","args":[4]}"""

  /** mask：整列置空（落在 BIGINT 列上——置空后类型不变）。 */
  private val MaskSalaryNull = """{"fn":"replace_with_null"}"""

  /**
   * mask 逃生口：非中国区的姓名不可见（§5.3.5 的单元格级语义），同时引用了
   * region 列——验证「mask 参数引用其它列时按全量 output 展开」（§10.2 第 15 条）。
   */
  private def maskNameByRegion: String =
    """{"expr":{"fn":"case_when","branches":[{"when":{"op":"eq","left":{"col":"region"},"right":{"lit":"CN"}},"then":{"col":"name"}}],"else":{"fn":"cast","operand":{"lit":null},"to":"STRING"}}}"""

  /** 下发协议里一条策略的 JSON（{@code ResolvedPolicy} 的形状）。 */
  private def policy(rowFilter: String, masks: Seq[(String, String)],
                     exempted: Boolean, status: String = "FOUND"): String = {
    val masksJson = masks.map { case (column, mask) =>
      s"""{"column":"$column","mask":$mask,"allowNullOnRequired":false}"""
    }.mkString(",")
    s"""{"status":"$status","version":7,""" +
      s""""rowFilter":${if (rowFilter == null) "null" else rowFilter},""" +
      s""""masks":[$masksJson],"exempted":$exempted,"enforcedScope":"engine"}"""
  }

  private val noneJson =
    """{"status":"NONE","version":0,"rowFilter":null,"masks":[],"exempted":false,"enforcedScope":"engine"}"""

  // ------------------------------------------------------------------ 环境

  lazy val stub: StubManagementServer = {
    val server = StubManagementServer.start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop()))
    server
  }

  lazy val session: SparkSession = {
    val s = SharedTestSession.session.newSession()
    s.conf.set(PaimonRestManagement.MANAGEMENT_URL, stub.baseUrl)
    s.conf.set(PaimonRestManagement.TOKEN, UserToken)
    s.conf.set("spark.lakehouse.rest.policy.enforced", "true")
    // TTL=0：用例之间改策略要立即生效（§10.2 第 9 条的「关缓存」测法）
    s.conf.set("spark.lakehouse.rest.policy.cacheTtlSeconds", "0")
    // Iceberg 的 hadoop 型 catalog：DML 目标豁免的载体（parquet 会话目录不支持 DELETE）
    s.conf.set(s"spark.sql.catalog.$icebergCatalog", "org.apache.iceberg.spark.SparkCatalog")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog.type", "hadoop")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog.warehouse", "file://" + warehouseDir.toString)
    s
  }

  /** 所有会话共用的 Iceberg warehouse（路径不同则各自的 catalog 看不到同一张表）。 */
  private lazy val warehouseDir = Files.createTempDirectory("policy-iceberg-wh")

  /** 豁免身份的强制会话：同一套配置，只换令牌（缓存键因此按主体分片）。
    * 配置全部显式再设一遍——不依赖 newSession 的配置继承行为，两个 Spark 版本都稳。 */
  lazy val exemptSession: SparkSession = {
    val s = session.newSession()
    s.conf.set(PaimonRestManagement.MANAGEMENT_URL, stub.baseUrl)
    s.conf.set(PaimonRestManagement.TOKEN, ExemptToken)
    s.conf.set("spark.lakehouse.rest.policy.enforced", "true")
    s.conf.set("spark.lakehouse.rest.policy.cacheTtlSeconds", "0")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog", "org.apache.iceberg.spark.SparkCatalog")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog.type", "hadoop")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog.warehouse", "file://" + warehouseDir.toString)
    s
  }

  /** 默认关强制的会话（enforcementDefaultsToOff 用）：装了扩展但不打开 enforced。 */
  lazy val plainSession: SparkSession = {
    val s = session.newSession()
    s.conf.set(PaimonRestManagement.MANAGEMENT_URL, stub.baseUrl)
    s.conf.set(PaimonRestManagement.TOKEN, UserToken)
    s.conf.set("spark.lakehouse.rest.policy.enforced", "false")
    s.conf.set("spark.lakehouse.rest.policy.cacheTtlSeconds", "0")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog", "org.apache.iceberg.spark.SparkCatalog")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog.type", "hadoop")
    s.conf.set(s"spark.sql.catalog.$icebergCatalog.warehouse", "file://" + warehouseDir.toString)
    s
  }

  private var tablesReady = false

  /** 建表与数据；只做一次（同 JVM 内用例共享）。 */
  def ensureTables(): Unit = synchronized {
    if (!tablesReady) {
      session.sql(s"CREATE DATABASE IF NOT EXISTS $icebergCatalog.$icebergNamespace")
      session.sql(s"CREATE TABLE $icebergTable (id BIGINT, name STRING, phone STRING," +
        " region STRING, salary BIGINT) USING iceberg")
      Rows.foreach { case (id, name, phone, region, salary) =>
        session.sql(
          s"INSERT INTO $icebergTable VALUES ($id, '$name', '$phone', '$region', $salary)")
      }
      // INSERT 的写入目标（V1，不在注入面）
      session.sql(s"CREATE TABLE $parquetTable2 (id BIGINT, name STRING, phone STRING," +
        " region STRING, salary BIGINT) USING parquet")
      tablesReady = true
    }
  }

  /**
   * 桩的 responder：按查询串里的 tables 参数回放策略；没登记的表回 NONE
   * （与真实服务一致：没策略不是错误）。
   */
  def usePolicy(policies: Map[String, String]): Unit = {
    stub.responder = request => {
      if (request.path.endsWith("/policies/resolve")) {
        val params = request.query.split("&")
          .map(_.split("=", 2))
          .map(p => p(0) -> java.net.URLDecoder.decode(p(1), "UTF-8")).toMap
        val tables = params.getOrElse("tables", "").split(",").filter(_.nonEmpty).toSeq
        // 策略体是完整的 {"status":...} 对象，剥掉外层花括号并进 table 字段所在的对象
        val entries = tables.map { t =>
          val body = policies.getOrElse(t, noneJson).stripPrefix("{")
          s"""{"table":"$t",$body"""
        }
        (200, s"""{"principal":"stub","policies":[${entries.mkString(",")}]}""")
      } else {
        (200, "{}")
      }
    }
  }

  def respondError(status: Int, body: String): Unit = {
    stub.responder = _ => (status, body)
  }
}
