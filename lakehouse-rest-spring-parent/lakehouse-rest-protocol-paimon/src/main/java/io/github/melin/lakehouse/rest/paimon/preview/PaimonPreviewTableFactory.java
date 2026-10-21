package io.github.melin.lakehouse.rest.paimon.preview;

import io.github.melin.lakehouse.rest.preview.PreviewTable;
import io.github.melin.lakehouse.rest.preview.PreviewTableFactory;
import io.github.melin.lakehouse.rest.preview.PreviewTarget;
import io.github.melin.lakehouse.rest.service.StorageFileIoFactory;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.paimon.fs.FileIO;
import org.apache.paimon.fs.Path;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.FileStoreTableFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 按表的物理路径打开一张 Paimon 表——**不经过 catalog**。
 *
 * <p><b>为什么不走 {@code FileSystemCatalog#getTable}。</b>那要求"表在 warehouse 下的
 * 标准位置"，而本工程允许三件它表达不了的事：外部表（{@code external=true}，目录在别处）、
 * 多级 namespace、以及通过 {@code register} 登记进来的表（元数据里的 {@code path} 才是权威）。
 * {@link FileStoreTableFactory#create} 只吃"路径 + FileIO"，三种情况一视同仁。
 *
 * <p><b>FileIO 与凭据仍由 {@link StorageFileIoFactory} 装配。</b>与浏览仓库文件、
 * 物化元数据共用同一份：两份实现漂移的代价是"引擎能读、控制台读不了"，
 * 而两边的报错都只说认证失败，从现象反推不回原因。
 */
@Component
@RequiredArgsConstructor
public class PaimonPreviewTableFactory implements PreviewTableFactory {

    private static final Logger log = LoggerFactory.getLogger(PaimonPreviewTableFactory.class);

    private final StorageFileIoFactory fileIoFactory;

    @Override
    public CatalogFormat format() {
        return CatalogFormat.PAIMON;
    }

    @Override
    public PreviewTable open(PreviewTarget target, Long snapshotId) {
        String tableType = target.table().getTableType();
        if (!previewable(tableType)) {
            throw ApiException.badRequest("Table '" + target.table().getName() + "' is a "
                    + tableType + " table; data preview only covers snapshot-managed Paimon tables"
                    + " (the data files of format/object/lance tables are not parquet-family"
                    + " files managed by snapshots)");
        }
        String path = target.table().getPath();
        if (path == null || path.isBlank()) {
            throw ApiException.badRequest("Table '" + target.table().getName() + "' has no path"
                    + " recorded, so there is nothing to read; the path is written when the table"
                    + " is created");
        }
        Map<String, String> options =
                fileIoFactory.optionsFor(target.catalog(), target.table().getId(), path);
        FileIO fileIO;
        try {
            fileIO = fileIoFactory.open(new Path(path), options);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot access the storage of table '"
                    + target.table().getName() + "': " + exception.getMessage(), exception);
        }
        FileStoreTable table;
        try {
            table = FileStoreTableFactory.create(fileIO, new Path(path));
        } catch (RuntimeException exception) {
            // 最常见的是 schema 文件缺失（外部表还没物化）：这是"这张表还不完整"，
            // 不是服务端故障，用 400 让用户去看文件列表比 500 有用
            log.debug("cannot open paimon table {} at {}", target.table().getName(), path, exception);
            throw ApiException.badRequest("Cannot open table '" + target.table().getName()
                    + "' at " + path + ": " + exception.getMessage()
                    + " (the directory may be missing its schema/schema-<n> file; check the"
                    + " files tab)");
        }
        return new PaimonPreviewTable(table, snapshotId);
    }

    /**
     * 这张表能不能预览。
     *
     * <p>认两种写法：实体默认值 {@code PAIMON}（历史上就是大写存的），以及 schema 里
     * {@code options.type} 的原始取值 {@code table}（Paimon 自己的 {@code TableType} 字面量）。
     * 其余三种（{@code format-table} 等）不认——它们的数据文件不是快照托管的 parquet 族，
     * 界面也不给这个页签（设计文档 §11.1）。
     */
    private static boolean previewable(String tableType) {
        if (tableType == null || tableType.isBlank()) {
            return true;
        }
        String normalized = tableType.trim().toLowerCase(Locale.ROOT);
        return "paimon".equals(normalized) || "table".equals(normalized);
    }
}
