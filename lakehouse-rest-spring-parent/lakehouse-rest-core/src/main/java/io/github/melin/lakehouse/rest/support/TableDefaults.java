package io.github.melin.lakehouse.rest.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * catalog 级的表属性默认值：{@code table-default.<表属性名>}。
 *
 * <p>两种格式用同一套写法，语义也一致：catalog 的属性里带这个前缀的项，是
 * 「此后在这个 catalog 下新建的表默认带上的表属性」。例如配
 * {@code table-default.write.format.default=parquet}，
 * 新建的表就自带 {@code write.format.default=parquet}，不必在每次建表时重复声明。
 * 前缀在写入表属性时**去掉**——表自己不认识这个前缀，留着它等于写了个没人读的键。
 *
 * <p><b>为什么放在 core。</b>这是 catalog 属性到表属性的一条纯函数映射，
 * 与具体格式的元数据代数无关（Paimon 的 {@code deletion-vectors.enabled} 与
 * Iceberg 的 {@code write.format.default} 走的是同一段代码）。两份实现意味着
 * 「前缀怎么切、哪些键要跳过」会有两个答案，而这类分叉只在某个格式上出错，很难发现。
 * 各格式不同的部分由调用方决定：Paimon 只对 Paimon 表合并（Format / Object / Lance
 * 表的 options 描述的是各自的表格式，不是 Paimon 表属性），Iceberg 侧的表只有一种。
 *
 * <p><b>表级参数优先。</b>建表请求里显式给了同名属性时以表的为准（这里只做
 * {@code putIfAbsent}）。这条决定了它的定位：默认值是「省事的缺省」，不是「强制的约束」——
 * 要强制某个取值应该用 catalog 的 {@code overrides}（客户端配置合并那一侧），
 * 而不是往表属性里塞。
 *
 * <p><b>{@code type} 不接受默认。</b>表格式由建表请求决定；若默认值能改它，一次 catalog
 * 配置就会让此后所有新表换一种格式——这是「意外改变表的语义」，不是默认参数的本意。
 * 对 Iceberg 表 {@code type} 不是合法表属性，跳过它同样是对的。
 *
 * <p><b>只在建表时合并。</b>已建成的表不受影响：改它的表属性是 {@code alter} 的范畴。
 *
 * <p>默认值取自 catalog 的 {@code properties}（管理 API 的
 * {@code PUT /catalogs/{name}} 与控制台「配置」页编辑的那一份），不是客户端配置合并用的
 * {@code defaults} / {@code overrides}：后两者是随 {@code GET /v1/config} 下发给引擎的
 * 「连接参数」，改它影响的是客户端能否连上，与表属性是两件事。
 */
public final class TableDefaults {

    /** 与 Paimon 的 {@code Catalog.TABLE_DEFAULT_OPTION_PREFIX}、Iceberg 的
     *  {@code CatalogProperties.TABLE_DEFAULT_PREFIX} 取值一致。 */
    public static final String PREFIX = "table-default.";

    /** 表格式选项：由建表请求决定，不接受默认（见类注释）。 */
    private static final String TABLE_KIND_OPTION = "type";

    private TableDefaults() {
    }

    /**
     * 把 catalog 上的默认参数合并进这次建表请求的表属性。
     *
     * @param tableProperties   这次建表的表属性，就地修改；为 {@code null} 时不合并
     * @param catalogProperties catalog 的属性；不是 {@code table-default.} 开头的项原样忽略
     * @return 实际写进去的键值（已去前缀），按 catalog 上的顺序；没有合并到东西时为空表
     */
    public static Map<String, String> apply(Map<String, String> tableProperties,
                                            Map<String, String> catalogProperties) {
        Map<String, String> applied = new LinkedHashMap<>();
        if (tableProperties == null || catalogProperties == null || catalogProperties.isEmpty()) {
            return applied;
        }
        for (Map.Entry<String, String> entry : catalogProperties.entrySet()) {
            String property = propertyNameOf(entry.getKey());
            if (property == null || tableProperties.containsKey(property)) {
                // 表级参数优先，这是本类唯一的优先级规则
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            tableProperties.put(property, value);
            applied.put(property, value);
        }
        return applied;
    }

    /**
     * 取出一个 catalog 属性对应的表属性名，不是默认参数时返回 {@code null}。
     *
     * <p>三种写法在这里被挡掉：不以 {@value #PREFIX} 开头（普通 catalog 属性）、
     * 前缀后面为空（{@code table-default.} 自己，去掉前缀是个空名）、
     * 以及 {@code type}（表格式由建表请求决定，见类注释）。
     */
    private static String propertyNameOf(String catalogProperty) {
        if (catalogProperty == null || !catalogProperty.startsWith(PREFIX)) {
            return null;
        }
        String property = catalogProperty.substring(PREFIX.length()).trim();
        if (property.isEmpty() || TABLE_KIND_OPTION.equals(property)) {
            return null;
        }
        return property;
    }
}
