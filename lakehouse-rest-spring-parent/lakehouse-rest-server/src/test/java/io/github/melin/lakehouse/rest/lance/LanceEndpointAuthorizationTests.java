package io.github.melin.lakehouse.rest.lance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URI;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Lance 面（{@code /lance/v1/**}）的授权，在 {@code authorization.enabled=true} 下。
 *
 * <p><b>这一类存在的唯一理由是一个很容易漏的洞</b>：{@code /lance/**} 不在 {@code /v1/**}
 * 之下，认证与授权两条拦截器都是「按前缀清单」挂的。少写那一行，整个 Lance 面就是
 * 匿名可调，而**没有任何既有测试会发现**——那些测试全都在 {@code /v1/} 上。
 * 因此这里断言的是「无权时是 403，不是 200」，而不是「有权时能通」。
 *
 * <p>另一件被钉住的事：**未登记路径必须拒绝**（失败关闭）。Lance 面有一个
 * {@code /lance/v1/**} 的兜底控制器回答 406，如果授权先放行，
 * 「乱敲一个路径」就会拿到 406 而不是 403——那等于任何人都能探测出
 * 「这个服务端实现了哪些 operation」，虽然信息量不大，但这是关闭方向上的松动。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.authorization.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:paimon-lance-authz;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LanceEndpointAuthorizationTests {

    private static final String M = "/api/management/v1";
    private static final String ROOT = "root";
    private static final String BOB = "lance-bob";
    private static final String CATALOG = "lance-authz-test";
    private static final String SCHEMA = "sales";
    private static final String NS_ID = CATALOG + "%24" + SCHEMA;
    private static final String WAREHOUSE =
            "file://" + System.getProperty("java.io.tmpdir") + "/lakehouse-rest-server-tests/lance-authz-test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private io.github.melin.lakehouse.rest.service.AuthorizationService authorizationService;

    @org.junit.jupiter.api.Test
    @Order(0)
    void authorizationIsReallyEnabledInThisContext() {
        org.junit.jupiter.api.Assertions.assertTrue(authorizationService.enabled(),
                "本类的全部断言都以授权开启为前提");
    }

    private final JsonMapper mapper = JsonMapper.builder().build();

    private JsonNode call(int expected, MockHttpServletRequestBuilder request, String principal)
            throws Exception {
        if (principal != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + principal);
        }
        MvcResult result = mockMvc.perform(request).andReturn();
        String body = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status as " + principal + ", body=" + body);
        return body == null || body.isBlank()
                ? JsonNodeFactory.instance.objectNode()
                : mapper.readTree(body);
    }

    private JsonNode as(String principal, int expected, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(URI.create(path));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return call(expected, request, principal);
    }

    @Test
    @Order(1)
    void serviceAdminBuildsTheFixture() throws Exception {
        JsonNode created = as(ROOT, 201, M + "/catalogs", """
                {"catalog":{"type":"INTERNAL","name":"%s","catalogFormat":"LANCE","properties":{},
                 "storageConfigInfo":{"storageType":"FILE","allowedLocations":["%s"]}}}
                """.formatted(CATALOG, WAREHOUSE));
        assertEquals("LANCE", created.get("catalogFormat").asString());
        as(ROOT, 200, "/lance/v1/namespace/" + NS_ID + "/create", "{\"properties\":{}}");
    }

    @Test
    @Order(2)
    void unauthorizedCallersAreRejected() throws Exception {
        // 匿名：认证关闭时它的主体名是 anonymous，授权开启后必须被拒——
        // 这条断言就是「/lance/** 有没有进拦截器」那一行的守卫
        as(null, 403, "/lance/v1/namespace/" + NS_ID + "/describe", null);
        as(BOB, 403, "/lance/v1/namespace/" + NS_ID + "/describe", null);
        as(BOB, 403, "/lance/v1/namespace/" + NS_ID + "/table/list", null);
    }

    @Test
    @Order(3)
    void unregisteredPathsAreRejectedToo() throws Exception {
        // 失败关闭：映射表里没有的路径连服务管理员也过不去，
        // 而不是落到 /lance/v1/** 的兜底控制器上拿一个 406
        as(ROOT, 403, "/lance/v1/widget/" + NS_ID + "/describe", null);
        as(ROOT, 403, "/lance/v1/namespace/" + NS_ID, null);
        as(BOB, 403, "/lance/v1/widget/" + NS_ID + "/describe", null);
    }

    @Test
    @Order(4)
    void serviceAdminSeesTheNamespaceAndUnsupportedOperationsStayUnsupported() throws Exception {
        JsonNode described = as(ROOT, 200, "/lance/v1/namespace/" + NS_ID + "/describe", null);
        assertEquals(CATALOG + "$" + SCHEMA, described.get("id").asString());
        // 「不支持」与「无权」是两层：授权过了，能力边界仍然要说清楚
        JsonNode failure = as(ROOT, 406, "/lance/v1/table/" + NS_ID + "%24orders/insert", "{}");
        assertEquals(0, failure.get("code").asInt());
    }
}
