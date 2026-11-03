package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRoleRepository;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProvider;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProviderProbe;
import io.github.melin.lakehouse.rest.dto.ManagementDtos.IdentityProviderProbeStep;
import io.github.melin.lakehouse.rest.service.IdentityProviderService;
import io.github.melin.lakehouse.rest.service.OidcClaimSampleStore;
import io.github.melin.lakehouse.rest.service.OidcService;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 身份接入探活的**判定逻辑**：每种「能配错的地方」是否都被指出来。
 *
 * <p>全是不起 HTTP 的单元测试，因为要断言的正是「连不上之后配置类步骤还要照常给结论」
 * 与「每一类误配各有一句人话」——这两件事用真实 IdP 反而测不稳。
 * Issuer 统一填一个必然连不上的回环地址（`http://127.0.0.1:1`），
 * 于是 discovery 稳定 FAIL，而 endpoints / mapping / provisioning 三步按配置走。
 */
class IdentityProviderServiceTests {

    @Test
    void unreachableIssuerFailsDiscoveryWithoutHidingConfigProblems() {
        IdentityProviderProbe probe = probe(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("http://127.0.0.1:1/realms/x");
            oidc.setClientId("paimon-console");
            // 故意留空：IdP 连不上时，这条「回调地址没配」仍然必须被说出来
        });

        assertEquals("FAIL", probe.verdict());
        assertEquals("FAIL", step(probe, "discovery").status());
        assertEquals("SKIPPED", step(probe, "jwks").status());
        assertTrue(step(probe, "endpoints").detail().contains("redirect-uri"),
                "配置类步骤不因连接失败而跳过");
    }

    @Test
    void allGoodConfigOnlyFailsOnUnreachableIssuer() {
        IdentityProviderProbe probe = probe(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("http://127.0.0.1:1/realms/x");
            oidc.setClientId("paimon-console");
            oidc.setAudience("paimon-console");
            oidc.setRedirectUri("https://catalog.example.com/console/auth/callback");
            oidc.setGroupClaim("groups");
        });

        assertEquals("FAIL", probe.verdict(), "只有 discovery 这一项该失败");
        assertEquals("OK", step(probe, "endpoints").status());
        assertEquals("OK", step(probe, "mapping").status());
        assertEquals("OK", step(probe, "provisioning").status());
    }

    @Test
    void customGroupClaimWarnsWithTheTwoUsualCulprits() {
        IdentityProviderProbe probe = probe(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("http://127.0.0.1:1/realms/x");
            oidc.setRedirectUri("https://catalog.example.com/console/auth/callback");
            oidc.setGroupClaim("my-groups");
        });

        IdentityProviderProbeStep mapping = step(probe, "mapping");
        assertEquals("WARN", mapping.status());
        assertTrue(mapping.detail().contains("my-groups"));
        assertTrue(mapping.detail().contains("cognito:groups"), "提示里要给出两个最常见的取值");
    }

    @Test
    void principalClaimOtherThanSubWarnsBecauseTheNameDrifts() {
        IdentityProviderProbe probe = probe(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("http://127.0.0.1:1/realms/x");
            oidc.setRedirectUri("https://catalog.example.com/console/auth/callback");
            oidc.setPrincipalClaim("email");
        });

        assertEquals("WARN", step(probe, "mapping").status());
        assertTrue(step(probe, "mapping").detail().contains("改邮箱"));
    }

    @Test
    void strictSyncModeAndAutoProvisionOffAreCalledOut() {
        IdentityProviderProbe probe = probe(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("http://127.0.0.1:1/realms/x");
            oidc.setRedirectUri("https://catalog.example.com/console/auth/callback");
            oidc.setAutoProvision(false);
            oidc.setSyncMode(RestServerProperties.Auth.Oidc.SyncMode.STRICT);
        });

        IdentityProviderProbeStep provisioning = step(probe, "provisioning");
        assertEquals("WARN", provisioning.status());
        assertTrue(provisioning.detail().contains("每页 403"));
        assertTrue(provisioning.detail().contains("清空"));
    }

    @Test
    void unknownDefaultRoleIsReportedByName() {
        IdentityProviderProbe probe = probe(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("http://127.0.0.1:1/realms/x");
            oidc.setRedirectUri("https://catalog.example.com/console/auth/callback");
            oidc.setDefaultRoles(new java.util.ArrayList<>(List.of("analyst")));
        });

        assertTrue(step(probe, "provisioning").detail().contains("analyst 不存在"),
                "配错角色名不会报错，只会静默少一个角色——必须在自检里说出来");
    }

    @Test
    void audienceDifferentFromClientIdIsQuestioned() {
        IdentityProviderProbe probe = probe(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("http://127.0.0.1:1/realms/x");
            oidc.setClientId("paimon-console");
            oidc.setAudience("account");
            oidc.setRedirectUri("https://catalog.example.com/console/auth/callback");
        });

        assertEquals("WARN", step(probe, "endpoints").status());
        assertTrue(step(probe, "endpoints").detail().contains("audience"));
    }

    @Test
    void readNeverExposesAnythingButConfiguration() {
        IdentityProviderService service = service(oidc -> {
            oidc.setEnabled(true);
            oidc.setIssuerUri("https://idp.example.com/realms/x");
            oidc.setClientId("paimon-console");
            oidc.setAllowedEmailDomains(new java.util.ArrayList<>(List.of("example.com")));
            oidc.setDefaultRoles(new java.util.ArrayList<>(List.of("viewer")));
        });

        IdentityProvider config = service.read();
        assertTrue(config.enabled());
        assertEquals("paimon-console", config.clientId());
        assertEquals("ON_LOGIN", config.syncMode());
        assertEquals(List.of("example.com"), config.allowedEmailDomains());
        assertEquals(List.of("viewer"), config.defaultRoles());
    }

    // ------------------------------------------------------------------ 工具

    private interface Customizer {
        void accept(RestServerProperties.Auth.Oidc oidc);
    }

    private static IdentityProviderService service(Customizer customizer) {
        RestServerProperties properties = new RestServerProperties();
        customizer.accept(properties.getAuth().getConsole().getOidc());
        PrincipalRoleRepository repository = mock(PrincipalRoleRepository.class);
        when(repository.findByNameIn(any())).thenReturn(List.of());
        return new IdentityProviderService(properties,
                new OidcService(properties, Clock.systemUTC()),
                new OidcClaimSampleStore(),
                repository);
    }

    private static IdentityProviderProbe probe(Customizer customizer) {
        return service(customizer).probe();
    }

    private static IdentityProviderProbeStep step(IdentityProviderProbe probe, String name) {
        return probe.steps().stream()
                .filter(step -> step.step().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("缺少步骤 " + name));
    }
}
