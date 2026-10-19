package io.github.melin.lakehouse.rest.config;

import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * catalog API（{@code /v1/**}）端点 → 所需 access 的映射表。
 *
 * <p>取词表来自 Paimon REST Management API 的 12 项 access，资源类型用它的 8 个
 * {@code PermissionResource.type}。每个端点归到「在哪个资源上行使哪项 access」，
 * 交给 {@link io.github.melin.lakehouse.rest.service.AuthorizationService} 用
 * {@code permission_assignment} 判定——不再有 Polaris 的 64 项权限。
 *
 * <p><b>两类目标的区分。</b>官方把 {@code CATALOG} 与 {@code CATALOG_ALL}、
 * {@code DATABASE} 与 {@code DATABASE_ALL} 定义成不同作用域：
 * <ul>
 *   <li>「列 catalog 下的库」这类**集合操作**落在 {@code CATALOG_ALL} 上
 *       （官方表里 {@code LIST} 只出现在 {@code CATALOG_ALL}，不在 {@code CATALOG}）；</li>
 *   <li>「建库」落在 {@code CATALOG} 上（只有它允许 {@code CREATEDATABASE}）；</li>
 *   <li>「建表 / 建视图 / 建函数」落在 {@code DATABASE} 上（官方表里这三项只出现在
 *       {@code DATABASE} 与 {@code CATALOG_ALL}）。</li>
 * </ul>
 * 因此同一个「表集合」端点的读与写会落到两个不同的目标类型上，这是官方模型的形状，
 * 不是笔误。
 *
 * <p><b>为什么用路径而不是逐个控制器加注解。</b>
 * catalog API 有 60 个端点，逐处标注会有两处风险：漏标（静默放行）和标注与实际
 * 路径漂移。集中成一张表后，映射可以被单测逐条覆盖，也能在
 * 「已登记端点集合」与「实际端点集合」不一致时立刻暴露。
 *
 * <p><b>失败关闭。</b>授权开启时，落在 {@code /v1/**} 下、但既不在本表登记、
 * 也不属于 {@link #isPublic(String)} 的请求会被拒绝（403），而不是放行。
 *
 * <p><b>两个 deferred 端点。</b>{@code POST .../permissions/grant} 与
 * {@code .../revoke} 的目标资源在请求体里，路径上只有 {@code GRANT} 这一层含义，
 * 无法在这里算出作用域。它们被标记为 {@link Requirement#deferred()}，
 * 由 {@code PermissionController} 拿着解析后的 {@code PermissionResource}
 * 调用 {@code AuthorizationService#requireGrantOn} 完成判定——比固定在 catalog 级
 * 更精确（在单表上持有 {@code GRANT} 的人也能管那张表的赋权）。
 */
public final class CatalogAccessRules {

    /** catalog API 的路径前缀。 */
    private static final String V1 = "/v1/";

    /**
     * 一个端点的授权要求。
     *
     * @param catalog      catalog 名，即路径中的 {@code {prefix}}
     * @param resourceType 目标资源类型
     * @param namespace    多级命名空间；catalog 级为空列表
     * @param objectName   对象名；catalog / 命名空间级为 {@code null}
     * @param access       要求的 access；{@code null} 表示由控制器按请求体判定
     */
    public record Requirement(String catalog, ResourceType resourceType, List<String> namespace,
                              String objectName, Access access) {

        /** 是否交给控制器判定（请求体里才有目标资源）。 */
        public boolean deferred() {
            return access == null;
        }
    }

    private CatalogAccessRules() {
    }

    /**
     * 判断路径是否显式豁免授权。
     *
     * <p>只有两处：{@code GET /v1/config} 与 {@code POST /v1/oauth/tokens}。
     * 两者都属于同一类——**认证凭据还没换到，就必须可达**。
     * 其余端点一律要求授权。
     */
    public static boolean isPublic(String requestUri) {
        if (requestUri == null) {
            return false;
        }
        String path = stripTrailingSlash(requestUri);
        return "/v1/config".equals(path) || "/v1/oauth/tokens".equals(path);
    }

    /**
     * 解析端点所需的 access。
     *
     * @param requestUri 请求路径（不含查询串）
     * @param method     HTTP 方法
     * @return 授权要求；路径不是 catalog API 或不匹配任何已登记端点时为 {@code null}
     */
    public static Requirement resolve(String requestUri, String method) {
        if (requestUri == null || !requestUri.startsWith(V1)) {
            return null;
        }
        String remainder = stripTrailingSlash(requestUri.substring(V1.length()));
        if (remainder.isEmpty()) {
            return null;
        }
        String[] raw = remainder.split("/");
        List<String> segments = new ArrayList<>();
        for (int index = 1; index < raw.length; index++) {
            if (!raw[index].isEmpty()) {
                segments.add(decode(raw[index]));
            }
        }
        if (segments.isEmpty()) {
            // /v1/{prefix} 自身不是端点
            return null;
        }
        return dispatch(decode(raw[0]), segments, method == null ? "" : method.toUpperCase(Locale.ROOT));
    }

    private static Requirement dispatch(String catalog, List<String> path, String method) {
        boolean read = isRead(method);
        String head = path.get(0);

        // ---------------------------------------------------------- 以 catalog 为作用域的全局端点
        if ("tables".equals(head) || "views".equals(head) || "functions".equals(head)) {
            return global(catalog, path, method, read);
        }
        if ("permissions".equals(head)) {
            return permissions(catalog, path, read);
        }
        if ("namespaces".equals(head)) {
            return namespaces(catalog, path, method, read);
        }
        if (!"databases".equals(head)) {
            return null;
        }

        // ---------------------------------------------------------- 命名空间自身
        if (path.size() == 1) {
            return read
                    ? at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.LIST)
                    : at(catalog, ResourceType.CATALOG, List.of(), null, Access.CREATEDATABASE);
        }
        String database = path.get(1);
        List<String> namespace = List.of(database);
        if (path.size() == 2) {
            if (read) {
                return at(catalog, ResourceType.DATABASE, namespace, null, Access.DESCRIBE);
            }
            if ("DELETE".equals(method)) {
                return at(catalog, ResourceType.DATABASE, namespace, null, Access.DROP);
            }
            return at(catalog, ResourceType.DATABASE, namespace, null, Access.ALTER);
        }

        return switch (path.get(2)) {
            case "tables", "table-details", "register" -> tables(catalog, path, method, read);
            case "views", "view-details" -> views(catalog, path, method, read);
            case "semantic-views" -> semanticViews(catalog, path, method, read);
            default -> functions(catalog, path, method, read, namespace);
        };
    }

    /** {@code /v1/{prefix}/tables……} 这类不带命名空间的全局端点。 */
    private static Requirement global(String catalog, List<String> path, String method, boolean read) {
        String head = path.get(0);
        if (path.size() == 1) {
            // 跨库列举：作用域是 catalog 的后代集合，官方表里 LIST 只在 CATALOG_ALL 上
            return read ? at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.LIST) : null;
        }
        if (path.size() == 2 && "rename".equals(path.get(1))) {
            // 改名的源与目标都在请求体里，路径上按 catalog 后代集合的 ALTER 收敛
            return read ? null : at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.ALTER);
        }
        if (path.size() == 3 && "tables".equals(head) && "id".equals(path.get(1)) && read) {
            // 按 id 查表跨命名空间，只能按 catalog 后代集合判
            return at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.DESCRIBE);
        }
        return null;
    }

    /**
     * Paimon 官方权限端点：{@code /v1/{prefix}/permissions[/grant|/revoke]}。
     *
     * <p>列赋权要求调用者在 catalog 的后代集合上具备 {@code GRANT}；
     * grant/revoke 的目标资源在请求体里，标为 deferred 交给控制器精确判定。
     * GET 落在 grant/revoke 路径上返回 {@code null} → 拒绝，
     * 避免把写端点误当读端点探测。
     */
    private static Requirement permissions(String catalog, List<String> path, boolean read) {
        if (path.size() == 1) {
            return read
                    ? at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.GRANT)
                    : null;
        }
        if (path.size() == 2 && ("grant".equals(path.get(1)) || "revoke".equals(path.get(1))) && !read) {
            return new Requirement(catalog, ResourceType.CATALOG_ALL, List.of(), null, null);
        }
        return null;
    }

    /** {@code /v1/{prefix}/databases/{database}/tables……}，含 partitions / branches / tags / consumers 等子资源。 */
    private static Requirement tables(String catalog, List<String> path, String method, boolean read) {
        String database = path.get(1);
        List<String> namespace = List.of(database);
        String family = path.get(2);

        if (path.size() == 3) {
            if (family.equals("table-details")) {
                return read ? at(catalog, ResourceType.TABLE, namespace, null, Access.LIST) : null;
            }
            if (family.equals("register")) {
                // 登记一张已存在的数据目录，与建表同一档（都在库上）
                return read ? null : at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATETABLE);
            }
            return read
                    ? at(catalog, ResourceType.TABLE, namespace, null, Access.LIST)
                    : at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATETABLE);
        }

        String table = path.get(3);
        if (path.size() == 4) {
            if (read) {
                return at(catalog, ResourceType.TABLE, namespace, table, Access.DESCRIBE);
            }
            if ("DELETE".equals(method)) {
                return at(catalog, ResourceType.TABLE, namespace, table, Access.DROP);
            }
            return at(catalog, ResourceType.TABLE, namespace, table, Access.ALTER);
        }

        // 表以下还有一层子资源，语义仍以该表为对象
        String sub = path.get(4);
        Access required = switch (sub) {
            case "commit", "rollback" -> Access.UPDATE;
            case "rollback-schema" -> Access.ALTER;
            case "token", "auth" -> Access.SELECT;
            case "partitions" -> partitionAccess(path.size() > 5 ? path.get(5) : "", method, read);
            case "consumers" -> read ? Access.DESCRIBE : Access.ALTER;
            default -> null; // snapshot / snapshots / branches / tags
        };
        if (required == null) {
            if (sub.equals("branches") || sub.equals("tags") || sub.equals("snapshot") || sub.equals("snapshots")) {
                required = read ? Access.DESCRIBE : Access.ALTER;
            } else {
                return null;
            }
        }
        return at(catalog, ResourceType.TABLE, namespace, table, required);
    }

    /** 分区操作：读挂 {@code SELECT}，写挂 {@code UPDATE}。 */
    private static Access partitionAccess(String operation, String method, boolean read) {
        if (operation.startsWith("list") || operation.startsWith("mark") || read) {
            // list-by-* 只读；mark 只是打标记不改数据
            return Access.SELECT;
        }
        if ("GET".equals(method)) {
            return Access.SELECT;
        }
        return Access.UPDATE;
    }

    /** {@code /v1/{prefix}/databases/{database}/views……}。 */
    private static Requirement views(String catalog, List<String> path, String method, boolean read) {
        List<String> namespace = List.of(path.get(1));
        String family = path.get(2);
        if (path.size() == 3) {
            if (family.equals("view-details")) {
                return read ? at(catalog, ResourceType.VIEW, namespace, null, Access.LIST) : null;
            }
            return read
                    ? at(catalog, ResourceType.VIEW, namespace, null, Access.LIST)
                    : at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATEVIEW);
        }
        String view = path.get(3);
        if (read) {
            return at(catalog, ResourceType.VIEW, namespace, view, Access.DESCRIBE);
        }
        if ("DELETE".equals(method)) {
            return at(catalog, ResourceType.VIEW, namespace, view, Access.DROP);
        }
        return at(catalog, ResourceType.VIEW, namespace, view, Access.ALTER);
    }

    /**
     * {@code /v1/{prefix}/databases/{database}/semantic-views……}。
     *
     * <p>语义视图是视图的一种（同一套 DDL 语义、同一套读写路径），而官方 12 项
     * access 里没有单独的 semantic-model 层——原先为它单列的
     * {@code SEMANTIC_MODEL_*} 六项权限已随 Polaris 词表整体移除。
     * 因此这里映射到 {@code VIEW} 资源：授权口径与普通视图一致。
     */
    private static Requirement semanticViews(String catalog, List<String> path, String method,
                                             boolean read) {
        List<String> namespace = List.of(path.get(1));
        if (path.size() == 3) {
            return read
                    ? at(catalog, ResourceType.VIEW, namespace, null, Access.LIST)
                    : at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATEVIEW);
        }
        String view = path.get(3);
        if (read) {
            return at(catalog, ResourceType.VIEW, namespace, view, Access.DESCRIBE);
        }
        if ("DELETE".equals(method)) {
            return at(catalog, ResourceType.VIEW, namespace, view, Access.DROP);
        }
        return at(catalog, ResourceType.VIEW, namespace, view, Access.ALTER);
    }

    /**
     * function 端点。
     *
     * <p>官方词表里有独立的 {@code FUNCTION} 资源类型与 {@code CREATEFUNCTION}，
     * 因此这里按对象级判定：列举是库上的 {@code LIST}，读取是函数的
     * {@code DESCRIBE}，改是 {@code ALTER}，删是 {@code DROP}，
     * 建是库上的 {@code CREATEFUNCTION}。
     */
    private static Requirement functions(String catalog, List<String> path, String method,
                                         boolean read, List<String> namespace) {
        if (!"functions".equals(path.get(2)) && !"function-details".equals(path.get(2))) {
            return null;
        }
        if (path.size() == 3) {
            if (path.get(2).equals("function-details")) {
                return read ? at(catalog, ResourceType.FUNCTION, namespace, null, Access.LIST) : null;
            }
            return read
                    ? at(catalog, ResourceType.FUNCTION, namespace, null, Access.LIST)
                    : at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATEFUNCTION);
        }
        String function = path.get(3);
        if (read) {
            return at(catalog, ResourceType.FUNCTION, namespace, function, Access.DESCRIBE);
        }
        if ("DELETE".equals(method)) {
            return at(catalog, ResourceType.FUNCTION, namespace, function, Access.DROP);
        }
        return at(catalog, ResourceType.FUNCTION, namespace, function, Access.ALTER);
    }

    /**
     * Iceberg 的命名空间端点：{@code /v1/{prefix}/namespaces……}。
     *
     * <p>与 Paimon 的 {@code databases/**} 是**同一件事的两个名词**，因此映射同构。
     * 差别是 namespace 为多级，先按 {@link NamespacePaths} 切成层级再比。
     * {@code functions} 与 {@code register-view} 是一期不做的端点，不登记——
     * 失败关闭保证它们不会因为「忘了登记」而变成静默的越权入口。
     */
    private static Requirement namespaces(String catalog, List<String> path, String method, boolean read) {
        if (path.size() == 1) {
            return read
                    ? at(catalog, ResourceType.CATALOG_ALL, List.of(), null, Access.LIST)
                    : at(catalog, ResourceType.CATALOG, List.of(), null, Access.CREATEDATABASE);
        }
        List<String> namespace = NamespacePaths.parse(path.get(1));
        if (namespace.isEmpty()) {
            return null;
        }
        if (path.size() == 2) {
            if (read) {
                return at(catalog, ResourceType.DATABASE, namespace, null, Access.DESCRIBE);
            }
            return "DELETE".equals(method)
                    ? at(catalog, ResourceType.DATABASE, namespace, null, Access.DROP)
                    : null;
        }
        return switch (path.get(2)) {
            case "properties" -> "POST".equals(method)
                    ? at(catalog, ResourceType.DATABASE, namespace, null, Access.ALTER)
                    : null;
            case "tables" -> namespaceTables(catalog, namespace, path, method, read);
            case "views" -> namespaceViews(catalog, namespace, path, method, read);
            case "register" -> "POST".equals(method)
                    ? at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATETABLE)
                    : null;
            default -> null;
        };
    }

    /** {@code /v1/{prefix}/namespaces/{ns}/tables……}，含 metrics / credentials / unregister 子资源。 */
    private static Requirement namespaceTables(String catalog, List<String> namespace, List<String> path,
                                               String method, boolean read) {
        if (path.size() == 3) {
            return read
                    ? at(catalog, ResourceType.TABLE, namespace, null, Access.LIST)
                    : at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATETABLE);
        }
        String table = path.get(3);
        if (path.size() == 4) {
            if (read) {
                return at(catalog, ResourceType.TABLE, namespace, table, Access.DESCRIBE);
            }
            if ("DELETE".equals(method)) {
                return at(catalog, ResourceType.TABLE, namespace, table, Access.DROP);
            }
            return at(catalog, ResourceType.TABLE, namespace, table, Access.ALTER);
        }
        String sub = path.get(4);
        Access required = switch (sub) {
            // 上报统计属于「改这张表的数据状态」，与 Paimon 的 consumers 归到同一档
            case "metrics", "tasks" -> Access.UPDATE;
            // 凭据下发与 Paimon 的 token / auth 是同一件事
            case "credentials", "plan" -> Access.SELECT;
            case "unregister" -> Access.DROP;
            case "sign" -> Access.DESCRIBE;
            default -> null;
        };
        return required == null ? null : at(catalog, ResourceType.TABLE, namespace, table, required);
    }

    /** {@code /v1/{prefix}/namespaces/{ns}/views……}（Iceberg 形态的视图）。 */
    private static Requirement namespaceViews(String catalog, List<String> namespace, List<String> path,
                                              String method, boolean read) {
        if (path.size() == 3) {
            return read
                    ? at(catalog, ResourceType.VIEW, namespace, null, Access.LIST)
                    : at(catalog, ResourceType.DATABASE, namespace, null, Access.CREATEVIEW);
        }
        String view = path.get(3);
        if (read) {
            return at(catalog, ResourceType.VIEW, namespace, view, Access.DESCRIBE);
        }
        if ("DELETE".equals(method)) {
            return at(catalog, ResourceType.VIEW, namespace, view, Access.DROP);
        }
        return at(catalog, ResourceType.VIEW, namespace, view, Access.ALTER);
    }

    private static Requirement at(String catalog, ResourceType resourceType, List<String> namespace,
                                  String objectName, Access access) {
        return new Requirement(catalog, resourceType, namespace, objectName, access);
    }

    private static boolean isRead(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
    }

    private static String stripTrailingSlash(String path) {
        String result = path;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 路径段可能含 URL 编码（表名、标签名允许出现特殊字符），比较前统一解码。 */
    private static String decode(String segment) {
        return URLDecoder.decode(segment, StandardCharsets.UTF_8);
    }
}
