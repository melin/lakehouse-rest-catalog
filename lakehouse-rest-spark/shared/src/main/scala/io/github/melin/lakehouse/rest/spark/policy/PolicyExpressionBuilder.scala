package io.github.melin.lakehouse.rest.spark.policy

import java.util.{Map => JMap}

import scala.collection.JavaConverters._

import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.types._

/**
 * 策略 AST（服务端下发协议的裸 JSON）→ Catalyst {@link Expression}。
 *
 * <p><b>词表与服务端 {@code PolicyExpressions} 逐字对应</b>（§5.3.2）：九种二元算子、
 * 四种一元/列表算子、十六个确定性函数、八个标准 mask。服务端在建策略时已经校验过
 * 同一棵树，这里的校验不是重复劳动而是第二道闸——两处校验之间可能隔着一次 schema
 * 变更，而「引擎侧缺校验」的最坏结果不是报错，是执行了一半的策略。任何不认识的
 * 节点都抛 {@link PolicyEnforcementException}（fail-closed），不静默跳过。
 *
 * <p><b>类型纪律（§5.3.3）：</b>mask 结果类型必须等于目标列类型（不许「可 cast」）；
 * 行过滤必须是布尔——服务端已把行过滤包进 {@code coalesce(expr, false)} 保证非 NULL，
 * 这里再守一次类型。比较两侧类型不一致时优先把**字面量**一侧 cast 到另一侧：
 * 注入点在分析之后，分析器的隐式转换不会再跑，只能在这里做最小的一份。
 *
 * <p>{@code $principal} 在这里**不应该再出现**：服务端下发前已折成字面量（§5.3.4），
 * 见到它说明协议被绕过了，fail-closed 报错而不是本地折叠——本地折叠等于把
 * 「我是谁」的解释权从服务端挪到引擎。
 */
private[spark] object PolicyExpressionBuilder {

  // ------------------------------------------------------------------ 入口

  /** 行过滤 AST → Catalyst 布尔表达式。 */
  def rowFilter(node: Any, attrs: Seq[AttributeReference]): Expression = {
    val expr = build(node, attrs, inRowFilter = true)
    expr.dataType match {
      case BooleanType => expr
      case other => throw new PolicyEnforcementException(
        s"row filter must be a boolean expression, but it evaluates to $other;"
          + " wrap comparisons or use is_null / is_not_null")
    }
  }

  /**
   * mask 节点（{@code {"fn": ..., "args": [...]}} 或 {@code {"expr": {...}}}）→ 表达式。
   *
   * @param target mask 目标列（结果类型必须与它一致）
   * @param attrs  表的完整输出——mask 参数允许引用其它列（§5.3.5 的单元格级语义）
   */
  def mask(maskNode: Any, target: AttributeReference,
           attrs: Seq[AttributeReference]): Expression = {
    val node = asMap(maskNode, "mask")
    if (node.containsKey("fn") && node.containsKey("expr")) {
      throw new PolicyEnforcementException(
        s"mask for column ${target.name} must use either 'fn' (standard set) or"
          + " \"expr\" (escape hatch), not both")
    }
    val expr =
      if (node.containsKey("fn")) standardMask(node, target)
      else if (node.containsKey("expr")) build(node.get("expr"), attrs, inRowFilter = false)
      else throw new PolicyEnforcementException(
        s"mask for column ${target.name} must contain either 'fn' or 'expr'")
    if (expr.dataType != target.dataType) {
      throw new PolicyEnforcementException(
        s"mask for column ${target.name} produces ${expr.dataType.simpleString} but the"
          + s" column is ${target.dataType.simpleString} — the result type must equal the"
          + " column type (design §5.3.3, no implicit casts)")
    }
    expr
  }

  // ------------------------------------------------------------------ 标准脱敏集（§5.3.2.1）

  /**
   * 逐字符替换的字符类（与 Hive {@code mask()} 一致）：大写→{@code X}、小写→{@code x}、
   * 数字→{@code n}，其余不变。用 {@code translate} 而不是正则逐段 replace：
   * {@code translate} 按字符映射，不在源串里的字符（含 Unicode）原样保留，不会漏。
   */
  private val AlphanumSource = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
  private val AlphanumTarget = "X" * 26 + "x" * 26 + "n" * 10

  private def alphanum(expr: Expression): Expression =
    StringTranslate(expr, Literal(AlphanumSource), Literal(AlphanumTarget))

  private def standardMask(node: JMap[String, Any], target: AttributeReference): Expression = {
    val fn = String.valueOf(node.get("fn"))
    val args = rawArgs(node)
    val col: Expression = target
    fn match {
      case "mask_alphanum" =>
        alphanum(col)

      case "show_first_n" =>
        val n = intArg(args, fn)
        // 保留前 n 个字符，其余按 alphanum 规则替换（Ranger 的 MASK_SHOW_FIRST_N 语义）。
        // substr 的起点越过串长返回空串，因此 concat 不会碰到 NULL
        Concat(Seq(Substring(col, Literal(1), Literal(n)),
          alphanum(Substring(col, Literal(n + 1), Literal(Int.MaxValue)))))

      case "show_last_n" =>
        val n = intArg(args, fn)
        // 保留后 n 个字符：对「前缀 = 前 len-n 个字符」做 alphanum，再接上原样的尾部。
        // 尾部起点用 -min(len, n)，保证负数起点不越过串头（len < n 时尾部就是全串）；
        // 前缀长度用 max(len-n, 0)，负长度在 substr 里是空串，两处都为了 len < n 的边角
        val len = Length(col)
        Concat(Seq(
          alphanum(Substring(col, Literal(1),
            Greatest(Seq(Subtract(len, Literal(n)), Literal(0))))),
          Substring(col, UnaryMinus(Least(Seq(len, Literal(n)))), Literal(Int.MaxValue))))

      case "replace_with_null" =>
        // 最常见的脱敏就是置空；结果类型必须等于列类型，所以 NULL 字面量按列类型造
        Literal.create(null, target.dataType)

      case "mask_to_fixed_value" =>
        if (args.size() != 1 || !args.get(0).isInstanceOf[String]) {
          throw new PolicyEnforcementException(
            "mask mask_to_fixed_value takes one non-empty string")
        }
        Literal(args.get(0))

      case "truncate_to_year" | "truncate_to_month" =>
        val unit = if (fn == "truncate_to_year") "year" else "month"
        // DATE 与 TIMESTAMP 都收敛到 TruncDate：它按输入类型返回同类结果，
        // 因此「结果类型 = 列类型」在外层校验自然成立
        TruncDate(col, Literal(unit))

      case "hash" =>
        if (args.size() != 1 || !args.get(0).isInstanceOf[String]) {
          throw new PolicyEnforcementException("mask hash takes one algorithm from"
            + " MD5 / SHA-256 / SHA-512 — the algorithm must be written down, not read"
            + " from cluster config (design §3.4)")
        }
        args.get(0).asInstanceOf[String].toUpperCase(java.util.Locale.ROOT) match {
          case "MD5" => Md5(col)
          case "SHA-256" => Sha2(col, Literal(256))
          case "SHA-512" => Sha2(col, Literal(512))
          case other => throw new PolicyEnforcementException(
            s"unsupported hash algorithm $other; allowed: MD5, SHA-256, SHA-512")
        }

      case other =>
        throw new PolicyEnforcementException(s"unknown standard mask: $other")
    }
  }

  private def rawArgs(node: JMap[String, Any]): java.util.List[Any] = {
    val args = node.get("args")
    if (args == null) {
      new java.util.ArrayList[Any]()
    } else {
      args.asInstanceOf[java.util.List[Any]]
    }
  }

  private def intArg(args: java.util.List[Any], fn: String): Int = {
    if (args.size() != 1 || !args.get(0).isInstanceOf[Number]) {
      throw new PolicyEnforcementException(s"mask $fn takes one non-negative integer")
    }
    args.get(0).asInstanceOf[Number].intValue()
  }

  // ------------------------------------------------------------------ AST 节点

  private def build(node: Any, attrs: Seq[AttributeReference], inRowFilter: Boolean): Expression =
    node match {
      case map: JMap[_, _] => buildMap(map.asInstanceOf[JMap[String, Any]], attrs, inRowFilter)
      case s: String => Literal.create(s, StringType)
      case b: java.lang.Boolean => Literal(b)
      case n: Number => numberLiteral(n)
      case null => Literal.create(null, NullType)
      case other => throw new PolicyEnforcementException(
        s"unsupported expression node: ${other.getClass.getSimpleName} ($other)"
          + " — use {col} / {lit} / {op} / {fn} shapes")
    }

  private def buildMap(map: JMap[String, Any], attrs: Seq[AttributeReference],
                       inRowFilter: Boolean): Expression = {
    if (map.containsKey("col")) {
      attribute(String.valueOf(map.get("col")), attrs)
    } else if (map.containsKey("lit")) {
      map.get("lit") match {
        case null => Literal.create(null, NullType)
        case b: java.lang.Boolean => Literal(b)
        case n: Number => numberLiteral(n)
        case s: String => Literal.create(s, StringType)
        case other => throw new PolicyEnforcementException(
          s"literal must be a scalar (string / number / boolean / null), got $other")
      }
    } else if (map.containsKey("principal")) {
      throw new PolicyEnforcementException(
        "the $principal placeholder reached the engine without being folded into a literal;"
          + " the policy service must substitute it before delivery (design §5.3.4)")
    } else if (map.containsKey("op")) {
      buildOp(map, attrs, inRowFilter)
    } else if (map.containsKey("fn")) {
      buildFn(map, attrs, inRowFilter)
    } else {
      throw new PolicyEnforcementException(
        s"unsupported expression shape: expected one of 'col' / 'lit' / 'op' / 'fn', got "
          + map.keySet().toArray().mkString(", "))
    }
  }

  // ------------------------------------------------------------------ 算子

  private def buildOp(map: JMap[String, Any], attrs: Seq[AttributeReference],
                      inRowFilter: Boolean): Expression = {
    val op = String.valueOf(map.get("op"))
    op match {
      case "eq" | "neq" | "lt" | "le" | "gt" | "gte" | "and" | "or" | "like" =>
        val left = build(map.get("left"), attrs, inRowFilter)
        val right = build(map.get("right"), attrs, inRowFilter)
        val (l, r) = coercePair(left, right, op)
        op match {
          case "eq" => EqualTo(l, r)
          case "neq" => Not(EqualTo(l, r))
          case "lt" => LessThan(l, r)
          case "le" => LessThanOrEqual(l, r)
          case "gt" => GreaterThan(l, r)
          case "gte" => GreaterThanOrEqual(l, r)
          case "and" => And(l, r)
          case "or" => Or(l, r)
          // like 的模式一律字面量（服务端已锁，§5.3.2）；这里再守一次：
          // 模式里若出现列引用，「像什么」就随数据漂了
          case "like" => r match {
            case lit: Literal if lit.dataType == StringType => Like(l, r, '\\')
            case other => throw new PolicyEnforcementException(
              s"like pattern must be a string literal, got $other")
          }
        }
      case "not" =>
        Not(build(map.get("operand"), attrs, inRowFilter))
      case "is_null" =>
        IsNull(build(map.get("operand"), attrs, inRowFilter))
      case "is_not_null" =>
        IsNotNull(build(map.get("operand"), attrs, inRowFilter))
      case "in" =>
        val operand = build(map.get("operand"), attrs, inRowFilter)
        val values = map.get("values") match {
          case list: java.util.List[_] => list.asScala.toSeq
          case other => throw new PolicyEnforcementException(
            s"in requires a non-empty literal list, got $other")
        }
        if (values.isEmpty) {
          throw new PolicyEnforcementException("in requires a non-empty literal list")
        }
        In(operand, values.map(v => coerceTo(build(v, attrs, inRowFilter), operand.dataType, "in")))
      case other =>
        throw new PolicyEnforcementException(
          s"unsupported op: $other — allowed: eq, neq, lt, le, gt, gte, and, or, like,"
            + " not, is_null, is_not_null, in")
    }
  }

  // ------------------------------------------------------------------ 函数

  private def buildFn(map: JMap[String, Any], attrs: Seq[AttributeReference],
                      inRowFilter: Boolean): Expression = {
    val fn = String.valueOf(map.get("fn"))
    // 函数参数在协议里统一叫 args（cast / case_when 除外，它们有具名字段）
    val args: Seq[Expression] = map.get("args") match {
      case list: java.util.List[_] => list.asScala.toSeq.map(build(_, attrs, inRowFilter))
      case null if fn == "cast" || fn == "case_when" => Seq.empty
      case null => throw new PolicyEnforcementException(s"fn $fn requires arguments")
      case other => throw new PolicyEnforcementException(s"fn $fn arguments must be a list")
    }
    fn match {
      case "substr" =>
        if (args.length < 2 || args.length > 3) {
          throw new PolicyEnforcementException("substr requires two or three arguments")
        }
        Substring(args(0), coerceToInt(args(1), "substr start"),
          if (args.length > 2) coerceToInt(args(2), "substr length") else Literal(Int.MaxValue))
      case "concat" =>
        // Concat 要求全 String：非字符串参数显式 cast（分析器不会再做隐式转换）
        Concat(args.map(castToString))
      case "upper" => Upper(single(args, fn))
      case "lower" => Lower(single(args, fn))
      case "trim" => StringTrim(single(args, fn))
      case "regexp_replace" =>
        if (args.length != 3) {
          throw new PolicyEnforcementException("regexp_replace requires exactly three arguments")
        }
        // 模式与替换串必须是字面量（服务端已锁，§7.5 的漂移点；这里 fail-closed 兜底）
        val pattern = requireStringLiteral(args(1), "regexp_replace pattern")
        val replacement = requireStringLiteral(args(2), "regexp_replace replacement")
        RegExpReplace(castToString(args(0)), pattern, replacement, Literal(1))
      case "date_trunc" =>
        // Spark 的签名是 date_trunc(format, ts)；协议按同一顺序存（§5.3.2）
        if (args.length != 2) {
          throw new PolicyEnforcementException("date_trunc requires exactly two arguments")
        }
        TruncDate(args(1), requireStringLiteral(args(0), "date_trunc unit"))
      case "year" => Year(single(args, fn))
      case "month" => Month(single(args, fn))
      case "to_date" =>
        single(args, fn).dataType match {
          case StringType => ParseToDate(single(args, fn), None)
          case DateType => single(args, fn)
          case TimestampType => Cast(single(args, fn), DateType)
          case other => throw new PolicyEnforcementException(
            s"to_date expects a string / date / timestamp input, got $other")
        }
      case "date_format" =>
        if (args.length != 2) {
          throw new PolicyEnforcementException("date_format requires exactly two arguments")
        }
        DateFormatClass(args(0), requireStringLiteral(args(1), "date_format pattern"))
      case "md5" => Md5(castToString(single(args, fn)))
      case "sha2" =>
        if (args.length != 2) {
          throw new PolicyEnforcementException("sha2 requires exactly two arguments")
        }
        Sha2(castToString(args(0)), coerceToInt(args(1), "sha2 digest length"))
      case "coalesce" =>
        if (args.length < 2) {
          throw new PolicyEnforcementException("coalesce requires at least two arguments")
        }
        Coalesce(args)
      case "case_when" =>
        buildCaseWhen(map, attrs, inRowFilter)
      case "cast" =>
        val operand = build(map.get("operand"), attrs, inRowFilter)
        Cast(operand, sparkType(String.valueOf(map.get("to"))))
      case other =>
        throw new PolicyEnforcementException(
          s"unsupported function: $other — only deterministic builtins are whitelisted"
            + " (no aggregates, windows, subqueries, UDFs or session-dependent functions)")
    }
  }

  private def buildCaseWhen(map: JMap[String, Any], attrs: Seq[AttributeReference],
                            inRowFilter: Boolean): Expression = {
    val branches = map.get("branches") match {
      case list: java.util.List[_] => list.asScala.toSeq
      case other => throw new PolicyEnforcementException(
        s"case_when requires at least one when/then branch, got $other")
    }
    if (branches.isEmpty) {
      throw new PolicyEnforcementException("case_when requires at least one when/then branch")
    }
    val pairs = branches.map { branchObj =>
      val branch = asMap(branchObj, "case_when branch")
      (build(branch.get("when"), attrs, inRowFilter),
        build(branch.get("then"), attrs, inRowFilter))
    }
    val elseValue = if (map.containsKey("else")) {
      Some(build(map.get("else"), attrs, inRowFilter))
    } else {
      None
    }
    CaseWhen(pairs, elseValue)
  }

  // ------------------------------------------------------------------ 类型收窄与校验

  /**
   * 比较两侧的类型对齐：类型一致直接用；不一致时优先把**字面量**一侧 cast 过去
   * （策略里最常见的是「列 vs 字面量」），两侧都是列引用且类型不同就 fail-closed——
   * 那种比较在分析器里也过不了隐式转换，与其在执行期炸，不如现在说清楚。
   */
  private def coercePair(left: Expression, right: Expression, op: String): (Expression, Expression) = {
    if (left.dataType == right.dataType) {
      (left, right)
    } else if (right.foldable && Cast.canCast(right.dataType, left.dataType)) {
      (left, Cast(right, left.dataType))
    } else if (left.foldable && Cast.canCast(left.dataType, right.dataType)) {
      (Cast(left, right.dataType), right)
    } else {
      throw new PolicyEnforcementException(
        s"op $op compares ${left.dataType.simpleString} with ${right.dataType.simpleString};"
          + " add an explicit cast in the policy expression so both sides share one type")
    }
  }

  private def coerceTo(expr: Expression, dataType: DataType, what: String): Expression =
    if (expr.dataType == dataType) {
      expr
    } else if (Cast.canCast(expr.dataType, dataType)) {
      Cast(expr, dataType)
    } else {
      throw new PolicyEnforcementException(
        s"$what operand produces ${expr.dataType.simpleString}, which cannot be compared with"
          + s" ${dataType.simpleString}; add an explicit cast in the policy expression")
    }

  private def coerceToInt(expr: Expression, what: String): Expression = expr.dataType match {
    case IntegerType => expr
    case LongType => Cast(expr, IntegerType)
    case _ => throw new PolicyEnforcementException(s"$what must be an integer literal")
  }

  private def castToString(expr: Expression): Expression = expr.dataType match {
    case StringType => expr
    case _ => Cast(expr, StringType)
  }

  private def requireStringLiteral(expr: Expression, what: String): Literal = expr match {
    case lit: Literal if lit.dataType == StringType => lit
    case other => throw new PolicyEnforcementException(s"$what must be a string literal, got $other")
  }

  private def single(args: Seq[Expression], fn: String): Expression = {
    if (args.length != 1) {
      throw new PolicyEnforcementException(s"$fn requires exactly one argument")
    }
    args.head
  }

  /**
   * 协议里的 cast 目标是**精确类型名**（服务端 {@code literalType} 归一成
   * STRING / TINYINT / SMALLINT / INT / BIGINT / FLOAT / DOUBLE / BOOLEAN / DATE / TIMESTAMP
   * 等粗词），这里按同一张表映射到 Spark 类型；映射不到的类型 fail-closed——
   * 服务端放行了一个引擎不认识的类型名，说明两侧词表漂移了，必须修而不是绕。
   */
  private def sparkType(name: String): DataType = name.toUpperCase(java.util.Locale.ROOT) match {
    case "STRING" | "VARCHAR" | "CHAR" | "TEXT" => StringType
    case "TINYINT" | "BYTE" => ByteType
    case "SMALLINT" | "SHORT" => ShortType
    case "INT" | "INTEGER" => IntegerType
    case "BIGINT" | "LONG" => LongType
    case "FLOAT" | "REAL" => FloatType
    case "DOUBLE" => DoubleType
    case "BOOLEAN" | "BOOL" => BooleanType
    case "DATE" => DateType
    case "TIMESTAMP" => TimestampType
    case other => throw new PolicyEnforcementException(
      s"unsupported cast target type: $other")
  }

  private def attribute(name: String, attrs: Seq[AttributeReference]): AttributeReference = {
    // 列名按服务端校验过的原样找；找不到再按大小写不敏感兜一次（会话可能开了
    // 大小写折叠），两次都落空就是「表达式里的列在 schema 里找不到」——fail-closed。
    // 报错带上当时实际可见的列名集合：mask 参数允许引用未投影列，正常展开时
    // child 是全量 output；报这个错几乎总是「展开时 child 被裁过列」，得能看到证据
    attrs.find(_.name == name)
      .orElse(attrs.find(a => a.name.equalsIgnoreCase(name)))
      .getOrElse(throw new PolicyEnforcementException(
        s"column does not exist in the table schema: $name (visible columns:" +
          s" ${attrs.map(_.name).mkString(", ")}) — the policy was written against"
          + " a different schema; fix or drop the policy before querying this table"))
  }

  private def numberLiteral(n: Number): Literal = n match {
    case i: java.lang.Integer => Literal(i)
    case l: java.lang.Long => Literal(l)
    case d: java.lang.Double => Literal(d)
    case f: java.lang.Float => Literal(f)
    case b: java.math.BigDecimal => Literal(b)
    case b: java.math.BigInteger => Literal(new java.math.BigDecimal(b))
    case other => Literal.create(other.toString, StringType)
  }

  private def asMap(node: Any, what: String): JMap[String, Any] = node match {
    case map: JMap[_, _] => map.asInstanceOf[JMap[String, Any]]
    case other => throw new PolicyEnforcementException(s"$what must be an object, got $other")
  }
}
