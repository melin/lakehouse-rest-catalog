package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.BranchEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<BranchEntity, String> {

    Optional<BranchEntity> findByTableIdAndName(String tableId, String name);

    boolean existsByTableIdAndName(String tableId, String name);

    List<BranchEntity> findAllByTableIdOrderByNameAsc(String tableId);

    /** 删掉一张表的全部分支行。Iceberg 侧删表时用（分支是 {@code refs} 的派生投影）。 */
    long deleteByTableId(String tableId);
}
