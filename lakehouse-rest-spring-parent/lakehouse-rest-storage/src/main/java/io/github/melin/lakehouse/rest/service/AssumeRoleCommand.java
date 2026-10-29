package io.github.melin.lakehouse.rest.service;

import java.time.Duration;

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
 */
public record AssumeRoleCommand(String roleArn,
                                String externalId,
                                String roleSessionName,
                                Duration duration,
                                String policy,
                                String region,
                                String stsEndpoint) {
}
