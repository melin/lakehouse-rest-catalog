package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.SubmissionRequest;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.Submitted;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Job;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 优化批次的调度节拍：出批、提交、跟踪。
 *
 * <p>三个动作拆成两个节拍，间隔不同：
 * <ul>
 *   <li><b>扫描</b>（{@code lakehouse.rest.optimization.scan-interval}，默认 1 分钟）：
 *       把到期策略攒成批次落库；
 *   <li><b>提交与跟踪</b>（{@code dispatch-interval}，默认 10 秒）：把 {@code PENDING}
 *       的批次发出去、问 {@code RUNNING} 的批次跑到哪了。
 * </ul>
 * 拆开的原因是它们的时效性要求不同：出批是分钟级的事，而「提交」与「状态刷新」
 * 决定用户看到多久的延迟——一个批次落库后等 60 秒才提交，控制台上就会出现
 * 「排队中」卡住一分钟的观感。
 *
 * <p><b>逐条独立失败。</b>一个批次提交失败不影响同一节拍里其它批次：
 * 循环里每个批次各自 try/catch，失败写进批次自己的 {@code error_message}。
 * 反过来（整节拍失败退出）会让「有一个坏批次」变成「所有批次都停摆」。
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
    private final RestServerProperties properties;
    private final Clock clock;

    /** 出批节拍：扫描到期策略，攒批落库。 */
    @Scheduled(fixedDelayString = "${lakehouse.rest.optimization.scan-interval:PT1M}",
            initialDelayString = "${lakehouse.rest.optimization.scan-interval:PT1M}")
    public void scan() {
        if (!properties.getOptimization().isSchedulerEnabled()) {
            return;
        }
        try {
            batchService.scanAndCreateBatches();
        } catch (RuntimeException exception) {
            log.error("optimization scan failed", exception);
        }
    }

    /** 提交与跟踪节拍。 */
    @Scheduled(fixedDelayString = "${lakehouse.rest.optimization.dispatch-interval:PT10S}",
            initialDelayString = "${lakehouse.rest.optimization.dispatch-interval:PT10S}")
    public void dispatch() {
        if (!properties.getOptimization().isSchedulerEnabled()) {
            return;
        }
        submitPending();
        pollRunning();
    }

    /** 把 PENDING 批次发到执行环境。 */
    public void submitPending() {
        int limit = properties.getOptimization().getMaxConcurrentJobs();
        for (OptimizationJobEntity job : jobRepository.findTop50ByStatusOrderByCreatedAtAsc(Job.PENDING.name())) {
            // 执行侧闸门：同时在跑的批次不超过上限，放不出去的 PENDING 留在队列里等下一拍。
            //
            // 与出批侧那道检查（{@code OptimizationBatchService}）数的是不同的口径，两道都要有：
            // 出批侧数「已承诺的批次」（含排队中的），决定**还该不该造新批次**；这里数
            // 「已经放出去的」，决定**造好的批次放不放行**。少了这一道，手动「立即优化」
            // 与重试就能绕过上限——它们直接建批次，压根不经过出批那条路，
            // 而这条上限是给集群留余量的（每个批次一个 Spark 驱动），绕过它等于没有上限。
            //
            // 提交顺序按创建时间，先到先跑：手动批次会排在已有队列后面，
            // 但不会插队——插队意味着调度批次可能永远等不到执行窗口。
            if (jobRepository.countByStatusIn(Job.dispatchedNames()) >= limit) {
                log.debug("optimization concurrency limit ({}) reached, pending batch(es) stay queued", limit);
                break;
            }
            if (!jobService.claimForSubmission(job.getId())) {
                continue;
            }
            try {
                Submitted submitted = submitter.submit(
                        new SubmissionRequest(job.getId(), job.getCatalogId(), job.getCallbackToken()));
                jobService.markSubmitted(job.getId(), submitted);
                log.info("optimization batch {} submitted to {} as {}/{}",
                        job.getId(), submitter.name(), submitted.namespace(), submitted.resource());
            } catch (RuntimeException exception) {
                // 提交失败是终态失败而不是重试：重试要由用户显式发起（可能配置错了镜像或权限，
                // 盲目重投只会反复失败并把执行环境的错误日志刷满）。
                jobService.markSubmitFailed(job.getId(), "提交优化任务失败：" + exception.getMessage()
                        + "（提交方式：" + submitter.name() + "）");
                log.error("cannot submit optimization batch {}", job.getId(), exception);
            }
        }
    }

    /** 问执行环境跑到哪了，并在终态或超时时收尾。 */
    public void pollRunning() {
        for (OptimizationJobEntity job : jobRepository.findTop50ByStatusOrderByCreatedAtAsc(Job.RUNNING.name())) {
            long now = clock.millis();
            JobState state;
            try {
                state = submitter.state(job.getNamespace(), job.getResource());
            } catch (RuntimeException exception) {
                // 查询失败不回写状态：一次 API 抖动不该把一个正在跑的批次判成失败。
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
                log.warn("optimization batch {} timed out after {}", job.getId(),
                        properties.getOptimization().getJobTimeout());
            }
        }
    }

    private void cancelQuietly(OptimizationJobEntity job) {
        if (job.getResource() == null) {
            return;
        }
        try {
            submitter.cancel(job.getNamespace(), job.getResource());
        } catch (RuntimeException exception) {
            log.warn("cannot cancel timed out optimization batch {} ({}/{}): {}", job.getId(),
                    job.getNamespace(), job.getResource(), exception.getMessage());
        }
    }
}
