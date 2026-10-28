package io.github.melin.lakehouse.rest.lance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
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

/**
 * 标签只读代理的 HTTP 层测试（P2 的 L2-2 里能做的那一半）。
 *
 * <p>被钉住的核心事实：**标签不是我们的数据**。测试夹具直接在数据集目录里写
 * {@code _refs/tags/*.json}——这正是 Lance 客户端创建标签的方式——然后断言
 * 服务端只是把它读出来。因此这里没有任何「建标签」的调用：如果我们提供了写端点，
 * 这个用例的第一句就该变了。
 *
 * <p>四个容易写错、写错了也不会报错的点，各自有一条断言：
 * <ol>
 *   <li>{@code manifestSize} 是**驼峰**（规范里唯一一处），写成 {@code manifest_size}
 *       客户端会读到 {@code null}；</li>
 *   <li>{@code tags/list} 的 {@code tags} 是 **map** 不是数组；</li>
 *   <li>没有目录时是**空 map**（确实没有标签），不是 404；</li>
 *   <li>坏标签文件：列举时跳过、指名问时 409 —— 「坏了」与「不存在」不是同一件事。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:paimon-lance-tag;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LanceTagApiTests {

    private static final String M = "/api/management/v1";
    private static final String CATALOG = "lance-tag-test";
    private static final String SCHEMA = "lance";
    private static final String TAGGED = "tagged";
    private static final String EMPTY = "empty";
    private static final String NS_ID = CATALOG + "%24" + SCHEMA;
    private static final String TAGGED_ID = NS_ID + "%24" + TAGGED;
    private static final String EMPTY_ID = NS_ID + "%24" + EMPTY;

    private static final String WAREHOUSE =
            "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-server-tests/lance-tag-test";

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

    private void assertLanceError(JsonNode node, int code) {
        assertEquals(code, node.get("code").asInt(), "body=" + node);
        assertTrue(node.has("error"), "error 字段必须出现: " + node);
    }

    /** 规范里这些字段可以「缺键」，而本服务端把 null 也序列化出来；两种都算没值。 */
    private void assertNullOrAbsent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        assertTrue(value == null || value.isNull(), field + " 应当是 null 或缺省: " + node);
    }

    private String locationOf(String tableId) throws Exception {
        return postJson(200, "/lance/v1/table/" + tableId + "/describe", null).get("location").asString();
    }

    /** 像 Lance 客户端那样在数据集里写一个标签文件。 */
    private static void writeTag(String location, String tag, String json) throws Exception {
        Path directory = Path.of(URI.create(location)).resolve("_refs/tags");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(tag + ".json"), json);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ------------------------------------------------------------------ 装配

    @Test
    @Order(1)
    void declaresTwoTables() throws Exception {
        // java.io.tmpdir 跨运行共享：不清掉上一轮写下的标签，用例 2 的「空 map」会直接失效
        deleteRecursively(Path.of(URI.create(WAREHOUSE)));
        postJson(201, M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"LANCE","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}
                """.formatted(CATALOG, WAREHOUSE));
        postJson(200, "/lance/v1/namespace/" + NS_ID + "/create", "{\"properties\":{}}");
        postJson(200, "/lance/v1/table/" + TAGGED_ID + "/declare", "{\"properties\":{}}");
        postJson(200, "/lance/v1/table/" + EMPTY_ID + "/declare", "{\"properties\":{}}");
    }

    @Test
    @Order(2)
    void aTableWithoutTagsReturnsAnEmptyMap() throws Exception {
        JsonNode listed = postJson(200, "/lance/v1/table/" + EMPTY_ID + "/tags/list", null);
        assertTrue(listed.get("tags").isObject(), "tags 必须是 map 不是数组: " + listed);
        assertEquals(0, listed.get("tags").size(), listed.toString());
        // 没有标签 ≠ 表不存在：指向不存在的标签才是 404
        assertLanceError(postJson(404, "/lance/v1/table/" + EMPTY_ID + "/tags/version",
                "{\"tag\":\"nope\"}"), 8);
    }

    @Test
    @Order(3)
    void listsTheTagsTheClientWroteIntoTheDataset() throws Exception {
        String location = locationOf(TAGGED_ID);
        // 主分支上的标签：盘上没有 branch 键
        writeTag(location, "baseline", "{\"version\":2,\"manifestSize\":1234}");
        // 分支上的标签：有 branch，且 size 是另一个值
        writeTag(location, "release-1.0", "{\"branch\":\"feature\",\"version\":3,\"manifestSize\":99}");

        JsonNode listed = postJson(200, "/lance/v1/table/" + TAGGED_ID + "/tags/list", null);
        JsonNode tags = listed.get("tags");
        assertEquals(2, tags.size(), listed.toString());
        assertTrue(tags.has("baseline") && tags.has("release-1.0"), listed.toString());

        JsonNode baseline = tags.get("baseline");
        assertEquals(2L, baseline.get("version").asLong());
        // 驼峰！写成 manifest_size 的话这里会是 null，而客户端只会看到「没有 size」
        assertEquals(1234L, baseline.get("manifestSize").asLong(), baseline.toString());
        assertFalse(baseline.has("manifest_size"), "规范里这个字段是驼峰: " + baseline);
        assertNullOrAbsent(baseline, "branch");

        JsonNode release = tags.get("release-1.0");
        assertEquals("feature", release.get("branch").asString());
        assertEquals(99L, release.get("manifestSize").asLong());
    }

    @Test
    @Order(4)
    void reportsTheVersionATagPointsTo() throws Exception {
        JsonNode baseline = postJson(200, "/lance/v1/table/" + TAGGED_ID + "/tags/version",
                "{\"tag\":\"baseline\"}");
        assertEquals(2L, baseline.get("version").asLong(), baseline.toString());
        assertNullOrAbsent(baseline, "branch");

        JsonNode release = postJson(200, "/lance/v1/table/" + TAGGED_ID + "/tags/version",
                "{\"tag\":\"release-1.0\"}");
        assertEquals(3L, release.get("version").asLong());
        assertEquals("feature", release.get("branch").asString());
    }

    @Test
    @Order(5)
    void rejectsMissingAndUnsafeTagNames() throws Exception {
        assertLanceError(postJson(400, "/lance/v1/table/" + TAGGED_ID + "/tags/version", "{}"), 13);
        assertLanceError(postJson(400, "/lance/v1/table/" + TAGGED_ID + "/tags/version",
                "{\"tag\":\"  \"}"), 13);
        // 标签名直接拼成文件名 → 必须拒绝 `..`，否则「读标签」变成「读任意对象」
        assertLanceError(postJson(400, "/lance/v1/table/" + TAGGED_ID + "/tags/version",
                "{\"tag\":\"../../_versions/1.manifest\"}"), 13);
        assertLanceError(postJson(400, "/lance/v1/table/" + TAGGED_ID + "/tags/version",
                "{\"tag\":\"a/b\"}"), 13);
        // 表不存在 → 4，不是 8
        assertLanceError(postJson(404, "/lance/v1/table/" + NS_ID + "%24ghost/tags/list", null), 4);
    }

    @Test
    @Order(6)
    void pagesThroughTags() throws Exception {
        JsonNode first = postJson(200, "/lance/v1/table/" + TAGGED_ID + "/tags/list?limit=1", null);
        // 顺序由我们定（标签名升序）——不定的话同一个 page_token 在两次请求里
        // 可能落在不同位置，翻页就会重复或漏
        assertEquals(1, first.get("tags").size(), first.toString());
        assertTrue(first.get("tags").has("baseline"), "第一页应当是名字最小的 baseline: " + first);
        JsonNode token = first.get("page_token");
        assertTrue(token != null && token.isString(), "还有下一页时必须给 page_token: " + first);

        JsonNode second = postJson(200, "/lance/v1/table/" + TAGGED_ID + "/tags/list?limit=1&page_token="
                + token.asString(), null);
        assertEquals(1, second.get("tags").size(), second.toString());
        assertTrue(second.get("tags").has("release-1.0"), "第二页应当是 release-1.0: " + second);
    }

    @Test
    @Order(7)
    void anUnreadableTagIsSkippedInTheListButFailsWhenAskedDirectly() throws Exception {
        writeTag(locationOf(TAGGED_ID), "broken", "not json at all");

        // 枚举：一个坏标签不该把其余标签一起藏起来
        JsonNode listed = postJson(200, "/lance/v1/table/" + TAGGED_ID + "/tags/list", null);
        JsonNode tags = listed.get("tags");
        assertEquals(2, tags.size(), listed.toString());
        assertFalse(tags.has("broken"), "坏标签必须被跳过: " + listed);

        // 指名要它：不能跳过，也不能说「不存在」（那是撒谎），而是「这张表的状态不对」
        assertLanceError(postJson(409, "/lance/v1/table/" + TAGGED_ID + "/tags/version",
                "{\"tag\":\"broken\"}"), 19);
    }

    @Test
    @Order(8)
    void tagWritesStayUnsupported() throws Exception {
        // 我们从不写 Lance 的文件：写端点仍由兜底控制器回 406 + code 0
        assertLanceError(postJson(406, "/lance/v1/table/" + TAGGED_ID + "/tags/create",
                "{\"tag\":\"x\",\"version\":1}"), 0);
        assertLanceError(postJson(406, "/lance/v1/table/" + TAGGED_ID + "/tags/delete",
                "{\"tag\":\"x\"}"), 0);
    }

    @Test
    @Order(9)
    void cleanupDeregistersTheTables() throws Exception {
        postJson(200, "/lance/v1/table/" + TAGGED_ID + "/deregister", null);
        postJson(200, "/lance/v1/table/" + EMPTY_ID + "/deregister", null);
        postJson(200, "/lance/v1/namespace/" + NS_ID + "/drop", null);
    }
}
