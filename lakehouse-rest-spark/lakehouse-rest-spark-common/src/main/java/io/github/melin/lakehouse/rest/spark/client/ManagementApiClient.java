package io.github.melin.lakehouse.rest.spark.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.melin.lakehouse.rest.engine.auth.TokenSource;
import io.github.melin.lakehouse.rest.spark.lineage.LineageRun;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * LakeHouse Catalog Management API 的客户端。
 *
 * <p>只依赖 JDK 自带的 {@link HttpClient} 与 Spark 自带的 Jackson 2，不引入额外 HTTP 库：
 * 这个模块最终会被放进 Spark 的 classpath，额外依赖会与集群已有版本冲突。
 *
 * <p><b>路径与规格一致。</b>官方部分（三个 {@code permissions} 端点）取自
 * Paimon REST Management API 的 {@code spec/rest-management-open-api.yaml}；
 * catalog / principal / principal role 是服务端扩展，形状沿用已移除的 Polaris 规格
 * （快照在 {@code docs/management-api-contract.md}）。两侧不做任何形状转换，避免各自演化。
 *
 * <p><b>响应体有两套形状，极易读错。</b>规格对「单个资源」的响应**大多不套外壳**：
 *
 * <pre>
 * POST /principals                    -> PrincipalWithCredentials  {"principal":{…},"credentials":{…}}
 * POST /principals/{n}/reset|rotate   -> PrincipalWithCredentials  {"principal":{…},"credentials":{…}}
 * GET  /principals/{n}                -> Principal                 裸对象
 * PUT  /principals/{n}                -> Principal                 裸对象
 * POST /principal-roles               -> PrincipalRole             裸对象
 * GET  /principal-roles/{n}           -> PrincipalRole             裸对象
 * POST /catalogs/{c}/catalog-roles    -> CatalogRole               裸对象
 * GET  /catalogs/{c}/catalog-roles/{n}-> CatalogRole               裸对象
 * </pre>
 *
 * 只有主体相关的接口（创建 / 重置 / 轮换）把结果包在 {@code principal} + {@code credentials}
 * 之下，因为需要同时返回一次性明文密钥；其余单资源接口返回裸对象。列表接口则统一是
 * 具名数组：{@code {"catalogs":[…]}}、{@code {"principals":[…]}}、{@code {"roles":[…]}}、
 * {@code {"grants":[…]}}。
 *
 * <p>读错的后果不是报错而是**静默取到空值**：例如从 {@code GET /principals/{n}} 上多读一层
 * {@code principal}，拿到的 {@code entityVersion} 会变成 0，下一次 {@code PUT} 就会因为
 * 版本不符得到 409（{@code expected 0, actual 1}）。因此下面每个方法都标注了它依赖的
 * 响应形状。
 *
 * <p><b>ALTER 的读改写。</b>规格的 update 请求会把 {@code properties} 整体替换，
 * 而 SQL 的 {@code ALTER ... SET PROPERTIES} 语义是「增量设置、其余保留」。
 * 两者不一致，因此在 {@link #alterPrincipalProperties} 等方法里做一次
 * 「读取 → 合并 → 回写」，把替换语义包装成 SQL 用户预期的增量语义。
 *
 * <p><b>两个基址。</b>绝大多数端点挂在管理 API 下（{@code /api/management/v1}），
 * 但官方权限端点（{@code /v1/{prefix}/permissions}）挂在 catalog API 下。因此本类持有
 * 两个基址：管理基址由构造参数给出，catalog 基址由它推导（默认部署下成立），
 * 推不出来时要求显式配置——理由见 {@link #deriveCatalogBase}。
 *
 * <p><b>刻意不实现 {@link AutoCloseable}：</b>{@code HttpClient#close} 到 Java 21 才出现，
 * 而本模块按 Java 17 编译；{@code HttpClient} 自身持有守护线程，无需显式关闭。
 */
public final class ManagementApiClient {

    private static final String CATALOGS = "/catalogs";
    private static final String PRINCIPALS = "/principals";
    private static final String PRINCIPAL_ROLES = "/principal-roles";
    private static final String CATALOG_ROLES = "/catalog-roles";
    private static final String GRANTS = "/grants";

    /** 血缘上报端点（挂在管理 API 下：它是跨 catalog 的元数据，不是 catalog 上的操作，§6.1）。 */
    private static final String LINEAGE_RUNS = "/lineage/runs";

    /** 引擎的策略解析通道（{@code docs/row-filter-column-mask-design.md} §5.4）。 */
    private static final String POLICIES_RESOLVE = "/policies/resolve";

    /** 管理 API 基址的后缀，用来反推 catalog API 基址（见 {@link #deriveCatalogBase}）。 */
    private static final String MANAGEMENT_SUFFIX = "/api/management/v1";

    /** 认证头的名字。重放时要用它把旧值从复制的请求里剔掉，因此提成常量。 */
    private static final String AUTHORIZATION = "Authorization";

    /**
     * 权限端点的最大分页数。
     *
     * <p>本类没法直接读状态码或区分「服务端卡住了」，因此给一个够大但有界的上限，
     * 把「下一页令牌永远不为空」这种服务端缺陷变成一条明确的异常，而不是死循环。
     * 100 页按每页 1000 条算是 10 万条直授，远超单 catalog 的合理规模。
     */
    private static final int MAX_PERMISSION_PAGES = 100;

    /** 权限端点一次请求的页大小（服务端上限即 1000）。 */
    private static final int PERMISSION_PAGE_SIZE = 1000;

    private final String baseUrl;

    /**
     * 当前该用的令牌。
     *
     * <p><b>它原来是一个 {@code final String}。</b>升级成 {@link TokenSource} 之后，
     * 「取票」从「构造时定死」变成「每次请求问一次」，续期才有可能发生。
     * 治理通道的票与 catalog 的票是同一个主体的同一把钥匙，过期时间也一样——
     * 会话活过 TTL 之后，血缘上报与 {@code GRANT} 会一起 401，
     * 而血缘是 fail-open 的（异步、队列满即丢、异常吞掉），症状是
     * 「血缘图里作业运行一段时间之后的边消失了」，不是任何一条报错。
     *
     * <p>默认仍是 {@code TokenSource.fixed(token)}：未配置凭据时行为与改造前逐字节相同。
     */
    private final TokenSource tokenSource;

    private final Duration timeout;
    private final HttpClient http;
    private final ObjectMapper mapper;

    /**
     * 官方权限端点的基址：catalog API 的 {@code /v1}，与 {@code baseUrl}（管理 API）
     * 是**两个不同的前缀**——权限端点在官方规格里挂在 catalog API 下
     * （{@code /v1/{prefix}/permissions}），不在 {@code /api/management/v1} 下。
     */
    private final String permissionBaseUrl;

    /**
     * 只配管理 API 基址的构造：catalog API 基址由它推导（见 {@link #deriveCatalogBase}）。
     *
     * <p>两种情况推不出来，此时权限语句（{@code GRANT ... TO USER ...}）会在此构造下
     * 报一条指向配置项的错——原样保留这个重载，是因为既有调用点与测试都用它。
     */
    public ManagementApiClient(String baseUrl, String token, Duration timeout) {
        this(baseUrl, token, null, timeout);
    }

    /**
     * @param catalogBaseUrl catalog API 基址，须含 {@code /v1} 前缀（如
     *                       {@code http://host:8080/v1}）；留空则从 {@code baseUrl} 推导
     */
    public ManagementApiClient(String baseUrl, String token, String catalogBaseUrl, Duration timeout) {
        // 固定票：令牌不会再变，401 时 invalidate() 返回 false，因此也不会重放
        this(baseUrl, TokenSource.fixed(token), catalogBaseUrl, timeout);
    }

    /**
     * 带续期的构造。
     *
     * <p>刻意做成静态工厂而不是第二个 {@code (String, TokenSource, String, Duration)} 构造：
     * 既有调用点里有 {@code new ManagementApiClient(url, null, catalogUrl, timeout)} 这样的
     * 写法，多一个同参数个数的重载会让那个 {@code null} 变成二义，编译器报的是调用点，
     * 而不是这里。
     *
     * @param tokens 票来源；401 时会尝试 {@link TokenSource#invalidate()} 并重放一次
     */
    public static ManagementApiClient withTokenSource(String baseUrl, TokenSource tokens,
                                                      String catalogBaseUrl, Duration timeout) {
        return new ManagementApiClient(baseUrl, tokens, catalogBaseUrl, timeout);
    }

    private ManagementApiClient(String baseUrl, TokenSource tokens, String catalogBaseUrl,
                                Duration timeout) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.tokenSource = tokens == null ? TokenSource.fixed(null) : tokens;
        this.timeout = timeout == null ? Duration.ofSeconds(30) : timeout;
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.mapper = new ObjectMapper();
        String configured = catalogBaseUrl == null || catalogBaseUrl.isBlank()
                ? null : stripTrailingSlash(catalogBaseUrl);
        this.permissionBaseUrl = configured != null ? configured : deriveCatalogBase(this.baseUrl);
    }

    /**
     * 从管理 API 基址反推 catalog API 基址。
     *
     * <p>两者在同一台服务的固定路径上：管理面是 {@code /api/management/v1}，数据面是
     * {@code /v1}。因此把前者换成后者即可——默认配置（{@code http://127.0.0.1:8080}）
     * 下不需要用户配第二个地址。
     *
     * <p>基址被部署在非标准路径下时推不出来（后缀对不上），此时返回 {@code null}，
     * 由使用者显式配 {@code spark.lakehouse.rest.catalog.url}；报错比猜一个错的地址好。
     */
    private static String deriveCatalogBase(String managementBaseUrl) {
        if (managementBaseUrl == null || managementBaseUrl.isEmpty()) {
            return null;
        }
        if (!managementBaseUrl.endsWith(MANAGEMENT_SUFFIX)) {
            return null;
        }
        String root = managementBaseUrl.substring(0, managementBaseUrl.length() - MANAGEMENT_SUFFIX.length());
        return root + "/v1";
    }

    // ------------------------------------------------------------------ catalog

    /** {@code GET /catalogs}。 */
    public List<CatalogInfo> listCatalogs() {
        JsonNode root = get(CATALOGS);
        List<CatalogInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("catalogs")) {
            result.add(new CatalogInfo(text(node, "name"), text(node, "type"),
                    properties(node), integer(node, "entityVersion")));
        }
        return result;
    }

    // ------------------------------------------------------------------ lineage

    /**
     * {@code POST /lineage/runs}：批量上报血缘（§6.2）。
     *
     * <p>报文形状就是 {@link LineageRun} 本身——服务端的 {@code RunReport} 与它一一对应，
     * 因此这里不做任何转换。{@code 200} 与 {@code 200 + deduplicated} 都算成功：
     * 重复上报（重试）本该是幂等的，客户端不需要区分「这条是新入库的还是命中了幂等键」。
     *
     * <p>调用方是 {@link io.github.melin.lakehouse.rest.spark.lineage.LineageReporter}
     * 的后台线程，不在 driver 的关键路径上；超时由构造时的 {@code timeout} 决定。
     *
     * @param runs 一批 run；调用方保证不超过服务端的 {@code max-runs-per-batch}
     */
    public void ingestLineage(List<LineageRun> runs) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode array = body.putArray("runs");
        for (LineageRun run : runs) {
            array.add(mapper.valueToTree(run));
        }
        post(LINEAGE_RUNS, body, 200);
    }

    // ------------------------------------------------------------------ principal

    /** {@code GET /principals}。 */
    public List<PrincipalInfo> listPrincipals() {
        JsonNode root = get(PRINCIPALS);
        List<PrincipalInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("principals")) {
            result.add(principalInfo(node));
        }
        return result;
    }

    /** {@code GET /principals/{name}}：响应是裸 {@code Principal}。 */
    public PrincipalInfo getPrincipal(String name) {
        return principalInfo(get(PRINCIPALS + "/" + encode(name)));
    }

    /**
     * {@code POST /principals}。
     *
     * @return 含明文密钥的凭据；密钥只在此处出现一次，服务端不保存明文
     */
    public Credentials createPrincipal(String name, Map<String, String> properties) {
        ObjectNode principal = mapper.createObjectNode();
        principal.put("name", name);
        if (properties != null && !properties.isEmpty()) {
            principal.set("properties", mapper.valueToTree(properties));
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("principal", principal);
        JsonNode response = post(PRINCIPALS, body, 201);
        return credentials(response);
    }

    /**
     * {@code POST /principals/{name}/reset} 与 {@code /rotate}：重置或轮换凭据。
     *
     * <p>注意这两条是规格里少见的**成功返回 200 而非 201** 的 POST 端点
     * （它们的语义是「更新凭据」而不是「创建资源」），响应体与创建一样是
     * {@code PrincipalWithCredentials}。
     */
    public Credentials resetPrincipal(String name, boolean rotate) {
        ObjectNode body = mapper.createObjectNode();
        JsonNode response = post(PRINCIPALS + "/" + encode(name) + (rotate ? "/rotate" : "/reset"),
                body, 200);
        return credentials(response);
    }

    /**
     * {@code PUT /principals/{name}} 的增量包装：先读当前 {@code entityVersion} 与属性，
     * 合并后再回写。
     *
     * @param toSet   要设置或覆盖的属性
     * @param toUnset 要移除的属性名
     */
    public PrincipalInfo alterPrincipalProperties(String name, Map<String, String> toSet,
                                                   Set<String> toUnset) {
        // GET 与 PUT 的响应都是裸 Principal，不是 {"principal":{…}}
        JsonNode current = get(PRINCIPALS + "/" + encode(name));
        Map<String, String> merged = merge(properties(current), toSet, toUnset);
        ObjectNode body = mapper.createObjectNode();
        body.put("currentEntityVersion", current.path("entityVersion").asInt());
        body.set("properties", mapper.valueToTree(merged));
        return principalInfo(put(PRINCIPALS + "/" + encode(name), body));
    }

    /** {@code DELETE /principals/{name}}。 */
    public void dropPrincipal(String name) {
        delete(PRINCIPALS + "/" + encode(name));
    }

    /** {@code GET /principals/{name}/principal-roles}。 */
    public List<String> listPrincipalRolesOf(String principal) {
        JsonNode root = get(PRINCIPALS + "/" + encode(principal) + PRINCIPAL_ROLES);
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    /** {@code PUT /principals/{name}/principal-roles}：把 principal role 授予主体。 */
    public void grantPrincipalRole(String principal, String principalRole) {
        put(PRINCIPALS + "/" + encode(principal) + PRINCIPAL_ROLES,
                roleBody("principalRole", principalRole), 201);
    }

    /** {@code DELETE /principals/{name}/principal-roles/{roleName}}。 */
    public void revokePrincipalRole(String principal, String principalRole) {
        delete(PRINCIPALS + "/" + encode(principal) + PRINCIPAL_ROLES + "/" + encode(principalRole));
    }

    // ------------------------------------------------------------------ principal role

    /** {@code GET /principal-roles}。 */
    public List<RoleInfo> listPrincipalRoles() {
        JsonNode root = get(PRINCIPAL_ROLES);
        List<RoleInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(roleInfo(node));
        }
        return result;
    }

    /** {@code POST /principal-roles}：响应是裸 {@code PrincipalRole}。 */
    public RoleInfo createPrincipalRole(String name, Map<String, String> properties) {
        ObjectNode role = mapper.createObjectNode();
        role.put("name", name);
        if (properties != null && !properties.isEmpty()) {
            role.set("properties", mapper.valueToTree(properties));
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("principalRole", role);
        return roleInfo(post(PRINCIPAL_ROLES, body, 201));
    }

    /** {@code GET /principal-roles/{name}}：响应是裸 {@code PrincipalRole}。 */
    public RoleInfo getPrincipalRole(String name) {
        return roleInfo(get(PRINCIPAL_ROLES + "/" + encode(name)));
    }

    /** {@code PUT /principal-roles/{name}} 的增量包装。GET 与 PUT 的响应都是裸 {@code PrincipalRole}。 */
    public RoleInfo alterPrincipalRoleProperties(String name, Map<String, String> toSet,
                                                 Set<String> toUnset) {
        JsonNode role = get(PRINCIPAL_ROLES + "/" + encode(name));
        ObjectNode body = mapper.createObjectNode();
        body.put("currentEntityVersion", role.path("entityVersion").asInt());
        body.set("properties", mapper.valueToTree(
                merge(properties(role), toSet, toUnset)));
        return roleInfo(put(PRINCIPAL_ROLES + "/" + encode(name), body));
    }

    /** {@code DELETE /principal-roles/{name}}。 */
    public void dropPrincipalRole(String name) {
        delete(PRINCIPAL_ROLES + "/" + encode(name));
    }

    /** {@code GET /principal-roles/{name}/principals}。 */
    public List<String> listPrincipalsOf(String principalRole) {
        JsonNode root = get(PRINCIPAL_ROLES + "/" + encode(principalRole) + PRINCIPALS);
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("principals")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    // ------------------------------------------------------------------ catalog role
    /*
     * 这一组方法保留完整的管理 API 覆盖面，但**SQL 层已不再暴露它们**：
     * catalog role 的建档 / 装配 / 清点语句已从 ManagementSql.g4 中移除（控制台也没有页面），
     * 它只剩管理 API 一条路。留在这里有两个用处：一是本类按规格实现，缺一组方法会让
     * 「客户端 == 管理 API」这个前提不成立；二是权限端点的装配测试需要一个类型化的入口
     * 去建角色脚手架（`role:<名称>` 这种授权对象要生效，主体必须持有同名 catalog role）。
     */

    /** {@code GET /catalogs/{catalog}/catalog-roles}。 */
    public List<RoleInfo> listCatalogRoles(String catalog) {
        JsonNode root = get(catalogPath(catalog) + CATALOG_ROLES);
        List<RoleInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(roleInfo(node));
        }
        return result;
    }

    /** {@code POST /catalogs/{catalog}/catalog-roles}：响应是裸 {@code CatalogRole}。 */
    public RoleInfo createCatalogRole(String catalog, String name, Map<String, String> properties) {
        ObjectNode role = mapper.createObjectNode();
        role.put("name", name);
        if (properties != null && !properties.isEmpty()) {
            role.set("properties", mapper.valueToTree(properties));
        }
        ObjectNode body = mapper.createObjectNode();
        body.set("catalogRole", role);
        return roleInfo(post(catalogPath(catalog) + CATALOG_ROLES, body, 201));
    }

    /** {@code GET /catalogs/{catalog}/catalog-roles/{name}}：响应是裸 {@code CatalogRole}。 */
    public RoleInfo getCatalogRole(String catalog, String name) {
        return roleInfo(get(catalogRolePath(catalog, name)));
    }

    /** {@code PUT /catalogs/{catalog}/catalog-roles/{name}} 的增量包装。GET 与 PUT 的响应都是裸 {@code CatalogRole}。 */
    public RoleInfo alterCatalogRoleProperties(String catalog, String name,
                                               Map<String, String> toSet, Set<String> toUnset) {
        JsonNode role = get(catalogRolePath(catalog, name));
        ObjectNode body = mapper.createObjectNode();
        body.put("currentEntityVersion", role.path("entityVersion").asInt());
        body.set("properties", mapper.valueToTree(merge(properties(role), toSet, toUnset)));
        return roleInfo(put(catalogRolePath(catalog, name), body));
    }

    /** {@code DELETE /catalogs/{catalog}/catalog-roles/{name}}。 */
    public void dropCatalogRole(String catalog, String name) {
        delete(catalogRolePath(catalog, name));
    }

    /** {@code GET /principal-roles/{principalRole}/catalog-roles/{catalog}}。 */
    public List<String> listCatalogRolesOf(String principalRole, String catalog) {
        JsonNode root = get(PRINCIPAL_ROLES + "/" + encode(principalRole)
                + CATALOG_ROLES + "/" + encode(catalog));
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    /** {@code PUT /principal-roles/{principalRole}/catalog-roles/{catalog}}。 */
    public void grantCatalogRole(String principalRole, String catalog, String catalogRole) {
        put(PRINCIPAL_ROLES + "/" + encode(principalRole) + CATALOG_ROLES + "/" + encode(catalog),
                roleBody("catalogRole", catalogRole), 201);
    }

    /** {@code DELETE /principal-roles/{principalRole}/catalog-roles/{catalog}/{catalogRole}}。 */
    public void revokeCatalogRole(String principalRole, String catalog, String catalogRole) {
        delete(PRINCIPAL_ROLES + "/" + encode(principalRole) + CATALOG_ROLES + "/"
                + encode(catalog) + "/" + encode(catalogRole));
    }

    /** {@code GET /catalogs/{catalog}/catalog-roles/{name}/principal-roles}。 */
    public List<String> listPrincipalRolesOfCatalogRole(String catalog, String catalogRole) {
        JsonNode root = get(catalogRolePath(catalog, catalogRole) + PRINCIPAL_ROLES);
        List<String> result = new ArrayList<>();
        for (JsonNode node : root.path("roles")) {
            result.add(text(node, "name"));
        }
        return result;
    }

    // ------------------------------------------------------------------ Polaris 授权（只读）

    /**
     * {@code GET /catalogs/{catalog}/catalog-roles/{name}/grants}。
     *
     * <p>这条 RBAC 链的**写侧从来没有进入 SQL 与控制台**，读侧唯一的 SQL 入口
     * （`SHOW GRANTS FOR CATALOG ROLE`）也随 catalog role 语句一并移除，因此本方法
     * 眼下只由测试使用——清点那些仍在判定里生效、却没有任何界面能看到的存量授权。
     * 要写授权，请用 {@link #grantPermission}。
     */
    public List<GrantInfo> listGrants(String catalog, String catalogRole) {
        JsonNode root = get(catalogRolePath(catalog, catalogRole) + GRANTS);
        List<GrantInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("grants")) {
            result.add(grantInfo(node));
        }
        return result;
    }


    // ------------------------------------------------------------------ 直授（官方权限模型）

    /*
     * 下面是 Paimon 官方 REST Management API 的权限端点，与上面的 Polaris 授权是
     * **两套并行模型**：Polaris 把授权挂在 catalog role 上，官方模型把授权直接挂在
     * principal 不透明字符串（`user:alice`）上。路径也不同——权限端点在 catalog API 下
     * （`/v1/{prefix}/permissions`），因此走 permissionBaseUrl。
     *
     * 三条官方语义由服务端落实，客户端不做任何补偿：赋权身份是 (resource, access,
     * principal) 三元组；重复授予覆盖过期时间；撤销幂等。
     */

    /** {@code POST /v1/{prefix}/permissions/grant}：授予一项权限。 */
    public void grantPermission(String catalog, PermissionResource resource, String access,
                                String principal, String expireTime) {
        postAt(permissionPath(catalog) + "/grant",
                permissionBody(resource, access, principal, expireTime), 201);
    }

    /**
     * {@code POST /v1/{prefix}/permissions/revoke}：撤销一项权限。
     *
     * <p>不带 {@code expireTime} 与列范围：两者都不属于赋权身份，服务端按身份删整条。
     */
    public void revokePermission(String catalog, PermissionResource resource, String access,
                                 String principal) {
        postAt(permissionPath(catalog) + "/revoke",
                permissionBody(resource, access, principal, null), 201);
    }

    /**
     * {@code GET /v1/{prefix}/permissions}。
     *
     * <p>服务端分页（默认 100、上限 1000），这里跟随 {@code nextPageToken} 取完为止——
     * 一条 {@code SHOW PERMISSIONS} 的语义是「列出全部」，静默截断比多几次请求糟糕得多。
     *
     * @param principal 按主体过滤；{@code null} 表示不过滤
     */
    public List<PermissionEntry> listPermissions(String catalog, String principal) {
        List<PermissionEntry> result = new ArrayList<>();
        String pageToken = null;
        for (int page = 0; page < MAX_PERMISSION_PAGES; page++) {
            StringBuilder path = new StringBuilder(permissionPath(catalog))
                    .append("?maxResults=").append(PERMISSION_PAGE_SIZE);
            if (principal != null && !principal.isBlank()) {
                path.append("&principal=").append(encode(principal));
            }
            if (pageToken != null) {
                path.append("&pageToken=").append(encode(pageToken));
            }
            JsonNode root = getAt(path.toString());
            for (JsonNode node : root.path("assignments")) {
                result.add(permissionEntry(node));
            }
            pageToken = text(root, "nextPageToken");
            if (pageToken == null || pageToken.isBlank()) {
                return result;
            }
        }
        throw new ManagementApiException(0, "GET", "/permissions",
                "the permissions endpoint kept returning a page token for " + MAX_PERMISSION_PAGES
                        + " pages; aborting to avoid an endless loop", null, null);
    }

    /** 权限端点的路径：{@code /{prefix}/permissions}。 */
    private String permissionPath(String catalog) {
        return "/" + encode(catalog) + "/permissions";
    }

    private ObjectNode permissionBody(PermissionResource resource, String access, String principal,
                                      String expireTime) {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode node = mapper.createObjectNode();
        node.put("type", resource.type());
        if (resource.database() != null) {
            node.put("database", resource.database());
        }
        String field = switch (resource.type()) {
            case "TABLE", "COLUMN" -> "table";
            case "VIEW" -> "view";
            case "FUNCTION" -> "function";
            default -> null;
        };
        if (field != null) {
            node.put(field, resource.objectName());
        }
        body.set("resource", node);
        body.put("access", access);
        body.put("principal", principal);
        if (expireTime != null && !expireTime.isBlank()) {
            body.put("expireTime", expireTime);
        }
        if (resource.hasColumnScope()) {
            // 官方要求白名单与黑名单恰好一个非空，不做本地校验：写错了由服务端返回 400
            ObjectNode columns = mapper.createObjectNode();
            String listField = resource.columns().isEmpty() ? "excludedColumnNames" : "columnNames";
            ArrayNode array = columns.putArray(listField);
            List<String> names = resource.columns().isEmpty() ? resource.excludedColumns()
                    : resource.columns();
            names.forEach(array::add);
            body.set("columns", columns);
        }
        return body;
    }

    private PermissionEntry permissionEntry(JsonNode node) {
        JsonNode resource = node.path("resource");
        String type = text(resource, "type");
        String database = text(resource, "database");
        String objectName = switch (type == null ? "" : type) {
            case "TABLE", "COLUMN" -> text(resource, "table");
            case "VIEW" -> text(resource, "view");
            case "FUNCTION" -> text(resource, "function");
            default -> null;
        };
        List<String> columns = strings(node.path("columns").path("columnNames"));
        List<String> excluded = strings(node.path("columns").path("excludedColumnNames"));
        return new PermissionEntry(
                new PermissionResource(type, database, objectName, columns, excluded),
                text(node, "access"), text(node, "principal"), text(node, "expireTime"));
    }

    private static List<String> strings(JsonNode array) {
        List<String> result = new ArrayList<>();
        for (JsonNode element : array) {
            result.add(element.asText());
        }
        return result;
    }

    // ------------------------------------------------------------------ data policy

    /**
     * {@code GET /policies/resolve?catalog=&tables=db.t1,db.t2}：引擎的策略解析通道（§5.4）。
     *
     * <p>响应按调用者个性化：{@code $principal} 已被服务端折成字面量、豁免已判定，
     * 引擎拿到什么就执行什么，没有第二层解释。{@code status} 三值：
     * {@code NONE}（没策略或已停用）、{@code FOUND}（命中）、{@code EXEMPT}（豁免）。
     * 服务端错误（拉不到）不在返回值里——那是 5xx，由调用方按 fail-closed 处理。
     *
     * <p>一次请求问一批表：一条 SQL 常常扫好几张表，逐表请求会在分析路径上放大延迟。
     *
     * @param catalog catalog 名（与服务端 prefix 同名）
     * @param tables  {@code db.table} 两段名的清单；空清单返回空列表（不发请求）
     */
    public List<ResolvedPolicyInfo> resolvePolicies(String catalog, List<String> tables) {
        if (tables == null || tables.isEmpty()) {
            return List.of();
        }
        String path = POLICIES_RESOLVE + "?catalog=" + encode(catalog)
                + "&tables=" + encode(String.join(",", tables));
        JsonNode root = get(path);
        List<ResolvedPolicyInfo> result = new ArrayList<>();
        for (JsonNode node : root.path("policies")) {
            JsonNode rowFilter = node.get("rowFilter");
            List<MaskBindingInfo> masks = new ArrayList<>();
            for (JsonNode mask : node.path("masks")) {
                masks.add(new MaskBindingInfo(text(mask, "column"), asMap(mask.get("mask")),
                        mask.path("allowNullOnRequired").asBoolean(false)));
            }
            result.add(new ResolvedPolicyInfo(text(node, "table"), text(node, "status"),
                    node.path("version").asLong(), asMap(rowFilter), masks,
                    strings(node.path("hiddenColumns")),
                    node.path("exempted").asBoolean(false), text(node, "enforcedScope")));
        }
        return result;
    }

    /** 一个 JSON 节点转 {@code Map<String,Object>}；null / 缺失时返回 {@code null}。 */
    private Map<String, Object> asMap(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        return mapper.convertValue(node,
                mapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
    }

    // ------------------------------------------------------------------ data policy（管理语句 §13.2）

    private static String policyPath(String catalog, String database, String table) {
        // /policies/tables/... —— 不是 /policies/resolve/tables/...，resolve 是引擎下发通道
        return POLICIES_RESOLVE.replaceAll("/resolve$", "") + "/tables/" + encode(catalog)
                + "/" + encode(database) + "/" + encode(table);
    }

    /**
     * {@code GET /policies/tables/{catalog}/{database}/{table}}：一张表的策略详情。
     *
     * @return 表上没配过策略时为 {@code null}（端点回 204，与「没权限」区分开）
     */
    public PolicyDetailInfo getPolicy(String catalog, String database, String table) {
        String path = policyPath(catalog, database, table);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        HttpResponse<String> response = sendAllowNoContent(builder, path);
        if (response.statusCode() == 204) {
            return null;
        }
        return parsePolicyDetail(response.body());
    }

    /**
     * {@code PUT /policies/tables/{catalog}/{database}/{table}}：创建或整份更新（§13.2 的
     * 「GET → 修改 → PUT」）。{@code baseVersion} 首次创建传 {@code null}，更新必须带上次
     * 读到的版本——两个会话同时改，后提交者 409，SQL 侧不自动重试（§13.6）。
     *
     * @param enabled        {@code null} = 服务端默认（首次创建启用）；更新时必须带上一次读到的值
     * @param rowFilter      行过滤 AST；{@code null} = 不过滤行
     * @param masks          列 mask 清单（<b>整份替换</b>：清单里没有的列会被删掉）
     * @param exemptPrincipals 豁免名单（整份替换）
     * @param subjects       主体白名单（{@code FOR} 子句；空 = 全部主体）
     */
    public PolicyDetailInfo putPolicy(String catalog, String database, String table,
                                      Integer baseVersion, Boolean enabled,
                                      Map<String, Object> rowFilter,
                                      List<MaskUpsertInfo> masks,
                                      List<String> hiddenColumns,
                                      List<String> exemptPrincipals, List<String> subjects) {
        String path = policyPath(catalog, database, table);
        ObjectNode body = mapper.createObjectNode();
        if (baseVersion != null) {
            body.put("baseVersion", baseVersion);
        }
        if (enabled != null) {
            body.put("enabled", enabled);
        }
        body.set("rowFilter", rowFilter == null ? null : mapper.valueToTree(rowFilter));
        ArrayNode maskArray = body.putArray("masks");
        for (MaskUpsertInfo mask : masks) {
            ObjectNode node = maskArray.addObject();
            node.put("column", mask.column());
            node.set("mask", mapper.valueToTree(mask.mask()));
            if (mask.allowNullOnRequired() != null) {
                node.put("allowNullOnRequired", mask.allowNullOnRequired());
            }
        }
        if (hiddenColumns != null) {
            body.putArray("hiddenColumns").addAll((ArrayNode) mapper.valueToTree(hiddenColumns));
        }
        if (exemptPrincipals != null) {
            body.putArray("exemptPrincipals").addAll((ArrayNode) mapper.valueToTree(exemptPrincipals));
        }
        if (subjects != null) {
            body.putArray("subjects").addAll((ArrayNode) mapper.valueToTree(subjects));
        }
        JsonNode root = put(path, body);
        return parsePolicyDetail(root.toString());
    }

    /** {@code DELETE /policies/tables/{catalog}/{database}/{table}}：物理删除整条策略（§13.3）。 */
    public void deletePolicy(String catalog, String database, String table) {
        delete(policyPath(catalog, database, table));
    }

    private PolicyDetailInfo parsePolicyDetail(String body) {
        try {
            JsonNode node = mapper.readTree(body);
            List<MaskEntryInfo> masks = new ArrayList<>();
            for (JsonNode mask : node.path("masks")) {
                masks.add(new MaskEntryInfo(text(mask, "column"), asMap(mask.get("mask")),
                        text(mask, "sql"), mask.path("allowNullOnRequired").asBoolean(false)));
            }
            return new PolicyDetailInfo(text(node, "table"), node.path("enabled").asBoolean(true),
                    asMap(node.get("rowFilter")), text(node, "rowFilterSql"), masks,
                    strings(node.path("hiddenColumns")),
                    strings(node.path("exemptPrincipals")), strings(node.path("subjects")),
                    text(node, "enforcedScope"), node.path("version").asInt(),
                    text(node, "createdBy"), node.path("createdAt").asLong(),
                    text(node, "updatedBy"), node.path("updatedAt").asLong());
        } catch (IOException e) {
            throw new ManagementApiException(200, "GET", "/policies",
                    "policy detail is not valid JSON: " + e.getMessage(), null, null);
        }
    }

    /** 与 {@link #get} 相同，只是 204（无内容）不按错误处理——「没策略」是正常响应。 */
    private HttpResponse<String> sendAllowNoContent(HttpRequest.Builder builder, String path) {
        return sendWithRefresh(build(builder), path);
    }

    // ------------------------------------------------------------------ 内部：HTTP

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(uri(path)).GET(), path, 200);
    }

    private JsonNode post(String path, JsonNode body, int expected) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                path, expected);
    }

    private JsonNode put(String path, JsonNode body) {
        return put(path, body, 200);
    }

    private JsonNode put(String path, JsonNode body, int expected) {
        return send(HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                path, expected);
    }

    private JsonNode delete(String path) {
        return send(HttpRequest.newBuilder(uri(path)).DELETE(), path, 204);
    }

    /** 与 {@link #get} 相同，只是打到 catalog API 的权限端点。 */
    private JsonNode getAt(String path) {
        return send(HttpRequest.newBuilder(permissionUri(path)).GET(), path, 200);
    }

    /** 与 {@link #post} 相同，只是打到 catalog API 的权限端点。 */
    private JsonNode postAt(String path, JsonNode body, int expected) {
        return send(HttpRequest.newBuilder(permissionUri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)),
                path, expected);
    }

    private JsonNode send(HttpRequest.Builder builder, String path, int expected) {
        HttpRequest request = build(builder);
        HttpResponse<String> response = sendWithRefresh(request, path);
        int status = response.statusCode();
        String body = response.body();
        if (status != expected) {
            throw error(request.method(), path, status, body);
        }
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new ManagementApiException(status, request.method(), path,
                    "management API returned a body that is not valid JSON: " + e.getMessage(),
                    null, null);
        }
    }

    /** 补齐超时、Accept 与 Authorization，得到最终请求。 */
    private HttpRequest build(HttpRequest.Builder builder) {
        builder.timeout(timeout).header("Accept", "application/json");
        String token = tokenSource.token();
        if (token != null) {
            // 未配置令牌时不发送 Authorization 头：空值会让服务端把空串当成主体名
            builder.header(AUTHORIZATION, "Bearer " + token);
        }
        return builder.build();
    }

    /**
     * 发一次；401 且换到了新票时重放一次。
     *
     * <p><b>只重放一次。</b>把 401 变成重试风暴比 401 本身更糟：服务端如果持续拒绝
     * （主体被撤权、票被换签名密钥作废），无限重试只会把一次失败放大成一片超时。
     * 第二次 401 原样返回，由调用方转成异常。
     *
     * <p><b>要不要重放由 {@link TokenSource#invalidate()} 回答。</b>固定票（没配凭据）
     * 与「换票失败 / 退避中」都会返回 {@code false}——那些情况下重放用的是同一张票，
     * 只会多打一次注定失败的请求。
     */
    private HttpResponse<String> sendWithRefresh(HttpRequest request, String path) {
        HttpResponse<String> response = execute(request, path);
        if (response.statusCode() != 401 || !tokenSource.invalidate()) {
            return response;
        }
        String refreshed = tokenSource.token();
        if (refreshed == null || refreshed.isBlank()) {
            return response;
        }
        // 用 newBuilder(request, filter) 复制除 Authorization 之外的所有头：
        // 直接复用原 Builder 再 .header(...) 会**追加**第二个 Authorization，
        // 服务端看到哪个由实现决定——这类问题只在 401 之后才出现，最难查
        HttpRequest replay = HttpRequest.newBuilder(request,
                        (name, value) -> !AUTHORIZATION.equalsIgnoreCase(name))
                .header(AUTHORIZATION, "Bearer " + refreshed)
                .build();
        return execute(replay, path);
    }

    private HttpResponse<String> execute(HttpRequest request, String path) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ManagementApiException(0, request.method(), path,
                    "cannot reach the management API at " + baseUrl + ": " + e.getMessage(),
                    null, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagementApiException(0, request.method(), path,
                    "interrupted while calling the management API", null, null);
        }
    }

    /** 把服务端的 {@code ErrorResponse} 还原成异常；无法解析时退化为原始响应文本。 */
    private ManagementApiException error(String method, String path, int status, String body) {
        String message = body;
        String type = null;
        String name = null;
        if (body != null && !body.isBlank()) {
            try {
                JsonNode node = mapper.readTree(body);
                if (node.hasNonNull("message")) {
                    message = node.get("message").asText();
                }
                if (node.hasNonNull("type")) {
                    type = node.get("type").asText();
                }
                if (node.hasNonNull("name")) {
                    name = node.get("name").asText();
                }
            } catch (IOException ignored) {
                // 保持原始文本作为消息
            }
        }
        return new ManagementApiException(status, method, path, message, type, name);
    }

    @Override
    public String toString() {
        return "ManagementApiClient(" + baseUrl + ")";
    }

    // ------------------------------------------------------------------ 内部：形状转换

    /*
     * 下面的摘要类型刻意写成普通静态嵌套类，而不是 {@code record}。
     *
     * 它们会被 Scala 侧引用——{@code ManagementAstBuilder} / {@code ManagementCommands}
     * 按名字引用 {@code GrantInfo}，其余几个靠方法返回值推断后调用访问器。IDE 的
     * 混合编译会让 scalac 直接解析 Java 源码提取签名，而 Scala 2.12 的 Java 源码
     * 解析器不认识 {@code record} 关键字，于是这些类型在 IDE 里整体消失：
     * 报「not a member of object」，连带 lambda 报「missing parameter type」。
     * Maven 构建不受影响（POM 里 {@code sendJavaToScalac=false}，scalac 读的是
     * javac 产出的 class 文件），但 IDE 与 Maven 必须同时是绿的，因此只能写普通类。
     * 访问器沿用 record 的方法名（{@code name()} 而非 {@code getName()}），
     * 调用点因此一行不用改；equals/hashCode/toString 手写，保持 record 的值语义。
     */

    /** 主体摘要。{@code clientSecret} 不在此模型内，明文密钥只经 {@link Credentials} 返回一次。 */
    public static final class PrincipalInfo {

        private final String name;
        private final String clientId;
        private final Map<String, String> properties;
        private final Integer entityVersion;

        public PrincipalInfo(String name, String clientId, Map<String, String> properties,
                             Integer entityVersion) {
            this.name = name;
            this.clientId = clientId;
            this.properties = properties;
            this.entityVersion = entityVersion;
        }

        public String name() {
            return name;
        }

        public String clientId() {
            return clientId;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public Integer entityVersion() {
            return entityVersion;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PrincipalInfo other
                    && Objects.equals(name, other.name)
                    && Objects.equals(clientId, other.clientId)
                    && Objects.equals(properties, other.properties)
                    && Objects.equals(entityVersion, other.entityVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, clientId, properties, entityVersion);
        }

        @Override
        public String toString() {
            return "PrincipalInfo[name=" + name + ", clientId=" + clientId
                    + ", properties=" + properties + ", entityVersion=" + entityVersion + "]";
        }
    }

    /** 角色摘要：principal role 与 catalog role 的公共形状。 */
    public static final class RoleInfo {

        private final String name;
        private final Map<String, String> properties;
        private final Boolean federated;
        private final Integer entityVersion;

        public RoleInfo(String name, Map<String, String> properties, Boolean federated,
                        Integer entityVersion) {
            this.name = name;
            this.properties = properties;
            this.federated = federated;
            this.entityVersion = entityVersion;
        }

        public String name() {
            return name;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public Boolean federated() {
            return federated;
        }

        public Integer entityVersion() {
            return entityVersion;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof RoleInfo other
                    && Objects.equals(name, other.name)
                    && Objects.equals(properties, other.properties)
                    && Objects.equals(federated, other.federated)
                    && Objects.equals(entityVersion, other.entityVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, properties, federated, entityVersion);
        }

        @Override
        public String toString() {
            return "RoleInfo[name=" + name + ", properties=" + properties
                    + ", federated=" + federated + ", entityVersion=" + entityVersion + "]";
        }
    }

    /** catalog 摘要。 */
    public static final class CatalogInfo {

        private final String name;
        private final String type;
        private final Map<String, String> properties;
        private final Integer entityVersion;

        public CatalogInfo(String name, String type, Map<String, String> properties,
                           Integer entityVersion) {
            this.name = name;
            this.type = type;
            this.properties = properties;
            this.entityVersion = entityVersion;
        }

        public String name() {
            return name;
        }

        public String type() {
            return type;
        }

        public Map<String, String> properties() {
            return properties;
        }

        public Integer entityVersion() {
            return entityVersion;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CatalogInfo other
                    && Objects.equals(name, other.name)
                    && Objects.equals(type, other.type)
                    && Objects.equals(properties, other.properties)
                    && Objects.equals(entityVersion, other.entityVersion);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, type, properties, entityVersion);
        }

        @Override
        public String toString() {
            return "CatalogInfo[name=" + name + ", type=" + type
                    + ", properties=" + properties + ", entityVersion=" + entityVersion + "]";
        }
    }

    /** 创建 / 重置 / 轮换主体时一次性返回的凭据。 */
    public static final class Credentials {

        private final String name;
        private final String clientId;
        private final String clientSecret;

        public Credentials(String name, String clientId, String clientSecret) {
            this.name = name;
            this.clientId = clientId;
            this.clientSecret = clientSecret;
        }

        public String name() {
            return name;
        }

        public String clientId() {
            return clientId;
        }

        public String clientSecret() {
            return clientSecret;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Credentials other
                    && Objects.equals(name, other.name)
                    && Objects.equals(clientId, other.clientId)
                    && Objects.equals(clientSecret, other.clientSecret);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name, clientId, clientSecret);
        }

        @Override
        public String toString() {
            // clientSecret 是一次性明文，不进日志与诊断输出
            return "Credentials[name=" + name + ", clientId=" + clientId + "]";
        }
    }

    /**
     * 一条 Polaris 资源授权（挂在 catalog role 上），只在读路径上用到。
     *
     * @param type      资源类型判别值：{@code catalog} / {@code namespace} / {@code table} /
     *                  {@code view} / {@code policy} / {@code semantic-model}
     * @param namespace 多级命名空间；catalog 类型为空列表
     * @param objectName 对象名；catalog 与 namespace 类型为 {@code null}
     * @param privilege 权限取值
     */
    public static final class GrantInfo {

        private final String type;
        private final List<String> namespace;
        private final String objectName;
        private final String privilege;

        public GrantInfo(String type, List<String> namespace, String objectName,
                         String privilege) {
            this.type = type;
            this.namespace = namespace == null ? List.of() : namespace;
            this.objectName = objectName;
            this.privilege = privilege;
        }

        public String type() {
            return type;
        }

        public List<String> namespace() {
            return namespace;
        }

        public String objectName() {
            return objectName;
        }

        public String privilege() {
            return privilege;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GrantInfo other
                    && Objects.equals(type, other.type)
                    && Objects.equals(namespace, other.namespace)
                    && Objects.equals(objectName, other.objectName)
                    && Objects.equals(privilege, other.privilege);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, namespace, objectName, privilege);
        }

        @Override
        public String toString() {
            return "GrantInfo[type=" + type + ", namespace=" + namespace
                    + ", objectName=" + objectName + ", privilege=" + privilege + "]";
        }
    }

    /**
     * 官方权限模型的资源定位器。
     *
     * <p>{@code type} 是判别字段，与官方 {@code ResourceType} 逐字一致：
     * {@code CATALOG} / {@code CATALOG_ALL} / {@code DATABASE} / {@code DATABASE_ALL} /
     * {@code TABLE} / {@code COLUMN} / {@code VIEW} / {@code FUNCTION}。
     * {@code _ALL} 后缀是显式作用域——覆盖该资源**及其此后新建的后代**，
     * 与「只在被引用的资源本身生效」的精确类型相对。
     *
     * <p>其余字段按类型取用：{@code CATALOG} / {@code CATALOG_ALL} 不定位（catalog 由
     * 请求路径里的 prefix 决定，因此两者都是 {@code null}）；{@code DATABASE} /
     * {@code DATABASE_ALL} 只用 {@code database}；{@code TABLE} / {@code COLUMN} /
     * {@code VIEW} / {@code FUNCTION} 用 {@code database} + {@code objectName}。
     * 列范围只对 {@code COLUMN} 有意义，且白名单 {@code columns} 与黑名单
     * {@code excludedColumns} 恰好一个非空。
     *
     * <p>写成普通静态嵌套类而不是 {@code record}，理由见下面的类型注释（Scala 2.12
     * 的 Java 源码解析器不认识 {@code record}，而本类型会被 Scala 侧构造）。
     */
    public static final class PermissionResource {

        private final String type;
        private final String database;
        private final String objectName;
        private final List<String> columns;
        private final List<String> excludedColumns;

        public PermissionResource(String type, String database, String objectName,
                                  List<String> columns, List<String> excludedColumns) {
            this.type = type;
            this.database = database;
            this.objectName = objectName;
            this.columns = columns == null ? List.of() : List.copyOf(columns);
            this.excludedColumns = excludedColumns == null ? List.of() : List.copyOf(excludedColumns);
        }

        /** 整个 catalog。 */
        public static PermissionResource catalog() {
            return new PermissionResource("CATALOG", null, null, null, null);
        }

        /** catalog 及其全部后代（含此后新建的）。 */
        public static PermissionResource catalogAll() {
            return new PermissionResource("CATALOG_ALL", null, null, null, null);
        }

        public static PermissionResource database(String database) {
            return new PermissionResource("DATABASE", database, null, null, null);
        }

        /** 库及其全部后代（含此后新建的表 / 视图 / 函数）。 */
        public static PermissionResource databaseAll(String database) {
            return new PermissionResource("DATABASE_ALL", database, null, null, null);
        }

        public static PermissionResource table(String database, String table) {
            return new PermissionResource("TABLE", database, table, null, null);
        }

        public static PermissionResource view(String database, String view) {
            return new PermissionResource("VIEW", database, view, null, null);
        }

        public static PermissionResource function(String database, String function) {
            return new PermissionResource("FUNCTION", database, function, null, null);
        }

        /**
         * 表上的列范围：{@code columns} 是白名单、{@code excludedColumns} 是黑名单，
         * 恰好一个非空（两者都给或都不给由服务端判成 400）。
         */
        public static PermissionResource column(String database, String table,
                                                List<String> columns, List<String> excludedColumns) {
            return new PermissionResource("COLUMN", database, table, columns, excludedColumns);
        }

        public String type() {
            return type;
        }

        public String database() {
            return database;
        }

        public String objectName() {
            return objectName;
        }

        /** 列白名单；非 COLUMN 或黑名单形式时为空。 */
        public List<String> columns() {
            return columns;
        }

        /** 列黑名单；非 COLUMN 或白名单形式时为空。 */
        public List<String> excludedColumns() {
            return excludedColumns;
        }

        /** 是否带列范围——决定请求体里要不要出现 {@code columns}。 */
        public boolean hasColumnScope() {
            return !columns.isEmpty() || !excludedColumns.isEmpty();
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PermissionResource other
                    && Objects.equals(type, other.type)
                    && Objects.equals(database, other.database)
                    && Objects.equals(objectName, other.objectName)
                    && Objects.equals(columns, other.columns)
                    && Objects.equals(excludedColumns, other.excludedColumns);
        }

        @Override
        public int hashCode() {
            return Objects.hash(type, database, objectName, columns, excludedColumns);
        }

        @Override
        public String toString() {
            return "PermissionResource[type=" + type + ", database=" + database
                    + ", objectName=" + objectName + ", columns=" + columns
                    + ", excludedColumns=" + excludedColumns + "]";
        }
    }

    /**
     * 一条直授：赋权身份是 {@code (resource, access, principal)} 三元组，
     * {@code expireTime} 不属于身份（重复授予覆盖它）。
     */
    public static final class PermissionEntry {

        private final PermissionResource resource;
        private final String access;
        private final String principal;
        private final String expireTime;

        public PermissionEntry(PermissionResource resource, String access, String principal,
                               String expireTime) {
            this.resource = resource;
            this.access = access;
            this.principal = principal;
            this.expireTime = expireTime;
        }

        public PermissionResource resource() {
            return resource;
        }

        public String access() {
            return access;
        }

        public String principal() {
            return principal;
        }

        /** ISO-8601 instant；长期有效时为 {@code null}。 */
        public String expireTime() {
            return expireTime;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof PermissionEntry other
                    && Objects.equals(resource, other.resource)
                    && Objects.equals(access, other.access)
                    && Objects.equals(principal, other.principal)
                    && Objects.equals(expireTime, other.expireTime);
        }

        @Override
        public int hashCode() {
            return Objects.hash(resource, access, principal, expireTime);
        }

        @Override
        public String toString() {
            return "PermissionEntry[resource=" + resource + ", access=" + access
                    + ", principal=" + principal + ", expireTime=" + expireTime + "]";
        }
    }

    /**
     * 一张表的策略解析结果（{@code /policies/resolve} 的单行，§5.4）。
     *
     * <p>写成普通静态嵌套类而不是 {@code record}，理由见类注释顶部（Scala 2.12 的
     * Java 源码解析器不认识 {@code record}，而本类型会被 Scala 侧引用）。
     *
     * <p>{@code rowFilter} 与每个 {@code mask} 是<b>裸 AST Map</b>（服务端校验并折叠
     * {@code $principal} 之后的原样 JSON）：表达式语义由 {@code PolicyExpressionBuilder}
     * 在引擎侧解释，本类不做任何形状假设——把白名单复制到类型里，两份迟早漂移。
     */
    public static final class ResolvedPolicyInfo {

        /** {@code db.table} 两段名，与服务端回显一致。 */
        private final String table;
        /** {@code NONE} / {@code FOUND} / {@code EXEMPT}，见 DTO 的 {@code ResolvedPolicy}。 */
        private final String status;
        private final long version;
        /** 行过滤 AST；NONE / EXEMPT 或没配行过滤时为 {@code null}。 */
        private final Map<String, Object> rowFilter;
        private final List<MaskBindingInfo> masks;
        /** 整列不可见清单（P3）：FOUND 时非空；NONE / EXEMPT 恒为空。 */
        private final List<String> hiddenColumns;
        private final boolean exempted;
        private final String enforcedScope;

        public ResolvedPolicyInfo(String table, String status, long version,
                                  Map<String, Object> rowFilter, List<MaskBindingInfo> masks,
                                  List<String> hiddenColumns, boolean exempted, String enforcedScope) {
            this.table = table;
            this.status = status;
            this.version = version;
            this.rowFilter = rowFilter;
            this.masks = masks == null ? List.of() : List.copyOf(masks);
            this.hiddenColumns = hiddenColumns == null ? List.of() : List.copyOf(hiddenColumns);
            this.exempted = exempted;
            this.enforcedScope = enforcedScope;
        }

        public String table() {
            return table;
        }

        public String status() {
            return status;
        }

        public long version() {
            return version;
        }

        public Map<String, Object> rowFilter() {
            return rowFilter;
        }

        public List<MaskBindingInfo> masks() {
            return masks;
        }

        public List<String> hiddenColumns() {
            return hiddenColumns;
        }

        public boolean exempted() {
            return exempted;
        }

        public String enforcedScope() {
            return enforcedScope;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ResolvedPolicyInfo other
                    && Objects.equals(table, other.table)
                    && Objects.equals(status, other.status)
                    && version == other.version
                    && Objects.equals(rowFilter, other.rowFilter)
                    && Objects.equals(masks, other.masks)
                    && Objects.equals(hiddenColumns, other.hiddenColumns)
                    && exempted == other.exempted
                    && Objects.equals(enforcedScope, other.enforcedScope);
        }

        @Override
        public int hashCode() {
            return Objects.hash(table, status, version, rowFilter, masks, hiddenColumns,
                exempted, enforcedScope);
        }

        @Override
        public String toString() {
            // 不打印 rowFilter / masks：表达式里的字面量可能就是明文（§8.2 的口径）
            return "ResolvedPolicyInfo[table=" + table + ", status=" + status
                    + ", version=" + version + ", masks=" + masks.size()
                    + ", exempted=" + exempted + "]";
        }
    }

    /** 一列的 mask 绑定（{@code /policies/resolve} 的子项）。 */
    public static final class MaskBindingInfo {

        private final String column;
        private final Map<String, Object> mask;
        private final boolean allowNullOnRequired;

        public MaskBindingInfo(String column, Map<String, Object> mask, boolean allowNullOnRequired) {
            this.column = column;
            this.mask = mask;
            this.allowNullOnRequired = allowNullOnRequired;
        }

        public String column() {
            return column;
        }

        public Map<String, Object> mask() {
            return mask;
        }

        public boolean allowNullOnRequired() {
            return allowNullOnRequired;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof MaskBindingInfo other
                    && Objects.equals(column, other.column)
                    && Objects.equals(mask, other.mask)
                    && allowNullOnRequired == other.allowNullOnRequired;
        }

        @Override
        public int hashCode() {
            return Objects.hash(column, mask, allowNullOnRequired);
        }

        @Override
        public String toString() {
            return "MaskBindingInfo[column=" + column + "]";
        }
    }

    private GrantInfo grantInfo(JsonNode node) {
        List<String> namespace = new ArrayList<>();
        for (JsonNode element : node.path("namespace")) {
            namespace.add(element.asText());
        }
        String type = text(node, "type");
        String objectName = null;
        if (type != null) {
            String field = switch (type) {
                case "table" -> "tableName";
                case "view" -> "viewName";
                case "policy" -> "policyName";
                case "semantic-model" -> "semanticModelName";
                default -> null;
            };
            if (field != null) {
                objectName = text(node, field);
            }
        }
        return new GrantInfo(type, namespace, objectName, text(node, "privilege"));
    }

    /** 一张表的策略详情（管理面视图，{@code /policies/tables/...}，管理语句用）。 */
    public static final class PolicyDetailInfo {

        private final String table;
        private final boolean enabled;
        private final Map<String, Object> rowFilter;
        private final String rowFilterSql;
        private final List<MaskEntryInfo> masks;
        /** 整列不可见清单（P3）；PUT 整份替换语义，GET→PUT 往返必须原样带回。 */
        private final List<String> hiddenColumns;
        private final List<String> exemptPrincipals;
        private final List<String> subjects;
        private final String enforcedScope;
        private final int version;
        private final String createdBy;
        private final long createdAt;
        private final String updatedBy;
        private final long updatedAt;

        public PolicyDetailInfo(String table, boolean enabled, Map<String, Object> rowFilter,
                                String rowFilterSql, List<MaskEntryInfo> masks,
                                List<String> hiddenColumns, List<String> exemptPrincipals,
                                List<String> subjects, String enforcedScope, int version,
                                String createdBy, long createdAt, String updatedBy, long updatedAt) {
            this.table = table;
            this.enabled = enabled;
            this.rowFilter = rowFilter;
            this.rowFilterSql = rowFilterSql;
            this.masks = masks;
            this.hiddenColumns = hiddenColumns == null ? List.of() : List.copyOf(hiddenColumns);
            this.exemptPrincipals = exemptPrincipals;
            this.subjects = subjects;
            this.enforcedScope = enforcedScope;
            this.version = version;
            this.createdBy = createdBy;
            this.createdAt = createdAt;
            this.updatedBy = updatedBy;
            this.updatedAt = updatedAt;
        }

        public List<String> hiddenColumns() {
            return hiddenColumns;
        }

        public String table() {
            return table;
        }

        public boolean enabled() {
            return enabled;
        }

        public Map<String, Object> rowFilter() {
            return rowFilter;
        }

        public String rowFilterSql() {
            return rowFilterSql;
        }

        public List<MaskEntryInfo> masks() {
            return masks;
        }

        public List<String> exemptPrincipals() {
            return exemptPrincipals;
        }

        public List<String> subjects() {
            return subjects;
        }

        public String enforcedScope() {
            return enforcedScope;
        }

        public int version() {
            return version;
        }

        public String createdBy() {
            return createdBy;
        }

        public long createdAt() {
            return createdAt;
        }

        public String updatedBy() {
            return updatedBy;
        }

        public long updatedAt() {
            return updatedAt;
        }

        @Override
        public String toString() {
            // rowFilter 不进 toString：字面量可能就是明文（与 ResolvedPolicyInfo 同一口径）
            return "PolicyDetailInfo[table=" + table + ", enabled=" + enabled
                    + ", version=" + version + ", masks=" + masks.size()
                    + ", exempt=" + exemptPrincipals.size() + ", subjects=" + subjects.size() + "]";
        }
    }

    /** 详情里的一列 mask（{@code sql} 是服务端渲染的展示文本）。 */
    public static final class MaskEntryInfo {

        private final String column;
        private final Map<String, Object> mask;
        private final String sql;
        private final boolean allowNullOnRequired;

        public MaskEntryInfo(String column, Map<String, Object> mask, String sql,
                             boolean allowNullOnRequired) {
            this.column = column;
            this.mask = mask;
            this.sql = sql;
            this.allowNullOnRequired = allowNullOnRequired;
        }

        public String column() {
            return column;
        }

        public Map<String, Object> mask() {
            return mask;
        }

        public String sql() {
            return sql;
        }

        public boolean allowNullOnRequired() {
            return allowNullOnRequired;
        }
    }

    /** PUT 请求体里的一列 mask 提交项（管理语句的整份回写用）。 */
    public static final class MaskUpsertInfo {

        private final String column;
        private final Map<String, Object> mask;
        private final Boolean allowNullOnRequired;

        public MaskUpsertInfo(String column, Map<String, Object> mask, Boolean allowNullOnRequired) {
            this.column = column;
            this.mask = mask;
            this.allowNullOnRequired = allowNullOnRequired;
        }

        public String column() {
            return column;
        }

        public Map<String, Object> mask() {
            return mask;
        }

        public Boolean allowNullOnRequired() {
            return allowNullOnRequired;
        }
    }

    private PrincipalInfo principalInfo(JsonNode node) {
        return new PrincipalInfo(text(node, "name"), text(node, "clientId"), properties(node),
                integer(node, "entityVersion"));
    }

    private Credentials credentials(JsonNode node) {
        JsonNode credentials = node.path("credentials");
        return new Credentials(text(node.path("principal"), "name"), text(credentials, "clientId"),
                text(credentials, "clientSecret"));
    }

    private RoleInfo roleInfo(JsonNode node) {
        return new RoleInfo(text(node, "name"), properties(node), bool(node, "federated"),
                integer(node, "entityVersion"));
    }

    private ObjectNode roleBody(String field, String roleName) {
        ObjectNode role = mapper.createObjectNode();
        role.put("name", roleName);
        ObjectNode body = mapper.createObjectNode();
        body.set(field, role);
        return body;
    }

    private static Map<String, String> merge(Map<String, String> current,
                                             Map<String, String> toSet, Set<String> toUnset) {
        Map<String, String> merged = new LinkedHashMap<>(current == null ? Map.of() : current);
        if (toUnset != null) {
            toUnset.forEach(merged::remove);
        }
        if (toSet != null) {
            merged.putAll(toSet);
        }
        return merged;
    }

    private static Map<String, String> properties(JsonNode node) {
        Map<String, String> result = new LinkedHashMap<>();
        JsonNode properties = node.path("properties");
        if (properties.isObject()) {
            properties.properties().forEach(entry -> result.put(entry.getKey(),
                    entry.getValue().isNull() ? null : entry.getValue().asText()));
        }
        return result;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static Integer integer(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isNumber() ? value.asInt() : null;
    }

    private static Boolean bool(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    /** 新的空属性集合，供不需要属性的调用点使用。 */
    public static Set<String> noKeys() {
        return new LinkedHashSet<>();
    }

    private String catalogPath(String catalog) {
        return CATALOGS + "/" + encode(catalog);
    }

    private String catalogRolePath(String catalog, String catalogRole) {
        return catalogPath(catalog) + CATALOG_ROLES + "/" + encode(catalogRole);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    /**
     * 权限端点的 URI。
     *
     * <p>基址推不出来时（管理 URL 不在默认路径下、又没显式配 catalog 基址）报一条
     * 指向配置项的错——比发一个一定 404 的请求好，也比静默猜一个地址好。
     */
    private URI permissionUri(String path) {
        if (permissionBaseUrl == null) {
            throw new ManagementApiException(0, "GET", path,
                    "cannot locate the permissions endpoint: the management API base URL '"
                            + baseUrl + "' does not end with " + MANAGEMENT_SUFFIX
                            + ", so the catalog API base URL cannot be derived; set"
                            + " spark.lakehouse.rest.catalog.url to the catalog API base"
                            + " (for example http://host:8080/v1)", null, null);
        }
        return URI.create(permissionBaseUrl + path);
    }

    /**
     * 路径段编码。
     *
     * <p>{@link URLEncoder} 是 form 编码，空格会变成 {@code +}，在路径里是错的；
     * 这里把它换回 {@code %20}。不使用 {@code URI} 的构造函数是因为它会二次编码。
     */
    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String stripTrailingSlash(String url) {
        String result = url == null ? "" : url.trim();
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /** 供诊断输出：客户端指向的基址。 */
    public String baseUrl() {
        return baseUrl;
    }

    /** 供诊断输出：推不出来时为 {@code null}。 */
    public Optional<String> permissionBaseUrlOptional() {
        return Optional.ofNullable(permissionBaseUrl);
    }

    /**
     * 供诊断输出：此刻该用的令牌。
     *
     * <p>语义从「构造时那个字符串」变成「当前票」——续期之后它会变。取票可能触发一次
     * 换票，所以不要把它放进热路径的日志里（每调一次都可能是一次网络往返）。
     */
    public Optional<String> tokenOptional() {
        return Optional.ofNullable(tokenSource.token());
    }
}
