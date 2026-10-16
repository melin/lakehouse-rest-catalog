package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionAssignment;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionList;
import io.github.melin.lakehouse.rest.dto.Privilege;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.PermissionManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Paimon 官方 REST Management API 的权限端点，共 3 个 operation。
 *
 * <p>路径在 catalog API（{@code /v1/{prefix}/...}）之下，官方把它们归入
 * management API；本工程统一按 catalog API 的鉴权与授权链路处理。
 * 官方要求「创建、删除、查看权限需要调用者对相关资源具备 GRANT」——
 * 这里收敛为 catalog 级的 {@code CATALOG_MANAGE_ACCESS}（与 Polaris 管理 API 的
 * catalog role 授权端点同一要求）：权限端点的调用方是管理员与控制台，
 * 逐资源校验 GRANT 需要读懂请求体，放在路径映射表里做不到，而收敛到
 * catalog 级不产生越权（要求只会更严）。
 */
@RestController
@RequestMapping("/v1/{prefix}/permissions")
@RequiredArgsConstructor
public class PermissionController {

    private final PermissionManagementService permissionManagementService;
    private final AuthorizationService authorizationService;

    /** {@code GET /v1/{prefix}/permissions}。 */
    @GetMapping
    public PermissionList list(@PathVariable String prefix,
                               @RequestParam(required = false) String resourceType,
                               @RequestParam(required = false) String database,
                               @RequestParam(required = false) String table,
                               @RequestParam(required = false) String view,
                               @RequestParam(required = false) String function,
                               @RequestParam(required = false) String principal,
                               @RequestParam(required = false) String access,
                               @RequestParam(required = false) Integer maxResults,
                               @RequestParam(required = false) String pageToken) {
        authorizationService.requireCatalog(prefix, Privilege.CATALOG_MANAGE_ACCESS,
                "list permissions");
        return permissionManagementService.list(prefix, resourceType, database, table, view,
                function, principal, access, maxResults, pageToken);
    }

    /** {@code POST /v1/{prefix}/permissions/grant}。 */
    @PostMapping("/grant")
    public ResponseEntity<Void> grant(@PathVariable String prefix,
                                      @RequestBody(required = false) PermissionAssignment request) {
        authorizationService.requireCatalog(prefix, Privilege.CATALOG_MANAGE_ACCESS,
                "grant a permission");
        permissionManagementService.grant(prefix, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** {@code POST /v1/{prefix}/permissions/revoke}。 */
    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(@PathVariable String prefix,
                                       @RequestBody(required = false) PermissionAssignment request) {
        authorizationService.requireCatalog(prefix, Privilege.CATALOG_MANAGE_ACCESS,
                "revoke a permission");
        permissionManagementService.revoke(prefix, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
