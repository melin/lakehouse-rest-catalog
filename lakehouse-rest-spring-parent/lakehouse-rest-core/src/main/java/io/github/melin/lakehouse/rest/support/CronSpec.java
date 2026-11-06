package io.github.melin.lakehouse.rest.support;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.OptionalLong;
import org.springframework.scheduling.support.CronExpression;

/**
 * 一条 cron 表达式：解析、校验、算下次运行时刻（设计见
 * {@code docs/sensitive-data-design.md} §4.6 / §5.6.3）。
 *
 * <p><b>为什么要一个类而不是每处直接 {@code CronExpression.parse}。</b>三件事必须只有
 * 一份实现，否则它们会互相矛盾：面板上的「下次运行时刻」预览、新建/修改时的合法性校验、
 * 以及节拍推进 {@code next_run_at} 时算出的值。<b>预览与真实推进算出不同的值</b>
 * 是这类功能最典型的一种错——用户看到的 3:00 与实际的 4:00 谁也不报错，只是对不上。
 *
 * <p><b>为什么同时收 5 段与 6 段。</b>5 段（分 时 日 月 周）是所有人写 cron 时的心智，
 * 6 段（秒 分 时 日 月 周）才能表达「每 30 秒」。存储原样保留用户写的那一份，
 * 解析时补一个秒位——<b>不把归一化后的表达式写回库</b>：用户下次打开表单要看到自己
 * 写的那一行，而不是被系统改写过的等价值。
 *
 * <p><b>时区由调用方给。</b>生产上是服务端时钟的时区（{@code Clock#getZone()}，默认 UTC），
 * 测试里传固定的 {@code ZoneId} 就能把「跨天」「月末」这些边界钉死。**不要在这里偷偷用
 * {@code ZoneId.systemDefault()}**：多副本部署里各台机器的时区设置不一致时，
 * 「每天 3 点」会变成每台各扫一次。
 */
public final class CronSpec {

    /** 5 段 cron 补上的秒位：标准 cron 的语义是「第 0 秒」。 */
    private static final String SECOND_ZERO = "0 ";

    private final String expression;
    private final CronExpression parsed;

    private CronSpec(String expression, CronExpression parsed) {
        this.expression = expression;
        this.parsed = parsed;
    }

    /**
     * 解析并校验。
     *
     * @throws IllegalArgumentException 段数不对、或 Spring 的 cron 解析器认不出
     */
    public static CronSpec parse(String expression) {
        String trimmed = expression == null ? "" : expression.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("cron 不能为空");
        }
        String[] fields = trimmed.split("\\s+");
        String normalized;
        if (fields.length == 5) {
            normalized = SECOND_ZERO + trimmed;
        } else if (fields.length == 6) {
            normalized = trimmed;
        } else {
            // 7 段（带年份）是 Quartz 的写法，Spring 的解析器不认。把「段数」说出来，
            // 比让解析器回一句 "Cron expression must consist of 6 fields" 更有指向性
            // ——用户照着 Quartz 文档写 7 段时，看到的会是「你多写了一年」。
            throw new IllegalArgumentException("cron 必须是 5 段（分 时 日 月 周）或 6 段"
                    + "（秒 分 时 日 月 周），实际 " + fields.length + " 段：" + trimmed);
        }
        for (String field : fields) {
            if (isQuartzExtension(field)) {
                // Quartz 的扩展写法。Spring 的解析器对它们的态度与 Quartz 不同（多数直接报错，
                // 少数会按完全不同的含义解释），静默接受会让「从别的调度系统抄过来的 cron」
                // 以一种说不清的方式错位。
                throw new IllegalArgumentException("cron 不支持 Quartz 的扩展写法（" + field + "）：" + trimmed
                        + "；请改用标准写法（例如「最后一个工作日」写成具体的日号）");
            }
        }
        try {
            return new CronSpec(trimmed, CronExpression.parse(normalized));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("cron 不合法：" + trimmed + "（" + exception.getMessage() + "）",
                    exception);
        }
    }

    /**
     * 这一段是不是 Quartz 的扩展写法。
     *
     * <p><b>必须逐段判定，不能对整串做 {@code contains("L")}。</b>月份名 {@code JUL} 里有
     * L、星期名 {@code WED} 里有 W——整串匹配会把「7 月 1 日 3 点」这种完全正常的表达式
     * 一起拒掉，而报错信息说「不支持 L」，用户会盯着一个没有 L 的 cron 看很久。
     */
    private static boolean isQuartzExtension(String field) {
        if (field.equals("?") || field.indexOf('#') >= 0) {
            return true;
        }
        // L（最后一天）/ L-3（倒数第三天）/ LW（最后一个工作日）/ 15W（最近的工作日）
        return field.equalsIgnoreCase("L") || field.equalsIgnoreCase("LW")
                || field.matches("(?i)L-?\\d*") || field.matches("(?i)\\d+W");
    }

    /** 用户写的那一份（5 段就还是 5 段）。 */
    public String expression() {
        return expression;
    }

    /**
     * {@code fromMillis} 之后的下一个匹配时刻。
     *
     * <p><b>严格之后</b>：正好落在匹配点上时返回的是下一次，不是它自己。否则节拍每次
     * 推进都可能停在原地——表现是「一条每小时的调度每秒都在触发」，而日志里看不出异常。
     *
     * @return 空表示这条表达式在可预见的将来不再匹配（例如 {@code 0 0 30 2 *}，2 月 30 日）
     */
    public OptionalLong nextAfter(long fromMillis, ZoneId zone) {
        ZonedDateTime from = ZonedDateTime.ofInstant(Instant.ofEpochMilli(fromMillis), zone);
        ZonedDateTime next;
        try {
            next = parsed.next(from);
        } catch (RuntimeException exception) {
            // Spring 在「找不到下一次」时可能返回 null，也可能在扫描窗口结束后抛异常，
            // 两种都归成「空」：调用方（新建/修改的校验）要把它变成 400，而不是 500。
            return OptionalLong.empty();
        }
        return next == null ? OptionalLong.empty() : OptionalLong.of(next.toInstant().toEpochMilli());
    }

    /**
     * 同 {@link #nextAfter}，但算不出来时抛。
     *
     * <p>新建与修改走这一条：一条永不匹配的 cron 存进去之后，现象是「保存成功、永远不跑」
     * ——那是最难被发现的一种静默失败，必须在写入时就拒绝。
     */
    public long requireNextAfter(long fromMillis, ZoneId zone) {
        return nextAfter(fromMillis, zone).orElseThrow(() -> new IllegalArgumentException(
                "cron 永不匹配（例如 2 月 30 日这种日期）：" + expression
                        + "；请检查日与月的组合"));
    }

    /** 合法就返回 {@code true}；供静态校验脚本与表单提示用，不抛。 */
    public static boolean isValid(String expression) {
        try {
            parse(expression);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public String toString() {
        return expression;
    }

    /** 供错误信息里列出合法示例，避免把段序写反（周与日的位置最容易错）。 */
    static String examples() {
        return Arrays.asList("*/30 * * * *（每 30 分钟）", "0 3 * * *（每天 03:00）",
                "0 0 3 * * *（每天 03:00:00，6 段写法）").toString();
    }
}
