package io.github.melin.lakehouse.rest.iceberg.web;

import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.iceberg.service.IcebergViewService;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Iceberg 形态的视图端点。
 *
 * <p>与 Paimon 面的 {@code /v1/{prefix}/databases/{db}/views…} 是**同一张表的两套契约**：
 * 两边都落在 {@code paimon_view} 上，但 Iceberg 侧的 {@code view_schema_doc} 存的是
 * 整份 {@code ViewMetadata}（见 {@code IcebergViewService}），因此两边各自解析、
 * 互不认对方的行——一个通过 Paimon 面建的视图在 Iceberg 面会 404，
 * 反之亦然，且在报错里说清原因。
 *
 * <p>端点与成功状态码（照规格逐条来）：
 *
 * <table border="1">
 *   <caption>视图端点</caption>
 *   <tr><th>端点</th><th>成功</th></tr>
 *   <tr><td>{@code GET/POST .../views}、{@code GET/POST .../views/{v}}</td><td>200</td></tr>
 *   <tr><td>{@code DELETE .../views/{v}}、{@code HEAD .../views/{v}}</td><td>204</td></tr>
 * </table>
 *
 * <p><b>一期不实现的 {@code POST .../register-view} 不登记</b>（因此不在
 * {@code IcebergEndpoints.SUPPORTED} 里）：它的语义是「把仓库里已有的
 * {@code ViewMetadata} 登记进来」，而视图的元数据文件位置是 {@code ViewMetadata}
 * 自己之外的另一个指针，登记它需要与 {@code register} 相同的一轮取舍
 * （external 标记、覆盖语义、purge 行为），那是一件独立的事。
 * 不做就不能声明——声明了等于承诺一个存在的能力，故障现场会离原因很远。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class IcebergViewController {

    private final IcebergViewService viewService;

    @GetMapping("/namespaces/{namespace}/views")
    public IcebergDtos.ListViewsResponse listViews(@PathVariable String prefix,
                                                  @PathVariable String namespace,
                                                  @RequestParam(required = false) Integer pageSize,
                                                  @RequestParam(required = false) String pageToken) {
        return viewService.list(prefix, levels(namespace), pageSize, pageToken);
    }

    @PostMapping("/namespaces/{namespace}/views")
    public IcebergDtos.LoadViewResponse createView(
            @PathVariable String prefix,
            @PathVariable String namespace,
            @RequestBody(required = false) IcebergDtos.CreateViewRequest request) {
        return viewService.create(prefix, levels(namespace), request);
    }

    @GetMapping("/namespaces/{namespace}/views/{view}")
    public IcebergDtos.LoadViewResponse loadView(@PathVariable String prefix,
                                                 @PathVariable String namespace,
                                                 @PathVariable String view) {
        return viewService.load(prefix, levels(namespace), view);
    }

    @RequestMapping(value = "/namespaces/{namespace}/views/{view}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> viewExists(@PathVariable String prefix,
                                           @PathVariable String namespace,
                                           @PathVariable String view) {
        viewService.requireExists(prefix, levels(namespace), view);
        return ResponseEntity.noContent().build();
    }

    /** {@code POST .../views/{view}}：一轮 update 作用在当前 {@code ViewMetadata} 上。 */
    @PostMapping("/namespaces/{namespace}/views/{view}")
    public IcebergDtos.LoadViewResponse replaceView(
            @PathVariable String prefix,
            @PathVariable String namespace,
            @PathVariable String view,
            @RequestBody(required = false) IcebergDtos.UpdateViewRequest request) {
        return viewService.commit(prefix, levels(namespace), view, request);
    }

    @DeleteMapping("/namespaces/{namespace}/views/{view}")
    public ResponseEntity<Void> dropView(@PathVariable String prefix,
                                         @PathVariable String namespace,
                                         @PathVariable String view) {
        viewService.drop(prefix, levels(namespace), view);
        return ResponseEntity.noContent().build();
    }

    private static List<String> levels(String namespace) {
        return NamespacePaths.parse(namespace);
    }
}
