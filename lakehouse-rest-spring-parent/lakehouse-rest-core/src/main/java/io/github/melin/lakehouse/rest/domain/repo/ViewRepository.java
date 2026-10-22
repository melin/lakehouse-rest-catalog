package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.ViewEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ViewRepository extends JpaRepository<ViewEntity, String> {

    Optional<ViewEntity> findByCatalogIdAndDatabaseIdAndName(String catalogId, String databaseId, String name);

    boolean existsByDatabaseIdAndName(String databaseId, String name);

    List<ViewEntity> findAllByDatabaseIdOrderByNameAsc(String databaseId);

    List<ViewEntity> findAllByCatalogIdOrderByDatabaseIdAscNameAsc(String catalogId);

    /**
     * Iceberg 视图的元数据提交：一次**带 CAS 条件**的更新。
     *
     * <p>与 {@code TableRepository#updateIcebergMetadata} 同一形态、同一理由
     * （那一条的注释写了完整的取舍，这里不重复）：JPA 的脏检查发出的
     * {@code update ... where id = ?} 会让两个并发提交互相覆盖，而 Iceberg 客户端
     * 假定服务端会拒绝过期提交。CAS 的条件是 {@code metadata_location}，
     * 影响 0 行时调用方回 409 {@code CommitFailedException}。
     *
     * <p>{@code clearAutomatically = true} 同理：批量更新绕过持久化上下文，
     * 之前 load 出来的视图实体已过期，不清掉的话后续任何一次脏检查会把刚提交的
     * 元数据改回去。
     *
     * <p>这里只写两列（元数据文件位置与 {@code ViewMetadata} 文档）。视图没有
     * 快照、没有 refs、没有数据文件，因此不像表那样还要同步一批投影列——
     * 这是 {@code view_schema_doc} 复用为 ViewMetadata JSON 的直接收益。
     *
     * <p><b>{@code updatedBy} 必须由调用方先归一化</b>（用
     * {@code RequestContext.auditPrincipal()}）：批量更新不触发
     * {@code AuditedEntity} 的 {@code @PreUpdate}，原始主体名会原样撞
     * {@code varchar(255)}。理由同 {@code TableRepository#updateIcebergMetadata}。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update ViewEntity v set"
            + " v.metadataLocation = :metadataLocation,"
            + " v.viewSchemaDoc = :viewSchemaDoc,"
            + " v.updatedAt = :updatedAt,"
            + " v.updatedBy = :updatedBy"
            + " where v.id = :id"
            + " and (v.metadataLocation = :expected or (v.metadataLocation is null and :expected is null))")
    int updateViewMetadata(@Param("id") String id,
                           @Param("expected") String expectedMetadataLocation,
                           @Param("metadataLocation") String metadataLocation,
                           @Param("viewSchemaDoc") String viewSchemaDoc,
                           @Param("updatedAt") long updatedAt,
                           @Param("updatedBy") String updatedBy);
}
