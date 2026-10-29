package io.github.melin.lakehouse.rest.service;

import java.time.Instant;

/**
 * 一次 STS 会话的四件套。
 *
 * <p>为什么不直接返回 SDK 的 {@code Credentials} 类型：那个类活在
 * {@code software.amazon.awssdk.auth} 里，把它带进接口意味着**每一个**用到
 * {@link AssumeRoleClient} 的地方（含单测）都要把 AWS SDK 放上类路径。
 * STS 是可选能力，凭据层不该因为「某部署启用了它」就整体绑上 SDK。
 *
 * <p>{@code expiration} 是**凭据自己的寿命**，不是「建议重取时间」。
 * 它的毫秒值会原样进 {@code expireTimeInMs}，而那个值又决定客户端要不要来续期——
 * 三者同源是刻意的：分成两个配置项就会出现「签名 15 分钟、声明 1 小时」的错配，
 * 表现为客户端拿着已过期的票继续用，拿到一个不含任何凭据线索的 403。
 */
public record StsSession(String accessKeyId,
                         String secretAccessKey,
                         String sessionToken,
                         Instant expiration) {

    /** 到期时刻的毫秒值；{@code expiration} 缺失时返回 {@code 0}（视为静态）。 */
    public long expireTimeInMs() {
        return expiration == null ? 0L : expiration.toEpochMilli();
    }
}
