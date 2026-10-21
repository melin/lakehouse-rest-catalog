package io.github.melin.lakehouse.rest.iceberg.preview;

import io.github.melin.lakehouse.rest.preview.PreviewTable;
import io.github.melin.lakehouse.rest.preview.PreviewTableFactory;
import io.github.melin.lakehouse.rest.preview.PreviewTarget;
import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.ResourceType;
import lombok.RequiredArgsConstructor;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableMetadataParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按**库里记的元数据位置**打开一张 Iceberg 表——不经过任何 catalog 客户端。
 *
 * <p>与 Paimon 侧同一条思路：权威来源是数据库里那一列
 * {@code lakehouse_table.metadata_location}（每次 commit 由本服务端写回），
 * 而不是"在 warehouse 下按名字推一个路径"。后者对 {@code register} 进来的表、
 * 改过 location 的表都会推错，而推错的表现是"表存在但没有数据"。
 *
 * <p>元数据文本通过 {@link StorageFileAccess#read} 读，解析用 Iceberg 自己的
 * {@link TableMetadataParser#fromJson}——与 {@code IcebergTableService} 加载表时
 * 用的是同一对方法。这样"详情页看到的 schema / 快照"与"预览读到的 schema / 快照"
 * 不可能不一致：它们读的是同一个文件、同一个解析器。
 *
 * <p>数据文件的读取所需的 {@code FileIO} 由 {@link IcebergPreviewFileIo} 在表对象里
 * 接上——它同样复用 {@code StorageFileAccess}，于是凭据解析只有一份。
 */
@Component
@RequiredArgsConstructor
public class IcebergPreviewTableFactory implements PreviewTableFactory {

    private static final Logger log = LoggerFactory.getLogger(IcebergPreviewTableFactory.class);

    private final StorageFileAccess fileAccess;

    @Override
    public CatalogFormat format() {
        return CatalogFormat.ICEBERG;
    }

    @Override
    public PreviewTable open(PreviewTarget target, Long snapshotId) {
        String name = target.table().getName();
        String metadataLocation = target.table().getMetadataLocation();
        if (metadataLocation == null || metadataLocation.isBlank()) {
            // 建表只写库、还没落第一份元数据（或历史上用别的 catalog 建的表）：
            // 这是"这张表还不完整"，不是服务端故障，用 400 让用户去看文件列表
            throw ApiException.badRequest("Table '" + name + "' has no metadata location recorded,"
                    + " so there is nothing to read; the location is written when the table is"
                    + " created or registered");
        }
        String catalogId = target.catalog().getId();
        String document = fileAccess.read(catalogId, metadataLocation);
        TableMetadata metadata;
        try {
            metadata = TableMetadataParser.fromJson(metadataLocation, document);
        } catch (RuntimeException exception) {
            log.warn("stored Iceberg metadata of table {} at {} cannot be parsed: {}",
                    name, metadataLocation, exception.getMessage());
            throw new ApiException(500, ResourceType.TABLE, metadataLocation,
                    "stored Iceberg metadata of table '" + name + "' at " + metadataLocation
                            + " cannot be read back: " + exception.getMessage());
        }
        return new IcebergPreviewTable(fileAccess, catalogId, metadata, name, snapshotId);
    }
}
