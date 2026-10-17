package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.iceberg.support.IcebergEndpoints;
import io.github.melin.lakehouse.rest.service.CatalogProtocol;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CatalogFormat;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Iceberg 协议面在**装配层**的钩子。
 *
 * <p>本类的全部内容是「把 Iceberg 侧与 Paimon 侧不同的那几处装配行为说出来」：
 * {@code GET /v1/config} 的响应体多一份 {@code endpoints} 能力清单，
 * 两条同路径的 {@code rename} 用 Iceberg 的请求体形状去绑。
 * 其余能力（namespace / table / view 的读写）由三个控制器树承担，
 * 装配层从不需要认识它们——这也是 {@link CatalogProtocol} 只有三个方法的原因。
 *
 * <p><b>为什么实现者是一个 {@code @Component} 而不是某个工厂里的注册调用。</b>
 * core 通过 Spring 收集 {@code List<CatalogProtocol>} 拿到两个面的实现，
 * 于是 core 不需要 import 任何一个协议面，「两个协议面互不依赖」这条模块不变量
 * （各自 POM 里的 enforcer {@code bannedDependencies} 守着）得以保持。
 * 换句话说：**这个类存在的方式本身就是那条不变量的实现**——把它换成
 * 「core 里 new 一个」，core 就不得不依赖本模块。
 *
 * <p><b>{@code endpoints} 用 {@link IcebergEndpoints#SUPPORTED} 而不是就地写一个列表。</b>
 * 那份清单要同时满足两件事：与本服务端真正实现的端点一一对应（多了就是承诺不存在的能力，
 * 少了会让客户端在连接阶段就拒绝本服务端），以及字符串与 Iceberg 客户端的
 * {@code Endpoint} 逐字一致（客户端拿它做等值比较，多一个空格就静默失效）。
 * 因此它被单独放在一处，并由 {@code scripts/verify-iceberg-endpoints.py} 与
 * 实际的映射做双向比对。
 *
 * <p>{@code defaults} / {@code overrides} 原样转发：catalog 自己的
 * {@code defaults} 与 {@code overrides} 已由 {@code CatalogProtocols#configResponse}
 * 合并进来（云的 endpoint、{@code io-impl} 这类连接参数就配在那里）。协议面**不在这里
 * 再加字段**——加一个新的默认值等于替所有部署做决定，而那件事的落点应该是
 * catalog 的属性，管理员在控制台上看得见、改得动。
 */
@Component
@RequiredArgsConstructor
public class IcebergProtocol implements CatalogProtocol {

    private final IcebergTableService tableService;
    private final IcebergViewService viewService;

    @Override
    public CatalogFormat format() {
        return CatalogFormat.ICEBERG;
    }

    /**
     * {@code GET /v1/config}（Iceberg 形状）：{@code {defaults, overrides, endpoints}}。
     *
     * <p>三份内容都是**拷贝**出去：{@code defaults} 的顺序有意义
     * （{@code warehouse} / {@code prefix} 先、catalog 自己的属性后，
     * 排障时一眼能看出哪几个是服务端钉的），而调用方传进来的那份可能还会被后续代码用到。
     */
    @Override
    public Object configResponse(CatalogEntity catalog, Map<String, String> defaults,
                                 Map<String, String> overrides) {
        Map<String, String> responseDefaults = defaults == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(defaults);
        Map<String, String> responseOverrides = overrides == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(overrides);
        List<String> endpoints = IcebergEndpoints.SUPPORTED;
        return new CommonDtos.IcebergConfigResponse(responseDefaults, responseOverrides, endpoints);
    }

    /**
     * 两条同路径的重命名端点（见 {@code CatalogProtocol#rename} 的表格）。
     *
     * <p>请求体在这里才绑成 {@link IcebergDtos.RenameTableRequest}——core 不认识这个类型，
     * 它只把 JSON 树递过来，因为 Iceberg 的标识符是 {@code {namespace: [...], name}}
     * 而 Paimon 的是 {@code {database, object}}。绑定失败一律翻成 400：
     * 这一层的失败原因只有一种（报文不是本面的形状），而 500 会让调用方以为是服务端的锅。
     */
    @Override
    public void rename(CatalogEntity catalog, ResourceType resourceType, JsonNode body) {
        IcebergDtos.RenameTableRequest request = bind(body);
        if (resourceType == ResourceType.VIEW) {
            viewService.rename(catalog, request);
        } else {
            tableService.rename(catalog, request);
        }
    }

    private static IcebergDtos.RenameTableRequest bind(JsonNode body) {
        if (body == null || body.isNull()) {
            throw ApiException.badRequest("rename requires a request body");
        }
        try {
            return Json.read(Json.write(body), IcebergDtos.RenameTableRequest.class);
        } catch (RuntimeException e) {
            String message = e.getMessage();
            throw ApiException.badRequest("Cannot parse the rename request: "
                    + (message == null || message.isBlank() ? e.getClass().getSimpleName() : message));
        }
    }
}
