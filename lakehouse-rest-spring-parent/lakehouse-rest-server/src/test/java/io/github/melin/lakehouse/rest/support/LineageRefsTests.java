package io.github.melin.lakehouse.rest.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.support.ApiException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * {@link LineageRefs} 的用例：点号拼接的解析规则与 {@code ~} 保留前缀。
 *
 * <p>这三条规则各自都能静默出错：
 * 多级 namespace 按数组下标取会把表名当库名（边连到不存在的资源上，图照画不误）、
 * {@code ~} 前缀漏判会让外部节点去查目录（图上少一个节点，日志里多一条 404）、
 * 空段漏判会拼出一个空 namespace（同一个空库里混进所有拼接错误的表）。
 */
class LineageRefsTests {

    @Test
    void parsesCatalogNamespaceAndTable() {
        LineageRefs.Parsed parsed = LineageRefs.parse("paimon.dw.orders").orElseThrow();
        assertEquals("paimon", parsed.catalog());
        assertEquals(List.of("dw"), parsed.namespace());
        assertEquals("orders", parsed.table());
        assertEquals("paimon.dw.orders", parsed.ref());
    }

    @Test
    void multiLevelNamespaceStaysOneNamespace() {
        // Iceberg 的多级 namespace 是点号拼的一段路径：中间全算 namespace，不能按下标取
        LineageRefs.Parsed parsed = LineageRefs.parse("iceberg.lake.dwd.fact_orders").orElseThrow();
        assertEquals("iceberg", parsed.catalog());
        assertEquals(List.of("lake", "dwd"), parsed.namespace());
        assertEquals("fact_orders", parsed.table());
    }

    @Test
    void twoSegmentsAreAcceptedWithEmptyNamespace() {
        // 只有 catalog 与表名：解析得出来，namespace 为空（目录查询会因此查不到表，
        // 但那条边仍然画得出来——比丢掉它更接近事实）
        LineageRefs.Parsed parsed = LineageRefs.parse("paimon.orders").orElseThrow();
        assertEquals("paimon", parsed.catalog());
        assertTrue(parsed.namespace().isEmpty());
        assertEquals("orders", parsed.table());
    }

    @Test
    void unresolvableRefsAreEmpty() {
        assertTrue(LineageRefs.parse(null).isEmpty());
        assertTrue(LineageRefs.parse("").isEmpty());
        assertTrue(LineageRefs.parse("   ").isEmpty());
        // 一段：定位不到 catalog
        assertTrue(LineageRefs.parse("orders").isEmpty());
        // 空段：`a..b` 是拼接错误，不要猜一个空 namespace
        assertTrue(LineageRefs.parse("paimon..orders").isEmpty());
        assertTrue(LineageRefs.parse("paimon.dw.").isEmpty());
    }

    @Test
    void externalRefsAreNotParsed() {
        for (String ref : List.of("~values", "~temp:abc", "~file:8c1f", "~external:mysql")) {
            assertTrue(LineageRefs.isExternal(ref), ref);
            assertTrue(LineageRefs.parse(ref).isEmpty(), ref);
        }
        assertFalse(LineageRefs.isExternal(null));
        assertFalse(LineageRefs.isExternal(""));
        assertFalse(LineageRefs.isExternal("paimon.dw.orders"));
    }

    @Test
    void refsKeepCaseAsIs() {
        // 折叠规则属于会话配置（spark.sql.caseSensitive），服务端再折一次等于替引擎做决定
        Optional<LineageRefs.Parsed> parsed = LineageRefs.parse("Paimon.DW.Orders");
        assertEquals("Orders", parsed.orElseThrow().table());
    }

    @Test
    void reservedPrefixIsRejectedForRealNames() {
        ApiException failure = assertThrows(ApiException.class,
                () -> LineageRefs.requireNotReserved("~values", "table"));
        assertEquals(400, failure.getStatus());
        // 普通名字照常通过
        LineageRefs.requireNotReserved("orders", "table");
        LineageRefs.requireNotReserved(null, "table");
        // 前缀只在**开头**才危险：名字里带 ~ 不会与伪节点撞名
        LineageRefs.requireNotReserved("sales~2024", "table");
    }

    @Test
    void ofBuildsTheSameShapeParseReads() {
        String ref = LineageRefs.of("paimon", "dw", "orders");
        assertEquals("paimon.dw.orders", ref);
        assertEquals(ref, LineageRefs.parse(ref).orElseThrow().ref());
    }
}
