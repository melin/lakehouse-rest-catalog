package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.iceberg.service.IcebergCredentialService;
import io.github.melin.lakehouse.rest.lance.service.LanceStorageOptions;
import io.github.melin.lakehouse.rest.service.AssumeRoleClient;
import io.github.melin.lakehouse.rest.service.AssumeRoleCommand;
import io.github.melin.lakehouse.rest.service.CredentialContext;
import io.github.melin.lakehouse.rest.service.CredentialScope;
import io.github.melin.lakehouse.rest.service.DefaultStorageCredentialManager;
import io.github.melin.lakehouse.rest.service.S3SessionPolicy;
import io.github.melin.lakehouse.rest.service.S3TokenCredentialProvider;
import io.github.melin.lakehouse.rest.service.StsSession;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.Json;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 凭据『真签发』这条链的单元测试：作用域、session policy、四件套 vs 两件套、
 * 默认不启用的等价性、以及续期广告的判据。
 *
 * <p><b>为什么这些断言值得写。</b>本文档反复记的那几条缺口（G2 整桶、G4 缺三个键、
 * G12 键名对不上、G18 静默降级）都有一模一样的特征：不报错、不断言就发现不了，
 * 第一次暴露是在引擎侧 403，而错误信息与原因隔着几层。这里逐条把「输出长什么样」
 * 钉死在测试里，比在文档里写三遍有用。
 *
 * <p>不碰真实 STS：{@link AssumeRoleClient} 注入假实现，断言「传下去的那份命令」。
 * 真实的网络路径靠 e2e 覆盖，单测里假装调用成功只会得到一个「没抛异常」的结论。
 */
class VendingCredentialProviderTests {

    private static final String SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private final RestServerProperties properties = new RestServerProperties();

    private final CredentialCipher cipher = cipher(properties);

    private static CredentialCipher cipher(RestServerProperties properties) {
        properties.getStorage().setCredentialSecretKey(SECRET_KEY);
        return new CredentialCipher(properties);
    }

    // ------------------------------------------------------------------ 作用域

    @Test
    void scopeDerivesPrefixFromTableLocation() {
        CredentialScope scope = CredentialScope.resolve("s3://warehouse/wh/db.db/orders", false);

        assertNotNull(scope);
        assertEquals("warehouse", scope.bucket());
        assertEquals(List.of("wh/db.db/orders"), scope.readPrefixes());
        assertEquals(List.of("arn:aws:s3:::warehouse/wh/db.db/orders/*"), scope.objectArns(scope.readPrefixes()));
        // 尾部斜杠不该产生 "orders//*" 这种永远不匹配的策略
        assertEquals(List.of("arn:aws:s3:::warehouse/wh/db.db/orders/*"),
                scope.objectArns(CredentialScope.resolve("s3://warehouse/wh/db.db/orders/", false)
                        .readPrefixes()));
        assertTrue(scope.writePrefixes().isEmpty(), "只读请求不该带写路径");
    }

    @Test
    void scopeAtBucketRootCannotBeNarrowed() {
        CredentialScope scope = CredentialScope.resolve("s3://warehouse", false);

        assertNotNull(scope);
        assertTrue(scope.bucketRoot());
        // 桶根的前缀条件是 "*"：空串在 StringLike 里只匹配「空前缀」，不是「整桶」
        assertEquals(List.of("*"), scope.listPrefixes());
    }

    @Test
    void scopeIsNullWhenLocationIsNotARecognisedUri() {
        assertNull(CredentialScope.resolve(null, false));
        assertNull(CredentialScope.resolve("/local/path", false));
        // file:// 的 authority 是空的，认不出桶名 —— 不猜，返回 null 交给调用方决定
        assertNull(CredentialScope.resolve("file:///tmp/wh", false));
        // s3a:// 也能认出来：Iceberg 的客户端两种 scheme 都可能在用
        assertNotNull(CredentialScope.resolve("s3a://warehouse/wh/t", false));
    }

    // ------------------------------------------------------------------ session policy

    @Test
    void policyKeepsListBucketResourceAtBucketLevel() {
        String policy = S3SessionPolicy.build(CredentialScope.resolve("s3://wh/db/t", false));

        assertTrue(policy.contains("s3:GetObject"), policy);
        assertTrue(policy.contains("ReadTableObjects"), policy);
        assertTrue(policy.contains("ListTablePrefix"), policy);
        // 最容易写错的一条：ListBucket 的 Resource 必须是桶，前缀只能走 Condition
        assertTrue(policy.contains("\"Resource\":[\"arn:aws:s3:::wh\"]"), policy);
        assertTrue(policy.contains("s3:prefix"), policy);
        assertTrue(policy.contains("db/t/*"), policy);
        assertFalse(policy.contains("WriteTableObjects"), "只读请求不该有写语句: " + policy);
    }

    @Test
    void policyAddsWriteStatementOnlyWhenWriteIsRequested() {
        String policy = S3SessionPolicy.build(CredentialScope.resolve("s3://wh/db/t", true));

        assertTrue(policy.contains("WriteTableObjects"), policy);
        assertTrue(policy.contains("s3:PutObject"), policy);
        // 中断的分片上传不清理会持续计费，而清理它需要这个动作
        assertTrue(policy.contains("s3:AbortMultipartUpload"), policy);
        assertTrue(policy.contains("s3:DeleteObject"), policy);
    }

    // ------------------------------------------------------------------ STS 签发

    @Test
    void stsProviderVendsFourKeysAndAdvertisesItsOwnName() {
        FakeSts sts = new FakeSts();
        DefaultStorageCredentialManager manager = managerWith(new S3TokenCredentialProvider(properties, sts));
        properties.getCredential().setTtlSeconds(3600);
        RequestContext.setPrincipal("alice");
        try {
            VendedStorageCredential vended =
                    manager.vend(awsWithRole("arn:aws:iam::123:role/lakehouse"), "t1",
                            "s3://wh/db/t");
            Map<String, String> token = vended.token();

            assertEquals("AKIA-SESSION", token.get("s3.access-key-id"));
            assertEquals("SECRET-SESSION", token.get("s3.secret-access-key"));
            assertEquals("TOKEN-SESSION", token.get("s3.session-token"));
            assertNotNull(token.get("s3.session-token-expires-at-ms"));
            // Paimon 的引擎键名也要给，否则 paimon-s3 翻译出来的键 Hadoop 不认
            assertEquals("AKIA-SESSION", token.get("s3.access-key"));
            assertEquals("SECRET-SESSION", token.get("s3.secret-key"));
            // 服务端自己的材料一律不下发
            assertFalse(token.containsKey("s3.role-arn"));
            assertFalse(token.containsKey("s3.sts-endpoint"));

            assertEquals(VendedStorageCredential.SOURCE_STS_SESSION, vended.source());
            assertEquals("s3-token", vended.provider());
            assertTrue(vended.shortLived(), "短期凭据必须带非零的 expireTimeInMs");
            assertTrue(vended.hasSecrets());

            AssumeRoleCommand command = sts.last.get();
            assertNotNull(command, "provider 启用时应当真的去换票");
            assertEquals("arn:aws:iam::123:role/lakehouse", command.roleArn());
            assertEquals(3600, command.duration().toSeconds());
            assertEquals("lh-alice-t1", command.roleSessionName());
            assertTrue(command.policy().contains("db/t/*"), command.policy());
        } finally {
            RequestContext.clear();
        }
    }

    @Test
    void stsProviderIsSkippedWhenCatalogCarriesStaticCredentials() {
        FakeSts sts = new FakeSts();
        DefaultStorageCredentialManager manager = managerWith(new S3TokenCredentialProvider(properties, sts));

        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://wh/db/t"),
                null, "arn:aws:iam::123:role/lakehouse", null, null, null, null, "us-east-1",
                null, null, null, null, null, null, "AKIA-STATIC", cipher.seal("SECRET-STATIC"));

        VendedStorageCredential vended = manager.vend(config, "t1", "s3://wh/db/t");

        assertNull(sts.last.get(), "显式指定的钥匙不该被服务端拿去换票");
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertEquals("AKIA-STATIC", vended.token().get("s3.access-key-id"));
        // 静态凭据绝不能编 session token：假 token 会被 S3 以 InvalidToken 拒绝每一次请求
        assertFalse(vended.token().containsKey("s3.session-token"));
        assertEquals(0L, vended.expireTimeInMs(), "静态凭据的 expireTimeInMs 恒为 0");
        assertFalse(vended.shortLived());
    }

    @Test
    void stsProviderIsSkippedWhenStsUnavailable() {
        FakeSts sts = new FakeSts();
        DefaultStorageCredentialManager manager = managerWith(new S3TokenCredentialProvider(properties, sts));

        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://wh/db/t"),
                null, "arn:aws:iam::123:role/lakehouse", null, null, null, null, "us-east-1",
                null, null, Boolean.TRUE, null, null, null, null, null);

        VendedStorageCredential vended = manager.vend(config, "t1", "s3://wh/db/t");

        assertNull(sts.last.get());
        assertEquals(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, vended.source());
        assertFalse(vended.token().containsKey("s3.access-key-id"));
        assertEquals("us-east-1", vended.token().get("s3.region"), "定位配置仍要下发");
    }

    @Test
    void disabledProviderChainKeepsTheRelayBehaviour() {
        FakeSts sts = new FakeSts();
        // 不写 providers：默认空链。provider bean 在容器里，但没启用
        DefaultStorageCredentialManager manager =
                new DefaultStorageCredentialManager(properties, cipher, List.of(new S3TokenCredentialProvider(properties, sts)));
        properties.getStorage().getAws().setAccessKey("AKIA-CONFIG");
        properties.getStorage().getAws().setSecretKey("SECRET-CONFIG");

        VendedStorageCredential vended =
                manager.vend(awsWithRole("arn:aws:iam::123:role/lakehouse"), "t1", "s3://wh/db/t");

        assertNull(sts.last.get(), "默认配置下不该有任何远程调用");
        assertEquals(VendedStorageCredential.PROVIDER_RELAY, vended.provider());
        assertEquals(VendedStorageCredential.SOURCE_CONFIGURATION, vended.source());
        assertEquals("AKIA-CONFIG", vended.token().get("s3.access-key-id"));
        assertEquals(0L, vended.expireTimeInMs());
    }

    @Test
    void unknownProviderNameFailsAtStartup() {
        properties.getCredential().setProviders(List.of("s3-tokn"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new DefaultStorageCredentialManager(properties, cipher, List.of()));

        assertTrue(failure.getMessage().contains("s3-tokn"), failure.getMessage());
        assertTrue(failure.getMessage().contains("unknown provider"), failure.getMessage());
    }

    @Test
    void providerWithoutRoleArnDeclinesInsteadOfSigning() {
        FakeSts sts = new FakeSts();
        DefaultStorageCredentialManager manager = managerWith(new S3TokenCredentialProvider(properties, sts));

        VendedStorageCredential vended =
                manager.vend(awsWithRole(null), "t1", "s3://wh/db/t");

        assertNull(sts.last.get());
        assertEquals(VendedStorageCredential.PROVIDER_RELAY, vended.provider());
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
    }

    // ------------------------------------------------------------------ 缓存比例

    @Test
    void cacheKeepsShortLivedCredentialsForOnlyAFractionOfTheirLifetime() {
        io.github.melin.lakehouse.rest.service.StorageCredentialCache cache =
                new io.github.melin.lakehouse.rest.service.StorageCredentialCache(properties);
        long now = System.currentTimeMillis();
        long oneHour = 3600L;

        // 8 分钟前签的：缓存期 9 分钟，还剩 1 分钟 → 命中
        VendedStorageCredential fresh = new VendedStorageCredential(Map.of("k", "v"), oneHour,
                io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType.S3,
                VendedStorageCredential.SOURCE_STS_SESSION,
                VendedStorageCredential.PROVIDER_S3_TOKEN, now - 8 * 60_000L + oneHour * 1000L);
        cache.put("a", fresh, now - 8 * 60_000L);
        assertTrue(cache.get("a").isPresent(), "8 分钟时仍在缓存期内");

        // 10 分钟前签的：缓存期已过 → 未命中（但凭据本身还没过期）
        VendedStorageCredential older = new VendedStorageCredential(Map.of("k", "v"), oneHour,
                io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType.S3,
                VendedStorageCredential.SOURCE_STS_SESSION,
                VendedStorageCredential.PROVIDER_S3_TOKEN, now - 10 * 60_000L + oneHour * 1000L);
        cache.put("b", older, now - 10 * 60_000L);
        assertTrue(cache.get("b").isEmpty(), "10 分钟时应已按比例失效");
    }

    @Test
    void cacheKeepsStaticCredentialsForTheFullTtl() {
        io.github.melin.lakehouse.rest.service.StorageCredentialCache cache =
                new io.github.melin.lakehouse.rest.service.StorageCredentialCache(properties);
        long now = System.currentTimeMillis();

        VendedStorageCredential staticCredential = new VendedStorageCredential(Map.of("k", "v"), 3600L,
                io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType.S3,
                VendedStorageCredential.SOURCE_CONFIGURATION);
        cache.put("s", staticCredential, now - 10 * 60_000L);

        assertTrue(cache.get("s").isPresent(), "静态凭据没有「快过期」，不该按比例缩短");
        assertEquals(now - 10 * 60_000L + 3600_000L, cache.get("s").orElseThrow().expiresAtMillis());
    }

    // ------------------------------------------------------------------ 续期广告

    @Test
    void refreshEndpointIsAdvertisedOnlyForShortLivedCredentials() {
        // 三个依赖都不参与 refreshConfig 的路径拼装，传 null 即可
        IcebergCredentialService service = new IcebergCredentialService(null, null, null);
        List<IcebergDtos.StorageCredential> entries =
                List.of(new IcebergDtos.StorageCredential("s3://wh/db/t", Map.of("s3.region", "us-east-1")));

        IcebergCredentialService.Delegated shortLived =
                new IcebergCredentialService.Delegated(entries, true);
        Map<String, String> config = service.refreshConfig("lh", List.of("db"), "orders", shortLived);
        assertNotNull(config);
        assertEquals("/v1/lh/namespaces/db/tables/orders/credentials",
                config.get("client.refresh-credentials-endpoint"));

        IcebergCredentialService.Delegated relay = new IcebergCredentialService.Delegated(entries, false);
        assertNull(service.refreshConfig("lh", List.of("db"), "orders", relay),
                "转发档没有可续的东西，不该广告续期端点");
        assertNull(service.refreshConfig("lh", List.of("db"), "orders", null));
    }

    @Test
    void multiLevelNamespaceIsEncodedWithTheSpecSeparator() {
        // 三个依赖都不参与 refreshConfig 的路径拼装，传 null 即可
        IcebergCredentialService service = new IcebergCredentialService(null, null, null);
        IcebergCredentialService.Delegated delegated = new IcebergCredentialService.Delegated(
                List.of(new IcebergDtos.StorageCredential("s3://wh/db/t", Map.of())), true);

        Map<String, String> config =
                service.refreshConfig("lh", List.of("a", "b"), "t", delegated);

        // 层级之间是 %1F，与控制器里 NamespacePaths.parse 的分隔符同源
        assertEquals("/v1/lh/namespaces/a%1Fb/tables/t/credentials",
                config.get("client.refresh-credentials-endpoint"));
    }

    @Test
    void loadCredentialsResponseCarriesBothFieldNames() {
        List<IcebergDtos.StorageCredential> entries =
                List.of(new IcebergDtos.StorageCredential("s3://wh/db/t", Map.of("s3.access-key-id", "AK")));

        String json = Json.write(new IcebergDtos.LoadCredentialsResponse(entries));

        assertTrue(json.contains("\"storage-credentials\""), json);
        assertTrue(json.contains("\"credentials\""), json);
    }

    // ------------------------------------------------------------------ Lance 键名

    @Test
    void lanceStorageOptionsUseAwsKeyNames() {
        Map<String, String> options = LanceStorageOptions.fromS3(Map.of(
                "s3.access-key-id", "AK",
                "s3.secret-access-key", "SK",
                "s3.session-token", "TOKEN",
                "s3.region", "us-east-1",
                "s3.endpoint", "http://minio.internal:9000",
                "s3.path-style-access", "true"));

        assertEquals("AK", options.get("aws_access_key_id"));
        assertEquals("SK", options.get("aws_secret_access_key"));
        assertEquals("TOKEN", options.get("aws_session_token"));
        assertEquals("us-east-1", options.get("aws_region"));
        assertEquals("http://minio.internal:9000", options.get("aws_endpoint"));
        assertEquals("true", options.get("allow_http"), "明文端点要显式放行");
        assertFalse(options.containsKey("s3.access-key-id"), "不该残留 s3.* 键: " + options);
    }

    @Test
    void lanceStorageOptionsPassThroughNonObjectStoreKeys() {
        // FILE 存储的 token 是自包含字段，与对象存储无关，不能被翻译吃掉
        Map<String, String> options = LanceStorageOptions.fromS3(Map.of(
                "accessKeyId", "PAIMON-t1",
                "expiration", "2026-10-29T00:00:00Z"));

        assertEquals("PAIMON-t1", options.get("accessKeyId"));
        assertEquals("2026-10-29T00:00:00Z", options.get("expiration"));
    }

    // ------------------------------------------------------------------ 工具

    private DefaultStorageCredentialManager managerWith(S3TokenCredentialProvider provider) {
        properties.getCredential().setProviders(List.of(provider.name()));
        return new DefaultStorageCredentialManager(properties, cipher, List.of(provider));
    }

    private static AwsStorageConfigInfo awsWithRole(String roleArn) {
        return new AwsStorageConfigInfo(List.of("s3://wh/db/t"), null, roleArn, null, null, null, null,
                "us-east-1", null, null, null, null, null, null, null, null);
    }

    /** 假 STS：只记录最后一次命令，返回一个固定寿命的会话。 */
    private static final class FakeSts implements AssumeRoleClient {

        private final AtomicReference<AssumeRoleCommand> last = new AtomicReference<>();

        @Override
        public StsSession assumeRole(AssumeRoleCommand command) {
            last.set(command);
            return new StsSession("AKIA-SESSION", "SECRET-SESSION", "TOKEN-SESSION",
                    Instant.now().plus(Duration.ofSeconds(command.duration().toSeconds())));
        }
    }

    /** {@link CredentialContext} 的会话名规整：STS 的字符集不含 {@code /} 与 {@code :}。 */
    @Test
    void roleSessionNameIsSanitisedToTheStsCharacterSet() {
        CredentialContext context = new CredentialContext(awsWithRole("arn:aws:iam::1:role/r"),
                "t1", "s3://wh/db/t", "user:alice@corp/x", false);

        String name = context.roleSessionName();

        assertTrue(name.matches("[\\w+=,.@-]{2,64}"), name);
        assertFalse(name.contains(":"), name);
        assertFalse(name.contains("/"), name);
    }
}
