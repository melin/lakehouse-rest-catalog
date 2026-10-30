package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.service.AliyunRpcSignature;
import io.github.melin.lakehouse.rest.service.CatalogCredentialSource;
import io.github.melin.lakehouse.rest.service.CredentialContext;
import io.github.melin.lakehouse.rest.service.DefaultStorageCredentialManager;
import io.github.melin.lakehouse.rest.service.OssStsClient;
import io.github.melin.lakehouse.rest.service.OssStsCredentialProvider;
import io.github.melin.lakehouse.rest.service.OssStsRequest;
import io.github.melin.lakehouse.rest.service.OssStsSession;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 阿里云 OSS 的临时凭据签发（设计稿 P1-2）：policy 形状、键名、有效期夹紧、退档。
 *
 * <p>与 Azure 那套的分工：那边断言的难点在「签出来的 SAS 字符串」，这边在
 * 「发给 STS 的 policy 与参数」。两家的失败方式也不同——OSS 签错了多半是
 * {@code AccessDenied} 或 {@code InvalidParameter}，而这两条都能从请求本身看出来，
 * 所以断言的落点是**请求对象**而不是响应。
 *
 * <p>不碰真实 STS：{@link OssStsClient} 注入假实现并记录请求。真实调用需要一对
 * AK/SK 与一个可 assume 的 RAM 角色，单测里假装成功只会得到一个没内容的结论。
 */
class OssStsVendingTests {

    private static final String SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private static final String BUCKET_PATH = "oss://warehouse/wh/db.db/orders";

    /** catalog 上那一对签名钥匙；它不是转发给引擎的凭据。 */
    private static final String CATALOG_AK = "CATALOG.AK";

    private static final String CATALOG_SK = "CATALOG.SK";

    private final RestServerProperties properties = new RestServerProperties();

    private final CredentialCipher cipher = cipher(properties);

    private static CredentialCipher cipher(RestServerProperties properties) {
        properties.getStorage().setCredentialSecretKey(SECRET_KEY);
        return new CredentialCipher(properties);
    }

    // ------------------------------------------------------------------ 键名

    /**
     * 三件套一起发，键名逐字取自 hadoop-aliyun。
     *
     * <p>少发 {@code fs.oss.securityToken} 的后果与 S3 少发 {@code s3.session-token}
     * 不同：那边是客户端 {@code Preconditions} 抛错，这边是引擎按「长期凭据」去签名、
     * 拿回 {@code InvalidAccessKeyId}——与「AK 写错了」完全同一个现象。
     */
    @Test
    void vendsTheThreeTemporaryKeysPlusTheEndpoint() {
        FakeSts sts = new FakeSts();
        VendedStorageCredential vended = manager(sts).vend(oss(BUCKET_PATH), "t", BUCKET_PATH);

        Map<String, String> token = vended.token();
        assertEquals("STS.AK", token.get("fs.oss.accessKeyId"));
        assertEquals("STS.SK", token.get("fs.oss.accessKeySecret"));
        assertEquals("STS.TOKEN", token.get("fs.oss.securityToken"));
        assertEquals("oss-cn-hangzhou.aliyuncs.com", token.get("fs.oss.endpoint"));
        // 长期凭据的两个键名与临时凭据是同一套，所以必须确认这里发的是临时的那份
        assertFalse(CATALOG_AK.equals(token.get("fs.oss.accessKeyId")), "不该发 catalog 上的长期 AK");

        // 签名用的是 catalog 上那一对；它只用于 assume，不进下发键
        assertEquals(CATALOG_AK, sts.requests.get(0).accessKeyId());
        assertEquals(CATALOG_SK, sts.requests.get(0).secretAccessKey());
        assertFalse(token.containsValue(CATALOG_SK), "签名钥匙不该出现在下发键里");

        assertEquals(VendedStorageCredential.SOURCE_OSS_STS, vended.source());
        assertEquals(VendedStorageCredential.PROVIDER_OSS_STS, vended.provider());
        assertTrue(vended.shortLived(), "临时凭据一定有到期时刻");
        assertTrue(vended.hasSecrets());
        assertFalse(vended.carriesLongTermKeyPair(), "短期票不算长期密钥对（USE_SECRET 判据）");
    }

    // ------------------------------------------------------------------ policy

    /**
     * policy 的资源 ARN 是 {@code acs:oss:*:*:bucket/prefix/*}，不是 S3 的形状。
     *
     * <p>照 S3 的 {@code arn:aws:s3:::} 复制过来会得到一个语法正确、永不匹配的策略，
     * 而它不报错——只表现为「签出来了、读不了」。
     */
    @Test
    void scopesThePolicyWithAliyunResourceArns() {
        FakeSts sts = new FakeSts();
        manager(sts).vend(oss(BUCKET_PATH), "t", BUCKET_PATH);

        String policy = sts.requests.get(0).policy();
        assertNotNull(policy);
        assertTrue(policy.contains("acs:oss:*:*:warehouse/wh/db.db/orders/*"), policy);
        assertFalse(policy.contains("arn:aws"), "阿里云的 policy 里不该出现 AWS 的 ARN: " + policy);
        assertFalse(policy.contains("oss:PutObject"), "只读时不该出现写动作: " + policy);
        assertTrue(policy.contains("oss:GetObject"), policy);
        assertTrue(policy.contains("oss:ListObjects"), policy);
    }

    /**
     * {@code ListObjects} 的资源是**桶**，前缀只能走 {@code Condition}。
     *
     * <p>把前缀拼进 {@code ListObjects} 的资源里会得到一个永远不匹配的策略，
     * 而现象是「读单个文件正常、列目录 403」——很容易被当成客户端的问题。
     */
    @Test
    void listsTheBucketWithAPrefixConditionNotAPrefixedResource() {
        FakeSts sts = new FakeSts();
        manager(sts).vend(oss(BUCKET_PATH), "t", BUCKET_PATH);

        String policy = sts.requests.get(0).policy();
        assertTrue(policy.contains("\"Resource\":[\"acs:oss:*:*:warehouse\"]"),
                "ListObjects 的资源必须就是桶: " + policy);
        assertTrue(policy.contains("\"oss:Prefix\""), policy);
        assertTrue(policy.contains("\"wh/db.db/orders/*\""), "前缀要以通配结尾才能列出目录下所有对象: "
                + policy);
    }

    /** 写票才带写动作，且写动作里有 {@code AbortMultipartUpload}（清理上传碎片）。 */
    @Test
    void addsWriteActionsOnlyWhenAsked() {
        FakeSts read = new FakeSts();
        manager(read).vend(oss(BUCKET_PATH), "t", BUCKET_PATH);
        assertFalse(read.requests.get(0).policy().contains("oss:PutObject"));

        FakeSts write = new FakeSts();
        manager(write).vendForCatalog(oss(BUCKET_PATH), "lh", List.of(BUCKET_PATH), true);
        String policy = write.requests.get(0).policy();
        assertTrue(policy.contains("oss:PutObject"), policy);
        assertTrue(policy.contains("oss:AbortMultipartUpload"),
                "不清理中断的分片会上长期计费: " + policy);
        assertTrue(policy.contains("oss:DeleteObject"), policy);
    }

    /** 桶根：对象级 ARN 退化成 {@code bucket/*}，列表条件退化成 {@code *}。 */
    @Test
    void signsTheWholeBucketWhenThePrefixIsEmpty() {
        FakeSts sts = new FakeSts();
        manager(sts).vend(oss("oss://warehouse"), "t", "oss://warehouse");

        String policy = sts.requests.get(0).policy();
        assertTrue(policy.contains("\"acs:oss:*:*:warehouse/*\""), policy);
        assertTrue(policy.contains("\"*\""), "桶根的前缀条件要写成 *，空串只能匹配「空前缀」: " + policy);
    }

    // ------------------------------------------------------------------ 有效期

    /**
     * 超过 3600 秒被夹紧，低于 900 秒被抬到 900。
     *
     * <p>夹紧而不是报错，是因为 TTL 是部署级配置：为一个存储类型调小它，
     * 会同时改掉 S3 那一档（S3 最长 12 小时）。
     */
    @Test
    void clampsTheLifetimeIntoTheAliyunRange() {
        properties.getCredential().setTtlSeconds(8 * 3600);
        FakeSts sts = new FakeSts();
        VendedStorageCredential vended = manager(sts).vend(oss(BUCKET_PATH), "t", BUCKET_PATH);
        assertEquals(3600, sts.requests.get(0).durationSeconds());
        assertTrue(vended.ttlSeconds() <= 3600, "下发的 ttl 不能比票长: " + vended.ttlSeconds());

        properties.getCredential().setTtlSeconds(60);
        FakeSts shortSts = new FakeSts();
        manager(shortSts).vend(oss(BUCKET_PATH), "t", BUCKET_PATH);
        assertEquals(900, shortSts.requests.get(0).durationSeconds());
    }

    /** 票的到期时刻来自云的响应，不是「现在 + TTL」——角色上限更短时两者会分叉。 */
    @Test
    void reportsTheExpiryTheCloudGave() {
        properties.getCredential().setTtlSeconds(3600);
        FakeSts sts = new FakeSts();
        VendedStorageCredential vended = manager(sts).vend(oss(BUCKET_PATH), "t", BUCKET_PATH);

        assertNotEquals(0L, vended.expireTimeInMs());
        long remaining = Duration.between(Instant.now(), Instant.ofEpochMilli(vended.expireTimeInMs()))
                .toSeconds();
        assertTrue(remaining > 1700 && remaining <= 1800, "假实现给的是 30 分钟: " + remaining);
    }

    // ------------------------------------------------------------------ 会话名

    /**
     * 会话名要过阿里云的字符集：{@code [a-zA-Z0-9.@_-]}，2–64 位。
     *
     * <p>{@code CredentialContext#roleSessionName()} 是按 AWS 的规则写的（允许
     * {@code + = ,}），直接透传会在签名后被拒，报 {@code InvalidParameter.RoleSessionName}。
     */
    @Test
    void sanitizesTheSessionNameForAliyun() {
        FakeSts sts = new FakeSts();
        // principal 里有 @ 与 / ：一个合法的主体名，但 AWS 与阿里接受的范围不同
        manager(sts).vendForCatalog(oss(BUCKET_PATH), "lh/a=b+c", List.of(BUCKET_PATH), false);

        String name = sts.requests.get(0).sessionName();
        assertTrue(name.length() >= 2 && name.length() <= 64, "长度要落在 2–64: " + name);
        for (char c : name.toCharArray()) {
            boolean allowed = Character.isLetterOrDigit(c) || c == '.' || c == '@' || c == '-'
                    || c == '_';
            assertTrue(allowed, "阿里云不接受的字符 '" + c + "' 出现在会话名里: " + name);
        }
    }

    @Test
    void sessionNameShorterThanTwoCharactersFallsBack() {
        FakeSts sts = new FakeSts();
        // scopeId 为空时 roleSessionName 会是 "lh-" 之类；这里只断言不会产出 0–1 位
        manager(sts).vend(oss(BUCKET_PATH), "", BUCKET_PATH);
        assertTrue(sts.requests.get(0).sessionName().length() >= 2);
    }

    // ------------------------------------------------------------------ 退档

    /** 没配角色：不签、不调云，行为与接入 provider 之前一致（退回转发档）。 */
    @Test
    void declinesWithoutARole() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_OSS_STS));
        FakeSts sts = new FakeSts();
        DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(
                properties, cipher, List.of(new OssStsCredentialProvider(properties, cipher, sts)));

        VendedStorageCredential vended = manager.vend(ossWithoutRole(BUCKET_PATH), "t", BUCKET_PATH);

        assertTrue(sts.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source(),
                "catalog 有 AK/SK 却没角色：退回转发");
        assertEquals(CATALOG_AK, vended.token().get("fs.oss.accessKeyId"));
    }

    /** 配了角色但 catalog 上没有 AK/SK：阿里云的 STS 必须有一对密钥签名，只能跳过。 */
    @Test
    void declinesWithoutACallerIdentity() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_OSS_STS));
        FakeSts sts = new FakeSts();
        DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(
                properties, cipher, List.of(new OssStsCredentialProvider(properties, cipher, sts)));

        VendedStorageCredential vended = manager.vend(ossWithoutCallerKeys(BUCKET_PATH), "t", BUCKET_PATH);

        assertTrue(sts.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_ENVIRONMENT, vended.source(),
                "没有任何密钥可转发：退到环境（引擎自己想办法）");
    }

    /** 位置认不出来（不是 OSS 的 URI 形状）时不签，而不是签一张猜出来的票。 */
    @Test
    void declinesWhenNoScopeCanBeDerived() {
        FakeSts sts = new FakeSts();
        VendedStorageCredential vended = manager(sts).vend(oss(""), "t", "");

        assertTrue(sts.requests.isEmpty());
        assertNull(vended.token().get("fs.oss.securityToken"));
    }

    /** 不是 OSS 的 catalog 走到这个 provider 时返回空，不抛异常（链上的一次正常路由）。 */
    @Test
    void ignoresOtherStorageTypes() {
        FakeSts sts = new FakeSts();
        OssStsCredentialProvider provider = new OssStsCredentialProvider(properties, cipher, sts);
        CredentialContext gcs = CredentialContext.forTable(
                new io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo(
                        List.of("gs://bucket/p"), null, null, null),
                "t", "gs://bucket/p", "alice", false);

        assertTrue(provider.vend(gcs).isEmpty());
        assertTrue(sts.requests.isEmpty());
    }

    // ------------------------------------------------------------------ 签名

    /**
     * 签名是这些参数的纯函数：参数一样就一样，换密钥/换参数就不一样。
     *
     * <p>「有签名」不构成证据——把 Signature 写死成常量也能让上一条断言通过。
     */
    @Test
    void signatureIsAPureFunctionOfItsInputs() {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("Action", "AssumeRole");
        parameters.put("RoleArn", "acs:ram::1:role/lh");
        parameters.put("Timestamp", "2026-10-30T03:00:00Z");
        String canonical = AliyunRpcSignature.canonicalQuery(parameters);

        String first = AliyunRpcSignature.sign("SECRET", canonical);
        assertEquals(first, AliyunRpcSignature.sign("SECRET", canonical), "同一组输入必须得到同一个签名");
        assertNotEquals(first, AliyunRpcSignature.sign("OTHER", canonical), "换密钥签名必须变");

        parameters.put("RoleArn", "acs:ram::2:role/lh");
        assertNotEquals(first, AliyunRpcSignature.sign("SECRET",
                AliyunRpcSignature.canonicalQuery(parameters)), "换参数签名必须变");
    }

    /** 规范化查询串按参数名排序，与插入顺序无关。 */
    @Test
    void canonicalQuerySortsByParameterName() {
        Map<String, String> first = new LinkedHashMap<>();
        first.put("B", "2");
        first.put("A", "1");
        Map<String, String> second = new LinkedHashMap<>();
        second.put("A", "1");
        second.put("B", "2");
        assertEquals("A=1&B=2", AliyunRpcSignature.canonicalQuery(first));
        assertEquals(AliyunRpcSignature.canonicalQuery(first),
                AliyunRpcSignature.canonicalQuery(second));
    }

    /**
     * 编码用阿里那套 percentEncode：空格是 {@code %20}、{@code *} 是 {@code %2A}、
     * {@code ~} 保持原样。
     *
     * <p>换成 {@code URLEncoder} 会把空格写成 {@code +}、{@code ~} 写成 {@code %7E}，
     * 而签名仍然算得出来，只在服务端判定不匹配。
     */
    @Test
    void percentEncodingFollowsTheAliyunCharacterSet() {
        assertEquals("a%20b", AliyunRpcSignature.percentEncode("a b"));
        assertEquals("%2A", AliyunRpcSignature.percentEncode("*"));
        assertEquals("~", AliyunRpcSignature.percentEncode("~"));
        assertEquals("%7B%22Version%22%3A%221%22%7D",
                AliyunRpcSignature.percentEncode("{\"Version\":\"1\"}"));
        // stringToSign 的第二段是被编码的斜杠，第三段是整串查询串再编一次（等号也要编）
        assertEquals("GET&%2F&A%3D1", AliyunRpcSignature.stringToSign("A=1"));
    }

    // ------------------------------------------------------------------ 控制台判定

    /** 控制台的「凭据来源」跟着变：三条齐全才是「签发」，缺调用者身份要明说。 */
    @Test
    void consoleReportsSignedCredentialsOnlyWhenAllThreePartsArePresent() {
        AliyunOssStorageConfigInfo withoutRole = ossWithoutRole(BUCKET_PATH);
        assertEquals(CatalogCredentialSource.Mode.NONE,
                CatalogCredentialSource.of(ossWithoutCallerKeys(BUCKET_PATH), properties).mode(),
                "默认不启用时，有 AK/SK 也只是转发");

        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_OSS_STS));
        assertEquals(CatalogCredentialSource.Mode.RELAY,
                CatalogCredentialSource.of(withoutRole, properties).mode(),
                "启用了但没角色：仍是转发");

        CatalogCredentialSource.Source withoutIdentity =
                CatalogCredentialSource.of(ossWithoutCallerKeys(BUCKET_PATH), properties);
        assertEquals(CatalogCredentialSource.Mode.INVALID, withoutIdentity.mode(),
                "有角色但没签名钥匙是配置错误，不是「短期签发」");
        assertTrue(withoutIdentity.detail().contains("accessKeyId"), withoutIdentity.detail());

        CatalogCredentialSource.Source signed =
                CatalogCredentialSource.of(oss(BUCKET_PATH), properties);
        assertEquals(CatalogCredentialSource.Mode.STS, signed.mode());
        assertTrue(signed.detail().contains("没有续期"), signed.detail());
    }

    /**
     * {@code stsUnavailable} 仍然优先于签发：那是调用方的明确决定，签发只是服务端的默认策略。
     *
     * <p><b>但「不申请临时凭据」只否定签发，不否定转发。</b>catalog 上那对 AK/SK 本来就在，
     * 这一档下它仍然原样发给引擎——所以档位要跟着密钥有无走，一律报「不下发」是界面上
     * 最容易误导人的一种不一致（用户以为引擎在用环境身份，其实拿的是 catalog 上的钥匙）。
     */
    @Test
    void explicitDecisionsWinOverSigning() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_OSS_STS));

        AliyunOssStorageConfigInfo stsUnavailable = new AliyunOssStorageConfigInfo(
                List.of(BUCKET_PATH), null, "oss-cn-hangzhou.aliyuncs.com", true,
                CATALOG_AK, cipher.seal(CATALOG_SK), "acs:ram::1234567890:role/lh-vending", null);
        assertEquals(CatalogCredentialSource.Mode.RELAY,
                CatalogCredentialSource.of(stsUnavailable, properties).mode(),
                "有钥匙可转发：档位必须是转发，不能报「不下发」");

        FakeSts sts = new FakeSts();
        VendedStorageCredential vended = manager(sts)
                .vend(stsUnavailable, "t", BUCKET_PATH);
        assertTrue(sts.requests.isEmpty(), "stsUnavailable=true 时不该调云");
        assertEquals(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, vended.source(),
                "「不签发」不等于「不转发」：钥匙照发，来源要说清是它");
        assertEquals(CATALOG_AK, vended.token().get("fs.oss.accessKeyId"));
        assertFalse(vended.token().containsKey("fs.oss.securityToken"), "静态凭据不该带假令牌");
    }

    /** 没有钥匙可转发时，{@code stsUnavailable} 才是真的「一个密钥都不发」。 */
    @Test
    void stsUnavailableWithoutAnyKeyVendsNoSecrets() {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_OSS_STS));

        AliyunOssStorageConfigInfo stsUnavailable = new AliyunOssStorageConfigInfo(
                List.of(BUCKET_PATH), null, "oss-cn-hangzhou.aliyuncs.com", true,
                null, null, "acs:ram::1234567890:role/lh-vending", null);
        assertEquals(CatalogCredentialSource.Mode.NONE,
                CatalogCredentialSource.of(stsUnavailable, properties).mode());

        FakeSts sts = new FakeSts();
        VendedStorageCredential vended = manager(sts)
                .vend(stsUnavailable, "t", BUCKET_PATH);
        assertTrue(sts.requests.isEmpty());
        assertEquals(VendedStorageCredential.SOURCE_STS_UNAVAILABLE, vended.source());
        assertFalse(vended.token().containsKey("fs.oss.accessKeyId"));
        assertEquals("oss-cn-hangzhou.aliyuncs.com", vended.token().get("fs.oss.endpoint"),
                "不下发密钥不等于不下发定位");
    }

    // ------------------------------------------------------------------ 工具

    private DefaultStorageCredentialManager manager(FakeSts sts) {
        properties.getCredential().setProviders(List.of(VendedStorageCredential.PROVIDER_OSS_STS));
        return new DefaultStorageCredentialManager(properties, cipher,
                List.of(new OssStsCredentialProvider(properties, cipher, sts)));
    }

    /** 三样齐全的 catalog：角色 + 签名用的 AK/SK（密钥按落库形态密封）。 */
    private AliyunOssStorageConfigInfo oss(String location) {
        return new AliyunOssStorageConfigInfo(List.of(location), null,
                "oss-cn-hangzhou.aliyuncs.com", null, CATALOG_AK, cipher.seal(CATALOG_SK),
                "acs:ram::1234567890:role/lh-vending", null);
    }

    /** 有 AK/SK 但没有角色：转发档。 */
    private AliyunOssStorageConfigInfo ossWithoutRole(String location) {
        return new AliyunOssStorageConfigInfo(List.of(location), null,
                "oss-cn-hangzhou.aliyuncs.com", null, CATALOG_AK, cipher.seal(CATALOG_SK),
                null, null);
    }

    /** 有角色但没有任何钥匙可签名：签发档接不住，退回环境链。 */
    private static AliyunOssStorageConfigInfo ossWithoutCallerKeys(String location) {
        return new AliyunOssStorageConfigInfo(List.of(location), null,
                "oss-cn-hangzhou.aliyuncs.com", null, null, null,
                "acs:ram::1234567890:role/lh-vending", null);
    }

    /** 假 STS：记录请求，返回一张固定寿命（30 分钟）的票。 */
    private static final class FakeSts implements OssStsClient {

        private final List<OssStsRequest> requests = new ArrayList<>();

        @Override
        public OssStsSession assumeRole(OssStsRequest request) {
            requests.add(request);
            return new OssStsSession("STS.AK", "STS.SK", "STS.TOKEN",
                    Instant.now().plus(Duration.ofMinutes(30)));
        }
    }
}
