package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.TagEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<TagEntity, String> {

    Optional<TagEntity> findByTableIdAndTagName(String tableId, String tagName);

    List<TagEntity> findAllByTableIdOrderByTagNameAsc(String tableId);

    /** 删掉一张表的全部标签行。Iceberg 侧删表时用（标签是 {@code refs} 的派生投影）。 */
    long deleteByTableId(String tableId);
}
