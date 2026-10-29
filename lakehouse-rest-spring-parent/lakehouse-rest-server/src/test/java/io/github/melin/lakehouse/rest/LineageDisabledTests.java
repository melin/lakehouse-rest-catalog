package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 血缘默认关闭时的行为：<b>只锁写入口，读照常</b>。
 *
 * <p>为什么默认关：采集器一上线就会被 Spark 任务按批调用，而「上线才发现每个作业都在
 * 往一张新表里写」不该是默认结果。反过来，读端点不跟着关——图是只读的，
 * 关掉它只会让「开关忘了打开」表现为「页签一直空着」，而空态无法区分这两种情况。
 *
 * <p>这个类刻意不写 {@code @TestPropertySource}：它验的就是**缺省值**。
 */
@SpringBootTest
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
     * <p>上面那条用例验的是**这份配置的生效值**——`application.yml` 里也写着 `false`，
     * 于是它把「代码里的缺省值被改成 `true`」这件事挡住了：两者只要有一个是 false，
     * 行为就还是关的。实际发生过一次（yml 与代码同时写成 true，注释写着 false），
     * 现象是这条用例红了而没人知道该改哪边。所以缺省值单独断言。
     */
    @Test
    void codeDefaultIsOff() {
        assertFalse(new RestServerProperties().getLineage().isEnabled(),
                "Lineage.enabled 的缺省值必须是 false：要采集的部署用启动参数显式打开");
    }
}
