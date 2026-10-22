package io.github.melin.lakehouse.rest.spark.lineage

import io.github.melin.lakehouse.rest.spark.PaimonRestManagement
import io.github.melin.lakehouse.rest.spark.client.ManagementApiClient
import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.QueryExecution
import org.apache.spark.sql.util.QueryExecutionListener
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal

/**
 * 采集入口：`QueryExecutionListener#onSuccess`（§5.1）。
 *
 * <p>两种安装方式，都能用：
 *
 * <pre>
 * # 1) 配置（用户零代码，spark-sql / spark-submit 的常规做法）
 * spark.sql.queryExecutionListeners=io.github.melin.lakehouse.rest.spark.lineage.LakehouseLineageListener
 * spark.paimon.rest.lineage.enabled=true
 *
 * # 2) 扩展懒注册（用了本仓 ManagementSparkExtensions 的会话自动生效）
 * spark.sql.extensions=io.github.melin.lakehouse.rest.spark.ManagementSparkExtensions
 * spark.paimon.rest.lineage.enabled=true
 * </pre>
 *
 * <p><b>为什么监听器里没有构造函数。</b>`spark.sql.queryExecutionListeners` 是反射实例化的，
 * 只认无参构造，拿不到 `SparkSession`。因此配置与客户端都在**第一次回调**时按
 * `SparkSession.getActiveSession` 懒初始化。副作用是：走配置那条路时，Spark 一定会把类
 * 挂上去，`enabled=false` 只能靠第一行判断短路——这正是推荐第二条（扩展懒注册）的原因，
 * 它连类都不挂（§10.2）。
 *
 * <p><b>整个回调包一层 `try/catch(Throwable)`。</b>监听器抛异常会让 `spark.sql` 报一个与
 * SQL 无关的错，用户会去查 SQL（§6.6）。这里连 `Error` 都接：`OutOfMemoryError` 之外
 * 的一切都不值得让作业挂掉，而 OOM 时这个 catch 也不会更糟。
 */
class LakehouseLineageListener extends QueryExecutionListener {

  import LakehouseLineageListener._

  /** 待定的就地执行回调：run 已算好，等语句级回调来合并，或宽限期到再上报。 */
  private case class Pending(run: LineageRun, createdAt: Long, task: java.util.concurrent.ScheduledFuture[_])

  /** 待定 run 表。键必须是身份不是结构——`LogicalPlan#equals` 递归比较，含环计划会栈溢出。 */
  private val pendings =
    new java.util.concurrent.ConcurrentHashMap[IdentityKey, Pending]()

  override def onSuccess(funcName: String, qe: QueryExecution, durationNs: Long): Unit = {
    try {
      val plan = qe.analyzed
      if (plan != null && LineageExtractor.isWritePlan(plan)) {
        val session = qe.sparkSession
        if (session != null && configure(session)) {
          if (isEagerCommandExecution(plan, qe)) {
            // 就地执行（Dataset 创建期 eagerlyExecuteCommands 把命令跑掉的那次回调）。
            // 它可能是唯一一次回调（spark.sql(insert) 不接 collect 的场景），也可能
            // 先于语句级回调到达（同一个 analyzed 实例）。两种情况在回调那一刻分不出来，
            // 所以不立刻上报：先记为待定，语句级回调来了就合并，宽限期到再兜底上报。
            rememberEager(plan, durationNs)
          } else {
            // 语句级回调（用户的 action，或 DataFrame 写 API 的 withAction）。
            // SQL 文本旁路的键就是这里的 qe.logical——只有语句级回调取得到文本。
            // 同一次写入若已被就地执行记为待定，此刻撤掉（连同它的兜底任务），
            // 让带文本的这条上报。
            Option(pendings.remove(new IdentityKey(plan))).foreach(_.task.cancel(false))
            report(plan, qe, sqlText = SqlTextRegistry.take(qe.logical), durationNs)
          }
        }
        // 无论开关与否：这条写语句的回调已经送达，退出兜底不用再等它（见 noteResolved）
        noteResolved()
      }
    } catch {
      case NonFatal(e) =>
        log.warn(s"Lineage collection failed for a successful ${funcName}(...); the statement itself is unaffected: $e")
      case e: Throwable =>
        log.warn(s"Lineage collection failed with a fatal error; the statement itself is unaffected: $e")
    }
  }

  /**
   * `onFailure` 什么都不记，只清旁路文本与待定 run，并打一条 WARN。
   *
   * <p>失败的 INSERT 没有让任何依赖成立；记进去只会让图上出现「一次没写完却存在的边」。
   * 参考实现在这里也发事件（带 exception），那是给运维面板用的，我们不抄（§5.1）。
   */
  override def onFailure(funcName: String, qe: QueryExecution, exception: Exception): Unit = {
    SqlTextRegistry.discard(qe.logical)
    if (qe.analyzed != null) {
      Option(pendings.remove(new IdentityKey(qe.analyzed))).foreach(_.task.cancel(false))
      if (LineageExtractor.isWritePlan(qe.analyzed)) {
        noteResolved()
      }
    }
    log.warn(s"Ignoring a failed ${funcName}(...) for lineage; the failure itself is reported by Spark: $exception")
  }

  /** 记一条待定 run：宽限期后被定时器兜底上报，除非语句级回调先来把它撤掉。 */
  private def rememberEager(plan: LogicalPlan, durationNs: Long): Unit = {
    val key = new IdentityKey(plan)
    if (!pendings.containsKey(key)) {
      val finishedAt = System.currentTimeMillis()
      val run = extract(plan, sqlText = None, startedAt = finishedAt - durationNs / 1000000L, finishedAt = finishedAt)
      run.foreach { r =>
        val task: java.util.concurrent.ScheduledFuture[_] = sweeper.schedule(
          new Runnable {
            override def run(): Unit =
              Option(pendings.remove(key)).foreach(p => reporter.get.offer(p.run))
          },
          EAGER_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        pendings.put(key, Pending(r, finishedAt, task))
      }
    }
  }

  private def report(plan: LogicalPlan, qe: QueryExecution, sqlText: Option[String], durationNs: Long): Unit = {
    val finishedAt = System.currentTimeMillis()
    extract(plan, sqlText, finishedAt - durationNs / 1000000L, finishedAt).foreach { run =>
      reporter.get.offer(run)
    }
  }

  private def extract(plan: LogicalPlan, sqlText: Option[String], startedAt: Long, finishedAt: Long): Option[LineageRun] =
    extractor.get.extract(plan, sqlText, startedAt, finishedAt)

  /**
   * 这次回调是不是「命令的就地执行」（原 `isNestedReExecution`）。
   *
   * <p><b>一次写入可能回调两次。</b>就地执行那次的 `qe.logical` 是**分析后计划里的
   * 命令节点本身**（`analyze` 不会再改它，两个字段是同一个实例）；语句级回调的
   * `logical` 是解析产物（`InsertIntoStatement`），分析后被改写成 `AppendData`，
   * 必然另一个实例。判据不看 `funcName`（Spark 内部命名，随版本变），也不看计划形状。
   *
   * <p><b>但不能像第一版那样直接跳过它。</b>`spark.sql("INSERT ...")` 不接 `collect()`
   * 时，就地执行就是唯一一次回调——跳过它 = 这次写入的血缘整个丢了。因此它现在被
   * 记为待定而不是被跳过，见 `onSuccess`。
   */
  private def isEagerCommandExecution(analyzed: LogicalPlan, qe: QueryExecution): Boolean =
    qe.logical eq analyzed

  /** 懒初始化：返回是否可用（开关打开且初始化成功）。 */
  private def configure(session: SparkSession): Boolean = {
    if (!initialized.get()) {
      listenerInit.synchronized {
        if (!initialized.get()) {
          try {
            val settings = LineageSettings.fromSession(session)
            if (settings.enabled) {
              val client: ManagementApiClient =
                if (settings.url.nonEmpty) {
                  new ManagementApiClient(settings.url, settings.token, Duration.ofSeconds(settings.timeoutSeconds))
                } else {
                  // 留空 = 复用管理 API 的客户端配置（地址、令牌、超时都从那一套来）
                  PaimonRestManagement.clientFor(session)
                }
              reporter.set(LineageReporter.forClient(client, settings.queueSize,
                LineageReporter.DEFAULT_BATCH_SIZE, settings.flushIntervalMs))
              extractor.set(new LineageExtractor(session, settings.columnLevel, settings.maxColumns))
              // `spark.sql.queryExecutionListeners` 路径没有解析器，noteWriteStatement
              // 不会触发，只能在第一次回调时挂（那条路的第一条写入仍可能丢，
              // 类注释建议优先用扩展路径）
              armShutdownHook(this, session.sparkContext)
              log.info(s"Lineage collection enabled (columnLevel=${settings.columnLevel}, "
                + s"queueSize=${settings.queueSize}, flushIntervalMs=${settings.flushIntervalMs})")
            } else {
              log.debug("Lineage collection is disabled (spark.paimon.rest.lineage.enabled=false)")
            }
          } catch {
            case NonFatal(e) =>
              log.warn(s"Failed to initialize lineage collection; it stays disabled: $e")
          } finally {
            initialized.set(true)
          }
        }
      }
    }
    extractor.get != null && reporter.get != null
  }
}

object LakehouseLineageListener {

  private val log = LoggerFactory.getLogger(classOf[LakehouseLineageListener])

  /**
   * 就地执行回调的宽限期。语句级回调（用户接了 `collect()` / 写 API 的 `withAction`）
   * 在同一条语句里紧跟着就地执行出现，间隔是毫秒级；宽限期过了还没来，就当用户
   * 不接 `collect()`（`spark.sql("INSERT ...")` 的常规形态），由定时器兜底上报。
   *
   * <p>已知的不完美：`sql()` 之后隔了很久才 `collect()`（超过宽限期）会出两条 run
   * ——兜底先报了无文本那条，语句级回调又报了带文本的。宁可偶发重复也不能丢：
   * 血缘丢边是图错了，重复只是运行历史里多一行。
   */
  private val EAGER_GRACE_MS = 2000L

  /** 兜底上报的定时器：单线程守护，只在宽限期到点时干活。 */
  private val sweeper: java.util.concurrent.ScheduledExecutorService = {
    val ex = new java.util.concurrent.ScheduledThreadPoolExecutor(1, (r: Runnable) => {
      val t = new Thread(r, "lakehouse-lineage-pending-sweeper")
      t.setDaemon(true)
      t
    })
    ex.setRemoveOnCancelPolicy(true)
    ex
  }

  /** 按 `eq` 比较的键：`LogicalPlan#equals` 是结构比较，含环计划会栈溢出（§13.7 第 4 条）。 */
  private final class IdentityKey(val ref: AnyRef) {
    override def hashCode: Int = System.identityHashCode(ref)
    override def equals(other: Any): Boolean = other match {
      case k: IdentityKey => k.ref eq ref
      case _ => false
    }
  }

  private val initialized = new AtomicBoolean(false)
  private val listenerInit = new Object

  private val extractor = new java.util.concurrent.atomic.AtomicReference[LineageExtractor]()
  private val reporter = new java.util.concurrent.atomic.AtomicReference[LineageReporter]()

  /** 扩展懒注册只做一次：`injectParser` 的工厂每次取解析器都会被调用。 */
  private val registered = new AtomicBoolean(false)

  /** 关掉上报器（测试用；正常运行时 JVM 退出由 shutdown hook 兜底，见下）。 */
  def shutdown(): Unit = {
    Option(hook.getAndSet(null)).foreach { thread =>
      try Runtime.getRuntime.removeShutdownHook(thread)
      catch {
        // JVM 已在退出中，hook 马上会自己跑；close() 幂等，跑两遍无害
        case _: IllegalStateException => ()
      }
    }
    hookArmed.set(false)
    expectedWrites.set(0)
    resolvedWrites.set(0)
    val current = reporter.getAndSet(null)
    if (current != null) {
      current.close()
    }
    extractor.set(null)
    initialized.set(false)
  }

  // ------------------------------------------------------------------ JVM 退出兜底

  /**
   * 「已解析、回调尚未送达」的写语句计数。解析（`SqlTextRegistry.record`）是同步的，
   * 回调（`QueryExecutionListener`）走异步监听总线——这对差值就是「欠着的 run 数」，
   * 退出兜底靠它区分「回调还没来」与「本来就没有写入」。
   */
  private val expectedWrites = new java.util.concurrent.atomic.AtomicLong()
  private val resolvedWrites = new java.util.concurrent.atomic.AtomicLong()

  /** 解析器在解析出**写计划**时调用（`SqlTextRegistry.record` 的顺手一笔）。 */
  private[spark] def noteWriteStatement(): Unit = {
    expectedWrites.incrementAndGet()
    log.info(s"Lineage: a write statement is parsed (expected=${expectedWrites.get()}, "
      + s"resolved=${resolvedWrites.get()}); the shutdown flush will wait for its callback")
    // 这里还拿不到监听器实例（注册也发生在第一次解析时，先后不定）；hook 等到
    // configure() 拿到 this 再挂。没开血缘（registered=false）时不挂 hook：
    // 关掉采集的部署不该在 JVM 退出时多等哪怕一毫秒。
    if (registered.get()) {
      pendingArm.set(true)
    }
  }

  /** 一条写语句的回调（成功或失败）已送达。 */
  private def noteResolved(): Unit = {
    resolvedWrites.incrementAndGet()
    log.info(s"Lineage: a write callback arrived (${resolvedWrites.get()}/${expectedWrites.get()})")
  }

  /** 回调最长等多久：正常是毫秒级，这里是「监听总线被先停掉」时的止损上限。 */
  private val HOOK_QUIESCE_MS = 5000L

  private val hookArmed = new AtomicBoolean(false)
  private val hook = new java.util.concurrent.atomic.AtomicReference[Thread]()
  private val pendingArm = new java.util.concurrent.atomic.AtomicBoolean(false)

  /**
   * JVM 退出时的兜底上报——**这是短命作业不丢血缘的唯一保证**。
   *
   * <p>上报链路全是守护线程（§6.4：不能因为上报没关就让作业退不出去），而一次写入
   * 从回调到真正发出 POST 要经过「监听总线投递 → 待定宽限期（2s）→ 攒批间隔（2s）」。
   * 语句结束到 JVM 退出比这条链短时（单元测试、`spark.sql` 一条 INSERT 就收工的
   * 脚本作业），最后一批 run 会被守护线程的死一起带走——2026-10-22 在
   * `PaimonS3Test` 上实测复现：commit 完成 3 秒后 JVM 就退了，run 从未出队。
   * 此前 sample3 那条能进来纯属退出时机凑巧。
   *
   * <p>hook 分两步：<b>先等回调送达</b>——按「已解析写语句 vs 已送达回调」的差值等
   * （正常毫秒级；监听总线先被 SparkContext.stop 打断时按 `isStopped` 止损，上限
   * {@link #HOOK_QUIESCE_MS}），JVM 会等所有 hook 跑完才停，所以这个等待是安全的；
   * <b>再把还在宽限期里的待定 run 立即入队</b>（不等语句级回调了——JVM 都在退出，
   * 没有下一条语句），最后 {@link LineageReporter#close()} 把积压发完，join 上限
   * （2×flush+5s）兜住「退不出去」的风险。发送失败仍走 send 的重试与 WARN。
   *
   * <p>挂载时机有两处：扩展路径在注册监听器时（先于一切回调，`noteWriteStatement`
   * 见到 registered=true 才会真正等着）；`spark.sql.queryExecutionListeners` 路径
   * 只能在 configure()（第一次回调）时挂——那条路的第一条写入仍可能丢（回调前
   * 没有任何同步信号可用），类注释里已经建议优先用扩展路径。
   */
  private def armShutdownHook(instance: LakehouseLineageListener, sparkContext: org.apache.spark.SparkContext): Unit = {
    if (hookArmed.compareAndSet(false, true)) {
      val thread = new Thread(new Runnable {
        override def run(): Unit = {
          if (pendingArm.getAndSet(false)) {
            val start = System.currentTimeMillis()
            val deadline = start + HOOK_QUIESCE_MS
            while (resolvedWrites.get() < expectedWrites.get()
                && System.currentTimeMillis() < deadline
                && !sparkContext.isStopped) {
              Thread.sleep(50)
            }
            log.info(s"Lineage shutdown flush: waited ${System.currentTimeMillis() - start}ms, "
              + s"expected=${expectedWrites.get()}, resolved=${resolvedWrites.get()}, "
              + s"sparkContextStopped=${sparkContext.isStopped}")
          }
          val pendingCount = instance.pendings.size()
          instance.pendings.values().forEach { pending =>
            pending.task.cancel(false)
            Option(reporter.get).foreach(_.offer(pending.run))
          }
          instance.pendings.clear()
          Option(reporter.get).foreach { r =>
            r.close()
            log.info(s"Lineage shutdown flush: $pendingCount pending run(s) flushed, "
              + s"reporter closed (offered=${r.offeredRuns()}, sent=${r.sentRuns()}, "
              + s"failed=${r.failedRuns()}, dropped=${r.droppedRuns()})")
          }
        }
      }, "lakehouse-lineage-shutdown-flush")
      Runtime.getRuntime.addShutdownHook(thread)
      hook.set(thread)
    }
  }

  /** 上报器是否已经起来（测试用）。 */
  def reporterOrNull: LineageReporter = reporter.get()

  /**
   * 扩展里的懒注册（§10.2 的第二种安装方式）。
   *
   * <p><b>调用时机是「会话第一次解析 SQL」，不是 `injectParser` 的工厂。</b>
   * 那个工厂由 `BaseSessionStateBuilder#sqlParser` 调用，也就是 `sessionState` 正在构建的
   * 时刻；而 `SparkSession#listenerManager` 本身就要读 `sessionState`，在工厂里注册会形成
   * `sessionState → buildParser → 工厂 → listenerManager → sessionState` 的环，
   * 表现为 `StackOverflowError`（栈顶落在 `ClassLoader.defineClass1` 上，极具误导性）。
   * 因此改由 `AbstractLakehouseRestSqlParser#armListener` 在第一次 `parsePlan` 时调用本方法
   * ——那时会话状态已经建好，`listenerManager` 才拿得到（§13.7 第 6 条）。
   *
   * <p>代价：**纯 DataFrame 作业不会被自动挂载**（它一次 `parsePlan` 都不走）。那种场景用
   * `spark.sql.queryExecutionListeners` 那条路——设计稿本来就把它列为推荐方式（§10.2）。
   *
   * <p><b>配置只读 `SparkConf`。</b>注册仍可能发生在会话状态构建的尾巴上（第一条语句之前），
   * 读 SQLConf 不安全；`enabled=false` 时**什么都不做**——监听器本身有成本，关掉就要真的
   * 不挂上去。见 `LineageSettings` 类注释。
   */
  def registerIfEnabled(session: SparkSession): Unit = {
    if (registered.get()) {
      return
    }
    listenerInit.synchronized {
      if (registered.get()) {
        return
      }
      try {
        if (LineageSettings.enabledAtRegistration(session.sparkContext.getConf)) {
          val listener = new LakehouseLineageListener
          session.listenerManager.register(listener)
          // 退出兜底必须在第一次回调之前挂上——回调本身可能赶不上 JVM 退出
          // （2026-10-22 实测：回调没送达，监听器连懒初始化都没走到）。
          // noteWriteStatement 只是把 pendingArm 置位；hook 在这里才真正存在。
          armShutdownHook(listener, session.sparkContext)
          log.info("Registered LakehouseLineageListener through spark.sql.extensions")
        }
      } catch {
        case NonFatal(e) => log.warn(s"Failed to register the lineage listener: $e")
      } finally {
        registered.set(true)
      }
    }
  }
}
