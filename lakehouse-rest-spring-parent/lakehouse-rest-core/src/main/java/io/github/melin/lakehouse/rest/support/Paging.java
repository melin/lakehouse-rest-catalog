package io.github.melin.lakehouse.rest.support;

import java.util.List;
import java.util.UUID;

/**
 * 分页工具：把不透明的 {@code pageToken} 编解码为列表偏移量。
 *
 * <p>规格只要求 token 对客户端不透明、服务端可自解释；这里使用
 * {@code base64url("offset:N")}，实现简单且无状态。
 */
public final class Paging {

    /** 远程分页的默认与最大页大小。控制台按默认值开局，用户可以改到上限为止。 */
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 200;

    private static final String PREFIX = "offset:";

    private Paging() {
    }

    public static int pageSize(Integer requested, int defaultSize, int maxSize) {
        if (requested == null || requested <= 0) {
            return Math.min(defaultSize, maxSize);
        }
        return Math.min(requested, maxSize);
    }

    /** 1-based 页码归一：null / 非法值按第一页。 */
    public static int page(Integer requested) {
        return requested == null || requested < 1 ? 1 : requested;
    }

    /**
     * 远程分页的统一入口：整份结果 → 一页 + {@link PageMeta}。
     *
     * <p>{@code page} 与 {@code pageSize} 都没给时返回 {@code null}，调用方据此
     * 走「一次拿全量」的老路径（脚本、Spark 客户端、curl 都是这么用的）。
     * {@code page} 为空它们分别是默认值与上限。
     *
     * <p>切页而不是让调用方各写一遍，是为了让所有表格的「第几页、共几页、
     * 还有没有下一页」出自同一段代码——这几个数字一旦各算各的，
     * 就会出现「页码翻到头了表格还有数据」这种只在边角复现的问题。
     *
     * @param all 已过滤、已按稳定顺序排好的完整结果
     */
    public static <T> Paged<T> paged(List<T> all, Integer page, Integer pageSize) {
        if (page == null && pageSize == null) {
            return null;
        }
        int size = pageSize(pageSize, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        int current = page(page);
        Slice<T> slice = slicePage(all, current, size);
        return new Paged<>(slice.items(), PageMeta.of(all.size(), current, size));
    }

    /** 一页数据与它的分页元数据。 */
    public record Paged<T>(List<T> items, PageMeta meta) {
    }

    /**
     * 对已排序的完整结果集按「页码 × 页大小」切片。
     *
     * <p>与 {@link #slice(List, int, int)} 同一条路径，只是把 0-based 的
     * {@code offset} 换成更贴近表格交互的页码（{@code el-pagination} 的
     * {@code current-page} 是 1-based）。远程分页端点用这个。
     */
    public static <T> Slice<T> slicePage(List<T> all, int page, int size) {
        return slice(all, (page - 1) * size, size);
    }

    public static String encode(int offset) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString((PREFIX + offset).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 解析 token；null / 空串表示第一页。 */
    public static int offset(String token) {
        if (token == null || token.isBlank()) {
            return 0;
        }
        try {
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(token),
                    java.nio.charset.StandardCharsets.UTF_8);
            if (decoded.startsWith(PREFIX)) {
                return Math.max(0, Integer.parseInt(decoded.substring(PREFIX.length())));
            }
        } catch (RuntimeException ignored) {
            // 落到下面的统一报错
        }
        throw ApiException.badRequest("Invalid pageToken");
    }

    /** 对已排序的完整结果集做切片。 */
    public static <T> Slice<T> slice(List<T> all, int offset, int size) {
        if (offset >= all.size()) {
            return new Slice<>(List.of(), null);
        }
        int end = Math.min(all.size(), offset + size);
        List<T> items = List.copyOf(all.subList(offset, end));
        String next = end < all.size() ? encode(end) : null;
        return new Slice<>(items, next);
    }

    public record Slice<T>(List<T> items, String nextPageToken) {
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }
}
