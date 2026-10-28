package io.github.melin.lakehouse.rest.spark

import com.fasterxml.jackson.databind.{JsonNode, ObjectMapper}
import org.apache.spark.sql.{DataFrame, Row}
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.{AfterEach, Assumptions, BeforeEach, Test}

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID
import scala.collection.JavaConverters._

/**
 * Lance 协议面（`/lance/v1/...`）的 Spark 端到端验收。
 *
 * <p><b>为什么必须有一个真的 Lance 客户端。</b>服务端那几组 Lance 测试全是 MockMvc：
 * 它们断言「我们按自己的理解回了一份规范的报文」。而这一面最容易错的恰好是**报文形状**
 * 与**客户端实际怎么发**之间的错位——第一次跑起来就抓到三处，三处都是服务端视角看不出来的：
 *
 * <ul>
 *   <li>请求体里的 {@code id}：规范定义是**层级数组**（{@code ["cat","schema"]}），
 *       Spark connector 发的就是数组；服务端原先按 {@code String} 绑定，于是
 *       `CREATE NAMESPACE` / `CREATE TABLE` 的每个请求都 `400 Malformed request`；</li>
 *   <li>{@code ListNamespaces} 的响应：规范是**字符串数组**（相对父级的名字），
 *       服务端原先回 `[{id, properties}]`，客户端连反序列化都过不了；</li>
 *   <li>{@code DropNamespace} 的 {@code behavior} 在**请求体**里
 *       （{@code {"behavior":"Cascade"}}），服务端只看 query 的 {@code mode}，
 *       于是级联一直 406。</li>
 * </ul>
 *
 * <p>另有两处**不是报文**的错位，也只有在真数据写出来之后才看得见：{@code is_only_declared}
 * 的探测原先只认 V1 的 {@code _versions/1.manifest}（4.x 客户端写 V2 命名，于是有数据也回
 * 「只有登记」）；manifest 其实是带 footer 的**容器**、且 {@code Field} 已经不再写
 * {@code type}（角色改由 {@code logical_type} 表达）。细节见
 * `docs/lance-rest-catalog-design.md` §12.10。
 *
 * <p>所以这个类的断言分两层，与另两面的 e2e 同一个套路：
 *
 * <ol>
 *   <li><b>Spark 侧</b>：真 SQL 能不能跑通、读回来的数据对不对；</li>
 *   <li><b>服务端侧</b>：直接打 `/lance/v1/...` 看它记下了什么——location、是否只有登记、
 *       manifest 里的 schema 与版本。只有这一层能证明「建表真的落了元数据」而不是
 *       「Spark 自己缓存了一份」。</li>
 * </ol>
 *
 * <p><b>这一面没有 `CREATE TABLE` 的服务端实现，也没有 `DROP TABLE`。</b>建表是
 * `declare`（登记意图）+ 客户端自己写数据集；删表在 Spark 里走 `DropTable`，
 * 而那个 operation 我们按规范劝退（恒 406）。因此本类的清理用 REST 的 `deregister`，
 * 不用 `DROP TABLE`——这不是偷懒，而是这一面真实的能力边界（见
 * `docs/spark-lance-rest-e2e.md` §3）。
 *
 * <p>跑法见 `scripts/e2e-lance-spark.sh`；缺 `-De2e.lance.enabled=true` 时整类跳过。
 */
class LanceCatalogLiveTests {

  import LanceCatalogLiveTests._

  /** SQL 里的 catalog 名，也是 `spark.sql.catalog.<name>` 的后缀。 */
  private def catalog: String = LiveSparkSession.lanceCatalog

  /** 每个用例独占一个 schema，避免互相看见对方的表。 */
  private var database: String = ""

  @BeforeEach
  def requireLiveServer(): Unit = {
    Assumptions.assumeTrue(LiveSparkSession.isAvailable,
      "跳过：需要 -De2e.management.url=<管理 API 基址>，见 scripts/e2e-lance-spark.sh")
    Assumptions.assumeTrue(LiveSparkSession.lanceEnabled,
      "跳过：需要 -De2e.lance.enabled=true 且类路径上有 lance-spark-bundle，见 scripts/e2e-lance-spark.sh")
    // LANCE catalog 不会被客户端「撞」出来（这一面没有 /v1/config），必须先建
    LiveSparkSession.lanceEnsureCatalog()
    database = "lance_" + UUID.randomUUID().toString.replace("-", "").substring(0, 10)
    sql(s"CREATE NAMESPACE $catalog.$database")
  }

  @AfterEach
  def dropNamespace(): Unit = {
    // @BeforeEach 里 assumption 失败时本方法仍会被调用，此时库名还是空串
    if (database == null || database.isEmpty) {
      return
    }
    // 先 deregister 每张表（Spark 侧没有 DROP TABLE，见类注释），再删空了的 schema。
    // 全程尽力而为：清理失败不该把用例的结论改写成「失败」——真正的失败在用例里已经报过。
    // ListTables 回的是完整 id，直接拿来当路径用（这正是那条「回完整 id」的规范形状）。
    quietly {
      rest("GET", "/lance/v1/namespace/" + encode(catalog, database) + "/table/list")
        ._2.get("tables").elements().asScala.map(_.asText()).foreach { tableId =>
          quietly(rest("POST", "/lance/v1/table/" + encodeId(tableId) + "/deregister"))
        }
      quietly(rest("POST", "/lance/v1/namespace/" + encode(catalog, database) + "/drop"))
    }
  }

  // ------------------------------------------------------------------ 命名空间

  /**
   * `CREATE NAMESPACE` 落的是服务端的 schema 行，`SHOW NAMESPACES` 看到的是相对名字。
   *
   * <p>两个断言各自钉一件事：
   *
   * <ul>
   *   <li>服务端 `ListNamespaces` 回的是**名字**（`lance_xxx`），不是 `cat$lance_xxx`、
   *       也不是对象——客户端拿它拼回父级，回完整 id 会拼成四段而 400；</li>
   *   <li>Spark 显示的名字也是同一个（`parent=lance_catalog` 把 catalog 段补回去了），
   *       所以 `SHOW NAMESPACES` 里不该出现带 `$` 的串。</li>
   * </ul>
   */
  @Test
  def createNamespaceAppearsInBothListings(): Unit = {
    val (code, body) = rest("GET", "/lance/v1/namespace/" + catalog + "/list")
    assertEquals(200, code, body.toString)
    val names = body.get("namespaces").elements().asScala.map(_.asText()).toList
    assertTrue(names.contains(database), "服务端 ListNamespaces 应回相对名字，实际: " + names)
    assertFalse(names.exists(_.contains("$")), "回的是子级名字而不是完整 id，实际: " + names)

    val shown = sql(s"SHOW NAMESPACES IN $catalog").map(_.getString(0)).toSet
    assertTrue(shown.contains(database), "SHOW NAMESPACES 应看到 " + database + "，实际: " + shown)
  }

  // ------------------------------------------------------------------ 建表 / 写入 / 读回

  /**
   * 建表 → 写入 → 读回，服务端侧逐项核对。
   *
   * <p>断言对应几处「只在一侧看得见」的事实：
   *
   * <ol>
   *   <li>{@code CREATE TABLE} 之后 location 是服务端按路径模板分配的，且
   *       {@code is_only_declared=false}——**connector 在 declare 拿到的位置上立刻写了一个
   *       0 fragment 的空数据集**，于是「登记」与「有数据集」在 Spark 侧是同一瞬间的事；</li>
   *   <li>{@code load_detailed_metadata=true} 能读出 schema 与版本号：服务端读的是
   *       客户端写的 manifest（4.x 的容器格式），两侧对 manifest 的理解一致；</li>
   *   <li>写入后数据读回来逐行一致，且位置没变。</li>
   * </ol>
   *
   * <p><b>「只有登记」那个状态在这一路里观察不到</b>（被第一条那一瞬间盖住），
   * 它由 {@link #declaredOnlyTableStaysOnlyDeclared} 单独钉住。
   */
  @Test
  def createTableInsertAndReadBack(): Unit = {
    val table = "orders"
    sql(s"CREATE TABLE $catalog.$database.$table (id BIGINT, name STRING)")

    // ---- 服务端侧：登记 + 位置 + 数据集已建
    val declared = rest("POST",
      "/lance/v1/table/" + encode(catalog, database, table) + "/describe?check_declared=true")._2
    assertEquals(catalog + "$" + database + "$" + table, declared.get("id").asText())
    val location = declared.get("location").asText()
    assertTrue(location.endsWith(".lance"), "服务端应按路径模板分配 .lance 目录，实际: " + location)
    assertFalse(declared.get("is_only_declared").asBoolean(),
      "connector 建完表就写出了空数据集，此时不该是「只有登记」，location: " + location)

    // ---- 写入并读回
    sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'a'), (2, 'b')")
    val rows = sql(s"SELECT id, name FROM $catalog.$database.$table ORDER BY id")
    assertEquals(2, rows.length)
    assertEquals(1L, rows(0).getLong(0))
    assertEquals("a", rows(0).getString(1))
    assertEquals(2L, rows(1).getLong(0))
    assertEquals("b", rows(1).getString(1))

    // ---- 服务端侧：写完之后数据集在 declare 分配的位置上，且能读出客户端写的 manifest
    // 两个开关各自管一件事，缺一个就少一组字段：check_declared 管 is_only_declared、
    // load_detailed_metadata 管 schema / version / stats（服务端不探测就不给，不回猜的值）
    val detailed = rest("POST", "/lance/v1/table/" + encode(catalog, database, table)
      + "/describe?check_declared=true&load_detailed_metadata=true")._2
    assertEquals(location, detailed.get("location").asText(),
      "写入不该换位置——客户端要写到 declare 分配的那个目录")
    assertFalse(detailed.get("is_only_declared").asBoolean(),
      "写完数据后数据集应已落在 " + location + "，不该还是「只有登记」")
    val fields = detailed.get("schema").get("fields").elements().asScala
      .map(node => node.get("name").asText()).toList
    assertEquals(List("id", "name"), fields, "服务端解析出的字段应与提交的一致")
    assertTrue(detailed.get("version").asLong() >= 1,
      "写入之后应有版本号，实际: " + detailed.get("version"))
    assertEquals(table, detailed.get("table").asText())
    assertEquals(List(catalog, database), detailed.get("namespace").elements().asScala
      .map(_.asText()).toList, "namespace 是层级数组（规范），不是拼接串")
  }

  /**
   * 只走 REST 的 {@code declare}：表登记了，存储里仍然没有数据集。
   *
   * <p><b>为什么这条要用裸 REST 而不是 Spark SQL。</b>connector 的 `CREATE TABLE` 在 declare
   * 之后立刻写一个空数据集，于是「只登记」那一瞬间在 Spark 侧根本看不到（见
   * {@link #createTableInsertAndReadBack}）。而这条契约本身是这一面的骨架：
   * declare 不碰存储，`is_only_declared` 因此才有 `true` 这一态。
   *
   * <p>顺带钉住「没有 manifest 时 {@code load_detailed_metadata} 必须失败」：
   * 规范允许这条失败，而我们若为了「不报错」回一份空 schema，客户端会把它当成
   * 「这张表有 0 列」——比失败难查得多。
   */
  @Test
  def declaredOnlyTableStaysOnlyDeclared(): Unit = {
    val table = "declared_only"
    val path = "/lance/v1/table/" + encode(catalog, database, table)
    val (code, body) = rest("POST", path + "/declare",
      s"""{"id":["$catalog","$database","$table"]}""")
    assertEquals(200, code, body.toString)

    val (describeCode, described) = rest("POST", path + "/describe?check_declared=true")
    assertEquals(200, describeCode, described.toString)
    assertTrue(described.get("is_only_declared").asBoolean(),
      "declare 只登记、不建数据集，此时应为「只有登记」，实际: " + described.get("is_only_declared"))
    assertTrue(described.get("location").asText().endsWith(".lance"), described.toString)

    // 详细元数据：没有 manifest 可读，按规范以 InvalidTableState（19 / 409）失败
    val (detailedCode, detailed) = rest("POST", path + "/describe?load_detailed_metadata=true")
    assertEquals(409, detailedCode, detailed.toString)
    assertEquals(19, detailed.get("code").asInt(), "InvalidTableState 的 code 是 19")
  }

  /**
   * `SHOW TABLES` 回的是**完整 id**——这是 `ListTables` 的规范形状，也是 Spark 侧的一处坑。
   *
   * <p>规范原文：「Each string in the list is the full identifier in string form」。
   * 于是 connector 把这个串当作表名塞进 `Identifier`，`SHOW TABLES` 就把
   * `cat$db$tbl` 当名字显示；拿它再去 `SELECT` 会拼成四段 id（服务端 400）。
   * 这条断言把这个事实钉住：**查数据用三段名，不要用 `SHOW TABLES` 那列**。
   *
   * <p>不改服务端去回短名，是因为回完整 id 是规范明文；而 Spark 这一侧的用法
   * 属于客户端的选择。要改就得先改规范，不是我们单方面迁就。
   */
  @Test
  def showTablesReturnsFullIdentifiers(): Unit = {
    val table = "t"
    sql(s"CREATE TABLE $catalog.$database.$table (id BIGINT)")

    val shown = sql(s"SHOW TABLES IN $catalog.$database").map(_.getString(1)).toSet
    assertEquals(Set(catalog + "$" + database + "$" + table), shown,
      "ListTables 回完整 id，connector 直接把它当表名")

    // 三段名照常可用——上面那个串是「显示名」，不是查询用的名字
    sql(s"INSERT INTO $catalog.$database.$table VALUES (7)")
    assertEquals(7L, sql(s"SELECT id FROM $catalog.$database.$table")(0).getLong(0))
  }

  // ------------------------------------------------------------------ 维护语句

  /**
   * `OPTIMIZE` / `VACUUM` 是 parser 级命令，不是 `CALL`——这里验的就是那个形状真能跑。
   *
   * <p>语句文本与 `LanceProcedures` 的渲染逐字一致（三段名各加反引号、`WITH (k = v)`
   * 的值是字面量、浮点带小数点、`VACUUM` 的两个布尔常量显式写出）。**这是有意的重复**：
   * 优化任务那边只做字符串渲染、从不真的执行，这里跑一次才知道渲染出来的东西
   * connector 认不认。渲染规则一改（比如浮点写成裸 `1`），这条会先响。
   *
   * <p>断言分三件：输出列名（connector 的契约）、语句能执行（解析 + 规划 + 执行三层都对）、
   * **数据没变**（`OPTIMIZE` 与 `VACUUM` 都不该改数据；`VACUUM` 还是不可逆的）。
   */
  @Test
  def optimizeAndVacuumRunWithTheRenderedStatementShape(): Unit = {
    val table = "maint"
    sql(s"CREATE TABLE $catalog.$database.$table (id BIGINT, name STRING)")
    // 分三次写 → 三个 fragment，`OPTIMIZE` 才有可合并的东西
    sql(s"INSERT INTO $catalog.$database.$table VALUES (1, 'a')")
    sql(s"INSERT INTO $catalog.$database.$table VALUES (2, 'b')")
    sql(s"INSERT INTO $catalog.$database.$table VALUES (3, 'c')")

    val qualified = s"`$catalog`.`$database`.`$table`"
    val optimize = fill(
      """OPTIMIZE %s WITH (target_rows_per_fragment = 1000, max_rows_per_group = 100,
        |materialize_deletions = TRUE, materialize_deletions_threshold = 0.5,
        |defer_index_remap = FALSE)""".stripMargin.format(qualified))
    val optimized = frame(optimize)
    assertEquals(List("fragments_removed", "fragments_added", "files_removed", "files_added"),
      optimized.columns.toList, "OPTIMIZE 的输出列由 connector 的 OptimizeOutputType 定")
    // 只 collect 一次：DataFrame 是惰性的，每 collect 一次就真的再跑一遍 OPTIMIZE
    val removed = optimized.collect()(0).getLong(0)
    assertTrue(removed >= 1, "三次写入应至少合并掉一个 fragment，实际: " + removed)

    // VACUUM：窗口给 epoch 毫秒（三个格式里唯一不用操心时区的那个）。
    // before_timestamp_millis = 现在 → 所有历史版本都过期，只留最新那版。
    val vacuum = fill(
      s"""VACUUM %s WITH (before_timestamp_millis = ${System.currentTimeMillis()},
         |delete_unverified = FALSE, error_if_tagged_old_versions = TRUE)""".stripMargin.format(qualified))
    val vacuumed = frame(vacuum)
    assertEquals(List("bytes_removed", "old_versions"), vacuumed.columns.toList,
      "VACUUM 的输出列由 connector 的 VacuumOutputType 定")
    val version = vacuumed.collect()(0).getLong(1)
    assertTrue(version >= 1, "三次写入留下的历史版本应被清掉，实际清掉: " + version)

    // 维护动作不该动数据——这也是 VACUUM 唯一能验的安全网（它不可逆）
    val rows = sql(s"SELECT id, name FROM $catalog.$database.$table ORDER BY id")
    assertEquals(3, rows.length, "维护后行数不变")
    assertEquals(3L, rows(2).getLong(0))
    assertEquals("c", rows(2).getString(1))

    // 服务端侧：manifest 里还是同一个版本号，且数据集仍然完整
    val detailed = rest("POST",
      "/lance/v1/table/" + encode(catalog, database, table) + "/describe?load_detailed_metadata=true")._2
    assertTrue(detailed.get("version").asLong() >= 1)
  }

  // ------------------------------------------------------------------ 错误面 / 授权

  /**
   * schema 不存在时，`CREATE TABLE` 以服务端的 404 收场（而不是在客户端造出一张本地表）。
   *
   * <p>钉的是「declare 之前先查 schema」这条链路：漏了它，Spark 会在客户端建出一个
   * 只在本次会话里存在的表，下一次 `SELECT` 才以一句无关的话失败。
   */
  @Test
  def createTableInMissingNamespaceFails(): Unit = {
    val missing = database + "_missing"
    val error = assertThrows(classOf[Exception], () =>
      sql(s"CREATE TABLE $catalog.$missing.t (id BIGINT)"))
    // 客户端会把它翻成 NoSuchNamespaceException / "not found" 一类；这里只要求
    // 「失败」且原因指向不存在，不锁死具体措辞（各版本 connector 的措辞会变）
    val message = Option(error.getMessage).getOrElse("").toLowerCase
    assertTrue(message.contains("not found") || message.contains("no such")
      || message.contains("does not exist"),
      "报错应指向 schema 不存在: " + error.getMessage)

    // 服务端侧：这个 schema 确实不存在（不是「建了一半」）
    val (code, body) = rest("POST", "/lance/v1/namespace/" + encode(catalog, missing) + "/describe")
    assertEquals(404, code, body.toString)
    assertEquals(1, body.get("code").asInt(), "NamespaceNotFound 的 code 是 1")
  }

  /**
   * 只读身份：服务端回 `403` / `code 15`，Spark 侧表现为「看不到这个 catalog」。
   *
   * <p>两处都要断言，因为它们**看起来不一致**：客户端把 403 折成
   * `NoSuchNamespaceException`（connector 的 `listNamespaces` 吞掉异常），
   * 于是 Spark 报「namespace 不存在」，真正的原因是权限。直连一次 REST 才能把
   * 「服务端确实拒绝了」与「客户端翻译得不好」分开——这两件事以前混在一起过
   * （授权规则漏登记时，控制器里明明判了权，请求却停在拦截器上）。
   *
   * <p>令牌由 `scripts/e2e-lance-spark.sh` 传 `-De2e.lance.limited-token=limited`，
   * 服务端把它映射到没有任何授权的 `e2e_limited`。没传就跳过（别人用别的服务端跑时不误报）。
   */
  @Test
  def limitedTokenIsDeniedByTheLanceFace(): Unit = {
    Assumptions.assumeTrue(LIMITED_TOKEN.nonEmpty,
      "跳过：需要 -De2e.lance.limited-token=<无授权的令牌>，见 scripts/e2e-lance-spark.sh")

    val (code, body) = rest("GET", "/lance/v1/namespace/" + catalog + "/list", null, LIMITED_TOKEN)
    assertEquals(403, code, body.toString)
    assertEquals(15, body.get("code").asInt(), "PermissionDenied 的 code 是 15")
    assertTrue(body.get("error").asText().nonEmpty, "错误体要说明原因: " + body)

    // Spark 侧：同一个 catalog 换个身份（子会话），请求被拒，报「不存在」
    val session = LiveSparkSession.sessionAs(LIMITED_TOKEN)
    assertThrows(classOf[Exception], () => session.sql(s"SHOW NAMESPACES IN $catalog").collect())
  }

  // ------------------------------------------------------------------ 辅助

  private def session = LiveSparkSession.instance

  private def sql(statement: String): Array[Row] = session.sql(statement).collect()

  /** 要断言的列名，所以留着 DataFrame 不立刻 collect。 */
  private def frame(statement: String): DataFrame = session.sql(statement)

  private def quietly(action: => Unit): Unit =
    try {
      action
      ()
    } catch {
      case _: Throwable => ()
    }

  /**
   * 把多行 SQL 折成一行。
   *
   * <p>{@code OPTIMIZE} / {@code VACUUM} 是**纯语法命令**，不是 `CALL`：语句里出现换行
   * 与注释（`--`）都不被接受，而 Scala 的多行字符串在拼接处会留下换行。这里把空白
   * 折成单个空格，顺带让「语句里不能有分号」这条规则不必再靠自觉。
   */
  private def fill(sql: String): String = sql.replaceAll("\\s+", " ").trim
}

object LanceCatalogLiveTests {

  private val MAPPER = new ObjectMapper()

  /** 无授权的令牌；脚本没给就跳过那条用例。 */
  private val LIMITED_TOKEN = System.getProperty("e2e.lance.limited-token", "").trim

  /** 把层级拼成路径里的 id：`$` 在 URL 里必须编码，否则会被当成变量前缀。 */
  private def encode(levels: String*): String = levels.mkString("%24")

  /** 已经拼好的 id（例如 `ListTables` 回的完整 id）→ 可以直接进路径的形状。 */
  private def encodeId(id: String): String = id.replace("$", "%24")

  private def rest(method: String, path: String, body: String = null,
                   token: String = LiveSparkSession.token): (Int, JsonNode) = {
    val builder = HttpRequest.newBuilder(URI.create(LiveSparkSession.catalogUrl + path))
      .timeout(Duration.ofSeconds(20))
      .header("Authorization", "Bearer " + token)
      .header("Accept", "application/json")
    if (body == null) {
      builder.method(method, HttpRequest.BodyPublishers.noBody())
    } else {
      builder.header("Content-Type", "application/json")
        .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    }
    val response: HttpResponse[String] = HttpClient.newHttpClient()
      .send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
    val node = if (response.body() == null || response.body().isEmpty) {
      MAPPER.createObjectNode()
    } else {
      MAPPER.readTree(response.body())
    }
    (response.statusCode(), node)
  }
}
