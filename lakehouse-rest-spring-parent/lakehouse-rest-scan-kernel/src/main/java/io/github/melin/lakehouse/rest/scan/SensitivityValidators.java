package io.github.melin.lakehouse.rest.scan;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * D3 值层的校验器（设计见 {@code docs/sensitive-data-design.md} §5.1 / §5.2）。
 *
 * <p><b>校验器比正则重要</b>——这是 §5.2 里唯一一句加粗的判定口径，理由是：
 * 正则只能回答「形状像不像」，而「形状像」的东西在一个百万行的表里一定存在
 * （{@code 110101199001011234} 与随便敲的 18 位数字在正则眼里一样）。
 * 校验位才是「这串数字是**算出来的**还是**编出来的**」的分界线：真实身份证号
 * 第 18 位由前 17 位按模 11 加权算出，随手编一个 18 位数能过校验的概率约 1/11；
 * 银行卡号的 Luhn 约 1/10；统一社会信用代码的模 31 约 1/31。
 * 三者叠加到「一列 2000 个值全过校验」时，误报概率已经低到可以忽略。
 *
 * <p><b>为什么校验器是代码里的常量而不是配置里的表达式。</b>它们是**算法**，不是策略：
 * 模 11 的权重表、Luhn 的加倍规则、USCC 的 31 字符表都是标准的一部分，写成
 * 可配置的表达式只会让「同一份配置在不同版本算出不同结论」成为可能。
 * 配置里能选的是**用哪一个**（{@code detectors_json} 的 {@code validator} 字段），
 * 不是**怎么算**。这与 {@code SensitivityScorer} 的层系数不可配是同一条取舍。
 *
 * <p><b>纯函数、无 Spring 依赖</b>：与 {@link SensitivityDetectors} 同一取舍，
 * 单测可以直接喂 500 条造数验证「命中率 100%、误报 0」（§12 的 P1 验收断言）。
 *
 * <p><b>认不出的校验器名返回 {@link Optional#empty()}。</b>刻意与「校验不通过」
 * 区分开：配置里把 {@code CN_ID_CARD} 写成 {@code CN_IDCARD} 是笔误，
 * 笔误的正确表现是「这一层没有可用判据」（值层得分少乘一个通过率），
 * 而不是「这一列所有值都不合格」（值层直接归零、连带把真命中判死）。
 * 掉分只会让人审队列多一条，归零会让它永远不出现——两者的坏处不对称。
 */
public final class SensitivityValidators {

    /** 身份证前 17 位的加权因子（GB 11643-1999）。 */
    private static final int[] ID_CARD_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 校验码表：{@code sum % 11} → 第 18 位字符（{@code 10X98765432}）。 */
    private static final char[] ID_CARD_CHECK_CODES = "10X98765432".toCharArray();

    /** 统一社会信用代码的 31 字符表（GB 32100-2015，去掉 I / O / Z / S / V）。 */
    private static final String USCC_CHARSET = "0123456789ABCDEFGHJKLMNPQRTUWXY";

    /** 统一社会信用代码前 17 位的加权因子。 */
    private static final int[] USCC_WEIGHTS = {1, 3, 9, 27, 19, 26, 16, 17, 20, 29, 25, 13, 8, 24, 10, 30, 28};

    /**
     * 未分配的移动号段（前三位）。
     *
     * <p>设计稿 §5.1 的原话是「号段白名单（排除 140–144 等未分配段）」。这里按
     * **排除法**实现而不是穷举白名单：穷举会把「工信部新批一个号段」变成一次代码发布，
     * 而排除法在那种情况下只会多认一批号（新增段本来就该被认成手机号）。
     * 反向的代价是「某个未分配段被运营商启用了却还在排除表里」——那需要一次
     * 有意的更正，而这正是这张表存在的方式。
     */
    private static final Set<String> UNASSIGNED_MOBILE_PREFIXES = Set.of(
            "140", "141", "142", "143", "144");

    /** 严格模式的 {@code yyyyMMdd}：{@code 20260230} 必须判为非法。 */
    private static final DateTimeFormatter BIRTH_DATE =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private SensitivityValidators() {
    }

    /**
     * 支持的校验器名（与 {@code BuiltinSensitiveTypes} 的 {@code value.validator} 一致）。
     *
     * <p>暴露它是为了让服务层与测试能回答「这个类型的值层到底有没有校验器」——
     * 一个配了 {@code validator} 但没有实现的类型，值层得分会静默少一块，
     * 而那与「这个类型本来就不靠值判」在结果上无法区分。
     */
    public static Set<String> supported() {
        return Set.of("CN_ID_CARD", "CN_MOBILE", "BANK_CARD", "CN_USCC", "IP");
    }

    /** 这个校验器名是否有实现（大小写不敏感，与配置里的写法解耦）。 */
    public static boolean supports(String validator) {
        return validator != null && supported().contains(normalize(validator));
    }

    /**
     * 校验一个值。
     *
     * @param validator 校验器名（{@code detectors_json} 的 {@code value.validator}）
     * @param value     待校验的值；{@code null} / 空串**不算通过**（调用方应先在
     *                  非空行上统计，见 {@code SensitivityScorer#valueScore} 的分母口径）
     * @return {@code true} 通过；{@code false} 不通过**或**校验器名认不出。
     *         需要区分这两种情况时用 {@link #check}。
     */
    public static boolean isValid(String validator, String value) {
        return check(validator, value).orElse(false);
    }

    /**
     * 校验一个值，并区分「校验不通过」与「没有这个校验器」。
     *
     * <p>这个区分是值层统计能算对的前提：认不出的校验器要按「无校验器」处理
     * （通过率传 {@code null}，不打折），而不是按 0% 通过率把整层乘成 0。
     */
    public static Optional<Boolean> check(String validator, String value) {
        if (!supports(validator)) {
            return Optional.empty();
        }
        if (value == null) {
            return Optional.of(false);
        }
        String text = value.trim();
        if (text.isEmpty()) {
            return Optional.of(false);
        }
        return Optional.of(switch (normalize(validator)) {
            case "CN_ID_CARD" -> idCard(text);
            case "CN_MOBILE" -> mobile(text);
            case "BANK_CARD" -> luhn(text);
            case "CN_USCC" -> uscc(text);
            case "IP" -> ip(text);
            default -> false;
        });
    }

    // ------------------------------------------------------------------ 各校验器

    /**
     * 居民身份证号：模 11 加权校验位 + 出生日期必须是真实存在的日期。
     *
     * <p><b>为什么还要校验出生日期。</b>模 11 只保证「这一串数字自洽」，而
     * {@code 110101199013011234} 这类月份为 13 的编造值同样可能碰巧过校验
     * （正则里 {@code (18|19|20)\d{2}(0[1-9]|1[0-2])...} 已经挡住月份，但
     * {@code 20260230} 这种「月份合法、日期不存在」的仍能通过）。加一条真实日期
     * 校验把误报再压一个数量级，代价是一次 {@code LocalDate} 解析。
     */
    private static boolean idCard(String text) {
        if (text.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int index = 0; index < 17; index++) {
            char ch = text.charAt(index);
            if (!Character.isDigit(ch)) {
                return false;
            }
            sum += (ch - '0') * ID_CARD_WEIGHTS[index];
        }
        char expected = ID_CARD_CHECK_CODES[sum % 11];
        char actual = Character.toUpperCase(text.charAt(17));
        if (expected != actual) {
            return false;
        }
        return realDate(text.substring(6, 14));
    }

    /** 中国大陆手机号：{@code 1[3-9]} + 9 位，且前三位不是未分配段。 */
    private static boolean mobile(String text) {
        if (text.length() != 11) {
            return false;
        }
        for (int index = 0; index < 11; index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        if (text.charAt(0) != '1') {
            return false;
        }
        char second = text.charAt(1);
        if (second < '3' || second > '9') {
            return false;
        }
        return !UNASSIGNED_MOBILE_PREFIXES.contains(text.substring(0, 3));
    }

    /** 银行卡号：13–19 位数字 + Luhn 校验（ISO/IEC 7812）。 */
    private static boolean luhn(String text) {
        if (text.length() < 13 || text.length() > 19) {
            return false;
        }
        for (int index = 0; index < text.length(); index++) {
            if (!Character.isDigit(text.charAt(index))) {
                return false;
            }
        }
        int sum = 0;
        boolean doubling = false;
        for (int index = text.length() - 1; index >= 0; index--) {
            int digit = text.charAt(index) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /** 统一社会信用代码：18 位、字符表内、模 31 校验。 */
    private static boolean uscc(String text) {
        if (text.length() != 18) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        int sum = 0;
        for (int index = 0; index < 17; index++) {
            int code = USCC_CHARSET.indexOf(upper.charAt(index));
            if (code < 0) {
                return false;
            }
            sum += code * USCC_WEIGHTS[index];
        }
        int expected = (31 - sum % 31) % 31;
        return USCC_CHARSET.charAt(expected) == upper.charAt(17);
    }

    /**
     * IP 地址：IPv4 每段 0–255（且不允许前导零）；含冒号时按 IPv6 的形状判。
     *
     * <p>IPv6 只做**结构**校验（分组数、每组的十六进制位数、{@code ::} 只能出现一次），
     * 不做压缩展开：值层的职责是把「{@code 10.0.0.1} 与一段随机字符串」分开，
     * 而不是做一个协议栈。结构对了就放过——放过的代价是极低概率的误报，
     * 而误判它是别的类型（比如把它当凭据）代价更高。
     */
    private static boolean ip(String text) {
        if (text.indexOf(':') >= 0) {
            return ipv6(text);
        }
        return ipv4(text);
    }

    private static boolean ipv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            return false;
        }
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 3) {
                return false;
            }
            if (part.length() > 1 && part.charAt(0) == '0') {
                // 前导零的段在 inet_aton 里有歧义（八进制），真实数据里几乎不出现
                return false;
            }
            for (int index = 0; index < part.length(); index++) {
                if (!Character.isDigit(part.charAt(index))) {
                    return false;
                }
            }
            if (Integer.parseInt(part) > 255) {
                return false;
            }
        }
        return true;
    }

    private static boolean ipv6(String text) {
        if (text.isBlank() || text.length() > 45) {
            return false;
        }
        int compressAt = text.indexOf("::");
        if (compressAt >= 0 && text.indexOf("::", compressAt + 1) >= 0) {
            return false;
        }
        String body = text;
        boolean trailingV4 = body.contains(".");
        if (trailingV4) {
            int lastColon = body.lastIndexOf(':');
            if (lastColon < 0 || !ipv4(body.substring(lastColon + 1))) {
                return false;
            }
            body = body.substring(0, lastColon + 1) + "0";
        }
        String[] groups = body.split(":", -1);
        int nonEmpty = 0;
        for (String group : groups) {
            if (group.isEmpty()) {
                continue;
            }
            if (group.length() > 4) {
                return false;
            }
            for (int index = 0; index < group.length(); index++) {
                if (Character.digit(group.charAt(index), 16) < 0) {
                    return false;
                }
            }
            nonEmpty++;
        }
        // 有 :: 时允许少于 8 组（压缩了若干组零）；没有 :: 必须正好 8 组
        return compressAt >= 0 ? nonEmpty <= 7 : nonEmpty == 8;
    }

    private static boolean realDate(String yyyyMMdd) {
        try {
            LocalDate.parse(yyyyMMdd, BIRTH_DATE);
            return true;
        } catch (DateTimeParseException exception) {
            return false;
        }
    }

    private static String normalize(String validator) {
        return validator.trim().toUpperCase(Locale.ROOT);
    }
}
