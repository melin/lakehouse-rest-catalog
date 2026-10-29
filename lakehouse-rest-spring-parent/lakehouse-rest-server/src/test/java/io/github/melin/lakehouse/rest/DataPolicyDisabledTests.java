package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * 数据策略被显式关掉时的行为：<b>只锁写入口，读照常</b>（§5.4）。
 *
 * <p>与血缘开关同一模式：把「功能没开」与「没建策略」分开。读侧（resolve、策略详情）
 * 如果也被关掉，引擎侧的 fail-closed 无法区分「故障」与「本来就没策略」；而写入口
 * 关掉只是「这个部署不开放治理」，不影响已经下发的策略继续执行。
 *
 * <p>本类不靠缺省值，而是显式写 {@code enabled=false}：缺省值是开，依赖缺省会让本类
 * 从「验关闭行为」悄悄变成「验开启行为」，用例还绿但已经什么都不验。
 * 缺省值本身由 {@link #codeDefaultIsOn()} 单独盯住。
 */
@SpringBootTest(properties = "lakehouse.rest.policy.enabled=false")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class DataPolicyDisabledTests {

    private static final String M = "/api/management/v1/policies";
    private static final String PATH = M + "/tables/paimon/policy_db/off_table";

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void writesAreForbiddenButReadsStillWork() throws Exception {
        MvcResult upsert = mockMvc.perform(put(PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rowFilter\": {\"op\": \"is_null\", \"operand\": {\"col\": \"dt\"}}}"))
                .andReturn();
        String upsertBody = upsert.getResponse().getContentAsString();
        assertEquals(403, upsert.getResponse().getStatus(), "关闭时写策略该被拒：" + upsertBody);
        assertTrue(upsertBody.contains("lakehouse.rest.policy.enabled"),
                "错误里要写出配置项，否则现象只是「保存不了」：" + upsertBody);

        MvcResult drop = mockMvc.perform(delete(PATH)).andReturn();
        assertEquals(403, drop.getResponse().getStatus(), "删除同样是写入口");

        // 读照常：这个类没有造表夹具，表不存在的 404 恰好证明读链路通到了业务层
        // （被开关锁住的话会先吃到 403，走不到「表不存在」这一步）
        MvcResult detail = mockMvc.perform(get(PATH)).andReturn();
        assertEquals(404, detail.getResponse().getStatus(), "读不该跟着关（404 = 表不存在，非 403）");

        // 引擎的解析通道照常：没策略回 NONE，引擎照常放行
        MvcResult resolve = mockMvc.perform(get(M + "/resolve")
                .param("catalog", "paimon").param("tables", "policy_db.off_table")).andReturn();
        assertEquals(200, resolve.getResponse().getStatus());
        assertEquals("NONE",
                mapper.readTree(resolve.getResponse().getContentAsString())
                        .get("policies").get(0).get("status").asString());
    }

    /**
     * 代码里的缺省值本身也要盯住（理由与 {@code LineageDisabledTests#codeDefaultIsOn} 相同）：
     * 上面那条用例是显式关的，验的是关闭时的行为，挡不住「缺省值被改掉」。
     */
    @Test
    void codeDefaultIsOn() {
        assertTrue(new RestServerProperties().getPolicy().isEnabled(),
                "DataPolicy.enabled 的缺省值是 true（写入口默认开放）；"
                        + "不开放治理的部署用 --lakehouse.rest.policy.enabled=false 关掉");
    }
}
