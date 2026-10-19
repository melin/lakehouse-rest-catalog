package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.PermissionAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.PermissionAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleAssignmentRepository;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionResource;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 授权判定：回答「调用者能否在某个资源上执行某个 access」。
 *
 * <p><b>只有一套权限模型。</b>全部授权都落在 {@code lakehouse_permission_assignment} 上，
 * 形状是 Paimon REST Management API 的三元组 {@code (resource, access, principal)}，
 * access 取值就是官方那 12 项（{@code ALL / CREATEDATABASE / DESCRIBE / ALTER / DROP /
 * CREATETABLE / CREATEFUNCTION / CREATEVIEW / LIST / SELECT / UPDATE / GRANT}）。
 * 不再有 Polaris 的 64 项权限、catalog role 与资源授权表——判定层因此只剩一个真相源。
 *
 * <p><b>主体解析。</b>{@code principal} 在官方语义里是不透明字符串，解析归服务端。
 * 本服务的约定是三条候选串：裸名 {@code alice}、{@code user:alice}，以及主体通过
 * principal role 成员关系得到的 {@code role:<name>}。principal role 因此退化成
 * 「主体分组」：它自己不带任何权限，只把授予 {@code role:x} 的赋权转发给成员。
 *
 * <p><b>作用域覆盖。</b>官方把 {@code CATALOG_ALL} / {@code DATABASE_ALL} 定义为
 * **显式后代作用域**（{@code CatalogAllResource}：covering database, table, view, and
 * function descendants；{@code DatabaseAllResource}：covering table, view, and function
 * descendants of one database），其余类型只覆盖自身：
 * <ul>
 *   <li>{@code CATALOG} 只覆盖 catalog 本身（改属性、删 catalog）；</li>
 *   <li>{@code CATALOG_ALL} 覆盖 catalog 下的命名空间、表、视图、函数，**不含 catalog 实体**；</li>
 *   <li>{@code DATABASE} 覆盖该库本身，以及「该库的集合操作」（列库内的表/视图/函数）；</li>
 *   <li>{@code DATABASE_ALL} 覆盖该库的后代，**不含库实体**；</li>
 *   <li>{@code TABLE} / {@code VIEW} / {@code FUNCTION} 只覆盖同名对象；</li>
 *   <li>{@code COLUMN} 只覆盖列级目标——**表级检查不收列级赋权**，
 *       否则「只授权了 a、b 两列」会被判成「整表可读」。</li>
 * </ul>
 * 两个 {@code _ALL} 不含容器本身是官方描述的直接结果，代价是
 * 「改 catalog 属性」要 {@code CATALOG:ALTER}、「改库属性」要 {@code DATABASE:ALTER}、
 * 「建库」要 {@code CATALOG:CREATEDATABASE}——引导角色因此同时持有
 * {@code CATALOG/ALL} 与 {@code CATALOG_ALL/ALL} 两条。
 *
 * <p><b>access 是否匹配。</b>官方没有给出权限蕴含关系，只有 {@code ALL}
 * （「All accesses applicable to the resource」）一个聚合值。因此判定是
 * 「相等，或授予的是 {@code ALL}」，不做任何自定义的包含推断——那会造出
 * 规格里并不存在的授权语义。
 *
 * <p><b>开关与管理员。</b>{@code lakehouse.rest.authorization.enabled} 为假时全部放行；
 * 服务管理员名单（{@code …service-admins}）直接放行，用于主体/角色/目录这类
 * 管理面操作——官方把这些生命周期明确归给服务端，不在 12 项 access 的表达范围内。
 */
@Service
@RequiredArgsConstructor
public class AuthorizationService {

    /**
     * 命名空间在「路径」这一侧的分隔符，与 {@link NamespacePaths#STORAGE_SEPARATOR} 同一口径：
     * 库里那一列库名、Iceberg 的多级 namespace、SQL 里的三段名都用点号。
     */
    private static final String NAMESPACE_SEPARATOR = String.valueOf(NamespacePaths.STORAGE_SEPARATOR);

    /** 官方的两个主体前缀。裸名与 {@code user:} 等价，方便两种习惯混用。 */
    private static final String USER_PREFIX = "user:";
    private static final String ROLE_PREFIX = "role:";

    private final RestServerProperties properties;
    private final PrincipalRepository principalRepository;
    private final PrincipalRoleRepository principalRoleRepository;
    private final PrincipalRoleAssignmentRepository principalRoleAssignmentRepository;
    private final CatalogRepository catalogRepository;
    private final PermissionAssignmentRepository permissionAssignmentRepository;

    /** 授权总开关。 */
    public boolean enabled() {
        return properties.getAuthorization().isEnabled();
    }

    /** 当前调用者是否为服务管理员。 */
    public boolean isServiceAdmin(String principalName) {
        if (principalName == null) {
            return false;
        }
        return properties.getAuthorization().getServiceAdmins().stream()
                .anyMatch(admin -> admin.equalsIgnoreCase(principalName));
    }

    /** 要求服务管理员身份，用于主体、principal role 与 catalog 的生命周期端点。 */
    public void requireServiceAdmin(String action) {
        if (!enabled()) {
            return;
        }
        String principal = RequestContext.principal();
        if (!isServiceAdmin(principal)) {
            throw ApiException.forbidden("The caller does not have permission to " + action
                    + ": principal " + principal + " is not a service admin");
        }
    }

    /** 要求调用者在 catalog 实体本身上具备某项 access。 */
    @Transactional(readOnly = true)
    public void requireCatalog(String catalogName, Access required, String action) {
        require(catalogName, ResourceType.CATALOG, List.of(), null, required, action);
    }

    /**
     * 要求调用者在指定资源上具备某项 access。
     *
     * <p><b>catalog 不存在时不判定。</b>此时直接返回，让下层服务抛出 404。
     * 否则「无权限」与「无此 catalog」都会变成 403，调用方无法区分是名字写错
     * 还是确实没有授权。
     *
     * @param catalogName  catalog 名（即 prefix）
     * @param resourceType 目标资源类型（{@code COLUMN} 不是合法的目标，列级赋权不参与表级判定）
     * @param namespace    多级命名空间；catalog 级传空列表
     * @param objectName   对象名；catalog / 命名空间级传 {@code null}
     * @param required     要求的 access
     * @param action       操作描述，用于 403 错误信息
     * @throws ApiException 403 权限不足
     */
    @Transactional(readOnly = true)
    public void require(String catalogName, ResourceType resourceType, List<String> namespace,
                        String objectName, Access required, String action) {
        if (!enabled()) {
            return;
        }
        String principal = RequestContext.principal();
        if (isServiceAdmin(principal)) {
            return;
        }
        Optional<String> catalogId = catalogIdOf(catalogName);
        if (catalogId.isEmpty()) {
            return;
        }
        if (!satisfies(principal, catalogId.get(), resourceType, namespace, objectName, required)) {
            throw ApiException.forbidden("The caller does not have permission to " + action
                    + ": principal " + principal + " lacks " + required
                    + " on " + describe(resourceType, namespace, objectName)
                    + " in catalog " + catalogName);
        }
    }

    /**
     * 授权端点自身的前置校验：调用者必须对**被授予的那个资源**持有 {@code GRANT}。
     *
     * <p>授权端点的目标资源在请求体里，路径映射表看不到，因此
     * {@code CatalogAccessRules} 把这两个端点标成 deferred，由控制器拿着解析后的
     * 资源调这里。{@code GRANT} 的覆盖规则与其它 access 相同——在 catalog 级
     * 持有 {@code GRANT} 的人自然覆盖其下一切，在单表上持有的人只能管那张表。
     */
    @Transactional(readOnly = true)
    public void requireGrantOn(String catalogName, PermissionResource resource, String action) {
        if (resource == null) {
            throw ApiException.badRequest("resource is required");
        }
        ResourceType type = ResourceType.parse(resource.type())
                .orElseThrow(() -> ApiException.badRequest("Unknown resource type: " + resource.type()));
        require(catalogName, type, namespaceOf(resource), objectOf(resource), Access.GRANT, action);
    }

    /**
     * 调用者在某个 catalog 下是否至少持有一条赋权（任何资源、任何 access）。
     *
     * <p>供管理面的 catalog 列表做可见性过滤：看不到 catalog 的调用方不该从列表里
     * 探测出服务端有哪些 catalog。
     */
    @Transactional(readOnly = true)
    public boolean hasAnyGrant(String principalName, String catalogName) {
        if (!enabled()) {
            return true;
        }
        Optional<String> catalogId = catalogIdOf(catalogName);
        if (catalogId.isEmpty()) {
            return false;
        }
        List<String> candidates = principalCandidates(principalName);
        if (candidates.isEmpty()) {
            return false;
        }
        long now = RequestContext.now();
        for (PermissionAssignmentEntity assignment
                : permissionAssignmentRepository.findByCatalogIdAndPrincipalIn(catalogId.get(), candidates)) {
            if (!isExpired(assignment, now)) {
                return true;
            }
        }
        return false;
    }

    /** 调用者在某个 catalog 上实际持有的全部有效赋权，供诊断与测试读取。 */
    @Transactional(readOnly = true)
    public List<PermissionAssignmentEntity> effectiveAssignments(String principalName, String catalogName) {
        Optional<String> catalogId = catalogIdOf(catalogName);
        List<String> candidates = principalCandidates(principalName);
        if (catalogId.isEmpty() || candidates.isEmpty()) {
            return List.of();
        }
        long now = RequestContext.now();
        List<PermissionAssignmentEntity> result = new ArrayList<>();
        for (PermissionAssignmentEntity assignment
                : permissionAssignmentRepository.findByCatalogIdAndPrincipalIn(catalogId.get(), candidates)) {
            if (!isExpired(assignment, now)) {
                result.add(assignment);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ 判定内部

    private boolean satisfies(String principalName, String catalogId, ResourceType resourceType,
                              List<String> namespace, String objectName, Access required) {
        List<String> candidates = principalCandidates(principalName);
        if (candidates.isEmpty()) {
            return false;
        }
        long now = RequestContext.now();
        for (PermissionAssignmentEntity assignment
                : permissionAssignmentRepository.findByCatalogIdAndPrincipalIn(catalogId, candidates)) {
            if (isExpired(assignment, now)) {
                continue;
            }
            if (!grantsAccess(assignment.getAccess(), required)) {
                continue;
            }
            if (covers(assignment, resourceType, namespace, objectName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 主体在某个 catalog 下的 principal 候选串。
     *
     * <p>裸名与 {@code user:} 前缀等价；{@code role:<name>} 来自主体持有的
     * principal role（成员关系解析是服务端职责，见官方 {@code Principal} 说明）。
     */
    @Transactional(readOnly = true)
    public List<String> principalCandidates(String principalName) {
        if (principalName == null || principalName.isBlank()) {
            return List.of();
        }
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(principalName);
        candidates.add(USER_PREFIX + principalName);
        principalRepository.findByName(principalName).ifPresent(principal -> {
            for (PrincipalRoleAssignmentEntity assignment
                    : principalRoleAssignmentRepository.findByPrincipalId(principal.getId())) {
                principalRoleRepository.findById(assignment.getPrincipalRoleId())
                        .ifPresent(role -> candidates.add(ROLE_PREFIX + role.getName()));
            }
        });
        return List.copyOf(candidates);
    }

    /**
     * access 是否满足要求。
     *
     * <p>只有 {@code ALL} 是聚合值（官方定义：All accesses applicable to the resource），
     * 其余一律要求相等——包括 {@code ALTER} 不自动包含 {@code SELECT} 这类常见直觉。
     */
    private boolean grantsAccess(String granted, Access required) {
        if (granted == null) {
            return false;
        }
        return granted.equals(required.name()) || Access.ALL.name().equals(granted);
    }

    /**
     * 一条赋权的作用域是否覆盖目标资源。
     *
     * <p>见类注释的覆盖表。{@code namespace} 一律按「点号拼接后的路径」比较，
     * 因为落库的 {@code database_name} 是单段字符串（Paimon 的库名、Iceberg 的点号拼接名），
     * 而目标可能来自层级数组。逐段比会把 {@code ["accounting.tax"]} 与
     * {@code ["accounting","tax"]} 判成两个命名空间，于是用 Iceberg 协议面授出的权限
     * **永远不生效**，而页面上看起来授权是成功的。
     */
    private boolean covers(PermissionAssignmentEntity assignment, ResourceType targetType,
                           List<String> targetNamespace, String targetObject) {
        ResourceType scope = ResourceType.parse(assignment.getResourceType()).orElse(null);
        if (scope == null) {
            return false;
        }
        String scopeDatabase = assignment.getDatabaseName() == null ? "" : assignment.getDatabaseName();
        String scopeObject = assignment.getObjectName() == null ? "" : assignment.getObjectName();
        String targetDatabase = joinNamespace(targetNamespace);

        return switch (scope) {
            case CATALOG -> targetType == ResourceType.CATALOG;
            case CATALOG_ALL -> targetType != ResourceType.CATALOG;
            case DATABASE -> targetType != ResourceType.CATALOG
                    && samePath(scopeDatabase, targetDatabase)
                    && (targetType == ResourceType.DATABASE || targetObject == null);
            // 官方的两个 _ALL 都写成「后代作用域」，因此不含容器自身
            case DATABASE_ALL -> targetType != ResourceType.CATALOG
                    && targetType != ResourceType.DATABASE
                    && isPathPrefix(scopeDatabase, targetDatabase);
            case TABLE, VIEW, FUNCTION -> scope == targetType
                    && samePath(scopeDatabase, targetDatabase)
                    && targetObject != null
                    && targetObject.equalsIgnoreCase(scopeObject);
            // 列级赋权只在列级目标上生效：表级 SELECT 检查不放行，
            // 否则列白名单会被当成整表可读（见类注释）
            case COLUMN -> targetType == ResourceType.COLUMN
                    && samePath(scopeDatabase, targetDatabase)
                    && targetObject != null
                    && targetObject.equalsIgnoreCase(scopeObject);
        };
    }

    private boolean isExpired(PermissionAssignmentEntity assignment, long nowMillis) {
        return assignment.getExpireTime() != null && assignment.getExpireTime() <= nowMillis;
    }

    private boolean samePath(String left, String right) {
        return left.equals(right);
    }

    /** 路径前缀判定，边界必须落在点号上：{@code a} 是 {@code a.b} 的前缀，不是 {@code ab} 的。 */
    private boolean isPathPrefix(String prefix, String candidate) {
        if (prefix.isEmpty()) {
            return true;
        }
        return candidate.equals(prefix) || candidate.startsWith(prefix + NAMESPACE_SEPARATOR);
    }

    private String joinNamespace(List<String> namespace) {
        return namespace == null ? "" : String.join(NAMESPACE_SEPARATOR, namespace);
    }

    /** 生成 403 错误信息中的资源描述。 */
    private String describe(ResourceType resourceType, List<String> namespace, String objectName) {
        if (resourceType == ResourceType.CATALOG) {
            return "the catalog";
        }
        StringBuilder builder = new StringBuilder(resourceType.name());
        if (namespace != null && !namespace.isEmpty()) {
            builder.append(' ').append(String.join(".", namespace));
        }
        if (objectName != null && !objectName.isEmpty()) {
            builder.append('.').append(objectName);
        }
        return builder.toString();
    }

    /** {@code PermissionResource} → 命名空间层级。 */
    static List<String> namespaceOf(PermissionResource resource) {
        ResourceType type = ResourceType.parse(resource.type()).orElse(null);
        if (type == null || !type.needsDatabase() || resource.database() == null) {
            return List.of();
        }
        return List.of(resource.database());
    }

    /** {@code PermissionResource} → 对象名；catalog / 库级为 {@code null}。 */
    static String objectOf(PermissionResource resource) {
        return switch (resource.type() == null ? "" : resource.type()) {
            case "TABLE", "COLUMN" -> blankToNull(resource.table());
            case "VIEW" -> blankToNull(resource.view());
            case "FUNCTION" -> blankToNull(resource.function());
            default -> null;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private Optional<String> catalogIdOf(String catalogName) {
        // 直接查仓储而不是走 ManagementCatalogService：后者可能反向依赖本服务，
        // 而 catalog 名与主键的对应关系只是一次按 prefix 的查询
        return catalogRepository.findByPrefix(catalogName)
                .map(catalog -> catalog.getId());
    }
}
