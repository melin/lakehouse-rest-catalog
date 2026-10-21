package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.LineageColumnEdgeEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 列级边的读写口。
 *
 * <p>upsert 的形状是「先按 (源表, 目标表) 这一对整批取回，再在内存里按
 * {@code (源列, 目标列)} 配对」：一列一查会在宽表上变成几百次往返，
 * 而一对表之间的列边本来就要整批写。查询次数因此是「源表数」而不是「列边数」。
 */
public interface LineageColumnEdgeRepository extends JpaRepository<LineageColumnEdgeEntity, Long> {

    /** upsert 前的整批读取。 */
    List<LineageColumnEdgeEntity> findByTargetRefHashAndSourceRefHash(String targetRefHash,
                                                                     String sourceRefHash);

    /** 上游：{@code ref} 这张表的列分别来自谁。 */
    List<LineageColumnEdgeEntity> findByTargetRefHash(String targetRefHash);

    /** 下游：{@code ref} 这张表的列流到了谁那里。 */
    List<LineageColumnEdgeEntity> findBySourceRefHash(String sourceRefHash);

    /** 单条命中（同一对列在一个 run 里重复出现时用，正常不该发生）。 */
    Optional<LineageColumnEdgeEntity> findBySourceRefHashAndSourceColumnAndTargetRefHashAndTargetColumn(
            String sourceRefHash, String sourceColumn, String targetRefHash, String targetColumn);

    /**
     * 一批目标表的列边（图上的 {@code columnCount} 用它算线宽）。
     *
     * <p>按 {@code in} 一次取回而不是逐边 {@code count}：一张 300 节点的图有几百条边，
     * 逐条 count 就是几百次查询，而这里取回的总行数在同一个量级。
     */
    List<LineageColumnEdgeEntity> findByTargetRefHashIn(Collection<String> targetRefHashes);

    /**
     * 表被删时级联删列边（与表级边同一个时机，见 {@code LineageEdgeCleanupListener}）。
     *
     * <p><b>故意不加 {@code clearAutomatically}。</b>这个方法是在<b>删表的事务里</b>被调用的
     * （{@code LineageEdgeCleanupListener} 是同步监听器）：{@code clear()} 会清空持久化上下文，
     * 把同一个事务里刚排队的 {@code delete projections} / {@code delete table} 一起丢掉。
     * 症状极难认：删表返回 204、表却还在（{@code IcebergRestCatalogApiTests} 与
     * {@code IcebergClientEndToEndTests} 就是这么红的），而日志里"清边成功、0 条"一切正常。
     * 反过来，"删完边后读到脏实体"在这里不成立：边是按 ref 现查的，上下文里本来就没有它们。
     */
    @Modifying
    @Query("delete from LineageColumnEdgeEntity e where e.sourceRef = :ref or e.targetRef = :ref")
    int deleteByRef(@Param("ref") String ref);
}
