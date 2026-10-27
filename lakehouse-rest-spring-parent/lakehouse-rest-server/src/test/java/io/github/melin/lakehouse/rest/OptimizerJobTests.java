package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.github.melin.lakehouse.rest.service.exec.ResolvedCluster;
import io.github.melin.lakehouse.rest.service.submit.OptimizerJob;
import io.github.melin.lakehouse.rest.support.ClusterType;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 优化任务 Job 报文的断言——重点是**地址拼接**。
 *
 * <p>为什么这条值得单独测：{@code callbackBaseUrl} 与 {@code --management-url}
 * 是两个不同粒度的地址（根 vs 管理 API 基址），差一个 {@code /api/management/v1}
 * 在单测里看不出来，在集群里表现为任务一启动就
 * {@code HTTP 404 ... No static resource optimization/jobs/...}——
 * 而那个 404 是静态资源处理器回的，看起来像「端点不存在」，实际是地址拼错。
 */
class OptimizerJobTests {

    private static final String CALLBACK_BASE =
            "http://lakehouse-rest-server.lakehouse-rest.svc.cluster.local:8080";

    private static ResolvedCluster cluster(String callbackBaseUrl, String catalogToken) {
        return new ResolvedCluster(
                ClusterType.SELF_HOSTED,
                "demo",
                "ctx",
                "https://172.88.0.153:6443",
                null,
                null,
                ResolvedCluster.Auth.EMPTY,
                catalogToken,
                "lakehouse-rest",
                "registry.example.com/lakehouse-rest-catalog:1.0.0",
                "IfNotPresent",
                List.of(),
                null,
                callbackBaseUrl,
                20,
                2,
                3600);
    }

    private static List<String> args(Job job) {
        return job.getSpec().getTemplate().getSpec().getContainers().get(0).getArgs();
    }

    /** 取 {@code --name value} 形式里的 value。 */
    private static String argValue(List<String> args, String name) {
        int index = args.indexOf("--" + name);
        assertTrue(index >= 0 && index + 1 < args.size(), "缺少参数 --" + name + "，实际：" + args);
        return args.get(index + 1);
    }

    @Test
    void managementUrlCarriesTheManagementApiPrefix() {
        Job job = OptimizerJob.build("job-1", cluster(CALLBACK_BASE, null), "tk");

        assertEquals(CALLBACK_BASE + "/api/management/v1", argValue(args(job), "management-url"),
                "任务会在这个地址后面拼 /optimization/jobs/{id}/submission，少了前缀就会落到静态资源处理器");
        assertEquals("job-1", argValue(args(job), "job-id"));
        assertEquals("tk", argValue(args(job), "token"));
    }

    @Test
    void trailingSlashDoesNotProduceADoubleSlash() {
        Job job = OptimizerJob.build("job-2", cluster(CALLBACK_BASE + "/", null), "tk");

        assertEquals(CALLBACK_BASE + "/api/management/v1", argValue(args(job), "management-url"));
    }

    @Test
    void alreadyPrefixedCallbackBaseUrlIsNotPrefixedTwice() {
        Job job = OptimizerJob.build("job-3", cluster(CALLBACK_BASE + "/api/management/v1", null), "tk");

        assertEquals(CALLBACK_BASE + "/api/management/v1", argValue(args(job), "management-url"));
    }

    @Test
    void catalogTokenIsOmittedWhenAbsent() {
        Job job = OptimizerJob.build("job-4", cluster(CALLBACK_BASE, null), "tk");
        assertFalse(args(job).contains("--catalog-token"), "空令牌会让任务以为「有令牌但为空」");

        Job withToken = OptimizerJob.build("job-5", cluster(CALLBACK_BASE, "data-plane-token"), "tk");
        assertEquals("data-plane-token", argValue(args(withToken), "catalog-token"));
    }

    /**
     * 报文里的两条取舍：k8s 自己重试会把「哪张表失败」的事实冲掉，
     * 因此失败即止；跑完一小时自动清理，避免 Job 对象越积越多。
     */
    @Test
    void retryAndCleanupFollowTheBatchSemantics() {
        Job job = OptimizerJob.build("job-6", cluster(CALLBACK_BASE, null), "tk");

        assertEquals(0, job.getSpec().getBackoffLimit().intValue());
        assertEquals(3600, job.getSpec().getTtlSecondsAfterFinished().intValue());
        assertEquals("Never", job.getSpec().getTemplate().getSpec().getRestartPolicy());
        assertEquals("paimon-optimize-job-6", job.getMetadata().getName());
    }
}
