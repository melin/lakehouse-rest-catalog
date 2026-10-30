package io.github.melin.lakehouse.rest.service;

import java.time.Instant;

/**
 * 一次 {@code AssumeRole} 换回来的三件套与到期时刻。
 *
 * <p>与 {@link StsSession} 分开而不是复用：两者的字段几乎一样，但 {@link StsSession}
 * 是 AWS SDK 的返回、带 SDK 的类型习惯，而这里是阿里云 JSON 里那四个字符串。
 * 合成一个类会让「给 OSS 加一个 {@code expiration} 的解析容错」这类改动
 * 顺手改到 S3 的行为上——两条云链的解析容错策略不该互相牵扯。
 *
 * @param accessKeyId     临时 AccessKeyId
 * @param secretAccessKey 临时 AccessKeySecret
 * @param securityToken   安全令牌，走 {@code x-oss-security-token} 头
 * @param expiration      到期时刻（云给的）；解析不出来时为 {@code null}，调用方按 TTL 兜底
 */
public record OssStsSession(String accessKeyId,
                            String secretAccessKey,
                            String securityToken,
                            Instant expiration) {
}
