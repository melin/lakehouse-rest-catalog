package io.github.melin.lakehouse.rest.dto;

import java.util.List;

/**
 * catalog 执行集群（优化任务提交到哪个 k8s、用什么身份）的契约。
 *
 * <p><b>凭据只进不出。</b>读接口永远不回 kubeconfig 原文、secretAccessKey、clientSecret、
 * catalogToken 的明文，只回 {@code has*} 布尔与可公开的标识（{@code accessKeyId}、
 * {@code tenantId}、{@code clientId}）——后者不保密，它们的用途是让人认出「配的是哪把钥匙」。
 * 这条规则与 {@code ManagementDtos.Catalog} 的 {@code withoutSecrets} 同一个方向：
 * 服务端能还原明文（它要下发给引擎/任务），但**不通过 API 交出去**。
 *
 * <p>{@code kubeconfigContexts} 是例外里的一小块：它由服务端解密 kubeconfig 后**重新解析**
 * 得到的 context 列表（名字、地址、认证方式、结论），不含任何密钥材料。
 * 没有它，「保存后想换个 context」就得让用户重新粘贴整段 kubeconfig。
 */
public final class ExecutionClusterDtos {

    private ExecutionClusterDtos() {
    }

    // ------------------------------------------------------------------ 读

    /**
     * {@code GET .../execution-cluster}。
     *
     * <p>{@code configured=false} 时除 {@code catalog} 与 {@code status} 之外的字段都无意义
     * （服务端此时回的是 404，这个形状只用于 {@code copy-from} 之类的内部复用）。
     */
    public record ExecutionCluster(String catalog,
                                   boolean configured,
                                   boolean enabled,
                                   String status,
                                   String statusReason,
                                   String clusterType,
                                   String contextName,
                                   String apiServer,
                                   String caCertPem,
                                   Kubeconfig kubeconfig,
                                   EksAuth eks,
                                   AksAuth aks,
                                   String namespace,
                                   String image,
                                   String imagePullPolicy,
                                   List<String> imagePullSecrets,
                                   String serviceAccount,
                                   String callbackBaseUrl,
                                   boolean hasCatalogToken,
                                   int maxTablesPerJob,
                                   int maxConcurrentJobs,
                                   int jobTimeoutSeconds,
                                   boolean credentialsReadable,
                                   String probeStatus,
                                   String probeReason,
                                   String probeMessage,
                                   Long probedAt,
                                   int entityVersion,
                                   Long createdAt,
                                   Long updatedAt) {
    }

    /**
     * kubeconfig 的**摘要**（SELF_HOSTED）。
     *
     * <p>{@code state}：{@code CONFIGURED}（有且能解）、{@code NONE}（没配）、
     * {@code UNREADABLE}（有密文但解不开——换了加密密钥，或本部署没配密钥）。
     * 第三种必须与「没配」区分开：前者要运维去恢复密钥，后者要用户去粘贴。
     */
    public record Kubeconfig(String state,
                             int contextCount,
                             List<Context> contexts,
                             List<String> warnings) {
    }

    /** 一个 context 的结论。{@code verdict}：{@code OK} / {@code WARN} / {@code UNSUPPORTED} / {@code UNAVAILABLE}。 */
    public record Context(String name,
                          String cluster,
                          String server,
                          String auth,
                          String verdict,
                          String note) {
    }

    /** EKS 的可公开部分 + 秘密是否存在。 */
    public record EksAuth(String region,
                          String clusterName,
                          String accessKeyId,
                          boolean hasSecretAccessKey,
                          boolean hasSessionToken) {
    }

    /** AKS 的可公开部分 + 秘密是否存在。 */
    public record AksAuth(String authority,
                          String tenantId,
                          String clientId,
                          boolean hasClientSecret,
                          String scope) {
    }

    // ------------------------------------------------------------------ 写

    /**
     * {@code PUT .../execution-cluster}：整份替换（含 {@code enabled}）。
     *
     * <p><b>留空 = 保持原样</b>（与静态凭据的 {@code caPem} 同一个约定）：
     * kubeconfig、secretAccessKey、clientSecret、catalogToken 传空表示「不改」。
     * 否则「打开抽屉什么都不改就保存」会把凭据清成空串——这类事故在静态凭据那块踩过一次。
     *
     * <p>要清空**只有一种情况需要开关**：{@code catalogToken} 是可选的（没开认证的部署
     * 本来就没有），所以「清掉它」是一个有意义的状态，用 {@code clearCatalogToken} 表达。
     * 另外四个秘密**没有清空开关，也不需要**：它们是这个类型下建客户端的必需材料，
     * 清掉之后这份配置连探活都跑不了（服务端会以 400 拒绝），
     * 因此「清空」在语义上等于「删掉整份配置」——那个动作用 {@code DELETE}。
     *
     * <p>{@code entityVersion} 是乐观锁：新建时不必传，更新时必须等于库里的当前值。
     */
    public record UpdateExecutionClusterRequest(Boolean enabled,
                                                String clusterType,
                                                String contextName,
                                                String kubeconfig,
                                                String caCertPem,
                                                String apiServer,
                                                String region,
                                                String clusterName,
                                                String accessKeyId,
                                                String secretAccessKey,
                                                String sessionToken,
                                                String authority,
                                                String tenantId,
                                                String clientId,
                                                String clientSecret,
                                                String scope,
                                                String namespace,
                                                String image,
                                                String imagePullPolicy,
                                                String imagePullSecrets,
                                                String serviceAccount,
                                                String callbackBaseUrl,
                                                String catalogToken,
                                                Integer maxTablesPerJob,
                                                Integer maxConcurrentJobs,
                                                Integer jobTimeoutSeconds,
                                                Boolean clearCatalogToken,
                                                Integer entityVersion) {
    }

    /** {@code POST .../kubeconfig/parse} 的请求。不落库，纯解析。 */
    public record ParseKubeconfigRequest(String kubeconfig) {
    }

    /** 解析结果：表单据此渲染「每行一个 context + 一行结论」，默认选中 {@code currentContext}。 */
    public record KubeconfigParseResult(List<Context> contexts,
                                        String currentContext,
                                        List<String> warnings) {
    }

    /**
     * {@code POST .../copy-from}：从另一个 catalog 复制连接与认证。
     *
     * <p><b>为什么单独要 {@code callbackBaseUrl}。</b>它是唯一一项**不能复制**的连接参数：
     * 它描述的是「任务所在的 Pod 能访问到哪个服务端地址」，方向与其余字段相反——
     * 抄一份别人的地址，症状是 Job 起来了、优化进程跑完了、结果一条都没回来，
     * 而探活第 5 步（只验服务端 → 地址那个方向）照样通过。因此它必须由调用方给。
     *
     * <p>不给就 400，而不是留空落库：留空的后果是这行配置「已存在但不可用」，
     * 而状态机只看 {@code enabled} 与探活结论，会把它算进「待验证」并允许派发。
     */
    public record CopyFromRequest(String sourceCatalog, String callbackBaseUrl) {
    }

    // ------------------------------------------------------------------ 探活

    /**
     * 一步探活的结论。{@code status}：{@code PASS} / {@code WARN} / {@code FAIL} / {@code SKIP}。
     *
     * <p>{@code detail} 放「做了什么、看到了什么」的一行摘要（如集群版本、ARN）；
     * {@code fix} 只在非 PASS 时出现，写的是处置而不是复述错误。
     */
    public record ProbeStep(String step,
                            String title,
                            String status,
                            String summary,
                            String fix,
                            String detail) {
    }

    /** 一次探活的结果，同时是「保存到库里的那一条」的形状。 */
    public record ProbeResult(String status,
                              String reason,
                              String message,
                              List<ProbeStep> steps,
                              long elapsedMillis,
                              Long probedAt) {
    }

    // ------------------------------------------------------------------ 列表摘要

    /**
     * catalog 列表每行带的那一小块。{@code status} 由服务端算（见 {@code ExecutionClusterStatus}），
     * 前端不重算：{@code configHash != probeConfigHash} 这种判据一旦两边各写一份迟早会漂，
     * 而漂的表现是「列表说正常，点进去说待验证」。
     */
    public record Summary(boolean configured,
                          boolean enabled,
                          String status,
                          String statusReason,
                          String clusterType,
                          String namespace,
                          String apiServer,
                          Long lastProbeAt,
                          String lastProbeMessage) {

        /** 没配的行。静态常量，避免列表里为每个未配置的 catalog 造对象。 */
        public static final Summary NOT_CONFIGURED =
                new Summary(false, false, "NOT_CONFIGURED", null, null, null, null, null, null);
    }
}
