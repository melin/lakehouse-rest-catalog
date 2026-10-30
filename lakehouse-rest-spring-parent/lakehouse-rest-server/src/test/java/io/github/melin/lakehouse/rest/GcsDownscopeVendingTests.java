package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.service.CatalogCredentialSource;
import io.github.melin.lakehouse.rest.service.CredentialScope;
import io.github.melin.lakehouse.rest.service.DefaultStorageCredentialManager;
import io.github.melin.lakehouse.rest.service.GcsAccessToken;
import io.github.melin.lakehouse.rest.service.GcsAccessTokenClient;
import io.github.melin.lakehouse.rest.service.GcsBoundary;
import io.github.melin.lakehouse.rest.service.GcsDownscopeClient;
import io.github.melin.lakehouse.rest.service.GcsDownscopeRequest;
import io.github.melin.lakehouse.rest.service.GcsDownscopedCredentialProvider;
import io.github.melin.lakehouse.rest.service.GcsDownscopedToken;
import io.github.melin.lakehouse.rest.service.GcsServiceAccount;
import io.github.melin.lakehouse.rest.service.GcsServiceAccountTokenSource;
import io.github.melin.lakehouse.rest.support.ApiException;
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
 *
 * <p><b>源身份在 catalog 上，不在 yml 里。</b>所以每个用例都必须先给
 * {@code serviceAccountJson}（库里是密文，这里用真实的 {@link CredentialCipher}
 * 封一遍，而不是塞一段假的 {@code v1:…}——那会把「解不开」变成另一条失败路径）。
 */
class GcsDownscopeVendingTests {

    private static final String SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final String TABLE = "gs://warehouse/wh/db.db/orders";

    private static final String SA_EMAIL = "svc@project.iam.gserviceaccount.com";

    /** 只求「是 JSON 对象且有两个必填键」——真正参与签名的只有换票客户端。 */
    private static final String SERVICE_ACCOUNT_JSON = """
            {"type":"service_account","project_id":"project",
             "client_email":"svc@project.iam.gserviceaccount.com",
             "private_key":"-----BEGIN PRIVATE KEY-----\\nMIIBVAIBADANBg\\n-----END PRIVATE KEY-----\\n",
             "token_uri":"https://oauth2.googleapis.com/token"}
            """;

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
                List.of(new CredentialScope("warehouse", List.of("wh/db.db/orders"),
                        List.of("wh/db.db/orders"), List.of())),
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
        CredentialScope scope = new CredentialScope(
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
        CredentialScope first = new CredentialScope(
                "warehouse", List.of("wh/a"), List.of("wh/a"), List.of());
        CredentialScope second = new CredentialScope(
                "archive", List.of("wh/b"), List.of("wh/b"), List.of());

        String boundary = GcsBoundary.json(List.of(first, second), false);
        assertTrue(boundary.contains("/buckets/warehouse/objects/wh/a*"), boundary);
        assertTrue(boundary.contains("/buckets/archive/objects/wh/b*"), boundary);
    }

    /** 超过 10 条规则时拒绝生成——STS 会拒整份 boundary，让它在本地失败更早也更清楚。 */
    @Test
    void refusesMoreRulesThanTheApiAccepts() {
        List<CredentialScope> scopes = new ArrayList<>();
        for (int i = 0; i < GcsBoundary.MAX_RULES + 1; i++) {
            scopes.add(new CredentialScope(
                    "warehouse", List.of("wh/t" + i), List.of("wh/t" + i), List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> GcsBoundary.json(scopes, false));

        FakeGcs gcs = new FakeGcs();
        VendedStorageCredential vended = manager(gcs).vendForCatalog(
                gcp(TABLE), "lh",
                scopes.stream().map(scope -> "gs://warehouse/" + scope.readPrefixes().get(0)).toList(),
                false);
        assertTrue(gcs.requests.isEmpty(), "规则太多时不该调云");
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source(),
                "退回「引擎自带身份」那一档，而不是报错");
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
        assertEquals(SA_EMAIL, vended.token().get("gcs.service-account"));
        assertEquals(VendedStorageCredential.SOURCE_GCS_DOWNSCOPED, vended.source());
        assertEquals(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE, vended.provider());
        assertTrue(vended.shortLived());
    }

    /** 交换请求里带的是「服务账号换来的源令牌」，且 {@code options} 是一段 JSON 文本。 */
    @Test
    void exchangesTheMintedTokenWithAJsonStringOption() {
        FakeGcs gcs = new FakeGcs();
        manager(gcs).vend(gcp(TABLE), "t", TABLE);

        GcsDownscopeRequest request = gcs.requests.get(0);
        assertEquals(FakeTokens.TOKEN, request.subjectToken());
        assertNotNull(request.options());
        assertTrue(request.options().startsWith("{"), "options 必须是 JSON 文本: " + request.options());
        assertTrue(request.options().contains("accessBoundary"), request.options());
    }

    /**
     * 换源令牌用的是**这个 catalog 上那份服务账号**。
     *
     * <p>这条断言是「配置跟着 catalog 走」在运行时的落点：身份一旦从全局配置读，
     * 两个 GCS catalog 会拿着同一把钥匙去换票，而这个错误在返回的键上完全看不出来。
     */
    @Test
    void mintsTheSourceTokenFromTheCatalogsOwnServiceAccount() {
        FakeTokens tokens = new FakeTokens();
        manager(new FakeGcs(), tokens).vend(gcp(TABLE), "t", TABLE);

        assertEquals(1, tokens.minted.size(), "一次取票只换一次源令牌");
        assertEquals(SA_EMAIL, tokens.minted.get(0).clientEmail());
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

    /** 没有服务账号：不签、不调云，行为与接上 provider 之前一致。 */
    @Test
    void declinesWithoutAServiceAccount() {
        FakeGcs gcs = new FakeGcs();
        GcpStorageConfigInfo noAccount =
                new GcpStorageConfigInfo(List.of(TABLE), null, SA_EMAIL, null);

        VendedStorageCredential vended = manager(gcs).vend(noAccount, "t", TABLE);

        assertTrue(gcs.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
        assertFalse(vended.token().containsKey("gcs.oauth2.token"));
    }

    /**
     * 位置认不出来时不换令牌，退回「引擎自带身份」那一档。
     *
     * <p>注意这里**不是**「把服务账号原样发出去」：{@code serviceAccountJson} 里是私钥，
     * 它只用于服务端换票。所以断言落在「没有令牌下发、也没调云」上。
     */
    @Test
    void declinesWithoutAScope() {
        FakeGcs gcs = new FakeGcs();
        VendedStorageCredential vended = manager(gcs).vend(gcp(""), "t", "");

        assertTrue(gcs.requests.isEmpty(), "认不出位置就不该调云");
        assertFalse(vended.token().containsKey("gcs.oauth2.token"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
    }

    /**
     * 有服务账号却换不到源令牌：**向上抛**，不退档。
     *
     * <p>这时引擎没有任何可用身份（GKE 之外没有 ADC），静默退回只会把错误推到
     * 引擎的 403 上，而服务端日志一片正常。
     */
    @Test
    void failsLoudlyWhenTheSourceTokenCannotBeMinted() {
        properties.getCredential().setProviders(
                List.of(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE));
        GcsAccessTokenClient broken = account -> {
            throw new ApiException(503, null, null, "mint failed");
        };
        DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(
                properties, cipher,
                List.of(new GcsDownscopedCredentialProvider(properties, cipher,
                        new GcsServiceAccountTokenSource(broken), new FakeGcs())));

        ApiException thrown = assertThrows(ApiException.class,
                () -> manager.vend(gcp(TABLE), "t", TABLE));
        assertEquals(503, thrown.getStatus());
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
    void consoleReportsTheFourGcsStates() {
        GcpStorageConfigInfo noAccount =
                new GcpStorageConfigInfo(List.of(TABLE), null, SA_EMAIL, null);
        GcpStorageConfigInfo account = gcp(TABLE);

        assertEquals(CatalogCredentialSource.Mode.NONE,
                CatalogCredentialSource.of(noAccount, properties).mode(), "没账号、没启用");
        assertTrue(CatalogCredentialSource.of(noAccount, properties).detail().contains("ADC"));

        assertEquals(CatalogCredentialSource.Mode.NONE,
                CatalogCredentialSource.of(account, properties).mode(), "有账号但没启用签发");
        assertTrue(CatalogCredentialSource.of(account, properties).detail()
                .contains("没启用 gcs-downscope"));

        properties.getCredential().setProviders(
                List.of(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE));
        CatalogCredentialSource.Source signed = CatalogCredentialSource.of(account, properties);
        assertEquals(CatalogCredentialSource.Mode.STS, signed.mode());
        assertTrue(signed.detail().contains("没有续期"), signed.detail());

        CatalogCredentialSource.Source enabledWithoutAccount =
                CatalogCredentialSource.of(noAccount, properties);
        assertEquals(CatalogCredentialSource.Mode.NONE, enabledWithoutAccount.mode());
        assertTrue(enabledWithoutAccount.detail().contains("serviceAccountJson"),
                enabledWithoutAccount.detail());
    }

    // ------------------------------------------------------------------ 工具

    private DefaultStorageCredentialManager manager(FakeGcs gcs) {
        return manager(gcs, new FakeTokens());
    }

    private DefaultStorageCredentialManager manager(FakeGcs gcs, GcsAccessTokenClient tokens) {
        properties.getCredential().setProviders(
                List.of(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE));
        return new DefaultStorageCredentialManager(properties, cipher,
                List.of(new GcsDownscopedCredentialProvider(properties, cipher,
                        new GcsServiceAccountTokenSource(tokens), gcs)));
    }

    /** 带服务账号的 catalog 配置；密钥按落库形态密封。 */
    private GcpStorageConfigInfo gcp(String location) {
        return new GcpStorageConfigInfo(List.of(location), null, SA_EMAIL,
                cipher.seal(SERVICE_ACCOUNT_JSON));
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

    /** 假换票端：记录「用哪份服务账号换的」，返回固定源令牌。 */
    private static final class FakeTokens implements GcsAccessTokenClient {

        static final String TOKEN = "SOURCE.TOKEN";

        private final List<GcsServiceAccount> minted = new ArrayList<>();

        @Override
        public GcsAccessToken mint(GcsServiceAccount account) {
            minted.add(account);
            return new GcsAccessToken(TOKEN, Instant.now().plus(Duration.ofHours(1)));
        }
    }
}
