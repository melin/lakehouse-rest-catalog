package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.service.CatalogProtocols;
import io.github.melin.lakehouse.rest.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/config}：目录发现入口。
 *
 * <p>这是两套 REST Catalog 规格**唯一完全同路径**的端点，两个客户端都在拿到认证凭据
 * 之后第一件事就是调它，用返回的 {@code prefix} 拼出后续所有资源路径。
 *
 * <p><b>返回 {@code Object} 是刻意的。</b>响应体的形状按 catalog 的格式不同：
 * Paimon 回 {@code {defaults, overrides}}，Iceberg 必须多回一份 {@code endpoints}
 * 能力清单（规格写明：不发这个字段，客户端会假定一整套默认端点存在，其中包含
 * 本服务端不做的多表事务）。格式是 catalog 的运行时属性，因此这里只能交出一个
 * 「某种 JSON」；具体形状由协议面决定，见
 * {@link io.github.melin.lakehouse.rest.service.CatalogProtocol#configResponse}。
 *
 * <p>序列化由 Spring MVC 的消息转换器完成，实际用到的是返回值的运行时类型。
 *
 * <p>**本类是两个下层的汇合点**：解析 prefix 需要业务侧（{@code CatalogService}，
 * 它还要读 {@code default-prefix} 这类部署配置），组装响应需要装配侧
 * （{@code CatalogProtocols}）。让任何一方去拥有另一方都会把依赖方向拧回来
 * （详见 {@code CatalogProtocols} 的注释），而控制器本来就是画这条线的位置——
 * 与 {@link CatalogRenameController} 同一个做法。
 */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ConfigController {

    private final CatalogService catalogService;
    private final CatalogProtocols protocols;

    @GetMapping("/config")
    public Object config(@RequestParam(required = false) String warehouse) {
        CatalogEntity catalog = catalogService.resolveForConfig(warehouse);
        return protocols.configResponse(catalogService.formatOf(catalog), catalog);
    }
}
