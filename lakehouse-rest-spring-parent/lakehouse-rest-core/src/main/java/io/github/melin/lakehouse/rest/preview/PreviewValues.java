package io.github.melin.lakehouse.rest.preview;

import java.util.ArrayList;
import java.util.List;

/**
 * 预览里"怎么把一个值渲染成前端认得的样子"的**共用规则**。
 *
 * <p><b>为什么放在 core 而不是各自拷贝一份。</b>两个格式的 reader 各自把原生行翻成
 * 普通 Java 值（Paimon 的 {@code InternalRow}、Iceberg 的 {@code Record}），
 * 但翻完之后还有三件事必须逐字一致：过长的文本怎么截、二进制怎么表示、
 * 数组与映射怎么表示。它们看起来只是文案，实际上都是**与前端之间的契约**：
 * 截断符是尾部的 {@code …}（前端据此显示省略号，不再额外传 {@code truncated} 标志），
 * 二进制是 {@code <binary 12 B>}（前端按尖括号识别"这不是真值"）。
 * 两处各写一份的代价是"Paimon 表显示 512 字截断、Iceberg 表显示 1024 字"这类
 * 只在对比时才发现的漂移。
 *
 * <p>值本身的类型映射**不在这里**：{@code DATE} 在 Paimon 是 int 天数、
 * 在 Iceberg 是 {@code LocalDate}，那一层归各自的 reader。本类只处理
 * "已经是普通 Java 值"之后的部分。
 */
public final class PreviewValues {

    /** 单元格文本的长度上限（字符数）。超过就截断并加省略号，见 {@link #truncate}。 */
    public static final int MAX_TEXT = 512;

    /** 展开数组 / 映射 / 行类型时最多列多少个元素，超出的用省略号表示。 */
    public static final int NESTED_LIMIT = 20;

    private PreviewValues() {
    }

    /**
     * 二进制不回传字节，只给形状。
     *
     * <p>预览不是文件下载器，而把 blob 塞进 JSON 会让一页响应变成几十 MB。
     * 但"有多少字节"要留着——排查时它至少能回答"这一列是不是真的有东西"。
     */
    public static String binary(int bytes) {
        return "<binary " + bytes + " B>";
    }

    /** 数组只给形状（{@code [3 项]}）：展开成嵌套 JSON 需要按元素类型逐个取值。 */
    public static String array(int items) {
        return "[" + items + " 项]";
    }

    /** 映射只给形状（{@code {2 键}}），理由同 {@link #array}。 */
    public static String map(int keys) {
        return "{" + keys + " 键}";
    }

    /**
     * 把一行里过长的文本截短——**只截给调用方的那一份**，判定用的那一份保持原样。
     *
     * <p>两个理由。其一，响应体要有上限：一列 JSON 正文可能有几 MB，200 行就能把
     * 响应撑到几百 MB，而这个端点的读者只想"看一眼长什么样"。
     *
     * <p>其二，<b>过滤必须看到完整的值</b>。{@code contains} 命中的位置可能在第 512 个
     * 字符之后，先截再判会把该命中的行判成不命中——那是一种只在长文本上出现的
     * 静默错误，而"过滤器不可信"这件事比"响应大"严重得多。因此调用方要先用完整值
     * 判过滤（{@code PreviewFilters#testAll}），判定通过后再用本方法裁一份放进结果。
     *
     * <p>截断标记是尾部的 {@code …}：前端据此显示省略号，不需要额外的
     * {@code truncated} 字段（多一个字段就要多一处保持一致的地方）。
     */
    public static List<Object> truncate(List<Object> values) {
        List<Object> shortened = new ArrayList<>(values.size());
        for (Object value : values) {
            if (value instanceof String text && text.length() > MAX_TEXT) {
                shortened.add(text.substring(0, MAX_TEXT) + "…");
            } else {
                shortened.add(value);
            }
        }
        return shortened;
    }
}
