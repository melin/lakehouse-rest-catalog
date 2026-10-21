package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleEntity;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.CreatePrincipalRoleRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Principal;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.PrincipalRole;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.PrincipalRoles;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.Principals;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.UpdatePrincipalRoleRequest;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * principal role 的增删改查，以及主体与它的成员关系。
 *
 * <p><b>它只是主体分组。</b>官方权限模型里没有角色，授权对象是
 * {@code (resource, access, principal)} 里的那个不透明 principal 字符串。
 * 本服务把 principal role 映射成 principal 命名空间里的 {@code role:<name>}：
 * 给 {@code role:analyst} 授的权，所有成员都会通过
 * {@link AuthorizationService#principalCandidates} 命中。
 *
 * <p>因此这一层不承载任何权限数据——角色解散时只有成员关系消失，
 * 授予该角色的赋权条目仍留在 {@code lakehouse_permission_assignment} 里
 * （再建同名角色会重新生效，这一点写进了 {@code docs/authorization.md}）。
 */
@Service
@RequiredArgsConstructor
public class PrincipalRoleService {

    private final PrincipalRoleRepository principalRoleRepository;
    private final PrincipalRepository principalRepository;
    private final PrincipalRoleAssignmentRepository principalRoleAssignmentRepository;
    private final CatalogEventPublisher events;

    /**
     * {@code GET /principal-roles?page=&pageSize=}：远程分页。
     *
     * <p>排序键是角色名（仓储的派生查询已按它升序），分页因此稳定。
     * 不传分页参数时给全量、{@code page} 为 {@code null}。
     */
    @Transactional(readOnly = true)
    public PrincipalRoles list(Integer page, Integer pageSize) {
        List<PrincipalRole> roles = new ArrayList<>();
        for (PrincipalRoleEntity entity : principalRoleRepository.findAllByOrderByNameAsc()) {
            roles.add(ManagementMappers.toDto(entity));
        }
        Paging.Paged<PrincipalRole> paged = Paging.paged(roles, page, pageSize);
        return paged == null ? new PrincipalRoles(roles, null) : new PrincipalRoles(paged.items(), paged.meta());
    }

    /** {@code POST /principal-roles}。 */
    @Transactional
    public PrincipalRole create(CreatePrincipalRoleRequest request) {
        String name = request == null || request.principalRole() == null
                ? null : request.principalRole().name();
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("principalRole.name is required");
        }
        if (principalRoleRepository.existsByName(name)) {
            throw ApiException.managementAlreadyExist(ResourceType.PRINCIPAL_ROLE, name);
        }
        PrincipalRoleEntity entity = new PrincipalRoleEntity();
        entity.setId(Paging.newId());
        entity.setName(name);
        entity.setFederated(Boolean.TRUE.equals(request.principalRole().federated()));
        Map<String, String> properties = request.principalRole().properties();
        entity.setProperties(properties == null ? new LinkedHashMap<>() : new LinkedHashMap<>(properties));
        entity.markCreated(RequestContext.principal(), RequestContext.now());
        PrincipalRoleEntity saved = principalRoleRepository.save(entity);
        events.publish(CatalogEventType.PRINCIPAL_ROLE_CREATED, "", CatalogEvent.TARGET_ROLE, name,
                Map.of("federated", saved.isFederated()));
        return ManagementMappers.toDto(saved);
    }

    /** {@code GET /principal-roles/{principalRoleName}}。 */
    @Transactional(readOnly = true)
    public PrincipalRole get(String name) {
        return ManagementMappers.toDto(require(name));
    }

    /** {@code PUT /principal-roles/{principalRoleName}}。 */
    @Transactional
    public PrincipalRole update(String name, UpdatePrincipalRoleRequest request) {
        PrincipalRoleEntity entity = require(name);
        if (request == null || request.currentEntityVersion() == null) {
            throw ApiException.badRequest("currentEntityVersion is required");
        }
        if (request.currentEntityVersion() != entity.getEntityVersion()) {
            throw ApiException.entityVersionMismatch(ResourceType.PRINCIPAL_ROLE, name,
                    request.currentEntityVersion(), entity.getEntityVersion());
        }
        if (request.properties() == null) {
            throw ApiException.badRequest("properties is required");
        }
        entity.setProperties(new LinkedHashMap<>(request.properties()));
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        PrincipalRoleEntity saved = principalRoleRepository.save(entity);
        events.publish(CatalogEventType.PRINCIPAL_ROLE_UPDATED, "", CatalogEvent.TARGET_ROLE, name,
                Map.of("entityVersion", saved.getEntityVersion()));
        return ManagementMappers.toDto(saved);
    }

    /**
     * {@code DELETE /principal-roles/{principalRoleName}}。
     *
     * <p>只清理成员关系。授予 {@code role:<name>} 的赋权不删除：那是 catalog 里的
     * 权限记录，删除角色不该顺手回收别人名下的授权；同名角色重建后成员会重新继承，
     * 这是可预期的行为，文档里写明了。
     */
    @Transactional
    public void delete(String name) {
        PrincipalRoleEntity entity = require(name);
        principalRoleAssignmentRepository.deleteByPrincipalRoleId(entity.getId());
        principalRoleRepository.delete(entity);
        events.publish(CatalogEventType.PRINCIPAL_ROLE_DROPPED, "", CatalogEvent.TARGET_ROLE, name,
                Map.of());
    }

    /**
     * {@code GET /principal-roles/{principalRoleName}/principals?page=&pageSize=}。
     *
     * <p>按主体名排序后再切页：赋值表的行序不保证稳定，直接切会让同一页
     * 两次请求拿到不同的成员。
     */
    @Transactional(readOnly = true)
    public Principals listPrincipals(String principalRoleName, Integer page, Integer pageSize) {
        PrincipalRoleEntity role = require(principalRoleName);
        List<Principal> principals = new ArrayList<>();
        for (PrincipalRoleAssignmentEntity assignment
                : principalRoleAssignmentRepository.findByPrincipalRoleId(role.getId())) {
            principalRepository.findById(assignment.getPrincipalId())
                    .map(ManagementMappers::toDto)
                    .ifPresent(principals::add);
        }
        principals.sort(java.util.Comparator.comparing(Principal::name));
        Paging.Paged<Principal> paged = Paging.paged(principals, page, pageSize);
        return paged == null ? new Principals(principals, null) : new Principals(paged.items(), paged.meta());
    }

    /** 按名称取 principal role，不存在则 404。 */
    @Transactional(readOnly = true)
    public PrincipalRoleEntity require(String name) {
        if (name == null || name.isBlank()) {
            throw ApiException.badRequest("principal role name is required");
        }
        return principalRoleRepository.findByName(name)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.PRINCIPAL_ROLE, name));
    }

    /** 批量按主键转 DTO，供其他服务复用。 */
    @Transactional(readOnly = true)
    public PrincipalRoles toRoles(List<String> principalRoleIds) {
        List<PrincipalRole> roles = new ArrayList<>();
        for (PrincipalRoleEntity entity : principalRoleRepository.findAllById(principalRoleIds)) {
            roles.add(ManagementMappers.toDto(entity));
        }
        roles.sort(java.util.Comparator.comparing(PrincipalRole::name));
        return new PrincipalRoles(roles, null);
    }

    /** 该 principal role 的成员主体主键，供诊断与测试读取。 */
    @Transactional(readOnly = true)
    public List<String> principalIdsOf(String principalRoleName) {
        return principalRoleRepository.findByName(principalRoleName)
                .map(role -> principalRoleAssignmentRepository.findByPrincipalRoleId(role.getId())
                        .stream().map(PrincipalRoleAssignmentEntity::getPrincipalId).toList())
                .orElse(List.of());
    }

    /** 主体在某角色上的成员实体，供 {@code PrincipalController} 判重。 */
    @Transactional(readOnly = true)
    public boolean isMember(String principalId, String principalRoleId) {
        return principalRoleAssignmentRepository
                .existsByPrincipalIdAndPrincipalRoleId(principalId, principalRoleId);
    }

    /** 主体实体，供成员关系端点复用。 */
    @Transactional(readOnly = true)
    public PrincipalEntity requirePrincipal(String name) {
        return principalRepository.findByName(name)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.PRINCIPAL, name));
    }
}
