package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.scan.ScanColumn;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

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
 *
 * <p><b>它留在 core 而不进 {@code lakehouse-rest-scan-kernel}（P4 的模块边界）。</b>
 * 这里解析的是**服务端库里的一列**（{@code schema_doc}），而集群上的扫描作业见不到它：
 * 服务端解析完、把 {@link ScanColumn} 随执行清单下发。因此内核模块不需要任何 JSON 库——
 * 它要同时能在 Spring Boot（Jackson 3）与 Spark（Jackson 2）两套依赖世界里编译，
 * 一旦引了其中任何一个，另一个世界里就会少类或多一份旧版本。
 */
public final class SchemaColumns {

    /** Iceberg 用 {@code doc} 放列注释，Paimon 用 {@code description}；两个键都试。 */
    private static final List<String> COMMENT_KEYS = List.of("description", "doc");

    private SchemaColumns() {
    }

    /**
     * 解析 {@code schema_doc}。
     *
     * @param schemaDoc 三种形状之一，或 {@code null} / 空串 / 非法 JSON
     * @return 列列表；认不出时返回空列表（**不抛异常**，理由见类注释）
     */
    public static List<ScanColumn> parse(String schemaDoc) {
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
        List<ScanColumn> columns = new ArrayList<>();
        for (JsonNode field : fields) {
            String name = field.path("name").asText("");
            if (name.isEmpty()) {
                continue;
            }
            columns.add(new ScanColumn(name, typeOf(field.path("type")), commentOf(field)));
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
