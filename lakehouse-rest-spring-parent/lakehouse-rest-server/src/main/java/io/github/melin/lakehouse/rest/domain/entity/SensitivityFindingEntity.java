package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.support.SensitivityEnums.FindingState;
import io.github.melin.lakehouse.rest.support.SensitivityEnums.SamplingMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 扫描命中：**这一轮扫描看到了什么**（{@code lakehouse_sensitivity_finding}，
 * 设计见 {@code docs/sensitive-data-design.md} §4.3）。
 *
 * <p><b>它是历史，不是状态。</b>扫描每天重跑，finding 会一轮一轮重复产生；
 * 而「这列是身份证」这个结论只该有一份，那是
 * {@link ColumnTagEntity} 的事。两张表分工的理由见 {@code ColumnTagEntity} 的类注释——
 * 一句话：合并成一张表就会出现「同列 30 条 pending」或者「重扫把人工拒绝洗回待审」。
 *
 * <p>因此本表的行**写完就不再变**（只 {@link #state} / {@link #decidedBy} /
 * {@link #decidedAt} 三列会被人审改写），tag 才是结论的当前状态。P0 只落 D1/D2
 * 命中；人审队列与自动应用是 P1。
 *
 * <p><b>{@link #maskedSamplesJson} 只存打码样本，任何路径都不写原值</b>
 * （设计稿 §5.4）：采样读到的原值只在内存里活一次，落库前必须过打码。
 * 这不是「顺手多一道」，而是这条链路的唯一一条不变量——一旦某处忘了打码，
 * 敏感原值就永久留在了一张分析表里，且无法追溯它从哪次扫描漏出来。
 *
 * <p>唯一键 {@code (scan_id, column_ref_hash, type_code)}：同一次扫描里同列同型
 * 只留一行。没有这个约束，重复提交同一次扫描（重试、并发）会让同一结论堆出多行，
 * 人审队列里就出现一串一模一样的待审项。
 */
@Entity
@Table(name = "lakehouse_sensitivity_finding",
        uniqueConstraints = @UniqueConstraint(name = "uk_sensitivity_finding",
                columnNames = {"scan_id", "column_ref_hash", "type_code"}),
        indexes = @Index(name = "ix_sensitivity_finding_scan", columnList = "scan_id"),
        comment = "敏感数据扫描命中（每轮扫描的历史，与 lakehouse_column_tag 分工）")
@Getter
@Setter
public class SensitivityFindingEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    /** 所属扫描（{@link SensitivityScanEntity#getId()}，无外键——扫描表会被清理，历史要留着）。 */
    @Column(name = "scan_id", length = 64, nullable = false, comment = "所属扫描的 id")
    private String scanId;

    @Column(name = "catalog_name", length = 64, nullable = false, comment = "catalog 名")
    private String catalogName;

    @Column(name = "database_name", length = 255, nullable = false, comment = "库名")
    private String databaseName;

    @Column(name = "table_name", length = 512, nullable = false, comment = "表名")
    private String tableName;

    /** 列名；**空串 = 表级聚合结论**（照抄血缘边「常量列用空串占位」的做法）。 */
    @Column(name = "column_name", length = 255, nullable = false,
            comment = "列名；空串 = 表级聚合结论")
    private String columnName;

    /**
     * {@code catalog.db.table.column} 的 SHA-256，唯一键的一半。
     *
     * <p>为什么不直接在三段 + 列名上建唯一键：列级全名在 utf8mb4 下 4344 字节，
     * 远超 MySQL 单索引 3072 上限（设计稿 §4.6）——与 {@link ColumnTagEntity}
     * 同一做法。三段 + 列名原名列只用于展示与过滤。
     */
    @Column(name = "column_ref_hash", length = 64, nullable = false,
            comment = "catalog.db.table.column 的 SHA-256（唯一键，索引长度与值长度解耦）")
    private String columnRefHash;

    @Column(name = "type_code", length = 64, nullable = false, comment = "命中的敏感类型")
    private String typeCode;

    /** 置信度 0–1，由 {@code SensitivityScorer} 按 §5.2 加权算出。 */
    @Column(name = "confidence", nullable = false, comment = "置信度 0-1")
    private double confidence;

    /**
     * 各探测器得分与命中率，形如 {@code {"name":0.9,"value":0.97}}。
     *
     * <p>存下来是为了让人审能回答「为什么是它」——只给一个 0.87 的数字，
     * 审核人只能凭感觉点；给出分层得分，他才知道是列名像还是值像。
     */
    @Column(name = "matched_detectors_json", length = 65535,
            comment = "各探测器得分与命中率（{\"name\":0.9,\"value\":0.97}）")
    private String matchedDetectorsJson;

    /** **打码后**的样本，至多 5 个（设计稿 §5.4）。任何路径都不写原值。 */
    @Column(name = "masked_samples_json", length = 65535,
            comment = "打码后的样本（至多 5 个，永不写原值）")
    private String maskedSamplesJson;

    /** 采样方式：{@code TABLESAMPLE} / {@code HEAD}（前者不可用时的降级，设计稿 §6.2）。 */
    @Column(name = "sampling_mode", length = 16,
            comment = "采样方式：TABLESAMPLE / HEAD（降级）")
    private String samplingMode = SamplingMode.HEAD.name();

    /** 人审状态：{@code PENDING} / {@code AUTO_APPLIED} / {@code CONFIRMED} / {@code REJECTED}。 */
    @Column(name = "state", length = 16, nullable = false,
            comment = "人审状态：PENDING / AUTO_APPLIED / CONFIRMED / REJECTED")
    private String state = FindingState.PENDING.name();

    @Column(name = "decided_by", length = 255, comment = "审核人主体名")
    private String decidedBy;

    @Column(name = "decided_at", comment = "审核时刻（epoch 毫秒）")
    private Long decidedAt;
}
