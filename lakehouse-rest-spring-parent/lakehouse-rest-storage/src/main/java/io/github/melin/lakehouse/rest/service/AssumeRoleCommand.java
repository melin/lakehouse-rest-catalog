package io.github.melin.lakehouse.rest.service;

import java.time.Duration;
import java.util.Map;

/**
 * 一次 {@code AssumeRole} 调用的入参。
 *
 * <p>单独成一个 record 而不是把七个参数摊在方法签名上：它是**可断言的**。
 * 设计稿 P0-2 的验收是「抓包断言 {@code DurationSeconds}」，但在没有真实 STS 的
 * 单测里能做的是断言「传给客户端的那份命令」，所以命令对象必须能被拿到、能被比较。
 * 参数摊开之后就只剩「调用了没抛异常」这一种断言，而那正是最没用的那种。
 *
 * @param policy session policy 的 JSON；{@code null} 表示不带 policy（整角色权限）。
 *               注意「不带 policy」不等于「用角色全部权限」以外的意思——两者的差别
 *               在审计与追责上很大，所以留空必须显式表达
 * @param stsEndpoint 自定义 STS 端点；{@code null} 表示用区域默认端点
 * @param tags 审计用的 session tags；**空表示不传 tag 集合**（不是传空数组），
 *             让关闭该特性时的请求与改造前逐字节相同。键值对须在调用前规整，
 *             非法的键值会让 STS 在签名阶段直接拒绝
 * @param callerAccessKey 用哪把钥匙去 assume。{@code null} 表示交给 SDK 的默认链
 *                        （实例角色 / IRSA / 环境变量）——那是部署自己的身份，
 *                        不需要任何配置项就能工作。非空时是 catalog 上填的 AK/SK：
 *                        「用这把只能 sts:AssumeRole 的钥匙去换票」是常见的自建机房形态，
 *                        而它属于某一个 catalog，不是一个全局配置
 * @param callerSecretKey 与上一条成对
 */
public record AssumeRoleCommand(String roleArn,
                                String externalId,
                                String roleSessionName,
                                Duration duration,
                                String policy,
                                String region,
                                String stsEndpoint,
                                Map<String, String> tags,
                                String callerAccessKey,
                                String callerSecretKey) {

    public AssumeRoleCommand {
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }

    /** 是否用 catalog 自带的钥匙签名。 */
    public boolean hasCallerCredentials() {
        return callerAccessKey != null && !callerAccessKey.isBlank()
                && callerSecretKey != null && !callerSecretKey.isBlank();
    }
}
