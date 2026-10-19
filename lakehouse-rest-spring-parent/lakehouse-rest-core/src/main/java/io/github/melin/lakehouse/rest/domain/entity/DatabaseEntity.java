package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * database（命名空间）。表、视图、函数都归属于它。
 */
@Entity
@Table(name = "lakehouse_database",
        uniqueConstraints = @UniqueConstraint(name = "uk_database", columnNames = {"catalog_id", "name"}),
        indexes = @Index(name = "ix_database_catalog", columnList = "catalog_id"),
        comment = "database（命名空间），表/视图/函数均归属于它")
@Getter
@Setter
public class DatabaseEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false, comment = "所属 catalog id")
    private String catalogId;

    @Column(name = "name", length = 512, nullable = false, comment = "库名，catalog 内唯一")
    private String name;

    @Column(name = "location_value", length = 1024, comment = "库的根路径；为空表示沿用 catalog 的 warehouse")
    private String location;

    @Convert(converter = StringMap.class)
    @Column(name = "options_json", length = 65535, comment = "库选项（JSON 对象）")
    private Map<String, String> options = new LinkedHashMap<>();

    @Column(name = "comment_text", length = 4096, comment = "库注释")
    private String comment;
}
