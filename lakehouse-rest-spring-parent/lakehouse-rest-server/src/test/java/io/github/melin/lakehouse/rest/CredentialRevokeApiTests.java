package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 「凭据作废」入口（设计稿 G10 / P2-3）的端到端验收。
 *
 * <p>这一层要证明的只有三件事，其余都是它们的推论：
 *
 * <ol>
 *   <li><b>作废之后真的会重新签发。</b>两次读表之间的那张票不同，且不是碰巧
 *       不同——{@code file://} 档的票整份是现造的随机值，缓存命中时两次必定逐字相同；</li>
 *   <li><b>回执不撒谎。</b>{@code alreadyIssuedStillWork} 恒为 {@code true}，
 *       {@code note} 说清「已发出去的票到期前仍然有效」。这个端点是全链路里最容易被
 *       当成「紧急撤销按钮」的一个，而云侧根本没有按 token 吊销的 API；</li>
 *   <li><b>可以重复调用。</b>第二次清到 0 条也是 200——它按 POST 实现（而不是 DELETE）
 *       正是这个理由：作废的是缓存，不是资源。</li>
 * </ol>
 *
 * <p>用 {@code FILE} 存储而不是 S3：这条链路上没有任何东西与存储类型有关，
 * 而对象存储会让每个用例真的去连一次端点（本机没有云，只能等到超时）。
 * 用本地文件系统跑，一个用例不到一秒，测的还是同一段代码。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.storage.credential-secret-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "spring.datasource.url=jdbc:h2:mem:paimon-cred-revoke;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CredentialRevokeApiTests {

    private static final String M = "/api/management/v1/catalogs";

    private static final String TABLE = "orders";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JsonMapper mapper;

    @Autowired
    private CatalogRepository catalogRepository;

    private final List<String> created = new ArrayList<>();

    @AfterEach
    void dropCatalogs() throws Exception {
        for (String prefix : created) {
            if (catalogRepository.findByPrefix(prefix).isPresent()) {
                json(204, "DELETE", M + "/" + prefix, null);
            }
        }
        created.clear();
    }

    @Test
    void revokingDropsTheCachedTicketSoTheNextReadMintsANewOne() throws Exception {
        String prefix = "revoke-file";
        json(201, "POST", M, """
                {"catalog":{"type":"INTERNAL","name":"%s","properties":{"owner":"qa"},
                 "storageConfigInfo":{"storageType":"FILE",
                                      "allowedLocations":["file:///tmp/lakehouse-cred-revoke/%s"]}}}
                """.formatted(prefix, prefix));
        created.add(prefix);
        seedTable(prefix);

        JsonNode first = token(prefix);
        JsonNode second = token(prefix);
        assertEquals(first.get("accessKeySecret").asString(), second.get("accessKeySecret").asString(),
                "缓存期内的两次读表必须拿到同一张票（否则下面的「不同」证明不了任何事）");

        JsonNode receipt = json(200, "POST", M + "/" + prefix + "/credentials/revoke", null);
        assertEquals(prefix, receipt.get("catalog").asString());
        assertEquals(1, receipt.get("invalidatedEntries").asInt(),
                "这张表的票该被清掉（服务端 catalog 级票此刻还没被用过，所以只有 1 条）");
        assertTrue(receipt.get("alreadyIssuedStillWork").asBoolean(),
                "回执必须如实说明：已经发出去的票到期前仍然有效");
        assertTrue(receipt.get("note").asString().contains("stay valid"),
                receipt.get("note").asString());

        JsonNode third = token(prefix);
        assertNotEquals(second.get("accessKeySecret").asString(),
                third.get("accessKeySecret").asString(),
                "作废之后下一次读表必须重新签发");

        JsonNode again = json(200, "POST", M + "/" + prefix + "/credentials/revoke", null);
        assertEquals(1, again.get("invalidatedEntries").asInt(),
                "第二次作废清掉的是刚刚那张新票；清到 0 也是正常的，接口照常 200");
    }

    /** 不存在的 catalog：404，而不是悄悄清一堆无关条目。 */
    @Test
    void revokingAnUnknownCatalogIs404() throws Exception {
        json(404, "POST", M + "/no-such-catalog/credentials/revoke", null);
    }

    private JsonNode token(String prefix) throws Exception {
        return json(200, "GET",
                "/v1/" + prefix + "/databases/cred_db/tables/" + TABLE + "/token", null).get("token");
    }

    private void seedTable(String prefix) throws Exception {
        json(200, "POST", "/v1/" + prefix + "/databases", "{\"name\":\"cred_db\",\"options\":{}}");
        json(200, "POST", "/v1/" + prefix + "/databases/cred_db/tables", """
                {"identifier":{"database":"cred_db","object":"%s"},
                 "schema":{"fields":[{"id":0,"name":"id","type":"BIGINT NOT NULL"}],
                           "primaryKeys":["id"],"options":{"bucket":"1"}}}
                """.formatted(TABLE));
    }

    private JsonNode json(int expected, String method, String path, String body) throws Exception {
        MockHttpServletRequestBuilder request = switch (method) {
            case "GET" -> get(path);
            case "DELETE" -> delete(path);
            case "POST" -> post(path);
            default -> throw new IllegalArgumentException(method);
        };
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        var result = mockMvc.perform(request).andReturn();
        String content = result.getResponse().getContentAsString();
        assertEquals(expected, result.getResponse().getStatus(),
                () -> "unexpected status, body=" + content);
        return content == null || content.isBlank()
                ? mapper.readTree("{}") : mapper.readTree(content);
    }
}
