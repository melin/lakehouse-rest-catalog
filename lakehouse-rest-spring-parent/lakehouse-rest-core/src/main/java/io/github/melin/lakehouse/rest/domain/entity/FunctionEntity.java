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
 * 函数定义。支持 {@code sql} / {@code file} / {@code lambda} 三种定义，
 * 按定义名保存在 JSON 文档中。
 */
@Entity
@Table(name = "lakehouse_function",
        uniqueConstraints = @UniqueConstraint(name = "uk_function", columnNames = {"catalog_id", "database_id", "name"}),
        indexes = @Index(name = "ix_function_database", columnList = "database_id"),
        comment = "函数定义（sql / file / lambda）")
@Getter
@Setter
public class FunctionEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false, comment = "所属 catalog id")
    private String catalogId;

    @Column(name = "database_id", length = 64, nullable = false, comment = "所属库 id")
    private String databaseId;

    @Column(name = "name", length = 512, nullable = false, comment = "函数名，库内唯一")
    private String name;

    @Column(name = "input_params_doc", length = 1048576, comment = "入参列表（JSON 文档）")
    private String inputParamsDoc;

    @Column(name = "return_params_doc", length = 1048576, comment = "返回值列表（JSON 文档）")
    private String returnParamsDoc;

    @Column(name = "definitions_doc", length = 1048576,
            comment = "函数定义（JSON，sql / file / lambda 三种定义并列）")
    private String definitionsDoc;

    @Column(name = "deterministic_flag", comment = "是否确定性函数")
    private Boolean deterministic;

    @Column(name = "comment_text", length = 4096, comment = "函数注释")
    private String comment;

    @Convert(converter = StringMap.class)
    @Column(name = "options_json", length = 65535, comment = "函数选项（JSON 对象）")
    private Map<String, String> options = new LinkedHashMap<>();
}
