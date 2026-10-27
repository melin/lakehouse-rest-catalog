package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 调度通道租约：一行一个「谁该跑这一拍」的凭据。
 *
 * <p><b>为什么是租约而不是锁。</b>锁要靠「持锁进程活着」这个前提，而多副本部署里最常发生的
 * 故障恰恰是进程不告而别（{@code kill -9}、OOMKilled、节点宕机）——锁会永久留在那里。
 * 租约带过期时间：持有者崩了，到期即失效，别的实例自然接手；代价只是「空窗一个 TTL」。
 *
 * <p><b>为什么只有一张表、三行。</b>调度通道是一个枚举，不是业务数据：{@code optimization.scan}、
 * {@code optimization.dispatch}、{@code lineage.cleanup}。行在启动时补齐（见
 * {@code SchedulerLease}），不在 DDL 里塞种子数据——{@code sql/schema-mysql.sql}
 * 是从实体生成的产物，写进去会在下次生成时丢失。
 *
 * <p><b>{@code fencing_token} 的用途是「认出过期持有者」</b>：每次易主 +1，所以
 * 「我是第几代」这件事可判定。但正确性不依赖它——真正确保不重复的是各处的条件更新
 * （出批的 {@code next_run_at} CAS、提交的 {@code claim_attempt}、终态的 {@code status} CAS）。
 * 租约只负责把「绝大多数拍次」收敛成单写者，减少白跑与相互阻塞。
 */
@Entity
@Table(name = "lakehouse_scheduler_lease",
        comment = "调度通道租约（多副本部署下的单写者）")
@Getter
@Setter
public class SchedulerLeaseEntity {

    /** 通道名：{@code optimization.scan} / {@code optimization.dispatch} / {@code lineage.cleanup}。 */
    @Id
    @Column(name = "name", length = 64, nullable = false, comment = "通道名")
    private String name;

    /**
     * 当前持有者（实例标识 = {@code hostname/pid}）。
     *
     * <p>不叫 {@code owner}：本项目的审计列里 {@code created_by} / {@code updated_by} 表示
     * 「归属主体」，复用一个同义字段名会让「这一行属于谁」和「谁在跑」在日志里混淆。
     */
    @Column(name = "lease_owner", length = 128, comment = "当前持有者（实例标识 = hostname/pid）")
    private String leaseOwner;

    @Column(name = "acquired_at", comment = "本轮租约开始时刻（epoch 毫秒）")
    private Long acquiredAt;

    /** 到期时刻。到期即可被他人抢走；{@code 0} 表示持有者主动让出（停机）。 */
    @Column(name = "expires_at", comment = "租约到期时刻（epoch 毫秒）；到期即可被他人抢走")
    private Long expiresAt;

    /** 代数：每次易主 +1，用于识别过期持有者。 */
    @Column(name = "fencing_token", nullable = false, comment = "代数：每次易主 +1，用于识别过期持有者")
    private long fencingToken;

    @Column(name = "updated_at", comment = "最后续约时刻（epoch 毫秒）")
    private Long updatedAt;
}
