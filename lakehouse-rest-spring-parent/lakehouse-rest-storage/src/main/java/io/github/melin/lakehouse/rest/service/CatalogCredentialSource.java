package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.TencentCosStorageConfigInfo;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;

/**
 * 一个 catalog 的凭据从哪来：**配置意图的判定，不签发、不调云**。
 *
 * <p>控制台只有把这件事显性说出来，用户才能分清三档（设计稿 §10.1）：
 * 「转发服务端存的长期密钥」「现签短期凭据」「不下发（引擎自带身份）」。
 * 后两档在客户端配置上完全一样，唯一能区分它们的地方就是界面上的标记——
 * 这也是 {@code credential-vending-design.md} 把「控制台标记」列进 P1 的理由。
 *
 * <p><b>这是推断，不是运行时事实。</b>判据是「{@link DefaultStorageCredentialManager}
 * 走哪条分支」，与真正下发时一致，但差别有两处，界面必须照原样说：
 *
 * <ol>
 *   <li>STS 那一档只说明「会去签」，签不签得成要到读表时才知道（角色不存在、
 *       信任关系错都会在那一刻失败）。前端不做预检，也不假装能预检（§10.6）。</li>
 *   <li>{@code storageName} 指向的配置项不存在时，真正的取票会 500；这里提前标成
 *       {@link Mode#INVALID}，因为那是**配置错误**而不是运行时失败。</li>
 * </ol>
 *
 * <p>为什么放在服务端而不是控制台里算：判据要读 {@code lakehouse.rest.storage.*} 与
 * {@code lakehouse.rest.credential.providers}，这两份东西前端拿不到；而把优先级
 * 抄进 JS 一定会与 {@link DefaultStorageCredentialManager#s3} 漂移，
 * 漂移的表现是「界面说在签发、实际在转发」——一个只在出事那天才被发现的错。
 */
public final class CatalogCredentialSource {

    /** 凭据来源的三种形态，外加一种「配置本身有问题」。 */
    public enum Mode {

        /** 现签短期凭据：按表收窄、到期失效，可续期。 */
        STS,

        /** 转发服务端存着的长期密钥（catalog 自己的、具名的、或服务端默认的）。 */
        RELAY,

        /** 不下发密钥：只给定位信息，引擎用自己的身份访问。 */
        NONE,

        /** 配置自相矛盾或引用了不存在的配置项，真正取票会失败。 */
        INVALID
    }

    /**
     * @param mode   三档（或「配置有误」）
     * @param detail 一句话说明**这一档具体走的是哪条分支**，直接显示在界面上：
     *               同一个 {@link Mode#RELAY} 可能是 catalog 自带静态凭据、具名存储、
     *               也可能是服务端默认凭据，三者轮换方式完全不同
     */
    public record Source(Mode mode, String detail) {
    }

    private CatalogCredentialSource() {
    }

    /**
     * 按 catalog 的存储配置与部署配置判定来源。
     *
     * <p>{@code storage} 可以是对外那份（{@code accessKeyId} 已打码成 {@code ******}）：
     * 本方法只问「有没有配」，不读密钥本身，因此打码值不影响结论——
     * 这也让控制台可以直接把列表接口的响应喂进来，不必再查一次库。
     *
     * @param storage    catalog 的 {@code storageConfigInfo}
     * @param properties 服务端配置（具名存储、默认凭据、启用的 provider）
     */
    public static Source of(StorageConfigInfo storage, RestServerProperties properties) {
        if (storage == null) {
            // 没有存储配置的 catalog 在老数据里存在（早于 storageConfigInfo 必填）。
            // 取票会 500，界面直接说清楚，而不是显示一个看起来正常的三态之一。
            return new Source(Mode.INVALID, "没有存储配置：取票会 500，请先补全 storageConfigInfo");
        }
        return switch (storage.storageType()) {
            case FILE -> new Source(Mode.NONE,
                    "本地文件系统：没有密钥可发，只回一个自包含令牌（内容是表 id 与过期时刻）");
            case AZURE -> new Source(Mode.NONE,
                    "Azure：只发定位元数据（tenantId / account / hierarchical），SAS 由引擎用自己的身份换"
                            + "（服务端不出具 SAS，见设计稿 §6.2）");
            case GCS -> gcs(properties);
            case S3 -> aws((AwsStorageConfigInfo) storage, properties);
            case OBS -> objectStore((HuaweiObsStorageConfigInfo) storage, "obs",
                    properties.getStorage().getObs());
            case OSS -> objectStore((AliyunOssStorageConfigInfo) storage, "oss",
                    properties.getStorage().getOss());
            case COS -> cos((TencentCosStorageConfigInfo) storage, properties);
        };
    }

    /**
     * GCS：只有一条路——把配置里的访问令牌透传下去。
     *
     * <p>没有令牌时引擎用自己的 ADC，与服务端无关；因此这一档是 {@link Mode#NONE}，
     * 而不是「签发」（服务端不会替任何人去换令牌）。令牌本身是长期访问令牌，
     * 透传它的性质与转发 AK/SK 相同，所以归 {@link Mode#RELAY}。
     */
    private static Source gcs(RestServerProperties properties) {
        String token = properties.getStorage().getGcp().getToken();
        if (token == null || token.isBlank()) {
            return new Source(Mode.NONE,
                    "GCS：服务端没有配置访问令牌，引擎用自己的 ADC（gcloud 凭据 / 服务账号文件）");
        }
        return new Source(Mode.RELAY,
                "GCS：下发服务端配置的访问令牌（lakehouse.rest.storage.gcp.token，长期）");
    }

    /**
     * S3：与 {@link DefaultStorageCredentialManager#s3} 同一套优先级。
     *
     * <p>顺序不能照抄成「先看 roleArn」：静态凭据与 {@code stsUnavailable} 是调用方的
     * **明确决定**，STS 只是服务端的默认策略，策略不许覆盖决定
     * （设计稿 §2.2 的三档表与 §5.8）。
     */
    private static Source aws(AwsStorageConfigInfo s3, RestServerProperties properties) {
        if (StorageConfigs.hasStaticCredentials(s3)) {
            return new Source(Mode.RELAY,
                    "转发 catalog 自带的静态凭据（长期，轮换要改这个 catalog）");
        }
        if (Boolean.TRUE.equals(s3.stsUnavailable())) {
            return new Source(Mode.NONE,
                    "stsUnavailable=true：只发端点与区域，不向云申请临时凭据，引擎用自己的身份"
                            + "（这一档与「转发」是两回事，见设计稿 §3.5.2）");
        }
        boolean roleArn = s3.roleArn() != null && !s3.roleArn().isBlank();
        boolean signer = signsShortLived(properties);
        if (roleArn && signer) {
            // noInlinePolicy 不改变「会不会签发」，只改变票的作用域。必须在同一句里说出来：
            // 它把这一档从「按表收窄」降成「角色权限范围内」，而界面上那三档本身长得一样。
            if (Boolean.TRUE.equals(s3.noInlinePolicy())) {
                return new Source(Mode.STS,
                        "STS 签发（noInlinePolicy=true）：这个存储不接受 inline policy，"
                                + "票只受角色自身权限约束，无法按表收窄。有效期到点失效、可续期");
            }
            return new Source(Mode.STS,
                    "STS 签发：服务端用 roleArn 换一份按表收窄的临时凭据（s3-token），"
                            + "有效期到点失效、可续期");
        }
        RestServerProperties.Storage.Aws aws = properties.getStorage().getAws();
        String named = s3.storageName();
        if (named != null && !named.isBlank()) {
            if (!aws.getStorages().containsKey(named)) {
                return new Source(Mode.INVALID,
                        "引用具名存储 " + named + "，但 lakehouse.rest.storage.aws.storages 里没有这个名字："
                                + "取票会 500（不会静默退回默认凭据，见 DefaultStorageCredentialManager）");
            }
            return new Source(Mode.RELAY,
                    "转发具名存储 " + named + " 的密钥（lakehouse.rest.storage.aws.storages，长期）");
        }
        if (configured(aws.getAccessKey(), aws.getSecretKey())) {
            return new Source(Mode.RELAY,
                    "转发服务端默认凭据（lakehouse.rest.storage.aws.access-key，长期）");
        }
        if (roleArn) {
            return new Source(Mode.NONE,
                    "填了 roleArn，但本部署没启用 s3-token（lakehouse.rest.credential.providers）："
                            + "不会签发，退回环境凭据链——引擎要用自己的身份才能访问");
        }
        return new Source(Mode.NONE,
                "服务端没有任何 S3 密钥：引擎走自己的凭据链（实例角色 / 环境变量 / 配置文件）");
    }

    /**
     * OBS / OSS：与 S3 同构，但**没有签发档**——两家的临时凭据接口还没接
     * （P1-2 / P1-3），因此 {@code roleArn} 在这里不产生 STS。
     */
    private static Source objectStore(StorageConfigInfo storage, String group,
                                      RestServerProperties.Storage.CloudCredentials credentials) {
        if (StorageConfigs.hasStaticCredentials(storage)) {
            return new Source(Mode.RELAY,
                    "转发 catalog 自带的静态凭据（长期，轮换要改这个 catalog）");
        }
        if (stsUnavailable(storage)) {
            return new Source(Mode.NONE,
                    "stsUnavailable=true：只发端点，不申请临时凭据，引擎用自己的身份");
        }
        if (sessionTokenConfigured(credentials)) {
            return new Source(Mode.RELAY,
                    "转发服务端配置的临时凭据（" + group + "：AK/SK + 安全令牌）");
        }
        String named = storage.storageName();
        if (named != null && !named.isBlank()) {
            if (!credentials.getStorages().containsKey(named)) {
                return new Source(Mode.INVALID,
                        "引用具名存储 " + named + "，但 lakehouse.rest.storage." + group
                                + ".storages 里没有这个名字：取票会 500");
            }
            return new Source(Mode.RELAY,
                    "转发具名存储 " + named + " 的密钥（lakehouse.rest.storage." + group + ".storages）");
        }
        if (configured(credentials.getAccessKey(), credentials.getSecretKey())) {
            return new Source(Mode.RELAY,
                    "转发服务端默认凭据（lakehouse.rest.storage." + group + ".access-key，长期）");
        }
        return new Source(Mode.NONE,
                "服务端没有任何 " + group.toUpperCase() + " 密钥：引擎用自己的身份访问");
    }

    /**
     * COS：只有转发档，没有签发档。
     *
     * <p>COSN 的 FileIO 不收临时凭据（设计稿 §6.3.4，P1-13 已作废），所以
     * {@code roleArn} 对 COS 没有意义；而**配了 sessionToken 会让取票直接 500**
     * （{@code putSecrets} 的显式拒绝），这一条必须比「转发」更早说出来。
     */
    private static Source cos(TencentCosStorageConfigInfo cos, RestServerProperties properties) {
        RestServerProperties.Storage.CloudCredentials credentials = properties.getStorage().getCos();
        if (!StorageConfigs.hasStaticCredentials(cos)
                && !Boolean.TRUE.equals(cos.stsUnavailable())
                && sessionTokenConfigured(credentials)
                && (cos.storageName() == null || cos.storageName().isBlank())) {
            return new Source(Mode.INVALID,
                    "lakehouse.rest.storage.cos 里配了 sessionToken，但 COSN 没有令牌位："
                            + "取票会 500。要么删掉令牌改用长期密钥，要么换 S3 类型的 catalog");
        }
        return objectStore(cos, "cos", credentials);
    }

    /** 本部署是否会去签短期凭据：只看 provider 名单，不看 catalog。 */
    private static boolean signsShortLived(RestServerProperties properties) {
        return properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_S3_TOKEN);
    }

    private static boolean sessionTokenConfigured(RestServerProperties.Storage.CloudCredentials credentials) {
        return credentials.getSessionToken() != null && !credentials.getSessionToken().isBlank();
    }

    private static boolean stsUnavailable(StorageConfigInfo storage) {
        return switch (storage.storageType()) {
            case OBS -> Boolean.TRUE.equals(((HuaweiObsStorageConfigInfo) storage).stsUnavailable());
            case OSS -> Boolean.TRUE.equals(((AliyunOssStorageConfigInfo) storage).stsUnavailable());
            case COS -> Boolean.TRUE.equals(((TencentCosStorageConfigInfo) storage).stsUnavailable());
            default -> false;
        };
    }

    private static boolean configured(String accessKey, String secretKey) {
        return accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
