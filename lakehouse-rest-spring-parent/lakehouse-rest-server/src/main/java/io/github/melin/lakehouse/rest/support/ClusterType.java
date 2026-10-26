package io.github.melin.lakehouse.rest.support;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * 执行集群的类型，也就是**服务端用什么身份向这个集群提交 Job**。
 *
 * <p><b>类型与认证方式是封闭的一对一</b>，不存在「EKS + kubeconfig」这种组合：
 * EKS 的 kubeconfig 里标准形态就是 {@code exec: aws eks get-token}，而服务端不能替
 * 调用方执行本地命令（那是 RCE，见 {@code docs/catalog-execution-cluster-design.md} §3.1），
 * 所以这类 kubeconfig 只能被拒；而「EKS + 静态令牌」在 AWS 侧也没有对应的签发方式。
 * 把组合封死，让「配置自相矛盾」在表单与接口两层都不可能发生。
 *
 * <p>只有三种取值。加第四种（GKE）是加一个 case：表结构只存字面量，不需要迁移；
 * 但本仓没有第二个云的真实环境可验，不写「照抄能编译、真跑没验过」的实现。
 */
public enum ClusterType {

    /**
     * 自建 / 任何「手里有一份 kubeconfig」的集群。
     *
     * <p>不提供「手填 apiServer + token + CA」三个框：那三样恰好就是 kubeconfig 里的
     * {@code clusters[].server} / {@code users[].token} / {@code clusters[].certificate-authority-data}，
     * 让人分三个框手抄一遍，唯一的产出是抄错。
     */
    SELF_HOSTED("自建 / 托管 · kubeconfig", AuthMode.KUBECONFIG),

    /** AWS EKS：STS 预签名令牌（15 分钟有效），服务端每次请求现签。 */
    EKS("AWS EKS · 令牌", AuthMode.EKS_TOKEN),

    /** Azure AKS：AAD 客户端凭据换令牌，scope 是 AKS 自己的 audience。 */
    AKS("Azure AKS · 令牌", AuthMode.AKS_TOKEN);

    /** 这一种类型用哪种方式证明身份。UI 的第二段就是按它整段替换的。 */
    public enum AuthMode {
        KUBECONFIG,
        EKS_TOKEN,
        AKS_TOKEN
    }

    private final String label;

    private final AuthMode authMode;

    ClusterType(String label, AuthMode authMode) {
        this.label = label;
        this.authMode = authMode;
    }

    /** 界面上的中文名（也用作错误文案里的说法）。 */
    public String label() {
        return label;
    }

    public AuthMode authMode() {
        return authMode;
    }

    /** 列表列里的简称：`自建` / `EKS` / `AKS`。 */
    public String shortName() {
        return switch (this) {
            case SELF_HOSTED -> "自建";
            case EKS -> "EKS";
            case AKS -> "AKS";
        };
    }

    /** 列里存的、管理 API 里传的字面量（大写，与列注释一致）。 */
    public String wireName() {
        return name();
    }

    /** 按字面量解析，未知取值返回空。 */
    public static Optional<ClusterType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(type -> type.name().equals(normalized))
                .findFirst();
    }

    /**
     * 读库里的取值，认不出来时回落到 {@link #SELF_HOSTED}。
     *
     * <p>回落而不是抛：这个值可能是运维直接改库写进去的，而「按 kubeconfig 解释」
     * 是唯一不需要凭据里额外字段的一侧——认错的下场只是探活失败，不会把凭据送到
     * 一个不该去的地址。写入侧的校验在 {@link #parse} 上做。
     */
    public static ClusterType of(String value) {
        return parse(value).orElse(SELF_HOSTED);
    }
}
