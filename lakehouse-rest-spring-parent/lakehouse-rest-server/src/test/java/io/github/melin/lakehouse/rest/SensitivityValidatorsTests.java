package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.scan.SensitivityValidators;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * D3 值层的五个校验器（设计见 {@code docs/sensitive-data-design.md} §5.1 / §5.2）。
 *
 * <p><b>为什么校验器要单独占一个用例类。</b>它是整条识别链路里唯一「算出来才算数」的
 * 判据：正则只能回答「形状像不像」，而形状像的东西在百万行里一定存在。§5.2 那句
 * 「校验器比正则重要」在代码里的落点就是这五个方法——它们算错了不会让任何构建失败，
 * 只会让「一列 500 个真身份证」被判成 0.45 的人审候选（值层归零），或者反过来让
 * 一列随机数字自动落上脱敏标签。两种错误的代价都很高，而表现都很安静。
 *
 * <p>断言的组织方式是**成对的正反例**：每一个「该通过」的旁边都有一个只差一位的
 * 「该拒绝」。只写正例的话，一个 `return true;` 的实现能全绿；只写反例的话，
 * 一个 `return false;` 的实现也能全绿。
 *
 * <p>纯函数、无 Spring：本类不加载上下文，跑一遍是毫秒级。
 */
class SensitivityValidatorsTests {

    // ------------------------------------------------------------------ 注册表

    /**
     * 支持的名字就是 §5.1 列的五种，且认名字时大小写不敏感。
     *
     * <p>{@code supports} 的调用方是值层统计：它要先回答「这个类型到底有没有校验器」，
     * 才能决定校验通过率传 {@code null}（不打折）还是传一个数（打折）。
     * 认错的表现是「配了校验器的类型被当成没配」——值层凭空多拿 0.40 分。
     */
    @Test
    void supportedValidatorsAreTheFiveListedOnes() {
        assertEquals(List.of("BANK_CARD", "CN_ID_CARD", "CN_MOBILE", "CN_USCC", "IP")
                        .stream().sorted().toList(),
                SensitivityValidators.supported().stream().sorted().toList());

        assertTrue(SensitivityValidators.supports("CN_ID_CARD"));
        assertTrue(SensitivityValidators.supports("cn_id_card"), "认名字时不看大小写");
        assertTrue(SensitivityValidators.supports("  IP  "), "前后空白不该让校验器消失");
        assertFalse(SensitivityValidators.supports("CN_IDCARD"), "笔误不能当成支持");
        assertFalse(SensitivityValidators.supports("PERSON_NAME"), "没有校验器的类型要如实回答没有");
        assertFalse(SensitivityValidators.supports(null));
        assertFalse(SensitivityValidators.supports(""));
    }

    /**
     * 认不出的校验器名返回 {@code Optional.empty()}，**不是** {@code false}。
     *
     * <p>这个三值语义是值层统计能算对的前提：{@code empty} 让调用方按「没有校验器」
     * 处理（通过率传 {@code null}、不打折），{@code false} 会被当成「全部不合格」
     * 把整层乘成 0。两种处理的坏处不对称——掉分只会多一条人审，归零会让真命中
     * 永远不出现。把配置里的 {@code CN_ID_CARD} 写成 {@code CN_IDCARD} 是笔误，
     * 笔误的正确表现是「这一层少一个判据」，不是「这一列所有值都不合格」。
     */
    @Test
    void unknownValidatorIsEmptyNotFalse() {
        assertEquals(Optional.empty(), SensitivityValidators.check("CN_IDCARD", "110101199003071233"));
        assertEquals(Optional.empty(), SensitivityValidators.check(null, "anything"));
        assertFalse(SensitivityValidators.isValid("CN_IDCARD", "110101199003071233"),
                "看不出校验器时 isValid 只能返回 false（它是二值的），但 check 保留了三值");
    }

    /** 空值不算通过——调用方应先在非空行上统计，这里只是不让它「意外通过」。 */
    @Test
    void blankValuesNeverPass() {
        assertEquals(Optional.of(false), SensitivityValidators.check("CN_ID_CARD", null));
        assertEquals(Optional.of(false), SensitivityValidators.check("CN_ID_CARD", ""));
        assertEquals(Optional.of(false), SensitivityValidators.check("CN_ID_CARD", "   "));
    }

    // ------------------------------------------------------------------ 身份证

    /**
     * 身份证：模 11 加权校验位。
     *
     * <p>正例里的三条覆盖了三种校验码形态：数字（{@code ...1233}）、{@code X}
     * （{@code ...2295}）、以及闰日出生（{@code 20000229}）。反例每条只改一处，
     * 因此失败时能直接指出是哪一个环节没管住。
     */
    @Test
    void idCardChecksMod11CheckDigit() {
        for (String valid : List.of(
                // 硬编码的期望值（独立按 GB 11643-1999 算出来的），而不是用测试里的生成器——
                // 生成器与校验器共用同一张权重表时，"两边一起错"是看不出来的
                "110101199003071233",
                "440304198512250071",
                "11010120000229123X",
                "61010219950412123X")) {
            assertTrue(SensitivityValidators.isValid("CN_ID_CARD", valid),
                    valid + " 应当通过（校验位按模 11 加权算）");
        }
        // 末位换一个字符 → 校验位不符
        assertFalse(SensitivityValidators.isValid("CN_ID_CARD", "110101199003071234"));
        // 校验位该是 X 的地方写成数字（61010219950412123 的正确校验码是 X）
        assertFalse(SensitivityValidators.isValid("CN_ID_CARD", "610102199504121233"));
        // 前 17 位里有非数字 → 直接不通过（不能靠 parseInt 抛异常兜）
        assertFalse(SensitivityValidators.isValid("CN_ID_CARD", "11010A199003071233"));
        // 长度不对：17 位与 19 位
        assertFalse(SensitivityValidators.isValid("CN_ID_CARD", "11010119900307123"));
        assertFalse(SensitivityValidators.isValid("CN_ID_CARD", "1101011990030712330"));
    }

    /** 小写 {@code x} 也认：真实数据里两种写法都有（Excel 导出尤其爱转小写）。 */
    @Test
    void idCardAcceptsLowercaseCheckCharacter() {
        assertTrue(SensitivityValidators.isValid("CN_ID_CARD", "11010120000229123x"));
    }

    /**
     * 出生日期必须是真实存在的日期。
     *
     * <p><b>为什么模 11 之外还要这一条。</b>{@code 110101202602301234} 的校验位是
     * **对的**（它是按前 17 位算出来的），但 2 月 30 日不存在。这类值在编造数据的
     * 场景里非常常见（「随便写个生日」），而模 11 对它无能为力。断言这两个值
     * 各自堵住一个口子：日期非法、月份非法，且**两者都能通过校验位**，
     * 否则这条用例测的其实是校验位而不是日期。
     */
    @Test
    void idCardRejectsImpossibleBirthDates() {
        assertFalse(SensitivityValidators.isValid("CN_ID_CARD", "110101202602301234"),
                "2026-02-30 不存在，尽管校验位是对的");
        assertFalse(SensitivityValidators.isValid("CN_ID_CARD", "110101199013011234"),
                "月份 13 不存在，尽管校验位是对的");
        // 对照：同一个位置的合法日期（2026-02-28）必须通过，证明上面两条拒的是日期而不是别的
        assertTrue(SensitivityValidators.isValid("CN_ID_CARD", idCard("11010120260228123")));
    }

    // ------------------------------------------------------------------ 手机号

    /**
     * 手机号：{@code 1[3-9]} + 9 位数字，且排除未分配号段。
     *
     * <p>排除法是刻意的（见 {@code SensitivityValidators} 的类注释）：穷举白名单会把
     * 「工信部新批一个号段」变成一次代码发布。这里断言两侧——已启用号段放开、
     * 未分配号段（140–144）挡住。
     */
    @Test
    void mobileChecksPrefixAndLength() {
        for (String valid : List.of("13800000001", "19912345678", "15012345678", "17700000000")) {
            assertTrue(SensitivityValidators.isValid("CN_MOBILE", valid), valid + " 应当通过");
        }
        for (String invalid : List.of(
                "14012345678", "14112345678", "14212345678", "14312345678", "14412345678")) {
            assertFalse(SensitivityValidators.isValid("CN_MOBILE", invalid),
                    invalid + " 是未分配号段，应当拒绝");
        }
        assertFalse(SensitivityValidators.isValid("CN_MOBILE", "12812345678"), "第二位必须是 3–9");
        assertFalse(SensitivityValidators.isValid("CN_MOBILE", "23812345678"), "第一位必须是 1");
        assertFalse(SensitivityValidators.isValid("CN_MOBILE", "1381234567"), "10 位不是手机号");
        assertFalse(SensitivityValidators.isValid("CN_MOBILE", "138123456789"), "12 位不是手机号");
        assertFalse(SensitivityValidators.isValid("CN_MOBILE", "1381234567a"));
    }

    // ------------------------------------------------------------------ 银行卡

    /**
     * 银行卡：13–19 位数字 + Luhn。
     *
     * <p>Luhn 只挡「随手敲的数字」里约 90%，它挡不住「另一个合法卡号」——但值层要的
     * 正是这个量级：一列 2000 个值全过 Luhn 的概率已经低到可以忽略。
     */
    @Test
    void bankCardChecksLuhnAndLength() {
        assertTrue(SensitivityValidators.isValid("BANK_CARD", "6222020000000007"));
        assertTrue(SensitivityValidators.isValid("BANK_CARD", "4111111111111111"));
        assertTrue(SensitivityValidators.isValid("BANK_CARD", "6228480000000000000"), "19 位也在范围内");
        // 末位改成相邻数字 → Luhn 不过
        assertFalse(SensitivityValidators.isValid("BANK_CARD", "6222020000000008"));
        assertFalse(SensitivityValidators.isValid("BANK_CARD", "622202000000"),
                "12 位太短（少于 13）");
        assertFalse(SensitivityValidators.isValid("BANK_CARD", "62220200000000070000"),
                "20 位太长（多于 19）");
        assertFalse(SensitivityValidators.isValid("BANK_CARD", "6222 0200 0000 0007"),
                "带空格/分隔符的卡号不该被当成数字");
    }

    // ------------------------------------------------------------------ 统一社会信用代码

    /**
     * 统一社会信用代码：18 位、字符表内、模 31。
     *
     * <p>字符表去掉 {@code I / O / Z / S / V} 是为了避免与 {@code 1 / 0 / 2 / 5 / U}
     * 混淆——因此这三个字母出现在代码里必须判错，而不是被当成某个数字的别名。
     */
    @Test
    void usccChecksMod31AndCharset() {
        assertTrue(SensitivityValidators.isValid("CN_USCC", "911100007109317389"));
        assertTrue(SensitivityValidators.isValid("CN_USCC", "91310115MA1K35Q8XQ"));
        assertTrue(SensitivityValidators.isValid("CN_USCC", "91440300MA5DA1B23G"));
        assertTrue(SensitivityValidators.isValid("CN_USCC", "91310115ma1k35q8xq"),
                "小写也认（入库前统一转了大小写）");
        assertFalse(SensitivityValidators.isValid("CN_USCC", "911100007109317388"),
                "末位错一个字符 → 模 31 不符");
        assertFalse(SensitivityValidators.isValid("CN_USCC", "91310115MA1K3SQ8XQ"),
                "S 不在字符表里（与 5 混淆，标准里被去掉）");
        assertFalse(SensitivityValidators.isValid("CN_USCC", "91110000710931738"),
                "17 位不是信用代码");
    }

    // ------------------------------------------------------------------ IP

    /**
     * IPv4：四段、每段 0–255、不允许前导零。
     *
     * <p>前导零那条不是洁癖：{@code 010.0.0.1} 在 {@code inet_aton} 里被解释成八进制
     * （等于 8.0.0.1），而在别的库里就是十进制——同一个值两个含义，识别层不该
     * 假装它是个确定的 IP。
     */
    @Test
    void ipv4ChecksRangesAndRejectsLeadingZeros() {
        assertTrue(SensitivityValidators.isValid("IP", "10.0.0.1"));
        assertTrue(SensitivityValidators.isValid("IP", "255.255.255.255"));
        assertTrue(SensitivityValidators.isValid("IP", "0.0.0.0"));
        assertFalse(SensitivityValidators.isValid("IP", "256.0.0.1"), "每段不超过 255");
        assertFalse(SensitivityValidators.isValid("IP", "010.0.0.1"), "前导零有八进制歧义");
        assertFalse(SensitivityValidators.isValid("IP", "10.0.0"), "只有三段");
        assertFalse(SensitivityValidators.isValid("IP", "10.0.0.1.2"), "五段");
        assertFalse(SensitivityValidators.isValid("IP", "10.0.0."), "末段为空");
        assertFalse(SensitivityValidators.isValid("IP", "10.0.0.1:8080"),
                "带端口不是 IP 值（列里偶见，但那属于另一类数据）");
    }

    /**
     * IPv6：只做**结构**校验（{@code ::} 至多一次、无压缩必须 8 组、允许末尾嵌 IPv4）。
     *
     * <p>不做压缩展开是刻意的：值层的职责是把「一个地址」与「一段随机字符串」分开，
     * 不是做协议栈。结构对了就放过——放过的代价是极低概率的误报，而把它判成别的
     * 类型（比如凭据）代价更高。
     */
    @Test
    void ipv6ChecksStructureOnly() {
        assertTrue(SensitivityValidators.isValid("IP", "::1"));
        assertTrue(SensitivityValidators.isValid("IP", "2001:db8::1"));
        assertTrue(SensitivityValidators.isValid("IP", "1:2:3:4:5:6:7:8"));
        assertTrue(SensitivityValidators.isValid("IP", "::ffff:192.168.0.1"),
                "末尾嵌 IPv4（IPv4-mapped）是合法写法");
        assertFalse(SensitivityValidators.isValid("IP", "1:2:3:4:5:6:7"),
                "没有 :: 时必须是 8 组");
        assertFalse(SensitivityValidators.isValid("IP", "::1::2"), ":: 只能出现一次");
        assertFalse(SensitivityValidators.isValid("IP", "12345::1"), "每组最多 4 个十六进制位");
        assertFalse(SensitivityValidators.isValid("IP", "gggg::1"), "g 不是十六进制字符");
    }

    // ------------------------------------------------------------------ 夹具

    /** 按 GB 11643 算校验位（只为造数；断言用的期望值是硬编码的）。 */
    private static String idCard(String base17) {
        int[] weights = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        String codes = "10X98765432";
        int sum = 0;
        for (int index = 0; index < 17; index++) {
            sum += (base17.charAt(index) - '0') * weights[index];
        }
        return base17 + codes.charAt(sum % 11);
    }
}
