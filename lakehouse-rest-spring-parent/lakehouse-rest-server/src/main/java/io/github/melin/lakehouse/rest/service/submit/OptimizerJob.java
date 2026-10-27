package io.github.melin.lakehouse.rest.service.submit;

import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.github.melin.lakehouse.rest.service.exec.ResolvedCluster;
import java.util.ArrayList;
import java.util.List;

/**
 * 优化任务的 Job 报文：**提交与探活共用这一份构造**。
 *
 * <p>为什么单独抽出来：探活第 4 步（dry-run 建 Job）与真正的提交必须产出一模一样的报文，
 * 否则「探活通过、提交被拒」就是一个只会在真机上出现的差异（比如 dry-run 时漏了
 * {@code imagePullSecrets}，准入层看不出问题，真跑时 {@code ErrImagePull}）。
 * 报文只有一处，这个差异就不可能发生。
 *
 * <p>两条刻意的取舍写在报文里：
 * <ul>
 *   <li>{@code backoffLimit=0}：重试是**批次级**语义（只重跑失败的表），k8s 自己重试会把
 *       「哪张表失败」的事实冲掉，也会重复删一遍孤儿文件。</li>
 *   <li>{@code ttlSecondsAfterFinished}：跑完由 k8s 清掉，Job 对象不会越积越多。
 *       要排障的人在这段时间内仍然能 {@code kubectl describe}。</li>
 * </ul>
 *
 * <p><b>TTL 必须大于批次的超时时间（{@link #ttlSecondsAfterFinished}）。</b>
 * 服务端的跟踪是「读 Job 的 condition」：如果 Job 在被收尾之前就被 TTL 清掉，
 * {@code state()} 只能返回 {@code UNKNOWN}，而 {@code pollRunning} 对 {@code UNKNOWN}
 * 的处置是「不动它」——于是一个**已经成功**的批次要一直等到 deadline 才被判超时。
 * 固定 3600 秒 + 默认 7200 秒的超时曾经正好落进这个坑里。
 */
public final class OptimizerJob {

    /**
     * {@code ttlSecondsAfterFinished} 的下限：跑完后至少留一小时给排障。
     *
     * <p>实际取值还要大于批次的超时时间，见 {@link #ttlSecondsAfterFinished}。
     */
    public static final int TTL_SECONDS_AFTER_FINISHED = 3600;

    /** TTL 相对超时的余量：至少覆盖「deadline 到点后一到两拍才被收尾」的窗口。 */
    public static final int TTL_MARGIN_SECONDS = 600;

    /** 资源名前缀。k8s 名字上限 63 字符，批次 id 是 32 位十六进制，加前缀仍在限内。 */
    public static final String NAME_PREFIX = "paimon-optimize-";

    /** 管理 API 的固定前缀，与 {@code OptimizationController} 的类级映射一致。 */
    private static final String MANAGEMENT_PATH = "/api/management/v1";

    private OptimizerJob() {
    }

    /**
     * 资源名。探活用它建 dry-run 对象，提交用它建真对象——同一个函数，同一条命名规则。
     *
     * <p>这条稳定性还是**提交回收**的安全支点：被回收的批次重投时必须命中同名 Job，
     * 才不会出现「集群里跑着一个、数据库里说没提交」的状态。
     */
    public static String resourceName(String jobId) {
        return NAME_PREFIX + jobId;
    }

    /**
     * Job 的清理延迟：{@code max(1 小时, 批次超时 + 10 分钟)}。
     *
     * <p>为什么不能只用一个固定值：服务端靠读 Job 的 condition 判定成败，Job 一旦被
     * TTL 清掉，{@code state()} 只能返回 {@code UNKNOWN}，收尾就只能等 deadline。
     * 因此 TTL 必须**大于**批次的超时时间——否则「执行成功」会被记成「超时」。
     */
    public static int ttlSecondsAfterFinished(ResolvedCluster cluster) {
        return Math.max(TTL_SECONDS_AFTER_FINISHED, cluster.jobTimeoutSeconds() + TTL_MARGIN_SECONDS);
    }

    /**
     * 构造一个 Job。
     *
     * @param callbackToken 批次回调令牌；探活时传一个占位串（dry-run 不落库，也不需要真令牌）
     */
    public static Job build(String jobId, ResolvedCluster cluster, String callbackToken) {
        List<String> args = new ArrayList<>();
        args.add("--job-id");
        args.add(jobId);
        args.add("--management-url");
        args.add(managementApiUrl(cluster.callbackBaseUrl()));
        args.add("--token");
        args.add(callbackToken);
        // 数据面令牌是可选的：没开认证的部署（本地、测试）不需要它。
        // 传一个空值会让任务以为「有令牌但为空」，因此干脆不传这个参数。
        if (cluster.catalogToken() != null && !cluster.catalogToken().isBlank()) {
            args.add("--catalog-token");
            args.add(cluster.catalogToken());
        }

        List<LocalObjectReference> pullSecrets = new ArrayList<>();
        for (String name : cluster.imagePullSecrets()) {
            pullSecrets.add(new LocalObjectReferenceBuilder().withName(name).build());
        }

        return new JobBuilder()
                .withNewMetadata()
                    .withName(resourceName(jobId))
                    .withNamespace(cluster.namespace())
                    .addToLabels("app", "paimon-optimizer")
                    .addToLabels("lakehouse-rest-job", jobId)
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withTtlSecondsAfterFinished(ttlSecondsAfterFinished(cluster))
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", "paimon-optimizer")
                            .addToLabels("lakehouse-rest-job", jobId)
                        .endMetadata()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .withServiceAccountName(blankToNull(cluster.serviceAccount()))
                            .withImagePullSecrets(pullSecrets)
                            .addNewContainer()
                                .withName("optimizer")
                                .withImage(cluster.image())
                                .withImagePullPolicy(cluster.imagePullPolicy())
                                .withArgs(args)
                            .endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
    }

    /**
     * 把「服务端根地址」拼成管理 API 基址。
     *
     * <p>这两个地址**不是同一个东西**，混用的代价在真机上才显形：
     *
     * <ul>
     *   <li>{@code callbackBaseUrl} 的语义是**根**——探活那一步用的是
     *       {@code callbackBaseUrl + "/api/console/v1/meta"}，同样不带前缀。
     *   <li>任务侧 {@code --management-url} 要的是**管理 API 基址**：
     *       {@code OptimizationApiClient} 直接往上拼
     *       {@code /optimization/jobs/{id}/submission}。
     * </ul>
     *
     * <p>早期版本把根地址原样传下去，任务一启动就报
     * {@code HTTP 404 ... No static resource optimization/jobs/...}。
     * 那个 404 来自静态资源处理器，说明请求**根本没匹配上任何控制器**；
     * 而批次不存在时的 404 带 {@code resourceType=OPTIMIZATION_JOB}。
     * 两者要分清——前者是地址拼错，后者才是数据问题。
     *
     * <p>已经带前缀的配置不再重复拼：有人会把 callbackBaseUrl 直接填成管理地址
     * （探活那一步也就跟着变成了 {@code /api/management/v1/api/console/v1/meta}，
     * 他们会自己发现），这里至少别把任务也一起弄坏。
     */
    static String managementApiUrl(String callbackBaseUrl) {
        String base = callbackBaseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base.endsWith(MANAGEMENT_PATH) ? base : base + MANAGEMENT_PATH;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
