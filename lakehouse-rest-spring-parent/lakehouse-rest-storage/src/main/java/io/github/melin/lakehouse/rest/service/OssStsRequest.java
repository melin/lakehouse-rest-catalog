package io.github.melin.lakehouse.rest.service;

/**
 * 一次 OSS {@code AssumeRole} 请求的全部输入。
 *
 * <p>之所以把「调用者凭据」也放进请求对象：阿里云的 STS 是一个**独立服务**
 * （{@code sts.aliyuncs.com}），它不认识我们服务端持有的任何上下文，必须由调用方
 * 用一对 AK/SK 显式签名。这与 AWS SDK 那边「凭据由 SDK 的凭证链提供」的形状不同，
 * 所以两个客户端不能共用同一个接口。
 *
 * @param roleArn         被 assume 的 RAM 角色：{@code acs:ram::<account-id>:role/<name>}
 * @param sessionName     会话名，进云侧审计日志
 * @param durationSeconds 有效期，阿里云要求 900–3600
 * @param policy          收窄用的 policy JSON；{@code null} 表示不带（退化成角色自身权限）
 * @param endpoint        STS 端点，默认 {@code https://sts.aliyuncs.com}
 * @param accessKeyId     调用者（服务身份）的 AK
 * @param secretAccessKey 调用者（服务身份）的 SK
 */
public record OssStsRequest(String roleArn,
                            String sessionName,
                            long durationSeconds,
                            String policy,
                            String endpoint,
                            String accessKeyId,
                            String secretAccessKey) {
}
