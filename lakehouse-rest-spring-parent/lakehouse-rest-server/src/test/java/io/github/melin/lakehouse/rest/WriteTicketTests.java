package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.domain.repo.CatalogRepository;
import io.github.melin.lakehouse.rest.domain.repo.DatabaseRepository;
import io.github.melin.lakehouse.rest.domain.repo.TableRepository;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.Access;
import io.github.melin.lakehouse.rest.dto.PermissionDtos.ResourceType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.iceberg.service.IcebergCredentialService;
import io.github.melin.lakehouse.rest.lance.service.LanceCatalogSupport;
import io.github.melin.lakehouse.rest.paimon.service.CredentialService;
import io.github.melin.lakehouse.rest.paimon.service.TableLookup;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.CatalogService;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.service.StorageCredentialManager;
import io.github.melin.lakehouse.rest.service.StorageFileAccess;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * S1 的验收（设计稿 §6.3 / E18）：**票的写权限跟着调用者走，三个协议面各一处**。
 *
 * <p>这条改动的前提是「读写分得开」这件事本来就不该由建 catalog 的人回答：
 * 同一张表，有 {@code UPDATE} 的人要能写数据，只有 {@code SELECT} 的人只能读。
 * 表单上问不出来（问的人也通常不知道谁会来写），而三个取票点都知道**是谁在请求**。
 *
 * <p><b>为什么用 mock 而不是走 HTTP。</b>「签出来的票里有没有 {@code PutObject}」
 * 只有真调云才看得见，而这条链路要验的是**意图有没有传下去**：调用点有没有把
 * {@code allowWrite} 传进管理器、缓存键有没有跟着分开。两件事都在进程内可判，
 * 而走 HTTP 还要求一个能连上的对象存储（本仓库那三个验收类因此是属性驱动 + 缺配置跳过）。
 *
 * <p>缓存键断言写成「以 {@code |rw} / {@code |r} 结尾」：键里前几段是 catalog id、
 * 整份存储配置的 JSON 与表路径，逐字符比对只会变成「改一个字段就红」的脆弱断言，
 * 而这一条要守住的只有「读写不共用一条缓存」。
 */
class WriteTicketTests {

    private static final String CATALOG = "write-ticket";
    private static final String CATALOG_ID = "cat-write-1";
    private static final String DATABASE = "db";
    private static final String TABLE = "orders";
    private static final String PATH = "s3://analytics-bucket/warehouse/db.db/orders";
    private static final String TABLE_ID = "table-write-1";

    private final StorageCredentialManager manager = mock(StorageCredentialManager.class);
    private final StorageCredentialCache cache = mock(StorageCredentialCache.class);
    private final AuthorizationService authorization = mock(AuthorizationService.class);

    // ------------------------------------------------------------------ Paimon

    /** Paimon {@code /token}：有 {@code UPDATE} 就签可写票，且缓存键进的是 {@code rw} 那一档。 */
    @Test
    void paimonTokenSignsAWriteTicketForACallerWhoMayUpdate() {
        givenCallerMayWrite(true);
        CredentialService service = paimonService();

        service.token(CATALOG, DATABASE, TABLE);

        verify(manager).vend(any(), eq(TABLE_ID), eq(PATH), eq(true));
        assertTrue(capturedTableKey().endsWith("|rw"), capturedTableKey());
    }

    /** 只有 {@code SELECT}：同一张表、同一个端点，签的是只读票。 */
    @Test
    void paimonTokenSignsAReadTicketForACallerWhoMayOnlySelect() {
        givenCallerMayWrite(false);
        CredentialService service = paimonService();

        service.token(CATALOG, DATABASE, TABLE);

        verify(manager).vend(any(), eq(TABLE_ID), eq(PATH), eq(false));
        assertTrue(capturedTableKey().endsWith("|r"), capturedTableKey());
    }

    // ------------------------------------------------------------------ Iceberg

    /** Iceberg {@code /credentials}：判据与 Paimon 面逐条相同。 */
    @Test
    void icebergCredentialsFollowTheSameRule() {
        givenCallerMayWrite(true);
        IcebergCredentialService service = new IcebergCredentialService(storagePolicy(), cache,
                authorization);

        service.credentials(catalog(), table(), List.of(DATABASE));

        verify(manager).vend(any(), eq(TABLE_ID), eq(PATH), eq(true));
        assertTrue(capturedTableKey().endsWith("|rw"), capturedTableKey());
    }

    /**
     * 请求头里没要凭据时，连 {@code UPDATE} 都不该去查。
     *
     * <p>{@code loadTable} 是读元数据的高频路径，每次多打一次授权查询换不到任何区别——
     * 那种「顺手查一下」的写法在大表目录里会变成可观测的延迟。
     */
    @Test
    void icebergDelegationWithoutTheHeaderTouchesNothing() {
        IcebergCredentialService service = new IcebergCredentialService(storagePolicy(), cache,
                authorization);

        assertNull(service.delegated(catalog(), table(), List.of(DATABASE), null));

        verify(authorization, never()).allows(any(), any(), any(), any(), any());
        verify(cache, never()).resolve(anyString(), any());
    }

    // ------------------------------------------------------------------ Lance

    /** Lance {@code storage_options}：同一个判据，第三处落点。 */
    @Test
    void lanceStorageOptionsFollowTheSameRule() {
        givenCallerMayWrite(true);

        lanceSupport().storageOptions(catalog(), TABLE_ID, PATH, List.of(DATABASE), TABLE, true);

        verify(manager).vend(any(), eq(TABLE_ID), eq(PATH), eq(true));
    }

    /**
     * 不取票的那条路（{@code vend_credentials=false}）连授权都不查。
     *
     * <p>它只需要定位信息，多判一次只会让「关掉凭据」这件事在授权层留下痕迹——
     * 而那条路径是客户端在读一个公开位置的元数据，与能不能写无关。
     */
    @Test
    void lanceWithoutTheVendFlagDoesNotCheckWriteAccess() {
        // 这一档仍会问管理器要一份凭据：它要拿 token 里的 endpoint / region 拼
        // storage_options（客户端至少得知道去哪儿读），只是不问写权限。这里给一份**带密钥**的票，
        // 正好验到「有秘密也不发」——密钥不能因为「顺手」漏进只读路径
        when(manager.vend(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(shortLived());

        lanceSupport().storageOptions(catalog(), TABLE_ID, PATH, List.of(DATABASE), TABLE, false);

        verify(authorization, never()).allows(any(), any(), any(), any(), eq(Access.UPDATE));
        verify(manager).vend(any(), eq(TABLE_ID), eq(PATH), eq(false));
    }

    // ------------------------------------------------------------------ 辅助

    private void givenCallerMayWrite(boolean mayWrite) {
        when(authorization.allows(eq(CATALOG), eq(ResourceType.TABLE), eq(List.of(DATABASE)),
                eq(TABLE), eq(Access.UPDATE))).thenReturn(mayWrite);
        when(manager.vend(any(), anyString(), anyString(), anyBoolean()))
                .thenReturn(shortLived());
        when(cache.resolve(anyString(), any()))
                .thenAnswer(invocation -> {
                    Supplier<VendedStorageCredential> loader = invocation.getArgument(1);
                    return new StorageCredentialCache.Cached(loader.get(),
                            System.currentTimeMillis() + 60_000L);
                });
    }

    private String capturedTableKey() {
        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(cache).resolve(keys.capture(), any());
        return keys.getValue();
    }

    /**
     * 一份短期票：{@code carriesLongTermKeyPair()} 为假，因此不会额外要求 {@code USE_SECRET}，
     * 本类也就不会被那一道判权挡住——它验的是写权限那一条线。
     */
    private static VendedStorageCredential shortLived() {
        Map<String, String> token = new LinkedHashMap<>();
        token.put("s3.access-key-id", "AKIA-WRITE");
        token.put("s3.secret-access-key", "SECRET-WRITE");
        return new VendedStorageCredential(token, 900L, StorageType.S3,
                VendedStorageCredential.SOURCE_STS_SESSION,
                VendedStorageCredential.PROVIDER_S3_TOKEN, System.currentTimeMillis() + 900_000L);
    }

    private StorageRuntimePolicy storagePolicy() {
        StorageRuntimePolicy policy = mock(StorageRuntimePolicy.class);
        when(policy.credentialManager()).thenReturn(manager);
        return policy;
    }

    private CredentialService paimonService() {
        TableLookup lookup = mock(TableLookup.class);
        when(lookup.requireTable(CATALOG, DATABASE, TABLE)).thenReturn(table());
        CatalogRepository catalogs = mock(CatalogRepository.class);
        when(catalogs.findById(CATALOG_ID)).thenReturn(Optional.of(catalog()));
        return new CredentialService(lookup, catalogs, storagePolicy(), cache, authorization);
    }

    private LanceCatalogSupport lanceSupport() {
        return new LanceCatalogSupport(mock(CatalogService.class), mock(DatabaseRepository.class),
                mock(TableRepository.class), storagePolicy(), authorization,
                mock(StorageFileAccess.class), new RestServerProperties());
    }

    private static CatalogEntity catalog() {
        CatalogEntity catalog = new CatalogEntity();
        catalog.setId(CATALOG_ID);
        catalog.setPrefix(CATALOG);
        catalog.setStorageType("S3");
        catalog.setWarehouse("s3://analytics-bucket/warehouse");
        catalog.setAllowedLocations(List.of("s3://analytics-bucket/warehouse"));
        catalog.setStorageConfig(new AwsStorageConfigInfo(
                List.of("s3://analytics-bucket/warehouse"), null, null, null, null,
                null, null, "us-east-1", "http://minio.internal:9000", null,
                true, null, null, true, null, "AKIA-WRITE", "SECRET-WRITE"));
        return catalog;
    }

    private static TableEntity table() {
        TableEntity table = new TableEntity();
        table.setId(TABLE_ID);
        table.setCatalogId(CATALOG_ID);
        table.setDatabaseId("db-write-1");
        table.setName(TABLE);
        table.setPath(PATH);
        return table;
    }

}
