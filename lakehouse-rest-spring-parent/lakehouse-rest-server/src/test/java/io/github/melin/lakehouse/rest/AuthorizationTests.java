package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.PermissionAssignmentEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.PermissionAssignmentRepository;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.Paging;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 授权体系的行为测试（单一权限模型）。
 *
 * <p>在 {@code lakehouse.rest.authorization.enabled=true} 下验证四件事：
 *
 * <ol>
 *   <li>管理端点确实按调用者身份拒绝请求（而不是只在文档里声明 403）；</li>
 *   <li>「principal → 赋权」这条链路真的能放行请求，且撤销立即生效；</li>
 *   <li>principal role 作为分组，把授予 {@code role:<name>} 的赋权转发给成员；</li>
 *   <li>作用域覆盖符合官方语义：{@code CATALOG_ALL} 覆盖后代、{@code CATALOG}
 *       只覆盖实体本身、对象级只覆盖同名对象、列级赋权不放行整表。</li>
 * </ol>
 *
 * <p>使用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:paimon-authz;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthorizationTests {

    private static final String M = "/api/management/v1";
    private static final String CATALOG = "paimon";
    private static final String ROOT = "root";
    private static final String BOB = "authz-bob";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthorizationService authorizationService;

    @Autowired
    private PermissionAssignmentRepository permissionAssignmentRepository;

    @Autowired
    private CatalogRepository catalogRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 工具

    private JsonNode call(int expected, MockHttpServletRequestBuilder request,
                          String asPrincipal) throws Exception {
        if (asPrincipal != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + asPrincipal);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status as " + asPrincipal + ", body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private JsonNode as(String principal, int expected, String method, String path, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "DELETE" -> delete(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request, principal);
    }

    /** 授予一条赋权：{@code (resource, access, principal)}。 */
    private void grant(String resourceJson, String access, String principal) throws Exception {
        as(ROOT, 201, "POST", "/v1/" + CATALOG + "/permissions/grant",
                "{\"resource\":" + resourceJson + ",\"access\":\"" + access
                        + "\",\"principal\":\"" + principal + "\"}");
    }

    /**
     * 建主体，已存在则跳过。
     *
     * <p>本类的用例共享一个 H2 库且不做清理，多个用例都要用同一个探针主体，
     * 于是第二个用例建主体会拿到 409。这里显式容忍它——比给每个用例起一个新名字
     * 更能表达「同一主体在不同场景下的授权行为」。
     */
    private void ensurePrincipal(String name) throws Exception {
        MockHttpServletRequestBuilder request = post(M + "/principals")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"principal\":{\"name\":\"" + name + "\"}}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + ROOT);
        int status = mockMvc.perform(request).andReturn().getResponse().getStatus();
        assertTrue(status == 201 || status == 409, "unexpected status creating principal: " + status);
    }

    private void revoke(String resourceJson, String access, String principal) throws Exception {
        as(ROOT, 201, "POST", "/v1/" + CATALOG + "/permissions/revoke",
                "{\"resource\":" + resourceJson + ",\"access\":\"" + access
                        + "\",\"principal\":\"" + principal + "\"}");
    }

    /** 以某个主体身份执行一次判定，断言被拒。 */
    private void assertForbidden(String principal, ResourceType type, List<String> namespace,
                                 String object, Access access) {
        RequestContext.setPrincipal(principal);
        try {
            assertThrows(ApiException.class, () -> authorizationService.require(
                    CATALOG, type, namespace, object, access, "test"));
        } finally {
            RequestContext.clear();
        }
    }

    private void assertAllowed(String principal, ResourceType type, List<String> namespace,
                               String object, Access access) {
        RequestContext.setPrincipal(principal);
        try {
            authorizationService.require(CATALOG, type, namespace, object, access, "test");
        } finally {
            RequestContext.clear();
        }
    }

    // ------------------------------------------------------------------ 自举

    @Test
    void bootstrapGrantsGiveRootCatalogAccess() {
        assertTrue(authorizationService.isServiceAdmin(ROOT));

        // 引导链路：root → service_admin → CATALOG/ALL + CATALOG_ALL/ALL
        List<PermissionAssignmentEntity> assignments =
                authorizationService.effectiveAssignments(ROOT, CATALOG);
        assertTrue(assignments.stream().anyMatch(a -> a.getResourceType().equals("CATALOG")
                && a.getAccess().equals("ALL")), "root 应通过引导角色拿到 CATALOG/ALL");
        assertTrue(assignments.stream().anyMatch(a -> a.getResourceType().equals("CATALOG_ALL")
                && a.getAccess().equals("ALL")), "root 应通过引导角色拿到 CATALOG_ALL/ALL");
    }

    @Test
    void unauthenticatedAndUnknownPrincipalsAreRejected() throws Exception {
        // 未携带令牌 → 匿名主体，不是服务管理员
        as(null, 403, "GET", M + "/principals", null);
        // 携带了令牌但该主体不是服务管理员
        as("nobody", 403, "GET", M + "/principals", null);
        // 未在管理面登记的主体没有任何授权
        assertTrue(authorizationService.effectiveAssignments("nobody", CATALOG).isEmpty());
    }

    // ------------------------------------------------------------------ 授予与撤销

    @Test
    void grantsActuallyEnableAccessAndRevocationRemovesIt() throws Exception {
        ensurePrincipal(BOB);

        // 尚无任何授权：读 catalog 被拒，也不能管理主体
        as(BOB, 403, "GET", M + "/catalogs/" + CATALOG, null);
        as(BOB, 403, "GET", M + "/principals", null);

        // 读 catalog 详情按 CATALOG_ALL 上的 DESCRIBE 判：官方对照表里 CATALOG 作用域
        // 只有 ALL / ALTER / DROP / GRANT / CREATEDATABASE，没有任何读权限
        grant("{\"type\":\"CATALOG_ALL\"}", "DESCRIBE", BOB);
        as(BOB, 200, "GET", M + "/catalogs/" + CATALOG, null);
        // 后代作用域不覆盖容器本身：改 catalog 属性要 CATALOG 上的 ALTER
        as(BOB, 403, "PUT", M + "/catalogs/" + CATALOG,
                "{\"currentEntityVersion\":0,\"properties\":{}}");
        // 也不是服务管理员
        as(BOB, 403, "GET", M + "/principals", null);

        revoke("{\"type\":\"CATALOG_ALL\"}", "DESCRIBE", BOB);
        as(BOB, 403, "GET", M + "/catalogs/" + CATALOG, null);
    }

    @Test
    void principalRoleForwardsItsGrantsToMembers() throws Exception {
        ensurePrincipal(BOB);
        as(ROOT, 201, "POST", M + "/principal-roles",
                "{\"principalRole\":{\"name\":\"authz-analyst\"}}");
        as(ROOT, 201, "PUT", M + "/principals/" + BOB + "/principal-roles",
                "{\"principalRole\":{\"name\":\"authz-analyst\"}}");

        // 授给 role:<name>，成员通过解析拿到。这里用 CATALOG_ALL 上的 LIST：
        // 它是「列 catalog 下的库」这一集合操作的正式口径
        grant("{\"type\":\"CATALOG_ALL\"}", "LIST", "role:authz-analyst");
        as(BOB, 200, "GET", "/v1/" + CATALOG + "/databases", null);

        // 退出角色后立即失去
        as(ROOT, 204, "DELETE", M + "/principals/" + BOB
                + "/principal-roles/authz-analyst", null);
        as(BOB, 403, "GET", "/v1/" + CATALOG + "/databases", null);
    }

    // ------------------------------------------------------------------ 作用域

    @Test
    void catalogScopeDoesNotCoverDescendants() throws Exception {
        // CATALOG 只覆盖 catalog 实体；表操作要求 CATALOG_ALL 或更窄的作用域
        grant("{\"type\":\"CATALOG\"}", "ALL", BOB);
        assertAllowed(BOB, ResourceType.CATALOG, List.of(), null, Access.DROP);
        assertForbidden(BOB, ResourceType.TABLE, List.of("default"), "t1", Access.SELECT);
        revoke("{\"type\":\"CATALOG\"}", "ALL", BOB);
    }

    @Test
    void databaseAllCoversDescendantsAndDatabaseCoversCollections() throws Exception {
        grant("{\"type\":\"DATABASE_ALL\",\"database\":\"default\"}", "SELECT", BOB);
        // 后代表被覆盖
        assertAllowed(BOB, ResourceType.TABLE, List.of("default"), "t1", Access.SELECT);
        // 别的库不被覆盖
        assertForbidden(BOB, ResourceType.TABLE, List.of("other"), "t1", Access.SELECT);
        // SELECT 不会升级成 ALTER
        assertForbidden(BOB, ResourceType.TABLE, List.of("default"), "t1", Access.ALTER);

        // DATABASE 覆盖「该库的集合操作」，但不覆盖具体表
        grant("{\"type\":\"DATABASE\",\"database\":\"default\"}", "LIST", BOB);
        assertAllowed(BOB, ResourceType.TABLE, List.of("default"), null, Access.LIST);
        assertForbidden(BOB, ResourceType.TABLE, List.of("default"), "t1", Access.LIST);

        revoke("{\"type\":\"DATABASE_ALL\",\"database\":\"default\"}", "SELECT", BOB);
        revoke("{\"type\":\"DATABASE\",\"database\":\"default\"}", "LIST", BOB);
    }

    @Test
    void tableScopeCoversOnlyTheNamedTable() throws Exception {
        grant("{\"type\":\"TABLE\",\"database\":\"default\",\"table\":\"t1\"}", "SELECT", BOB);
        assertAllowed(BOB, ResourceType.TABLE, List.of("default"), "t1", Access.SELECT);
        assertForbidden(BOB, ResourceType.TABLE, List.of("default"), "t2", Access.SELECT);
        revoke("{\"type\":\"TABLE\",\"database\":\"default\",\"table\":\"t1\"}", "SELECT", BOB);
    }

    @Test
    void columnGrantDoesNotAuthorizeTheWholeTable() throws Exception {
        // COLUMN 赋权必须带列范围（官方规格：columns 只在 COLUMN 上出现且恰好一种非空）
        String columnResource = "{\"type\":\"COLUMN\",\"database\":\"default\",\"table\":\"t1\"}";
        as(ROOT, 201, "POST", "/v1/" + CATALOG + "/permissions/grant",
                "{\"resource\":" + columnResource + ",\"access\":\"SELECT\",\"principal\":\"" + BOB
                        + "\",\"columns\":{\"columnNames\":[\"a\",\"b\"]}}");

        // 列白名单不能当作整表可读：表级 SELECT 仍然拒绝
        assertForbidden(BOB, ResourceType.TABLE, List.of("default"), "t1", Access.SELECT);
        // 列级判定本身是通过的——这正是「授了两列」与「授了整表」的区别所在
        assertAllowed(BOB, ResourceType.COLUMN, List.of("default"), "t1", Access.SELECT);

        revoke(columnResource, "SELECT", BOB);
    }

    @Test
    void expiredAssignmentIsIgnored() {
        CatalogEntity catalog = catalogRepository.findByPrefix(CATALOG).orElseThrow();
        PermissionAssignmentEntity expired = new PermissionAssignmentEntity();
        expired.setId(Paging.newId());
        expired.setCatalogId(catalog.getId());
        expired.setResourceType("CATALOG_ALL");
        expired.setDatabaseName("");
        expired.setObjectName("");
        expired.setAccess("ALTER");
        expired.setPrincipal(BOB);
        expired.setExpireTime(System.currentTimeMillis() - 60_000);
        expired.markCreated(ROOT, System.currentTimeMillis() - 120_000);
        permissionAssignmentRepository.save(expired);

        assertForbidden(BOB, ResourceType.DATABASE, List.of("default"), null, Access.ALTER);
        assertFalse(authorizationService.effectiveAssignments(BOB, CATALOG).stream()
                        .anyMatch(a -> a.getAccess().equals("ALTER")),
                "过期赋权不应出现在有效集合里");
    }

    @Test
    void serviceAdminBypassesAccessChecks() {
        RequestContext.setPrincipal(ROOT);
        try {
            // 服务管理员在任意 catalog 上都直接放行
            authorizationService.require("totally-unknown-catalog", ResourceType.CATALOG,
                    List.of(), null, Access.GRANT, "do anything");
        } finally {
            RequestContext.clear();
        }
    }
}
