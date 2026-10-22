package io.github.melin.lakehouse.rest.spark.client

import io.github.melin.lakehouse.rest.spark.support.{RecordedRequest, StubManagementServer}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{BeforeEach, Test}

import java.net.ServerSocket
import java.time.Duration

import scala.collection.JavaConverters._

/**
 * REST 客户端测试。
 *
 * <p>桩服务端见 {@link StubManagementServer}。这里验证两件容易写错、且写错了只会
 * 在真实服务端上暴露的事：请求的**方法与路径**、请求体的**字段形状**——
 * 主体与角色那部分必须与 {@code spec/polaris-management-service.yml} 一致，
 * 直授那部分必须与 Paimon 官方权限端点（{@code /v1/{prefix}/permissions}，
 * 服务端实现见 {@code PermissionDtos}）一致。
 */
object ManagementApiClientTests {

  /**
   * 整类共用一个桩服务端。
   *
   * <p>放在伴生对象里而不是实例字段：JUnit 每个测试方法都会新建一次测试实例，
   * 写在实例字段上会重复起服务端。
   */
  lazy val stub: StubManagementServer = {
    val server = StubManagementServer.start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop()))
    server
  }
}

class ManagementApiClientTests {

  private val stub = ManagementApiClientTests.stub

  @BeforeEach
  def resetStub(): Unit = stub.reset()

  private def client(token: String = null): ManagementApiClient =
    new ManagementApiClient(stub.baseUrl, token, Duration.ofSeconds(5))

  /** 空属性集合。不用 `Map.of()`：零参泛型重载在 Scala 里推断不稳定。 */
  private def noProperties(): java.util.Map[String, String] =
    new java.util.LinkedHashMap[String, String]()

  // ------------------------------------------------------------------ 列表与读取

  @Test
  def listCatalogsReadsTheCatalogsArray(): Unit = {
    stub.responder = (_: RecordedRequest) => (200,
      """{"catalogs":[{"name":"paimon","type":"INTERNAL","properties":{"owner":"lake"},
        |"entityVersion":3}]}""".stripMargin)

    val catalogs = client().listCatalogs()

    assertEquals(1, catalogs.size())
    assertEquals("paimon", catalogs.get(0).name())
    assertEquals("INTERNAL", catalogs.get(0).`type`())
    assertEquals("lake", catalogs.get(0).properties().get("owner"))
    assertEquals(3, catalogs.get(0).entityVersion().intValue())
    assertEquals("GET", stub.last.method)
    assertEquals("/api/management/v1/catalogs", stub.last.path)
  }

  @Test
  def listPrincipalsReadsThePrincipalsArray(): Unit = {
    stub.responder = (_: RecordedRequest) => (200,
      """{"principals":[{"name":"analyst","clientId":"c1","properties":{},"entityVersion":2}]}""")

    val principals = client().listPrincipals()

    assertEquals("analyst", principals.get(0).name())
    assertEquals("c1", principals.get(0).clientId())
    assertEquals("/api/management/v1/principals", stub.last.path)
  }

  @Test
  def listGrantsReadsThePerTypeObjectNameField(): Unit = {
    // 规格里各资源类型的对象名字段名不同：table 用 tableName，view 用 viewName
    stub.responder = (_: RecordedRequest) => (200,
      """{"grants":[
        |{"type":"catalog","privilege":"CATALOG_MANAGE_CONTENT"},
        |{"type":"namespace","namespace":["raw"],"privilege":"NAMESPACE_CREATE"},
        |{"type":"table","namespace":["raw","events"],"tableName":"orders",
        | "privilege":"TABLE_READ_DATA"},
        |{"type":"view","namespace":["raw"],"viewName":"v1","privilege":"VIEW_READ_DATA"}]}
        |""".stripMargin)

    val grants = client().listGrants("paimon", "reader")

    assertEquals(4, grants.size())
    assertEquals("catalog", grants.get(0).`type`())
    assertTrue(grants.get(0).namespace().isEmpty)
    assertNull(grants.get(0).objectName())
    assertEquals(Seq("raw").asJava, grants.get(1).namespace())
    assertEquals("orders", grants.get(2).objectName())
    assertEquals(Seq("raw", "events").asJava, grants.get(2).namespace())
    assertEquals("v1", grants.get(3).objectName())
    assertEquals("/api/management/v1/catalogs/paimon/catalog-roles/reader/grants", stub.last.path)
  }

  // ------------------------------------------------------------------ 写入

  @Test
  def createPrincipalPostsNameAndProperties(): Unit = {
    stub.responder = (_: RecordedRequest) => (201,
      """{"principal":{"name":"analyst"},
        |"credentials":{"clientId":"c1","clientSecret":"s3cr3t"}}""".stripMargin)

    val credentials = client().createPrincipal("analyst",
      Map("owner" -> "risk", "tier" -> "gold").asJava)

    assertEquals("analyst", credentials.name())
    assertEquals("c1", credentials.clientId())
    assertEquals("s3cr3t", credentials.clientSecret())
    assertEquals("POST", stub.last.method)
    assertEquals("/api/management/v1/principals", stub.last.path)
    assertTrue(stub.last.body.contains("\"name\":\"analyst\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"owner\":\"risk\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"tier\":\"gold\""), stub.last.body)
  }

  @Test
  def createPrincipalOmitsPropertiesWhenEmpty(): Unit = {
    stub.responder = (_: RecordedRequest) =>
      (201, """{"principal":{"name":"analyst"},"credentials":{}}""")

    client().createPrincipal("analyst", noProperties())

    assertFalse(stub.last.body.contains("properties"), stub.last.body)
  }

  @Test
  def resetAndRotateHitDifferentEndpoints(): Unit = {
    // 规格里这两条 POST 成功返回 200（更新凭据），不是 201
    stub.responder = (_: RecordedRequest) =>
      (200, """{"principal":{"name":"analyst"},"credentials":{"clientId":"c"}}""")

    client().resetPrincipal("analyst", false)
    assertEquals("/api/management/v1/principals/analyst/reset", stub.last.path)
    assertEquals("POST", stub.last.method)

    client().resetPrincipal("analyst", true)
    assertEquals("/api/management/v1/principals/analyst/rotate", stub.last.path)
  }

  @Test
  def resetPrincipalRejectsA201BecauseTheSpecSays200(): Unit = {
    // 锁住规格里的状态码：写错成 201 时应当立刻暴露，而不是静默接受
    stub.responder = (_: RecordedRequest) =>
      (201, """{"principal":{"name":"analyst"},"credentials":{"clientId":"c"}}""")

    val error = assertThrows(classOf[ManagementApiException], () =>
      client().resetPrincipal("analyst", true))

    assertEquals(201, error.status())
  }

  @Test
  def dropPrincipalUsesDeleteAndExpects204(): Unit = {
    stub.responder = (_: RecordedRequest) => (204, "")

    client().dropPrincipal("analyst")

    assertEquals("DELETE", stub.last.method)
    assertEquals("/api/management/v1/principals/analyst", stub.last.path)
  }

  /**
   * ALTER 的语义差：规格的 PUT 是「整体替换 properties」，
   * 而 SQL 的 SET PROPERTIES 是增量。客户端必须做读-合并-回写。
   *
   * <p>响应形状按规格：GET 与 PUT {@code /principals/{n}} 都是**裸** {@code Principal}，
   * 只有 {@code POST /principals} 才返回 {@code {principal, credentials}}。
   */
  @Test
  def alterPrincipalMergesPropertiesInsteadOfReplacingThem(): Unit = {
    stub.responder = (request: RecordedRequest) => request.method match {
      case "GET" => (200,
        """{"name":"analyst","clientId":"c1","properties":{"owner":"risk","tier":"gold"},
          |"entityVersion":7}""".stripMargin)
      case _ => (200,
        """{"name":"analyst","clientId":"c1","properties":{"tier":"silver","team":"quant"},
          |"entityVersion":8}""".stripMargin)
    }

    client().alterPrincipalProperties("analyst",
      Map("tier" -> "silver", "team" -> "quant").asJava, Set("owner").asJava)

    val put = stub.requestsOf("PUT")
    assertEquals(1, put.size, "应当只发一次 PUT")
    assertTrue(put.head.body.contains("\"currentEntityVersion\":7"),
      "回写必须带上读取到的 entityVersion: " + put.head.body)
    // owner 被移除、tier 被覆盖、team 新增——三者都体现在同一个请求体里
    assertFalse(put.head.body.contains("\"owner\""), put.head.body)
    assertTrue(put.head.body.contains("\"tier\":\"silver\""), put.head.body)
    assertTrue(put.head.body.contains("\"team\":\"quant\""), put.head.body)
  }

  // ------------------------------------------------------------------ 响应体形状

  /**
   * 单资源读取必须按规格的**裸对象**形状解析。
   *
   * <p>多读一层外壳不会抛异常，只会静默得到全空字段与 {@code entityVersion=0}——
   * 后者的后果是下一次 PUT 因版本不符拿到 409。这类错误桩测试与真实服务端都能发现，
   * 因此这里逐字段断言。
   */
  @Test
  def principalReadsUseTheBareResponseShape(): Unit = {
    stub.responder = (_: RecordedRequest) => (200,
      """{"name":"analyst","clientId":"cid-9","properties":{"tier":"gold"},"entityVersion":7}""")

    val principal = client().getPrincipal("analyst")

    assertEquals("analyst", principal.name())
    assertEquals("cid-9", principal.clientId())
    assertEquals("gold", principal.properties().get("tier"))
    assertEquals(7, principal.entityVersion().intValue())
  }

  @Test
  def principalRoleReadsUseTheBareResponseShape(): Unit = {
    stub.responder = (_: RecordedRequest) => (201,
      """{"name":"data_engineer","federated":false,"properties":{"purpose":"etl"},
        |"entityVersion":3}""".stripMargin)

    val created = client().createPrincipalRole("data_engineer", null)

    assertEquals("data_engineer", created.name())
    assertEquals("etl", created.properties().get("purpose"))
    assertEquals(3, created.entityVersion().intValue())

    stub.responder = (_: RecordedRequest) => (200,
      """{"name":"data_engineer","federated":true,"properties":{},"entityVersion":4}""")

    val fetched = client().getPrincipalRole("data_engineer")

    assertEquals("data_engineer", fetched.name())
    assertTrue(fetched.federated())
    assertEquals(4, fetched.entityVersion().intValue())
  }

  @Test
  def catalogRoleReadsUseTheBareResponseShape(): Unit = {
    stub.responder = (_: RecordedRequest) => (201,
      """{"name":"reader","properties":{"scope":"north"},"entityVersion":2}""")

    val created = client().createCatalogRole("paimon", "reader", null)

    assertEquals("reader", created.name())
    assertEquals("north", created.properties().get("scope"))
    assertEquals(2, created.entityVersion().intValue())

    stub.responder = (_: RecordedRequest) => (200,
      """{"name":"reader","properties":{},"entityVersion":5}""")

    assertEquals(5, client().getCatalogRole("paimon", "reader").entityVersion().intValue())
  }

  @Test
  def alterRolePropertiesSendTheVersionReadFromTheBareObject(): Unit = {
    stub.responder = (request: RecordedRequest) =>
      if (request.method == "GET") {
        (200, """{"name":"r","properties":{"a":"1"},"entityVersion":9}""")
      } else {
        (200, """{"name":"r","properties":{"a":"1","b":"2"},"entityVersion":10}""")
      }

    client().alterPrincipalRoleProperties("data_engineer",
      Map("b" -> "2").asJava, Set.empty[String].asJava)
    val rolePut = stub.requestsOf("PUT").last
    assertEquals("/api/management/v1/principal-roles/data_engineer", rolePut.path)
    assertTrue(rolePut.body.contains("\"currentEntityVersion\":9"), rolePut.body)

    client().alterCatalogRoleProperties("paimon", "reader",
      Map("b" -> "2").asJava, Set.empty[String].asJava)
    val catalogRolePut = stub.requestsOf("PUT").last
    assertEquals("/api/management/v1/catalogs/paimon/catalog-roles/reader", catalogRolePut.path)
    assertTrue(catalogRolePut.body.contains("\"currentEntityVersion\":9"), catalogRolePut.body)
    // 增量合并：原有属性 a 被保留
    assertTrue(catalogRolePut.body.contains("\"a\":\"1\""), catalogRolePut.body)
  }

  @Test
  def listResponsesUseTheirNamedArrayFields(): Unit = {
    // 列表接口统一是具名数组；roles 同时服务于 principal role 与 catalog role 列表
    stub.responder = (request: RecordedRequest) => request.path match {
      case p if p.endsWith("/principals") =>
        (200, """{"principals":[{"name":"a","clientId":"c","properties":{},"entityVersion":1}]}""")
      case p if p.endsWith("/principal-roles") =>
        (200, """{"roles":[{"name":"r1","federated":false,"properties":{},"entityVersion":1}]}""")
      case _ =>
        (200, """{"roles":[{"name":"r2","properties":{},"entityVersion":1}]}""")
    }

    assertEquals("a", client().listPrincipals().get(0).name())
    assertEquals("r1", client().listPrincipalRoles().get(0).name())
    assertEquals("r2", client().listCatalogRoles("paimon").get(0).name())
  }

  @Test
  def grantRoleUsesPutAndRevokeUsesDelete(): Unit = {
    // 授权是新资源（201），撤销没有响应体（204）
    stub.responder = (request: RecordedRequest) =>
      if (request.method == "DELETE") (204, "") else (201, "{}")

    client().grantPrincipalRole("analyst", "data_engineer")
    assertEquals("PUT", stub.last.method)
    assertEquals("/api/management/v1/principals/analyst/principal-roles", stub.last.path)
    assertTrue(stub.last.body.contains("\"principalRole\""), stub.last.body)

    client().revokePrincipalRole("analyst", "data_engineer")
    assertEquals("DELETE", stub.last.method)
    assertEquals("/api/management/v1/principals/analyst/principal-roles/data_engineer", stub.last.path)
  }

  @Test
  def grantCatalogRoleCarriesTheCatalogInThePath(): Unit = {
    stub.responder = (_: RecordedRequest) => (201, "{}")

    client().grantCatalogRole("data_engineer", "paimon", "reader")

    assertEquals("PUT", stub.last.method)
    assertEquals("/api/management/v1/principal-roles/data_engineer/catalog-roles/paimon", stub.last.path)
    assertTrue(stub.last.body.contains("\"catalogRole\""), stub.last.body)
  }

  // ------------------------------------------------------------------ 直授（官方权限模型）

  @Test
  def grantPermissionPostsTheLocatorAccessPrincipalAndExpiry(): Unit = {
    stub.responder = (_: RecordedRequest) => (201, "")

    client().grantPermission("paimon",
      ManagementApiClient.PermissionResource.table("sales", "orders"),
      "SELECT", "user:analyst", "2026-11-30T15:59:59.999Z")

    assertEquals("POST", stub.last.method)
    // 权限端点在 catalog API 下（/v1/…），与管理 API 是两个前缀
    assertEquals("/v1/paimon/permissions/grant", stub.last.path)
    assertTrue(stub.last.body.contains("\"type\":\"TABLE\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"database\":\"sales\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"table\":\"orders\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"access\":\"SELECT\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"principal\":\"user:analyst\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"expireTime\":\"2026-11-30T15:59:59.999Z\""),
      stub.last.body)
  }

  @Test
  def catalogScopePermissionCarriesNeitherDatabaseNorObjectName(): Unit = {
    stub.responder = (_: RecordedRequest) => (201, "")

    client().grantPermission("paimon", ManagementApiClient.PermissionResource.catalogAll(),
      "LIST", "role:analyst", null)

    assertTrue(stub.last.body.contains("\"type\":\"CATALOG_ALL\""), stub.last.body)
    // catalog 由请求路径里的 prefix 决定，定位符里不该再出现一次
    assertFalse(stub.last.body.contains("database"), stub.last.body)
    assertFalse(stub.last.body.contains("table"), stub.last.body)
    // 长期有效：请求体里不出现 expireTime（省略即不过期）
    assertFalse(stub.last.body.contains("expireTime"), stub.last.body)
  }

  @Test
  def viewAndFunctionLocatorsUseTheirOwnFieldNames(): Unit = {
    stub.responder = (_: RecordedRequest) => (201, "")

    client().grantPermission("paimon",
      ManagementApiClient.PermissionResource.view("sales", "v_orders"), "SELECT", "user:analyst", null)
    assertTrue(stub.last.body.contains("\"view\":\"v_orders\""), stub.last.body)
    assertFalse(stub.last.body.contains("\"table\""), stub.last.body)

    client().grantPermission("paimon",
      ManagementApiClient.PermissionResource.function("sales", "f_clean"), "SELECT", "user:analyst", null)
    assertTrue(stub.last.body.contains("\"function\":\"f_clean\""), stub.last.body)
  }

  @Test
  def columnPermissionCarriesExactlyOneColumnList(): Unit = {
    stub.responder = (_: RecordedRequest) => (201, "")

    client().grantPermission("paimon",
      ManagementApiClient.PermissionResource.column("sales", "orders",
        java.util.List.of("amount", "id"), java.util.Collections.emptyList[String]()),
      "SELECT", "user:analyst", null)

    assertTrue(stub.last.body.contains("\"type\":\"COLUMN\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"table\":\"orders\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"columnNames\":[\"amount\",\"id\"]"), stub.last.body)
    assertFalse(stub.last.body.contains("excludedColumnNames"), stub.last.body)

    client().grantPermission("paimon",
      ManagementApiClient.PermissionResource.column("sales", "orders",
        java.util.Collections.emptyList[String](),
        java.util.Collections.singletonList("card_no")),
      "SELECT", "user:analyst", null)

    assertTrue(stub.last.body.contains("\"excludedColumnNames\":[\"card_no\"]"), stub.last.body)
    assertFalse(stub.last.body.contains("\"columnNames\""), stub.last.body)
  }

  @Test
  def revokePermissionPostsToRevokeWithoutAnExpiry(): Unit = {
    stub.responder = (_: RecordedRequest) => (201, "")

    client().revokePermission("paimon",
      ManagementApiClient.PermissionResource.database("sales"), "ALTER", "group:eng")

    assertEquals("POST", stub.last.method)
    assertEquals("/v1/paimon/permissions/revoke", stub.last.path)
    assertTrue(stub.last.body.contains("\"type\":\"DATABASE\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"access\":\"ALTER\""), stub.last.body)
    assertTrue(stub.last.body.contains("\"principal\":\"group:eng\""), stub.last.body)
    assertFalse(stub.last.body.contains("expireTime"), stub.last.body)
  }

  @Test
  def listPermissionsReadsTheAssignmentsAndFiltersByPrincipal(): Unit = {
    stub.responder = (_: RecordedRequest) => (200,
      """{"assignments":[
        |{"resource":{"type":"TABLE","database":"sales","table":"orders"},"access":"SELECT",
        | "principal":"user:analyst","expireTime":"2026-11-30T15:59:59.999Z"},
        |{"resource":{"type":"COLUMN","database":"sales","table":"orders"},
        | "access":"SELECT","principal":"role:reader",
        | "columns":{"excludedColumnNames":["card_no"]}}]}""".stripMargin)

    val entries = client().listPermissions("paimon", "user:analyst")

    assertEquals(2, entries.size())
    assertEquals("TABLE", entries.get(0).resource().`type`())
    assertEquals("sales", entries.get(0).resource().database())
    assertEquals("orders", entries.get(0).resource().objectName())
    assertEquals("SELECT", entries.get(0).access())
    assertEquals("user:analyst", entries.get(0).principal())
    assertEquals("2026-11-30T15:59:59.999Z", entries.get(0).expireTime())
    assertNull(entries.get(1).expireTime(), "列表里没有 expireTime 表示长期有效")
    assertEquals(Seq("card_no").asJava, entries.get(1).resource().excludedColumns())

    // 主体过滤与页大小写在查询串里，`:` 要编码
    assertEquals("/v1/paimon/permissions", stub.last.path)
    assertTrue(stub.last.query.contains("principal=user%3Aanalyst"), stub.last.query)
  }

  @Test
  def listPermissionsFollowsPageTokens(): Unit = {
    stub.responder = (request: RecordedRequest) =>
      if (request.query.contains("pageToken=")) {
        (200, """{"assignments":[{"resource":{"type":"CATALOG"},"access":"ALTER",
                 |"principal":"user:root"}]}""".stripMargin)
      } else {
        (200, """{"assignments":[{"resource":{"type":"CATALOG"},"access":"GRANT",
                 |"principal":"user:root"}],"nextPageToken":"p2"}""".stripMargin)
      }

    val entries = client().listPermissions("paimon", null)

    // 下一页令牌要跟到底：SHOW 的语义是「列出全部」，静默截断比多几次请求糟糕
    assertEquals(2, entries.size())
    assertEquals(Seq("GRANT", "ALTER"), entries.asScala.map(_.access()).toSeq)
    assertEquals(2, stub.requestsOf("GET").size, "两次 GET：第一页与第二页")
  }

  @Test
  def theCatalogApiBaseUrlIsDerivedFromTheManagementBaseUrl(): Unit = {
    stub.responder = (_: RecordedRequest) => (200, """{"assignments":[]}""")

    val derived = client()
    assertTrue(derived.permissionBaseUrlOptional.isPresent)
    assertEquals(stub.baseUrl.replace("/api/management/v1", "/v1"),
      derived.permissionBaseUrlOptional.get())

    // 也可以用四参构造显式指定（服务部署在非标准路径下时）
    val explicit = new ManagementApiClient(stub.baseUrl, null,
      "http://127.0.0.1:9/custom/v1", Duration.ofSeconds(5))
    assertEquals("http://127.0.0.1:9/custom/v1", explicit.permissionBaseUrlOptional.get())
  }

  @Test
  def permissionCallsExplainWhichConfigKeyFixesAnUnderivableBaseUrl(): Unit = {
    // 管理 API 不在标准路径下时推导不出 catalog API 基址：报错要指向配置项，
    // 而不是发一个一定 404 的请求
    val custom = new ManagementApiClient("http://127.0.0.1:9/custom/management", null,
      Duration.ofSeconds(2))

    val error = assertThrows(classOf[ManagementApiException], () =>
      custom.listPermissions("paimon", null))

    assertTrue(error.getMessage.contains("spark.lakehouse.rest.catalog.url"), error.getMessage)
    assertEquals(0, error.status(), "没发出请求，状态码为 0")
  }

  // ------------------------------------------------------------------ 编码与认证

  @Test
  def pathSegmentsAreUrlEncodedWithoutFormEncoding(): Unit = {
    stub.responder = (_: RecordedRequest) => (200, """{"name":"a b/c","clientId":"c1"}""")

    client().getPrincipal("a b/c")

    // 空格必须是 %20 而不是 +（URLEncoder 的 form 编码会给出 +，在路径里是错的）
    assertEquals("/api/management/v1/principals/a%20b%2Fc", stub.last.path)
  }

  @Test
  def tokenIsSentWhenConfiguredAndOmittedOtherwise(): Unit = {
    stub.responder = (_: RecordedRequest) => (200, """{"catalogs":[]}""")

    client().listCatalogs()
    assertNull(stub.last.authorization, "未配置令牌时不应发送 Authorization 头")

    client(token = "t0ken").listCatalogs()
    assertEquals("Bearer t0ken", stub.last.authorization)
  }

  @Test
  def blankTokenIsTreatedAsAbsent(): Unit = {
    stub.responder = (_: RecordedRequest) => (200, """{"catalogs":[]}""")

    client(token = "   ").listCatalogs()

    assertNull(stub.last.authorization)
    assertFalse(client(token = "   ").tokenOptional().isPresent)
  }

  // ------------------------------------------------------------------ 错误映射

  @Test
  def notFoundAndConflictAreRecognisableFromTheStatus(): Unit = {
    stub.responder = (_: RecordedRequest) =>
      (404, """{"type":"NotFound","message":"principal does not exist"}""")
    val missing = assertThrows(classOf[ManagementApiException], () => client().getPrincipal("ghost"))
    assertTrue(missing.notFound())
    assertEquals(404, missing.status())
    assertEquals("principal does not exist", missing.getMessage)

    stub.responder = (_: RecordedRequest) =>
      (409, """{"type":"AlreadyExists","message":"principal already exists"}""")
    val existing = assertThrows(classOf[ManagementApiException], () =>
      client().createPrincipal("analyst", noProperties()))
    assertTrue(existing.alreadyExists())
    assertEquals(409, existing.status())

    stub.responder = (_: RecordedRequest) =>
      (403, """{"type":"Forbidden","message":"not authorized"}""")
    val forbidden = assertThrows(classOf[ManagementApiException], () => client().listPrincipals())
    assertTrue(forbidden.forbidden())
    assertEquals(403, forbidden.status())
  }

  @Test
  def errorMessageIsTakenFromTheResponseBody(): Unit = {
    stub.responder = (_: RecordedRequest) =>
      (409, """{"type":"AlreadyExists","message":"principal 'analyst' already exists"}""")

    val error = assertThrows(classOf[ManagementApiException], () =>
      client().createPrincipal("analyst", noProperties()))

    assertEquals("principal 'analyst' already exists", error.getMessage)
    assertTrue(error.describe().contains("POST"), error.describe())
  }

  @Test
  def unreachableServerIsReportedWithStatusZero(): Unit = {
    // 取一个刚被释放的端口：连接会被立刻拒绝
    val probe = new ServerSocket(0)
    val port = probe.getLocalPort
    probe.close()
    val dead = new ManagementApiClient(
      s"http://127.0.0.1:$port/api/management/v1", null, Duration.ofSeconds(2))

    val error = assertThrows(classOf[ManagementApiException], () => dead.listCatalogs())

    assertEquals(0, error.status())
    assertFalse(error.notFound())
  }
}
