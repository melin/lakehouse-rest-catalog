package io.github.melin.lakehouse.rest.preview;

import java.util.List;
import java.util.Map;

/**
 * 一个分区切片：一组分区字段取值 + 它名下的文件统计。
 *
 * <p><b>为什么是"切片"而不是"分区值"。</b>多级分区（{@code dt} / {@code hour} / {@code region}）
 * 的一个分区由多个字段共同确定，界面上要能整体选中、整体过滤；只传一个字段的值，
 * 到了比较与回显那一步就得再拼一次。{@code values} 因此是"字段 → 值"的有序映射，
 * 顺序与表的分区字段声明顺序一致——{@code key} 也按这个顺序拼，于是同一个分区
 * 在不同请求里得到同一个键。
 *
 * <p><b>{@code key} 为什么是字符串而不是格式自己的对象。</b>它是跨层的通行证：
 * 游标里存它、planner 用它做集合运算、格式实现用它回查自己的原生表示
 * （Paimon 的 {@code BinaryRow}、Iceberg 的 {@code StructLike}）。让 core 认识
 * 这些原生类型等于让 core 依赖两个格式的 API，而它连 {@code org.apache.paimon}
 * 都不该 import。
 *
 * @param key           规范化键，形如 {@code dt=2026-10-21/hour=15}
 * @param values        字段 → Java 值（{@code null} 表示该字段为空）；用于比较与展示
 * @param files         数据文件数
 * @param bytes         数据文件总字节
 * @param records       记录数（格式给不出时为 -1）
 * @param lastWriteTime 分区内最新文件写入时间（epoch 毫秒；给不出时为 0）
 */
public record PartitionSlice(
        String key,
        Map<String, Object> values,
        long files,
        long bytes,
        long records,
        long lastWriteTime) {

    /**
     * 拼规范化键。
     *
     * <p>分隔符用 {@code /}、字段与值之间用 {@code =}：这两个字符在分区值里不常见，
     * 而键本身要能在日志与游标里被人读懂——用 {@code \u0001} 之类的控制字符虽然更安全，
     * 但让排障时需要另写一个解码脚本。
     */
    public static String key(List<String> fields, Map<String, Object> values) {
        StringBuilder builder = new StringBuilder();
        for (String field : fields) {
            if (builder.length() > 0) {
                builder.append('/');
            }
            builder.append(field).append('=');
            Object value = values.get(field);
            // null 写成一个明确的可读标记，而不是保留 Java 的 "null" 字面量：
            // 分区值真的可能是字符串 "null"（某些写入端就是这么干的），两者必须能区分
            builder.append(value == null ? "<null>" : String.valueOf(value));
        }
        return builder.toString();
    }

    /** 该切片在某字段上的取值；字段不在切片里时为 {@code null}。 */
    public Object value(String field) {
        return values.get(field);
    }
}
