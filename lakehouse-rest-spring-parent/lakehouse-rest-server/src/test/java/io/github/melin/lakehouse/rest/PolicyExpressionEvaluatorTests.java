package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.PolicyExpressionEvaluator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 服务端 AST 求值器的单元行为（设计 §6 P1——这套设计里唯一一处双实现，本类是
 * 与 Spark 侧 {@code PolicyExpressionBuilder} 的**语义对齐锚**：这里每条断言的
 * 期望值都按 Catalyst 语义写，漂移即测试红）。
 *
 * <p>分组与白名单一一对应：九种二元算子与三值逻辑、四种一元/列表算子、
 * 十六个函数、八个标准 mask、{@code $principal} 防绕过、列收集。
 */
class PolicyExpressionEvaluatorTests {

    // ------------------------------------------------------------------ 行工具

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static Map<String, Object> col(String name) {
        return Map.of("col", name);
    }

    private static Map<String, Object> lit(Object value) {
        Map<String, Object> out = new HashMap<>();
        out.put("lit", value);
        return out;
    }

    private static Map<String, Object> op(String op, Object left, Object right) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("op", op);
        out.put("left", left);
        out.put("right", right);
        return out;
    }

    /** 一元算子（not / is_null / is_not_null）：操作数挂在 {@code operand} 上。 */
    private static Map<String, Object> op(String op, Object operand) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("op", op);
        out.put("operand", operand);
        return out;
    }

    private static Map<String, Object> fn(String fn, Object... args) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fn", fn);
        out.put("args", List.of(args));
        return out;
    }

    private static Map<String, Object> coalesceFalse(Object inner) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fn", "coalesce");
        out.put("args", List.of(inner, lit(Boolean.FALSE)));
        return out;
    }

    // ------------------------------------------------------------------ 比较与三值逻辑

    @Test
    void comparisonsPromoteNumericTypes() {
        Map<String, Object> row = row("i", 10, "l", 10L, "d", 10.0, "dec", new BigDecimal("10.00"));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("i"), lit(10L))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("l"), col("d"))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("dec"), col("i"))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("gte", col("i"), lit(9.5))), row));
        assertFalse(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("lt", col("i"), lit(10))), row));
    }

    @Test
    void comparisonsOnDatesAndBooleans() {
        Map<String, Object> row = row("day", LocalDate.of(2026, 10, 24),
                "ts", LocalDateTime.of(2026, 10, 24, 9, 0), "flag", Boolean.TRUE);
        // 日期列 vs 字符串字面量：字面量按列的类型解释（引擎 coercePair 语义）
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("day"), lit("2026-10-24"))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("lt", col("day"), lit("2026-10-25"))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("gte", col("ts"), lit("2026-10-24 09:00:00"))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("flag"), lit(Boolean.TRUE))), row));
    }

    @Test
    void nullIsUnknownAndCoalesceFoldsItToInvisible() {
        Map<String, Object> row = row("x", null);
        // NULL = 任何值都是未知；顶层 coalesce(expr, false) 把未知折成「不可见」
        assertFalse(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("eq", col("x"), lit(1))), row));
        assertFalse(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("neq", col("x"), lit(1))), row));
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("is_null", col("x"))), row));
        assertFalse(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("is_not_null", col("x"))), row));
    }

    @Test
    void andOrNotFollowThreeValuedLogic() {
        Map<String, Object> row = row("a", Boolean.TRUE, "b", null, "c", Boolean.FALSE);
        Map<String, Object> unknown = op("eq", col("b"), lit(1));
        // FALSE AND 未知 = FALSE；TRUE AND 未知 = 未知；TRUE OR 未知 = TRUE
        assertEquals(false, threeValued(op("and", op("eq", col("c"), lit(true)), unknown), row));
        assertNull(threeValued(op("and", op("eq", col("a"), lit(true)), unknown), row));
        assertEquals(true, threeValued(op("or", op("eq", col("a"), lit(true)), unknown), row));
        assertNull(threeValued(op("not", unknown), row));
        assertEquals(false, threeValued(op("not", op("eq", col("c"), lit(false))), row));
    }

    private static Object threeValued(Object ast, Map<String, Object> row) {
        // 直接求值（不包 coalesce），看三值逻辑本身
        return PolicyExpressionEvaluator.eval(ast, row);
    }

    @Test
    void likeUsesSqlWildcardsWithBackslashEscape() {
        Map<String, Object> row = row("s", "a_c100%", "t", "aXc100Z");
        // 反斜杠转义后 _ 与 % 都是字面量：只有 s（真含下划线与百分号）命中
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("like", col("s"), lit("a\\_c100\\%"))), row));
        assertFalse(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("like", col("t"), lit("a\\_c%"))), row));
        // 不转义时是通配符语义
        assertTrue(PolicyExpressionEvaluator.evalRowFilter(
                coalesceFalse(op("like", col("t"), lit("a_c%"))), row));
        assertNull(PolicyExpressionEvaluator.eval(op("like", col("missing"), lit("x")),
                row("s", "x")));
    }

    @Test
    void inMatchesAnyLiteralAndNullIsUnknown() {
        Map<String, Object> ast = Map.of("op", "in", "operand", col("r"),
                "values", List.of(lit("CN"), lit("APAC")));
        assertTrue((Boolean) PolicyExpressionEvaluator.eval(ast, row("r", "APAC")));
        assertEquals(false, PolicyExpressionEvaluator.eval(ast, row("r", "US")));
        assertNull(PolicyExpressionEvaluator.eval(ast, row("r", null)));
    }

    // ------------------------------------------------------------------ 函数

    @Test
    void substrFollowsSparkSemantics() {
        Map<String, Object> row = row("s", "abcdef");
        assertEquals("bcd", PolicyExpressionEvaluator.eval(fn("substr", col("s"), lit(2), lit(3)), row));
        assertEquals("abc", PolicyExpressionEvaluator.eval(fn("substr", col("s"), lit(0), lit(3)), row));
        // 负起点从串尾数；-2 起点是 "ef"
        assertEquals("ef", PolicyExpressionEvaluator.eval(fn("substr", col("s"), lit(-2)), row));
        // 负起点越过串头且用不满长度：空串（Spark Substring 的边界）
        assertEquals("", PolicyExpressionEvaluator.eval(fn("substr", col("s"), lit(-10), lit(2)), row));
        assertEquals("", PolicyExpressionEvaluator.eval(fn("substr", col("s"), lit(2), lit(0)), row));
        assertEquals("", PolicyExpressionEvaluator.eval(fn("substr", col("s"), lit(9), lit(3)), row));
        assertNull(PolicyExpressionEvaluator.eval(fn("substr", lit(null), lit(1), lit(1)), row));
    }

    @Test
    void concatIsNullPropagatingAndStringifiesArguments() {
        Map<String, Object> row = row("a", "x", "n", 7, "day", LocalDate.of(2026, 10, 24));
        assertEquals("x7", PolicyExpressionEvaluator.eval(
                fn("concat", col("a"), col("n")), row));
        assertEquals("x2026-10-24", PolicyExpressionEvaluator.eval(
                fn("concat", col("a"), col("day")), row));
        assertNull(PolicyExpressionEvaluator.eval(fn("concat", col("a"), lit(null)), row));
    }

    @Test
    void regexpReplaceReplacesAllMatches() {
        Map<String, Object> row = row("s", "a1b22c333");
        assertEquals("a-b-c-", PolicyExpressionEvaluator.eval(
                fn("regexp_replace", col("s"), lit("[0-9]+"), lit("-")), row));
        assertNull(PolicyExpressionEvaluator.eval(
                fn("regexp_replace", lit(null), lit("[0-9]+"), lit("-")), row));
    }

    @Test
    void upperLowerTrimMd5Sha2() {
        Map<String, Object> row = row("s", "  MiXeD  ");
        assertEquals("  MIXED  ", PolicyExpressionEvaluator.eval(fn("upper", col("s")), row));
        assertEquals("  mixed  ", PolicyExpressionEvaluator.eval(fn("lower", col("s")), row));
        assertEquals("MiXeD", PolicyExpressionEvaluator.eval(fn("trim", col("s")), row));
        // md5("abc") = 900150983cd24fb0d6963f7d28e17f72（与 Spark md5 一致，小写十六进制）
        assertEquals("900150983cd24fb0d6963f7d28e17f72",
                PolicyExpressionEvaluator.eval(fn("md5", lit("abc")), row));
        // sha2("abc", 256) = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PolicyExpressionEvaluator.eval(fn("sha2", lit("abc"), lit(256)), row));
    }

    @Test
    void dateTruncKeepsInputType() {
        Map<String, Object> row = row("ts", LocalDateTime.of(2026, 10, 24, 9, 30, 45, 1),
                "day", LocalDate.of(2026, 10, 24));
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0),
                PolicyExpressionEvaluator.eval(fn("date_trunc", lit("year"), col("ts")), row));
        // quarter：10 月在 Q4 → 10-01
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0),
                PolicyExpressionEvaluator.eval(fn("date_trunc", lit("quarter"), col("ts")), row));
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0),
                PolicyExpressionEvaluator.eval(fn("date_trunc", lit("month"), col("ts")), row));
        // 2026-10-24 是周六 → week 截到周一 10-19
        assertEquals(LocalDateTime.of(2026, 10, 19, 0, 0),
                PolicyExpressionEvaluator.eval(fn("date_trunc", lit("week"), col("ts")), row));
        assertEquals(LocalDateTime.of(2026, 10, 24, 9, 0),
                PolicyExpressionEvaluator.eval(fn("date_trunc", lit("hour"), col("ts")), row));
        // DATE 进 DATE 出（引擎 TruncDate 按输入类型返回同类结果）
        assertEquals(LocalDate.of(2026, 10, 1),
                PolicyExpressionEvaluator.eval(fn("date_trunc", lit("month"), col("day")), row));
        assertNull(PolicyExpressionEvaluator.eval(fn("date_trunc", lit("month"), lit(null)), row));
    }

    @Test
    void yearMonthToDateFormat() {
        Map<String, Object> row = row("ts", LocalDateTime.of(2026, 10, 24, 9, 0),
                "text", "2026-10-24");
        assertEquals(2026L, PolicyExpressionEvaluator.eval(fn("year", col("ts")), row));
        assertEquals(10L, PolicyExpressionEvaluator.eval(fn("month", col("ts")), row));
        assertEquals(LocalDate.of(2026, 10, 24), PolicyExpressionEvaluator.eval(
                fn("to_date", col("text")), row));
        assertEquals(LocalDate.of(2026, 10, 24), PolicyExpressionEvaluator.eval(
                fn("to_date", col("text"), lit("yyyy-MM-dd")), row));
        // 解析失败按 Spark 语义返回 NULL，不抛错
        assertNull(PolicyExpressionEvaluator.eval(
                fn("to_date", lit("not-a-date"), lit("yyyy-MM-dd")), row));
        assertEquals("2026-10", PolicyExpressionEvaluator.eval(
                fn("date_format", col("ts"), lit("yyyy-MM")), row));
    }

    @Test
    void coalesceCaseWhenCast() {
        Map<String, Object> row = row("a", null, "b", "fb", "n", 5, "s", "region-CN");
        Map<String, Object> coalesce = Map.of("fn", "coalesce",
                "args", List.of(col("a"), col("b"), lit("z")));
        assertEquals("fb", PolicyExpressionEvaluator.eval(coalesce, row));

        Map<String, Object> caseWhen = new LinkedHashMap<>();
        caseWhen.put("fn", "case_when");
        caseWhen.put("branches", List.of(
                Map.of("when", op("eq", col("n"), lit(5)), "then", lit("five")),
                Map.of("when", op("eq", col("n"), lit(6)), "then", lit("six"))));
        caseWhen.put("else", lit("other"));
        assertEquals("five", PolicyExpressionEvaluator.eval(caseWhen, row));

        Map<String, Object> noMatch = new LinkedHashMap<>(caseWhen);
        noMatch.put("branches", List.of(Map.of("when", op("eq", col("n"), lit(6)),
                "then", lit("six"))));
        assertEquals("other", PolicyExpressionEvaluator.eval(noMatch, row));
        // 没有 else 且全不命中 → NULL（与引擎 CaseWhen 一致）
        Map<String, Object> noElse = Map.of("fn", "case_when", "branches",
                List.of(Map.of("when", op("eq", col("n"), lit(6)), "then", lit("six"))));
        assertNull(PolicyExpressionEvaluator.eval(noElse, row));

        assertEquals("region-CN", PolicyExpressionEvaluator.eval(
                Map.of("fn", "cast", "operand", col("s"), "to", "STRING"), row));
        assertEquals(5L, PolicyExpressionEvaluator.eval(
                Map.of("fn", "cast", "operand", col("n"), "to", "BIGINT"), row));
    }

    // ------------------------------------------------------------------ 标准脱敏集

    @Test
    void maskAlphanumIsPerCharacterLikeHiveMask() {
        Map<String, Object> row = row("phone", "Abc-138_0066");
        assertEquals("Xxx-nnn_nnnn",
                PolicyExpressionEvaluator.evalMask(Map.of("fn", "mask_alphanum"), "phone", row));
        // Unicode（汉字）不在字符类里，原样保留——逐字符而不是正则整段 replace
        Map<String, Object> cn = row("name", "张三Abc123");
        assertEquals("张三Xxxnnn",
                PolicyExpressionEvaluator.evalMask(Map.of("fn", "mask_alphanum"), "name", cn));
        assertNull(PolicyExpressionEvaluator.evalMask(Map.of("fn", "mask_alphanum"), "phone",
                row("phone", null)));
    }

    @Test
    void showFirstNAndShowLastNKeepEdgeCases() {
        Map<String, Object> row = row("phone", "13800000001");
        assertEquals("138nnnnnnnn", PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "show_first_n", "args", List.of(3)), "phone", row));
        assertEquals("nnnnnnnn001", PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "show_last_n", "args", List.of(3)), "phone", row));
        // len < n：前缀 maskUpTo = 0 → 全串保留（show_first_n）；show_last_n 同理
        Map<String, Object> shortRow = row("phone", "138");
        assertEquals("138", PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "show_first_n", "args", List.of(9)), "phone", shortRow));
        assertEquals("138", PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "show_last_n", "args", List.of(9)), "phone", shortRow));
        assertNull(PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "show_first_n", "args", List.of(3)), "phone", row("phone", null)));
    }

    @Test
    void fixedValueNullReplacementAndHash() {
        Map<String, Object> row = row("phone", "13800000001", "name", "alice");
        assertEquals("REDACTED", PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "mask_to_fixed_value", "args", List.of("REDACTED")), "phone", row));
        assertNull(PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "replace_with_null"), "phone", row));
        // hash 与引擎一致：Spark md5 / sha2 的小写十六进制
        assertEquals("900150983cd24fb0d6963f7d28e17f72", PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "hash", "args", List.of("MD5")), "name", row("name", "abc")));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                PolicyExpressionEvaluator.evalMask(
                        Map.of("fn", "hash", "args", List.of("SHA-256")), "name", row("name", "abc")));
    }

    @Test
    void truncateMasksPreserveDateAndTimestampTypes() {
        Map<String, Object> row = row("day", LocalDate.of(2026, 10, 24),
                "ts", LocalDateTime.of(2026, 10, 24, 9, 30));
        assertEquals(LocalDate.of(2026, 1, 1), PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "truncate_to_year"), "day", row));
        assertEquals(LocalDate.of(2026, 10, 1), PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "truncate_to_month"), "day", row));
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), PolicyExpressionEvaluator.evalMask(
                Map.of("fn", "truncate_to_year"), "ts", row));
        assertNull(PolicyExpressionEvaluator.evalMask(Map.of("fn", "truncate_to_year"), "day",
                row("day", null)));
    }

    @Test
    void escapeHatchMaskEvaluatesTheExpression() {
        // 单元格级语义（§5.3.5）：mask 参数引用其它列，读到的是原值
        Map<String, Object> ast = Map.of("expr", Map.of(
                "fn", "case_when",
                "branches", List.of(Map.of(
                        "when", op("eq", col("region"), lit("US")),
                        "then", col("name"))),
                "else", lit("HIDDEN")));
        Map<String, Object> us = row("name", "alice", "region", "US");
        Map<String, Object> cn = row("name", "bob", "region", "CN");
        assertEquals("alice", PolicyExpressionEvaluator.evalMask(ast, "name", us));
        assertEquals("HIDDEN", PolicyExpressionEvaluator.evalMask(ast, "name", cn));
    }

    // ------------------------------------------------------------------ 防绕过与列收集

    @Test
    void unfoldedPrincipalFailsClosed() {
        Map<String, Object> ast = op("eq", col("owner"), Map.of("principal", true));
        ApiException exception = assertThrows(ApiException.class,
                () -> PolicyExpressionEvaluator.eval(ast, row("owner", "alice")));
        assertTrue(exception.getMessage().contains("folded"),
                "占位符必须报错而不是按调用者本地解释：" + exception.getMessage());
    }

    @Test
    void collectColumnsWalksTheWholeTree() {
        Map<String, Object> ast = coalesceFalse(op("and",
                op("eq", col("region"), lit("CN")),
                op("gt", fn("year", col("created_at")), lit(2020))));
        assertEquals(Set.of("region", "created_at"), PolicyExpressionEvaluator.collectColumns(ast));
    }
}
