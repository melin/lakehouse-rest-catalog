package io.github.melin.lakehouse.rest.spark.policy

import scala.jdk.CollectionConverters._

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{AttributeReference, BindReferences, ExprId, GenericInternalRow}
import org.apache.spark.sql.types._
import org.apache.spark.unsafe.types.UTF8String
import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

/**
 * 八个标准脱敏函数的构造与语义测试（{@code PolicyExpressionBuilder#mask}）。
 *
 * <p>两层断言，缺一不可：
 *
 * <ul>
 *   <li><b>构造层</b>：表达式必须 {@code resolved}、结果类型必须等于目标列类型、
 *       生成正确的 Catalyst 形状——注入点在分析之后，隐式 cast 不会再来，
 *       类型不吻合的表达式放出去会在物理规则里炸（见类内各 fail-closed 用例）；</li>
 *   <li><b>语义层</b>：绑定后直接 eval 一行样例数据，脱敏值与服务端
 *       {@code PolicyExpressionEvaluatorTests} 的期望逐字一致——两侧词表之外，
 *       语义也必须逐字对齐。</li>
 * </ul>
 *
 * <p>不起 SparkSession：表达式构造与求值只依赖 catalyst。
 */
class PolicyExpressionBuilderTests {

  // ------------------------------------------------------------------ 装置

  /** 属性按此顺序排列，evalOn 的行值也按此顺序给。 */
  private var nextId = 1

  private def newAttr(name: String, dt: DataType): AttributeReference = {
    val a = AttributeReference(name, dt, nullable = true)(exprId = ExprId(nextId))
    nextId += 1
    a
  }

  private val s = newAttr("s", StringType)
  private val n = newAttr("n", LongType)
  private val ts = newAttr("ts", TimestampType)
  private val d = newAttr("d", DateType)
  private val attrs = Seq(s, n, ts, d)

  private def jmap(pairs: (String, Any)*): java.util.Map[String, Any] = {
    val m = new java.util.LinkedHashMap[String, Any]()
    pairs.foreach { case (k, v) => m.put(k, v) }
    m
  }

  private def mask(fn: String, args: Any*): java.util.Map[String, Any] = {
    val m = jmap("fn" -> fn)
    if (args.nonEmpty) m.put("args", args.toList.asJava)
    m
  }

  private def exprMask(expr: java.util.Map[String, Any]): java.util.Map[String, Any] =
    jmap("expr" -> expr)

  private def text(v: String): Any = UTF8String.fromString(v)

  /** 绑定到 attrs 后对一行数据求值；values 的顺序与 attrs 一致。 */
  private def evalOn(expr: org.apache.spark.sql.catalyst.expressions.Expression,
                     values: Any*): Any = {
    val bound = BindReferences.bindReference(expr, attrs)
    val row: InternalRow = new GenericInternalRow(values.toArray[Any])
    bound.eval(row)
  }

  private def str(v: Any): String = v.asInstanceOf[UTF8String].toString

  private def hex(algorithm: String, value: String): String =
    java.security.MessageDigest.getInstance(algorithm)
      .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
      .map("%02x" format _).mkString

  // ------------------------------------------------------------------ 1. mask_alphanum

  /** 大写→X、小写→x、数字→n、其余原样（与 Hive mask() 一致，含 Unicode 保留）。 */
  @Test
  def maskAlphanumReplacesByCharacterClass(): Unit = {
    val expr = PolicyExpressionBuilder.mask(mask("mask_alphanum"), s, attrs)
    assertTrue(expr.resolved)
    assertEquals(StringType, expr.dataType)
    assertEquals("Xxx-nnn_nnnn", str(evalOn(expr, text("Abc-138_0066"))))
    // 汉字不在字符类里，原样保留——逐字符而不是正则整段 replace
    assertEquals("张三Xxxnnn", str(evalOn(expr, text("张三Abc123"))))
    assertNull(evalOn(expr, null), "NULL 进 NULL 出")
  }

  /** 服务端建策略时按 STRING 类别拒（PolicyExpressions）；引擎类型规则兜底。 */
  @Test
  def maskAlphanumOnNumericColumnFailsClosed(): Unit = {
    val e = assertThrows(classOf[PolicyEnforcementException], () =>
      PolicyExpressionBuilder.mask(mask("mask_alphanum"), n, attrs))
    assertTrue(e.getMessage.contains("string"), "报错应说明两侧类型: " + e.getMessage)
  }

  // ------------------------------------------------------------------ 2. show_first_n

  @Test
  def showFirstNKeepsPrefixAndMasksRest(): Unit = {
    val expr = PolicyExpressionBuilder.mask(mask("show_first_n", 4), s, attrs)
    assertTrue(expr.resolved)
    assertEquals(StringType, expr.dataType)
    assertEquals("1380nnnnnnn", str(evalOn(expr, text("13800000001"))))
    // 起点越过串长返回空串：n 超过串长时整串保留（前缀=全串，后缀=空串）
    assertEquals("a", str(evalOn(expr, text("a"))))
    assertNull(evalOn(expr, null))
  }

  @Test
  def showFirstNRejectsBadArguments(): Unit = {
    // 缺参 / 负数 / 非数字
    for (bad <- Seq(mask("show_first_n"), mask("show_first_n", -1), mask("show_first_n", "4"))) {
      assertThrows(classOf[PolicyEnforcementException],
        () => PolicyExpressionBuilder.mask(bad, s, attrs), () => "bad args: " + bad)
    }
  }

  // ------------------------------------------------------------------ 3. show_last_n

  @Test
  def showLastNKeepsSuffixAndMasksPrefix(): Unit = {
    val expr = PolicyExpressionBuilder.mask(mask("show_last_n", 4), s, attrs)
    assertTrue(expr.resolved)
    assertEquals(StringType, expr.dataType)
    // 前 7 位打码、末 4 位原样；负数起点不越过串头（len < n 时尾部就是全串）
    assertEquals("nnnnnnn0001", str(evalOn(expr, text("13800000001"))))
    // len < n 时尾部起点取 -len：整串原样保留（打码部分为空）
    assertEquals("ab", str(evalOn(expr, text("ab"))))
    assertNull(evalOn(expr, null))
  }

  // ------------------------------------------------------------------ 4. replace_with_null

  /** 唯一全类型可用的标准 mask：NULL 字面量按列类型造，结果类型恒等于列类型。 */
  @Test
  def replaceWithNullWorksOnEveryColumnType(): Unit = {
    for (target <- Seq(s, n, ts, d)) {
      val expr = PolicyExpressionBuilder.mask(mask("replace_with_null"), target, attrs)
      assertTrue(expr.resolved, s"replace_with_null 落 ${target.dataType} 应构造成功")
      assertEquals(target.dataType, expr.dataType, "结果类型必须等于列类型")
      assertNull(evalOn(expr, text("x"), java.lang.Long.valueOf(1L),
        java.lang.Integer.valueOf(0), java.lang.Integer.valueOf(0)))
    }
  }

  @Test
  def replaceWithNullRejectsArguments(): Unit = {
    assertThrows(classOf[PolicyEnforcementException],
      () => PolicyExpressionBuilder.mask(mask("replace_with_null", 1), s, attrs))
  }

  /** 服务端建策略时就拒（takes no arguments）；引擎是第二道闸。 */
  @Test
  def argumentFreeStandardMasksRejectArguments(): Unit = {
    for (bad <- Seq(mask("mask_alphanum", 1), mask("replace_with_null", 1),
        mask("truncate_to_year", 1), mask("truncate_to_month", 1))) {
      assertThrows(classOf[PolicyEnforcementException],
        () => PolicyExpressionBuilder.mask(bad, d, attrs), () => "bad args: " + bad)
    }
  }

  // ------------------------------------------------------------------ 5. mask_to_fixed_value

  @Test
  def maskToFixedValueReplacesWithTheLiteral(): Unit = {
    val expr = PolicyExpressionBuilder.mask(mask("mask_to_fixed_value", "***"), s, attrs)
    assertTrue(expr.resolved)
    assertEquals(StringType, expr.dataType)
    assertEquals("***", str(evalOn(expr, text("anything"))))
    // Literal 替换不看行值：NULL 行同样变成固定值（与服务端 PolicyExpressionEvaluator
    // 的 mask_to_fixed_value 无条件返回 rawArgs.get(0) 一致——不保留缺失语义）
    assertEquals("***", str(evalOn(expr, null)))
  }

  @Test
  def maskToFixedValueRejectsBadShapeAndNonStringColumn(): Unit = {
    // 空串 / 非字符串参数
    for (bad <- Seq(mask("mask_to_fixed_value", ""), mask("mask_to_fixed_value", 1),
        mask("mask_to_fixed_value"))) {
      assertThrows(classOf[PolicyEnforcementException],
        () => PolicyExpressionBuilder.mask(bad, s, attrs), () => "bad args: " + bad)
    }
    // 数字列：服务端 requireStringColumn 拒，引擎结果类型规则兜底
    val e = assertThrows(classOf[PolicyEnforcementException], () =>
      PolicyExpressionBuilder.mask(mask("mask_to_fixed_value", "***"), n, attrs))
    assertTrue(e.getMessage.contains("bigint"), "报错应说明两侧类型: " + e.getMessage)
  }

  // ------------------------------------------------------------------ 6/7. truncate_to_year / truncate_to_month

  /** DATE 与 TIMESTAMP 都放行（服务端 requireTemporal 同口径）：TIMESTAMP 走 TruncTimestamp。 */
  @Test
  def truncateToYearResolvesOnDateAndTimestamp(): Unit = {
    val onDate = PolicyExpressionBuilder.mask(mask("truncate_to_year"), d, attrs)
    assertTrue(onDate.resolved)
    assertEquals(DateType, onDate.dataType)

    val onTimestamp = PolicyExpressionBuilder.mask(mask("truncate_to_year"), ts, attrs)
    assertTrue(onTimestamp.resolved, "TIMESTAMP 列应走 TruncTimestamp 并补上会话时区")
    assertEquals(TimestampType, onTimestamp.dataType)
    assertTrue(onTimestamp.getClass.getSimpleName.contains("TruncTimestamp"))
  }

  @Test
  def truncateToMonthResolvesOnDateAndTimestamp(): Unit = {
    val onDate = PolicyExpressionBuilder.mask(mask("truncate_to_month"), d, attrs)
    assertTrue(onDate.resolved)
    assertEquals(DateType, onDate.dataType)

    val onTimestamp = PolicyExpressionBuilder.mask(mask("truncate_to_month"), ts, attrs)
    assertTrue(onTimestamp.resolved)
    assertEquals(TimestampType, onTimestamp.dataType)
  }

  /** 数字列没有「截到月」的语义：结果类型校验在构造期拒绝。 */
  @Test
  def truncateOnNumericFailsClosed(): Unit = {
    val e = assertThrows(classOf[PolicyEnforcementException], () =>
      PolicyExpressionBuilder.mask(mask("truncate_to_month"), n, attrs))
    assertTrue(e.getMessage.contains("date"), "报错应说明两侧类型: " + e.getMessage)
  }

  // ------------------------------------------------------------------ 8. hash

  /** 三种算法都构造成功；语义 = 对 UTF-8 字节做摘要、小写十六进制（与服务端 digest 对齐）。 */
  @Test
  def hashMasksProduceLowercaseHexOverUtf8Bytes(): Unit = {
    for ((algorithm, digestName) <- Seq("MD5" -> "MD5", "SHA-256" -> "SHA-256",
        "SHA-512" -> "SHA-512")) {
      val expr = PolicyExpressionBuilder.mask(mask("hash", algorithm), s, attrs)
      assertTrue(expr.resolved, s"hash($algorithm) 落字符串列应自带 string→binary cast")
      assertEquals(StringType, expr.dataType)
      assertEquals(hex(digestName, "13800000001"),
        str(evalOn(expr, text("13800000001"))), s"hash($algorithm) 的摘要值")
    }
    assertNull(evalOn(
      PolicyExpressionBuilder.mask(mask("hash", "MD5"), s, attrs), null))
  }

  @Test
  def hashRejectsUnknownAlgorithm(): Unit = {
    assertThrows(classOf[PolicyEnforcementException],
      () => PolicyExpressionBuilder.mask(mask("hash", "CRC32"), s, attrs))
    assertThrows(classOf[PolicyEnforcementException],
      () => PolicyExpressionBuilder.mask(mask("hash"), s, attrs))
  }

  // ------------------------------------------------------------------ escape hatch 与 date_trunc（既有覆盖保留）

  @Test
  def upperOnNumericResolvesViaDisplayString(): Unit = {
    val expr = PolicyExpressionBuilder.mask(
      exprMask(jmap("fn" -> "upper", "args" -> List(jmap("col" -> "n")).asJava)),
      s, attrs)
    assertTrue(expr.resolved, "upper(数字列) 应显式 cast 到字符串后构造: " + expr)
    assertEquals(StringType, expr.dataType)
    assertEquals("138", str(evalOn(expr, text("placeholder"), java.lang.Long.valueOf(138L),
      java.lang.Integer.valueOf(0), java.lang.Integer.valueOf(0))))
  }

  @Test
  def dateTruncOnTimestampResolves(): Unit = {
    val expr = PolicyExpressionBuilder.mask(
      exprMask(jmap("fn" -> "date_trunc",
        "args" -> List(jmap("lit" -> "month"), jmap("col" -> "ts")).asJava)),
      ts, attrs)
    assertTrue(expr.resolved, "date_trunc 落 TIMESTAMP 参数应走 TruncTimestamp: " + expr)
    assertEquals(TimestampType, expr.dataType)
  }

  /** escape hatch 造出类型不吻合的表达式时，必须在构造期指名失败，不能穿透到物理规则。 */
  @Test
  def typeMismatchViaCastEscapeHatchFailsClosed(): Unit = {
    val e = assertThrows(classOf[PolicyEnforcementException], () =>
      PolicyExpressionBuilder.mask(
        exprMask(jmap("fn" -> "cast", "operand" -> jmap("col" -> "n"), "to" -> "BOOLEAN")),
        n, attrs))
    assertTrue(e.getMessage.contains("bigint"), "报错应说明两侧类型: " + e.getMessage)
  }
}
