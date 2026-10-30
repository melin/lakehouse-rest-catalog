package io.github.melin.lakehouse.rest.engine.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

/**
 * 进程级的票缓存：同一个「端点 + 主体身份」只保留一份续期状态。
 *
 * <p><b>这不是优化，是正确性要求。</b>Paimon 的 {@code RESTTokenFileIO.refreshToken()}
 * 每次刷新数据票都会 {@code new RESTApi(options, false)}，于是
 * {@code createAuthProvider(options)} 被反复执行。若续期状态挂在 provider 实例上，
 * 每次新实例都从配置里那张**初始票**重新开始——它早就过期了。症状是
 * 「目录能读、读表 1 小时后 401」，而目录那条路（同一个实例）明明还在正常续期。
 *
 * <p><b>键里不能出现密钥。</b>键形如 {@code endpoint|cid:<clientId>} 或
 * {@code endpoint|sub:<subject>}，令牌本体只以摘要出现（见 {@link #keyOf}）。
 * 缓存键会进日志、进堆转储、进 toString，把凭据放进去等于给它们开了一条泄露路径。
 */
public final class RefreshingTokens {

    private static final ConcurrentMap<String, RefreshingToken> CACHE = new ConcurrentHashMap<>();

    private RefreshingTokens() {
    }

    /**
     * 取或创建缓存项。
     *
     * <p>用 {@code computeIfAbsent} 而不是「先查再放」：两个 provider 实例被并发创建时
     * （多个 task 同时启动）后者会把前者刚建立的状态顶掉，于是两边的续期互不知情。
     *
     * <p>后到的实例<b>不会</b>覆盖先到的状态，即使它手里的初始票更新——先到的那个
     * 一定正在续期，它的票只会比配置里的更新。反过来的情形（先到的已经过期、后到的
     * 拿了新票）在同一个进程里不会发生：配置在作业启动后就固定了。
     */
    public static RefreshingToken obtain(String key, Supplier<RefreshingToken> factory) {
        return CACHE.computeIfAbsent(key, ignored -> factory.get());
    }

    /**
     * 组装缓存键。
     *
     * <p>身份优先取<b>凭据的 clientId</b>：同一个主体用两个作业跑，它们共享一份续期状态
     * 是对的（换出来的票等权）。没有凭据时退回令牌的 {@code sub}（自签 JWT 的主体名），
     * 连 {@code sub} 都读不出来（静态令牌）才用令牌的摘要——那种情况下「同一张票」
     * 就是唯一的身份，用摘要是为了不把令牌本身写进键。
     *
     * <p>{@code sub} 读不出来而用摘要：静态令牌不是 JWT，没有 {@code sub}；
     * 它同时也不可续（没有 {@code exp}），所以这个键基本只会命中同一个字符串。
     */
    public static String keyOf(String endpoint, String clientId, String token) {
        String identity;
        if (clientId != null && !clientId.isBlank()) {
            identity = "cid:" + clientId;
        } else {
            String subject = JwtClaims.claim(token, "sub");
            identity = subject != null && !subject.isBlank()
                    ? "sub:" + subject
                    : "tok:" + digest(token);
        }
        return endpoint + "|" + identity;
    }

    /**
     * 清空缓存。
     *
     * <p>只给测试用：静态表在同一个 JVM 里跨用例共享，不清理会让第二个用例看到
     * 第一个用例创建的实例。生产上没有任何调用点——清掉这张表等于让正在续期的
     * 作业退回配置里的初始票。
     */
    static void clear() {
        CACHE.clear();
    }

    /** 令牌摘要，只用于键：取 SHA-256 的前 8 字节。 */
    private static String digest(String token) {
        if (token == null || token.isBlank()) {
            return "none";
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (int i = 0; i < 8; i++) {
                builder.append(String.format("%02x", hash[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必须实现的算法，走不到这里；退化成身份串长度也不泄露内容
            return "len:" + token.length();
        }
    }

    /** 当前缓存了多少个端点/主体组合（诊断用）。 */
    public static Map<String, RefreshingToken> snapshot() {
        return Map.copyOf(CACHE);
    }
}
