package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.service.AccessTokenService;
import io.github.melin.lakehouse.rest.service.TokenIssuanceMetrics;
import io.github.melin.lakehouse.rest.support.AuthenticatedToken;
import io.github.melin.lakehouse.rest.support.Jwt;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link AccessTokenService} 的单元测试。
 *
 * <p>这一层是「令牌为什么失效」的答案所在。用户看到的永远是 401，
 * 而可能的原因有：过期、密钥换了、签发者不符、令牌被篡改。把它们逐个钉死，
 * 线上排查就只剩配置问题。
 *
 * <p>时间用固定时钟推进，不用 {@code Thread.sleep}：验证「TTL 之后失效」
 * 若靠真实等待，一个 1 小时的 TTL 不可能被测试覆盖，而改成 1 秒又会引入
 * 机器负载导致的假失败。
 */
class AccessTokenServiceTests {

    private static final String KEY = base64Key("0123456789abcdef0123456789abcdef");

    /** 轮换里的「新钥」。取值随意，只要与 {@link #KEY} 不同且足够长。 */
    private static final String NEW_KEY = base64Key("fedcba9876543210fedcba9876543210");

    private final TestClock clock = new TestClock("2026-01-01T00:00:00Z");

    @Test
    void issuedTokenCarriesThePrincipalAndTheConfiguredTtl() {
        AccessTokenService service = service(KEY, Duration.ofHours(2));

        AccessTokenService.Issued issued = service.issue("alice", "PRINCIPAL_ROLE:ALL");

        assertEquals(7200, issued.expiresInSeconds());
        Jwt.Decoded decoded = Jwt.decode(issued.token());
        assertEquals("alice", decoded.claim("sub"));
        assertEquals("paimon-rest", decoded.claim("iss"));
        assertEquals("PRINCIPAL_ROLE:ALL", decoded.claim("scope"));
        assertEquals(clock.instant().getEpochSecond() + 7200, decoded.numericClaim("exp"));
        assertTrue(decoded.claim("jti") != null && !decoded.claim("jti").isBlank());

        Optional<AuthenticatedToken> verified = service.verify(issued.token());
        assertTrue(verified.isPresent());
        assertEquals("alice", verified.get().principal());
        assertEquals((clock.instant().getEpochSecond() + 7200) * 1000, verified.get().expiresAtMillis());
    }

    /** 不带 scope 时不写该 claim，而不是写一个空串。 */
    @Test
    void emptyScopeIsOmitted() {
        AccessTokenService service = service(KEY, Duration.ofMinutes(30));
        assertFalse(Jwt.decode(service.issue("alice", "  ").token()).claims().containsKey("scope"));
    }

    /** TTL 到期后立即失效——不多给一秒，因为没有撤销机制时这就是唯一的暴露窗口上限。 */
    @Test
    void tokenExpiresExactlyAtTheTtlBoundary() {
        AccessTokenService service = service(KEY, Duration.ofMinutes(30));
        String token = service.issue("alice", null).token();

        clock.advance(Duration.ofMinutes(29));
        assertTrue(service.verify(token).isPresent(), "未到期就失效会让用户莫名被登出");

        clock.advance(Duration.ofMinutes(1));
        assertFalse(service.verify(token).isPresent());
    }

    /** 换一把签名密钥后，此前签发的令牌必须全部失效——这是唯一的「全员登出」手段。 */
    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        AccessTokenService first = service(KEY, Duration.ofHours(1));
        AccessTokenService second = service(base64Key("ffffffffffffffffffffffffffffffff"), Duration.ofHours(1));

        assertFalse(second.verify(first.issue("alice", null).token()).isPresent());
    }

    /** 篡改载荷（把主体改成 admin）必须失效。 */
    @Test
    void tamperedTokenIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        String[] parts = service.issue("alice", null).token().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"iss\":\"paimon-rest\",\"sub\":\"admin\",\"exp\":9999999999}"
                        .getBytes(StandardCharsets.UTF_8));

        assertFalse(service.verify(parts[0] + "." + forgedPayload + "." + parts[2]).isPresent());
    }

    /**
     * 签发者不符的令牌被拒。
     *
     * <p>这条守的是「同一份密钥被多个服务共用」的部署：密钥相同、签名合法，
     * 但令牌不是发给本服务的。少了这个校验，任何一个能拿到密钥的服务
     * 都能替本服务签发身份。
     */
    @Test
    void tokenWithAnotherIssuerIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "another-service");
        claims.put("sub", "alice");
        claims.put("exp", clock.instant().getEpochSecond() + 3600);
        String forged = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY));

        assertFalse(service.verify(forged).isPresent());
    }

    /** 没有 exp 的令牌被拒：那意味着它永不过期。 */
    @Test
    void tokenWithoutExpiryIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "paimon-rest");
        claims.put("sub", "alice");
        String forged = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY));

        assertFalse(service.verify(forged).isPresent());
    }

    /** 尚未生效（nbf 在未来）的令牌被拒。 */
    @Test
    void tokenNotYetValidIsRejected() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "paimon-rest");
        claims.put("sub", "alice");
        claims.put("exp", clock.instant().getEpochSecond() + 3600);
        claims.put("nbf", clock.instant().getEpochSecond() + 600);
        String forged = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY));

        assertFalse(service.verify(forged).isPresent());
    }

    /** 空主体、乱码令牌、静态令牌都要被稳妥拒绝，而不是抛异常。 */
    @Test
    void malformedTokensAreRejectedWithoutThrowing() {
        AccessTokenService service = service(KEY, Duration.ofHours(1));

        // 显式转型为 String：verify 有两个重载，null 会引发歧义
        assertFalse(service.verify((String) null).isPresent());
        assertFalse(service.verify("").isPresent());
        assertFalse(service.verify("opaque-static-token").isPresent());
        assertFalse(service.verify("a.b.c").isPresent());
    }

    /**
     * 未配置签名密钥时随机生成，且进程之间互不认账。
     *
     * <p>「互不认账」正是要断言的行为：如果随机密钥能被另一个实例猜中，
     * 那这个降级方案反而是安全的——但它不会，所以多实例部署必须显式配置密钥。
     * 这个测试同时也说明本地单机调试是安全的（重启后令牌失效，仅此而已）。
     */
    @Test
    void randomKeyIsGeneratedWhenUnsetAndIsNotSharedAcrossInstances() {
        AccessTokenService first = service(null, Duration.ofHours(1));
        AccessTokenService second = service(null, Duration.ofHours(1));

        String token = first.issue("alice", null).token();
        assertTrue(first.verify(token).isPresent());
        assertFalse(second.verify(token).isPresent(), "两个实例不该共享同一把随机密钥");
    }

    /** 密钥配置写错时必须启动失败，不能静默退回随机密钥。 */
    @Test
    void invalidSigningKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> service("not-base64!!!", Duration.ofHours(1)),
                "非 base64 的密钥应当让服务起不来");

        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> service(shortKey, Duration.ofHours(1)),
                "短于 32 字节的 HMAC 密钥可被暴力还原");

        // 合法的最小长度：32 字节，必须通过
        service(base64Key("0123456789abcdef0123456789abcdef"), Duration.ofHours(1));
    }

    // ------------------------------------------------- 密钥轮换（kid + 双密钥）

    /**
     * 没配 {@code signing-key-id} 时，头部必须还是那两项——逐项比对，不是「看起来一样」。
     *
     * <p>这条守的是「升级不改变令牌形状」：单密钥部署加了这个功能之后，头部多一个
     * {@code kid} 也不会更安全（只有一把钥匙，没有歧义），但会让升级前后签出的票在
     * 头部就出现差异，排查时多一个与问题无关的变量。所以断言的是<b>键的顺序与集合</b>，
     * 而不只是「有 alg 和 typ」。
     */
    @Test
    void headerKeepsTwoFieldsWhenSigningKeyIdIsNotConfigured() {
        String token = service(KEY, Duration.ofHours(1)).issue("alice", null).token();

        Map<String, Object> header = Jwt.decode(token).header();
        assertEquals(List.of("alg", "typ"), new ArrayList<>(header.keySet()));
    }

    /** 配了 {@code signing-key-id} 时多一个 kid，其余不变。 */
    @Test
    void headerCarriesKidWhenSigningKeyIdIsConfigured() {
        String token = service(KEY, "k1", Map.of(), Duration.ofHours(1)).issue("alice", null).token();

        Jwt.Decoded decoded = Jwt.decode(token);
        assertEquals("k1", decoded.keyId());
        assertEquals("HS256", decoded.algorithm());
        assertEquals("JWT", decoded.header().get("typ"));
    }

    /**
     * 旧钥匙签的票，在「签 B、验 A+B」的实例上仍要通过——这是滚动轮换不中断的全部。
     *
     * <p>它同时钉住一个副作用：这类票会被记进 {@code legacy-key} 计数。删旧钥的判据是
     * 「连续一个窗口没有新增」，判据要是没人记，第三步就只能靠猜。
     */
    @Test
    void tokenSignedWithTheOldKeyStillVerifiesWhileItIsListedAsVerifyOnly() {
        AccessTokenService beforeRotation = service(KEY, "k1", Map.of(), Duration.ofHours(1));
        String oldToken = beforeRotation.issue("alice", null).token();

        TokenIssuanceMetrics metrics = new TokenIssuanceMetrics();
        AccessTokenService duringRotation = new AccessTokenService(
                properties(NEW_KEY, "k2", Map.of("k1", KEY), Duration.ofHours(1)), clock, metrics);

        Optional<AuthenticatedToken> verified = duringRotation.verify(oldToken);
        assertTrue(verified.isPresent(), "旧票在轮换窗口里被拒，等于轮换期间全量 401");
        assertEquals("alice", verified.get().principal());
        assertEquals(1L, metrics.legacyKeyUsage().get("k1"), "旧钥匙的用量是删旧钥的唯一判据");

        // 新票由新钥签发，并且它只在新配置下通过——旧配置还没学会新钥（这正是滚动顺序的意义）
        String newToken = duringRotation.issue("alice", null).token();
        assertEquals("k2", Jwt.decode(newToken).keyId());
        assertTrue(duringRotation.verify(newToken).isPresent());
        assertFalse(beforeRotation.verify(newToken).isPresent());
    }

    /**
     * 未知的 kid 直接判 {@code INVALID}，<b>不回退去挨个试</b>。
     *
     * <p>两边都用真钥匙签、签名完全合法，唯一的差别是头部的 kid：
     * 如果实现「不认识的 kid 就挨个试」，这张票会被收下，而它代表的正是
     * 「某个副本还没学会新钥却已经开始签发」这种错误顺序。让它通过等于把这个
     * 错误推迟到删旧钥时才爆发。
     *
     * <p>第二条断言守的是「先验签再判过期」：过期且 kid 未知必须是 {@code INVALID}
     * 而不是 {@code EXPIRED}，否则编一个 kid 就能探出这张票原来什么时候过期。
     */
    @Test
    void tokenWithUnknownKidIsRejectedWithoutFallingBackToOtherKeys() {
        AccessTokenService service = service(KEY, "k2", Map.of("k1", NEW_KEY), Duration.ofHours(1));

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", "paimon-rest");
        claims.put("sub", "alice");
        claims.put("exp", clock.instant().getEpochSecond() + 3600);
        String future = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY), "k9");
        assertEquals(AccessTokenService.Verdict.INVALID, service.examine(Jwt.decode(future)));

        claims.put("exp", clock.instant().getEpochSecond() - 1);
        String expired = Jwt.signHs256(claims, Base64.getDecoder().decode(KEY), "k9");
        assertEquals(AccessTokenService.Verdict.INVALID, service.examine(Jwt.decode(expired)));
        assertNotEquals(AccessTokenService.Verdict.EXPIRED, service.examine(Jwt.decode(expired)),
                "未验签就回 EXPIRED，等于用一张乱签的票探测过期时间");
    }

    /**
     * 升级前签发的票（头部没有 kid、由旧钥签）在多钥配置下仍要通过。
     *
     * <p>没有这条，升级动作本身就会踢掉所有在线的人：他们的票是上一个版本签的，
     * 头部没有 kid，走的是「逐把试」那条路径。这条路径通常只在升级当天被走到，
     * 也就最容易没被测到。
     */
    @Test
    void tokenWithoutKidIsStillAcceptedWhenAnOldKeyIsListed() {
        AccessTokenService beforeUpgrade = service(KEY, Duration.ofHours(1));
        String oldToken = beforeUpgrade.issue("alice", null).token();
        assertFalse(Jwt.decode(oldToken).header().containsKey("kid"));

        AccessTokenService afterUpgrade = service(NEW_KEY, "k2", Map.of("k1", KEY), Duration.ofHours(1));
        assertTrue(afterUpgrade.verify(oldToken).isPresent());
    }

    /**
     * 四类密钥配置错误必须启动失败。
     *
     * <p>它们的共同点是「会让票签出来或配下去之后没人能验」，而症状都是 401——
     * 如果静默接受，排查会从「为什么所有人掉线」开始，而不是从「配置写错了」开始。
     */
    @Test
    void misconfiguredKeyRingFailsFast() {
        String shortKey = Base64.getEncoder().encodeToString("too-short".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalStateException.class,
                () -> service(KEY, "k2", Map.of("k1", "not-base64!!!"), Duration.ofHours(1)),
                "旧钥写成非 base64：轮换窗口里的票会全部被拒");
        assertThrows(IllegalStateException.class,
                () -> service(KEY, "k2", Map.of("k1", shortKey), Duration.ofHours(1)),
                "短于 32 字节的旧钥同样可被暴力还原");
        assertThrows(IllegalStateException.class,
                () -> service(KEY, "k1", Map.of("k1", NEW_KEY), Duration.ofHours(1)),
                "同一个 kid 既是签发钥又在旧钥表里：旧钥会被静默架空，轮换第三步删的是个空配置");
        assertThrows(IllegalStateException.class,
                () -> service(KEY, "k1", Map.of("  ", NEW_KEY), Duration.ofHours(1)),
                "空 kid 永远命中不了，属于纯写错");
    }

    /** TTL 写成 0 或负数时收敛到 1 秒，而不是签出一个立刻失效的令牌。 */
    @Test
    void nonPositiveTtlIsClampedToOneSecond() {
        assertEquals(1, service(KEY, Duration.ZERO).ttlSeconds());
        assertEquals(1, service(KEY, Duration.ofSeconds(-100)).ttlSeconds());

        AccessTokenService service = service(KEY, Duration.ZERO);
        clock.advance(Duration.ofMillis(999));
        assertTrue(service.verify(service.issue("alice", null).token()).isPresent());
    }

    // ------------------------------------------------------------------ 工具

    private RestServerProperties properties(String signingKey, Duration ttl) {
        return properties(signingKey, null, Map.of(), ttl);
    }

    private RestServerProperties properties(String signingKey, String signingKeyId,
                                            Map<String, String> verifyOnlyKeys, Duration ttl) {
        RestServerProperties properties = new RestServerProperties();
        properties.getAuth().setEnabled(true);
        properties.getAuth().getAccessToken().setTtl(ttl);
        properties.getAuth().getAccessToken().setSigningKey(signingKey);
        properties.getAuth().getAccessToken().setSigningKeyId(signingKeyId);
        properties.getAuth().getAccessToken().getVerifyOnlyKeys().putAll(verifyOnlyKeys);
        return properties;
    }

    private AccessTokenService service(String signingKey, Duration ttl) {
        return service(signingKey, null, Map.of(), ttl);
    }

    private AccessTokenService service(String signingKey, String signingKeyId,
                                       Map<String, String> verifyOnlyKeys, Duration ttl) {
        return new AccessTokenService(properties(signingKey, signingKeyId, verifyOnlyKeys, ttl), clock,
                new TokenIssuanceMetrics());
    }

    private static String base64Key(String value) {
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
