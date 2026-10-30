package io.github.melin.lakehouse.rest.service;

import java.time.Instant;

/**
 * 换票结果。
 *
 * <p>{@code expiresAt} 允许为 {@code null}（响应里没有 {@code expires_in} 时）：
 * 没有到期时刻的令牌不能被缓存，只能一次性使用——这比「假设一小时后过期」安全，
 * 后者会把一张不知道寿命的票放进缓存，然后在某个时刻开始 401。
 */
public record GcsAccessToken(String token, Instant expiresAt) {
}
