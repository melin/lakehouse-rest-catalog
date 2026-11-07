package io.github.melin.lakehouse.rest.service.exec;

import io.github.melin.lakehouse.rest.support.ClusterType;
import java.util.List;

/**
 * 一份**解开后的**执行集群配置：密文已还原、kubeconfig 已解析成可用的形状。
 *
 * <p>它是「实体 → 可用配置」这条路上唯一的中转物：构造客户端、探活、提交三处都读它，
 * 而不是各自去解密、各自去解析 kubeconfig。三个地方各写一遍的结果是
 * 「探活说能连、提交说连不上」——两个实现的分歧只有在真机上才暴露。
 *
 * <p><b>它带着明文凭据，因此只活在方法栈上</b>：不缓存、不落库、不进日志
 * （{@link #toString()} 被覆写成不含凭据的形态，避免有人顺手打日志）。
 *
 * <p><b>表优化与敏感识别各占一组策略，共用一把并发闸门</b>：{@code maxTablesPerJob} /
 * {@code jobTimeoutSeconds} 给优化批次，{@code maxScanTablesPerJob} /
 * {@code scanJobTimeoutSeconds} 给下推扫描；{@code maxConcurrentJobs} 是**两类作业共用**
 * 的那一个数（{@code docs/sensitive-data-design.md} §5.6.6）——扫描没有独立的
 * {@code maxConcurrentScans}，两者争的本来就是同一台集群的算力。
 *
 * <p>{@code scanSparkConf} 是**已归一化**的 {@code k=v} 行（白名单过滤在
 * {@code ExecutionClusterService#normalizeSparkConf} 里做过），这里只负责带着它走到
 * 报文构造处。默认段（{@code spark.driver.memory=1g} / {@code spark.driver.cores=1}）
 * 不在这里补——它属于「没有配置时的起点」，由 {@code ScannerJob} 在拼命令行时垫底，
 * 两者合并之后才是一份完整的 {@code --conf} 列表。
 */
public record ResolvedCluster(ClusterType type,
                              String catalogId,
                              String contextName,
                              String apiServer,
                              String caCertPem,
                              String kubeconfig,
                              Auth auth,
                              String catalogToken,
                              String namespace,
                              String image,
                              String imagePullPolicy,
                              List<String> imagePullSecrets,
                              String serviceAccount,
                              String callbackBaseUrl,
                              int maxTablesPerJob,
                              int maxConcurrentJobs,
                              int jobTimeoutSeconds,
                              int maxScanTablesPerJob,
                              int scanJobTimeoutSeconds,
                              List<String> scanSparkConf) {

    /**
     * EKS / AKS 的认证参数，**一个 record 装两家**。
     *
     * <p>不拆成两个类型：它的生命周期只有「解密 → 构造 TokenProvider」这一段，
     * 拆开之后要在一个接口上做两套泛型，收益只是「不会误读对方的字段」——
     * 而真正防误读的是 {@code type}：读哪个字段由类型决定，不由字段是否为空决定。
     */
    public record Auth(String region,
                       String clusterName,
                       String accessKeyId,
                       String secretAccessKey,
                       String sessionToken,
                       String authority,
                       String tenantId,
                       String clientId,
                       String clientSecret,
                       String scope) {

        public static final Auth EMPTY = new Auth(null, null, null, null, null, null, null, null, null, null);
    }

    /** 日志里只出现这些：地址、命名空间、类型。凭据一律不出现。 */
    @Override
    public String toString() {
        return "ResolvedCluster[" + type + " " + apiServer + " ns=" + namespace + "]";
    }
}
