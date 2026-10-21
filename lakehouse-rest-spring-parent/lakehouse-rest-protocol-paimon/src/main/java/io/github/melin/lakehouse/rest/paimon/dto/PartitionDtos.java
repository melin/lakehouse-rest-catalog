package io.github.melin.lakehouse.rest.paimon.dto;

import java.util.List;
import java.util.Map;

/**
 * 分区相关 DTO。
 *
 * <p>{@code spec} 是自由 object（分区字段 → 字面量），按 {@code Map<String,Object>} 承载；
 * 请求侧的 {@code partitionSpecs} / {@code partitionOptions} 值均为字符串。
 */
public final class PartitionDtos {

    private PartitionDtos() {
    }

    public record Partition(
            Map<String, Object> spec,
            Long recordCount,
            Long fileSizeInBytes,
            Long fileCount,
            Long lastFileCreationTime,
            Integer totalBuckets,
            Boolean done,
            Long createdAt,
            String createdBy,
            Long updatedAt,
            String updatedBy,
            Map<String, String> options) {
    }

    public record PartitionStatistics(
            Map<String, Object> spec,
            Long recordCount,
            Long fileSizeInBytes,
            Long fileCount,
            Long lastFileCreationTime,
            Integer totalBuckets) {
    }

    /**
     * 分区列表。
     *
     * <p>两种翻法互斥，与其它列表端点同一口径（见 {@code Paging.paged}）：
     * 不给 {@code page}/{@code pageSize} 是规格里的游标模式（{@code nextPageToken}
     * 由引擎客户端消费），给了就是控制台表格的页码模式——此时
     * {@code nextPageToken} 为 {@code null}，{@code total} 是过滤后的完整条数。
     */
    public record ListPartitionsResponse(List<Partition> partitions, String nextPageToken, Integer total) {
    }

    public record CreatePartitionsRequest(
            List<Map<String, String>> partitionSpecs,
            Boolean ignoreIfExists,
            List<PartitionStatistics> partitionStatistics,
            Boolean replaceStatistics,
            List<Map<String, String>> partitionOptions) {
    }

    public record CreatePartitionsResponse(
            List<Map<String, String>> created,
            List<Map<String, String>> existed) {
    }

    public record DropPartitionsRequest(List<Map<String, String>> partitionSpecs, Boolean ignoreIfNotExists) {
    }

    public record DropPartitionsResponse(
            List<Map<String, String>> dropped,
            List<Map<String, String>> missing) {
    }

    public record MarkDonePartitionsRequest(List<Map<String, Object>> specs) {
    }

    public record ListPartitionsByNamesRequest(List<Map<String, String>> specs) {
    }

    /** filter 为分区谓词的 JSON 序列化文本。 */
    public record ListPartitionsByFilterRequest(
            String filter,
            String partitionNamePattern,
            Integer maxResults,
            String pageToken) {
    }
}
