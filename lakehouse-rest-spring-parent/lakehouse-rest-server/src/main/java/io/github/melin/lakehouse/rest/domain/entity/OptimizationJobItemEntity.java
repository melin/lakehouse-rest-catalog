package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringMap;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 批次里的单表明细：状态与前后指标都落在这一层。
 *
 * <p><b>为什么指标存 JSON 而不是给每个指标一列。</b>三项动作的指标集合不同
 * （compaction 是前后文件数与大小，expire 是删除快照数与释放空间，
 * orphan 是扫描/删除文件数），合并成一张表就得给每种动作留一列空值；
 * 而指标只用于展示，不参与查询与聚合，JSON 单列正是这种「形状随动作变化、
 * 又不被查询」的字段该有的形态（与 {@code snapshot_statistics} 的取舍相反：
 * 那个要按快照聚合，所以给了列）。
 *
 * <p><b>表名冗余存 {@code table_full_name}。</b>历史列表与控制台展示要的是
 * {@code db.table}，而批次明细可能在被删表之后还要能显示——那时按 {@code table_id}
 * 联表已经查不到名字，历史会变成一串 id。这与本工程「审计信息不因对象删除而消失」
 * 的处理一致。
 *
 * <p>{@code SKIPPED} 是正常分支而不是失败：compaction 未达触发阈值、
 * 快照没有过期的、孤儿文件扫描为空，都属于「跑过但无事可做」，
 * 计入失败会让「批次成功率」这类指标失去意义。
 */
@Entity
@Table(name = "lakehouse_optimization_job_item",
        indexes = {
                @Index(name = "ix_optimization_item_job", columnList = "job_id"),
                @Index(name = "ix_optimization_item_table", columnList = "table_id")
        },
        comment = "优化批次的单表明细与执行指标")
@Getter
@Setter
public class OptimizationJobItemEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "job_id", length = 64, nullable = false, comment = "所属批次 id")
    private String jobId;

    @Column(name = "table_id", length = 64, nullable = false, comment = "表 id")
    private String tableId;

    /** {@code catalog.database.table}，写入时冗余，删表后历史仍可读。 */
    @Column(name = "table_full_name", length = 1024,
            comment = "catalog.database.table，冗余存储以便删表后历史仍可读")
    private String tableFullName;

    /** 这张表实际执行的动作，逗号分隔；批次动作与该表的开关取交集。 */
    @Column(name = "actions", length = 64, nullable = false, comment = "这张表实际执行的动作，逗号分隔")
    private String actions;

    @Column(name = "status_value", length = 16, nullable = false,
            comment = "明细状态：SUCCESS / SKIPPED / FAILED 等")
    private String status;

    @Convert(converter = StringMap.class)
    @Column(name = "metrics_json", length = 8192, comment = "执行前后指标（JSON，形状随动作变化）")
    private Map<String, String> metrics = new LinkedHashMap<>();

    @Column(name = "error_message", length = 1000, comment = "失败原因")
    private String errorMessage;

    @Column(name = "started_at", comment = "开始执行时刻（epoch 毫秒）")
    private Long startedAt;

    @Column(name = "finished_at", comment = "执行结束时刻（epoch 毫秒）")
    private Long finishedAt;

    /** 优化任务上报结果的时刻。 */
    @Column(name = "reported_at", comment = "优化任务上报结果的时刻（epoch 毫秒）")
    private Long reportedAt;
}
