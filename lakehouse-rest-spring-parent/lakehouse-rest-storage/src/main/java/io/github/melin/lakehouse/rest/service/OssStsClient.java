package io.github.melin.lakehouse.rest.service;

/**
 * 阿里云 RAM STS 的 {@code AssumeRole}。
 *
 * <p>做成接口是为了让「签出来的票长什么样」与「怎么调用云」分开：
 * {@code OssStsCredentialProvider} 的单测只关心前者，注入一个假实现即可，
 * 不需要真的连 {@code sts.aliyuncs.com}。
 */
public interface OssStsClient {

    /**
     * 换一份临时凭据。
     *
     * @throws io.github.melin.lakehouse.rest.support.ApiException 调用失败（503，消息里点名云侧的错误码）
     */
    OssStsSession assumeRole(OssStsRequest request);
}
