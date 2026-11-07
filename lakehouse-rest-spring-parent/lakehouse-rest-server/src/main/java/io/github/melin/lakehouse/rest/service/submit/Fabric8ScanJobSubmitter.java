package io.github.melin.lakehouse.rest.service.submit;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.service.exec.ExecutionProbeException;
import io.github.melin.lakehouse.rest.service.exec.ResolvedCluster;
import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 把扫描提交成 k8s Job——**提交到该 catalog 自己的集群**，与优化批次同一份配置、
 * 同一把并发闸门（{@code docs/sensitive-data-design.md} §5.6.6）。
 *
 * <p><b>幂等与令牌的交叉点（本类最容易写错的一处）。</b>调度器的状态机是
 * 「抢占 → 提交 → 回写」，进程在「提交成功、回写之前」退出时，回收会把扫描打回
 * {@code PENDING}，下一次提交会命中**已存在的同名 Job**。此时：
 * <ul>
 *   <li>不能重建 Job（会覆盖一份正在跑的作业，或直接 409）；</li>
 *   <li>也**不能**沿用调用方这次新生成的令牌——那个令牌没有进过 Job 的命令行，
 *       作业回调时带的是旧令牌，服务端按库里的新哈希校验必然 403。
 *       而作业侧看到的只是「回调被拒」，日志里不会有任何一句提到令牌被换过。</li>
 * </ul>
 * 因此命中已存在时从 Job 的 {@code --token} 参数里把令牌读回来，交给调用方回写哈希。
 * 服务端本来就有这个集群的读权限，令牌从「自己创建的 Job 对象」里取回，不扩大信任边界。
 */
@Component
@RequiredArgsConstructor
public class Fabric8ScanJobSubmitter implements ScanJobSubmitter {

    private static final Logger log = LoggerFactory.getLogger(Fabric8ScanJobSubmitter.class);

    private final ExecutionClusterService clusters;

    @Override
    public String name() {
        return "kubernetes";
    }

    @Override
    public Submitted submit(Submission submission) {
        ResolvedCluster cluster = clusters.resolve(submission.catalogId());
        KubernetesClient client = clusters.clientFor(submission.catalogId());
        String namespace = cluster.namespace();
        String resource = ScannerJob.resourceName(submission.scanId());

        Job existing = client.batch().v1().jobs().inNamespace(namespace).withName(resource).get();
        if (existing != null) {
            String token = tokenOf(existing);
            if (token == null) {
                // 形状不认识（老报文、或有人手工改过 Job）：宁可直接失败，也不要用新令牌
                // 去覆盖哈希——那会造出一个「作业在跑、回调永远 403」的扫描
                throw new IllegalStateException("扫描作业 " + namespace + "/" + resource
                        + " 已存在，但其 --token 参数读不出来（报文形状与当前版本不一致）；"
                        + " 请先删除该 Job 或等待 TTL 清理后再重试");
            }
            log.info("sensitivity scan {} already has job {} in {} (phase {}), reusing its callback token",
                    submission.scanId(), resource, namespace, JobStates.phaseOf(existing));
            return new Submitted(namespace, resource, token);
        }

        Job job = ScannerJob.build(submission.scanId(), cluster, submission.callbackToken());
        try {
            client.batch().v1().jobs().inNamespace(namespace).resource(job).create();
        } catch (KubernetesClientException failure) {
            throw explain(failure, "提交敏感识别扫描到集群 " + cluster.apiServer() + "（namespace " + namespace + "）");
        }
        log.info("sensitivity scan {} submitted as {} in {} (namespace {}, image {})",
                submission.scanId(), resource, cluster.apiServer(), namespace, cluster.image());
        return new Submitted(namespace, resource, submission.callbackToken());
    }

    @Override
    public JobState state(String catalogId, String namespace, String resource) {
        if (namespace == null || resource == null) {
            return JobState.UNKNOWN;
        }
        Job job;
        try {
            job = clusters.clientFor(catalogId).batch().v1().jobs()
                    .inNamespace(namespace).withName(resource).get();
        } catch (KubernetesClientException failure) {
            throw explain(failure, "查询敏感识别扫描状态（" + namespace + "/" + resource + "）");
        }
        if (job == null) {
            return JobState.UNKNOWN;
        }
        return JobStates.stateOf(job);
    }

    @Override
    public void cancel(String catalogId, String namespace, String resource) {
        if (namespace == null || resource == null) {
            return;
        }
        try {
            clusters.clientFor(catalogId).batch().v1().jobs()
                    .inNamespace(namespace).withName(resource)
                    // Background：先删 Job 对象，Pod 由 GC 异步清。用 Foreground 会让这次调用
                    // 卡在「等 Pod 真正退出」上，而调用它的是调度节拍——一个不响应的 Pod
                    // 会拖住整拍（与优化批次同一条理由）
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
        } catch (KubernetesClientException failure) {
            throw explain(failure, "取消敏感识别扫描（" + namespace + "/" + resource + "）");
        }
    }

    /**
     * 从 Job 的容器参数里读回 {@code --token} 的值；读不到返回 {@code null}。
     *
     * <p>只找 {@code scanner} 容器：一个 Pod 里理论上只有它，但按名字找能让「将来加了
     * initContainer / sidecar」时不会误读别人的参数。
     */
    static String tokenOf(Job job) {
        if (job.getSpec() == null || job.getSpec().getTemplate() == null
                || job.getSpec().getTemplate().getSpec() == null) {
            return null;
        }
        List<Container> containers = job.getSpec().getTemplate().getSpec().getContainers();
        if (containers == null) {
            return null;
        }
        for (Container container : containers) {
            if (!ScannerJob.CONTAINER_NAME.equals(container.getName())) {
                continue;
            }
            List<String> args = container.getArgs();
            if (args == null) {
                return null;
            }
            for (int i = 0; i < args.size() - 1; i++) {
                if ("--token".equals(args.get(i))) {
                    return args.get(i + 1);
                }
            }
        }
        return null;
    }

    /** 同 {@link Fabric8JobSubmitter}：把 k8s 异常翻成「该去改什么」。 */
    private static RuntimeException explain(KubernetesClientException failure, String action) {
        ExecutionProbeException classified = ExecutionProbeException.classify(failure, action);
        return new IllegalStateException(classified.getMessage()
                + (classified.fix() == null ? "" : "（" + classified.fix() + "）"), failure);
    }
}
