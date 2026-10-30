package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RequestContext;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.ManagementEnums;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.TencentCosStorageConfigInfo;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.CredentialCipher;
import io.github.melin.lakehouse.rest.support.CredentialManagerType;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 默认的凭据下发实现：按 {@code storageType} 产出引擎 FileIO 认识的键。
 *
 * <p>键名族不是随手起的，而是各引擎 FileIO 的真实属性名：
 * S3 用 {@code s3.*}，GCS 用 {@code gcs.oauth2.*}，本地文件系统无凭据。
 * 键名写错不会报错，只会让引擎静默地拿不到凭据——因此每种类型都配了测试，
 * 断言的是键名本身，而不是「调用了没抛异常」。
 *
 * <p><b>S3 是唯一需要下发两套键名的一族。</b>规格（Polaris / Iceberg）叫
 * {@code s3.access-key-id} / {@code s3.secret-access-key}，Paimon 引擎叫
 * {@code s3.access-key} / {@code s3.secret-key}，两边互不认识；
 * 只给规格键名，Paimon 拿不到密钥。取舍与机制见 {@link #KEY_S3_ACCESS_ENGINE}。
 *
 * <p><b>OBS、OSS 与 COS 的键名族不能互相套用。</b>三家云的对象存储都兼容 S3 协议，
 * 容易以为用 {@code s3.*} 或对方的键名都能跑通，实际不行：Paimon 的
 * {@code paimon-obs}、{@code paimon-oss} 与 {@code paimon-cosn} 是三个独立的 FileIO，
 * 各认自己的一套键，而且三套的命名风格还各不相同——华为是点分隔小写的
 * {@code fs.obs.access.key} / {@code fs.obs.secret.key} / {@code fs.obs.session.token}，
 * 阿里是驼峰的 {@code fs.oss.accessKeyId} / {@code fs.oss.accessKeySecret} /
 * {@code fs.oss.securityToken}，腾讯是 {@code fs.cosn.userinfo.secretId} /
 * {@code fs.cosn.userinfo.secretKey}（**大小写敏感**，且没有令牌项）。
 * 就连「临时凭据的令牌」这个东西，前两家有、第三家没有。
 * 这层差异在 {@link #OBS_KEYS}、{@link #OSS_KEYS} 与 {@link #COS_KEYS} 里集中表达，便于对照。
 *
 * <p><b>刻意不下发的字段。</b>{@code AwsStorageConfigInfo} 的
 * {@code endpointInternal} 与 {@code stsEndpoint} 是服务端自己访问对象存储时用的地址，
 * 规格明确写了 {@code endpointInternal} 客户端永远看不到。把它们发给引擎，
 * 轻则引擎连到一个不可达的内网地址，重则把内网拓扑泄给调用方。
 * {@code roleArn}/{@code externalId}/{@code userArn} 同理不下发：
 * 那是服务端去 assume 角色的材料，不是给客户端的凭据。
 *
 * <p><b>{@code stsUnavailable} 的语义。</b>规格写的是「设为 true 时 Polaris 服务端
 * 不再为访问该 catalog 下发凭据」。这里照此执行：仍然下发定位配置（区域、端点），
 * 但不给密钥，由引擎用自己的身份访问。这是配置意图，不是失败。
 *
 * <p><b>catalog 上的静态凭据与 {@code roleArn} 共用同一组字段，但身份不同。</b>
 * 只填钥匙 = 它是发给引擎的数据面凭据（转发档）；钥匙配上 {@code roleArn} = 它是
 * 服务端拿去 assume 的调用方身份（签发档优先）。因此顺序不是「静态凭据最高」，
 * 而是 **{@code stsUnavailable} → 签发 → 转发 → 环境链**，逐条理由写在
 * {@link #s3} 的方法注释里。密钥在库中是密文，这里是唯一需要解密的消费点；
 * 解密失败（换了加密密钥）直接 500，不退回别的档：退回等于把
 * 「这个 catalog 该用哪把钥匙」从调用方明确指定降级成服务端默认，
 * 而密钥无法解开的真实原因（配置变更）会因此被掩盖成「权限不对」。
 */
@Component
public class DefaultStorageCredentialManager implements StorageCredentialManager {

    private static final Logger log = LoggerFactory.getLogger(DefaultStorageCredentialManager.class);

    /**
     * 从 {@code abfss://container@account.dfs.core.windows.net/prefix/} 里取出账户名。
     *
     * <p>刻意只认 {@code dfs.core.windows.net} 与 {@code blob.core.windows.net} 这两种后缀。
     * 更宽松的写法（取 {@code @} 与第一个点之间那段）在自定义域名上会解出一个似是而非的名字，
     * 而错误的账户名比没有账户名更糟：引擎会拿着它去认证一个不存在的账户，
     * 报出来的错与真正的原因（地址写错了）隔着一层。解不出来就不给这个键，
     * 让错误停在「地址格式不认识」这一层。
     */
    private static final Pattern AZURE_ACCOUNT = Pattern.compile(
            "^[a-zA-Z0-9]+://[^@/]+@([^.@/]+)\\.(?:dfs|blob)\\.core\\.windows\\.net(?:[/:?#]|$)");

    /**
     * S3 凭据的「规格」键名：Polaris / Iceberg 的叫法。
     *
     * <p>规格里 {@code LoadTableResult} 的 {@code credentials} 就写成
     * {@code s3.access-key-id} / {@code s3.secret-access-key}，客户端按这套名字取值，
     * 所以这两个键不能少。
     */
    private static final String KEY_S3_ACCESS = "s3.access-key-id";

    private static final String KEY_S3_SECRET = "s3.secret-access-key";

    /**
     * S3 凭据的「引擎」键名：Paimon 自己的叫法。与上面两个键**必须同时下发**。
     *
     * <p>两套名字不是重复，而是分属两个消费方。Paimon 的 {@code paimon-s3}
     * 拿到令牌后会把所有 {@code s3.*} 键按前缀翻译成 Hadoop 的
     * {@code fs.s3a.*}（{@code loadHadoopConfigFromContext}：
     * {@code "fs.s3a." + key.substring("s3.".length())}），再做一次小范围镜像
     * （{@code MIRRORED_CONFIG_KEYS}）：{@code fs.s3a.access-key → fs.s3a.access.key}、
     * {@code fs.s3a.secret-key → fs.s3a.secret.key}。
     *
     * <p>于是 {@code s3.access-key-id} 只会变成 {@code fs.s3a.access-key-id}——
     * 一个 Hadoop 根本不认识的键，镜像表里也没有它。结果是密钥被**静默丢弃**：
     * 引擎退回默认凭据链（环境变量 / 实例身份），报出
     * {@code NoAuthWithAWSException: No AWS Credentials provided by ...}。
     * 这个异常类是插件隔离类加载器里的，Spark 上报时序列化不到 driver，
     * 最终只显示成 {@code UnknownReason}——错误信息与真正的原因完全脱钩。
     * 同时下发 Paimon 键名，让 {@code fs.s3a.access.key} / {@code fs.s3a.secret.key}
     * 真正落位，才谈得上「凭据下发」。
     */
    private static final String KEY_S3_ACCESS_ENGINE = "s3.access-key";

    private static final String KEY_S3_SECRET_ENGINE = "s3.secret-key";

    private static final String KEY_GCS_TOKEN = "gcs.oauth2.token";

    /**
     * 华为云 OBS 的凭据键名族，取自 OBSA（{@code hadoop-huaweicloud}）的配置项。
     *
     * <p>{@code session.token} 是临时 AK/SK 配套的安全令牌，不是 {@code security.token}——
     * 华为与阿里的命名不同，写错了不会报错，只会让引擎在临时凭据场景下签名失败。
     */
    private static final KeyFamily OBS_KEYS = new KeyFamily(
            "fs.obs.access.key", "fs.obs.secret.key", "fs.obs.session.token", "fs.obs.endpoint");

    /**
     * 阿里云 OSS 的凭据键名族，取自 {@code hadoop-aliyun} 的配置项。
     *
     * <p>注意 {@code accessKeyId} / {@code accessKeySecret} 是驼峰，与 OBS 的点分隔小写
     * 不是同一套风格；临时凭据的键也不同（{@code securityToken} 对 {@code session.token}）。
     */
    static final KeyFamily OSS_KEYS = new KeyFamily(
            "fs.oss.accessKeyId", "fs.oss.accessKeySecret", "fs.oss.securityToken", "fs.oss.endpoint");

    /**
     * 腾讯云 COS（COSN）的键名族，取自 hadoop-cos 的 {@code CosNConfigKeys}。
     *
     * <p><b>大小写是真的敏感。</b>{@code secretId} 里的那个大写 {@code I} 必须保住：
     * Paimon 的 {@code COSNFileIO#configure} 会把键名小写化后送进 Hadoop
     * {@code Configuration}（Hadoop 自己就会小写化），再靠一张
     * {@code CASE_SENSITIVE_KEYS} 还原表把 {@code fs.cosn.userinfo.secretid} 映射回
     * {@code fs.cosn.userinfo.secretId}。那张表里**只有这两个键**，所以键名写错
     * （哪怕是 {@code secretID}）不会报错，只会让 AK 变成空串，接着就是认证失败。
     *
     * <p><b>{@code sessionToken} 是 {@code null}，不是漏写。</b>校验过的
     * paimon-cosn 2.1.0 内置 hadoop-cos：{@code CosNConfigKeys} 没有会话令牌这一项，
     * 认证包（{@code org.apache.hadoop.fs.cosn.auth}）只有
     * {@code SimpleCredentialsProvider} 与 {@code EnvironmentVariableCredentialsProvider}，
     * 类路径上也搜不到 {@code SessionTokenCredentialProvider}。也就是说
     * **COSN 这条路走不了临时凭据**——发一个没人读的令牌，与不发它的区别只是
     * 「让运维以为临时凭据生效了」。{@link #putSecrets} 因此把「配了令牌但这一族
     * 没有令牌键」变成一次显式失败。
     *
     * <p>端点键是 {@code endpoint_suffix}（形如 {@code cos.ap-guangzhou.myqcloud.com}），
     * 不是 S3 那种带协议的 {@code endpoint}。
     */
    private static final KeyFamily COS_KEYS = new KeyFamily(
            "fs.cosn.userinfo.secretId", "fs.cosn.userinfo.secretKey", null,
            "fs.cosn.bucket.endpoint_suffix");

    /** COSN 的区域键。{@code COSNLoader#requiredOptions} 要求它与端点至少给一个。 */
    private static final String KEY_COS_REGION = "fs.cosn.bucket.region";

    /**
     * COSN 是否走 HTTPS。**它的缺省是 {@code false}**（{@code CosNConfigKeys.DEFAULT_USE_HTTPS}），
     * 不配就意味着密钥与数据全部走明文，因此本工程默认下发 {@code true}，
     * 只有 catalog 显式写了 {@code useHttps=false}（私有化部署的 COS 兼容实现）才发 {@code false}。
     */
    private static final String KEY_COS_USE_HTTPS = "fs.cosn.useHttps";

    private final RestServerProperties properties;

    private final CredentialCipher cipher;

    /**
     * 已启用的签发 provider 链，按配置里的顺序。
     *
     * <p><b>默认为空。</b>空链意味着这一层的行为与引入 provider 之前逐字节相同——
     * 这是设计稿 P0-1 的验收条件，也是「能力先具备、默认不启用」的落法：
     * 一个会在读表路径上引入远程调用的能力，不该靠「默认打开、需要时再关」发布。
     *
     * <p>链里只有「真签发」的实现；静态转发不是一个 provider，而是本类的默认分支。
     * 之所以不把转发也做成 provider，是因为那样默认链里就必然有一个条目，
     * 「默认空」这个性质会用一个表达式去模拟，读代码的人要绕一圈才知道
     * 「默认什么也没启用」。
     */
    private final List<CredentialProvider> providers;

    /**
     * 云侧限流的退避器：只包住 provider 那一次远程调用。
     *
     * <p>为什么不在更外层包：转发档与文件系统档根本不发请求，套上去只是徒增
     * 一层「可能会等 0.6 秒」的想象；而真正的远程调用只发生在 provider 里。
     */
    private final ThrottlingGuard throttling;

    /** 测试与手工装配用：按配置的默认值自己造一个退避器。 */
    public DefaultStorageCredentialManager(RestServerProperties properties,
                                           CredentialCipher cipher,
                                           List<CredentialProvider> availableProviders) {
        this(properties, cipher, availableProviders, new ThrottlingGuard(properties));
    }

    @Autowired
    public DefaultStorageCredentialManager(RestServerProperties properties,
                                           CredentialCipher cipher,
                                           List<CredentialProvider> availableProviders,
                                           ThrottlingGuard throttling) {
        this.properties = properties;
        this.cipher = cipher;
        this.providers = selectProviders(availableProviders, properties.getCredential().getProviders());
        this.throttling = throttling;
    }

    /** 文件系统档的随机令牌用它；对象存储的档不用，但这是唯一一处「本地造密钥」的路径。 */
    private final SecureRandom random = new SecureRandom();

    /**
     * 按配置名挑出 provider，顺序照配置里的顺序。
     *
     * <p>写了不认识的名字直接失败（启动期），与 {@code credential-manager.type}
     * 的处置一致：一个拼错的 provider 名若被静默忽略，表现是「明明配了 STS、
     * 出来的还是长期密钥」——功能看起来在跑，其实没生效，正是本文档反复出现的
     * 那一类故障。
     *
     * <p>{@code relay} 是允许出现的名字但不映射到任何 bean：它是本类的默认分支，
     * 写成显式的 {@code relay} 只是让人能读懂「这里就是不走 STS」。
     */
    private static List<CredentialProvider> selectProviders(List<CredentialProvider> available,
                                                            List<String> enabled) {
        if (enabled == null || enabled.isEmpty()) {
            return List.of();
        }
        Map<String, CredentialProvider> byName = new LinkedHashMap<>();
        for (CredentialProvider provider : available) {
            byName.put(provider.name(), provider);
        }
        List<CredentialProvider> chain = new ArrayList<>();
        for (String raw : enabled) {
            String name = raw == null ? "" : raw.trim();
            if (name.isEmpty() || VendedStorageCredential.PROVIDER_RELAY.equals(name)) {
                continue;
            }
            CredentialProvider provider = byName.get(name);
            if (provider == null) {
                throw new IllegalArgumentException(
                        "lakehouse.rest.credential.providers contains unknown provider '" + name
                                + "'; available providers: " + byName.keySet()
                                + " (relay is always implicit)");
            }
            chain.add(provider);
        }
        return List.copyOf(chain);
    }

    @Override
    public CredentialManagerType type() {
        return CredentialManagerType.DEFAULT;
    }

    @Override
    public VendedStorageCredential vend(StorageConfigInfo storage, String tableId, String tablePath,
                                        boolean allowWrite) {
        requireStorage(storage);
        CredentialContext context = CredentialContext.forTable(storage, tableId, tablePath,
                RequestContext.principal(), allowWrite);
        // S3 分支自己返回完整结果（它是历史上唯一接 provider 链的类型）；
        // 其余类型走 vendOther 的统一骨架。
        VendedStorageCredential vended = storage.storageType() == ManagementEnums.StorageType.S3
                ? s3((AwsStorageConfigInfo) storage, context)
                : vendOther(context);
        logVended(storage, tableId, vended);
        return vended;
    }

    /**
     * 服务端自己访问仓库的票：作用域是 catalog 的允许位置，与用户的表级票分开。
     *
     * <p>实现上它与 {@link #vend} 走同一条分支（同样的四级优先级、
     * 同样的 provider 链），差别只在 {@link CredentialContext}：这里带的是
     * {@code allowedLocations} 一份列表，而不是一张表的路径。因此静态转发档、
     * {@code stsUnavailable} 档的行为与引擎那张票完全一致——「服务端能不能访问」
     * 与「引擎能不能访问」仍是同一个答案。
     *
     * <p><b>Azure 的 catalog 级票尤其要用这条通道。</b>它签的是「目录级」SAS，
     * 而目录由位置决定：用表级票去应答「服务端扫描整个仓库」，票的作用面比动作小，
     * 表现是列目录 403。
     */
    @Override
    public VendedStorageCredential vendForCatalog(StorageConfigInfo storage, String catalogId,
                                                 List<String> allowedLocations, boolean allowWrite) {
        requireStorage(storage);
        CredentialContext context = CredentialContext.forCatalog(storage, catalogId,
                allowedLocations, RequestContext.principal(), allowWrite);
        VendedStorageCredential vended = storage.storageType() == ManagementEnums.StorageType.S3
                ? s3((AwsStorageConfigInfo) storage, context)
                : vendOther(context);
        logVended(storage, "catalog:" + catalogId, vended);
        return vended;
    }

    /**
     * S3 之外的各类型共用的骨架，顺序与 {@link #s3} 逐条对应。
     *
     * <p>抽成一条共用路径而不是每家写一遍，是因为「{@code stsUnavailable} 先于签发」
     * 「签发排在转发之前、服务端默认排在最后」这两条判断与存储类型无关；
     * 逐家复制时最先漂移的一定是新加的那一家。各家自己的差异只有两处，
     * 都在下面的 switch 与 {@link #forwardOrUnavailable} 里：发哪些定位键、
     * 有没有可转发的密钥族。
     *
     * <p>{@code COS} 也走这条链：它的 {@code providers} 里没有能接手的实现
     * （COSN 不支持临时凭据，见 {@code COS_KEYS}），链对它是空转——
     * 但这比在代码里给它开一个例外更诚实：能力边界写在 provider 里，
     * 不写在调用点上。
     */
    private VendedStorageCredential vendOther(CredentialContext context) {
        StorageConfigInfo storage = context.storage();
        Map<String, String> token = new LinkedHashMap<>();
        switch (storage.storageType()) {
            case AZURE -> putAzureLocators((AzureStorageConfigInfo) storage, token);
            case GCS -> putIfPresent(token, "gcs.service-account",
                    ((GcpStorageConfigInfo) storage).gcsServiceAccount());
            case OBS -> putIfPresent(token, OBS_KEYS.endpoint(),
                    ((HuaweiObsStorageConfigInfo) storage).endpoint());
            case OSS -> putIfPresent(token, OSS_KEYS.endpoint(),
                    ((AliyunOssStorageConfigInfo) storage).endpoint());
            case COS -> putCosLocators((TencentCosStorageConfigInfo) storage, token);
            case FILE -> {
                return file((FileStorageConfigInfo) storage, context, token);
            }
            case S3 -> throw new IllegalStateException("S3 is handled before the switch");
        }
        if (stsUnavailable(storage)) {
            return vended(storage, token,
                    forwardOrUnavailable(storage, token, VendedStorageCredential.SOURCE_STS_UNAVAILABLE));
        }
        VendedStorageCredential signed = assumeRole(context);
        if (signed != null) {
            return signed;
        }
        return vended(storage, token,
                forwardOrUnavailable(storage, token, VendedStorageCredential.SOURCE_ENVIRONMENT));
    }

    /**
     * 把 catalog 自带的 AK/SK 写进 {@code token} 并报一个来源；写不进去时给出退档来源。
     *
     * <p><b>Azure 永远不走这条转发路。</b>它的 {@code clientId}/{@code clientSecret} 是
     * 签发 SAS 用的身份，不是数据面的凭据——把这对值发给引擎，引擎既没法用它访问
     * 存储，也没法用它换票，而来源字段会写成「静态凭据」，看上去一切正常。
     * 因此 Azure 在没有服务主体时明确回「只发定位元数据」。
     *
     * <p><b>GCS 也不走。</b>{@code serviceAccountJson} 同理，它只用于服务端换票；
     * 没有它时引擎用自己的 ADC，来源就是环境。
     */
    private String forwardOrUnavailable(StorageConfigInfo storage, Map<String, String> token,
                                       String fallback) {
        if (storage.storageType() == ManagementEnums.StorageType.AZURE) {
            return VendedStorageCredential.SOURCE_AZURE_METADATA_ONLY;
        }
        if (storage.storageType() == ManagementEnums.StorageType.GCS) {
            return VendedStorageCredential.SOURCE_ENVIRONMENT;
        }
        StorageConfigs.StorageSecrets own = StorageConfigs.secrets(storage, cipher);
        if (own == null) {
            return fallback;
        }
        putSecrets(token, keysOf(storage), own.id(), own.secret());
        return VendedStorageCredential.SOURCE_STATIC_CREDENTIALS;
    }

    /**
     * 各类型是否声明了「不要票」。
     *
     * <p>{@code Azure} 与 {@code GCS} 的规格里没有这个字段（Azure 用
     * {@code skipSasTokenGeneration}、GCS 用 {@code skipCredentialDownscoping}，
     * 两者都还没落到本工程的 DTO 里），它们的「不签发」由「部署没配服务身份」表达；
     * 见 {@code RestServerProperties.Storage.Azure} 的注释。
     */
    private static boolean stsUnavailable(StorageConfigInfo storage) {
        return (storage instanceof HuaweiObsStorageConfigInfo obs
                    && Boolean.TRUE.equals(obs.stsUnavailable()))
                || (storage instanceof AliyunOssStorageConfigInfo oss
                    && Boolean.TRUE.equals(oss.stsUnavailable()))
                || (storage instanceof TencentCosStorageConfigInfo cos
                    && Boolean.TRUE.equals(cos.stsUnavailable()));
    }

    /** 一家云一组密钥键名，供 {@link #putSecrets} 使用。 */
    private static KeyFamily keysOf(StorageConfigInfo storage) {
        return switch (storage.storageType()) {
            case OBS -> OBS_KEYS;
            case OSS -> OSS_KEYS;
            case COS -> COS_KEYS;
            default -> throw new IllegalStateException(
                    "storage type " + storage.storageType() + " has no static key family");
        };
    }

    /** 配置组名，仅用于错误信息。 */
    private static String groupOf(StorageConfigInfo storage) {
        return storage.storageType().name().toLowerCase(java.util.Locale.ROOT);
    }

    private static void requireStorage(StorageConfigInfo storage) {
        if (storage == null) {
            throw new ApiException(500, null, null,
                    "catalog has no storage configuration; cannot vend data access credentials");
        }
    }

    /**
     * 一行 debug：{@code provider} 与 {@code source} 是两个维度，排障时都要看——
     * {@code provider=s3-token} 但 {@code source=configuration} 意味着链没生效、退回了转发。
     */
    private static void logVended(StorageConfigInfo storage, String scopeId,
                                  VendedStorageCredential vended) {
        if (log.isDebugEnabled()) {
            log.debug("vended {} credentials for {} provider={} source={} expiresIn={}",
                    storage.storageType(), scopeId, vended.provider(), vended.source(),
                    vended.shortLived()
                            ? vended.remainingMillis(System.currentTimeMillis()) + "ms" : "none");
        }
    }

    /**
     * 一个对象存储 FileIO 的凭据键名族。
     *
     * <p>四个键里前三个是密钥，最后一个只是定位。{@code sessionToken} 允许为
     * {@code null}：COSN 这一族根本没有令牌键（见 {@link #COS_KEYS}），
     * 用 {@code null} 表达「这一族不支持临时凭据」，比给一个发了没人读的键名要好——
     * 后者会在「配了令牌」时静默通过。
     */
    record KeyFamily(String accessKey, String secretKey, String sessionToken, String endpoint) {
    }

    // ------------------------------------------------------------------ S3

    /**
     * 写入 S3 密钥：规格键名与引擎键名一起给。
     *
     * <p>集中在一处是为了不出现「某一个分支忘了给引擎键名」的情况——
     * 这类遗漏不会报错，只会让那一条凭据来源在引擎侧静默失效。
     * 两套名字的由来见 {@link #KEY_S3_ACCESS_ENGINE}。
     */
    private static void putS3Secrets(Map<String, String> token, String accessKey, String secretKey) {
        token.put(KEY_S3_ACCESS, accessKey);
        token.put(KEY_S3_SECRET, secretKey);
        token.put(KEY_S3_ACCESS_ENGINE, accessKey);
        token.put(KEY_S3_SECRET_ENGINE, secretKey);
    }

    /**
     * S3：三档，顺序是 **{@code stsUnavailable} → 签发 → 转发 → 环境链**。
     *
     * <p><b>为什么「签发」排在「转发」之前。</b>catalog 上的 AK/SK 现在是**服务端据以
     * 行动的身份**：配了 {@code roleArn} 表示「用这把钥匙去 assume 这个角色」，
     * 不配才表示「直接把钥匙交给引擎」。前者是调用方明确要求换一张短期票，
     * 后者只是没有别的来源。顺序反过来的话，一个「填了角色也填了最低权限钥匙」的
     * catalog 会永远走转发档，而那正是配置里写着、运行时读不到的一种。
     *
     * <p>这条顺序不会影响任何存量 catalog：{@code roleArn} 与静态凭据原先被
     * {@code StorageConfigs#validate} 判为互斥（400），所以「两者并存」的数据不存在。
     *
     * <p>{@code stsUnavailable=true} 仍然排在最前：它表达的是「不要向云申请临时凭据」，
     * 是调用方的决定，签发只是服务端的默认策略，策略不能覆盖决定。
     */
    private VendedStorageCredential s3(AwsStorageConfigInfo s3, CredentialContext context) {
        Map<String, String> token = new LinkedHashMap<>();
        // 定位配置与密钥有无无关：即使不下发密钥，引擎也需要知道该连哪里
        putIfPresent(token, "s3.region", s3.region());
        putIfPresent(token, "s3.endpoint", s3.endpoint());
        if (s3.pathStyleAccess() != null) {
            token.put("s3.path-style-access", s3.pathStyleAccess().toString());
        }
        if (Boolean.TRUE.equals(s3.stsUnavailable())) {
            return relay(s3, token, VendedStorageCredential.SOURCE_STS_UNAVAILABLE);
        }
        VendedStorageCredential signed = assumeRole(context);
        if (signed != null) {
            return signed;
        }
        return relay(s3, token, VendedStorageCredential.SOURCE_ENVIRONMENT);
    }

    /**
     * 把 catalog 自带的 AK/SK 原样下发；没有则返回 {@code fallbackSource} 那一档。
     *
     * <p>抽出来是因为 S3 有两条路会走到它（{@code stsUnavailable} 与「签发没接住」），
     * 而两处都必须给出同一份「有就用、没有就退回」的语义。
     */
    private VendedStorageCredential relay(AwsStorageConfigInfo s3, Map<String, String> token,
                                          String fallbackSource) {
        StorageConfigs.StorageSecrets own = StorageConfigs.secrets(s3, cipher);
        if (own == null) {
            return vended(s3, token, fallbackSource);
        }
        putS3Secrets(token, own.id(), own.secret());
        return vended(s3, token, VendedStorageCredential.SOURCE_STATIC_CREDENTIALS);
    }

    /**
     * 走一遍已启用的 provider 链，第一个接手的说了算。
     *
     * <p>返回 {@code null} 表示「这一档没人接」，调用方继续走静态转发。
     * 链为空是默认情形——那时本方法只做一次空循环，不产生任何副作用，
     * 也不会有远程调用。
     *
     * <p>每次 {@code provider.vend} 都过一遍 {@link ThrottlingGuard}：限流不是
     * 「这个 provider 的特性」，而是这条路线上唯一的远程调用点，包在这里就不会
     * 漏掉将来新加的 provider。冷却键用 provider 名——配额按云账号算，
     * 一个 catalog 打满了，同 provider 下的别的 catalog 也签不出来。
     */
    private VendedStorageCredential assumeRole(CredentialContext context) {
        if (providers.isEmpty()) {
            return null;
        }
        for (CredentialProvider provider : providers) {
            var vended = throttling.call(provider.name(), () -> provider.vend(context));
            if (vended.isPresent()) {
                return vended.get();
            }
        }
        return null;
    }

    /** 用一次性的 token 映射与本 catalog 的 TTL 组装结果。 */
    private VendedStorageCredential vended(StorageConfigInfo storage,
                                           Map<String, String> token,
                                           String source) {
        return new VendedStorageCredential(token, ttlSeconds(storage), storage.storageType(), source);
    }

    // ------------------------------------------------------------------ OBS / OSS / COS

    /**
     * 腾讯云 COS 的定位键：区域、端点、是否 HTTPS。
     *
     * <p>两处 COSN 特有的决定：
     *
     * <ul>
     *   <li>{@code fs.cosn.bucket.region} 只在非空时发。{@code COSNLoader#requiredOptions}
     *       要它与端点「至少有一个」，两个都空时引擎会以一份没有定位信息的配置起 FileIO，
     *       报错落在「无法解析桶」上，与真正的原因（catalog 没填区域）隔着一层。</li>
     *   <li>{@code fs.cosn.useHttps} 恒发（见 {@link #KEY_COS_USE_HTTPS}）：
     *       这是本工程对 COSN 那个「默认明文」缺省值的唯一纠正点，不发就等于默认明文。</li>
     * </ul>
     *
     * <p><b>COS 没有第五级「真签发」。</b>COSN 走不了临时凭据（理由见 {@link #COS_KEYS}），
     * 所以 {@code providers} 链里的任何实现都不会接手它——这不是遗漏，是上游能力的边界：
     * 造一条「服务端签了、引擎不认」的路径，比不做更糟。
     */
    private static void putCosLocators(TencentCosStorageConfigInfo cos, Map<String, String> token) {
        putIfPresent(token, KEY_COS_REGION, cos.bucketRegion());
        putIfPresent(token, COS_KEYS.endpoint(), cos.endpoint());
        token.put(KEY_COS_USE_HTTPS, Boolean.toString(!Boolean.FALSE.equals(cos.useHttps())));
    }

    /**
     * 写入一组密钥。
     *
     * <p>{@code sessionToken} 不在这里：本工程的四家对象存储在 catalog 上都只有
     * AK/SK（COS 的 COSN 根本不认临时凭据，见 {@link #COS_KEYS}），令牌没有来源。
     * 早先那一版为「服务端配置里的令牌」留了一个失败分支，配置面删掉之后它就成了
     * 一条永远不会走的路径——留着只会让下一个维护者去找那个不存在的配置项。
     */
    private static void putSecrets(Map<String, String> token,
                                   KeyFamily keys,
                                   String accessKey,
                                   String secretKey) {
        token.put(keys.accessKey(), accessKey);
        token.put(keys.secretKey(), secretKey);
        if (keys.sessionToken() != null) {
            // COS 这一族没有令牌键，这一支不会进入；其余三家有键但此刻没有值可填
            putIfPresent(token, keys.sessionToken(), null);
        }
    }

    // ------------------------------------------------------------------ Azure

    /**
     * Azure 的定位元数据：租户、账户、多租户应用、同意链接、是否分层命名空间。
     *
     * <p>这些键不发也「能跑」——引擎会用自己那份身份去换访问权；发它们是让引擎知道
     * 该拿哪个账户/租户去认证。**但 {@code adls.account-host} 不在这里，是有意的**：
     * 它不是定位信息而是端点开关，一旦给了，fsspec/adlfs 会改走 DFS 端点，
     * 而 PyIceberg 的默认实现只认 Blob（设计 §3.5.4）。
     *
     * <p>包级可见 + 静态，因为 {@code azure-sas} 那个 provider 签完票之后也要带上同一组
     * 定位键；两处各写一份时，最先漏掉的一定是「新加的那个字段」。
     */
    static void putAzureLocators(AzureStorageConfigInfo azure, Map<String, String> token) {
        putIfPresent(token, "azure.tenant-id", azure.tenantId());
        putIfPresent(token, "azure.account", accountOf(azure.allowedLocations()));
        putIfPresent(token, "azure.multi-tenant-app-name", azure.multiTenantAppName());
        putIfPresent(token, "azure.consent-url", azure.consentUrl());
        if (azure.hierarchical() != null) {
            token.put("azure.hierarchical", azure.hierarchical().toString());
        }
    }

    /** 从位置白名单的首项里取 Azure 账户名；取不到返回 {@code null}。 */
    static String accountOf(List<String> allowedLocations) {
        if (allowedLocations == null || allowedLocations.isEmpty()) {
            return null;
        }
        String first = allowedLocations.get(0);
        if (first == null) {
            return null;
        }
        Matcher matcher = AZURE_ACCOUNT.matcher(first);
        return matcher.find() ? matcher.group(1) : null;
    }

    // ------------------------------------------------------------------ GCS

    // ------------------------------------------------------------------ FILE

    /**
     * 本地文件系统：没有凭据，保留服务端自签的自包含令牌。
     *
     * <p>为什么不是空映射：这是凭据下发接口的既有行为，且部分调用方按
     * 「token 非空」判断下发是否成功。本地仓库确实不需要凭据，
     * 但一个自包含令牌仍有意义——它把这次授权的范围（表 id）与过期时间
     * 落在一处，便于审计。文件系统本身不校验它。
     */
    private VendedStorageCredential file(FileStorageConfigInfo file, CredentialContext context,
                                         Map<String, String> token) {
        long expiresAt = System.currentTimeMillis() + ttlSeconds(file) * 1000L;
        // catalog 级取票没有表 id；这里给一个可读的别名，避免出现 "PAIMON-null"
        token.put("accessKeyId", "PAIMON-" + (context.tableId() == null ? "catalog" : context.tableId()));
        token.put("accessKeySecret", randomSecret(24));
        token.put("securityToken", randomSecret(48));
        token.put("expiration", Instant.ofEpochMilli(expiresAt).toString());
        if (context.tablePath() != null) {
            token.put("tablePath", context.tablePath());
        }
        return vended(file, token, VendedStorageCredential.SOURCE_FILESYSTEM);
    }

    // ------------------------------------------------------------------ 公共

    /**
     * 本次下发的有效期（秒）。
     *
     * <p>基准是 {@code lakehouse.rest.credential.ttl-seconds}。签发档会用自己的
     * 寿命覆盖它（STS 的角色上限、Azure 的 7 天上限、GCS 的 1 小时上限），
     * 那些夹紧在各自的 provider 里，因为只有它们知道云侧的硬约束。
     */
    private long ttlSeconds(StorageConfigInfo storage) {
        return properties.getCredential().getTtlSeconds();
    }

    private static void putIfPresent(Map<String, String> token, String key, String value) {
        if (value != null && !value.isBlank()) {
            token.put(key, value);
        }
    }

    private String randomSecret(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
