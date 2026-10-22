package io.github.melin.lakehouse.rest.config;

import io.github.melin.lakehouse.rest.support.AuditPrincipal;

/**
 * 单次请求的上下文：保存当前认证主体，供审计字段写入。
 *
 * <p>与 Polaris 的做法一致，认证在服务端统一完成，各引擎请求都经过同一层鉴权，
 * 因此业务代码只需要读取主体名，不需要感知具体认证方式。
 *
 * <p>这里保存的是<b>原始</b>主体名：授权判定按它查授权链路，长度不受限。
 * 落库到审计列时才会归一化（超长的记摘要），见
 * {@link AuditPrincipal#of}——两处的要求不同，所以不在这一层改写。
 */
public final class RequestContext {

    private static final ThreadLocal<String> PRINCIPAL = new ThreadLocal<>();

    /**
     * 请求关联 ID：与主体名同一生命周期（拦截器进、出各一次），事件与审计按它串起
     * 同一次请求的多条记录。目录事件框架引入（{@code docs/event-framework.md} §3），
     * 取值规则在 {@code BearerAuthInterceptor}——先信客户端带来的
     * {@code X-Request-Id}，没有才生成，因此**跨服务调用可以透传同一个 id**。
     */
    private static final ThreadLocal<String> REQUEST_ID = new ThreadLocal<>();

    private RequestContext() {
    }

    public static void setPrincipal(String principal) {
        PRINCIPAL.set(principal);
    }

    public static String principal() {
        String principal = PRINCIPAL.get();
        return principal == null || principal.isBlank() ? AuditPrincipal.ANONYMOUS : principal;
    }

    /**
     * 要写进 {@code owner} / {@code created_by} / {@code updated_by} 三列的取值：
     * 与 {@link #principal()} 的区别是**已按列宽归一化**（超长的记摘要而非截断）。
     *
     * <p>实体走 {@code touch(principal, now)} / {@code markCreated} 时内部已经归一化过，
     * 这个方法只给**绕开实体**的写入用——JPQL 的 {@code update ... set updated_by = :updatedBy}
     * 不经过持久化上下文，不触发任何实体回调，传什么进去就存什么
     * （见 {@code TableRepository#updateIcebergMetadata} 与 {@code ViewRepository#updateViewMetadata}）。
     * 这两处曾经直接传 {@link #principal()}，于是「令牌被当成主体名」时整次提交以
     * {@code Data too long for column 'updated_by'} 失败——而错误信息里看不出与认证有关。
     */
    public static String auditPrincipal() {
        return AuditPrincipal.of(principal());
    }

    /** 当前请求的关联 ID；没有 HTTP 请求的路径（调度器、优化回调之外的线程）为 {@code null}。 */
    public static String requestId() {
        return REQUEST_ID.get();
    }

    public static void setRequestId(String requestId) {
        REQUEST_ID.set(requestId);
    }

    public static void clear() {
        PRINCIPAL.remove();
        REQUEST_ID.remove();
    }

    public static long now() {
        return System.currentTimeMillis();
    }
}
