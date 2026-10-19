package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Catalog;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Catalogs;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.CreateCatalogRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.UpdateCatalogRequest;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.ManagementCatalogService;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理面的 catalog 端点（服务端扩展）。
 *
 * <p>官方 Paimon 管理 API 不含 catalog 生命周期（它把 catalog 看作部署配置），
 * 这里保留路径与形状与 {@code spec/polaris-management-service.yml} 一致的一组端点，
 * 基址为 {@code /api/management/v1}。**权限不在这条链上**：
 * catalog 内「谁能读什么」由 {@code /v1/{prefix}/permissions} 的 access 决定，
 * 本控制器只判读（{@code CATALOG_ALL:DESCRIBE}）与改/删（{@code CATALOG:ALTER|DROP}）。
 */
@RestController
@RequestMapping("/api/management/v1/catalogs")
@RequiredArgsConstructor
public class ManagementCatalogController {

    private final ManagementCatalogService catalogService;
    private final AuthorizationService authorizationService;

    /**
     * {@code GET /catalogs}。
     *
     * <p>开启授权时按调用者的可见范围过滤：服务管理员看到全部 catalog，
     * 其余调用者只看到自己至少持有一条赋权的 catalog。
     * 这样未授权调用方不会通过 catalog 列表探测到服务端有哪些 catalog。
     */
    @GetMapping
    public Catalogs list() {
        Catalogs all = catalogService.list();
        if (!authorizationService.enabled() || authorizationService.isServiceAdmin(RequestContext.principal())) {
            return all;
        }
        List<Catalog> visible = new ArrayList<>();
        for (Catalog catalog : all.catalogs()) {
            if (authorizationService.hasAnyGrant(RequestContext.principal(), catalog.name())) {
                visible.add(catalog);
            }
        }
        return new Catalogs(visible);
    }

    /** {@code POST /catalogs}。创建 catalog 尚无权限可依，故要求服务管理员身份。 */
    @PostMapping
    public ResponseEntity<Catalog> create(@RequestBody CreateCatalogRequest request) {
        authorizationService.requireServiceAdmin("create a catalog");
        Catalog created = catalogService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /**
     * {@code GET /catalogs/{catalogName}}。
     *
     * <p>判 {@code CATALOG_ALL} 上的 {@code DESCRIBE}，而不是 {@code CATALOG} 上的：
     * 官方 Access/Resource 对照表里 {@code CATALOG} 只有 {@code ALL / ALTER / DROP / GRANT /
     * CREATEDATABASE}，**没有**任何读权限；{@code DESCRIBE} 只出现在
     * {@code CATALOG_ALL / DATABASE / TABLE / VIEW / FUNCTION} 上。
     * 而「能读这个 catalog 里的东西」正是本端点的语义，所以落在 {@code CATALOG_ALL}。
     */
    @GetMapping("/{catalogName}")
    public Catalog get(@PathVariable String catalogName) {
        authorizationService.require(catalogName, ResourceType.CATALOG_ALL, List.of(), null,
                Access.DESCRIBE, "read the catalog");
        return catalogService.get(catalogName);
    }

    /** {@code PUT /catalogs/{catalogName}}。 */
    @PutMapping("/{catalogName}")
    public Catalog update(@PathVariable String catalogName,
                          @RequestBody UpdateCatalogRequest request) {
        authorizationService.requireCatalog(catalogName, Access.ALTER, "update the catalog");
        return catalogService.update(catalogName, request);
    }

    /** {@code DELETE /catalogs/{catalogName}}。 */
    @DeleteMapping("/{catalogName}")
    public ResponseEntity<Void> delete(@PathVariable String catalogName) {
        authorizationService.requireCatalog(catalogName, Access.DROP, "delete the catalog");
        catalogService.delete(catalogName);
        return ResponseEntity.noContent().build();
    }
}
