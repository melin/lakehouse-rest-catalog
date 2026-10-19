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
 * 表分支：指向某个快照的命名历史线，供流式写入与多路消费使用。
 */
@Entity
@Table(name = "lakehouse_branch",
        uniqueConstraints = @UniqueConstraint(name = "uk_branch", columnNames = {"table_id", "name"}),
        indexes = @Index(name = "ix_branch_table", columnList = "table_id"),
        comment = "表分支：指向某个快照的命名历史线")
@Getter
@Setter
public class BranchEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "table_id", length = 64, nullable = false, comment = "所属表 id")
    private String tableId;

    @Column(name = "name", length = 512, nullable = false, comment = "分支名，同表内唯一")
    private String name;

    @Column(name = "snapshot_id", comment = "分支当前指向的快照 id")
    private Long snapshotId;

    @Convert(converter = StringMap.class)
    @Column(name = "options_json", length = 65535, comment = "分支选项（JSON 对象）")
    private Map<String, String> options = new LinkedHashMap<>();
}
