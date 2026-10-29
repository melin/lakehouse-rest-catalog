package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

/**
 * 血缘被显式关掉时的行为：<b>只锁写入口，读照常</b>。
 *
 * <p>关掉之后采集器拿 403、图照常能查——这条不变式与「缺省是开还是关」无关，
 * 因此本类不靠缺省值，而是显式写 {@code enabled=false}。缺省值本身由
 * {@link #codeDefaultIsOn()} 单独盯住（缺省值曾是这套开关最容易被改坏的地方：
 * yml 的值会盖住代码默认，行为用例绿了不等于默认值对）。
 *
 * <p>为什么不跟着缺省值走：缺省值改过一次（false → true）。若这里仍依赖缺省，
 * 改默认值就会让本类从「验关闭行为」悄悄变成「验开启行为」——用例还绿着，
 * 但已经什么都不验了。
 */
@SpringBootTest(properties = "lakehouse.rest.lineage.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LineageDisabledTests {

    private static final String M = "/api/management/v1/lineage";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ingestIsForbiddenButReadStillWorks() throws Exception {
        MvcResult ingest = mockMvc.perform(post(M + "/runs")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"runs\":[{\"runId\":\"off-1\",\"engine\":\"SPARK\",\"engineVersion\":\"3.5\","
                        + "\"statementType\":\"INSERT\",\"targets\":[{\"table\":\"paimon.dw.off\"}]}]}"))
                .andReturn();
        String body = ingest.getResponse().getContentAsString();
        assertEquals(403, ingest.getResponse().getStatus(), "关闭时上报该被拒：" + body);

        MvcResult read = mockMvc.perform(get(M + "/tables").param("ref", "paimon.dw.off")).andReturn();
        assertEquals(200, read.getResponse().getStatus());
        // 读得到、只是空的：这份响应正好是「还没采集到」那个空态的数据来源
        assertEquals("paimon.dw.off",
                mapper.readTree(read.getResponse().getContentAsString()).get("root").asString());
    }

    /**
     * 代码里的缺省值本身也要盯住。
     *
     * <p>上面那条用例是显式关的，验的是**关闭时的行为**；它挡不住「缺省值被改掉」——
     * 这正是历史上出过的事（yml 与代码同时写成 true，注释却写着 false，于是用例红了
     * 也没人知道该改哪边）。所以缺省值单独断言，且**与 yml 里的值分开看**：
     * 这里 new 一个空对象，读的是纯代码默认。
     */
    @Test
    void codeDefaultIsOn() {
        assertTrue(new RestServerProperties().getLineage().isEnabled(),
                "Lineage.enabled 的缺省值是 true（谁按规范上报都收）；"
                        + "不采集的部署用 --lakehouse.rest.lineage.enabled=false 关掉");
    }
}
