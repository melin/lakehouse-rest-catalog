package io.github.melin.lakehouse.rest.support;

import java.util.ArrayList;
import java.util.List;

/**
 * 采样值的打码（设计见 {@code docs/sensitive-data-design.md} §5.4）。
 *
 * <p><b>本类只做一件事，但它是这条链路唯一的不变量</b>：任何要落库、进日志、
 * 进事件或回给调用方的样本，都必须先过 {@link #mask(String)}。
 * finding 的 {@code masked_samples_json} 之所以叫 masked 而不是 samples，
 * 就是为了让「这里有没有打码」成为类型和命名上的事实，而不是一句约定。
 *
 * <p><b>打码的形状是「头 3 尾 4」</b>（{@code 110***********1234}）：人审要看的是
 * 「这个值像不像身份证」，而真正需要那一眼的是**前 6 位地址 + 后 4 位**——
 * 前 3 位足够让审核人认出「这是 110 开头的北京号」，后 4 位足够让他确认
 * 「和他在业务系统里看到的是同一批数据」。中间的出生日期与顺序码全部变成星号，
 * 于是这一串在库里既不构成一条可用身份信息，又仍然能支撑判定。
 *
 * <p><b>为什么保留尾部而不是全遮。</b>全遮（{@code ****}）会让人审队列失去唯一的
 * 判断依据——审核人只能看到一个「0.87」的数字，然后凭感觉点确认。而把整段的
 * 尾部保留会让「打码样本」重新变成一条可用身份信息。3 + 4 是这两件事之间的
 * 折中，且**与 {@code TablePreviewService} 的列脱敏动作无关**：那条路径有自己的
 * mask 函数（业务语义），这里的打码只为「识别结果能被审」服务。
 *
 * <p><b>短值降级。</b>长度不足 8 的值按「头 1 尾 1」处理，长度 2 及以下只保留首字符。
 * 不做「太短就不打码」的例外——那正是最容易被写出来的一条捷径，而它会把
 * 「值太短」变成「明文落库」的合法理由。
 */
public final class SensitivityMasking {

    /** 落库的样本上限（§5.4：至多 5 个）。 */
    public static final int MAX_SAMPLES = 5;

    /** 长值（≥8）保留的头部字符数。 */
    private static final int HEAD = 3;

    /** 长值（≥8）保留的尾部字符数。 */
    private static final int TAIL = 4;

    private SensitivityMasking() {
    }

    /**
     * 打码一个值。
     *
     * @param value 原值；{@code null} 原样返回 {@code null}（「这一行是空的」与
     *              「这一行被打成了空」在样本里必须可区分）
     * @return 打码后的字符串，长度与原值一致
     */
    public static String mask(String value) {
        if (value == null) {
            return null;
        }
        int length = value.length();
        if (length == 0) {
            return "";
        }
        if (length == 1) {
            return "*";
        }
        if (length == 2) {
            return value.charAt(0) + "*";
        }
        if (length < HEAD + TAIL + 1) {
            return value.charAt(0) + stars(length - 2) + value.charAt(length - 1);
        }
        return value.substring(0, HEAD) + stars(length - HEAD - TAIL)
                + value.substring(length - TAIL);
    }

    /**
     * 打码一批值并截到 {@link #MAX_SAMPLES} 个。
     *
     * <p>顺序保留（不排序、不去重之后再截）：样本的作用是「让人看到真实分布的一角」，
     * 去重会让一列「90% 是同一个值」的数据看起来像「每个值都不同」。
     * 空值照样保留 —— 一个全空的样本列表与「采样读到了但都是 NULL」是两件事，
     * 后者恰恰是「这列不该判敏感」的一条证据。
     */
    public static List<String> maskSamples(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> masked = new ArrayList<>(Math.min(values.size(), MAX_SAMPLES));
        for (String value : values) {
            if (masked.size() >= MAX_SAMPLES) {
                break;
            }
            masked.add(mask(value));
        }
        return masked;
    }

    private static String stars(int count) {
        return "*".repeat(Math.max(0, count));
    }
}
