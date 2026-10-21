package io.github.melin.lakehouse.rest.domain.entity;

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
 * 列级边（{@code lineage_column_edge}）：{@code (源表.列) → (目标表.列)}，同表级边一样是状态。
 *
 * <p>{@code transformKind} 与 {@code expression} 取**最近一次**：同一对列在不同 run 里
 * 可能先 {@code DIRECT} 后 {@code DERIVED}（有人把 SQL 改成 {@code a+0}），
 * 图上该显示的是它现在的样子。
 *
 * <p><b>没有源列的列也有一行</b>：常量列与静态分区列（{@code PARTITION (dt='2026-10-20')}
 * 的 {@code dt}）的 {@code sources} 是空的（§4.5），但那确实是「被写了的列」，
 * 缺了它，列映射表就会少掉整张宽表里所有的分区列。实现上用空串占住
 * {@code source_ref} / {@code source_column}——唯一键因此仍然成立，
 * 而「没有源」这件事在数据里是显式的一行，不是「查不到」。
 *
 * <p>{@code expression} 只供人看（{@code Expression#sql} 的规范化文本，截断 512），
 * 不参与任何判定。
 */
@Entity
@Table(name = "lakehouse_lineage_column_edge",
        uniqueConstraints = @UniqueConstraint(name = "uk_lineage_column_edge",
                columnNames = {"source_ref_hash", "source_column", "target_ref_hash", "target_column"}),
        indexes = {
                @Index(name = "ix_lineage_column_edge_target", columnList = "target_ref_hash"),
                @Index(name = "ix_lineage_column_edge_source", columnList = "source_ref_hash")
        },
        comment = "列级血缘边：一对 (源列, 目标列) 一行；常量列用空 source 占一行")
@Getter
@Setter
public class LineageColumnEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键（列边按 ref 摘要 + 列名查，不用它）")
    private Long id;

    @Column(name = "source_ref", length = 1024, nullable = false,
            comment = "上游表全名；常量列（无源）为空串")
    private String sourceRef;

    @Column(name = "source_ref_hash", length = 64, nullable = false, comment = "source_ref 的 SHA-256")
    private String sourceRefHash;

    @Column(name = "source_column", length = 255, nullable = false,
            comment = "上游列名；常量列为空串")
    private String sourceColumn;

    @Column(name = "target_ref", length = 1024, nullable = false, comment = "下游表全名")
    private String targetRef;

    @Column(name = "target_ref_hash", length = 64, nullable = false, comment = "target_ref 的 SHA-256")
    private String targetRefHash;

    @Column(name = "target_column", length = 255, nullable = false, comment = "下游列名")
    private String targetColumn;

    @Column(name = "transform_kind", length = 16, nullable = false,
            comment = "DIRECT / DERIVED / AGGREGATE / CONSTANT / UNRESOLVED")
    private String transformKind;

    @Column(name = "expression", length = 512, comment = "最近一次的规范化表达式文本，仅供展示")
    private String expression;

    @Column(name = "last_run_id", length = 64, comment = "最近一次写它的 run，供下钻")
    private String lastRunId;

    @Column(name = "last_seen", nullable = false, comment = "最近一次观测时刻（epoch 毫秒）")
    private Long lastSeen;
}
