package io.github.melin.lakehouse.rest.spark.policy

import java.util.{Map => JMap}

import scala.collection.JavaConverters._

import org.apache.spark.sql.catalyst.analysis.UnresolvedAttribute
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.internal.SQLConf
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
 * <b>每个内建函数的输入类型也要自己满足</b>（如 Md5 / Sha2 要 BinaryType，见
 * {@link #toUtf8Bytes}）——构造出的表达式若未解析，必须 fail-closed 拒绝，
 * 不能把半成品放进物理计划。
 *
 * <p>{@code $principal} 在这里**不应该再出现**：服务端下发前已折成字面量（§5.3.4），
 * 见到它说明协议被绕过了，fail-closed 报错而不是本地折叠——本地折叠等于把
 * 「我是谁」的解释权从服务端挪到引擎。
 */
private[spark] object PolicyExpressionBuilder {

  // ------------------------------------------------------------------ 入口

  /** 行过滤 AST → Catalyst 布尔表达式。 */
  def rowFilter(node: Any, attrs: Seq[AttributeReference]): Expression = {
    val expr = resolveTimeZones(build(node, attrs, inRowFilter = true))
    // 本类在分析之后构造表达式，分析器的隐式 cast 不会再跑——类型不吻合的表达式
    // 在这里就是「未解析」，放出去会在物理规则里以 UnresolvedAttribute 的面目炸出
    // 一句谁也看不懂的 `Invalid call to exprId`（实测：Sha2 落在 string 列上）。
    // fail-closed：现在就说清楚是策略表达式的问题。
    if (!expr.resolved) {
      throw new PolicyEnforcementException(
        s"row filter did not resolve against the table schema (an input type does not match"
          + s" the operator's requirement, got ${describeTypes(expr)}); fix or drop the policy"
          + " before querying this table")
    }
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
    val built =
      if (node.containsKey("fn")) standardMask(node, target)
      else if (node.containsKey("expr")) build(node.get("expr"), attrs, inRowFilter = false)
      else throw new PolicyEnforcementException(
        s"mask for column ${target.name} must contain either 'fn' or 'expr'")
    val expr = resolveTimeZones(built)
    if (expr.dataType != target.dataType) {
      throw new PolicyEnforcementException(
        s"mask for column ${target.name} produces ${expr.dataType.simpleString} but the"
          + s" column is ${target.dataType.simpleString} — the result type must equal the"
          + " column type (design §5.3.3, no implicit casts)")
    }
    // 同 rowFilter：分析期已过，隐式 cast 不会再来。未解析的 mask 一旦包进
    // PolicyProjectExec，其 output 会把这一列吐成 UnresolvedAttribute，
    // RemoveRedundantProjects 一碰就炸（实测教训，见 rowFilter 内的注释）
    if (!expr.resolved) {
      throw new PolicyEnforcementException(
        s"mask for column ${target.name} did not resolve against the table schema (an input"
          + s" type does not match the function's requirement, got ${describeTypes(expr)});"
          + " fix or drop the policy before querying this table")
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
        requireNoArgs(args, fn)
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
        requireNoArgs(args, fn)
        Literal.create(null, target.dataType)

      case "mask_to_fixed_value" =>
        // 与服务端 isBlank() 同口径：空白串（含空串）等于「固定值没写」，两侧都拒
        if (args.size() != 1 || !args.get(0).isInstanceOf[String]
            || args.get(0).asInstanceOf[String].isBlank) {
          throw new PolicyEnforcementException(
            "mask mask_to_fixed_value takes one non-empty string")
        }
        Literal(args.get(0))

      case "truncate_to_year" | "truncate_to_month" =>
        requireNoArgs(args, fn)
        val unit = if (fn == "truncate_to_year") "year" else "month"
        // DATE 与 TIMESTAMP 都放行（服务端 requireTemporal 同口径，inferType 按列类回）：
        // TruncDate 只收 DATE 且恒返回 DATE；TIMESTAMP 列必须走 TruncTimestamp
        // （inputTypes 收 TIMESTAMP，恒返回 TIMESTAMP）——两者的结果类型都与目标列
        // 一致，「结果类型 = 列类型」的外层校验自然成立。日期以外落到 TruncDate 会
        // 未解析，由 mask() 出口的 resolved 闸门 fail-closed
        col.dataType match {
          case TimestampType => TruncTimestamp(Literal(unit), col)
          case _ => TruncDate(col, Literal(unit))
        }

      case "hash" =>
        if (args.size() != 1 || !args.get(0).isInstanceOf[String]) {
          throw new PolicyEnforcementException("mask hash takes one algorithm from"
            + " MD5 / SHA-256 / SHA-512 — the algorithm must be written down, not read"
            + " from cluster config (design §3.4)")
        }
        args.get(0).asInstanceOf[String].toUpperCase(java.util.Locale.ROOT) match {
          // Md5 / Sha2 的输入类型是 BinaryType：hash 的语义是「对 UTF-8 字节做摘要、
          // 输出小写十六进制」（与服务端 PolicyExpressionEvaluator#digest 对齐）。
          // 这里在分析之后构造表达式，分析器的隐式 string→binary cast 不会再跑，
          // 必须自己补——否则表达式未解析，RemoveRedundantProjects 直接炸（实测）
          case "MD5" => Md5(toUtf8Bytes(col))
          case "SHA-256" => Sha2(toUtf8Bytes(col), Literal(256))
          case "SHA-512" => Sha2(toUtf8Bytes(col), Literal(512))
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
    // 负数在服务端建策略时已拒（PolicyExpressions：number.intValue() < 0）；
    // 这里是第二道闸——负的 n 会让 substr 的语义漂移（起点从尾部倒数），必须拒绝
    val v = args.get(0).asInstanceOf[Number].intValue()
    if (v < 0) {
      throw new PolicyEnforcementException(s"mask $fn takes one non-negative integer, got $v")
    }
    v
  }

  /** mask_alphanum / replace_with_null / truncate_to_* 不收参数（服务端同口径）。 */
  private def requireNoArgs(args: java.util.List[Any], fn: String): Unit =
    if (!args.isEmpty) {
      throw new PolicyEnforcementException(s"mask $fn takes no arguments")
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
    } else if (map.containsKey("principal_roles")) {
      throw new PolicyEnforcementException(
        "the $principal_roles placeholder reached the engine without being folded into a role"
          + " list; the policy service must substitute it before delivery (design §5.3.4.1)")
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
        // 服务端语义是「先 displayString 再截取」（substr/upper/lower/trim 同一惯例，
        // inferType 也一律回 STRING）：数字等非字符串列同样可用，这里显式补 cast
        Substring(castToString(args(0)), coerceToInt(args(1), "substr start"),
          if (args.length > 2) coerceToInt(args(2), "substr length") else Literal(Int.MaxValue))
      case "concat" =>
        // Concat 要求全 String：非字符串参数显式 cast（分析器不会再做隐式转换）
        Concat(args.map(castToString))
      case "upper" => Upper(castToString(single(args, fn)))
      case "lower" => Lower(castToString(single(args, fn)))
      case "trim" => StringTrim(castToString(single(args, fn)))
      case "regexp_replace" =>
        if (args.length != 3) {
          throw new PolicyEnforcementException("regexp_replace requires exactly three arguments")
        }
        // 模式与替换串必须是字面量（服务端已锁，§7.5 的漂移点；这里 fail-closed 兜底）
        val pattern = requireStringLiteral(args(1), "regexp_replace pattern")
        val replacement = requireStringLiteral(args(2), "regexp_replace replacement")
        RegExpReplace(castToString(args(0)), pattern, replacement, Literal(1))
      case "date_trunc" =>
        // Spark 的签名是 date_trunc(format, ts)；协议按同一顺序存（§5.3.2）。
        // 与 truncate_to_* 同口径：TIMESTAMP 参数走 TruncTimestamp，DATE 走 TruncDate——
        // 服务端把 date_trunc 的结果类型推断为参数列自己的类型（inferType(args[1])），
        // 引擎的结果类型必须跟上，否则比较两侧对不齐
        if (args.length != 2) {
          throw new PolicyEnforcementException("date_trunc requires exactly two arguments")
        }
        val unit = requireStringLiteral(args(0), "date_trunc unit")
        args(1).dataType match {
          case TimestampType => TruncTimestamp(unit, args(1))
          case DateType => TruncDate(args(1), unit)
          case other => throw new PolicyEnforcementException(
            s"date_trunc expects a date / timestamp input, got $other")
        }
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
      case "md5" => Md5(toUtf8Bytes(single(args, fn)))
      case "sha2" =>
        if (args.length != 2) {
          throw new PolicyEnforcementException("sha2 requires exactly two arguments")
        }
        Sha2(toUtf8Bytes(args(0)), coerceToInt(args(1), "sha2 digest length"))
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

  /**
   * 补会话时区。{@code TimeZoneAwareExpression}（TruncTimestamp / ParseToDate /
   * 涉及时区的 Cast 等）的 {@code resolved} 要求 {@code timeZoneId.isDefined}，
   * 正常链路由分析期的 {@code ResolveTimeZone} 规则填入——而本类在分析之后构造，
   * 没人替我们填，时区敏感的表达式会永远「未解析」。这里在构造出口统一补上
   * 会话时区（与 Spark 对用户查询的行为一致：date_trunc 按会话时区截断）。
   */
  private def resolveTimeZones(expr: Expression): Expression =
    expr.transformUp {
      case tz: TimeZoneAwareExpression if tz.timeZoneId.isEmpty =>
        tz.withTimeZone(SQLConf.get.sessionLocalTimeZone)
    }

  /**
   * 摘要函数的输入收敛：Md5 / Sha2 要求 BinaryType，语义是「对 UTF-8 字节做摘要」
   * （服务端 {@code digest} 是 {@code displayString(value).getBytes(UTF_8)}）。
   * 字符串列直接 cast（Cast(string→binary) 即取 UTF-8 字节）；其它类型先转字符串
   * 再取字节，与「先显示串再编码」的顺序一致。分析期已过，这一步没人替我们做。
   */
  private def toUtf8Bytes(expr: Expression): Expression = expr.dataType match {
    case BinaryType => expr
    case StringType => Cast(expr, BinaryType)
    case _ => Cast(Cast(expr, StringType), BinaryType)
  }

  /** 报错辅助：把表达式里未解析子节点的形态摊开，策略写错类型时能一眼定位。 */
  private def describeTypes(expr: Expression): String = {
    val bad = expr.collect { case u: UnresolvedAttribute => u.name.mkString(".") }.distinct
    val types = expr.children.map(c =>
      s"${c.getClass.getSimpleName}(${if (c.resolved) c.dataType.simpleString else "unresolved"})")
    (if (bad.nonEmpty) s"unresolved columns: ${bad.mkString(", ")}; " else "") +
      types.mkString(", ")
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
