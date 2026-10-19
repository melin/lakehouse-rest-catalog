package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

import java.time.{Duration, Instant, LocalDateTime, ZoneId}

import scala.collection.JavaConverters._

/**
 * 解析层测试。
 *
 * <p>只验证「哪条语句被识别成哪个命令、参数解成什么」：不建 `SparkSession`、
 * 不发 HTTP 请求。命令对象本身是无状态的（只带参数与客户端引用），因此可以
 * 直接对解析结果做断言。
 *
 * <p>客户端指向一个不会有人监听的端口：解析不触发任何网络调用，
 * 构造它只是为了满足命令的签名。
 */
class ManagementSqlParsingTests {

  // 原生解析器的替身是**版本模块提供**的具体类：ParserInterface 的抽象成员在
  // 3.5 与 4.x 之间不同（4.x 多 parseRoutineParam），因此共享源码里只能给抽象基类。
  private val delegate = new SparkParserDelegate()
  private val parser = new LakehouseRestSqlParser(
    delegate,
    () => new ManagementApiClient("http://127.0.0.1:1/api/management/v1", null, Duration.ofSeconds(1)))

  /** 断言语句被识别为管理语句，并取出对应的命令。 */
  private def parseAs[T](sql: String): T = parser.parseManagementStatement(sql) match {
    case Some(plan) => plan.asInstanceOf[T]
    case None => throw new AssertionError("语句未被识别为管理语句: " + sql)
  }

  /** 断言语句**不**被本扩展接管。 */
  private def assertNotManagement(sql: String): Unit =
    assertEquals(None, parser.parseManagementStatement(sql),
      s"语句不应被管理语法接管: $sql")

  // ------------------------------------------------------------------ DDL：主体

  @Test
  def createPrincipalParsesNamePropertiesAndIfNotExists(): Unit = {
    val command = parseAs[CreatePrincipalCommand](
      "CREATE PRINCIPAL analyst PROPERTIES ('owner' = 'risk-team', tier = gold)")
    assertEquals("analyst", command.name)
    assertEquals(Map("owner" -> "risk-team", "tier" -> "gold"), command.properties)
    assertFalse(command.ifNotExists)

    val conditional = parseAs[CreatePrincipalCommand]("CREATE PRINCIPAL IF NOT EXISTS analyst")
    assertEquals("analyst", conditional.name)
    assertEquals(Map.empty[String, String], conditional.properties)
    assertTrue(conditional.ifNotExists)
  }

  @Test
  def quotedIdentifiersAndKeywordCaseAreAccepted(): Unit = {
    // 连字符不是 IDENTIFIER 的合法字符，必须用反引号
    val quoted = parseAs[CreatePrincipalCommand]("create principal `svc-analytics@example`")
    assertEquals("svc-analytics@example", quoted.name)

    // 反引号内的 `` 表示一个反引号
    val escaped = parseAs[DropPrincipalCommand]("DROP PRINCIPAL `a``b`")
    assertEquals("a`b", escaped.name)
  }

  @Test
  def dropPrincipalParsesIfExists(): Unit = {
    assertTrue(parseAs[DropPrincipalCommand]("DROP PRINCIPAL IF EXISTS analyst").ifExists)
    assertFalse(parseAs[DropPrincipalCommand]("DROP PRINCIPAL analyst").ifExists)
  }

  @Test
  def alterPrincipalParsesSetAndUnsetSeparately(): Unit = {
    val set = parseAs[AlterPrincipalCommand](
      "ALTER PRINCIPAL analyst SET PROPERTIES ('owner' = 'quant')")
    assertEquals("analyst", set.name)
    assertEquals(Map("owner" -> "quant"), set.toSet)
    assertTrue(set.toUnset.isEmpty)

    val unset = parseAs[AlterPrincipalCommand]("ALTER PRINCIPAL analyst UNSET PROPERTIES (owner, tier)")
    assertEquals(Set("owner", "tier"), unset.toUnset)
    assertTrue(unset.toSet.isEmpty)
  }

  @Test
  def resetAndRotateDifferOnlyInTheRotateFlag(): Unit = {
    assertFalse(parseAs[ResetPrincipalCommand]("RESET PRINCIPAL analyst").rotate)
    assertTrue(parseAs[ResetPrincipalCommand]("ROTATE PRINCIPAL analyst").rotate)
    assertEquals("analyst", parseAs[ResetPrincipalCommand]("rotate principal analyst").name)
  }

  // ------------------------------------------------------------------ DDL：角色

  @Test
  def principalRoleDdlParses(): Unit = {
    val created = parseAs[CreatePrincipalRoleCommand](
      "CREATE PRINCIPAL ROLE IF NOT EXISTS data_engineer PROPERTIES ('purpose' = 'etl')")
    assertEquals("data_engineer", created.name)
    assertEquals(Map("purpose" -> "etl"), created.properties)
    assertTrue(created.ifNotExists)

    assertTrue(parseAs[DropPrincipalRoleCommand]("DROP PRINCIPAL ROLE IF EXISTS data_engineer").ifExists)

    val altered = parseAs[AlterPrincipalRoleCommand](
      "ALTER PRINCIPAL ROLE data_engineer UNSET PROPERTIES (purpose)")
    assertEquals(Set("purpose"), altered.toUnset)
  }

  // ------------------------------------------------------------------ DCL：角色装配

  @Test
  def grantAndRevokePrincipalRoleDifferOnlyInTheRevokeFlag(): Unit = {
    val granted = parseAs[GrantPrincipalRoleCommand](
      "GRANT PRINCIPAL ROLE data_engineer TO PRINCIPAL analyst")
    assertEquals("analyst", granted.principal)
    assertEquals("data_engineer", granted.principalRole)
    assertFalse(granted.revoke)

    val revoked = parseAs[GrantPrincipalRoleCommand](
      "REVOKE PRINCIPAL ROLE data_engineer FROM PRINCIPAL analyst")
    assertEquals("analyst", revoked.principal)
    assertEquals("data_engineer", revoked.principalRole)
    assertTrue(revoked.revoke)
  }

  // ------------------------------------------------------------------ DCL：资源授权

  @Test
  def catalogScopeGrantsCarryNoDatabaseAndTargetTheWholeCatalog(): Unit = {
    val command = parseAs[GrantPermissionsCommand](
      "GRANT CREATEDATABASE ON CATALOG paimon TO USER analyst")

    assertEquals("paimon", command.catalog)
    assertEquals(Seq("CREATEDATABASE"), command.accesses)
    assertEquals("user:analyst", command.principal)
    assertFalse(command.revoke)
    assertTrue(command.expireTime.isEmpty)
    assertEquals("CATALOG", command.resource.`type`())
    assertNull(command.resource.database())
    assertNull(command.resource.objectName())

    // `_ALL` 是显式作用域：catalog 及其此后新建的后代
    val all = parseAs[GrantPermissionsCommand](
      "GRANT LIST ON CATALOG_ALL paimon TO USER analyst")
    assertEquals("CATALOG_ALL", all.resource.`type`())
    assertEquals("paimon", all.catalog)
  }

  @Test
  def databaseScopesKeepTheDatabaseAndHaveNoObjectName(): Unit = {
    val database = parseAs[GrantPermissionsCommand](
      "GRANT CREATETABLE ON DATABASE sales IN CATALOG paimon TO ROLE sales_writer")
    assertEquals("paimon", database.catalog)
    assertEquals("DATABASE", database.resource.`type`())
    assertEquals("sales", database.resource.database())
    assertNull(database.resource.objectName())
    assertEquals("role:sales_writer", database.principal)

    val wholeDatabase = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON DATABASE_ALL sales IN CATALOG paimon TO ROLE sales_reader")
    assertEquals("DATABASE_ALL", wholeDatabase.resource.`type`())
    assertEquals("sales", wholeDatabase.resource.database())
  }

  @Test
  def objectScopesSplitTheDatabaseFromTheObjectName(): Unit = {
    // 多级库名用点号连接进 database 字段，与页面里「库」列显示的形式一致
    val table = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON TABLE raw_layer.events.orders IN CATALOG paimon TO USER analyst")
    assertEquals("TABLE", table.resource.`type`())
    assertEquals("raw_layer.events", table.resource.database())
    assertEquals("orders", table.resource.objectName())

    val view = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON VIEW sales.v_orders IN CATALOG paimon TO USER analyst")
    assertEquals("VIEW", view.resource.`type`())
    assertEquals("sales", view.resource.database())
    assertEquals("v_orders", view.resource.objectName())

    val function = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON FUNCTION sales.f_clean IN CATALOG paimon TO USER analyst")
    assertEquals("FUNCTION", function.resource.`type`())
    assertEquals("sales", function.resource.database())
    assertEquals("f_clean", function.resource.objectName())
  }

  @Test
  def columnScopeIsAWhiteListUnlessExceptTurnsItIntoABlacklist(): Unit = {
    val allowed = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON COLUMN sales.orders (amount, id) IN CATALOG paimon TO USER analyst")
    assertEquals("COLUMN", allowed.resource.`type`())
    assertEquals("sales", allowed.resource.database())
    assertEquals("orders", allowed.resource.objectName())
    assertEquals(Seq("amount", "id"), allowed.resource.columns().asScala.toSeq)
    assertTrue(allowed.resource.excludedColumns().isEmpty)

    val excluded = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON COLUMN sales.orders EXCEPT (card_no) IN CATALOG paimon TO USER analyst")
    assertTrue(excluded.resource.columns().isEmpty)
    assertEquals(Seq("card_no"), excluded.resource.excludedColumns().asScala.toSeq)
  }

  @Test
  def everyGranteeFormsBecomesAPrincipalString(): Unit = {
    def principal(sql: String): String =
      parseAs[GrantPermissionsCommand](sql).principal

    assertEquals("user:alice", principal(
      "GRANT SELECT ON CATALOG paimon TO USER alice"))
    assertEquals("role:sales_writer", principal(
      "GRANT SELECT ON CATALOG paimon TO ROLE sales_writer"))
    assertEquals("group:eng", principal(
      "GRANT SELECT ON CATALOG paimon TO GROUP eng"))
    // 不在三种约定里的主体：原样透传（官方把 principal 定义为不透明字符串）
    assertEquals("svc:etl", principal(
      "GRANT SELECT ON CATALOG paimon TO PRINCIPAL 'svc:etl'"))
    // 反引号用来承载裸标识符不允许的字符
    assertEquals("user:order-reader", principal(
      "GRANT SELECT ON CATALOG paimon TO USER `order-reader`"))
  }

  @Test
  def multipleAccessesExpandInOrderAndAreUppercased(): Unit = {
    val command = parseAs[GrantPermissionsCommand](
      "grant select, alter, drop on table sales.orders in catalog paimon to user analyst")

    assertEquals(Seq("SELECT", "ALTER", "DROP"), command.accesses)
    assertEquals("paimon", command.catalog)

    // 官方词表里 GRANT / ALTER / DROP 同时也是本语法的关键字，必须与裸标识符一样可用
    assertEquals(Seq("GRANT"), parseAs[GrantPermissionsCommand](
      "GRANT GRANT ON CATALOG paimon TO USER analyst").accesses)

    // 反引号写法要剥掉引号再折大小写
    assertEquals(Seq("SELECT"), parseAs[GrantPermissionsCommand](
      "GRANT `select` ON CATALOG paimon TO USER analyst").accesses)
  }

  @Test
  def revokeCarriesTheRevokeFlagAndTheSameTarget(): Unit = {
    val command = parseAs[GrantPermissionsCommand](
      "REVOKE SELECT, DESCRIBE ON TABLE sales.orders IN CATALOG paimon FROM ROLE sales_reader")

    assertTrue(command.revoke)
    assertEquals(Seq("SELECT", "DESCRIBE"), command.accesses)
    assertEquals("role:sales_reader", command.principal)
    assertEquals("sales", command.resource.database())
    assertEquals("orders", command.resource.objectName())
    // 撤销不带有效期：有效期不属于赋权身份，服务端按身份删整条
    assertTrue(command.expireTime.isEmpty)
  }

  @Test
  def untilTurnsADateIntoTheLastMomentOfThatDayInTheLocalZone(): Unit = {
    val command = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON TABLE sales.orders IN CATALOG paimon TO USER analyst UNTIL '2026-11-30'")

    val expire = command.expireTime.get
    // 「到 11 月 30 日」应当整日有效，因此落到本地时区的 30 日 23:59:59.999——
    // 断言用「换回本地时区再比」的写法，避免测试只在某个时区下成立
    val local = LocalDateTime.ofInstant(Instant.parse(expire), ZoneId.systemDefault())
    assertEquals("2026-11-30T23:59:59.999", local.toString)
  }

  @Test
  def untilAcceptsAFullInstantAsIs(): Unit = {
    val command = parseAs[GrantPermissionsCommand](
      "GRANT SELECT ON CATALOG paimon TO USER analyst UNTIL '2026-11-30T15:59:59.999Z'")
    assertEquals("2026-11-30T15:59:59.999Z", command.expireTime.get)
  }

  @Test
  def anUnparsableUntilIsRejectedBeforeAnyRequest(): Unit = {
    val error = assertThrows(classOf[ManagementSqlException], () =>
      parseAs[GrantPermissionsCommand](
        "GRANT SELECT ON CATALOG paimon TO USER analyst UNTIL 'next tuesday'"))
    assertTrue(error.getMessage.contains("expire time"), error.getMessage)
  }

  @Test
  def objectGrantWithoutADatabaseIsRejected(): Unit = {
    // 库是授权作用域的一部分，单段名字无法确定作用域，因此直接在解析期拒绝
    val error = assertThrows(classOf[ManagementSqlException], () =>
      parseAs[GrantPermissionsCommand](
        "GRANT SELECT ON TABLE orders IN CATALOG paimon TO USER analyst"))
    assertTrue(error.getMessage.contains("database"),
      "报错信息应说明需要 database，实际为: " + error.getMessage)
  }

  // ------------------------------------------------------------------ SHOW

  @Test
  def showStatementsParseTheirScopes(): Unit = {
    assertNotNull(parseAs[ShowCatalogsCommand]("SHOW CATALOGS"))

    assertTrue(parseAs[ShowPrincipalsCommand]("SHOW PRINCIPALS").forPrincipalRole.isEmpty)
    assertEquals("data_engineer",
      parseAs[ShowPrincipalsCommand]("SHOW PRINCIPALS FOR PRINCIPAL ROLE data_engineer")
        .forPrincipalRole.orNull)

    assertTrue(parseAs[ShowPrincipalRolesCommand]("SHOW PRINCIPAL ROLES").forPrincipal.isEmpty)
    assertEquals("analyst",
      parseAs[ShowPrincipalRolesCommand]("SHOW PRINCIPAL ROLES FOR PRINCIPAL analyst")
        .forPrincipal.orNull)

    val permissions = parseAs[ShowPermissionsCommand]("SHOW PERMISSIONS IN CATALOG paimon")
    assertEquals("paimon", permissions.catalog)
    assertTrue(permissions.principal.isEmpty)

    val forUser = parseAs[ShowPermissionsCommand](
      "SHOW PERMISSIONS IN CATALOG paimon FOR USER analyst")
    assertEquals("user:analyst", forUser.principal.orNull)
  }

  // ------------------------------------------------------------------ 接管边界

  @Test
  def nonManagementStatementsAreHandedBackToTheNativeParser(): Unit = {
    val delegate = new SparkParserDelegate()
    val parser = new LakehouseRestSqlParser(delegate,
      () => new ManagementApiClient("http://127.0.0.1:1/api/management/v1", null, Duration.ofSeconds(1)))

    val sql = "SELECT * FROM paimon.raw_layer.orders"
    assertThrows(classOf[UnsupportedOperationException], () => parser.parsePlan(sql))
    assertEquals(List(sql), delegate.delegated.toList)
  }

  @Test
  def statementsThatOnlyLookLikeManagementSqlAreNotSwallowed(): Unit = {
    // Spark 原生的 SHOW / CREATE 语句不能误判
    assertNotManagement("SHOW TABLES")
    assertNotManagement("SHOW DATABASES")
    assertNotManagement("CREATE TABLE t (a INT)")
    // Spark 原生也有 GRANT（Hive 风格），且没有 `IN CATALOG`，不能被接管
    assertNotManagement("GRANT SELECT ON TABLE t TO user")
    assertNotManagement("REVOKE SELECT ON TABLE t FROM user")
    // 授权对象必须写明是哪一类 principal
    assertNotManagement("GRANT SELECT ON CATALOG paimon TO analyst")
    // 撤销不带有效期
    assertNotManagement("REVOKE SELECT ON CATALOG paimon FROM USER analyst UNTIL '2026-11-30'")
    // 管理关键字开头但不完整：语法要求整句匹配到 EOF，因此不会被接管
    assertNotManagement("CREATE PRINCIPAL")
    assertNotManagement("CREATE PRINCIPAL analyst extra_token")
    assertNotManagement("DROP PRINCIPAL")
  }

  /**
   * catalog role 的语句已经全部移出语法：控制台没有它的页面，SQL 也不留入口，
   * 它只剩管理 API 一条路（装配用例与 `docs/authorization.md` §5 的 curl）。
   *
   * <p>这条用例钉住的是「**真的没有了**」——六条语句原样交回原生解析器，
   * 由 Spark 自己报语法错。留着它比删干净更值：将来有人顺手把语句加回来，
   * 这里会立刻红，而文档与页面都还没有跟上。
   */
  @Test
  def catalogRoleStatementsAreNoLongerRecognised(): Unit = {
    assertNotManagement("CREATE CATALOG ROLE reader IN CATALOG paimon")
    assertNotManagement("DROP CATALOG ROLE reader IN CATALOG paimon")
    assertNotManagement("ALTER CATALOG ROLE reader IN CATALOG paimon SET PROPERTIES (a = '1')")
    assertNotManagement("GRANT CATALOG ROLE reader TO PRINCIPAL ROLE data_engineer IN CATALOG paimon")
    assertNotManagement("REVOKE CATALOG ROLE reader FROM PRINCIPAL ROLE data_engineer IN CATALOG paimon")
    assertNotManagement("SHOW CATALOG ROLES IN CATALOG paimon")
    assertNotManagement("SHOW GRANTS FOR CATALOG ROLE reader IN CATALOG paimon")
  }

  @Test
  def trailingSemicolonAndCommentsAreTolerated(): Unit = {
    assertEquals("analyst", parseAs[DropPrincipalCommand]("DROP PRINCIPAL analyst;").name)
    assertEquals("analyst", parseAs[DropPrincipalCommand]("DROP PRINCIPAL analyst;;").name)
    assertEquals("analyst",
      parseAs[DropPrincipalCommand]("-- 迁移脚本\nDROP PRINCIPAL analyst /* 一次性 */").name)
  }
}
