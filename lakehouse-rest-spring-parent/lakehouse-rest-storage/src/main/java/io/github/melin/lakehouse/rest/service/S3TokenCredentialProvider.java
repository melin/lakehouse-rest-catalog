package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
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
 * S3 的『真签发』：拿服务身份去 STS {@code AssumeRole}，换一张只覆盖这张表的票。
 *
 * <p>这是设计稿里「凭据转发 → 凭据下发」的那一步。与 {@link DefaultStorageCredentialManager}
 * 里的转发分支相比，差别只在密钥从哪来，但安全属性完全不同：泄漏窗口从「永久」变成
 * 「十几分钟到几小时」，作用域从「整个桶」变成「表前缀 + 读写分离」，
 * 出事之后不需要任何人去撤销——到期自然失效。
 *
 * <p><b>三件必须先满足的事，缺一条就不该由本 provider 接手：</b>
 * <ol>
 *   <li>存储类型是 S3（其余云各有自己的换票接口，见设计 §6）；</li>
 *   <li>catalog 配了 {@code roleArn}——没有角色可 assume，签不出来；</li>
 *   <li>catalog 没把 {@code stsUnavailable} 设为真。</li>
 * </ol>
 * 第三条的判定在调用方（{@code DefaultStorageCredentialManager}）按既有优先级做完，
 * 因为「{@code stsUnavailable} 表达不下发」是那一层的契约，放在这里会变成两份需要
 * 同步的规则。
 *
 * <p><b>catalog 上的 AK/SK 不是「让本 provider 让路」的信号。</b>配了 {@code roleArn}
 * 之后那对钥匙的身份变了：它是服务端拿去 assume 时的调用方身份（自建机房里极常见的
 * 形态——一把只允许 {@code sts:AssumeRole} 的钥匙），不再是发给引擎的数据面凭据。
 * 两者并存时签发优先，顺序写在 {@code DefaultStorageCredentialManager#s3}；
 * 没有 {@code roleArn} 时那对钥匙才退回纯转发。
 *
 * <p><b>发哪些键。</b>短期凭据必须四件套齐全：AK、SK、{@code s3.session-token}、
 * 到期时刻。少发 {@code session-token} 的后果不是「少个可选字段」，而是 Iceberg 的
 * {@code VendedCredentialsProvider} 在第一次刷新时 {@code Preconditions.checkState} 失败
 * （设计稿 G4）——而且它只在**续期**路径上炸，首次读取一切正常。
 *
 * <p><b>Paimon 的引擎键名也要给。</b>{@code s3.access-key-id} 经 {@code paimon-s3}
 * 的前缀翻译只会变成 {@code fs.s3a.access-key-id}，一个 Hadoop 不认识的键，
 * 结果是密钥被静默丢弃。这里同时给 {@code s3.access-key} / {@code s3.secret-key}，
 * 理由与转发分支完全相同，见 {@code KEY_S3_ACCESS_ENGINE} 的长注释。
 */
@Component
@RequiredArgsConstructor
public class S3TokenCredentialProvider implements CredentialProvider {

    private static final Logger log = LoggerFactory.getLogger(S3TokenCredentialProvider.class);

    static final String KEY_ACCESS = "s3.access-key-id";
    static final String KEY_SECRET = "s3.secret-access-key";
    static final String KEY_SESSION = "s3.session-token";
    static final String KEY_EXPIRES_AT = "s3.session-token-expires-at-ms";
    static final String KEY_ACCESS_ENGINE = "s3.access-key";
    static final String KEY_SECRET_ENGINE = "s3.secret-key";

    private final RestServerProperties properties;

    private final CredentialCipher cipher;

    private final AssumeRoleClient assumeRoleClient;

    @Override
    public String name() {
        return VendedStorageCredential.PROVIDER_S3_TOKEN;
    }

    @Override
    public Optional<VendedStorageCredential> vend(CredentialContext context) {
        AwsStorageConfigInfo s3 = context.aws();
        if (s3 == null || s3.roleArn() == null || s3.roleArn().isBlank()) {
            return Optional.empty();
        }
        List<CredentialScope> scopes = context.scopes();
        if (scopes.isEmpty()) {
            // 一个位置都认不出来：不签。整桶凭据比不签更糟——它看起来生效了，
            // 实际把收窄整个跳过，而日志里只有一行正常。
            log.warn("cannot derive a credential scope from {} for {}; refusing to sign an"
                            + " unscoped session",
                    context.scopePaths(), context.scopeId());
            return Optional.empty();
        }
        if (scopes.stream().anyMatch(CredentialScope::bucketRoot) && !noInlinePolicy(context)) {
            log.warn("{} sits at a bucket root; the session policy can only be scoped to the"
                    + " whole bucket: {}", context.scopeId(), scopes);
        }
        Duration ttl = Duration.ofSeconds(properties.getCredential().getTtlSeconds());
        // noInlinePolicy：VAST 这类 S3 兼容实现的 STS 收到 Policy 直接回 501，
        // 所以按 catalog 的显式声明省略 policy。省略之后作用域退回角色自身权限
        // （设计稿 §3.5.3）——**这不是「收窄失败」而是「没有收窄」**，必须留痕，
        // 否则出事时日志里只有一行正常签发，看不出这张票其实能读整个角色范围。
        String policy = noInlinePolicy(context) ? null : S3SessionPolicy.build(scopes);
        if (policy == null) {
            log.warn("vending provider={} WITHOUT an inline policy for {} (catalog sets"
                            + " noInlinePolicy=true): the ticket is scoped to the role itself,"
                            + " not to this table",
                    name(), context.scopeId());
        }
        // 拿哪把钥匙去 assume：catalog 自己填的那对；没填就交给 SDK 的默认链
        // （实例角色 / IRSA），那是部署身份，不需要配置项。
        StorageConfigs.StorageSecrets caller = StorageConfigs.secrets(s3, cipher);
        if (caller == null && properties.getCredential().isRequireCatalogSigner()) {
            // P2-2 身份分层：本部署不允许用服务端自己的身份去 assume。
            // 不回退到「转发」也不静默跳过——两种都会让「谁签的这张票」变得不可知，
            // 而 CloudTrail 里只会留下服务端那个万能身份。
            throw new ApiException(503, null, null,
                    "catalog " + context.scopeId() + " declares roleArn " + s3.roleArn()
                            + " but carries no accessKeyId/secretAccessKey to sign the AssumeRole call,"
                            + " and this deployment forbids falling back to its own identity"
                            + " (lakehouse.rest.credential.require-catalog-signer=true);"
                            + " set the catalog's accessKeyId + secretAccessKey, or turn that flag off");
        }
        StsSession session = assumeRoleClient.assumeRole(new AssumeRoleCommand(
                s3.roleArn(),
                s3.externalId(),
                context.roleSessionName(),
                ttl,
                policy,
                s3.region(),
                s3.stsEndpoint(),
                // 关掉时给空 Map：客户端据此**不传** tag 集合，请求与改造前逐字节相同。
                // 打开它是运维动作（信任策略要先放行 sts:TagSession），见配置项说明。
                properties.getCredential().isSessionTags() ? context.sessionTags() : Map.of(),
                caller == null ? null : caller.id(),
                caller == null ? null : caller.secret()));

        Map<String, String> token = new LinkedHashMap<>();
        putLocators(token, s3);
        token.put(KEY_ACCESS, session.accessKeyId());
        token.put(KEY_SECRET, session.secretAccessKey());
        token.put(KEY_SESSION, session.sessionToken());
        token.put(KEY_EXPIRES_AT, Long.toString(session.expireTimeInMs()));
        token.put(KEY_ACCESS_ENGINE, session.accessKeyId());
        token.put(KEY_SECRET_ENGINE, session.secretAccessKey());

        // 下发的 ttl 与票的寿命同源：分成两个值就会出现「签名 15 分钟、声明 1 小时」，
        // 客户端于是在第 16 分钟拿着过期票继续用，拿到一个没有凭据线索的 403。
        long ttlSeconds = ttlSecondsOf(session);
        log.info("vended provider={} credentials for {} (role {}, session {}, expiresIn={}s)",
                name(), context.scopeId(), s3.roleArn(),
                context.roleSessionName(), ttlSeconds);
        return Optional.of(new VendedStorageCredential(token, ttlSeconds,
                s3.storageType(), VendedStorageCredential.SOURCE_STS_SESSION,
                name(), session.expireTimeInMs()));
    }

    /**
     * catalog 是否显式要求「别带 inline policy」。
     *
     * <p>只在 catalog 自己声明时成立，不从 {@code endpoint} 去推测（理由见
     * {@code StorageDtos.AwsStorageConfigInfo} 的 {@code noInlinePolicy} 说明）。
     */
    private static boolean noInlinePolicy(CredentialContext context) {
        AwsStorageConfigInfo s3 = context.aws();
        return s3 != null && Boolean.TRUE.equals(s3.noInlinePolicy());
    }

    /**
     * 定位键：即使签的是短期票，引擎也要知道连哪里。
     *
     * <p>{@code stsEndpoint} / {@code endpointInternal} / {@code roleArn} 一律不发——
     * 前两个是服务端自己的网络位置，后者是服务端去 assume 的材料，都不是客户端的凭据。
     */
    private static void putLocators(Map<String, String> token, AwsStorageConfigInfo storage) {
        if (storage.region() != null && !storage.region().isBlank()) {
            token.put("s3.region", storage.region());
        }
        if (storage.endpoint() != null && !storage.endpoint().isBlank()) {
            token.put("s3.endpoint", storage.endpoint());
        }
        if (storage.pathStyleAccess() != null) {
            token.put("s3.path-style-access", storage.pathStyleAccess().toString());
        }
    }

    /**
     * 票还剩多少秒。
     *
     * <p>用「到期时刻 − 现在」而不是请求里的 {@code DurationSeconds}：真到了这一步，
     * 前者是云给的事实，后者是我们提的请求。两者在角色 {@code MaxSessionDuration}
     * 更短时会分叉，而分叉的方向恰好是「我们以为更长」——即客户端以为还能用。
     */
    private static long ttlSecondsOf(StsSession session) {
        if (session.expiration() == null) {
            return 1L;
        }
        return Math.max(1L, Duration.between(Instant.now(), session.expiration()).toSeconds());
    }
}
