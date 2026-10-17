package io.github.melin.lakehouse.rest.optimizer;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把「表 + 动作 + 生效参数」翻译成 Iceberg 的 {@code CALL} 语句。
 *
 * <p><b>与 Paimon 侧同一个理由，用 SQL 而不是 Java API。</b>procedure 的参数名与语义
 * 随 Iceberg 版本变，而 {@code CALL} 由**目标集群上的 Iceberg** 解释——集群是什么版本
 * 就按什么版本的语义执行。若在这里依赖某个版本的 Java 类，本模块的编译产物就把
 * Iceberg 版本焊死了。
 *
 * <p><b>两处与 Paimon 不同，都是 Iceberg 的规格带来的：</b>
 *
 * <ul>
 *   <li><b>时间窗口是绝对时间戳。</b>Paimon 的 {@code older_than} 收「相对时长」
 *       （{@code 24 h}），没有时区问题；Iceberg 的 {@code older_than} 是
 *       {@code timestamp}，必须由任务侧算出绝对时刻。这里统一按 **UTC** 格式化，
 *       同时把会话的 {@code spark.sql.session.timeZone} 钉成 UTC——两边都是 UTC，
 *       窗口就不会因为集群默认时区而整体平移（平移在 orphan 清理上的后果是多删文件）。</li>
 *   <li><b>Z-Order 不是独立策略。</b>Iceberg 没有 {@code z_order_columns} 参数：
 *       {@code strategy => 'sort'} 配 {@code sort_order => 'zorder(c1,c2)'}。
 *       策略层仍把它暴露成第三个取值（用户理解的是「按什么方式重排」），
 *       翻译在这里完成。</li>
 * </ul>
 *
 * <p>参数类型照形参逐个渲染：字符串加单引号、整数/布尔裸写、时间戳写成
 * {@code TIMESTAMP '...'} 字面量。{@code options} 是一个 map 形参，
 * 因此用 {@code map('k','v',...)} 而不是 Paimon 那种逗号拼接的字符串。
 */
public final class IcebergProcedures {

    public static final String COMPACT = "compact";
    public static final String EXPIRE = "expire";
    public static final String ORPHAN = "orphan";

    /** wire 名 → Iceberg 的 procedure 名。 */
    private static final Map<String, String> PROCEDURES = Map.of(
            COMPACT, "rewrite_data_files",
            EXPIRE, "expire_snapshots",
            ORPHAN, "remove_orphan_files");

    /**
     * 批内动作的执行顺序：与 Paimon 侧同一条理由。
     * compaction 先生成新快照，expire 才能基于最新状态判过期；orphan 放最后，
     * 它「只删早于窗口的文件」这条约束天然保护前两步刚产生的文件。
     */
    private static final List<String> ORDER = List.of(COMPACT, EXPIRE, ORPHAN);

    private static final String P_STRATEGY = "strategy";
    private static final String P_SORT_COLUMNS = "sort-columns";
    private static final String P_MIN_INPUT_FILES = "min-input-files";
    private static final String P_TARGET_FILE_SIZE_MB = "target-file-size-mb";
    private static final String P_MAX_CONCURRENT_REWRITES = "max-concurrent-file-group-rewrites";
    private static final String P_RETENTION_HOURS = "retention-hours";
    private static final String P_RETAIN_LAST = "retain-last";
    private static final String P_OLDER_THAN_HOURS = "older-than-hours";
    private static final String P_DRY_RUN = "dry-run";

    public static final String STRATEGY_BINPACK = "binpack";
    public static final String STRATEGY_SORT = "sort";
    public static final String STRATEGY_ZORDER = "zorder";

    /** 与 {@code spark.sql.session.timeZone=UTC} 配套：时间戳的字面量必须是 UTC 的。 */
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC);

    private IcebergProcedures() {
    }

    /** 一步执行：动作 wire 名 + 可直接交给 Spark 的语句。 */
    public record Step(String action, String statement) {
    }

    /**
     * 生成一张表的全部步骤。
     *
     * @param catalog 批次所属 catalog，同时是 Spark 会话里 {@code spark.sql.catalog.<name>} 的后缀
     * @param table   表在 catalog 内的限定名（{@code namespace.table}，多级 namespace 用点号拼接）
     * @param actions 该表要执行的动作
     * @param options 按动作给出的合并后全部参数
     * @param now     窗口的基准时刻（由调用方给一次，三步共用，避免批内时钟漂移）
     */
    public static List<Step> steps(String catalog, String table, List<String> actions,
                                   Map<String, Map<String, String>> options, Instant now) {
        requireName(catalog, "catalog");
        requireName(table, "table");
        List<Step> steps = new ArrayList<>();
        for (String action : ORDER) {
            if (actions == null || !actions.contains(action)) {
                continue;
            }
            Map<String, String> values = options == null || options.get(action) == null
                    ? Map.of() : options.get(action);
            steps.add(new Step(action, statement(catalog, action, table, values, now)));
        }
        return steps;
    }

    /** 单个动作的语句。{@code action} 用 wire 名。 */
    public static String statement(String catalog, String action, String table,
                                   Map<String, String> values, Instant now) {
        Map<String, Arg> args = new LinkedHashMap<>();
        args.put("table", Arg.text(table));
        switch (action) {
            case COMPACT -> compactArgs(args, values);
            case EXPIRE -> expireArgs(args, values, now);
            case ORPHAN -> orphanArgs(args, values, now);
            default -> throw new IllegalArgumentException("unsupported optimization action: " + action);
        }
        return "CALL " + catalog + ".system." + PROCEDURES.get(action) + arguments(args);
    }

    // ------------------------------------------------------------------ 各动作的参数

    private static void compactArgs(Map<String, Arg> args, Map<String, String> values) {
        String strategy = text(values, P_STRATEGY, STRATEGY_BINPACK).toLowerCase(Locale.ROOT);
        // 三个取值里只有 binpack 是 Iceberg 的 strategy 名。sort 与 zorder 都必须传
        // strategy=sort：zorder 只是 sort_order 的一种写法，不是独立的 strategy。
        switch (strategy) {
            case STRATEGY_SORT -> {
                args.put("strategy", Arg.text(STRATEGY_SORT));
                args.put("sort_order", Arg.text(ascending(required(values, P_SORT_COLUMNS,
                        "sort-columns is required when strategy is sort"))));
            }
            case STRATEGY_ZORDER -> {
                args.put("strategy", Arg.text(STRATEGY_SORT));
                args.put("sort_order", Arg.text("zorder(" + columns(required(values, P_SORT_COLUMNS,
                        "sort-columns is required when strategy is zorder")) + ")"));
            }
            case STRATEGY_BINPACK -> {
                // 不传 strategy：Iceberg 默认就是 binpack，显式传一遍只会多一处要跟随上游改的地方
            }
            default -> throw new IllegalArgumentException("unsupported compaction strategy: " + strategy);
        }
        Map<String, String> options = new LinkedHashMap<>();
        options.put("min-input-files", required(values, P_MIN_INPUT_FILES, "min-input-files is required"));
        options.put("target-file-size-bytes", Long.toString(
                Long.parseLong(required(values, P_TARGET_FILE_SIZE_MB, "target-file-size-mb is required"))
                        * 1024L * 1024L));
        options.put("max-concurrent-file-group-rewrites", required(values, P_MAX_CONCURRENT_REWRITES,
                "max-concurrent-file-group-rewrites is required"));
        args.put("options", Arg.map(options));
    }

    private static void expireArgs(Map<String, Arg> args, Map<String, String> values, Instant now) {
        args.put("older_than", Arg.timestamp(windowStart(now, required(values, P_RETENTION_HOURS,
                "retention-hours is required"))));
        args.put("retain_last", Arg.number(required(values, P_RETAIN_LAST, "retain-last is required")));
    }

    private static void orphanArgs(Map<String, Arg> args, Map<String, String> values, Instant now) {
        args.put("older_than", Arg.timestamp(windowStart(now, required(values, P_OLDER_THAN_HOURS,
                "older-than-hours is required"))));
        // 干跑是默认档：三项动作里只有它会删数据文件，第一次执行只列不删。
        args.put("dry_run", Arg.flag(Boolean.parseBoolean(text(values, P_DRY_RUN, "true"))));
    }

    /** 窗口起点：UTC 的 {@code now - hours}。 */
    private static String windowStart(Instant now, String hours) {
        long amount;
        try {
            amount = Long.parseLong(hours.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("window must be a number of hours: " + hours);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("window must be positive, got " + amount + " hour(s)");
        }
        return TIMESTAMP.format(now.minus(amount, ChronoUnit.HOURS));
    }

    /** 排序列：{@code a,b} → {@code a ASC NULLS LAST, b ASC NULLS LAST}。 */
    private static String ascending(String columns) {
        List<String> parts = new ArrayList<>();
        for (String column : columns(columns).split(",")) {
            parts.add(column + " ASC NULLS LAST");
        }
        return String.join(", ", parts);
    }

    /** 只做形状校验：列名来自用户输入，非法字符在 SQL 里就是注入面。 */
    private static String columns(String value) {
        String trimmed = value.trim();
        for (String column : trimmed.split(",")) {
            String name = column.trim();
            if (name.isEmpty() || !name.matches("[A-Za-z0-9_]+")) {
                throw new IllegalArgumentException("illegal column name in sort-columns: " + value);
            }
        }
        return trimmed.replace(" ", "");
    }

    // ------------------------------------------------------------------ 渲染

    private enum Kind {
        STRING, NUMBER, BOOLEAN, TIMESTAMP, MAP
    }

    private record Arg(String value, Kind kind) {

        static Arg text(String value) {
            return new Arg(value, Kind.STRING);
        }

        static Arg number(String value) {
            return new Arg(value, Kind.NUMBER);
        }

        static Arg flag(boolean value) {
            return new Arg(Boolean.toString(value), Kind.BOOLEAN);
        }

        static Arg timestamp(String value) {
            return new Arg(value, Kind.TIMESTAMP);
        }

        static Arg map(Map<String, String> values) {
            List<String> parts = new ArrayList<>();
            values.forEach((key, value) -> parts.add(quote(key) + ", " + quote(value)));
            return new Arg("map(" + String.join(", ", parts) + ")", Kind.MAP);
        }

        String render() {
            return switch (kind) {
                case NUMBER, BOOLEAN, MAP -> value;
                case TIMESTAMP -> "TIMESTAMP '" + value.replace("'", "''") + "'";
                case STRING -> quote(value);
            };
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }

    private static String arguments(Map<String, Arg> args) {
        List<String> parts = new ArrayList<>();
        args.forEach((name, arg) -> parts.add(name + " => " + arg.render()));
        return "(" + String.join(", ", parts) + ")";
    }

    // ------------------------------------------------------------------ 取值

    private static String text(Map<String, String> values, String key, String fallback) {
        String value = values == null ? null : values.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String required(Map<String, String> values, String key, String message) {
        String value = text(values, key, null);
        if (value == null) {
            throw new IllegalArgumentException(message + " (option '" + key + "' is missing)");
        }
        return value;
    }

    private static void requireName(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " name must not be empty");
        }
        if (value.contains("'") || value.contains("`") || value.contains(";")
                || value.contains("\n") || value.contains(" ")) {
            throw new IllegalArgumentException("illegal " + what + " name: " + value);
        }
    }
}
