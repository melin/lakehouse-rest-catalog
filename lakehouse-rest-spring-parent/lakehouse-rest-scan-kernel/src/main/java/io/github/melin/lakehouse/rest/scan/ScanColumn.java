package io.github.melin.lakehouse.rest.scan;

import java.util.Locale;

/**
 * 一列的三个关键信息：名称、类型、注释（设计见 {@code docs/sensitive-data-design.md} §5.1）。
 *
 * <p><b>为什么它是内核模块里的一个独立类型，而不是 {@code SchemaColumns} 的嵌套 record。</b>
 * 「一列长什么样」是打分器的输入，与「{@code schema_doc} 那个 JSON 有几种形状」是两件事：
 * 前者两个执行位置都要用（服务端进程内、集群上的扫描作业），后者只属于服务端——
 * {@code schema_doc} 是服务端库里的一列，作业从来见不到它。
 *
 * <p>把两者分开之后，作业拿到的是**已经归一化过的列**（服务端解析好、随清单下发），
 * 于是集群那一侧既不需要 Jackson，也不需要认识 Paimon / Iceberg / Lance 的 schema 写法。
 *
 * <p>{@code type} 是**原始类型声明**（可能带括号，如 {@code varchar(18)}）：
 * 归一化由 {@link #normalizedType()} 与 {@link #length()} 现算，而不是在构造时抹平——
 * D2 的两个判据（类型名、长度）要用同一份原始文本推出来，抹平一次就再也推不出长度。
 */
public record ScanColumn(String name, String type, String comment) {

    /** 类型名的小写形式，去掉了括号与参数。 */
    public String normalizedType() {
        if (type == null) {
            return "";
        }
        String lower = type.toLowerCase(Locale.ROOT).trim();
        int paren = lower.indexOf('(');
        return paren < 0 ? lower : lower.substring(0, paren).trim();
    }

    /**
     * 类型声明里的长度/精度；没有则 {@code null}。
     *
     * <p>取第一个整数即可：{@code varchar(18)} 是长度，{@code decimal(12,2)}
     * 是精度——D2 的判据是「8–20 位的定长字符串」，两者都只要第一个数。
     *
     * <p><b>嵌套类型形态（Paimon 的 {@code {"type":"DECIMAL","precision":12}}）
     * 拿不到这个数</b>：它不在类型名的括号里，而在同级字段上。这里刻意不去读——
     * D2 的长度判据只对字符型有意义（{@code char(18)} → 身份证候选），
     * 而 Paimon 的嵌套类型里没有字符型（字符长度由 {@code length} 字段表达，
     * 而 {@code CHAR(18)} 这种写法在 Paimon 侧本来就走裸字符串形态）。
     * 为一条用不到的路径去认 JSON 字段名，只会让这段代码多一处要维护的猜测。
     */
    public Integer length() {
        if (type == null) {
            return null;
        }
        int open = type.indexOf('(');
        int close = type.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return null;
        }
        String inner = type.substring(open + 1, close);
        int comma = inner.indexOf(',');
        String first = (comma < 0 ? inner : inner.substring(0, comma)).trim();
        try {
            return Integer.valueOf(first);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
