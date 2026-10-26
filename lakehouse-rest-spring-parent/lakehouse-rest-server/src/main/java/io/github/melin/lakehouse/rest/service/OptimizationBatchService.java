package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.DatabaseEntity;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobItemEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableOptimizationEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobItemRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableOptimizationRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.service.OptimizationJobService.BatchTable;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.OptimizationOptions;
import io.github.melin.lakehouse.rest.support.OptimizationOptions.Action;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Item;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Job;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 把到期的优化策略攒成批次。
 *
 * <p><b>为什么按 catalog 攒、而不是一张表一个任务。</b>一个 Spark 任务的固定开销
 * （pod 调度、镜像拉取、SparkContext 启动）与它处理几张表无关：一表一任务在几十张表
 * 的规模下就会把 k8s 调度打满，而每张表的优化本身是秒级到分钟级的轻活。
 * 按 catalog 分组是因为同一 catalog 的表共享仓库与凭据——任务建一次 REST catalog
 * 就能处理完全部表，跨 catalog 混批则要在同一个会话里切换凭据，得不偿失。
 *
 * <p>出批的每一步都遵循「宁可少出一批，不可重复执行」：
 * <ul>
 *   <li>抢占用条件更新（{@link TableOptimizationRepository#claim}），多副本部署下
 *       同一张表只会被一个实例拿走；
 *   <li>并发上限先于抢占判断——拿不到执行配额就不动游标，下一拍再来（抢占后才被拒
 *       会把这一轮的调度机会白白吃掉）；
 *   <li>单批表数上限用「不再往里加」而不是「报错」，剩下的表留在原地等下一拍。
 * </ul>
 *
 * <p>整个扫描在一个事务里：批次与明细必须原子落库，而一次扫描的行数是可控的
 * （到期策略取前 500 条、单批上限几十张表）。把「落库」与「提交」拆成两个节拍之后，
 * 这个事务里没有任何外部调用，不会长事务占着连接等网络。
 */
@Service
@RequiredArgsConstructor
public class OptimizationBatchService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationBatchService.class);

    /** 明细里算作「这张表正忙」的状态。 */
    private static final List<String> ACTIVE_ITEMS = List.of(Item.PENDING.name(), Item.RUNNING.name());
    /** 明细里算作「跑过一次」的状态：跳过也是跑过（到点判定要认它）。 */
    private static final List<String> SETTLED_ITEMS =
            List.of(Item.SUCCESS.name(), Item.SKIPPED.name(), Item.FAILED.name());

    private final TableOptimizationRepository policyRepository;
    private final OptimizationJobRepository jobRepository;
    private final OptimizationJobItemRepository itemRepository;
    private final TableRepository tableRepository;
    private final DatabaseRepository databaseRepository;
    private final CatalogRepository catalogRepository;
    private final OptimizationConfigResolver configResolver;
    private final OptimizationJobService jobService;
    private final ExecutionClusterService clusters;
    private final Clock clock;

    /**
     * 扫描一遍到期策略并出批，返回创建的批次数。
     *
     * <p>没有可出的批次是常态（每分钟一拍，而策略的小时间隔多以小时计），
     * 因此这里的每一步都以「快速返回」为先：没有到期策略时连分组都不做。
     */
    @Transactional
    public int scanAndCreateBatches() {
        long now = clock.millis();
        initializeCursors(now);
        List<TableOptimizationEntity> due =
                policyRepository.findTop500ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(now);
        if (due.isEmpty()) {
            return 0;
        }
        Map<String, List<TableOptimizationEntity>> groups = new LinkedHashMap<>();
        for (TableOptimizationEntity policy : due) {
            groups.computeIfAbsent(policy.getCatalogId(), key -> new ArrayList<>()).add(policy);
        }
        int created = 0;
        for (Map.Entry<String, List<TableOptimizationEntity>> group : groups.entrySet()) {
            String catalogId = group.getKey();
            // 不可派发的 catalog **整组跳过，并且不动游标**：它们的策略保持「已到期」，
            // 于是配好执行集群之后的下一拍就会立刻把该跑的表跑掉。若这里消耗掉游标，
            // 「补完配置还要再多等一个调度周期」这件事没人能提前知道。
            if (!clusters.dispatchable(catalogId)) {
                log.debug("catalog {} has no dispatchable execution cluster, defer its {} due table(s)",
                        catalogId, group.getValue().size());
                continue;
            }
            // 配额也按 catalog 数：一个 catalog 跑满不该把别的 catalog 的批次也堵住
            long active = jobRepository.countByCatalogIdAndStatusIn(catalogId, Job.activeNames());
            if (active >= clusters.maxConcurrentJobs(catalogId)) {
                log.debug("optimization concurrency limit ({}) of catalog {} reached, defer its {} due table(s)",
                        active, catalogId, group.getValue().size());
                continue;
            }
            if (createBatch(catalogId, group.getValue(), now)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("optimization scheduler created {} batch(es) for {} due table(s)", created, due.size());
        }
        return created;
    }

    /** 为 catalog 里这一组到期策略出一个批次；没有可跑的表时只推进游标。 */
    private boolean createBatch(String catalogId, List<TableOptimizationEntity> policies, long now) {
        CatalogEntity catalog = catalogRepository.findById(catalogId).orElse(null);
        if (catalog == null) {
            log.warn("skip {} optimization policies: catalog {} no longer exists", policies.size(), catalogId);
            return false;
        }
        Map<String, String> catalogProperties = catalog.getProperties();
        // 一个 catalog 只有一种格式（catalogFormat 创建后不可变），因此整组共用一份，
        // 不必逐表判断——逐表判断反而会掩盖「同一 catalog 里混了两种格式的表」这种脏数据。
        CatalogFormat format = CatalogFormat.of(catalog.getCatalogFormat());
        List<String> tableIds = policies.stream().map(TableOptimizationEntity::getTableId).toList();
        Map<String, TableEntity> tables = new HashMap<>();
        for (TableEntity table : tableRepository.findAllById(tableIds)) {
            tables.put(table.getId(), table);
        }
        Set<String> databaseIds = new LinkedHashSet<>();
        tables.values().forEach(table -> databaseIds.add(table.getDatabaseId()));
        Map<String, String> databaseNames = new HashMap<>();
        for (DatabaseEntity database : databaseRepository.findAllById(databaseIds)) {
            databaseNames.put(database.getId(), database.getName());
        }
        Set<String> busy = new LinkedHashSet<>();
        for (OptimizationJobItemEntity item
                : itemRepository.findAllByTableIdInAndStatusIn(tableIds, ACTIVE_ITEMS)) {
            busy.add(item.getTableId());
        }

        // 生效配置先算一遍：既用于判「到点没」，也用于算需要的查询窗口
        Map<String, Map<String, Map<String, String>>> effective = new HashMap<>();
        Map<String, List<Action>> enabled = new HashMap<>();
        long windowMillis = 0;
        for (TableOptimizationEntity policy : policies) {
            List<Action> actions = TableOptimizationService.enabledActions(policy);
            enabled.put(policy.getId(), actions);
            Map<String, Map<String, String>> resolved = configResolver.resolve(format, catalogProperties,
                    OptimizationConfigResolver.tableConfigs(policy));
            effective.put(policy.getId(), resolved);
            for (Action action : actions) {
                windowMillis = Math.max(windowMillis,
                        OptimizationOptions.interval(format, action, resolved.get(action.wire())).toMillis());
            }
        }
        // 窗口至少留 1 小时：全部动作都「刚跑过」时窗口为 0 会把历史全滤掉，
        // 于是每张表都被判成「从没跑过」，动作间隔形同虚设。
        long since = now - Math.max(windowMillis, 3_600_000L);
        Map<String, Map<String, Long>> lastRuns = lastRuns(tableIds, since);

        List<BatchTable> batch = new ArrayList<>();
        for (TableOptimizationEntity policy : policies) {
            TableEntity table = tables.get(policy.getTableId());
            if (table == null) {
                log.debug("skip optimization policy {}: table {} no longer exists", policy.getId(), policy.getTableId());
                continue;
            }
            // 策略是写入时校验的，但表可以在之后被改成外部表（Iceberg 的 register 就是这条路），
            // 也可以因为脏数据落在另一种格式上。这里再过一道：进批之后再被服务端拒绝，
            // 结果是整批失败，代价比跳过一次调度大得多。
            if (!TableOptimizationService.isOptimizable(table)) {
                log.debug("skip table {}: it is not optimizable any more (type={}, external={})",
                        table.getId(), table.getTableType(), table.isExternal());
                continue;
            }
            if (busy.contains(policy.getTableId())) {
                log.debug("skip table {}: an optimization item is still running", policy.getTableId());
                continue;
            }
            List<Action> dueActions = new ArrayList<>();
            for (Action action : enabled.getOrDefault(policy.getId(), List.of())) {
                long last = lastRuns.getOrDefault(policy.getTableId(), Map.of()).getOrDefault(action, 0L);
                long interval = OptimizationOptions
                        .interval(format, action, effective.get(policy.getId()).get(action.wire()))
                        .toMillis();
                if (last == 0L || now - last >= interval) {
                    dueActions.add(action);
                }
            }
            long next = now + policy.getScheduleIntervalSeconds() * 1000L;
            if (dueActions.isEmpty()) {
                // 没有到点的动作也要推进游标：否则每一拍都会把这张表重新算一遍，
                // 而它的下一个动作可能几小时后才到点。
                policyRepository.claim(policy.getId(), policy.getNextRunAt(), next);
                continue;
            }
            if (batch.size() >= clusters.maxTablesPerJob(catalogId)) {
                log.debug("optimization batch for catalog {} is full ({}), defer the rest to the next tick",
                        catalogId, batch.size());
                break;
            }
            if (policyRepository.claim(policy.getId(), policy.getNextRunAt(), next) != 1) {
                log.debug("table {} was claimed by another scheduler tick", policy.getTableId());
                continue;
            }
            String fullName = catalog.getPrefix() + "." + databaseNames.get(table.getDatabaseId())
                    + "." + table.getName();
            batch.add(new BatchTable(table.getId(), fullName, dueActions));
        }
        if (batch.isEmpty()) {
            return false;
        }
        OptimizationJobEntity job = jobService.createJob(catalogId, OptimizationJobService.TRIGGER_SCHEDULED,
                batch, null, OptimizationJobService.SCHEDULER_PRINCIPAL);
        policyRepository.assignJob(job.getId(), batch.stream().map(BatchTable::tableId).toList());
        return true;
    }

    /** 从明细里折算「每张表每个动作最近一次跑完的时间」。 */
    private Map<String, Map<String, Long>> lastRuns(Collection<String> tableIds, long since) {
        Map<String, Map<String, Long>> result = new HashMap<>();
        for (OptimizationJobItemEntity item
                : itemRepository.findAllByTableIdInAndStatusInAndFinishedAtGreaterThanEqual(tableIds, SETTLED_ITEMS, since)) {
            if (item.getFinishedAt() == null) {
                continue;
            }
            Map<String, Long> perAction = result.computeIfAbsent(item.getTableId(), key -> new HashMap<>());
            for (Action action : OptimizationOptions.fromWire(item.getActions())) {
                perAction.merge(action.wire(), item.getFinishedAt(), Math::max);
            }
        }
        return result;
    }

    /**
     * 给从未调度过的策略补上游标。
     *
     * <p>只有两种策略会是这个状态：本功能上线前建的（不存在），以及策略行是手工插入的。
     * 补成「立刻到期」，让它们在下一次扫描被看见——否则它们的 {@code nextRunAt} 永远是
     * null，扫描的 {@code <= now} 条件永远不成立，表就再也不会被优化。
     */
    private void initializeCursors(long now) {
        List<TableOptimizationEntity> fresh = policyRepository.findTop500ByNextRunAtIsNull();
        for (TableOptimizationEntity policy : fresh) {
            policyRepository.initializeCursor(policy.getId(), now);
        }
        if (!fresh.isEmpty()) {
            log.info("initialized {} optimization cursor(s)", fresh.size());
        }
    }
}
