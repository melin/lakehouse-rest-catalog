package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.GroupRoleMappingEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleEntity;
import io.github.melin.lakehouse.rest.domain.repo.GroupRoleMappingRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.CreateGroupMappingRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMapping;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMappingPreview;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMappingPreviewRequest;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.GroupMappings;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.UpdateGroupMappingRequest;
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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 组映射的管理面：增删改查、预览、手动同步。
 *
 * <p>它自己不实现任何映射规则——真正的计算在 {@link GroupRoleSyncService#preview} 与
 * {@link GroupRoleSyncService#sync} 里，两者共用同一段代码。这个类只做四件事：
 * 校验入参、判危险映射、写库、发事件。
 *
 * <p><b>权限只挂服务管理员。</b>身份接入是全局配置，不属于任何 catalog；
 * 现有 14 项 access 词表是资源级的，没有一个能表达「改这条组映射」。
 * 硬塞一个相近的 access 反而会让「谁有权改身份接入」变成一条需要解释的规则。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupMappingService {

    private final GroupRoleMappingRepository mappings;

    private final PrincipalRoleRepository roles;

    private final PrincipalRepository principals;

    private final PrincipalRoleAssignmentRepository assignments;

    private final GroupRoleSyncService sync;

    private final RestServerProperties properties;

    private final CatalogEventPublisher events;

    /**
     * {@code GET /group-mappings?provider=&group=&page=&pageSize=}。
     *
     * <p>映射总条数是个位到百位量级，一次取全再切片比给每条过滤条件写一个派生查询便宜；
     * 分页语义与主体列表一致（不传分页参数时给全量、{@code page} 为 {@code null}）。
     */
    public GroupMappings list(String provider, String group, Integer page, Integer pageSize) {
        List<GroupMapping> result = new ArrayList<>();
        for (GroupRoleMappingEntity entity : mappings.findAllByOrderByProviderNameAscIdpGroupAscRoleNameAsc()) {
            if (provider != null && !provider.isBlank() && !provider.equals(entity.getProviderName())) {
                continue;
            }
            // 组名过滤是「包含」而不是精确匹配：管理员在搜索框里输入的是片段，
            // 而精确匹配是同步时的语义——两件事不要混用一个约定
            if (group != null && !group.isBlank() && !entity.getIdpGroup().contains(group)) {
                continue;
            }
            result.add(toDto(entity));
        }
        Paging.Paged<GroupMapping> paged = Paging.paged(result, page, pageSize);
        return paged == null
                ? new GroupMappings(result, null)
                : new GroupMappings(paged.items(), paged.meta());
    }

    /** {@code POST /group-mappings/preview}：不写库，只算「会得到哪些角色」。 */
    public GroupMappingPreview preview(GroupMappingPreviewRequest request) {
        String provider = providerOrDefault(request == null ? null : request.provider());
        List<String> groups = request == null ? List.of() : request.groups();
        GroupRoleSyncService.Preview preview = sync.preview(provider, groups);
        return new GroupMappingPreview(preview.desiredRoles(), preview.unmappedGroups(),
                preview.unknownRoles(), preview.warnings());
    }

    /** {@code POST /group-mappings}。撞唯一键回 409，不回 500。 */
    public GroupMapping create(CreateGroupMappingRequest request) {
        String provider = providerOrDefault(request == null ? null : request.provider());
        String group = request == null ? null : trimToNull(request.group());
        String roleName = request == null ? null : trimToNull(request.role());
        if (group == null) {
            throw ApiException.badRequest("group is required");
        }
        if (roleName == null) {
            throw ApiException.badRequest("role is required");
        }
        requireRole(roleName);
        requireAdminMappingConfirmed(roleName, request.confirmAdminMapping());
        if (mappings.existsByProviderNameAndIdpGroupAndRoleName(provider, group, roleName)) {
            throw ApiException.conflict("the group " + group + " is already mapped to the role "
                    + roleName);
        }

        GroupRoleMappingEntity entity = new GroupRoleMappingEntity();
        entity.setId(Paging.newId());
        entity.setProviderName(provider);
        entity.setIdpGroup(group);
        entity.setRoleName(roleName);
        entity.setEnabled(request.enabled() == null || request.enabled());
        entity.markCreated(RequestContext.principal(), RequestContext.now());
        GroupRoleMappingEntity saved = mappings.save(entity);
        publish("created", saved);
        return toDto(saved);
    }

    /** {@code PUT /group-mappings/{id}}：改角色与启停；组名与 provider 不可变。 */
    public GroupMapping update(String id, UpdateGroupMappingRequest request) {
        GroupRoleMappingEntity entity = require(id);
        if (request == null || request.currentEntityVersion() == null) {
            throw ApiException.badRequest("currentEntityVersion is required");
        }
        if (request.currentEntityVersion() != entity.getEntityVersion()) {
            throw ApiException.conflict("the group mapping has been modified by someone else;"
                    + " reload it and try again");
        }

        String roleName = trimToNull(request.role());
        boolean roleChanged = roleName != null && !roleName.equals(entity.getRoleName());
        if (roleChanged) {
            requireRole(roleName);
            requireAdminMappingConfirmed(roleName, request.confirmAdminMapping());
            if (mappings.existsByProviderNameAndIdpGroupAndRoleName(
                    entity.getProviderName(), entity.getIdpGroup(), roleName)) {
                throw ApiException.conflict("the group " + entity.getIdpGroup()
                        + " is already mapped to the role " + roleName);
            }
            entity.setRoleName(roleName);
        }
        if (request.enabled() != null) {
            entity.setEnabled(request.enabled());
        }
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        entity.touch(RequestContext.principal(), RequestContext.now());
        GroupRoleMappingEntity saved = mappings.save(entity);
        publish("updated", saved);
        return toDto(saved);
    }

    /**
     * {@code DELETE /group-mappings/{id}}。
     *
     * <p><b>已同步的成员关系不会被删。</b>这是一个刻意的选择：删映射表达的是
     * 「以后别再按这条规则授权」，而不是「现在把已经授出去的收回来」。
     * 后者如果要立刻生效，应该同时调用一次 {@code /principals/{name}/sync}——
     * 让两个动作分开，是因为「删规则」和「撤权」的确认强度不一样。
     */
    public void delete(String id) {
        GroupRoleMappingEntity entity = require(id);
        mappings.delete(entity);
        publish("deleted", entity);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 危险映射的二次确认。
     *
     * <p>判据是「这个角色是不是正被某个服务管理员持有」，而不是名字像不像
     * {@code admin}：名字是习惯，持有关系才是事实。把一组人映射到管理员手里的角色，
     * 等价于把「谁能进 IdP 的这个组」变成「谁能管理整个服务」——
     * 这件事值得多一次确认，但不该被禁止（内部小团队的「全员管理员」是合理场景）。
     */
    private void requireAdminMappingConfirmed(String roleName, Boolean confirmed) {
        if (!isHeldByServiceAdmin(roleName)) {
            return;
        }
        if (Boolean.TRUE.equals(confirmed)) {
            return;
        }
        throw ApiException.badRequest("the role " + roleName + " is held by a service administrator;"
                + " set confirmAdminMapping=true to map a group to it");
    }

    private boolean isHeldByServiceAdmin(String roleName) {
        List<String> adminNames = properties.getAuthorization().getServiceAdmins();
        if (adminNames == null || adminNames.isEmpty()) {
            return false;
        }
        for (String adminName : adminNames) {
            Optional<PrincipalEntity> admin = principals.findByName(adminName);
            if (admin.isEmpty()) {
                continue;
            }
            for (PrincipalRoleAssignmentEntity assignment
                    : assignments.findByPrincipalId(admin.get().getId())) {
                Optional<PrincipalRoleEntity> role = roles.findById(assignment.getPrincipalRoleId());
                if (role.isPresent() && role.get().getName().equals(roleName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void requireRole(String roleName) {
        if (!roles.existsByName(roleName)) {
            // 保存时就拦住拼错，而不是留到同步时「跳过并告警」：
            // 那时候没人看着日志，表现只是「这个人少了一个角色」
            throw ApiException.managementNotExist(ResourceType.PRINCIPAL_ROLE, roleName);
        }
    }

    private GroupRoleMappingEntity require(String id) {
        if (id == null || id.isBlank()) {
            throw ApiException.badRequest("group mapping id is required");
        }
        return mappings.findById(id).orElseThrow(() -> new ApiException(404, null, id,
                "the group mapping does not exist"));
    }

    private void publish(String action, GroupRoleMappingEntity entity) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("action", action);
        attributes.put("provider", entity.getProviderName());
        attributes.put("group", entity.getIdpGroup());
        attributes.put("role", entity.getRoleName());
        attributes.put("enabled", entity.isEnabled());
        events.publish(CatalogEventType.GROUP_MAPPING_CHANGED, "",
                CatalogEvent.TARGET_ROLE, entity.getIdpGroup(), attributes);
    }

    private GroupMapping toDto(GroupRoleMappingEntity entity) {
        return new GroupMapping(entity.getId(), entity.getProviderName(), entity.getIdpGroup(),
                entity.getRoleName(), entity.isEnabled(), entity.getCreatedAt(),
                entity.getUpdatedAt(), entity.getEntityVersion());
    }

    private static String providerOrDefault(String provider) {
        String value = trimToNull(provider);
        return value == null ? GroupRoleSyncService.DEFAULT_PROVIDER : value;
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
