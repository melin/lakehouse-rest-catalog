package io.github.melin.lakehouse.rest.service.exec;

import io.github.melin.lakehouse.rest.support.ProbeReason;

/**
 * 探活/提交失败时**带结论**的异常：一个短因码 + 一句处置。
 *
 * <p>为什么不用 {@code IllegalStateException(msg)}：探活的产物是三样东西
 * （列表上的短因、横幅里的一句话、日志里的栈），只有一个 message 的位置时装不下。
 * 有了这个类型，{@link ExecutionClusterProbeService} 就能把「是什么错」与
 * 「该去改哪里」分开填，而不必在 catch 里拼字符串。
 *
 * <p>处置（{@code fix}）是**动作**不是复述：写「把集群 CA 填进 caPem」，
 * 不写「TLS 握手失败」——后者已经在 message 里了。
 */
public class ExecutionProbeException extends RuntimeException {

    private final ProbeReason reason;

    private final String fix;

    public ExecutionProbeException(ProbeReason reason, String message, String fix, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.fix = fix;
    }

    public static ExecutionProbeException of(ProbeReason reason, String message, String fix) {
        return new ExecutionProbeException(reason, message, fix, null);
    }

    public ProbeReason reason() {
        return reason;
    }

    public String fix() {
        return fix;
    }

    /**
     * 把任意异常归类到 {@link ProbeReason}，并补一句可执行的处置。
     *
     * <p>分类靠**异常链里的类名与消息**，不靠状态码：fabric8 把 HTTP 错误包成
     * {@code KubernetesClientException}（消息里带状态码与响应体），而 TLS 与网络错误
     * 一路是 {@code SSLHandshakeException} / {@code UnknownHostException} / {@code HttpTimeoutException}。
     * 这条判据在探活与提交两处共用，避免两边各写一份「什么算权限不足」。
     */
    public static ExecutionProbeException classify(Throwable failure, String action) {
        Throwable root = rootOf(failure);
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        String chain = (failure.getClass().getName() + " " + root.getClass().getName() + " " + message).toLowerCase();
        String detail = action + "：" + message;

        if (chain.contains("sslhandshake") || chain.contains("certpath") || chain.contains("pkix")
                || chain.contains("certificateexception") || chain.contains("unknown authority")) {
            return new ExecutionProbeException(ProbeReason.TLS, detail,
                    "目标 apiserver 用的是自签 CA：把集群 CA 的 PEM 填进「集群 CA」（EKS 取自 "
                            + "aws eks describe-cluster --query cluster.certificateAuthority.data，"
                            + "kubeconfig 场景改用 certificate-authority-data）", failure);
        }
        if (chain.contains("unknownhost") || chain.contains("connectexception")
                || chain.contains("timeout") || chain.contains("goaway")
                || chain.contains("connection reset") || chain.contains("no route")) {
            return new ExecutionProbeException(ProbeReason.NETWORK, detail,
                    "确认服务端能出网到这个地址（DNS、安全组、代理）；GOAWAY / 连接被重置时先查中间代理",
                    failure);
        }
        if (chain.contains("unauthorized") || chain.contains(" 401") || chain.contains("401 ")) {
            return new ExecutionProbeException(ProbeReason.CREDENTIAL_INVALID, detail,
                    "身份没有被 apiserver 认可：EKS 先确认集群名大小写（它进 x-k8s-aws-id 头），"
                            + "AKS 先确认 scope 是 AKS 的 audience", failure);
        }
        if (chain.contains("forbidden") || chain.contains(" 403") || chain.contains("403 ")) {
            return new ExecutionProbeException(ProbeReason.FORBIDDEN, detail,
                    "补 RBAC：namespaces/" + "<ns>" + " 上对 jobs 的 create/delete/get 与 pods、pods/log 的 get",
                    failure);
        }
        return new ExecutionProbeException(ProbeReason.UNKNOWN, detail, "查看服务端日志里的完整栈", failure);
    }

    private static Throwable rootOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
