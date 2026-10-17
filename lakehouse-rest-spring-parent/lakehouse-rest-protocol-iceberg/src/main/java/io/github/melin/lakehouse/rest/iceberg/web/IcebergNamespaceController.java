package io.github.melin.lakehouse.rest.iceberg.web;

import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.iceberg.service.IcebergNamespaceService;
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
 * Iceberg 的 namespace 端点（规格 §5.1「直接映射 namespace ↔ database」那一类）。
 *
 * <p><b>{@code {namespace}} 一律经过 {@link NamespacePaths#parse} 转成层级。</b>
 * 路径里那一段是**多级拼接**的（默认分隔符 {@code 0x1F}、URL 编码 {@code %1F}，
 * 解析时同时接受点号），服务层的入参是层级列表。容器与 Spring 已经把那一段
 * URL 解码过（{@code @PathVariable} 拿到的是解码后的值），因此这里不再解码——
 * 再解一遍会把层级里真实的 {@code %25} 变成 {@code %}。
 *
 * <p><b>HEAD 与 GET 是两条独立的映射，都写上。</b>Spring 对 {@code @GetMapping}
 * 有隐式的 HEAD 支持（{@code RequestMethodsRequestCondition#matchRequestMethod} 在
 * 请求方法是 HEAD、条件里只有 GET 时会回落到 GET 条件），因此只写 GET 也能让 HEAD 有响应；
 * 但那样拿到的是 **200 + 空体**，而规格把这两个「存在性检查」端点的成功写成
 * <b>204</b>。两条都显式登记不会歧义：{@code compareTo} 里有一条针对这个组合的排序规则
 * （HEAD-only 优于 GET-only，字节码可见），一次 HEAD 请求命中的是 HEAD 那一条。
 * 这个区别不是洁癖：Iceberg 的客户端在 {@code namespaceExists} / {@code tableExists}
 * 上只看「2xx 还是 404」，而据此写测试与对规格的人会看到不一致的状态码。
 *
 * <p><b>删除回 204、非空回 409。</b>与 Paimon 面 {@code DELETE /databases/{db}} 的
 * 级联删除**刻意不同**（理由见 {@link IcebergNamespaceService}）。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class IcebergNamespaceController {

    private final IcebergNamespaceService namespaceService;

    /**
     * {@code GET /v1/{prefix}/namespaces}（可选 {@code ?parent=}）。
     *
     * <p>{@code parent} 收成 {@code String} 而不是 {@code List<String>}：规格里它
     * **一个**参数，层级之间用 namespace separator 拼在这一个值里
     * （{@code ?parent=accounting%1Ftax}）。收成列表会让 Spring 的集合转换器
     * 按逗号切一次，而层级名里含逗号时就会切错。
     */
    @GetMapping("/namespaces")
    public IcebergDtos.ListNamespacesResponse listNamespaces(@PathVariable String prefix,
                                                             @RequestParam(required = false) String parent,
                                                             @RequestParam(required = false) Integer pageSize,
                                                             @RequestParam(required = false) String pageToken) {
        return namespaceService.list(prefix, NamespacePaths.parse(parent), pageSize, pageToken);
    }

    @PostMapping("/namespaces")
    public IcebergDtos.CreateNamespaceResponse createNamespace(
            @PathVariable String prefix,
            @RequestBody(required = false) IcebergDtos.CreateNamespaceRequest request) {
        return namespaceService.create(prefix, request);
    }

    @GetMapping("/namespaces/{namespace}")
    public IcebergDtos.GetNamespaceResponse loadNamespace(@PathVariable String prefix,
                                                          @PathVariable String namespace) {
        return namespaceService.load(prefix, levels(namespace));
    }

    @RequestMapping(value = "/namespaces/{namespace}", method = RequestMethod.HEAD)
    public ResponseEntity<Void> namespaceExists(@PathVariable String prefix,
                                                @PathVariable String namespace) {
        namespaceService.requireExists(prefix, levels(namespace));
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/namespaces/{namespace}")
    public ResponseEntity<Void> dropNamespace(@PathVariable String prefix,
                                              @PathVariable String namespace) {
        namespaceService.drop(prefix, levels(namespace));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/namespaces/{namespace}/properties")
    public IcebergDtos.UpdateNamespacePropertiesResponse updateNamespaceProperties(
            @PathVariable String prefix,
            @PathVariable String namespace,
            @RequestBody(required = false) IcebergDtos.UpdateNamespacePropertiesRequest request) {
        return namespaceService.updateProperties(prefix, levels(namespace), request);
    }

    private static List<String> levels(String namespace) {
        return NamespacePaths.parse(namespace);
    }
}
