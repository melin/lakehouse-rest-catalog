package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.{ManagementApiClient, ManagementApiException}
import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.{Duration, Instant, LocalDate, LocalTime, ZoneId}

import scala.collection.JavaConverters._

/**
 * 对着**真实服务端**的验收测试。
 *
 * <p>为什么还需要这一层：前面三层测试里的管理 API 都是桩，桩只能证明「客户端与我的
 * 假设自洽」，证明不了「服务端真的接受这些请求」。这一层用真的 LakeHouse Rest Catalog Server
 * 跑完整的 SQL 链路，验证的是两侧实现的契约一致性——请求路径、请求体字段、
 * 状态码、以及 `entityVersion` 这类容易被双方各自理解错的细节。
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
 * mvn -o -pl :lakehouse-rest-spark test -Dtest=ManagementSqlLiveServerTests \
 *     -De2e.management.url=http://127.0.0.1:18080/api/management/v1 \
 *     -De2e.management.token=root
 * </pre>
 */
object ManagementSqlLiveServerTests {

  /**
   * 会话按进程共享。
   *
   * <p>实现在 {@link LiveSparkSession}：一个 JVM 只能有一个 SparkContext，
   * 而 `spark.sql.extensions` 只在建会话时生效，因此所有需要真实服务端的
   * 测试类必须取同一个会话——包括需要 Paimon 扩展的建表用例
   * （见 {@link PaimonTableDdlTests}）。这里保留这个入口，
   * 是为了不打断既有引用（{@link SparkSqlDocExamplesTests}）。
   */
  lazy val session: SparkSession = LiveSparkSession.instance
}

class ManagementSqlLiveServerTests {

  private val url = Option(System.getProperty("e2e.management.url")).filter(_.nonEmpty)
  private val token = System.getProperty("e2e.management.token", "")

  @BeforeEach
  def requireLiveServer(): Unit =
    Assumptions.assumeTrue(url.isDefined,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")

  private def session: SparkSession = ManagementSqlLiveServerTests.session

  /** 直接用 HTTP 读原始响应：`SHOW` 语句不返回属性，验证属性必须走原始接口。 */
  private def rawGet(path: String): String = {
    val (status, body) = rawSend("GET", path, null)
    assertEquals(200, status, () => "GET " + path + " -> " + body)
    body
  }

  /**
   * 直接向管理 API 发一次请求，返回 `(状态码, 响应体)`。
   *
   * <p>给两处用：一是读原始响应（`SHOW` 语句不返回属性，验证属性必须走原始接口）；
   * 二是碰上**没有 SQL 语句**的动作时直接调端点——catalog role 的建档与装配
   * 已经只剩管理 API 一条路。
   */
  private def rawSend(method: String, path: String, body: String): (Int, String) = {
    val publisher =
      if (body == null) {
        HttpRequest.BodyPublishers.noBody()
      } else {
        HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)
      }
    val request = HttpRequest.newBuilder(URI.create(url.get + path))
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + token)
      .header("Content-Type", "application/json")
      .header("Accept", "application/json")
      .method(method, publisher)
      .build()
    val response = HttpClient.newHttpClient()
      .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    (response.statusCode(), response.body())
  }

  private def collect(sql: String): Array[Row] = session.sql(sql).collect()

  // ------------------------------------------------------------------ 主体

  @Test
  def principalLifecycleRoundTripsThroughRealSql(): Unit = {
    val name = "e2e_analyst"
    collect(s"DROP PRINCIPAL IF EXISTS $name")

    // 创建：一次性返回 clientId 与明文密钥
    val created = collect(s"CREATE PRINCIPAL $name PROPERTIES ('purpose' = 'e2e')")
    assertEquals(1, created.length)
    assertEquals(name, created(0).getString(0))
    assertNotNull(created(0).getString(1), "应返回 clientId")
    val firstSecret = created(0).getString(2)
    assertNotNull(firstSecret, "应返回一次性明文密钥")

    // 重复创建：服务端 409，经客户端映射成 alreadyExists
    val duplicate = assertThrows(classOf[ManagementApiException], () => collect(s"CREATE PRINCIPAL $name"))
    assertTrue(duplicate.alreadyExists(),
      "重复创建应映射为 409，实际: " + duplicate.status() + " / " + duplicate.getMessage)

    // IF NOT EXISTS 应当吞掉 409
    assertTrue(collect(s"CREATE PRINCIPAL IF NOT EXISTS $name").isEmpty)

    // ALTER：SQL 是增量语义，而规格的 PUT 是整体替换，客户端必须读-合并-回写
    collect(s"ALTER PRINCIPAL $name SET PROPERTIES ('tier' = 'gold')")
    assertTrue(rawGet(s"/principals/$name").contains("\"tier\":\"gold\""), "SET 应写入属性")

    collect(s"ALTER PRINCIPAL $name SET PROPERTIES ('owner' = 'risk')")
    val merged = rawGet(s"/principals/$name")
    assertTrue(merged.contains("\"tier\":\"gold\""), "第二次 SET 不应丢掉先前的属性: " + merged)
    assertTrue(merged.contains("\"owner\":\"risk\""), merged)

    collect(s"ALTER PRINCIPAL $name UNSET PROPERTIES (tier)")
    val unset = rawGet(s"/principals/$name")
    assertFalse(unset.contains("\"tier\""), "UNSET 应移除属性: " + unset)
    assertTrue(unset.contains("\"owner\":\"risk\""), "UNSET 不应影响其它属性: " + unset)

    // SHOW
    val listed = collect("SHOW PRINCIPALS")
    assertTrue(listed.exists(_.getString(0) == name), "SHOW PRINCIPALS 应包含刚创建的主体")

    // ROTATE：重新签发凭据，与首次的密钥不同
    val rotated = collect(s"ROTATE PRINCIPAL $name")
    assertEquals(1, rotated.length)
    assertNotEquals(firstSecret, rotated(0).getString(2), "轮换后密钥应变化")

    // 清理
    assertTrue(collect(s"DROP PRINCIPAL $name").isEmpty)
    assertFalse(collect("SHOW PRINCIPALS").exists(_.getString(0) == name), "删除后不应再出现在列表里")

    // IF EXISTS 应当吞掉 404
    assertTrue(collect(s"DROP PRINCIPAL IF EXISTS $name").isEmpty)
    val missing = assertThrows(classOf[ManagementApiException], () => collect(s"DROP PRINCIPAL $name"))
    assertTrue(missing.notFound(), "删除不存在的主体应映射为 404，实际: " + missing.status())
  }

  // ------------------------------------------------------------------ 角色脚手架与权限直授

  /**
   * 角色脚手架（principal role / catalog role）与资源授权的完整链路。
   *
   * <p>资源授权这一段走的是**官方权限模型**：`GRANT <access> ON <资源> TO <主体>`
   * 打到 `/v1/{prefix}/permissions/grant`，与「权限管理」页同一个端点、同一套语义。
   * 授权对象是不透明 principal 字符串（`user:` / `role:` / `group:` 前缀），
   * 不再经过 catalog role——因此这里同时覆盖了 `ROLE <名称>` 折成 `role:<名称>`
   * 这一步（折错名字下面的断言会读到空集）。
   *
   * <p><b>catalog role 那半截走管理 API。</b>它的语句已经全部移出语法（控制台也没有页面），
   * 只剩管理 API 一条路，因此建档 / 改属性 / 装配 / 查装配都用
   * {@link io.github.melin.lakehouse.rest.spark.client.ManagementApiClient}——
   * 顺带把「SQL 撤出之后这条链在服务端仍然完整可用」钉在真实服务端上。
   */
  @Test
  def roleScaffoldAndPermissionChainRoundTripThroughRealSql(): Unit = {
    val principalRole = "e2e_reader_role"
    val catalogRole = "e2e_reader_catalog_role"
    val principal = "e2e_reader"
    val admin = LiveSparkSession.managementClient

    // 前一轮可能残留（服务端是内存库，正常不会）
    collect(s"DROP PRINCIPAL IF EXISTS $principal")
    LiveSparkSession.dropCatalogRoleIfPresent("paimon", catalogRole)
    collect(s"DROP PRINCIPAL ROLE IF EXISTS $principalRole")

    collect(s"CREATE PRINCIPAL $principal")
    collect(s"CREATE PRINCIPAL ROLE $principalRole PROPERTIES ('purpose' = 'e2e')")
    admin.createCatalogRole("paimon", catalogRole, null)

    // 角色的 ALTER 同样是读-合并-回写，且连续两次才暴露版本号读错的问题：
    // 第一次读到的版本若被解析成 0，服务端放行并把版本推进到 1，第二次就会 409
    collect(s"ALTER PRINCIPAL ROLE $principalRole SET PROPERTIES ('tier' = 'gold')")
    collect(s"ALTER PRINCIPAL ROLE $principalRole SET PROPERTIES ('owner' = 'risk')")
    val principalRoleJson = rawGet(s"/principal-roles/$principalRole")
    assertTrue(principalRoleJson.contains("\"tier\":\"gold\""),
      "第二次 SET 不应丢掉先前属性: " + principalRoleJson)
    assertTrue(principalRoleJson.contains("\"owner\":\"risk\""), principalRoleJson)

    // catalog role 的 ALTER：同一条「读-合并-回写」路径，只是入口换成了客户端方法
    admin.alterCatalogRoleProperties("paimon", catalogRole,
      Map("tier" -> "gold").asJava, Set.empty[String].asJava)
    admin.alterCatalogRoleProperties("paimon", catalogRole,
      Map("owner" -> "risk").asJava, Set.empty[String].asJava)
    val catalogRoleJson = rawGet(s"/catalogs/paimon/catalog-roles/$catalogRole")
    assertTrue(catalogRoleJson.contains("\"tier\":\"gold\""),
      "第二次 SET 不应丢掉先前属性: " + catalogRoleJson)
    assertTrue(catalogRoleJson.contains("\"owner\":\"risk\""), catalogRoleJson)
    admin.alterCatalogRoleProperties("paimon", catalogRole,
      Map.empty[String, String].asJava, Set("tier").asJava)
    assertFalse(rawGet(s"/catalogs/paimon/catalog-roles/$catalogRole").contains("\"tier\""),
      "UNSET 应移除属性")

    collect(s"ALTER PRINCIPAL ROLE $principalRole UNSET PROPERTIES (tier)")
    assertFalse(rawGet(s"/principal-roles/$principalRole").contains("\"tier\""), "UNSET 应移除属性")

    // 主体 → principal role → catalog role
    collect(s"GRANT PRINCIPAL ROLE $principalRole TO PRINCIPAL $principal")
    admin.grantCatalogRole(principalRole, "paimon", catalogRole)

    assertTrue(collect(s"SHOW PRINCIPAL ROLES FOR PRINCIPAL $principal")
      .exists(_.getString(0) == principalRole), "主体应持有该 principal role")
    assertTrue(collect(s"SHOW PRINCIPALS FOR PRINCIPAL ROLE $principalRole")
      .exists(_.getString(0) == principal), "principal role 应关联该主体")
    assertTrue(admin.listCatalogRolesOf(principalRole, "paimon").asScala.contains(catalogRole),
      "principal role 应持有该 catalog role")

    // ---------------------------------------------------------- 资源授权：官方权限模型
    //
    // 列：0 catalog、1 principal、2 type、3 resource、4 database、5 object_name、
    //     6 columns、7 access、8 expire_time
    val asRole = s"role:$catalogRole"
    def permissions: Array[Row] = collect("SHOW PERMISSIONS IN CATALOG paimon")
    def own: Array[Row] = permissions.filter(_.getString(1) == asRole)
    def pairs(rows: Array[Row]): Set[(String, String)] =
      rows.map(row => (row.getString(2), row.getString(7))).toSet

    // ① catalog 作用域：不写数据库、不写对象名，resource 列回显 catalog 名
    collect(s"GRANT CREATEDATABASE ON CATALOG paimon TO ROLE $catalogRole")
    // ② 后代作用域：一条 access 覆盖该 catalog 下全部（含此后新建的）库、表与视图
    collect(s"GRANT LIST, DESCRIBE, SELECT ON CATALOG_ALL paimon TO ROLE $catalogRole")
    // ③ 表作用域：resource 拼成 db.object
    collect(s"GRANT SELECT ON TABLE default.e2e_orders IN CATALOG paimon TO ROLE $catalogRole")
    // ④ 带有效期：'2030-01-01' 是该日整日有效，即当天 23:59:59.999（本地时区）
    collect(s"GRANT UPDATE ON TABLE default.e2e_orders IN CATALOG paimon "
      + s"TO ROLE $catalogRole UNTIL '2030-01-01'")

    assertEquals(6, own.length, "1 + 3 + 1 + 1 项 access 应各成一条记录")
    assertEquals(
      Set(("CATALOG", "CREATEDATABASE"),
        ("CATALOG_ALL", "LIST"), ("CATALOG_ALL", "DESCRIBE"), ("CATALOG_ALL", "SELECT"),
        ("TABLE", "SELECT"), ("TABLE", "UPDATE")),
      pairs(own))

    val catalogRow = own.find(_.getString(2) == "CATALOG").get
    assertNull(catalogRow.getString(4), "catalog 级授权不带 database")
    assertNull(catalogRow.getString(5), "catalog 级授权不带对象名")
    assertEquals("paimon", catalogRow.getString(3), "catalog 级 resource 列回显 catalog 名")

    val tableRow = own.find(row => row.getString(2) == "TABLE" && row.getString(7) == "SELECT").get
    assertEquals("default.e2e_orders", tableRow.getString(3))
    assertEquals("default", tableRow.getString(4))
    assertEquals("e2e_orders", tableRow.getString(5))
    assertNull(tableRow.getString(6), "非列级授权没有列范围")
    assertNull(tableRow.getString(8), "没写 UNTIL 的授权没有有效期")

    // 有效期落到服务端的值：2030-01-01 当天 23:59:59.999（本地时区）。
    // 用本地时区判定而不是直接比字符串：值的形态依赖运行机器的时区，
    // 而「到某日」的语义与时区无关——它就该是那天最后一毫秒。
    val expires = Instant.parse(own.find(_.getString(8) != null).get.getString(8))
    val zone = ZoneId.systemDefault()
    assertEquals(LocalDate.of(2030, 1, 1), LocalDate.ofInstant(expires, zone),
      "有效期应落在 2030-01-01 当天")
    assertEquals(LocalTime.of(23, 59, 59, 999000000), LocalTime.ofInstant(expires, zone),
      "有效期应是当天最后一毫秒，而不是当天零点")

    // ⑤ 列范围：只有 COLUMN 定位器才带 columns 列，白名单直写列名、黑名单带 EXCEPT 前缀。
    //
    // 更关键的是**重复授予的语义**：赋权身份是 (资源, access, principal) 三元组，
    // 列范围不是身份的一部分，因此对同一个表、同一个 SELECT、同一个主体再授一次
    // 列范围是**整体替换**而不是并存——白名单那一行会被黑名单顶掉，始终只有一条。
    // 写成两条并列的记录是实现最容易出错的地方（列范围合并），这里钉住服务端的实际行为。
    def asUser: Array[Row] = permissions.filter(_.getString(1) == s"user:$principal")

    collect(s"GRANT SELECT ON COLUMN default.e2e_orders (amount, id) IN CATALOG paimon TO USER $principal")
    assertEquals(1, asUser.length, "首条列级授权应只产生一行")
    assertEquals("COLUMN", asUser.head.getString(2))
    assertEquals("default.e2e_orders", asUser.head.getString(3))
    assertEquals("amount, id", asUser.head.getString(6), "白名单直写列名")

    collect(s"GRANT SELECT ON COLUMN default.e2e_orders EXCEPT (card_no) "
      + s"IN CATALOG paimon TO USER $principal")
    assertEquals(1, asUser.length, "同一个赋权身份再授一次是整体替换，不是新增一条")
    assertEquals("EXCEPT card_no", asUser.head.getString(6), "黑名单带 EXCEPT 前缀")

    // ⑥ FOR <授权对象>：过滤发生在服务端，客户端只把 principal 串带过去
    val filtered = collect(s"SHOW PERMISSIONS IN CATALOG paimon FOR ROLE $catalogRole")
    assertEquals(own.length, filtered.length, "FOR ROLE 应只返回该主体的授权")
    assertTrue(filtered.forall(_.getString(1) == asRole))

    // ⑦ 撤销：按赋权身份删整条，同资源上的其它 access 不受影响
    collect(s"REVOKE DESCRIBE ON CATALOG_ALL paimon FROM ROLE $catalogRole")
    assertFalse(pairs(own).contains(("CATALOG_ALL", "DESCRIBE")), "被撤销的 access 不应还在")
    assertTrue(pairs(own).contains(("CATALOG_ALL", "LIST")), "同资源上的其它 access 不应受影响")
    assertTrue(pairs(own).contains(("TABLE", "SELECT")), "别的资源上的授权不应受影响")

    // 撤销是幂等的：服务端对不存在的赋权身份返回成功而不是 404
    collect(s"REVOKE DESCRIBE ON CATALOG_ALL paimon FROM ROLE $catalogRole")
    assertEquals(5, own.length)

    // 列级撤销同样不看列范围——身份里没有它。语法要求 COLUMN 定位器必须带列范围，
    // 因此这里刻意写一个与服务端存的不一样的列名，验证它确实被忽略。
    collect(s"REVOKE SELECT ON COLUMN default.e2e_orders (whatever) "
      + s"IN CATALOG paimon FROM USER $principal")
    assertTrue(asUser.isEmpty, "撤销列级授权时列范围不参与匹配，写什么列都删整条")

    // 清理：把本用例造出来的授权撤干净，并确认真的不剩
    collect(s"REVOKE CREATEDATABASE ON CATALOG paimon FROM ROLE $catalogRole")
    collect(s"REVOKE LIST, SELECT ON CATALOG_ALL paimon FROM ROLE $catalogRole")
    collect(s"REVOKE SELECT, UPDATE ON TABLE default.e2e_orders IN CATALOG paimon FROM ROLE $catalogRole")
    assertTrue(own.isEmpty, "撤销后该 catalog role 名下不应再有任何授权")

    // 清理角色脚手架
    admin.dropCatalogRole("paimon", catalogRole)
    collect(s"DROP PRINCIPAL ROLE $principalRole")
    collect(s"DROP PRINCIPAL $principal")
  }

  @Test
  def catalogDiscoveryAndShowCatalogsAgree(): Unit = {
    val catalogs = collect("SHOW CATALOGS")
    assertTrue(catalogs.exists(_.getString(0) == "paimon"),
      "初始配置预置的 catalog 应出现在 SHOW CATALOGS 中")
  }

  // ------------------------------------------------------------------ 授权由服务端强制

  @Test
  def serverDeniesAPrincipalWithoutAnyGrant(): Unit = {
    // 令牌 limited 经服务端 token-principals 映射到主体 e2e_limited，该主体没有任何授权。
    // 管理主体属于服务管理员的职责范围，因此普通主体应被拒绝。
    val limited = new ManagementApiClient(url.get, "limited", Duration.ofSeconds(10))

    val error = assertThrows(classOf[ManagementApiException], () => limited.listPrincipals())

    assertTrue(error.forbidden(),
      "无授权主体管理主体应被拒绝，实际状态码: " + error.status() + " / " + error.getMessage)
  }
}
