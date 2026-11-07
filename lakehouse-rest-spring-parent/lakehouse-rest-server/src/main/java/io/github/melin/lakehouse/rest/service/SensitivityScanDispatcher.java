package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.SensitivityScanEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityScanRepository;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanExecution;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanStatus;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;
import io.github.melin.lakehouse.rest.service.submit.ScanJobSubmitter;
import io.github.melin.lakehouse.rest.service.submit.ScanJobSubmitter.Submitted;
import io.github.melin.lakehouse.rest.service.submit.ScanJobSubmitter.Submission;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Job;
import io.github.melin.lakehouse.rest.support.ScanTokens;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 下推扫描的提交与跟踪节拍（设计见 {@code docs/sensitive-data-design.md} §5.6.5 / §5.6.6）。
 *
 * <p><b>它只做三件事：</b>把 {@code PENDING} 提交成 Job、回收卡在 {@code SUBMITTING} 的孤儿、
 * 问执行侧跑到哪了并在终态 / 超时时收尾。逐表结果由作业回调写入
 * （{@code POST /sensitivity-scans/{id}/items}），不经过这里——作业才是那个知道
 * 「哪张表跑了、哪张失败了」的角色。
 *
 * <p><b>与优化批次共用一把 {@code maxConcurrentJobs}，这是本轮的口径。</b>两类作业跑在
 * 同一台集群上、争的是同一份算力，所以这里数的是「扫描在跑的 + 优化在跑的」之和：
 * 各开一把闸门时两侧各自看起来都在额度内，实际把同一台集群放到两份额度的驱动数，
 * 而这个总和没有任何一处看得见（§5.6.6）。代价是两类任务互相排队，让位比超发诚实。
 *
 * <p><b>两个通道必须分开。</b>{@code sensitivity.scan}（调度节拍）负责「到点了该发起一次扫描」，
 * {@code sensitivity.dispatch}（本类）负责「把线上的扫描提交成 Job」。合成一个之后，
 * 一台实例在做 A 路线扫描时会同时挡住提交，反之亦然（§5.6.3）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitivityScanDispatcher {

    /** 待提交的扫描状态。 */
    private static final String PENDING = ScanStatus.PENDING.name();

    /** 提交中的扫描状态（带着提交租约）。 */
    private static final String SUBMITTING = ScanStatus.SUBMITTING.name();

    /** 运行中的扫描状态。 */
    private static final String RUNNING = ScanStatus.RUNNING.name();

    private final SensitivityScanRepository scanRepository;
    private final OptimizationJobRepository jobRepository;
    private final SensitivityScanService scans;
    private final ScanJobSubmitter submitter;
    private final ExecutionClusterService clusters;
    private final CatalogRepository catalogRepository;
    private final SchedulerLease lease;
    private final RestServerProperties properties;
    private final Clock clock;

    /**
     * 节拍（默认 10 秒，{@code lakehouse.rest.sensitivity.schedule.dispatch-interval}）。
     *
     * <p>这个值决定「用户点了发起扫描之后多久真的开始跑」的延迟上限，也决定提交租约的
     * TTL（{@link SchedulerLease#sensitivitySubmissionLeaseTtl()}：3 × 节拍，下限 30 秒）。
     * 调大它省不到什么——本拍的代价是两次按状态索引的查询。
     */
    @Scheduled(fixedDelayString = "${lakehouse.rest.sensitivity.schedule.dispatch-interval:PT10S}",
            initialDelayString = "${lakehouse.rest.sensitivity.schedule.dispatch-interval:PT10S}")
    public void tick() {
        if (!properties.getOptimization().isSchedulerEnabled()) {
            return;
        }
        if (lease.tryAcquire(SchedulerLease.CHANNEL_SENSITIVITY_DISPATCH)) {
            submitPending();
            reapStaleSubmissions();
        } else {
            log.debug("skip sensitivity submit: another instance holds {}",
                    SchedulerLease.CHANNEL_SENSITIVITY_DISPATCH);
        }
        // 跟踪不占租约：写全是条件更新，并发安全；放开之后接管只差一拍（与优化批次同一条）
        pollRunning();
    }

    /** 把 PENDING 的扫描提交到各自 catalog 的执行集群。 */
    public void submitPending() {
        // 本拍内每个 catalog 的「已经放出去几个」与「上限」：库里数一次之后就累加，
        // 避免每提交一个就再查两次库
        Map<String, Long> dispatched = new HashMap<>();
        Map<String, Long> limits = new HashMap<>();
        for (SensitivityScanEntity scan : scanRepository.findTop50ByStatusOrderByCreatedAtAsc(PENDING)) {
            String catalogId = catalogIdOf(scan);
            if (catalogId == null) {
                // catalog 被删了：这一行是历史事实，永远提交不出去。**不判失败**——
                // 判失败会让人在「catalog 恢复」之后还得手工重来，而那时它已经不该被提交
                log.debug("scan {} refers to a catalog that no longer exists, staying PENDING", scan.getId());
                continue;
            }
            long limit = limits.computeIfAbsent(catalogId, key -> {
                // 不可派发（未配置 / 停用 / 验证失败）：只跳过，不判失败——填完配置就该自动开始
                if (!clusters.dispatchable(key)) {
                    log.debug("catalog {} has no dispatchable execution cluster, its pending scans stay queued", key);
                    return -1L;
                }
                return (long) clusters.maxConcurrentJobs(key);
            });
            if (limit < 0) {
                continue;
            }
            long already = dispatched.computeIfAbsent(catalogId, key ->
                    // 扫描按 **catalog 名** 数（扫描表上没有 catalog_id 列），优化批次按 id 数
                    scanRepository.countByCatalogNameAndStatusIn(scan.getCatalogName(),
                                    List.of(SUBMITTING, RUNNING))
                            + jobRepository.countByCatalogIdAndStatusIn(key, Job.dispatchedNames()));
            if (already >= limit) {
                log.debug("job concurrency limit ({}) of catalog {} reached, its pending scans stay queued",
                        limit, catalogId);
                continue;
            }

            long now = clock.millis();
            long expires = now + lease.sensitivitySubmissionLeaseTtl().toMillis();
            if (scanRepository.claimForSubmission(scan.getId(), now, expires) == 0) {
                continue;
            }
            Integer attempt = scanRepository.findClaimAttempt(scan.getId());
            if (attempt == null) {
                // 抢占成功却读不到代数：这一行在本次事务里被删了。跳过，下一拍不会再看到它
                continue;
            }
            // 令牌在**提交这一刻**生成：库里只存哈希（§4.2），明文随 Job 的命令行离开进程。
            // 不在建单时生成，是因为 PENDING 可能等很久，而一个「还没人用」的令牌放得越久
            // 越没有理由存在
            String token = ScanTokens.newToken();
            try {
                Submitted submitted = submitter.submit(new Submission(scan.getId(), catalogId, token));
                // 用**提交器交回来的**令牌算哈希：命中已存在的同名 Job 时它才是作业真正带着的那个
                String hash = ScanTokens.hash(submitted.callbackToken());
                if (scanRepository.markSubmitted(scan.getId(), submitted.namespace(),
                        submitted.resource(), hash, attempt, now) == 0) {
                    log.warn("scan {} submitted as {}/{} but the claim was lost; another instance takes over",
                            scan.getId(), submitted.namespace(), submitted.resource());
                    continue;
                }
                dispatched.merge(catalogId, 1L, Long::sum);
                log.info("sensitivity scan {} submitted to {} as {}/{}", scan.getId(), submitter.name(),
                        submitted.namespace(), submitted.resource());
            } catch (RuntimeException exception) {
                // 提交失败是终态：原因通常是配置层面长期存在的问题（镜像、RBAC、namespace），
                // 盲目重投只会每分钟刷一遍同样的错误
                scanRepository.failClaim(scan.getId(), "提交敏感识别扫描失败：" + exception.getMessage()
                        + "（提交方式：" + submitter.name() + "）", attempt, now);
                log.error("cannot submit sensitivity scan {}", scan.getId(), exception);
            }
        }
    }

    /**
     * 回收卡在 {@code SUBMITTING} 的孤儿扫描。
     *
     * <p>与提交同一拍、同一租约，且**放在提交之后**：刚提交完、租约还没到期的扫描不该被
     * 误回收（回收语句自己也有 {@code claim_expires_at < now} 兜着）。
     */
    void reapStaleSubmissions() {
        long now = clock.millis();
        try {
            for (SensitivityScanEntity scan
                    : scanRepository.findTop50ByStatusAndClaimExpiresAtLessThanOrderByClaimExpiresAtAsc(SUBMITTING, now)) {
                Integer attempt = scan.getClaimAttempt();
                if (attempt == null) {
                    continue;
                }
                if (scanRepository.reclaimSubmission(scan.getId(), attempt, now) > 0) {
                    log.warn("reclaimed sensitivity scan {} stuck in SUBMITTING (attempt {})",
                            scan.getId(), attempt);
                }
            }
        } catch (RuntimeException exception) {
            // 回收失败不该影响下一拍：坏掉的是「某一条卡住」，不是整条调度
            log.error("cannot reap stale sensitivity submissions", exception);
        }
    }

    /**
     * 问执行侧跑到哪了，并在终态或超时时收尾。
     *
     * <p><b>只处理 {@code execution=SPARK} 的行。</b>{@code RUNNING} 这个状态 A 路线也用——
     * 同步扫描期间它就是 RUNNING，而它没有 Job 坐标。不跳过的话，每一拍都会对每一条正在跑的
     * A 路线扫描调用一次 {@code state(null, null)}，得到 UNKNOWN，然后白白等超时。
     *
     * <p><b>「少上报了几张表」不在这里补。</b>设计稿要求作业对每张表都回一条（包括失败），
     * 服务端不替它补：补的话就得知道「这一轮到底该有几张表」，而表清单在一次扫描期间可能变，
     * 算出来的差额会把「有人建了张新表」记成「作业漏扫」。
     */
    public void pollRunning() {
        for (SensitivityScanEntity scan : scanRepository.findTop50ByStatusOrderByCreatedAtAsc(RUNNING)) {
            if (!ScanExecution.SPARK.name().equals(scan.getExecution())) {
                continue;
            }
            String catalogId = catalogIdOf(scan);
            if (catalogId == null) {
                // 执行集群配置随 catalog 一起没了：没有判据能问出状态，直接按超时处理会更诚实，
                // 但「catalog 被删」不该让一次已经在跑的扫描变失败——留给下一次清理
                continue;
            }
            long now = clock.millis();
            JobState state;
            try {
                state = submitter.state(catalogId, scan.getExecutionNamespace(), scan.getExecutionResource());
            } catch (RuntimeException exception) {
                // 查询失败不回写状态：一次 API 抖动不该把正在跑的扫描判成失败。
                // 集群不可达、凭据过期都属于这一类，正确处置是「等它好起来」或「由超时收尾」
                log.warn("cannot query state of sensitivity scan {} ({}/{}): {}", scan.getId(),
                        scan.getExecutionNamespace(), scan.getExecutionResource(), exception.getMessage());
                state = JobState.UNKNOWN;
            }
            if (state == JobState.SUCCEEDED || state == JobState.FAILED) {
                finish(scan, state, now);
                continue;
            }
            if (timedOut(scan, catalogId, now)) {
                cancelQuietly(scan, catalogId);
                finish(scan, JobState.FAILED,
                        "扫描作业超时（" + clusters.scanJobTimeoutSeconds(catalogId) + " 秒），已删除该 Job", now);
            }
        }
    }

    private boolean timedOut(SensitivityScanEntity scan, String catalogId, long now) {
        if (scan.getStartedAt() == null) {
            return false;
        }
        long timeout = clusters.scanJobTimeoutSeconds(catalogId) * 1000L;
        return now > scan.getStartedAt() + timeout;
    }

    /**
     * 写终态并做血缘传播。
     *
     * <p>{@code from} 同时含 {@code RUNNING} 与 {@code SUBMITTING}：一个提交成功但回写失败的
     * 扫描可能停在 SUBMITTING，而它的 Job 已经跑完了——这时也该收尾，否则它要等到租约到期
     * 被回收、再投一次、再跑一遍。
     */
    private void finish(SensitivityScanEntity scan, JobState state, long now) {
        finish(scan, state, state == JobState.FAILED
                ? "执行侧扫描作业失败（" + scan.getExecutionNamespace() + "/" + scan.getExecutionResource()
                        + "），请查看该资源的日志"
                : null, now);
    }

    private void finish(SensitivityScanEntity scan, JobState state, String error, long now) {
        String finalStatus = state == JobState.SUCCEEDED
                ? ScanStatus.SUCCEEDED.name() : ScanStatus.FAILED.name();
        int updated = scanRepository.finishStatus(scan.getId(), finalStatus, error,
                List.of(RUNNING, SUBMITTING), now);
        if (updated == 0) {
            // 已经被取消、或另一个实例先收尾了：什么都不做
            log.debug("sensitivity scan {} was already finished; skipping", scan.getId());
            return;
        }
        scan.setStatus(finalStatus);
        scan.setFinishedAt(now);
        scan.setErrorMessage(error);
        log.info("sensitivity scan {} finished as {} (tables scanned {}, failed {})",
                scan.getId(), finalStatus, scan.getTablesScanned(), scan.getTablesFailed());
        // 传播是收尾的增值动作：它不改计数，也不该让一次已经成功的扫描变失败（§7.1）
        if (state == JobState.SUCCEEDED) {
            scans.propagateAfterPush(scan);
        }
    }

    private void cancelQuietly(SensitivityScanEntity scan, String catalogId) {
        if (scan.getExecutionResource() == null) {
            return;
        }
        try {
            submitter.cancel(catalogId, scan.getExecutionNamespace(), scan.getExecutionResource());
        } catch (RuntimeException exception) {
            log.warn("cannot cancel timed out sensitivity scan {} ({}/{}): {}", scan.getId(),
                    scan.getExecutionNamespace(), scan.getExecutionResource(), exception.getMessage());
        }
    }

    /**
     * 扫描所属 catalog 的内部 id。
     *
     * <p>扫描表上只存 catalog **名**（{@code catalog_name}，与标签表同一个口径：名字进哈希、
     * 删除不影响审计），而集群配置按 id 存。所以这里做一次名字 → id 的解析。
     */
    private String catalogIdOf(SensitivityScanEntity scan) {
        return catalogRepository.findByPrefix(scan.getCatalogName())
                .map(CatalogEntity::getId)
                .orElse(null);
    }
}
