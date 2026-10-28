package io.github.melin.lakehouse.rest.optimizer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「表 + 动作 + 生效参数」翻译成 Lance 的 {@code OPTIMIZE} / {@code VACUUM} 语句。
 *
 * <p><b>这里不是 {@code CALL}，是解析器级命令。</b>Paimon 与 Iceberg 的维护动作是
 * procedure，语句形状是 {@code CALL ...}；Lance 的 Spark connector 自带一套 ANTLR 语法
 * （{@code LanceSqlExtensions.g4}），把 {@code OPTIMIZE} 与 {@code VACUUM} 注册成
 * {@code SparkSessionExtensions} 的 parser 扩展。因此：
 *
 * <ul>
 *   <li>它先被 Spark 自己的 {@code SqlBase} 解析器试一遍，{@code ParseException} 之后才落到
 *       Lance 的 parser（{@code LanceSparkSqlExtensionsParser.parsePlan}）；</li>
 *   <li>语句里**不能有分号、不能有注释**——{@code CALL} 容忍它们，纯语法命令不容忍；</li>
 *   <li>参数不是「形参名 → 值」而是 {@code WITH (k = v, ...)}，且值必须是**字面量**：
 *       布尔写 {@code TRUE/FALSE}（写成 {@code 'true'} 会以
 *       {@code Cannot cast STRING to BOOLEAN} 失败），整数写裸数字。</li>
 * </ul>
 *
 * <p><b>类名与语句形状对应 connector 0.4.0</b>（{@code org.lance:lance-spark-3.5_2.12}）。
 * 两处易碎的事实，写在这里是为了升级时能一眼看出要复验什么：
 *
 * <ol>
 *   <li>语法可接受的名字是 **connector 决定**的。{@code OPTIMIZE} 认
 *       {@code target_rows_per_fragment}/{@code max_rows_per_group}/{@code max_bytes_per_file}/
 *       {@code materialize_deletions}/{@code materialize_deletions_threshold}/{@code num_threads}/
 *       {@code batch_size}/{@code defer_index_remap}（见 0.4.0 的 {@code OptimizeExec.buildOptions}）。
 *       <b>没有 {@code max_source_fragments}</b>——{@code CompactionOptions} 有这个字段，
 *       但 Spark 侧没把它接出来，写了也是静默忽略。因此策略层也不提供它
 *       （见 {@code LanceOptimizationOptions} 的类注释）。</li>
 *   <li>输出列名：{@code OPTIMIZE} 给 {@code fragments_removed} / {@code fragments_added} /
 *       {@code files_removed} / {@code files_added}，{@code VACUUM} 给
 *       {@code bytes_removed} / {@code old_versions}。</li>
 * </ol>
 *
 * <p><b>为什么仍然走 SQL 而不是 Java API。</b>与另两种格式同一条理由：connector 的版本由
 * 目标集群决定，把语句交给它自己解释，本模块的编译产物就不绑定某个 connector 版本。
 * 这个类里没有任何 {@code org.lance} 的 import——正是这条边界的证据。
 */
public final class LanceProcedures {

    /** 两项动作的 wire 名；与服务端 API 的 {@code action}、库里的 {@code actions} 列同一套词。 */
    public static final String COMPACT = "compact";
    public static final String EXPIRE = "expire";
    /** 在这张格式上不存在（{@code VACUUM} 已包含），保留常量只为让拒绝信息能指名道姓。 */
    public static final String ORPHAN = "orphan";

    /**
     * 批内动作顺序：{@code compact → expire}。
     *
     * <p>与另两种格式同一个理由：{@code OPTIMIZE} 先生成新版本，{@code VACUUM} 才基于
     * 最新状态判「哪些版本过期」；反过来先 VACUUM 会把刚要合并的版本当成过期删掉。
     * {@code orphan} 不在这张单子里——它不会被翻译成任何语句。
     */
    private static final List<String> ORDER = List.of(COMPACT, EXPIRE);

    /** 语句关键字 → 动作。用枚举而不是字符串分支，是为了「加一个关键字」时编译器能提示。 */
    private enum Command {
        OPTIMIZE("OPTIMIZE"), VACUUM("VACUUM");

        private final String keyword;

        Command(String keyword) {
            this.keyword = keyword;
        }
    }

    // ------------------------------------------------------------------ 策略键（kebab）

    private static final String P_TARGET_ROWS_PER_FRAGMENT = "target-rows-per-fragment";
    private static final String P_MAX_ROWS_PER_GROUP = "max-rows-per-group";
    private static final String P_MATERIALIZE_DELETIONS = "materialize-deletions";
    private static final String P_MATERIALIZE_DELETIONS_THRESHOLD = "materialize-deletions-threshold";
    private static final String P_DEFER_INDEX_REMAP = "defer-index-remap";
    private static final String P_BATCH_SIZE = "batch-size";
    private static final String P_RETENTION_HOURS = "retention-hours";

    /**
     * 每项动作认得的键：认不得的直接失败，而不是「过滤掉继续跑」。
     *
     * <p>{@code interval-hours} / {@code interval-days} 是「两次执行间隔」，服务端用它算
     * 调度窗口，执行侧没有对应概念——它们仍然算「认得的键」（服务端下发的合并结果里带着
     * 它们，判成未知键会让整表失败），只是渲染语句时跳过：{@link #compactArgs} 与
     * {@link #vacuumArgs} 逐键取值，没提到的键自然不出现。
     */
    private static final Map<String, List<String>> KNOWN_KEYS = Map.of(
            COMPACT, List.of(P_TARGET_ROWS_PER_FRAGMENT, P_MAX_ROWS_PER_GROUP, P_MATERIALIZE_DELETIONS,
                    P_MATERIALIZE_DELETIONS_THRESHOLD, P_DEFER_INDEX_REMAP, P_BATCH_SIZE,
                    "interval-hours"),
            EXPIRE, List.of(P_RETENTION_HOURS, "delete-unverified", "interval-days"));

    private LanceProcedures() {
    }

    /** 一步执行：动作 wire 名 + 可直接交给 Spark 的语句。 */
    public record Step(String action, String statement) {
    }

    /**
     * 生成一张表的全部步骤。
     *
     * @param catalog 批次所属 catalog，同时是 Spark 会话里 {@code spark.sql.catalog.<name>} 的后缀
     * @param table   表在 catalog 内的限定名（{@code schema.table}，**不含** catalog 前缀）
     * @param actions 该表要执行的动作
     * @param options 按动作给出的合并后全部参数
     * @param now     窗口基准时刻（由调用方给一次，两步共用，避免批内时钟漂移）
     */
    public static List<Step> steps(String catalog, String table, List<String> actions,
                                   Map<String, Map<String, String>> options, Instant now) {
        requireName(catalog, "catalog");
        requireName(table, "table");
        rejectOrphan(actions);
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
        rejectUnknownKeys(action, values);
        Map<String, String> args = new LinkedHashMap<>();
        Command command;
        switch (action) {
            case COMPACT -> {
                command = Command.OPTIMIZE;
                compactArgs(args, values);
            }
            case EXPIRE -> {
                command = Command.VACUUM;
                vacuumArgs(args, values, now);
            }
            case ORPHAN -> throw new IllegalArgumentException(orphanReason());
            default -> throw new IllegalArgumentException("unsupported optimization action: " + action);
        }
        return command.keyword + " " + qualified(catalog, table) + with(args);
    }

    // ------------------------------------------------------------------ 各动作的参数

    /**
     * {@code OPTIMIZE} 的参数。
     *
     * <p>{@code batch-size} **不给就不写**：它在 connector 里是 {@code Option}，不传就用
     * 写入侧默认；给一个值会让输出文件按字节截断，与 {@code target_rows_per_fragment}
     * 一起用容易产出「行数没到但文件满了」的碎片，下一轮再次成为候选。
     */
    private static void compactArgs(Map<String, String> args, Map<String, String> values) {
        args.put("target_rows_per_fragment",
                integer(values, P_TARGET_ROWS_PER_FRAGMENT, "target-rows-per-fragment is required"));
        args.put("max_rows_per_group",
                integer(values, P_MAX_ROWS_PER_GROUP, "max-rows-per-group is required"));
        args.put("materialize_deletions",
                flag(values, P_MATERIALIZE_DELETIONS, "materialize-deletions is required"));
        args.put("materialize_deletions_threshold", decimal(values, P_MATERIALIZE_DELETIONS_THRESHOLD,
                "materialize-deletions-threshold is required"));
        args.put("defer_index_remap",
                flag(values, P_DEFER_INDEX_REMAP, "defer-index-remap is required"));
        String batchSize = text(values, P_BATCH_SIZE, null);
        if (batchSize != null) {
            args.put("batch_size", integer(values, P_BATCH_SIZE, "batch-size must be an integer"));
        }
    }

    /**
     * {@code VACUUM} 的参数。
     *
     * <p><b>两个常量，都不是可配项：</b>
     *
     * <ul>
     *   <li>{@code delete_unverified = FALSE}——{@code true} 的含义是「删掉验证不了引用关系的
     *       文件」，那是「反正我要腾空间」的场景；我们是定期维护，没有理由承担这个风险。
     *       策略里的 {@code delete-unverified} 只接受 {@code false}，这里也恒写 {@code FALSE}：</li>
     *   <li>{@code error_if_tagged_old_versions = TRUE}——待删的版本里有打标签的，整个
     *       {@code VACUUM} 报错退出。标签是用户手动留的回滚点，静默删掉它比这次维护失败
     *       严重得多；失败信息会进 item 的 {@code error_message}，用户去删标签或调窗口后重试。
     *       注意 connector 的默认值是 {@code false}，所以这一条必须显式写。</li>
     * </ul>
     *
     * <p>窗口写成 {@code now - retentionHours} 的 **epoch 毫秒**：这条语句是三个格式里唯一
     * 不用操心时区的地方（Paimon 按 JVM 默认时区解析时间戳、Iceberg 按会话时区，
     * 两个都踩过坑），{@code before_timestamp_millis} 是绝对值，格式化与解析都不存在。
     */
    private static void vacuumArgs(Map<String, String> args, Map<String, String> values, Instant now) {
        args.put("before_timestamp_millis", Long.toString(windowStart(now,
                hours(values, P_RETENTION_HOURS, "retention-hours is required")).toEpochMilli()));
        args.put("delete_unverified", "FALSE");
        args.put("error_if_tagged_old_versions", "TRUE");
    }

    /**
     * 窗口起点：{@code now - hours}。
     *
     * <p>取值写错（{@code 'abc'}、{@code 0}、负数）在**生成语句时**就失败：发到集群上才炸的话，
     * 同一批次里排在它前面的步骤已经跑完了，而重试会把那些步骤再跑一遍。
     */
    private static Instant windowStart(Instant now, long hours) {
        return now.minus(hours, ChronoUnit.HOURS);
    }

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

    // ------------------------------------------------------------------ 渲染

    /**
     * 三段名逐段加反引号。
     *
     * <p>为什么与 Paimon 的 {@code $files} 相反（那边只包最后一段）：{@code OPTIMIZE} 的表名是
     * 一个**多段标识符**，逐段包才是「三段」；整串包会变成单段标识符，解析器当成一张名叫
     * {@code demo.sales.orders} 的表。而逐段包必须做，因为段内可能撞保留字
     * （{@code order}、{@code table}），且 {@code -} 在未加引号标识符里非法。
     *
     * <p>反引号本身不允许出现在段内：Spark 的反引号标识符用「两个反引号」转义，
     * 而我们没有任何合法 id 需要它——在这里拒绝，比在拼接时做转义更容易证明没有注入面。
     */
    static String qualified(String catalog, String table) {
        List<String> parts = new ArrayList<>();
        parts.add(quote(catalog, "catalog"));
        for (String part : table.split("\\.", -1)) {
            parts.add(quote(part, "table"));
        }
        return String.join(".", parts);
    }

    private static String quote(String segment, String what) {
        requireName(segment, what);
        return "`" + segment + "`";
    }

    /** {@code WITH (k = v, ...)}；没有参数时**整块不写**（{@code WITH ()} 虽然是合法语法，但没必要）。 */
    private static String with(Map<String, String> args) {
        if (args.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        args.forEach((key, value) -> parts.add(key + " = " + value));
        return " WITH (" + String.join(", ", parts) + ")";
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

    /**
     * 整数字面量：先解析再回写，保证语句里出现的一定是裸数字。
     *
     * <p>不直接把策略里的字符串拼进去：{@code WITH} 子句里 {@code 1_048_576} 或
     * {@code 1e6} 都不是 {@code BIGINT_LITERAL}，解析期就会失败，而那种失败信息
     * （{@code mismatched input}）不会告诉你是哪个键写错了。
     */
    private static String integer(Map<String, String> values, String key, String message) {
        String raw = required(values, key, message);
        try {
            return Long.toString(Long.parseLong(raw.trim()));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be an integer: " + raw);
        }
    }

    /**
     * 浮点字面量。
     *
     * <p>connector 那边按 {@code java.lang.Float} 取值（{@code t.value.asInstanceOf[Float]}），
     * 而**裸写 {@code 1} 会被词法分析成 {@code BIGINT_LITERAL}**（{@code Long}），
     * 于是运行期在 exec 里抛 {@code ClassCastException}——报错栈里没有任何一句提到我们。
     * 所以这里一律输出带小数点的形状（{@code 1} → {@code 1.0}），并且用
     * {@link BigDecimal#toPlainString()} 避免 {@code 1.0E-7} 这类科学计数法
     * （词法器不认）。
     */
    private static String decimal(Map<String, String> values, String key, String message) {
        String raw = required(values, key, message);
        BigDecimal parsed;
        try {
            parsed = new BigDecimal(raw.trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(key + " must be a number: " + raw);
        }
        BigDecimal stripped = parsed.stripTrailingZeros();
        String plain = stripped.toPlainString();
        return plain.indexOf('.') >= 0 ? plain : plain + ".0";
    }

    private static String flag(Map<String, String> values, String key, String message) {
        String raw = required(values, key, message);
        if (!"true".equalsIgnoreCase(raw) && !"false".equalsIgnoreCase(raw)) {
            throw new IllegalArgumentException(key + " must be true or false: " + raw);
        }
        return Boolean.parseBoolean(raw) ? "TRUE" : "FALSE";
    }

    /**
     * 不认识的键直接失败。
     *
     * <p>服务端只会下发本动作认得的键，所以走到这里说明两边的键表已经不一致了。
     * 静默过滤的后果是「用户配了、页面显示生效、执行时没发生」——那是最难排查的一类
     * 静默失效；宁可整表失败并把键名打在 {@code error_message} 里。
     */
    private static void rejectUnknownKeys(String action, Map<String, String> values) {
        List<String> known = KNOWN_KEYS.getOrDefault(action, List.of());
        if (values == null) {
            return;
        }
        for (String key : values.keySet()) {
            if (key == null || !known.contains(key.trim())) {
                throw new IllegalArgumentException("unknown " + action + " option: " + key
                        + ", supported: " + String.join(", ", known));
            }
        }
    }

    /** Lance 上 {@code orphan} 不可用；清单里出现它就是服务端的契约被破坏。 */
    private static void rejectOrphan(List<String> actions) {
        if (actions != null && actions.contains(ORPHAN)) {
            throw new IllegalArgumentException(orphanReason());
        }
    }

    private static String orphanReason() {
        return "action orphan is not supported on Lance tables: VACUUM already removes both"
                + " expired versions and unreferenced files";
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
