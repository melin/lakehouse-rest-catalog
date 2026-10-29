package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.support.VendedStorageCredential;
import java.util.Optional;

/**
 * 一个可以「真去云上换一张票」的签发器。
 *
 * <p><b>为什么要有这一层，而不直接在 {@code vend()} 的 switch 里加分支。</b>
 * 转发（把配置里的长期密钥发出去）与签发（拿服务自己的身份去 STS 换短期票据）
 * 是两种性质完全不同的动作：前者一次内存拷贝，后者是一次**有配额、会超时、会限流、
 * 会因权限配置出错**的远程调用。混在同一个 switch 里，最直接的后果是
 * 「只是想读张表」的路径上多出一个可能失败的依赖，而且默认就该打开——
 * 因为没人会为了保持现状而关掉一个看起来更好的选项。
 *
 * <p>做成链之后，默认链里**没有**任何需要调云的 provider，行为与今天逐字节相同；
 * 想启用 STS 的部署显式把 {@code s3-token} 加进
 * {@code lakehouse.rest.credential.providers}。这是设计稿 P0-1 的落法：
 * 能力先具备、默认不启用，而不是默认启用再想办法关掉。
 *
 * <p>{@link #vend} 返回 {@link Optional#empty()} 表示「这一档我不接」，链会继续往下走；
 * 抛异常表示「我接了但失败了」。两者的区别是**故意的**：
 * 前者是正常的路由（例如 catalog 没配 {@code roleArn}），
 * 后者必须让调用方看见（assume 失败时静默降级成转发，等于把「本来就该报错」
 * 变成「跑到一半才 403」，见设计 §5.8）。
 */
public interface CredentialProvider {

    /** provider 名，进诊断日志与凭据的 {@code provider} 字段。 */
    String name();

    /**
     * 尝试签发。
     *
     * @return 签好了就是凭据；这一档不适用则 {@link Optional#empty()}
     * @throws io.github.melin.lakehouse.rest.support.ApiException 接了但签发失败（必须可见）
     */
    Optional<VendedStorageCredential> vend(CredentialContext context);
}
