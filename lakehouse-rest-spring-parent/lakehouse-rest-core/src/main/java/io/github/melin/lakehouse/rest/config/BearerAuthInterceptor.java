package io.github.melin.lakehouse.rest.config;

import io.github.melin.lakehouse.rest.service.TokenAuthenticationService;
import io.github.melin.lakehouse.rest.support.ApiException;
import io.github.melin.lakehouse.rest.support.AuthenticatedToken;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Bearer 鉴权拦截器。
 *
 * <p>它要求令牌有两种情形，两者都作用在同一段路径上（见 {@code WebConfig}）：
 *
 * <ul>
 *   <li><b>整体鉴权</b>（{@code lakehouse.rest.auth.enabled=true}）：{@code /v1/**}、
 *       catalog API、管理 API 与控制台扩展端点全部要求令牌。
 *   <li><b>仅控制台门禁</b>（{@code lakehouse.rest.auth.console.required=true}，默认开，
 *       而整体鉴权关着）：<b>只有控制台扩展端点</b>要求令牌。这是「浏览器打开控制台
 *       要先登录」的实现方式，同时刻意<b>不</b>动数据面——把 {@code auth.enabled}
 *       默认打开会让开箱即用的 {@code curl /v1/config}、Spark 示例与两个验收脚本
 *       全部变成 401。代价是这层门禁挡的是界面而不是数据，这一点在登录页与
 *       {@code docs/console-auth.md} 里都写明了。
 * </ul>
 *
 * <p><b>本类只做两件事：取请求头里的令牌、把认证结果写进 {@link RequestContext}。</b>
 * 令牌来自哪里（服务端配置的静态令牌、控制台登录签发的访问令牌、外部 OIDC）
 * 由认证链判断，本类不关心也不会分叉——所以加一种认证方式不需要动这里。
 *
 * <p><b>「没有令牌」与「令牌无效」的响应体相同，但挑战头不同。</b>报文一字不差
 * （区分它们对攻击者有用——能确认「这个地址确实要鉴权」以及「我的令牌格式被识别了」），
 * 而 {@code WWW-Authenticate} 按 RFC 6750 §3.1 说明下一步该做什么：
 *
 * <ul>
 *   <li>没带令牌 → {@code Bearer}（规范要求此时不给 {@code error} 码）；</li>
 *   <li>令牌过期 → {@code error="invalid_token"} 加一句「已过期」；</li>
 *   <li>令牌无效 → 同样 {@code invalid_token}，措辞不同。</li>
 * </ul>
 *
 * <p>为什么值得多这一行：长作业续期失败时，作业日志里只有服务端回的状态码，
 * 而「票过期」与「票不对」在 401 上长得一模一样——前者该去看续期配置，后者该去看
 * 签名密钥或主体名。把区别放在挑战头里，是因为它<b>不进响应体</b>：
 * {@code ErrorResponse} 的形状是对外契约，为一句诊断说明改它不划算；
 * 而 {@code WWW-Authenticate} 本来就是用来讲「怎么才能通过」的地方。
 *
 * <p>「过期」不会泄露信息：能拿到这个答案的前提是签名与签发者都对，也就是
 * 调用方手里本来就有一张真票的副本。
 *
 * <p><b>例外路径由 {@code WebConfig} 声明</b>，本类看不到它们——拦截器根本不会被调用。
 * 那几个例外之所以必须存在（登录页要先读到「支持哪些登录方式」、登录与令牌端点本身
 * 就是用来换取令牌的），理由写在各自的控制器上。
 */
@Component
@RequiredArgsConstructor
public class BearerAuthInterceptor implements HandlerInterceptor {

    private static final String BEARER = "Bearer ";

    /**
     * 挑战里那个 scheme 名。
     *
     * <p>与 {@link #BEARER} 分开一个常量：前者带尾空格，是「怎么从请求头里切出令牌」的
     * 前缀；这个不带，是「告诉客户端该用哪种方式」的 scheme 名。合成一个常量会让
     * 挑战头里多出一个尾空格——客户端倒是能容忍，但抓包对比时很难看出是为什么。
     */
    private static final String BEARER_CHALLENGE = "Bearer";

    /** 控制台自己的扩展端点。门禁只作用于这一段路径。 */
    private static final String CONSOLE_API = "/api/console/v1/";

    private final RestServerProperties properties;

    private final TokenAuthenticationService tokens;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = bearerToken(request);
        // 请求关联 ID 先于主体名定下来：事件、审计与日志都按它串起同一次请求。
        // 客户端带了 X-Request-Id 就透传（跨服务共用一个 id 才有意义），没有才生成。
        String requestId = request.getHeader("X-Request-Id");
        RequestContext.setRequestId(requestId == null || requestId.isBlank()
                ? java.util.UUID.randomUUID().toString()
                : requestId.trim());

        if (!requiresToken(request)) {
            // 不需要令牌时也要尽量认出令牌：静态令牌与已签发的访问令牌都能给出真实主体名，
            // 于是「本地用 -H 'Authorization: Bearer alice' 以指定主体调试」照常可用，
            // 而带着令牌访问的实体会被记为它自己的主体而不是 anonymous。
            // 认不出来就退回「令牌即主体名」——这是既有的本地调试约定，保持兼容。
            RequestContext.setPrincipal(token == null
                    ? properties.getAuth().getPrincipal()
                    : tokens.authenticate(token).map(AuthenticatedToken::principal).orElse(token));
            return true;
        }

        if (token == null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, BEARER_CHALLENGE);
            throw new ApiException(401, null, null, "No auth for this resource");
        }
        AuthenticatedToken identity = tokens.authenticate(token).orElse(null);
        if (identity == null) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, challengeFor(tokens.rejectionOf(token)));
            throw new ApiException(401, null, null, "No auth for this resource");
        }
        RequestContext.setPrincipal(identity.principal());
        return true;
    }

    /**
     * 按被拒原因写出 {@code WWW-Authenticate}。
     *
     * <p>「没带令牌」那一条刻意不带 {@code error} 码：RFC 6750 §3.1 说得很清楚——
     * 调用方连凭据都没发时，不该收到「凭证无效」这种结论，那会让一个本来只是
     * 忘了加头的客户端去怀疑自己的令牌。
     */
    private static String challengeFor(TokenAuthenticationService.Rejection rejection) {
        return switch (rejection) {
            case MISSING -> BEARER_CHALLENGE;
            case EXPIRED -> BEARER_CHALLENGE
                    + ", error=\"invalid_token\", error_description=\"the access token has expired\"";
            case INVALID -> BEARER_CHALLENGE
                    + ", error=\"invalid_token\", error_description=\"the access token is not valid\"";
        };
    }

    /**
     * 这一个请求是否必须带令牌。
     *
     * <p>整体鉴权开着时是全部受管路径；只有控制台门禁开着时是控制台端点那一段。
     * 判据用请求路径而不是「是哪个控制器」：拦截器拿不到处理器映射的业务含义，
     * 而路径前缀在这里就是稳定的契约（与 {@code WebConfig} 的注册模式同一份字符串）。
     */
    private boolean requiresToken(HttpServletRequest request) {
        if (properties.getAuth().isEnabled()) {
            return true;
        }
        return properties.getAuth().getConsole().isRequired()
                && request.getRequestURI().startsWith(CONSOLE_API);
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        RequestContext.clear();
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            return null;
        }
        String token = header.substring(BEARER.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
