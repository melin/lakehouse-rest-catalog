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
}
