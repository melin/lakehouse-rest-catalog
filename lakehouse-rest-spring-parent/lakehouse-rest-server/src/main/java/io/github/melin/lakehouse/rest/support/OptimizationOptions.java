package io.github.melin.lakehouse.rest.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 表存储优化的三项动作、参数键与默认值。
 *
 * <p><b>三层配置的收口点。</b>一项参数的最终取值由三层合并而来：
 * 内置默认（本类）→ catalog 默认（catalog 属性里 {@code optimization.*} 前缀的键，
 * 与 {@code table-default.*} 同一套做法）→ 表策略里显式写过的键。
 * 表级只存**差异**，所以「系统改了默认值」能自动作用到没配过的表上；
 * 合并只发生在 {@link #resolve} 一处，别处不许再拼配置。
 *
 * <p>参数键名与 Paimon 的选项名刻意不同（{@code min-input-files} 而不是
 * {@code compaction.min.file-num}）：这一层是**策略**参数，取值与含义要能对用户解释，
 * 而且要能在不知道 Paimon 选项名的情况下校验。策略到 procedure 参数的翻译
 * （{@code min-input-files} 进 {@code options} 映射等）发生在优化任务侧，那里才知道
 * 自己连的是哪个 Paimon 版本。
 *
 * <p>校验一律在写入前做（{@link #validate}），不做「存进去等跑的时候再报错」：
 * 优化任务跑在 k8s 上，配置错误的反馈路径是「批次失败 → 控制台看错误」，
 * 比在表单上直接拒绝贵得多。
 */
public final class OptimizationOptions {

    private OptimizationOptions() {
    }

    /** catalog 属性里承载优化默认值的前缀。 */
    public static final String CATALOG_PREFIX = "optimization.";

    /** 三项动作。{@code wire} 名用于 API 与数据库存储，改名等于改契约。 */
    public enum Action {

        COMPACT("compact", "Compaction"),
        EXPIRE("expire", "Snapshot Expire"),
        ORPHAN("orphan", "Orphan File Remove");

        private final String wire;
        private final String displayName;

        Action(String wire, String displayName) {
            this.wire = wire;
            this.displayName = displayName;
        }

        public String wire() {
            return wire;
        }

        public String displayName() {
            return displayName;
        }

        /** 按 wire 名解析；非法取值返回 {@code null}，由调用方决定报错文案。 */
        public static Action parse(String name) {
            if (name == null) {
                return null;
            }
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            for (Action action : values()) {
                if (action.wire.equals(normalized)) {
                    return action;
                }
            }
            return null;
        }
    }

    /**
     * 合法的动作名，逗号分隔，用于报错文案。
     *
     * <p>写在这里而不是各处的字符串字面量：报错文案是用户唯一能看到的「契约说明」，
     * 漏掉一个动作名就会让人以为那个动作不存在。
     */
    public static String actionNames() {
        List<String> names = new ArrayList<>();
        for (Action action : Action.values()) {
            names.add(action.wire());
        }
        return String.join(", ", names);
    }

    // ---------------------------------------------------------------- 参数键

    /** Compaction：{@code binpack}（小文件合并）或 {@code sort}（排序合并）。 */
    public static final String STRATEGY = "strategy";
    public static final String STRATEGY_BINPACK = "binpack";
    public static final String STRATEGY_SORT = "sort";
    /** Compaction：参与合并的最少小文件数。 */
    public static final String MIN_INPUT_FILES = "min-input-files";
    /** Compaction：目标文件大小（MB）。 */
    public static final String TARGET_FILE_SIZE_MB = "target-file-size-mb";
    /** Compaction：排序合并的排序列，逗号分隔。 */
    public static final String SORT_COLUMNS = "sort-columns";

    /** Snapshot Expire：快照保留时长（小时）。 */
    public static final String RETENTION_HOURS = "retention-hours";
    /** Snapshot Expire：最少保留的快照个数。 */
    public static final String RETAIN_MIN = "retain-min";
    /** Snapshot Expire / Compaction：两次执行的最小间隔（小时）。 */
    public static final String INTERVAL_HOURS = "interval-hours";

    /** Orphan File Remove：只清理早于该时长的文件（小时）。 */
    public static final String OLDER_THAN_HOURS = "older-than-hours";
    /** Orphan File Remove：执行间隔（天）。 */
    public static final String INTERVAL_DAYS = "interval-days";
    /** Orphan File Remove：是否只预览不删除。 */
    public static final String DRY_RUN = "dry-run";

    /** 每项动作允许出现的键，用于拒绝拼错的参数名。 */
    private static final Map<Action, Set<String>> KNOWN_KEYS = Map.of(
            Action.COMPACT, Set.of(STRATEGY, MIN_INPUT_FILES, TARGET_FILE_SIZE_MB, SORT_COLUMNS, INTERVAL_HOURS),
            Action.EXPIRE, Set.of(RETENTION_HOURS, RETAIN_MIN, INTERVAL_HOURS),
            Action.ORPHAN, Set.of(OLDER_THAN_HOURS, INTERVAL_DAYS, DRY_RUN));

    /** 这个参数名是不是该项动作认得的。用于校验「别人写进 catalog 属性的键」。 */
    public static boolean isKnownOption(Action action, String key) {
        return key != null && KNOWN_KEYS.get(action).contains(key.trim());
    }

    // ---------------------------------------------------------------- 按格式分派
    //
    // 三项动作的**名字**是格式无关的契约（API、库里的 actions 列、指标前缀都用它），
    // 但每项动作的参数键、默认值与合法取值都是格式各自的——Paimon 的 retain-min 与
    // Iceberg 的 retain-last 不是同一个东西。下面这组重载是唯一的格式分派点：
    // 调用方只需把 catalog 的格式传进来，不必知道背后有两套参数表。

    /** 内置默认值（按格式）。 */
    public static Map<String, String> defaults(CatalogFormat format, Action action) {
        return isIceberg(format) ? IcebergOptimizationOptions.defaults(action) : defaults(action);
    }

    /** catalog 属性里的默认值（按格式）。 */
    public static Map<String, String> catalogDefaults(Map<String, String> catalogProperties,
                                                      CatalogFormat format, Action action) {
        return isIceberg(format)
                ? IcebergOptimizationOptions.catalogDefaults(catalogProperties, action)
                : catalogDefaults(catalogProperties, action);
    }

    /** 三层合并（按格式）。 */
    public static Map<String, String> resolve(CatalogFormat format, Action action,
                                              Map<String, String> catalogProperties,
                                              Map<String, String> tableConfig) {
        if (!isIceberg(format)) {
            return resolve(action, catalogProperties, tableConfig);
        }
        Map<String, String> resolved = IcebergOptimizationOptions.defaults(action);
        resolved.putAll(IcebergOptimizationOptions.catalogDefaults(catalogProperties, action));
        if (tableConfig != null) {
            tableConfig.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank()
                        && IcebergOptimizationOptions.isKnownOption(action, key)) {
                    resolved.put(key.trim(), value.trim());
                }
            });
        }
        return resolved;
    }

    /** 校验（按格式）。 */
    public static void validate(CatalogFormat format, Action action, Map<String, String> config) {
        if (isIceberg(format)) {
            IcebergOptimizationOptions.validate(action, config);
        } else {
            validate(action, config);
        }
    }

    /** 某个键是不是该项动作认得的（按格式）。 */
    public static boolean isKnownOption(CatalogFormat format, Action action, String key) {
        return isIceberg(format)
                ? IcebergOptimizationOptions.isKnownOption(action, key)
                : isKnownOption(action, key);
    }

    /** 某项动作的合法键集合（按格式），用于报错文案与静态校验。 */
    public static Set<String> knownKeys(CatalogFormat format, Action action) {
        return isIceberg(format)
                ? IcebergOptimizationOptions.knownKeys(action)
                : KNOWN_KEYS.get(action);
    }

    /** 执行间隔（按格式）。 */
    public static Duration interval(CatalogFormat format, Action action, Map<String, String> effective) {
        return isIceberg(format)
                ? IcebergOptimizationOptions.interval(action, effective)
                : interval(action, effective);
    }

    private static boolean isIceberg(CatalogFormat format) {
        return format == CatalogFormat.ICEBERG;
    }

    /**
     * 新建策略时三项动作的默认开关。
     *
     * <p>孤儿文件清理默认**关闭**：三项动作里只有它会删数据文件，而它删除的依据
     * （「没被任何快照引用的文件」）是三者中最容易因时钟偏差或提交窗口判断错的。
     * 让用户为一个会删数据的动作显式按下开关，比让它默认开着再解释「为什么文件少了」要好。
     */
    public static boolean defaultEnabled(Action action) {
        return action != Action.ORPHAN;
    }

    // ---------------------------------------------------------------- 默认值

    /**
     * 内置默认值。
     *
     * <p>三个「保守」取值与它们的不保守之处写在 {@code docs/table-optimization.md}：
     * 快照保留 24 小时（DLF 默认 1 小时太激进，回溯窗口不够）、
     * 孤儿文件清理窗口 1 天且默认干跑（三类动作里唯一会删数据文件的，窗口必须大于
     * 表的最大提交间隔）、compaction 触发阈值 5 个文件（对齐 Paimon 自己的
     * {@code compaction.min.file-num} 默认值，比 AWS 的 100 更早介入小文件问题——
     * 对象存储上每个小文件都是一次读放大）。
     */
    public static Map<String, String> defaults(Action action) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (action) {
            case COMPACT -> {
                values.put(STRATEGY, STRATEGY_BINPACK);
                values.put(MIN_INPUT_FILES, "5");
                values.put(TARGET_FILE_SIZE_MB, "128");
                values.put(INTERVAL_HOURS, "1");
            }
            case EXPIRE -> {
                values.put(RETENTION_HOURS, "24");
                values.put(RETAIN_MIN, "10");
                values.put(INTERVAL_HOURS, "24");
            }
            case ORPHAN -> {
                values.put(OLDER_THAN_HOURS, "24");
                values.put(INTERVAL_DAYS, "7");
                values.put(DRY_RUN, "true");
            }
        }
        return values;
    }

    /**
     * 从 catalog 属性里摘出某项动作的默认值。
     *
     * <p>键形如 {@code optimization.compact.min-input-files}——动作段用的是**动作的
     * wire 名**（{@code compact} / {@code expire} / {@code orphan}），与 API 里
     * {@code action} 字段、数据库 {@code actions} 列是同一套词。不为 catalog 属性
     * 另起一套「更长更像英文」的名字（{@code optimization.compaction.*}）：两套词意味着
     * 两处映射、两处漂移，而用户看到的动作名在不同地方不一致是最难查的那类问题。
     * 摘完前缀剩下的部分必须与内置默认的键名对得上，对不上的忽略——catalog 属性是
     * 自由键空间，别人往里写什么都有可能，但只有能解释的键才允许影响行为。
     */
    public static Map<String, String> catalogDefaults(Map<String, String> catalogProperties, Action action) {
        Map<String, String> result = new LinkedHashMap<>();
        if (catalogProperties == null) {
            return result;
        }
        String prefix = CATALOG_PREFIX + action.wire() + ".";
        for (Map.Entry<String, String> entry : catalogProperties.entrySet()) {
            if (entry.getKey() == null || !entry.getKey().startsWith(prefix)) {
                continue;
            }
            String key = entry.getKey().substring(prefix.length()).trim();
            if (KNOWN_KEYS.get(action).contains(key)) {
                result.put(key, entry.getValue());
            }
        }
        return result;
    }

    /**
     * 合并出一项动作的最终配置：内置默认 → catalog 默认 → 表级覆盖。
     *
     * <p>值为 {@code null} 或空串的表级键视为「没配过」而跳过：控制台清空一个输入框
     * 提交上来就是空串，让它回落默认值才是用户按下「保存」时的预期。
     */
    public static Map<String, String> resolve(Action action, Map<String, String> catalogProperties,
                                              Map<String, String> tableConfig) {
        Map<String, String> resolved = defaults(action);
        resolved.putAll(catalogDefaults(catalogProperties, action));
        if (tableConfig != null) {
            tableConfig.forEach((key, value) -> {
                if (key != null && value != null && !value.isBlank() && KNOWN_KEYS.get(action).contains(key.trim())) {
                    resolved.put(key.trim(), value.trim());
                }
            });
        }
        return resolved;
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 校验表级配置，非法取值抛 400。
     *
     * <p>校验的是「写进策略里的差异」，不是合并后的结果——内置默认与 catalog 默认
     * 都来自本类或运维配置，不需要每次都重新证明自己合法（catalog 属性写错时
     * 由 {@code resolve} 的值域兜住：非法值在这里也会被同一套规则拒掉，
     * 因为 {@code validate} 对合并后的值同样适用，见 {@code TableOptimizationService}）。
     */
    public static void validate(Action action, Map<String, String> tableConfig) {
        if (tableConfig == null) {
            return;
        }
        Set<String> known = KNOWN_KEYS.get(action);
        for (String key : tableConfig.keySet()) {
            if (key == null || !known.contains(key.trim())) {
                throw ApiException.badRequest("Unknown " + action.wire() + " option: " + key
                        + ", supported: " + String.join(", ", known));
            }
        }
        switch (action) {
            case COMPACT -> {
                String strategy = text(tableConfig, STRATEGY);
                if (strategy != null && !STRATEGY_BINPACK.equals(strategy) && !STRATEGY_SORT.equals(strategy)) {
                    throw ApiException.badRequest("strategy must be one of: " + STRATEGY_BINPACK + ", " + STRATEGY_SORT);
                }
                integer(tableConfig, MIN_INPUT_FILES, 1, 1000);
                integer(tableConfig, TARGET_FILE_SIZE_MB, 1, 4096);
                integer(tableConfig, INTERVAL_HOURS, 1, 8760);
                if (STRATEGY_SORT.equals(strategy) && text(tableConfig, SORT_COLUMNS) == null) {
                    throw ApiException.badRequest("sort-columns is required when strategy is sort");
                }
            }
            case EXPIRE -> {
                integer(tableConfig, RETENTION_HOURS, 1, 8760);
                integer(tableConfig, RETAIN_MIN, 0, 10000);
                integer(tableConfig, INTERVAL_HOURS, 1, 8760);
            }
            case ORPHAN -> {
                // 下限 1 小时不是随意取的：清理窗口必须大于表的最大提交间隔，
                // 否则会把正在写入、尚未被快照引用的数据文件当成孤儿删掉。
                integer(tableConfig, OLDER_THAN_HOURS, 1, 8760);
                integer(tableConfig, INTERVAL_DAYS, 1, 365);
                flag(tableConfig, DRY_RUN);
            }
        }
    }

    private static String text(Map<String, String> config, String key) {
        String value = config.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void integer(Map<String, String> config, String key, int min, int max) {
        String value = text(config, key);
        if (value == null) {
            return;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest(key + " must be an integer");
        }
        if (parsed < min || parsed > max) {
            throw ApiException.badRequest(key + " must be between " + min + " and " + max);
        }
    }

    private static void flag(Map<String, String> config, String key) {
        String value = text(config, key);
        if (value != null && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw ApiException.badRequest(key + " must be true or false");
        }
    }

    // ---------------------------------------------------------------- 调度

    /**
     * 一项动作两次执行之间的最小间隔。
     *
     * <p>为什么需要它而不是「每次扫描都跑一遍」：三项动作的代价与时效性完全不同——
     * 快照清理每天一次足够，孤儿文件清理按周足够（它要遍历目录，是三类里最贵的），
     * 只有 compaction 值得跟着小时级的调度走。把它们绑在同一个节拍上，
     * 要么让 compaction 太迟钝，要么让孤儿文件清理空转。
     */
    public static Duration interval(Action action, Map<String, String> effective) {
        switch (action) {
            case ORPHAN -> {
                return Duration.ofDays(longValue(effective, INTERVAL_DAYS, 7));
            }
            case EXPIRE -> {
                return Duration.ofHours(longValue(effective, INTERVAL_HOURS, 24));
            }
            default -> {
                return Duration.ofHours(longValue(effective, INTERVAL_HOURS, 1));
            }
        }
    }

    private static long longValue(Map<String, String> values, String key, long fallback) {
        String value = values == null ? null : values.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /** 把 wire 名列表解析成动作集合，遇到非法名抛 400。 */
    public static List<Action> parseActions(List<String> names) {
        List<Action> actions = new ArrayList<>();
        if (names == null) {
            return actions;
        }
        for (String name : names) {
            Action action = Action.parse(name);
            if (action == null) {
                throw ApiException.badRequest("Unknown optimization action: " + name
                        + ", supported: " + actionNames());
            }
            if (!actions.contains(action)) {
                actions.add(action);
            }
        }
        return actions;
    }

    /** 动作集合的存储形态：逗号分隔的 wire 名，顺序固定（与枚举声明一致）。 */
    public static String wire(List<Action> actions) {
        List<String> names = new ArrayList<>();
        for (Action action : Action.values()) {
            if (actions.contains(action)) {
                names.add(action.wire());
            }
        }
        return String.join(",", names);
    }

    /** 解析存储形态；无法识别的项忽略（历史数据里可能有已下线动作）。 */
    public static List<Action> fromWire(String wire) {
        List<Action> actions = new ArrayList<>();
        if (wire == null || wire.isBlank()) {
            return actions;
        }
        for (String name : wire.split(",")) {
            Action action = Action.parse(name);
            if (action != null && !actions.contains(action)) {
                actions.add(action);
            }
        }
        return actions;
    }
}
