package io.github.melin.lakehouse.rest.event;

import java.util.Map;

/**
 * 一条目录领域事件：已经发生的变更的事实记录。
 *
 * <p>固定列 + 开放属性（理由见 {@code docs/event-framework.md} §2）：
 * 两种表格式的同名事件携带的属性不同（Paimon 提交带 {@code snapshotId}，
 * Iceberg 带 {@code updates} 数），逐列建模会让「哪列属于哪种格式」变成
 * 第二张对照表要维护，因此属性用自由 JSON 承载。
 *
 * <p>{@code target} 是展示用定位符（{@code db.table}、授权资源、批次 id），
 * 由发布点拼好；它不是查询键——按目录与游标的增量拉取走实体的
 * {@code catalog} 与自增 {@code id}，见 {@code AuditEventRepository}。
 */
public record CatalogEvent(
        String eventId,
        CatalogEventType type,
        String catalog,
        String principal,
        String requestId,
        long occurredAt,
        String targetType,
        String target,
        Map<String, Object> attributes) {

    /** 目标定位层级：事件作用在什么粒度的对象上。 */
    public static final String TARGET_TABLE = "TABLE";
    public static final String TARGET_NAMESPACE = "NAMESPACE";
    public static final String TARGET_CATALOG = "CATALOG";
    public static final String TARGET_VIEW = "VIEW";
    public static final String TARGET_GRANT = "GRANT";
    public static final String TARGET_PRINCIPAL = "PRINCIPAL";
    public static final String TARGET_ROLE = "ROLE";
    public static final String TARGET_JOB = "JOB";

    public CatalogEvent {
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** 类别由类型决定，不单独存字段——两处声明早晚会不一致。 */
    public CatalogEventCategory category() {
        return type.category();
    }
}
