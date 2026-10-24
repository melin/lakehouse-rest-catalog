package io.github.melin.lakehouse.rest.support;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 策略 AST 的**服务端求值器**（设计 §6 P1）——预览路径上逐行过滤 + 列变换的唯一执行点。
 *
 * <p><b>它是这套设计里唯一一处双实现</b>：Spark 侧把同一棵 AST 编译成 Catalyst 表达式
 * （{@code PolicyExpressionBuilder}），服务端在预览里逐行解释。语义必须逐条对齐
 * 引擎侧，漂移的症状是「同一个策略在控制台和 Spark 里给出不同的结果」——
 * 所以本类的每一段都注明对应的引擎语义；两边的差异由同一份用例表看住（测试）。
 *
 * <p><b>输入前提</b>（都是下游已有闸门保证的，这里不再校验）：
 * AST 是 {@link PolicyExpressions} 归一化后的产物——行过滤外层已包
 * {@code coalesce(expr, false)}；{@code $principal} 已折叠成字面量（§5.3.4，
 * 预览路径由 {@code DataPolicyService#previewPolicyOf} 调用同一份折叠规则）；
 * mask 结果类型与列同类在建策略时已验过。
 *
 * <p><b>求值顺序（§5.3.5）由调用方保证</b>，本类只算单行：
 * 先行过滤（读原值）、后 mask（同一层、参数一律读原值、不链式）。
 *
 * <p><b>NULL 语义照 SQL 三值逻辑走</b>：比较与逻辑算子在操作数为 NULL 时返回
 * {@code null}（未知），AND/OR/NOT 按三值表折叠；行过滤顶层的 coalesce 把未知折成
 * 「不可见」——与引擎里 {@code Filter} 对 NULL 的处置一致。
 */
public final class PolicyExpressionEvaluator {

    private PolicyExpressionEvaluator() {
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 求值行过滤。返回的布尔值可直接当「这行可见」用：
     * 顶层 coalesce 保证不会出现 {@code null}（万一出现也按 false 处理——fail-closed）。
     */
    public static boolean evalRowFilter(Map<String, Object> ast, Map<String, Object> row) {
        Object result = eval(ast, row);
        return Boolean.TRUE.equals(result);
    }

    /**
     * 求值一个 mask。{@code targetColumn} 是 mask 的目标列：标准集的
     * {@code {"fn": ...}} 形状没有参数指明操作数，操作数就是这一列的**原值**。
     */
    public static Object evalMask(Map<String, Object> maskAst, String targetColumn,
                                  Map<String, Object> row) {
        if (maskAst.containsKey("fn")) {
            // 标准集形状（fn 直接挂在 mask 上）：操作数是目标列的原值
            return standardMask(maskAst, targetColumn, row);
        }
        if (maskAst.containsKey("expr")) {
            // 逃生口形状：解开一层，按普通表达式求值
            return eval(maskAst.get("expr"), row);
        }
        throw new ApiException(400, null, null,
                "mask must contain either \"fn\" (standard set) or \"expr\" (escape hatch)");
    }

    /** 收集 AST 引用的全部列名（预览路径用它们补投影——§5.3.3 的全量 output 展开）。 */
    public static void collectColumns(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object col = map.get("col");
            if (col != null) {
                out.add(String.valueOf(col));
            }
            for (Object value : map.values()) {
                collectColumns(value, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectColumns(item, out);
            }
        } else if (node instanceof Object[] array) {
            for (Object item : array) {
                collectColumns(item, out);
            }
        }
    }

    /** 便捷重载：返回新的 {@code LinkedHashSet}。 */
    public static Set<String> collectColumns(Object node) {
        Set<String> out = new LinkedHashSet<>();
        collectColumns(node, out);
        return out;
    }

    // ------------------------------------------------------------------ 递归求值

    /** 求值一个（子）表达式；返回 Java 值或 {@code null}。 */
    public static Object eval(Object node, Map<String, Object> row) {
        if (node == null) {
            return null;
        }
        if (node instanceof String || node instanceof Boolean || node instanceof Number) {
            return node;
        }
        if (!(node instanceof Map<?, ?> map)) {
            throw new ApiException(400, null, null,
                    "unsupported expression node: " + node.getClass().getSimpleName());
        }
        if (map.containsKey("col")) {
            return row.get(String.valueOf(map.get("col")));
        }
        if (map.containsKey("lit")) {
            return map.get("lit");
        }
        if (map.containsKey("principal")) {
            // 服务端下发前已折叠（§5.3.4）；预览路径走同一份折叠，见到占位符就是绕过了协议
            throw new ApiException(400, null, null,
                    "$principal reached the evaluator without being folded into a literal;"
                            + " the policy service must substitute it before use (design §5.3.4)");
        }
        if (map.containsKey("principal_roles")) {
            // 同上：预览路径走同一份折叠，见到角色占位符就是绕过了协议
            throw new ApiException(400, null, null,
                    "$principal_roles reached the evaluator without being folded into a role"
                            + " list; the policy service must substitute it before use (design §5.3.4.1)");
        }
        if (map.containsKey("op")) {
            return evalOp(map, row);
        }
        if (map.containsKey("fn")) {
            return evalFn(map, row);
        }
        throw new ApiException(400, null, null,
                "unsupported expression shape: " + map.keySet());
    }

    // ------------------------------------------------------------------ 算子

    private static Object evalOp(Map<?, ?> map, Map<String, Object> row) {
        String op = String.valueOf(map.get("op"));
        return switch (op) {
            case "eq", "neq", "lt", "le", "gt", "gte" -> relation(map.get("left"),
                    map.get("right"), row, op);
            case "and" -> threeValuedAnd(eval(map.get("left"), row), eval(map.get("right"), row));
            case "or" -> threeValuedOr(eval(map.get("left"), row), eval(map.get("right"), row));
            case "not" -> not(eval(map.get("operand"), row));
            case "is_null" -> eval(map.get("operand"), row) == null;
            case "is_not_null" -> eval(map.get("operand"), row) != null;
            case "like" -> like(eval(map.get("left"), row), literalOf(map.get("right"), row), row);
            case "in" -> in(map, row);
            default -> throw new ApiException(400, null, null, "unsupported op: " + op);
        };
    }

    /** 关系算子：任一侧未知 → 未知（SQL 三值逻辑）；否则按比较结果翻译成布尔。 */
    private static Object relation(Object leftNode, Object rightNode, Map<String, Object> row,
                                   String op) {
        Integer comparison = compare(leftNode, rightNode, row, op);
        if (comparison == null) {
            return null;
        }
        return switch (op) {
            case "eq" -> comparison == 0;
            case "neq" -> comparison != 0;
            case "lt" -> comparison < 0;
            case "le" -> comparison <= 0;
            case "gt" -> comparison > 0;
            case "gte" -> comparison >= 0;
            default -> throw new ApiException(400, null, null, "unsupported op: " + op);
        };
    }

    /**
     * 与引擎的 {@code coercePair} 对齐（优先把字面量一侧 cast 到另一侧）：
     * 一侧是列值、一侧是字面量时，按**列值**的类型解释字面量。返回负/零/正；
     * 任一侧为 null（SQL 意义上的未知）返回 {@code null}。
     */
    private static Integer compare(Object leftNode, Object rightNode, Map<String, Object> row,
                                   String op) {
        Object left = eval(leftNode, row);
        Object right = eval(rightNode, row);
        if (left == null || right == null) {
            return null;
        }
        int result = compareTo(left, right, op);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static int compareTo(Object left, Object right, String op) {
        // 数值族：全部升到 BigDecimal 比较（Float/Double 先经 valueOf 避免二进制噪声）
        if (left instanceof Number && right instanceof Number) {
            return numeric(left).compareTo(numeric(right));
        }
        // 时间族：同型直比；date vs datetime 把日期抬到当天零点（与引擎 cast 语义一致）
        if (left instanceof LocalDate || right instanceof LocalDate
                || left instanceof LocalDateTime || right instanceof LocalDateTime
                || left instanceof Instant || right instanceof Instant) {
            LocalDateTime a = toDateTime(left, "left side of " + op);
            LocalDateTime b = toDateTime(right, "right side of " + op);
            return a.compareTo(b);
        }
        if (left instanceof LocalTime || right instanceof LocalTime) {
            return toTime(left).compareTo(toTime(right));
        }
        // 布尔：FALSE < TRUE
        if (left instanceof Boolean && right instanceof Boolean) {
            return Boolean.compare((Boolean) left, (Boolean) right);
        }
        // 其余按字符串比较（引擎侧 cast 到 string 的兜底形态）
        String a = displayString(left, "left side of " + op);
        String b = displayString(right, "right side of " + op);
        return a.compareTo(b);
    }

    private static BigDecimal numeric(Object value) {
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (value instanceof Double d) {
            return BigDecimal.valueOf(d);
        }
        if (value instanceof Float f) {
            return BigDecimal.valueOf(f);
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        throw new ApiException(400, null, null,
                "cannot compare " + value.getClass().getSimpleName() + " as a number");
    }

    private static LocalDateTime toDateTime(Object value, String where) {
        if (value instanceof LocalDateTime dt) {
            return dt;
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
        if (value instanceof String text) {
            return parseDateTime(text, where);
        }
        throw new ApiException(400, null, null,
                "cannot compare " + value.getClass().getSimpleName() + " as a timestamp (" + where + ")");
    }

    private static LocalTime toTime(Object value) {
        if (value instanceof LocalTime time) {
            return time;
        }
        if (value instanceof String text) {
            return LocalTime.parse(text);
        }
        throw new ApiException(400, null, null,
                "cannot compare " + value.getClass().getSimpleName() + " as a time");
    }

    /** "2026-10-24" / "2026-10-24T09:30:00" / "2026-10-24 09:30:00[.fff]" 三种都收。 */
    private static LocalDateTime parseDateTime(String text, String where) {
        String normalized = text.trim().replace('T', ' ');
        try {
            return LocalDateTime.parse(normalized,
                    DateTimeFormatter.ofPattern("yyyy-MM-dd[ HH:mm:ss][.SSS][.SSSSSS][.SSSSSSSSS]"));
        } catch (DateTimeParseException ignored) {
            // 落到下面的日期-only 分支
        }
        try {
            return LocalDate.parse(normalized.substring(0, 10)).atStartOfDay();
        } catch (RuntimeException exception) {
            throw new ApiException(400, null, null,
                    "'" + text + "' is not a date or timestamp literal (" + where + ")");
        }
    }

    private static Boolean threeValuedAnd(Object left, Object right) {
        if (Boolean.FALSE.equals(left) || Boolean.FALSE.equals(right)) {
            return false;
        }
        if (left == null || right == null) {
            return null;
        }
        return booleanOf(left, "AND") && booleanOf(right, "AND");
    }

    private static Boolean threeValuedOr(Object left, Object right) {
        if (Boolean.TRUE.equals(left) || Boolean.TRUE.equals(right)) {
            return true;
        }
        if (left == null || right == null) {
            return null;
        }
        return booleanOf(left, "OR") || booleanOf(right, "OR");
    }

    private static Object not(Object operand) {
        if (operand == null) {
            return null;
        }
        return !booleanOf(operand, "not");
    }

    private static boolean booleanOf(Object value, String where) {
        if (value instanceof Boolean b) {
            return b;
        }
        throw new ApiException(400, null, null,
                "operand of " + where + " must be a boolean, got "
                        + value.getClass().getSimpleName());
    }

    private static Object literalOf(Object node, Map<String, Object> row) {
        Object value = eval(node, row);
        if (!(value instanceof String)) {
            throw new ApiException(400, null, null, "like pattern must be a string literal");
        }
        return value;
    }

    /** SQL LIKE（引擎用 {@code Like(l, r, '\\')}：反斜杠是转义符）。NULL 任一侧 → 未知。 */
    private static Object like(Object value, Object pattern, Map<String, Object> row) {
        if (value == null || pattern == null) {
            return null;
        }
        String text = displayString(value, "like");
        return likeToRegex(String.valueOf(pattern)).matcher(text).matches();
    }

    /** {@code %} → {@code .*}、{@code _} → {@code .}、{@code \x} → 字面 {@code x}。 */
    private static Pattern likeToRegex(String pattern) {
        StringBuilder regex = new StringBuilder(pattern.length() * 2);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            if (c == '\\' && i + 1 < pattern.length()) {
                regex.append(Pattern.quote(String.valueOf(pattern.charAt(++i))));
            } else if (c == '%') {
                regex.append(".*");
            } else if (c == '_') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    @SuppressWarnings("unchecked")
    private static Object in(Map<?, ?> map, Map<String, Object> row) {
        Object operand = eval(map.get("operand"), row);
        if (operand == null) {
            return null;
        }
        boolean sawNull = false;
        for (Object valueNode : (List<Object>) map.get("values")) {
            Object value = eval(valueNode, row);
            if (value == null) {
                sawNull = true;
                continue;
            }
            if (compareTo(operand, value, "in") == 0) {
                return true;
            }
        }
        return sawNull ? null : false;
    }

    // ------------------------------------------------------------------ 函数

    @SuppressWarnings("unchecked")
    private static Object evalFn(Map<?, ?> map, Map<String, Object> row) {
        String fn = String.valueOf(map.get("fn"));
        return switch (fn) {
            case "substr" -> substr(((List<Object>) map.get("args")), row, fn);
            case "concat" -> concat((List<Object>) map.get("args"), row);
            case "upper" -> stringFn(single((List<Object>) map.get("args"), row, fn), fn,
                    String::toUpperCase);
            case "lower" -> stringFn(single((List<Object>) map.get("args"), row, fn), fn,
                    String::toLowerCase);
            case "trim" -> stringFn(single((List<Object>) map.get("args"), row, fn), fn,
                    String::trim);
            case "regexp_replace" -> regexpReplace((List<Object>) map.get("args"), row);
            case "date_trunc" -> dateTrunc((List<Object>) map.get("args"), row);
            case "year" -> yearMonth(single((List<Object>) map.get("args"), row, fn), row, true);
            case "month" -> yearMonth(single((List<Object>) map.get("args"), row, fn), row, false);
            case "to_date" -> toDate((List<Object>) map.get("args"), row);
            case "date_format" -> dateFormat((List<Object>) map.get("args"), row);
            case "md5" -> digest(single((List<Object>) map.get("args"), row, fn), row, "MD5", 0);
            case "sha2" -> {
                List<Object> args = (List<Object>) map.get("args");
                Object bits = eval(args.get(1), row);
                if (!(bits instanceof Number number)) {
                    throw new ApiException(400, null, null, "sha2 digest length must be a number");
                }
                yield digest(eval(args.get(0), row), row, "SHA-" + number.intValue(),
                        number.intValue());
            }
            case "coalesce" -> {
                for (Object arg : (List<Object>) map.get("args")) {
                    Object value = eval(arg, row);
                    if (value != null) {
                        yield value;
                    }
                }
                yield null;
            }
            case "cast" -> cast(eval(map.get("operand"), row), String.valueOf(map.get("to")));
            case "case_when" -> caseWhen(map, row);
            default -> throw new ApiException(400, null, null, "unsupported function: " + fn);
        };
    }

    /** 引擎 {@code CaseWhen}：条件按三值逻辑，第一个为真的分支生效；没有 else 且全不命中 → NULL。 */
    @SuppressWarnings("unchecked")
    private static Object caseWhen(Map<?, ?> map, Map<String, Object> row) {
        for (Object branchObject : (List<Object>) map.get("branches")) {
            Map<?, ?> branch = (Map<?, ?>) branchObject;
            if (Boolean.TRUE.equals(eval(branch.get("when"), row))) {
                return eval(branch.get("then"), row);
            }
        }
        if (map.containsKey("else")) {
            return eval(map.get("else"), row);
        }
        return null;
    }

    private static Object single(List<Object> args, Map<String, Object> row, String fn) {
        if (args == null || args.size() != 1) {
            throw new ApiException(400, null, null, fn + " requires exactly one argument");
        }
        return eval(args.get(0), row);
    }

    /** NULL 直通；非字符串先按引擎的 castToString 语义翻成字符串。 */
    private static Object stringFn(Object value, String fn,
                                   java.util.function.UnaryOperator<String> transform) {
        if (value == null) {
            return null;
        }
        return transform.apply(displayString(value, fn));
    }

    /**
     * Spark {@code Substring} 语义（引擎 {@code Substring(str, start, len)}）：
     * 1 基；start=0 按 1 算；start 为负从串尾数（-1 是最后一个字符）；
     * len &le; 0 得空串；start 越界得空串。
     */
    private static Object substr(List<Object> args, Map<String, Object> row, String fn) {
        if (args == null || args.size() < 2 || args.size() > 3) {
            throw new ApiException(400, null, null, fn + " requires two or three arguments");
        }
        Object value = eval(args.get(0), row);
        if (value == null) {
            return null;
        }
        String text = displayString(value, fn);
        Object startObject = eval(args.get(1), row);
        Object lengthObject = args.size() > 2 ? eval(args.get(2), row) : null;
        if (!(startObject instanceof Number) || (lengthObject != null && !(lengthObject instanceof Number))) {
            throw new ApiException(400, null, null, fn + " start/length must be integers");
        }
        int start = ((Number) startObject).intValue();
        int length = lengthObject == null ? Integer.MAX_VALUE : ((Number) lengthObject).intValue();
        if (length <= 0) {
            return "";
        }
        int from = start == 0 ? 0 : (start > 0 ? start - 1 : text.length() + start);
        if (from < 0) {
            // 负起点越过串头：起点抬到 0，可用长度相应减少（与 Spark 一致）
            length += from;
            from = 0;
            if (length <= 0) {
                return "";
            }
        }
        if (from >= text.length()) {
            return "";
        }
        int end = (long) from + length > text.length() ? text.length() : from + length;
        return text.substring(from, end);
    }

    /** 引擎 {@code Concat(args.map(castToString))}：任一参数为 NULL 结果为 NULL。 */
    private static Object concat(List<Object> args, Map<String, Object> row) {
        if (args == null || args.size() < 2) {
            throw new ApiException(400, null, null, "concat requires at least two arguments");
        }
        StringBuilder out = new StringBuilder();
        for (Object arg : args) {
            Object value = eval(arg, row);
            if (value == null) {
                return null;
            }
            out.append(displayString(value, "concat"));
        }
        return out.toString();
    }

    /** 引擎 {@code RegExpReplace(..., Literal(1))}：替换**全部**匹配（Java replaceAll 语义）。 */
    private static Object regexpReplace(List<Object> args, Map<String, Object> row) {
        if (args == null || args.size() != 3) {
            throw new ApiException(400, null, null, "regexp_replace requires exactly three arguments");
        }
        Object value = eval(args.get(0), row);
        if (value == null) {
            return null;
        }
        Object pattern = eval(args.get(1), row);
        Object replacement = eval(args.get(2), row);
        if (!(pattern instanceof String) || !(replacement instanceof String)) {
            throw new ApiException(400, null, null,
                    "regexp_replace pattern/replacement must be string literals");
        }
        try {
            return Pattern.compile((String) pattern)
                    .matcher(displayString(value, "regexp_replace"))
                    .replaceAll((String) replacement);
        } catch (RuntimeException exception) {
            throw new ApiException(400, null, null,
                    "regexp_replace failed: " + exception.getMessage());
        }
    }

    /** 引擎 {@code TruncDate(col, unit)}：DATE 进 DATE 出、TIMESTAMP 进 TIMESTAMP 出。 */
    private static Object dateTrunc(List<Object> args, Map<String, Object> row) {
        if (args == null || args.size() != 2) {
            throw new ApiException(400, null, null, "date_trunc requires exactly two arguments");
        }
        Object value = eval(args.get(1), row);
        if (value == null) {
            return null;
        }
        Object unitObject = eval(args.get(0), row);
        if (!(unitObject instanceof String unit)) {
            throw new ApiException(400, null, null, "date_trunc unit must be a string literal");
        }
        String u = unit.toLowerCase(java.util.Locale.ROOT);
        if (value instanceof LocalDate date) {
            return truncateDate(date, u);
        }
        if (value instanceof LocalDateTime dt) {
            return truncateDateTime(dt, u);
        }
        if (value instanceof Instant instant) {
            return truncateDateTime(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()), u);
        }
        if (value instanceof String text) {
            return truncateDateTime(parseDateTime(text, "date_trunc"), u);
        }
        throw new ApiException(400, null, null, "date_trunc expects a date/timestamp input, got "
                + value.getClass().getSimpleName());
    }

    private static LocalDate truncateDate(LocalDate date, String unit) {
        return switch (unit) {
            case "year", "years", "yy", "yyyy" -> date.withDayOfYear(1);
            case "quarter", "quarters", "qq", "q" -> date.withMonth(((date.getMonthValue() - 1) / 3) * 3 + 1)
                    .withDayOfMonth(1);
            case "month", "months", "mm", "mon" -> date.withDayOfMonth(1);
            case "week", "weeks", "wk" -> date.minusDays((date.getDayOfWeek().getValue() - 1) % 7);
            case "day", "days", "dd", "d" -> date;
            default -> throw new ApiException(400, null, null,
                    "date_trunc unit '" + unit + "' cannot truncate a date value");
        };
    }

    private static LocalDateTime truncateDateTime(LocalDateTime dt, String unit) {
        return switch (unit) {
            case "year", "years", "yy", "yyyy" -> dt.withDayOfYear(1).toLocalDate().atStartOfDay();
            case "quarter", "quarters", "qq", "q" -> dt.withMonth(((dt.getMonthValue() - 1) / 3) * 3 + 1)
                    .withDayOfMonth(1).toLocalDate().atStartOfDay();
            case "month", "months", "mm", "mon" -> dt.withDayOfMonth(1).toLocalDate().atStartOfDay();
            case "week", "weeks", "wk" -> dt.toLocalDate()
                    .minusDays((dt.getDayOfWeek().getValue() - 1) % 7).atStartOfDay();
            case "day", "days", "dd", "d" -> dt.toLocalDate().atStartOfDay();
            case "hour", "hours", "hh", "h" -> dt.withMinute(0).withSecond(0).withNano(0);
            case "minute", "minutes", "mi", "n" -> dt.withSecond(0).withNano(0);
            case "second", "seconds", "ss", "s" -> dt.withNano(0);
            default -> throw new ApiException(400, null, null,
                    "date_trunc unit '" + unit + "' is not in the whitelist");
        };
    }

    /** 引擎 {@code Year / Month}：DATE / TIMESTAMP / 可解析字符串都收。 */
    private static Object yearMonth(Object value, Map<String, Object> row, boolean year) {
        if (value == null) {
            return null;
        }
        LocalDateTime dt;
        if (value instanceof LocalDate date) {
            dt = date.atStartOfDay();
        } else if (value instanceof LocalDateTime localDateTime) {
            dt = localDateTime;
        } else if (value instanceof Instant instant) {
            dt = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        } else if (value instanceof String text) {
            dt = parseDateTime(text, "year/month");
        } else {
            throw new ApiException(400, null, null,
                    "year/month expects a date/timestamp input, got " + value.getClass().getSimpleName());
        }
        return (long) (year ? dt.getYear() : dt.getMonthValue());
    }

    /** 引擎 {@code ParseToDate}：无格式按 cast(string as date)，有格式按 pattern 解析。 */
    private static Object toDate(List<Object> args, Map<String, Object> row) {
        if (args == null || args.isEmpty() || args.size() > 2) {
            throw new ApiException(400, null, null, "to_date requires one or two arguments");
        }
        Object value = eval(args.get(0), row);
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return date;
        }
        if (value instanceof LocalDateTime dt) {
            return dt.toLocalDate();
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate();
        }
        if (value instanceof String text) {
            if (args.size() == 2) {
                Object format = eval(args.get(1), row);
                if (!(format instanceof String pattern)) {
                    throw new ApiException(400, null, null, "to_date format must be a string literal");
                }
                try {
                    return LocalDate.parse(text.trim(),
                            java.time.format.DateTimeFormatter.ofPattern(pattern));
                } catch (DateTimeParseException exception) {
                    return null; // Spark 对解析失败返回 NULL，不抛错
                }
            }
            try {
                return parseDateTime(text, "to_date").toLocalDate();
            } catch (ApiException exception) {
                return null;
            }
        }
        throw new ApiException(400, null, null,
                "to_date expects a string / date / timestamp input, got "
                        + value.getClass().getSimpleName());
    }

    /** 引擎 {@code DateFormatClass(ts, pattern)}：SimpleDateFormat 词表。 */
    private static Object dateFormat(List<Object> args, Map<String, Object> row) {
        if (args == null || args.size() != 2) {
            throw new ApiException(400, null, null, "date_format requires exactly two arguments");
        }
        Object value = eval(args.get(0), row);
        if (value == null) {
            return null;
        }
        Object patternObject = eval(args.get(1), row);
        if (!(patternObject instanceof String pattern)) {
            throw new ApiException(400, null, null, "date_format pattern must be a string literal");
        }
        java.util.Date date;
        if (value instanceof LocalDate localDate) {
            date = java.util.Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } else if (value instanceof LocalDateTime localDateTime) {
            date = java.util.Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
        } else if (value instanceof Instant instant) {
            date = java.util.Date.from(instant);
        } else if (value instanceof String text) {
            date = java.util.Date.from(parseDateTime(text, "date_format")
                    .atZone(ZoneId.systemDefault()).toInstant());
        } else {
            throw new ApiException(400, null, null,
                    "date_format expects a date/timestamp input, got " + value.getClass().getSimpleName());
        }
        return new java.text.SimpleDateFormat(pattern, java.util.Locale.ROOT).format(date);
    }

    /** 引擎 {@code Md5(castToString(x))} / {@code Sha2(castToString(x), bits)}：小写十六进制。 */
    private static Object digest(Object value, Map<String, Object> row, String algorithm, int bits) {
        if (value == null) {
            return null;
        }
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance(
                    bits == 0 ? "MD5" : "SHA-" + bits);
            byte[] hashed = digest.digest(displayString(value, algorithm).getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new ApiException(400, null, null, "unsupported hash algorithm: " + algorithm);
        }
    }

    /** 协议里 cast 目标是服务端 {@code literalType} 归一过的粗词；放宽型在建策略时已验。 */
    private static Object cast(Object value, String to) {
        if (value == null) {
            return null;
        }
        return switch (to) {
            case "STRING" -> displayString(value, "cast to STRING");
            case "TINYINT", "SMALLINT", "INT" -> numeric(value).longValue();
            case "BIGINT" -> numeric(value).longValue();
            case "FLOAT", "DOUBLE" -> numeric(value).doubleValue();
            case "BOOLEAN" -> {
                if (value instanceof Boolean b) {
                    yield b;
                }
                yield Boolean.parseBoolean(String.valueOf(value));
            }
            case "DATE" -> {
                if (value instanceof LocalDate d) {
                    yield d;
                }
                if (value instanceof LocalDateTime dt) {
                    yield dt.toLocalDate();
                }
                if (value instanceof Instant instant) {
                    yield LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toLocalDate();
                }
                yield parseDateTime(String.valueOf(value), "cast to DATE").toLocalDate();
            }
            case "TIMESTAMP" -> {
                if (value instanceof LocalDateTime dt) {
                    yield dt;
                }
                if (value instanceof LocalDate d) {
                    yield d.atStartOfDay();
                }
                if (value instanceof Instant instant) {
                    yield LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
                }
                yield parseDateTime(String.valueOf(value), "cast to TIMESTAMP");
            }
            default -> throw new ApiException(400, null, null,
                    "unsupported cast target type: " + to);
        };
    }

    // ------------------------------------------------------------------ 标准脱敏集（与引擎 standardMask 逐条对齐）

    /** 与 Hive {@code mask()} / 引擎的 translate 表逐字符一致：大写→X、小写→x、数字→n、其余不变。 */
    private static Object alphanum(Object value, String where) {
        if (value == null) {
            return null;
        }
        String text = displayString(value, where);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                out.append('X');
            } else if (c >= 'a' && c <= 'z') {
                out.append('x');
            } else if (c >= '0' && c <= '9') {
                out.append('n');
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static Object standardMask(Map<?, ?> mask, String targetColumn, Map<String, Object> row) {
        String fn = String.valueOf(mask.get("fn"));
        List<?> rawArgs = mask.get("args") instanceof List<?> list ? list : List.of();
        Object value = row.get(targetColumn);
        return switch (fn) {
            case "mask_alphanum" -> alphanum(value, fn);
            case "show_first_n" -> {
                int n = ((Number) rawArgs.get(0)).intValue();
                if (value == null) {
                    yield null;
                }
                String text = displayString(value, fn);
                int keep = Math.min(n, text.length());
                yield text.substring(0, keep) + alphanum(text.substring(keep), fn);
            }
            case "show_last_n" -> {
                int n = ((Number) rawArgs.get(0)).intValue();
                if (value == null) {
                    yield null;
                }
                String text = displayString(value, fn);
                // 与引擎一致：前缀 = 前 max(len-n, 0) 个字符做 alphanum，尾部原样保留
                int maskUpTo = Math.max(text.length() - n, 0);
                yield alphanum(text.substring(0, maskUpTo), fn) + text.substring(maskUpTo);
            }
            case "replace_with_null" -> null;
            case "mask_to_fixed_value" -> rawArgs.get(0);
            case "truncate_to_year" -> truncateTemporal(value, "year", targetColumn);
            case "truncate_to_month" -> truncateTemporal(value, "month", targetColumn);
            case "hash" -> {
                String algorithm = String.valueOf(rawArgs.get(0))
                        .toUpperCase(java.util.Locale.ROOT);
                yield switch (algorithm) {
                    case "MD5" -> digest(value, Map.of(), "MD5", 0);
                    case "SHA-256" -> digest(value, Map.of(), "SHA-256", 256);
                    case "SHA-512" -> digest(value, Map.of(), "SHA-512", 512);
                    default -> throw new ApiException(400, null, null,
                            "unsupported hash algorithm " + algorithm
                                    + "; allowed: MD5, SHA-256, SHA-512");
                };
            }
            default -> throw new ApiException(400, null, null, "unknown standard mask: " + fn);
        };
    }

    /** 引擎 {@code TruncDate}：DATE 进 DATE 出、TIMESTAMP 进 TIMESTAMP 出。 */
    private static Object truncateTemporal(Object value, String unit, String column) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDate date) {
            return truncateDate(date, unit);
        }
        if (value instanceof LocalDateTime dt) {
            return truncateDateTime(dt, unit);
        }
        if (value instanceof Instant instant) {
            return truncateDateTime(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()), unit);
        }
        if (value instanceof String text) {
            return truncateDateTime(parseDateTime(text, "truncate_" + unit + " of " + column), unit);
        }
        throw new ApiException(400, null, null, "mask truncate_" + unit + " applies to"
                + " date/timestamp columns, but the value of " + column + " is "
                + value.getClass().getSimpleName());
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 引擎 cast-to-string 的对齐形态。date/timestamp 用与 Jackson/预览响应相同的 ISO
     * 形状（LocalDate → {@code 2026-10-24}、LocalDateTime → ISO、Instant → ISO），
     * 让「md5(某列)」在控制台与 Spark 里对同一行算出同一个值。
     */
    private static String displayString(Object value, String where) {
        if (value instanceof String text) {
            return text;
        }
        if (value instanceof LocalDate || value instanceof LocalDateTime
                || value instanceof Instant || value instanceof LocalTime) {
            return String.valueOf(value);
        }
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return String.valueOf(d);
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        throw new ApiException(400, null, null, "cannot render "
                + value.getClass().getSimpleName() + " as a string (" + where + ")");
    }
}
