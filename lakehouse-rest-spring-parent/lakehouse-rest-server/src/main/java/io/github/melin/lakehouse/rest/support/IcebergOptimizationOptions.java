package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.support.OptimizationOptions.Action;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iceberg 表的三项存储优化参数：键名、内置默认值、校验与调度间隔。
 *
 * <p><b>为什么单开一份，而不是给 {@link OptimizationOptions} 加 if。</b>两边的参数
 * 虽然能对上「合并策略 / 保留窗口 / 清理窗口」这三件事，但**语义与取值都不同**：
 * Paimon 的 {@code retain-min} 是「最少保留几个快照」，Iceberg 的
 * {@code retain-last} 是「保留最后几个祖先快照」；Paimon 的压缩阈值走
 * {@code compaction.min.file-num}，Iceberg 走 {@code rewrite_data_files} 的
 * {@code min-input-files}；快照保留默认值一个是 24 小时、一个是 5 天（Iceberg 自己的
 * 默认）。把它们塞进同一个类，会让「某个键属于哪种格式」变成一张隐式的表，
 * 而写错的那一侧不报错、只是行为悄悄变成另一个格式的语义。
 *
 * <p><b>键名是策略层的契约。</b>它们与 Iceberg 的 procedure 参数**同名但不是同一层**：
 * {@code target-file-size-mb} 到执行侧才换算成 {@code target-file-size-bytes}，
 * {@code retention-hours} 才换算成 {@code older_than} 时间戳。翻译发生在
 * 优化任务侧（{@code IcebergProcedures}），因为那里才知道自己连的是哪个 Iceberg 版本。
 *
 * <p>三层合并（内置默认 → catalog 属性 → 表级差异）与 Paimon 侧完全同构，
 * 合并仍只发生在 {@code OptimizationOptions#resolve} 一处。
 */
public final class IcebergOptimizationOptions {

    private IcebergOptimizationOptions() {
    }

    // ---------------------------------------------------------------- 参数键

    /** Compaction：{@code binpack}（小文件合并）、{@code sort}（排序合并）或 {@code zorder}。 */
    public static final String STRATEGY = "strategy";
    public static final String STRATEGY_BINPACK = "binpack";
    public static final String STRATEGY_SORT = "sort";
    /** Z-Order 在 Iceberg 里不是独立策略：{@code strategy=sort} + {@code sort_order=zorder(...)}。 */
    public static final String STRATEGY_ZORDER = "zorder";
    /** Compaction：参与合并的最少小文件数（procedure 的 {@code min-input-files}）。 */
    public static final String MIN_INPUT_FILES = "min-input-files";
    /**
     * Compaction：目标文件大小（MB）。
     *
     * <p>用 MB 而不是 Iceberg 的字节数：控制台的输入控件与 Paimon 侧共用一套单位，
     * 而 536870912 这种数字在表单里既难读也容易少打一位。
     */
    public static final String TARGET_FILE_SIZE_MB = "target-file-size-mb";
    /** Compaction：排序合并的排序列，逗号分隔。 */
    public static final String SORT_COLUMNS = "sort-columns";
    /** Compaction：同时重写的文件组数（procedure 的 {@code max-concurrent-file-group-rewrites}）。 */
    public static final String MAX_CONCURRENT_REWRITES = "max-concurrent-file-group-rewrites";

    /** Snapshot Expire：快照保留时长（小时）。 */
    public static final String RETENTION_HOURS = "retention-hours";
    /** Snapshot Expire：保留最后几个祖先快照（procedure 的 {@code retain_last}）。 */
    public static final String RETAIN_LAST = "retain-last";
    /** Snapshot Expire / Compaction：两次执行的最小间隔（小时）。 */
    public static final String INTERVAL_HOURS = "interval-hours";

    /** Orphan File Remove：只清理早于该时长的文件（小时）。 */
    public static final String OLDER_THAN_HOURS = "older-than-hours";
    /** Orphan File Remove：执行间隔（天）。 */
    public static final String INTERVAL_DAYS = "interval-days";
    /** Orphan File Remove：是否只预览不删除。 */
    public static final String DRY_RUN = "dry-run";

    /** 每项动作允许出现的键。键名写错的表现是保存时 400，而不是运行时的静默忽略。 */
    private static final Map<Action, Set<String>> KNOWN_KEYS = Map.of(
            Action.COMPACT, Set.of(STRATEGY, SORT_COLUMNS, MIN_INPUT_FILES, TARGET_FILE_SIZE_MB,
                    MAX_CONCURRENT_REWRITES, INTERVAL_HOURS),
            Action.EXPIRE, Set.of(RETENTION_HOURS, RETAIN_LAST, INTERVAL_HOURS),
            Action.ORPHAN, Set.of(OLDER_THAN_HOURS, INTERVAL_DAYS, DRY_RUN));

    /** 这个参数名是不是该项动作认得的。 */
    public static boolean isKnownOption(Action action, String key) {
        return key != null && KNOWN_KEYS.get(action).contains(key.trim());
    }

    /** 某项动作的合法键集合，用于报错文案与校验脚本比对。 */
    public static Set<String> knownKeys(Action action) {
        return KNOWN_KEYS.get(action);
    }

    public static String knownKeysText(Action action) {
        return String.join(", ", sorted(KNOWN_KEYS.get(action)));
    }

    // ---------------------------------------------------------------- 默认值

    /**
     * 内置默认值。
     *
     * <p>三项的取值都以 **Iceberg 自己的默认为基线**，只对两处做了更保守的调整：
     *
     * <ul>
     *   <li>快照保留 120 小时（Iceberg {@code history.expire.max-snapshot-age-ms}
     *       默认 5 天）——与上游一致，不做「1 小时」那种激进值：快照没了，
     *       时间旅行查询也就没了；</li>
     *   <li>孤儿文件 72 小时且**默认干跑**——清理窗口必须大于表的最大提交间隔，
     *       而干跑让第一次执行只列不删。</li>
     * </ul>
     *
     * <p>compaction 的目标文件大小取 Iceberg 的 512 MB：对象存储上小文件意味着读放大，
     * 但 Iceberg 的扫描规划对超大文件更敏感（单个文件就是一个 split），
     * 512 MB 是上游验证过的折中。
     */
    public static Map<String, String> defaults(Action action) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (action) {
            case COMPACT -> {
                values.put(STRATEGY, STRATEGY_BINPACK);
                values.put(MIN_INPUT_FILES, "5");
                values.put(TARGET_FILE_SIZE_MB, "512");
                values.put(MAX_CONCURRENT_REWRITES, "5");
                values.put(INTERVAL_HOURS, "1");
            }
            case EXPIRE -> {
                values.put(RETENTION_HOURS, "120");
                values.put(RETAIN_LAST, "10");
                values.put(INTERVAL_HOURS, "24");
            }
            case ORPHAN -> {
                values.put(OLDER_THAN_HOURS, "72");
                values.put(INTERVAL_DAYS, "7");
                values.put(DRY_RUN, "true");
            }
        }
        return values;
    }

    /** 从 catalog 属性里摘出某项动作的默认值（前缀与 Paimon 侧同一套 {@code optimization.<action>.}）。 */
    public static Map<String, String> catalogDefaults(Map<String, String> catalogProperties, Action action) {
        Map<String, String> result = new LinkedHashMap<>();
        if (catalogProperties == null) {
            return result;
        }
        String prefix = OptimizationOptions.CATALOG_PREFIX + action.wire() + ".";
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

    // ---------------------------------------------------------------- 校验

    /**
     * 校验一份配置，非法取值抛 400。
     *
     * <p>校验的是「要写进策略的差异」，也包括合并后的结果（catalog 属性是运维直接改的，
     * 没人拦得住，所以 {@code OptimizationConfigResolver} 合并后会再过一遍这里）。
     */
    public static void validate(Action action, Map<String, String> config) {
        if (config == null) {
            return;
        }
        Set<String> known = KNOWN_KEYS.get(action);
        for (String key : config.keySet()) {
            if (key == null || !known.contains(key.trim())) {
                throw ApiException.badRequest("Unknown " + action.wire() + " option: " + key
                        + ", supported: " + knownKeysText(action));
            }
        }
        switch (action) {
            case COMPACT -> {
                String strategy = text(config, STRATEGY);
                if (strategy != null && !STRATEGY_BINPACK.equals(strategy)
                        && !STRATEGY_SORT.equals(strategy) && !STRATEGY_ZORDER.equals(strategy)) {
                    throw ApiException.badRequest("strategy must be one of: "
                            + STRATEGY_BINPACK + ", " + STRATEGY_SORT + ", " + STRATEGY_ZORDER);
                }
                integer(config, MIN_INPUT_FILES, 1, 1000);
                integer(config, TARGET_FILE_SIZE_MB, 1, 8192);
                integer(config, MAX_CONCURRENT_REWRITES, 1, 100);
                integer(config, INTERVAL_HOURS, 1, 8760);
                // 排序与 z-order 都要列名：没有列名的排序合并没有意义，而 Iceberg 的
                // procedure 在 sort_order 为空时会退化成 binpack——用户以为在排序，实际没有。
                if ((STRATEGY_SORT.equals(strategy) || STRATEGY_ZORDER.equals(strategy))
                        && text(config, SORT_COLUMNS) == null) {
                    throw ApiException.badRequest("sort-columns is required when strategy is "
                            + STRATEGY_SORT + " or " + STRATEGY_ZORDER);
                }
            }
            case EXPIRE -> {
                integer(config, RETENTION_HOURS, 1, 8760);
                integer(config, RETAIN_LAST, 0, 10000);
                integer(config, INTERVAL_HOURS, 1, 8760);
            }
            case ORPHAN -> {
                // 与 Paimon 侧同一条理由：窗口必须大于表的最大提交间隔，否则会把
                // 正在写入、尚未被快照引用的数据文件当成孤儿删掉。
                integer(config, OLDER_THAN_HOURS, 1, 8760);
                integer(config, INTERVAL_DAYS, 1, 365);
                flag(config, DRY_RUN);
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

    /** 一项动作两次执行之间的最小间隔，语义与 Paimon 侧一致（默认值不同）。 */
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

    private static List<String> sorted(Set<String> keys) {
        List<String> result = new ArrayList<>(keys);
        result.sort(String::compareTo);
        return result;
    }
}
