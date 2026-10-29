package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次存储凭据下发的产物。
 *
 * <p>不直接返回 {@code Map} 而是带上几个元信息，是因为这几件事在日志与测试里都要看：
 * 凭据属于哪种存储（决定了键名族）、有效期多久（缓存按它失效）、
 * 密钥是从哪一级取到的（配置、具名存储、还是环境凭据链）。
 *
 * <p>{@link #source()} 刻意不进 {@link #token()}：那是引擎消费的配置映射，
 * 混入一个非 FileIO 的键，轻则被引擎当成未知属性打印警告，
 * 重则在某些实现里触发「未知键即报错」的校验。它只用于诊断。
 *
 * <p><b>{@link #expireTimeInMs()} 是「静态还是短期」的唯一判据。</b>
 * 这一点抄自 Gravitino（{@code Credential.expireTimeInMs()}）：静态凭据返回 {@code 0}，
 * 短期凭据返回**真实到期时刻的毫秒值**。它不是一个「模式」字段，而是结果本身——
 * 协议适配层靠它决定发几个键（短期才有 {@code s3.session-token}）、
 * 要不要广告续期端点、缓存留多久。用开关表达同一件事，必然产生
 * 「开关说走 STS、但配置里没有 roleArn」这类半截状态。
 *
 * <p>那个 {@code 0} 同时表达两件事：**不会过期**、**别去刷新**。客户端两边都靠它判断，
 * 所以它绝不能拿来当「暂时没有值」的占位符。
 *
 * <p>{@link #provider()} 是签发这份凭据的 provider 名（{@code relay} / {@code s3-token}）。
 * 它与 {@link #source()} 是两个维度：source 说「钥匙从哪来」，provider 说「谁签的」。
 * 排障时两者都要——「provider=s3-token 但 source=configuration」意味着
 * provider 链没生效、退回了转发，这正是最需要看见的一种状态。
 */
public record VendedStorageCredential(Map<String, String> token,
                                      long ttlSeconds,
                                      StorageType storageType,
                                      String source,
                                      String provider,
                                      long expireTimeInMs) {

    public VendedStorageCredential {
        token = token == null ? new LinkedHashMap<>() : new LinkedHashMap<>(token);
    }

    /**
     * 兼容构造器：静态转发（旧行为）。
     *
     * <p>等价于 {@code provider=relay, expireTimeInMs=0}。保留它是因为绝大多数
     * 凭据来源确实是静态的，让每个调用点都写一遍这两个常量只会把「静态」这件事
     * 噪声化——真正需要显式写的是**短期**那一侧。
     */
    public VendedStorageCredential(Map<String, String> token,
                                   long ttlSeconds,
                                   StorageType storageType,
                                   String source) {
        this(token, ttlSeconds, storageType, source, PROVIDER_RELAY, 0L);
    }

    /** 密钥来源为服务端配置里的默认凭据。 */
    public static final String SOURCE_CONFIGURATION = "configuration";

    /** 密钥来源为服务端配置里的具名存储，实际取值形如 {@code named-storage:<name>}。 */
    public static final String SOURCE_NAMED_STORAGE_PREFIX = "named-storage:";

    /**
     * 密钥来源为 catalog 自己带的静态凭据（{@code storageConfigInfo.accessKeyId}）。
     *
     * <p>这是最具体的一级：catalog 显式写下了「用这把钥匙」，就不再去找服务端配置。
     */
    public static final String SOURCE_STATIC_CREDENTIALS = "static-credentials";

    /** 服务端无凭据，交给引擎的环境凭据链。 */
    public static final String SOURCE_ENVIRONMENT = "environment";

    /** 本地文件系统，没有凭据可下发。 */
    public static final String SOURCE_FILESYSTEM = "filesystem";

    /** 该存储的 {@code stsUnavailable} 为真，按规格不下发凭据。 */
    public static final String SOURCE_STS_UNAVAILABLE = "sts-unavailable";

    /**
     * 密钥来源为服务端向云 STS 现签的短期会话。
     *
     * <p>与上面几个来源的区别不是「从哪读配置」，而是「这次真的调了云」。
     * 它一定伴随非零的 {@link #expireTimeInMs()}。
     */
    public static final String SOURCE_STS_SESSION = "sts-session";

    /**
     * Azure 只下发定位元数据。
     *
     * <p>Polaris 的 {@code polaris.storage.*} 里没有 Azure 账户密钥这一项——它靠服务进程
     * 自身的 Azure 标识签 SAS。本工程没有那层标识，因此只能把库、账户、租户传下去，
     * 由引擎用自己那份身份去换 SAS。这是明确的缺口，不是遗漏。
     */
    public static final String SOURCE_AZURE_METADATA_ONLY = "metadata-only";

    /** provider 名：静态转发（不调云，密钥来自配置或 catalog）。 */
    public static final String PROVIDER_RELAY = "relay";

    /** provider 名：AWS STS {@code AssumeRole}。 */
    public static final String PROVIDER_S3_TOKEN = "s3-token";

    /** 取值是否来自具名存储。 */
    public boolean namedStorage() {
        return source != null && source.startsWith(SOURCE_NAMED_STORAGE_PREFIX);
    }

    /**
     * 是否真的下发了密钥（而非只给定位信息）。
     *
     * <p>按 {@link #source} 判断，而不是遍历 {@link #token} 里有没有密钥键。
     * 早先的写法是硬编码「含 {@code s3.secret-access-key} 或 {@code gcs.oauth2.token}」，
     * 每加一种存储类型就要回来补一个字符串，漏补时表现为「明明发了密钥却报没发」——
     * 一处与存储类型数量同步的清单，是最容易忘的地方。
     * 而「密钥从哪一级取到」这个信息，下发方本来就已经算出来了。
     */
    public boolean hasSecrets() {
        return SOURCE_CONFIGURATION.equals(source) || namedStorage()
                || SOURCE_STATIC_CREDENTIALS.equals(source) || SOURCE_STS_SESSION.equals(source);
    }

    /**
     * 是否是可续期的短期凭据。
     *
     * <p>协议适配层用这一个判断分叉：短期凭据带会话令牌与到期时刻、并且值得告诉客户端
     * 去哪续期；静态凭据两者都不能给（编一个假的会话令牌会被存储侧以 {@code InvalidToken}
     * 拒绝每一次请求，详见设计 §5.8）。
     */
    public boolean shortLived() {
        return expireTimeInMs > 0;
    }

    /**
     * 这份凭据里是否有**没有时限的那对长期密钥**——也就是 {@code USE_SECRET} 的判据。
     *
     * <p>两个条件缺一不可：{@link #hasSecrets()} 说「真的发了密钥」（Azure 那种只发
     * 定位元数据的、以及 {@code sts-unavailable} 的空响应都不算），{@link #shortLived()}
     * 说「这对密钥有时限」。短期票是现签的、policy 收窄到这张表、到点作废，发给任何能读表
     * 的人都是设计意图；而长期那一对没有时限也没有收窄，拿到就等于拿到该身份在这台存储上
     * 的全部权限——它必须单独授权。
     *
     * <p><b>判据放在这里而不是让两个协议面各写一遍。</b>Paimon 的 {@code /token} 与
     * Iceberg 的 {@code /credentials} 是两个类，但它们拿到的是同一种
     * {@code VendedStorageCredential}；规则只要落在这个类型的派生方法上，
     * 两处就只能是同一个答案。写成两份时，最先漂移的一定是「新加一种存储类型」那一次。
     */
    public boolean carriesLongTermKeyPair() {
        return hasSecrets() && !shortLived();
    }

    /**
     * 相对 {@code now} 还剩多少毫秒；非短期凭据返回 {@link Long#MAX_VALUE}。
     *
     * <p>诊断用。静态凭据没有「剩余寿命」这个概念，返回 {@code MAX_VALUE} 而不是 {@code 0}，
     * 是为了让日志里不出现「还剩 0 毫秒」这种看起来像快过期、实际永不过期的行。
     */
    public long remainingMillis(long now) {
        return shortLived() ? Math.max(0L, expireTimeInMs - now) : Long.MAX_VALUE;
    }
}
