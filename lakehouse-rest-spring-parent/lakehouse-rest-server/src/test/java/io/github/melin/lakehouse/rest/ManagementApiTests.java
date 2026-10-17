package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 管理 API（{@code /api/management/v1}）的 HTTP 层集成测试。
 *
 * <p>逐条覆盖 {@code spec/polaris-management-service.yml} 的 33 个 operation，
 * 并校验规格声明的状态码（创建 201、删除 204、版本冲突 409、资源缺失 404、
 * 参数不合法 400）。路径与动词属于契约的一部分，只能在 HTTP 层验证，
 * 因此本类走 MockMvc 而不是直接调用服务层。
 *
 * <p>测试自建 catalog {@value #CATALOG} 并在结束时删除，避免与 catalog API 的测试数据互相影响。
 *
 * <p>{@code test} profile 把数据源从默认的 MySQL 换成内存 H2，
 * 使本类不依赖本机是否有 MySQL 实例。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ManagementApiTests {

    private static final String CATALOG = "mgmt-test";
    private static final String M = "/api/management/v1";

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 工具

    /** 发一次请求，断言状态码，返回响应体。 */
    private JsonNode call(int expected, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status, body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
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
        return call(expected, request);
    }

    private String credentialsBody(String name) {
        return "{\"principal\":{\"name\":\"" + name + "\"}}";
    }

    private String roleBody(String name) {
        return "{\"principalRole\":{\"name\":\"" + name + "\"}}";
    }

    private String catalogRoleBody(String name) {
        return "{\"catalogRole\":{\"name\":\"" + name + "\"}}";
    }

    // ------------------------------------------------------------------ catalog

    @Test
    @Order(1)
    void catalogLifecycleAndVersioning() throws Exception {
        // 1 GET /catalogs
        assertTrue(json(200, "GET", M + "/catalogs", null).get("catalogs").isArray());

        // 2 POST /catalogs
        JsonNode created = json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/mgmt-test-wh"]}}}
                """.formatted(CATALOG));
        assertEquals(CATALOG, created.get("name").asString());
        assertEquals("FILE", created.get("storageConfigInfo").get("storageType").asString());
        assertEquals(List.of("file:///tmp/mgmt-test-wh"),
                mapper.convertValue(created.get("storageConfigInfo").get("allowedLocations"),
                        mapper.getTypeFactory().constructCollectionType(List.class, String.class)));
        int version = created.get("entityVersion").asInt();

        // 重名 409
        json(409, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":[]}}}
                """.formatted(CATALOG));

        // 未知 storageType 400
        json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"mgmt-bad","properties":{},
                 "storageConfigInfo":{"storageType":"HDFS","allowedLocations":[]}}}
                """);

        // 3 GET /catalogs/{catalogName}
        assertEquals(CATALOG, json(200, "GET", M + "/catalogs/" + CATALOG, null).get("name").asString());
        json(404, "GET", M + "/catalogs/does-not-exist", null);

        // 4 PUT /catalogs/{catalogName}
        JsonNode updated = json(200, "PUT", M + "/catalogs/" + CATALOG,
                "{\"currentEntityVersion\":" + version + ",\"properties\":{\"tier\":\"gold\"}}");
        assertEquals("gold", updated.get("properties").get("tier").asString());
        assertEquals(version + 1, updated.get("entityVersion").asInt());

        // 版本不符 409
        JsonNode conflict = json(409, "PUT", M + "/catalogs/" + CATALOG,
                "{\"currentEntityVersion\":9999,\"properties\":{}}");
        assertEquals(409, conflict.get("code").asInt());
        assertTrue(conflict.get("message").asString().contains("currentEntityVersion"));

        // storageConfigInfo 更新会同时改 warehouse，管理面与 catalog 面共享同一份数据
        json(200, "PUT", M + "/catalogs/" + CATALOG, """
                {"currentEntityVersion":%d,
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/mgmt-test-wh2"]}}
                """.formatted(version + 1));
        JsonNode config = json(200, "GET", "/v1/config?warehouse=" + CATALOG, null);
        assertEquals("file:///tmp/mgmt-test-wh2", config.get("defaults").get("warehouse").asString());
        assertEquals(CATALOG, config.get("defaults").get("prefix").asString());
    }

    // ------------------------------------------------------------------ principal

    @Test
    @Order(2)
    void principalLifecycleAndCredentials() throws Exception {
        // 6 GET /principals
        assertTrue(json(200, "GET", M + "/principals", null).get("principals").isArray());

        // 7 POST /principals：响应含明文密钥
        JsonNode created = json(201, "POST", M + "/principals", credentialsBody("mgmt-alice"));
        JsonNode credentials = created.get("credentials");
        assertNotNull(credentials.get("clientId"));
        assertNotNull(credentials.get("clientSecret"));
        assertEquals("mgmt-alice", created.get("principal").get("name").asString());
        String clientId = credentials.get("clientId").asString();
        String secret = credentials.get("clientSecret").asString();

        // 重名 409
        json(409, "POST", M + "/principals", credentialsBody("mgmt-alice"));
        // 缺 name 400
        json(400, "POST", M + "/principals", "{\"principal\":{}}");

        // 8 GET /principals/{principalName}：不含密钥
        JsonNode fetched = json(200, "GET", M + "/principals/mgmt-alice", null);
        assertEquals(clientId, fetched.get("clientId").asString());
        assertFalse(fetched.has("credentials"), "GET 响应不应包含凭据");
        json(404, "GET", M + "/principals/nope", null);

        // 9 PUT /principals/{principalName}
        int version = fetched.get("entityVersion").asInt();
        JsonNode updated = json(200, "PUT", M + "/principals/mgmt-alice",
                "{\"currentEntityVersion\":" + version + ",\"properties\":{\"dept\":\"science\"}}");
        assertEquals("science", updated.get("properties").get("dept").asString());
        json(409, "PUT", M + "/principals/mgmt-alice",
                "{\"currentEntityVersion\":9999,\"properties\":{}}");
        json(400, "PUT", M + "/principals/mgmt-alice", "{\"properties\":{}}");

        // 11 POST /principals/{principalName}/rotate：clientId 保留，密钥更换
        JsonNode rotated = json(200, "POST", M + "/principals/mgmt-alice/rotate", null);
        assertEquals(clientId, rotated.get("credentials").get("clientId").asString());
        assertFalse(secret.equals(rotated.get("credentials").get("clientSecret").asString()),
                "rotate 后密钥应当变化");

        // 12 POST /principals/{principalName}/reset
        JsonNode reset = json(200, "POST", M + "/principals/mgmt-alice/reset", "{}");
        assertNotNull(reset.get("credentials").get("clientId"));
        JsonNode fixed = json(200, "POST", M + "/principals/mgmt-alice/reset",
                "{\"clientId\":\"fixed-id\",\"clientSecret\":\"fixed-secret\"}");
        assertEquals("fixed-id", fixed.get("principal").get("clientId").asString());
        json(404, "POST", M + "/principals/nope/reset", "{}");
    }

    // ------------------------------------------------------------------ principal role

    @Test
    @Order(3)
    void principalRoleLifecycleAndAssignment() throws Exception {
        // 16 GET /principal-roles
        assertTrue(json(200, "GET", M + "/principal-roles", null).get("roles").isArray());

        // 17 POST /principal-roles
        JsonNode created = json(201, "POST", M + "/principal-roles",
                "{\"principalRole\":{\"name\":\"mgmt_engineer\",\"properties\":{\"tier\":\"1\"}}}");
        assertEquals("mgmt_engineer", created.get("name").asString());
        assertFalse(created.get("federated").asBoolean());
        json(409, "POST", M + "/principal-roles", roleBody("mgmt_engineer"));
        json(400, "POST", M + "/principal-roles", "{\"principalRole\":{}}");

        // 18 GET /principal-roles/{principalRoleName}
        JsonNode fetched = json(200, "GET", M + "/principal-roles/mgmt_engineer", null);
        json(404, "GET", M + "/principal-roles/nope", null);

        // 19 PUT /principal-roles/{principalRoleName}
        int version = fetched.get("entityVersion").asInt();
        JsonNode updated = json(200, "PUT", M + "/principal-roles/mgmt_engineer",
                "{\"currentEntityVersion\":" + version + ",\"properties\":{\"tier\":\"2\"}}");
        assertEquals("2", updated.get("properties").get("tier").asString());
        json(409, "PUT", M + "/principal-roles/mgmt_engineer",
                "{\"currentEntityVersion\":9999,\"properties\":{}}");

        // 14 PUT /principals/{p}/principal-roles：被授予角色名在请求体里
        json(201, "PUT", M + "/principals/mgmt-alice/principal-roles", roleBody("mgmt_engineer"));
        json(201, "PUT", M + "/principals/mgmt-alice/principal-roles", roleBody("mgmt_engineer"));
        json(400, "PUT", M + "/principals/mgmt-alice/principal-roles", "{}");
        json(404, "PUT", M + "/principals/mgmt-alice/principal-roles", roleBody("ghost"));

        // 13 GET /principals/{p}/principal-roles
        JsonNode roles = json(200, "GET", M + "/principals/mgmt-alice/principal-roles", null);
        assertEquals(1, roles.get("roles").size());
        assertEquals("mgmt_engineer", roles.get("roles").get(0).get("name").asString());

        // 21 GET /principal-roles/{r}/principals
        JsonNode principals = json(200, "GET", M + "/principal-roles/mgmt_engineer/principals", null);
        assertEquals(1, principals.get("principals").size());
        assertEquals("mgmt-alice", principals.get("principals").get(0).get("name").asString());
    }

    // ------------------------------------------------------------------ catalog role 与授权

    @Test
    @Order(4)
    void catalogRoleLifecycleAndGrants() throws Exception {
        // 25 GET /catalogs/{c}/catalog-roles
        assertTrue(json(200, "GET", M + "/catalogs/" + CATALOG + "/catalog-roles", null)
                .get("roles").isArray());
        json(404, "GET", M + "/catalogs/does-not-exist/catalog-roles", null);

        // 26 POST /catalogs/{c}/catalog-roles
        JsonNode created = json(201, "POST", M + "/catalogs/" + CATALOG + "/catalog-roles",
                "{\"catalogRole\":{\"name\":\"mgmt_reader\",\"properties\":{\"scope\":\"read\"}}}");
        assertEquals("mgmt_reader", created.get("name").asString());
        json(409, "POST", M + "/catalogs/" + CATALOG + "/catalog-roles", catalogRoleBody("mgmt_reader"));
        json(201, "POST", M + "/catalogs/" + CATALOG + "/catalog-roles", catalogRoleBody("mgmt_writer"));

        // 27 GET /catalogs/{c}/catalog-roles/{r}
        JsonNode fetched = json(200, "GET",
                M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_reader", null);
        json(404, "GET", M + "/catalogs/" + CATALOG + "/catalog-roles/ghost", null);

        // 28 PUT /catalogs/{c}/catalog-roles/{r}
        int version = fetched.get("entityVersion").asInt();
        json(200, "PUT", M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_reader",
                "{\"currentEntityVersion\":" + version + ",\"properties\":{\"scope\":\"read-only\"}}");
        json(409, "PUT", M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_reader",
                "{\"currentEntityVersion\":9999,\"properties\":{}}");

        String grants = M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_reader/grants";
        // 32 PUT grants：新增（catalog / namespace / table 三种层级）
        json(201, "PUT", grants, "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"CATALOG_MANAGE_ACCESS\"}}");
        json(201, "PUT", grants, """
                {"grant":{"type":"namespace","namespace":["silver"],"privilege":"NAMESPACE_LIST"}}""");
        json(201, "PUT", grants, """
                {"grant":{"type":"table","namespace":["silver","sales"],"tableName":"orders",
                          "privilege":"TABLE_READ_PROPERTIES"}}""");
        // 幂等
        json(201, "PUT", grants, """
                {"grant":{"type":"table","namespace":["silver","sales"],"tableName":"orders",
                          "privilege":"TABLE_READ_PROPERTIES"}}""");
        // 参数错误
        json(400, "PUT", grants, "{\"grant\":{\"type\":\"column\",\"privilege\":\"TABLE_LIST\"}}");
        json(400, "PUT", grants, """
                {"grant":{"type":"table","namespace":["a"],"tableName":"t","privilege":"POLICY_READ"}}""");
        json(400, "PUT", grants, """
                {"grant":{"type":"table","namespace":["a"],"privilege":"TABLE_LIST"}}""");
        json(400, "PUT", grants, "{}");

        // 31 GET grants
        JsonNode listed = json(200, "GET", grants, null);
        assertEquals(3, listed.get("grants").size());
        boolean hasTableGrant = false;
        for (JsonNode grant : listed.get("grants")) {
            if ("table".equals(grant.get("type").asString())) {
                hasTableGrant = true;
                // 对象名字段随类型变化：table 用 tableName 而非统一的 objectName
                assertEquals("orders", grant.get("tableName").asString());
                assertEquals("silver", grant.get("namespace").get(0).asString());
            }
        }
        assertTrue(hasTableGrant, "列表应含 table 级授权");

        // 33 POST grants：撤销
        json(201, "POST", grants, """
                {"grant":{"type":"table","namespace":["silver","sales"],"tableName":"orders",
                          "privilege":"TABLE_READ_PROPERTIES"}}""");
        assertEquals(2, json(200, "GET", grants, null).get("grants").size());
        json(404, "POST", grants, """
                {"grant":{"type":"view","namespace":["silver"],"viewName":"v","privilege":"VIEW_LIST"}}""");

        // 23 PUT /principal-roles/{pr}/catalog-roles/{c}
        String assign = M + "/principal-roles/mgmt_engineer/catalog-roles/" + CATALOG;
        json(201, "PUT", assign, catalogRoleBody("mgmt_reader"));
        json(201, "PUT", assign, catalogRoleBody("mgmt_reader"));
        json(404, "PUT", assign, catalogRoleBody("ghost"));

        // 22 GET /principal-roles/{pr}/catalog-roles/{c}：只回该 principal role 被授予的角色
        JsonNode assigned = json(200, "GET", assign, null);
        assertEquals(1, assigned.get("roles").size());
        assertEquals("mgmt_reader", assigned.get("roles").get(0).get("name").asString());

        // 30 GET /catalogs/{c}/catalog-roles/{r}/principal-roles
        JsonNode owners = json(200, "GET",
                M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_reader/principal-roles", null);
        assertEquals(1, owners.get("roles").size());
        assertEquals("mgmt_engineer", owners.get("roles").get(0).get("name").asString());
    }

    // ------------------------------------------------------------------ 删除与级联

    @Test
    @Order(5)
    void deletionSemantics() throws Exception {
        // 24 DELETE /principal-roles/{pr}/catalog-roles/{c}/{r}
        String assign = M + "/principal-roles/mgmt_engineer/catalog-roles/" + CATALOG;
        json(204, "DELETE", assign + "/mgmt_reader", null);
        json(404, "DELETE", assign + "/mgmt_reader", null);

        // 15 DELETE /principals/{p}/principal-roles/{r}
        json(204, "DELETE", M + "/principals/mgmt-alice/principal-roles/mgmt_engineer", null);
        json(404, "DELETE", M + "/principals/mgmt-alice/principal-roles/mgmt_engineer", null);

        // 29 DELETE /catalogs/{c}/catalog-roles/{r}
        json(204, "DELETE", M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_writer", null);
        json(204, "DELETE", M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_reader", null);
        json(404, "DELETE", M + "/catalogs/" + CATALOG + "/catalog-roles/mgmt_reader", null);

        // 20 DELETE /principal-roles/{r}
        json(204, "DELETE", M + "/principal-roles/mgmt_engineer", null);
        json(404, "DELETE", M + "/principal-roles/mgmt_engineer", null);

        // 10 DELETE /principals/{p}
        json(204, "DELETE", M + "/principals/mgmt-alice", null);
        json(404, "DELETE", M + "/principals/mgmt-alice", null);
    }

    @Test
    @Order(6)
    void deletingCatalogCascadesDatabasesAndRoles() throws Exception {
        // 先在待删 catalog 下建库、建角色，验证级联
        json(200, "POST", "/v1/" + CATALOG + "/databases", "{\"name\":\"cascade_db\"}");
        json(201, "POST", M + "/catalogs/" + CATALOG + "/catalog-roles", catalogRoleBody("cascade_role"));
        json(201, "PUT", M + "/catalogs/" + CATALOG + "/catalog-roles/cascade_role/grants",
                "{\"grant\":{\"type\":\"catalog\",\"privilege\":\"CATALOG_READ_PROPERTIES\"}}");

        // 5 DELETE /catalogs/{catalogName}
        json(204, "DELETE", M + "/catalogs/" + CATALOG, null);
        json(404, "GET", M + "/catalogs/" + CATALOG, null);
        // 库随 catalog 一起消失
        json(404, "GET", "/v1/" + CATALOG + "/databases/cascade_db", null);
    }

    // ------------------------------------------------------------------ 表格式

    /**
     * catalog 的**表格式**：管理面能读能建、不能改；它决定这个 prefix 服务哪一套协议。
     *
     * <p>本用例走的是 HTTP，因此顺带覆盖了三件只有装配起来才能验的事：
     * {@code /v1/config} 的响应形状按格式二选一、错误响应形状按格式二选一、
     * 以及格式不匹配时那条「点出真正原因」的消息。这三件事都在管理 API 之外的
     * 代码里，但它们的输入只有一个——管理面写下的那个字段。
     */
    @Test
    @Order(7)
    void catalogFormatDrivesProtocolSelection() throws Exception {
        // 省略 catalogFormat -> 用部署默认格式（test profile 不改 default-catalog-format）
        JsonNode defaulted = json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"fmt-default","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/fmt-default"]}}}
                """);
        assertEquals("PAIMON", defaulted.get("catalogFormat").asString());

        // 显式 ICEBERG
        JsonNode iceberg = json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"fmt-iceberg","catalogFormat":"ICEBERG","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file:///tmp/fmt-iceberg"]}}}
                """);
        assertEquals("ICEBERG", iceberg.get("catalogFormat").asString());
        // 建完就落库了：GET 回同一个值
        assertEquals("ICEBERG", json(200, "GET", M + "/catalogs/fmt-iceberg", null)
                .get("catalogFormat").asString());

        // 认不出来的取值 400，不让脏值进库（否则该 catalog 的所有请求都会被按 Paimon 解释）
        json(400, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"fmt-typo","catalogFormat":"ICEBURG","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":[]}}}
                """);

        // 格式不可变：改一个已有 catalog 的格式 -> 400，消息点明「要换就删除重建」
        JsonNode refused = json(400, "PUT", M + "/catalogs/fmt-iceberg",
                "{\"catalogFormat\":\"PAIMON\"}");
        assertTrue(refused.get("message").asString().contains("immutable"),
                "消息应说明格式不可变: " + refused.get("message").asString());
        // 原样带回同一个值则放行（管理面的 GET -> 改字段 -> PUT 往返会带上它）
        json(200, "PUT", M + "/catalogs/fmt-iceberg",
                "{\"catalogFormat\":\"ICEBERG\",\"properties\":{\"tier\":\"silver\"}}");
        assertEquals("ICEBERG", json(200, "GET", M + "/catalogs/fmt-iceberg", null)
                .get("catalogFormat").asString());

        // /v1/config 的形状随格式变：Iceberg 必须带 endpoints，Paimon 不带
        JsonNode icebergConfig = json(200, "GET", "/v1/config?warehouse=fmt-iceberg", null);
        assertEquals("fmt-iceberg", icebergConfig.get("defaults").get("prefix").asString());
        assertTrue(icebergConfig.get("endpoints").isArray(), "Iceberg 的 /v1/config 必须发能力清单");
        // 不发 endpoints 时客户端会假定一整套默认端点存在，其中包含多表事务
        assertFalse(strings(icebergConfig.get("endpoints")).contains("POST /v1/{prefix}/transactions/commit"),
                "不实现的多表事务不能出现在能力清单里");

        JsonNode paimonConfig = json(200, "GET", "/v1/config?warehouse=fmt-default", null);
        assertEquals("fmt-default", paimonConfig.get("defaults").get("prefix").asString());
        assertNull(paimonConfig.get("endpoints"), "Paimon 的 /v1/config 不发 endpoints");

        // 走错面的客户端拿到的必须是「格式不对」，而不是「没有这个东西」——
        // 否则它会以为是自己把库名写错了，再试一百次也不会对。
        // 这里是 Paimon 独有的路径，因此错误是**扁平**形状：形状按路径定（客户端拼出
        // 这个路径就说明它本来在说 Paimon 的话），不按 catalog 的格式定
        JsonNode mismatch = json(404, "GET", "/v1/fmt-iceberg/databases", null);
        assertNull(mismatch.get("error"), "Paimon 路径上的错误是扁平形状");
        assertTrue(mismatch.get("message").asString().contains("ICEBERG catalog"),
                "消息要点出这个 prefix 是什么格式: " + mismatch.get("message").asString());

        // 反向：Paimon catalog 上的 Iceberg 路径 -> 嵌套形状 + 同样的明确消息
        JsonNode reverse = json(404, "GET", "/v1/fmt-default/namespaces", null);
        assertTrue(reverse.get("error").get("message").asString().contains("PAIMON catalog"),
                "消息要点出这个 prefix 是什么格式: " + reverse.get("error").get("message").asString());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.asString()));
        return values;
    }
}
