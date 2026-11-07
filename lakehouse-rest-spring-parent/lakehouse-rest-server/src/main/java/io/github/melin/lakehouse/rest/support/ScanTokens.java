package io.github.melin.lakehouse.rest.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 扫描作业的回调令牌：生成与哈希。
 *
 * <p><b>为什么单独一个类。</b>令牌在两个地方被碰：提交器（生成、下发）与回调校验
 * （算哈希、比对）。两边各写一遍 SHA-256，漏掉 {@code StandardCharsets} 或 hex 大小写
 * 就会得到「生成时算出来 64 个字符、校验时算出来 64 个字符，但永不相等」——
 * 而这种错误的表现是「作业一切正常、回调永远 403」，没有任何一条日志指向编码。
 *
 * <p><b>库里只存哈希</b>（{@code lakehouse_sensitivity_scan.callback_token_hash}，§4.2）。
 * 令牌本身只在提交那一刻活一次：进 Job 的命令行，然后随方法栈消失。
 * 进程在「提交成功、回写哈希之前」退出时，重投会从已存在的 Job 里把令牌读回来
 * （见 {@code Fabric8ScanJobSubmitter#submit}），因此「库里没有明文」不等于「重投会换令牌」。
 */
public final class ScanTokens {

    /**
     * 令牌长度：32 字节随机数（base64url 之后 43 个字符）。
     *
     * <p>128 位就足以让「猜到一个有效令牌」不可行；取 256 位是因为它不增加任何成本，
     * 而减少一个「将来要论证一下够不够」的问题。用 {@link SecureRandom} 而不是
     * {@code Math.random}：后者是可预测的线性同余，令牌的作用域虽小，但它是唯一的凭据。
     */
    private static final int TOKEN_BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();

    private ScanTokens() {
    }

    /** 新的回调令牌（URL 安全、无填充，能原样放进命令行与请求头）。 */
    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 令牌的 SHA-256（十六进制小写，64 个字符，正好是列宽）。 */
    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // JDK 必须提供 SHA-256；走到这里说明运行环境坏了，不该被当成「令牌不匹配」
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    /**
     * 定长比较两个十六进制哈希。
     *
     * <p>用 {@link MessageDigest#isEqual} 而不是 {@code equals}：后者在第一个不同的字节
     * 就返回，把「比到第几位」泄漏成一个可测量的时间差。
     */
    public static boolean matches(String expectedHash, String token) {
        if (expectedHash == null || token == null) {
            return false;
        }
        return MessageDigest.isEqual(expectedHash.getBytes(StandardCharsets.UTF_8),
                hash(token.trim()).getBytes(StandardCharsets.UTF_8));
    }
}
