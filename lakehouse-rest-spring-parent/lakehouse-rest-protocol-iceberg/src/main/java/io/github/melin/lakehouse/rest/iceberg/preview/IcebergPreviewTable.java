package io.github.melin.lakehouse.rest.iceberg.preview;

import io.github.melin.lakehouse.rest.preview.PartitionSlice;
import io.github.melin.lakehouse.rest.preview.PreviewChunk;
import io.github.melin.lakehouse.rest.preview.PreviewColumn;
import io.github.melin.lakehouse.rest.preview.PreviewFilters;
import io.github.melin.lakehouse.rest.preview.PreviewTable;
import io.github.melin.lakehouse.rest.preview.PreviewValues;
import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.iceberg.BaseTable;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.StaticTableOperations;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.TableScan;
import org.apache.iceberg.data.DeleteFilter;
import org.apache.iceberg.data.GenericDeleteFilter;
import org.apache.iceberg.data.IdentityPartitionConverters;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.formats.FormatModelRegistry;
import org.apache.iceberg.formats.ReadBuilder;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.PartitionUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Iceberg 侧的数据预览：分区枚举 + **按 data file 逐个读**。
 *
 * <p><b>为什么不用 {@code IcebergGenerics.read(table)}。</b>它把整张表当成一个
 * 迭代器交出来，看起来最省事，但它回答不了这个端点必须回答的三个问题：
 * 打开了几个文件（{@code filesRead}）、预算用完停在哪（{@code maxFiles} /
 * {@code maxBytes}）、下一页从哪接着读（游标的 {@code splitOffset}）。
 * 一个"读到哪算哪"的迭代器里没有文件边界，于是「只读了 3/12 个文件」这句话
 * 只能靠猜——而设计文档 §10 的全部内容就是"不许猜"。
 *
 * <p>因此这里按 {@code FileScanTask}（= 一个 data file + 它的 delete 文件）
 * 自己读，逐句复用 {@code iceberg-data} 的两个 public 组件：
 * {@link GenericDeleteFilter}（position / equality delete 的抵扣）与
 * {@link FormatModelRegistry}（parquet 读取 + 分区常量注入）。这正是上游
 * {@code GenericReader#open(FileScanTask)} 的做法——那个类不是 public，
 * 所以照抄它的四条语句而不是绕过它。
 *
 * <p><b>为什么不能自己列文件读 parquet。</b>Iceberg v2 的删除是 delete 文件：
 * 被删的行仍然在数据文件里，由 {@link FileScanTask#deletes()} 在读取时抵扣。
 * 自己按清单挑数据文件去读，读到的就是"删除之前"的行——预览显示幽灵行比不显示
 * 更糟（用户会拿它当"数据脏了"的证据）。这与 Paimon 主键表要走
 * {@code createReader(split)} 是同一条理由，也是设计文档 §3 否决候选 B/C 的那条。
 *
 * <p><b>分区选择在任务层完成，不用 {@code Expression}。</b>{@code planFiles()} 已经
 * 把每个文件的分区值算出来了，按它过滤是精确匹配；换成
 * {@code Expressions.transform(col, transform) = value} 反而要自己把显示值
 * 逆变换回清单内部表示（日期 → 天数、时间 → 微秒），多一层可能出错的映射。
 * 行级条件（{@code filters}）仍由 {@link PreviewFilters} 判——格式无关，两个面共用。
 */
public class IcebergPreviewTable implements PreviewTable {

    private static final Logger log = LoggerFactory.getLogger(IcebergPreviewTable.class);

    /** 每读这么多行看一次截止时间；逐行取 {@code nanoTime} 在热点循环里并不便宜。 */
    private static final int DEADLINE_CHECK_EVERY = 512;

    private final StorageFileAccess fileAccess;

    private final String catalogId;

    private final Table table;

    private final TableScan scan;

    private final Schema schema;

    private final List<PreviewColumn> columns;

    /** 分区字段（源列名，按 spec 顺序）→ 变换名（{@code identity} / {@code day} …）。 */
    private final Map<String, String> transforms;

    private final Long snapshotId;

    private final Long latestSnapshotId;

    /** 分区"最近写入时间"的替代值：所读快照的 {@code timestamp-ms}；空表为 0。 */
    private final long writeTime;

    /** 一次规划的产物，实例内复用：分区列表与读取用的是同一份清单。 */
    private List<IcebergPartitions.PlannedFile> planned;

    private List<PartitionSlice> partitions;

    /**
     * @param metadata          已解析的表元数据（调用方负责读文件并解析）
     * @param name              表名，只用于错误信息
     * @param requestedSnapshot 要读的快照；{@code null} 表示最新
     */
    IcebergPreviewTable(StorageFileAccess fileAccess, String catalogId, TableMetadata metadata,
                        String name, Long requestedSnapshot) {
        this.fileAccess = fileAccess;
        this.catalogId = catalogId;
        this.schema = metadata.schema();

        IcebergPreviewFileIo io = new IcebergPreviewFileIo(fileAccess, catalogId);
        this.table = new BaseTable(new StaticTableOperations(metadata, io), name);

        Snapshot current = metadata.currentSnapshot();
        this.latestSnapshotId = current == null ? null : current.snapshotId();
        Snapshot readSnapshot = current;
        if (requestedSnapshot != null) {
            // 快照被 expire 掉之后 id 还在游标里：这是"你要看的那一版已经没了"，
            // 不是服务端故障（与 Paimon 侧同一个 404）
            readSnapshot = metadata.snapshot(requestedSnapshot);
            if (readSnapshot == null) {
                throw ApiException.snapshotNotExist(requestedSnapshot);
            }
        }
        this.snapshotId = requestedSnapshot == null ? latestSnapshotId : requestedSnapshot;
        // 时间旅行走 Iceberg 自己的 scan 选项，而不是"拿历史 manifest 自己拼队列"：
        // 快照里带着 schema-id 与 spec-id，扫描器据此挑对应版本的 schema 与分区布局
        this.scan = requestedSnapshot == null || requestedSnapshot.equals(latestSnapshotId)
                ? table.newScan()
                : table.newScan().useSnapshot(requestedSnapshot);
        this.writeTime = readSnapshot == null ? 0 : readSnapshot.timestampMillis();

        Map<String, String> transforms = new LinkedHashMap<>();
        PartitionSpec spec = metadata.spec();
        for (PartitionField field : spec.fields()) {
            transforms.putIfAbsent(columnName(field), field.transform().toString());
        }
        this.transforms = Collections.unmodifiableMap(transforms);

        Set<Integer> identifiers = schema.identifierFieldIds();
        Set<String> partitionNames = new HashSet<>(transforms.keySet());
        List<PreviewColumn> built = new ArrayList<>(schema.columns().size());
        for (Types.NestedField field : schema.columns()) {
            Type type = field.type();
            built.add(new PreviewColumn(
                    field.name(),
                    type + (field.isOptional() ? "" : " NOT NULL"),
                    kindOf(type),
                    partitionNames.contains(field.name()),
                    identifiers.contains(field.fieldId())));
        }
        this.columns = List.copyOf(built);
    }

    @Override
    public String formatLabel() {
        return "Iceberg";
    }

    @Override
    public Long snapshotId() {
        return snapshotId;
    }

    @Override
    public Long latestSnapshotId() {
        return latestSnapshotId;
    }

    @Override
    public List<PreviewColumn> columns() {
        return columns;
    }

    @Override
    public List<String> partitionFields() {
        return List.copyOf(transforms.keySet());
    }

    @Override
    public Map<String, String> partitionTransforms() {
        return transforms;
    }

    @Override
    public List<PartitionSlice> partitions() {
        if (transforms.isEmpty()) {
            return List.of();
        }
        if (partitions == null) {
            partitions = IcebergPartitions.aggregate(plan(), schema, writeTime);
        }
        return partitions;
    }

    @Override
    public int totalFiles() {
        return plan().size();
    }

    @Override
    public int countFiles(List<PartitionSlice> selection) {
        if (selection == null || selection.isEmpty()) {
            return totalFiles();
        }
        Set<String> keys = new HashSet<>(selection.size());
        for (PartitionSlice slice : selection) {
            keys.add(slice.key());
        }
        int files = 0;
        for (IcebergPartitions.PlannedFile file : plan()) {
            if (keys.contains(file.key())) {
                files++;
            }
        }
        return files;
    }

    @Override
    public PreviewChunk read(ReadSpec spec) {
        List<IcebergPartitions.PlannedFile> files = scope(spec.partitions());
        if (spec.splitOffset() >= files.size()) {
            return new PreviewChunk(List.of(), 0, 0, 0, true, files.size(), 0);
        }
        Schema expected = projected(spec.projection());
        List<Types.NestedField> expectedFields = expected.columns();
        List<List<Object>> rows = new ArrayList<>(Math.min(spec.limit(), 256));
        long rowsRead = 0;
        long bytesRead = 0;
        int filesRead = 0;
        boolean exhausted = true;
        long skip = spec.skipRows();
        int nextSplit = files.size();
        long nextSplitRows = 0;

        for (int index = spec.splitOffset(); index < files.size(); index++) {
            if (filesRead >= spec.maxFiles() || bytesRead >= spec.maxBytes()) {
                // 还有文件没打开：这不是"读完了"，只是本次预算用完
                exhausted = false;
                nextSplit = index;
                nextSplitRows = 0;
                break;
            }
            FileScanTask task = files.get(index).task();
            boolean limitReached = false;
            long consumedInSplit = 0;
            try (CloseableIterable<Record> records = records(task, expected)) {
                Iterator<Record> iterator = records.iterator();
                while (iterator.hasNext()) {
                    Record record = iterator.next();
                    rowsRead++;
                    consumedInSplit++;
                    if (skip > 0) {
                        skip--;
                        continue;
                    }
                    List<Object> values =
                            IcebergRowValues.toJava(record, expectedFields, spec.outputCount());
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
            } catch (IOException exception) {
                throw new UncheckedIOException("failed to read " + task.file().path()
                        + " of table " + table.name() + ": " + exception.getMessage(), exception);
            }
            filesRead++;
            bytesRead += task.file().fileSizeInBytes();
            if (limitReached) {
                exhausted = index == files.size() - 1;
                nextSplit = index;
                nextSplitRows = consumedInSplit;
                break;
            }
        }
        return new PreviewChunk(rows, rowsRead, bytesRead, filesRead, exhausted,
                nextSplit, nextSplitRows);
    }

    /**
     * 读一个 data file 的全部行——{@code GenericReader#open(FileScanTask)} 的四条语句。
     *
     * <p>顺序有意义：
     * <ol>
     *   <li>{@link GenericDeleteFilter} 先算 {@code requiredSchema}：有 position delete 时
     *       会多读一个行号列，有 equality delete 时会多读被删条件的列——**用它给的表，
     *       不要用自己传进去的那份**，否则删除抵扣会按错位置读行号。</li>
     *   <li>{@code idToConstant} 注入 identity 分区值：隐藏分区（{@code days(ts)}、
     *       {@code bucket(id)}）的列**不在数据文件里**，不注入就取到 null。</li>
     *   <li>{@code deletes.filter(...)} 真正抵扣删除行。</li>
     * </ol>
     *
     * <p>不调 {@code .filter(task.residual())}：本类没有给 scan 设过滤条件（分区选择在
     * 任务层完成），residual 恒为 {@code alwaysTrue}；行级条件由 {@link PreviewFilters}
     * 在拿到 Java 值之后判——那一层与格式无关，两个面共用一套语义。
     */
    private CloseableIterable<Record> records(FileScanTask task, Schema expected) {
        IcebergPreviewFileIo io = new IcebergPreviewFileIo(fileAccess, catalogId);
        DeleteFilter<Record> deletes = new GenericDeleteFilter(io, task, schema, expected);
        Schema readSchema = deletes.requiredSchema();
        Map<Integer, ?> constants =
                PartitionUtil.constantsMap(task, IdentityPartitionConverters::convertConstant);
        CloseableIterable<Record> records;
        try {
            ReadBuilder<Record, ?> builder = FormatModelRegistry.readBuilder(
                    task.file().format(), Record.class, io.newInputFile(task.file()));
            records = builder
                    .project(readSchema)
                    .idToConstant(constants)
                    .split(task.start(), task.length())
                    .caseSensitive(true)
                    .build();
        } catch (RuntimeException exception) {
            // 没有对应的格式模型（例如表的数据文件是 avro/orc 而 ICEBERG 侧只带了
            // parquet）：这是"这张表的格式不支持预览"，400 比 500 有用
            log.debug("cannot read {} of table {}: {}", task.file().path(), table.name(),
                    exception.getMessage());
            throw ApiException.badRequest("Cannot read " + task.file().path() + " of table '"
                    + table.name() + "': " + exception.getMessage()
                    + " (data preview reads parquet files through iceberg-data; a table written"
                    + " in another file format is not covered)");
        }
        return deletes.filter(records);
    }

    private void checkDeadline(long deadlineNanos, long rowsRead) {
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
            throw new ApiException(504, null, null,
                    "preview timed out after reading " + rowsRead + " rows; narrow the partition"
                            + " or add a filter");
        }
    }

    /** 本次要读的文件：选了分区就按分区键过滤，没选就是全部。 */
    private List<IcebergPartitions.PlannedFile> scope(List<PartitionSlice> selection) {
        if (selection == null || selection.isEmpty()) {
            return plan();
        }
        Set<String> keys = new HashSet<>(selection.size());
        for (PartitionSlice slice : selection) {
            keys.add(slice.key());
        }
        List<IcebergPartitions.PlannedFile> scoped = new ArrayList<>();
        for (IcebergPartitions.PlannedFile file : plan()) {
            if (keys.contains(file.key())) {
                scoped.add(file);
            }
        }
        return scoped;
    }

    /** 规划一次并缓存。对象存储上每个文件一次 RTT，重复规划是纯浪费。 */
    private List<IcebergPartitions.PlannedFile> plan() {
        if (planned != null) {
            return planned;
        }
        List<IcebergPartitions.PlannedFile> files = new ArrayList<>();
        try (CloseableIterable<FileScanTask> tasks = scan.planFiles()) {
            for (FileScanTask task : tasks) {
                files.add(IcebergPartitions.planned(task, schema));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("failed to plan the files of table " + table.name()
                    + ": " + exception.getMessage(), exception);
        }
        planned = List.copyOf(files);
        return planned;
    }

    /**
     * 本次要读的列。
     *
     * <p>用 {@code Schema} 而不是列名列表：{@code project} 之后 {@code Record} 的
     * 字段顺序**就是这个 schema 的顺序**，而行值的转换是按位置取的。用名字列表的话，
     * 顺序由上游决定（{@code Schema#select} 会按表顺序重排），于是"勾选顺序"
     * 与"返回顺序"会静默不一致。
     */
    private Schema projected(int[] projection) {
        if (projection.length == 0) {
            return schema;
        }
        List<Types.NestedField> all = schema.columns();
        List<Types.NestedField> picked = new ArrayList<>(projection.length);
        for (int index : projection) {
            picked.add(all.get(index));
        }
        return new Schema(picked);
    }

    /** 分区字段的源列名；列已从 schema 删掉时退回分区字段名（历史分区仍然引用它）。 */
    private String columnName(PartitionField field) {
        try {
            String name = schema.findColumnName(field.sourceId());
            return name == null ? field.name() : name;
        } catch (RuntimeException exception) {
            return field.name();
        }
    }

    private static PreviewColumn.Kind kindOf(Type type) {
        return switch (type.typeId()) {
            case BOOLEAN -> PreviewColumn.Kind.BOOLEAN;
            case INTEGER, LONG, FLOAT, DOUBLE, DECIMAL -> PreviewColumn.Kind.NUMBER;
            // 带不带时区都是 TypeID.TIMESTAMP（看 shouldAdjustToUTC），这里不区分
            case DATE, TIME, TIMESTAMP, TIMESTAMP_NANO -> PreviewColumn.Kind.TEMPORAL;
            case STRING, UUID -> PreviewColumn.Kind.TEXT;
            case FIXED, BINARY -> PreviewColumn.Kind.BINARY;
            // STRUCT / LIST / MAP / VARIANT / GEOMETRY / GEOGRAPHY / UNKNOWN
            default -> PreviewColumn.Kind.COMPLEX;
        };
    }
}
