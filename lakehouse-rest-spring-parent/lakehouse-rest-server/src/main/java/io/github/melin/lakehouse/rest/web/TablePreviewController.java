package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.dto.PreviewDtos;
import io.github.melin.lakehouse.rest.dto.PreviewDtos.PartitionPage;
import io.github.melin.lakehouse.rest.dto.PreviewDtos.PreviewRequest;
import io.github.melin.lakehouse.rest.dto.PreviewDtos.PreviewResponse;
import io.github.melin.lakehouse.rest.preview.PreviewTarget;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.TablePreviewService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据预览的两个端点——表详情页「数据预览」页签的唯一数据源。
 *
 * <p><b>为什么要判权，而其它控制台端点不判。</b>文件列表那几个端点只读**元数据与目录名**，
 * 拿到凭据进控制台的人本来就该看到；预览读的是**行数据**，"能看元数据"与"能看数据"
 * 在客户心里是两件事。因此这里要求 TABLE 级 {@code SELECT}——不是 {@code DESCRIBE}。
 *
 * <p><b>为什么是 POST 而不是 GET。</b>过滤器是结构化数组（{@code >=}、日期、中文值放进
 * query 要转义，长度不可控）；请求语义上是"读一批数据"，会写审计事件；游标是不透明长串。
 * GET 的缓存语义在这里全是负担，所以显式 {@code no-store}。
 *
 * <p>两条端点都**不做写操作**：不写文件、不提交快照、不做 DDL。控制台里唯一的数据面
 * 入口，只有读。
 */
@RestController
@RequiredArgsConstructor
public class TablePreviewController {

    private final TablePreviewService previewService;

    private final AuthorizationService authorizationService;

    /**
     * {@code POST /api/console/v1/tables/{tableId}/preview}：读一页数据。
     *
     * <p>{@code request} 可以为空（等同于全默认：最新快照、自动锁最新分区、前 30 列、100 行），
     * 这让"先用 curl 看一眼"不必构造请求体。
     */
    @PostMapping("/api/console/v1/tables/{tableId}/preview")
    public ResponseEntity<PreviewResponse> preview(@PathVariable String tableId,
                                                   @RequestBody(required = false) PreviewRequest request) {
        PreviewTarget target = previewService.resolve(tableId);
        requireSelect(target, "preview table data");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(previewService.preview(target, request));
    }

    /**
     * {@code GET /api/console/v1/tables/{tableId}/partitions}：分区选择器的数据源。
     *
     * <p>与预览同一道门（TABLE 级 {@code SELECT}）：分区值与文件数本身就是数据分布信息，
     * 只有 {@code DESCRIBE} 的人不该看到"哪些天写得最多"。
     *
     * <p>{@code keyword} 按分区值前缀过滤，{@code page}/{@code pageSize} 走全站远程分页口径；
     * 两者都不给时一次返回（上限 20000 条，超出时 {@code truncated=true}）。
     */
    @GetMapping("/api/console/v1/tables/{tableId}/partitions")
    public ResponseEntity<PartitionPage> partitions(
            @PathVariable String tableId,
            @RequestParam(name = "keyword", required = false) String keyword,
            @RequestParam(name = "snapshotId", required = false) Long snapshotId,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "pageSize", required = false) Integer pageSize) {
        PreviewTarget target = previewService.resolve(tableId);
        requireSelect(target, "list the partitions of a table");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(previewService.partitions(target, page, pageSize, keyword, snapshotId));
    }

    /**
     * {@code GET /api/console/v1/tables/lookup?prefix=&database=&table=}：按名字换内部 id。
     *
     * <p>存在的理由只有一条：预览按**内部 id** 寻址，而 Iceberg 表详情页是按名字
     * 加载的（{@code LoadTableResult} 是 Iceberg 规格的形状，里面没有本服务端的 id，
     * 也不该有）。所以给它一条窄路：拿到 id 之后，两次预览请求与 Paimon 表完全一样。
     *
     * <p>与预览同一道门（TABLE 级 {@code SELECT}）：这个端点只服务于预览，
     * 能换到 id 却读不到数据没有意义；反过来，让"只有 DESCRIBE"的人通过它确认
     * 某张表存在，等于把预览的门开在别处。
     */
    @GetMapping("/api/console/v1/tables/lookup")
    public ResponseEntity<PreviewDtos.TableRef> lookup(@RequestParam("prefix") String prefix,
                                                      @RequestParam("database") String database,
                                                      @RequestParam("table") String table) {
        PreviewTarget target = previewService.resolve(prefix, database, table);
        requireSelect(target, "locate a table for data preview");
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PreviewDtos.TableRef(target.table().getId()));
    }

    /**
     * TABLE 级 {@code SELECT}。授权关闭时该调用直接放行（与所有管理端点一致）。
     *
     * <p>namespace 传库名：多级 namespace 的路径在这里拼成两段是错的——本工程里
     * 表实体只挂一个 {@code databaseId}，多级库名在元数据里是一条带点号的记录，
     * 拆成多段要靠 {@code NamespacePaths}，而它只被点号路径的判定用到。
     */
    private void requireSelect(PreviewTarget target, String action) {
        authorizationService.require(target.catalog().getPrefix(), ResourceType.TABLE,
                List.of(target.database()), target.table().getName(), Access.SELECT, action);
    }
}
