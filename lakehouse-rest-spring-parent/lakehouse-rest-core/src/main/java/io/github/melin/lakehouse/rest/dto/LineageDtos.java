package io.github.melin.lakehouse.rest.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.util.List;
import java.util.Map;

/**
 * 血缘的请求与响应模型（管理 API 基址 {@code /api/management/v1/lineage}）。
 *
 * <p>形状与 {@code docs/lineage-design.md} §6.2 / §8 一一对应。两处值得先说清楚：
 *
 * <ul>
 *   <li><b>上报与查询刻意不问同一个问题。</b>上报是「这次执行里，哪一列来自哪一列」；
 *       查询是「这张表上下游是谁」，因此边在入库时就被折成状态（upsert），
 *       返回的是<b>当前</b>的图，不是某次执行的快照（§7.1 的代价已写在那里）。</li>
 *   <li><b>{@code nodes} + {@code edges} 而不是嵌套树。</b>血缘图可能有环
 *       （{@code A → B → A} 的回刷是真实存在的），嵌套结构在环上只能靠人为截断，
 *       而扁平结构 + 服务端的 {@code visited} 集合是唯一稳的表示（§8.1）。</li>
 * </ul>
 *
 * <p>与其余管理 API 的模型一样用 record：只在控制器与服务层之间传递，无原地修改。
 */
public final class LineageDtos {

    private LineageDtos() {
    }

    // ------------------------------------------------------------------ 上报

    /** 批量上报的请求体（§6.2）。 */
    public record ReportBatch(List<RunReport> runs) {
    }

    /**
     * 一次执行的完整产物。
     *
     * <p>{@code principal} 不在报文里：由服务端从令牌解析。客户端自报身份
     * 等于让人自己填工号（§6.2）。
     */
    public record RunReport(String runId,
                            String engine,
                            String engineVersion,
                            String statementType,
                            String writeMode,
                            String sqlHash,
                            String sqlText,
                            Long startedAt,
                            Long finishedAt,
                            String mode,
                            List<TargetReport> targets,
                            List<SourceReport> sources,
                            Map<String, Object> attributes) {
    }

    /** 一个写目标：目标表 + 每一列的来源。 */
    public record TargetReport(String table, List<ColumnReport> columns) {
    }

    /**
     * 目标列的一条列级依赖。
     *
     * <p>{@code sources} 为空是合法且常见的：常量列与静态分区列没有源（§4.5）。
     * 它<b>不是</b>「没采集到」——那是整条 run 缺少 {@code columns} 字段。
     */
    public record ColumnReport(String targetColumn,
                               String kind,
                               String expression,
                               List<ColumnSource> sources) {
    }

    /** 一个源列。 */
    public record ColumnSource(String table, String column) {
    }

    /** 表级源。{@code kind} 取 {@code TABLE} / {@code EXTERNAL}（{@code ~} 前缀的节点）。 */
    public record SourceReport(String table, String kind) {
    }

    /**
     * 上报结果。
     *
     * <p>{@code deduplicated} 是**重复的 runId 条数**（幂等命中），不是布尔值：
     * 一次批量里可能一半是新的一半是重试的，只回 {@code true/false} 会让客户端
     * 无法判断「我这条到底进没进库」。
     */
    public record IngestResult(int accepted, int deduplicated, int truncatedColumns) {
    }

    // ------------------------------------------------------------------ 图

    /**
     * 图上的一个节点。
     *
     * <p>{@code objectType} 只有两种：{@code TABLE}（目录里点得开）与
     * {@code EXTERNAL}（{@code ~} 前缀的外部节点）。视图算前者——它有名字、
     * 有定义、能点开，只是 {@code analyzed} 计划里已经被展开成底表（§4.1）。
     *
     * @param stale 目录里已经找不到这张表（边还在）。客户端标灰但照常画
     * @param more  该方向上再走一跳是否还有邻居（画不画 {@code ›} 的依据，§8.6）
     */
    public record TableNode(String ref, String objectType, String format, boolean stale, boolean more) {
    }

    /**
     * 表级边。
     *
     * @param kind       {@code TABLE} / {@code EXTERNAL}
     * @param writeMode  最近一次的写模式：{@code APPEND} / {@code OVERWRITE} / {@code UPSERT} / {@code CREATE}
     * @param columnCount 该对表之间的列级边数；{@code columnCount=false} 时为 {@code null}
     */
    public record TableEdge(String source,
                            String target,
                            String kind,
                            String writeMode,
                            String engine,
                            long lastSeen,
                            long runCount,
                            Integer columnCount) {
    }

    /**
     * 预算块（§8.6）。
     *
     * <p>{@code upstream} / {@code downstream} 是**真实总数**（root 的直接邻居数），
     * 不是本次返回的条数，也不随 {@code maxNodes} 变化——前端靠它画
     * 「还有 812 张表没画」。没有它，折叠节点上的 {@code +42} 就是编的。
     */
    public record Budget(long upstream, long downstream, int returned, boolean truncated) {
    }

    /** {@code GET /lineage/tables} 的默认形状（为表格设计：节点与边都是对象）。 */
    public record TableGraph(String root, List<TableNode> nodes, List<TableEdge> edges,
                             Budget budget, boolean truncated) {
    }

    /**
     * {@code compact=1} 的形状（§8.6）。
     *
     * <p>{@code n} 的五个位置：{@code [ref, format, kind, more, stale]}；
     * {@code e} 的八个位置：{@code [src, dst, kind, writeMode, engine, lastSeen, runCount, columnCount]}。
     * 枚举压成单字母，省掉的是重复字符串与重复键名——gzip 之后省不了多少，
     * 真实收益在 {@code JSON.parse}（数组比 900 个带键对象快 20–40ms）。
     *
     * <p>{@code v} 是形状版本号：将来加字段靠它分辨，而不是靠「有没有某个键」。
     */
    public record CompactGraph(int v, int root, List<CompactNode> n, List<CompactEdge> e, Budget budget) {
    }

    /**
     * {@code [ref, format, kind, more, stale]}。
     *
     * <p>{@code stale} 只有 0/1 两种取值，跟着 {@code more} 一起压成整数：它是「目录里
     * 已经找不到这张表、但边还在」的标记，图上要标灰。不放进来的话，{@code compact=1}
     * 的响应里「已删除」就彻底丢了——而控制台的图恰恰只走紧凑形状。
     */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record CompactNode(String ref, String format, String kind, int more, int stale) {
    }

    /** {@code [src, dst, kind, writeMode, engine, lastSeen, runCount, columnCount]}。 */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record CompactEdge(int source, int target, String kind, String writeMode, String engine,
                              long lastSeen, long runCount, int columnCount) {
    }

    // ------------------------------------------------------------------ 列级

    /** 一处列级上游（或下游，取决于查询方向）。 */
    public record ColumnLink(String table, String column, String kind, String expression) {
    }

    /**
     * 按目标列分组的一组列映射。
     *
     * <p>两个方向各一组字段而不是一个 {@code links}：{@code upstream} 回答
     * 「我这个字段从哪来」，{@code downstream} 回答「我改了会影响谁」，
     * 两者的 {@code kind} 语义不同（前者是目标列的 kind，后者是下游列的 kind）。
     * 混成一个数组时，前端要靠方向参数决定标签，那是把服务端的语义搬到前端再实现一遍。
     */
    public record ColumnGroup(String column, String kind, String expression,
                              List<ColumnLink> upstream, List<ColumnLink> downstream) {
    }

    /** {@code GET /lineage/columns}。 */
    public record ColumnGraph(String ref, String direction, List<ColumnGroup> columns) {
    }

    /**
     * 图视图用的批量列边（§8.4）：控制台把**当前展示的节点集合**原样发回来，
     * 这里只回「两端的表都在集合里」的列边——跨出集合的那半截边在图上没有落点。
     *
     * <p>形状沿用紧凑约定：{@code refs} 是去重后的表全名（下标即节点号），
     * {@code edges} 每行是 {@code [源下标, 源列, 目标下标, 目标列, kind]}。
     * <b>常量列没有源</b>：{@code s = -1}、{@code sc = ""}，它们不产生画出来的线，
     * 但让目标卡片的列清单里有一行「被写过」的列。
     */
    public record ColumnEdgeGraph(List<String> refs, List<ColumnEdgeRow> edges) {
    }

    /** {@code [源下标, 源列, 目标下标, 目标列, kind]}；{@code s = -1} 表示常量列。 */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record ColumnEdgeRow(int s, String sc, int t, String tc, String kind) {
    }

    // ------------------------------------------------------------------ 运行历史

    /**
     * 列表里的一行（不含 {@code sqlText} 与列级明细）。
     *
     * <p>列表页拖着 8KB 的 SQL 是这类接口最常见的浪费：一屏 50 行就是 400KB，
     * 而人只看时间、主体、语句类型这三列（§8.3）。
     */
    public record RunSummary(String runId, String engine, String engineVersion,
                             String statementType, String writeMode, String mode,
                             String principal, long startedAt, long finishedAt,
                             List<String> targets, boolean truncatedColumns) {
    }

    /** {@code GET /lineage/runs}：游标是 {@code (finishedAt, id)} 的复合值。 */
    public record RunList(List<RunSummary> runs, String nextCursor) {
    }

    /** {@code GET /lineage/runs/{runId}}：一条 run 的明细（含 SQL 与目标）。 */
    public record RunDetail(RunSummary summary, String sqlText, String sqlHash,
                            List<TargetReport> targets, Map<String, Object> attributes) {
    }
}
