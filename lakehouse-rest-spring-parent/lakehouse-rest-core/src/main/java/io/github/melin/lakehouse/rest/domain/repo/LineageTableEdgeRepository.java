package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.LineageTableEdgeEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 表级边的读写口。
 *
 * <p>所有查询都按 <b>ref 的摘要</b>走：图遍历从 root 出发，手里只有名字，
 * 而索引建在 {@code *_ref_hash} 上（全名没有索引，理由见实体注释）。
 * 因此调用方必须自己先算摘要——这不是泄漏实现细节，而是把「摘要即身份」
 * 这件事固定在 O(1) 的字符串运算上（{@code Digests.sha256Hex}）。
 */
public interface LineageTableEdgeRepository extends JpaRepository<LineageTableEdgeEntity, Long> {

    /** 上游：指向 {@code target} 的边。 */
    List<LineageTableEdgeEntity> findByTargetRefHash(String targetRefHash);

    List<LineageTableEdgeEntity> findByTargetRefHashAndEngine(String targetRefHash, String engine);

    /** 下游：从 {@code source} 出发的边。 */
    List<LineageTableEdgeEntity> findBySourceRefHash(String sourceRefHash);

    List<LineageTableEdgeEntity> findBySourceRefHashAndEngine(String sourceRefHash, String engine);

    /**
     * 一批节点的上游边（{@code more} 标志用）：边界层每个节点问一次「再走一跳还有没有」，
     * 逐个查是 N 次往返，一次 {@code in} 是一条。
     */
    List<LineageTableEdgeEntity> findByTargetRefHashIn(Collection<String> targetRefHashes);

    List<LineageTableEdgeEntity> findBySourceRefHashIn(Collection<String> sourceRefHashes);

    /** upsert 的命中查询：唯一键的三段。 */
    Optional<LineageTableEdgeEntity> findBySourceRefHashAndTargetRefHashAndEngine(
            String sourceRefHash, String targetRefHash, String engine);

    /**
     * 删一张表的全部边（{@code table.dropped} / {@code table.unregistered} 时级联）。
     *
     * <p>两个方向都删：表没了，它作为源的那条边也不再是「现在谁依赖谁」。
     * 回边（{@code A → B → A}）因此会留一条断头边，那是正确的——另一半已经不存在了。
     *
     * <p><b>故意不加 {@code clearAutomatically}。</b>这个方法是在<b>删表的事务里</b>被调用的
     * （{@code LineageEdgeCleanupListener} 是同步监听器）：{@code clear()} 会清空持久化上下文，
     * 把同一个事务里刚排队的 {@code delete projections} / {@code delete table} 一起丢掉。
     * 症状极难认：删表返回 204、表却还在（{@code IcebergRestCatalogApiTests} 与
     * {@code IcebergClientEndToEndTests} 就是这么红的），而日志里"清边成功、0 条"一切正常。
     * 反过来，"删完边后读到脏实体"在这里不成立：边是按 ref 现查的，上下文里本来就没有它们。
     */
    @Modifying
    @Query("delete from LineageTableEdgeEntity e where e.sourceRef = :ref or e.targetRef = :ref")
    int deleteByRef(@Param("ref") String ref);
}
