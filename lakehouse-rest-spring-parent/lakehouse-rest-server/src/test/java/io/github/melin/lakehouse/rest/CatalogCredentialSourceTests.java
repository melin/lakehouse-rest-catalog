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
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 「这个 catalog 的凭据从哪来」的判定：五档 + 几种「配置有误」。
 *
 * <p>钉的是**顺序与归属**，不是文案：{@code stsUnavailable} 先于签发、签发先于转发。
 * 这条链与 {@code DefaultStorageCredentialManager} 是同一套，任何一处改动只要改错了
 * 顺序，界面上就会出现「说在转发、其实在签发」这类只在出事那天才被发现的错。
 *
 * <p><b>{@code NONE} 已经拆成 {@code LOCAL} 与 {@code LEGACY_ENV}。</b>
 * 前者是「本地文件，本来就没有密钥」（正常状态），后者是「对象存储上什么都没有」
 * （问题，要补一种方式）。把它们判成一档的后果是：本地 catalog 被标成「配置有误」，
 * 或者一个建完就坏的 catalog 被标成「正常」。
 *
 * <p>纯单测（不起 Spring）：判定只读配置，起容器只会把「判了哪一条」埋掉。
 */
class CatalogCredentialSourceTests {

    private static final String ROLE = "arn:aws:iam::123:role/lakehouse";
    private static final String AK = "AK-CALLER";
    private static final String SK = "SK-CALLER";

    private final RestServerProperties properties = new RestServerProperties();

    // ------------------------------------------------------------------ S3

    /** 只有 AK/SK、没有角色：直接转发给引擎。 */
    @Test
    void catalogCredentialsAreRelayedWhenThereIsNoRole() {
        AwsStorageConfigInfo s3 = aws("AK-STORE", "SK-STORE", null, null, null);

        CatalogCredentialSource.Source source = CatalogCredentialSource.of(s3, properties);

        assertEquals(Mode.STATIC, source.mode());
        assertTrue(source.detail().contains("静态凭据"), source.detail());
    }

    /**
     * 角色 + 签名密钥，本部署也开了签发：VENDED。**哪怕同时填了 AK/SK**。
     *
     * <p>此时那对钥匙是「去 assume 的身份」，不是要转发给引擎的凭据——
     * 判定顺序写反的话，这个 catalog 会永远走转发档。
     */
    @Test
    void roleArnWithSigningKeysIsVended() {
        properties.getCredential().getProviders().add(VendedStorageCredential.PROVIDER_S3_TOKEN);
        AwsStorageConfigInfo s3 = aws(AK, SK, ROLE, null, null);

        CatalogCredentialSource.Source source = CatalogCredentialSource.of(s3, properties);

        assertEquals(Mode.VENDED, source.mode());
        assertTrue(source.detail().contains("s3-token"), source.detail());
    }

    /**
     * 只填角色、不填签名密钥：**配置有误**，不是「退回环境链」。
     *
     * <p>v3 把「用服务端自己的身份去 AssumeRole」整档删掉了：那个身份在表单上
     * 看不见，于是「保存成功」会被读成「应该能用」。现在这类配置在写入口就 400，
     * 这个判定只对存量说话。
     */
    @Test
    void roleArnWithoutSigningKeysIsInvalid() {
        properties.getCredential().getProviders().add(VendedStorageCredential.PROVIDER_S3_TOKEN);

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(null, null, ROLE, null, null), properties);

        assertEquals(Mode.INVALID, source.mode());
        assertTrue(source.detail().contains("accessKeyId"), source.detail());
    }

    /**
     * 角色 + 密钥，但本部署没开签发：**按 STATIC 报**。
     *
     * <p>管理器的实际行为就是退回转发（那对钥匙原样发给引擎）。这里若报 INVALID，
     * 用户会去查一个不存在的错误；若报 VENDED，他会以为拿到了短期票。
     */
    @Test
    void roleArnWithoutTheProviderFallsBackToForwardingTheKeys() {
        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(AK, SK, ROLE, null, null), properties);

        assertEquals(Mode.STATIC, source.mode());
        assertTrue(source.detail().contains("credential.providers"), source.detail());
        assertTrue(source.detail().contains("roleArn"), source.detail());
    }

    /** {@code stsUnavailable} 排在签发之前：调用方明说「不要票」，签了就是违背意图。 */
    @Test
    void stsUnavailableBeatsSigning() {
        properties.getCredential().getProviders().add(VendedStorageCredential.PROVIDER_S3_TOKEN);

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(aws(AK, SK, ROLE, null, true), properties);

        assertEquals(Mode.STATIC, source.mode());
        assertTrue(source.detail().contains("stsUnavailable"), source.detail());
    }

    /** 什么都没有：服务端读不了（预览 500），引擎用它自己的。这一档要能「补全」掉。 */
    @Test
    void nothingConfiguredIsTheLegacyEnvironmentBucket() {
        assertEquals(Mode.LEGACY_ENV,
                CatalogCredentialSource.of(aws(null, null, null, null, null), properties).mode());
    }

    /**
     * 对外那份（凭据已打码成 {@code ******}）也能判：只看「有没有配」。
     *
     * <p>控制台把列表接口的响应直接喂进这个判定，因此这条不是理论情形。
     */
    @Test
    void maskedAccessKeyIdStillCountsAsConfigured() {
        AwsStorageConfigInfo masked = aws("******", null, null, null, null);

        assertEquals(Mode.STATIC, CatalogCredentialSource.of(masked, properties).mode());
    }

    // ------------------------------------------------------------------ OBS / OSS / COS

    @Test
    void obsRelaysTheCatalogCredentials() {
        assertEquals(Mode.STATIC,
                CatalogCredentialSource.of(obs(false, "AK-OBS", "SK-OBS"), properties).mode());
    }

    @Test
    void obsStsUnavailableWithoutKeysHasNothingUsable() {
        assertEquals(Mode.LEGACY_ENV,
                CatalogCredentialSource.of(obs(true, null, null), properties).mode());
    }

    /** OSS：角色 + 钥匙 + 本部署开了 oss-sts，三者齐了才签。 */
    @Test
    void ossSignsWhenRoleAndCallerKeysAreBothOnTheCatalog() {
        properties.getCredential().getProviders().add(VendedStorageCredential.PROVIDER_OSS_STS);

        CatalogCredentialSource.Source source = CatalogCredentialSource.of(
                oss(AK, SK, "acs:ram::1:role/lh"), properties);

        assertEquals(Mode.VENDED, source.mode());
        assertTrue(source.detail().contains("900–3600"), source.detail());
    }

    /**
     * OSS 填了角色却没有钥匙：**配置有误**。
     *
     * <p>阿里云的 STS 必须用一对 AK/SK 签名，没有环境凭据链。界面若显示「签发」，
     * 排查会从权限绕一大圈才回到配置上。
     */
    @Test
    void ossWithARoleButNoCallerKeysIsReportedAsInvalid() {
        properties.getCredential().getProviders().add(VendedStorageCredential.PROVIDER_OSS_STS);

        CatalogCredentialSource.Source source = CatalogCredentialSource.of(
                oss(null, null, "acs:ram::1:role/lh"), properties);

        assertEquals(Mode.INVALID, source.mode());
        assertTrue(source.detail().contains("AK/SK"), source.detail());
    }

    @Test
    void ossWithoutARoleJustRelays() {
        properties.getCredential().getProviders().add(VendedStorageCredential.PROVIDER_OSS_STS);

        assertEquals(Mode.STATIC,
                CatalogCredentialSource.of(oss("AK-OSS", "SK-OSS", null), properties).mode());
    }

    /** COS 只有转发档：COSN 不收临时凭据，配了也没有可以表达它的字段。 */
    @Test
    void cosOnlyEverRelaysOrHasNothing() {
        assertEquals(Mode.STATIC,
                CatalogCredentialSource.of(cos("AK-COS", "SK-COS"), properties).mode());
        assertEquals(Mode.LEGACY_ENV,
                CatalogCredentialSource.of(cos(null, null), properties).mode());
    }

    // ------------------------------------------------------------------ Azure / GCS

    /** 没开 azure-sas：服务端签不出 SAS，只能发定位元数据——对用户等于「补一个身份」。 */
    @Test
    void azureWithoutTheProviderHasNoUsableCredential() {
        CatalogCredentialSource.Source source = CatalogCredentialSource.of(
                azure("tenant", "client", "secret"), properties);

        assertEquals(Mode.LEGACY_ENV, source.mode());
        assertTrue(source.detail().contains("azure-sas"), source.detail());
    }

    /** 开了 azure-sas 且 catalog 上有服务主体：签 SAS。 */
    @Test
    void azureSignsWhenTheServicePrincipalIsOnTheCatalog() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_AZURE_SAS));

        assertEquals(Mode.VENDED,
                CatalogCredentialSource.of(azure("tenant", "client", "secret"), properties).mode());
    }

    /** 开了 azure-sas 但 catalog 上没服务主体：标成配置有误，而不是「签发」。 */
    @Test
    void azureWithoutAServicePrincipalIsReportedAsInvalid() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_AZURE_SAS));

        assertEquals(Mode.INVALID,
                CatalogCredentialSource.of(azure("tenant", null, null), properties).mode());
    }

    @Test
    void gcsWithoutAServiceAccountHasNothingUsable() {
        assertEquals(Mode.LEGACY_ENV, CatalogCredentialSource.of(gcs(null), properties).mode());
    }

    /** 有服务账号但没启用 gcs-downscope：不签发，服务账号也不下发。 */
    @Test
    void gcsServiceAccountDoesNotImplySigning() {
        assertEquals(Mode.LEGACY_ENV,
                CatalogCredentialSource.of(gcs("{\"a\":1}"), properties).mode());
    }

    @Test
    void gcsSignsOnceTheProviderIsEnabled() {
        properties.getCredential().setProviders(
                List.of(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE));

        CatalogCredentialSource.Source source =
                CatalogCredentialSource.of(gcs("{\"a\":1}"), properties);

        assertEquals(Mode.VENDED, source.mode());
        assertTrue(source.detail().contains("没有续期"), source.detail());
    }

    /** 本地文件系统：没有密钥这回事，不是「缺配置」。 */
    @Test
    void fileStorageIsLocalNotLegacy() {
        assertEquals(Mode.LOCAL, CatalogCredentialSource.of(
                new FileStorageConfigInfo(List.of("file:///tmp/wh"), null), properties).mode());
    }

    /** 没有存储配置的老数据：取票本来就会 500，这里不能显示成五档里正常的那几档。 */
    @Test
    void missingStorageConfigIsInvalid() {
        assertEquals(Mode.INVALID, CatalogCredentialSource.of(null, properties).mode());
    }

    // ------------------------------------------------------------------ 构造

    private static AwsStorageConfigInfo aws(String accessKeyId, String secretAccessKey,
                                            String roleArn, String storageName, Boolean stsUnavailable) {
        return new AwsStorageConfigInfo(List.of("s3://bucket/wh"), storageName, roleArn, null, null,
                null, null, "us-east-1", null, null, stsUnavailable, null, null, null, null,
                accessKeyId, secretAccessKey);
    }

    private static HuaweiObsStorageConfigInfo obs(Boolean stsUnavailable, String accessKeyId,
                                                  String secretAccessKey) {
        return new HuaweiObsStorageConfigInfo(List.of("obs://bucket/wh"), null,
                "obs.cn-north-4.myhuaweicloud.com", stsUnavailable, accessKeyId, secretAccessKey);
    }

    private static AliyunOssStorageConfigInfo oss(String accessKeyId, String secretAccessKey,
                                                  String roleArn) {
        return new AliyunOssStorageConfigInfo(List.of("oss://bucket/wh"), null,
                "oss-cn-hangzhou.aliyuncs.com", null, accessKeyId, secretAccessKey, roleArn, null);
    }

    private static TencentCosStorageConfigInfo cos(String accessKeyId, String secretAccessKey) {
        return new TencentCosStorageConfigInfo(List.of("cosn://bucket-1250000000/wh"), null,
                null, null, null, null, null, accessKeyId, secretAccessKey);
    }

    private static AzureStorageConfigInfo azure(String tenantId, String clientId, String clientSecret) {
        return new AzureStorageConfigInfo(List.of("abfss://c@a.dfs.core.windows.net/x"), null,
                tenantId, null, null, null, clientId, clientSecret, null, null);
    }

    private static GcpStorageConfigInfo gcs(String serviceAccountJson) {
        return new GcpStorageConfigInfo(List.of("gs://b/x"), null, null, serviceAccountJson);
    }
}
