package io.github.melin.lakehouse.rest.iceberg.preview;

import io.github.melin.lakehouse.rest.preview.PreviewValues;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.types.Types;

/**
 * 把 Iceberg 的 {@link Record} 翻成**普通 Java 值**，好让响应能直接序列化成 JSON。
 *
 * <p>与 Paimon 侧 {@code RowValues} 是一对：那边从 {@code InternalRow} 取值，
 * 这边从 {@code Record} 取。两边**刻意不共用代码**——取值方式与原生类型完全不同
 * （{@code BinaryString} vs {@code String}、int 天数 vs {@code LocalDate}），
 * 共用只会让其中一边迁就另一边。但"翻完之后怎么渲染"必须一致，那部分在
 * {@link PreviewValues} 里（截断、二进制、嵌套形状），两边都调它。
 *
 * <p>Iceberg 的 {@code Record} 已经是 {@code LocalDate} / {@code LocalDateTime} /
 * {@code OffsetDateTime} / {@code BigDecimal} 这类普通类型（{@code GenericData} 的
 * 映射），所以这里不需要像 Paimon 那样按字段类型二次转换——只看运行时类型即可。
 * 唯一要处理的是"JSON 表达不了或不该表达"的三类：字节、集合、嵌套行。
 */
final class IcebergRowValues {

    private IcebergRowValues() {
    }

    /**
     * 取一行里的前 {@code count} 个值（{@code count} 是"要回给调用方的列数"，
     * 投影末尾补读的过滤列会被切掉）。
     *
     * <p>注意读出来的 {@code Record} 可能是**加宽过的**：有 position delete 时
     * {@code DeleteFilter} 会多读一个行号列（追加在末尾），于是 {@code record.size()}
     * 大于 {@code fields.size()}。按 {@code count} 切而不是按 record 大小遍历，
     * 就是为了让那个列不会被当成业务列泄漏出去。
     */
    static List<Object> toJava(Record record, List<Types.NestedField> fields, int count) {
        int size = Math.min(count, fields.size());
        List<Object> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(value(record.get(i)));
        }
        return values;
    }

    /** 把一个值翻成 JSON 友好的形态；null 原样返回（前端渲染成 {@code <null>}）。 */
    static Object value(Object raw) {
        if (raw == null) {
            return null;
        }
        if (raw instanceof CharSequence text) {
            return text.toString();
        }
        if (raw instanceof ByteBuffer buffer) {
            return PreviewValues.binary(buffer.remaining());
        }
        if (raw instanceof byte[] bytes) {
            return PreviewValues.binary(bytes.length);
        }
        if (raw instanceof List<?> list) {
            return PreviewValues.array(list.size());
        }
        if (raw instanceof Map<?, ?> map) {
            return PreviewValues.map(map.size());
        }
        if (raw instanceof Record nested) {
            return nested(nested);
        }
        // 数值 / 布尔 / 日期时间 / BigDecimal / UUID：Jackson 都认，原样给
        return raw;
    }

    /**
     * 嵌套行展开成对象——与 Paimon 侧的取舍一致：行类型字段少、语义明确，
     * 展开成对象比给一个 {@code {3 键}} 更有用（而数组/映射仍然只给形状）。
     */
    private static Map<String, Object> nested(Record record) {
        List<Types.NestedField> fields = record.struct().fields();
        Map<String, Object> values = new LinkedHashMap<>();
        int limit = Math.min(fields.size(), PreviewValues.NESTED_LIMIT);
        for (int i = 0; i < limit; i++) {
            values.put(fields.get(i).name(), value(record.get(i)));
        }
        return values;
    }
}
