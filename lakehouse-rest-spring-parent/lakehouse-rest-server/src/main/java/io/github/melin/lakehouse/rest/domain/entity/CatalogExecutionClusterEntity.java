package io.github.melin.lakehouse.rest.domain.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * 一个 catalog 的**执行集群**配置：优化任务提交到哪个 k8s、用什么身份跑。
 *
 * <p><b>为什么是独立一张表而不是往 {@code lakehouse_catalog} 上加十几列。</b>
 * catalog 是**协议面**的实体，被 Paimon / Iceberg 两套协议与 Spark / Flink 客户端共用；
 * 往里加 k8s 凭据，等于让「读一张表的元数据」与「服务端能不能往某个集群提交 Job」
 * 耦合在一起。而这一份配置是可选的（很多部署根本不用优化功能），
 * 一行一 catalog、随 catalog 级联删除，用外键说清楚比用十几个可空列说清楚更好。
 *
 * <p><b>三处刻意的取舍。</b>
 * <ol>
 *   <li>凭据列全部 {@code _sealed} 后缀：一眼能看出「这列不是明文」，读写都经
 *       {@code CredentialCipher}。非密文字段（{@code apiServer} / {@code namespace} / …）
 *       是运维要能在库里直接看清的东西，加密它们只会增加排障成本。</li>
 *   <li>探活结果放同一行而不是单独一张历史表：界面上只用「最近一次」，
 *       留一张只有写入没有读取的表没有收益（要趋势是 metrics 的事）。</li>
 *   <li>{@code config_hash} 与 {@code probe_config_hash} 成对出现：
 *       它们是「改完配置没验证」这件事能在列表上被看见的唯一依据。没有它，
 *       改错配置与没改配置在界面上长得一模一样。</li>
 * </ol>
 */
@Entity
@Table(name = "lakehouse_catalog_execution_cluster",
        uniqueConstraints = @UniqueConstraint(name = "uk_exec_cluster_catalog", columnNames = "catalog_id"),
        comment = "catalog 的执行集群配置（优化任务提交到哪里、用什么身份），一行一 catalog")
@Getter
@Setter
public class CatalogExecutionClusterEntity extends AuditedEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false, comment = "主键")
    private String id;

    /**
     * 所属 catalog 的内部 id（{@code lakehouse_catalog.id}）。
     *
     * <p>不是 prefix：prefix 可被改（本仓虽不允许，但库层没有约束），而执行集群的引用
     * 一旦跟着改名漂移就会指向另一份配置。唯一约束保证 1:1。
     */
    @Column(name = "catalog_id", length = 64, nullable = false,
            comment = "所属 catalog 的内部 id，一行一 catalog（唯一约束）")
    private String catalogId;

    /** 停用而不是删除：重新启用只要一次点击，删除后要把凭据全填一遍。 */
    @Column(name = "enabled", nullable = false, comment = "是否启用；停用即该 catalog 不跑优化，配置保留")
    private boolean enabled = true;

    /** {@code SELF_HOSTED} / {@code EKS} / {@code AKS}，见 {@code ClusterType}。 */
    @Column(name = "cluster_type", length = 16, nullable = false,
            comment = "集群类型 SELF_HOSTED / EKS / AKS，决定用哪种认证")
    private String clusterType = "SELF_HOSTED";

    /** 仅 SELF_HOSTED：选中 kubeconfig 里的哪个 context。 */
    @Column(name = "context_name", length = 128, comment = "kubeconfig 里选中的 context（仅 SELF_HOSTED）")
    private String contextName;

    /**
     * apiserver 地址。
     *
     * <p>SELF_HOSTED 时它是**从 kubeconfig 里解析出来的投影**（保存时写入，读回来用于展示与
     * 列表副行），EKS / AKS 时是用户填的。两种来源都落这一列，是因为「这个 catalog 提交到
     * 哪个地址」是列表要显示的信息，不该为了它去解密 kubeconfig。
     */
    @Column(name = "api_server", length = 255, nullable = false, comment = "apiserver 地址（SELF_HOSTED 时是 kubeconfig 的投影）")
    private String apiServer;

    /**
     * 集群 CA（PEM 文本）。
     *
     * <p>是**内容**不是路径：服务端读不到调用方机器上的文件。留空表示用 JVM 信任库
     * （公共 CA 签发的 apiserver 证书，或自签但已导入信任库的部署）。
     */
    @Column(name = "ca_cert_pem", columnDefinition = "text",
            comment = "集群 CA 的 PEM 文本；空 = 用 JVM 信任库")
    private String caCertPem;

    /** kubeconfig 原文（密封）。SELF_HOSTED 用。 */
    @Column(name = "kubeconfig_sealed", columnDefinition = "text",
            comment = "kubeconfig 原文（CredentialCipher 密封），仅 SELF_HOSTED")
    private String kubeconfigSealed;

    /**
     * EKS / AKS 的认证配置（密封的 JSON）。
     *
     * <p>放一个 JSON 列而不是给两家各开几列：两家的字段集合没有交集
     * （{@code region/clusterName/accessKeyId/secretAccessKey/sessionToken}
     * 与 {@code authority/tenantId/clientId/clientSecret/scope}），拆成列会得到一堆
     * 恒为 null 的列，且加一种云就要迁移一次表。
     */
    @Column(name = "auth_config_sealed", columnDefinition = "text",
            comment = "EKS / AKS 认证配置的 JSON（CredentialCipher 密封）")
    private String authConfigSealed;

    /** 提交 Job 的目标命名空间。 */
    @Column(name = "namespace", length = 63, nullable = false, comment = "提交 Job 的命名空间")
    private String namespace;

    /**
     * 优化任务镜像。
     *
     * <p>必填且没有部署级默认值：集群可以多个，镜像只可能有一个「对的那个」。
     * 给个默认值只会让「填错集群的镜像」更难发现——表现是 {@code ErrImagePull}，
     * 看起来像网络问题。
     */
    @Column(name = "image", length = 255, nullable = false, comment = "优化任务镜像（含 Spark 运行时）")
    private String image;

    @Column(name = "image_pull_policy", length = 16, nullable = false,
            comment = "镜像拉取策略 IfNotPresent / Always / Never")
    private String imagePullPolicy = "IfNotPresent";

    /** 逗号分隔的 Secret 名；私有仓库才需要，漏了的表现是 {@code ErrImagePull}。 */
    @Column(name = "image_pull_secrets", length = 255,
            comment = "imagePullSecrets，逗号分隔的 Secret 名")
    private String imagePullSecrets;

    /** 运行优化任务的 service account；空 = 命名空间默认账号。IRSA / Workload Identity 挂在这里。 */
    @Column(name = "service_account", length = 63, comment = "Pod 的 serviceAccountName；空 = 命名空间默认")
    private String serviceAccount;

    /**
     * 下发给任务的服务端地址（任务用它回调拉清单、上报结果）。
     *
     * <p>逐 catalog 配置而不是全局：同一个服务端在不同集群里的可达地址不同
     * （集群内 Service 名 / 内网 LB / 对外域名）。它是**反向可达**的地址，
     * 填错最难查，因此**没有默认值**：每份配置都必须由配置它的人自己填。
     */
    @Column(name = "callback_base_url", length = 255, nullable = false,
            comment = "任务回调服务端的基址（集群里的 Pod 能访问到的地址）")
    private String callbackBaseUrl;

    /** 下发给任务的 catalog 数据面令牌（密封）。与批次回调令牌作用域不同。 */
    @Column(name = "catalog_token_sealed", columnDefinition = "text",
            comment = "下发给任务的 catalog 数据面令牌（CredentialCipher 密封）")
    private String catalogTokenSealed;

    /** 单批最多几张表。超过拆到下一批——跟着集群算力走，所以逐 catalog 配置。 */
    @Column(name = "max_tables_per_job", nullable = false, comment = "单批最多几张表")
    private int maxTablesPerJob = 20;

    /** 同时允许几个批次在跑。超出的留在 PENDING。 */
    @Column(name = "max_concurrent_jobs", nullable = false, comment = "该 catalog 同时运行几个批次")
    private int maxConcurrentJobs = 2;

    /** 批次超时（秒）。超过它仍未终结的批次判超时并清理执行侧资源。 */
    @Column(name = "job_timeout_seconds", nullable = false, comment = "批次超时秒数")
    private int jobTimeoutSeconds = 7200;

    /**
     * 「参与连接与运行的全部字段」的 SHA-256（十六进制）。
     *
     * <p>不含批次策略与探活时间戳：改批次上限不需要重新验证连接，把它算进来会让
     * 每次点保存都掉成「待验证」，那条提示很快就没人看了。
     *
     * <p>密文字段取的是**密文本身**：同一份明文每次加密的 IV 不同，用它算 hash 会
     * 「什么都没改却掉成待验证」——因此 {@code update} 在明文未变时沿用原文的密文
     * （见 {@code ExecutionClusterService}）。
     */
    @Column(name = "config_hash", length = 64, nullable = false, comment = "连接与运行字段的 SHA-256，用于判定「改完没验证」")
    private String configHash;

    /** 最近一次探活结果：{@code PASS} / {@code FAIL}；{@code null} = 从未探过。 */
    @Column(name = "probe_status", length = 8, comment = "最近一次探活 PASS / FAIL；空 = 从未探过")
    private String probeStatus;

    /** 失败的短因（列表 chip 与横幅用）：{@code CREDENTIAL_INVALID} / {@code FORBIDDEN} / … */
    @Column(name = "probe_reason", length = 32, comment = "探活失败的短因码")
    private String probeReason;

    /** 一句话结论（列表 tooltip 与横幅用）。不存原始响应体：里面可能有集群版本、账号 id。 */
    @Column(name = "probe_message", length = 512, comment = "探活结论（一句话，不含原始响应体）")
    private String probeMessage;

    /** 探活时的 {@code configHash}。与当前值不等即「改完配置没验证」。 */
    @Column(name = "probe_config_hash", length = 64, comment = "探活时的 configHash")
    private String probeConfigHash;

    @Column(name = "probed_at", comment = "最近一次探活时刻（epoch 毫秒）")
    private Long probedAt;

    /** 乐观并发版本号，对应管理 API 的 {@code entityVersion}。 */
    @Column(name = "entity_version", nullable = false, comment = "乐观并发版本号，对应管理 API 的 entityVersion")
    private int entityVersion;
}
