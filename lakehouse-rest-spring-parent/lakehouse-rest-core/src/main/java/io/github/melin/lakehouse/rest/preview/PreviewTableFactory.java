package io.github.melin.lakehouse.rest.preview;

import io.github.melin.lakehouse.rest.support.CatalogFormat;

/**
 * 按 catalog 的表格式拿到一个 {@link PreviewTable}。
 *
 * <p>服务端按 {@code CatalogFormat} 在这个接口上做一次查表，就把"预览"这件事与
 * "哪个格式"解耦了：加一个格式等于加一个实现类，编排层一行不改（与
 * {@code CatalogFormatResolver} 决定"这个 catalog 走哪套协议面"是同一个手法）。
 *
 * <p>实现由各自的协议模块提供（Spring bean），装配层只按 {@link #format()} 建索引——
 * 因此协议模块之间仍然互不依赖，而 server 是唯一同时看得见两边的地方。
 */
public interface PreviewTableFactory {

    /** 本实现的表格式。 */
    CatalogFormat format();

    /**
     * 打开一张表。
     *
     * <p>失败要抛 {@code ApiException}（400/404 这类用户可纠正的错误）或运行时异常
     * （基础设施问题），不要返回 {@code null}——调用方没有"没有表"这个分支要处理。
     *
     * @param target     表、catalog 与库名
     * @param snapshotId 要读的快照；{@code null} 表示最新快照（时间旅行预览用得到它）
     */
    PreviewTable open(PreviewTarget target, Long snapshotId);
}
