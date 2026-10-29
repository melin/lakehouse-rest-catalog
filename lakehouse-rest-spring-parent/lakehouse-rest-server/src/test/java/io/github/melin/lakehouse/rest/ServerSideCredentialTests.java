package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.service.AssumeRoleClient;
import io.github.melin.lakehouse.rest.service.AssumeRoleCommand;
import io.github.melin.lakehouse.rest.service.DefaultStorageCredentialManager;
import io.github.melin.lakehouse.rest.service.S3TokenCredentialProvider;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.service.StorageFileIoFactory;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.service.StsSession;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 服务端自己那张票（catalog 级作用域）的行为。
 *
 * <p><b>为什么单独一个测试类。</b>「服务端也是凭据消费者」这件事（设计稿 §5.7）
 * 容易在实现时被当成「顺手复用一下引擎的票」。两者的差别不是性能，而是正确性：
 * 引擎的票按一张表签，服务端要列目录、删表目录、跨表核对文件——拿表级票去干这些，
 * 表现是 403，而报错指向存储、与 catalog 无关。所以这里的断言都是**作用域的形状**，
 * 而不是「方法没抛异常」。
 *
 * <p>不碰真实 STS：{@link AssumeRoleClient} 注入假实现，断言「传下去的那份命令」。
 */
class ServerSideCredentialTests {

    private static final String SECRET_KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

    private final RestServerProperties properties = new RestServerProperties();

    private final CredentialCipher cipher = cipher(properties);

    private static CredentialCipher cipher(RestServerProperties properties) {
        properties.getStorage().setCredentialSecretKey(SECRET_KEY);
        return new CredentialCipher(properties);
    }

    // ------------------------------------------------------------------ 作用域

    @Test
    void catalogScopeCoversEveryAllowedLocation() {
        FakeSts sts = new FakeSts();
        List<String> locations = List.of("s3://wh/db1/t1", "s3://wh/db2/t2");

        VendedStorageCredential vended = managerWith(sts)
                .vendForCatalog(aws("arn:aws:iam::1:role/r", locations), "cat-1", locations, false);

        String policy = sts.last.get().policy();
        // 两个位置都要在票里：少一个的表现是「其中一张表读到一半 403」
        assertTrue(policy.contains("db1/t1/*"), policy);
        assertTrue(policy.contains("db2/t2/*"), policy);
        assertTrue(vended.shortLived(), "签的是短期票");
        assertTrue(vended.token().containsKey("s3.session-token"), vended.token().toString());
    }

    @Test
    void catalogScopeKeepsOneListBucketResourcePerBucket() {
        FakeSts sts = new FakeSts();
        List<String> locations = List.of("s3://wh-a/db/t", "s3://wh-b/db/t");

        managerWith(sts).vendForCatalog(aws("arn:aws:iam::1:role/r", locations), "cat-1",
                locations, false);

        String policy = sts.last.get().policy();
        // ListBucket 的 Resource 是桶：两个桶就是两个 ARN，前缀仍然只能走 Condition
        assertTrue(policy.contains("arn:aws:s3:::wh-a"), policy);
        assertTrue(policy.contains("arn:aws:s3:::wh-b"), policy);
        assertTrue(policy.contains("s3:prefix"), policy);
    }

    @Test
    void catalogWriteTicketCarriesWriteObjects() {
        List<String> locations = List.of("s3://wh/db1/t1");

        FakeSts readSts = new FakeSts();
        managerWith(readSts).vendForCatalog(aws("arn:aws:iam::1:role/r", locations), "cat-1",
                locations, false);
        assertFalse(readSts.last.get().policy().contains("WriteTableObjects"),
                "只读票不该带写权限: " + readSts.last.get().policy());

        FakeSts writeSts = new FakeSts();
        managerWith(writeSts).vendForCatalog(aws("arn:aws:iam::1:role/r", locations), "cat-1",
                locations, true);
        String policy = writeSts.last.get().policy();
        // 删表目录要 DeleteObject；写元数据要 PutObject。少这条的表现是「删到一半 403」
        assertTrue(policy.contains("WriteTableObjects"), policy);
        assertTrue(policy.contains("s3:DeleteObject"), policy);
    }

    @Test
    void catalogTicketIsNamedAfterTheCatalogNotATable() {
        FakeSts sts = new FakeSts();
        List<String> locations = List.of("s3://wh/db1/t1");
        RequestContext.setPrincipal("alice");
        try {
            managerWith(sts).vendForCatalog(aws("arn:aws:iam::1:role/r", locations), "cat-1",
                    locations, false);
        } finally {
            RequestContext.clear();
        }

        String sessionName = sts.last.get().roleSessionName();
        // 云侧审计里要能分开「某个用户读了某张表」与「服务端自己取票」
        assertTrue(sessionName.startsWith("lh-alice-catalog-"), sessionName);
        assertTrue(sessionName.matches("[\\w+=,.@-]{2,64}"), sessionName);
    }

    @Test
    void catalogScopeRefusesToSignWhenNoLocationIsRecognisable() {
        FakeSts sts = new FakeSts();
        List<String> locations = List.of("file:///tmp/wh");

        VendedStorageCredential vended = managerWith(sts)
                .vendForCatalog(aws("arn:aws:iam::1:role/r", locations), "cat-1", locations, false);

        // 认不出桶名时不签：整桶票看起来生效、实际把收窄整个跳过
        assertNull(sts.last.get(), "认不出作用域时不该调用 STS");
        assertFalse(vended.token().containsKey("s3.session-token"), vended.token().toString());
    }

    @Test
    void emptyAllowedLocationsYieldNoTicket() {
        FakeSts sts = new FakeSts();

        VendedStorageCredential vended = managerWith(sts)
                .vendForCatalog(aws("arn:aws:iam::1:role/r", List.of()), "cat-1", List.of(), false);

        assertNull(sts.last.get());
        // 只剩定位键：这是「没有位置可收窄，于是不发密钥」的形状
        assertEquals(Map.of("s3.region", "us-east-1"), vended.token());
    }

    @Test
    void disabledProviderChainKeepsCatalogVendingUnchanged() {
        FakeSts sts = new FakeSts();
        // providers 保持默认空：这是「默认关闭」的验收点，新入口也要守住
        DefaultStorageCredentialManager manager = new DefaultStorageCredentialManager(
                properties, cipher, List.of(new S3TokenCredentialProvider(properties, sts)));
        List<String> locations = List.of("s3://wh/db1/t1");

        VendedStorageCredential vended = manager.vendForCatalog(
                aws("arn:aws:iam::1:role/r", locations), "cat-1", locations, false);

        assertNull(sts.last.get(), "默认链为空时不该有远程调用");
        assertEquals(VendedStorageCredential.PROVIDER_RELAY, vended.provider());
        assertFalse(vended.shortLived());
    }

    // ------------------------------------------------------------------ 缓存

    @Test
    void catalogAndTableTicketsNeverShareACacheKey() {
        AwsStorageConfigInfo storage = aws("arn:aws:iam::1:role/r", List.of("s3://wh/db/t"));

        String catalogKey = StorageCredentialCache.catalogKey("cat-1", storage, false);
        String tableKey = StorageCredentialCache.key("cat-1", storage, "s3://wh/db/t");

        assertNotEquals(catalogKey, tableKey);
        // 读写也要分开：共用一条缓存会把只读票发给要写的那次调用
        assertNotEquals(catalogKey, StorageCredentialCache.catalogKey("cat-1", storage, true));
        assertEquals(catalogKey, StorageCredentialCache.catalogKey("cat-1", storage, false));
    }

    @Test
    void catalogTicketIsCachedButStaticRelayIsNot() {
        FakeSts sts = new FakeSts();
        List<String> locations = List.of("s3://wh/db1/t1", "s3://wh/db2/t2");
        DefaultStorageCredentialManager manager = managerWith(sts);
        StorageCredentialCache cache = new StorageCredentialCache(properties);
        StorageFileIoFactory factory = new StorageFileIoFactory(
                new StorageRuntimePolicy(properties, List.of(manager)), cache);

        CatalogEntity signed = catalog("cat-signed", aws("arn:aws:iam::1:role/r", locations));
        factory.catalogOptionsFor(signed, false);
        factory.catalogOptionsFor(signed, false);
        // 浏览一次目录会连开十几个文件，不缓存就是十几次 STS 调用
        assertEquals(1, sts.calls.get(), "同一个 catalog 的同一档票只该签一次");

        factory.catalogOptionsFor(signed, true);
        assertEquals(2, sts.calls.get(), "读写是两份授权，不能复用");

        // 静态转发档不缓存：管理 API 换过 catalog 凭据之后，服务端不该还拿旧钥匙
        FakeSts unused = new FakeSts();
        properties.getCredential().setProviders(List.of());
        DefaultStorageCredentialManager relay = new DefaultStorageCredentialManager(
                properties, cipher, List.of(new S3TokenCredentialProvider(properties, unused)));
        StorageCredentialCache relayCache = new StorageCredentialCache(properties);
        StorageFileIoFactory relayFactory = new StorageFileIoFactory(
                new StorageRuntimePolicy(properties, List.of(relay)), relayCache);
        AwsStorageConfigInfo noSts = new AwsStorageConfigInfo(
                List.of("s3://wh/db1/t1"), null, "arn:aws:iam::1:role/r", null, null, null, null,
                "us-east-1", null, null, true, null, null, null, null, null);
        CatalogEntity plain = catalog("cat-plain", noSts);
        relayFactory.catalogOptionsFor(plain, false);
        relayFactory.catalogOptionsFor(plain, false);

        assertEquals(0, unused.calls.get());
        assertEquals(0, relayCache.size(), "静态/不签档不进缓存");
    }

    // ------------------------------------------------------------------ 工具

    /** 把 provider 链配成 {@code [s3-token]} 并返回管理器。 */
    private DefaultStorageCredentialManager managerWith(FakeSts sts) {
        S3TokenCredentialProvider provider = new S3TokenCredentialProvider(properties, sts);
        properties.getCredential().setProviders(List.of(provider.name()));
        return new DefaultStorageCredentialManager(properties, cipher, List.of(provider));
    }

    private static CatalogEntity catalog(String id, AwsStorageConfigInfo storage) {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setId(id);
        catalog.setPrefix(id);
        catalog.setStorageType("S3");
        catalog.setStorageConfig(storage);
        catalog.setWarehouse(storage.allowedLocations().get(0));
        return catalog;
    }

    private static AwsStorageConfigInfo aws(String roleArn, List<String> locations) {
        return new AwsStorageConfigInfo(locations, null, roleArn, null, null, null, null,
                "us-east-1", null, null, null, null, null, null, null, null);
    }

    /** 假 STS：记录最后一次命令与调用次数，返回固定寿命的会话。 */
    private static final class FakeSts implements AssumeRoleClient {

        private final AtomicReference<AssumeRoleCommand> last = new AtomicReference<>();

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public StsSession assumeRole(AssumeRoleCommand command) {
            last.set(command);
            calls.incrementAndGet();
            return new StsSession("AKIA-SESSION", "SECRET-SESSION", "TOKEN-SESSION",
                    Instant.now().plus(Duration.ofSeconds(command.duration().toSeconds())));
        }
    }
}
