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

    private final OptimizationJobRepository jobRepository;
    private final OptimizationJobItemRepository itemRepository;
    private final TableOptimizationRepository policyRepository;
    private final CatalogRepository catalogRepository;
    private final OptimizationConfigResolver configResolver;
    private final OptimizationJobSubmitter submitter;
    private final RestServerProperties properties;
    private final CatalogEventPublisher events;
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

    /** 抢占一个待提交批次；返回 {@code false} 表示已被别的节拍或实例提交。 */
    @Transactional
    public boolean claimForSubmission(String jobId) {
        return jobRepository.transition(jobId, Job.PENDING.name(), Job.SUBMITTING.name()) == 1;
    }

    /** 提交成功：记录执行侧资源并转入运行中，同时定下超时时刻。 */
    @Transactional
    public void markSubmitted(String jobId, Submitted submitted) {
        OptimizationJobEntity job = require(jobId);
        long now = clock.millis();
        job.setSubmitter(submitter.name());
        job.setNamespace(submitted.namespace());
        job.setResource(submitted.resource());
        job.setStatus(Job.RUNNING.name());
        job.setSubmittedAt(now);
        job.setStartedAt(now);
        job.setDeadlineAt(now + properties.getOptimization().getJobTimeout().toMillis());
        job.setUpdatedAt(now);
        jobRepository.save(job);
    }

    /** 提交失败：批次与未终结的明细一起置失败。 */
    @Transactional
    public void markSubmitFailed(String jobId, String error) {
        OptimizationJobEntity job = require(jobId);
        long now = clock.millis();
        job.setStatus(Job.FAILED.name());
        job.setErrorMessage(truncate(error, ERROR_LIMIT));
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        jobRepository.save(job);
        failOpenItems(jobId, error, now);
        publishFinished(job, "submit-failed");
    }

    /**
     * 执行侧到达终态后收尾。
     *
     * <p>「k8s 说成功」不等于「每张表都成功」：任务可能没来得及上报结果就退出了
     * （驱动被驱逐、上报请求失败）。此时没有上报的明细是**未知**而不是成功——
     * 按失败处理，让重试机制把它们捞回来。反过来把它们当成功，用户看到的就是
     * 「批次成功」而表还是没合并。
     */
    @Transactional
    public void finishFromSubmitter(String jobId, JobState state, String error) {
        OptimizationJobEntity job = require(jobId);
        Job current = Job.parse(job.getStatus());
        if (current != null && current.terminal()) {
            return;
        }
        long now = clock.millis();
        if (state == JobState.SUCCEEDED) {
            failOpenItems(jobId, "任务已结束但这张表没有上报结果", now);
        } else {
            failOpenItems(jobId, error == null || error.isBlank() ? "任务在执行侧失败" : error, now);
        }
        Job status = statusFromItems(jobId);
        job.setStatus(status.name());
        if (error != null && !error.isBlank()) {
            job.setErrorMessage(truncate(error, ERROR_LIMIT));
        }
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        jobRepository.save(job);
        log.info("optimization batch {} finished as {}", jobId, status);
        publishFinished(job, "executor");
    }

    /** 超时收尾：与执行侧失败同一套处置，只是原因不同。 */
    @Transactional
    public void markTimedOut(String jobId) {
        long now = clock.millis();
        failOpenItems(jobId, "批次超过配置的超时时间仍未结束", now);
        OptimizationJobEntity job = require(jobId);
        Job status = statusFromItems(jobId);
        job.setStatus(status == Job.SUCCESS ? Job.PARTIAL_SUCCESS.name() : status.name());
        job.setErrorMessage(truncate("批次超过 " + properties.getOptimization().getJobTimeout()
                + " 仍未结束，已按超时处理", ERROR_LIMIT));
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        jobRepository.save(job);
        publishFinished(job, "timeout");
    }

    /**
     * 明细上报。
     *
     * <p>幂等：同一张表重复上报以最后一次为准（任务重试、上报重发都会出现这种情况）。
     * 但已经终结的明细不接受改写——重跑一张表会产生**新批次**，历史批次的结果是事实记录，
     * 不该被后来的执行覆盖。
     */
    @Transactional
    public void report(String jobId, String itemId, ItemReportRequest request) {
        OptimizationJobItemEntity item = requireItem(jobId, itemId);
        Item current = Item.parse(item.getStatus());
        if (current != null && current.terminal()) {
            throw ApiException.badRequest("item already finished as " + item.getStatus());
        }
        Item status = Item.parse(request.status());
        if (status == null || status.active()) {
            throw ApiException.badRequest("status must be one of: SUCCESS, FAILED, SKIPPED");
        }
        long now = clock.millis();
        item.setStatus(status.name());
        item.setMetrics(request.metrics() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request.metrics()));
        item.setErrorMessage(truncate(request.errorMessage(), ERROR_LIMIT));
        if (item.getStartedAt() == null) {
            item.setStartedAt(now);
        }
        item.setFinishedAt(now);
        item.setReportedAt(now);
        item.setUpdatedAt(now);
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
        return createJob(origin.getCatalogId(), origin.getTriggerType(), tables, origin.getId(), principal);
    }

    /** 取消批次：执行侧资源一并删掉，避免任务继续跑并写数据。 */
    @Transactional
    public OptimizationJobEntity cancel(String jobId, String principal) {
        OptimizationJobEntity job = require(jobId);
        if (Job.parse(job.getStatus()).terminal()) {
            throw ApiException.badRequest("batch already finished as " + job.getStatus());
        }
        String error = null;
        if (job.getResource() != null) {
            try {
                submitter.cancel(job.getNamespace(), job.getResource());
            } catch (RuntimeException exception) {
                // 取消失败不改状态：批次确实不该继续跑了，但资源可能还在。
                // 把原因记在批次上，运维照着资源名手工清理即可。
                error = "执行侧资源取消失败（" + job.getNamespace() + "/" + job.getResource() + "）："
                        + exception.getMessage();
                log.warn("cannot cancel optimization batch {}: {}", jobId, exception.getMessage());
            }
        }
        long now = clock.millis();
        failOpenItems(jobId, "批次已被取消", now);
        job.setStatus(Job.CANCELED.name());
        job.setErrorMessage(truncate(error, ERROR_LIMIT));
        job.setFinishedAt(now);
        job.setUpdatedAt(now);
        job.setUpdatedBy(principal);
        jobRepository.save(job);
        publishFinished(job, "canceled");
        return job;
    }

    // ------------------------------------------------------------------ 查询

    /** 批次列表，最近的在前。不返回表级明细（列表页要的是批次级信息）。 */
    @Transactional(readOnly = true)
    public List<OptimizationDtos.Job> list(String catalogName, String status, Action action, int limit) {
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

    private void failOpenItems(String jobId, String error, long now) {
        for (OptimizationJobItemEntity item : itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId)) {
            Item status = Item.parse(item.getStatus());
            if (status != null && status.terminal()) {
                continue;
            }
            item.setStatus(Item.FAILED.name());
            item.setErrorMessage(truncate(error, ERROR_LIMIT));
            item.setFinishedAt(now);
            item.setUpdatedAt(now);
            itemRepository.save(item);
        }
    }

    private Job statusFromItems(String jobId) {
        long success = 0;
        long failed = 0;
        for (OptimizationJobItemEntity item : itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId)) {
            if (Item.SUCCESS.name().equals(item.getStatus())) {
                success++;
            } else if (Item.FAILED.name().equals(item.getStatus())) {
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
     * （{@code catalogRoleIdsOf}）也都按名字走。对外给 id 会让调用方被迫再做一次
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
     * 批次到达终态后发一条 {@code optimization.job_finished} 事件。
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
        events.publish(CatalogEventType.OPTIMIZATION_JOB_FINISHED, catalogName(job.getCatalogId()),
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
