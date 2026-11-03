package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityClaimSamples;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProviderProbe;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProviderProbeStep;
import io.github.melin.lakehouse.rest.service.IdentityProviderService;
import io.github.melin.lakehouse.rest.service.OidcClaimSampleStore;
import io.github.melin.lakehouse.rest.service.OidcService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

/**
 * 身份接入的三个只读端点（{@code /api/management/v1/identity-provider}）。
 *
 * <p>测试 profile 里 OIDC 是关的，因此这里钉住的是**「没配」这张脸**：
 * 配置回默认值与 `enabled=false`、探活把失败分类成「哪一步不行」、
 * claim 样例为空但结构齐备。真正配好 IdP 的那一档需要外部服务，
 * 由 `IdentityProviderServiceTests` 用手工构造的配置覆盖。
 *
 * <p>三件这里测不到但必须记得的事：非管理员访问会 403（授权链在
 * `AuthorizationTests` 一族里测）、配置里没有密钥字段（DTO 没有这个字段，
 * 编译期就是保证）、探活不返回任何令牌（同上）。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sso-idp;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class IdentityProviderApiTests {

    private static final String BASE = "/api/management/v1/identity-provider";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private OidcClaimSampleStore samples;

    @Autowired
    private IdentityProviderService service;

    @Test
    void readsEffectiveConfigurationWhenOidcIsOff() throws Exception {
        JsonNode body = call(200, get(BASE));

        assertFalse(body.get("enabled").asBoolean(), "测试 profile 没开 OIDC");
        // 供给侧的默认值：这几项决定了「登录之后会发生什么」，写错任何一项都是静默故障
        assertEquals("groups", body.get("groupClaim").asString());
        assertEquals("ON_LOGIN", body.get("syncMode").asString());
        assertTrue(body.get("autoProvision").asBoolean());
        assertFalse(body.get("allowAdoptLocal").asBoolean());
        assertEquals(0, body.get("defaultRoles").size());
        assertEquals(0, body.get("allowedEmailDomains").size());
        // null 字段被全局的 NON_NULL 策略省掉（与其余端点一致），因此「没配」= 键不存在
        assertNull(body.get("issuer"));
        assertNull(body.get("clientId"));
    }

    @Test
    void probeClassifiesEachStepEvenWhenDisabled() throws Exception {
        JsonNode body = call(200, post(BASE + "/probe"));

        assertEquals("FAIL", body.get("verdict").asString());
        JsonNode steps = body.get("steps");
        assertEquals(5, steps.size(), "五步固定：discovery / endpoints / jwks / mapping / provisioning");

        IdentityProviderProbe parsed = parseProbe(steps);
        assertEquals("FAIL", step(parsed, "discovery").status());
        assertTrue(step(parsed, "discovery").detail().contains("oidc.enabled"),
                "原因要说到具体是哪一项没配，不能只说「连不上」");
        // 发现文档取不到时 JWKS 无从下手：跳过而不是 FAIL，否则总判会被一个「本来就不该跑」
        // 的步骤拖成失败
        assertEquals("SKIPPED", step(parsed, "jwks").status());
        // 配置类步骤不因连接失败而跳过：IdP 连不上时也要能看见「redirect-uri 没配」
        assertTrue(step(parsed, "endpoints").detail().contains("redirect-uri"));
    }

    @Test
    void claimSamplesAreEmptyBeforeAnyoneLogsIn() throws Exception {
        samples.clear();
        JsonNode body = call(200, get(BASE + "/claim-samples"));

        assertEquals(0, body.get("samples").size());
        assertTrue(body.get("processLocal").asBoolean(),
                "样本只在进程内存里，多副本部署时要如实说出来");
    }

    @Test
    void claimSamplesShowTheShapeNotTheValues() {
        samples.clear();
        OidcService.OidcIdentity identity = new OidcService.OidcIdentity(
                "alice", "https://idp.example.com/realms/x", "a1b2c3d4-uuid", "alice@corp.example.com",
                "alice@corp.example.com", java.util.List.of("data-engineers", "ops", "qa"),
                true, 1_700_000_000_000L);
        samples.record(identity, "success");

        IdentityClaimSamples result = service.samples();
        assertEquals(1, result.samples().size());
        var sample = result.samples().get(0);
        assertEquals("alice", sample.principal());
        assertEquals(3, sample.groupCount());
        assertTrue(sample.groupsClaimPresent());
        assertEquals(3, sample.groups().size());
        // 打码：组名只留前三个字符，邮箱只留域名
        assertEquals("dat***", sample.groups().get(0));
        assertEquals("ali***@corp.example.com", sample.email());
        assertEquals("a1b***", sample.subject());
    }

    // ------------------------------------------------------------------ 工具

    private JsonNode call(int expected, MockHttpServletRequestBuilder request) throws Exception {
        var response = mockMvc.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.getContentAsString());
    }

    private static IdentityProviderProbe parseProbe(JsonNode steps) {
        var parsed = new java.util.ArrayList<IdentityProviderProbeStep>();
        for (JsonNode step : steps) {
            parsed.add(new IdentityProviderProbeStep(step.get("step").asString(),
                    step.get("status").asString(), step.get("detail").asString()));
        }
        return new IdentityProviderProbe(parsed, "");
    }

    private static IdentityProviderProbeStep step(IdentityProviderProbe probe, String name) {
        return probe.steps().stream()
                .filter(step -> step.step().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少步骤 " + name));
    }
}
