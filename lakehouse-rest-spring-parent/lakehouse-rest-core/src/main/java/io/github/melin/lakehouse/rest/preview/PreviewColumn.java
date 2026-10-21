package io.github.melin.lakehouse.rest.preview;

import java.util.Locale;

/**
 * 预览里的一列——名字、SQL 类型、以及**前端要用的粗分类**。
 *
 * <p>{@code type} 是给人看的（{@code DECIMAL(10,2)}、{@code TIMESTAMP(3)}），
 * {@code kind} 是给机器用的：算子候选（§6.1）与值输入控件都按它选，
 * 而不是让前端去解析 SQL 类型字符串。两处各存一份的理由是它们服务不同的读者，
 * 而唯一需要保持一致的地方（"哪些算子能用在哪些列上"）就在服务端的
 * {@link PreviewFilters#validate} 里，前端只是提前把不可能的选项藏起来。
 *
 * @param name       列名
 * @param type       SQL 类型文本，含 {@code NOT NULL} 后缀
 * @param kind       粗分类，决定算子候选与值控件
 * @param partition  是否是分区字段（Paimon 的 identity 分区列；Iceberg 的 hidden 分区不是列，不会出现在这里）
 * @param primaryKey 是否主键列（前端加钥匙图标）
 */
public record PreviewColumn(String name, String type, Kind kind, boolean partition, boolean primaryKey) {

    /** 列的粗分类。判别顺序即 {@link PreviewFilters} 里那套，别在两处各写一遍。 */
    public enum Kind {
        NUMBER,
        TEXT,
        TEMPORAL,
        BOOLEAN,
        BINARY,
        COMPLEX;

        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
