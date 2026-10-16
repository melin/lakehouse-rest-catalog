package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.OptimizationJobItemEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/** 批次的表级明细读写。 */
public interface OptimizationJobItemRepository extends JpaRepository<OptimizationJobItemEntity, String> {

    List<OptimizationJobItemEntity> findAllByJobIdOrderByCreatedAtAsc(String jobId);

    List<OptimizationJobItemEntity> findAllByJobIdAndStatus(String jobId, String status);

    /** 单表的历史（表详情页三块历史的数据源），最近在前。 */
    List<OptimizationJobItemEntity> findTop100ByTableIdOrderByCreatedAtDesc(String tableId);

    /** 该表是否有未终结的明细：有则不再入批。 */
    List<OptimizationJobItemEntity> findAllByTableIdInAndStatusIn(Collection<String> tableIds,
                                                                 Collection<String> statuses);

    /**
     * 这一批表的「最近执行记录」，窗口由调用方按表内各项动作的最大执行间隔算出来。
     *
     * <p>动作级的执行间隔（快照清理每天、孤儿清理每周）靠它判断「到点没」。
     * 窗口开大了会把历史明细全捞回来，开小了会把「上周清过孤儿文件」误判成从没跑过——
     * 因此窗口必须由调用方按**实际启用的动作**算，这里只负责按窗口过滤。
     */
    List<OptimizationJobItemEntity> findAllByTableIdInAndStatusInAndFinishedAtGreaterThanEqual(
            Collection<String> tableIds, Collection<String> statuses, long since);
}
