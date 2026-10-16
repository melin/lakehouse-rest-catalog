package io.github.melin.lakehouse.rest.dto;

import java.util.List;
import java.util.Map;

/**
 * 表存储优化的请求与响应模型（管理 API 基址 {@code /api/management/v1}）。
 *
 * <p>与其余管理 API 的模型一样用 record：只在控制器与服务层之间传递，无原地修改。
 *
 * <p><b>为什么策略里同时给出 {@code config} 与 {@code effective}。</b>
 * {@code config} 是用户在表上显式写过的差异（可能就是空的），{@code effective}
 * 是三层合并后的实际取值。控制台靠前者决定「默认设置 / 自定义」两档的选中态，
 * 靠后者显示「你现在会以什么参数跑」——只给一个的话，要么分不清用户改没改过，
 * 要么得让前端自己实现一遍合并规则（两份合并逻辑必然漂移）。
 */
public final class OptimizationDtos {

    private OptimizationDtos() {
    }

    // ---------------------------------------------------------------- 策略

    /** 一项动作的策略：开关、差异配置、生效配置。 */
    public record ActionPolicy(String action,
                               String displayName,
                               boolean enabled,
                               Map<String, String> config,
                               Map<String, String> effective,
                               boolean usingDefaults) {
    }

    /**
     * 一张表的完整策略。
     *
     * <p>{@code supported} 与 {@code unsupportedReason} 说的是这张表**能不能**开优化
     * （非 Paimon 表、外部表不行，见 {@code TableOptimizationService}）。
     * 与 {@code enabled} 是两件事：前者是能力边界，后者是用户的开关。
     * 放在同一个响应里，是因为控制台要在同一个面板上同时表达「不支持，原因是 X」
     * 与「支持但你还没开」——分两个接口拿，页面会出现半截状态。
     */
    public record TablePolicy(String catalog,
                              String database,
                              String table,
                              boolean enabled,
                              int scheduleIntervalSeconds,
                              Long nextRunAt,
                              String lastJobId,
                              boolean supported,
                              String unsupportedReason,
                              List<ActionPolicy> actions) {
    }

    /** 一项动作的更新：{@code null} 表示不改（与 {@code false} 是两件事）。 */
    public record ActionPolicyUpdate(String action, Boolean enabled, Map<String, String> config) {
    }

    /**
     * 表策略更新。
     *
     * <p>逐字段可选：控制台改一个开关不该顺手提交整张表的所有参数，
     * 那会把「另一个标签页里刚改的值」覆盖回去。{@code actions} 里没提到的动作完全不动；
     * 提到的动作里，{@code config} 为 {@code null} 表示保持原样，空 Map 表示清空回默认。
     */
    public record UpdateTablePolicyRequest(Boolean enabled,
                                           Integer scheduleIntervalSeconds,
                                           List<ActionPolicyUpdate> actions) {
    }

    /** catalog 级默认设置：原样给出 {@code optimization.*} 属性与拆好的生效默认值。 */
    public record CatalogSettings(String catalog,
                                  Map<String, String> properties,
                                  List<ActionPolicy> defaults) {
    }

    /** catalog 级默认设置的更新：整体替换这批属性，未提到的键保持不变。 */
    public record UpdateCatalogSettingsRequest(Map<String, String> properties) {
    }

    /**
     * 立即优化请求：不指定动作则跑该表所有已启用的动作。
     *
     * <p>没有「本次强制真删孤儿文件」这类一次性覆盖参数：会删数据的动作必须体现在
     * 策略里（{@code dry-run} 开关），而不是藏在一次请求的参数里。一次点击就能绕过
     * 策略删文件，等于策略形同虚设；要真删就改策略，那是一次显式且留痕的操作。
     */
    public record RunOptimizationRequest(List<String> actions) {
    }

    // ---------------------------------------------------------------- 批次

    /**
     * 批次里的单表明细。
     *
     * <p>带上 {@code jobId}：表详情页看到的是一条条「这张表在某个批次里跑成什么样」，
     * 而运维的下一个动作几乎总是「去看看那个批次里别的表怎么样」。不给批次 id 的话，
     * 单表历史是一条死路。
     */
    public record JobItem(String id,
                          String jobId,
                          String tableId,
                          String table,
                          String actions,
                          String status,
                          Map<String, String> metrics,
                          String errorMessage,
                          Long startedAt,
                          Long finishedAt,
                          Long reportedAt) {
    }

    /**
     * 优化批次。
     *
     * <p>{@code items} 只在详情里给（列表页要的是批次级信息，带上明细会让
     * 一次列表请求返回几百行）；{@code callbackToken} 只在调用方有权看时给，
     * 见 {@code OptimizationController}。
     */
    public record Job(String id,
                      String catalog,
                      String triggerType,
                      List<String> actions,
                      String status,
                      int tableCount,
                      String submitter,
                      String namespace,
                      String resource,
                      String sparkAppId,
                      Long createdAt,
                      Long submittedAt,
                      Long startedAt,
                      Long finishedAt,
                      Long deadlineAt,
                      String errorMessage,
                      String retryOf,
                      String callbackToken,
                      List<JobItem> items) {
    }

    /** 批次列表。 */
    public record Jobs(List<Job> jobs) {
    }

    /**
     * 单表的优化历史（表详情页的三块历史）。
     *
     * <p>为什么不复用批次列表：列表按设计不带表级明细，控制台要回答「这张表最近几次
     * 跑成什么样」就得逐个批次去拉详情——那是 N 次请求换一屏数据，而这张表的明细
     * 本来就在一张表里，一次查完。
     */
    public record TableHistory(String table, List<JobItem> items) {
    }

    // ---------------------------------------------------------------- 优化任务协定

    /** 优化任务启动时拉取的执行清单。 */
    public record Submission(String jobId,
                             String catalog,
                             String triggerType,
                             List<String> actions,
                             List<SubmissionTable> tables) {
    }

    /**
     * 清单里的一张表。
     *
     * <p>{@code options} 按动作给出**合并后的全部参数**（不是差异）：执行侧的
     * 参数解析越少越好，而服务端本来就要算一遍生效配置用于展示，复用同一份
     * 结果可以保证「控制台显示的参数」与「任务实际用的参数」永远是同一个值。
     *
     * <p>{@code status} 一并给出，是为了让任务的重启是幂等的：上报过的表（终态）
     * 不必再跑一遍，重拉清单时跳过即可。
     */
    public record SubmissionTable(String itemId,
                                  String table,
                                  String status,
                                  Map<String, Map<String, String>> options) {
    }

    /** 单表结果上报。{@code status} 取 {@code SUCCESS} / {@code FAILED} / {@code SKIPPED}。 */
    public record ItemReportRequest(String status, Map<String, String> metrics, String errorMessage) {
    }
}
