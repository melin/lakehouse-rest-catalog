package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobItemEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableOptimizationEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobItemRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableOptimizationRepository;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.ItemReportRequest;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.JobItem;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.Submission;
import io.github.melin.lakehouse.rest.dto.OptimizationDtos.SubmissionTable;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.Submitted;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.OptimizationOptions;
import io.github.melin.lakehouse.rest.support.OptimizationOptions.Action;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Item;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Job;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 优化批次的状态机与读写。
 *
 * <p><b>状态变化只从这里走。</b>调度器负责「什么时候提交、什么时候去问执行侧」，
 * 但把批次或明细置成什么状态一律调本类的方法——终态判定（哪些明细算失败、
 * 批次该是 {@code SUCCESS} 还是 {@code PARTIAL_SUCCESS}）只有一份实现，
 * 否则调度器与回调两条路径迟早对同一批数据得出不同结论。
 *
 * <p>所有方法都在事务里跑，且状态推进用条件更新（{@code transition}）抢占：
 * 两个调度节拍重叠、或回调与调度同时到达时，后到的那个会拿到 0 行更新而退出，
 * 不会把已经推进的状态覆盖回去。
 */
@Service
@RequiredArgsConstructor
public class OptimizationJobService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationJobService.class);

    /** 调度器出批的触发方标识，落进审计列。 */
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    /** 用户点「立即优化」或「重试」的触发方标识。 */
    public static final String TRIGGER_MANUAL = "MANUAL";
    /** 调度器写审计信息时用的主体名。 */
    public static final String SCHEDULER_PRINCIPAL = "paimon-scheduler";

    private static final int ERROR_LIMIT = 1000;
    private static final int MAX_LIST_SIZE = 200;

    /**
     * 提交阶段允许被中断并回收的次数。
     *
     * <p>封顶的理由不是「怕慢」，而是无限重投会把执行集群刷满失败记录，也会让
     * {@code deadline} 的计算失去意义（每一代都从「现在」重新算超时）。
     */
    public static final int MAX_SUBMIT_ATTEMPTS = 3;

    /** 执行侧收尾时可以改写的状态：{@code RUNNING}，以及回调早于 {@code markSubmitted} 时的 {@code SUBMITTING}。 */
    private static final List<String> TERMINAL_FROM = List.of(Job.RUNNING.name(), Job.SUBMITTING.name());
    /** 明细的终态集合（上报语态与「补失败」共用）。 */
    private static final List<String> ITEM_SETTLED =
            List.of(Item.SUCCESS.name(), Item.FAILED.name(), Item.SKIPPED.name());
    /** 明细的活跃集合：同一张表最多出现在一条活跃明细里（不变式 I1）。 */
    private static final List<String> ITEM_ACTIVE = List.of(Item.PENDING.name(), Item.RUNNING.name());

    /**
     * 明细层的固定失败文案。
     *
     * <p>为什么是常量而不是「把批次的错误原因抄下来」：多副本收尾时两台机器都会给未上报的
     * 明细补失败，文案里带动态内容会让它们互相覆盖成两种措辞。原因留给批次的
     * {@code error_message}——那里由条件更新胜出的那一台写，不会打架。
     */
    private static final String ITEM_NO_REPORT = "任务已结束但这张表没有上报结果";
    private static final String ITEM_EXECUTOR_FAILED = "任务在执行侧失败，这张表没有上报结果";
    private static final String ITEM_TIMED_OUT = "批次超过配置的超时时间仍未结束";
    private static final String ITEM_SUBMIT_FAILED = "提交优化任务失败，未开始执行";
    private static final String ITEM_CANCELED = "批次已被取消";

    private final OptimizationJobRepository jobRepository;
    private final OptimizationJobItemRepository itemRepository;
    private final TableOptimizationRepository policyRepository;
    private final CatalogRepository catalogRepository;
    private final OptimizationConfigResolver configResolver;
    private final OptimizationJobSubmitter submitter;
    private final ExecutionClusterService clusters;
    private final RestServerProperties properties;
    private final CatalogEventPublisher events;
    private final SchedulerLease lease;
    private final Clock clock;

    /** 入批的一张表：id、展示用的全名、这张表要执行的动作。 */
    public record BatchTable(String tableId, String fullName, List<Action> actions) {
    }

    // ------------------------------------------------------------------ 创建

    /**
     * 落库一个批次（含表级明细），状态 {@code PENDING}。
     *
     * <p>先落库、后提交是硬要求：提交是外部调用，可能失败、可能超时、
     * 进程可能在两者之间被重启。反过来（先提交再落库）会留下「集群里跑着一个
     * 谁都不知道的 Spark 任务」这种无法排查的状态。
     */
    @Transactional
    public OptimizationJobEntity createJob(String catalogId, String triggerType, List<BatchTable> tables,
                                           String retryOf, String principal) {
        if (tables == null || tables.isEmpty()) {
            throw ApiException.badRequest("optimization batch must contain at least one table");
        }
        long now = clock.millis();
        OptimizationJobEntity job = new OptimizationJobEntity();
        job.setId(Paging.newId());
        job.setCatalogId(catalogId);
        job.setTriggerType(triggerType);
        List<Action> actions = new ArrayList<>();
        for (BatchTable table : tables) {
            for (Action action : table.actions()) {
                if (!actions.contains(action)) {
                    actions.add(action);
                }
            }
        }
        job.setActions(OptimizationOptions.wire(actions));
        job.setStatus(Job.PENDING.name());
        job.setTableCount(tables.size());
        job.setCallbackToken(UUID.randomUUID().toString().replace("-", ""));
        job.setRetryOf(retryOf);
        job.markCreated(principal, now);
        job.setUpdatedAt(now);
        jobRepository.save(job);

        for (BatchTable table : tables) {
            OptimizationJobItemEntity item = new OptimizationJobItemEntity();
            item.setId(Paging.newId());
            item.setJobId(job.getId());
            item.setTableId(table.tableId());
            item.setTableFullName(truncate(table.fullName(), 1024));
            item.setActions(OptimizationOptions.wire(table.actions()));
            item.setStatus(Item.PENDING.name());
            item.markCreated(principal, now);
            item.setUpdatedAt(now);
            itemRepository.save(item);
        }
        log.info("optimization batch {} created for catalog {} with {} table(s), actions {}, trigger {}",
                job.getId(), catalogId, tables.size(), job.getActions(), triggerType);
        return job;
    }

    // ------------------------------------------------------------------ 状态推进

    /**
     * 抢占一个待提交批次，返回本次提交的代数；{@code 0} 表示没抢到。
     *
     * <p>抢占同时写提交租约（{@code claim_owner} + {@code claim_expires_at}）：
     * 分开写会留下「状态已是 SUBMITTING 但没有租约」的窗口，回收器读不到它，
     * 批次就永久卡在「提交中」——这正是上一版缺的那条回退路径。
     */
    @Transactional
    public int claimForSubmission(String jobId) {
        long now = clock.millis();
        int claimed = jobRepository.claimForSubmission(jobId, lease.instanceId(), now,
                now + lease.submissionLeaseTtl().toMillis());
        if (claimed != 1) {
            return 0;
        }
        // MySQL 没有 RETURNING：回读一次，读到的必然是刚写的那一代
        Integer attempt = jobRepository.findClaimAttempt(jobId);
        return attempt == null ? 0 : attempt;
    }

    /**
     * 提交成功：记录执行侧资源并转入运行中，同时定下超时时刻。
     *
     * <p>回写带 {@code attempt}：如果这次提交期间租约过期、别人已经回收并接管，
     * 我这边的回写就是「上一代的写」，必须被丢弃而不是覆盖——否则接管者刚写下的
     * 资源名会被我覆盖成同一个值还好，但 {@code deadline} 会被推后，批次可能永远不超时。
     *
     * @return {@code false} 表示批次已被接管，本次回写被丢弃
     */
    @Transactional
    public boolean markSubmitted(String jobId, Submitted submitted, int attempt) {
        OptimizationJobEntity job = require(jobId);
        long now = clock.millis();
        // 超时按 catalog 算：同一台集群上的不同 catalog 可以有不同份额，
        // 而「一批最多跑多久」跟着集群与表规模走
        long deadline = now + clusters.jobTimeoutSeconds(job.getCatalogId()) * 1000L;
        int rows = jobRepository.markRunning(jobId, submitter.name(), submitted.namespace(),
                submitted.resource(), deadline, attempt, now);
        if (rows != 1) {
            log.warn("optimization batch {} was taken over before it could be marked running"
                    + " (attempt {}, submitter {})", jobId, attempt, submitter.name());
            return false;
        }
        return true;
    }

    /**
     * 提交失败：批次与未终结的明细一起置失败。
     *
     * <p>先写批次再写明细：写在批次上的条件更新带代数，抢不到就说明这个批次已经被别人
     * 接管并重新提交了——此时把明细标失败会污染一个正在跑的批次。
     */
    @Transactional
    public void markSubmitFailed(String jobId, String error, int attempt) {
        long now = clock.millis();
        int rows = jobRepository.finishClaimed(jobId, Job.FAILED.name(), truncate(error, ERROR_LIMIT),
                List.of(Job.SUBMITTING.name()), attempt, now);
        if (rows != 1) {
            log.debug("optimization batch {} was reclaimed before submit failure of attempt {} was recorded",
                    jobId, attempt);
            return;
        }
        failOpenItems(jobId, ITEM_SUBMIT_FAILED, now);
        publishFinished(require(jobId), "submit-failed");
    }

    /**
     * 回收「提交中」的孤儿批次。
     *
     * <p>没有这一步，进程在 {@code submit()} 与 {@link #markSubmitted} 之间被杀的批次会
     * **永久停在 {@code SUBMITTING}**：{@code findTop50ByStatus(PENDING)} 取不到它，
     * {@code pollRunning} 又只看 {@code RUNNING}，控制台上它永远显示「提交中」。
     *
     * <p>回收不会造成双跑，前提是 {@code submit()} 幂等（Job 名只由批次 id 决定，
     * 且提交前先查同名资源）：被回收的批次下一拍重投会命中「已存在」而不是造出第二个 Job。
     * 该前提失效的唯一情形是「Job 已被 k8s 的 TTL 清掉」——所以
     * {@code claim-expires} 必须远小于 Job 的清理 TTL（见 {@code OptimizerJob}）。
     *
     * <p>回收次数封顶（{@link #MAX_SUBMIT_ATTEMPTS}）：反复中断说明服务端到执行集群的
     * 连接有问题，继续重投只会刷满失败记录，因此直接判失败并留下可读的原因。
     *
     * @return 本次回收（回 PENDING）的批次数
     */
    @Transactional
    public int reapStaleSubmissions() {
        long now = clock.millis();
        int reaped = 0;
        for (OptimizationJobEntity job : jobRepository
                .findTop50ByStatusAndClaimExpiresAtLessThanOrderByClaimExpiresAtAsc(Job.SUBMITTING.name(), now)) {
            if (job.getClaimAttempt() >= MAX_SUBMIT_ATTEMPTS) {
                String message = truncate("提交阶段反复中断（已重试 " + MAX_SUBMIT_ATTEMPTS
                        + " 次），请检查服务端与执行集群的连接", ERROR_LIMIT);
                if (jobRepository.finishClaimed(job.getId(), Job.FAILED.name(), message,
                        List.of(Job.SUBMITTING.name()), job.getClaimAttempt(), now) == 1) {
                    failOpenItems(job.getId(), ITEM_SUBMIT_FAILED, now);
                    publishFinished(require(job.getId()), "submit-failed");
                    log.warn("optimization batch {} failed after {} interrupted submissions",
                            job.getId(), MAX_SUBMIT_ATTEMPTS);
                }
                continue;
            }
            if (jobRepository.reclaimSubmission(job.getId(), job.getClaimAttempt(), now) == 1) {
                reaped++;
                log.warn("optimization batch {} submission lease expired (owner {}, attempt {}), back to PENDING",
                        job.getId(), job.getClaimOwner(), job.getClaimAttempt());
            }
        }
        return reaped;
    }

    /**
     * 执行侧到达终态后收尾。
     *
     * <p>「k8s 说成功」不等于「每张表都成功」：任务可能没来得及上报结果就退出了
     * （驱动被驱逐、上报请求失败）。此时没有上报的明细是**未知**而不是成功——
     * 按失败处理，让重试机制把它们捞回来。反过来把它们当成功，用户看到的就是
     * 「批次成功」而表还是没合并。
     *
     * <p><b>多副本下这一步是必然并发，不是偶发。</b>每台机器都会 poll 到同一个
     * {@code RUNNING} 批次，因此终态必须用条件更新写：只有改到 1 行的那台才有资格
     * 补明细、发事件。否则订阅方会收到两条 {@code job_succeeded}，超时判定还会把
     * 一个成功的批次改写成 {@code PARTIAL_SUCCESS}（不变式 I3 / I5）。
     */
    @Transactional
    public void finishFromSubmitter(String jobId, JobState state, String error) {
        OptimizationJobEntity job = require(jobId);
        Job current = Job.parse(job.getStatus());
        if (current != null && current.terminal()) {
            return;
        }
        long now = clock.millis();
        // 终态由明细分布决定：未上报的明细按失败计，所以这一步可以在写库之前算出结论，
        // 两台机器会算出同一个值（明细在收尾之后不再变化）
        Job status = statusFromItems(jobId);
        String message = error == null || error.isBlank() ? null : truncate(error, ERROR_LIMIT);
        int rows = jobRepository.finish(jobId, status.name(), message, TERMINAL_FROM, now);
        if (rows != 1) {
            log.debug("optimization batch {} was finished by another instance, drop this round", jobId);
            return;
        }
        failOpenItems(jobId, state == JobState.SUCCEEDED ? ITEM_NO_REPORT : ITEM_EXECUTOR_FAILED, now);
        log.info("optimization batch {} finished as {}", jobId, status);
        publishFinished(require(jobId), "executor");
    }

    /**
     * 超时收尾：与执行侧失败同一套处置，只是原因不同。
     *
     * <p>{@code deadline_at < now} 写进条件更新里，而不是「刚才读过一遍觉得超时了」：
     * 另一台机器可能已经把批次判成 {@code SUCCESS}，此时这条更新返回 0 行，
     * 成功的结论不会被改写成部分成功。
     */
    @Transactional
    public void markTimedOut(String jobId) {
        long now = clock.millis();
        // 超时意味着「执行侧没有给出终态」，因此明细不能留成 SUCCESS：全成功也只能是部分成功
        Job status = statusFromItems(jobId);
        Job target = status == Job.SUCCESS ? Job.PARTIAL_SUCCESS : status;
        String message = truncate("批次超过 " + clusters.jobTimeoutSeconds(require(jobId).getCatalogId())
                + " 秒仍未结束，已按超时处理", ERROR_LIMIT);
        int rows = jobRepository.finishAfterDeadline(jobId, target.name(), message,
                Job.activeNames(), now);
        if (rows != 1) {
            log.debug("optimization batch {} was already finished, timeout of this round is dropped", jobId);
            return;
        }
        failOpenItems(jobId, ITEM_TIMED_OUT, now);
        publishFinished(require(jobId), "timeout");
    }

    /**
     * 明细上报。
     *
     * <p>幂等：同一张表重复上报以最后一次为准（任务重试、上报重发都会出现这种情况）。
     * 但已经终结的明细不接受改写——重跑一张表会产生**新批次**，历史批次的结果是事实记录，
     * 不该被后来的执行覆盖。
     *
     * <p>判定从「读状态再写」改成条件更新：「已经终结就不接受」这条规则如果只在 Java 里
     * 判一次，两个并发上报都能通过。改成 {@code status not in (终态)} 之后只有一个能改到
     * 1 行，另一个按「已终结」返回 400，语义不变而竞态收口。
     */
    @Transactional
    public void report(String jobId, String itemId, ItemReportRequest request) {
        requireItem(jobId, itemId);
        Item status = Item.parse(request.status());
        if (status == null || status.active()) {
            throw ApiException.badRequest("status must be one of: SUCCESS, FAILED, SKIPPED");
        }
        long now = clock.millis();
        int rows = itemRepository.report(itemId, status.name(), truncate(request.errorMessage(), ERROR_LIMIT),
                ITEM_SETTLED, now);
        if (rows != 1) {
            throw ApiException.badRequest("item already finished as " + requireItem(jobId, itemId).getStatus());
        }
        OptimizationJobItemEntity item = requireItem(jobId, itemId);
        // metrics 是 JSON 列（带属性转换器），留给实体 save 写：批量 JPQL 里带转换器
        // 参数的行为在不同 Hibernate 版本上不一致，不值得为少一条语句去赌
        item.setMetrics(request.metrics() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.metrics()));
        if (item.getStartedAt() == null) {
            item.setStartedAt(now);
        }
        itemRepository.save(item);

        OptimizationJobEntity job = require(jobId);
        if (job.getStartedAt() == null) {
            job.setStartedAt(now);
            job.setUpdatedAt(now);
            jobRepository.save(job);
        }
    }


    // ------------------------------------------------------------------ 重试与取消

    /**
     * 重试：只把失败的表明细复制成一个新批次。
     *
     * <p>成功过的表不再跑一遍——重跑代价不是零（compaction 会重写文件），
     * 而且会让「这个批次的结果」在历史上出现两次不同结论。这与 AWS 优化器的
     * 「重跑只针对失败项」是同一个取舍。
     *
     * <p>参数取**当前策略**而不是原批次那一刻的参数：重试往往就是因为参数有问题，
     * 用户改过配置之后重试，理应带上新配置。
     */
    @Transactional
    public OptimizationJobEntity retry(String jobId, String principal) {
        OptimizationJobEntity origin = require(jobId);
        List<OptimizationJobItemEntity> failed = new ArrayList<>();
        for (OptimizationJobItemEntity item : itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId)) {
            if (Item.FAILED.name().equals(item.getStatus())) {
                failed.add(item);
            }
        }
        List<BatchTable> tables = new ArrayList<>();
        for (OptimizationJobItemEntity item : failed) {
            TableOptimizationEntity policy = policyRepository.findByTableId(item.getTableId()).orElse(null);
            if (policy == null) {
                log.warn("skip retry of table {} in batch {}: policy no longer exists",
                        item.getTableId(), jobId);
                continue;
            }
            tables.add(new BatchTable(item.getTableId(), item.getTableFullName(),
                    OptimizationOptions.fromWire(item.getActions())));
        }
        if (tables.isEmpty()) {
            throw ApiException.badRequest("nothing to retry: the batch has no failed table");
        }
        // 锁内复查（与出批同一把锁、同一顺序：catalog → 明细）：
        // 「这张表现在没在跑」是读出来的结论，而重试直接建批次、不经过 claim，
        // 不复查就会与调度批次同表双跑（单机两个线程也能复现）
        catalogRepository.lockById(origin.getCatalogId());
        for (BatchTable table : tables) {
            itemRepository.findFirstByTableIdAndStatusInOrderByCreatedAtDesc(table.tableId(), ITEM_ACTIVE)
                    .ifPresent(active -> {
                        throw ApiException.conflict("table " + table.fullName()
                                + " is already in batch " + active.getJobId());
                    });
        }
        return createJob(origin.getCatalogId(), origin.getTriggerType(), tables, origin.getId(), principal);
    }

    /**
     * 取消批次：执行侧资源一并删掉，避免任务继续跑并写数据。
     *
     * <p>写状态用条件更新（只认活跃状态）：两个用户同时点取消、或取消与收尾赛跑时，
     * 只有一台能改到 1 行。抢不到说明批次已经是终态了——此时不能把
     * {@code SUCCESS} 覆盖成 {@code CANCELED}，直接回 409 让调用方刷新。
     */
    @Transactional
    public OptimizationJobEntity cancel(String jobId, String principal) {
        OptimizationJobEntity job = require(jobId);
        if (Job.parse(job.getStatus()).terminal()) {
            throw ApiException.badRequest("batch already finished as " + job.getStatus());
        }
        String error = null;
        if (job.getResource() != null) {
            try {
                submitter.cancel(job.getCatalogId(), job.getNamespace(), job.getResource());
            } catch (RuntimeException exception) {
                // 取消失败不改状态：批次确实不该继续跑了，但资源可能还在。
                // 把原因记在批次上，运维照着资源名手工清理即可。
                error = "执行侧资源取消失败（" + job.getNamespace() + "/" + job.getResource() + "）："
                        + exception.getMessage();
                log.warn("cannot cancel optimization batch {}: {}", jobId, exception.getMessage());
            }
        }
        long now = clock.millis();
        int rows = jobRepository.cancel(jobId, truncate(error, ERROR_LIMIT), principal,
                Job.activeNames(), now);
        if (rows != 1) {
            throw ApiException.conflict("batch " + jobId + " has been finished as "
                    + require(jobId).getStatus());
        }
        failOpenItems(jobId, ITEM_CANCELED, now);
        OptimizationJobEntity canceled = require(jobId);
        publishFinished(canceled, "canceled");
        return canceled;
    }


    // ------------------------------------------------------------------ 查询

    /** 批次列表，最近的在前。不返回表级明细（列表页要的是批次级信息）。 */
    @Transactional(readOnly = true)
    public List<OptimizationDtos.Job> list(String catalogName, String status, Action action,
                                           String triggerType, int limit) {
        List<OptimizationJobEntity> jobs;
        if (catalogName == null || catalogName.isBlank()) {
            jobs = jobRepository.findAllByOrderByCreatedAtDesc();
        } else {
            // 过滤条件是 catalog 名（与路径段、控制台、权限判定同一套词），实体上存的是 id。
            String catalogId = catalogRepository.findByPrefix(catalogName)
                    .map(CatalogEntity::getId)
                    .orElse(null);
            jobs = catalogId == null ? List.of() : jobRepository.findAllByCatalogIdOrderByCreatedAtDesc(catalogId);
        }
        List<OptimizationDtos.Job> result = new ArrayList<>();
        int size = Math.min(Math.max(limit, 1), MAX_LIST_SIZE);
        for (OptimizationJobEntity job : jobs) {
            if (status != null && !status.isBlank() && !status.equalsIgnoreCase(job.getStatus())) {
                continue;
            }
            if (action != null && !OptimizationOptions.fromWire(job.getActions()).contains(action)) {
                continue;
            }
            // 触发方式（SCHEDULED / MANUAL）也在这里筛：它是批次自己的属性，
            // 与状态、动作一样属于「列表要显示什么」，不是另一层数据。
            if (triggerType != null && !triggerType.isBlank()
                    && !triggerType.equalsIgnoreCase(job.getTriggerType())) {
                continue;
            }
            result.add(toDto(job, null, false));
            if (result.size() >= size) {
                break;
            }
        }
        return result;
    }

    /** 批次详情：含表级明细。{@code includeToken} 为真时带上回调凭据（见控制器）。 */
    @Transactional(readOnly = true)
    public OptimizationDtos.Job detail(String jobId, boolean includeToken) {
        OptimizationJobEntity job = require(jobId);
        return toDto(job, items(jobId), includeToken);
    }

    @Transactional(readOnly = true)
    public OptimizationJobEntity require(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.OPTIMIZATION_JOB, jobId));
    }

    @Transactional(readOnly = true)
    public List<JobItem> items(String jobId) {
        List<JobItem> result = new ArrayList<>();
        for (OptimizationJobItemEntity item : itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId)) {
            result.add(toItem(item));
        }
        return result;
    }

    /** 单表的历史（表详情页三块历史的数据源），最近的在前。 */
    @Transactional(readOnly = true)
    public List<JobItem> itemsOfTable(String tableId) {
        List<JobItem> result = new ArrayList<>();
        for (OptimizationJobItemEntity item : itemRepository.findTop100ByTableIdOrderByCreatedAtDesc(tableId)) {
            result.add(toItem(item));
        }
        return result;
    }

    /** 明细的对外形状只有一份，两个查询共用——加字段时不会漏掉其中一个。 */
    private static JobItem toItem(OptimizationJobItemEntity item) {
        return new JobItem(item.getId(), item.getJobId(), item.getTableId(), item.getTableFullName(),
                item.getActions(), item.getStatus(), item.getMetrics(), item.getErrorMessage(),
                item.getStartedAt(), item.getFinishedAt(), item.getReportedAt());
    }

    // ------------------------------------------------------------------ 优化任务协定

    /** 拉取执行清单（优化任务启动时调）。清单里的参数是**当前生效值**，见 DTO 注释。 */
    @Transactional(readOnly = true)
    public Submission submission(String jobId) {
        OptimizationJobEntity job = require(jobId);
        CatalogFormat format = catalogFormat(job.getCatalogId());
        List<SubmissionTable> tables = new ArrayList<>();
        for (OptimizationJobItemEntity item : itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId)) {
            Map<String, Map<String, String>> all = configResolver
                    .resolveByTable(format, job.getCatalogId(), item.getTableId());
            Map<String, Map<String, String>> wanted = new LinkedHashMap<>();
            for (Action action : OptimizationOptions.fromWire(item.getActions())) {
                wanted.put(action.wire(), all.get(action.wire()));
            }
            tables.add(new SubmissionTable(item.getId(), item.getTableFullName(), item.getStatus(), wanted));
        }
        return new Submission(job.getId(), catalogName(job.getCatalogId()), format.wireName(),
                job.getTriggerType(),
                OptimizationOptions.fromWire(job.getActions()).stream().map(Action::wire).toList(), tables);
    }

    /**
     * 批次所属 catalog 的格式。
     *
     * <p>catalog 已被删除时回落 Paimon：批次是历史事实，此时它不可能再被提交
     * （提交前服务端会先查到 catalog），回落只影响一条查询的返回值，不会影响执行。
     */
    private CatalogFormat catalogFormat(String catalogId) {
        return catalogRepository.findById(catalogId)
                .map(catalog -> CatalogFormat.of(catalog.getCatalogFormat()))
                .orElse(CatalogFormat.DEFAULT);
    }

    /**
     * 校验批次回调凭据。
     *
     * <p>比较用 {@link MessageDigest#isEqual}：令牌是随机值，时序攻击在这里并不现实，
     * 但「比较秘密值」这件事本身不值得写成 {@code equals}——同样的写法在别处用错一次
     * 就是真问题，统一成常量时间比较没有额外成本。
     */
    @Transactional(readOnly = true)
    public void requireCallbackToken(String jobId, String token) {
        OptimizationJobEntity job = require(jobId);
        if (token == null || token.isBlank()) {
            throw ApiException.forbidden("missing optimization job token");
        }
        boolean matched = MessageDigest.isEqual(
                job.getCallbackToken().getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8));
        if (!matched) {
            throw ApiException.forbidden("invalid optimization job token");
        }
    }

    // ------------------------------------------------------------------ 内部

    private OptimizationJobItemEntity requireItem(String jobId, String itemId) {
        OptimizationJobItemEntity item = itemRepository.findById(itemId)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.OPTIMIZATION_JOB, itemId));
        if (!jobId.equals(item.getJobId())) {
            throw ApiException.badRequest("item " + itemId + " does not belong to batch " + jobId);
        }
        return item;
    }

    /**
     * 把没上报结果的明细补成失败。
     *
     * <p>用条件更新而不是实体 {@code save}：两件事同时得到保证——
     * <ul>
     *   <li>已经上报的明细不会被覆盖（{@code status not in 终态} 的 0 行）；
     *   <li>两台机器同时补失败时只有一台改到，文案虽然相同也不会互相打架。
     * </ul>
     * 这也是「补失败允许双写」这条设计的前提：文案是常量，写入是幂等的。
     */
    private int failOpenItems(String jobId, String reason, long now) {
        int updated = 0;
        for (OptimizationJobItemEntity item : itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId)) {
            Item status = Item.parse(item.getStatus());
            if (status != null && status.terminal()) {
                continue;
            }
            updated += itemRepository.report(item.getId(), Item.FAILED.name(), reason, ITEM_SETTLED, now);
        }
        if (updated > 0) {
            log.info("optimization batch {}: {} item(s) without a result marked as FAILED ({})",
                    jobId, updated, reason);
        }
        return updated;
    }

    /**
     * 由明细分布折算批次结论。
     *
     * <p>**未终结的明细按失败计**：走到这一步意味着执行侧已经不跑了，没上报的表是
     * 「不知道」而不是「成功」（重试机制会把这些表捞回来）。把它算成失败还有一层好处——
     * 结论可以在写库之前算出来，两台机器算的是同一份数据、同一个规则，不会得出两种答案。
     */
    private Job statusFromItems(String jobId) {
        long success = 0;
        long failed = 0;
        for (OptimizationJobItemEntity item : itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId)) {
            if (Item.SUCCESS.name().equals(item.getStatus())) {
                success++;
            } else if (!Item.SKIPPED.name().equals(item.getStatus())) {
                // FAILED 与「还没终结」都计为失败
                failed++;
            }
        }
        if (failed == 0) {
            return Job.SUCCESS;
        }
        return success > 0 ? Job.PARTIAL_SUCCESS : Job.FAILED;
    }


    private OptimizationDtos.Job toDto(OptimizationJobEntity job, List<JobItem> items, boolean includeToken) {
        return new OptimizationDtos.Job(job.getId(), catalogName(job.getCatalogId()), job.getTriggerType(),
                OptimizationOptions.fromWire(job.getActions()).stream().map(Action::wire).toList(),
                job.getStatus(), job.getTableCount(), job.getSubmitter(), job.getNamespace(), job.getResource(),
                job.getSparkAppId(), job.getCreatedAt(), job.getSubmittedAt(), job.getStartedAt(),
                job.getFinishedAt(), job.getDeadlineAt(), job.getErrorMessage(), job.getRetryOf(),
                includeToken ? job.getCallbackToken() : null, items);
    }

    /**
     * 批次的 catalog 对外用**名字**（prefix），不是实体里的 id。
     *
     * <p>两个理由，都不是风格问题：优化任务要拿这个名字去建 REST catalog 连接
     * （id 对它毫无意义，那是本服务内部的主键）；控制台的过滤条件与权限判定
     * （{@code AuthorizationService#hasAnyGrant}）也都按名字走。对外给 id 会让调用方被迫再做一次
     * id → name 的转换，而这个转换表只有本服务有。
     *
     * <p>catalog 被删除时回落成 id：批次是历史事实，不该因为 catalog 没了就查不出来。
     */
    private String catalogName(String catalogId) {
        return catalogRepository.findById(catalogId)
                .map(CatalogEntity::getPrefix)
                .orElse(catalogId);
    }

    /**
     * 批次到达终态后按终态发一条事件：{@code job_succeeded} / {@code job_partially_succeeded} /
     * {@code job_failed} / {@code job_canceled}。
     *
     * <p><b>为什么按终态分型而不是一条 {@code job_finished} 带 state 属性。</b>消费方最常做的
     * 一件事是「失败就告警」，分型之后这条规则是订阅一个类型，而不用在报文里再判一次
     * {@code state}；反过来「所有批次终态都要」也有现成的类别（{@code OPTIMIZATION}）可用。
     *
     * <p>只发一次的保障来自「先判终态再写」的调用方纪律之外，这里再兜一道：
     * 事件只在 {@code status} 已是终态时发布，重复调用（调度与回调赛跑）不会发出
     * 第二条。{@code cause} 区分四条到达路径（executor / submit-failed / timeout /
     * canceled），同一终态可能由不同路径到达，排障时要分得开。
     */
    private void publishFinished(OptimizationJobEntity job, String cause) {
        Job status = Job.parse(job.getStatus());
        if (status == null || !status.terminal()) {
            return;
        }
        CatalogEventType type = switch (status) {
            case SUCCESS -> CatalogEventType.OPTIMIZATION_JOB_SUCCEEDED;
            case PARTIAL_SUCCESS -> CatalogEventType.OPTIMIZATION_JOB_PARTIALLY_SUCCEEDED;
            case CANCELED -> CatalogEventType.OPTIMIZATION_JOB_CANCELED;
            default -> CatalogEventType.OPTIMIZATION_JOB_FAILED;
        };
        events.publish(type, catalogName(job.getCatalogId()),
                CatalogEvent.TARGET_JOB, job.getId(),
                Map.of("jobId", job.getId(), "state", job.getStatus(), "cause", cause,
                        "tables", job.getTableCount(), "trigger", job.getTriggerType()));
    }

    private static String truncate(String value, int limit) {
        if (value == null) {
            return null;
        }
        return value.length() <= limit ? value : value.substring(0, limit);
    }
}
