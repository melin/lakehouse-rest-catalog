package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.ArrayList;
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
 * 表生命周期钩子的验收（设计见 {@code docs/sensitive-data-design.md} §7.3）。
 *
 * <p><b>走的是真实链路，不是直接调监听器。</b>三个用例分别调
 * {@code TableService.drop / rename / alter / rollbackSchema}——它们各自发布那条事件，
 * 监听器在同一个事务里被同步唤醒。直接调 {@code SensitivityLifecycleListener} 会把
 * 「事件发得对不对」「提交后有没有真的触发」这两件最容易出错的事一起跳过。
 *
 * <p>三条断言各钉一条规则，且都是「错了不报错、只是治理数据悄悄失准」的那一类：
 * <ol>
 *   <li><b>{@code DROP} 连标签一起删</b>。留着的后果不是多一条脏数据，而是同名表重建后
 *       某一列因为名字碰巧一样被静默 mask 掉；finding 指向的列已经不存在，
 *       留着只会在人审队列里堆一串点开就 404 的待审项；</li>
 *   <li><b>{@code RENAME} 标签跟随、且不重扫</b>。改名不改语义，保护要跟过去；
 *       而列的集合没变，重扫一遍只是多一条 LIFECYCLE 扫描记录；</li>
 *   <li><b>{@code ALTER} 触发一次 {@code trigger_type=LIFECYCLE} 的单表元数据扫描，
 *       {@code rollback-schema} 不触发</b>。两者都发 {@code TABLE_COMMITTED}，
 *       差别只在 {@code change} 属性——判据写错的表现是「每次回滚都白扫一遍」，
 *       日志里除了多几条扫描记录什么也看不出来。</li>
 * </ol>
 */
@SpringBootTest(properties = {
        // 本类不测鉴权：application-test.yml 已是这个值，写出来是让「为什么这里不需要令牌」显式
        "lakehouse.rest.auth.enabled=false",
        // 内置 seed 必须开：手工打标要引用类型表里的 CN_ID_CARD（L4）
        "lakehouse.rest.sensitivity.seed-builtin-types=true"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SensitivityLifecycleTests {

    private static final String M = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String DB = "lc_db";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableRepository tableRepository;

    // ------------------------------------------------------------------ DROP

    @Test
    @Order(1)
    void droppingATableTakesItsTagsAndFindingsWithIt() throws Exception {
        createTable("lc_drop", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        tag("lc_drop", "id_card");
        scan("lc_drop");
        assertFalse(tagsOf("lc_drop").isEmpty(), "前提：删表之前这一列确实有标签");
        assertFalse(findingsOf("lc_drop").isEmpty(), "前提：删表之前这一列确实有 finding");

        tableService.drop(PREFIX, DB, "lc_drop");

        assertTrue(tagsInCatalog("lc_drop").isEmpty(),
                "删表必须连标签一起删：否则同名表重建时，这一列会因为名字碰巧一样被静默 mask 掉");
        assertTrue(findingsOf("lc_drop").isEmpty(),
                "finding 指向的列已经不存在，留着只会让人审队列堆一串点开就 404 的待审项");
    }

    // ------------------------------------------------------------------ RENAME

    @Test
    @Order(2)
    void renamingATableMovesItsTags() throws Exception {
        createTable("lc_old", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        tag("lc_old", "id_card");

        tableService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(DB, "lc_old"),
                new CommonDtos.Identifier(DB, "lc_new")));

        assertTrue(tagsInCatalog("lc_old").isEmpty(),
                "旧名下不该再有标签：留着就是一条指向不存在表的结论");
        JsonNode moved = only(tagsInCatalog("lc_new"), "id_card");
        assertEquals("MANUAL", moved.get("source").asString(),
                "改名是搬家，不是重新判断：来源必须原样带过去（改成 AUTO 就等于降级了这条人工结论）");
        assertEquals("PII.CN_ID_CARD", moved.get("tagKey").asString());
        assertEquals("L4", moved.get("level").asString(),
                "级别也要跟着走：这正是「保护跟着表走」与「标签丢了却没人发现」的区别");
        assertEquals(0, lifecycleScans("lc_new"),
                "改名不改列的集合，不该触发重扫——多一条 LIFECYCLE 记录只会让「新列刚被扫过」这个信号变噪");
    }

    // ------------------------------------------------------------------ ALTER

    @Test
    @Order(3)
    void alteringATableScansItAgain() throws Exception {
        createTable("lc_alter", "id:BIGINT NOT NULL", "name:VARCHAR(64)");
        assertEquals(0, lifecycleScans("lc_alter"), "前提：新表还没有被钩子扫过");

        tableService.alter(PREFIX, DB, "lc_alter", new TableDtos.AlterTableRequest(List.of(
                Map.of("action", "addColumn",
                        "fieldNames", List.of("id_card"),
                        "dataType", "VARCHAR(18)"))));

        JsonNode created = onlyScan("lc_alter");
        assertEquals("LIFECYCLE", created.get("triggerType").asString(),
                "这条扫描必须能被区分出来：它是表结构变更触发的，不是人点的、也不是定时的");
        assertEquals("METADATA_ONLY", created.get("mode").asString(),
                "钩子只扫元数据：为一个新列重新采样整张表，等于把一次 schema 变更变成一次明文读");
        assertEquals("SUCCEEDED", created.get("status").asString(),
                "同步跑完才返回，因此这里读到的就是终态");
        assertEquals("TABLE", created.get("scope").asString(),
                "只扫被改的那一张表——一次加列不该把整个 catalog 拖一遍");
        assertEquals(DB + ".lc_alter", created.get("database").asString() + "." + created.get("table").asString());
    }

    @Test
    @Order(4)
    void rollingBackASchemaDoesNotScan() throws Exception {
        // 与上一条共用 lc_alter：它此刻已有 1 条 LIFECYCLE 记录（schema 0 → 1）
        assertEquals(1, lifecycleScans("lc_alter"));

        tableService.rollbackSchema(PREFIX, DB, "lc_alter",
                new TableDtos.RollbackSchemaRequest(0L));

        assertEquals(1, lifecycleScans("lc_alter"),
                "回滚也发 TABLE_COMMITTED，但 change=rollback-schema 不改列的集合："
                        + "判据只看 change 是否为 alter，写错的表现是「每次回滚都白扫一遍」");
    }

    // ------------------------------------------------------------------ 造数据

    /** 建一张最简表；第一个字段是主键（与其他敏感数据用例同一造法）。 */
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

    /** 手工给一列打一条敏感标签（不打标就没有可删 / 可搬的东西）。 */
    private void tag(String table, String column) throws Exception {
        json(200, "PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "lc_db", "table": "%s", "column": "%s",
                 "tagKey": "PII.CN_ID_CARD", "tagValue": "L4", "typeCode": "CN_ID_CARD"}
                """.formatted(table, column));
    }

    /** 扫一次这张表（元数据档）：为 {@code DROP} 那条用例准备 finding。 */
    private void scan(String table) throws Exception {
        json(202, "POST", M + "/sensitivity-scans",
                """
                {"catalog": "paimon", "scope": "TABLE", "database": "lc_db", "table": "%s",
                 "mode": "METADATA_ONLY"}
                """.formatted(table));
    }

    private JsonNode tagsOf(String table) throws Exception {
        return json(200, "GET", M + "/column-tags?catalog=" + PREFIX + "&table=" + DB + "." + table, null)
                .get("items");
    }

    /**
     * 目录里属于这张表的标签（**不校验表是否存在**）。
     *
     * <p>删表 / 改名之后必须用这一条读：「按表名读标签」的端点在表不存在时回 404
     * （那是有意的：不存在的范围与「这个范围里没有标签」在界面上长得一样，处置却完全不同）。
     * 断言「旧名下不该再有标签」时表已经不在了，只能从整个 catalog 里筛。
     */
    private List<JsonNode> tagsInCatalog(String table) throws Exception {
        JsonNode items = json(200, "GET", M + "/column-tags?catalog=" + PREFIX, null).get("items");
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode item : items) {
            if (DB.equals(item.get("database").asString()) && table.equals(item.get("table").asString())) {
                matched.add(item);
            }
        }
        return matched;
    }

    private JsonNode findingsOf(String table) throws Exception {
        return json(200, "GET", M + "/sensitivity-findings?catalog=" + PREFIX + "&table="
                + DB + "." + table, null).get("items");
    }

    /**
     * 这张表被钩子扫过几次。
     *
     * <p>列表端点的过滤轴只有 catalog / status / scope / triggerType，没有表名——
     * 表名这一段在内存里筛。用 {@code triggerType=LIFECYCLE} 先缩小候选集，
     * 顺带让断言自带一层含义：本文件里出现的扫描记录**全部**来自钩子。
     */
    private int lifecycleScans(String table) throws Exception {
        return scansOf(table).size();
    }

    private List<JsonNode> scansOf(String table) throws Exception {
        JsonNode items = json(200, "GET",
                M + "/sensitivity-scans?catalog=" + PREFIX + "&triggerType=LIFECYCLE", null).get("items");
        List<JsonNode> matched = new ArrayList<>();
        for (JsonNode item : items) {
            if (table.equals(item.get("table").asString()) && DB.equals(item.get("database").asString())) {
                matched.add(item);
            }
        }
        return matched;
    }

    /** 这张表恰好有一条钩子扫描，取回它（多于一条说明同一个变更扫了不止一次）。 */
    private JsonNode onlyScan(String table) throws Exception {
        List<JsonNode> items = scansOf(table);
        assertEquals(1, items.size(), "expected exactly one lifecycle scan for " + table
                + ", got " + items.size());
        JsonNode found = items.get(0);
        assertNotNull(found);
        return found;
    }

    /** 断言这一列恰好有一条标签，并取回它（多于一条说明搬家时留下了副本）。 */
    private static JsonNode only(List<JsonNode> items, String column) {
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
            default -> throw new IllegalArgumentException(method);
        };
        return body == null
                ? request
                : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
