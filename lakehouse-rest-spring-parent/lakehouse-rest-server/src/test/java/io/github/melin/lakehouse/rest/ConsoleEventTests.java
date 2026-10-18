package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionAssignment;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.PermissionResource;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import io.github.melin.lakehouse.rest.service.PermissionManagementService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 目录事件流（{@code GET /api/console/v1/events}）与发布点的行为测试。
 *
 * <p>事件框架的核心承诺有三条，这里各钉住一条：
 *
 * <ol>
 *   <li><b>变更真的会落成事件</b>——建表 / 改名 / 删表走 Paimon 侧服务层，
 *       授权与撤销走权限服务，事件流里都能按类型与目标找到对应条目，
 *       且 principal / requestId 来自请求上下文、属性带格式标识。</li>
 *   <li><b>事件流里没有空操作</b>——幂等的撤销「没找到也不报错」，但那种调用
 *       不进事件流（docs/event-framework.md §3 规则 3）。断了这条，
 *       消费方按事件做增量就会拿到幻影事件。</li>
 *   <li><b>增量游标契约</b>——首页游标落在「现在」，带 {@code since} 只回更新的，
 *       {@code limit} 截断时给 {@code hasMore}，catalog 过滤在服务端做。</li>
 * </ol>
 *
 * <p>触发直接走服务层（与 {@link PaimonRestCatalogApiTests} 同一取向），
 * 读取走 MockMvc——读端点本身是薄封装，但它带着控制台门禁与拦截器的
 * 请求上下文，用 HTTP 路径测才覆盖得到。使用独立的 H2 库。
 */
@SpringBootTest(properties = {
        // 控制台扩展端点在门禁之后（console.required 默认开），登记一个静态令牌
        "paimon.rest.auth.tokens[0]=console-event-test-token",
        "spring.datasource.url=jdbc:h2:mem:paimon-events;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConsoleEventTests {

    private static final String EVENTS = "/api/console/v1/events";
    private static final String PREFIX = "paimon";
    private static final String TOKEN = "console-event-test-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private TableService tableService;

    @Autowired
    private PermissionManagementService permissions;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 变更落成事件

    @Test
    void tableLifecycleEmitsEventsWithContext() throws Exception {
        String database = "evt_lifecycle";
        createDatabase(database);

        // 模拟一次带请求 ID 的调用：拦截器在 HTTP 路径上做同一件事（取 X-Request-Id），
        // 服务层只读上下文，因此直接设上下文与走 HTTP 对事件内容是等价的
        withContext("evt-user", "evt-req-create", () -> tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(
                        new CommonDtos.Identifier(database, "evt_orders"), evtSchema())));

        withContext("evt-user", "evt-req-rename", () -> tableService.rename(PREFIX,
                new TableDtos.RenameTableRequest(
                        new CommonDtos.Identifier(database, "evt_orders"),
                        new CommonDtos.Identifier(database, "evt_orders_renamed"))));

        withContext("evt-user", "evt-req-drop", () -> tableService.drop(PREFIX, database,
                "evt_orders_renamed"));

        JsonNode page = fetchEvents("?catalog=evt-lifecycle&limit=1000");
        // catalog 过滤按 catalog 名（prefix），不是库名——不认识的 catalog 返回空页。
        // 两种表格式的事件在同一张表、同一个流里，过滤只是查询参数（§4）
        assertEquals(0, page.get("events").size(), "catalog 过滤值是 catalog 名，不是库名");

        JsonNode all = fetchEvents("?limit=1000");
        JsonNode created = findLatest(all, "table.created", database + ".evt_orders");
        assertEquals("PAIMON", created.get("attributes").get("format").asString(),
                "建表事件应带格式标识（两种表格式在同一股流里）");
        assertEquals("evt-user", created.get("principal").asString());
        assertEquals("evt-req-create", created.get("requestId").asString());
        assertEquals("TABLE", created.get("targetType").asString());

        JsonNode renamed = findLatest(all, "table.renamed", database + ".evt_orders_renamed");
        assertEquals(database + ".evt_orders", renamed.get("attributes").get("from").asString(),
                "改名事件的目标是目的地，来源在属性里");

        JsonNode dropped = findLatest(all, "table.dropped", database + ".evt_orders_renamed");
        assertTrue(dropped.get("attributes").has("external"),
                "删表事件带 external（表目录是否一起删取决于配置，事件只记事实）");
    }

    @Test
    void grantAndRevokeEmitEventsButMissesDoNot() throws Exception {
        String principal = "user:evt-alice";
        PermissionAssignment assignment = new PermissionAssignment(
                new PermissionResource("TABLE", "evt_grant_db", "evt_granted", null, null),
                "SELECT", principal, null, null);

        withContext("evt-admin", "evt-req-grant", () -> permissions.grant(PREFIX, assignment));
        withContext("evt-admin", "evt-req-revoke", () -> permissions.revoke(PREFIX, assignment));

        JsonNode all = fetchEvents("?limit=1000");
        assertEquals(2, countByTarget(all, "grant.changed", "TABLE evt_grant_db.evt_granted"),
                "授予与撤销各产生一条事件");
        // 找的是最新一条（撤销），主体与请求 ID 来自请求上下文
        JsonNode latest = findLatest(all, "grant.changed", "TABLE evt_grant_db.evt_granted");
        assertEquals("revoke", latest.get("attributes").get("action").asString());
        assertEquals("evt-admin", latest.get("principal").asString());
        // 授予那条：属性里带「授给了谁、授的什么、授在哪」
        JsonNode grant = findByAttribute(all, "grant.changed", "TABLE evt_grant_db.evt_granted",
                "action", "grant");
        assertEquals(principal, grant.get("attributes").get("grantee").asString());
        assertEquals("SELECT", grant.get("attributes").get("access").asString());
        assertEquals("TABLE", grant.get("attributes").get("resourceType").asString());
        JsonNode revoke = latest;
        long cursor = revoke.get("id").asLong();

        // 幂等撤销（赋权已不存在）不进事件流
        withContext("evt-admin", "evt-req-revoke-miss", () -> permissions.revoke(PREFIX, assignment));
        JsonNode after = fetchEvents("?limit=1000");
        assertEquals(2, countByTarget(after, "grant.changed", "TABLE evt_grant_db.evt_granted"),
                "撤销不存在的赋权是空操作，不应产生事件");
        assertTrue(latestId(after) >= cursor);
    }

    // ------------------------------------------------------------------ 游标契约

    @Test
    void cursorReturnsOnlyNewerEvents() throws Exception {
        // 首页：游标落在「现在」——带着它再来一次，返回空
        JsonNode first = fetchEvents("?limit=1000");
        long cursor = first.get("nextCursor").asLong();
        assertTrue(first.get("events").isEmpty()
                        || first.get("events").get(first.get("events").size() - 1).get("id").asLong() == cursor,
                "游标应是最新一条的 id");
        JsonNode idle = fetchEvents("?since=" + cursor + "&limit=1000");
        assertEquals(0, idle.get("events").size(), "没有新事件时增量拉取返回空页");
        // 发生一次变更后，同一个游标只带回新事件
        PermissionAssignment assignment = new PermissionAssignment(
                new PermissionResource("DATABASE", "evt_cursor_db", null, null, null),
                "CREATETABLE", "user:evt-cursor", null, null);
        withContext("evt-admin", "evt-req-cursor", () -> permissions.grant(PREFIX, assignment));

        JsonNode incremental = fetchEvents("?since=" + cursor + "&limit=1000");
        JsonNode events = incremental.get("events");
        assertTrue(events.size() >= 1, "游标之后发生了变更，应至少带回一条");
        for (JsonNode event : events) {
            assertTrue(event.get("id").asLong() > cursor, "增量页不应包含游标之前的条目");
        }

        // limit=1 截断：hasMore 为真、游标推进到本页最后一条
        JsonNode single = fetchEvents("?since=" + cursor + "&limit=1");
        assertEquals(1, single.get("events").size());
        assertTrue(single.get("hasMore").asBoolean(), "还有剩余条目时应给出 hasMore");
        assertEquals(single.get("events").get(0).get("id").asLong(),
                single.get("nextCursor").asLong(), "游标推进到本页最后一条");
    }

    @Test
    void firstPageIsLatestWindowInAscendingOrder() throws Exception {
        JsonNode page = fetchEvents("?limit=3");
        JsonNode events = page.get("events");
        assertTrue(events.size() <= 3);
        // 事件升序返回（内部倒序取再反转）：前端按「新在前」显示靠的是自己反转
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i).get("id").asLong() > events.get(i - 1).get("id").asLong(),
                    "事件页按游标升序返回");
        }
        assertFalse(page.get("hasMore").asBoolean() && events.size() < 3,
                "拉满一页才可能有更多");
    }

    // ------------------------------------------------------------------ 辅助

    private JsonNode fetchEvents(String query) throws Exception {
        MvcResult result = mockMvc.perform(get(EVENTS + query)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(200, result.getResponse().getStatus(), () -> "body=" + body);
        return mapper.readTree(body);
    }

    /** 在一页事件里找「指定类型 + 目标」的最新一条；找不到即断言失败。 */
    private JsonNode findLatest(JsonNode page, String type, String target) {
        JsonNode latest = null;
        for (JsonNode event : page.get("events")) {
            if (type.equals(event.get("type").asString()) && target.equals(event.get("target").asString())) {
                latest = event;
            }
        }
        assertTrue(latest != null, () -> "event not found: " + type + " on " + target);
        return latest;
    }

    private int countByTarget(JsonNode page, String type, String target) {
        int count = 0;
        for (JsonNode event : page.get("events")) {
            if (type.equals(event.get("type").asString()) && target.equals(event.get("target").asString())) {
                count++;
            }
        }
        return count;
    }

    /** 在一页事件里找「指定类型 + 目标 + 某属性值」的第一条；找不到即断言失败。 */
    private JsonNode findByAttribute(JsonNode page, String type, String target,
                                     String attribute, String value) {
        for (JsonNode event : page.get("events")) {
            if (type.equals(event.get("type").asString()) && target.equals(event.get("target").asString())
                    && event.get("attributes").has(attribute)
                    && value.equals(event.get("attributes").get(attribute).asString())) {
                return event;
            }
        }
        assertTrue(false, () -> "event not found: " + type + " on " + target
                + " with " + attribute + "=" + value);
        return null;
    }

    private long latestId(JsonNode page) {
        long latest = 0;
        for (JsonNode event : page.get("events")) {
            latest = Math.max(latest, event.get("id").asLong());
        }
        return latest;
    }

    /** 在设定的请求上下文里执行一次变更（事件从上下文取 principal / requestId）。 */
    private void withContext(String principal, String requestId, Runnable action) {
        RequestContext.setPrincipal(principal);
        RequestContext.setRequestId(requestId);
        try {
            action.run();
        } finally {
            RequestContext.clear();
        }
    }

    private void createDatabase(String name) {
        if (!databaseService.list(PREFIX, null, null).databases().contains(name)) {
            databaseService.create(PREFIX,
                    new DatabaseDtos.CreateDatabaseRequest(name, Map.of("owner", "evt")));
        }
    }

    private static TypeDtos.Schema evtSchema() {
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "id", "BIGINT NOT NULL"));
        schema.getFields().add(field(1, "name", "VARCHAR(20)"));
        schema.getPrimaryKeys().add("id");
        schema.getOptions().put("bucket", "1");
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
