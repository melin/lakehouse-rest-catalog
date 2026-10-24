package io.github.melin.lakehouse.rest.spark.policy

import java.time.{Instant, LocalDate}
import java.util.{ArrayList, LinkedHashMap, Locale}
import java.util.{Map => JavaMap}

import io.github.melin.lakehouse.rest.spark.ManagementSqlException
import org.apache.spark.sql.catalyst.analysis.{UnresolvedAttribute, UnresolvedFunction}
import org.apache.spark.sql.catalyst.expressions.{
  And,
  AttributeReference,
  CaseWhen,
  Cast,
  Coalesce,
  CurrentUser,
  EqualTo,
  Expression,
  GreaterThan,
  GreaterThanOrEqual,
  In,
  IsNotNull,
  IsNull,
  LessThan,
  LessThanOrEqual,
  Like,
  Literal,
  Not,
  Or
}
import org.apache.spark.sql.types.{
  BooleanType,
  ByteType,
  DataType,
  DateType,
  DoubleType,
  FloatType,
  IntegerType,
  LongType,
  NullType,
  NumericType,
  ShortType,
  StringType,
  TimestampType
}
import org.apache.spark.unsafe.types.UTF8String

/**
 * Catalyst 表达式 → 策略 AST（JSON）的单向转换器（§13.4）。
 *
 * <p>管理语句的表达式片段不进 ANTLR 语法，而是交给会话自己的
 * {@code ParserInterface#parseExpression} 解析成 Catalyst 树，再由这里逐节点转成
 * 服务端白名单的 AST 形状（{@code PolicyExpressions} 是白名单的唯一权威）。
 * 三个硬约束：
 *
 * <ul>
 *   <li><b>遇白名单外的节点即报错</b>，报错含节点类名/函数名与原始 SQL 片段——
 *       不静默丢弃、不降级（§13.4）；</li>
 *   <li><b>按语义匹配而不是类名白名单</b>：{@code parseExpression} 不做分析，
 *       函数调用是 {@link UnresolvedFunction}，按名字分发正好绕开 3.5 与 4.1 的
 *       函数类名差异（§13.4 的版本注意事项）；</li>
 *   <li><b>产出 Java 集合</b>：结果要经 Jackson 序列化进 PUT 请求体，客户端的
 *       {@code ObjectMapper} 没有 Scala 模块，Scala Map 会序列化失败。</li>
 * </ul>
 *
 * <p>标准脱敏集（{@code show_first_n(3)} 这类）在<b>文本层</b>识别而不是经
 * {@code parseExpression}：这些函数不是 Spark 内置函数，解析器对未定义函数会直接
 * 抛 ParseException。文本形状（单个调用 + 参数全字面量）在 {@link #convertMask} 里
 * 手工拆分，参数仍逐个 {@code parseExpression} 校验是真字面量。
 */
private[spark] object PolicyAstConverter {

  /** 与服务端 {@code PolicyExpressions.STANDARD_MASK_FNS} 一致；漂移由服务端校验兜住。 */
  val STANDARD_MASK_FNS: Set[String] = Set(
    "mask_alphanum", "show_first_n", "show_last_n", "replace_with_null",
    "mask_to_fixed_value", "truncate_to_year", "truncate_to_month", "hash")

  private val ZERO_ARG_MASKS: Set[String] =
    Set("mask_alphanum", "replace_with_null", "truncate_to_year", "truncate_to_month")

  private val EXPRESSION_FNS: Set[String] = Set(
    "substr", "concat", "upper", "lower", "trim", "regexp_replace", "date_trunc",
    "year", "month", "to_date", "date_format", "md5", "sha2", "coalesce")

  /** 行过滤表达式 → AST。服务端会在落库时自行包 {@code coalesce(expr, false)}。 */
  def convertRowFilter(raw: String, parse: String => Expression): JavaMap[String, Object] =
    convert(parse(raw.trim), raw, inRowFilter = true)

  /**
   * mask 表达式 → AST：标准集形状返回 {@code {"fn":..., "args":[...]}}，
   * 其余一切返回 {@code {"expr": <AST>}}（逃生口）。
   */
  def convertMask(raw: String, parse: String => Expression): JavaMap[String, Object] = {
    val text = raw.trim
    val lower = text.toLowerCase(Locale.ROOT)
    if (ZERO_ARG_MASKS.contains(lower)) {
      return javaMap("fn" -> lower)
    }
    // 「单个调用」的形状：一个简单词 + 括号包到末尾。名字按标准集判（§13.5），
    // 参数逐个过 parseExpression 验明是真字面量
    val paren = text.indexOf('(')
    if (paren > 0 && text.lastIndexOf(')') == text.length - 1) {
      val namePart = text.substring(0, paren).trim
      val name = namePart.toLowerCase(Locale.ROOT)
      if (STANDARD_MASK_FNS.contains(name) && namePart.matches("[A-Za-z_][A-Za-z0-9_]*")) {
        val args = new ArrayList[Object]()
        splitTopLevel(text.substring(paren + 1, text.length - 1)).foreach { arg =>
          val trimmed = arg.trim
          if (trimmed.nonEmpty) {
            parse(trimmed) match {
              case lit: Literal => args.add(literalValue(lit))
              case other =>
                throw new ManagementSqlException(
                  s"standard mask '$name' takes literals only, got '$trimmed'" +
                    s" (${other.getClass.getSimpleName}) — arbitrary expressions use the" +
                    " escape-hatch shape (design §13.5)")
            }
          }
        }
        return javaMap("fn" -> name, "args" -> args)
      }
      // 名字是标准集但整体形状不是「单个调用」：交给原生解析器报它自己的错
    }
    javaMap("expr" -> convert(parse(text), text, inRowFilter = false))
  }

  // ------------------------------------------------------------------ 树转换

  def convert(node: Expression, sql: String, inRowFilter: Boolean): JavaMap[String, Object] =
    node match {
      case lit: Literal => literalNode(lit)
      case attr: UnresolvedAttribute => colNode(attr.nameParts.mkString("."))
      case ref: AttributeReference => colNode(ref.name)
      case And(left, right) => binary("and", left, right, sql, inRowFilter)
      case Or(left, right) => binary("or", left, right, sql, inRowFilter)
      case Not(child) => unaryOp("not", child, sql, inRowFilter)
      case EqualTo(left, right) => binary("eq", left, right, sql, inRowFilter)
      case LessThan(left, right) => binary("lt", left, right, sql, inRowFilter)
      case LessThanOrEqual(left, right) => binary("le", left, right, sql, inRowFilter)
      case GreaterThan(left, right) => binary("gt", left, right, sql, inRowFilter)
      case GreaterThanOrEqual(left, right) => binary("gte", left, right, sql, inRowFilter)
      case IsNull(child) => unaryOp("is_null", child, sql, inRowFilter)
      case IsNotNull(child) => unaryOp("is_not_null", child, sql, inRowFilter)
      case like: Like =>
        if (like.escapeChar != '\\') {
          throw unsupported(sql, node,
            "LIKE with a custom escape character — the AST's LIKE escapes with backslash only")
        }
        javaMap("op" -> "like",
          "left" -> convert(like.left, sql, inRowFilter),
          "right" -> convert(like.right, sql, inRowFilter))
      case in: In =>
        val values = new ArrayList[Object]()
        in.list.foreach(value => values.add(convert(value, sql, inRowFilter)))
        javaMap("op" -> "in",
          "operand" -> convert(in.value, sql, inRowFilter), "values" -> values)
      case when: CaseWhen =>
        val branches = new ArrayList[Object]()
        when.branches.foreach { case (condition, value) =>
          val branch = new LinkedHashMap[String, Object]()
          branch.put("when", convert(condition, sql, inRowFilter))
          branch.put("then", convert(value, sql, inRowFilter))
          branches.add(branch)
        }
        val out = javaMap("fn" -> "case_when", "branches" -> branches)
        when.elseValue.foreach { otherwise =>
          out.put("else", convert(otherwise, sql, inRowFilter))
        }
        out
      case coalesce: Coalesce => fnNode("coalesce", coalesce.children, sql, inRowFilter)
      case cast: Cast =>
        javaMap("fn" -> "cast",
          "operand" -> convert(cast.child, sql, inRowFilter),
          "to" -> castType(cast.dataType, sql))
      case _: CurrentUser => principalNode()
      case fn: UnresolvedFunction =>
        if (fn.isDistinct) {
          throw unsupported(sql, fn,
            fn.nameParts.mkString(".") + "(DISTINCT ...) — the AST has no DISTINCT semantics")
        }
        convertCall(fn.nameParts.mkString("."), fn.children, sql, inRowFilter)
      case other => throw unsupported(sql, other, null)
    }

  /**
   * 未分析函数按<b>名字</b>分发（语义匹配，避开版本类名差异）。
   * {@code current_user} 在转换期折成 {@code $principal} 占位符——AST 里不存
   * 函数调用，也不存任何人的名字（§13.4）。
   */
  private def convertCall(nameRaw: String, args: Seq[Expression],
                          sql: String, inRowFilter: Boolean): JavaMap[String, Object] = {
    val name = nameRaw.toLowerCase(Locale.ROOT)
    name match {
      case "current_user" => principalNode()
      case "substr" | "substring" => fnNode("substr", args, sql, inRowFilter)
      case _ if !EXPRESSION_FNS.contains(name) =>
        throw unsupported(sql, null,
          s"$nameRaw() — only deterministic whitelisted builtins are allowed"
            + " (no aggregates, windows, UDFs or session-dependent functions)")
      case "concat" => fnNode("concat", args, sql, inRowFilter)
      case "upper" | "lower" | "trim" | "md5" | "year" | "month" =>
        fnNode(name, args, sql, inRowFilter)
      case "regexp_replace" => fnNode("regexp_replace", args, sql, inRowFilter)
      case "date_trunc" => fnNode("date_trunc", args, sql, inRowFilter)
      case "to_date" => fnNode("to_date", args, sql, inRowFilter)
      case "date_format" => fnNode("date_format", args, sql, inRowFilter)
      case "sha2" => fnNode("sha2", args, sql, inRowFilter)
      case "coalesce" => fnNode("coalesce", args, sql, inRowFilter)
    }
  }

  // ------------------------------------------------------------------ 节点工厂

  private def colNode(name: String): JavaMap[String, Object] = javaMap("col" -> name)

  private def principalNode(): JavaMap[String, Object] =
    javaMap("principal" -> java.lang.Boolean.TRUE)

  private def binary(op: String, left: Expression, right: Expression,
                     sql: String, inRowFilter: Boolean): JavaMap[String, Object] =
    javaMap("op" -> op,
      "left" -> convert(left, sql, inRowFilter),
      "right" -> convert(right, sql, inRowFilter))

  private def unaryOp(op: String, operand: Expression,
                      sql: String, inRowFilter: Boolean): JavaMap[String, Object] =
    javaMap("op" -> op, "operand" -> convert(operand, sql, inRowFilter))

  private def fnNode(fn: String, args: Seq[Expression],
                     sql: String, inRowFilter: Boolean): JavaMap[String, Object] = {
    val values = new ArrayList[Object]()
    args.foreach(arg => values.add(convert(arg, sql, inRowFilter)))
    javaMap("fn" -> fn, "args" -> values)
  }

  /**
   * 字面量 → JSON 标量。两处特殊处理：
   *
   * <ul>
   *   <li>{@code UTF8String} → String（Catalyst 内部串类型，Jackson 不认识）；</li>
   *   <li>DATE / TIMESTAMP → ISO 字符串。AST 里日期字面量一律存字符串，语义按
   *       目标列的类型解析（服务端求值器与引擎侧同一约定）；存成天数/微秒整数
   *       会被类型推断判成 INTEGER 而对不上 DATE 列。</li>
   * </ul>
   */
  private def literalNode(lit: Literal): JavaMap[String, Object] =
    javaMap("lit" -> literalValue(lit))

  private def literalValue(lit: Literal): Object = lit.dataType match {
    case NullType => null
    case StringType => lit.value match {
      case text: UTF8String => text.toString
      case other: Object => other
    }
    case DateType => LocalDate.ofEpochDay(number(lit.value).longValue()).toString
    case TimestampType =>
      // Catalyst 内部是微秒
      Instant.ofEpochMilli(number(lit.value).longValue() / 1000).toString
    case BooleanType => lit.value.asInstanceOf[java.lang.Boolean]
    case _: NumericType => lit.value match {
      case decimal: java.math.BigDecimal => decimal
      case num: Number => num
      case other =>
        throw new ManagementSqlException("unsupported numeric literal: " + other)
    }
    case other =>
      throw new ManagementSqlException("unsupported literal type: " + other.simpleString)
  }

  private def number(value: Any): Number = value match {
    case num: Number => num
    case other => throw new ManagementSqlException("unsupported numeric literal: " + other)
  }

  /** cast 目标 → 服务端 {@code literalType} 认识的类型名；窄化/ exotic 类型交给服务端判。 */
  private def castType(dataType: DataType, sql: String): String = dataType match {
    case StringType => "STRING"
    case ByteType => "TINYINT"
    case ShortType => "SMALLINT"
    case IntegerType => "INT"
    case LongType => "BIGINT"
    case FloatType => "FLOAT"
    case DoubleType => "DOUBLE"
    case BooleanType => "BOOLEAN"
    case DateType => "DATE"
    case TimestampType => "TIMESTAMP"
    case other =>
      throw new ManagementSqlException("unsupported cast target: " + other.simpleString
        + " (expression: " + sql.trim + ")")
  }

  // ------------------------------------------------------------------ 工具

  /**
   * 「单个函数调用」的顶层参数拆分：逗号在括号深度 0 且不在引号里才算分隔符。
   * 引号内连续两个同引号是转义（与 Spark 的字符串字面量一致）。
   */
  private def splitTopLevel(text: String): Seq[String] = {
    val out = scala.collection.mutable.ArrayBuffer[String]()
    val current = new StringBuilder
    var depth = 0
    var quote = '\u0000'
    text.foreach { char =>
      if (quote != '\u0000') {
        current += char
        if (char == quote) {
          quote = '\u0000'
        }
      } else char match {
        case '\'' | '"' =>
          quote = char
          current += char
        case '(' =>
          depth += 1
          current += char
        case ')' =>
          depth -= 1
          current += char
        case ',' if depth == 0 =>
          out += current.toString
          current.clear()
        case other => current += other
      }
    }
    out += current.toString
    out.toSeq
  }

  private def javaMap(pairs: (String, Object)*): JavaMap[String, Object] = {
    val out = new LinkedHashMap[String, Object]()
    pairs.foreach { case (key, value) => out.put(key, value) }
    out
  }

  /** 白名单外的节点：报错指认类名/函数名与原始 SQL 片段（§13.4 的「拒绝」行）。 */
  private def unsupported(sql: String, node: Expression, reason: String): ManagementSqlException = {
    val shape = if (node != null) node.getClass.getSimpleName else ""
    val fragment = if (sql == null) "" else " (expression: " + sql.trim + ")"
    val why = if (reason == null || reason.isEmpty) "" else " — " + reason
    new ManagementSqlException(
      s"Unsupported expression: $shape$why$fragment — convert the expression to a" +
        " whitelisted shape instead; nothing is silently dropped (design §13.4)")
  }
}
