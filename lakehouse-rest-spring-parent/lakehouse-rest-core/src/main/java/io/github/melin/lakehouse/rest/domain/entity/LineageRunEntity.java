package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.ObjectMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 一次执行（{@code lineage_run}）：血缘的<b>事件</b>侧。
 *
 * <p>与边表的区别是语义而不是形状：这里是「某时某刻跑了一次」，只插入、不更新；
 * 边表是「现在谁依赖谁」，upsert 更新计数。两者混成一张表会让「同一对表被跑了 1 万次」
 * 变成 1 万行（§7.1）。
 *
 * <p><b>不继承 {@link AuditedEntity}</b>：{@code updatedBy} / {@code updatedAt} 对
 * 一条事件没有意义——事件一旦写下就不会改。列集是自己的，与 {@code lakehouse_audit_event}
 * 同一个理由。
 *
 * <p>{@code principal} 由服务端从令牌解析后写入（{@link io.github.melin.lakehouse.rest.support.AuditPrincipal}
 * 归一化），不是报文里的字段。
 *
 * <p><b>{@code targetRefs} 是一处对设计文档的补充</b>（{@code docs/lineage-design.md} §7.2 的列集里没有它）：
 * {@code GET /lineage/runs?ref=…} 要按表过滤运行历史，而 run 自己不存表名——不存就只能
 * 「先查边表拿 lastRunId 再回查 run」，那只回答得了「最近一次」，答不出历史。
 * 存成 {@code |ref1|ref2|} 而不是 JSON，是为了让等值过滤退化成一次
 * {@code like '%|ref|%'}（两端带分隔符，{@code paimon.db.t1} 不会命中 {@code paimon.db.t10}）。
 */
@Entity
@Table(name = "lakehouse_lineage_run",
        uniqueConstraints = @UniqueConstraint(name = "uk_lineage_run", columnNames = "run_id"),
        indexes = {
                @Index(name = "ix_lineage_run_catalog", columnList = "catalog, finished_at"),
                @Index(name = "ix_lineage_run_finished", columnList = "finished_at")
        },
        comment = "血缘执行记录：一行一次执行，只增不改（边表才是状态）")
@Getter
@Setter
public class LineageRunEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键，同时是游标的第二段（同毫秒靠它定序）")
    private Long id;

    @Column(name = "run_id", length = 64, nullable = false, comment = "客户端生成的 UUID，幂等键")
    private String runId;

    @Column(name = "engine", length = 16, nullable = false, comment = "引擎：本期恒为 SPARK，枚举留扩展位")
    private String engine;

    @Column(name = "engine_version", length = 32, nullable = false, comment = "引擎自报版本")
    private String engineVersion;

    @Column(name = "statement_type", length = 16, nullable = false, comment = "INSERT / MERGE / CTAS")
    private String statementType;

    @Column(name = "write_mode", length = 16, nullable = false,
            comment = "APPEND / OVERWRITE / UPSERT / CREATE")
    private String writeMode;

    @Column(name = "mode", length = 16, nullable = false, comment = "BATCH / STREAMING")
    private String mode;

    @Column(name = "catalog", length = 255, nullable = false,
            comment = "主 catalog（第一个目标表所在的那个），供按目录过滤")
    private String catalog;

    @Column(name = "principal", length = 255, nullable = false,
            comment = "上报者，由服务端从令牌解析（不采信报文）")
    private String principal;

    @Column(name = "sql_hash", length = 64, comment = "SQL 文本的 SHA-256；没有 SQL 文本时为空")
    private String sqlHash;

    @Column(name = "sql_text", columnDefinition = "text", comment = "SQL 文本，截断 8KB")
    private String sqlText;

    @Column(name = "started_at", nullable = false, comment = "开始时刻（epoch 毫秒）")
    private Long startedAt;

    @Column(name = "finished_at", nullable = false, comment = "结束时刻（epoch 毫秒），列表与清理都按它")
    private Long finishedAt;

    /** {@code |ref1|ref2|}，见类注释。 */
    @Column(name = "target_refs", columnDefinition = "text", comment = "目标表列表（竖线包夹，供 ?ref= 过滤）")
    private String targetRefs;

    @Convert(converter = ObjectMap.class)
    @Column(name = "attributes_json", columnDefinition = "text", comment = "自由属性（JSON 对象）")
    private Map<String, Object> attributes;

    /** 列级是否因超过上限被丢弃（降级为表级）。放在属性里而不是新列：它是上报侧的事实。 */
    @Column(name = "columns_truncated", nullable = false, comment = "列级边是否因超上限被丢弃")
    private boolean columnsTruncated;
}
