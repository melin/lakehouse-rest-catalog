package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.PermissionAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.PermissionAssignmentRepository;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionAssignment;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionColumns;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionList;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionResource;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionScope;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionScopes;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.event.CatalogEvent;
import io.github.melin.lakehouse.rest.event.CatalogEventPublisher;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.PageMeta;
import io.github.melin.lakehouse.rest.support.Paging;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Paimon 官方 REST Management API 权限端点的实现：
 * {@code GET /v1/{prefix}/permissions}、{@code POST .../grant}、{@code POST .../revoke}。
 *
 * <p>三条官方语义，这里逐条落实：
 *
 * <ol>
 *   <li><b>赋权身份是三元组</b> {@code (resource, access, principal)}——
 *       重复授予覆盖 expireTime（官方：「Granting the same identity replaces its expiry」），
 *       列范围不属于身份、整体替换；</li>
 *   <li><b>撤销幂等</b>（官方：「revocation is idempotent」）——撤销不存在的赋权返回
 *       成功而不是 404，这与 Polaris 规格「撤销不存在返回 404」的行为刻意不同，
 *       两套 API 各守各的规格；</li>
 *   <li><b>access 必须属于资源类型</b>（官方 Access/Resource 对照表）——
 *       提交对照表之外的组合返回 400。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class PermissionManagementService {

    /** 列表默认与最大页大小。官方 Spark 过程的默认值是 50，HTTP 端点放宽到 100。 */
    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 1000;

    /** 官方对 principal 的长度上限。 */
    private static final int MAX_PRINCIPAL_LENGTH = 128;

    private final PermissionAssignmentRepository repository;
    private final CatalogRepository catalogRepository;
    private final CatalogEventPublisher events;

    /** {@code GET /v1/{prefix}/permissions}：按可选过滤条件列出直授，稳定排序后分页。 */
    @Transactional(readOnly = true)
    public PermissionList list(String catalogName, String resourceType, String database,
                               String table, String view, String function, String principal,
                               String access, Integer maxResults, String pageToken,
                               Integer page, Integer pageSize) {
        String catalogId = requireCatalogId(catalogName);
        List<PermissionAssignmentEntity> all = new ArrayList<>(
                repository.findByCatalogId(catalogId));
        all.removeIf(entity -> !matches(entity, resourceType, database, table, view, function,
                principal, access));
        // 分页要求顺序稳定：排序键即赋权身份三元组 + 定位符，同一批数据两次查询结果一致
        all.sort(Comparator
                .comparing(PermissionAssignmentEntity::getPrincipal)
                .thenComparing(PermissionAssignmentEntity::getResourceType)
                .thenComparing(PermissionAssignmentEntity::getDatabaseName)
                .thenComparing(PermissionAssignmentEntity::getObjectName)
                .thenComparing(PermissionAssignmentEntity::getAccess));
        // 控制台要页码与总数（el-pagination 的 current-page / total），
        // 规格里的游标翻页留给 Spark 过程这类「一路向前」的调用方。两者互斥。
        Paging.Paged<PermissionAssignmentEntity> paged = Paging.paged(all, page, pageSize);
        if (paged != null) {
            List<PermissionAssignment> pageAssignments = paged.items().stream()
                    .map(PermissionManagementService::toAssignment)
                    .toList();
            return new PermissionList(pageAssignments, null, paged.meta());
        }
        int size = Paging.pageSize(maxResults, DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE);
        Paging.Slice<PermissionAssignmentEntity> slice = Paging.slice(all, Paging.offset(pageToken), size);
        List<PermissionAssignment> assignments = slice.items().stream()
                .map(PermissionManagementService::toAssignment)
                .toList();
        return new PermissionList(assignments, slice.nextPageToken(), null);
    }

    /**
     * {@code GET /v1/{prefix}/permissions/scopes}：控制台授权清单的一页。
     *
     * <p><b>折叠在切页之前。</b>清单一行是「主体 × 作用范围」，由同范围的几条赋权
     * 折叠而成；先按赋权切页会把一组切到两页上，同一个主体在相邻两页各出现一次。
     * 因此先折叠、再过滤、再切页——`total` 也就是「有多少行」，与界面数的一致。
     *
     * <p>{@code keyword} 在服务端匹配「主体 + 作用范围」：客户端只有一页，
     * 在浏览器里筛只能筛出这一页里匹配的，与「全站匹配的」是两回事。
     */
    @Transactional(readOnly = true)
    public PermissionScopes scopes(String catalogName, String resourceType, String database,
                                   String table, String view, String function, String principal,
                                   String access, String keyword, Integer page, Integer pageSize) {
        String catalogId = requireCatalogId(catalogName);
        List<PermissionAssignmentEntity> all = new ArrayList<>(repository.findByCatalogId(catalogId));
        all.removeIf(entity -> !matches(entity, resourceType, database, table, view, function,
                principal, access));

        // 分组键：主体 + 资源类型 + 库 + 对象。用 LinkedHashMap 保持首次出现的顺序，
        // 排序在下面统一做（分页要求顺序稳定）。
        Map<String, List<PermissionAssignmentEntity>> grouped = new LinkedHashMap<>();
        for (PermissionAssignmentEntity entity : all) {
            String key = String.join("\n",
                    String.valueOf(entity.getPrincipal()),
                    String.valueOf(entity.getResourceType()),
                    String.valueOf(entity.getDatabaseName()),
                    String.valueOf(entity.getObjectName()));
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(entity);
        }

        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<PermissionScope> scopes = new ArrayList<>(grouped.size());
        for (List<PermissionAssignmentEntity> group : grouped.values()) {
            PermissionAssignmentEntity head = group.get(0);
            String scope = scopeOf(head);
            if (!needle.isEmpty()
                    && !(head.getPrincipal() + " " + scope).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            List<PermissionAssignment> accesses = new ArrayList<>(group.size());
            for (PermissionAssignmentEntity entity : group) {
                accesses.add(toAssignment(entity));
            }
            // 行内按 access 名排序：同一范围下的标签顺序不该随建表顺序变
            accesses.sort(Comparator.comparing(PermissionAssignment::access));
            scopes.add(new PermissionScope(head.getPrincipal(), head.getResourceType(),
                    head.getDatabaseName(), head.getObjectName(), scope, accesses));
        }
        scopes.sort(Comparator.comparing(PermissionScope::principal)
                .thenComparing(PermissionScope::scope));

        int size = Paging.pageSize(pageSize, Paging.DEFAULT_PAGE_SIZE, Paging.MAX_PAGE_SIZE);
        int current = Paging.page(page);
        Paging.Slice<PermissionScope> slice = Paging.slicePage(scopes, current, size);
        return new PermissionScopes(slice.items(), PageMeta.of(scopes.size(), current, size));
    }

    /** 界面上的「作用范围」文案；规则与前端曾经的那份逐字一致。 */
    private static String scopeOf(PermissionAssignmentEntity entity) {
        String type = entity.getResourceType();
        if ("CATALOG".equals(type) || "CATALOG_ALL".equals(type)) {
            return "整个 catalog";
        }
        String databaseName = entity.getDatabaseName();
        String objectName = entity.getObjectName();
        if (objectName == null || objectName.isBlank()) {
            return databaseName == null ? "" : databaseName;
        }
        return (databaseName == null || databaseName.isBlank() ? "" : databaseName + ".") + objectName;
    }

    /** {@code POST /v1/{prefix}/permissions/grant}。 */
    @Transactional
    public void grant(String catalogName, PermissionAssignment request) {
        String catalogId = requireCatalogId(catalogName);
        Parsed parsed = parse(request, true);
        PermissionAssignmentEntity entity = repository
                .findByCatalogIdAndResourceTypeAndLocatorHashAndAccessAndPrincipal(
                        catalogId, parsed.resourceType().name(), parsed.locatorHash(),
                        parsed.access().name(), parsed.principal())
                .orElseGet(() -> {
                    PermissionAssignmentEntity fresh = new PermissionAssignmentEntity();
                    fresh.setId(Paging.newId());
                    fresh.setCatalogId(catalogId);
                    fresh.setPrincipal(parsed.principal());
                    fresh.markCreated(RequestContext.principal(), RequestContext.now());
                    return fresh;
                });
        entity.setResourceType(parsed.resourceType().name());
        entity.setDatabaseName(parsed.database());
        entity.setObjectName(parsed.object());
        entity.setAccess(parsed.access().name());
        entity.setExpireTime(parsed.expireTime());
        entity.setColumnsJson(parsed.columnsJson());
        entity.refreshLocatorHash();
        entity.touch(RequestContext.principal(), RequestContext.now());
        repository.save(entity);
        events.publish(CatalogEventType.GRANT_GRANTED, catalogName, CatalogEvent.TARGET_GRANT,
                grantTarget(parsed), grantAttributes(parsed));
    }

    /**
     * {@code POST /v1/{prefix}/permissions/revoke}：幂等，不存在的赋权静默成功。
     *
     * <p>列范围不属于赋权身份，因此官方明确「撤销省略 columns、移除整条列赋权」——
     * revoke 侧不做 COLUMN 列范围校验。
     */
    @Transactional
    public void revoke(String catalogName, PermissionAssignment request) {
        String catalogId = requireCatalogId(catalogName);
        Parsed parsed = parse(request, false);
        repository.findByCatalogIdAndResourceTypeAndLocatorHashAndAccessAndPrincipal(
                        catalogId, parsed.resourceType().name(), parsed.locatorHash(),
                        parsed.access().name(), parsed.principal())
                .ifPresent(entity -> {
                    repository.delete(entity);
                    // 幂等的撤销「没找到也不报错」，但那种调用不进事件流：
                    // 事件流里没有空操作，消费方才敢按事件做增量
                    events.publish(CatalogEventType.GRANT_REVOKED, catalogName,
                            CatalogEvent.TARGET_GRANT, grantTarget(parsed),
                            grantAttributes(parsed));
                });
    }

    // ------------------------------------------------------------------ 解析与校验

    /**
     * 校验并拆解请求体。{@code expireTime} / {@code columns} 不属于赋权身份，
     * 单独放在结果里。
     *
     * @param requireColumns grant 为 true：COLUMN 赋权必须恰好给一个列范围；
     *                       revoke 为 false：省略 columns 合法（撤销按身份删整条）
     */
    private Parsed parse(PermissionAssignment request, boolean requireColumns) {
        if (request == null || request.resource() == null) {
            throw ApiException.badRequest("resource is required");
        }
        PermissionResource resource = request.resource();
        ResourceType type = ResourceType.parse(resource.type())
                .orElseThrow(() -> ApiException.badRequest("Unknown resource type: " + resource.type()));

        String database = orEmpty(resource.database());
        String object = switch (type) {
            case TABLE, COLUMN -> orEmpty(resource.table());
            case VIEW -> orEmpty(resource.view());
            case FUNCTION -> orEmpty(resource.function());
            default -> "";
        };
        if (type.needsDatabase() && database.isEmpty()) {
            throw ApiException.badRequest("resource.database is required for type " + type);
        }
        if (type.needsObject() && object.isEmpty()) {
            throw ApiException.badRequest("resource locator is required for type " + type);
        }
        // 反向也校验：定位符给了但不属于该类型，多半是调用方拼错了请求
        if (!type.needsObject() && !object.isEmpty()) {
            throw ApiException.badRequest("resource locator is not allowed for type " + type);
        }

        Access access = Access.parse(request.access())
                .orElseThrow(() -> ApiException.badRequest("Unknown access: " + request.access()));
        if (!Access.allowedFor(type).contains(access)) {
            throw ApiException.badRequest("Access " + access + " is not applicable to resource type " + type);
        }

        String principal = request.principal() == null ? "" : request.principal().trim();
        if (principal.isEmpty()) {
            throw ApiException.badRequest("principal is required");
        }
        if (principal.length() > MAX_PRINCIPAL_LENGTH) {
            throw ApiException.badRequest("principal must be at most " + MAX_PRINCIPAL_LENGTH + " characters");
        }

        Long expireTime = null;
        if (request.expireTime() != null && !request.expireTime().isBlank()) {
            try {
                expireTime = Instant.parse(request.expireTime()).toEpochMilli();
            } catch (DateTimeParseException ignored) {
                throw ApiException.badRequest("expireTime must be an ISO-8601 instant, e.g. 2027-01-01T00:00:00Z");
            }
        }

        String columnsJson = null;
        if (type == ResourceType.COLUMN) {
            PermissionColumns columns = request.columns();
            boolean hasAllow = columns != null && columns.columnNames() != null
                    && !columns.columnNames().isEmpty();
            boolean hasDeny = columns != null && columns.excludedColumnNames() != null
                    && !columns.excludedColumnNames().isEmpty();
            if (hasAllow && hasDeny) {
                // 两个范围互斥，同时给是调用方拼错请求
                throw ApiException.badRequest(
                        "columns.columnNames and columns.excludedColumnNames are mutually exclusive");
            }
            if (!hasAllow && !hasDeny) {
                if (requireColumns) {
                    // 官方「Exactly one non-empty list is allowed」；撤销侧不做此要求
                    throw ApiException.badRequest(
                            "COLUMN assignment requires exactly one non-empty of columns.columnNames / columns.excludedColumnNames");
                }
            } else {
                columnsJson = Json.write(columns);
            }
        } else if (request.columns() != null) {
            throw ApiException.badRequest("columns is only allowed for COLUMN assignments");
        }

        return new Parsed(type, database, object, access, principal, expireTime, columnsJson);
    }

    private record Parsed(ResourceType resourceType, String database, String object, Access access,
                          String principal, Long expireTime, String columnsJson) {

        String locatorHash() {
            return io.github.melin.lakehouse.rest.support.Digests.sha256Hex(database,
                    PermissionAssignmentEntity.LOCATOR_SEPARATOR, object);
        }
    }

    /** 事件的 target：资源定位符（catalog 级资源没有库表段，只有类型）。 */
    private static String grantTarget(Parsed parsed) {
        if (parsed.database().isEmpty() && parsed.object().isEmpty()) {
            return parsed.resourceType().name();
        }
        if (parsed.object().isEmpty()) {
            return parsed.resourceType().name() + " " + parsed.database();
        }
        return parsed.resourceType().name() + " " + parsed.database() + "." + parsed.object();
    }

    /**
     * 事件的开放属性：谁被授了什么、授在哪。
     *
     * <p>没有 {@code action} 键——授予还是撤销已经由事件类型（{@code grant.granted} /
     * {@code grant.revoked}）表达，属性里再写一遍就是同一事实的第二个来源。
     */
    private static Map<String, Object> grantAttributes(Parsed parsed) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("granteeType", "PRINCIPAL");
        attributes.put("grantee", parsed.principal());
        attributes.put("resourceType", parsed.resourceType().name());
        if (!parsed.database().isEmpty()) {
            attributes.put("database", parsed.database());
        }
        if (!parsed.object().isEmpty()) {
            attributes.put("object", parsed.object());
        }
        attributes.put("access", parsed.access().name());
        return attributes;
    }

    private static PermissionAssignment toAssignment(PermissionAssignmentEntity entity) {
        PermissionResource resource = switch (entity.getResourceType()) {
            case "CATALOG" -> new PermissionResource("CATALOG", null, null, null, null);
            case "CATALOG_ALL" -> new PermissionResource("CATALOG_ALL", null, null, null, null);
            case "DATABASE" -> new PermissionResource("DATABASE", entity.getDatabaseName(), null, null, null);
            case "DATABASE_ALL" -> new PermissionResource("DATABASE_ALL", entity.getDatabaseName(), null, null, null);
            case "TABLE" -> new PermissionResource("TABLE", entity.getDatabaseName(), entity.getObjectName(), null, null);
            case "COLUMN" -> new PermissionResource("COLUMN", entity.getDatabaseName(), entity.getObjectName(), null, null);
            case "VIEW" -> new PermissionResource("VIEW", entity.getDatabaseName(), null, entity.getObjectName(), null);
            case "FUNCTION" -> new PermissionResource("FUNCTION", entity.getDatabaseName(), null, null, entity.getObjectName());
            default -> new PermissionResource(entity.getResourceType(), entity.getDatabaseName(),
                    entity.getObjectName(), null, null);
        };
        PermissionColumns columns = entity.getColumnsJson() == null
                ? null
                : Json.read(entity.getColumnsJson(), PermissionColumns.class);
        String expireTime = entity.getExpireTime() == null
                ? null
                : Instant.ofEpochMilli(entity.getExpireTime()).toString();
        return new PermissionAssignment(resource, entity.getAccess(), entity.getPrincipal(),
                expireTime, columns);
    }

    /** 列表过滤：null / 空的过滤条件视为不过滤；定位符按提交时的字段各比各的。 */
    private boolean matches(PermissionAssignmentEntity entity, String resourceType, String database,
                            String table, String view, String function, String principal,
                            String access) {
        if (notBlank(resourceType) && !resourceType.equals(entity.getResourceType())) {
            return false;
        }
        if (notBlank(database) && !database.equals(entity.getDatabaseName())) {
            return false;
        }
        // 对象过滤：请求里给了哪个对象字段，就与实体的 objectName 比——
        // 官方 list_addresses 一个精确资源，这里宽松一档也允许只按 database 过滤
        String objectFilter = firstNonBlank(table, view, function);
        if (notBlank(objectFilter) && !objectFilter.equals(entity.getObjectName())) {
            return false;
        }
        if (notBlank(principal) && !principal.equals(entity.getPrincipal())) {
            return false;
        }
        return !notBlank(access) || access.equals(entity.getAccess());
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (notBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    /** catalog 不存在时按 404 处理：与 catalog API 其余端点「先 404 后 403」的口径一致。 */
    private String requireCatalogId(String catalogName) {
        Optional<io.github.melin.lakehouse.rest.domain.entity.CatalogEntity> catalog =
                catalogRepository.findByPrefix(catalogName);
        return catalog
                .map(io.github.melin.lakehouse.rest.domain.entity.CatalogEntity::getId)
                // support.ResourceType 用全限定名：本类同时引用官方词表的 ResourceType，
                // 简名 import 两个同名类型会撞名
                .orElseThrow(() -> new ApiException(404,
                        io.github.melin.lakehouse.rest.support.ResourceType.CATALOG, catalogName,
                        "The given catalog does not exist"));
    }
}
