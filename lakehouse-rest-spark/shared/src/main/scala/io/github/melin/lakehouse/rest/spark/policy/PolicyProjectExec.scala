package io.github.melin.lakehouse.rest.spark.policy

import org.apache.spark.rdd.RDD
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.{Attribute, NamedExpression, SortOrder, UnsafeProjection}
import org.apache.spark.sql.catalyst.plans.physical.Partitioning
import org.apache.spark.sql.execution.{SparkPlan, UnaryExecNode}

/**
 * 承载 mask 展开的物理投影节点——<b>刻意不叫 {@code ProjectExec}</b>。
 *
 * <p><b>为什么不能用 ProjectExec（实测得出的修正）：</b>mask 别名必须沿用原列的
 * exprId（schema 透明性，见 {@link PolicyEnforcementStrategy}），而物理规则
 * {@code RemoveRedundantProjects} 恰好以「输出 exprId 与 child 一致」为冗余判据——
 * 它无从知道别名换了值（这正是它自己的信任假设，与 §5.6 警告的「裸投影被优化器吃掉」
 * 是同一类问题），于是 mask 投影在物理规划期被整体删除，数据原样出表。自定义节点
 * 不在任何规则的匹配名单里，删除逻辑无从下手。
 *
 * <p>语义与 ProjectExec 等价：对每行按 projectList 求值；投影是逐行映射，
 * 不改变行的顺序与分区，因此 outputOrdering / outputPartitioning 直接沿用 child，
 * 上层的 Sort / Join 需求检查可以看穿本节点，不会插入多余的 Shuffle。
 *
 * <p>不做 codegen（未实现 CodegenSupport）：本节点上方多出一条 codegen 阶段边界，
 * 对正确性无影响；脱敏本来就只发生在带策略的表上，性能敏感路径请走服务端
 * 下发的数据隔离网关方案（§13）。
 */
private[spark] case class PolicyProjectExec(projectList: Seq[NamedExpression], child: SparkPlan)
  extends UnaryExecNode {

  // projectList 的别名全部沿用原列 name/exprId/qualifier，output 即「原 schema」
  override def output: Seq[Attribute] = projectList.map(_.toAttribute)

  override def outputOrdering: Seq[SortOrder] = child.outputOrdering

  override def outputPartitioning: Partitioning = child.outputPartitioning

  override protected def doExecute(): RDD[InternalRow] =
    child.execute().mapPartitions { iter =>
      // 绑定投影：projectList 里的属性全部能在 child.output 上定位（exprId 相同）
      val projection = UnsafeProjection.create(projectList, child.output)
      iter.map(projection)
    }

  override protected def withNewChildInternal(newChild: SparkPlan): SparkPlan =
    copy(child = newChild)

  override def simpleString(maxFields: Int): String =
    s"PolicyProjectExec[${projectList.map(_.name).mkString(", ")}]"
}
