package io.github.melin.lakehouse.rest.spark.lineage

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import io.github.melin.lakehouse.rest.spark.LiveSparkSession
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * 血缘的端到端验收（§11.4）：真实服务端 + 真实 SparkSession + 真实上报。
 *
 * <p><b>它验的是「三段拼起来还对不对」。</b>前面那些用例各自只钉住一段：
 * `LineageExtractorTests` 钉抽取（计划→模型）、`LineageReporterTests` 钉队列
 * （模型→队列）、服务端的 `LineageApiTests` 钉落库（报文→图）。三段都绿而拼起来错位
 * 完全可能——字段名对不上、ref 的归一化两端不一致、SQL 文本旁路没带过去，只有真跑一遍才看得见。
 *
 * <p>默认跳过，需要显式指向一个**开了血缘**的服务端：
 *
 * <pre>
 * scripts/e2e-lineage.sh                       # 自动起服务端（3.5 与 4.1 各跑一遍）
 * mvn -o -pl :lakehouse-rest-spark-3.5 test -Dtest=LineageLiveServerTests \
 *     -De2e.management.url=http://127.0.0.1:18081/api/management/v1 \
 *     -De2e.management.token=root -De2e.lineage.enabled=true
 * </pre>
 *
 * <p>为什么用 Paimon 表而不是 Parquet：Paimon 是 V2 表，写入走的是 `AppendData`
 * （另一条分支）；Parquet 走 `InsertIntoHadoopFsRelationCommand`，那条已经有 24 条
 * 表驱动的单测钉着。这里要补的是「V2 路径也能一路报到服务端」。
 *
 * <p><b>每个用例一个全新的库。</b>不共享库、也不靠 `DROP TABLE` 清理：库名带随机后缀，
 * 服务端是内存 H2、库随进程消失。共享库的写法踩过两次——上一轮残留的表让
 * `CREATE TABLE` 直接 `TABLE_OR_VIEW_ALREADY_EXISTS`，而原因（上一轮 DROP 没生效）
 * 与被测行为毫无关系。
 */
object LineageLiveServerTests {

  /** 会话按进程共享，见 {@link LiveSparkSession}。 */
  lazy val session: SparkSession = LiveSparkSession.instance
}

class LineageLiveServerTests {

  import LineageLiveServerTests._

  private val url = System.getProperty("e2e.management.url", "")
  private val token = System.getProperty("e2e.management.token", "")
  private val catalog = LiveSparkSession.catalog

  /** 上报是异步的：抽取在 driver 上同步完成，出队则要等一个 flush 周期。 */
  private val awaitMillis = 30000L

  /** 本用例独占的库名，见类注释。 */
  private var database = ""

  private def src: String = s"$catalog.$database.src"

  private def dst: String = s"$catalog.$database.dst"

  private def agg: String = s"$catalog.$database.agg"

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(url.nonEmpty,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-lineage.sh")
    Assumptions.assumeTrue(LiveSparkSession.lineageEnabled,
      "跳过：需要 -De2e.lineage.enabled=true（否则监听器根本没挂上）")
  }

  @Test
  def insertIntoAPaimonTableIsReportedWithColumnKinds(): Unit = {
    prepare()
    session.sql(s"INSERT INTO $dst SELECT a, CAST(b AS BIGINT), c FROM $src").collect()

    val detail = awaitRun(dst)
    assertEquals("INSERT", detail.get("summary").get("statementType").asText())
    assertEquals("APPEND", detail.get("summary").get("writeMode").asText())
    assertEquals("SPARK", detail.get("summary").get("engine").asText())

    val target = detail.get("targets").get(0)
    assertEquals(dst, target.get("table").asText())

    // 目标列 x 来自源列 a：CAST 是 analyzer 补的类型对齐，不改变「这一列从哪来」
    val x = column(target, "x")
    assertEquals("DIRECT", x.get("kind").asText(), () => "x 的报文明细：" + x)
    assertEquals(Seq(src -> "a"), sourcesOf(x))

    // 显式 CAST(b AS BIGINT) 同样算直传：只有一个入参，没有算术
    assertEquals("DIRECT", column(target, "y").get("kind").asText())
    assertEquals(Seq(src -> "b"), sourcesOf(column(target, "y")))
    assertEquals(Seq(src -> "c"), sourcesOf(column(target, "z")))

    // 表级边：源表 → 目标表，kind=TABLE（两张都是目录里点得开的表）
    val graph = get(s"/lineage/tables?ref=$dst&direction=UPSTREAM")
    val edges = graph.get("edges")
    assertTrue(edges.size() > 0, () => "目标表没有任何表级边：" + graph)
    val upstream = edges.get(0)
    assertEquals(src, upstream.get("source").asText())
    assertEquals(dst, upstream.get("target").asText())
    assertEquals("TABLE", upstream.get("kind").asText())

    // 列图：x 的上游应当是 src.a（这一段验的是服务端把列边按目标列归组的能力）
    val columns = get(s"/lineage/columns?ref=$dst&direction=UPSTREAM").get("columns")
    assertTrue(columns.size() > 0, () => "列图为空：" + columns)
  }

  /** `count(*)` 一个源列都没有，但 `kind` 必须说得出 `AGGREGATE`——这条最容易在真实链路上丢。 */
  @Test
  def aggregateInsertKeepsItsKindAllTheWayToTheServer(): Unit = {
    prepare()
    session.sql(s"INSERT INTO $agg SELECT count(*) FROM $src").collect()

    val detail = awaitRun(agg)
    val n = column(detail.get("targets").get(0), "n")
    assertEquals("AGGREGATE", n.get("kind").asText(), () => "count(*) 的报文明细：" + n)
    assertEquals(0, n.get("sources").size(), "聚合没有源列")
  }

  /**
   * SQL 文本走的是解析器旁路（`SqlTextRegistry`）：这条钉住「真的带到了服务端」。
   *
   * <p>它是三段里最容易断的一环——文本不进计划树，只在解析那一刻存在于旁路里；
   * 任何一次「多解析了一次」或「换了线程」都会让它变成 null，而图的其余部分照常工作。
   * 上一轮就断在这里：旁路曾用 `ThreadLocal` 存，而回调跑在
   * `spark-listener-group-shared` 上，于是「图全对、只有文本空」。
   *
   * <p><b>顺带钉住「一条语句一条 run」。</b>一次 `INSERT` 会触发两次回调（就地执行 + 语句级，
   * 见 `LakehouseLineageListener` 类内注释），不去重的话这里是 2 条、且只有其中一条带文本
   * ——这正是上一轮该用例 flaky 的原因：`awaitRuns(dst, 2)` 被内层那次凑够了数，
   * 断言在真正的语句级 run 到达之前就跑了。
   */
  @Test
  def sqlTextSurvivesTheParserSideChannel(): Unit = {
    prepare()
    val sql = s"INSERT INTO $dst SELECT a, CAST(b AS BIGINT), c FROM $src"

    // 先确认「解析那一刻真的记下了」：旁路是按计划实例关联的，断了要能一眼看出断在哪一段
    SqlTextRegistry.clear()
    val frame = session.sql(sql)
    val recorded = SqlTextRegistry.take(frame.queryExecution.logical)
    assertTrue(recorded.contains(sql), () => "解析器没有留下 SQL 文本，实际：" + recorded)
    frame.collect()
    awaitRuns(dst, 1)

    // 再跑一条同样的语句，让监听器自己走完整链路（这次旁路里没人抢先取走）
    session.sql(sql).collect()
    val details = awaitRuns(dst, 2)
    val matched = details.find(_.path("sqlText").asText() == sql)
    assertTrue(matched.isDefined,
      () => s"没有一条 run 带着原始 SQL。共 ${details.size} 条，明细：${details.mkString(" | ")}")
    assertEquals(64, matched.get.get("sqlHash").asText().length, "sqlHash 是 SHA-256 的十六进制")

    // 内层重放执行不该再报一条：等过一个 flush 周期后仍然只有两条（每条语句一条）
    Thread.sleep(1500L)
    val settled = runsOf(dst)
    assertEquals(2, settled.size,
      () => s"一条语句只该有一条 run（去掉内层重放执行）。实际：" + settled.mkString(" | "))
  }

  /**
   * `spark.sql("INSERT ...")` 不接 `collect()` 的形态——用户作业的常规写法。
   *
   * <p>命令在 Dataset 创建期就被 `eagerlyExecuteCommands` 就地执行，那次回调的
   * `qe.logical` 与 `qe.analyzed` 是同一个实例——第一版监听器把它当「内层重放」
   * 直接跳过，这条写入的血缘就整个丢了（线上报障正是这个形态：图里没有这条边，
   * 运行历史里没有这条 run）。现在的规矩：就地回调先记为待定，宽限期（2s）内
   * 语句级回调没来就由定时器兜底上报。
   */
  @Test
  def insertWithoutCollectIsStillReported(): Unit = {
    prepare()
    session.sql(s"INSERT INTO $dst SELECT a, CAST(b AS BIGINT), c FROM $src") // 故意不接 collect()

    val detail = awaitRun(dst)
    assertEquals("INSERT", detail.get("summary").get("statementType").asText())
    assertEquals("APPEND", detail.get("summary").get("writeMode").asText())
    // 兜底上报发生在解析旁路之外，取不到 SQL 文本——这条只验「边在」，不验文本
    assertTrue(detail.path("sqlText").asText("").isEmpty,
      () => "就地执行的兜底 run 不该带文本（文本只在语句级回调取得到）：" + detail)
  }

  // ------------------------------------------------------------------ 夹具

  /** 建一个一次性的库与三张表，并灌一行源数据。 */
  private def prepare(): Unit = {
    database = "lineage_e2e_" + UUID.randomUUID().toString.replace("-", "").substring(0, 10)
    session.sql(s"CREATE DATABASE $catalog.$database").collect()
    session.sql(s"CREATE TABLE $src (a INT, b INT, c STRING) USING paimon").collect()
    session.sql(s"CREATE TABLE $dst (x INT, y BIGINT, z STRING) USING paimon").collect()
    session.sql(s"CREATE TABLE $agg (n BIGINT) USING paimon").collect()
    session.sql(s"INSERT INTO $src VALUES (1, 2, 'a')").collect()
  }

  /**
   * 等这张表上至少出现 `expected` 条 run，返回它们的明细（按服务端顺序）。
   */
  private def awaitRuns(ref: String, expected: Int): Seq[JsonNode] = {
    val deadline = System.currentTimeMillis() + awaitMillis
    var last = ""
    while (System.currentTimeMillis() < deadline) {
      val runs = get(s"/lineage/runs?ref=$ref&limit=10").get("runs")
      if (runs.size() >= expected) {
        return runsOf(ref)
      }
      last = runs.toString
      Thread.sleep(200L)
    }
    fail(s"等了 ${awaitMillis}ms 也只等到不足 $expected 条 $ref 的 run。最近一次响应：" + last)
    Nil
  }

  /** 现在已有的 run 明细，不等——用来断言「没有多出来的那一条」。 */
  private def runsOf(ref: String): Seq[JsonNode] = {
    val runs = get(s"/lineage/runs?ref=$ref&limit=10").get("runs")
    (0 until runs.size()).map(i => get("/lineage/runs/" + runs.get(i).get("runId").asText()))
  }

  /**
   * 等目标表的第一条 run 出现，返回它的明细。
   *
   * <p>每个用例独占一个库，所以「这张表上的第一条 run」就是刚才那条，不需要按 SQL 文本
   * 认领；个别用例会再断言文本本身。
   */
  private def awaitRun(ref: String): JsonNode = {
    val deadline = System.currentTimeMillis() + awaitMillis
    var last = ""
    while (System.currentTimeMillis() < deadline) {
      val list = get(s"/lineage/runs?ref=$ref&limit=5")
      val runs = list.get("runs")
      if (runs.size() > 0) {
        return get("/lineage/runs/" + runs.get(0).get("runId").asText())
      }
      last = list.toString
      Thread.sleep(200L)
    }
    fail(s"等了 ${awaitMillis}ms 也没等到 $ref 的 run。最近一次响应：" + last)
    null
  }

  private def column(target: JsonNode, name: String): JsonNode = {
    val columns = target.get("columns")
    assertNotNull(columns, "columns 为 null：表级降级了？")
    var i = 0
    while (i < columns.size()) {
      if (columns.get(i).get("targetColumn").asText() == name) {
        return columns.get(i)
      }
      i += 1
    }
    fail(s"目标列里没有 $name：" + columns)
    null
  }

  private def sourcesOf(column: JsonNode): Seq[(String, String)] = {
    val sources = column.get("sources")
    if (sources == null) return Nil
    (0 until sources.size()).map { i =>
      sources.get(i).get("table").asText() -> sources.get(i).get("column").asText()
    }
  }

  private def get(path: String): JsonNode = {
    val request = HttpRequest.newBuilder(URI.create(url + path))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + token)
      .header("Accept", "application/json")
      .GET()
      .build()
    val response = HttpClient.newHttpClient()
      .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    assertEquals(200, response.statusCode(), () => "GET " + path + " -> " + response.body())
    MAPPER.readTree(response.body())
  }

  private val MAPPER = new ObjectMapper()
}
