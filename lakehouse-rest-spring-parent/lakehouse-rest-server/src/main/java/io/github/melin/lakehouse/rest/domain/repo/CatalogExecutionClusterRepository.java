package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.CatalogExecutionClusterEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogExecutionClusterRepository
        extends JpaRepository<CatalogExecutionClusterEntity, String> {

    /** 一个 catalog 至多一行（唯一约束在库里，这里给调用方一个不用处理 List 的入口）。 */
    Optional<CatalogExecutionClusterEntity> findByCatalogId(String catalogId);

    /**
     * 一批 catalog 的执行集群，供 catalog 列表一次 join 完。
     *
     * <p>不走逐 catalog 查询：列表一页 20 行就是 20 次往返，而这一列在每个视图上都要显示。
     */
    List<CatalogExecutionClusterEntity> findAllByCatalogIdIn(Collection<String> catalogIds);
}
