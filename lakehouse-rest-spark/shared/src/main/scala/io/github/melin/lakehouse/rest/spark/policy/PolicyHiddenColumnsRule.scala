package io.github.melin.lakehouse.rest.spark.policy

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{Attribute, AttributeReference, ExprId, Expression, NamedExpression}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.command.ExplainCommand

/**
 * 整列不可见（`hiddenColumns`，P3，设计 §5.8）的强制规则。
 *
 * <p><b>为什么是独立的一条规则而不是塞进 [[PolicyInjectionRule]]：</b>注入规则是
 * 自底向上的（把 relation 包起来），而「整列不可见」要回答的问题全部关于
 * <b>上层</b>怎么引用这张表——星号展开成什么、显式引用了哪几列——这些只有
 * 自顶向下看才看得到。注册顺序在注入规则之后（同一个 PostHocResolution 批次，
 * 扩展规则的注册顺序即执行顺序），跑的时候 PolicyRelation 已经在了。
 *
 * <p><b>`readableColumns()` 的规范语义是两件事</b>：`SELECT *` 只返回可读列、
 * 显式引用不可读列直接失败（SPIP 的定义）。注入点在 PostHocResolution 时
 * 星号已经展开完（这正是 §3.2 选它的理由），展开后的普通属性引用与显式引用
 * 无法区分——所以本规则用「形状」区分：
 *
 * <ul>
 *   <li><b>全宽普通投影</b>（projectList 逐位等于 relation 的输出、全是裸属性、
 *       无别名）按星号处理：裁掉不可见列（`SELECT *` 返回可读列子集）；</li>
 *   <li><b>其余任何引用</b>（显式列、聚合键、`t.*` 混合写法、别名形态）一律失败。
 *       实现上是「兜底裁剪」把不可见列从输出里拿掉，上层残留的引用变成悬空
 *       属性，CheckAnalysis 直接抛 AnalysisException——分析期失败，指名列不存在；</li>
 *   <li>「全宽普通投影」的歧义面：显式列出全部列（原顺序、无别名）与星号无法
 *       区分，按星号裁掉——被裁掉不泄露任何值，比 fail-closed 的误伤小得多；
 *       这条取舍与 §5.8 的「静默忽略是最坏结果」不矛盾：保护真实存在，可见性
 *       少了一列，报文里列名清单本身就是提示。</li>
 * </ul>
 *
 * <p><b>两遍扫描：</b>
 *
 * <ol>
 *   <li><b>星号裁剪</b>：找到引用了不可见列的全宽普通投影，裁掉那些列；</li>
 *   <li><b>兜底裁剪</b>：给每个带不可见列的 PolicyRelation 之上自动包一层
 *       只含可读列的 Project——`spark.table("t")` 这类没有投影的 DataFrame 读法、
 *       join 的某一侧，不包这层就是把明文列放进输出。兜底之后仍引用不可见列的
 *       计划必然悬空失败（见上）。</li>
 * </ol>
 *
 * <p><b>写路径整棵跳过</b>：§5.7 的写命令语义不套策略，整列不可见是读路径的能力。
 * 形状比想象的多（4.1 实测）：INSERT 的顶层是 `AppendData`（V1 目标也是）、
 * UPDATE / DELETE 分析期重写成 `ReplaceData`、V1 命令形态还有
 * `InsertIntoHadoopFsRelationCommand` / `CreateDataSourceTableCommand` 这类执行命令——
 * 所以判据用「V2 写节点全集 + Command 基类（EXPLAIN 除外）」，逐类枚举迟早漏。
 * CTAS 因此与 INSERT 同语义（整列随写路径进新表，需要 CREATE 特权，值来自表自身，
 * 与「INSERT INTO t2 SELECT *」一致，不构成额外的读侧泄露）。
 *
 * <p><b>fail-closed 口径</b>：策略声明了 schema 里已不存在的列名时静默忽略——
 * 不存在的列没有值可泄露；这与 mask / 行过滤引用已删列时引擎侧报错的口径不同，
 * 原因是那两者「缺列 = 策略执行了一半」，而这里的缺列只会让保护范围更小。
 */
private[spark] class PolicyHiddenColumnsRule(session: SparkSession) extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!PolicySettings.enforced(session)) {
      return plan
    }
    curtail(trimStars(plan))
  }

  // ------------------------------------------------------------------ 第一遍：星号裁剪

  /** 引用了不可见列的全宽普通投影 → 裁掉；其余带引用的投影形态 → 失败。 */
  private def trimStars(plan: LogicalPlan): LogicalPlan = plan match {
    // 已强制的子树整棵跳过（里面的引用都是策略自己的）
    case _: PolicyRelation => plan

    // 写路径整棵跳过（类注释）
    case w if isWrite(w) => plan

    case p @ Project(projectList, child) =>
      val child2 = trimStars(child)
      val hiddenById = hiddenIdsOf(child2)
      val touched = projectList.flatMap(_.collect {
        case a: AttributeReference if hiddenById.contains(a.exprId) => a.exprId
      }).distinct
      if (touched.isEmpty) {
        p.withNewChildren(Seq(child2))
      } else if (isFullWidthPlain(projectList, child2)) {
        // 星号语义：只返回可读列（保留原顺序）
        Project(projectList.filterNot(ne =>
          ne.collect { case a: AttributeReference => a.exprId }.exists(hiddenById.contains)), child2)
      } else {
        val (column, table) = hiddenById(touched.head)
        throw new PolicyEnforcementException(
          s"column '$column' is not readable for you on $table (hidden by the table's data"
            + " policy); list readable columns explicitly — SELECT * returns the readable"
            + " subset, but referencing hidden columns is refused (design §5.8)")
      }

    case other => other.mapChildren(trimStars)
  }

  /** 全宽普通投影：逐位等于 child 的输出、全是裸属性引用（无别名、无表达式）。 */
  private def isFullWidthPlain(projectList: Seq[NamedExpression], child: LogicalPlan): Boolean =
    projectList.size == child.output.size &&
      projectList.asInstanceOf[Seq[Expression]].zip(child.output).forall {
        case (ne: AttributeReference, a: Attribute) => ne.exprId == a.exprId
        case _ => false
      }

  // ------------------------------------------------------------------ 第二遍：兜底裁剪

  /**
   * 给每个带不可见列的 PolicyRelation 包一层只含可读列的 Project（保留原顺序）。
   * 不递归进新 Project 的 child（就是刚处理过的 relation），也不进写命令。
   */
  private def curtail(plan: LogicalPlan): LogicalPlan = plan match {
    case pr: PolicyRelation =>
      val readable = pr.output.filterNot(a => pr.hiddenColumns.contains(a.name))
      if (readable.length < pr.output.length) Project(readable, pr) else pr

    case w if isWrite(w) => plan

    case other => other.mapChildren(curtail)
  }

  // ------------------------------------------------------------------ 公共

  private def isWrite(plan: LogicalPlan): Boolean = plan match {
    // EXPLAIN 是 Command，但它的 child 正是用户要看的查询——必须照常处理
    case _: ExplainCommand => false
    // V1 / 执行命令形态（INSERT INTO hadoopfs、CTAS 命令、RunnableCommand 一族）
    case _: Command => true
    // V2 写节点全集（4.1 实测：INSERT 顶层是 AppendData，行级写是 ReplaceData；
    // CreateTableAsSelectStatement 两个版本都没有——V2 CTAS 若有未列出的形态漏进来，
    // 走的是「按可读子集建表」的良性回退，不会报错）
    case _: AppendData | _: ReplaceData | _: InsertIntoStatement | _: DeleteFromTable |
         _: UpdateTable | _: MergeIntoTable | _: RowLevelWrite | _: OverwriteByExpression |
         _: OverwritePartitionsDynamic => true
    case _ => false
  }

  /** 子树里所有 PolicyRelation 的不可见列 → exprId →（列名，表名）——报错与判定共用。 */
  private def hiddenIdsOf(plan: LogicalPlan): Map[ExprId, (String, String)] = {
    val builder = Map.newBuilder[ExprId, (String, String)]
    plan.collect { case pr: PolicyRelation =>
      val hidden = pr.hiddenColumns.toSet
      pr.output.filter(a => hidden.contains(a.name))
        .foreach(a => builder += a.exprId -> ((a.name, pr.tableRef)))
    }
    builder.result()
  }
}
