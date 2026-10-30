package io.github.melin.lakehouse.rest.engine.auth;

/**
 * 换票动作本身。抽成接口只为一个理由：{@link RefreshingToken} 的时序规则
 * （何时该换、并发只换一次、失败怎么退避）是这个模块真正容易写错的地方，
 * 而它不该依赖一个真实 HTTP 服务端才能被验证。
 */
public interface TokenClient {

    /** 是否配置了客户端凭据（{@code oauth2.credential}）。没有凭据时只能靠交换续期。 */
    boolean hasCredential();

    /**
     * 用一张仍然有效的票换一张新的（RFC 8693）。
     *
     * @throws TokenRefreshException 服务端拒绝或不可达
     */
    IssuedToken exchange(String subjectToken);

    /**
     * 用客户端凭据取票（RFC 6749 {@code client_credentials}）。
     *
     * @throws TokenRefreshException 服务端拒绝或不可达
     * @throws IllegalStateException 没有配置凭据
     */
    IssuedToken clientCredentials();
}
