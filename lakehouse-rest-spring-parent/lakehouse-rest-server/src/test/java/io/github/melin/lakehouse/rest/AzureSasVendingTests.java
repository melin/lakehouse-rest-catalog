package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.lakehouse.rest.service.AzureDelegationRequest;
import io.github.melin.lakehouse.rest.service.AzureLocation;
import io.github.melin.lakehouse.rest.service.AzureSasCredentialProvider;
import io.github.melin.lakehouse.rest.service.CatalogCredentialSource;
import io.github.melin.lakehouse.rest.service.DefaultStorageCredentialManager;
import io.github.melin.lakehouse.rest.service.UserDelegationKey;
import io.github.melin.lakehouse.rest.service.UserDelegationKeyClient;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Azure 用户委托 SAS 的签发（设计稿 P1-1）：键名四形态、作用域收敛、平台上限。
 *
 * <p><b>为什么这些断言值得写。</b>Azure 这一档的失败方式与 S3 完全不同：S3 签错了多半天
 * 拿到 {@code AccessDenied}，Azure 签错了拿到的是 {@code AuthenticationFailed /
 * Signature did not match}——与「密钥不对」「账户名写错」「路径写错」全都长得一样。
 * 所以这里断言的都不是「没抛异常」，而是**签出来的那个字符串长什么样**：
 * 查询串里有哪些参数、签名会随哪些输入变化、哪些键该出现、哪些键必须不出现。
 *
 * <p>不碰真实 Azure：{@link UserDelegationKeyClient} 注入假实现并记录请求。
 * 真实的 {@code generateUserDelegationKey} 需要一套应用注册与数据面权限，
 * 单测里假装成功只会得到一个没内容的结论。
 */
class AzureSasVendingTests {

    private static final String SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    /** 一个合法的 base64 HMAC 密钥（32 字节全零）；内容不重要，能解码就行。 */
    private static final String DELEGATION_KEY = Base64.getEncoder().encodeToString(new byte[32]);

    private static final String TABLE = "abfss://warehouse@acct.dfs.core.windows.net/wh/db.db/orders";

    private final RestServerProperties properties = new RestServerProperties();

    private final CredentialCipher cipher = cipher(properties);

    private static CredentialCipher cipher(RestServerProperties properties) {
        properties.getStorage().setCredentialSecretKey(SECRET_KEY);
        return new CredentialCipher(properties);
    }

    // ------------------------------------------------------------------ 键名

    /**
     * 单一账户时四种键名一起发。
     *
     * <p>少的任何一种都会让某一代客户端认证失败，而报错不提键名：Spark 经 Iceberg 的
     * ADLSFileIO 认 {@code adls.sas-token.<account>.<host>}，Iceberg 1.7.x 认
     * {@code adls.sas-token.<account>}，PyIceberg 经 adlfs/fsspec 认 bare 那一个 +
     * {@code adls.account-name}。
     */
    @Test
    void vendsEveryKeyFormForASingleAccount() {
        FakeKeys keys = new FakeKeys();
        VendedStorageCredential vended = manager(keys).vend(azure(TABLE), "db.orders", TABLE);

        Map<String, String> token = vended.token();
        String standard = token.get("adls.sas-token.acct.dfs.core.windows.net");
        assertNotNull(standard, "标准形态：账户 + 完整存储 DNS 名");
        assertEquals(standard, token.get("adls.sas-token.acct"), "Iceberg 1.7.x 的形态");
        assertEquals(standard, token.get("adls.sas-token"), "bare 形态（单账户时才发）");
        assertEquals("acct", token.get("adls.account-name"));
        assertNotNull(token.get("adls.sas-token-expires-at-ms"));
        assertNotNull(token.get("adls.sas-token-expires-at-ms.acct.dfs.core.windows.net"));

        assertEquals(VendedStorageCredential.SOURCE_AZURE_SAS, vended.source());
        assertEquals(VendedStorageCredential.PROVIDER_AZURE_SAS, vended.provider());
        assertTrue(vended.shortLived(), "SAS 一定有到期时刻");
        assertTrue(vended.hasSecrets(), "短期凭据一律算「发了密钥」");
    }

    /**
     * {@code adls.account-host} 必须**不**出现。
     *
     * <p>它不是凭据而是端点开关：给了之后 fsspec/adlfs 会拿它去拼 URL，从此走 DFS 端点，
     * 而 PyIceberg 的默认实现只认 Blob——表现是「本地能读、PyIceberg 认证失败」。
     * PyIceberg 自己会从 {@code adls.sas-token.<account>.<host>} 的键名推出这两件事。
     */
    @Test
    void neverEmitsAccountHost() {
        VendedStorageCredential vended = manager(new FakeKeys()).vend(azure(TABLE), "t", TABLE);

        assertFalse(vended.token().containsKey("adls.account-host"),
                "会把 fsspec 客户端导去 DFS 端点: " + vended.token().keySet());
        assertFalse(vended.token().containsKey("adls.sas-token-expires-at-ms."),
                "空后缀的到期键是另一个错法: " + vended.token().keySet());
    }

    // ------------------------------------------------------------------ 作用域

    /**
     * 目录级 SAS：{@code sr=d} + {@code sdd=3}，权限只读 {@code rl}。
     *
     * <p>{@code sdd} 是「目录相对容器根有几层」，填错的表现是 400
     * {@code MissingRequiredQueryParameter}（少给）或签名与实际 URL 对不上（多给）。
     */
    @Test
    void scopesTheSasToTheTableDirectory() {
        VendedStorageCredential vended = manager(new FakeKeys()).vend(azure(TABLE), "t", TABLE);

        Map<String, String> query = query(vended.token().get("adls.sas-token"));
        assertEquals("d", query.get("sr"), "目录级，不是容器级");
        assertEquals("3", query.get("sdd"), "wh/db.db/orders 有三层");
        assertEquals("rl", query.get("sp"), "只读：read + list");
        assertEquals("2020-12-06", query.get("sv"));
        assertEquals("b", query.get("sks"));
        assertEquals("2020-12-06", query.get("skv"));
        assertEquals("oid-1", query.get("skoid"));
        assertEquals("tid-1", query.get("sktid"));
        // skt / ske 用服务端返回的原串：重排格式会让签名与密钥生命周期不一致
        assertEquals("2026-10-30T00:00:00.0000000Z", query.get("skt"));
        assertNotNull(query.get("sig"));
        assertNotNull(query.get("st"));
        assertNotNull(query.get("se"));
    }

    /** 写票要加 {@code acwdl}；顺序错了签名就对不上，所以这里是按序比的。 */
    @Test
    void addsWritePermissionsOnlyWhenAsked() {
        VendedStorageCredential read = manager(new FakeKeys()).vend(azure(TABLE), "t", TABLE);
        assertEquals("rl", query(read.token().get("adls.sas-token")).get("sp"));

        VendedStorageCredential write = manager(new FakeKeys())
                .vendForCatalog(azure(TABLE), "lh", List.of(TABLE), true);
        assertEquals("racwdl", query(write.token().get("adls.sas-token")).get("sp"));
    }

    /**
     * Blob 端点（{@code wasbs}）没有目录概念，只能整容器——而且必须留痕。
     *
     * <p>这是「静默放宽」里最隐蔽的一种：票能用、读得了，只是覆盖的范围比「这张表」大。
     * 断言 {@code sr=c} 且**没有** {@code sdd}：容器级带上 {@code sdd} 会被服务端拒绝。
     */
    @Test
    void fallsBackToContainerScopeOnBlobEndpoints() {
        String blob = "wasbs://warehouse@acct.blob.core.windows.net/wh/db.db/orders";
        VendedStorageCredential vended = manager(new FakeKeys()).vend(azure(blob), "t", blob);

        Map<String, String> query = query(vended.token().get("adls.sas-token"));
        assertEquals("c", query.get("sr"), "flat 命名空间没有目录级 SAS");
        assertNull(query.get("sdd"), "容器级带上 sdd 会被拒");
        assertEquals("https", query.get("spr"), "wasbs 是 TLS，只放行 https");
    }

    /** {@code abfs}（无 S）不是 TLS，{@code spr} 必须放行 http，否则引擎用不了。 */
    @Test
    void signsTheProtocolThatMatchesTheScheme() {
        String plain = "abfs://warehouse@acct.dfs.core.windows.net/wh/db.db/orders";
        VendedStorageCredential vended = manager(new FakeKeys()).vend(azure(plain), "t", plain);

        assertEquals("https,http", query(vended.token().get("adls.sas-token")).get("spr"));
        assertEquals("d", query(vended.token().get("adls.sas-token")).get("sr"),
                "abfs 也是 ADLS Gen2，目录级仍然可用");
    }

    /**
     * 同一账户下的多个位置收敛成一张目录级 SAS。
     *
     * <p>下发键每个账户只有一个槽位，签两张会互相覆盖，而覆盖发生在 Map 里、
     * 日志里看不到任何异常。所以先收敛：{@code wh/db/t1} 与 {@code wh/db/t2}
     * 的共同父目录是 {@code wh/db}，签一张覆盖两者。
     */
    @Test
    void collapsesSiblingPathsToTheirCommonDirectory() {
        FakeKeys keys = new FakeKeys();
        VendedStorageCredential vended = manager(keys).vendForCatalog(azure(TABLE), "lh",
                List.of("abfss://warehouse@acct.dfs.core.windows.net/wh/db/t1",
                        "abfss://warehouse@acct.dfs.core.windows.net/wh/db/t2"),
                false);

        assertEquals(1, keys.requests.size(), "同账户只签一次");
        Map<String, String> query = query(vended.token().get("adls.sas-token"));
        assertEquals("2", query.get("sdd"), "wh/db 有两层");
    }

    /**
     * 同账户但跨容器：拒绝这一档，退回定位元数据。
     *
     * <p>SAS 是容器（或容器内目录）级的，一个账户一个槽位——两个容器签不出来。
     * 这里**不抛异常**：抛错会把一个本来能读的 catalog 变成读不了；
     * 退回「只发定位元数据」与接入 provider 之前的行为一致，引擎若自带身份仍能工作。
     */
    @Test
    void declinesWhenOneAccountSpansSeveralContainers() {
        FakeKeys keys = new FakeKeys();
        VendedStorageCredential vended = manager(keys).vendForCatalog(azure(TABLE), "lh",
                List.of("abfss://warehouse@acct.dfs.core.windows.net/wh/db/t",
                        "abfss://archive@acct.dfs.core.windows.net/wh/db/t"),
                false);

        assertTrue(keys.requests.isEmpty(), "签不出来就不该调云");
        assertEquals(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, vended.source());
        assertEquals(VendedStorageCredential.PROVIDER_RELAY, vended.provider());
        assertNull(vended.token().get("adls.sas-token"));
        // 定位元数据照发：引擎靠它去认证
        assertEquals("acct", vended.token().get("azure.account"));
    }

    /**
     * 跨账户各签一张，且**不发** bare 键。
     *
     * <p>bare 键没有后缀，一个值表达不了两个账户；写进 Map 之后是「最后写入者赢」，
     * 结果是 PyIceberg 拿 B 账户的票去读 A 账户的桶，报错与「票签错了」无关。
     */
    @Test
    void signsOneSasPerAccountAndOmitsTheBareKey() {
        FakeKeys keys = new FakeKeys();
        VendedStorageCredential vended = manager(keys).vendForCatalog(azure(TABLE), "lh",
                List.of("abfss://w1@acct1.dfs.core.windows.net/wh/t",
                        "abfss://w2@acct2.dfs.core.windows.net/wh/t"),
                false);

        assertEquals(2, keys.requests.size());
        assertNotNull(vended.token().get("adls.sas-token.acct1.dfs.core.windows.net"));
        assertNotNull(vended.token().get("adls.sas-token.acct2.dfs.core.windows.net"));
        assertFalse(vended.token().containsKey("adls.sas-token"),
                "多账户时 bare 值只可能表达其中一个: " + vended.token().keySet());
        assertFalse(vended.token().containsKey("adls.account-name"));
    }

    // ------------------------------------------------------------------ 退档

    /** 没配服务身份：不签、不调云，行为与接入 provider 之前一致。 */
    @Test
    void declinesWithoutAServiceIdentity() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_AZURE_SAS));
        FakeKeys keys = new FakeKeys();
        DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(
                properties, cipher, List.of(new AzureSasCredentialProvider(properties, cipher, keys)));

        VendedStorageCredential vended = manager.vend(azureWithoutIdentity(TABLE), "t", TABLE);

        assertTrue(keys.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, vended.source());
        assertEquals("acct", vended.token().get("azure.account"), "定位元数据仍然照发");
    }

    /**
     * 服务主体的两半必须成对：只填 {@code clientId} 不填密钥，等于没有身份。
     *
     * <p>半份配置若被当成本地签发档，换票会在 {@code invalid_client} 上失败——
     * 那句话里没有任何线索指向「catalog 少填了一个字段」。
     */
    @Test
    void declinesWhenTheServicePrincipalIsIncomplete() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_AZURE_SAS));
        FakeKeys keys = new FakeKeys();
        DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(
                properties, cipher, List.of(new AzureSasCredentialProvider(properties, cipher, keys)));
        AzureStorageConfigInfo half = new AzureStorageConfigInfo(List.of(TABLE), null,
                "tenant-from-catalog", null, null, null, "client", null, null, null);

        VendedStorageCredential vended = manager.vend(half, "t", TABLE);

        assertTrue(keys.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, vended.source());
    }

    /**
     * 云环境（authority / scope）也来自 catalog：中国云与公有云可以并存。
     *
     * <p>这两项若退回全局配置，一个部署里就只能有一种 Azure 云——
     * 而错误现场是一个 audience 不对的令牌，离「配置放错了层」隔着好几步。
     */
    @Test
    void takesTheCloudEnvironmentFromTheCatalog() {
        FakeKeys keys = new FakeKeys();
        AzureStorageConfigInfo china = new AzureStorageConfigInfo(List.of(TABLE), null,
                "tenant-from-catalog", null, null, null, "client",
                cipher.seal("secret"), "https://login.chinacloudapi.cn",
                "https://storage.azure.cn/.default");

        manager(keys).vend(china, "t", TABLE);

        AzureDelegationRequest request = keys.requests.get(0);
        assertEquals("https://login.chinacloudapi.cn", request.authority());
        assertEquals("https://storage.azure.cn/.default", request.scope());
    }

    /** 位置认不出来时同样退档，而不是签一张猜出来的票。 */
    @Test
    void declinesLocationsThatAreNotAzureUris() {
        FakeKeys keys = new FakeKeys();
        VendedStorageCredential vended = manager(keys)
                .vend(azure("s3://warehouse/wh/db/t"), "t", "s3://warehouse/wh/db/t");

        assertTrue(keys.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, vended.source());
    }

    // ------------------------------------------------------------------ 有效期

    /**
     * 委托密钥的窗口必须**包住** SAS：起点往前放、终点就到 SAS 的到期时刻。
     *
     * <p>反过来的表现是：时钟稍快的机器在 SAS 生效前就拿到它，
     * 服务端回 {@code AuthenticationFailed}（只说签名不对）。
     */
    @Test
    void asksForAKeyThatOutlivesTheTicket() {
        FakeKeys keys = new FakeKeys();
        manager(keys).vend(azure(TABLE), "t", TABLE);

        AzureDelegationRequest request = keys.requests.get(0);
        Instant now = Instant.now();
        assertTrue(request.start().isBefore(now.minus(Duration.ofMinutes(4))),
                "起点至少往前 4 分钟: " + request.start());
        assertTrue(request.expiry().isAfter(now.plus(Duration.ofMinutes(50))),
                "默认 TTL 一小时: " + request.expiry());
        assertEquals("acct", request.account());
        assertEquals("blob.core.windows.net", request.blobHost(), "换密钥的端点在 blob 上，不在 dfs 上");
        assertEquals("tenant-from-catalog", request.tenantId(), "catalog 的 tenantId 优先");
        assertEquals("client", request.clientId());
        assertEquals("https://login.microsoftonline.com", request.authority());
    }

    /** 平台上限 7 天：配了 30 天也只会签 7 天，并且下发的 ttl 与之一致。 */
    @Test
    void capsTheTtlAtThePlatformMaximum() {
        properties.getCredential().setTtlSeconds(30L * 24 * 3600);
        FakeKeys keys = new FakeKeys();
        VendedStorageCredential vended = manager(keys).vend(azure(TABLE), "t", TABLE);

        assertEquals(AzureSasCredentialProvider.MAX_TTL_SECONDS, vended.ttlSeconds());
        long lifetime = Duration.between(keys.requests.get(0).start(), keys.requests.get(0).expiry())
                .toSeconds();
        assertTrue(lifetime <= AzureSasCredentialProvider.MAX_TTL_SECONDS + Duration.ofMinutes(5).toSeconds(),
                "密钥本身也不该超过 7 天: " + lifetime);
    }

    // ------------------------------------------------------------------ 签名本身

    /**
     * 签名必须同时取决于「路径」与「委托密钥」。
     *
     * <p>这两条是「真的在签」的最小证据：不签（或把 sig 写死）时前者不会变；
     * 用错密钥（比如把 base64 当原始字节）时后者不会变。两者都不变而测试只断言
     * 「有 sig 字段」的话，整条链可以一直错到线上。
     */
    @Test
    void signatureDependsOnThePathAndOnTheDelegationKey() {
        String other = "abfss://warehouse@acct.dfs.core.windows.net/wh/db.db/other";
        String first = query(manager(new FakeKeys()).vend(azure(TABLE), "t", TABLE)
                .token().get("adls.sas-token")).get("sig");
        String second = query(manager(new FakeKeys()).vend(azure(other), "t", other)
                .token().get("adls.sas-token")).get("sig");
        assertNotEquals(first, second, "路径不同，签名必须不同");

        String rotated = query(manager(new FakeKeys("another-key-bytes-here-32-bytes.."))
                .vend(azure(TABLE), "t", TABLE).token().get("adls.sas-token")).get("sig");
        assertNotEquals(first, rotated, "换一把委托密钥，签名必须不同");
    }

    // ------------------------------------------------------------------ 位置解析

    @Test
    void locationParsesDfsAndBlobEndpoints() {
        AzureLocation dfs = AzureLocation.parse(TABLE);
        assertNotNull(dfs);
        assertEquals("warehouse", dfs.container());
        assertEquals("acct", dfs.account());
        assertEquals("dfs.core.windows.net", dfs.host());
        assertEquals("wh/db.db/orders", dfs.path());
        assertEquals(3, dfs.depth());
        assertTrue(dfs.hierarchical(), "abfss 就是 ADLS Gen2");
        assertEquals("blob.core.windows.net", dfs.blobHost());

        AzureLocation blob = AzureLocation.parse("wasbs://c@a.blob.core.windows.net/x/y");
        assertNotNull(blob);
        assertFalse(blob.hierarchical(), "wasbs 走 Blob 端点，没有目录");
        assertEquals(2, blob.depth(), "x/y 是两层");
    }

    @Test
    void locationRejectsForeignSchemes() {
        assertNull(AzureLocation.parse("s3://bucket/prefix"));
        assertNull(AzureLocation.parse("gs://bucket/prefix"));
        assertNull(AzureLocation.parse("file:///tmp/wh"));
        assertNull(AzureLocation.parse(null));
        assertNull(AzureLocation.parse("abfss://no-account-host/path"), "缺 @ 认不出来");
    }

    // ------------------------------------------------------------------ 控制台判定

    /** 控制台的「凭据来源」要跟着变：启用 azure-sas 之后不再是「不下发」。 */
    @Test
    void consoleReportsSignedSasOnlyWhenBothTheProviderAndTheIdentityArePresent() {
        AzureStorageConfigInfo withoutIdentity = azureWithoutIdentity(TABLE);
        assertEquals(CatalogCredentialSource.Mode.LEGACY_ENV,
                CatalogCredentialSource.of(withoutIdentity, properties).mode(),
                "默认不启用，且没有服务主体：环境身份那一档");

        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_AZURE_SAS));
        CatalogCredentialSource.Source enabledWithoutIdentity =
                CatalogCredentialSource.of(withoutIdentity, properties);
        assertEquals(CatalogCredentialSource.Mode.INVALID, enabledWithoutIdentity.mode(),
                "启用了却没身份是配置错误，不是「短期签发」");
        assertTrue(enabledWithoutIdentity.detail().contains("clientId"),
                enabledWithoutIdentity.detail());

        CatalogCredentialSource.Source signed =
                CatalogCredentialSource.of(azure(TABLE), properties);
        assertEquals(CatalogCredentialSource.Mode.VENDED, signed.mode());
        assertTrue(signed.detail().contains("没有标准续期"), signed.detail());
    }

    // ------------------------------------------------------------------ 工具

    private DefaultStorageCredentialManager manager(FakeKeys keys) {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_AZURE_SAS));
        return new DefaultStorageCredentialManager(properties, cipher,
                List.of(new AzureSasCredentialProvider(properties, cipher, keys)));
    }

    /** 配齐了服务主体的 catalog（密钥按落库形态密封）。 */
    private AzureStorageConfigInfo azure(String location) {
        return new AzureStorageConfigInfo(List.of(location), null,
                "tenant-from-catalog", null, null, null,
                "client", cipher.seal("secret"), null, null);
    }

    /** 只有定位元数据、没有服务主体的 catalog。 */
    private static AzureStorageConfigInfo azureWithoutIdentity(String location) {
        return new AzureStorageConfigInfo(List.of(location), null,
                "tenant-from-catalog", null, null, null, null, null, null, null);
    }

    /** 把 SAS 查询串解成 Map，用于断言参数而不是「有 sig 就行」。 */
    private static Map<String, String> query(String sas) {
        assertNotNull(sas, "SAS 应当存在");
        Map<String, String> parsed = new LinkedHashMap<>();
        for (String pair : sas.split("&")) {
            int eq = pair.indexOf('=');
            parsed.put(pair.substring(0, eq),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return parsed;
    }

    /** 假委托密钥服务：记录请求，返回一张固定生命周期的密钥。 */
    private static final class FakeKeys implements UserDelegationKeyClient {

        private final List<AzureDelegationRequest> requests = new ArrayList<>();

        private final String value;

        private FakeKeys() {
            this(DELEGATION_KEY);
        }

        private FakeKeys(String value) {
            this.value = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public UserDelegationKey requestKey(AzureDelegationRequest request) {
            requests.add(request);
            return new UserDelegationKey("oid-1", "tid-1",
                    "2026-10-30T00:00:00.0000000Z", "2026-10-31T00:00:00.0000000Z",
                    "b", "2020-12-06", value);
        }
    }
}
