package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** principal role 仓储。 */
public interface PrincipalRoleRepository extends JpaRepository<PrincipalRoleEntity, String> {

    Optional<PrincipalRoleEntity> findByName(String name);

    boolean existsByName(String name);

    List<PrincipalRoleEntity> findAllByOrderByNameAsc();

    List<PrincipalRoleEntity> findAllByIdIn(Iterable<String> ids);

    /**
     * 按名字批量取角色。
     *
     * <p>组同步要么一次问「这些名字里哪些真的存在」，要么逐个查 N 次；
     * 一次问完的好处是同步过程里不会因为「查到一半角色被删」而给出半套结果。
     */
    List<PrincipalRoleEntity> findByNameIn(java.util.Collection<String> names);
}
