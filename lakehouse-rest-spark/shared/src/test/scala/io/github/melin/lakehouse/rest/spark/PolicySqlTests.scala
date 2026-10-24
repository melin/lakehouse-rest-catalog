package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import io.github.melin.lakehouse.rest.spark.support.{RecordedRequest, SharedTestSession, StubManagementServer}
import org.apache.spark.sql.SparkSession
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{BeforeEach, Test}

/**
 * 数据策略管理语句的端到端测试（§13）：真实会话 + 桩服务端，断言的是
 * 「SQL 文本 → PUT 请求体里的 AST」这条链——语句的语义权威是服务端，
 * 这里钉住的是形状与关键语义（{@code $principal} 折叠、标准集识别、防呆与 no-op）。
 */
object PolicySqlTests {

  lazy val stub: StubManagementServer = SharedTestSession.stub

  lazy val session: SparkSession = SharedTestSession.session

  /** 表上已有策略的详情（GET 的响应体；version 2 供乐观锁断言用）。 */
  val existingPolicy: String =    """{"table":"paimon.sales.orders","enabled":true,
      |"rowFilter":{"op":"eq","left":{"col":"region"},"right":{"lit":"CN"}},
      |"rowFilterSql":"region = 'CN'",
      |"masks":[{"column":"phone","mask":{"fn":"show_first_n","args":[3]},
      |          "sql":"show_first_n(3)","allowNullOnRequired":false}],
      |"exemptPrincipals":["table_owner"],"subjects":[],"enforcedScope":"engine",
      |"version":2,"createdBy":"admin","createdAt":1700000000000,
      |"updatedBy":"admin","updatedAt":1700000000001}""".stripMargin

}

class PolicySqlTests {

  import PolicySqlTests._

  private val stub = PolicySqlTests.stub
  private val session = PolicySqlTests.session

  @BeforeEach
  def resetStub(): Unit = stub.reset()

  /** GET 策略详情的响应；{@code null} = 表上没配过策略（204）。 */
  private def respond(detailBody: String): Unit = {
    stub.responder = (request: RecordedRequest) => (request.method, request.path) match {
      case ("GET", path) if path.contains("/policies/tables/") =>
        if (detailBody == null) (204, "") else (200, detailBody)
      case ("PUT", _) => (200, existingPolicy)
      case ("DELETE", _) => (204, "")
      case _ => (200, "{}")
    }
  }

  private def putRequests: Seq[RecordedRequest] = stub.requestsOf("PUT")

  private def lastPutBody: String = {
    assertTrue(putRequests.nonEmpty, "应有一条 PUT 请求")
    putRequests.last.body
  }

  // ------------------------------------------------------------------ SET ROW FILTER

  @Test
  def setRowFilterCreatesVersionOneWithTheConvertedAst(): Unit = {
    respond(null)

    session.sql(
      "ALTER TABLE sales.orders IN CATALOG paimon " +
        "SET ROW FILTER region = 'CN' AND status <> 'void'").collect()

    val put = lastPutBody
    // 首次创建：不带 baseVersion / enabled，由服务端定默认值
    assertFalse(put.contains("baseVersion"), put)
    assertFalse(put.contains("\"enabled\""), put)
    assertTrue(put.contains("\"rowFilter\":{\"op\":\"and\""), put)
    assertTrue(put.contains("\"col\":\"region\""), put)
    // <> 解析成 Not(EqualTo)：AST 用 not + eq 表达
    assertTrue(put.contains("\"op\":\"not\""), put)
    // 省略 FOR = 全部主体（§13.2）：白名单清空，不做「继承上一次」
    assertTrue(put.contains("\"subjects\":[]"), put)
    assertEquals("/api/management/v1/policies/tables/paimon/sales/orders",
      stub.requestsOf("PUT").head.path)
  }

  @Test
  def currentUserFoldsIntoThePrincipalPlaceholderAtConversionTime(): Unit = {
    respond(null)

    session.sql(
      "ALTER TABLE sales.orders IN CATALOG paimon SET ROW FILTER region = current_user()")
      .collect()

    // AST 里只存占位符：不存函数调用，更不存任何人的名字（§13.4）
    val put = lastPutBody
    assertTrue(put.contains("\"principal\":true"), put)
    assertFalse(put.contains("current_user"), put)
  }

  @Test
  def forClauseStoresTheSubjectListAndRejectsRoleShapes(): Unit = {
    respond(null)

    session.sql(
      "ALTER TABLE sales.orders IN CATALOG paimon " +
        "SET ROW FILTER region = 'CN' FOR USER analyst_li").collect()

    assertTrue(lastPutBody.contains("\"subjects\":[\"analyst_li\"]"), lastPutBody)

    val roleError = assertThrows(classOf[ManagementSqlException], () =>
      session.sql(
        "ALTER TABLE sales.orders IN CATALOG paimon " +
          "SET ROW FILTER region = 'CN' FOR ROLE bi_readers").collect())
    assertTrue(roleError.getMessage.contains("USER"), roleError.getMessage)
  }

  // ------------------------------------------------------------------ SET MASK / DROP MASK

  @Test
  def standardMaskIsRecognizedByTextShapeAndExistingPolicyIsCarriedThrough(): Unit = {
    respond(existingPolicy)

    session.sql(
      "ALTER TABLE sales.orders IN CATALOG paimon " +
        "ALTER COLUMN customer_phone SET MASK show_first_n(3)").collect()

    val put = lastPutBody
    assertTrue(put.contains("\"column\":\"customer_phone\"," +
      "\"mask\":{\"fn\":\"show_first_n\",\"args\":[3]}}"), put)
    // 整份替换：原有的行过滤、豁免名单与 enabled 必须原样带回
    assertTrue(put.contains("\"baseVersion\":2"), put)
    assertTrue(put.contains("\"enabled\":true"), put)
    assertTrue(put.contains("\"rowFilter\":{\"op\":\"eq\""), put)
    assertTrue(put.contains("\"exemptPrincipals\":[\"table_owner\"]"), put)
    // 原有的 phone mask 也在——整份替换带走清单里已有的列，覆盖只发生在同列重复 SET
    assertTrue(put.contains("\"column\":\"phone\""), put)
  }

  @Test
  def escapeHatchMaskProducesAnExprNodeWithCaseWhenAndCast(): Unit = {
    respond(null)

    session.sql(
      "ALTER TABLE sales.orders IN CATALOG paimon ALTER COLUMN customer_name SET MASK " +
        "CASE WHEN region = 'CN' THEN customer_name ELSE cast(NULL AS string) END").collect()

    val put = lastPutBody
    assertTrue(put.contains("\"mask\":{\"expr\":{\"fn\":\"case_when\""), put)
    assertTrue(put.contains("\"fn\":\"cast\""), put)
    assertTrue(put.contains("\"to\":\"STRING\""), put)
  }

  @Test
  def allowNullOrRequiredIsStrippedIntoTheConfirmationFlag(): Unit = {
    respond(null)

    session.sql(
      "ALTER TABLE sales.orders IN CATALOG paimon ALTER COLUMN note SET MASK " +
        "replace_with_null ALLOW NULL ON REQUIRED").collect()

    val put = lastPutBody
    assertTrue(put.contains("\"mask\":{\"fn\":\"replace_with_null\"}"), put)
    assertTrue(put.contains("\"allowNullOnRequired\":true"), put)
  }

  @Test
  def dropMaskFailsWhenTheColumnHasNoMask(): Unit = {
    respond(existingPolicy)

    val error = assertThrows(classOf[ManagementSqlException], () =>
      session.sql(
        "ALTER TABLE sales.orders IN CATALOG paimon ALTER COLUMN email DROP MASK").collect())

    assertTrue(error.getMessage.contains("no mask"), error.getMessage)
    assertTrue(putRequests.isEmpty, "报错的语句不应发出 PUT")
  }

  // ------------------------------------------------------------------ 报错而不是 no-op

  @Test
  def dropRowFilterFailsWithoutAnExistingPolicy(): Unit = {
    respond(null)

    val error = assertThrows(classOf[ManagementSqlException], () =>
      session.sql(
        "ALTER TABLE sales.orders IN CATALOG paimon DROP ROW FILTER").collect())

    assertTrue(error.getMessage.contains("no-op"), error.getMessage)
  }

  @Test
  def setPolicyForFailsWithoutAnExistingPolicy(): Unit = {
    respond(null)

    val error = assertThrows(classOf[ManagementSqlException], () =>
      session.sql(
        "ALTER TABLE sales.orders IN CATALOG paimon SET POLICY FOR USER analyst").collect())

    assertTrue(error.getMessage.contains("no data policy"), error.getMessage)
  }

  // ------------------------------------------------------------------ DROP POLICY 防呆

  @Test
  def dropPolicyRequiresTheSessionLevelConfirmation(): Unit = {
    respond(existingPolicy)

    // 缺省拒绝，报错给出配置键与替代路径（§13.3）
    val error = assertThrows(classOf[ManagementSqlException], () =>
      session.sql("ALTER TABLE sales.orders IN CATALOG paimon DROP POLICY").collect())
    assertTrue(error.getMessage.contains(PolicySqlCommands.CONFIRM_DESTRUCTIVE),
      error.getMessage)
    assertTrue(stub.requestsOf("DELETE").isEmpty)

    try {
      session.conf.set(PolicySqlCommands.CONFIRM_DESTRUCTIVE, "true")
      session.sql("ALTER TABLE sales.orders IN CATALOG paimon DROP POLICY").collect()
      assertEquals("/api/management/v1/policies/tables/paimon/sales/orders",
        stub.last.path)
      assertEquals("DELETE", stub.last.method)
    } finally {
      session.conf.set(PolicySqlCommands.CONFIRM_DESTRUCTIVE, "false")
    }
  }

  // ------------------------------------------------------------------ SHOW POLICY

  @Test
  def showPolicyReturnsTheDeclaredColumnsAndZeroRowsWithoutPolicy(): Unit = {
    respond(existingPolicy)
    val rows = session.sql("SHOW POLICY ON TABLE sales.orders IN CATALOG paimon")
    assertEquals(Seq("version", "subjects", "row_filter", "masked_columns",
      "allow_null_confirmed", "updated_by", "updated_at"), rows.schema.fieldNames.toSeq)
    val row = rows.collect()(0)
    assertEquals("2", row.getString(0))
    assertEquals("", row.getString(1), "白名单空 = 全部主体，回显为空串")
    assertEquals("region = 'CN'", row.getString(2))
    assertEquals("phone", row.getString(3))
    assertEquals("false", row.getString(4))
    assertEquals("admin", row.getString(5))

    respond(null)
    assertTrue(session.sql("SHOW POLICY ON TABLE sales.orders IN CATALOG paimon")
      .collect().isEmpty, "没有策略返回 0 行，不报错（§13.3）")
  }

  // ------------------------------------------------------------------ 白名单外与确定性

  @Test
  def nonDeterministicExpressionsAreNamedAndRejected(): Unit = {
    respond(null)

    val error = assertThrows(classOf[ManagementSqlException], () =>
      session.sql(
        "ALTER TABLE sales.orders IN CATALOG paimon SET ROW FILTER rand() > 0.5").collect())

    assertTrue(error.getMessage.contains("Unsupported expression"), error.getMessage)
    assertTrue(stub.requests.isEmpty, "被拒绝的语句不应发起任何请求")
  }

  @Test
  def ansiModeDoesNotChangeTheConvertedAst(): Unit = {
    // §13.4 的会话依赖钉子：parseExpression 在 ANSI / 非 ANSI 下可能给出不同的树；
    // 同一句谓词两种配置各转一次，AST 必须一致，否则该写法应被拉黑
    def convert(): String = {
      respond(null)
      session.sql(
        "ALTER TABLE sales.orders IN CATALOG paimon SET ROW FILTER amount > 100").collect()
      val body = lastPutBody
      stub.reset()
      body
    }

    val plain = convert()
    try {
      session.conf.set("spark.sql.ansi.enabled", "true")
      val ansi = convert()
      assertEquals(plain, ansi, "同一谓词在 ANSI 开/关下必须转换出同一个 AST")
    } finally {
      session.conf.set("spark.sql.ansi.enabled", "false")
    }
  }

  // ------------------------------------------------------------------ 边界

  @Test
  def managementPolicyStatementsLeaveNoLineageTrace(): Unit = {
    // §13.6：管理语句不得产生血缘。抽取器只认对目标表的写命令——
    // 策略语句的计划是 RunnableCommand，不是写命令；这条钉子防住
    // 「每次改策略都造一条假 run」的回归
    respond(null)
    session.sql(
      "ALTER TABLE sales.orders IN CATALOG paimon SET ROW FILTER region = 'CN'").collect()

    assertTrue(stub.requestsOf("POST").forall(!_.path.contains("/lineage/")),
      "策略语句不应触发血缘上报，实际: " + stub.requestsOf("POST").map(_.path).toString)
  }

  @Test
  def nativeAlterTableStillTakesTheNativePath(): Unit = {
    // IN CATALOG 形状互斥的另一半：普通 ALTER TABLE 不被策略语句拦截
    val error = assertThrows(classOf[Exception], () =>
      session.sql("ALTER TABLE nonexistent_table SET TBLPROPERTIES ('k' = 'v')").collect())
    assertTrue(error.getMessage.nonEmpty)
    assertTrue(stub.requests.isEmpty, "原生 DDL 不应触碰管理 API")
  }
}
