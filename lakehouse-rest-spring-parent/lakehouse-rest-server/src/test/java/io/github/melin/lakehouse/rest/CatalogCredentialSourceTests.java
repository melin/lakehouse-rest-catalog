package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.TencentCosStorageConfigInfo;
import io.github.melin.lakehouse.rest.service.CatalogCredentialSource;
import io.github.melin.lakehouse.rest.service.CatalogCredentialSource.Mode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「这个 catalog 的凭据从哪来」的判定：三档优先级 + 四种「配置有误」。
 *
 * <p>钉的是**顺序**，不是文案：静态凭据排在 {@code stsUnavailable} 之前、
 * {@code stsUnavailable} 排在签发之前、签发排在具名存储之前。
 * 这条链与 {@code DefaultStorageCredentialManager#s3} 是同一套，任何一处改动
 * 只要改错了顺序，界面上就会出现「说在转发、其实在签发」这类只在出事那天才被发现的错。
 *
 * <p>纯单测（不起 Spring）：判定只读配置，起容器只会把「判了哪一条」埋掉。
 */
class CatalogCredentialSourceTests {

    private static final String ROLE = "arn:aws:iam::123:role/lakehouse";

    private final RestServerProperties properties = new RestServerProperties();

    // ------------------------------------------------------------------ S3

    /** 静态凭据最优先：调用方明确写了「用这把钥匙」，策略不许覆盖决定。 */
    @Test
    void catalogStaticCredentialsWinOverEverythingElse() {
        properties.getCredential().getProviders().add("s3-token");
        AwsStorageConfigInfo s3 = aws("AK-STORE", "SK-STORE", ROLE, null, true);

        CatalogCredentialSource.Source source = CatalogCredentialSource.of(s3, properties);

        assertEquals(Mode.RELAY, source.mode());
        assertTrue(source.detail().contains("静态凭据"), source.detail());
    }

    /** 只填了 roleArn 与本部署开了 s3-token：这是唯一会去签的一档。 */
    @Test
    void roleArnWithTheProviderEnabledIsTheOnlyStsBranch() {
        properties.getCredential().getProviders().add("s3-token");

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(null, null, ROLE, null, null), properties);

        assertEquals(Mode.STS, source.mode());
        assertTrue(source.detail().contains("s3-token"), source.detail());
    }

    /**
     * 填了 roleArn 但本部署没开 provider：**不能显示成签发**。
     *
     * <p>这是最容易出现的一种错觉——配置看起来齐全，实际退回环境凭据链，
     * 而引擎在那一刻拿不到任何密钥。界面上必须说清「是 providers 没开」。
     */
    @Test
    void roleArnWithoutTheProviderFallsBackToTheEnvironmentChain() {
        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(null, null, ROLE, null, null), properties);

        assertEquals(Mode.NONE, source.mode());
        assertTrue(source.detail().contains("credential.providers"), source.detail());
    }

    /** {@code stsUnavailable} 排在签发之前：调用方明说「不要票」，签了就是违背意图。 */
    @Test
    void stsUnavailableBeatsSigning() {
        properties.getCredential().getProviders().add("s3-token");

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(null, null, ROLE, null, true), properties);

        assertEquals(Mode.NONE, source.mode());
        assertTrue(source.detail().contains("stsUnavailable"), source.detail());
    }

    @Test
    void namedStorageVendsTheKeysItReferences() {
        properties.getStorage().getAws().getStorages().put("etl", keys("AK-NAMED", "SK-NAMED"));

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(null, null, null, "etl", null), properties);

        assertEquals(Mode.RELAY, source.mode());
        assertTrue(source.detail().contains("etl"), source.detail());
    }

    /** 引用了不存在的具名存储：取票会 500，界面必须先标出来。 */
    @Test
    void referencingAMissingNamedStorageIsReportedAsInvalid() {
        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(null, null, null, "typo", null), properties);

        assertEquals(Mode.INVALID, source.mode());
        assertTrue(source.detail().contains("typo"), source.detail());
    }

    @Test
    void serverDefaultKeysAreForwarded() {
        properties.getStorage().getAws().setAccessKey("AK-DEFAULT");
        properties.getStorage().getAws().setSecretKey("SK-DEFAULT");

        assertEquals(Mode.RELAY,
                CatalogCredentialSource.of(aws(null, null, null, null, null), properties).mode());
    }

    @Test
    void nothingConfiguredMeansTheEngineBringsItsOwnIdentity() {
        assertEquals(Mode.NONE,
                CatalogCredentialSource.of(aws(null, null, null, null, null), properties).mode());
    }

    /**
     * 对外那份（{@code accessKeyId} 已打码成 {@code ******}）也能判：只看「有没有配」。
     *
     * <p>控制台把列表接口的响应直接喂进这个判定，因此这条不是理论情形。
     */
    @Test
    void maskedAccessKeyIdStillCountsAsConfigured() {
        AwsStorageConfigInfo masked = aws("******", null, null, null, null);

        assertEquals(Mode.RELAY, CatalogCredentialSource.of(masked, properties).mode());
    }

    // ------------------------------------------------------------------ OBS / OSS / COS

    @Test
    void obsHasNoSigningBranchSoRoleArnIsIrrelevant() {
        properties.getStorage().getObs().setAccessKey("AK-OBS");
        properties.getStorage().getObs().setSecretKey("SK-OBS");

        assertEquals(Mode.RELAY,
                CatalogCredentialSource.of(obs(null, false, null, null), properties).mode());
    }

    @Test
    void obsStsUnavailableSendsLocatorsOnly() {
        assertEquals(Mode.NONE,
                CatalogCredentialSource.of(obs(null, true, null, null), properties).mode());
    }

    /** OSS 配了会话令牌：转发的是临时凭据，仍然是「转发」而不是「签发」。 */
    @Test
    void ossForwardsATemporaryKeyTripleAsRelay() {
        properties.getStorage().getOss().setAccessKey("AK-OSS");
        properties.getStorage().getOss().setSecretKey("SK-OSS");
        properties.getStorage().getOss().setSessionToken("TOKEN-OSS");

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(oss(null, false, null, null), properties);

        assertEquals(Mode.RELAY, source.mode());
        assertTrue(source.detail().contains("安全令牌"), source.detail());
    }

    /**
     * COS 配了 {@code sessionToken}：COSN 没有令牌位，**取票会 500**。
     *
     * <p>这条必须比「转发」更早说出来，否则界面显示一切正常，读表时才炸。
     */
    @Test
    void cosWithASessionTokenIsInvalidRatherThanRelayed() {
        properties.getStorage().getCos().setAccessKey("AK-COS");
        properties.getStorage().getCos().setSecretKey("SK-COS");
        properties.getStorage().getCos().setSessionToken("TOKEN-COS");

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(cos(null, null, null, null, null, false, null, null),
                        properties);

        assertEquals(Mode.INVALID, source.mode());
        assertTrue(source.detail().contains("sessionToken"), source.detail());
    }

    // ------------------------------------------------------------------ 其余类型

    @Test
    void azureSendsLocatorsOnly() {
        CatalogCredentialSource.Source source = CatalogCredentialSource.of(
                new AzureStorageConfigInfo(List.of("abfss://c@a.dfs.core.windows.net/x"), null,
                        "tenant", null, null, null), properties);

        assertEquals(Mode.NONE, source.mode());
    }

    @Test
    void gcsIsRelayOnlyWhenAnAccessTokenIsConfigured() {
        GcpStorageConfigInfo gcs = new GcpStorageConfigInfo(List.of("gs://b/x"), null, null);
        assertEquals(Mode.NONE, CatalogCredentialSource.of(gcs, properties).mode());

        properties.getStorage().getGcp().setToken("ya29.token");
        assertEquals(Mode.RELAY, CatalogCredentialSource.of(gcs, properties).mode());
    }

    @Test
    void fileStorageHasNoCredentialToVend() {
        assertEquals(Mode.NONE, CatalogCredentialSource.of(
                new FileStorageConfigInfo(List.of("file:///tmp/wh"), null), properties).mode());
    }

    /** 没有存储配置的老数据：取票本来就会 500，这里不能显示成三档之一。 */
    @Test
    void missingStorageConfigIsInvalid() {
        assertEquals(Mode.INVALID, CatalogCredentialSource.of(null, properties).mode());
    }

    // ------------------------------------------------------------------ 构造

    private static AwsStorageConfigInfo aws(String accessKeyId, String secretAccessKey,
                                            String roleArn, String storageName, Boolean stsUnavailable) {
        return new AwsStorageConfigInfo(List.of("s3://bucket/wh"), storageName, roleArn, null, null,
                null, null, "us-east-1", null, null, stsUnavailable, null, null, null,
                accessKeyId, secretAccessKey);
    }

    private static HuaweiObsStorageConfigInfo obs(String storageName, Boolean stsUnavailable,
                                                  String accessKeyId, String secretAccessKey) {
        return new HuaweiObsStorageConfigInfo(List.of("obs://bucket/wh"), storageName,
                "obs.cn-north-4.myhuaweicloud.com", stsUnavailable, accessKeyId, secretAccessKey);
    }

    private static AliyunOssStorageConfigInfo oss(String storageName, Boolean stsUnavailable,
                                                  String accessKeyId, String secretAccessKey) {
        return new AliyunOssStorageConfigInfo(List.of("oss://bucket/wh"), storageName,
                "oss-cn-hangzhou.aliyuncs.com", stsUnavailable, accessKeyId, secretAccessKey);
    }

    private static TencentCosStorageConfigInfo cos(String storageName, String endpoint,
                                                   String bucketRegion, String appId, Boolean useHttps,
                                                   Boolean stsUnavailable, String accessKeyId,
                                                   String secretAccessKey) {
        return new TencentCosStorageConfigInfo(List.of("cosn://bucket-1250000000/wh"), storageName,
                endpoint, bucketRegion, appId, useHttps, stsUnavailable, accessKeyId, secretAccessKey);
    }

    private static RestServerProperties.Storage.Keys keys(String accessKey, String secretKey) {
        RestServerProperties.Storage.Keys keys = new RestServerProperties.Storage.Keys();
        keys.setAccessKey(accessKey);
        keys.setSecretKey(secretKey);
        return keys;
    }
}
