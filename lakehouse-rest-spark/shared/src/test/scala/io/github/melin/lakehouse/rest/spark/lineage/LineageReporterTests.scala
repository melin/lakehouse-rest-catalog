package io.github.melin.lakehouse.rest.spark.lineage

import org.junit.jupiter.api.Assertions._
import org.junit.jupiter.api.Test

import java.util.concurrent.{CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.JavaConverters._

/**
 * 上报器的队列行为（§6.4）。
 *
 * <p>这里不碰 HTTP：`LineageIngestor` 只有一个方法，正是为了把「队列怎么攒批、满了怎么办、
 * 失败了怎么办」与「怎么发出去」分开测。真正的报文形状由 `ManagementApiClientTests` 与
 * 服务端的 `LineageApiTests` 各管一段。
 */
class LineageReporterTests {

  @Test
  def closeFlushesEverythingThatWasAccepted(): Unit = {
    val received = new CopyOnWriteArrayList[java.util.List[LineageRun]]()
    val reporter = new LineageReporter(new LineageIngestor {
      override def ingest(runs: java.util.List[LineageRun]): Unit = received.add(runs)
    }, 100, 50, 20)

    (1 to 10).foreach(i => assertTrue(reporter.offer(run("run-" + i))))
    reporter.close()

    assertEquals(10, reporter.sentRuns(), "关闭时要把积压发完，不能留着")
    assertEquals(0, reporter.droppedRuns())
    assertEquals(10, received.asScala.flatMap(_.asScala).size)
    assertEquals(10, received.asScala.flatMap(_.asScala).map(_.runId).toSet.size)
  }

  @Test
  def aFailingIngestorIsRetriedTwiceAndThenCounted(): Unit = {
    val attempts = new AtomicInteger()
    val reporter = new LineageReporter(new LineageIngestor {
      override def ingest(runs: java.util.List[LineageRun]): Unit = {
        attempts.incrementAndGet()
        throw new IllegalStateException("server is down")
      }
    }, 10, 5, 20)

    assertTrue(reporter.offer(run("run-1")))
    reporter.close()

    assertEquals(1, reporter.failedRuns(), "重试耗尽后要计数——漏报必须能被发现")
    assertEquals(3, attempts.get(), "首次 + 2 次重试")
    assertEquals(0, reporter.sentRuns())
  }

  @Test
  def aFullQueueDropsInsteadOfBlockingTheDriver(): Unit = {
    val started = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val reporter = new LineageReporter(new LineageIngestor {
      override def ingest(runs: java.util.List[LineageRun]): Unit = {
        started.countDown()
        release.await(10, TimeUnit.SECONDS)
      }
    }, 1, 1, 10)

    assertTrue(reporter.offer(run("run-1")))
    assertTrue(started.await(10, TimeUnit.SECONDS), "上报线程应该已经开始处理第一条")

    // 容量 1：第二条进队，第三条必须被丢弃——丢弃而不是阻塞，是「血缘不能拖慢作业」的底线
    assertTrue(reporter.offer(run("run-2")))
    assertFalse(reporter.offer(run("run-3")))
    assertEquals(1, reporter.droppedRuns())

    release.countDown()
    reporter.close()
    assertEquals(2, reporter.sentRuns())
  }

  @Test
  def offerAfterCloseIsRejectedQuietly(): Unit = {
    val reporter = new LineageReporter(new LineageIngestor {
      override def ingest(runs: java.util.List[LineageRun]): Unit = ()
    }, 10, 5, 10)
    reporter.close()

    assertFalse(reporter.offer(run("run-1")))
    assertEquals(0, reporter.sentRuns())
  }

  private def run(id: String): LineageRun =
    new LineageRun(id, "SPARK", "3.5.9", "INSERT", "APPEND", null, null, 1L, 2L, "BATCH",
      List(new LineageRun.Target("paimon.db.t", null)).asJava,
      List(new LineageRun.Source("paimon.db.s", "TABLE")).asJava,
      Map.empty[String, Object].asJava)
}
