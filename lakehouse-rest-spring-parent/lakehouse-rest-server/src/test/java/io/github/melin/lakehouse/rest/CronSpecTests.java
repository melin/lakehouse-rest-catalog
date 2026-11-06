package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.melin.lakehouse.rest.support.CronSpec;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

/**
 * {@link CronSpec} 的纯函数用例（设计见 {@code docs/sensitive-data-design.md} §4.6 / §5.6.3）。
 *
 * <p><b>为什么这几条值得单独测。</b>调度项的正确性最终落在「这一拍该不该触发」上，
 * 而那个判断由两处共用同一个函数：写入时算 {@code next_run_at}（面板上的「下次运行时刻」
 * 预览也走它）与节拍推进时重算。两份推导只要有一处不同，用户看到的时刻与真实的触发
 * 时刻就会错开，且**谁也不报错**——这正是设计稿要求「只有一个 CronSpec」的理由，
 * 而「只有一个」这件事需要一个用例把它钉住。
 *
 * <p>时区一律显式传 {@code UTC}：`CronSpec` 自己不用 {@code systemDefault()}，
 * 用例也不该让「跑测试的机器在哪个时区」影响结果。
 */
class CronSpecTests {

    private static final ZoneId UTC = ZoneId.of("UTC");

    /** 5 段写法补的秒位是 0：`0 3 * * *` 是「每天 03:00:00」，不是「03:00 的那一分钟里任意一秒」。 */
    @Test
    void fiveFieldExpressionFiresAtSecondZero() {
        CronSpec spec = CronSpec.parse("0 3 * * *");
        assertEquals(Instant.parse("2026-03-01T03:00:00Z").toEpochMilli(),
                spec.requireNextAfter(Instant.parse("2026-03-01T00:00:00Z").toEpochMilli(), UTC));
    }

    /**
     * 6 段写法保留秒位。
     *
     * <p>这一条同时在守「别把 5 段归一化后写回」的反面：{@link CronSpec#expression()}
     * 必须还是用户写的那一份，否则表单重新打开时看到的是被系统改过的等价值。
     */
    @Test
    void sixFieldExpressionKeepsItsSeconds() {
        CronSpec spec = CronSpec.parse("*/30 * * * * *");
        assertEquals("*/30 * * * * *", spec.expression(),
                "表达式要原样保留，不能写回归一化后的形式");
        assertEquals(Instant.parse("2026-03-01T00:00:30Z").toEpochMilli(),
                spec.requireNextAfter(Instant.parse("2026-03-01T00:00:10Z").toEpochMilli(), UTC));
    }

    /**
     * 月份名 {@code JUL} 与星期名 {@code WED} 里有 L 和 W，但它们是标准写法。
     *
     * <p>整串 {@code contains("L")} 会把「7 月 1 日 03:00」这种完全正常的表达式拒掉，
     * 而报错信息说「不支持 L」——用户会盯着一个没有 L 的 cron 看很久。因此 Quartz
     * 扩展必须逐段判定，这两条用例就是那行判定的守卫。
     */
    @Test
    void monthAndWeekdayNamesAreNotQuartzExtensions() {
        assertTrue(CronSpec.isValid("0 3 1 JUL *"), "JUL 是标准月份名，不该被当成 L 扩展");
        assertTrue(CronSpec.isValid("0 3 * * WED"), "WED 是标准星期名，不该被当成 W 扩展");
        // 名字能解析，且指向的确实是 7 月 1 日 03:00
        long next = CronSpec.parse("0 3 1 JUL *")
                .requireNextAfter(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), UTC);
        assertEquals(Instant.parse("2026-07-01T03:00:00Z").toEpochMilli(), next);
    }

    /**
     * Quartz 的扩展写法一律拒（{@code ?} / {@code #} / {@code L} / {@code L-3} / {@code LW} / {@code 15W}）。
     *
     * <p>拒的理由不是「我们懒」：Spring 的解析器对这些字符的态度与 Quartz 不同，
     * 多数直接报错、少数会按完全不同的含义解释。静默接受会让「从别的调度系统抄过来的
     * cron」以一种说不清的方式错位——比在写入时报错坏得多。
     */
    @Test
    void quartzExtensionsAreRejected() {
        for (String expression : new String[] {"0 3 ? * *", "0 3 * * 3#2", "0 3 L * *",
                "0 3 L-3 * *", "0 3 LW * *", "0 3 15W * *"}) {
            assertThrows(IllegalArgumentException.class, () -> CronSpec.parse(expression),
                    "Quartz 扩展写法应当被拒：" + expression);
        }
    }

    /** 7 段（带年份）是 Quartz 的形状，报错要说出「几段」，而不是让解析器回一句看不清的英文。 */
    @Test
    void wrongFieldCountIsRejectedWithTheCount() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> CronSpec.parse("0 0 3 * * * 2026"));
        assertTrue(error.getMessage().contains("7"), () -> "报错里要有实际段数，实际=" + error.getMessage());

        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> CronSpec.parse("   "));
        assertTrue(blank.getMessage().contains("不能为空"));
    }

    /**
     * 算「之后」而不是「不早于」：正好落在匹配点上时返回下一次。
     *
     * <p>否则节拍每次推进都可能停在原地——表现是「一条每小时的调度每秒都在触发」，
     * 而日志里看不出任何异常（它确实每次都抢到了）。
     */
    @Test
    void nextIsStrictlyAfter() {
        CronSpec spec = CronSpec.parse("0 3 * * *");
        long exactlyThree = Instant.parse("2026-03-01T03:00:00Z").toEpochMilli();
        assertEquals(Instant.parse("2026-03-02T03:00:00Z").toEpochMilli(),
                spec.requireNextAfter(exactlyThree, UTC),
                "恰好落在匹配点上时要给下一次，不能返回它自己");
    }

    /**
     * 永不匹配的表达式（2 月 30 日）在写入时就要拒。
     *
     * <p>存进去之后的现象是「保存成功、永远不跑」——那是最难被发现的一种静默失败，
     * 而它恰好长得和「调度系统坏了」一模一样。
     */
    @Test
    void neverMatchingExpressionIsRejected() {
        CronSpec spec = CronSpec.parse("0 0 30 2 *");
        assertTrue(spec.nextAfter(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), UTC).isEmpty(),
                "2 月 30 日不该算出结果");
        assertThrows(IllegalArgumentException.class,
                () -> spec.requireNextAfter(Instant.parse("2026-01-01T00:00:00Z").toEpochMilli(), UTC));
    }

    /** {@code isValid} 不抛，只在非法时回 false（表单提示与校验脚本用它，不能有异常逃出去）。 */
    @Test
    void isValidNeverThrows() {
        assertTrue(CronSpec.isValid("*/30 * * * *"));
        assertTrue(CronSpec.isValid("0 0 3 * * *"), "6 段标准写法也合法");
        assertFalse(CronSpec.isValid("0 3 ? * *"));
        assertFalse(CronSpec.isValid("not a cron"));
        assertFalse(CronSpec.isValid(null));
    }
}
