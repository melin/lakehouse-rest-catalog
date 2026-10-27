package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobItemEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     * 一张表当前活跃的那条明细（手动入口要在锁内复查它）。
     *
     * <p>按主键倒序取第一条即可：同一张表在活跃状态下最多只有一条（调度侧靠
     * {@code claim} 与锁内复查保证），这里不追求「数学上唯一」，只要能在冲突时
     * 报出**一个**已有批次 id 供用户跳转。
     */
    Optional<OptimizationJobItemEntity> findFirstByTableIdAndStatusInOrderByCreatedAtDesc(
            String tableId, Collection<String> statuses);

    /**
     * 这一批表的「最近执行记录」，窗口由调用方按表内各项动作的最大执行间隔算出来。
     *
     * <p>动作级的执行间隔（快照清理每天、孤儿清理每周）靠它判断「到点没」。
     * 窗口开大了会把历史明细全捞回来，开小了会把「上周清过孤儿文件」误判成从没跑过——
     * 因此窗口必须由调用方按**实际启用的动作**算，这里只负责按窗口过滤。
     */
    List<OptimizationJobItemEntity> findAllByTableIdInAndStatusInAndFinishedAtGreaterThanEqual(
            Collection<String> tableIds, Collection<String> statuses, long since);

    /**
     * 上报结果的状态推进：只在明细还没终结时生效。
     *
     * <p>「先读状态再写」在多副本下不成立——两个回调（重试、重发、执行侧两次上报）
     * 同时到达时都能通过那个判断。改成条件更新后，后到的那个拿到 0 行，
     * 由调用方翻成 400「已终结」。
     *
     * <p>只改状态与三个时刻，{@code metrics} 是 JSON 列（有属性转换器），
     * 留给实体 {@code save} 写——批量 JPQL 里带转换器参数的行为在不同 Hibernate
     * 版本上不一致，不值得为了少一条语句去赌。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobItemEntity i"
            + " set i.status = :status, i.errorMessage = :error, i.finishedAt = :now,"
            + "     i.reportedAt = :now, i.updatedAt = :now"
            + " where i.id = :id and i.status not in :terminal")
    int report(@Param("id") String id, @Param("status") String status, @Param("error") String error,
               @Param("terminal") Collection<String> terminal, @Param("now") long now);
}
