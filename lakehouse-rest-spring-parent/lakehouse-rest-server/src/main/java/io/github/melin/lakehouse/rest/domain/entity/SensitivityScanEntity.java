package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanExecution;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanMode;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanScope;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanStatus;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.ScanTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 一次敏感数据扫描（{@code lakehouse_sensitivity_scan}，设计见
 * {@code docs/sensitive-data-design.md} §4.2）。A 路线（进程内）与 B 路线（下推 Spark）
 * 共用这一张表——两条路的**历史**是同一种东西，分开建表会让「最近扫过哪些 catalog」
 * 需要查两张表再合并。
 *
 * <p><b>后三个范围字段可空</b>：{@code CATALOG} 级扫描没有库名与表名，
 * {@code DATABASE} 级没有表名。它们不是「没填」，而是「按这个层级扫」——
 * 因此 {@link #scope} 才是判别字段，不要用「库名是否为空」反推层级。
 *
 * <p><b>{@code execution} 落的是生效值，不是请求体原值。</b>三层覆盖
 * （请求 &gt; catalog 的 {@code defaultExecution} &gt; 全局）算完之后才写库：
 * 请求没传的时候原值是 {@code null}，而「三个月后回看这次扫描跑在哪」必须能答上来。
 * 同理，新增 {@code execution} 这一列时 P2 就落库、值恒为 {@link ScanExecution#IN_PROCESS}
 * ——<b>先有列再有值，比事后给存量行补一个说不清的默认值干净</b>（设计稿 §12 的注）。
 *
 * <p><b>B 路线那组列（Job 坐标 / 令牌哈希 / 提交代数）P2 一次建好</b>，尽管
 * {@code execution=SPARK} 到 P4 才通：{@link ScanStatus} 的取值域一旦要扩到
 * {@code PENDING} / {@code SUBMITTING}，改的是列宽与所有既有读取点。同一条理由
 * 也适用于 {@code callback_token_hash}——只存哈希、不存令牌本身，
 * 与执行集群凭据「只进不出」同一口径。
 *
 * <p><b>{@code tables_failed} 与 {@code tables_scanned} 必须分开计数。</b>
 * 合成一个「扫了多少张」就无法表达「扫了 100 张、其中 3 张读失败」——
 * 覆盖率会把读失败的表算成已覆盖，而「假覆盖」是这类系统最坏的一种错
 * （设计稿 §13「下推路线的假覆盖」）。
 */
@Entity
@Table(name = "lakehouse_sensitivity_scan",
        indexes = {
                @Index(name = "ix_sensitivity_scan_catalog", columnList = "catalog_name"),
                @Index(name = "ix_sensitivity_scan_status", columnList = "status_value")
        },
        comment = "敏感数据扫描：一次扫描的范围、模式、生效执行位置与计数")
@Getter
@Setter
public class SensitivityScanEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键（UUID）")
    private String id;

    @Column(name = "catalog_name", length = 64, nullable = false, comment = "扫描的 catalog 名")
    private String catalogName;

    /** 库名；{@code CATALOG} 级扫描为空。 */
    @Column(name = "database_name", length = 255,
            comment = "库名；CATALOG 级扫描为空（是否为空不代表层级，看 scope）")
    private String databaseName;

    /** 表名；{@code CATALOG} / {@code DATABASE} 级扫描为空。 */
    @Column(name = "table_name", length = 512,
            comment = "表名；CATALOG / DATABASE 级扫描为空（是否为空不代表层级，看 scope）")
    private String tableName;

    /** 范围层级：{@code CATALOG} / {@code DATABASE} / {@code TABLE}。判别字段。 */
    @Column(name = "scope", length = 16, nullable = false,
            comment = "范围层级：CATALOG / DATABASE / TABLE")
    private String scope = ScanScope.CATALOG.name();

    /**
     * 扫描模式：{@code METADATA_ONLY}（只跑 D1/D2，秒级）/ {@code SAMPLED}（加 D3/D4/D5）。
     *
     * <p>{@code METADATA_ONLY} 恒走 A 路线：下推 Spark 读元数据没有任何收益，
     * 反而多一次作业调度开销（设计稿 §5.6.1）。
     */
    @Column(name = "mode", length = 16, nullable = false,
            comment = "扫描模式：METADATA_ONLY（只 D1/D2）/ SAMPLED（加 D3/D4/D5）")
    private String mode = ScanMode.METADATA_ONLY.name();

    /** 执行位置**生效值**：{@code IN_PROCESS}（默认 A）/ {@code SPARK}（下推 B，P4）。 */
    @Column(name = "execution", length = 16, nullable = false,
            comment = "执行位置生效值：IN_PROCESS（A）/ SPARK（B）；落生效值不是请求原值")
    private String execution = ScanExecution.IN_PROCESS.name();

    /** 触发方式：{@code MANUAL} / {@code LIFECYCLE}（P2）/ {@code SCHEDULE}（P4）。 */
    @Column(name = "trigger_type", length = 16, nullable = false,
            comment = "触发方式：MANUAL / LIFECYCLE（P2）/ SCHEDULE（P4）")
    private String triggerType = ScanTrigger.MANUAL.name();

    /** 仅 {@code trigger_type=SCHEDULE} 有值；回查调度项，**不建外键**（调度项 P4 才落地）。 */
    @Column(name = "schedule_name", length = 128,
            comment = "调度项名（仅 SCHEDULE 有值，不建外键）")
    private String scheduleName;

    /** 采样行上限，默认 2000；硬上限 10000（设计稿 §5.4）。 */
    @Column(name = "sample_size", nullable = false,
            comment = "采样行上限（默认 2000，硬上限 10000）")
    private int sampleSize = 2000;

    /**
     * 状态。取值域**已按 B 路线的宽度建齐**（含 {@code PENDING} / {@code SUBMITTING}），
     * 尽管 A 路线只会经过 {@code RUNNING} → 终态。
     */
    @Column(name = "status_value", length = 16, nullable = false,
            comment = "状态：PENDING / SUBMITTING（仅 B）/ RUNNING / SUCCEEDED / FAILED / CANCELLED")
    private String status = ScanStatus.RUNNING.name();

    /** 扫过的表数。**包含读失败的表**——所以「覆盖了多少」要减去 {@code tables_failed} 才准。 */
    @Column(name = "tables_scanned", nullable = false, comment = "扫过的表数（含读失败的）")
    private int tablesScanned;

    @Column(name = "columns_scanned", nullable = false, comment = "扫过的列数")
    private int columnsScanned;

    @Column(name = "findings_created", nullable = false, comment = "本轮新增的 finding 数")
    private int findingsCreated;

    /**
     * 读失败的表数。**与 {@link #tablesScanned} 分开**，否则「扫了 100 张、其中 3 张读失败」
     * 无法表达（设计稿 §5.6.5 第 2 条）。
     */
    @Column(name = "tables_failed", nullable = false,
            comment = "读失败的表数；与 tables_scanned 分开，否则「扫了 100 张其中 3 张失败」无法表达")
    private int tablesFailed;

    /** 发起者主体名（写入前经 {@code AuditPrincipal} 归一化，列宽 255）。 */
    @Column(name = "triggered_by", length = 255, comment = "发起扫描的主体名")
    private String triggeredBy;

    @Column(name = "started_at", comment = "开始时刻（epoch 毫秒）")
    private Long startedAt;

    @Column(name = "finished_at", comment = "结束时刻（epoch 毫秒）")
    private Long finishedAt;

    /** 失败原因（截断到 2048）。A 路线是异常消息；B 路线要回读 Spark 历史，形状不同。 */
    @Column(name = "error_message", length = 2048, comment = "失败原因（截断）；A 与 B 的形状不同")
    private String errorMessage;

    /** **B 路线**：提交后的 Job 坐标（命名空间），用于跟踪与取消。A 路线为空。 */
    @Column(name = "execution_namespace", length = 255,
            comment = "B 路线：提交后的 Job 命名空间（A 路线为空）")
    private String executionNamespace;

    /** **B 路线**：提交后的 Job 资源名。A 路线为空。 */
    @Column(name = "execution_resource", length = 255,
            comment = "B 路线：提交后的 Job 资源名（A 路线为空）")
    private String executionResource;

    /**
     * **B 路线**：本次扫描回调令牌的 SHA-256。**只存哈希，不存令牌本身**——
     * 与执行集群凭据「只进不出」同一口径。令牌只在 {@code POST /sensitivity-scans} 的
     * 响应里出现一次。
     */
    @Column(name = "callback_token_hash", length = 64,
            comment = "B 路线：回调令牌的 SHA-256（只存哈希，令牌本身只在创建响应里出现一次）")
    private String callbackTokenHash;

    /** **B 路线**：提交的 fencing 代数，每被重新抢占一次 +1（与优化批次同形）。 */
    @Column(name = "claim_attempt", comment = "B 路线：提交代数（fencing）")
    private Integer claimAttempt;

    /** **B 路线**：提交租约到期时刻；到期后可被别的实例回收，用于清理「提交中」的孤儿扫描。 */
    @Column(name = "claim_expires_at",
            comment = "B 路线：提交租约到期（epoch 毫秒）；到期后可被别的实例回收")
    private Long claimExpiresAt;
}
