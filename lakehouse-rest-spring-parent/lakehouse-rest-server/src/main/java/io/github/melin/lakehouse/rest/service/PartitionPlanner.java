package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.dto.PreviewDtos;
import io.github.melin.lakehouse.rest.preview.ColumnFilter;
import io.github.melin.lakehouse.rest.preview.PartitionSlice;
import io.github.melin.lakehouse.rest.preview.PreviewColumn;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 「默认预览最新分区」的那套判断——**与本类之外的任何格式都无关**。
 *
 * <p>它只吃三样东西：分区字段名、分区切片（值 + 文件数）、用户的请求。
 * 于是这条最容易出错的逻辑可以拿纯单测钉住（见 {@code PartitionPlannerTests}），
 * 而不必先起一个 Paimon 表。
 *
 * <h2>三条规则</h2>
 *
 * <ol>
 *   <li><b>先判有序性。</b>"最新"只对可排序的分区字段有意义：identity 分区、
 *       日期/时间分区取最大值；哈希桶（bucket）之间没有先后，不参与自动锁定。
 *       Paimon 的分区恒为 identity，因此这条在本期只影响 Iceberg 的
 *       {@code bucket}/{@code truncate}——但判断写在这里，将来接 Iceberg 不用改用例。</li>
 *   <li><b>按文件数预算级联。</b>锁一级 → 数文件 → 还超过 {@code maxFiles} 就再锁下一级。
 *       锁几层由数据量决定，不由人猜：固定锁满会让 {@code dt/hour/region} 表默认只看
 *       {@code cn-east} 的 1 个文件（数据量小到看不出分布）；只锁一级又会跨小时。</li>
 *   <li><b>用户说了算。</b>请求里显式给了分区、或者过滤条件里已经等值命中某个分区字段，
 *       该字段就不再自动锁定（否则 {@code dt=今天} 与 {@code dt=2024-01-01} 会 AND 出
 *       静默的 0 行，而用户会以为"这个日期没数据"）。</li>
 * </ol>
 *
 * <p><b>时间族判定宁严勿宽。</b>只有"类型就是时间"或"名字像时间**且**值严格是日期形状"
 * 才写「最新分区」；否则写「最大分区值」。在 {@code region} 分区上说"最新"是编的，
 * 而编出来的词会被当成事实用。
 */
public final class PartitionPlanner {

    /** 认作"像时间"的字段名。命中名字还不够，值也要过格式校验。 */
    private static final Set<String> TEMPORAL_NAMES =
            Set.of("dt", "ds", "date", "day", "hour", "month", "year", "分区日期");

    /** 日期形状：{@code 2026-10-21}、{@code 2026-10-21 08}、{@code 20261021}、{@code 202610}。 */
    private static final Pattern DATE_SHAPE =
            Pattern.compile("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}(:\\d{2}(:\\d{2})?)?)?|\\d{8}|\\d{6}");

    private PartitionPlanner() {
    }

    /**
     * 决定这次读哪些分区。
     *
     * @param fields          分区字段，按表定义顺序
     * @param columns         全部列（用于判"这个字段是不是时间族"）
     * @param transforms      字段 → 分区变换（Paimon 恒为 identity；缺省即 identity）
     * @param slices          该快照的全部分区切片
     * @param request         请求里的分区选择；{@code null} 视为 {@code mode=auto}
     * @param filters         行级过滤条件（其中对分区字段的等值条件会被当成"用户已锁定"）
     * @param maxFiles        自动收敛的目标文件数
     * @param maxCascadeDepth 最多锁几级
     */
    public static Decision decide(
            List<String> fields,
            List<PreviewColumn> columns,
            Map<String, String> transforms,
            List<PartitionSlice> slices,
            PreviewDtos.PartitionSelectionRequest request,
            List<ColumnFilter> filters,
            int maxFiles,
            int maxCascadeDepth) {

        String mode = mode(request);
        if (fields.isEmpty()) {
            return new Decision(mode, List.of(), List.of(), null, false, 0, null, 0, 0, List.of());
        }

        Map<String, PreviewColumn> byName = new LinkedHashMap<>();
        for (PreviewColumn column : columns) {
            byName.put(column.name(), column);
        }

        // 用户显式锁定的字段（两条来源：partition.values、filters 里的等值条件）
        Map<String, String> explicit = new LinkedHashMap<>();
        Map<String, String> fromFilters = new LinkedHashMap<>();
        if ("explicit".equals(mode)) {
            if (request == null || request.values() == null || request.values().isEmpty()) {
                throw ApiException.badRequest("partition.mode=explicit needs at least one value");
            }
            for (PreviewDtos.PartitionValue value : request.values()) {
                if (value == null || value.field() == null || value.field().isBlank()) {
                    throw ApiException.badRequest("Every partition value needs a field");
                }
                if (!fields.contains(value.field())) {
                    throw ApiException.badRequest("'" + value.field() + "' is not a partition field;"
                            + " partition fields of this table: " + fields);
                }
                explicit.put(value.field(), value.value());
            }
        } else if ("auto".equals(mode)) {
            for (ColumnFilter filter : filters == null ? List.<ColumnFilter>of() : filters) {
                if (filter == null || !fields.contains(filter.column())) {
                    continue;
                }
                if ("eq".equals(filter.op()) && filter.value() != null) {
                    fromFilters.put(filter.column(), String.valueOf(filter.value()));
                }
            }
            explicit.putAll(fromFilters);
        }

        List<PartitionSlice> selection = slices;
        List<String> warnings = new ArrayList<>();
        if (!explicit.isEmpty()) {
            List<PartitionSlice> matched = match(slices, explicit);
            if (matched.isEmpty()) {
                if ("explicit".equals(mode)) {
                    // 显式点名一个不存在的分区：静默 0 行会让人以为"这个分区没数据"，
                    // 而真实原因是这个分区根本不在当前快照里
                    throw ApiException.badRequest("No partition matches " + describe(explicit)
                            + "; this snapshot has " + slices.size() + " partitions"
                            + newestHint(fields, slices));
                }
                // 来自过滤条件的等值：这是一条普通的行过滤，允许筛出 0 行。
                // 因此不拿它裁文件（裁了就会 400 或把范围缩成空），让它走第 3 段逐行判定
                explicit.clear();
            } else {
                selection = matched;
            }
        }

        boolean auto = false;
        int depth = 0;
        PreviewDtos.Fallback fallback = null;
        if ("auto".equals(mode)) {
            boolean cascade = request == null || request.cascade() == null || request.cascade();
            boolean anyOrderable = false;
            List<String> candidates = new ArrayList<>();
            for (String field : fields) {
                if (!orderable(field, byName, transforms)) {
                    continue;
                }
                anyOrderable = true;
                if (!explicit.containsKey(field)) {
                    candidates.add(field);
                }
            }
            if (!anyOrderable) {
                // 哈希桶 / 前缀截断之间没有先后。这里必须给 fallback 而不是静默读全表：
                // 界面上要能说清"为什么没有那个「自动」角标"，否则用户会以为功能坏了
                fallback = new PreviewDtos.Fallback("NOT_ORDERABLE",
                        "分区按 " + transformsOf(fields, transforms) + " 划分，分区之间没有先后；"
                                + "已读取全部分区");
            }
            int rounds = Math.min(cascade ? maxCascadeDepth : 1, candidates.size());
            for (int round = 0; round < rounds; round++) {
                String field = candidates.get(round);
                // 先按**全部**取值取最大（含空分区）：否则"最新那个分区还没写数据"这个
                // 情形永远走不到回退分支——它会在取最大值时就被跳过，界面于是安静地
                // 少看一天的数据，而 fallback 是 null
                Object max = maxValue(selection, field, false);
                if (max == null) {
                    // 该字段的取值全是 null（默认分区）：没有"最新"可言，就地读它比编一个最新好
                    fallback = new PreviewDtos.Fallback("DEFAULT_PARTITION",
                            "分区字段 '" + field + "' 只有空值（默认分区），已读取它");
                    break;
                }
                List<PartitionSlice> narrowed = withValue(selection, field, max);
                if (sum(narrowed) == 0) {
                    // "最新"那个分区里没有数据文件：退到有文件的最新一个。
                    // 这一步必须做——否则默认视图永远是一片空白，而用户不知道为什么
                    Object withFiles = maxValue(selection, field, true);
                    if (withFiles != null && !sameValue(withFiles, max)) {
                        fallback = new PreviewDtos.Fallback("NO_FILES",
                                "最新分区 " + field + " = " + labelOf(max) + " 还没有数据文件，已回退到 "
                                        + field + " = " + labelOf(withFiles));
                        narrowed = withValue(selection, field, withFiles);
                    }
                }
                // 该字段在本次选择里只有一个取值："锁"它没有更窄，但深度与 auto 仍要记——
                // 否则 chip 不显示条件，而实际读的只是那一个分区
                boolean narrowing = narrowed.size() < selection.size();
                selection = narrowed;
                depth = round + 1;
                auto = true;
                if (sum(selection) <= maxFiles) {
                    break;
                }
                if (!narrowing || round == rounds - 1) {
                    // 已经无处可锁，而文件数仍超预算 → 只能采样，必须说出来
                    warnings.add("分区内仍有 " + sum(selection) + " 个数据文件，本次只读最近 "
                            + maxFiles + " 个");
                    break;
                }
            }
        }

        List<PreviewDtos.PartitionValue> resolved = new ArrayList<>();
        // 只剩一个分区时（无论是不是服务端挑的），把它写进 resolved 才是诚实的：
        // 用户看到 dt=X 就知道"我现在看的是哪一天"，而不是去猜 128 个文件属于哪一段
        boolean single = selection.size() == 1 && slices.size() > 1;
        for (String field : fields) {
            Object value;
            if (explicit.containsKey(field)) {
                value = selection.isEmpty() ? explicit.get(field) : selection.get(0).value(field);
            } else if (single || (auto && isLocked(selection, field))) {
                value = selection.get(0).value(field);
            } else {
                // 多级里没被锁定的字段不写进 resolved：它不是条件，只是"没限"
                continue;
            }
            resolved.add(new PreviewDtos.PartitionValue(field,
                    transforms == null ? "identity" : transforms.getOrDefault(field, "identity"),
                    value == null ? "<null>" : labelOf(value)));
        }

        String label = label(fields, resolved, auto, depth, !explicit.isEmpty() && !fromFilters.isEmpty());
        return new Decision(mode, List.copyOf(selection), List.copyOf(resolved), label, auto, depth,
                fallback, slices.size(), selection.size(), List.copyOf(warnings));
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 把一份**已解析**的分区值还原成切片——游标续读走这条，不重新规划。
     *
     * <p>为什么不能"第二页再 decide 一次 {@code auto}"：期间若正好有新分区提交，
     * 重新解析会指向更新的那个分区，而游标里的 {@code r} 是**旧分区**里已消费的行数，
     * 在新分区上 {@code skip(r)} 会跳错位置——行错位且不报错，是这一类 bug 里最难发现的。
     *
     * <p>返回空表示这些取值在当前分区集合里都不存在（快照被换掉、或分区已被完全清理），
     * 由调用方决定是 409 还是当成"没有分区过滤"。
     *
     * @param slices   该快照的全部分区切片
     * @param resolved 游标里固化的分区取值
     */
    public static List<PartitionSlice> resolve(List<PartitionSlice> slices,
                                               List<PreviewDtos.PartitionValue> resolved) {
        if (slices == null || slices.isEmpty() || resolved == null || resolved.isEmpty()) {
            return List.of();
        }
        Map<String, String> expected = new LinkedHashMap<>();
        for (PreviewDtos.PartitionValue value : resolved) {
            if (value != null && value.field() != null) {
                expected.put(value.field(), value.value());
            }
        }
        return expected.isEmpty() ? List.of() : match(slices, expected);
    }

    private static String mode(PreviewDtos.PartitionSelectionRequest request) {
        String mode = request == null || request.mode() == null || request.mode().isBlank()
                ? "auto"
                : request.mode().trim().toLowerCase(Locale.ROOT);
        if (!Set.of("auto", "explicit", "all").contains(mode)) {
            throw ApiException.badRequest("Unknown partition.mode '" + request.mode()
                    + "'; expected auto / explicit / all");
        }
        return mode;
    }

    /**
     * 这个字段能不能定序。
     *
     * <p>先看变换：{@code bucket} 是哈希桶号、{@code truncate} 是前缀截断，两者都是
     * "把值打到若干个桶里"，桶与桶之间没有先后——在这样的表上说"最新分区"是编的。
     * identity（Paimon 恒为它）以及 {@code year/month/day/hour} 这类时间截断都能排序。
     *
     * <p>再看类型：复合类型与二进制没法比大小。{@code DATE}/{@code TIMESTAMP} 是数字
     * 存储的，能比；{@code STRING} 也能比（字典序），只是"最新"在字符串上要谨慎，
     * 所以 {@link #label} 那边才要看值形状决定措辞。
     */
    private static boolean orderable(String field, Map<String, PreviewColumn> columns,
                                     Map<String, String> transforms) {
        String transform = transforms == null ? null : transforms.get(field);
        if (transform != null) {
            String normalized = transform.trim().toLowerCase(Locale.ROOT);
            if (normalized.startsWith("bucket") || normalized.startsWith("truncate")) {
                return false;
            }
        }
        PreviewColumn column = columns.get(field);
        if (column == null) {
            return false;
        }
        return column.kind() != PreviewColumn.Kind.COMPLEX && column.kind() != PreviewColumn.Kind.BINARY;
    }

    /** 字段们的变换，拼成一句人话（{@code bucket(4, id)}、{@code identity}）。 */
    private static String transformsOf(List<String> fields, Map<String, String> transforms) {
        StringBuilder builder = new StringBuilder();
        for (String field : fields) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            String transform = transforms == null ? null : transforms.get(field);
            builder.append(transform == null ? "identity" : transform).append('(').append(field).append(')');
        }
        return builder.toString();
    }

    /** 该字段在本次选择里是否被锁成了单一取值。 */
    private static boolean isLocked(List<PartitionSlice> selection, String field) {
        Object first = selection.get(0).value(field);
        for (PartitionSlice slice : selection) {
            if (!sameValue(slice.value(field), first)) {
                return false;
            }
        }
        return true;
    }

    private static List<PartitionSlice> match(List<PartitionSlice> slices, Map<String, String> expected) {
        List<PartitionSlice> matched = new ArrayList<>();
        for (PartitionSlice slice : slices) {
            boolean all = true;
            for (Map.Entry<String, String> entry : expected.entrySet()) {
                Object value = slice.value(entry.getKey());
                if (value == null) {
                    all = false;
                } else if (!labelOf(value).equals(entry.getValue())) {
                    all = false;
                }
                if (!all) {
                    break;
                }
            }
            if (all) {
                matched.add(slice);
            }
        }
        return matched;
    }

    private static List<PartitionSlice> withValue(List<PartitionSlice> slices, String field, Object value) {
        List<PartitionSlice> narrowed = new ArrayList<>();
        for (PartitionSlice slice : slices) {
            if (sameValue(slice.value(field), value)) {
                narrowed.add(slice);
            }
        }
        return narrowed;
    }

    /**
     * 选择里该字段的最大值。
     *
     * @param preferWithFiles 只考虑"有数据文件"的分区。用于"最新分区是空的"这一步：
     *                        空分区不该成为默认视图
     */
    private static Object maxValue(List<PartitionSlice> slices, String field, boolean preferWithFiles) {
        Object max = null;
        for (PartitionSlice slice : slices) {
            if (preferWithFiles && slice.files() == 0) {
                continue;
            }
            Object value = slice.value(field);
            if (value == null) {
                // null 分区等价 NULLS LAST：只有所有值都是 null 时才会被选中
                continue;
            }
            if (max == null || compare(value, max) > 0) {
                max = value;
            }
        }
        if (max == null && preferWithFiles) {
            return maxValue(slices, field, false);
        }
        return max;
    }

    static int compare(Object left, Object right) {
        if (left instanceof Number a && right instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        }
        if (left instanceof Comparable<?> && left.getClass().isInstance(right)) {
            @SuppressWarnings("unchecked")
            Comparable<Object> comparable = (Comparable<Object>) left;
            return comparable.compareTo(right);
        }
        return labelOf(left).compareTo(labelOf(right));
    }

    private static boolean sameValue(Object left, Object right) {
        if (left == null || right == null) {
            return left == right;
        }
        return compare(left, right) == 0;
    }

    private static long sum(List<PartitionSlice> slices) {
        long files = 0;
        for (PartitionSlice slice : slices) {
            files += slice.files();
        }
        return files;
    }

    /** 分区值的展示文本；{@code null} 写成一个明确的标记，与字符串 "null" 区分开。 */
    static String labelOf(Object value) {
        if (value == null) {
            return "<null>";
        }
        if (value instanceof LocalDateTime dateTime) {
            return dateTime.toString().replace('T', ' ');
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof Instant instant) {
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).toString().replace('T', ' ');
        }
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        return String.valueOf(value);
    }

    private static String describe(Map<String, String> values) {
        StringBuilder builder = new StringBuilder();
        values.forEach((field, value) -> {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(field).append('=').append(value);
        });
        return builder.toString();
    }

    private static String newestHint(List<String> fields, List<PartitionSlice> slices) {
        if (slices.isEmpty()) {
            return " (it has no partitions yet)";
        }
        String field = fields.get(0);
        Object max = maxValue(slices, field, true);
        return max == null ? "" : " (latest " + field + " = " + labelOf(max) + ")";
    }

    private static String label(List<String> fields, List<PreviewDtos.PartitionValue> resolved,
                                boolean auto, int depth, boolean viaFilter) {
        if (resolved.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (PreviewDtos.PartitionValue value : resolved) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(value.field()).append(" = ").append(value.value());
        }
        if (auto) {
            builder.append(depth > 1 ? "（自动收敛）" : "（最新分区）");
        } else if (viaFilter) {
            // 范围是被过滤条件收窄的（§7.4 让位规则）。不标出来，分区控件会显示"全部"，
            // 而实际只读了 1 个分区——用户会以为读的是全表
            builder.append("（随过滤条件）");
        }
        return builder.toString();
    }

    /**
     * 自动分区的结论。
     *
     * @param mode              请求的模式（回显）
     * @param selection         实际要读的分区；空表示不裁
     * @param resolved          生效的分区条件（含用户显式给的那些）
     * @param label             一句人话，前端直接显示
     * @param auto              有没有服务端替用户做的选择（决定 chip 的「自动」角标）
     * @param depth             自动锁了几级
     * @param fallback          为什么没锁成"最新"；没发生则为 {@code null}
     * @param partitionsTotal   该快照的分区总数
     * @param partitionsMatched 命中的分区数
     * @param warnings          其它人话提示
     */
    public record Decision(
            String mode,
            List<PartitionSlice> selection,
            List<PreviewDtos.PartitionValue> resolved,
            String label,
            boolean auto,
            int depth,
            PreviewDtos.Fallback fallback,
            int partitionsTotal,
            int partitionsMatched,
            List<String> warnings) {
    }
}
