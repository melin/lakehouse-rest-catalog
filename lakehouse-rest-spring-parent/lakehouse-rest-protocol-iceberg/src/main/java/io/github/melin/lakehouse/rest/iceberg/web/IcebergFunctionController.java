package io.github.melin.lakehouse.rest.iceberg.web;

import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.iceberg.service.IcebergFunctionService;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Iceberg 形态的函数端点（只读两个，照规格逐条来）。
 *
 * <p>函数的**写路径在 Paimon 面**（{@code /v1/{prefix}/databases/{db}/functions…}）——
 * Iceberg REST 规格本来就只定义了这两个 GET；本服务端不做「半套写路径」。
 * 读取走 {@link IcebergFunctionService} 的只读投影：Paimon 面建的函数在这里
 * 以 {@code FunctionMetadata} 的形状出现，两边看到的是同一行数据。
 */
@RestController
@RequestMapping("/v1/{prefix}")
@RequiredArgsConstructor
public class IcebergFunctionController {

    private final IcebergFunctionService functionService;

    @GetMapping("/namespaces/{namespace}/functions")
    public IcebergDtos.ListFunctionsResponse listFunctions(@PathVariable String prefix,
                                                           @PathVariable String namespace,
                                                           @RequestParam(required = false) Integer pageSize,
                                                           @RequestParam(required = false) String pageToken) {
        return functionService.list(prefix, levels(namespace), pageSize, pageToken);
    }

    @GetMapping("/namespaces/{namespace}/functions/{function}")
    public IcebergDtos.LoadFunctionResult loadFunction(@PathVariable String prefix,
                                                       @PathVariable String namespace,
                                                       @PathVariable String function) {
        return functionService.load(prefix, levels(namespace), function);
    }

    private static List<String> levels(String namespace) {
        return NamespacePaths.parse(namespace);
    }
}
