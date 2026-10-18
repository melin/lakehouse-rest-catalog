package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.AuditEventEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 目录事件的读取口。
 *
 * <p><b>查询形状只有增量拉取这一种</b>：给定游标（含「从头」= 取最新一页）与可选的
 * catalog 过滤，返回升序列表。不提供按类型 / 时间区间的任意组合查询——控制台之外
 * 还没有第二个消费方，等出现真实需求再加方法，而不是预先铺一组用不上的派生查询。
 */
public interface AuditEventRepository extends JpaRepository<AuditEventEntity, Long> {

    /** 最新一条；首次拉取用它定位游标起点。 */
    Optional<AuditEventEntity> findTopByOrderByIdDesc();

    /** 游标之后的升序列表（全目录）。 */
    List<AuditEventEntity> findByIdGreaterThanOrderByIdAsc(long id, Pageable pageable);

    /** 游标之后的升序列表（单目录）。 */
    List<AuditEventEntity> findByCatalogAndIdGreaterThanOrderByIdAsc(String catalog, long id,
                                                                     Pageable pageable);

    /** 最新一页（全目录），倒序取、由调用方反转。 */
    List<AuditEventEntity> findAllByOrderByIdDesc(Pageable pageable);

    /** 最新一页（单目录），倒序取、由调用方反转。 */
    List<AuditEventEntity> findByCatalogOrderByIdDesc(String catalog, Pageable pageable);
}
