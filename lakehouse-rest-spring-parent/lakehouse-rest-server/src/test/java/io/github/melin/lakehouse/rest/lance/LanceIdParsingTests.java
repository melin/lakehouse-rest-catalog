package io.github.melin.lakehouse.rest.lance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.lance.support.LanceIds;
import io.github.melin.lakehouse.rest.support.LanceErrors;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code id} 解析的五条规则（{@code docs/lance-rest-catalog-design.md} §4.2）。
 *
 * <p>纯单测、不起 Spring：这些规则是「一个字符串怎么变成三级标识」，
 * 与容器、数据库都无关，起上下文只会让失败变慢、且更难定位到规则本身。
 *
 * <p>每条断言对应一个具体的故障，不是形状检查：
 * 客户端换了 delimiter 却全部 404、层级里混进斜杠造成路径穿越、
 * 路径与 body 不一致时服务端按另一个 id 执行却回 200。
 */
class LanceIdParsingTests {

    @Test
    void splitsOnTheDefaultDelimiter() {
        LanceIds.Id id = LanceIds.table("cat$sales$orders", null);
        assertEquals(List.of("cat", "sales", "orders"), id.levels());
        assertEquals("cat", id.catalog());
        assertEquals("sales", id.schema());
        assertEquals("orders", id.table());
    }

    @Test
    void honoursACustomDelimiter() {
        LanceIds.Id id = LanceIds.table("cat::sales::orders", "::");
        assertEquals(List.of("cat", "sales", "orders"), id.levels());
        // 客户端换成别的分隔符时，切分必须跟着换（$ 此时不再是层级边界）
        assertEquals(List.of("cat", "sales", "orders"),
                LanceIds.table("cat-sales-orders", "-").levels());
    }

    @Test
    void namespaceTakesOneOrTwoLevelsOnly() {
        assertEquals(List.of("cat"), LanceIds.namespace("cat", null).levels());
        assertEquals(List.of("cat", "sales"), LanceIds.namespace("cat$sales", null).levels());
        assertThrows(LanceErrors.LanceException.class, () -> LanceIds.namespace("cat$sales$orders", null));
    }

    @Test
    void tableRequiresExactlyThreeLevels() {
        assertThrows(LanceErrors.LanceException.class, () -> LanceIds.table("cat$sales", null));
        assertThrows(LanceErrors.LanceException.class,
                () -> LanceIds.table("cat$sales$orders$extra", null));
    }

    @Test
    void rejectsEmptyLevelsAndTrailingDelimiters() {
        for (String bad : List.of("cat$$orders", "$sales$orders", "cat$sales$")) {
            LanceErrors.LanceException failure =
                    assertThrows(LanceErrors.LanceException.class, () -> LanceIds.table(bad, null));
            assertEquals(LanceErrors.Code.INVALID_INPUT, failure.code());
            assertEquals(400, failure.status());
        }
    }

    @Test
    void rejectsEncodedSlashInsideOneLevel() {
        // 一段 id 里装两条路径：路径穿越的入口
        LanceErrors.LanceException failure = assertThrows(LanceErrors.LanceException.class,
                () -> LanceIds.table("cat$sales/../other$orders", null));
        assertEquals(LanceErrors.Code.INVALID_INPUT, failure.code());
    }

    @Test
    void rejectsCharactersOutsideTheWhitelist() {
        for (String bad : List.of("cat$sa les$orders", "cat$sa(les)$orders", "cat$sa/les$orders")) {
            assertThrows(LanceErrors.LanceException.class, () -> LanceIds.table(bad, null));
        }
        // 白名单内的字符必须能通过：连字符是表名里最常见的分隔符
        assertEquals("orders-2026", LanceIds.table("cat$sales$orders-2026", null).table());
    }

    @Test
    void rejectsAnEmptyId() {
        assertThrows(LanceErrors.LanceException.class, () -> LanceIds.table("", null));
        assertThrows(LanceErrors.LanceException.class, () -> LanceIds.namespace(null, null));
    }

    /**
     * body 里的 {@code id} 三种形状都要认，且「不一致」必须 400。
     *
     * <p>数组是**规范定义的形状**，也是真客户端（Spark connector）发的那个：
     * 服务端早先把它当字符串收，于是每个建库/建表请求都 400 {@code Malformed request}——
     * 这个用例就是为了让那次错位不再复发。拼接串是控制台与手写脚本发的，老形状不能丢。
     */
    @Test
    void bodyIdMustMatchThePathId() {
        // 三种「没意见」：缺键 / null / 空白串
        LanceIds.requireSameAsBody("cat$sales$orders", null, null);
        LanceIds.requireSameAsBody("cat$sales$orders", json("null"), null);
        LanceIds.requireSameAsBody("cat$sales$orders", json("\"  \""), null);

        // 拼接串：与路径同形就放行
        LanceIds.requireSameAsBody("cat$sales$orders", json("\"cat$sales$orders\""), null);
        // 数组：逐段比，与拼接串等价
        LanceIds.requireSameAsBody("cat$sales$orders", json("[\"cat\",\"sales\",\"orders\"]"), null);
        assertEquals(List.of("cat", "sales", "orders"),
                LanceIds.levelsOfBody(json("[\"cat\",\"sales\",\"orders\"]"), null));

        LanceErrors.LanceException mismatch = assertThrows(LanceErrors.LanceException.class,
                () -> LanceIds.requireSameAsBody("cat$sales$orders",
                        json("[\"cat\",\"sales\",\"other\"]"), null));
        assertEquals(LanceErrors.Code.INVALID_INPUT, mismatch.code());
        assertEquals(400, mismatch.status());

        // 数组里混进非字符串：这不是 id，不猜，直接拒
        LanceErrors.LanceException notString = assertThrows(LanceErrors.LanceException.class,
                () -> LanceIds.levelsOfBody(json("[\"cat\",1,\"orders\"]"), null));
        assertEquals(LanceErrors.Code.INVALID_INPUT, notString.code());

        // 数字、对象：同样不是 id
        assertThrows(LanceErrors.LanceException.class, () -> LanceIds.levelsOfBody(json("42"), null));
        assertThrows(LanceErrors.LanceException.class,
                () -> LanceIds.levelsOfBody(json("{\"id\":\"cat\"}"), null));

        // 全空白的数组等价于「没给」
        assertNull(LanceIds.levelsOfBody(json("[\"\",\" \"]"), null));
    }


    @Test
    void joinUsesTheDelimiterOfTheRequest() {
        LanceIds.Id id = LanceIds.table("cat$sales$orders", null);
        assertEquals("cat$sales$orders", id.join("$"));
        assertEquals("cat::sales::orders", id.join("::"));
        assertTrue(id.toString().contains("$"));
    }

    /** Jackson 3（服务端 DTO 用的那一版），这里只借它把 JSON 文本变成 {@link JsonNode}。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode json(String text) {
        return MAPPER.readTree(text);
    }
}
