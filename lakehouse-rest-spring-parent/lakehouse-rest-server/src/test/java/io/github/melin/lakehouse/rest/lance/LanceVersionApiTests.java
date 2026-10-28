package io.github.melin.lakehouse.rest.lance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.lance.support.LanceVersionNaming;
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
 * 表版本的 HTTP 层测试（P1 的读两个 + P2 的写两个）。
 *
 * <p>与 {@link LanceRestCatalogApiTests} 分工：那边守登记语义，
 * 这边守「版本从哪来」。最关键的一条断言是**版本有两个来源**——存储里的
 * manifest 文件（原生）与库里的版本账（托管），列举时并集、按版本号去重、
 * 托管优先。只验其中一种会漏掉「切换管理方式之后历史版本还在目录里」
 * 这个真实存在的场景。
 *
 * <p>manifest 是**真的写进文件系统**的（{@link LanceManifestFixture} 编出的
 * protobuf 字节），因此这条链路覆盖的不只是控制器：它经过
 * {@code StorageFileAccess.list} / {@code readBytes}，也就是真实的对象存储访问路径。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paimon-lance-version;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LanceVersionApiTests {

    private static final String M = "/api/management/v1";
    private static final String CATALOG = "lance-version-test";
    private static final String SCHEMA = "lance";
    private static final String TABLE = "events";
    private static final String NS_ID = CATALOG + "%24" + SCHEMA;
    private static final String TABLE_ID = NS_ID + "%24" + TABLE;
    private static final String PLAIN_ID = NS_ID + "%24plain";

    private static final String WAREHOUSE =
            "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-server-tests/lance-version-test";

    @Autowired
    private MockMvc mockMvc;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 工具

    private JsonNode call(int expected, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "unexpected status, body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode() : mapper.readTree(body);
    }

    /** 走 {@link URI} 构造请求：MockMvc 会把字符串模板里的 {@code %24} 再编码成 {@code %2524}。 */
    private JsonNode postJson(int expected, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(URI.create(path));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request);
    }

    private JsonNode getJson(int expected, String path) throws Exception {
        return call(expected, get(URI.create(path)));
    }

    private void assertLanceError(JsonNode node, int code) {
        assertEquals(code, node.get("code").asInt(), "body=" + node);
        assertTrue(node.has("error"), "error 字段必须出现: " + node);
        assertTrue(node.has("instance"), "instance 填请求路径: " + node);
    }

    private List<Long> versions(JsonNode node) {
        List<Long> found = new ArrayList<>();
        node.get("versions").forEach(item -> found.add(item.get("version").asLong()));
        return found;
    }

    /** 删掉一个目录树（测试夹具的自清理，只碰 {@code java.io.tmpdir} 下的本用例目录）。 */
    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /**
     * 在数据集目录里写一个真实（可解析）的 manifest。
     *
     * <p>返回**存储 URI** 而不是 {@code Path}：{@code manifest_path} 要过 catalog 的
     * {@code allowedLocations} 白名单，而白名单里的形态是 {@code file://...}。
     * 用 {@code Path.toString()}（裸的 POSIX 路径）会被判成越界——
     * 这个区别在生产环境里是「本地文件系统 vs 对象存储」的分界。
     */
    private static String writeManifest(String location, long version) throws Exception {
        Path dataset = Path.of(URI.create(location));
        Path directory = dataset.resolve("_versions");
        Files.createDirectories(directory);
        Files.write(directory.resolve(LanceVersionNaming.manifestName(version, false)),
                LanceManifestFixture.manifest(version, 1_700_000_000L + version,
                        List.of(LanceManifestFixture.leaf("id", 1, 0, "int64"),
                                LanceManifestFixture.leaf("name", 2, 0, "string")),
                        2, 1));
        return location + "/_versions/" + LanceVersionNaming.manifestName(version, false);
    }

    // ------------------------------------------------------------------ 装配

    @Test
    @Order(1)
    void declaresAManagedVersioningTable() throws Exception {
        // 清掉上一次运行留下的数据集目录：本用例会往里面写 manifest，
        // 而 java.io.tmpdir 是跨运行共享的。不清的话第二次跑会看到上一轮的
        // 版本 1/2/3，「只登记过 → 详细元数据失败」这类断言直接失效。
        deleteRecursively(Path.of(URI.create(WAREHOUSE)));
        postJson(201, M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"LANCE","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}
                """.formatted(CATALOG, WAREHOUSE));
        postJson(200, "/lance/v1/namespace/" + NS_ID + "/create", "{\"properties\":{}}");

        JsonNode declared = postJson(200, "/lance/v1/table/" + TABLE_ID + "/declare",
                "{\"properties\":{\"lance.managed_versioning\":\"true\"}}");
        String location = declared.get("location").asString();
        assertTrue(location.endsWith(".lance"), location);

        // 开关落到了列上：describe 会把 managed_versioning 报回去（缺省是「不出现该键」）
        JsonNode described = postJson(200, "/lance/v1/table/" + TABLE_ID + "/describe", null);
        assertTrue(described.get("managed_versioning").asBoolean(), described.toString());
        // 没有开的那张表：字段不出现（= false）
        postJson(200, "/lance/v1/table/" + PLAIN_ID + "/declare", "{\"properties\":{}}");
        JsonNode plain = postJson(200, "/lance/v1/table/" + PLAIN_ID + "/describe", null);
        assertTrue(!plain.has("managed_versioning") || plain.get("managed_versioning").isNull(),
                "没开托管时不能报 true: " + plain);
    }

    @Test
    @Order(2)
    void detailedMetadataIsRefusedWhileTheTableIsOnlyDeclared() throws Exception {
        JsonNode failure = postJson(409,
                "/lance/v1/table/" + TABLE_ID + "/describe?load_detailed_metadata=true", null);
        assertLanceError(failure, 19);
        assertTrue(failure.get("error").asString().contains("only declared"), failure.toString());
    }

    @Test
    @Order(3)
    void detailedMetadataComesFromTheLatestManifest() throws Exception {
        String location = postJson(200, "/lance/v1/table/" + TABLE_ID + "/describe", null)
                .get("location").asString();
        writeManifest(location, 1);
        writeManifest(location, 2);

        JsonNode detailed = postJson(200,
                "/lance/v1/table/" + TABLE_ID + "/describe?load_detailed_metadata=true", null);
        assertEquals(2L, detailed.get("version").asLong(), detailed.toString());
        assertEquals(TABLE, detailed.get("table").asString());
        assertEquals(List.of(CATALOG, SCHEMA), List.of(
                detailed.get("namespace").get(0).asString(), detailed.get("namespace").get(1).asString()));
        assertEquals(2, detailed.get("stats").get("num_fragments").asInt());
        assertEquals(2L, detailed.get("stats").get("num_deleted_rows").asLong());
        JsonNode fields = detailed.get("schema").get("fields");
        assertEquals(2, fields.size(), detailed.toString());
        assertEquals("id", fields.get(0).get("name").asString());
        assertEquals("int", fields.get(0).get("type").get("type").asString());
        assertEquals(64, fields.get(0).get("type").get("bitWidth").asInt());
        assertEquals("utf8", fields.get(1).get("type").get("type").asString());
    }

    @Test
    @Order(4)
    void listsNativeVersionsInBothDirections() throws Exception {
        JsonNode ascending = postJson(200, "/lance/v1/table/" + TABLE_ID + "/version/list", null);
        assertEquals(List.of(1L, 2L), versions(ascending), ascending.toString());
        assertTrue(ascending.get("versions").get(0).get("manifest_path").asString().endsWith("1.manifest"));

        JsonNode descending = postJson(200,
                "/lance/v1/table/" + TABLE_ID + "/version/list?descending=true", null);
        assertEquals(List.of(2L, 1L), versions(descending), descending.toString());

        // 参考实现（Gravitino 1.3.1）用的是 GET，两种都接
        JsonNode viaGet = getJson(200, "/lance/v1/table/" + TABLE_ID + "/version/list");
        assertEquals(List.of(1L, 2L), versions(viaGet), viaGet.toString());
    }

    @Test
    @Order(5)
    void describesOneVersionAndReportsMissingOnes() throws Exception {
        JsonNode described = postJson(200, "/lance/v1/table/" + TABLE_ID + "/version/describe",
                "{\"version\":1}");
        assertEquals(1L, described.get("version").get("version").asLong());
        assertTrue(described.get("version").get("manifest_path").asString().endsWith("1.manifest"));

        // 没有版本号 → 400 / code 13
        assertLanceError(postJson(400, "/lance/v1/table/" + TABLE_ID + "/version/describe", "{}"), 13);
        // 没有这个版本 → 404 / code 11（不是 4：表在，是版本不在）
        assertLanceError(postJson(404, "/lance/v1/table/" + TABLE_ID + "/version/describe",
                "{\"version\":99}"), 11);
    }

    // ------------------------------------------------------------------ P2 写

    @Test
    @Order(6)
    void createVersionIsRejectedWhenTheManifestIsMissingOrTheTableIsNotManaged() throws Exception {
        String location = postJson(200, "/lance/v1/table/" + TABLE_ID + "/describe", null)
                .get("location").asString();
        // manifest 还没写 → 400
        assertLanceError(postJson(400, "/lance/v1/table/" + TABLE_ID + "/version/create",
                "{\"version\":3,\"manifest_path\":\"" + location + "/_versions/3.manifest\"}"), 13);
        // 没开 managed_versioning 的表 → 409 / code 19
        assertLanceError(postJson(409, "/lance/v1/table/" + PLAIN_ID + "/version/create",
                "{\"version\":1,\"manifest_path\":\"" + location + "/_versions/1.manifest\"}"), 19);
        // 缺 manifest_path → 400
        assertLanceError(postJson(400, "/lance/v1/table/" + TABLE_ID + "/version/create",
                "{\"version\":3}"), 13);
    }

    @Test
    @Order(7)
    void createVersionRecordsTheManifestAndRefusesDuplicates() throws Exception {
        String location = postJson(200, "/lance/v1/table/" + TABLE_ID + "/describe", null)
                .get("location").asString();
        String manifest = writeManifest(location, 3);

        JsonNode created = postJson(200, "/lance/v1/table/" + TABLE_ID + "/version/create",
                "{\"version\":3,\"manifest_path\":\"" + manifest + "\",\"metadata\":{\"by\":\"test\"}}");
        assertEquals(3L, created.get("version").get("version").asLong());
        assertEquals("test", created.get("version").get("metadata").get("by").asString());

        // put_if_not_exists：409 / code 14
        assertLanceError(postJson(409, "/lance/v1/table/" + TABLE_ID + "/version/create",
                "{\"version\":3,\"manifest_path\":\"" + manifest + "\"}"), 14);

        // 托管记录与原生 manifest 都在：并集去重，不重复出现 3
        JsonNode listed = postJson(200, "/lance/v1/table/" + TABLE_ID + "/version/list", null);
        assertEquals(List.of(1L, 2L, 3L), versions(listed), listed.toString());
    }

    @Test
    @Order(8)
    void batchCreateTableVersionsStaysUnsupported() throws Exception {
        // 路径里没有表标识 → 授权层无法定位资源，因此不做（406 + code 0）
        JsonNode failure = postJson(406, "/lance/v1/table/version/batch-create",
                "{\"entries\":[{\"id\":[\"" + CATALOG + "\",\"" + SCHEMA + "\",\"" + TABLE
                        + "\"],\"version\":9,\"manifest_path\":\"x\"}]}");
        assertLanceError(failure, 0);
    }

    @Test
    @Order(9)
    void deletingVersionRecordsLeavesTheFilesAlone() throws Exception {
        JsonNode deleted = postJson(200, "/lance/v1/table/" + TABLE_ID + "/version/delete",
                "{\"ranges\":[{\"start_version\":0,\"end_version\":-1}]}");
        assertEquals(1L, deleted.get("deleted_count").asLong(), deleted.toString());

        // 删的是**记账行**，不是文件：3.manifest 还在，因此列举仍然看得到它
        JsonNode listed = postJson(200, "/lance/v1/table/" + TABLE_ID + "/version/list", null);
        assertEquals(List.of(1L, 2L, 3L), versions(listed), listed.toString());

        // 原生版本管理的表没有账可删 → 0 条，而不是报错
        JsonNode none = postJson(200, "/lance/v1/table/" + PLAIN_ID + "/version/delete",
                "{\"ranges\":[{\"start_version\":0,\"end_version\":-1}]}");
        assertEquals(0L, none.get("deleted_count").asLong(), none.toString());

        // 非法区间 → 400
        assertLanceError(postJson(400, "/lance/v1/table/" + TABLE_ID + "/version/delete",
                "{\"ranges\":[{\"start_version\":5,\"end_version\":2}]}"), 13);
        assertLanceError(postJson(400, "/lance/v1/table/" + TABLE_ID + "/version/delete", "{}"), 13);
    }

    @Test
    @Order(10)
    void deregisteringDropsTheLedgerButNotTheData() throws Exception {
        String location = postJson(200, "/lance/v1/table/" + TABLE_ID + "/describe", null)
                .get("location").asString();
        postJson(200, "/lance/v1/table/" + TABLE_ID + "/deregister", null);
        // 表没了 → 再列版本是 404（表不存在），而不是空列表
        assertLanceError(postJson(404, "/lance/v1/table/" + TABLE_ID + "/version/list", null), 4);

        // deregister 只删登记：manifest 文件仍在（这一面从不删数据）
        assertTrue(Files.exists(Path.of(URI.create(location)).resolve("_versions/3.manifest")),
                "deregister 不能动数据: " + location);

        // 清场：删掉没开托管那张表，命名空间才能删
        postJson(200, "/lance/v1/table/" + PLAIN_ID + "/deregister", null);
        postJson(200, "/lance/v1/namespace/" + NS_ID + "/drop", null);
    }
}
