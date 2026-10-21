package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.LineageRunEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 血缘执行记录的读写口。
 *
 * <p><b>过滤用 {@link JpaSpecificationExecutor} 而不是派生方法。</b>运行历史的过滤维度是
 * 「ref × engine × since × 游标」的组合，派生查询要写十几个方法名才覆盖；
 * 而每个谓词都是可选的，拼条件比枚举方法名更贴近它的形状。
 *
 * <p>游标是 {@code (finishedAt, id)} 的复合值，不是单独的时间戳：同一毫秒里可能落两条 run，
 * 只按时间翻页会漏行或重行（与 {@code event-framework.md} §4 的口径一致）。
 */
public interface LineageRunRepository extends JpaRepository<LineageRunEntity, Long>,
        JpaSpecificationExecutor<LineageRunEntity> {

    /** 幂等键查询。重复 {@code POST} 同一个 {@code runId} 靠它命中。 */
    boolean existsByRunId(String runId);

    java.util.Optional<LineageRunEntity> findByRunId(String runId);

    /**
     * 清理过期 run（保留天数来自 {@code lakehouse.rest.lineage.run-retention-days}）。
     *
     * <p>只删 run，不删边：边是「现在谁依赖谁」的状态，它不是历史（§7.5）。
     *
     * <p>同样不加 {@code clearAutomatically}（理由见 {@code LineageTableEdgeRepository#deleteByRef}）：
     * 清理任务今天是自成一个事务，但"谁会在什么事务里调它"不该由这里假设。
     */
    @Modifying
    @Query("delete from LineageRunEntity r where r.finishedAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") long cutoff);
}
