package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import io.github.melin.lakehouse.rest.spark.policy.{PolicyEnforcementStrategy, PolicyInjectionRule}
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.SparkSessionExtensions

import java.time.Duration
import java.util.Locale

/**
 * Spark SQL 扩展入口：把管理语句的解析器注入会话。
 *
 * <p>两种启用方式，二选一即可：
 *
 * <pre>
 * # 1) 配置（推荐，对 spark-sql / spark-submit / PySpark 都有效）
 * spark.sql.extensions=io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions
 * spark.lakehouse.rest.management.url=http://catalog-host:8080/api/management/v1
 * spark.lakehouse.rest.token=&lt;调用者主体的令牌&gt;
 * # 权限语句（GRANT ... TO USER ...）需要 catalog API 基址；默认由上面那个推导，
 * # 服务部署在非标准路径下时才需要显式指定：
 * # spark.lakehouse.rest.catalog.url=http://catalog-host:8080/v1
 *
 * # 2) 编程方式
 * val spark = PaimonRestManagement.install(SparkSession.builder())
 *   .master("local[2]").getOrCreate()
 * </pre>
 *
 * <p><b>令牌的作用。</b>管理服务按调用者身份判定权限，因此
 * {@code spark.lakehouse.rest.token} 应当填「执行这些语句的主体」的令牌，
 * 而不是随便一个管理员令牌——用管理员令牌会让所有 SQL 都绕过权限判定。
 */
class ManagementSparkExtensions extends (SparkSessionExtensions => Unit) {

  override def apply(extensions: SparkSessionExtensions): Unit = {
    // 血缘监听器**不在这里**注册：这个工厂由 `BaseSessionStateBuilder#sqlParser` 调用，
    // 也就是 `sessionState` 正在构建的时刻，而 `session.listenerManager` 本身要读
    // `sessionState`——在这里注册会重入构建流程并栈溢出。改由解析器在第一次 `parsePlan`
    // 时挂载（见 `AbstractLakehouseRestSqlParser#armListener`，§13.7 第 6 条）。
    extensions.injectParser { (session, parser) => new LakehouseRestSqlParser(parser, session) }
    // 行过滤 / 列脱敏（§5.4）：默认关闭（spark.lakehouse.rest.policy.enforced），
    // 关闭时两条规则都是纯 no-op——装这个扩展做管理语句解析的会话不会被波及。
    // 两条规则的分工：注入规则把带策略的表包进 PolicyRelation（不透明的逻辑节点，
    // 优化器不认识它 → 不会把 mask 吃掉）；planner strategy 在物理规划期才把它
    // 展开成 Filter + Project，展开形状由我们完全控制。
    extensions.injectPostHocResolutionRule { session => new PolicyInjectionRule(session) }
    extensions.injectPlannerStrategy { session => new PolicyEnforcementStrategy }
  }
}

/** 配置键与编程式安装入口。 */
object PaimonRestManagement {

  /** 管理 API 基址，须包含 {@code /api/management/v1} 前缀。 */
  val MANAGEMENT_URL = "spark.lakehouse.rest.management.url"

  /**
   * catalog API 基址，须包含 {@code /v1} 前缀。
   *
   * <p>只有权限语句（`GRANT ... TO USER ...` / `SHOW PERMISSIONS`）需要它：官方把权限端点
   * 挂在 catalog API 下（`/v1/{prefix}/permissions`），而主体与角色的端点在管理 API 下。
   * 默认留空表示「从管理基址推导」——把 {@code /api/management/v1} 换成 {@code /v1}，
   * 常规部署下不需要配它。服务被部署在非标准路径下时推导不出来，此时必须显式配这一个，
   * 否则权限语句会报一条指向本配置项的错。
   */
  val CATALOG_URL = "spark.lakehouse.rest.catalog.url"

  /** 调用者令牌，会作为 {@code Authorization: Bearer <token>} 发出。 */
  val TOKEN = "spark.lakehouse.rest.token"

  /** 单次请求超时（秒）。 */
  val TIMEOUT_SECONDS = "spark.lakehouse.rest.management.timeoutSeconds"

  /** 扩展类名，供 {@code spark.sql.extensions} 使用。 */
  val EXTENSION_CLASS = classOf[ManagementSparkExtensions].getName

  private[spark] val DEFAULT_URL = "http://127.0.0.1:8080/api/management/v1"
  private val DEFAULT_CATALOG_URL = ""

  /** 默认超时秒数；策略获取与解析器共用，包内可见。 */
  private[spark] val DEFAULT_TIMEOUT_SECONDS = 30

  /**
   * 编程式安装扩展。
   *
   * <p>{@code withExtensions} 必须在 {@code getOrCreate()} 之前调用。
   */
  def install(builder: SparkSession.Builder): SparkSession.Builder =
    builder.withExtensions { extensions => installAll(extensions) }

  /** 解析器与策略规则一起装；配置驱动的入口与编程式入口必须给出同一套扩展。 */
  private def installAll(extensions: SparkSessionExtensions): Unit = {
    extensions.injectParser { (session, parser) => new LakehouseRestSqlParser(parser, session) }
    // 行过滤 / 列脱敏（§5.4）：默认关闭，规则在 enforced=false 时是纯 no-op，见 apply
    extensions.injectPostHocResolutionRule { session => new PolicyInjectionRule(session) }
    extensions.injectPlannerStrategy { _ => new PolicyEnforcementStrategy }
  }

  /**
   * 按当前会话配置构造 REST 客户端。
   *
   * <p><b>每个会话一个客户端。</b>{@link LakehouseRestSqlParser} 内部把它持有为
   * `lazy val`，因此它是在该会话的**第一条**管理语句到来时创建的，之后整会话复用——
   * `HttpClient` 自带连接池与选择器线程，每条语句新建一个并不划算。
   * 由此推出一个使用上的约束：{@code spark.lakehouse.rest.management.url} 与
   * {@code spark.lakehouse.rest.token} 必须在第一条管理语句执行**之前**设好；
   * 之后再改不会生效，需要重建会话。
   */
  def clientFor(session: SparkSession): ManagementApiClient = {
    val url = setting(session, MANAGEMENT_URL, DEFAULT_URL)
    val catalogUrl = setting(session, CATALOG_URL, DEFAULT_CATALOG_URL)
    val token = setting(session, TOKEN, "")
    val timeout = setting(session, TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS.toString)
    new ManagementApiClient(url, token, catalogUrl, Duration.ofSeconds(parseTimeout(timeout)))
  }

  /**
   * 读取配置项：优先读会话的 SQL 配置，其次读 {@code SparkConf}。
   *
   * <p>两条路径都要看：{@code spark.conf.set} 写进 SQL 配置，而建会话时的
   * {@code .config(...)} 与命令行的 {@code --conf} 写进 {@code SparkConf}
   * （外加会话初始化时从 {@code SparkConf} 同步过来的那一份）。两者都不存在时用默认值。
   */
  private def setting(session: SparkSession, key: String, default: String): String = {
    val fromSqlConf = session.sessionState.conf.getConfString(key, "")
    if (fromSqlConf != null && fromSqlConf.nonEmpty) {
      fromSqlConf
    } else {
      session.sparkContext.getConf.get(key, default)
    }
  }

  /** 解析超时配置；写错或非正数时退化为默认值，不让会话启动失败。 */
  private[spark] def parseTimeout(raw: String): Long =
    try {
      val value = if (raw == null) 0L else raw.trim.toLong
      if (value <= 0) DEFAULT_TIMEOUT_SECONDS.toLong else value
    } catch {
      case _: NumberFormatException => DEFAULT_TIMEOUT_SECONDS.toLong
    }

  /** 默认超时秒数，供测试断言。 */
  private[spark] def defaultTimeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS

  /** 语句首关键字，供测试与文档输出使用；命令覆盖范围以语法文件为准。 */
  val STATEMENT_KEYWORDS: Seq[String] =
    Seq("create", "drop", "alter", "reset", "rotate", "grant", "revoke", "show")
      .map(_.toUpperCase(Locale.ROOT))
}
