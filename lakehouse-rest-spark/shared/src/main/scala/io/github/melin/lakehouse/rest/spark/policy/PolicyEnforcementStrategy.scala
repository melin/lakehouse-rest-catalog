package io.github.melin.lakehouse.rest.spark.policy

import org.apache.spark.sql.catalyst.expressions.{Alias, AttributeReference, NamedExpression}
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.{SparkPlan, SparkStrategy}

/**
 * 把 {@link PolicyRelation} 展开成物理计划（§5.5 的下半段）：
 *
 * <pre>
 * Project(全量输出；被脱敏列换成 mask 表达式)
 *   Filter(rowFilter)
 *     （原 relation 的物理计划）
 * </pre>
 *
 * <p><b>三条铁律（§5.3.5 / §5.5），展开处是唯一执行点：</b>
 *
 * <ol>
 *   <li><b>先裁行、再脱敏</b>——行过滤可能引用被脱敏列，若先脱敏，判定用的就不再是
 *       原值；Filter 在 Project 下面，看的是 scan 的原始输出；</li>
 *   <li><b>所有 mask 在同一层 Project、参数一律读原值、不链式</b>——{@code mask_2} 看到的
 *       {@code b} 是原始 {@code b}；若允许链式，结果就依赖列的插入顺序；</li>
 *   <li><b>Project 按 child 的完整 output 展开</b>——mask 参数可能引用未出现在上层
 *       投影里的列（{@code SELECT ssn}，而 mask 引用 {@code region}）；优化器穿不过
 *       PolicyRelation，列裁剪到不了里面，多算几列是 "secure choice"（§5.3.3）。</li>
 * </ol>
 *
 * <p><b>别名的 exprId 必须沿用原列</b>：上层计划的引用都指向原 exprId，schema 要做到
 * 「列名、顺序、类型完全不变」（§5.6），脱敏对下游透明。同一张表在计划里出现多次
 * （自连接）会各自展开一份——不共享，物理层的 {@code ReuseExchange} 会做该做的事。
 */
private[spark] class PolicyEnforcementStrategy extends SparkStrategy {
  // 用 SparkStrategy 而不是 org.apache.spark.sql.Strategy：后者 4.0 起被移除，
  // 而 SparkStrategy 在 3.5 / 4.1 都存在，也正是 injectPlannerStrategy 的参数类型；
  // planLater 是它的具体方法（javap 可证），两个版本签名一致

  override def apply(plan: LogicalPlan): Seq[SparkPlan] = plan match {
    case policy: PolicyRelation =>
      expand(policy) :: Nil
    case _ => Nil
  }

  private def expand(policy: PolicyRelation): SparkPlan = {
    // 表达式锚定 fullRelation（wrap 时的原始 relation）的 output 而不是 child：child 会被
    // pushdown 重写成 scan relation（等价、exprId 不变，但对象不同）；fullRelation 永远是
    // wrap 时的原对象。优化器内 exprId 稳定，上层引用与这里的 attrs 必然对得上
    val attrs = policy.fullRelation.output.map(_.asInstanceOf[AttributeReference])
    val filterExpr = policy.rowFilter.map { ast =>
      PolicyExpressionBuilder.rowFilter(ast, attrs)
    }

    // 存在类型（JMap[String, _]）进不了 Map 的 pair 归一，这里放宽成 Any——
    // mask 的形状解释在 PolicyExpressionBuilder，本层不做任何假设
    val maskByColumn: Map[String, Any] =
      policy.masks.map(m => m.column -> (m.mask: Any)).toMap
    // 同名多列（schema 不该有，但 fail-closed 总得有出口）：按列名找属性时出现多个，
    // 逐个展开等价于每个都脱敏——与「mask 是列的属性」一致。
    // 注意：保留 Alias 本身（NamedExpression），绝不能 _.toAttribute——那会把 mask
    // 表达式抹回普通属性引用，投影退化为纯透传，脱敏静默失效
    val projections: Seq[NamedExpression] = attrs.map { attr =>
      maskByColumn.get(attr.name) match {
        case Some(maskAst) =>
          val maskExpr = PolicyExpressionBuilder.mask(maskAst, attr, attrs)
          // 沿用原列的 name / exprId / qualifier：对上层完全透明
          Alias(maskExpr, attr.name)(exprId = attr.exprId, qualifier = attr.qualifier)
        case None =>
          attr
      }
    }

    // 扫描用 child（可能是 scan relation 或「Project → scan」，都可规划）；
    // fullRelation 是裸 DataSourceV2Relation，物理策略根本不会规划它（正常链路里
    // V2ScanRelationPushDown 保证 scan 转换先行），绝不能直接 planLater 它
    val scan = planLater(policy.child)
    val filtered = filterExpr match {
      case Some(expr) => org.apache.spark.sql.execution.FilterExec(expr, scan)
      case None => scan
    }
    // 必须用 PolicyProjectExec 而不是 ProjectExec：后者会被 RemoveRedundantProjects
    // 当作冗余投影删除（判据是「输出 exprId 与 child 一致」，而我们的别名恰好沿用
    // 原 exprId——规则不知道别名换了值）。详见 PolicyProjectExec 类注释
    PolicyProjectExec(projections, filtered)
  }
}
