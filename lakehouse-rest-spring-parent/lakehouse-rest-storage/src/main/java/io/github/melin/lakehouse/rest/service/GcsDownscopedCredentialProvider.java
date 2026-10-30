package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GCS 的『真签发』：用服务端现有的访问令牌换一个「只能碰这张表」的令牌。
 *
 * <p>这是设计稿 P1-4。它是四家云里**唯一不需要新凭据**的一方：源令牌
 * （{@code lakehouse.rest.storage.gcp.token}）本来就在配置里，这一档只是把它
 * 换小。所以「启用条件」也比别家简单——有令牌就签，没令牌就不签。
 *
 * <p><b>没有令牌时不签，而且不降级成「转发一个更小的票」。</b>转发档本来就会
 * 把这个令牌原样发给引擎；如果这里在拿不到新令牌时返回一个「看起来一样」的结果，
 * 上层就无法区分「收窄成功」与「没收窄」——那正是设计稿反复警告的那类故障。
 * 所以只回答两件事：签成功，或者「我不适用」。
 *
 * <p><b>1 小时是平台上限。</b>downscoped token 最长 3600 秒，配得更长会被拒；
 * 这里夹紧并留痕，理由与 OSS 那一档相同：TTL 是部署级配置，不该被一个存储类型
 * 反过来限制其它类型的取值。
 *
 * <p><b>没有续期落点。</b>GCS 的 FileIO 不会自己再换一次票（设计 §9.4），
 * 所以票到期后长作业会开始 401/403，而「把 TTL 调大」被 3600 卡死。
 */
@Component
@RequiredArgsConstructor
public class GcsDownscopedCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(GcsDownscopedCredentialProvider.class);

    /** downscoped token 的平台上限：1 小时。 */
    static final long MAX_TTL_SECONDS = 3600L;

    /** 低于这个值时交换没有意义（一次 loadTable 就可能跑完），按它兜底。 */
    static final long MIN_TTL_SECONDS = 60L;

    static final String KEY_TOKEN = "gcs.oauth2.token";
    static final String KEY_EXPIRES = "gcs.oauth2.token-expires-at";

    private final RestServerProperties properties;

    private final GcsDownscopeClient client;

    @Override
    public String name() {
        return VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE;
    }

    @Override
    public Optional<VendedStorageCredential> vend(CredentialContext context) {
        if (!(context.storage() instanceof GcpStorageConfigInfo gcs)) {
            return Optional.empty();
        }
        String source = properties.getStorage().getGcp().getToken();
        if (source == null || source.isBlank()) {
            // 没有源令牌 = 本部署没有身份可换。退回「引擎用自己的 ADC」那一档，
            // 不是失败：绝大多数 GKE 部署就是靠 Workload Identity 直连的。
            return Optional.empty();
        }
        List<CredentialScope> scopes = context.scopes();
        if (scopes.isEmpty()) {
            log.warn("cannot derive a GCS scope from {} for {}; refusing to exchange an"
                    + " unbound token", context.scopePaths(), context.scopeId());
            return Optional.empty();
        }
        String options;
        try {
            options = GcsBoundary.json(scopes, context.allowWrite());
        } catch (IllegalArgumentException tooMany) {
            // 超过 10 条规则时 STS 会拒整份 boundary；在本地拒掉，日志里说清楚
            log.warn("{} spans {} locations, more than the {} rules a GCS credential access"
                            + " boundary accepts; not vending a downscoped token",
                    context.scopeId(), scopes.size(), GcsBoundary.MAX_RULES);
            return Optional.empty();
        }
        long ttl = clamp(properties.getCredential().getTtlSeconds());
        if (ttl != properties.getCredential().getTtlSeconds()) {
            log.info("clamped the GCS token lifetime from {}s to {}s for {} (downscoped tokens"
                            + " live at most {}s)",
                    properties.getCredential().getTtlSeconds(), ttl, context.scopeId(),
                    MAX_TTL_SECONDS);
        }
        if (scopes.stream().anyMatch(CredentialScope::bucketRoot)) {
            log.warn("{} sits at a bucket root; the GCS boundary can only cover the whole"
                    + " bucket: {}", context.scopeId(), scopes);
        }
        GcsDownscopedToken token = client.downscope(new GcsDownscopeRequest(
                source, options, properties.getStorage().getGcp().getStsEndpoint()));

        Map<String, String> keys = new LinkedHashMap<>();
        if (gcs.gcsServiceAccount() != null && !gcs.gcsServiceAccount().isBlank()) {
            // 定位信息照发：引擎要知道这是哪个服务账号在访问（它决定配额与审计归属）
            keys.put("gcs.service-account", gcs.gcsServiceAccount());
        }
        keys.put(KEY_TOKEN, token.accessToken());
        long expiresAt = expiresAt(token, ttl);
        keys.put(KEY_EXPIRES, Long.toString(expiresAt));

        long ttlSeconds = Math.max(1L, (expiresAt - System.currentTimeMillis()) / 1000L);
        log.info("vended provider={} credentials for {} (source token downscoped to {} rule(s),"
                        + " expiresIn={}s)",
                name(), context.scopeId(), scopes.size(), ttlSeconds);
        return Optional.of(new VendedStorageCredential(keys, ttlSeconds,
                gcs.storageType(), VendedStorageCredential.SOURCE_GCS_DOWNSCOPED, name(),
                expiresAt));
    }

    /**
     * 到期时刻：云给的就用它，没给就按 TTL 算。
     *
     * <p>两者都不能省：{@code gcs.oauth2.token-expires-at} 是发给引擎的**事实**，
     * 而 {@link VendedStorageCredential#expireTimeInMs()} 决定缓存复用窗口与
     * 「能不能续期」的判断。缺一个就会出现「票写着一小时后过期、缓存却当成两小时」。
     */
    private static long expiresAt(GcsDownscopedToken token, long ttl) {
        return token.expiresAt() != null
                ? token.expiresAt().toEpochMilli()
                : Instant.now().plus(Duration.ofSeconds(ttl)).toEpochMilli();
    }

    static long clamp(long requested) {
        return Math.min(MAX_TTL_SECONDS, Math.max(MIN_TTL_SECONDS, requested));
    }
}
