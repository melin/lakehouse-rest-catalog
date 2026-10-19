package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionAssignment;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionList;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.PermissionManagementService;
import java.util.List;
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
 *
 * <p>官方要求「查看、授予、撤销权限需要调用者对相关资源具备 {@code GRANT}」，
 * 三条端点因此分两种判法：
 * <ul>
 *   <li><b>列赋权</b>：目标是一整个 catalog 的赋权集合，要求
 *       {@code CATALOG_ALL} 上的 {@code GRANT}；</li>
 *   <li><b>授予 / 撤销</b>：目标资源在请求体里，路径映射表算不出作用域，
 *       因此 {@code CatalogAccessRules} 把这两个端点标成 deferred，
 *       由这里拿着 {@code request.resource()} 调
 *       {@link AuthorizationService#requireGrantOn}——在 catalog 级持 {@code GRANT}
 *       的人覆盖其下一切，在单表上持 {@code GRANT} 的人只能管那张表。</li>
 * </ul>
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
        authorizationService.require(prefix, ResourceType.CATALOG_ALL, List.of(), null,
                Access.GRANT, "list permissions");
        return permissionManagementService.list(prefix, resourceType, database, table, view,
                function, principal, access, maxResults, pageToken);
    }

    /** {@code POST /v1/{prefix}/permissions/grant}。 */
    @PostMapping("/grant")
    public ResponseEntity<Void> grant(@PathVariable String prefix,
                                      @RequestBody(required = false) PermissionAssignment request) {
        authorizationService.requireGrantOn(prefix,
                request == null ? null : request.resource(), "grant a permission");
        permissionManagementService.grant(prefix, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    /** {@code POST /v1/{prefix}/permissions/revoke}。 */
    @PostMapping("/revoke")
    public ResponseEntity<Void> revoke(@PathVariable String prefix,
                                       @RequestBody(required = false) PermissionAssignment request) {
        authorizationService.requireGrantOn(prefix,
                request == null ? null : request.resource(), "revoke a permission");
        permissionManagementService.revoke(prefix, request);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }
}
