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
 *       （path / name / length / mtime / atime / owner，见服务端 {@code TableKinds}）。
 *       它的「写入」不走 SQL：对象由存储侧放进目录，查询时现列目录即可见，
 *       两条入口各有一个用例（{@link #objectTableIndexesAnExistingObjectDirectory}、
 *       {@link #objectTableSeesObjectsDroppedIntoItsDirectory}）；</li>
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

  /**
   * Object 表怎么「写入」：数据不由 Paimon 存，索引的是对象目录里已有的东西。
   *
   * <p>这是 Object 表的常规用法——对象早就躺在对象存储里，建表只是把 catalog 的
   * 元数据（权限、血缘、可与结构化数据 join 的入口）指过去。建表时客户端给的
   * {@code path} 就是那个目录，服务端把它当作**表位置**（{@code GetTableResponse.path}）
   * 并按外部表登记。
   *
   * <p>为什么非要「表位置 = 客户端的目录」：Paimon 的 REST 客户端在 load 表时用响应里的
   * 表位置**覆盖** {@code options.path}（{@code RESTCatalog.toTableMetadata} 用的是
   * {@code put}，不是 {@code putIfAbsent}）。所以服务端只把位置留在 options 里是没用的，
   * 引擎读的仍会是目录按模板分配的那个目录——那个目录里只有服务端写的 {@code schema-0}，
   * 一个对象都看不到。这条曾经真的发生过，本用例就是那次修复的回归防线。
   *
   * <p>顺带钉住外部表的两个后果：不往对象目录写 {@code schema-0}（所以列出来的对象集是干净的），
   * 以及删表时不删它的目录（{@code TableMetadataService} 对 {@code external} 恒不处理）。
   */
  @Test
  def objectTableIndexesAnExistingObjectDirectory(): Unit = {
    val table = "photo_index"
    val objectDir = scratchObjectDir()
    putObject(objectDir, "cover.jpg", "0123456789")
    putObject(objectDir, "images/photo.png", "abc")

    sql(s"CREATE TABLE $catalog.$database.$table (id BIGINT) USING paimon " +
      s"TBLPROPERTIES ('type' = 'object-table', 'path' = '$objectDir')")

    // ---- 服务端侧：客户端的目录成了表位置，options 与它一致，并按外部表登记
    val metadata = serverTable(database, table)
    val options = metadata.get("schema").get("options")
    assertEquals(objectDir, metadata.get("path").asText(),
      "客户端给的对象目录应当就是表位置（引擎读的是它）")
    assertEquals(objectDir, options.get("path").asText())
    assertTrue(metadata.get("isExternal").asBoolean(),
      "数据目录由客户端给定 → 按外部表登记，服务端不写它、删表也不删它")

    // ---- Spark 侧：查出来的就是对象目录里那两个对象，相对路径按对象目录算；
    // 没有 schema-0 混进来，也正说明服务端确实没往这个目录写过东西
    val rows = sql(s"SELECT path, name, length FROM $catalog.$database.$table")
      .map(row => row.getString(0) -> (row.getString(1), row.getLong(2)))
      .toMap
    assertEquals(Set("cover.jpg", "images/photo.png"), rows.keySet,
      "应列出对象目录里的全部对象（递归），且不含服务端写的 schema 文件")
    assertEquals(("cover.jpg", 10L), rows("cover.jpg"))
    assertEquals(("photo.png", 3L), rows("images/photo.png"))
  }

  /**
   * Object 表默认 path 时：往表目录里放对象，下一次查询就能看到——没有 refresh 这一步。
   *
   * <p>这条断言钉的是「Object 表读时现列目录」这个实现事实：{@code ObjectTableImpl}
   * 的 scan 只有位置、没有快照，{@code ObjectRead.createReader} 每次执行才去
   * {@code fileIO.listFilesIterative}。因此上游 1.0 时代 Flink 连接器里的
   * {@code CALL sys.refresh_object_table(...)}（那套还需要 {@code object-location} 选项）
   * 在 2.0.0 里已经没有对应物——2.0.0 的所有 paimon jar 里都不存在这两个字符串。
   */
  @Test
  def objectTableSeesObjectsDroppedIntoItsDirectory(): Unit = {
    val table = "inbox"
    sql(s"CREATE TABLE $catalog.$database.$table (id BIGINT) USING paimon " +
      "TBLPROPERTIES ('type' = 'object-table')")
    val location = serverTable(database, table).get("schema").get("options").get("path").asText()

    putObject(location, "report.pdf", "hello")

    // 没有 refresh、没有 COMMIT、也没有重建表——直接再查一次
    val names = sql(s"SELECT name FROM $catalog.$database.$table").map(_.getString(0)).toSet
    assertEquals(Set("schema-0", "report.pdf"), names,
      "新放进去的对象应当立刻可见（顺带钉住服务端把自己写的 schema-0 也列进去这件事）")
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

  /**
   * 往一个目录里放一个对象——这就是「写 Object 表」的全部动作。
   *
   * <p>Object 表不存数据：它的行是读的时候现列目录算出来的。生产里这个动作由存储侧
   * 完成（{@code aws s3 cp}、{@code hdfs dfs -put}、应用自己的 SDK PUT，或 Spark 用
   * 普通文件数据源写成文件）。这里走 Hadoop 的 {@code FileSystem} API，与它们同一条路径，
   * 以便测试在不依赖外部工具的前提下把「放进去就能查到」跑实。
   *
   * <p>本地仓库上刻意换成裸实现：Hadoop 的 {@code LocalFileSystem} 是带校验和的外壳，
   * 用它写文件会顺带落下 {@code .report.pdf.crc} 这样的旁挂文件——真实对象存储不会有，
   * 但 Object 表的查询会把它当成一行列出来。测试不想把这种噪音写进断言。
   */
  private def putObject(location: String, relative: String, content: String): Unit = {
    val target = new org.apache.hadoop.fs.Path(new org.apache.hadoop.fs.Path(location), relative)
    val fileSystem = target.getFileSystem(session.sparkContext.hadoopConfiguration) match {
      case local: org.apache.hadoop.fs.LocalFileSystem => local.getRaw
      case other => other
    }
    if (target.getParent != null) {
      fileSystem.mkdirs(target.getParent)
    }
    val out = fileSystem.create(target, true)
    try {
      out.write(content.getBytes(StandardCharsets.UTF_8))
    } finally {
      out.close()
    }
  }

  /**
   * 在仓库根下造一个空目录，当作「对象存储里已经存在的目录」。
   *
   * <p>仓库根从服务端给表分配的路径反推（{@code <warehouse>/<db>.db/<table>}，
   * 见 {@code paimon.rest.path-template}），于是不必在测试里写死仓库位置，
   * 也不会把对象目录放到仓库外去。
   */
  private def scratchObjectDir(): String = {
    val seed = "seed_for_object_dir"
    sql(s"CREATE TABLE $catalog.$database.$seed (id BIGINT) USING paimon " +
      "TBLPROPERTIES ('type' = 'object-table')")
    val tableDir = new org.apache.hadoop.fs.Path(
      serverTable(database, seed).get("schema").get("options").get("path").asText())
    // 去掉 <table> 与 <db>.db 两级，剩下的就是仓库根（保留 file:// 这类 scheme）
    tableDir.getParent.getParent.toString + "/object-store-" + shortId()
  }

  private def shortId(): String = UUID.randomUUID().toString.replace("-", "").substring(0, 8)

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
