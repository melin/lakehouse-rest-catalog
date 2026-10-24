package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.AuditEventEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.AuditEventRepository;
import io.github.melin.lakehouse.rest.domain.repo.DataPolicyRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import io.github.melin.lakehouse.rest.service.CatalogFileService;
import io.github.melin.lakehouse.rest.service.DataPolicyService;
import io.github.melin.lakehouse.rest.support.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 行过滤 / 列脱敏策略的集成测试：CRUD 与乐观锁、表达式 fail-closed、批量下发个性化、
 * 读路径收口（分区 / 文件下载 403；预览 P1 起按策略求值，见 PolicyPreviewTests）与表生命周期联动。
 *
 * <p>表达式校验的失败分支在这里用 HTTP 层逐条打（400 + 指认形状的报错）：这批断言
 * 是 §5.3.3「建时 fail-closed」的落地——半个策略比没有策略更危险，校验失败的请求
 * 必须什么都不落，最后一条断言专门验证这一点。
 *
 * <p>下发（resolve）按调用者个性化：{@code $principal} 折叠、豁免名单、停用与没策略
 * 四种状态各自可见——「没策略（NONE）」与「已停用（DISABLED）」对引擎都是放行，
 * 但状态如实分开回，排障时能看出区别（§5.4 的失败模型）。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "lakehouse.rest.policy.enabled=true",
        // 控制台端点默认要求先登录（console.required=true）。与 ConsoleApiTests 同一做法：
        // 登记一个静态令牌，预览 / 分区的请求统一带上——顺带覆盖「门禁开启时静态令牌可用」
        "lakehouse.rest.auth.tokens[0]=data-policy-test-token"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataPolicyApiTests {

    private static final String M = "/api/management/v1/policies";
    private static final String PREFIX = "paimon";
    private static final String DB = "policy_db";
    private static final String TABLE = "t1";
    private static final String TABLE2 = "t2";
    private static final String BEARER = "Bearer data-policy-test-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableRepository tableRepository;
    @Autowired
    private DataPolicyService policyService;
    @Autowired
    private DataPolicyRepository policyRepository;
    @Autowired
    private CatalogFileService fileService;
    @Autowired
    private AuditEventRepository eventRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 1. CRUD 与乐观锁

    @Test
    @Order(1)
    void createReadListAndVersionBump() throws Exception {
        createPaimonTable(DB, TABLE);
        createPaimonTable(DB, TABLE2);

        // 首建：行过滤（$principal 占位符走行过滤专用通道）+ 两条 mask + 豁免名单
        JsonNode created = json(200, "PUT", policyPath(DB, TABLE), """
                {
                  "rowFilter": {"op": "eq", "left": {"col": "dt"},
                                "right": {"op": "like", "left": {"principal": true},
                                          "right": {"lit": "%"}}},
                  "masks": [
                    {"column": "name", "mask": {"fn": "show_first_n", "args": [3]}},
                    {"column": "id", "mask": {"fn": "replace_with_null"},
                     "allowNullOnRequired": true}
                  ],
                  "exemptPrincipals": ["svc_owner"]
                }
                """);
        assertEquals(1, created.get("version").asInt(), "首建版本从 1 起");
        assertEquals("engine", created.get("enforcedScope").asString());
        assertTrue(created.get("rowFilterSql").asString().toLowerCase().contains("coalesce"),
                "行过滤外层自动包 coalesce(expr, false)，SQL 渲染要能看出来");
        assertEquals(2, created.get("masks").size());
        assertEquals("svc_owner", created.get("exemptPrincipals").get(0).asString(),
                "豁免名单在详情里可见（查看它需要 TABLE:ALTER，判权在控制器）");

        // 更新必须带 baseVersion：缺省 / 陈旧都 409；对上了才递增
        json(409, "PUT", policyPath(DB, TABLE), "{\"rowFilter\": {\"op\": \"is_null\", \"operand\": {\"col\": \"dt\"}}}");
        json(409, "PUT", policyPath(DB, TABLE),
                "{\"baseVersion\": 99, \"rowFilter\": {\"op\": \"is_null\", \"operand\": {\"col\": \"dt\"}}}");
        JsonNode updated = json(200, "PUT", policyPath(DB, TABLE), """
                {
                  "baseVersion": 1,
                  "rowFilter": {"op": "is_null", "operand": {"col": "dt"}},
                  "masks": [{"column": "name", "mask": {"fn": "mask_alphanum"}}],
                  "exemptPrincipals": []
                }
                """);
        assertEquals(2, updated.get("version").asInt());
        assertEquals(1, updated.get("masks").size(), "PUT 是整份替换：这次少提交的 mask 就是被删掉的");
        assertTrue(updated.get("exemptPrincipals").isEmpty(), "空名单覆盖旧名单");

        // 详情与列表
        JsonNode detail = json(200, "GET", policyPath(DB, TABLE), null);
        assertEquals(2, detail.get("version").asInt());
        assertTrue(detail.get("enabled").asBoolean(), "默认启用");
        assertTrue(detail.get("exemptPrincipals").isEmpty(), "上一轮的空名单覆盖了旧名单");
        JsonNode list = json(200, "GET", M + "?catalog=" + PREFIX + "&refTable=" + TABLE, null);
        assertEquals(1, list.get("items").size(), "refTable 反查只回这一张表");
        assertTrue(list.get("items").get(0).get("hasRowFilter").asBoolean());

        // 审计事件：策略变化单独一个类型（与优化策略分开，§8.2）
        assertTrue(eventRepository.findAll().stream()
                        .anyMatch(event -> "policy.changed".equals(event.getType())),
                "策略增改要落 policy.changed 审计事件");
    }

    // ------------------------------------------------------------------ 2. 表达式 fail-closed

    @Test
    @Order(2)
    void invalidExpressionsAreRejectedAndNothingIsStored() throws Exception {
        createPaimonTable(DB, TABLE2);
        String path = policyPath(DB, TABLE2);

        record Bad(String label, String body, String messagePart) {}
        List<Bad> bad = List.of(
                // 白名单外函数：聚合、会话函数、UDF 一律不收（§5.3.2）
                new Bad("unknown fn", """
                        {"rowFilter": {"fn": "current_user", "args": []}}""", "unsupported function"),
                // 行过滤必须是布尔表达式
                new Bad("non-boolean", """
                        {"rowFilter": {"col": "name"}}""", "boolean"),
                // 引用不存在的列
                new Bad("missing column", """
                        {"rowFilter": {"op": "eq", "left": {"col": "region"}, "right": {"lit": "CN"}}}""",
                        "column does not exist"),
                // 嵌套字段：整列不可见 v1 不做，嵌套引用一并拒绝（§5.7）
                new Bad("nested field", """
                        {"rowFilter": {"op": "eq", "left": {"col": "dt.year"}, "right": {"lit": 2026}}}""",
                        "nested field"),
                // LIKE 模式必须是字面量：模式里出现列引用会随数据漂（§7.5）
                new Bad("like non-literal", """
                        {"rowFilter": {"op": "like", "left": {"col": "name"}, "right": {"col": "dt"}}}""",
                        "string literal"),
                // mask 类型规则：md5 产出 STRING，落不到 BIGINT 列上（§5.3.3）
                new Bad("mask type mismatch", """
                        {"masks": [{"column": "id",
                                    "mask": {"expr": {"fn": "md5", "args": [{"col": "id"}]}}}]}""",
                        "must equal"),
                // 标准集外的 mask 名字
                new Bad("unknown standard mask", """
                        {"masks": [{"column": "name", "mask": {"fn": "mask_hash", "args": ["MD5"]}}]}""",
                        "unknown standard mask"),
                // 字符串类 mask 不能落在 BIGINT 列上
                new Bad("string mask on bigint", """
                        {"masks": [{"column": "id",
                                    "mask": {"fn": "show_first_n", "args": [3]}}]}""",
                        "string"),
                // hash 算法必须显式且在白名单内（§3.4 第 4 条）
                new Bad("bad hash algorithm", """
                        {"masks": [{"column": "name",
                                    "mask": {"fn": "hash", "args": ["CRC32"]}}]}""",
                        "hash"),
                // 置空 mask 落在非空列必须显式确认（§5.3.3）
                new Bad("null on required without confirm", """
                        {"masks": [{"column": "id", "mask": {"fn": "replace_with_null"}}]}""",
                        "allowNullOnRequired"));

        for (Bad item : bad) {
            MvcResult result = mockMvc.perform(put(path)
                    .contentType(MediaType.APPLICATION_JSON).content(item.body())).andReturn();
            String responseBody = result.getResponse().getContentAsString();
            assertEquals(400, result.getResponse().getStatus(),
                    () -> item.label() + " should be rejected, body=" + responseBody);
            assertTrue(responseBody.contains(item.messagePart()),
                    () -> item.label() + " 的报错要指认形状: " + responseBody);
        }

        // 校验失败的请求什么都不落：上一批失败之后详情仍然是「没策略」（204）
        json(204, "GET", path, null);
    }

    // ------------------------------------------------------------------ 3. 批量下发（引擎通道）

    @Test
    @Order(3)
    void resolveFoldsPrincipalExemptsAndReportsStates() throws Exception {
        // t1 带策略（用例 1 留下的，rowFilter 是 is_null(dt)）；t2 刻意豁免当前主体
        String principal = json(200, "GET", M + "/resolve?catalog=" + PREFIX
                + "&tables=" + DB + "." + TABLE, null).get("principal").asString();

        JsonNode exempt = json(200, "PUT", policyPath(DB, TABLE2),
                "{\"rowFilter\": {\"op\": \"is_null\", \"operand\": {\"col\": \"dt\"}},"
                        + " \"exemptPrincipals\": [\"" + principal + "\"]}");
        assertEquals(1, exempt.get("version").asInt());

        JsonNode result = json(200, "GET", M + "/resolve?catalog=" + PREFIX
                + "&tables=" + DB + "." + TABLE + "," + DB + "." + TABLE2 + ","
                + DB + ".no_policy", null);
        assertEquals(principal, result.get("principal").asString());
        JsonNode policies = result.get("policies");
        assertEquals(3, policies.size(), "请求了几张表就回几条，缺席才是异常");

        JsonNode found = byTable(policies, DB + "." + TABLE);
        assertEquals("FOUND", found.get("status").asString());
        assertEquals(2, found.get("version").asInt(), "version 就是下发的 etag");
        assertTrue(found.get("rowFilterSql").asString().contains("IS NULL"));
        assertEquals(1, found.get("masks").size());
        assertFalse(found.get("exempted").asBoolean());

        JsonNode exempted = byTable(policies, DB + "." + TABLE2);
        assertEquals("EXEMPT", exempted.get("status").asString(), "豁免名单内拿到空表达式");
        assertTrue(exempted.get("exempted").asBoolean());
        JsonNode folded = exempted.get("rowFilter");
        assertTrue(folded == null || folded.isNull() || folded.isMissingNode(),
                "豁免的下发不带表达式");

        JsonNode none = byTable(policies, DB + ".no_policy");
        assertEquals("NONE", none.get("status").asString(), "没策略是正常响应（引擎放行），不是错误");
    }

    @Test
    @Order(4)
    void disabledPolicyResolvesAsDisabledAndEnabledToggleFlipsBack() throws Exception {
        // enabled=false：定义保留、resolve 如实回 DISABLED（与 NONE 分开）
        JsonNode disabled = json(200, "PUT", policyPath(DB, TABLE2),
                "{\"baseVersion\": 1, \"enabled\": false}");
        assertFalse(disabled.get("enabled").asBoolean());
        assertEquals("DISABLED",
                byTable(json(200, "GET", M + "/resolve?catalog=" + PREFIX
                                + "&tables=" + DB + "." + TABLE2, null).get("policies"),
                        DB + "." + TABLE2).get("status").asString());

        // 重新启用后回到 FOUND（开关是治理决定，不走 SQL 删除那道门）
        json(200, "PUT", policyPath(DB, TABLE2), "{\"baseVersion\": 2, \"enabled\": true}");
        assertEquals("FOUND",
                byTable(json(200, "GET", M + "/resolve?catalog=" + PREFIX
                                + "&tables=" + DB + "." + TABLE2, null).get("policies"),
                        DB + "." + TABLE2).get("status").asString());
    }

    @Test
    @Order(5)
    void resolveValidatesRequestShape() throws Exception {
        json(400, "GET", M + "/resolve?catalog=" + PREFIX + "&tables=", null);
        // 超过单次解析上限：65 张表（上限 64）要被拒——一条 SQL 扫几张表是常态，几百张是异常用法
        json(400, "GET", M + "/resolve?catalog=" + PREFIX
                + "&tables=" + ("db.t0,".repeat(DataPolicyService.MAX_RESOLVE_TABLES + 1)), null);
    }

    /**
     * 主体白名单（§13.2 的 {@code FOR} 子句落库侧）：非空名单 = 只对名单内主体生效，
     * 名单外的调用者按「没策略」放行（NONE），与豁免（EXEMPT）、停用（DISABLED）三态分开。
     */
    @Test
    @Order(41)
    void subjectWhitelistGatesResolve() throws Exception {
        String principal = json(200, "GET", M + "/resolve?catalog=" + PREFIX
                + "&tables=" + DB + "." + TABLE2, null).get("principal").asString();

        // 白名单包含当前主体 → FOUND；名单随 PUT 整份替换
        JsonNode scoped = json(200, "PUT", policyPath(DB, TABLE2),
                "{\"baseVersion\": 3, \"subjects\": [\"" + principal + "\", \"role_member\"]}");
        assertEquals(2, scoped.get("subjects").size());
        assertEquals("FOUND",
                byTable(json(200, "GET", M + "/resolve?catalog=" + PREFIX
                                + "&tables=" + DB + "." + TABLE2, null).get("policies"),
                        DB + "." + TABLE2).get("status").asString());

        // 名单不含当前主体 → NONE（对我不生效 ≈ 表没策略，引擎放行）
        json(200, "PUT", policyPath(DB, TABLE2),
                "{\"baseVersion\": 4, \"subjects\": [\"someone_else\"]}");
        assertEquals("NONE",
                byTable(json(200, "GET", M + "/resolve?catalog=" + PREFIX
                                + "&tables=" + DB + "." + TABLE2, null).get("policies"),
                        DB + "." + TABLE2).get("status").asString());

        // 空名单 = 全部主体，恢复默认语义
        json(200, "PUT", policyPath(DB, TABLE2), "{\"baseVersion\": 5, \"subjects\": []}");
        JsonNode detail = json(200, "GET", policyPath(DB, TABLE2), null);
        assertEquals(0, detail.get("subjects").size());
        assertEquals("FOUND",
                byTable(json(200, "GET", M + "/resolve?catalog=" + PREFIX
                                + "&tables=" + DB + "." + TABLE2, null).get("policies"),
                        DB + "." + TABLE2).get("status").asString());
    }

    /**
     * §5.3.4.1：{@code $principal_roles} 在下发时折成调用者持有的 principal role 名单
     * （裸名、去重排序），只允许作为 {@code in} 的取值集合出现。名单为空时整条
     * {@code in} 折成 {@code false}——不属于任何角色就一行都看不到，方向不会错。
     * mask 里 / 普通操作数位置出现占位符都在校验期指名拒绝。
     */
    @Test
    @Order(42)
    void principalRolesFoldIntoInValues() throws Exception {
        String principal = json(200, "GET", M + "/resolve?catalog=" + PREFIX
                + "&tables=" + DB + "." + TABLE2, null).get("principal").asString();
        String role = "policy_role_reader";
        // M 以 /policies 结尾；主体与角色的端点挂在管理根上
        String mgmt = M.replaceAll("/policies$", "");

        // 测试调用者是「匿名令牌」（未登记主体）：先建出同名 principal 才能挂角色
        json(201, "POST", mgmt + "/principals", "{\"principal\":{\"name\":\"" + principal + "\"}}");
        json(201, "POST", mgmt + "/principal-roles", "{\"principalRole\":{\"name\":\"" + role + "\"}}");
        json(201, "PUT", mgmt + "/principals/" + principal + "/principal-roles",
                "{\"principalRole\":{\"name\":\"" + role + "\"}}");
        try {
            // 有角色：in 的 values 折成角色名列表
            json(200, "PUT", policyPath(DB, TABLE2),
                    "{\"baseVersion\": 6, \"rowFilter\": {\"op\": \"in\", \"operand\": {\"col\": \"dt\"},"
                            + " \"values\": {\"principal_roles\": true}}}");
            JsonNode folded = byTable(json(200, "GET", M + "/resolve?catalog=" + PREFIX
                            + "&tables=" + DB + "." + TABLE2, null).get("policies"),
                    DB + "." + TABLE2).get("rowFilter");
            // upsert 时外层包了 coalesce（非 NULL 兜底），in 在 args[0]
            assertEquals("coalesce", folded.get("fn").asString());
            JsonNode inNode = folded.get("args").get(0);
            assertEquals("in", inNode.get("op").asString());
            assertEquals(1, inNode.get("values").size());
            assertEquals(role, inNode.get("values").get(0).get("lit").asString(),
                    "$principal_roles 应折成调用者持有的角色名（裸名，不带 role: 前缀）");

            // 占位符只能出现在 in 的 values 位：普通操作数位置校验期指名拒绝
            json(400, "PUT", policyPath(DB, TABLE2),
                    "{\"baseVersion\": 7, \"rowFilter\": {\"op\": \"eq\", \"left\": {\"col\": \"dt\"},"
                            + " \"right\": {\"principal_roles\": true}}}");
            // mask 里出现占位符 → 400（行过滤专属）
            json(400, "PUT", policyPath(DB, TABLE2),
                    "{\"baseVersion\": 7, \"rowFilter\": {\"op\": \"eq\", \"left\": {\"col\": \"dt\"},"
                            + " \"right\": {\"lit\": \"x\"}}, \"masks\": [{\"column\": \"name\","
                            + " \"mask\": {\"principal_roles\": true}}]}");
            // 字面量列表里夹带占位符 → 400
            json(400, "PUT", policyPath(DB, TABLE2),
                    "{\"baseVersion\": 7, \"rowFilter\": {\"op\": \"in\", \"operand\": {\"col\": \"dt\"},"
                            + " \"values\": [{\"lit\": \"a\"}, {\"principal_roles\": true}]}}");

            // 撤掉装配后：名单为空，整条 in 折成 false（fail-closed，不是报错也不是放行）
            json(204, "DELETE", mgmt + "/principals/" + principal + "/principal-roles/" + role, null);
            JsonNode empty = byTable(json(200, "GET", M + "/resolve?catalog=" + PREFIX
                            + "&tables=" + DB + "." + TABLE2, null).get("policies"),
                    DB + "." + TABLE2).get("rowFilter");
            JsonNode emptyIn = empty.get("args").get(0);
            assertFalse(emptyIn.has("op"), "角色名单为空时不应保留 in 节点");
            assertFalse(emptyIn.get("lit").asBoolean(), "名单为空应折成字面量 false");
        } finally {
            json(204, "DELETE", mgmt + "/principal-roles/" + role, null);
            json(204, "DELETE", mgmt + "/principals/" + principal, null);
        }
    }

    // ------------------------------------------------------------------ 4. 读路径收口（§6 / §7.2）

    @Test
    @Order(6)
    void previewAndDownloadAreBlockedForProtectedTable() throws Exception {
        TableEntity protectedTable = table(DB, TABLE);

        // P1 起预览不再被策略整体拒绝（设计 §6）：控制器按调用者解析策略后交给
        // 预览服务逐行求值。这张测试表没有真实仓库，请求会走到"打开表"那一步才
        // 失败（400）——关键是**不是** 403 策略收口：门开了，语义由
        // PolicyPreviewTests 的真实读路径（行过滤 + 逐列 mask）看住
        MvcResult preview = mockMvc.perform(
                post("/api/console/v1/tables/" + protectedTable.getId() + "/preview")
                        .header("Authorization", BEARER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        String body = preview.getResponse().getContentAsString();
        assertTrue(preview.getResponse().getStatus() != 403,
                () -> "受保护表的预览不再被策略整体拒绝: " + body);
        assertTrue(!body.contains("row filter / column mask policy is active"),
                () -> "拒绝原因不应再是策略收口: " + body);

        // 分区列举维持拒绝（分区值是 transform 后的值，mask 语义套不上去，§6）
        MvcResult partitions = mockMvc.perform(
                get("/api/console/v1/tables/" + protectedTable.getId() + "/partitions")
                        .header("Authorization", BEARER)).andReturn();
        assertEquals(403, partitions.getResponse().getStatus());

        // 文件下载：一个 parquet 原文件就是全表明文，优先级最高的绕过面（§7.2）
        ApiException download = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> fileService.openTableFile(protectedTable.getId(), "data.parquet"));
        assertEquals(403, download.getStatus());

        // catalog 级下载同一道门：仓库根路径拼上表目录就能绕开表级端点——
        // 两扇门只锁一扇等于没锁（§7.2；判据同一份「启用中的策略」，路径前缀比对）
        ApiException catalogDownload = org.junit.jupiter.api.Assertions.assertThrows(ApiException.class,
                () -> fileService.openFile(PREFIX, DB + ".db/" + TABLE + "/data-0.parquet"));
        assertEquals(403, catalogDownload.getStatus());

        // 快照统计打码（§7.3）：statistics 是服务端唯一可能携带列级 min/max/nullCount
        // 的载体（Paimon 优化器统计的序列化串）。有 mask 的策略在 → 置空下发，
        // 行数等聚合值不受影响；没策略 → 原样回
        TableDtos.Snapshot snapshot = new TableDtos.Snapshot(1, "uuid-stats", 901L, null,
                "base.manifest", "delta.manifest", null, "index.manifest",
                "tester", "901", "APPEND", System.currentTimeMillis(),
                Map.of(), 7L, 7L, 0L, 0L, "col-stats-blob");
        assertTrue(tableService.commit(PREFIX, DB, TABLE,
                new TableDtos.CommitTableRequest(protectedTable.getId(), null, snapshot, null)).success());
        TableDtos.TableSnapshot masked = tableService.getTableSnapshot(PREFIX, DB, TABLE).snapshot();
        assertEquals(7L, masked.recordCount(), "行数聚合不在打码范围——只藏列级统计");
        assertNull(masked.snapshot().statistics(),
                "带 mask 的策略表：快照统计必须置空，不能下发列级 min/max 的序列化串（§7.3）");

        // 删掉策略后三处口子同步放开（403 判据是「启用中的策略」，不是表的属性）
        json(200, "DELETE", policyPath(DB, TABLE), null);
        int downloadStatus;
        try {
            fileService.openTableFile(protectedTable.getId(), "data.parquet");
            downloadStatus = 200;
        } catch (ApiException error) {
            downloadStatus = error.getStatus();
        }
        assertTrue(downloadStatus != 403,
                "删除策略后下载不再被策略收口拒绝（404 是文件不存在，另一回事）");
        int catalogDownloadStatus;
        try {
            fileService.openFile(PREFIX, DB + ".db/" + TABLE + "/data-0.parquet");
            catalogDownloadStatus = 200;
        } catch (ApiException error) {
            catalogDownloadStatus = error.getStatus();
        }
        assertTrue(catalogDownloadStatus != 403,
                "删除策略后 catalog 级下载同样放开（404 是文件不存在，另一回事）");
        TableDtos.TableSnapshot released = tableService.getTableSnapshot(PREFIX, DB, TABLE).snapshot();
        assertEquals("col-stats-blob", released.snapshot().statistics(),
                "删除策略后快照统计原样下发：打码跟策略走，不是表的属性");
        MvcResult previewAfter = mockMvc.perform(
                post("/api/console/v1/tables/" + protectedTable.getId() + "/preview")
                        .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertTrue(previewAfter.getResponse().getStatus() != 403
                        || !previewAfter.getResponse().getContentAsString().contains("row filter / column mask"),
                "删除策略后不再被策略收口拒绝");
        json(204, "GET", policyPath(DB, TABLE), null);
    }

    // ------------------------------------------------------------------ 5. 表生命周期联动（§5.2 取舍 4）

    @Test
    @Order(7)
    void tableLifecycleMovesAndDropsPolicies() throws Exception {
        createPaimonTable(DB, TABLE);
        // 前一个用例可能已把 t1 的策略删掉（也可能没走到），这里容错清场
        mockMvc.perform(delete(policyPath(DB, TABLE)));
        json(200, "PUT", policyPath(DB, TABLE),
                "{\"rowFilter\": {\"op\": \"is_null\", \"operand\": {\"col\": \"dt\"}}}");

        // RENAME：走协议面的真改名（发 TABLE_RENAMED 事件），策略跟随——改名不改语义。
        // 目标名必须没有别的策略：策略按三段名唯一，撞上 t2 的策略会撞唯一键
        tableService.rename(PREFIX, new TableDtos.RenameTableRequest(
                new CommonDtos.Identifier(DB, TABLE), new CommonDtos.Identifier(DB, "t_renamed")));
        JsonNode moved = json(200, "GET", policyPath(DB, "t_renamed"), null);
        assertEquals(2, moved.get("version").asInt(), "改名也递增版本：缓存的旧 etag 必须失效");

        // DROP：走协议面的真删表（发 TABLE_DROPPED 事件），级联删策略——
        // 同名重建因此不继承旧策略。表已经不存在，详情端点会先回 404，
        // 所以直接查仓储确认策略行没了
        tableService.drop(PREFIX, DB, "t_renamed");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        String hash = HexFormat.of().formatHex(
                digest.digest("paimon.policy_db.t_renamed".getBytes(StandardCharsets.UTF_8)));
        assertTrue(policyRepository.findByFullNameHash(hash).isEmpty(),
                "删表要级联删掉策略行（同名重建不继承）");
    }

    // ------------------------------------------------------------------ 辅助

    private static JsonNode byTable(JsonNode policies, String table) {
        for (JsonNode node : policies) {
            if (table.equals(node.get("table").asString())) {
                return node;
            }
        }
        throw new AssertionError("resolved policy not found for " + table);
    }

    private TableEntity table(String database, String name) {
        return tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalogId(), databaseId(database), name)
                .orElseThrow();
    }

    private static String policyPath(String database, String table) {
        return M + "/tables/" + PREFIX + "/" + database + "/" + table;
    }

    private void createPaimonTable(String database, String table) {
        if (!databaseService.list(PREFIX, null, null, null, null).databases().contains(database)) {
            databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(database, Map.of()));
        }
        if (tableRepository.findByCatalogIdAndDatabaseIdAndName(catalogId(), databaseId(database), table)
                .isPresent()) {
            return;
        }
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "id", "BIGINT NOT NULL"));
        schema.getFields().add(field(1, "name", "VARCHAR(100)"));
        schema.getFields().add(field(2, "dt", "VARCHAR(10)"));
        schema.getPrimaryKeys().add("id");
        schema.getPartitionKeys().add("dt");
        schema.getOptions().put("bucket", "1");
        tableService.create(PREFIX, database,
                new TableDtos.CreateTableRequest(new CommonDtos.Identifier(database, table), schema));
    }

    private String catalogId() {
        return databaseService.require(PREFIX, DB).getCatalogId();
    }

    private String databaseId(String database) {
        return databaseService.require(PREFIX, database).getId();
    }

    private static TypeDtos.DataField field(int id, String name, String type) {
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(id);
        field.setName(name);
        field.setType(type);
        return field;
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilderAlias request = switch (method) {
            case "GET" -> new MockHttpServletRequestBuilderAlias(get(path));
            case "PUT" -> new MockHttpServletRequestBuilderAlias(put(path));
            case "POST" -> new MockHttpServletRequestBuilderAlias(post(path));
            case "DELETE" -> new MockHttpServletRequestBuilderAlias(delete(path));
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request.raw().contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mockMvc.perform(request.raw()).andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> path + " unexpected status, body=" + responseBody);
        if (expected == 204 || expected == 404 || responseBody == null || responseBody.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return mapper.readTree(responseBody);
    }

    /** 只是给 switch 一个统一类型用的薄壳（MockHttpServletRequestBuilder 无公共父类可用时的习惯写法）。 */
    private record MockHttpServletRequestBuilderAlias(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder raw) {
    }
}
