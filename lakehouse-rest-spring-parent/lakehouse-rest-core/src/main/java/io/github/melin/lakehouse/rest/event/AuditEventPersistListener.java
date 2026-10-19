package io.github.melin.lakehouse.rest.event;

import io.github.melin.lakehouse.rest.domain.entity.AuditEventEntity;
import io.github.melin.lakehouse.rest.domain.repo.AuditEventRepository;
import org.springframework.stereotype.Component;

/**
 * DB 监听器：把事件写进 {@code lakehouse_audit_event}，与触发它的业务变更同事务。
 *
 * <p><b>同事务是刻意的，不是实现偷懒。</b>发布点都在业务 Service 的事务方法里，
 * 这里的 insert 加入同一个事务：业务回滚，事件一起消失——事件流里没有「发生了
 * 又被撤销」的变更，消费方才敢按事件做增量（§3 规则 1、3 的交汇点）。
 * 代价是「事务最终提交前事件对外不可见」，对审计用途这无关紧要。
 *
 * <p>监听器自己不做 try/catch：与业务同事务的写入失败本来就该让变更失败
 * （存储打挂时宁可让请求 500，也不能装作记下来了）；发布器外层的兜底对它不起
 * 「吞掉」作用——同一事务标记回滚后业务照样失败。
 *
 * <p>启用开关是 {@code lakehouse.rest.event-listener.types}（写 {@code db}），
 * 订阅哪些类别/类型见 {@link EventListenerSettings}。
 */
@Component
public class AuditEventPersistListener implements CatalogEventListener {

    private final AuditEventRepository repository;

    public AuditEventPersistListener(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Override
    public String name() {
        return "db";
    }

    @Override
    public void onEvent(CatalogEvent event) {
        repository.save(AuditEventEntity.of(event));
    }
}
