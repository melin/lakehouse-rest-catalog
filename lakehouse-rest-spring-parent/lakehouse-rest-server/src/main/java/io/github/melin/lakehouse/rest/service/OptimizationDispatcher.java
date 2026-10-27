package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.SubmissionRequest;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.Submitted;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Job;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 优化批次的调度节拍：出批、提交、回收、跟踪。
 *
 * <p>三个动作拆成两个节拍，间隔不同：
 * <ul>
 *   <li><b>扫描</b>（{@code lakehouse.rest.optimization.scan-interval}，默认 1 分钟）：
 *       把到期策略攒成批次落库；
 *   <li><b>提交与跟踪</b>（{@code dispatch-interval}，默认 10 秒）：把 {@code PENDING}
 *       的批次发出去、回收卡住的提交、问 {@code RUNNING} 的批次跑到哪了。
 * </ul>
 * 拆开的原因是它们的时效性要求不同：出批是分钟级的事，而「提交」与「状态刷新」
 * 决定用户看到多久的延迟——一个批次落库后等 60 秒才提交，控制台上就会出现
 * 「排队中」卡住一分钟的观感。
 *
 * <p><b>多副本下谁跑哪一段。</b>出批与提交都有外部副作用（推游标、建 k8s Job），
 * 因此挂在自己的租约通道上（{@link SchedulerLease}）：抢不到就整拍早退，只记 debug。
 * <b>跟踪不占租约</b>：{@code pollRunning} 的每一处写都是条件更新，多台机器同时 poll
 * 只会多几次 GET，不会写出第二个终态。把它留在租约里反而有害——持有者挂掉后要等
 * 一个 TTL（默认 60 秒）别人才能接手收尾，而放开之后接管只需一拍（10 秒）。
 *
 * <p><b>逐条独立失败。</b>一个批次提交失败不影响同一节拍里其它批次：
 * 循环里每个批次各自 try/catch，失败写进批次自己的 {@code error_message}。
 * 反过来（整节拍失败退出）会让「有一个坏批次」变成「所有批次都停摆」。
 *
 * <p><b>不可派发的 catalog 只跳过、不判失败。</b>没配置执行集群、被停用、
 * 密文解不开——这些时候 {@code PENDING} 的批次留在队列里（下一拍再看），
 * 而不是被置成 {@code FAILED}。判失败会让「填完配置」这个动作之后还得手工重试一遍，
 * 而用户的预期显然是「配好就自动开始」。{@link #submitPending} 与
 * {@link OptimizationBatchService#scanAndCreateBatches()} 两处都按这条走。
 *
 * <p>两个 {@code @Scheduled} 方法默认跑在同一个单线程调度器上，因此互相串行。
 * 这是有意的：扫描本身可能出好几个批次，与提交并发执行没有收益，而串行让
 * 「批次按创建顺序提交」成为默认行为，排查时看到的顺序与落库顺序一致。
 */
@Component
@RequiredArgsConstructor
public class OptimizationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(OptimizationDispatcher.class);

    private final OptimizationJobRepository jobRepository;
    private final OptimizationJobService jobService;
    private final OptimizationBatchService batchService;
    private final OptimizationJobSubmitter submitter;
    private final ExecutionClusterService clusters;
    private final RestServerProperties properties;
    private final SchedulerLease lease;
    private final Clock clock;

    /** 出批节拍：扫描到期策略，攒批落库。 */
    @Scheduled(fixedDelayString = "${lakehouse.rest.optimization.scan-interval:PT1M}",
            initialDelayString = "${lakehouse.rest.optimization.scan-interval:PT1M}")
    public void scan() {
        if (!properties.getOptimization().isSchedulerEnabled()) {
            return;
        }
        // 抢不到就整拍早退：另一台机器正在扫。不是错误，连 WARN 都不该有——
        // 多副本的常态就是「一半的拍次不由我跑」
        if (!lease.tryAcquire(SchedulerLease.CHANNEL_SCAN)) {
            log.debug("skip optimization scan: another instance holds {}", SchedulerLease.CHANNEL_SCAN);
            return;
        }
        try {
            batchService.scanAndCreateBatches();
        } catch (RuntimeException exception) {
            log.error("optimization scan failed", exception);
        }
    }

    /** 提交、回收与跟踪节拍。 */
    @Scheduled(fixedDelayString = "${lakehouse.rest.optimization.dispatch-interval:PT10S}",
            initialDelayString = "${lakehouse.rest.optimization.dispatch-interval:PT10S}")
    public void dispatch() {
        if (!properties.getOptimization().isSchedulerEnabled()) {
            return;
        }
        if (lease.tryAcquire(SchedulerLease.CHANNEL_DISPATCH)) {
            submitPending();
            reapStaleSubmissions();
        } else {
            log.debug("skip optimization submit: another instance holds {}", SchedulerLease.CHANNEL_DISPATCH);
        }
        // 跟踪不占租约：写全是条件更新，并发安全；放开之后接管只差一拍
        pollRunning();
    }

    /** 把 PENDING 批次发到各自 catalog 的执行集群。 */
    public void submitPending() {
        // 本拍内每个 catalog 的「已经放出去几个」与「上限」。库里数一次之后就地累加，
        // 避免每提交一个批次就再查两次库；上限为 -1 表示这个 catalog 不可派发。
        Map<String, Long> dispatched = new HashMap<>();
        Map<String, Long> limits = new HashMap<>();
        for (OptimizationJobEntity job : jobRepository.findTop50ByStatusOrderByCreatedAtAsc(Job.PENDING.name())) {
            String catalogId = job.getCatalogId();
            long limit = limits.computeIfAbsent(catalogId, key -> {
                // 不可派发（未配置 / 停用 / 验证失败）：**只跳过，不判失败**。
                // 判失败会让「填完配置」之后还得手工重试一遍，而用户的预期是配好就自动开始。
                if (!clusters.dispatchable(key)) {
                    log.debug("catalog {} has no dispatchable execution cluster, its pending batches stay queued", key);
                    return -1L;
                }
                return (long) clusters.maxConcurrentJobs(key);
            });
            if (limit < 0) {
                continue;
            }
            // 执行侧闸门：这个 catalog 同时在跑的批次不超过它自己的上限，
            // 放不出去的 PENDING 留在队列里等下一拍。
            //
            // 与出批侧那道检查（{@code OptimizationBatchCreator}）数的是不同的口径，两道都要有：
            // 出批侧数「已承诺的批次」（含排队中的），决定**还该不该造新批次**；这里数
            // 「已经放出去的」，决定**造好的批次放不放行**。少了这一道，手动「立即优化」
            // 与重试就能绕过上限——它们直接建批次，压根不经过出批那条路，
            // 而这条上限是给集群留余量的（每个批次一个 Spark 驱动），绕过它等于没有上限。
            //
            // 提交顺序按创建时间，先到先跑：手动批次会排在已有队列后面，
            // 但不会插队——插队意味着调度批次可能永远等不到执行窗口。
            long already = dispatched.computeIfAbsent(catalogId,
                    key -> jobRepository.countByCatalogIdAndStatusIn(key, Job.dispatchedNames()));
            if (already >= limit) {
                log.debug("optimization concurrency limit ({}) of catalog {} reached, its pending batches stay queued",
                        limit, catalogId);
                continue;
            }
            // 抢占同时写提交租约，拿到的是本次提交的代数（fencing）
            int attempt = jobService.claimForSubmission(job.getId());
            if (attempt <= 0) {
                continue;
            }
            try {
                Submitted submitted = submitter.submit(
                        new SubmissionRequest(job.getId(), catalogId, job.getCallbackToken()));
                if (!jobService.markSubmitted(job.getId(), submitted, attempt)) {
                    // 回写被丢弃：租约在提交期间过期并被别的实例回收了。那个实例会重投，
                    // 而重投命中同名 Job（幂等），所以这里什么都不用做
                    log.warn("optimization batch {} submitted but the claim was lost, another instance takes over",
                            job.getId());
                    continue;
                }
                dispatched.merge(catalogId, 1L, Long::sum);
                log.info("optimization batch {} submitted to {} as {}/{}",
                        job.getId(), submitter.name(), submitted.namespace(), submitted.resource());
            } catch (RuntimeException exception) {
                // 提交失败是终态失败而不是重试：重试要由用户显式发起（可能配置错了镜像或权限，
                // 盲目重投只会反复失败并把执行环境的错误日志刷满）。
                jobService.markSubmitFailed(job.getId(), "提交优化任务失败：" + exception.getMessage()
                        + "（提交方式：" + submitter.name() + "）", attempt);
                log.error("cannot submit optimization batch {}", job.getId(), exception);
            }
        }
    }

    /**
     * 回收「提交中」的孤儿批次。
     *
     * <p>与 {@link #submitPending} 同一拍、同一租约：回收会改状态（{@code SUBMITTING → PENDING}），
     * 而下一拍提交会读同一批行。放在提交之后跑，是为了让「刚提交完、租约还没到期」的批次
     * 不被误回收（回收语句自己也有 {@code claim_expires_at < now} 兜着）。
     */
    void reapStaleSubmissions() {
        try {
            int reaped = jobService.reapStaleSubmissions();
            if (reaped > 0) {
                log.warn("reclaimed {} optimization batch(es) stuck in SUBMITTING", reaped);
            }
        } catch (RuntimeException exception) {
            // 回收失败不该影响下一拍：坏掉的是「某个批次卡住」，不是整条调度
            log.error("cannot reap stale optimization submissions", exception);
        }
    }

    /** 问执行环境跑到哪了，并在终态或超时时收尾。 */
    public void pollRunning() {
        for (OptimizationJobEntity job : jobRepository.findTop50ByStatusOrderByCreatedAtAsc(Job.RUNNING.name())) {
            long now = clock.millis();
            JobState state;
            try {
                state = submitter.state(job.getCatalogId(), job.getNamespace(), job.getResource());
            } catch (RuntimeException exception) {
                // 查询失败不回写状态：一次 API 抖动不该把一个正在跑的批次判成失败。
                // 集群不可达、凭据过期、执行集群被删都属于这一类——它们的正确处置是
                // 「等它好起来」或「由 deadline 收尾」，不是把批次判死。
                log.warn("cannot query state of optimization batch {} ({}/{}): {}",
                        job.getId(), job.getNamespace(), job.getResource(), exception.getMessage());
                state = JobState.UNKNOWN;
            }
            if (state == JobState.SUCCEEDED || state == JobState.FAILED) {
                jobService.finishFromSubmitter(job.getId(), state,
                        state == JobState.FAILED ? "执行侧任务失败（" + job.getNamespace() + "/"
                                + job.getResource() + ")，请查看该资源的日志" : null);
                continue;
            }
            if (job.getDeadlineAt() != null && now > job.getDeadlineAt()) {
                cancelQuietly(job);
                jobService.markTimedOut(job.getId());
                log.warn("optimization batch {} timed out (deadline {})", job.getId(), job.getDeadlineAt());
            }
        }
    }

    private void cancelQuietly(OptimizationJobEntity job) {
        if (job.getResource() == null) {
            return;
        }
        try {
            submitter.cancel(job.getCatalogId(), job.getNamespace(), job.getResource());
        } catch (RuntimeException exception) {
            log.warn("cannot cancel timed out optimization batch {} ({}/{}): {}", job.getId(),
                    job.getNamespace(), job.getResource(), exception.getMessage());
        }
    }
}
