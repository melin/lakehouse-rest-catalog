package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.domain.entity.PermissionAssignmentEntity;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.GrantType;
import io.github.melin.lakehouse.rest.dto.Privilege;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Paimon 原生权限模型 → 本工程内部权限模型的映射。
 *
 * <p>本工程已有两条授权来源：Polaris RBAC 链（主体 → principal role → catalog role →
 * 资源授权）与 Paimon 原生的 principal 直授。判定层（{@link AuthorizationService}）
 * 最终只认内部权限（{@link Privilege}）与内部作用域（{@link GrantType}），
 * 因此直授条目在这里被「翻译」成内部形状再参与判定。
 *
 * <p><b>翻译规则来自官方文档的 Access/Resource 对照表</b>（management-api 的
 * Permission model 一节），方向是「官方 access 在本工程的授权判定里对应哪些内部权限」：
 * 内部判定把每个 catalog API 端点归到一项内部权限上（{@code CatalogAccessRules}），
 * 映射只需保证「持有官方 access 的调用者能通过对应端点的判定」。
 *
 * <p><b>principal 的匹配约定（官方把解析留给服务端）：</b>
 * 认证主体名 {@code alice} 匹配 {@code alice}（裸名）与 {@code user:alice}；
 * {@code role:<name>} 匹配主体在该 catalog 下持有的**同名 catalog role**——
 * 这是 Polaris 链路与原生模型唯一的交汇点：catalog role 的名字被当作
 * Paimon 语义下的 role principal 复用，管理员用一套角色命名就能同时服务两个模型。
 * {@code group:*} 没有可解析的成员关系，明确不匹配（宁可不生效，不能放大权限）。
 */
public final class PaimonPermissionModel {

    private PaimonPermissionModel() {
    }

    /** 一条直授翻译后的内部形状：作用域 + 命名空间 + 对象名 + 内部权限集合。 */
    public record MappedGrant(GrantType scope, List<String> namespace, String objectName,
                              Set<Privilege> privileges) {
    }

    /**
     * 把一条直授翻译成内部形状。
     *
     * <p>作用域的翻译规则：{@code CATALOG}/{@code CATALOG_ALL} → catalog 级
     * （内部判定里 catalog 级授权覆盖一切）；{@code DATABASE}/{@code DATABASE_ALL} →
     * namespace 级（内部判定里 namespace 授权按前缀覆盖自身与后代，与
     * {@code *_ALL} 的「显式作用域、含此后新建后代」语义一致）；TABLE/COLUMN →
     * 对象级；VIEW → view 对象级；FUNCTION → 所在 namespace（内部没有 function 层级，
     * {@code CatalogAccessRules} 也把 function 端点归到 namespace 权限上）。
     */
    public static MappedGrant map(PermissionAssignmentEntity entity) {
        String type = entity.getResourceType();
        String db = entity.getDatabaseName();
        String object = entity.getObjectName();
        Set<Privilege> privileges = privilegesOf(type, entity.getAccess());

        return switch (type) {
            case "CATALOG", "CATALOG_ALL" -> new MappedGrant(GrantType.CATALOG, List.of(), null, privileges);
            case "DATABASE", "DATABASE_ALL" -> new MappedGrant(GrantType.NAMESPACE, List.of(db), null, privileges);
            case "TABLE", "COLUMN" -> new MappedGrant(GrantType.TABLE, List.of(db), object, privileges);
            case "VIEW" -> new MappedGrant(GrantType.VIEW, List.of(db), object, privileges);
            case "FUNCTION" -> new MappedGrant(GrantType.NAMESPACE, List.of(db), null, privileges);
            default -> new MappedGrant(GrantType.NAMESPACE, List.of(db), null, Set.of());
        };
    }

    /** 单个 access → 内部权限集合。取值来自映射表的逐条对照，不做蕴含推断。 */
    private static Set<Privilege> privilegesOf(String resourceType, String access) {
        boolean catalogLevel = "CATALOG".equals(resourceType) || "CATALOG_ALL".equals(resourceType);
        EnumSet<Privilege> result = EnumSet.noneOf(Privilege.class);
        switch (access) {
            case "ALL" -> {
                // ALL = 该资源类型允许的全部 access 的并集（官方定义：All accesses applicable to the resource）。
                // 逐个 access 递归取并集，而不是另维护一份清单——对照表改了这里自动跟上。
                for (io.github.melin.lakehouse.rest.dto.PermissionDtos.Access item
                        : io.github.melin.lakehouse.rest.dto.PermissionDtos.Access.allowedFor(
                        io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType.valueOf(resourceType))) {
                    if (item != io.github.melin.lakehouse.rest.dto.PermissionDtos.Access.ALL) {
                        result.addAll(privilegesOf(resourceType, item.name()));
                    }
                }
            }
            case "SELECT" -> {
                if (catalogLevel) {
                    result.add(Privilege.TABLE_READ_DATA);
                    result.add(Privilege.VIEW_READ_PROPERTIES);
                } else if ("DATABASE_ALL".equals(resourceType)) {
                    result.add(Privilege.TABLE_READ_DATA);
                    result.add(Privilege.VIEW_READ_PROPERTIES);
                } else if ("TABLE".equals(resourceType) || "COLUMN".equals(resourceType)) {
                    result.add(Privilege.TABLE_READ_DATA);
                } else if ("VIEW".equals(resourceType)) {
                    result.add(Privilege.VIEW_READ_PROPERTIES);
                } else if ("FUNCTION".equals(resourceType)) {
                    result.add(Privilege.NAMESPACE_READ_PROPERTIES);
                } else {
                    // DATABASE：官方词表里 DATABASE 层没有 SELECT，进不来这里
                }
            }
            case "UPDATE" -> {
                // UPDATE = 写数据（insert / update / delete 一体），对应内部 TABLE_WRITE_DATA
                result.add(Privilege.TABLE_WRITE_DATA);
            }
            case "ALTER" -> {
                if (catalogLevel) {
                    result.add(Privilege.CATALOG_WRITE_PROPERTIES);
                } else if ("DATABASE".equals(resourceType) || "FUNCTION".equals(resourceType)) {
                    result.add(Privilege.NAMESPACE_WRITE_PROPERTIES);
                }
                if (!"FUNCTION".equals(resourceType)) {
                    // 对象与作用域层级的 ALTER 同时意味着改下层资源的元数据
                    result.add(Privilege.TABLE_WRITE_PROPERTIES);
                    result.add(Privilege.VIEW_WRITE_PROPERTIES);
                }
                if ("DATABASE".equals(resourceType) || catalogLevel) {
                    result.add(Privilege.NAMESPACE_WRITE_PROPERTIES);
                }
            }
            case "DROP" -> {
                if ("DATABASE".equals(resourceType) || catalogLevel) {
                    result.add(Privilege.NAMESPACE_DROP);
                }
                if (!"FUNCTION".equals(resourceType) && !"CATALOG".equals(resourceType)) {
                    result.add(Privilege.TABLE_DROP);
                    result.add(Privilege.VIEW_DROP);
                }
                // CATALOG 上的 DROP：内部没有 catalog 删除权限（删除 catalog 是服务管理员
                // 操作，不走 RBAC 判定），映射为空集——宁可不生效，不能虚构放大。
            }
            case "CREATETABLE" -> result.add(Privilege.TABLE_CREATE);
            case "CREATEVIEW" -> result.add(Privilege.VIEW_CREATE);
            case "CREATEFUNCTION" -> result.add(Privilege.NAMESPACE_WRITE_PROPERTIES);
            case "CREATEDATABASE" -> result.add(Privilege.NAMESPACE_CREATE);
            case "LIST" -> {
                result.add(Privilege.NAMESPACE_LIST);
                result.add(Privilege.TABLE_LIST);
                result.add(Privilege.VIEW_LIST);
            }
            case "DESCRIBE" -> {
                // DESCRIBE = 读元数据。catalog / database 级同时覆盖到表与视图的元数据读取
                result.add(Privilege.NAMESPACE_READ_PROPERTIES);
                result.add(Privilege.TABLE_READ_PROPERTIES);
                result.add(Privilege.VIEW_READ_PROPERTIES);
                if (catalogLevel) {
                    result.add(Privilege.CATALOG_READ_PROPERTIES);
                }
            }
            case "GRANT" -> result.add(Privilege.CATALOG_MANAGE_ACCESS);
            default -> {
                // 未知取值映射为空集：词表校验在写入侧已拦截，这里是纵深防御
            }
        }
        return result;
    }

    /**
     * 认证主体在指定 catalog 下的 principal 候选串。
     *
     * <p>裸名与 {@code user:} 前缀等价；{@code role:} 候选来自主体持有的 catalog role
     * 名（交汇点见类注释）。候选串用于仓储的 {@code principal IN (...)} 查询。
     */
    public static List<String> principalCandidates(String principalName, List<String> catalogRoleNames) {
        List<String> candidates = new ArrayList<>();
        if (principalName != null && !principalName.isBlank()) {
            candidates.add(principalName);
            candidates.add("user:" + principalName);
        }
        for (String roleName : catalogRoleNames) {
            candidates.add("role:" + roleName);
        }
        return candidates;
    }

    /** 赋权是否已过期。过期条目保留在列表里，但不再参与判定。 */
    public static boolean isExpired(PermissionAssignmentEntity entity, long nowMillis) {
        return entity.getExpireTime() != null && entity.getExpireTime() <= nowMillis;
    }
}
