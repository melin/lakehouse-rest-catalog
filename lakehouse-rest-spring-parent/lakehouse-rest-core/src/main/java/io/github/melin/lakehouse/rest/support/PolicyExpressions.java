package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.dto.TypeDtos;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 策略表达式的白名单模型：解析、校验、类型推断、SQL 渲染与 {@code $principal} 折叠
 * （设计见 {@code docs/row-filter-column-mask-design.md} §5.3，本类是它在服务端的唯一实现）。
 *
 * <p><b>存 AST，不存 SQL 文本</b>（§5.3.1）。策略里落库的是结构化表达式（JSON），
 * SQL 文本只是给人看的展示形态（{@code SHOW POLICY}、控制台），语义以 AST 为准。
 * AST 在结构上不存在「子查询」「UDF 调用」「聚合」这些形态——注入面不是被检查挡住的，
 * 是被表示法排除的。
 *
 * <p><b>校验在建策略时 fail-closed</b>（§4.2 同一哲学的行过滤版）：引用不存在的列、
 * 嵌套字段、白名单外的函数、类型对不上的 mask，全部在 PUT 时拒绝。查询侧的
 * fail-closed（引擎拉不到策略就读失败）兜的是「校验之后表又变了」的情况，
 * 两道闸各管一段。
 *
 * <p><b>类型规则</b>（§5.3.3）：mask 结果类型必须等于原列类型（不接受「可 cast」，
 * 否则下游 DataFrame 的 schema 变了、case class 反序列化直接炸）；行过滤结果必须是
 * Boolean 且不为 NULL——解析后统一包一层 {@code coalesce(expr, false)}
 * （幂等：已包过的不重复包），否则「被过滤」与「条件写错了」在结果里分不开。
 *
 * <p>输入是反序列化后的普通 {@code Map / List / String / Number / Boolean}（请求体
 * 原样透传进来），输出是归一化后的 AST Map（键序固定，重存前后字节一致）。
 */
public final class PolicyExpressions {

    private PolicyExpressions() {
    }

    // ------------------------------------------------------------------ 词表

    /** 二元算子（比较 + 逻辑 + like）。键是 AST 里的 {@code op} 值。 */
    private static final Set<String> BINARY_OPS =
            Set.of("eq", "neq", "lt", "le", "gt", "gte", "and", "or", "like");

    /** 标准脱敏函数集（§5.3.2.1，名字取自 SPIP 的 {@code ColumnMasks}，不自己发明）。 */
    public static final Set<String> STANDARD_MASK_FNS = Set.of(
            "mask_alphanum", "show_first_n", "show_last_n", "replace_with_null",
            "mask_to_fixed_value", "truncate_to_year", "truncate_to_month", "hash");

    /** 表达式白名单里的标量函数（§5.3.2；全部是 Spark 内置确定性函数，3.5 / 4.1 都有）。 */
    private static final Set<String> EXPRESSION_FNS = Set.of(
            "substr", "concat", "upper", "lower", "trim", "regexp_replace",
            "date_trunc", "year", "month", "to_date", "date_format",
            "md5", "sha2", "coalesce", "case_when", "cast");

    /** hash 标准集允许的算法（算法必须写死成字面量，不许读集群配置——§3.4 第 4 条）。 */
    public static final Set<String> HASH_ALGORITHMS = Set.of("MD5", "SHA-256", "SHA-512");

    /** 表达式树的最大深度。策略表达式是治理员写的短句，32 层远超合理用法，只挡恶意深嵌套。 */
    private static final int MAX_DEPTH = 32;

    // ------------------------------------------------------------------ 类型类别

    /**
     * 列类型的粗分类。mask 的类型规则只到这个粒度（{@code string == string}），
     * 不比较 {@code varchar(32)} 与 {@code string} 的差别——那是「同类」，类型不变。
     */
    public static final String STRING = "STRING";
    public static final String INTEGER = "INTEGER";
    public static final String DECIMAL = "DECIMAL";
    public static final String FLOAT = "FLOAT";
    public static final String BOOLEAN = "BOOLEAN";
    public static final String DATE = "DATE";
    public static final String TIME = "TIME";
    public static final String TIMESTAMP = "TIMESTAMP";
    public static final String NULL_TYPE = "NULL";
    /** struct / map / array：不允许出现在任何策略表达式里（§5.7）。 */
    public static final String COMPLEX = "COMPLEX";

    // ------------------------------------------------------------------ 公开入口

    /**
     * 表 schema 的只读视图：列名集合 + 列名到粗分类的映射 + 非空列集合。
     * 由 {@code TypeDtos.Schema} 的 JSON 文档构造——类型在文档里是裸字符串
     * （{@code "INT"}）或类型 Map（{@code {"type":"DECIMAL",...}}），两种形态都收进
     * 同一个分类；nullability 从类型串里的 {@code NOT NULL} 后缀解析（schema 文档的
     * 写法是 {@code "BIGINT NOT NULL"}），「置空 mask 落非空列要显式确认」的校验靠它。
     */
    public record SchemaView(Set<String> columns, Map<String, String> types,
                             Set<String> requiredColumns) {

        public static SchemaView of(TypeDtos.Schema schema) {
            Set<String> columns = new LinkedHashSet<>();
            Map<String, String> types = new LinkedHashMap<>();
            Set<String> required = new LinkedHashSet<>();
            if (schema != null && schema.getFields() != null) {
                for (TypeDtos.DataField field : schema.getFields()) {
                    if (field == null || field.getName() == null) {
                        continue;
                    }
                    columns.add(field.getName());
                    types.put(field.getName(), categoryOf(field.getType()));
                    if (field.getType() != null
                            && String.valueOf(field.getType()).toUpperCase(Locale.ROOT)
                                    .contains("NOT NULL")) {
                        required.add(field.getName());
                    }
                }
            }
            return new SchemaView(columns, types, required);
        }
    }

    /** 行过滤的解析产物：归一化 AST（已包 coalesce）+ 展示用 SQL。 */
    public record ValidatedRowFilter(Map<String, Object> ast, String sql) {
    }

    /** mask 的解析产物：归一化 AST + 展示用 SQL + 推断出的结果类型分类。 */
    public record ValidatedMask(Map<String, Object> ast, String sql, String resultCategory) {
    }

    /**
     * 解析并校验行过滤表达式。
     *
     * @param json   请求体里的表达式节点（Map/List/String/Number/Boolean）
     * @param schema 表 schema 视图；列存在性与类型规则对它校验
     * @return 归一化 AST（外层已保证非 NULL，见 {@link #wrapCoalesce}）
     */
    public static ValidatedRowFilter parseRowFilter(Object json, SchemaView schema) {
        if (json == null) {
            throw ApiException.badRequest("rowFilter must not be empty");
        }
        Object normalized = validate(json, schema, 0, true);
        String category = inferType(normalized, schema);
        if (!BOOLEAN.equals(category)) {
            throw ApiException.badRequest("rowFilter must be a boolean expression, but it is "
                    + category + "; wrap comparisons or use is_null / is_not_null");
        }
        Map<String, Object> wrapped = wrapCoalesce(asMap(normalized));
        return new ValidatedRowFilter(wrapped, toSql(wrapped));
    }

    /**
     * 解析并校验一列的 mask（标准集或逃生口表达式，§5.3.2.1）。
     *
     * @param json       mask 节点：{@code {"fn": ..., "args": [...]}} 或 {@code {"expr": ...}}
     * @param column     目标列；mask 的结果类型必须与它同类（§5.3.3）
     * @param schema     表 schema 视图
     * @param allowEmpty {@code false} 时 mask 不能为空（PUT 里删 mask 用显式的 mask 列表，不走这个入口）
     */
    public static ValidatedMask parseMask(Object json, String column, SchemaView schema) {
        if (!(json instanceof Map<?, ?> mask)) {
            throw ApiException.badRequest("mask must be an object with \"fn\" or \"expr\"");
        }
        String columnCategory = requireColumnCategory(column, schema);
        if (COMPLEX.equals(columnCategory)) {
            throw ApiException.badRequest("column " + column + " is a struct/map/array type;"
                    + " nested fields are not supported by data policies (design §5.7)");
        }
        if (mask.containsKey("fn") && mask.containsKey("expr")) {
            throw ApiException.badRequest("mask must use either \"fn\" (standard set) or \"expr\""
                    + " (escape hatch), not both");
        }
        if (mask.containsKey("fn")) {
            return parseStandardMask(asMap(mask), column, columnCategory);
        }
        if (mask.containsKey("expr")) {
            Object normalized = validate(mask.get("expr"), schema, 0, false);
            String category = inferType(normalized, schema);
            if (NULL_TYPE.equals(category) || category == null) {
                throw ApiException.badRequest("cannot infer the result type of the mask for column "
                        + column + "; add an explicit cast so the result type equals the column type");
            }
            requireSameCategory(category, columnCategory, column);
            return new ValidatedMask(asMap(normalized), toSql(normalized), category);
        }
        throw ApiException.badRequest("mask must contain either \"fn\" or \"expr\"");
    }

    // ------------------------------------------------------------------ 节点校验（递归）

    /**
     * 递归校验：白名单外的形态直接拒绝并指认形状（§13.4 的服务端镜像——
     * 报错含节点形状，不静默丢弃、不降级）。返回<b>归一化</b>后的节点
     * （Map 键序固定、子节点递归归一化），保证同一表达式两次保存字节一致。
     *
     * @param inRowFilter 当前是否处于行过滤语境（{@code $principal} 只允许出现在行过滤里：
     *                    脱敏是「这列看起来像什么」，与「谁在读」无关，语义上不该出现主体）
     */
    private static Object validate(Object node, SchemaView schema, int depth, boolean inRowFilter) {
        return validate(node, schema, depth, inRowFilter, false);
    }

    private static Object validate(Object node, SchemaView schema, int depth, boolean inRowFilter,
                                   boolean allowPrincipalRoles) {
        if (depth > MAX_DEPTH) {
            throw ApiException.badRequest("expression is nested deeper than " + MAX_DEPTH + " levels");
        }
        if (node instanceof String || node instanceof Boolean) {
            return node;
        }
        if (node instanceof Number) {
            // 数值统一收成 Double/Long 的 JSON 原样：这里只透传，类型推断时再分类
            return node;
        }
        if (node == null) {
            // JSON null（作为字面量出现）：归一成 lit 节点，类型推断按 NULL 处理
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lit", null);
            return out;
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw ApiException.badRequest("unsupported expression node: " + node.getClass().getSimpleName()
                    + " (" + node + ") — use {col} / {lit} / {op} / {fn} shapes");
        }
        if (map.containsKey("col")) {
            String name = String.valueOf(map.get("col"));
            if (name.indexOf('.') >= 0) {
                throw ApiException.badRequest("nested field reference is not supported: " + name
                        + " (design §5.7)");
            }
            if (!schema.columns().contains(name)) {
                throw ApiException.badRequest("column does not exist in the table schema: " + name);
            }
            return Map.of("col", name);
        }
        if (map.containsKey("lit")) {
            Object value = map.get("lit");
            if (value instanceof Map || value instanceof Collection || value instanceof Object[]) {
                throw ApiException.badRequest("literal must be a scalar (string / number / boolean / null)");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("lit", value);
            return out;
        }
        if (map.containsKey("principal")) {
            if (!inRowFilter) {
                throw ApiException.badRequest("$principal is only allowed in row filters, not in masks");
            }
            if (!Boolean.TRUE.equals(map.get("principal"))) {
                throw ApiException.badRequest("$principal must be written as {\"principal\": true}");
            }
            // 下发前会被服务端折成字面量（§5.3.4）；AST 里只存占位符，不存任何人的名字
            return Map.of("principal", true);
        }
        if (map.containsKey("principal_roles")) {
            if (!inRowFilter) {
                throw ApiException.badRequest(
                        "$principal_roles is only allowed in row filters, not in masks");
            }
            if (!allowPrincipalRoles) {
                // 只认 in 的 values 位：别的位置（eq 右侧、mask 参数……）语义要么含糊
                // 要么永远不成立，与其靠 fold 期硬失败，不如在校验期就指名拒绝
                throw ApiException.badRequest(
                        "$principal_roles is only allowed as the values of an in operator");
            }
            if (!Boolean.TRUE.equals(map.get("principal_roles"))) {
                throw ApiException.badRequest(
                        "$principal_roles must be written as {\"principal_roles\": true}");
            }
            // 下发前会被服务端折成调用者持有的 principal role 名单（§5.3.4.1）；
            // AST 里只存占位符，不存任何角色名
            return Map.of("principal_roles", true);
        }
        if (map.containsKey("op")) {
            return validateOp(map, schema, depth, inRowFilter);
        }
        if (map.containsKey("fn")) {
            return validateFn(map, schema, depth, inRowFilter);
        }
        throw ApiException.badRequest("unsupported expression shape: expected one of"
                + " \"col\" / \"lit\" / \"principal\" / \"op\" / \"fn\", got keys " + map.keySet());
    }

    private static Object validateOp(Map<?, ?> map, SchemaView schema, int depth, boolean inRowFilter) {
        Object opValue = map.get("op");
        String op = String.valueOf(opValue);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("op", op);
        switch (op) {
            case "eq", "neq", "lt", "le", "gt", "gte", "and", "or", "like" -> {
                requireKeys(map, List.of("left", "right"), "op " + op);
                out.put("left", validate(map.get("left"), schema, depth + 1, inRowFilter));
                out.put("right", validate(map.get("right"), schema, depth + 1, inRowFilter));
                if ("like".equals(op)) {
                    // 模式里若出现列引用，「像什么」就随数据漂了；LIKE 的模式一律字面量
                    if (!(asMap(out.get("right")).containsKey("lit"))) {
                        throw ApiException.badRequest("like pattern must be a string literal");
                    }
                }
            }
            case "not", "is_null", "is_not_null" -> {
                requireKeys(map, List.of("operand"), "op " + op);
                out.put("operand", validate(map.get("operand"), schema, depth + 1, inRowFilter));
            }
            case "in" -> {
                requireKeys(map, List.of("operand", "values"), "op in");
                out.put("operand", validate(map.get("operand"), schema, depth + 1, inRowFilter));
                if (map.get("values") instanceof Map<?, ?> rolesMap
                        && rolesMap.containsKey("principal_roles")) {
                    // $principal_roles 作为 in 的取值集合（§5.3.4.1）：下发时折成
                    // 调用者的角色名列表；名单为空时整条 in 折成 false（见 fold）
                    out.put("values", validate(rolesMap, schema, depth + 1, inRowFilter, true));
                } else if (!(map.get("values") instanceof Collection<?> values) || values.isEmpty()) {
                    throw ApiException.badRequest(
                            "in requires a non-empty literal list or $principal_roles");
                } else {
                    List<Object> literals = new ArrayList<>();
                    for (Object value : values) {
                        Object normalized = validate(value, schema, depth + 1, inRowFilter);
                        if (!(asMap(normalized).containsKey("lit"))) {
                            throw ApiException.badRequest("in list must contain literals only");
                        }
                        literals.add(normalized);
                    }
                    out.put("values", literals);
                }
            }
            default -> throw ApiException.badRequest("unsupported op: " + op
                    + " — allowed: " + String.join(", ", BINARY_OPS.stream().sorted().toList())
                    + ", not, is_null, is_not_null, in");
        }
        // 禁掉请求里夹带的未知键：多出来的键要么是笔误、要么是给未来实现埋的语义
        requireNoExtraKeys(map, knownOpKeys(op), "op " + op);
        return out;
    }

    private static Object validateFn(Map<?, ?> map, SchemaView schema, int depth, boolean inRowFilter) {
        String fn = String.valueOf(map.get("fn"));
        if (!EXPRESSION_FNS.contains(fn)) {
            throw ApiException.badRequest("unsupported function: " + fn
                    + " — only deterministic builtins are whitelisted (no aggregates, windows,"
                    + " subqueries, UDFs or session-dependent functions)");
        }
        requireNoExtraKeys(map, knownFnKeys(fn), "fn " + fn);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fn", fn);
        switch (fn) {
            case "substr" -> {
                List<Object> args = literalTailArgs(map, schema, depth, inRowFilter, 2, 3, fn);
                requireIntLiteral(args.get(1), fn + " start offset");
                if (args.size() > 2) {
                    requireIntLiteral(args.get(2), fn + " length");
                }
                out.put("args", args);
            }
            case "concat" -> {
                List<Object> args = varArgs(map, "args", fn);
                if (args.size() < 2) {
                    throw ApiException.badRequest("concat requires at least two arguments");
                }
                List<Object> normalized = new ArrayList<>();
                for (Object arg : args) {
                    normalized.add(validate(arg, schema, depth + 1, inRowFilter));
                }
                out.put("args", normalized);
            }
            case "upper", "lower", "trim", "md5", "year", "month" -> {
                List<Object> args = literalTailArgs(map, schema, depth, inRowFilter, 1, 1, fn);
                out.put("args", args);
            }
            case "regexp_replace" -> {
                // 模式与替换串一律字面量：正则贪婪性是两侧实现最容易漂移的点（§7.5），
                // 锁成常量后至少表达式本身是确定的
                List<Object> args = literalTailArgs(map, schema, depth, inRowFilter, 3, 3, fn);
                requireStringLiteral(args.get(1), fn + " pattern");
                requireStringLiteral(args.get(2), fn + " replacement");
                out.put("args", args);
            }
            case "date_trunc" -> {
                // Spark 的签名是 date_trunc(format, timestamp)：单位在前，且必须是字面量
                List<Object> args = literalTailArgs(map, schema, depth, inRowFilter, 2, 2, fn);
                String unit = literalString(args.get(0), fn + " unit");
                requireDateTruncUnit(unit);
                out.put("args", args);
            }
            case "to_date" -> {
                List<Object> args = literalTailArgs(map, schema, depth, inRowFilter, 1, 2, fn);
                if (args.size() > 1) {
                    requireStringLiteral(args.get(1), fn + " format");
                }
                out.put("args", args);
            }
            case "date_format" -> {
                List<Object> args = literalTailArgs(map, schema, depth, inRowFilter, 2, 2, fn);
                requireStringLiteral(args.get(1), fn + " pattern");
                out.put("args", args);
            }
            case "sha2" -> {
                List<Object> args = literalTailArgs(map, schema, depth, inRowFilter, 2, 2, fn);
                Object bits = asMap(args.get(1)).get("lit");
                if (!(bits instanceof Number number) || !Set.of(224, 256, 384, 512)
                        .contains(number.intValue())) {
                    throw ApiException.badRequest("sha2 digest length must be one of 224/256/384/512");
                }
                out.put("args", args);
            }
            case "coalesce" -> {
                List<Object> args = varArgs(map, "args", fn);
                if (args.size() < 2) {
                    throw ApiException.badRequest("coalesce requires at least two arguments");
                }
                List<Object> normalized = new ArrayList<>();
                for (Object arg : args) {
                    normalized.add(validate(arg, schema, depth + 1, inRowFilter));
                }
                out.put("args", normalized);
            }
            case "case_when" -> {
                requireKeys(map, List.of("branches"), "fn case_when");
                if (!(map.get("branches") instanceof Collection<?> branches) || branches.isEmpty()) {
                    throw ApiException.badRequest("case_when requires at least one when/then branch");
                }
                List<Object> normalizedBranches = new ArrayList<>();
                for (Object branchObject : branches) {
                    if (!(branchObject instanceof Map<?, ?> branch)
                            || !branch.containsKey("when") || !branch.containsKey("then")) {
                        throw ApiException.badRequest("case_when branches must be"
                                + " {\"when\": condition, \"then\": value} objects");
                    }
                    Map<String, Object> normalizedBranch = new LinkedHashMap<>();
                    normalizedBranch.put("when",
                            validate(branch.get("when"), schema, depth + 1, inRowFilter));
                    normalizedBranch.put("then",
                            validate(branch.get("then"), schema, depth + 1, inRowFilter));
                    normalizedBranches.add(normalizedBranch);
                }
                out.put("branches", normalizedBranches);
                if (map.containsKey("else")) {
                    out.put("else", validate(map.get("else"), schema, depth + 1, inRowFilter));
                }
            }
            case "cast" -> {
                requireKeys(map, List.of("operand", "to"), "fn cast");
                out.put("operand", validate(map.get("operand"), schema, depth + 1, inRowFilter));
                String to = literalType(map.get("to"));
                requireWidening(inferType(out.get("operand"), schema), to);
                out.put("to", to);
            }
            default -> throw ApiException.badRequest("unsupported function: " + fn);
        }
        return out;
    }

    // ------------------------------------------------------------------ 标准脱敏集

    private static ValidatedMask parseStandardMask(Map<String, Object> mask, String column,
                                                   String columnCategory) {
        String fn = String.valueOf(mask.get("fn"));
        if (!STANDARD_MASK_FNS.contains(fn)) {
            throw ApiException.badRequest("unknown standard mask: " + fn
                    + " — allowed: " + String.join(", ", STANDARD_MASK_FNS.stream().sorted().toList())
                    + "; arbitrary expressions go through {\"expr\": ...}");
        }
        List<Object> rawArgs = mask.containsKey("args")
                ? varArgs(mask, "args", fn) : List.of();
        for (Object arg : rawArgs) {
            // 标准集的参数一律字面量（控制台是「下拉 + 一个数字」的形状，§5.3.2.1）
            if (!(arg == null || arg instanceof String || arg instanceof Number || arg instanceof Boolean)) {
                throw ApiException.badRequest("standard mask arguments must be literals");
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fn", fn);
        switch (fn) {
            case "mask_alphanum", "replace_with_null", "truncate_to_year", "truncate_to_month" -> {
                if (!rawArgs.isEmpty()) {
                    throw ApiException.badRequest("mask " + fn + " takes no arguments");
                }
                if ("truncate_to_year".equals(fn) || "truncate_to_month".equals(fn)) {
                    requireTemporal(columnCategory, column, fn);
                }
                if ("mask_alphanum".equals(fn)) {
                    // X/x/n 是字符类替换，对数字 / 时间列没有意义；引擎的 StringTranslate
                    // 也要求字符串输入（结果类型必须等于列类型，§5.3.3）——建策略时就拒
                    requireStringColumn(columnCategory, column, fn);
                }
            }
            case "show_first_n", "show_last_n" -> {
                if (rawArgs.size() != 1 || !(rawArgs.get(0) instanceof Number number)
                        || number.intValue() < 0) {
                    throw ApiException.badRequest("mask " + fn + " takes one non-negative integer");
                }
                requireStringColumn(columnCategory, column, fn);
                out.put("args", List.of(rawArgs.get(0)));
            }
            case "mask_to_fixed_value" -> {
                if (rawArgs.size() != 1 || !(rawArgs.get(0) instanceof String value) || value.isBlank()) {
                    throw ApiException.badRequest("mask " + fn + " takes one non-empty string");
                }
                requireStringColumn(columnCategory, column, fn);
                out.put("args", List.of(rawArgs.get(0)));
            }
            case "hash" -> {
                if (rawArgs.size() != 1 || !(rawArgs.get(0) instanceof String algorithm)
                        || !HASH_ALGORITHMS.contains(algorithm.toUpperCase(Locale.ROOT))) {
                    throw ApiException.badRequest("mask hash takes one algorithm from "
                            + HASH_ALGORITHMS + " — the algorithm must be written down, not read"
                            + " from cluster config (design §3.4)");
                }
                requireStringColumn(columnCategory, column, fn);
                out.put("args", List.of(algorithm.toUpperCase(Locale.ROOT)));
            }
            default -> throw ApiException.badRequest("unknown standard mask: " + fn);
        }
        return new ValidatedMask(out, toSql(out), columnCategory);
    }

    // ------------------------------------------------------------------ 类型推断

    /**
     * 推断表达式的结果类型分类。返回 {@code null} 表示推不出来（只发生在没有
     * else 分支且全部分支都是 NULL 字面量的 case_when 上）——调用方据此拒绝。
     */
    private static String inferType(Object node, SchemaView schema) {
        if (node instanceof String || node instanceof Boolean) {
            return node instanceof Boolean ? BOOLEAN : STRING;
        }
        if (node instanceof Number number) {
            return number instanceof Long || number instanceof Integer
                    ? INTEGER : DECIMAL;
        }
        if (node == null) {
            return NULL_TYPE;
        }
        if (!(node instanceof Map<?, ?> map)) {
            return null;
        }
        if (map.containsKey("col")) {
            return schema.types().get(String.valueOf(map.get("col")));
        }
        if (map.containsKey("lit")) {
            Object value = map.get("lit");
            if (value == null) {
                return NULL_TYPE;
            }
            if (value instanceof Boolean) {
                return BOOLEAN;
            }
            if (value instanceof Number number) {
                return number instanceof Long || number instanceof Integer
                        ? INTEGER : DECIMAL;
            }
            return STRING;
        }
        if (map.containsKey("principal")) {
            return STRING;
        }
        if (map.containsKey("op")) {
            return switch (String.valueOf(map.get("op"))) {
                case "eq", "neq", "lt", "le", "gt", "gte", "and", "or", "not",
                     "in", "like", "is_null", "is_not_null" -> BOOLEAN;
                default -> null;
            };
        }
        if (map.containsKey("fn")) {
            return inferFnType(asMap(map), schema);
        }
        return null;
    }

    private static String inferFnType(Map<String, Object> map, SchemaView schema) {
        return switch (String.valueOf(map.get("fn"))) {
            case "substr", "concat", "upper", "lower", "trim", "regexp_replace",
                 "md5", "sha2", "date_format" -> STRING;
            case "year", "month" -> INTEGER;
            case "to_date" -> DATE;
            case "date_trunc" -> inferType(((List<?>) map.get("args")).get(1), schema);
            case "cast" -> String.valueOf(map.get("to"));
            case "coalesce" -> {
                String found = null;
                for (Object arg : (List<?>) map.get("args")) {
                    String category = inferType(arg, schema);
                    if (category != null && !NULL_TYPE.equals(category)) {
                        found = category;
                        break;
                    }
                }
                yield found;
            }
            case "case_when" -> {
                String found = null;
                for (Object branchObject : (List<?>) map.get("branches")) {
                    String category = inferType(asMap(branchObject).get("then"), schema);
                    if (category != null && !NULL_TYPE.equals(category)) {
                        found = category;
                        break;
                    }
                }
                if (found == null && map.containsKey("else")) {
                    found = inferType(map.get("else"), schema);
                }
                yield found;
            }
            default -> null;
        };
    }

    // ------------------------------------------------------------------ $principal 折叠与渲染

    /**
     * 把 {@code {"principal": true}} 折叠成调用者主体名的字符串字面量（§5.3.4）。
     * 在下发（resolve）时执行：客户端拿到的是字面量，改不了「我是谁」；
     * 服务端预览用同一份规则，双端语义天然一致。
     */
    public static Map<String, Object> foldPrincipal(Map<String, Object> ast, String principal,
                                                    List<String> roles) {
        if (ast == null) {
            return null;
        }
        return fold(ast, principal, roles == null ? List.of() : roles);
    }

    private static Map<String, Object> fold(Map<String, Object> node, String principal,
                                            List<String> roles) {
        // $principal_roles 只允许出现在 in 的 values 位（parse 已保证）。角色名单为空
        // 折成 lit false——「不属于任何角色 → 一行都看不到」，fail-closed 的方向不会错
        if ("in".equals(node.get("op"))
                && node.get("values") instanceof Map<?, ?> values
                && values.containsKey("principal_roles")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("op", "in");
            out.put("operand", fold(asMap(node.get("operand")), principal, roles));
            if (roles.isEmpty()) {
                return Map.of("lit", Boolean.FALSE);
            }
            List<Object> folded = new ArrayList<>();
            for (String role : roles) {
                folded.add(Map.of("lit", role));
            }
            out.put("values", folded);
            return out;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if ("principal".equals(key)) {
                out.put("lit", principal == null ? "anonymous" : principal);
            } else if ("principal_roles".equals(key)) {
                throw new IllegalStateException("$principal_roles may only appear as the values"
                        + " of an in operator (design §5.3.4.1)");
            } else if (value instanceof Map<?, ?> child) {
                out.put(key, fold(asMap(child), principal, roles));
            } else if (value instanceof Collection<?> children) {
                List<Object> folded = new ArrayList<>();
                for (Object child : children) {
                    folded.add(child instanceof Map<?, ?> map
                            ? fold(asMap(map), principal, roles) : child);
                }
                out.put(key, folded);
            } else {
                out.put(key, value);
            }
        }
        return out;
    }

    /** AST → 展示用 SQL。{@code $principal} 渲染成 {@code current_user()}（§13.8 的会话形状）。 */
    public static String toSql(Object node) {
        if (node instanceof String text) {
            return quote(text);
        }
        if (node instanceof Boolean b) {
            return b ? "TRUE" : "FALSE";
        }
        if (node instanceof Number) {
            return String.valueOf(node);
        }
        if (node == null) {
            return "NULL";
        }
        if (!(node instanceof Map<?, ?> map)) {
            return String.valueOf(node);
        }
        if (map.containsKey("col")) {
            return String.valueOf(map.get("col"));
        }
        if (map.containsKey("lit")) {
            return toSql(map.get("lit"));
        }
        if (map.containsKey("principal")) {
            return "current_user()";
        }
        if (map.containsKey("principal_roles")) {
            return "$principal_roles";
        }
        if (map.containsKey("op")) {
            String op = String.valueOf(map.get("op"));
            return switch (op) {
                case "eq" -> binary(map, " = ");
                case "neq" -> binary(map, " <> ");
                case "lt" -> binary(map, " < ");
                case "le" -> binary(map, " <= ");
                case "gt" -> binary(map, " > ");
                case "gte" -> binary(map, " >= ");
                case "and" -> paren(binary(map, " AND "));
                case "or" -> paren(binary(map, " OR "));
                case "like" -> binary(map, " LIKE ");
                case "not" -> "NOT " + paren(toSql(map.get("operand")));
                case "is_null" -> toSql(map.get("operand")) + " IS NULL";
                case "is_not_null" -> toSql(map.get("operand")) + " IS NOT NULL";
                case "in" -> {
                    Object inValues = map.get("values");
                    String rendered = inValues instanceof Map<?, ?> roles
                            && roles.containsKey("principal_roles")
                            ? "$principal_roles"
                            : renderList((Collection<?>) inValues);
                    yield toSql(map.get("operand")) + " IN (" + rendered + ")";
                }
                default -> op + "(?)";
            };
        }
        if (map.containsKey("fn")) {
            String fn = String.valueOf(map.get("fn"));
            return switch (fn) {
                case "substr" -> "SUBSTR(" + renderArgs(map) + ")";
                case "concat" -> "CONCAT(" + renderArgs(map) + ")";
                case "upper" -> "UPPER(" + renderArgs(map) + ")";
                case "lower" -> "LOWER(" + renderArgs(map) + ")";
                case "trim" -> "TRIM(" + renderArgs(map) + ")";
                case "regexp_replace" -> "REGEXP_REPLACE(" + renderArgs(map) + ")";
                case "date_trunc" -> "DATE_TRUNC(" + renderArgs(map) + ")";
                case "year" -> "YEAR(" + renderArgs(map) + ")";
                case "month" -> "MONTH(" + renderArgs(map) + ")";
                case "to_date" -> "TO_DATE(" + renderArgs(map) + ")";
                case "date_format" -> "DATE_FORMAT(" + renderArgs(map) + ")";
                case "md5" -> "MD5(" + renderArgs(map) + ")";
                case "sha2" -> "SHA2(" + renderArgs(map) + ")";
                case "coalesce" -> "COALESCE(" + renderArgs(map) + ")";
                case "cast" -> "CAST(" + toSql(map.get("operand")) + " AS "
                        + String.valueOf(map.get("to")).toUpperCase(Locale.ROOT) + ")";
                case "case_when" -> {
                    StringBuilder sql = new StringBuilder("CASE");
                    for (Object branchObject : (Collection<?>) map.get("branches")) {
                        Map<?, ?> branch = (Map<?, ?>) branchObject;
                        sql.append(" WHEN ").append(toSql(branch.get("when")))
                                .append(" THEN ").append(toSql(branch.get("then")));
                    }
                    if (map.containsKey("else")) {
                        sql.append(" ELSE ").append(toSql(map.get("else")));
                    }
                    yield sql.append(" END").toString();
                }
                default -> fn.toUpperCase(Locale.ROOT) + "(" + renderArgs(map) + ")";
            };
        }
        return String.valueOf(map);
    }

    // ------------------------------------------------------------------ 内部工具

    /**
     * 给行过滤包一层 {@code coalesce(expr, false)}（§5.3.3：NULL 在 WHERE 里等价
     * FALSE，但让人分不清「被过滤」和「表达式写错了」）。幂等：根节点已经是
     * {@code coalesce(..., false)} 时不重复包——否则每次保存都加一层，
     * 第 N 次保存的表达式会变成 N 层嵌套。
     */
    private static Map<String, Object> wrapCoalesce(Map<String, Object> node) {
        if (isCoalesceFalse(node)) {
            return node;
        }
        Map<String, Object> wrapped = new LinkedHashMap<>();
        wrapped.put("fn", "coalesce");
        wrapped.put("args", List.of(node, Map.of("lit", Boolean.FALSE)));
        return wrapped;
    }

    private static boolean isCoalesceFalse(Map<String, Object> node) {
        if (!"coalesce".equals(node.get("fn"))) {
            return false;
        }
        Object args = node.get("args");
        if (!(args instanceof Collection<?> list) || list.size() < 2) {
            return false;
        }
        Object last = null;
        for (Object item : list) {
            last = item;
        }
        return last instanceof Map<?, ?> lastMap
                && lastMap.containsKey("lit")
                && Boolean.FALSE.equals(lastMap.get("lit"));
    }

    private static String requireColumnCategory(String column, SchemaView schema) {
        if (column == null || column.isBlank() || column.indexOf('.') >= 0) {
            throw ApiException.badRequest("mask target must be a top-level column name, got: " + column);
        }
        if (!schema.columns().contains(column)) {
            throw ApiException.badRequest("column does not exist in the table schema: " + column);
        }
        return schema.types().get(column);
    }

    private static void requireSameCategory(String actual, String expected, String column) {
        if (!actual.equals(expected)) {
            throw ApiException.badRequest("mask for column " + column + " produces " + actual
                    + " but the column is " + expected + " — the result type must equal the"
                    + " column type (design §5.3.3, no implicit casts)");
        }
    }

    private static void requireStringColumn(String columnCategory, String column, String fn) {
        if (!STRING.equals(columnCategory)) {
            throw ApiException.badRequest("mask " + fn + " produces a string, but column " + column
                    + " is " + columnCategory + " — the result type must equal the column type");
        }
    }

    private static void requireTemporal(String columnCategory, String column, String fn) {
        if (!DATE.equals(columnCategory) && !TIMESTAMP.equals(columnCategory)) {
            throw ApiException.badRequest("mask " + fn + " applies to date/timestamp columns,"
                    + " but column " + column + " is " + columnCategory);
        }
    }

    /** cast 只允许放宽（§5.3.2）：同类、整型升位、浮点升位、任意 → string。 */
    private static void requireWidening(String from, String to) {
        if (to.equals(STRING)) {
            return;
        }
        if (from == null || from.equals(COMPLEX) || from.equals(NULL_TYPE)) {
            throw ApiException.badRequest("cannot determine the source type of a cast to " + to);
        }
        if (from.equals(to)) {
            return;
        }
        List<String> integerOrder = List.of("TINYINT", "SMALLINT", "INT", "BIGINT", INTEGER);
        if (integerOrder.contains(from) && integerOrder.contains(to)
                && integerOrder.indexOf(to) > integerOrder.indexOf(from)) {
            return;
        }
        if (FLOAT.equals(from) && DOUBLE_NAMES.contains(to)) {
            return;
        }
        throw ApiException.badRequest("cast from " + from + " to " + to
                + " is not a widening cast — only same-type, integer/float widening and"
                + " any→string are allowed (design §5.3.2)");
    }

    private static final List<String> DOUBLE_NAMES = List.of("FLOAT", "DOUBLE", "REAL");

    // ------------------------------------------------------------------ 列类型分类

    /** schema 文档里的类型（裸字符串或 Map）→ 粗分类。识别不了的按 COMPLEX 拒绝。 */
    private static String categoryOf(Object type) {
        if (type instanceof Map<?, ?> map) {
            Object kind = map.get("type");
            if (kind == null) {
                return COMPLEX;
            }
            String name = String.valueOf(kind).toUpperCase(Locale.ROOT);
            return switch (name) {
                case "DECIMAL", "NUMERIC" -> DECIMAL;
                case "ARRAY", "LIST", "MAP", "STRUCT", "ROW" -> COMPLEX;
                case "TIMESTAMP", "TIMESTAMP_WITH_LOCAL_TIME_ZONE", "TIMESTAMPTZ" -> TIMESTAMP;
                default -> categoryOfSimpleName(name);
            };
        }
        return categoryOfSimpleName(String.valueOf(type).toUpperCase(Locale.ROOT));
    }

    private static String categoryOfSimpleName(String name) {
        // 先剥修饰再认："NOT NULL" / "(n)" / "(p,s)" 这类后缀不是类型名的一部分
        // （schema 文档里的类型写作 "BIGINT NOT NULL"、"VARCHAR(32)"），剥掉后剩下的
        // 才进白名单；什么都剥不出来就按 COMPLEX 拒绝——fail-closed。
        String base = name.indexOf('(') > 0 ? name.substring(0, name.indexOf('(')) : name;
        base = base.indexOf(' ') > 0 ? base.substring(0, base.indexOf(' ')) : base;
        return switch (base) {
            case "STRING", "VARCHAR", "CHAR", "TEXT" -> STRING;
            case "TINYINT", "SMALLINT", "INT", "INTEGER", "BIGINT", "LONG", "SHORT", "BYTE" -> INTEGER;
            case "DECIMAL", "NUMERIC" -> DECIMAL;
            case "FLOAT", "DOUBLE", "REAL" -> FLOAT;
            case "BOOLEAN", "BOOL" -> BOOLEAN;
            case "DATE" -> DATE;
            case "TIME" -> TIME;
            case "TIMESTAMP", "TIMESTAMP_WITH_LOCAL_TIME_ZONE", "TIMESTAMPTZ", "TIMESTAMP_LTZ" -> TIMESTAMP;
            case "ARRAY", "LIST", "MAP", "STRUCT", "ROW" -> COMPLEX;
            default -> COMPLEX;
        };
    }

    private static String literalType(Object to) {
        if (to instanceof Map<?, ?>) {
            throw ApiException.badRequest("cast target must be a simple type name, not a type object");
        }
        String name = String.valueOf(to).toUpperCase(Locale.ROOT);
        String base = name.indexOf('(') > 0 ? name.substring(0, name.indexOf('(')) : name;
        return switch (base) {
            case "STRING", "VARCHAR", "CHAR", "TEXT" -> STRING;
            case "TINYINT" -> "TINYINT";
            case "SMALLINT" -> "SMALLINT";
            case "INT", "INTEGER" -> "INT";
            case "BIGINT", "LONG" -> "BIGINT";
            case "FLOAT" -> "FLOAT";
            case "DOUBLE", "REAL" -> "DOUBLE";
            case "BOOLEAN", "BOOL" -> BOOLEAN;
            case "DATE" -> DATE;
            case "TIMESTAMP" -> TIMESTAMP;
            default -> throw ApiException.badRequest("cast target is not in the whitelist: " + to
                    + " — allowed: integer/float family, boolean, date, timestamp, string");
        };
    }

    private static void requireDateTruncUnit(String unit) {
        if (!Set.of("year", "quarter", "month", "week", "day", "hour", "minute", "second")
                .contains(unit.toLowerCase(Locale.ROOT))) {
            throw ApiException.badRequest("date_trunc unit must be one of"
                    + " year/quarter/month/week/day/hour/minute/second, got: " + unit);
        }
    }

    // ------------------------------------------------------------------ 形状工具

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }

    private static void requireKeys(Map<?, ?> map, List<String> keys, String where) {
        for (String key : keys) {
            if (!map.containsKey(key)) {
                throw ApiException.badRequest(where + " requires key \"" + key + "\"");
            }
        }
    }

    private static void requireNoExtraKeys(Map<?, ?> map, Set<String> known, String where) {
        for (Object key : map.keySet()) {
            if (!known.contains(String.valueOf(key))) {
                throw ApiException.badRequest(where + " has an unexpected key \"" + key + "\""
                        + " — allowed: " + String.join(", ", known.stream().sorted().toList()));
            }
        }
    }

    private static Set<String> knownOpKeys(String op) {
        return switch (op) {
            case "not", "is_null", "is_not_null" -> Set.of("op", "operand");
            case "in" -> Set.of("op", "operand", "values");
            default -> Set.of("op", "left", "right");
        };
    }

    private static Set<String> knownFnKeys(String fn) {
        return switch (fn) {
            case "cast" -> Set.of("fn", "operand", "to");
            case "case_when" -> Set.of("fn", "branches", "else");
            default -> Set.of("fn", "args");
        };
    }

    /**
     * 单操作数函数（upper / md5 / ...）：Spark 惯例里操作数放在 {@code args}，
     * 但单参写 {@code "args": node}（不包数组）更顺；两种形状都收，归一成数组。
     */
    private static void requireArgs(Map<?, ?> map, int min, int max, String fn) {
        if (!map.containsKey("args")) {
            throw ApiException.badRequest(fn + " requires \"args\"");
        }
        Object args = map.get("args");
        int size = args instanceof Collection<?> list ? list.size() : 1;
        if (size < min || size > max) {
            throw ApiException.badRequest(fn + " takes between " + min + " and " + max + " arguments");
        }
    }

    private static List<Object> literalTailArgs(Map<?, ?> map, SchemaView schema, int depth,
                                                boolean inRowFilter, int min, int max, String fn) {
        requireArgs(map, min, max, fn);
        List<Object> raw = varArgs(map, "args", fn);
        List<Object> normalized = new ArrayList<>();
        for (Object arg : raw) {
            normalized.add(validate(arg, schema, depth + 1, inRowFilter));
        }
        return normalized;
    }

    /** {@code args} 归一成 List（单值形状也收）。 */
    private static List<Object> varArgs(Map<?, ?> map, String key, String where) {
        Object args = map.get(key);
        if (args instanceof Collection<?> list) {
            return new ArrayList<>(list);
        }
        if (args == null) {
            throw ApiException.badRequest(where + " requires \"" + key + "\"");
        }
        return new ArrayList<>(List.of(args));
    }

    private static void requireStringLiteral(Object node, String what) {
        if (!(asMap(node).containsKey("lit")) || !(asMap(node).get("lit") instanceof String)) {
            throw ApiException.badRequest(what + " must be a string literal");
        }
    }

    private static void requireIntLiteral(Object node, String what) {
        if (!(asMap(node).containsKey("lit")) || !(asMap(node).get("lit") instanceof Number)) {
            throw ApiException.badRequest(what + " must be an integer literal");
        }
    }

    private static String literalString(Object node, String what) {
        Object value = asMap(node).get("lit");
        if (!(value instanceof String text)) {
            throw ApiException.badRequest(what + " must be a string literal");
        }
        return text;
    }

    private static String binary(Map<?, ?> map, String infix) {
        return toSql(map.get("left")) + infix + toSql(map.get("right"));
    }

    private static String paren(String sql) {
        return "(" + sql + ")";
    }

    private static String renderArgs(Map<?, ?> map) {
        Object args = map.get("args");
        if (args instanceof Collection<?> list) {
            return renderList(list);
        }
        return toSql(args);
    }

    private static String renderList(Collection<?> nodes) {
        StringBuilder sql = new StringBuilder();
        for (Object node : nodes) {
            if (sql.length() > 0) {
                sql.append(", ");
            }
            sql.append(toSql(node));
        }
        return sql.toString();
    }

    private static String quote(String text) {
        return "'" + text.replace("'", "''") + "'";
    }
}
