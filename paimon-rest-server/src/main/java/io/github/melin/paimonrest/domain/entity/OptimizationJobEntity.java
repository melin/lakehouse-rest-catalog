package io.github.melin.paimonrest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 优化批次：一次 Spark 任务覆盖的一组表。
 *
 * <p><b>为什么批次是独立实体而不是「一堆单表任务」。</b>一个 Spark 任务的固定开销
 * （pod 调度、镜像拉取、SparkContext 启动）与它处理几张表无关，一表一任务会把
 * k8s 调度打满而收益为零。批次因此是执行的最小单位：同一 catalog 的一组到期表
 * 合并成一个批次，一个批次对应一次提交。
 *
 * <p><b>状态推进顺序</b>：{@code PENDING}（先落库，等提交）→ {@code SUBMITTING}
 * （提交器执行中）→ {@code RUNNING}（k8s 侧已有资源）→ 终态
 * {@code SUCCESS} / {@code PARTIAL_SUCCESS} / {@code FAILED} / {@code CANCELED}。
 * 落库与提交分成两步由两个调度节拍完成（见 {@code OptimizationDispatcher}），
 * 因此服务端在任意时刻重启都不会丢批次：{@code PENDING} 会被下一次节拍捡起来，
 * {@code SUBMITTING} 之后再启动则按 k8s 侧的资源名对账。
 *
 * <p>{@code callback_token} 是**批次级**的回调凭据：优化任务用它在启动时拉本批次的
 * 表清单、运行中逐表上报结果。它只对这一个批次有效，随批次终态失效，与登录令牌、
 * 静态令牌都不是一回事——泄露它的最坏后果是攻击者能伪造这一个批次的表级结果。
 * 之所以不做成签名令牌：批次是有生命周期的数据库记录，随机值存一行即可，
 * 引入签发/校验两套代码换不到额外收益。
 */
@Entity
@Table(name = "paimon_optimization_job",
        indexes = {
                @Index(name = "ix_optimization_job_catalog", columnList = "catalog_id"),
                @Index(name = "ix_optimization_job_status", columnList = "status_value")
        })
@Getter
@Setter
public class OptimizationJobEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    /** {@code SCHEDULED}：调度器出批；{@code MANUAL}：用户在控制台点「立即优化」。 */
    @Column(name = "trigger_type", length = 16, nullable = false)
    private String triggerType;

    /** 批次覆盖的动作集合，逗号分隔（{@code compact,expire,orphan}）。 */
    @Column(name = "actions", length = 64, nullable = false)
    private String actions;

    @Column(name = "status_value", length = 24, nullable = false)
    private String status;

    @Column(name = "table_count", nullable = false)
    private int tableCount;

    /** 提交方式标识（{@code k8s} / {@code log}），与提交器实现的 {@code name()} 一致。 */
    @Column(name = "submitter", length = 32)
    private String submitter;

    /** 回调凭据：优化任务用它拉明细、上报结果。 */
    @Column(name = "callback_token", length = 64, nullable = false)
    private String callbackToken;

    @Column(name = "k8s_namespace", length = 128)
    private String namespace;

    /** k8s 侧资源名（Job 或 SparkApplication 的名称），用于跟踪与取消。 */
    @Column(name = "k8s_resource", length = 256)
    private String resource;

    @Column(name = "spark_app_id", length = 128)
    private String sparkAppId;

    @Column(name = "submitted_at")
    private Long submittedAt;

    @Column(name = "started_at")
    private Long startedAt;

    @Column(name = "finished_at")
    private Long finishedAt;

    /** 超时时刻；超过它仍未终态的批次会被判失败并清理资源。 */
    @Column(name = "deadline_at")
    private Long deadlineAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 重试来源批次：只重跑失败表的批次会指向原批次。 */
    @Column(name = "retry_of", length = 64)
    private String retryOf;
}
