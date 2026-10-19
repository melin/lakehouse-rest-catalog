package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * SQL 视图。
 *
 * <p>{@code viewSchemaDoc} 同时保存字段列表、默认查询语句与各方言查询语句，
 * 以 JSON 文档形式存放。
 */
@Entity
@Table(name = "lakehouse_view",
        uniqueConstraints = @UniqueConstraint(name = "uk_view", columnNames = {"catalog_id", "database_id", "name"}),
        indexes = @Index(name = "ix_view_database", columnList = "database_id"),
        comment = "SQL 视图（含 Iceberg 视图的元数据文件指针）")
@Getter
@Setter
public class ViewEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false, comment = "所属 catalog id")
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false, comment = "所属库 id")
    private String databaseId;

    @Column(name = "name", length = 512, nullable = false, comment = "视图名，库内唯一")
    private String name;

    @Column(name = "view_schema_doc", length = 1048576, comment = "字段列表与各方言查询语句（JSON 文档）")
    private String viewSchemaDoc;

    /**
     * 当前元数据文件路径（Iceberg 视图用）：
     * {@code <location>/metadata/00001-<uuid>.metadata.json}。
     *
     * <p>Paimon 视图不写元数据文件（它是纯目录条目），这一列为 {@code null}。
     * 与 {@code lakehouse_table.metadata_location} 同一个角色：既是「引擎该去读哪个文件」的
     * 指针，也是乐观并发的 CAS 条件。
     */
    @Column(name = "metadata_location", length = 1024,
            comment = "Iceberg 视图的元数据文件路径；Paimon 视图为空")
    private String metadataLocation;
}
