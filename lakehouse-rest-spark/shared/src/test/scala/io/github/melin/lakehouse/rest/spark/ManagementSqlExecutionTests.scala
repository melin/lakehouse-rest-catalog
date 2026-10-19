package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiException
import io.github.melin.lakehouse.rest.spark.support.{RecordedRequest, StubManagementServer}
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{BeforeEach, Test}

/**
 * 端到端测试：真实 `SparkSession` + 本扩展 + 进程内管理 API 桩。
 *
 * <p>这一层验证的是前两层测不到的东西：扩展是否真的被会话加载、`spark.sql(...)`
 * 是否真的把管理语句执行掉（`RunnableCommand` 的 `run` 会被
 * `QueryExecution.assertCommandExecuted` 触发）、结果行是否按命令的 `output`
 * 形状返回、以及**原生 SQL 是否完全不受影响**。
 *
 * <p>刻意用配置方式启用扩展（{@code spark.sql.extensions=...}）而不是
 * {@link PaimonRestManagement#install}：前者是用户在 spark-sql / spark-submit 上
 * 的实际做法，后者只是同一段注入逻辑的编程式包装。
 *
 * <p>会话按类共享。Spark 一个 JVM 内只起一个 `SparkContext`，因此这里不能为每个
 * 测试方法新建会话；桩服务端同样按类共享，测试间只清空记录。
 */
object ManagementSqlExecutionTests {

  /** 桩服务端：必须在会话之前起好，因为会话要拿它的地址当配置。 */
  lazy val stub: StubManagementServer = {
    val server = StubManagementServer.start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop()))
    server
  }

  val token = "spark-session-token"

  lazy val session: SparkSession = {
    // 先确认 JDK 跑得了 Spark 3.5 的会话（JDK 24+ 会在 Hadoop 内部以
    // 「getSubject is not supported」炸掉），否则这里就给出该换哪个 JDK
    SparkJdkRequirement.requireHadoopCompatibleJdk()
    val warehouse = java.nio.file.Files.createTempDirectory("paimon-rest-warehouse")
    val session = SparkSession.builder()
      .master("local[1]")
      .appName("lakehouse-rest-spark-e2e")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.warehouse.dir", warehouse.toString)
      .config(PaimonRestManagement.MANAGEMENT_URL, stub.baseUrl)
      .config(PaimonRestManagement.TOKEN, token)
      .config("spark.sql.extensions", PaimonRestManagement.EXTENSION_CLASS)
      .getOrCreate()
    // 主动触发一次解析器构造，让配置错误的报错发生在建会话时而不是第一个测试里
    session.sessionState.sqlParser
    session
  }
}

class ManagementSqlExecutionTests {

  private val stub = ManagementSqlExecutionTests.stub
  private val session = ManagementSqlExecutionTests.session

  @BeforeEach
  def resetStub(): Unit = stub.reset()

  /** 让桩对常见读取返回可解析的响应；未列出的写请求统一按「新建」返回 201。 */
  private def respondWithDefaults(): Unit = {
    stub.responder = (request: RecordedRequest) => (request.method, request.path) match {
      case ("DELETE", _) => (204, "")
      case ("POST", path) if path.endsWith("/principals") =>
        (201, """{"principal":{"name":"svc_etl"},
                 |"credentials":{"clientId":"cid-1","clientSecret":"secret-1"}}""".stripMargin)
      case ("GET", path) if path.endsWith("/principals") =>
        (200, """{"principals":[{"name":"svc_etl","clientId":"cid-1","properties":{},
                 |"entityVersion":1}]}""".stripMargin)
      case ("GET", path) if path.endsWith("/catalogs") =>
        (200, """{"catalogs":[{"name":"paimon","type":"INTERNAL","entityVersion":1}]}""")
      // 直授列表：权限端点长这样 /v1/{prefix}/permissions
      case ("GET", path) if path.endsWith("/permissions") =>
        (200, """{"assignments":[{"resource":{"type":"TABLE","database":"sales","table":"orders"},
                 |"access":"SELECT","principal":"user:analyst"}]}""".stripMargin)
      case ("GET", _) =>
        // 兜底：剩下的 GET（例如 SHOW PRINCIPAL ROLES）给空列表就行，用例不断言它们的行
        (200, "{}")
      case _ => (201, "{}")
    }
  }

  // ------------------------------------------------------------------ 前提

  @Test
  def theExtensionIsLoadedAndTargetsTheCompiledSparkVersion(): Unit = {
    // 期望值由 POM 注入（surefire 的 systemPropertyVariables，取 ${spark.version}）：
    // 3.5 与 4.1 两个模块跑的是同一份测试源码，写死 "3.5" 在 4.1 上必然失败；
    // 而「本产物是不是给这个 Spark 编的」正是这条用例要守的东西，不能省。
    val expected = System.getProperty("expected.spark.version")
    assertNotNull(expected, "缺少系统属性 expected.spark.version（应由 POM 的 surefire 配置注入）")
    assertEquals(expected, session.version, "会话的 Spark 版本应与本产物的编译目标一致")

    val extensions = session.conf.get("spark.sql.extensions", "")
    assertTrue(extensions.contains(classOf[ManagementSparkExtensions].getName),
      "扩展应通过 spark.sql.extensions 加载，实际: " + extensions)
  }

  // ------------------------------------------------------------------ 不破坏原生 SQL

  @Test
  def nativeSqlIsUnaffectedAndDoesNotTouchTheManagementApi(): Unit = {
    val rows = session.sql("SELECT 1 AS one, 'a' AS letter").collect()

    assertEquals(1, rows.length)
    assertEquals(1, rows(0).getInt(0))
    assertEquals("a", rows(0).getString(1))
    assertTrue(stub.requests.isEmpty, "普通查询不应触碰管理 API")
  }

  @Test
  def nativeShowStatementsStillTakeTheNativePath(): Unit = {
    // Spark 自带 SHOW DATABASES / SHOW TABLES，不能被本扩展误接管
    session.sql("SHOW DATABASES").collect()
    session.sql("CREATE DATABASE IF NOT EXISTS demo").collect()
    val tables = session.sql("SHOW TABLES IN demo")

    // Spark 3.5 的 SHOW TABLES 列为 namespace / tableName / isTemporary
    assertEquals(Seq("namespace", "tableName", "isTemporary"), tables.schema.fieldNames.toSeq)
    assertTrue(tables.collect().isEmpty, "demo 库还没有表")
    assertTrue(stub.requests.isEmpty, "原生 SHOW 不应触碰管理 API")
  }

  // ------------------------------------------------------------------ 执行

  @Test
  def createPrincipalReturnsTheOneTimeCredentials(): Unit = {
    respondWithDefaults()

    val rows = session.sql(
      "CREATE PRINCIPAL svc_etl PROPERTIES ('owner' = 'data-platform')").collect()

    assertEquals(1, rows.length)
    assertEquals("svc_etl", rows(0).getString(0))
    assertEquals("cid-1", rows(0).getString(1))
    assertEquals("secret-1", rows(0).getString(2))

    val posted = stub.requestsOf("POST").head
    assertEquals("/api/management/v1/principals", posted.path)
    assertTrue(posted.body.contains("\"owner\":\"data-platform\""), posted.body)
    assertEquals("Bearer " + ManagementSqlExecutionTests.token, posted.authorization)
  }

  @Test
  def showStatementsReturnRowsWithTheDeclaredColumns(): Unit = {
    respondWithDefaults()

    val catalogs = session.sql("SHOW CATALOGS")
    assertEquals(Seq("name", "type"), catalogs.schema.fieldNames.toSeq)
    val catalogRows = catalogs.collect()
    assertEquals(1, catalogRows.length)
    assertEquals("paimon", catalogRows(0).getString(0))
    assertEquals("INTERNAL", catalogRows(0).getString(1))

    val principals = session.sql("SHOW PRINCIPALS")
    assertEquals(Seq("name", "client_id"), principals.schema.fieldNames.toSeq)
    assertEquals("svc_etl", principals.collect()(0).getString(0))
  }

  @Test
  def grantExecutesOneRequestPerAccess(): Unit = {
    stub.responder = (_: RecordedRequest) => (201, "")

    session.sql("GRANT SELECT, DESCRIBE ON TABLE sales.orders IN CATALOG paimon TO USER analyst")
      .collect()

    val posts = stub.requestsOf("POST")
    assertEquals(2, posts.size, "两项 access 应展开成两次授权请求")
    // 权限端点在 catalog API 下，与管理 API 是两个前缀
    assertTrue(posts.forall(_.path == "/v1/paimon/permissions/grant"), posts.map(_.path).toString)
    assertTrue(posts.head.body.contains("\"SELECT\""), posts.head.body)
    assertTrue(posts.head.body.contains("\"user:analyst\""), posts.head.body)
    assertTrue(posts.last.body.contains("\"DESCRIBE\""), posts.last.body)
  }

  @Test
  def showPermissionsReturnsOneRowPerAssignment(): Unit = {
    respondWithDefaults()

    val permissions = session.sql("SHOW PERMISSIONS IN CATALOG paimon")

    // catalog、principal、type、resource、database、object_name、columns、access、expire_time
    assertEquals(Seq("catalog", "principal", "type", "resource", "database", "object_name",
      "columns", "access", "expire_time"), permissions.schema.fieldNames.toSeq)

    val rows = permissions.collect()
    assertEquals(1, rows.length)
    assertEquals("paimon", rows(0).getString(0))
    assertEquals("user:analyst", rows(0).getString(1))
    assertEquals("TABLE", rows(0).getString(2))
    assertEquals("sales.orders", rows(0).getString(3))
    assertEquals("sales", rows(0).getString(4))
    assertEquals("orders", rows(0).getString(5))
    assertNull(rows(0).getString(6), "表级授权没有列范围")
    assertEquals("SELECT", rows(0).getString(7))
    assertNull(rows(0).getString(8), "列表里没有 expireTime 表示长期有效")
    assertEquals("/v1/paimon/permissions", stub.last.path)
  }

  @Test
  def writeStatementsReturnNoRows(): Unit = {
    respondWithDefaults()

    // 与 Spark 原生 DDL 一致：管理类写入语句不返回结果集
    assertTrue(session.sql("CREATE PRINCIPAL ROLE data_engineer").collect().isEmpty)
    assertTrue(session.sql(
      "GRANT PRINCIPAL ROLE data_engineer TO PRINCIPAL svc_etl").collect().isEmpty)
    assertTrue(session.sql(
      "REVOKE SELECT ON TABLE sales.orders IN CATALOG paimon FROM USER analyst").collect().isEmpty)
    assertTrue(session.sql("DROP PRINCIPAL IF EXISTS svc_etl").collect().isEmpty)
  }

  @Test
  def resetPrincipalReturnsFreshCredentials(): Unit = {
    // rotate/reset 按规格成功返回 200
    stub.responder = (_: RecordedRequest) => (200,
      """{"principal":{"name":"svc_etl"},
        |"credentials":{"clientId":"cid-2","clientSecret":"rotated"}}""".stripMargin)

    val rows = session.sql("ROTATE PRINCIPAL svc_etl").collect()

    assertEquals("cid-2", rows(0).getString(1))
    assertEquals("rotated", rows(0).getString(2))
    assertEquals("/api/management/v1/principals/svc_etl/rotate", stub.last.path)
  }

  // ------------------------------------------------------------------ 错误路径

  @Test
  def serverSideDenialSurfacesAsAManagementApiException(): Unit = {
    stub.responder = (_: RecordedRequest) =>
      (403, """{"type":"Forbidden","message":"svc_etl is not allowed to create principals"}""")

    val error = assertThrows(classOf[ManagementApiException], () =>
      session.sql("CREATE PRINCIPAL ghost").collect())

    assertTrue(error.forbidden())
    assertEquals("svc_etl is not allowed to create principals", error.getMessage)
  }

  @Test
  def malformedManagementSqlIsReportedByTheNativeParser(): Unit = {
    // 语法要求整句匹配到 EOF，因此残篇不会被本扩展接管；
    // 由 Spark 原生解析器给出它自己的错误信息，而不是这里的语法信息
    val error = assertThrows(classOf[ParseException], () => session.sql("CREATE PRINCIPAL").collect())

    assertTrue(error.getMessage.nonEmpty)
    assertTrue(stub.requests.isEmpty, "解析失败的语句不应发起任何请求")
  }

  @Test
  def missingDatabaseInAnObjectGrantFailsBeforeAnyRequest(): Unit = {
    val error = assertThrows(classOf[ManagementSqlException], () =>
      session.sql("GRANT SELECT ON TABLE orders IN CATALOG paimon TO USER analyst").collect())

    assertTrue(error.getMessage.contains("database"), error.getMessage)
    assertTrue(stub.requests.isEmpty, "形状非法时不应发起请求")
  }
}
