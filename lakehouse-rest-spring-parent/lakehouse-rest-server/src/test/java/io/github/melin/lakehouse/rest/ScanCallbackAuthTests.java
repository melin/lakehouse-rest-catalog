package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

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
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code lakehouse.rest.auth.enabled=true} 时两个作业回调端点仍然可用
 * （设计见 {@code docs/sensitive-data-design.md} §5.6.5 与 {@code WebConfig} 的豁免清单）。
 *
 * <p><b>这条用例是补一个真环境才暴露的洞。</b>作业回调的凭据是随 Job 命令行下发的
 * <b>回调令牌</b>（{@code X-Sensitivity-Scan-Token}，作用域 = 这一次扫描），它<b>不是</b>
 * 访问令牌，认证链里没有它的位置。而回调路径落在 {@code /api/management/v1/**} 之下，
 * 整体鉴权开着时 {@code BearerAuthInterceptor} 会把每一条回调拦成 401——服务端的表现是
 * 「作业跑完了、一条结果也没回来」，最后按 Job 状态收尾成 FAILED，日志里只有一句
 * 「执行侧扫描作业失败」。单元测试默认关着鉴权，所以这条一直没被发现；
 * 真集群 e2e（{@code scripts/e2e-sensitivity-scan-k8s.sh}）第一次跑就撞上了。
 *
 * <p>断言分成三组，缺一组都会让修法走偏：
 *
 * <ul>
 *   <li><b>只带扫描令牌、不带 {@code Authorization} → 200</b>，这是作业的真实处境；</li>
 *   <li><b>什么令牌都不带 → 403</b>（不是 401）。豁免之后请求进到控制器，
 *       令牌校验是唯一闸门；仍然回 401 说明豁免只做了一半，作业还是进不来；</li>
 *   <li><b>带错令牌 → 403</b>：豁免不能变成「这几个路径公开」；</li>
 *   <li><b>同一个服务端上，别的管理端点不带 {@code Authorization} 仍然是 401</b>：
 *       证明这次改的是四条路径，不是把整段 {@code /api/management/v1/**} 的鉴权关掉；</li>
 *   <li><b>同样两条优化批次的回调路径也不是 401</b>：四条路径是同一个决定，
 *       只验敏感识别那两条，写错 {@code items/*} 这类通配符照样能全绿。</li>
 * </ul>
 */
@SpringBootTest(properties = {
        // 只有这一条与本类要验的东西有关：整体鉴权开着
        "lakehouse.rest.auth.enabled=true",
        "lakehouse.rest.auth.tokens[0]=root",
        "lakehouse.rest.sensitivity.seed-builtin-types=true",
        // 状态机由用例手工推进，不让后台节拍把在跑的扫描收尾掉
        "lakehouse.rest.optimization.scheduler-enabled=false"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScanCallbackAuthTests {

    private static final String M = "/api/management/v1";
    private static final String PREFIX = "paimon";
    private static final String DB = "callback_auth_db";
    private static final String TABLE = "auth_a";

    /** 桩提交器交回来的回调令牌：服务端拿它算哈希，用例拿它当请求头。 */
    private static final String CALLBACK_TOKEN = "callback-token-for-auth-tests";

    private static final String TOKEN_HEADER = "X-Sensitivity-Scan-Token";
    private static final String BEARER = "Bearer root";

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

    private boolean createdCluster;

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

    @Test
    void scanTokenAloneIsEnoughForTheCallbackEndpoints() throws Exception {
        createTable();
        givenDispatchableCluster();

        JsonNode accepted = read(202, HttpMethod.POST, M + "/sensitivity-scans", """
                {"catalog": "paimon", "scope": "TABLE", "database": "callback_auth_db",
                 "table": "auth_a", "mode": "SAMPLED", "execution": "SPARK"}
                """, null, BEARER);
        String scanId = accepted.get("scanId").asString();
        dispatcher.submitPending();

        // 作业的真实处境：没有任何 Authorization，只有一个作用域等于本次扫描的令牌
        JsonNode spec = read(200, HttpMethod.GET,
                M + "/sensitivity-scans/" + scanId + "/submission", null, CALLBACK_TOKEN, null);
        assertEquals(TABLE, spec.get("tables").get(0).get("table").asString());

        // 不带任何令牌：豁免之后进到控制器才被挡，所以是 403 而不是 401
        assertEquals(403, status(HttpMethod.GET, M + "/sensitivity-scans/" + scanId + "/submission",
                        null, null, null),
                "豁免不等于公开：控制器里的令牌校验仍要挡住没有凭据的请求");

        assertEquals(403, status(HttpMethod.GET, M + "/sensitivity-scans/" + scanId + "/submission",
                        null, "not-this-scan", null),
                "错令牌必须 403：否则任何一次扫描的令牌都能读别人的清单");

        // 逐表回调同理（204 是成功码，这里只看能不能进来）
        assertEquals(204, status(HttpMethod.POST, M + "/sensitivity-scans/" + scanId + "/items",
                        """
                        {"database": "callback_auth_db", "table": "auth_a", "status": "SCANNED",
                         "columnsScanned": 2, "findings": []}
                        """, CALLBACK_TOKEN, null),
                "回调要能被作业调用到，否则计数永远是 0");

        // 对照组：同一条路径前缀下的**非回调**端点仍然要 Bearer
        assertEquals(401, status(HttpMethod.GET, M + "/sensitivity-scans?catalog=" + PREFIX,
                        null, null, null),
                "这次只豁免了四条回调路径，不能顺带把整个管理面变成匿名可读");

        // 优化批次的回调面是同一个决定（两个作业、四条路径），不能只验敏感识别那两条。
        // 故意用一个不存在的批次：没有 Authorization 还能走进控制器，就说明认证层没拦它
        // ——拿到的 404 是「库里没有这个批次」，不是 401「你是谁」。
        assertNotEquals(401, status(HttpMethod.GET,
                        M + "/optimization/jobs/no-such-job/submission", null, null, null),
                "优化批次的提交清单回调同样没有主体身份；被认证层拦成 401 等于那条路还是断的");
        assertNotEquals(401, status(HttpMethod.POST,
                        M + "/optimization/jobs/no-such-job/items/no-such-item", """
                        {"status": "SUCCEEDED", "metrics": {}, "errorMessage": null}
                        """, null, null),
                "逐项回调同理");
        assertEquals(401, status(HttpMethod.GET, M + "/optimization/jobs", null, null, null),
                "非回调的批次端点仍要 Bearer");
    }

    @AfterAll
    void removeFixtures() {
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

    // ------------------------------------------------------------------ 夹具

    private String catalogId() {
        return databaseService.require(PREFIX, DB).getCatalogId();
    }

    private void createTable() {
        if (!databaseService.list(PREFIX, null, null, null, null).databases().contains(DB)) {
            databaseService.create(PREFIX, new DatabaseDtos.CreateDatabaseRequest(DB, Map.of()));
        }
        String databaseId = databaseService.require(PREFIX, DB).getId();
        String catalogId = databaseService.require(PREFIX, DB).getCatalogId();
        if (tableRepository.findByCatalogIdAndDatabaseIdAndName(catalogId, databaseId, TABLE).isPresent()) {
            return;
        }
        TypeDtos.Schema schema = new TypeDtos.Schema();
        String[][] columns = {{"id", "BIGINT NOT NULL"}, {"id_card", "VARCHAR(18)"}};
        int index = 0;
        for (String[] column : columns) {
            TypeDtos.DataField field = new TypeDtos.DataField();
            field.setId(index);
            field.setName(column[0]);
            field.setType(column[1]);
            schema.getFields().add(field);
            if (index == 0) {
                schema.getPrimaryKeys().add(field.getName());
            }
            index++;
        }
        schema.getOptions().put("bucket", "1");
        tableService.create(PREFIX, DB, new TableDtos.CreateTableRequest(
                new CommonDtos.Identifier(DB, TABLE), schema));
    }

    private void givenDispatchableCluster() {
        String catalogId = catalogId();
        if (clusterRepository.findByCatalogId(catalogId).isPresent()) {
            return;
        }
        CatalogExecutionClusterEntity entity = new CatalogExecutionClusterEntity();
        entity.setId("ec-" + catalogId);
        entity.setCatalogId(catalogId);
        entity.setEnabled(true);
        entity.setClusterType("SELF_HOSTED");
        entity.setApiServer("https://127.0.0.1:6443");
        entity.setContextName("test");
        entity.setConfigHash("test-config-hash");
        entity.setNamespace("scan-ns");
        entity.setImage("lakehouse-rest-optimizer:test");
        entity.setCallbackBaseUrl("http://localhost:8080");
        entity.setMaxConcurrentJobs(1);
        entity.setMaxScanTablesPerJob(200);
        entity.setScanJobTimeoutSeconds(3600);
        clusterRepository.save(entity);
        createdCluster = true;
    }

    // ------------------------------------------------------------------ HTTP 工具

    /** 三个「凭据放着的位置」互斥地组成一个请求：Bearer（主体）、扫描令牌（作业）、都没有。 */
    private JsonNode read(int expected, HttpMethod method, String path, String body,
                          String scanToken, String bearer) throws Exception {
        MvcResult result = mockMvc.perform(builder(method, path, body, scanToken, bearer)).andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> method + " " + path + " unexpected status, body=" + responseBody);
        return responseBody == null || responseBody.isBlank()
                ? mapper.createObjectNode() : mapper.readTree(responseBody);
    }

    private int status(HttpMethod method, String path, String body, String scanToken, String bearer)
            throws Exception {
        return mockMvc.perform(builder(method, path, body, scanToken, bearer)).andReturn()
                .getResponse().getStatus();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder(
            HttpMethod method, String path, String body, String scanToken, String bearer) {
        var builder = request(method, path).contentType(MediaType.APPLICATION_JSON);
        if (body != null) {
            builder.content(body);
        }
        if (scanToken != null) {
            builder.header(TOKEN_HEADER, scanToken);
        }
        if (bearer != null) {
            builder.header("Authorization", bearer);
        }
        return builder;
    }
}
