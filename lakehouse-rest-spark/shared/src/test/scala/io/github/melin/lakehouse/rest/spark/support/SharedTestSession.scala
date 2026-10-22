package io.github.melin.lakehouse.rest.spark.support

import io.github.melin.lakehouse.rest.spark.{PaimonRestManagement, SparkJdkRequirement}
import org.apache.spark.sql.SparkSession

/**
 * 不需要真实 catalog 的测试共用的 `SparkSession` 与桩服务端。
 *
 * <p><b>为什么必须共用。</b>一个 JVM 只能有一个 `SparkContext`，`getOrCreate()` 在已有活动
 * 会话时直接返回它；而 `spark.sql.extensions` 只在**建会话那一刻**生效。若每个测试类各建
 * 各的会话，后建的类会静默拿到先建的那个，症状是「扩展没装上」却以「catalog 不存在」
 * 之类无关的报错收场。
 *
 * <p>因此这里只有一份会话：解析与管理语句的测试（{@code ManagementSqlExecutionTests}）
 * 和血缘抽取器的测试（{@code LineageExtractorTests}）都从这里拿。桩服务端也跟着放在一起——
 * 会话的地址配置必须指向它，两者拆开就会各建一个。
 *
 * <p>会话里装了本仓的管理扩展，但**没有**开血缘上报（`spark.lakehouse.rest.lineage.enabled`
 * 保持默认的 `false`）：抽取器测试直接对 `analyzed` 计划做断言，不需要真的发 HTTP；
 * 开着反而会让每个用例都往桩服务端发一遍。
 */
object SharedTestSession {

  /** 桩服务端：必须在会话之前起好，因为会话要拿它的地址当配置。 */
  lazy val stub: StubManagementServer = {
    val server = StubManagementServer.start()
    Runtime.getRuntime.addShutdownHook(new Thread(() => server.stop()))
    server
  }

  val token = "spark-session-token"

  lazy val session: SparkSession = {
    // 先确认 JDK 跑得了 Spark 的会话（JDK 24+ 会在 Hadoop 内部以
    // 「getSubject is not supported」炸掉），否则这里就给出该换哪个 JDK
    SparkJdkRequirement.requireHadoopCompatibleJdk()
    val warehouse = java.nio.file.Files.createTempDirectory("lakehouse-rest-spark-warehouse")
    val session = SparkSession.builder()
      .master("local[1]")
      .appName("lakehouse-rest-spark-tests")
      .config("spark.ui.enabled", "false")
      .config("spark.driver.host", "127.0.0.1")
      .config("spark.driver.bindAddress", "127.0.0.1")
      .config("spark.sql.shuffle.partitions", "1")
      .config("spark.sql.warehouse.dir", warehouse.toString)
      .config(PaimonRestManagement.MANAGEMENT_URL, stub.baseUrl)
      .config(PaimonRestManagement.TOKEN, token)
      .config("spark.sql.extensions", PaimonRestManagement.EXTENSION_CLASS)
      .getOrCreate()
    // 主动触发一次解析器构造，让配置错误的报错发生在建会话时而不是第一个测试里
    session.sessionState.sqlParser
    session
  }
}
