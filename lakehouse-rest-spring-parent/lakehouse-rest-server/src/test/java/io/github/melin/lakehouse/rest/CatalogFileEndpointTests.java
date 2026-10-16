package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.dto.TableDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.service.DatabaseService;
import io.github.melin.lakehouse.rest.service.TableService;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 仓库文件浏览端点的 HTTP 层测试。
 *
 * <p>与 {@link CatalogFileBrowserTests} 的分工：那一类测服务的语义（排序、截断、
 * 越界判断），这一类只钉**接口本身**——URL 形状、查询参数名、状态码、
 * 响应字段名。两者分开的理由是这些错误各自独立：路径写错或字段名写错时，
 * 服务层全绿而控制台拿到的是 404 与 {@code undefined}；反过来，把语义断言
 * 塞进 HTTP 层则会让每个用例都要起服务端上下文，改一个排序规则就得跑一整趟 Web 链路。
 *
 * <p>鉴权用静态令牌（与 {@code ConsoleApiTests} 同一手法）：这些端点要求认证，
 * 而每条用例都走一遍登录换令牌只会让「门禁开启时静态令牌可用」之外的断言变慢。
 */
@SpringBootTest(properties = {
        "paimon.rest.auth.tokens[0]=catalog-file-endpoint-test-token"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CatalogFileEndpointTests {

    private static final String PREFIX = "paimon";

    private static final String TOKEN = "catalog-file-endpoint-test-token";

    private static final String FILES = "/api/console/v1/catalogs/" + PREFIX + "/files";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CatalogService catalogService;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;

    private final JsonMapper mapper = JsonMapper.builder().build();

    private String database;

    @BeforeEach
    void createDatabase() {
        database = "fe_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(database, Map.of("owner", "paimon")));
    }

    @AfterEach
    void dropDatabase() throws IOException {
        if (database == null || database.isBlank()) {
            return;
        }
        try {
            tableService.drop(PREFIX, database, "orders");
        } catch (RuntimeException ignored) {
            // 用例没有建表或已经删掉；清理不该让用例失败
        }
        try {
            databaseService.drop(PREFIX, database);
        } catch (RuntimeException ignored) {
            // 清理不该让用例失败
        }
        Path directory = Paths.get(URI.create(catalogService.resolve(PREFIX).getWarehouse() + "/" + database + ".db"));
        if (Files.exists(directory)) {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void filesEndpointListsTheWarehouseRoot() throws Exception {
        // 建库只在元数据里登记，不产生物理目录；要让根目录里出现它，得自己建出来
        Files.createDirectories(databaseDirectory());

        JsonNode body = getJson(FILES, 200);

        assertEquals(PREFIX, body.get("catalog").asString());
        assertEquals("", body.get("path").asString(), "不带 path 参数就是仓库根");
        assertEquals(catalogService.resolve(PREFIX).getWarehouse(), body.get("warehouse").asString());
        assertFalse(body.get("truncated").asBoolean());
        assertTrue(body.get("maxEntries").asInt() > 0);

        JsonNode entry = firstEntryNamed(body.get("entries"), database + ".db");
        assertTrue(entry.get("directory").asBoolean());
        assertEquals(database + ".db", entry.get("path").asString());
        assertTrue(entry.has("size"));
        assertTrue(entry.has("modificationTime"));
    }

    @Test
    void pathQueryParameterDrillsDown() throws Exception {
        Files.createDirectories(databaseDirectory().resolve("orders"));

        JsonNode body = getJson(FILES + "?path=" + database + ".db", 200);
        assertEquals(database + ".db", body.get("path").asString());
        // 条目路径是相对**仓库根**的，不是相对本次列出的目录：控制台原样回传就能继续下钻
        assertEquals(database + ".db/orders",
                firstEntryNamed(body.get("entries"), "orders").get("path").asString());
    }

    @Test
    void unknownCatalogAndBadPathsGetTheirOwnStatusCodes() throws Exception {
        assertStatus("/api/console/v1/catalogs/no-such-" + database + "/files", 404);
        // 越界路径是 400 而不是 403：它不是一个权限问题，而是请求形状不对
        assertStatus(FILES + "?path=" + "..", 400);
        assertStatus(FILES + "?path=/etc", 400);
        assertStatus(FILES + "?path=" + database + ".db/nope", 404);
    }

    @Test
    void tableFilesEndpointIsRootedAtTheTableDirectory() throws Exception {
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        String tableId = tableService.get(PREFIX, database, "orders").id();
        String tableFiles = "/api/console/v1/tables/" + tableId + "/files";

        JsonNode body = getJson(tableFiles, 200);
        assertEquals(PREFIX, body.get("catalog").asString());
        assertEquals(database, body.get("database").asString());
        assertEquals("orders", body.get("table").asString());
        assertEquals(tableService.get(PREFIX, database, "orders").path(), body.get("root").asString());
        assertEquals("", body.get("path").asString(), "不带 path 参数就是表目录本身");
        assertFalse(body.get("truncated").asBoolean());
        assertTrue(body.get("maxEntries").asInt() > 0);
        // 建表即物化 schema：表目录根下就有 schema/
        JsonNode schema = firstEntryNamed(body.get("entries"), "schema");
        assertTrue(schema.get("directory").asBoolean());
        assertEquals("schema", schema.get("path").asString());

        // 下钻：path 相对表目录，条目路径也是相对表目录
        JsonNode drill = getJson(tableFiles + "?path=schema", 200);
        assertEquals("schema", drill.get("path").asString());
        assertEquals("schema/schema-0",
                firstEntryNamed(drill.get("entries"), "schema-0").get("path").asString());

        // 越界与未知表各有各的状态码
        assertStatus(tableFiles + "?path=" + "..", 400);
        assertStatus("/api/console/v1/tables/no-such-" + database + "/files", 404);
    }

    @Test
    void prefixQueryParameterFiltersAndIsEchoed() throws Exception {
        Files.createDirectories(databaseDirectory().resolve("dt=20261010"));
        Files.writeString(databaseDirectory().resolve("dt=20261010").resolve("bucket-0"), "x");
        Files.writeString(databaseDirectory().resolve("other.txt"), "x");

        // 前缀过滤走服务端（回车即重新查询），响应把生效的过滤条件回显回来。
        // 参数用 queryParam 传：MockMvc 的 get(url) 不解析百分号编码，
        // 拼在 URL 里的 `dt%3D` 会原样进到服务端（前缀里的 `=` 是合法字符）
        JsonNode body = getJsonWith(FILES, Map.of("path", database + ".db", "prefix", "dt="), 200);
        assertEquals("dt=", body.get("prefix").asString());
        assertEquals(1, body.get("total").asInt(), "total 是过滤后的条数");
        assertEquals("dt=20261010", body.get("entries").get(0).get("name").asString());

        // 不带 prefix 时回显空串
        assertEquals("",
                getJsonWith(FILES, Map.of("path", database + ".db"), 200).get("prefix").asString());

        // 含分隔符的前缀是 400：条目名不含 `/`，那种写法永远匹配不到东西
        assertStatusWith(FILES, Map.of("path", database + ".db", "prefix", "dt=20261010/"), 400);

        // 匹配不到是空列表，不是错误
        JsonNode empty = getJsonWith(FILES, Map.of("path", database + ".db", "prefix", "nope"), 200);
        assertEquals(0, empty.get("total").asInt());
        assertTrue(empty.get("entries").isEmpty());
    }

    @Test
    void downloadEndpointStreamsTheFileWithAttachmentHeaders() throws Exception {
        tableService.create(PREFIX, database, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(database, "orders"), ordersSchema()));
        String tableId = tableService.get(PREFIX, database, "orders").id();

        // 表级下载：200、attachment 头、长度与实际字节数一致
        MvcResult result = mockMvc.perform(get("/api/console/v1/tables/" + tableId + "/files/download")
                .queryParam("path", "schema/schema-0")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertTrue(disposition != null && disposition.contains("schema-0"),
                "文件名要进 Content-Disposition，浏览器才知道存成什么：" + disposition);
        byte[] content = result.getResponse().getContentAsByteArray();
        assertTrue(content.length > 0);
        assertEquals(content.length, result.getResponse().getContentLength(),
                "Content-Length 必须与实际字节数一致，差了浏览器会截断或挂起");

        // catalog 级下载同一份文件：路径相对仓库根
        assertStatus("/api/console/v1/catalogs/" + PREFIX + "/files/download?path="
                + database + ".db/orders/schema/schema-0", 200);

        // 目录是 400（列表去看），缺失是 404
        assertStatus("/api/console/v1/tables/" + tableId + "/files/download?path=schema", 400);
        assertStatus("/api/console/v1/tables/" + tableId + "/files/download?path=not-there", 404);
    }

    // ------------------------------------------------------------------ 辅助

    /** 本用例那个库对应的目录。建库不产生目录，因此用例自己建。 */
    private Path databaseDirectory() {
        return Paths.get(URI.create(catalogService.resolve(PREFIX).getWarehouse() + "/" + database + ".db"));
    }

    private JsonNode getJson(String path, int expected) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body);
    }

    private void assertStatus(String path, int expected) throws Exception {
        MvcResult result = mockMvc.perform(get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "body=" + body);
    }

    /** 带查询参数发请求：值里的 `=`、`/` 等字符不能靠拼 URL 传（MockMvc 不解析 %xx）。 */
    private JsonNode getJsonWith(String path, Map<String, String> query, int expected) throws Exception {
        MvcResult result = mockMvc.perform(performWith(path, query)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body);
    }

    private void assertStatusWith(String path, Map<String, String> query, int expected) throws Exception {
        MvcResult result = mockMvc.perform(performWith(path, query)).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> "body=" + body);
    }

    // 只建请求不执行：执行统一走 mockMvc.perform——Builder 上的 andReturn() 在 Spring 7 已移除
    private MockHttpServletRequestBuilder performWith(String path, Map<String, String> query) {
        MockHttpServletRequestBuilder builder = get(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
        for (Map.Entry<String, String> entry : query.entrySet()) {
            builder = builder.queryParam(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    private static JsonNode firstEntryNamed(JsonNode entries, String name) {
        for (JsonNode entry : entries) {
            if (name.equals(entry.get("name").asString())) {
                return entry;
            }
        }
        throw new AssertionError("响应里没有 " + name + "，实际是 " + entries);
    }

    /** 与 {@code CatalogFileBrowserTests.ordersSchema()} 保持同一份形状。 */
    private static TypeDtos.Schema ordersSchema() {
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "id", "BIGINT NOT NULL"));
        schema.getFields().add(field(1, "name", "VARCHAR(20)"));
        schema.getFields().add(field(2, "dt", "VARCHAR(10)"));
        schema.getPrimaryKeys().add("id");
        schema.getPartitionKeys().add("dt");
        schema.getOptions().put("bucket", "1");
        schema.setComment("orders");
        return schema;
    }

    private static TypeDtos.DataField field(int id, String name, String type) {
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(id);
        field.setName(name);
        field.setType(type);
        return field;
    }
}
