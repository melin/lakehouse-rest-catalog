package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.scan.ScanColumn;
import java.util.List;

/**
 * 覆盖率分母的算法（设计见 {@code docs/sensitive-data-design.md} §12 / §13）。
 *
 * <p><b>为什么分母要单独有一个类。</b>「已分类 / 全部」这个分数的分母是
 * 「这个范围内一共有多少列」，而它**只能靠解析每张表的 {@code schema_doc} 得到**。
 * 把「列总数」直接存成一列的话，每次建表、加列、删列都要同步维护它，
 * 而漂移的表现是「覆盖率算出来大于 100%」或者「加了一列覆盖率反而降了」。
 * 现算的代价是解析 JSON，那就把「解析多少张表」这个上限明确写出来
 * （见 {@link #MAX_TABLES}），而不是让它默默变成一次全量元数据扫描。
 *
 * <p><b>为什么与「有标签的表数」分开算。</b>两个集合的来源不同：
 * 分母来自 {@code lakehouse_table}（哪些表在这个范围内），分子来自
 * {@code lakehouse_column_tag}（哪些列有标签）。合成一个遍历会让
 * 「这个范围内有多少表」也变成「只能从标签表里数出来」——而没被扫过的表
 * 在标签表里根本不存在，于是覆盖率恒为 100%，这一屏就失去了意义。
 *
 * <p><b>表上限是对「诚实」的一次取舍。</b>超过上限时不是静默截断：调用方拿到的
 * {@code totalTables} 与 {@code counted} 是两个数，前端据此写明「按前 N 张表计算」。
 * 只回一个被截断的分母等于让这个数字撒谎，而这一屏存在的理由就是
 * 「不承诺扫完全覆盖」。
 */
public final class CoverageTotals {

    /**
     * 分母最多数多少张表。
     *
     * <p>取 2000 的依据：每张表要解析一次 {@code schema_doc}（多数在几 KB 量级），
     * 2000 张在毫秒到秒级之间；而 2000 张表 × 平均 50 列 = 10 万列，
     * 已经足够让「覆盖率」这个比例有意义。更大的 catalog 需要的是**分范围看**
     * （按库、按表过滤），那正是这个接口本来就支持的参数。
     */
    public static final int MAX_TABLES = 2000;

    private CoverageTotals() {
    }

    /**
     * 分母的计算结果。
     *
     * @param totalColumns 已计入分母的列数（{@code counted} 张表里的列）
     * @param counted      实际数了几张表
     * @param totalTables  范围内的表总数（**可能大于 {@code counted}**）
     * @param truncated    {@code totalTables > counted} 时为 true
     */
    public record Totals(int totalColumns, int counted, int totalTables, boolean truncated) {
    }

    /**
     * 数一遍这些表一共有多少列。
     *
     * <p><b>不去重列名。</b>「同一列名在两张表里都出现」（{@code id} / {@code created_at}
     * 到处都是）是正常情况，去重会让分母变成「整个范围里有多少个不同的列名」——
     * 那时覆盖率会远高于实际，而高估覆盖率意味着「还有很多列没分类」这件事被掩盖了。
     * 同一个 schema 内部也不会重复（schema 是列定义的有序集合）。
     *
     * @param schemaDocs 范围内每张表的 {@code schema_doc}；**已被调用方截断到
     *                   {@link #MAX_TABLES}**，因此这里的 {@code size()} 就是 {@code counted}
     * @param totalTables 范围内的表总数（调用方知道，且正是它做的截断）
     */
    public static Totals count(List<String> schemaDocs, int totalTables) {
        int counted = schemaDocs.size();
        int columns = 0;
        for (String schemaDoc : schemaDocs) {
            for (ScanColumn column : SchemaColumns.parse(schemaDoc)) {
                if (column.name() == null || column.name().isEmpty()) {
                    continue;
                }
                columns++;
            }
        }
        return new Totals(columns, counted, Math.max(counted, totalTables), totalTables > counted);
    }
}
