package io.github.melin.lakehouse.rest.event;

import io.github.melin.lakehouse.rest.config.RequestContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 事件发布器：服务层变更成功后经它发事件，监听链从这里出发。
 *
 * <p><b>发布点在 Service 层而不是 Controller 层。</b>同一变更可能有多个入口
 * （Paimon 的 {@code commit} 就有三个重载），入口各自发事件必然重复或遗漏；
 * 服务方法内部的收口处只有一个。发布点清单与「什么不发」见
 * {@code docs/event-framework.md} §1。
 *
 * <p><b>缺省值在这里补齐，不要求发布点猜。</b>{@code eventId} / {@code occurredAt} /
 * {@code principal} / {@code requestId} 都取自当前请求上下文（调度路径没有请求，
 * requestId 自然为空）。发布点只负责它才知道的信息：哪个 catalog、什么对象、
 * 带哪些属性。
 *
 * <p><b>逐个监听器单独兜底。</b>一个监听器抛异常只记 ERROR，其余监听器照常收到
 * 事件，业务请求不受影响——事件是业务的旁观者，不是参与者。唯一例外是 DB 监听器
 * 与业务同事务：事务回滚时它写的事件一起消失，这是设计要的性质（§3 规则 1）。
 *
 * <p><b>订阅过滤在这里统一做。</b>部署可以用 {@code paimon.rest.event-listener.types}
 * 只启用部分监听器、用 {@code <name>.enabled-event-categories} 让某个监听器只收一类事件
 * （例如日志只记 TABLE 与 GRANT）。监听器实现因此不需要各自解析配置，新加的实现自动
 * 获得同样的开关——见 {@link EventListenerSettings}。
 */
@Service
public class CatalogEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(CatalogEventPublisher.class);

    private final List<CatalogEventListener> listeners;
    private final EventListenerSettings settings;

    public CatalogEventPublisher(List<CatalogEventListener> listeners, EventListenerSettings settings) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.settings = settings;
    }

    /**
     * 发布一条事件。只在变更真正发生之后调用——空 updates、CAS 冲突、
     * 撤销不存在的授权这类「没有变化的操作」不进事件流（§3 规则 3）。
     */
    public void publish(CatalogEventType type, String catalog, String targetType, String target,
                        Map<String, Object> attributes) {
        if (listeners.isEmpty()) {
            return;
        }
        CatalogEvent event = new CatalogEvent(UUID.randomUUID().toString(), type,
                catalog == null ? "" : catalog,
                RequestContext.principal(), RequestContext.requestId(), RequestContext.now(),
                targetType, target == null ? "" : target, attributes);
        for (CatalogEventListener listener : listeners) {
            // 订阅过滤在这里而不是在各监听器里：监听器只管「收到事件做什么」，
            // 「该不该收到」是部署配置（types / enabled-event-categories），
            // 放在发布器上，新加的监听器自动获得同样的开关，不用自己实现一遍
            if (!settings.enabled(listener.name()) || !settings.accepts(listener.name(), type)) {
                continue;
            }
            try {
                listener.onEvent(event);
            } catch (RuntimeException exception) {
                log.error("event listener {} failed for {} on {}: {}",
                        listener.name(), type.wire(), target, exception.getMessage(), exception);
            }
        }
    }
}
