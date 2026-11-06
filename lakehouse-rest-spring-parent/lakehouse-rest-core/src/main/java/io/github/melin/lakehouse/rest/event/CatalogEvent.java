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
 *
 * <p><b>属性里可以有空值，这是刻意的。</b>下面那条紧凑构造器曾经写成
 * {@code attributes == null ? Map.of() : Map.copyOf(attributes)}，而
 * {@link Map#copyOf} 与 {@link Map#of} 一样**拒绝空值**（抛 {@code NullPointerException}）。
 * 于是「往属性里放一个 null」不再是「这个属性没有值」，而是**一次变更失败**：
 * 打一条不带类型的标签（{@code typeCode} 为空，这是 {@code owner.team} 这类
 * 自由标签的正常形态）会让请求 500，而错误栈里只有 {@code ImmutableCollections}
 * 一行，看不出是标签的哪个字段。这与「属性用自由 JSON 承载」的初衷正相反。
 *
 * <p>因此构造器改成逐项过滤：<b>空值属性的语义是「这次事件没有这个键」</b>，
 * 与省略它等价。这条归一还有一个下游收益：DB 监听器把属性经
 * {@code ObjectMap} 序列化成一列 JSON，而序列化器（全局
 * {@code default-property-inclusion=non_null}）本来也会把 null 丢掉
 * ——在这里丢掉比在那里丢掉更早，也是唯一能让「事件对象本身」与
 * 「落库后的行」长得一样的地方（否则 {@code LoggingEventListener} 与
 * 订阅方拿到的 {@code attributes} 会比库里多几个 null 键）。
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
        attributes = attributes == null ? Map.of() : dropNullValues(attributes);
    }

    /**
     * 复制一份属性并丢掉空值项。
     *
     * <p>不用 {@code Map.copyOf} 的原因见类注释：它拒绝空值。排序也顺手做了——
     * {@code ObjectMap} 序列化成一列 JSON 时键序取决于遍历顺序，而
     * 「同一条事件在两次发布里字段顺序不同」会让基于文本的审计比对失效。
     * 用 {@link java.util.TreeMap} 得到稳定的键序，代价是属性个数只有个位数。
     */
    private static Map<String, Object> dropNullValues(Map<String, Object> attributes) {
        Map<String, Object> kept = new java.util.TreeMap<>();
        attributes.forEach((key, value) -> {
            if (key != null && value != null) {
                kept.put(key, value);
            }
        });
        return java.util.Collections.unmodifiableMap(kept);
    }

    /** 类别由类型决定，不单独存字段——两处声明早晚会不一致。 */
    public CatalogEventCategory category() {
        return type.category();
    }
}
