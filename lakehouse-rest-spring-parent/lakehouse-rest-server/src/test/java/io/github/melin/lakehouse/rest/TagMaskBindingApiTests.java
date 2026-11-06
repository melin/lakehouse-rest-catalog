package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.AuditEventEntity;
import io.github.melin.lakehouse.rest.domain.repo.AuditEventRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import io.github.melin.lakehouse.rest.service.DataPolicyService;
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
 * 标签驱动脱敏（§8.1，P3）的 HTTP 层验收：绑定端点 + resolve 展开 + 三条冲突规则。
 *
 * <p><b>为什么单开一个类而不是塞进 {@code SensitivityApiTests}。</b>那一类验证的是
 * 「扫描 → 命中 → 标签」这条链，本类验证的是「标签 → 掩码」这条链：前者写
 * {@code lakehouse_column_tag}，后者读它并影响 {@code DataPolicyService.resolve} 的输出。
 * 两条链的失败方式完全不同（一个是识别不准，一个是策略下发错），混在一个类里
 * 会让「这条断言挂了是因为谁」变成一次考古。
 *
 * <p><b>本类要钉死的六件事，每一件都对应一种「看起来正常但实际错误」的实现：</b>
 * <ol>
 *   <li><b>没有策略的表也要脱敏。</b>只在「有 {@code DataPolicyEntity}」的分支里展开，
 *       会让 §8.1 那句「新增列被识别打标后自动纳入覆盖，不用改策略」变成空话——
 *       而它看起来完全正常，因为配过策略的表都工作。</li>
 *   <li><b>表级标签永不参与展开。</b>它的列名是空串。不显式排除的话，
 *       命中的 mask 会落进 {@code masks[""]}：要么静默不生效，要么（更糟）
 *       被当成「所有列」的前缀。</li>
 *   <li><b>显式 mask 优先，且冲突可见。</b>静默让绑定覆盖显式 mask，等于让一条
 *       catalog 级规则悄悄改掉某个治理员手工定的表级规则。</li>
 *   <li><b>同列多命中按 level / priority / 精确度选一条</b>，其余进 conflicts。
 *       靠插入顺序决定的话，同一列在不同实例上可能被脱敏成不同样子。</li>
 *   <li><b>hidden 与 mask 叠加时拒绝展开。</b>整列不可见与脱敏同时生效说不清谁赢，
 *       猜一个的后果是「这列既不在结果里、又‘被脱敏了’」。</li>
 *   <li><b>mask 模板写入时校验、执行时兜底。</b>绑定不知道会落在什么类型的列上，
 *       所以「结果类型等于列类型」留到引擎编译；但「fn 拼错」「表达式引用列」
 *       必须在写入时就拒——后者会把一条只对某列有意义的规则记成 catalog 级规则。</li>
 * </ol>
 *
 * <p>用独立的 H2 库：本类会写全局的 {@code paimon} catalog 配置（绑定是 catalog 级），
 * 与其它测试类共享内存库会让「列表里有几条绑定」取决于执行顺序。
 */
@SpringBootTest(properties = {
        // 本类不测鉴权：绑定端点的判权（DESCRIBE / ALTER）在 AuthzTests 那一层覆盖
        "lakehouse.rest.auth.enabled=false",
        "lakehouse.rest.sensitivity.seed-builtin-types=true",
        "spring.datasource.url=jdbc:h2:mem:tag-mask-binding;DB_CLOSE_DELAY=-1;"
                + "DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TagMaskBindingApiTests {

    private static final String M = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String DB = "tagdb";
    private static final String TABLE = "people";
    /** 只有表级标签的表：用来证明「表级标签不展开」而不是「恰好没命中」。 */
    private static final String LEVEL_ONLY_TABLE = "level_only";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableRepository tableRepository;
    @Autowired
    private AuditEventRepository eventRepository;
    @Autowired
    private DataPolicyService policyService;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 1. 绑定的写入与读回

    @Test
    @Order(1)
    void bindingIsUpsertedOnTheUniqueKeyAndWildcardsAreExplicit() throws Exception {
        createTables();

        // tagValue 省略 = 该键任意分级。服务层必须把它归一成空串落库——
        // 用 null 的话唯一索引下「同键通配」能插两条（MySQL 多行 NULL 互不冲突）
        JsonNode created = json(200, "PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "tagKey": "PII.*",
                 "mask": {"fn": "hash", "args": ["MD5"]}}
                """);
        long id = created.get("id").asLong();
        assertTrue(id > 0);
        assertEquals("", created.get("tagValue").asString(),
                "省略 tagValue 要归一成空串，而不是存 null");
        assertTrue(created.get("wildcardKey").asBoolean(), "PII.* 是键通配");
        assertTrue(created.get("wildcardValue").asBoolean(), "空 tagValue = 该键任意值");
        assertTrue(created.get("enabled").asBoolean(), "新建默认启用");
        assertNotNull(created.get("maskSql"), "要回渲染后的 SQL 供界面展示");
        assertFalse(created.get("maskSql").asString().isBlank(), "渲染结果不能是空串");

        // 同一把键再 PUT 是更新：id 不变、列表仍只有一条
        JsonNode updated = json(200, "PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "tagKey": "PII.*",
                 "mask": {"fn": "hash", "args": ["SHA-256"]}, "priority": 5}
                """);
        assertEquals(id, updated.get("id").asLong(), "唯一键命中同一行");
        assertEquals(5, updated.get("priority").asInt());
        assertEquals(1, json(200, "GET", M + "/tag-mask-bindings?catalog=" + PREFIX, null)
                .get("items").size(), "upsert 不产生第二行");

        // 事件带 kind=BINDING：这个事件类型被设置与绑定两条写路径共用，
        // 没有 kind 的话下游无法判断该刷新哪份缓存
        AuditEventEntity event = latestBindingEvent();
        assertEquals("BINDING", String.valueOf(event.getAttributes().get("kind")),
                () -> "绑定变更要带 kind=BINDING，实际属性=" + event.getAttributes());
        assertEquals("UPDATED", String.valueOf(event.getAttributes().get("action")));
    }

    // ------------------------------------------------------------------ 2. 没有策略，标签照样生效

    @Test
    @Order(2)
    void tagAloneProducesAMaskWithoutAnyTablePolicy() throws Exception {
        // 只打标签，不建任何 DataPolicyEntity
        json(200, "PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "tagdb", "table": "people",
                 "column": "id_card", "tagKey": "PII.CN_ID_CARD", "tagValue": "L4"}
                """);

        JsonNode found = resolved(DB + "." + TABLE);
        assertEquals("FOUND", found.get("status").asString(),
                "没策略但有标签命中绑定 → FOUND（§8.1「不用改策略」的落点）");
        // 全局序列化省略 null 字段：没有策略就没有版本号，这里两种形态都要认
        JsonNode version = found.get("version");
        assertTrue(version == null || version.isNull(),
                () -> "没有策略就没有版本号：引擎侧缓存必须靠标签事件失效，不能靠比对版本，"
                        + "实际=" + version);
        assertEquals(1, found.get("masks").size());
        JsonNode mask = found.get("masks").get(0);
        assertEquals("id_card", mask.get("column").asString());
        assertEquals("TAG", mask.get("origin").asString(),
                "origin 让「这列为什么是星号」能直接答上来");
        assertEquals("SHA-256", mask.get("mask").get("args").get(0).asString(),
                "下发的是绑定的 mask AST 本身（引擎侧零改动）");

        // 预览 / 主体试算必须与引擎看到的一样：两条读路径共用同一份展开，
        // 只改 resolve 会让控制台预览出明文而引擎侧是密文
        DataPolicyService.PrincipalPolicy dryRun =
                policyService.policyForPrincipal(PREFIX, DB, TABLE, "user:zhang");
        assertEquals("FOUND", dryRun.status(), "试算也要看到标签保护");
        assertTrue(dryRun.policy().masks().containsKey("id_card"),
                () -> "预览策略要带标签展开的 mask，实际=" + dryRun.policy().masks());
    }

    // ------------------------------------------------------------------ 3. 显式 mask 优先

    @Test
    @Order(3)
    void explicitMaskWinsAndTheConflictIsReported() throws Exception {
        String policyPath = policyPath(DB, TABLE);
        json(200, "PUT", policyPath,
                """
                {"masks": [{"column": "id_card",
                            "mask": {"fn": "show_first_n", "args": [3]}}]}
                """);

        JsonNode found = resolved(DB + "." + TABLE);
        assertEquals(1, found.get("masks").size());
        assertEquals("EXPLICIT", found.get("masks").get(0).get("origin").asString(),
                "显式 mask 赢（§8.2 优先级的第一条）");
        JsonNode conflict = firstConflict(found);
        assertEquals("EXPLICIT_WINS", conflict.get("reason").asString());
        assertEquals("id_card", conflict.get("column").asString());
        assertEquals("PII.CN_ID_CARD", conflict.get("tagKey").asString(),
                "冲突要指认是被哪条标签绑定撞上的");

        mockMvc.perform(delete(policyPath));
    }

    // ------------------------------------------------------------------ 4. 同列多命中选一条

    @Test
    @Order(4)
    void tighterBindingWinsAndTheLoserIsReported() throws Exception {
        // 精确绑定 + 更高优先级。它与 PII.* 同时命中 id_card 那一列
        json(200, "PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "tagKey": "PII.CN_ID_CARD", "priority": 10,
                 "mask": {"fn": "show_last_n", "args": [4]}}
                """);

        JsonNode found = resolved(DB + "." + TABLE);
        assertEquals(1, found.get("masks").size(), "一列只下发一条 mask");
        assertEquals("show_last_n", found.get("masks").get(0).get("mask").get("fn").asString(),
                "priority 更高的那条赢");
        boolean outranked = false;
        for (JsonNode conflict : found.get("conflicts")) {
            if ("OUTRANKED".equals(conflict.get("reason").asString())) {
                outranked = true;
                // 两条候选的**标签**键相同（都是 PII.CN_ID_CARD），区别在绑定的键上——
                // 只报 tagKey 的话两条记录一模一样，「输的是哪条规则」就答不上来了
                assertEquals("PII.*", conflict.get("bindingKey").asString(),
                        "被放弃的是那条泛化绑定");
                assertEquals("PII.CN_ID_CARD", conflict.get("tagKey").asString(),
                        "tagKey 记的是标签本身");
            }
        }
        assertTrue(outranked, () -> "落选候选要进 conflicts，不能静默吞：" + found.get("conflicts"));
    }

    // ------------------------------------------------------------------ 5. 表级标签永不展开

    @Test
    @Order(5)
    void tableLevelTagIsNeverExpanded() throws Exception {
        // 关键在**键名故意用自定义的**：sensitivity.level 是自动重算的表级键
        // （refreshTableLevel 的领域），用它会让人分不清「没展开」是因为规则生效
        // 还是因为那行被重算掉了。这里要证明的是「列名为空串的标签一律不展开」，
        // 与它叫什么键无关。
        json(200, "PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "tagKey": "tbl.level",
                 "mask": {"fn": "replace_with_null"}}
                """);
        // level_only 表只有这一条表级标签，没有任何列级标签
        json(200, "PUT", M + "/column-tags",
                """
                {"catalog": "paimon", "database": "tagdb", "table": "level_only",
                 "column": "", "tagKey": "tbl.level", "tagValue": "L4"}
                """);

        JsonNode none = resolved(DB + "." + LEVEL_ONLY_TABLE);
        assertEquals("NONE", none.get("status").asString(),
                "表级标签不参与展开（§5.5 已拍板）——它没有对应的列可遮");
        assertTrue(none.get("masks").isEmpty());

        // people 表同时有列级标签与表级标签：只该出列级那一列，且绝不能出现空列名
        JsonNode found = resolved(DB + "." + TABLE);
        for (JsonNode mask : found.get("masks")) {
            assertFalse(mask.get("column").asString().isEmpty(),
                    "空列名的 mask 一旦下发，引擎侧要么忽略它、要么把它当所有列");
        }

        deleteBinding("tbl.level");
    }

    // ------------------------------------------------------------------ 6. hidden 与 mask 互斥

    @Test
    @Order(6)
    void hiddenColumnRefusesTheTagMask() throws Exception {
        String policyPath = policyPath(DB, TABLE);
        json(200, "PUT", policyPath, """
                {"hiddenColumns": ["id_card"]}
                """);

        JsonNode found = resolved(DB + "." + TABLE);
        assertTrue(found.get("masks").isEmpty(),
                "整列不可见时不再展开 mask：两者叠加说不清谁生效");
        assertEquals("id_card", found.get("hiddenColumns").get(0).asString());
        JsonNode conflict = firstConflict(found);
        assertEquals("HIDDEN_CONFLICT", conflict.get("reason").asString());

        mockMvc.perform(delete(policyPath));
    }

    // ------------------------------------------------------------------ 7. 模板校验

    @Test
    @Order(7)
    void maskTemplateIsValidatedAtWriteTime() throws Exception {
        // 表达式里引用列：绑定要落在未知的列上，写死一个列名会让它落到别的列时
        // 把那一列的值搬过来。报错要指认是哪一个 col
        String referencing = statusWithBody("PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "tagKey": "PII.CN_MOBILE",
                 "mask": {"expr": {"fn": "upper", "args": [{"col": "name"}]}}}
                """);
        assertEquals(400, statusOf(referencing), () -> "引用列的模板必须被拒：" + referencing);
        assertTrue(referencing.contains("must not reference a column"),
                () -> "报错要说清是「不能引用列」而不是「列不存在」：" + referencing);

        // 标准集里的 fn 拼错 → 400（这是最常见的写法错误）
        assertEquals(400, status("PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "tagKey": "PII.CN_MOBILE",
                 "mask": {"fn": "mask_phone"}}
                """), "未知的标准集函数要 400");

        // 参数个数不对 → 400
        assertEquals(400, status("PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "tagKey": "PII.CN_MOBILE",
                 "mask": {"fn": "show_first_n"}}
                """), "show_first_n 缺参数要 400");

        // catalog 不存在 → 404（不是把绑定建成一个孤儿行）
        assertEquals(404, status("PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "no_such_catalog", "tagKey": "PII.*",
                 "mask": {"fn": "hash", "args": ["MD5"]}}
                """), "catalog 不存在要 404");

        // tagKey 必填
        assertEquals(400, status("PUT", M + "/tag-mask-bindings",
                """
                {"catalog": "paimon", "mask": {"fn": "hash", "args": ["MD5"]}}
                """), "tagKey 必填");

        // 被拒的请求什么都不落：此时库里有 O1 的 PII.* 与 O4 的 PII.CN_ID_CARD 两条
        // （O5 建的那条 tbl.level 已被自己清掉）
        assertEquals(2, json(200, "GET", M + "/tag-mask-bindings?catalog=" + PREFIX, null)
                .get("items").size(), "四次被拒的写入不该留下任何行");
    }

    // ------------------------------------------------------------------ 8. 删绑定即回到明文

    @Test
    @Order(8)
    void deletingBindingsFallsBackToNoProtection() throws Exception {
        for (JsonNode item : json(200, "GET", M + "/tag-mask-bindings?catalog=" + PREFIX, null)
                .get("items")) {
            json(204, "DELETE", M + "/tag-mask-bindings/" + item.get("id").asLong(), null);
        }
        JsonNode none = resolved(DB + "." + TABLE);
        assertEquals("NONE", none.get("status").asString(),
                "绑定删光之后，标签还在但没有任何 mask——这正是「删绑定会立刻回到明文」，"
                        + "所以删除必须发事件");
        assertTrue(none.get("masks").isEmpty());

        // 删除也发事件（kind=BINDING / action=DELETED）：它是「这些列为什么突然明文了」
        // 唯一的一手线索
        assertTrue(eventRepository.findAll().stream()
                        .filter(event -> "sensitivity.binding.changed".equals(event.getType()))
                        .anyMatch(event -> "DELETED".equals(
                                String.valueOf(event.getAttributes().get("action")))),
                "删绑定要留 DELETED 事件");
    }

    // ------------------------------------------------------------------ 工具

    /** 建两张表：people（含 id_card 列）与 level_only（只用于表级标签的负例）。 */
    private void createTables() {
        if (!databaseService.list(PREFIX, null, null, null, null).databases().contains(DB)) {
            databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(DB, Map.of()));
        }
        createTableIfAbsent(TABLE, List.of(new String[]{"id", "BIGINT NOT NULL"},
                new String[]{"name", "VARCHAR(100)"},
                new String[]{"id_card", "VARCHAR(18)"}));
        createTableIfAbsent(LEVEL_ONLY_TABLE, List.of(new String[]{"id", "BIGINT NOT NULL"},
                new String[]{"note", "VARCHAR(64)"}));
    }

    private void createTableIfAbsent(String table, List<String[]> columns) {
        if (tableRepository.findByCatalogIdAndDatabaseIdAndName(
                catalogId(), databaseId(DB), table).isPresent()) {
            return;
        }
        TypeDtos.Schema schema = new TypeDtos.Schema();
        int id = 0;
        for (String[] column : columns) {
            TypeDtos.DataField field = new TypeDtos.DataField();
            field.setId(id++);
            field.setName(column[0]);
            field.setType(column[1]);
            schema.getFields().add(field);
        }
        schema.getPrimaryKeys().add("id");
        schema.getOptions().put("bucket", "1");
        tableService.create(PREFIX, DB,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(DB, table), schema));
    }

    private JsonNode resolved(String dbTable) throws Exception {
        JsonNode policies = json(200, "GET", M + "/policies/resolve?catalog=" + PREFIX
                + "&tables=" + dbTable, null).get("policies");
        for (JsonNode node : policies) {
            if (dbTable.equals(node.get("table").asString())) {
                return node;
            }
        }
        throw new AssertionError("resolve 少回了这张表: " + dbTable + " / " + policies);
    }

    private static JsonNode firstConflict(JsonNode resolvedPolicy) {
        JsonNode conflicts = resolvedPolicy.get("conflicts");
        assertNotNull(conflicts, "每个 resolve 结果都带 conflicts（可以是空数组）");
        assertTrue(conflicts.size() >= 1, () -> "期望至少一条冲突，实际=" + resolvedPolicy);
        return conflicts.get(0);
    }

    private void deleteBinding(String tagKey) throws Exception {
        for (JsonNode item : json(200, "GET", M + "/tag-mask-bindings?catalog=" + PREFIX, null)
                .get("items")) {
            if (tagKey.equals(item.get("tagKey").asString())) {
                json(204, "DELETE", M + "/tag-mask-bindings/" + item.get("id").asLong(), null);
            }
        }
    }

    /** 最后一条 {@code sensitivity.binding.changed} 事件（**按 id 倒序**，不依赖 findAll 的返回顺序）。 */
    private AuditEventEntity latestBindingEvent() {
        List<AuditEventEntity> matched = new ArrayList<>();
        for (AuditEventEntity event : eventRepository.findAll()) {
            if ("sensitivity.binding.changed".equals(event.getType())) {
                matched.add(event);
            }
        }
        assertFalse(matched.isEmpty(), "绑定写入必须发 sensitivity.binding.changed");
        matched.sort((left, right) -> Long.compare(right.getId(), left.getId()));
        return matched.get(0);
    }

    private String catalogId() {
        return databaseService.require(PREFIX, DB).getCatalogId();
    }

    private String databaseId(String database) {
        return databaseService.require(PREFIX, database).getId();
    }

    private static String policyPath(String database, String table) {
        return M + "/policies/tables/" + PREFIX + "/" + database + "/" + table;
    }

    private int status(String method, String path, String body) throws Exception {
        return mockMvc.perform(request(method, path, body)).andReturn().getResponse().getStatus();
    }

    /** 同 {@link #status}，但把响应体一起带回来（断言报错文案用）。 */
    private String statusWithBody(String method, String path, String body) throws Exception {
        MvcResult result = mockMvc.perform(request(method, path, body)).andReturn();
        return result.getResponse().getStatus() + " " + result.getResponse().getContentAsString();
    }

    private static int statusOf(String statusWithBody) {
        return Integer.parseInt(statusWithBody.substring(0, 3));
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
