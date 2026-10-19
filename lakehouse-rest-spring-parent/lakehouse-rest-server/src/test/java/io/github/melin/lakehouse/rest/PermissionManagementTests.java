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
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.support.ApiException;
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
 * Paimon 原生权限端点（{@code /v1/{prefix}/permissions}）与直授判定的行为测试。
 *
 * <p>验证的是一条链路，而不是两条：官方三元组 {@code (resource, access, principal)}
 * 是**唯一**的授权来源（catalog role 与资源授权表已整体移除），因此这里既测
 * 端点的增删查、官方词表的入参校验、撤销幂等、重复授予覆盖过期时间，
 * 也测这些赋权真的能打通 catalog API 的判定。
 *
 * <p>判定调 {@link AuthorizationService#require} 而不是某个「有效权限集合」方法：
 * 官方只定义 {@code ALL} 一个聚合值，没有可展开的蕴含关系，
 * 所以能观察到的本来就只有「某个 access 在某资源上是否放行」这一个布尔量。
 *
 * <p>使用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:paimon-perm;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PermissionManagementTests {

    private static final String BASE = "/v1/paimon/permissions";
    private static final String CATALOG = "paimon";
    private static final String M = "/api/management/v1";
    private static final String ROOT = "root";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthorizationService authorizationService;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 工具

    private JsonNode call(int expected, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status, body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private JsonNode asRoot(int expected, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "POST" -> post(path);
            case "PUT" -> put(path);
            case "DELETE" -> delete(path);
            default -> get(path);
        };
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + ROOT);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request);
    }

    /** {@code resource} 定位器的 JSON 片段。 */
    private String tableResource(String db, String table) {
        return "{\"type\":\"TABLE\",\"database\":\"" + db + "\",\"table\":\"" + table + "\"}";
    }

    private void grant(String body) throws Exception {
        asRoot(201, "POST", BASE + "/grant", body);
    }

    private void revoke(String body) throws Exception {
        asRoot(201, "POST", BASE + "/revoke", body);
    }

    private JsonNode listAll() throws Exception {
        return asRoot(200, "GET", BASE, null);
    }

    /** 以某个主体身份跑一次单点判定。 */
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

    private void assertGranted(String principal, ResourceType type, List<String> namespace,
                               String object, Access access) {
        assertTrue(allows(principal, type, namespace, object, access),
                "期望放行：" + principal + " " + access + " on " + type + " " + namespace + " " + object);
    }

    private void assertDenied(String principal, ResourceType type, List<String> namespace,
                              String object, Access access) {
        assertThrows(ApiException.class,
                () -> allows(principal, type, namespace, object, access),
                "期望被拒：" + principal + " " + access + " on " + type + " " + namespace + " " + object);
    }

    // ------------------------------------------------------------------ 端点行为

    @Test
    void grantListRevokeRoundTrip() throws Exception {
        // 每个用例用独立的 principal/表名：测试类共享一个内存库，用例间不做清理，
        // 身份互不相同才不会互相污染计数
        String identity = "{\"resource\":" + tableResource("sales", "rt_orders")
                + ",\"access\":\"SELECT\",\"principal\":\"user:rt\"}";

        grant(identity);
        grant(identity); // 重复授予：身份相同，不新建条目

        JsonNode list = listAll();
        int matching = 0;
        for (JsonNode item : list.get("assignments")) {
            if ("user:rt".equals(item.get("principal").asString())
                    && "rt_orders".equals(item.get("resource").get("table").asString())) {
                matching++;
            }
        }
        assertEquals(1, matching, "同一身份重复授予只保留一条");

        // 撤销幂等：第一次删掉，第二次静默成功
        revoke(identity);
        revoke(identity);
        assertEquals(0, countAssignments("user:rt", "rt_orders"),
                "撤销后列表里不应再有该赋权");
    }

    @Test
    void grantingSameIdentityReplacesExpiry() throws Exception {
        grant("{\"resource\":" + tableResource("sales", "exp_orders")
                + ",\"access\":\"SELECT\",\"principal\":\"user:exp\"}");
        grant("{\"resource\":" + tableResource("sales", "exp_orders")
                + ",\"access\":\"SELECT\",\"principal\":\"user:exp\","
                + "\"expireTime\":\"2027-01-01T00:00:00Z\"}");

        assertEquals(1, countAssignments("user:exp", "exp_orders"),
                "重复授予是覆盖而不是追加");
        JsonNode remaining = findAssignment("user:exp", "exp_orders");
        assertEquals("2027-01-01T00:00:00Z", remaining.get("expireTime").asString(),
                "过期时间应被最近一次授予覆盖");
    }

    @Test
    void listSupportsFiltersAndPagination() throws Exception {
        grant("{\"resource\":{\"type\":\"DATABASE\",\"database\":\"sales\"},"
                + "\"access\":\"CREATETABLE\",\"principal\":\"user:alice\"}");
        grant("{\"resource\":" + tableResource("sales", "orders")
                + ",\"access\":\"SELECT\",\"principal\":\"user:bob\"}");

        // 按 principal 过滤
        JsonNode filtered = asRoot(200, "GET",
                BASE + "?principal=" + "user:bob", null);
        assertEquals(1, filtered.get("assignments").size());
        assertEquals("SELECT", filtered.get("assignments").get(0).get("access").asString());

        // 按 resourceType 过滤
        JsonNode byType = asRoot(200, "GET", BASE + "?resourceType=DATABASE", null);
        for (JsonNode item : byType.get("assignments")) {
            assertEquals("DATABASE", item.get("resource").get("type").asString());
        }

        // 分页：每页一条，nextPageToken 能走完
        JsonNode page = asRoot(200, "GET", BASE + "?maxResults=1", null);
        assertEquals(1, page.get("assignments").size());
        assertTrue(page.has("nextPageToken") && !page.get("nextPageToken").asString().isEmpty(),
                "还有剩余条目时应给出下一页 token");
        JsonNode second = asRoot(200, "GET",
                BASE + "?maxResults=1&pageToken=" + page.get("nextPageToken").asString(), null);
        assertEquals(1, second.get("assignments").size());
        assertFalse(second.get("assignments").get(0).equals(page.get("assignments").get(0)),
                "第二页不应重复第一页的条目");
    }

    @Test
    void validationRejectsInvalidAssignments() throws Exception {
        // access 与资源类型不匹配（官方对照表：CREATETABLE 不适用于 TABLE）
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":" + tableResource("sales", "orders")
                        + ",\"access\":\"CREATETABLE\",\"principal\":\"user:alice\"}");
        // DATABASE_ALL 上没有 DESCRIBE（官方对照表）
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":{\"type\":\"DATABASE_ALL\",\"database\":\"sales\"},"
                        + "\"access\":\"DESCRIBE\",\"principal\":\"user:alice\"}");
        // 缺 principal
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":" + tableResource("sales", "orders") + ",\"access\":\"SELECT\"}");
        // 缺 database 定位符
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":{\"type\":\"TABLE\"},\"access\":\"SELECT\",\"principal\":\"user:alice\"}");
        // expireTime 不是 ISO instant
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":" + tableResource("sales", "orders")
                        + ",\"access\":\"SELECT\",\"principal\":\"user:alice\",\"expireTime\":\"tomorrow\"}");
        // COLUMN 赋权必须恰好给一个列范围
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":{\"type\":\"COLUMN\",\"database\":\"sales\",\"table\":\"orders\"}"
                        + ",\"access\":\"SELECT\",\"principal\":\"user:alice\"}");
        // 非 COLUMN 赋权不允许带 columns
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":" + tableResource("sales", "orders")
                        + ",\"access\":\"SELECT\",\"principal\":\"user:alice\","
                        + "\"columns\":{\"columnNames\":[\"a\"]}}");
        // principal 超过官方 128 字符上限
        asRoot(400, "POST", BASE + "/grant",
                "{\"resource\":" + tableResource("sales", "orders")
                        + ",\"access\":\"SELECT\",\"principal\":\"" + "x".repeat(129) + "\"}");
    }

    @Test
    void unauthorizedCallersAreRejected() throws Exception {
        // 无授权主体调用权限端点：403（授权开启时，由路径映射与控制器双重拦下）
        MockHttpServletRequestBuilder request = get(BASE);
        request.header(HttpHeaders.AUTHORIZATION, "Bearer perm-nobody");
        call(403, request);
        MockHttpServletRequestBuilder grantRequest = post(BASE + "/grant");
        grantRequest.header(HttpHeaders.AUTHORIZATION, "Bearer perm-nobody")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"resource\":{\"type\":\"CATALOG\"},\"access\":\"GRANT\",\"principal\":\"user:x\"}");
        call(403, grantRequest);
    }

    // ------------------------------------------------------------------ 直授判定

    @Test
    void directUserGrantEnablesCatalogAccess() throws Exception {
        String identity = "{\"resource\":" + tableResource("sales", "orders")
                + ",\"access\":\"SELECT\",\"principal\":\"user:alice\"}";
        grant(identity);

        // user: 形式的直授对从未进入任何角色链的主体生效
        assertGranted("alice", ResourceType.TABLE, List.of("sales"), "orders", Access.SELECT);
        // SELECT 不放大为写：官方只定义 ALL 一个聚合值，没有 ALTER 含 SELECT 这类蕴含
        assertDenied("alice", ResourceType.TABLE, List.of("sales"), "orders", Access.UPDATE);

        // 未授予的主体什么都不得到
        assertDenied("bob", ResourceType.TABLE, List.of("sales"), "orders", Access.SELECT);

        // 撤销后立即失效
        revoke(identity);
        assertDenied("alice", ResourceType.TABLE, List.of("sales"), "orders", Access.SELECT);
    }

    @Test
    void databaseLevelGrantCoversCollectionOperations() throws Exception {
        String identity = "{\"resource\":{\"type\":\"DATABASE\",\"database\":\"sales\"},"
                + "\"access\":\"CREATETABLE\",\"principal\":\"user:alice\"}";
        grant(identity);

        // 建表判定落在 DATABASE 资源 + 空对象名（见 CatalogAccessRules 对
        // POST /databases/{db}/tables 的登记），DATABASE 级 CREATETABLE 应覆盖它
        assertGranted("alice", ResourceType.TABLE, List.of("sales"), null, Access.CREATETABLE);

        // 其它库不被覆盖
        assertDenied("alice", ResourceType.TABLE, List.of("other"), null, Access.CREATETABLE);
        // 也不覆盖具体的某张表——那是 TABLE 级目标
        assertDenied("alice", ResourceType.TABLE, List.of("sales"), "orders", Access.CREATETABLE);

        revoke(identity);
    }

    @Test
    void namespaceGrantMatchesMultiLevelNamespace() throws Exception {
        // 赋权把库名存成一列字符串，而 Iceberg 协议面把路径切成层级数组。
        // 「授 `accounting.tax` 覆盖 ["accounting","tax"]」这条如果断了，表现是
        // **权限页显示已授权、Iceberg 客户端却一直 403**——两处都不报错。
        grant("{\"resource\":{\"type\":\"DATABASE\",\"database\":\"accounting.tax\"},"
                + "\"access\":\"DESCRIBE\",\"principal\":\"user:iceberg_two_levels\"}");

        assertGranted("iceberg_two_levels", ResourceType.DATABASE, List.of("accounting", "tax"),
                null, Access.DESCRIBE);
        // 单段授权不该反向覆盖上级
        assertDenied("iceberg_two_levels", ResourceType.DATABASE, List.of("accounting"),
                null, Access.DESCRIBE);

        // 前缀必须落在层级边界上：`sales` 不能覆盖 `salesforce`
        grant("{\"resource\":{\"type\":\"DATABASE_ALL\",\"database\":\"sales\"},"
                + "\"access\":\"SELECT\",\"principal\":\"user:iceberg_prefix\"}");
        assertGranted("iceberg_prefix", ResourceType.TABLE, List.of("sales", "2026"),
                "orders", Access.SELECT);
        assertDenied("iceberg_prefix", ResourceType.TABLE, List.of("salesforce"),
                "orders", Access.SELECT);
    }

    @Test
    void roleGrantResolvesThroughPrincipalRole() throws Exception {
        // principal role 是纯分组：它自己不带权限，只把授予 role:<name> 的赋权转发给成员
        asRoot(201, "POST", M + "/principal-roles", "{\"principalRole\":{\"name\":\"perm_pr\"}}");
        asRoot(201, "POST", M + "/principals", "{\"principal\":{\"name\":\"perm-bob\"}}");
        asRoot(201, "PUT", M + "/principals/perm-bob/principal-roles",
                "{\"principalRole\":{\"name\":\"perm_pr\"}}");

        grant("{\"resource\":" + tableResource("sales", "orders")
                + ",\"access\":\"SELECT\",\"principal\":\"role:perm_pr\"}");

        assertGranted("perm-bob", ResourceType.TABLE, List.of("sales"), "orders", Access.SELECT);
        // 不持有该角色的主体不受影响
        assertDenied("perm-nobody", ResourceType.TABLE, List.of("sales"), "orders", Access.SELECT);

        // 退出角色后立即失去
        asRoot(204, "DELETE", M + "/principals/perm-bob/principal-roles/perm_pr", null);
        assertDenied("perm-bob", ResourceType.TABLE, List.of("sales"), "orders", Access.SELECT);
    }

    @Test
    void expiredAssignmentNoLongerGrants() throws Exception {
        grant("{\"resource\":" + tableResource("sales", "orders")
                + ",\"access\":\"SELECT\",\"principal\":\"user:carol\","
                + "\"expireTime\":\"2020-01-01T00:00:00Z\"}");

        assertDenied("carol", ResourceType.TABLE, List.of("sales"), "orders", Access.SELECT);
        // 列表仍反映落库内容（过期与否由判定层处理）
        assertEquals(1, countAssignments("user:carol", "orders"));
    }

    // ------------------------------------------------------------------ 查询辅助

    private int countAssignments(String principal, String table) throws Exception {
        int count = 0;
        for (JsonNode item : listAll().get("assignments")) {
            if (principal.equals(item.get("principal").asString())
                    && table.equals(item.get("resource").get("table").asString())) {
                count++;
            }
        }
        return count;
    }

    private JsonNode findAssignment(String principal, String table) throws Exception {
        for (JsonNode item : listAll().get("assignments")) {
            if (principal.equals(item.get("principal").asString())
                    && table.equals(item.get("resource").get("table").asString())) {
                return item;
            }
        }
        throw new AssertionError("assignment not found: " + principal + " on " + table);
    }
}
