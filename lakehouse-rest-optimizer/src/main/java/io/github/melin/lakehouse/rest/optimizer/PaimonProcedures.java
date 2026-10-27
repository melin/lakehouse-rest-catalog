package io.github.melin.lakehouse.rest.optimizer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
 *
 * <p><b>「保留最近 N 小时」有两个不同的入口，两个 procedure 各只能用其中一个。</b>
 * {@code older_than} 的语义是「窗口起点**这个时刻**」，Paimon 用
 * {@code DateTimeUtils.parseTimestampData(..., TimeZone.getDefault())} 解析
 * （2.0.0：{@code ProcedureUtils.fillInSnapshotOptions} 与
 * {@code OrphanFilesClean.olderThanMillis}），传 {@code '24 h'} 会在**执行期**抛
 * {@code DateTimeParseException: Text '24 h' could not be parsed at index 0}；
 * 收时长的键是表选项 {@code snapshot.time-retained}。于是：
 *
 * <ul>
 *   <li>{@code expire_snapshots} 有形参 {@code options}，用
 *       {@code options => 'snapshot.time-retained=24 h'}——时长交给 Paimon 解析，
 *       窗口在**执行瞬间**按当前时刻折算（正好 N 小时），也不必由我们格式化时间戳；</li>
 *   <li>{@code remove_orphan_files} 没有 {@code options}，只有 {@code older_than}，
 *       那边只能由任务侧算成绝对时刻，见 {@link #windowStart}。</li>
 * </ul>
 *
 * <p>两者在 {@code ExpireSnapshotsProcedure} 内部最终都折算成
 * {@code ExpireConfig.snapshotTimeRetain}，差别在折算时刻：选项形式以执行时的
 * {@code System.currentTimeMillis()} 为基准，而 {@code older_than} 会把
 * {@code now - olderThan} 当成时长——这个 {@code now} 是执行瞬间，于是窗口会**多出**
 * 「批次开始到这一步实际执行」的那段时间。方向偏保守（多留不少删），但既然我们表达的是
 * 「保留 N 小时」，就该用选项形态。
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
    /**
     * 保留窗口的时长。这是 Paimon 里**唯一**接受 {@code '24 h'} / {@code '7 d'} 这类时长的键，
     * 与 {@code older_than}（绝对时刻）是两回事，见类注释。
     */
    private static final String O_SNAPSHOT_TIME_RETAINED = "snapshot.time-retained";

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
     * @param now              orphan 窗口的基准时刻（由调用方给一次，批内各表共用，避免时钟漂移）
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
            case EXPIRE -> expireArgs(args, values);
            case ORPHAN -> orphanArgs(args, values, now);
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
        // 保留窗口走表选项 snapshot.time-retained：它收时长，且由 Paimon 在执行瞬间折算成
        // 「早于该时刻的快照过期」。用 older_than 也能表达同一件事（我们算好一个绝对时刻），
        // 但那是「窗口起点」而不是「保留多久」——compaction 跑多久，窗口就宽多久，
        // 而且要自己格式化时间戳（格式化时区与解析时区一旦不一致，窗口就整体平移）。
        args.put("options", Arg.text(O_SNAPSHOT_TIME_RETAINED + "="
                + hours(values, P_RETENTION_HOURS, "retention-hours is required") + " h"));
        // retain_min 是整数形参，必须写裸数字。
        args.put("retain_min", Arg.number(required(values, P_RETAIN_MIN, "retain-min is required")));
        // retain_max 刻意不传：本工程的策略只表达「保留窗口」与「最少保留个数」两个概念，
        // 而 retain_max 会额外限制「最多留几个快照」。替用户猜一个上限，会在快照变多时
        // 悄悄删掉还在窗口内的快照——这正是「保留窗口」这个设置想避免的事。
        // 不传时 Paimon 回落表的 snapshot.num-retained.max，保持表本身的语义。
    }

    private static void orphanArgs(Map<String, Arg> args, Map<String, String> values, Instant now) {
        args.put("older_than", Arg.text(windowStart(now,
                hours(values, P_OLDER_THAN_HOURS, "older-than-hours is required"))));
        args.put("dry_run", Arg.flag(Boolean.parseBoolean(text(values, P_DRY_RUN, "true"))));
    }

    /**
     * 保留窗口的小时数。
     *
     * <p>取值来自策略，写错（{@code 'abc'}、{@code 0}、负数）要在**生成语句时**就失败：
     * 把 {@code 'abc h'} 发到集群上才炸的话，同一批次里排在它前面的步骤已经跑完了，
     * 而重试会把那些步骤再跑一遍（compaction 不是幂等操作）。
     */
    private static long hours(Map<String, String> values, String key, String message) {
        String raw = required(values, key, message);
        long amount;
        try {
            amount = Long.parseLong(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("window must be a number of hours: " + raw);
        }
        if (amount <= 0) {
            throw new IllegalArgumentException("window must be positive, got " + amount + " hour(s)");
        }
        return amount;
    }

    /**
     * 窗口起点：{@code now - hours} 的**绝对时刻**，写成 Paimon 认的
     * {@code yyyy-MM-dd HH:mm:ss}（本地时间）。只给 {@code remove_orphan_files} 用——
     * 它没有 {@code options} 形参，表达不了「保留多久」，只能给一个时刻。
     *
     * <p><b>为什么不是相对时长（{@code '24 h'}）。</b>Paimon 2.0.0 把 {@code older_than}
     * 当时间戳解析：{@code RemoveOrphanFilesProcedure} 走
     * {@code OrphanFilesClean.olderThanMillis} →
     * {@code DateTimeUtils.parseTimestampData(olderThan, 3, TimeZone.getDefault())}，
     * **没有「时长」这个分支**。曾经这里拼的是 {@code '24 h'}（expire 与 orphan 共用），
     * 于是集群上以 {@code DateTimeParseException: Text '24 h' could not be parsed at index 0}
     * 失败。
     *
     * <p><b>为什么用 JVM 默认时区而不是 UTC。</b>解析那边取的是
     * {@code TimeZone.getDefault()}——即**执行这个 procedure 的同一个 JVM** 的默认时区
     * （procedure 就在 driver 进程里跑，见调用栈里 {@code SparkSession.sql}）。格式化与
     * 解析同进程同时区，往返严格相等；反过来若这里按 UTC 格式化，而容器 TZ 是 +08:00，
     * 窗口会平移 8 小时，orphan 清理就会多删文件。注意这跟
     * {@code spark.sql.session.timeZone} 无关，Paimon 不看那个设置——Iceberg 侧才看，
     * 所以 {@link IcebergProcedures} 反过来钉 UTC。
     */
    private static String windowStart(Instant now, long hours) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(now.minus(hours, ChronoUnit.HOURS));
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
