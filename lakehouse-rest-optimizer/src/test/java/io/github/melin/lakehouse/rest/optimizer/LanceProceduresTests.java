package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.optimizer.LanceProcedures.Step;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.apache.spark.sql.catalyst.parser.extensions.LanceSqlExtensionsLexer;
import org.apache.spark.sql.catalyst.parser.extensions.LanceSqlExtensionsParser;
import org.apache.spark.sql.catalyst.parser.extensions.UpperCaseCharStream;
import org.junit.jupiter.api.Test;

/**
 * Lance {@code OPTIMIZE} / {@code VACUUM} 语句的生成。
 *
 * <p>与另两份 {@code *ProceduresTests} 的最大差别：这里的语句**不是 {@code CALL}**，
 * 而是 connector 自己那套 ANTLR 语法里的 parser 级命令。所以除了字符串断言，
 * 这里还用 connector 真实的 lexer/parser 把每条生成的语句**解析一遍**——字符串断言
 * 只能证明「我们拼出了想拼的东西」，语法树才证明「connector 认这个东西」。
 * 三条真实约束都是这么定下来的：
 *
 * <ul>
 *   <li>标识符要逐段加反引号（{@code `order`} 这类保留字、名字里的 {@code -}）；</li>
 *   <li>布尔必须写字面量 {@code TRUE/FALSE}，写成 {@code 'true'} 在 {@code WITH} 子句里
 *       是字符串；</li>
 *   <li>浮点必须**带小数点**：裸 {@code 1} 会被词法分析成 {@code BIGINT_LITERAL}，
 *       而 connector 的 exec 里是 {@code value.asInstanceOf[Float]}，运行期才抛
 *       {@code ClassCastException}。</li>
 * </ul>
 *
 * <p>语法解析用的类来自 {@code lance-spark-bundle} 的测试作用域依赖（与镜像里那个 jar
 * 同一个构件），因此这条断言同时守住了「升级 connector 时语句形状是否还能用」。
 */
class LanceProceduresTests {

    private static final String CATALOG = "lake";
    private static final String TABLE = "sales.orders";
    private static final Instant NOW = Instant.parse("2026-10-17T06:00:00Z");

    /** 服务端下发的「合并后全部参数」——与 {@code LanceOptimizationOptions.defaults} 一致。 */
    private static Map<String, String> compactDefaults() {
        return Map.of(
                "target-rows-per-fragment", "1048576",
                "max-rows-per-group", "1024",
                "materialize-deletions", "true",
                "materialize-deletions-threshold", "0.1",
                "defer-index-remap", "false",
                "interval-hours", "1");
    }

    private static Map<String, String> expireDefaults() {
        return Map.of(
                "retention-hours", "72",
                "delete-unverified", "false",
                "interval-days", "7");
    }

    @Test
    void compactRendersEveryOptionButBatchSize() {
        List<Step> steps = LanceProcedures.steps(CATALOG, TABLE, List.of("compact"),
                Map.of("compact", compactDefaults()), NOW);

        assertEquals(1, steps.size());
        assertEquals("compact", steps.get(0).action());
        assertEquals("OPTIMIZE `lake`.`sales`.`orders` WITH ("
                + "target_rows_per_fragment = 1048576, "
                + "max_rows_per_group = 1024, "
                + "materialize_deletions = TRUE, "
                + "materialize_deletions_threshold = 0.1, "
                + "defer_index_remap = FALSE)", steps.get(0).statement());
        // batch-size 不给就不写：connector 里它是 Option，给了会让输出按字节截断，
        // 与 target_rows_per_fragment 一起用容易产出行数没到但文件满了的碎片
        assertFalse(steps.get(0).statement().contains("batch_size"), steps.get(0).statement());
        // interval-hours 是调度参数，永远不进语句
        assertFalse(steps.get(0).statement().contains("interval"), steps.get(0).statement());
    }

    @Test
    void batchSizeAppearsOnlyWhenConfigured() {
        Map<String, String> configured = new java.util.LinkedHashMap<>(compactDefaults());
        configured.put("batch-size", "8192");

        String sql = LanceProcedures.statement(CATALOG, "compact", TABLE, configured, NOW);
        assertTrue(sql.contains("batch_size = 8192"), sql);
    }

    @Test
    void vacuumPinsBothSafetyConstants() {
        List<Step> steps = LanceProcedures.steps(CATALOG, TABLE, List.of("expire"),
                Map.of("expire", expireDefaults()), NOW);

        assertEquals(1, steps.size());
        assertEquals("VACUUM `lake`.`sales`.`orders` WITH ("
                        + "before_timestamp_millis = "
                        + Instant.parse("2026-10-14T06:00:00Z").toEpochMilli() + ", "
                        // 恒定 false：true 的语义是「删掉验证不了引用关系的文件」
                        + "delete_unverified = FALSE, "
                        // 恒定 true（connector 的默认值是 false）：待删版本里有打标签的，
                        // 整个 VACUUM 报错退出，而不是静默删掉用户留的回滚点
                        + "error_if_tagged_old_versions = TRUE)",
                steps.get(0).statement());
        assertFalse(steps.get(0).statement().contains("interval_days"), steps.get(0).statement());
    }

    @Test
    void retentionWindowIsEpochMillisWithoutTimezone() {
        String sql = LanceProcedures.statement(CATALOG, "expire", TABLE, expireDefaults(), NOW);
        long expected = NOW.minus(72, ChronoUnit.HOURS).toEpochMilli();
        assertTrue(sql.contains("before_timestamp_millis = " + expected), sql);
        // 三个格式里唯一一处不碰时区：给出的是绝对毫秒，没有格式化也没有解析
        assertFalse(sql.contains("TIMESTAMP '"), sql);
    }

    @Test
    void actionsRunCompactionBeforeExpire() {
        List<Step> steps = LanceProcedures.steps(CATALOG, TABLE, List.of("expire", "compact"),
                Map.of("compact", compactDefaults(), "expire", expireDefaults()), NOW);

        // 顺序由 ORDER 决定，与调用方给的顺序无关：先 OPTIMIZE 产生新版本，
        // VACUUM 才基于最新状态判过期
        assertEquals(List.of("compact", "expire"),
                steps.stream().map(Step::action).toList());
    }

    @Test
    void orphanIsRejectedOnLance() {
        RuntimeException failure = assertThrows(IllegalArgumentException.class,
                () -> LanceProcedures.steps(CATALOG, TABLE, List.of("orphan"), Map.of(), NOW));
        assertTrue(failure.getMessage().contains("VACUUM already removes"), failure.getMessage());
    }

    @Test
    void unknownOptionFailsInsteadOfBeingDropped() {
        Map<String, String> stale = new java.util.LinkedHashMap<>(compactDefaults());
        stale.put("min-input-files", "5");

        RuntimeException failure = assertThrows(IllegalArgumentException.class,
                () -> LanceProcedures.statement(CATALOG, "compact", TABLE, stale, NOW));
        assertTrue(failure.getMessage().contains("min-input-files"), failure.getMessage());
    }

    @Test
    void decimalAlwaysCarriesAPoint() {
        Map<String, String> integerLike = new java.util.LinkedHashMap<>(compactDefaults());
        integerLike.put("materialize-deletions-threshold", "1");

        // 裸 1 会被词法分析成 BIGINT_LITERAL，connector 那边按 Float 取值 → ClassCastException
        assertTrue(LanceProcedures.statement(CATALOG, "compact", TABLE, integerLike, NOW)
                .contains("materialize_deletions_threshold = 1.0"));

        integerLike.put("materialize-deletions-threshold", "0.05");
        assertTrue(LanceProcedures.statement(CATALOG, "compact", TABLE, integerLike, NOW)
                .contains("materialize_deletions_threshold = 0.05"));
    }

    @Test
    void identifiersAreQuotedSegmentBySegment() {
        // 整串加反引号会变成「单段标识符」，解析器会去找一张名叫 lake.sales.order 的表
        assertEquals("`lake`.`sales`.`order`", LanceProcedures.qualified("lake", "sales.order"));
        // 段内不允许出现反引号：没有合法 id 需要它，拒绝比转义更容易证明没有注入面
        assertThrows(IllegalArgumentException.class, () -> LanceProcedures.qualified("lake", "a`b"));
    }

    @Test
    void generatedStatementsParseWithTheRealConnectorGrammar() {
        List<Step> steps = LanceProcedures.steps(CATALOG, TABLE, List.of("compact", "expire"),
                Map.of("compact", compactDefaults(), "expire", expireDefaults()), NOW);
        assertEquals(2, steps.size());
        for (Step step : steps) {
            assertParses(step.statement());
        }
        // 带反引号外最容易被忽略的一种：表名是保留字
        assertParses(LanceProcedures.statement(CATALOG, "compact", "db.table",
                compactDefaults(), NOW));
    }

    /**
     * 用 connector 的真实语法解析一条语句。
     *
     * <p>复刻 {@code LanceSparkSqlExtensionsParser.parse} 的两步：套上
     * {@code UpperCaseCharStream}（语法开了 case-insensitive，词法规则按大写匹配，
     * 不套这一层小写字母会被当成无法识别的字符），再去掉默认的报错监听、改收集。
     */
    private static void assertParses(String sql) {
        LanceSqlExtensionsLexer lexer = new LanceSqlExtensionsLexer(
                new UpperCaseCharStream(CharStreams.fromString(sql)));
        lexer.removeErrorListeners();
        LanceSqlExtensionsParser parser = new LanceSqlExtensionsParser(new CommonTokenStream(lexer));
        parser.removeErrorListeners();
        StringBuilder errors = new StringBuilder();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String message,
                                    RecognitionException exception) {
                errors.append("line ").append(line).append(':').append(charPositionInLine)
                        .append(' ').append(message).append("; ");
            }
        });
        parser.singleStatement();
        assertEquals("", errors.toString(), "the connector cannot parse: " + sql);
        assertEquals(0, parser.getNumberOfSyntaxErrors(), "the connector cannot parse: " + sql);
    }
}
