package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.spark.sql.catalyst.parser.ParseException;
import org.apache.spark.sql.execution.SparkSqlParser;
import org.junit.jupiter.api.Test;

/**
 * 两件事：compaction 前后取文件数用的那条 SQL 的形状，以及失败原因怎么写。
 *
 * <p>SQL 形状部分断言的是「Spark 能不能解析它」，而不是字符串拼接本身——因为线上事故正是
 * 解析期失败：{@code FROM demo.paimon_sample2$files} 报
 * {@code PARSE_SYNTAX_ERROR Syntax error at or near '$'}，整个优化任务在第一步就挂了，
 * 而 {@code CALL ... compact} 还没轮到。
 *
 * <p>失败原因部分断言的是「语句在不在、排第几、有没有撑破列宽」：这三条决定的是
 * 「看到控制台上一条失败原因，能不能直接照着重放」。
 *
 * <p>用真实的 {@link SparkSqlParser} 而不是正则比对：{@code $}、反引号、三级命名空间
 * 这几件事的判定权在解析器手里，抄一份规则到断言里只会跟着错。
 */
class TableOptimizerJobTests {

    private final SparkSqlParser parser = new SparkSqlParser();

    @Test
    void systemTableSuffixIsQuotedAndCatalogQualified() {
        // 三段都不能少：catalog、库、被反引号包住的「表名$系统表名」
        assertEquals("paimon_catalog.demo.`paimon_sample2$files`",
                TableOptimizerJob.fileStatisticsTable("paimon_catalog", "demo.paimon_sample2"));
    }

    @Test
    void generatedSqlIsParsable() {
        String sql = "SELECT count(1) AS files, coalesce(sum(file_size_in_bytes), 0) AS bytes FROM "
                + TableOptimizerJob.fileStatisticsTable("paimon_catalog", "demo.paimon_sample2");

        // 不抛异常即通过：能解析到逻辑计划，说明 $ 没被当成非法字符
        assertEquals("Project", parser.parsePlan(sql).getClass().getSimpleName());
    }

    @Test
    void bareDollarSignIsRejectedBySpark() {
        // 事故现场的形状。写在这里是为了说明「为什么必须加反引号」：
        // $ 不是 Spark 未加引号标识符的合法字符，这与 Paimon 无关，是解析器的规则。
        assertThrows(ParseException.class,
                () -> parser.parsePlan("SELECT count(1) FROM demo.paimon_sample2$files"));
    }

    @Test
    void quotedWholeNameIsStillWrong() {
        // 反引号必须是「最后一段」而不是整串：整串加引号会变成一个单段标识符，
        // 解析能过，但语义变成了在当前库里找一张叫 demo.paimon_sample2$files 的表。
        assertEquals("Project", parser.parsePlan("SELECT count(1) FROM `demo.paimon_sample2$files`")
                .getClass().getSimpleName());
        assertEquals("paimon_catalog.demo.`paimon_sample2$files`",
                TableOptimizerJob.fileStatisticsTable("paimon_catalog", "demo.paimon_sample2"));
    }

    @Test
    void rejectsNamesThatAreNotDatabaseDotTable() {
        assertThrows(IllegalArgumentException.class,
                () -> TableOptimizerJob.fileStatisticsTable("paimon_catalog", "paimon_sample2"));
        // 三段同样拒绝：清单给的是 catalog 内的 database.table，多出来的段会拼出解析不了的名字
        assertThrows(IllegalArgumentException.class,
                () -> TableOptimizerJob.fileStatisticsTable("paimon_catalog", "demo.sub.paimon_sample2"));
    }

    @Test
    void rejectsBackquoteInAnySegment() {
        assertThrows(IllegalArgumentException.class,
                () -> TableOptimizerJob.fileStatisticsTable("paimon_catalog", "demo.`paimon_sample2"));
        assertThrows(IllegalArgumentException.class,
                () -> TableOptimizerJob.fileStatisticsTable("paimon`_catalog", "demo.paimon_sample2"));
    }

    @Test
    void databaseNameIsKeptAsIs() {
        // 库名不加引号：它是常规标识符，加了引号反而会让大小写敏感的名字在
        // 服务端与 Spark 两侧表现不一致
        String name = TableOptimizerJob.fileStatisticsTable("paimon_catalog", "demo.paimon_sample2");
        assertTrue(name.startsWith("paimon_catalog.demo.`"), name);
    }

    /**
     * 失败原因里必须带上**失败的那条语句**。
     *
     * <p>Spark 的异常常常只说「解析失败 / 分析失败」，而控制台上的人要复现，需要的是参数、
     * 窗口、表名都在里面的那条 {@code CALL}。语句还要排在 cause 链**前面**：失败原因列宽 1000，
     * 后面的链可以被截，语句不能。
     */
    @Test
    void failureReasonCarriesTheStatementThatFailed() {
        String sql = "CALL paimon_catalog.sys.expire_snapshots(table => 'demo.t1',"
                + " options => 'snapshot.time-retained=24 h', retain_min => 10)";

        String reason = TableOptimizerJob.describe(new TableOptimizerJob.StepFailure(
                "expire", sql, new IllegalArgumentException("Text '24 h' could not be parsed")));

        assertTrue(reason.startsWith("expire failed. SQL: " + sql), reason);
        assertTrue(reason.contains("IllegalArgumentException: Text '24 h' could not be parsed"), reason);
        // 不套 "StepFailure: "：它是给用户看的自述句，不是内部异常名
        assertFalse(reason.contains("StepFailure"), reason);
    }

    /** 语句再长、cause 链再深，也不能撑破 1000 字的列（留 100 字余量给服务端）。 */
    @Test
    void failureReasonFitsTheColumn() {
        String sql = "CALL paimon_catalog.sys.compact(table => 'demo.t1', options => '"
                + "compaction.min.file-num=5,target-file-size=128 mb')";
        RuntimeException cause = new RuntimeException("x".repeat(2000));
        cause = new RuntimeException("y".repeat(500), cause);

        String reason = TableOptimizerJob.describe(
                new TableOptimizerJob.StepFailure("compact", sql, cause));

        assertTrue(reason.length() <= 900, "length=" + reason.length());
        // 截断发生在尾部：语句整条还在
        assertTrue(reason.contains(sql), reason);
    }

    /** 普通异常仍带类名——那是唯一能看出「哪一层抛的」的信息。 */
    @Test
    void plainFailureKeepsTheExceptionType() {
        String reason = TableOptimizerJob.describe(
                new IllegalStateException("cannot fetch the plan", new RuntimeException("connection refused")));

        assertEquals("IllegalStateException: cannot fetch the plan"
                + " <- RuntimeException: connection refused", reason);
    }

    /**
     * 清单格式的三种输入，三种结论。
     *
     * <p>这三条合起来是「缺字段回落」与「未知取值回落」的分界：前者是本特性引入前的形状
     * （老服务端没有 {@code format} 字段），回落 Paimon 是兼容；后者说明服务端比任务新，
     * 猜一个分支去执行只会让每张表以「表不存在」失败，而真正的错因是没人看的那行日志。
     */
    @Test
    void formatParsingSeparatesMissingFromUnknown() {
        // 缺字段：老服务端，唯一可能的形状就是 Paimon
        assertEquals(TableOptimizerJob.Format.PAIMON, TableOptimizerJob.Format.parse(null));
        assertEquals(TableOptimizerJob.Format.PAIMON, TableOptimizerJob.Format.parse("  "));
        // 大小写不敏感：wire 名由服务端枚举给出，但不该因为大小写差异就失败
        assertEquals(TableOptimizerJob.Format.LANCE, TableOptimizerJob.Format.parse("lance"));
        assertEquals(TableOptimizerJob.Format.ICEBERG, TableOptimizerJob.Format.parse("ICEBERG"));

        RuntimeException failure = assertThrows(IllegalArgumentException.class,
                () -> TableOptimizerJob.Format.parse("HUDI"));
        // 报错要指名取值与「升级镜像」这条出路
        assertTrue(failure.getMessage().contains("HUDI"), failure.getMessage());
        assertTrue(failure.getMessage().contains("upgrade the optimizer image"), failure.getMessage());
    }
}
