package io.github.melin.lakehouse.rest.spark.lineage;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * 一次执行的归一化产物，形状与服务端 {@code POST /lineage/runs} 的报文一致
 * （{@code docs/lineage-design.md} §6.2）。
 *
 * <p><b>为什么模型在这里（{@code -common}）而不是抽取器旁边。</b>抽取器是 Scala、
 * 按 Spark 版本编两份；这份模型与上报器是纯 Java、两个版本共用一份。放在一起会让
 * {@code -common} 必须选一个 Scala 版本，而 2.12 编出来的 class 在 2.13 的会话里加载不了。
 *
 * <p><b>为什么用 record。</b>它只在「抽取器 → 队列 → HTTP」之间传递，没有原地修改；
 * record 的不可变性正好对上「入队之后不再动它」这条约束——上报线程与 driver 线程
 * 因此不需要任何同步。
 *
 * <p><b>字段与报文一一对应，不做改名。</b>服务端按 {@code RunReport} 反序列化，
 * 两侧各自演化是这类契约最容易烂掉的地方，所以宁可让 Java 侧的命名不那么「Java 味」。
 *
 * <p>{@code principal} 不在这里：它由服务端从令牌解析（客户端自报身份等于让人自己填工号）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LineageRun(String runId,
                         String engine,
                         String engineVersion,
                         String statementType,
                         String writeMode,
                         String sqlHash,
                         String sqlText,
                         long startedAt,
                         long finishedAt,
                         String mode,
                         List<Target> targets,
                         List<Source> sources,
                         Map<String, Object> attributes) {

    /** 引擎名。本期只有 Spark，枚举留在这里是为了让上报方与存储侧的取值同源。 */
    public static final String ENGINE_SPARK = "SPARK";

    /** 语句类型，与服务端 {@code STATEMENT_TYPES} 一致。 */
    public static final String INSERT = "INSERT";
    public static final String MERGE = "MERGE";
    public static final String CTAS = "CTAS";

    /** 写模式，与服务端 {@code WRITE_MODES} 一致。 */
    public static final String APPEND = "APPEND";
    public static final String OVERWRITE = "OVERWRITE";
    public static final String UPSERT = "UPSERT";
    public static final String CREATE = "CREATE";

    /** 运行模式。流式作业的单位是「提交」而不是「每次写入」（§12 #7）。 */
    public static final String BATCH = "BATCH";

    /** 一个写目标：目标表 + 每一列分别来自谁。 */
    public record Target(String table, List<Column> columns) {
    }

    /**
     * 目标列的一条列级依赖。
     *
     * <p>{@code sources} 为空是合法且常见的（常量列、静态分区列）；它<b>不是</b>
     * 「没采集到」——那是整条 run 缺少 {@code columns} 字段。
     */
    public record Column(String targetColumn, String kind, String expression, List<ColumnSource> sources) {
    }

    /** 一个源列。 */
    public record ColumnSource(String table, String column) {
    }

    /** 表级源；{@code kind} 取 {@code TABLE} 或 {@code EXTERNAL}（{@code ~} 前缀的节点）。 */
    public record Source(String table, String kind) {
    }
}
