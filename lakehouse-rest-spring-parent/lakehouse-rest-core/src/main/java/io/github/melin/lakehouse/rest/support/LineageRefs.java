package io.github.melin.lakehouse.rest.support;

import java.util.List;
import java.util.Optional;

/**
 * 血缘里的 {@code ref}：点号拼接的表全名，以及它的两条例外规则。
 *
 * <p>形状是 {@code {catalog}.{namespace...}.{table}}，<b>不保证正好三段</b>：
 * Paimon 的多级 namespace 用点号拼成一段路径，与 {@link NamespacePaths} 同口径。
 * 因此解析只能「首段是 catalog、末段是表、中间全是 namespace」，不能按下标取值。
 *
 * <p>三条例外的来源都在设计文档 §4.1 / §5.6：
 *
 * <ul>
 *   <li>{@code ~} 是<b>保留前缀</b>：{@code ~values}、{@code ~temp:x}、{@code ~file:<hash>}、
 *       {@code ~external:<provider>}。这些节点不在目录里，判权时直接跳过——
 *       它们不是「谁的资源」，问「你有没有权限」没有意义。</li>
 *   <li>大小写<b>原样保留</b>：折叠规则属于会话配置（{@code spark.sql.caseSensitive}），
 *       服务端再折叠一次等于替引擎做决定。</li>
 *   <li>catalog 必填：同名表在两个 catalog 下是两张表，省略 catalog 会把它们合成一条边。</li>
 * </ul>
 */
public final class LineageRefs {

    /** 外部节点的保留前缀。 */
    public static final String EXTERNAL_PREFIX = "~";

    /** 纯常量源（{@code VALUES} / {@code SELECT 1}）的固定节点名。 */
    public static final String VALUES = "~values";

    private LineageRefs() {
    }

    /** 拆开的 ref。{@code namespace} 可能为空列表（理论上不该出现，但解析要容错）。 */
    public record Parsed(String catalog, List<String> namespace, String table) {

        /** 重新拼回全名。 */
        public String ref() {
            StringBuilder sb = new StringBuilder(catalog);
            for (String part : namespace) {
                sb.append('.').append(part);
            }
            return sb.append('.').append(table).toString();
        }
    }

    /** 是否外部节点（{@code ~} 开头）。空串也返回 {@code false}——那不是节点，是「没有源」。 */
    public static boolean isExternal(String ref) {
        return ref != null && ref.startsWith(EXTERNAL_PREFIX);
    }

    /**
     * 解析成三段语义。
     *
     * <p>少于两段（{@code t} 或 {@code a.b} 里只有表名）时返回空：那种 ref 无法定位 catalog，
     * 也就无法判权与查目录，只能当外部节点处理。调用方据此走
     * {@code objectType=EXTERNAL}。
     */
    public static Optional<Parsed> parse(String ref) {
        if (ref == null || ref.isBlank() || isExternal(ref)) {
            return Optional.empty();
        }
        String[] parts = ref.split("\\.", -1);
        if (parts.length < 2) {
            return Optional.empty();
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                // `a..b` 这类拼接错误：宁可当成解析不出来的外部节点，也不要猜一个空 namespace
                return Optional.empty();
            }
        }
        String catalog = parts[0];
        String table = parts[parts.length - 1];
        List<String> namespace = List.of(parts).subList(1, parts.length - 1);
        return Optional.of(new Parsed(catalog, namespace, table));
    }

    /**
     * 拼一个 ref。{@code database} 是控制台路由里那一段，已经包含多级 namespace
     * （{@code BrowseView} 逐级导航，拿到的就是完整路径）。
     */
    public static String of(String catalog, String database, String table) {
        return catalog + "." + database + "." + table;
    }

    /**
     * 表名 / 库名 / catalog 名不得以 {@code ~} 开头（§4.1 的保留位）。
     *
     * <p>没有这道校验，用户建一张叫 {@code ~values} 的表就会与「纯常量源」这个伪节点
     * 撞名：图上两者合成一个点，而查边时又会按外部节点跳过判权。
     */
    public static void requireNotReserved(String name, String what) {
        if (name != null && name.startsWith(EXTERNAL_PREFIX)) {
            throw ApiException.badRequest(
                    "The name of a " + what + " must not start with '" + EXTERNAL_PREFIX
                            + "': that prefix is reserved for nodes outside the catalog");
        }
    }
}
