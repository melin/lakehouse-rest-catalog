package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.TableSnapshotEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TableSnapshotRepository extends JpaRepository<TableSnapshotEntity, String> {

    Optional<TableSnapshotEntity> findByTableIdAndSnapshotId(String tableId, long snapshotId);

    Optional<TableSnapshotEntity> findByTableIdAndUuid(String tableId, String uuid);

    Optional<TableSnapshotEntity> findFirstByTableIdOrderBySnapshotIdDesc(String tableId);

    /**
     * 快照 id 严格小于给定值的最新一行：累计文件统计的基线。
     *
     * <p>不用「当前最新一行」是因为提交可能重放——同一个快照 id 被客户端重试时，
     * 基线必须还是它之前那一行，否则同一批增量会被算两遍。
     */
    Optional<TableSnapshotEntity> findFirstByTableIdAndSnapshotIdLessThanOrderBySnapshotIdDesc(
            String tableId, long snapshotId);

    /** {@code EARLIEST}：按快照 id 取最早的那个。 */
    Optional<TableSnapshotEntity> findFirstByTableIdOrderBySnapshotIdAsc(String tableId);

    List<TableSnapshotEntity> findAllByTableIdOrderBySnapshotIdDesc(String tableId);

    long deleteByTableIdAndSnapshotIdGreaterThan(String tableId, long snapshotId);

    /**
     * 删掉一张表的全部快照行。
     *
     * <p>给 Iceberg 面用：那张表的快照是 {@code metadata_doc} 的派生投影，
     * 删表时由 {@code IcebergProjections#deleteAll} 整批清掉。
     * Paimon 面的删表路径走的是按 id 逐个删（那里还要保留「目标之后」的语义），
     * 因此这条只服务 Iceberg 侧——但这不构成两条路径的语义分叉：
     * 两者都要求「表实体消失之后，快照行不留下孤儿」。
     */
    long deleteByTableId(String tableId);

    // ---------------------------------------------------------------- 存储页聚合
    //
    // 为控制台「存储」页的聚合端点而生（ManagementCatalogController#tableStats 与
    // #tableRows）：控制台此前是「一张表一个请求」逐个取数，表多时是一条请求队列。
    // 这一组查询都建立同一个投影 join 上——
    //
    //   lakehouse_table  left join  当前快照行（snapshot_id = latest_snapshot_id）
    //
    // left join 保证还没有快照的表也有一行（统计列为 null，即「无快照」），
    // 与逐表端点的语义一致。所有汇总在 SQL 里算完，浏览器只收结果——
    // 表再多也是常数次查询，与表数无关。
    //
    // 返回 Object[] 而不是投影接口：列只在 ManagementCatalogService 一处被映射
    // 进 DTO，中间多一个接口类型没有读者。

    /**
     * 逐表明细的一页：可按库 / 关键字过滤、按列排序，limit/offset 由 {@link Pageable} 下推。
     *
     * <p>排序表达式由服务层用 {@code JpaSort#unsafe} 传入（白名单映射，见
     * {@code ManagementCatalogService#SORT_EXPRESSIONS}）——「平均文件大小」这类派生列
     * 没有实体字段，只能用原始表达式排序。
     */
    @Query("""
            select d.name, t.name, t.tableType, s.snapshotId,
                   s.fileCount, s.fileSizeInBytes, s.recordCount, s.deleteFileCount, s.timeMillis
            from TableEntity t
              join DatabaseEntity d on d.id = t.databaseId
              left join TableSnapshotEntity s on s.tableId = t.id and s.snapshotId = t.latestSnapshotId
            where t.catalogId = :catalogId
              and (:database is null or d.name = :database)
              and (:onlyPaimon = false or t.tableType = 'PAIMON')
              and (:keyword is null or lower(concat(d.name, '.', t.name)) like lower(concat('%', :keyword, '%')))
            """)
    List<Object[]> findCatalogTableRows(@Param("catalogId") String catalogId,
                                        @Param("database") String database,
                                        @Param("onlyPaimon") boolean onlyPaimon,
                                        @Param("keyword") String keyword,
                                        Pageable pageable);

    /** 逐表明细的总行数（与 {@link #findCatalogTableRows} 同一where，给远程分页的 total）。 */
    @Query("""
            select count(t.id)
            from TableEntity t
              join DatabaseEntity d on d.id = t.databaseId
              left join TableSnapshotEntity s on s.tableId = t.id and s.snapshotId = t.latestSnapshotId
            where t.catalogId = :catalogId
              and (:database is null or d.name = :database)
              and (:onlyPaimon = false or t.tableType = 'PAIMON')
              and (:keyword is null or lower(concat(d.name, '.', t.name)) like lower(concat('%', :keyword, '%')))
            """)
    long countCatalogTableRows(@Param("catalogId") String catalogId,
                               @Param("database") String database,
                               @Param("onlyPaimon") boolean onlyPaimon,
                               @Param("keyword") String keyword);

    /**
     * 整个 catalog 的汇总读数（单行聚合）。返回 List 是因为 Spring Data 会把
     * 「一行本身是 Object[]」的结果再包一层——取首行才是这 11 列。
     *
     * <p>列序（服务层按位取）：
     * {@code tables, empty, unknown, files, size, records, deleteFiles,
     * filesWithSize, smallTables, smallFiles, smallSize}。
     * 口径与前端旧实现一致：「无快照」（快照行缺失）与「缺统计」（有快照行但统计列空）
     * 是两件事；小文件判据是表级平均 {@code size / fileCount < 阈值}。
     */
    @Query("""
            select count(t.id),
                   sum(case when s.snapshotId is null then 1 else 0 end),
                   sum(case when s.snapshotId is not null and s.fileCount is null then 1 else 0 end),
                   sum(coalesce(s.fileCount, 0)),
                   sum(coalesce(s.fileSizeInBytes, 0)),
                   sum(coalesce(s.recordCount, 0)),
                   sum(coalesce(s.deleteFileCount, 0)),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null then s.fileCount else 0 end),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount < :smallThreshold then 1 else 0 end),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount < :smallThreshold then s.fileCount else 0 end),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount < :smallThreshold then s.fileSizeInBytes else 0 end)
            from TableEntity t
              join DatabaseEntity d on d.id = t.databaseId
              left join TableSnapshotEntity s on s.tableId = t.id and s.snapshotId = t.latestSnapshotId
            where t.catalogId = :catalogId
              and (:onlyPaimon = false or t.tableType = 'PAIMON')
            """)
    List<Object[]> summarizeCatalogTables(@Param("catalogId") String catalogId,
                                          @Param("onlyPaimon") boolean onlyPaimon,
                                          @Param("smallThreshold") long smallThreshold);

    /**
     * 按库聚合（「按库的存储」条形图）：每库一行，列序
     * {@code database, tables, files, size, small, unknown}，按存储降序。
     */
    @Query("""
            select d.name,
                   count(t.id),
                   sum(coalesce(s.fileCount, 0)),
                   sum(coalesce(s.fileSizeInBytes, 0)),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount < :smallThreshold then 1 else 0 end),
                   sum(case when s.snapshotId is not null and s.fileCount is null then 1 else 0 end)
            from TableEntity t
              join DatabaseEntity d on d.id = t.databaseId
              left join TableSnapshotEntity s on s.tableId = t.id and s.snapshotId = t.latestSnapshotId
            where t.catalogId = :catalogId
              and (:onlyPaimon = false or t.tableType = 'PAIMON')
            group by d.name
            order by sum(coalesce(s.fileSizeInBytes, 0)) desc, d.name asc
            """)
    List<Object[]> groupCatalogTablesByDatabase(@Param("catalogId") String catalogId,
                                                @Param("onlyPaimon") boolean onlyPaimon,
                                                @Param("smallThreshold") long smallThreshold);

    /**
     * 平均文件大小的五档分布（直方图）：列序 {@code <1MB, <10MB, <100MB, <1GB, ≥1GB, unknown}。
     * 判据与汇总的小文件一致（表级平均）；「缺统计」的表不计入任何一档。
     */
    @Query("""
            select sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount < 1048576 then 1 else 0 end),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount >= 1048576
                            and s.fileSizeInBytes / s.fileCount < 10485760 then 1 else 0 end),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount >= 10485760
                            and s.fileSizeInBytes / s.fileCount < 104857600 then 1 else 0 end),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount >= 104857600
                            and s.fileSizeInBytes / s.fileCount < 1073741824 then 1 else 0 end),
                   sum(case when s.fileCount is not null and s.fileCount > 0
                            and s.fileSizeInBytes is not null
                            and s.fileSizeInBytes / s.fileCount >= 1073741824 then 1 else 0 end),
                   sum(case when s.snapshotId is not null and s.fileCount is null then 1 else 0 end)
            from TableEntity t
              join DatabaseEntity d on d.id = t.databaseId
              left join TableSnapshotEntity s on s.tableId = t.id and s.snapshotId = t.latestSnapshotId
            where t.catalogId = :catalogId
              and (:onlyPaimon = false or t.tableType = 'PAIMON')
            """)
    List<Object[]> bucketCatalogTables(@Param("catalogId") String catalogId,
                                       @Param("onlyPaimon") boolean onlyPaimon);
}
