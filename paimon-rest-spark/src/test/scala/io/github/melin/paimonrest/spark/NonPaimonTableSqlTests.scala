package io.github.melin.paimonrest.spark

import com.fasterxml.jackson.databind.JsonNode
import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{AfterEach, Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import scala.collection.JavaConverters._

/**
 * 三种非 Paimon 表（Format / Object / Lance）经 Spark SQL 建表、写入与查询的验收测试。
 *
 * <p>这三种表的建表请求与 Paimon 表走同一条 REST 契约（{@code options.type} 区分），
 * 但能力边界完全不同。Paimon 2.0.0 的 Spark 侧分派（{@code SparkCatalog.loadSparkTable}）：
 *
 * <ul>
 *   <li><b>Format 表</b> → {@code PaimonFormatTable}，capabilities 含
 *       {@code BATCH_READ + BATCH_WRITE}——建、写、读全链路可用，数据落成
 *       {@code file.format} 指定格式的普通文件；</li>
 *   <li><b>Object 表</b> → {@code SparkObjectTable}，只有 {@code BATCH_READ}——
 *       建表与查询可用（查询 = 列举表目录下的对象），写入没有能力、必然在分析阶段被拒；
 *       且服务端建表时会把用户声明的字段替换成六个固定列
 *       （path / name / length / mtime / atime / owner，见服务端 {@code TableKinds}）；</li>
 *   <li><b>Lance 表</b> → {@code SparkLanceTable}，只继承 {@code BaseTable}、
 *       capabilities 为空集——只有建表可行，读写在 Spark 分析阶段就被拒，
 *       根本到不了 Paimon 的 {@code LanceTableImpl}（它自己也是只读壳：
 *       {@code newScan/newRead} 都抛 {@code UnsupportedOperationException}）。</li>
 * </ul>
 *
 * <p>这些边界不是猜测，是 Paimon 源码里写死的；测试把它们逐条钉住：上游一旦
 * 放开某种表的能力（或服务端改了归一化行为），对应的用例会先响，提醒更新
 * {@code docs/catalog-api-endpoints.md} 的表格式章节。
 *
 * <p>运行方式与 {@link PaimonTableDdlTests} 相同：需要真实服务端，默认跳过，
 * 见 {@code scripts/e2e-spark-sql.sh}。断言同样分两层——Spark 侧行为 +
 * 服务端侧 {@code GET /v1/{prefix}/databases/{db}/tables/{table}} 的原始元数据。
 */
class NonPaimonTableSqlTests {

  import NonPaimonTableSqlTests._

  private def catalog: String = LiveSparkSession.catalog

  /** 每个测试独占一个库，避免用例之间互相看见对方的表。 */
  private var database: String = ""

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(LiveSparkSession.isAvailable,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")
    database = "nonpaimon_" + UUID.randomUUID().toString.replace("-", "").substring(0, 12)
    sql(s"CREATE DATABASE $catalog.$database")
  }

  @AfterEach
  def dropDatabase(): Unit = {
    // @BeforeEach 里 assumption 失败时本方法仍会被调用，此时库名还是空串
    if (database == null || database.isEmpty) {
      return
    }
    try {
      sql(s"SHOW TABLES IN $catalog.$database").map(_.getString(1)).foreach { table =>
        sqlQuietly(s"DROP TABLE IF EXISTS $catalog.$database.$table")
      }
    } catch {
      case _: Throwable => ()
    }
    sqlQuietly(s"DROP DATABASE IF EXISTS $catalog.$database")
  }

  // ------------------------------------------------------------------ Format 表

  /**
   * Format 表（CSV）：建表、写入、读回全链路。
   *
   * <p>Format 表是「目录里同格式普通文件的表」，不受快照管理——写入后仓库里
   * 只有 CSV 文件，没有 snapshot 目录。断言分三层：
   *
   * <ol>
   *   <li>INSERT 后 SELECT 能把数据读回来；</li>
   *   <li>Spark 侧 DESCRIBE 的列结构与提交的一致；</li>
   *   <li>服务端侧：{@code type} / {@code file.format} 落库，{@code path}
   *       由服务端按路径模板补齐，主键为空（Format 表禁止主键）。</li>
   * </ol>
   */
  @Test
  def formatTableCreateInsertQueryRoundTrip(): Unit = {
    val table = "csv_events"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "id BIGINT, name STRING) USING paimon " +
      "TBLPROPERTIES ('type' = 'format-table', 'file.format' = 'csv')")

    // ---- 服务端侧：类型、格式与路径落库，主键为空
    val schema = serverTable(database, table).get("schema")
    val options = schema.get("options")
    assertEquals("format-table", options.get("type").asText())
    assertEquals("csv", options.get("file.format").asText())
    assertTrue(options.get("path").asText().endsWith(s"/$database.db/$table"),
      "服务端应按路径模板补 path，实际: " + options.get("path").asText())
    assertEquals(List.empty[String], textList(schema.get("primaryKeys")),
      "Format 表不允许主键")
    assertEquals(List("id", "name"), fieldNames(schema))

    // ---- Spark 侧：结构与提交一致（无注释时 DESCRIBE 回 null 而不是空串）
    val columns = describedColumns(database, table)
    assertEquals(("bigint", null), columns("id"))
    assertEquals(("string", null), columns("name"))

    // ---- 写入并读回
    sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'x')")
    sql(s"INSERT INTO $catalog.$database.$table VALUES (2, 'y')")

    val rows = sql(s"SELECT id, name FROM $catalog.$database.$table ORDER BY id")
    assertEquals(2, rows.length, "两次 INSERT 应各落一行")
    assertEquals(1L, rows(0).getLong(0))
    assertEquals("x", rows(0).getString(1))
    assertEquals(2L, rows(1).getLong(0))
    assertEquals("y", rows(1).getString(1))
  }

  /**
   * Format 表（Parquet）：同一套语义换个格式，确认 {@code file.format}
   * 决定落盘格式而不是被写死成 CSV。
   */
  @Test
  def formatTableParquetCreateInsertQueryRoundTrip(): Unit = {
    val table = "parquet_events"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "id BIGINT, name STRING) USING paimon " +
      "TBLPROPERTIES ('type' = 'format-table', 'file.format' = 'parquet')")

    assertEquals("parquet", serverTable(database, table).get("schema")
      .get("options").get("file.format").asText())

    sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'x')")
    val rows = sql(s"SELECT id, name FROM $catalog.$database.$table")
    assertEquals(1, rows.length)
    assertEquals(1L, rows(0).getLong(0))
    assertEquals("x", rows(0).getString(1))
  }

  // ------------------------------------------------------------------ Object 表

  /**
   * Object 表：用户字段被服务端替换成固定六列，查询列举目录对象，写入被拒。
   *
   * <p>故意声明了两个自己的字段（id / name）——落库的必须是六个固定列，
   * 这才能证明「替换」真的发生了，而不是恰好碰上声明一致。
   */
  @Test
  def objectTableFixedSchemaAndRejectsWrite(): Unit = {
    val table = "objects"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "id BIGINT, name STRING) USING paimon " +
      "TBLPROPERTIES ('type' = 'object-table')")

    // ---- 服务端侧：固定六列 + path 落库
    val schema = serverTable(database, table).get("schema")
    assertEquals("object-table", schema.get("options").get("type").asText())
    assertTrue(schema.get("options").get("path").asText().endsWith(s"/$database.db/$table"),
      "服务端应按路径模板补 path，实际: " + schema.get("options").get("path").asText())
    assertEquals(ObjectTableColumns, fieldNames(schema),
      "用户声明的字段应被替换为 Object 表的固定六列")
    assertEquals(List.empty[String], textList(schema.get("primaryKeys")))
    assertEquals(List.empty[String], textList(schema.get("partitionKeys")))

    // ---- Spark 侧：DESCRIBE 看到的也是固定六列（顺序按名字排序比较——
    // DESCRIBE 断言走 Map，迭代顺序不保证；列序本身由服务端断言钉住）
    assertEquals(ObjectTableColumns.sorted,
      describedColumns(database, table).keySet.toList.sorted)

    // ---- 查询：读的是「列目录里的对象」。服务端建表时把 schema-0 物化进表目录
    //（对齐上游 JdbcCatalog.createObjectTable 的行为），而读路径递归列出表目录下
    // 的所有文件——所以「空」表返回的恰好是这一行元数据文件。这是上游就有的形状，
    // 用真实路径钉住它，防止有人把它悄悄改掉。
    val rows = sql(s"SELECT path, name FROM $catalog.$database.$table")
    assertEquals(1, rows.length, "刚建的 Object 表应只列出 schema 文件自己")
    assertEquals("schema/schema-0", rows(0).getString(0))
    assertEquals("schema-0", rows(0).getString(1))

    // ---- 写入：SparkObjectTable 只有 BATCH_READ，写入在分析阶段被拒
    val error = assertThrows(classOf[Exception], () =>
      sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'x')"))
    assertTrue(error.getMessage.toLowerCase.contains("write"),
      "报错应与写入能力有关: " + error.getMessage)
  }

  // ------------------------------------------------------------------ Lance 表

  /**
   * Lance 表：只有建表可行；读写在 Spark 分析阶段就被拒。
   *
   * <p>{@code SparkLanceTable} 的 capabilities 是空集，Spark 连 scan/write 的
   * 执行计划都构造不出来——这两条断言钉住的是「失败发生在客户端侧」，与
   * 服务端无关。服务端侧只断言建表请求按 Lance 表的语义落库：字段保留、
   * {@code type} 与 {@code path} 正确。
   */
  @Test
  def lanceTableCreateOnly(): Unit = {
    val table = "lance_dataset"
    sql(s"CREATE TABLE $catalog.$database.$table (" +
      "id BIGINT, name STRING) USING paimon " +
      "TBLPROPERTIES ('type' = 'lance-table')")

    // ---- 服务端侧：字段保留（不像 Object 表被替换），type 与 path 落库
    val schema = serverTable(database, table).get("schema")
    assertEquals("lance-table", schema.get("options").get("type").asText())
    assertTrue(schema.get("options").get("path").asText().endsWith(s"/$database.db/$table"),
      "服务端应按路径模板补 path，实际: " + schema.get("options").get("path").asText())
    assertEquals(List("id", "name"), fieldNames(schema))

    // ---- Spark 侧：DESCRIBE 可用（只是元数据，不触发 scan）；无注释时回 null
    val columns = describedColumns(database, table)
    assertEquals(("bigint", null), columns("id"))
    assertEquals(("string", null), columns("name"))

    // ---- 查询与写入都被拒：SparkLanceTable 没有任何能力，报错在分析阶段
    // 就说出来（"does not support batch scan." / "does not support append in batch
    // mode."），计划根本构造不出来，更轮不到 Paimon 的 LanceTableImpl 抛它的
    // UnsupportedOperationException
    val readError = assertThrows(classOf[Exception], () =>
      sql(s"SELECT id FROM $catalog.$database.$table"))
    assertTrue(readError.getMessage.toLowerCase.contains("batch scan"),
      "读失败的报错应指出不支持 batch scan: " + readError.getMessage)

    val writeError = assertThrows(classOf[Exception], () =>
      sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'x')"))
    assertTrue(writeError.getMessage.toLowerCase.contains("append in batch"),
      "写失败的报错应指出不支持 batch 写入: " + writeError.getMessage)
  }

  // ------------------------------------------------------------------ 辅助

  private def session: SparkSession = LiveSparkSession.instance

  private def sql(statement: String): Array[Row] = session.sql(statement).collect()

  private def sqlQuietly(statement: String): Unit =
    try {
      sql(statement)
      ()
    } catch {
      case _: Throwable => ()
    }

  /** `DESCRIBE` 的列名 → (类型, 注释)，丢掉 `# Partition Information` 这类分隔行。 */
  private def describedColumns(db: String, table: String): Map[String, (String, String)] =
    sql(s"DESCRIBE $catalog.$db.$table")
      .filterNot(_.getString(0).startsWith("#"))
      .map(row => row.getString(0) -> (row.getString(1), row.getString(2)))
      .toMap

  /** 直接读服务端的表元数据——只有这一层能证明元数据真的落了库。 */
  private def serverTable(db: String, table: String): JsonNode = {
    val path = s"/v1/${LiveSparkSession.catalog}/databases/$db/tables/$table"
    val request = HttpRequest.newBuilder(URI.create(LiveSparkSession.catalogUrl + path))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + LiveSparkSession.token)
      .header("Accept", "application/json")
      .GET().build()
    val response = HttpClient.newHttpClient()
      .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    assertEquals(200, response.statusCode(), () => "GET " + path + " -> " + response.body())
    MAPPER.readTree(response.body())
  }
}

object NonPaimonTableSqlTests {

  private val MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()

  /** Paimon {@code ObjectTable.SCHEMA} 的六个固定列，与服务端 {@code TableKinds} 镜像。 */
  private val ObjectTableColumns = List("path", "name", "length", "mtime", "atime", "owner")

  private def textList(node: JsonNode): List[String] = node.elements().asScala.map(_.asText()).toList

  private def fieldNames(schema: JsonNode): List[String] =
    schema.get("fields").elements().asScala.map(_.get("name").asText()).toList
}
