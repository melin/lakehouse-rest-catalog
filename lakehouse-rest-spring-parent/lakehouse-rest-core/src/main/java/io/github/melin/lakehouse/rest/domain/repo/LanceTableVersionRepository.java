package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.LanceTableVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * {@link LanceTableVersionEntity} 的仓储。
 *
 * <p><b>删除走 {@code @Modifying} 的批量语句，但不开 {@code clearAutomatically}。</b>
 * 与优化批次那处的理由相同：批量删会绕过持久化上下文，若让 JPA 自动
 * {@code clear}，同一事务里随后加载的实体会被丢掉改动。这里删除后
 * 事务里紧接着要读的就是同一张表的行，开了那个开关反而会丢数据。
 */
public interface LanceTableVersionRepository extends JpaRepository<LanceTableVersionEntity, String> {

    Optional<LanceTableVersionEntity> findByTableIdAndVersion(String tableId, long version);

    List<LanceTableVersionEntity> findAllByTableIdOrderByVersionAsc(String tableId);

    /** 范围删除：{@code [start, end)}；{@code end} 为 {@code null} 表示到最大版本为止。 */
    @Modifying
    @Query("delete from LanceTableVersionEntity v where v.tableId = :tableId"
            + " and v.version >= :start"
            + " and (:end is null or v.version < :end)")
    int deleteRange(@Param("tableId") String tableId, @Param("start") long start,
                    @Param("end") Long end);

    long deleteByTableId(String tableId);

    long deleteByCatalogId(String catalogId);
}
