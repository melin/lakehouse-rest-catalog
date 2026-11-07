package io.github.melin.lakehouse.rest.optimizer;

import io.github.melin.lakehouse.rest.optimizer.OptimizationApiClient.Plan;
import io.github.melin.lakehouse.rest.optimizer.OptimizationApiClient.Table;
import io.github.melin.lakehouse.rest.optimizer.SparkSessions.Format;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 表存储优化任务的入口：一个批次一个进程。
 *
 * <pre>
 *   拉执行清单 → 建一次 Spark 会话 → 逐表执行 compact/expire/orphan → 逐表回调上报
 * </pre>
 *
 * <p><b>为什么一个批次只建一次 Spark 会话。</b>这正是服务端组批的目的：一个 Spark 任务的
 * 固定开销（pod 调度、镜像拉取、SparkContext 启动）与它处理几张表无关，而每张表的优化
 * 是秒级到分钟级的轻活。一表一任务在几十张表的规模下会把集群调度打满。
 *
 * <p><b>每张表独立 try/catch。</b>一张表失败不拖死同批其它表——这是 `PARTIAL_SUCCESS`
 * 这个批次状态存在的唯一理由。失败原因连同指标一起上报到该表的明细上，重试时只重跑
 * 失败的那些表（服务端按明细状态挑，与这里无关）。
 *
 * <p><b>退出码。</b>全部成功/跳过 → 0；有失败 → 1。服务端会用 k8s Job 的失败计数把批次
 * 收尾，但**表级结论以回调为准**：没上报的表会被服务端按失败处理，因此这里的退出码
 * 只影响「批次级」的日志可读性，不承担状态判定。
 */
public final class TableOptimizerJob {

    private static final Logger log = LoggerFactory.getLogger(TableOptimizerJob.class);

    /** 回调超时。上报是幂等的小请求，超过这个时间基本是网络或服务端不健康。 */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    /** Paimon 的「数据文件清单」系统表名，跟着表名用 {@code $} 相连（{@code tbl$files}）。 */
    private static final String PAIMON_FILES_TABLE = "files";

    private TableOptimizerJob() {
    }

    public static void main(String[] args) {
        OptimizerOptions options;
        try {
            options = OptimizerOptions.parse(args);
        } catch (RuntimeException exception) {
            log.error("cannot start the optimizer: {}", exception.getMessage());
            System.exit(2);
            return;
        }
        int exitCode = run(options);
        // 显式 System.exit：Spark 会留下非守护线程，不退出会让容器一直挂着，
        // 而 k8s Job 的完成判定依赖容器退出码。
        System.exit(exitCode);
    }

    static int run(OptimizerOptions options) {
        try (OptimizationApiClient client =
                     new OptimizationApiClient(options.managementUrl(), options.callbackToken(), HTTP_TIMEOUT)) {
            Plan plan;
            try {
                plan = client.fetchPlan(options.jobId());
            } catch (RuntimeException exception) {
                // 清单都拉不到就没有 item id 可上报，只能靠退出码让服务端把批次判为失败。
                log.error("cannot fetch the plan of batch {}: {}", options.jobId(), exception.getMessage());
                return 1;
            }
            Format format;
            try {
                format = Format.parse(plan.format());
            } catch (IllegalArgumentException exception) {
                // 格式不认识就整批不跑：所有表都会走进同一个错误分支，而按 Paimon 猜一个
                // 只会让每张表以「表不存在」失败、把批次标注成 PARTIAL_SUCCESS
                log.error("batch {}: {}", plan.jobId(), exception.getMessage());
                return 1;
            }
            log.info("batch {}: catalog={}, format={}, actions={}, tables={}",
                    plan.jobId(), plan.catalog(), format, plan.actions(), plan.tables().size());

            SparkSession session = null;
            int failed = 0;
            for (Table table : plan.tables()) {
                if (table.settled()) {
                    // 任务重启（例如 pod 被驱逐后重排）时，上一次已经上报过的表不再跑一遍：
                    // compaction 会重写文件，重跑不是「无副作用的重试」。
                    log.info("skip table {}: already reported as {}", table.table(), table.status());
                    continue;
                }
                try {
                    if (session == null) {
                        session = spark(plan, options, format);
                    }
                    execute(session, client, plan, table, format);
                } catch (RuntimeException exception) {
                    failed++;
                    log.error("table {} failed", table.table(), exception);
                    reportFailure(client, plan, table, exception);
                }
            }
            if (session != null) {
                session.stop();
            }
            log.info("batch {} finished, {} table(s) failed", plan.jobId(), failed);
            return failed == 0 ? 0 : 1;
        }
    }

    // ------------------------------------------------------------------ 单表执行

    /** 一步：动作 wire 名 + 语句。三种格式的步骤在调度上完全同构，只有语句不同。 */
    private record Step(String action, String statement) {
    }

    /**
     * 一步执行失败，带着**失败的那条语句**往外抛。
     *
     * <p>为什么单开一个类型：Spark 的异常常常只说「解析失败 / 分析失败」，
     * 而真正要复现的是那条 {@code CALL}——参数、窗口、表名全在里面。能拿到语句的作用域只有
     * 步骤循环内部（{@code execute} 的 catch 在外层，手里只有表名），所以用异常把它带出去：
     * 日志与上报的失败原因里就都带着它，控制台上不用翻 Pod 日志也能照原样重放。
     */
    static final class StepFailure extends RuntimeException {

        StepFailure(String action, String statement, RuntimeException cause) {
            // 语句放在最前面：失败原因列只有 1000 字，后面的 cause 链可以被截，语句不能。
            super(action + " failed. SQL: " + statement, cause);
        }
    }

    /** 按固定顺序执行该表的动作，采集指标，最后上报一次结论。 */
    private static void execute(SparkSession session, OptimizationApiClient client, Plan plan,
                                Table table, Format format) {
        String within = PaimonProcedures.withinCatalog(plan.catalog(), table.table());
        List<String> actions = PaimonProcedures.normalizeActions(plan.actions());
        // 窗口基准只取一次：三步共用同一个「现在」，否则跨步骤的几分钟会让
        // expire 与 orphan 的窗口起点相差一截（批内可复现，批间不可比）。
        Instant now = Instant.now();
        List<Step> steps = steps(plan, within, actions, table.options(), now, format);
        if (steps.isEmpty()) {
            client.report(plan.jobId(), table.itemId(), "SKIPPED",
                    Map.of("reason", "no enabled action for this table"), null);
            return;
        }
        Map<String, String> metrics = new LinkedHashMap<>();
        boolean changed = false;
        for (Step step : steps) {
            long started = System.currentTimeMillis();
            StepResult result;
            try {
                result = switch (format) {
                    case PAIMON -> runPaimonStep(session, plan.catalog(), within, step);
                    case ICEBERG -> runIcebergStep(session, step);
                    case LANCE -> runLanceStep(session, step);
                };
            } catch (RuntimeException exception) {
                // 单独打一行「语句」，而不是只靠上面的栈：栈里那条 CallSite 是 Paimon/Spark 的
                // 内部帧，日志里按 "statement:" 一搜就能找到本次到底发了什么。
                log.error("table {}: {} step failed, statement: {}",
                        within, step.action(), step.statement());
                throw new StepFailure(step.action(), step.statement(), exception);
            }
            result.metrics().forEach((key, value) -> metrics.put(step.action() + "." + key, value));
            metrics.put(step.action() + ".duration-ms", Long.toString(System.currentTimeMillis() - started));
            log.info("table {} {} done in {} ms: {}", within, step.action(),
                    metrics.get(step.action() + ".duration-ms"), result.metrics());
            changed |= result.changed();
        }
        // 「跑过但无事可做」是正常分支，不该记成成功：compress 未达阈值、没有过期快照、
        // 没有孤儿文件都属此类，控制台上要能一眼分出「真的合了文件」和「扫了一遍没动」。
        client.report(plan.jobId(), table.itemId(), changed ? "SUCCESS" : "SKIPPED",
                metrics, changed ? null : "nothing to do: all steps reported no change");
    }

    /**
     * 该表要执行的步骤，按格式翻译。
     *
     * <p>三种格式的动作顺序与上报结构完全一样（{@code compact → expire → orphan}、
     * 指标带动作前缀），因此只在「语句怎么生成」这一处分派——把执行流程也拆成三套，
     * 只会让「跳过 / 部分成功 / 耗时」这些共同语义各写一遍。
     *
     * <p>Lance 的 {@code ORDER} 里只有 {@code compact} 与 {@code expire}：{@code orphan}
     * 由 {@code VACUUM} 一并包含，服务端也不会把它派下来（{@code ActionPolicy.supported}）。
     */
    private static List<Step> steps(Plan plan, String within, List<String> actions,
                                    Map<String, Map<String, String>> options, Instant now,
                                    Format format) {
        List<Step> steps = new ArrayList<>();
        switch (format) {
            case PAIMON -> {
                for (PaimonProcedures.Step step
                        : PaimonProcedures.steps(plan.catalog(), within, actions, options, now)) {
                    steps.add(new Step(step.action(), step.statement()));
                }
            }
            case ICEBERG -> {
                for (IcebergProcedures.Step step
                        : IcebergProcedures.steps(plan.catalog(), within, actions, options, now)) {
                    steps.add(new Step(step.action(), step.statement()));
                }
            }
            case LANCE -> {
                for (LanceProcedures.Step step
                        : LanceProcedures.steps(plan.catalog(), within, actions, options, now)) {
                    steps.add(new Step(step.action(), step.statement()));
                }
            }
        }
        return steps;
    }

    /** 执行一步 Paimon procedure，把输出行转成指标。{@code step.action()} 是动作的 wire 名。 */
    private static StepResult runPaimonStep(SparkSession session, String catalog, String table, Step step) {
        Map<String, String> metrics = new LinkedHashMap<>();
        switch (step.action()) {
            case PaimonProcedures.COMPACT -> {
                Files filesBefore = files(session, catalog, table);
                Row row = session.sql(step.statement()).head();
                boolean applied = asBoolean(row, "result");
                Files filesAfter = files(session, catalog, table);
                boolean grew = filesBefore != null && filesAfter != null
                        && filesAfter.files() != filesBefore.files();
                // 读不到文件数时（系统表不可用）不写这两组指标，而不是写 0：
                // 0 在控制台上看起来像「表里没有文件」，与「没测到」是两件事。
                if (filesBefore != null && filesAfter != null) {
                    metrics.put("files-before", Long.toString(filesBefore.files()));
                    metrics.put("files-after", Long.toString(filesAfter.files()));
                    metrics.put("size-before", humanSize(filesBefore.bytes()));
                    metrics.put("size-after", humanSize(filesAfter.bytes()));
                }
                return new StepResult(applied || grew, metrics);
            }
            case PaimonProcedures.EXPIRE -> {
                Row row = session.sql(step.statement()).head();
                long deleted = asLong(row, "deleted_snapshots_count");
                metrics.put("deleted-snapshots", Long.toString(deleted));
                return new StepResult(deleted > 0, metrics);
            }
            case PaimonProcedures.ORPHAN -> {
                Row row = session.sql(step.statement()).head();
                long files = asLong(row, "deletedFileCount");
                long bytes = asLong(row, "deletedFileTotalLenInBytes");
                metrics.put("deleted-files", Long.toString(files));
                metrics.put("released-size", humanSize(bytes));
                // 干跑模式下也报 SUCCESS：用户按下的就是「预览」，预览出结果就是本次的目的。
                // dry-run 与否由策略参数决定，这里不替它判断。
                return new StepResult(files > 0 || dryRun(step), metrics);
            }
            default -> throw new IllegalArgumentException("unsupported action: " + step.action());
        }
    }

    /**
     * 执行一步 Iceberg procedure。
     *
     * <p><b>指标口径与 Paimon 侧不同，这是数据本身决定的。</b>
     * {@code rewrite_data_files} 不给压缩前后的文件数（它只报重写/新增了多少），
     * {@code expire_snapshots} 不给「删了几个快照」（它只报删了多少数据与清单文件）。
     * 因此这里报的是 procedure 真正输出的列，控制台的指标展示按动作兼容两套键；
     * 硬把 Iceberg 的读数翻译成 Paimon 的键（例如把 deleted_data_files_count
     * 当成 deleted-snapshots）会让同一个键在两种表上含义不同，那是更坏的选择。
     */
    private static StepResult runIcebergStep(SparkSession session, Step step) {
        Map<String, String> metrics = new LinkedHashMap<>();
        switch (step.action()) {
            case IcebergProcedures.COMPACT -> {
                Row row = session.sql(step.statement()).head();
                long rewritten = asLong(row, "rewritten_data_files_count");
                long added = asLong(row, "added_data_files_count");
                long bytes = asLong(row, "rewritten_bytes_count");
                metrics.put("rewritten-files", Long.toString(rewritten));
                metrics.put("added-files", Long.toString(added));
                metrics.put("rewritten-size", humanSize(bytes));
                return new StepResult(rewritten > 0, metrics);
            }
            case IcebergProcedures.EXPIRE -> {
                Row row = session.sql(step.statement()).head();
                long data = asLong(row, "deleted_data_files_count");
                long manifests = asLong(row, "deleted_manifest_files_count");
                long manifestLists = asLong(row, "deleted_manifest_lists_count");
                long statistics = asLong(row, "deleted_statistics_files_count");
                metrics.put("deleted-data-files", Long.toString(data));
                metrics.put("deleted-manifest-files", Long.toString(manifests));
                metrics.put("deleted-manifest-lists", Long.toString(manifestLists));
                if (statistics > 0) {
                    metrics.put("deleted-statistics-files", Long.toString(statistics));
                }
                return new StepResult(data + manifests + manifestLists + statistics > 0, metrics);
            }
            case IcebergProcedures.ORPHAN -> {
                // remove_orphan_files 的输出是「一行一个孤儿文件路径」，没有汇总列。
                // count() 会真的把 procedure 跑完（干跑时就是把候选列全），这也是我们要的：
                // 干跑的产出就是这份清单的数量。
                long files = session.sql(step.statement()).count();
                metrics.put("deleted-files", Long.toString(files));
                return new StepResult(files > 0 || dryRun(step), metrics);
            }
            default -> throw new IllegalArgumentException("unsupported action: " + step.action());
        }
    }

    private static boolean dryRun(Step step) {
        return step.statement().contains("dry_run => true");
    }

    /**
     * 执行一步 Lance 的 {@code OPTIMIZE} / {@code VACUUM}。
     *
     * <p><b>指标口径是第三种，这是 Lance 自己给的列决定的。</b>{@code OPTIMIZE} 报的是
     * 碎片与文件的进出四个数，{@code VACUUM} 报的是「删了多少字节 / 多少个旧版本」。
     * 键名与另两套刻意**不重叠**（{@code fragments-removed} 这类词在 Paimon / Iceberg 里
     * 不存在），于是控制台的 {@code metricSummary()} 仍然「只判键存在、不判格式」——
     * 一个批次里混着三种格式的表时，页面不需要知道每张表是什么格式。
     *
     * <p>{@code VACUUM} 不可逆且没有干跑：这里不回「nothing to do 也报 SUCCESS」那种话。
     * 一个旧版本都没删时 {@code changed=false}，上报的是 {@code SKIPPED}，与另两种格式一致。
     */
    private static StepResult runLanceStep(SparkSession session, Step step) {
        Map<String, String> metrics = new LinkedHashMap<>();
        switch (step.action()) {
            case LanceProcedures.COMPACT -> {
                Row row = session.sql(step.statement()).head();
                long fragmentsRemoved = asLong(row, "fragments_removed");
                long fragmentsAdded = asLong(row, "fragments_added");
                long filesRemoved = asLong(row, "files_removed");
                long filesAdded = asLong(row, "files_added");
                metrics.put("fragments-removed", Long.toString(fragmentsRemoved));
                metrics.put("fragments-added", Long.toString(fragmentsAdded));
                metrics.put("files-removed", Long.toString(filesRemoved));
                metrics.put("files-added", Long.toString(filesAdded));
                long changed = fragmentsRemoved + fragmentsAdded + filesRemoved + filesAdded;
                return new StepResult(changed > 0, metrics);
            }
            case LanceProcedures.EXPIRE -> {
                Row row = session.sql(step.statement()).head();
                long versions = asLong(row, "old_versions");
                long bytes = asLong(row, "bytes_removed");
                metrics.put("old-versions", Long.toString(versions));
                // 与 Paimon 的 released-size 同一口径：采集侧就转成人类可读，
                // 控制台不为了「字节怎么显示」再实现一遍（table-optimization.md §6）
                metrics.put("bytes-removed", humanSize(bytes));
                return new StepResult(versions > 0, metrics);
            }
            default -> throw new IllegalArgumentException("unsupported lance action: " + step.action());
        }
    }

    /**
     * 表的文件数/总大小。走 Paimon 的 {@code $files} 系统表，不需要 Paimon 的 Java 依赖。
     *
     * <p><b>拿不到就返回 null，不让整张表失败。</b>系统表是 Paimon 的实现细节：它要对
     * {@code table$files} 这个名字做本地解析、再自己读 manifest 与文件系统。任何一环
     * 不成立（catalog 实现不支持、令牌拿不到对象存储、表是外部表），失败的都只是
     * 「compaction 前后各少一组数字」，而 compaction 本身与它无关。指标缺失可以在控制台
     * 上看见，整表 FAILED 却会让批次重跑一遍——重跑 compaction 不是无副作用的。
     */
    private static Files files(SparkSession session, String catalog, String table) {
        String statement = "SELECT count(1) AS files, coalesce(sum(file_size_in_bytes), 0) AS bytes FROM "
                + fileStatisticsTable(catalog, table);
        try {
            Row row = session.sql(statement).head();
            return new Files(asLong(row, "files"), asLong(row, "bytes"));
        } catch (RuntimeException failure) {
            log.warn("cannot read the file statistics of {} via {} ({}):"
                    + " compaction still runs, only the before/after counts are missing",
                    table, statement, describe(failure));
            return null;
        }
    }

    /**
     * Paimon {@code $files} 系统表的 Spark 名字。
     *
     * <p>三件都不能少，每一件都对应一个踩过的坑：
     *
     * <ol>
     *   <li><b>表名段加反引号</b>：{@code $} 不是 Spark 未加引号标识符的合法字符，
     *       {@code FROM demo.tbl$files} 会在解析期报
     *       {@code PARSE_SYNTAX_ERROR Syntax error at or near '$'}。用反引号包住**最后一段**
     *       （{@code demo.`tbl$files`}），既让解析器接受，又让整串作为一个标识符交给
     *       Paimon 的 {@code Identifier} 去按 {@code $} 拆成「表 + 系统表名」。
     *       给整串加反引号（{@code `demo.tbl$files`}）不行：那样成了单段标识符。
     *   <li><b>catalog 必须全限定</b>：任务会话把批次所属 catalog 注册成
     *       {@code spark.sql.catalog.<name>}，但清单里的表名是 catalog 内的
     *       {@code database.table}（见 {@link PaimonProcedures#withinCatalog}）。
     *       少了 catalog 前缀，{@code demo.tbl} 会去默认的 {@code spark_catalog} 里找。
     *   <li><b>不能用 {@code USE} 之类的会话状态代替前缀</b>：这里只借用当前 catalog，
     *       不做隐式状态，下次换 catalog 时不会有一半语句落在旧 catalog 上。
     * </ol>
     */
    static String fileStatisticsTable(String catalog, String table) {
        int dot = table.lastIndexOf('.');
        // 恰好两段。多出来的段说明清单给的不是 catalog 内的 database.table，
        // 按最后一段当表名会拼出一个解析不了的三级名，不如在这里直接说清楚。
        if (dot < 0 || dot != table.indexOf('.')) {
            throw new IllegalArgumentException("table name must be database.table, got " + table);
        }
        String database = table.substring(0, dot);
        String name = table.substring(dot + 1);
        for (String part : new String[] {catalog, database, name}) {
            if (part.indexOf('`') >= 0) {
                throw new IllegalArgumentException("illegal identifier (contains a backquote): " + part);
            }
        }
        return catalog + "." + database + ".`" + name + "$" + PAIMON_FILES_TABLE + "`";
    }

    private record Files(long files, long bytes) {
    }

    private record StepResult(boolean changed, Map<String, String> metrics) {
    }

    // ------------------------------------------------------------------ 上报

    private static void reportFailure(OptimizationApiClient client, Plan plan, Table table, RuntimeException cause) {
        try {
            client.report(plan.jobId(), table.itemId(), "FAILED", Map.of(), describe(cause));
        } catch (RuntimeException exception) {
            // 上报失败不再抛：本表的结论没记上，服务端在批次收尾时会把未上报的明细按失败处理，
            // 结果是一致的（可能丢了具体原因），比让整个批次因为「上报失败」而中断要好。
            log.error("cannot report the failure of table {}: {}", table.table(), exception.getMessage());
        }
    }

    /**
     * 上报的失败原因上限。列宽 1000（`lakehouse_optimization_job_item.error_message`），
     * 留 100 字余量——宁可少一段 cause 链，也不要让服务端截断时把开头的语句切掉。
     */
    private static final int REASON_LIMIT = 900;

    /**
     * 把异常链压成一行给服务端看。
     *
     * <p>{@link StepFailure} 的消息自带「动作 + 语句」，不再套类名：它的消息就是要给用户看的
     * 那句话，套上 {@code StepFailure: } 只会白占长度。
     */
    static String describe(Throwable cause) {
        StringBuilder message = new StringBuilder();
        Throwable current = cause;
        while (current != null && message.length() < REASON_LIMIT) {
            if (!message.isEmpty()) {
                message.append(" <- ");
            }
            if (current instanceof StepFailure) {
                message.append(current.getMessage());
            } else {
                message.append(current.getClass().getSimpleName())
                        .append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return message.length() > REASON_LIMIT
                ? message.substring(0, REASON_LIMIT) : message.toString();
    }

    // ------------------------------------------------------------------ Spark 会话

    /**
     * 建会话：**实现已抽到 {@link SparkSessions}**（下推扫描用同一份，见
     * {@code docs/sensitive-data-design.md} §5.6.5）。
     *
     * <p>留这个薄壳而不是让调用点直接调静态方法：{@code appName} 用哪个、{@code catalogUrl}
     * 从哪来，是本任务的知识；「三种格式各配哪些键」不是。边界就在这一行。
     */
    private static SparkSession spark(Plan plan, OptimizerOptions options, Format format) {
        return SparkSessions.open("lakehouse-rest-optimizer-" + options.jobId(),
                plan.catalog(), format, options.catalogUrl(), options.catalogToken());
    }

    // ------------------------------------------------------------------ 取值辅助

    private static boolean asBoolean(Row row, String field) {
        Object value = value(row, field);
        return value instanceof Boolean flag && flag;
    }

    private static long asLong(Row row, String field) {
        Object value = value(row, field);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 按名取列，找不到就退回按位置取。
     *
     * <p>procedure 的输出列名（如 {@code deleted_snapshots_count}、{@code deletedFileCount}）
     * 在不同 Paimon 版本里改过大小写风格，而**输出只有一个列**这件事一直没变。
     * 按名取是首选（可读、版本差异能被立刻发现），失败时按位置兜底可以让优化任务
     * 在一个「只是改了列名」的新版本上继续工作，而不是整批失败。
     */
    private static Object value(Row row, String field) {
        try {
            return row.getAs(field);
        } catch (IllegalArgumentException exception) {
            List<String> names = new ArrayList<>();
            for (String name : row.schema().fieldNames()) {
                names.add(name);
            }
            if (names.size() != 1) {
                throw new IllegalStateException("cannot find column '" + field + "' in the procedure output,"
                        + " available columns are " + names, exception);
            }
            log.warn("procedure output has no column '{}' but exactly one column '{}';"
                    + " reading it positionally (a Paimon version changed the output name)",
                    field, names.get(0));
            return row.get(0);
        }
    }

    /** 人类可读的大小，与服务端控制台展示同一口径（KB/MB/GB，一位小数）。 */
    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB", "PB"};
        double value = bytes;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
    }
}
