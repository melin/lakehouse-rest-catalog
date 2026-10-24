package io.github.melin.lakehouse.rest.spark.policy

import org.apache.spark.sql.SparkSession

/**
 * 引擎侧行过滤 / 列脱敏的配置键与读取
 * （{@code docs/row-filter-column-mask-design.md} §5.4）。
 *
 * <p><b>三个开关的失效方向各不相同，不能合并成一个：</b>
 *
 * <ul>
 *   <li>{@code enforced}（默认 false）：整个引擎侧强制是否启用。默认关——
 *       扩展同时承担管理语句解析，装它的会话未必接了策略服务；开着而服务不在，
 *       fail-closed 会把所有查询打死。与服务端 {@code lakehouse.rest.policy.enabled}
 *       「默认关」同一个取向；</li>
 *   <li>{@code fail-open}（默认 false）：策略服务不可达时放行还是拒读。默认拒读——
 *       fail-open 的失效方向是明文泄露，fail-closed 的失效方向是读不了，后者可以修；</li>
 *   <li>{@code cacheTtlSeconds}（默认 60）：策略缓存的存活时间，0 关闭。策略是安全配置，
 *       不一致窗口要短（对照：同类实现的默认是 5 分钟，§2.2）。</li>
 * </ul>
 *
 * <p>读取路径与 {@code PaimonRestManagement#setting} 一致：先读 SQLConf（{@code spark.conf.set}
 * 写的那份），再读 SparkConf（建会话时 {@code .config(...)} 写的那份）。缓存的键里含主体分片，
 * 主体由令牌指代——令牌也在这里读。
 */
private[spark] object PolicySettings {

  /** 引擎侧强制开关。 */
  val ENFORCED = "spark.lakehouse.rest.policy.enforced"

  /** 策略服务不可达时是否放行（fail-open）。默认 false = fail-closed。 */
  val FAIL_OPEN = "spark.lakehouse.rest.policy.fail-open"

  /** 策略缓存 TTL（秒）；0 关闭缓存。 */
  val CACHE_TTL_SECONDS = "spark.lakehouse.rest.policy.cacheTtlSeconds"

  val DEFAULT_TTL_SECONDS: Long = 60L

  def enforced(session: SparkSession): Boolean =
    booleanOf(session, ENFORCED, defaultValue = false)

  def failOpen(session: SparkSession): Boolean =
    booleanOf(session, FAIL_OPEN, defaultValue = false)

  /** TTL 毫秒；写错或负数按 0（关闭）处理——写错了宁可不用缓存，不可猜一个长 TTL。 */
  def ttlMillis(session: SparkSession): Long = {
    val raw = stringOf(session, CACHE_TTL_SECONDS, "")
    if (raw == null || raw.isBlank) {
      DEFAULT_TTL_SECONDS * 1000L
    } else {
      try math.max(raw.trim.toLong, 0L) * 1000L
      catch { case _: NumberFormatException => 0L }
    }
  }

  /**
   * 调用者令牌（缓存键的主体分片）。
   *
   * <p>引擎的「我是谁」只有令牌一个来源：{@code resolve} 的响应里虽有 {@code principal}
   * 回显，但那要等第一次请求回来才有，而缓存键在请求之前就要算出来。令牌与主体一一对应
   * （服务端按令牌认证出主体），用它分片粒度等价。
   */
  def principalShard(session: SparkSession): String = {
    val token = stringOf(session, "spark.lakehouse.rest.token", "")
    if (token == null || token.isBlank) "anonymous" else token
  }

  private def booleanOf(session: SparkSession, key: String, defaultValue: Boolean): Boolean = {
    val raw = stringOf(session, key, null)
    if (raw == null || raw.isBlank) defaultValue
    else raw.trim.equalsIgnoreCase("true")
  }

  /** 与 {@code PaimonRestManagement#setting} 相同的两级读取：SQLConf 优先，SparkConf 兜底。 */
  private[spark] def stringOf(session: SparkSession, key: String, default: String): String = {
    val fromSqlConf = session.sessionState.conf.getConfString(key, "")
    if (fromSqlConf != null && fromSqlConf.nonEmpty) {
      fromSqlConf
    } else {
      session.sparkContext.getConf.get(key, default)
    }
  }
}
