package io.github.melin.lakehouse.rest.spark

import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.expressions.Expression
import org.apache.spark.sql.catalyst.parser.ParserInterface
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.types.{DataType, StructType}

import scala.collection.mutable.ListBuffer

/**
 * 原生解析器的替身：记录被交回的语句，用于验证「不是管理语句时原样交给原生解析器」。
 *
 * <p>放在共享测试源码里、但声明为抽象类：`ParserInterface` 的抽象成员在 Spark 3.5
 * 与 4.x 之间不同（4.x 多一个 `parseRoutineParam`），写死在这里会让 3.5 编译失败。
 * 具体子类 {@link SparkParserDelegate} 由各版本模块的测试源码提供——3.5 侧不补任何
 * 东西，4.x 侧补那一个方法。
 */
abstract class ParserDelegateStub extends ParserInterface {

  /** 被交回原生解析器的语句，按顺序记录。 */
  val delegated: ListBuffer[String] = ListBuffer.empty[String]

  override def parsePlan(sqlText: String): LogicalPlan = {
    delegated += sqlText
    throw new UnsupportedOperationException("delegated: " + sqlText)
  }

  override def parseQuery(sqlText: String): LogicalPlan = parsePlan(sqlText)

  override def parseExpression(sqlText: String): Expression =
    throw new UnsupportedOperationException("unused")

  override def parseTableIdentifier(sqlText: String): TableIdentifier =
    throw new UnsupportedOperationException("unused")

  override def parseFunctionIdentifier(sqlText: String): FunctionIdentifier =
    throw new UnsupportedOperationException("unused")

  override def parseMultipartIdentifier(sqlText: String): Seq[String] =
    throw new UnsupportedOperationException("unused")

  override def parseTableSchema(sqlText: String): StructType =
    throw new UnsupportedOperationException("unused")

  override def parseDataType(sqlText: String): DataType =
    throw new UnsupportedOperationException("unused")
}
