package io.github.melin.lakehouse.rest.web;

import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityClaimSamples;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProvider;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProviderProbe;
import io.github.melin.lakehouse.rest.service.AuthorizationService;
import io.github.melin.lakehouse.rest.service.IdentityProviderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 身份接入的只读端点：生效配置、连通性测试、最近登录的 claim 形状。
 *
 * <p>三个端点是一个整体，缺哪一个都会让「登录成功但没有权限」只能靠翻日志排查：
 * 配置告诉你「我以为什么」，连通性告诉你「现在连不连得上」，
 * claim 形状告诉你「令牌里<b>实际</b>有什么」——最后这条才是最终判据。
 *
 * <p><b>整组只读，也整组要求服务管理员。</b>读密码一样的东西不加限制是没有意义的：
 * claim 形状里有打码后的组名与邮箱域名，那足以画出这个组织的权限拓扑。
 */
@RestController
@RequestMapping("/api/management/v1/identity-provider")
@RequiredArgsConstructor
public class IdentityProviderController {

    private final IdentityProviderService identityProvider;

    private final AuthorizationService authorizationService;

    /** {@code GET /identity-provider}：生效中的配置（不含任何密钥）。 */
    @GetMapping
    public IdentityProvider read() {
        authorizationService.requireServiceAdmin("read the identity provider configuration");
        return identityProvider.read();
    }

    /**
     * {@code POST /identity-provider/probe}：主动探一次，回五步结论。
     *
     * <p>用 POST 而不是 GET：它会真的向 IdP 发请求，不该被缓存、也不该被预览链接触发。
     */
    @PostMapping("/probe")
    public IdentityProviderProbe probe() {
        authorizationService.requireServiceAdmin("probe the identity provider");
        return identityProvider.probe();
    }

    /**
     * {@code GET /identity-provider/claim-samples}：最近若干次登录的 claim 形状。
     *
     * <p>样本只在**本进程内存**里（{@code OidcClaimSampleStore}），因此多副本部署时
     * 每个副本只记得自己处理过的那几次登录。响应里的 {@code processLocal=true}
     * 就是把这条限制说出来，免得「这里只看到一半的人」被当成故障。
     */
    @GetMapping("/claim-samples")
    public IdentityClaimSamples claimSamples() {
        authorizationService.requireServiceAdmin("read OIDC claim samples");
        return identityProvider.samples();
    }
}
