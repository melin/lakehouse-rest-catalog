package io.github.melin.lakehouse.rest.spark.policy

import java.util.{Map => JMap}

import org.apache.spark.sql.catalyst.expressions.{Attribute, Expression}
import org.apache.spark.sql.catalyst.plans.logical.{LogicalPlan, UnaryNode}

/**
 * 「这张表带策略」的不透明逻辑节点（§5.6 方案 B）。
 *
 * <p><b>它存在的唯一理由是「优化器不认识它」。</b>Catalyst 没有为未知 UnaryNode 准备
 * 消除 / 重排类规则：{@code CollapseProject}、{@code PushPredicateThroughNonJoin} 一族
 * 全部按节点类型匹配，遇到没见过的节点一律绕行。于是行过滤与列脱敏只要包在里面，
 * 就既不会被消除、也不会被重排到「更弱」的位置——这正是 SPARK-59726 立项的原因
 * （裸投影会被优化器吃掉，静默返回明文），也是本方案不走「裸投影」路线的原因（§5.6 方案 A）。
 *
 * <p><b>但「不认识」不等于「不触碰」（实测得出的修正，Kyuubi #3186 的镜像教训）：</b>
 * 列裁剪一族并不针对未知节点绕行——{@code ColumnPruning} 会按「透明 UnaryNode」的假设
 * 在本节点下面插入裁剪 Project（裁剪列取自外层投影），{@code RemoveNoopOperators} 顺势
 * 删掉外层投影，{@code V2ScanRelationPushDown} 再把裁剪推进 scan——三步走完，策略表达式
 * 引用的列在扫描层就没了，物理展开时无米下锅。防线是 {@link #anchors}：把全量输出声明为
 * 本节点的 expressions（{@code QueryPlan#expressions} 是 final，只能靠 case class 字段
 * 参与 productIterator 推导），优化器的引用统计看到每一列都被本节点用到，裁剪便停在本
 * 节点之上——多算几列正是「secure choice」（§5.3.3 的列裁剪铁律本来就要求按全量展开）。
 * 实测（探针对照）在 3.5 上成立；升级 Spark 时必须重验（附录 A）。
 *
 * <p>展开后的形状由我们完全控制：
 *
 * <pre>
 * Project(所有列；被脱敏列换成 mask 表达式)   ← 单层投影，所有 mask 参数读原值
 *   Filter(rowFilter)                        ← 在 Project 下面：行过滤看的是脱敏前的原值
 *     （child 的物理计划；child 已被 V2ScanRelationPushDown 转成 scan relation）
 * </pre>
 *
 * <p><b>output 必须逐字节等于 child.output</b>：列名、顺序、类型、exprId 全部不变，
 * 只有执行期可能出现的数据可为 NULL（mask 语义，见 §5.3.3）。否则用户 {@code SELECT *}
 * 的 DataFrame schema 会变，case class 反序列化直接炸。因此这个类不重写 {@code output}，
 * 用 {@link UnaryNode} 的默认实现。
 *
 * <p><b>字段用 Java Map 而不是转换成 Scala 集合</b>：AST 是 Jackson 反序列化的原样产物
 * （{@code LinkedHashMap}），转 Scala 集合只是多一次拷贝；case class 相等性里
 * Java Map 的 {@code equals} 本来就是按内容比的，两次解析同一策略得到同一节点。
 *
 * @param relation      当前 child；pushdown 会把它转成 scan relation（等价重写，exprId 不变）
 * @param fullRelation  wrap 时的原始 relation；物理展开时表达式以它的 output 为锚
 *                      （优化器内 exprId 稳定，与 child 的 scan 输出逐一对得上）
 * @param tableRef      三段全名 {@code catalog.db.table}，报错与日志用
 * @param policyVersion 策略版本（下发的 etag），日志与排障用
 * @param rowFilter     行过滤 AST；没配行过滤时为 None
 * @param masks         列脱敏绑定：列名 → mask 节点（{@code {"fn":...}} 或 {@code {"expr":...}}）
 * @param anchors       全量输出属性，声明为本节点的 expressions——优化器引用统计的锚点，
 *                      防止裁剪钻进本节点之下（见类注释）；不参与任何执行语义
 */
case class PolicyRelation(
    relation: LogicalPlan,
    fullRelation: LogicalPlan,
    tableRef: String,
    policyVersion: Long,
    rowFilter: Option[JMap[String, _]],
    masks: Seq[PolicyMask],
    anchors: Seq[Expression]
) extends UnaryNode {

  override def child: LogicalPlan = relation

  /**
   * output 必须逐字节等于 child.output（类注释）：列名、顺序、类型、exprId 全部不变。
   * 这是「脱敏对下游透明」的硬约束——SELECT * 的 schema 不能因策略而变。
   */
  override def output: Seq[Attribute] = child.output

  override protected def withNewChildInternal(newChild: LogicalPlan): LogicalPlan =
    copy(relation = newChild)

  /**
   * 只打印一行类名 + 表名 + 版本（§7.6）：逻辑计划的 {@code EXPLAIN} 输出里
   * 不能露出策略表达式文本——字面量可能就是黑名单成员或别人的租户名。
   * 物理计划会展开（P0 接受该泄露，§7.6 先按方案 b 交付）。
   */
  override def simpleString(maxFields: Int): String =
    s"PolicyRelation[$tableRef#$policyVersion]"
}

/** 一列的 mask 绑定（下发协议里 {@code MaskBinding} 的引擎侧形状）。 */
final case class PolicyMask(column: String, mask: JMap[String, _])
