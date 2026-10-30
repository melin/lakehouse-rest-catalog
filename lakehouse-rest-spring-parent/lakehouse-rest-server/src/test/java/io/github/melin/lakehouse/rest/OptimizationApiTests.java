package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import io.github.melin.lakehouse.rest.domain.entity.OptimizationJobEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableOptimizationEntity;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.CatalogExecutionClusterEntity;
import io.github.melin.lakehouse.rest.domain.repo.AuditEventRepository;
import io.github.melin.lakehouse.rest.domain.repo.CatalogExecutionClusterRepository;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobItemRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.domain.repo.SchedulerLeaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableOptimizationRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.event.CatalogEventType;
import io.github.melin.lakehouse.rest.service.SchedulerLease;
import io.github.melin.lakehouse.rest.dto.CommonDtos;
import io.github.melin.lakehouse.rest.paimon.dto.DatabaseDtos;
import io.github.melin.lakehouse.rest.paimon.dto.TableDtos;
import io.github.melin.lakehouse.rest.dto.TypeDtos;
import io.github.melin.lakehouse.rest.paimon.service.DatabaseService;
import io.github.melin.lakehouse.rest.service.OptimizationBatchService;
import io.github.melin.lakehouse.rest.service.OptimizationDispatcher;
import io.github.melin.lakehouse.rest.service.OptimizationJobService;
import io.github.melin.lakehouse.rest.paimon.service.TableService;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.SubmissionRequest;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.Submitted;
import io.github.melin.lakehouse.rest.support.OptimizationStatus.Job;
import io.github.melin.lakehouse.rest.support.Paging;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 表存储优化的集成测试：策略读写、catalog 默认值、组批、提交与跟踪、回调、重试、取消与超时。
 *
 * <p>用 HTTP 层（MockMvc）验证端点与状态码，用服务层驱动调度节拍——调度是
 * 定时触发的，测试必须能自己决定「什么时候扫一次」，因此把
 * {@code scheduler-enabled} 关掉，改为显式调用
 * {@link OptimizationBatchService#scanAndCreateBatches()} 与
 * {@link OptimizationDispatcher} 的提交与跟踪方法。
 *
 * <p>提交器换成桩实现（{@link StubSubmitter}）：它把「执行环境」变成一个可编程的开关——
 * 提交成功/失败、执行侧状态、被取消的资源都在测试里直接设定。没有它，
 * 这些状态机分支就只能靠起一个 k8s 才能覆盖，而状态机恰恰是最容易写错、
 * 又最不容易在真机上复现的部分。
 *
 * <p>批次数上限与并发上限**不再来自 yml**：它们是每个 catalog 执行集群配置的一部分
 * （逐集群的配额），因此由 {@link #ensureExecutionCluster()} 在每个测试前写一行
 * 「每批 2 张表、并发 2 批」的配置。这样用 4~5 张表就能覆盖「合并执行」「批满顺延」
 * 「拿不到配额不动游标」三种行为，不必真的建几十张表。
 *
 * <p>那一行同时是**可派发的**（{@code probeConfigHash} 为空 → {@code UNVERIFIED}）：
 * 没有它，策略保存会被 fail-closed 挡成 400、出批会被跳过——这正是新规则要的效果，
 * 但会让这一整套用例测不到它们本来要测的东西。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "lakehouse.rest.optimization.scheduler-enabled=false"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OptimizationApiTests {

    private static final String M = "/api/management/v1/optimization";
    /** 管理 API 的基址（Lance 用例要自己建 catalog）。 */
    private static final String MANAGEMENT = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String DB = "opt_basic";
    private static final String TABLE = "t1";
    private static final String TABLE2 = "t2";
    private static final String BATCH_DB = "opt_batch";

    /** Lance 用例自己的 catalog / 命名空间 / 表：与 {@code paimon} 互不影响。 */
    private static final String LANCE_PREFIX = "lance_opt";
    private static final String LANCE_DB = "sales";
    private static final String LANCE_TABLE = "orders";
    private static final String LANCE_WAREHOUSE =
            "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-server-tests/lance-opt";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private DatabaseService databaseService;
    @Autowired
    private TableService tableService;
    @Autowired
    private TableRepository tableRepository;
    @Autowired
    private TableOptimizationRepository policyRepository;
    @Autowired
    private OptimizationJobRepository jobRepository;
    @Autowired
    private OptimizationJobItemRepository itemRepository;
    @Autowired
    private OptimizationJobService jobService;
    @Autowired
    private OptimizationBatchService batchService;
    @Autowired
    private OptimizationDispatcher dispatcher;
    @Autowired
    private CatalogRepository catalogRepository;
    @Autowired
    private CatalogExecutionClusterRepository clusterRepository;
    @Autowired
    private SchedulerLease lease;
    @Autowired
    private SchedulerLeaseRepository leaseRepository;
    @Autowired
    private AuditEventRepository auditEventRepository;

    private final JsonMapper mapper = JsonMapper.builder().build();

    // ------------------------------------------------------------------ 执行集群夹具

    /**
     * 每个测试前确保 catalog {@code paimon} 有一份**可派发**的执行集群配置。
     *
     * <p>两个数（每批 2 张表、并发 2 批）在这里给，是因为它们已经从 yml 搬到
     * 「每个 catalog 的执行集群」——这正是本次改造的语义变化，测试也必须跟着改，
     * 否则它测的就是一个已经不存在的配置来源。
     *
     * <p>不调探活：{@code probeConfigHash} 留空即 {@code UNVERIFIED}，而 {@code UNVERIFIED}
     * 是可派发的（「刚配好、还没验」是正常中间状态）。探活要真连集群，测试里没有。
     *
     * <p>**收尾在 {@code @AfterAll}**：H2 内存库在同一 JVM 的测试类之间共享，
     * 留着这一行会让「paimon 没有执行集群」的用例（404 那几条）在别处失败。
     */
    @BeforeEach
    void ensureExecutionCluster() {
        ensureCluster(catalogRepository.findByPrefix(PREFIX).orElseThrow(), 2, 2);
    }

    /** 给任意 catalog 写一份可派发的执行集群配置（Lance 用例要给它自己的 catalog 也配一份）。 */
    private void ensureCluster(CatalogEntity catalog, int maxTables, int maxConcurrent) {
        CatalogExecutionClusterEntity entity = clusterRepository.findByCatalogId(catalog.getId()).orElse(null);
        if (entity == null) {
            entity = new CatalogExecutionClusterEntity();
            entity.setId(Paging.newId());
            entity.setCatalogId(catalog.getId());
            entity.markCreated("optimization-tests", 1L);
        }
        entity.setEnabled(true);
        entity.setClusterType("SELF_HOSTED");
        entity.setContextName("test");
        entity.setApiServer("https://k8s.optimization-tests.invalid");
        entity.setNamespace("optimizer");
        entity.setImage("registry.invalid/lakehouse-rest-optimizer:test");
        entity.setImagePullPolicy("IfNotPresent");
        entity.setCallbackBaseUrl("http://lakehouse-rest-server.optimizer-tests.svc:8080");
        entity.setMaxTablesPerJob(maxTables);
        entity.setMaxConcurrentJobs(maxConcurrent);
        entity.setJobTimeoutSeconds(7200);
        entity.setProbeStatus(null);
        entity.setProbeReason(null);
        entity.setProbeMessage(null);
        entity.setProbeConfigHash(null);
        entity.setProbedAt(null);
        // 只用于满足非空约束与「配置变了要重建客户端」的比较；UNVERIFIED 由
        // probeConfigHash 为空决定，与这个值无关
        entity.setConfigHash("optimization-tests-" + catalog.getId());
        entity.touch("optimization-tests", 1L);
        entity.setEntityVersion(entity.getEntityVersion() + 1);
        clusterRepository.save(entity);
    }

    /**
     * 每个测试后把夹具删掉。
     *
     * <p>用 {@code @AfterEach} 而不是 {@code @AfterAll}：H2 内存库在同一 JVM 的测试类之间
     * 共享，而 {@code @AfterAll} 的静态方法注入不了仓储（Spring 只对 PER_CLASS 生命周期
     * 的实例方法做参数注入）。逐测试删建也顺带保证了「上一个测试改坏了这一行」不会外溢。
     */
    @AfterEach
    void removeExecutionCluster() {
        catalogRepository.findByPrefix(PREFIX).ifPresent(catalog -> clusterRepository
                .findByCatalogId(catalog.getId()).ifPresent(clusterRepository::delete));
        catalogRepository.findByPrefix(LANCE_PREFIX).ifPresent(catalog -> clusterRepository
                .findByCatalogId(catalog.getId()).ifPresent(clusterRepository::delete));
    }

    // ------------------------------------------------------------------ 桩提交器

    /**
     * 可编程的提交器。
     *
     * <p>{@code states} 按资源名记状态，默认「提交后进入运行中」；测试用
     * {@link #finish(String, JobState)} 让它变成成功或失败。{@code failSubmit}
     * 用来模拟镜像不存在、权限不足这类提交期失败。
     */
    static final class StubSubmitter implements OptimizationJobSubmitter {

        static final StubSubmitter INSTANCE = new StubSubmitter();

        final Map<String, JobState> states = new ConcurrentHashMap<>();
        final Map<String, SubmissionRequest> requests = new ConcurrentHashMap<>();
        final List<String> cancelled = new CopyOnWriteArrayList<>();
        volatile RuntimeException failSubmit;

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public Submitted submit(SubmissionRequest request) {
            if (failSubmit != null) {
                throw failSubmit;
            }
            requests.put(request.jobId(), request);
            states.putIfAbsent(resourceOf(request.jobId()), JobState.RUNNING);
            return new Submitted("stub-ns", resourceOf(request.jobId()));
        }

        @Override
        public JobState state(String catalogId, String namespace, String resource) {
            return states.getOrDefault(resource, JobState.UNKNOWN);
        }

        @Override
        public void cancel(String catalogId, String namespace, String resource) {
            cancelled.add(resource);
        }

        void finish(String jobId, JobState state) {
            states.put(resourceOf(jobId), state);
        }

        void reset() {
            states.clear();
            requests.clear();
            cancelled.clear();
            failSubmit = null;
        }

        private static String resourceOf(String jobId) {
            return "stub-" + jobId;
        }
    }

    @TestConfiguration
    static class StubSubmitterConfig {

        @Bean
        @Primary
        OptimizationJobSubmitter stubSubmitter() {
            return StubSubmitter.INSTANCE;
        }
    }

    // ------------------------------------------------------------------ 1. 策略

    @Test
    @Order(1)
    void policyDefaultsAndValidation() throws Exception {
        createPaimonTable(DB, TABLE);

        JsonNode policy = json(200, "GET", policyPath(DB, TABLE), null);
        assertEquals(DB, policy.get("database").asString());
        assertEquals(TABLE, policy.get("table").asString());
        assertFalse(policy.get("enabled").asBoolean());
        assertTrue(policy.get("supported").asBoolean());
        assertTrue(absent(policy, "nextRunAt"), "还没启用就没有调度游标");
        assertEquals(3, policy.get("actions").size());

        JsonNode compact = action(policy, "compact");
        assertTrue(compact.get("enabled").asBoolean());
        assertTrue(compact.get("usingDefaults").asBoolean(), "没配过的动作应显示「用默认设置」");
        assertEquals("binpack", compact.get("effective").get("strategy").asString());
        assertEquals("5", compact.get("effective").get("min-input-files").asString());
        assertEquals("128", compact.get("effective").get("target-file-size-mb").asString());
        // 会删数据文件的动作默认关闭
        assertFalse(action(policy, "orphan").get("enabled").asBoolean());

        // 参数名写错 -> 400
        json(400, "PUT", policyPath(DB, TABLE),
                "{\"actions\":[{\"action\":\"compact\",\"config\":{\"min-file-num\":\"5\"}}]}");
        // 排序合并必须给排序列
        json(400, "PUT", policyPath(DB, TABLE),
                "{\"actions\":[{\"action\":\"compact\",\"config\":{\"strategy\":\"sort\"}}]}");
        // 调度间隔超出范围
        json(400, "PUT", policyPath(DB, TABLE), "{\"scheduleIntervalSeconds\":10}");
        // 动作名写错
        json(400, "PUT", policyPath(DB, TABLE), "{\"actions\":[{\"action\":\"compact-all\"}]}");

        JsonNode updated = json(200, "PUT", policyPath(DB, TABLE), """
                {"enabled":true,
                 "scheduleIntervalSeconds":1800,
                 "actions":[
                   {"action":"compact","config":{"strategy":"sort","sort-columns":"dt,id","min-input-files":"8"}},
                   {"action":"orphan","enabled":true,"config":{"older-than-hours":"48","interval-days":"3"}}]}
                """);
        assertTrue(updated.get("enabled").asBoolean());
        assertEquals(1800, updated.get("scheduleIntervalSeconds").asInt());
        assertFalse(absent(updated, "nextRunAt"), "首次启用应立刻到期，不必等一个调度周期");
        JsonNode compactAgain = action(updated, "compact");
        assertFalse(compactAgain.get("usingDefaults").asBoolean());
        assertEquals("sort", compactAgain.get("config").get("strategy").asString());
        // 差异之外的参数仍然来自默认值（合并而不是替换）
        assertEquals("128", compactAgain.get("effective").get("target-file-size-mb").asString());
        assertEquals("dt,id", compactAgain.get("effective").get("sort-columns").asString());
        assertTrue(action(updated, "orphan").get("enabled").asBoolean());
        assertTrue(action(updated, "expire").get("usingDefaults").asBoolean());
    }

    @Test
    @Order(2)
    void catalogDefaultsApplyAndTableOverridesWin() throws Exception {
        createPaimonTable(DB, TABLE2);

        JsonNode settings = json(200, "PUT", M + "/catalogs/" + PREFIX + "/settings", """
                {"properties":{"optimization.compact.min-input-files":"3",
                               "optimization.expire.retention-hours":"72"}}
                """);
        assertEquals("3", settings.get("properties").get("optimization.compact.min-input-files").asString());
        assertEquals("3", action(settings.get("defaults"), "compact").get("effective").get("min-input-files").asString());
        assertEquals("72", action(settings.get("defaults"), "expire").get("effective").get("retention-hours").asString());

        // 没配过的表吃到 catalog 默认值
        JsonNode fresh = json(200, "GET", policyPath(DB, TABLE2), null);
        assertEquals("3", action(fresh, "compact").get("effective").get("min-input-files").asString());
        assertTrue(action(fresh, "compact").get("usingDefaults").asBoolean(),
                "吃默认值不等于「自定义过」：usingDefaults 看的是表级差异");

        // 表级差异优先于 catalog 默认值（第 1 步给 t1 配的是 8）
        JsonNode overridden = json(200, "GET", policyPath(DB, TABLE), null);
        assertEquals("8", action(overridden, "compact").get("effective").get("min-input-files").asString());
        assertEquals("72", action(overridden, "expire").get("effective").get("retention-hours").asString());

        // 键名不合规 -> 400
        json(400, "PUT", M + "/catalogs/" + PREFIX + "/settings",
                "{\"properties\":{\"optimization.compact.min-file-num\":\"3\"}}");
        // catalog 属性里的动作段必须用 wire 名，不接受「更长的英文名」
        json(400, "PUT", M + "/catalogs/" + PREFIX + "/settings",
                "{\"properties\":{\"optimization.compaction.min-input-files\":\"3\"}}");
        json(400, "PUT", M + "/catalogs/" + PREFIX + "/settings",
                "{\"properties\":{\"table-default.bucket\":\"1\"}}");
        // 空值表示清除该键，回落内置默认
        JsonNode cleared = json(200, "PUT", M + "/catalogs/" + PREFIX + "/settings",
                "{\"properties\":{\"optimization.compact.min-input-files\":\"\"}}");
        assertFalse(cleared.get("properties").has("optimization.compact.min-input-files"));
        assertEquals("5", action(cleared.get("defaults"), "compact").get("effective").get("min-input-files").asString());
    }

    @Test
    @Order(3)
    void nonPaimonAndExternalTablesAreRejected() throws Exception {
        String formatTable = "raw_files";
        TypeDtos.Schema schema = new TypeDtos.Schema();
        schema.getFields().add(field(0, "payload", "VARCHAR(20)"));
        schema.getOptions().put("type", "format-table");
        schema.getOptions().put("file.format", "csv");
        // 不指定 path：位置由模板分配到 warehouse 之下（白名单要求，见 Paths.requireWithin）。
        // 本用例要的是「非 Paimon 表被优化器拒绝」，与表落在哪个目录无关。
        tableService.create(PREFIX, DB, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(DB, formatTable), schema));

        JsonNode policy = json(200, "GET", policyPath(DB, formatTable), null);
        assertFalse(policy.get("supported").asBoolean());
        assertTrue(policy.get("unsupportedReason").asString().contains("Paimon"));
        json(400, "PUT", policyPath(DB, formatTable), "{\"enabled\":true}");
        json(400, "POST", runPath(DB, formatTable), "{}");

        // 外部表（position 不归本服务所有）同样拒绝
        TableEntity table = requireTable(DB, TABLE);
        table.setExternal(true);
        tableRepository.save(table);
        try {
            JsonNode external = json(200, "GET", policyPath(DB, TABLE), null);
            assertFalse(external.get("supported").asBoolean());
            assertTrue(external.get("unsupportedReason").asString().contains("外部表"));
            json(400, "PUT", policyPath(DB, TABLE), "{\"enabled\":true}");
        } finally {
            table.setExternal(false);
            tableRepository.save(table);
        }
    }

    // ------------------------------------------------------------------ 2. 立即执行与提交跟踪

    @Test
    @Order(4)
    void manualRunCreatesPendingBatch() throws Exception {
        JsonNode job = json(201, "POST", runPath(DB, TABLE), "{}");
        assertEquals("MANUAL", job.get("triggerType").asString());
        assertEquals(Job.PENDING.name(), job.get("status").asString());
        assertEquals(1, job.get("tableCount").asInt());
        assertEquals(List.of("compact", "expire", "orphan"),
                mapper.convertValue(job.get("actions"), mapper.getTypeFactory()
                        .constructCollectionType(List.class, String.class)));
        assertEquals(1, job.get("items").size());
        assertEquals("compact,expire,orphan", job.get("items").get(0).get("actions").asString());
        assertEquals("PENDING", job.get("items").get(0).get("status").asString());
        assertEquals(PREFIX + "." + DB + "." + TABLE, job.get("items").get(0).get("table").asString());
        // 授权关闭时详情里给出回调令牌（本地调试与端到端测试要用）
        assertFalse(absent(job, "callbackToken"));

        // 指定被关闭的动作 -> 400（t2 的孤儿文件清理默认关闭）
        json(400, "POST", runPath(DB, TABLE2), "{\"actions\":[\"orphan\"]}");
        // 动作名写错 -> 400
        json(400, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact-all\"]}");

        // 列表与过滤
        JsonNode listed = json(200, "GET", M + "/jobs?catalog=" + PREFIX
                + "&status=PENDING&action=expire&limit=10", null);
        assertTrue(listed.get("jobs").size() >= 1);
        for (JsonNode item : listed.get("jobs")) {
            assertEquals(Job.PENDING.name(), item.get("status").asString());
            assertTrue(absent(item, "items"), "列表不返回表级明细");
        }
        json(400, "GET", M + "/jobs?action=nope", null);
        json(404, "GET", M + "/jobs/does-not-exist", null);
    }

    @Test
    @Order(5)
    void submitFetchSubmissionAndReportResults() throws Exception {
        StubSubmitter.INSTANCE.reset();
        String jobId = pendingJobIds().get(0);

        dispatcher.submitPending();
        JsonNode running = job(jobId);
        assertEquals(Job.RUNNING.name(), running.get("status").asString());
        assertEquals("stub", running.get("submitter").asString());
        assertEquals("stub-ns", running.get("namespace").asString());
        assertEquals("stub-" + jobId, running.get("resource").asString());
        assertNotNull(running.get("deadlineAt"));
        assertNotNull(StubSubmitter.INSTANCE.requests.get(jobId), "提交时应把批次回调凭据交给任务");

        String itemId = running.get("items").get(0).get("id").asString();
        String token = running.get("callbackToken").asString();

        // 回调鉴权
        json(403, "GET", M + "/jobs/" + jobId + "/submission", null);
        json(403, "GET", M + "/jobs/" + jobId + "/submission", null, "wrong-token");

        JsonNode submission = json(200, "GET", M + "/jobs/" + jobId + "/submission", null, token);
        assertEquals(PREFIX, submission.get("catalog").asString());
        assertEquals(1, submission.get("tables").size());
        JsonNode submitted = submission.get("tables").get(0);
        assertEquals(PREFIX + "." + DB + "." + TABLE, submitted.get("table").asString());
        // 清单里给的是合并后的全部参数（不是表级差异）
        assertEquals("sort", submitted.get("options").get("compact").get("strategy").asString());
        assertEquals("128", submitted.get("options").get("compact").get("target-file-size-mb").asString());
        assertEquals("48", submitted.get("options").get("orphan").get("older-than-hours").asString());

        // 非法状态与非法令牌
        json(400, "POST", M + "/jobs/" + jobId + "/items/" + itemId,
                "{\"status\":\"DONE\"}", token);
        json(403, "POST", M + "/jobs/" + jobId + "/items/" + itemId,
                "{\"status\":\"SUCCESS\"}", "wrong-token");

        json(204, "POST", M + "/jobs/" + jobId + "/items/" + itemId, """
                {"status":"SUCCESS","metrics":{"files-before":"6","files-after":"1",
                 "size-before":"3.4 KB","size-after":"847 B"}}
                """, token);
        JsonNode reported = job(jobId).get("items").get(0);
        assertEquals("SUCCESS", reported.get("status").asString());
        assertEquals("6", reported.get("metrics").get("files-before").asString());
        assertNotNull(reported.get("reportedAt"));
        // 已终结的明细不接受改写（重跑会产生新批次）
        json(400, "POST", M + "/jobs/" + jobId + "/items/" + itemId,
                "{\"status\":\"FAILED\"}", token);

        StubSubmitter.INSTANCE.finish(jobId, JobState.SUCCEEDED);
        dispatcher.pollRunning();
        assertEquals(Job.SUCCESS.name(), job(jobId).get("status").asString());
        assertNotNull(job(jobId).get("finishedAt"));

        // 表级历史：控制台「存储优化」页签的三块历史都从这一个端点取
        JsonNode history = json(200, "GET", historyPath(DB, TABLE), null);
        assertEquals(PREFIX + "." + DB + "." + TABLE, history.get("table").asString());
        JsonNode latest = history.get("items").get(0);
        assertEquals(itemId, latest.get("id").asString(), "最近的在前");
        assertEquals(jobId, latest.get("jobId").asString(), "历史里要能回到批次");
        assertEquals("SUCCESS", latest.get("status").asString());
        assertEquals("1", latest.get("metrics").get("files-after").asString());
        // 别的表不在这张表的历史里；表不存在时 404
        assertTrue(json(200, "GET", historyPath(DB, TABLE2), null).get("items").isEmpty());
        json(404, "GET", historyPath(DB, "does-not-exist"), null);
    }

    // ------------------------------------------------------------------ 3. 组批

    @Test
    @Order(6)
    void schedulerMergesDueTablesIntoSingleBatch() throws Exception {
        clearActiveJobs();
        parkPolicies();
        for (String table : List.of("b1", "b2", "b3", "b4")) {
            createPaimonTable(BATCH_DB, table);
            json(200, "PUT", policyPath(BATCH_DB, table), "{\"enabled\":true}");
        }
        int before = jobRepository.findAllByOrderByCreatedAtDesc().size();

        // 同一 catalog 的到期表合成一个批次；批内动作是各表开关的交集
        assertEquals(1, batchService.scanAndCreateBatches());
        assertEquals(before + 1, jobRepository.findAllByOrderByCreatedAtDesc().size());
        OptimizationJobEntity first = jobRepository.findAllByOrderByCreatedAtDesc().get(0);
        assertEquals(OptimizationJobService.TRIGGER_SCHEDULED, first.getTriggerType());
        assertEquals("compact,expire", first.getActions(), "未启用孤儿清理的表不该把它带进批次动作");
        assertEquals(2, first.getTableCount(), "每批上限 2 张表");
        assertEquals(2, itemRepository.findAllByJobIdOrderByCreatedAtAsc(first.getId()).size());
        assertEquals(2, policyRepository.findAll().stream()
                .filter(policy -> first.getId().equals(policy.getLastJobId())).count(), "回填最近批次");

        // 批满后剩下的表顺延到下一拍，不会被丢掉
        assertEquals(1, batchService.scanAndCreateBatches());
        assertEquals(before + 2, jobRepository.findAllByOrderByCreatedAtDesc().size());
        assertEquals(0, batchService.scanAndCreateBatches(), "游标都推进过了，这一拍无事可做");

        // 并发配额已满：新到期的表既不进批，也不动它的游标（下一拍还会看到它）
        createPaimonTable(BATCH_DB, "b5");
        json(200, "PUT", policyPath(BATCH_DB, "b5"), "{\"enabled\":true}");
        Long cursorBefore = policy(BATCH_DB, "b5").getNextRunAt();
        assertNotNull(cursorBefore);
        assertTrue(jobRepository.countByStatusIn(List.of(Job.PENDING.name()))
                >= 2, "两个批次还没结束，配额已满");
        assertEquals(0, batchService.scanAndCreateBatches());
        assertEquals(before + 2, jobRepository.findAllByOrderByCreatedAtDesc().size());
        assertEquals(cursorBefore, policy(BATCH_DB, "b5").getNextRunAt(),
                "拿不到执行配额时不该推进游标，否则这张表会被永久跳过");

        // 释放一个配额后它立刻被捞起来
        jobService.cancel(jobRepository.findTop50ByStatusOrderByCreatedAtAsc(Job.PENDING.name())
                .get(0).getId(), "test");
        assertEquals(1, batchService.scanAndCreateBatches());
        assertEquals(before + 3, jobRepository.findAllByOrderByCreatedAtDesc().size());
        assertEquals(1, jobRepository.findAllByOrderByCreatedAtDesc().get(0).getTableCount());
    }

    // ------------------------------------------------------------------ 4. 部分成功、重试、取消与超时

    @Test
    @Order(7)
    void partialSuccessThenRetryOnlyFailedTables() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();
        parkPolicies();
        for (String table : List.of("c1", "c2", "c3", "c4")) {
            createPaimonTable(BATCH_DB, table);
            json(200, "PUT", policyPath(BATCH_DB, table), "{\"enabled\":true}");
        }
        assertEquals(1, batchService.scanAndCreateBatches());
        assertEquals(1, batchService.scanAndCreateBatches());
        List<OptimizationJobEntity> batches = jobRepository.findAllByOrderByCreatedAtDesc();
        String allSettled = batches.get(1).getId();
        String partlyFailed = batches.get(0).getId();
        assertEquals(2, batches.get(0).getTableCount());
        assertEquals(2, batches.get(1).getTableCount());

        dispatcher.submitPending();

        // 批次一：一张成功、一张跳过 -> 全部明细都有结论，批次成功
        List<JsonNode> settledItems = itemsOf(allSettled);
        String settledToken = job(allSettled).get("callbackToken").asString();
        report(allSettled, settledItems.get(0), settledToken,
                "{\"status\":\"SUCCESS\",\"metrics\":{\"files-before\":\"128\",\"files-after\":\"9\"}}");
        report(allSettled, settledItems.get(1), settledToken,
                "{\"status\":\"SKIPPED\",\"metrics\":{\"reason\":\"below-threshold\"}}");
        StubSubmitter.INSTANCE.finish(allSettled, JobState.SUCCEEDED);
        dispatcher.pollRunning();
        assertEquals(Job.SUCCESS.name(), job(allSettled).get("status").asString(),
                "跳过是「跑过但无事可做」，不该算失败");

        // 批次二：一张上报失败、一张根本没上报 -> 部分成功，未上报的按失败处理
        List<JsonNode> failedItems = itemsOf(partlyFailed);
        String failedToken = job(partlyFailed).get("callbackToken").asString();
        report(partlyFailed, failedItems.get(0), failedToken, "{\"status\":\"SUCCESS\",\"metrics\":{}}");
        report(partlyFailed, failedItems.get(1), failedToken,
                "{\"status\":\"FAILED\",\"errorMessage\":\"java.io.IOException: commit conflict\"}");
        StubSubmitter.INSTANCE.finish(partlyFailed, JobState.SUCCEEDED);
        dispatcher.pollRunning();
        JsonNode partial = job(partlyFailed);
        assertEquals(Job.PARTIAL_SUCCESS.name(), partial.get("status").asString());
        assertEquals("FAILED", partial.get("items").get(1).get("status").asString());
        assertTrue(partial.get("items").get(1).get("errorMessage").asString().contains("commit conflict"));

        // 执行侧说成功、但表压根没上报 -> 那一行是「未知」而不是成功，按失败处理
        createPaimonTable(BATCH_DB, "c5");
        json(200, "PUT", policyPath(BATCH_DB, "c5"), "{\"enabled\":true}");
        assertEquals(1, batchService.scanAndCreateBatches());
        String silent = jobRepository.findAllByOrderByCreatedAtDesc().get(0).getId();
        dispatcher.submitPending();
        StubSubmitter.INSTANCE.finish(silent, JobState.SUCCEEDED);
        dispatcher.pollRunning();
        JsonNode silentJob = job(silent);
        assertEquals(Job.FAILED.name(), silentJob.get("status").asString());
        assertTrue(silentJob.get("items").get(0).get("errorMessage").asString().contains("没有上报结果"));

        // 重试只重跑失败的表，并指向原批次
        JsonNode retry = json(201, "POST", M + "/jobs/" + partlyFailed + "/retry", "{}");
        assertEquals(partlyFailed, retry.get("retryOf").asString());
        assertEquals(1, retry.get("tableCount").asInt());
        assertEquals(Job.PENDING.name(), retry.get("status").asString());
        assertEquals(partial.get("items").get(1).get("table").asString(),
                retry.get("items").get(0).get("table").asString());

        // 没有失败表的批次不能重试
        json(400, "POST", M + "/jobs/" + allSettled + "/retry", "{}");
        clearActiveJobs();
    }

    @Test
    @Order(8)
    void cancelTimesOutAndSubmitFailure() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();

        // 取消：未提交的批次没有执行侧资源，只改状态并把明细置失败
        JsonNode pending = json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}");
        String pendingId = pending.get("id").asString();
        JsonNode canceled = json(200, "POST", M + "/jobs/" + pendingId + "/cancel", null);
        assertEquals(Job.CANCELED.name(), canceled.get("status").asString());
        assertEquals("FAILED", canceled.get("items").get(0).get("status").asString());
        json(400, "POST", M + "/jobs/" + pendingId + "/cancel", null);

        // 超时：把截止时间挪到过去，跟踪节拍应判失败并清理执行侧资源
        JsonNode runningJob = json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}");
        String runningId = runningJob.get("id").asString();
        dispatcher.submitPending();
        assertEquals(Job.RUNNING.name(), job(runningId).get("status").asString());
        OptimizationJobEntity entity = jobRepository.findById(runningId).orElseThrow();
        entity.setDeadlineAt(System.currentTimeMillis() - 1000);
        jobRepository.save(entity);
        dispatcher.pollRunning();
        JsonNode timedOut = job(runningId);
        assertEquals(Job.FAILED.name(), timedOut.get("status").asString());
        assertTrue(timedOut.get("errorMessage").asString().contains("超时"));
        assertTrue(StubSubmitter.INSTANCE.cancelled.contains("stub-" + runningId), "超时后要清理执行侧资源");

        // 提交失败：镜像不存在这类问题在提交期就失败，不反复重投
        StubSubmitter.INSTANCE.failSubmit = new IllegalStateException("image pull back-off");
        JsonNode failing = json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}");
        String failingId = failing.get("id").asString();
        dispatcher.submitPending();
        JsonNode failed = job(failingId);
        assertEquals(Job.FAILED.name(), failed.get("status").asString());
        assertTrue(failed.get("errorMessage").asString().contains("image pull back-off"));
        assertEquals("FAILED", failed.get("items").get(0).get("status").asString());
        StubSubmitter.INSTANCE.failSubmit = null;
        clearActiveJobs();
    }

    // ------------------------------------------------------------------ 5. 并发闸门

    /**
     * 手动触发的批次同样受并发上限约束。
     *
     * <p>出批侧那道配额检查（{@code OptimizationBatchCreator}）只拦「自动调度造批次」——
     * 手动「立即优化」与重试都是直接建批次，压根不经过那条路。少了提交侧这道闸门，
     * 运维连点几次就能在集群里堆起同等数量的 Spark 任务，而这条上限本来就是
     * 「给集群留余量」用的（每个批次一个驱动），绕过它等于上限不存在。
     *
     * <p>用**三张不同的表**造三个批次：同一张表连点三次现在会被 409 挡住
     * （不变式 I1，见 {@link #manualRunRejectsTableAlreadyInAnActiveBatch()}），
     * 而这条用例要测的是提交侧配额，不是同表互斥。
     */
    @Test
    @Order(9)
    void concurrencyLimitAlsoGatesManuallyTriggeredBatches() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();
        parkPolicies();

        for (String table : List.of(TABLE, "t3", "t4")) {
            createPaimonTable(DB, table);
            json(201, "POST", runPath(DB, table), "{\"actions\":[\"compact\"]}");
        }
        assertEquals(3, pendingJobIds().size());

        dispatcher.submitPending();

        assertEquals(2, jobRepository.countByStatusIn(List.of(Job.RUNNING.name())), "并发上限是 2");
        assertEquals(1, pendingJobIds().size(), "放不出去的批次留在队列里等下一拍，不是被丢掉");

        // 释放一个名额，排队中的那个在下一拍被放行
        String runningId = jobRepository.findTop50ByStatusOrderByCreatedAtAsc(Job.RUNNING.name())
                .get(0).getId();
        jobService.cancel(runningId, "test");
        dispatcher.submitPending();
        assertEquals(2, jobRepository.countByStatusIn(List.of(Job.RUNNING.name())));
        assertEquals(0, pendingJobIds().size(), "配额一空，队列立刻往前走");
        clearActiveJobs();
    }

    // ------------------------------------------------------------------ 6. 多副本：同表互斥与终态只写一次

    /**
     * 「立即优化」与调度批次抢同一张表时，后到的那个必须被挡住。
     *
     * <p>这不是多副本才有的问题：调度扫描与用户点按钮在同一个进程里也是两个线程，
     * 而两者的「这张表没人跑」都是读出来的结论。修法是同一把 catalog 锁内复查活跃明细。
     * 409 的报文要带上已有批次 id——控制台据此把用户导过去，而不是让他盯着一个
     * 「什么都没发生」的按钮。
     */
    @Test
    @Order(10)
    void manualRunRejectsTableAlreadyInAnActiveBatch() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();

        JsonNode first = json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}");
        String jobId = first.get("id").asString();

        JsonNode conflict = json(409, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}");
        assertTrue(conflict.get("message").asString().contains(jobId),
                "409 要说清是哪张表进了哪个批次，实际：" + conflict);
        clearActiveJobs();
    }

    /**
     * 终态只写一次：这是多副本下**必然发生**的那一处。
     *
     * <p>每台机器都会 poll 到同一个 {@code RUNNING} 批次，因此收尾必须是条件更新。
     * 这里用「成功之后再判一次超时」来覆盖同一个场景——超时判定是最危险的那条路径，
     * 它会把 {@code SUCCESS} 改写成 {@code PARTIAL_SUCCESS}。
     */
    @Test
    @Order(11)
    void terminalStateIsWrittenOnceAndNeverDowngraded() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();

        JsonNode created = json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}");
        String jobId = created.get("id").asString();
        dispatcher.submitPending();
        String token = job(jobId).get("callbackToken").asString();
        report(jobId, itemsOf(jobId).get(0), token, "{\"status\":\"SUCCESS\",\"metrics\":{}}");
        assertEquals(Job.SUCCESS.name(), finishWith(jobId, JobState.SUCCEEDED).get("status").asString(),
                "明细全成功 -> SUCCESS");
        Long finishedAt = jobRepository.findById(jobId).orElseThrow().getFinishedAt();

        // 另一个实例也在收尾：它的条件更新返回 0 行，状态与完成时刻都不该变
        jobService.finishFromSubmitter(jobId, JobState.SUCCEEDED, null);
        assertEquals(finishedAt, jobRepository.findById(jobId).orElseThrow().getFinishedAt());

        // 最危险的一条：超时判定不能把一个已成功的批次改写成部分成功（I5）
        OptimizationJobEntity entity = jobRepository.findById(jobId).orElseThrow();
        entity.setDeadlineAt(System.currentTimeMillis() - 1000);
        jobRepository.save(entity);
        jobService.markTimedOut(jobId);
        assertEquals(Job.SUCCESS.name(), jobRepository.findById(jobId).orElseThrow().getStatus(),
                "成功一旦写入，不能被超时路径改写");

        // 事件只发一次（I3）：由 db 监听器落进 lakehouse_audit_event
        assertEquals(1, auditCount(CatalogEventType.OPTIMIZATION_JOB_SUCCEEDED.wire(), jobId),
                "同一个批次只能有一条成功事件");
        clearActiveJobs();
    }

    // ------------------------------------------------------------------ 7. 多副本：提交租约的 fencing 与回收

    /**
     * 进程在 {@code submit()} 之后、回写之前被杀：租约过期 → 别人回收 → 重新抢占，
     * 而**上一代的回写必须被丢弃**（否则它会把接管者的 deadline 覆盖掉）。
     */
    @Test
    @Order(12)
    void submissionLeaseFencingDropsStaleWriteBack() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();

        String jobId = json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}")
                .get("id").asString();
        int first = jobService.claimForSubmission(jobId);
        assertTrue(first > 0, "PENDING 批次应当能抢到");
        OptimizationJobEntity claimed = jobRepository.findById(jobId).orElseThrow();
        assertEquals(lease.instanceId(), claimed.getClaimOwner(), "抢占要写提交租约的持有者");
        assertEquals(Job.SUBMITTING.name(), claimed.getStatus());

        // 模拟持有者已死：租约过期（不调 markSubmitted）
        claimed.setClaimExpiresAt(System.currentTimeMillis() - 1);
        jobRepository.save(claimed);
        assertEquals(1, jobService.reapStaleSubmissions(), "过期的提交租约要被回收");
        assertEquals(Job.PENDING.name(), jobRepository.findById(jobId).orElseThrow().getStatus(),
                "回收之后回到队列，下一拍重投（命中同名 Job，不会双跑）");

        int second = jobService.claimForSubmission(jobId);
        assertEquals(first + 1, second, "每次重新抢占代数 +1");
        assertFalse(jobService.markSubmitted(jobId, new Submitted("stub-ns", "stub-" + jobId), first),
                "上一代的回写必须被丢弃");
        assertTrue(jobService.markSubmitted(jobId, new Submitted("stub-ns", "stub-" + jobId), second));
        assertEquals(Job.RUNNING.name(), jobRepository.findById(jobId).orElseThrow().getStatus());
        clearActiveJobs();
    }

    /** 反复中断的提交不再无限重投：代数封顶之后直接判失败，原因写清楚。 */
    @Test
    @Order(13)
    void repeatedlyInterruptedSubmissionFailsInsteadOfRetryingForever() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();

        String jobId = json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}")
                .get("id").asString();
        OptimizationJobEntity entity = jobRepository.findById(jobId).orElseThrow();
        entity.setStatus(Job.SUBMITTING.name());
        entity.setClaimOwner("dead-instance/1");
        entity.setClaimAttempt(OptimizationJobService.MAX_SUBMIT_ATTEMPTS);
        entity.setClaimExpiresAt(System.currentTimeMillis() - 1);
        jobRepository.save(entity);

        jobService.reapStaleSubmissions();
        OptimizationJobEntity failed = jobRepository.findById(jobId).orElseThrow();
        assertEquals(Job.FAILED.name(), failed.getStatus(), "第 3 次中断之后不再回队列");
        assertTrue(failed.getErrorMessage().contains("反复中断"));
        assertEquals("FAILED", itemRepository.findAllByJobIdOrderByCreatedAtAsc(jobId).get(0).getStatus(),
                "没跑起来的表要留一条可读的失败");
        clearActiveJobs();
    }

    // ------------------------------------------------------------------ 8. 调度通道租约

    /**
     * 通道租约的语义：持有者续约 → 别人抢不到；让出（或到期）→ 别人立刻能接手。
     *
     * <p>这里直接用仓储模拟「另一台机器」：两个实例之间没有任何进程内共享，
     * 唯一的裁判就是这一行。
     *
     * <p>方法上带 {@code @Transactional}：{@code @Modifying} 查询必须在一个事务里执行，
     * 而且整段断言结束后回滚——租约是全局共享行，不该让这条用例影响别的用例。
     */
    @Test
    @Order(14)
    @Transactional
    void schedulerLeaseIsExclusiveAndReleasable() {
        assertEquals(SchedulerLease.channels().size(), leaseRepository.count(),
                "启动时要把所有通道的行补齐（少一行 = 那个通道没有单写者）");

        assertTrue(lease.tryAcquire(SchedulerLease.CHANNEL_SCAN));
        long now = System.currentTimeMillis();
        assertEquals(0, leaseRepository.acquire(SchedulerLease.CHANNEL_SCAN, "other-instance/1",
                        now, now + 60_000), "别人持有且未过期时抢不到");

        lease.release(SchedulerLease.CHANNEL_SCAN);
        assertEquals(1, leaseRepository.acquire(SchedulerLease.CHANNEL_SCAN, "other-instance/1",
                        now, now + 60_000), "让出之后别人立刻能接手");

        // 换回本实例，保持夹具干净
        leaseRepository.release(SchedulerLease.CHANNEL_SCAN, "other-instance/1", now);
        assertTrue(lease.tryAcquire(SchedulerLease.CHANNEL_SCAN));
    }

    // ------------------------------------------------------------------ 3. Lance（第三个格式面）

    @Test
    @Order(20)
    void lanceUsesItsOwnKeysAndRefusesOrphan() throws Exception {
        String base = ensureLanceTable();
        JsonNode policy = json(200, "GET", base + "/policy", null);

        assertEquals("LANCE", policy.get("format").asString());
        assertTrue(policy.get("supported").asBoolean(), "declare 出来的 Lance 表可优化");

        // 键表是第三套：目标大小按**行**算，且没有「至少几个小文件」这个门槛
        JsonNode compact = action(policy, "compact");
        assertTrue(compact.get("supported").asBoolean());
        assertEquals("1048576", compact.get("effective").get("target-rows-per-fragment").asString());
        assertTrue(absent(compact.get("effective"), "batch-size"), "batch-size 默认不传（用 connector 默认）");
        assertTrue(absent(compact.get("effective"), "min-input-files"), "Lance 没有这个门槛");
        assertTrue(absent(compact.get("effective"), "strategy"), "OPTIMIZE 不做排序合并");
        // connector 0.4.0 的 WITH 里没有 max_source_fragments（CompactionOptions 有字段但
        // Spark 侧没接出来），给了也只会被静默忽略，所以这个键根本不提供
        assertTrue(absent(compact.get("effective"), "max-source-fragments"),
                "不提供不生效的旋钮");
        assertEquals(6, compact.get("effective").size(), compact.get("effective").toString());

        // expire = VACUUM：不可逆、没有干跑，因此默认关闭
        JsonNode expire = action(policy, "expire");
        assertEquals("72", expire.get("effective").get("retention-hours").asString());
        assertEquals("7", expire.get("effective").get("interval-days").asString());
        assertFalse(expire.get("enabled").asBoolean(), "VACUUM 无干跑，默认不自动执行");

        // orphan 存在但不可用：置灰由前端做，服务端给的是「不支持 + 原因」
        JsonNode orphan = action(policy, "orphan");
        assertFalse(orphan.get("supported").asBoolean());
        assertFalse(orphan.get("enabled").asBoolean());
        assertTrue(orphan.get("unsupportedReason").asString().contains("VACUUM"),
                orphan.toString());
        assertEquals(0, orphan.get("effective").size(), "不支持的动作没有参数表");

        // 保存不支持的动作 -> 400，且文案说清是「VACUUM 已包含」而不是「你没开开关」
        JsonNode rejected = json(400, "PUT", base + "/policy",
                "{\"actions\":[{\"action\":\"orphan\",\"enabled\":true}]}");
        assertTrue(rejected.get("message").asString().contains("VACUUM"), rejected.toString());
        // 立即执行同样拒绝：绕不过去
        json(400, "POST", base + "/run", "{\"actions\":[\"orphan\"]}");

        // Paimon 的键在这张格式上不存在 -> 400（两套键表混用不会静默生效）
        json(400, "PUT", base + "/policy",
                "{\"actions\":[{\"action\":\"compact\",\"config\":{\"min-input-files\":\"5\"}}]}");
        // 行数上限超界 -> 400
        json(400, "PUT", base + "/policy",
                "{\"actions\":[{\"action\":\"compact\",\"config\":{\"target-rows-per-fragment\":\"1000000000\"}}]}");
        // delete_unverified 恒 false：它是安全属性，不是可调参数
        json(400, "PUT", base + "/policy",
                "{\"actions\":[{\"action\":\"expire\",\"config\":{\"delete-unverified\":\"true\"}}]}");

        // 保留窗口的相对下限：catalog 的 max-commit-interval-hours 默认 24，填 12 要拒
        JsonNode tooShort = json(400, "PUT", base + "/policy",
                "{\"actions\":[{\"action\":\"expire\",\"config\":{\"retention-hours\":\"12\"}}]}");
        assertTrue(tooShort.get("message").asString().contains("24"), tooShort.toString());

        JsonNode saved = json(200, "PUT", base + "/policy", """
                {"enabled":true,
                 "actions":[
                   {"action":"compact","config":{"target-rows-per-fragment":"524288","max-rows-per-group":"2048"}},
                   {"action":"expire","enabled":true,"config":{"retention-hours":"48","interval-days":"3"}}]}
                """);
        assertEquals("524288", action(saved, "compact").get("effective").get("target-rows-per-fragment").asString());
        assertEquals("2048", action(saved, "compact").get("effective").get("max-rows-per-group").asString());
        assertEquals("0.1", action(saved, "compact").get("effective").get("materialize-deletions-threshold").asString(),
                "差异之外的键仍来自默认值（合并而不是替换）");
        assertEquals("48", action(saved, "expire").get("effective").get("retention-hours").asString());

        // catalog 级的「最大提交间隔」可以调高，而它会把低于新下限的表级值拒掉
        json(200, "PUT", M + "/catalogs/" + LANCE_PREFIX + "/settings",
                "{\"properties\":{\"optimization.expire.max-commit-interval-hours\":\"96\"}}");
        json(400, "PUT", base + "/policy",
                "{\"actions\":[{\"action\":\"expire\",\"config\":{\"retention-hours\":\"48\"}}]}");
        // 这一条是 catalog-only 的键：写进表策略要拒（它是全局护栏，不是表参数）
        json(400, "PUT", base + "/policy",
                "{\"actions\":[{\"action\":\"expire\",\"config\":{\"max-commit-interval-hours\":\"96\"}}]}");
        json(400, "PUT", M + "/catalogs/" + LANCE_PREFIX + "/settings",
                "{\"properties\":{\"optimization.compact.target_rows_per_fragment\":\"524288\"}}");
    }

    @Test
    @Order(21)
    void lanceSubmissionCarriesItsOwnFormat() throws Exception {
        String base = ensureLanceTable();
        clearActiveJobs();
        // 先把上一轮（@Order(20) 里调高的）相对下限复位，48 小时才是合法的
        json(200, "PUT", M + "/catalogs/" + LANCE_PREFIX + "/settings",
                "{\"properties\":{\"optimization.expire.max-commit-interval-hours\":\"\"}}");
        json(200, "PUT", base + "/policy", """
                {"enabled":true,
                 "actions":[{"action":"compact","config":{"target-rows-per-fragment":"524288"}},
                            {"action":"expire","enabled":true,"config":{"retention-hours":"48","interval-days":"3"}}]}
                """);
        String jobId = json(201, "POST", base + "/run", "{}").get("id").asString();

        StubSubmitter.INSTANCE.reset();
        dispatcher.submitPending();
        JsonNode running = job(jobId);
        assertEquals(Job.RUNNING.name(), running.get("status").asString());
        JsonNode submission = json(200, "GET", M + "/jobs/" + jobId + "/submission", null,
                running.get("callbackToken").asString());

        // 清单级格式决定执行侧建哪种会话：缺了它，Lance 批次会静默走 Paimon 分支
        assertEquals("LANCE", submission.get("format").asString());
        assertEquals(List.of("compact", "expire"),
                List.of(submission.get("actions").get(0).asString(), submission.get("actions").get(1).asString()));
        JsonNode options = submission.get("tables").get(0).get("options");
        assertEquals("524288", options.get("compact").get("target-rows-per-fragment").asString());
        // 调度参数与常量都不进语句，但清单里给的是「合并后的全部参数」，它们照样在
        assertEquals("1", options.get("compact").get("interval-hours").asString());
        assertEquals("48", options.get("expire").get("retention-hours").asString());
        assertEquals("false", options.get("expire").get("delete-unverified").asString());

        json(204, "POST", M + "/jobs/" + jobId + "/items/" + running.get("items").get(0).get("id").asString(),
                "{\"status\":\"SUCCESS\",\"metrics\":{\"compact.fragments-removed\":\"8\","
                        + "\"compact.files-removed\":\"12\"}}",
                running.get("callbackToken").asString());
        assertEquals("8", job(jobId).get("items").get(0).get("metrics")
                .get("compact.fragments-removed").asString());
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 造一套 Lance 夹具（catalog + 命名空间 + 一张 declare 出来的表），返回表策略的路径。
     *
     * <p>只判 catalog 在不在：它一次性把三件事都建出来，因此「catalog 存在」等价于
     * 「命名空间与表也存在」。不重复调 {@code declare}——Lance 的 declare 对已存在的表
     * 是 409（不是幂等的 exist_ok），第二次调用会把用例打红。
     *
     * <p>执行集群配置在 {@code @AfterEach} 里被删掉，所以这里每跑一次都要补一份：
     * 没有它，保存启用策略与立即执行都会被 fail-closed 挡成 400，用例就测了个空。
     */
    private String ensureLanceTable() throws Exception {
        CatalogEntity catalog = catalogRepository.findByPrefix(LANCE_PREFIX).orElse(null);
        if (catalog == null) {
            json(201, "POST", MANAGEMENT + "/catalogs", """
                    {"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"LANCE","properties":{},
                     "storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}
                    """.formatted(LANCE_PREFIX, LANCE_WAREHOUSE));
            catalog = catalogRepository.findByPrefix(LANCE_PREFIX).orElseThrow();
            String namespaceId = LANCE_PREFIX + "%24" + LANCE_DB;
            postViaUri(200, "/lance/v1/namespace/" + namespaceId + "/create", "{\"properties\":{}}");
            postViaUri(200, "/lance/v1/table/" + namespaceId + "%24" + LANCE_TABLE + "/declare",
                    "{\"properties\":{}}");
        }
        ensureCluster(catalog, 2, 2);
        return M + "/tables/" + LANCE_PREFIX + "/" + LANCE_DB + "/" + LANCE_TABLE;
    }

    /**
     * 发一个 POST，路径里的 {@code %24} 原样保留。
     *
     * <p>MockMvc 的 {@code post(String)} 会把模板变量再编码一次（{@code %24} → {@code %2524}），
     * 于是 Lance 的 id 里会多出一层转义、路径压根匹配不上。用 {@link URI} 构造才是原样的
     * （同一个坑在 {@code LanceVersionApiTests} 里踩过一次）。
     */
    private JsonNode postViaUri(int expected, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(URI.create(path));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String text = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(), () -> path + " unexpected status, body=" + text);
        return text == null || text.isBlank() ? JsonNodeFactory.instance.objectNode() : mapper.readTree(text);
    }

    private String policyPath(String database, String table) {
        return M + "/tables/" + PREFIX + "/" + database + "/" + table + "/policy";
    }

    /** 立即优化的端点挂在表上，不带 {@code /policy} 段（见方案文档 §5）。 */
    private String runPath(String database, String table) {
        return M + "/tables/" + PREFIX + "/" + database + "/" + table + "/run";
    }

    /** 单表优化历史的端点，同样挂在表上。 */
    private String historyPath(String database, String table) {
        return M + "/tables/" + PREFIX + "/" + database + "/" + table + "/items";
    }

    private JsonNode job(String jobId) throws Exception {
        return json(200, "GET", M + "/jobs/" + jobId, null);
    }

    /** 让桩提交器给出终态，再跑一拍跟踪，返回刷新后的批次。 */
    private JsonNode finishWith(String jobId, JobState state) throws Exception {
        StubSubmitter.INSTANCE.finish(jobId, state);
        dispatcher.pollRunning();
        return job(jobId);
    }

    /** 某个批次在某类事件上落了几条审计（I3：终态事件只发一次）。 */
    private long auditCount(String type, String target) {
        return auditEventRepository.findAll().stream()
                .filter(event -> type.equals(event.getType()) && target.equals(event.getTarget()))
                .count();
    }

    /** 批次详情里的明细列表，用来按行上报结果。顺序由服务端保证（创建先后）。 */
    private List<JsonNode> itemsOf(String jobId) throws Exception {
        List<JsonNode> items = new ArrayList<>();
        job(jobId).get("items").forEach(items::add);
        return items;
    }

    /** 以优化任务的身份上报一条表级结果。 */
    private void report(String jobId, JsonNode item, String token, String body) throws Exception {
        json(204, "POST", M + "/jobs/" + jobId + "/items/" + item.get("id").asString(), body, token);
    }

    private List<String> pendingJobIds() {
        List<String> ids = new ArrayList<>();
        for (OptimizationJobEntity job : jobRepository.findTop50ByStatusOrderByCreatedAtAsc(Job.PENDING.name())) {
            ids.add(job.getId());
        }
        return ids;
    }

    private TableOptimizationEntity policy(String database, String table) {
        return policyRepository.findByTableId(requireTable(database, table).getId()).orElseThrow();
    }

    /** 把未结束的批次收尾，避免并发配额被上一个用例占着（跨用例的确定性前提）。 */
    private void clearActiveJobs() {
        for (OptimizationJobEntity job : jobRepository.findAllByOrderByCreatedAtDesc()) {
            Job status = Job.parse(job.getStatus());
            if (status != null && status.active()) {
                jobService.cancel(job.getId(), "test");
            }
        }
    }

    /**
     * 把已有策略的调度游标推到一天之后。
     *
     * <p>组批用例只该看到自己刚建的那几张表。没有这一步，「到期表」里会混进
     * 前面用例留下的策略（比如第 1 步启用的 t1），批次数就成了跨用例的耦合值——
     * 用例顺序一调整就红。放在这里显式清场，比在每个断言里算上历史表可靠。
     *
     * <p>不能把游标置空来「清场」：空游标会被初始化成「立刻到期」，方向正好相反。
     */
    private void parkPolicies() {
        long far = System.currentTimeMillis() + 86_400_000L;
        for (TableOptimizationEntity policy : policyRepository.findAll()) {
            policy.setNextRunAt(far);
            policyRepository.save(policy);
        }
    }

    private TableEntity requireTable(String database, String table) {
        return tableRepository
                .findByCatalogIdAndDatabaseIdAndName(catalogId(), databaseId(database), table)
                .orElseThrow();
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
        schema.getFields().add(field(1, "dt", "VARCHAR(10)"));
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

    /**
     * 字段「没有值」。
     *
     * <p>服务端开了 {@code spring.jackson.default-property-inclusion: non_null}，
     * 因此值为 null 的字段在 JSON 里是**不出现**，而不是一个 null 节点。
     * 两种写法都要认，否则断言会因为「字段不存在」而 NPE，看起来像功能坏了。
     */
    private static boolean absent(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull();
    }

    private static JsonNode action(JsonNode policyOrSettings, String action) {        JsonNode actions = policyOrSettings.has("actions") ? policyOrSettings.get("actions") : policyOrSettings;
        for (JsonNode node : actions) {
            if (action.equals(node.get("action").asString())) {
                return node;
            }
        }
        throw new AssertionError("action not found: " + action);
    }

    private static TypeDtos.DataField field(int id, String name, String type) {
        TypeDtos.DataField field = new TypeDtos.DataField();
        field.setId(id);
        field.setName(name);
        field.setType(type);
        return field;
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        return json(expected, method, path, body, null);
    }

    private JsonNode json(int expected, String method, String path, String body, String token) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "POST" -> post(path);
            case "PUT" -> put(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        if (token != null) {
            request = request.header("X-Optimization-Token", token);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> path + " unexpected status, body=" + responseBody);
        if (expected == 204 || responseBody == null || responseBody.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return mapper.readTree(responseBody);
    }
}
