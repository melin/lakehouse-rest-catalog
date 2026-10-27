package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.optimizer.PaimonProcedures.Step;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.paimon.CoreOptions;
import org.apache.paimon.options.ExpireConfig;
import org.apache.paimon.utils.ProcedureUtils;
import org.junit.jupiter.api.Test;

/**
 * {@code CALL} 语句的生成。
 *
 * <p>这类断言看起来像「把实现抄一遍」，但它挡住的是一类**很难在运行前发现**的错误：
 * 参数名拼错、参数类型写成字符串、动作顺序调换——三者都不会让本模块编译失败，
 * 只会在集群上以「procedure 参数不匹配」或「多删了文件」的形式出现。
 * 参数名与类型是照着 Paimon 2.0.0 的 class 文件反解出来的（见 {@link PaimonProcedures}
 * 的类注释），因此这里的断言同时也是「我们对着哪个版本写的」这份记录。
 *
 * <p>时间窗口另有一类**只在集群上才炸**的错误，两条 procedure 各有一条断言：
 * {@code older_than} 必须是 Paimon 能解析的绝对时间戳（orphan 侧，
 * {@link #orphanWindowIsParseableByPaimonItself()} 用 Paimon 自己的解析器做往返校验）；
 * {@code snapshot.time-retained} 的键名与时长必须能被 Paimon 的选项链路认出来
 * （{@link #retentionOptionSurvivesPaimonOwnOptionParsing()}）。曾经这里对 expire 也写
 * {@code older_than => '24 h'}，读起来很自然地像「相对时长」，集群上直接
 * {@code DateTimeParseException}。
 */
class PaimonProceduresTests {

    private static final String CATALOG = "paimon";
    private static final String TABLE = "db.t1";

    /** 固定时刻，且截到秒：{@code older_than} 只写到秒，断言才能写成等号。 */
    private static final Instant NOW = Instant.parse("2026-10-17T06:00:00Z");

    private static final Pattern OLDER_THAN = Pattern.compile("older_than => '([^']*)'");

    private static final Pattern OPTIONS = Pattern.compile("options => '([^']*)'");

    @Test
    void compactBinpackOnlySendsThresholds() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"), Map.of("compact", Map.of(
                "strategy", "binpack",
                "min-input-files", "5",
                "target-file-size-mb", "128")), NOW);

        assertEquals(1, steps.size());
        assertEquals("compact", steps.get(0).action());
        assertEquals("CALL paimon.sys.compact(table => 'db.t1',"
                        + " options => 'compaction.min.file-num=5,target-file-size=128 mb')",
                steps.get(0).statement());
        // binpack 不传 compact_strategy：让 Paimon 按表类型自己选，
        // 替它决定会在主键表上选错（主键表走 LSM compaction，不是 full）。
        assertFalse(steps.get(0).statement().contains("compact_strategy"));
    }

    @Test
    void compactSortTranslatesStrategyAndColumns() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"), Map.of("compact", Map.of(
                "strategy", "sort",
                "sort-columns", "dt,id",
                "min-input-files", "8",
                "target-file-size-mb", "256")), NOW);

        String sql = steps.get(0).statement();
        // compact_strategy 只接受 full/minor（排序意图由 order_strategy 表达），
        // 而且 order_strategy 的取值是 CoreOptions.OrderType 的枚举名（大写）。
        assertTrue(sql.contains("compact_strategy => 'full'"), sql);
        assertTrue(sql.contains("order_strategy => 'ORDER'"), sql);
        assertTrue(sql.contains("order_by => 'dt,id'"), sql);
        assertTrue(sql.contains("options => 'compaction.min.file-num=8,target-file-size=256 mb'"), sql);
    }

    @Test
    void sortStrategyWithoutColumnsFails() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"),
                        Map.of("compact", Map.of("strategy", "sort", "min-input-files", "5",
                                "target-file-size-mb", "128")), NOW));
        assertTrue(error.getMessage().contains("sort-columns"), error.getMessage());
    }

    @Test
    void expireExpressesRetentionAsDurationOption() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("expire"), Map.of("expire", Map.of(
                "retention-hours", "24",
                "retain-min", "10")), NOW);

        // 「保留最近 24 小时」只有 snapshot.time-retained 能直接表达；older_than 收的是
        // 「窗口起点这个时刻」，写 '24 h' 会在执行期 DateTimeParseException。
        // retain_min 是整数形参，带引号在 ANSI 模式下会直接抛转换错误。
        assertEquals("CALL paimon.sys.expire_snapshots(table => 'db.t1',"
                        + " options => 'snapshot.time-retained=24 h', retain_min => 10)",
                steps.get(0).statement());
        assertFalse(steps.get(0).statement().contains("older_than"));
        // retain_max 刻意不传：它会额外限制「最多留几个快照」，替用户猜一个上限
        // 会在快照变多时删掉仍在窗口内的快照。
        assertFalse(steps.get(0).statement().contains("retain_max"));
    }

    @Test
    void orphanDryRunIsBooleanLiteral() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("orphan"), Map.of("orphan", Map.of(
                "older-than-hours", "48",
                "dry-run", "false")), NOW);

        assertEquals("CALL paimon.sys.remove_orphan_files(table => 'db.t1',"
                        + " older_than => '" + expected(48) + "', dry_run => false)",
                steps.get(0).statement());
    }

    @Test
    void actionsRunInFixedOrder() {
        Map<String, Map<String, String>> options = Map.of(
                "compact", Map.of("min-input-files", "5", "target-file-size-mb", "128"),
                "expire", Map.of("retention-hours", "24", "retain-min", "10"),
                "orphan", Map.of("older-than-hours", "24", "dry-run", "true"));

        // 传入顺序无关，输出顺序固定为 compact → expire → orphan：
        // compaction 产生新快照后 expire 才能判过期，orphan 最后跑才安全。
        // 注意步骤上的 action 是「动作的 wire 名」（与服务端契约同一套词），
        // procedure 名（expire_snapshots / remove_orphan_files）只出现在语句里。
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("orphan", "expire", "compact"),
                options, NOW);

        assertEquals(List.of("compact", "expire", "orphan"), steps.stream().map(Step::action).toList());
        assertEquals("CALL paimon.sys.expire_snapshots(table => 'db.t1',"
                        + " options => 'snapshot.time-retained=24 h', retain_min => 10)",
                steps.get(1).statement());
        assertEquals("CALL paimon.sys.remove_orphan_files(table => 'db.t1',"
                        + " older_than => '" + expected(24) + "', dry_run => true)",
                steps.get(2).statement());
    }

    /**
     * orphan 的 {@code older_than} 必须能被 **Paimon 自己的解析器**吃下去。
     *
     * <p>这是本文件里唯一「对着真实实现」的断言，也是唯一能挡住 {@code '24 h'} 那类错误的
     * 地方：语法上它只是一个字符串，编译器与肉眼都不会反对，到集群上才
     * {@code DateTimeParseException}。这里用同一份 {@code DateTimeUtils} 解析、再把结果
     * 折回毫秒比对，顺带钉住「格式化用的时区就是解析用的时区」。
     */
    @Test
    void orphanWindowIsParseableByPaimonItself() {
        String sql = PaimonProcedures.statement(CATALOG, "orphan", TABLE,
                Map.of("older-than-hours", "24", "dry-run", "true"), NOW);

        Matcher matcher = OLDER_THAN.matcher(sql);
        assertTrue(matcher.find(), sql);
        String olderThan = matcher.group(1);

        // 形状：本地时间戳，不带任何时长后缀（'24 h'、'1 d' 都会挂在这一行）
        assertTrue(olderThan.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"), olderThan);

        long parsed = org.apache.paimon.utils.DateTimeUtils
                .parseTimestampData(olderThan, 3, TimeZone.getDefault())
                .getMillisecond();
        assertEquals(NOW.minus(24, ChronoUnit.HOURS).toEpochMilli(), parsed,
                "the window start must survive Paimon's own parser");
    }

    /**
     * expire 的保留窗口要能通过 **Paimon 自己的选项解析链路**，结果正好等于 N 小时。
     *
     * <p>{@code options => 'snapshot.time-retained=24 h'} 与 {@code older_than} 的差别，
     * 单看 SQL 看不出来：键名写错一个字符，Paimon 读不到这个键、也不会报错，
     * 「保留 24 小时」就悄悄退化成表的默认 1 天（1 天正好也是默认值，肉眼更难发现）。所以这里把语句里的选项取出来，
     * 走一遍 {@code ExpireSnapshotsProcedure} 的路径
     * （选项串 → {@code CoreOptions} → {@code ProcedureUtils.fillInSnapshotOptions}），
     * 断言最终落到 {@code ExpireConfig.snapshotTimeRetain == 24h}。
     */
    @Test
    void retentionOptionSurvivesPaimonOwnOptionParsing() {
        String sql = PaimonProcedures.statement(CATALOG, "expire", TABLE,
                Map.of("retention-hours", "24", "retain-min", "10"), NOW);

        Matcher matcher = OPTIONS.matcher(sql);
        assertTrue(matcher.find(), sql);
        Map<String, String> dynamic =
                org.apache.paimon.utils.ParameterUtils.parseCommaSeparatedKeyValues(matcher.group(1));
        // 键名必须是 Paimon 自己的常量，而不是我们手写的同名字符串
        assertTrue(dynamic.containsKey(CoreOptions.SNAPSHOT_TIME_RETAINED.key()), sql);

        ExpireConfig config = ProcedureUtils
                .fillInSnapshotOptions(new CoreOptions(dynamic), null, 10, null, null)
                .build();
        assertEquals(Duration.ofHours(24), config.getSnapshotTimeRetain());
    }

    @Test
    void nonNumericOrNonPositiveWindowFailsBeforeTouchingTheCluster() {
        // 窗口取值来自策略；写错了应该在生成语句时就失败，而不是把 'abc h' 发到集群上
        assertThrows(IllegalArgumentException.class, () -> PaimonProcedures.statement(
                CATALOG, "expire", TABLE, Map.of("retention-hours", "abc", "retain-min", "10"), NOW));
        assertThrows(IllegalArgumentException.class, () -> PaimonProcedures.statement(
                CATALOG, "orphan", TABLE, Map.of("older-than-hours", "0", "dry-run", "true"), NOW));
    }

    @Test
    void missingActionIsNotEmitted() {
        List<Step> steps = PaimonProcedures.steps(CATALOG, TABLE, List.of("compact"),
                Map.of("compact", Map.of("min-input-files", "5", "target-file-size-mb", "128")), NOW);
        assertEquals(1, steps.size());
        assertEquals(List.of(), PaimonProcedures.steps(CATALOG, TABLE, List.of(), Map.of(), NOW));
    }

    @Test
    void unknownActionFailsInsteadOfBeingIgnored() {
        // 服务端已经裁剪过动作集合，这里是第二道闸：拼错的动作名不该被静默跳过
        // （跳过等于「以为跑过了」）。
        assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.normalizeActions(List.of("compact-all")));
        assertEquals(List.of("compact"), PaimonProcedures.normalizeActions(List.of("COMPACT", "compact")));
    }

    @Test
    void tableNameMustBelongToTheBatchCatalog() {
        assertEquals("db.t1", PaimonProcedures.withinCatalog("paimon", "paimon.db.t1"));

        // 前缀对不上意味着任务拿到的清单与它连的 catalog 不是一回事，
        // 继续执行会去优化另一张同名表——必须失败而不是「去掉第一段试试」。
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.withinCatalog("paimon", "other.db.t1"));
        assertTrue(error.getMessage().contains("does not belong"), error.getMessage());

        // 只有库名、没有表名
        assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.withinCatalog("paimon", "paimon.db"));
    }

    @Test
    void singleQuotesInValuesAreEscaped() {
        // 值全部来自用户配置，按 SQL 惯例用两个单引号转义，而不是把字符串直接拼进去
        String sql = PaimonProcedures.statement(CATALOG, "compact", TABLE, Map.of(
                "strategy", "sort", "sort-columns", "a'b", "min-input-files", "5",
                "target-file-size-mb", "128"), NOW);
        assertTrue(sql.contains("order_by => 'a''b'"), sql);

        // 反例的护栏：正常取值里不该出现转义后的引号
        String normal = PaimonProcedures.statement(CATALOG, "expire", TABLE,
                Map.of("retention-hours", "24", "retain-min", "10"), NOW);
        assertFalse(normal.contains("''"), normal);
    }

    @Test
    void illegalTableNameIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.steps(CATALOG, "db.t; DROP", List.of(), Map.of(), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> PaimonProcedures.steps(CATALOG, "db.t 1", List.of(), Map.of(), NOW));
    }

    /** 期望的窗口起点（与实现同一条规则：{@code now - hours}，按 JVM 默认时区格式化）。 */
    private static String expected(long hours) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(NOW.minus(hours, ChronoUnit.HOURS));
    }
}
