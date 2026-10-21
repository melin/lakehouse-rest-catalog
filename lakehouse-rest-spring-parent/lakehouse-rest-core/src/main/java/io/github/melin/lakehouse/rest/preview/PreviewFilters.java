package io.github.melin.lakehouse.rest.preview;

import io.github.melin.lakehouse.rest.support.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 行级过滤：校验、编译、逐行判定。
 *
 * <p><b>为什么放在 core 而不是各格式各写一份。</b>过滤语义是**格式无关**的：用户说
 * {@code amount >= 100}，Paimon 与 Iceberg 该给出同样的结果。两处各实现一遍的代价
 * 不是重复几百行，而是"同一个条件在两种表上筛出不同的行"——那种分歧只在用户
 * 同时有两张表时才会被发现，而发现者会怀疑是数据的问题。
 *
 * <p><b>不隐式转换（§6.3）。</b>给 {@code INT} 列传 {@code "abc"} 是 400，不是"筛出 0 行"。
 * 静默的 0 行会被读成"表里没有这个值"，这类误判比报错贵得多——报错至少指向请求，
 * 0 行指向数据。
 *
 * <p><b>null 的语义照 SQL 走。</b>{@code null > 1} 不成立、{@code null = 1} 不成立、
 * {@code null <> 1} 也不成立；要判空只有 {@code isNull}。这不是偷懒：把 {@code ne}
 * 做成"null 也算不等于"会让"排除某值"的条件悄悄把空值行带进来。
 */
public final class PreviewFilters {

    private static final Set<String> NUMERIC_OPS =
            Set.of("eq", "ne", "gt", "ge", "lt", "le", "isNull", "isNotNull");
    private static final Set<String> TEXT_OPS =
            Set.of("eq", "ne", "gt", "ge", "lt", "le", "contains", "startsWith", "isNull", "isNotNull");
    private static final Set<String> BOOLEAN_OPS = Set.of("eq", "isNull", "isNotNull");
    private static final Set<String> OPAQUE_OPS = Set.of("isNull", "isNotNull");

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd[' ']['T']HH:mm:ss[.SSS][.SSSSSS]", Locale.ROOT);

    private PreviewFilters() {
    }

    /**
     * 编译一组过滤条件。
     *
     * @param filters    请求里的条件
     * @param projection 本次要读的列名（按顺序）；条件里的列必须在这里面，
     *                   否则说明服务端没有把它补进投影——那是编排层的 bug，不是用户输入问题
     * @param columns    表的全部列，用于类型与算子校验
     * @param maxFilters 条件条数上限
     * @throws ApiException 400：列不存在 / 算子与类型不匹配 / 值不是该类型的值 / 条件太多
     */
    public static List<Compiled> compile(List<ColumnFilter> filters, List<String> projection,
                                         Map<String, PreviewColumn> columns, int maxFilters) {
        if (filters == null || filters.isEmpty()) {
            return List.of();
        }
        if (filters.size() > maxFilters) {
            throw ApiException.badRequest("Too many filters: " + filters.size()
                    + " (at most " + maxFilters + "); use an engine for anything bigger");
        }
        List<Compiled> compiled = new ArrayList<>(filters.size());
        for (ColumnFilter filter : filters) {
            if (filter == null || filter.column() == null || filter.column().isBlank()) {
                throw ApiException.badRequest("Every filter needs a column");
            }
            PreviewColumn column = columns.get(filter.column());
            if (column == null) {
                throw ApiException.badRequest("Unknown column '" + filter.column()
                        + "' in filter; this table has " + columns.keySet());
            }
            String op = filter.op() == null ? "" : filter.op().trim();
            if (!ColumnFilter.OPS.contains(op)) {
                throw ApiException.badRequest("Unsupported operator '" + filter.op()
                        + "' on column '" + column.name() + "'; supported: " + ColumnFilter.OPS);
            }
            if (!allowedOps(column.kind()).contains(op)) {
                throw ApiException.badRequest("Operator '" + op + "' does not apply to " + column.type()
                        + " column '" + column.name() + "'; supported: " + allowedOps(column.kind()));
            }
            int position = projection.indexOf(column.name());
            if (position < 0) {
                throw ApiException.badRequest("column '" + column.name()
                        + "' is filtered on but missing from the projection");
            }
            Object value = ColumnFilter.needsValue(op) ? coerce(column, filter.value()) : null;
            compiled.add(new Compiled(position, op, value, column.kind()));
        }
        return compiled;
    }

    /** 某类列可用的算子；前端提前隐藏不可用的选项用的也是这套（§6.1）。 */
    public static Set<String> allowedOps(PreviewColumn.Kind kind) {
        return switch (kind) {
            case NUMBER, TEMPORAL -> NUMERIC_OPS;
            case TEXT -> TEXT_OPS;
            case BOOLEAN -> BOOLEAN_OPS;
            case BINARY, COMPLEX -> OPAQUE_OPS;
        };
    }

    /** 一个条件对上某一行是否成立。 */
    public static boolean test(List<Object> row, Compiled filter) {
        Object left = filter.position() < row.size() ? row.get(filter.position()) : null;
        return switch (filter.op()) {
            case "isNull" -> left == null;
            case "isNotNull" -> left != null;
            // 两个后缀/子串匹配单独判：它们的"结果"不是三向比较，走 holds 只会得到 false
            case "contains" -> left != null && String.valueOf(left).contains(String.valueOf(filter.value()));
            case "startsWith" -> left != null && String.valueOf(left).startsWith(String.valueOf(filter.value()));
            default -> {
                if (left == null) {
                    yield false;
                }
                Integer sign = compare(left, filter);
                yield sign != null && holds(sign, filter.op());
            }
        };
    }

    /** 全部条件同时成立（AND）。 */
    public static boolean testAll(List<Object> row, List<Compiled> filters) {
        for (Compiled filter : filters) {
            if (!test(row, filter)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ 比较

    private static boolean holds(int sign, String op) {
        return switch (op) {
            case "eq" -> sign == 0;
            case "ne" -> sign != 0;
            case "gt" -> sign > 0;
            case "ge" -> sign >= 0;
            case "lt" -> sign < 0;
            case "le" -> sign <= 0;
            default -> false;
        };
    }

    /**
     * 比较一行里的值与条件里的值；类型不可比时返回 {@code null}（该条件不成立）。
     *
     * <p>三个分支各自有理由：数值统一走 {@link BigDecimal}（{@code 10.5 > 9.1} 用字符串比会错）；
     * 时间统一走 {@link LocalDateTime}（{@code DATE} 补成当天零点，{@code TIMESTAMP_LTZ}
     * 按系统时区落到本地时间——预览是给人看的，不是给跨时区对账用的）；
     * 文本走 {@code String.compareTo}，并额外支持 {@code contains}/{@code startsWith}。
     */
    private static Integer compare(Object left, Compiled filter) {
        return switch (filter.kind()) {
            case NUMBER -> numeric(left).compareTo(numeric(filter.value()));
            case TEMPORAL -> {
                LocalDateTime a = temporal(left);
                LocalDateTime b = temporal(filter.value());
                yield a == null || b == null ? null : a.compareTo(b);
            }
            case BOOLEAN -> Boolean.compare(Boolean.parseBoolean(String.valueOf(left)),
                    Boolean.parseBoolean(String.valueOf(filter.value())));
            default -> text(left) == null || text(filter.value()) == null
                    ? null
                    : text(left).compareTo(text(filter.value()));
        };
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal numeric(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        return new BigDecimal(String.valueOf(value).trim());
    }

    private static LocalDateTime temporal(Object value) {
        if (value instanceof LocalDateTime dateTime) {
            return dateTime;
        }
        if (value instanceof LocalDate date) {
            return date.atStartOfDay();
        }
        if (value instanceof LocalTime time) {
            return LocalDateTime.of(LocalDate.EPOCH, time);
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
        }
        if (value instanceof OffsetDateTime offset) {
            // Iceberg 的 timestamptz 读出来就是 OffsetDateTime（Paimon 的 LTZ 是 Instant）。
            // 不接住它会掉到下面的字符串解析：带偏移量的文本按 yyyy-MM-dd HH:mm:ss 解析
            // 必然失败，于是条件"永远不成立"——整列被筛空，而界面只显示 0 行
            return LocalDateTime.ofInstant(offset.toInstant(), ZoneId.systemDefault());
        }
        if (value instanceof Number number) {
            // 毫秒时间戳：某些写入端把时间存成 long，读出来就是数字
            return LocalDateTime.ofInstant(Instant.ofEpochMilli(number.longValue()), ZoneId.systemDefault());
        }
        try {
            return parseTemporal(String.valueOf(value));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 校验与解析

    private static Object coerce(PreviewColumn column, Object value) {
        if (value == null) {
            throw ApiException.badRequest("Filter on column '" + column.name() + "' needs a value");
        }
        return switch (column.kind()) {
            case NUMBER -> {
                try {
                    yield new BigDecimal(String.valueOf(value).trim());
                } catch (NumberFormatException exception) {
                    throw ApiException.badRequest("Column '" + column.name() + "' is " + column.type()
                            + "; '" + value + "' is not a number");
                }
            }
            case TEMPORAL -> {
                try {
                    yield parseTemporal(String.valueOf(value));
                } catch (DateTimeParseException exception) {
                    throw ApiException.badRequest("Column '" + column.name() + "' is " + column.type()
                            + "; '" + value + "' is not a date/time (use yyyy-MM-dd or yyyy-MM-dd HH:mm:ss)");
                }
            }
            case BOOLEAN -> {
                String text = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
                if (!"true".equals(text) && !"false".equals(text)) {
                    throw ApiException.badRequest("Column '" + column.name() + "' is " + column.type()
                            + "; '" + value + "' is not true/false");
                }
                yield Boolean.parseBoolean(text);
            }
            case BINARY, COMPLEX -> throw ApiException.badRequest("Column '" + column.name() + "' is "
                    + column.type() + "; only isNull / isNotNull are supported");
            case TEXT -> String.valueOf(value);
        };
    }

    private static LocalDateTime parseTemporal(String text) {
        String trimmed = text.trim();
        if (trimmed.length() <= 10) {
            return LocalDate.parse(trimmed).atStartOfDay();
        }
        return LocalDateTime.parse(trimmed, DATE_TIME);
    }

    /**
     * 编译后的条件：位置 + 算子 + 已解析的值 + 类型。
     *
     * <p>位置而不是列名，是因为判定发生在逐行循环里——那里每行查一次 map 就是
     * 每行多一次哈希，而预览的行数是以万计的。
     *
     * @param position 在本次投影里的下标
     * @param op       算子
     * @param value    已按列类型解析过的比较值；null 判定时为 {@code null}
     * @param kind     列的类型分类，决定比较方式
     */
    public record Compiled(int position, String op, Object value, PreviewColumn.Kind kind) {
    }
}
