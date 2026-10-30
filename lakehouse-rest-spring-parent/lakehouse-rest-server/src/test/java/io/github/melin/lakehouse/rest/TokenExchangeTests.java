package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.service.LoginAttemptLimiter;
import io.github.melin.lakehouse.rest.support.Jwt;
import io.github.melin.lakehouse.rest.support.Paging;
import io.github.melin.lakehouse.rest.support.Secrets;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * RFC 8693 令牌交换在 HTTP 层的契约。
 *
 * <p><b>为什么这个测试比服务层的单元测试重要。</b>这条链路的唯一价值是「Iceberg 那边
 * 一行代码不改就能续期」——而 Iceberg 依规范发的是一个 {@code grant_type} 字符串、
 * 一组 snake_case 参数名、以及它自己按 {@code expires_in} 或 JWT 的 {@code exp}
 * 排的下一次刷新。这些<b>全部是 wire 契约</b>：参数名写成驼峰、grant 字符串少一个
 * 冒号、响应里把 {@code expires_in} 丢掉，服务层单测都发现不了，只会让引擎在跑了一
 * 小时之后开始 401，且日志里是一句「Failed to refresh token」。
 *
 * <p><b>本类刻意不 mock 认证链。</b>subject token 由客户端凭据流程真实签发，
 * 为的是覆盖「换出来的票能被 {@code BearerAuthInterceptor} 接受」这一环——
 * 「换到了票」与「这张票能用」之间的接线错误只有在这一层才会暴露。
 *
 * <p>过期票与伪造票只能手工签：服务端签票用的是注入的 {@code Clock}，
 * 而测试配置里的 TTL 是 30 分钟，等不起。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.auth.enabled=true",
        // 与 ConsoleAuthEndpointTests 用同一把固定密钥：本类要手工签「过期的」与
        // 「别人签的」两种票，密钥必须能从测试里构造出来
        "lakehouse.rest.auth.access-token.signing-key=dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=",
        "lakehouse.rest.auth.access-token.ttl=30m",
        "lakehouse.rest.auth.tokens[0]=static-token-for-exchange-tests",
        "lakehouse.rest.auth.token-principals.static-token-for-exchange-tests=static-machine-user",
        // 阈值调小，让「连续失败后被拒」能在几行内测到
        "lakehouse.rest.auth.console.client-credentials.rate-limit.max-failures=3",
        "lakehouse.rest.auth.console.client-credentials.rate-limit.window=1m"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TokenExchangeTests {

    private static final String TOKEN_ENDPOINT = "/api/catalog/v1/oauth/tokens";

    /** Iceberg 规格里的那个路径。它与上面是同一个处理方法的两个名字。 */
    private static final String ICEBERG_TOKEN_ENDPOINT = "/v1/oauth/tokens";

    private static final String PROTECTED = "/api/console/v1/meta";

    private static final String GRANT_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";

    private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    /** 与 {@code auth.access-token.ttl=30m} 对应的秒数。 */
    private static final long TTL_SECONDS = 1800;

    private static final String PRINCIPAL_NAME = "token-exchange-test-principal";

    private static final String CLIENT_ID = "token-exchange-test-client";

    private static final String CLIENT_SECRET = "s3cret-value-for-exchange-tests";

    private static final String STATIC_TOKEN = "static-token-for-exchange-tests";

    private static final byte[] SIGNING_KEY = Base64.getDecoder()
            .decode("dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrincipalRepository principalRepository;

    @Autowired
    private LoginAttemptLimiter limiter;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        // 限速器是单例，且交换失败按「来源地址」分桶：用例之间必须隔开，
        // 否则前一条故意制造的失败会把后一条顶到阈值
        limiter.reset();
        if (principalRepository.findByClientId(CLIENT_ID).isEmpty()) {
            principalRepository.save(newPrincipal(PRINCIPAL_NAME, CLIENT_ID, CLIENT_SECRET));
        }
    }

    @AfterEach
    void tearDown() {
        principalRepository.findByClientId(CLIENT_ID).ifPresent(principalRepository::delete);
        limiter.reset();
    }

    // ------------------------------------------------------------------ 正常路径

    /**
     * 一张客户端凭据换来的票，可以换出一张新的、能被受保护端点接受、主体不变的票。
     *
     * <p>这条覆盖的是整条链的闭环：签票 → 交换 → 认证。断言主体名而不只是
     * 「200」：交换的 {@code sub} 一旦不是取自认证结果，就是提权——
     * 而 200 的响应看起来完全正常。
     */
    @Test
    void anIssuedTokenCanBeExchangedForANewOne() throws Exception {
        String subject = issueViaClientCredentials();
        JsonNode body = json(exchangeRequest(subject, null, null, null, null), 200);

        String exchanged = body.get("access_token").asString();
        assertFalse(exchanged.isBlank());
        assertNotEquals(subject, exchanged, "交换必须产出新票；返回原票等于没续期");
        assertEquals("bearer", body.get("token_type").asString());
        assertEquals("PRINCIPAL_ROLE:ALL", body.get("scope").asString());
        assertEquals(TTL_SECONDS, body.get("expires_in").asLong(),
                "TTL 应当来自 access-token.ttl=30m，而不是「在原票基础上延长」");
        assertEquals(ACCESS_TOKEN_TYPE, body.get("issued_token_type").asString(),
                "RFC 8693 要求回显换出来的令牌类型；Iceberg 用得到");

        MvcResult probe = mockMvc.perform(get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + exchanged)).andReturn();
        assertEquals(200, probe.getResponse().getStatus(), () -> "换出来的票被拒了：" + body(probe));

        JsonNode session = json(mockMvc.perform(get("/api/console/v1/auth")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + exchanged)).andReturn(), 200).get("session");
        assertEquals(PRINCIPAL_NAME, session.get("principal").asString(), "主体必须与原票一致");
        assertEquals("console-access-token", session.get("source").asString(),
                "换出来的是本服务端签的票，与客户端凭据签的那种没有区别");
    }

    /**
     * 新票的 {@code sub} 与原票相同，且 {@code exp - iat} 等于 TTL（不放大）。
     *
     * <p>直接读 JWT 的载荷而不是只看响应字段：Iceberg 走的是
     * {@code OAuth2Util.expiresAtMillis(token)}——它解析的是<b>票里的</b> {@code exp}。
     * 响应里 {@code expires_in} 对、票里的 {@code exp} 不对，那种情况只在这条断言下现形，
     * 而它的现象是「引擎按错误的时间排刷新」。
     */
    @Test
    void theNewTokenKeepsTheSameSubjectAndDoesNotExtendTheTtl() throws Exception {
        String subject = issueViaClientCredentials();
        String exchanged = json(exchangeRequest(subject, null, null, null, null), 200)
                .get("access_token").asString();

        Jwt.Decoded before = Jwt.decode(subject);
        Jwt.Decoded after = Jwt.decode(exchanged);

        assertEquals(before.claim("sub"), after.claim("sub"));
        long ttl = after.numericClaim("exp") - after.numericClaim("iat");
        assertEquals(TTL_SECONDS, ttl, "交换不是「延期」，是换一张同寿命的新票");
        assertTrue(after.numericClaim("iat") >= before.numericClaim("iat"),
                "新票的 iat 不该早于原票");
        assertNotEquals(before.claim("jti"), after.claim("jti"), "两张票必须是不同的票");
    }

    /** 换出来的票还能再换——多级链上主体保持不变，这一点是「长作业」的前提。 */
    @Test
    void chainedExchangesStayInTheSameHands() throws Exception {
        String first = issueViaClientCredentials();
        String second = json(exchangeRequest(first, null, null, null, null), 200)
                .get("access_token").asString();
        String third = json(exchangeRequest(second, null, null, null, null), 200)
                .get("access_token").asString();

        assertEquals(PRINCIPAL_NAME, Jwt.decode(third).claim("sub"));
        assertEquals(TTL_SECONDS,
                Jwt.decode(third).numericClaim("exp") - Jwt.decode(third).numericClaim("iat"));
    }

    /** 省略的 scope 归一成 {@code PRINCIPAL_ROLE:ALL}，与客户端凭据流程同一套规则。 */
    @Test
    void scopeDefaultsToPrincipalRoleAll() throws Exception {
        JsonNode body = json(exchangeRequest(issueViaClientCredentials(), null, null, null, "  "), 200);
        assertEquals("PRINCIPAL_ROLE:ALL", body.get("scope").asString());
    }

    /** 静态令牌也能参与交换：它本来就代表一次长期授权，换出来的票更短命而不是更长。 */
    @Test
    void staticTokensCanBeExchangedIntoShortLivedTokens() throws Exception {
        JsonNode body = json(exchangeRequest(STATIC_TOKEN, null, null, null, null), 200);
        String exchanged = body.get("access_token").asString();

        assertEquals("static-machine-user", Jwt.decode(exchanged).claim("sub"),
                "主体按 token-principals 映射后的名字，而不是令牌本身");
        assertEquals(TTL_SECONDS, body.get("expires_in").asLong(),
                "静态令牌本身没有失效时刻，换出来的票必须有");
    }

    // ------------------------------------------------------------------ 拒绝路径

    /**
     * 过期的 subject token → {@code invalid_grant}。
     *
     * <p>这是整条链的寿命上界：作业挂起超过一个 TTL 就续不上，必须重新拿票。
     * 少了这条，「一张泄露的票」就能被无限续期。
     */
    @Test
    void expiredSubjectTokenIsInvalidGrant() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String expired = sign(Map.of("sub", PRINCIPAL_NAME, "iat", now - 7200, "exp", now - 3600));

        JsonNode body = json(exchangeRequest(expired, null, null, null, null), 400);
        assertEquals("invalid_grant", body.get("error").asString());
    }

    /** 认不出的 subject token 同样是 {@code invalid_grant}：调用方要做的事是「重新拿票」。 */
    @Test
    void unknownSubjectTokenIsInvalidGrant() throws Exception {
        JsonNode body = json(exchangeRequest("not-a-real-token", null, null, null, null), 400);
        assertEquals("invalid_grant", body.get("error").asString());
    }

    /** 别的实例（别的密钥）签的票与本实例的令牌无关，不能换来本实例的票。 */
    @Test
    void aTokenSignedWithAnotherKeyIsInvalidGrant() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 7);
        String foreign = Jwt.signHs256(Map.of(
                "iss", "paimon-rest", "sub", PRINCIPAL_NAME, "iat", now, "exp", now + 3600), otherKey);

        assertEquals("invalid_grant",
                json(exchangeRequest(foreign, null, null, null, null), 400).get("error").asString());
    }

    /**
     * 不支持的 scope → {@code invalid_scope}，与客户端凭据流程共用同一套归一化。
     *
     * <p>「交换能把权限收紧」是个常见误解：交换只是重新签发一张<b>等权</b>的票，
     * 它既不能加也不能减。接受一个不生效的 scope 会让人以为权限被收窄了。
     */
    @Test
    void unsupportedScopeIsRejected() throws Exception {
        JsonNode body = json(exchangeRequest(issueViaClientCredentials(), null, null, null,
                "PRINCIPAL_ROLE:read_only"), 400);

        assertEquals("invalid_scope", body.get("error").asString());
        assertTrue(body.get("error_description").asString().contains("PRINCIPAL_ROLE:ALL"),
                "报文要说出唯一被接受的值，否则调用方只能猜");
    }

    /** {@code actor_token} 一律拒绝——本工程没有「代他人操作」的模型。 */
    @Test
    void actorTokenIsRejected() throws Exception {
        JsonNode body = json(exchangeRequest(issueViaClientCredentials(), null, null,
                "some-actor-token", null), 400);

        assertEquals("invalid_request", body.get("error").asString());
        assertTrue(body.get("error_description").asString().contains("actor_token"));
    }

    /** 要一种我们不会签发的令牌类型（例如 {@code jwt}）时拒绝，而不是照样给 access_token。 */
    @Test
    void unsupportedRequestedTokenTypeIsRejected() throws Exception {
        JsonNode body = json(exchangeRequest(issueViaClientCredentials(), null,
                "urn:ietf:params:oauth:token-type:jwt", null, null), 400);
        assertEquals("invalid_request", body.get("error").asString());

        JsonNode subjectType = json(exchangeRequest(issueViaClientCredentials(),
                "urn:ietf:params:oauth:token-type:id_token", null, null, null), 400);
        assertEquals("invalid_request", subjectType.get("error").asString());
    }

    /** 缺 {@code subject_token} 是参数问题（400 {@code invalid_request}），不是认证问题。 */
    @Test
    void missingSubjectTokenIsInvalidRequest() throws Exception {
        assertEquals("invalid_request",
                json(exchangeRequest(null, null, null, null, null), 400).get("error").asString());
    }

    /**
     * 连续失败的交换会被限速，且错误码与客户端凭据那条一致。
     *
     * <p>这个端点与 {@code client_credentials} 一样匿名可调：不拦的话，一次小规模并发
     * 就能带着大量验签与解析进来。成功不计数——正常的长作业会不断换票。
     */
    @Test
    void repeatedFailedExchangesAreRateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertEquals(400, exchangeRequest("wrong-" + i, null, null, null, null).getResponse().getStatus());
        }

        MvcResult blocked = exchangeRequest("wrong-again", null, null, null, null);
        assertEquals(429, blocked.getResponse().getStatus());
        assertEquals("temporarily_unavailable", json(blocked, 429).get("error").asString());

        // 正确的票同样被拒：限速是按来源计的，不为「这次对了」开例外
        assertEquals(429, exchangeRequest(issueViaClientCredentials(), null, null, null, null)
                .getResponse().getStatus());
    }

    // ------------------------------------------------------------------ 兼容与形态

    /**
     * 客户端凭据流程的响应对老客户端<b>逐字节不变</b>：不出现 {@code issued_token_type} 这个键。
     *
     * <p>加字段对按 RFC 6749 写的库是透明的，但「同一个端点有时带这个键、有时不带」
     * 会让日志对比与手工解析多一个噪声。因此用 {@code NON_NULL} 显式抹掉 null。
     */
    @Test
    void theClientCredentialsResponseDoesNotGrowANewKey() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", CLIENT_ID)
                        .param("client_secret", CLIENT_SECRET))
                .andReturn();

        json(result, 200);
        assertFalse(body(result).contains("issued_token_type"),
                "client_credentials 的响应不该多出这个键：" + body(result));
    }

    /** 也接受 JSON 请求体：拒绝它只会让人以为端点写错了。 */
    @Test
    void jsonRequestBodiesWorkForTheExchange() throws Exception {
        String subject = issueViaClientCredentials();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("grant_type", GRANT_EXCHANGE);
        payload.put("subject_token", subject);
        payload.put("subject_token_type", ACCESS_TOKEN_TYPE);

        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(payload)))
                .andReturn();

        assertNotNull(json(result, 200).get("access_token"));
    }

    /** Iceberg 规格里的 {@code /v1/oauth/tokens} 与 Polaris 对齐路径是同一个端点的两个名字。 */
    @Test
    void bothEndpointPathsAcceptTheExchange() throws Exception {
        String subject = issueViaClientCredentials();

        MvcResult result = mockMvc.perform(post(ICEBERG_TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", GRANT_EXCHANGE)
                        .param("subject_token", subject))
                .andReturn();

        assertNotNull(json(result, 200).get("access_token"));
    }

    /** 不认识的 grant_type 仍然报 {@code unsupported_grant_type}，并在报文里列出两个取值。 */
    @Test
    void unknownGrantTypeListsBothSupportedGrants() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code"))
                .andReturn();

        JsonNode body = json(result, 400);
        assertEquals("unsupported_grant_type", body.get("error").asString());
        assertTrue(body.get("error_description").asString().contains("client_credentials"));
        assertTrue(body.get("error_description").asString().contains(GRANT_EXCHANGE));
    }

    // ------------------------------------------------------------------ 工具

    /** 走客户端凭据流程拿一张真票：本类所有正常路径都从它出发。 */
    private String issueViaClientCredentials() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", CLIENT_ID)
                        .param("client_secret", CLIENT_SECRET))
                .andReturn();
        return json(result, 200).get("access_token").asString();
    }

    /** 打一次交换请求。{@code null} 的参数一律不发送，让「没填」与「填了空串」分开。 */
    private MvcResult exchangeRequest(String subjectToken, String subjectTokenType,
            String requestedTokenType, String actorToken, String scope) throws Exception {
        var request = post(TOKEN_ENDPOINT).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("grant_type", GRANT_EXCHANGE);
        if (subjectToken != null) {
            request = request.param("subject_token", subjectToken);
        }
        if (subjectTokenType != null) {
            request = request.param("subject_token_type", subjectTokenType);
        }
        if (requestedTokenType != null) {
            request = request.param("requested_token_type", requestedTokenType);
        }
        if (actorToken != null) {
            request = request.param("actor_token", actorToken);
        }
        if (scope != null) {
            request = request.param("scope", scope);
        }
        return mockMvc.perform(request).andReturn();
    }

    /** 用本服务的那把密钥签一张票：用来造「过期的」「别的实例签的」这类输入。 */
    private static String sign(Map<String, Object> claims) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("iss", "paimon-rest");
        payload.put("jti", Paging.newId());
        payload.putAll(claims);
        return Jwt.signHs256(payload, SIGNING_KEY);
    }

    private JsonNode json(MvcResult result, int expectedStatus) throws Exception {
        String text = body(result);
        assertEquals(expectedStatus, result.getResponse().getStatus(), () -> "body=" + text);
        return mapper.readTree(text);
    }

    private static String body(MvcResult result) {
        try {
            return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is unavailable", e);
        }
    }

    private static PrincipalEntity newPrincipal(String name, String clientId, String secret) {
        PrincipalEntity entity = new PrincipalEntity();
        entity.setId(Paging.newId());
        entity.setName(name);
        entity.setClientId(clientId);
        entity.setProperties(new LinkedHashMap<>());
        String salt = Secrets.newSalt();
        entity.setSecretSalt(salt);
        entity.setSecretHash(Secrets.hash(salt, secret));
        entity.markCreated("system", System.currentTimeMillis());
        return entity;
    }
}
