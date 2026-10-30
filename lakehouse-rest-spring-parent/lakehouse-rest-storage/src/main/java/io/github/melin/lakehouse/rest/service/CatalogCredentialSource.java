package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.TencentCosStorageConfigInfo;
import io.github.melin.lakehouse.rest.support.StorageConfigs;
import io.github.melin.lakehouse.rest.support.VendedStorageCredential;

/**
 * 一个 catalog 的凭据从哪来：**配置意图的判定，不签发、不调云**。
 *
 * <p>控制台只有把这件事显性说出来，用户才能分清三档（设计稿 §10.1）：
 * 「转发 catalog 自带的长效密钥」「现签短期凭据」「不下发（引擎自带身份）」。
 * 后两档在客户端配置上完全一样，唯一能区分它们的地方就是界面上的标记——
 * 这也是 {@code credential-vending-design.md} 把「控制台标记」列进 P1 的理由。
 *
 * <p><b>这是推断，不是运行时事实。</b>判据是「{@link DefaultStorageCredentialManager}
 * 走哪条分支」，与真正下发时一致，但差别有两处，界面必须照原样说：
 *
 * <ol>
 *   <li>STS 那一档只说明「会去签」，签不签得成要到读表时才知道（角色不存在、
 *       信任关系错、服务账号被删都会在那一刻失败）。前端不做预检，也不假装能预检（§10.6）。</li>
 *   <li>「填了角色/服务账号，但少了签名用的钥匙」这一档是**配置错误**，
 *       真正取票时会退回转发或环境链——界面必须说出来，否则「为什么还是长期密钥」
 *       会变成一道猜谜。</li>
 * </ol>
 *
 * <p>为什么放在服务端而不是控制台里算：判据要读 {@code lakehouse.rest.credential.providers}，
 * 前端拿不到；而把优先级抄进 JS 一定会与 {@link DefaultStorageCredentialManager#s3} 漂移，
 * 漂移的表现是「界面说在签发、实际在转发」——一个只在出事那天才被发现的错。
 */
public final class CatalogCredentialSource {

    /** 凭据来源的三种形态，外加一种「配置本身有问题」。 */
    public enum Mode {

        /** 现签短期凭据：按表收窄、到期失效、可续期。STS 与 Azure 的 SAS 都算这一档。 */
        STS,

        /** 转发 catalog 自带的长期密钥。 */
        RELAY,

        /** 不下发密钥：只给定位信息，引擎用自己的身份访问。 */
        NONE,

        /** 配置自相矛盾，真正取票会失败或会静默退回别的档。 */
        INVALID
    }

    /**
     * @param mode   三档（或「配置有误」）
     * @param detail 一句话说明**这一档具体走的是哪条分支**，直接显示在界面上
     */
    public record Source(Mode mode, String detail) {
    }

    private CatalogCredentialSource() {
    }

    /**
     * 按 catalog 的存储配置与部署配置判定来源。
     *
     * <p>{@code storage} 可以是对外那份（凭据已打码成 {@code ******}）：
     * 本方法只问「有没有配」，不读密钥本身，因此打码值不影响结论——
     * 这也让控制台可以直接把列表接口的响应喂进来，不必再查一次库。
     *
     * @param storage    catalog 的 {@code storageConfigInfo}
     * @param properties 服务端配置（只用其中的 provider 名单）
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
            case GCS -> gcs((GcpStorageConfigInfo) storage, properties);
            case S3 -> aws((AwsStorageConfigInfo) storage, properties);
            case OBS -> objectStore((HuaweiObsStorageConfigInfo) storage, properties);
            case OSS -> objectStore((AliyunOssStorageConfigInfo) storage, properties);
            case COS -> objectStore((TencentCosStorageConfigInfo) storage, properties);
        };
    }

    /**
     * S3：与 {@link DefaultStorageCredentialManager#s3} 同一套顺序——
     * {@code stsUnavailable} → 签发 → 转发 → 环境链。
     *
     * <p>「签发」判在「转发」之前，因为 catalog 上的 AK/SK 在配了 {@code roleArn} 时
     * 是**签名用的身份**，不是要转发给引擎的钥匙。顺序写反，界面就会把「在签发」
     * 显示成「在转发」。
     */
    private static Source aws(AwsStorageConfigInfo s3, RestServerProperties properties) {
        boolean hasCredentials = StorageConfigs.hasCredentials(s3);
        if (Boolean.TRUE.equals(s3.stsUnavailable())) {
            // 档位要跟着密钥有无走：真的把钥匙发出去了还说「不下发」，是界面上最容易
            // 误导人的一种不一致（用户以为引擎在用环境身份，其实拿的是 catalog 上的钥匙）
            return hasCredentials
                    ? new Source(Mode.RELAY, "stsUnavailable=true：不向云申请临时凭据，"
                            + "catalog 上的 AK/SK 仍会原样转发给引擎")
                    : new Source(Mode.NONE, "stsUnavailable=true：不向云申请临时凭据，"
                            + "也没有密钥可转发，引擎用自己的身份");
        }
        boolean roleArn = !blank(s3.roleArn());
        boolean enabled = signsShortLived(properties);
        if (roleArn && enabled) {
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
        if (hasCredentials) {
            return new Source(Mode.RELAY,
                    "转发 catalog 自带的静态凭据（长期，轮换要改这个 catalog）");
        }
        if (roleArn) {
            return new Source(Mode.NONE,
                    "填了 roleArn，但本部署没启用 s3-token（lakehouse.rest.credential.providers）："
                            + "不会签发，退回环境凭据链——引擎要用自己的身份才能访问");
        }
        return new Source(Mode.NONE,
                "catalog 上没有密钥：引擎走自己的凭据链（实例角色 / 环境变量 / 配置文件）");
    }

    /**
     * OBS / OSS / COS：{@code stsUnavailable} → 签发 → 转发 → 环境链。
     *
     * <p><b>两处顺序必须与 {@code DefaultStorageCredentialManager#vendOther} 一致**，
     * 否则界面上写着「签发」而取票走的是转发。
     *
     * <p>签发档只有 OSS 有（P1-2）：它要三个条件同时成立——能力（provider 名单）、
     * 上限（catalog 上的 {@code roleArn}）、签名身份（catalog 上的 AK/SK）。
     * 少了最后一个时取票会让 provider 返回空、链继续走到转发，所以这里必须明说，
     * 否则「为什么还是长期密钥」变成一道猜谜。
     */
    private static Source objectStore(StorageConfigInfo storage, RestServerProperties properties) {
        boolean hasCredentials = StorageConfigs.hasCredentials(storage);
        String group = storage.storageType().wireName();
        if (stsUnavailable(storage)) {
            return hasCredentials
                    ? new Source(Mode.RELAY, "stsUnavailable=true：只发端点、不申请临时凭据，"
                            + "catalog 上的 AK/SK 仍会原样转发给引擎")
                    : new Source(Mode.NONE, "stsUnavailable=true：只发端点、不申请临时凭据，"
                            + "也没有密钥可转发，引擎用自己的身份");
        }
        if (storage instanceof AliyunOssStorageConfigInfo oss && !blank(oss.roleArn())) {
            boolean enabled = properties.getCredential().getProviders()
                    .contains(VendedStorageCredential.PROVIDER_OSS_STS);
            if (!enabled) {
                return new Source(hasCredentials ? Mode.RELAY : Mode.NONE,
                        "catalog 填了 roleArn，但本部署没启用 oss-sts（lakehouse.rest.credential"
                                + ".providers）：不会签发，"
                                + (hasCredentials ? "AK/SK 会原样转发给引擎" : "也没有 AK/SK 可转发"));
            }
            if (!hasCredentials) {
                return new Source(Mode.INVALID,
                        "catalog 填了 roleArn，但没有 accessKeyId / secretAccessKey：阿里云的 STS"
                                + " 必须用一对 AK/SK 签名，因此不会签发，取票会退回环境链");
            }
            return new Source(Mode.STS,
                    "阿里云 RAM STS 签发：服务端用 catalog 上的 AK/SK 去 assume roleArn，"
                            + "换一份按表收窄的临时凭据，有效期 900–3600 秒（超出会被夹紧）。"
                            + "**没有续期**——hadoop-aliyun 不会自动换票");
        }
        if (hasCredentials) {
            return new Source(Mode.RELAY,
                    "转发 catalog 自带的静态凭据（长期，轮换要改这个 catalog）");
        }
        return new Source(Mode.NONE,
                "catalog 上没有 " + group + " 密钥：引擎用自己的身份访问"
                        + "（OBS 走 fs.obs.security.provider，OSS 走环境凭据链）");
    }

    /**
     * Azure：能签 SAS 就签，签不了就还是「只给定位元数据」。
     *
     * <p><b>两个条件缺一不可</b>：部署的 {@code credential.providers} 里有 {@code azure-sas}，
     * 且 catalog 上配齐了服务主体（tenantId + clientId + clientSecret）。只满足「启用」
     * 而没配身份时，真正的取票会**退回**定位元数据（provider 返回空、链继续往下走），
     * 所以界面必须说「启用但没身份」，而不是显示「短期签发」——后者会让人以为已经收窄了。
     *
     * <p>与 S3 那一档的差别值得写在界面上：Azure 的 SAS **没有标准续期端点**
     * （Iceberg 的 ADLSFileIO 不会自己去换票），平台上限 7 天。所以这里不能像 S3 那样
     * 说「可续期」——说了的话，长作业会在中途开始 403，而它没有任何理由预期这件事。
     */
    private static Source azure(AzureStorageConfigInfo azure, RestServerProperties properties) {
        boolean enabled = properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_AZURE_SAS);
        boolean hasPrincipal = StorageConfigs.hasCredentials(azure) && !blank(azure.tenantId());
        if (!enabled) {
            return new Source(Mode.NONE,
                    "Azure：只发定位元数据（tenantId / account / hierarchical），SAS 由引擎用自己的身份换"
                            + "。要服务端签 SAS，把 azure-sas 加进 lakehouse.rest.credential.providers");
        }
        if (!hasPrincipal) {
            return new Source(Mode.INVALID,
                    "azure-sas 已启用，但这个 catalog 没有配齐 tenantId / clientId / clientSecret："
                            + "仍然只发定位元数据（不会失败，也不会签发）");
        }
        return new Source(Mode.STS,
                "Azure 用户委托 SAS：服务端换委托密钥后按目录签发，覆盖这张表的路径，"
                        + "有效期到点失效。**没有标准续期端点**，平台上限 7 天——"
                        + "作业时长要小于有效期");
    }

    /**
     * GCS：catalog 上有服务账号就能换小，没有就让引擎用自己的 ADC。
     *
     * <p>判据只有两条（是否启用 {@code gcs-downscope}、catalog 上有没有
     * {@code serviceAccountJson}），因为这一家**不需要角色**：downscoped token 是
     * 「源令牌的更小投影」，换不换得成取决于那个服务账号有没有
     * {@code roles/iam.serviceAccountTokenCreator}，而那不是配置能表达的事。
     */
    private static Source gcs(GcpStorageConfigInfo gcs, RestServerProperties properties) {
        boolean enabled = properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE);
        boolean hasAccount = StorageConfigs.hasCredentials(gcs);
        if (enabled && hasAccount) {
            return new Source(Mode.STS,
                    "GCS downscoped token：服务端用 catalog 上的服务账号换访问令牌，再收窄成"
                            + "只能覆盖这张表的令牌（boundary 写到对象层，最长 1 小时）。"
                            + "**没有续期**——作业时长要小于有效期");
        }
        if (enabled) {
            return new Source(Mode.NONE,
                    "gcs-downscope 已启用，但这个 catalog 没有 serviceAccountJson："
                            + "没有源身份可换，仍然让引擎用自己的 ADC");
        }
        if (hasAccount) {
            return new Source(Mode.NONE,
                    "GCS：catalog 上有服务账号，但本部署没启用 gcs-downscope：不签发，"
                            + "引擎用自己那份访问令牌（它拿不到这里的服务账号）");
        }
        return new Source(Mode.NONE,
                "GCS：没有服务账号，引擎用自己的 ADC（gcloud 凭据 / Workload Identity）");
    }

    /** 本部署是否会去签短期凭据：只看 provider 名单，不看 catalog。 */
    private static boolean signsShortLived(RestServerProperties properties) {
        return properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_S3_TOKEN);
    }

    private static boolean stsUnavailable(StorageConfigInfo storage) {
        return switch (storage.storageType()) {
            case OBS -> Boolean.TRUE.equals(((HuaweiObsStorageConfigInfo) storage).stsUnavailable());
            case OSS -> Boolean.TRUE.equals(((AliyunOssStorageConfigInfo) storage).stsUnavailable());
            case COS -> Boolean.TRUE.equals(((TencentCosStorageConfigInfo) storage).stsUnavailable());
            default -> false;
        };
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
