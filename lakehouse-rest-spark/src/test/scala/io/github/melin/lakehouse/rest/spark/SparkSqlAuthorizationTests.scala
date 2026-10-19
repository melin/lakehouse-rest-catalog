package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import org.apache.spark.sql.{Row, SparkSession}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * 「同一个 catalog、同一张表，两种身份」的 Spark SQL 权限验收——**Paimon 与 Iceberg 两种格式各一套**。
 *
 * <p><b>为什么要在 Spark 侧再验一遍。</b>服务端已有两层授权测试
 * （`AuthorizationTests` 验判定逻辑、`CatalogEndpointAuthorizationTests` 验映射覆盖率），
 * 但它们都用 MockMvc 直接打 REST。真正要拦住的是**引擎发出的请求**，而请求由 Paimon 的
 * `SparkCatalog` 生成：「读一张表会用到哪几个端点」「403 会不会被客户端吞掉或改写成别的错」
 * 「拒绝之后数据到底有没有变」这些只有跑真 SQL 才知道。因此本类对着真实服务端跑真实
 * SparkSession，是授权链路上唯一致力于端到端的那一层。
 *
 * <p><b>场景。</b>两个用户，同一个 catalog、同一张表（Iceberg 侧换成另一个 catalog、另一张表，
 * 见下一段）：
 *
 * <ul>
 *   <li>{@link #readWriteUser}（令牌 {@code alice}）：能建库、建表，能读写数据；
 *   <li>{@link #readOnlyUser}（令牌 {@code bob}）：只能读数据——`SELECT` 正常，
 *       而 `INSERT` / `CREATE TABLE` / `CREATE DATABASE` 一律被服务端拒绝。
 * </ul>
 *
 * <p><b>Iceberg 那一套是平行的一份，不是复用。</b>同一个会话里另有一个
 * {@link LiveSparkSession#icebergCatalog}，同一批主体、另一套 catalog role（catalog role
 * 属于某个 catalog，不能跨格式借用），验的语句也换成了 Iceberg 的写法（`CREATE NAMESPACE`、
 * `USING iceberg`、`POST .../namespaces/{ns}/tables/{t}` 的提交）。两套刻意分开写而不是
 * 参数化：差别不在字符串，而在**判定链路**——Paimon 那条走 `/databases/...` 与 `/commit`，
 * Iceberg 那条走 `/namespaces/...` 与协议面的更新提交，权限映射是两组规则
 * （`CatalogAccessRules.tables` 与 `namespaces`）。参数化会把这两件事的差异藏进一个
 * `if` 里，正是本类要证明的东西。
 *
 * <p>由此得到一条**只能从 Iceberg 侧看出来的结论**：写数据需要的 access 比 Paimon 多一项
 * `ALTER`。Paimon 的提交落在 `/tables/{t}/commit`（判定 `TABLE_WRITE_DATA`，
 * 由 `UPDATE` 给出），Iceberg 没有单独的提交端点——数据提交与改表属性共用
 * `POST /namespaces/{ns}/tables/{t}`（判定 `TABLE_WRITE_PROPERTIES`，由 `ALTER` 给出），
 * 而执行端另有一条 `.../metrics` 上报（判定 `TABLE_WRITE_DATA`，由 `UPDATE` 给出）。
 * 判定发生在解析请求体之前，服务端分不清这次 `updates` 是追加数据还是改属性，
 * 因此「能写数据」在 Iceberg 面就是「`UPDATE` + `ALTER`」——两道闸各管一个端点，
 * 缺哪一道都写不进去。见 {@link #readWriteIcebergPermissions}。
 *
 * <p><b>只读身份也必须有读元数据的能力。</b>这是实测出来的约束，不是顺手多给：
 * `SparkCatalog.initialize` 在 Spark **首次**解析到该 catalog 的限定名时懒加载它，
 * 而加载过程会去读默认命名空间 `default` 的属性（`GET /v1/{prefix}/databases/default`）。
 * 缺了它，连第一句 `SELECT` 都到不了表——它与「读不读数据」无关，
 * 属于「用得了这个 catalog」的门槛。只读角色因此是「读元数据 + 读数据」而不是「只有读数据」。
 *
 * <p><b>装配走的是「权限管理」页那条链路。</b>授权语句是
 * {@code GRANT <access> ON <资源> TO ROLE <名称>}——官方权限模型，与页面同一套
 * 语义与同一个端点（{@code /v1/{prefix}/permissions}）。`ROLE <名称>` 折成 principal
 * 字符串 {@code role:<名称>}，判定层再把它解析为主体通过 principal role 成员关系
 * 拿到的分组（见 `AuthorizationService#principalCandidates`）。principal role 因此
 * **只是分组**：它自己不携带任何权限，资源赋权全在
 * `lakehouse_permission_assignment` 那一条三元组上。
 *
 * <p><b>主体 → principal role 的装配走管理 API，不走 SQL。</b>
 * 装配只能调 {@link io.github.melin.lakehouse.rest.spark.client.ManagementApiClient}。
 * 这恰好也让本类顺带守住一件事：管理 API 那条路在自己不受 SQL 支持之后仍然可用。
 *
 * <p><b>身份是怎么来的。</b>两个身份共用 {@link LiveSparkSession#instance} 这一个会话的
 * 两份 SQLConf（见 {@link LiveSparkSession#sessionAs}），差别只有发给服务端的令牌
 * ——Paimon 与 Iceberg 两个 catalog 各换一份，换成同一个令牌。
 * 「谁能做什么」完全由服务端的 RBAC 决定：本模块不认识「用户」，只认识令牌。
 *
 * <p><b>断言锚在服务端自己的措辞上。</b>被拒绝时错误信息里必须出现服务端授权判定产出的
 * `does not have permission`，以及 `principal e2e_bob lacks TABLE_WRITE_DATA` 这类文案——
 * 前者证明拒绝来自本服务端的授权判定（而不是文件系统凭据、网络之类的偶发原因），
 * 后者钉住了「缺的到底是哪一项权限」。少了后半句，一个「碰巧也失败了」的实现照样能过。
 *
 * <p>刻意不锚客户端加的那层壳：Paimon 的 `*NoPermissionException`、Iceberg 的
 * `ForbiddenException` 都是同一个 403 的不同落地方式（见 `assertDenied` 里的说明），
 * 只有服务端原文在两种情况下都稳定存在。
 *
 * <p>默认跳过，需要显式指向一个**同时开了认证与授权、且配了两个令牌**的服务端，
 * 现在的做法是走脚本：
 *
 * <pre>
 * scripts/e2e-spark-sql.sh          # 起服务端（含令牌 alice / bob）并跑本测试
 * </pre>
 *
 * <p><b>本类只验「能不能写」，不验「写得对不对」。</b>被拒绝的写入可能在提交之前已经往
 * 仓库写了数据文件（授权判定发生在 commit 那一步），留下的是无人引用的孤儿文件，
 * 不影响任何查询结果——用例断言的是「数据没变」，不是「仓库里没有多余文件」。
 */
object SparkSqlAuthorizationTests {

  /** 数据面用的 catalog（Paimon）。既是服务端的 prefix，也是 SQL 里的 catalog 限定名。 */
  private[spark] def catalog: String = LiveSparkSession.cata log

  /** 第二个数据面 catalog（Iceberg）。同一批身份，另一套 catalog role（见类注释）。 */
  private[spark] def icebergCatalog: String = LiveSparkSession.icebergCatalog

  /** 有「建库 + 建表 + 读写数据」权限的那一个。令牌 `alice` 由服务端映射到该主体。 */
  private[spark] val readWriteToken = "alice"
  private[spark] val readWriteUser = "e2e_alice"

  /** 只有「读数据」权限的那一个。令牌 `bob` 由服务端映射到该主体。 */
  private[spark] val readOnlyToken = "bob"
  private[spark] val readOnlyUser = "e2e_bob"

  // 装配用的实体名。带前缀是为了不和既有用例（e2e_reader / authz_* 等）撞名。
  private val readWritePrincipalRole = "e2e_authz_rw_role"
  private val readOnlyPrincipalRole = "e2e_authz_ro_role"
  private val readWriteCatalogRole = "e2e_authz_rw"
  private val readOnlyCatalogRole = "e2e_authz_ro"

  // Iceberg 侧的两个 catalog role。catalog role 属于某个 catalog，
  // 因此即使权限集合完全相同也不能复用上面那两个名字——复用会得到 404 或
  // 「角色不在此 catalog 下」，两种都与「没授权」难以区分。
  private val readWriteIcebergCatalogRole = "e2e_authz_rw_ice"
  private val readOnlyIcebergCatalogRole = "e2e_authz_ro_ice"

  /**
   * 两个角色的授权集合，装配完照此自查。
   *
   * <p>断言用 `(资源类型, access)` 而不是 access 一项：`CATALOG_ALL` 与 `CATALOG`
   * 是同一份清单里仅凭 access 无法区分的两条记录，而两者的语义差别很大
   * （前者覆盖后代、后者只覆盖 catalog 自身，`CREATEDATABASE` 只在后者可用）。
   */
  private val readWritePermissions = Set(
    ("CATALOG", "CREATEDATABASE"),
    ("CATALOG_ALL", "LIST"),
    ("CATALOG_ALL", "DESCRIBE"),
    ("CATALOG_ALL", "SELECT"),
    ("CATALOG_ALL", "UPDATE"),
    ("CATALOG_ALL", "CREATETABLE"))
  private val readOnlyPermissions = Set(
    ("CATALOG_ALL", "LIST"),
    ("CATALOG_ALL", "DESCRIBE"),
    ("CATALOG_ALL", "SELECT"))

  /**
   * Iceberg 侧读写角色的授权集合：比 Paimon 那条多一个 `ALTER`。
   *
   * <p>原因见类注释——一次 Iceberg 写入要过两个端点、两种权限：执行端上报指标
   * （`POST .../tables/{t}/metrics`，`TABLE_WRITE_DATA`，由 `UPDATE` 给出）与提交
   * （`POST .../tables/{t}`，`TABLE_WRITE_PROPERTIES`，由 `ALTER` 给出）。
   * Paimon 侧这两件事都在 `/commit` 上，一条 `UPDATE` 就够。少给 `ALTER`，
   * `INSERT` 会在提交那一刻以 `lacks TABLE_WRITE_PROPERTIES` 被拒——本类会当场指出来。
   *
   * <p>只读那一侧两套集合相同，因此不另立一份：读路径在两种格式下落到的是同一组内部权限
   * （`TABLE_READ_PROPERTIES` / `TABLE_LIST` / `NAMESPACE_READ_PROPERTIES`）。
   */
  private val readWriteIcebergPermissions = Set(
    ("CATALOG", "CREATEDATABASE"),
    ("CATALOG_ALL", "LIST"),
    ("CATALOG_ALL", "DESCRIBE"),
    ("CATALOG_ALL", "SELECT"),
    ("CATALOG_ALL", "UPDATE"),
    ("CATALOG_ALL", "ALTER"),
    ("CATALOG_ALL", "CREATETABLE"))

  /**
   * 装配只跑一次。
   *
   * <p>状态放在伴生对象而不是测试类字段里：JUnit 默认**每个用例新建一个测试类实例**，
   * 实例字段记不住「已经装配过」。服务端是内存库、随本次运行一起消失，
   * 因此一次运行内不需要把主体删掉。
   */
  @volatile private var prepared = false

  private[spark] def ensureFixture(): Unit = synchronized {
    if (prepared) {
      return
    }
    // Iceberg 的 prefix 必须先按 ICEBERG 建出来：`/v1/config` 是连接阶段的路由，
    // 那时客户端还没说过自己要哪种格式，自动登记按部署默认值（PAIMON）走，
    // 之后这个 prefix 上的每次 Iceberg 调用都会 404（报文写 `is a PAIMON catalog`）。
    // 放在建角色之前：管理面按名字 require 这个 catalog，不存在就直接 404。
    LiveSparkSession.ensureIcebergCatalog()
    registerDataPlaneCatalog()
    wireIdentities()
    prepared = true
  }

  /**
   * 先让服务端把数据面要用的 catalog 登记出来。
   *
   * <p>顺序不能反：Spark 客户端请求的仓库名（`spark.sql.catalog.<catalog>.warehouse`）
   * 就是它之后要用的 prefix，服务端只有在 `lakehouse.rest.auto-create-catalog=true` 时才
   * 按需登记它。而管理面建 catalog role 之前会按名字 `require` 这个 catalog，
   * 不存在就直接 404——catalog 不先造出来，下一步建角色必然失败。
   *
   * <p>这里带 root 的令牌：`/v1/config` 免的是**授权**（`CatalogAccessRules.isPublic`），
   * 不是认证——开了 `auth.enabled` 之后不带令牌照样 401。
   */
  private def registerDataPlaneCatalog(): Unit = {
    val uri = URI.create(LiveSparkSession.catalogUrl
      + "/v1/config?warehouse=" + LiveSparkSession.catalog)
    val request = HttpRequest.newBuilder(uri)
      .timeout(Duration.ofSeconds(15))
      .header("Authorization", "Bearer " + LiveSparkSession.token)
      .GET().build()
    val response = HttpClient.newHttpClient().send(request,
      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    assertEquals(200, response.statusCode(),
      () => "GET /v1/config 应当把数据面 catalog 登记出来，需要服务端开启 "
        + "lakehouse.rest.auto-create-catalog；实际响应: " + response.body())
  }

  /**
   * 用 root 的语句把两个用户装配好：主体 → principal role → catalog role，再按
   * 官方权限模型给 catalog role 授资源权限。
   *
   * <p>资源授权都落在 **`CATALOG_ALL`**（catalog 及其后代）而不是某个 namespace 上：
   * 两个用户要能看到彼此建出来的库与表，而库名是每次随机生成的，授权不可能事先
   * 写死在某个库上。
   *
   * <p>两个角色的权限故意拉开：读写那边是「建库 + 建表 + 读写数据」，只读那边只有
   * 「看得见 + 读得到」。少给一项，就会有一条用例如实报出缺什么权限。
   *
   * <p><b>装配要做两遍</b>（Paimon 与 Iceberg 各一份 catalog role）。主体与 principal role
   * 是全局的，只有「principal role → catalog role」那一段按 catalog 分——同一个 principal role
   * 在两个 catalog 下各挂一个同名用途的 role，正是 Polaris 那条链的设计。
   */
  private def wireIdentities(): Unit = {
    // catalog role 的建档与装配在 SQL 里已经没有语句了（控制台也没有页面），
    // 只能走管理 API——用客户端，免得在测试里再写一遍路径与请求体形状。
    val admin = LiveSparkSession.managementClient

    // 幂等：正常不会残留（内存库），但对着同一个实例重跑时要能再装配一遍
    rootSql(s"DROP PRINCIPAL IF EXISTS $readWriteUser")
    rootSql(s"DROP PRINCIPAL IF EXISTS $readOnlyUser")
    LiveSparkSession.dropCatalogRoleIfPresent(catalog, readWriteCatalogRole)
    LiveSparkSession.dropCatalogRoleIfPresent(catalog, readOnlyCatalogRole)
    LiveSparkSession.dropCatalogRoleIfPresent(icebergCatalog, readWriteIcebergCatalogRole)
    LiveSparkSession.dropCatalogRoleIfPresent(icebergCatalog, readOnlyIcebergCatalogRole)
    rootSql(s"DROP PRINCIPAL ROLE IF EXISTS $readWritePrincipalRole")
    rootSql(s"DROP PRINCIPAL ROLE IF EXISTS $readOnlyPrincipalRole")

    rootSql(s"CREATE PRINCIPAL $readWriteUser")
    rootSql(s"CREATE PRINCIPAL $readOnlyUser")
    rootSql(s"CREATE PRINCIPAL ROLE $readWritePrincipalRole PROPERTIES ('purpose' = 'e2e-authz')")
    rootSql(s"CREATE PRINCIPAL ROLE $readOnlyPrincipalRole PROPERTIES ('purpose' = 'e2e-authz')")
    admin.createCatalogRole(catalog, readWriteCatalogRole, null)
    admin.createCatalogRole(catalog, readOnlyCatalogRole, null)
    admin.createCatalogRole(icebergCatalog, readWriteIcebergCatalogRole, null)
    admin.createCatalogRole(icebergCatalog, readOnlyIcebergCatalogRole, null)

    // 主体 → principal role；principal role → catalog role（两段都是多对多，要显式装配）
    rootSql(s"GRANT PRINCIPAL ROLE $readWritePrincipalRole TO PRINCIPAL $readWriteUser")
    rootSql(s"GRANT PRINCIPAL ROLE $readOnlyPrincipalRole TO PRINCIPAL $readOnlyUser")
    admin.grantCatalogRole(readWritePrincipalRole, catalog, readWriteCatalogRole)
    admin.grantCatalogRole(readOnlyPrincipalRole, catalog, readOnlyCatalogRole)
    admin.grantCatalogRole(readWritePrincipalRole, icebergCatalog, readWriteIcebergCatalogRole)
    admin.grantCatalogRole(readOnlyPrincipalRole, icebergCatalog, readOnlyIcebergCatalogRole)

    grantReadWrite(catalog, readWriteCatalogRole, extra = "")
    grantReadOnly(catalog, readOnlyCatalogRole)

    // Iceberg 侧多一项 ALTER：数据提交与改属性共用 POST .../tables/{t}，
    // 判定只认 TABLE_WRITE_PROPERTIES。理由见 readWriteIcebergPermissions。
    grantReadWrite(icebergCatalog, readWriteIcebergCatalogRole, extra = ", ALTER")
    grantReadOnly(icebergCatalog, readOnlyIcebergCatalogRole)

    // 自查：装配没生效时（例如两个令牌没配上、或 catalog 名字对不上），后面每个用例都会
    // 以「谁缺什么权限」的报文失败——读起来像权限判定错了。这里先把装配结果读回来，
    // 让环境问题在装配阶段就露出原貌。四个 role 全查，两套权限集合互不相同，
    // 复制粘贴时把 Iceberg 那份写错会在这里就露出来。
    assertEquals(readWritePermissions, permissionsOf(catalog, readWriteCatalogRole),
      "Paimon 读写角色的授权集合与预期不符")
    assertEquals(readOnlyPermissions, permissionsOf(catalog, readOnlyCatalogRole),
      "Paimon 只读角色的授权集合与预期不符")
    assertEquals(readWriteIcebergPermissions, permissionsOf(icebergCatalog, readWriteIcebergCatalogRole),
      "Iceberg 读写角色的授权集合与预期不符")
    assertEquals(readOnlyPermissions, permissionsOf(icebergCatalog, readOnlyIcebergCatalogRole),
      "Iceberg 只读角色的授权集合与预期不符")
  }

  /**
   * 读写用户的资源授权：建库、建表、读写数据。
   *
   * <p>拆成两条语句，起因是官方的 Access/Resource 对照表：`CREATEDATABASE` 只在
   * `CATALOG` 上可用，而 `LIST` / `SELECT` / `UPDATE` / `CREATETABLE` 要覆盖
   * 每次随机生成的库，只能写在 `CATALOG_ALL` 上（`_ALL` = 该资源及其后代，
   * 含此后新建的）。`CATALOG` 上恰好不能授予 LIST/SELECT——写在一起会被服务端按
   * 「该资源类型不允许这个 access」拒掉。
   *
   * <p>官方的 access 词表比内部权限粗：一条 DESCRIBE 同时给出 catalog / namespace /
   * table / view 四层的读属性，一条 LIST 同时给出 namespace / table / view 的列举权。
   * 因此这里写 6 条 access，落到内部判定上是 10 项权限。
   *
   * <p>刻意**不给** DROP：能建、能写不等于能删，删除在官方词表里是单独的
   * `DROP`。这也意味着下面的清理是尽力而为，删不掉是预期行为。
   *
   * @param extra 格式额外的 access（Iceberg 的 `ALTER`，见类注释），Paimon 传空串
   */
  private def grantReadWrite(c: String, role: String, extra: String): Unit = {
    rootSql(s"GRANT CREATEDATABASE ON CATALOG $c TO ROLE $role")
    rootSql(s"GRANT LIST, DESCRIBE, SELECT, UPDATE, CREATETABLE$extra "
      + s"ON CATALOG_ALL $c TO ROLE $role")
  }

  /**
   * 只读用户的资源授权：看得见 + 读得到，别的都不给。
   *
   * <p>读元数据的能力与读数据绑在一起，见类注释——少了它连第一句 SELECT 都到不了表。
   * 两种格式共用这一份：读路径在 Paimon 与 Iceberg 面落到的是同一组内部权限。
   */
  private def grantReadOnly(c: String, role: String): Unit =
    rootSql(s"GRANT LIST, DESCRIBE, SELECT ON CATALOG_ALL $c TO ROLE $role")

  /** 以 root 执行一条管理语句（`LiveSparkSession.instance` 上带着 root 的令牌）。 */
  private def rootSql(statement: String): Unit = {
    LiveSparkSession.instance.sql(statement).collect()
    ()
  }

  /**
   * 用 `SHOW PERMISSIONS` 读回某个 catalog role 的直授，取 `(资源类型, access)` 集合。
   *
   * <p>`FOR ROLE <名称>` —— 与装配时同一个写法，因此这条断言同时钉住了
   * 「`ROLE x` 折成 `role:x`」这一步：折错名字这里会读到空集。
   *
   * @param inCatalog 角色所属的 catalog。catalog role 是 catalog 内的实体，
   *                  查错 catalog 会得到空集（而不是报错），与「忘了授权」长得一样。
   */
  private def permissionsOf(inCatalog: String, catalogRole: String): Set[(String, String)] =
    LiveSparkSession.instance
      .sql(s"SHOW PERMISSIONS IN CATALOG $inCatalog FOR ROLE $catalogRole")
      .collect()
      .map(row => (row.getString(2), row.getString(7)))
      .toSet
}

class SparkSqlAuthorizationTests {

  import SparkSqlAuthorizationTests._

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(LiveSparkSession.isAvailable,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-spark-sql.sh")
    SparkSqlAuthorizationTests.ensureFixture()
  }

  private def sqlAs(session: SparkSession, statement: String): Array[Row] =
    session.sql(statement).collect()

  /** 每个用例独占一个库，避免互相看见对方的表。 */
  private def newDatabase(tag: String): String =
    "authz_" + tag + "_" + UUID.randomUUID().toString.replace("-", "").substring(0, 8)

  /** 用有写权限的那个身份把「一张有数据的表」准备好。 */
  private def prepareTable(session: SparkSession, database: String): Unit = {
    sqlAs(session, s"CREATE DATABASE $catalog.$database")
    sqlAs(session, s"CREATE TABLE $catalog.$database.orders (id BIGINT, v STRING) USING paimon "
      + "TBLPROPERTIES ('primary-key' = 'id', 'bucket' = '1')")
    sqlAs(session, s"INSERT INTO $catalog.$database.orders VALUES (1, 'x'), (2, 'y')")
  }

  /**
   * Iceberg 版的「准备一张有数据的表」。
   *
   * <p>与 Paimon 那份的差别只有建表语法：Iceberg 没有主键 / 分桶这类表属性，
   * `CREATE NAMESPACE` 与 `CREATE DATABASE` 同义（这里用前者，Iceberg 的原生叫法）。
   * 写入本身走协议面的更新提交，而不是 Paimon 的 `/commit`。
   */
  private def prepareIcebergTable(session: SparkSession, namespace: String): Unit = {
    sqlAs(session, s"CREATE NAMESPACE $icebergCatalog.$namespace")
    sqlAs(session, s"CREATE TABLE $icebergCatalog.$namespace.orders (id BIGINT, v STRING) USING iceberg")
    sqlAs(session, s"INSERT INTO $icebergCatalog.$namespace.orders VALUES (1, 'x'), (2, 'y')")
  }

  /**
   * 尽力而为地回收用例自己的库。
   *
   * <p>**删不掉是预期结果**：读写的那个角色刻意没有被授予 `TABLE_DROP` / `NAMESPACE_DROP`
   * ——能建、能写不等于能删。库名每次随机，残留不影响别的用例；服务端是内存库，
   * 整轮跑完一起消失。真正要保住的是「清理失败不能把用例判成失败」。
   *
   * <p>两种格式共用这一份：`DROP DATABASE` 在 Iceberg 的 catalog 上就是 `DROP NAMESPACE`
   * （Spark 把同一个命令交给 catalog 实现），因此不需要为 Iceberg 另写一个清理。
   *
   * @param inCatalog 库所在的 catalog，默认 Paimon 那个（Iceberg 用需要显式传）
   */
  private def dropDatabaseQuietly(session: SparkSession, database: String,
                                  inCatalog: String = catalog): Unit = {
    try {
      sqlAs(session, s"SHOW TABLES IN $inCatalog.$database")
        .map(_.getString(1))
        .foreach { table =>
          try {
            sqlAs(session, s"DROP TABLE IF EXISTS $inCatalog.$database.$table")
          } catch {
            case _: Throwable => ()
          }
        }
    } catch {
      case _: Throwable => ()
    }
    try {
      sqlAs(session, s"DROP DATABASE IF EXISTS $inCatalog.$database")
    } catch {
      case _: Throwable => ()
    }
  }

  /**
   * 跑一段 SQL 并断言它被服务端的授权判定拒绝。
   *
   * @param principal    期望被点名的那个主体
   * @param action       用例描述，失败时用来说明是哪一步
   * @param expectedLack 服务端报文里应当点名的、缺失的那一项权限
   */
  private def assertDenied(principal: String, action: String, expectedLack: String)
                          (body: => Any): Unit = {
    val error = assertThrows(classOf[Throwable], () => { body; () })
    val text = messages(error)

    // 锚点必须取**服务端自己的措辞**（`AuthorizationService.require` 的原文），不能用
    // 客户端加的外壳。原因是同一个 403 有三种落地方式：
    //   - Paimon 走 commit 的写入、以及 DROP 一类操作：被包装成 `TableNoPermissionException`，
    //     外层消息是「Table x has no permission. Cause by <服务端原文>」；
    //   - Paimon 的 `CREATE TABLE` / `CREATE DATABASE`：`RESTCatalog` 不包装，直接抛出
    //     `ForbiddenException`，消息就是服务端原文；
    //   - Iceberg 的 `RESTCatalog`（本类里两条 Iceberg 用例）：一律不包装，直接
    //     `ForbiddenException`，与上一条相同。
    // 只锚「has no permission」会在后两种情况下误报失败，故锚两者的公共部分。
    assertTrue(text.contains("does not have permission"),
      s"$action 应当被拒（应报服务端授权判定原文 does not have permission），实际: $text")

    // 光「失败了」还不够：可能是网络、文件系统权限之类的偶发原因。点名主体与缺失权限，
    // 才能证明拒绝确实来自本服务端的 RBAC 判定。
    assertTrue(text.contains(s"principal $principal lacks $expectedLack"),
      s"$action 的拒绝应当来自本服务端授权判定、并点名 $principal 缺少 $expectedLack，实际: $text")
  }

  /**
   * 变体：一次写入可能被**两个**端点分别拦下，报文点名哪一个取决于哪一步先到。
   *
   * <p>Iceberg 的写入就是这样：执行端在提交前后会 `POST .../tables/{t}/metrics`
   * （判定 `TABLE_WRITE_DATA`，由 `UPDATE` 给出），提交本身走
   * `POST .../tables/{t}`（判定 `TABLE_WRITE_PROPERTIES`，由 `ALTER` 给出）。
   * 两个都是真实闸门，但「哪一个先冒出到异常链上」与 Iceberg 的容错顺序有关——
   * 只钉一个会在换版本时变成假失败。要求「至少点名其中一个」既能压住回归
   * （两个都不给就没得点了），也不会把实现顺序当成契约。
   */
  private def assertDeniedEither(principal: String, action: String, expectedLacks: String*)
                                (body: => Any): Unit = {
    val error = assertThrows(classOf[Throwable], () => { body; () })
    val text = messages(error)
    assertTrue(text.contains("does not have permission"),
      s"$action 应当被拒（应报服务端授权判定原文 does not have permission），实际: $text")
    val named = expectedLacks.filter(p => text.contains(s"principal $principal lacks $p"))
    assertTrue(named.nonEmpty,
      s"$action 的拒绝应当点名 $principal 缺少 ${expectedLacks.mkString(" 或 ")}，实际: $text")
  }

  /** 异常链上每条异常的 message——被 Spark / Paimon 包了几层要看得见。 */
  private def messages(error: Throwable): String =
    Iterator.iterate(error)(_.getCause)
      .takeWhile(_ != null)
      .take(20)
      .map(e => Option(e.getMessage).getOrElse(e.getClass.getSimpleName))
      .mkString(" <- ")

  // ------------------------------------------------------------------ 用户 A：建库建表 + 读写

  /**
   * 读写用户可以完整走一遍：建库 → 建表 → 写入 → 读回。
   *
   * <p>这条同时是权限用例的**对照组**：只有它绿了，B 那边的拒绝才能说明是
   * 「B 少了权限」，而不是「整个链路压根不通」。
   */
  @Test
  def readWriteUserCanCreateDatabaseCreateTableAndWriteThenRead(): Unit = {
    val session = LiveSparkSession.sessionAs(readWriteToken)
    val database = newDatabase("rw")
    try {
      sqlAs(session, s"CREATE DATABASE $catalog.$database")
      sqlAs(session, s"CREATE TABLE $catalog.$database.orders (id BIGINT, v STRING) USING paimon "
        + "TBLPROPERTIES ('primary-key' = 'id', 'bucket' = '1')")
      sqlAs(session, s"INSERT INTO $catalog.$database.orders VALUES (1, 'x'), (2, 'y')")

      val rows = sqlAs(session, s"SELECT id, v FROM $catalog.$database.orders ORDER BY id")
      assertEquals(2, rows.length, "写入的两行应当都能读回来")
      assertEquals(1L, rows(0).getLong(0))
      assertEquals("x", rows(0).getString(1))
      assertEquals(2L, rows(1).getLong(0))
      assertEquals("y", rows(1).getString(1))

      // 建、写、读都有，**但没有删**：DROP 是单独授予的权限，读写角色不该顺带拿到它。
      // 这条同时解释了下面清理为什么可以不成功。
      assertDenied(readWriteUser, "读写用户 DROP TABLE", "TABLE_DROP") {
        sqlAs(session, s"DROP TABLE $catalog.$database.orders")
      }
    } finally {
      dropDatabaseQuietly(session, database)
    }
  }

  // ------------------------------------------------------------------ 用户 B：只能读

  /**
   * 只读用户读得到别人写的数据，但每一次写都在**服务端的授权判定**处被挡下。
   *
   * <p>拒绝的检查分三层，缺一层都压不住回归：
   *
   * <ol>
   *   <li>确实抛错，且报错点名「缺哪一项权限」（见 {@link #assertDenied}）
   *       ——建表缺 `TABLE_CREATE`、建库缺 `NAMESPACE_CREATE`、写数据缺 `TABLE_WRITE_DATA`；
   *   <li>读与「发现」在同一时刻仍然是通的（那次 `SELECT` 与 `SHOW TABLES`）
   *       ——证明拒绝是「这一项没授权」，不是「这个主体整个被关在门外」；
   *   <li>被拒之后数据**没变**——写入真的没生效，而不是「报了错但已经写进去了」。
   * </ol>
   */
  @Test
  def readOnlyUserReadsDataButEveryWriteIsDenied(): Unit = {
    val writer = LiveSparkSession.sessionAs(readWriteToken)
    val database = newDatabase("ro")
    prepareTable(writer, database)
    try {
      val reader = LiveSparkSession.sessionAs(readOnlyToken)

      // ① 读：正常，且看到的是真实数据
      val rows = sqlAs(reader, s"SELECT id, v FROM $catalog.$database.orders ORDER BY id")
      assertEquals(2, rows.length, "只读用户应当能读到全部数据")
      assertEquals(1L, rows(0).getLong(0))
      assertEquals("x", rows(0).getString(1))

      // ② 发现能力：看得见有哪些表（TABLE_LIST），否则「只读」会变成「只有精确知道表名才能读」
      assertTrue(sqlAs(reader, s"SHOW TABLES IN $catalog.$database")
        .exists(_.getString(1) == "orders"), "只读用户应当能看到表列表")

      // ③ 写数据：被拒，缺的是数据写权限
      assertDenied(readOnlyUser, "只读用户 INSERT", "TABLE_WRITE_DATA") {
        sqlAs(reader, s"INSERT INTO $catalog.$database.orders VALUES (3, 'z')")
      }

      // ④ 建表：被拒，缺的是建表权限
      assertDenied(readOnlyUser, "只读用户 CREATE TABLE", "TABLE_CREATE") {
        sqlAs(reader, s"CREATE TABLE $catalog.$database.orders_copy (id BIGINT) USING paimon")
      }

      // ⑤ 建库：被拒，缺的是建命名空间的权限
      assertDenied(readOnlyUser, "只读用户 CREATE DATABASE", "NAMESPACE_CREATE") {
        sqlAs(reader, s"CREATE DATABASE $catalog.${database}_extra")
      }

      // ⑥ 三次被拒之后数据仍是两行：写入没有「报错但半途生效」
      val after = sqlAs(reader, s"SELECT count(*) FROM $catalog.$database.orders")
      assertEquals(2L, after(0).getLong(0), "被拒绝的写入不应改变数据")
    } finally {
      dropDatabaseQuietly(writer, database)
    }
  }

  // ------------------------------------------------------------------ Iceberg：同两个身份，另一条判定链

  /**
   * 读写用户在 **Iceberg 面**走一遍：建 namespace → 建表 → 写入 → 读回。
   *
   * <p>与 Paimon 那条并列，不是重复：这条链上的每个请求都落在 `/v1/{prefix}/namespaces/...`
   * 与协议面的更新提交上，权限映射是 `CatalogAccessRules.namespaces` 那一组规则。
   * 两个 catalog 各有一套 catalog role，因此这条用例同时证明「同一个主体在两种格式下
   * 各有一份授权、互不影响」。
   *
   * <p>`DROP` 仍然被拒（缺 `TABLE_DROP`）——这既是对照组，也解释了清理为什么可以失败。
   */
  @Test
  def readWriteUserCanCreateIcebergNamespaceTableAndWriteThenRead(): Unit = {
    val session = LiveSparkSession.sessionAs(readWriteToken)
    val namespace = newDatabase("ice_rw")
    try {
      sqlAs(session, s"CREATE NAMESPACE $icebergCatalog.$namespace")
      sqlAs(session, s"CREATE TABLE $icebergCatalog.$namespace.orders (id BIGINT, v STRING) USING iceberg")
      sqlAs(session, s"INSERT INTO $icebergCatalog.$namespace.orders VALUES (1, 'x'), (2, 'y')")

      val rows = sqlAs(session,
        s"SELECT id, v FROM $icebergCatalog.$namespace.orders ORDER BY id")
      assertEquals(2, rows.length, "Iceberg 表写入的两行应当都能读回来")
      assertEquals(1L, rows(0).getLong(0))
      assertEquals("x", rows(0).getString(1))
      assertEquals(2L, rows(1).getLong(0))
      assertEquals("y", rows(1).getString(1))

      // 表真的登记在这个 namespace 下（走 GET /namespaces/{ns}/tables）
      assertTrue(sqlAs(session, s"SHOW TABLES IN $icebergCatalog.$namespace")
        .exists(_.getString(1) == "orders"), "建好的 Iceberg 表应当出现在表列表里")

      // 有建、有写、有读，但**没有删**：DROP 在官方词表里是单独的 access。
      assertDenied(readWriteUser, "读写用户 DROP TABLE（Iceberg）", "TABLE_DROP") {
        sqlAs(session, s"DROP TABLE $icebergCatalog.$namespace.orders")
      }
    } finally {
      dropDatabaseQuietly(session, namespace, icebergCatalog)
    }
  }

  /**
   * 只读用户在 Iceberg 面读得到数据，三次写各自在对应的权限上被拒。
   *
   * <p><b>与 Paimon 那条的最大差别是「写数据缺哪一项」</b>：Paimon 只有一道闸
   * （`/tables/{t}/commit` 判定 `TABLE_WRITE_DATA`，由 `UPDATE` 给出），
   * Iceberg 有两道（`.../metrics` 判 `TABLE_WRITE_DATA`、`POST .../tables/{t}` 判
   * `TABLE_WRITE_PROPERTIES`，后者由 `ALTER` 给出）。这不是实现差异，是两种协议对
   * 「提交」与「改属性」的建模差异：Iceberg 用同一个端点承载两类 `updates`。
   * 用例把这条钉住——哪天有人给 Iceberg 的提交端点换一套判定，这条会以
   * 「缺的权限名不在预期集合里」失败，而不是静默放宽。
   */
  @Test
  def readOnlyUserReadsIcebergDataButEveryWriteIsDenied(): Unit = {
    val writer = LiveSparkSession.sessionAs(readWriteToken)
    val namespace = newDatabase("ice_ro")
    prepareIcebergTable(writer, namespace)
    try {
      val reader = LiveSparkSession.sessionAs(readOnlyToken)

      val rows = sqlAs(reader,
        s"SELECT id, v FROM $icebergCatalog.$namespace.orders ORDER BY id")
      assertEquals(2, rows.length, "只读用户应当能读到 Iceberg 表的全部数据")
      assertEquals(1L, rows(0).getLong(0))
      assertEquals("x", rows(0).getString(1))

      assertTrue(sqlAs(reader, s"SHOW TABLES IN $icebergCatalog.$namespace")
        .exists(_.getString(1) == "orders"), "只读用户应当能看到 Iceberg 的表列表")

      // 写数据：Iceberg 一次写入要过两道闸——指标上报（TABLE_WRITE_DATA，来自 UPDATE）
      // 与提交（TABLE_WRITE_PROPERTIES，来自 ALTER），见 assertDeniedEither
      assertDeniedEither(readOnlyUser, "只读用户 INSERT（Iceberg）",
        "TABLE_WRITE_DATA", "TABLE_WRITE_PROPERTIES") {
        sqlAs(reader, s"INSERT INTO $icebergCatalog.$namespace.orders VALUES (3, 'z')")
      }

      assertDenied(readOnlyUser, "只读用户 CREATE TABLE（Iceberg）", "TABLE_CREATE") {
        sqlAs(reader,
          s"CREATE TABLE $icebergCatalog.$namespace.orders_copy (id BIGINT) USING iceberg")
      }

      assertDenied(readOnlyUser, "只读用户 CREATE NAMESPACE（Iceberg）", "NAMESPACE_CREATE") {
        sqlAs(reader, s"CREATE NAMESPACE $icebergCatalog.${namespace}_extra")
      }

      val after = sqlAs(reader, s"SELECT count(*) FROM $icebergCatalog.$namespace.orders")
      assertEquals(2L, after(0).getLong(0), "被拒绝的写入不应改变 Iceberg 表的数据")
    } finally {
      dropDatabaseQuietly(writer, namespace, icebergCatalog)
    }
  }
}
