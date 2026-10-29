package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * 数据策略默认关闭时的行为：<b>只锁写入口，读照常</b>（§5.4）。
 *
 * <p>与血缘开关同一模式：把「功能没开」与「没建策略」分开。读侧（resolve、策略详情）
 * 如果也被关掉，引擎侧的 fail-closed 无法区分「故障」与「本来就没策略」；而写入口
 * 关掉只是「这个部署不开放治理」，不影响已经下发的策略继续执行。
 *
 * <p>这个类刻意不写 {@code @TestPropertySource}：它验的就是**缺省值**。
 */
@SpringBootTest
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
     * 代码里的缺省值本身也要盯住（理由与 {@code LineageDisabledTests#codeDefaultIsOff} 相同）：
     * yml 里也写着 false，上面那条用例因此验的是「生效值」，挡不住「缺省值被改成 true」。
     */
    @Test
    void codeDefaultIsOff() {
        assertFalse(new RestServerProperties().getPolicy().isEnabled(),
                "DataPolicy.enabled 的缺省值必须是 false：要开放的部署用启动参数显式打开");
    }
}
