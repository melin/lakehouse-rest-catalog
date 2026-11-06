package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.SensitivityScheduleEntity;
import io.github.melin.lakehouse.rest.domain.repo.SensitivityScheduleRepository;
import io.github.melin.lakehouse.rest.service.SensitivityScheduleDispatcher;
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

/**
 * 自建调度的 HTTP 层与节拍验收（设计见 {@code docs/sensitive-data-design.md} §4.6 / §5.6.3 / §12 P4 行）。
 *
 * <p><b>这一层补的是「配置能建出来」与「只触发一次」两件只有走完整链路才看得见的事。</b>
 * {@code CronSpecTests} 覆盖的是表达式本身；这里覆盖的是端点、乐观锁、范围递进、
 * 以及 {@code next_run_at} 的条件更新——把它们桩掉之后，「多副本下每小时触发两次」
 * 这类问题在测试里永远不会出现。
 *
 * <ol>
 *   <li><b>新建时 {@code next_run_at} 就算好。</b>留空的表现是「保存成功、下次运行时刻未知」，
 *       而节拍那侧还要为「空值怎么算」再实现一遍——两次推导在同一份 cron 上给出不同答案时，
 *       面板预览与真实触发会对不上，双方都不报错。
 *   <li><b>改名 400。</b>{@code lakehouse_sensitivity_scan.schedule_name} 存的就是这个名字，
 *       而扫描是历史：改名会让「这条扫描是哪条调度项触发的」在库里失去指向。
 *   <li><b>{@code METADATA_ONLY} + {@code SPARK} 与不可派发 catalog 都是 400。</b>
 *       前者下推没有收益，后者是 fail-closed（§5.6.5）——两者的失败都要发生在**建的时候**，
 *       否则现象是「调度项看起来配好了，凌晨三点什么都没发生」。
 *   <li><b>抢占只成功一次。</b>同一拍调两次 {@code claimDue()}，第二次必须为空——
 *       这是「多副本只触发一次」里正确性的那一道（另一道是租约，只降噪）。
 *   <li><b>停用后不再被抢占。</b>停用的语义是「规则还在、暂时不跑」，
 *       而历史扫描仍按 {@code schedule_name} 可查——所以停用不能是删除。
 * </ol>
 *
 * <p>本类关掉优化调度节拍（{@code optimizer.scheduler-enabled=false}）：真实节拍每 60 秒
 * 一拍，一旦在用例中途触发，就会把本类准备好的「已到期」行提前抢走，得到一条
 * 只在慢机器上偶发的红。{@code claimDue()} 直接调用即可，它的语义与节拍里那条路径一致。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.auth.enabled=false",
        // 真实节拍会在用例中途抢走本类刻意改成「已到期」的行，关掉它让触发时机可控
        "lakehouse.rest.optimization.scheduler-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SensitivityScheduleApiTests {

    private static final String M = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String NAME = "nightly_scan";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private SensitivityScheduleRepository repository;
    @Autowired
    private SensitivityScheduleDispatcher dispatcher;

    private final JsonMapper mapper = JsonMapper.builder().build();

    /** 新建出来的一条调度项，后续用例都围着它转。 */
    private static Long scheduleId;

    // ------------------------------------------------------------------ 1. 新建

    @Test
    @Order(1)
    void createComputesNextRunAt() throws Exception {
        JsonNode created = json(201, "POST", M + "/sensitivity-schedules",
                body("{\"name\":\"" + NAME + "\",\"cron\":\"0 3 * * *\",\"catalog\":\"" + PREFIX + "\","
                        + "\"scope\":\"TABLE\",\"database\":\"sens_db\",\"table\":\"people\","
                        + "\"mode\":\"METADATA_ONLY\",\"sampleSize\":500}"));

        scheduleId = created.get("id").asLong();
        assertEquals(NAME, created.get("name").asString());
        // cron 原样保留用户写的那一份（5 段不归一化成 6 段）
        assertEquals("0 3 * * *", created.get("cron").asString());
        assertEquals("TABLE", created.get("scope").asString());
        assertEquals("sens_db", created.get("database").asString());
        assertEquals("people", created.get("table").asString());
        assertEquals(500, created.get("sampleSize").asInt());
        assertTrue(created.get("enabled").asBoolean(), "新建默认启用");
        assertEquals(0, created.get("entityVersion").asInt());
        // 请求没传 execution，落的是生效值（全局默认 IN_PROCESS），而不是 null
        assertEquals("IN_PROCESS", created.get("execution").asString());
        long nextRunAt = created.get("nextRunAt").asLong();
        assertTrue(nextRunAt > System.currentTimeMillis(),
                "新建时就要算好 next_run_at 且在未来，实际=" + nextRunAt);
    }

    /** 同名 409：唯一键撞车会以 500 冒出来，而「名字重复」是用户能自己改的错误。 */
    @Test
    @Order(2)
    void duplicateNameConflicts() throws Exception {
        JsonNode error = json(409, "POST", M + "/sensitivity-schedules",
                body("{\"name\":\"" + NAME + "\",\"cron\":\"0 4 * * *\",\"catalog\":\"" + PREFIX + "\"}"));
        assertTrue(error.toString().contains(NAME), "冲突响应里要带上撞车的名字");
    }

    /**
     * 非法 cron 在写入时 400。
     *
     * <p>三种都验：段数不对、Quartz 扩展、永不匹配（2 月 30 日）。存进去之后它们
     * 分别表现为「永远不跑」与「按完全不同的含义跑」，都极难从现象追回配置。
     */
    @Test
    @Order(3)
    void unusableCronIsRejected() throws Exception {
        for (String cron : List.of("0 0 3 * * * 2026", "0 3 ? * *", "0 0 30 2 *")) {
            json(400, "POST", M + "/sensitivity-schedules",
                    body("{\"name\":\"bad_" + Math.abs(cron.hashCode()) + "\",\"cron\":\"" + cron
                            + "\",\"catalog\":\"" + PREFIX + "\"}"));
        }
    }

    /** 范围必须递进：说了 TABLE 却没有表名，是「说了一套、填了另一套」。 */
    @Test
    @Order(4)
    void scopeMustBeProgressive() throws Exception {
        json(400, "POST", M + "/sensitivity-schedules",
                body("{\"name\":\"no_table\",\"cron\":\"0 3 * * *\",\"catalog\":\"" + PREFIX + "\","
                        + "\"scope\":\"TABLE\",\"database\":\"sens_db\"}"));
        json(400, "POST", M + "/sensitivity-schedules",
                body("{\"name\":\"no_database\",\"cron\":\"0 3 * * *\",\"catalog\":\"" + PREFIX + "\","
                        + "\"scope\":\"DATABASE\"}"));
    }

    /**
     * {@code METADATA_ONLY} + {@code SPARK}：400，且报错要指向这两项的组合。
     *
     * <p>元数据档下推没有任何收益，只多一次作业调度开销（§5.6.1）。静默改走 A 会让
     * 「我明明选了 SPARK」变成一条查不出来的行为。
     */
    @Test
    @Order(5)
    void metadataOnlyWithSparkIsRejected() throws Exception {
        JsonNode error = json(400, "POST", M + "/sensitivity-schedules",
                body("{\"name\":\"spark_metadata\",\"cron\":\"0 3 * * *\",\"catalog\":\"" + PREFIX + "\","
                        + "\"mode\":\"METADATA_ONLY\",\"execution\":\"SPARK\"}"));
        assertTrue(error.toString().contains("METADATA_ONLY"),
                () -> "报错要指向 METADATA_ONLY 与 SPARK 的组合，实际=" + error);
    }

    /**
     * 不可派发的 catalog 建 {@code execution=SPARK}：400（fail-closed，§5.6.5 与 §12 P4 行）。
     *
     * <p>{@code paimon} 这个预置 catalog 在本环境没有执行集群，因此它正好是「不可派发」。
     * 等到触发时才失败的表现是「调度项看起来配好了，凌晨三点什么都没发生」——
     * 而定时任务最难排查的坏法正是这一种。
     */
    @Test
    @Order(6)
    void sparkOnNonDispatchableCatalogIsRejected() throws Exception {
        JsonNode error = json(400, "POST", M + "/sensitivity-schedules",
                body("{\"name\":\"spark_sampled\",\"cron\":\"0 3 * * *\",\"catalog\":\"" + PREFIX + "\","
                        + "\"mode\":\"SAMPLED\",\"execution\":\"SPARK\"}"));
        assertTrue(error.toString().contains("执行集群"),
                () -> "报错要指向「缺执行集群」这个前置依赖，实际=" + error);
    }

    // ------------------------------------------------------------------ 2. 改

    /**
     * 改名 400，改别的字段照旧成功。
     *
     * <p>「换个名字」的正确做法是新建一条、停用旧的：历史留在旧名字下，那才叫可审计。
     */
    @Test
    @Order(7)
    void renameIsRejected() throws Exception {
        JsonNode error = json(400, "PUT", M + "/sensitivity-schedules/" + scheduleId,
                body("{\"name\":\"renamed\",\"cron\":\"0 4 * * *\",\"catalog\":\"" + PREFIX + "\"}"));
        assertTrue(error.toString().contains("不支持改名"),
                () -> "报错要说清为什么不支持改名，实际=" + error);

        // 改 cron 是允许的，且 next_run_at 会随之重算
        JsonNode updated = json(200, "PUT", M + "/sensitivity-schedules/" + scheduleId,
                body("{\"cron\":\"*/30 * * * *\",\"catalog\":\"" + PREFIX + "\","
                        + "\"scope\":\"CATALOG\",\"mode\":\"SAMPLED\",\"entityVersion\":0}"));
        assertEquals("*/30 * * * *", updated.get("cron").asString());
        // 改成 catalog 级之后，旧的库名/表名必须被置空：否则读模型里会出现
        // 「scope=CATALOG 但有 table」这种自相矛盾的行
        assertEquals("CATALOG", updated.get("scope").asString());
        // 更宽的层级要清掉库名与表名：否则读模型里会出现「scope=CATALOG 但有 table」
        // 这种自相矛盾的行，而按表名过滤的界面会把它算进那张表。
        // 判空要允许「字段整个不出现」——本仓的 JSON 序列化对 null 是省略而不是输出 null
        // （与 issued_token_type 那条「逐字不变」的口径同一套配置）。
        assertTrue(isNullOrAbsent(updated, "database"), "更宽的层级要清掉库名");
        assertTrue(isNullOrAbsent(updated, "table"), "更宽的层级要清掉表名");
        assertEquals(1, updated.get("entityVersion").asInt(), "改一次版本号 +1");
        assertTrue(updated.get("nextRunAt").asLong() > System.currentTimeMillis());
    }

    /** 乐观锁：带一个过期的 {@code entityVersion} 回来要 409，而不是悄悄覆盖别人的编辑。 */
    @Test
    @Order(8)
    void staleEntityVersionConflicts() throws Exception {
        json(409, "PUT", M + "/sensitivity-schedules/" + scheduleId,
                body("{\"cron\":\"0 5 * * *\",\"catalog\":\"" + PREFIX + "\",\"entityVersion\":0}"));
    }

    // ------------------------------------------------------------------ 3. 读与 404/400

    @Test
    @Order(9)
    void listGetAndErrorShapes() throws Exception {
        JsonNode list = json(200, "GET", M + "/sensitivity-schedules?catalog=" + PREFIX, null);
        assertTrue(list.get("items").isArray(), "清单用 items 包装（不分页，量级是几十条）");
        boolean found = false;
        for (JsonNode item : list.get("items")) {
            found |= NAME.equals(item.get("name").asString());
        }
        assertTrue(found, "按 catalog 过滤要能查到刚建的那一条");

        json(200, "GET", M + "/sensitivity-schedules/" + scheduleId, null);
        // 不存在的 id 是 404（资源类型 SENSITIVITY_SCHEDULE），不是 403 也不是 500
        json(404, "GET", M + "/sensitivity-schedules/999999", null);
        // 路径参数写错是调用方的问题：400，不是全局处理器兜不住的 500
        json(400, "GET", M + "/sensitivity-schedules/abc", null);
    }

    // ------------------------------------------------------------------ 4. 节拍：只触发一次

    /**
     * 同一拍的第二次 {@code claimDue()} 必须为空——正确性的那一道闸门。
     *
     * <p>把 {@code next_run_at} 手工改到过去，模拟「这一拍到期」。第一次抢占成功
     * 且 {@code next_run_at} 被推进（推进发生在抢占时、不是跑完之后：否则慢扫描期间
     * 每一拍都会把它当到期项）；第二次读到的已是未来，因而什么都不做。
     */
    @Test
    @Order(10)
    void claimDueTriggersOnlyOnce() throws Exception {
        SensitivityScheduleEntity entity = repository.findById(scheduleId).orElseThrow();
        entity.setEnabledFlag(true);
        entity.setNextRunAt(System.currentTimeMillis() - 60_000L);
        repository.save(entity);

        List<SensitivityScheduleEntity> first = dispatcher.claimDue();
        assertEquals(1, first.size(), "到期的启用项应当被这一拍抢占一次");
        assertEquals(NAME, first.get(0).getName());
        assertNotNull(first.get(0).getLastRunAt(), "抢占时就记下 last_run_at");
        assertTrue(first.get(0).getNextRunAt() > System.currentTimeMillis(),
                "抢占时就把 next_run_at 推进到下一个 cron 点");

        assertTrue(dispatcher.claimDue().isEmpty(), "同一拍的第二台实例不能再次抢到它");
    }

    /** 停用后不再被抢占；重新启用后又能被抢到（停用不是删除）。 */
    @Test
    @Order(11)
    void disabledScheduleIsNotClaimed() throws Exception {
        SensitivityScheduleEntity entity = repository.findById(scheduleId).orElseThrow();
        entity.setEnabledFlag(false);
        entity.setNextRunAt(System.currentTimeMillis() - 60_000L);
        repository.save(entity);

        assertTrue(dispatcher.claimDue().isEmpty(), "停用的调度项不该被抢占");

        entity.setEnabledFlag(true);
        repository.save(entity);
        assertEquals(1, dispatcher.claimDue().size(), "重新启用后又能被抢到");
    }

    /** 删除是真删：再 GET 就 404。历史扫描不受影响（按 {@code schedule_name} 存字符串）。 */
    @Test
    @Order(12)
    void deleteRemovesTheRow() throws Exception {
        json(204, "DELETE", M + "/sensitivity-schedules/" + scheduleId, null);
        json(404, "GET", M + "/sensitivity-schedules/" + scheduleId, null);
        assertFalse(repository.findByName(NAME).isPresent(), "删掉之后按名字也不该查得到");
    }

    // ------------------------------------------------------------------ 工具

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

    /**
     * 请求体先自己解析一遍再发。
     *
     * <p><b>不是多此一举。</b>本类有若干「期望 400」的断言，而一个少写引号的请求体
     * 也会得到 400——那会让「因为语法错」冒充「因为规则对」。解析失败在这里就抛，
     * 报错指向用例自己，而不是服务端的校验结果。
     */
    private static String body(String json) {
        try {
            MAPPER.readTree(json);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("用例里的请求体不是合法 JSON：" + json, exception);
        }
        return json;
    }

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    /** 字段「没有值」：可能是没这个键，也可能是显式的 {@code null}——两种都算清空。 */
    private static boolean isNullOrAbsent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull();
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
