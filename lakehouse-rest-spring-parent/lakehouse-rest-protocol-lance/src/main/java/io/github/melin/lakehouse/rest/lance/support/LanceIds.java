package io.github.melin.lakehouse.rest.lance.support;

import io.github.melin.lakehouse.rest.support.LanceErrors;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Lance 的 {@code id} 解析。
 *
 * <p>{@code id} 是**用分隔符拼起来的完整路径**（{@code my_catalog$sales$orders}），
 * 默认分隔符 {@code $}、在 URL 里写成 {@code %24}。它不是一个表名，也不是
 * {@code /v1/{prefix}} 那种「prefix 一段、其余各段」的形态——这是本面与另两面
 * 最容易混淆的一处，也是 {@code spark.sql.catalog.<c>.parent} 那条配置存在的理由。
 *
 * <p>五条规则，每条都对应一个「不校验就会出事」的场景：
 *
 * <ol>
 *   <li><b>{@code delimiter} 可覆盖</b>（{@code ?delimiter=::}）。客户端能换分隔符，
 *       硬编码 {@code $} 会让配了 {@code ::} 的客户端全部 404；
 *   <li><b>按分隔符切分后不再解码</b>。容器与 Spring 已经把 {@code @PathVariable}
 *       解码过一次，再解一遍会把层级里真实的 {@code %} 变成别的字符；
 *   <li><b>段数按 operation 类别收窄</b>：namespace 类 1–2 段，table 类恰好 3 段
 *       （规范是两级命名空间，表只能挂在 schema 下）；
 *   <li><b>空段 / 末尾分隔符 → 400</b>（{@code a$$b} 是我们解释不了的输入）；
 *   <li><b>段内字符白名单</b>（字母数字与 {@code _ - .}）。理由不是洁癖：{@code id}
 *       会进 URL、进日志、进唯一约束，任何通配字符都会在某处变成注入面。
 * </ol>
 *
 * <p>另外规范还有一条硬要求：**路径里的 {@code id} 与 body 里的 {@code id} 同时出现
 * 且不一致时必须 400**（{@link #requireSameAsBody}）。
 */
public final class LanceIds {

    /** 默认分隔符。客户端可用 {@code ?delimiter=} 换掉它。 */
    public static final String DEFAULT_DELIMITER = "$";

    /** 段内允许的字符：字母数字与 {@code _ - .}。 */
    private static final Pattern LEVEL = Pattern.compile("[A-Za-z0-9_.-]+");

    private LanceIds() {
    }

    /**
     * 解析后的 {@code id}。
     *
     * @param levels 由浅到深的层级：{@code [catalog]}、{@code [catalog, schema]} 或
     *               {@code [catalog, schema, table]}
     */
    public record Id(List<String> levels) {

        public String catalog() {
            return levels.get(0);
        }

        /** schema 名；只有 catalog 一级时为 {@code null}。 */
        public String schema() {
            return levels.size() >= 2 ? levels.get(1) : null;
        }

        /** 表名；没有表级时为 {@code null}。 */
        public String table() {
            return levels.size() >= 3 ? levels.get(2) : null;
        }

        /**
         * 用分隔符拼回去，用于响应里的 {@code id} 与错误文案。
         *
         * <p>分隔符为空（客户端没有传 {@code ?delimiter=}）时按默认分隔符拼——
         * 不能把 {@code null} 交给 {@code String.join}，那会在每个没带参数的请求上 NPE，
         * 而这类请求是绝大多数。
         */
        public String join(String delimiter) {
            return String.join(
                    delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter, levels);
        }

        @Override
        public String toString() {
            return String.join(DEFAULT_DELIMITER, levels);
        }
    }

    /**
     * 解析 namespace 类 operation 的 {@code id}：1–2 段。
     *
     * @param raw       路径里的那一段（容器已解码）
     * @param delimiter 分隔符；为空时用 {@link #DEFAULT_DELIMITER}
     */
    public static Id namespace(String raw, String delimiter) {
        return parse(raw, delimiter, 1, 2, "namespace");
    }

    /** 解析 table 类 operation 的 {@code id}：恰好 3 段。 */
    public static Id table(String raw, String delimiter) {
        return parse(raw, delimiter, 3, 3, "table");
    }

    /**
     * 解析跨命名空间列举的 {@code id}：1–3 段（catalog 必填，后面两段可选）。
     *
     * <p>它与 {@link #table} 的差别是允许更短：{@code GET /lance/v1/table/{id}} 的
     * {@code id} 可以只是 catalog 名。但**不允许为空**——全库扫描既慢又越权，
     * 因此 {@code /lance/v1/table} 这种不带 id 的请求按 400 处理（由控制器拦下）。
     */
    public static Id search(String raw, String delimiter) {
        return parse(raw, delimiter, 1, 3, "table");
    }

    /**
     * 路径与 body 的 {@code id} 一致性校验。
     *
     * <p>规范明文要求：两者都出现且不等时必须 400。这不是挑剔——body 里那个值会被
     * 客户端用作「我操作的是谁」，若服务端按路径里的另一个值执行而回 200，
     * 客户端的本地状态就与实际结果不一致了，且这种不一致只在并发改写时才暴露。
     */
    public static void requireSameAsBody(String pathId, String bodyId, String delimiter) {
        if (bodyId == null || bodyId.isBlank()) {
            return;
        }
        if (!normalize(pathId, delimiter).equals(normalize(bodyId, delimiter))) {
            throw LanceErrors.invalidInput("id in the request body \"" + bodyId
                    + "\" does not match the id in the path \"" + pathId + "\"");
        }
    }

    // ------------------------------------------------------------------ 内部

    private static Id parse(String raw, String delimiter, int minLevels, int maxLevels, String what) {
        if (raw == null || raw.isBlank()) {
            throw LanceErrors.invalidInput(what + " id must not be empty");
        }
        String separator = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
        String[] parts = raw.split(Pattern.quote(separator), -1);
        if (parts.length < minLevels || parts.length > maxLevels) {
            throw LanceErrors.invalidInput(what + " id \"" + raw + "\" must have "
                    + describeLevels(minLevels, maxLevels) + " separated by \"" + separator + "\"");
        }
        for (String part : parts) {
            if (part.isEmpty()) {
                throw LanceErrors.invalidInput(what + " id \"" + raw + "\" has an empty level");
            }
            if (part.contains("/")) {
                // 编码过的斜杠：一段 id 里装两条路径，是路径穿越的入口
                throw LanceErrors.invalidInput(what + " id \"" + raw + "\" must not contain '/'");
            }
            if (!LEVEL.matcher(part).matches()) {
                throw LanceErrors.invalidInput(what + " id level \"" + part
                        + "\" contains characters outside [A-Za-z0-9_.-]");
            }
        }
        return new Id(List.of(parts));
    }

    private static String normalize(String id, String delimiter) {
        String separator = delimiter == null || delimiter.isEmpty() ? DEFAULT_DELIMITER : delimiter;
        return String.join(separator, id.split(Pattern.quote(separator), -1));
    }

    private static String describeLevels(int min, int max) {
        return min == max ? min + " levels" : min + " to " + max + " levels";
    }
}
