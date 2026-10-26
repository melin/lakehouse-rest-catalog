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
    UNKNOWN
}
