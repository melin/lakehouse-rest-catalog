package io.github.melin.lakehouse.rest.scan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

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
     * <p><b>四段依次 update，而不是先拼成一个字符串再取摘要。</b>两者的结果相同（拼接就是
     * 依次 update），但把四段分开传，读者一眼能看出「哈希覆盖的是这四段」，不必去猜
     * 某个分隔符是否参与了哈希。这条区别在改动时才有价值：想加一段前缀的人会看见
     * 这里没有拼接，也就不会以为「改分隔符不影响既有标签」。
     *
     * <p><b>这里自己算，不借 core 的 {@code Digests}。</b>内核模块要能独立编进集群上的
     * 扫描作业（那边没有 core），而 SHA-256 是 JDK 自带的能力——为了八行代码把 core 的
     * 一个通用工具搬进来，会把「哪些东西属于内核」这条边界弄模糊。算法与 core 那份逐字
     * 相同（同一个 JDK {@code MessageDigest}、同样的小写十六进制），因此**历史标签的哈希
     * 不会变**：列引用哈希一旦变，所有既有标签在库里的唯一键就与扫描结果对不上。
     */
    public static String hash(String catalog, String database, String table, String column) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : new String[] {catalog, database, table, column}) {
                digest.update((part == null ? "" : part).getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            // JDK 必须提供 SHA-256；走到这里说明运行环境异常
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    /** 是否表级标签（{@link #TABLE_LEVEL_COLUMN}）。 */
    public static boolean isTableLevel(String column) {
        return column == null || column.isEmpty();
    }
}
