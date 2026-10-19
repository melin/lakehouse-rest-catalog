package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.dto.ManagementEnums.GrantType;
import io.github.melin.lakehouse.rest.dto.Privilege;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Paimon 原生权限端点（{@code /v1/{prefix}/permissions}）与直授判定的行为测试。
 *
 * <p>与 {@link AuthorizationTests}（Polaris RBAC 链）互补，这里验证第二路授权来源：
 * 赋权三元组的增删查、官方词表的入参校验、撤销幂等、重复授予覆盖过期时间，
 * 以及直授真的能打通 catalog API 的权限判定（{@code user:} 与 {@code role:} 两种
 * principal 形式各一条链路）。
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

        // user: 形式的直授对从未进入 RBAC 链的主体生效
        Set<Privilege> effective = authorizationService.effectivePrivileges(
                "alice", "paimon", GrantType.TABLE, List.of("sales"), "orders");
        assertTrue(effective.contains(Privilege.TABLE_READ_DATA),
                "user:alice 的 SELECT 直授应转化为 alice 的 TABLE_READ_DATA");
        assertFalse(effective.contains(Privilege.TABLE_WRITE_DATA),
                "SELECT 不应放大为写权限");

        // 裸名等价：官方 principal 是不透明字符串，服务端按约定同时接受裸名
        Set<Privilege> viaBareName = authorizationService.effectivePrivileges(
                "bob", "paimon", GrantType.TABLE, List.of("sales"), "orders");
        assertTrue(viaBareName.isEmpty());

        // 撤销后立即失效
        revoke(identity);
        assertTrue(authorizationService.effectivePrivileges(
                        "alice", "paimon", GrantType.TABLE, List.of("sales"), "orders").isEmpty(),
                "撤销直授后 alice 不应再有权限");
    }

    @Test
    void databaseLevelGrantCoversTableOperations() throws Exception {
        grant("{\"resource\":{\"type\":\"DATABASE\",\"database\":\"sales\"},"
                + "\"access\":\"CREATETABLE\",\"principal\":\"user:alice\"}");

        // 建表判定落在 TABLE 层（CatalogAccessRules），DATABASE 级 CREATETABLE 应覆盖它
        Set<Privilege> effective = authorizationService.effectivePrivileges(
                "alice", "paimon", GrantType.TABLE, List.of("sales"), null);
        assertTrue(effective.contains(Privilege.TABLE_CREATE),
                "DATABASE 级 CREATETABLE 应覆盖库内建表判定");

        // 其它库不被覆盖
        assertTrue(authorizationService.effectivePrivileges(
                "alice", "paimon", GrantType.TABLE, List.of("other"), null).isEmpty());

        revoke("{\"resource\":{\"type\":\"DATABASE\",\"database\":\"sales\"},"
                + "\"access\":\"CREATETABLE\",\"principal\":\"user:alice\"}");
    }

    @Test
    void namespaceGrantMatchesMultiLevelNamespace() throws Exception {
        // 直授把库名存成一列字符串，而 Iceberg 协议面把路径切成层级数组。
        // 「授 `accounting.tax` 覆盖 ["accounting","tax"]」这条如果断了，表现是
        // **权限页显示已授权、Iceberg 客户端却一直 403**——两处都不报错。
        grant("{\"resource\":{\"type\":\"DATABASE\",\"database\":\"accounting.tax\"},"
                + "\"access\":\"DESCRIBE\",\"principal\":\"user:iceberg_two_levels\"}");

        assertTrue(namespacePrivileges("iceberg_two_levels", List.of("accounting", "tax"))
                        .contains(Privilege.NAMESPACE_READ_PROPERTIES),
                "单段 `accounting.tax` 必须覆盖 Iceberg 的两级 namespace");
        assertTrue(namespacePrivileges("iceberg_two_levels", List.of("accounting", "tax", "2026"))
                        .contains(Privilege.NAMESPACE_READ_PROPERTIES),
                "namespace 级授权按前缀覆盖后代");
        assertFalse(namespacePrivileges("iceberg_two_levels", List.of("accounting"))
                        .contains(Privilege.NAMESPACE_READ_PROPERTIES),
                "两级授权不该反过来覆盖到上级");

        // 前缀必须落在层级边界上：单段 `sales` 不能覆盖 `salesforce`
        grant("{\"resource\":{\"type\":\"DATABASE\",\"database\":\"sales\"},"
                + "\"access\":\"DESCRIBE\",\"principal\":\"user:iceberg_prefix\"}");
        assertFalse(namespacePrivileges("iceberg_prefix", List.of("salesforce"))
                        .contains(Privilege.NAMESPACE_READ_PROPERTIES),
                "`sales` 不该被当成 `salesforce` 的前缀");
        assertTrue(namespacePrivileges("iceberg_prefix", List.of("sales", "2026"))
                        .contains(Privilege.NAMESPACE_READ_PROPERTIES),
                "`sales` 仍应是 `sales.2026` 的前缀");
    }

    /** 主体在一组 namespace 层级上的有效权限（走直授判定链）。 */
    private Set<Privilege> namespacePrivileges(String principal, List<String> levels) {
        return authorizationService.effectivePrivileges(
                principal, "paimon", GrantType.NAMESPACE, levels, null);
    }

    @Test
    void roleGrantResolvesThroughCatalogRoleName() throws Exception {
        // 准备 RBAC 链：bob → principal role → catalog role perm_sales_writer
        asRoot(201, "POST", M + "/catalogs/paimon/catalog-roles",
                "{\"catalogRole\":{\"name\":\"perm_sales_writer\"}}");
        asRoot(201, "POST", M + "/principal-roles", "{\"principalRole\":{\"name\":\"perm_pr\"}}");
        asRoot(201, "PUT", M + "/principal-roles/perm_pr/catalog-roles/paimon",
                "{\"catalogRole\":{\"name\":\"perm_sales_writer\"}}");
        asRoot(201, "POST", M + "/principals", "{\"principal\":{\"name\":\"perm-bob\"}}");
        asRoot(201, "PUT", M + "/principals/perm-bob/principal-roles",
                "{\"principalRole\":{\"name\":\"perm_pr\"}}");

        // 直授给 role:perm_sales_writer——与 catalog role 同名，按命名约定解析
        grant("{\"resource\":" + tableResource("sales", "orders")
                + ",\"access\":\"SELECT\",\"principal\":\"role:perm_sales_writer\"}");

        Set<Privilege> effective = authorizationService.effectivePrivileges(
                "perm-bob", "paimon", GrantType.TABLE, List.of("sales"), "orders");
        assertTrue(effective.contains(Privilege.TABLE_READ_DATA),
                "role: 形式的直授应经同名 catalog role 生效");

        // 不持有该角色的主体不受影响
        assertTrue(authorizationService.effectivePrivileges(
                "perm-nobody", "paimon", GrantType.TABLE, List.of("sales"), "orders").isEmpty());
    }

    @Test
    void expiredAssignmentNoLongerGrants() throws Exception {
        grant("{\"resource\":" + tableResource("sales", "orders")
                + ",\"access\":\"SELECT\",\"principal\":\"user:carol\","
                + "\"expireTime\":\"2020-01-01T00:00:00Z\"}");

        assertTrue(authorizationService.effectivePrivileges(
                        "carol", "paimon", GrantType.TABLE, List.of("sales"), "orders").isEmpty(),
                "过期的直授不应再产生有效权限");
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
