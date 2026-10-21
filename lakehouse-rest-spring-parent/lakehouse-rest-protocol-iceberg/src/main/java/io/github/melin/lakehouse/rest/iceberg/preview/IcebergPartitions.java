package io.github.melin.lakehouse.rest.iceberg.preview;

import io.github.melin.lakehouse.rest.preview.PartitionSlice;
import io.github.melin.lakehouse.rest.preview.PreviewValues;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.FileScanTask;
import org.apache.iceberg.PartitionField;
import org.apache.iceberg.PartitionSpec;
import org.apache.iceberg.Schema;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.types.Type;
import org.apache.iceberg.types.Types;
import org.apache.iceberg.util.DateTimeUtil;

/**
 * 把 {@code planFiles()} 的结果聚合成"一个分区一行"。
 *
 * <p><b>为什么分区清单必须从 {@code planFiles()} 来。</b>Iceberg 没有"列出分区"的
 * API：分区是 manifest 里每个 data file 上的一个 {@link StructLike}，只能顺着
 * 清单枚举文件、顺便把分区值聚合起来（{@code PartitionSpec#partitionType()} 给出
 * 每个位置的类型）。Paimon 那边有 {@code listPartitionEntries()}，Iceberg 没有，
 * 这是两边唯一的形状差异——对外都收敛成 {@link PartitionSlice}。
 *
 * <p><b>为什么键是"源列名"而不是 Iceberg 的分区字段名。</b>{@code day(ts)} 的分区字段
 * 在元数据里叫 {@code ts_day}，它**不是表的一列**。而预览里的分区选择要能对上
 * 列（用户按 {@code ts} 过滤，自动锁最新分区也要看 {@code ts} 的类型能不能排序），
 * 所以对外一律用 {@code PartitionField#sourceId()} 翻回源列名，把变换名单独放进
 * {@code PreviewTable#partitionTransforms()}。于是 {@code day(ts)} 表现为
 * "分区字段 {@code ts}，变换 {@code day}"——与用户的心里模型一致。
 *
 * <p><b>值为什么换成 Java 时间类型。</b>{@code day(ts)} 的分区值在清单里是
 * "1970 起的第几天"（int）。原样回给前端就是 {@code ts=20424}，没人看得懂；
 * 而"能不能排序"又必须按类型判断（{@link io.github.melin.lakehouse.rest.service.PartitionPlanner}
 * 用 typed comparator）。因此这里统一换成 {@link LocalDate} / {@link LocalDateTime}
 * 这类值：既好显示，又能直接比较。
 *
 * <p><b>例外：{@code year()} / {@code month()} 保留整数。</b>它们的分区类型就是
 * {@code int}（自 1970 起的年/月数），换成日期要发明"这个月的第一天"这种语义
 * （而空月份并不存在）。既然变换名会显示在界面上（{@code year(ts)}），
 * {@code year=56} 至少是可解释的，且仍能正确排序。
 */
final class IcebergPartitions {

    /** 一次规划出来的一个数据文件，以及它落在哪个分区上（键由 {@link #key} 拼）。 */
    record PlannedFile(FileScanTask task, String key) {
    }

    private IcebergPartitions() {
    }

    /**
     * 一个文件所属分区的键。
     *
     * <p>按**任务自己的 spec** 取值，而不是表的当前 spec：分区演化（spec evolution）之后
     * 老文件仍然按老 spec 布局，拿新 spec 去读会错位。代价是新旧 spec 会产出不同的键
     * （它们是不同的分区），这正是诚实的结果——两批文件本来就不在同一个分区里。
     */
    static PlannedFile planned(FileScanTask task, Schema schema) {
        Map<String, Object> values = values(task, schema);
        return new PlannedFile(task, PartitionSlice.key(List.copyOf(values.keySet()), values));
    }

    /** 该文件的分区取值：源列名 → 可显示、可比大小的 Java 值。 */
    static Map<String, Object> values(FileScanTask task, Schema schema) {
        PartitionSpec spec = task.spec();
        List<PartitionField> fields = spec.fields();
        Types.StructType partitionType = spec.partitionType();
        StructLike partition = task.partition();
        Map<String, Object> values = new LinkedHashMap<>();
        for (int position = 0; position < fields.size(); position++) {
            PartitionField field = fields.get(position);
            String name = columnName(schema, field);
            Type type = partitionType.fields().get(position).type();
            // 取 Object 而不是具体类型：清单里的实际类型与"分区类型"在少数变换上
            // 并不完全一致（例如老版本写的 hour 分区存的是 long），按类型强转会在
            // 这些表上抛 ClassCastException，而它们读出来的值本身是可用的
            values.put(name, display(type, partition.get(position, Object.class)));
        }
        return values;
    }

    /**
     * 源列名；列已经从 schema 里删掉时退回分区字段名。
     *
     * <p>退回而不是报错：删列是合法操作，历史分区的 manifest 仍然引用着那个 source-id。
     * 此时界面会显示一个"不在列清单里"的分区字段——它在选择器里选得动、排不了序
     * （planner 找不到列类型就不参与自动锁定），但不会被静默丢掉。
     */
    private static String columnName(Schema schema, PartitionField field) {
        try {
            String name = schema.findColumnName(field.sourceId());
            return name == null ? field.name() : name;
        } catch (RuntimeException exception) {
            return field.name();
        }
    }

    /**
     * 把清单里的分区值换成可显示的值。
     *
     * <p>日期 / 时间是"自 1970 起的计数"，不换就是天文数字；字符串可能是
     * {@code Utf8}（{@code CharSequence}），不换成 {@code String} 会让 Jackson
     * 序列化出一个 {@code {"bytes":...}} 之类的内部结构。
     */
    static Object display(Type type, Object raw) {
        if (raw == null) {
            return null;
        }
        return switch (type.typeId()) {
            case DATE -> DateTimeUtil.dateFromDays(number(raw).intValue());
            case TIME -> DateTimeUtil.timeFromMicros(number(raw).longValue());
            case TIMESTAMP -> timestamp(type, number(raw).longValue(), false);
            case TIMESTAMP_NANO -> timestamp(type, number(raw).longValue(), true);
            case STRING, UUID -> raw.toString();
            case FIXED, BINARY -> PreviewValues.binary(((ByteBuffer) raw).remaining());
            // INTEGER / LONG / FLOAT / DOUBLE / BOOLEAN / DECIMAL：本身就是可比较可显示的值
            default -> raw;
        };
    }

    /** 带时区语义的时间换 {@link OffsetDateTime}，不带时区的换 {@link LocalDateTime}。 */
    private static Object timestamp(Type type, long value, boolean nanos) {
        boolean withZone = type instanceof Types.TimestampType timestamp
                ? timestamp.shouldAdjustToUTC()
                : type instanceof Types.TimestampNanoType nano && nano.shouldAdjustToUTC();
        if (nanos) {
            return withZone
                    ? DateTimeUtil.timestamptzFromNanos(value)
                    : DateTimeUtil.timestampFromNanos(value);
        }
        return withZone
                ? DateTimeUtil.timestamptzFromMicros(value)
                : DateTimeUtil.timestampFromMicros(value);
    }

    private static Number number(Object raw) {
        if (raw instanceof Number number) {
            return number;
        }
        throw new IllegalStateException("partition value " + raw + " is a "
                + (raw == null ? "null" : raw.getClass().getName())
                + ", not a number; the manifest does not match its partition type");
    }

    /**
     * 聚合成分区切片。
     *
     * @param planned    一次规划的产物
     * @param schema     表结构（用于把 source-id 翻成列名）
     * @param writeTime  分区内"最近写入时间"的替代值：Iceberg 没有逐文件写入时间，
     *                   退化为本次所读快照的 {@code timestamp-ms}（设计文档 §7.1）
     */
    static List<PartitionSlice> aggregate(List<PlannedFile> planned, Schema schema, long writeTime) {
        Map<String, long[]> counters = new LinkedHashMap<>();
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (PlannedFile file : planned) {
            long[] counter = counters.get(file.key());
            if (counter == null) {
                counters.put(file.key(), new long[] {
                        1,
                        size(file),
                        records(file),
                        writeTime});
                byKey.put(file.key(), values(file.task(), schema));
                continue;
            }
            counter[0]++;
            counter[1] += size(file);
            counter[2] = mergeRecords(counter[2], records(file));
        }
        List<PartitionSlice> slices = new ArrayList<>(counters.size());
        for (Map.Entry<String, long[]> entry : counters.entrySet()) {
            long[] counter = entry.getValue();
            slices.add(new PartitionSlice(entry.getKey(), byKey.get(entry.getKey()),
                    counter[0], counter[1], counter[2], counter[3]));
        }
        return List.copyOf(slices);
    }

    private static long size(PlannedFile file) {
        return file.task().file().fileSizeInBytes();
    }

    /**
     * 一个文件的记录数。
     *
     * <p>Iceberg 的 {@code DataFile} 把 {@code record-count} 当成必填字段
     * （{@code DataFiles.Builder#build} 拒绝 -1），所以实际不会出现未知；这里仍按
     * "任一项未知则总和未知"求和，是为了让 {@code PartitionSlice.records} 的
     * {@code -1} 语义在两种格式上保持一致——多一处小心，少一处"这个数字怎么来的"。
     */
    private static long records(PlannedFile file) {
        return file.task().file().recordCount();
    }

    private static long mergeRecords(long left, long right) {
        return left < 0 || right < 0 ? -1 : left + right;
    }
}
