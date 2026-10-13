package io.github.melin.paimonrest.domain.repo;

import io.github.melin.paimonrest.domain.entity.PermissionAssignmentEntity;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Paimon 原生权限赋权的读写。
 *
 * <p>查询只有两个方向：按 catalog 全量列（权限列表端点，量小、内存过滤分页），
 * 与按「catalog + principal 候选串」取（授权判定，见 {@code PaimonPermissionModel}）。
 * 不做更细的查询下推——赋权数量级是「人 × 资源」，全量取回后过滤足够。
 */
public interface PermissionAssignmentRepository
        extends JpaRepository<PermissionAssignmentEntity, String> {

    List<PermissionAssignmentEntity> findByCatalogId(String catalogId);

    List<PermissionAssignmentEntity> findByCatalogIdAndPrincipalIn(String catalogId,
                                                                   Collection<String> principals);

    Optional<PermissionAssignmentEntity> findByCatalogIdAndResourceTypeAndLocatorHashAndAccessAndPrincipal(
            String catalogId, String resourceType, String locatorHash, String access,
            String principal);
}
