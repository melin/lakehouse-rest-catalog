package io.github.melin.lakehouse.rest.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 日志监听器：一行 INFO 摘要进应用日志。
 *
 * <p>与 DB 监听器并存的理由：日志有它自己的生命周期（滚动、采集、告警），
 * 运维在不上控制台、不查库的情况下也能从应用日志里看到「谁动了目录」。
 * 一行一条、固定字段顺序，便于 grok / 采集器解析；attributes 只挑
 * {@code format} 与 {@code state} 两个最常用的键进日志，完整的看库。
 */
@Component
@ConditionalOnProperty(prefix = "paimon.rest.event", name = "log-enabled",
        havingValue = "true", matchIfMissing = true)
public class LoggingEventListener implements CatalogEventListener {

    private static final Logger log = LoggerFactory.getLogger("catalog.event");

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void onEvent(CatalogEvent event) {
        Object format = event.attributes().get("format");
        Object state = event.attributes().get("state");
        log.info("event={} catalog={} target=[{}] {} principal={} requestId={} format={} state={}",
                event.type().wire(), event.catalog(), event.targetType(), event.target(),
                event.principal(), event.requestId(), format, state);
    }
}
