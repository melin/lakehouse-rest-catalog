package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.DataPolicyEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 表级数据策略的仓储。
 *
 * <p>键是三段全名的 SHA-256（{@code full_name_hash}，为什么用摘要见
 * {@link io.github.melin.lakehouse.rest.domain.entity.DataPolicyEntity} 的类注释），
 * 所以单表操作一律走 hash 查找；三段原名列用于列表页的展示与按 catalog 过滤。
 */
public interface DataPolicyRepository extends JpaRepository<DataPolicyEntity, Long> {

    /** 按三段全名摘要找策略；表上的策略至多一条（唯一键保证）。 */
    Optional<DataPolicyEntity> findByFullNameHash(String fullNameHash);

    /** 批量解析用：一条 SQL 拿一批表的策略（引擎解析一次查询常扫多张表）。 */
    List<DataPolicyEntity> findByFullNameHashIn(Collection<String> fullNameHashes);

    /** 按 catalog 过滤的全量列表（量级是「几十」，内存切页见 {@code DataPolicyService#list}）。 */
    List<DataPolicyEntity> findByCatalogName(String catalogName);
}
