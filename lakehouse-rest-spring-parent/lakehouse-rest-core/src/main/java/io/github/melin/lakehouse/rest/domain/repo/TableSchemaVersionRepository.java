package io.github.melin.lakehouse.rest.domain.repo;

import io.github.melin.lakehouse.rest.domain.entity.TableSchemaVersionEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableSchemaVersionRepository extends JpaRepository<TableSchemaVersionEntity, String> {

    Optional<TableSchemaVersionEntity> findByTableIdAndSchemaId(String tableId, long schemaId);

    List<TableSchemaVersionEntity> findAllByTableIdOrderBySchemaIdAsc(String tableId);

    /**
     * 删掉一张表的全部 schema 版本行。
     *
     * <p>Iceberg 侧删表时用：那里的 schema 行是 {@code metadata_doc} 中
     * {@code schemas[]} 的派生投影（见 {@code IcebergProjections}）。
     */
    long deleteByTableId(String tableId);
}
