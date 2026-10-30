package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.iceberg.service.IcebergCredentialService;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Iceberg 的 {@code loadTable} 委派：缺权限时**不带凭据**，而不是报 403。
 *
 * <p>这条路径与 `/credentials` 的差别就是这个类存在的理由：它同时承载元数据读取，
 * 而 {@code X-Iceberg-Access-Delegation: vended-credentials} 只是「顺便把凭据也带上」。
 * 规格明确允许服务端一个机制都不选，因此「没权限就不带」既合规又不连坐；
 * 打成 403 会让「看表结构」跟着失败。{@link CredentialAccessSplitTests} 验的是
 * 另外两个端点「必须报错」的那一侧，这里验「必须安静」的那一侧——两种姿势都有用例，
 * 才不会在改动时被顺手统一掉。
 *
 * <p>用 mock：要验的是「判了哪几个 access、返回什么」，起完整上下文只会把这两件事埋掉。
 */
class IcebergDelegationAccessTests {

    private static final String CATALOG = "ice";
    private static final List<String> LEVELS = List.of("ns");
    private static final String TABLE = "orders";

    private final StorageCredentialCache cache = mock(StorageCredentialCache.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);

    private IcebergCredentialService service(VendedStorageCredential credential) {
        // 服务层走 singleflight 的 resolve（P2-1），所以打桩的是它而不是 get/put。
        // 打桩 get 会让 mock 返回 null，用例的失败信息变成 NPE——
        // 而真正要验的是「判了哪几个 access」，不该被这条噪音挡住。
        when(cache.resolve(anyString(), any())).thenReturn(
                new StorageCredentialCache.Cached(credential, Long.MAX_VALUE, Long.MAX_VALUE));
        return new IcebergCredentialService(mock(StorageRuntimePolicy.class), cache, authorization);
    }

    private static CatalogEntity catalog() {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setPrefix(CATALOG);
        catalog.setStorageType("S3");
        return catalog;
    }

    private static TableEntity table() {
        TableEntity table = new TableEntity();
        table.setId("tbl-ice");
        table.setName(TABLE);
        table.setPath("s3://bucket/wh/ns.db/orders");
        return table;
    }

    private static VendedStorageCredential credential(String source, long expireTimeInMs) {
        Map<String, String> token = new LinkedHashMap<>();
        token.put("s3.access-key-id", "AKIA-ICE");
        token.put("s3.secret-access-key", "SECRET-ICE");
        return new VendedStorageCredential(token, 3600L, StorageType.S3, source,
                VendedStorageCredential.PROVIDER_RELAY, expireTimeInMs);
    }

    /** 没有「能取票」：元数据照给，凭据不带。 */
    @Test
    void missingTheCredentialGrantSkipsVendingWithoutFailingTheLoad() {
        when(authorization.allows(any(), any(), any(), any(), eq(Access.USE_CREDENTIAL)))
                .thenReturn(false);

        IcebergCredentialService.Delegated delegated = service(
                credential(VendedStorageCredential.SOURCE_STS_SESSION, Long.MAX_VALUE / 2))
                .delegated(catalog(), table(), LEVELS, "vended-credentials");

        assertNull(delegated, "缺 USE_CREDENTIAL 时不该带凭据，但 loadTable 本身要继续成功");
    }

    /** 有「能取票」但没「能拿存着的密钥」，而票里正是那对长期密钥：同样不带。 */
    @Test
    void longTermKeyPairWithoutTheSecretGrantIsNotEmbedded() {
        when(authorization.allows(any(), any(), any(), any(), eq(Access.USE_CREDENTIAL)))
                .thenReturn(true);
        when(authorization.allows(any(), any(), any(), any(), eq(Access.USE_SECRET)))
                .thenReturn(false);

        IcebergCredentialService.Delegated delegated = service(
                credential(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, 0L))
                .delegated(catalog(), table(), LEVELS, "vended-credentials");

        assertNull(delegated, "长期密钥对没授 USE_SECRET 就不能随 loadTable 一起出去");
    }

    /** 短期票 + 能取票：照常带上，并且广告续期地址（客户端到点会去换票）。 */
    @Test
    void shortLivedTicketIsEmbeddedWithARefreshEndpoint() {
        when(authorization.allows(any(), any(), any(), any(), eq(Access.USE_CREDENTIAL)))
                .thenReturn(true);

        IcebergCredentialService.Delegated delegated = service(
                credential(VendedStorageCredential.SOURCE_STS_SESSION, Long.MAX_VALUE / 2))
                .delegated(catalog(), table(), LEVELS, "vended-credentials");

        assertNotNull(delegated);
        assertNotNull(delegated.credentials(), "短期票要真的带出去，不能只回一个空信封");
        assertTrue(delegated.advertisesRefresh(), "短期票要广告续期端点，客户端到点才有地方换票");
    }
}
