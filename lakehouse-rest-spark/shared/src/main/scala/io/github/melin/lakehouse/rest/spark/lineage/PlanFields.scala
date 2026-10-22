package io.github.melin.lakehouse.rest.spark.lineage

import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.columnar.InMemoryRelation

import scala.util.control.NonFatal

/**
 * 所有「必须靠反射才能拿到」的东西，都收在这一个对象里（§5.8 #8）。
 *
 * <p><b>为什么需要它。</b>计划节点的字段有的是 `private`（例如缓存关系的原始逻辑计划），
 * 而反射没有编译期保护：升级 Spark 时**编译不会红、运行时才炸**。把反射集中到一处，
 * 升级时只改这里，并且 {@code PlanFieldsTests} 会逐个把它们钉住（§11.3）。
 *
 * <p><b>为什么只有三个访问器，而不是参考实现的十二个。</b>设计稿按参考实现的印象列了
 * 十二个字段，落地时用 `javap` 逐个核对过 3.5.9 与 4.1.2，结论是**大多数都是 public**：
 *
 * <pre>
 * InsertIntoStatement.{table,partitionSpec,userSpecifiedCols,query,overwrite,byName}   public
 * AppendData / OverwriteByExpression / OverwritePartitionsDynamic.table|query|isByName public
 * CreateTableAsSelect.{name,query,tableName}                                          public
 * MergeIntoTable.{targetTable,sourceTable,matchedActions,notMatchedActions}           public
 * CommandResult.commandLogicalPlan                                                   public（不是 private！）
 * </pre>
 *
 * 也就是说设计稿里「`CommandResult` 得反射取 `commandLogicalPlan`」这条**不成立**，
 * 直接调用即可。反射只剩下「缓存关系的原始逻辑计划」与「表标识符的兜底解析」两件事，
 * 少一处反射就少一处升级时会静默坏掉的地方。
 */
private[lineage] object PlanFields {

  /**
   * 缓存关系背后的逻辑计划。
   *
   * <p>两种取值路径，都与 Spark 版本有关：
   *
   * <ul>
   *   <li><b>Spark 4.1</b>：{@code CachedRDDBuilder.logicalPlan}（private 字段）；</li>
   *   <li><b>Spark 3.5</b>：{@code CachedRDDBuilder.cachedPlan} 已经是 **SparkPlan**、
   *       而 {@code InMemoryRelation} 不再保留 `logicalLink`——也就是说这一版**取不到**。
   *       取不到就返回 {@code None}：图上少一段缓存链路，好过编一段假的。</li>
   * </ul>
   *
   * <p>这条与 §5.4「`InMemoryRelation` → `cacheBuilder.cachedPlan` 留」不同，原因是
   * 3.5 起 `cachedPlan` 的类型变了；写在这里免得以后被当成 bug 查。
   */
  def logicalPlanOf(relation: InMemoryRelation): Option[LogicalPlan] =
    privateField(relation.cacheBuilder, "logicalPlan")
      .orElse(privateField(relation, "logicalLink"))
      .flatMap {
        case plan: LogicalPlan => Some(plan)
        case boxed: Option[_] => boxed.collect { case plan: LogicalPlan => plan }
        case _ => None
      }

  /**
   * 读一个私有字段。
   *
   * <p>沿类层次往上找（Scala 的 case class 字段可能在父类里声明），并且**永不抛异常**：
   * 反射失败等价于「这个字段这一版没有」，由调用方决定退化行为。
   */
  def privateField(target: AnyRef, name: String): Option[AnyRef] = {
    if (target == null) {
      return None
    }
    var clazz: Class[_] = target.getClass
    while (clazz != null) {
      try {
        val field = clazz.getDeclaredField(name)
        field.setAccessible(true)
        return Option(field.get(target))
      } catch {
        case _: NoSuchFieldException => clazz = clazz.getSuperclass
        case NonFatal(_) =>
          // SecurityException / InaccessibleObjectException：当成「取不到」，
          // 不要让它冒到 onSuccess 上（那会让一条 SQL 以一个无关的错误收场，§6.6）
          return None
      }
    }
    None
  }
}
