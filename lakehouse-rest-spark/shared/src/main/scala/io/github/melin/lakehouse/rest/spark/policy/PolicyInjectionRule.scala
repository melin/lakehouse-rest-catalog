package io.github.melin.lakehouse.rest.spark.policy

import java.util

import scala.collection.JavaConverters._

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.expressions.{Expression, ExprId, PlanExpression}
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.rules.Rule
import org.apache.spark.sql.execution.datasources.v2.DataSourceV2Relation

/**
 * 分析收尾阶段把策略包到读路径上（主注入点，§3.2 / §5.5）。
 *
 * <p><b>为什么是 PostHocResolution：</b>这个批次跑完时 {@code SELECT *} 已展开成具体列、
 * 视图与 CTE 已内联、计划里只剩已解析的 Attribute——在这个点注入，漏判面只有
 * 「哪些 relation 解析到了哪张表」一个问题；更早的批次要接回 {@code Star}、
 * {@code UnresolvedRelation}、{@code UnresolvedAttribute} 三种未解析形态，每种都是一个漏判点。
 *
 * <p><b>遍历规则（每一条都对应一次「不这么写就错」）：</b>
 *
 * <ul>
 *   <li>只认 V2 relation：本方案的表全是 V2（Paimon / Iceberg），V1 的
 *       {@code LogicalRelation} 刻意不碰——范围之外的能力宁可没有，不要半吊子；</li>
 *   <li>已包过 {@link PolicyRelation} 的子树整棵跳过：同一批分析里 relation 只会路过一次，
 *       但视图内联、缓存重建会让子计划再分析一遍，无守卫就是 {@code mask(mask(x))}；
 *       而 CASE WHEN 类表达式套两层不是幂等的；</li>
 *   <li>写命令的目标表不包（§5.7）：行过滤套到 DML 上会出现「看不见所以删不掉 /
 *       改不了」。DELETE / UPDATE / MERGE 的目标表在 children 里，按输出 exprId 保护；
 *       {@code INSERT INTO t SELECT ...} 的目标表不在 children 里，天然扫不到；</li>
 *   <li>写命令的 **source** 照常包：{@code INSERT INTO t2 SELECT * FROM t} 读到的是
 *       脱敏后的值——这是用户自己选的语义（§5.7），不是漏洞；</li>
 *   <li>子查询（{@code IN (SELECT ...)} 的右表等）不在 children 里，挂在表达式上，
 *       由 {@code mapSubqueries} 接住——漏了它，子查询里的表就不受策略约束。</li>
 * </ul>
 *
 * <p>策略解析按 catalog 分组批量做，结果整批包上。任何一步失败 → fail-closed
 * （见 {@link PolicyFetcher}），绝不把「包不上」当成「没策略」。
 */
private[spark] class PolicyInjectionRule(session: SparkSession) extends Rule[LogicalPlan] {

  override def apply(plan: LogicalPlan): LogicalPlan = {
    if (!PolicySettings.enforced(session)) {
      return plan
    }
    enforce(plan, protectedIds = Set.empty)
  }

  private def enforce(plan: LogicalPlan, protectedIds: Set[ExprId]): LogicalPlan = plan match {
    // 已强制的子树整棵跳过：里面没有未处理的 relation，也没有写命令
    case _: PolicyRelation => plan

    case rel: DataSourceV2Relation =>
      if (exprIdsOf(rel).exists(protectedIds)) {
        rel
      } else {
        wrapOrSelf(rel)
      }

    // DELETE / UPDATE：目标表是 child，保护它的 exprId；condition 里的子查询照常处理
    case d: DeleteFromTable =>
      protectTarget(d.table, protectedIds) { ids =>
        withSubqueries(d.mapChildren(child => enforce(child, ids)))(
          p => enforce(p, protectedIds))
      }
    case u: UpdateTable =>
      protectTarget(u.table, protectedIds) { ids =>
        withSubqueries(u.mapChildren(child => enforce(child, ids)))(
          p => enforce(p, protectedIds))
      }

    // MERGE：目标是 left，source（right）照常包
    case m: MergeIntoTable =>
      protectTarget(m.targetTable, protectedIds) { ids =>
        withSubqueries(m.mapChildren(child => enforce(child, ids)))(
          p => enforce(p, protectedIds))
      }

    // 分析期已重写的行级写（Spark 4.0 起 UPDATE / DELETE 在**分析期**就被改写成
    // ReplaceData 一类 RowLevelWrite 节点，PostHocResolution 看到的不再是
    // UpdateTable / DeleteFromTable；3.5 的重写仍发生在优化器，此分支不命中）。
    // query 读的是目标表自己的数据，按 §5.7 目标豁免——否则脱敏值会被写回表里
    // （数据被永久污染：n 脱敏一遍再脱一遍变 x）。table 字段与 query 里的目标
    // relation 是同一实例（同 exprId），按输出 exprId 保护；条件子查询里引用的
    // 其它表不在保护范围内，照常包
    case rlw: RowLevelWrite =>
      protectTarget(rlw.table, protectedIds) { ids =>
        withSubqueries(rlw.mapChildren(child => enforce(child, ids)))(
          p => enforce(p, protectedIds))
      }

    case other =>
      withSubqueries(other.mapChildren(child => enforce(child, protectedIds)))(
        p => enforce(p, protectedIds))
  }

  /**
   * 遍历本节点表达式里挂着的子查询计划（{@code IN (SELECT ...)} 的右表等）。
   * 3.5 起 {@code QueryPlan#mapSubqueries} 不再公开，这里手写同构的一层——
   * 只 map 顶层 PlanExpression，内部递归交给 {@code enforce}。
   */
  private def withSubqueries(plan: LogicalPlan)
                            (f: LogicalPlan => LogicalPlan): LogicalPlan =
    plan.mapExpressions {
      case p: PlanExpression[LogicalPlan @unchecked] => p.withNewPlan(f(p.plan)).asInstanceOf[Expression]
      case e => e
    }.asInstanceOf[LogicalPlan]

  private def exprIdsOf(plan: LogicalPlan): Set[ExprId] =
    plan.output.map(_.exprId).toSet

  /**
   * 保护写命令的目标表：目标是 relation 时记下它的输出 exprId（可能隔着
   * SubqueryAlias，剥掉再认）。保护靠 exprId 而不是表名——自连接里同名表会出现
   * 两次，只有 exprId 能区分「这一份是目标」。
   */
  private def protectTarget(target: LogicalPlan, protectedIds: Set[ExprId])
                           (body: Set[ExprId] => LogicalPlan): LogicalPlan = {
    val ids = exprIdsOf(unwrapAlias(target))
    body(protectedIds ++ ids)
  }

  private def unwrapAlias(plan: LogicalPlan): LogicalPlan = plan match {
    case SubqueryAlias(_, child) => unwrapAlias(child)
    case other => other
  }

  /** 没有策略 → 原样返回；有策略 → 包上 PolicyRelation（幂等性由外层 case 保证）。 */
  private def wrapOrSelf(rel: DataSourceV2Relation): LogicalPlan = {
    val ref = PolicyRef.of(rel, session)
      // 没有 identifier 的 V2 关系是 `spark.read.format(...)` 读出来的外部源，
      // 目录里点不开、也就没有策略可言；直接放行
      .getOrElse(return rel)
    val resolved = try {
      fetcher.resolve(Seq(ref))
    } catch {
      case e: PolicyEnforcementException => throw e
      case e: Exception => throw PolicyFetcher.failClosed(ref, Seq(ref), e)
    }
    resolved.getOrElse(ref, None) match {
      case Some(info) =>
        val masks = info.masks.asScala.map(m => PolicyMask(m.column, m.mask)).toSeq
        val rowFilter = Option(info.rowFilter).map(_.asInstanceOf[util.Map[String, Any]])
        // 第二个 rel 是 fullRelation（wrap 时的原始 relation，物理展开的表达式锚点）；
        // anchors 把全量输出声明为 expressions，防止裁剪钻进本节点之下（见类注释）
        PolicyRelation(rel, rel, ref, info.version, rowFilter, masks, rel.output)
      case None =>
        rel
    }
  }

  private lazy val fetcher = new PolicyFetcher(session)
}
