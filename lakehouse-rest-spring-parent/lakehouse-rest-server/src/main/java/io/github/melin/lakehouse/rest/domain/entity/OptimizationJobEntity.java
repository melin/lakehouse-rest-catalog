package io.github.melin.lakehouse.rest.domain.entity;

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
@Table(name = "lakehouse_optimization_job",
        indexes = {
                @Index(name = "ix_optimization_job_catalog", columnList = "catalog_id"),
                @Index(name = "ix_optimization_job_status", columnList = "status_value")
        },
        comment = "存储优化批次：一次 Spark 任务覆盖的一组表")
@Getter
@Setter
public class OptimizationJobEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    @Column(name = "catalog_id", length = 64, nullable = false, comment = "所属 catalog id")
    private String catalogId;

    /** {@code SCHEDULED}：调度器出批；{@code MANUAL}：用户在控制台点「立即优化」。 */
    @Column(name = "trigger_type", length = 16, nullable = false,
            comment = "触发方式：SCHEDULED（调度出批）/ MANUAL（控制台立即优化）")
    private String triggerType;

    /** 批次覆盖的动作集合，逗号分隔（{@code compact,expire,orphan}）。 */
    @Column(name = "actions", length = 64, nullable = false,
            comment = "批次覆盖的动作集合，逗号分隔（compact,expire,orphan）")
    private String actions;

    @Column(name = "status_value", length = 24, nullable = false,
            comment = "批次状态：PENDING / SUBMITTING / RUNNING / 终态")
    private String status;

    /**
     * 提交租约持有者（实例标识 = {@code hostname/pid}）。
     *
     * <p>只在 {@code SUBMITTING} 期间有值，转 {@code RUNNING} 时清空。它的作用是让
     * 「提交中」这件事**可回收**：进程在 {@code submit()} 与回写之间被杀时，
     * 别的实例能看到「谁拿的、什么时候到期」，到期后凭 {@link #claimAttempt} 回收。
     */
    @Column(name = "claim_owner", length = 128,
            comment = "当前提交租约持有者（实例标识 = hostname/pid）；只在 SUBMITTING 期间有值")
    private String claimOwner;

    @Column(name = "claim_expires_at",
            comment = "提交租约到期时刻（epoch 毫秒）；到期后可被别的实例回收")
    private Long claimExpiresAt;

    /**
     * 提交代数（fencing）：每被重新抢占一次 +1。
     *
     * <p>回写（{@code SUBMITTING → RUNNING}）与回收都要带上代数：过期持有者「复活」时
     * 手里那个代数已经不是当前值，条件更新返回 0 行，于是它写不进去——这就是
     * 「上一代不能覆盖这一代」的实现方式。MySQL 没有 {@code RETURNING}，
     * 抢占后要在同一事务里回读一次（读到的必然是自己刚写的那一代）。
     */
    @Column(name = "claim_attempt", nullable = false,
            comment = "提交代数（fencing）：每次重新抢占 +1")
    private int claimAttempt;

    @Column(name = "table_count", nullable = false, comment = "批次覆盖的表数")
    private int tableCount;

    /** 提交方式标识（{@code k8s} / {@code log}），与提交器实现的 {@code name()} 一致。 */
    @Column(name = "submitter", length = 32, comment = "提交方式标识（k8s / log）")
    private String submitter;

    /** 回调凭据：优化任务用它拉明细、上报结果。 */
    @Column(name = "callback_token", length = 64, nullable = false,
            comment = "批次级回调凭据，随批次终态失效")
    private String callbackToken;

    @Column(name = "k8s_namespace", length = 128, comment = "k8s 命名空间")
    private String namespace;

    /** k8s 侧资源名（Job 或 SparkApplication 的名称），用于跟踪与取消。 */
    @Column(name = "k8s_resource", length = 256,
            comment = "k8s 侧资源名（Job 或 SparkApplication），用于跟踪与取消")
    private String resource;

    @Column(name = "spark_app_id", length = 128, comment = "Spark 应用 id")
    private String sparkAppId;

    @Column(name = "submitted_at", comment = "提交时刻（epoch 毫秒）")
    private Long submittedAt;

    @Column(name = "started_at", comment = "开始执行时刻（epoch 毫秒）")
    private Long startedAt;

    @Column(name = "finished_at", comment = "进入终态的时刻（epoch 毫秒）")
    private Long finishedAt;

    /** 超时时刻；超过它仍未终态的批次会被判失败并清理资源。 */
    @Column(name = "deadline_at", comment = "超时时刻，超过仍未终态则判失败并清理资源")
    private Long deadlineAt;

    @Column(name = "error_message", length = 1000, comment = "失败原因")
    private String errorMessage;

    /** 重试来源批次：只重跑失败表的批次会指向原批次。 */
    @Column(name = "retry_of", length = 64, comment = "重试来源批次 id（只重跑失败表时指向原批次）")
    private String retryOf;
}
