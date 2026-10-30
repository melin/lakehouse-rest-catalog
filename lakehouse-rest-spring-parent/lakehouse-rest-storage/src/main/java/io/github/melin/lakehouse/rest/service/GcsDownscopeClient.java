package io.github.melin.lakehouse.rest.service;

/**
 * GCS 的 downscoped token 签发（设计稿 P1-4）。
 *
 * <p>做成接口是为了让「boundary 长什么样、键怎么发」与「怎么调 STS」分开：
 * provider 的单测注入假实现，真实实现只在真正取票时出场。
 */
public interface GcsDownscopeClient {

    /**
     * 交换一个更小权限的令牌。
     *
     * @throws io.github.melin.lakehouse.rest.support.ApiException 调用失败（503，消息里带 GCS 的错误码）
     */
    GcsDownscopedToken downscope(GcsDownscopeRequest request);
}
