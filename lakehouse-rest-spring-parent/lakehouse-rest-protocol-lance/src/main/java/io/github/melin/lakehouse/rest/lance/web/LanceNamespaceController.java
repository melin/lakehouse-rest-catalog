package io.github.melin.lakehouse.rest.lance.web;

import io.github.melin.lakehouse.rest.lance.dto.LanceDtos;
import io.github.melin.lakehouse.rest.lance.service.LanceNamespaceService;
import io.github.melin.lakehouse.rest.lance.service.LanceTableService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lance 的 namespace 端点（5 个 operation）与挂在命名空间下的表列举。
 *
 * <p><b>{@code {id}} 是拼接串，默认分隔符 {@code $}（URL 里 {@code %24}）。</b>
 * 它不是一个层级：{@code cat$schema} 在这里是**一段**路径。容器与 Spring 已经把它
 * 解码过，因此这里不再解码——再解一遍会把层级里真实的 {@code %} 变成别的字符。
 *
 * <p><b>{@code delimiter} 是 query 参数而不是路径的一部分</b>，且每个可能带 id 的
 * 操作都要收它：客户端可以换分隔符（{@code ::} / {@code __delim__}），
 * 服务端不能假设一定是 {@code $}。
 *
 * <p><b>写操作用 POST 而不是 PUT/DELETE。</b>Lance REST spec 的路由如此
 * （{@code create} / {@code drop} 都在路径尾段，方法一律 POST），
 * 理由写在规范里：让反代不用反序列化 body 就能知道这个请求要做什么。
 */
@RestController
@RequestMapping("/lance/v1/namespace")
@RequiredArgsConstructor
public class LanceNamespaceController {

    private final LanceNamespaceService namespaceService;
    private final LanceTableService tableService;

    @PostMapping("/{id}/create")
    public LanceDtos.CreateNamespaceResponse create(
            @PathVariable String id,
            @RequestParam(required = false) String delimiter,
            @RequestParam(required = false) String mode,
            @RequestBody(required = false) LanceDtos.CreateNamespaceRequest request) {
        return namespaceService.create(id, delimiter, mode, request);
    }

    @GetMapping("/{id}/list")
    public LanceDtos.ListNamespacesResponse list(@PathVariable String id,
                                                 @RequestParam(required = false) String delimiter,
                                                 @RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) String page_token) {
        return namespaceService.list(id, delimiter, limit, page_token);
    }

    @PostMapping("/{id}/describe")
    public LanceDtos.DescribeNamespaceResponse describe(@PathVariable String id,
                                                        @RequestParam(required = false) String delimiter) {
        return namespaceService.describe(id, delimiter);
    }

    /**
     * {@code POST .../exists}：存在返回 200 空体、不存在 404。
     *
     * <p>与 {@code describe} 只用同一个服务方法，是为了让「存在性」与「描述」
     * 不可能对同一份数据给出两个答案——它们常被用来互相验证。
     */
    @PostMapping("/{id}/exists")
    public ResponseEntity<Void> exists(@PathVariable String id,
                                        @RequestParam(required = false) String delimiter) {
        namespaceService.requireExists(id, delimiter);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/drop")
    public LanceDtos.EmptyResponse drop(@PathVariable String id,
                                         @RequestParam(required = false) String delimiter,
                                         @RequestParam(required = false) String mode) {
        namespaceService.drop(id, delimiter, mode);
        return new LanceDtos.EmptyResponse();
    }

    /** {@code GET .../namespace/{id}/table/list}：列该 schema 下的表。 */
    @GetMapping("/{id}/table/list")
    public LanceDtos.ListTablesResponse listTables(@PathVariable String id,
                                                   @RequestParam(required = false) String delimiter,
                                                   @RequestParam(required = false) Integer limit,
                                                   @RequestParam(required = false) String page_token) {
        return tableService.list(id, delimiter, limit, page_token);
    }
}
