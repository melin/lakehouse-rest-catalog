package io.github.melin.lakehouse.rest.service.submit;

/**
 * 把优化批次提交到执行环境的抽象。
 *
 * <p><b>为什么还要保留这层抽象。</b>批次状态机（落库 → 提交 → 跟踪 → 收尾）与
 * 「提交到哪里、怎么问它跑到哪了」是两件事：前者是业务规则，后者随部署形态变化。
 * 分开之后状态机可以在没有集群的环境里被完整测试——测试注入一个桩提交器，
 * 直接驱动 {@link JobState} 的每一次跃迁，不必去起一个 k8s。
 *
 * <p><b>生产实现只有一个：{@link Fabric8JobSubmitter}。</b>上一版的两种实现
 * （{@code KubernetesJobSubmitter} 用 RestClient 手拼三个动词、{@code LoggingJobSubmitter}
 * 只打日志）已删除：前者的认证只有「读 ServiceAccount 令牌文件」一条路，因此它
 * 只能提交到服务端自己所在的集群——「每个 catalog 用自己的集群」在它的形状里
 * 根本表达不出来；后者的存在理由是「没有执行环境也要能把状态机跑起来」，
 * 而那件事现在由测试里的桩承担，不需要一个会静默吞掉所有批次的生产实现
 * （{@code submitter=log} 配错一次，所有优化都会「成功」而什么都没做）。
 *
 * <p><b>每个方法都带 {@code catalogId}。</b>这是本接口相对上一版的唯一形状变化，
 * 也是这次改造的核心：同一个服务端要为不同 catalog 提交到不同集群，
 * 而「用哪个集群」是 catalog 的属性。带着它，实现才能取到对应那份配置与客户端；
 * 不带它，实现只能回落成「全局那一个集群」——那正是被去掉的东西。
 *
 * <p>实现不抛受检异常：调用方（{@code OptimizationDispatcher}）对失败的处置是
 * 「把批次置为 FAILED 并记下原因」，这个处置对所有实现都一样，方法签名上再声明一遍
 * 异常只会让调用处多一层无意义的包装。
 */
public interface OptimizationJobSubmitter {

    /** 实现标识，落库到批次的 {@code submitter} 列，用于排查「这批是谁提交的」。 */
    String name();

    /** 提交一个批次。实现要保证幂等：同名资源已存在时视为已提交成功。 */
    Submitted submit(SubmissionRequest request);

    /** 查询批次在执行侧的状态。资源不存在时返回 {@link JobState#UNKNOWN}。 */
    JobState state(String catalogId, String namespace, String resource);

    /** 取消批次；资源已不存在时静默返回。 */
    void cancel(String catalogId, String namespace, String resource);

    /** 执行侧的状态。只有前三种是终态判定用得上的，{@code UNKNOWN} 表示查不到。 */
    enum JobState {
        RUNNING,
        SUCCEEDED,
        FAILED,
        UNKNOWN
    }

    /**
     * 提交所需的最小信息。
     *
     * <p>表清单与配置**不在这里**：任务自己去回调管理 API 拉。原因是参数有长度上限，
     * 而一批可能有几十张表、每张表三个动作各一组参数——塞进命令行迟早会撞上，
     * 而且「库里是什么就执行什么」比「提交时快照一份参数」更不容易出岔子。
     *
     * <p>{@code catalogId} 是**内部 id**，不是 prefix：实现要拿它去查这个 catalog 的
     * 执行集群配置（表上存的就是 id）。
     */
    record SubmissionRequest(String jobId, String catalogId, String callbackToken) {
    }

    /** 提交结果：执行侧的资源定位信息，回填到批次上用于跟踪与取消。 */
    record Submitted(String namespace, String resource) {
    }
}
