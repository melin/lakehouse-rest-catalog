package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.ColumnTagEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 列级标签的仓储。
 *
 * <p><b>唯一入口是 {@code (column_ref_hash, tag_key)}</b>——列级全名的原名列
 * （catalog / database / table / column）只用于展示与按 catalog 过滤，
 * 不能用来定位单条记录：三段 + 列名的联合条件在任何 DB 上都无法走唯一键
 * （索引长度超限，见 {@code ColumnTagEntity} 的类注释），实际会退化成全表扫。
 */
public interface ColumnTagRepository extends JpaRepository<ColumnTagEntity, Long> {

    /** 一列一个标签键只有一个值（唯一键保证），所以是 {@code Optional} 而不是 {@code List}。 */
    Optional<ColumnTagEntity> findByColumnRefHashAndTagKey(String columnRefHash, String tagKey);

    /** 一列的全部标签（{@code PII.CN_ID_CARD} 与 {@code owner.team} 并存）。 */
    List<ColumnTagEntity> findByColumnRefHash(String columnRefHash);

    /**
     * 一次性取多列的全部标签。
     *
     * <p>表详情页要渲染一整张表的标签，逐列查会变成「N 列 N 次查询」；
     * 而哈希是与列一一对应的定长值，批量条件不会超长——这正是用摘要列的好处。
     */
    List<ColumnTagEntity> findByColumnRefHashIn(Collection<String> columnRefHashes);

    /** 按 catalog 过滤的全量列表（量级「几十到几百」，内存切页）。 */
    List<ColumnTagEntity> findByCatalogName(String catalogName);

    /** 取某张表的全部标签（**含表级标签**，其 {@code column_name} 是空串）。 */
    List<ColumnTagEntity> findByCatalogNameAndDatabaseNameAndTableName(
            String catalogName, String databaseName, String tableName);

    /**
     * 表级标签（{@code column_name} 是空串）。
     *
     * <p>表级标签的值是「该表所有列标签的 {@code max(level)}」的聚合结果，
     * 重算时需要先拿到旧的那一条做比对（只有级别变了才写，否则每次扫描都会把
     * 全库的表级标签各写一遍，审计里全是噪声）。
     */
    List<ColumnTagEntity> findByCatalogNameAndDatabaseNameAndTableNameAndColumnName(
            String catalogName, String databaseName, String tableName, String columnName);

    /** 血缘继承（P2）要按来源筛。 */
    List<ColumnTagEntity> findBySource(String source);
}
