package io.github.melin.lakehouse.rest.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * 存储配置 DTO，对应管理规格的 {@code StorageConfigInfo} 及其四个实现。
 *
 * <p><b>为什么用判别联合而不是一个大对象。</b>规格把
 * {@code StorageConfigInfo} 声明为带 {@code discriminator} 的多态基类，
 * {@code storageType} 决定其余字段属于哪个子类型：
 * {@code S3} → {@code AwsStorageConfigInfo}、{@code AZURE} → {@code AzureStorageConfigInfo}、
 * {@code GCS} → {@code GcpStorageConfigInfo}、{@code FILE} → {@code FileStorageConfigInfo}。
 * 若把它们拍平成一个「所有字段都可空」的 record，就会出现
 * 「{@code storageType=GCS} 却带着 {@code roleArn}」这类规格不允许的状态，
 * 而且 {@code AZURE} 必填 {@code tenantId} 这样的约束无处安放。
 * 这里按子类型建模，非法组合在类型层面就不成立。
 *
 * <p><b>判别字段不在子类型里。</b>{@code storageType} 由 Jackson 的类型信息机制读写
 * （{@code include = As.PROPERTY}），因此子类型 record 刻意不含该字段：
 * 序列化时由 Jackson 写入，反序列化时被 Jackson 消费，避免出现重复属性。
 * 子类型注册名见 {@link #WIRE_S3} 等常量，必须与
 * {@link ManagementEnums.StorageType} 的对外取值一致，由
 * {@code StorageConfigDtosTests} 断言守住，防止改名时两侧漂移。
 *
 * <p><b>两个非规格子类型。</b>{@code OBS} 与 {@code OSS} 不在 Polaris 规格的
 * {@code discriminator.mapping} 里，是本工程为华为云 OBS 与阿里云 OSS 加的扩展，
 * 理由见 {@link ManagementEnums.StorageType}。它们的字段只接收凭据下发真正要用的
 * 定位信息（{@code endpoint}）与<b>这个 catalog 自己的凭据</b>，后者见下一段。
 *
 * <p><b>凭据是本工程加的扩展字段，而且这里是凭据唯一的存放处。</b>规格里
 * {@code S3} / {@code OBS} / {@code OSS} 三种子类型都没有「用户直接填写 AK/SK」的位置：
 * Polaris 只从服务端配置（{@code polaris.storage.aws.access-key} / 具名存储）取密钥。
 * 本工程把那一层删了，改为让**建 catalog 的人**直接指定该 catalog 用哪把钥匙，
 * 因为每个 catalog 的存储配置本来就不一样。各类型带的凭据字段：
 *
 * <ul>
 *   <li>{@code S3} / {@code OBS} / {@code OSS} / {@code COS}：{@code accessKeyId} + {@code secretAccessKey}；
 *   <li>{@code AZURE}：{@code clientId} + {@code clientSecret}（另有 {@code authority} / {@code scope}
 *       表达云环境，留空取公有云缺省）；
 *   <li>{@code GCS}：{@code serviceAccountJson}（服务账号密钥文件，服务端自签 JWT 换令牌）。
 * </ul>
 *
 * <p>读写语义与普通字段不同（由 {@code StorageConfigs} 与 {@code CredentialCipher} 实现）：
 * 密钥落库前加密、任何响应中都不回显且 id 打码成 {@code ******}，因此 {@code PUT} 省略
 * 密钥字段表示「保持不变」而不是「清空」（规格的 {@code PUT} 对其余字段是整体替换）。
 * {@code ******} 同时是回写哨兵：控制台每次提交都带着它，服务端据此沿用库里那份。
 *
 * <p><b>已废弃字段未建模。</b>{@code AwsStorageConfigInfo} 的 {@code currentKmsKey} 与
 * {@code allowedKmsKeys} 在规格里标了 {@code deprecated}，这里不接收也不返回，
 * 统一用 {@code encryptionKeys} / {@code decryptionKeys}。
 * 传入这两个字段会被 Jackson 静默忽略（Spring Boot 默认关闭
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}），不影响既有调用方。
 */
public final class StorageDtos {

    /** 子类型注册名：S3。 */
    static final String WIRE_S3 = "S3";

    /** 子类型注册名：Azure Blob Storage。 */
    static final String WIRE_AZURE = "AZURE";

    /** 子类型注册名：Google Cloud Storage。 */
    static final String WIRE_GCS = "GCS";

    /** 子类型注册名：华为云 OBS。非规格取值，本工程扩展。 */
    static final String WIRE_OBS = "OBS";

    /** 子类型注册名：阿里云 OSS。非规格取值，本工程扩展。 */
    static final String WIRE_OSS = "OSS";

    /** 子类型注册名：腾讯云 COS。非规格取值，本工程扩展。 */
    static final String WIRE_COS = "COS";

    /** 子类型注册名：本地文件系统。 */
    static final String WIRE_FILE = "FILE";

    private StorageDtos() {
    }

    /**
     * {@code StorageConfigInfo}：按 {@code storageType} 判别的存储配置基类。
     *
     * <p>{@code allowedLocations} 与 {@code storageName} 是所有子类型共有的字段，
     * 但 Java 的 record 不能继承字段，因此每个子类型各自声明一份。这是为换取
     * 「每种存储类型只暴露自己的字段」而付出的代价，由本类的测试守住一致性。
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.PROPERTY,
            property = "storageType")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AwsStorageConfigInfo.class, name = WIRE_S3),
            @JsonSubTypes.Type(value = AzureStorageConfigInfo.class, name = WIRE_AZURE),
            @JsonSubTypes.Type(value = GcpStorageConfigInfo.class, name = WIRE_GCS),
            @JsonSubTypes.Type(value = HuaweiObsStorageConfigInfo.class, name = WIRE_OBS),
            @JsonSubTypes.Type(value = AliyunOssStorageConfigInfo.class, name = WIRE_OSS),
            @JsonSubTypes.Type(value = TencentCosStorageConfigInfo.class, name = WIRE_COS),
            @JsonSubTypes.Type(value = FileStorageConfigInfo.class, name = WIRE_FILE)
    })
    public sealed interface StorageConfigInfo
            permits AwsStorageConfigInfo, AzureStorageConfigInfo, GcpStorageConfigInfo,
                    HuaweiObsStorageConfigInfo, AliyunOssStorageConfigInfo,
                    TencentCosStorageConfigInfo, FileStorageConfigInfo {

        /** 该 catalog 允许写入的位置白名单；首项同时用作仓库根。 */
        List<String> allowedLocations();

        /**
         * 人类可读的存储名，**只是标签**，不参与凭据解析。
         *
         * <p>它一度指向服务端配置里的具名存储（{@code storage.<云>.storages.<name>}）。
         * 那条路已经取消：凭据不再有全局配置那一份，只跟着 catalog 走。字段留着
         * 是为了两件事——已有的 catalog 里存着它，删掉会让人以为数据被改写；以及
         * 列表页要有一个「这是哪套桶」的短名可显示。
         *
         * <p>因此它不再与任何凭据字段互斥：填了名字又填了 AK/SK 是正常配置。
         */
        String storageName();

        /**
         * 该配置对应的存储类型。
         *
         * <p>标 {@link JsonIgnore} 是必须的：JSON 里的 {@code storageType} 由 Jackson
         * 的类型信息机制负责读写，若再把这个方法当普通属性序列化，报文里会出现两个
         * 同名字段。
         */
        @JsonIgnore
        ManagementEnums.StorageType storageType();
    }

    /**
     * {@code AwsStorageConfigInfo}：S3 或任何兼容 S3 协议的对象存储。
     *
     * <p>后两个字段是本工程的扩展（见类注释）：{@code accessKeyId} 可读写，
     * {@code secretAccessKey} 只写不读。它们与 {@code roleArn} 系列是两条互斥的路：
     * 前者是「用这把长期钥匙直接访问」，后者是「服务端去 assume 这个角色」，
     * 两者同时出现在一份配置里没有确定语义，由 {@code StorageConfigs} 拒绝。
     *
     * <p><b>{@code noInlinePolicy}（本工程扩展，对照设计稿 §3.5.3）。</b>VAST 等
     * S3 兼容实现的 STS **不接受** inline session policy，收到 {@code Policy} 参数直接回 501；
     * 为 {@code true} 时服务端 {@code AssumeRole} 不带 policy，代价是这张票的作用域
     * 退回角色自身权限（**按表收窄失效**）。
     *
     * <p>它是**显式开关、默认 {@code null}=带 policy**，而不是「S3 兼容就默认不带」。
     * 判据本来就不存在：MinIO / Ceph RGW / Ozone 与 VAST 在本工程里都是
     * {@code storageType=S3} 加一个自定义 {@code endpoint}，没有字段能把「接受 policy」
     * 与「拒绝 policy」分开。按「有 endpoint 就不带」去推测，会让所有**接受** policy 的
     * 自建存储静默失去收窄——那正是本项目反复警告的「看起来生效了、实际什么都没收窄」，
     * 而它与「真能跑」的表象完全一样。因此宁可让 VAST 的运维先吃一次 501、
     * 由控制台在那两个字段旁边直接写明这条退路，也不替全部自建存储默认关掉收窄。
     */
    public record AwsStorageConfigInfo(List<String> allowedLocations,
                                       String storageName,
                                       String roleArn,
                                       String externalId,
                                       String userArn,
                                       List<String> encryptionKeys,
                                       List<String> decryptionKeys,
                                       String region,
                                       String endpoint,
                                       String stsEndpoint,
                                       Boolean stsUnavailable,
                                       Boolean noInlinePolicy,
                                       String endpointInternal,
                                       Boolean pathStyleAccess,
                                       Boolean kmsUnavailable,
                                       String accessKeyId,
                                       String secretAccessKey)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.S3;
        }
    }

    /**
     * {@code AzureStorageConfigInfo}：Azure Blob Storage / ADLS Gen2。{@code tenantId} 必填。
     *
     * <p><b>后四个字段是本工程的扩展：服务主体 + 云环境。</b>规格里 Azure 配置只有
     * 「数据在哪个租户/账户」，凭据由服务进程自己的标识提供。本工程没有那层标识，
     * 签发 User Delegation SAS 必须有一个应用注册，于是把
     * {@code clientId} / {@code clientSecret} 放到 catalog 上——与 S3 的 AK/SK
     * 同一个位置、同一套加密与打码（{@code clientSecret} 只写不读）。
     *
     * <p>{@code authority} / {@code scope} 也放在这里而不是全局配置：它们必须与
     * 云环境一致（中国云是 {@code login.chinacloudapi.cn} 与
     * {@code storage.azure.cn}），而同一个服务端同时服务公有云与中国云的 catalog
     * 是正常部署形态。留空时取公有云的缺省值。
     */
    public record AzureStorageConfigInfo(List<String> allowedLocations,
                                         String storageName,
                                         String tenantId,
                                         String multiTenantAppName,
                                         String consentUrl,
                                         Boolean hierarchical,
                                         String clientId,
                                         String clientSecret,
                                         String authority,
                                         String scope)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.AZURE;
        }
    }

    /**
     * {@code GcpStorageConfigInfo}：Google Cloud Storage。
     *
     * <p><b>{@code serviceAccountJson} 是本工程的扩展</b>，形态是服务账号密钥文件的
     * 全文（{@code {"type":"service_account","client_email":...,"private_key":...}}）。
     * 服务端用它自签一份 JWT assertion 换访问令牌，再把令牌 downscope 成
     * 「只能读这张表」的令牌下发给引擎。
     *
     * <p>为什么是密钥文件而不是访问令牌：访问令牌一小时就过期，把它存进 catalog
     * 等于存了一份必然失效的配置，而失效现场是「昨天还能读，今天 401」。密钥文件
     * 没有这个问题。整个字段按密钥对待——加密落库、对外只回 {@code ******}。
     */
    public record GcpStorageConfigInfo(List<String> allowedLocations,
                                       String storageName,
                                       String gcsServiceAccount,
                                       String serviceAccountJson)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.GCS;
        }
    }

    /**
     * 华为云 OBS。**非规格子类型，本工程扩展**（见 {@link ManagementEnums.StorageType}）。
     *
     * <p>字段与 {@link AliyunOssStorageConfigInfo} 当前完全相同，但没有合并成一个
     * record：判别联合的成员身份由类型本身承担，合并后 {@code storageType()} 就无法
     * 自证；且两家云的 FileIO 配置面并不一致（OBS 有 {@code fs.obs.security.provider}
     * 凭据提供器，OSS 有服务端加密选项），后续任一侧新增字段时，合并的写法要被迫拆开。
     *
     * <p><b>没有 {@code region} 字段，是有意的。</b>华为云 OBSA 的配置表里不存在
     * {@code fs.obs.region} 这一项——区域信息已经包含在 {@code endpoint} 里
     * （形如 {@code obs.cn-north-4.myhuaweicloud.com}）。造一个下发后无人识别的键，
     * 比不给更容易误导：运维会以为填了就生效。
     */
    public record HuaweiObsStorageConfigInfo(List<String> allowedLocations,
                                             String storageName,
                                             String endpoint,
                                             Boolean stsUnavailable,
                                             String accessKeyId,
                                             String secretAccessKey)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.OBS;
        }
    }

    /**
     * 阿里云 OSS。**非规格子类型，本工程扩展**（见 {@link ManagementEnums.StorageType}）。
     *
     * <p>没有 {@code region} 字段的理由同 {@link HuaweiObsStorageConfigInfo}：
     * Hadoop 的 aliyun-oss 官方配置表里只有 {@code fs.oss.endpoint} 与两个密钥项，
     * 区域由 endpoint 表达（形如 {@code oss-cn-hangzhou.aliyuncs.com}）。
     *
     * <p><b>{@code roleArn} 与 {@code stsEndpoint} 是本工程的扩展</b>，落在 catalog
     * 而不是服务端配置上：一套部署里不同的 OSS catalog 属于不同的阿里云账号时，
     * 要 assume 的角色各不相同。语义与 S3 那一组一致——
     * {@code accessKeyId}/{@code secretAccessKey} 是**服务端据以行动的钥匙**，
     * {@code roleArn} 是**要换成的身份**；两者并存表示「用这把钥匙去 assume 这个角色」，
     * 只给钥匙表示「直接把钥匙转发给引擎」。{@code stsEndpoint} 留空取全球化端点。
     */
    public record AliyunOssStorageConfigInfo(List<String> allowedLocations,
                                             String storageName,
                                             String endpoint,
                                             Boolean stsUnavailable,
                                             String accessKeyId,
                                             String secretAccessKey,
                                             String roleArn,
                                             String stsEndpoint)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.OSS;
        }
    }

    /**
     * 腾讯云 COS。**非规格子类型，本工程扩展**（见 {@link ManagementEnums.StorageType}）。
     *
     * <p><b>它没有沿用 {@code AwsStorageConfigInfo} 的形状，尽管 COS 兼容 S3 协议。</b>
     * 本工程对接 COS 走的是 COSN（{@code cosn://} + {@code fs.cosn.*}），与
     * {@code s3://} + {@code s3.*} 是两条不同的 FileIO 实现路径；用一个
     * {@code storageType=S3} 加自定义 {@code endpoint} 去接，得到的是一份
     * 「服务端按 S3 键族下发、引擎按 COSN 键族读取」的配置——两边都不报错，
     * 引擎只是拿不到密钥。因此单列类型、单列字段。
     *
     * <p><b>字段取舍：</b>
     *
     * <ul>
     *   <li>{@code endpoint} 是 COSN 的 {@code fs.cosn.bucket.endpoint_suffix}
     *       （形如 {@code cos.ap-guangzhou.myqcloud.com}），<b>不是</b> S3 兼容端点。
     *       一个字段只表达一件事：同一个字符串若被两种实现各按自己的语义解读，
     *       配置读起来就是对的、跑起来才是错的。</li>
     *   <li>{@code bucketRegion} 对应 {@code fs.cosn.bucket.region}（形如
     *       {@code ap-guangzhou}）。COSN 的 {@code requiredOptions} 要求它与
     *       endpoint 至少给一个，所以两个都留。</li>
     *   <li>{@code appId} <b>不是下发键</b>——COS 的 appId 是桶名的一部分
     *       （{@code cosn://<bucket>-<appid>/<path>}），没有独立的配置项。
     *       留着它只有一个用途：写入口校验位置里的桶名确实属于这个 appId
     *       （见 {@code StorageConfigs}）。桶名写错时 COS 返回的是 404，
     *       与「密钥不对」的现象隔着一层。</li>
     *   <li>{@code useHttps} 为 {@code null} 时按 {@code true} 下发。COSN 自己的缺省是
     *       {@code fs.cosn.useHttps=false}（{@code CosNConfigKeys.DEFAULT_USE_HTTPS}），
     *       也就是不配就走明文——密钥、数据、全部走明文。这个默认值不适合作为
     *       本工程的下发默认值，而私有化部署的 COS 兼容实现可能确实只有 HTTP，
     *       所以留一个可以显式关掉的开关，而不是把默认值钉死。</li>
     *   <li><b>没有 {@code sessionToken}，也没有 {@code roleArn}。</b>
     *       paimon-cosn 2.1.0 内置的 hadoop-cos 只带 {@code SimpleCredentialsProvider}
     *       与 {@code EnvironmentVariableCredentialsProvider}，其
     *       {@code CosNConfigKeys} 里根本没有会话令牌这一项——发了也没有消费者。
     *       详见 {@code docs/credential-vending-design.md} 的 §6.3 勘误。</li>
     * </ul>
     */
    public record TencentCosStorageConfigInfo(List<String> allowedLocations,
                                              String storageName,
                                              String endpoint,
                                              String bucketRegion,
                                              String appId,
                                              Boolean useHttps,
                                              Boolean stsUnavailable,
                                              String accessKeyId,
                                              String secretAccessKey)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.COS;
        }
    }

    /** {@code FileStorageConfigInfo}：本地或已挂载文件系统，规格注明仅供测试。 */
    public record FileStorageConfigInfo(List<String> allowedLocations,
                                        String storageName)
            implements StorageConfigInfo {

        @Override
        public ManagementEnums.StorageType storageType() {
            return ManagementEnums.StorageType.FILE;
        }
    }
}
