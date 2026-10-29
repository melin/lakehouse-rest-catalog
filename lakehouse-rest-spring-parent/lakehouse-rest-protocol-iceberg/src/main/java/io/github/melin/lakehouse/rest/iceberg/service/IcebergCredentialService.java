package io.github.melin.lakehouse.rest.iceberg.service;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.domain.entity.TableEntity;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.iceberg.dto.IcebergDtos;
import io.github.melin.lakehouse.rest.service.StorageCredentialCache;
import io.github.melin.lakehouse.rest.service.StorageRuntimePolicy;
import io.github.melin.lakehouse.rest.support.NamespacePaths;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 *
 * <p><b>续期广告只在短期凭据时出现。</b>{@code client.refresh-credentials-endpoint}
 * 是一句承诺：客户端到点会去那里换票。转发档没有可续的东西，广告它等于让客户端
 * 在 TTL 之后拿着「换不到新票」的地址去请求，最终表现成一组与权限无关的 400。
 * 判据就是凭据自己的 {@code expireTimeInMs}——不为零才广告。
 */
@Service
@RequiredArgsConstructor
public class IcebergCredentialService {

    private static final Logger log = LoggerFactory.getLogger(IcebergCredentialService.class);

    /** {@code X-Iceberg-Access-Delegation} 里表示「把凭据直接发给我」的取值。 */
    private static final String VENDED_CREDENTIALS = "vended-credentials";

    /** 客户端用来续期的非敏感配置项，规格里 kind 是 {@code config}（不是 credential）。 */
    static final String KEY_REFRESH_ENDPOINT = "client.refresh-credentials-endpoint";

    private final StorageRuntimePolicy storagePolicy;
    private final StorageCredentialCache cache;

    /**
     * 一次委托的结果：凭据条目 + 它能不能续期。
     *
     * <p>为什么不只返回列表：{@code loadTable} 的 {@code config} 要不要带续期地址，
     * 取决于凭据是短期还是静态。这个信息只有签发层知道，而它一旦在下发之后丢失，
     * 调用方就只能靠「token 里有没有 session-token」去猜——那是**键名**层面的推断，
     * 换一种存储就失效，也正是本工程设计稿反复要求避免的「用键的形态反推状态」。
     */
    public record Delegated(List<IcebergDtos.StorageCredential> credentials, boolean refreshable) {

        /** 有凭据且可续期时才需要广告续期端点。 */
        public boolean advertisesRefresh() {
            return refreshable && credentials != null && !credentials.isEmpty();
        }
    }

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
    public Delegated delegated(CatalogEntity catalog, TableEntity table, String delegation) {
        if (delegation == null || delegation.isBlank()) {
            return null;
        }
        if (!requestsVendedCredentials(delegation)) {
            log.debug("client asked for access delegation \"{}\" on {}.{}; only vended-credentials"
                            + " is implemented, sending no credentials",
                    delegation, table.getDatabaseId(), table.getName());
            return null;
        }
        StorageCredentialCache.Cached cached = cached(catalog, table);
        return new Delegated(List.of(new IcebergDtos.StorageCredential(table.getPath(),
                cached.credential().token())), cached.credential().shortLived());
    }

    /**
     * {@code GET .../tables/{table}/credentials}：无条件下发一份凭据。
     *
     * <p>与 {@link #delegated} 的差别就是这个端点存在的意义——它本身就是客户端的
     * 「我要凭据」请求，不需要再看请求头。签不出来时如实把错误回给客户端
     * （例如 {@code credential-manager.type=noop} 的部署回 501），
     * 与 Paimon 面 {@code /token} 的处置一致：明明做不到却回一个空的成功响应，
     * 会让引擎在真正读数据时才失败，错误现场离原因很远。
     *
     * <p><b>两个字段名都发。</b>规格副本写 {@code storage-credentials}，而
     * iceberg-java 1.10/1.11 的 {@code LoadCredentialsResponse} 方法名是
     * {@code credentials()} 且**没有** {@code @JsonProperty}——它按字段名反序列化。
     * 两种客户端都在野，只发一个就会出现「刷新请求反序列化出空列表」，
     * 报 {@code Invalid S3 Credentials: empty}：一个不含任何凭据线索的错（设计稿 G5）。
     * 同一份内容发两遍的代价是两个 map 的重复字符，换的是不必猜客户端版本。
     */
    @Transactional(readOnly = true)
    public IcebergDtos.LoadCredentialsResponse credentials(CatalogEntity catalog, TableEntity table) {
        IcebergDtos.StorageCredential entry = new IcebergDtos.StorageCredential(table.getPath(),
                cached(catalog, table).credential().token());
        List<IcebergDtos.StorageCredential> single = List.of(entry);
        return new IcebergDtos.LoadCredentialsResponse(single, single);
    }

    /**
     * {@code loadTable} 响应里的 {@code config}：告诉客户端去哪续期。
     *
     * <p>返回 {@code null} 表示「不给 config」（静态凭据、或本次根本没下发凭据）。
     * 不给而不是给一个空 map：规格里两者都能被接受，但空 map 会让某些客户端
     * 认为「服务端支持 config 机制、只是这次没配置」，进而去猜默认路径。
     *
     * <p>地址给**相对路径**：{@code AwsClientProperties} 用
     * {@code RESTUtil.resolveEndpoint(catalog.uri, endpoint)} 解析它，相对路径在
     * 反向代理、多域名、改前缀的部署下都跟着 catalog 的 uri 走；写绝对地址则会
     * 指向服务端自己以为的地址，在网关后面几乎必错。
     */
    public Map<String, String> refreshConfig(String prefix, List<String> levels, String tableName,
                                            Delegated delegated) {
        if (delegated == null || !delegated.advertisesRefresh()) {
            return null;
        }
        String endpoint = "/v1/" + prefix + "/namespaces/" + NamespacePaths.format(levels)
                + "/tables/" + tableName + "/credentials";
        Map<String, String> config = new LinkedHashMap<>();
        config.put(KEY_REFRESH_ENDPOINT, endpoint);
        return config;
    }

    /**
     * 取一份范围限定到这张表、有效期内可复用的凭据。
     *
     * <p>缓存键与 Paimon 侧同源（{@code StorageCredentialCache.key}），于是同一张表
     * 从两个协议面各取一次凭据时命中同一份——否则「同一个引擎、同一张表、两把不同的钥匙」
     * 会在密钥轮换时表现成一半请求失败。
     */
    private StorageCredentialCache.Cached cached(CatalogEntity catalog, TableEntity table) {
        StorageConfigInfo storage = StorageConfigs.of(catalog);
        String key = StorageCredentialCache.key(catalog.getId(), storage, table.getPath());
        long now = System.currentTimeMillis();
        return cache.get(key).orElseGet(() -> cache.put(key,
                storagePolicy.credentialManager().vend(storage, table.getId(), table.getPath()), now));
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
