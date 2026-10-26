package io.github.melin.lakehouse.rest.service.submit;

import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClientFactory;
import io.github.melin.lakehouse.rest.service.exec.ExecutionClusterService;
import io.github.melin.lakehouse.rest.service.exec.ExecutionProbeException;
import io.github.melin.lakehouse.rest.service.exec.ResolvedCluster;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 用 fabric8 把优化批次提交成 k8s Job——**提交到该 catalog 自己的集群**。
 *
 * <p><b>为什么换成 fabric8。</b>上一版用 {@code RestClient} 手拼 {@code POST/LIST/DELETE}
 * 三个动词，认证只有一个来源：挂载在服务端 Pod 里的 ServiceAccount 令牌文件。
 * 那个形状只能表达「提交给服务端自己所在的集群」，而本设计的核心恰恰是
 * 「不同 catalog 用不同集群、各自带认证」。fabric8 把三件事一次解决：
 * 按 kubeconfig 或令牌回调建客户端、序列化 Job 报文、把 409/404/401 翻成有意义的异常。
 *
 * <p><b>客户端从 {@link ExecutionClientFactory} 拿，绝不自己 new。</b>那里做了三件本类
 * 不该重复的事：按 catalog 缓存（一个客户端 = 一组 OkHttp 线程 + 连接池）、
 * 配置变更时关掉旧的、以及断言客户端真的指向配置的 apiserver（防
 * {@code autoConfigure} 静默回落到服务端自己所在的集群）。**因此本类不 close 客户端**——
 * 它属于缓存，不属于某一次调用。
 *
 * <p><b>幂等。</b>{@link #submit} 先查同名资源是否存在：调度器的状态机是「抢占 → 提交 →
 * 回写」，进程在「提交成功、回写之前」崩掉会让批次回到 PENDING（乐观锁失效后重新抢），
 * 下一次提交必须把「已经存在」当成成功，否则那个批次永远失败、而集群里那个 Job 还在跑。
 * 名字由 {@link OptimizerJob#resourceName} 唯一决定（前缀 + 批次 id），这条幂等才成立。
 *
 * <p><b>TTL 的副作用要认识。</b>Job 跑完一小时后由 k8s 清掉（{@code ttlSecondsAfterFinished}），
 * 此后 {@link #state} 只能返回 {@link JobState#UNKNOWN}。这不是缺陷：判定终态靠的是
 * 批次的 deadline（超时即收尾），而「查不到」在 {@code pollRunning} 里被视为「不动它」。
 * 反过来说，若某个批次的 Job 在还没被判超时就消失了，它会在 deadline 到达时被收尾。
 */
@Component
@RequiredArgsConstructor
public class Fabric8JobSubmitter implements OptimizationJobSubmitter {

    private static final Logger log = LoggerFactory.getLogger(Fabric8JobSubmitter.class);

    private final ExecutionClusterService clusters;

    @Override
    public String name() {
        return "kubernetes";
    }

    @Override
    public Submitted submit(SubmissionRequest request) {
        ResolvedCluster cluster = clusters.resolve(request.catalogId());
        KubernetesClient client = clusters.clientFor(request.catalogId());
        String namespace = cluster.namespace();
        String resource = OptimizerJob.resourceName(request.jobId());

        // 已经存在 = 上一次提交其实成功了（进程在回写前退出）。当成成功，不重建：
        // 重建要么 409，要么（如果上一次其实没建出来）多此一举地覆盖一份正在跑的 Job
        Job existing = client.batch().v1().jobs().inNamespace(namespace).withName(resource).get();
        if (existing != null) {
            log.info("optimization job {} already exists in {} (phase {}), treating as submitted",
                    resource, namespace, phaseOf(existing));
            return new Submitted(namespace, resource);
        }

        Job job = OptimizerJob.build(request.jobId(), cluster, request.callbackToken());
        try {
            client.batch().v1().jobs().inNamespace(namespace).resource(job).create();
        } catch (KubernetesClientException failure) {
            throw explain(failure, "提交优化任务到集群 " + cluster.apiServer() + "（namespace " + namespace + "）");
        }
        log.info("optimization job {} created in {} (namespace {}, image {})",
                resource, cluster.apiServer(), namespace, cluster.image());
        return new Submitted(namespace, resource);
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
            // 集群暂时不可达：交给调用方决定（它把 UNKNOWN 当「不动它」）
            throw explain(failure, "查询优化任务状态（" + namespace + "/" + resource + "）");
        }
        if (job == null) {
            return JobState.UNKNOWN;
        }
        return stateOf(job);
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
                    // 卡在「等 Pod 真正退出」上，而调度循环是单线程的——一个不响应的 Pod
                    // 会拖住所有 catalog 的分派
                    .withPropagationPolicy(DeletionPropagation.BACKGROUND).delete();
        } catch (KubernetesClientException failure) {
            throw explain(failure, "取消优化任务（" + namespace + "/" + resource + "）");
        }
    }

    // ------------------------------------------------------------------ 状态折算

    /**
     * Job → 三态。
     *
     * <p>判据的顺序是刻意的：先看 {@code conditions}，再看计数。{@code backoffLimit=0} 时
     * Pod 一失败 k8s 立刻给 {@code type=Failed} 的 condition，而 {@code status.failed}
     * 计数可能还是空（对象是同一轮 patch 出来的，读到中间态完全可能）。
     * 只看计数会漏掉「刚失败」的那一瞬间，而那一刻恰好是最常被轮询到的。
     */
    static JobState stateOf(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            // 刚创建、控制器还没处理：既没 condition 也没计数
            return JobState.RUNNING;
        }
        boolean completed = conditionTrue(status, "Complete");
        boolean failed = conditionTrue(status, "Failed");
        if (completed) {
            return JobState.SUCCEEDED;
        }
        if (failed) {
            return JobState.FAILED;
        }
        if (status.getSucceeded() != null && status.getSucceeded() > 0) {
            return JobState.SUCCEEDED;
        }
        // 有 active 计数、或三项都是空/零：都还在跑
        return status.getFailed() != null && status.getFailed() > 0 ? JobState.FAILED : JobState.RUNNING;
    }

    private static boolean conditionTrue(JobStatus status, String type) {
        if (status.getConditions() == null) {
            return false;
        }
        for (JobCondition condition : status.getConditions()) {
            if (type.equals(condition.getType()) && "True".equals(condition.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private static String phaseOf(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return "pending";
        }
        if (conditionTrue(status, "Complete")) {
            return "complete";
        }
        return conditionTrue(status, "Failed") ? "failed" : "running";
    }

    /**
     * 把 k8s 客户端异常翻成「该去改什么」。
     *
     * <p>不做这一步的后果是：提交失败时批次上写着
     * {@code Failure executing: POST at: https://…/jobs. Message: Forbidden!}——
     * 用户看不出这是 RBAC 少了一条还是一整类配置错了。{@link ExecutionProbeException#classify}
     * 已经在探活那条路上做了同样的归类，这里复用同一份判断（两处判定不一致会出现
     * 「探活说通过、提交说 403」这种最难查的状态）。
     */
    private static RuntimeException explain(KubernetesClientException failure, String action) {
        ExecutionProbeException classified = ExecutionProbeException.classify(failure, action);
        return new IllegalStateException(classified.getMessage()
                + (classified.fix() == null ? "" : "（" + classified.fix() + "）"), failure);
    }
}
