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
 *   <li>{@code ttlSecondsAfterFinished}：跑完一小时由 k8s 清掉，Job 对象不会越积越多。
 *       要排障的人在这一小时内仍然能 {@code kubectl describe}。</li>
 * </ul>
 */
public final class OptimizerJob {

    /** 批次结束后由 k8s 自动清理资源，避免 Job 对象越积越多。 */
    public static final int TTL_SECONDS_AFTER_FINISHED = 3600;

    /** 资源名前缀。k8s 名字上限 63 字符，批次 id 是 32 位十六进制，加前缀仍在限内。 */
    public static final String NAME_PREFIX = "paimon-optimize-";

    private OptimizerJob() {
    }

    /** 资源名。探活用它建 dry-run 对象，提交用它建真对象——同一个函数，同一条命名规则。 */
    public static String resourceName(String jobId) {
        return NAME_PREFIX + jobId;
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
        args.add(cluster.callbackBaseUrl());
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
                    .withTtlSecondsAfterFinished(TTL_SECONDS_AFTER_FINISHED)
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

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
