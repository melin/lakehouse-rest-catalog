package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Iceberg 侧的凭据委托：把对象存储的访问密钥下发给引擎。
 *
 * <p><b>与 Paimon 侧的 {@code CredentialService} 是同一件事的两个协议形状。</b>
 * Paimon 的 {@code GET .../tables/{table}/token} 回一段扁平配置，Iceberg 的
 * {@code storage-credentials} 回一个 {@code {prefix, config}} 列表（客户端按最长前缀匹配）。
 * 两者都落在 {@code StorageRuntimePolicy} 选定的 {@code StorageCredentialManager} 与
 * {@code StorageCredentialCache} 上——键名族、密钥从哪一级取、缓存与过期时刻的算法
 * 都只有一份。这是「表统一」在本特性里最直接的收益：换一种存储类型只需要改一个管理器，
 * 两个协议面同时生效。
 *
 * <p><b>不下发是默认行为。</b>只有客户端在 {@code X-Iceberg-Access-Delegation} 里
 * 明确要求 {@code vended-credentials} 时才去签凭据。规格给客户端的承诺是
 * 「服务端可以从所列机制里任选、也可以一个都不给」，因此「不问不给」是合规的，
 * 而且它避免了每次 load 表都去碰一次凭据管理后端（那是要钱的调用）。
 * 这也解释了下面那个常量里的连字符——它是规格里逐字的值，不是笔误。
 */
@Service
@RequiredArgsConstructor
public class IcebergCredentialService {

    private static final Logger log = LoggerFactory.getLogger(IcebergCredentialService.class);

    /** {@code X-Iceberg-Access-Delegation} 里表示「把凭据直接发给我」的取值。 */
    private static final String VENDED_CREDENTIALS = "vended-credentials";

    private final StorageRuntimePolicy storagePolicy;
    private final StorageCredentialCache cache;

    /**
     * 按请求头决定要不要下发凭据。
     *
     * <p>返回值 {@code null} 表示「本次不下发」，而不是空列表：规格里
     * {@code storage-credentials} 缺席与空数组的含义不同——前者是「服务端不支持或没给」，
     * 后者是「给了，但一个都不适用」。序列化成 JSON 时前者是字段不存在，后者是
     * {@code []}，而客户端对两者的日志不同。
     *
     * @param delegation {@code X-Iceberg-Access-Delegation} 的原值，可为 {@code null}
     */
    @Transactional(readOnly = true)
    public List<IcebergDtos.StorageCredential> delegated(CatalogEntity catalog, TableEntity table,
                                                        String delegation) {
        if (delegation == null || delegation.isBlank()) {
            return null;
        }
        if (!requestsVendedCredentials(delegation)) {
            log.debug("client asked for access delegation \"{}\" on {}.{}; only vended-credentials"
                            + " is implemented, sending no credentials",
                    delegation, table.getDatabaseId(), table.getName());
            return null;
        }
        return List.of(new IcebergDtos.StorageCredential(table.getPath(), credential(catalog, table)));
    }

    /**
     * {@code GET .../tables/{table}/credentials}：无条件下发一份凭据。
     *
     * <p>与 {@link #delegated} 的差别就是这个端点存在的意义——它本身就是客户端的
     * 「我要凭据」请求，不需要再看请求头。签不出来时如实把错误回给客户端
     * （例如 {@code credential-manager.type=noop} 的部署回 501），
     * 与 Paimon 面 {@code /token} 的处置一致：明明做不到却回一个空的成功响应，
     * 会让引擎在真正读数据时才失败，错误现场离原因很远。
     */
    @Transactional(readOnly = true)
    public IcebergDtos.LoadCredentialsResponse credentials(CatalogEntity catalog, TableEntity table) {
        return new IcebergDtos.LoadCredentialsResponse(
                List.of(new IcebergDtos.StorageCredential(table.getPath(), credential(catalog, table))));
    }

    /**
     * 取一份范围限定到这张表、有效期内可复用的凭据。
     *
     * <p>缓存键与 Paimon 侧同源（{@code StorageCredentialCache.key}），于是同一张表
     * 从两个协议面各取一次凭据时命中同一份——否则「同一个引擎、同一张表、两把不同的钥匙」
     * 会在密钥轮换时表现成一半请求失败。
     */
    private java.util.Map<String, String> credential(CatalogEntity catalog, TableEntity table) {
        StorageConfigInfo storage = StorageConfigs.of(catalog);
        String key = StorageCredentialCache.key(catalog.getId(), storage, table.getPath());
        long now = System.currentTimeMillis();
        StorageCredentialCache.Cached cached = cache.get(key).orElseGet(() -> cache.put(key,
                storagePolicy.credentialManager().vend(storage, table.getId(), table.getPath()), now));
        return cached.credential().token();
    }

    /**
     * 请求头里是否包含 {@code vended-credentials}。
     *
     * <p>按逗号切成机制名再逐个比对，而不是 {@code contains}：头部里可能有
     * {@code remote-signing-vended-credentials} 这类将来才会出现的值，
     * 子串匹配会把它误判成我们支持的那一种。大小写不敏感是规格的要求
     * （HTTP 头里的枚举值按 RFC 7230 是大小写不敏感的）。
     */
    private static boolean requestsVendedCredentials(String delegation) {
        for (String mechanism : delegation.split(",")) {
            if (VENDED_CREDENTIALS.equals(mechanism.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
