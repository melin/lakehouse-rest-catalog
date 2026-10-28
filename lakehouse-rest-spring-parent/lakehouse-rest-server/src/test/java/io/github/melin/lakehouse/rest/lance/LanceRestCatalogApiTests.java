package io.github.melin.lakehouse.rest.lance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Lance 协议面（{@code /lance/v1/**}）的 HTTP 层集成测试。
 *
 * <p>守的是**行为**而不是形状：状态码是不是 {@code code} 算出来的那一个
 * （{@code code 0 → 406}、{@code 3 → 409}、{@code 13 → 400}）、错误体是不是
 * 规范那四个字段、{@code declare} 有没有真的**不写存储**、{@code register} 的弱校验
 * 有没有真的去看 {@code _versions/}。端点清单与授权映射另有守卫，它们读的是映射与常量，
 * 从不真的发一个请求。
 *
 * <p><b>{@code id} 里的 {@code $} 在 URL 里写成 {@code %24}</b>——这不是编码偏好，
 * 而是协议形状：客户端把三级标识拼成一个串放进路径。测试里照客户端的样子发，
 * 才能覆盖「分隔符」这条最容易写死的规则。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paimon-lance-api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LanceRestCatalogApiTests {

    private static final String M = "/api/management/v1";
    private static final String CATALOG = "lance-api-test";
    private static final String SCHEMA = "lance";
    private static final String TABLE = "orders";

    /** 路径里的 {@code id}：{@code $} 编码成 {@code %24}。 */
    private static final String NS_ID = CATALOG + "%24" + SCHEMA;
    private static final String TABLE_ID = NS_ID + "%24" + TABLE;
    private static final String MISSING_TABLE_ID = NS_ID + "%24nope";

    private static final String WAREHOUSE =
            "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-server-tests/lance-api-test";

    @Autowired
    private MockMvc mockMvc;

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

    /**
     * 按路径造请求。
     *
     * <p><b>走 {@link java.net#URI} 而不是字符串模板</b>：MockMvc 会把字符串模板再编码一次，
     * 于是 {@code %24} 变成 {@code %2524}，服务端看到的是「字面上的 %24」而不是
     * 分隔符 {@code $}。真客户端发的是前者，测试必须发同样的东西。
     */
    private MockHttpServletRequestBuilder postAt(String path) {
        return post(URI.create(path));
    }

    private MockHttpServletRequestBuilder getAt(String path) {
        return get(URI.create(path));
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = postAt(path);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request);
    }

    private JsonNode getJson(int expected, String path) throws Exception {
        return call(expected, getAt(path));
    }

    /** 断言 Lance 错误体的四个字段与 code→HTTP 的对应关系。 */
    private void assertLanceError(JsonNode node, int code) {
        assertEquals(code, node.get("code").asInt(), "body=" + node);
        assertTrue(node.has("error"), "error 字段必须出现: " + node);
        assertTrue(node.has("detail") || node.get("detail") == null || node.get("detail").isNull(),
                "detail 可空但键要在: " + node);
        assertTrue(node.has("instance"), "instance 填请求路径: " + node);
    }

    // ------------------------------------------------------------------ 装配

    @Test
    @Order(1)
    void lanceCatalogIsCreatedAndNamespaceRoundTrips() throws Exception {
        JsonNode created = json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"LANCE","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}
                """.formatted(CATALOG, WAREHOUSE));
        assertEquals("LANCE", created.get("catalogFormat").asString());

        // create
        JsonNode ns = json(200, "POST", "/lance/v1/namespace/" + NS_ID + "/create",
                "{\"properties\":{\"owner\":\"melin\"}}");
        assertEquals(CATALOG + "$" + SCHEMA, ns.get("id").asString());
        assertEquals("melin", ns.get("properties").get("owner").asString());

        // create 重复 → 409 / code 2
        assertLanceError(json(409, "POST", "/lance/v1/namespace/" + NS_ID + "/create",
                "{\"properties\":{}}"), 2);
        // exist_ok → 200
        json(200, "POST", "/lance/v1/namespace/" + NS_ID + "/create?mode=exist_ok",
                "{\"properties\":{}}");
        // overwrite → 重置属性，id 不变
        JsonNode overwritten = json(200, "POST",
                "/lance/v1/namespace/" + NS_ID + "/create?mode=overwrite",
                "{\"properties\":{\"owner\":\"other\"}}");
        assertEquals("other", overwritten.get("properties").get("owner").asString());

        // describe / exists
        JsonNode described = json(200, "POST", "/lance/v1/namespace/" + NS_ID + "/describe", null);
        assertEquals(CATALOG + "$" + SCHEMA, described.get("id").asString());
        call(200, postAt("/lance/v1/namespace/" + NS_ID + "/exists"));

        // list：新 schema 出现在直接子级里
        JsonNode listed = getJson(200, "/lance/v1/namespace/" + CATALOG + "/list");
        List<String> ids = new ArrayList<>();
        listed.get("namespaces").forEach(node -> ids.add(node.get("id").asString()));
        assertTrue(ids.contains(CATALOG + "$" + SCHEMA), "namespaces=" + ids);
    }

    @Test
    @Order(2)
    void namespaceErrorsUseTheLanceErrorModel() throws Exception {
        // 不存在的 schema：404 / code 1（NamespaceNotFound）
        assertLanceError(json(404, "POST",
                "/lance/v1/namespace/" + CATALOG + "%24nope/describe", null), 1);
        call(404, postAt("/lance/v1/namespace/" + CATALOG + "%24nope/exists"));
        // 第一级（catalog）上的写操作：406，Lance API 不建 catalog（决策 D2）
        assertLanceError(json(406, "POST", "/lance/v1/namespace/" + CATALOG + "/create",
                "{\"properties\":{}}"), 0);
        // Cascade 删除：406
        assertLanceError(json(406, "POST",
                "/lance/v1/namespace/" + NS_ID + "/drop?mode=cascade", null), 0);
        // 非法 id：400 / code 13
        assertLanceError(json(400, "POST",
                "/lance/v1/namespace/" + CATALOG + "%24/describe", null), 13);
        // 路径与 body 不一致：400
        assertLanceError(json(400, "POST", "/lance/v1/namespace/" + NS_ID + "/create",
                "{\"id\":\"other$schema\"}"), 13);
    }

    // ------------------------------------------------------------------ 表

    @Test
    @Order(3)
    void declareDescribeAndDropRegistration() throws Exception {
        JsonNode declared = json(200, "POST", "/lance/v1/table/" + TABLE_ID + "/declare",
                "{\"properties\":{\"lance.encoding\":\"v2\"}}");
        String location = declared.get("location").asString();
        assertTrue(location.startsWith(WAREHOUSE), "location=" + location);
        assertTrue(location.endsWith(".lance"), "location=" + location);
        assertEquals("v2", declared.get("properties").get("lance.encoding").asString());
        // declare 不写存储：目录此时还不存在
        assertFalse(Files.exists(Path.of(URI.create(location))), "declare 不能碰存储");

        // 重复 declare → 409 / code 5；exist_ok → 200
        assertLanceError(json(409, "POST", "/lance/v1/table/" + TABLE_ID + "/declare",
                "{\"properties\":{}}"), 5);
        json(200, "POST", "/lance/v1/table/" + TABLE_ID + "/declare?mode=exist_ok",
                "{\"properties\":{}}");

        // describe
        JsonNode described = json(200, "POST", "/lance/v1/table/" + TABLE_ID + "/describe", null);
        assertEquals(location, described.get("location").asString());
        assertEquals("v2", described.get("properties").get("lance.encoding").asString());
        assertTrue(described.has("storage_options"), "storage_options 是这一面唯一的数据面控制点");
        // 默认不探测 → 三态里的「不知道」
        assertTrue(!described.has("is_only_declared") || described.get("is_only_declared").isNull(),
                "未要求检查时不能给出真值: " + described);
        // check_declared=true 且目录还没有 → true
        JsonNode checked = json(200, "POST",
                "/lance/v1/table/" + TABLE_ID + "/describe?check_declared=true", null);
        assertTrue(checked.get("is_only_declared").asBoolean());
        // with_table_uri=true → 规范化成合法 URI
        JsonNode uri = json(200, "POST",
                "/lance/v1/table/" + TABLE_ID + "/describe?with_table_uri=true", null);
        assertTrue(uri.get("table_uri").asString().contains("://"), uri.toString());

        // load_detailed_metadata=true → P0 显式 406
        assertLanceError(json(406, "POST",
                "/lance/v1/table/" + TABLE_ID + "/describe?load_detailed_metadata=true", null), 0);

        // exists
        call(200, postAt("/lance/v1/table/" + TABLE_ID + "/exists"));
        assertLanceError(json(404, "POST", "/lance/v1/table/" + MISSING_TABLE_ID + "/describe", null), 4);

        // 列举：回完整 id，不是裸表名
        JsonNode listed = getJson(200, "/lance/v1/namespace/" + NS_ID + "/table/list");
        List<String> tables = new ArrayList<>();
        listed.get("tables").forEach(node -> tables.add(node.asString()));
        assertTrue(tables.contains(CATALOG + "$" + SCHEMA + "$" + TABLE), "tables=" + tables);

        // 跨命名空间列举必须带 catalog 前缀
        JsonNode search = getJson(200, "/lance/v1/table/" + CATALOG);
        assertTrue(search.get("tables").toString().contains(TABLE));
        assertLanceError(getJson(400, "/lance/v1/table"), 13);
    }

    @Test
    @Order(4)
    void renameStaysInsideTheCatalog() throws Exception {
        JsonNode renamed = json(200, "POST", "/lance/v1/table/" + TABLE_ID + "/rename",
                "{\"new_name\":\"orders_2026\"}");
        assertEquals(CATALOG + "$" + SCHEMA + "$orders_2026", renamed.get("id").asString());
        call(404, postAt("/lance/v1/table/" + TABLE_ID + "/exists"));

        // 跨 catalog → 400
        assertLanceError(json(400, "POST", "/lance/v1/table/" + NS_ID + "%24orders_2026/rename",
                "{\"new_name\":\"other$" + SCHEMA + "$orders\"}"), 13);

        // 改回去，后面的用例还用 orders
        json(200, "POST", "/lance/v1/table/" + NS_ID + "%24orders_2026/rename",
                "{\"new_name\":\"" + TABLE + "\"}");
    }

    @Test
    @Order(5)
    void registerRequiresAnExistingDataset() throws Exception {
        // 没有 manifest 的位置 → 400（弱校验）
        assertLanceError(json(400, "POST", "/lance/v1/table/" + NS_ID + "%24ghost/register",
                "{\"location\":\"" + WAREHOUSE + "/lance.db/ghost.lance\"}"), 13);
        // 没有 location → 400
        assertLanceError(json(400, "POST", "/lance/v1/table/" + NS_ID + "%24ghost/register",
                "{\"properties\":{}}"), 13);

        // 造一个最小数据集（一个 manifest 文件），register 应当接受
        Path dataset = Path.of(URI.create(WAREHOUSE + "/lance.db/registered.lance"));
        Files.createDirectories(dataset.resolve("_versions"));
        Files.writeString(dataset.resolve("_versions/1.manifest"), "proto");
        assertTrue(Files.exists(dataset.resolve("_versions/1.manifest")),
                "测试夹具自己要能写出 manifest: " + dataset);
        JsonNode registered = json(200, "POST", "/lance/v1/table/" + NS_ID + "%24registered/register",
                "{\"location\":\"" + WAREHOUSE + "/lance.db/registered.lance\"}");
        assertEquals(WAREHOUSE + "/lance.db/registered.lance", registered.get("location").asString());
        // 已登记的表，check_declared=true 应当看到 false
        JsonNode checked = json(200, "POST",
                "/lance/v1/table/" + NS_ID + "%24registered/describe?check_declared=true", null);
        assertFalse(checked.get("is_only_declared").asBoolean());
    }

    @Test
    @Order(6)
    void unimplementedOperationsAnswerWith406() throws Exception {
        for (String operation : List.of("insert", "query", "create", "drop",
                "version/list", "alter_columns", "index/create")) {
            JsonNode failure = json(406, "POST",
                    "/lance/v1/table/" + TABLE_ID + "/" + operation, "{}");
            assertLanceError(failure, 0);
            assertTrue(failure.get("error").asString().contains(operation),
                    "错误文案要指名是哪个 operation 不做: " + failure);
        }
    }

    @Test
    @Order(7)
    void deregisterKeepsTheDataAndReleasesTheNamespace() throws Exception {
        // 先清掉 register 进来的那张表，命名空间才能删
        json(200, "POST", "/lance/v1/table/" + NS_ID + "%24registered/deregister", null);
        call(404, postAt("/lance/v1/table/" + NS_ID + "%24registered/exists"));

        // 非空 → 409 / code 3
        assertLanceError(json(409, "POST", "/lance/v1/namespace/" + NS_ID + "/drop", null), 3);

        // deregister 只删登记：响应带回 location 与属性，数据目录仍在
        JsonNode removed = json(200, "POST",
                "/lance/v1/table/" + TABLE_ID + "/deregister", null);
        assertTrue(removed.get("location").asString().endsWith(".lance"));
        call(404, postAt("/lance/v1/table/" + TABLE_ID + "/exists"));

        json(200, "POST", "/lance/v1/namespace/" + NS_ID + "/drop", null);
        call(404, postAt("/lance/v1/namespace/" + NS_ID + "/exists"));
    }
}
