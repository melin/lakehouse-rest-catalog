package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.ManagementDtos.CreatePrincipalRoleRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.PrincipalRole;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.PrincipalRoles;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Principals;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.UpdatePrincipalRoleRequest;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.PrincipalRoleService;
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
 * 管理面的 principal role 端点，共 7 个 operation（服务端扩展，非官方契约）。
 *
 * <p>官方管理 API 没有角色概念：授权对象是 principal 字符串，成员关系由服务端解析。
 * 本服务把 principal role 当作「主体分组」，对外表现为 {@code role:<name>}。
 * 它自身不携带权限，因此整组按服务管理员处理；「谁能对什么资源做什么」全部走
 * {@code /v1/{prefix}/permissions}。
 */
@RestController
@RequestMapping("/api/management/v1/principal-roles")
@RequiredArgsConstructor
public class PrincipalRoleController {

    private final PrincipalRoleService principalRoleService;
    private final AuthorizationService authorizationService;

    /** {@code GET /principal-roles}。 */
    @GetMapping
    public PrincipalRoles list() {
        authorizationService.requireServiceAdmin("list principal roles");
        return principalRoleService.list();
    }

    /** {@code POST /principal-roles}。 */
    @PostMapping
    public ResponseEntity<PrincipalRole> create(
            @RequestBody(required = false) CreatePrincipalRoleRequest request) {
        authorizationService.requireServiceAdmin("create a principal role");
        return ResponseEntity.status(HttpStatus.CREATED).body(principalRoleService.create(request));
    }

    /** {@code GET /principal-roles/{principalRoleName}}。 */
    @GetMapping("/{principalRoleName}")
    public PrincipalRole get(@PathVariable String principalRoleName) {
        authorizationService.requireServiceAdmin("read a principal role");
        return principalRoleService.get(principalRoleName);
    }

    /** {@code PUT /principal-roles/{principalRoleName}}。 */
    @PutMapping("/{principalRoleName}")
    public PrincipalRole update(@PathVariable String principalRoleName,
                                @RequestBody(required = false) UpdatePrincipalRoleRequest request) {
        authorizationService.requireServiceAdmin("update a principal role");
        return principalRoleService.update(principalRoleName, request);
    }

    /** {@code DELETE /principal-roles/{principalRoleName}}。 */
    @DeleteMapping("/{principalRoleName}")
    public ResponseEntity<Void> delete(@PathVariable String principalRoleName) {
        authorizationService.requireServiceAdmin("delete a principal role");
        principalRoleService.delete(principalRoleName);
        return ResponseEntity.noContent().build();
    }

    /** {@code GET /principal-roles/{principalRoleName}/principals}。 */
    @GetMapping("/{principalRoleName}/principals")
    public Principals listPrincipals(@PathVariable String principalRoleName) {
        authorizationService.requireServiceAdmin("read the principals of a principal role");
        return principalRoleService.listPrincipals(principalRoleName);
    }
}
