package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Azure 的『真签发』：拿服务身份的 AAD 令牌换一张用户委托密钥，再给这张表签一张 SAS。
 *
 * <p>这是设计稿 P1-1。与 S3 的 STS 签发相比，两件事完全一样（短期、收窄到表、
 * 服务身份换票），两件事完全不同（键名有**四种形态**、租期有**平台硬上限 7 天**），
 * 后者正是这一层必须单独实现的原因。
 *
 * <p><b>四种键名一起发，是有意的冗余。</b>Azure 的键名随客户端世代变化：
 * 标准形态 {@code adls.sas-token.<account>.<host>}（Spark 经 Iceberg 的 ADLSFileIO）、
 * {@code adls.sas-token.<account>}（Iceberg 1.7.x）、bare {@code adls.sas-token} +
 * {@code adls.account-name}（PyIceberg 经 adlfs/fsspec）。发哪一种都能让**某一种**客户端
 * 工作，而少发一种的表现是「A 引擎能读、B 引擎认证失败」，报错与键名毫无关系。
 * 一个 SAS 字符串的字节数很小，把三种都发出去换来「不挑客户端」，这个交换很划算。
 *
 * <p><b>{@code adls.account-host} 有意不发。</b>它不是凭据，而是「告诉客户端走哪个端点」
 * 的开关：一旦给了，fsspec/adlfs 会拿它去拼 URL，从此走 **DFS** 端点（{@code .dfs.core.windows.net}）
 * 而不是 Blob 端点，而 PyIceberg 的默认实现只认 Blob——结果是「本地能读、PyIceberg 不行」。
 * PyIceberg 自己会从 {@code adls.sas-token.<account>.<host>} 那个键名里推出 account 与 host，
 * 不需要我们代劳。
 *
 * <p><b>作用域按账户收敛，一个账户只签一张。</b>{@link CredentialScope} 那套「多条路径
 * 各签一张」在 Azure 上行不通：下发键只有「每账户一个槽位」，签两张第二张会覆盖第一张，
 * 而覆盖发生在 Map 里，日志里看不到任何异常。所以先把同一账户下的多个位置收敛成
 * 一个共同前缀（一个位置是另一个的父级时就取父级），收敛不了就**拒绝这一档**、
 * 退回原来的定位元数据下发——这比发一张只覆盖最后一次的票诚实。
 */
@Component
@RequiredArgsConstructor
public class AzureSasCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(AzureSasCredentialProvider.class);

    /** 平台硬上限：用户委托 SAS 最长 7 天，写长了服务端直接拒。 */
    public static final long MAX_TTL_SECONDS = 7L * 24 * 3600;

    /** 起始时刻往前放一点：SAS 的 {@code st} 早于「现在」不会失败，晚了会。 */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    /** 公有云的 AAD authority；catalog 没填时用它。 */
    static final String DEFAULT_AUTHORITY = "https://login.microsoftonline.com";

    /** 公有云存储面的 scope；catalog 没填时用它。中国云要填 {@code storage.azure.cn}。 */
    static final String DEFAULT_SCOPE = "https://storage.azure.com/.default";

    static final String KEY_SAS = "adls.sas-token";
    static final String KEY_ACCOUNT_NAME = "adls.account-name";
    static final String KEY_EXPIRES = "adls.sas-token-expires-at-ms";

    private final RestServerProperties properties;

    private final CredentialCipher cipher;

    private final UserDelegationKeyClient client;

    @Override
    public String name() {
        return VendedStorageCredential.PROVIDER_AZURE_SAS;
    }

    @Override
    public Optional<VendedStorageCredential> vend(CredentialContext context) {
        if (!(context.storage() instanceof AzureStorageConfigInfo azure)) {
            return Optional.empty();
        }
        ServiceIdentity identity = identityOf(azure);
        if (identity == null) {
            // 没配服务身份 = 本部署没有那个能力。这不是失败：凭据下发退回「只给定位元数据」，
            // 与接入本 provider 之前完全一致。
            return Optional.empty();
        }
        List<AzureLocation> parsed = new ArrayList<>();
        for (String location : context.scopePaths()) {
            AzureLocation azureLocation = AzureLocation.parse(location);
            if (azureLocation == null) {
                log.warn("ignoring location '{}' of {}: not an abfs/abfss/wasb/wasbs URI, so it"
                        + " cannot be covered by a SAS", location, context.scopeId());
                continue;
            }
            parsed.add(azureLocation);
        }
        if (parsed.isEmpty()) {
            log.warn("cannot derive any Azure container from {} for {}; falling back to"
                    + " metadata-only vending", context.scopePaths(), context.scopeId());
            return Optional.empty();
        }

        Map<String, AzureLocation> perAccount = collapsePerAccount(parsed, context);
        if (perAccount == null) {
            return Optional.empty();
        }

        long ttlSeconds = Math.min(properties.getCredential().getTtlSeconds(), MAX_TTL_SECONDS);
        Instant now = Instant.now();
        Instant start = now.minus(CLOCK_SKEW);
        Instant expiry = now.plusSeconds(ttlSeconds);
        String startText = AzureSasToken.TIME.format(start);
        String expiryText = AzureSasToken.TIME.format(expiry);
        String permissions = AzureSasToken.permissions(context.allowWrite());

        Map<String, String> token = new LinkedHashMap<>();
        DefaultStorageCredentialManager.putAzureLocators(azure, token);
        String expiresAt = Long.toString(expiry.toEpochMilli());
        for (AzureLocation location : perAccount.values()) {
            UserDelegationKey key = client.requestKey(new AzureDelegationRequest(
                    location.account(), location.blobHost(),
                    identity.tenantId(), identity.clientId(), identity.clientSecret(),
                    identity.authority(), identity.scope(), start, expiry));
            String sas = AzureSasToken.build(location, permissions, startText, expiryText, key);
            // 标准形态：按「账户 + 完整存储 DNS 名」，Spark 经 Iceberg 的 ADLSFileIO 认这个
            token.put(KEY_SAS + "." + location.account() + "." + location.host(), sas);
            // Iceberg 1.7.x 认这个
            token.put(KEY_SAS + "." + location.account(), sas);
            token.put(KEY_EXPIRES + "." + location.account() + "." + location.host(), expiresAt);
            log.info("vended provider={} SAS for {} (account {}, resource {}, permissions {},"
                            + " expiresIn={}s)",
                    name(), context.scopeId(), location.account(),
                    location.path().isEmpty() ? "container " + location.container()
                            : "directory " + location.path(),
                    permissions, ttlSeconds);
        }
        if (perAccount.size() == 1) {
            // bare 形态只在单一账户时有意义：一个无后缀的值表达不了两个账户，
            // 而写进 Map 之后「最后写入者赢」——那会让 PyIceberg 拿 B 账户的票去读 A 账户的桶。
            AzureLocation only = perAccount.values().iterator().next();
            String sas = token.get(KEY_SAS + "." + only.account() + "." + only.host());
            token.put(KEY_SAS, sas);
            token.put(KEY_ACCOUNT_NAME, only.account());
            token.put(KEY_EXPIRES, expiresAt);
        } else {
            log.info("{} spans accounts {}; the bare '{}' key is omitted because one unsuffixed"
                            + " value cannot express several accounts",
                    context.scopeId(), perAccount.keySet(), KEY_SAS);
        }
        return Optional.of(new VendedStorageCredential(token, ttlSeconds,
                azure.storageType(), VendedStorageCredential.SOURCE_AZURE_SAS,
                name(), expiry.toEpochMilli()));
    }

    /**
     * 把一组位置收敛成「每个账户一个」。收敛不了返回 {@code null}（调用方退档）。
     *
     * <p>两条规则，都来自 Azure 的表达力而不是我们的偏好：
     * <ol>
     *   <li>同一账户下的位置必须落在**同一个容器**里。SAS 是容器（或容器内目录）级的，
     *       跨容器的票在同一个账户上无法用两个键表达；</li>
     *   <li>同一账户下的多条路径取**共同父目录**。一条位置是其余位置的父级时就是它；
     *       互不相干（{@code /a/x} 与 {@code /b/y}）时共同父目录是容器根，
     *       也就是退化成整容器的票。</li>
     * </ol>
     *
     * <p>第 2 条的后半段是「静默放宽」，所以它不是默默发生的：{@link #collapsePerAccount}
     * 会把「原本要收窄到某个目录、结果只能给整容器」这件事写进 WARN。降级到整容器仍然比
     * 「拒绝签发、让引擎自己想办法」好——后者在开了这套配置之后立刻表现为读不了表。
     */
    private Map<String, AzureLocation> collapsePerAccount(List<AzureLocation> locations,
                                                          CredentialContext context) {
        Map<String, List<AzureLocation>> byAccount = new LinkedHashMap<>();
        for (AzureLocation location : locations) {
            byAccount.computeIfAbsent(location.account(), ignored -> new ArrayList<>()).add(location);
        }
        Map<String, AzureLocation> collapsed = new LinkedHashMap<>();
        for (Map.Entry<String, List<AzureLocation>> entry : byAccount.entrySet()) {
            Set<String> containers = new LinkedHashSet<>();
            for (AzureLocation location : entry.getValue()) {
                containers.add(location.container());
            }
            if (containers.size() > 1) {
                // 一个账户两个容器：键名只有一个槽位，签两张会互相覆盖。
                // 不发比发一张只覆盖一半的强——后者看起来成功，另一半读的时候才 403。
                log.warn("account {} is used by several containers {} in {}; a SAS cannot cover"
                                + " more than one container per account, so this catalog keeps"
                                + " the metadata-only behaviour",
                        entry.getKey(), containers, context.scopeId());
                return null;
            }
            AzureLocation first = entry.getValue().get(0);
            String prefix = commonPrefix(entry.getValue());
            if (!first.hierarchical() && !prefix.isEmpty()) {
                // wasbs:// 是 Blob 端点，没有目录级 SAS。降到整容器必须留痕：
                // 这张票覆盖的范围比「这张表」大，而出事时日志里只会有一行正常签发。
                log.warn("{} uses {}, a flat-namespace Blob endpoint without directory scope:"
                                + " the SAS for account {} covers the whole container '{}',"
                                + " not '{}'",
                        context.scopeId(), first.scheme(), first.account(), first.container(), prefix);
                prefix = "";
            }
            collapsed.put(entry.getKey(), new AzureLocation(first.scheme(), first.container(),
                    first.account(), first.host(), prefix));
        }
        return collapsed;
    }

    /**
     * 多条路径的共同父目录（按路径段算，不按字符算）。
     *
     * <p>按字符算会得到 {@code wh/db} 这种「前一半相同、后一半不同」的假父级——
     * {@code wh/db/orders} 与 {@code wh/db2/items} 的字符公共前缀是 {@code wh/db}，
     * 但它既不是前者也不是后者的父目录，签出来的票覆盖不到任何一个。按段比较就不会。
     */
    static String commonPrefix(List<AzureLocation> locations) {
        List<String> segments = null;
        for (AzureLocation location : locations) {
            List<String> own = location.path().isEmpty()
                    ? List.of() : List.of(location.path().split("/"));
            if (segments == null) {
                segments = own;
                continue;
            }
            int shared = 0;
            while (shared < segments.size() && shared < own.size()
                    && segments.get(shared).equals(own.get(shared))) {
                shared++;
            }
            segments = segments.subList(0, shared);
        }
        return segments == null || segments.isEmpty() ? "" : String.join("/", segments);
    }

    /**
     * 本次要用的服务身份；catalog 上没配齐时返回 {@code null}。
     *
     * <p>四样东西全部来自 catalog：{@code tenantId}（规格自带，表达「这份数据在哪个租户」）、
     * {@code clientId} / {@code clientSecret}（应用注册，按 `credential-secret-key` 加密落库），
     * 以及 {@code authority} / {@code scope}（云环境，留空取公有云缺省）。
     *
     * <p><b>为什么不再有服务端兜底。</b>一套部署里同时存在公有云与中国云的 catalog
     * 是正常形态，而租户与应用注册也各不相同；把其中任何一项放到全局配置里，
     * 都会让「另一个 catalog」悄悄用错身份——错误现场是 {@code invalid_resource} 或
     * 一个 audience 不对的令牌，离「配置放错了层」隔着好几步。
     */
    private ServiceIdentity identityOf(AzureStorageConfigInfo azure) {
        StorageConfigs.StorageSecrets principal = StorageConfigs.secrets(azure, cipher);
        if (principal == null || blank(principal.id()) || blank(principal.secret())
                || blank(azure.tenantId())) {
            return null;
        }
        String authority = blank(azure.authority()) ? DEFAULT_AUTHORITY : azure.authority();
        String scope = blank(azure.scope()) ? DEFAULT_SCOPE : azure.scope();
        return new ServiceIdentity(azure.tenantId(), principal.id(), principal.secret(),
                authority, scope);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    /** 换票用的服务身份（应用注册）与它的 AAD 端点。 */
    private record ServiceIdentity(String tenantId, String clientId, String clientSecret,
                                   String authority, String scope) {
    }
}
