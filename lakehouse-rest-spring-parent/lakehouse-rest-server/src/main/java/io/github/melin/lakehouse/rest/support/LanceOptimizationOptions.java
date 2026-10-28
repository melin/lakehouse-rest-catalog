package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.support.OptimizationOptions.Action;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lance 表的两项存储优化参数：键名、内置默认值、校验与调度间隔。
 *
 * <p><b>为什么单开一份。</b>理由与 {@link IcebergOptimizationOptions} 的类注释同源，
 * 但 Lance 这一份的差异更硬：Lance 只有两条维护语句（{@code OPTIMIZE} / {@code VACUUM}），
 * 而且**口径整块换了**——「目标大小」是<b>行数</b>不是字节数（{@code target-rows-per-fragment}）、
 * 时间窗口是 <b>epoch 毫秒</b>不是时间戳字面量、没有「至少几个小文件才合并」这个门槛、
 * 也没有「最少保留几个版本」。把两边的键塞进一张表，写错的那一侧不会报错，
 * 只会安静地不生效（{@code min-input-files} 在 Lance 上就是个不存在的概念）。
 *
 * <p><b>只有两项动作。</b>{@code orphan} 的键集是**空集**：{@code VACUUM} 一步就把
 * 「过期版本 + 孤儿文件」都做了，再暴露一张参数表等于让同一件事跑两遍。
 * 「空集」而不是「不给这一项」——动作名是格式无关的契约（API、{@code actions} 列、
 * 指标前缀都用它），缺一项会让「同一张表在两个页面不一样」。见
 * {@code docs/lance-table-optimization-design.md} §3.3。
 *
 * <p><b>键名 kebab，Spark 选项名 snake。</b>{@code target-rows-per-fragment} 到执行侧才
 * 翻成 {@code target_rows_per_fragment}（{@code LanceProcedures}）。策略层的键要能与
 * catalog 属性（{@code optimization.compact.target-rows-per-fragment}）、API 的 JSON 键、
 * 控制台表单共用同一个词；照抄 snake_case 会让它与 Iceberg 的 kebab 键在一张属性表里混排。
 *
 * <p>三层合并（内置默认 → catalog 属性 → 表级差异）仍只发生在
 * {@code OptimizationOptions#resolve} 一处。
 */
public final class LanceOptimizationOptions {

    private LanceOptimizationOptions() {
    }

    // ---------------------------------------------------------------- 参数键

    /** Compaction：目标碎片行数。Lance 的「目标大小」按**行**算，不是字节。 */
    public static final String TARGET_ROWS_PER_FRAGMENT = "target-rows-per-fragment";
    /** Compaction：碎片内 Row Group 的行数（影响重写后的编码）。 */
    public static final String MAX_ROWS_PER_GROUP = "max-rows-per-group";
    /** Compaction：是否把软删除的行真正物化掉。 */
    public static final String MATERIALIZE_DELETIONS = "materialize-deletions";
    /** Compaction：删除比例达到这个阈值才物化（{@code ≥1} 等于关闭）。 */
    public static final String MATERIALIZE_DELETIONS_THRESHOLD = "materialize-deletions-threshold";
    /** Compaction：延迟索引 Remap（{@code true} 时 Commit 只记一层映射）。 */
    public static final String DEFER_INDEX_REMAP = "defer-index-remap";
    /** Compaction：扫描批次大小。**默认不传**，只在 OOM 时才调。 */
    public static final String BATCH_SIZE = "batch-size";

    /** Snapshot Expire：版本保留时长（小时）→ 执行侧的 {@code before_timestamp_millis}。 */
    public static final String RETENTION_HOURS = "retention-hours";
    /** Snapshot Expire：是否删除「验证不了引用关系」的文件。**恒 false**，见 {@link #validate}。 */
    public static final String DELETE_UNVERIFIED = "delete-unverified";

    /** Compaction：两次执行的最小间隔（小时）。 */
    public static final String INTERVAL_HOURS = "interval-hours";
    /** Snapshot Expire：执行间隔（天）。清理版本要读 manifest 判引用，比 compaction 重。 */
    public static final String INTERVAL_DAYS = "interval-days";

    /**
     * **catalog 级**安全闸：这个 catalog 里表的最大提交间隔（小时）。
     *
     * <p>它不是一个可下发的 SQL 选项，也不进表策略——它只用来回答一个问题：
     * 「{@code retention-hours} 填到多少以下就会删到正在写入的文件」。因此它只出现在
     * catalog 属性里（{@code optimization.expire.max-commit-interval-hours}），
     * 由 {@link #maxCommitIntervalHours} 读、由 {@code TableOptimizationService} 在
     * 写入时硬校验（见 {@code lance-table-optimization-design.md} §8 R4）。
     */
    public static final String MAX_COMMIT_INTERVAL_HOURS = "max-commit-interval-hours";
    /** 上面那条闸的默认值：24 小时。 */
    public static final int DEFAULT_MAX_COMMIT_INTERVAL_HOURS = 24;

    /**
     * 每项动作允许出现的键。
     *
     * <p>{@code orphan} 是**空集**：它在这张格式上不存在，因此没有任何合法参数。
     * 空集让「给 orphan 传参数」在保存时就是 400，而不是存下去、跑到 k8s 上才发现
     * 这个动作根本不会被翻译成语句。
     */
    private static final Map<Action, Set<String>> KNOWN_KEYS = Map.of(
            Action.COMPACT, Set.of(TARGET_ROWS_PER_FRAGMENT, MAX_ROWS_PER_GROUP, MATERIALIZE_DELETIONS,
                    MATERIALIZE_DELETIONS_THRESHOLD, DEFER_INDEX_REMAP, BATCH_SIZE, INTERVAL_HOURS),
            Action.EXPIRE, Set.of(RETENTION_HOURS, DELETE_UNVERIFIED, INTERVAL_DAYS),
            Action.ORPHAN, Set.of());

    /** 这个参数名是不是该项动作认得的。 */
    public static boolean isKnownOption(Action action, String key) {
        return key != null && KNOWN_KEYS.get(action).contains(key.trim());
    }

    /** 某项动作的合法键集合，用于报错文案与校验脚本比对。 */
    public static Set<String> knownKeys(Action action) {
        return KNOWN_KEYS.get(action);
    }

    public static String knownKeysText(Action action) {
        Set<String> keys = KNOWN_KEYS.get(action);
        return keys.isEmpty() ? "（无）" : String.join(", ", sorted(keys));
    }

    // ---------------------------------------------------------------- 默认值

    /**
     * 内置默认值。
     *
     * <p>取值各有出处，**不发明新数字**：
     *
     * <ul>
     *   <li>{@code target-rows-per-fragment} 取 1048576——就是 Lance 自己的默认值
     *       （{@code 1024 * 1024}）。给另一个数字会让「换到 Spark 跑」与
     *       「Lance 自己写」产出不同形状的碎片；</li>
     *   <li>{@code max-rows-per-group} 取 1024，同上；</li>
     *   <li>{@code retention-hours} 取 72 小时且执行间隔 7 天——{@code VACUUM}
     *       <b>不可逆、没有干跑</b>，窗口必须远大于任何合理的提交间隔，宁可少回收空间。</li>
     * </ul>
     *
     * <p>{@code batch-size} 不出现在默认值里：它在 connector 的 exec 里是
     * {@code Option}，不传就用写入侧的默认。给一个值会让输出文件按字节截断，与
     * {@code target-rows-per-fragment} 一起用容易产出「行数没到但文件满了」的碎片，
     * 下一轮再次成为候选。
     *
     * <p><b>没有 {@code max-source-fragments}，这是查过源码后的结论而不是遗漏。</b>
     * design 稿把它列为「单次工作量上限」，但 connector 0.4.0 的
     * {@code OptimizeExec.buildOptions} 只读了 8 个 {@code WITH} 参数，
     * **不含 {@code max_source_fragments}**（{@code org.lance.compaction.CompactionOptions}
     * 有这个字段，Spark 侧没接出来）。写进语句会被静默忽略——那正是「用户配了、页面显示
     * 生效、执行时没发生」这一类最难排查的失效。宁可少一个键，也不给一个不生效的旋钮。
     */
    public static Map<String, String> defaults(Action action) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (action) {
            case COMPACT -> {
                values.put(TARGET_ROWS_PER_FRAGMENT, "1048576");
                values.put(MAX_ROWS_PER_GROUP, "1024");
                values.put(MATERIALIZE_DELETIONS, "true");
                values.put(MATERIALIZE_DELETIONS_THRESHOLD, "0.1");
                values.put(DEFER_INDEX_REMAP, "false");
                values.put(INTERVAL_HOURS, "1");
            }
            case EXPIRE -> {
                values.put(RETENTION_HOURS, "72");
                values.put(DELETE_UNVERIFIED, "false");
                values.put(INTERVAL_DAYS, "7");
            }
            case ORPHAN -> {
                // 空表：这一项在这张格式上不存在（VACUUM 已包含），没有任何参数
            }
        }
        return values;
    }

    /** 从 catalog 属性里摘出某项动作的默认值（前缀与另两种格式同一套 {@code optimization.<action>.}）。 */
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
     * <p>两条 Lance 独有的规则：
     *
     * <ul>
     *   <li><b>{@code delete-unverified} 只能是 {@code false}。</b>它的语义是「删掉验证不了
     *       引用关系的文件」——那是「反正我要腾空间」的场景，而我们是定期维护。
     *       做成开关等于提供一个「关掉安全检查」的按钮，而点它的用户在点之前不会知道
     *       自己在关什么。因此这个键认得、但只接受一个值。</li>
     *   <li><b>{@code retention-hours} 有绝对下限，另有一条相对下限</b>（catalog 的
     *       {@code max-commit-interval-hours}）在 {@code TableOptimizationService} 里判——
     *       那条需要 catalog 属性，本方法看不见。</li>
     * </ul>
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
                number(config, TARGET_ROWS_PER_FRAGMENT, 1_000L, 100_000_000L);
                number(config, MAX_ROWS_PER_GROUP, 64L, 1_000_000L);
                flag(config, MATERIALIZE_DELETIONS);
                decimal(config, MATERIALIZE_DELETIONS_THRESHOLD, 0D, 1D);
                flag(config, DEFER_INDEX_REMAP);
                number(config, BATCH_SIZE, 1L, 1_000_000L);
                integer(config, INTERVAL_HOURS, 1, 8760);
            }
            case EXPIRE -> {
                // 下限 1 小时与另两种格式一致；真正的护栏是 catalog 级的 max-commit-interval-hours
                integer(config, RETENTION_HOURS, 1, 8760);
                integer(config, INTERVAL_DAYS, 1, 365);
                String unverified = text(config, DELETE_UNVERIFIED);
                if (unverified != null && !"false".equalsIgnoreCase(unverified)) {
                    throw ApiException.badRequest(DELETE_UNVERIFIED + " must be false: VACUUM does not"
                            + " support deleting files whose references cannot be verified");
                }
            }
            case ORPHAN -> throw ApiException.badRequest(unsupportedOrphanReason());
        }
    }

    /**
     * 这张格式上 {@code orphan} 不可用的原因（服务端与执行侧共用一句文案）。
     *
     * <p>写成方法而不是散在两处的字面量：控制台显示的就是这一句，而它同时是
     * {@code ActionPolicy.unsupportedReason} 的取值——两处抄一遍，迟早出现
     * 「前端置灰卡的说明与 API 返回的原因不一致」。
     */
    public static String unsupportedOrphanReason() {
        return "Lance 的 VACUUM 已同时清理过期版本与孤儿文件，此动作不单独提供";
    }

    // ---------------------------------------------------------------- 保留窗口的相对下限

    /**
     * 读 catalog 级的「最大提交间隔」（小时）。缺省或非法取值回落
     * {@link #DEFAULT_MAX_COMMIT_INTERVAL_HOURS}。
     *
     * <p>回落而不是报错：这个键是运维写在 catalog 属性里的，写错一个值不该让整个
     * catalog 的优化全部停摆——按默认值 24 小时继续跑是安全的那一侧。
     */
    public static int maxCommitIntervalHours(Map<String, String> catalogProperties) {
        String value = catalogProperties == null ? null
                : catalogProperties.get(OptimizationOptions.CATALOG_PREFIX + Action.EXPIRE.wire()
                        + "." + MAX_COMMIT_INTERVAL_HOURS);
        if (value == null || value.isBlank()) {
            return DEFAULT_MAX_COMMIT_INTERVAL_HOURS;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? Math.min(parsed, 8760) : DEFAULT_MAX_COMMIT_INTERVAL_HOURS;
        } catch (NumberFormatException exception) {
            return DEFAULT_MAX_COMMIT_INTERVAL_HOURS;
        }
    }

    /**
     * 把 {@code retention-hours} 抬到不低于 catalog 的最大提交间隔（就地修改）。
     *
     * <p><b>为什么是「抬高」而不是「回落默认」。</b>这个值的语义是「早于多久的版本可以删」，
     * 抬高只会少删、不会多删——方向是安全的那一侧。回落默认（72）反而可能在
     * 「运维把最大提交间隔调到 168」之后继续删掉 72 小时前的版本，那正是要防的事。
     *
     * @return 是否发生了调整（调用方据此打一条 WARN）
     */
    public static boolean enforceRetentionFloor(Map<String, String> effective,
                                                Map<String, String> catalogProperties) {
        if (effective == null) {
            return false;
        }
        int floor = maxCommitIntervalHours(catalogProperties);
        String value = text(effective, RETENTION_HOURS);
        if (value == null) {
            return false;
        }
        int retention;
        try {
            retention = Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return false;
        }
        if (retention >= floor) {
            return false;
        }
        effective.put(RETENTION_HOURS, String.valueOf(floor));
        return true;
    }

    // ---------------------------------------------------------------- 调度

    /**
     * 一项动作两次执行之间的最小间隔。
     *
     * <p>{@code expire} 按**天**（默认 7）：{@code VACUUM} 要读 manifest 判引用关系，
     * 是两项里更贵的那一项，而回收空间这件事不随时间线性增长。
     * {@code orphan} 在这张格式上不可用，取值不会被用到（{@code enabledActions}
     * 会先把它滤掉），这里给一个同量级的值只为让方法保持全序。
     */
    public static Duration interval(Action action, Map<String, String> effective) {
        switch (action) {
            case EXPIRE -> {
                return Duration.ofDays(longValue(effective, INTERVAL_DAYS, 7));
            }
            case ORPHAN -> {
                return Duration.ofDays(longValue(effective, INTERVAL_DAYS, 7));
            }
            default -> {
                return Duration.ofHours(longValue(effective, INTERVAL_HOURS, 1));
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String text(Map<String, String> config, String key) {
        String value = config.get(key);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static void integer(Map<String, String> config, String key, int min, int max) {
        String value = text(config, key);
        if (value == null) {
            return;
        }
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < min || parsed > max) {
                throw ApiException.badRequest(key + " must be between " + min + " and " + max);
            }
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest(key + " must be an integer");
        }
    }

    /**
     * 长整数的区间校验。
     *
     * <p>单独一个方法而不是复用 {@link #integer}：{@code target-rows-per-fragment} 的上限
     * 是 1 亿，超出 {@code int}——用 int 解析它的表现是「填 3000000000 报一个
     * 「must be an integer」」，而它明明是个整数。
     */
    private static void number(Map<String, String> config, String key, long min, long max) {
        String value = text(config, key);
        if (value == null) {
            return;
        }
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest(key + " must be an integer");
        }
        if (parsed < min || parsed > max) {
            throw ApiException.badRequest(key + " must be between " + min + " and " + max);
        }
    }

    private static void decimal(Map<String, String> config, String key, double min, double max) {
        String value = text(config, key);
        if (value == null) {
            return;
        }
        double parsed;
        try {
            parsed = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest(key + " must be a number");
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
