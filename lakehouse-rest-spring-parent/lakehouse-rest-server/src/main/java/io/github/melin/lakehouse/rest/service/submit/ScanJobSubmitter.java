package io.github.melin.lakehouse.rest.service.submit;

import io.github.melin.lakehouse.rest.service.submit.OptimizationJobSubmitter.JobState;

/**
 * 把一次敏感识别扫描提交成 k8s Job 的抽象（设计见
 * {@code docs/sensitive-data-design.md} §5.6.5）。
 *
 * <p><b>为什么另起一个接口而不是复用 {@link OptimizationJobSubmitter}。</b>两者共用的
 * 是「往哪个集群、怎么问它跑到哪了、怎么取消」这一段，而**不共用「提交什么」**：
 * 优化的 {@code SubmissionRequest} 只有批次 id，扫描的报文里还多一段按 catalog 配的
 * {@code --conf} 和扫描自己的超时与 TTL。硬塞进一个接口意味着一个实现了两类任务、
 * 内部再按 id 前缀分叉的类——那正是「读代码的人以为它只做一件事」的起点。
 * 状态的取值域仍共用 {@link JobState}，判据共用 {@link JobStates}。
 *
 * <p><b>令牌语义比优化批次更严一档。</b>优化把令牌明文存在批次行上（{@code callback_token}），
 * 扫描只存 SHA-256（{@code docs/sensitive-data-design.md} §4.2）。因此令牌**只在提交时生成、
 * 只进 Job 的命令行**，而 {@link #submit} 的返回值要把「这个 Job 实际用的那个令牌」交回来
 * ——进程在「提交成功、回写之前」退出时，回收之后的重投会命中已存在的同名 Job，
 * 那时必须沿用 Job 里的令牌而不是重新生成一个（重生成会让库里哈希与作业手里的令牌不一致，
 * 表现为回调 403 而作业一切正常）。
 */
public interface ScanJobSubmitter {

    /** 实现标识，落日志用于排查「这个扫描是谁提交的」。 */
    String name();

    /** 提交一次扫描。实现要保证幂等：同名资源已存在时视为已提交，并交回它实际用的令牌。 */
    Submitted submit(Submission submission);

    /** 查询作业状态。资源不存在时返回 {@link JobState#UNKNOWN}。 */
    JobState state(String catalogId, String namespace, String resource);

    /** 取消作业；资源已不存在时静默返回。 */
    void cancel(String catalogId, String namespace, String resource);

    /**
     * 提交所需的最小信息。
     *
     * <p>表清单与探测器配置**不在这里**：作业自己按 {@code scanId} 分页回调拉
     * （{@code GET /sensitivity-scans/{id}/submission}）。理由与优化批次相同——参数有长度上限，
     * 而扫描的表清单可能有几百张、每张还带列清单，塞进命令行必然撞上。
     */
    record Submission(String scanId, String catalogId, String callbackToken) {
    }

    /**
     * 提交结果。
     *
     * <p>{@code callbackToken} 是**这个 Job 实际会用的令牌**，不一定等于请求里那个：
     * 命中已存在的同名 Job 时，它是从 Job 的 {@code --token} 参数里读回来的。
     * 调用方必须用它回写 {@code callback_token_hash}，否则回调校验必然失败。
     */
    record Submitted(String namespace, String resource, String callbackToken) {
    }
}
