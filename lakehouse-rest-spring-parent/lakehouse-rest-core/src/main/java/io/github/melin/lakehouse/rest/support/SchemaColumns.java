package io.github.melin.lakehouse.rest.support;

import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 从 {@code lakehouse_table.schema_doc} 里抽出列名、类型与注释。
 *
 * <p><b>为什么需要它：{@code schema_doc} 有三种形状。</b>这个列是「当前 schema 的 JSON
 * 文档」，但写入方按格式各写各的：
 *
 * <ul>
 *   <li><b>Paimon</b>：{@code TypeDtos.Schema} 序列化结果，即
 *       {@code {"fields":[{"id":1,"name":"c1","type":"INT","description":"列注释"}],...}}。
 *       列注释在 {@code description}。</li>
 *   <li><b>Iceberg</b>：{@code SchemaParser.toJson(metadata.schema())} 的原始 Iceberg
 *       schema，字段形如 {@code {"id":1,"name":"c1","required":true,"type":"int","doc":"列注释"}}，
 *       且类型可以是字符串（{@code "int"}）或嵌套对象
 *       （{@code {"type":"struct","fields":[...]}} / {@code {"type":"list","element":...}}）。
 *       列注释在 {@code doc}。</li>
 *   <li><b>Lance</b>：<b>根本不写这一列</b>（{@code protocol-lance} 下没有任何
 *       {@code setSchemaDoc} 调用），因此值为 {@code null}。</li>
 * </ul>
 *
 * <p><b>因此这里的每条分支都是「容错」而不是「解析」。</b>识别引擎要回答的是
 * 「这一列像不像身份证号」，而列名与列注释在三种形状里都在顶层字段上——只要取到
 * {@code name} 与两个注释键之一就够了，不需要理解类型树。认不出的形状返回空列表，
 * 表现为「这张表在 D1/D2 上没命中」，而不是抛异常把整轮扫描打断：
 * <b>一次格式变更不该让一个 catalog 的敏感数据治理停摆</b>。
 *
 * <p><b>不引入格式专属解析器。</b>{@code SchemaParser} 在 iceberg 模块、{@code Codecs}
 * 在 protocol-paimon 模块，core 依赖不到它们（依赖方向是 core ← protocol-*）。
 * 为三种形状各引入一次依赖会让 core 变成「什么格式都认识」的模块，而它现在
 * 只知道 {@code schema_doc} 是一个 JSON 文档。多读一个键、少读一个键，
 * 代价远小于这条依赖。
 */
public final class SchemaColumns {

    /** Iceberg 用 {@code doc} 放列注释，Paimon 用 {@code description}；两个键都试。 */
    private static final List<String> COMMENT_KEYS = List.of("description", "doc");

    private SchemaColumns() {
    }

    /**
     * 一列的三个关键信息。
     *
     * <p>{@code type} 是**归一化后的类型名**（小写、去掉括号里的精度），
     * 例如 {@code varchar(18)} 与 {@code string} 都归一成 {@code string}/
     * {@code varchar}——D2 判的是「像不像 char(18)」，不需要知道括号里的数字以外的东西，
     * 但需要知道括号里是 18（见 {@link Column#length()}）。
     */
    public record Column(String name, String type, String comment) {

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

    /**
     * 解析 {@code schema_doc}。
     *
     * @param schemaDoc 三种形状之一，或 {@code null} / 空串 / 非法 JSON
     * @return 列列表；认不出时返回空列表（**不抛异常**，理由见类注释）
     */
    public static List<Column> parse(String schemaDoc) {
        if (schemaDoc == null || schemaDoc.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = Json.readTree(schemaDoc);
        } catch (RuntimeException e) {
            // 非法 JSON 与「空的 schema_doc」在这里等价：这张表本轮扫不出列，
            // 调用方会把它计进 tables_scanned 而不计进 tables_failed——
            // 它确实读到了，只是里面没有可判的东西。
            return List.of();
        }
        JsonNode fields = root.path("fields");
        if (!fields.isArray()) {
            return List.of();
        }
        List<Column> columns = new ArrayList<>();
        for (JsonNode field : fields) {
            String name = field.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            columns.add(new Column(name, typeOf(field.path("type")), commentOf(field)));
        }
        return columns;
    }

    /**
     * 取字段的类型。
     *
     * <p>两种形态：裸字符串（{@code "INT"} / {@code "int"}）与嵌套对象
     * （Iceberg 的 {@code {"type":"struct","fields":[...]}}、
     * Paimon 的 {@code {"type":"ARRAY","element":...}}）。嵌套形态只取
     * 最外层的 {@code type} 名——D2 关心的是「这是不是个定长字符列」，
     * 数组里的元素类型不是这一层的判据。
     */
    private static String typeOf(JsonNode type) {
        if (type == null || type.isMissingNode() || type.isNull()) {
            return null;
        }
        if (type.isTextual()) {
            return type.asText();
        }
        if (type.isObject()) {
            JsonNode inner = type.path("type");
            return inner.isTextual() ? inner.asText() : null;
        }
        return type.asText();
    }

    /** 列注释：先试 {@code description}（Paimon），再试 {@code doc}（Iceberg）。 */
    private static String commentOf(JsonNode field) {
        for (String key : COMMENT_KEYS) {
            JsonNode value = field.path(key);
            if (value.isTextual() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }
}
