package io.github.melin.lakehouse.rest.paimon.preview;

import io.github.melin.lakehouse.rest.preview.PreviewValues;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.paimon.data.BinaryString;
import org.apache.paimon.data.InternalArray;
import org.apache.paimon.data.InternalMap;
import org.apache.paimon.data.InternalRow;
import org.apache.paimon.data.Timestamp;
import org.apache.paimon.types.DataField;
import org.apache.paimon.types.DataType;
import org.apache.paimon.types.DecimalType;
import org.apache.paimon.types.RowType;
import org.apache.paimon.types.TimestampType;

/**
 * 把 Paimon 的 {@link InternalRow} 翻成**普通 Java 值**，好让响应能直接序列化成 JSON。
 *
 * <p><b>为什么必须翻。</b>{@code InternalRow} 里的东西不是"JSON 能表达的值"：
 * 字符串是 {@link BinaryString}（一段带偏移的字节）、时间是 {@link Timestamp}
 * （纳秒 + 时区语义）、二进制是 {@code byte[]}。直接交给 Jackson 的下场是
 * 一列 {@code {"offset":0,"numBytes":12,...}} 之类的内部结构——看起来像数据，
 * 实际上是实现细节泄漏。
 *
 * <p><b>几个刻意的取舍。</b>
 *
 * <ul>
 *   <li>二进制不回传字节，只给形状（{@link PreviewValues#binary}）：预览不是文件下载器，
 *       而把 blob 塞进 JSON 会让一页响应变成几十 MB。</li>
 *   <li>{@code DATE} / {@code TIMESTAMP} 翻成 {@link LocalDate} / {@link LocalDateTime}，
 *       让 Jackson 按 ISO 输出（前端不用再猜格式）。</li>
 *   <li>{@code TIMESTAMP_LTZ} 翻成 {@link java.time.Instant}（带时区语义的那一类本该如此），
 *       而 {@code TIMESTAMP} 是没有时区的墙上时间，翻成 {@link LocalDateTime}。</li>
 *   <li>数组与映射这一版只给形状（{@code [3 项]} / {@code {2 键}}）：把它们展开成
 *       嵌套 JSON 需要按元素类型逐个取值，而预览里"看一眼有没有值"通常就够了。
 *       行类型（{@code ROW}）例外——它字段少、语义明确，展开成对象更有用。</li>
 * </ul>
 *
 * <p><b>渲染规则本身在 core 的 {@link PreviewValues} 里。</b>截断、二进制、嵌套形状
 * 这三件事必须与 Iceberg 侧的读取器逐字一致，否则同一份数据在两种表上显示得不一样。
 */
final class RowValues {

    private RowValues() {
    }

    /**
     * 取一行里的前 {@code count} 个值（{@code count} 是"要回给调用方的列数"，
     * 投影末尾补读的过滤列会被切掉）。
     */
    static List<Object> toJava(InternalRow row, List<DataType> types, int count) {
        int size = Math.min(count, types.size());
        List<Object> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(value(row, i, types.get(i)));
        }
        return values;
    }

    /** 把一个字段翻成 JSON 友好的值；null 原样返回（前端渲染成 {@code <null>}）。 */
    static Object value(InternalRow row, int position, DataType type) {
        if (row.isNullAt(position)) {
            return null;
        }
        try {
            return switch (type.getTypeRoot()) {
                case BOOLEAN -> row.getBoolean(position);
                case TINYINT -> (int) row.getByte(position);
                case SMALLINT -> (int) row.getShort(position);
                case INTEGER -> row.getInt(position);
                case BIGINT -> row.getLong(position);
                case FLOAT -> row.getFloat(position);
                case DOUBLE -> row.getDouble(position);
                case DECIMAL -> {
                    DecimalType decimal = (DecimalType) type;
                    yield row.getDecimal(position, decimal.getPrecision(), decimal.getScale()).toBigDecimal();
                }
                case CHAR, VARCHAR -> string(row.getString(position));
                case BINARY, VARBINARY -> PreviewValues.binary(row.getBinary(position).length);
                case DATE -> LocalDate.ofEpochDay(row.getInt(position));
                case TIME_WITHOUT_TIME_ZONE ->
                        LocalTime.ofNanoOfDay(row.getInt(position) * 1_000_000L);
                case TIMESTAMP_WITHOUT_TIME_ZONE -> {
                    TimestampType timestamp = (TimestampType) type;
                    yield row.getTimestamp(position, timestamp.getPrecision()).toLocalDateTime();
                }
                case TIMESTAMP_WITH_LOCAL_TIME_ZONE -> {
                    TimestampType timestamp = (TimestampType) type;
                    yield row.getTimestamp(position, timestamp.getPrecision()).toInstant();
                }
                case ARRAY -> array(row.getArray(position));
                case MAP -> map(row.getMap(position));
                case ROW -> nested(row, position, (RowType) type);
                // 落到这里的是我们没有专门渲染的类型：Paimon 2.1.0 新增的
                // GEOMETRY / GEOGRAPHY（WKB 字节，没有稳定的长度访问器）、
                // 以及 MULTISET / VARIANT / VECTOR。统一渲染成 `<TYPE_ROOT>`，
                // 比按「大概能当字节读」去猜安全——猜错会把整页拖进异常分支。
                default -> "<" + type.getTypeRoot() + ">";
            };
        } catch (RuntimeException exception) {
            // 单列取不出来不该让整页失败：把异常类型写进单元格，排障时反而更有用
            return "<" + exception.getClass().getSimpleName() + ">";
        }
    }

    private static String string(BinaryString value) {
        return value == null ? null : value.toString();
    }

    private static String array(InternalArray value) {
        return PreviewValues.array(value.size());
    }

    private static String map(InternalMap value) {
        return PreviewValues.map(value.size());
    }

    private static Map<String, Object> nested(InternalRow row, int position, RowType type) {
        List<DataField> fields = type.getFields();
        InternalRow nested = row.getRow(position, fields.size());
        Map<String, Object> values = new LinkedHashMap<>();
        int limit = Math.min(fields.size(), PreviewValues.NESTED_LIMIT);
        for (int i = 0; i < limit; i++) {
            DataField field = fields.get(i);
            values.put(field.name(), value(nested, i, field.type()));
        }
        return values;
    }
}
