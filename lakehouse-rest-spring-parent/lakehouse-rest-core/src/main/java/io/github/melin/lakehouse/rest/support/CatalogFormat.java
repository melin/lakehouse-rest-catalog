package io.github.melin.lakehouse.rest.support;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * catalog 的**表格式**，也就是它服务哪一种 REST Catalog 协议。
 *
 * <p>它与 {@code catalog_type}（{@code INTERNAL} / {@code EXTERNAL}，托管模式）是
 * **两条正交的属性**，因此是两个列、两个字段：
 * {@code EXTERNAL + ICEBERG}（外部 Iceberg 仓库的只读入口）是「托管模式」那个枚举
 * 表达不了的状态。取舍见 {@code docs/iceberg-rest-catalog-design.md} §3.1。
 *
 * <p><b>创建后不可变。</b>同一个 prefix 下的表，两种格式的元数据代数、仓库里的
 * 物化产物、客户端可见的端点集合都不同；改一个列等于让所有已建对象的语义整体漂移，
 * 而且 Iceberg 的 {@code table-uuid} 与 Paimon 的 snapshot 文件会留在同一个目录里对不上。
 * 要换格式就删除重建。
 *
 * <p>这个枚举放在 core 而不是某个协议面：它是**分派键**，装配层（{@code /v1/config}、
 * 错误响应形状、异常处理）与两个协议面都要读它，而 core 同时被三方依赖。
 */
public enum CatalogFormat {

    /** Apache Paimon 的 REST Catalog（本服务端原有的那一套，60 个端点）。 */
    PAIMON,

    /** Apache Iceberg 的 REST Catalog（35 个端点）。 */
    ICEBERG,

    /**
     * Lance 的 REST Catalog（{@code /lance/v1/**}，规范共 48 个 operation，本服务端实现 13 个）。
     *
     * <p>它与上面两种的差别不是「又一种表格式」，而是**协议面形状不同**：Lance 没有
     * {@code /v1/config} 握手、{@code id} 用分隔符拼在路径里、错误体是
     * {@code {error, code, detail, instance}} 四字段。因此它挂在自己的基址上，
     * 不复用 {@code /v1/{prefix}}。见 {@code docs/lance-rest-catalog-design.md}。
     */
    LANCE;

    /** 建表 / 自动登记时未指定格式的兜底值，与列默认值一致。 */
    public static final CatalogFormat DEFAULT = PAIMON;

    /** 列里存的、管理 API 里传的字面量（大写，与列默认值逐字一致）。 */
    public String wireName() {
        return name();
    }

    /** 按字面量解析，未知取值返回空。 */
    public static Optional<CatalogFormat> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(format -> format.name().equals(normalized))
                .findFirst();
    }

    /**
     * 读取数据库列或响应字段里的取值，认不出来时回落到 {@link #DEFAULT}。
     *
     * <p>回落而不是抛异常：这个值可能来自一行旧数据、也可能是运维直接改库写进去的，
     * 两种情况下「按 Paimon 解释」都是安全的那一侧——默认格式下的行为与本特性引入前
     * 完全一致。写入侧的校验（管理 API 的 {@code catalogFormat}）在
     * {@link #parse} 上做，不让脏值进库。
     */
    public static CatalogFormat of(String value) {
        return parse(value).orElse(DEFAULT);
    }
}
