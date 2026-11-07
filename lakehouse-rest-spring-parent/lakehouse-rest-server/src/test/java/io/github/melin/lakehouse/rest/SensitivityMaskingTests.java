package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.scan.SensitivityMasking;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 采样值的打码（设计见 {@code docs/sensitive-data-design.md} §5.4）。
 *
 * <p><b>这是整条识别链路里唯一的不变量</b>：任何要落库、进日志、进事件或回给调用方的
 * 样本，都必须先过 {@link SensitivityMasking#mask}。因此这里的断言不是「格式化函数
 * 好不好看」，而是「原值有没有可能以任何形状漏出去」——它一旦出错，落库的
 * {@code masked_samples_json} 就是一张明文身份证表，而那张表会被备份、被导出、
 * 被同步到测试环境。
 *
 * <p>分三组：形状（头 3 尾 4）→ 降级（短值）→ 批量（上限与顺序）。
 */
class SensitivityMaskingTests {

    // ------------------------------------------------------------------ 形状

    /**
     * 长值保留头 3 尾 4，中间全星号，且**长度与原值一致**。
     *
     * <p>长度一致是为了让「这是不是一个 18 位身份证」这件事仍然看得出来——
     * 人审要判的正是这个。头部 3 位让人认出「110 开头的北京号」，尾部 4 位让人确认
     * 「与业务系统里看到的是同一批数据」；中间的出生日期与顺序码全变星号，
     * 于是这一串在库里既不构成一条可用身份信息，又仍然支撑判定。
     */
    @Test
    void longValuesKeepHeadAndTailOnly() {
        String idCard = "110101199003071233";
        String masked = SensitivityMasking.mask(idCard);

        assertEquals("110***********1233", masked);
        assertEquals(idCard.length(), masked.length(), "打码不改变长度");
        assertEquals(18 - 3 - 4, masked.chars().filter(ch -> ch == '*').count(),
                "中间 11 个字符全部变成星号");
        assertFalse(masked.contains("19900307"),
                "出生日期这一段不能留在样本里——它是这段值里最敏感的部分");
        assertFalse(masked.equals(idCard));
    }

    /** 手机号、银行卡走同一条规则（11 位 → 4 个星号，19 位 → 12 个星号）。 */
    @Test
    void otherShapesUseTheSameRule() {
        assertEquals("138****0001", SensitivityMasking.mask("13800000001"));
        assertEquals("622************0000",
                SensitivityMasking.mask("6228480000000000000"),
                "19 位卡号：头 3 尾 4，中间 12 个星号");
        assertEquals(19, SensitivityMasking.mask("6228480000000000000").length());
    }

    // ------------------------------------------------------------------ 降级

    /**
     * 短值降级：长度 &lt; 8 时按「头 1 尾 1」，长度 ≤ 2 只保留首字符。
     *
     * <p><b>不做「太短就不打码」的例外。</b>那是最容易被写出来的一条捷径，
     * 而它会把「值太短」变成「明文落库」的合法理由——一列 4 位验证码同样是敏感数据。
     * 因此这里逐个长度都断言，长度 1 与 2 也得打。
     */
    @Test
    void shortValuesStillGetMasked() {
        assertNull(SensitivityMasking.mask(null), "null 原样返回：空行与「打成空」要能区分");
        assertEquals("", SensitivityMasking.mask(""));
        assertEquals("*", SensitivityMasking.mask("a"), "单字符没有可保留的尾部");
        assertEquals("a*", SensitivityMasking.mask("ab"), "两字符只留首字符");
        assertEquals("a*g", SensitivityMasking.mask("abg"));
        assertEquals("a*****g", SensitivityMasking.mask("abcdefg"), "7 位：头 1 尾 1，中间 5 个星号");
        assertEquals(7, SensitivityMasking.mask("abcdefg").length());
        // 8 位起走「头 3 尾 4」——边界正好在 3 + 4 + 1
        assertEquals("abc*efgh", SensitivityMasking.mask("abcdefgh"));
    }

    /** 任何非空输入的打码结果都不能等于原值（除了长度 1 的退化情形，见上一条）。 */
    @Test
    void maskedValueNeverEqualsTheOriginal() {
        for (String value : List.of("ab", "abg", "abcdefg", "abcdefgh", "110101199003071233")) {
            assertFalse(value.equals(SensitivityMasking.mask(value)),
                    value + " 的打码结果不能与原文相同");
        }
    }

    // ------------------------------------------------------------------ 批量

    /**
     * 批量：至多 5 个、**顺序保留**、空值照样保留。
     *
     * <p>不排序也不去重：样本的作用是「让人看到真实分布的一角」，去重会让一列
     * 「90% 是同一个值」的数据看起来像「每个值都不同」。空值保留同理——
     * 一个空样本列表与「采到了但都是 NULL」是两件事，后者恰恰是「这列不该判敏感」
     * 的一条证据。
     */
    @Test
    void maskSamplesCapsAtFiveAndKeepsOrderAndNulls() {
        List<String> values = new ArrayList<>(Arrays.asList(
                "13800000001", null, "13800000003", "13800000004", "13800000005",
                "13800000006", "13800000007"));

        List<String> masked = SensitivityMasking.maskSamples(values);

        assertEquals(SensitivityMasking.MAX_SAMPLES, masked.size(), "至多留 5 个");
        assertEquals(5, masked.size());
        assertEquals("138****0001", masked.get(0));
        assertNull(masked.get(1), "空值保留（它是「这一行是 NULL」的证据）");
        assertEquals("138****0003", masked.get(2));
        assertEquals("138****0005", masked.get(4), "顺序保留，不排序也不去重");

        assertEquals(List.of(), SensitivityMasking.maskSamples(null));
        assertEquals(List.of(), SensitivityMasking.maskSamples(List.of()));
        assertEquals(5, SensitivityMasking.MAX_SAMPLES, "上限是 §5.4 定的 5，改它就是改设计");
        assertTrue(SensitivityMasking.maskSamples(List.of("a")).size() == 1);
    }
}
