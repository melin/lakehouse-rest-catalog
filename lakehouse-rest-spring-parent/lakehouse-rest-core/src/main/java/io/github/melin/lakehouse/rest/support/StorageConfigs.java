package io.github.melin.lakehouse.rest.support;

import io.github.melin.lakehouse.rest.domain.entity.CatalogEntity;
import io.github.melin.lakehouse.rest.dto.ManagementEnums.StorageType;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AliyunOssStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AwsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.AzureStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.FileStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.GcpStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.HuaweiObsStorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.StorageConfigInfo;
import io.github.melin.lakehouse.rest.dto.StorageDtos.TencentCosStorageConfigInfo;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code StorageConfigInfo} 的校验、归一化，以及实体与 DTO 之间的搬运。
 *
 * <p>判别联合的「按类型校验」只能写在 Java 侧：规格里只有
 * {@code AzureStorageConfigInfo} 声明了 {@code required: [tenantId]}，
 * 其余五种都没有必填字段，这一点用注解表达不了。
 *
 * <p><b>OBS / OSS 的 {@code endpoint} 不设为必填。</b>它落空时引擎会需要一个端点，
 * 看起来像该拦下来的配置错误，但服务端无从判断引擎侧是否已经在
 * {@code core-site.xml} 里配好了 {@code fs.obs.endpoint}——这是常见部署形态。
 * 硬判会把合法配置拒之门外，与下面对 {@code allowedLocations} 前缀的处理同一个取舍：
 * 服务端只拒绝它确知非法的输入。
 *
 * <p>刻意**不**校验 {@code allowedLocations} 的 URI 前缀。规格里
 * {@code s3://} / {@code abfss://} / {@code gs://} 是 example 而不是约束，
 * 而现实中 S3 兼容存储常用自定义协议、Azure 还有 {@code wasb://} 这类历史形态。
 * 按前缀硬判会把合法配置拒之门外，因此这里只拒绝空串。
 *
 * <p><b>静态凭据的三个入口在这里收口。</b>规格没有「用户填 AK/SK」的位置，
 * 这对字段是本工程的扩展（见 {@code StorageDtos}），因此它的读写规则也由本类承担：
 * 写路径 {@link #mergeStaticCredentials}（保持/设置/清除）、对外响应
 * {@link #withoutSecrets}（打码 + 抹掉密文）、内部下发 {@link #staticCredentials}
 * （解密后交给凭据管理器）。加解密本身在 {@link CredentialCipher}，
 * 本类只决定「什么时候该调用它」——这样规则可以脱离 Spring 上下文单测。
 *
 * <p><b>打码（{@link #MASKED}）与它的回写哨兵是一对，必须同时成立。</b>对外那份把
 * {@code accessKeyId} 也换成 {@code ******}，于是「读-改-写」的调用方必然把
 * {@code ******} 原样送回来。{@link #mergeStaticCredentials} 若不认这个值，
 * 第一次「只改个名字」的保存就会把库里的 AK ID 写成六个星号，而密钥仍是旧的那把——
 * 密钥对错位，错误现场在存储侧（{@code InvalidAccessKeyId}），与「我刚改了什么」
 * 完全联系不上。所以这里只允许一种组合：<b>打码在 {@link #withoutSecrets} 一处产生，
 * 哨兵在 {@link #mergeStaticCredentials} 一处消费</b>。
 */
public final class StorageConfigs {

    private StorageConfigs() {
    }

    /**
     * 对外响应里 {@code accessKeyId} 的占位值，同时是写路径的「未修改」哨兵。
     *
     * <p>六个星号而不是「前四位 + 星号」这类半遮蔽：半遮蔽会让人把它当成真值抄回去，
     * 而它必须一眼看出「这不是给你用的」。与 Gravitino #13539 的取值一致，
     * 客户端 SDK 只要照抄那个实现就能对上。
     */
    public static final String MASKED = "******";

    /**
     * 按 {@code storageType} 校验类型专属的必填字段。
     *
     * @throws ApiException 400，字段缺失或取值非法
     */
    public static void validate(StorageConfigInfo info) {
        if (info == null) {
            return;
        }
        requireLocationsNotBlank(info);

        if (info instanceof AzureStorageConfigInfo azure && isBlank(azure.tenantId())) {
            throw ApiException.badRequest(
                    "storageConfigInfo.tenantId is required for storageType AZURE");
        }
        if (info instanceof TencentCosStorageConfigInfo cos) {
            requireCosBucketMatchesAppId(cos);
        }
        requireSingleCredentialSource(info);
    }

    /**
     * COS 的 {@code appId} 必须出现在位置里的桶名末尾。
     *
     * <p>COS 的桶名就是 {@code <name>-<appid>}，appId 不是独立参数。填错的表现是
     * 访问一个不存在的桶——COS 返回 404，而引擎的报错会落在「读不到文件」上，
     * 与「密钥不对」的现象只差一层。写入口校验能把这个问题提前到建 catalog 时，
     * 代价只是多一次字符串比较。
     *
     * <p>只在**给了 appId** 时校验：不给表示调用方自己清楚桶名，服务端不猜。
     * 位置里的 {@code cosn://} 之外的前缀（例如 {@code cos://}）不在这里拦——
     * 与 {@link #validate} 的总体取舍一致：只拒绝确知非法的输入。
     */
    private static void requireCosBucketMatchesAppId(TencentCosStorageConfigInfo cos) {
        String appId = cos.appId();
        if (isBlank(appId) || cos.allowedLocations() == null) {
            return;
        }
        String suffix = "-" + appId.trim();
        for (String location : cos.allowedLocations()) {
            String bucket = bucketOf(location);
            if (bucket != null && !bucket.endsWith(suffix)) {
                throw ApiException.badRequest("storageConfigInfo.allowedLocations contains \""
                        + location + "\" whose bucket \"" + bucket + "\" does not end with \""
                        + suffix + "\": a COS bucket is named <bucket>-" + appId.trim()
                        + ", so either the bucket or the appId is wrong");
            }
        }
    }

    /** 位置里的桶名（URI 的 host）；认不出 URI 时返回 {@code null}。 */
    private static String bucketOf(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(location.trim()).getHost();
            return host == null || host.isBlank() ? null : host;
        } catch (IllegalArgumentException e) {
            // 非法 URI 不在这里报错：位置的整体校验不归这个类管，这里只做 appId 比对
            return null;
        }
    }

    /**
     * {@code storageName} 与静态凭据互斥。
     *
     * <p>两者都在回答「用哪把钥匙」：{@code storageName} 指向服务端配置里的具名存储，
     * 静态凭据则是这个 catalog 自己带着的。同时给出时没有确定语义——按前者取值会让
     * 调用方以为后者生效了，按后者取值则让凭据轮换（改服务端配置）静默失效。
     * 这种「两条路都写了、但只有一条生效」的配置，报错比猜要好。
     */
    private static void requireSingleCredentialSource(StorageConfigInfo info) {
        if (hasStaticCredentials(info) && !isBlank(info.storageName())) {
            throw ApiException.badRequest("storageConfigInfo.storageName and static credentials are"
                    + " mutually exclusive: both select the credentials used to access the storage;"
                    + " keep only one of them");
        }
    }

    /**
     * 归一化位置列表：列表为空时退回到给定位置，使落库的 JSON 与投影列完全一致。
     *
     * <p>{@code allowedLocations} 在规格里是可选的，但服务端要用它的首项当仓库根，
     * 因此这里补一个默认值，而不是让下游各自处理空列表。
     */
    public static StorageConfigInfo normalize(StorageConfigInfo info, String fallbackLocation) {
        if (info == null) {
            return null;
        }
        List<String> locations = info.allowedLocations();
        if (locations == null || locations.isEmpty()) {
            List<String> fallback = new ArrayList<>();
            if (fallbackLocation != null && !fallbackLocation.isBlank()) {
                fallback.add(fallbackLocation);
            }
            return copyWith(info, fallback, info.storageName());
        }
        return copyWith(info, new ArrayList<>(locations), info.storageName());
    }

    /** 该配置的仓库根，即 {@code allowedLocations} 的首项；无位置时返回 {@code null}。 */
    public static String warehouseOf(StorageConfigInfo info) {
        if (info == null || info.allowedLocations() == null || info.allowedLocations().isEmpty()) {
            return null;
        }
        String first = info.allowedLocations().get(0);
        return first == null || first.isBlank() ? null : first;
    }

    /** 只含共有字段的 {@code FILE} 配置，位置为给定仓库。供服务端自建 catalog 使用。 */
    public static StorageConfigInfo fileStorage(String warehouse) {
        List<String> locations = new ArrayList<>();
        if (warehouse != null && !warehouse.isBlank()) {
            locations.add(warehouse);
        }
        return new FileStorageConfigInfo(locations, null);
    }

    /**
     * 把存储配置写进实体，同时同步它的两个投影列与仓库位置。
     *
     * <p><b>所有写 catalog 存储的地方都必须走这里</b>：JSON 与投影列由同一处一起落库，
     * 才不会出现「JSON 说是 S3、投影列说是 FILE」这种不一致。
     */
    public static void apply(CatalogEntity entity, StorageConfigInfo config) {
        entity.setStorageConfig(config);
        entity.setStorageType(config.storageType().wireName());
        entity.setAllowedLocations(config.allowedLocations() == null
                ? new ArrayList<>()
                : new ArrayList<>(config.allowedLocations()));
        String warehouse = warehouseOf(config);
        if (warehouse != null) {
            entity.setWarehouse(warehouse);
        }
    }

    /**
     * 还原实体的存储配置。
     *
     * <p>优先用 {@link CatalogEntity#getStorageConfig()}；该列为空时退回投影列重建，
     * 以便读取本特性上线前写入的历史行。重建只能得到共有字段，
     * 类型专属字段一律为空——这是历史数据的固有损失，不是 bug。
     *
     * <p><b>返回的是库中原样，静态凭据在其中是密文。</b>两条使用路径对它的处理不同：
     * 对外响应必须先过 {@link #withoutSecrets}，内部下发凭据走
     * {@link #staticCredentials}。直接把它交给客户端会把密文当密钥发出去
     * （引擎侧表现为认证失败，而不是「密钥不对」），因此这里不提供「默认已脱敏」的
     * 假象——需要哪一份就显式要哪一份。
     */
    public static StorageConfigInfo of(CatalogEntity entity) {
        if (entity == null) {
            return null;
        }
        if (entity.getStorageConfig() != null) {
            return entity.getStorageConfig();
        }
        StorageType type = StorageType.parse(entity.getStorageType()).orElse(StorageType.FILE);
        List<String> locations = entity.getAllowedLocations() == null
                ? new ArrayList<>()
                : new ArrayList<>(entity.getAllowedLocations());
        if (locations.isEmpty() && entity.getWarehouse() != null && !entity.getWarehouse().isBlank()) {
            locations.add(entity.getWarehouse());
        }
        return blank(type, locations);
    }

    /** 按存储类型构造只有共有字段的配置。 */
    public static StorageConfigInfo blank(StorageType type, List<String> allowedLocations) {
        List<String> locations = allowedLocations == null ? new ArrayList<>() : allowedLocations;
        return switch (type) {
            case S3 -> new AwsStorageConfigInfo(locations, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, null);
            case AZURE -> new AzureStorageConfigInfo(locations, null, null, null, null, null);
            case GCS -> new GcpStorageConfigInfo(locations, null, null);
            case OBS -> new HuaweiObsStorageConfigInfo(locations, null, null, null, null, null);
            case OSS -> new AliyunOssStorageConfigInfo(locations, null, null, null, null, null);
            case COS -> new TencentCosStorageConfigInfo(locations, null, null, null, null, null,
                    null, null, null);
            case FILE -> new FileStorageConfigInfo(locations, null);
        };
    }

    // ------------------------------------------------------------------ 静态凭据

    /**
     * 一份配置里的静态凭据。{@code secretAccessKey} 在实体里是**密文**，
     * 只有 {@link #staticCredentials} 拿到的才是明文。
     */
    public record StaticCredentials(String accessKeyId, String secretAccessKey) {
    }

    /** 该配置能否承载静态凭据：只有带 AK/SK 字段的子类型可以。 */
    public static boolean supportsStaticCredentials(StorageConfigInfo info) {
        return info instanceof AwsStorageConfigInfo
                || info instanceof HuaweiObsStorageConfigInfo
                || info instanceof AliyunOssStorageConfigInfo
                || info instanceof TencentCosStorageConfigInfo;
    }

    /** 该配置是否配了静态凭据（以 {@code accessKeyId} 为准，两者由写路径保证成对）。 */
    public static boolean hasStaticCredentials(StorageConfigInfo info) {
        return supportsStaticCredentials(info) && !isBlank(accessKeyIdOf(info));
    }

    /**
     * 取出可下发的静态凭据；没有配置时返回 {@code null}。
     *
     * <p>解密在这里发生，而不是在 DTO 装配时：DTO 是要发给客户端的，而凭据只在
     * 服务端内部消费。把解密收在唯一的读取点上，就不会出现「某个响应顺手把明文带出去」。
     *
     * <p><b>拿到打码值就在这里失败</b>，而不是把它当 AK 发出去。这说明有一处代码把
     * 对外那份（{@link #withoutSecrets} 的结果）当成了内部那份——打码值配上真实密钥
     * 只会在存储侧报 {@code InvalidAccessKeyId}，与本意完全脱钩；报错在这里则一眼能看出
     * 是「传错了一份配置」。这条断言是打码引入的新错法的唯一护栏。
     *
     * @throws ApiException 500 密文无法用当前密钥解开，或传入的是打码后的对外配置
     */
    public static StaticCredentials staticCredentials(StorageConfigInfo info, CredentialCipher cipher) {
        if (!hasStaticCredentials(info)) {
            return null;
        }
        if (isMasked(accessKeyIdOf(info))) {
            throw new ApiException(500, null, null,
                    "refusing to vend credentials: the config carries the masking placeholder \""
                            + MASKED + "\" instead of a real accessKeyId, so it is the masked copy"
                            + " meant for API responses, not the stored one");
        }
        return new StaticCredentials(accessKeyIdOf(info), cipher.reveal(secretAccessKeyOf(info)));
    }

    /**
     * 抹掉密文、给 {@code accessKeyId} 打码，供对外响应使用。
     *
     * <p><b>这是唯一一处打码。</b>列表与详情都走这里（{@code ManagementCatalogService#toDto}
     * 只有一个出口），因此两边不会出现「列表给六个星号、详情给真值」这种
     * 让人以为是 bug 的不一致；反过来，任何新加的响应路径也必须经过本方法，
     * 否则打码就漏在那个路径上。
     *
     * <p>打码而不是省略：省略了调用方就分不清「没配静态凭据」与「配了但看不到」，
     * 而这两种情况在 {@code PUT} 里的含义不同（前者什么都不给，后者要回一个哨兵）。
     * 取值见 {@link #MASKED}——它是 {@link #mergeStaticCredentials} 认的「未修改」。
     *
     * <p>密钥本身一律不回显：它一旦能被读出来，加密落库就只剩「防备份外泄」这一层意义了。
     */
    public static StorageConfigInfo withoutSecrets(StorageConfigInfo info) {
        if (!supportsStaticCredentials(info)) {
            return info;
        }
        String id = accessKeyIdOf(info);
        return withCredentials(info, isBlank(id) ? id : MASKED, null);
    }

    /**
     * 把请求里的静态凭据与库中已有的合并成可落库的形态。
     *
     * <p><b>为什么这里不能照搬 {@code PUT} 的整体替换语义。</b>规格的 {@code PUT}
     * 对未给出的字段一律置空（见 {@code StorageConfigApiTests} 的
     * {@code updatingStorageReplacesTheWholeConfiguration}），这条规则对
     * {@code secretAccessKey} 是不成立的：它只写不读，调用方**拿不到旧值回填**，
     * 按整体替换处理就等于「每次改 endpoint 都得重新输一遍密钥」，
     * 而更糟的是别人用 SDK 做读-改-写时会静默把密钥清空、直到引擎读写数据才报错。
     * 因此密钥是整体替换的唯一例外，规则如下：
     *
     * <ul>
     *   <li>两个字段都没给（{@code null}）→ 保持库中原值；
     *   <li>{@code accessKeyId} 是打码值 {@link #MASKED} → 视为「未修改」：库里有就用库里的
     *       （同时给了密钥则只换密钥，见下一条）；库里没有则 400，因为此时
     *       {@code ******} 没有可指向的原值，「保持」无从谈起；
     *   <li>两个字段都给了且都非空 → 换新（{@code accessKeyId} 与旧值相同也照换，
     *       此时 {@code secretAccessKey} 必须一起给，避免「只换 id 不换密钥」的半份配置）；
     *   <li>两个字段都给了且都是空串 → 清除静态凭据，回到服务端配置的取密钥路径；
     *   <li>只给一个 → 400。读-改-写回显旧 {@code accessKeyId} 是唯一的例外：
     *       它与库中一致时不要求同时给密钥（掩码上线前发出的报文仍按这个口径收）。
     * </ul>
     *
     * <p><b>{@code MASKED} 与「空串」的区别要守住。</b>打码是「这个值有、但我不告诉你」，
     * 空串是「这里不要值了」。因此 {@code ******} + 空密钥不按清除处理（两个字段各自都
     * 表示「不变」，组合起来只能读成保持）；要清除必须显式提交一对空串——控制台的
     * 「移除已保存的静态凭据」正是这么发的。若把两者混为一谈，「用户把表单里的密钥
     * 留空」就会静默删掉一份还能用的凭据。
     *
     * <p>存储类型换了（例如 S3 → GCS）时凭据不迁移：AK/SK 是签发到具体云厂商与
     * 具体密钥空间的，跨类型沿用只会让另一家云的密钥留在配置里。
     *
     * @param requested 本次请求里的配置
     * @param existing  库中已有的配置，新建时为 {@code null}
     */
    public static StorageConfigInfo mergeStaticCredentials(StorageConfigInfo requested,
                                                           StorageConfigInfo existing,
                                                           CredentialCipher cipher) {
        if (!supportsStaticCredentials(requested)) {
            return requested;
        }
        String givenId = accessKeyIdOf(requested);
        String givenSecret = secretAccessKeyOf(requested);
        boolean sameType = existing != null && requested.storageType() == existing.storageType();
        String storedId = sameType ? accessKeyIdOf(existing) : null;
        String storedSecret = sameType ? secretAccessKeyOf(existing) : null;

        if (isMasked(givenId)) {
            // 送到这里的 ****** 必须能在库里找到对应的那一把钥匙，否则存下去就是
            // 「AK 是六个星号、SK 还是旧的」——错位要到引擎第一次签名才暴露
            if (isBlank(storedId)) {
                throw ApiException.badRequest("storageConfigInfo.accessKeyId is \"" + MASKED
                        + "\", the placeholder the API returns for a stored key, but this catalog"
                        + " has no stored static credentials of that storage type; supply the real"
                        + " accessKeyId");
            }
            if (MASKED.equals(givenSecret == null ? null : givenSecret.trim())) {
                throw ApiException.badRequest("storageConfigInfo.secretAccessKey must not be the"
                        + " masking placeholder: it is never returned by the API, so it must be"
                        + " re-supplied whenever the key changes");
            }
            // 密钥留空 = 连密钥一起保持；给了新密钥 = 只换密钥、沿用库里的 AK ID
            String secret = isBlank(givenSecret) ? storedSecret : cipher.seal(givenSecret.trim());
            return withCredentials(requested, storedId, secret);
        }

        if (givenId == null && givenSecret == null) {
            return withCredentials(requested, storedId, storedSecret);
        }
        if (givenId != null && givenSecret != null) {
            if (isBlank(givenId) && isBlank(givenSecret)) {
                return withCredentials(requested, null, null);
            }
            if (!isBlank(givenId) && !isBlank(givenSecret)) {
                return withCredentials(requested, givenId.trim(), cipher.seal(givenSecret.trim()));
            }
            throw ApiException.badRequest("storageConfigInfo.accessKeyId and secretAccessKey must be"
                    + " provided together: both filled to set, both empty to remove");
        }
        // 只给了一个：允许「原样回显已有的 accessKeyId」，其余一律拒绝
        if (givenId != null && givenSecret == null && !isBlank(givenId)
                && givenId.trim().equals(storedId)) {
            return withCredentials(requested, storedId, storedSecret);
        }
        throw ApiException.badRequest("storageConfigInfo.secretAccessKey is required when accessKeyId"
                + " is set or changed; it is never returned by the API, so it must be re-supplied"
                + " whenever the key changes");
    }

    /** 是否为对外那份发出的打码值（容忍首尾空白：调用方常把整个值 {@code trim()} 后再回传）。 */
    private static boolean isMasked(String value) {
        return value != null && MASKED.equals(value.trim());
    }

    private static String accessKeyIdOf(StorageConfigInfo info) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return s3.accessKeyId();
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return obs.accessKeyId();
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return oss.accessKeyId();
        }
        if (info instanceof TencentCosStorageConfigInfo cos) {
            return cos.accessKeyId();
        }
        return null;
    }

    private static String secretAccessKeyOf(StorageConfigInfo info) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return s3.secretAccessKey();
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return obs.secretAccessKey();
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return oss.secretAccessKey();
        }
        if (info instanceof TencentCosStorageConfigInfo cos) {
            return cos.secretAccessKey();
        }
        return null;
    }

    /**
     * 换掉静态凭据、保留其余字段。
     *
     * <p>与 {@link #copyWith} 同样写成分类型的分支：字段增删时编译期报错，
     * 而反射写法会静默漏字段。无静态凭据字段的类型原样返回。
     */
    private static StorageConfigInfo withCredentials(StorageConfigInfo info,
                                                     String accessKeyId,
                                                     String secretAccessKey) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return new AwsStorageConfigInfo(s3.allowedLocations(), s3.storageName(),
                    s3.roleArn(), s3.externalId(), s3.userArn(),
                    s3.encryptionKeys(), s3.decryptionKeys(), s3.region(),
                    s3.endpoint(), s3.stsEndpoint(), s3.stsUnavailable(),
                    s3.endpointInternal(), s3.pathStyleAccess(), s3.kmsUnavailable(),
                    accessKeyId, secretAccessKey);
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return new HuaweiObsStorageConfigInfo(obs.allowedLocations(), obs.storageName(),
                    obs.endpoint(), obs.stsUnavailable(), accessKeyId, secretAccessKey);
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return new AliyunOssStorageConfigInfo(oss.allowedLocations(), oss.storageName(),
                    oss.endpoint(), oss.stsUnavailable(), accessKeyId, secretAccessKey);
        }
        if (info instanceof TencentCosStorageConfigInfo cos) {
            return new TencentCosStorageConfigInfo(cos.allowedLocations(), cos.storageName(),
                    cos.endpoint(), cos.bucketRegion(), cos.appId(), cos.useHttps(),
                    cos.stsUnavailable(), accessKeyId, secretAccessKey);
        }
        return info;
    }

    private static void requireLocationsNotBlank(StorageConfigInfo info) {
        List<String> locations = info.allowedLocations();
        if (locations == null) {
            return;
        }
        for (String location : locations) {
            if (location == null || location.isBlank()) {
                throw ApiException.badRequest(
                        "storageConfigInfo.allowedLocations must not contain blank entries");
            }
        }
    }

    /**
     * 换掉共有字段、保留类型专属字段。
     *
     * <p>写成分类型的分支而不是反射或 Map 改写：字段一旦增删，
     * 这里会编译期报错，而反射写法会静默漏字段。
     */
    private static StorageConfigInfo copyWith(StorageConfigInfo info,
                                              List<String> locations,
                                              String storageName) {
        if (info instanceof AwsStorageConfigInfo s3) {
            return new AwsStorageConfigInfo(locations, storageName,
                    s3.roleArn(), s3.externalId(), s3.userArn(),
                    s3.encryptionKeys(), s3.decryptionKeys(), s3.region(),
                    s3.endpoint(), s3.stsEndpoint(), s3.stsUnavailable(),
                    s3.endpointInternal(), s3.pathStyleAccess(), s3.kmsUnavailable(),
                    s3.accessKeyId(), s3.secretAccessKey());
        }
        if (info instanceof AzureStorageConfigInfo azure) {
            return new AzureStorageConfigInfo(locations, storageName,
                    azure.tenantId(), azure.multiTenantAppName(),
                    azure.consentUrl(), azure.hierarchical());
        }
        if (info instanceof GcpStorageConfigInfo gcs) {
            return new GcpStorageConfigInfo(locations, storageName, gcs.gcsServiceAccount());
        }
        if (info instanceof HuaweiObsStorageConfigInfo obs) {
            return new HuaweiObsStorageConfigInfo(locations, storageName,
                    obs.endpoint(), obs.stsUnavailable(), obs.accessKeyId(), obs.secretAccessKey());
        }
        if (info instanceof AliyunOssStorageConfigInfo oss) {
            return new AliyunOssStorageConfigInfo(locations, storageName,
                    oss.endpoint(), oss.stsUnavailable(), oss.accessKeyId(), oss.secretAccessKey());
        }
        if (info instanceof TencentCosStorageConfigInfo cos) {
            return new TencentCosStorageConfigInfo(locations, storageName,
                    cos.endpoint(), cos.bucketRegion(), cos.appId(), cos.useHttps(),
                    cos.stsUnavailable(), cos.accessKeyId(), cos.secretAccessKey());
        }
        if (info instanceof FileStorageConfigInfo) {
            return new FileStorageConfigInfo(locations, storageName);
        }
        throw ApiException.badRequest("Unsupported storageConfigInfo implementation: " + info.getClass());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
