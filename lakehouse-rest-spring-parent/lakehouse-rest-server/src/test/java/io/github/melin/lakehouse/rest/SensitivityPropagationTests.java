package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import java.util.List;
import java.util.Map;
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
 * 血缘传播的验收（设计见 {@code docs/sensitive-data-design.md} §7.1 / §7.2）。
 *
 * <p><b>它验的是「扫描之后发生了什么」，所以只能走完整链路：</b>
 * 上报血缘边 → 给上游列打标 → 扫一次上游表 → 看下游有没有结论。桩掉任何一段都会
 * 让断言变成「我调的方法被调用了」，而那正是这一节最容易自欺的地方——
 * 传播的 bug 全表现为「什么都没发生」，看起来与「这张表本来就不敏感」一模一样。
 *
 * <p>五条断言各钉一个规则，且都是「错了也不报错、只是结论不对」的那一类：
 * <ol>
 *   <li><b>{@code DIRECT} 边一轮传播后下游出标签</b>，置信度 = 上游 × 0.9（人工标签按 1.0 起算）；</li>
 *   <li><b>{@code DERIVED} 边继承但再打 0.6 折，并落一条待复核建议</b>——变形之后还算不算敏感，
 *       规则给不出答案；</li>
 *   <li><b>{@code AGGREGATE} 边不落标签，只落建议</b>——{@code count(id_card)} 与
 *       {@code max(salary)} 无法用规则分开；</li>
 *   <li><b>人审否决优先</b>：撤掉一条继承标签会落抑制项，重扫不再复活（§7.2 的死循环防线）；</li>
 *   <li><b>深度上限</b>：{@code max-depth=2} 时第 3 跳停在 {@code PENDING}，不落标签。</li>
 * </ol>
 *
 * <p><b>每条用例用自己的一对源 / 目标表。</b>传播是从「本次扫描覆盖的表的标签」出发的，
 * 共用一个源表会让后跑的用例被前面留下的边干扰，而失败信息会指向一个跟它无关的表。
 */
@SpringBootTest(properties = {
        // 本类不测鉴权：application-test.yml 已是这个值，写出来是让「为什么这里不需要令牌」显式
        "lakehouse.rest.auth.enabled=false",
        // 内置 seed 必须开：传播继承的是类型表里的 level（CN_ID_CARD → L4）
        "lakehouse.rest.sensitivity.seed-builtin-types=true",
        // 深度上限用全局默认 5；第 5 条用例用 catalog 覆盖把它压到 2，验「超限停在建议」
        "lakehouse.rest.sensitivity.propagation.max-depth=5"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SensitivityPropagationTests {

    private static final String M = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String DB = "prop_db";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableRepository tableRepository;

    // ------------------------------------------------------------------ 三档判定

    @Test
    @Order(1)
    void directEdgeInheritsTheConclusion() throws Exception {
        createTable("dir_src", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        createTable("dir_dst", "id:BIGINT NOT NULL", "id_no:VARCHAR(18)");
        report("run-dir", "dir_src", "id_card", "dir_dst", "id_no", "DIRECT", "id_card");

        tag("dir_src", "id_card");
        scan("dir_src");

        JsonNode inherited = only(tagsOf("dir_dst"), "id_no");
        assertEquals("INHERITED", inherited.get("source").asString(),
                "直接列引用必须继承——否则这张宽表只能等人工一个列一个列地打标");
        assertEquals("CN_ID_CARD", inherited.get("typeCode").asString(), "类型跟着上游走");
        assertEquals("L4", inherited.get("level").asString(),
                "级别取自类型表，而不是从上游标签里抄一遍");
        assertEquals(0.9, inherited.get("confidence").asDouble(), 1e-6,
                "人工标签的置信度按 1.0 起算，第一跳 ×0.9");
        assertEquals("paimon.prop_db.dir_src.id_card", inherited.get("inheritedFrom").asString(),
                "继承必须记下来源列：否则这条标签既解释不了、也追不回去");
        assertFalse(inherited.get("tableLevel").asBoolean());
    }

    @Test
    @Order(2)
    void derivedEdgeInheritsButAsksForReview() throws Exception {
        createTable("der_src", "id:BIGINT NOT NULL", "card_no:VARCHAR(18)");
        createTable("der_dst", "id:BIGINT NOT NULL", "card_md5:VARCHAR(32)");
        report("run-der", "der_src", "card_no", "der_dst", "card_md5", "DERIVED", "md5(card_no)");

        tag("der_src", "card_no");
        scan("der_src");

        JsonNode inherited = only(tagsOf("der_dst"), "card_md5");
        assertEquals("INHERITED", inherited.get("source").asString());
        assertEquals(0.54, inherited.get("confidence").asDouble(), 1e-6,
                "0.9（逐跳）× 0.6（派生变换）——已变形但仍可反推");

        JsonNode review = only(findingsOf("der_dst"), "card_md5");
        assertEquals("PENDING", review.get("state").asString(),
                "派生列的继承要人看一眼：md5 之后还算不算敏感，规则给不出答案");
        assertEquals("DERIVED", review.get("detectors").get("reason").asString(),
                "建议里要写清缘由，否则人审只看到一个分数");
        assertEquals(1, review.get("detectors").get("depth").asInt(),
                "depth 是跳数：被打标的那一列是 0，第一跳是 1"
                        + "（第 5 条用例的「第 3 跳」就是 depth=3，两处必须是同一个口径）");
        // 键不存在也是一种「为空」：读模型不序列化 null 字段，因此这里不能直接 .isNull()
        assertTrue(review.get("samplingMode") == null || review.get("samplingMode").isNull(),
                "这条建议不是从值里看出来的：采样方式必须为空，不能默认成 HEAD");
    }

    @Test
    @Order(3)
    void aggregateEdgeSuggestsWithoutATag() throws Exception {
        createTable("agg_src", "id:BIGINT NOT NULL", "salary:DECIMAL(12,2)");
        createTable("agg_dst", "id:BIGINT NOT NULL", "max_salary:DECIMAL(12,2)");
        report("run-agg", "agg_src", "salary", "agg_dst", "max_salary", "AGGREGATE", "max(salary)");

        tag("agg_src", "salary", "SALARY", "PII.SALARY", "L3");
        scan("agg_src");

        assertTrue(tagsOf("agg_dst").isEmpty(),
                "聚合列不继承：count(id_card) 不敏感、max(salary) 敏感，规则分不开");
        JsonNode review = only(findingsOf("agg_dst"), "max_salary");
        assertEquals("PENDING", review.get("state").asString());
        assertEquals("AGGREGATE", review.get("detectors").get("reason").asString());
        assertEquals("SALARY", review.get("typeCode").asString(), "建议的类型沿用上游那一列");
    }

    // ------------------------------------------------------------------ 人工否决与深度

    @Test
    @Order(4)
    void aRejectedInheritanceDoesNotComeBack() throws Exception {
        createTable("rej_src", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        createTable("rej_dst", "id:BIGINT NOT NULL", "id_no:VARCHAR(18)");
        report("run-rej", "rej_src", "id_card", "rej_dst", "id_no", "DIRECT", "id_card");

        tag("rej_src", "id_card");
        scan("rej_src");

        JsonNode inherited = only(tagsOf("rej_dst"), "id_no");
        long tagId = inherited.get("id").asLong();
        // 删一条带类型的标签 = 撤回这个结论 + 落一条永久抑制项（P1 的口径）
        assertEquals(204, status("DELETE", M + "/column-tags/" + tagId, null));
        assertTrue(tagsOf("rej_dst").isEmpty());

        scan("rej_src");
        assertTrue(tagsOf("rej_dst").isEmpty(),
                "人审否决优先于自动传播：否则「否掉 → 明天换个上游又推一遍」会变成每天的例行劳动");
        assertTrue(findingsOf("rej_dst").isEmpty(),
                "抑制项连建议都要挡掉——提了又被否，比不提更浪费人手");
    }

    @Test
    @Order(5)
    void beyondTheDepthLimitItStopsAtPending() throws Exception {
        createTable("hop1", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        createTable("hop2", "id:BIGINT NOT NULL", "id_no:VARCHAR(18)");
        createTable("hop3", "id:BIGINT NOT NULL", "id_no:VARCHAR(18)");
        createTable("hop4", "id:BIGINT NOT NULL", "id_no:VARCHAR(18)");
        report("run-hop-a", "hop1", "id_card", "hop2", "id_no", "DIRECT", "id_card");
        report("run-hop-b", "hop2", "id_no", "hop3", "id_no", "DIRECT", "id_no");
        report("run-hop-c", "hop3", "id_no", "hop4", "id_no", "DIRECT", "id_no");

        // catalog 覆盖把跳数压到 2：第 1、2 跳继承，第 3 跳只出建议
        json(200, "PUT", M + "/sensitivity-settings?catalog=" + PREFIX,
                """
                {"propagationMaxDepth": 2}
                """);
        try {
            tag("hop1", "id_card");
            scan("hop1");

            assertEquals(0.9, only(tagsOf("hop2"), "id_no").get("confidence").asDouble(), 1e-6);
            assertEquals(0.81, only(tagsOf("hop3"), "id_no").get("confidence").asDouble(), 1e-6,
                    "第 2 跳：0.9 × 0.9");
            assertTrue(tagsOf("hop4").isEmpty(),
                    "第 3 跳超出上限，必须停在建议——一条误标沿长链污染半张数仓，比漏标更难收拾");
            JsonNode review = only(findingsOf("hop4"), "id_no");
            assertEquals("PENDING", review.get("state").asString());
            assertEquals("DEPTH_EXCEEDED", review.get("detectors").get("reason").asString());
            assertEquals(3, review.get("detectors").get("depth").asInt());
        } finally {
            // 覆盖值是 catalog 级的，留着会让别的测试类也按 2 跳走
            json(200, "PUT", M + "/sensitivity-settings?catalog=" + PREFIX,
                    """
                    {"propagationMaxDepth": null}
                    """);
        }
    }

    // ------------------------------------------------------------------ 建表与上报

    /** 建一张最简表；第一个字段是主键（与 {@code SensitivityApiTests} 同一造法）。 */
    private void createTable(String table, String... columns) {
        if (!databaseService.list(PREFIX, null, null, null, null).databases().contains(DB)) {
            databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(DB, Map.of()));
        }
        String databaseId = databaseService.require(PREFIX, DB).getId();
        String catalogId = databaseService.require(PREFIX, DB).getCatalogId();
        if (tableRepository.findByCatalogIdAndDatabaseIdAndName(catalogId, databaseId, table).isPresent()) {
            return;
        }
        TypeDtos.Schema schema = new TypeDtos.Schema();
        int id = 0;
        for (String column : columns) {
            int colon = column.indexOf(':');
            TypeDtos.DataField field = new TypeDtos.DataField();
            field.setId(id);
            field.setName(column.substring(0, colon));
            field.setType(column.substring(colon + 1));
            schema.getFields().add(field);
            if (id == 0) {
                schema.getPrimaryKeys().add(field.getName());
            }
            id++;
        }
        schema.getOptions().put("bucket", "1");
        tableService.create(PREFIX, DB,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(DB, table), schema));
    }

    /**
     * 上报一条列级血缘边。
     *
     * <p>表的 ref 形状是 {@code catalog.namespace.table}（{@link io.github.melin.lakehouse.rest.support.LineageRefs}
     * 的口径），因此这里拼的是 {@code paimon.prop_db.xxx}，而不是控制台路由里的两段式。
     */
    private void report(String runId, String sourceTable, String sourceColumn, String targetTable,
                        String targetColumn, String kind, String expression) throws Exception {
        String target = PREFIX + "." + DB + "." + targetTable;
        String source = PREFIX + "." + DB + "." + sourceTable;
        String body = "{\"runs\":[{\"runId\":\"" + runId + "\",\"engine\":\"SPARK\","
                + "\"engineVersion\":\"3.5.1\",\"statementType\":\"INSERT\",\"writeMode\":\"APPEND\","
                + "\"mode\":\"BATCH\",\"sqlHash\":\"h-" + runId + "\","
                + "\"sqlText\":\"INSERT INTO " + target + " SELECT * FROM " + source + "\","
                + "\"targets\":[{\"table\":\"" + target + "\",\"columns\":[{\"targetColumn\":\""
                + targetColumn + "\",\"kind\":\"" + kind + "\",\"expression\":\"" + expression
                + "\",\"sources\":[{\"table\":\"" + source + "\",\"column\":\"" + sourceColumn
                + "\"}]}]}],"
                + "\"sources\":[{\"table\":\"" + source + "\",\"kind\":\"TABLE\"}]}]}";
        json(200, "POST", M + "/lineage/runs", body);
    }

    /** 手工给上游列打一条敏感标签（不打标就没有可传播的结论）。 */
    private void tag(String table, String column) throws Exception {
        tag(table, column, "CN_ID_CARD", "PII.CN_ID_CARD", "L4");
    }

    private void tag(String table, String column, String typeCode, String tagKey, String level)
            throws Exception {
        json(200, "PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "prop_db", "table": "%s", "column": "%s",
                 "tagKey": "%s", "tagValue": "%s", "typeCode": "%s"}
                """.formatted(table, column, tagKey, level, typeCode));
    }

    /**
     * 扫一次这张表（元数据档就够：传播读的是标签，不是值）。
     *
     * <p>用 {@code METADATA_ONLY} 是有意的：本类要验的是「扫完之后传播做了什么」，
     * 采样档会引入存储依赖，把一个纯逻辑的回归变成需要真 parquet 文件的用例。
     */
    private void scan(String table) throws Exception {
        json(202, "POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "prop_db", "table": "%s",
                 "mode": "METADATA_ONLY"}
                """.formatted(table));
    }

    private JsonNode tagsOf(String table) throws Exception {
        return json(200, "GET", M + "/column-tags?catalog=" + PREFIX + "&table=" + DB + "." + table, null)
                .get("items");
    }

    private JsonNode findingsOf(String table) throws Exception {
        return json(200, "GET", M + "/sensitivity-findings?catalog=" + PREFIX + "&table="
                + DB + "." + table, null).get("items");
    }

    /** 断言这一列恰好有一条标签 / 一条建议，并取回它（多于一条说明幂等失效了）。 */
    private static JsonNode only(JsonNode items, String column) {
        JsonNode found = null;
        int count = 0;
        for (JsonNode item : items) {
            if (column.equals(item.get("column").asString())) {
                found = item;
                count++;
            }
        }
        assertEquals(1, count, "column " + column + " should have exactly one row, got " + count);
        assertNotNull(found);
        return found;
    }

    private int status(String method, String path, String body) throws Exception {
        return mockMvc.perform(request(method, path, body)).andReturn().getResponse().getStatus();
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(request(method, path, body)).andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> method + " " + path + " unexpected status, body=" + responseBody);
        if (expected == 204 || responseBody == null || responseBody.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return mapper.readTree(responseBody);
    }

    private static MockHttpServletRequestBuilder request(String method, String path, String body) {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "PUT" -> put(path);
            case "POST" -> post(path);
            case "DELETE" -> delete(path);
            default -> throw new IllegalArgumentException(method);
        };
        return body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
