package io.github.melin.lakehouse.rest.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 最近若干次 SSO 登录的 <b>claim 形状</b>：只留「取到了什么、有几个」，不留原值。
 *
 * <h2>它解决的是哪一类问题</h2>
 *
 * <p>{@code group-claim} 填错（Keycloak 开了 full group path 就是 {@code /data-engineers}，
 * Cognito 叫 {@code cognito:groups}）不会报错，只会让这个人一个角色都没有。
 * 从「登录成功但没权限」反推「claim 名写错了」几乎不可能——那需要看到
 * <b>这次登录的令牌里到底有哪些 claim、{@code groups} 有几个值</b>。
 * 服务端不存令牌（那是凭据），也不该把 claim 原值回显给浏览器，
 * 因此这里只留形状：键名、个数、打码后的示例值。
 *
 * <h2>三条自我约束</h2>
 *
 * <ol>
 *   <li><b>只存打码后的值</b>：写入时就地打码，内存里没有原值可泄漏。
 *       {@code sub} / 邮箱本地部分 / 组名都只留前几个字符。
 *   <li><b>有界</b>：最多 {@value #CAPACITY} 条，同一主体只留最近一次（新的顶掉旧的）。
 *       否则一个每刷新一次页面就写一条的循环会把内存吃满——而它的用途只是「看一眼」。
 *   <li><b>进程内</b>：多副本部署时看到的是本副本最近登录过的人。这一点必须写进文档，
 *       否则「为什么这里只有一半的人」会被当成故障。
 * </ol>
 *
 * <p>取样点只有一个：{@link OidcLoginService#onLogin}。它记录的是
 * <b>服务端验证通过之后</b>的 claim，因此「这里没有记录」与「令牌没通过验证」
 * 是两个可以区分开的结论——而这两种情况在浏览器里都只表现为「登录没成」。
 */
@Slf4j
@Component
public class OidcClaimSampleStore {

    /** 保留条数。20 个主体足够覆盖「最近谁登录过」，再多也没有人会往下翻。 */
    public static final int CAPACITY = 20;

    /** 单个 claim 的示例值保留几个字符；再长也没有诊断价值。 */
    private static final int KEEP_PREFIX = 3;

    /**
     * 一次登录的 claim 形状。
     *
     * @param principal          主体名（取 {@code principal-claim}，即授权用的那个名字）
     * @param issuer             签发方，用来区分「换了 IdP」与「同一个人」
     * @param subject            打码后的 {@code sub}
     * @param email              打码后的邮箱（本地部分打码，域名保留——白名单按域名配）
     * @param groups             打码后的组名，最多 {@value #SAMPLE_GROUPS} 个
     * @param groupCount         组的总数（打码列表被截断时靠它）
     * @param groupsClaimPresent 令牌里到底有没有那个 claim（空数组算「有」）
     * @param outcome            {@code success} / {@code not-provisioned} / {@code partial} /
     *                           {@code refused}（被准入判定拒绝）
     * @param at                 这次登录的时刻（epoch 毫秒）
     */
    public record ClaimSample(String principal, String issuer, String subject, String email,
                              List<String> groups, int groupCount, boolean groupsClaimPresent,
                              String outcome, long at) {
    }

    private static final int SAMPLE_GROUPS = 3;

    private final Deque<ClaimSample> samples = new ArrayDeque<>(CAPACITY);

    /**
     * 记一次登录。<b>永不抛异常</b>：取样是诊断的附属品，它失败不该让登录失败。
     */
    public synchronized void record(OidcService.OidcIdentity identity, String outcome) {
        if (identity == null || identity.principal() == null) {
            return;
        }
        try {
            List<String> groups = identity.groups() == null ? List.of() : identity.groups();
            List<String> masked = new ArrayList<>();
            for (String group : groups) {
                if (masked.size() >= SAMPLE_GROUPS) {
                    break;
                }
                masked.add(mask(group));
            }
            ClaimSample sample = new ClaimSample(identity.principal(), identity.issuer(),
                    mask(identity.subject()), maskEmail(identity.email()), List.copyOf(masked),
                    groups.size(), identity.groupsClaimPresent(), outcome, System.currentTimeMillis());

            // 同一主体只留最近一次：同一个人每刷新一次控制台都会走到这里，
            // 不合并的话整个缓冲区只剩一个人的二十条
            samples.removeIf(existing -> existing.principal().equals(sample.principal()));
            samples.addFirst(sample);
            while (samples.size() > CAPACITY) {
                samples.removeLast();
            }
        } catch (RuntimeException e) {
            log.debug("failed to record an OIDC claim sample: {}", e.getMessage());
        }
    }

    /** 最近的样本，新的在前。返回的是不可变副本，调用方改不动缓冲区。 */
    public synchronized List<ClaimSample> samples() {
        return List.copyOf(samples);
    }

    /**
     * 清空样本。**只有测试用它**：生产路径没有「重置诊断信息」的需求，
     * 缓冲区有界、会自己滚动。
     */
    public synchronized void clear() {
        samples.clear();
    }

    /**
     * 打码：留前 3 个字符，其余换成 {@code ***}。
     *
     * <p>不用「全部打码」：形状诊断要能看出「这是不是一个组名」，
     * 全变星号之后 {@code /data-engineers} 与 {@code data-engineers} 长得一样，
     * 而这恰恰是要区分的那个差异。
     */
    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() <= KEEP_PREFIX) {
            return "***";
        }
        return trimmed.substring(0, KEEP_PREFIX) + "***";
    }

    /** 邮箱单独打码：本地部分按上面的规则，<b>域名保留</b>。
     *
     * <p>域名是配置项（{@code allowed-email-domains} 按它配），诊断时最需要看的就是它；
     * 把它也打码等于让这个页面答不了它唯一要回答的问题。
     */
    static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        String trimmed = email.trim();
        int at = trimmed.lastIndexOf('@');
        if (at <= 0 || at == trimmed.length() - 1) {
            return mask(trimmed);
        }
        String domain = trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
        return mask(trimmed.substring(0, at)) + "@" + domain;
    }
}
