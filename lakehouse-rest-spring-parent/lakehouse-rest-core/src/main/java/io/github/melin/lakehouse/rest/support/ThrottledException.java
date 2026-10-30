package io.github.melin.lakehouse.rest.support;

/**
 * 云侧限流：{@code 429}、{@code Throttling}、{@code SlowDown}、{@code RequestLimitExceeded}。
 *
 * <p><b>为什么值得单独一个类型。</b>签发档在失败时一律回 {@code 503}
 * （「客户端明确要凭据，拿不到就必须看得见」），于是「限流」与「权限不足」
 * 「网络不通」在状态码上是同一件事。而这两类失败的处置完全不同：
 * 权限错了再重试一百次也没用，限流了立刻退避反而能过去。
 * 只用 {@code status == 503} 去判会重试一堆永远不可能成功的事。
 *
 * <p>分类由**发请求的那个客户端**做，因为只有它读得到云的响应体：
 * AWS 是 {@code StsException#errorCode()}、阿里云是 {@code Code} 字段、
 * Azure 与 GCS 只看状态码。集中到调用层去猜字符串必然漏。
 *
 * <p>继承 {@link ApiException} 而不是另起一支：现有的调用方与异常处理器都按
 * {@code ApiException} 处理（{@code 503} + 人读消息），多一个并列的异常类型
 * 会让它们漏掉这一支。
 */
public class ThrottledException extends ApiException {

    private final long retryAfterMillis;

    public ThrottledException(String message) {
        this(message, 0L);
    }

    /**
     * @param retryAfterMillis 云侧给的 {@code Retry-After}，没有就是 0。
     *                         它是**建议**值：调用方自己的退避曲线不会因为它而变短，
     *                         只在它更长时采用（见 {@code ThrottlingGuard#call}）。
     */
    public ThrottledException(String message, long retryAfterMillis) {
        super(503, null, null, message);
        this.retryAfterMillis = Math.max(0L, retryAfterMillis);
    }

    public long retryAfterMillis() {
        return retryAfterMillis;
    }
}
