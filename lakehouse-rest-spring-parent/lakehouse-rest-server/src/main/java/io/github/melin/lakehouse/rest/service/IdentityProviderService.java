package io.github.melin.lakehouse.rest.service;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.entity.PrincipalRoleEntity;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityClaimSample;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityClaimSamples;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProvider;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProviderProbe;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProviderProbeStep;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 身份接入的<b>只读</b>视图：生效配置、连通性、最近登录的 claim 形状。
 *
 * <h2>为什么没有写端点</h2>
 *
 * <p>这一页展示的每一项都来自部署配置（`application.yml` / ConfigMap / 环境变量）。
 * 让浏览器改它们意味着「一个登录进来的人可以改掉自己的准入规则」，而配错的表现是
 * <b>所有人都登不上</b>——那需要重启、回滚与审计流程，不是一个下拉框能承担的操作。
 * 因此这里只回答三个问题：现在是什么、能不能连上、最近登录的人带回来什么 claim。
 *
 * <h2>三个方法各自回答什么</h2>
 *
 * <ul>
 *   <li>{@link #read()}：生效值。<b>不回密钥类字段</b>——OIDC 是公开客户端，本来也没有密钥；
 *       这条约束写在这里是为了防止将来有人把 `client-secret` 加进这个 DTO。
 *   <li>{@link #probe()}：分五步跑一遍，每步给结论。判据见 {@link #probeSteps}：
 *       能自动判的才判，判不了的（例如「IdP 里到底有没有这个组」）明说判不了，
 *       不假装给一个 OK。
 *   <li>{@link #samples()}：来自 {@link OidcClaimSampleStore}，只含打码后的形状。
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdentityProviderService {

    /** `group-claim` 的常见取值。不在这个集合里不算错，只提示一句「确认一下」。 */
    private static final Set<String> COMMON_GROUP_CLAIMS = Set.of(
            "groups", "cognito:groups", "roles", "realm_access.roles", "memberOf");

    private final RestServerProperties properties;

    private final OidcService oidcService;

    private final OidcClaimSampleStore samples;

    private final PrincipalRoleRepository roles;

    /** 生效配置。<b>没有密钥字段</b>，见类注释。 */
    public IdentityProvider read() {
        RestServerProperties.Auth.Oidc oidc = oidc();
        return new IdentityProvider(
                oidcService.enabled(),
                properties.getAuth().getConsole().isRequired(),
                text(oidc.getIssuerUri()),
                text(oidc.getClientId()),
                text(oidc.getAudience()),
                text(oidc.getRedirectUri()),
                text(oidc.getScope()),
                text(oidc.getPrincipalClaim()),
                text(oidc.getGroupClaim()),
                text(oidc.getDisplayNameClaim()),
                oidc.getSyncMode() == null ? null : oidc.getSyncMode().name(),
                oidc.isAutoProvision(),
                oidc.isAllowAdoptLocal(),
                List.copyOf(nullSafe(oidc.getAllowedEmailDomains())),
                List.copyOf(nullSafe(oidc.getDefaultRoles())));
    }

    /**
     * 连通性测试：五步，每步给一条人话结论。
     *
     * <p>顺序是「先看能不能连，再看连上之后配置对不对」——但**配置类步骤不因连接失败而跳过**：
     * IdP 暂时连不上时，管理员仍然需要知道「`sync-mode` 现在是 `STRICT`，改它会清空权限」
     * 这类事实。跳过的只有真正依赖发现文档的那一步（JWKS 地址来自发现文档）。
     */
    public IdentityProviderProbe probe() {
        List<IdentityProviderProbeStep> steps = probeSteps();
        String verdict = "OK";
        for (IdentityProviderProbeStep step : steps) {
            if ("FAIL".equals(step.status())) {
                verdict = "FAIL";
                break;
            }
            if ("WARN".equals(step.status())) {
                verdict = "WARN";
            }
        }
        return new IdentityProviderProbe(steps, verdict);
    }

    /** 最近登录的 claim 形状。新的在前，同一主体只留最近一次。 */
    public IdentityClaimSamples samples() {
        List<IdentityClaimSample> result = new ArrayList<>();
        for (OidcClaimSampleStore.ClaimSample sample : samples.samples()) {
            result.add(new IdentityClaimSample(sample.principal(), sample.issuer(), sample.subject(),
                    sample.email(), sample.groups(), sample.groupCount(),
                    sample.groupsClaimPresent(), sample.outcome(), sample.at()));
        }
        // processLocal 恒真：样本在本进程内存里，多副本时每个副本只记得自己处理过的那几次
        return new IdentityClaimSamples(result, true);
    }

    // ------------------------------------------------------------------ 内部

    private List<IdentityProviderProbeStep> probeSteps() {
        List<IdentityProviderProbeStep> steps = new ArrayList<>();
        OidcService.Probe probed = null;
        try {
            probed = oidcService.probe();
            steps.add(new IdentityProviderProbeStep("discovery", "OK",
                    "发现文档已取到，issuer 与配置一致"));
        } catch (RuntimeException e) {
            // 这里把服务端异常的原文回给管理员：它只含配置值与 HTTP 结论，
            // 不含任何令牌。含糊其辞会让人只能去翻服务端日志
            steps.add(new IdentityProviderProbeStep("discovery", "FAIL", message(e)));
        }

        steps.add(endpointsStep(probed));
        steps.add(probed == null
                ? new IdentityProviderProbeStep("jwks", "SKIPPED",
                        "发现文档没取到，拿不到 jwks_uri")
                : new IdentityProviderProbeStep("jwks", probed.keyCount() > 0 ? "OK" : "FAIL",
                        probed.keyCount() > 0
                                ? "JWKS 可解析，共 " + probed.keyCount() + " 把公钥"
                                : "JWKS 里没有可用的公钥，令牌一律验不过"));
        steps.add(mappingStep());
        steps.add(provisioningStep());
        return steps;
    }

    /**
     * 端点与回调地址的形态自检。
     *
     * <p>三项：发现的端点是否走 https（回环地址除外，本地调试要用 http）、
     * `redirect-uri` 是否配了、`audience` 与 `client-id` 是否一致。
     *
     * <p>最后一项值得单独说：很多 IdP 把 access token 的 `aud` 填成 client_id，
     * 但也有填成别的（Cognito 的 access token 干脆没有 `aud`）。填错的表现是
     * **登录成功但每个请求 401**，而错误提示里只有「未通过鉴权」。
     */
    private IdentityProviderProbeStep endpointsStep(OidcService.Probe probed) {
        RestServerProperties.Auth.Oidc oidc = oidc();
        List<String> warnings = new ArrayList<>();
        if (probed != null) {
            if (!secure(probed.metadata().authorizationEndpoint())) {
                warnings.add("authorization_endpoint 不是 https（回环地址除外）");
            }
            if (!secure(probed.metadata().tokenEndpoint())) {
                warnings.add("token_endpoint 不是 https");
            }
            if (probed.metadata().endSessionEndpoint() == null) {
                // 不是错：IdP 可以不提供 RP-initiated logout，只是登出后浏览器会停在它自己的页面
                warnings.add("发现文档没有 end_session_endpoint，登出只会清本机令牌");
            }
        }
        if (!hasText(oidc.getRedirectUri())) {
            warnings.add("redirect-uri 为空，IdP 不会把浏览器送回来");
        } else if (!secure(oidc.getRedirectUri())) {
            warnings.add("redirect-uri 不是 https（回环地址除外），生产环境多数 IdP 会拒");
        }
        if (hasText(oidc.getAudience()) && hasText(oidc.getClientId())
                && !oidc.getAudience().equals(oidc.getClientId())) {
            warnings.add("audience 与 client-id 不同：确认签发方真的把 aud 填成了 " + oidc.getAudience());
        }
        return new IdentityProviderProbeStep("endpoints", warnings.isEmpty() ? "OK" : "WARN",
                warnings.isEmpty() ? "端点与回调地址形态正常" : String.join("；", warnings));
    }

    /**
     * claim 映射的自检。
     *
     * <p>这一节能自动判的只有两件事：`principal-claim` 是不是 `sub`
     * （换成 email 之类会随改名漂移），以及 `group-claim` 是不是常见取值。
     * **真正的问题（IdP 里到底有没有这个 claim）只有登录过的令牌能回答**，
     * 所以这两条只能是 WARN，且措辞上要说清「去 claim 样例页核对」。
     */
    private IdentityProviderProbeStep mappingStep() {
        RestServerProperties.Auth.Oidc oidc = oidc();
        List<String> warnings = new ArrayList<>();
        String principalClaim = text(oidc.getPrincipalClaim());
        if (!"sub".equals(principalClaim)) {
            warnings.add("principal-claim 是 " + principalClaim + "，不是 sub：主体名会随该字段变化"
                    + "（改名 / 改邮箱 = 换一个人）");
        }
        String groupClaim = text(oidc.getGroupClaim());
        if (!COMMON_GROUP_CLAIMS.contains(groupClaim)) {
            warnings.add("group-claim 是 " + groupClaim + "，不是常见取值：注意 Keycloak 的 full group path"
                    + " 会给 /组名，Cognito 叫 cognito:groups");
        }
        return new IdentityProviderProbeStep("mapping", warnings.isEmpty() ? "OK" : "WARN",
                warnings.isEmpty()
                        ? "claim 映射是常见取值；groups 到底有没有值看「令牌样例」"
                        : String.join("；", warnings));
    }

    /**
     * 供给开关的自检。
     *
     * <p>三件会让「登录成功、权限为零」的配置：关掉 JIT、`default-roles` 里有不存在的角色、
     * `sync-mode=strict`（IdP 少给一次 groups 就清空权限）。前两条是错，第三条是取舍，
     * 因此都只报结论与后果，不改配置。
     */
    private IdentityProviderProbeStep provisioningStep() {
        RestServerProperties.Auth.Oidc oidc = oidc();
        List<String> warnings = new ArrayList<>();
        if (!oidc.isAutoProvision()) {
            warnings.add("auto-provision 关着：没有预注册过的主体登录后每页 403");
        }
        if (oidc.getSyncMode() == RestServerProperties.Auth.Oidc.SyncMode.STRICT) {
            warnings.add("sync-mode=strict：令牌不带 groups 时清空 IDP 来源的角色");
        }
        if (oidc.getSyncMode() == RestServerProperties.Auth.Oidc.SyncMode.OFF) {
            warnings.add("sync-mode=off：组映射不生效，角色只能手工授");
        }
        List<String> configured = nullSafe(oidc.getDefaultRoles());
        if (!configured.isEmpty()) {
            Set<String> existing = new LinkedHashSet<>();
            for (PrincipalRoleEntity role : roles.findByNameIn(configured)) {
                existing.add(role.getName());
            }
            for (String name : configured) {
                if (!existing.contains(name)) {
                    warnings.add("default-roles 里的 " + name + " 不存在，登录时会被忽略");
                }
            }
        }
        if (oidc.isAllowAdoptLocal()) {
            warnings.add("allow-adopt-local 开着：能让 IdP 签发同名 sub 的人可以接管本地主体");
        }
        return new IdentityProviderProbeStep("provisioning", warnings.isEmpty() ? "OK" : "WARN",
                warnings.isEmpty() ? "供给开关正常" : String.join("；", warnings));
    }

    private RestServerProperties.Auth.Oidc oidc() {
        return properties.getAuth().getConsole().getOidc();
    }

    /** https 或回环地址（本地 Keycloak 用 http://localhost:8080 是常见部署）。 */
    private static boolean secure(String url) {
        if (!hasText(url)) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return true;
            }
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && ("localhost".equals(host.toLowerCase(Locale.ROOT))
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 异常的消息可能为空（例如 NPE），兜一句「没有更多信息」。 */
    private static String message(RuntimeException e) {
        String value = e.getMessage();
        return value == null || value.isBlank() ? e.getClass().getSimpleName() : value;
    }

    private static List<String> nullSafe(List<String> values) {
        return values == null ? List.of() : values;
    }

    private static String text(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
