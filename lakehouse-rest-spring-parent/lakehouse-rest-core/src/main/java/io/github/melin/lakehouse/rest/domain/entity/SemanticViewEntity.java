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
 * 语义视图定义。
 *
 * <p>与 SQL 视图是两类对象：语义视图的名称不出现在普通视图列表中，
 * 其 {@code content} 整篇保存、不做解析，单个定义上限 1 MiB UTF-8 字节。
 */
@Entity
@Table(name = "lakehouse_semantic_view",
        uniqueConstraints = @UniqueConstraint(name = "uk_semantic_view",
                columnNames = {"catalog_id", "database_id", "name"}),
        indexes = @Index(name = "ix_semantic_view_database", columnList = "database_id"),
        comment = "语义视图定义（整篇保存，不做解析）")
@Getter
@Setter
public class SemanticViewEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false, comment = "所属 catalog id")
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false, comment = "所属库 id")
    private String databaseId;

    /** 语义视图名，允许含点号等字符。 */
    @Column(name = "name", length = 512, nullable = false, comment = "语义视图名，库内唯一，允许含点号")
    private String name;

    /** 模型文档格式，如 {@code databricks-yaml}、{@code snowflake-yaml}、{@code ossie-yaml}。 */
    @Column(name = "format_name", length = 255, nullable = false,
            comment = "模型文档格式，如 databricks-yaml / snowflake-yaml / ossie-yaml")
    private String format;

    @Column(name = "content_doc", length = 3000000, nullable = false,
            comment = "语义视图定义原文，单篇上限 1 MiB UTF-8 字节")
    private String content;
}
