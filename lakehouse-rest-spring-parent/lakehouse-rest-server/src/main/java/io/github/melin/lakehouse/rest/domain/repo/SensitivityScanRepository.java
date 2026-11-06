package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityScanEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * 敏感数据扫描的读写。
 *
 * <p><b>与优化批次同形：状态推进走条件更新。</b>A 路线（进程内）只在单实例内推进，
 * 但 B 路线（下推 Spark）的收尾来自回调，多副本下「读到什么」随时过期——
 * 因此终态写入一律写成「只在当前状态是我读到的那一个时才改」，用返回行数判定
 * 谁写成功。0 行意味着我输了，调用方不能继续做副作用。
 *
 * <p>P0 只用到 A 路线，因此这里用到的只有 {@code RUNNING → SUCCEEDED/FAILED}
 * 与「同表是否已有在跑的扫描」。B 路线的三个条件更新（抢占 / 提交回写 / 回收）
 * 待 P4 落地时再加——**先不写空实现**，因为那会让「A 路线不支持取消」
 * 这类事实被一堆无人调用的方法掩盖。
 *
 * <p><b>两条 {@code @Modifying} 各自带 {@code @Transactional}，不能省。</b>
 * 与 {@code SchedulerLeaseRepository} 同一个理由：这两个方法的调用上下文里都没有事务——
 * {@link #failOrphanedRunning} 来自 {@code ApplicationReadyEvent} 监听器，
 * {@link #finish} 来自 {@code SensitivityScanService#start}（那个方法刻意不加事务，
 * 见它的类注释）。而 Spring Data 的「仓储方法自带事务」只覆盖继承来的 CRUD 方法，
 * 自定义 {@code @Query} 方法不带；{@code @Modifying} 的批量 JPQL 在没有活动事务时
 * 直接抛 {@code TransactionRequiredException: No active transaction for update or delete query}。
 *
 * <p>**也不该指望在调用方补**：给 {@code cleanOrphanedRuns} 加类方法级
 * {@code @Transactional} 会造出更坏的结果——那个方法有意吞掉异常（表还没建出来时
 * 不该挡住启动），而事务里被吞掉的异常已经把事务标成 rollback-only，于是提交时抛
 * {@code UnexpectedRollbackException}，反而让上下文加载失败。局部化到语句上，
 * 失败就只是这一条语句回滚，catch 拿到的仍是原始的错误信息。
 */
public interface SensitivityScanRepository extends JpaRepository<SensitivityScanEntity, String> {

    /** 全局列表，最近的在最前。 */
    List<SensitivityScanEntity> findAllByOrderByCreatedAtDesc();

    /** 某个 catalog 的历史。 */
    List<SensitivityScanEntity> findAllByCatalogNameOrderByCreatedAtDesc(String catalogName);

    /** 某张表的历史（表详情页的「最近扫过几次」）。 */
    List<SensitivityScanEntity> findAllByCatalogNameAndDatabaseNameAndTableNameOrderByCreatedAtDesc(
            String catalogName, String databaseName, String tableName);

    /**
     * 同一张表是否已有在跑的扫描（**并发闸门之一**）。
     *
     * <p>为什么要挡：两次扫描同一张表不只是浪费——它们在采样模式下会同时向存储侧
     * 拉数据，并把同一列的两条 {@code PENDING} finding 落进人审队列，
     * 审核人看到的是「同一条结论问了两遍」。
     *
     * <p>注意它挡的是**同一张表**，不是同一个 catalog：catalog 级扫描期间
     * 允许对某张表单独再扫（用户刚改完这张表想立刻验证），此时两条扫描
     * 扫的范围重叠但语义不同，不该互相阻塞。
     */
    Optional<SensitivityScanEntity> findFirstByCatalogNameAndDatabaseNameAndTableNameAndStatusInOrderByCreatedAtDesc(
            String catalogName, String databaseName, String tableName, Collection<String> statuses);

    /** 某个 catalog 里处于这些状态的扫描数（用于「这个 catalog 正在扫」的提示）。 */
    List<SensitivityScanEntity> findByCatalogNameAndStatusIn(String catalogName, Collection<String> statuses);

    long countByStatusIn(Collection<String> statuses);

    /**
     * 写终态（A 路线）。
     *
     * <p>条件里带状态集合而没有代数，是因为 A 路线没有提交租约：真正的互斥点是
     * 「谁先把状态改掉」。多副本下两台机器同时读到同一条 {@code RUNNING}
     * 只可能出现在 B 路线（A 路线的执行者是发起请求的那个线程），
     * 但把条件写全了，B 路线接上来时这段不需要改。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.status = :final, s.finishedAt = :now, s.errorMessage = :error,"
            + "     s.tablesScanned = :tables, s.columnsScanned = :columns,"
            + "     s.findingsCreated = :findings, s.tablesFailed = :failed, s.updatedAt = :now"
            + " where s.id = :id and s.status in :from")
    int finish(@Param("id") String id, @Param("final") String finalStatus, @Param("error") String error,
               @Param("tables") int tablesScanned, @Param("columns") int columnsScanned,
               @Param("findings") int findingsCreated, @Param("failed") int tablesFailed,
               @Param("from") Collection<String> from, @Param("now") long now);

    /**
     * 进程重启后清理遗留的 {@code RUNNING} 扫描。
     *
     * <p>A 路线的执行线程随进程消亡，重启后磁盘上那些 {@code RUNNING} 行永远不会
     * 有收尾——不清掉的话，「这个 catalog 正在扫」的提示会一直亮着，
     * 同表闸门也会永久挡住重扫。用**启动时刻**作为分界：比进程启动还早的行
     * 一定是上一个进程留下的。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.status = 'FAILED', s.errorMessage = :error, s.finishedAt = :now, s.updatedAt = :now"
            + " where s.status = 'RUNNING' and s.createdAt < :startedBefore")
    int failOrphanedRunning(@Param("error") String error, @Param("startedBefore") long startedBefore,
                            @Param("now") long now);
}
