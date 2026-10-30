package io.github.melin.lakehouse.rest.engine.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.paimon.options.Options;
import org.apache.paimon.rest.auth.AuthProvider;
import org.apache.paimon.rest.auth.AuthProviderFactory;
import org.apache.paimon.rest.auth.RESTAuthParameter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 续期规则的用例。
 *
 * <p>重心不是「能换到票」，而是四条<b>配错了也不会报错</b>的规则：
 * 窗口没到不能换（否则每个请求都换票）、并发只能换一次、换票失败不能抛
 * （否则把本来能成功的请求变成 500）、没有过期依据时不能假装能续。
 * 它们的共同点是「错了也跑得下去」，所以只能靠用例钉住。
 */
class RefreshingAuthProviderTests {

    private static final String ENDPOINT = "http://catalog:8080/v1/oauth/tokens";

    /** 起始票：iat=0、exp=3600，于是窗口 = min(15% × 3600s, 5min) = 5min。 */
    private static final String START = jwt("alice", 0, 3_600);

    @BeforeEach
    void clearCache() {
        RefreshingTokens.clear();
        TokenRefreshMetrics.clear();
    }

    // ------------------------------------------------------------------ 时序

    @Test
    void doesNotRefreshBeforeTheRefreshWindow() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient().returns(jwt("alice", 0, 7_200), 3_600L);
        RefreshingToken token = token(START, client, null, clock);

        // 窗口上限 5 分钟：50 分钟时离到期还有 10 分钟，还没进窗口
        clock.advance(Duration.ofMinutes(50));
        token.token();

        assertEquals(0, client.total(), "窗口是到期前 5 分钟，50 分钟时不该换票");
    }

    @Test
    void refreshesOnceInsideTheRefreshWindow() {
        MutableClock clock = new MutableClock();
        String refreshed = jwt("alice", 0, 7_200);
        RecordingClient client = new RecordingClient().returns(refreshed, 3_600L);
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));   // 进入最后 5 分钟
        String first = token.token();
        String second = token.token();

        assertEquals(1, client.exchanges(), "同一窗口内只该换一次");
        assertEquals(first, second);
        assertEquals(refreshed, first, "换票后应使用新票");
        assertNotEquals(START, first);
    }

    @Test
    void concurrentCallsShareASingleRefresh() throws Exception {
        MutableClock clock = new MutableClock();
        // 换票本身要花时间，否则「8 个线程各自看到窗口已到」这一幕撞不上
        RecordingClient client = new RecordingClient().returns(jwt("alice", 0, 7_200), 3_600L)
                .delay(Duration.ofMillis(50));
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<String> seen = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    String value = token.token();
                    synchronized (seen) {
                        seen.add(value);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "并发取票应在超时前完成");
        pool.shutdownNow();

        assertEquals(1, client.exchanges(), "8 个并发请求只该发 1 次换票");
        assertEquals(1, seen.stream().distinct().count(), "所有线程必须拿到同一张票");
    }

    // ------------------------------------------------------------------ 失败

    @Test
    void failedRefreshKeepsTheOldTokenAndDoesNotThrow() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient().fails(new TokenRefreshException(500, null, "boom"));
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));
        String returned = token.token();

        assertEquals(START, returned,
                "换票失败必须返回旧票：旧票可能还剩几分钟，抛出去会把能成功的请求变成 500");
        assertEquals(1, client.exchanges());
    }

    @Test
    void failedRefreshesAreCountedByCause() {
        MutableClock clock = new MutableClock();
        // errorCode 为空、状态 500：这一类是「服务端或网络的问题」，与「服务端明确拒绝」分开计数
        RecordingClient client = new RecordingClient().fails(new TokenRefreshException(500, null, "boom"));
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));
        token.token();
        assertEquals(1, TokenRefreshMetrics.failures(TokenRefreshMetrics.CAUSE_IO),
                "连不上 / 5xx 归 io_error，与「被服务端拒绝」分开");

        clock.advance(Duration.ofMillis(1_100));
        token.token();
        assertEquals(2, TokenRefreshMetrics.failures(TokenRefreshMetrics.CAUSE_IO));
        assertEquals(2, TokenRefreshMetrics.totalFailures(), "累计总数用于区分「偶发」与「连续」");
    }

    /** 服务端给了 OAuth 错误码时，计数按那个码分桶——它才指向配置问题。 */
    @Test
    void serverSideErrorCodesBecomeTheirOwnCause() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient()
                .fails(new TokenRefreshException(400, "invalid_grant", "subject_token expired"));
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));
        token.token();

        assertEquals(1, TokenRefreshMetrics.failures("invalid_grant"));
        assertEquals(0, TokenRefreshMetrics.failures(TokenRefreshMetrics.CAUSE_IO));
    }

    @Test
    void backoffStopsRetryingImmediately() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient().fails(new TokenRefreshException(500, null, "boom"));
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));
        token.token();                       // 第 1 次失败：退避 1s
        token.token();                       // 退避期内：不该再打服务端
        assertEquals(1, client.exchanges(), "退避期内不该重复换票");

        clock.advance(Duration.ofMillis(1_100));
        token.token();                       // 退避已过 → 第 2 次失败，退避 2s
        assertEquals(2, client.exchanges(), "退避到期后应重试");

        clock.advance(Duration.ofMillis(1_900));
        token.token();                       // 1.9s < 2s，还不该死磕
        assertEquals(2, client.exchanges(), "第二次失败后应退避 2s");

        clock.advance(Duration.ofMillis(200));
        token.token();
        assertEquals(3, client.exchanges());
    }

    @Test
    void invalidateReportsWhetherTheCallerShouldReplay() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient().returns(jwt("alice", 0, 7_200), 3_600L);
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(1));
        assertTrue(token.invalidate(), "换到了另一张票 → 值得重放一次");

        client.fails(new TokenRefreshException(503, null, "down"));
        assertFalse(token.invalidate(), "换票失败 → 重放也是同一个 401，不该重放");
    }

    @Test
    void fixedTokenIsNeverInvalidated() {
        TokenSource fixed = TokenSource.fixed("static-token");
        assertEquals("static-token", fixed.token());
        assertFalse(fixed.invalidate(), "固定票作废之后还是同一张票，调用方不该重放");
        assertNull(TokenSource.fixed("  ").token(), "空白令牌视同没配");
    }

    // ------------------------------------------------------------------ 过期依据

    @Test
    void fallsBackToJwtExpWhenTheResponseHasNoExpiresIn() {
        MutableClock clock = new MutableClock();
        // 换票响应不给 expires_in，只能读新票自己的 exp：iat=0、exp=600 → 窗口 90s
        String issued = jwt("alice", 0, 600);
        RecordingClient client = new RecordingClient().returns(issued, null);
        RefreshingToken token = token(jwt("alice", 0, 600), client, null, clock);

        clock.advance(Duration.ofMinutes(6));      // 360s：离到期 240s，还没进 90 秒窗口
        token.token();
        assertEquals(0, client.exchanges(), "窗口按 JWT 的 exp 推算：6 分钟时还没进窗口");

        clock.advance(Duration.ofSeconds(155));   // 515s：进入最后 90 秒
        token.token();
        assertEquals(1, client.exchanges());
        assertTrue(token.refreshable(), "能从 JWT exp 推出过期时间 → 可续");
    }

    @Test
    void stopsRefreshingWhenNeitherExpiresInNorJwtExpIsAvailable() {
        MutableClock clock = new MutableClock();
        // 静态令牌（不是 JWT），无从判断何时到期
        RecordingClient client = new RecordingClient().returns("opaque-token", null);
        RefreshingToken token = token("opaque-token", client, null, clock);

        clock.advance(Duration.ofDays(7));
        assertEquals("opaque-token", token.token());
        assertEquals(0, client.total(), "没有过期依据时不能假装能续：盲换既浪费又掩盖真实故障");
        assertFalse(token.refreshable());
    }

    @Test
    void treatsAZeroExpiresInAsNonRefreshable() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient().returns("opaque-token", 0L);
        RefreshingToken token = token(null, client, null, clock);

        token.token();
        token.token();

        assertEquals(1, client.total(),
                "expires_in=0 若被判为「可续」，就会变成每个请求都换票的死循环");
        assertFalse(token.refreshable());
    }

    @Test
    void clampsTheWindowSoThatAMisconfiguredValueCannotCauseAStorm() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient().returns("fresh", 90L);
        // 窗口配成 5 分钟而 TTL 只有 90 秒：压到 81 秒，给票留 9 秒余量
        RefreshingToken token = token(null, client, Duration.ofMinutes(5), clock);

        token.token();                                   // 冷启动取票，窗口被压到 81s
        token.token();
        assertEquals(1, client.total(), "刚换到的票不该马上又换");

        clock.advance(Duration.ofSeconds(82));
        token.token();
        assertEquals(2, client.total(), "窗口被压到 TTL 的 90%：到期前仍会换一次");
    }

    // ------------------------------------------------------------------ 冷启动与兜底

    @Test
    void coldStartFetchesWithClientCredentialsWhenThereIsNoInitialToken() {
        MutableClock clock = new MutableClock();
        String issued = jwt("alice", 0, 3_600);
        RecordingClient client = new RecordingClient().returns(issued, 3_600L);
        RefreshingToken token = token(null, client, null, clock);

        assertEquals(issued, token.token());
        assertEquals(1, client.credentials(), "没有起始票时首次取票走 client_credentials");
        assertEquals(0, client.exchanges());
    }

    @Test
    void anExpiredStartingTokenWithoutIatIsRefetchedWhenACredentialIsConfigured() {
        // 这张票有 exp、但**没有 iat**（外部 IdP 的票可能如此），且 exp 已在过去。
        // 算不出正寿命时不能把它当成「不可续的静态令牌」——那样配了凭据也永远不会取新票，
        // 而工厂 javadoc 承诺的「起始票过期 + 有凭据 = 可用」就不成立
        MutableClock clock = new MutableClock();
        clock.advance(Duration.ofMinutes(10));
        String expired = jwtWithoutIat("alice", -60);
        String issued = jwt("alice", 0, 7_200);
        RecordingClient client = new RecordingClient().withCredential().returns(issued, 3_600L);
        RefreshingToken token = token(expired, client, null, clock);

        assertEquals(issued, token.token());
        assertEquals(1, client.credentials(), "已过期且有凭据：直接走 client_credentials");
        assertEquals(0, client.exchanges(), "不去交换一张死票（必然 invalid_grant）");
    }

    @Test
    void fallsBackToClientCredentialsWhenTheServerRejectsTheExchange() {
        MutableClock clock = new MutableClock();
        String issued = jwt("alice", 0, 7_200);
        RecordingClient client = new RecordingClient()
                .exchangeFails(new TokenRefreshException(400, "unsupported_grant_type", "only client_credentials"))
                .withCredential()
                .returns(issued, 3_600L);
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));
        assertEquals(issued, token.token());
        assertEquals(1, client.credentials(), "服务端不认 token-exchange 时应退回 client_credentials");
    }

    @Test
    void doesNotFallBackWithoutACredential() {
        MutableClock clock = new MutableClock();
        RecordingClient client = new RecordingClient()
                .exchangeFails(new TokenRefreshException(400, "unsupported_grant_type", "only client_credentials"));
        RefreshingToken token = token(START, client, null, clock);

        clock.advance(Duration.ofMinutes(56));
        assertEquals(START, token.token(), "没有凭据时无路可退，只能继续用旧票");
        assertEquals(0, client.credentials());
    }

    // ------------------------------------------------------------------ 进程级缓存

    @Test
    void refreshedStateSurvivesProviderRecreation() {
        MutableClock clock = new MutableClock();
        RecordingClient first = new RecordingClient().returns("from-first", 3_600L);
        RecordingClient second = new RecordingClient().returns("from-second", 3_600L);
        String key = RefreshingTokens.keyOf(ENDPOINT, null, START);

        RefreshingToken one = RefreshingTokens.obtain(key,
                () -> new RefreshingToken(key, START, first, null, clock));
        clock.advance(Duration.ofMinutes(56));
        one.token();

        // Paimon 的 RESTTokenFileIO 每次刷新数据票都会重建 RESTApi → 工厂被再次调用
        RefreshingToken two = RefreshingTokens.obtain(key,
                () -> new RefreshingToken(key, START, second, null, clock));

        assertSame(one, two, "同一个端点 + 主体必须复用同一份续期状态");
        assertEquals("from-first", two.token(),
                "重建后的实例必须看到已续期的票，而不是配置里那张初始票");
        assertEquals(0, second.total(), "新实例不该自己再去换一次");
    }

    @Test
    void cacheKeyNeverContainsTheToken() {
        String key = RefreshingTokens.keyOf(ENDPOINT, null, START);
        assertTrue(key.contains("sub:alice"), "有 sub 时按主体分组：" + key);
        assertFalse(key.contains(START), "键会进日志与堆转储，不能带令牌本体");

        String opaqueKey = RefreshingTokens.keyOf(ENDPOINT, null, "static-secret-token");
        assertFalse(opaqueKey.contains("static-secret-token"), "静态令牌同样只以摘要出现");
        assertTrue(opaqueKey.startsWith(ENDPOINT + "|tok:"));
    }

    // ------------------------------------------------------------------ provider 与工厂

    @Test
    void providerWritesTheBearerHeader() {
        AuthProvider provider = new PaimonOAuth2AuthProvider(TokenSource.fixed("abc"), "test");
        Map<String, String> headers = provider.mergeAuthHeader(new HashMap<>(Map.of("Accept", "json")),
                parameter());

        assertEquals("Bearer abc", headers.get("Authorization"));
        assertEquals("json", headers.get("Accept"), "不能把调用方原有的头丢掉");
    }

    @Test
    void providerFailsClearlyWhenThereIsNoTokenAtAll() {
        AuthProvider provider = new PaimonOAuth2AuthProvider(TokenSource.fixed(null), "endpoint|sub:alice");
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> provider.mergeAuthHeader(new HashMap<>(), parameter()));
        assertTrue(error.getMessage().contains("oauth2.credential"),
                "错误消息要直接指出该配什么，而不是只说没有票");
    }

    @Test
    void factoryReadsTheDocumentedOptions() {
        Options options = new Options();
        options.set("uri", "http://catalog:8080");
        options.set("token", START);
        options.set(PaimonOAuth2AuthProviderFactory.OPTION_CREDENTIAL, "alice:s3cret");

        AuthProviderFactory factory = new PaimonOAuth2AuthProviderFactory();
        assertEquals("oauth2", factory.identifier());

        AuthProvider provider = factory.create(options);
        assertEquals("Bearer " + START,
                provider.mergeAuthHeader(new HashMap<>(), parameter()).get("Authorization"));
    }

    @Test
    void factoryRejectsAConfigurationThatCanNeverWork() {
        Options options = new Options();
        options.set("uri", "http://catalog:8080");
        options.set("token.provider", "oauth2");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PaimonOAuth2AuthProviderFactory().create(options));
        assertTrue(error.getMessage().contains(PaimonOAuth2AuthProviderFactory.OPTION_CREDENTIAL),
                "既没有 token 也没有凭据时，报错必须点名该补哪个配置");
    }

    @Test
    void endpointIsDerivedFromTheCatalogUri() {
        assertEquals(URI.create("http://h:8080/v1/oauth/tokens"),
                PaimonOAuth2AuthProviderFactory.endpointOf("http://h:8080", null));
        assertEquals(URI.create("http://h:8080/v1/oauth/tokens"),
                PaimonOAuth2AuthProviderFactory.endpointOf("http://h:8080/", null));
        // uri 已经带 /v1 时不能再拼一次：拼成 /v1/v1/... 是 404，
        // 而它看起来像「服务端没开令牌端点」
        assertEquals(URI.create("http://h:8080/v1/oauth/tokens"),
                PaimonOAuth2AuthProviderFactory.endpointOf("http://h:8080/v1", null));
        assertEquals(URI.create("https://idp/token"),
                PaimonOAuth2AuthProviderFactory.endpointOf("http://h:8080", "https://idp/token"));
        assertThrows(IllegalArgumentException.class,
                () -> PaimonOAuth2AuthProviderFactory.endpointOf(null, null));
    }

    @Test
    void credentialSplitsOnTheFirstColon() {
        String[] pair = EngineTokenSources.splitCredential("alice:a:b:c");
        assertEquals("alice", pair[0]);
        assertEquals("a:b:c", pair[1], "密钥里允许出现冒号，按第一个冒号拆");

        assertNull(EngineTokenSources.splitCredential("  "));
        assertThrows(IllegalArgumentException.class,
                () -> EngineTokenSources.splitCredential("nocolon"));
    }

    @Test
    void refreshWindowAcceptsTheDocumentedForms() {
        assertEquals(Duration.ofSeconds(300), EngineTokenSources.parseWindow("300s"));
        assertEquals(Duration.ofMinutes(5), EngineTokenSources.parseWindow("5min"));
        assertEquals(Duration.ofMillis(500), EngineTokenSources.parseWindow("500ms"));
        assertEquals(Duration.ofSeconds(300), EngineTokenSources.parseWindow("300"),
                "裸数字按秒，不按毫秒");
        assertEquals(Duration.ofHours(1), EngineTokenSources.parseWindow("1h"));
        assertNull(EngineTokenSources.parseWindow(""));
        assertThrows(IllegalArgumentException.class,
                () -> EngineTokenSources.parseWindow("soon"));
    }

    /**
     * SPI 文件存在且指向本工厂。
     *
     * <p>没有这一条，「忘了 resources/META-INF/services」在测试里完全看不出——所有用例
     * 都是直接 {@code new} 工厂，而真实部署走 ServiceLoader。症状只会出现在引擎上：
     * {@code Could not find any factory for identifier 'oauth2'}。
     */
    @Test
    void withoutAnEndpointTheSourceDegradesToAFixedToken() {
        TokenSource source = EngineTokenSources.create(null, START, "alice:s3cret", null, null);
        assertEquals(START, source.token());
        assertFalse(source.invalidate(),
                "没有端点就无从换票，必须退化为固定票——否则调用方会在 401 后不断重放");
    }

    @Test
    void withoutAnyTicketTheSourceStaysEmptyInsteadOfFailing() {
        // 与改造前一致：不发 Authorization 头，由服务端决定怎么拒
        assertNull(EngineTokenSources.create(null, null, null, null, null).token());
        assertNull(EngineTokenSources.create(ENDPOINT, "  ", "  ", null, null).token());
    }

    @Test
    void withAnEndpointTheSourceIsRefreshable() {
        TokenSource source = EngineTokenSources.create(ENDPOINT, START, null, null, null);
        assertTrue(source instanceof RefreshingToken, "有端点 + 有票 → 该走续期");
        assertEquals(START, source.token());
    }

    @Test
    void spiRegistrationPointsAtThisFactory() throws IOException {
        String resource = "META-INF/services/org.apache.paimon.factories.Factory";
        try (InputStream stream = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resource)) {
            assertTrue(stream != null, "缺少 " + resource);
            String content = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(content.contains(PaimonOAuth2AuthProviderFactory.class.getName()),
                    "SPI 文件必须登记 " + PaimonOAuth2AuthProviderFactory.class.getName());
        }
    }

    // ------------------------------------------------------------------ 夹具

    private static RefreshingToken token(String initial, TokenClient client, Duration window,
                                         Clock clock) {
        return new RefreshingToken(ENDPOINT + "|test", initial, client, window, clock);
    }

    private static RESTAuthParameter parameter() {
        return new RESTAuthParameter("/v1/abc/tables", Map.of(), "GET", null);
    }

    /** 造一张 JWT 形状的票：只读第二段（载荷），签名不验。 */
    private static String jwt(String subject, long issuedAt, long expiresAt) {
        return jwtPayload("{\"sub\":\"" + subject + "\",\"iat\":" + issuedAt + ",\"exp\":" + expiresAt + "}");
    }

    /** 没有 {@code iat} 的票：外部 IdP 可能这样签，此时只能按 {@code exp - now} 估寿命。 */
    private static String jwtWithoutIat(String subject, long expiresAt) {
        return jwtPayload("{\"sub\":\"" + subject + "\",\"exp\":" + expiresAt + "}");
    }

    private static String jwtPayload(String json) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        return header + "." + encoder.encodeToString(json.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    /** 可推进的时钟：窗口是「TTL 的 15%」，靠真等没法验证。 */
    private static final class MutableClock extends Clock {

        private long millis;

        void advance(Duration duration) {
            millis += duration.toMillis();
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    /** 可编程的换票客户端：记录调用次数，按需返回票或抛错。 */
    private static final class RecordingClient implements TokenClient {

        private final AtomicInteger exchanges = new AtomicInteger();

        private final AtomicInteger credentials = new AtomicInteger();

        private IssuedToken next;

        /** 两个 grant 都失败。 */
        private RuntimeException failure;

        /** 只有 exchange 失败（用来模拟服务端不认识 exchange、但认 client_credentials）。 */
        private RuntimeException exchangeFailure;

        private Duration delay = Duration.ZERO;

        private boolean credential;

        RecordingClient returns(String token, Long expiresInSeconds) {
            this.next = new IssuedToken(token, expiresInSeconds, null);
            return this;
        }

        RecordingClient fails(RuntimeException error) {
            this.failure = error;
            return this;
        }

        RecordingClient exchangeFails(RuntimeException error) {
            this.exchangeFailure = error;
            return this;
        }

        RecordingClient delay(Duration duration) {
            this.delay = duration;
            return this;
        }

        RecordingClient withCredential() {
            this.credential = true;
            return this;
        }

        int exchanges() {
            return exchanges.get();
        }

        int credentials() {
            return credentials.get();
        }

        int total() {
            return exchanges.get() + credentials.get();
        }

        @Override
        public boolean hasCredential() {
            return credential;
        }

        @Override
        public IssuedToken exchange(String subjectToken) {
            exchanges.incrementAndGet();
            sleep();
            if (exchangeFailure != null) {
                throw exchangeFailure;
            }
            if (failure != null) {
                throw failure;
            }
            return next;
        }

        @Override
        public IssuedToken clientCredentials() {
            credentials.incrementAndGet();
            sleep();
            if (failure != null) {
                throw failure;
            }
            return next;
        }

        private void sleep() {
            if (delay.isZero()) {
                return;
            }
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
