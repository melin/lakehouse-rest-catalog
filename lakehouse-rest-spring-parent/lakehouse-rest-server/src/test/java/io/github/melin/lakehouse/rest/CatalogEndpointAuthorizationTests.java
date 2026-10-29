package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.config.CatalogAccessRules;
import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * catalog API（{@code /v1/**}）的授权拦截行为测试。
 *
 * <p>与 {@link AuthorizationTests} 的分工：那一类验证判定本身（作用域覆盖、{@code ALL} 聚合、
 * 过期与撤销），本类验证判定被真正接到了 catalog 端点上——即
 * {@link CatalogAccessRules} 的映射表与控制器实际暴露的端点集合一致。
 *
 * <p><b>端点清单不手写。</b>{@link #catalogEndpoints()} 直接从
 * {@link RequestMappingHandlerMapping} 读出运行时注册的全部路径与方法，
 * 因此新增端点若忘记在映射表登记，
 * {@link #everyCatalogEndpointPassesWithSufficientPrivileges()} 会立刻失败。
 * 这正是把授权做成集中映射表而非逐处注解的目的。
 *
 * <p><b>授权只有一条路径。</b>catalog role 已整体移除，本类不再需要「catalog role →
 * principal role → principal」三段装配，一条
 * {@code POST /v1/{prefix}/permissions/grant} 就够；access 用的是官方那 12 项
 * 加本工程的两个凭据扩展词（{@code USE_CREDENTIAL} / {@code USE_SECRET}）。
 *
 * <p>使用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 * {@code test} profile 负责把数据源从默认的 MySQL 换成内存 H2
 * （见 {@code src/test/resources/application-test.yml}），本类再覆盖库名以进一步隔离。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:paimon-catalog-authz;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CatalogEndpointAuthorizationTests {

    private static final String M = "/api/management/v1";
    private static final String CATALOG = "paimon";
    private static final String ROOT = "root";
    private static final String READER = "cat-reader";
    private static final String WRITER = "cat-writer";
    private static final String SCOPED = "cat-scoped";

    /** 探针用的命名空间：并不存在，因此端点应返回 404 而不是 403。 */
    private static final String NAMESPACE_A = "authz_ns_a";
    private static final String NAMESPACE_B = "authz_ns_b";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RequestMappingHandlerMapping handlerMapping;

    @Autowired
    private AuthorizationService authorizationService;

    // ------------------------------------------------------------------ 工具

    /** 一个端点：HTTP 方法与已把占位符替换成具体值的路径。 */
    private record Endpoint(String method, String path) {
    }

    /**
     * 从运行时映射表读出 {@code /v1/{prefix}/…} 下的全部端点。
     *
     * <p>{@code /v1/config} 被排除：它是显式豁免授权的端点，
     * 单独由 {@link #configEndpointStaysPublic()} 覆盖。
     */
    private List<Endpoint> catalogEndpoints() {
        List<Endpoint> endpoints = new ArrayList<>();
        for (var entry : handlerMapping.getHandlerMethods().entrySet()) {
            RequestMappingInfo info = entry.getKey();
            var patterns = info.getPathPatternsCondition();
            if (patterns == null) {
                continue;
            }
            Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
            for (String pattern : patterns.getPatternValues()) {
                if (!pattern.startsWith("/v1/{prefix}")) {
                    continue;
                }
                RequestMethod method = methods.isEmpty() ? RequestMethod.GET : methods.iterator().next();
                endpoints.add(new Endpoint(method.name(), concrete(pattern)));
            }
        }
        endpoints.sort(Comparator.comparing(Endpoint::path).thenComparing(Endpoint::method));
        return endpoints;
    }

    private String concrete(String pattern) {
        return pattern.replace("{prefix}", CATALOG).replaceAll("\\{[^}]+}", "authz_probe");
    }

    private MvcResult perform(String principal, int expected, String method, String path, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = builder(method, path);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (principal != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + principal);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        int actual = result.getResponse().getStatus();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, actual, "unexpected status for " + method + " " + path
                + " as " + principal + ", body=" + responseBody);
        return result;
    }

    /** 只取状态码，用于断言「不是 403」。 */
    private int status(String principal, String method, String path) throws Exception {
        MockHttpServletRequestBuilder request = builder(method, path);
        if ("POST".equals(method) || "PUT".equals(method)) {
            request = request.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        if (principal != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + principal);
        }
        return mockMvc.perform(request).andReturn().getResponse().getStatus();
    }

    /**
     * 按方法名造请求。
     *
     * <p><b>为什么要有 {@code HEAD}。</b>Iceberg 侧的三个「存在性检查」端点
     * （namespace / table / view）是**显式**登记的 {@code HEAD} 映射，而
     * {@link #catalogEndpoints()} 是从运行时映射表读端点的——它会把这三个一并读出来，
     * 于是一个只能造 GET/POST/PUT/DELETE 的工厂会在这里以
     * {@code IllegalArgumentException: HEAD} 失败。这不是「多加一个 case」那么简单：
     * 它证明端点枚举确实覆盖了新协议面，而枚举不到的分支会静默地少测一批端点。
     */
    private MockHttpServletRequestBuilder builder(String method, String path) {
        return switch (method) {
            case "GET" -> get(path);
            case "HEAD" -> head(path);
            case "DELETE" -> delete(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
    }

    private void createPrincipal(String name) throws Exception {
        perform(ROOT, 201, "POST", M + "/principals", "{\"principal\":{\"name\":\"" + name + "\"}}");
    }

    /**
     * 授予一条赋权。{@code resourceJson} 是官方 {@code PermissionResource} 片段，
     * 例如 {@code {"type":"DATABASE_ALL","database":"sales"}}。
     */
    private void grant(String principal, String resourceJson, String access) throws Exception {
        perform(ROOT, 201, "POST", "/v1/" + CATALOG + "/permissions/grant",
                "{\"resource\":" + resourceJson + ",\"access\":\"" + access
                        + "\",\"principal\":\"" + principal + "\"}");
    }

    /** 以某个主体身份跑一次判定，期望被拒。 */
    private void assertForbidden(String principal, ResourceType type, List<String> namespace,
                                 String object, Access access) {
        assertThrows(ApiException.class,
                () -> allows(principal, type, namespace, object, access),
                "期望被拒：" + principal + " " + access + " on " + type + " " + namespace + " " + object);
    }

    private void assertAllowed(String principal, ResourceType type, List<String> namespace,
                               String object, Access access) {
        assertTrue(allows(principal, type, namespace, object, access),
                "期望放行：" + principal + " " + access + " on " + type + " " + namespace + " " + object);
    }

    /** 用 {@link RequestContext} 临时冒充某个主体，跑完立刻清空，避免污染其它用例。 */
    private boolean allows(String principal, ResourceType type, List<String> namespace,
                           String object, Access access) {
        RequestContext.setPrincipal(principal);
        try {
            authorizationService.require(CATALOG, type, namespace, object, access, "test");
            return true;
        } finally {
            RequestContext.clear();
        }
    }

    // ------------------------------------------------------------------ 映射表完整性

    @Test
    void everyCatalogEndpointPassesWithSufficientPrivileges() throws Exception {
        createPrincipal(WRITER);
        // 两条都得给，不是冗余：
        //   CATALOG_ALL 覆盖 catalog 的后代（库、表、视图、函数）；
        //   CATALOG     覆盖 catalog 实体本身——建库（CREATEDATABASE）只在这个作用域上，
        //               官方对照表里 CATALOG_ALL 没有 CREATEDATABASE。
        // 一条 ALL 就能满足其作用域内的全部 access，因此不需要逐条授予。
        grant(WRITER, "{\"type\":\"CATALOG_ALL\"}", "ALL");
        grant(WRITER, "{\"type\":\"CATALOG\"}", "ALL");

        List<Endpoint> endpoints = catalogEndpoints();
        assertTrue(endpoints.size() >= 55,
                "catalog API 的端点数量明显偏少，可能是枚举逻辑失效了：" + endpoints.size());

        List<String> rejected = new ArrayList<>();
        for (Endpoint endpoint : endpoints) {
            if (status(WRITER, endpoint.method(), endpoint.path()) == 403) {
                rejected.add(endpoint.method() + " " + endpoint.path());
            }
        }
        assertTrue(rejected.isEmpty(),
                "以下端点被 403 拒绝，说明映射表缺失或权限要求过严：" + rejected);

        // 映射表登记的端点数量应与运行时端点数量一致
        long mapped = endpoints.stream()
                .filter(endpoint -> CatalogAccessRules.resolve(endpoint.path(), endpoint.method()) != null)
                .count();
        assertEquals(endpoints.size(), mapped,
                "存在未在 CatalogAccessRules 登记的 catalog 端点（失败关闭会在运行时报 403）");
    }

    @Test
    void configEndpointStaysPublic() throws Exception {
        assertTrue(CatalogAccessRules.isPublic("/v1/config"));
        perform(null, 200, "GET", "/v1/config", null);
    }

    // ------------------------------------------------------------------ 拒绝未授权调用

    @Test
    void unprivilegedCallersAreRejectedOnCatalogEndpoints() throws Exception {
        // 匿名
        perform(null, 403, "GET", "/v1/" + CATALOG + "/databases", null);
        // 已登记主体但没有任何授权
        createPrincipal("cat-nobody");
        assertEquals(403, status("cat-nobody", "GET", "/v1/" + CATALOG + "/databases"));
        // 未登记主体同样被拒
        assertEquals(403, status("cat-ghost", "GET", "/v1/" + CATALOG + "/tables"));
        assertEquals(403, status("cat-ghost", "POST", "/v1/" + CATALOG + "/databases"));
    }

    @Test
    void listAccessDoesNotAllowWrites() throws Exception {
        createPrincipal(READER);
        // 只有列举权限：能看到有哪些库、哪些表，但建不了、也读不到数据。
        // 官方词表里没有「读元数据就顺带能读数据」这种蕴含，SELECT 必须单独给。
        grant(READER, "{\"type\":\"CATALOG_ALL\"}", "LIST");

        // 允许：列举命名空间
        perform(READER, 200, "GET", "/v1/" + CATALOG + "/databases", null);
        // 允许：列举表（命名空间不存在，因此 404 而非 403）
        assertEquals(404, status(READER, "GET", "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables"));

        // 拒绝：创建命名空间（要求 CATALOG 上的 CREATEDATABASE）
        assertEquals(403, status(READER, "POST", "/v1/" + CATALOG + "/databases"));
        // 拒绝：创建表（要求 DATABASE 上的 CREATETABLE）
        assertEquals(403, status(READER, "POST",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables"));
        // 拒绝：取数据令牌（要求 TABLE 上的 USE_CREDENTIAL，LIST 不含它）
        assertEquals(403, status(READER, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables/authz_probe/token"));
        // 拒绝：读语义视图（按 VIEW 资源判定，要求 DESCRIBE）
        assertEquals(403, status(READER, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/semantic-views/authz_probe"));
    }

    @Test
    void databaseScopedGrantOnlyCoversItsOwnSubtree() throws Exception {
        createPrincipal(SCOPED);
        // DATABASE_ALL 是官方的「显式后代作用域」：覆盖该库及其下全部对象。
        // 选 USE_CREDENTIAL 是因为它能直接在 token 端点上观察到——那正是「取票」的入口；
        // 官方词表里的 SELECT 已不再是这个端点的判据（见 CatalogAccessRules 的注释）。
        grant(SCOPED, "{\"type\":\"DATABASE_ALL\",\"database\":\"" + NAMESPACE_A + "\"}", "USE_CREDENTIAL");

        // 被授权命名空间内：通过授权检查，继而因表不存在返回 404
        int insideStatus = status(SCOPED, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_A + "/tables/authz_probe/token");
        assertEquals(404, insideStatus, "库内取令牌应通过授权，表不存在则 404");

        // 另一个命名空间：拒绝
        assertEquals(403, status(SCOPED, "GET",
                "/v1/" + CATALOG + "/databases/" + NAMESPACE_B + "/tables/authz_probe/token"));

        // 服务层判定与之一致
        assertAllowed(SCOPED, ResourceType.TABLE, List.of(NAMESPACE_A), "authz_probe",
                Access.USE_CREDENTIAL);
        assertForbidden(SCOPED, ResourceType.TABLE, List.of(NAMESPACE_B), "authz_probe",
                Access.USE_CREDENTIAL);
        // 前缀必须落在点号边界上：`authz_ns_a` 不能覆盖 `authz_ns_a2`
        assertForbidden(SCOPED, ResourceType.TABLE, List.of(NAMESPACE_A + "2"), "authz_probe",
                Access.USE_CREDENTIAL);
        // 两个凭据点之间没有蕴含：只授了「能取票」不等于「能拿走存的密钥」
        assertForbidden(SCOPED, ResourceType.TABLE, List.of(NAMESPACE_A), "authz_probe",
                Access.USE_SECRET);
        // 也不放大成 SELECT / DESCRIBE / UPDATE
        assertForbidden(SCOPED, ResourceType.TABLE, List.of(NAMESPACE_A), "authz_probe",
                Access.SELECT);
        assertForbidden(SCOPED, ResourceType.TABLE, List.of(NAMESPACE_A), "authz_probe",
                Access.UPDATE);
    }

    // ------------------------------------------------------------------ 映射表自身的单元校验

    @Test
    void mappingTableResolvesRepresentativeEndpoints() {
        CatalogAccessRules.Requirement listTables =
                CatalogAccessRules.resolve("/v1/paimon/tables", "GET");
        // 跨库列举：官方表里 LIST 只出现在 CATALOG_ALL 上
        assertEquals(ResourceType.CATALOG_ALL, listTables.resourceType());
        assertEquals(Access.LIST, listTables.access());

        // 同一个「库集合」路径，读与写落在不同目标类型：LIST 在 CATALOG_ALL，
        // CREATEDATABASE 只在 CATALOG 上——这是官方模型的形状
        assertEquals(ResourceType.CATALOG_ALL,
                CatalogAccessRules.resolve("/v1/paimon/databases", "GET").resourceType());
        CatalogAccessRules.Requirement createDatabase =
                CatalogAccessRules.resolve("/v1/paimon/databases", "POST");
        assertEquals(ResourceType.CATALOG, createDatabase.resourceType());
        assertEquals(Access.CREATEDATABASE, createDatabase.access());

        CatalogAccessRules.Requirement readTable =
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t", "GET");
        assertEquals(ResourceType.TABLE, readTable.resourceType());
        assertEquals(List.of("db"), readTable.namespace());
        assertEquals("t", readTable.objectName());
        assertEquals(Access.DESCRIBE, readTable.access());

        // 删除表要求 DROP，改表要求 ALTER
        assertEquals(Access.DROP,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t", "DELETE").access());
        assertEquals(Access.ALTER,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t", "POST").access());

        // commit 走数据写权限
        assertEquals(Access.UPDATE,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t/commit", "POST").access());
        // token 走「能取票」那一项，而不是 SELECT——两者是两个风险，见设计稿 §3.4.3
        assertEquals(Access.USE_CREDENTIAL,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t/token", "GET").access());
        // 同一个表下的 /auth 只回行过滤与脱敏规则、不签发任何凭据，仍归 SELECT
        assertEquals(Access.SELECT,
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/t/auth", "POST").access());
        // Iceberg 面的 /credentials 与 Paimon 的 /token 是同一件事，同一个词
        assertEquals(Access.USE_CREDENTIAL, CatalogAccessRules
                .resolve("/v1/iceberg/namespaces/db/tables/t/credentials", "GET").access());
        // 而 /plan 只回扫描计划、不带密钥
        assertEquals(Access.SELECT,
                CatalogAccessRules.resolve("/v1/iceberg/namespaces/db/tables/t/plan", "POST").access());
        // 分区列表是读，分区 drop 是写
        assertEquals(Access.SELECT, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/partitions/list-by-names", "POST").access());
        assertEquals(Access.UPDATE, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/partitions/drop", "POST").access());
        // 分支与标签仍是表级元数据
        assertEquals(Access.DESCRIBE, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/branches", "GET").access());
        assertEquals(Access.ALTER, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/tables/t/tags/old", "DELETE").access());

        // 语义视图按 VIEW 资源授权：官方 12 项 access 里没有 semantic-model 这一层
        assertEquals(ResourceType.VIEW, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/semantic-views/sv", "GET").resourceType());
        assertEquals(Access.DESCRIBE, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/semantic-views/sv", "GET").access());

        // function 有独立的资源类型与 CREATEFUNCTION
        CatalogAccessRules.Requirement listFunctions =
                CatalogAccessRules.resolve("/v1/paimon/databases/db/functions", "GET");
        assertEquals(ResourceType.FUNCTION, listFunctions.resourceType());
        assertEquals(Access.LIST, listFunctions.access());
        assertEquals(Access.CREATEFUNCTION, CatalogAccessRules
                .resolve("/v1/paimon/databases/db/functions", "POST").access());

        // 按 id 查表跨命名空间，只能按 catalog 后代集合判
        assertEquals(ResourceType.CATALOG_ALL,
                CatalogAccessRules.resolve("/v1/paimon/tables/id/12345", "GET").resourceType());

        // 权限端点：列赋权要求 CATALOG_ALL 上的 GRANT；授予/撤销是 deferred
        assertEquals(Access.GRANT,
                CatalogAccessRules.resolve("/v1/paimon/permissions", "GET").access());
        CatalogAccessRules.Requirement grantEndpoint =
                CatalogAccessRules.resolve("/v1/paimon/permissions/grant", "POST");
        assertNotNull(grantEndpoint);
        assertTrue(grantEndpoint.deferred(), "grant 的目标资源在请求体里，必须标为 deferred");
        assertNull(grantEndpoint.access());
        // 把写端点当读端点探测：不登记 → 拒绝
        assertNull(CatalogAccessRules.resolve("/v1/paimon/permissions/grant", "GET"));

        // 非 catalog API 与豁免路径
        assertNull(CatalogAccessRules.resolve("/api/management/v1/catalogs", "GET"));
        assertNull(CatalogAccessRules.resolve("/v1/config", "GET"));
        assertNull(CatalogAccessRules.resolve("/polaris-management-service.yml", "GET"));
    }

    @Test
    void pathSegmentsAreUrlDecoded() {
        // 表名允许含需要编码的字符，解码后再与授权中的作用域比较
        CatalogAccessRules.Requirement requirement =
                CatalogAccessRules.resolve("/v1/paimon/databases/db/tables/my%20table", "GET");
        assertEquals("my table", requirement.objectName());
    }

    @Test
    void unknownEndpointInsideCatalogApiIsNotSilentlyAllowed() {
        // 失败关闭：/v1 下未登记的路径不会被映射表静默放行
        assertNull(CatalogAccessRules.resolve("/v1/paimon/unknown-endpoint", "GET"));
        assertFalse(CatalogAccessRules.isPublic("/v1/paimon/databases"));
        assertFalse(CatalogAccessRules.isPublic("/v1/config/extra"));
        assertTrue(CatalogAccessRules.isPublic("/v1/config"));
        // 结尾斜杠不影响豁免判定
        assertTrue(CatalogAccessRules.isPublic("/v1/config/"));
    }
}
