package io.github.melin.lakehouse.rest.service;

/**
 * 一次 {@code generateUserDelegationKey} 换回来的用户委托密钥。
 *
 * <p><b>{@code signedStart} / {@code signedExpiry} 保留服务端返回的原始字符串，
 * 不重新格式化。</b>它们既要拼进 SAS 的查询串（{@code skt} / {@code ske}），
 * 又要逐字进签名串。Azure 返回的是 {@code 2023-05-24T01:13:55.0000000Z} 这种带
 * 七位小数的形态，我们这边若按 {@code yyyy-MM-dd'T'HH:mm:ss'Z'} 重新格式化，
 * 两处字符串就与实际生效的密钥生命周期不一致——签名能算出来，但用的时候回
 * {@code AuthorizationFailure}，而它看起来像「权限不够」。原样透传是唯一不会错的做法。
 *
 * <p>{@code signedOid} / {@code signedTid} / {@code signedService} / {@code signedVersion}
 * 同理必须原样进签名串与查询串（{@code skoid} / {@code sktid} / {@code sks} / {@code skv}）。
 *
 * <p>{@code value} 是 base64 的 HMAC 密钥，**只用于签名，绝不下发**——
 * 拿到它等于拿到该账户在该时间段内任意位置签 SAS 的能力。
 */
public record UserDelegationKey(String signedOid,
                                String signedTid,
                                String signedStart,
                                String signedExpiry,
                                String signedService,
                                String signedVersion,
                                String value) {
}
