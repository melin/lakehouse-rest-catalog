package io.github.melin.lakehouse.rest.spark

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.melin.lakehouse.rest.spark.client.{ManagementApiClient, ManagementApiException}
import io.github.melin.lakehouse.rest.spark.lineage.LineageSettings
import org.apache.spark.sql.SparkSession

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Duration

/**
 * 需要真实服务端的测试类共用的 SparkSession。
 *
 * <p><b>为什么必须共用而不是各建各的。</b>一个 JVM 只能有一个 SparkContext，
 * `SparkSession.builder().getOrCreate()` 在已有活动会话时会直接返回它，
 * 而 `spark.sql.extensions` 只在**建会话那一刻**生效、之后改不了。
 * 若每个测试类各自建会话，后建的类会静默拿到先建的那个——
 * 扩展其实没装上，用例却会以「语法错误」「catalog 不存在」之类的形式失败，
 * 排查起来要绕很远。因此这里是唯一的会话入口。
 *
 * <p>配置一次性配齐，覆盖两类被测对象：
 *
 * <ul>
 *   <li>{@code io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions} —— 本项目的管理语句扩展；
 *   <li>{@code org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions} 与
 *       {@code spark.sql.catalog.paimon} —— Paimon 自身的扩展与 REST catalog，
 *       供 `CREATE TABLE ... USING paimon` 一类的建表用例使用；
 *   <li>{@code org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions} 与
 *       {@code spark.sql.catalog.iceberg_catalog} —— Iceberg 自身的扩展与 REST catalog，
 *       供 `CREATE TABLE ... USING iceberg` 一类的建表用例使用。
 * </ul>
 *
 * <p>三个扩展可以并存：本项目的扩展先用自己的解析器识别管理语句，
 * 识别不了就原样交回 Spark 原生解析器；Paimon 与 Iceberg 注入的规则各自只认
 * 自己的 catalog（数据源名 / catalog 实现类不同），互不重叠。
 *
 * <p><b>两个 catalog 为什么在同一个会话里。</b>`spark.sql.extensions` 只在建会话那一刻
 * 生效，一个 JVM 又只能有一个 SparkContext——想把两种格式分成两个测试类、各建各的会话，
 * 后建的那个会静默拿到先建的那个，扩展缺一半。因此 Paimon 与 Iceberg 的 catalog 都
 * 配在这里，由 {@code spark.sql.catalog.<name>} 区分；两种格式的元数据本来就互不可见
 * （一个 catalog 一种格式，建完不可改），共用一个会话不会互相干扰。
 *
 * <p>目标地址取自系统属性，未配置时 {@link #isAvailable} 为 false，
 * 各测试类据此跳过（见 {@code scripts/e2e-spark-sql.sh}）。
 */
object LiveSparkSession {

    /** 管理 API 基址，例如 {@code http://127.0.0.1:18080/api/management/v1}。 */
    private[spark] val managementUrl = System.getProperty("e2e.management.url", "")

    /** 管理 API 在服务端上的固定前缀，用来反推 catalog API 的基址。 */
    private val managementPath = "/api/management/v1"

    /** 调用者令牌；测试类要直接读服务端原始响应时也用它。 */
    private[spark] val token = System.getProperty("e2e.management.token", "")

    /**
     * LakeHouse Catalog 的基址。
     *
     * <p>默认从管理 API 基址去掉 {@value #managementPath} 得到——两者本来就挂在
     * 同一个服务端上，这样只需给一个地址。需要指向别处时用
     * {@code -De2e.paimon.url=} 覆盖。
     */
    def catalogUrl: String = {
        val explicit = System.getProperty("e2e.paimon.url", "")
        if (explicit.nonEmpty) {
            explicit
        } else if (managementUrl.endsWith(managementPath)) {
            managementUrl.substring(0, managementUrl.length - managementPath.length)
        } else {
            managementUrl
        }
    }

    /** 是否给了目标地址。没给就说明没打算跑端到端用例。 */
    def isAvailable: Boolean = managementUrl.nonEmpty

    /**
     * 是否要连血缘一起验（`scripts/e2e-lineage.sh` 设 `-De2e.lineage.enabled=true`）。
     *
     * <p>默认不验：每一条写入都会多一次上报请求，普通 e2e 没必要为它买单；
     * 而服务端那边要是没开血缘，上报会稳定失败，日志里会多出一串 WARN。
     */
    private[spark] val lineageEnabled: Boolean =
        System.getProperty("e2e.lineage.enabled", "").trim.equalsIgnoreCase("true")

    /**
     * 用 root 令牌直连管理 API 的客户端。
     *
     * <p>测试在两种场合需要绕过 SQL：一是拿服务端原始响应做核对，
     * 二是装配那些**已经没有 SQL 语句**的东西——catalog role 的建档与装配
     * （控制台没有页面，SQL 里也只删得只剩管理 API）。
     *
     * <p>用客户端而不是裸 HTTP：它与服务端同一份规格，路径与请求体形状不用在测试里重写一遍；
     * 需要看原始报文的地方仍然走各自的 `rawSend`。
     */
    def managementClient: ManagementApiClient =
        new ManagementApiClient(managementUrl, token, Duration.ofSeconds(30))

    /**
     * 删掉一个 catalog role，不存在就当已经干净。
     *
     * <p>规格的 `DELETE` 缺了就是 404，没有「IF EXISTS」这种写法——那条语义原先由
     * `DROP CATALOG ROLE IF EXISTS` 语句在命令层补，语句移除后只能在这里补。
     * 两个装配用例（{@code SparkSqlAuthorizationTests} / {@code ManagementSqlLiveServerTests}）
     * 都要「先清干净再建」，所以放在共用入口上。
     */
    def dropCatalogRoleIfPresent(catalog: String, role: String): Unit =
        try {
            managementClient.dropCatalogRole(catalog, role)
        } catch {
            case e: ManagementApiException if e.notFound() => ()
        }

    /** Paimon 自己的扩展类名。 */
    private val PaimonExtensionClass = "org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions"

    /** Iceberg 自己的扩展类名（`ALTER TABLE ... CREATE BRANCH` 这类 DDL 由它提供）。 */
    private val IcebergExtensionClass = "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions"

    /**
     * `spark.sql.extensions` 的取值：三个扩展都要装。
     *
     * <p>顺序上本项目的扩展在前——它先用自己的解析器识别管理语句，
     * 识别不了就原样交回 Spark 原生解析器，与另外两个注入的规则不重叠。
     *
     * <p>Paimon 的扩展**不能省**：`SparkCatalog.initialize` 会检查
     * `spark.sql.extensions` 里有没有它，缺了就直接抛异常、连建库都做不了
     * （提示语是 "When using Paimon, it is necessary to configure `spark.sql.extensions`…"）。
     *
     * <p>Iceberg 的扩展不检查自己的存在（只在用到 Iceberg 专有 DDL 时才生效），
     * 但装上是必要的：`ALTER TABLE ... CREATE BRANCH` 一类的语句**没有它就会解析失败**，
     * 而失败信息是通用的语法错误，与「少装了一个扩展」看不出关系。
     */
    val extensions = PaimonRestManagement.EXTENSION_CLASS + "," + PaimonExtensionClass +
        "," + IcebergExtensionClass

    /**
     * 测试用的 catalog 标识，既是 `spark.sql.catalog.<name>` 的后缀，
     * 也是服务端上的 prefix（服务端默认 prefix 由 `lakehouse.rest.default-prefix` 决定，默认同名）。
     *
     * <p>SQL 里要用全限定名 `paimon.<database>.<table>` 才能落到这个 catalog 上：
     * `USE <database>` 只切换当前库、不切换 catalog，漏写限定名会把表建到
     * `spark_catalog` 里，之后 `INSERT` 会以「paimon is not a valid Spark SQL Data Source」
     * 收场——报错信息与真正的原因相去甚远。
     */
    val catalog: String = "paimon_catalog"

    /**
     * Iceberg 侧的 catalog 名，同样是「`spark.sql.catalog.<name>` 的后缀 + 服务端 prefix」。
     *
     * <p>与 {@link #catalog} 是两个独立登记项：服务端的 catalog 一格一种格式、建完不可改，
     * 因此 Paimon 与 Iceberg 必须是**两个 catalog**，不能指望同一个 prefix 兼顾两者
     * （同一个 prefix 上两种格式的元数据互不可见）。
     *
     * <p>这个 prefix 不会像 Paimon 那个由 `auto-create-catalog` 自动登记——`/v1/config`
     * 这条连接阶段的路由不知道客户端要哪种格式，自动登记会按部署默认值建成 Paimon，
     * 之后每一次 Iceberg 调用都会 404（报 "is a PAIMON catalog"）。所以
     * {@link #ensureIcebergCatalog} 先用管理 API 把它建成 ICEBERG，再让客户端连。
     */
    val icebergCatalog: String = "iceberg_catalog"

    /**
     * Iceberg catalog 的仓库根，只在**首次**建 catalog 时用一次。
     *
     * <p>用临时目录而不是写死路径：同一台机器上反复跑测试不该踩上一轮留下的文件。
     * 服务端按这个值判定 `allowedLocations`，表的实际位置仍由服务端的 `path-template`
     * 推（`<warehouse>/<namespace>.db/<table>`）。
     */
    lazy val icebergWarehouse: String =
        "file://" + Files.createTempDirectory("lakehouse-rest-iceberg-live-warehouse").toString

    /** 建 catalog 是「一次就好」的副作用，见 {@link #ensureIcebergCatalog}。 */
    private var icebergCatalogReady = false

    /**
     * 确保服务端上有一个 ICEBERG 格式的 {@link #icebergCatalog}。
     *
     * <p>走管理 API 而不是让客户端自己撞：`GET /v1/config?warehouse=<prefix>` 是连接阶段，
     * 那时客户端还没说过自己要哪种格式，服务端的自动登记按部署默认值走（默认 PAIMON）。
     * 一旦以 Paimon 登记，这个 prefix 上**每一次** Iceberg 调用都会以
     * `prefix "..." is a PAIMON catalog` 失败，且错是第一次请求自己留下的。
     *
     * <p>幂等：已存在（409）时不再建，但仍要核对格式——同名 prefix 若是 Paimon，
     * 继续跑下去只会得到一串与真正原因无关的 404，不如在这里说清楚。
     */
    def ensureIcebergCatalog(): Unit = synchronized {
        if (icebergCatalogReady) {
            return
        }
        val body = ("""{"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"ICEBERG",""" +
            """"properties":{},"storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}""")
            .format(icebergCatalog, icebergWarehouse)
        val response = send(HttpRequest.newBuilder(URI.create(managementUrl + "/catalogs"))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
            .build())
        val code = response.statusCode()
        require(code == 201 || code == 409,
            s"创建 ICEBERG catalog 失败：POST /catalogs -> $code ${response.body()}")

        val actual = catalogFormatOf(icebergCatalog)
        require(actual == "ICEBERG",
            s"prefix $icebergCatalog 已存在且格式是 $actual，不是 ICEBERG。"
                + "同一个 prefix 不能同时承载两种格式，请换一个名字（或删掉重建）。")
        icebergCatalogReady = true
    }

    /** 读一个 catalog 的格式；读不到时返回空串（调用方负责把它变成一句能照做的提示）。 */
    private def catalogFormatOf(prefix: String): String = {
        val response = send(HttpRequest.newBuilder(URI.create(managementUrl + "/catalogs/" + prefix))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json")
            .GET().build())
        if (response.statusCode() != 200) {
            return s"(读取失败：${response.statusCode()})"
        }
        val node = MAPPER.readTree(response.body()).get("catalogFormat")
        if (node == null || node.isNull) "" else node.asText()
    }

    private def send(request: HttpRequest): HttpResponse[String] =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
            .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

    /**
     * 以指定令牌**另开一个会话**，用来在同一个 catalog 上扮演另一个身份。
     *
     * <p><b>为什么不是再建一个 SparkSession。</b>一个 JVM 只能有一个 SparkContext，
     * 而 `spark.sql.extensions` 只在建会话那一刻生效；`SparkSession.builder().getOrCreate()`
     * 会直接把 {@link #instance} 返回给你，身份根本换不掉。
     *
     * <p>`newSession()` 满足这里的需求：它共享 SparkContext，但**另有一份 SQLConf**，
     * 因此可以只改 `spark.sql.catalog.<catalog>.token` 而不影响别人。
     * 令牌是这一层唯一的身份来源——服务端由
     * `lakehouse.rest.auth.tokens` 认证出主体名，再由 RBAC 判定能做什么；
     * Spark 与 Paimon 客户端都不认识「用户」这个概念。
     *
     * <p>改的是 catalog 配置，所以子会话必须在**第一次使用该 catalog 之前**取；
     * 已经用过的会话不会因为改配置而换身份（`CatalogManager` 已经实例化过那个 catalog）。
     *
     * <p><b>两个 catalog 的令牌一起换。</b>身份是「这个会话对服务端是谁」，不是
     * 「Paimon 这个 catalog 认谁」——只换一半会得到最坏的组合：Paimon 侧被拦、
     * Iceberg 侧仍以 root 畅通，用例看起来「只读身份写不了 Paimon」，其实
     * 从没验过 Iceberg。两个 key 各写一次而不是抽循环：名字不同、都不是数组，
     * 抽出来省不下两行，反而看不出「这两个 catalog 各有一份令牌」。
     */
    def sessionAs(identity: String): SparkSession = {
        require(identity.nonEmpty, "identity token must not be empty")
        val session = instance.newSession()
        session.conf.set("spark.sql.catalog." + catalog + ".token", identity)
        session.conf.set("spark.sql.catalog." + icebergCatalog + ".token", identity)
        require(session.conf.get("spark.sql.catalog." + catalog + ".token") == identity
                && session.conf.get("spark.sql.catalog." + icebergCatalog + ".token") == identity,
            "子会话没能覆盖 catalog 令牌，身份不会生效")
        session
    }

    /** 会话按进程共享，只建一次。仅在有目标地址时才被触碰。 */
    lazy val instance: SparkSession = {
        // 同上：JDK 24+ 下 Spark 建会话必失败，提前换成一句能照做的提示
        SparkJdkRequirement.requireHadoopCompatibleJdk()
        val warehouse = Files.createTempDirectory("paimon-rest-live-warehouse")
        val session = SparkSession.builder()
            .master("local[1]")
            .appName("paimon-rest-live")
            .config("spark.ui.enabled", "false")
            .config("spark.driver.host", "127.0.0.1")
            .config("spark.driver.bindAddress", "127.0.0.1")
            .config("spark.sql.shuffle.partitions", "1")
            .config("spark.sql.warehouse.dir", warehouse.toString)
            .config("spark.sql.extensions", LiveSparkSession.extensions)
            // Paimon 自己的扩展：建表/读写等 Paimon SQL 由它落地
            .config("spark.sql.catalog." + catalog, "org.apache.paimon.spark.SparkCatalog")
            .config("spark.sql.catalog." + catalog + ".metastore", "rest")
            .config("spark.sql.catalog." + catalog + ".uri", catalogUrl)
            // warehouse 传的是 catalog 的 prefix：服务端 GET /v1/config 会用仓库名换回 prefix，
            // 客户端后续路径都以它开头。这里两侧同名
            .config("spark.sql.catalog." + catalog + ".warehouse", catalog)
            .config("spark.sql.catalog." + catalog + ".token.provider", "bear")
            .config("spark.sql.catalog." + catalog + ".token", token)
            // 让客户端向服务端索取表级数据访问凭据（Paimon 的 data token）。
            //
            // 为什么必须开：catalog 的 warehouse 落在 `s3://`（或 OBS / OSS）上时，
            // 客户端自己没有任何密钥，**端点和密钥都由服务端下发**——这正是
            // REST 目录存在的意义（引擎不持有对象存储的长期密钥）。默认值是 false，
            // 此时客户端会拿本机凭据链去连 S3，在测试机上必然是
            // `Could not find a file io implementation` 或签名失败。
            //
            // 对 FILE 仓库无害：服务端仍会回一份自包含令牌，本地文件系统不读它。
            // 代价是每次新建 FileIO 之前多一次 `.../tables/{t}/token` 请求，
            // 凭据在有效期内由双方缓存。
            .config("spark.sql.catalog." + catalog + ".data-token.enabled", "true")
            // Iceberg 自己的 REST catalog：`type=rest` 是「走 REST 而不是 hive/hadoop」
            // 的开关，CatalogUtil 按它选实现。两个 key 与 Paimon 那套刻意不同：
            // Paimon 是 `metastore=rest`，Iceberg 是 `type=rest`——写混了得到的是
            // 「不认识的 catalog 实现」，不是「退化成默认」，所以这里不抽公共方法。
            .config("spark.sql.catalog." + icebergCatalog, "org.apache.iceberg.spark.SparkCatalog")
            .config("spark.sql.catalog." + icebergCatalog + ".type", "rest")
            .config("spark.sql.catalog." + icebergCatalog + ".uri", catalogUrl)
            // 同上：这里的 warehouse 是 catalog 的 prefix，不是仓库路径
            .config("spark.sql.catalog." + icebergCatalog + ".warehouse", icebergCatalog)
            // 令牌 + 显式声明认证方式。
            //
            // 只给 `token` 也能用：客户端看到 token 会**猜**成 oauth2 并打一条
            // `Inferring rest.auth.type=oauth2` 的警告。显式写出来是为了让日志干净，
            // 也为了把「属性全名是 rest.auth.type」这条坑留在代码里（写成 auth.type
            // 不会切换认证方式，只会变成一个发给服务端但没人看的 catalog 属性）。
            .config("spark.sql.catalog." + icebergCatalog + ".rest.auth.type", "oauth2")
            .config("spark.sql.catalog." + icebergCatalog + ".token", token)
            // 关掉客户端的元数据缓存：用例要的是「每一次都真的发请求」。
            // 开着的话，INSERT 之后的 SELECT 可能命中本地缓存的旧 metadata，
            // 表现是「写入成功但读不到」——与服务端的回归长得一模一样。
            .config("spark.sql.catalog." + icebergCatalog + ".cache-enabled", "false")
            .config(PaimonRestManagement.MANAGEMENT_URL, managementUrl)
            .config(PaimonRestManagement.TOKEN, token)
            // 血缘采集（§10.2）：默认关，`scripts/e2e-lineage.sh` 打开。
            //
            // 必须走 `.config(...)`（写进 SparkConf），不能靠 `spark.conf.set`：
            // 监听器的注册发生在**会话状态构建期**（`injectParser` 的工厂里），那一刻
            // 只有 SparkConf 读得到——读 SQLConf 会重入构建流程并栈溢出（见 LineageSettings）。
            .config(LineageSettings.ENABLED, if (lineageEnabled) "true" else "false")
            // 上报间隔压到 200ms：用例要在几秒内看到结果，而不是等默认的 2 秒一批
            .config(LineageSettings.FLUSH_INTERVAL_MS,
                if (lineageEnabled) "200" else LineageSettings.DEFAULT_FLUSH_INTERVAL_MS.toString)
            .getOrCreate()

        // 守住会话复用这个坑：若本进程里已经有别的 SparkSession（例如用桩服务端的
        // ManagementSqlExecutionTests），getOrCreate 会**直接返回那一个**，
        // 我们设的 extensions 与管理 API 地址都不会生效。此时用例仍会跑，
        // 但会以「catalog 不存在」「客户端 404」这类无关报错收场，很难定位。
        // 与其静默跑错，不如立刻说清楚。
        val effective = session.conf.get("spark.sql.extensions", "")
        require(effective.contains(PaimonExtensionClass)
                && effective.contains(IcebergExtensionClass)
                && effective.contains(PaimonRestManagement.EXTENSION_CLASS),
            s"""拿到的 SparkSession 不是本 holder 创建的那个，扩展配置为 "$effective"。
               |一个 JVM 只能有一个 SparkContext，而 spark.sql.extensions 只在建会话时生效。
               |请只运行需要真实服务端的测试类，例如：
               |  mvn -o -pl :lakehouse-rest-spark-3.5 test -Dtest=ManagementSqlLiveServerTests,PaimonTableDdlTests,NonPaimonTableSqlTests,IcebergTableDdlTests,SparkSqlAuthorizationTests,SparkSqlDocExamplesTests
               |或直接用 scripts/e2e-spark-sql.sh。""".stripMargin)

        // 提前初始化 sessionState：解析器在此时装配，装配出问题会立刻暴露，
        // 而不是拖到第一条语句才报错。
        val _ = session.sessionState.sqlParser
        session
    }

    private val MAPPER = new ObjectMapper()
}
