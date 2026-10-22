package io.github.melin.lakehouse.rest.spark.lineage

import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.slf4j.LoggerFactory

/**
 * 「刚刚解析过的那条 SQL」的旁路（§2.4）。
 *
 * <p><b>为什么需要它。</b>`QueryExecutionListener#onSuccess` 只给 `funcName`（`collect` /
 * `command` / `append`），**不含 SQL 文本**；而计划树里也没有原文。解析器是唯一在
 * 「文本还在」的时刻经过的地方，因此让它顺手记一笔，监听器随后取走。
 *
 * <p><b>为什么按「计划实例」而不是 `ThreadLocal` 关联。</b>这一点踩过：看起来
 * `spark.sql(...)` 是「解析完就地执行」，用线程本地足够——实际上
 * `QueryExecutionListener` 的回调是被 `ExecutionListenerManager` 投递到
 * **`spark-listener-group-shared` 线程**上异步执行的。解析在 `main` 上、取文本在监听
 * 线程上，`ThreadLocal` 永远读到空，症状是「图都对、只有 SQL 文本没了」。
 *
 * <p>改用身份关联：`SparkSession#sql` 把**解析出来的那个计划对象**直接交给
 * `QueryExecution`，所以监听器里的 `qe.logical` 与 {@link #record} 时拿到的 `plan` 是
 * 同一个实例。键必须用 `IdentityHashMap`——`LogicalPlan#equals` 会做结构比较，既慢，
 * 又可能把两条一模一样的 SQL 合成一条。
 *
 * <p><b>只在「写计划」时记录。</b>不加这一条的话，`spark.sql("SELECT 1")` 留下的文本
 * 会被随后一次 `df.write.saveAsTable()` 取走。判断依据是**已经解析出来的计划**，
 * 不是 SQL 前缀——前缀判断在 `INSERT` 被 analyzer 改写成 `AppendData` 之后就不成立了（§5.1）。
 *
 * <p><b>取即删除、容量有上限。</b>宁可少一条 SQL 文本，也不要让它无限攒着：写入失败时
 * 那条记录没人取，靠 {@link #MAX_ENTRIES} 兜底（超了整体清空——最坏结果是丢掉几条文本）。
 */
private[spark] object SqlTextRegistry {

  private val log = LoggerFactory.getLogger(SqlTextRegistry.getClass)

  /** 同时挂着的文本条数上限。一次只会有一两条在途，64 已经是很松的上限。 */
  private val MAX_ENTRIES = 64

  private val texts = new java.util.IdentityHashMap[LogicalPlan, String]()

  /** 解析完成时调用：只有写计划才记。 */
  def record(sqlText: String, plan: LogicalPlan): Unit = {
    if (sqlText == null || sqlText.isEmpty || plan == null) {
      return
    }
    if (!LineageExtractor.isWritePlan(plan)) {
      log.info(s"SqlTextRegistry: parsed plan is NOT a write plan: ${plan.getClass.getName}")
      return
    }
    // 顺手告诉监听器「一条 run 应该会来」：JVM 退出兜底（armShutdownHook）靠它
    // 区分「回调还没送达」与「本来就没有写入」。解析是同步的，这是整个链路里
    // 唯一能拿到这个信号的时刻——回调本身走异步监听总线，JVM 退出时可能被丢。
    LakehouseLineageListener.noteWriteStatement()
    texts.synchronized {
      if (texts.size() >= MAX_ENTRIES) {
        texts.clear()
      }
      texts.put(plan, sqlText)
    }
  }

  /**
   * 监听器取走文本；没有就返回 `None`。
   *
   * @param plan 传 `QueryExecution#logical`——就是 `spark.sql` 里解析出来的那个实例
   */
  def take(plan: LogicalPlan): Option[String] = {
    if (plan == null) {
      return None
    }
    texts.synchronized(Option(texts.remove(plan)))
  }

  /** 语句失败时丢掉这一条：失败的写入不该把文本留给下一次写操作。 */
  def discard(plan: LogicalPlan): Unit = {
    if (plan == null) {
      return
    }
    texts.synchronized {
      texts.remove(plan)
      ()
    }
  }

  /** 兜底清空（测试与异常路径）。 */
  def clear(): Unit = texts.synchronized {
    texts.clear()
  }
}
