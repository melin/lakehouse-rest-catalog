package io.github.melin.lakehouse.rest.engine.auth;

/**
 * 换票端点返回的一张票。
 *
 * <p>{@code expiresInSeconds} 可空：RFC 6749 §5.1 把 {@code expires_in} 列为
 * RECOMMENDED（不是 REQUIRED），而「缺了它」与「它是 0」是两件事——
 * 前者还可以去读 JWT 的 {@code exp}，后者意味着这张票当场就过期。用一个
 * {@code long} 加哨兵值会把这两件事糊在一起，所以这里用可空的包装类型。
 *
 * @param token            令牌本体
 * @param expiresInSeconds 服务端声明的有效期（秒）；{@code null} 表示响应里没给
 * @param scope            服务端回显的 scope；{@code null} 表示没给
 */
public record IssuedToken(String token, Long expiresInSeconds, String scope) {
}
