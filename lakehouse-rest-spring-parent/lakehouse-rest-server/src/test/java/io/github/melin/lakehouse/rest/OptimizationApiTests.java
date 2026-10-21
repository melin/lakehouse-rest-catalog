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
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobItemRepository;
import io.github.melin.lakehouse.rest.domain.repo.OptimizationJobRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableOptimizationRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * <p>批次数上限与并发上限在 {@code @TestPropertySource} 里调小（每批 2 张表、并发 2 批），
 * 这样用 4~5 张表就能覆盖「合并执行」「批满顺延」「拿不到配额不动游标」三种行为，
 * 不必真的建几十张表。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "lakehouse.rest.optimization.scheduler-enabled=false",
        "lakehouse.rest.optimization.max-tables-per-job=2",
        "lakehouse.rest.optimization.max-concurrent-jobs=2"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OptimizationApiTests {

    private static final String M = "/api/management/v1/optimization";
    private static final String PREFIX = "paimon";
    private static final String DB = "opt_basic";
    private static final String TABLE = "t1";
    private static final String TABLE2 = "t2";
    private static final String BATCH_DB = "opt_batch";

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

    private final JsonMapper mapper = JsonMapper.builder().build();

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
        public JobState state(String namespace, String resource) {
            return states.getOrDefault(resource, JobState.UNKNOWN);
        }

        @Override
        public void cancel(String namespace, String resource) {
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
        schema.getOptions().put("path", "file:///tmp/lakehouse-rest-server-tests/opt-format-dir");
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
     * <p>出批侧那道配额检查（{@code OptimizationBatchService}）只拦「自动调度造批次」——
     * 手动「立即优化」与重试都是直接建批次，压根不经过那条路。少了提交侧这道闸门，
     * 运维连点几次就能在集群里堆起同等数量的 Spark 任务，而这条上限本来就是
     * 「给集群留余量」用的（每个批次一个驱动），绕过它等于上限不存在。
     */
    @Test
    @Order(9)
    void concurrencyLimitAlsoGatesManuallyTriggeredBatches() throws Exception {
        StubSubmitter.INSTANCE.reset();
        clearActiveJobs();
        parkPolicies();

        for (int i = 0; i < 3; i++) {
            json(201, "POST", runPath(DB, TABLE), "{\"actions\":[\"compact\"]}");
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

    // ------------------------------------------------------------------ 工具

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
