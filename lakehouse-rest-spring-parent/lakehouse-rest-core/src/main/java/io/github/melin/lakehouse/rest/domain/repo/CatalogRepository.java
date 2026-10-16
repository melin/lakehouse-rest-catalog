package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CatalogRepository extends JpaRepository<CatalogEntity, String> {

    Optional<CatalogEntity> findByPrefix(String prefix);

    List<CatalogEntity> findAllByOrderByPrefixAsc();
}
