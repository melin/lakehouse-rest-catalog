package io.github.melin.lakehouse.rest.spark.lineage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 异步上报器：一个内存队列 + 一个守护线程（§6.4）。
 *
 * <pre>
 * onSuccess（driver 线程）                    reporter 线程（单线程，守护）
 *     │  offer(run)                              │
 *     ├── 队列未满 → 立即返回，作业继续           ├── 批量取（最多 batchSize，或攒 flushInterval）
 *     └── 队列满   → 丢弃 + dropped 计数 + WARN   ├── POST（超时由 HTTP 客户端给）
 *                                                ├── 失败 → 指数退避重试 2 次
 *                                                └── 最终失败 → failed 计数 + WARN，丢弃
 * </pre>
 *
 * <p><b>三条硬约束，都体现在实现里：</b>
 *
 * <ol>
 *   <li>{@link #offer} 里<b>只做入队</b>，绝不做网络 IO。它在作业收尾路径上，
 *       同步 POST 等于把超时加到每个 {@code spark.sql} 的尾部延迟上；</li>
 *   <li><b>绝不抛异常给调用方</b>。任何路径上的异常都降级为计数 + WARN——监听器抛异常
 *       会让 {@code spark.sql} 报一个与 SQL 无关的错，用户会去查 SQL（§6.6）；</li>
 *   <li><b>队列满就丢</b>。血缘是「可以缺一条，不能拖慢作业」的数据。丢弃计数是
 *       「漏报可被发现」的唯一手段，因此它必须能被读到（{@link #droppedRuns()}）。</li>
 * </ol>
 *
 * <p><b>为什么不合并同 key 的 run。</b>合并在客户端做，服务端就再也看不出「这条边被跑了 2 次」，
 * 而 {@code run_count} 的异常分布正是漏报的旁证。攒批只按条数与时间，不按内容。
 */
public final class LineageReporter implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LineageReporter.class);

    /** 单批上限。与服务端 {@code max-runs-per-batch} 的默认值一致，超了会被 400。 */
    public static final int DEFAULT_BATCH_SIZE = 50;

    /** 攒批间隔：一条 run 最多等这么久。 */
    public static final long DEFAULT_FLUSH_INTERVAL_MS = 2000L;

    /** 队列长度。 */
    public static final int DEFAULT_QUEUE_SIZE = 500;

    /** 重试次数（不含首次）。 */
    private static final int MAX_RETRIES = 2;

    private static final long RETRY_BACKOFF_MS = 200L;

    private final BlockingQueue<LineageRun> queue;
    private final LineageIngestor ingestor;
    private final int batchSize;
    private final long flushIntervalMs;
    private final Thread worker;

    private final AtomicLong offered = new AtomicLong();
    private final AtomicLong sent = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();

    /** 丢弃日志的节流：每次丢弃都打一行会把 driver 日志刷满。 */
    private final AtomicLong dropWarnings = new AtomicLong();

    private volatile boolean running = true;

    public LineageReporter(LineageIngestor ingestor) {
        this(ingestor, DEFAULT_QUEUE_SIZE, DEFAULT_BATCH_SIZE, DEFAULT_FLUSH_INTERVAL_MS);
    }

    public LineageReporter(LineageIngestor ingestor, int queueSize, int batchSize, long flushIntervalMs) {
        if (ingestor == null) {
            throw new IllegalArgumentException("ingestor is required");
        }
        this.ingestor = ingestor;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueSize));
        this.batchSize = Math.min(Math.max(1, batchSize), DEFAULT_BATCH_SIZE);
        this.flushIntervalMs = Math.max(1L, flushIntervalMs);
        this.worker = new Thread(this::drainLoop, "lakehouse-lineage-reporter");
        // 守护线程：不能因为上报器没关就让作业的 JVM 退不出去
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 直接挂在管理 API 客户端上。
     *
     * <p>放在这里而不是让 Scala 侧自己写 lambda：{@code ingestLineage} 与
     * {@link LineageIngestor#ingest} 是同一个形状，一处方法引用就够；但那个方法引用
     * 在 Scala 里要写成 {@code client.ingestLineage _}，2.12/2.13 的下划线语义还不一样。
     */
    public static LineageReporter forClient(
            io.github.melin.lakehouse.rest.spark.client.ManagementApiClient client,
            int queueSize, int batchSize, long flushIntervalMs) {
        return new LineageReporter(client::ingestLineage, queueSize, batchSize, flushIntervalMs);
    }

    /**
     * 入队。永不阻塞、永不抛异常。
     *
     * @return 是否入队成功；{@code false} 表示队列已满、这条被丢弃
     */
    public boolean offer(LineageRun run) {
        if (run == null || !running) {
            return false;
        }
        offered.incrementAndGet();
        if (queue.offer(run)) {
            return true;
        }
        long total = dropped.incrementAndGet();
        // 第 1 次、之后每 100 次打一行：既留下痕迹，又不至于刷屏
        if (dropWarnings.incrementAndGet() == 1 || total % 100 == 0) {
            LOG.warn("Lineage queue is full ({} entries); dropped {} run(s) so far. "
                    + "Lineage is best-effort: the job itself is unaffected. "
                    + "Raise spark.lakehouse.rest.lineage.queue-size or flush-interval-ms if this repeats.",
                    queue.size() + batchSize, total);
        }
        return false;
    }

    /** 已成功上报的 run 数。 */
    public long sentRuns() {
        return sent.get();
    }

    /** 因队列满被丢弃的 run 数。 */
    public long droppedRuns() {
        return dropped.get();
    }

    /** 重试耗尽后仍失败的 run 数（服务端不可达、令牌过期、报文被拒）。 */
    public long failedRuns() {
        return failed.get();
    }

    /** 已入队总数。 */
    public long offeredRuns() {
        return offered.get();
    }

    /** 已经发出去的批次数（含失败的批）。 */
    public long batches() {
        return batches.get();
    }

    /** 队列里还积压多少条。 */
    public int backlog() {
        return queue.size();
    }

    /**
     * 停止接收并尽力把积压发完。
     *
     * <p>最多等 {@code 2 * flushInterval + 5s}：关闭发生在作业收尾（会话停止、
     * 测试结束），此处多等几百毫秒换「最后一批不丢」是值得的，但不能无限等——
     * 那会变成一个退不出去的 JVM。
     */
    @Override
    public void close() {
        running = false;
        worker.interrupt();
        try {
            worker.join(flushIntervalMs * 2 + 5000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ 内部

    private void drainLoop() {
        List<LineageRun> batch = new ArrayList<>(batchSize);
        while (true) {
            try {
                // 先等第一条：没数据时不空转
                LineageRun first = queue.poll(flushIntervalMs, TimeUnit.MILLISECONDS);
                if (first == null) {
                    if (!running && queue.isEmpty()) {
                        return;
                    }
                    continue;
                }
                batch.clear();
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                send(batch);
            } catch (InterruptedException e) {
                // close() 的中断只是「叫醒 poll」，不是「放弃重试」
                if (!running) {
                    break;
                }
            } catch (Throwable t) {
                // 兜底：这个线程死了就再也不会有人上报，而没人会发现
                LOG.warn("Lineage reporter loop hit an unexpected error; continuing", t);
            }
        }
        // 关机路径：**必须先清掉中断位**再 flush。否则 send() 里的退避 sleep 会立刻抛
        // InterruptedException，一条 run 变成「只试了一次就放弃」——重试形同虚设。
        Thread.interrupted();
        flushRemaining();
    }

    /**
     * 关闭时的最后一批：仍然走 {@link #send}（含重试），但总共只在这里等一次。
     *
     * <p>不等是不可能的——最后一批往往正是最有价值的那条（作业的收尾写入）。
     * 等待上限由 {@link #close()} 的 join 超时兜住。
     */
    private void flushRemaining() {
        List<LineageRun> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        while (!batch.isEmpty()) {
            send(batch);
            batch.clear();
            queue.drainTo(batch, batchSize);
        }
    }

    private void send(List<LineageRun> batch) {
        batches.incrementAndGet();
        Exception last = null;
        long backoff = RETRY_BACKOFF_MS;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            // close() 的 interrupt 只负责叫醒 poll，不该毒化发送：进入尝试前清掉残留的
            // 中断位。不清的话，HttpClient.send 看到中断位立刻抛 InterruptedException，
            // 而 sleep 又刻意保留中断位——三次重试全部秒废（2026-10-22 在退出兜底上
            // 实测复现：failed=1，last="interrupted while calling the management API"，
            // 最后一批 run 在「close 与 offer 赛跑赢了」的假象下被静默丢弃）。
            Thread.interrupted();
            try {
                ingestor.ingest(new ArrayList<>(batch));
                sent.addAndGet(batch.size());
                return;
            } catch (Exception e) {
                last = e;
                if (attempt < MAX_RETRIES) {
                    // 退避期间被中断也接着试：那个中断是 close() 用来叫醒 poll 的，
                    // 不代表「这条 run 不要了」。早先在这里 break，导致重试实际只发生一次。
                    sleep(backoff);
                    backoff *= 2;
                }
            }
        }
        failed.addAndGet(batch.size());
        LOG.warn("Failed to report {} lineage run(s) after {} attempt(s); dropping them. "
                        + "The job is unaffected. First target: {}",
                batch.size(), MAX_RETRIES + 1,
                batch.isEmpty() ? "-" : batch.get(0).targets().toString(),
                last);
    }

    /**
     * 退避等待，**吞掉**中断。
     *
     * <p>本线程唯一的中断来源是 {@link #close()}，它只是想让 {@code poll} 立刻返回，
     * 不是想砍掉正在上报的这一批。等待上限由 {@link #close()} 的 join 超时兜住，
     * 所以这里可以放心不响应中断。
     */
    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            // 保留中断位：后面的 poll 会立刻返回，循环随即看到 running=false 退出
            Thread.currentThread().interrupt();
        }
    }
}
