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
 * 表级边（{@code lineage_table_edge}）：血缘的<b>状态</b>侧。
 *
 * <p>同一对 {@code (源, 目标)} 只存在一行，重复执行只累加 {@code runCount} 并推进
 * {@code lastSeen}。这条决定是整份设计里最影响成本的：若按 run 存，
 * 日 1 万次 INSERT × 3 张源表 = 3 万行/天，一年上亿行，而其中 99% 是同一对表（§7.1）。
 *
 * <p><b>唯一键用摘要而不是全名字段本身</b>：{@code varchar(1024)} 在 utf8mb4 下是
 * 4096 字节，超过 MySQL 单列索引上限（3072），两列拼起来更不可能。摘要定长 64，
 * 索引长度与值长度解耦，唯一性语义不变（与 {@code data-model.md} 里既有的做法一致）。
 *
 * <p>{@code engine} 本期恒为 {@code SPARK}，但它**必须在唯一键里**：同一对表将来可能被
 * 不同引擎写过，两者是两条边（「哪个引擎在写」是有效信息）。唯一键里少一列，
 * 将来加引擎就是一次数据迁移。
 *
 * <p>{@code writeMode} 存最近一次的写模式（{@code APPEND} / {@code OVERWRITE} /
 * {@code UPSERT} / {@code CREATE}）：它不影响图的形状，但影响人怎么读这张图（§4.2）。
 */
@Entity
@Table(name = "lakehouse_lineage_table_edge",
        uniqueConstraints = @UniqueConstraint(name = "uk_lineage_table_edge",
                columnNames = {"source_ref_hash", "target_ref_hash", "engine"}),
        indexes = {
                @Index(name = "ix_lineage_table_edge_target", columnList = "target_ref_hash"),
                @Index(name = "ix_lineage_table_edge_source", columnList = "source_ref_hash")
        },
        comment = "表级血缘边：一对 (源, 目标) 一行，重复执行只累加计数")
@Getter
@Setter
public class LineageTableEdgeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键（不做外键引用，边表按 ref 的摘要查）")
    private Long id;

    @Column(name = "source_ref", length = 1024, nullable = false, comment = "源表全名（可含 ~ 前缀的外部节点）")
    private String sourceRef;

    @Column(name = "source_ref_hash", length = 64, nullable = false, comment = "source_ref 的 SHA-256，唯一键的一半")
    private String sourceRefHash;

    @Column(name = "target_ref", length = 1024, nullable = false, comment = "目标表全名")
    private String targetRef;

    @Column(name = "target_ref_hash", length = 64, nullable = false, comment = "target_ref 的 SHA-256")
    private String targetRefHash;

    @Column(name = "edge_kind", length = 16, nullable = false, comment = "TABLE / EXTERNAL")
    private String edgeKind;

    @Column(name = "engine", length = 16, nullable = false, comment = "上报引擎；进唯一键，将来加引擎不改表")
    private String engine;

    @Column(name = "write_mode", length = 16, nullable = false, comment = "最近一次的写模式")
    private String writeMode;

    @Column(name = "run_count", nullable = false, comment = "累计被观测到的次数")
    private Long runCount;

    @Column(name = "first_seen", nullable = false, comment = "首次观测时刻（epoch 毫秒）")
    private Long firstSeen;

    @Column(name = "last_seen", nullable = false, comment = "最近一次观测时刻，图的「新鲜度」靠它")
    private Long lastSeen;

    @Column(name = "last_run_id", length = 64, comment = "最近一次写它的 run，供下钻")
    private String lastRunId;
}
