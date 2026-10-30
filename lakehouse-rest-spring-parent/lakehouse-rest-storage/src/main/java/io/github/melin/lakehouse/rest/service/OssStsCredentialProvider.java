package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
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
 * 阿里云 OSS 的『真签发』：拿服务身份去 RAM STS {@code AssumeRole}，换一张只覆盖这张表的票。
 *
 * <p>这是设计稿 P1-2。与 S3 那一档的差别不在「要不要收窄」，而在三件具体的事：
 *
 * <ol>
 *   <li><b>有效期范围窄得多。</b>阿里云的 {@code DurationSeconds} 只接受 900–3600，
 *       超出范围时请求直接被拒（{@code InvalidParameter.DurationSeconds}）。
 *       所以这里**夹紧**而不是原样透传配置：配 8 小时的部署会得到一个 1 小时的票，
 *       而日志里说清楚了「实际签了多久」。反面做法是让它 400——那会让一个
 *       「只是 TTL 配大了」的部署整片读不了表。</li>
 *   <li><b>没有续期落点。</b>hadoop-aliyun 不会自己去换票（设计 §9.4），所以票到期后
 *       长作业会开始 403。这条不能靠「把 TTL 调大」解决——它被 3600 卡死。
 *       因此日志里必须能看出票的寿命，而不是让人事后从 403 反推。</li>
 *   <li><b>会话名有字符集与长度限制。</b>阿里云要求 {@code [a-zA-Z0-9.@\-_]} 且 2–64 位；
 *       {@link CredentialContext#roleSessionName()} 是按 AWS 的字符集写的（含冒号），
 *       直接透传会在签名阶段被拒，报错是 {@code InvalidParameter.RoleSessionName}。</li>
 * </ol>
 *
 * <p><b>先看 {@code stsUnavailable}，再看角色。</b>两者的顺序在调用方
 * （{@code DefaultStorageCredentialManager}）按既有优先级做完：静态凭据优先于一切、
 * {@code stsUnavailable} 表达「不要申请」。本 provider 只在被链调起来时回答一个问题：
 * 「这次我能不能签」。
 */
@Component
@RequiredArgsConstructor
public class OssStsCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(OssStsCredentialProvider.class);

    /** 阿里云允许的最小有效期。低于它的请求被拒，而不是被抬到最小值。 */
    static final long MIN_TTL_SECONDS = 900L;

    /** 阿里云 {@code AssumeRole} 允许的最大有效期（角色自身的 MaxSessionDuration 可能更短）。 */
    static final long MAX_TTL_SECONDS = 3600L;

    private final RestServerProperties properties;

    private final OssStsClient client;

    @Override
    public String name() {
        return VendedStorageCredential.PROVIDER_OSS_STS;
    }

    @Override
    public Optional<VendedStorageCredential> vend(CredentialContext context) {
        if (!(context.storage() instanceof AliyunOssStorageConfigInfo oss)) {
            return Optional.empty();
        }
        RestServerProperties.Storage.Oss config = properties.getStorage().getOss();
        String roleArn = config.getRoleArn();
        if (roleArn == null || roleArn.isBlank()) {
            // 没配角色 = 本部署没有签发能力。退回「只有定位键」的旧行为，不是失败。
            return Optional.empty();
        }
        String accessKeyId = config.getAccessKey();
        String secretAccessKey = config.getSecretKey();
        if (blank(accessKeyId) || blank(secretAccessKey)) {
            // 阿里云的 STS 必须由一对 AK/SK 签名，没有「环境凭据链」这条路。
            // 静默跳过而不是抛错：一个开着 oss-sts 却没有服务身份的部署，
            // 仍然应该能用「转发 catalog 里的静态凭据」那一档读表。
            log.warn("oss-sts is enabled and has a roleArn, but lakehouse.rest.storage.oss"
                    + ".access-key / secret-key is not configured: skipping STS for {}",
                    context.scopeId());
            return Optional.empty();
        }
        List<CredentialScope> scopes = context.scopes();
        if (scopes.isEmpty()) {
            // 认不出位置就不签：一张整桶票看起来生效了，实际把收窄整个跳过。
            log.warn("cannot derive an OSS scope from {} for {}; refusing to sign an unscoped"
                    + " session", context.scopePaths(), context.scopeId());
            return Optional.empty();
        }
        long ttl = clamp(properties.getCredential().getTtlSeconds());
        if (ttl != properties.getCredential().getTtlSeconds()) {
            // 夹紧必须留痕：配置说 8 小时、票只有 1 小时，否则「为什么到点就 403」
            // 会把排障引向权限而不是有效期。
            log.info("clamped the OSS session lifetime from {}s to {}s for {} (the Aliyun STS"
                            + " Accepts {}–{}s only)",
                    properties.getCredential().getTtlSeconds(), ttl, context.scopeId(),
                    MIN_TTL_SECONDS, MAX_TTL_SECONDS);
        }
        if (scopes.stream().anyMatch(CredentialScope::bucketRoot)) {
            log.warn("{} sits at a bucket root; the OSS policy can only be scoped to the whole"
                    + " bucket: {}", context.scopeId(), scopes);
        }
        OssStsSession session = client.assumeRole(new OssStsRequest(
                roleArn,
                sessionName(context),
                ttl,
                OssStsPolicy.build(scopes),
                config.getStsEndpoint(),
                accessKeyId,
                secretAccessKey));

        Map<String, String> token = new LinkedHashMap<>();
        if (!blank(oss.endpoint())) {
            // 端点照发：引擎要知道连哪个区域，而这与密钥从哪来无关
            token.put(DefaultStorageCredentialManager.OSS_KEYS.endpoint(), oss.endpoint());
        }
        // 三件套一起发：临时凭据的 AK/SK 必须配 securityToken，少一个的后果是
        // 引擎用「长期凭据」的方式去签名，报 {@code InvalidAccessKeyId}——
        // 与「密钥写错了」完全同一个现象。
        token.put(DefaultStorageCredentialManager.OSS_KEYS.accessKey(), session.accessKeyId());
        token.put(DefaultStorageCredentialManager.OSS_KEYS.secretKey(), session.secretAccessKey());
        token.put(DefaultStorageCredentialManager.OSS_KEYS.sessionToken(), session.securityToken());

        long ttlSeconds = ttlSecondsOf(session, ttl);
        log.info("vended provider={} credentials for {} (role {}, session {}, expiresIn={}s)",
                name(), context.scopeId(), roleArn, sessionName(context), ttlSeconds);
        return Optional.of(new VendedStorageCredential(token, ttlSeconds,
                oss.storageType(), VendedStorageCredential.SOURCE_OSS_STS, name(),
                session.expiration() == null ? 0L : session.expiration().toEpochMilli()));
    }

    /**
     * 票还剩多少秒；解析不出到期时刻时退回请求里的 TTL。
     *
     * <p>与 S3 那段同一个取舍（见 {@code S3TokenCredentialProvider#ttlSecondsOf}）：
     * 云给的到期时刻是事实，请求里的时长只是我们提的。角色 {@code MaxSessionDuration}
     * 更短时两者分叉，方向恰好是「我们以为更长」。
     */
    private static long ttlSecondsOf(OssStsSession session, long requested) {
        if (session.expiration() == null) {
            return requested;
        }
        return Math.max(1L, Duration.between(Instant.now(), session.expiration()).toSeconds());
    }

    /**
     * 把请求的有效期夹进阿里云接受的范围。
     *
     * <p>取 {@code max(min)} 而不是「超范围就报错」：TTL 是部署级的全局配置，
     * 为一个存储类型把它调小会同时改掉 S3 那一档；而 S3 最长能到 12 小时。
     * 所以这里按存储类型各自夹紧，是唯一能同时满足两家的做法。
     */
    static long clamp(long requested) {
        return Math.min(MAX_TTL_SECONDS, Math.max(MIN_TTL_SECONDS, requested));
    }

    /**
     * 会话名：{@code lh-<principal>-<scope>} 规整到阿里云的字符集与长度。
     *
     * <p>不能用 {@link CredentialContext#roleSessionName()} 的原值：它按 AWS 的规则
     * 只把非法字符换成 {@code -}，而长度上限是 64——两者恰好一致，但 AWS 允许
     * {@code +=,.@-_} 里的等号与加号，阿里云不允许。这里再收一道，
     * 让「换一家云」不变成一次签名失败。
     */
    static String sessionName(CredentialContext context) {
        String raw = context.roleSessionName();
        StringBuilder cleaned = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length() && cleaned.length() < 64; i++) {
            char c = raw.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '@' || c == '-' || c == '_';
            cleaned.append(allowed ? c : '-');
        }
        String value = cleaned.toString();
        // 阿里云要求 2–64 位；空串或单字符会被拒（InvalidParameter.RoleSessionName）
        return value.length() < 2 ? "lh" : value;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
