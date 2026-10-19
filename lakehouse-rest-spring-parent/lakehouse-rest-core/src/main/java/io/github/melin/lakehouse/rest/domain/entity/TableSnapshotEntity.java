package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.LongMap;
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
 * 表快照。
 *
 * <p>同时承载规格里 {@code Snapshot} 与 {@code TableSnapshot} 两类信息：
 * {@code Snapshot} 是提交产物的清单指针，{@code TableSnapshot} 在其上补充了
 * 记录数、文件数与文件体积等聚合统计，这里合并存放。
 */
@Entity
@Table(name = "lakehouse_snapshot",
        uniqueConstraints = @UniqueConstraint(name = "uk_snapshot", columnNames = {"table_id", "snapshot_id"}),
        indexes = @Index(name = "ix_snapshot_table", columnList = "table_id"),
        comment = "表快照：Paimon 与 Iceberg 共用同一张表")
@Getter
@Setter
public class TableSnapshotEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "table_id", length = 64, nullable = false, comment = "所属表 id")
    private String tableId;

    /** 规格中的 {@code Snapshot.id}。 */
    @Column(name = "snapshot_id", nullable = false, comment = "快照 id，表内唯一")
    private long snapshotId;

    /**
     * 规格中的 {@code Snapshot.version}：快照**文件格式版本**，不是自增序号。
     *
     * <p>客户端每个快照都送同一个值（{@code Snapshot.CURRENT_VERSION}，Paimon 2.0 是 3），
     * 所以它当不了查询键——按「第 n 个快照」取快照的是
     * {@code TableService.getVersionSnapshot}，那里查的是 {@code snapshot_id}。
     */
    @Column(name = "version_number", comment = "快照文件格式版本，不是自增序号")
    private Integer version;

    @Column(name = "uuid_value", length = 64, comment = "快照 uuid")
    private String uuid;

    @Column(name = "schema_id", nullable = false, comment = "该快照对应的 schema 版本号")
    private long schemaId;

    @Column(name = "base_manifest_list", length = 1024, comment = "基础清单文件路径")
    private String baseManifestList;

    @Column(name = "delta_manifest_list", length = 1024, comment = "增量清单文件路径")
    private String deltaManifestList;

    @Column(name = "changelog_manifest_list", length = 1024, comment = "变更日志清单文件路径")
    private String changelogManifestList;

    @Column(name = "index_manifest", length = 1024, comment = "索引清单文件路径")
    private String indexManifest;

    @Column(name = "commit_user", length = 255, comment = "提交者")
    private String commitUser;

    @Column(name = "commit_identifier", length = 255, comment = "提交标识，用于幂等去重")
    private String commitIdentifier;

    /** APPEND / COMPACT / OVERWRITE / ANALYZE。 */
    @Column(name = "commit_kind", length = 32, comment = "提交类型：APPEND / COMPACT / OVERWRITE / ANALYZE")
    private String commitKind;

    @Column(name = "time_millis", comment = "快照生成时刻（epoch 毫秒）")
    private Long timeMillis;

    @Convert(converter = LongMap.class)
    @Column(name = "log_offsets_json", length = 65535, comment = "各分区的日志偏移（JSON 对象）")
    private Map<String, Long> logOffsets = new LinkedHashMap<>();

    @Column(name = "total_record_count", comment = "全量记录数")
    private Long totalRecordCount;

    @Column(name = "delta_record_count", comment = "本次提交的增量记录数")
    private Long deltaRecordCount;

    @Column(name = "changelog_record_count", comment = "本次提交的 changelog 记录数")
    private Long changelogRecordCount;

    @Column(name = "watermark_value", comment = "水位值（流式写入）")
    private Long watermark;

    @Column(name = "statistics_doc", length = 1048576, comment = "快照统计信息（JSON 文档）")
    private String statistics;

    @Column(name = "record_count", comment = "记录数")
    private Long recordCount;

    @Column(name = "file_size_in_bytes", comment = "文件总字节数")
    private Long fileSizeInBytes;

    @Column(name = "file_count", comment = "文件数")
    private Long fileCount;

    @Column(name = "last_file_creation_time", comment = "最近一次文件创建时刻（epoch 毫秒）")
    private Long lastFileCreationTime;

    // ------------------------------------------------------- Iceberg 侧的三列
    //
    // 只对 Iceberg 表的快照行有值。Iceberg 的 snapshots[] 与 Paimon 的快照在
    // 「快照 id、schema id、时间戳、清单文件、记录数」这五处本来就同构，因此复用既有列
    // （映射关系见 docs/iceberg-rest-catalog-design.md §4.2），只有下面三样 Paimon 没有。

    /** Iceberg 的 {@code parent-snapshot-id}：快照链的前驱。Paimon 的快照没有这个字段。 */
    @Column(name = "parent_snapshot_id", comment = "Iceberg 的 parent-snapshot-id；Paimon 快照为空")
    private Long parentSnapshotId;

    /**
     * Iceberg v2 的 {@code sequence-number}。
     *
     * <p>它不等于快照 id：sequence number 记录的是「元数据提交的次序」，删掉再提交会让两者
     * 分叉。refs 的保留策略与快照过期都要按它排序，因此必须单独存——用快照 id 顶替
     * 在正常路径上看不出差别，只在回滚后再提交时出错。v1 表恒为 0。
     */
    @Column(name = "sequence_number",
            comment = "Iceberg v2 的 sequence-number，记录元数据提交次序（v1 恒为 0）")
    private Long sequenceNumber;

    /**
     * Iceberg 的 {@code summary} 原文（{@code added-records} / {@code total-files-size} / …）。
     *
     * <p>原样存 JSON 而不是挑几个键建列：summary 的键集合由 Iceberg 的
     * {@code SnapshotSummary} 定义，且随版本增删（v1 没有 {@code total-data-files}）。
     * 挑键建列等于把一个会漂移的集合固化进表结构。控制台需要的三个数
     * （记录数 / 文件数 / 体积）已经从 summary 派生到了既有列上。
     */
    @Column(name = "summary_json", length = 65535, comment = "Iceberg snapshot summary 原文 JSON")
    private String summaryJson;
}
