package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.service.CatalogCredentialSource;
import io.github.melin.lakehouse.rest.service.DefaultStorageCredentialManager;
import io.github.melin.lakehouse.rest.service.GcsBoundary;
import io.github.melin.lakehouse.rest.service.GcsDownscopeClient;
import io.github.melin.lakehouse.rest.service.GcsDownscopeRequest;
import io.github.melin.lakehouse.rest.service.GcsDownscopedCredentialProvider;
import io.github.melin.lakehouse.rest.service.GcsDownscopedToken;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * GCS downscoped token 的签发（设计稿 P1-4）：boundary 形状、键名、有效期夹紧、退档。
 *
 * <p>这一家没有「角色」，所以断言的落点全在 boundary 上：资源必须写到对象层、
 * 权限必须是 {@code inRole:…} 形式、规则数不能超过 10。三条都是「写错了不报错、
 * 只在交换时被拒」的那一类。
 */
class GcsDownscopeVendingTests {

    private static final String SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final String TABLE = "gs://warehouse/wh/db.db/orders";

    private final RestServerProperties properties = new RestServerProperties();

    private final CredentialCipher cipher = cipher(properties);

    private static CredentialCipher cipher(RestServerProperties properties) {
        properties.getStorage().setCredentialSecretKey(SECRET_KEY);
        return new CredentialCipher(properties);
    }

    // ------------------------------------------------------------------ boundary

    /**
     * 资源写在对象层、协议相对、前缀以 {@code *} 结尾。
     *
     * <p>写成桶级（{@code .../buckets/<b>}）会被 STS 拒 {@code INVALID_ATTRIBUTE}；
     * 带上 {@code https:} 也不是它要的形状。
     */
    @Test
    void writesTheResourceAtObjectLevel() {
        String boundary = GcsBoundary.json(
                List.of(new io.github.melin.lakehouse.rest.service.CredentialScope(
                        "warehouse", List.of("wh/db.db/orders"), List.of("wh/db.db/orders"), List.of())),
                false);

        assertTrue(boundary.contains(
                "\"//storage.googleapis.com/projects/_/buckets/warehouse/objects/wh/db.db/orders*\""),
                boundary);
        assertFalse(boundary.contains("https:"), "必须是协议相对的形式: " + boundary);
        assertTrue(boundary.contains("inRole:roles/storage.objectViewer"), boundary);
        assertFalse(boundary.contains("objectCreator"), "只读时不该给创建权限: " + boundary);
    }

    /** 写权限加的是 {@code objectCreator}，不是 {@code objectAdmin}。 */
    @Test
    void addsTheCreatorRoleOnlyWhenAsked() {
        io.github.melin.lakehouse.rest.service.CredentialScope scope =
                new io.github.melin.lakehouse.rest.service.CredentialScope(
                        "warehouse", List.of("wh/t"), List.of("wh/t"), List.of("wh/t"));

        assertFalse(GcsBoundary.json(List.of(scope), false).contains("objectCreator"));
        String write = GcsBoundary.json(List.of(scope), true);
        assertTrue(write.contains("inRole:roles/storage.objectCreator"), write);
        assertFalse(write.contains("objectAdmin"),
                "objectAdmin 含删除与 ACL 管理，比「往表目录写文件」多得多: " + write);
    }

    /** 多条位置 → 多条规则；一个 scope 一条，不合并（合并要靠共同父目录，那是 Azure 的活）。 */
    @Test
    void writesOneRulePerScope() {
        io.github.melin.lakehouse.rest.service.CredentialScope first =
                new io.github.melin.lakehouse.rest.service.CredentialScope(
                        "warehouse", List.of("wh/a"), List.of("wh/a"), List.of());
        io.github.melin.lakehouse.rest.service.CredentialScope second =
                new io.github.melin.lakehouse.rest.service.CredentialScope(
                        "archive", List.of("wh/b"), List.of("wh/b"), List.of());

        String boundary = GcsBoundary.json(List.of(first, second), false);
        assertTrue(boundary.contains("/buckets/warehouse/objects/wh/a*"), boundary);
        assertTrue(boundary.contains("/buckets/archive/objects/wh/b*"), boundary);
    }

    /** 超过 10 条规则时拒绝生成——STS 会拒整份 boundary，让它在本地失败更早也更清楚。 */
    @Test
    void refusesMoreRulesThanTheApiAccepts() {
        List<io.github.melin.lakehouse.rest.service.CredentialScope> scopes = new ArrayList<>();
        for (int i = 0; i < GcsBoundary.MAX_RULES + 1; i++) {
            scopes.add(new io.github.melin.lakehouse.rest.service.CredentialScope(
                    "warehouse", List.of("wh/t" + i), List.of("wh/t" + i), List.of()));
        }
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> GcsBoundary.json(scopes, false));

        FakeGcs gcs = new FakeGcs();
        VendedStorageCredential vended = manager(gcs).vendForCatalog(
                gcp(TABLE), "lh", scopes.stream().map(scope -> "gs://warehouse/" + scope.readPrefixes().get(0)).toList(),
                false);
        assertTrue(gcs.requests.isEmpty(), "规则太多时不该调云");
        assertEquals(VendedStorageCredential.SOURCE_CONFIGURATION, vended.source(),
                "退回「转发服务端令牌」那一档，而不是报错");
    }

    // ------------------------------------------------------------------ 键名

    /**
     * 下发 {@code gcs.oauth2.token} + {@code gcs.oauth2.token-expires-at}。
     *
     * <p>到期键不能省：Paimon 的 GCS FileIO 会读它，缺了它引擎就当成「不会过期」，
     * 于是票到期后继续用，拿回一个 401。
     */
    @Test
    void vendsTheDownscopedTokenWithItsExpiry() {
        FakeGcs gcs = new FakeGcs();
        VendedStorageCredential vended = manager(gcs).vend(gcp(TABLE), "t", TABLE);

        assertEquals("DOWN.TOKEN", vended.token().get("gcs.oauth2.token"));
        assertNotNull(vended.token().get("gcs.oauth2.token-expires-at"));
        assertEquals("svc@project.iam.gserviceaccount.com", vended.token().get("gcs.service-account"));
        assertEquals(VendedStorageCredential.SOURCE_GCS_DOWNSCOPED, vended.source());
        assertEquals(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE, vended.provider());
        assertTrue(vended.shortLived());
    }

    /** 交换请求里带的是配置里的源令牌，且 {@code options} 是一段 JSON 文本（不是对象）。 */
    @Test
    void exchangesTheConfiguredTokenWithAJsonStringOption() {
        FakeGcs gcs = new FakeGcs();
        manager(gcs).vend(gcp(TABLE), "t", TABLE);

        GcsDownscopeRequest request = gcs.requests.get(0);
        assertEquals("SOURCE.TOKEN", request.subjectToken());
        assertNotNull(request.options());
        assertTrue(request.options().startsWith("{"), "options 必须是 JSON 文本: " + request.options());
        assertTrue(request.options().contains("accessBoundary"), request.options());
    }

    // ------------------------------------------------------------------ 有效期

    /** 平台上限 1 小时：配 6 小时也只签 1 小时，且下发的 ttl 与票一致。 */
    @Test
    void clampsTheLifetimeToOneHour() {
        properties.getCredential().setTtlSeconds(6 * 3600);
        VendedStorageCredential vended = manager(new FakeGcs()).vend(gcp(TABLE), "t", TABLE);

        assertTrue(vended.ttlSeconds() <= 3600, "downscoped token 最长 1 小时: " + vended.ttlSeconds());
        long remaining = Duration.between(Instant.now(),
                Instant.ofEpochMilli(vended.expireTimeInMs())).toSeconds();
        assertTrue(remaining > 3000 && remaining <= 3600, "假实现给一小时: " + remaining);
    }

    // ------------------------------------------------------------------ 退档

    /** 没有源令牌：不签、不调云，行为与接入 provider 之前一致。 */
    @Test
    void declinesWithoutASourceToken() {
        properties.getCredential().setProviders(
                List.of(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE));
        FakeGcs gcs = new FakeGcs();
        DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(
                properties, cipher, List.of(new GcsDownscopedCredentialProvider(properties, gcs)));

        VendedStorageCredential vended = manager.vend(gcp(TABLE), "t", TABLE);

        assertTrue(gcs.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
        assertFalse(vended.token().containsKey("gcs.oauth2.token"));
    }

    /**
     * 位置认不出来时不换令牌，退回「转发源令牌」那一档。
     *
     * <p>注意这里**不是**「什么都不发」：转发档本来就会把配置里的令牌原样给出去，
     * 断言要落在「发的是源令牌、不是换出来的」上，否则会误以为退档等于没凭据。
     */
    @Test
    void declinesWithoutAScope() {
        FakeGcs gcs = new FakeGcs();
        VendedStorageCredential vended = manager(gcs).vend(gcp(""), "t", "");

        assertTrue(gcs.requests.isEmpty(), "认不出位置就不该调云");
        assertEquals("SOURCE.TOKEN", vended.token().get("gcs.oauth2.token"),
                "退回合法的转发档：源令牌原样下发");
        assertEquals(VendedStorageCredential.SOURCE_CONFIGURATION, vended.source());
    }

    /** 源令牌本身带过期时刻时，取云的到期而不是「现在 + TTL」。 */
    @Test
    void prefersTheExpiryTheExchangeReturned() {
        FakeGcs gcs = new FakeGcs();
        VendedStorageCredential vended = manager(gcs).vend(gcp(TABLE), "t", TABLE);
        assertEquals(gcs.expiresAt.toEpochMilli(),
                Long.parseLong(vended.token().get("gcs.oauth2.token-expires-at")));
    }

    // ------------------------------------------------------------------ 控制台判定

    @Test
    void consoleReportsTheThreeGcsStates() {
        GcpStorageConfigInfo gcs = gcp(TABLE);
        assertEquals(CatalogCredentialSource.Mode.NONE,
                CatalogCredentialSource.of(gcs, properties).mode(), "没令牌、没启用");

        properties.getStorage().getGcp().setToken("SOURCE.TOKEN");
        assertEquals(CatalogCredentialSource.Mode.RELAY,
                CatalogCredentialSource.of(gcs, properties).mode(), "有令牌但没启用签发：转发");

        properties.getCredential().setProviders(
                List.of(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE));
        CatalogCredentialSource.Source signed = CatalogCredentialSource.of(gcs, properties);
        assertEquals(CatalogCredentialSource.Mode.STS, signed.mode());
        assertTrue(signed.detail().contains("没有续期"), signed.detail());

        properties.getStorage().getGcp().setToken("");
        CatalogCredentialSource.Source enabledWithoutToken =
                CatalogCredentialSource.of(gcs, properties);
        assertEquals(CatalogCredentialSource.Mode.NONE, enabledWithoutToken.mode());
        assertTrue(enabledWithoutToken.detail().contains("没有源令牌"), enabledWithoutToken.detail());
    }

    // ------------------------------------------------------------------ 工具

    private DefaultStorageCredentialManager manager(FakeGcs gcs) {
        properties.getCredential().setProviders(
                List.of(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE));
        properties.getStorage().getGcp().setToken("SOURCE.TOKEN");
        return new DefaultStorageCredentialManager(properties, cipher,
                List.of(new GcsDownscopedCredentialProvider(properties, gcs)));
    }

    private static GcpStorageConfigInfo gcp(String location) {
        return new GcpStorageConfigInfo(List.of(location), null,
                "svc@project.iam.gserviceaccount.com");
    }

    /** 假 STS：记录请求，返回一个一小时有效的令牌。 */
    private static final class FakeGcs implements GcsDownscopeClient {

        private final List<GcsDownscopeRequest> requests = new ArrayList<>();

        private final Instant expiresAt = Instant.now().plus(Duration.ofHours(1));

        @Override
        public GcsDownscopedToken downscope(GcsDownscopeRequest request) {
            requests.add(request);
            return new GcsDownscopedToken("DOWN.TOKEN", expiresAt);
        }
    }
}
