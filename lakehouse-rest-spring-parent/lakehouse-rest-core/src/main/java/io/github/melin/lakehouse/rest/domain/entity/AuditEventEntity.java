package io.github.melin.lakehouse.rest.domain.entity;

import io.github.melin.lakehouse.rest.event.CatalogEvent;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import io.github.melin.lakehouse.rest.domain.entity.JsonConverters.ObjectMap;
import jakarta.persistence.Convert;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * 目录事件：一行一条，只插入、从不更新。
 *
 * <p>事件是事实记录，「改写历史」本身就是错误信号，因此它<b>不继承
 * {@link AuditedEntity}</b>——那套 {@code updatedBy} / {@code updatedAt} 的更新语义
 * 在这里没有意义，列集是自己的（结构见 {@code docs/event-framework.md} §2）。
 *
 * <p><b>{@code id} 是增量拉取的游标，不是 {@code eventId}。</b>UUID 无序，
 * 按它分页不稳定；自增号的顺序即写入顺序。{@code (catalog, id)} 的联合索引
 * 支撑「按目录增量拉」这条最高频的查询。
 *
 * <p>{@code category} 是 {@code type} 的冗余列（由事件类型决定），存下来是为了让
 * 「只看权限变更」这类过滤走索引，而不是在 SQL 里列一长串 wire 值做 {@code IN}。
 * 冗余的代价是两者必须同步——同步点在 {@link #of}，只有这一个入口。
 */
@Entity
@Table(name = "lakehouse_audit_event",
        indexes = {
                @Index(name = "ix_audit_event_catalog", columnList = "catalog, id"),
                @Index(name = "ix_audit_event_type", columnList = "type"),
                @Index(name = "ix_audit_event_category", columnList = "category, id")
        })
@Getter
@Setter
public class AuditEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false)
    private Long id;

    @Column(name = "event_id", length = 64, nullable = false)
    private String eventId;

    @Column(name = "type", length = 64, nullable = false)
    private String type;

    /** 事件类别（{@code TABLE} / {@code GRANT} …），由 {@code type} 决定，见类注释。 */
    @Column(name = "category", length = 32, nullable = false)
    private String category;

    @Column(name = "catalog", length = 255, nullable = false)
    private String catalog;

    @Column(name = "principal", length = 255, nullable = false)
    private String principal;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Long occurredAt;

    @Column(name = "target_type", length = 32, nullable = false)
    private String targetType;

    @Column(name = "target", length = 1024, nullable = false)
    private String target;

    @Convert(converter = ObjectMap.class)
    @Column(name = "attributes_json", columnDefinition = "text")
    private Map<String, Object> attributes;

    /** 从事件构造实体。principal 的归一化也在这里收口，发布点不用管列宽。 */
    public static AuditEventEntity of(CatalogEvent event) {
        AuditEventEntity entity = new AuditEventEntity();
        entity.setEventId(event.eventId());
        entity.setType(event.type().wire());
        entity.setCategory(event.category().name());
        entity.setCatalog(event.catalog());
        entity.setPrincipal(io.github.melin.lakehouse.rest.support.AuditPrincipal.of(event.principal()));
        entity.setRequestId(event.requestId());
        entity.setOccurredAt(event.occurredAt());
        entity.setTargetType(event.targetType());
        entity.setTarget(event.target());
        entity.setAttributes(event.attributes());
        return entity;
    }
}
