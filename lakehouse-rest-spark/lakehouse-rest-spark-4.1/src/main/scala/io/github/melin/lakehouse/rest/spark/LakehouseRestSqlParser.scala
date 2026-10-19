package io.github.melin.lakehouse.rest.spark

import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.types.StructType

/**
 * Spark 4.1 的具体解析器。
 *
 * <p>与 3.5 侧同名同签名，唯一差别是 `ParserInterface` 在 4.x 里多了一个抽象成员
 * `parseRoutineParam`——共享基类不能实现它（3.5 的接口里没有这个方法，写了会报
 * 「方法不是父类型的成员」），所以补在这里。
 *
 * <p>`parseRoutineParam` 解析的是 `CREATE FUNCTION ... (a INT, b STRING)` 里的参数列表。
 * 本扩展不接管这类语句，直接交给原生解析器：管理语句的入口是 `parsePlan`，
 * 走到这里说明调用方要的就是 Spark 自己的例行参数解析。
 */
class LakehouseRestSqlParser(delegate: ParserInterface, clientFactory: () => ManagementApiClient)
  extends AbstractLakehouseRestSqlParser(delegate, clientFactory) {

  def this(delegate: ParserInterface, session: SparkSession) =
    this(delegate, () => PaimonRestManagement.clientFor(session))

  override def parseRoutineParam(sqlText: String): StructType = delegate.parseRoutineParam(sqlText)
}
