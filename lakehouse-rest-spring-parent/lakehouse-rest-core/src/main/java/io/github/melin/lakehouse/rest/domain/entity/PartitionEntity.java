package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.ObjectMap;
import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringMap;
import io.github.melin.lakehouse.rest.support.Digests;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 分区。
 *
 * <p>{@code specKey} 是 spec 的规范化键，用于按分区定位与去重；
 * {@code specJson} 保存完整的分区字段映射。{@code done} 标记分区是否已
 * 完成（{@code markDonePartitions}），{@code options} 支持自定义路径等扩展。
 *
 * <p>唯一约束落在 {@code specHash} 而不是 {@code specKey} 上：规格未限制分区字段数量，
 * {@code specKey} 的长度没有上界，而 MySQL 的索引键上限是 3072 字节，
 * 直接索引会被 {@code ERROR 1071: Specified key was too long} 拒绝。
 */
@Entity
@Table(name = "lakehouse_partition",
        uniqueConstraints = @UniqueConstraint(name = "uk_partition", columnNames = {"table_id", "spec_hash"}),
        indexes = @Index(name = "ix_partition_table", columnList = "table_id"),
        comment = "表分区及其统计信息")
@Getter
@Setter
public class PartitionEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "table_id", length = 64, nullable = false, comment = "所属表 id")
    private String tableId;

    /** 规范化后的 spec 文本，用于按分区等值定位。 */
    @Column(name = "spec_key", length = 1024, nullable = false,
            comment = "规范化后的分区 spec 文本，用于按分区等值定位")
    private String specKey;

    /** {@code specKey} 的定长摘要，承担唯一约束；见 {@link Digests}。 */
    @Column(name = "spec_hash", length = Digests.HEX_LENGTH, nullable = false,
            comment = "spec_key 的 SHA-256 摘要，唯一约束落在它上面")
    private String specHash = "";

    @Convert(converter = ObjectMap.class)
    @Column(name = "spec_json", length = 65535, comment = "分区字段映射的完整 JSON")
    private Map<String, Object> spec = new LinkedHashMap<>();

    @Column(name = "record_count", comment = "分区内记录数")
    private Long recordCount;

    @Column(name = "file_size_in_bytes", comment = "分区内文件总字节数")
    private Long fileSizeInBytes;

    @Column(name = "file_count", comment = "分区内文件数")
    private Long fileCount;

    @Column(name = "last_file_creation_time", comment = "分区内最近一次文件创建时刻（epoch 毫秒）")
    private Long lastFileCreationTime;

    @Column(name = "total_buckets", comment = "桶总数")
    private Integer totalBuckets;

    @Column(name = "done_flag", nullable = false, comment = "分区是否已提交完成")
    private boolean done;

    @Convert(converter = StringMap.class)
    @Column(name = "options_json", length = 65535, comment = "分区选项（JSON 对象，如自定义路径）")
    private Map<String, String> options = new LinkedHashMap<>();

    /**
     * 依据当前 {@code specKey} 重算摘要。
     *
     * <p>放在生命周期回调里而不是 setter 里，是为了不依赖调用方是否记得调用
     * {@code normalize}：无论 {@code specKey} 从哪条路径被赋值，落库前摘要一定会被刷新。
     */
    @PrePersist
    @PreUpdate
    void refreshSpecHash() {
        this.specHash = Digests.sha256Hex(specKey);
    }
}
