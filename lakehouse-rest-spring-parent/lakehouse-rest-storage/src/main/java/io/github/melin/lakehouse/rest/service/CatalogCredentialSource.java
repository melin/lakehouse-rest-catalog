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
 * <p>五档是一个维度，不是两个（v2 的 {@code serverReadable} + {@code engineCredential}
 * 两维已收敛）。理由很实在：控制台能给的只有一格标签，而两维组合出来的四种状态里
 * 有一种（「服务端能读、引擎拿不到」）根本不应该被建出来。
 *
 * <ul>
 *   <li>{@link Mode#VENDED} 现签短期凭据：S3 / OSS / Azure / GCS 四条，按表收窄；</li>
 *   <li>{@link Mode#STATIC} 转发 catalog 上的长期密钥：四格全通，但没有时限；</li>
 *   <li>{@link Mode#LOCAL} 本地文件系统：没有密钥这回事；</li>
 *   <li>{@link Mode#LEGACY_ENV} **什么都用不上**：catalog 上没有服务端可用的秘密，
 *       服务端读它靠进程自己的身份（预览大概率 500），引擎靠它自己的；</li>
 *   <li>{@link Mode#INVALID} 声明了身份却缺签名材料（{@code roleArn} 没配 AK/SK、
 *       启用了 {@code azure-sas} 却没配服务主体）。</li>
 * </ul>
 *
 * <p><b>{@code NONE} 必须拆成 {@code LOCAL} 与 {@code LEGACY_ENV}。</b>它原来把
 * 「本地文件」（正常状态，永远不会有密钥）和「没有密钥」（问题，建于 v3 之前或漏填）
 * 挤在同一档，而两者对用户的意思相反：前者什么都不用做，后者要补一种方式。
 *
 * <p><b>这是推断，不是运行时事实。</b>判据是「{@link DefaultStorageCredentialManager}
 * 会走哪条分支」，与真正下发时一致，但有一处必须照原样说清楚：VENDED 只说明
 * 「会去签」，签不签得成要到读表时才知道（角色不存在、信任关系错、服务账号被删）。
 * 前端不做预检，也不假装能预检。
 *
 * <p>为什么放在服务端而不是控制台里算：判据要读
 * {@code lakehouse.rest.credential.providers}，前端拿不到；而把优先级抄进 JS 一定会与
 * {@link DefaultStorageCredentialManager} 漂移，漂移的表现是「界面说在签发、实际在转发」。
 */
public final class CatalogCredentialSource {

    /** 凭据来源的五档。取值即协议字段，改名等于改接口，因此不要为了措辞好看去动它。 */
    public enum Mode {

        /** 服务端现签短期凭据：按表收窄、到期失效（能不能续期看方式）。 */
        VENDED,

        /** 转发 catalog 自带的长期密钥：四格全通，但没有时限。 */
        STATIC,

        /** 本地文件系统：没有凭据可发，服务端自签一个自包含令牌。 */
        LOCAL,

        /**
         * 什么都用不上：catalog 上没有服务端可用的秘密，服务端与引擎各自用进程身份。
         *
         * <p>只读状态——它不能被「选」出来，只能被「补全」掉（编辑时选一种方式）。
         */
        LEGACY_ENV,

        /** 配置自相矛盾：声明了身份却缺签名材料，取票会失败或静默退回别的档。 */
        INVALID
    }

    /**
     * @param mode   五档之一
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
            // 取票会 500，界面直接说清楚，而不是显示一个看起来正常的五档之一。
            return new Source(Mode.INVALID, "没有存储配置：取票会 500，请先补全 storageConfigInfo");
        }
        return switch (storage.storageType()) {
            case FILE -> new Source(Mode.LOCAL,
                    "本地文件系统：没有密钥可发，服务端只回一个自包含令牌（内容是表 id 与过期时刻）");
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
     *
     * <p><b>「填了角色、本部署没启用 s3-token」按 STATIC 报，不按 INVALID。</b>
     * 管理器的实际行为是：签发那一档没人接 → 退回转发 → 那对钥匙原样发给引擎。
     * 界面上说「在签发」会让人以为拿到了短期票，说「配置有误」又会让人去查一个
     * 并不存在的错误——它只是降级成了静态密钥，行为与配一把钥匙完全一致。
     */
    private static Source aws(AwsStorageConfigInfo s3, RestServerProperties properties) {
        boolean hasCredentials = StorageConfigs.hasCredentials(s3);
        if (Boolean.TRUE.equals(s3.stsUnavailable())) {
            return hasCredentials
                    ? new Source(Mode.STATIC, "stsUnavailable=true：不向云申请临时凭据，"
                            + "catalog 上的 AK/SK 会原样转发给引擎（长期，轮换要改这个 catalog）")
                    : new Source(Mode.LEGACY_ENV, "stsUnavailable=true 又没有密钥："
                            + "服务端与引擎都只能用各自进程的身份，预览大概率失败");
        }
        boolean roleArn = !blank(s3.roleArn());
        boolean enabled = signsShortLived(properties);
        if (roleArn && !hasCredentials) {
            // 新配置在这里会被 400 拦住（roleArn 必带一对只授 sts:AssumeRole 的钥匙）；
            // 这一档留给存量，别让它显示成「在签发」
            return new Source(Mode.INVALID,
                    "填了 roleArn 但没有 accessKeyId / secretAccessKey：没有签名身份就去不了"
                            + " AssumeRole。新配置会被直接拒绝，存量请补上这对钥匙（或清空 roleArn）");
        }
        if (roleArn && enabled) {
            // noInlinePolicy 不改变「会不会签发」，只改变票的作用域。必须在同一句里说出来：
            // 它把这一档从「按表收窄」降成「角色权限范围内」，而界面上那几档本身长得一样。
            if (Boolean.TRUE.equals(s3.noInlinePolicy())) {
                return new Source(Mode.VENDED,
                        "STS 签发（noInlinePolicy=true）：这个存储不接受 inline policy，"
                                + "票只受角色自身权限约束，无法按表收窄。有效期到点失效、可续期");
            }
            return new Source(Mode.VENDED,
                    "STS 签发：服务端用 roleArn 换一份按表收窄的临时凭据（s3-token），"
                            + "有效期到点失效、可续期");
        }
        if (hasCredentials) {
            return new Source(Mode.STATIC, roleArn
                    ? "catalog 上的那对钥匙会原样转发给引擎：本部署没启用 s3-token"
                            + "（lakehouse.rest.credential.providers），roleArn 用不上，"
                            + "结果是一把长期密钥"
                    : "转发 catalog 自带的静态凭据（长期，轮换要改这个 catalog）");
        }
        return new Source(Mode.LEGACY_ENV,
                "catalog 上没有密钥：服务端读这个位置靠进程自己的身份（预览可能 500），"
                        + "引擎也用它自己的凭据链。编辑时补一种方式即可");
    }

    /**
     * OBS / OSS / COS：{@code stsUnavailable} → 签发 → 转发 → 环境链。
     *
     * <p><b>两处顺序必须与 {@code DefaultStorageCredentialManager#vendOther} 一致**，
     * 否则界面上写着「签发」而取票走的是转发。
     *
     * <p>这三家里只有 OSS 有签发档（OSS 的 RAM 角色）。OBS 的委托与 COS 的临时密钥
     * 在规格 / COSN 认证包里都不存在，所以它们永远只会是 STATIC 或 LEGACY_ENV。
     */
    private static Source objectStore(StorageConfigInfo storage, RestServerProperties properties) {
        boolean hasCredentials = StorageConfigs.hasCredentials(storage);
        String group = storage.storageType().wireName();
        if (stsUnavailable(storage)) {
            return hasCredentials
                    ? new Source(Mode.STATIC, "stsUnavailable=true：只发端点、不申请临时凭据，"
                            + "catalog 上的 AK/SK 会原样转发给引擎")
                    : new Source(Mode.LEGACY_ENV, "stsUnavailable=true 又没有密钥："
                            + "服务端与引擎都只能用各自进程的身份");
        }
        if (storage instanceof AliyunOssStorageConfigInfo oss && !blank(oss.roleArn())) {
            boolean enabled = properties.getCredential().getProviders()
                    .contains(VendedStorageCredential.PROVIDER_OSS_STS);
            if (!hasCredentials) {
                return new Source(Mode.INVALID,
                        "填了 roleArn 但没有 accessKeyId / secretAccessKey：阿里云的 STS"
                                + " 必须用一对 AK/SK 签名，因此签不出来。新配置会被直接拒绝");
            }
            if (!enabled) {
                return new Source(Mode.STATIC,
                        "本部署没启用 oss-sts（lakehouse.rest.credential.providers）：roleArn 用不上，"
                                + "catalog 上的 AK/SK 会原样转发给引擎");
            }
            return new Source(Mode.VENDED,
                    "阿里云 RAM STS 签发：服务端用 catalog 上的 AK/SK 去 assume roleArn，"
                            + "换一份按表收窄的临时凭据，有效期 900–3600 秒（超出会被夹紧）。"
                            + "没有续期——hadoop-aliyun 不会自动换票，作业时长要小于有效期");
        }
        if (hasCredentials) {
            return new Source(Mode.STATIC,
                    "转发 catalog 自带的静态凭据（长期，轮换要改这个 catalog）");
        }
        return new Source(Mode.LEGACY_ENV,
                "catalog 上没有 " + group + " 密钥：服务端与引擎都只能用各自进程的身份"
                        + "（服务端预览大概率失败）。编辑时补一种方式即可");
    }

    /**
     * Azure：能签 SAS 就签；配了服务主体但本部署没启用就什么都不发；两样都没有也是什么都不发。
     *
     * <p><b>Azure 的「不签发」不是环境身份这一档，而是「服务端没有凭据」</b>：
     * 它签 SAS 必须有一个应用注册，没有就只剩定位元数据。所以后两种情况都归
     * {@link Mode#LEGACY_ENV}——它们对用户的意思是同一件事：补一个服务主体。
     */
    private static Source azure(AzureStorageConfigInfo azure, RestServerProperties properties) {
        boolean enabled = properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_AZURE_SAS);
        boolean hasPrincipal = StorageConfigs.hasCredentials(azure) && !blank(azure.tenantId());
        if (enabled && !hasPrincipal) {
            return new Source(Mode.INVALID,
                    "azure-sas 已启用，但这个 catalog 没有配齐 tenantId / clientId / clientSecret："
                            + "签不出 SAS，只会发定位元数据（预览会失败）");
        }
        if (enabled) {
            return new Source(Mode.VENDED,
                    "Azure 用户委托 SAS：服务端换委托密钥后按目录签发，覆盖这张表的路径，"
                            + "有效期到点失效。没有标准续期端点，平台上限 7 天——"
                            + "作业时长要小于有效期");
        }
        return new Source(Mode.LEGACY_ENV, hasPrincipal
                ? "服务主体已填，但本部署没启用 azure-sas（lakehouse.rest.credential.providers）："
                        + "不会签 SAS，服务端只能发定位元数据，预览会失败。找运维启用即可"
                : "没有服务主体（tenantId / clientId / clientSecret）：签不出 SAS，"
                        + "服务端只能发定位元数据，预览会失败");
    }

    /**
     * GCS：catalog 上有服务账号且本部署启用 {@code gcs-downscope} 才能换小。
     *
     * <p>这一家**不需要角色**：downscoped token 是「源令牌的更小投影」，换不换得成取决于
     * 那个服务账号有没有 {@code roles/iam.serviceAccountTokenCreator}。
     */
    private static Source gcs(GcpStorageConfigInfo gcs, RestServerProperties properties) {
        boolean enabled = properties.getCredential().getProviders()
                .contains(VendedStorageCredential.PROVIDER_GCS_DOWNSCOPE);
        boolean hasAccount = StorageConfigs.hasCredentials(gcs);
        if (enabled && hasAccount) {
            return new Source(Mode.VENDED,
                    "GCS downscoped token：服务端用 catalog 上的服务账号换访问令牌，再收窄成"
                            + "只能覆盖这张表的令牌（boundary 写到对象层，最长 1 小时）。"
                            + "没有续期——作业时长要小于有效期");
        }
        if (enabled) {
            return new Source(Mode.INVALID,
                    "gcs-downscope 已启用，但这个 catalog 没有 serviceAccountJson："
                            + "没有源身份可换，签不出来");
        }
        return new Source(Mode.LEGACY_ENV, hasAccount
                ? "catalog 上有服务账号，但本部署没启用 gcs-downscope：不签发，也不转发它，"
                        + "服务端与引擎都只能用各自的身份"
                : "没有 serviceAccountJson：服务端与引擎都只能用各自的身份");
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
