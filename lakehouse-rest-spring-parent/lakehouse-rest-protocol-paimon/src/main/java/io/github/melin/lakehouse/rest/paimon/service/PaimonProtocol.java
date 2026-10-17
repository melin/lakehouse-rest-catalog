package io.github.melin.lakehouse.rest.paimon.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.service.CatalogProtocol;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Paimon 协议面在**装配层**的钩子。
 *
 * <p>它与 Iceberg 面的 {@code IcebergProtocol} 是一对：core 通过 Spring 收集
 * {@code List<CatalogProtocol>} 拿到两个面的实现，因此 core 不需要 import 任何一个面，
 * 「两个协议面互不依赖」这条模块不变量（各自 POM 里的 enforcer 守着）得以保持。
 *
 * <p>本类只做汇编写得出来的事——把装配层的三次调用转给本面已有的服务，
 * 不在这里做任何判定：格式判定的权威在 core（{@code CatalogFormatResolver} +
 * catalog 上的那一列），本面只回答「Paimon 这个格式该怎么表现」。
 */
@Component
@RequiredArgsConstructor
public class PaimonProtocol implements CatalogProtocol {

    private final TableService tableService;
    private final ViewService viewService;

    @Override
    public CatalogFormat format() {
        return CatalogFormat.PAIMON;
    }

    /**
     * {@code GET /v1/config}（Paimon 形状）：{@code {defaults, overrides}}，就两段。
     *
     * <p>**没有 {@code endpoints}**，而且这一点是刻意的：Paimon 规格里根本没有这个字段，
     * 加上它只会让客户端拿到一个它不认识的键。两段内容原样转发——catalog 自己的
     * {@code defaults}（{@code table-default.*} 之类）已由
     * {@code CatalogService#config} 合并进来。
     */
    @Override
    public Object configResponse(CatalogEntity catalog, Map<String, String> defaults,
                                 Map<String, String> overrides) {
        Map<String, String> responseDefaults = defaults == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(defaults);
        Map<String, String> responseOverrides = overrides == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(overrides);
        return new CommonDtos.ConfigResponse(responseDefaults, responseOverrides);
    }

    /**
     * 两条同路径的重命名端点（见 {@code CatalogProtocol#rename} 的表格）。
     *
     * <p>请求体在这里才绑成 {@link TableDtos.RenameTableRequest}——core 不认识这个类型，
     * 它只把 JSON 树递过来。绑定失败一律翻成 400：这一层的失败原因只有一种
     * （报文不是本面的形状），而 500 会让调用方以为是服务端的锅。
     *
     * <p>仍然把 {@code catalog.getPrefix()} 传给服务、而不是把整个 catalog 递进去：
     * 那三个 rename 服务的第一件事就是按 prefix 解析 catalog，
     * 多查一次的代价是一次带索引的查询，换来的是「每个服务方法的入参形状一致」
     * （都从 prefix 开始），以及不必为这一条路径改三个方法的签名。
     */
    @Override
    public void rename(CatalogEntity catalog, ResourceType resourceType, JsonNode body) {
        TableDtos.RenameTableRequest request = bind(body);
        if (resourceType == ResourceType.VIEW) {
            viewService.rename(catalog.getPrefix(), request);
        } else {
            tableService.rename(catalog.getPrefix(), request);
        }
    }

    private static TableDtos.RenameTableRequest bind(JsonNode body) {
        if (body == null || body.isNull()) {
            throw ApiException.badRequest("rename requires a request body");
        }
        try {
            return Json.read(Json.write(body), TableDtos.RenameTableRequest.class);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            throw ApiException.badRequest("Cannot parse the rename request: "
                    + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message));
        }
    }
}
