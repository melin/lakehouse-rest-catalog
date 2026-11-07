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
 * <p><b>A 路线只用到 {@code RUNNING → SUCCEEDED/FAILED}</b> 与「同表是否已有在跑的扫描」；
 * <b>B 路线（下推 Spark）另用四个条件更新</b>：抢占（{@code claimForSubmission}）、
 * 提交回写（{@code markSubmitted}）、回收（{@code reclaimSubmission}）与逐表计数累加
 * （{@code addItemCounts}）。这四条都是 P4 落地的，**不是空实现**——每一条都有调用方
 * （{@code SensitivityScanDispatcher} 与 {@code SensitivityScanService#reportItem}）。
 *
 * <p><b>B 路线没有 {@code deadline_at} 列</b>，超时由 {@code startedAt + scanJobTimeoutSeconds}
 * 现算：扫描的超时随 catalog 配置变，落一个绝对值会让「调小超时」对已经在跑的扫描
 * 不生效——而那正是最需要它生效的时刻。
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

    // ------------------------------------------------------------------ B 路线：提交租约

    /** 待提交 / 运行中 / 提交中的扫描，调度节拍按状态各取一批。 */
    List<SensitivityScanEntity> findTop50ByStatusOrderByCreatedAtAsc(String status);

    List<SensitivityScanEntity> findTop50ByStatusInOrderByCreatedAtAsc(Collection<String> statuses);

    /**
     * 某个 catalog 里处于这些状态的扫描数——**与优化批次共用的那把闸门**数的就是它。
     *
     * <p><b>参数是 catalog 名而不是 id</b>：扫描表上只有 {@code catalog_name}
     * （与标签表同一个口径：名字进哈希、删 catalog 不影响审计），优化批次表上才是
     * {@code catalog_id}。写成 {@code countByCatalogId...} 会让 Spring Data 在**启动时**
     * 就抛 {@code No property 'catalogId' found for type 'SensitivityScanEntity'}——
     * 整个服务起不来，而不是「这个计数永远为 0」。
     */
    long countByCatalogNameAndStatusIn(String catalogName, Collection<String> statuses);

    /**
     * 提交租约已过期的 {@code SUBMITTING} 扫描（被回收的候选）。
     *
     * <p>按 {@code claim_expires_at} 升序：卡得最久的先回收，避免某一条被反复跳过。
     */
    List<SensitivityScanEntity> findTop50ByStatusAndClaimExpiresAtLessThanOrderByClaimExpiresAtAsc(
            String status, long now);

    /**
     * 抢占一次待提交的扫描，顺手把提交租约写成自己的（带代数）。
     *
     * <p>与优化批次那条同形，但**没有 {@code claim_owner} 列**：扫描表在设计稿 §4.2 里
     * 只有 {@code claim_attempt} 与 {@code claim_expires_at}。少一列只影响排查时
     * 「是哪台机器卡住的」这一个问题的可答性，不影响正确性——正确性由
     * 「状态 + 代数 + 到期时刻」三个条件共同保证。要补这一列得走一次迁移，
     * 而现在它换不来一条新的判定。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            // coalesce 不是多余的：这一列在**新行上是 NULL**（建单时不写它），而
            // SQL 里 NULL + 1 还是 NULL。少了它，抢占会「成功」（状态确实变成 SUBMITTING），
            // 但代数永远是 NULL，于是 markSubmitted 的 `claimAttempt = :attempt` 永不成立、
            // findClaimAttempt 返回 null —— 整条扫描安静地卡在 SUBMITTING，等到租约过期再被
            // 回收、再卡一次。这是「不报错、只是不动」的典型形状
            + " set s.status = 'SUBMITTING', s.claimExpiresAt = :expires,"
            + "     s.claimAttempt = coalesce(s.claimAttempt, 0) + 1, s.updatedAt = :now"
            + " where s.id = :id and s.status = 'PENDING'")
    int claimForSubmission(@Param("id") String id, @Param("now") long now, @Param("expires") long expires);

    /** 回读当前代数（抢占后在同一事务里读，读到的必然是刚写的那一代）。 */
    @Query("select s.claimAttempt from SensitivityScanEntity s where s.id = :id")
    Integer findClaimAttempt(@Param("id") String id);

    /**
     * 提交成功回写：{@code SUBMITTING → RUNNING}，写 Job 坐标、令牌哈希与开始时刻。
     *
     * <p>两个条件缺一不可：{@code status = 'SUBMITTING'} 挡住「已经被取消 / 已经收尾」的行；
     * {@code claim_attempt = :attempt} 挡住「我的租约已被回收、别人用新代数接管」。
     * 写 {@code started_at} 是超时收尾的起点——扫描表没有 {@code deadline_at} 列，
     * 截止时刻由 {@code startedAt + scanJobTimeoutSeconds} 现算。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.status = 'RUNNING', s.executionNamespace = :namespace,"
            + "     s.executionResource = :resource, s.callbackTokenHash = :tokenHash,"
            + "     s.startedAt = :now, s.claimExpiresAt = null, s.updatedAt = :now"
            + " where s.id = :id and s.status = 'SUBMITTING' and s.claimAttempt = :attempt")
    int markSubmitted(@Param("id") String id, @Param("namespace") String namespace,
                      @Param("resource") String resource, @Param("tokenHash") String tokenHash,
                      @Param("attempt") int attempt, @Param("now") long now);

    /**
     * 回收：租约过期且没人接着推进的 {@code SUBMITTING} 扫描回到 {@code PENDING}。
     *
     * <p>回到 {@code PENDING} 而不是判失败：卡住的可能只是「提交请求发出去了、回写前进程死了」，
     * 而集群里那个 Job 可能正在跑——重投会命中同名 Job（幂等），把「其实已经在跑」认出来。
     * 判失败则会让那次扫描永远停在 FAILED，而作业还在消耗集群算力。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.status = 'PENDING', s.claimExpiresAt = null, s.updatedAt = :now"
            + " where s.id = :id and s.status = 'SUBMITTING' and s.claimAttempt = :attempt"
            + "   and s.claimExpiresAt < :now")
    int reclaimSubmission(@Param("id") String id, @Param("attempt") int attempt, @Param("now") long now);

    /**
     * 逐表回调的计数累加（B 路线）。
     *
     * <p><b>写成 {@code +=} 而不是「读实体 → 算 → save」。</b>作业的回调是逐表、可能并发的，
     * 读改写会丢更新（两张表同时回调时，后写的那个把前一个的计数覆盖掉，现象是
     * 「扫了 100 张、计数说 97」）。条件 {@code status = 'RUNNING'} 让「取消之后迟到的回调」
     * 不落进任何计数里——那种计数没有归属。
     *
     * <p>0 行返回值的处置是**不报错**：迟到的回调在取消之后本来就会发生，把它变成 500
     * 只会让作业侧把一次正常的取消当成故障。调用方记 WARN 即可。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.tablesScanned = s.tablesScanned + :tables,"
            + "     s.columnsScanned = s.columnsScanned + :columns,"
            + "     s.findingsCreated = s.findingsCreated + :findings,"
            + "     s.tablesFailed = s.tablesFailed + :failed,"
            + "     s.updatedAt = :now"
            + " where s.id = :id and s.status = 'RUNNING'")
    int addItemCounts(@Param("id") String id, @Param("tables") int tables, @Param("columns") int columns,
                      @Param("findings") int findings, @Param("failed") int failed, @Param("now") long now);

    /**
     * 用户取消（B 路线）。
     *
     * <p>只认活跃状态（{@code PENDING} / {@code SUBMITTING} / {@code RUNNING}）：
     * 终态的扫描改不动，也就不会把一次已经成功的扫描改写成「已取消」。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.status = 'CANCELLED', s.finishedAt = :now, s.errorMessage = :error,"
            + "     s.claimExpiresAt = null, s.updatedBy = :principal, s.updatedAt = :now"
            + " where s.id = :id and s.status in :from")
    int cancel(@Param("id") String id, @Param("error") String error, @Param("principal") String principal,
               @Param("from") Collection<String> from, @Param("now") long now);

    /**
     * 提交失败：{@code SUBMITTING → FAILED}（带代数）。
     *
     * <p><b>提交失败是终态，不是「等下拍重试」</b>——与优化批次同一个判断：失败的原因通常是
     * 配置层面长期存在的问题（镜像拉不到、RBAC 少一条、namespace 不存在），盲目重投只会
     * 每分钟刷一遍同样的错误。要重来由用户显式再发起一次。
     *
     * <p>带 {@code claim_attempt} 是必须的：过期持有者的「失败」不该落到已经被别人接管的
     * 扫描上——那种情况下另一次提交可能正在路上。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.status = 'FAILED', s.finishedAt = :now, s.errorMessage = :error,"
            + "     s.claimExpiresAt = null, s.updatedAt = :now"
            + " where s.id = :id and s.status = 'SUBMITTING' and s.claimAttempt = :attempt")
    int failClaim(@Param("id") String id, @Param("error") String error, @Param("attempt") int attempt,
                  @Param("now") long now);

    /**
     * 只改状态的收尾（B 路线）。
     *
     * <p><b>为什么不能复用 {@link #finish}。</b>那个方法会把四个计数**写成传入值**——A 路线
     * 在内存里攒计数、收尾时一次写入，是对的；B 路线的计数是逐表回调累加进库的，收尾时
     * 内存里没有一份完整的分母。用它收尾会把「扫了 100 张」写成 0。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update SensitivityScanEntity s"
            + " set s.status = :final, s.finishedAt = :now, s.errorMessage = :error, s.updatedAt = :now"
            + " where s.id = :id and s.status in :from")
    int finishStatus(@Param("id") String id, @Param("final") String finalStatus, @Param("error") String error,
                     @Param("from") Collection<String> from, @Param("now") long now);
}
