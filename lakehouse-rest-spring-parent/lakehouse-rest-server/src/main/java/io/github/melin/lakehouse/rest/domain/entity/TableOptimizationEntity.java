package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.StringMap;
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
 * 表存储优化策略：一张表一行，记录总开关、三项动作各自的开关与配置差异。
 *
 * <p><b>配置为什么是「差异」而不是「全量」。</b>三项动作各有一组默认值，
 * 默认值属于 catalog（{@code optimization.*} 前缀的 catalog 属性，与
 * {@code table-default.*} 同一套做法），表级只写用户显式改过的键。
 * 于是「系统改了默认值」这件事对没配过的表自动生效——若这里存全量快照，
 * 默认值的调整永远追不上已经建过策略的表，用户看到的历史默认值会一直留在那里。
 * 代价是配置的最终取值要三层合并（内置默认 → catalog → 表），合并只发生在一处
 * （{@code OptimizationOptions#resolve}）。
 *
 * <p>{@code catalog_id} 是冗余列：组批按 catalog 分组，调度扫描一次就要按它归并，
 * 走 {@code table_id} 反查两张表既慢又没必要。它与 {@code paimon_table.catalog_id}
 * 由写入方保持一致。
 *
 * <p>{@code next_run_at} 是调度游标，也是**分布式抢占的落点**：出批前用
 * 「{@code UPDATE ... WHERE id = ? AND next_run_at = ?}」把它推到下一个周期，
 * 更新行数为 1 的表才进这一批。多副本部署下靠这条条件更新互斥，不引入额外锁。
 */
@Entity
@Table(name = "paimon_table_optimization",
        uniqueConstraints = @UniqueConstraint(name = "uk_table_optimization", columnNames = "table_id"),
        indexes = {
                @Index(name = "ix_table_optimization_catalog", columnList = "catalog_id"),
                @Index(name = "ix_table_optimization_due", columnList = "enabled_flag,next_run_at")
        })
@Getter
@Setter
public class TableOptimizationEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "table_id", length = 64, nullable = false)
    private String tableId;

    /** 冗余的 catalog 归属，组批与按 catalog 过滤都读它。 */
    @Column(name = "catalog_id", length = 64, nullable = false)
    private String catalogId;

    /** 总开关。关掉表示三项动作全部停止，但已配置的参数保留。 */
    @Column(name = "enabled_flag", nullable = false)
    private boolean enabled;

    /** 调度间隔（秒）：这张表多久参与一次组批。 */
    @Column(name = "schedule_interval_seconds", nullable = false)
    private int scheduleIntervalSeconds = 3600;

    /** 下次到期时间（epoch 毫秒）。 */
    @Column(name = "next_run_at")
    private Long nextRunAt;

    /** 最近一次把这张表纳入的批次 id。 */
    @Column(name = "last_job_id", length = 64)
    private String lastJobId;

    @Column(name = "compaction_enabled_flag", nullable = false)
    private boolean compactionEnabled = true;

    /**
     * Compaction 的参数差异（只存用户显式改过的键）。
     *
     * <p><b>为什么是 {@code text} 而不是 {@code varchar(8192)}。</b>三个配置列原本都是
     * {@code varchar(8192)}，在 utf8mb4 下每列计入行大小的上限是 {@code 8192 × 4 = 32768}
     * 字节，三列合计 98304，超过 InnoDB 的行大小上限 65535，建表时报
     * {@code ERROR 1118 Row size too large}。Hibernate 按 {@code @Column(length)}
     * 选类型，写成 65535 即 {@code text}，每列只计入约 10 字节。
     *
     * <p>65535 字节（utf8mb4 下约 1.6 万字符）对这张「只存差异」的表绰绰有余：键集由
     * {@code OptimizationOptions.KNOWN_KEYS} 限定，值也都很短。这条例的是 MySQL 的
     * <b>行</b>上限，与列本身的长度无关——单看一列 32768 是合法的，三列同表才越界。
     * 该约束由 {@code MysqlDdlGeneratorTests} 的行大小预算断言守住，避免下一个
     * {@code varchar(8192)} 再踩一次。
     */
    @Convert(converter = StringMap.class)
    @Column(name = "compaction_config_json", length = 65535)
    private Map<String, String> compactionConfig = new LinkedHashMap<>();

    @Column(name = "snapshot_expire_enabled_flag", nullable = false)
    private boolean snapshotExpireEnabled = true;

    /** Snapshot Expire 的参数差异；类型取 {@code text}，理由见 {@link #compactionConfig}。 */
    @Convert(converter = StringMap.class)
    @Column(name = "snapshot_expire_config_json", length = 65535)
    private Map<String, String> snapshotExpireConfig = new LinkedHashMap<>();

    @Column(name = "orphan_remove_enabled_flag", nullable = false)
    private boolean orphanRemoveEnabled;

    /** 孤儿文件清理的参数差异；类型取 {@code text}，理由见 {@link #compactionConfig}。 */
    @Convert(converter = StringMap.class)
    @Column(name = "orphan_remove_config_json", length = 65535)
    private Map<String, String> orphanRemoveConfig = new LinkedHashMap<>();
}
