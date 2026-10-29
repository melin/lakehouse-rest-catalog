package io.github.melin.lakehouse.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.melin.lakehouse.rest.config.RestServerProperties;
import io.github.melin.lakehouse.rest.domain.repo.PrincipalRepository;
import io.github.melin.lakehouse.rest.dto.ConsoleDtos;
import io.github.melin.lakehouse.rest.service.ConsolePasswordService;
import io.github.melin.lakehouse.rest.service.OidcService;
import io.github.melin.lakehouse.rest.service.TokenAuthenticationService;
import io.github.melin.lakehouse.rest.web.ConsoleAuthController;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 控制台登录引导里那一份 OIDC 配置：**下发什么、什么情况下下发 {@code null}**。
 *
 * <p>被钉住的是「发现文档 → DTO」这一跳。{@link OidcServiceTests} 已经证明
 * {@code end_session_endpoint} 能从文档里解析出来，但解析出来之后没人往下传过：
 * 字段在 {@code Metadata} 里躺了很久，控制台拿到的配置里根本没有它，
 * 于是「退出登录」永远是纯本地行为，IdP 那边的会话一直活着。
 *
 * <p>这一跳没有编译期保证——两个 record 各加一个字段，漏传的那个是 {@code null}，
 * 而 {@code null} 在这里恰好是合法值（「IdP 没提供」）。因此只能靠断言。
 *
 * <p>用真实的 HTTP 服务器冒充 IdP 而不是 mock 掉 {@code OidcService}：
 * mock 掉就等于把要测的那一段（解析出端点的结果如何流进 DTO）也一起 mock 了。
 */
class ConsoleAuthOidcConfigTests {

    private HttpServer server;

    private String issuer;

    /** 发现文档正文，用例可替换它来模拟「IdP 不给 end_session_endpoint」。 */
    private final AtomicReference<String> discovery = new AtomicReference<>();

    @BeforeEach
    void startFakeIdp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
        discovery.set(discoveryDocument(true));
        server.createContext("/.well-known/openid-configuration",
                exchange -> respond(exchange, discovery.get()));
        server.start();
    }

    @AfterEach
    void stopFakeIdp() {
        if (server != null) {
            server.stop(0);
        }
    }

    /**
     * 登出端点与回跳地址都下发。
     *
     * <p>两个字段一起断言，是因为前端只用一条判据（有没有值）决定跳不跳，
     * 少任何一个的表现不同：少了端点 → 永远不跳（SSO 会话留着）；
     * 少了回跳地址 → 跳到 IdP 登出页却回不来。
     */
    @Test
    void logoutEndpointAndRedirectAreAdvertised() {
        ConsoleDtos.OidcClientConfig config = config("http://localhost:8080/console/login");

        assertNotNull(config, "OIDC 配置齐全时应当下发");
        assertEquals(issuer + "/logout", config.endSessionEndpoint());
        assertEquals("http://localhost:8080/console/login", config.postLogoutRedirectUri());
    }

    /**
     * 没配回跳地址时下发 {@code null}，而不是替用户拼一个。
     *
     * <p>拼出来的地址几乎必然没在 IdP 侧登记过，而 RP-initiated logout 要求
     * {@code post_logout_redirect_uri} 逐字匹配登记值——拒绝方式是在 IdP 页面上
     * 报一句语焉不详的错，而那时用户的本机令牌已经清掉了。
     */
    @Test
    void blankPostLogoutRedirectUriBecomesNull() {
        assertNull(config("").postLogoutRedirectUri());
        assertNull(config("   ").postLogoutRedirectUri());
        assertNull(config(null).postLogoutRedirectUri());
    }

    /**
     * IdP 不给 {@code end_session_endpoint} 时下发 {@code null}。
     *
     * <p>OIDC 不强制这个端点存在。此时控制台退回「只清本机令牌」——
     * 这正是改造前的行为，不是错误。
     */
    @Test
    void missingEndSessionEndpointBecomesNull() {
        discovery.set(discoveryDocument(false));

        assertNull(config(null).endSessionEndpoint());
    }

    /** 拿一次登录引导；{@code auth()} 里 OIDC 那一段与其余字段无关，此处只看它。 */
    private ConsoleDtos.OidcClientConfig config(String postLogoutRedirectUri) {
        RestServerProperties properties = new RestServerProperties();
        RestServerProperties.Auth.Oidc oidc = properties.getAuth().getConsole().getOidc();
        oidc.setEnabled(true);
        oidc.setIssuerUri(issuer);
        oidc.setClientId("lakehouse-console");
        oidc.setRedirectUri("http://localhost:8080/console/auth/callback");
        oidc.setPostLogoutRedirectUri(postLogoutRedirectUri);

        // 口令登录开着：让 methods() 有东西可列，避免「一个方式都没有」的配置噪声
        ConsolePasswordService passwords = mock(ConsolePasswordService.class);
        when(passwords.enabled()).thenReturn(true);
        // 请求不带 Authorization，认证服务如实回答「不知道这个令牌」
        TokenAuthenticationService tokens = mock(TokenAuthenticationService.class);
        when(tokens.authenticate(nullable(String.class))).thenReturn(Optional.empty());

        ConsoleAuthController controller = new ConsoleAuthController(
                properties,
                tokens,
                new OidcService(properties, Clock.systemUTC()),
                passwords,
                mock(PrincipalRepository.class));

        HttpServletRequest request = mock(HttpServletRequest.class);
        return controller.auth(request).oidc();
    }

    private String discoveryDocument(boolean withEndSession) {
        String document = "{\"issuer\":\"" + issuer + "\","
                + "\"authorization_endpoint\":\"" + issuer + "/authorize\","
                + "\"token_endpoint\":\"" + issuer + "/token\","
                + "\"jwks_uri\":\"" + issuer + "/jwks\","
                + "\"id_token_signing_alg_values_supported\":[\"RS256\"]}";
        if (!withEndSession) {
            return document;
        }
        return document.replaceFirst("\\}$", ",\"end_session_endpoint\":\"" + issuer + "/logout\"}");
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
