package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.parser.ParserInterface

/**
 * Spark 3.5 的具体解析器。
 *
 * <p>共享基类已经实现了 `ParserInterface` 的全部抽象成员（3.5 的接口里没有
 * `parseRoutineParam`），因此这里只需要把两个构造方式接出来：
 *
 *   - `(delegate, clientFactory)`：解析层测试用，客户端可以直接给一个不监听的地址；
 *   - `(delegate, session)`：会话用，客户端从 SQL conf / SparkConf 读配置后延迟创建。
 *
 * <p>4.1 侧的同一个类还多一个 `parseRoutineParam`，见 -4.1 模块。
 */
class LakehouseRestSqlParser(delegate: ParserInterface, clientFactory: () => ManagementApiClient)
  extends AbstractLakehouseRestSqlParser(delegate, clientFactory) {

  def this(delegate: ParserInterface, session: SparkSession) =
    this(delegate, () => PaimonRestManagement.clientFor(session))
}
