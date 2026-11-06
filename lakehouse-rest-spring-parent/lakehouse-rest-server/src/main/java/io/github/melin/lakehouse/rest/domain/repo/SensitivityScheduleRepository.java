package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityScheduleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 调度项的读写，以及**节拍用的那一句条件更新**。
 *
 * <p>{@link #claim} 是本仓储里唯一不是 CRUD 的方法，也是「一条调度项一拍只触发一次」
 * 的全部实现：多副本下每台实例都读到同一个「到期」，只有当它读到的
 * {@code next_run_at} 仍是库里那个值时，UPDATE 才会命中一行。命中即意味着
 * 「这一拍归我推」，其余实例的 UPDATE 影响 0 行、安静地跳过。
 *
 * <p><b>为什么不加分布式锁。</b>租约（{@code SchedulerLease} 的
 * {@code sensitivity.scan} 通道）已经把「这一拍谁扫调度表」缩到一台实例了；
 * {@code next_run_at} 的 CAS 是第二道，守的是「租约在扫描期间过期、另一台接手」
 * 这种租约本身兜不住的情形。两道都在，才既不多跑也不漏跑——租约失效时 CAS 仍然
 * 保证一次到期只被推进一次。反过来只靠租约，一次慢扫描撞上租约到期就会双触发。
 */
public interface SensitivityScheduleRepository extends JpaRepository<SensitivityScheduleEntity, Long> {

    Optional<SensitivityScheduleEntity> findByName(String name);

    List<SensitivityScheduleEntity> findAllByOrderByNameAsc();

    List<SensitivityScheduleEntity> findAllByCatalogNameOrderByNameAsc(String catalogName);

    /**
     * 本拍到期且启用的调度项。
     *
     * <p>取 50 条与表优化那边同一口径：一拍处理不完全部到期项是正常的（下一拍接着来），
     * 而无上限会让「一次改了很多条 cron」变成一个长事务。
     *
     * <p>{@code next_run_at is null} 的项**不在这里**：那表示「还没算过」，
     * 由 {@code SensitivityScheduleService#backfillNextRunAt} 负责补，不是「立刻到期」。
     */
    List<SensitivityScheduleEntity> findTop50ByEnabledFlagTrueAndNextRunAtLessThanEqualOrderByNextRunAtAsc(
            long now);

    List<SensitivityScheduleEntity> findByEnabledFlagTrueAndNextRunAtIsNull();

    /**
     * 抢占这一拍，并把 {@code next_run_at} 推到下一拍。
     *
     * <p><b>推进写在抢占同一条语句里，不是「先抢占、跑完再推进」。</b>后者的表现是
     * 「扫描跑了五分钟，这五分钟里每一拍都把它当成到期项」——如果中间某拍租约易主，
     * 接手的那台会再触发一次同一条调度（同表闸门会挡住表级扫描，但 catalog 级没有
     * 那道闸门）。先推进的代价是「这一拍触发的扫描失败了，下一次要等到下一个 cron 点」
     * ——对定时任务来说这是正确语义：失败要人看，而不是每分钟重试同一件失败的事。
     *
     * <p>{@code enabled_flag = true} 再判一次：停用与节拍命中可能同拍发生，停用的语义
     * 是「立刻不再触发」，不该被一条已经在内存里的候选集绕过去。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScheduleEntity s"
            + " set s.lastRunAt = :now, s.nextRunAt = :next, s.updatedAt = :now"
            + " where s.id = :id and s.enabledFlag = true and s.nextRunAt = :expectedNext")
    int claim(@Param("id") Long id, @Param("expectedNext") long expectedNext,
              @Param("now") long now, @Param("next") long next);

    /** 算下次运行时刻（新建 / 改 cron / 补空值时用）；不参与抢占。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScheduleEntity s set s.nextRunAt = :next, s.updatedAt = :now"
            + " where s.id = :id")
    int scheduleNextRun(@Param("id") Long id, @Param("next") long next, @Param("now") long now);
}
