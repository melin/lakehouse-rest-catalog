package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityFindingEntity;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 扫描命中的仓储。
 *
 * <p><b>finding 是历史，读路径以扫描为轴。</b>绝大多数查询是「看某次扫描的结果」
 * 或「看某列的历次命中」，因此只建这两条；没有「按置信度区间筛全部 finding」
 * 这类接口——那需要跨扫描聚合，正确的做法是先把结果收敛到 tag（结论只有一份），
 * 再查 tag。给出跨扫描的 finding 查询会鼓励调用方在历史表上做「当前状态」的判断，
 * 而这正是两张表分工要避免的事。
 */
public interface SensitivityFindingRepository extends JpaRepository<SensitivityFindingEntity, Long> {

    /** 一次扫描的全部命中（结果页）。 */
    List<SensitivityFindingEntity> findByScanIdOrderByConfidenceDesc(String scanId);

    /**
     * 一次扫描里待审的命中（人审队列，P1）。
     *
     * <p>按置信度降序：审核时间有限，先看最可能对的——一个 0.98 的命中点一下确认
     * 就完事，而 0.62 的需要看样本才知道，两者排在一起会让人先跳过难的那些。
     */
    List<SensitivityFindingEntity> findByScanIdAndStateOrderByConfidenceDesc(String scanId, String state);

    /** 某列的历次命中（列详情页的「这列被判过什么」）。 */
    List<SensitivityFindingEntity> findByColumnRefHashOrderByCreatedAtDesc(String columnRefHash);

    /** 全库待审队列（跨扫描，人审工作台的入口）。 */
    List<SensitivityFindingEntity> findTop200ByStateOrderByConfidenceDesc(String state);

    /**
     * 一个 catalog 的全部命中（跨扫描）。
     *
     * <p>P0 的读路径只有它一条「按 catalog 看结论」的入口：{@code #overview} 要算
     * 「待确认」与「自动应用率」两个比例，而这两个数的分母都是「这个 catalog 里
     * 目前为止判过什么」。按扫描逐次去取会变成「N 次扫描 N 个请求」——
     * 控制台刷新一屏发几十个请求，比一次全量载入慢得多。
     *
     * <p>它刻意**不**聚合到「当前状态」：finding 是历史，同一个结论会在多轮扫描里
     * 各留一行。要「现在这列是什么」请查 {@code ColumnTagRepository}——
     * 两张表的分工见 {@code ColumnTagEntity} 的类注释。
     */
    List<SensitivityFindingEntity> findByCatalogNameOrderByConfidenceDesc(String catalogName);

    long countByScanId(String scanId);

    long countByScanIdAndState(String scanId, String state);

    /**
     * 某次扫描里已知的列引用集合。
     *
     * <p>写 finding 前用它做去重判定：唯一键 {@code (scan_id, column_ref_hash, type_code)}
     * 本来就挡住重复，但**靠异常捕获来做幂等是错的**——一次扫描里几十个重复键会
     * 触发几十次约束冲突，事务在某些 DB 上会被标记为 rollback-only，
     * 后续「继续处理下一列」的意图就实现不了。所以先查一次集合，命中就跳过。
     */
    @Query("select f.columnRefHash from SensitivityFindingEntity f"
            + " where f.scanId = :scanId and f.typeCode = :typeCode")
    List<String> findColumnRefHashes(@Param("scanId") String scanId, @Param("typeCode") String typeCode);

    /** 某批列引用在某次扫描里已有的记录（重扫时用来判断哪几条要更新）。 */
    List<SensitivityFindingEntity> findByScanIdAndColumnRefHashIn(
            String scanId, Collection<String> columnRefHashes);

    /**
     * 一张表的全部命中（表被删 / 注销时级联清掉，设计稿 §7.3）。
     *
     * <p><b>刻意不加 {@code clearAutomatically}。</b>这个方法是**在删表的事务里**被调用的
     * （生命周期监听器是同步监听器）：{@code clear()} 会清空持久化上下文，
     * 把同一个事务里刚排队的 {@code delete table} 一起丢掉，症状是「删表返回 204、
     * 表却还在」。同款坑见 {@code LineageColumnEdgeRepository#deleteByRef}。
     */
    @Modifying
    @Query("delete from SensitivityFindingEntity f where f.catalogName = :catalog"
            + " and f.databaseName = :database and f.tableName = :table")
    int deleteByTable(@Param("catalog") String catalog, @Param("database") String database,
                      @Param("table") String table);
}
