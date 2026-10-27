package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.TableOptimizationEntity;
import io.github.melin.lakehouse.rest.domain.repo.TableOptimizationRepository;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 扫描到期策略，按 catalog 交给 {@link OptimizationBatchCreator} 出批。
 *
 * <p><b>为什么按 catalog 攒、而不是一张表一个任务。</b>一个 Spark 任务的固定开销
 * （pod 调度、镜像拉取、SparkContext 启动）与它处理几张表无关：一表一任务在几十张表
 * 的规模下就会把 k8s 调度打满，而每张表的优化本身是秒级到分钟级的轻活。
 * 按 catalog 分组是因为同一 catalog 的表共享仓库与凭据——任务建一次 REST catalog
 * 就能处理完全部表，跨 catalog 混批则要在同一个会话里切换凭据，得不偿失。
 *
 * <p><b>本类刻意不加 {@code @Transactional}。</b>上一版把整轮扫描包在一个事务里，
 * 单副本时无所谓；多副本下配额闸门要在「catalog 行锁」内数一遍活跃批次，
 * 如果锁在一个覆盖 N 个 catalog 的大事务里，两台机器就会在整轮扫描上互相排队，
 * 而排队期间锁一直持有到方法结束。现在拆成：扫一遍（只读）→ 每个 catalog 一个
 * 独立事务（{@link OptimizationBatchCreator#createForCatalog}）。
 *
 * <p>出批的每一步都遵循「宁可少出一批，不可重复执行」：
 * <ul>
 *   <li>抢占用条件更新（{@link TableOptimizationRepository#claim}），多副本部署下
 *       同一张表只会被一个实例拿走；
 *   <li>并发上限先于抢占判断——拿不到执行配额就不动游标，下一拍再来（抢占后才被拒
 *       会把这一轮的调度机会白白吃掉）；
 *   <li>单批表数上限用「不再往里加」而不是「报错」，剩下的表留在原地等下一拍。
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class OptimizationBatchService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationBatchService.class);

    private final TableOptimizationRepository policyRepository;
    private final OptimizationBatchCreator batchCreator;
    private final ExecutionClusterService clusters;
    private final Clock clock;

    /**
     * 扫描一遍到期策略并出批，返回创建的批次数。
     *
     * <p>没有可出的批次是常态（每分钟一拍，而策略的小时间隔多以小时计），
     * 因此这里的每一步都以「快速返回」为先：没有到期策略时连分组都不做。
     */
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
            if (batchCreator.createForCatalog(catalogId, group.getValue(), now)) {
                created++;
            }
        }
        if (created > 0) {
            log.info("optimization scheduler created {} batch(es) for {} due table(s)", created, due.size());
        }
        return created;
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
