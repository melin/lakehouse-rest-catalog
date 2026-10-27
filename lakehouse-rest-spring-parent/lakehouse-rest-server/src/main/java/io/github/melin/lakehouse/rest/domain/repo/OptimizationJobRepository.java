package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 优化批次的读写。
 *
 * <p><b>状态推进一律走条件更新。</b>多副本部署下「读到什么」很快就会过期，
 * 所有写路径都写成「只在当前状态是我读到的那一个时才改」，用返回行数判定谁写成功。
 * 这类语句的返回值不是「影响了几行」这种统计信息，而是**抢占的结果**——0 行意味着
 * 我输了，调用方必须直接返回，不能继续做副作用（写明细、发事件）。
 */
public interface OptimizationJobRepository extends JpaRepository<OptimizationJobEntity, String> {

    List<OptimizationJobEntity> findAllByOrderByCreatedAtDesc();

    List<OptimizationJobEntity> findAllByCatalogIdOrderByCreatedAtDesc(String catalogId);

    /** 待提交与运行中的批次，调度节拍按状态各取一批。 */
    List<OptimizationJobEntity> findTop50ByStatusOrderByCreatedAtAsc(String status);

    List<OptimizationJobEntity> findTop50ByStatusInOrderByCreatedAtAsc(Collection<String> statuses);

    /** 某张表是否已有在跑的批次（防止同表重复入批）。 */
    Optional<OptimizationJobEntity> findFirstByCatalogIdAndStatusIn(String catalogId, Collection<String> statuses);

    long countByStatusIn(Collection<String> statuses);

    /**
     * 某个 catalog 里处于这些状态的批次数。
     *
     * <p>派发的并发闸门按 **catalog** 数，不再按全局数：每个 catalog 有自己的执行集群
     * 与 {@code maxConcurrentJobs}，全局计数会让「A 跑满」把 B 也堵住。
     */
    long countByCatalogIdAndStatusIn(String catalogId, Collection<String> statuses);

    /**
     * 提交租约已过期、需要回收的批次。
     *
     * <p>按 {@code claim_expires_at} 升序：卡得最久的先回收，避免某个批次被反复跳过。
     */
    List<OptimizationJobEntity> findTop50ByStatusAndClaimExpiresAtLessThanOrderByClaimExpiresAtAsc(
            String status, long now);

    // ------------------------------------------------------------------ 提交租约

    /**
     * 抢占一个待提交批次，顺手把提交租约写成自己的（带代数）。
     *
     * <p>这是「抢占」与「加租约」的合并：两条分开写会留下「状态已是 SUBMITTING 但没有
     * 租约」的窗口，回收器读不到它，批次就永久卡住。
     *
     * <p>返回 1 后必须回读 {@code claim_attempt}（{@link #findClaimAttempt}）作为本次提交的代数：
     * MySQL 没有 {@code RETURNING}，而代数要靠它做后续的条件更新。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j"
            + " set j.status = 'SUBMITTING', j.claimOwner = :owner, j.claimExpiresAt = :expires,"
            + "     j.claimAttempt = j.claimAttempt + 1, j.updatedAt = :now"
            + " where j.id = :id and j.status = 'PENDING'")
    int claimForSubmission(@Param("id") String id, @Param("owner") String owner,
                           @Param("now") long now, @Param("expires") long expires);

    /** 回读当前代数（抢占后在同一事务里读，读到的必然是刚写的那一代）。 */
    @Query("select j.claimAttempt from OptimizationJobEntity j where j.id = :id")
    Integer findClaimAttempt(@Param("id") String id);

    /**
     * 提交成功回写。
     *
     * <p>{@code status = 'SUBMITTING' and claim_attempt = :attempt} 是两个条件：
     * 前者挡住「已经收尾的批次」（执行侧可能在提交后几秒内就失败并回了终态），
     * 后者挡住「我的租约已被回收、别人已经用新代数接管」。0 行 = 我没资格写，
     * 调用方丢弃结果即可，不能退化成无条件 {@code save}。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j"
            + " set j.status = 'RUNNING', j.submitter = :submitter, j.namespace = :namespace,"
            + "     j.resource = :resource, j.submittedAt = :now, j.startedAt = :now,"
            + "     j.deadlineAt = :deadline, j.claimOwner = null, j.claimExpiresAt = null,"
            + "     j.updatedAt = :now"
            + " where j.id = :id and j.status = 'SUBMITTING' and j.claimAttempt = :attempt")
    int markRunning(@Param("id") String id, @Param("submitter") String submitter,
                    @Param("namespace") String namespace, @Param("resource") String resource,
                    @Param("deadline") long deadline, @Param("attempt") int attempt,
                    @Param("now") long now);

    /**
     * 回收：租约过期且没人接着推进的 {@code SUBMITTING} 批次回到 {@code PENDING}。
     *
     * <p>带 {@code claim_attempt} 是第二道保险：过期之后可能已经有实例抢占并续上新租约，
     * 那条的 {@code expires_at} 在未来，条件自然不成立；写进 SQL 是为了让
     * 「我回收的是我读到的那一代」这件事可读，而不是靠推理。
     *
     * <p>回收安全的前提是**提交动作幂等**：{@code submit()} 的 Job 名只由批次 id 决定，
     * 且提交前先查同名资源，所以被回收后的重投会命中「已存在」而不是造出第二个 Job。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j"
            + " set j.status = 'PENDING', j.claimOwner = null, j.claimExpiresAt = null, j.updatedAt = :now"
            + " where j.id = :id and j.status = 'SUBMITTING' and j.claimAttempt = :attempt"
            + "   and j.claimExpiresAt < :now")
    int reclaimSubmission(@Param("id") String id, @Param("attempt") int attempt, @Param("now") long now);

    // ------------------------------------------------------------------ 终态收尾

    /**
     * 写终态（执行侧到达终态）。
     *
     * <p>条件里只有状态集合，没有代数：走到这一步时批次已经是 {@code RUNNING}，
     * 提交租约也已在 {@link #markRunning} 时清空。真正的互斥点是「谁先把状态改掉」——
     * 两台机器同时 poll 到同一个批次是**必然**发生的（多副本下每台都会扫
     * {@code RUNNING}），只有改到 1 行的那台才有资格发事件。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j"
            + " set j.status = :final, j.finishedAt = :now, j.errorMessage = :error,"
            + "     j.claimOwner = null, j.claimExpiresAt = null, j.updatedAt = :now"
            + " where j.id = :id and j.status in :from")
    int finish(@Param("id") String id, @Param("final") String finalStatus,
               @Param("error") String error, @Param("from") Collection<String> from,
               @Param("now") long now);

    /**
     * 写终态（提交阶段的失败，带代数）。
     *
     * <p>与 {@link #finish} 分开的理由：提交失败发生在 {@code SUBMITTING} 期间，
     * 过期持有者的「失败」不该落到已经由别人接管的批次上——带上代数才是同一个写者。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j"
            + " set j.status = :final, j.finishedAt = :now, j.errorMessage = :error,"
            + "     j.claimOwner = null, j.claimExpiresAt = null, j.updatedAt = :now"
            + " where j.id = :id and j.status in :from and j.claimAttempt = :attempt")
    int finishClaimed(@Param("id") String id, @Param("final") String finalStatus,
                      @Param("error") String error, @Param("from") Collection<String> from,
                      @Param("attempt") int attempt, @Param("now") long now);

    /**
     * 超时收尾。
     *
     * <p>{@code deadline_at < :now} 必须写进 {@code WHERE}，而不是靠调用方「刚才读过
     * 一遍觉得超时了」：另一台机器可能已经把批次判成 {@code SUCCESS}，
     * 此时这条更新返回 0 行，成功的结论不会被改写成部分成功（不变式 I5）。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j"
            + " set j.status = :final, j.finishedAt = :now, j.errorMessage = :error,"
            + "     j.claimOwner = null, j.claimExpiresAt = null, j.updatedAt = :now"
            + " where j.id = :id and j.status in :from"
            + "   and j.deadlineAt is not null and j.deadlineAt < :now")
    int finishAfterDeadline(@Param("id") String id, @Param("final") String finalStatus,
                            @Param("error") String error, @Param("from") Collection<String> from,
                            @Param("now") long now);

    /** 用户取消：只认活跃状态，终态的批次改不动（也就不会把成功改写成已取消）。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OptimizationJobEntity j"
            + " set j.status = 'CANCELED', j.finishedAt = :now, j.errorMessage = :error,"
            + "     j.updatedBy = :principal, j.claimOwner = null, j.claimExpiresAt = null,"
            + "     j.updatedAt = :now"
            + " where j.id = :id and j.status in :from")
    int cancel(@Param("id") String id, @Param("error") String error, @Param("principal") String principal,
               @Param("from") Collection<String> from, @Param("now") long now);
}
