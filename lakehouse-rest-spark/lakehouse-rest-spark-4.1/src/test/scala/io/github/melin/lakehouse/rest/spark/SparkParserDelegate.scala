package io.github.melin.lakehouse.rest.spark

import org.apache.spark.sql.types.StructType

/**
 * 测试用的原生解析器替身（Spark 4.1）。
 *
 * <p>比 3.5 侧多实现一个 `parseRoutineParam`：它是 4.x 里 `ParserInterface` 的抽象成员，
 * 共享基类不能写（3.5 上没有这个方法）。
 */
class SparkParserDelegate extends ParserDelegateStub {

  override def parseRoutineParam(sqlText: String): StructType =
    throw new UnsupportedOperationException("unused")
}
