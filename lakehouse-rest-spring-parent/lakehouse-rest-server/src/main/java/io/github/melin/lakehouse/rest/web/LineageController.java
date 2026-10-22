package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnEdgeGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.IngestResult;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ReportBatch;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunDetail;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunList;
import io.github.melin.lakehouse.rest.service.LineageService;
import io.github.melin.lakehouse.rest.service.LineageService.GraphPayload;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 血缘的管理端点（{@code /api/management/v1/lineage}，设计见
 * {@code docs/lineage-design.md} §6.1 / §8）。
 *
 * <p><b>为什么挂在管理 API 而不是 catalog API（{@code /v1/{prefix}/…}）。</b>三个理由：
 * 血缘是<b>跨 catalog</b> 的元数据（{@code {prefix}} 只是其中一个维度）；上报器已经在用
 * 管理 API 的地址与令牌（{@code spark.lakehouse.rest.management.url}）；而 catalog API 的每个
 * 端点都必须在 {@code CatalogAccessRules} 登记权限映射并在
 * {@code CatalogEndpointAuthorizationTests} 里覆盖——血缘是「关于表的关系」，
 * 不是「表上的操作」，不该挤进那张表。
 *
 * <p><b>读不判表级权限，写要判。</b>读走管理 API 的既有门禁（认证 + 服务管理员/授权），
 * 与事件页、{@code /meta} 一致：能看见目录的人能看见这张表的上游。写必须逐条 run 判
 * 「能写目标表 + 能读源表」（{@code LineageService#requirePermission}），
 * 因为上报的是「我改了这张表」这句话——不能让人替别人声明。
 *
 * <p><b>{@code If-None-Match} 与 304 由这里处理，不在服务层。</b>服务层给出 ETag
 * （{@code max(lastSeen) + 节点数 + 边数} 的摘要），要不要短路是 HTTP 语义，
 * 与「图怎么算」无关。
 */
@RestController
@RequestMapping("/api/management/v1/lineage")
@RequiredArgsConstructor
public class LineageController {

    private final LineageService lineageService;

    /**
     * {@code POST /lineage/runs}：批量上报，单次最多
     * {@code lakehouse.rest.lineage.max-runs-per-batch} 条。
     *
     * <p>响应给出「收了几条、幂等命中几条、因超上限丢弃了几列」。三件事都需要：
     * 客户端重试时要知道自己有没有重复上报；宽表降级为表级时要知道图为什么少了列。
     */
    @PostMapping("/runs")
    public IngestResult report(@RequestBody(required = false) ReportBatch batch) {
        return lineageService.ingest(batch);
    }

    /**
     * {@code GET /lineage/runs}：运行历史（摘要），按 {@code finishedAt} 倒序。
     *
     * <p>游标用 {@code (finishedAt, id)} 的复合值：同一毫秒里可能落两条 run，
     * 只按时间翻页会漏行。响应里的 {@code nextCursor} 原样带回即可。
     */
    @GetMapping("/runs")
    public RunList runs(@RequestParam(value = "ref", required = false) String ref,
                        @RequestParam(value = "engine", required = false) String engine,
                        @RequestParam(value = "since", required = false) Long since,
                        @RequestParam(value = "limit", required = false) Integer limit,
                        @RequestParam(value = "cursor", required = false) String cursor) {
        return lineageService.runs(ref, engine, since, limit, cursor);
    }

    /** {@code GET /lineage/runs/{runId}}：一条 run 的明细（含 SQL 与列映射）。 */
    @GetMapping("/runs/{runId}")
    public RunDetail run(@PathVariable String runId) {
        return lineageService.run(runId);
    }

    /**
     * {@code GET /lineage/tables}：表级上下游。
     *
     * @param ref         归一化全名（{@code catalog.namespace.table}），也接受 {@code ~} 前缀的外部节点
     * @param direction   {@code upstream} / {@code downstream} / {@code both}，默认 {@code downstream}
     * @param depth       1..{@code max-depth}（默认 5），超了 400 而不是静默截断
     * @param engine      按引擎过滤；空 = 全部
     * @param maxNodes    一次最多返回多少节点（默认 {@code max-nodes}，硬上限 2000）
     * @param compact     {@code 1} 时用「数组 + 整数下标」编码（§8.6）
     * @param columnCount {@code 1} 时边的 {@code columnCount} 由服务端聚合（图用它算线宽）
     * @param ifNoneMatch 标准条件请求；命中回 304
     */
    @GetMapping("/tables")
    public ResponseEntity<Object> tables(
            @RequestParam("ref") String ref,
            @RequestParam(value = "direction", required = false) String direction,
            @RequestParam(value = "depth", required = false) Integer depth,
            @RequestParam(value = "engine", required = false) String engine,
            @RequestParam(value = "maxNodes", required = false) Integer maxNodes,
            @RequestParam(value = "compact", required = false, defaultValue = "0") String compact,
            @RequestParam(value = "columnCount", required = false, defaultValue = "0") String columnCount,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        GraphPayload payload = lineageService.tables(ref, direction, depth, engine, maxNodes,
                isTrue(columnCount));
        // no-cache（而不是 no-store）：允许浏览器存一份、每次带 If-None-Match 来问。
        // 图的 ETag 只随边表变化，绝大多数请求会命中 304，省下一次完整序列化。
        CacheControl cache = CacheControl.noCache();
        if (payload.etag().equals(ifNoneMatch)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(payload.etag())
                    .cacheControl(cache)
                    .build();
        }
        Object body = isTrue(compact) ? payload.compact() : payload.full();
        return ResponseEntity.ok().eTag(payload.etag()).cacheControl(cache).body(body);
    }

    /**
     * {@code GET /lineage/columns}：按目标列分组的列映射。
     *
     * <p>与 {@code /tables} 分开是刻意的：图只需要表级结构（列级会让边数变成 O(表数 × 列数)），
     * 列映射只在用户切到「列」页签时才拉。两个端点共用同一套 {@code direction} 语义。
     */
    @GetMapping("/columns")
    public ColumnGraph columns(@RequestParam("ref") String ref,
                              @RequestParam(value = "direction", required = false) String direction,
                              @RequestParam(value = "engine", required = false) String engine) {
        return lineageService.columns(ref, direction, engine);
    }

    /**
     * {@code GET /lineage/column-edges}：图视图的批量列边（§8.4）。
     *
     * <p>与 {@code /columns} 分开是刻意的：列映射回答「这张表的列从哪来」（单表视角），
     * 这里回答「**展示中的这些表**之间，列与列怎么连」（图视角）。节点集合由调用方给
     * （逗号分隔、去重、最多 64 个）——图已经被过滤、展开与截断过，服务端按根重算
     * 得到的集合与它对不上。响应是紧凑的 {@code [源下标, 源列, 目标下标, 目标列, kind]} 行，
     * 常量列 {@code s = -1}（有行无线）。
     */
    @GetMapping("/column-edges")
    public ColumnEdgeGraph columnEdges(@RequestParam("refs") List<String> refs) {
        return lineageService.columnEdges(refs);
    }

    /** {@code 1} / {@code true} 都算真：控制台的开关会发字符串，curl 习惯写 true。 */
    private static boolean isTrue(String value) {
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
