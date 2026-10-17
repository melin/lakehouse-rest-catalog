package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.TableSnapshotEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableSnapshotRepository extends JpaRepository<TableSnapshotEntity, String> {

    Optional<TableSnapshotEntity> findByTableIdAndSnapshotId(String tableId, long snapshotId);

    Optional<TableSnapshotEntity> findByTableIdAndUuid(String tableId, String uuid);

    Optional<TableSnapshotEntity> findFirstByTableIdOrderBySnapshotIdDesc(String tableId);

    /**
     * 快照 id 严格小于给定值的最新一行：累计文件统计的基线。
     *
     * <p>不用「当前最新一行」是因为提交可能重放——同一个快照 id 被客户端重试时，
     * 基线必须还是它之前那一行，否则同一批增量会被算两遍。
     */
    Optional<TableSnapshotEntity> findFirstByTableIdAndSnapshotIdLessThanOrderBySnapshotIdDesc(
            String tableId, long snapshotId);

    /** {@code EARLIEST}：按快照 id 取最早的那个。 */
    Optional<TableSnapshotEntity> findFirstByTableIdOrderBySnapshotIdAsc(String tableId);

    List<TableSnapshotEntity> findAllByTableIdOrderBySnapshotIdDesc(String tableId);

    long deleteByTableIdAndSnapshotIdGreaterThan(String tableId, long snapshotId);

    /**
     * 删掉一张表的全部快照行。
     *
     * <p>给 Iceberg 面用：那张表的快照是 {@code metadata_doc} 的派生投影，
     * 删表时由 {@code IcebergProjections#deleteAll} 整批清掉。
     * Paimon 面的删表路径走的是按 id 逐个删（那里还要保留「目标之后」的语义），
     * 因此这条只服务 Iceberg 侧——但这不构成两条路径的语义分叉：
     * 两者都要求「表实体消失之后，快照行不留下孤儿」。
     */
    long deleteByTableId(String tableId);
}
