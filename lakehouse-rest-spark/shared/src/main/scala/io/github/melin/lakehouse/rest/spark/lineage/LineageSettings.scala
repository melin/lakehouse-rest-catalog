package io.github.melin.lakehouse.rest.spark.lineage

import io.github.melin.lakehouse.rest.spark.PaimonRestManagement
import org.apache.spark.SparkConf
import org.apache.spark.sql.SparkSession

/**
 * `spark.paimon.rest.lineage.*` 的取值（§10.2）。
 *
 * <p><b>两个入口，不是两个实现。</b>取值逻辑只有一份（{@code resolve}），差别只在
 * 「去哪儿找原始字符串」：
 *
 * <ul>
 *   <li>{@link #fromSession}：SQLConf 优先、{@code SparkConf} 兜底。**首次查询时**用这个——
 *       `spark.conf.set` 写进 SQLConf，建会话时的 {@code .config(...)} 与命令行 `--conf`
 *       写进 `SparkConf`，只看一处都会得到「配了没生效」。</li>
 *   <li>{@link #fromSparkConf}：只读 `SparkConf`。**会话状态构建期间**只能用这个。</li>
 * </ul>
 *
 * <p><b>为什么必须两套（这是踩过的坑）。</b>扩展式安装走
 * `injectParser { (session, parser) => ... }`，而这个工厂是在
 * `BaseSessionStateBuilder#sqlParser` 里被调用的——那正是 `sessionState` 正在构建的时刻。
 * 此时读 `session.sessionState.conf` 会重新进入构建流程：`instantiateSessionState` →
 * `buildParser` → 工厂 → 又读 `sessionState`……表现为 `StackOverflowError`，栈顶落在
 * `ClassLoader.defineClass1` 上，看起来像类加载问题，实际是重入。
 *
 * <p>由此推出一条使用约束：{@code spark.paimon.rest.lineage.enabled} 决定「挂不挂监听器」，
 * 而挂载发生在会话状态构建期间，因此这一位**只能从头到尾靠 `SparkConf` 给**（`--conf`、
 * `spark-submit --conf`、`SparkSession.builder().config(...)`）。运行期
 * `spark.conf.set(..., "true")` 改不动它——那时会话状态已经建好，监听器要么已经挂上、
 * 要么永远不挂。其余各项（列级开关、队列、上报地址）在首次查询时才读，那时 SQLConf 可用。
 *
 * <p><b>默认全关。</b>`enabled=false` 时**不注册监听器**：监听器本身有成本（每次查询都进一次
 * 回调），关掉要真的不挂上去，不能靠回调里 `if (!enabled) return`。
 */
private[spark] final case class LineageSettings(enabled: Boolean,
                                                columnLevel: Boolean,
                                                maxColumns: Int,
                                                queueSize: Int,
                                                flushIntervalMs: Int,
                                                url: String,
                                                token: String,
                                                timeoutSeconds: Long)

private[spark] object LineageSettings {

  val PREFIX = "spark.paimon.rest.lineage."

  val ENABLED = PREFIX + "enabled"
  val COLUMN_LEVEL = PREFIX + "column-level"
  val MAX_COLUMNS = PREFIX + "max-columns"
  val QUEUE_SIZE = PREFIX + "queue-size"
  val FLUSH_INTERVAL_MS = PREFIX + "flush-interval-ms"
  val URL = PREFIX + "url"
  val TOKEN = PREFIX + "token"

  val DEFAULT_ENABLED = false
  val DEFAULT_COLUMN_LEVEL = true
  val DEFAULT_MAX_COLUMNS = 2000
  val DEFAULT_QUEUE_SIZE = 500
  val DEFAULT_FLUSH_INTERVAL_MS = 2000

  /** 首次查询时用：SQLConf 优先，`SparkConf` 兜底。 */
  def fromSession(session: SparkSession): LineageSettings =
    resolve { key =>
      val fromSqlConf = session.sessionState.conf.getConfString(key, "")
      if (fromSqlConf != null && fromSqlConf.nonEmpty) Some(fromSqlConf)
      else session.sparkContext.getConf.getOption(key)
    }

  /** 会话状态构建期间用：**不碰** `sessionState`，否则重入（见类注释）。 */
  def fromSparkConf(conf: SparkConf): LineageSettings =
    resolve(key => conf.getOption(key))

  /**
   * 注册开关。单独一个方法是因为它必须在注册那一刻就有答案，而注册那一刻只有 `SparkConf`
   * 可读；把范围收窄成「一个布尔」比让整个 `LineageSettings` 都退化成 `SparkConf` 诚实。
   */
  def enabledAtRegistration(conf: SparkConf): Boolean =
    boolean(key => conf.getOption(key), ENABLED, DEFAULT_ENABLED)

  private def resolve(lookup: String => Option[String]): LineageSettings =
    new LineageSettings(
      enabled = boolean(lookup, ENABLED, DEFAULT_ENABLED),
      columnLevel = boolean(lookup, COLUMN_LEVEL, DEFAULT_COLUMN_LEVEL),
      maxColumns = int(lookup, MAX_COLUMNS, DEFAULT_MAX_COLUMNS),
      queueSize = int(lookup, QUEUE_SIZE, DEFAULT_QUEUE_SIZE),
      flushIntervalMs = int(lookup, FLUSH_INTERVAL_MS, DEFAULT_FLUSH_INTERVAL_MS),
      // 留空 = 复用管理 API 的地址与令牌：客户端本来就有那一套，不再造一个配置世界
      url = string(lookup, URL, ""),
      token = string(lookup, TOKEN, ""),
      timeoutSeconds = PaimonRestManagement.parseTimeout(
        string(lookup, PaimonRestManagement.TIMEOUT_SECONDS,
          PaimonRestManagement.defaultTimeoutSeconds.toString)))

  private def string(lookup: String => Option[String], key: String, default: String): String =
    lookup(key).map(_.trim).filter(_.nonEmpty).getOrElse(default)

  /** 布尔值写错时退化为默认值：一个拼错的配置不该让会话起不来。 */
  private def boolean(lookup: String => Option[String], key: String, default: Boolean): Boolean =
    lookup(key).map(_.trim).filter(_.nonEmpty)
      .map(raw => raw.equalsIgnoreCase("true"))
      .getOrElse(default)

  private def int(lookup: String => Option[String], key: String, default: Int): Int =
    lookup(key).map(_.trim).filter(_.nonEmpty).map { raw =>
      try {
        val value = raw.toInt
        if (value > 0) value else default
      } catch {
        case _: NumberFormatException => default
      }
    }.getOrElse(default)
}
