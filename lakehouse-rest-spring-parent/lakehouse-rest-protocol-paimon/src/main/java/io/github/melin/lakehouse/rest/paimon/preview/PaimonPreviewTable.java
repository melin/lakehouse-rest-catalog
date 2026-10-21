package io.github.melin.lakehouse.rest.paimon.preview;

import io.github.melin.lakehouse.rest.preview.PartitionSlice;
import io.github.melin.lakehouse.rest.preview.PreviewChunk;
import io.github.melin.lakehouse.rest.preview.PreviewColumn;
import io.github.melin.lakehouse.rest.preview.PreviewFilters;
import io.github.melin.lakehouse.rest.preview.PreviewTable;
import io.github.melin.lakehouse.rest.preview.PreviewValues;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.Snapshot;
import org.apache.paimon.data.BinaryRow;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.manifest.PartitionEntry;
import org.apache.paimon.reader.RecordReader;
import org.apache.paimon.table.FileStoreTable;
import org.apache.paimon.table.source.DataSplit;
import org.apache.paimon.table.source.InnerTableScan;
import org.apache.paimon.table.source.ReadBuilder;
import org.apache.paimon.table.source.Split;
import org.apache.paimon.table.source.TableRead;
import org.apache.paimon.table.source.TableScan;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.RowType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Paimon 侧的数据预览：分区枚举 + 按格式自己的扫描器读行。
 *
 * <p><b>为什么读的入口必须是 {@code plan() + createReader()}</b>。Paimon 的
 * {@link DataSplit} 是 {@code dataFiles + deleteFiles} 的装配体：主键表删掉的行
 * 没有从数据文件里消失，而是由 delete 文件在读取时抵扣。绕过扫描器、自己按文件
 * 列表挑 parquet 去读，读到的就是"逻辑删除之前"的行——界面上看起来像数据脏了，
 * 用户会去排查一个不存在的故障。这条与设计文档 §3 否决候选 B/C 是同一条理由。
 *
 * <p><b>分区过滤交给 {@code withPartitionFilter(List<BinaryRow>)}</b>，而不是拿
 * 分区值拼 SQL 或字符串条件：枚举时拿到的 {@link PartitionEntry#partition()} 就是
 * Paimon 自己的 {@link BinaryRow}，原样回传既是精确匹配（不经过"值 → 字符串 → 值"
 * 的往返），也避免日期/时间/Decimal 的解析分歧。
 */
public class PaimonPreviewTable implements PreviewTable {

    private static final Logger log = LoggerFactory.getLogger(PaimonPreviewTable.class);

    /** 每读这么多行看一次截止时间；逐行取 {@code nanoTime} 在热点循环里并不便宜。 */
    private static final int DEADLINE_CHECK_EVERY = 512;

    /** 打开时的那份表（未做时间旅行），用来回答"现在最新快照是哪一个"。 */
    private final FileStoreTable base;

    private final FileStoreTable table;

    private final List<String> partitionKeys;

    private final List<PreviewColumn> columns;

    private final List<DataType> dataTypes;

    private final RowType partitionType;

    private final Long snapshotId;

    /** 分区键 → Paimon 的原生分区行，供 {@code withPartitionFilter} 使用。 */
    private final Map<String, BinaryRow> nativePartitions = new HashMap<>();

    private List<PartitionSlice> partitions;

    PaimonPreviewTable(FileStoreTable table, Long requestedSnapshot) {
        this.base = table;
        Snapshot latest = table.latestSnapshot().orElse(null);
        if (requestedSnapshot != null) {
            if (latest == null) {
                throw ApiException.snapshotNotExist(requestedSnapshot);
            }
            Snapshot snapshot = snapshotOf(table, requestedSnapshot);
            if (snapshot == null) {
                throw ApiException.snapshotNotExist(requestedSnapshot);
            }
            // 时间旅行走 Paimon 自己的扫描选项，而不是"自己找当时的 manifest 文件"：
            // 快照里带着 schema-id，扫描器会据此挑对应版本的 schema，手拼会漏掉这一层
            this.table = table.copy(Map.of(
                    CoreOptions.SCAN_SNAPSHOT_ID.key(), String.valueOf(requestedSnapshot)));
            this.snapshotId = requestedSnapshot;
        } else {
            this.table = table;
            this.snapshotId = latest == null ? null : latest.id();
        }

        RowType rowType = this.table.rowType();
        this.partitionKeys = List.copyOf(this.table.partitionKeys());
        this.partitionType = partitionKeys.isEmpty() ? null : rowType.project(partitionKeys);
        Set<String> primaryKeys = new HashSet<>(this.table.primaryKeys());
        List<PreviewColumn> built = new ArrayList<>(rowType.getFieldCount());
        List<DataType> types = new ArrayList<>(rowType.getFieldCount());
        for (DataField field : rowType.getFields()) {
            DataType type = field.type();
            types.add(type);
            built.add(new PreviewColumn(
                    field.name(),
                    type.asSQLString() + (type.isNullable() ? "" : " NOT NULL"),
                    kindOf(type),
                    partitionKeys.contains(field.name()),
                    primaryKeys.contains(field.name())));
        }
        this.columns = List.copyOf(built);
        this.dataTypes = List.copyOf(types);
    }

    private static Snapshot snapshotOf(FileStoreTable table, long snapshotId) {
        try {
            return table.snapshot(snapshotId);
        } catch (RuntimeException exception) {
            // 快照被清理或从来没有过：对调用方是同一个答案（404），不需要区分
            log.debug("snapshot {} is not available on table {}: {}",
                    snapshotId, table.name(), exception.getMessage());
            return null;
        }
    }

    @Override
    public String formatLabel() {
        return "Paimon";
    }

    @Override
    public Long latestSnapshotId() {
        return base.latestSnapshot().map(Snapshot::id).orElse(null);
    }

    @Override
    public List<PreviewColumn> columns() {
        return columns;
    }

    @Override
    public List<String> partitionFields() {
        return partitionKeys;
    }

    @Override
    public Long snapshotId() {
        return snapshotId;
    }

    @Override
    public List<PartitionSlice> partitions() {
        if (partitionKeys.isEmpty()) {
            return List.of();
        }
        if (partitions == null) {
            partitions = loadPartitions();
        }
        return partitions;
    }

    private List<PartitionSlice> loadPartitions() {
        List<PartitionEntry> entries = table.newReadBuilder().newScan().listPartitionEntries();
        List<PartitionSlice> slices = new ArrayList<>(entries.size());
        for (PartitionEntry entry : entries) {
            BinaryRow row = entry.partition();
            Map<String, Object> values = new LinkedHashMap<>();
            for (int i = 0; i < partitionType.getFieldCount(); i++) {
                values.put(partitionType.getFieldNames().get(i),
                        RowValues.value(row, i, partitionType.getTypeAt(i)));
            }
            String key = PartitionSlice.key(partitionKeys, values);
            nativePartitions.put(key, row);
            slices.add(new PartitionSlice(key, values, entry.fileCount(), entry.fileSizeInBytes(),
                    entry.recordCount(), entry.lastFileCreationTime()));
        }
        return List.copyOf(slices);
    }

    @Override
    public int totalFiles() {
        if (!partitionKeys.isEmpty()) {
            return (int) partitions().stream().mapToLong(PartitionSlice::files).sum();
        }
        return table.newReadBuilder().newScan().plan().splits().size();
    }

    @Override
    public int countFiles(List<PartitionSlice> selection) {
        if (selection == null || selection.isEmpty()) {
            return totalFiles();
        }
        return (int) selection.stream().mapToLong(PartitionSlice::files).sum();
    }

    @Override
    public PreviewChunk read(ReadSpec spec) {
        ReadBuilder builder = table.newReadBuilder();
        if (spec.projection().length > 0) {
            builder = builder.withProjection(spec.projection());
        }
        TableScan scan = builder.newScan();
        List<BinaryRow> selected = nativeRows(spec.partitions());
        if (!selected.isEmpty()) {
            scan = ((InnerTableScan) scan).withPartitionFilter(selected);
        }
        List<Split> splits = scan.plan().splits();
        if (spec.splitOffset() >= splits.size()) {
            return new PreviewChunk(List.of(), 0, 0, 0, true, splits.size(), 0);
        }

        TableRead read = builder.newRead();
        List<DataType> projectedTypes = projectedTypes(spec.projection());
        List<List<Object>> rows = new ArrayList<>(Math.min(spec.limit(), 256));
        long rowsRead = 0;
        long bytesRead = 0;
        int filesRead = 0;
        boolean exhausted = true;
        long skip = spec.skipRows();
        int nextSplit = splits.size();
        long nextSplitRows = 0;

        for (int index = spec.splitOffset(); index < splits.size(); index++) {
            if (filesRead >= spec.maxFiles() || bytesRead >= spec.maxBytes()) {
                // 还有 split 没打开：这不是"读完了"，只是本次预算用完
                exhausted = false;
                nextSplit = index;
                nextSplitRows = 0;
                break;
            }
            Split split = splits.get(index);
            boolean limitReached = false;
            long consumedInSplit = 0;
            try (RecordReader<InternalRow> reader = read.createReader(split)) {
                RecordReader.RecordIterator<InternalRow> batch;
                while (!limitReached && (batch = reader.readBatch()) != null) {
                    InternalRow row;
                    while ((row = batch.next()) != null) {
                        rowsRead++;
                        consumedInSplit++;
                        if (skip > 0) {
                            skip--;
                            continue;
                        }
                        List<Object> values = RowValues.toJava(row, projectedTypes, spec.outputCount());
                        // 判定用完整值，结果用截短的那一份：contains 命中的位置可能在
                        // 512 个字符之后，先截再判会把该命中的行悄悄判成不命中
                        if (PreviewFilters.testAll(values, spec.filters())) {
                            rows.add(PreviewValues.truncate(values));
                        }
                        if (rowsRead % DEADLINE_CHECK_EVERY == 0) {
                            checkDeadline(spec.deadlineNanos(), rowsRead);
                        }
                        if (rows.size() >= spec.limit()) {
                            limitReached = true;
                            break;
                        }
                    }
                    batch.releaseBatch();
                }
            } catch (ApiException exception) {
                throw exception;
            } catch (Exception exception) {
                throw new IllegalStateException("failed to read split " + index + " of table "
                        + table.name() + ": " + exception.getMessage(), exception);
            }
            filesRead++;
            bytesRead += splitSize(split);
            if (limitReached) {
                exhausted = index == splits.size() - 1;
                nextSplit = index;
                nextSplitRows = consumedInSplit;
                break;
            }
        }
        return new PreviewChunk(rows, rowsRead, bytesRead, filesRead, exhausted,
                nextSplit, nextSplitRows);
    }

    private void checkDeadline(long deadlineNanos, long rowsRead) {
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
            throw new ApiException(504, null, null,
                    "preview timed out after reading " + rowsRead + " rows; narrow the partition"
                            + " or add a filter");
        }
    }

    private List<BinaryRow> nativeRows(List<PartitionSlice> selection) {
        if (selection == null || selection.isEmpty()) {
            return List.of();
        }
        List<BinaryRow> rows = new ArrayList<>(selection.size());
        for (PartitionSlice slice : selection) {
            BinaryRow row = nativePartitions.get(slice.key());
            if (row == null) {
                // 分区列表在本次请求内是稳定的；查不到说明选择不是从 partitions() 来的
                throw new IllegalStateException("unknown partition " + slice.key()
                        + "; partitions must come from this preview's partition enumeration");
            }
            rows.add(row);
        }
        return rows;
    }

    private List<DataType> projectedTypes(int[] projection) {
        if (projection.length == 0) {
            return dataTypes;
        }
        List<DataType> types = new ArrayList<>(projection.length);
        for (int index : projection) {
            types.add(dataTypes.get(index));
        }
        return types;
    }

    private static long splitSize(Split split) {
        if (split instanceof DataSplit dataSplit) {
            return dataSplit.dataFiles().stream().mapToLong(file -> file.fileSize()).sum();
        }
        return 0;
    }

    private static PreviewColumn.Kind kindOf(DataType type) {
        return switch (type.getTypeRoot()) {
            case TINYINT, SMALLINT, INTEGER, BIGINT, FLOAT, DOUBLE, DECIMAL -> PreviewColumn.Kind.NUMBER;
            case CHAR, VARCHAR -> PreviewColumn.Kind.TEXT;
            case DATE, TIME_WITHOUT_TIME_ZONE, TIMESTAMP_WITHOUT_TIME_ZONE,
                 TIMESTAMP_WITH_LOCAL_TIME_ZONE -> PreviewColumn.Kind.TEMPORAL;
            case BOOLEAN -> PreviewColumn.Kind.BOOLEAN;
            case BINARY, VARBINARY, BLOB -> PreviewColumn.Kind.BINARY;
            case ARRAY, MAP, ROW, MULTISET, VARIANT, VECTOR -> PreviewColumn.Kind.COMPLEX;
        };
    }
}
