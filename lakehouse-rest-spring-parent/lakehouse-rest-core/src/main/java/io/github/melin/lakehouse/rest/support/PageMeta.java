package io.github.melin.lakehouse.rest.support;

/**
 * 列表端点的统一分页元数据。
 *
 * <p>控制台的表格全部改为<b>远程分页</b>：浏览器只持有一页，翻页 / 过滤 / 排序
 * 都下推服务端。为了让 {@code el-pagination} 能渲染页码，服务端在响应里带回
 * {@code total}（过滤后的总行数）与当前 {@code page}/{@code pageSize}——客户端
 * 不再自己猜「还有多少页」。
 *
 * <p>这个 record 嵌在各列表响应的 {@code page} 字段里（例如
 * {@code Catalogs.page}）。不传分页参数的调用方看到的是 {@code page = null}，
 * 即「一次拿全量」的旧行为——参数是可选的，向后兼容。
 *
 * @param total    过滤后的总行数
 * @param page     当前页，1-based
 * @param pageSize 本页大小（服务端已按默认/上限夹过）
 * @param hasNext  是否还有下一页
 */
public record PageMeta(long total, int page, int pageSize, boolean hasNext) {

    public static PageMeta of(long total, int page, int pageSize) {
        return new PageMeta(total, page, pageSize, (long) page * pageSize < total);
    }
}
