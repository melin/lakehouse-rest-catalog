package io.github.melin.lakehouse.rest.optimizer;

import io.github.melin.lakehouse.rest.optimizer.OptimizationApiClient.Plan;
import io.github.melin.lakehouse.rest.optimizer.OptimizationApiClient.Table;
import io.github.melin.lakehouse.rest.optimizer.PaimonProcedures.Step;
import java.time.Duration;
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

    /** Paimon 自己的 Spark 扩展。catalog 初始化会检查它，缺了连库都建不了。 */
    private static final String PAIMON_EXTENSION =
            "org.apache.paimon.spark.extensions.PaimonSparkSessionExtensions";

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
            log.info("batch {}: catalog={}, actions={}, tables={}",
                    plan.jobId(), plan.catalog(), plan.actions(), plan.tables().size());

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
                        session = spark(plan.catalog(), options);
                    }
                    execute(session, client, plan, table);
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

    /** 按固定顺序执行该表的动作，采集指标，最后上报一次结论。 */
    private static void execute(SparkSession session, OptimizationApiClient client, Plan plan, Table table) {
        String within = PaimonProcedures.withinCatalog(plan.catalog(), table.table());
        List<String> actions = PaimonProcedures.normalizeActions(plan.actions());
        List<Step> steps = PaimonProcedures.steps(plan.catalog(), within, actions, table.options());
        if (steps.isEmpty()) {
            client.report(plan.jobId(), table.itemId(), "SKIPPED",
                    Map.of("reason", "no enabled action for this table"), null);
            return;
        }
        Map<String, String> metrics = new LinkedHashMap<>();
        boolean changed = false;
        for (Step step : steps) {
            long started = System.currentTimeMillis();
            StepResult result = runStep(session, within, step);
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

    /** 执行一步，把 procedure 的输出行转成指标。{@code step.action()} 是动作的 wire 名。 */
    private static StepResult runStep(SparkSession session, String table, Step step) {
        Map<String, String> metrics = new LinkedHashMap<>();
        switch (step.action()) {
            case PaimonProcedures.COMPACT -> {
                Files filesBefore = files(session, table);
                Row row = session.sql(step.statement()).head();
                boolean applied = asBoolean(row, "result");
                Files filesAfter = files(session, table);
                metrics.put("files-before", Long.toString(filesBefore.files()));
                metrics.put("files-after", Long.toString(filesAfter.files()));
                metrics.put("size-before", humanSize(filesBefore.bytes()));
                metrics.put("size-after", humanSize(filesAfter.bytes()));
                return new StepResult(applied || filesAfter.files() != filesBefore.files(), metrics);
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

    private static boolean dryRun(Step step) {
        return step.statement().contains("dry_run => true");
    }

    /** 表的文件数/总大小。走 Paimon 的 {@code $files} 系统表，不需要 Paimon 的 Java 依赖。 */
    private static Files files(SparkSession session, String table) {
        Row row = session.sql("SELECT count(1) AS files, coalesce(sum(file_size_in_bytes), 0) AS bytes FROM "
                + table + "$files").head();
        return new Files(asLong(row, "files"), asLong(row, "bytes"));
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

    private static String describe(Throwable cause) {
        StringBuilder message = new StringBuilder();
        Throwable current = cause;
        while (current != null && message.length() < 900) {
            if (!message.isEmpty()) {
                message.append(" <- ");
            }
            message.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            current = current.getCause();
        }
        return message.toString();
    }

    // ------------------------------------------------------------------ Spark 会话

    /**
     * 建会话：把批次所属 catalog 配成 Spark 的一个 namespace，其余交给 Paimon 自己的扩展。
     *
     * <p>{@code warehouse} 传的是 catalog 的 **prefix**（与端到端测试同一套配法）：
     * 客户端会用它换取 catalog 的实际标识，后续所有 {@code /v1/...} 路径都以它开头。
     *
     * <p>{@code data-token.enabled=true} 不能省：catalog 的仓库可能落在对象存储上，
     * 任务自己没有任何密钥——端点和凭据都由服务端下发，这正是 REST catalog 存在的意义。
     * 对本地文件系统仓库无害（服务端仍回一份自包含令牌，本地 FileIO 不读它）。
     */
    private static SparkSession spark(String catalog, OptimizerOptions options) {
        org.apache.spark.sql.SparkSession.Builder builder = SparkSession.builder()
                .appName("lakehouse-rest-optimizer-" + options.jobId())
                .config("spark.sql.extensions", PAIMON_EXTENSION)
                .config("spark.sql.catalog." + catalog, "org.apache.paimon.spark.SparkCatalog")
                .config("spark.sql.catalog." + catalog + ".metastore", "rest")
                .config("spark.sql.catalog." + catalog + ".uri", options.catalogUrl())
                .config("spark.sql.catalog." + catalog + ".warehouse", catalog)
                .config("spark.sql.catalog." + catalog + ".data-token.enabled", "true");
        if (options.catalogToken() != null) {
            builder.config("spark.sql.catalog." + catalog + ".token.provider", "bear")
                    .config("spark.sql.catalog." + catalog + ".token", options.catalogToken());
        } else {
            log.info("no catalog token provided: connecting to {} without data plane credentials,"
                    + " which only works on deployments with authentication disabled", options.catalogUrl());
        }
        SparkSession session = builder.getOrCreate();
        session.sparkContext().setLogLevel("WARN");
        log.info("spark session ready for catalog {}", catalog);
        return session;
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
