package io.github.melin.lakehouse.rest.preview;

import java.util.Set;

/**
 * 一条行级过滤条件：列 + 算子 + 值，多条之间是 AND。
 *
 * <p><b>值仍然是 {@code Object} 而不是 {@code String}。</b>请求体里它是 JSON 的
 * string / number / boolean 三选一，Jackson 反序列化后就是对应的 Java 类型；
 * 在这里统一转成字符串会丢掉"这个值到底是数字还是字符串"这个信息，
 * 而 {@link PreviewFilters#validate} 的强校验（§6.3：不给 {@code INT} 列传
 * {@code "abc"}）正需要它。真正的类型对齐发生在校验那一步，不在解析这一步。
 *
 * @param column 列名，大小写敏感
 * @param op     算子，取值见 {@link #OPS}
 * @param value  比较值；{@code isNull}/{@code isNotNull} 时为 {@code null}
 */
public record ColumnFilter(String column, String op, Object value) {

    /** 支持的算子。名单短是刻意的：每多一个就要多一条"能不能下推、null 怎么算"的规则。 */
    public static final Set<String> OPS = Set.of(
            "eq", "ne", "gt", "ge", "lt", "le", "contains", "startsWith", "isNull", "isNotNull");

    /** 需要比较值的算子；其余（null 判定）不看 {@code value}。 */
    public static boolean needsValue(String op) {
        return !"isNull".equals(op) && !"isNotNull".equals(op);
    }
}
