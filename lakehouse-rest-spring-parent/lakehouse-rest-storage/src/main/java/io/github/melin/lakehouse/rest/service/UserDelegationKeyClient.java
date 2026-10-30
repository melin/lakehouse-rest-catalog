package io.github.melin.lakehouse.rest.service;

/**
 * 换用户委托密钥的那一次远程调用。
 *
 * <p>做成接口的理由与 {@link AssumeRoleClient} 完全相同：真正的调用需要一套 Azure
 * 应用注册（以及 {@code generateUserDelegationKey} 的数据面权限），单测里假调用一次
 * 只能得到「没抛异常」的结论；而这一层最容易错的地方是**传下去的是什么**
 * （哪个账户、哪个租户、密钥窗口多长），不是它有没有返回。所以测试注入假实现、
 * 断言请求本身，真实网络路径留给 e2e。
 */
public interface UserDelegationKeyClient {

    /**
     * 换一张委托密钥。
     *
     * @throws io.github.melin.lakehouse.rest.support.ApiException 拿不到 AAD 令牌或服务端拒绝签发
     */
    UserDelegationKey requestKey(AzureDelegationRequest request);
}
