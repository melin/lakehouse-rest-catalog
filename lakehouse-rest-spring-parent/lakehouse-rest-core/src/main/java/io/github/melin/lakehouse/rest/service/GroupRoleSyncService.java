package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.GrantSource;
import io.github.melin.lakehouse.rest.domain.entity.GroupRoleMappingEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleEntity;
import io.github.melin.lakehouse.rest.domain.repo.GroupRoleMappingRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.ResourceType;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * IdP 组 → 主体角色的同步。
 *
 * <p>这是整条身份链路上唯一会写授权数据的地方。它只做一件事：
 * 把「这个人现在属于哪些组」翻译成「这个人现在该有哪些 principal role」，
 * 然后<b>只增删 {@code granted_by=IDP} 的成员关系</b>。
 *
 * <h2>三条不变量（破坏任何一条都会静默地把权限搞错）</h2>
 *
 * <ol>
 *   <li><b>手工授权不可见</b>：差集只由 {@code granted_by=IDP} 的行构成。
 *       把手工行算进来，IdP 少一个组就会连管理员手工授的角色一起删掉。
 *   <li><b>幂等</b>：同样的组跑两次，第二次不增不删、不发事件。
 *   <li><b>只动成员关系</b>：不创建、不删除 principal role 本身；映射里的角色名
 *       不存在时跳过并告警，绝不让登录 500——管理员改错一个名字不该导致全员登不上。
 * </ol>
 *
 * <h2>为什么 {@link #preview} 与 {@link #sync} 共用一段计算</h2>
 *
 * <p>控制台的「保存前预览」如果自己实现一遍映射规则，它迟早会和真正生效的逻辑
 * 分叉，而分叉的表现是「预览说会得到两个角色，保存后一个都没有」——
 * 这类问题几乎无法从现象反推原因。因此两者都调 {@link #desired}，
 * 预览只是不写库的那一半。
 *
 * <h2>为什么这里不开事务</h2>
 *
 * <p>同步的每一步都是幂等的（差集算法天然如此），而「一个事务包住全部」
 * 会带来一个更糟的后果：并发登录时两个事务互相等待同一批行。这里改为
 * 每次仓储调用各自原子，失败时最坏是「这次少加了一个角色」，
 * 下一次登录（或在控制台点一次同步）就收敛。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GroupRoleSyncService {

    /** 单 IdP 部署下 provider 的固定取值；多 IdP 时才需要真正的区分。 */
    public static final String DEFAULT_PROVIDER = "default";

    private final GroupRoleMappingRepository mappings;

    private final PrincipalRoleRepository roles;

    private final PrincipalRoleAssignmentRepository assignments;

    private final PrincipalRepository principals;

    private final CatalogEventPublisher events;

    private final RestServerProperties properties;

    /** 预览结果：会得到哪些角色、哪些组没配映射、哪些映射指向了不存在的角色。 */
    public record Preview(List<String> desiredRoles, List<String> unmappedGroups,
                          List<String> unknownRoles, List<String> warnings) {
    }

    /** 同步结果。{@code skippedReason} 非空表示这次<b>刻意</b>没做任何改动，以及为什么。 */
    public record SyncResult(List<String> added, List<String> removed, int groups,
                             String skippedReason) {

        public boolean skipped() {
            return skippedReason != null;
        }

        public boolean changed() {
            return !added.isEmpty() || !removed.isEmpty();
        }
    }

    /** 一份「该有哪些角色」的中间结果，{@link #preview} 与 {@link #sync} 共用。 */
    private record Desired(Set<String> roles, List<String> unmappedGroups, List<String> unknownRoles,
                           List<String> warnings) {
    }

    /**
     * 给定一组 IdP 组名，算出会得到哪些角色。<b>不写库</b>。
     *
     * @param provider 身份提供方标识，单 IdP 用 {@link #DEFAULT_PROVIDER}
     * @param groups   该用户当前的组名；{@code null} 视为空
     */
    public Preview preview(String provider, Collection<String> groups) {
        Desired desired = desired(provider, groups);
        return new Preview(List.copyOf(desired.roles()), desired.unmappedGroups(),
                desired.unknownRoles(), desired.warnings());
    }

    /**
     * 把主体的 IDP 来源角色收敛到「当前组该有的样子」。
     *
     * <p><b>{@code groupsPresent=false} 且模式是 {@code ON_LOGIN} 时不做删除</b>，
     * 只补 {@code default-roles}：很多 IdP 的 {@code groups} 只在特定 scope 或
     * 只能从 access_token 里取，把「这次没带」解释成「这个人不在任何组」，
     * 会在一次 scope 配置错误后削掉所有人的权限，而现象看起来像「IdP 挂了」。
     *
     * @param principal     目标主体（必须已落库）
     * @param groups        令牌里的组名；{@code null} 视为空
     * @param groupsPresent 令牌里到底有没有这个 claim（空数组算「有」）
     */
    public SyncResult sync(PrincipalEntity principal, Collection<String> groups, boolean groupsPresent) {
        RestServerProperties.Auth.Oidc oidc = properties.getAuth().getConsole().getOidc();
        RestServerProperties.Auth.Oidc.SyncMode mode = oidc.getSyncMode();
        if (mode == RestServerProperties.Auth.Oidc.SyncMode.OFF) {
            return new SyncResult(List.of(), List.of(), sizeOf(groups), "sync-mode-off");
        }

        Desired desired = desired(DEFAULT_PROVIDER, groups);
        Set<String> defaults = defaultRoles();

        Map<String, String> currentByRoleName = currentIdpRoles(principal);
        Set<String> current = new LinkedHashSet<>(currentByRoleName.keySet());

        Set<String> target = new LinkedHashSet<>();
        String skippedReason = null;
        if (!groupsPresent && mode == RestServerProperties.Auth.Oidc.SyncMode.ON_LOGIN) {
            // 只补默认角色，一个都不删
            target.addAll(current);
            target.addAll(defaults);
            skippedReason = "groups-claim-absent";
        } else {
            target.addAll(desired.roles());
            target.addAll(defaults);
        }

        Set<String> toAdd = new TreeSet<>(target);
        toAdd.removeAll(current);
        Set<String> toRemove = new TreeSet<>(current);
        toRemove.removeAll(target);

        for (String roleName : toRemove) {
            assignments.deleteById(currentByRoleName.get(roleName));
        }
        for (String roleName : toAdd) {
            roles.findByName(roleName)
                    .ifPresent(role -> assignments.save(newAssignment(principal, role)));
        }

        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            attributes.put("added", List.copyOf(toAdd));
            attributes.put("removed", List.copyOf(toRemove));
            attributes.put("mode", mode.name());
            attributes.put("groupsCount", sizeOf(groups));
            events.publish(CatalogEventType.PRINCIPAL_ROLES_SYNCED, "",
                    CatalogEvent.TARGET_PRINCIPAL, principal.getName(), attributes);
            log.info("synced IdP roles for principal {}: +{} -{} (mode={})",
                    principal.getName(), toAdd, toRemove, mode);
        }
        return new SyncResult(List.copyOf(toAdd), List.copyOf(toRemove), sizeOf(groups), skippedReason);
    }

    /**
     * 手动重算一个主体的 IDP 角色（管理面 {@code POST /principals/{name}/sync}）。
     *
     * <p>与登录时走同一条 {@link #sync}，因此结果一致、同样幂等。区别只有一处：
     * 这里没有令牌可用，组名由调用方给。
     *
     * @throws ApiException 404 主体不存在
     */
    public SyncResult syncPrincipal(String principalName, Collection<String> groups) {
        PrincipalEntity principal = principals.findByName(principalName)
                .orElseThrow(() -> ApiException.managementNotExist(ResourceType.PRINCIPAL,
                        principalName));
        return sync(principal, groups, true);
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 「组 → 角色」的翻译，以及所有需要告警的情况。
     *
     * <p>匹配是<b>精确的</b>：区分大小写、不 trim、不做前缀或通配。看起来苛刻，
     * 但这些差异恰恰是最常见的配置错误（Keycloak 的 full group path 打开后组名是
     * {@code /data-engineers}），而「猜一下」会把错误变成一个更难查的「有时命中有时不中」。
     */
    private Desired desired(String provider, Collection<String> groups) {
        List<String> normalized = normalize(groups);
        Set<String> roleNames = new LinkedHashSet<>();
        List<String> unmapped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!normalized.isEmpty()) {
            Map<String, List<GroupRoleMappingEntity>> byGroup = new LinkedHashMap<>();
            for (GroupRoleMappingEntity mapping : mappings.findByProviderNameAndIdpGroupIn(
                    provider, normalized)) {
                byGroup.computeIfAbsent(mapping.getIdpGroup(), key -> new ArrayList<>()).add(mapping);
            }
            for (String group : normalized) {
                List<GroupRoleMappingEntity> rows = byGroup.getOrDefault(group, List.of());
                boolean mapped = false;
                for (GroupRoleMappingEntity row : rows) {
                    if (!row.isEnabled()) {
                        continue;
                    }
                    mapped = true;
                    roleNames.add(row.getRoleName());
                }
                if (!mapped) {
                    // 「一行都没有」与「有但全停用」要分开说：前者是漏配，
                    // 后者是管理员故意关掉的，把它们混成一句会让人去重复建一条
                    if (rows.isEmpty()) {
                        unmapped.add(group);
                        warnings.add("group \"" + group + "\" has no mapping");
                    } else {
                        warnings.add("group \"" + group + "\" has only disabled mappings");
                    }
                }
            }
        }

        // 角色存在性一次问完：逐个查会在「查到一半角色被删」时给出半套结果，
        // 而且组多的时候是 N 次往返
        List<String> unknown = new ArrayList<>();
        if (!roleNames.isEmpty()) {
            Set<String> existing = new LinkedHashSet<>();
            for (PrincipalRoleEntity role : roles.findByNameIn(roleNames)) {
                existing.add(role.getName());
            }
            for (String name : roleNames) {
                if (!existing.contains(name)) {
                    unknown.add(name);
                    // 跳过而不是报错：管理员写错一个角色名，不该让所有人登不上
                    warnings.add("role \"" + name + "\" does not exist; skipped");
                }
            }
            roleNames.retainAll(existing);
        }
        return new Desired(roleNames, List.copyOf(unmapped), List.copyOf(unknown), List.copyOf(warnings));
    }

    /** 主体当前由 IDP 来源授予的角色：角色名 → 成员关系 id。 */
    private Map<String, String> currentIdpRoles(PrincipalEntity principal) {
        List<PrincipalRoleAssignmentEntity> rows =
                assignments.findByPrincipalIdAndGrantedBy(principal.getId(), GrantSource.IDP);
        if (rows.isEmpty()) {
            return Map.of();
        }
        Map<String, String> byRoleId = new LinkedHashMap<>();
        List<String> ids = new ArrayList<>();
        for (PrincipalRoleAssignmentEntity row : rows) {
            byRoleId.put(row.getPrincipalRoleId(), row.getId());
            ids.add(row.getPrincipalRoleId());
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (PrincipalRoleEntity role : roles.findAllByIdIn(ids)) {
            String assignmentId = byRoleId.get(role.getId());
            if (assignmentId != null) {
                result.put(role.getName(), assignmentId);
            }
        }
        return result;
    }

    /**
     * 配置里的基线角色，只保留真的存在的。
     *
     * <p>配错的角色名跳过而不是让登录失败——这与「映射指向不存在的角色」同一条理由。
     */
    private Set<String> defaultRoles() {
        List<String> configured = properties.getAuth().getConsole().getOidc().getDefaultRoles();
        Set<String> result = new LinkedHashSet<>();
        if (configured == null || configured.isEmpty()) {
            return result;
        }
        List<String> wanted = normalize(configured);
        if (wanted.isEmpty()) {
            return result;
        }
        for (PrincipalRoleEntity role : roles.findByNameIn(wanted)) {
            result.add(role.getName());
        }
        for (String name : wanted) {
            if (!result.contains(name)) {
                log.warn("oidc.default-roles references the unknown role {}; ignored", name);
            }
        }
        return result;
    }

    private PrincipalRoleAssignmentEntity newAssignment(PrincipalEntity principal,
            PrincipalRoleEntity role) {
        PrincipalRoleAssignmentEntity assignment = new PrincipalRoleAssignmentEntity();
        assignment.setId(Paging.newId());
        assignment.setPrincipalId(principal.getId());
        assignment.setPrincipalRoleId(role.getId());
        // 这一行是同步产生的，下一次同步才允许删它——正是 grantedBy 的意义
        assignment.setGrantedBy(GrantSource.IDP);
        assignment.markCreated(RequestContext.principal(), RequestContext.now());
        return assignment;
    }

    private static List<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        // distinct 但保持顺序：预览的展示顺序要与管理员填的顺序一致
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private static int sizeOf(Collection<String> values) {
        return values == null ? 0 : normalize(values).size();
    }
}
