package io.github.melin.lakehouse.rest.spark.policy

import java.time.Duration

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.control.NonFatal

import io.github.melin.lakehouse.rest.spark.PaimonRestManagement
import io.github.melin.lakehouse.rest.spark.client.{ManagementApiClient, ManagementApiException, PolicyCache}
import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient.ResolvedPolicyInfo
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation
import org.slf4j.LoggerFactory

/**
 * 策略获取：缓存命中 → 批量拉取 → 失败按 fail-open / fail-closed 处置（§5.4）。
 *
 * <p><b>每个会话一个实例</b>：注入规则在 sessionState 构建期创建，本类跟着规则走，
 * {@code ManagementApiClient}（内含连接池）与 {@link PolicyCache} 因此也是每会话一份——
 * 与管理语句解析器持有客户端的方式一致。
 *
 * <p><b>批量拉取。</b>一条 SQL 常常扫好几张表，按 catalog 分组后一次 {@code resolve}
 * 问一批（服务端逐表判 TABLE:SELECT，调用者本来就在读这些表）。缓存键
 * {@code (catalog, db, table, 令牌)}：令牌即主体分片，不同主体拿到的表达式不同。
 *
 * <p><b>失败方向（本类的核心约定）：</b>
 *
 * <ul>
 *   <li>fail-closed（默认）：拉不到 → 抛 {@link PolicyEnforcementException}，查询失败。
 *       fail-open 的失效方向是明文泄露，fail-closed 的失效方向是读不了——后者可以修；</li>
 *   <li>fail-open（显式开关）：只对「服务不可达 / 5xx」放行并打 WARN（能从日志里查），
 *       表达式解析失败之类的本地错误**不放行**——那不是服务的问题，是策略的问题，
 *       放行等于执行了一半的策略。</li>
 * </ul>
 */
private[spark] class PolicyFetcher(session: SparkSession) {

  private val log = LoggerFactory.getLogger(classOf[PolicyFetcher])

  private lazy val client: ManagementApiClient = {
    val url = PolicySettings.stringOf(session, PaimonRestManagement.MANAGEMENT_URL,
      PaimonRestManagement.DEFAULT_URL)
    val token = PolicySettings.stringOf(session, PaimonRestManagement.TOKEN, "")
    val timeout = PolicySettings.stringOf(session, PaimonRestManagement.TIMEOUT_SECONDS,
      PaimonRestManagement.DEFAULT_TIMEOUT_SECONDS.toString)
    new ManagementApiClient(url, token,
      Duration.ofSeconds(PaimonRestManagement.parseTimeout(timeout)))
  }

  private lazy val cache: PolicyCache = new PolicyCache(PolicySettings.ttlMillis(session))

  private val principalShard: String = PolicySettings.principalShard(session)

  /**
   * 「按无策略放行」的缓存哨兵：NONE / EXEMPT 也要进缓存——这类放行决定同样是按
   * 主体算的，不缓存的话豁免主体每次查询都要多一次 HTTP。{@code PolicyCache} 的
   * 条目外层有 Entry 包装，值可以为 null，但用哨兵能让「缓存里是 null」与「没缓存」
   * 在本类内部区分开，不必给缓存层加 Optional。
   */
  private val NoPolicy = new ResolvedPolicyInfo("", "NONE", 0L, null,
    java.util.List.of(), false, null)

  /**
   * 解析一批表的策略。
   *
   * @return 每个传入 ref（{@code catalog.db.table}）到解析结果的映射；
   *         值为 {@code None} 表示「按无策略放行」——没策略（NONE）、豁免（EXEMPT）、
   *         以及 fail-open 下的「拿不到」三种情况在引擎侧的处理完全一样
   */
  def resolve(refs: Seq[String]): Map[String, Option[ResolvedPolicyInfo]] = {
    if (refs.isEmpty) {
      return Map.empty
    }
    val result = mutable.Map[String, Option[ResolvedPolicyInfo]]()
    val missing = mutable.Map[String, mutable.Set[String]]() // catalog -> refs

    refs.foreach { ref =>
      val (catalog, db, table) = PolicyRef.parse(ref)
      val cached = cache.get(PolicyCache.key(catalog, db, table, principalShard))
      if (cached != null) {
        result.put(ref, if (cached == NoPolicy) None else Some(cached.asInstanceOf[ResolvedPolicyInfo]))
      } else {
        missing.getOrElseUpdate(catalog, mutable.Set[String]()) += ref
      }
    }

    missing.foreach { case (catalog, refsOfCatalog) =>
      fetchAndFill(catalog, refsOfCatalog.toSeq.sorted, result)
    }
    result.toMap
  }

  /** FOUND 才算「有策略」；NONE / EXEMPT 缓存并按无策略放行。 */
  private def isEnforced(info: ResolvedPolicyInfo): Boolean = "FOUND" == info.status

  private def fetchAndFill(catalog: String, refs: Seq[String],
                           result: mutable.Map[String, Option[ResolvedPolicyInfo]]): Unit = {
    // 协议里的 tables 是 db.table 两段（catalog 由查询参数给出）
    val tables = refs.map(ref => ref.stripPrefix(catalog + ".")).asJava
    var fetched: Seq[ResolvedPolicyInfo] = null
    try {
      fetched = client.resolvePolicies(catalog, tables).asScala.toSeq
    } catch {
      case e: ManagementApiException if PolicySettings.failOpen(session) =>
        log.warn(PolicyFetcher.warnText(catalog, refs, e)
          + " — continuing WITHOUT policies because spark.lakehouse.rest.policy.fail-open=true;"
          + " queries on protected tables will return UNFILTERED / UNMASKED data", e)
        refs.foreach { ref =>
          result.put(ref, None)
        }
        return
      case NonFatal(e) =>
        throw PolicyFetcher.failClosed(catalog, refs, e)
    }
    val byTable = fetched.map(info => (catalog + "." + info.table, info)).toMap
    refs.foreach { ref =>
      // 服务端必须对请求过的每张表回一项；缺了是服务端行为异常，说清楚，不猜
      val info = byTable.getOrElse(ref, throw new PolicyEnforcementException(
        s"data policy resolve returned no entry for $ref; the policy service must return"
          + " an entry for every requested table"))
      val (catalogOfRef, db, table) = PolicyRef.parse(ref)
      val key = PolicyCache.key(catalogOfRef, db, table, principalShard)
      if (isEnforced(info)) {
        cache.put(key, info)
        result.put(ref, Some(info))
      } else {
        cache.put(key, NoPolicy)
        result.put(ref, None)
      }
    }
  }
}

/** V2 relation → 三段全名（{@code catalog.db.table}）的拆装。 */
private[spark] object PolicyRef {

  def qualify(catalog: String, database: String, table: String): String =
    s"$catalog.$database.$table"

  /** namespace 可能是多级（Paimon 的多级 namespace 用点号拼成一段）。 */
  def qualify(catalog: String, namespace: Seq[String], table: String): String =
    (namespace.filter(_.nonEmpty) :+ table).mkString(catalog + ".", ".", "")

  /**
   * relation 的三段全名；没有 identifier 返回 None（外部源，无策略可言）。
   *
   * <p>identifier 在而 catalog 插件缺失时（理论上只出现在手工构造的计划里），退回
   * 会话默认 catalog——与服务端「唯一的三段全名」口径一致，宁可解析出一个可能不存在的
   * 策略键，也不跳过。
   */
  def of(rel: DataSourceV2Relation, session: SparkSession): Option[String] = {
    val catalog = rel.catalog.map(_.name()).getOrElse(defaultCatalog(session))
    rel.identifier.map(id => qualify(catalog, id.namespace().toSeq, id.name()))
  }

  private def defaultCatalog(session: SparkSession): String =
    try session.conf.get("spark.sql.defaultCatalog", "spark_catalog")
    catch { case _: Throwable => "spark_catalog" }

  def parse(ref: String): (String, String, String) = {
    val parts = ref.split("\\.")
    if (parts.length != 3) {
      // 调用方只应传入 qualify 的产物；走到这里说明出现了没见过的形状，fail-closed
      throw new PolicyEnforcementException(s"cannot resolve table reference '$ref' into"
        + " catalog.database.table; refusing to continue without knowing which table"
        + " a policy would apply to")
    }
    (parts(0), parts(1), parts(2))
  }
}

private[spark] object PolicyFetcher {

  private val log = LoggerFactory.getLogger(classOf[PolicyFetcher])

  private def warnText(catalog: String, refs: Seq[String], cause: Exception): String =
    s"cannot resolve data policies for $catalog tables ${refs.mkString(", ")}: ${cause.getMessage}"

  /** fail-closed 的统一出口：报错文本直接面向用户（出现在查询异常里）。 */
  def failClosed(catalog: String, refs: Seq[String], cause: Throwable): PolicyEnforcementException = {
    val message = s"cannot resolve data policies for $catalog tables ${refs.mkString(", ")}: " +
      (cause match {
        case e: ManagementApiException => e.getMessage
        case other => other.toString
      })
    new PolicyEnforcementException(message
      + " — failing closed (a policy cannot be read, so the table cannot be read safely);"
      + " start the policy service or set spark.lakehouse.rest.policy.fail-open=true"
      + " explicitly if availability matters more than protection here", cause)
  }
}
