package io.github.melin.lakehouse.rest.optimizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把「表 + 动作 + 生效参数」翻译成 Paimon 的 {@code CALL} 语句。
 *
 * <p><b>为什么用 SQL 而不是 Paimon 的 Java API。</b>procedure 的参数名与取值语义
 * 随 Paimon 版本变（本仓库钉的 2.0.0 与 1.4.x 就不完全一样），而 {@code CALL} 这条
 * 通路由**目标集群上的 Paimon** 解释——集群是什么版本就按什么版本的语义执行。
 * 若在这里依赖某个版本的 Java 类，本模块的编译产物就把 Paimon 版本焊死了，
 * 升级集群必须重新发版。
 *
 * <p><b>参数类型必须逐项对上。</b>Spark 的 {@code CALL} 只接受字面量，而且会把实参
 * 按 procedure 声明的形参类型做转换，因此三个 procedure 的参数分三类渲染：
 * 字符串加单引号、整数写成裸数字、布尔写成 {@code true/false}。
 * 对照的形参类型（从 2.0.0 的 class 文件反解，别凭印象改）：
 *
 * <ul>
 *   <li>{@code compact}: 八个参数**全是 StringType**（含 {@code options}，
 *       它是 {@code k1=v1,k2=v2} 形式的字符串，不是 map 字面量）；
 *   <li>{@code expire_snapshots}: {@code table}/{@code older_than}/{@code options}
 *       是字符串，{@code retain_max}/{@code retain_min}/{@code max_deletes} 是整数；
 *   <li>{@code remove_orphan_files}: {@code table}/{@code older_than}/{@code mode}
 *       是字符串，{@code dry_run} 是布尔，{@code parallelism} 是整数。
 * </ul>
 *
 * <p>把 {@code retain_min} 写成 {@code '10'}（带引号）在 ANSI 模式下会直接抛转换错误，
 * 而在非 ANSI 模式下静默走一次 string→int 转换——两种表现都不该依赖，所以按类型渲染。
 */
public final class PaimonProcedures {

    /**
     * 三项动作的 **wire 名**：与服务端 API 的 {@code action} 字段、库里的 {@code actions} 列、
     * 控制台的展示名是同一套词。清单里的动作用的就是这套。
     */
    public static final String COMPACT = "compact";
    public static final String EXPIRE = "expire";
    public static final String ORPHAN = "orphan";

    /**
     * wire 名 → Paimon 的 procedure 名。
     *
     * <p>为什么两套名字要分开摆在这里：策略层的动作名是**契约**（改名等于改 API），
     * 而 procedure 名是**目标集群 Paimon 的实现细节**（{@code expire_snapshots} 而不是
     * {@code expire}，将来还可能变）。把映射收在一处，翻译出错时就只有这一个地方可看；
     * 散在 SQL 拼接里则会出现「一半用契约名、一半用 procedure 名」的不一致。
     */
    private static final Map<String, String> PROCEDURES = Map.of(
            COMPACT, "compact",
            EXPIRE, "expire_snapshots",
            ORPHAN, "remove_orphan_files");

    /**
     * 一批内的动作执行顺序。
     *
     * <p>顺序是写死的语义而不是偏好：compaction 产生新快照后，expire 才能基于最新状态
     * 判过期；orphan 放最后，它「只删早于窗口的文件」这条约束天然保护前两步刚产生的文件。
     * 顺序错了会删掉还需要的数据，因此不给配置项。
     */
    private static final List<String> ORDER = List.of(COMPACT, EXPIRE, ORPHAN);

    private static final String P_STRATEGY = "strategy";
    private static final String P_MIN_INPUT_FILES = "min-input-files";
    private static final String P_TARGET_FILE_SIZE_MB = "target-file-size-mb";
    private static final String P_SORT_COLUMNS = "sort-columns";
    private static final String P_RETENTION_HOURS = "retention-hours";
    private static final String P_RETAIN_MIN = "retain-min";
    private static final String P_OLDER_THAN_HOURS = "older-than-hours";
    private static final String P_INTERVAL_DAYS = "interval-days";
    private static final String P_DRY_RUN = "dry-run";

    /** Paimon 的表选项名。翻译发生在这里，服务端只认「策略参数名」。 */
    private static final String O_MIN_FILE_NUM = "compaction.min.file-num";
    private static final String O_TARGET_FILE_SIZE = "target-file-size";

    private PaimonProcedures() {
    }

    /** 一步执行：动作 wire 名 + 可直接交给 Spark 的语句。 */
    public record Step(String action, String statement) {
    }

    /**
     * 生成一张表的全部步骤。
     *
     * @param catalog          批次所属 catalog，同时是 Spark 会话里 {@code spark.sql.catalog.<name>} 的后缀
     * @param table            表在 catalog 内的限定名（{@code database.table}，**不含** catalog 前缀）
     * @param actions          该表要执行的动作（服务端已按批内交集的语义裁剪过）
     * @param options          按动作给出的**合并后全部参数**
     */
    public static List<Step> steps(String catalog, String table, List<String> actions,
                                   Map<String, Map<String, String>> options) {
        requireName(catalog, "catalog");
        requireName(table, "table");
        List<Step> steps = new ArrayList<>();
        for (String action : ORDER) {
            if (actions == null || !actions.contains(action)) {
                continue;
            }
            Map<String, String> values = options == null || options.get(action) == null
                    ? Map.of() : options.get(action);
            steps.add(new Step(action, statement(catalog, action, table, values)));
        }
        return steps;
    }

    /** 单个动作的语句。{@code action} 用 wire 名。 */
    public static String statement(String catalog, String action, String table, Map<String, String> values) {
        Map<String, Arg> args = new LinkedHashMap<>();
        args.put("table", Arg.text(table));
        switch (action) {
            case COMPACT -> compactArgs(args, values);
            case EXPIRE -> expireArgs(args, values);
            case ORPHAN -> orphanArgs(args, values);
            default -> throw new IllegalArgumentException("unsupported optimization action: " + action);
        }
        return "CALL " + catalog + ".sys." + PROCEDURES.get(action) + arguments(args);
    }

    // ------------------------------------------------------------------ 各动作的参数

    private static void compactArgs(Map<String, Arg> args, Map<String, String> values) {
        String strategy = text(values, P_STRATEGY, "binpack");
        if ("sort".equalsIgnoreCase(strategy)) {
            // 排序合并：compact_strategy 只接受 full/minor，排序意图靠 order_strategy 表达。
            args.put("compact_strategy", Arg.text("full"));
            args.put("order_strategy", Arg.text("ORDER"));
            args.put("order_by", Arg.text(required(values, P_SORT_COLUMNS,
                    "sort-columns is required when strategy is sort")));
        }
        // strategy=binpack 时不传 compact_strategy：让 Paimon 自己按表类型选
        // （unaware-bucket append 表要 full，主键表走 LSM），比我们替它决定更准。
        Map<String, String> options = new LinkedHashMap<>();
        options.put(O_MIN_FILE_NUM, required(values, P_MIN_INPUT_FILES, "min-input-files is required"));
        options.put(O_TARGET_FILE_SIZE, required(values, P_TARGET_FILE_SIZE_MB,
                "target-file-size-mb is required") + " mb");
        args.put("options", Arg.text(joinOptions(options)));
    }

    private static void expireArgs(Map<String, Arg> args, Map<String, String> values) {
        args.put("older_than", Arg.text(duration(required(values, P_RETENTION_HOURS,
                "retention-hours is required"), "h")));
        // retain_min 是整数形参，必须写裸数字。
        args.put("retain_min", Arg.number(required(values, P_RETAIN_MIN, "retain-min is required")));
        // retain_max 刻意不传：本工程的策略只表达「保留窗口」与「最少保留个数」两个概念，
        // 而 retain_max 会额外限制「最多留几个快照」。替用户猜一个上限，会在快照变多时
        // 悄悄删掉还在窗口内的快照——这正是「保留窗口」这个设置想避免的事。
        // 不传时 Paimon 回落表的 snapshot.num-retained.max，保持表本身的语义。
    }

    private static void orphanArgs(Map<String, Arg> args, Map<String, String> values) {
        args.put("older_than", Arg.text(duration(required(values, P_OLDER_THAN_HOURS,
                "older-than-hours is required"), "h")));
        args.put("dry_run", Arg.flag(Boolean.parseBoolean(text(values, P_DRY_RUN, "true"))));
    }

    /**
     * 时间窗口的写法：Paimon 的时长语法（{@code <n> <单位>}）。
     *
     * <p>刻意不用绝对时间戳（{@code yyyy-MM-dd HH:mm:ss}）：那种形式要由任务侧按某个时区
     * 格式化、再由 Paimon 按会话时区解析，两个时区不一致时窗口会整体平移若干小时——
     * 而窗口平移在 orphan 清理上的后果是多删文件。相对时长没有时区，也就没有这个问题。
     */
    private static String duration(String amount, String unit) {
        return amount.trim() + " " + unit;
    }

    private static String joinOptions(Map<String, String> options) {
        List<String> parts = new ArrayList<>();
        options.forEach((key, value) -> parts.add(key + "=" + value));
        return String.join(",", parts);
    }

    // ------------------------------------------------------------------ 渲染

    private enum Kind {
        STRING, NUMBER, BOOLEAN
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

        String render() {
            return switch (kind) {
                case NUMBER, BOOLEAN -> value;
                // 单引号按 SQL 惯例用两个单引号转义；表名/排序列/参数值都可能来自用户输入。
                case STRING -> "'" + value.replace("'", "''") + "'";
            };
        }
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

    /**
     * 把服务端给的「catalog.库.表」切成 catalog 内的限定名。
     *
     * <p>服务端下发的表名带 catalog 前缀（它是批次内的唯一标识），而 {@code CALL} 里
     * 表名必须是 catalog 内的名字——会话已经指明是哪个 catalog，再写一遍前缀会解析失败。
     * 前缀对不上时直接失败而不是「去掉第一段试试」：这意味着任务拿到的清单与它连的
     * catalog 不是一回事，继续执行会去优化**另一张同名表**。
     */
    public static String withinCatalog(String catalog, String fullName) {
        if (fullName == null) {
            throw new IllegalArgumentException("table name must not be null");
        }
        String prefix = catalog + ".";
        if (!fullName.startsWith(prefix)) {
            throw new IllegalArgumentException("table " + fullName + " does not belong to catalog "
                    + catalog + ": the optimizer session is bound to one catalog, and a mismatched"
                    + " prefix would silently target a different table");
        }
        String within = fullName.substring(prefix.length());
        if (within.isBlank() || within.indexOf('.') < 0) {
            throw new IllegalArgumentException("table name must be catalog.database.table, got " + fullName);
        }
        return within;
    }

    /** 规范化动作名，非法值直接失败（服务端已经裁剪过，这里是第二道闸）。 */
    public static List<String> normalizeActions(List<String> actions) {
        List<String> normalized = new ArrayList<>();
        for (String action : actions == null ? List.<String>of() : actions) {
            String name = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
            if (!ORDER.contains(name)) {
                throw new IllegalArgumentException("unsupported optimization action: " + action);
            }
            if (!normalized.contains(name)) {
                normalized.add(name);
            }
        }
        return normalized;
    }
}
