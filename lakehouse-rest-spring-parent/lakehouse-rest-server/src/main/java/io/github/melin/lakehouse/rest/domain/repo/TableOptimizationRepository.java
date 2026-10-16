package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.TableOptimizationEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** 表存储优化策略的读写。 */
public interface TableOptimizationRepository extends JpaRepository<TableOptimizationEntity, String> {

    Optional<TableOptimizationEntity> findByTableId(String tableId);

    List<TableOptimizationEntity> findAllByCatalogId(String catalogId);

    /**
     * 取出到期且已启用的策略，按到期时间升序。
     *
     * <p>加 {@code Top500} 上限：调度节拍是分钟级的，一次扫描处理不完的积压
     * 应该落到下一拍，而不是让一个节拍跑成长事务。积压表的 {@code nextRunAt}
     * 保持不变，下一拍自然还能取到。
     */
    List<TableOptimizationEntity> findTop500ByEnabledTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(long now);

    /** 未启用也从未跑过的策略只需要初始化游标，不产生批次。 */
    List<TableOptimizationEntity> findTop500ByNextRunAtIsNull();

    List<TableOptimizationEntity> findAllByTableIdIn(Collection<String> tableIds);

    /**
     * 抢占一次调度机会：只有当游标仍是扫描时读到的那个值时才推进它。
     *
     * <p>返回 1 表示抢到了，0 表示另一个实例（或另一个节拍）已经推进过——
     * 调用方必须把这张表从本批里剔除。多副本部署下的互斥就靠这一条，
     * 不引入额外锁或选举组件：出批本身就是一次条件更新，天然适合乐观抢占。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TableOptimizationEntity p set p.nextRunAt = :next"
            + " where p.id = :id and p.nextRunAt = :expected")
    int claim(@Param("id") String id, @Param("expected") Long expected, @Param("next") long next);

    /** 初始化从未调度过的策略游标（{@code nextRunAt} 为 null）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TableOptimizationEntity p set p.nextRunAt = :next where p.id = :id and p.nextRunAt is null")
    int initializeCursor(@Param("id") String id, @Param("next") long next);

    /**
     * 回填「这张表进了哪个批次」。
     *
     * <p>用一条批量更新而不是逐行 {@code save}：{@link #claim} 带 {@code clearAutomatically}，
     * 扫描时读到的那批实体在抢占之后已经处于游离态，再把它们 merge 回库会把内存里
     * 过期的 {@code next_runAt} 一起写回去——等于把刚抢到的调度机会还回去。
     * 只改这一个列，绕开整实体写回。
     *
     * <p>条件是 {@code table_id} 而不是策略行的主键：调用方手里有的是入批的**表 id**
     * （{@code BatchTable.tableId}），策略主键是另一套值。写成 {@code p.id in :ids}
     * 是一条永远匹配不到行的更新——不报错，只是「最近批次」这一列永远为空。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TableOptimizationEntity p set p.lastJobId = :jobId where p.tableId in :ids")
    int assignJob(@Param("jobId") String jobId, @Param("ids") Collection<String> ids);
}
