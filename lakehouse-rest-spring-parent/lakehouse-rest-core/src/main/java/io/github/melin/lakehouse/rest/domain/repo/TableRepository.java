package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TableRepository extends JpaRepository<TableEntity, String> {

    Optional<TableEntity> findByCatalogIdAndDatabaseIdAndName(String catalogId, String databaseId, String name);

    boolean existsByDatabaseIdAndName(String databaseId, String name);

    List<TableEntity> findAllByDatabaseIdOrderByNameAsc(String databaseId);

    List<TableEntity> findAllByCatalogIdOrderByDatabaseIdAscNameAsc(String catalogId);

    /**
     * Iceberg 表的元数据提交：一次**带 CAS 条件**的更新。
     *
     * <p>为什么要一条手写的批量更新，而不是改实体再 {@code save}：JPA 的脏检查只会发出
     * {@code update ... where id = ?}，两个并发提交会互相覆盖，而 Iceberg 的客户端
     * **假定服务端会拒绝过期提交**（它靠 {@code requirements} 做乐观并发，服务端这一侧
     * 若没有对应的一道闸，两个 writer 同时提交时会静默丢掉一次）。
     *
     * <p>CAS 的条件是 {@code metadata_location}：它就是「客户端上次看到的那一份元数据」。
     * 影响 0 行说明期间被别人提交过，调用方据此回 409
     * （{@code CommitFailedException}）。这与优化批次里
     * {@code update ... where id = ? and next_run_at = ?} 抢占是同一个手法，
     * 多副本部署下不引入任何锁组件。
     *
     * <p>{@code expected} 为空表示「这张表还没有元数据文件」——那只会发生在建表那一刻，
     * 而建表走的是 {@code save}（新行插入），因此这里的 {@code null} 分支是防御性的。
     *
     * <p>{@code clearAutomatically = true}：批量更新绕过持久化上下文，同一次事务里
     * 之前 load 出来的实体已经过期，必须清掉，否则后续任何一次脏检查写出旧值，
     * 就会把刚提交的元数据**改回去**。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TableEntity t set"
            + " t.metadataLocation = :metadataLocation,"
            + " t.metadataDoc = :metadataDoc,"
            + " t.tableUuid = :tableUuid,"
            + " t.formatVersion = :formatVersion,"
            + " t.schemaId = :schemaId,"
            + " t.schemaDoc = :schemaDoc,"
            + " t.tableType = :tableType,"
            + " t.path = :path,"
            + " t.latestSnapshotId = :latestSnapshotId,"
            + " t.latestSnapshotUuid = :latestSnapshotUuid,"
            + " t.updatedAt = :updatedAt,"
            + " t.updatedBy = :updatedBy"
            + " where t.id = :id"
            + " and (t.metadataLocation = :expected or (t.metadataLocation is null and :expected is null))")
    int updateIcebergMetadata(@Param("id") String id,
                              @Param("expected") String expectedMetadataLocation,
                              @Param("metadataLocation") String metadataLocation,
                              @Param("metadataDoc") String metadataDoc,
                              @Param("tableUuid") String tableUuid,
                              @Param("formatVersion") Integer formatVersion,
                              @Param("schemaId") long schemaId,
                              @Param("schemaDoc") String schemaDoc,
                              @Param("tableType") String tableType,
                              @Param("path") String path,
                              @Param("latestSnapshotId") Long latestSnapshotId,
                              @Param("latestSnapshotUuid") String latestSnapshotUuid,
                              @Param("updatedAt") long updatedAt,
                              @Param("updatedBy") String updatedBy);
}
