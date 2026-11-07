package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.SensitivityScheduleEntity;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityScheduleRepository;
import io.github.melin.lakehouse.rest.dto.SensitivityDtos.ScanRequest;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanTrigger;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时扫描的节拍：读到期项 → 抢占 → 发起扫描（设计见
 * {@code docs/sensitive-data-design.md} §5.6.2 / §5.6.3）。
 *
 * <p><b>「只触发一次」由两件事共同保证，缺一不可：</b>
 * <ol>
 *   <li><b>租约</b>（{@link SchedulerLease#CHANNEL_SENSITIVITY_SCAN}）：多副本下只有一台
 *       实例读调度表。它只降噪，不保正确——租约到期、进程假死都能让它短暂失效；</li>
 *   <li><b>{@code next_run_at} 的条件更新</b>（{@link SensitivityScheduleRepository#claim}）：
 *       只有当库里那个值仍是我读到的那个时，UPDATE 才命中。它是正确性的那一道，
 *       也覆盖「租约在扫描期间过期、另一台接手」这种租约自己兜不住的情形。</li>
 * </ol>
 * 反过来只靠前面那一道，一次慢扫描撞上租约到期就会双触发；只靠后面那一道，
 * 每台实例每分钟都去读一遍调度表（正确但浪费）。设计稿 §5.6.3 的两行「调度单写者」
 * 与「到点抢占」说的就是这两件事。
 *
 * <p><b>为什么扫描不在调度线程里跑。</b>Spring Boot 默认只有**一个**调度线程，而
 * {@code OptimizationDispatcher} 的出批与提交节拍也跑在它上面。一次 catalog 级扫描
 * 是分钟级的，直接在这里跑会把表优化的提交节拍一起按停——现象是「配了敏感识别之后，
 * 优化批次开始排队」，而没有任何一处日志指向这两件事的关联。因此本类把扫描丢给
 * 自己的执行器（{@link #workers}），调度线程只做「读表 + 抢占 + 入队」。
 *
 * <p><b>执行器是进程内的，队列满时丢弃并记 WARN。</b>丢弃是刻意的：<b>不要重试</b>。
 * 那一条调度项的 {@code next_run_at} 在抢占时就已推进到下一个 cron 点，
 * 「这一次没跑起来」的正确语义是「跳到下一次」，而不是「每分钟重试这个注定失败的扫描」。
 * 队列容量足够大（{@value #QUEUE_CAPACITY}）：真被打满说明到期项远多于执行能力，
 * 那时候限流已经是结论，再排队只是把内存也一起赔进去。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitivityScheduleDispatcher {

    /**
     * 调度器发起扫描时写进审计列的主体名。
     *
     * <p>没有请求线程时 {@code RequestContext.principal()} 会给出 {@code anonymous}——
     * 那会让「凌晨三点谁读了明文」的答案是「匿名」，而这恰恰是审计要回答的问题。
     * 记成 {@code system:sensitivity-scheduler} 才是事实：这是一次调度触发的读取，
     * 不是某个人。
     */
    public static final String SYSTEM_PRINCIPAL = "system:sensitivity-scheduler";

    /** 队列容量：远大于「一分钟内可能到期的调度项数」，满了就是达到了执行能力上限。 */
    private static final int QUEUE_CAPACITY = 1000;

    private final SensitivityScheduleRepository repository;
    private final SensitivityScheduleService schedules;
    private final SensitivityScanService scans;
    private final RestServerProperties properties;
    private final SchedulerLease lease;
    private final Clock clock;

    /**
     * 扫描执行器：线程数取 A 路线的并发闸门（默认 2），超过它只会让扫描互相排队。
     *
     * <p><b>不能写成 {@code = newWorkers()} 的字段初始化。</b>字段初始化发生在构造器体之前，
     * 而 Lombok 生成的构造器这时还没把 {@code properties} 赋进来——{@code newWorkers()}
     * 一读它就是 NPE，而栈会指向 {@code RestServerProperties.getSensitivity()}，
     * 看起来像配置缺了。改成 {@code @PostConstruct}：那时注入已经完成。
     */
    private ThreadPoolExecutor workers;

    @PostConstruct
    void initWorkers() {
        this.workers = newWorkers();
    }

    // ------------------------------------------------------------------ 节拍

    /**
     * 调度节拍（默认 1 分钟，{@code lakehouse.rest.sensitivity.schedule.tick}）。
     *
     * <p><b>与表优化共用一个开关</b>：{@code lakehouse.rest.optimization.scheduler-enabled=false}
     * 时这里整拍早退。「服务端有没有定时任务」该是一个开关，不是两个
     * （§5.6.3 的「节拍来源」那一行）。
     */
    @Scheduled(fixedDelayString = "${lakehouse.rest.sensitivity.schedule.tick:PT1M}",
            initialDelayString = "${lakehouse.rest.sensitivity.schedule.tick:PT1M}")
    public void tick() {
        if (!properties.getOptimization().isSchedulerEnabled()) {
            return;
        }
        // 抢不到就整拍早退：另一台实例正在扫。不是错误，连 WARN 都不该有
        if (!lease.tryAcquire(SchedulerLease.CHANNEL_SENSITIVITY_SCAN)) {
            log.debug("skip sensitivity schedule tick: another instance holds {}",
                    SchedulerLease.CHANNEL_SENSITIVITY_SCAN);
            return;
        }
        try {
            // 抢占与入队留在调度线程（快），真正的扫描在 workers 上跑（慢）
            for (SensitivityScheduleEntity due : claimDue()) {
                submit(due);
            }
        } catch (RuntimeException exception) {
            log.error("sensitivity schedule tick failed", exception);
        }
    }

    // ------------------------------------------------------------------ 抢占

    /**
     * 本拍到期的调度项，**且已经被这一拍抢占**。
     *
     * <p>返回值只含抢占成功的那些：抢占失败意味着另一台实例（或同一台的上一拍）
     * 已经推进过，这一拍必须什么都不做——否则就是「同一分钟触发两次」。
     *
     * <p>公开出来是为了让验收用例能确定性地驱动它：{@code tick()} 里的异步边界
     * 只影响「什么时候跑扫描」，不影响「谁抢到了这一拍」。
     */
    public List<SensitivityScheduleEntity> claimDue() {
        long now = clock.millis();
        backfillNextRun(now);
        List<SensitivityScheduleEntity> claimed = new ArrayList<>();
        for (SensitivityScheduleEntity due
                : repository.findTop50ByEnabledFlagTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now)) {
            Long expected = due.getNextRunAt();
            if (expected == null) {
                // 理论上走不到：backfill 刚补过。留着这一条是为了不让 null 参与 CAS
                // （JPQL 里 `= :expectedNext` 对 null 恒不成立，那会变成一个静默的空转）
                continue;
            }
            // 下一次从「现在」算而不是从 expected 算：一次延迟的节拍（进程重启、GC 停顿）
            // 从 expected 算会得到「过去某刻」，于是下一拍又立刻到期，变成追赶式连发
            long next;
            try {
                next = schedules.nextRunAfter(due.getCron(), now);
            } catch (RuntimeException exception) {
                // 库里的 cron 认不出（被手改过）。跳过它，别让一条坏行挡住整张表
                log.warn("sensitivity schedule {} has an unusable cron \"{}\": {}",
                        due.getName(), due.getCron(), exception.getMessage());
                continue;
            }
            if (repository.claim(due.getId(), expected, now, next) == 0) {
                log.debug("sensitivity schedule {} was claimed by another instance", due.getName());
                continue;
            }
            // claim 是 JPQL 批量更新，不经持久化上下文，因此这里手工更新内存里的那份，
            // 免得后面读到一个过期的 nextRunAt（日志与事件里会出现旧值）
            due.setLastRunAt(now);
            due.setNextRunAt(next);
            claimed.add(due);
        }
        return claimed;
    }

    /**
     * 把「还没算过下次运行时刻」的启用项补上。
     *
     * <p>这类行的来源只有两种：库被手工改过（运维想「先停一下再开」却清空了字段），
     * 以及本表刚建、历史数据从别处导入。它不该被当成「立刻到期」——一条每天 3 点的
     * 调度项在补算时被立刻触发一次全量扫描，是用户完全没预期的一次明文读。
     */
    private void backfillNextRun(long now) {
        for (SensitivityScheduleEntity entity : repository.findByEnabledFlagTrueAndNextRunAtIsNull()) {
            try {
                long next = schedules.nextRunAfter(entity.getCron(), now);
                repository.scheduleNextRun(entity.getId(), next, now);
                log.info("sensitivity schedule {} had no next_run_at; scheduled at {}", entity.getName(), next);
            } catch (RuntimeException exception) {
                log.warn("cannot compute the next run of sensitivity schedule {}: {}",
                        entity.getName(), exception.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ 执行

    /** 入队；队列满就丢弃并记 WARN（语义见类注释：这一次跳到下一个 cron 点）。 */
    private void submit(SensitivityScheduleEntity schedule) {
        try {
            workers.execute(() -> runScheduled(schedule));
        } catch (RejectedExecutionException rejected) {
            log.warn("sensitivity schedule {} fired but the scan queue is full ({} pending);"
                    + " this occurrence is skipped and the next cron point will be used instead",
                    schedule.getName(), workers.getQueue().size());
        }
    }

    /**
     * 跑一条调度项：把调度项翻译成一次扫描，交给扫描服务。
     *
     * <p><b>{@code start} 的异常一律吞掉并记日志。</b>一次调度失败的原因通常是配置层面
     * 长期存在的问题（catalog 被删、执行集群停用、同表已有扫描在跑），把它冒泡到
     * 调度线程只会得到一张每分钟一条的栈；而这里已经没有办法「重试对这一次」——
     * {@code next_run_at} 早就推进了。
     */
    void runScheduled(SensitivityScheduleEntity schedule) {
        RequestContext.setPrincipal(SYSTEM_PRINCIPAL);
        try {
            ScanRequest request = new ScanRequest(schedule.getCatalogName(),
                    SensitivityScheduleService.scopeOf(schedule).name(),
                    schedule.getDatabaseName(), schedule.getTableName(), schedule.getMode(),
                    schedule.getExecution(), schedule.getSampleSize(), ScanTrigger.SCHEDULE.name());
            scans.start(request, schedule.getName());
            log.info("sensitivity schedule {} triggered a {} scan of {}",
                    schedule.getName(), schedule.getMode(), schedule.getCatalogName());
        } catch (RuntimeException exception) {
            log.warn("sensitivity schedule {} could not start its scan: {}",
                    schedule.getName(), exception.getMessage());
        } finally {
            // ThreadLocal 必须清：执行器的线程是复用的，留着会让下一次扫描的
            // triggered_by 继承上一条调度项的主体名（反过来也说得通：都是 system，
            // 但「谁来清理」这件事不能靠巧合）
            RequestContext.clear();
        }
    }

    /** 停机时停掉执行器：正在跑的扫描会被中断（`scan was left RUNNING` 由启动清理收尾）。 */
    @PreDestroy
    void shutdownWorkers() {
        workers.shutdownNow();
    }

    private ThreadPoolExecutor newWorkers() {
        int threads = Math.max(1, properties.getSensitivity().getScan().getMaxConcurrent());
        AtomicInteger counter = new AtomicInteger();
        return new ThreadPoolExecutor(threads, threads, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "sensitivity-scan-" + counter.incrementAndGet());
                    // 守护线程：停机时不该为了等一次扫描而拖住 JVM 退出
                    thread.setDaemon(true);
                    return thread;
                });
    }
}
