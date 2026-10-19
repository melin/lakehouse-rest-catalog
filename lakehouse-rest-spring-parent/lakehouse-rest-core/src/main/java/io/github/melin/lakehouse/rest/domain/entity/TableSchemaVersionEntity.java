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
 * 表 schema 的历史版本，用于 {@code rollback-schema}。
 */
@Entity
@Table(name = "lakehouse_table_schema",
        uniqueConstraints = @UniqueConstraint(name = "uk_table_schema", columnNames = {"table_id", "schema_id"}),
        indexes = @Index(name = "ix_table_schema_table", columnList = "table_id"),
        comment = "表 schema 的历史版本，用于 rollback-schema")
@Getter
@Setter
public class TableSchemaVersionEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    /** 关联 {@link TableEntity#getId()}。 */
    @Column(name = "table_id", length = 64, nullable = false, comment = "关联的表 id")
    private String tableId;

    @Column(name = "schema_id", nullable = false, comment = "schema 版本号")
    private long schemaId;

    @Column(name = "schema_doc", length = 1048576, comment = "该版本 schema 的 JSON 文档")
    private String schemaDoc;

    @Column(name = "created_at", nullable = false, comment = "该版本写入时刻（epoch 毫秒）")
    private long createdAt;
}
