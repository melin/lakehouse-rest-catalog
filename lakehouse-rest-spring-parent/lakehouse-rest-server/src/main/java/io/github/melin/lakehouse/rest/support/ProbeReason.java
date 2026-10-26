package io.github.melin.lakehouse.rest.support;

/**
 * 探活失败的**短因**：落库到 {@code probe_reason}，列表 chip 与横幅都读它。
 *
 * <p>为什么要有这一层枚举而不是直接把异常 message 放进列表：列表一列只有几十像素，
 * 要的是「凭据失效」这种一眼能归类的词；完整的一句话在 {@code probe_message} 里，
 * 原始异常栈只进日志。三类信息三种去处，混在一起的结果是界面上堆着
 * {@code java.net.http.HttpConnectTimeoutException: HTTP connect timed out}。
 */
public enum ProbeReason {

    /** 云厂商侧的凭据本身不认了：{@code InvalidClientTokenId}、{@code invalid_client}、过期。 */
    CREDENTIAL_INVALID("凭据失效"),

    /** 本地这份凭据解不开或根本没配：换了加密密钥、没配 credential-secret-key、kubeconfig 空。 */
    CREDENTIAL_UNAVAILABLE("凭据不可用"),

    /** 认证过了但没权限：401 之后的 403、RBAC 不足、AAD 应用没绑集群角色。 */
    FORBIDDEN("权限不足"),

    /** TLS 层就不通：自签 CA 没填、证书主机名不匹配、代理中间人。 */
    TLS("TLS/证书"),

    /** 连不上：DNS、超时、拒绝连接、GOAWAY。 */
    NETWORK("网络不通"),

    /** 准入链路拒绝：配额、PodSecurity、SA 不存在、镜像策略。 */
    ADMISSION("准入预检失败"),

    /** 回调地址从服务端不可达（它只说明了一个方向）。 */
    CALLBACK("回调不可达"),

    /** 兜底：认不出的失败。 */
    UNKNOWN("未知原因");

    private final String label;

    ProbeReason(String label) {
        this.label = label;
    }

    /** 列表 chip 与横幅上的两三个字。 */
    public String label() {
        return label;
    }
}
