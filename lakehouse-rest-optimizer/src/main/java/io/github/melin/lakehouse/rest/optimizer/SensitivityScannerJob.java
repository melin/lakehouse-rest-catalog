package io.github.melin.lakehouse.rest.optimizer;

import io.github.melin.lakehouse.rest.optimizer.SparkSessions.Format;
import io.github.melin.lakehouse.rest.scan.ScanColumn;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanFinding;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanItemReport;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanJobSpec;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanTableSpec;
import io.github.melin.lakehouse.rest.scan.ScanProtocol.ScanType;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.CandidateType;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.ColumnValues;
import io.github.melin.lakehouse.rest.scan.SensitivityDetectors.Match;
import io.github.melin.lakehouse.rest.scan.SensitivityEnums.SamplingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 敏感数据识别的下推扫描（{@code execution=SPARK}）：一个扫描一个进程
 * （设计见 {@code docs/sensitive-data-design.md} §5.6.5）。
 *
 * <pre>
 *   分页拉执行清单 → 建一次 Spark 会话 → 逐表 LIMIT 采样 → 调内核打分 → 打码 → 逐表回报
 * </pre>
 *
 * <p><b>与优化任务的三处不同，每一处都是设计稿点名的：</b>
 * <ol>
 *   <li><b>一个扫描 = 一个 Job，不组批</b>（第 1 条）。因此这里没有「批次清单」，
 *       只有「按 {@code hasMore} 一直翻页」：几千张表也不切 Job，否则「同一张表同时只允许
 *       一个扫描」这条互斥会被绕过；</li>
 *   <li><b>「没命中」必须上报成结果</b>（第 2 条）。{@code findings} 为空、状态仍是
 *       {@code SCANNED}——服务端的覆盖率靠这个区分「扫完没发现」与「漏扫」；</li>
 *   <li><b>原始值不出 Pod</b>（第 3 条）。采样值只在
 *       {@link SensitivityDetectors} 的内存里活一次，落进回调的是
 *       {@link ScanFinding#of} 打码后的样本。**本类不打日志里的任何值**：一旦有一行
 *       {@code log.debug("values: {}", values)}，整个「不出 Pod」就不成立了，而它不会
 *       在任何测试里表现出来。</li>
 * </ol>
 *
 * <p><b>判定逻辑一行都不在这里。</b>探测器、打分器、校验器来自 {@code lakehouse-rest-scan-kernel}
 * ——与服务端进程内扫描是同一份字节码。设计稿明确禁止「把正则翻译成 Spark SQL 表达式」：
 * 那会造出第二份打分实现，权重与公式立刻分叉，而分叉的表现是「同一个 catalog 两次扫描
 * 结论不同」，查起来没有尽头。这里只负责「把行拉回来」。
 *
 * <p><b>采样方式恒 {@code HEAD}</b>（{@code LIMIT n}，按文件顺序读前 n 行），与服务端
 * 进程内的 {@code SensitivitySampler} 同一口径。标 {@code TABLESAMPLE} 会让
 * 「同一份数据、两种执行位置」给出不同置信度，而样本偏斜的风险由打分器的样本量打折与
 * 「值层只占 0.40」共同承担。
 *
 * <p><b>退出码。</b>全部扫成 → 0；有表失败 → 1。服务端用 k8s Job 的失败计数兜底，
 * 但**表级结论以回调为准**：没回调的表在服务端按超时失败处理。
 */
public final class SensitivityScannerJob {

    private static final Logger log = LoggerFactory.getLogger(SensitivityScannerJob.class);

    /** 回报超时。回报是幂等的小请求，超过这个时间基本是网络或服务端不健康。 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    /**
     * 每次拉多少张表。
     *
     * <p>服务端会把请求值夹到它自己的上限（{@code SUBMISSION_PAGE_MAX = 200}）。这里直接
     * 给上限值而不是「先问一次集群配置」：分页大小只影响往返次数，不影响扫描范围，
     * 而多一次握手就多一个「清单还没开始拉就失败」的位置。
     */
    private static final int PAGE_SIZE = 200;

    /** 能采样值的模式。其余（含 {@code METADATA_ONLY}）只跑 D1 + D2。 */
    private static final String SAMPLED = "SAMPLED";

    /**
     * 不能取值的类型（结构体 / 数组 / 映射这类）。
     *
     * <p><b>它们被排除在 {@code SELECT} 之外，而不是让整张表失败。</b>值层对结构体本来
     * 就没有意义（正则与校验器吃的是一段文本），而把这些列带进采样语句的代价是
     * **一张表里只要有一列读不出来，整张表就没采到样**——症状是「这个库整体置信度偏低」，
     * 而原因只是某张表有一个 map 列。
     *
     * <p>元数据两层（名称 / 类型）对这些列照常生效：它们仍然参与判定，只是没有值证据。
     */
    private static final Set<String> COMPLEX_TYPES = Set.of(
            "array", "map", "struct", "row", "uniontype", "variant", "object", "list");

    private SensitivityScannerJob() {
    }

    public static void main(String[] args) {
        ScannerOptions options;
        try {
            options = ScannerOptions.parse(args);
        } catch (RuntimeException exception) {
            log.error("cannot start the sensitivity scanner: {}", exception.getMessage());
            System.exit(2);
            return;
        }
        int exitCode = run(options);
        // 显式 System.exit：Spark 留下非守护线程，不退出会让容器一直挂着，
        // 而 k8s Job 的完成判定依赖容器退出码
        System.exit(exitCode);
    }

    static int run(ScannerOptions options) {
        try (ScanApiClient client = new ScanApiClient(
                options.managementUrl(), options.callbackToken(), HTTP_TIMEOUT)) {
            ScanJobSpec first;
            try {
                first = client.page(options.scanId(), 0, PAGE_SIZE);
            } catch (RuntimeException exception) {
                // 清单拉不到就一张表也没有，没有表名可回报：只能靠退出码让服务端判失败，
                // 剩下的由扫描超时兜底
                log.error("cannot fetch the submission of scan {}: {}",
                        options.scanId(), exception.getMessage());
                return 1;
            }
            Format format;
            try {
                format = Format.parse(first.format());
            } catch (IllegalArgumentException exception) {
                // 格式不认识就整轮不跑：所有表都会走进同一个错误分支，而按 Paimon 猜一个
                // 只会让每张表以「表不存在」失败，把真实原因（镜像太旧）埋掉
                log.error("scan {}: {}", options.scanId(), exception.getMessage());
                return 1;
            }
            List<CandidateType> types = candidates(first);
            boolean sample = SAMPLED.equalsIgnoreCase(first.mode());
            log.info("scan {}: catalog={}, format={}, mode={}, sampleSize={}, tables={}, types={}",
                    first.scanId(), first.catalog(), format, first.mode(), first.sampleSize(),
                    first.totalTables(), types.size());
            if (types.isEmpty()) {
                // 没有候选类型时每一列都判不出来，扫完也是「0 命中」——那不是干净，是没判据。
                // 直接失败，让人去查内置类型是不是没 seed 上，而不是等一轮扫描白跑
                log.error("scan {}: no candidate sensitive type was provided;"
                        + " nothing could be detected (check the builtin type seed)", first.scanId());
                return 1;
            }

            SparkSession session = null;
            int scanned = 0;
            int failed = 0;
            int offset = 0;
            while (true) {
                ScanJobSpec page = offset == 0 ? first : client.page(options.scanId(), offset, PAGE_SIZE);
                if (page.tables().isEmpty()) {
                    // 空页却 hasMore=true 只可能来自「翻页期间范围变小了」（表被删）。
                    // 不 break 的话这里会一直空转
                    break;
                }
                for (ScanTableSpec table : page.tables()) {
                    try {
                        if (session == null) {
                            session = SparkSessions.open("lakehouse-rest-scan-" + page.scanId(),
                                    page.catalog(), format, options.catalogUrl(), options.catalogToken());
                        }
                        ScanItemReport report = scanTable(session, page, table, types, sample);
                        client.report(page.scanId(), report);
                        scanned++;
                    } catch (RuntimeException exception) {
                        failed++;
                        log.error("table {}.{} failed", table.database(), table.table(), exception);
                        reportFailure(client, page, table, exception);
                    }
                }
                log.info("scan {}: {} of {} table(s) done", page.scanId(),
                        offset + page.tables().size(), page.totalTables());
                if (!page.hasMore()) {
                    break;
                }
                // 按**实际拿到的张数**前进而不是按 PAGE_SIZE：清单是每次请求现算的
                // （表可能在翻页期间被删），短页时按 PAGE_SIZE 前进会静默跳过几张表
                offset += page.tables().size();
            }
            if (session != null) {
                session.stop();
            }
            log.info("scan {} finished: {} table(s) scanned, {} failed",
                    first.scanId(), scanned, failed);
            return failed == 0 ? 0 : 1;
        }
    }

    // ------------------------------------------------------------------ 单表扫描

    /**
     * 扫一张表：采样 → 逐列跑探测器 → 打码 → 组一条上报。
     *
     * <p>{@code columnsScanned} 报的是**全部列**（含因类型复杂而没采到值的那些）：
     * 它是服务端覆盖率的分母，而元数据两层对每一列都跑过。
     */
    private static ScanItemReport scanTable(SparkSession session, ScanJobSpec spec, ScanTableSpec table,
                                            List<CandidateType> types, boolean sample) {
        List<ScanColumn> columns = table.columns() == null ? List.of() : table.columns();
        if (columns.isEmpty()) {
            // 没有列清单 = 服务端那边这张表没有 schema。这不是「扫了没命中」，
            // 是「没法扫」——报成 SCANNED 会让覆盖率把它算成一次干净的扫描
            throw new IllegalStateException("no column metadata was provided for "
                    + table.qualifiedName() + "; the scan cannot judge a table without its schema");
        }
        Map<String, ColumnValues> values = sample
                ? readSamples(session, spec, table, columns)
                : Map.of();
        List<ScanFinding> findings = new ArrayList<>();
        int sampledColumns = 0;
        for (ScanColumn column : columns) {
            ColumnValues columnValues = values.getOrDefault(column.name(), ColumnValues.none());
            if (columnValues.sampled()) {
                sampledColumns++;
            }
            for (Match match : SensitivityDetectors.detect(
                    spec.catalog(), table.table(), column, types, columnValues)) {
                // ScanFinding.of 内部打码：出这个方法的样本已经是 ****** 那一份，
                // 原始值不再有任何引用（§5.4 的唯一不变量）
                findings.add(ScanFinding.of(column.name(), match, columnValues.values(),
                        SamplingMode.HEAD.name()));
            }
        }
        log.info("scanned {}: {} column(s), {} of them sampled, {} finding(s)",
                table.qualifiedName(), columns.size(), sampledColumns, findings.size());
        return new ScanItemReport(table.database(), table.table(), ScanItemReport.STATUS_SCANNED,
                columns.size(), null, findings);
    }

    /**
     * 读一张表的样本，按列转置。
     *
     * <p><b>一次 {@code SELECT} 而不是每列一次</b>：列数是几十到几百，每列一次查询等于把
     * 同一份文件读几十遍，而扫描的主要成本就是这次读。取回的行再按列切开交给探测器，
     * 与 {@code SensitivitySampler} 的「读回行、按列转置」是同一件事。
     *
     * <p><b>结构化列不参与</b>（见 {@link #COMPLEX_TYPES}）：它们的值层判定没有意义，
     * 而带进语句的代价是整张表读失败。
     *
     * <p>{@code null} 值保留在列里：值层的分母是「非空行」，在入口滤掉会让一列
     * 「90% 是 NULL、非空部分全是身份证」算出虚高的命中率。
     */
    /**
     * 采样语句：{@code SELECT `c`... FROM `catalog`.`db`.`tbl` LIMIT n}。
     *
     * <p><b>单独抽出来是为了能测。</b>这条语句的正确性只在**解析期**才暴露（有没有引号、
     * 引号包了几段、{@code LIMIT} 在不在），而它一旦写错，表现出来是「每张表都失败」，
     * 原因藏在一条 {@code PARSE_SYNTAX_ERROR} 里。单元测试用真实的
     * {@link org.apache.spark.sql.execution.SparkSqlParser} 解析它，与优化任务里那条
     * {@code $files} 语句的做法一致。
     *
     * <p>三段都加反引号（{@code `catalog`.`db`.`tbl`}）：表名是 {@code order}、列名是
     * {@code select} 这类名字时，能不能不加引号取决于解析器对「关键字出现在标识符位置」
     * 的宽容度，而那是随版本变的行为。加了引号就只有一种解释，不依赖版本。
     *
     * <p>{@code LIMIT} 至少为 1：{@code LIMIT 0} 会得到「一趟都不跑」的采样，与
     * 「这一列没采到」在打分上不可区分（都走元数据下界），但它看起来像「采样成功」。
     */
    static String selectStatement(String catalog, ScanTableSpec table, List<ScanColumn> columns,
                                  int sampleSize) {
        StringBuilder select = new StringBuilder("SELECT ");
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                select.append(", ");
            }
            select.append(identifier(columns.get(index).name(), "column"));
        }
        select.append(" FROM ").append(qualified(catalog, table));
        select.append(" LIMIT ").append(Math.max(1, sampleSize));
        return select.toString();
    }

    private static Map<String, ColumnValues> readSamples(SparkSession session, ScanJobSpec spec,
                                                         ScanTableSpec table, List<ScanColumn> columns) {
        List<ScanColumn> targets = new ArrayList<>(columns.size());
        for (ScanColumn column : columns) {
            if (isSamplable(column)) {
                targets.add(column);
            }
        }
        if (targets.isEmpty()) {
            return Map.of();
        }
        Dataset<Row> rows = session.sql(selectStatement(spec.catalog(), table, targets, spec.sampleSize()));
        int width = targets.size();
        List<List<String>> perColumn = new ArrayList<>(width);
        for (int index = 0; index < width; index++) {
            perColumn.add(new ArrayList<>(Math.max(1, spec.sampleSize())));
        }
        // toLocalIterator 而不是 collect：容器就是 driver、堆只有 1g，一张宽表的上千行
        // 一次性物化成 Row[] 是没必要的峰值
        Iterator<Row> iterator = rows.toLocalIterator();
        int read = 0;
        while (iterator.hasNext()) {
            Row row = iterator.next();
            for (int index = 0; index < width; index++) {
                // null 与「空串」不同，必须保留 null：见方法注释
                perColumn.get(index).add(row.isNullAt(index) ? null : String.valueOf(row.get(index)));
            }
            read++;
        }
        Map<String, ColumnValues> values = new LinkedHashMap<>();
        for (int index = 0; index < width; index++) {
            values.put(targets.get(index).name(), new ColumnValues(perColumn.get(index)));
        }
        log.debug("sampled {}: {} row(s) x {} column(s)",
                table.qualifiedName(), read, width);
        return values;
    }

    /**
     * 这一列能不能取值。
     *
     * <p>看类型名的第一段：{@code array<int>} / {@code map<string,int>} /
     * {@code struct<a:int>} 都是结构化类型，Lance 的 Arrow 写法还会带 {@code list<item: ...>}。
     * 因此先截掉 {@code <} 之后的部分再比对，而不是要求类型名完全相等。
     */
    static boolean isSamplable(ScanColumn column) {
        String type = column.normalizedType();
        int angle = type.indexOf('<');
        if (angle >= 0) {
            type = type.substring(0, angle);
        }
        return !COMPLEX_TYPES.contains(type.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * 回报一张失败的表。
     *
     * <p><b>回报本身失败不再往外抛</b>：一张表读不出来已经是既定事实，而「上报也失败」
     * 只会让同一轮扫描里剩下的表全都跑不到。这里记一条 error 就继续——那些没上报的表
     * 在服务端会按超时失败处理（§5.6.5 的收尾）。
     *
     * <p>失败原因截断到 1000 字：列宽就是 1000（{@code lakehouse_sensitivity_scan.error_message}
     * 由 SQL 异常链拼出来时常常上千字，超长会让整条 insert 失败——比丢一段栈还糟）。
     */
    private static void reportFailure(ScanApiClient client, ScanJobSpec spec, ScanTableSpec table,
                                      RuntimeException cause) {
        String message = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
        String clipped = message.length() > 1000 ? message.substring(0, 1000) : message;
        try {
            client.report(spec.scanId(), new ScanItemReport(table.database(), table.table(),
                    ScanItemReport.STATUS_FAILED, 0, clipped, List.of()));
        } catch (RuntimeException exception) {
            log.error("cannot report the failure of {}.{}: {}",
                    table.database(), table.table(), exception.getMessage());
        }
    }

    // ------------------------------------------------------------------ 协议细节

    /** 候选类型：服务端已经解析好 {@code detectors_json}，这里只做形状转换。 */
    private static List<CandidateType> candidates(ScanJobSpec spec) {
        List<CandidateType> types = new ArrayList<>(spec.types() == null ? 0 : spec.types().size());
        for (ScanType type : spec.types() == null ? List.<ScanType>of() : spec.types()) {
            types.add(type.toCandidate());
        }
        return types;
    }

    /** {@code `catalog`.`database`.`table`}：三段都加反引号，保留字与特殊字符都不用特判。 */
    private static String qualified(String catalog, ScanTableSpec table) {
        return identifier(catalog, "catalog") + "." + identifier(table.database(), "database")
                + "." + identifier(table.table(), "table");
    }

    /**
     * 标识符加反引号。
     *
     * <p><b>反引号本身要拒绝，而不是转义</b>：这些名字来自服务端，出现反引号说明上游已经
     * 出了问题，而「转义一下继续跑」会让一条注入式的表名被当成正常表名拼进语句。
     */
    private static String identifier(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(what + " name must not be empty");
        }
        if (value.indexOf('`') >= 0) {
            throw new IllegalArgumentException("illegal " + what + " name (contains a backquote): " + value);
        }
        return "`" + value + "`";
    }
}
