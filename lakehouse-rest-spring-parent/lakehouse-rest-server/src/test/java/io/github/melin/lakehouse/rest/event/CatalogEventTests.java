package io.github.melin.lakehouse.rest.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link CatalogEvent} 属性归一化的语义测试。
 *
 * <p><b>这一条守的是一个已经真实发生过的 500。</b>{@code attributes} 曾经经
 * {@code Map.copyOf} 复制，而它（与 {@code Map.of} 一样）**拒绝空值**。于是
 * {@code ColumnTagService.upsert} 里那句 {@code attributes.put("typeCode", typeCode)}
 * （标签不带类型时 {@code typeCode} 为 null，而 {@code owner.team} 这类自由标签
 * 就是这个形态）会让**整个打标请求 500**，错误栈里只有 {@code ImmutableCollections}
 * 一行，看不出是标签的哪个字段。发现它的是一条 HTTP 层用例，
 * 而不是任何一条针对「发布事件」的用例——因为此前根本没有。
 *
 * <p>因此这里把「空值属性的语义」钉死：**「这次事件没有这个键」，与省略它等价**，
 * 而不是「变更失败」。顺带钉住键序稳定（属性会序列化成一列 JSON，键序漂移会让
 * 基于文本的审计比对失效）。
 */
class CatalogEventTests {

    @Test
    void nullAttributesBecomeAnEmptyMap() {
        assertTrue(event(null).attributes().isEmpty());
    }

    @Test
    void nullValuesAreDroppedInsteadOfRejected() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("column", "team");
        attributes.put("tagKey", "owner.team");
        // 这两行就是 ColumnTagService 在「不带类型的标签」上的写法，
        // 也正是曾经抛 NullPointerException 的两行
        attributes.put("typeCode", null);
        attributes.put("level", null);

        CatalogEvent event = event(attributes);

        assertEquals("team", event.attributes().get("column"));
        assertEquals("owner.team", event.attributes().get("tagKey"));
        assertFalse(event.attributes().containsKey("typeCode"),
                "空值的语义是「没有这个键」，不是「值是 null」");
        assertFalse(event.attributes().containsKey("level"));
        assertEquals(2, event.attributes().size());
    }

    @Test
    void aNullKeyIsDroppedToo() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("table", "people");
        attributes.put(null, "orphan");

        assertEquals(Map.of("table", "people"), event(attributes).attributes());
    }

    @Test
    void attributesAreImmutable() {
        CatalogEvent event = event(Map.of("table", "people"));

        assertThrows(UnsupportedOperationException.class,
                () -> event.attributes().put("table", "orders"));
    }

    @Test
    void keysAreOrderedSoTheStoredJsonIsStable() {
        // 逆序放进来的键，取出来必须是排好序的：属性会经 ObjectMap 落成一列 JSON，
        // 而「同一条事件在两次发布里字段顺序不同」会让基于文本的审计比对失效
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("table", "people");
        attributes.put("column", "id_card");
        attributes.put("change", "CREATED");

        assertEquals(java.util.List.of("change", "column", "table"),
                java.util.List.copyOf(event(attributes).attributes().keySet()));
    }

    private static CatalogEvent event(Map<String, Object> attributes) {
        return new CatalogEvent("evt-1", CatalogEventType.COLUMN_TAG_CHANGED, "paimon",
                "tester", null, 1_700_000_000_000L,
                CatalogEvent.TARGET_TABLE, "sens_db.people", attributes);
    }
}
