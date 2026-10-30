package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.lance.service.LanceCatalogSupport;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.service.StorageCredentialManager;
import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Lance 面的取票判权（设计稿 §3.4.3 / P1-7 的第三处落点）。
 *
 * <p><b>为什么这一面要单独测。</b>Paimon 与 Iceberg 的「能取票」由
 * {@code CatalogAccessRules} 按路径判，映射表就是证据；Lance 没有凭据端点——
 * 取票是 {@code describe} 上的 {@code vend_credentials=true}，路径完全相同，
 * 因此两个点都落在 {@code LanceCatalogSupport#storageOptions} 里。这层逻辑不写用例，
 * 「同一张表、带不带那个参数」就是一条没人验过的分叉。
 *
 * <p>用 mock 而不是起 Spring：这里要验的是**判定顺序与判据**
 * （先 {@code USE_CREDENTIAL}、票里有长期密钥再 {@code USE_SECRET}），
 * 不是 HTTP 形状——后者由 {@code CredentialAccessSplitTests} 覆盖。
 */
class LanceCredentialAccessTests {

    private static final String CATALOG = "lake";
    private static final String TABLE_ID = "tbl-1";
    private static final String LOCATION = "s3://bucket/wh/schema/orders.lance";
    private static final List<String> NAMESPACE = List.of("schema");

    private final StorageRuntimePolicy policy = mock(StorageRuntimePolicy.class);
    private final StorageCredentialManager manager = mock(StorageCredentialManager.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);

    private LanceCatalogSupport support() {
        when(policy.credentialManager()).thenReturn(manager);
        return new LanceCatalogSupport(mock(CatalogService.class), mock(DatabaseRepository.class),
                mock(TableRepository.class), policy, authorization, mock(StorageFileAccess.class),
                new RestServerProperties());
    }

    private static CatalogEntity catalog() {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setPrefix(CATALOG);
        catalog.setStorageType("S3");
        return catalog;
    }

    private static VendedStorageCredential credential(String source, long expireTimeInMs) {
        Map<String, String> token = new LinkedHashMap<>();
        token.put("s3.access-key-id", "AKIA-LANCE");
        token.put("s3.secret-access-key", "SECRET-LANCE");
        token.put("s3.endpoint", "http://minio.internal:9000");
        return new VendedStorageCredential(token, 3600L, StorageType.S3, source,
                VendedStorageCredential.PROVIDER_RELAY, expireTimeInMs);
    }

    /**
     * 没授「能取票」：带 {@code vend_credentials=true} 直接 403，点名 {@code USE_CREDENTIAL}。
     *
     * <p>拒绝方向必须是**失败关闭**：静默返回一份不含密钥的 {@code storage_options} 会让
     * 客户端在开数据集时拿到一个指向存储的错，而真正的原因在授权层。
     */
    @Test
    void vendCredentialsRequiresTheCredentialGrant() {
        when(manager.vend(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(credential(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, 0L));
        doThrow(ApiException.forbidden("lacks USE_CREDENTIAL"))
                .when(authorization).require(eq(CATALOG), eq(ResourceType.TABLE), eq(NAMESPACE),
                        eq("orders"), eq(Access.USE_CREDENTIAL), anyString());

        ApiException error = assertThrows(ApiException.class, () ->
                support().storageOptions(catalog(), TABLE_ID, LOCATION, NAMESPACE, "orders", true));

        assertTrue(error.getMessage().contains("USE_CREDENTIAL"), error.getMessage());
    }

    /**
     * 有「能取票」但票里是目录存的那对长期密钥：还要 {@code USE_SECRET}。
     *
     * <p>这是本次拆分的重点：旧行为下 {@code describe} 这一个读元数据的动作就能把
     * 没有时限的云凭据带走，而它只需要 {@code TABLE:DESCRIBE}。
     */
    @Test
    void storedKeyPairAlsoNeedsTheSecretGrant() {
        when(manager.vend(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(credential(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, 0L));
        doThrow(ApiException.forbidden("lacks USE_SECRET"))
                .when(authorization).require(eq(CATALOG), eq(ResourceType.TABLE), eq(NAMESPACE),
                        eq("orders"), eq(Access.USE_SECRET), anyString());

        ApiException error = assertThrows(ApiException.class, () ->
                support().storageOptions(catalog(), TABLE_ID, LOCATION, NAMESPACE, "orders", true));

        assertTrue(error.getMessage().contains("USE_SECRET"), error.getMessage());
    }

    /** 短期票只要能取票：它是现签、收窄到表、到点作废的，不该再要一个密钥权限。 */
    @Test
    void shortLivedTicketNeedsOnlyTheCredentialGrant() {
        when(manager.vend(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(credential(VendedStorageCredential.SOURCE_STS_SESSION,
                        System.currentTimeMillis() + 900_000L));

        Map<String, String> options =
                support().storageOptions(catalog(), TABLE_ID, LOCATION, NAMESPACE, "orders", true);

        // 换过键名：Lance 的 Rust 侧只认 aws_*，原样透传等于没下发
        assertEquals("AKIA-LANCE", options.get("aws_access_key_id"));
        assertTrue(options.containsKey("expires_at_millis"), options.toString());
        verify(authorization).require(eq(CATALOG), eq(ResourceType.TABLE), eq(NAMESPACE),
                eq("orders"), eq(Access.USE_CREDENTIAL), anyString());
        verify(authorization, never()).require(any(), any(), any(), any(), eq(Access.USE_SECRET),
                anyString());
    }

    /** 不带那个参数就不取票，也就不判权——定位信息照发，且里面不能有密钥。 */
    @Test
    void withoutTheVendFlagNothingIsVendedOrChecked() {
        when(manager.vend(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(credential(VendedStorageCredential.SOURCE_STATIC_CREDENTIALS, 0L));

        Map<String, String> options =
                support().storageOptions(catalog(), TABLE_ID, LOCATION, NAMESPACE, "orders", false);

        assertFalse(options.containsKey("aws_secret_access_key"), options.toString());
        assertFalse(options.containsKey("aws_access_key_id"), options.toString());
        verifyNoInteractions(authorization);
        // 也不该凭空多出到期时刻：客户端会据它去刷新一个拿不到的票
        assertFalse(options.containsKey("expires_at_millis"), options.toString());
    }
}
