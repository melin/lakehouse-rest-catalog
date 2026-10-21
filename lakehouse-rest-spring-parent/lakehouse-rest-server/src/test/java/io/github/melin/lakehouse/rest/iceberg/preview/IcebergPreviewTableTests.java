package io.github.melin.lakehouse.rest.iceberg.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.preview.PreviewChunk;
import io.github.melin.lakehouse.rest.preview.PreviewColumn;
import io.github.melin.lakehouse.rest.preview.PreviewColumn.Kind;
import io.github.melin.lakehouse.rest.preview.PreviewTable;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.TableMetadata;
import org.apache.iceberg.types.Types;
import org.junit.jupiter.api.Test;

/**
 * Iceberg 预览表对象上**不需要数据文件**就能成立的那些契约。
 *
 * <p>能在这里钉住的是四件事：列怎么描述（类型文本 / 粗分类 / 是否分区列）、
 * 分区字段与变换怎么暴露、快照 id 怎么回答、空表读一页该是什么结果。它们都是
 * "把格式翻译成预览契约"的翻译层，也正是最容易把 {@code timestamptz} 当成
 * {@code timestamp}、或者把 {@code day(ts)} 暴露成 {@code ts_day} 的地方。
 *
 * <p>真实的数据读取（parquet / delete 文件 / 分区裁剪）需要真的写一个 Iceberg 表，
 * 那属于端到端验收（设计文档 §14 的 M2 条目），不在这里造假。
 */
class IcebergPreviewTableTests {

    private static final Schema SCHEMA = new Schema(
            Types.NestedField.required(1, "id", Types.LongType.get()),
            Types.NestedField.optional(2, "name", Types.StringType.get()),
            Types.NestedField.optional(3, "ts", Types.TimestampType.withZone()));

    @Test
    void columnsDescribeTypeKindAndThePartitionFlag() {
        IcebergPreviewTable table = table(PartitionSpec.builderFor(SCHEMA).day("ts").build());

        List<PreviewColumn> columns = table.columns();

        assertEquals(List.of("id", "name", "ts"),
                columns.stream().map(PreviewColumn::name).toList());
        PreviewColumn id = columns.get(0);
        assertEquals(Kind.NUMBER, id.kind());
        assertEquals("long NOT NULL", id.type(), "必填列要带 NOT NULL 后缀，否则界面看不出能不能为空");
        assertFalse(id.partition());
        assertEquals(Kind.TEXT, columns.get(1).kind());
        PreviewColumn ts = columns.get(2);
        assertEquals(Kind.TEMPORAL, ts.kind(),
                "timestamptz 与 timestamp 是同一个 TypeID，带不带时区都该是时间类型");
        assertTrue(ts.partition(), "day(ts) 的分区源列是 ts，它该被标成分区列");
        assertEquals("timestamptz", ts.type());
    }

    @Test
    void partitionFieldsUseTheSourceColumnNameNotTheSpecFieldName() {
        IcebergPreviewTable table = table(PartitionSpec.builderFor(SCHEMA).day("ts").build());

        // 元数据里这个分区字段叫 ts_day；对外必须是 ts + 变换名，否则前端没法把它当列看
        assertEquals(List.of("ts"), table.partitionFields());
        assertEquals(Map.of("ts", "day"), table.partitionTransforms());
    }

    @Test
    void unpartitionedTableExposesNothingToSelect() {
        IcebergPreviewTable table = table(PartitionSpec.unpartitioned());

        assertEquals(List.of(), table.partitionFields());
        assertEquals(Map.of(), table.partitionTransforms());
        assertEquals(List.of(), table.partitions());
        assertEquals(0, table.totalFiles());
        assertEquals(0, table.countFiles(List.of()));
    }

    @Test
    void aTableWithoutSnapshotsReportsNullSnapshotIds() {
        IcebergPreviewTable table = table(PartitionSpec.unpartitioned());

        assertNull(table.snapshotId(), "还没写过数据的表没有快照，如实回 null 而不是 0");
        assertNull(table.latestSnapshotId());
        assertEquals("Iceberg", table.formatLabel());
    }

    @Test
    void anExpiredOrWrongSnapshotIsA404() {
        ApiException exception = assertThrows(ApiException.class,
                () -> new IcebergPreviewTable(null, "cat", metadata(PartitionSpec.unpartitioned()),
                        "orders", 42L));

        assertEquals(404, exception.getStatus());
        // 消息是上游那句中性的 "The given snapshot does not exist"；是哪个快照在资源字段上
        assertEquals("42", exception.getResourceName());
    }

    @Test
    void readingAnEmptyTableIsExhaustedInsteadOfAnError() {
        IcebergPreviewTable table = table(PartitionSpec.unpartitioned());

        PreviewChunk chunk = table.read(new PreviewTable.ReadSpec(
                new int[] {0}, 1, List.of(), List.of(), 0, 0, 10, 8, 64L * 1024 * 1024, 0));

        assertTrue(chunk.exhausted(), "没有文件就是读完了，不该给「加载更多」");
        assertEquals(List.of(), chunk.rows());
        assertEquals(0, chunk.filesRead());
        assertEquals(0, chunk.nextSplitOffset(), "没有下一页时 nextSplitOffset 指向末尾（这里是 0）");
    }

    // ------------------------------------------------------------------ 夹具

    /**
     * 一张只有元数据、没有任何快照的表。
     *
     * <p>{@code fileAccess} 传 null：本文件里所有断言都不碰数据文件，而分区枚举在
     * 零个 manifest 时也不会去打开存储。真去读了会立刻 NPE——那正是我想要的信号
     * （说明"枚举分区只需要元数据"这条假设不成立）。
     */
    private static IcebergPreviewTable table(PartitionSpec spec) {
        return new IcebergPreviewTable(null, "cat", metadata(spec), "orders", null);
    }

    private static TableMetadata metadata(PartitionSpec spec) {
        return TableMetadata.newTableMetadata(SCHEMA, spec, "file:///tmp/warehouse/db/orders",
                Map.of("format-version", "2"));
    }
}
