package io.github.melin.lakehouse.rest.service.submit;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobCondition;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;

/**
 * k8s Job 的状态折算：**优化批次与扫描作业共用这一份判据**。
 *
 * <p>抽出来的理由不是「少几行」：两条链路的收尾都用它判「成功 / 失败 / 还在跑」，
 * 而这里的判据顺序是有取舍的（见 {@link #stateOf}）——两份实现分叉之后，
 * 会出现「优化批次在某个瞬间被判失败、扫描在同一瞬间被判还在跑」这种只在真机上
 * 出现、且两边日志都自洽的差异。
 */
public final class JobStates {

    private JobStates() {
    }

    /**
     * Job → 三态。
     *
     * <p>判据的顺序是刻意的：先看 {@code conditions}，再看计数。{@code backoffLimit=0} 时
     * Pod 一失败 k8s 立刻给 {@code type=Failed} 的 condition，而 {@code status.failed}
     * 计数可能还是空（对象是同一轮 patch 出来的，读到中间态完全可能）。
     * 只看计数会漏掉「刚失败」的那一瞬间，而那一刻恰好是最常被轮询到的。
     */
    public static OptimizationJobSubmitter.JobState stateOf(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            // 刚创建、控制器还没处理：既没 condition 也没计数
            return OptimizationJobSubmitter.JobState.RUNNING;
        }
        if (conditionTrue(status, "Complete")) {
            return OptimizationJobSubmitter.JobState.SUCCEEDED;
        }
        if (conditionTrue(status, "Failed")) {
            return OptimizationJobSubmitter.JobState.FAILED;
        }
        if (status.getSucceeded() != null && status.getSucceeded() > 0) {
            return OptimizationJobSubmitter.JobState.SUCCEEDED;
        }
        // 有 active 计数、或三项都是空/零：都还在跑
        return status.getFailed() != null && status.getFailed() > 0
                ? OptimizationJobSubmitter.JobState.FAILED
                : OptimizationJobSubmitter.JobState.RUNNING;
    }

    /** 某个 condition 是否为 {@code True}。 */
    public static boolean conditionTrue(JobStatus status, String type) {
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

    /** 日志用的一句话阶段（{@code pending} / {@code running} / {@code complete} / {@code failed}）。 */
    public static String phaseOf(Job job) {
        JobStatus status = job.getStatus();
        if (status == null) {
            return "pending";
        }
        if (conditionTrue(status, "Complete")) {
            return "complete";
        }
        return conditionTrue(status, "Failed") ? "failed" : "running";
    }
}
