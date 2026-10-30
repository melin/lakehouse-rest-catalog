package io.github.melin.lakehouse.rest.engine.auth;

/**
 * 「当前该用的票」的来源。
 *
 * <p><b>为什么要有这一层。</b>两个消费面要的东西是同一个：Paimon 的
 * {@code AuthProvider#mergeAuthHeader}（每个 HTTP 请求调一次）与 Spark 治理通道的
 * {@code ManagementApiClient}（每次请求取一次头）。它们的共同点不是「有一个令牌字符串」，
 * 而是「每次要头的时候问一句，拿到当前还有效的票」——差别只在失败之后能不能重放。
 * 所以接口取这个形状，续期策略（缓存、single-flight、退避）只在
 * {@link RefreshingToken} 里实现一次。
 *
 * <p><b>{@link #invalidate()} 返回布尔，而不是无返回值。</b>它回答的是「这次作废之后，
 * 下一次 {@link #token()} 会不会给出另一张票」。调用方（401 重放）只有在这个答案为真时
 * 才值得重发一次请求：固定票作废之后还是同一张票，重放只会多打一次注定失败的请求，
 * 并把「401」变成「两次 401」。
 */
public interface TokenSource {

    /**
     * 当前应该用的票。
     *
     * <p>实现可以在这里同步换票（进刷新窗口时），也可以什么都不做直接返回缓存的票。
     *
     * @return 令牌字符串；**没有任何可用票时返回 {@code null}**（调用方据此给出
     *         「配置缺票」这种明确的错误，而不是发一个 {@code Bearer null} 出去）
     */
    String token();

    /**
     * 丢弃当前缓存，使下一次 {@link #token()} 尽量换一张新票。
     *
     * <p>语义是「我怀疑这张票不管用了」，不是「保证换到新票」：换票本身可能失败
     * （服务端不可达、凭据不对），那时 {@link #token()} 仍会返回旧票——把
     * 「本来能成功的请求」变成 500 是更糟的选择，理由写在 {@link RefreshingToken}。
     *
     * @return 下一次 {@link #token()} 是否可能给出另一张票。{@code false} 表示这个来源
     *         不可换票（例如固定票），调用方**不应**重放请求
     */
    boolean invalidate();

    /**
     * 固定的票：不续期、作废无效。
     *
     * <p>这是「行为与改造前逐字节相同」的那条路——老部署没有配凭据，令牌就是配置里
     * 那个字符串，到期后由运维重启。 Spark 治理通道的默认值就是它。
     */
    static TokenSource fixed(String token) {
        String value = token == null || token.isBlank() ? null : token;
        return new TokenSource() {
            @Override
            public String token() {
                return value;
            }

            @Override
            public boolean invalidate() {
                return false;
            }

            @Override
            public String toString() {
                return "TokenSource.fixed";
            }
        };
    }
}
