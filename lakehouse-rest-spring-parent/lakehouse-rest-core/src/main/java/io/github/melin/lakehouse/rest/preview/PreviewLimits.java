package io.github.melin.lakehouse.rest.preview;

/**
 * 预览的限额。
 *
 * <p>写在这里而不是 {@code RestServerProperties} 里，是因为它们不是"部署差异"而是
 * "交互式读的物理上限"：{@code maxFiles} 之所以是 8，是因为一次预览的主要成本是
 * 对象存储的 RTT × 文件数，而人盯着页面的耐心只有几秒；这个数字换一台机器也不变。
 * 真要按部署调，再把它挪进配置——先有量再谈旋钮。
 *
 * @param defaultLimit    单页默认行数
 * @param maxLimit        单页行数上限（{@code skip} 的代价与它同阶）
 * @param maxFiles        单次请求默认打开的数据文件数（也是自动分区收敛的目标）
 * @param maxFilesUpper   {@code maxFiles} 的绝对上限
 * @param maxBytes        单次请求默认最多读多少字节
 * @param timeoutMs       默认超时
 * @param timeoutUpper    超时上限
 * @param maxFilters      过滤条件条数上限
 * @param maxColumns      宽表默认投影列数
 * @param maxCascadeDepth 自动分区的最大级联层数
 */
public record PreviewLimits(
        int defaultLimit,
        int maxLimit,
        int maxFiles,
        int maxFilesUpper,
        long maxBytes,
        long timeoutMs,
        long timeoutUpper,
        int maxFilters,
        int maxColumns,
        int maxCascadeDepth) {

    /** 本部署的取值。改这里就是改默认行为，文档 §9 的表格要与它同步。 */
    public static final PreviewLimits DEFAULTS = new PreviewLimits(
            100,
            200,
            8,
            32,
            64L * 1024 * 1024,
            5_000,
            15_000,
            5,
            30,
            3);
}
