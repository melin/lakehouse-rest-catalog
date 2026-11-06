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
 * 一个定时扫描的调度项（{@code lakehouse_sensitivity_schedule}，设计见
 * {@code docs/sensitive-data-design.md} §4.6 / §5.6.3）。
 *
 * <p><b>这张表存在的理由不是「方便的配置存放处」。</b>本轮拍板把定时扫描从「外部调度器
 * 调 {@code POST /sensitivity-scans}」翻成服务端自建，换来的正是「调度项本身成为一个
 * 可审计、可停启、在控制台上看得见的对象」。外置方案里 cron 在别人手里，
 * {@code triggered_by} 只能记成「那个 Airflow 账号」——「谁在什么时候读了明文」
 * 这条审计链上会多一个说不清的环节。
 *
 * <p><b>两个「不合并」的字段。</b>
 * <ul>
 *   <li>{@link #nextRunAt} 是<b>抢占令牌</b>，不是展示字段：多副本下每台实例都算出同一个
 *       「到期」，靠 {@code update ... where next_run_at = <读到的值>} 只让一方推进。
 *       展示只是它的副作用。</li>
 *   <li>{@link #entityVersion} 是<b>人工编辑</b>的乐观锁（两个人同时改同一个调度项，
 *       后者收 409）。两者混成一个字段的表现是「改一次 cron，调度器以为自己在抢占时撞车了」
 *       ——不报错，只是那一拍谁也没跑。</li>
 * </ul>
 *
 * <p><b>为什么没有 {@code lastScanId}。</b>一次调度可能因范围变化对应多条扫描
 * （catalog 级调度在表被删/加之后仍是一条），用「扫描表的 {@code trigger_type=SCHEDULE}
 * + {@code schedule_name}」回查更准；存一个 id 会造出两表互相更新，而那次调度
 * 恰好因为同表闸门被跳过时，这个 id 指向的还是一条别人的扫描。
 *
 * <p><b>范围三字段可空且必须递进</b>（{@code table} 有值则 {@code database} 必须有值）：
 * 与 {@code POST /sensitivity-scans} 的三段范围同口径。**层级不靠「哪几个字段有值」反推**
 * ——这里的 {@code scope} 是派生的，见 {@code SensitivityScheduleService#scopeOf}。
 */
@Entity
@Table(name = "lakehouse_sensitivity_schedule",
        uniqueConstraints = @UniqueConstraint(name = "uk_sensitivity_schedule", columnNames = "name"),
        indexes = @Index(name = "ix_sensitivity_schedule_catalog", columnList = "catalog_name"),
        comment = "定时扫描的调度项（自建调度：cron + next_run_at 抢占令牌）")
@Getter
@Setter
public class SensitivityScheduleEntity extends AuditedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, comment = "自增主键")
    private Long id;

    /**
     * 调度项名（唯一）。
     *
     * <p><b>不可改名。</b>扫描表的 {@code schedule_name} 存的就是它，而扫描是历史记录
     * ——改名会让「这条扫描是哪条调度项触发的」在库里失去指向。要换名字请建一条新的、
     * 停用旧的：历史留在旧名字下，这才是「可审计」的含义。
     */
    @Column(name = "name", length = 128, nullable = false,
            comment = "调度项名（唯一；扫描表按它回查历史，因此不可改名）")
    private String name;

    /**
     * cron 表达式（5 段或 6 段，秒级可选）。
     *
     * <p><b>这里存 cron 而不是「间隔秒数」</b>，与表优化那条 {@code schedule_interval_seconds}
     * 刻意不同：interval 表达不了「每天凌晨 3 点」，而扫描的典型诉求恰恰是「凌晨跑」。
     * 换算 {@code 3600 → 0 0 * * * *} 看起来对，可用户一旦想写 {@code 0 3 * * *} 就没有
     * 位置放了。
     */
    @Column(name = "cron", length = 64, nullable = false, comment = "cron 表达式（5 段或 6 段，秒级可选）")
    private String cron;

    @Column(name = "catalog_name", length = 64, nullable = false, comment = "catalog 名")
    private String catalogName;

    /** 库名；catalog 级调度为空。 */
    @Column(name = "database_name", length = 255, comment = "库名；catalog 级调度为空")
    private String databaseName;

    /** 表名；catalog / 库级调度为空。 */
    @Column(name = "table_name", length = 512, comment = "表名；catalog / 库级调度为空")
    private String tableName;

    @Column(name = "mode", length = 16, nullable = false,
            comment = "扫描模式：METADATA_ONLY / SAMPLED")
    private String mode = "METADATA_ONLY";

    @Column(name = "sample_size", nullable = false, comment = "采样行上限（默认 2000）")
    private int sampleSize = 2000;

    /**
     * 执行位置：{@code IN_PROCESS} / {@code SPARK}。
     *
     * <p>落的是**这一条调度项自己的选择**，未填时按 §5.6.1 的三层覆盖取生效值再落库
     * ——调度项是长期对象，它的「在哪跑」必须能在库里读出来，不能每次触发时再算一遍。
     */
    @Column(name = "execution", length = 16, nullable = false,
            comment = "执行位置：IN_PROCESS / SPARK（未填时取三层覆盖的生效值）")
    private String execution = "IN_PROCESS";

    /** 停用后不再出计划，但历史扫描按 {@code schedule_name} 仍可查。 */
    @Column(name = "enabled_flag", nullable = false,
            comment = "是否启用；停用后不再触发，历史扫描仍按 schedule_name 可查")
    private boolean enabledFlag = true;

    @Column(name = "last_run_at", comment = "上次触发的时刻（epoch 毫秒）")
    private Long lastRunAt;

    /**
     * 下次应触发的时刻（epoch 毫秒）。**抢占的 CAS 条件**，不是展示字段。
     *
     * <p>为 {@code null} 表示「还没算过」（刚建出来、或 cron 被改过）——节拍会把它补上，
     * 而不是把它当成「立刻到期」。后者会让「新建一条每天 3 点的调度」立刻扫一次全量，
     * 而那通常不是用户按下保存时想做的事。
     */
    @Column(name = "next_run_at", comment = "下次触发时刻（epoch 毫秒）；调度器抢占的 CAS 条件")
    private Long nextRunAt;

    @Column(name = "entity_version", nullable = false,
            comment = "乐观并发版本号（人工编辑用），与 next_run_at 的抢占语义分开")
    private int entityVersion;
}
