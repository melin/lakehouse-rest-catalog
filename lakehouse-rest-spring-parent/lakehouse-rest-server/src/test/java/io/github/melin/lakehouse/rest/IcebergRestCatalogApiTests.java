package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.support.NamespacePaths;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
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

/**
 * Iceberg 协议面（{@code /v1/{prefix}/namespaces…}）的 HTTP 层集成测试。
 *
 * <p><b>为什么需要这一层，光有静态校验不够。</b>Iceberg 面的 26 个端点另有两条机器守卫：
 * {@code scripts/verify-iceberg-endpoints.py} 断言「声明的能力清单 ↔ 控制器映射」一一对应，
 * {@code CatalogEndpointAuthorizationTests} 断言每个端点都在授权映射表里登记过。
 * 那两条守的都是**形状**（路径、方法、清单），它们读的是映射与常量，
 * 从不真的发一个请求。而这一层守的是**行为**：状态码是不是规格要求的那个
 * （Iceberg 的 {@code rename} 是 204、Iceberg 的 {@code unregister} 是 200、
 * 两个 {@code HEAD} 是 204）、报文的字段名是不是 kebab-case、写路径有没有真的
 * 把 {@code metadata.json} 落到仓库里、错误体是不是嵌了一层 {@code error}
 * （清单与授权那两条都看不出来）。
 *
 * <p>本类只覆盖**行为**，格式门禁与 {@code /v1/config} 的形状在
 * {@link ManagementApiTests#catalogFormatDrivesProtocolSelection} 里
 * （那边要的是一个 Paimon catalog 做对照，放在那边不必再建第二个 catalog）。
 *
 * <p>用独立的 H2 库，避免与其它测试类共享内存库导致状态互相污染。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paimon-iceberg-api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IcebergRestCatalogApiTests {

    private static final String M = "/api/management/v1";

    /** 本类自己的 catalog。名字即 catalog API 的 prefix。 */
    private static final String CATALOG = "iceberg-api-test";
    private static final String BASE = "/v1/" + CATALOG;

    private static final String NS = "ice";
    /** 另一个 namespace，只用来验「删掉一个空的」这条路。 */
    private static final String EMPTY_NS = "ice_empty";

    private static final String TABLE = "orders";
    /** 多级 namespace：路径里按点号写（服务端同时接受规格默认的 {@code %1F}）。 */
    private static final String MULTI_NS = "acct.tax";

    /**
     * 建表用的 schema（Iceberg 形状，字段带 id / required / type）。
     *
     * <p>写成常量而不是在每处内联：这份 schema 有三处用到（建表、登记、对照断言），
     * 三份副本一旦漂移，失败的断言会指向错误的地方。
     */
    private static final String SCHEMA = """
            {"type":"struct","schema-id":0,"fields":[
              {"id":1,"name":"id","required":true,"type":"long"},
              {"id":2,"name":"data","required":false,"type":"string"}]}
            """;

    /**
     * 仓库根：本 catalog 的 {@code allowedLocations[0]}，也就是管理面理解的 warehouse。
     *
     * <p>用 {@code java.io.tmpdir} 而不是写死 {@code /tmp}：与
     * {@code application-test.yml} 同一个理由（仓库路径要随机器变化）。
     */
    private static final String WAREHOUSE =
            "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-server-tests/iceberg-api-test";

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

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        return json(expected, method, path, body, null);
    }

    /** {@code delegation} 非空时带上 {@code X-Iceberg-Access-Delegation}。 */
    private JsonNode json(int expected, String method, String path, String body, String delegation)
            throws Exception {
        MockHttpServletRequestBuilder request = builder(method, path);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (delegation != null) {
            request = request.header("X-Iceberg-Access-Delegation", delegation);
        }
        return call(expected, request);
    }

    /**
     * 与 {@link #json} 同，但不解析响应体。
     *
     * <p>{@code HEAD} 与 204 的响应体是空的，这条更直白；错误路径也无需求 JSON 树。
     */
    private void status(int expected, String method, String path) throws Exception {
        call(expected, builder(method, path));
    }

    /**
     * 按方法名造请求。
     *
     * <p>{@code HEAD} 必须显式写：Iceberg 的三个存在性检查端点是**显式登记的 HEAD 映射**
     * （而不是靠 Spring 对 {@code GET} 的隐式 HEAD 支持），因为规格要求它们的成功是 204，
     * 而隐式映射只给 200。见 {@code IcebergNamespaceController} 的类注释。
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

    private String createTableBody(String name) {
        return "{\"name\":\"" + name + "\",\"schema\":" + SCHEMA
                + ",\"properties\":{\"write.format.default\":\"parquet\"}}";
    }

    private String renameBody(String ns, String from, String to) {
        return "{\"source\":{\"namespace\":[\"" + ns + "\"],\"name\":\"" + from + "\"},"
                + "\"destination\":{\"namespace\":[\"" + ns + "\"],\"name\":\"" + to + "\"}}";
    }

    /**
     * 取一个 namespace 的层级。
     *
     * <p><b>两种形状都要认。</b>{@code ListNamespacesResponse} 的 {@code namespaces} 是
     * {@code List<List<String>>}，元素**本身就是数组**；而
     * {@code GetNamespaceResponse} / {@code CreateNamespaceResponse} 把层级放在
     * {@code namespace} 字段里。只写一种会让另一边的断言在 {@code get(String)} 上
     * 拿到 {@code null} 而崩，崩的位置与真正的差异（形状）无关。
     */
    private static List<String> levelsOf(JsonNode node) {
        JsonNode array = node.isArray() ? node : node.get("namespace");
        List<String> levels = new ArrayList<>();
        array.forEach(level -> levels.add(level.asString()));
        return levels;
    }

    /** {@code ListNamespacesResponse.namespaces} 逐项展开成层级列表。 */
    private static List<List<String>> namespacesOf(JsonNode namespaces) {
        List<List<String>> values = new ArrayList<>();
        namespaces.forEach(node -> values.add(levelsOf(node)));
        return values;
    }

    /**
     * 把仓库位置变成本地路径。
     *
     * <p>本类用 {@code FILE} 存储，于是 {@code file:///…} 能直接映射成本地文件——
     * 「删表有没有真的清目录」与「外部表有没有被误清」这两条只能落到文件系统上验，
     * 响应体里看不出任何区别。
     */
    /** 元数据文件里的 {@code metadata-location} 指向的那份文档就在仓库里。 */
    private static Path localFile(String location) {
        return Path.of(URI.create(location));
    }

    private String createIcebergCatalog(String name, String warehouse) throws Exception {
        JsonNode created = json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"ICEBERG","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}
                """.formatted(name, warehouse));
        assertEquals("ICEBERG", created.get("catalogFormat").asString());
        return created.get("name").asString();
    }

    // ------------------------------------------------------------------ 装配

    @Test
    @Order(1)
    void configDeclaresExactlyTheImplementedEndpoints() throws Exception {
        createIcebergCatalog(CATALOG, WAREHOUSE);

        JsonNode config = json(200, "GET", "/v1/config?warehouse=" + CATALOG, null);
        assertEquals(CATALOG, config.get("defaults").get("prefix").asString());
        assertEquals(WAREHOUSE, config.get("defaults").get("warehouse").asString());

        List<String> endpoints = new ArrayList<>();
        config.get("endpoints").forEach(node -> endpoints.add(node.asString()));
        assertEquals(26, endpoints.size(), "一期实现 26 个端点: " + endpoints);

        // 三个「明确不做」的能力一个都不能出现在清单里：声明了就等于承诺了它存在
        for (String unsupported : List.of("transactions/commit", "/plan", "/tasks", "/sign")) {
            assertFalse(endpoints.stream().anyMatch(endpoint -> endpoint.contains(unsupported)),
                    unsupported + " 未实现，不能出现在能力清单里: " + endpoints);
        }
        assertTrue(endpoints.contains("HEAD /v1/{prefix}/namespaces/{namespace}"),
                "能力清单里的字符串必须与 Iceberg 客户端的 Endpoint 逐字一致: " + endpoints);
    }

    // ------------------------------------------------------------------ namespace

    @Test
    @Order(2)
    void namespaceLifecycle() throws Exception {
        // 还没有任何 namespace
        assertEquals(List.of(), namespacesOf(json(200, "GET", BASE + "/namespaces", null).get("namespaces")));

        // 建
        JsonNode created = json(200, "POST", BASE + "/namespaces",
                "{\"namespace\":[\"" + NS + "\"],\"properties\":{\"owner\":\"qa\"}}");
        assertEquals(List.of(NS), levelsOf(created));
        assertEquals("qa", created.get("properties").get("owner").asString());

        // 读 / 存在性
        JsonNode loaded = json(200, "GET", BASE + "/namespaces/" + NS, null);
        assertEquals(List.of(NS), levelsOf(loaded));
        assertEquals("qa", loaded.get("properties").get("owner").asString());
        status(204, "HEAD", BASE + "/namespaces/" + NS);
        status(404, "HEAD", BASE + "/namespaces/does-not-exist");
        json(404, "GET", BASE + "/namespaces/does-not-exist", null);

        // 列举里出现了
        assertTrue(namespacesOf(json(200, "GET", BASE + "/namespaces", null).get("namespaces"))
                .contains(List.of(NS)));

        // 重名 409，错误体是嵌套一层的 Iceberg 形状
        JsonNode duplicate = json(409, "POST", BASE + "/namespaces", "{\"namespace\":[\"" + NS + "\"]}");
        assertNotNull(duplicate.get("error"), "Iceberg 侧的错误体必须嵌一层 error");
        assertEquals("AlreadyExistsException", duplicate.get("error").get("type").asString());
        assertEquals(409, duplicate.get("error").get("code").asInt());

        // 空层级 400：空白 level 会拼出 "a."，与 ["a"] 反解撞名
        json(400, "POST", BASE + "/namespaces", "{\"namespace\":[\"a\",\"\"]}");
        json(400, "POST", BASE + "/namespaces", "{\"namespace\":[]}");

        // properties 批量增删：改一个、删一个存在的、删一个不存在的
        JsonNode props = json(200, "POST", BASE + "/namespaces/" + NS + "/properties",
                "{\"removals\":[\"owner\",\"never-set\"],\"updates\":{\"tier\":\"gold\"}}");
        assertEquals(List.of("tier"), strings(props.get("updated")));
        assertEquals(List.of("owner"), strings(props.get("removed")));
        assertEquals(List.of("never-set"), strings(props.get("missing")),
                "规格允许部分成功，未命中的键要如实报告而不是当作已删除");
        assertEquals("gold", json(200, "GET", BASE + "/namespaces/" + NS, null)
                .get("properties").get("tier").asString());

        // 删一个空的：204
        json(200, "POST", BASE + "/namespaces", "{\"namespace\":[\"" + EMPTY_NS + "\"]}");
        status(204, "DELETE", BASE + "/namespaces/" + EMPTY_NS);
        status(404, "GET", BASE + "/namespaces/" + EMPTY_NS);
    }

    @Test
    @Order(3)
    void multiLevelNamespacesRoundTripOnBothSeparators() throws Exception {
        json(200, "POST", BASE + "/namespaces", "{\"namespace\":[\"acct\",\"tax\"]}");
        // 库里存点号拼接，反解回层级
        assertEquals(List.of("acct", "tax"), levelsOf(json(200, "GET", BASE + "/namespaces/" + MULTI_NS, null)));

        // 规格默认分隔符 0x1F（URL 编码 %1F）的解析钉在 NamespacePaths 上，而不是走 HTTP：
        // MockMvc 的请求根本没有经过真实容器的 URI 解码，%1F 会原样交给 @PathVariable，
        // 于是「HEAD %1F 路径 404」这条断言测到的是测试框架的行为，不是服务端的行为。
        assertEquals(List.of("acct", "tax"),
                NamespacePaths.parse("acct" + NamespacePaths.SEPARATOR + "tax"));
        assertEquals(List.of("acct", "tax"), NamespacePaths.parse("acct.tax"));

        // ?parent= 只列直接子级
        json(200, "POST", BASE + "/namespaces", "{\"namespace\":[\"acct\"]}");
        List<List<String>> children = namespacesOf(
                json(200, "GET", BASE + "/namespaces?parent=acct", null).get("namespaces"));
        assertEquals(List.of(List.of("acct", "tax")), children,
                "parent=acct 只应列出它下一层，不含 acct 自己与更深层");
    }

    // ------------------------------------------------------------------ table

    @Test
    @Order(4)
    void tableLifecycleWritesMetadataIntoTheWarehouse() throws Exception {
        assertEquals(List.of(), strings(json(200, "GET", BASE + "/namespaces/" + NS + "/tables", null)
                .get("identifiers")));

        JsonNode created = json(200, "POST", BASE + "/namespaces/" + NS + "/tables",
                createTableBody(TABLE));
        String metadataLocation = created.get("metadata-location").asString();
        assertTrue(metadataLocation.startsWith(WAREHOUSE + "/" + NS + ".db/" + TABLE + "/metadata/"),
                "元数据文件要落在按 path-template 推出来的表目录里: " + metadataLocation);
        assertTrue(metadataLocation.endsWith(".metadata.json"), metadataLocation);
        // 5 位补零的序号：人肉排障的第一步是 ls metadata/
        assertTrue(localFile(metadataLocation).getFileName().toString().startsWith("00001-"),
                "第一代元数据的序号是 00001: " + metadataLocation);
        assertTrue(Files.exists(localFile(metadataLocation)),
                "先写仓库、后落库：响应回来时那份 metadata.json 必须在仓库里（否则库里会指向一个不存在的文件）");

        // 响应里的 metadata 就是仓库里那一份的内容
        JsonNode metadata = created.get("metadata");
        assertEquals(2, metadata.get("format-version").asInt(),
                "建表默认 format-version 是 2（Iceberg 的默认值）");
        assertNotNull(metadata.get("table-uuid"));
        assertEquals(WAREHOUSE + "/" + NS + ".db/" + TABLE, metadata.get("location").asString());
        assertEquals("parquet", metadata.get("properties").get("write.format.default").asString());
        assertNotNull(metadata.get("properties").get("created-at"),
                "创建时刻由服务端补上，与 Iceberg 自己的服务端一致");
        // metadata-location 只在**顶层**有值：上游的 newTableMetadata 不填
        // metadataFileLocation，因此文档里那个字段缺席（或为 null）——位置由服务端这一层推，
        // 见 IcebergMetadataFiles。回程若是把位置也写进文档，客户端读到的就不再是
        // 仓库里那一份逐字节相同的元数据。
        assertTrue(metadata.get("metadata-location") == null || metadata.get("metadata-location").isNull(),
                "位置归顶层字段，文档里那份不该有值：文档要与仓库里那一份逐字节相同");

        // 读回来是**逐字节同一份**：库里存的文档原文
        JsonNode loaded = json(200, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE, null);
        assertEquals(metadataLocation, loaded.get("metadata-location").asString());
        assertEquals(metadata, loaded.get("metadata"));
        assertNull(loaded.get("storage-credentials"),
                "客户端没要凭据时这一项必须缺席——空数组与缺席在规格里含义不同");

        status(204, "HEAD", BASE + "/namespaces/" + NS + "/tables/" + TABLE);
        status(404, "HEAD", BASE + "/namespaces/" + NS + "/tables/nope");

        // 列举：identifier 的 namespace 是**数组**
        JsonNode identifiers = json(200, "GET", BASE + "/namespaces/" + NS + "/tables", null)
                .get("identifiers");
        assertEquals(1, identifiers.size());
        assertEquals(List.of(NS), levelsOf(identifiers.get(0)));
        assertEquals(TABLE, identifiers.get(0).get("name").asString());

        // 建表的各种拒绝路径
        json(409, "POST", BASE + "/namespaces/" + NS + "/tables", createTableBody(TABLE));
        json(400, "POST", BASE + "/namespaces/" + NS + "/tables", "{\"name\":\"no_schema\"}");
        json(404, "POST", BASE + "/namespaces/no_such_ns/tables", createTableBody("t"));
        json(404, "GET", BASE + "/namespaces/" + NS + "/tables/does-not-exist", null);

        // stage-create 没有提交路径（多表事务未实现），必须明说而不是静默忽略
        JsonNode staged = json(400, "POST", BASE + "/namespaces/" + NS + "/tables",
                "{\"name\":\"staged\",\"schema\":" + SCHEMA + ",\"stage-create\":true}");
        assertEquals("UnsupportedOperationException", staged.get("error").get("type").asString());
        status(404, "GET", BASE + "/namespaces/" + NS + "/tables/staged");

        // snapshots 参数只认 all / refs
        json(200, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE + "?snapshots=all", null);
        json(200, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE + "?snapshots=refs", null);
        json(400, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE + "?snapshots=nonsense", null);

        // 凭据：不问不给，问了才给，且 prefix 指向这张表的位置
        JsonNode delegated = json(200, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE, null,
                "vended-credentials");
        assertEquals(WAREHOUSE + "/" + NS + ".db/" + TABLE,
                delegated.get("storage-credentials").get(0).get("prefix").asString());
        JsonNode credentials = json(200, "GET",
                BASE + "/namespaces/" + NS + "/tables/" + TABLE + "/credentials", null);
        assertEquals(WAREHOUSE + "/" + NS + ".db/" + TABLE,
                credentials.get("storage-credentials").get(0).get("prefix").asString());

        // metrics：report-type 必填；快照对不上只记一条 warn，仍是 204
        json(400, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE + "/metrics", "{}");
        json(204, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE + "/metrics",
                "{\"report-type\":\"commit-report\",\"snapshot-id\":987654321}");
    }

    @Test
    @Order(5)
    void commitAppliesUpdatesAndEnforcesRequirements() throws Exception {
        JsonNode before = json(200, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE, null);
        String uuid = before.get("metadata").get("table-uuid").asString();
        String firstLocation = before.get("metadata-location").asString();

        // 空 updates：不产生新一代元数据，位置不动
        JsonNode empty = json(200, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE,
                "{\"requirements\":[],\"updates\":[]}");
        assertEquals(firstLocation, empty.get("metadata-location").asString(),
                "空提交不该写文件，否则「这一代的序号为什么跳了」要读代码才能回答");

        // requirements 与当前状态相符 -> 放行，序号走到 00002
        JsonNode committed = json(200, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE,
                "{\"requirements\":[{\"type\":\"assert-table-uuid\",\"uuid\":\"" + uuid + "\"}],"
                        + "\"updates\":[{\"action\":\"set-properties\",\"updates\":{\"tier\":\"gold\"}}]}");
        String secondLocation = committed.get("metadata-location").asString();
        assertTrue(localFile(secondLocation).getFileName().toString().startsWith("00002-"),
                "第二代元数据的序号是 00002: " + secondLocation);
        assertEquals("gold", committed.get("metadata").get("properties").get("tier").asString());
        assertTrue(Files.exists(localFile(secondLocation)), secondLocation);

        // requirements 对不上 -> 409 CommitFailedException（客户端据此 refresh 重试）
        JsonNode conflict = json(409, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE,
                "{\"requirements\":[{\"type\":\"assert-table-uuid\",\"uuid\":"
                        + "\"00000000-0000-0000-0000-000000000000\"}],\"updates\":[]}");
        assertEquals("CommitFailedException", conflict.get("error").get("type").asString());
        assertEquals(409, conflict.get("error").get("code").asInt());

        // 请求体里的 identifier 与路径不符 -> 400，不静默按路径执行
        json(400, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE,
                "{\"identifier\":{\"namespace\":[\"" + NS + "\"],\"name\":\"another_table\"},\"updates\":[]}");
        // 畸形 update -> 400 而不是 500
        json(400, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE,
                "{\"updates\":[{\"action\":\"nonsense\"}]}");
    }

    @Test
    @Order(6)
    void renameMovesTheNameWithoutMovingTheData() throws Exception {
        JsonNode before = json(200, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE, null);
        String location = before.get("metadata").get("location").asString();

        // Iceberg 的 rename 成功码是 204（Paimon 那套是 200）——这条路径登在 core，按格式各返各的
        json(204, "POST", BASE + "/tables/rename", renameBody(NS, TABLE, "orders_v2"));

        JsonNode renamed = json(200, "GET", BASE + "/namespaces/" + NS + "/tables/orders_v2", null);
        assertEquals(location, renamed.get("metadata").get("location").asString(),
                "rename 只改标识，不动数据位置");
        json(404, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE, null);

        // 目标名被占用 -> 409
        json(200, "POST", BASE + "/namespaces/" + NS + "/tables", createTableBody("occupied"));
        JsonNode taken = json(409, "POST", BASE + "/tables/rename", renameBody(NS, "orders_v2", "occupied"));
        assertEquals("AlreadyExistsException", taken.get("error").get("type").asString());
        // 报文残缺 -> 400（而不是在这里变成 500）
        json(400, "POST", BASE + "/tables/rename", "{\"source\":{\"namespace\":[\"" + NS + "\"]}}");

        // 改回原名，后面的用例继续用 TABLE 这个名字
        json(204, "POST", BASE + "/tables/rename", renameBody(NS, "orders_v2", TABLE));
        json(204, "POST", BASE + "/tables/rename", renameBody(NS, "occupied", "occupied2"));
    }

    @Test
    @Order(7)
    void registerAndUnregisterOnlyTouchTheCatalogEntry() throws Exception {
        JsonNode source = json(200, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE, null);
        String metadataLocation = source.get("metadata-location").asString();

        // 登记一张「元数据已在仓库里」的表：内容以仓库那一份为准
        JsonNode registered = json(200, "POST", BASE + "/namespaces/" + NS + "/register",
                "{\"name\":\"external_table\",\"metadata-location\":\"" + metadataLocation + "\"}");
        assertEquals(metadataLocation, registered.get("metadata-location").asString());
        assertEquals(source.get("metadata"), registered.get("metadata"));

        // 位置写错的 404 来自仓库（文件不存在），不是「表不存在」
        json(404, "POST", BASE + "/namespaces/" + NS + "/register",
                "{\"name\":\"nowhere\",\"metadata-location\":\"" + WAREHOUSE + "/no/such/file.json\"}");
        json(400, "POST", BASE + "/namespaces/" + NS + "/register", "{\"name\":\"no_location\"}");

        // 外部表永不清：即便显式 purge 也不动仓库
        status(204, "DELETE", BASE + "/namespaces/" + NS + "/tables/external_table?purgeRequested=true");
        assertTrue(Files.exists(localFile(metadataLocation)),
                "register 进来的表 external=true，purge 也不能删它的目录（那是不可逆的越权）");
        status(404, "GET", BASE + "/namespaces/" + NS + "/tables/external_table");

        // unregister 只摘目录条目，**绝不碰仓库**，且成功码是 200（不是 204）
        json(200, "POST", BASE + "/namespaces/" + NS + "/tables/" + TABLE + "/unregister", null);
        status(404, "GET", BASE + "/namespaces/" + NS + "/tables/" + TABLE);
        assertTrue(Files.exists(localFile(metadataLocation)),
                "unregister 的语义就是「本目录不再声明它」，仓库里的文件必须原样留着");

        // drop 的 purge 与部署开关取或：客户端显式要求就清目录
        Path tableDirectory = localFile(metadataLocation).getParent().getParent();
        json(200, "POST", BASE + "/namespaces/" + NS + "/register",
                "{\"name\":\"" + TABLE + "\",\"metadata-location\":\"" + metadataLocation + "\"}");
        status(204, "DELETE", BASE + "/namespaces/" + NS + "/tables/" + TABLE + "?purgeRequested=true");
        // 登记进来的表是 external，purge 一样不动它；这里验的是端点可用且状态码正确
        assertTrue(Files.exists(tableDirectory), "外部表（含刚登记回来的那张）的目录不该被删");
    }

    // ------------------------------------------------------------------ view

    @Test
    @Order(8)
    void viewLifecycle() throws Exception {
        assertEquals(List.of(), strings(json(200, "GET", BASE + "/namespaces/" + NS + "/views", null)
                .get("identifiers")));

        String viewVersion = """
                {"version-id":1,"timestamp-ms":1700000000000,"schema-id":0,
                 "summary":{"engine":"qa"},"default-catalog":"%s","default-namespace":["%s"],
                 "representations":[{"type":"sql","sql":"SELECT 1","dialect":"spark"}]}
                """.formatted(CATALOG, NS);

        JsonNode created = json(200, "POST", BASE + "/namespaces/" + NS + "/views",
                "{\"name\":\"v_orders\",\"schema\":" + SCHEMA + ",\"view-version\":" + viewVersion
                        + ",\"properties\":{\"comment\":\"qa\"}}");
        String location = created.get("metadata-location").asString();
        assertTrue(location.endsWith(".metadata.json"), location);
        assertTrue(Files.exists(localFile(location)));
        assertEquals("qa", created.get("metadata").get("properties").get("comment").asString(),
                "view 的 properties 要原样进元数据");

        // 读 / 存在性
        JsonNode loaded = json(200, "GET", BASE + "/namespaces/" + NS + "/views/v_orders", null);
        assertEquals(location, loaded.get("metadata-location").asString());
        status(204, "HEAD", BASE + "/namespaces/" + NS + "/views/v_orders");
        status(404, "HEAD", BASE + "/namespaces/" + NS + "/views/nope");

        assertEquals(List.of("v_orders"), strings(
                json(200, "GET", BASE + "/namespaces/" + NS + "/views", null).get("identifiers")));

        json(409, "POST", BASE + "/namespaces/" + NS + "/views",
                "{\"name\":\"v_orders\",\"schema\":" + SCHEMA + ",\"view-version\":" + viewVersion + "}");
        json(400, "POST", BASE + "/namespaces/" + NS + "/views", "{\"name\":\"v_no_schema\"}");

        // 提交一轮 update
        JsonNode committed = json(200, "POST", BASE + "/namespaces/" + NS + "/views/v_orders",
                "{\"requirements\":[],\"updates\":[{\"action\":\"set-properties\",\"updates\":{\"tier\":\"gold\"}}]}");
        assertTrue(committed.get("metadata-location").asString()
                        .contains("/metadata/00002-"),
                "视图与表用同一套元数据文件命名: " + committed.get("metadata-location").asString());

        // 同路径的 views/rename 也只改标识，成功码 204
        json(204, "POST", BASE + "/views/rename", renameBody(NS, "v_orders", "v_orders_v2"));
        json(200, "GET", BASE + "/namespaces/" + NS + "/views/v_orders_v2", null);
        json(404, "GET", BASE + "/namespaces/" + NS + "/views/v_orders", null);

        // 删视图只摘目录条目，不碰仓库（视图不拥有数据目录）
        String viewMetadata = json(200, "GET", BASE + "/namespaces/" + NS + "/views/v_orders_v2", null)
                .get("metadata-location").asString();
        status(204, "DELETE", BASE + "/namespaces/" + NS + "/views/v_orders_v2");
        json(404, "GET", BASE + "/namespaces/" + NS + "/views/v_orders_v2", null);
        assertTrue(Files.exists(localFile(viewMetadata)),
                "删视图不删元数据文件：视图位置可能与其他对象共用，按目录删会误删别人的文件");
    }

    // ------------------------------------------------------------------ 跨协议面的门禁

    @Test
    @Order(9)
    void droppingANonEmptyNamespaceIsRefused() throws Exception {
        // 规格要求非空时 409 NamespaceNotEmptyException，而不是静默级联删表
        JsonNode refused = json(409, "DELETE", BASE + "/namespaces/" + NS, null);
        assertEquals("NamespaceNotEmptyException", refused.get("error").get("type").asString());
        assertTrue(refused.get("error").get("message").asString().contains("is not empty"),
                refused.get("error").get("message").asString());
    }

    @Test
    @Order(10)
    void icebergPathsOnAPaimonCatalogReportTheFormatMismatch() throws Exception {
        json(201, "POST", M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"iceberg-api-paimon","catalogFormat":"PAIMON","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["file://%s/iceberg-api-paimon"]}}}
                """.formatted(System.getProperty("java.io.tmpdir")));

        // Iceberg 路径落在 Paimon catalog 上：错误体是嵌套形状（因为发出这条请求的客户端说的是
        // Iceberg 的话），消息点明这个 prefix 是什么格式
        JsonNode mismatch = json(404, "GET", "/v1/iceberg-api-paimon/namespaces", null);
        assertNotNull(mismatch.get("error"), "Iceberg 路径必须拿到 Iceberg 形状的错误体");
        assertEquals("NoSuchNamespaceException", mismatch.get("error").get("type").asString());
        assertTrue(mismatch.get("error").get("message").asString().contains("PAIMON catalog"),
                "消息要点出这个 prefix 是什么格式: " + mismatch.get("error").get("message").asString());
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(node -> values.add(node.isObject() ? node.get("name").asString() : node.asString()));
        return values;
    }
}
