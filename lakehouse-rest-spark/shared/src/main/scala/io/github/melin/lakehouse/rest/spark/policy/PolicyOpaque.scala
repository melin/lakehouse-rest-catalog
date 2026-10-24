package io.github.melin.lakehouse.rest.spark.policy

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Expression, Literal, UnaryExpression}
import org.apache.spark.sql.catalyst.expressions.codegen.{CodegenContext, ExprCode}
import org.apache.spark.sql.types.DataType

/**
 * 策略表达式的不透明包装（§7.6 方案 a）：`EXPLAIN` / `EXPLAIN EXTENDED` 的物理计划
 * 会把 `Filter` 的条件与 mask 表达式完整打印出来——行过滤的字面量可能就是黑名单成员、
 * 别人的租户名或内部部门编码。包装之后，打印全链路只见
 * `policy_expr(N literals masked)`，看不到任何一个字面量。
 *
 * <p><b>语义是恒等委托</b>：`eval` 直接返回 `child.eval`，`doGenCode` 直接返回
 * `child.doGenCode`——codegen 不退化（§7.6 选方案 a 的理由），数据类型与可空性
 * 沿用内层表达式。包装只改「打印面」，不改任何执行语义；对优化器它是一个
 * 普通的一元表达式（引用、类型、codegen 全部透传），不会触发任何绕行或特判。
 *
 * <p><b>三处打印入口全部覆盖</b>：`toString`（TreeNode 树打印走它）、`sql`
 * （`FilterExec.simpleString` 用 `condition` 的树打印）、`verboseString`
 * （EXPLAIN EXTENDED 的详细模式）。`argString` 是从 productIterator 推导的，
 * 只会被本类的 `toString` / `simpleString` 调用——这三处都已被遮住，内层表达式
 * 的文本不会从这里漏出去。
 *
 * <p><b>它只出现在物理计划的展开处</b>（[[PolicyEnforcementStrategy]]）：逻辑计划
 * 里的 PolicyRelation 本来就只打印一行类名（§7.6 表格第一行），不需要它。
 * 字面量计数按内层树上的 [[Literal]] 节点数——打印时才算，不进 case class
 * 字段（不参与相等性与 canonicalized）。
 */
case class PolicyOpaque(child: Expression) extends UnaryExpression {

  // 类型与可空性必须与 child 一致——恒等包装的硬前提（4.x 的 UnaryExpression 不再
  // 提供 dataType 默认实现，两个版本都得显式声明；nullable 由 UnaryExpression 给出）

  override def dataType: DataType = child.dataType

  override def eval(input: InternalRow): Any = child.eval(input)

  override protected def withNewChildInternal(newChild: Expression): Expression =
    copy(child = newChild)

  /**
   * 恒等委托：生成的代码就是 child 的生成代码——本表达式的值**就是** child 的值
   * （类型与可空性一致）。这是「codegen 不退化」的关键：若走 CodegenFallback，
   * 每行求值都退回解释执行，行过滤在大表上会肉眼可见地变慢。
   *
   * <p>经 `child.genCode` 委托而不是直接调 `child.doGenCode`：后者是 protected，
   * 只能在 catalyst.expressions 包内访问（Alias 那类内置包装靠同包才够得着）；
   * `genCode` 是公开入口，内部同样走 child 的 doGenCode。它比 doGenCode 多做的
   * 只是空值处理的包装——本类的 nullable 与 child 一致，语义恒等。
   */
  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode =
    child.genCode(ctx)

  private def maskedText: String = {
    val literals = child.collect { case l: Literal => l }.size
    s"policy_expr($literals literals masked)"
  }

  override def toString: String = maskedText

  override def sql: String = maskedText

  // verboseString 在 Expression 上是 final（内部走 toString）——不覆盖也能遮住
}
