package io.github.melin.lakehouse.rest.service;

/**
 * 向云 STS 换一张短期票的那一层。
 *
 * <p>抽成接口的唯一目的是**可测**：单测里注入一个假实现，就能断言
 * 「传下去的 {@code DurationSeconds}、{@code RoleSessionName}、policy 是什么」，
 * 而不必真的碰 AWS。这不是形式主义——STS 是外部依赖，隐藏在一个
 * {@code static} 调用后面时，pipeline 里能验的就只剩「没抛异常」。
 *
 * <p>实现方要保证：失败抛异常而不是返回 {@code null}。调用链上
 * 「拿不到票」与「不需要票」是两件事，前者必须可见（设计 §5.8 的第三条推论）。
 */
public interface AssumeRoleClient {

    /**
     * 换票。
     *
     * @throws io.github.melin.lakehouse.rest.support.ApiException 云侧拒绝、网络不可达、限流
     */
    StsSession assumeRole(AssumeRoleCommand command);
}
