package io.github.melin.lakehouse.rest.support;

/**
 * 错误响应中的资源类型。
 *
 * <p>前一组取值与 catalog 规格 {@code ErrorResponse.resourceType} 一致；
 * 后一组是管理 API 的资源类型。管理规格的错误响应只给了描述、没有定义响应体，
 * 因此这组取值是本服务为统一错误体而设，不与规格冲突。
 */
public enum ResourceType {
    // catalog API
    DATABASE,
    TABLE,
    PARTITION,
    COLUMN,
    SNAPSHOT,
    BRANCH,
    TAG,
    VIEW,
    SEMANTIC_VIEW,
    DIALECT,
    FUNCTION,
    DEFINITION,
    // management API
    CATALOG,
    PRINCIPAL,
    PRINCIPAL_ROLE,
    CATALOG_ROLE,
    GRANT,
    /** 存储优化策略与优化批次（本服务自己的一组资源，管理 API 的一部分）。 */
    TABLE_OPTIMIZATION,
    OPTIMIZATION_JOB,
    /**
     * catalog 的执行集群配置（优化任务提交到哪个 k8s、用什么身份）。
     *
     * <p>它是 catalog 的从属资源（1:1，随 catalog 级联删除），但在错误体里要有自己的
     * 资源类型：{@code 404} 的语义是「这个 catalog 还没配执行集群」，与「catalog 不存在」
     * 是两件事，混用 {@code CATALOG} 会让人去查错对象。
     */
    EXECUTION_CLUSTER,
    /** 血缘的一次执行记录（{@code /api/management/v1/lineage/runs/{runId}} 的 404 用它定位）。 */
    LINEAGE_RUN,
    /** 敏感类型与探测器配置（{@code /api/management/v1/sensitive-types/{typeCode}}）。 */
    SENSITIVE_TYPE,
    /**
     * 一次敏感数据扫描（{@code /api/management/v1/sensitivity-scans/{scanId}}）。
     *
     * <p>与 {@link #SENSITIVITY_FINDING} 分开：{@code 404} 的语义是「这次扫描不存在」
     * 还是「这条命中不存在」，指向的是两张表、两套排查动作——混成一个取值会让人
     * 拿着 finding 的 id 去扫描历史里找。
     */
    SENSITIVITY_SCAN,
    /** 一次扫描的命中记录。 */
    SENSITIVITY_FINDING,
    /**
     * 人审否决的永久抑制项（{@code /api/management/v1/sensitivity-suppressions/{id}}）。
     *
     * <p>与 {@link #SENSITIVITY_FINDING} 分开：抑制项是「这一列**不是**某类型」这个结论，
     * 它的 {@code 404} 指向的是「这条抑制记录不存在」，而不是「这条命中不存在」。
     * 混成一个取值会让人拿着抑制项的 id 去人审队列里找。
     */
    SENSITIVITY_SUPPRESSION,
    /** 列级标签（{@code /api/management/v1/column-tags}）。 */
    COLUMN_TAG,
    /**
     * 一条定时扫描的调度项（{@code /api/management/v1/sensitivity-schedules/{id}}）。
     *
     * <p>与 {@link #SENSITIVITY_SCAN} 分开：{@code 404} 的语义是「这条调度项不存在」
     * 还是「这次扫描不存在」，指向两张表、两套排查动作。而两者的 id 形状还不同
     * （调度项是自增数字、扫描是 UUID），混用会让人拿着 3 去扫描历史里找。
     */
    SENSITIVITY_SCHEDULE,
    /** 标签到脱敏动作的绑定。 */
    TAG_MASK_BINDING,
    UNKNOWN
}
