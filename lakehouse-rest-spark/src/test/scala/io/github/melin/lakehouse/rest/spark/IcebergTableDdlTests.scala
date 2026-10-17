package io.github.melin.lakehouse.rest.spark

import com.fasterxml.jackson.databind.JsonNode
import org.apache.iceberg.exceptions.NamespaceNotEmptyException
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
 * 「用 Spark SQL 建 Iceberg 表」的验收测试：语句经 Iceberg 自己的 Spark catalog
 * 落到我们的 **Iceberg 协议面**，再回读服务端保存的元数据。
 *
 * <p><b>为什么这一层必须真跑。</b>`docs/spark-iceberg-rest-e2e.md` 第 0 节把验证分成三层：
 * 协议层（`IcebergRestCatalogApiTests`）、客户端协商层（`IcebergClientEndToEndTests`）、
 * 引擎层。前两层早已实测，**引擎层此前只有静态核对**——本文档写的「照 1.2 配好会话就能跑」
 * 那句话没有人验证过。本类补的就是这一层：真的起 Spark、真的跑 SQL、真的读仓库。
 *
 * <p>它与 {@link PaimonTableDdlTests} 是平行的两条链，差异集中在四处：
 *
 * <table>
 *   <tr><th></th><th>Paimon</th><th>Iceberg</th></tr>
 *   <tr><td>catalog 实现</td><td>`SparkCatalog` + `metastore=rest`</td>
 *       <td>`SparkCatalog` + `type=rest`</td></tr>
 *   <tr><td>「库」</td><td>database</td><td>namespace（`CREATE NAMESPACE` / `CREATE DATABASE` 同义）</td></tr>
 *   <tr><td>改表</td><td>catalog 自己的端点</td><td>更新代数：客户端生成 `requirements` + `updates`</td></tr>
 *   <tr><td>服务端元数据</td><td>`schema` / `partitionKeys` / `options`</td>
 *       <td>`metadata.schemas[]` / `partition-specs[]` / `properties`（整份 metadata.json）</td></tr>
 * </table>
 *
 * <p><b>断言分两层</b>（与 Paimon 侧同一条理由）：Spark 侧看 `DESCRIBE` / `SHOW TABLES` /
 * `SHOW CREATE TABLE` 的渲染结果；服务端侧直接 `GET /v1/{prefix}/namespaces/{ns}/tables/{t}`
 * 读原始 JSON。少了第二层，服务端完全可以「建表返回成功但什么都没存」，而第一层照样通过。
 *
 * <p><b>catalog 要先建成 ICEBERG。</b>服务端的 `/v1/config` 是连接阶段的路由，
 * 那时客户端还没说过自己要哪种格式，自动登记会按部署默认值建成 Paimon；一旦如此，
 * 这个 prefix 上每一次 Iceberg 调用都会 404（报文写 `is a PAIMON catalog`）。
 * 因此 {@link LiveSparkSession#ensureIcebergCatalog} 先用管理 API 建好再连。
 *
 * <p>默认跳过，需要显式指向一个已启动的服务端：
 *
 * <pre>
 * scripts/e2e-spark-sql.sh          # 自动起服务端并跑本测试
 * </pre>
 *
 * <p>或手工：
 *
 * <pre>
 * mvn -o -pl :lakehouse-rest-spark test -Dtest=IcebergTableDdlTests \
 *     -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
 *     -De2e.management.token=root
 * </pre>
 */
class IcebergTableDdlTests {

  import IcebergTableDdlTests._

  /** Iceberg 侧的 catalog 名（= 服务端 prefix），由 [[LiveSparkSession]] 统一给出。 */
  private def catalog: String = LiveSparkSession.icebergCatalog

  /** 每个测试独占一个 namespace，避免用例之间互相看见对方的表。 */
  private var namespace: String = ""

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(LiveSparkSession.isAvailable,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")
    // 建 catalog 必须早于这个 catalog 上的第一条语句：客户端第一次用到它时会请求
    // GET /v1/config，而服务端在那一步就可能把它登记成错误的格式（见类注释）。
    LiveSparkSession.ensureIcebergCatalog()
    namespace = "ice_" + UUID.randomUUID().toString.replace("-", "").substring(0, 12)
    sql(s"CREATE NAMESPACE $catalog.$namespace")
  }

  @AfterEach
  def dropNamespace(): Unit = {
    // @BeforeEach 里 assumption 失败时本方法仍会被调用，此时 namespace 还是空串
    if (namespace == null || namespace.isEmpty) {
      return
    }
    try {
      sql(s"SHOW TABLES IN $catalog.$namespace").map(_.getString(1)).foreach { table =>
        sqlQuietly(s"DROP TABLE IF EXISTS $catalog.$namespace.$table")
      }
    } catch {
      case _: Throwable => ()
    }
    // Iceberg 要求 namespace 为空才让删（规格的 409），这里按顺序清干净
    sqlQuietly(s"DROP NAMESPACE IF EXISTS $catalog.$namespace")
  }

  // ------------------------------------------------------------------ 正常路径

  /**
   * 分区表：列类型、列注释、表注释、分区变换、表属性都要原样到达服务端。
   *
   * <p>Iceberg 的分区与 Paimon 不是一套写法：这里写 `PARTITIONED BY (dt)`（identity 变换），
   * 落进元数据是 `partition-specs[].fields[].transform = "identity"`；
   * 换成 `days(ts)` 就是 `"day"`，由 {@link #partitionTransformLandsInTheSpec} 钉住。
   */
  @Test
  def namespaceAndPartitionedTableRoundTripThroughServer(): Unit = {
    val table = "orders"
    sql(s"CREATE TABLE $catalog.$namespace.$table (" +
      "id BIGINT COMMENT '订单号', " +
      "amount DECIMAL(18,2) COMMENT '成交金额', " +
      "dt STRING COMMENT '分区日') " +
      "USING iceberg " +
      "COMMENT '订单明细表' " +
      "PARTITIONED BY (dt) " +
      "TBLPROPERTIES ('custom.e2e' = 'yes')")

    // ---- Spark 侧：客户端渲染出的结构与提交的一致
    val columns = describedColumns(namespace, table)
    assertEquals(("bigint", "订单号"), columns("id"))
    assertEquals(("decimal(18,2)", "成交金额"), columns("amount"))
    assertEquals(("string", "分区日"), columns("dt"))

    val ddl = singleValue(s"SHOW CREATE TABLE $catalog.$namespace.$table")
    assertTrue(ddl.contains("USING iceberg"), ddl)
    assertTrue(ddl.contains("PARTITIONED BY (dt)"), ddl)

    assertTrue(sql(s"SHOW TABLES IN $catalog.$namespace").exists(_.getString(1) == table),
      "SHOW TABLES 应包含刚建的表")

    // ---- 服务端侧：整份 metadata.json 里该有的都在
    val loaded = serverTable(namespace, table)
    val metadata = loaded.get("metadata")
    assertEquals(2, metadata.get("format-version").asInt(),
      "建表默认 format-version 是 2（Iceberg 自己的默认值）")

    val schema = currentSchema(metadata)
    assertEquals(List("id", "amount", "dt"), fieldNames(schema))
    assertEquals("long", typeOf(fieldOf(schema, "id")), "BIGINT 在 Iceberg 元数据里是 long")
    assertEquals("decimal(18, 2)", typeOf(fieldOf(schema, "amount")),
      "decimal 的类型名带精度与标度；空格是 iceberg-core 规范化后的写法")
    assertEquals("订单号", fieldOf(schema, "id").get("doc").asText(), "列注释落在 doc 上")

    assertEquals("订单明细表", metadata.get("properties").get("comment").asText(),
      "表注释是 Iceberg 的 comment 表属性")
    assertEquals("yes", metadata.get("properties").get("custom.e2e").asText(),
      "自定义表属性要原样保留")

    val spec = defaultPartitionSpec(metadata)
    assertEquals(Map("dt" -> "identity"), partitionFields(spec),
      "identity 分区在元数据里写的是 transform=identity")

    // 位置由服务端的 path-template 推，不由客户端自拟；metadata-location 由服务端物化
    assertTrue(metadata.get("location").asText().endsWith(s"/$namespace.db/$table"),
      "位置应遵循服务端模板 <warehouse>/<namespace>.db/<table>，实际: " +
        metadata.get("location").asText())
    assertTrue(loaded.get("metadata-location").asText().startsWith(metadata.get("location").asText()),
      "metadata-location 应落在表目录下: " + loaded.get("metadata-location").asText())
  }

  /** `CREATE DATABASE` 与 `CREATE NAMESPACE` 是同一个命令的两个拼法，两者都能列出。 */
  @Test
  def createDatabaseIsTheSameAsCreateNamespace(): Unit = {
    val another = namespace + "_alias"
    sql(s"CREATE DATABASE $catalog.$another")
    try {
      val namespaces = sql(s"SHOW NAMESPACES IN $catalog").map(_.getString(0))
      assertTrue(namespaces.contains(another), "SHOW NAMESPACES 应包含刚建的库: " + namespaces)
      // 服务端的 namespaces 端点与「库列表」是同一份数据
      assertTrue(serverNamespaces().contains(another),
        "服务端 GET /namespaces 应列出它: " + serverNamespaces())
    } finally {
      sqlQuietly(s"DROP NAMESPACE IF EXISTS $catalog.$another")
    }
  }

  /**
   * 写入链路：`INSERT` 之后能读回，且服务端侧真的多了快照与数据文件。
   *
   * <p>这条链比 Paimon 侧长一截：Spark 自己写 parquet 数据文件 → 客户端生成
   * `requirements` + `updates`（`add-snapshot` / `set-snapshot-ref`）→ 服务端校验
   * 更新代数、应用、**物化一份新的 metadata.json** → 客户端再读回来。
   * 任何一环错位，读到的就不是刚写的那两行。
   */
  @Test
  def insertWritesSnapshotsAndIsReadable(): Unit = {
    val table = "appended"
    sql(s"CREATE TABLE $catalog.$namespace.$table (id BIGINT, data STRING) USING iceberg")

    sql(s"INSERT INTO $catalog.$namespace.$table VALUES (1, 'a'), (2, 'b')")

    val rows = sql(s"SELECT id, data FROM $catalog.$namespace.$table ORDER BY id")
    assertEquals(2, rows.length, "INSERT 之后应读到两行")
    assertEquals(1L, rows(0).getLong(0))
    assertEquals("a", rows(0).getString(1))
    assertEquals(2L, rows(1).getLong(0))

    // 服务端侧：快照与数据文件真的落了库
    val metadata = serverTable(namespace, table).get("metadata")
    assertTrue(metadata.get("current-snapshot-id").asLong > 0,
      "提交之后 current-snapshot-id 应指向一个真实快照，实际: " + metadata.get("current-snapshot-id"))
    assertEquals(1, metadata.get("snapshots").size(), "一次 INSERT = 一个快照")

    val snapshot = metadata.get("snapshots").elements().next()
    val summary = snapshot.get("summary")
    assertEquals("append", summary.get("operation").asText(), "INSERT 的提交操作是 append")
    assertEquals("2", summary.get("total-records").asText(),
      "快照摘要里应记下写了两条记录")
    assertEquals("1", summary.get("added-data-files").asText(),
      "一次 INSERT 应产出恰好一个 parquet 数据文件")
  }

  /**
   * `CREATE TABLE ... AS SELECT` 在当前服务端上**会被拒**，且拒绝信息点出原因。
   *
   * <p>这条不是「已知失败」的托词，而是一个**边界守卫**：Spark 3.5 把 CTAS
   * 翻译成 Iceberg 的 **staged create**（先登记一个未提交的表、写完再提交），
   * 而 staged create 的提交路径是 `POST /transactions/commit` ——
   * 一期没实现的九个端点之一。服务端在这里回的是「指名原因」的 400：
   * `stage-create is not supported by this catalog: multi-table transactions
   * (POST /v1/{prefix}/transactions/commit) are not implemented, so a staged
   * create has no commit path`。
   *
   * <p>换言之：**单表写入（`INSERT`）不受影响，CTAS 受影响**。
   * `docs/spark-iceberg-rest-e2e.md` §5 原先只写了「多表事务不可用，单表写入不受影响」，
   * 本用例把这条补充说清楚。服务端补上 `transactions/commit` 之后，
   * 这里会先响——那时把断言翻成「CTAS 成功」即可（与 Paimon 侧当初那条
   * 写入守卫用例同一个套路）。
   */
  @Test
  def createTableAsSelectIsRejectedWithANamedReason(): Unit = {
    val table = "ctas"
    val error = assertThrows(classOf[Throwable], () =>
      sql(s"CREATE TABLE $catalog.$namespace.$table USING iceberg AS SELECT 1L AS id"))

    val message = chain(error)
    assertTrue(message.contains("stage-create"), () => "拒绝原因应点名 staged create: " + message)
    assertTrue(message.contains("transactions/commit"),
      () => "拒绝原因应指向缺的那个端点: " + message)

    // 失败没有留下半张表
    assertEquals(404, serverTableStatus(namespace, table),
      "CTAS 被拒之后不该留下一张只有元数据的空表")
  }

  // ------------------------------------------------------------------ 更新代数

  /**
   * 改 schema：`ADD COLUMN` 与 `RENAME COLUMN` 都由客户端生成 `updates`、服务端应用后代数 +1。
   *
   * <p>这里同时钉住两件事：Spark 侧 `DESCRIBE` 看到的新列，与服务端
   * `current-schema-id` 真的前进了——只断言前者的话，客户端本地缓存也能让它绿。
   */
  @Test
  def schemaEvolutionAddsAndRenamesColumns(): Unit = {
    val table = "evolving"
    sql(s"CREATE TABLE $catalog.$namespace.$table (id BIGINT, data STRING) USING iceberg")
    val before = serverTable(namespace, table).get("metadata").get("current-schema-id").asInt()

    sql(s"ALTER TABLE $catalog.$namespace.$table ADD COLUMN extra STRING")
    sql(s"ALTER TABLE $catalog.$namespace.$table RENAME COLUMN data TO payload")

    val columns = describedColumns(namespace, table)
    assertEquals(Set("id", "payload", "extra"), columns.keySet)
    assertEquals("string", columns("payload")._1)

    val metadata = serverTable(namespace, table).get("metadata")
    assertTrue(metadata.get("current-schema-id").asInt() > before,
      "两次加列/改名之后 current-schema-id 应当前进")
    val schema = currentSchema(metadata)
    assertEquals(Set("id", "payload", "extra"), fieldNames(schema).toSet,
      "服务端保留的历史 schemas 里，当前那一份应是改名后的")
    assertEquals(3, metadata.get("schemas").size(),
      "建表 1 代 + 加列 1 代 + 改名 1 代；旧的不删，供时间旅行读取")
  }

  /** 分区变换原样落到 `partition-specs`：`days(ts)` 在元数据里是 `day`，不是字符串 `days(ts)`。 */
  @Test
  def partitionTransformLandsInTheSpec(): Unit = {
    val table = "daily"
    sql(s"CREATE TABLE $catalog.$namespace.$table (id BIGINT, ts TIMESTAMP) " +
      "USING iceberg PARTITIONED BY (days(ts))")

    val spec = defaultPartitionSpec(serverTable(namespace, table).get("metadata"))
    assertEquals(Map("ts_day" -> "day"), partitionFields(spec),
      "时间变换要落成 Iceberg 自己的 transform 名称，且分区字段名带变换后缀")
  }

  /**
   * 重命名只改标识、不动位置；旧名字随后 404，新名字能读出同一份元数据。
   *
   * <p><b>`RENAME TO` 后面不能写全限定名。</b>Spark 3.5 只对**源**标识做 catalog 前缀剥离，
   * 目标是原样交给 catalog 的：写 `RENAME TO catalog.ns.new` 时，catalog 收到的
   * 名字空间是 `["catalog", "ns"]`（把 catalog 名当成了第一级 namespace），
   * 于是服务端回一句「namespace `catalog.ns` 不存在」。
   * 正确写法是 `RENAME TO ns.new`——省略 catalog，因为源已经说明了是哪个 catalog。
   */
  @Test
  def renameKeepsLocationAndRemovesTheOldName(): Unit = {
    val oldName = "before_rename"
    val newName = "after_rename"
    sql(s"CREATE TABLE $catalog.$namespace.$oldName (id BIGINT) USING iceberg")
    val location = serverTable(namespace, oldName).get("metadata").get("location").asText()

    sql(s"ALTER TABLE $catalog.$namespace.$oldName RENAME TO $namespace.$newName")

    assertEquals(404, serverTableStatus(namespace, oldName), "旧名字应已注销")
    assertEquals(location, serverTable(namespace, newName).get("metadata").get("location").asText(),
      "rename 只改标识，不动数据位置")
    assertTrue(sql(s"SHOW TABLES IN $catalog.$namespace").exists(_.getString(1) == newName))
  }

  // ------------------------------------------------------------------ 边界

  /**
   * 非空 namespace 删不掉：规格要求 409，客户端把它翻成 `NamespaceNotEmptyException`。
   *
   * <p>Paimon 侧没有这条约束（Paimon 的库可以连表一起删）。这条差异写在
   * `docs/spark-iceberg-rest-e2e.md` §3 的对照表里，这里把它从「文档说」变成「跑出来的」。
   */
  @Test
  def nonEmptyNamespaceCannotBeDropped(): Unit = {
    val table = "blocking"
    sql(s"CREATE TABLE $catalog.$namespace.$table (id BIGINT) USING iceberg")

    val error = assertThrows(classOf[NamespaceNotEmptyException], () =>
      sql(s"DROP NAMESPACE $catalog.$namespace"))
    assertTrue(chain(error).contains(namespace), () => "消息里应点名 namespace: " + chain(error))

    // 表还在——失败没有产生半删状态
    assertTrue(sql(s"SHOW TABLES IN $catalog.$namespace").exists(_.getString(1) == table),
      "删 namespace 失败之后表必须还在")
  }

  /**
   * 建到不存在的 namespace：服务端的 404 被客户端翻成 `NoSuchNamespaceException`。
   *
   * <p>Paimon 侧对应的是 `NoSuchNamespaceException`（catalyst 的那个），Iceberg 侧是
   * 它自己的同名异常——两个类的**全限定名不同**，写错一个都不会编译不过，
   * 只会在运行时变成「断言类型不匹配」。这里显式钉住 Iceberg 那一个。
   */
  @Test
  def creatingTableInMissingNamespaceFails(): Unit = {
    val missing = namespace + "_absent"
    val error = assertThrows(classOf[org.apache.iceberg.exceptions.NoSuchNamespaceException], () =>
      sql(s"CREATE TABLE $catalog.$missing.t (id BIGINT) USING iceberg"))
    assertTrue(chain(error).contains(missing), () => "消息里应点名 namespace: " + chain(error))
  }

  /** 重复建表：客户端把服务端的 409 翻成「已存在」，而不是静默复用旧表。 */
  @Test
  def duplicateCreateIsRejected(): Unit = {
    val table = "dup"
    sql(s"CREATE TABLE $catalog.$namespace.$table (id BIGINT) USING iceberg")

    val error = assertThrows(classOf[Throwable], () =>
      sql(s"CREATE TABLE $catalog.$namespace.$table (id BIGINT) USING iceberg"))
    assertTrue(chain(error).toLowerCase.contains("already exists"),
      () => "重复建表应报 already exists，实际: " + chain(error))

    // 表结构没被第二次提交改动
    assertEquals(List("id"), fieldNames(currentSchema(serverTable(namespace, table).get("metadata"))))
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

  private def singleValue(statement: String): String = {
    val rows = sql(statement)
    assertEquals(1, rows.length, () => statement + " 应返回恰好一行")
    rows(0).getString(0)
  }

  /** `DESCRIBE` 的列名 → (类型, 注释)，丢掉 `# Partition Information` 这类分隔行。 */
  private def describedColumns(ns: String, table: String): Map[String, (String, String)] =
    sql(s"DESCRIBE $catalog.$ns.$table")
      .filterNot(_.getString(0).startsWith("#"))
      .map(row => row.getString(0) -> (row.getString(1), row.getString(2)))
      .toMap

  /** 直接读服务端的 LoadTableResult——只有这一层能证明元数据真的落了库。 */
  private def serverTable(ns: String, table: String): JsonNode = {
    val path = s"/v1/$catalog/namespaces/$ns/tables/$table"
    val response = get(path)
    assertEquals(200, response.statusCode(), () => "GET " + path + " -> " + response.body())
    MAPPER.readTree(response.body())
  }

  private def serverTableStatus(ns: String, table: String): Int =
    get(s"/v1/$catalog/namespaces/$ns/tables/$table").statusCode()

  private def serverNamespaces(): List[String] = {
    val response = get(s"/v1/$catalog/namespaces")
    assertEquals(200, response.statusCode(), () => "GET /namespaces -> " + response.body())
    MAPPER.readTree(response.body()).get("namespaces").elements().asScala
      .map(_.elements().asScala.map(_.asText()).mkString("."))
      .toList
  }

  private def get(path: String): HttpResponse[String] =
    HttpClient.newHttpClient().send(
      HttpRequest.newBuilder(URI.create(LiveSparkSession.catalogUrl + path))
        .timeout(Duration.ofSeconds(15))
        .header("Authorization", "Bearer " + LiveSparkSession.token)
        .header("Accept", "application/json")
        .GET().build(),
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
}

object IcebergTableDdlTests {

  private val MAPPER = new com.fasterxml.jackson.databind.ObjectMapper()

  private def elements(node: JsonNode): List[JsonNode] = node.elements().asScala.toList

  private def fieldNames(schema: JsonNode): List[String] =
    elements(schema.get("fields")).map(_.get("name").asText())

  private def fieldOf(schema: JsonNode, name: String): JsonNode =
    elements(schema.get("fields")).find(_.get("name").asText() == name).getOrElse(
      throw new AssertionError("服务端返回的 schema 里没有列 " + name + "：" + schema))

  /**
   * `metadata.schemas` 里 `schema-id` 等于 `current-schema-id` 的那一份。
   *
   * <p>不能直接取 `schemas[0]`：改过 schema 之后历史版本都留着（供时间旅行读），
   * 数组第一项是最早那一版。
   */
  private def currentSchema(metadata: JsonNode): JsonNode = {
    val current = metadata.get("current-schema-id").asInt()
    elements(metadata.get("schemas")).find(_.get("schema-id").asInt() == current)
      .getOrElse(throw new AssertionError("找不到 current-schema-id=" + current + " 的 schema"))
  }

  private def defaultPartitionSpec(metadata: JsonNode): JsonNode = {
    val current = metadata.get("default-spec-id").asInt()
    elements(metadata.get("partition-specs")).find(_.get("spec-id").asInt() == current)
      .getOrElse(throw new AssertionError("找不到 default-spec-id=" + current + " 的分区规格"))
  }

  /** 分区字段 → 变换名。Iceberg 的字段名带变换后缀（`ts_day`），与 Paimon 的写法不同。 */
  private def partitionFields(spec: JsonNode): Map[String, String] =
    elements(spec.get("fields")).map(f => f.get("name").asText() -> f.get("transform").asText()).toMap

  /**
   * 类型的读法：基础类型是字符串（`long` / `string`），decimal 在**规范化之后**
   * 也是字符串（`decimal(18, 2)`），但在客户端刚生成、还没被服务端解析的元数据里
   * 它是对象（`{"type":"decimal","precision":18,"scale":2}`）。
   *
   * <p>两种形状都要认：读到的是哪一份，取决于这个字段走没过服务端的 metadata 解析。
   * 用统一的规范写法比较，断言才不会因为「元数据被谁重写了一遍」而红。
   */
  private def typeOf(field: JsonNode): String = {
    val node = field.get("type")
    if (node.isTextual) {
      node.asText()
    } else {
      node.get("type").asText() match {
        case "decimal" => s"decimal(${node.get("precision").asInt()}, ${node.get("scale").asInt()})"
        case other => other
      }
    }
  }

  /** 异常链上的全部消息，用 ` | ` 连起来——客户端经常把真正的原因包在 cause 里。 */
  private def chain(error: Throwable): String =
    Iterator.iterate(error)(_.getCause).takeWhile(_ != null)
      .map(e => Option(e.getMessage).getOrElse(""))
      .mkString(" | ")
}
