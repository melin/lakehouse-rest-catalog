package io.github.melin.lakehouse.rest.iceberg.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.preview.PartitionSlice;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DataFiles;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.FileFormat;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionData;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.DateTimeUtil;
import org.junit.jupiter.api.Test;

/**
 * 「把文件清单聚合成分区」这块的单元测试——**不建表、不读文件**。
 *
 * <p>它值得单独钉住，因为这里是 Iceberg 与 Paimon 唯一形状不同的地方：Paimon 有
 * {@code listPartitionEntries()}（一个分区一条记录），Iceberg 只有"每个 data file
 * 带一个分区值"。聚合错了不会报错，只会让界面上的分区列表少几天、或者把
 * {@code day(ts)} 的分区显示成 {@code ts=20424}。
 *
 * <p>用 {@link FileScanTask} 的假实现 + {@link DataFiles.Builder} 造输入：分区值、文件数、
 * 大小、记录数都是我自己给的，于是"求和 / 去重 / 未知值传播"这三条规则可以被精确断言。
 */
class IcebergPartitionsTests {

    private static final Schema SCHEMA = new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "dt", Types.StringType.get()),
            Types.NestedField.optional(3, "region", Types.StringType.get()),
            Types.NestedField.optional(4, "ts", Types.TimestampType.withoutZone()));

    @Test
    void identityPartitionAggregatesFilesBytesAndRecords() {
        PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("dt").build();
        List<FileScanTask> tasks = List.of(
                task(spec, "f1.parquet", 100, 10, "2026-10-21"),
                task(spec, "f2.parquet", 200, 5, "2026-10-21"),
                task(spec, "f3.parquet", 50, 2, "2026-10-20"));

        List<PartitionSlice> slices = aggregate(tasks, spec, SCHEMA, 1_700_000_000_000L);

        assertEquals(2, slices.size());
        PartitionSlice newest = slice(slices, "dt=2026-10-21");
        assertEquals(2, newest.files(), "两个文件落在同一个分区上，必须并成一行");
        assertEquals(300, newest.bytes());
        assertEquals(15, newest.records(), "记录数相加，不是取最大");
        assertEquals(1_700_000_000_000L, newest.lastWriteTime(),
                "Iceberg 没有逐文件写入时间，退化为快照时间（给前端排序用）");
        assertEquals(Map.of("dt", "2026-10-21"), newest.values());
        assertEquals(1, slice(slices, "dt=2026-10-20").files());
    }

    @Test
    void dayTransformShowsADateInsteadOfADayCount() {
        PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).day("ts").build();
        int days = DateTimeUtil.daysFromDate(LocalDate.of(2026, 10, 21));
        List<FileScanTask> tasks = List.of(task(spec, "f1.parquet", 100, 10, days));

        List<PartitionSlice> slices = aggregate(tasks, spec, SCHEMA, 42L);

        // 键与值都用**源列名** ts，而不是元数据里的分区字段名 ts_day：ts_day 不是表的一列，
        // 而分区选择要能与列对上（自动锁最新要看 ts 的类型）
        PartitionSlice slice = slice(slices, "ts=2026-10-21");
        assertEquals(LocalDate.of(2026, 10, 21), slice.values().get("ts"),
                "分区值是 int 天数，回给前端就是 20424 这种没人看得懂的数字");
    }

    @Test
    void noFilesMeansNoPartitions() {
        PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("dt").build();

        // 分区表但一个文件都没有（刚建、或者被全删了）：选择器是空的，而不是"一个空分区"。
        // 后者会让界面显示一行 files=0 的 dt=null，用户点进去发现什么都没有
        assertTrue(aggregate(List.of(), spec, SCHEMA, 0L).isEmpty());
    }

    @Test
    void multiFieldPartitionKeepsFieldOrderInTheKey() {
        PartitionSpec spec = PartitionSpec.builderFor(SCHEMA)
                .identity("dt").identity("region").build();
        List<FileScanTask> tasks = List.of(
                task(spec, "f1.parquet", 10, 1, "2026-10-21", "cn-east"),
                task(spec, "f2.parquet", 10, 1, "2026-10-21", "cn-south"));

        List<PartitionSlice> slices = aggregate(tasks, spec, SCHEMA, 7L);

        assertEquals(2, slices.size(), "两个 region 是两个分区，不能按 dt 合并");
        assertEquals("dt=2026-10-21/region=cn-east", slices.get(0).key(),
                "键的字段顺序必须与 spec 一致，否则同一個分区在不同请求里得到不同的键");
        assertEquals(List.of("dt", "region"), List.copyOf(slices.get(0).values().keySet()));
    }

    @Test
    void columnRemovedFromSchemaFallsBackToThePartitionFieldName() {
        PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("dt").build();
        List<FileScanTask> tasks = List.of(task(spec, "f1.parquet", 10, 1, "2026-10-21"));
        // 删列之后的历史分区仍然引用着那个 source-id：退回分区字段名（dt）而不是抛异常，
        // 于是它在选择器里仍然选得动，只是不参与"最新"的判断
        Schema evolved = new Schema(
                Types.NestedField.required(1, "id", Types.LongType.get()),
                Types.NestedField.optional(4, "ts", Types.TimestampType.withoutZone()));

        List<PartitionSlice> slices = aggregate(tasks, spec, evolved, 0L);

        assertEquals("dt=2026-10-21", slices.get(0).key());
        assertEquals(1, slices.get(0).files());
    }

    @Test
    void nullPartitionValueIsMarkedAsSuch() {
        PartitionSpec spec = PartitionSpec.builderFor(SCHEMA).identity("dt").build();
        List<FileScanTask> tasks = List.of(task(spec, "f1.parquet", 10, 1, (Object) null));

        PartitionSlice slice = aggregate(tasks, spec, SCHEMA, 0L).get(0);

        // 空值分区（Paimon 的默认分区、Iceberg 的 null 分区）必须能被单独选中：
        // 键里写成 <null> 而不是 Java 的 "null" 字面量，后者与字符串 "null" 无法区分
        assertEquals("dt=<null>", slice.key());
        assertNull(slice.values().get("dt"));
        assertTrue(slice.files() == 1);
    }

    // ------------------------------------------------------------------ 夹具

    private static List<PartitionSlice> aggregate(List<FileScanTask> tasks, PartitionSpec spec,
                                                  Schema schema, long writeTime) {
        List<IcebergPartitions.PlannedFile> planned = new java.util.ArrayList<>();
        for (FileScanTask task : tasks) {
            planned.add(IcebergPartitions.planned(task, schema));
        }
        return IcebergPartitions.aggregate(planned, schema, writeTime);
    }

    private static PartitionSlice slice(List<PartitionSlice> slices, String key) {
        for (PartitionSlice slice : slices) {
            if (slice.key().equals(key)) {
                return slice;
            }
        }
        throw new AssertionError("no partition " + key + " in " + slices.stream().map(PartitionSlice::key).toList());
    }

    /** 一个数据文件；{@code partitionValues} 的顺序与 spec 的字段顺序一致。 */
    private static FileScanTask task(PartitionSpec spec, String name, long size, long records,
                                     Object... partitionValues) {
        PartitionData partition = new PartitionData(spec.partitionType());
        for (int i = 0; i < partitionValues.length; i++) {
            partition.put(i, partitionValues[i]);
        }
        DataFile file = DataFiles.builder(spec)
                .withPath("s3://bucket/table/data/" + name)
                .withFormat(FileFormat.PARQUET)
                .withFileSizeInBytes(size)
                .withRecordCount(records)
                .withPartition(partition)
                .build();
        return new FakeTask(file, spec);
    }

    /**
     * 最小的 {@link FileScanTask}：聚合只用到 {@code file()} / {@code spec()} / {@code partition()}，
     * 其余方法在真实读取路径上才需要（那边由 {@code planFiles()} 产出真实任务）。
     */
    private record FakeTask(DataFile file, PartitionSpec spec) implements FileScanTask {

        @Override
        public List<DeleteFile> deletes() {
            return List.of();
        }

        @Override
        public long start() {
            return 0;
        }

        @Override
        public long length() {
            return file.fileSizeInBytes();
        }

        @Override
        public Expression residual() {
            return Expressions.alwaysTrue();
        }

        @Override
        public Iterable<FileScanTask> split(long splitSize) {
            return List.of(this);
        }
    }
}
