package io.github.melin.lakehouse.rest.support;

/**
 * 列级标签的引用坐标（设计见 {@code docs/sensitive-data-design.md} §4.6）。
 *
 * <p><b>为什么必须有一个类收口。</b>列级全名 {@code catalog.database.table.column}
 * 要用在两处：展示（三段 + 列名原文）与唯一键（SHA-256 摘要）。
 * 两者一旦分叉——比如某处用点号拼接、另一处用别的分隔符——就会出现
 * 「标签查得到但哈希对不上」，表现是同一列被写进两条 tag。
 * 而这类分叉不会报错，只会让「这列到底是什么」有两个答案。
 *
 * <p>点号是分隔符这一选择本身也有约束：列名里可以有点号（Iceberg 的多级 namespace
 * 与某些引擎的嵌套列名）。用点号拼接会把 {@code a.b} 与 {@code a}+{@code b} 混同。
 * 本工程沿用既有口径（血缘边表也是点号拼接），因为唯一的另一条路——
 * 给每段加长度前缀——会让所有既有读者的中文注释都失效。**在这里写清楚，
 * 是为了让「为什么列名里的点号是个已知的边界」有地方可查**，而不是等到
 * 某个嵌套列被误判时才发现。
 */
public final class ColumnRefs {

    /** 表级聚合结论用空串占位（照抄血缘边「常量列用空串占位」的既有做法）。 */
    public static final String TABLE_LEVEL_COLUMN = "";

    private ColumnRefs() {
    }

    /**
     * 三段 + 列名的可读全名（展示与过滤）。
     *
     * @param column 列名；传 {@code null} 或空串表示表级结论
     */
    public static String fullName(String catalog, String database, String table, String column) {
        String name = column == null ? TABLE_LEVEL_COLUMN : column;
        return catalog + "." + database + "." + table + "." + name;
    }

    /**
     * 唯一键用的 SHA-256 摘要。
     *
     * <p>用 {@link Digests#sha256Hex} 的**多参数**形式而不是先拼成字符串再取摘要：
     * 多参数形式的实现是「依次 update」，与拼接等价；但把四段分别传进来，
     * 读者一眼能看出「哈希覆盖的是这四段」，而不是去猜某个分隔符。
     */
    public static String hash(String catalog, String database, String table, String column) {
        return Digests.sha256Hex(catalog, database, table, column);
    }

    /** 是否表级标签（{@link #TABLE_LEVEL_COLUMN}）。 */
    public static boolean isTableLevel(String column) {
        return column == null || column.isEmpty();
    }
}
