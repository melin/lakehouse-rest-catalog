package io.github.melin.lakehouse.rest.optimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.scan.ScanColumn;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanTableSpec;
import java.util.List;
import org.apache.spark.sql.execution.SparkSqlParser;
import org.junit.jupiter.api.Test;

/**
 * 扫描作业里「拼出去的东西」的形状：采样语句、标识符引号、结构化列的过滤，以及启动参数。
 *
 * <p><b>为什么断言的是解析器而不是字符串。</b>{@code `demo`.`db`.`order`} 与
 * {@code demo.db.order} 在字符串比较里都「看起来对」，差别只在 Spark 解析器眼里：
 * 后者遇到保留字就报 {@code PARSE_SYNTAX_ERROR}，整个扫描的第一张表就失败。判定权在
 * 解析器手里，抄一份规则到断言里只会跟着错（优化任务里那条 {@code $files} 语句的测试
 * 是同一个理由）。
 *
 * <p><b>参数解析也在这里测</b>：{@code --scan-id} 缺失时的报错是运维唯一能看到的东西，
 * 而「作业被误传 {@code --job-id}」是这条链路最可能的人工失误（两个入口同镜像）。
 */
class SensitivityScannerJobTests {

    private final SparkSqlParser parser = new SparkSqlParser();

    private static ScanTableSpec table(String database, String name, ScanColumn... columns) {
        return new ScanTableSpec(database, name, List.of(columns));
    }

    /**
     * 三段标识符都加引号。
     *
     * <p>断言的是**字节级形状**（每一段都被反引号包住）加**解析器接受**：形状是「不依赖
     * 解析器宽容度」的那一半，解析是另一半。三处都用关键字做名字（{@code order} /
     * {@code select} / {@code from}），因为它们是最容易在别的格式或版本上翻车的一类。
     */
    @Test
    void samplingStatementQuotesEveryIdentifier() {
        String sql = SensitivityScannerJob.selectStatement("demo",
                table("sales", "order", new ScanColumn("select", "varchar(18)", null),
                        new ScanColumn("from", "bigint", null)),
                List.of(new ScanColumn("select", "varchar(18)", null),
                        new ScanColumn("from", "bigint", null)),
                500);
        assertEquals("SELECT `select`, `from` FROM `demo`.`sales`.`order` LIMIT 500", sql);
        // 真正要断言的是这一句：解析器接受它
        parser.parsePlan(sql);
    }

    /** 采样行数传 0 时按 1 处理：{@code LIMIT 0} 会得到「一趟都不跑」的假采样。 */
    @Test
    void sampleSizeFallsBackToOne() {
        ScanTableSpec spec = table("db", "t", new ScanColumn("c", "string", null));
        assertTrue(SensitivityScannerJob.selectStatement("demo", spec,
                List.of(new ScanColumn("c", "string", null)), 0).endsWith("LIMIT 1"));
    }

    /**
     * 结构化列进不了采样语句，标量列照旧。
     *
     * <p>判据落在**类型名的第一段**上：{@code array<int>} / {@code map<string,int>} 是
     * Spark 的写法，{@code list<item: int>} 是 Lance 的 Arrow 写法，两者都要被排除——
     * 而 {@code decimal(12,2)}、{@code varchar(18)} 这类带括号的标量不能被误判。
     */
    @Test
    void complexTypesAreExcludedFromSamplingButScalarsAreNot() {
        assertFalse(SensitivityScannerJob.isSamplable(new ScanColumn("tags", "array<int>", null)));
        assertFalse(SensitivityScannerJob.isSamplable(new ScanColumn("attrs", "map<string,int>", null)));
        assertFalse(SensitivityScannerJob.isSamplable(new ScanColumn("addr", "struct<a:int>", null)));
        assertFalse(SensitivityScannerJob.isSamplable(new ScanColumn("nums", "list<item: int>", null)));
        assertFalse(SensitivityScannerJob.isSamplable(new ScanColumn("meta", "ARRAY<STRING>", null)));

        assertTrue(SensitivityScannerJob.isSamplable(new ScanColumn("id_card", "varchar(18)", null)));
        assertTrue(SensitivityScannerJob.isSamplable(new ScanColumn("amount", "decimal(12,2)", null)));
        assertTrue(SensitivityScannerJob.isSamplable(new ScanColumn("name", "string", null)));
        assertTrue(SensitivityScannerJob.isSamplable(new ScanColumn("photo", "binary", null)));
    }

    /**
     * 名字里的反引号直接拒绝，而不是转义。
     *
     * <p>表名来自服务端，出现反引号说明上游已经不对了；「转义一下继续跑」等于把一条
     * 注入式的名字当正常名字拼进语句。抛出点在拼语句之前，因此不会有一条半成品 SQL 被下发。
     */
    @Test
    void backquoteInNameIsRejected() {
        ScanTableSpec evil = table("db", "t`; DROP TABLE x; --", new ScanColumn("c", "string", null));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> SensitivityScannerJob.selectStatement("demo", evil,
                        List.of(new ScanColumn("c", "string", null)), 10));
        assertTrue(failure.getMessage().contains("backquote"), failure.getMessage());
    }

    /** 空表名同样在拼语句前失败：{@code ``} 是合法标识符，会去扫一张名叫空串的表。 */
    @Test
    void blankNameIsRejected() {
        ScanTableSpec blank = table(" ", "t", new ScanColumn("c", "string", null));
        assertThrows(IllegalArgumentException.class, () -> SensitivityScannerJob.selectStatement(
                "demo", blank, List.of(new ScanColumn("c", "string", null)), 10));
    }

    /**
     * 启动参数：缺 {@code --scan-id} 时点名这个参数，且**不接受 {@code --job-id} 顶替**。
     *
     * <p>这条是「同一个镜像、两个入口」的直接后果：把优化批次号传给扫描入口时，
     * 应该在这里失败，而不是拿它去查扫描清单（那会 404，然后按「拉不到清单」退出，
     * 日志里一句话都不提参数）。
     */
    @Test
    void scannerRejectsOptionsOfTheOptimizer() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class,
                () -> ScannerOptions.parse(new String[]{
                        "--job-id", "abc",
                        "--management-url", "http://server:8080/api/management/v1",
                        "--token", "t"}));
        assertTrue(missing.getMessage().contains("--scan-id"), missing.getMessage());

        IllegalArgumentException noValue = assertThrows(IllegalArgumentException.class,
                () -> ScannerOptions.parse(new String[]{"--scan-id"}));
        assertTrue(noValue.getMessage().contains("missing value"), noValue.getMessage());
    }

    /**
     * catalog 地址由管理 API 基址推出，等号形式与斜杠都吃。
     *
     * <p>推导是这条链路唯一「猜」的地方：猜错的表现是作业连不上数据面，而错误信息指向
     * 「表不存在」。因此 {@code --catalog-url} 显式给值时必须覆盖推导结果。
     */
    @Test
    void catalogUrlIsDerivedFromTheManagementUrl() {
        ScannerOptions derived = ScannerOptions.parse(new String[]{
                "--scan-id=scan-1",
                "--management-url", "http://server:8080/api/management/v1/",
                "--token", "tok"});
        assertEquals("scan-1", derived.scanId());
        assertEquals("http://server:8080/api/management/v1", derived.managementUrl());
        assertEquals("http://server:8080", derived.catalogUrl());
        // 可选参数没给就是 null，不是空串：会话构建按 null 判「这个部署不用凭据」
        assertNull(derived.catalogToken());

        ScannerOptions explicit = ScannerOptions.parse(new String[]{
                "--scan-id", "scan-1",
                "--management-url", "http://server:8080/api/management/v1",
                "--token", "tok",
                "--catalog-url", "http://gateway/lakehouse",
                "--catalog-token", ""});
        assertEquals("http://gateway/lakehouse", explicit.catalogUrl());
        assertNull(explicit.catalogToken());
    }

    /** 参数值里出现 {@code --} 时按「缺值」失败，而不是把下一个参数名当成值。 */
    @Test
    void valueStartingWithDashesIsTreatedAsMissing() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ScannerOptions.parse(new String[]{"--scan-id", "--token", "x"}));
        assertTrue(failure.getMessage().contains("missing value for --scan-id"), failure.getMessage());
    }
}
