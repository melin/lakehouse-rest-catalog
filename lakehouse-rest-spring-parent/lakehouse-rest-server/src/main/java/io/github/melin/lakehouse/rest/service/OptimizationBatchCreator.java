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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 为一个 catalog 出一次批。
 *
 * <p><b>为什么从 {@code OptimizationBatchService} 里拆出来。</b>配额闸门要「先锁 catalog 行、
 * 再数活跃批次、再建批次」，这三步必须在**同一个事务**里，而锁的范围必须小到一个 catalog——
 * 不能是整轮扫描。拆分有两种做法：注入 {@code TransactionTemplate} 或搬进独立 bean。
 * 选后者是因为「Spring 的自调用不走代理」这件事太容易踩：把 {@code createBatch} 标成
 * {@code REQUIRES_NEW} 留在原类里，{@code this.createBatch(...)} 会绕过代理，
 * 于是它仍在大事务里，锁一直持有到扫描结束——两台机器就开始互相排队。
 *
 * <p><b>锁顺序：catalog → policy/明细。</b>先 {@code lockById(catalogId)}，再对策略行做
 * 条件更新（{@code claim}）。「立即优化」与「重试」走同样的顺序，因此三条路径不会交叉死锁。
 *
 * <p>事务里没有外部调用：批次的**提交**是另一个节拍的事（{@code OptimizationDispatcher}），
 * 这里只落库，所以锁只持有毫秒级。
 */
@Service
@RequiredArgsConstructor
public class OptimizationBatchCreator {

    private static final Logger log = LoggerFactory.getLogger(OptimizationBatchCreator.class);

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
     * 为 catalog 里这一组到期策略出一个批次；没有可跑的表时只推进游标。
     *
     * <p>{@code REQUIRES_NEW}：调用方（扫描循环）本身不持有事务，每个 catalog 各自一个事务。
     * 这样两台机器只在这一个 catalog 上排队，其余 catalog 照常并行。
     *
     * @return 是否真的创建了批次
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean createForCatalog(String catalogId, List<TableOptimizationEntity> policies, long now) {
        // 锁在这一行上，是「配额不超额」的唯一保证：count 与 create 之间的窗口被它关掉
        CatalogEntity catalog = catalogRepository.lockById(catalogId).orElse(null);
        if (catalog == null) {
            log.warn("skip {} optimization policies: catalog {} no longer exists", policies.size(), catalogId);
            return false;
        }
        // 配额在锁内数：一个 catalog 跑满不该把别的 catalog 的批次也堵住，所以按 catalog 数
        long active = jobRepository.countByCatalogIdAndStatusIn(catalogId, Job.activeNames());
        if (active >= clusters.maxConcurrentJobs(catalogId)) {
            log.debug("optimization concurrency limit ({}) of catalog {} reached, defer its {} due table(s)",
                    active, catalogId, policies.size());
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
            List<Action> actions = TableOptimizationService.enabledActions(policy, format);
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
                : itemRepository.findAllByTableIdInAndStatusInAndFinishedAtGreaterThanEqual(
                        tableIds, SETTLED_ITEMS, since)) {
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
}
