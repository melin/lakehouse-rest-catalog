package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.apache.spark.sql.catalyst.parser.ParseException;
import org.apache.spark.sql.execution.SparkSqlParser;
import org.junit.jupiter.api.Test;

/**
 * compaction 前后取文件数用的那条 SQL 的形状。
 *
 * <p>这里断言的是「Spark 能不能解析它」，而不是字符串拼接本身——因为线上事故正是
 * 解析期失败：{@code FROM demo.paimon_sample2$files} 报
 * {@code PARSE_SYNTAX_ERROR Syntax error at or near '$'}，整个优化任务在第一步就挂了，
 * 而 {@code CALL ... compact} 还没轮到。
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
}
