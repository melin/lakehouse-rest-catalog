package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.OptimizationJobEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 优化批次的读写。 */
public interface OptimizationJobRepository extends JpaRepository<OptimizationJobEntity, String> {

    List<OptimizationJobEntity> findAllByOrderByCreatedAtDesc();

    List<OptimizationJobEntity> findAllByCatalogIdOrderByCreatedAtDesc(String catalogId);

    /** 待提交与运行中的批次，调度节拍按状态各取一批。 */
    List<OptimizationJobEntity> findTop50ByStatusOrderByCreatedAtAsc(String status);

    List<OptimizationJobEntity> findTop50ByStatusInOrderByCreatedAtAsc(Collection<String> statuses);

    /** 某张表是否已有在跑的批次（防止同表重复入批）。 */
    Optional<OptimizationJobEntity> findFirstByCatalogIdAndStatusIn(String catalogId, Collection<String> statuses);

    long countByStatusIn(Collection<String> statuses);

    /**
     * 状态推进的条件更新：只有当前状态与读取时一致才改。
     *
     * <p>用于把「取一批 PENDING」与「我这一拍只提交其中一部分」之间的竞争
     * 收敛掉——两个节拍重叠时，后到的那个 update 返回 0，直接跳过该批次。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j set j.status = :next where j.id = :id and j.status = :expected")
    int transition(@Param("id") String id, @Param("expected") String expected, @Param("next") String next);
}
