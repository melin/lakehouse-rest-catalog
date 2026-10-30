package io.github.melin.lakehouse.rest.service;

import java.time.Instant;

/**
 * 一次 GCS 令牌交换换回来的东西：一个更小权限的访问令牌 + 它的到期时刻。
 *
 * @param accessToken 交换出来的令牌（仍然是不透明字符串，形状与源令牌一样）
 * @param expiresAt   到期时刻；响应里没有 {@code expires_in} 时为 {@code null}
 */
public record GcsDownscopedToken(String accessToken, Instant expiresAt) {
}
