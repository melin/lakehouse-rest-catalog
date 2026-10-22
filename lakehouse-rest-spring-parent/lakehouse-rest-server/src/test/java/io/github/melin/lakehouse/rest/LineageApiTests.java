package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.dto.LineageDtos.Budget;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnEdgeGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnEdgeRow;
import io.github.melin.lakehouse.rest.dto.LineageDtos.ColumnGraph;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunDetail;
import io.github.melin.lakehouse.rest.dto.LineageDtos.RunList;
import io.github.melin.lakehouse.rest.dto.LineageDtos.TableEdge;
import io.github.melin.lakehouse.rest.dto.LineageDtos.TableGraph;
import io.github.melin.lakehouse.rest.service.LineageService;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * 血缘的集成测试：上报、幂等、图查询、列映射、预算与保留清理。
 *
 * <p>用 HTTP 层（MockMvc）验端点与状态码，用服务层读结果（{@code tables} / {@code columns}）
 * ——图是一个嵌套结构，直接在 JSON 里挑字段断言会把用例写成「解析器测试」；
 * 而这里的重点是**边表怎么被写、怎么被合并**。
 *
 * <p>三个上限在 {@code @TestPropertySource} 里调小（每批 5 条、每个目标 2 列、节点预算 4）：
 * 这样「超上限降级」「超预算截断」不用真的造几百列/几百张表。
 *
 * <p>不建真实的库与表：{@code describe} 对「目录里查不到」的表给 {@code stale=true}
 * 而不是报错（那正是它存在的理由），因此这些用例不需要元数据就能跑——
 * 血缘本来就是「边先到、表可能已经没了」的东西。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "lakehouse.rest.lineage.enabled=true",
        "lakehouse.rest.lineage.max-runs-per-batch=5",
        "lakehouse.rest.lineage.max-columns-per-target=2",
        "lakehouse.rest.lineage.max-nodes=4",
        "lakehouse.rest.lineage.max-depth=3"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LineageApiTests {

    private static final String M = "/api/management/v1/lineage";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LineageService lineageService;

    // ------------------------------------------------------------------ 上报

    @Test
    @Order(1)
    void ingestStoresTableAndColumnEdges() throws Exception {
        JsonNode result = ingest(run("run-1", "paimon.dw.t3", "paimon.dw.t1", 2));
        assertEquals(1, result.get("accepted").asInt());
        assertEquals(0, result.get("deduplicated").asInt());
        assertEquals(0, result.get("truncatedColumns").asInt());

        TableGraph graph = lineageService.tables("paimon.dw.t3", "both", 1, null, 100, true).full();
        assertEquals("paimon.dw.t3", graph.root());
        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
        TableEdge edge = graph.edges().get(0);
        assertEquals("paimon.dw.t1", edge.source());
        assertEquals("paimon.dw.t3", edge.target());
        assertEquals("SPARK", edge.engine());
        assertEquals("APPEND", edge.writeMode());
        assertEquals(1, edge.runCount());
        // columnCount=1 才聚合；这里是 2 条列映射
        assertEquals(2, edge.columnCount());
        // 目录里没有这张 catalog：节点标 stale 而不是消失
        assertTrue(graph.nodes().stream().filter(node -> node.ref().equals("paimon.dw.t3"))
                .findFirst().orElseThrow().stale());
    }

    @Test
    @Order(2)
    void duplicateRunIsIdempotentAndDoesNotDoubleCount() throws Exception {
        JsonNode again = ingest(run("run-1", "paimon.dw.t3", "paimon.dw.t1", 2));
        assertEquals(0, again.get("accepted").asInt());
        assertEquals(1, again.get("deduplicated").asInt());
        // 幂等命中不能把 runCount 加上去：同一次执行被重试了两次，图上仍是一次
        assertEquals(1, lineageService.tables("paimon.dw.t3", "upstream", 1, null, 100, false).full()
                .edges().get(0).runCount());
    }

    @Test
    @Order(3)
    void secondRunAccumulatesOnTheSameEdge() throws Exception {
        ingest(run("run-2", "paimon.dw.t3", "paimon.dw.t1", 1));
        TableEdge edge = lineageService.tables("paimon.dw.t3", "upstream", 1, null, 100, false).full()
                .edges().get(0);
        assertEquals(2, edge.runCount());
        // 列级也是 upsert：第一次的 2 条 + 第二次的 1 条（同一列重复只算一次）
        assertEquals(2, lineageService.tables("paimon.dw.t3", "upstream", 1, null, 100, true).full()
                .edges().get(0).columnCount());
    }

    @Test
    @Order(4)
    void wideTableIsDowngradedToTableLevel() throws Exception {
        // 3 列 > max-columns-per-target(2)：整张表的列映射被丢弃，表级边照常入
        JsonNode result = ingest(run("run-wide", "paimon.dw.t4", "paimon.dw.t1", 3));
        assertEquals(3, result.get("truncatedColumns").asInt());
        TableEdge edge = lineageService.tables("paimon.dw.t4", "upstream", 1, null, 100, true).full()
                .edges().get(0);
        assertEquals(0, edge.columnCount());
        // run 明细里标着「这次上报的列被截断」，否则用户会以为是采集器漏了列
        RunDetail detail = lineageService.run("run-wide");
        assertTrue(detail.summary().truncatedColumns());
        assertTrue(lineageService.columns("paimon.dw.t4", "upstream", null).columns().isEmpty());
    }

    @Test
    @Order(5)
    void unknownEngineAndStatementTypeAreRejected() throws Exception {
        json(400, "POST", M + "/runs",
                "{\"runs\":[{\"runId\":\"bad-1\",\"engine\":\"FLINK\",\"engineVersion\":\"1.19\","
                        + "\"statementType\":\"INSERT\",\"targets\":[{\"table\":\"paimon.dw.t9\"}]}]}");
        json(400, "POST", M + "/runs",
                "{\"runs\":[{\"runId\":\"bad-2\",\"engine\":\"SPARK\",\"engineVersion\":\"3.5\","
                        + "\"statementType\":\"UPDATE\",\"targets\":[{\"table\":\"paimon.dw.t9\"}]}]}");
        // 没有目标：一条没有写目标的 run 不是血缘
        json(400, "POST", M + "/runs",
                "{\"runs\":[{\"runId\":\"bad-3\",\"engine\":\"SPARK\",\"engineVersion\":\"3.5\","
                        + "\"statementType\":\"INSERT\",\"targets\":[]}]}");
        // 解析不出 catalog 也不带 ~ 前缀
        json(400, "POST", M + "/runs",
                "{\"runs\":[{\"runId\":\"bad-4\",\"engine\":\"SPARK\",\"engineVersion\":\"3.5\","
                        + "\"statementType\":\"INSERT\",\"targets\":[{\"table\":\"t9\"}]}]}");
    }

    // ------------------------------------------------------------------ 查询

    @Test
    @Order(6)
    void columnGraphGroupsByTargetColumn() throws Exception {
        ColumnGraph graph = lineageService.columns("paimon.dw.t3", "upstream", null);
        assertEquals("paimon.dw.t3", graph.ref());
        assertEquals(2, graph.columns().size());
        // 分组的键是目标列：人看血缘时想的是「我这个字段从哪来」
        assertTrue(graph.columns().stream().anyMatch(group -> group.column().equals("id")));
        assertTrue(graph.columns().stream()
                .flatMap(group -> group.upstream().stream())
                .allMatch(link -> link.table().equals("paimon.dw.t1")));
    }

    @Test
    @Order(7)
    void budgetReportsRealNeighbourCountsAndDepthIsCapped() throws Exception {
        // t3 的上游有 t1；下游还没有
        Budget budget = lineageService.tables("paimon.dw.t3", "both", 1, null, 100, false).full().budget();
        assertEquals(1, budget.upstream());
        assertEquals(0, budget.downstream());
        assertFalse(budget.truncated());

        json(400, "GET", M + "/tables?ref=paimon.dw.t3&depth=0", null);
        json(400, "GET", M + "/tables?ref=paimon.dw.t3&depth=99", null);
        json(400, "GET", M + "/tables?ref=paimon.dw.t3&maxNodes=5000", null);
        json(400, "GET", M + "/tables", null);
    }

    @Test
    @Order(8)
    void compactShapeAndEtag() throws Exception {
        MvcResult first = mockMvc.perform(get(M + "/tables")
                .param("ref", "paimon.dw.t3")
                .param("direction", "both")
                .param("compact", "1")
                .param("columnCount", "1")).andReturn();
        assertEquals(200, first.getResponse().getStatus());
        JsonNode body = mapper.readTree(first.getResponse().getContentAsString());
        // 紧凑形状：节点是 5 元数组、边是 8 元数组，root 是下标
        assertEquals(0, body.get("root").asInt());
        JsonNode node = body.get("n").get(0);
        assertEquals("paimon.dw.t3", node.get(0).asString(), "root 必须是第一个节点");
        assertFalse(node.get(4).isNull(), "第 5 位是 stale，图靠它标灰");
        JsonNode edge = body.get("e").get(0);
        assertEquals("SPARK", edge.get(4).asString());
        assertEquals(2, edge.get(7).asInt(), "columnCount 在最后一位");

        String etag = first.getResponse().getHeader("ETag");
        assertNotNull(etag);
        // 条件请求：同一份图的 ETag 命中就回 304，省掉整份序列化。
        // 参数必须与第一次**逐字相同**——ETag 是对这一份图的摘要，
        // 换了 direction 就是另一张图（边数不同），命中不了是正确行为
        MvcResult second = mockMvc.perform(get(M + "/tables")
                .param("ref", "paimon.dw.t3")
                .param("direction", "both")
                .param("compact", "1")
                .param("columnCount", "1")
                .header("If-None-Match", etag)).andReturn();
        assertEquals(304, second.getResponse().getStatus());
        assertEquals(etag, second.getResponse().getHeader("ETag"));
    }

    @Test
    @Order(9)
    void runHistoryFiltersByTableAndPaginates() throws Exception {
        ingest(run("run-p1", "paimon.dw.t5", "paimon.dw.t1", 1));
        ingest(run("run-p2", "paimon.dw.t5", "paimon.dw.t1", 1));
        // 两条都落在 t5：按表过滤必须查得到；t3 的历史不该混进来
        assertEquals(2, lineageService.runs("paimon.dw.t5", null, null, 10, null).runs().size());
        assertTrue(lineageService.runs("paimon.dw.t3", null, null, 10, null).runs().stream()
                .allMatch(summary -> summary.targets().contains("paimon.dw.t3")));

        RunList first = lineageService.runs("paimon.dw.t5", null, null, 1, null);
        assertEquals(1, first.runs().size());
        assertNotNull(first.nextCursor(), "还有下一条时必须给游标");
        RunList second = lineageService.runs("paimon.dw.t5", null, null, 1, first.nextCursor());
        assertEquals(1, second.runs().size());
        assertNull(second.nextCursor(), "最后一条之后游标必须为空");
        // 两条不是同一条 run（复合游标按 finishedAt + id 翻页，同毫秒也不会漏）
        assertFalse(second.runs().get(0).runId().equals(first.runs().get(0).runId()));
        // 按表过滤：t5 的历史里不该混进 t3 的 run
        assertTrue(first.runs().get(0).targets().contains("paimon.dw.t5"));
        // 不存在的 runId → 404
        json(404, "GET", M + "/runs/no-such-run", null);
    }

    // ------------------------------------------------------------------ 清理

    @Test
    @Order(10)
    void purgeRemovesExpiredRunsButKeepsEdges() throws Exception {
        // 这一条的时间戳远早于保留期（90 天）
        ingest("{\"runs\":[{\"runId\":\"run-old\",\"engine\":\"SPARK\",\"engineVersion\":\"3.5\","
                + "\"statementType\":\"INSERT\",\"finishedAt\":1000,"
                + "\"targets\":[{\"table\":\"paimon.dw.t6\"}],"
                + "\"sources\":[{\"table\":\"paimon.dw.t1\"}]}]}");

        int purged = lineageService.purgeExpiredRuns(System.currentTimeMillis());
        assertTrue(purged >= 1);
        json(404, "GET", M + "/runs/run-old", null);
        // 边是状态、run 是事件：清掉历史不该让图少一条边
        assertEquals(1, lineageService.tables("paimon.dw.t6", "upstream", 1, null, 100, false).full()
                .edges().size());
    }

    @Test
    @Order(11)
    void droppingEdgesDoesNotLeaveDanglingNodes() throws Exception {
        lineageService.dropEdges("paimon.dw.t3");
        TableGraph graph = lineageService.tables("paimon.dw.t3", "both", 1, null, 100, true).full();
        assertTrue(graph.edges().isEmpty());
        // 只剩根自己：节点集合是从边表走出来的，边没了节点也就没了
        assertEquals(1, graph.nodes().size());
        assertTrue(lineageService.columns("paimon.dw.t3", "upstream", null).columns().isEmpty());
    }

    @Test
    @Order(12)
    void columnEdgesReturnsRowsForTheRequestedNodeSet() throws Exception {
        // refs 顺序即下标：t7=0、t1=1
        ingest(run("run-ce", "paimon.dw.t7", "paimon.dw.t1", 2));
        // t8 只有一条常量列（无源）：有行、无线
        ingest("{\"runs\":[{\"runId\":\"run-ce-const\",\"engine\":\"SPARK\",\"engineVersion\":\"3.5\","
                + "\"statementType\":\"INSERT\",\"writeMode\":\"APPEND\",\"mode\":\"BATCH\","
                + "\"targets\":[{\"table\":\"paimon.dw.t8\",\"columns\":[{"
                + "\"targetColumn\":\"dt\",\"kind\":\"CONSTANT\",\"expression\":\"'2026-10-22'\","
                + "\"sources\":[]}]}],\"sources\":[]}]}");

        // 两端都在集合里：两条列边按 (目标下标, 目标列) 排序
        ColumnEdgeGraph graph = lineageService.columnEdges(List.of("paimon.dw.t7", "paimon.dw.t1"));
        assertEquals(List.of("paimon.dw.t7", "paimon.dw.t1"), graph.refs());
        assertEquals(2, graph.edges().size());
        ColumnEdgeRow first = graph.edges().get(0);
        assertEquals(1, first.s(), "源是 t1，在 refs 里的下标是 1");
        assertEquals(0, first.t());
        assertEquals("id", first.tc());
        assertEquals("DIRECT", first.kind());

        // 源不在集合里 → 这条边不回；表级图上这条边仍在
        ColumnEdgeGraph trimmed = lineageService.columnEdges(List.of("paimon.dw.t7"));
        assertTrue(trimmed.edges().isEmpty());

        // 常量列（无源）也有一行：s = -1、sc 为空串
        ColumnEdgeGraph constants = lineageService.columnEdges(List.of("paimon.dw.t8"));
        assertEquals(1, constants.edges().size());
        assertEquals(-1, constants.edges().get(0).s());
        assertEquals("", constants.edges().get(0).sc());
        assertEquals("CONSTANT", constants.edges().get(0).kind());

        // 参数校验：缺参、空集合、超上限
        json(400, "GET", M + "/column-edges", null);
        json(400, "GET", M + "/column-edges?refs=", null);
        StringBuilder tooMany = new StringBuilder();
        for (int i = 0; i < 65; i += 1) {
            if (i > 0) {
                tooMany.append(',');
            }
            tooMany.append("paimon.dw.t").append(i);
        }
        json(400, "GET", M + "/column-edges?refs=" + tooMany, null);
    }

    // ------------------------------------------------------------------ 辅助

    /**
     * 一条 INSERT：目标表 `target`，源表 `source`，列映射数 `columns`。时间戳交给服务端取 now。
     *
     * <p>整批只有一条 run：多条 run 之间会共享边与列边，断言就得先算清楚幂等与累加，
     * 而那是别的用例在验的事。
     */
    private String run(String runId, String target, String source, int columns) {
        StringBuilder cols = new StringBuilder();
        String[] names = {"id", "name", "flag"};
        for (int i = 0; i < columns; i += 1) {
            if (i > 0) {
                cols.append(',');
            }
            cols.append("{\"targetColumn\":\"").append(names[i])
                    .append("\",\"kind\":\"DIRECT\",\"expression\":\"t1.").append(names[i])
                    .append("\",\"sources\":[{\"table\":\"").append(source)
                    .append("\",\"column\":\"").append(names[i]).append("\"}]}");
        }
        return "{\"runs\":[{\"runId\":\"" + runId + "\",\"engine\":\"SPARK\","
                + "\"engineVersion\":\"3.5.1\",\"statementType\":\"INSERT\",\"writeMode\":\"APPEND\","
                + "\"mode\":\"BATCH\",\"sqlHash\":\"h-" + runId + "\","
                + "\"sqlText\":\"INSERT INTO " + target + " SELECT * FROM " + source + "\","
                + "\"targets\":[{\"table\":\"" + target + "\",\"columns\":[" + cols + "]}],"
                + "\"sources\":[{\"table\":\"" + source + "\",\"kind\":\"TABLE\"}]}]}";
    }

    private JsonNode ingest(String body) throws Exception {
        return json(200, "POST", M + "/runs", body);
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = "GET".equals(method) ? get(path) : post(path);
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String responseBody = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> path + " unexpected status, body=" + responseBody);
        if (responseBody == null || responseBody.isBlank()) {
            return JsonNodeFactory.instance.objectNode();
        }
        return mapper.readTree(responseBody);
    }
}
