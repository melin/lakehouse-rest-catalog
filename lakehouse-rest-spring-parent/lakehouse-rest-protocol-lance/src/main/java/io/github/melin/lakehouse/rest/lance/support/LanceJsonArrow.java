package io.github.melin.lakehouse.rest.lance.support;

import io.github.melin.lakehouse.rest.lance.support.LanceManifest.Field;
import io.github.melin.lakehouse.rest.lance.support.LanceManifest.Manifest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 把 manifest 里的 {@code fields} 翻成规范要的 **JSON Arrow**（{@code DescribeTableResponse.schema}）。
 *
 * <p><b>这是「尽力而为」的一步，边界要写清。</b>Arrow 的 JSON schema 与 Lance 的
 * {@code logical_type} 不是同一套词表：前者是 Arrow 的类型名（{@code int} /
 * {@code utf8} / {@code struct}），后者是 Lance 自己的串（{@code int32} /
 * {@code string} / {@code struct} / {@code decimal:128:38:10}）。映射表覆盖
 * {@code format.proto} 里注释列出的全部类型；**认不出的类型原样透传**——
 * 客户端看到一个不认识的 {@code type} 至少知道「服务端没认出它」，
 * 而我们猜一个类型会让它按错误的布局去读数据。
 *
 * <p><b>嵌套靠 {@code parent_id} 还原。</b>manifest 的 {@code fields} 是深度优先的
 * 扁平列表，父子关系在 {@code parent_id} 里（顶层列的写法有两套：老的是 {@code 0}、
 * 4.x 起是 {@code -1}，见 {@link #parentIdIsRoot}）。{@code PARENT} 是 struct、
 * {@code REPEATED} 是 list，二者的子字段就是它的成员 / 元素。
 */
public final class LanceJsonArrow {

    private LanceJsonArrow() {
    }

    /** 顶层只有一个 {@code fields} 键；schema 级 metadata 我们不从 manifest 里取（它是 bytes map）。 */
    public static Map<String, Object> schema(Manifest manifest) {
        Map<Integer, List<Field>> children = new LinkedHashMap<>();
        List<Field> roots = new ArrayList<>();
        Set<Integer> ids = new HashSet<>();
        for (Field field : manifest.fields()) {
            ids.add(field.id());
        }
        for (Field field : manifest.fields()) {
            // 顶层的判定有**两套约定**，都要认（见 `parentIdIsRoot`）。
            if (parentIdIsRoot(field, ids)) {
                roots.add(field);
            } else {
                children.computeIfAbsent(field.parentId(), key -> new ArrayList<>()).add(field);
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        List<Object> jsonFields = new ArrayList<>();
        for (Field root : roots) {
            jsonFields.add(jsonField(root, children));
        }
        schema.put("fields", jsonFields);
        return schema;
    }

    /**
     * 这个字段是不是顶层列。
     *
     * <p>两套约定并存，原因还是 Lance 换了主子：老版本用 {@code parent_id = 0} 表示顶层
     * （字段 id 从 1 开始，0 空着正好当哨兵）；4.x 起字段 id 从 0 开始，
     * 0 不再是空位，于是换成 {@code parent_id = -1}。
     *
     * <p>只认 0 或只认 -1 都会出事：只认 0 时 4.x 的字段全被挂到 id 为 0 的列下面，
     * 顶层变成空表；只认 -1 时老 manifest 的每一列都成了别人的子节点，同样空表。
     * 因此按「这个 parent_id 在不在已知 id 里」判断——**没有哪个字段是这个 id，
     * 就说明它不是父引用**。这条判据对两套约定都成立，也顺带容忍了父节点被删过的坏数据。
     */
    private static boolean parentIdIsRoot(Field field, Set<Integer> ids) {
        return field.parentId() <= 0 || !ids.contains(field.parentId());
    }

    private static Map<String, Object> jsonField(Field field, Map<Integer, List<Field>> children) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", field.name());
        json.put("nullable", field.nullable());
        json.put("type", jsonType(field, children));
        return json;
    }

    private static Map<String, Object> jsonType(Field field, Map<Integer, List<Field>> children) {
        if (field.parent()) {
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("type", "struct");
            List<Object> nested = new ArrayList<>();
            for (Field child : children.getOrDefault(field.id(), List.of())) {
                nested.add(jsonField(child, children));
            }
            type.put("fields", nested);
            return type;
        }
        if (field.repeated()) {
            Map<String, Object> type = new LinkedHashMap<>();
            type.put("type", "list");
            List<Object> nested = new ArrayList<>();
            List<Field> elements = children.getOrDefault(field.id(), List.of());
            // Arrow 的 JSON 里列表元素约定叫 item；Lance 那边父子同名（"b.c" 下面还是 "b.c"），
            // 照搬会让客户端看到两个同名层。只取第一个子元素：list 的形状决定了它只有一个。
            if (!elements.isEmpty()) {
                Map<String, Object> element = new LinkedHashMap<>();
                element.put("name", "item");
                element.put("nullable", elements.get(0).nullable());
                element.put("type", jsonType(elements.get(0), children));
                nested.add(element);
            }
            type.put("fields", nested);
            return type;
        }
        return dataType(field.logicalType());
    }

    /**
     * {@code logical_type} → Arrow 的 {@code DataType}。
     *
     * <p>参数化类型（{@code decimal:128:38:10}、{@code timestamp:us}、
     * {@code dict:...}）按冒号切：Lance 把它们写成一个串，而 Arrow 要的是
     * 分开的字段。切不开就整串透传。
     */
    private static Map<String, Object> dataType(String logicalType) {
        Map<String, Object> type = new LinkedHashMap<>();
        String logical = logicalType == null ? "" : logicalType.trim();
        String[] parts = logical.split(":", -1);
        switch (parts[0].toLowerCase(Locale.ROOT)) {
            case "null" -> type.put("type", "null");
            case "bool" -> type.put("type", "bool");
            case "int8" -> integer(type, 8, true);
            case "uint8" -> integer(type, 8, false);
            case "int16" -> integer(type, 16, true);
            case "uint16" -> integer(type, 16, false);
            case "int32" -> integer(type, 32, true);
            case "uint32" -> integer(type, 32, false);
            case "int64" -> integer(type, 64, true);
            case "uint64" -> integer(type, 64, false);
            case "halffloat" -> floating(type, "HALF");
            case "float" -> floating(type, "SINGLE");
            case "double" -> floating(type, "DOUBLE");
            case "string", "large_string" -> type.put("type", "utf8");
            case "binary", "large_binary" -> type.put("type", "binary");
            case "date32" -> date(type, "DAY");
            case "date64" -> date(type, "MILLISECOND");
            case "time" -> unit(type, "time", parts, "MICROSECOND");
            case "timestamp" -> {
                unit(type, "timestamp", parts, "MICROSECOND");
                type.put("timezone", null);
            }
            case "duration" -> unit(type, "duration", parts, "MILLISECOND");
            case "decimal" -> decimal(type, parts);
            case "dict" -> {
                // Lance 的 dict:{值类型}:{索引类型}:{sorted}；值类型递归一次，
                // 索引类型丢掉——JSON Arrow 的 dictionary 形状在客户端之间不统一，
                // 而值类型是客户端真正要用去读数据的那个。
                type.put("type", "dictionary");
                type.put("valueType", dataType(parts.length > 1 ? parts[1] : ""));
            }
            // 认不出：整串透传。宁可让客户端看到 "unknown-thing"，也不要猜。
            default -> type.put("type", logical.isEmpty() ? "null" : logical);
        }
        return type;
    }

    private static void integer(Map<String, Object> type, int bitWidth, boolean signed) {
        type.put("type", "int");
        type.put("bitWidth", bitWidth);
        type.put("isSigned", signed);
    }

    private static void floating(Map<String, Object> type, String precision) {
        type.put("type", "floatingpoint");
        type.put("precision", precision);
    }

    private static void date(Map<String, Object> type, String unit) {
        type.put("type", "date");
        type.put("unit", unit);
    }

    /** {@code timestamp:us} / {@code time:ns} 之类：单位写成 Arrow 的大写词。 */
    private static void unit(Map<String, Object> type, String name, String[] parts, String fallback) {
        type.put("type", name);
        String unit = parts.length > 1 ? parts[1].toLowerCase(Locale.ROOT) : "";
        type.put("unit", switch (unit) {
            case "s" -> "SECOND";
            case "ms" -> "MILLISECOND";
            case "us" -> "MICROSECOND";
            case "ns" -> "NANOSECOND";
            default -> fallback;
        });
    }

    /** {@code decimal:128:38:10} → {@code {bitWidth, precision, scale}}。 */
    private static void decimal(Map<String, Object> type, String[] parts) {
        type.put("type", "decimal");
        type.put("bitWidth", parts.length > 1 ? parseOr(parts[1], 128) : 128);
        type.put("precision", parts.length > 2 ? parseOr(parts[2], 38) : 38);
        type.put("scale", parts.length > 3 ? parseOr(parts[3], 0) : 0);
    }

    private static int parseOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
