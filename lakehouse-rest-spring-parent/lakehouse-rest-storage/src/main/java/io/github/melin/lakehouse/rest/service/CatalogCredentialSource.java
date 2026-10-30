package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
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

        /** 现签短期凭据：按表收窄、到期失效、可续期。STS 与 Azure 的 SAS 都算这一档。 */
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
            case AZURE -> azure((AzureStorageConfigInfo) storage, properties);
            case GCS -> gcs(properties);
            case S3 -> aws((AwsStorageConfigInfo) storage, properties);
            case OBS -> objectStore((HuaweiObsStorageConfigInfo) storage, "obs",
                    properties.getStorage().getObs(), null);
            case OSS -> objectStore((AliyunOssStorageConfigInfo) storage, "oss",
                    properties.getStorage().getOss(), ossSts(properties));
            case COS -> cos((TencentCosStorageConfigInfo) storage, properties);
        };
    }

    /**
     * GCS：有令牌就能换小，没令牌就让引擎用自己的 ADC。
     *
     * <p>三档的判据只有两条（是否启用 {@code gcs-downscope}、是否有源令牌），
     * 因为这一家**不需要角色**：downscoped token 是「源令牌的更小投影」，
     * 换不换得成取决于源凭据有没有 {@code roles/iam.serviceAccountTokenCreator}，
     * 而那不是配置能表达的事。
     *
     * <p>所以「启用但没有令牌」这一档要专门说：它看起来是「开了签发」，
     * 实际 provider 会返回空、链继续走到「引擎用 ADC」。
     */
    private static Source gcs(RestServerProperties properties) {
        boolean enabled = properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE);
        String token = properties.getStorage().getGcp().getToken();
        boolean hasToken = token != null && !token.isBlank();
        if (enabled && hasToken) {
            return new Source(Mode.STS,
                    "GCS downscoped token：服务端把配置里的访问令牌换成一张只覆盖这张表的令牌"
                            + "（boundary 写在对象层，最长 1 小时）。**没有续期**——"
                            + "作业时长要小于有效期");
        }
        if (enabled) {
            return new Source(Mode.NONE,
                    "gcs-downscope 已启用，但 lakehouse.rest.storage.gcp.token 是空的："
                            + "没有源令牌可换，仍然让引擎用自己的 ADC");
        }
        if (hasToken) {
            return new Source(Mode.RELAY,
                    "GCS：下发服务端配置的访问令牌（lakehouse.rest.storage.gcp.token，长期）。"
                            + "要按表收窄，把 gcs-downscope 加进 lakehouse.rest.credential.providers");
        }
        return new Source(Mode.NONE,
                "GCS：服务端没有配置访问令牌，引擎用自己的 ADC（gcloud 凭据 / 服务账号文件）");
    }

    /**
     * Azure：能签 SAS 就签，签不了就还是「只给定位元数据」。
     *
     * <p><b>两个条件缺一不可</b>：部署的 {@code credential.providers} 里有 {@code azure-sas}，
     * 且三项服务身份（租户、clientId、clientSecret）齐全——租户可以来自 catalog，
     * 也可以是配置兜底。只满足了「启用」而没配身份时，真正的取票会**退回**定位元数据
     * （provider 返回空、链继续往下走），所以界面必须说「启用但没身份」，
     * 而不是显示「短期签发」——后者会让人以为已经收窄了。
     *
     * <p>与 S3 那一档的差别值得写在界面上：Azure 的 SAS **没有标准续期端点**
     * （Iceberg 的 ADLSFileIO 不会自己去换票），平台上限 7 天。所以这里不能像 S3 那样
     * 说「可续期」——说了的话，长作业会在中途开始 403，而它没有任何理由预期这件事。
     */
    private static Source azure(AzureStorageConfigInfo azure, RestServerProperties properties) {
        boolean enabled = properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_AZURE_SAS);
        if (!enabled) {
            return new Source(Mode.NONE,
                    "Azure：只发定位元数据（tenantId / account / hierarchical），SAS 由引擎用自己的身份换"
                            + "。要服务端签 SAS，把 azure-sas 加进 lakehouse.rest.credential.providers"
                            + " 并配好 lakehouse.rest.storage.azure");
        }
        String tenantId = azure.tenantId() != null && !azure.tenantId().isBlank()
                ? azure.tenantId() : properties.getStorage().getAzure().getTenantId();
        if (tenantId == null || tenantId.isBlank()
                || !configured(properties.getStorage().getAzure().getClientId(),
                        properties.getStorage().getAzure().getClientSecret())) {
            return new Source(Mode.NONE,
                    "azure-sas 已启用，但 lakehouse.rest.storage.azure 里的 tenant-id / client-id /"
                            + " client-secret 没配齐：仍然只发定位元数据（不会失败，也不会签发）");
        }
        return new Source(Mode.STS,
                "Azure 用户委托 SAS：服务端换委托密钥后按目录签发，覆盖这张表的路径，"
                        + "有效期到点失效。**没有标准续期端点**，平台上限 7 天——"
                        + "作业时长要小于有效期");
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
     * OBS / OSS：与 S3 同构。
     *
     * <p>{@code sts} 是调用方给出的「签发档」判定（OSS 有，OBS 还没有，见 P1-2 / P1-3）。
     * 它插在 {@code stsUnavailable} 之后、转发之前——这正是
     * {@code DefaultStorageCredentialManager#vendOther} 的顺序：静态凭据与
     * {@code stsUnavailable} 是调用方的明确决定，签发是服务端的默认策略，
     * 转发是最后一档。**两处顺序必须一致**，否则界面上写着「签发」而取票走的是转发。
     */
    private static Source objectStore(StorageConfigInfo storage, String group,
                                      RestServerProperties.Storage.CloudCredentials credentials,
                                      Source sts) {
        if (StorageConfigs.hasStaticCredentials(storage)) {
            return new Source(Mode.RELAY,
                    "转发 catalog 自带的静态凭据（长期，轮换要改这个 catalog）");
        }
        if (stsUnavailable(storage)) {
            return new Source(Mode.NONE,
                    "stsUnavailable=true：只发端点，不申请临时凭据，引擎用自己的身份");
        }
        if (sts != null) {
            return sts;
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
        return objectStore(cos, "cos", credentials, null);
    }

    /**
     * 阿里云的签发档：{@code oss-sts} 启用 + 配了角色 + 配了调用者 AK/SK 才成立。
     *
     * <p><b>三条缺一不可</b>，因为这三件事分别在三个地方：能力（provider 名单）、
     * 上限（RAM 角色）、调用者身份（服务端 AK/SK）。只差最后一条时的现象很容易误判：
     * 界面说「会签发」，取票时 provider 却返回空、链继续走到转发——
     * 于是「为什么还是长期密钥」变成一道猜谜。所以缺调用者身份时这里也明确说。
     *
     * <p>返回 {@code null} 表示「这一档不适用」，交给转发档的正常判定；
     * 这与 Azure 那档不同——Azure 没有转发可选，只能退回「只给定位元数据」。
     */
    private static Source ossSts(RestServerProperties properties) {
        if (!properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_OSS_STS)) {
            return null;
        }
        RestServerProperties.Storage.Oss oss = properties.getStorage().getOss();
        if (oss.getRoleArn() == null || oss.getRoleArn().isBlank()) {
            // 启用了但没配角色：能力不存在，退回转发档（它的文案会指出服务端用了哪对密钥）
            return null;
        }
        if (!configured(oss.getAccessKey(), oss.getSecretKey())) {
            return new Source(Mode.NONE,
                    "oss-sts 已启用、也配了 roleArn，但 lakehouse.rest.storage.oss 里没有"
                            + " access-key / secret-key：阿里云的 STS 必须用一对 AK/SK 签名，"
                            + "因此不会签发（会退回下面的转发档，或让引擎用自己身份）");
        }
        return new Source(Mode.STS,
                "阿里云 RAM STS 签发：服务端用 oss-sts 换一份按表收窄的临时凭据，"
                        + "有效期 900–3600 秒（超出会被夹紧）。**没有续期**——"
                        + "hadoop-aliyun 不会自动换票，作业时长要小于有效期");
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
