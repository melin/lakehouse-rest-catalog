package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.TencentCosStorageConfigInfo;
import io.github.melin.lakehouse.rest.service.DefaultStorageCredentialManager;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.CredentialManagerType;
import io.github.melin.lakehouse.rest.support.FileIoType;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 存储凭据解析与缓存的纯单元测试，不起 Spring 上下文。
 *
 * <p>为什么把这一层单独测：HTTP 层的测试只能看到「最后返回的键有没有配对」，
 * 而真正容易写错的是取值优先级与边界——{@code stsUnavailable} 时该不该给密钥、
 * 哪些服务端专用地址不能发给客户端、某一种云的身份能不能当数据面凭据下发。
 * 这些都在这里逐条钉住，HTTP 层就只负责证明「这层被接上了」。
 *
 * <p><b>所有凭据都来自 {@code storageConfigInfo}。</b>{@code lakehouse.rest.storage}
 * 里已经没有任何对象存储密钥（见 {@code RestServerProperties.Storage} 的类注释），
 * 所以本类的每个用例都是「给一份 catalog 配置，看下发结果」——没有第二条来源可对照。
 * 这恰好让断言更直接：某把钥匙没出现，就只有一种解释。
 *
 * <p>用真实的可变对象构造 {@code RestServerProperties} 而不是 mock：
 * 这些配置项就是普通的可变对象，直接设值比 mock 掉取值链更能暴露绑定层面的问题。
 */
class StorageCredentialResolutionTests {

    /** base64 的 32 字节：凭据在库里是密文，本类要用真密钥把它解出来。 */
    private static final String CREDENTIAL_SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private final RestServerProperties properties = new RestServerProperties();

    private final CredentialCipher cipher = configuredCipher(properties);

    private final DefaultStorageCredentialManager manager =
            new DefaultStorageCredentialManager(properties, cipher, List.of());

    /**
     * 配好密钥的 cipher。
     *
     * <p>不配密钥时 {@code seal} 会拒绝——这正是写路径想要的失败方向，
     * 但本类测的是下发优先级，需要能把凭据密封进配置里。密钥与密文的对应关系
     * 由 {@code CredentialCipherTests} 单独守住，这里只把它当工具用。
     */
    private static CredentialCipher configuredCipher(RestServerProperties properties) {
        properties.getStorage().setCredentialSecretKey(CREDENTIAL_SECRET_KEY);
        return new CredentialCipher(properties);
    }

    private StorageCredentialCache cache() {
        return new StorageCredentialCache(properties);
    }

    // ------------------------------------------------------------------ S3

    /**
     * S3 的密钥必须同时以规格键名与引擎键名下发。
     *
     * <p>Polaris / Iceberg 认 {@code s3.access-key-id}，Paimon 认
     * {@code s3.access-key}——Paimon 的 {@code paimon-s3} 会把 {@code s3.}
     * 前缀整体换成 Hadoop 的 {@code fs.s3a.}，再镜像成
     * {@code fs.s3a.access.key} / {@code fs.s3a.secret.key}，
     * 而 {@code fs.s3a.access-key-id} 不在镜像表里。
     *
     * <p>只给规格键名的后果不是报错，是引擎静默拿不到密钥、退回默认凭据链，
     * 报出来的 {@code NoAuthWithAWSException} 还被插件类加载器挡在 driver 之外，
     * 最后只剩一句 {@code UnknownReason}。所以这条断言针对的是「两边都能用」，
     * 而不是「回了一个值」。
     */
    @Test
    void s3VendsCredentialsUnderBothSpecAndEngineKeyNames() {
        VendedStorageCredential vended = manager.vend(
                s3("s3://bucket-a/prefix", "AKIA-CATALOG", cipher.seal("SECRET-CATALOG")),
                "t1", null);

        Map<String, String> token = vended.token();
        assertEquals("AKIA-CATALOG", token.get("s3.access-key-id"));
        assertEquals("SECRET-CATALOG", token.get("s3.secret-access-key"));
        assertEquals("AKIA-CATALOG", token.get("s3.access-key"));
        assertEquals("SECRET-CATALOG", token.get("s3.secret-key"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertTrue(vended.hasSecrets(), "填了密钥就算「真的下发了密钥」");
    }

    @Test
    void s3FallsBackToEnvironmentCredentialChainWhenNothingConfigured() {
        VendedStorageCredential vended = manager.vend(s3("s3://bucket-a/prefix"), "t1", null);

        assertFalse(vended.token().containsKey("s3.access-key-id"));
        assertFalse(vended.token().containsKey("s3.secret-access-key"));
        assertFalse(vended.token().containsKey("s3.access-key"));
        assertFalse(vended.token().containsKey("s3.secret-key"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
    }

    @Test
    void s3DoesNotVendSecretsWhenStsUnavailable() {
        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://bucket-a/prefix"), null,
                null, null, null, null, null, "us-east-1", null, null,
                Boolean.TRUE, null, null, null, null, null, null);

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertFalse(vended.token().containsKey("s3.access-key-id"));
        assertEquals(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, vended.source());
        // 定位配置仍要下发：不下发密钥不等于让引擎不知道连哪里
        assertEquals("us-east-1", vended.token().get("s3.region"));
    }

    /**
     * 服务端专用地址与 assume 角色材料不得出现在下发结果里。
     *
     * <p>规格写明 {@code endpointInternal} 客户端永远看不到；{@code roleArn} 系列
     * 是服务端去换临时凭据的材料。发出去轻则引擎连到内网地址，
     * 重则把内网拓扑与账户结构泄露给调用方。
     */
    @Test
    void s3DoesNotLeakServerSideOnlyFields() {
        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://bucket-a/prefix"), null,
                "arn:aws:iam::123:role/r", "ext-id", "arn:aws:iam::123:user/u",
                List.of("arn:aws:kms:key/1"), List.of("arn:aws:kms:key/2"), "us-east-1",
                "https://s3.example.com", "https://sts.example.com", null, null,
                "https://s3.internal.example.com", Boolean.TRUE, null, null, null);

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertFalse(vended.token().containsKey("s3.sts-endpoint"));
        assertFalse(vended.token().containsKey("s3.endpoint-internal"));
        assertFalse(vended.token().containsKey("s3.role-arn"));
        assertFalse(vended.token().values().contains("https://s3.internal.example.com"));
        assertFalse(vended.token().values().contains("https://sts.example.com"));
        // 客户端该看到的三项
        assertEquals("us-east-1", vended.token().get("s3.region"));
        assertEquals("https://s3.example.com", vended.token().get("s3.endpoint"));
        assertEquals("true", vended.token().get("s3.path-style-access"));
    }

    /**
     * {@code stsUnavailable} 不阻断 catalog 上那把密钥的下发。
     *
     * <p>这条是「兼容 S3 的对象存储能不能用起来」的关键：MinIO / Ceph / Ozone / FlashBlade
     * 这类存储没有 STS，配置里照惯例会写 {@code stsUnavailable: true}。
     * 若它顺手也把密钥拦掉，用户填了 AK/SK 却收不到，现象是「功能好像没生效」——
     * 而 {@code stsUnavailable} 表达的是「服务端不去向云申请临时凭据」，
     * 与「把 catalog 上那份长期密钥发给引擎」是两件事。定位配置照旧下发。
     */
    @Test
    void s3StaticCredentialsSurviveStsUnavailable() {
        AwsStorageConfigInfo config = new AwsStorageConfigInfo(List.of("s3://minio-bucket/wh"), null,
                null, null, null, null, null, "us-east-1", "http://minio.internal:9000", null,
                Boolean.TRUE, null, null, Boolean.TRUE, null,
                "minioadmin", cipher.seal("minioadmin-secret"));

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals("minioadmin", vended.token().get("s3.access-key-id"));
        assertEquals("minioadmin-secret", vended.token().get("s3.secret-access-key"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source(),
                "真的发了密钥，来源就得说「静态凭据」——它是排障时唯一能看出走了哪一档的字段");
        assertEquals("http://minio.internal:9000", vended.token().get("s3.endpoint"));
        assertEquals("true", vended.token().get("s3.path-style-access"));
    }

    // ------------------------------------------------------------------ 密钥的读写

    /**
     * 密文解不开时直接失败，不退回别的东西。
     *
     * <p>退回的后果是把「这个 catalog 该用哪把钥匙」从调用方明确指定降级成部署方默认——
     * 一次加密密钥轮换会静默变成权限扩大，而且真因（换过密钥）被掩盖成「权限不对」。
     */
    @Test
    void credentialsFailLoudlyWhenTheyCannotBeDecrypted() {
        RestServerProperties other = new RestServerProperties();
        other.getStorage().setCredentialSecretKey("AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA=");
        AwsStorageConfigInfo config = s3("s3://bucket-a/prefix",
                "AKIA-CATALOG", new CredentialCipher(other).seal("SECRET-CATALOG"));

        ApiException failure = assertThrows(ApiException.class, () -> manager.vend(config, "t1", null));

        assertEquals(500, failure.getStatus());
        assertTrue(failure.getMessage().contains("credential-secret-key"), failure.getMessage());
    }

    /** 明文混进配置里（历史数据或手改）同样拒绝，不把它当密钥发出去。 */
    @Test
    void credentialsRejectUnsealedSecrets() {
        AwsStorageConfigInfo config = s3("s3://bucket-a/prefix", "AKIA-CATALOG", "plaintext-secret");

        assertEquals(500, assertThrows(ApiException.class, () -> manager.vend(config, "t1", null)).getStatus());
    }

    /**
     * 取凭据的读取点与脱敏点分工：对外那份两个字段都不给真值，内部那份才解得出明文。
     *
     * <p>AK ID 也从「原样保留」改成了打码（P1-8）。理由不是它本身保密，而是它一旦回显，
     * 「这个 catalog 用哪把钥匙」就多了一条不需要凭据的读取路径；而它与 SK 是成对的，
     * 打一半等于给了一套可以用来猜另一半的线索。
     */
    @Test
    void maskingReplacesTheKeyIdAndDropsTheSecret() {
        AwsStorageConfigInfo config = s3("s3://bucket-a/prefix",
                "AKIA-CATALOG", cipher.seal("SECRET-CATALOG"));

        AwsStorageConfigInfo masked = (AwsStorageConfigInfo) StorageConfigs.withoutSecrets(config);

        assertEquals(StorageConfigs.MASKED, masked.accessKeyId(),
                "对外那份的 accessKeyId 必须是打码值，且它就是回写哨兵");
        assertNull(masked.secretAccessKey(), "对外那份不应带出密文");
        assertEquals(List.of("s3://bucket-a/prefix"), masked.allowedLocations(), "脱敏不应动其他字段");
        // 内部那份（库中原样）才拿得到真值
        StorageConfigs.StorageSecrets stored = StorageConfigs.secrets(config, cipher);
        assertEquals("SECRET-CATALOG", stored.secret());
        assertEquals("AKIA-CATALOG", stored.id());
        // 打码不影响「有没有配凭据」的判断：否则写路径会把已配的当成没配
        assertTrue(StorageConfigs.hasCredentials(masked));
    }

    /**
     * 打码那份配置不许进下发通道。
     *
     * <p>它只会出现在一种情况下：某处代码把「对外响应」当成了「内部配置」。放过去的表现是
     * 引擎拿 {@code ******} 当 AK 去签名，报错落在存储侧的认证失败上，与真正的原因
     * （传错了哪一份）完全脱钩；在这里失败则一眼看出。
     */
    @Test
    void vendingAMaskedConfigFailsLoudly() {
        AwsStorageConfigInfo masked = (AwsStorageConfigInfo) StorageConfigs.withoutSecrets(
                s3("s3://bucket-a/prefix", "AKIA-CATALOG", cipher.seal("SECRET-CATALOG")));

        ApiException failure = assertThrows(ApiException.class,
                () -> StorageConfigs.secrets(masked, cipher));

        assertEquals(500, failure.getStatus());
        assertTrue(failure.getMessage().contains("masking placeholder"), failure.getMessage());
    }

    // ------------------------------------------------------------------ 各家键族

    /** OBS 用自己那套键名族下发凭据，不能串到 S3 或 OSS 的键上。 */
    @Test
    void obsVendsCredentialsWithItsOwnKeyFamily() {
        VendedStorageCredential vended = manager.vend(
                obs("obs://analytics-bucket/warehouse/", "obs.cn-north-4.myhuaweicloud.com",
                        "OBS-AK", cipher.seal("OBS-SK")),
                "t1", null);

        assertEquals("OBS-AK", vended.token().get("fs.obs.access.key"));
        assertEquals("OBS-SK", vended.token().get("fs.obs.secret.key"));
        assertEquals("obs.cn-north-4.myhuaweicloud.com", vended.token().get("fs.obs.endpoint"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertFalse(hasAnyKeyStartingWith(vended.token(), "s3."), "OBS 不应下发 s3.* 键: " + vended.token());
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.oss."), "OBS 不应下发 OSS 键: " + vended.token());
    }

    /**
     * 长期凭据不该带出一个空的会话令牌。
     *
     * <p>catalog 上的四家对象存储都只有 AK/SK，令牌没有来源；发一个 {@code null} 值的
     * {@code fs.obs.session.token} 会让引擎以为自己在用临时凭据。
     */
    @Test
    void obsOmitsSessionTokenForLongTermCredentials() {
        VendedStorageCredential vended = manager.vend(
                obs("obs://analytics-bucket/warehouse", null, "OBS-AK", cipher.seal("OBS-SK")),
                "t1", null);

        assertFalse(vended.token().containsKey("fs.obs.session.token"));
        assertEquals("OBS-AK", vended.token().get("fs.obs.access.key"));
    }

    /** OSS 同理，且键名是驼峰那一套。 */
    @Test
    void ossVendsCredentialsWithItsOwnKeyFamily() {
        VendedStorageCredential vended = manager.vend(
                oss("oss://analytics-bucket/warehouse/", "oss-cn-hangzhou.aliyuncs.com",
                        "OSS-AK", cipher.seal("OSS-SK")),
                "t1", null);

        assertEquals("OSS-AK", vended.token().get("fs.oss.accessKeyId"));
        assertEquals("OSS-SK", vended.token().get("fs.oss.accessKeySecret"));
        assertEquals("oss-cn-hangzhou.aliyuncs.com", vended.token().get("fs.oss.endpoint"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertFalse(vended.token().containsKey("fs.oss.securityToken"),
                "长期凭据没有安全令牌: " + vended.token());
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.obs."), "OSS 不应下发 OBS 键: " + vended.token());
    }

    /**
     * 一家的密钥不会被另一家的 catalog 拿到。
     *
     * <p>把密钥放到 catalog 上之后这一条变得更加直接：有没有凭据由<b>这份配置</b>
     * 决定，与部署里别的 catalog 填了什么无关。
     */
    @Test
    void oneCatalogsCredentialsAreInvisibleToAnother() {
        manager.vend(obs("obs://analytics-bucket/warehouse", null,
                "OBS-AK", cipher.seal("OBS-SK")), "t1", null);

        VendedStorageCredential ossCatalog = manager.vend(
                oss("oss://analytics/warehouse", null, null, null), "t1", null);

        assertFalse(ossCatalog.hasSecrets());
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, ossCatalog.source());
    }

    /** 声明不下发凭据时端点仍要发：引擎得知道连哪里，它可能自带委托身份。 */
    @Test
    void obsKeepsEndpointWhenStsUnavailable() {
        VendedStorageCredential vended = manager.vend(
                new HuaweiObsStorageConfigInfo(List.of("obs://analytics/warehouse"), null,
                        "obs.cn-north-4.myhuaweicloud.com", Boolean.TRUE, null, null),
                "t1", null);

        assertFalse(vended.token().containsKey("fs.obs.access.key"));
        assertFalse(vended.hasSecrets());
        assertEquals(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, vended.source());
        assertEquals("obs.cn-north-4.myhuaweicloud.com", vended.token().get("fs.obs.endpoint"));
    }

    @Test
    void obsFallsBackToEnvironmentWhenNothingConfigured() {
        VendedStorageCredential vended = manager.vend(
                obs("obs://analytics/warehouse", "obs.cn-north-4.myhuaweicloud.com", null, null),
                "t1", null);

        assertFalse(vended.token().containsKey("fs.obs.access.key"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
        // 没有凭据不等于没有端点
        assertEquals("obs.cn-north-4.myhuaweicloud.com", vended.token().get("fs.obs.endpoint"));
    }

    // ------------------------------------------------------------------ COS

    /**
     * COS 的键名族是 {@code fs.cosn.userinfo.secretId} / {@code secretKey}——**大小写敏感**。
     *
     * <p>这条断言的是键名本身，不是「没抛异常」。Paimon 的 {@code COSNFileIO} 会把键名
     * 小写化送进 Hadoop {@code Configuration}，再用一张只含这两个键的还原表把
     * {@code secretid} 映射回 {@code secretId}；写错一个字母不会报错，
     * 只会让 AK 变成空串。
     *
     * <p>顺带守住定位三项：区域与端点是 COSN {@code requiredOptions} 要求的东西，
     * {@code useHttps} 则是它那个「默认明文」缺省值的唯一纠正点。
     */
    @Test
    void cosVendsItsOwnKeyFamilyWithExactCase() {
        VendedStorageCredential vended = manager.vend(
                cos("cosn://analytics-1250000000/warehouse", "cos.ap-guangzhou.myqcloud.com",
                        "ap-guangzhou", "COS-AK", cipher.seal("COS-SK")),
                "t1", null);

        assertEquals("COS-AK", vended.token().get("fs.cosn.userinfo.secretId"));
        assertEquals("COS-SK", vended.token().get("fs.cosn.userinfo.secretKey"));
        assertEquals("cos.ap-guangzhou.myqcloud.com", vended.token().get("fs.cosn.bucket.endpoint_suffix"));
        assertEquals("ap-guangzhou", vended.token().get("fs.cosn.bucket.region"));
        assertEquals("true", vended.token().get("fs.cosn.useHttps"));
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source());
        assertTrue(vended.hasSecrets());

        // 三家云的键族互不可见，S3 那一套也不行
        assertFalse(hasAnyKeyStartingWith(vended.token(), "s3."));
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.obs."));
        assertFalse(hasAnyKeyStartingWith(vended.token(), "fs.oss."));
    }

    /**
     * {@code useHttps} 缺省为 {@code true}，只有显式 {@code false} 才发 {@code false}。
     *
     * <p>COSN 自己的缺省是 {@code false}（明文），不配置就走 HTTP。本工程把默认值翻过来，
     * 同时留一个显式关闭的出口（私有化部署的 COS 兼容实现可能只有 HTTP）。
     * 这两种情况都要有用例，否则「默认安全」这件事只是注释里的一句话。
     */
    @Test
    void cosDefaultsToHttpsUnlessExplicitlyDisabled() {
        VendedStorageCredential byDefault = manager.vend(
                cos("cosn://analytics-1250000000/warehouse", null, null, null, null), "t1", null);
        assertEquals("true", byDefault.token().get("fs.cosn.useHttps"));

        VendedStorageCredential optedOut = manager.vend(
                new TencentCosStorageConfigInfo(List.of("cosn://analytics-1250000000/warehouse"),
                        null, null, "ap-guangzhou", "1250000000", Boolean.FALSE, null, null, null),
                "t1", null);
        assertEquals("false", optedOut.token().get("fs.cosn.useHttps"));
    }

    /**
     * 不下发任何会话令牌，也不下发凭据提供器类名。
     *
     * <p>这条是**反悔用例**：设计稿里 G18 曾要求临时档发
     * {@code fs.cosn.userinfo.sessionToken} 并把
     * {@code fs.cosn.credentials.provider} 指到
     * {@code org.apache.hadoop.fs.auth.SessionTokenCredentialProvider}。
     * 核对 paimon-cosn 2.1.0 内置的 hadoop-cos 后确认：{@code CosNConfigKeys} 里没有
     * 会话令牌这一项，认证包里也只有 {@code SimpleCredentialsProvider} 与
     * {@code EnvironmentVariableCredentialsProvider}，那个类名根本不存在。
     * 发了就是「看起来配了、实际没人读」，所以这里把它钉住。
     */
    @Test
    void cosNeverVendsASessionTokenOrAProviderClass() {
        VendedStorageCredential vended = manager.vend(
                cos("cosn://analytics-1250000000/warehouse", null, "ap-guangzhou",
                        "COS-AK", cipher.seal("COS-SK")),
                "t1", null);

        assertFalse(vended.token().containsKey("fs.cosn.userinfo.sessionToken"));
        assertFalse(vended.token().containsKey("fs.cosn.credentials.provider"));
        assertFalse(vended.token().containsValue("org.apache.hadoop.fs.auth.SessionTokenCredentialProvider"));
    }

    /** 声明不下发凭据时定位三项仍要发：引擎可能用自己的 CVM 委托身份。 */
    @Test
    void cosKeepsLocationKeysWhenStsUnavailable() {
        VendedStorageCredential vended = manager.vend(
                new TencentCosStorageConfigInfo(List.of("cosn://analytics-1250000000/warehouse"),
                        null, "cos.ap-guangzhou.myqcloud.com", "ap-guangzhou", "1250000000",
                        null, Boolean.TRUE, null, null),
                "t1", null);

        assertFalse(vended.hasSecrets());
        assertEquals(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, vended.source());
        assertEquals("cos.ap-guangzhou.myqcloud.com", vended.token().get("fs.cosn.bucket.endpoint_suffix"));
        assertEquals("ap-guangzhou", vended.token().get("fs.cosn.bucket.region"));
        assertFalse(vended.token().containsKey("fs.cosn.userinfo.secretId"));
    }

    @Test
    void cosFallsBackToEnvironmentWhenNothingConfigured() {
        VendedStorageCredential vended = manager.vend(
                cos("cosn://analytics-1250000000/warehouse", null, "ap-guangzhou", null, null),
                "t1", null);

        assertFalse(vended.token().containsKey("fs.cosn.userinfo.secretId"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
        // 没有凭据不等于没有定位信息
        assertEquals("ap-guangzhou", vended.token().get("fs.cosn.bucket.region"));
    }

    // ------------------------------------------------------------------ Azure

    @Test
    void azureReturnsTenantAccountAndHierarchicalFlag() {
        AzureStorageConfigInfo config = azure("abfss://container@myaccount.dfs.core.windows.net/prefix",
                "tenant-1", null, null);

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals(StorageType.AZURE, vended.storageType());
        assertEquals("tenant-1", vended.token().get("azure.tenant-id"));
        assertEquals("myaccount", vended.token().get("azure.account"));
        assertEquals("true", vended.token().get("azure.hierarchical"));
        assertEquals(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, vended.source());
        // 没有账户密钥就不该出现任何凭据类键
        assertFalse(vended.hasSecrets());
    }

    /**
     * 服务主体的两半**永远不下发**，哪怕它就写在 catalog 上。
     *
     * <p>{@code clientId} / {@code clientSecret} 是「服务端去换委托密钥」的身份，
     * 不是数据面凭据：引擎拿它既连不上存储、也换不了票。下发它还会让来源字段
     * 写成「静态凭据」，看上去一切正常——这是本类最想拦住的一类错误。
     */
    @Test
    void azureNeverVendsTheServicePrincipal() {
        AzureStorageConfigInfo config = azure(
                "abfss://container@myaccount.dfs.core.windows.net/prefix",
                "tenant-1", "app-client-id", cipher.seal("app-client-secret"));

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals(VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY, vended.source(),
                "没有启用 azure-sas 时只发定位元数据");
        assertFalse(vended.hasSecrets());
        assertFalse(vended.token().containsValue("app-client-id"), vended.token().toString());
        assertFalse(vended.token().containsValue("app-client-secret"), vended.token().toString());
    }

    /**
     * 账户名只在认得出来的地址上解出。
     *
     * <p>自定义域名（内网代理、私有云）解不出账户名，此时不给这个键而不是给一个猜的名字——
     * 错的账户名会让引擎去认证一个不存在的账户，报出来的错与真正原因隔着好几层。
     */
    @Test
    void azureAccountIsParsedOnlyForKnownEndpointSuffixes() {
        assertEquals("myaccount", manager.vend(azure(
                "wasbs://container@myaccount.blob.core.windows.net/prefix", "tenant-1", null, null),
                "t1", null).token().get("azure.account"));

        assertEquals("acct2", manager.vend(azure(
                "abfss://container@acct2.dfs.core.windows.net", "tenant-1", null, null),
                "t1", null).token().get("azure.account"));

        assertFalse(manager.vend(azure(
                "abfss://container@storage.internal.example/prefix", "tenant-1", null, null),
                "t1", null).token().containsKey("azure.account"));
    }

    // ------------------------------------------------------------------ GCS

    /**
     * GCS 的 {@code serviceAccountJson} 是**服务端换票用的私钥**，一个字节都不下发。
     *
     * <p>没有启用 {@code gcs-downscope} 时只发定位用的 {@code gcs.service-account}；
     * 私钥留在服务端。这条断言的意义在于：这份 JSON 与 AK/SK 不同，它是可签发令牌的
     * 长期密钥，泄露的后果比一对 AK/SK 更大。
     */
    @Test
    void gcsNeverVendsTheServiceAccountKey() {
        GcpStorageConfigInfo config = new GcpStorageConfigInfo(
                List.of("gs://bucket/prefix"), null, "svc@project.iam.gserviceaccount.com",
                cipher.seal("{\"client_email\":\"svc@project.iam.gserviceaccount.com\","
                        + "\"private_key\":\"-----BEGIN PRIVATE KEY-----\\nMIIB\\n"
                        + "-----END PRIVATE KEY-----\\n\"}"));

        VendedStorageCredential vended = manager.vend(config, "t1", null);

        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
        assertFalse(vended.hasSecrets());
        assertEquals("svc@project.iam.gserviceaccount.com",
                vended.token().get("gcs.service-account"));
        assertFalse(vended.token().containsKey("gcs.oauth2.token"),
                "没有启用签发就不该有令牌: " + vended.token());
        assertFalse(vended.token().values().stream().anyMatch(v -> v.contains("PRIVATE KEY")),
                "私钥不得出现在下发键里: " + vended.token());
    }

    @Test
    void gcsWithoutAServiceAccountFallsBackToEnvironment() {
        VendedStorageCredential vended = manager.vend(
                new GcpStorageConfigInfo(List.of("gs://bucket/prefix"), null, null, null), "t1", null);

        assertFalse(vended.token().containsKey("gcs.oauth2.token"));
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source());
    }

    /**
     * 打码只打在有 id 的那一栏；GCS 没有 id，所以星号落在密钥那一栏。
     *
     * <p>「已保存」这个信息必须有地方承载，否则调用方分不清「没配」与「配了但看不到」——
     * 而这两种情况在 {@code PUT} 里的含义不同。
     */
    @Test
    void gcsMasksTheServiceAccountJsonBecauseItHasNoKeyId() {
        GcpStorageConfigInfo config = new GcpStorageConfigInfo(
                List.of("gs://bucket/prefix"), null, "svc@project.iam.gserviceaccount.com",
                cipher.seal("{\"client_email\":\"svc@project.iam.gserviceaccount.com\","
                        + "\"private_key\":\"k\"}"));

        GcpStorageConfigInfo masked = (GcpStorageConfigInfo) StorageConfigs.withoutSecrets(config);

        assertEquals(StorageConfigs.MASKED, masked.serviceAccountJson());
        assertEquals("svc@project.iam.gserviceaccount.com", masked.gcsServiceAccount(),
                "定位字段照旧回显");
        assertTrue(StorageConfigs.hasCredentials(masked), "打码后仍算「已配置」");
    }

    // ------------------------------------------------------------------ FILE

    @Test
    void fileKeepsSelfContainedTokenAndTablePath() {
        VendedStorageCredential vended = manager.vend(
                new FileStorageConfigInfo(List.of("file:///tmp/wh"), null), "table-7", "file:///tmp/wh/db.db/table-7");

        assertEquals("PAIMON-table-7", vended.token().get("accessKeyId"));
        assertNotNull(vended.token().get("securityToken"));
        assertNotNull(vended.token().get("expiration"));
        assertEquals("file:///tmp/wh/db.db/table-7", vended.token().get("tablePath"));
        assertEquals(VendedStorageCredential.SOURCE_FILESYSTEM, vended.source());
    }

    @Test
    void missingStorageConfigIsAServerError() {
        ApiException failure = assertThrows(ApiException.class, () -> manager.vend(null, "t1", null));
        assertEquals(500, failure.getStatus());
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    void cacheReusesCredentialUntilItExpires() {
        StorageCredentialCache cache = cache();
        StorageConfigInfo storage = s3("s3://bucket-a/prefix");
        String key = StorageCredentialCache.key("catalog-1", storage, "s3://bucket-a/prefix");
        VendedStorageCredential vended =
                new VendedStorageCredential(java.util.Map.of("k", "v"), 60, StorageType.S3, "x");
        // 缓存按真实时钟判断过期，签发时刻因此必须取自同一个时钟
        long issuedAt = System.currentTimeMillis();

        StorageCredentialCache.Cached first = cache.put(key, vended, issuedAt);

        assertEquals(issuedAt + 60_000L, first.expiresAtMillis());
        assertEquals(1, cache.size());
        assertEquals(first.expiresAtMillis(), cache.get(key).orElseThrow().expiresAtMillis());
    }

    @Test
    void cacheKeyVariesWithStorageConfigAndPath() {
        StorageConfigInfo a = s3("s3://bucket-a/prefix");
        StorageConfigInfo b = s3("s3://bucket-b/prefix");

        assertFalse(StorageCredentialCache.key("c1", a, "p").equals(StorageCredentialCache.key("c2", a, "p")));
        assertFalse(StorageCredentialCache.key("c1", a, "p").equals(StorageCredentialCache.key("c1", b, "p")));
        assertFalse(StorageCredentialCache.key("c1", a, "p").equals(StorageCredentialCache.key("c1", a, "q")));
    }

    /** 超过上限时淘汰最久未使用的条目，而不是拒绝新增。 */
    @Test
    void cacheEvictsLeastRecentlyUsedBeyondMaxEntries() {
        properties.getStorageCredentialCache().setMaxEntries(2);
        StorageCredentialCache cache = cache();
        VendedStorageCredential vended =
                new VendedStorageCredential(java.util.Map.of("k", "v"), 600, StorageType.S3, "x");
        long issuedAt = System.currentTimeMillis();

        cache.put("a", vended, issuedAt);
        cache.put("b", vended, issuedAt);
        // 触碰 a，使 b 成为最久未使用
        assertTrue(cache.get("a").isPresent());
        cache.put("c", vended, issuedAt);

        assertEquals(2, cache.size());
        assertTrue(cache.get("a").isPresent());
        assertFalse(cache.get("b").isPresent());
        assertTrue(cache.get("c").isPresent());
    }

    /** TTL 非正的凭据不入缓存，但调用方仍拿得到过期时刻。 */
    @Test
    void cacheSkipsNonPositiveTtl() {
        StorageCredentialCache cache = cache();
        VendedStorageCredential vended = new VendedStorageCredential(java.util.Map.of(), 0, StorageType.FILE, "x");

        StorageCredentialCache.Cached cached = cache.put("a", vended, 5_000L);

        assertEquals(0, cache.size());
        assertEquals(5_000L, cached.expiresAtMillis());
    }

    // ------------------------------------------------------------------ 策略取值

    @Test
    void fileIoTypeParsesAndRejectsUnknownValues() {
        assertEquals(FileIoType.DEFAULT, FileIoType.parse("DEFAULT").orElseThrow());
        assertEquals(FileIoType.LOCAL, FileIoType.parse(" local ").orElseThrow());
        assertTrue(FileIoType.parse("nope").isEmpty());

        // 未知取值必须启动即失败，且错误信息要列出合法取值
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> FileIoType.require("nope"));
        assertTrue(failure.getMessage().contains("s3"));
        assertTrue(failure.getMessage().contains("local"));
    }

    @Test
    void fileIoTypeRestrictsStorageTypes() {
        assertTrue(FileIoType.DEFAULT.supports(StorageType.AZURE));
        assertTrue(FileIoType.S3.supports(StorageType.S3));
        assertFalse(FileIoType.S3.supports(StorageType.AZURE));
        assertFalse(FileIoType.S3.supports(StorageType.GCS));
        // FILE 是本地的退路，任何取值下都保留
        for (FileIoType type : FileIoType.values()) {
            assertTrue(type.supports(StorageType.FILE), type + " must keep FILE available");
        }
        assertEquals(1, FileIoType.LOCAL.supportedStorageTypes().size());
        assertEquals(StorageType.values().length, FileIoType.DEFAULT.supportedStorageTypes().size());
        // OBS / OSS / COS 各自只放开自己那一种云存储，不是「所有对象存储」
        assertTrue(FileIoType.OBS.supports(StorageType.OBS));
        assertFalse(FileIoType.OBS.supports(StorageType.OSS));
        assertFalse(FileIoType.OBS.supports(StorageType.S3));
        assertTrue(FileIoType.OSS.supports(StorageType.OSS));
        assertFalse(FileIoType.OSS.supports(StorageType.OBS));
        assertTrue(FileIoType.COS.supports(StorageType.COS));
        assertFalse(FileIoType.COS.supports(StorageType.S3));
        assertFalse(FileIoType.COS.supports(StorageType.OSS));
        // COS 走 COSN，不是 S3 兼容层：S3 档不该顺手放开它
        assertFalse(FileIoType.S3.supports(StorageType.COS));
        // 对外的取值是 cosn（同时是 Paimon 的 FileIO scheme），不是 cos
        assertEquals("cosn", FileIoType.COS.wireName());
    }

    @Test
    void credentialManagerTypeRejectsUnknownValues() {
        assertEquals(CredentialManagerType.DEFAULT, CredentialManagerType.parse("default").orElseThrow());
        assertEquals(CredentialManagerType.NOOP, CredentialManagerType.parse("NOOP").orElseThrow());
        assertTrue(CredentialManagerType.parse("off").isEmpty());

        // 拼错的 noop 不能退回 default：那会让本该停发凭据的部署继续发
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> CredentialManagerType.require("nooop"));
        assertTrue(failure.getMessage().contains("noop"));
        assertTrue(failure.getMessage().contains("default"));
    }

    // ------------------------------------------------------------------ 辅助

    /** 不带凭据的 S3 配置。 */
    private static AwsStorageConfigInfo s3(String location) {
        return s3(location, null, null);
    }

    /**
     * 带凭据的 S3 配置。{@code sealedSecret} 是库内形态，明文由本类自行密封。
     *
     * <p>刻意把 {@code storageName} 留空：它现在只是一个标签，与凭据不再互斥，
     * 但「填了标签」不该影响下发的任何一项。
     */
    private static AwsStorageConfigInfo s3(String location, String accessKeyId, String sealedSecret) {
        return new AwsStorageConfigInfo(new ArrayList<>(List.of(location)), null,
                null, null, null, null, null, null, null, null, null, null, null, null, null,
                accessKeyId, sealedSecret);
    }

    private static HuaweiObsStorageConfigInfo obs(String location, String endpoint,
                                                  String accessKeyId, String sealedSecret) {
        return new HuaweiObsStorageConfigInfo(List.of(location), null, endpoint, null,
                accessKeyId, sealedSecret);
    }

    private static AliyunOssStorageConfigInfo oss(String location, String endpoint,
                                                  String accessKeyId, String sealedSecret) {
        return new AliyunOssStorageConfigInfo(List.of(location), null, endpoint, null,
                accessKeyId, sealedSecret, null, null);
    }

    private static TencentCosStorageConfigInfo cos(String location, String endpoint, String region,
                                                    String accessKeyId, String sealedSecret) {
        return new TencentCosStorageConfigInfo(List.of(location), null, endpoint, region,
                null, null, null, accessKeyId, sealedSecret);
    }

    /** Azure：账户名从位置里解析，租户与（可选的）服务主体从参数来。 */
    private static AzureStorageConfigInfo azure(String location, String tenantId,
                                                String clientId, String sealedSecret) {
        return new AzureStorageConfigInfo(List.of(location), null, tenantId, "app-name",
                "https://consent.example.com", Boolean.TRUE, clientId, sealedSecret, null, null);
    }

    /** 键名族是否越界：断言「不含某前缀的键」比逐个断言「含哪些键」更能抓住串族。 */
    private static boolean hasAnyKeyStartingWith(Map<String, String> token, String prefix) {
        return token.keySet().stream().anyMatch(key -> key.startsWith(prefix));
    }
}
