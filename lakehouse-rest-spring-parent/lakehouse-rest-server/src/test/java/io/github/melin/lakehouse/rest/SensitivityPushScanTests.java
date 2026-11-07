package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.domain.entity.CatalogExecutionClusterEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogExecutionClusterRepository;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import io.github.melin.lakehouse.rest.service.SensitivityScanDispatcher;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;
import io.github.melin.lakehouse.rest.service.submit.ScanJobSubmitter;
import io.github.melin.lakehouse.rest.service.submit.ScanJobSubmitter.Submitted;
import io.github.melin.lakehouse.rest.service.submit.ScanJobSubmitter.Submission;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * {@code execution=SPARK} 下推扫描的状态机与两个回调端点（设计见
 * {@code docs/sensitive-data-design.md} §5.6.5 / §5.6.6）。
 *
 * <p><b>提交器是桩，其余全是真的。</b>真的库、真的条件更新、真的端点与令牌校验；只把
 * {@link ScanJobSubmitter} 换成一个返回固定坐标的 mock，因为真提交要一台 k8s 集群，
 * 而本类要验的不是「kubectl 能不能用」，是下面这六件「错了不报错、只是把话说错」的事：
 *
 * <ol>
 *   <li><b>建单即 {@code PENDING}，且没有 {@code startedAt}</b>。B 路线的执行者是另一个进程，
 *       从落库那一刻起它还没开始跑——写 {@code RUNNING} 等于谎报，还会让「扫描耗时」
 *       把排队时间算进去；</li>
 *   <li><b>提交之后才 {@code RUNNING}，并且拿到了 Job 坐标与令牌哈希</b>。三者缺一，
 *       回调就永远 403 或者永远对不上作业；</li>
 *   <li><b>{@code /submission} 没有令牌 → 403</b>。这两个端点在 RBAC 之外，令牌是唯一凭据；
 *       而它的作用域是「这一次扫描」，因此错令牌也必须 403（不是「随便给一个就过」）；</li>
 *   <li><b>「扫了但没命中」与「扫了有命中」都算 {@code tables_scanned}</b>，且重复回调
 *       不重复计数 finding。前者是覆盖率的分母，后者是作业重投时的幂等；</li>
 *   <li><b>扫描与优化共用一把 {@code maxConcurrentJobs}</b>：满额时第二条 {@code PENDING}
 *       扫描留在队列里，取消掉第一条之后它才会被提交；</li>
 *   <li><b>终态不能再取消（409）</b>。取消写的是条件更新，写不到就说明它已经结束了。</li>
 * </ol>
 *
 * <p><b>调度节拍关掉</b>（{@code scheduler-enabled=false}）：本类直接调
 * {@link SensitivityScanDispatcher#submitPending()}，而不是等定时器。开着的话同一条断言
 * 会在两次测试之间被后台线程改写，失败信息会指向错误的地方。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.auth.enabled=false",
        // 内置类型要 seed：回调里的 CN_ID_CARD 与自动落标都按类型表展开
        "lakehouse.rest.sensitivity.seed-builtin-types=true",
        // 本类手工推进状态机，不要后台节拍来抢
        "lakehouse.rest.optimization.scheduler-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
// PER_CLASS 是 @AfterAll 能读注入字段的前提（默认的 PER_METHOD 下 @AfterAll 必须是静态方法）
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SensitivityPushScanTests {

    private static final String M = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String DB = "push_db";

    /** 桩提交器交回来的回调令牌：端点拿它算哈希，测试拿它当请求头。 */
    private static final String CALLBACK_TOKEN = "callback-token-for-tests";

    private static final String TOKEN_HEADER = "X-Sensitivity-Scan-Token";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableRepository tableRepository;
    @Autowired
    private CatalogExecutionClusterRepository clusterRepository;
    @Autowired
    private CatalogRepository catalogRepository;
    @Autowired
    private SensitivityScanDispatcher dispatcher;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 本类是否自己建了那份执行集群配置。
     *
     * <p>只删自己建的：别的类也可能给同一个 catalog 配过一份（各自收尾），
     * 把别人的夹具删掉会让失败出现在别的类里。
     */
    private boolean createdCluster;

    /**
     * 提交器桩。
     *
     * <p>{@code state} 默认回 {@code RUNNING}：本类验的是提交与回调，不想让
     * {@code pollRunning} 顺手把扫描收尾掉——那会把「正在跑」这个前提冲掉，后续断言
     * 就变成在验一个已经结束的扫描。
     */
    @TestConfiguration
    static class StubSubmitter {

        @Bean
        @Primary
        ScanJobSubmitter stubScanJobSubmitter() {
            ScanJobSubmitter stub = mock(ScanJobSubmitter.class);
            when(stub.name()).thenReturn("stub");
            when(stub.submit(any())).thenAnswer(invocation -> {
                Submission submission = invocation.getArgument(0);
                return new Submitted("scan-ns", "paimon-scan-" + submission.scanId(), CALLBACK_TOKEN);
            });
            when(stub.state(any(), any(), any())).thenReturn(JobState.RUNNING);
            return stub;
        }
    }

    // ------------------------------------------------------------------ 1. 建单与提交

    @Test
    @Order(1)
    void pushedScanIsPendingThenSubmitted() throws Exception {
        createTable("push_a", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        givenDispatchableCluster(1);

        JsonNode accepted = json(202, "POST", M + "/sensitivity-scans", """
                {"catalog": "paimon", "scope": "TABLE", "database": "push_db", "table": "push_a",
                 "mode": "SAMPLED", "execution": "SPARK", "sampleSize": 500}
                """);
        String scanId = accepted.get("scanId").asString();
        assertEquals("PENDING", accepted.get("status").asString(),
                "建单即返回：提交在别的节拍/别的进程里发生，这里写 RUNNING 是谎报");
        assertEquals("SPARK", accepted.get("execution").asString());

        JsonNode pending = json(200, "GET", M + "/sensitivity-scans/" + scanId, null);
        assertTrue(pending.get("startedAt") == null || pending.get("startedAt").isNull(),
                "B 路线的 startedAt 由 markSubmitted 写：排队时间不该算进扫描耗时");

        dispatcher.submitPending();

        JsonNode running = json(200, "GET", M + "/sensitivity-scans/" + scanId, null);
        assertEquals("RUNNING", running.get("status").asString());
        assertEquals("paimon-scan-" + scanId, running.get("executionResource").asString(),
                "Job 坐标要落库：收尾与取消都靠它去问执行侧");
        assertNotNull(running.get("startedAt"), "提交成功必须写 startedAt，否则超时永远不成立");
    }

    // ------------------------------------------------------------------ 2. 令牌

    /**
     * 回调端点只认这一次扫描的令牌。
     *
     * <p>三个断言分别对应三种「看起来差不多」的失败：没给、给错、给对。
     * 把它们合成一条（「反正没令牌就 403」）会漏掉最危险的那种——**错令牌也通过**，
     * 那意味着任何一次扫描的令牌都能读别的扫描的清单。
     */
    @Test
    @Order(2)
    void submissionEndpointOnlyAcceptsThisScanToken() throws Exception {
        String scanId = onlyPushedScan();

        assertEquals(403, status("GET", M + "/sensitivity-scans/" + scanId + "/submission", null, null),
                "没有令牌就是 403：这两个端点在 RBAC 之外，令牌是唯一凭据");
        assertEquals(403, status("GET", M + "/sensitivity-scans/" + scanId + "/submission", null,
                "some-other-token"), "错令牌也必须 403：令牌的作用域是这一次扫描");

        JsonNode spec = jsonWithToken(200, "GET", M + "/sensitivity-scans/" + scanId + "/submission",
                null, CALLBACK_TOKEN);
        assertEquals("push_a", spec.get("tables").get(0).get("table").asString());
        assertEquals(DB, spec.get("tables").get(0).get("database").asString());
        assertEquals("PAIMON", spec.get("format").asString(),
                "catalog 格式必须随清单下发：作业没有别的途径知道该用哪种 catalog 实现建会话");
        assertEquals("SAMPLED", spec.get("mode").asString());
        assertEquals(500, spec.get("sampleSize").asInt());
        assertFalse(spec.get("hasMore").asBoolean(), "只有一张表的一页不该说还有下一页");
        assertEquals(1, spec.get("totalTables").asInt());

        // 列是服务端解析好下发的：作业侧不认识 schema_doc
        List<String> columns = new java.util.ArrayList<>();
        for (JsonNode column : spec.get("tables").get(0).get("columns")) {
            columns.add(column.get("name").asString());
        }
        assertTrue(columns.contains("id_card"), "清单里要有列：" + columns);
        assertTrue(spec.get("types").size() > 0, "候选类型随页下发，作业不该为它多开一个端点");
    }

    // ------------------------------------------------------------------ 3. 逐表回调

    /**
     * 回调累加计数、去重 finding、并给自动应用的那条落标签。
     *
     * <p>第二次上报同一个 finding 时 {@code findingsCreated} 不再增加——那是
     * {@code existingKeys} 的作用；而 {@code tablesScanned} **会**再加 1，因为「又被上报了
     * 一次」是事实（服务端没有第三列区分「表数」与「上报次数」）。
     */
    @Test
    @Order(3)
    void itemReportsAccumulateCountsAndAreIdempotentPerFinding() throws Exception {
        String scanId = onlyPushedScan();
        String body = """
                {"database": "push_db", "table": "push_a", "status": "SCANNED", "columnsScanned": 2,
                 "findings": [{"column": "id_card", "typeCode": "CN_ID_CARD", "confidence": 0.92,
                               "state": "AUTO_APPLIED", "scores": {"name": 0.35, "value": 0.40},
                               "maskedSamples": ["1101**********1234"], "samplingMode": "HEAD"}]}
                """;

        jsonWithToken(204, "POST", M + "/sensitivity-scans/" + scanId + "/items", body, CALLBACK_TOKEN);
        JsonNode first = json(200, "GET", M + "/sensitivity-scans/" + scanId, null);
        assertEquals(1, first.get("tablesScanned").asInt());
        assertEquals(1, first.get("findingsCreated").asInt());
        assertEquals(0, first.get("tablesFailed").asInt());

        JsonNode findings = json(200, "GET", M + "/sensitivity-findings?catalog=" + PREFIX
                + "&table=" + DB + ".push_a", null).get("items");
        assertEquals(1, findings.size(), "一条回调只产一条 finding：" + findings);
        assertEquals("AUTO_APPLIED", findings.get(0).get("state").asString());
        assertEquals("id_card", findings.get(0).get("column").asString());

        jsonWithToken(204, "POST", M + "/sensitivity-scans/" + scanId + "/items", body, CALLBACK_TOKEN);
        JsonNode second = json(200, "GET", M + "/sensitivity-scans/" + scanId, null);
        assertEquals(1, second.get("findingsCreated").asInt(),
                "重复回调不该再落一条同列同型的 finding（作业重投、多副本竞争都走这条路）");
        assertEquals(2, second.get("tablesScanned").asInt(),
                "「表数」与「上报次数」在库里是同一个字段：如实地再记一次");

        // 自动应用会落**两行**：列级一条（id_card）+ 表级一条（tagKey=sensitivity.level，
        // column 是空串）。表级标签是设计里的既定形状（§4.3），因此这里按 tableLevel 分开数，
        // 而不是断言「一共只有一条」——那会把一条正确的行为当成 bug
        JsonNode tags = json(200, "GET", M + "/column-tags?catalog=" + PREFIX + "&table=" + DB + ".push_a",
                null).get("items");
        JsonNode columnTag = null;
        JsonNode tableTag = null;
        for (JsonNode tag : tags) {
            if (tag.get("tableLevel").asBoolean()) {
                tableTag = tag;
            } else {
                columnTag = tag;
            }
        }
        assertNotNull(columnTag, "AUTO_APPLIED 的列要落一条列级标签：" + tags);
        assertEquals("id_card", columnTag.get("column").asString());
        assertEquals("AUTO", columnTag.get("source").asString());
        assertEquals("system:sensitivity-scanner", columnTag.get("updatedBy").asString(),
                "回调没有主体身份，但审计不能因此断在 anonymous 上");
        assertNotNull(tableTag, "同一轮还要给这张表落一条表级标签（column 为空串）：" + tags);
        assertEquals("", tableTag.get("column").asString(), "表级标签的列名是空串，不是缺字段");
    }

    /**
     * 非 SPARK 的扫描：拿不到清单，而且**先被令牌挡住**。
     *
     * <p>端点的判定顺序是「令牌 → 执行位置」，所以这里拿到的是 403 而不是 400：
     * A 路线根本没有回调令牌（{@code callback_token_hash} 为空），任何携带令牌的调用
     * 都不可能是它的作业发出来的。这也是有意的——先答「你有没有资格」，再答
     * 「这个扫描有没有清单」，否则未授权的人能靠状态码差异去探「哪些扫描是下推的」。
     *
     * <p>{@code reportItem} / {@code submission} 里那两个「不是 SPARK → 400」的分支因此
     * 是**防线的第二道**：从 HTTP 走到那里必须已经持有令牌，而能持有一枚令牌的扫描
     * 只可能是 SPARK。留着它们的价值是「将来若放宽令牌作用域，语义不会静默变成 500」。
     */
    @Test
    @Order(4)
    void inProcessScanHasNoCallbackToken() throws Exception {
        createTable("push_local", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        JsonNode accepted = json(202, "POST", M + "/sensitivity-scans", """
                {"catalog": "paimon", "scope": "TABLE", "database": "push_db", "table": "push_local",
                 "mode": "METADATA_ONLY"}
                """);
        assertEquals("IN_PROCESS", accepted.get("execution").asString(),
                "默认执行位置是进程内（本用例的前提）");
        String scanId = accepted.get("scanId").asString();

        assertEquals(403, status("GET", M + "/sensitivity-scans/" + scanId + "/submission",
                        null, CALLBACK_TOKEN),
                "A 路线没有回调令牌：先被令牌挡住，而不是回一份空清单或一份 400 解释");
        assertEquals(403, status("POST", M + "/sensitivity-scans/" + scanId + "/items",
                        """
                        {"database": "push_db", "table": "push_local", "status": "SCANNED",
                         "columnsScanned": 1, "findings": []}
                        """,
                        CALLBACK_TOKEN));
    }

    // ------------------------------------------------------------------ 5. 共用闸门

    /**
     * 满额时第二条扫描留在队列里。
     *
     * <p>这是「扫描与优化共用一把 {@code maxConcurrentJobs}」在代码里的唯一体现：
     * 各开一把闸门时两侧各自看起来都在额度内，实际把同一台集群放到了两份额度的驱动数。
     */
    @Test
    @Order(5)
    void secondScanWaitsForTheSharedSlot() throws Exception {
        createTable("push_b", "id:BIGINT NOT NULL", "id_card:VARCHAR(18)");
        JsonNode accepted = json(202, "POST", M + "/sensitivity-scans", """
                {"catalog": "paimon", "scope": "TABLE", "database": "push_db", "table": "push_b",
                 "mode": "SAMPLED", "execution": "SPARK"}
                """);
        String second = accepted.get("scanId").asString();

        dispatcher.submitPending();
        assertEquals("PENDING", json(200, "GET", M + "/sensitivity-scans/" + second, null)
                        .get("status").asString(),
                "上限是 1，已有一条在跑：第二条必须排队，而不是一起放出去");
    }

    // ------------------------------------------------------------------ 6. 取消

    /**
     * 取消的条件更新与「取消之后额度释放」。
     *
     * <p>第二次取消回 409 是条件更新起作用的样子（{@code where status in (...)} 命中 0 行）。
     * 而取消之后 {@code submitPending()} 必须能把排队的那条放出去——否则「额度」这个概念
     * 会随着一次取消永久少一格。
     */
    @Test
    @Order(6)
    void cancellingReleasesTheSlotAndConflictsOnceFinished() throws Exception {
        String running = onlyPushedScan();
        JsonNode cancelled = json(200, "POST", M + "/sensitivity-scans/" + running + ":cancel", null);
        assertEquals("CANCELLED", cancelled.get("status").asString());

        assertEquals(409, status("POST", M + "/sensitivity-scans/" + running + ":cancel", null, null),
                "终态不能再取消：条件更新会命中 0 行，翻成 409");

        dispatcher.submitPending();
        JsonNode queued = json(200, "GET", M + "/sensitivity-scans/" + queuedScanOf("push_b"), null);
        assertEquals("RUNNING", queued.get("status").asString(),
                "取消之后额度要能释放：否则每取消一次就永久少一格并发");
    }

    // ------------------------------------------------------------------ 造数据

    /**
     * 一条可派发的执行集群记录。
     *
     * <p>{@code probeConfigHash} 留空 → 派生状态 {@code UNVERIFIED}，它**是可派发的**
     * （「保存完没点验证」不该挡住提交，只该在列表上显示「待验证」）。真去探活要一台
     * 集群，而本类验的是提交链路。
     */
    private void givenDispatchableCluster(int maxConcurrentJobs) {
        String catalogId = catalogId();
        if (clusterRepository.findByCatalogId(catalogId).isPresent()) {
            return;
        }
        CatalogExecutionClusterEntity entity = new CatalogExecutionClusterEntity();
        entity.setId("ec-" + catalogId);
        entity.setCatalogId(catalogId);
        entity.setEnabled(true);
        entity.setClusterType("SELF_HOSTED");
        // 四个非空列：apiServer 是 kubeconfig 的投影（列表要显示它，不为它去解密），
        // 另外三个是提交 Job 必需的。本类不真连集群，值只要合法即可。
        entity.setApiServer("https://127.0.0.1:6443");
        entity.setContextName("test");
        entity.setConfigHash("test-config-hash");
        entity.setNamespace("scan-ns");
        entity.setImage("lakehouse-rest-optimizer:test");
        entity.setCallbackBaseUrl("http://localhost:8080");
        entity.setMaxConcurrentJobs(maxConcurrentJobs);
        entity.setMaxScanTablesPerJob(200);
        entity.setScanJobTimeoutSeconds(3600);
        clusterRepository.save(entity);
        createdCluster = true;
    }

    /**
     * 收尾：把本类写下的标签 / 结果 / 扫描与那份集群配置删干净。
     *
     * <p><b>不删会怎样（本轮实测到的）</b>：H2 内存库是
     * {@code jdbc:h2:mem:paimon;DB_CLOSE_DELAY=-1} + {@code ddl-auto=update}，
     * 同一个 JVM 里的所有 {@code @SpringBootTest} 共用它，且上下文关闭不删数据。
     * 本类第 3 个用例会给 {@code id_card} 自动落两条标签（列级 + 表级），而
     * {@code SensitivityApiTests} 的基数断言是<b>全表</b> {@code tagRepository.count() == 0}——
     * 两个类谁先跑由 surefire 的文件顺序决定，于是同一份代码时红时绿，
     * 失败信息还指向一个和它毫无关系的用例。
     *
     * <p>用 {@code @AfterAll} 而不是逐测试收尾：本类是**一条**有序故事（第 5 个用例
     * 要第 1 个用例留下的在跑扫描，第 6 个要第 5 个留下的排队扫描），中途清掉就没法跑了。
     * 这也正是本类声明 PER_CLASS 的原因——PER_METHOD 下 {@code @AfterAll} 必须是静态方法，
     * 而静态方法拿不到注入的仓储。
     */
    @AfterAll
    void removeFixtures() {
        // 先删派生数据，再删扫描：三张表都按 (catalog, database) 定位，本类的库里只有
        // push_a / push_b / push_local 三张表
        jdbc.update("delete from lakehouse_column_tag where catalog_name = ? and database_name = ?", PREFIX, DB);
        jdbc.update("delete from lakehouse_sensitivity_finding where catalog_name = ? and database_name = ?",
                PREFIX, DB);
        jdbc.update("delete from lakehouse_sensitivity_scan where catalog_name = ? and database_name = ?",
                PREFIX, DB);
        if (createdCluster) {
            catalogRepository.findByPrefix(PREFIX).ifPresent(catalog -> clusterRepository
                    .findByCatalogId(catalog.getId()).ifPresent(clusterRepository::delete));
        }
    }

    private String catalogId() {
        return databaseService.require(PREFIX, DB).getCatalogId();
    }

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

    /** 当前唯一一条处于 RUNNING 的下推扫描（用例之间靠它承接，不重复造数据）。 */
    private String onlyPushedScan() throws Exception {
        JsonNode items = json(200, "GET", M + "/sensitivity-scans?catalog=" + PREFIX + "&status=RUNNING",
                null).get("items");
        String found = null;
        for (JsonNode item : items) {
            if ("SPARK".equals(item.get("execution").asString())) {
                assertNull(found, "本类的顺序保证同一时刻只有一条在跑；多了说明闸门没生效");
                found = item.get("id").asString();
            }
        }
        assertNotNull(found, "expected one running pushed scan");
        return found;
    }

    /** 某张表最近一条下推扫描的 id（按创建时间倒序取第一条）。 */
    private String queuedScanOf(String table) throws Exception {
        JsonNode items = json(200, "GET", M + "/sensitivity-scans?catalog=" + PREFIX, null).get("items");
        for (JsonNode item : items) {
            if (table.equals(item.get("table").asString())) {
                return item.get("id").asString();
            }
        }
        throw new AssertionError("no scan found for " + table);
    }

    // ------------------------------------------------------------------ HTTP 工具

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        return read(expected, method, path, body, null);
    }

    private JsonNode jsonWithToken(int expected, String method, String path, String body, String token)
            throws Exception {
        return read(expected, method, path, body, token);
    }

    private int status(String method, String path, String body, String token) throws Exception {
        MvcResult result = mockMvc.perform(request(method, path, body, token)).andReturn();
        return result.getResponse().getStatus();
    }

    private JsonNode read(int expected, String method, String path, String body, String token)
            throws Exception {
        MvcResult result = mockMvc.perform(request(method, path, body, token)).andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> method + " " + path + " unexpected status, body=" + responseBody);
        if (expected == 204 || responseBody == null || responseBody.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return mapper.readTree(responseBody);
    }

    private static MockHttpServletRequestBuilder request(String method, String path, String body,
                                                         String token) {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (token != null) {
            request = request.header(TOKEN_HEADER, token);
        }
        return request;
    }
}
