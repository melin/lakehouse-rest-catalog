package io.github.melin.lakehouse.rest.event;

/**
 * 事件监听器：对一条已发生的目录变更做出反应。
 *
 * <p>实现方以 Spring bean 注册，{@code CatalogEventPublisher} 收集全部实现逐个分发；
 * 加一种消费方式（Kafka、Webhook）就是加一个实现类加自己的配置开关，发布侧零改动。
 *
 * <p><b>实现方自行保证投递语义与顺序。</b>框架只承诺「变更成功后、业务事务提交前
 * 同步调用一次」；要不要异步、失败怎么重试、事件是否可能重复，都由实现自己决定
 * 并在自己的文档里声明——发布侧不背这些复杂度（取舍见 {@code docs/event-framework.md} §3）。
 *
 * <p><b>监听器失败不影响业务。</b>发布器会捕获并只记 ERROR；但 DB 监听器刻意
 * 不吃自己的异常之外的靠山——它与业务同事务，变更回滚事件就不存在，这是想要的性质。
 */
public interface CatalogEventListener {

    /** 监听器名：配置开关与日志里用它指代（{@code db} / {@code log}）。 */
    String name();

    /** 处理一条事件。实现不应抛出异常到发布器之外（发布器会兜住，但那是最后防线）。 */
    void onEvent(CatalogEvent event);
}
