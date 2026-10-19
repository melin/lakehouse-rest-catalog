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
 * 标签：给快照起的不可变名称，常用于长期保留某个可回滚的版本。
 */
@Entity
@Table(name = "lakehouse_tag",
        uniqueConstraints = @UniqueConstraint(name = "uk_tag", columnNames = {"table_id", "tag_name"}),
        indexes = @Index(name = "ix_tag_table", columnList = "table_id"),
        comment = "快照标签：给快照起的不可变名称")
@Getter
@Setter
public class TagEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "table_id", length = 64, nullable = false, comment = "所属表 id")
    private String tableId;

    @Column(name = "tag_name", length = 512, nullable = false, comment = "标签名，同表内唯一")
    private String tagName;

    @Column(name = "snapshot_id", nullable = false, comment = "标签指向的快照 id")
    private long snapshotId;

    @Column(name = "tag_create_time", comment = "标签创建时刻（epoch 毫秒）")
    private Long tagCreateTime;

    /** 保留时长，形如 {@code "1d"} / {@code "12h"} / {@code "30m"}。 */
    @Column(name = "tag_time_retained", length = 64, comment = "保留时长，形如 1d / 12h / 30m")
    private String tagTimeRetained;
}
