package io.github.melin.lakehouse.rest.event;

import io.github.melin.lakehouse.rest.service.LineageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 表被删除时级联删掉它的血缘边（{@code docs/lineage-design.md} §7.5）。
 *
 * <p><b>为什么这件事必须挂在事件上。</b>边表不按时间清——它是「现在谁依赖谁」的状态，
 * 没有过期概念。表删了边还在，图上就会出现一张点不开的节点，而且会一直挂着。
 * 唯一的清理时机就是「删除」这一刻，而这正是 {@link CatalogEventListener} 的位置
 * （{@code AuditEventPersistListener} 的邻居）。
 *
 * <p><b>只删边，不删 run。</b>run 是历史事实：「这张表当年被谁写过」在删表之后仍然有价值，
 * 审计与排障要看的正是它。这与「边是状态、run 是事件」的划分是同一条线（§7.1）。
 *
 * <p><b>监听器名 {@code lineage} 可以单独关掉</b>
 * （{@code lakehouse.rest.event-listener.lineage.enabled=false}）：关掉之后删表不再
 * 顺手清边，孤儿边会留在图上（标 {@code stale}），不会报错。之所以给这个开关，
 * 是因为清理本身是一次 DELETE——运维在排查「删表为什么变慢」时应该能把它单独摘掉。
 *
 * <p>失败只记 ERROR（发布器兜底），删除表本身不受影响：血缘是元数据，删表是生产操作，
 * 这条优先级与 §6.6 一致。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LineageEdgeCleanupListener implements CatalogEventListener {

    /** 监听器名：配置里的键是 {@code lakehouse.rest.event-listener.lineage.*}。 */
    public static final String NAME = "lineage";

    private final LineageService lineageService;

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public void onEvent(CatalogEvent event) {
        if (event.type() != CatalogEventType.TABLE_DROPPED
                && event.type() != CatalogEventType.TABLE_UNREGISTERED) {
            return;
        }
        if (!CatalogEvent.TARGET_TABLE.equals(event.targetType())) {
            return;
        }
        String ref = ref(event);
        if (ref == null) {
            return;
        }
        try {
            lineageService.dropEdges(ref);
        } catch (RuntimeException exception) {
            // 边清不掉不该让删表失败；下一次同名表出现时旧边会被 run_count 继续累加，
            // 因此这不是「静默丢数据」，而是「图上多了一条待清理的边」
            log.error("failed to drop lineage edges of {}: {}", ref, exception.getMessage(), exception);
        }
    }

    /**
     * 事件 → ref。
     *
     * <p>{@code target} 是 {@code db.table}（发布点拼好的展示用定位符），
     * 加上 catalog 才是血缘的键。外部表（{@code register} 进来、catalog 里没有）也走这条路：
     * 它们的边同样以三段名存放在边表里。
     *
     * <p>多级 namespace 的表在事件里同样拼成 {@code a.b.table}，因此这里不做
     * 「正好三段」的假设——直接拼就对了。
     */
    private static String ref(CatalogEvent event) {
        String target = event.target();
        if (target == null || target.isBlank()) {
            return null;
        }
        String catalog = event.catalog();
        if (catalog == null || catalog.isBlank()) {
            return target;
        }
        return catalog + "." + target;
    }
}
