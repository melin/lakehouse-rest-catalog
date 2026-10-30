package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.melin.lakehouse.rest.domain.entity.PrincipalEntity;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.service.AccessTokenService;
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
 * {@code max-session} 护栏：一条续期链最长能活多久。
 *
 * <p><b>为什么它需要单独一个测试类。</b>上限是<b>启动期</b>的配置（它决定令牌里有没有
 * {@code ses} 这个 claim），而 {@code TokenExchangeTests} 那套配置跑的是默认值
 * （不限）。把它塞进去只能靠 {@code @TestPropertySource} 逐方法改值，而 Spring
 * 会因此多建一个容器——不如各自一份，配置就在类头上，读的人一眼看到前提。
 *
 * <p><b>断言的核心不是「被拒」，而是「拒了也不影响手里的票」。</b>到点即失效的写法
 * 会让跑满 N 小时的作业在某个随机请求上 401，而那一刻它既没有新票也没有告警；
 * 这里的规矩是「续不上新的，旧的用完为止」——所以第一条用例里同一个老链上的票
 * 一边被拒换票、一边仍然打得通受保护端点。
 */
@SpringBootTest(properties = {
        "lakehouse.rest.auth.enabled=true",
        "lakehouse.rest.auth.access-token.signing-key=dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=",
        "lakehouse.rest.auth.access-token.ttl=30m",
        // 链最长 1 小时：用例用「两小时前开始的链」触发，不用等
        "lakehouse.rest.auth.access-token.max-session=1h",
        "lakehouse.rest.auth.tokens[0]=static-token-for-session-limit-tests",
        "lakehouse.rest.auth.token-principals.static-token-for-session-limit-tests=static-machine-user",
        "lakehouse.rest.auth.console.client-credentials.rate-limit.max-failures=5",
        "lakehouse.rest.auth.console.client-credentials.rate-limit.window=1m"})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TokenSessionLimitTests {

    private static final String TOKEN_ENDPOINT = "/api/catalog/v1/oauth/tokens";

    private static final String PROTECTED = "/api/console/v1/meta";

    private static final String GRANT_EXCHANGE = "urn:ietf:params:oauth:grant-type:token-exchange";

    private static final String PRINCIPAL_NAME = "token-session-limit-principal";

    private static final String CLIENT_ID = "token-session-limit-client";

    private static final String CLIENT_SECRET = "s3cret-value-for-session-limit-tests";

    private static final String STATIC_TOKEN = "static-token-for-session-limit-tests";

    private static final byte[] SIGNING_KEY = Base64.getDecoder()
            .decode("dGVzdC1zaWduaW5nLWtleS0zMi1ieXRlcy1sb25nISE=");

    /** 与 {@code max-session=1h} 对应的秒数。 */
    private static final long MAX_SESSION_SECONDS = 3600;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PrincipalRepository principalRepository;

    @Autowired
    private LoginAttemptLimiter limiter;

    private final JsonMapper mapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
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

    /**
     * 登录签出的票就是一条新链的起点：{@code ses} 等于它自己的 {@code iat}。
     *
     * <p>若 {@code ses} 被写成「每次换票的时刻」，上限就永远算不出真实链龄——
     * 每换一次都重置，这道护栏等于不存在，而且没有任何报错。
     */
    @Test
    void aFreshlyIssuedTokenStartsANewChain() throws Exception {
        Jwt.Decoded token = Jwt.decode(issueViaClientCredentials());

        Long session = token.numericClaim(AccessTokenService.CLAIM_SESSION_START);
        assertNotNull(session, "打开 max-session 后每张票都必须带 ses");
        assertEquals(token.numericClaim("iat"), session);
    }

    /** 链龄在上限之内时照常换票，且新票<b>沿用</b>同一个起点。 */
    @Test
    void aChainWithinTheLimitIsExtendedAndKeepsItsOrigin() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        long startedAt = now - 600;
        String parent = sign(Map.of("sub", PRINCIPAL_NAME, "iat", startedAt,
                "exp", now + 600, "ses", startedAt));

        JsonNode body = json(exchangeRequest(parent), 200);
        Jwt.Decoded next = Jwt.decode(body.get("access_token").asString());

        assertEquals(startedAt, next.numericClaim(AccessTokenService.CLAIM_SESSION_START),
                "新票必须沿用父票的 ses；写成「现在」会让上限每换一次就重置");
    }

    /**
     * 链龄超过上限 → 拒绝交换，但<b>手里的票仍然可用到期</b>。
     *
     * <p>这两件事必须同时成立，否则「护栏」会变成「到点随机 401」——那是比不限更糟的
     * 行为：不限至少是可预测的，随机失败则要人去猜是哪一环。
     */
    @Test
    void anOverAgeChainCannotBeExtendedButItsTokenStillWorks() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        long startedAt = now - MAX_SESSION_SECONDS - 3600;
        String parent = sign(Map.of("sub", PRINCIPAL_NAME, "iat", startedAt,
                "exp", now + 600, "ses", startedAt));

        JsonNode body = json(exchangeRequest(parent), 400);
        assertEquals("invalid_grant", body.get("error").asString());
        assertTrue(body.get("error_description").asString().contains("max-session"),
                "报文要指出是哪个配置项拦下的，否则一线只会看到又一个 401/400：" + body);

        MvcResult stillWorks = mockMvc.perform(get(PROTECTED)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + parent)).andReturn();
        assertEquals(200, stillWorks.getResponse().getStatus(),
                () -> "拒绝续期不该连带作废手里的票：" + stillWorks.getResponse().getStatus());
    }

    /**
     * 上限打开之前签出的票没有 {@code ses}，用 {@code iat} 兜底算出链龄。
     *
     * <p>这条覆盖的是「先关着跑一段、再打开上限」的升级路径：那批票里没有 {@code ses}，
     * 若只认 {@code ses}，它们会被当成「链龄 0」而无限续下去——护栏在有存量作业的
     * 部署里正好失效，而那正是它唯一有意义的场合。
     */
    @Test
    void theChainStartFallsBackToIatWhenSesIsAbsent() throws Exception {
        long now = System.currentTimeMillis() / 1000;
        String legacy = sign(Map.of("sub", PRINCIPAL_NAME,
                "iat", now - MAX_SESSION_SECONDS - 3600, "exp", now + 600));

        JsonNode body = json(exchangeRequest(legacy), 400);
        assertEquals("invalid_grant", body.get("error").asString());
        assertTrue(body.get("error_description").asString().contains("max-session"));
    }

    /**
     * 静态令牌不在这个账上：它不是 JWT，没有链的起点可言。
     *
     * <p>把它也算进去只能拿「第一次换票的时刻」当起点，那等于给每个用静态令牌的部署
     * 加一个从启动起算的倒计时——而静态令牌本来就是运维显式配置的长期凭据，
     * 要收回它应当去改配置，不是靠这道护栏。这里断言的是「换取成功且从此刻起算」。
     */
    @Test
    void staticTokensStartANewChain() throws Exception {
        JsonNode body = json(exchangeRequest(STATIC_TOKEN), 200);
        Jwt.Decoded issued = Jwt.decode(body.get("access_token").asString());

        assertEquals(issued.numericClaim("iat"),
                issued.numericClaim(AccessTokenService.CLAIM_SESSION_START),
                "静态令牌换来的票是一条新链的开头");
    }

    /** 认不出的令牌照旧是干净的 400，不会因为读不出链起点而 500。 */
    @Test
    void anUnreadableTokenIsRejectedCleanly() throws Exception {
        JsonNode body = json(exchangeRequest("garbage-token"), 400);
        assertEquals("invalid_grant", body.get("error").asString());
    }

    // ------------------------------------------------------------------ 工具

    private String issueViaClientCredentials() throws Exception {
        MvcResult result = mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "client_credentials")
                        .param("client_id", CLIENT_ID)
                        .param("client_secret", CLIENT_SECRET))
                .andReturn();
        return json(result, 200).get("access_token").asString();
    }

    private MvcResult exchangeRequest(String subjectToken) throws Exception {
        return mockMvc.perform(post(TOKEN_ENDPOINT)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", GRANT_EXCHANGE)
                        .param("subject_token", subjectToken))
                .andReturn();
    }

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
