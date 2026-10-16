package io.github.melin.lakehouse.rest.paimon.support;

import io.github.melin.lakehouse.rest.dto.TypeDtos;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * catalog 级的表属性默认值：{@code table-default.<Paimon table property name>}。
 *
 * <p>语义来自 Paimon 自己（{@code Catalog.TABLE_DEFAULT_OPTION_PREFIX}，取值就是
 * {@code table-default.}）：catalog 的选项里带这个前缀的项，是「此后新建的表默认带上的
 * 表属性」。例如在 catalog 上配
 * {@code table-default.deletion-vectors.enabled=true}，
 * 新建的 Paimon 表就自带 {@code deletion-vectors.enabled=true}，不必在每次建表时重复声明。
 * 前缀在写入表属性时**去掉**——表自己不认识这个前缀，留着它等于写了个没人读的键。
 *
 * <p><b>表级参数优先。</b>表请求里显式给了同名属性时以表的为准（这里只做
 * {@code putIfAbsent}）。这条决定了它的定位：默认值是「省事的缺省」，不是「强制的约束」——
 * 要强制某个取值应该用 catalog 的 {@code overrides}（客户端配置合并那一侧），
 * 而不是往表属性里塞。
 *
 * <p><b>只对 Paimon 表生效。</b>Format / Object / Lance 表的 {@code options} 描述的是
 * 它们各自的表格式（{@code type} / {@code path} / {@code file.format} 等），
 * 与 Paimon 表的属性集不是一回事：把 {@code deletion-vectors.enabled} 写进一张 Object 表，
 * 没有任何代码会读它，只会在仓库的 {@code schema-0} 里留下噪音。
 *
 * <p><b>{@code type} 不接受默认。</b>表格式由建表请求决定；若默认值能改它，一次 catalog
 * 配置就会让此后所有新表换一种格式——这是「意外改变表的语义」，不是默认参数的本意。
 *
 * <p><b>只在建表时合并。</b>已建成的表不受影响：改它的表属性是 {@code alter} 的范畴
 * （Paimon 的语义也是建表时合并），配置页会把这一点写在界面上。
 *
 * <p>默认值取自 catalog 的 {@code properties}（管理 API 的
 * {@code PUT /catalogs/{name}} 与控制台「配置」页编辑的那一份），不是客户端配置合并用的
 * {@code defaults} / {@code overrides}：后两者是随 {@code GET /v1/config} 下发给引擎的
 * 「连接参数」，改它影响的是客户端能否连上，与表属性是两件事。
 */
public final class TableDefaults {

    /** 与 Paimon 的 {@code Catalog.TABLE_DEFAULT_OPTION_PREFIX} 取值一致。 */
    public static final String PREFIX = "table-default.";

    private TableDefaults() {
    }

    /**
     * 把 catalog 上的默认参数合并进这次建表请求的 options。
     *
     * @param schema           已被 {@link SchemaSupport#normalize} 归一化过的 schema，就地修改
     * @param kind             这次要建的表格式；只有 {@link TableKinds.Kind#PAIMON} 会合并
     * @param catalogProperties catalog 的属性；不是 {@code table-default.} 开头的项原样忽略
     * @return 实际写进去的键值（已去前缀），按 catalog 上的顺序；没有合并到东西时为空表
     */
    public static Map<String, String> apply(TypeDtos.Schema schema, TableKinds.Kind kind,
                                            Map<String, String> catalogProperties) {
        Map<String, String> applied = new LinkedHashMap<>();
        if (kind != TableKinds.Kind.PAIMON || catalogProperties == null || catalogProperties.isEmpty()) {
            return applied;
        }
        if (schema.getOptions() == null) {
            schema.setOptions(new LinkedHashMap<>());
        }
        for (Map.Entry<String, String> entry : catalogProperties.entrySet()) {
            String property = propertyNameOf(entry.getKey());
            if (property == null || schema.getOptions().containsKey(property)) {
                // 表级参数优先，这是本类唯一的优先级规则
                continue;
            }
            String value = entry.getValue() == null ? "" : entry.getValue();
            schema.getOptions().put(property, value);
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
        if (property.isEmpty() || TableKinds.TYPE_OPTION.equals(property)) {
            return null;
        }
        return property;
    }
}
