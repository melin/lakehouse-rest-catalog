package io.github.melin.lakehouse.rest.dto;

import io.github.melin.lakehouse.rest.preview.ColumnFilter;
import io.github.melin.lakehouse.rest.preview.PreviewColumn;
import io.github.melin.lakehouse.rest.support.PageMeta;
import java.util.List;
import java.util.Map;

/**
 * 数据预览的 wire 契约（{@code /api/console/v1/tables/{tableId}/preview} 与
 * {@code .../partitions}）。
 *
 * <p><b>为什么单独一个文件而不是塞进 {@code ConsoleDtos}。</b>后者是控制台里那些
 * "读元数据"的接口，字段少、形状稳定；预览是一组会随实现长大的契约（分区回显、
 * 采样统计、游标），单独一份更容易看出"这次改动碰的是哪一层"。
 *
 * <p><b>{@code partition} 与 {@code filters} 是两个通道</b>（设计文档 §7.5）：
 * {@code day(ts)} 分区在 schema 里没有对应列，{@code ts = 值} 与 {@code ts_day = 值}
 * 不是同一个谓词，塞进 filters 就得让前端懂 transform。
 */
public final class PreviewDtos {

    private PreviewDtos() {
    }

    /**
     * 预览请求。
     *
     * @param filters    行级条件，AND 连接；缺省为空
     * @param partition  分区范围；缺省等于 {@code mode=auto}（自动锁最新分区）
     * @param columns    投影列名；缺省为全部列（宽表默认只取前 N 列，见服务端）
     * @param limit      本页行数；缺省 100、上限 200
     * @param cursor     上一页返回的游标；给了它就忽略 filters/columns/partition（都绑在游标里了）
     * @param snapshotId 快照 id；缺省为最新快照（时间旅行预览用）
     */
    public record PreviewRequest(
            List<ColumnFilter> filters,
            PartitionSelectionRequest partition,
            List<String> columns,
            Integer limit,
            String cursor,
            Long snapshotId) {
    }

    /**
     * 分区选择。
     *
     * @param mode     {@code auto}（默认）/ {@code explicit} / {@code all}
     * @param values   仅 {@code explicit} 用；给定时按它们锁定分区
     * @param cascade  仅 {@code auto} 用：是否允许按文件数级联下钻，默认 true
     */
    public record PartitionSelectionRequest(String mode, List<PartitionValue> values, Boolean cascade) {
    }

    /**
     * 一个分区字段的取值。
     *
     * @param field     分区字段名
     * @param transform 分区变换；Paimon 恒为 {@code identity}，Iceberg 可能是
     *                  {@code day}/{@code hour}/{@code bucket} 等
     * @param value     转换后的分区值（{@code day(ts)} 传 {@code 2026-10-21}，不是时间戳）
     */
    public record PartitionValue(String field, String transform, String value) {
    }

    /**
     * 预览响应。
     *
     * @param snapshotId 本次读的快照
     * @param partition  分区回显——前端**不许**自己算"最新分区"，两处实现迟早不一致
     * @param columns    列定义（含类型与粗分类）
     * @param rows       行；每行是按 columns 顺序排好的值数组
     * @param cursor     下一页游标；{@code null} 表示读完了
     * @param hasMore    是否还有下一页
     * @param scanned    本次的真实代价
     * @param sampled    是否只读了作用域内的一部分文件
     * @param warnings   人话提示（采样、回退、超时降级都在这里）
     */
    public record PreviewResponse(
            Long snapshotId,
            PartitionSelection partition,
            List<PreviewColumn> columns,
            List<List<Object>> rows,
            String cursor,
            boolean hasMore,
            Scanned scanned,
            boolean sampled,
            List<String> warnings) {
    }

    /**
     * 分区的最终形态——既接受请求（{@code mode=auto}），也回显结果。
     *
     * @param mode              请求的模式
     * @param resolved          实际生效的分区条件；{@code explicit} 下原样回显
     * @param label             一句人话，前端直接显示在 chip 上
     * @param auto              是否服务端自动加的（决定 chip 是否带「自动」角标）
     * @param cascadeDepth      锁了几级
     * @param fallback          非 null 时说明为什么没锁成"最新"
     * @param partitionsTotal   该快照的分区总数
     * @param partitionsMatched 命中的分区数
     */
    public record PartitionSelection(
            String mode,
            List<PartitionValue> resolved,
            String label,
            boolean auto,
            int cascadeDepth,
            Fallback fallback,
            int partitionsTotal,
            int partitionsMatched) {
    }

    /**
     * 回退原因。
     *
     * @param reason 机器可读的原因：{@code NO_FILES}（最新分区没有数据文件）/
     *               {@code EMPTY_RESULT}（有文件但读不到行）/ {@code NOT_ORDERABLE}
     *               （bucket/truncate 分区，之间没有先后）/ {@code DEFAULT_PARTITION}
     *               （分区值全是空，只有默认分区）
     * @param detail 人话说明，前端直接显示
     */
    public record Fallback(String reason, String detail) {
    }

    /**
     * 本次读取的真实代价。三个文件数必须分开：
     * {@code filesInTable} 是整张表、{@code filesInScope} 是分区裁剪后的候选集、
     * {@code filesRead} 是真正打开的数量——合成一个分母会把"分区已经缩过范围"这个
     * 信息丢掉（{@code 3/12} 会被读成 {@code 3/128}）。
     */
    public record Scanned(
            int filesRead,
            int filesInScope,
            int filesInTable,
            long bytesRead,
            long rowsRead,
            long elapsedMs) {
    }

    /**
     * 分区列表（{@code GET .../partitions}）。
     *
     * @param truncated 是否被 20000 条上限截断
     * @param page      全站统一的远程分页元信息
     */
    public record PartitionPage(
            String catalog,
            String database,
            String table,
            Long snapshotId,
            List<PartitionRow> partitions,
            boolean truncated,
            PageMeta page) {
    }

    /**
     * 分区列表的一行。
     *
     * @param fields        字段 → 值，多级分区就是多键
     * @param files         数据文件数
     * @param bytes         数据文件总字节
     * @param lastWriteTime 分区内最新写入时间（epoch 毫秒）；给不出时为 {@code null}
     * @param records       记录数；给不出时为 -1
     */
    public record PartitionRow(
            Map<String, Object> fields,
            long files,
            long bytes,
            Long lastWriteTime,
            long records) {
    }

    /**
     * 按名字定位到的那张表的内部 id（{@code GET .../tables/lookup}）。
     *
     * <p>存在的理由只有一个：预览的两个端点按**内部 id** 寻址，而 Iceberg 表详情页
     * 手里只有 {@code LoadTableResult}——那个响应是 Iceberg 规格的形状，里面没有本服务端
     * 的 id，也不该有（规格响应里塞私有字段会让客户端解析出意外的 key）。
     * 于是给它一条"名字 → id"的窄路，而不是把预览端点改成两套寻址方式。
     *
     * @param id 内部 id（{@code lakehouse_table.id}）
     */
    public record TableRef(String id) {
    }
}
